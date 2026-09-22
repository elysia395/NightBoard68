# QuietType iOS App —— 安装（侧载）与编译

## 零、已发布安装包（大多数人看这里）

[Releases](../../releases) 里有 `QuietType-ios-1.0.0-unsigned.ipa`：
**未签名 IPA，用自己的 Apple ID 重签后即可安装，全程不需要 Mac。**

iOS 不像 Android 能直接装 APK，任何分发方式（含 App Store）都绕不开 Apple 签名；
免费 Apple ID 的侧载签名 **7 天有效**，到期用同一工具重签一次即可（App 数据保留），
$99/年开发者账号的签名有效期 1 年。

### Windows 用户：Sideloadly（推荐，图形界面）

1. iPhone 数据线连电脑，电脑装 [Sideloadly](https://sideloadly.io/)（免费）
2. iPhone：设置 → 通用 → 传输或还原 iPhone → 做一次本地备份（Sideloadly 要求，
   不会动现有数据）；**iOS 16+ 还要打开 开发者模式**：
   设置 → 隐私与安全性 → 开发者模式 → 打开并重启
3. Sideloadly 登录你的 Apple ID → 选择下载的 IPA → Start
4. iPhone：设置 → 通用 → VPN 与设备管理 → 信任你的开发者证书
5. 打开 QuietType，弹「本地网络」权限时点允许；填电脑 IP（端口默认 8567）→ 连接 → 开打

### Mac 用户

AltStore / Sideloadly 同样适用，流程与上面一致。

### 到期续签

重跑一次 Sideloadly / AltStore 即可；或把 iPhone 插回 Mac 用 Xcode 再 ⌘R 一次。

### 不想折腾

**网页版零安装、零签名**：电脑双击 `quiettype/run.bat`，iPhone Safari 打开
`http://电脑IP:8567`（可「添加到主屏幕」伪装成 App），功能与原生 App 一致。

## 一、开发者：工程结构（XcodeGen）

本目录用 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 描述工程，
**`.xcodeproj` 不入库**，由 `project.yml` 现生成（CI 同样如此）：

```bash
brew install xcodegen
cd quiettype/ios
xcodegen generate      # 生成 QuietType.xcodeproj
```

| 文件 | 作用 |
| --- | --- |
| `project.yml` | 工程描述（目标 iOS 16+、Bundle ID、无签名设置） |
| `Info.plist` | 静态 Info.plist：本地网络权限说明 + ATS 允许 http 明文 |
| `QuietType/` | 7 个 Swift 源文件 + `Assets.xcassets`（App 图标） |
| `QuietTypeApp.swift` | App 入口 |
| `ContentView.swift` | 连接页 + 根视图 |
| `Network.swift` | 与 Windows 主机通信（取 token、POST 协议消息） |
| `KeyboardMirror.swift` | 镜像文本框增量 diff |
| `MirrorTextView.swift` | UITextView 包装（处理中文输入法组合） |
| `RemoteView.swift` | 键盘 / 触控板主界面 |
| `TouchpadView.swift` | 触控板手势（多点触摸） |

### 本机编译 / 真机运行

```bash
xcodebuild -project QuietType.xcodeproj -scheme QuietType \
  -configuration Release -destination 'generic/platform=iOS' build
```

Xcode 里打开生成的工程，选你的 iPhone 为运行目标，`Signing & Capabilities` 选你的
Team（免费 Apple ID 即可），⌘R 运行。首次在手机上需信任证书（见上文第 4 步）。

### CI 自动出包

推送到本分支的 `quiettype-ios-*` tag 会触发
`.github/workflows/release-ios-app.yml`：macOS runner 上 XcodeGen 生成工程 →
**无签名构建** → 打包成 `Payload/`  zip  IPA → 发布 GitHub Release。
仓库里不需要任何签名证书；安装时的重签由侧载工具或开发者证书完成。

## 二、改 Bundle ID / 端口 / 图标

- Bundle ID：改 `project.yml` 的 `PRODUCT_BUNDLE_IDENTIFIER`
- 端口：App 里端口框填主机端 `--port` 的同一数值（默认 8567）
- 图标：替换 `QuietType/Assets.xcassets/AppIcon.appiconset/icon-1024.png`
  （单尺寸 1024×1024，Xcode 14+ 通用图标）

## 备注

- Swift 源码早期在纯 Windows 环境编写；现已由 CI 真机架构编译验证，
  但这是**首次真机分发**，如有问题欢迎带日志反馈（App 内问题优先看
  `quiettype/run.bat` 窗口的服务端日志）
- 协议见 [`../PROTOCOL.md`](../PROTOCOL.md)，网页版与 iOS App 两边通用
