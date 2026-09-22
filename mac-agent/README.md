# mac-agent — NightBoard68 macOS 局域网接收端

NightBoard68 的「局域网模式」原本只有 Windows 电脑端代理（`pc-agent/NightBoardAgent.exe`，
C# + Win32 SendInput）。本目录是等价的 **macOS 版本**：菜单栏常驻 App，单文件 Swift 源码，
零第三方依赖，通过 CoreGraphics `CGEvent` 注入合成键鼠事件。

协议与 Windows 版逐字段一致（`app/src/main/java/com/nightboard/keyboard68/LanKeyboard.kt`
是手机端实现，可对照）：

| 方向 | 消息 | 说明 |
| --- | --- | --- |
| 手机→电脑 | `{"t":"hello","v":1,"n":"机型"}` | TCP 连上后的握手 |
| 手机→电脑 | `{"t":"kd","c":HID}` / `{"t":"ku","c":HID}` | 按键按下 / 抬起 |
| 手机→电脑 | `{"t":"m","dx":..,"dy":..,"w":..,"b":..}` | 鼠标相对移动 / 滚轮 / 按键位 |
| 手机→电脑 | `{"t":"ra"}` | 释放全部（切模式、退出键盘页时） |
| 手机→电脑 | `{"t":"p","i":id}` | 心跳 ping（每秒一次） |
| 电脑→手机 | `{"t":"po","i":id}` | pong |
| 电脑→手机 | `{"t":"led","c":mask}` | 键盘灯（bit1 = CapsLock） |
| 电脑→手机 | `{"t":"disc","n":计算机名,"p":6868,"v":1}` | UDP 6869 发现应答 |

UDP 6869（自动发现）+ TCP 6868（输入传输），换行分隔 UTF-8 JSON。

## 与 Windows 版的差异（必要适配）

- 注入 API：`SendInput` → `CGEventPost(tap: .cghidEventTap)`；
  macOS 额外要求 **辅助功能权限**，未授权时注入被系统静默丢弃（菜单栏 ⚠️ 提示 + 一键跳转设置）
- 键码表：Windows 扫描码 → macOS 虚拟键码（`kVK_*`）。注意字母/数字是
  **QWERTY 物理序**而非字母序，F 区键码不连续，均逐项映射
- 长按连发、断线补发抬起、只保留最新一台手机等行为与 Windows 版一致
- 鼠标拖选中发送 `.leftMouseDragged`（AppKit 拖选需要 dragged 事件而非 moved）
- NumLock / ScrollLock 在 Mac 键盘上不存在，灯态恒为灭；CapsLock 可读并回传

## 自己编译

```bash
# 本机架构
swiftc -O -parse-as-library -o NightBoardAgent NightBoardAgent.swift \
  -framework Foundation -framework AppKit \
  -framework CoreGraphics -framework ApplicationServices

# 通用二进制（Intel + Apple Silicon）
for ARCH in x86_64 arm64; do
  swiftc -O -parse-as-library -target "${ARCH}-apple-macos12.0" -o "agent-${ARCH}" NightBoardAgent.swift \
    -framework Foundation -framework AppKit \
    -framework CoreGraphics -framework ApplicationServices
done
lipo -create agent-x86_64 agent-arm64 -output NightBoardAgent
```

把可执行文件放进 `NightBoardAgent.app/Contents/MacOS/`，配 `Info.plist`
（`LSUIElement = true` 即无 Dock 图标的菜单栏 App）即可运行。

## 发布

推送到 `NightBoard68-mac` 分支的形如 `v1.4.2-mac.1` 的 tag 会自动触发
`.github/workflows/release-mac-agent.yml`：macOS runner 编译通用二进制 →
打 DMG → 发布 GitHub Release（Release 文案取 `RELEASE_NOTES.md`）。
