#!/usr/bin/env python3
"""
Mac Host Build Daemon
Runs on the Mac host. Jailed to execute commands strictly within ~/.vm/.monet
Listens on port 9876 by default.
"""

import json
import os
import signal
import subprocess
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path

# Base directory on Mac host - commands are strictly jailed inside this root
BASE_DIR = Path(os.path.expanduser("~/.vm/.monet")).resolve()
PORT = int(os.environ.get("MAC_BUILD_PORT", 9876))


class BuildHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path in ("/", "/health"):
            body = json.dumps({
                "status": "ok",
                "message": "Mac Build Daemon is running",
                "base_dir": str(BASE_DIR)
            }, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path != "/exec":
            self.send_response(404)
            self.end_headers()
            return

        try:
            content_length = int(self.headers.get("Content-Length", 0))
            raw_data = self.rfile.read(content_length).decode("utf-8")
            req_data = json.loads(raw_data)
        except Exception as e:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(f"Invalid request JSON: {e}\n".encode())
            return

        rel_path = req_data.get("rel_path", "").strip()
        cmd = req_data.get("cmd", [])

        if not cmd or not isinstance(cmd, list):
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b"Error: 'cmd' must be a non-empty list of command arguments.\n")
            return

        # Security check: Target directory must be strictly inside BASE_DIR
        target_dir = (BASE_DIR / rel_path).resolve()
        try:
            target_dir.relative_to(BASE_DIR)
        except ValueError:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"Error: Path traversal outside ~/.vm/.monet is strictly forbidden.\n")
            return

        if not target_dir.is_dir():
            self.send_response(400)
            self.end_headers()
            self.wfile.write(f"Error: Target directory not found: {target_dir}\n".encode())
            return

        # Start chunked HTTP response
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def send_chunk(text: str):
            data = text.encode("utf-8")
            if data:
                self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
                self.wfile.flush()

        send_chunk(f"[Mac Host] CWD: {target_dir}\n")
        send_chunk(f"[Mac Host] CMD: {' '.join(cmd)}\n")
        send_chunk("=" * 60 + "\n\n")

        return_code = 1
        try:
            proc = subprocess.Popen(
                cmd,
                cwd=target_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                env=os.environ.copy()
            )

            for line in proc.stdout:
                send_chunk(line)

            proc.wait()
            return_code = proc.returncode
            send_chunk(f"\n" + "=" * 60 + "\n")
            send_chunk(f"[Mac Host] Finished with exit code: {return_code}\n")
        except Exception as e:
            send_chunk(f"\n[Mac Host] Execution failed: {e}\n")
            return_code = 127
        finally:
            # Protocol trailer: Sentinel line for client to extract exit code
            send_chunk(f"__EXIT_CODE__:{return_code}\n")
            # End of chunked stream
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()

    def log_message(self, format, *args):
        sys.stderr.write(f"[Daemon] {format % args}\n")


def run():
    signal.signal(signal.SIGINT, lambda s, f: sys.exit(0))
    server = HTTPServer(("0.0.0.0", PORT), BuildHandler)
    print("=" * 60)
    print(f" Mac Build Daemon is running on port {PORT}")
    print(f" Jailed directory: {BASE_DIR}")
    print(" Press Ctrl+C to stop.")
    print("=" * 60)
    server.serve_forever()


if __name__ == "__main__":
    run()
