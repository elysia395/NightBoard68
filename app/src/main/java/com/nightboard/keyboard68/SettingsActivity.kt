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

        box.addView(hint("所有修改立即保存，无需点确定。"))
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
