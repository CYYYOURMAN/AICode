#!/usr/bin/env python3
"""AIcode Gateway —— 自托管 DSH 引擎的移动端网关（开源版）。

职责：
  1. 给手机 App 下发 DSH 的访问 token（从 DSH 日志/命令抓取，可配置）；
  2. 手机控制命令通道（App 轮询 /phone/cmd，执行后回报 /phone/result）；
  3. App 自更新通道（/app/version、/app/download）；
  4. 崩溃上报（/app/crash）；
  5. 配对：一次性配对码换长期密钥（App 首次连接用）。

安全模型：除 /health 外，所有接口要求 Authorization: Bearer <PAIR_SECRET>。
未配置密钥时 fail-closed（一律 401）。

环境变量：
  GATEWAY_PORT        监听端口，默认 3300
  GATEWAY_HOST        监听地址，默认 0.0.0.0
  GATEWAY_DATA_DIR    数据目录（secret、配对码、crash.log、app 通道），默认 /data
  GATEWAY_SECRET      显式指定共享密钥；不设则首次启动自动生成到 DATA_DIR/secret
  DSH_TOKEN_LOG       DSH 日志文件路径（网关从里面抓 "?token=xxx"），默认 /data/dsh.log
  DSH_TOKEN_CMD       可选：执行该命令、取 stdout 最后一行作为 token（优先于日志）
  DSH_BASE            下发给 App 的 DSH base URL（默认按请求 Host 推断）
  APP_CHANNEL_DIR     App 更新通道目录（含 stable/、beta/ 子目录），默认 DATA_DIR/app
  PHONE_ENABLED       是否启用手机控制命令通道，默认 1
  EXEC_ENABLED        是否启用 /exec 远程命令执行，默认 0（开源默认关闭）
  CRASH_MAX_BYTES     crash.log 超过该大小轮转，默认 1048576
"""
import hmac
import json
import os
import pathlib
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ── 配置 ──────────────────────────────────────────────────────────────
PORT = int(os.environ.get("GATEWAY_PORT", "3300"))
HOST = os.environ.get("GATEWAY_HOST", "0.0.0.0")
DATA = pathlib.Path(os.environ.get("GATEWAY_DATA_DIR", "/data"))
DATA.mkdir(parents=True, exist_ok=True)

SECRET_FILE = DATA / "secret"
PAIR_CODE_FILE = DATA / "pair-code"
CRASH_LOG = DATA / "crash.log"
APP_DIR = pathlib.Path(os.environ.get("APP_CHANNEL_DIR", str(DATA / "app")))
DSH_TOKEN_LOG = pathlib.Path(os.environ.get("DSH_TOKEN_LOG", str(DATA / "dsh.log")))
DSH_TOKEN_CMD = os.environ.get("DSH_TOKEN_CMD", "").strip()
DSH_BASE = os.environ.get("DSH_BASE", "").strip()
PHONE_ENABLED = os.environ.get("PHONE_ENABLED", "1") not in ("0", "false", "no")
EXEC_ENABLED = os.environ.get("EXEC_ENABLED", "0") in ("1", "true", "yes")
CRASH_MAX_BYTES = int(os.environ.get("CRASH_MAX_BYTES", "1048576"))


def load_secret() -> str:
    """共享密钥：环境变量 > 数据目录文件 > 自动生成。"""
    env = os.environ.get("GATEWAY_SECRET", "").strip()
    if env:
        return env
    try:
        s = SECRET_FILE.read_text().strip()
        if s:
            return s
    except Exception:
        pass
    s = secrets.token_hex(24)
    SECRET_FILE.write_text(s)
    os.chmod(SECRET_FILE, 0o600)
    return s


SECRET = load_secret()


def rotate_pair_code() -> str:
    """生成一次性配对码（6 位数字），落盘。"""
    code = str(secrets.randbelow(900000) + 100000)
    PAIR_CODE_FILE.write_text(code)
    os.chmod(PAIR_CODE_FILE, 0o600)
    return code


def pair_code() -> str:
    try:
        return PAIR_CODE_FILE.read_text().strip()
    except Exception:
        return rotate_pair_code()


# ── 手机控制命令队列 ───────────────────────────────────────────────────
PHONE_UI = {}
PHONE_CMDS = []
PHONE_RESULTS = {}
PHONE_SEQ = 0
PHONE_SEEN = 0.0
PHONE_LOCK = threading.Lock()


def enqueue(payload):
    global PHONE_SEQ
    with PHONE_LOCK:
        PHONE_SEQ += 1
        cid = PHONE_SEQ
        PHONE_CMDS.append({"id": cid, "cmd": payload})
        if len(PHONE_RESULTS) > 50:
            PHONE_RESULTS.clear()
    return cid


