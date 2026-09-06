package com.nightboard.keyboard68

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * 单手模式：竖屏全屏，上触摸板 + 中快捷键 + 下 26 键。
 * 与横屏 68 键（KeyboardActivity）并存，互相可切换。
 */
class OneHandActivity : Activity(), App.HidUi {

    private lateinit var view: OneHandView

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

        view = OneHandView(this, (application as App).hub)
        setContentView(view)

        // 前台服务保活（与横屏共用同一个服务）
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
