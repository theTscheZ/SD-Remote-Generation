#!/usr/bin/env python3
"""A1111 Remote PC Controller — Windows-only, standard library, no arbitrary commands.

The API exposes only explicit, token-authenticated power and WebUI actions. Keep the Windows firewall limited to your LAN /
Tailscale interface and never forward this port on your router.
"""
import argparse
import hmac
import http.server
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time

CONFIG_PATH = Path(os.getenv("LOCALAPPDATA", str(Path.home()))) / "A1111Remote" / "controller.json"
DEFAULT_PORT = 42001


def setup():
    if sys.platform != "win32":
        raise SystemExit("Setup needs a Windows PC.")
    print("A1111 Remote — PC control helper setup")
    raw = input("Full path to webui-user.bat: ").strip().strip('"')
    script = Path(raw).expanduser().resolve(strict=True)
    if script.name.lower() != "webui-user.bat" or not script.is_file():
        raise SystemExit("Choose your existing webui-user.bat file.")
    port_text = input("A1111 port [42000]: ").strip() or "42000"
    webui_port = int(port_text)
    if not 1 <= webui_port <= 65535:
        raise SystemExit("Invalid A1111 port.")
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(32)
    config = {"webui_bat": str(script), "webui_port": webui_port,
              "controller_port": DEFAULT_PORT, "token": token}
    # Owner-readable user profile; on shared Windows accounts secure the account itself.
    with CONFIG_PATH.open("w", encoding="utf-8") as file:
        json.dump(config, file, indent=2)
    print("\nSetup saved at:", CONFIG_PATH)
    print("Controller port:", DEFAULT_PORT)
    print("Copy this token into the Android app's PC Control settings:")
    print(token)
    print("\nRun: py pc_control_helper.py serve")
    print("Allow TCP port 42001 in Windows Firewall ONLY for your private LAN/Tailscale.")
    print("Do not expose the port to the internet.")


def private_peer(ip):
    try:
        addr = ipaddress.ip_address(ip)
        return addr.is_private or addr in ipaddress.ip_network("100.64.0.0/10")
    except ValueError:
        return False


