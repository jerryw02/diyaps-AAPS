package app.aaps.utils

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat

/**
 * 鸿蒙后台保活管理器
 * 专门针对鸿蒙系统的后台限制
 */
object HarmonyBackgroundManager {
    
    private const val TAG = "HarmonyBgManager"
    
    // 鸿蒙特定的权限和设置
    private const val HARMONY_PERMISSION_KEEP_BACKGROUND = "ohos.permission.KEEP_BACKGROUND_RUNNING"
    private const val HARMONY_PERMISSION_RUNNING_LOCK = "ohos.permission.RUNNING_LOCK"
    private const val HARMONY_SETTINGS_ACTION = "com.huawei.systemmanager.optimize.process.ProtectActivity"
    
    /**
     * 鸿蒙专用后台保活初始化
     */
    fun initHarmonyBackground(context: Context) {
        Log.d(TAG, "初始化鸿蒙后台保活")
        
        // 1. 启动永久前台服务
        startPermanentForegroundService(context)
        
        // 2. 申请鸿蒙特殊权限
        requestHarmonyPermissions(context)
        
        // 3. 设置鸿蒙自启动
        setupHarmonyAutoStart(context)
        
        // 4. 设置鸿蒙电池优化白名单
        setupHarmonyBatteryWhiteList(context)
        
        // 5. 定期心跳保持
        startHeartbeatService(context)
    }
    