def phone_act(payload, timeout=12.0):
    steps = payload.get("steps")
    if payload.get("action") == "seq" and isinstance(steps, list):
        need = 4.0 + 2.0 * len(steps)
        for s in steps:
            if not isinstance(s, dict):
                continue
            if s.get("action") == "wait":
                need += float(s.get("timeout") or 6000) / 1000.0
            elif s.get("action") == "sleep":
                need += float(s.get("ms") or 500) / 1000.0
        timeout = max(timeout, min(need, 120.0))
    cid = enqueue(payload)
    deadline = time.time() + timeout
    while time.time() < deadline:
        with PHONE_LOCK:
            if cid in PHONE_RESULTS:
                return PHONE_RESULTS.pop(cid)
        time.sleep(0.02)
    with PHONE_LOCK:
        PHONE_RESULTS.pop(cid, None)
        PHONE_CMDS[:] = [c for c in PHONE_CMDS if c.get("id") != cid]
        online = (time.time() - PHONE_SEEN) < 6
    return {"id": cid, "ok": False,
            "error": "手机没响应（" + ("App 在后台被系统冻结？" if not online else "动作超时") + "）"}


# ── token 抓取 ─────────────────────────────────────────────────────────
_TOKEN_RE = re.compile(r"token=([A-Za-z0-9_\-]+)")


def latest_token() -> str:
    if DSH_TOKEN_CMD:
        try:
            out = subprocess.run(DSH_TOKEN_CMD, shell=True, capture_output=True, text=True, timeout=15)
            for line in reversed((out.stdout or "").splitlines()):
                token = line.strip()
                if token:
                    return token
        except Exception:
            pass
    try:
        # 只读日志尾部 1MB，避免大日志整文件进内存
        size = DSH_TOKEN_LOG.stat().st_size
        with open(DSH_TOKEN_LOG, "rb") as f:
            if size > 1_048_576:
                f.seek(-1_048_576, os.SEEK_END)
            text = f.read().decode("utf-8", errors="replace")
        found = _TOKEN_RE.findall(text)
        return found[-1] if found else ""
    except Exception:
        return ""


def app_base(handler) -> str:
    """返回 App 应该连接的 DSH 引擎地址。

    注意：这里返回的是 DSH（Web UI/API）的地址，不是网关自己的地址——
    App 的本地回环转发会把请求转到这里。默认按请求 Host 的主机 + DSH 端口推断；
    套了 HTTPS 反代时用 DSH_BASE 显式指定（如 https://aicode.example.com）。
    """
    if DSH_BASE:
        return DSH_BASE
    host = (handler.headers.get("Host") or "").split(":")[0].strip()
    if not host:
        host = str(handler.server.server_address[0])
    dsh_port = os.environ.get("DSH_PORT", "3080")
    return "http://" + host + ":" + dsh_port


def rotate_crash_log():
    try:
        if CRASH_LOG.stat().st_size <= CRASH_MAX_BYTES:
            return
        lines = CRASH_LOG.read_text(errors="replace").splitlines()[-500:]
        CRASH_LOG.write_text("\n".join(lines) + "\n")
    except Exception:
        pass


