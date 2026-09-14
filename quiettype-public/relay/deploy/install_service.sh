#!/usr/bin/env bash
# 安装为 systemd 服务(开机自启)。用法: sudo bash install_service.sh
set -e
cd "$(dirname "$0")/.."
DIR="$(pwd)"
SVC="quiettype-relay.service"
cat > /etc/systemd/system/$SVC <<EOF
[Unit]
Description=QuietType public relay
After=network.target

[Service]
WorkingDirectory=$DIR
ExecStart=/usr/bin/python3 $DIR/relay.py --http-port 9001 --ws-port 9000
Restart=always
RestartSec=3
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable $SVC
systemctl restart $SVC
echo "==> 已启动并设为开机自启。状态: systemctl status $SVC"
echo "==> 查看日志: journalctl -u $SVC -f"
