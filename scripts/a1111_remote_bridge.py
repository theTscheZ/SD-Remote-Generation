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
        "hires_denoising": number("Denoising strength", 0.4, float),
        "img2img_denoising": number("Denoising strength", 0.7, float),
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
    print("[A1111 Remote] PC history bridge active (API Basic authentication required).")


script_callbacks.on_app_started(_add_routes)
