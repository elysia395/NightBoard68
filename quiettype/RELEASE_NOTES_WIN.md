## QuietType Windows 主机端 1.0.0 —— 双击即用，免装 Python

把 iPhone 变成 Windows 电脑的静音键盘 / 触控板：深夜敲代码不吵人，打字、划触控板
实时注入电脑光标处，效果等同亲手敲键盘。

**这个 exe 就是电脑端唯一需要运行的东西**：下载 → 双击 → 黑窗口里出网址和二维码 →
iPhone 相机扫一下 → 开打。不用装 Python、不用装运行时、不用管理员权限。

### 使用（3 步）

1. **电脑**：下载 `QuietType-1.0.0.exe`，双击运行（首次可能弹
   「Windows 已保护你的电脑」→ 点**更多信息 → 仍要运行**；未做商业签名，属正常提示）。
   黑窗口里会打印本次网址（`http://192.168.x.x:8567/`）和一个二维码
2. **手机**：iPhone 与电脑连**同一个 Wi-Fi**，用**相机扫窗口里的二维码**
   （或 Safari 手动输入那行网址），弹出「本地网络」权限点允许
3. **开打**：先在**电脑上点好光标位置**，再到手机上打字 / 划触控板。
   想让网页更像 App：Safari 分享 → 添加到主屏幕

保持黑窗口开着即服务运行中；关掉窗口停止。`Ctrl+C` 或关窗口退出。

### 连不上时

- 确认手机电脑同一 Wi-Fi（公司/访客 Wi-Fi 的「AP 隔离」会挡，换家用路由）
- 下载附件里的 `firewall_allow.bat`，**右键 → 以管理员身份运行**，放行 TCP 8567
- 电脑 IP 变了（重连 WiFi/重启常见）：关掉重开，用新打印的网址

### 说明

- 端口默认 8567，被占用时自动顺延；窗口里打印的地址以当次为准
- 每次启动随机生成 6 位 token 防误连；仅面向自用局域网，无加密，别在公共 Wi-Fi 开太久
- 打字内容会记录在 exe 同目录的 `access.log`（隐私敏感可删；加 `--no-log`
  启动可关闭，需要从命令行运行）
- 网页版功能与此完全一致：不想下载任何东西的话，见
  [QuietType iOS IPA](../../releases/tag/quiettype-ios-1.0.0)（原生 App，需侧载）
  或直接用仓库源码里的网页版

### 自己构建

```bash
pip install pyinstaller
python quiettype/build_exe.py     # 产物 dist/QuietType.exe
```

杀毒软件可能对 PyInstaller 产物误报；如担心可改用源码运行（`quiettype/run.bat`，
需装 Python）。MIT License · github.com/elysia395/NightBoard68
