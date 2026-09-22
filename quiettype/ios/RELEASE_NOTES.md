## QuietType iOS 1.0.0 —— 把 iPhone 变成 Windows 的静音键盘 / 触控板

笔记本键盘太吵？把输入挪到手机上：iPhone 上打字、划触控板，内容实时注入
Windows 电脑，效果等同亲手敲键盘。深夜宿舍/图书馆不吵人。

**这不是蓝牙 HID**（iOS 不开放蓝牙键盘模拟），而是：局域网 Wi-Fi 传输 +
Windows 端软件模拟（SendInput 注入）。电脑端零依赖，纯 Python 标准库。

### 这是什么

原生 iOS App（SwiftUI，iPhone 竖屏）。手机端维护一个镜像文本框，每次真实编辑
只把「删了几个字符 + 插入了什么」增量发给电脑，撤销历史保持自然；触控板支持
单指移动、轻点=左键、双指轻点=右键、双指上下滑=滚轮。

### 使用前提

- **Windows 电脑**：下载本仓库源码 zip，解压后双击 `quiettype/run.bat`
  （或下载 [QuietType 主机端](#) 后运行），记下窗口里显示的 IP 和端口（默认 8567）
- **iPhone**：iOS 16.0 及以上，与电脑连**同一个 Wi-Fi**
- 电脑端先运行，App 里填 IP → 连接 → 开打

### 安装（未签名 IPA，侧载）

iOS 不像 Android 能直接装 APK——**必须用自己的 Apple ID 重签**。免费 Apple ID
即可（签名 7 天有效，到期重签一次；$99/年开发者账号可管一年）。

**Windows 用户（推荐 Sideloadly，全程图形界面）：**

1. iPhone 数据线连电脑，电脑安装 [Sideloadly](https://sideloadly.io/)（免费）
2. iPhone 上：设置 → 通用 → 传输或还原 iPhone → 先做一次本地备份（Sideloadly 要求，
   不会覆盖现有数据）；**iOS 16+ 还需：设置 → 隐私与安全性 → 开发者模式 → 打开并重启**
3. Sideloadly 里登录你的 Apple ID，选择下载的
   `QuietType-ios-1.0.0-unsigned.ipa`，Start 安装
4. iPhone 上：设置 → 通用 → VPN 与设备管理 → 信任你的开发者证书
5. 打开 QuietType，首次弹「本地网络」权限时点允许

**Mac 用户：** AltStore / Sideloadly 同样适用，流程与上面一致。

到期续签：重跑一次 Sideloadly/AltStore 即可（App 数据保留）。

### 已知限制

- 未签名 / 未公证：这是 iOS 分发的常态，不是 bug；嫌 7 天续签麻烦可以换
  $99/年开发者账号（安装后一年有效），或等仓库后续走 TestFlight
- 本 IPA 由 CI 自动构建，**首次面向真机发布**：如遇界面或输入异常，先用功能完全
  一致的网页版（电脑运行 `run.bat`，iPhone Safari 打开 `http://电脑IP:8567`，
  可「添加到主屏幕」），并到 issue 反馈
- token 只防局域网误连、不做强安全，面向自用场景

### 不想折腾安装？

**网页版零安装、零签名**：电脑双击 `quiettype/run.bat`，iPhone Safari 打开
提示的地址即可，功能与原生 App 一致。
