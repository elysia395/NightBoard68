package com.nightboard.keyboard68

import android.app.Activity
import android.content.Context
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

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
 *  - 顶部状态条：中英 / 输入法 / 触控板 / 退出 快捷按钮
 *  - 触控板模式：整屏变触控板，单指移动、轻点左键、双指轻点右键、双指滚动
 */
class KeyboardView(context: Context, private val hub: InputHub) : View(context) {

    private class KeyRect(val key: Key, l: Float, t: Float, r: Float, b: Float) {
        val rect = RectF(l, t, r, b)
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    private class StripBtn(val rect: RectF, val label: String, val id: Int) {
        fun contains(x: Float, y: Float) = rect.contains(x, y)
    }

    private val prefs = context.getSharedPreferences("nightboard", Context.MODE_PRIVATE)
    private val hapticAmp = prefs.getInt("haptic_amp", 140)
    private val modHold = prefs.getBoolean("mod_hold", false)

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

    // 修饰键组合（如 Ctrl+Shift 切输入法）：延迟发送，期间打字则取消
    private var pendingChordBits = 0
    private var pendingChordAction: Runnable? = null

    // 触控板模式
    private var touchMode = false
    private val tpPos = HashMap<Int, Pair<Float, Float>>()
    private var tpDownAt = 0L
    private var tpMoveDist = 0f
    private var tpMaxPointers = 1
    private var tpPendingDx = 0f
    private var tpPendingDy = 0f
    private var tpPendingWheel = 0f
    private var tpLastFlush = 0L
    // 单指长按 = 右键
    private var tpLongPressRun: Runnable? = null
    private var tpLongPressFired = false
    // 右缘滚动条：落在条内的手指上下滑 = 滚动（双指滑动的单指平替）
    private val pointerInScroll = HashSet<Int>()

    // 颜色
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
    private val paintFn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintStrip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        computeLayout()
    }

    private fun computeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        pad = dp(6f)
        gap = dp(4f)
        radius = dp(9f)
        stripH = dp(34f)
        unitW = (w - pad * 2 - gap * 16) / 16f
        rowH = (h - stripH - pad * 2 - gap * 4) / 5f
        val list = ArrayList<KeyRect>(68)
        var y = stripH + pad
        for (row in Layout68.rows) {
            var x = pad
            for (k in row) {
                list.add(KeyRect(k, x, y, x + k.width * unitW, y + rowH))
                x += k.width * unitW + gap
            }
            y += rowH + gap
        }
        rects = list

