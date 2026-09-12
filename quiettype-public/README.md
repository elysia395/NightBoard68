# QuietType 公网版 —— 让手机在"任何网络"连接自己的 Windows 电脑

> 局域网版(`../quiettype`)要求手机和电脑**同一个 Wi-Fi**。
> 本目录解决"不在同一网络也能用"的问题,提供两条路:

```
A: 个人公网隧道版    每台电脑跑一次 → 自动得到一个公网 https 网址(带二维码)
                    手机任何网络扫码即连。 免费、无需服务器、无需注册。
B: 中继门户版        所有人打开【同一个网址】→ 选自己的电脑 → 输入口令 → 开打。
                    需要一个有公网 IP 的服务器(约 10-30 元/月)。
```

**两个版本都要求那台 Windows 电脑上有一个小 agent 在运行**——这是浏览器
安全限制决定的(网页无法给其它软件注入按键),谁都绕不开,不是本项目的妥协。

---

## 方案 A:个人公网隧道版(先试这个,零成本)

目录:`A/`

1. 电脑(Windows)双击 `start-public.bat`,或运行 `python A/run_tunnel.py`;
2. 首次运行会自动下载 cloudflared(~50MB,含国内镜像;失败就运行
   `A/download-cloudflared.bat`,或手动把 cloudflared.exe 放进 `A/bin`);
3. 黑窗口出现:
   ```
   公网网址: https://xxxx-xxxx.trycloudflare.com
   ```
   并打印二维码;
4. 手机(流量/别的 Wi-Fi 都行,iOS/安卓浏览器)相机扫二维码 → 打开 → 打字即敲进这台电脑。

要点:
- 隧道免费、自动断线重连由 Cloudflare 托管;窗口保持开着;
- 此网址每次启动都变(随机),启动后以当次打印为准;
- 电脑本地**不同网段**问题消失:公司电脑 + 家里手机也能连;
- 想做成给朋友的"一个 exe"(双击即开隧道+服务):本目录自带
  `python A/build_exe.py` 可在拿到 cloudflared.exe 后一键打包(见 A/README.md)。

## 方案 B:中继门户版(所有人同一个网址)

目录:`relay/`,部署在有公网 IP 的服务器上(VPS / 家里路由器映射),说明见
`relay/deploy/README-VPS.md`。大致流程:

1. 服务器跑 `python3 relay.py`(HTTP 9001 / WS 9000,可改,支持 systemd 自启);
2. 每台 Windows 电脑(任意网络,在公司/在家都行)运行:
   `python relay/agent.py --relay ws://你的服务器IP:9000 --name 你的电脑名 --pin 你的口令`
3. 手机打开 `http://你的服务器IP:9001/`:
   下拉框选自己的电脑 → 输入口令 → 开打(实时敲进那台电脑)。

安全:中继只做转发,不落盘按键内容;口令即钥匙,务必用强口令,建议套 HTTPS(WSS)。

---

## 本目录结构
```
quiettype-public/
├─ vendor/           自带 websockets(中继用)与 websocket-client(电脑端用),免 pip
├─ A/                个人公网隧道版(Cloudflare 快速隧道)
├─ relay/            中继门户版: relay.py(服务器) + agent.py(Windows 端)
│   ├─ web/          手机网页(与局域网版同一套 UI,自动进入中继模式)
│   └─ deploy/       VPS 部署脚本 + systemd + HTTPS 说明
└─ 本 README
```
