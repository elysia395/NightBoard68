#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 QuietType 局域网主机端打成单个 exe：QuietType.exe
（内含网页资源 + segno 二维码库；用户电脑无需安装 Python，双击即用）

用法（开发机）：
    pip install pyinstaller
    python build_exe.py
产物：dist/QuietType.exe

CI 里由 .github/workflows/release-quiettype-exe.yml 自动执行并发布 Release。
"""

import os
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_SERVER = os.path.join(_HERE, "server")


def main():
    cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm", "--clean", "--onefile", "--console",
        "--name", "QuietType",
        # 让 PyInstaller 的模块分析找到内嵌 segno（vendor/ 在运行时也会进 sys.path）
        "--paths", os.path.join(_SERVER, "vendor"),
        # 手机端网页资源与二维码库打进 exe（Windows 用 ; 分隔 源;目标）
        "--add-data", "%s%sweb;web" % (_SERVER, os.sep),
        "--add-data", "%s%svendor;vendor" % (_SERVER, os.sep),
        os.path.join(_SERVER, "quiettype_server.py"),
    ]
    print("执行:", " ".join(cmd))
    subprocess.check_call(cmd)
    print("完成: dist\\QuietType.exe（单文件，免安装 Python）")


if __name__ == "__main__":
    main()
