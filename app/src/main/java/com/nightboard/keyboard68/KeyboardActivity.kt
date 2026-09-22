package com.nightboard.keyboard68

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 键盘界面：横屏全屏，整块屏幕就是 68 键键盘。
 */
class KeyboardActivity : Activity(), App.HidUi {

    private lateinit var view: KeyboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyKeepScreenOn()
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view = KeyboardView(this, (application as App).hub)
        setContentView(view)

        // 软键盘输入：IME 悬浮在键盘之上，不压缩/重排键盘布局（Moonlight 式无输入框输入）
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // 系统栏被系统重新拉出（切换 Activity 的动画结束后、横竖屏切换时）
        // 时立即再次隐藏，防止导航条/状态条意外出现。
        window.decorView.setOnSystemUiVisibilityChangeListener { flags ->
            if ((flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) applyImmersive()
        }

        // 前台服务保活：退出键盘页面 / 锁屏后连接不掉线
        startForegroundService(Intent(this, KeyboardService::class.java))
    }

    /** 全屏沉浸：隐藏状态栏与导航栏（API 30+ 用 WindowInsetsController，否则旧 flag） */
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        if (Build.VERSION.SDK_INT >= 30) {
            // API 30+（Android 11）仅靠旧 flag 无法可靠隐藏导航栏，需用 InsetsController
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars() or WindowInsets.Type.captionBar())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 切屏 / 从其他页面回来时系统栏可能在聚焦后被重新拉出：立即重设，并再延迟一帧兜底
        if (hasFocus) {
            applyImmersive()
            window.decorView.post { applyImmersive() }
        }
    }

    /** 屏幕常亮：设置页开关（默认开），键盘页面不自动熄屏 */
    private fun applyKeepScreenOn() {
        val on = getSharedPreferences("nightboard", MODE_PRIVATE).getBoolean("keep_screen_on", true)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        (application as App).addUi(this)
        applyKeepScreenOn()
        applyBrightness()
        view.refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // 亮度控制权交还系统，避免影响其他页面
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        // 退到后台时把所有按住的键放开，防止电脑上"卡键"
        view.releaseAll()
        (application as App).removeUi(this)
    }

    /** 夜间模式：设置里调低的亮度只作用于键盘页面 */
    private fun applyBrightness() {
        val pct = getSharedPreferences("nightboard", MODE_PRIVATE).getInt("kb_brightness", 100)
        window.attributes = window.attributes.apply {
            screenBrightness = if (pct >= 100) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                pct / 100f
            }
        }
    }

    override fun onHidState() {
        runOnUiThread { view.refreshStatus() }
    }
}