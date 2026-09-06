# NightBoard68

把 Android 手机横过来，变成一台 **68 键 65% 配列的蓝牙键盘（+ 触控板）**。
电脑把手机识别成一台真正的蓝牙 HID 设备，**电脑端无需安装任何软件**。

> English: NightBoard68 turns your Android phone into a 68-key (65% layout)
> Bluetooth HID keyboard + touchpad for your PC. No companion software needed
> on the computer — the phone shows up as a real Bluetooth keyboard.
> Requires Android 9+. See [usage](#使用) below. (README is mainly in Chinese.)

## 为什么做这个（开发缘由）

我喜欢晚上在宿舍学编程，但机械键盘的敲击声会吵到已经休息的舍友。
声音进不了耳机——只要还想敲代码，噪音问题就绕不过去。

于是我想到：**手机触屏打字是零噪音的**，而且手机就在手边、横过来尺寸正好
接近一块 60% 机械键盘。如果手机能伪装成一台真正的蓝牙键盘，电脑零安装、
不占充电口、不依赖网络，就是完美的深夜键盘。

调研后发现现成的开源方案（如 [android-bt-remote](https://github.com/jqssun/android-bt-remote)、
[Atharok/BtRemote](https://gitlab.com/Atharok/BtRemote)）已经实现了「手机变蓝牙键盘」，
但它们的键盘界面都是为遥控/随手输入设计的通用布局——**没有人做 65% 标准配列，
没有人为主力打字/写代码优化横屏体验**。

所以就有了 NightBoard68：

- **68 键 65% 配列**：数字排、右下方向键、Fn 组合出 F 区和编辑键，键位与主流
  65% 机械键盘一致，肌肉记忆无缝迁移
- **触屏专属交互**：修饰键点按锁定（点 Ctrl → 点 C = Ctrl+C，单指就能完成），
  这是实体键盘做不到的
- **零噪音**：夜里只听得见你的思路
- **零安装、零依赖**：电脑什么都不用装；App 本身也只有 2MB、零第三方库

## 适用场景

- 🌙 **宿舍夜学**：舍友睡了，你想敲代码——这是它诞生的场景
- 📚 **图书馆 / 自习室**：没有键盘或不想带键盘的时候
- 🛋️ **躺沙发 / 躺床上**：控制客厅电脑、HTPC，键盘+触控板二合一
- 🖥️ **临时演示 / 抢救**：键盘坏了、没电了，手机应急顶上
- 📺 **Android TV / 投影**：给电视盒子输入文字

## 功能

### 键盘
- 68 键 65% 配列，多指同按（左手 Ctrl 右手 C）
- 修饰键**点按锁定**：点一下 Ctrl（橙框亮起）→ 点 C → 自动释放；再点一次取消
- 修饰键**组合发送**：Ctrl 已锁定时点 Shift = 发出 Ctrl+Shift（切换输入法）；
  已锁定的 Shift 再点一次 = 单发 Shift（中文输入法切中英文）
- **Fn 层**：数字排 → F1~F12，Esc → `` ` ``，Del → PrtSc，PgUp/PgDn ↔ Home/End
- 按住自动连发；Caps Lock 状态由电脑回传、键帽实时点亮
- 键缝命中兜底：快速敲击落在键缝上也能正确判定，不留死区

### 触控板
- 顶部按钮一键切换：整屏变大触控板
- 单指移动 / 轻点=左键 / 双指轻点=右键 / 双指滑动=滚轮

### 连接与保活
- 前台服务保活：退出键盘页、锁屏不断连，通知栏一键回到键盘
- 自动回连上次连接的电脑；也可在主页手动点击重连

### 设置
- 触感反馈强度 0~255 无级调节（拖动实时试震）
- 键盘页面独立亮度（最低 5%，夜间不刺眼，退出自动恢复）
- 修饰键模式切换（点按锁定 ↔ 按住生效）
- 自动回连开关

## 配列

```
Esc  1  2  3  4  5  6  7  8  9  0  -  =  Backspace   Del
Tab  Q  W  E  R  T  Y  U  I  O  P  [  ]  \           PgUp
Caps A  S  D  F  G  H  J  K  L  ;  '  Enter          PgDn
Shift   Z  X  C  V  B  N  M  ,  .  /  Shift   ↑      End
Ctrl Win Alt        Space        Alt Fn Ctrl  ←  ↓  →
```

## 要求

- 手机：Android 9.0+（使用系统 `BluetoothHidDevice` profile）
- 电脑：任何支持蓝牙键盘/鼠标的系统（Windows / macOS / Linux / Android TV）

## 使用

1. 从 [Releases](../../releases) 下载 APK 安装到手机，打开并授予
   「附近的设备」权限，等主页显示「键盘就绪」
2. 点 **「让电脑发现我」**，然后电脑：设置 → 蓝牙和其他设备 → 添加设备 → 蓝牙
3. 选择**手机的蓝牙名字**（键盘是手机上注册的服务，不会出现叫
   NightBoard68 的独立设备），手机上确认配对
4. 点「开始打字」，电脑光标点进输入框直接敲

> **提示**：如果电脑以前把这台手机当手机配对过，请先删除旧配对再重新添加，
> 否则电脑读不到键盘服务。以后升级 APK **不需要**重新配对（除非 Release 说明
> 里特别提到描述符变更）。

## 自己编译

```bash
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

零第三方依赖，只需要 Android SDK（compileSdk 34）和 JDK 17+。
改键位映射只需编辑
[`app/src/main/java/com/nightboard/keyboard68/KeyLayout.kt`](app/src/main/java/com/nightboard/keyboard68/KeyLayout.kt)。

## 技术实现（一句话版）

`BluetoothHidDevice`（Android 9+ 的 HID Device profile）注册键盘+鼠标组合设备
（Report ID 1 = 键盘 8 字节报告，Report ID 2 = 鼠标 4 字节报告），电脑把它当
原生蓝牙 HID 设备；UI 为单 View 自绘（`onDraw` 画键帽 + `onTouchEvent` 多指
调度），命中判定带 hit-slop 兜底。

## 已知限制

- 组合键（如 Ctrl+Shift 切输入法）有 300ms 延迟——这是在「组合键」和
  「修饰键+字母」之间消歧的设计取舍
- 部分国产 ROM（MIUI/ColorOS 老版本）对 HID device profile 有限制，
  若一直显示「正在注册蓝牙键盘…」请反馈机型
- 触控板滚轮方向如与习惯不符可在 issue 里喊一声，一行符号的事

## 致谢与参考

- [Arian04/android-hid-client](https://github.com/Arian04/android-hid-client) 与
  [jqssun/android-bt-remote](https://github.com/jqssun/android-bt-remote) /
  [Atharok/BtRemote](https://gitlab.com/Atharok/BtRemote) —— 验证了
  `BluetoothHidDevice` 路线的可行性
- [USB HID Usage Tables](https://usb.org/document-library/hid-usage-tables-and-digitizers-table-reference-guides) /
  [Understanding HID report descriptors](http://who-t.blogspot.com/2018/12/understanding-hid-report-descriptors.html)

## 许可

[MIT](LICENSE)
