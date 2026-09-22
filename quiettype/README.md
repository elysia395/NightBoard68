# QuietType —— 把 iPhone 变成 Windows 的静音键盘 / 触控板

用途:深夜安静写代码。笔记本键盘太吵?把输入挪到手机上——
iPhone 上打字/划触控板,内容实时注入 Windows 电脑,效果等同亲手敲键盘。

**不是蓝牙 HID**(iOS 不开放蓝牙键盘模拟),而是:**局域网 Wi-Fi 传输 +
Windows 端软件模拟 HID(SendInput 注入)**。纯 Python 标准库,零依赖。

```
手机(网页 或 iOS App)  --同一Wi-Fi HTTP-->  Windows 主机端  --SendInput--> 你正用的编辑器/任何窗口
```

## 目录结构

```
quiettype/
├─ run.bat                     源码方式启动主机端(Windows,需装 Python)
├─ build_exe.py                把主机端打成单文件 exe(开发机用,需 pyinstaller)
├─ firewall_allow.bat          管理员运行一次,放行 8567(连不上时才需要)
├─ README.md                   本文件
├─ PROTOCOL.md                 网页版与 iOS App 共用的通信协议
├─ server/
│  ├─ quiettype_server.py      主机端(纯标准库)
│  └─ web/                     手机浏览器客户端(index.html/app.js/style.css)
└─ ios/
   ├─ README-iOS.md            iOS App 编译与侧载完整步骤
   ├─ project.yml              XcodeGen 工程描述(Info.plist 权限在里面)
   └─ QuietType/               Swift 源码(7 个文件)+ App 图标
```

## 一、电脑端(两种方式,推荐 A)

### A. 下载 exe 双击即用(推荐,不用装 Python)

到 [Releases](../../releases) 下载 `QuietType-1.0.0.exe`,**双击运行**:

- 黑窗口打印本次网址(如 `http://192.168.1.5:8567/`)和一个二维码;
- 首次运行若弹「Windows 已保护你的电脑」,点**更多信息 → 仍要运行**
  (未做商业签名的正常提示);
- 保持窗口开着;关掉即停止。`Ctrl+C` 退出。

### B. 源码运行(开发者,需装 Python 3)

1. 双击 `run.bat`(或命令行 `python server\quiettype_server.py`)。
2. 窗口同样会打印出本次网址和一个**用 # 拼成的二维码**:
   - 手机手动打开 `http://192.168.1.5:8567/`,或
   - 用 **iPhone 相机扫窗口里的二维码**直达连接页(最快,不用敲网址);
   - 电脑浏览器打开 `http://127.0.0.1:8567/` 时,页面顶部也会显示同款可扫二维码。
3. 保持窗口开着;关掉即停止。`Ctrl+C` 退出。

> 连不上时:手机电脑必须**同一个 Wi-Fi**;然后右键
> `firewall_allow.bat` **以管理员身份运行**一次(放行 TCP 8567;
> exe 用户在 Release 附件里能下载到同一个文件)。
> 公司/访客 Wi-Fi 若开启了「AP 隔离」则手机连不到电脑,换家用路由即可。

## 二、手机端 —— 网页版(今天就能用,不用装任何东西)

1. iPhone 用 **Safari** 打开上面那行网址(在地址栏输入
   `http://电脑IP:8567` 即可,token 页面会自动取)。
2. 允许“本地网络”访问(如弹窗)。
3. 「键盘」页:点输入框弹出 iPhone 键盘,**直接打字**,内容实时敲进电脑;
   下方按钮发 Tab / Esc / 方向键 / 回车 / Ctrl 组合键等。
4. 「触控板」页:单指滑=移动鼠标,轻点=左键,双指轻点=右键,双指上下滑=滚轮。
5. 想让页面更像 App:分享按钮 → **添加到主屏幕**,以后从桌面图标进。

### 使用要点(务必看)

- 电脑端**先点好光标位置**,再在手机上输入;文字落在电脑光标处。
- 镜像文本框内容 = 你从“同步”以来输入的全部内容。若电脑端文本被
  撤销/切换窗口/别的程序改动过,先点「**镜像同步**」再继续,否则会错位。
- 含中文的段落走“剪贴板粘贴”注入,会临时占用电脑剪贴板(可接受)。

## 三、手机端 —— 原生 iOS App(可选,需要 Mac)

iOS App 与网页版功能一致、走同一协议,但没有浏览器外壳、体验更“App”。
**编译 iOS 必须用 Mac + Xcode**(Windows 上无法编译 Swift)。
完整步骤(建工程、放代码、Info.plist、免费 Apple ID 侧载、7 天续签、
以及没有 Mac 时的替代方案)见 **[ios/README-iOS.md](ios/README-iOS.md)**。

## 怎么分享给其他人(对方也是 iPhone + Windows)

别人的用法和你一样:**他电脑上双击运行服务,手机浏览器打开网址即可**。
给对方 [Releases](../../releases) 里打好的 `QuietType-1.0.0.exe` 即可,
不必给对方装 Python:

1. 对方下载 `QuietType-1.0.0.exe`(连不上时再给 `firewall_allow.bat`),
   双击运行(Windows 若提示"已保护你的电脑",点"更多信息 → 仍要运行"即可,
   因为 exe 未做商业签名);
2. 保持黑窗口开着,手机上 **相机扫窗口里的二维码**(或手动打开窗口里网址)
   → 允许本地网络 → 开始用。

重新打包 exe 的方法(本仓库开发机):`pip install pyinstaller` 后执行
`python build_exe.py`;或在 [Releases](../../releases) 下载 CI 构建的版本。
杀毒软件可能对 PyInstaller 产物误报;如担心可改用源码运行(run.bat,需装 Python)。

关于"原生 iOS App 给别人用":免费 Apple ID 只能给自己的设备签名;
要分发给任意第三方,需要 $99/年 开发者账号(TestFlight / Ad Hoc 按 UDID 添加)
或上架 App Store——与本项目"不进 App Store"的目标相悖。**网页版功能与原生壳
完全一致且零安装,是分享给别人的推荐路径**。
CI 已能产出未签名 IPA([Releases](../../releases) 的 `QuietType-ios-*` 标签),
爱折腾的用户可用 AltStore / Sideloadly 自签安装,步骤见
[ios/README-iOS.md](ios/README-iOS.md);有 Mac 的朋友也可以拿 `ios/`
源码自行编译侧载。

## 常见问题

| 现象 | 处理 |
|------|------|
| 手机打不开页面 | 同 Wi-Fi?防火墙放行?网址里 IP 对不对(电脑上 `ipconfig` 查 IPv4)? |
| 页面能开但打字没反应 | 先点电脑端窗口/编辑器让光标就位;确认状态点绿;点「镜像同步」 |
| 记事本能打、网页/聊天框打不进 | 网页富文本编辑器(如 Lexical)不认逐字注入 → 点「**文本:粘贴**」切到粘贴模式 |
| 中文打出来是乱码/不显示 | 非 ASCII 走粘贴,检查目标是否普通文本框;终端里粘贴中文请用正常输入法场景 |
| 按键偶尔重复/丢失 | 属注入节奏的正常现象,放慢输入即可;代码编辑器一般无感 |
| 鼠标太飘/太钝 | 触控板页调灵敏度滑块 |

## 局限与安全

- 面向**自用局域网**:无加密,仅 token 防误连;别在公共 Wi-Fi 开太久。
- 手机只能“打字/按键/鼠标”,不能看到电脑屏幕(那是另一类远程桌面工具)。
- 主机端注入的是全局键盘/鼠标事件:输入期间请勿同时手敲键盘,避免串键。
