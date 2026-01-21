package app.aaps.plugins.source.xDripAidl

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.main.MainActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * xDrip AIDL连接前台服务
 * 负责在后台保持与xDrip的AIDL连接，确保数据及时接收
 * 鸿蒙系统下需要前台服务来避免后台限制
 */
class XdripForegroundService : Service() {
    
    companion object {
        private const val TAG = "XdripForegroundService"
        private const val NOTIFICATION_ID = 10089
        private const val CHANNEL_ID = "xdrip_aidl_foreground"
        private const val CHANNEL_NAME = "xDrip数据服务"
        
        // 服务控制
        const val ACTION_START_SERVICE = "app.aaps.xdrip.START_FOREGROUND"
        const val ACTION_STOP_SERVICE = "app.aaps.xdrip.STOP_FOREGROUND"
        const val ACTION_UPDATE_NOTIFICATION = "app.aaps.xdrip.UPDATE_NOTIFICATION"
        
        // 数据相关
        const val EXTRA_GLUCOSE = "glucose"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_CONNECTION_STATUS = "connection_status"
        
        fun startService(context: Context) {
            val intent = Intent(context, XdripForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, XdripForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        fun updateNotification(context: Context, glucose: Double? = null, status: String? = null) {
            val intent = Intent(context, XdripForegroundService::class.java).apply {
                action = ACTION_UPDATE_NOTIFICATION
                glucose?.let { putExtra(EXTRA_GLUCOSE, it) }
                status?.let { putExtra(EXTRA_CONNECTION_STATUS, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private lateinit var serviceScope: CoroutineScope
    private var isRunning = false
    private var lastGlucoseValue: Double = 0.0
    private var lastDataTime: Long = 0
    private var connectionStatus: String = "等待连接"
    private var notificationUpdateCount = 0
    
    // ========== 数据广播接收器 ==========
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                // 来自XdripAidlService的数据
                XdripAidlService.ACTION_DATA_RECEIVED -> {
                    handleIncomingData(intent)
                }
                
                // 来自Plugin的连接状态更新
                "app.aaps.xdrip.CONNECTION_STATUS" -> {
                    connectionStatus = intent.getStringExtra("status") ?: "未知"
                    updateNotification()
                }
            }
        }
    }
    
    private fun handleIncomingData(intent: Intent) {
        val glucose = intent.getDoubleExtra(XdripAidlService.EXTRA_GLUCOSE, 0.0)
        val timestamp = intent.getLongExtra(XdripAidlService.EXTRA_TIMESTAMP, 0)
        
        if (glucose > 0 && timestamp > 0) {
            lastGlucoseValue = glucose
            lastDataTime = timestamp
            connectionStatus = "数据正常"
            
            // 更新通知
            updateNotification()
            
            // 记录数据接收
            if (notificationUpdateCount % 10 == 0) {
                logToFile("Data received: $glucose at ${formatTime(timestamp)}")
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        
        serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        isRunning = true
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务
        startForegroundService()
        
        // 注册广播接收器
        registerDataReceiver()
        
        // 启动心跳检测
        startHeartbeatCheck()
        
        logToFile("Foreground service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleCommand(it) }
        return START_STICKY
    }
    
    private fun handleCommand(intent: Intent) {
        when (intent.action) {
            ACTION_START_SERVICE -> {
                logToFile("Service start command received")
                updateNotification()
            }
            
            ACTION_STOP_SERVICE -> {
                logToFile("Service stop command received")
                stopSelf()
            }
            
            ACTION_UPDATE_NOTIFICATION -> {
                val glucose = intent.getDoubleExtra(EXTRA_GLUCOSE, 0.0)
                val status = intent.getStringExtra(EXTRA_CONNECTION_STATUS)
                
                if (glucose > 0) {
                    lastGlucoseValue = glucose
                    lastDataTime = System.currentTimeMillis()
                }
                
                status?.let { connectionStatus = it }
                
                updateNotification()
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持xDrip数据连接，确保后台数据接收"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun startForegroundService() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        logToFile("Foreground service started with notification")
    }
    
    private fun buildNotification(): Notification {
        // 主Activity Intent
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("source", "xdrip_foreground_service")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        // 构建通知内容
        val title = if (lastGlucoseValue > 0) {
            "AAPS: ${lastGlucoseValue.toInt()} mg/dL"
        } else {
            "AAPS-xDrip"
        }
        
        val timeText = if (lastDataTime > 0) {
            val secondsAgo = (System.currentTimeMillis() - lastDataTime) / 1000
            "${secondsAgo}s前"
        } else {
            "暂无数据"
        }
        
        val contentText = "$connectionStatus • $timeText"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(getNotificationIcon())
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    
    private fun getNotificationIcon(): Int {
        return try {
            // 尝试获取AAPS的通知图标
            val resources = packageManager.getResourcesForApplication(packageName)
            resources.getIdentifier("ic_notification", "drawable", packageName)
        } catch (e: Exception) {
            // 使用默认图标
            android.R.drawable.ic_dialog_info
        }
    }
    
    private fun updateNotification() {
        notificationUpdateCount++
        
        val notification = buildNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
        
        // 定期记录状态
        if (notificationUpdateCount % 20 == 0) {
            logToFile("Notification updated $notificationUpdateCount times, last glucose: $lastGlucoseValue")
        }
    }
    
    private fun registerDataReceiver() {
        val filter = IntentFilter().apply {
            addAction(XdripAidlService.ACTION_DATA_RECEIVED)
            addAction("app.aaps.xdrip.CONNECTION_STATUS")
        }
        
        try {
            registerReceiver(dataReceiver, filter, RECEIVER_EXPORTED)
            logToFile("Data receiver registered")
        } catch (e: Exception) {
            logToFile("Failed to register receiver: ${e.message}")
        }
    }
    
    private fun startHeartbeatCheck() {
        serviceScope.launch {
            var heartbeatCount = 0
            
            while (isRunning) {
                delay(30000) // 30秒一次心跳
                heartbeatCount++
                
                // 检查数据新鲜度
                val timeSinceLastData = System.currentTimeMillis() - lastDataTime
                if (timeSinceLastData > 300000 && lastDataTime > 0) { // 5分钟无数据
                    connectionStatus = "数据延迟"
                    updateNotification()
                    
                    if (heartbeatCount % 4 == 0) { // 每2分钟记录一次
                        logToFile("No data for ${timeSinceLastData / 1000}s, status: $connectionStatus")
                    }
                }
                
                // 每10次心跳记录一次
                if (heartbeatCount % 10 == 0) {
                    logToFile("Service heartbeat #$heartbeatCount, last data: ${formatTime(lastDataTime)}")
                }
            }
        }
    }
    
    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0) return "N/A"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun logToFile(message: String) {
        // 这里可以添加文件日志记录，用于调试
        // 对于生产环境，建议使用AAPS的日志系统
        println("[$TAG] $message")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        
        isRunning = false
        serviceScope.cancel()
        
        try {
            unregisterReceiver(dataReceiver)
        } catch (e: IllegalArgumentException) {
            // 忽略
        }
        
        logToFile("Foreground service destroyed")
    }
}