    /**
     * 启动永久前台服务（核心）
     */
    private fun startPermanentForegroundService(context: Context) {
        try {
            val serviceIntent = Intent(context, HarmonyForegroundService::class.java)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            Log.d(TAG, "鸿蒙永久前台服务已启动")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "启动前台服务权限不足", e)
            // 申请通知权限
            requestNotificationPermission(context)
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
        }
    }
    
    /**
     * 申请鸿蒙特殊权限
     */
    private fun requestHarmonyPermissions(context: Context) {
        try {
            // 检查是否为鸿蒙系统
            if (!isHarmonyOS()) {
                Log.d(TAG, "非鸿蒙系统，跳过特殊权限申请")
                return
            }
            
            Log.d(TAG, "开始申请鸿蒙特殊权限")
            
            // 方法1：通过反射申请权限
            tryRequestViaReflection(context)
            
            // 方法2：引导用户到鸿蒙设置
            guideToHarmonySettings(context)
            
            // 方法3：设置系统属性
            setHarmonySystemProperties()
            
        } catch (e: Exception) {
            Log.e(TAG, "申请鸿蒙权限失败", e)
        }
    }
    
    /**
     * 检查是否为鸿蒙系统
     */
    private fun isHarmonyOS(): Boolean {
        return try {
            // 方法1：检查鸿蒙特有类
            Class.forName("ohos.app.Context")
            true
        } catch (e: ClassNotFoundException) {
            // 方法2：检查Build属性
            Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
            Build.BRAND.equals("HUAWEI", ignoreCase = true) ||
            Build.BRAND.equals("HONOR", ignoreCase = true)
        }
    }
    
    /**
     * 通过反射申请鸿蒙权限
     */
    private fun tryRequestViaReflection(context: Context) {
        try {
            // 鸿蒙的AbilityManager
            val abilityManagerClass = Class.forName("ohos.app.AbilityManager")
            val getInstanceMethod = abilityManagerClass.getMethod("getInstance")
            val abilityManager = getInstanceMethod.invoke(null)
            
            // 申请后台运行权限
            val requestMethod = abilityManagerClass.getMethod(
                "requestKeepBackgroundRunning", 
                Int::class.java, 
                String::class.java
            )
            
            val result = requestMethod.invoke(abilityManager, context.hashCode(), "血糖监控服务")
            Log.d(TAG, "鸿蒙后台权限申请结果: $result")
            
        } catch (e: Exception) {
            Log.w(TAG, "反射申请鸿蒙权限失败", e)
        }
    }
    
    /**
     * 引导用户到鸿蒙设置
     */
    private fun guideToHarmonySettings(context: Context) {
        if (context !is Activity) {
            return
        }
        
        val activity = context as Activity
        
        // 显示鸿蒙专用引导对话框
        AlertDialog.Builder(activity)
            .setTitle("鸿蒙系统设置")
            .setMessage("""
                为了确保AAPS在后台持续运行，请在鸿蒙系统中进行以下设置：
                
                1. 【应用启动管理】
                  设置 > 应用 > 应用启动管理 > AAPS
                  → 关闭「自动管理」
                  → 开启「允许自启动」
                  → 开启「允许后台活动」
                
                2. 【电池优化】
                  设置 > 电池 > 应用耗电排行 > AAPS
                  → 选择「不允许」
                
                3. 【通知管理】
                  设置 > 通知 > AAPS
                  → 开启所有通知权限
                
                点击「去设置」查看详细教程
            """.trimIndent())
            .setPositiveButton("去设置") { dialog, _ ->
                dialog.dismiss()
                openHarmonySettings(activity)
            }
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 打开鸿蒙设置
     */
    private fun openHarmonySettings(context: Context) {
        try {
            // 尝试多个鸿蒙设置入口
            val intents = listOf(
                // 入口1：应用启动管理
                Intent().apply {
                    action = "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    putExtra("packageName", context.packageName)
                },
                
                // 入口2：电池优化
                Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:${context.packageName}")
                },
                
                // 入口3：应用详情
                Intent().apply {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
            )
            
            for (intent in intents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d(TAG, "成功打开鸿蒙设置: ${intent.action}")
                    return
                } catch (e: ActivityNotFoundException) {
                    // 尝试下一个入口
                    continue
                }
            }
            
            // 所有入口都失败，显示手动指南
            showManualGuide(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "打开鸿蒙设置失败", e)
            showManualGuide(context)
        }
    }
    
    /**
     * 显示手动设置指南
     */
    private fun showManualGuide(context: Context) {
        if (context !is Activity) return
        
        AlertDialog.Builder(context)
            .setTitle("手动设置指南")
            .setMessage("""
                请手动进行以下设置：
                
                1. 进入「设置」>「应用」>「应用启动管理」
                2. 找到「AAPS」，关闭「自动管理」
                3. 手动开启「允许自启动」和「允许后台活动」
                4. 返回设置，进入「电池」
                5. 找到「AAPS」，设置为「不允许」
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }
    
    /**
     * 设置鸿蒙系统属性
     */
    private fun setHarmonySystemProperties() {
        try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val setMethod = systemProperties.getMethod("set", String::class.java, String::class.java)
            
            // 尝试设置鸿蒙后台相关属性
            val properties = mapOf(
                "persist.sys.allow_third_app_bg" to "1",
                "hw_power.whitelist" to "app.aaps",  // AAPS包名
                "sys.background_app_restrict" to "0"
            )
            
            for ((key, value) in properties) {
                try {
                    setMethod.invoke(null, key, value)
                    Log.d(TAG, "设置系统属性: $key=$value")
                } catch (e: Exception) {
                    // 忽略失败
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "设置系统属性失败", e)
        }
    }
    
    /**
     * 设置鸿蒙自启动
     */
    private fun setupHarmonyAutoStart(context: Context) {
        try {
            // 鸿蒙自启动设置
            val autoStartIntent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.parse("package:${context.packageName}")
                putExtra("from", "autostart")
            }
            
            context.startActivity(autoStartIntent)
            
        } catch (e: Exception) {
            Log.w(TAG, "设置自启动失败", e)
        }
    }
    
    /**
     * 设置鸿蒙电池优化白名单
     */
    private fun setupHarmonyBatteryWhiteList(context: Context) {
        try {
            // 鸿蒙电池优化设置
            val batteryIntent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:${context.packageName}")
            }
            
            context.startActivity(batteryIntent)
            
        } catch (e: Exception) {
            Log.w(TAG, "设置电池优化失败", e)
        }
    }
    
    /**
     * 申请通知权限
     */
    private fun requestNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+需要通知渠道
            val channel = NotificationChannel(
                "harmony_bg_channel",
                "血糖监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持AAPS在后台运行以监控血糖"
            }
            
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * 启动心跳服务
     */
    private fun startHeartbeatService(context: Context) {
        // 启动一个定期唤醒的服务
        val heartbeatIntent = Intent(context, HarmonyHeartbeatService::class.java)
        context.startService(heartbeatIntent)
    }
    
    /**
     * 检查后台运行状态
     */
    fun checkBackgroundStatus(context: Context): String {
        return try {
            if (isHarmonyOS()) {
                "鸿蒙系统检测通过"
            } else {
                "非鸿蒙系统，使用标准方案"
            }
        } catch (e: Exception) {
            "状态检查失败: ${e.message}"
        }
    }
}

/**
 * 鸿蒙永久前台服务
 */
class HarmonyForegroundService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 10091
        private const val CHANNEL_ID = "harmony_foreground"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d("HarmonyService", "永久前台服务创建")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 保持服务运行
        keepServiceAlive()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("HarmonyService", "服务启动命令")
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "血糖监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持AAPS在后台运行"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        // 创建点击打开应用的Intent
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AAPS血糖监控运行中")
            .setContentText("鸿蒙后台保活服务")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .build()
    }
    
    private fun keepServiceAlive() {
        // 定期发送广播保持活跃
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                sendBroadcast(Intent("app.aaps.HEARTBEAT"))
                handler.postDelayed(this, 60000) // 每分钟一次
            }
        }, 60000)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d("HarmonyService", "永久前台服务销毁")
    }
}

/**
 * 鸿蒙心跳服务
 */
class HarmonyHeartbeatService : Service() {
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 定期唤醒，保持进程活跃
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            // 发送空广播保持活跃
            sendBroadcast(Intent("harmony.keepalive"))
            
            // 重启自己
            val restartIntent = Intent(this, HarmonyHeartbeatService::class.java)
            startService(restartIntent)
        }, 300000) // 每5分钟一次
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
