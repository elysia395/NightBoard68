package com.nightboard.keyboard68

import android.content.Context
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper

/**
 * 输入枢纽：蓝牙模式 / 局域网模式两个完全独立的连接，用户按场景自行选择。
 *
 *  - 蓝牙模式：电脑零安装（系统原生 HID），速度一般 —— 对应 hid 通道
 *  - 局域网模式：电脑跑 NightBoardAgent，延迟低、触控板跟手 —— 对应 lan 通道
 *  - 两个通道不并行：当前模式下另一条通道直接停用（蓝牙模式注销 HID 注册，
 *    局域网模式断开 TCP），状态显示、输入路由只认当前模式
 *  - 切换入口：主页模式选择器、两个键盘页右上角的模式按钮
 *
 * View 层只跟本类对话，不直接持有 HidKeyboard / LanKeyboard。
 */
class InputHub(
    context: Context,
    val hid: HidKeyboard,
    val lan: LanKeyboard,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private enum class Channel { BLUETOOTH, LAN }

    /** 当前连接模式："bt" | "lan"（主页/键盘页切换，实时读，SharedPreferences 有内存缓存） */
    val mode: String
        get() = prefs.getString("conn_mode", MODE_BT) ?: MODE_BT

    companion object {
        const val MODE_BT = "bt"
        const val MODE_LAN = "lan"
    }

    // ---------- 链路状态 ----------

    val btConnected: Boolean get() = hid.hostName() != null
    val lanConnected: Boolean get() = lan.isConnected

    /** 蓝牙键盘服务是否已注册（不代表电脑已连） */
    val btReady: Boolean get() = hid.isRegistered()

    /** 当前模式是否已连上电脑，null = 未连接 */
    fun activeChannelName(): String? = if (mode == MODE_LAN) {
        if (lanConnected) "局域网" else null
    } else {
        if (btConnected) "蓝牙" else null
    }

    /** 大写锁定状态取自当前模式的通道（电脑 LED 回传） */
    val capsOn: Boolean get() = if (mode == MODE_LAN) lan.capsOn else hid.capsOn

    /** 状态条文案（横屏键盘 / 单手模式 / 通知栏 / 主页共用） */
    fun statusLine(): String = if (mode == MODE_LAN) {
        when {
            lanConnected && lan.rttMs >= 0 -> "局域网●${lan.rttMs}ms"
            lanConnected -> "局域网●"
            lan.state == LanKeyboard.State.CONNECTING -> "局域网◌连接中"
            else -> "局域网○搜索电脑"
        }
    } else {
        when {
            btConnected -> "蓝牙●"
            btReady -> "蓝牙○等待"
            else -> "蓝牙×未就绪"
        }
    }

    // ---------- 输入路由（只认当前模式） ----------

    private fun active(): Channel? = if (mode == MODE_LAN) {
        if (lanConnected) Channel.LAN else null
    } else {
        if (btConnected) Channel.BLUETOOTH else null
    }

    private fun send(event: (Channel) -> Boolean): Boolean {
        val target = active() ?: return false
        return try {
            event(target)
        } catch (_: Exception) {
            false
        }
    }

    // ---------- 输入 API（KeyboardView / OneHandView 调这些） ----------

    fun modDown(bit: Int) {
        send { c -> c.sendModDown(bit) }
    }

    fun modUp(bit: Int) {
        send { c -> c.sendModUp(bit) }
    }

    /** 瞬时发出一组修饰键并自动抬起（70ms）：单发 Shift 切中英文等 */
    fun tapMods(bits: Int) {
        if (bits == 0) return
        if (!send { c -> c.sendModDown(bits) }) return
        main.postDelayed({
            send { c -> c.sendModUp(bits) }
        }, 70)
    }

    fun keyDown(code: Int, latchedMods: Int) {
        // 修饰键先落下，再落主键，保证电脑端看到完整组合
        if (latchedMods != 0) send { c -> c.sendModDown(latchedMods) }
        if (send { c -> c.sendKeyDown(code) }) oneShotModBits = latchedMods
    }

    fun keyUp(code: Int) {
        // 抬键时把可能带的一次性修饰键一起抬起（先键后修饰，顺序与 HID 报告一致）
        send { c -> c.sendKeyUp(code) }
        val bits = oneShotModBits
        if (bits != 0) send { c -> c.sendModUp(bits) }
        oneShotModBits = 0
    }

    /** 记录最近一次 keyDown 携带的修饰键，keyUp 时对应抬起 */
    @Volatile
    private var oneShotModBits = 0

    fun releaseAll() {
        oneShotModBits = 0
        try {
            lan.releaseAll()
        } catch (_: Exception) {
        }
        try {
            hid.releaseAll()
        } catch (_: Exception) {
        }
    }

    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        send { c -> c.sendMouseEvt(dx, dy, wheel, buttons) }
    }

    private fun Channel.sendModDown(bit: Int): Boolean = when (this) {
        Channel.LAN -> bitDownOverLan(bit)
        Channel.BLUETOOTH -> hidRun { modDown(bit); true }
    }

    private fun Channel.sendModUp(bit: Int): Boolean = when (this) {
        Channel.LAN -> bitUpOverLan(bit)
        Channel.BLUETOOTH -> hidRun { modUp(bit); true }
    }

    private fun Channel.sendKeyDown(code: Int): Boolean = when (this) {
        Channel.LAN -> lan.keyDown(code)
        Channel.BLUETOOTH -> hidRun { keyDown(code, 0); true }
    }

    private fun Channel.sendKeyUp(code: Int): Boolean = when (this) {
        Channel.LAN -> lan.keyUp(code)
        Channel.BLUETOOTH -> hidRun { keyUp(code); true }
    }

    private fun Channel.sendMouseEvt(dx: Int, dy: Int, wheel: Int, buttons: Int): Boolean = when (this) {
        Channel.LAN -> lan.sendMouse(dx, dy, wheel, buttons)
        Channel.BLUETOOTH -> hidRun { sendMouse(dx, dy, wheel, buttons); true }
    }

    // 局域网的修饰键：映射成 HID 修饰键码（0xE0..）用 kd/ku 发送
    private fun bitDownOverLan(bits: Int): Boolean {
        var ok = true
        for (code in bitsToHidCodes(bits)) ok = lan.keyDown(code) && ok
        return ok
    }

    private fun bitUpOverLan(bits: Int): Boolean {
        var ok = true
        for (code in bitsToHidCodes(bits)) ok = lan.keyUp(code) && ok
        return ok
    }

    private fun bitsToHidCodes(bits: Int): IntArray {
        val codes = ArrayList<Int>(4)
        if (bits and Mods.LCTRL != 0) codes.add(0xE0)
        if (bits and Mods.LSHIFT != 0) codes.add(0xE1)
        if (bits and Mods.LALT != 0) codes.add(0xE2)
        if (bits and Mods.LGUI != 0) codes.add(0xE3)
        if (bits and Mods.RCTRL != 0) codes.add(0xE4)
        if (bits and Mods.RSHIFT != 0) codes.add(0xE5)
        if (bits and Mods.RALT != 0) codes.add(0xE6)
        if (bits and Mods.RGUI != 0) codes.add(0xE7)
        return codes.toIntArray()
    }

    private inline fun hidRun(block: HidKeyboard.() -> Boolean): Boolean =
        try {
            hid.block()
        } catch (_: Exception) {
            false
        }

    // ---------- 连接检查 ----------

    /**
     * 一键检查（只作用于当前模式）：
     * 局域网模式 → 重新发现/连接电脑；蓝牙模式 → 回连上次配对的电脑。
     * 结果通过链路状态回调推给 UI。
     */
    fun checkConnections() {
        if (mode == MODE_LAN) {
            if (!lanConnected) lan.reconnectNow()
            return
        }
        if (!btConnected && btReady) {
            val mac = prefs.getString("last_host_mac", null) ?: return
            val dev: BluetoothDevice = try {
                hid.adapter?.getRemoteDevice(mac) ?: return
            } catch (_: IllegalArgumentException) {
                return
            }
            try {
                if (dev.bondState == BluetoothDevice.BOND_BONDED) hid.connectHost(dev)
            } catch (_: SecurityException) {
            }
        }
    }
}
