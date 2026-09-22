package com.nightboard.keyboard68

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自绘 68 键键盘 + 触控板。
 *
 * 交互模型（为触屏设计，不是照搬实体键盘）：
 *  - 默认：Ctrl/Alt/Shift 点按即锁定，打一个键后自动释放；再点一次取消
 *    · 已锁定的修饰键再点一次 = 单独发送该键（Shift 单点 → 切中英文）
 *    · 已锁定 A 时再点 B     = 延迟组成组合键（Ctrl+Shift → 切输入法），
 *      期间打字则取消组合、两个修饰键跟随字母一起发送
 *  - 设置里可切换为「按住生效」（和实体键盘一致，多指按住即可）
 *  - Win：始终按住生效（避免误触弹出开始菜单）
 *  - Fn：点按锁定，打一个键后自动释放
 *  - 普通键：按住不放 = 电脑端自动连发
 *  - 顶部状态条：中英 / 输入法 / 触控板开关 / 触控板样式 / 布局 / 竖屏 / 检查 / 模式 / 退出
 *  - 触控板开关 + 样式循环：
 *    · 全屏：整屏触控板（左侧带鼠标左中右三键）
 *    · 悬浮：键盘上悬浮一块可拖动位置的小触控板
 *    · 嵌入：键盘缩窄，两侧各一条鼠标左中右键列，小触控板嵌入键盘空间（左/中/右）
 *  - 触控板手势：单指移动、轻点=左键、双指=右键、长按拖动、双指滑动=滚动、捏合=缩放
 *  - 布局编辑：点顶部「布局」，左右拖动任意键帽改变键宽；同一行自动重新分配空间
 */
class KeyboardView(context: Context, private val hub: InputHub) : View(context) {