        // 顶部快捷按钮：从右往左排（状态文本在左侧）
        val btnDefs = listOf(
            Triple("✕", BTN_EXIT, dp(34f)),
            Triple(if (hub.mode == InputHub.MODE_LAN) "局域网" else "蓝牙", BTN_MODE, dp(56f)),
            Triple("检查", BTN_CHECK, dp(46f)),
            Triple("竖屏", BTN_ONEHAND, dp(46f)),
            Triple(if (touchMode) "键盘" else "触控板", BTN_PAD, dp(60f)),
            Triple("输入法", BTN_IME, dp(64f)),
            Triple("中英", BTN_SHIFT, dp(52f)),
        )
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

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colorBg)
        if (rects.isEmpty()) return

        drawStrip(canvas)

        if (touchMode) {
            drawTouchpad(canvas)
            return
        }

        val mainSize = minOf(rowH * 0.30f, unitW * 0.34f)
        val fnSize = mainSize * 0.52f

        for (r in rects) {
            val k = r.key
            val pressed = pointerKeys.containsValue(r)
            val latched = k.isModifier && k.modBit != Mods.LGUI && k in latchedMods
            // 双击锁定的 Shift：橙框 + 下缘橙条
            val locked = k.isModifier &&
                (k.modBit == Mods.LSHIFT || k.modBit == Mods.RSHIFT) &&
                (lockedBits and k.modBit) != 0
            // 电脑发回的 LED 状态：大写锁定时 Caps 键常亮
            val capsLit = k.code == Hid.CAPSLOCK && hub.capsOn
            val fnActive = fnLatched && k.fnLabel != null

            paintFill.color = when {
                pressed -> colorKeyPressed
                latched -> colorKeyLatched
                else -> colorKey
            }
            canvas.drawRoundRect(r.rect, radius, radius, paintFill)

            if (latched || fnActive || capsLit || locked) {
                paintStroke.color = colorAccent
                canvas.drawRoundRect(r.rect, radius, radius, paintStroke)
            }
            if (locked) {
                paintFill.color = colorAccent
                canvas.drawRoundRect(
                    RectF(r.rect.left + dp(10f), r.rect.bottom - dp(5f), r.rect.right - dp(10f), r.rect.bottom - dp(2f)),
                    dp(2f), dp(2f), paintFill,
                )
            }

            if (k.label.isNotEmpty()) {
                paintText.color = when {
                    fnActive -> colorAccent
                    capsLit && k.code == Hid.CAPSLOCK -> colorAccent
                    locked -> colorAccent
                    else -> colorText
                }
                paintText.textSize = mainSize
                val cx = r.rect.centerX()
                val cy = r.rect.centerY() + if (k.fnLabel != null) mainSize * 0.22f else 0f
                val fm = paintText.fontMetrics
                canvas.drawText(
                    if (fnActive) k.fnLabel!! else k.label,
                    cx,
                    cy - (fm.ascent + fm.descent) / 2f,
                    paintText,
                )
            }

            // 未激活时在右上角显示 Fn 层标签
            if (k.fnLabel != null && !fnActive && k.label.isNotEmpty()) {
                paintFn.color = colorAccentDim
                paintFn.textSize = fnSize
                canvas.drawText(
                    k.fnLabel,
                    r.rect.right - dp(7f),
                    r.rect.top + dp(6f) + fnSize,
                    paintFn,
                )
            }
        }
    }

    private fun drawStrip(canvas: Canvas) {
        // 状态文本（按钮左侧的空间里）：蓝牙 / 局域网 双链路状态
        paintStrip.color = if (hub.btConnected || hub.lanConnected) colorAccent else colorDim
        paintStrip.textSize = dp(13f)
        canvas.drawText(hub.statusLine(), pad + dp(4f), stripH / 2f + dp(5f), paintStrip)

        // 快捷按钮
        for (b in stripButtons) {
            val active = b.id == BTN_PAD && touchMode
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
        val l = pad
        val t = stripH + pad
        val r = width - pad
        val b = height - pad
        paintFill.color = colorKey
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paintFill)
        paintStroke.color = colorAccentDim
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paintStroke)

        // 右缘滚动条（贴条上下滑 = 滚动）
        paintFill.color = Color.parseColor("#161D27")
        canvas.drawRoundRect(
            RectF(r - dp(30f), t + dp(5f), r - dp(4f), b - dp(5f)),
            radius, radius, paintFill,
        )
        paintText.color = Color.parseColor("#5A6474")
        paintText.textSize = dp(10f)
        val fm = paintText.fontMetrics
        canvas.drawText("滚", r - dp(17f), t + dp(26f) - fm.ascent - fm.descent, paintText)

        paintText.color = colorDim
        paintText.textSize = dp(22f)
        canvas.drawText("触控板", width / 2f, height / 2f - dp(10f), paintText)
        paintText.textSize = dp(13f)
        canvas.drawText("单指移动 · 轻点=左键 · 长按=右键 · 右缘条/双指上下滑=滚动", width / 2f, height / 2f + dp(18f), paintText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val x = e.getX(i)
        val y = e.getY(i)

        // 状态条：快捷按钮
        if (y < stripH) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                for (b in stripButtons) {
                    if (b.contains(x, y)) {
                        onStripButton(b.id)
                        break
                    }
                }
            }
            return true
        }

        if (touchMode) {
            handleTouchpad(e)
            return true
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

    private fun onStripButton(id: Int) {
        haptic()
        when (id) {
            BTN_SHIFT -> hub.tapMods(Mods.LSHIFT)                  // 单发 Shift：切中英文
            BTN_IME -> hub.tapMods(Mods.LCTRL or Mods.LSHIFT)      // Ctrl+Shift：切输入法
            BTN_PAD -> {
                touchMode = !touchMode
                releaseAll()
                computeLayout()   // 按钮文字换成「键盘」
            }
            BTN_ONEHAND -> {
                releaseAll()
                context.startActivity(android.content.Intent(context, OneHandActivity::class.java))
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
            BTN_EXIT -> (context as Activity).finish()
        }
        invalidate()
    }

    // ---------- 键盘触控 ----------

    private fun press(pid: Int, x: Float, y: Float) {
        val r = keyAt(x, y) ?: return
        pointerKeys[pid] = r
        haptic()
        val k = r.key
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
                        // 再点一次已锁定的修饰键 = 单独发送（Shift 单点切中英文）
                        cancelPendingChord(clearBits = true)
                        latchedMods.remove(k)
                        hub.tapMods(k.modBit)
                    }
                    pendingChordBits != 0 -> {
                        // 组合键里再加一个修饰键，重新计时
                        pendingChordBits = pendingChordBits or k.modBit
                        scheduleChord()
                    }
                    latchedMods.isNotEmpty() -> {
                        // 已有锁定修饰键时点新修饰键 = 延迟组组合键（Ctrl+Shift 切输入法）
                        pendingChordBits = latchedBits() or k.modBit
                        scheduleChord()
                    }
                    else -> {
                        latchedMods.add(k)
                        if (isShift) lastShiftLatchAt = SystemClock.uptimeMillis()
                    }
                }
            }
            k.code == -1 -> fnLatched = !fnLatched
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
            k.isModifier && (modHold || k.modBit == Mods.LGUI) ->
                pointerHeldMods.remove(pid)?.let { hub.modUp(it) }
            k.isModifier -> {
                // 锁存型修饰键自身抬起：保持锁存，等下一个普通键来消费
                // （点 Ctrl 锁定 → 点 A = Ctrl+A；再点 Ctrl = 单发 Shift 类切换）
            }
            // 锁存型修饰键 / Fn：由下一次普通键释放消费
            else -> {
                pointerSent.remove(pid)?.let { hub.keyUp(it) }
                latchedMods.clear()
                fnLatched = false
            }
        }
        invalidate()
    }

    private fun latchedBits(): Int = latchedMods.fold(0) { acc, k -> acc or k.modBit }

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
        cancelPendingChord(clearBits = true)
        for (pid in pointerKeys.keys.toList()) release(pid)
        pointerKeys.clear()
        pointerSent.clear()
        for (bit in pointerHeldMods.values) hub.modUp(bit)
        pointerHeldMods.clear()
        latchedMods.clear()
        lockedBits = 0
        fnLatched = false
        hub.releaseAll()
        // 触控板残余状态
        tpPos.clear()
        tpPendingDx = 0f; tpPendingDy = 0f; tpPendingWheel = 0f
        tpLongPressRun?.let { removeCallbacks(it) }
        tpLongPressRun = null
        tpLongPressFired = false
        pointerInScroll.clear()
        invalidate()
    }

    // ---------- 触控板 ----------

    private fun handleTouchpad(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                val pid = e.getPointerId(i)
                tpPos[pid] = x to y
                if (x >= width - pad - dp(34f)) pointerInScroll.add(pid)
                if (tpPos.size > tpMaxPointers) tpMaxPointers = tpPos.size
                if (tpPos.size == 1) {
                    tpDownAt = SystemClock.uptimeMillis()
                    tpMoveDist = 0f
                    tpLongPressFired = false
                    // 单指按住 500ms 不动 = 右键（滚动条手指/第二根手指落下/移动则取消）
                    tpLongPressRun?.let { removeCallbacks(it) }
                    if (pid !in pointerInScroll) {
                        val run = Runnable {
                            tpLongPressRun = null
                            if (tpPos.size == 1 && tpMoveDist < 14f && !tpLongPressFired && pointerInScroll.isEmpty()) {
                                tpLongPressFired = true
                                haptic()
                                hub.sendMouse(0, 0, 0, 2)
                                postDelayed({ hub.sendMouse(0, 0, 0, 0) }, 45)
                            }
                        }
                        tpLongPressRun = run
                        postDelayed(run, TP_LONG_PRESS_MS)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val pid = e.getPointerId(i)
                    val old = tpPos[pid] ?: continue
                    val dx = e.getX(i) - old.first
                    val dy = e.getY(i) - old.second
                    tpPos[pid] = e.getX(i) to e.getY(i)
                    when {
                        tpPos.size >= 2 -> tpPendingWheel += dy / 2f
                        pid in pointerInScroll -> tpPendingWheel += dy   // 右缘条：整幅位移算滚动
                        else -> {
                            tpPendingDx += dx
                            tpPendingDy += dy
                            tpMoveDist += Math.abs(dx) + Math.abs(dy)
                        }
                    }
                }
                flushTouchpad(force = false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = e.getPointerId(e.actionIndex)
                tpPos.remove(pid)
                pointerInScroll.remove(pid)
                if (tpPos.isEmpty()) {
                    flushTouchpad(force = true)
                    tpLongPressRun?.let { removeCallbacks(it); tpLongPressRun = null }
                    val dur = SystemClock.uptimeMillis() - tpDownAt
                    if (!tpLongPressFired && tpMaxPointers == 1 && tpMoveDist < 14f && dur < 220f) {
                        // 轻点 = 左键（长按已发右键；双指轻点不再触发右键）
                        hub.sendMouse(0, 0, 0, 1)
                        postDelayed({ hub.sendMouse(0, 0, 0, 0) }, 45)
                    }
                    tpLongPressFired = false
                    tpMaxPointers = 1
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                tpPos.clear()
                tpMaxPointers = 1
                flushTouchpad(force = true)
            }
        }
    }

    private fun wasTwoFingerTap(): Boolean = tpMaxPointers >= 2

    private fun flushTouchpad(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - tpLastFlush < 12) return
        tpLastFlush = now
        if (tpPendingDx != 0f || tpPendingDy != 0f) {
            // 只发送整数部分，小数余量留到下次累计——慢速拖动不再丢步
            val sx = tpPendingDx.toInt()
            val sy = tpPendingDy.toInt()
            if (sx != 0 || sy != 0) {
                tpPendingDx -= sx
                tpPendingDy -= sy
                hub.sendMouse(sx, sy, 0, 0)
            }
        } else if (tpPendingWheel != 0f) {
            // 滚动：一格 = 设置里的像素数（默认 24，可在设置页调灵敏度）
            val notch = prefs.getInt("scroll_notch_px", 24).coerceIn(8, 80).toFloat()
            val wv = (-tpPendingWheel / notch).toInt()
            if (wv != 0) {
                tpPendingWheel += wv * notch
                hub.sendMouse(0, 0, wv.coerceIn(-6, 6), 0)
            }
        }
    }

    // ---------- 通用 ----------

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
        private const val TP_LONG_PRESS_MS = 500L
        private const val HIT_SLOP_DP = 3f   // 死区就近归属半径（间隙 4dp 时 3dp 恰好完全覆盖）
        private const val BTN_SHIFT = 1
        private const val BTN_IME = 2
        private const val BTN_PAD = 3
        private const val BTN_EXIT = 4
        private const val BTN_ONEHAND = 5
        private const val BTN_CHECK = 6
        private const val BTN_MODE = 7
    }
}
