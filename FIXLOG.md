# FIXLOG — 近两次 bug 修复记录

> 报告周期：蓝牙连发 bug 之后到现在（局域网 / 蓝牙双通道）。

## 1. 蓝牙模式：长时间不用 / 切后台回来，第一次按键卡键连发

- **现象**：回到应用后第一次点击键盘，某键在电脑端连打到上限停不下来。
- **根因**：HID 键盘报告是「当前按住键集合」，`keyUp` 先清集合再 `sync()` 重发；发送失败（`host`/`hidDevice` 瞬时为空被静默 `return`、`sendReport` 异常被吞）时**手机端认为已抬起、电脑端永远收不到抬起** → OS 认为键按住自动连发；且蓝牙重连建立后不重发状态，抬起永不会补发。
- **修复**（[HidKeyboard.kt](app/src/main/java/com/nightboard/keyboard68/HidKeyboard.kt)）：
  - `sync()` 发送失败 / 连接未就绪时置 `dirty`，状态不丢；
  - 蓝牙重连建立（CONNECTED）时若 `dirty` 或仍有按键/修饰位 → 补发全量当前报告（无按键则发空报告解除卡键）。

## 2. 蓝牙「假连接」兜底：链路半挂起时第一次按键先对齐

- **现象**：链路被系统/驱动挂起但未触发断连回调（`sendReport` 不抛异常、不重连），keyUp 静默未送达，卡键可能持续。
- **修复**：记录上次成功发送时刻 `lastOkSyncAt`；`keyDown` 前若连 3 秒无成功发送（`RESYNC_IDLE_MS`），先补发一次当前状态对齐电脑端。发送失败进 `dirty` 转交重连补发，两路兜底互通；正常使用 3 秒内有交互不触发，无副作用。

## 3. 局域网：主键盘方向键在 NumLock 开时变成数字（8/2/4/6）

- **根因**：Agent 方向键用扫描码方案，靠 `KEYEVENTF_EXTENDEDKEY`(E0 前缀) 与数字小键盘 8/2/4/6 区分；部分环境 E0 前缀不生效 → 被 Windows 认成小键盘键，NumLock 开时输出数字。
- **修复**（[NightBoardAgent.cs](pc-agent/NightBoardAgent.cs)）：编辑键区 Insert/Home/PgUp/Delete/End/PgDn/←↓→↑ 全部改走 **VK 注入**，与 NumLock、键盘布局无关。

## 4. 局域网：概率反复断连 + 触控板只能移动、点击全失效（同一根因）

- **根因A**：心跳 ping 与所有输入事件共用**同一 512 队列**；触控板刷屏（12ms 一次 + 惯性）打满队列时 ping 入队失败被静默丢弃 → 连续 3 次 → 误判断线重连 → 循环；重连窗口内 `active()==null` 所有点击被丢弃 → 表现为「只能移动、鼠标键和单击无效」。
- **修复A**（[LanKeyboard.kt](app/src/main/java/com/nightboard/keyboard68/LanKeyboard.kt)）：心跳 ping **直写 socket**（不走输入队列，与 writer 共用 `writeLock` 防字节交错）；输入队列 512 → 2048。
- **根因B**：重连过快时旧连接的心跳线程未退出，新连接又起一个心跳线程，两者共用 `pongSeen/misses` 互相干扰 → 再次误判断线。
- **修复B**：`connGen` 连接代号，新连接自增，旧 writer/心跳线程发现代号过期即自灭。

## 5. 局域网：鼠标中键无效

- **根因**：手机端中键 = bit2(4)，Agent `HandleMouse` 只处理 bit0/bit1。
- **修复**（NightBoardAgent.cs）：补 `MOUSEEVENTF_MIDDLEDOWN/UP`(0x20/0x40) 中键边沿检测。

## 6. 局域网：NumLock 状态切换后不反映

- **根因**：Agent 只在 CapsLock(0x39) 切换后回传 LED；NumLock(0x53)/ScrollLock(0x47) 从不回传。
- **修复**（NightBoardAgent.cs）：`IsLedToggle()` 覆盖 Caps/Num/Scroll 三种切换键，按下/抬起都 `PostLed()` 回传最新 LED 掩码。

## 7. 局域网：部分程序（如以管理员运行的 IDE）键盘/触控板无反应

- **根因**：Windows **UIPI**（用户界面特权隔离）禁止普通权限进程向管理员权限（高完整性）窗口注入 SendInput → 错误码 5，普通窗口正常、管理员窗口全失效。属系统安全机制，非软件 bug。
- **修复与提示**（NightBoardAgent.cs）：启动时检测自身权限并提示；`SendInput` 被拦截（错误码 5）时明确指出原因。**解决办法：右键本 exe →「以管理员身份运行」，或在文件属性 → 兼容性 → 勾选「以管理员身份运行此程序」**，之后即可向管理员窗口注入（对普通窗口同样正常）。

## 8. 小键盘「修改模式」显示（NumLock 状态联动，主机权威）

- 确认 NumLock 关：带备选标注的键**整体切换键帽**（7→Home、8→↑、9→PgUp、4→←、6→→、1→End、2→↓、3→PgDn、0→Ins、.→Del），底色高亮 + 橙色描边 + 橙色文字（同 Fn 激活样式）；顶部不再重复显示右上角小字。
- 确认 NumLock 开：数字主标签，**Num 键点亮**指示当前为数字模式，其余键右上角灰色提示。
- 未知（未收到 LED）：保持数字 + 灰色标注，不猜测。

## 9. 大写锁定键帽缺一层颜色

- **根因**：Caps 点亮时只加橙色描边，**底色填充分支漏了 capsLit**，与 Shift/Ctrl/Fn 锁存样式不一致。
- **修复**（KeyboardView.kt）：`capsLit -> colorKeyLatched` 底色填充，叠加橙色描边 + 橙色文字，样式统一。

## 部署

| 侧                                                 | 重新构建                             |
| -------------------------------------------------- | ------------------------------------ |
| 手机端（HidKeyboard / KeyboardView / LanKeyboard） | `gradle assembleDebug`，装新 APK     |
| 电脑端 Agent（3/5/6/7 在 Agent 侧）                | 运行 `pc-agent\build.bat` 生成新 exe |