    private class KeyRect(
        val key: Key,
        val prefKey: String,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) {
        val rect = RectF(l, t, r, b)
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    private class StripBtn(val rect: RectF, val label: String, val id: Int) {
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    /** 鼠标键列上的一个键（左键/中键/右键） */
    private class MouseKey(val rect: RectF, val button: Int, val label: String) {
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    /** 数字小键盘的一个格：label 显示文字；altLabel 右上角标注（NumLock 关闭时的操作）；
     *  code+mods 发送的 HID 键码与修饰键；span 跨列数；rowSpan 跨行数 */
    private class NpCell(
        val label: String,
        val code: Int,
        val mods: Int = 0,
        val span: Int = 1,
        val rowSpan: Int = 1,
        val altLabel: String = "",
    )

    private class NpKeyRect(val rect: RectF, val cell: NpCell) {
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    /** 自定义组合键：修饰键位 + 一个或多个主键码（可多键同时按下） */
    private class ComboDef(val mods: Int, val codes: List<Int>)

    private val prefs = context.getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val hapticAmp = prefs.getInt("haptic_amp", 140)
    private val modHold = prefs.getBoolean("mod_hold", false)

    // ---------- Mac 键位（设置页"键盘布局"切换，win = Windows/Linux） ----------
    private val isMacLayout get() = prefs.getString("keyboard_layout", "win") == "mac"

    /** Mac 模式：左 Alt 发 Command(⌘)、左 Win 发 Option(⌥)，与 Mac 键盘手位一致；其余位不变 */
    private fun modBit(k: Key): Int {
        if (!isMacLayout) return k.modBit
        return when (k.modBit) {
            Mods.LALT -> Mods.LGUI
            Mods.LGUI -> Mods.LALT
            else -> k.modBit
        }
    }

    /** Mac 模式修饰键标签（⌃⌥⌘） */
    private fun modLabel(k: Key): String {
        if (!isMacLayout) return k.label
        return when (k.modBit) {
            Mods.LCTRL, Mods.RCTRL -> "⌃"
            Mods.LALT, Mods.RALT -> "⌥"
            Mods.LGUI -> "⌘"
            else -> k.label
        }
    }

    /** 本设备电量（左上角显示，60s 定时刷新） */
    private var batteryPct = 100
    private val batteryHandler = Handler(Looper.getMainLooper())
    private val batteryTask = object : Runnable {
        override fun run() {
            updateBattery()
            batteryHandler.postDelayed(this, BATTERY_REFRESH_MS)
        }
    }

    /** 状态条按钮长按：中英/输入法可直接选择发送的键值 */
    private var pendingStripId = -1
    private var stripLongRun: Runnable? = null
    private var stripLongFired = false

    /** 横屏左右边距（dp，设置页可调，避免边缘摄像头/圆角遮挡键盘） */
    private val sideInset = dp(prefs.getInt("landscape_side_margin", 0).toFloat())
    /** 键盘文字大小比例（0.7~1.6）。getter 每次读取，快捷弹窗/布局编辑调整后实时生效 */
    private val textScale: Float
        get() = prefs.getFloat("key_text_scale", 1f).coerceIn(0.7f, 1.6f)
    /** 嵌入模式：触控板占键盘区比例（20~60%）与鼠标键列宽（dp），三嵌入模式共享；getter 实时读取 */
    private val embedPadFraction: Float
        get() = prefs.getInt("embed_pad_width", 35).coerceIn(20, 60) / 100f
    private val embedMouseCol: Float
        get() = dp(prefs.getInt("embed_mouse_col", 52).coerceIn(36, 72).toFloat())

    /** 触摸板手势引擎（与竖屏 OneHandView 共用同一实现） */
    private val tp = TouchpadEngine(this, hub, prefs).apply {
        feedback = { haptic() }
    }

    private var rects: List<KeyRect> = emptyList()
    private var stripButtons: List<StripBtn> = emptyList()
    private var unitW = 0f
    private var rowH = 0f
    private var pad = 0f
    private var gap = 0f
    private var stripH = 0f
    private var radius = 0f

    // 触控状态
    private val pointerKeys = HashMap<Int, KeyRect>()
    private val pointerSent = HashMap<Int, Int>()
    private val pointerHeldMods = HashMap<Int, Int>()   // 按住型修饰键（Win / 按住模式）
    private val latchedMods = HashSet<Key>()
    /** 双击 Shift 后的持续锁定（替代 CapsLock：锁定期间字母全大写、符号全上档） */
    private var lockedBits = 0
    private var lastShiftLatchAt = 0L
    private var fnLatched = false
    /** 按住 Fn 期间打过字：抬指即结束 Fn 层（区分点按锁定与按住使用） */
    private var fnHeldUsed = false

    // 修饰键组合（如 Ctrl+Shift 切输入法）：延迟发送，期间打字则取消
    private var pendingChordBits = 0
    private var pendingChordAction: Runnable? = null

    // 锁存修饰键中已随按键真实发到电脑、待其手指抬起再放开的位：
    // 手指仍按着的修饰键等同按住，组合跨多次按键（长按 Alt 连点 Tab 循环切窗）
    private var realLatchBits = 0

    // ---------- 触控板形态 ----------
    // 开关 + 样式（full 全屏 / floating 悬浮 / embedL·embedM·embedR 嵌入），prefs 持久化
    // 初始默认：开启且为全屏触控板；轮换顺序 全屏 → 悬浮 → 嵌入
    // 首次进入默认显示键盘（触控板关闭）；用户手动开过则保持其选择
    private var padOn = prefs.getBoolean(PREF_PAD_ENABLED, false)
    private var padType = prefs.getString(PREF_PAD_TYPE, PAD_FULL) ?: PAD_FULL
    /** 触控板手势区域（悬浮含把手条外的本体；全屏/嵌入即整个区域） */
    private var padBodyRect = RectF()
    /** 悬浮模式顶部把手条（拖动位置用；为空表示无把手） */
    private var padHandleRect = RectF()
    private var floatingDragPointer = INVALID_POINTER_ID
    private var floatingDragOffX = 0f
    private var floatingDragOffY = 0f
    /** 悬浮触控板右下角缩放把手（拖拽改变大小） */
    private var floatingGripRect = RectF()
    private var floatingResizePointer = INVALID_POINTER_ID
    private var floatingResizeStartX = 0f
    private var floatingResizeStartY = 0f
    private var floatingResizeStartW = 0f
    private var floatingResizeStartH = 0f
    /** 鼠标左/中/右键列（全屏仅左列；嵌入左右各一列） */
    private var mouseKeys: List<MouseKey> = emptyList()
    /** 鼠标键列当前按住的键（非 0 = 该键保持按下，触控板移动联动成拖放） */
    private var heldMouseButton = 0
    private var heldMousePointer = INVALID_POINTER_ID

    // ---------- 底部小白条选择（长按中英/输入法/触控板模式后的选项条） ----------
    private var bottomBarLabels: List<String> = emptyList()
    private var bottomBarRects: List<RectF> = emptyList()
    private var bottomBarRect = RectF()
    private var bottomBarPick: ((Int) -> Unit)? = null
    /** 嵌入居中模式：键盘被触控板分成左右两半，右半区起点 */
    private var kbRightMid = 0f

    // ---------- 数字小键盘（顶部「数字」唤出，可拖动悬浮窗，参考悬浮触控板） ----------
    private var numpadOn = prefs.getBoolean(PREF_NUMPAD_ENABLED, false)
    private var numpadBodyRect = RectF()
    private var numpadHandleRect = RectF()
    private var numpadKeys: List<NpKeyRect> = emptyList()
    private var numpadDragPointer = INVALID_POINTER_ID
    private var numpadDragOffX = 0f
    private var numpadDragOffY = 0f
    /** 按在数字键上的手指 → 键（按住 = 电脑端自动连发） */
    private val numpadPointerKey = HashMap<Int, NpKeyRect>()

    // ---------- 软键盘（无输入框，Moonlight 式：隐藏 EditText 捕获 IME，逐字发字符串） ----------
    private var softKbOn = false
    private var softEdit: EditText? = null
    private var softLastText = ""
    /** 数字小键盘右下角缩放把手（拖拽改变悬浮窗大小） */
    private var numpadGripRect = RectF()
    private var numpadResizePointer = INVALID_POINTER_ID
    private var numpadResizeStartX = 0f
    private var numpadResizeStartY = 0f
    private var numpadResizeStartCell = 0f

    private val isFullPad: Boolean get() = padOn && padType == PAD_FULL
    private val isFloating: Boolean get() = padOn && padType == PAD_FLOATING
    private val isEmbed: Boolean get() = padOn && padType.startsWith(PAD_EMBED_PREFIX)

    /** Shift 层激活（单点锁存 或 双击持续锁定）：键帽显示上档字符，如 Fn 激活那样高亮 */
    private val shiftActive: Boolean
        get() = (lockedBits and (Mods.LSHIFT or Mods.RSHIFT)) != 0 ||
            latchedMods.any { it.modBit == Mods.LSHIFT || it.modBit == Mods.RSHIFT }

    /** 该键的 Shift 上档字符；无上档（修饰键/空格/功能键/字母键标签本就大写）返回 null */
    private fun shiftLabelOf(k: Key): String? = when (k.code) {
        Hid.ESC -> "~"   // Shift+Esc = ~（Fn 层才是 `）
        Hid.NUM_1 -> "!"; Hid.NUM_2 -> "@"; Hid.NUM_3 -> "#"; Hid.NUM_4 -> "$"
        Hid.NUM_5 -> "%"; Hid.NUM_6 -> "^"; Hid.NUM_7 -> "&"; Hid.NUM_8 -> "*"
        Hid.NUM_9 -> "("; Hid.NUM_0 -> ")"
        Hid.MINUS -> "_"; Hid.EQUAL -> "+"; Hid.LBRACKET -> "{"; Hid.RBRACKET -> "}"
        Hid.BACKSLASH -> "|"; Hid.SEMICOLON -> ":"; Hid.APOSTROPHE -> "\""
        Hid.COMMA -> "<"; Hid.PERIOD -> ">"; Hid.SLASH -> "?"; Hid.GRAVE -> "~"
        else -> null
    }

    // 键宽编辑：比例按行列位置持久化，避免 Shift/Ctrl 等重复标签互相覆盖
    private var layoutEditMode = false
    /** 状态条按钮临时高亮（中英/输入法点击反馈），300ms 后自动熄灭 */
    private var flashBtnId = -1
    private val keyWidthScales = HashMap<String, Float>()
    /** 进入编辑态时的键宽快照：点「取消」退出编辑时回滚到编辑前状态 */
    private val editStartScales = HashMap<String, Float>()
    private var resizePointerId = INVALID_POINTER_ID
    private var resizingKeyId: String? = null
    private var selectedKeyId: String? = null
    private var resizeStartX = 0f
    private var resizeStartScale = 1f

    // 颜色
    private val colorBg = Color.parseColor("#0E1116")
    private val colorKey = Color.parseColor("#1C232D")
    private val colorLetter = Color.parseColor("#232C38")   // 26 字母键：稍浅便于定位
    private val colorKeyPressed = Color.parseColor("#334152")
    private val colorKeyLatched = Color.parseColor("#3A2F1E")
    private val colorText = Color.parseColor("#E6EAF0")
    private val colorAccent = Color.parseColor("#E8944A")
    private val colorAccentDim = Color.parseColor("#8A6234")
    private val colorDim = Color.parseColor("#8A93A3")

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintFn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintShift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT   // 左上角 Shift 层字符（与右上角 Fn 层标签对称）
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintStrip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        computeLayout()
    }

    // ---------- 布局 ----------

    private fun computeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        pad = dp(6f)
        gap = dp(4f)
        radius = dp(9f)
        stripH = dp(34f)

        val fullColW = if (isFullPad) dp(FULL_COL_W_DP) else 0f    // 全屏模式左侧键列
        val mouseColW = if (isEmbed) embedMouseCol else 0f          // 嵌入模式左右键列（可调宽）

        // 键盘区边界（全屏/嵌入时两侧让出键列与触控板；sideInset 避开边缘摄像头/圆角）
        var kbLeft = pad + sideInset + fullColW + (if (fullColW > 0f) gap else 0f)
        var kbRight = w - pad - sideInset
        if (isEmbed) {
            // 鼠标键列只放在触控板对侧：嵌左（板左）右侧有键列；嵌右（板右）左侧有键列；嵌中两侧都有
            when (padType) {
                PAD_EMBED_LEFT -> kbRight -= mouseColW + gap
                PAD_EMBED_RIGHT -> kbLeft += mouseColW + gap
                else -> {
                    kbLeft += mouseColW + gap
                    kbRight -= mouseColW + gap
                }
            }
        }

        // 触控板区域
        padBodyRect = RectF()
        padHandleRect = RectF()
        floatingGripRect = RectF()
        kbRightMid = 0f
        when {
            isFullPad -> padBodyRect = RectF(kbLeft, stripH + pad, kbRight, h - pad)
            isEmbed -> {
                val tpW = (kbRight - kbLeft) * embedPadFraction
                when (padType) {
                    PAD_EMBED_LEFT -> {   // 触控板在左，键盘靠右
                        padBodyRect = RectF(kbLeft, stripH + pad, kbLeft + tpW, h - pad)
                        kbLeft += tpW + gap
                    }
                    PAD_EMBED_RIGHT -> {  // 触控板在右，键盘靠左
                        padBodyRect = RectF(kbRight - tpW, stripH + pad, kbRight, h - pad)
                        kbRight -= tpW + gap
                    }
                    else -> {             // 触控板居中，键盘分成左右两半
                        val cx = (kbLeft + kbRight) / 2f
                        padBodyRect = RectF(cx - tpW / 2f, stripH + pad, cx + tpW / 2f, h - pad)
                        kbRightMid = cx + tpW / 2f + gap
                        val kbRightKeep = kbRight
                        kbRight = cx - tpW / 2f - gap
                        // 右半区上边界复用 kbRightMid；结束边界存临时变量
                        embedRightEdge = kbRightKeep
                    }
                }
            }
            isFloating -> {
                val defW = minOf(w * 0.44f, dp(320f))
                val defH = minOf(h * 0.56f, dp(380f))
                val pwRaw = prefs.getFloat(PREF_FLT_W, 0f)
                val phRaw = prefs.getFloat(PREF_FLT_H, 0f)
                val pw = if (pwRaw > 0f) dp(pwRaw).coerceIn(dp(FLT_MIN_W_DP), w * 0.9f) else defW
                val ph = if (phRaw > 0f) dp(phRaw).coerceIn(dp(FLT_MIN_H_DP), h * 0.9f) else defH
                val sx = prefs.getFloat(PREF_FLT_X, (w - pw - pad - sideInset).coerceAtLeast(pad + sideInset))
                val sy = prefs.getFloat(PREF_FLT_Y, (h - ph - pad).coerceAtLeast(stripH + pad))
                val bodyH = ph - dp(HANDLE_H_DP) - gap
                padBodyRect = RectF(sx, sy + dp(HANDLE_H_DP) + gap, sx + pw, sy + ph)
                padHandleRect = RectF(sx, sy, sx + pw, sy + dp(HANDLE_H_DP))
                val grip = dp(FLT_GRIP_DP)
                floatingGripRect = RectF(
                    padBodyRect.right - grip - dp(3f),
                    padBodyRect.bottom - grip - dp(3f),
                    padBodyRect.right - dp(3f),
                    padBodyRect.bottom - dp(3f),
                )
            }
        }

        // 数字小键盘（悬浮在键盘上方，独立于触控板；位置与大小持久化）
        numpadBodyRect = RectF()
        numpadHandleRect = RectF()
        numpadGripRect = RectF()
        numpadKeys = emptyList()
        if (numpadOn) {
            val cell = dp(prefs.getFloat(PREF_NUMPAD_CELL, NP_CELL_DP).coerceIn(NP_CELL_MIN_DP, NP_CELL_MAX_DP))
            val padNp = dp(NP_PAD_DP)
            val gapNp = dp(NP_GAP_DP)
            val bodyW = 4 * cell + 3 * gapNp + 2 * padNp
            val bodyH = 5 * cell + 4 * gapNp + 2 * padNp
            val defX = (w - bodyW - pad - sideInset).coerceAtLeast(pad + sideInset)
            val defY = (h - bodyH - pad).coerceAtLeast(stripH + pad + dp(NP_HANDLE_H_DP) + gapNp)
            val bx = prefs.getFloat(PREF_NUMPAD_X, defX)
                .coerceIn(pad + sideInset, w - bodyW - pad - sideInset)
            val by = prefs.getFloat(PREF_NUMPAD_Y, defY)
                .coerceIn(stripH + pad + dp(NP_HANDLE_H_DP) + gapNp, h - bodyH - pad)
            numpadBodyRect = RectF(bx, by, bx + bodyW, by + bodyH)
            numpadHandleRect = RectF(bx, by - dp(NP_HANDLE_H_DP) - gapNp, bx + bodyW, by - gapNp)
            val grip = dp(NP_GRIP_DP)
            numpadGripRect = RectF(
                bx + bodyW - grip - dp(3f),
                by + bodyH - grip - dp(3f),
                bx + bodyW - dp(3f),
                by + bodyH - dp(3f),
            )
            val keys = ArrayList<NpKeyRect>(NUMPAD_LAYOUT.size * 4)
            // 每行被上方跨行键（+ / Ent）占掉的列号
            val skipCols = Array(NUMPAD_LAYOUT.size) { HashSet<Int>() }
            for ((ri, row) in NUMPAD_LAYOUT.withIndex()) {
                var col = 0
                var x = bx + padNp
                val y = by + padNp + ri * (cell + gapNp)
                for (cellDef in row) {
                    // 跳过被上方跨行键占用的列
                    while (col < 4 && col in skipCols[ri]) {
                        col++
                        x += cell + gapNp
                    }
                    val cw = cell * cellDef.span + gapNp * (cellDef.span - 1)
                    val ch = cell * cellDef.rowSpan + gapNp * (cellDef.rowSpan - 1)
                    keys.add(NpKeyRect(RectF(x, y, x + cw, y + ch), cellDef))
                    for (r in 1 until cellDef.rowSpan) {
                        if (ri + r < skipCols.size) skipCols[ri + r].add(col)
                    }
                    x += cw + gapNp
                    col++
                }
            }
            numpadKeys = keys
        }

        // 键盘行：16u 归一化到键盘区（嵌入居中时分两半）
        unitW = (kbRight - kbLeft - gap * 16) / 16f
        rowH = (h - stripH - pad * 2 - gap * 4) / 5f
        val list = ArrayList<KeyRect>(68)
        var y = stripH + pad
        for ((rowIndex, row) in Layout68.rows.withIndex()) {
            if (isEmbed && padType == PAD_EMBED_MID && kbRightMid > 0f) {
                val (l, r) = splitRow(row)
                if (l.isNotEmpty()) placeRow(list, rowIndex, l, kbLeft, kbRight, y)
                if (r.isNotEmpty()) placeRow(list, rowIndex, r, kbRightMid, embedRightEdge, y)
            } else {
                placeRow(list, rowIndex, row, kbLeft, kbRight, y)
            }
            y += rowH + gap
        }
        rects = list

        // 鼠标键列
        buildMouseKeys(w, h, fullColW, mouseColW)

        // 顶部快捷按钮：从右往左排（状态文本在左侧）
        val btnDefs = if (layoutEditMode) {
            listOf(
                Triple("取消", BTN_EXIT, dp(52f)),
                Triple("完成", BTN_LAYOUT, dp(52f)),
                Triple("尺寸", BTN_LAYOUT_SIZE, dp(52f)),
                Triple("重置", BTN_LAYOUT_RESET, dp(52f)),
            )
        } else {
            buildList {
                add(Triple("✕", BTN_EXIT, dp(34f)))
                add(Triple(if (hub.mode == InputHub.MODE_LAN) "局域网" else "蓝牙", BTN_MODE, dp(56f)))
                add(Triple("检查", BTN_CHECK, dp(42f)))
                add(Triple("竖屏", BTN_ONEHAND, dp(42f)))
                add(Triple("布局", BTN_LAYOUT, dp(42f)))
                add(Triple("字号", BTN_TEXT_SCALE, dp(42f)))
                add(Triple(padTypeLabel(), BTN_PAD_TYPE, dp(48f)))
                add(Triple("触控板", BTN_PAD, dp(50f)))
                add(Triple("数字", BTN_NUMPAD, dp(42f)))
                // 软键盘文本输入只走局域网通道，蓝牙模式不显示
                if (hub.mode == InputHub.MODE_LAN) add(Triple("软键盘", BTN_SOFT_KB, dp(48f)))
                add(Triple("组合键", BTN_COMBO, dp(48f)))
                add(Triple("输入法", BTN_IME, dp(52f)))
                add(Triple("中英", BTN_SHIFT, dp(44f)))
            }
        }
        val btnH = dp(24f)
        val btnY = (stripH - btnH) / 2f
        val gapBtn = dp(8f)
        var xr = w - pad - sideInset - dp(2f)
        // 按钮总宽超出状态文本区域时按比例缩小，窄屏不溢出、宽屏保持原样
        var totalW = 0f
        for (b in btnDefs) totalW += b.third
        totalW += gapBtn * (btnDefs.size - 1)
        val avail = (xr - (pad + sideInset + dp(28f))).coerceAtLeast(dp(10f))
        val scale = if (totalW > avail) avail / totalW else 1f
        val btns = ArrayList<StripBtn>(btnDefs.size)
        for ((label, id, bw) in btnDefs) {
            val bwd = bw * scale
            btns.add(StripBtn(RectF(xr - bwd, btnY, xr, btnY + btnH), label, id))
            xr -= bwd + gapBtn
        }
        stripButtons = btns
    }

    /** 嵌入居中：把一行按键按 16u 一半处切成左右两段 */
    private fun splitRow(keys: List<Key>): Pair<List<Key>, List<Key>> {
        var acc = 0f
        for (i in keys.indices) {
            val u = keys[i].width
            if (acc + u / 2f >= ROW_WIDTH_UNITS / 2f) {
                return keys.subList(0, i + 1) to keys.subList(i + 1, keys.size)
            }
            acc += u
        }
        return keys to emptyList()
    }

    /** 把一行按键按原始占比铺进 [left, right] 区间（行内各键宽度比例 = 自定义键宽） */
    private fun placeRow(
        list: MutableList<KeyRect>,
        rowIndex: Int,
        row: List<Key>,
        left: Float,
        right: Float,
        top: Float,
    ) {
        val rawWidths = row.mapIndexed { column, key ->
            key.width * keyWidthScale(widthPrefKey(rowIndex, column))
        }
        val available = (right - left) - gap * (row.size - 1)
        val normalize = available / rawWidths.sum().coerceAtLeast(1f)
        var x = left
        for ((column, key) in row.withIndex()) {
            val keyWidth = rawWidths[column] * normalize
            val prefKey = widthPrefKey(rowIndex, column)
            list.add(KeyRect(key, prefKey, x, top, x + keyWidth, top + rowH))
            x += keyWidth + gap
        }
    }

    /** 生成鼠标左/中/右键列（全屏仅左列；嵌入只在对侧，嵌中左右各一列） */
    private fun buildMouseKeys(w: Float, h: Float, fullColW: Float, mouseColW: Float) {
        mouseKeys = emptyList()
        val cols = ArrayList<Pair<Float, Float>>()
        if (isFullPad) cols.add((pad + sideInset) to (pad + sideInset + fullColW))
        if (isEmbed) {
            when (padType) {
                PAD_EMBED_LEFT -> cols.add((w - pad - sideInset - mouseColW) to (w - pad - sideInset))   // 板在左 → 右侧键列
                PAD_EMBED_RIGHT -> cols.add((pad + sideInset) to (pad + sideInset + mouseColW))          // 板在右 → 左侧键列
                else -> {
                    cols.add((pad + sideInset) to (pad + sideInset + mouseColW))
                    cols.add((w - pad - sideInset - mouseColW) to (w - pad - sideInset))
                }
            }
        }
        if (cols.isEmpty()) return
        val bh = (h - stripH - pad * 2 - gap * 2) / 3f
        val list = ArrayList<MouseKey>(cols.size * 3)
        for ((l, r) in cols) {
            var y = stripH + pad
            for ((label, btn) in MOUSE_KEY_DEFS) {
                list.add(MouseKey(RectF(l, y, r, y + bh), btn, label))
                y += bh + gap
            }
        }
        mouseKeys = list
    }

    private var embedRightEdge = 0f

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBg)
        if (rects.isEmpty()) return

        drawStrip(canvas)

        if (isFullPad) {
            drawPadSurface(canvas, padBodyRect)
            drawMouseKeys(canvas)
            return
        }

        for (r in rects) {
            val k = r.key
            val editing = layoutEditMode && r.prefKey == selectedKeyId
            val pressed = pointerKeys.containsValue(r)
            val latched = k.isModifier && modBit(k) != Mods.LGUI && k in latchedMods
            // 双击锁定的 Shift：橙框 + 下缘橙条
            val locked = k.isModifier &&
                (k.modBit == Mods.LSHIFT || k.modBit == Mods.RSHIFT) &&
                (lockedBits and k.modBit) != 0
            // 电脑发回的 LED 状态：大写锁定时 Caps 键常亮
            val capsLit = k.code == Hid.CAPSLOCK && hub.capsOn
            val fnActive = fnLatched && k.fnLabel != null
            // Fn 键自身：Fn 层开启时点亮（与 Shift/Ctrl 锁存样式一致）
            val fnLit = fnLatched && k.code == -1

            // 26 字母键用稍浅底色，便于盲打定位
            val isLetter = k.label.length == 1 && k.label[0] in 'A'..'Z'
            paintFill.color = when {
                editing -> colorKeyLatched
                pressed -> colorKeyPressed
                latched -> colorKeyLatched
                capsLit -> colorKeyLatched
                fnLit -> colorKeyLatched
                isLetter -> colorLetter
                else -> colorKey
            }
            canvas.drawRoundRect(r.rect, radius, radius, paintFill)

            if (layoutEditMode || latched || fnActive || capsLit || locked || fnLit) {
                paintStroke.color = if (editing || latched || fnActive || capsLit || locked || fnLit) colorAccent else colorAccentDim
                canvas.drawRoundRect(r.rect, radius, radius, paintStroke)
            }
            if (locked) {
                paintFill.color = colorAccent
                canvas.drawRoundRect(
                    RectF(r.rect.left + dp(10f), r.rect.bottom - dp(5f), r.rect.right - dp(10f), r.rect.bottom - dp(2f)),
                    dp(2f), dp(2f), paintFill,
                )
            }

            val shownBase = if (k.isModifier) modLabel(k) else k.label
            val visibleLabel = if (layoutEditMode && shownBase.isEmpty()) "空格" else shownBase
            // Shift 激活时该键若存在上档字符，主标签切换为上档字符（如 Fn 激活那样）
            val shifted = shiftActive && shiftLabelOf(k) != null
            // 文字大小按每个键的实际宽高与标签长度适配：嵌中时键盘被分割变窄，
            // 但行高充足，按键完全容纳得下更大的文字（textScale 为设置页可调倍率）
            val shown = when {
                fnActive -> k.fnLabel!!
                shifted -> shiftLabelOf(k)!!
                else -> visibleLabel
            }
            val mainSize = minOf(
                r.rect.height() * 0.34f,
                r.rect.width() * 0.85f / maxOf(shown.length, 1),
            ) * textScale
            val fnSize = mainSize * 0.52f
            if (visibleLabel.isNotEmpty()) {
                paintText.color = when {
                    editing -> colorAccent
                    fnActive || fnLit || shifted -> colorAccent
                    capsLit && k.code == Hid.CAPSLOCK -> colorAccent
                    locked -> colorAccent
                    else -> colorText
                }
                paintText.textSize = mainSize
                val cx = r.rect.centerX()
                val cy = r.rect.centerY() + if (k.fnLabel != null) mainSize * 0.22f else 0f
                val fm = paintText.fontMetrics
                canvas.drawText(
                    shown,
                    cx,
                    cy - (fm.ascent + fm.descent) / 2f,
                    paintText,
                )
            }

            if (editing) {
                // 右侧双线是拖动把手；比例同时显示在键帽左下角。
                paintStroke.color = colorAccent
                paintStroke.strokeWidth = dp(2f)
                val handleX = r.rect.right - dp(7f)
                canvas.drawLine(handleX - dp(3f), r.rect.centerY() - dp(9f), handleX - dp(3f), r.rect.centerY() + dp(9f), paintStroke)
                canvas.drawLine(handleX + dp(3f), r.rect.centerY() - dp(9f), handleX + dp(3f), r.rect.centerY() + dp(9f), paintStroke)
                paintStroke.strokeWidth = dp(1.5f)
                paintStrip.color = colorAccent
                paintStrip.textSize = dp(9f)
                canvas.drawText(
                    "%.0f%%".format(keyWidthScale(r.prefKey) * 100f),
                    r.rect.left + dp(6f),
                    r.rect.bottom - dp(5f),
                    paintStrip,
                )
            }

            // 嵌入模式下键窄：右上 Fn 与左上 Shift 两个角标和主标签放不下时，隐藏 Fn 角标
            val sl = if (shiftActive) null else shiftLabelOf(k)
            var hideFnHint = false
            if (isEmbed && k.fnLabel != null && !fnActive && k.label.isNotEmpty()) {
                paintFn.textSize = fnSize
                paintShift.textSize = fnSize
                val half = r.rect.width() / 2f - dp(7f)
                val mainW = paintText.measureText(shown)
                hideFnHint =
                    mainW / 2f + paintFn.measureText(k.fnLabel) > half ||
                    (sl != null && mainW / 2f + paintShift.measureText(sl) > half)
            }

            // 未激活时在右上角显示 Fn 层标签
            if (k.fnLabel != null && !fnActive && k.label.isNotEmpty() && !hideFnHint) {
                paintFn.color = colorAccentDim
                paintFn.textSize = fnSize
                canvas.drawText(
                    k.fnLabel,
                    r.rect.right - dp(7f),
                    r.rect.top + dp(6f) + fnSize,
                    paintFn,
                )
            }

            // 未激活时在左上角显示 Shift 上档字符（与右上角 Fn 标签对称）
            if (!shiftActive && k.label.isNotEmpty() && sl != null) {
                paintShift.color = colorAccentDim
                paintShift.textSize = fnSize
                canvas.drawText(
                    sl,
                    r.rect.left + dp(7f),
                    r.rect.top + dp(6f) + fnSize,
                    paintShift,
                )
            }
        }

        // 触控板浮层/嵌入
        if (isEmbed) drawPadSurface(canvas, padBodyRect)
        if (isFloating) drawFloatingPad(canvas)
        drawMouseKeys(canvas)
        // 数字小键盘永远最上层（悬浮窗）
        if (numpadOn) drawNumpad(canvas)
        // 底部白条选择（最上层）
        if (bottomBarRects.isNotEmpty()) drawBottomBar(canvas)
    }

