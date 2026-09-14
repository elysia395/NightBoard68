#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 自测脚本(对着 dryrun 实例跑,不会真的注入按键)

用法:
  python tests/selftest.py --port 8610            # 测本机 dryrun 实例
  python tests/selftest.py --base http://127.0.0.1:8610

覆盖: /api/info、静态资源、全部按键名、文本/粘贴/退格、鼠标移动/按键/滚轮、
      错误 token、未知键名、未知消息类型、并发、延迟与吞吐统计。
"""
import argparse
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request

# 兼容非 UTF-8 控制台(Windows 中文控制台默认 GBK)
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors="replace")
    except Exception:
        pass

ALL_KEYS = (
    ["esc", "tab", "enter", "space", "backspace", "delete", "insert", "home", "end",
     "pgup", "pgdn", "up", "down", "left", "right", "capital", "capslock", "win",
     "printscreen", "scroll", "pause", "numlock", "apps"]
    + ["f%d" % i for i in range(1, 25)]
    + list("abcdefghijklmnopqrstuvwxyz")
    + list("0123456789")
    + ["numpad%d" % i for i in range(10)]
    + ["multiply", "add", "separator", "subtract", "decimal", "divide"]
    + ["oem_1", "oem_2", "oem_3", "oem_4", "oem_5", "oem_6", "oem_7",
       "oem_plus", "oem_minus", "oem_comma", "oem_period", "oem_102"]
    + ["volume_mute", "volume_down", "volume_up",
       "media_next", "media_prev", "media_stop", "media_play"]
)

results = {"pass": [], "fail": [], "warn": []}


def ok(name, detail=""):
    results["pass"].append("%s %s" % (name, detail))


def fail(name, detail=""):
    results["fail"].append("%s %s" % (name, detail))


def warn(name, detail=""):
    results["warn"].append("%s %s" % (name, detail))


def post(base, body, timeout=10):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + "/api/input", data=data,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"error": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8610")
    ap.add_argument("--port", type=int, default=None)
    args = ap.parse_args()
    base = args.base if not args.port else "http://127.0.0.1:%d" % args.port

    # ---------- 1. 基础接口 ----------
    try:
        with urllib.request.urlopen(base + "/api/info", timeout=5) as r:
            info = json.loads(r.read().decode("utf-8"))
        if info.get("token") and info.get("name") == "QuietType":
            ok("api/info", "token=%s url=%s" % (info.get("token"), info.get("url")))
        else:
            fail("api/info", str(info))
    except Exception as e:
        fail("api/info 不可达", str(e))
        report()
        return
    token = info["token"]

    # ---------- 2. 静态资源 ----------
    for path, must in (("/", "layout-portrait"), ("/app.js", "showZhBar"),
                       ("/style.css", ".k.pressed")):
        try:
            with urllib.request.urlopen(base + path, timeout=5) as r:
                body = r.read().decode("utf-8", "replace")
            if must in body:
                ok("静态资源 %s" % path, "%d 字节" % len(body))
            else:
                fail("静态资源 %s" % path, "缺少标记 %s" % must)
        except Exception as e:
            fail("静态资源 %s" % path, str(e))

    # ---------- 3. 全部按键名 ----------
    bad = []
    t0 = time.time()
    for k in ALL_KEYS:
        st, res = post(base, {"token": token, "t": "key", "k": k, "mods": []})
        if not (st == 200 and res.get("ok")):
            bad.append(k)
    dt = (time.time() - t0) * 1000
    if bad:
        fail("按键全集", "不支持: %s" % ", ".join(bad))
    else:
        ok("按键全集", "%d 个键全部接受, 总耗时 %.0fms (均 %.1fms/键)"
           % (len(ALL_KEYS), dt, dt / len(ALL_KEYS)))

    # ---------- 4. 组合键 ----------
    combos = [("ctrl", "c"), ("ctrl", "v"), ("ctrl", "shift", "t"), ("alt", "f4"), ("win", "d")]
    cbad = []
    for c in combos:
        st, res = post(base, {"token": token, "t": "key", "k": c[-1], "mods": list(c[:-1])})
        if not (st == 200 and res.get("ok")):
            cbad.append("+".join(c))
    ok("组合键", "全部通过" if not cbad else "失败: %s" % cbad) if not cbad else fail("组合键", str(cbad))

    # ---------- 5. 文本 / 粘贴 / 退格 ----------
    cases = [
        ("英文文本", {"t": "txt", "s": "hello world"}),
        ("中文文本", {"t": "txt", "s": "中文测试段落"}),
        ("中文粘贴", {"t": "paste", "s": "中文粘贴测试"}),
        ("符号文本", {"t": "txt", "s": "{}()[]<>;:'\",./\\|_+=-*&^%$#@!?"}),
        ("换行/Tab", {"t": "txt", "s": "line1\nline2\tend"}),
        ("退格 x3", {"t": "bs", "n": 3}),
    ]
    for name, body in cases:
        body["token"] = token
        st, res = post(base, body)
        (ok if (st == 200 and res.get("ok")) else fail)("输入:" + name, str(res.get("info")))

    # ---------- 6. 鼠标 ----------
    for name, body in (("移动", {"t": "mv", "x": 12, "y": -8}),
                       ("左键按下", {"t": "mb", "b": "left", "d": True}),
                       ("左键抬起", {"t": "mb", "b": "left", "d": False}),
                       ("右键", {"t": "mb", "b": "right", "d": True}),
                       ("滚轮", {"t": "wl", "v": -3})):
        body["token"] = token
        st, res = post(base, body)
        (ok if (st == 200 and res.get("ok")) else fail)("鼠标:" + name, str(res.get("info")))

    # ---------- 7. 异常处理 ----------
    st, res = post(base, {"token": token, "t": "key", "k": "not_a_real_key"})
    if st == 200 and not res.get("ok"):
        ok("未知键名被拒", res.get("info"))
    else:
        fail("未知键名未拒绝", "%s %s" % (st, res))
    st, res = post(base, {"token": token, "t": "no_such_type"})
    if st == 200 and not res.get("ok"):
        ok("未知消息类型被拒", res.get("info"))
    else:
        fail("未知消息类型未拒绝", "%s %s" % (st, res))
    st, res = post(base, {"token": "WRONG", "t": "ping"})
    if st == 403:
        ok("错误 token 拒绝", "HTTP 403")
    else:
        fail("错误 token 未拒绝", "HTTP %s" % st)
    st, res = post(base, {})
    if st in (403, 400):
        ok("空 token 拒绝", "HTTP %s" % st)
    else:
        fail("空 token 未拒绝", "HTTP %s" % st)

    # ---------- 8. 延迟(串行) ----------
    lat = []
    for _ in range(30):
        t = time.time()
        post(base, {"token": token, "t": "mv", "x": 3, "y": 3})
        lat.append((time.time() - t) * 1000)
    ok("鼠标移动串行延迟", "平均 %.1fms / 中位 %.1fms / 最大 %.1fms"
       % (statistics.mean(lat), statistics.median(lat), max(lat)))

    # ---------- 9. 吞吐(并发 8 条在途,模拟流式) ----------
    n, pipe = 200, 8
    sem = threading.Semaphore(pipe)
    done = []

    def worker(i):
        with sem:
            st, _ = post(base, {"token": token, "t": "mv", "x": 2, "y": 2})
            done.append(st)

    t0 = time.time()
    ths = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    for th in ths:
        th.start()
    for th in ths:
        th.join()
    dt = time.time() - t0
    ok("鼠标流式吞吐", "%d 条 / %.2fs = %.0f 条/秒(管道深度 %d)"
       % (len(done), dt, len(done) / dt, pipe))

    # ---------- 10. 长文本耗时 ----------
    t = time.time()
    st, res = post(base, {"token": token, "t": "txt", "s": "A" * 50})
    dt = (time.time() - t) * 1000
    ok("50 字符文本耗时", "%.0fms(dry 模式含逐字打印开销)" % dt)

    # ---------- 11. 并发多客户端 ----------
    errs = []

    def client():
        for _ in range(10):
            st, res = post(base, {"token": token, "t": "key", "k": "a"})
            if st != 200 or not res.get("ok"):
                errs.append(st)
    ths = [threading.Thread(target=client) for _ in range(4)]
    for th in ths:
        th.start()
    for th in ths:
        th.join()
    (ok if not errs else fail)("4 客户端并发输入", "全部成功" if not errs else str(errs))

    report()


def report():
    print("\n" + "=" * 68)
    print("  QuietType 自测报告")
    print("=" * 68)
    print("\n【通过】%d 项" % len(results["pass"]))
    for x in results["pass"]:
        print("  [OK]   " + x)
    if results["warn"]:
        print("\n【警告】%d 项" % len(results["warn"]))
        for x in results["warn"]:
            print("  [WARN] " + x)
    print("\n【失败】%d 项" % len(results["fail"]))
    for x in results["fail"]:
        print("  [FAIL] " + x)
    print("\n" + "=" * 68)
    print("  结论: %s" % ("全部通过,无阻塞问题" if not results["fail"]
                          else "存在 %d 项失败,需修复" % len(results["fail"])))
    print("=" * 68)


if __name__ == "__main__":
    main()
