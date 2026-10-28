#!/usr/bin/env python3
"""
Mac Host Command Daemon
Runs on the Mac host. Jailed to execute commands strictly within ~/.vm/.monet.
Guarded by command whitelist and interactive macOS approval prompt.
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
PORT = int(os.environ.get("MAC_COMMAND_PORT", 9876))
WHITELIST_FILE = Path(os.path.expanduser("~/.vm/command_whitelist.json"))
STRICT_MODE = os.environ.get("MAC_COMMAND_STRICT", "0") in ("1", "true", "True")

DANGEROUS_TOKENS = {"&&", "||", ";", "|", "&", ">", "<", ">>", "`"}
SHELL_WRAPPERS = {"sh", "bash", "zsh", "dash", "csh", "tcsh", "fish"}

# 审批面板给出的前缀层级深度（git / git add / ...），完整命令本身不计入
MAX_PREFIX_LEVELS = int(os.environ.get("MAC_COMMAND_MAX_PREFIX_LEVELS", "2"))
APPROVAL_TIMEOUT = int(os.environ.get("MAC_COMMAND_APPROVAL_TIMEOUT", "120"))
# 仅用于在面板上提示用户，不作为拦截依据
SUSPICIOUS_SUBSTRINGS = ("&&", "||", "|&", "|", "$(", "`", ">", "<", ";")


def load_whitelist() -> dict:
    if not WHITELIST_FILE.exists():
        return {"exact_commands": [], "allowed_prefixes": []}
    try:
        with open(WHITELIST_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            if not isinstance(data, dict):
                return {"exact_commands": [], "allowed_prefixes": []}
            # Clean and validate rules: never allow empty prefix/command to match everything
            exact = [c for c in data.get("exact_commands", []) if isinstance(c, list) and len(c) > 0]
            prefixes = [p for p in data.get("allowed_prefixes", []) if isinstance(p, list) and len(p) > 0]
            return {"exact_commands": exact, "allowed_prefixes": prefixes}
    except Exception as e:
        sys.stderr.write(f"[Daemon] Warning: Failed to parse whitelist: {e}\n")
        return {"exact_commands": [], "allowed_prefixes": []}


def save_whitelist(whitelist: dict):
    WHITELIST_FILE.parent.mkdir(parents=True, exist_ok=True)
    temp_file = WHITELIST_FILE.with_suffix(".tmp")
    with open(temp_file, "w", encoding="utf-8") as f:
        json.dump(whitelist, f, indent=2, ensure_ascii=False)
    temp_file.replace(WHITELIST_FILE)


def is_whitelisted(cmd: list, whitelist: dict) -> bool:
    if not cmd:
        return False

    # 1. Exact command match
    if cmd in whitelist.get("exact_commands", []):
        return True

    # 2. Allowed prefix match (prefix MUST be non-empty)
    for prefix in whitelist.get("allowed_prefixes", []):
        if isinstance(prefix, list) and len(prefix) > 0 and len(cmd) >= len(prefix):
            if cmd[:len(prefix)] == prefix:
                return True
    return False


def validate_safety(cmd: list) -> tuple[bool, str]:
    if not cmd or not isinstance(cmd, list):
        return False, "Command must be a non-empty list of arguments."

    bin_name = Path(cmd[0]).name
    # Prevent shell wrapper evasion like ["sh", "-c", "open ... && evil"]
    if bin_name in SHELL_WRAPPERS:
        if any(arg in ("-c", "-s") or arg.startswith("-c") for arg in cmd[1:]):
            return False, f"Running commands via shell wrapper '{bin_name} -c' is strictly forbidden."

    # Prevent shell metacharacters in arguments
    for arg in cmd:
        if not isinstance(arg, str):
            return False, "All command arguments must be strings."
        if arg in DANGEROUS_TOKENS:
            return False, f"Shell control operator '{arg}' is strictly forbidden in arguments."
        if any(c in arg for c in ("\n", "\r", ";")):
            return False, f"Suspicious character or newline found in argument: {repr(arg)}"

    return True, ""


def applescript_literal(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"')


def shorten(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 3] + "..."


def build_prefix_options(cmd: list) -> list:
    """按命令层级生成白名单候选：git / git add / ...，最深一级即完整命令。

    display alert 最多 3 个按钮，留一个给「取消」，所以最多 2 级。
    """
    limit = min(MAX_PREFIX_LEVELS, 2, len(cmd))
    return [cmd[:i] for i in range(1, limit + 1)]


def run_osascript(script: str, timeout_seconds: int):
    """跑一段 AppleScript，返回 CompletedProcess（不抛异常，调用方自己判断）。"""
    return subprocess.run(
        ["osascript", "-e", script],
        capture_output=True,
        text=True,
        timeout=timeout_seconds + 5,
    )


def ask_deny_reason() -> str:
    """拒绝后收一个原因，用于回传给 VM 侧。"""
    script = (
        'tell application "System Events" to activate\n'
        'set theResult to display dialog "请填写拒绝原因（可留空）：" '
        'default answer "" with title "Mac Command Bridge：拒绝执行" '
        'buttons {"跳过", "提交"} default button "提交" giving up after 60\n'
        'return text returned of theResult\n'
    )
    try:
        reason = run_osascript(script, 60).stdout.strip()
    except Exception as e:
        sys.stderr.write(f"[Daemon] Deny reason prompt failed: {e}\n")
        reason = ""
    reason = " ".join(reason.split())
    return reason or "用户拒绝，未填写原因"


def run_approval_alert(title: str, message_expr: str, buttons: list, default_button: int,
                       cancel_button: int, timeout_seconds: int):
    """弹一个原生 alert。

    返回 (result, error_detail)，result ∈ 按钮标题 / "__TIMEOUT__" / "__CANCEL__" / "__ERROR__"。
    display alert 的 cancel 按钮不返回标题，而是抛 User canceled (-128)，需单独识别。
    """
    btns = "{ " + ", ".join(f'"{applescript_literal(b)}"' for b in buttons) + " }"
    script = (
        'tell application "System Events" to activate\n'
        f"set theMessage to {message_expr}\n"
        f'set r to display alert "{applescript_literal(title)}" message theMessage '
        f"buttons {btns} default button {default_button} cancel button {cancel_button} "
        f"giving up after {timeout_seconds}\n"
        'if gave up of r then return "__TIMEOUT__"\n'
        "return button returned of r\n"
    )
    try:
        res = subprocess.run(
            ["osascript", "-e", script],
            capture_output=True,
            text=True,
            timeout=timeout_seconds + 5,
        )
    except subprocess.TimeoutExpired:
        return "__TIMEOUT__", None
    if res.returncode == 0:
        return res.stdout.strip(), None

    detail = (res.stderr or res.stdout).strip().splitlines()
    detail = detail[-1][:200] if detail else "unknown error"
    if "User canceled" in detail or "(-128)" in detail:
        return "__CANCEL__", detail
    sys.stderr.write(f"[Daemon] Approval alert failed: {detail}\n")
    return "__ERROR__", detail


def ask_mac_approval(cmd: list, rel_path: str, timeout_seconds: int = None):
    """两步弹窗：先选大类（仅本次 / 加入白名单 / 拒绝），再选白名单范围。

    返回 (decision, value)：
      ("allow_prefix", prefix)  把该前缀写入白名单，并执行本次命令
      ("allow_once", None)      仅执行本次，不写入白名单
      ("deny", reason)          拒绝，reason 为 Mac 侧填写的原因
    """
    timeout_seconds = timeout_seconds or APPROVAL_TIMEOUT

    once_label = "仅本次运行"
    whitelist_label = "加入白名单…"
    deny_label = "拒绝并说明原因"

    dangerous = [tok for tok in SUSPICIOUS_SUBSTRINGS if any(tok in arg for arg in cmd)]
    lines = [
        "Linux VM 请求在 Mac 宿主上执行命令：",
        f"命令：{shorten(' '.join(cmd), 500)}",
        f"路径：{rel_path}",
    ]
    if dangerous:
        lines.append(
            f"注意：参数中含 shell 元字符 {' '.join(sorted(set(dangerous)))}，"
            "本桥不走 shell，将按字面传入。"
        )
    message_expr = " & linefeed & linefeed & ".join(
        f'"{applescript_literal(line)}"' for line in lines
    )

    picked, error = run_approval_alert(
        "Mac Command Bridge：命令审批",
        message_expr,
        [once_label, whitelist_label, deny_label],
        default_button=1,
        cancel_button=3,
        timeout_seconds=timeout_seconds,
    )

    if picked == "__ERROR__":
        return "deny", f"审批弹窗执行失败：{error}"
    if picked == "__TIMEOUT__":
        sys.stderr.write(f"[Daemon] Approval alert timed out after {timeout_seconds}s\n")
        return "deny", f"审批弹窗超时（{timeout_seconds}s），已中止"
    if picked == "__CANCEL__" or picked == deny_label:
        return "deny", ask_deny_reason()
    if picked == once_label:
        return "allow_once", None

    # 加入白名单：第二层弹窗，版式与第一层一致，选项用通配写法「允许 X *」/「允许 X Y *」
    prefixes = build_prefix_options(cmd)
    range_buttons = ["取消"] + [f"允许 {' '.join(prefix)} *" for prefix in prefixes]
    range_message = " & linefeed & linefeed & ".join(
        f'"{applescript_literal(line)}"'
        for line in [f"命令：{shorten(' '.join(cmd), 500)}", f"路径：{rel_path}"]
    )
    # 默认停在最窄的一级，避免误点成宽前缀
    picked, error = run_approval_alert(
        "Mac Command Bridge：加入白名单",
        range_message,
        range_buttons,
        default_button=len(prefixes) + 1,
        cancel_button=1,
        timeout_seconds=timeout_seconds,
    )

    if picked == "__ERROR__":
        return "deny", f"白名单弹窗执行失败：{error}"
    if picked == "__TIMEOUT__":
        return "deny", f"白名单弹窗超时（{timeout_seconds}s），已中止"
    if picked == "__CANCEL__" or picked == "取消":
        return "deny", "未选择白名单范围，已中止"
    for index, label in enumerate(range_buttons[1:]):
        if picked == label:
            return "allow_prefix", prefixes[index]
    return "deny", "白名单范围选项无法识别，已中止"


class CommandHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path in ("/", "/health"):
            whitelist = load_whitelist()
            body = json.dumps({
                "status": "ok",
                "message": "Mac Command Daemon is running",
                "base_dir": str(BASE_DIR),
                "strict_mode": STRICT_MODE,
                "whitelist_rules": {
                    "exact_count": len(whitelist.get("exact_commands", [])),
                    "prefix_count": len(whitelist.get("allowed_prefixes", []))
                }
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

        # Step 1: Structural and safety validation
        safe, err_msg = validate_safety(cmd)
        if not safe:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(f"Security Error: {err_msg}\n".encode())
            return

        # Step 2: Directory sandbox check
        target_dir = (BASE_DIR / rel_path).resolve()
        try:
            target_dir.relative_to(BASE_DIR)
        except ValueError:
            self.send_response(403)
            self.end_headers()
            self.wfile.write(b"Security Error: Path traversal outside ~/.vm/.monet is strictly forbidden.\n")
            return

        if not target_dir.is_dir():
            self.send_response(400)
            self.end_headers()
            self.wfile.write(f"Error: Target directory not found: {target_dir}\n".encode())
            return

        # Step 3: Whitelist & Approval check
        whitelist = load_whitelist()
        if not is_whitelisted(cmd, whitelist):
            if STRICT_MODE:
                self.send_response(403)
                self.end_headers()
                self.wfile.write(b"Forbidden: Command not in whitelist and STRICT_MODE is enabled.\n")
                return

            decision, value = ask_mac_approval(cmd, rel_path)
            if decision == "allow_prefix":
                whitelist.setdefault("allowed_prefixes", []).append(value)
                save_whitelist(whitelist)
                sys.stderr.write(f"[Daemon] Whitelisted prefix: {' '.join(value)}\n")
            elif decision == "allow_once":
                sys.stderr.write(f"[Daemon] Allowed once: {' '.join(cmd)}\n")
            else:
                reason = value or "未提供原因"
                sys.stderr.write(f"[Daemon] Denied: {' '.join(cmd)} | reason: {reason}\n")
                self.send_response(403)
                self.end_headers()
                self.wfile.write(f"Forbidden: Rejected on Mac host. Reason: {reason}\n".encode("utf-8"))
                return

        # Step 4: Execute command with chunked streaming
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
        proc = None
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
            send_chunk("\n" + "=" * 60 + "\n")
            send_chunk(f"[Mac Host] Finished with exit code: {return_code}\n")
        except (BrokenPipeError, ConnectionResetError):
            # Client disconnected (e.g. user pressed Ctrl+C)
            if proc and proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    proc.kill()
            return
        except Exception as e:
            send_chunk(f"\n[Mac Host] Execution failed: {e}\n")
            return_code = 127
        finally:
            try:
                send_chunk(f"__EXIT_CODE__:{return_code}\n")
                self.wfile.write(b"0\r\n\r\n")
                self.wfile.flush()
            except Exception:
                pass

    def log_message(self, format, *args):
        sys.stderr.write(f"[Daemon] {format % args}\n")


def manage_whitelist(action: str, args: list):
    whitelist = load_whitelist()
    if action == "list":
        print(f"Whitelist path: {WHITELIST_FILE}")
        print("\n[Exact Commands]")
        for i, cmd in enumerate(whitelist.get("exact_commands", [])):
            print(f"  {i + 1}. {' '.join(cmd)}")
        print("\n[Allowed Prefixes]")
        for i, pfx in enumerate(whitelist.get("allowed_prefixes", [])):
            print(f"  {i + 1}. {' '.join(pfx)} *")
    elif action == "add-prefix" and args:
        prefix = args
        if prefix not in whitelist["allowed_prefixes"]:
            whitelist["allowed_prefixes"].append(prefix)
            save_whitelist(whitelist)
            print(f"Added prefix: {' '.join(prefix)} *")
        else:
            print("Prefix already exists.")
    elif action == "add-exact" and args:
        cmd = args
        if cmd not in whitelist["exact_commands"]:
            whitelist["exact_commands"].append(cmd)
            save_whitelist(whitelist)
            print(f"Added exact command: {' '.join(cmd)}")
        else:
            print("Command already exists.")
    elif action == "clear":
        save_whitelist({"exact_commands": [], "allowed_prefixes": []})
        print("Cleared whitelist.")
    else:
        print("Usage:")
        print("  mac_command_daemon.py whitelist list")
        print("  mac_command_daemon.py whitelist add-prefix <binary> [args...]")
        print("  mac_command_daemon.py whitelist add-exact <binary> [args...]")
        print("  mac_command_daemon.py whitelist clear")


def run():
    if len(sys.argv) > 1 and sys.argv[1] == "whitelist":
        action = sys.argv[2] if len(sys.argv) > 2 else "list"
        manage_whitelist(action, sys.argv[3:])
        return

    signal.signal(signal.SIGINT, lambda s, f: sys.exit(0))
    server = HTTPServer(("0.0.0.0", PORT), CommandHandler)
    print("=" * 60)
    print(f" Mac Command Daemon is running on port {PORT}")
    print(f" Jailed directory: {BASE_DIR}")
    print(f" Whitelist file:  {WHITELIST_FILE}")
    print(f" Strict mode:     {'ON (no prompts)' if STRICT_MODE else 'OFF (interactive prompt)'}")
    print(" Press Ctrl+C to stop.")
    print("=" * 60)
    server.serve_forever()


if __name__ == "__main__":
    run()
