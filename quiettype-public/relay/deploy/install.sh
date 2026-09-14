#!/usr/bin/env bash
# QuietType 中继部署: 安装/检查依赖(其实 vendor 已自带,此脚本主要做环境检查)
set -e
cd "$(dirname "$0")/.."
echo "==> Python 版本检查"
python3 --version
echo "==> vendor 依赖检查"
python3 - <<'PY'
import sys, os
sys.path.insert(0, os.path.join(os.getcwd(), '..', 'vendor'))
try:
    import websockets
    print('websockets OK', websockets.__version__)
except Exception as e:
    print('websockets 缺失:', e); sys.exit(1)
PY
echo "==> 依赖正常,可直接: python3 relay.py"
