# QuietType iOS App —— 编译与侧载(不进入 App Store)

## 前提(硬性)

- **一台 Mac**(自己的、借的、或云端 Mac,如 MacinCloud)——iOS 只能用
  macOS + Xcode 编译。只有 Windows 编不了 Swift,**请先用网页版**。
- Mac 上装好 **Xcode**(App Store 免费,≥ 15)。
- iPhone(iOS 15+)+ 数据线,或与 Mac 登录同一 Apple ID。
- **免费 Apple ID** 即可侧载(无需 $99)。iOS 16+ 需要先到
  iPhone「设置 → 隐私与安全性 → 开发者模式」打开并重启。

## 一、在 Xcode 里建工程并放入源码

1. `File → New → Project… → iOS → App`
   - Product Name:`QuietType`;Interface:**SwiftUI**;Language:**Swift**。
   - Bundle Identifier 填唯一值,如 `com.你的名字.quiettype`(小写字母/数字/点)。
2. 工程会生成默认的 `QuietTypeApp.swift` 与 `ContentView.swift`。
   把本目录 `QuietType/` 里的 **7 个 .swift 文件**全部拷进工程
   (同名文件直接覆盖默认的两个;其余 5 个拖入工程,勾选 Target 成员):

   | 文件 | 作用 |
   |------|------|
   | QuietTypeApp.swift | App 入口 |
   | ContentView.swift   | 连接页 + 根视图 |
   | Network.swift       | 与 Windows 主机通信(取 token、POST 协议消息) |
   | KeyboardMirror.swift| 镜像文本框增量 diff |
   | MirrorTextView.swift| UITextView 包装(处理中文输入法组合) |
   | RemoteView.swift    | 键盘 / 触控板主界面 |
   | TouchpadView.swift  | 触控板手势(多点触摸) |

3. 打开 `Info.plist`(工程里找),加入 `ios/Info.plist.extra.txt` 的内容:
   - `NSLocalNetworkUsageDescription`:首次连局域网会弹权限说明;
   - `NSAppTransportSecurity → NSAllowsArbitraryLoads = YES`:允许 http 明文连局域网 IP。

## 二、签名与真机运行

1. Xcode 左上选你的 **iPhone** 作为运行目标(先插线)。
2. `Signing & Capabilities → Team`:选你的 Apple ID
   (第一次要在 Xcode `Settings → Accounts` 里登录并信任)。
3. `⌘R` 运行。首次在手机上:**设置 → 通用 → VPN 与设备管理 →
   信任你的开发者证书**;弹出“本地网络”权限时选允许。
4. 打开 App,填电脑 IP(端口默认 8567)→ 连接 → 开打。

## 三、免费账号 7 天续签(重要)

免费 Apple ID 的开发者证书 **7 天过期**,之后 App 打不开,需要重签:

- 最省事:每次到期前,把 iPhone 插回 Mac,Xcode 里再 `⌘R` 一次(自动重签)。
- 或用 Windows 也能操作的方式:在 Mac 上 `Product → Archive → Distribute App`
  导出一次 **.ipa**,之后在 Windows 上用 **AltStore / Sideloadly**
  用你的 Apple ID 重签续期,不再需要 Mac。
- 想一年免管:$99/年 的个人开发者账号(Apple Developer Program),同一流程。

## 四、没有 Mac 的替代方案

网页版功能一致、零编译:电脑运行 `run.bat`,iPhone Safari 打开
`http://电脑IP:8567` 即可(可“添加到主屏幕”伪装成 App)。
本仓库的协议(PROTOCOL.md)两边通用,日后有 Mac 随时把原生壳补上。

## 备注

- Swift 源码在纯 Windows 环境编写、无法本机编译验证;如遇编译报错,
  多半是 API 名称/签名小差异,按 Xcode 提示微调即可(结构都很直白)。
- 需要改端口时,App 端端口框填主机端 `--port` 的同一数值。
