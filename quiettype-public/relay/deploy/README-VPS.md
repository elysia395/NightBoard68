# QuietType 公网中继 —— VPS 部署说明(方案 B)

## 你需要什么
- 一台**有公网 IP** 的服务器(阿里云/腾讯云轻量 ≈ ¥10-30/月,或家里路由器做端口映射),
  建议 Ubuntu 20.04+/Debian,装好 Python 3.8+。
- 一个域名(可选但**强烈建议**,用于 HTTPS;没有域名也可先用 IP + HTTP 试用)。

## 一键部署(整目录上传后)
把整个 `quiettype-public` 上传到服务器(如 `/opt/quiettype-public`),然后:

```bash
cd /opt/quiettype-public/relay
bash deploy/install.sh            # 装依赖(vendor 已自带,这步只是检查/建服务)
bash deploy/start.sh              # 前台运行(测试用)
# 或安装为系统服务(开机自启):
sudo bash deploy/install_service.sh
```

防火墙放行两个端口(默认):
```bash
sudo ufw allow 9000/tcp   # agent(电脑端)连进来的 WebSocket
sudo ufw allow 9001/tcp   # 手机打开的网页/API
```

## 使用流程
1. 服务器跑起 relay.py;
2. 每台 Windows 电脑(家里/公司/任意地点)双击运行 agent(或加开机自启):
   `python agent.py --relay ws://你的服务器IP:9000 --name 小明的工作机 --pin 123456`
3. 手机(任何网络、iOS/安卓都一样)打开 `http://你的服务器IP:9001/`:
   - 顶部"公网中继模式"下拉框选"小明的工作机";
   - 输入口令 `123456`;点输入框打字即可实时敲进那台电脑。

## 强烈建议:套 HTTPS(nginx)
浏览器对公网 http 页面有限制(定位/部分功能),且明文传输不安全。有域名时:
```nginx
server {
  listen 443 ssl;
  server_name qt.example.com;
  ssl_certificate     /etc/letsencrypt/live/qt.example.com/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/qt.example.com/privkey.pem;
  location / {
    proxy_pass http://127.0.0.1:9001;
    proxy_http_version 1.1;
  }
}
```
HTTPS 下手机打开 `https://qt.example.com/`,其它不变。

## 安全提醒
- 口令即钥匙:agent 显示的口令就是别人操控你电脑的凭证,**别用弱口令、别外传**;
- 当前为明文 HTTP/WS,MVP 用途;真正长期对外请务必套 HTTPS(WSS);
- 建议 agent 只在你需要远程打字时启动,用完关掉(或加 --pin 复杂口令 + 防火墙只放行到中继 IP)。

## 目录说明
```
quiettype-public/
├─ vendor/          自带 websockets(服务器)与 websocket-client(电脑端),免 pip
├─ relay/
│  ├─ relay.py      中继服务器(VPS 上跑)
│  ├─ agent.py      Windows 电脑端(主动连中继,注入按键)
│  ├─ web/          手机网页(与局域网版同一套 UI,自动进入中继模式)
│  └─ deploy/       部署脚本与 systemd 示例
└─ A/               个人公网隧道版(见 A/README.md)
```