    private fun drawStrip(canvas: Canvas) {
        val status = if (layoutEditMode) {
            val selected = rects.firstOrNull { it.prefKey == selectedKeyId }
            if (selected == null) "布局编辑 · 选中键帽后左右拖动" else "正在调整 ${keyName(selected.key)} · 左右拖动"
        } else {
            hub.statusLine()
        }
        drawBattery(canvas)
        // 普通模式显示连接状态；布局编辑时显示操作提示。
        paintStrip.color = if (layoutEditMode || hub.btConnected || hub.lanConnected) colorAccent else colorDim
        paintStrip.textSize = dp(13f)
        canvas.drawText(status, pad + sideInset + dp(28f), stripH / 2f + dp(5f), paintStrip)

        // 快捷按钮
        for (b in stripButtons) {
            val active = (b.id == BTN_PAD && padOn) || (b.id == BTN_NUMPAD && numpadOn) ||
                (b.id == BTN_SOFT_KB && softKbOn) || (b.id == BTN_LAYOUT && layoutEditMode)
            val flashing = b.id == flashBtnId
            paintFill.color = if (active || flashing) colorKeyLatched else colorKey
            canvas.drawRoundRect(b.rect, radius, radius, paintFill)
            if (active || flashing) {
                paintStroke.color = colorAccent
                canvas.drawRoundRect(b.rect, radius, radius, paintStroke)
            }
            paintText.color = if (active || flashing) colorAccent else colorText
            paintText.textSize = dp(11f)
            val fm = paintText.fontMetrics
            canvas.drawText(
                b.label,
                b.rect.centerX(),
                b.rect.centerY() - (fm.ascent + fm.descent) / 2f,
                paintText,
            )
            // 可长按的按钮（中英/输入法/触控板模式）：从未长按过 = 右上角橙点引导；
            // 长按过至少一次 = 底部 2px 圆角浅白横线（表示已会用）
            if (b.id == BTN_SHIFT || b.id == BTN_IME || b.id == BTN_PAD_TYPE) {
                if (prefs.getBoolean(longPressPrefKey(b.id), false)) {
                    paintFill.color = Color.parseColor("#59D8DEE8")   // 半透明浅白（35% 透明度），不刺眼
                    val lw = b.rect.width() * 0.5f
                    canvas.drawRoundRect(
                        RectF(
                            b.rect.centerX() - lw / 2f,
                            b.rect.bottom - dp(4f),
                            b.rect.centerX() + lw / 2f,
                            b.rect.bottom - dp(2f),
                        ),
                        dp(1f), dp(1f), paintFill,
                    )
                } else {
                    paintFill.color = colorAccent
                    canvas.drawCircle(b.rect.right - dp(4f), b.rect.top + dp(4f), dp(1.8f), paintFill)
                }
            }
        }
    }

    /** 左上角本设备电量：电池图标 + 百分比 */
    private fun drawBattery(canvas: Canvas) {
        val x = pad + sideInset + dp(2f)
        val cy = stripH / 2f
        val bw = dp(20f)
        val bh = dp(11f)
        val pct = batteryPct.coerceIn(0, 100)
        // 电池外框 + 电极
        paintStroke.color = colorDim
        paintStroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(RectF(x, cy - bh / 2f, x + bw, cy + bh / 2f), dp(2f), dp(2f), paintStroke)
        canvas.drawRect(RectF(x + bw + dp(0.5f), cy - dp(2.5f), x + bw + dp(2.5f), cy + dp(2.5f)), paintStroke)
        // 电量填充（≤20% 变红）
        val fillW = (bw - dp(2f)) * pct / 100f
        paintFill.color = if (pct <= 20) Color.parseColor("#E05A5A") else colorAccent
        canvas.drawRoundRect(
            RectF(x + dp(1f), cy - bh / 2f + dp(1f), x + dp(1f) + fillW, cy + bh / 2f - dp(1f)),
            dp(1f), dp(1f), paintFill,
        )
    }

