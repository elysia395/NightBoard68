package com.nightboard.keyboard68

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 设置页：触感强度 / 键盘亮度 / 修饰键模式 / 自动回连。
 * 所有修改写入 SharedPreferences（"nightboard"），立即生效。
 * 布局：卡片式分组，每类设置一块圆角深色卡片。
 */
class SettingsActivity : Activity() {

    private val dp get() = resources.displayMetrics.density
    private val prefs get() = getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var ipEdit: android.widget.EditText
    private var ipSavePending: Runnable? = null

    private val fg = Color.parseColor("#E6EAF0")
    private val dim = Color.parseColor("#8A93A3")
    private val accent = Color.parseColor("#E8944A")
    private val cardBg = Color.parseColor("#161D27")
    private val barBg = Color.parseColor("#1C232D")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nightboard", Context.MODE_PRIVATE)

        val pad = (16 * dp).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (16 * dp).toInt(), pad, (24 * dp).toInt())
        }
        root.addView(box)
        setContentView(root)

        fun title(t: String): TextView = TextView(this).apply {
            text = t; textSize = 17f; setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
        }
        fun hint(t: String): TextView = TextView(this).apply {
            text = t; textSize = 12f; setTextColor(dim)
            setLineSpacing(0f, 1.2f)
        }
        fun gap(h: Int) = box.addView(TextView(this), LinearLayout.LayoutParams(0, h))

        /** 一块分组卡片：标题 + 内容（圆角深色底，间距统一） */
        fun card(cardTitle: String, inner: LinearLayout.() -> Unit) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(cardBg)
                }
            }
            c.addView(TextView(this).apply {
                text = cardTitle
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
                setPadding(0, 0, 0, (10 * dp).toInt())
            })
            c.inner()
            box.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (10 * dp).toInt() })
        }

        // 返回
        box.addView(TextView(this).apply {
            text = "‹ 返回"
            textSize = 15f
            setTextColor(accent)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * dp).toInt())
            setOnClickListener { finish() }
        })
        box.addView(title("设置"))
        gap((6 * dp).toInt())

        // ---------- 触感反馈 ----------
        card("触感反馈") {
            val hapticLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f; setTextColor(dim)
            }
            fun hapticWord(p: Int) = when {
                p == 0 -> "关"
                p < 86 -> "轻"
                p < 171 -> "中"
                else -> "强"
            }
            val hapticBar = SeekBar(this@SettingsActivity).apply {
                max = 255; progress = prefs.getInt("haptic_amp", 140)
            }
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
            addView(hapticLabel)
            addView(hapticBar)
        }

        // ---------- 键盘亮度 ----------
        card("键盘页面亮度") {
            val brightLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f; setTextColor(dim)
            }
            fun updateBrightLabel(p: Int) {
                val pct = p + 5
                brightLabel.text = if (pct >= 100) "亮度：100%（跟随系统）" else "亮度：$pct%"
            }
            val brightBar = SeekBar(this@SettingsActivity).apply {
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
            addView(brightLabel)
            addView(brightBar)
            addView(hint("调低后夜间打字不刺眼，下次进入键盘页面生效；100% 跟随系统。"))
        }

        // ---------- 行为 ----------
        card("行为") {
            addView(CheckBox(this@SettingsActivity).apply {
                text = "修饰键按住生效（默认：点按锁定）"
                textSize = 14f
                setTextColor(fg)
                isChecked = prefs.getBoolean("mod_hold", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("mod_hold", checked).apply()
                }
            })
            addView(CheckBox(this@SettingsActivity).apply {
                text = "打开 App 时自动回连上次连接的电脑"
                textSize = 14f
                setTextColor(fg)
                isChecked = prefs.getBoolean("auto_reconnect", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("auto_reconnect", checked).apply()
                }
            })
            addView(CheckBox(this@SettingsActivity).apply {
                text = "屏幕常亮（键盘页面不自动熄屏）"
                textSize = 14f
                setTextColor(fg)
                isChecked = prefs.getBoolean("keep_screen_on", true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("keep_screen_on", checked).apply()
                }
            })
        }

        // ---------- 状态条按钮（输入法热键，不同输入法不一致） ----------
        card("状态条按钮热键") {
            addView(hint("「中英」「输入法」按钮发出的组合键可按你输入法实际的热键调整，"
                + "默认值对不上的话切换会不生效。"))
            fun comboSpinner(labelText: String, prefKey: String, options: List<Pair<String, String>>) {
                addView(TextView(this@SettingsActivity).apply {
                    text = labelText
                    textSize = 13f
                    setTextColor(dim)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (6 * dp).toInt() })
                addView(android.widget.Spinner(this@SettingsActivity).apply {
                    adapter = android.widget.ArrayAdapter(
                        this@SettingsActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        options.map { it.first },
                    )
                    val cur = prefs.getString(prefKey, options[0].second)
                    setSelection(options.indexOfFirst { it.second == cur }.coerceAtLeast(0))
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                            prefs.edit().putString(prefKey, options[pos].second).apply()
                        }

                        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                    }
                })
            }
            comboSpinner(
                "「中英」按钮发送：",
                "btn_shift_combo",
                listOf(
                    "Shift（Gboard 等默认）" to "shift",
                    "Ctrl+Space" to "ctrl_space",
                ),
            )
            comboSpinner(
                "「输入法」按钮发送：",
                "btn_ime_combo",
                listOf(
                    "Ctrl+Shift（默认）" to "ctrl_shift",
                    "Alt+Shift" to "alt_shift",
                    "Win+Space" to "win_space",
                ),
            )
        }

        // ---------- 键盘布局（Windows/Linux ↔ Mac） ----------
        card("键盘布局") {
            addView(hint("Mac 模式交换 Win 键与 Alt 键的键值（Win→Option ⌥、Alt→Command ⌘），"
                + "使物理手位与 Mac 键盘一致，键帽与快捷键标签同步显示 ⌃⌥⌘；"
                + "改完退出重进键盘页面生效。"))
            addView(TextView(this@SettingsActivity).apply {
                text = "键位风格："
                textSize = 13f
                setTextColor(dim)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt() })
            addView(android.widget.Spinner(this@SettingsActivity).apply {
                adapter = android.widget.ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("Windows/Linux", "Mac"),
                )
                setSelection(if (prefs.getString("keyboard_layout", "win") == "mac") 1 else 0)
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                        prefs.edit().putString("keyboard_layout", if (pos == 1) "mac" else "win").apply()
                    }

                    override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                }
            })
        }

        // ---------- 触摸板 ----------
        card("触摸板滚动灵敏度") {
            val scrollLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f; setTextColor(dim)
            }
            fun scrollWord(px: Int) = when {
                px <= 14 -> "高"
                px <= 32 -> "中"
                else -> "低"
            }
            fun updateScrollLabel(px: Int) {
                scrollLabel.text = "灵敏度：${scrollWord(px)}（滚动一格 = 手指滑 ${px}px）"
            }
            val scrollBar = SeekBar(this@SettingsActivity).apply {
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
            addView(scrollLabel)
            addView(scrollBar)
            addView(hint("双指上下滑或触摸板右缘条上下滑 = 滚动；觉得滚得慢往左调，太快往右调。"))
        }

        // ---------- 屏幕边距（避开摄像头/挖孔/圆角） ----------
        card("屏幕边距") {
            addView(hint("部分手机的前置摄像头/挖孔会遮挡按钮，可分别调整竖屏顶部和横屏左右留白（dp）。"
                + "改完退出重进键盘页面生效。"))
            fun marginSlider(labelText: String, prefKey: String, maxVal: Int) {
                addView(TextView(this@SettingsActivity).apply {
                    text = labelText
                    textSize = 13f
                    setTextColor(dim)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (6 * dp).toInt() })
                val marginLabel = TextView(this@SettingsActivity).apply {
                    textSize = 13f
                    setTextColor(dim)
                }
                val bar = SeekBar(this@SettingsActivity).apply { max = maxVal; progress = prefs.getInt(prefKey, 0) }
                fun updateLabel(p: Int) {
                    marginLabel.text = if (p == 0) "当前：0（不偏移）" else "当前：$p dp"
                }
                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        updateLabel(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        prefs.edit().putInt(prefKey, sb.progress).apply()
                    }
                })
                updateLabel(bar.progress)
                addView(marginLabel)
                addView(bar)
            }
            marginSlider("竖屏 · 顶部边距（避免摄像头挡触摸板/按钮）：", "onehand_top_margin", 48)
            marginSlider("横屏 · 左右边距（避免边缘摄像头/圆角挡按键）：", "landscape_side_margin", 48)
        }

        // ---------- 外观 ----------
        card("外观") {
            addView(hint("键盘文字大小、嵌入模式触控板与鼠标键列宽度均可调整；嵌中/嵌左/嵌右共享同一组宽度。"
                + "改完退出重进键盘页面生效。"))
            // 键盘文字大小（0.7~1.6 倍）
            addView(TextView(this@SettingsActivity).apply {
                text = "键盘文字大小："
                textSize = 13f
                setTextColor(dim)
            })
            val textLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f
                setTextColor(dim)
            }
            val textBar = SeekBar(this@SettingsActivity).apply {
                max = 90
                progress = ((prefs.getFloat("key_text_scale", 1f).coerceIn(0.7f, 1.6f) - 0.7f) * 100f).toInt()
            }
            fun updateTextLabel(p: Int) {
                textLabel.text = "当前：${((p / 100f) + 0.7f)} 倍"
            }
            textBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    updateTextLabel(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putFloat("key_text_scale", (sb.progress / 100f + 0.7f).coerceIn(0.7f, 1.6f)).apply()
                }
            })
            updateTextLabel(textBar.progress)
            addView(textLabel)
            addView(textBar)
            // 嵌入触控板宽度（占键盘区百分比）
            addView(TextView(this@SettingsActivity).apply {
                text = "嵌入模式 · 触控板宽度："
                textSize = 13f
                setTextColor(dim)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt() })
            val padWLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f
                setTextColor(dim)
            }
            val padWBar = SeekBar(this@SettingsActivity).apply {
                max = 40
                progress = prefs.getInt("embed_pad_width", 35).coerceIn(20, 60) - 20
            }
            fun updatePadWLabel(p: Int) {
                padWLabel.text = "当前：${p + 20}%（越大键盘越窄）"
            }
            padWBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    updatePadWLabel(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putInt("embed_pad_width", (sb.progress + 20).coerceIn(20, 60)).apply()
                }
            })
            updatePadWLabel(padWBar.progress)
            addView(padWLabel)
            addView(padWBar)
            // 嵌入鼠标键列宽度（dp）
            addView(TextView(this@SettingsActivity).apply {
                text = "嵌入模式 · 鼠标键列宽度："
                textSize = 13f
                setTextColor(dim)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt() })
            val colWLabel = TextView(this@SettingsActivity).apply {
                textSize = 13f
                setTextColor(dim)
            }
            val colWBar = SeekBar(this@SettingsActivity).apply {
                max = 36
                progress = prefs.getInt("embed_mouse_col", 52).coerceIn(36, 72) - 36
            }
            fun updateColWLabel(p: Int) {
                colWLabel.text = "当前：${p + 36} dp"
            }
            colWBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    updateColWLabel(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putInt("embed_mouse_col", (sb.progress + 36).coerceIn(36, 72)).apply()
                }
            })
            updateColWLabel(colWBar.progress)
            addView(colWLabel)
            addView(colWBar)
        }

        // ---------- 网络与链路 ----------
        card("网络与链路") {
            addView(hint("连接模式（蓝牙 / 局域网）在主页或键盘页右上角切换，两个模式互相独立、按需选用。"))
            addView(TextView(this@SettingsActivity).apply {
                text = "局域网模式 · 电脑 IP（留空 = 自动搜索）："
                textSize = 13f
                setTextColor(dim)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = (8 * dp).toInt() })
            // 局域网电脑 IP：输入停顿 500ms 自动保存；离开页面时再兜底保存一次。
            // （不能依赖焦点变化——本页其他控件都不可聚焦，焦点永远不会"移走"）
            ipEdit = android.widget.EditText(this@SettingsActivity).apply {
                hint = "例如 192.168.1.8（可带端口 192.168.1.8:6868）"
                setText(prefs.getString("lan_host", ""))
                textSize = 14f
                setSingleLine(true)
                setTextColor(fg)
                setHintTextColor(dim)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(barBg)
                }
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            }
            ipEdit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    scheduleIpSave()
                }
            })
            addView(ipEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() })
            addView(hint("输完稍停半秒即自动保存并重连；Agent 首次运行如弹出防火墙提示请点允许。"))
        }

        // ---------- 连接日志（诊断蓝牙断连） ----------
        card("连接日志") {
            addView(hint("最近 30 条蓝牙连接事件。若经常断连，看这里的规律：断开发生在闲置时还是打字时、"
                + "间隔多久、自动回连是否成功。设置 → 应用 → NightBoard68 → 电池，选择「无限制」可排除省电干预。"))
            addView(TextView(this@SettingsActivity).apply {
                textSize = 11f
                setTextColor(dim)
                typeface = Typeface.MONOSPACE
                text = (application as App).hid.connLogLines().joinToString("\n").ifEmpty { "（暂无事件）" }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() })
        }

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
