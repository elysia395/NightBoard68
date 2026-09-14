# 方案 A: 个人公网隧道版

让手机在**任何网络**(蜂窝数据、别家 Wi-Fi)都能连到这台 Windows 电脑打字。
原理: 本地 QuietType 服务 + Cloudflare 免费快速隧道,黑窗口打印公网 https 网址和二维码。

## 使用
1. 双击 `start-public.bat`,或命令行 `python run_tunnel.py`;
2. 首次自动下载 cloudflared(~50MB)到 `bin\`;下载失败时:
   - 双击 `download-cloudflared.bat` 重试,或
   - 去 https://github.com/cloudflare/cloudflared/releases 手动下载
     `cloudflared-windows-amd64.exe` 放到本目录 `bin\`;
3. 看到 `公网网址: https://xxxx.trycloudflare.com` 和二维码后,手机相机扫它;
4. 允许本地网络(手机首次)→ 打字即敲进电脑。窗口保持开着。

其它:
- `--no-tunnel`: 只起本地服务(普通局域网版),端口 `--inner-port` 可改(默认 8577,
  与局域网版的 8567 不冲突,可同时跑);
- 每次启动网址都会变,以当次黑窗口为准;
- 隧道走 Cloudflare 加密,但请勿输入银行卡密码等敏感信息(免费隧道用途有限)。

## 打成"给朋友双击即用"的单 exe(拿到 cloudflared 后)
本机(有 Python + PyInstaller 的开发机)执行:

```bat
python -m pip install pyinstaller
python build_exe.py
:: 产物: dist\QuietTypePublic.exe —— 双击即开隧道 + 服务 + 打印公网二维码
```

朋友拿到单个 exe:双击 → 等 ~5 秒出现公网网址+二维码 → 手机(任何网络)扫码 → 开打。
