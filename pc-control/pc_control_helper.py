#!/usr/bin/env python3
"""A1111 Remote PC Controller — Windows-only, standard library, no arbitrary commands.

The API has four fixed actions. Keep the Windows firewall limited to your LAN /
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


def serve():
    if sys.platform != "win32":
        raise SystemExit("This helper runs on Windows only.")
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    if len(config.get("token", "")) < 32:
        raise SystemExit("Missing/weak token. Run setup again.")
    controller = Controller(config)

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
    args = parser.parse_args()
    if args.command == "setup":
        setup()
    else:
        serve()
