package com.nightboard.keyboard68

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：保活 HID 键盘注册与蓝牙连接。
 * 退出键盘页面、手机锁屏后连接不掉线；通知栏可快速回到键盘或停止服务。
 */
class KeyboardService : Service(), App.HidUi {

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        app.ensureHid()
        app.addUi(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                (application as App).removeUi(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_KB -> startActivity(
                Intent(this, KeyboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        (application as App).removeUi(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onHidState() {
        updateNotification()
    }

    private fun startAsForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NightBoard68 键盘", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val app = application as App
        val host = app.hid.hostName()
        val text = when {
            host != null -> "已连接 $host · 点按回到键盘"
            app.hid.isRegistered() -> "键盘就绪 · 等待电脑连接"
            else -> "蓝牙键盘未就绪"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, KeyboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KeyboardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("NightBoard68")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "停止", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.nightboard.keyboard68.action.STOP"
        const val ACTION_OPEN_KB = "com.nightboard.keyboard68.action.OPEN_KB"
        const val CHANNEL_ID = "keyboard"
        const val NOTIF_ID = 1
    }
}
