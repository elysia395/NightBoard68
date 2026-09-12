#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 双模启动器(一键两用)
==============================
一次启动,同时给你两条路:
  【局域网】同 Wi-Fi 用,毫秒级延迟,触控板顺滑
  【公网  】外出/不同网络也能用(Cloudflare 免费隧道,网址每次启动都变)

用法:
  python run_dual.py                 # 默认内网端口 8577
  python run_dual.py --port 8577
  python run_dual.py --no-tunnel     # 只开局域网(不联网)
  python run_dual.py --dryrun        # 测试:不真正注入按键
"""
import argparse
import os
import sys
import threading
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_FROZEN = getattr(sys, "frozen", False)

# 核心(Windows 注入 + HTTP 服务 + 二维码)
if not _FROZEN:
    sys.path.insert(0, os.path.join(_HERE, "..", "..", "quiettype", "server"))
    sys.path.insert(0, _HERE)
import quiettype_server as core          # noqa
import run_tunnel as tun                 # noqa: 复用 cloudflared 下载/隧道逻辑


def log(*a):
    print(*a, flush=True)


def show(url, title):
    log("  %s" % title)
    log("    %s" % url)
    log("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8577, help="本地服务端口(默认 8577,避开局域网版 8567)")
    ap.add_argument("--no-tunnel", action="store_true", help="只开局域网,不开公网隧道")
    ap.add_argument("--dryrun", action="store_true", help="测试模式,不真正注入按键")
    args = ap.parse_args()

    if args.dryrun:
        core.DRY_RUN = True
    os.environ["QT_NO_QR"] = "1"          # 不再打印二维码,只打印网址

    port = args.port
    lan_urls = ["http://%s:%d/" % (ip, port) for ip in core.lan_ips()]

    # 1) 先把本地服务起在后台线程:这样局域网网址"立刻"可用
    threading.Thread(target=lambda: core.main(["--port", str(port)]), daemon=True).start()
    time.sleep(0.8)

    # 2) 立刻打印局域网网址(不等公网,避免窗口长时间空白)
    log("")
    log("=" * 64)
    log("  QuietType 双模模式(本窗口保持开着)")
    log("=" * 64)
    for u in lan_urls:
        show(u, "【局域网 · 触控板最顺滑】同 Wi-Fi 时手机打开:")

    if args.no_tunnel:
        log("  (已指定 --no-tunnel:仅局域网模式)")
        log("=" * 64)
    else:
        # 3) 再去开公网隧道;失败会自动降级,不影响上面的局域网
        log("-" * 64)
        log("  正在开启公网隧道(最多等 75 秒,失败会自动降级为仅局域网)…")
        log("")
        public_url, proc = None, None
        try:
            public_url, proc = tun.start_tunnel(port)
        except SystemExit as e:
            log("")
            log("!! 公网隧道失败:%s" % e)
            log("!! 现在是【仅局域网】模式,同一 Wi-Fi 下照常使用。")
        if public_url:
            os.environ["QT_PUBLIC_URL"] = public_url
            log("=" * 64)
            show(public_url, "【公网 · 出门在外也能用】手机任何网络:")
            try:
                core._clipboard_set(public_url)
                log("  (公网网址已复制到剪贴板,可直接粘贴发给朋友)")
                log("")
            except Exception:
                pass
            log("=" * 64)
            log("  提示:知道公网网址的人都能输入到这台电脑,不用时关掉本窗口即可。")
            log("=" * 64)

    # 4) 主线程常驻;Ctrl+C 或关窗口即退出
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
