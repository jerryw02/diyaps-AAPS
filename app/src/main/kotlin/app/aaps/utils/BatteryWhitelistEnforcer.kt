package app.aaps.utils // 请保持与你的项目包名一致

import android.app.Activity
import android.app.AlertDialog
import androidx.annotation.RequiresApi
import android.content.ActivityNotFoundException
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 电池白名单强制执行器 - Kotlin版本
 * 参考 xDrip 的 JoH.forceBatteryWhitelisting() 实现
 */
object BatteryWhitelistEnforcer {
    
    private const val TAG = "BatteryWhitelist"
    
    /**
     * 主方法：强制加入电池优化白名单
     * 使用方式：BatteryWhitelistEnforcer.forceWhitelisting(this)
     */
    fun forceWhitelisting(context: Context) {
        Log.d(TAG, "开始强制电池白名单处理...")
        
        Thread {
            try {
                // 1. 先检查是否已经在白名单中
                if (isInBatteryWhitelistInternal(context)) {
                    Log.d(TAG, "已在电池白名单中，跳过")
                    showToast(context, "已在电池优化白名单中")
                    return@Thread
                }
                
                // 2. 记录当前状态
                logCurrentStatus(context)
                
                // 3. 尝试多种方法（按优先级）
                var success = false
                
                // 方法1：系统级反射调用（xDrip方式）
                success = forceSystemWhitelistingViaReflection(context)
                
                // 方法2：标准Android API
                if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    success = forceStandardWhitelisting(context)
                }
                
                // 方法3：鸿蒙特定方法
                if (!success && isHarmonyOS()) {
                    success = forceHarmonyWhitelisting(context)
                }
                
                // 4. 处理结果
                handleWhitelistResult(context, success)
                
                // 5. 验证最终状态
                verifyAndLogFinalStatus(context)
                
            } catch (e: Exception) {
                Log.e(TAG, "白名单处理错误: ${e.message}", e)
                showToast(context, "白名单设置失败，请手动设置")
            }
        }.start()
    }
    
    /**
     * 方法1：系统级反射调用（xDrip核心方法）
     */
    private fun forceSystemWhitelistingViaReflection(context: Context): Boolean {
        Log.d(TAG, "尝试通过反射进行系统级白名单设置(xDrip方法)...")
        
        return try {
            // 获取ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            
            // 获取deviceidle服务
            val binder = getServiceMethod.invoke(null, "deviceidle") as IBinder?
            
            if (binder == null) {
                Log.e(TAG, "无法获取deviceidle服务binder")
                return false
            }
            
            Log.d(TAG, "成功获取deviceidle服务binder")
            
            // 调用addPowerSaveWhitelistApp方法
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            
            try {
                data.writeInterfaceToken("android.os.IDeviceIdleController")
                data.writeString(context.packageName)
                
                // 交易代码：FIRST_CALL_TRANSACTION = 1
                binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                reply.readException()
                
                Log.d(TAG, "通过反射成功调用addPowerSaveWhitelistApp")
                true
                
            } finally {
                data.recycle()
                reply.recycle()
            }
            
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "ServiceManager类未找到", e)
            false
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "getService方法未找到", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "反射错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 方法2：标准Android API
     */
    private fun forceStandardWhitelisting(context: Context): Boolean {
        Log.d(TAG, "尝试标准Android白名单设置...")
        
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
            if (powerManager == null) {
                Log.e(TAG, "无法获取PowerManager服务")
                return false
            }
            
            val packageName = context.packageName
            
            // 检查是否已经在白名单中
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "已在标准电池白名单中")
                return true
            }
            
            // 需要用户交互，在UI线程中执行
            if (context is Activity) {
                context.runOnUiThread {
                    showStandardWhitelistDialog(context)
                }
            } else {
                // 非Activity上下文，直接打开设置
                openBatteryOptimizationSettings(context)
            }
            
            // 返回true表示已触发设置流程
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "标准白名单设置错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 方法3：鸿蒙特定方法
     */
    private fun forceHarmonyWhitelisting(context: Context): Boolean {
        Log.d(TAG, "尝试鸿蒙系统特定白名单设置...")
        
        return try {
            // 尝试多种鸿蒙方法
            
            // 方法3.1：系统属性设置
            var success = setHarmonySystemProperties(context.packageName)
            
            // 方法3.2：特殊服务调用
            if (!success) {
                success = callHarmonySpecialService(context.packageName)
            }
            
            // 方法3.3：引导用户设置
            if (!success && context is Activity) {
                context.runOnUiThread {
                    showHarmonyWhitelistGuide(context)
                }
                success = true // 已触发引导流程
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "鸿蒙白名单设置错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 设置鸿蒙系统属性
     */
    private fun setHarmonySystemProperties(packageName: String): Boolean {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val setMethod = systemProperties.getMethod("set", String::class.java, String::class.java)
            
            val properties = arrayOf(
                "sys.power.whitelist_app",
                "persist.sys.power.whitelist_app",
                "hw_power.whitelist",
                "deviceidle.whitelist"
            )
            
            for (prop in properties) {
                try {
                    setMethod.invoke(null, prop, packageName)
                    Log.d(TAG, "设置鸿蒙属性: $prop = $packageName")
                } catch (e: Exception) {
                    Log.d(TAG, "设置属性失败: $prop")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.w(TAG, "设置鸿蒙系统属性失败", e)
            false
        }
    }
    
    /**
     * 调用鸿蒙特殊服务
     */
    private fun callHarmonySpecialService(packageName: String): Boolean {
        return try {
            // 尝试鸿蒙特有的服务名称
            val serviceNames = arrayOf(
                "deviceidle_harmony",
                "ohos.powermanager",
                "power_harmony"
            )
            
            for (serviceName in serviceNames) {
                try {
                    val serviceManager = Class.forName("android.os.ServiceManager")
                    val getService = serviceManager.getMethod("getService", String::class.java)
                    val binder = getService.invoke(null, serviceName) as IBinder?
                    
                    if (binder != null) {
                        Log.d(TAG, "发现鸿蒙服务: $serviceName")
                        return callHarmonyWhitelistMethod(binder, packageName)
                    }
                } catch (e: Exception) {
                    // 继续尝试下一个服务名
                }
            }
            
            false
            
        } catch (e: Exception) {
            Log.w(TAG, "调用鸿蒙服务错误", e)
            false
        }
    }
    
    /**
     * 调用鸿蒙白名单方法
     */
    private fun callHarmonyWhitelistMethod(binder: IBinder, packageName: String): Boolean {
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            
            // 尝试不同的接口描述符
            val descriptors = arrayOf(
                "ohos.powermanager.IDeviceIdleManager",
                "android.os.IDeviceIdleController",
                "huawei.power.IDeviceIdleController"
            )
            
            for (descriptor in descriptors) {
                try {
                    data.writeInterfaceToken(descriptor)
                    data.writeString(packageName)
                    
                    binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
                    reply.readException()
                    
                    Log.d(TAG, "成功调用鸿蒙白名单方法，描述符: $descriptor")
                    return true
                    
                } catch (e: Exception) {
                    // 重置Parcel，尝试下一个描述符
                    data.setDataPosition(0)
                    reply.setDataPosition(0)
                }
            }
            
            data.recycle()
            reply.recycle()
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "鸿蒙白名单方法调用错误", e)
            false
        }
    }
    
    /**
     * 内部检查是否在白名单中
     */
    private fun isInBatteryWhitelistInternal(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查白名单状态错误", e)
            false
        }
    }
    
    /**
     * 检查是否为鸿蒙系统
     */
    private fun isHarmonyOS(): Boolean {
        return try {
            // 方法1：检查Build属性
            val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                // 尝试检测鸿蒙
                try {
                    Class.forName("ohos.system.version.SystemVersion")
                    true
                } catch (e: ClassNotFoundException) {
                    // 不是鸿蒙或没有鸿蒙类
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            // 方法2：检查其他特征
            Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) && 
            Build.VERSION.SDK_INT >= 31 // 鸿蒙6.0+基于Android 12+
        }
    }
    
    /**
     * 显示标准白名单对话框
     */
    private fun showStandardWhitelistDialog(activity: Activity) {
        val prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        val shownCount = prefs.getInt("standard_dialog_shown", 0)
        
        if (shownCount < 2) { // 最多显示2次
            AlertDialog.Builder(activity)
                .setTitle("电池优化设置")
                .setMessage("为了确保AAPS能持续监控血糖数据，请允许忽略电池优化。\n\n" +
                           "系统将打开设置页面，请将AAPS设置为『不优化』或『允许后台活动』。")
                .setPositiveButton("去设置") { dialog, which ->
                    openBatteryOptimizationSettings(activity)
                    prefs.edit().putInt("standard_dialog_shown", shownCount + 1).apply()
                }
                .setNegativeButton("稍后") { dialog, which ->
                    prefs.edit().putInt("standard_dialog_shown", shownCount + 1).apply()
                }
                .setCancelable(false)
                .show()
        } else {
            // 直接打开设置，不再显示对话框
            openBatteryOptimizationSettings(activity)
        }
    }
    
    /**
     * 显示鸿蒙白名单引导
     */
    private fun showHarmonyWhitelistGuide(activity: Activity) {
        val prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        val shownCount = prefs.getInt("harmony_guide_shown", 0)
        
        if (shownCount < 2) {
            AlertDialog.Builder(activity)
                .setTitle("鸿蒙系统设置")
                .setMessage("请在鸿蒙系统中进行以下设置：\n\n" +
                           "1. 进入『设置』>『应用』>『应用启动管理』\n" +
                           "2. 找到AAPS，关闭『自动管理』\n" +
                           "3. 手动开启『允许自启动』和『允许后台活动』\n\n" +
                           "点击确定查看详细教程")
                .setPositiveButton("确定") { dialog, which ->
                    // 打开鸿蒙设置页面
                    openHarmonyAppSettings(activity)
                    prefs.edit().putInt("harmony_guide_shown", shownCount + 1).apply()
                }
                .setNegativeButton("取消") { dialog, which ->
                    prefs.edit().putInt("harmony_guide_shown", shownCount + 1).apply()
                }
                .setCancelable(false)
                .show()
        } else {
            openHarmonyAppSettings(activity)
        }
    }
    
    /**
     * 打开电池优化设置
     */
    private fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:${context.packageName}")
            } else {
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = Uri.parse("package:${context.packageName}")
            }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "打开电池优化设置失败", e)
            openAppSettings(context)
        }
    }
    
    /**
     * 打开鸿蒙应用设置
     */
    private fun openHarmonyAppSettings(context: Context) {
        try {
            val intent = Intent()
            
            // 尝试鸿蒙特定的设置入口
            intent.action = "com.huawei.systemmanager.optimize.process.ProtectActivity"
            
            if (intent.resolveActivity(context.packageManager) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            
            // 备用：标准应用设置
            openAppSettings(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "打开鸿蒙应用设置失败", e)
            openAppSettings(context)
        }
    }
    
    /**
     * 打开标准应用设置
     */
    private fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "打开应用设置失败", e)
        }
    }
    
    /**
     * 处理白名单结果
     */
    private fun handleWhitelistResult(context: Context, success: Boolean) {
        // 记录结果
        val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        
        var totalAttempts = prefs.getInt("total_attempts", 0)
        var successfulAttempts = prefs.getInt("successful_attempts", 0)
        
        totalAttempts++
        if (success) {
            successfulAttempts++
            showToast(context, "已成功设置电池优化白名单")
        } else {
            showToast(context, "自动设置失败，请手动设置")
        }
        
        // 保存记录
        val history = prefs.getString("attempt_history", "") ?: ""
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        val record = "[${sdf.format(Date())}] ${if (success) "SUCCESS" else "FAILED"}\n"
        
        // 保留最近10条记录
        val records = (record + history).split("\n").take(10)
        
        prefs.edit()
            .putInt("total_attempts", totalAttempts)
            .putInt("successful_attempts", successfulAttempts)
            .putString("attempt_history", records.joinToString("\n"))
            .putLong("last_attempt", System.currentTimeMillis())
            .putBoolean("last_success", success)
            .apply()
        
        Log.d(TAG, "白名单尝试记录: ${if (success) "成功" else "失败"}")
    }
    
    /**
     * 验证并记录最终状态
     */
    private fun verifyAndLogFinalStatus(context: Context) {
        Thread {
            try {
                // 等待系统更新
                Thread.sleep(2000)
                
                // 检查最终状态
                val finalStatus = isInBatteryWhitelistInternal(context)
                
                // 记录详细状态
                val status = StringBuilder()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                status.append("=== 电池白名单最终状态 ===\n")
                status.append("时间: ${sdf.format(Date())}\n")
                status.append("包名: ${context.packageName}\n")
                status.append("制造商: ${Build.MANUFACTURER}\n")
                status.append("型号: ${Build.MODEL}\n")
                status.append("Android: ${Build.VERSION.RELEASE}\n")
                status.append("鸿蒙系统: ${if (isHarmonyOS()) "是" else "否"}\n")
                status.append("在白名单: ${if (finalStatus) "是" else "否"}\n")
                
                Log.d(TAG, status.toString())
                
                // 保存状态日志
                val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("last_status_log", status.toString())
                    .putLong("last_status_check", System.currentTimeMillis())
                    .putBoolean("last_in_whitelist", finalStatus)
                    .apply()
                
                if (!finalStatus && context is Activity) {
                    // 如果最终不在白名单中，显示重要警告
                    context.runOnUiThread {
                        showCriticalWarning(context)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "最终状态验证错误", e)
            }
        }.start()
    }
    
    /**
     * 显示关键警告
     */
    private fun showCriticalWarning(activity: Activity) {
        val prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        val warningCount = prefs.getInt("critical_warning_shown", 0)
        
        if (warningCount < 1) { // 只显示一次
            AlertDialog.Builder(activity)
                .setTitle("⚠️ 重要警告")
                .setMessage("AAPS未在电池优化白名单中！\n\n" +
                           "这可能导致：\n" +
                           "• 血糖数据接收延迟\n" +
                           "• 后台运行被限制\n" +
                           "• 可能错过重要警报\n\n" +
                           "强烈建议立即设置。")
                .setPositiveButton("立即设置") { dialog, which ->
                    openBatteryOptimizationSettings(activity)
                    prefs.edit().putInt("critical_warning_shown", warningCount + 1).apply()
                }
                .setNegativeButton("了解风险") { dialog, which ->
                    prefs.edit().putInt("critical_warning_shown", warningCount + 1).apply()
                }
                .setCancelable(false)
                .show()
        }
    }
    
    /**
     * 记录当前状态
     */
    private fun logCurrentStatus(context: Context) {
        val log = StringBuilder()
        log.append("=== 开始白名单处理 ===\n")
        log.append("应用: ${context.packageName}\n")
        log.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        log.append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        log.append("鸿蒙系统: ${isHarmonyOS()}\n")
        log.append("初始白名单状态: ${if (isInBatteryWhitelistInternal(context)) "在" else "不在"}\n")
        
        Log.d(TAG, log.toString())
    }
    
    /**
     * 显示Toast消息
     */
    private fun showToast(context: Context, message: String) {
        if (context is Activity) {
            context.runOnUiThread {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        } else {
            // 使用应用上下文显示Toast
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 获取调试信息（用于调试界面）
     */
    fun getDebugInfo(context: Context): String {
        val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        
        val info = StringBuilder()
        info.append("=== 电池白名单调试信息 ===\n\n")
        
        // 基本信息
        info.append("应用包名: ${context.packageName}\n")
        info.append("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        info.append("系统版本: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        info.append("鸿蒙系统: ${if (isHarmonyOS()) "是" else "否"}\n\n")
        
        // 当前状态
        info.append("当前白名单状态: ${if (isInBatteryWhitelistInternal(context)) "✅ 已加入" else "❌ 未加入"}\n\n")
        
        // 统计信息
        info.append("=== 统计信息 ===\n")
        info.append("总尝试次数: ${prefs.getInt("total_attempts", 0)}\n")
        info.append("成功次数: ${prefs.getInt("successful_attempts", 0)}\n")
        
        val lastAttempt = prefs.getLong("last_attempt", 0)
        if (lastAttempt > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            info.append("最后尝试: ${sdf.format(Date(lastAttempt))}\n")
            info.append("最后结果: ${if (prefs.getBoolean("last_success", false)) "成功" else "失败"}\n")
        }
        
        info.append("\n=== 最近记录 ===\n")
        val history = prefs.getString("attempt_history", "无记录") ?: "无记录"
        info.append(history)
        
        return info.toString()
    }
    
    /**
     * 复制调试信息到剪贴板
     */
    fun copyDebugInfoToClipboard(context: Context) {
        val debugInfo = getDebugInfo(context)
        
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (clipboard != null) {
            val clip = ClipData.newPlainText("AAPS白名单调试信息", debugInfo)
            clipboard.setPrimaryClip(clip)
            showToast(context, "调试信息已复制到剪贴板")
        }
    }
    
    /**
     * 检查并更新白名单状态（供Service等非Activity组件调用）
     */
    fun checkAndUpdateWhitelist(context: Context) {
        Thread {
            try {
                val inWhitelist = isInBatteryWhitelistInternal(context)
                
                if (!inWhitelist) {
                    Log.w(TAG, "应用不在电池白名单中，尝试添加...")
                    forceWhitelisting(context)
                } else {
                    Log.d(TAG, "应用在电池白名单中 - 良好！")
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新白名单错误", e)
            }
        }.start()
    }
    
    /**
     * 简化调用：在Activity中只需要这一行代码
     * 使用方式：BatteryWhitelistEnforcer.simpleForce(this)
     */
    fun simpleForce(context: Context) {
        Log.d(TAG, "简单强制白名单调用")
        
        Thread {
            try {
                // 快速检查是否已在白名单
                if (isInBatteryWhitelistInternal(context)) {
                    return@Thread // 已经在白名单中，无需操作
                }
                
                // 尝试系统级方法（不显示任何UI）
                val success = forceSystemWhitelistingViaReflection(context)
                
                // 如果不成功且是Activity，显示简单提示
                if (!success && context is Activity) {
                    context.runOnUiThread {
                        AlertDialog.Builder(context)
                            .setTitle("电池优化设置")
                            .setMessage("AAPS需要后台运行权限以持续监控血糖。请允许忽略电池优化。")
                            .setPositiveButton("去设置") { dialog, which ->
                                openBatteryOptimizationSettings(context)
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "简单强制白名单错误", e)
            }
        }.start()
    }
    
    /**
     * 获取当前白名单状态
     */
    fun isInBatteryWhitelist(context: Context): Boolean {
        return isInBatteryWhitelistInternal(context)
    }
}
