"""Authenticated read-only PC bridge for A1111 Remote.

Install this repository as an AUTOMATIC1111 extension (Extensions > Install from URL),
then restart WebUI. Requires --api and --api-auth USER:PASSWORD.
No secondary server, open port, or additional password is used.
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

from fastapi import Depends, HTTPException
from fastapi.responses import FileResponse, Response
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from PIL import Image, ImageOps

from modules import script_callbacks, shared
from modules.paths import data_path

_auth_scheme = HTTPBasic(auto_error=False)
_cache_lock = threading.Lock()
_cache = {"time": 0.0, "images": [], "by_id": {}}
_extensions = {".png", ".jpg", ".jpeg", ".webp"}


def _authorized(creds: HTTPBasicCredentials | None = Depends(_auth_scheme)):
    # Do not expose local images/params if --api-auth has not been configured.
    configured = getattr(shared.cmd_opts, "api_auth", None)
    if not configured:
        raise HTTPException(503, "PC bridge requires --api-auth USER:PASSWORD")
    if creds is not None:
        for entry in configured.split(","):
            username, separator, password = entry.partition(":")
            if separator and secrets.compare_digest(creds.username, username) and secrets.compare_digest(creds.password, password):
                return True
    raise HTTPException(401, "Invalid API credentials", headers={"WWW-Authenticate": "Basic"})


# Prevent Windows' automatic idle sleep for the entire A1111 WebUI session,
# including the time between generations. No dependency on Android connectivity.
# The execution-state request is thread-scoped and automatically disappears
# when the WebUI process exits. Explicit sleep / lid-close is not prevented.
_sleep_guard_started = False
_sleep_guard_lock = threading.Lock()


def _sleep_guard_loop():
    if os.name != "nt":
        return
    import ctypes

    es_continuous = 0x80000000
    es_system_required = 0x00000001
    kernel32 = ctypes.windll.kernel32
    kernel32.SetThreadExecutionState.argtypes = [ctypes.c_uint32]
    kernel32.SetThreadExecutionState.restype = ctypes.c_uint32

    enabled = False
    try:
        # This dedicated thread must remain alive: Windows attributes the
        # persistent power request to the calling thread, not the app as a whole.
        while True:
            result = kernel32.SetThreadExecutionState(
                es_continuous | es_system_required
            )
            if result:
                if not enabled:
                    print(
                        "[A1111 Remote] Windows automatic sleep prevention ACTIVE "
                        "for the entire A1111 session (display may turn off).",
                        flush=True,
                    )
                enabled = True
            else:
                enabled = False
                print(
                    "[A1111 Remote] WARNING: Could not register Windows "
                    "sleep prevention.",
                    flush=True,
                )
            # Renew/check periodically; do not clear on idle or between jobs.
            time.sleep(60)
    finally:
        # Executed on graceful thread exit; Windows also clears this
        # thread-scoped request when its process terminates.
        if enabled:
            kernel32.SetThreadExecutionState(es_continuous)


def _enable_sleep_guard():
    global _sleep_guard_started
    if os.name != "nt":
        return
    with _sleep_guard_lock:
        if _sleep_guard_started:
            return
        threading.Thread(
            target=_sleep_guard_loop,
            name="a1111-remote-sleep-guard",
            daemon=True,
        ).start()
        _sleep_guard_started = True


# Launch the optional PC controller alongside A1111, without another
# console window. No controller is launched before its setup exists.
_pc_helper_started = False
_pc_helper_lock = threading.Lock()


def _start_pc_helper():
    global _pc_helper_started
    if os.name != "nt":
        return
    with _pc_helper_lock:
        if _pc_helper_started:
            return
        _pc_helper_started = True
        helper = Path(__file__).resolve().parent.parent / "pc-control" / "pc_control_helper.py"
        config = Path(os.getenv("LOCALAPPDATA", str(Path.home()))) / "A1111Remote" / "controller.json"
        if not helper.is_file() or not config.is_file():
            print("[A1111 Remote] PC controller not configured; skipping auto-start.",
                  flush=True)
            return
        try:
            # If manually started, do not compete for the same port.
            with socket.create_connection(("127.0.0.1", 42001), timeout=0.4):
                print("[A1111 Remote] PC controller already listening on 42001.",
                      flush=True)
                return
        except OSError:
            pass
        try:
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
            subprocess.Popen(
                [sys.executable, str(helper), "serve",
                 "--parent-pid", str(os.getpid())],
                cwd=str(helper.parent),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=flags,
            )
            print("[A1111 Remote] PC controller launched silently with A1111.",
                  flush=True)
        except OSError as error:
            print(f"[A1111 Remote] PC controller auto-start failed: {error}",
                  flush=True)


def _roots():
    base = Path(data_path)
    opts = shared.opts
    common = getattr(opts, "outdir_samples", "") or ""
    candidates = []
    for option, default in (
        ("outdir_txt2img_samples", "txt2img-images"),
        ("outdir_img2img_samples", "img2img-images"),
        ("outdir_extras_samples", "extras-images"),
    ):
        configured = getattr(opts, option, "") or ""
        root = Path(configured or common or (base / "outputs" / default)).expanduser()
        if not root.is_absolute():
            root = (base / root)
        try:
            resolved = root.resolve()
            if resolved.is_dir() and resolved not in candidates:
                candidates.append(resolved)
        except OSError:
            continue
    return candidates


def _index():
    now = time.monotonic()
    with _cache_lock:
        if now - _cache["time"] < 15:
            return _cache
        newest = []
        for root in _roots():
            for folder, dirs, filenames in os.walk(root, followlinks=False):
                dirs[:] = [name for name in dirs if not name.startswith(".")]
                for name in filenames:
                    if Path(name).suffix.lower() not in _extensions:
                        continue
                    path = Path(folder) / name
                    try:
                        resolved = path.resolve()
                        if not resolved.is_relative_to(root) or not resolved.is_file():
                            continue
                        newest.append((resolved.stat().st_mtime_ns, resolved))
                    except OSError:
                        continue
        newest.sort(key=lambda entry: entry[0], reverse=True)
        items = []
        paths = {}
        for timestamp, path in newest[:80]:
            identifier = hashlib.sha256(str(path).encode("utf-8")).hexdigest()[:32]
            items.append({"id": identifier, "name": path.name, "modified": timestamp // 1_000_000})
            paths[identifier] = path
        _cache.update(time=now, images=items, by_id=paths)
        return _cache


def _find_image(identifier):
    if not re.fullmatch(r"[0-9a-f]{32}", identifier):
        raise HTTPException(404, "Image not found")
    path = _index()["by_id"].get(identifier)
    if path is None or not path.is_file():
        raise HTTPException(404, "Image not found")
    return path


def _mode_images(mode):
    option, default = {
        "txt2img": ("outdir_txt2img_samples", "txt2img-images"),
        "img2img": ("outdir_img2img_samples", "img2img-images"),
        "extras": ("outdir_extras_samples", "extras-images"),
    }[mode]
    configured = getattr(shared.opts, option, "") or ""
    common = getattr(shared.opts, "outdir_samples", "") or ""
    base = Path(data_path)
    root = Path(configured or common or (base / "outputs" / default)).expanduser()
    if not root.is_absolute():
        root = base / root
    root = root.resolve()
    if not root.is_dir():
        return []
    newest = []
    for folder, dirs, files in os.walk(root, followlinks=False):
        dirs[:] = [name for name in dirs if not name.startswith(".")]
        for name in files:
            if Path(name).suffix.lower() not in _extensions:
                continue
            path = (Path(folder) / name).resolve()
            try:
                if path.is_relative_to(root) and path.is_file():
                    newest.append((path.stat().st_mtime_ns, path))
            except OSError:
                continue
    return [item[1] for item in sorted(newest, reverse=True)[:30]]


def _last_parameters(mode: str = "txt2img"):
    if mode not in ("txt2img", "img2img", "extras"):
        raise HTTPException(400, "Unsupported mode")

    if mode == "extras":
        # Extras writes its postprocessing settings into the saved output
        # metadata, not the global txt2img params.txt. Load only real PC data.
        for path in _mode_images("extras"):
            try:
                with Image.open(path) as img:
                    info = str(img.info.get("extras") or img.info.get("postprocessing") or "")
                if not info or "Postprocess upscale" not in info:
                    continue
                scale_match = re.search(r"Postprocess upscale by:\\s*([0-9.]+)", info)
                upscaler_match = re.search(r"Postprocess upscaler:\\s*([^,]+)", info)
                return {
                    "scale": float(scale_match.group(1)) if scale_match else 2.0,
                    "upscaler": upscaler_match.group(1).strip() if upscaler_match else "Lanczos"
                }
            except (OSError, ValueError):
                continue
        raise HTTPException(404, "No Extras output with saved upscaler metadata on PC")

    info = ""
    if mode == "txt2img":
        file_path = Path(data_path) / "params.txt"
        if file_path.is_file():
            info = file_path.read_text(encoding="utf-8", errors="replace").strip()
    if not info:
        for path in _mode_images(mode):
            try:
                with Image.open(path) as img:
                    info = str(img.info.get("parameters") or "")
                if info:
                    break
            except OSError:
                continue
    if not info:
        raise HTTPException(404, f"No saved {mode} generation parameters on PC")
    try:
        from modules import infotext_utils as paste
    except ImportError:
        from modules import generation_parameters_copypaste as paste
    parsed = paste.parse_generation_parameters(info, [])
    def number(key, default, converter):
        try:
            return converter(parsed.get(key, default))
        except (ValueError, TypeError):
            return default
    # ADetailer writes its active tabs into PNG infotext as optional keys.
    # Export only fields that are actually present, so a missing field never
    # resets the corresponding Android setting to a guessed default.
    def _adetailer_tabs():
        tabs = []
        suffixes = ("", " 2nd", " 3rd", " 4th", " 5th")
        fields = {
            "model": ("ADetailer model",),
            "prompt": ("ADetailer prompt", "ad_prompt"),
            "negative_prompt": ("ADetailer negative prompt", "ad_negative_prompt"),
            "confidence": ("ADetailer confidence", "ADetailer detection confidence"),
            "top_k": ("ADetailer mask only top k", "ADetailer mask k",
                      "ADetailer top k", "ADetailer mask only the top k largest"),
            "denoising": ("ADetailer denoising strength",),
            "mask_blur": ("ADetailer mask blur",),
            "padding": ("ADetailer inpaint only masked padding",),
            "dilation": ("ADetailer mask erosion / dilation", "ADetailer dilate erode"),
            "only_masked": ("ADetailer inpaint only masked",),
        }
        lookup = {str(key).strip().casefold(): value for key, value in parsed.items()}
        for index, suffix in enumerate(suffixes):
            current = {}
            for name, aliases in fields.items():
                # Different versions of ADetailer use either unsuffixed first
                # tab or a "1st" suffix. Preserve other tabs' native suffixes.
                variants = [suffix] if index else ["", " 1st"]
                for alias in aliases:
                    for variant in variants:
                        key = (alias + variant).casefold()
                        if key in lookup:
                            current[name] = lookup[key]
                            break
                    if name in current:
                        break
            if current:
                tabs.append(current)
            elif index > 0:
                # Skip gaps without inventing placeholder tabs.
                continue
        return tabs

    return {
        "prompt": parsed.get("Prompt", ""),
        "negative_prompt": parsed.get("Negative prompt", ""),
        "steps": number("Steps", 25, int),
        "cfg_scale": number("CFG scale", 7.0, float),
        "sampler": parsed.get("Sampler", "Euler"),
        "scheduler": parsed.get("Schedule type", "Automatic"),
        "seed": number("Seed", -1, int),
        "width": number("Size-1", 512, int),
        "height": number("Size-2", 512, int),
        "model": parsed.get("Model", ""),
        "model_hash": parsed.get("Model hash", ""),
        "hires_enabled": "Hires upscale" in parsed or "Hires steps" in parsed,
        "hires_scale": number("Hires upscale", 1.5, float),
        "hires_steps": number("Hires steps", 0, int),
        "hires_denoising": number("Denoising strength", 0.4, float),
        "img2img_denoising": number("Denoising strength", 0.7, float),
        "adetailer": _adetailer_tabs(),
        "batch_count": number("Batch count", 1, int),
        "batch_size": number("Batch size", 1, int),
    }


def _preview(identifier):
    path = _find_image(identifier)
    try:
        with Image.open(path) as original:
            picture = ImageOps.exif_transpose(original)
            picture.thumbnail((512, 512))
            output = io.BytesIO()
            picture.convert("RGB").save(output, format="JPEG", quality=78, optimize=True)
            return Response(output.getvalue(), media_type="image/jpeg")
    except Exception:
        raise HTTPException(422, "Could not decode image")


def _add_routes(_demo, app):
    secure = [Depends(_authorized)]
    app.add_api_route("/sd-remote/v1/status", lambda: {"version": 1}, methods=["GET"], dependencies=secure)
    app.add_api_route("/sd-remote/v1/last", _last_parameters, methods=["GET"], dependencies=secure)
    app.add_api_route("/sd-remote/v1/images", lambda: {"images": _index()["images"]}, methods=["GET"], dependencies=secure)
    app.add_api_route("/sd-remote/v1/images/{identifier}/preview", _preview, methods=["GET"], dependencies=secure)
    app.add_api_route("/sd-remote/v1/images/{identifier}/file", lambda identifier: FileResponse(_find_image(identifier)), methods=["GET"], dependencies=secure)
    _enable_sleep_guard()
    _start_pc_helper()
    print("[A1111 Remote] PC history bridge active (API Basic authentication required).")


script_callbacks.on_app_started(_add_routes)
