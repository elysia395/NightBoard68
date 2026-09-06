package com.nightboard.keyboard68

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * 键盘界面：横屏全屏，整块屏幕就是 68 键键盘。
 */
class KeyboardActivity : Activity(), App.HidUi {

    private lateinit var view: KeyboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        view = KeyboardView(this, (application as App).hid)
        setContentView(view)

        // 前台服务保活：退出键盘页面 / 锁屏后连接不掉线
        startForegroundService(Intent(this, KeyboardService::class.java))
    }

    override fun onResume() {
        super.onResume()
        (application as App).addUi(this)
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
