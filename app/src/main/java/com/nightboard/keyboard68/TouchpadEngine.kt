package com.nightboard.keyboard68

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View

/**
 * 触摸板手势引擎：横屏全屏触控板（KeyboardView）与竖屏触摸板（OneHandView）共用。
 *
 * 手势语义（对齐笔记本触摸板习惯）：
 *  - 单指移动 = 光标（亚像素累积，12ms 节流，慢速拖动不丢步）
 *  - 轻点（<220ms 且位移 <14px）= 左键单击
 *  - 双击后按住拖动 = 选择文本（第二次按下即左键按下，随移动拖动选区）
 *  - 单指长按 500ms 不动 = 右键
 *  - 滚动条手指（由视图判定后告知）= 上下滑全幅滚轮，优先级最高
 *  - 双指滑动 = 滚动；指间距变化占主导时 = 缩放（按住 Ctrl + 滚轮）
 *  - 滚动/甩动带惯性，16ms 步进衰减
 *
 * 实现要点：
 *  - 位移与滚轮独立冲销（历史上 else-if 耦合 + 亚像素残留曾导致滚轮被永久饿死）
 *  - 惯性用 generation token 作废旧任务；新手势/取消/分离三处都会终止
 *  - 视图只做事件路由与滚动条命中判定，鼠标事件一律经 InputHub 按当前模式发送
 */