class Controller:
    def __init__(self, config):
        self.config = config
        self.child = None
        self.lock = threading.Lock()
        self.shutdown_pending = False

    def reachable(self):
        try:
            with socket.create_connection(("127.0.0.1", self.config["webui_port"]), timeout=1):
                return True
        except OSError:
            return False

    def status(self):
        with self.lock:
            managed = self.child is not None and self.child.poll() is None
            return {"pc_online": True, "a1111_reachable": self.reachable(),
                    "a1111_managed": managed, "shutdown_pending": self.shutdown_pending}

    def start(self):
        with self.lock:
            if self.reachable():
                return {"message": "A1111 is already listening on its port."}
            if self.child is not None and self.child.poll() is None:
                return {"message": "A1111 is already starting."}
            script = Path(self.config["webui_bat"])
            if not script.is_file() or script.name.lower() != "webui-user.bat":
                raise ValueError("webui-user.bat not found; rerun setup.")
            self.child = subprocess.Popen(
                ["cmd.exe", "/c", str(script)], cwd=str(script.parent),
                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP
            )
            return {"message": "A1111 startup requested; it can take several minutes."}

    def stop(self):
        with self.lock:
            if self.child is None or self.child.poll() is not None:
                raise ValueError("A1111 was not started by this helper. Refusing to stop an unrelated process.")
            pid = self.child.pid
            subprocess.run(["taskkill.exe", "/PID", str(pid), "/T", "/F"],
                           check=True, capture_output=True, timeout=15)
            self.child = None
            return {"message": "Helper-managed A1111 process stopped."}

    def sleep(self):
        """Schedule normal Windows sleep after acknowledging the HTTP request.

        Wake events stay ENABLED, so Ethernet Wake-on-LAN can resume the PC.
        A short delay lets the Android client receive the acknowledgement.
        """
        with self.lock:
            if self.shutdown_pending:
                raise ValueError("Cancel the pending shutdown before sleeping.")

            def suspend_after_ack():
                time.sleep(1.5)
                try:
                    import ctypes
                    from ctypes import wintypes

                    # SetSuspendState requires SeShutdownPrivilege enabled in
                    # the process token. Standard signed-in users normally have
                    # this privilege available, but it is initially disabled.
                    TOKEN_ADJUST_PRIVILEGES = 0x0020
                    TOKEN_QUERY = 0x0008
                    SE_PRIVILEGE_ENABLED = 0x00000002

                    class LUID(ctypes.Structure):
                        _fields_ = [("LowPart", wintypes.DWORD),
                                    ("HighPart", wintypes.LONG)]

                    class LUID_AND_ATTRIBUTES(ctypes.Structure):
                        _fields_ = [("Luid", LUID),
                                    ("Attributes", wintypes.DWORD)]

                    class TOKEN_PRIVILEGES(ctypes.Structure):
                        _fields_ = [("PrivilegeCount", wintypes.DWORD),
                                    ("Privileges", LUID_AND_ATTRIBUTES * 1)]

                    advapi32 = ctypes.WinDLL("advapi32", use_last_error=True)
                    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                    powrprof = ctypes.WinDLL("powrprof", use_last_error=True)
                    token = wintypes.HANDLE()
                    advapi32.OpenProcessToken.argtypes = [
                        wintypes.HANDLE, wintypes.DWORD,
                        ctypes.POINTER(wintypes.HANDLE)]
                    advapi32.OpenProcessToken.restype = wintypes.BOOL
                    advapi32.LookupPrivilegeValueW.argtypes = [
                        wintypes.LPCWSTR, wintypes.LPCWSTR,
                        ctypes.POINTER(LUID)]
                    advapi32.LookupPrivilegeValueW.restype = wintypes.BOOL
                    advapi32.AdjustTokenPrivileges.argtypes = [
                        wintypes.HANDLE, wintypes.BOOL,
                        ctypes.POINTER(TOKEN_PRIVILEGES), wintypes.DWORD,
                        ctypes.c_void_p, ctypes.c_void_p]
                    advapi32.AdjustTokenPrivileges.restype = wintypes.BOOL
                    kernel32.GetCurrentProcess.restype = wintypes.HANDLE
                    kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
                    powrprof.SetSuspendState.argtypes = [
                        ctypes.c_ubyte, ctypes.c_ubyte, ctypes.c_ubyte]
                    powrprof.SetSuspendState.restype = ctypes.c_ubyte

                    if not advapi32.OpenProcessToken(
                        kernel32.GetCurrentProcess(),
                        TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY,
                        ctypes.byref(token)
                    ):
                        raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        privileges = TOKEN_PRIVILEGES()
                        privileges.PrivilegeCount = 1
                        if not advapi32.LookupPrivilegeValueW(
                            None, "SeShutdownPrivilege",
                            ctypes.byref(privileges.Privileges[0].Luid)
                        ):
                            raise ctypes.WinError(ctypes.get_last_error())
                        privileges.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED
                        if not advapi32.AdjustTokenPrivileges(
                            token, False, ctypes.byref(privileges),
                            0, None, None
                        ):
                            raise ctypes.WinError(ctypes.get_last_error())
                        if ctypes.get_last_error() == 1300:
                            raise PermissionError(
                                "SeShutdownPrivilege unavailable for controller user")
                        # Hibernate=False, force=False, disable wake events=False.
                        if not powrprof.SetSuspendState(0, 0, 0):
                            raise ctypes.WinError(ctypes.get_last_error())
                    finally:
                        kernel32.CloseHandle(token)
                except Exception as error:
                    print("[A1111 Remote] Windows sleep FAILED:", error, flush=True)

            threading.Thread(
                target=suspend_after_ack,
                name="a1111-remote-sleep",
                daemon=True
            ).start()
            return {"message": "Windows sleep requested. Wake events remain enabled."}

    def shutdown(self):
        if self.shutdown_pending:
            raise ValueError("Shutdown is already scheduled.")
        # 30 seconds to give the user time to cancel and avoid abrupt shutdown.
        subprocess.run(["shutdown.exe", "/s", "/t", "30"], check=True, timeout=10)
        self.shutdown_pending = True
        return {"message": "Windows will shut down in 30 seconds. Use Cancel Shutdown if necessary."}

    def cancel_shutdown(self):
        subprocess.run(["shutdown.exe", "/a"], check=True, timeout=10)
        self.shutdown_pending = False
        return {"message": "Windows shutdown cancelled."}


