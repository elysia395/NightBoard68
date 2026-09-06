package com.nightboard.keyboard68

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import java.util.concurrent.CopyOnWriteArrayList

class App : Application() {

    /** 关心 HID 状态的界面/服务实现它，状态变化时统一广播 */
    interface HidUi {
        fun onHidState()
    }

    lateinit var hid: HidKeyboard
        private set

    private val uis = CopyOnWriteArrayList<HidUi>()

    val btAdapter: BluetoothAdapter?
        get() = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var hidStarted = false

    override fun onCreate() {
        super.onCreate()
        hid = HidKeyboard(this, object : HidKeyboard.Listener {
            override fun onAppRegistered(registered: Boolean) = notifyUi()
            override fun onHostChanged(name: String?) = notifyUi()
            override fun onLedsChanged() = notifyUi()
        })
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

    /** 拿到蓝牙权限后调用，注册 HID 键盘 */
    fun ensureHid() {
        if (!hidStarted) {
            hidStarted = true
            hid.start()
        }
    }
}
