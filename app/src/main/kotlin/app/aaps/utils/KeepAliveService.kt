package app.aaps.utils

import app.aaps.MainActivity
import android.R

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import android.app.PendingIntent

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "aaps_bg_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AndroidAPS 后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "确保实时接收血糖数据"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
        builder.setContentTitle("AndroidAPS 正在运行")
        builder.setContentText("实时监控血糖，勿清除通知")
        builder.setSmallIcon(AndroidR.drawable.ic_dialog_info) // ✅ 系统图标
        builder.setContentIntent(pendingIntent)
        builder.setOngoing(true)
        builder.priority = NotificationCompat.PRIORITY_LOW

        return builder.build()
    }
}