def _exit_with_parent(parent_pid):
    """Exit helper when the WebUI process that launched it terminates."""
    if sys.platform != "win32" or parent_pid is None:
        return
    import ctypes
    from ctypes import wintypes

    SYNCHRONIZE = 0x00100000
    handle = ctypes.windll.kernel32.OpenProcess(SYNCHRONIZE, False, parent_pid)
    if not handle:
        print("[A1111 Remote] Parent already exited; stopping helper.", flush=True)
        os._exit(0)

    def wait_for_parent():
        try:
            ctypes.windll.kernel32.WaitForSingleObject(handle, 0xFFFFFFFF)
        finally:
            ctypes.windll.kernel32.CloseHandle(handle)
        print("[A1111 Remote] A1111 exited; stopping PC helper.", flush=True)
        os._exit(0)

    threading.Thread(target=wait_for_parent, daemon=True,
                     name="a1111-parent-monitor").start()


def serve(parent_pid=None):
    if sys.platform != "win32":
        raise SystemExit("This helper runs on Windows only.")
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if len(config.get("token", "")) < 32:
        raise SystemExit("Missing/weak token. Run setup again.")
    controller = Controller(config)
    _exit_with_parent(parent_pid)

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Never log credential headers.
            print("%s %s" % (self.address_string(), fmt % args), flush=True)

        def respond(self, code, payload):
            data = json.dumps(payload).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            if not private_peer(self.client_address[0]):
                self.respond(403, {"error": "Private network only."})
                return False
            token = self.headers.get("X-Controller-Token", "")
            if not hmac.compare_digest(token, config["token"]):
                self.respond(401, {"error": "Invalid controller token."})
                return False
            return True

        def do_GET(self):
            if not self.authorized():
                return
            if self.path != "/v1/status":
                self.respond(404, {"error": "Not found."})
                return
            self.respond(200, controller.status())

        def do_POST(self):
            if not self.authorized():
                return
            if self.headers.get("Content-Length") not in ("0", None):
                self.respond(400, {"error": "No request body accepted."})
                return
            actions = {"/v1/start": controller.start, "/v1/stop": controller.stop,
                       "/v1/sleep": controller.sleep,
                       "/v1/shutdown": controller.shutdown,
                       "/v1/shutdown/cancel": controller.cancel_shutdown}
            action = actions.get(self.path)
            if action is None:
                self.respond(404, {"error": "Unknown action."})
                return
            try:
                self.respond(200, action())
            except (ValueError, OSError, subprocess.SubprocessError) as error:
                self.respond(409, {"error": str(error)})

    server = http.server.ThreadingHTTPServer(("0.0.0.0", int(config["controller_port"])), Handler)
    server.daemon_threads = True
    print("A1111 PC Controller listening on port", config["controller_port"])
    print("Private firewall rules required. Never port-forward this service.")
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("Stopping controller.")
    finally:
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("setup", "serve"))
    parser.add_argument("--parent-pid", type=int, default=None,
                        help="Exit automatically when this A1111 process exits.")
    args = parser.parse_args()
    if args.command == "setup":
        setup()
    else:
        serve(args.parent_pid)