# ── HTTP 服务 ─────────────────────────────────────────────────────────
class Handler(BaseHTTPRequestHandler):
    def _send(self, body, code=200, ctype="application/json"):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(json.dumps(obj, ensure_ascii=False).encode(), code)

    def _drain_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length:
            try:
                self.rfile.read(length)
            except Exception:
                pass

    def _authed(self) -> bool:
        if not SECRET:
            return False
        got = self.headers.get("Authorization", "")
        return hmac.compare_digest(got, "Bearer " + SECRET)

    def _unauthorized(self):
        self._drain_body()
        self._json({"ok": False, "error": "unauthorized"}, 401)

    # ---- GET ----
    def do_GET(self):
        if self.path.startswith("/health"):
            ok_port = False
            try:
                with socket.create_connection(("127.0.0.1", int(os.environ.get("DSH_PORT", "3080"))), timeout=2):
                    ok_port = True
            except Exception:
                pass
            self._json({"ok": ok_port, "port": ok_port, "phone": PHONE_ENABLED,
                        "time": time.strftime("%Y-%m-%d %H:%M:%S")})
            return
        if not self._authed():
            self._unauthorized()
            return
        path = self.path
        if path.startswith("/pair/code"):
            # 管理员查看当前配对码（也可直接看 DATA_DIR/pair-code）
            self._json({"code": pair_code()})
            return
        if path.startswith("/engine"):
            try:
                self._send((DATA / "engine-update.json").read_bytes())
            except Exception:
                self._send(b"{}")
            return
        if path.startswith("/app/version"):
            channel = "beta" if "channel=beta" in path else "stable"
            try:
                self._send((APP_DIR / channel / "version.json").read_bytes())
            except Exception:
                self._json({"versionCode": 0, "versionName": "", "size": 0})
            return
        if path.startswith("/app/download"):
            channel = "beta" if "channel=beta" in path else "stable"
            try:
                data = (APP_DIR / channel / "aicode-latest.apk").read_bytes()
            except Exception:
                self._send(b"{}", 404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="aicode.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if path.startswith("/phone/ui"):
            with PHONE_LOCK:
                self._send(json.dumps(PHONE_UI, ensure_ascii=False).encode())
            return
        if path.startswith("/phone/cmd"):
            global PHONE_SEEN
            with PHONE_LOCK:
                PHONE_SEEN = time.time()
                item = PHONE_CMDS.pop(0) if PHONE_CMDS else None
            self._json({"id": 0, "cmd": None} if item is None else {"id": item["id"], "cmd": item["cmd"]})
            return
        if path.startswith("/phone/state"):
            with PHONE_LOCK:
                ui = dict(PHONE_UI)
                online = (time.time() - PHONE_SEEN) < 6
                pending = len(PHONE_CMDS)
            self._json({"online": online, "a11y": bool(ui.get("a11y")), "overlay": bool(ui.get("overlay")),
                        "package": ui.get("package", ""), "elements": len(ui.get("elements") or []),
                        "pending": pending})
            return
        # 默认：给 App 的登录信息
        self._json({"token": latest_token(), "base": app_base(self),
                    "phone": PHONE_ENABLED, "exec": EXEC_ENABLED})

    # ---- POST ----
    def do_POST(self):
        if not self._authed():
            # 配对例外：用一次性码换密钥
            if self.path.startswith("/pair/claim"):
                self._handle_pair()
                return
            self._unauthorized()
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        if self.path.startswith("/app/crash"):
            try:
                data = json.loads(raw) if raw else {}
                rotate_crash_log()
                with open(CRASH_LOG, "a", encoding="utf-8") as f:
                    f.write(json.dumps(data, ensure_ascii=False) + "\n")
                self._json({"ok": True})
            except Exception as e:
                self._json({"ok": False, "error": str(e)})
            return
        if self.path.startswith("/exec"):
            if not EXEC_ENABLED:
                self._json({"code": -1, "output": "exec disabled by server config"}, 403)
                return
            try:
                data = json.loads(raw) if raw else {}
                cmd = str(data.get("cmd", "")).strip()
                if not cmd:
                    raise ValueError("empty command")
                p = subprocess.run(cmd, shell=True, cwd=os.environ.get("EXEC_CWD", "/data"),
                                   capture_output=True, text=True, timeout=60)
                self._json({"code": p.returncode, "output": (p.stdout or "") + (p.stderr or "")})
            except subprocess.TimeoutExpired:
                self._json({"code": -1, "output": "命令超时（60 秒）"})
            except Exception as e:
                self._json({"code": -1, "output": "错误：%s" % e})
            return
        if self.path.startswith("/phone/ui"):
            try:
                with PHONE_LOCK:
                    PHONE_UI.clear()
                    PHONE_UI.update(json.loads(raw))
                    PHONE_UI["_at"] = time.time()
                self._json({"ok": True})
            except Exception as e:
                self._json({"ok": False, "error": str(e)})
            return
        if self.path.startswith("/phone/result"):
            try:
                data = json.loads(raw)
                cid = int(data.get("id", 0) or 0)
                with PHONE_LOCK:
                    PHONE_RESULTS[cid] = data
                self._json({"ok": True})
            except Exception as e:
                self._json({"ok": False, "error": str(e)})
            return
        if self.path.startswith("/phone/act"):
            if not PHONE_ENABLED:
                self._json({"ok": False, "error": "phone control disabled"}, 403)
                return
            try:
                data = json.loads(raw) if raw else {}
                timeout = float(data.pop("_timeout", 12.0))
                self._json(phone_act(data, timeout=timeout))
            except Exception as e:
                self._json({"ok": False, "error": str(e)})
            return
        if self.path.startswith("/phone/do"):
            try:
                cid = enqueue(json.loads(raw))
                self._json({"ok": True, "queued": True, "id": cid})
            except Exception as e:
                self._json({"ok": False, "error": str(e)})
            return
        self._send(b"{}", 404)

    def _handle_pair(self):
        """一次性配对码换长期密钥。请求 {"code": "123456"}。"""
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw) if raw else {}
            got = str(data.get("code", "")).strip()
        except Exception:
            got = ""
        current = pair_code()
        if got and hmac.compare_digest(got, current):
            rotate_pair_code()  # 一次性
            self._json({"ok": True, "secret": SECRET})
        else:
            self._json({"ok": False, "error": "配对码错误或已使用"}, 401)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print("AIcode Gateway 监听 %s:%d" % (HOST, PORT), flush=True)
    print("配对码：%s（文件 %s）" % (pair_code(), PAIR_CODE_FILE), flush=True)
    print("共享密钥文件：%s" % SECRET_FILE, flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
