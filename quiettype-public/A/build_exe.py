#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把方案 A 打成单个 exe: QuietTypePublic.exe(内含网页资源 + segno + cloudflared)。
前置: 本目录 bin/cloudflared.exe 已存在(运行过 run_tunnel.py 一次即自动下载),
      开发机已装 pyinstaller(pip install pyinstaller)。
用法:  python build_exe.py
"""
import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_SERVER = os.path.join(_HERE, "..", "..", "quiettype", "server")


def main():
    cf = os.path.join(_HERE, "bin", "cloudflared.exe")
    if not os.path.isfile(cf):
        print("缺少 %s\n请先运行 run_tunnel.py 一次(会自动下载),或手动放入。" % cf)
        sys.exit(1)
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm", "--clean", "--onefile",
        "--name", "QuietTypePublic",
        "--add-data", "%s%sweb;web" % (_SERVER, os.sep),
        "--add-data", "%s%svendor;vendor" % (os.path.dirname(_SERVER), os.sep),  # segno
        "--add-data", "%s;bin" % cf,
        os.path.join(_HERE, "run_tunnel.py"),
    ]
    print("执行:", " ".join(cmd))
    subprocess.check_call(cmd)
    print("完成: dist\\QuietTypePublic.exe")


if __name__ == "__main__":
    main()