    /** 触控板区域绘制：背景 + 右缘滚动条 + 手势提示（全屏/悬浮/嵌入共用） */
    private fun drawPadSurface(canvas: Canvas, r: RectF) {
        paintFill.color = colorKey
        canvas.drawRoundRect(r, radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(r, radius, radius, paintStroke)

        // 右缘滚动条（贴条上下滑 = 滚动）
        paintFill.color = Color.parseColor("#161D27")
        canvas.drawRoundRect(
            RectF(r.right - dp(30f), r.top + dp(5f), r.right - dp(4f), r.bottom - dp(5f)),
            radius, radius, paintFill,
        )
        paintText.color = Color.parseColor("#5A6474")
        paintText.textSize = dp(10f)
        val fm = paintText.fontMetrics
        canvas.drawText("滚", r.right - dp(17f), r.top + dp(26f) - fm.ascent - fm.descent, paintText)

        paintText.color = colorDim
        paintText.textSize = dp(13f)
        canvas.drawText("触控板", r.centerX(), r.centerY() - dp(18f), paintText)
        paintText.textSize = dp(10f)
        canvas.drawText("单指移动 · 轻点=左键 · 双指=右键", r.centerX(), r.centerY() + dp(2f), paintText)
        canvas.drawText("长按拖动 · 双指滑动=滚动", r.centerX(), r.centerY() + dp(16f), paintText)
    }

    /** 悬浮触控板：把手条 + 触控板本体 */
    private fun drawFloatingPad(canvas: Canvas) {
        // 把手条
        paintFill.color = Color.parseColor("#222B36")
        canvas.drawRoundRect(padHandleRect, radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(padHandleRect, radius, radius, paintStroke)
        paintText.color = colorAccentDim
        paintText.textSize = dp(10f)
        canvas.drawText("≡ 拖动位置", padHandleRect.centerX(), padHandleRect.centerY() + dp(3.5f), paintText)
        // 本体
        drawPadSurface(canvas, padBodyRect)
        // 右下角缩放把手（三条斜线）
        if (!floatingGripRect.isEmpty) {
            paintStroke.color = colorAccent
            paintStroke.strokeWidth = dp(2f)
            val g = floatingGripRect
            for (k in 0..2) {
                val off = k * dp(3.5f)
                canvas.drawLine(
                    g.left + off, g.bottom, g.right, g.top + off,
                    paintStroke,
                )
            }
        }
    }

    /** 鼠标键列（左键/中键/右键） */
    private fun drawMouseKeys(canvas: Canvas) {
        for (mk in mouseKeys) {
            paintFill.color = colorKey
            canvas.drawRoundRect(mk.rect, radius, radius, paintFill)
            paintStroke.color = colorAccentDim
            canvas.drawRoundRect(mk.rect, radius, radius, paintStroke)
            paintText.color = colorDim
            paintText.textSize = dp(14f)
            val fm = paintText.fontMetrics
            canvas.drawText(mk.label, mk.rect.centerX(), mk.rect.centerY() - (fm.ascent + fm.descent) / 2f, paintText)
        }
    }

    /** 数字小键盘：把手条 + 4×5 键格（悬浮窗，最上层） */
    private fun drawNumpad(canvas: Canvas) {
        // 把手条
        paintFill.color = Color.parseColor("#222B36")
        canvas.drawRoundRect(numpadHandleRect, radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(numpadHandleRect, radius, radius, paintStroke)
        paintText.color = colorAccentDim
        paintText.textSize = dp(10f)
        canvas.drawText("≡ 拖动", numpadHandleRect.centerX(), numpadHandleRect.centerY() + dp(3.5f), paintText)
        // 面板底
        paintFill.color = Color.parseColor("#161D27")
        canvas.drawRoundRect(numpadBodyRect, radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(numpadBodyRect, radius, radius, paintStroke)
        // 键格
        val keyRadius = radius * 0.7f
        // 修改模式（NumLock 关，已确认）：带 altLabel 的键像 Fn 层那样整体切换键帽并高亮。
        // 主机权威：ledKnown=false（未知）时保持数字，不猜测。
        val altMode = hub.ledKnown && !hub.numOn
        // NumLock 开（确认）：Num 键点亮，指示当前为数字模式
        val numLit = hub.ledKnown && hub.numOn
        for (nk in numpadKeys) {
            val pressed = numpadPointerKey.containsValue(nk)
            val isNumKey = nk.cell.code == Hid.NUM_LOCK
            val isAlt = altMode && nk.cell.altLabel.isNotEmpty()
            paintFill.color = when {
                pressed -> colorKeyPressed
                isAlt || (isNumKey && numLit) -> colorKeyLatched
                else -> colorKey
            }
            canvas.drawRoundRect(nk.rect, keyRadius, keyRadius, paintFill)
            if (isAlt || (isNumKey && numLit)) {
                paintStroke.color = colorAccent
                canvas.drawRoundRect(nk.rect, keyRadius, keyRadius, paintStroke)
            }
            // 主标签：修改模式下切换为 altLabel（像 Fn）；NumLock 开时 Num 键文字为主色
            val shownLabel = if (isAlt) nk.cell.altLabel else nk.cell.label
            paintText.color = when {
                isAlt || (isNumKey && numLit) -> colorAccent
                else -> colorText
            }
            paintText.textSize = dp(14f)
            val fm = paintText.fontMetrics
            canvas.drawText(
                shownLabel,
                nk.rect.centerX(),
                nk.rect.centerY() - (fm.ascent + fm.descent) / 2f,
                paintText,
            )
            // 右上角标注（仅数字模式/未知时）：提示 NumLock 关闭后的操作
            // 修改模式下键帽已切换为对应按键，不再重复标注；主机权威：只有确认关才橙色
            if (nk.cell.altLabel.isNotEmpty() && !isAlt) {
                paintText.color = if (altMode) colorAccent else Color.parseColor("#5A6474")
                paintText.textSize = if (altMode) dp(9f) else dp(8f)
                paintText.textAlign = Paint.Align.RIGHT
                canvas.drawText(nk.cell.altLabel, nk.rect.right - dp(4f), nk.rect.top + dp(10f), paintText)
                paintText.textAlign = Paint.Align.CENTER
            }
        }
        // 右下角缩放把手（三条斜线）
        if (!numpadGripRect.isEmpty) {
            paintStroke.color = colorAccent
            paintStroke.strokeWidth = dp(2f)
            val g = numpadGripRect
            for (k in 0..2) {
                val off = k * dp(3.5f)
                canvas.drawLine(
                    g.left + off, g.bottom, g.right, g.top + off,
                    paintStroke,
                )
            }
        }
    }

    // ---------- 触控分发 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 调整中的手指即使滑进顶部状态条，也继续归布局编辑处理，避免误触按钮。
        if (layoutEditMode && resizePointerId != INVALID_POINTER_ID) {
            handleLayoutResize(e)
            return true
        }

        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)

        // 底部白条选择：命中选项即触发并收起；点条外收起；其余事件不穿透到键盘
        if (bottomBarRects.isNotEmpty()) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                var hit = false
                for ((bi, r) in bottomBarRects.withIndex()) {
                    if (r.contains(x, y)) {
                        hit = true
                        haptic()
                        val pick = bottomBarPick
                        hideBottomBar()
                        pick?.invoke(bi)
                        break
                    }
                }
                if (!hit && !bottomBarRect.contains(x, y)) hideBottomBar()
                return true
            }
            return true
        }

        // 状态条：快捷按钮（短按执行；长按中英/输入法 = 弹出键值直接选择）
        if (y < stripH) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    for (b in stripButtons) {
                        if (b.contains(x, y)) {
                            pendingStripId = b.id
                            stripLongFired = false
                            stripLongRun?.let { removeCallbacks(it) }
                            val run = Runnable {
                                stripLongRun = null
                                stripLongFired = true
                                when (b.id) {
                                    BTN_SHIFT, BTN_IME -> {
                                        haptic()
                                        openComboPicker(b.id)
                                    }
                                    BTN_PAD_TYPE -> {
                                        haptic()
                                        openPadTypePicker()
                                    }
                                    else -> {}
                                }
                            }
                            stripLongRun = run
                            postDelayed(run, STRIP_LONG_PRESS_MS)
                            break
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (!stripLongFired) {
                        stripLongRun?.let { removeCallbacks(it) }
                        stripLongRun = null
                        val id = pendingStripId
                        if (id != -1) onStripButton(id)
                    }
                    pendingStripId = -1
                    stripLongFired = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    stripLongRun?.let { removeCallbacks(it) }
                    stripLongRun = null
                    pendingStripId = -1
                    stripLongFired = false
                }
            }
            return true
        }

        if (layoutEditMode) {
            handleLayoutResize(e)
            return true
        }

        // ---------- 数字小键盘（悬浮窗，优先于触控板/键盘） ----------
        if (numpadOn && numpadResizePointer != INVALID_POINTER_ID) {
            handleNumpadResize(e)
            return true
        }
        if (numpadOn && numpadDragPointer != INVALID_POINTER_ID) {
            handleNumpadDrag(e)
            return true
        }
        if (numpadOn && (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            val i = e.actionIndex
            val x = e.getX(i)
            val y = e.getY(i)
            // 右下角把手：按住拖拽改变悬浮窗大小（锚定左上角）
            if (!numpadGripRect.isEmpty && numpadGripRect.contains(x, y)) {
                numpadResizePointer = e.getPointerId(i)
                numpadResizeStartX = x
                numpadResizeStartY = y
                numpadResizeStartCell = prefs.getFloat(PREF_NUMPAD_CELL, NP_CELL_DP)
                    .coerceIn(NP_CELL_MIN_DP, NP_CELL_MAX_DP)
                haptic()
                return true
            }
            // 把手条：按住拖动位置
            if (!numpadHandleRect.isEmpty && numpadHandleRect.contains(x, y)) {
                numpadDragPointer = e.getPointerId(i)
                numpadDragOffX = x - numpadBodyRect.left
                numpadDragOffY = y - numpadBodyRect.top
                haptic()
                return true
            }
            // 键格：按下即发送（按住 = 电脑端连发）；面板区域不穿透到底下键盘
            if (!numpadBodyRect.isEmpty && numpadBodyRect.contains(x, y)) {
                val nk = numpadKeys.firstOrNull { it.contains(x, y) }
                if (nk != null) {
                    numpadPointerKey[e.getPointerId(i)] = nk
                    haptic()
                    hub.keyDown(nk.cell.code, nk.cell.mods)
                }
                return true
            }
        }
        if (numpadOn && numpadPointerKey.isNotEmpty()) {
            when (e.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val nk = numpadPointerKey.remove(e.getPointerId(e.actionIndex))
                    if (nk != null) {
                        hub.keyUp(nk.cell.code)
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    for (nk in numpadPointerKey.values) hub.keyUp(nk.cell.code)
                    numpadPointerKey.clear()
                    return true
                }
            }
        }

        // 悬浮触控板：缩放把手拖动（进行中）或位置拖动（进行中）
        if (floatingResizePointer != INVALID_POINTER_ID) {
            handleFloatingResize(e)
            return true
        }
        if (floatingDragPointer != INVALID_POINTER_ID) {
            handleFloatingDrag(e)
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // 右下角把手：按住拖拽改变悬浮触控板大小
            if (isFloating && !floatingGripRect.isEmpty && floatingGripRect.contains(x, y)) {
                floatingResizePointer = e.getPointerId(i)
                floatingResizeStartX = x
                floatingResizeStartY = y
                floatingResizeStartW = padBodyRect.width()
                floatingResizeStartH = padBodyRect.height()
                haptic()
                return true
            }
            // 悬浮把手条：按住拖动位置
            if (isFloating && !padHandleRect.isEmpty && padHandleRect.contains(x, y)) {
                floatingDragPointer = e.getPointerId(i)
                floatingDragOffX = x - padBodyRect.left
                floatingDragOffY = y - padBodyRect.top
                haptic()
                return true
            }
            // 鼠标键列：按住 = 该鼠标键保持按下，配合触控板移动形成拖放/框选；
            // 快速按下抬起仍是普通点击
            for (mk in mouseKeys) {
                if (mk.rect.contains(x, y)) {
                    haptic()
                    heldMouseButton = mk.button
                    heldMousePointer = e.getPointerId(i)
                    tp.externalButton = mk.button
                    hub.sendMouse(0, 0, 0, mk.button)
                    return true
                }
            }
        }
        // 鼠标键列抬起 / 取消：释放按住的键
        if (heldMousePointer != INVALID_POINTER_ID &&
            (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_POINTER_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            val pid = e.getPointerId(e.actionIndex)
            if (e.actionMasked == MotionEvent.ACTION_CANCEL || pid == heldMousePointer) {
                hub.sendMouse(0, 0, 0, 0)
                heldMouseButton = 0
                heldMousePointer = INVALID_POINTER_ID
                tp.externalButton = 0
                return true
            }
        }

        // 触控板区域（全屏/悬浮/嵌入）
        // 注意：ACTION_MOVE 的 actionIndex 恒为 0，若 0 号手指是鼠标键列等触控板外的手指，
        // 按坐标判定会漏掉触控板上的移动；故 MOVE/CANCEL 直接路由给引擎（引擎按自身记录的手指筛选）
        if (padOn && !padBodyRect.isEmpty) {
            val padHit = when (e.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> true
                else -> padBodyRect.contains(x, y)
            }
            if (padHit) {
                handlePadEvent(e)
                return true
            }
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                press(e.getPointerId(i), x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    /** 拖动数字小键盘位置（把手条，与悬浮触控板同一套逻辑） */
    private fun handleNumpadDrag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(numpadDragPointer)
                if (i < 0) return
                val nx = (e.getX(i) - numpadDragOffX)
                    .coerceIn(pad + sideInset, width - numpadBodyRect.width() - pad - sideInset)
                val ny = (e.getY(i) - numpadDragOffY)
                    .coerceIn(
                        stripH + pad + dp(NP_HANDLE_H_DP) + dp(NP_GAP_DP),
                        height - numpadBodyRect.height() - pad,
                    )
                if (nx != numpadBodyRect.left || ny != numpadBodyRect.top) {
                    val dx = nx - numpadBodyRect.left
                    val dy = ny - numpadBodyRect.top
                    numpadBodyRect.offset(dx, dy)
                    numpadHandleRect.offset(dx, dy)
                    numpadGripRect.offset(dx, dy)
                    for (k in numpadKeys) k.rect.offset(dx, dy)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == numpadDragPointer) {
                    numpadDragPointer = INVALID_POINTER_ID
                    prefs.edit()
                        .putFloat(PREF_NUMPAD_X, numpadBodyRect.left)
                        .putFloat(PREF_NUMPAD_Y, numpadBodyRect.top)
                        .apply()
                }
            }
            MotionEvent.ACTION_CANCEL -> numpadDragPointer = INVALID_POINTER_ID
        }
    }

    /** 拖拽右下角把手缩放数字小键盘（锚定左上角，格子边长按拖动量增减） */
    private fun handleNumpadResize(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(numpadResizePointer)
                if (i < 0) return
                // 面板宽 = 4 格 + 缝隙 + 边距，横向拖动 4px ≈ 1 格变 1px
                val dxPx = e.getX(i) - numpadResizeStartX
                val dyPx = e.getY(i) - numpadResizeStartY
                val density = resources.displayMetrics.density
                // 宽向 4 列缩放，高向 5 行缩放，取变化小的方向跟随，避免倾斜失真
                val cellByW = dxPx / 4f / density
                val cellByH = dyPx / 5f / density
                val delta = if (kotlin.math.abs(cellByW) <= kotlin.math.abs(cellByH)) cellByW else cellByH
                val cell = (numpadResizeStartCell + delta).coerceIn(NP_CELL_MIN_DP, NP_CELL_MAX_DP)
                prefs.edit().putFloat(PREF_NUMPAD_CELL, cell).apply()
                computeLayout()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == numpadResizePointer) {
                    numpadResizePointer = INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_CANCEL -> numpadResizePointer = INVALID_POINTER_ID
        }
    }