class TouchpadEngine(
    private val view: View,
    private val hub: InputHub,
    private val prefs: SharedPreferences,
) {
    /** 长按右键/进入拖动等时机触发震动（视图注入） */
    var feedback: (() -> Unit)? = null

    // ---- 指针状态 ----
    private val pointers = HashMap<Int, Pair<Float, Float>>()
    private val inScroll = HashSet<Int>()
    private val inert = HashSet<Int>()          // 双指手势结束后残留的手指：不再移动光标
    private var maxPointers = 1
    private var downAt = 0L
    private var moveDist = 0f

    // ---- 长按右键 ----
    private var longPressRun: Runnable? = null
    private var longPressFired = false

    // ---- 轻点 / 双击拖动 ----
    private var lastTapUpAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var clickUpRun: Runnable? = null    // 轻点/右键 click 的 45ms 延迟抬起
    private var dragPointer = -1                // 拖动选择中的手指

    // ---- 位移 / 滚轮累积 ----
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var pendingWheel = 0f
    private var lastFlush = 0L

    // ---- 双指滚动 vs 捏合缩放 ----
    private var pinchLocked = false
    private var pinchAccum = 0f                 // |Δ指间距| 累计
    private var pinchDy = 0f                    // 平均纵向位移累计（比值判据用）
    private var lastPairDist = 0f

    // ---- 甩动速度 / 惯性 ----
    private var lastWheelAt = 0L
    private var wheelVel = 0f                   // px/ms，指数平滑
    private var momentumGen = 0
    private var momentumRun: Runnable? = null
    private var momentumWheelAcc = 0f

    // ---------- 事件入口（视图路由调用） ----------

    fun onPointerDown(pid: Int, x: Float, y: Float, inScrollStrip: Boolean) {
        stopMomentum()
        if (dragPointer != -1) return           // 拖动选择中：忽略额外手指（不计数、不触发双指）
        pointers[pid] = x to y
        if (inScrollStrip) inScroll.add(pid)
        if (pointers.size > maxPointers) maxPointers = pointers.size

        if (pointers.size == 1) {
            downAt = now()
            moveDist = 0f
            longPressFired = false
            pendingDx = 0f; pendingDy = 0f; pendingWheel = 0f
            pinchLocked = false; pinchAccum = 0f; pinchDy = 0f
            wheelVel = 0f
            cancelLongPress()
            if (pid !in inScroll) {
                // 双击后按住 = 拖动选择：立即左键按下
                if (now() - lastTapUpAt < DOUBLE_TAP_MS &&
                    dist(x, y, lastTapX, lastTapY) < dpx(DOUBLE_TAP_SLOP_DP)
                ) {
                    // ★防竞争：第一次轻点的 45ms 延迟抬起必须撤销，否则会抬掉拖动的按下
                    clickUpRun?.let { view.removeCallbacks(it) }
                    clickUpRun = null
                    dragPointer = pid
                    hub.sendMouse(0, 0, 0, 1)
                    feedback?.invoke()
                    return
                }
                val run = Runnable {
                    longPressRun = null
                    if (pointers.size == 1 && moveDist < TAP_SLOP_PX && !longPressFired &&
                        inScroll.isEmpty() && dragPointer == -1
                    ) {
                        longPressFired = true
                        feedback?.invoke()
                        hub.sendMouse(0, 0, 0, 2)
                        scheduleClickUp()
                    }
                }
                longPressRun = run
                view.postDelayed(run, LONG_PRESS_MS)
            }
        } else if (pointers.size == 2) {
            // 第二根手指落下：建立捏合判定基线
            pinchLocked = false; pinchAccum = 0f; pinchDy = 0f
            lastPairDist = pairDist()
        }
    }

    fun onPointerMove(pid: Int, x: Float, y: Float) {
        if (pid == dragPointer) {
            val old = pointers[pid] ?: return
            val dx = x - old.first
            val dy = y - old.second
            pointers[pid] = x to y
            pendingDx += dx
            pendingDy += dy
            moveDist += Math.abs(dx) + Math.abs(dy)
            flush(force = false)
            return
        }
        if (pid in inert && pid !in inScroll) {
            pointers[pid] = x to y              // 位置更新即可，效果被屏蔽（滚动条手指不受限）
            return
        }
        val old = pointers[pid] ?: return
        val dx = x - old.first
        val dy = y - old.second
        pointers[pid] = x to y

        when {
            // 滚动条手指优先级最高：全幅滚轮，不受双指分支影响
            pid in inScroll -> {
                pendingWheel += dy
                moveDist += Math.abs(dy)        // ★甩动累计位移，防止被误判成轻点左键
                trackWheelVel(dy)
            }
            pointers.size >= 2 -> {
                val d = pairDist()
                val dd = d - lastPairDist
                lastPairDist = d
                pinchAccum += Math.abs(dd)
                // 捏合意图：间距变化有绝对量且显著大于平移分量才锁定，防止斜向滚动误判
                if (!pinchLocked &&
                    pinchAccum > Math.max(dpx(10f), 2f * pinchDy) &&
                    pinchAccum > dpx(14f)
                ) {
                    pinchLocked = true
                    hub.modDown(Mods.LCTRL)     // 缩放 = Ctrl+滚轮
                }
                if (pinchLocked) {
                    pendingWheel += dd          // 间距拉大 = 滚轮向上 = 放大
                } else {
                    pendingWheel += dy / 2f
                    pinchDy += Math.abs(dy) / 2f
                    trackWheelVel(dy / 2f)
                }
            }
            else -> {
                pendingDx += dx
                pendingDy += dy
                moveDist += Math.abs(dx) + Math.abs(dy)
            }
        }
        flush(force = false)
    }

    fun onPointerUp(pid: Int) {
        if (pid == dragPointer) {
            pointers.remove(pid)
            dragPointer = -1
            cancelLongPress()
            hub.sendMouse(0, 0, 0, 0)           // 左键抬起，选择结束
            flush(force = true)
            return
        }
        inScroll.remove(pid)
        if (pointers.remove(pid) == null) return

        if (pointers.isEmpty()) {
            flush(force = true)
            cancelLongPress()
            val dur = now() - downAt
            val clickFired = !longPressFired && dragPointer == -1 &&
                maxPointers == 1 && moveDist < TAP_SLOP_PX && dur < TAP_MAX_MS
            if (clickFired) {
                hub.sendMouse(0, 0, 0, 1)
                scheduleClickUp()
            } else {
                startMomentumIfFlick()
            }
            if (pinchLocked) {
                hub.modUp(Mods.LCTRL)
                pinchLocked = false
            }
            maxPointers = 1
            longPressFired = false
        } else if (pointers.size == 1) {
            // 双指 → 单指：剩余手指标记 inert，光标不跳变；双指滚轮/捏合结束收 Ctrl
            inert.add(pointers.keys.first())
            if (pinchLocked) {
                hub.modUp(Mods.LCTRL)
                pinchLocked = false
            }
        }
    }

    /** 触摸取消 / 退后台 / 模式切换：清空全部状态，必要时补发鼠标抬起防卡键 */
    fun cancelAll(sendMouseUp: Boolean) {
        momentumGen++
        momentumRun = null
        cancelLongPress()
        if (dragPointer != -1 || clickUpRun != null) {
            clickUpRun?.let { view.removeCallbacks(it) }
            clickUpRun = null
            if (sendMouseUp) hub.sendMouse(0, 0, 0, 0)
        }
        if (pinchLocked) {
            hub.modUp(Mods.LCTRL)
            pinchLocked = false
        }
        dragPointer = -1
        pointers.clear()
        inScroll.clear()
        inert.clear()
        pendingDx = 0f; pendingDy = 0f; pendingWheel = 0f
        maxPointers = 1
        moveDist = 0f
        longPressFired = false
        wheelVel = 0f
    }

    // ---------- 内部 ----------

    private fun flush(force: Boolean) {
        val t = now()
        if (!force && t - lastFlush < FLUSH_MS) return
        lastFlush = t

        // 位移：只发整数部分，亚像素残留继续累积（慢速拖动不丢步）
        val sx = pendingDx.toInt()
        val sy = pendingDy.toInt()
        if (sx != 0 || sy != 0) {
            pendingDx -= sx
            pendingDy -= sy
            hub.sendMouse(sx, sy, 0, 0)
        }
        // 滚轮：与位移独立冲销（★不可 else-if，否则残留饿死滚轮）
        pendingWheel = if (force) {
            drainWheel(pendingWheel, roundUp = true)
        } else {
            drainWheel(pendingWheel, roundUp = false)
        }
        if (force) {
            // 全部抬指后，亚像素残留已无意义，清零防跨手势污染
            if (Math.abs(pendingDx) < 1f) pendingDx = 0f
            if (Math.abs(pendingDy) < 1f) pendingDy = 0f
        }
    }

    /** 把滚轮像素冲销成格发送；每条报告最多 ±6 格，超出循环排空 */
    private fun drainWheel(accum: Float, roundUp: Boolean): Float {
        val notch = notchPx()
        var w = accum
        while (true) {
            val raw = if (roundUp) Math.round(-w / notch) else (-w / notch).toInt()
            if (raw == 0) break
            val c = raw.coerceIn(-6, 6)
            hub.sendMouse(0, 0, c, 0)
            w += c * notch
            if (Math.abs(raw) < 6) break        // 剩余不足一批，下轮继续
        }
        return w
    }

    private fun scheduleClickUp() {
        val r = Runnable {
            clickUpRun = null
            hub.sendMouse(0, 0, 0, 0)
        }
        clickUpRun = r
        view.postDelayed(r, CLICK_UP_MS)
    }

    private fun cancelLongPress() {
        longPressRun?.let { view.removeCallbacks(it) }
        longPressRun = null
    }

    private fun trackWheelVel(dy: Float) {
        val t = now()
        val dt = t - lastWheelAt
        wheelVel = if (dt in 1..120) {
            val inst = dy / dt
            if (wheelVel == 0f) inst else wheelVel * 0.6f + inst * 0.4f
        } else {
            dy / Math.max(dt, 1L)
        }
        lastWheelAt = t
    }

    private fun startMomentumIfFlick() {
        if (Math.abs(wheelVel) < FLICK_VEL_PX_MS) return
        val vel = wheelVel
        momentumGen++
        val gen = momentumGen
        var remaining = vel * MOMENTUM_TOTAL_FACTOR
        if (Math.abs(remaining) < 24f) return
        momentumWheelAcc = 0f
        val run = object : Runnable {
            override fun run() {
                if (gen != momentumGen) return   // 已被新手势/取消作废
                val step = remaining * 0.18f
                momentumWheelAcc += step
                remaining *= 0.86f
                momentumWheelAcc = drainWheel(momentumWheelAcc, roundUp = false)
                if (Math.abs(remaining) < 4f) return
                view.postDelayed(this, MOMENTUM_STEP_MS)
            }
        }
        view.postDelayed(run, MOMENTUM_STEP_MS)
    }

    private fun stopMomentum() {
        momentumGen++
        momentumRun = null
    }

    private fun pairDist(): Float {
        val it = pointers.values.iterator()
        val a = it.next()
        val b = it.next()
        return dist(a.first, a.second, b.first, b.second)
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun notchPx(): Float = prefs.getInt("scroll_notch_px", DEFAULT_NOTCH_PX).coerceIn(8, 60).toFloat()

    private fun dpx(v: Float): Float = v * view.resources.displayMetrics.density

    private fun now(): Long = SystemClock.uptimeMillis()

    companion object {
        private const val TAP_MAX_MS = 220L
        private const val TAP_SLOP_PX = 14f         // 与历史版本一致，用物理像素
        private const val DOUBLE_TAP_MS = 300L
        private const val DOUBLE_TAP_SLOP_DP = 40f
        private const val LONG_PRESS_MS = 500L
        private const val CLICK_UP_MS = 45L
        private const val FLUSH_MS = 12L
        private const val MOMENTUM_STEP_MS = 16L
        private const val MOMENTUM_TOTAL_FACTOR = 140f
        private const val FLICK_VEL_PX_MS = 0.6f
        private const val DEFAULT_NOTCH_PX = 14
    }
}
