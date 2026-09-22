## NightBoard68 macOS 局域网 Agent（首个 mac 版）

让 Mac 也能用「局域网模式」：手机通过 WiFi 直连 Mac，打字延迟 2~10ms（热点直连更低），触控板吞吐不受 HID 报告率限制。此前局域网模式的电脑端只有 Windows 版（`NightBoardAgent.exe`），本版补齐 macOS。

菜单栏常驻（无 Dock 图标），协议与 Windows 版**完全一致**，兼容 NightBoard68 v1.3.0 及以后的任意版本 APK。

### 安装

1. 下载 `NightBoardAgent-v1.4.2-mac.dmg`，打开后把 **NightBoardAgent.app** 拖到「应用程序」
2. **首次启动：在启动台/应用程序里 右键 → 打开**（未签名分发，Gatekeeper 会拦截直接双击；与 Windows 版被 Smart App Control 拦截属同类情况，之后启动不再提示）
3. 菜单栏出现 **NB68** 图标即运行中（显示 ⚠️ 表示还差最后一步）
4. **授予「辅助功能」权限**：点菜单里的 ⚠️ 提示直达
   *系统设置 → 隐私与安全性 → 辅助功能*，勾选 NightBoardAgent
   （macOS 要求：注入合成键鼠事件必须有此权限，否则 App 里显示连上但打不了字）
5. App 主页切到「局域网模式」，同一 WiFi 下自动发现你的 Mac；搜不到就在 App 设置里手动填 Mac 的 IP（系统设置 → Wi-Fi → 详细信息里看）

### 功能与行为（对齐 Windows 版）

- **菜单栏状态**：等待连接 / 已连接（显示手机机型名）、辅助功能权限状态、一键打开日志、退出
- **自动发现**：UDP 6869 应答手机广播；**输入传输**：TCP 6868，换行分隔 JSON
- **键位**：68 键全量 + 数字小键盘（v1.4.0 起 App 支持）+ F1~F12 + 编辑键区；
  Cmd/Win 键映射到 macOS Command，AltGr（右 Alt）映射 RightOption
- **长按连发**：按住退格/字母 450ms 后 30 次/秒连发，与蓝牙模式行为对齐
- **断线保护**：手机断线时自动补发所有按键/鼠标按键抬起，不会卡键
- **CapsLock 灯态回传**：Mac 上 Caps Lock 状态实时同步到 App 键帽
  （NumLock / ScrollLock 在 Mac 键盘上不存在，恒为灭）
- **通用二进制**：Intel + Apple Silicon 通吃，要求 macOS 12 Monterey 及以上

### 已知限制

- **未签名 / 未公证**：首次启动必须「右键 → 打开」；企业内网如完全禁止未签名 App 请自行用开发者证书重签
- 极少见 App 不接收系统合成事件，属 macOS 安全模型限制
- 暂不支持登录时自动启动（双击运行，与 Windows 版一致；后续版本可加）

### 自己编译

源码在 `mac-agent/NightBoardAgent.swift`（单文件，零第三方依赖）：

```bash
swiftc -O -o NightBoardAgent mac-agent/NightBoardAgent.swift \
  -framework Foundation -framework AppKit \
  -framework CoreGraphics -framework ApplicationServices
```

APK 与 Windows 版 Agent 请从对应的正式版 Release 获取（如
[v1.4.2](https://github.com/elysia395/NightBoard68/releases/tag/v1.4.2)）。
