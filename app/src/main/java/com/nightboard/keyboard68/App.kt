package com.nightboard.keyboard68

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import java.util.concurrent.CopyOnWriteArrayList

class App : Application() {

    /** 关心 HID/局域网 状态的界面/服务实现它，状态变化时统一广播 */
    interface HidUi {
        fun onHidState()
    }

    lateinit var hid: HidKeyboard
        private set
    lateinit var lan: LanKeyboard
        private set
    lateinit var hub: InputHub
        private set

    private val uis = CopyOnWriteArrayList<HidUi>()

    val btAdapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var btRunning = false
    private var lanRunning = false

    override fun onCreate() {
        super.onCreate()
        hid = HidKeyboard(this, object : HidKeyboard.Listener {
            override fun onAppRegistered(registered: Boolean) = notifyUi()
            override fun onHostChanged(name: String?) = notifyUi()
            override fun onLedsChanged() = notifyUi()
        })
        lan = LanKeyboard(this, object : LanKeyboard.Listener {
            override fun onLanState() = notifyUi()
        })
        hub = InputHub(this, hid, lan)
    }

    fun addUi(u: HidUi) {
        uis.add(u)
    }

    fun removeUi(u: HidUi) {
        uis.remove(u)
    }

    private fun notifyUi() {
        for (u in uis) u.onHidState()
    }

    /** 拿到蓝牙权限后调用，注册 HID 键盘（蓝牙模式） */
    fun ensureHid() {
        if (!btRunning) {
            btRunning = true
            hid.start()
        }
    }

    /**
     * 按当前连接模式启停通道（设置 conn_mode 后调用）：
     *  - 蓝牙模式：局域网断开，注册蓝牙 HID
     *  - 局域网模式：注销蓝牙 HID（电脑蓝牙列表里消失），启动局域网发现/连接
     * 幂等，可反复调用。
     */
    fun applyConnMode() {
        if (hub.mode == InputHub.MODE_LAN) {
            if (btRunning) {
                btRunning = false
                hid.stop()
            }
            if (!lanRunning) {
                lanRunning = true
                lan.start()
            }
        } else {
            if (lanRunning) {
                lanRunning = false
                lan.stop()
            }
            ensureHid()
        }
    }
}
