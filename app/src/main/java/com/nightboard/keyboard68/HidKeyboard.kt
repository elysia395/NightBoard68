package com.nightboard.keyboard68

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * 把手机注册成一台蓝牙 HID 键盘+鼠标组合设备（Android 9+ 的 BluetoothHidDevice profile）。
 * 对端电脑把手机识别成普通蓝牙键盘，无需安装任何软件。
 *
 * 描述符是键盘/鼠标组合，用 Report ID 区分：
 *   Report ID 1 = 键盘，8 字节 [修饰键, 保留, 键码×6]（6 键无冲）
 *   Report ID 2 = 鼠标，4 字节 [按键, dx, dy, 滚轮]
 */
class HidKeyboard(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** 应用向系统注册/注销 HID 键盘 */
        fun onAppRegistered(registered: Boolean)

        /** 电脑连接状态变化，name 为 null 表示断开 */
        fun onHostChanged(name: String?)

        /** 电脑发回的键盘灯状态变化（大写锁定等） */
        fun onLedsChanged()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences("nightboard", Context.MODE_PRIVATE)

    /** 电脑端大写锁定状态（由 LED output report 回传） */
    @Volatile
    var capsOn = false
        private set

    val adapter: BluetoothAdapter?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false
    private var host: BluetoothDevice? = null

    // 键盘+鼠标组合描述符（Report ID 1 = 键盘，Report ID 2 = 鼠标）
    private val reportDescriptor = byteArrayOf(
        // ---------- 键盘 ----------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x01,    //   Report ID (1)
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(),
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95.toByte(), 0x08,
        0x81.toByte(), 0x02,    //   Input 修饰键 ×8
        0x95.toByte(), 0x01, 0x75, 0x08,
        0x81.toByte(), 0x01,    //   Input 保留字节
        0x95.toByte(), 0x06, 0x75, 0x08,
        0x15, 0x00, 0x25, 0x65,
        0x05, 0x07, 0x19, 0x00, 0x29, 0x65,
        0x81.toByte(), 0x00,    //   Input 键码 ×6
        0x05, 0x08,             //   Usage Page (LED)
        0x19, 0x01, 0x29, 0x03,
        0x95.toByte(), 0x05, 0x75, 0x01,
        0x91.toByte(), 0x02,    //   Output 键盘灯
        0x95.toByte(), 0x01, 0x75, 0x03,
        0x91.toByte(), 0x01,    //   Output 补位
        0xC0.toByte(),          // End Collection
        // ---------- 鼠标 ----------
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x02,    //   Report ID (2)
        0x09, 0x01,             //   Usage (Pointer)
        0xA1.toByte(), 0x00,    //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Buttons)
        0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x03, 0x75, 0x01,
        0x81.toByte(), 0x02,    //     按键 ×3
        0x95.toByte(), 0x01, 0x75, 0x05,
        0x81.toByte(), 0x03,    //     补位 5bit
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30, 0x09, 0x31, //     X / Y
        0x15, 0x81.toByte(), 0x25, 0x7F,   // Logical -128..127
        0x75, 0x08, 0x95.toByte(), 0x02,
        0x81.toByte(), 0x06,    //     Input (Data,Var,Rel)
        0x09, 0x38,             //     Wheel
        0x15, 0x81.toByte(), 0x25, 0x7F,
        0x75, 0x08, 0x95.toByte(), 0x01,
        0x81.toByte(), 0x06,    //     Input (Data,Var,Rel)
        0xC0.toByte(),          //   End Physical
        0xC0.toByte(),          // End Collection
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidKeyboard.registered = registered
            if (registered) autoReconnect()
            main.post { listener.onAppRegistered(registered) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                try {
                    prefs.edit().putString("last_host_mac", device.address).apply()
                } catch (_: SecurityException) {
                }
                main.post { listener.onHostChanged(safeName(device)) }
            } else if (host == device) {
                host = null
                main.post { listener.onHostChanged(null) }
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, reportId: Byte, data: ByteArray) {
            // LED output report：bit0 NumLock、bit1 CapsLock、bit2 ScrollLock
            if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && data.isNotEmpty()) {
                val caps = (data[0].toInt() and 0x02) != 0
                if (caps != capsOn) {
                    capsOn = caps
                    main.post { listener.onLedsChanged() }
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            hidDevice = proxy as BluetoothHidDevice
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "NightBoard68",                    // 电脑蓝牙列表里显示的名字（配对时）
                "68-Key Bluetooth Keyboard & Mouse",
                "nightboard",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                reportDescriptor,
            )
            try {
                registerAppCompat(sdp, callback)
            } catch (e: Exception) {
                Log.e(TAG, "registerApp 失败（权限被拒或系统限制）", e)
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            registered = false
            main.post { listener.onAppRegistered(false) }
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            main.post { listener.onAppRegistered(false) }
            return
        }
        a.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    fun stop() {
        try {
            hidDevice?.unregisterApp()
        } catch (_: Exception) {
        }
        hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        registered = false
    }

    fun isRegistered(): Boolean = registered && hidDevice != null

    fun hostName(): String? = host?.let { safeName(it) }

    fun bondedHosts(): Set<BluetoothDevice> = try {
        adapter?.bondedDevices ?: emptySet()
    } catch (_: SecurityException) {
        emptySet()
    }

    /** 主动连接一台已配对的电脑（重连用；一般配对成功后系统自动连接） */
    fun connectHost(device: BluetoothDevice): Boolean = try {
        hidDevice?.connect(device) ?: false
    } catch (_: Exception) {
        false
    }

    /** 注册成功后自动回连上次连接过的电脑（设置里可关） */
    private fun autoReconnect() {
        if (!prefs.getBoolean("auto_reconnect", true)) return
        val mac = prefs.getString("last_host_mac", null) ?: return
        val a = adapter ?: return
        val dev = try {
            a.getRemoteDevice(mac)
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                hidDevice?.connect(dev)
            }
        } catch (_: SecurityException) {
        }
    }

    /**
     * 兼容注册：Android 13+ 直接调带 Executor 的版本；
     * Android 9~12 上该重载不存在（NoSuchMethodError），反射调老的 4 参版本。
     * （老重载在 SDK 34 的 stubs 里已被移除，无法直接引用，但运行时仍在。）
     */
    private fun registerAppCompat(sdp: BluetoothHidDeviceAppSdpSettings, cb: BluetoothHidDevice.Callback) {
        val d = hidDevice ?: return
        try {
            d.registerApp(sdp, null, null, Executor { it.run() }, cb)
        } catch (_: NoSuchMethodError) {
            val m = BluetoothHidDevice::class.java.getMethod(
                "registerApp",
                BluetoothHidDeviceAppSdpSettings::class.java,
                BluetoothHidDeviceAppQosSettings::class.java,
                BluetoothHidDeviceAppQosSettings::class.java,
                BluetoothHidDevice.Callback::class.java,
            )
            m.invoke(d, sdp, null, null, cb)
        }
    }

    // ---------- 键盘报告 ----------

    private val lock = Any()
    private val report = ByteArray(8)
    private var heldMods = 0      // 物理按住的修饰键（Win 键等）
    private var oneShotMods = 0   // 点按锁存的修饰键（一次性）
    private val keys = IntArray(6)

    fun modDown(bit: Int) = synchronized(lock) {
        heldMods = heldMods or bit
        sync()
    }

    fun modUp(bit: Int) = synchronized(lock) {
        heldMods = heldMods and bit.inv()
        // 一次性锁存里若还挂着同一位（组合跨键保持后被抬起），一并清掉防止残留按住
        oneShotMods = oneShotMods and bit.inv()
        sync()
    }

    /** 瞬时发出一组修饰键并自动抬起（70ms）：
     *  单发 Shift = 中文输入法切换中英文；Ctrl+Shift / Alt+Shift = 切换输入法 */
    fun tapMods(bits: Int) {
        if (bits == 0) return
        synchronized(lock) {
            if (host == null) return
            heldMods = heldMods or bits
            sync()
        }
        main.postDelayed({
            synchronized(lock) {
                heldMods = heldMods and bits.inv()
                sync()
            }
        }, 70)
    }

    fun keyDown(code: Int, latchedMods: Int) = synchronized(lock) {
        if (code <= 0) return
        oneShotMods = latchedMods
        if (keys.indexOf(code) < 0) {
            val slot = keys.indexOf(0)
            if (slot >= 0) keys[slot] = code
        }
        sync()
    }

    /** keepOneShot：保持按下的修饰键位（修饰键手指仍按住、组合跨多次按键时由上层传入） */
    fun keyUp(code: Int, keepOneShot: Int = 0) = synchronized(lock) {
        if (code <= 0) return
        val i = keys.indexOf(code)
        if (i >= 0) keys[i] = 0
        oneShotMods = keepOneShot
        sync()
    }

    fun releaseAll() = synchronized(lock) {
        heldMods = 0
        oneShotMods = 0
        keys.fill(0)
        sync()
    }

    private fun sync() {
        val h = host ?: return
        val d = hidDevice ?: return
        report[0] = (heldMods or oneShotMods).toByte()
        report[1] = 0
        for (i in 0 until 6) report[2 + i] = keys[i].toByte()
        try {
            d.sendReport(h, REPORT_ID_KEYBOARD, report)
        } catch (e: Exception) {
            Log.w(TAG, "sendReport 失败", e)
        }
    }

    // ---------- 鼠标报告 ----------

    private val mouseReport = ByteArray(4)

    /** buttons: bit0 左键、bit1 右键；dx/dy/wheel: 相对位移，-127..127 */
    fun sendMouse(dx: Int, dy: Int, wheel: Int, buttons: Int) {
        synchronized(lock) {
            val h = host ?: return
            val d = hidDevice ?: return
            mouseReport[0] = buttons.toByte()
            mouseReport[1] = dx.coerceIn(-127, 127).toByte()
            mouseReport[2] = dy.coerceIn(-127, 127).toByte()
            mouseReport[3] = wheel.coerceIn(-127, 127).toByte()
            try {
                d.sendReport(h, REPORT_ID_MOUSE, mouseReport)
            } catch (e: Exception) {
                Log.w(TAG, "sendMouse 失败", e)
            }
        }
    }

    private fun safeName(d: BluetoothDevice): String? = try {
        d.name
    } catch (_: SecurityException) {
        null
    }

    companion object {
        private const val TAG = "HidKeyboard"
        private const val REPORT_ID_KEYBOARD = 1
        private const val REPORT_ID_MOUSE = 2
    }
}
