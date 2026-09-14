#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 公网中继服务器 (部署在有公网 IP 的服务器 / VPS / Linux 主机上)
========================================================================
让"手机 -> 自己的 Windows 电脑"这件事变成:所有人打开【同一个网址】,
手机(任意网络,iOS/安卓均可)在中继页面上选自己的电脑 + 输入口令,
按键/文本经本中继转发到那台电脑上常驻运行的 agent.py,由 agent 注入按键。

架构:
  每台 Windows 电脑:  python agent.py --relay ws://本服务器:9000 --name 名字 --pin 口令
  手机/浏览器:        打开 http://本服务器:9001/  → 选择电脑 → 输入口令 → 开打

依赖: 自带 vendor/websockets,无需 pip install(Python 3.8+);生产环境建议
套一层 nginx/caddy 提供 HTTPS(见 deploy/)。

用法:
  python3 relay.py                  # HTTP 9001 / WS 9000
  python3 relay.py --http-port 9001 --ws-port 9000
"""
import argparse
import asyncio
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_VENDOR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "vendor")
sys.path.insert(0, _VENDOR)
import websockets  # noqa

HERE = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(HERE, "web")
APP_NAME = "QuietType Relay"

HTTP_PORT = 9001
WS_PORT = 9000

# 在线 agent: name -> {"pin": str, "ws": websocket, "ts": float}
agents = {}
agents_lock = threading.Lock()
loop = None
pending = {}          # msg_id -> asyncio.Future
_msg_id = 0


def _next_id():
    global _msg_id
    _msg_id += 1
    return _msg_id


def _log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------------------
# WebSocket 侧:每台 Windows 电脑的 agent 连上来
# ---------------------------------------------------------------------------
async def agent_conn(ws):
    name = None
    try:
        raw = await asyncio.wait_for(ws.recv(), timeout=15)
        reg = json.loads(raw)
        if reg.get("type") != "reg" or not reg.get("name") or not reg.get("pin"):
            await ws.close()
            return
        name = reg["name"]
        with agents_lock:
            old = agents.get(name)
            if old and old["ws"] is not ws:
                try:
                    await old["ws"].close()
                except Exception:
                    pass
            agents[name] = {"pin": str(reg["pin"]), "ws": ws, "ts": time.time()}
        _log("[agent] %s 上线" % name)

        async for raw in ws:
            m = json.loads(raw)
            if m.get("type") == "reply" and m.get("id") is not None:
                fut = pending.pop(m["id"], None)
                if fut and not fut.done():
                    fut.set_result(m)
            elif m.get("type") == "ping":
                pass
    except Exception as e:
        _log("[agent] %s 连接结束: %s" % (name, e))
    finally:
        with agents_lock:
            cur = agents.get(name)
            if cur and cur["ws"] is ws:
                agents.pop(name, None)
        _log("[agent] %s 离线" % name)
        # 把还在等这台电脑回复的请求全部标记失败
        dead = [i for i, f in pending.items() if not f.done()]
        for i in dead:
            f = pending.pop(i, None)
            if f and not f.done():
                f.set_result({"ok": False, "info": "agent offline"})


async def forward_input(name, msg):
    """在事件循环内: 把手机发来的协议消息转发给对应 agent,等回复。"""
    with agents_lock:
        a = agents.get(name)
    if not a:
        return {"ok": False, "info": "agent offline"}
    mid = _next_id()
    fut = loop.create_future()
    pending[mid] = fut
    try:
        await a["ws"].send(json.dumps({"id": mid, "type": "input", "msg": msg}))
        return await asyncio.wait_for(fut, timeout=12)
    except asyncio.TimeoutError:
        pending.pop(mid, None)
        return {"ok": False, "info": "timeout"}
    except Exception as e:
        pending.pop(mid, None)
        return {"ok": False, "info": "send error: %s" % e}


# ---------------------------------------------------------------------------
# HTTP 侧: 手机网页 UI + API
# ---------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def do_GET(self):
        p = self.path.split("?", 1)[0]
        if p == "/api/info":
            return self._json(200, {"name": APP_NAME, "token": "",
                                    "version": 2, "url": ""})
        if p == "/api/agents":
            with agents_lock:
                names = sorted(agents.keys())
            return self._json(200, {"agents": [{"name": n} for n in names]})
        # 静态页面
        if p in ("/", "/index.html"):
            p = "/index.html"
        name = os.path.normpath(p.lstrip("/"))
        full = os.path.normpath(os.path.join(WEB_DIR, name))
        if not full.startswith(WEB_DIR) or not os.path.isfile(full):
            return self._respond(404, "text/plain", b"not found")
        ctype = {"html": "text/html; charset=utf-8", "js": "application/javascript; charset=utf-8",
                 "css": "text/css; charset=utf-8"}.get(os.path.splitext(full)[1].lstrip(".").lower(),
                                                       "application/octet-stream")
        with open(full, "rb") as f:
            return self._respond(200, ctype, f.read())

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/input":
            return self._json(404, {"ok": False})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            msg = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
        except Exception as e:
            return self._json(400, {"ok": False, "error": str(e)})
        name = msg.pop("name", "")
        pin = msg.pop("pin", "")
        with agents_lock:
            a = agents.get(name)
        if not a:
            return self._json(404, {"ok": False, "info": "电脑不在线"})
        if a["pin"] != str(pin):
            return self._json(403, {"ok": False, "info": "口令错误"})
        fut = asyncio.run_coroutine_threadsafe(forward_input(name, msg), loop)
        try:
            result = fut.result(timeout=15)
        except Exception as e:
            result = {"ok": False, "info": "relay error: %s" % e}
        return self._json(200, result)

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._respond(code, "application/json; charset=utf-8", body)

    def _respond(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except Exception:
            pass


class RelayServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        import traceback
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError)):
            return
        traceback.print_exc()


def run_http():
    srv = RelayServer(("0.0.0.0", HTTP_PORT), Handler)
    _log("[http] 网页/API 端口: %d" % HTTP_PORT)
    srv.serve_forever()


def _resolve_serve():
    """websockets 各版本 serve 入口位置不同,逐个尝试(14+ / 10-13 / 旧顶层)。"""
    import importlib
    for mod in ("websockets.asyncio.server", "websockets.server"):
        try:
            fn = getattr(importlib.import_module(mod), "serve", None)
            if fn:
                return fn
        except Exception:
            continue
    return websockets.serve


def main():
    global HTTP_PORT, WS_PORT, loop
    ap = argparse.ArgumentParser()
    ap.add_argument("--http-port", type=int, default=HTTP_PORT)
    ap.add_argument("--ws-port", type=int, default=WS_PORT)
    args = ap.parse_args()
    HTTP_PORT, WS_PORT = args.http_port, args.ws_port

    threading.Thread(target=run_http, daemon=True).start()
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    serve_fn = _resolve_serve()

    async def _serve():
        return await serve_fn(agent_conn, "0.0.0.0", WS_PORT,
                              ping_interval=20, ping_timeout=10,
                              max_size=1 << 20)

    ws_server = loop.run_until_complete(_serve())
    _log("=" * 56)
    _log("  QuietType 公网中继已启动")
    _log("  手机打开:  http://本机公网IP:%d/   (建议套 HTTPS)" % HTTP_PORT)
    _log("  电脑 agent: python agent.py --relay ws://本机公网IP:%d --name 名字 --pin 口令" % WS_PORT)
    _log("  按 Ctrl+C 退出")
    _log("=" * 56)
    try:
        loop.run_forever()
    except KeyboardInterrupt:
        pass
    finally:
        ws_server.close()
        loop.run_until_complete(ws_server.wait_closed())
        _log("已退出")


if __name__ == "__main__":
    main()
