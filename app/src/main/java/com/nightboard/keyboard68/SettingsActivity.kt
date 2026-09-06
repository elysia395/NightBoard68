package com.nightboard.keyboard68

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 设置页：触感强度 / 键盘亮度 / 修饰键模式 / 自动回连。
 * 所有修改写入 SharedPreferences（"nightboard"），立即生效。
 */
class SettingsActivity : Activity() {

    private val dp get() = resources.displayMetrics.density
    private val prefs get() = getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var ipEdit: android.widget.EditText
    private var ipSavePending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nightboard", Context.MODE_PRIVATE)

        val pad = (16 * dp).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (24 * dp).toInt(), pad, (24 * dp).toInt())
        }
        root.addView(box)
        setContentView(root)

        fun title(t: String): TextView = TextView(this).apply {
            text = t; textSize = 17f; setTextColor(Color.parseColor("#E6EAF0"))
            typeface = Typeface.DEFAULT_BOLD
        }
        fun hint(t: String): TextView = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(Color.parseColor("#8A93A3"))
        }
        fun gap(h: Int) = box.addView(TextView(this), LinearLayout.LayoutParams(0, h))

        // ---------- 触感反馈 ----------
        box.addView(title("触感反馈"))
        val hapticLabel = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#8A93A3"))
        }
        fun hapticWord(p: Int) = when {
            p == 0 -> "关"
            p < 86 -> "轻"
            p < 171 -> "中"
            else -> "强"
        }
        val hapticBar = SeekBar(this).apply { max = 255; progress = prefs.getInt("haptic_amp", 140) }
        fun updateHapticLabel() {
            hapticLabel.text = "强度：${hapticWord(hapticBar.progress)}（${hapticBar.progress}）"
        }
        hapticBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateHapticLabel()
                if (fromUser && p > 0) vibrate(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("haptic_amp", sb.progress).apply()
            }
        })
        updateHapticLabel()
        box.addView(hapticLabel)
        box.addView(hapticBar)
        gap((10 * dp).toInt())

        // ---------- 键盘亮度 ----------
        box.addView(title("键盘页面亮度"))
        val brightLabel = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#8A93A3"))
        }
        fun updateBrightLabel(p: Int) {
            val pct = p + 5
            brightLabel.text = if (pct >= 100) "亮度：100%（跟随系统）" else "亮度：$pct%"
        }
        val brightBar = SeekBar(this).apply {
            max = 95
            progress = prefs.getInt("kb_brightness", 100) - 5
        }
        brightBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateBrightLabel(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("kb_brightness", sb.progress + 5).apply()
            }
        })
        updateBrightLabel(brightBar.progress)
        box.addView(brightLabel)
        box.addView(brightBar)
        box.addView(hint("调低后夜间打字不刺眼，下次进入键盘页面生效；100% 跟随系统。"))
        gap((10 * dp).toInt())

        // ---------- 行为 ----------
        box.addView(title("行为"))
        box.addView(CheckBox(this).apply {
            text = "修饰键按住生效（默认：点按锁定）"
            textSize = 14f
            setTextColor(Color.parseColor("#E6EAF0"))
            isChecked = prefs.getBoolean("mod_hold", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("mod_hold", checked).apply()
            }
        })
        box.addView(CheckBox(this).apply {
            text = "打开 App 时自动回连上次连接的电脑"
            textSize = 14f
            setTextColor(Color.parseColor("#E6EAF0"))
            isChecked = prefs.getBoolean("auto_reconnect", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_reconnect", checked).apply()
            }
        })
        gap((10 * dp).toInt())

        // ---------- 触摸板 ----------
        box.addView(title("触摸板滚动灵敏度"))
        val scrollLabel = TextView(this).apply {
            textSize = 13f; setTextColor(Color.parseColor("#8A93A3"))
        }
        fun scrollWord(px: Int) = when {
            px <= 14 -> "高"
            px <= 32 -> "中"
            else -> "低"
        }
        fun updateScrollLabel(px: Int) {
            scrollLabel.text = "灵敏度：${scrollWord(px)}（滚动一格 = 手指滑 ${px}px）"
        }
        val scrollBar = SeekBar(this).apply {
            max = 52
            progress = 60 - prefs.getInt("scroll_notch_px", 24).coerceIn(8, 60)
        }
        scrollBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateScrollLabel(60 - p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("scroll_notch_px", (60 - sb.progress).coerceIn(8, 60)).apply()
            }
        })
        updateScrollLabel(60 - scrollBar.progress)
        box.addView(scrollLabel)
        box.addView(scrollBar)
        box.addView(hint("双指上下滑或触摸板右缘条上下滑 = 滚动；觉得滚得慢往左调，太快往右调。"))
        gap((10 * dp).toInt())

        // ---------- 网络与链路 ----------
        box.addView(title("网络与链路"))
        box.addView(hint("连接模式（蓝牙 / 局域网）在主页或键盘页右上角切换，两个模式互相独立、按需选用。"))
        box.addView(TextView(this).apply {
            text = "局域网模式 · 电脑 IP（留空 = 自动搜索）："
            textSize = 13f
            setTextColor(Color.parseColor("#8A93A3"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (8 * dp).toInt() })
        // 局域网电脑 IP：输入停顿 500ms 自动保存；离开页面时再兜底保存一次。
        // （不能依赖焦点变化——本页其他控件都不可聚焦，焦点永远不会"移走"）
        ipEdit = android.widget.EditText(this).apply {
            hint = "例如 192.168.1.8（可带端口 192.168.1.8:6868）"
            setText(prefs.getString("lan_host", ""))
            textSize = 14f
            setSingleLine(true)
        }
        ipEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                scheduleIpSave()
            }
        })
        box.addView(ipEdit)
        box.addView(hint("输完稍停半秒即自动保存并重连；Agent 首次运行如弹出防火墙提示请点允许。"))
        gap((10 * dp).toInt())

        box.addView(hint("所有修改立即保存，无需点确定。"))
    }

    /** 输入停顿后自动保存；变了就顺手触发一次局域网重连 */
    private fun scheduleIpSave() {
        ipSavePending?.let { mainHandler.removeCallbacks(it) }
        val run = Runnable {
            ipSavePending = null
            saveIpNow(reconnect = true)
        }
        ipSavePending = run
        mainHandler.postDelayed(run, 500)
    }

    private fun saveIpNow(reconnect: Boolean) {
        if (!::ipEdit.isInitialized) return
        prefs.edit().putString("lan_host", ipEdit.text.toString().trim()).apply()
        if (reconnect && (application as App).hub.mode == InputHub.MODE_LAN) {
            (application as App).lan.reconnectNow()
        }
    }

    override fun onPause() {
        super.onPause()
        // 兜底：还挂着防抖任务（输完马上退出）就立即落盘并重连
        if (ipSavePending != null) {
            ipSavePending?.let { mainHandler.removeCallbacks(it) }
            ipSavePending = null
            saveIpNow(reconnect = true)
        }
    }

    /** 拖动条上实时试震，选完即所见即所得 */
    private fun vibrate(amplitude: Int) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(18, amplitude.coerceIn(1, 255)))
        } catch (_: Exception) {
        }
    }
}
