package com.nightboard.keyboard68

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 主界面：显示 HID 键盘注册状态 / 电脑连接状态，
 * 提供「让电脑发现我」和首次配对引导。
 *
 * 注意：NightBoard68 不是独立的蓝牙设备，而是手机上的键盘服务。
 * 电脑扫描列表里出现的是手机自己的蓝牙名字，配对时电脑读到
 * HID 键盘服务后，就会把它装成一台键盘。
 */
class MainActivity : Activity(), App.HidUi {

    private lateinit var status: TextView
    private lateinit var modeHint: TextView
    private lateinit var btModeBtn: Button
    private lateinit var lanModeBtn: Button
    private lateinit var deviceBox: LinearLayout
    private lateinit var enableBt: Button
    private lateinit var discoverBt: Button
    private lateinit var batteryBtn: Button

    private val app get() = application as App
    private val dp get() = resources.displayMetrics.density

    private val fg = Color.parseColor("#E6EAF0")
    private val dim = Color.parseColor("#8A93A3")
    private val accent = Color.parseColor("#E8944A")
    private val red = Color.parseColor("#E05B5B")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * dp).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (28 * dp).toInt(), pad, (28 * dp).toInt())
        }
        root.addView(box)
        setContentView(root)

        fun label(text: String, size: Float, color: Int = fg, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        /** 统一主按钮样式：圆角深色底 + 白字 + 可选 SVG 图标；primary = 主操作按钮 */
        fun mainBtn(
            text: String,
            onClick: () -> Unit,
            icon: Int = 0,
            primary: Boolean = false,
        ): Button = Button(this).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.parseColor("#0E1116") else fg)
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(if (primary) Color.parseColor("#E8944A") else Color.parseColor("#1C232D"))
            }
            if (icon != 0) {
                val d = resources.getDrawable(icon, null)
                d?.setTint(if (primary) Color.parseColor("#0E1116") else dim)
                // 右侧放一个同宽透明图标，让「图标+文本」整体真正居中（否则文本会被左侧图标挤偏）
                val blank = resources.getDrawable(R.drawable.ic_blank, null)
                setCompoundDrawablesWithIntrinsicBounds(d, null, blank, null)
                compoundDrawablePadding = (8 * dp).toInt()
            }
            setOnClickListener { onClick() }
        }

        box.addView(label("NightBoard68", 30f, fg, bold = true))
        box.addView(label("手机横过来，就是宿舍的 68 键蓝牙键盘", 14f, dim))
        box.addView(label("", 6f, dim))

        // ---------- 连接模式选择：蓝牙 / 局域网 两个独立模式，按场景自选 ----------
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeBtn(caption: String, m: String): Button = Button(this).apply {
            text = caption
            setOnClickListener {
                if (app.hub.mode != m) {
                    app.hub.releaseAll()
                    getSharedPreferences("nightboard", MODE_PRIVATE).edit()
                        .putString("conn_mode", m).apply()
                    app.applyConnMode()
                    refresh()
                }
            }
        }
        btModeBtn = modeBtn("蓝牙模式", InputHub.MODE_BT)
        lanModeBtn = modeBtn("局域网模式", InputHub.MODE_LAN)
        modeRow.addView(
            btModeBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = (4 * dp).toInt() }
        )
        modeRow.addView(
            lanModeBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = (4 * dp).toInt() }
        )
        box.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        modeHint = label("", 12f, dim)
        box.addView(modeHint)
        box.addView(label("", 6f, dim))

        status = label("", 16f)
        box.addView(status)

        // 常用操作放最上面：设备很多时也不会被挤到下面
        box.addView(mainBtn("开始打字", primary = true, icon = R.drawable.ic_keyboard, onClick = {
            startActivity(Intent(this@MainActivity, KeyboardActivity::class.java))
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (12 * dp).toInt() })

        box.addView(mainBtn("竖屏模式", icon = R.drawable.ic_smartphone, onClick = {
            startActivity(Intent(this@MainActivity, OneHandActivity::class.java))
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (10 * dp).toInt() })

        box.addView(mainBtn("检查连接", icon = R.drawable.ic_refresh, onClick = {
            app.hub.checkConnections()
            status.text = "正在检查：局域网重新搜索电脑，蓝牙尝试回连…"
            status.setTextColor(dim)
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (10 * dp).toInt() })

        enableBt = mainBtn("打开蓝牙", onClick = {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2)
        }).apply { visibility = ViewGroup.GONE }
        box.addView(enableBt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (10 * dp).toInt() })

        discoverBt = mainBtn("让电脑发现我（5 分钟）", onClick = { startDiscoverable() }).apply { visibility = ViewGroup.GONE }
        box.addView(discoverBt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (10 * dp).toInt() })

        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(deviceBox)

        // 电池优化豁免：部分 ROM 会杀掉前台服务所属进程，HID 注册随之重建，
        // 用户感知为蓝牙频繁断连。豁免后进程受保护（连接日志里表现为成对的注册/注销事件消失）。
        batteryBtn = mainBtn(
            "申请电池优化豁免（防后台被杀导致断连）",
            icon = R.drawable.ic_bolt,
            onClick = {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"))
                )
            },
        ).apply { visibility = ViewGroup.GONE }
        box.addView(batteryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = (10 * dp).toInt() })

        box.addView(mainBtn("设置（震动 / 亮度 / 局域网）", icon = R.drawable.ic_gear, onClick = {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (10 * dp).toInt() })

        box.addView(label("", 10f, dim))
        box.addView(label("首次使用（配对一次即可）：", 14f, fg, bold = true))
        box.addView(label(
            "1. 等主页显示「键盘就绪」\n" +
            "2. 点「让电脑发现我」\n" +
            "3. 电脑：设置 → 蓝牙和其他设备 → 添加设备 → 蓝牙\n" +
            "4. 列表里选【你手机的蓝牙名字】（键盘是手机上的服务，\n" +
            "    不会出现叫 NightBoard68 的独立设备）\n" +
            "5. 手机弹出配对框点「配对」，之后电脑就会把它装成键盘\n\n" +
            "重要：如果电脑以前配对过这台手机，先在电脑蓝牙设置里\n" +
            "删掉旧配对，再按上面步骤重新添加，否则键盘服务装不上。\n\n" +
            "打字技巧：Ctrl/Alt/Shift 点按即锁定；Fn+数字排 = F1~F12；\n" +
            "按住按键自动连发。", 13f, dim))
    }

    private fun startDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivityForResult(i, 3)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3 && resultCode > 0) {
            status.text = "手机已可被电脑发现 $resultCode 秒，去电脑上添加设备吧"
            status.setTextColor(accent)
        }
    }

    override fun onResume() {
        super.onResume()
        app.addUi(this)
        ensurePermissionThenHid()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        app.removeUi(this)
    }

    override fun onHidState() {
        runOnUiThread { refresh() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            app.applyConnMode()   // 修复：权限到位后重新触发当前模式
        }
        refresh()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun batteryOptimizationIgnored(): Boolean = try {
        (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) {
        true
    }

    private fun ensurePermissionThenHid() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            app.applyConnMode()
        }
    }

    private fun refresh() {
        // 电池优化豁免按钮：未豁免时显示
        batteryBtn.visibility = if (Build.VERSION.SDK_INT >= 23 && !batteryOptimizationIgnored()) {
            ViewGroup.VISIBLE
        } else {
            ViewGroup.GONE
        }

        // 模式按钮高亮 + 模式说明
        val activeBg = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(accent) }
        val normalBg = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(Color.parseColor("#1C232D")) }
        val lanMode = app.hub.mode == InputHub.MODE_LAN
        btModeBtn.background = if (lanMode) normalBg else activeBg
        btModeBtn.setTextColor(if (lanMode) fg else Color.parseColor("#0E1116"))
        lanModeBtn.background = if (lanMode) activeBg else normalBg
        lanModeBtn.setTextColor(if (lanMode) Color.parseColor("#0E1116") else fg)
        modeHint.text = if (lanMode) {
            "局域网模式：电脑双击运行 NightBoardAgent.exe，手机连同一 WiFi，延迟更低"
        } else {
            "蓝牙模式：电脑零安装，首次按下方引导配对一次即可"
        }

        when {
            // ---------- 局域网模式：只看 LAN 状态 ----------
            lanMode -> {
                enableBt.visibility = ViewGroup.GONE
                discoverBt.visibility = ViewGroup.GONE
                deviceBox.removeAllViews()
                when {
                    app.hub.lanConnected -> {
                        val rtt = app.lan.rttMs
                        val name = app.lan.agentName ?: "电脑"
                        status.text = "● 局域网已连接 $name${if (rtt >= 0) " · ${rtt}ms" else ""} — 点「开始打字」"
                        status.setTextColor(accent)
                    }
                    app.lan.state == LanKeyboard.State.CONNECTING -> {
                        status.text = "◌ 正在连接电脑…（确认电脑已运行 NightBoardAgent）"
                        status.setTextColor(dim)
                    }
                    else -> {
                        status.text = "○ 正在搜索电脑…（需同一 WiFi；搜不到就在设置里手动填 IP）"
                        status.setTextColor(accent)
                    }
                }
            }
            // ---------- 蓝牙模式 ----------
            app.btAdapter == null -> {
                status.text = "× 本机没有蓝牙（想用局域网模式可切换）"
                status.setTextColor(red)
                enableBt.visibility = ViewGroup.GONE
                discoverBt.visibility = ViewGroup.GONE
                deviceBox.removeAllViews()
            }
            app.btAdapter?.isEnabled != true -> {
                status.text = "蓝牙未开启"
                status.setTextColor(red)
                enableBt.visibility = ViewGroup.VISIBLE
                discoverBt.visibility = ViewGroup.GONE
                deviceBox.removeAllViews()
            }
            !hasConnectPermission() -> {
                status.text = "需要「附近的设备」权限才能注册键盘"
                status.setTextColor(red)
                enableBt.visibility = ViewGroup.GONE
                discoverBt.visibility = ViewGroup.GONE
                deviceBox.removeAllViews()
            }
            !app.hid.isRegistered() -> {
                status.text = "正在注册蓝牙键盘…（长时间不动就退出重开 App）"
                status.setTextColor(dim)
                enableBt.visibility = ViewGroup.GONE
                discoverBt.visibility = ViewGroup.GONE
                deviceBox.removeAllViews()
            }
            else -> {
                val host = app.hid.hostName()
                if (host != null) {
                    status.text = "● 蓝牙已连接：$host — 点「开始打字」"
                    status.setTextColor(accent)
                    discoverBt.visibility = ViewGroup.GONE
                } else {
                    status.text = "○ 键盘就绪：点「让电脑发现我」，再去电脑添加设备"
                    status.setTextColor(accent)
                    discoverBt.visibility = ViewGroup.VISIBLE
                }
                refreshDevices()
            }
        }
    }

    /** 已配对电脑列表：默认收起（设备多时不挤占页面） */
    private var devicesExpanded = false

    /** 按蓝牙设备大类选真实设备图标（电脑/手机/耳机/键盘/鼠标/其他=蓝牙） */
    private fun deviceIcon(d: android.bluetooth.BluetoothDevice): Int {
        val cls = try { d.bluetoothClass } catch (_: SecurityException) { null } ?: return R.drawable.ic_bluetooth
        return when (cls.majorDeviceClass) {
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> R.drawable.ic_computer
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> R.drawable.ic_smartphone
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_headset
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> when (cls.deviceClass) {
                android.bluetooth.BluetoothClass.Device.PERIPHERAL_KEYBOARD -> R.drawable.ic_keyboard
                android.bluetooth.BluetoothClass.Device.PERIPHERAL_POINTING -> R.drawable.ic_mouse
                else -> R.drawable.ic_bluetooth
            }
            else -> R.drawable.ic_bluetooth
        }
    }

    private fun refreshDevices() {
        deviceBox.removeAllViews()
        val hosts = app.hid.bondedHosts()
        if (hosts.isEmpty()) return
        // 折叠头部：点一下展开 / 收起
        deviceBox.addView(Button(this).apply {
            text = if (devicesExpanded) "▾ 收起已配对的电脑（${hosts.size}）" else "▸ 已配对的电脑（${hosts.size}）"
            background = GradientDrawable().apply { cornerRadius = 8 * dp; setColor(Color.parseColor("#1C232D")) }
            setTextColor(dim)
            setOnClickListener {
                devicesExpanded = !devicesExpanded
                refreshDevices()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = (10 * dp).toInt()
        })
        if (!devicesExpanded) return
        // 当前连接的主机（名字或地址），用于高亮
        val current = app.hid.hostName()
        for (d in hosts) {
            val name = try { d.name } catch (_: SecurityException) { null } ?: d.address
            val connected = name == current || d.address == current
            deviceBox.addView(Button(this).apply {
                text = if (connected) "$name（当前连接）" else name
                isAllCaps = false
                textSize = 14f
                minHeight = 0
                minimumHeight = 0
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(if (connected) accent else Color.parseColor("#1C232D"))
                }
                setTextColor(if (connected) Color.parseColor("#0E1116") else fg)
                val icon = resources.getDrawable(deviceIcon(d), null)
                icon?.setTint(if (connected) Color.parseColor("#0E1116") else dim)
                setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
                compoundDrawablePadding = (8 * dp).toInt()
                setOnClickListener {
                    // 切换目标：已连接其他电脑时先断开再连（此前直接 connect 会被系统忽略）
                    app.hid.switchHost(d)
                    status.text = "正在连接 $name…"
                    status.setTextColor(dim)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (8 * dp).toInt()
            })
        }
    }
}
