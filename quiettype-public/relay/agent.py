#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 中继客户端 —— 跑在【每台 Windows 电脑】上(只能 Windows,因为要注入按键)。
它主动连接公网中继服务器;手机打开中继页面,选到这台电脑 + 输入口令后,
按键/文本经中继转发到这里,由本程序用 SendInput 注入本机——和局域网版完全同效。

用法:
  python agent.py --relay ws://1.2.3.4:9000 --name 我的电脑 --pin 123456
  python agent.py --relay ws://1.2.3.4:9000 --name 我的电脑 --pin 123456 --dryrun   # 测试不真正注入

提示: --name 是显示在中继页面上的电脑名; --pin 是手机端要输入的连接口令。
"""
import argparse
import json
import os
import sys
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
# 复用局域网版的核心(协议分发 + SendInput 注入)
sys.path.insert(0, os.path.join(_HERE, "..", "..", "quiettype", "server"))
import quiettype_server as core  # noqa
# websocket-client(自带 vendor)
sys.path.insert(0, os.path.join(_HERE, "..", "vendor"))
import websocket  # noqa

URL = ""
NAME = ""
PIN = ""


def _send_reg(ws):
    ws.send(json.dumps({"type": "reg", "name": NAME, "pin": PIN}))
    print("[agent] 已连接中继。电脑名=%s 口令=%s (手机中继页选它并输入口令)" % (NAME, PIN), flush=True)


def on_open(ws):
    _send_reg(ws)


def on_message(ws, raw):
    try:
        m = json.loads(raw)
    except Exception:
        return
    if m.get("type") != "input":
        return
    ok, info = core.dispatch(m.get("msg") or {})
    try:
        ws.send(json.dumps({"type": "reply", "id": m.get("id"), "ok": bool(ok), "info": info}))
    except Exception:
        pass


def on_error(ws, e):
    print("[agent] 连接错误: %s" % e, flush=True)


def on_close(ws, *a):
    print("[agent] 连接断开,3 秒后重连…", flush=True)


def run():
    while True:
        try:
            app = websocket.WebSocketApp(URL,
                                         on_open=on_open, on_message=on_message,
                                         on_error=on_error, on_close=on_close)
            app.run_forever(ping_interval=20, ping_timeout=10)
        except KeyboardInterrupt:
            return
        except Exception as e:
            print("[agent] %s" % e, flush=True)
        time.sleep(3)


def main():
    global URL, NAME, PIN
    ap = argparse.ArgumentParser()
    ap.add_argument("--relay", required=True, help="中继服务器地址,如 ws://1.2.3.4:9000")
    ap.add_argument("--name", required=True, help="这台电脑在中继页面上显示的名字")
    ap.add_argument("--pin", required=True, help="手机端连接所需口令")
    ap.add_argument("--dryrun", action="store_true", help="测试模式,不真正注入按键")
    args = ap.parse_args()
    URL, NAME, PIN = args.relay, args.name, args.pin
    if args.dryrun:
        core.DRY_RUN = True
        print("[agent] dry-run 模式:不真正注入")
    print("[agent] 目标中继: %s  电脑名: %s" % (URL, NAME), flush=True)
    run()


if __name__ == "__main__":
    main()
