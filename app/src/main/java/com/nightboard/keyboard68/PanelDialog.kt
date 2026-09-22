package com.nightboard.keyboard68

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Point
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 统一的自定义弹窗（替代系统 AlertDialog）：深色圆角面板，与键盘同风格。
 * KeyboardView 与 OneHandView 共用；内容由调用方在 content 里构建。
 */
object PanelDialog {

    fun dp(act: Activity, v: Float): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun panelBg(act: Activity): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(act, 16f).toFloat()
        setColor(Color.parseColor("#161D27"))
        setStroke(dp(act, 1f), Color.parseColor("#3A4553"))
    }

    /** 打开一个自定义面板；content(root, dialog) 里添加控件。返回 Dialog 便于控制关闭。 */
    fun show(act: Activity, title: String, content: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = Dialog(act, R.style.CustomDialog)
        val wm = act.windowManager
        val size = Point()
        wm.defaultDisplay.getRealSize(size)
        // 外层 ScrollView：内容超高时整体可滚动，保证底部按钮（保存/完成）始终可达
        val outer = MaxHeightScrollView(act, (size.y * 0.92f).toInt()).apply { isVerticalScrollBarEnabled = false }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 18f), dp(act, 10f), dp(act, 18f), dp(act, 14f))
            background = panelBg(act)
        }
        outer.addView(root)
        // 标题行：左标题 + 右上角 ✕ 关闭
        val titleRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(act, 8f))
        }
        titleRow.addView(TextView(act).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E6EAF0"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(act).apply {
            text = "✕"
            textSize = 17f
            includeFontPadding = false
            setTextColor(Color.parseColor("#E6EAF0"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(act, 9f).toFloat()
                setColor(Color.parseColor("#2A3340"))
                setStroke(dp(act, 1f), Color.parseColor("#3A4553"))
            }
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(act, 36f), dp(act, 36f)))
        root.addView(titleRow)
        content(root, dialog)
        dialog.setContentView(outer)
        // 高度自适应内容，但不超过屏高 92%（超出则整体滚动）
        dialog.window?.setLayout((size.x * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        dialog.show()
        // 弹窗也是全屏沉浸（隐藏导航栏/状态栏），避免唤出弹窗时系统栏意外弹出
        try {
            dialog.window?.decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        } catch (_: Exception) {
        }
        return dialog
    }

    /** 面板里的一个可点选项行；selected 高亮为橙色 */
    fun optionRow(act: Activity, root: LinearLayout, label: String, selected: Boolean, onClick: () -> Unit) {
        root.addView(TextView(act).apply {
            text = label
            textSize = 15f
            setTextColor(if (selected) Color.parseColor("#E8944A") else Color.parseColor("#E6EAF0"))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 12f), dp(act, 12f), dp(act, 12f), dp(act, 12f))
            background = if (selected) GradientDrawable().apply {
                cornerRadius = dp(act, 8f).toFloat()
                setColor(Color.parseColor("#2A2318"))
            } else null
            setOnClickListener {
                hapticTap(act)
                onClick()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(act, 4f) })
    }

    /** 面板底部的强调按钮（如「完成」「发送」） */
    fun accentButton(act: Activity, root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(TextView(act).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#0E1116"))
            gravity = Gravity.CENTER
            setPadding(0, dp(act, 12f), 0, dp(act, 12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(act, 8f).toFloat()
                setColor(Color.parseColor("#E8944A"))
            }
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(act, 10f) })
    }

    /** 面板底部的次要按钮（如「取消」「关闭」） */
    fun plainButton(act: Activity, root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(TextView(act).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#8A93A3"))
            gravity = Gravity.CENTER
            setPadding(0, dp(act, 10f), 0, dp(act, 6f))
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    /** 面板内的分组小标题 */
    fun sectionTitle(act: Activity, root: LinearLayout, text: String) {
        root.addView(TextView(act).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#8A93A3"))
            setPadding(0, dp(act, 10f), 0, dp(act, 6f))
        })
    }

    private fun hapticTap(act: Activity) {
        try {
            val v = act.getSystemService(Activity.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v?.vibrate(android.os.VibrationEffect.createOneShot(12, 80))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(12)
            }
        } catch (_: Exception) {
        }
    }
}

/**
 * 高度受限的 ScrollView：内容未超限时高度自适应内容，超限时压缩到 maxHeight 并滚动。
 * （View.setMaxHeight 是隐藏 API，用测量约束实现）
 */
class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            maxHeight
        } else {
            size.coerceAtMost(maxHeight)
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}

/**
 * 简易流式布局：子 View 一行放不下自动换行（用于组合键按钮自适应宽度）。
 * 子 View 用 wrap_content 测量，行内顶部对齐。
 */
class FlowLayout(context: Context) : ViewGroup(context) {

    private val gapPx = (8 * context.resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val childWSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED)
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(childWSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x + cw > width && x > 0) {
                x = 0
                y += lineH + gapPx
                lineH = 0
            }
            x += cw + gapPx
            lineH = maxOf(lineH, ch)
        }
        setMeasuredDimension(width, resolveSize(y + lineH, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x + cw > width && x > 0) {
                x = 0
                y += lineH + gapPx
                lineH = 0
            }
            c.layout(x, y, x + cw, y + ch)
            x += cw + gapPx
            lineH = maxOf(lineH, ch)
        }
    }
}
