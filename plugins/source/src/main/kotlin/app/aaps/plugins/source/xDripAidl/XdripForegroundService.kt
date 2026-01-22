package app.aaps.plugins.source.xDripAidl

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * 完全独立的xDrip前台服务
 * 不依赖任何外部类，避免编译错误
 */
class XdripForegroundService : Service() {
    
    companion object {
        private const val TAG = "XdripForegroundService"
        private const val NOTIFICATION_ID = 10090
        private const val CHANNEL_ID = "xdrip_service_channel"
        
        fun startService(context: Context) {
            try {
                val intent = Intent(context, XdripForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
        
        fun stopService(context: Context) {
            try {
                val intent = Intent(context, XdripForegroundService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Service stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }
    
    private var serviceScope: CoroutineScope? = null
    private var isServiceRunning = false
    private var lastUpdateTime: Long = 0
    
    // 数据接收器
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // 接收来自XdripAidlService的数据
                "app.aaps.xdrip.DATA_RECEIVED" -> {
                    handleDataReceived(intent)
                }
            }
        }
    }
    
    private fun handleDataReceived(intent: Intent) {
        try {
            val glucose = intent.getDoubleExtra("glucose", 0.0)
            val timestamp = intent.getLongExtra("timestamp", 0)
            
            if (glucose > 0) {
                lastUpdateTime = timestamp
                updateNotification(glucose, timestamp)
                Log.d(TAG, "Data received: $glucose at $timestamp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling data", e)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        isServiceRunning = true
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        createNotificationChannel()
        startForegroundService()
        registerReceivers()
        startHeartbeat()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, startId: $startId")
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "xDrip数据服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持xDrip数据连接"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        val notification = createNotification("服务启动中...", "正在连接xDrip")
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground service started")
    }
    
    // ========== 修复：移除MainActivity引用，使用隐式Intent ==========
    private fun createNotification(title: String, content: String): Notification {
        // 创建启动主应用的Intent
        // 使用包名来启动应用
        val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            PendingIntent.getActivity(
                this, 
                0, 
                launchIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
        } else {
            // 备用方案：创建一个空的PendingIntent
            PendingIntent.getActivity(
                this,
                0,
                Intent(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            )
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
    }
    
    
    private fun updateNotification(glucose: Double, timestamp: Long) {
        val timeAgo = (System.currentTimeMillis() - timestamp) / 1000
        val title = "AAPS: ${glucose.toInt()} mg/dL"
        val content = "数据正常 • ${timeAgo}秒前"
        
        val notification = createNotification(title, content)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }
     
    
    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction("app.aaps.xdrip.DATA_RECEIVED")
                addAction("app.aaps.xdrip.CONNECTION_STATUS")
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(dataReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(dataReceiver, filter)
            }
            
            Log.d(TAG, "Receivers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers", e)
        }
    }
    
    private fun startHeartbeat() {
        serviceScope?.launch {
            var count = 0
            while (isServiceRunning) {
                delay(30000) // 30秒心跳
                count++
                
                // 检查数据新鲜度
                val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
                if (timeSinceUpdate > 300000 && lastUpdateTime > 0) { // 5分钟无数据
                    val notification = createNotification(
                        "AAPS-xDrip", 
                        "数据延迟 • ${timeSinceUpdate / 1000}秒"
                    )
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, notification)
                }
                
                // 每10次心跳记录一次日志
                if (count % 10 == 0) {
                    Log.d(TAG, "Service heartbeat #$count")
                }
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        
        isServiceRunning = false
        serviceScope?.cancel()
        serviceScope = null
        
        try {
            unregisterReceiver(dataReceiver)
        } catch (e: IllegalArgumentException) {
            // 忽略未注册异常
        }
    }
}
