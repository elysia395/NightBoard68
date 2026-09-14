#!/usr/bin/env bash
# 前台启动中继(测试用)。端口可用环境变量覆盖: HTTP_PORT / WS_PORT
cd "$(dirname "$0")/.."
export HTTP_PORT="${HTTP_PORT:-9001}"
export WS_PORT="${WS_PORT:-9000}"
exec python3 relay.py --http-port "$HTTP_PORT" --ws-port "$WS_PORT"
