package com.nightboard.keyboard68

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/**
 * 竖屏模式：
 *  ┌──────────────┐
 *  │   触 摸 板    │  单指移动 · 轻点=左键 · 双指=右键 · 双指滑动=滚轮
 *  ├──────────────┤
 *  │Ctrl AltShiftTabWinEsc│  固定修饰键行（点按锁定，单指组合）
 *  │ 快捷 快捷 快捷 …  │  6 个自定义快捷键槽（长按编辑任意组合键）
 *  │ ( ) { } [ ] ; ' ` \ = / │  编程常用符号行
 *  │ F1 F2 F3 … F12 │
 *  │ 1 2 3 4 5 6 7 8 9 0 │
 *  │ Q W E R T Y U I O P │
 *  │  A S D F G H J K L  │
 *  │ Z X C V B N M   ⌫   │
 *  │ ,     空格     . 回车 │
 *  └──────────────┘
 * 输入一律走 InputHub（蓝牙/局域网独立模式），与横屏 68 键完全共存。
 */
class OneHandView(context: Context, private val hub: InputHub) : View(context) {

    private class Cap(
        val key: Key?,
        val rect: RectF,
        val customSlot: Int = -1,
        val modRowSlot: Int = -1,   // 修饰键排（Ctrl/Alt/Tab/Win/Esc）槽位，长按可换位
    ) {
        var prefKey: String = ""
        var baseWidth: Float = rect.width()
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    private class StripBtn(val rect: RectF, val label: String, val id: Int) {
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    private val store = ShortcutStore(context)
    private var shortcuts: List<ShortcutStore.Shortcut?> = store.load()

    private val prefs = context.getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val hapticAmp = prefs.getInt("haptic_amp", 140)
    private val modHold = prefs.getBoolean("mod_hold", false)

    private var caps: List<Cap> = emptyList()
    private var stripButtons: List<StripBtn> = emptyList()
    private var padRect = RectF()
    private var unit = 0f
    private var keyH = 0f
    private var fH = 0f
    private var modH = 0f
    private var stripH = 0f
    private var pad = 0f
    private var gap = 0f
    private var radius = 0f

    // 触控状态
    private val pointerCaps = HashMap<Int, Cap>()
    private val pointerSent = HashMap<Int, Int>()
    private val pointerHeldMods = HashMap<Int, Int>()
    private val latchedMods = HashSet<Key>()
    /** 双击 Shift 后的持续锁定（替代 CapsLock：锁定期间字母全大写、符号全上档） */
    private var lockedBits = 0
    private var lastShiftLatchAt = 0L
    private val pointerCustom = HashMap<Int, Int>()       // 按在自定义槽上的手指
    private val customPendingRun = HashMap<Int, Runnable>() // 长判定任务
    private val customLongFired = HashSet<Int>()

    // 修饰键组合（Ctrl+Shift 切输入法）：延迟发送，期间打字则取消
    private var pendingChordBits = 0
    private var pendingChordAction: Runnable? = null

    // 触控板（只统计落在触摸板区域的手指；手势逻辑在 TouchpadEngine，与横屏共用）
    private val pointerInPad = HashSet<Int>()
    private val tp = TouchpadEngine(this, hub, prefs).apply {
        feedback = { haptic() }
    }

    // 修饰键排（Ctrl/Alt/Tab/Win/Esc）长按换位
    private val modRowLongRun = HashMap<Int, Runnable>()
    private val modRowLongFired = HashSet<Int>()
    private val modRowDeferredChord = HashMap<Int, Int>()   // 延迟发送的 Tab/Esc 携带的组合键

    // 竖屏键宽编辑：与横屏使用独立前缀，避免两套布局互相影响
    private var layoutEditMode = false
    private val keyWidthScales = HashMap<String, Float>()
    private var resizePointerId = INVALID_POINTER_ID
    private var resizingKeyId: String? = null
    private var selectedKeyId: String? = null
    private var resizeStartX = 0f
    private var resizeStartScale = DEFAULT_KEY_WIDTH_SCALE

    // 颜色（与横屏键盘一致）
    private val colorBg = Color.parseColor("#0E1116")
    private val colorKey = Color.parseColor("#1C232D")
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

        unit = (w - pad * 2 - gap * 9) / 10f
        keyH = (unit * 1.55f).coerceAtMost(dp(72f))
        val fW = (w - pad * 2 - gap * 11) / 12f
        fH = (fW * 1.5f).coerceAtMost(dp(52f))
        modH = keyH * 0.95f

        // 从底部往上堆：数字行 + 4 行字母 → F 行 → 符号行 → 自定义行 → 修饰键行
        // 剩余高度全部给触摸板（比旧版更紧凑，键盘整体抬高）
        val nTop = h - pad - 5 * keyH - 4 * gap
        val qTop = nTop + keyH + gap
        val fTop = nTop - gap - fH
        val symTop = fTop - gap - fH
        val customTop = symTop - gap - modH
        val modsTop = customTop - gap - modH
        padRect = RectF(pad, stripH + pad, w - pad, modsTop - gap)

        val list = ArrayList<Cap>(64)

        // 固定修饰键行（Shift 按手机习惯放 Z 行行首）；五个键的左右顺序可自定义
        val modW = (w - pad * 2 - gap * 4) / 5f
        // 自定义快捷键槽行是 6 槽，宽度单独算（混用会挤出屏幕）
        val customW = (w - pad * 2 - gap * 5) / 6f
        run {
            var x = pad
            modRowOrder().forEachIndexed { slot, token ->
                list.add(Cap(keyForToken(token), RectF(x, modsTop, x + modW, modsTop + modH), modRowSlot = slot))
                x += modW + gap
            }
        }

        // 自定义快捷键槽（同一行高，描边区分）
        run {
            var x = pad
            for (i in 0 until ShortcutStore.SLOTS) {
                list.add(Cap(null, RectF(x, customTop, x + customW, customTop + modH), customSlot = i))
                x += customW + gap
            }
        }

        // F1~F12
        run {
            var x = pad
            for (i in 1..12) {
                list.add(Cap(Key("F$i", Hid.F1 + i - 1), RectF(x, fTop, x + fW, fTop + fH)))
                x += fW + gap
            }
        }

        // 编程常用符号行（F 行上方；/ / 等 = 直接键，( ) { } = 自动带 Shift）
        run {
            val syms = listOf(
                "(" to (Hid.NUM_9 to Mods.LSHIFT),
                ")" to (Hid.NUM_0 to Mods.LSHIFT),
                "{" to (Hid.LBRACKET to Mods.LSHIFT),
                "}" to (Hid.RBRACKET to Mods.LSHIFT),
                "[" to (Hid.LBRACKET to 0),
                "]" to (Hid.RBRACKET to 0),
                ";" to (Hid.SEMICOLON to 0),
                "'" to (Hid.APOSTROPHE to 0),
                "`" to (Hid.GRAVE to 0),
                "\\" to (Hid.BACKSLASH to 0),
                "=" to (Hid.EQUAL to 0),
                "/" to (Hid.SLASH to 0),
            )
            var x = pad
            for ((label, def) in syms) {
                list.add(
                    Cap(
                        Key(label, def.first, autoMods = def.second),
                        RectF(x, symTop, x + fW, symTop + fH),
                    )
                )
                x += fW + gap
            }
        }

        // 26 键 QWERTY + 数字行
        fun letterRow(labels: String, top: Float, inset: Float) {
            var x = pad + inset
            for (c in labels) {
                list.add(Cap(Key(c.toString(), Hid.A + (c - 'A')), RectF(x, top, x + unit, top + keyH)))
                x += unit + gap
            }
        }
        run {
            // 数字行 1-0
            var x = pad
            for (i in 0 until 10) {
                val code = if (i < 9) Hid.NUM_1 + i else Hid.NUM_0
                list.add(Cap(Key(((i + 1) % 10).toString(), code), RectF(x, nTop, x + unit, nTop + keyH)))
                x += unit + gap
            }
        }
        letterRow("QWERTYUIOP", qTop, 0f)
        letterRow("ASDFGHJKL", qTop + keyH + gap, (unit + gap) / 2f)
        run {
            // 手机式 Z 行：Shift 在行首，Z~M，行尾宽退格
            val top = qTop + 2 * (keyH + gap)
            var x = pad
            val shiftW = unit * 1.5f
            list.add(
                Cap(
                    Key("Shift", 0, isModifier = true, modBit = Mods.LSHIFT),
                    RectF(x, top, x + shiftW, top + keyH),
                )
            )
            x += shiftW + gap
            for (c in "ZXCVBNM") {
                list.add(Cap(Key(c.toString(), Hid.A + (c - 'A')), RectF(x, top, x + unit, top + keyH)))
                x += unit + gap
            }
            list.add(Cap(Key("⌫", Hid.BKSP), RectF(x, top, w - pad, top + keyH)))
        }
        run {
            // 手机式底行：逗号 · 空格 · 句号 · 回车（逗号句号分居两边）
            val top = qTop + 3 * (keyH + gap)
            val edgeW = unit
            val enterW = unit * 2f
            val spaceW = w - pad * 2 - gap * 3 - edgeW * 2 - enterW
            var x = pad
            list.add(Cap(Key(",", Hid.COMMA), RectF(x, top, x + edgeW, top + keyH))); x += edgeW + gap
            list.add(Cap(Key("", Hid.SPACE), RectF(x, top, x + spaceW, top + keyH))); x += spaceW + gap
            list.add(Cap(Key(".", Hid.PERIOD), RectF(x, top, x + edgeW, top + keyH))); x += edgeW + gap
            list.add(Cap(Key("回车", Hid.ENTER), RectF(x, top, w - pad, top + keyH)))
        }

        applyCustomKeyWidths(list)
        caps = list

        // 顶部快捷按钮：从右往左排
        val btnDefs = if (layoutEditMode) {
            listOf(
                Triple("✕", BTN_EXIT, dp(34f)),
                Triple("完成", BTN_LAYOUT, dp(52f)),
                Triple("重置", BTN_LAYOUT_RESET, dp(52f)),
            )
        } else {
            listOf(
                Triple("✕", BTN_EXIT, dp(34f)),
                Triple(if (hub.mode == InputHub.MODE_LAN) "局域网" else "蓝牙", BTN_MODE, dp(56f)),
                Triple("横屏", BTN_LAND, dp(50f)),
                Triple("检查", BTN_CHECK, dp(46f)),
                Triple("布局", BTN_LAYOUT, dp(46f)),
            )
        }
        val btnH = dp(24f)
        val btnY = (stripH - btnH) / 2f
        var xr = w - pad - dp(2f)
        val btns = ArrayList<StripBtn>(btnDefs.size)
        for ((label, id, bw) in btnDefs) {
            btns.add(StripBtn(RectF(xr - bw, btnY, xr, btnY + btnH), label, id))
            xr -= bw + dp(8f)
        }
        stripButtons = btns
    }

    /**
     * 先按默认公式生成各行，再在每行原有左右边界内重新分配宽度。
     * 因此 A 行缩进、Z 行宽 Shift/退格、底行宽空格都会保留，任何自定义比例也不会溢出屏幕。
     */
    private fun applyCustomKeyWidths(list: List<Cap>) {
        val rows = list.groupBy { it.rect.top }.values.sortedBy { it.first().rect.top }
        for ((rowIndex, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val left = row.minOf { it.rect.left }
            val right = row.maxOf { it.rect.right }
            val available = right - left - gap * (row.size - 1)
            val rawWidths = row.mapIndexed { column, cap ->
                cap.baseWidth = cap.rect.width()
                cap.prefKey = widthPrefKey(rowIndex, column)
                cap.baseWidth * keyWidthScale(cap.prefKey)
            }
            val normalize = available / rawWidths.sum().coerceAtLeast(1f)
            var x = left
            for ((column, cap) in row.withIndex()) {
                val customWidth = rawWidths[column] * normalize
                cap.rect.set(x, cap.rect.top, x + customWidth, cap.rect.bottom)
                x += customWidth + gap
            }
        }
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBg)
        if (caps.isEmpty()) return

        drawStrip(canvas)
        drawTouchpad(canvas)

        val mainSize = minOf(keyH * 0.30f, unit * 0.36f)
        val smallSize = mainSize * 0.8f

        for (c in caps) {
            val k = c.key
            val editing = layoutEditMode && c.prefKey == selectedKeyId
            val pressed = pointerCaps.containsValue(c)
            val latched = k != null && k.isModifier && k.modBit != Mods.LGUI && k in latchedMods
            val locked = k != null && k.isModifier &&
                (k.modBit == Mods.LSHIFT || k.modBit == Mods.RSHIFT) &&
                (lockedBits and k.modBit) != 0
            val capsLit = k != null && k.code == Hid.CAPSLOCK && hub.capsOn

            if (c.customSlot >= 0) {
                // 自定义槽
                val s = shortcuts.getOrNull(c.customSlot)
                paintFill.color = when {
                    editing -> colorKeyLatched
                    pressed -> colorKeyPressed
                    else -> colorKey
                }
                canvas.drawRoundRect(c.rect, radius, radius, paintFill)
                paintStroke.color = when {
                    editing -> colorAccent
                    layoutEditMode || s != null -> colorAccentDim
                    else -> Color.parseColor("#3A4350")
                }
                canvas.drawRoundRect(c.rect, radius, radius, paintStroke)
                val label = if (layoutEditMode) {
                    s?.let { ShortcutStore.labelFor(it.mods, it.code) } ?: "快捷${c.customSlot + 1}"
                } else {
                    s?.let { ShortcutStore.labelFor(it.mods, it.code) } ?: "＋"
                }
                if (label.isNotEmpty()) {
                    paintText.color = when {
                        editing -> colorAccent
                        s != null -> colorAccent
                        else -> colorDim
                    }
                    var ts = smallSize
                    paintText.textSize = ts
                    val tw = paintText.measureText(label)
                    val maxW = c.rect.width() - dp(6f)
                    if (tw > maxW) {
                        ts = (ts * maxW / tw).coerceAtLeast(dp(7f))
                        paintText.textSize = ts
                    }
                    val fm = paintText.fontMetrics
                    canvas.drawText(
                        label,
                        c.rect.centerX(),
                        c.rect.centerY() - (fm.ascent + fm.descent) / 2f,
                        paintText,
                    )
                }
                drawResizeOverlay(canvas, c, editing)
                continue
            }

            val key = k!!
            paintFill.color = when {
                editing -> colorKeyLatched
                pressed -> colorKeyPressed
                latched -> colorKeyLatched
                else -> colorKey
            }
            canvas.drawRoundRect(c.rect, radius, radius, paintFill)
            if (layoutEditMode || latched || capsLit || locked) {
                paintStroke.color = if (editing || latched || capsLit || locked) colorAccent else colorAccentDim
                canvas.drawRoundRect(c.rect, radius, radius, paintStroke)
            }
            if (locked) {
                // 锁定态：键帽下缘橙条（区别于一次性锁存）
                paintFill.color = colorAccent
                canvas.drawRoundRect(
                    RectF(c.rect.left + dp(10f), c.rect.bottom - dp(5f), c.rect.right - dp(10f), c.rect.bottom - dp(2f)),
                    dp(2f), dp(2f), paintFill,
                )
            }
            val visibleLabel = if (layoutEditMode && key.label.isEmpty()) "空格" else key.label
            if (visibleLabel.isNotEmpty()) {
                paintText.color = when {
                    editing -> colorAccent
                    capsLit && key.code == Hid.CAPSLOCK -> colorAccent
                    locked -> colorAccent
                    else -> colorText
                }
                paintText.textSize = if (visibleLabel.length > 2) smallSize else mainSize
                val fm = paintText.fontMetrics
                canvas.drawText(
                    visibleLabel,
                    c.rect.centerX(),
                    c.rect.centerY() - (fm.ascent + fm.descent) / 2f,
                    paintText,
                )
            }
            drawResizeOverlay(canvas, c, editing)
        }
    }

    private fun drawResizeOverlay(canvas: Canvas, cap: Cap, editing: Boolean) {
        if (!editing) return
        paintStroke.color = colorAccent
        paintStroke.strokeWidth = dp(2f)
        val handleX = cap.rect.right - dp(7f)
        canvas.drawLine(handleX - dp(3f), cap.rect.centerY() - dp(9f), handleX - dp(3f), cap.rect.centerY() + dp(9f), paintStroke)
        canvas.drawLine(handleX + dp(3f), cap.rect.centerY() - dp(9f), handleX + dp(3f), cap.rect.centerY() + dp(9f), paintStroke)
        paintStroke.strokeWidth = dp(1.5f)
        paintStrip.color = colorAccent
        paintStrip.textSize = dp(9f)
        canvas.drawText(
            "%.0f%%".format(keyWidthScale(cap.prefKey) * 100f),
            cap.rect.left + dp(5f),
            cap.rect.bottom - dp(4f),
            paintStrip,
        )
    }

    private fun drawStrip(canvas: Canvas) {
        val status = if (layoutEditMode) {
            val selected = caps.firstOrNull { it.prefKey == selectedKeyId }
            if (selected == null) "布局编辑 · 选中键帽后左右拖动" else "正在调整 ${capName(selected)} · 左右拖动"
        } else {
            hub.statusLine()
        }
        paintStrip.color = if (layoutEditMode || hub.btConnected || hub.lanConnected) colorAccent else colorDim
        paintStrip.textSize = dp(13f)
        canvas.drawText(status, pad + dp(4f), stripH / 2f + dp(5f), paintStrip)

        for (b in stripButtons) {
            val active = b.id == BTN_LAYOUT && layoutEditMode
            paintFill.color = if (active) colorKeyLatched else colorKey
            canvas.drawRoundRect(b.rect, radius, radius, paintFill)
            if (active) {
                paintStroke.color = colorAccent
                canvas.drawRoundRect(b.rect, radius, radius, paintStroke)
            }
            paintText.color = if (active) colorAccent else colorText
            paintText.textSize = dp(11f)
            val fm = paintText.fontMetrics
            canvas.drawText(
                b.label,
                b.rect.centerX(),
                b.rect.centerY() - (fm.ascent + fm.descent) / 2f,
                paintText,
            )
        }
    }

    private fun drawTouchpad(canvas: Canvas) {
        if (padRect.isEmpty) return
        paintFill.color = colorKey
        canvas.drawRoundRect(padRect, radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(padRect, radius, radius, paintStroke)

        // 右缘滚动条（贴条上下滑 = 滚动）
        paintFill.color = Color.parseColor("#161D27")
        canvas.drawRoundRect(
            RectF(padRect.right - dp(30f), padRect.top + dp(5f), padRect.right - dp(4f), padRect.bottom - dp(5f)),
            radius, radius, paintFill,
        )
        paintText.color = Color.parseColor("#5A6474")
        paintText.textSize = dp(10f)
        val fm = paintText.fontMetrics
        canvas.drawText("滚", padRect.right - dp(17f), padRect.top + dp(26f) - fm.ascent - fm.descent, paintText)

        paintText.color = colorDim
        paintText.textSize = dp(16f)
        canvas.drawText("触 摸 板", padRect.centerX(), padRect.centerY() - dp(8f), paintText)
        paintText.textSize = dp(11f)
        canvas.drawText(
            "单指移动 · 轻点=左键 · 长按=右键 · 右缘条/双指上下滑=滚动",
            padRect.centerX(),
            padRect.centerY() + dp(14f),
            paintText,
        )
        paintText.color = Color.parseColor("#5A6474")
        canvas.drawText(
            "长按自定义键可编辑快捷键 · 长按 Ctrl/Alt/Tab/Win/Esc 可互换位置",
            padRect.centerX(),
            padRect.bottom - dp(10f),
            paintText,
        )
    }

    // ---------- 触控分发 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // 调整中的手指滑入触摸板或状态条后仍归布局编辑处理，避免中途触发其他操作。
        if (layoutEditMode && resizePointerId != INVALID_POINTER_ID) {
            handleLayoutResize(e)
            return true
        }

        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)

        if (y < stripH) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                for (b in stripButtons) {
                    if (b.contains(x, y)) {
                        onStripButton(b.id)
                        break
                    }
                }
            }
            return true
        }

        if (layoutEditMode) {
            handleLayoutResize(e)
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pid = e.getPointerId(i)
                when {
                    padRect.contains(x, y) -> {
                        pointerInPad.add(pid)
                        tp.onPointerDown(pid, x, y, inScrollStrip = x >= padRect.right - dp(34f))
                    }
                    else -> press(pid, x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val pid = e.getPointerId(p)
                    if (pid in pointerInPad) {
                        tp.onPointerMove(pid, e.getX(p), e.getY(p))
                    }
                    // 键盘区手指不支持滑动换键：按下即绑定，抬起时结算
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                when {
                    pid in pointerInPad -> {
                        pointerInPad.remove(pid)
                        tp.onPointerUp(pid)
                    }
                    else -> release(pid)
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun onStripButton(id: Int) {
        haptic()
        when (id) {
            BTN_LAND -> {
                releaseAll()
                context.startActivity(Intent(context, KeyboardActivity::class.java))
            }
            BTN_MODE -> {
                // 蓝牙/局域网 独立模式一键切换
                releaseAll()
                val next = if (hub.mode == InputHub.MODE_LAN) InputHub.MODE_BT else InputHub.MODE_LAN
                prefs.edit().putString("conn_mode", next).apply()
                (context.applicationContext as App).applyConnMode()
                computeLayout()   // 按钮文字换成当前模式
            }
            BTN_LAYOUT -> {
                finishLayoutResize(save = true)
                if (!layoutEditMode) releaseAll()
                layoutEditMode = !layoutEditMode
                selectedKeyId = null
                computeLayout()
            }
            BTN_LAYOUT_RESET -> {
                finishLayoutResize(save = false)
                resetCustomKeyWidths()
                computeLayout()
            }
            BTN_CHECK -> hub.checkConnections()
            BTN_EXIT -> (context as Activity).finish()
        }
        invalidate()
    }

    // ---------- 竖屏键宽编辑 ----------

    private fun handleLayoutResize(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val i = e.actionIndex
                val cap = capAt(e.getX(i), e.getY(i)) ?: return
                resizePointerId = e.getPointerId(i)
                resizingKeyId = cap.prefKey
                selectedKeyId = cap.prefKey
                resizeStartX = e.getX(i)
                resizeStartScale = keyWidthScale(cap.prefKey)
                haptic()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(resizePointerId)
                if (i < 0) return
                val id = resizingKeyId ?: return
                val cap = caps.firstOrNull { it.prefKey == id } ?: return
                val dragScale = (e.getX(i) - resizeStartX) / cap.baseWidth.coerceAtLeast(unit * 0.5f)
                val scale = (resizeStartScale + dragScale).coerceIn(MIN_KEY_WIDTH_SCALE, MAX_KEY_WIDTH_SCALE)
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
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_WIDTH_PREF_PREFIX)) edit.remove(key)
        }
        edit.apply()
        keyWidthScales.clear()
        selectedKeyId = null
    }

    private fun keyWidthScale(prefKey: String): Float = keyWidthScales.getOrPut(prefKey) {
        prefs.getFloat(prefKey, DEFAULT_KEY_WIDTH_SCALE).coerceIn(MIN_KEY_WIDTH_SCALE, MAX_KEY_WIDTH_SCALE)
    }

    private fun widthPrefKey(row: Int, column: Int) = "$KEY_WIDTH_PREF_PREFIX${row}_$column"

    private fun capName(cap: Cap): String = when {
        cap.customSlot >= 0 -> "快捷${cap.customSlot + 1}"
        cap.key?.label.isNullOrEmpty() -> "空格"
        else -> cap.key!!.label
    }

    // ---------- 键盘触控 ----------

    private fun capAt(x: Float, y: Float): Cap? {
        caps.firstOrNull { it.contains(x, y) }?.let { return it }
        var best: Cap? = null
        var bestDist = Float.MAX_VALUE
        for (c in caps) {
            val d = distToRect(c.rect, x, y)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return if (bestDist <= dp(HIT_SLOP_DP)) best else null
    }

    private fun press(pid: Int, x: Float, y: Float) {
        val c = capAt(x, y) ?: return
        pointerCaps[pid] = c
        haptic()

        if (c.customSlot >= 0) {
            // 自定义槽：抬起时发组合键；按住 550ms 不动 = 打开编辑器
            pointerCustom[pid] = c.customSlot
            val run = Runnable {
                if (pointerCustom.containsKey(pid)) {
                    customLongFired.add(pid)
                    openEditor(c.customSlot)
                }
            }
            customPendingRun[pid] = run
            postDelayed(run, 550)
            invalidate()
            return
        }

        // 修饰键排：无锁存/无组合/无大写锁定时，长按 550ms 打开换位对话框
        // （有锁存时按下会立即产生锁存/组合动作，长按与其冲突，故不启用）
        if (c.modRowSlot >= 0 && latchedMods.isEmpty() && pendingChordBits == 0 && lockedBits == 0) {
            val run = Runnable { fireModRowLongPress(pid, c) }
            modRowLongRun[pid] = run
            postDelayed(run, MOD_ROW_LONG_PRESS_MS)
        }

        val k = c.key!!
        when {
            k.isModifier && (modHold || k.modBit == Mods.LGUI) -> {
                pointerHeldMods[pid] = k.modBit
                hub.modDown(k.modBit)
            }
            k.isModifier -> {
                val isShift = k.modBit == Mods.LSHIFT || k.modBit == Mods.RSHIFT
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
                        cancelPendingChord(clearBits = true)
                        latchedMods.remove(k)
                        hub.tapMods(k.modBit)   // 再点已锁定键 = 单发（Shift 切中英文）
                    }
                    pendingChordBits != 0 -> {
                        pendingChordBits = pendingChordBits or k.modBit
                        scheduleChord()
                    }
                    latchedMods.isNotEmpty() -> {
                        pendingChordBits = latchedBits() or k.modBit
                        scheduleChord()
                    }
                    else -> {
                        latchedMods.add(k)
                        if (isShift) lastShiftLatchAt = SystemClock.uptimeMillis()
                    }
                }
            }
            else -> {
                val chordBits = pendingChordBits
                cancelPendingChord(clearBits = false)
                pendingChordBits = 0
                if (c.modRowSlot >= 0 && (k.code == Hid.TAB || k.code == Hid.ESC)) {
                    // 修饰键排的 Tab/Esc：延迟到抬起发送，给长按换位留判定窗口
                    // （否则按住 Tab 450ms 后电脑端自动连发已经发生，长按期间污染输入）
                    modRowDeferredChord[pid] = chordBits
                } else {
                    pointerSent[pid] = k.code
                    hub.keyDown(k.code, latchedBits() or chordBits or k.autoMods)
                }
            }
        }
        invalidate()
    }

    private fun release(pid: Int) {
        val c = pointerCaps.remove(pid) ?: return
        // 修饰键排：取消未触发的长按；已触发（对话框已开）则本次抬起不再发键
        modRowLongRun.remove(pid)?.let { removeCallbacks(it) }
        val modRowLongPressDidFire = modRowLongFired.remove(pid)
        if (c.customSlot >= 0) {
            customPendingRun.remove(pid)?.let { removeCallbacks(it) }
            val slot = pointerCustom.remove(pid)
            if (slot != null && !customLongFired.remove(pid)) {
                // 快速点按：发送该槽的组合键
                shortcuts.getOrNull(slot)?.let { fireShortcut(it) }
                latchedMods.clear()
            }
            invalidate()
            return
        }
        val k = c.key!!
        when {
            k.isModifier && (modHold || k.modBit == Mods.LGUI) ->
                pointerHeldMods.remove(pid)?.let { hub.modUp(it) }
            k.isModifier -> {
                // 锁存型修饰键自身抬起：保持锁存，等下一个普通键来消费
                // （点 Ctrl 锁定 → 点 A = Ctrl+A；再点 Ctrl = 单发切中英文）
            }
            // 普通键释放 = 消费锁存
            else -> {
                if (modRowLongPressDidFire) {
                    // 长按换位对话框已打开：这次按压不发键
                } else if (c.modRowSlot >= 0 && (k.code == Hid.TAB || k.code == Hid.ESC)) {
                    // 延迟发送的 Tab/Esc：按下+抬起一次性发出（携带锁存的组合键）
                    val chord = modRowDeferredChord.remove(pid) ?: 0
                    hub.keyDown(k.code, latchedBits() or chord)
                    hub.keyUp(k.code)
                    latchedMods.clear()
                } else {
                    pointerSent.remove(pid)?.let { hub.keyUp(it) }
                    latchedMods.clear()
                }
            }
        }
        invalidate()
    }

    private fun fireShortcut(s: ShortcutStore.Shortcut) {
        hub.keyDown(s.code, s.mods)
        postDelayed({ hub.keyUp(s.code) }, 50)
    }

    private fun latchedBits(): Int = latchedMods.fold(0) { acc, k -> acc or k.modBit }

    private fun scheduleChord() {
        pendingChordAction?.let { removeCallbacks(it) }
        val action = Runnable {
            val bits = pendingChordBits
            pendingChordBits = 0
            pendingChordAction = null
            if (bits != 0) hub.tapMods(bits)
            latchedMods.clear()
            invalidate()
        }
        pendingChordAction = action
        postDelayed(action, CHORD_DELAY_MS)
    }

    private fun cancelPendingChord(clearBits: Boolean) {
        pendingChordAction?.let { removeCallbacks(it) }
        pendingChordAction = null
        if (clearBits) pendingChordBits = 0
    }

    private fun distToRect(r: RectF, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun releaseAll() {
        finishLayoutResize(save = true)
        cancelPendingChord(clearBits = true)
        for (pid in pointerCaps.keys.toList()) release(pid)
        pointerCaps.clear()
        pointerSent.clear()
        for (bit in pointerHeldMods.values) hub.modUp(bit)
        pointerHeldMods.clear()
        latchedMods.clear()
        lockedBits = 0
        hub.releaseAll()
        // 触摸板残余状态清空；拖动/点击中的鼠标键一并抬起
        pointerInPad.clear()
        tp.cancelAll(sendMouseUp = true)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // View 分离后 postDelayed 的任务不再有意义，惯性/拖动全部终止
        tp.cancelAll(sendMouseUp = true)
    }

    // ---------- 修饰键排顺序自定义（Ctrl/Alt/Tab/Win/Esc） ----------

    private fun modRowDefaults(): List<String> = listOf("ctrl", "alt", "tab", "win", "esc")

    private fun modRowOrder(): List<String> {
        val raw = prefs.getString("mod_row_order", null)
            ?.split(',')?.map { it.trim()?.lowercase() ?: "" }
        return if (raw != null && raw.size == 5 && raw.toSet() == modRowDefaults().toSet()) {
            raw
        } else {
            modRowDefaults()
        }
    }

    private fun saveModRowOrder(order: List<String>) {
        prefs.edit().putString("mod_row_order", order.joinToString(",")).apply()
    }

    private fun keyForToken(token: String): Key = when (token) {
        "alt" -> Key("Alt", 0, isModifier = true, modBit = Mods.LALT)
        "tab" -> Key("Tab", Hid.TAB)
        "win" -> Key("Win", 0, isModifier = true, modBit = Mods.LGUI)
        "esc" -> Key("Esc", Hid.ESC)
        else -> Key("Ctrl", 0, isModifier = true, modBit = Mods.LCTRL)
    }

    /** 长按修饰键排 550ms：回滚按下瞬间的效果，打开换位对话框 */
    private fun fireModRowLongPress(pid: Int, c: Cap) {
        modRowLongRun.remove(pid)
        modRowLongFired.add(pid)
        val k = c.key!!
        if (k.isModifier && k.modBit != Mods.LGUI && k in latchedMods) {
            latchedMods.remove(k)               // 回滚 Ctrl/Alt 在按下瞬间的锁存
        }
        cancelPendingChord(clearBits = true)
        // 回滚 Win 的按住（release 时 pointerHeldMods 已空，自动去重）
        pointerHeldMods.remove(pid)?.let { hub.modUp(it) }
        haptic()
        openModRowSwapDialog(c.modRowSlot)
        invalidate()
    }

    private fun openModRowSwapDialog(slot: Int) {
        val act = context as? Activity ?: return
        val labels = mapOf("ctrl" to "Ctrl", "alt" to "Alt", "tab" to "Tab", "win" to "Win", "esc" to "Esc")
        val order = modRowOrder()
        val current = order.getOrNull(slot) ?: return
        AlertDialog.Builder(act)
            .setTitle("把此位置换成（与所选键互换）")
            .setItems(order.map { labels[it] ?: it }.toTypedArray()) { _, which ->
                val pick = order.getOrNull(which)
                if (pick != null && pick != current) {
                    val next = order.toMutableList()
                    next[which] = current
                    next[slot] = pick
                    saveModRowOrder(next)
                    computeLayout()
                    haptic()
                }
                invalidate()
            }
            .show()
    }

    // ---------- 自定义快捷键编辑器 ----------

    private fun openEditor(slot: Int) {
        val act = context as? Activity ?: return
        haptic()
        val current = shortcuts.getOrNull(slot)

        val dpI = { v: Float -> (v * resources.displayMetrics.density).toInt() }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpI(20f), dpI(12f), dpI(20f), dpI(4f))
        }

        // 前置声明：监听器里先引用，创建完控件后再赋上真正的实现
        var refreshPreview: () -> Unit = {}

        box.addView(TextView(context).apply {
            text = "修饰键（可多选）"
            textSize = 13f
            setTextColor(colorDim)
        })
        val modChecks = LinkedHashMap<Int, CheckBox>()
        run {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((bit, name) in listOf(
                Mods.LCTRL to "Ctrl", Mods.LSHIFT to "Shift",
                Mods.LALT to "Alt", Mods.LGUI to "Win",
            )) {
                row.addView(CheckBox(context).apply {
                    text = name
                    textSize = 14f
                    isChecked = current != null && current.mods and bit != 0
                    setOnCheckedChangeListener { _, _ -> refreshPreview() }
                    modChecks[bit] = this
                })
            }
            box.addView(row)
        }

        box.addView(TextView(context).apply {
            text = "主键"
            textSize = 13f
            setTextColor(colorDim)
        }, LinearLayout.LayoutParams(ViewGroup_Wrap(), dpI(6f)))

        val keyLabels = ShortcutStore.CHOOSABLE_KEYS.map { it.first }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                keyLabels,
            )
            setSelection(
                current?.let { c -> ShortcutStore.CHOOSABLE_KEYS.indexOfFirst { it.second == c.code } }
                    ?.takeIf { it >= 0 } ?: 0
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) =
                    refreshPreview()

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        box.addView(spinner)

        val preview = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup_Wrap(), ViewGroup_Wrap()).also {
            it.topMargin = dpI(14f)
        })

        fun modsBits(): Int = modChecks.entries.fold(0) { acc, (bit, cb) -> if (cb.isChecked) acc or bit else acc }

        refreshPreview = {
            val code = ShortcutStore.CHOOSABLE_KEYS[spinner.selectedItemPosition].second
            preview.text = "预览：${ShortcutStore.labelFor(modsBits(), code)}"
        }
        refreshPreview()

        val dialog = AlertDialog.Builder(act)
            .setTitle("自定义快捷键 · 槽位 ${slot + 1}")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("清空此槽", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = ShortcutStore.CHOOSABLE_KEYS[spinner.selectedItemPosition].second
                val list = shortcuts.toMutableList()
                list[slot] = ShortcutStore.Shortcut(modsBits(), code)
                shortcuts = list
                store.save(list)
                dialog.dismiss()
                invalidate()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val list = shortcuts.toMutableList()
                list[slot] = null
                shortcuts = list
                store.save(list)
                dialog.dismiss()
                invalidate()
            }
        }
        dialog.show()
    }

    private fun ViewGroup_Wrap(): Int = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

    // ---------- 通用 ----------

    fun refreshStatus() {
        invalidate()
    }

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
        private const val MOD_ROW_LONG_PRESS_MS = 550L
        private const val HIT_SLOP_DP = 3f
        private const val DEFAULT_KEY_WIDTH_SCALE = 1f
        private const val MIN_KEY_WIDTH_SCALE = 0.6f
        private const val MAX_KEY_WIDTH_SCALE = 2.5f
        private const val KEY_WIDTH_PREF_PREFIX = "onehand_key_width_"
        private const val INVALID_POINTER_ID = -1
        private const val BTN_LAND = 1
        private const val BTN_CHECK = 2
        private const val BTN_EXIT = 3
        private const val BTN_MODE = 4
        private const val BTN_LAYOUT = 5
        private const val BTN_LAYOUT_RESET = 6
    }
}
