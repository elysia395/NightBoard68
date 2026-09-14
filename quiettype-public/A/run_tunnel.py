#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QuietType 个人公网隧道版 (Windows)
==================================
作用: 让手机【任何网络,不必和电脑同一个 Wi-Fi】都能连接自己的电脑。
原理: 本地起 QuietType 服务 + 自动开一条 Cloudflare 快速隧道,得到一个
公网 https 网址;黑窗口打印网址 + 二维码,手机相机一扫即连(经 Cloudflare 加密转发)。

用法:
  python run_tunnel.py              # 自动: 找/下载 cloudflared → 开隧道 → 起本地服务
  python run_tunnel.py --inner-port 8577
  python run_tunnel.py --no-tunnel  # 不起隧道,等同普通局域网版

首次运行会自动下载 cloudflared(约 50MB)到本目录下的 bin 目录;
下载慢/失败时可手动放置 cloudflared.exe 到 bin 目录,或改 QT_CLOUDFLARED 环境变量指向它。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request

_HERE = os.path.dirname(os.path.abspath(__file__))

if getattr(sys, "frozen", False):
    BIN_DIR = os.path.join(sys._MEIPASS, "bin")   # 打包版内嵌
    BASE_DIR = os.path.dirname(sys.executable)
else:
    BIN_DIR = os.path.join(_HERE, "bin")
    BASE_DIR = _HERE

CLOUDFLARED = os.environ.get("QT_CLOUDFLARED") or os.path.join(BIN_DIR, "cloudflared.exe")

# cloudflared 下载源(多镜像,依次尝试)
DOWNLOAD_URLS = [
    "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe",
    "https://download.cloudflare.com/cloudflared/stable-windows-amd64.exe",
    "https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe",
    "https://ghproxy.net/https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe",
]

URL_RE = re.compile(r"https://[-a-z0-9]+\.trycloudflare\.com", re.I)


def log(*a):
    print(*a, flush=True)


def ensure_cloudflared():
    if os.path.isfile(CLOUDFLARED):
        return CLOUDFLARED
    w = shutil.which("cloudflared")
    if w:
        return w
    log("未找到 cloudflared,开始自动下载(约 50MB,多镜像依次尝试)…")
    os.makedirs(BIN_DIR, exist_ok=True)
    last = ""
    for url in DOWNLOAD_URLS:
        try:
            log("  尝试: %s" % url)
            tmp = CLOUDFLARED + ".tmp"
            with urllib.request.urlopen(url, timeout=60) as r, open(tmp, "wb") as f:
                shutil.copyfileobj(r, f, length=1 << 20)
            os.replace(tmp, CLOUDFLARED)
            log("下载完成: %s" % CLOUDFLARED)
            return CLOUDFLARED
        except Exception as e:
            last = str(e)
            log("  失败: %s" % e)
    raise SystemExit("cloudflared 下载失败(%s)。\n请手动下载 cloudflared-windows-amd64.exe 放到:\n  %s\n然后重新运行。" % (last, CLOUDFLARED))


def start_tunnel(inner_port):
    """启动 cloudflared 快速隧道,返回公网 https 网址(等它打印出来)。"""
    exe = ensure_cloudflared()
    log("启动隧道: %s tunnel --url http://127.0.0.1:%d …" % (exe, inner_port))
    proc = subprocess.Popen(
        [exe, "tunnel", "--url", "http://127.0.0.1:%d" % inner_port, "--no-autoupdate"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    found = {}

    def reader():
        for raw in iter(proc.stdout.readline, b""):
            line = raw.decode("utf-8", "replace").strip()
            log("[tunnel] " + line)
            m = URL_RE.search(line)
            if m and not found:
                found["url"] = m.group(0)
    threading.Thread(target=reader, daemon=True).start()

    deadline = time.time() + 75
    while time.time() < deadline:
        if "url" in found:
            return found["url"], proc        # (公网网址, cloudflared 进程)
        if proc.poll() is not None:
            break
        time.sleep(0.3)
    proc.terminate()
    log("")
    log("75 秒内未取得公网网址。可能原因:")
    log("  1. 网络无法直连 Cloudflare(公司/校园网常见)→ 换网络再试;")
    log("  2. cloudflared 被安全软件拦截 → 手动双击 bin\\cloudflared.exe 看报错;")
    log("  3. 可先用 --no-tunnel 跑局域网版应急。")
    raise SystemExit("启动公网隧道失败。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--inner-port", type=int, default=8577,
                    help="本地 QuietType 服务端口(默认 8577)")
    ap.add_argument("--no-tunnel", action="store_true", help="不起隧道(普通局域网版)")
    args = ap.parse_args()

    if args.no_tunnel:
        _run_server(args.inner_port, public_url=None)
        return

    url, proc = start_tunnel(args.inner_port)
    log("")
    log("=" * 60)
    log("  公网网址: %s" % url)
    log("  手机任何网络都能打开;本窗口保持开着。")
    log("=" * 60)
    try:
        _run_server(args.inner_port, public_url=url)
    finally:
        try:
            proc.terminate()
        except Exception:
            pass


def _run_server(inner_port, public_url):
    # 源码运行时: 指向局域网版核心(quiettype/server);打包时已通过 hidden-import 内嵌
    if not getattr(sys, "frozen", False):
        sys.path.insert(0, os.path.join(_HERE, "..", "..", "quiettype", "server"))
    import quiettype_server as core
    if public_url:
        os.environ["QT_PUBLIC_URL"] = public_url
    core.main(["--port", str(inner_port)])


if __name__ == "__main__":
    main()