    /** 拖拽悬浮触控板右下角把手：改变悬浮窗大小（锚定左上角） */
    private fun handleFloatingResize(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(floatingResizePointer)
                if (i < 0) return
                val density = resources.displayMetrics.density
                val wDp = ((floatingResizeStartW + (e.getX(i) - floatingResizeStartX)) / density)
                    .coerceIn(FLT_MIN_W_DP, FLT_MAX_W_DP)
                val hDp = ((floatingResizeStartH + (e.getY(i) - floatingResizeStartY)) / density)
                    .coerceIn(FLT_MIN_H_DP, FLT_MAX_H_DP)
                prefs.edit().putFloat(PREF_FLT_W, wDp).putFloat(PREF_FLT_H, hDp).apply()
                computeLayout()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == floatingResizePointer) {
                    floatingResizePointer = INVALID_POINTER_ID
                }
            }
            MotionEvent.ACTION_CANCEL -> floatingResizePointer = INVALID_POINTER_ID
        }
    }

    /** 拖动悬浮触控板位置（把手条） */
    private fun handleFloatingDrag(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(floatingDragPointer)
                if (i < 0) return
                val nx = (e.getX(i) - floatingDragOffX)
                    .coerceIn(pad + sideInset, width - padBodyRect.width() - pad - sideInset)
                val ny = (e.getY(i) - floatingDragOffY)
                    .coerceIn(stripH + pad, height - padBodyRect.height() - pad)
                if (nx != padBodyRect.left || ny != padBodyRect.top) {
                    val dx = nx - padBodyRect.left
                    val dy = ny - padBodyRect.top
                    padBodyRect.offsetTo(nx, ny)
                    // 把手条始终固定在触控板本体正上方（修复拖动时把手条错位）
                    padHandleRect.offsetTo(nx, ny - dp(HANDLE_H_DP) - gap)
                    floatingGripRect.offset(dx, dy)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == floatingDragPointer) {
                    floatingDragPointer = INVALID_POINTER_ID
                    prefs.edit()
                        .putFloat(PREF_FLT_X, padBodyRect.left)
                        .putFloat(PREF_FLT_Y, padBodyRect.top - dp(HANDLE_H_DP) - gap)
                        .apply()
                }
            }
            MotionEvent.ACTION_CANCEL -> floatingDragPointer = INVALID_POINTER_ID
        }
    }

    private fun onStripButton(id: Int) {
        haptic()
        when (id) {
            BTN_SHIFT -> {
                flashBtn(BTN_SHIFT)
                if (prefs.getString("btn_shift_combo", "shift") == "ctrl_space") {
                    // Ctrl+Space：部分输入法的中英切换热键（设置页可选，见 btn_shift_combo）
                    hub.tapCombo(Mods.LCTRL, Hid.SPACE)
                } else {
                    hub.tapMods(shiftPulseBits(Mods.LSHIFT))          // 单发 Shift：切中英文
                }
            }
            BTN_IME -> {
                flashBtn(BTN_IME)
                when (prefs.getString("btn_ime_combo", "ctrl_shift")) {
                    "alt_shift" -> hub.tapMods(Mods.LALT or shiftPulseBits(Mods.LSHIFT))   // Alt+Shift
                    "win_space" -> hub.tapCombo(Mods.LGUI, Hid.SPACE)                      // Win+Space
                    else -> hub.tapMods(Mods.LCTRL or shiftPulseBits(Mods.LSHIFT))  // Ctrl+Shift：切输入法
                }
            }
            BTN_PAD -> {
                padOn = !padOn
                prefs.edit().putBoolean(PREF_PAD_ENABLED, padOn).apply()
                releaseAll()
                computeLayout()
            }
            BTN_NUMPAD -> {
                numpadOn = !numpadOn
                prefs.edit().putBoolean(PREF_NUMPAD_ENABLED, numpadOn).apply()
                if (!numpadOn) {
                    // 收起时抬起按住的数字键，防止电脑端卡键
                    for (nk in numpadPointerKey.values) hub.keyUp(nk.cell.code)
                    numpadPointerKey.clear()
                }
                computeLayout()
            }
            BTN_COMBO -> openComboPanel()
            BTN_SOFT_KB -> toggleSoftKeyboard()
            BTN_PAD_TYPE -> {
                // 样式循环：全屏 → 悬浮 → 嵌入左 → 嵌入中 → 嵌入右
                padType = nextPadType(padType)
                prefs.edit().putString(PREF_PAD_TYPE, padType).apply()
                releaseAll()
                computeLayout()
            }
            BTN_TEXT_SCALE -> openTextScaleDialog()
            BTN_LAYOUT_SIZE -> openLayoutSizeDialog()
            BTN_LAYOUT -> {
                finishLayoutResize(save = true)
                if (!layoutEditMode) {
                    releaseAll()
                    beginLayoutEdit()
                }
                layoutEditMode = !layoutEditMode
                selectedKeyId = null
                computeLayout()
            }
            BTN_LAYOUT_RESET -> {
                finishLayoutResize(save = false)
                resetCustomKeyWidths()
                computeLayout()
            }
            BTN_ONEHAND -> {
                releaseAll()
                context.startActivity(android.content.Intent(context, OneHandActivity::class.java))
                // 切换后关闭当前页：返回键回到主界面，而不是回到上一个键盘布局
                (context as Activity).finish()
            }
            BTN_MODE -> {
                // 蓝牙/局域网 独立模式一键切换
                releaseAll()
                val next = if (hub.mode == InputHub.MODE_LAN) InputHub.MODE_BT else InputHub.MODE_LAN
                prefs.edit().putString("conn_mode", next).apply()
                (context.applicationContext as App).applyConnMode()
                computeLayout()   // 按钮文字换成当前模式
            }
            BTN_CHECK -> hub.checkConnections()
            BTN_EXIT -> if (layoutEditMode) cancelLayoutEdit() else (context as Activity).finish()
        }
        invalidate()
    }

    /** 状态条按钮点击反馈：高亮 300ms 后自动熄灭 */
    private fun flashBtn(id: Int) {
        flashBtnId = id
        postDelayed({
            if (flashBtnId == id) {
                flashBtnId = -1
                invalidate()
            }
        }, 300)
    }

    /** 长按标记的 pref key：记录该按钮是否已被长按过（长按过就换成底部横线标记） */
    private fun longPressPrefKey(btnId: Int): String = when (btnId) {
        BTN_SHIFT -> "long_press_shift"
        BTN_IME -> "long_press_ime"
        else -> "long_press_padtype"
    }

    /** 长按「中英」/「输入法」：底部白条选择该按钮发送的键值 */
    private fun openComboPicker(btnId: Int) {
        prefs.edit().putBoolean(longPressPrefKey(btnId), true).apply()
        if (btnId == BTN_SHIFT) {
            showComboBar(
                "「中英」按钮发送",
                "btn_shift_combo",
                listOf("单发 Shift" to "shift", "Ctrl+Space" to "ctrl_space"),
            )
        } else {
            showComboBar(
                "「输入法」按钮发送",
                "btn_ime_combo",
                listOf(
                    "Ctrl+Shift" to "ctrl_shift",
                    "Alt+Shift" to "alt_shift",
                    "Win+Space" to "win_space",
                ),
            )
        }
    }

    /** 底部白条：选择「中英/输入法」按钮发送的键值（选项上方小标题，点选项即保存） */
    private fun showComboBar(title: String, prefKey: String, options: List<Pair<String, String>>) {
        showBottomBar(options.map { it.first }) { which ->
            prefs.edit().putString(prefKey, options[which].second).apply()
            haptic()
            invalidate()
        }
    }

    /** 弹窗里的一行滑条：标题 / 实时文案 / 拖动预览（可选） */
    private class SliderSpec(
        val title: String,
        val bar: SeekBar,
        val labelOf: (Int) -> String,
        val live: ((Int) -> Unit)? = null,
        val onStop: (Int) -> Unit,
    )

    /** 通用滑条弹窗：标题 + 多组「名称 / 当前值 / 滑条」，拖动即时预览 */
    private fun showSlidersDialog(title: String, specs: List<SliderSpec>) {
        val act = context as? Activity ?: return
        PanelDialog.show(act, title) { root, dlg ->
            val labels = ArrayList<TextView>(specs.size)
            for (s in specs) {
                root.addView(TextView(act).apply {
                    text = s.title
                    textSize = 13f
                    setTextColor(Color.parseColor("#8A93A3"))
                })
                labels.add(TextView(act).apply {
                    textSize = 13f
                    setTextColor(Color.parseColor("#8A93A3"))
                }.also { root.addView(it) })
                root.addView(s.bar)
            }
            for ((i, s) in specs.withIndex()) {
                labels[i].text = s.labelOf(s.bar.progress)
                s.bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        labels[i].text = s.labelOf(p)
                        if (fromUser) s.live?.invoke(p)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        s.onStop(sb.progress)
                    }
                })
            }
            PanelDialog.accentButton(act, root, "完成") { dlg.dismiss() }
        }
    }

    /** 横屏快捷设置：键盘文字大小（0.7~1.6 倍，拖动即时预览） */
    private fun openTextScaleDialog() {
        val scale = textScale
        showSlidersDialog("键盘文字大小", listOf(SliderSpec(
            "文字大小（0.7 ~ 1.6 倍）：",
            SeekBar(context).apply {
                max = 90
                progress = ((scale.coerceIn(0.7f, 1.6f) - 0.7f) * 100f).toInt()
            },
            { p -> "当前：${"%.1f".format(p / 100f + 0.7f)} 倍" },
            live = { p -> saveTextScale(p) },
            onStop = { p -> saveTextScale(p) },
        )))
    }

    private fun saveTextScale(p: Int) {
        prefs.edit().putFloat("key_text_scale", (p / 100f + 0.7f).coerceIn(0.7f, 1.6f)).apply()
        invalidate()
    }

    /** 布局编辑模式：调整嵌入触控板宽度 / 鼠标键列宽度（拖动即时重排） */
    private fun openLayoutSizeDialog() {
        showSlidersDialog("嵌入布局尺寸", listOf(
            SliderSpec(
                "触控板宽度（占键盘区）：",
                SeekBar(context).apply {
                    max = 40
                    progress = prefs.getInt("embed_pad_width", 35).coerceIn(20, 60) - 20
                },
                { p -> "当前：${p + 20}%（越大键盘越窄）" },
                live = { p ->
                    prefs.edit().putInt("embed_pad_width", (p + 20).coerceIn(20, 60)).apply()
                    computeLayout(); invalidate()
                },
                onStop = { p ->
                    prefs.edit().putInt("embed_pad_width", (p + 20).coerceIn(20, 60)).apply()
                    computeLayout(); invalidate()
                },
            ),
            SliderSpec(
                "鼠标键列宽度：",
                SeekBar(context).apply {
                    max = 36
                    progress = prefs.getInt("embed_mouse_col", 52).coerceIn(36, 72) - 36
                },
                { p -> "当前：${p + 36} dp" },
                live = { p ->
                    prefs.edit().putInt("embed_mouse_col", (p + 36).coerceIn(36, 72)).apply()
                    computeLayout(); invalidate()
                },
                onStop = { p ->
                    prefs.edit().putInt("embed_mouse_col", (p + 36).coerceIn(36, 72)).apply()
                    computeLayout(); invalidate()
                },
            ),
        ))
    }

    /** 长按「触控板模式」：底部白条直接选择（全屏/悬浮/嵌左/嵌中/嵌右） */
    private fun openPadTypePicker() {
        prefs.edit().putBoolean(longPressPrefKey(BTN_PAD_TYPE), true).apply()
        val labels = arrayOf("全屏", "悬浮", "嵌左", "嵌中", "嵌右")
        val values = arrayOf(PAD_FULL, PAD_FLOATING, PAD_EMBED_LEFT, PAD_EMBED_MID, PAD_EMBED_RIGHT)
        showBottomBar(labels.toList()) { i ->
            if (i < values.size && values[i] != padType) {
                padType = values[i]
                prefs.edit().putString(PREF_PAD_TYPE, padType).apply()
                releaseAll()
                computeLayout()
                invalidate()
            }
            haptic()
        }
    }

    // ---------- 底部小白条选择条 ----------

    /** 显示底部白条：横排选项，点选后执行 onPick 并收起；点条外收起 */
    private fun showBottomBar(labels: List<String>, onPick: (Int) -> Unit) {
        bottomBarLabels = labels
        bottomBarPick = onPick
        bottomBarRects = emptyList()
        bottomBarRect = RectF()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val barH = dp(44f)
        val barTop = h - barH - dp(10f)
        val padB = dp(8f)
        val gap = dp(8f)
        val n = labels.size
        val avail = w - padB * 2 - gap * (n - 1)
        val chipW = avail / n
        bottomBarRect = RectF(padB, barTop, w - padB, barTop + barH)
        val rects = ArrayList<RectF>(n)
        var x = padB
        for (i in 0 until n) {
            rects.add(RectF(x, barTop, x + chipW, barTop + barH))
            x += chipW + gap
        }
        bottomBarRects = rects
        invalidate()
    }

    private fun hideBottomBar() {
        if (bottomBarRects.isEmpty()) return
        bottomBarLabels = emptyList()
        bottomBarRects = emptyList()
        bottomBarPick = null
        invalidate()
    }

    /** 绘制底部白条（最上层） */
    private fun drawBottomBar(canvas: Canvas) {
        if (bottomBarRects.isEmpty()) return
        paintFill.color = Color.parseColor("#F2F4F8")
        canvas.drawRoundRect(bottomBarRect, radius, radius, paintFill)
        paintText.color = Color.parseColor("#1C232D")
        paintText.textSize = dp(13f)
        for ((i, r) in bottomBarRects.withIndex()) {
            val fm = paintText.fontMetrics
            canvas.drawText(
                bottomBarLabels.getOrNull(i) ?: "",
                r.centerX(),
                r.centerY() - (fm.ascent + fm.descent) / 2f,
                paintText,
            )
            if (i > 0) {
                paintStroke.color = Color.parseColor("#C0C6D0")
                paintStroke.strokeWidth = dp(1f)
                canvas.drawLine(r.left, r.top + dp(6f), r.left, r.bottom - dp(6f), paintStroke)
            }
        }
    }

    // ---------- 组合键面板（预设 + 自定义按钮，垂直滚动 + 自适应宽度） ----------

    /** 组合键面板：预设 + 自定义组合按钮流式排列（宽度自适应）；点一下即发；＋ 新增；长按自定义可删 */
    private fun openComboPanel() {
        val act = context as? Activity ?: return
        val customs = loadCustomCombos()
        PanelDialog.show(act, "组合键") { root, dlg ->
            val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
            val flow = FlowLayout(act)
            scroll.addView(flow)
            val size = Point()
            act.windowManager.defaultDisplay.getRealSize(size)
            root.addView(
                scroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (size.y * 0.55f).toInt()),
            )

            /** 一个自适应宽度的组合键按钮（chip） */
            fun chip(label: String, onClick: () -> Unit, onLong: (() -> Unit)? = null, accent: Boolean = false) {
                flow.addView(TextView(act).apply {
                    text = label
                    textSize = 14f
                    typeface = if (accent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (accent) Color.parseColor("#0E1116") else Color.parseColor("#E6EAF0"))
                    gravity = Gravity.CENTER
                    setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(9f)
                        setColor(if (accent) colorAccent else Color.parseColor("#1C232D"))
                    }
                    setOnClickListener {
                        haptic()
                        onClick()
                    }
                    if (onLong != null) {
                        setOnLongClickListener {
                            haptic()
                            onLong()
                            true
                        }
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, PanelDialog.dp(act, 46f)
                ))
            }

            // 预设组合键
            for ((label, combo) in PRESET_COMBOS) {
                chip(label, onClick = { hub.tapCombo(combo.first, combo.second) })
            }
            // 自定义组合键（长按 = 删除确认）
            for ((i, cmb) in customs.withIndex()) {
                val c = cmb
                chip(
                    comboLabel(c.mods, c.codes),
                    onClick = {
                        if (c.codes.size == 1) hub.tapCombo(c.mods, c.codes[0])
                        else hub.tapComboMulti(c.mods, c.codes)
                    },
                    onLong = {
                        PanelDialog.show(act, "删除组合键") { r2, d2 ->
                            r2.addView(TextView(act).apply {
                                text = "删除「${comboLabel(c.mods, c.codes)}」？"
                                textSize = 14f
                                setTextColor(Color.parseColor("#E6EAF0"))
                                setPadding(0, dp(4f).toInt(), 0, dp(10f).toInt())
                            })
                            PanelDialog.accentButton(act, r2, "删除") {
                                saveCustomCombos(customs.toMutableList().apply { removeAt(i) })
                                d2.dismiss()
                                dlg.dismiss()
                                openComboPanel()
                            }
                        }
                    },
                )
            }
            // 新增按钮
            chip("＋ 新增", accent = true, onClick = { openComboConfig(dlg) })
            // 底部一行小提示（不占列表空间）
            root.addView(TextView(act).apply {
                text = "长按自定义组合键可删除"
                textSize = 11f
                setTextColor(Color.parseColor("#8A93A3"))
                setPadding(0, dp(4f).toInt(), 0, 0)
            })
        }
    }

    /** 新增组合键配置弹窗：勾选修饰键 + 选主键（竖屏式下拉，主键数量不限）→ 保存为按钮 */
    private fun openComboConfig(parent: Dialog?) {
        val act = context as? Activity ?: return
        PanelDialog.show(act, "新增组合键") { box, dlg ->
            // 修饰键（可多选）
            val modChecks = LinkedHashMap<Int, CheckBox>()
            val modRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((name, bit) in PRESET_MODS) {
                modRow.addView(CheckBox(act).apply {
                    text = name
                    textSize = 14f
                    setTextColor(Color.parseColor("#E6EAF0"))
                    modChecks[bit] = this
                })
            }
            box.addView(modRow)

            // 主键（可多个，同时按下）：下拉选择 + 行内 ✕ 删除；底部「＋ 添加主键」
            val options = ShortcutStore.CHOOSABLE_KEYS + NUMPAD_EXTRA_KEYS
            val spinners = ArrayList<Spinner>()
            val keyRows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(act).apply {
                text = "主键（可多个，同时按下）："
                textSize = 13f
                setTextColor(Color.parseColor("#8A93A3"))
                setPadding(0, dp(8f).toInt(), 0, dp(4f).toInt())
            })
            box.addView(keyRows)

            val preview = TextView(act).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                setPadding(0, dp(6f).toInt(), 0, 0)
            }
            box.addView(preview)
            fun modsBits(): Int = PRESET_MODS.fold(0) { acc, (_, bit) ->
                if (modChecks[bit]?.isChecked == true) acc or bit else acc
            }
            fun refreshPreview() {
                val codes = spinners.map { options[it.selectedItemPosition].second }.distinct()
                preview.text = "预览：${comboLabel(modsBits(), codes)}"
            }

            fun addKeyRow(initialCode: Int = -1) {
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                val spinner = Spinner(act).apply {
                    adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, options.map { it.first })
                    if (initialCode >= 0) {
                        setSelection(options.indexOfFirst { it.second == initialCode }.coerceAtLeast(0))
                    }
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) =
                            refreshPreview()

                        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
                    }
                }
                row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val remove = TextView(act).apply {
                    text = "✕"
                    textSize = 15f
                    includeFontPadding = false
                    setTextColor(Color.parseColor("#8A93A3"))
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (spinners.size > 1) {
                            spinners.remove(spinner)
                            keyRows.removeView(row)
                            refreshPreview()
                        }
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(PanelDialog.dp(act, 36f), PanelDialog.dp(act, 40f)).also {
                    it.leftMargin = dp(6f).toInt()
                })
                keyRows.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(6f).toInt() })
                spinners.add(spinner)
            }
            addKeyRow()

            // ＋ 添加主键
            box.addView(TextView(act).apply {
                text = "＋ 添加主键"
                textSize = 13f
                setTextColor(colorAccent)
                gravity = Gravity.CENTER
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
                setOnClickListener {
                    haptic()
                    addKeyRow()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // 修饰键变化刷新预览
            for (cb in modChecks.values) {
                cb.setOnCheckedChangeListener { _, _ -> refreshPreview() }
            }
            refreshPreview()

            PanelDialog.accentButton(act, box, "保存") {
                // 去重：同一主键只保留一个（防止 A+A+A）
                val codes = spinners.map { options[it.selectedItemPosition].second }.distinct().filter { it > 0 }
                if (codes.isNotEmpty()) {
                    val next = loadCustomCombos().toMutableList()
                        .apply { add(ComboDef(modsBits(), codes)) }
                    saveCustomCombos(next)
                }
                dlg.dismiss()
                parent?.dismiss()
                openComboPanel()
            }
        }
    }

    private fun loadCustomCombos(): MutableList<ComboDef> {
        val raw = prefs.getString("combo_customs", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<ComboDef>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optInt("m", 0)
                val codes = mutableListOf<Int>()
                val ks = o.optJSONArray("ks")
                if (ks != null) {
                    for (j in 0 until ks.length()) codes.add(ks.optInt(j, 0))
                } else {
                    val c = o.optInt("c", 0)   // 兼容旧版单键存储
                    if (c > 0) codes.add(c)
                }
                codes.removeAll { it <= 0 }
                if (codes.isNotEmpty()) out.add(ComboDef(m, codes))
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveCustomCombos(list: List<ComboDef>) {
        val arr = JSONArray()
        for (c in list) {
            val ks = JSONArray()
            for (code in c.codes) ks.put(code)
            arr.put(JSONObject().put("m", c.mods).put("ks", ks))
        }
        prefs.edit().putString("combo_customs", arr.toString()).apply()
    }

    /** 组合按钮显示标签：如 "Ctrl+Shift+T"、"Win+D+E" */
    private fun comboLabel(mods: Int, codes: List<Int>): String {
        val sb = StringBuilder()
        for ((name, bit) in PRESET_MODS) {
            if (mods and bit != 0) {
                if (sb.isNotEmpty()) sb.append('+')
                sb.append(name)
            }
        }
        for (code in codes) {
            if (sb.isNotEmpty()) sb.append('+')
            sb.append(comboKeyLabel(code))
        }
        return sb.toString()
    }

    /** 键码 → 显示标签：优先 68 配列键帽，其次功能键/小键盘扩展 */
    private fun comboKeyLabel(code: Int): String {
        if (code == Hid.SPACE) return "Space"   // 68 配列空格键帽是空串，显示为 Space
        for (row in Layout68.rows) {
            for (k in row) if (k.code == code) return k.label
        }
        NUMPAD_EXTRA_KEYS.firstOrNull { it.second == code }?.let { return it.first }
        (1..12).firstOrNull { Hid.F1 + it - 1 == code }?.let { return "F$it" }
        ShortcutStore.CHOOSABLE_KEYS.firstOrNull { it.second == code }?.let { return it.first }
        return "键$code"
    }

    // ---------- 软键盘（无输入框，Moonlight 式） ----------
    // 顶部「软键盘」按钮 = 开关：唤起系统输入法，输入的中文/符号以字符串逐字发到电脑
    // （只走局域网通道，Agent 用 SendInput UNICODE 直接打出，不经过 HID 键码）

    private fun toggleSoftKeyboard() {
        if (hub.mode != InputHub.MODE_LAN) {
            Toast.makeText(context, "软键盘文本输入需局域网模式（蓝牙 HID 无法发送中文）", Toast.LENGTH_SHORT).show()
            return
        }
        if (softKbOn) closeSoftKeyboard() else openSoftKeyboard()
    }

    private fun openSoftKeyboard() {
        val et = ensureSoftEdit() ?: return
        softLastText = et.text.toString()
        softKbOn = true
        et.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        computeLayout()
        invalidate()
    }

    private fun closeSoftKeyboard() {
        val et = softEdit
        if (et != null) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
        if (softKbOn) {
            softKbOn = false
            computeLayout()
            invalidate()
        }
    }

    /** 懒创建隐藏在 Activity 内容层里的捕获框：1×1 全透明，只承接输入法文本 */
    private fun ensureSoftEdit(): EditText? {
        softEdit?.let { return it }
        val act = context as? Activity ?: return null
        val host = act.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val et = EditText(act).apply {
            alpha = 0f
            textSize = 1f
            setTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            isFocusableInTouchMode = true
            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = ViewGroup.LayoutParams(1, 1)
        }
        // 外部收起输入法（如返回键）时同步按钮高亮状态
        et.setOnFocusChangeListener { _, has ->
            if (!has && softKbOn) {
                softKbOn = false
                invalidate()
            }
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!softKbOn) {
                    softLastText = ""
                    return
                }
                val ed = s ?: return
                // 拼音等未确认的合成区不算已输入，不发送
                val comp = composingRange(ed)
                val committed = if (comp == null) ed.toString()
                else ed.substring(0, comp.first) + ed.substring(comp.last + 1)
                val old = softLastText
                // 公共前缀后的差异：删除 → 退格；新增 → 字符文本
                var p = 0
                while (p < old.length && p < committed.length && old[p] == committed[p]) p++
                val removed = old.length - p
                val added = committed.substring(p)
                if (removed > 0) sendBackspaces(removed)
                if (added.isNotEmpty()) sendTextChunk(added)
                softLastText = committed
            }
        })
        host.addView(et)
        softEdit = et
        return et
    }

    /** 合成区（正在输入的拼音/候选词）范围；无合成返回 null */
    private fun composingRange(ed: Editable): IntRange? {
        var start = Int.MAX_VALUE
        var end = -1
        for (span in ed.getSpans(0, ed.length, Any::class.java)) {
            if ((ed.getSpanFlags(span) and Spanned.SPAN_COMPOSING) != 0) {
                start = minOf(start, ed.getSpanStart(span))
                end = maxOf(end, ed.getSpanEnd(span))
            }
        }
        return if (end < 0) null else start until end
    }

    /** 整段文本发送：换行拆成回车键，其余走 txt 协议 */
    private fun sendTextChunk(text: String) {
        if (text.isEmpty()) return
        val sb = StringBuilder()
        for (c in text) {
            if (c == '\n') {
                if (sb.isNotEmpty()) {
                    hub.sendText(sb.toString())
                    sb.clear()
                }
                hub.keyDown(Hid.ENTER, 0)
                hub.keyUp(Hid.ENTER)
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) hub.sendText(sb.toString())
    }

    private fun sendBackspaces(n: Int) {
        for (i in 0 until n) {
            hub.keyDown(Hid.BKSP, 0)
            hub.keyUp(Hid.BKSP)
        }
    }

    // ---------- 键宽编辑 ----------

    private fun handleLayoutResize(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = e.actionIndex
                val keyRect = keyAt(e.getX(i), e.getY(i)) ?: return
                resizePointerId = e.getPointerId(i)
                resizingKeyId = keyRect.prefKey
                selectedKeyId = keyRect.prefKey
                resizeStartX = e.getX(i)
                resizeStartScale = keyWidthScale(keyRect.prefKey)
                haptic()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(resizePointerId)
                if (i < 0) return
                val id = resizingKeyId ?: return
                val keyRect = rects.firstOrNull { it.prefKey == id } ?: return
                val dragUnits = (e.getX(i) - resizeStartX) / (unitW * keyRect.key.width.coerceAtLeast(1f))
                val scale = (resizeStartScale + dragUnits).coerceIn(MIN_KEY_WIDTH_SCALE, MAX_KEY_WIDTH_SCALE)
                if (kotlin.math.abs(scale - keyWidthScale(id)) >= 0.005f) {
                    keyWidthScales[id] = scale
                    computeLayout()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> finishLayoutResize(save = true)
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == resizePointerId) finishLayoutResize(save = true)
            }
            MotionEvent.ACTION_CANCEL -> finishLayoutResize(save = true)
        }
    }

    private fun finishLayoutResize(save: Boolean) {
        val id = resizingKeyId
        if (save && id != null) {
            val scale = keyWidthScale(id)
            val edit = prefs.edit()
            if (kotlin.math.abs(scale - DEFAULT_KEY_WIDTH_SCALE) < 0.01f) {
                keyWidthScales.remove(id)
                edit.remove(id)
            } else {
                edit.putFloat(id, scale)
            }
            edit.apply()
        }
        resizePointerId = INVALID_POINTER_ID
        resizingKeyId = null
        invalidate()
    }

    private fun resetCustomKeyWidths() {
        val edit = prefs.edit()
        for ((row, keys) in Layout68.rows.withIndex()) {
            for (column in keys.indices) edit.remove(widthPrefKey(row, column))
        }
        edit.apply()
        keyWidthScales.clear()
        selectedKeyId = null
    }

    /** 进入布局编辑前快照当前键宽，供「取消」回滚 */
    private fun beginLayoutEdit() {
        editStartScales.clear()
        for ((k, v) in prefs.all) {
            if (k.startsWith(KEY_WIDTH_PREF_PREFIX) && v is Float) editStartScales[k] = v
        }
    }

    /** 取消布局编辑：回滚到进入编辑态前的键宽并退出编辑（区别于「完成」保留修改） */
    private fun cancelLayoutEdit() {
        finishLayoutResize(save = false)
        val edit = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith(KEY_WIDTH_PREF_PREFIX)) edit.remove(k)
        for ((k, v) in editStartScales) edit.putFloat(k, v)
        edit.apply()
        keyWidthScales.clear()
        layoutEditMode = false
        selectedKeyId = null
        computeLayout()
        invalidate()
    }

    private fun keyWidthScale(prefKey: String): Float = keyWidthScales.getOrPut(prefKey) {
        prefs.getFloat(prefKey, DEFAULT_KEY_WIDTH_SCALE).coerceIn(MIN_KEY_WIDTH_SCALE, MAX_KEY_WIDTH_SCALE)
    }

    private fun widthPrefKey(row: Int, column: Int) = "$KEY_WIDTH_PREF_PREFIX${row}_$column"

    private fun keyName(key: Key): String =
        (if (key.isModifier) modLabel(key) else key.label).ifEmpty { "空格" }

    // ---------- 键盘触控 ----------

    private fun press(pid: Int, x: Float, y: Float) {
        val r = keyAt(x, y) ?: return
        pointerKeys[pid] = r
        haptic()
        val k = r.key
        when {
            k.isModifier && (modHold || modBit(k) == Mods.LGUI) -> {
                val mb = modBit(k)
                pointerHeldMods[pid] = mb
                hub.modDown(mb)
            }
            k.isModifier -> {
                val isShift = k.modBit == Mods.LSHIFT || k.modBit == Mods.RSHIFT
                // Shift 与 Fn 互斥：按下 Shift 时关闭 Fn 层
                if (isShift) fnLatched = false
                when {
                    // 已锁定的 Shift：点一下解锁
                    isShift && (lockedBits and k.modBit) != 0 -> {
                        cancelPendingChord(clearBits = true)
                        lockedBits = lockedBits and k.modBit.inv()
                        hub.modUp(k.modBit)
                    }
                    // 双击 Shift（280ms 内）= 持续锁定；锁住期间键帽下缘亮橙条
                    isShift && latchedMods.contains(k) &&
                        SystemClock.uptimeMillis() - lastShiftLatchAt < DOUBLE_TAP_LOCK_MS -> {
                        cancelPendingChord(clearBits = true)
                        latchedMods.remove(k)
                        lockedBits = lockedBits or k.modBit
                        hub.modDown(k.modBit)
                    }
                    latchedMods.contains(k) -> {
                        // 再点一次已锁定的修饰键 = 单独发送（Shift 单点切中英文）
                        cancelPendingChord(clearBits = true)
                        latchedMods.remove(k)
                        hub.tapMods(modBit(k))
                    }
                    pendingChordBits != 0 -> {
                        // 组合键里再加一个修饰键，重新计时
                        pendingChordBits = pendingChordBits or modBit(k)
                        scheduleChord()
                    }
                    latchedMods.isNotEmpty() -> {
                        // 已有锁定修饰键时点新修饰键 = 延迟组组合键（Ctrl+Shift 切输入法）
                        pendingChordBits = latchedBits() or modBit(k)
                        scheduleChord()
                    }
                    else -> {
                        latchedMods.add(k)
                        if (isShift) lastShiftLatchAt = SystemClock.uptimeMillis()
                    }
                }
            }
            k.code == -1 -> {
                // Shift 与 Fn 互斥：开启 Fn 层时取消锁存/锁定的 Shift
                val shiftBits = Mods.LSHIFT or Mods.RSHIFT
                if (lockedBits and shiftBits != 0) {
                    val bits = lockedBits and shiftBits
                    lockedBits = lockedBits and bits.inv()
                    hub.modUp(bits)   // 锁定期间电脑端 Shift 真实按下，需抬起
                }
                latchedMods.removeAll { it.modBit and shiftBits != 0 }
                fnLatched = !fnLatched
                if (fnLatched) fnHeldUsed = false
            }
            else -> {
                val chordBits = pendingChordBits
                cancelPendingChord(clearBits = false)
                pendingChordBits = 0
                val code = if (fnLatched && k.fnLabel != null) k.fnCode else k.code
                pointerSent[pid] = code
                hub.keyDown(code, latchedBits() or chordBits or k.autoMods)
            }
        }
        invalidate()
    }

    private fun release(pid: Int) {
        val r = pointerKeys.remove(pid) ?: return
        val k = r.key
        when {
            k.isModifier && (modHold || modBit(k) == Mods.LGUI) ->
                pointerHeldMods.remove(pid)?.let { hub.modUp(it) }
            k.isModifier -> {
                // 锁存型修饰键自身抬起：
                //  - 组合已发生（该位随按键真实发下）→ 现在放开 = 提交
                //    （长按 Alt 连点 Tab 循环切窗：抬手那一刻电脑端 Alt 才抬起、窗口切换）
                //  - 纯点按锁存（尚未发过键）→ 保持锁存，等下一个普通键来消费
                val mb = modBit(k)
                if (mb and realLatchBits != 0) {
                    realLatchBits = realLatchBits and mb.inv()
                    hub.modUp(mb)
                    latchedMods.remove(k)
                }
            }
            k.code == -1 -> {
                // Fn 手指抬起：点按锁定保持（等下一个普通键消费）；
                // 按住期间打过字则视为按住使用，抬指即结束 Fn 层
                if (fnHeldUsed) {
                    fnHeldUsed = false
                    fnLatched = false
                }
            }
            // 普通键释放 = 消费锁存；但修饰键手指仍按着的位等同按住，跨键保持
            else -> {
                val keepBits = fingerHeldLatchBits()
                pointerSent.remove(pid)?.let { hub.keyUp(it, keepBits) }
                realLatchBits = keepBits
                latchedMods.removeAll { modBit(it) and keepBits == 0 }
                if (fnFingerDown()) fnHeldUsed = true else fnLatched = false
            }
        }
        invalidate()
    }

    private fun latchedBits(): Int = latchedMods.fold(0) { acc, k -> acc or modBit(k) }

    /** 锁存的修饰键中，手指仍按在屏上的位（这类锁存等同按住：组合跨多次按键保持） */
    private fun fingerHeldLatchBits(): Int {
        var bits = 0
        for (r in pointerKeys.values) {
            val k = r.key
            if (k.isModifier && k in latchedMods) bits = bits or modBit(k)
        }
        return bits
    }

    private fun fnFingerDown(): Boolean = pointerKeys.values.any { it.key.code == -1 }

    /**
     * 条上按钮的 Shift 脉冲位：LSHIFT 被双击锁定时（真实按下中）换用 RSHIFT，
     * 否则 tapMods 的 70ms 抬起会把大写锁定悄悄解除。
     */
    private fun shiftPulseBits(preferred: Int): Int =
        if (preferred == Mods.LSHIFT && (lockedBits and Mods.LSHIFT) != 0) Mods.RSHIFT else preferred

    /**
     * 命中判定：先精确匹配键帽矩形（原区域行为 100% 不变）；
     * 落进键间死区时按就近归属（消除"点到缝隙没反应"，视觉外观零改动）。
     */
    private fun keyAt(x: Float, y: Float): KeyRect? {
        rects.firstOrNull { it.contains(x, y) }?.let { return it }
        var best: KeyRect? = null
        var bestDist = Float.MAX_VALUE
        for (r in rects) {
            val d = distToRect(r.rect, x, y)
            if (d < bestDist) {
                bestDist = d
                best = r
            }
        }
        return if (bestDist <= dp(HIT_SLOP_DP)) best else null
    }

    private fun distToRect(r: RectF, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun scheduleChord() {
        pendingChordAction?.let { removeCallbacks(it) }
        val action = Runnable {
            val bits = pendingChordBits
            pendingChordBits = 0
            pendingChordAction = null
            if (bits != 0) hub.tapMods(bits)
            // 脉冲会把已按住型锁存位一起抬起，同步真实位标记
            realLatchBits = realLatchBits and bits.inv()
            latchedMods.clear()
            invalidate()
        }
        pendingChordAction = action
        postDelayed(action, CHORD_DELAY_MS)
    }

    /** 取消待发送的组合键；clearBits=false 时保留 pendingChordBits 供打字消费 */
    private fun cancelPendingChord(clearBits: Boolean) {
        pendingChordAction?.let { removeCallbacks(it) }
        pendingChordAction = null
        if (clearBits) pendingChordBits = 0
    }

    /** 释放全部按键（退后台、取消触控、切换模式时调用），防止电脑端卡键 */
    fun releaseAll() {
        finishLayoutResize(save = true)
        cancelPendingChord(clearBits = true)
        for (pid in pointerKeys.keys.toList()) release(pid)
        pointerKeys.clear()
        pointerSent.clear()
        for (bit in pointerHeldMods.values) hub.modUp(bit)
        pointerHeldMods.clear()
        latchedMods.clear()
        realLatchBits = 0
        lockedBits = 0
        fnLatched = false
        fnHeldUsed = false
        floatingDragPointer = INVALID_POINTER_ID
        numpadDragPointer = INVALID_POINTER_ID
        numpadResizePointer = INVALID_POINTER_ID
        closeSoftKeyboard()
        // 释放鼠标键列按住的状态（hub.releaseAll 会补发鼠标全键抬起）
        heldMouseButton = 0
        heldMousePointer = INVALID_POINTER_ID
        tp.externalButton = 0
        hideBottomBar()
        for (nk in numpadPointerKey.values) hub.keyUp(nk.cell.code)
        numpadPointerKey.clear()
        hub.releaseAll()
        // 触摸板残余状态清空；拖动/点击中的鼠标键一并抬起
        tp.cancelAll(sendMouseUp = true)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 分离后 postDelayed 的任务不再有意义，惯性/拖动/文本队列全部终止
        batteryHandler.removeCallbacks(batteryTask)
        closeSoftKeyboard()
        tp.cancelAll(sendMouseUp = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBattery()
        batteryHandler.postDelayed(batteryTask, BATTERY_REFRESH_MS)
        // 首次进入：长按功能引导教学（只弹一次）
        post {
            showLongPressTeach()
        }
    }

    /** 首次进入弹一次「长按」教学（中英/输入法/触控板模式 支持长按） */
    private fun showLongPressTeach() {
        val act = context as? Activity ?: return
        if (prefs.getBoolean("strip_long_press_taught", false)) return
        prefs.edit().putBoolean("strip_long_press_taught", true).apply()
        PanelDialog.show(act, "长按小技巧") { root, dlg ->
            root.addView(TextView(act).apply {
                text = "带橙色小圆点的按钮支持长按：\n" +
                    "· 长按「中英」→ 选择单发 Shift 或 Ctrl+Space\n" +
                    "· 长按「输入法」→ 选择切输入法组合键\n" +
                    "· 长按「触控板模式」→ 直接选 全屏/悬浮/嵌左/嵌中/嵌右\n\n" +
                    "普通按钮点按即用，长按有更多功能。"
                textSize = 14f
                setTextColor(Color.parseColor("#E6EAF0"))
                setLineSpacing(0f, 1.3f)
                setPadding(0, dp(4f).toInt(), 0, dp(8f).toInt())
            })
            PanelDialog.accentButton(act, root, "知道了") { dlg.dismiss() }
        }
    }

    /** 读取本设备电量（电池无此属性时保持默认 100） */
    private fun updateBattery() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        invalidate()
    }

    // ---------- 触控板（手势全部委托给 TouchpadEngine） ----------

    private fun handlePadEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                tp.onPointerDown(
                    e.getPointerId(i),
                    e.getX(i),
                    e.getY(i),
                    inScrollStrip = e.getX(i) >= padBodyRect.right - dp(34f),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    tp.onPointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                tp.onPointerUp(e.getPointerId(e.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                tp.cancelAll(sendMouseUp = true)
            }
        }
    }

    // ---------- 通用 ----------

    private fun padTypeLabel(): String = when (padType) {
        PAD_FULL -> "全屏"
        PAD_FLOATING -> "悬浮"
        PAD_EMBED_LEFT -> "嵌左"
        PAD_EMBED_MID -> "嵌中"
        else -> "嵌右"
    }

    private fun nextPadType(cur: String): String = when (cur) {
        PAD_FULL -> PAD_FLOATING
        PAD_FLOATING -> PAD_EMBED_LEFT
        PAD_EMBED_LEFT -> PAD_EMBED_MID
        PAD_EMBED_MID -> PAD_EMBED_RIGHT
        else -> PAD_FULL
    }

    fun refreshStatus() {
        invalidate()
    }

    /** 可调振幅的短震（设置页调节，0 = 关闭） */
    private fun haptic() {
        if (hapticAmp <= 0) return
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(15, hapticAmp.coerceIn(1, 255)))
        } catch (_: Exception) {
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val CHORD_DELAY_MS = 300L
        private const val DOUBLE_TAP_LOCK_MS = 280L
        private const val HIT_SLOP_DP = 3f   // 死区就近归属半径（间隙 4dp 时 3dp 恰好完全覆盖）
        private const val ROW_WIDTH_UNITS = 16f
        private const val DEFAULT_KEY_WIDTH_SCALE = 1f
        private const val MIN_KEY_WIDTH_SCALE = 0.6f
        private const val MAX_KEY_WIDTH_SCALE = 2.5f
        private const val KEY_WIDTH_PREF_PREFIX = "key_width_"
        private const val INVALID_POINTER_ID = -1
        private const val STRIP_LONG_PRESS_MS = 500L   // 状态条按钮长按判定
        private const val BATTERY_REFRESH_MS = 60_000L // 电量刷新间隔
        private const val BTN_SHIFT = 1
        private const val BTN_IME = 2
        private const val BTN_PAD = 3
        private const val BTN_EXIT = 4
        private const val BTN_ONEHAND = 5
        private const val BTN_CHECK = 6
        private const val BTN_MODE = 7
        private const val BTN_LAYOUT = 8
        private const val BTN_LAYOUT_RESET = 9
        private const val BTN_PAD_TYPE = 10
        private const val BTN_TEXT_SCALE = 11
        private const val BTN_LAYOUT_SIZE = 12
        private const val BTN_NUMPAD = 13
        private const val BTN_COMBO = 14
        private const val BTN_SOFT_KB = 15

        // 数字小键盘
        private const val PREF_NUMPAD_ENABLED = "numpad_enabled"
        private const val PREF_NUMPAD_X = "numpad_x"
        private const val PREF_NUMPAD_Y = "numpad_y"
        private const val PREF_NUMPAD_CELL = "numpad_cell"   // 键格边长 dp（右下角把手拖拽调整）
        private const val NP_CELL_DP = 30f      // 键格边长默认值
        private const val NP_CELL_MIN_DP = 18f  // 缩放下限
        private const val NP_CELL_MAX_DP = 46f  // 缩放上限
        private const val NP_GAP_DP = 3f        // 键间缝隙
        private const val NP_PAD_DP = 8f        // 面板内边距
        private const val NP_HANDLE_H_DP = 16f  // 把手条高
        private const val NP_GRIP_DP = 22f      // 右下角缩放把手边长

        /** 预设组合键：标签 → (修饰键位, 键码) */
        private val PRESET_COMBOS = listOf(
            "Ctrl+C" to (Mods.LCTRL to Hid.C),
            "Ctrl+V" to (Mods.LCTRL to Hid.V),
            "Ctrl+X" to (Mods.LCTRL to Hid.X),
            "Ctrl+Z" to (Mods.LCTRL to Hid.Z),
            "Ctrl+A" to (Mods.LCTRL to Hid.A),
            "Ctrl+S" to (Mods.LCTRL to Hid.S),
            "Alt+Tab" to (Mods.LALT to Hid.TAB),
            "Alt+F4" to (Mods.LALT to Hid.F4),
            "Ctrl+Alt+Del" to (Mods.LCTRL or Mods.LALT to Hid.DELETE),
            "Ctrl+Shift+Esc" to (Mods.LCTRL or Mods.LSHIFT to Hid.ESC),
            "Win+D" to (Mods.LGUI to Hid.D),
            "Win+E" to (Mods.LGUI to Hid.E),
        )

        /** 自定义组合可选修饰键：标签 → 位掩码 */
        private val PRESET_MODS = listOf(
            "Ctrl" to Mods.LCTRL,
            "Alt" to Mods.LALT,
            "Shift" to Mods.LSHIFT,
            "Win" to Mods.LGUI,
        )

        /** 新增组合键的可选扩展键：数字小键盘（标签 → 键码） */
        private val NUMPAD_EXTRA_KEYS = listOf(
            "NumLk" to Hid.NUM_LOCK, "/" to Hid.DIVIDE, "*" to Hid.MULTIPLY, "-" to Hid.SUBTRACT,
            "+" to Hid.ADD, "Ent" to Hid.KEYPAD_ENTER,
            "7" to Hid.NUMPAD_7, "8" to Hid.NUMPAD_8, "9" to Hid.NUMPAD_9,
            "4" to Hid.NUMPAD_4, "5" to Hid.NUMPAD_5, "6" to Hid.NUMPAD_6,
            "1" to Hid.NUMPAD_1, "2" to Hid.NUMPAD_2, "3" to Hid.NUMPAD_3,
            "0" to Hid.NUMPAD_0, "." to Hid.NUMPAD_DOT,
        )

        /** 数字小键盘 4×5 布局（标准电脑小键盘）：label / 键码 / 修饰键 / 跨列数 / 跨行数 / 右上角标注
         *  NumLk   /    *    -
         *  7 8 9   +（跨 2 行）     ← altLabel = NumLock 关闭时的常见操作（Home/↑/PgUp 等）
         *  4 5 6   +
         *  1 2 3   Ent（跨 2 行）
         *  0（跨 2 列） .  Ent
         */
        private val NUMPAD_LAYOUT = listOf(
            listOf(NpCell("Num", Hid.NUM_LOCK), NpCell("/", Hid.DIVIDE), NpCell("*", Hid.MULTIPLY), NpCell("-", Hid.SUBTRACT)),
            listOf(
                NpCell("7", Hid.NUMPAD_7, altLabel = "Home"), NpCell("8", Hid.NUMPAD_8, altLabel = "↑"),
                NpCell("9", Hid.NUMPAD_9, altLabel = "PgUp"), NpCell("+", Hid.ADD, rowSpan = 2),
            ),
            listOf(
                NpCell("4", Hid.NUMPAD_4, altLabel = "←"), NpCell("5", Hid.NUMPAD_5),
                NpCell("6", Hid.NUMPAD_6, altLabel = "→"),
            ),
            listOf(
                NpCell("1", Hid.NUMPAD_1, altLabel = "End"), NpCell("2", Hid.NUMPAD_2, altLabel = "↓"),
                NpCell("3", Hid.NUMPAD_3, altLabel = "PgDn"), NpCell("Ent", Hid.KEYPAD_ENTER, rowSpan = 2),
            ),
            listOf(
                NpCell("0", Hid.NUMPAD_0, span = 2, altLabel = "Ins"),
                NpCell(".", Hid.NUMPAD_DOT, altLabel = "Del"),
            ),
        )

        // 触控板形态
        private const val PREF_PAD_ENABLED = "pad_enabled"
        private const val PREF_PAD_TYPE = "pad_type"
        private const val PREF_FLT_X = "pad_flt_x"
        private const val PREF_FLT_Y = "pad_flt_y"
        private const val PREF_FLT_W = "pad_flt_w"   // 悬浮触控板宽（dp，右下角把手调整）
        private const val PREF_FLT_H = "pad_flt_h"   // 悬浮触控板高（dp）
        private const val FLT_GRIP_DP = 22f          // 右下角缩放把手边长
        private const val FLT_MIN_W_DP = 110f
        private const val FLT_MIN_H_DP = 120f
        private const val FLT_MAX_W_DP = 460f
        private const val FLT_MAX_H_DP = 520f
        private const val PAD_FULL = "full"
        private const val PAD_FLOATING = "floating"
        private const val PAD_EMBED_PREFIX = "embed"
        private const val PAD_EMBED_LEFT = "embedL"
        private const val PAD_EMBED_MID = "embedM"
        private const val PAD_EMBED_RIGHT = "embedR"

        // 触控板尺寸
        private const val FULL_COL_W_DP = 56f   // 全屏触控板左侧鼠标键列宽
        private const val MOUSE_COL_W_DP = 52f  // 嵌入模式左右鼠标键列宽
        private const val HANDLE_H_DP = 18f     // 悬浮触控板把手条高
        private const val EMBED_PAD_FRACTION = 0.35f  // 嵌入模式触控板占键盘区比例

        private val MOUSE_KEY_DEFS = listOf("左" to 1, "中" to 4, "右" to 2)
    }
}