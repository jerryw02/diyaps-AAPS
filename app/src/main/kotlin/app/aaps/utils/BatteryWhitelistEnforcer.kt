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
 * 电池优化白名单强制执行器 - Kotlin版本
 * 参考 xDrip 的 JoH.forceBatteryWhitelisting() 实现
 * 优化弹窗显示顺序，避免混乱和空白弹窗
 */
object BatteryWhitelistEnforcer {
    
    private const val TAG = "BatteryWhitelist"
    
    // 使用Handler确保UI操作在主线程
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 用于控制弹窗显示顺序
    private var isShowingDialog = false
    private val dialogQueue = mutableListOf<DialogTask>()
    
    /**
     * 主方法：智能白名单处理
     * 优化弹窗显示，避免混乱
     */
    fun smartWhitelist(context: Context) {
        Log.d(TAG, "开始智能白名单处理...")
        
        // 在主线程执行检查
        mainHandler.post {
            checkAndHandleWhitelist(context)
        }
    }
    
    /**
     * 检查并处理白名单（在主线程执行）
     */
    private fun checkAndHandleWhitelist(context: Context) {
        try {
            // 1. 检查是否已经在白名单中
            if (isInBatteryWhitelist(context)) {
                Log.d(TAG, "已在电池白名单中")
                // 只显示一次提示，避免重复
                if (!hasShownSuccessToast()) {
                    showToast(context, "✅ 已在电池优化白名单中")
                    markSuccessToastShown()
                }
                return
            }
            
            Log.d(TAG, "不在电池白名单中，开始处理流程")
            
            // 2. 记录状态
            logCurrentStatus(context)
            
            // 3. 按顺序执行处理流程
            executeWhitelistFlow(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "白名单处理错误: ${e.message}", e)
        }
    }
    
    /**
     * 执行白名单处理流程（按顺序）
     */
    private fun executeWhitelistFlow(context: Context) {
        if (context !is Activity) {
            Log.e(TAG, "上下文不是Activity，无法显示弹窗")
            return
        }
        
        // 获取弹窗显示次数
        val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
        val totalDialogsShown = prefs.getInt("total_dialogs_shown", 0)
        
        // 如果已经显示过3次弹窗，直接打开设置
        if (totalDialogsShown >= 3) {
            Log.d(TAG, "已显示过3次弹窗，直接打开设置")
            openBatteryOptimizationSettings(context)
            return
        }
        
        // 显示引导弹窗
        showGuidedDialog(context, totalDialogsShown)
    }
    
    /**
     * 显示引导弹窗（主入口）
     */
    private fun showGuidedDialog(context: Activity, dialogCount: Int) {
        if (isShowingDialog) {
            Log.d(TAG, "已有弹窗正在显示，加入队列")
            dialogQueue.add(DialogTask(context, dialogCount, DialogType.GUIDED))
            return
        }
        
        isShowingDialog = true
        
        val dialogMessage = when (dialogCount) {
            0 -> "为了确保AAPS能持续监控血糖数据，需要加入电池优化白名单。\n\n" +
                 "请允许AAPS忽略电池优化，以防止系统在后台停止应用。"
            1 -> "检测到AAPS不在电池优化白名单中，这可能导致：\n\n" +
                 "• 血糖数据接收延迟\n" +
                 "• 后台运行被限制\n" +
                 "• 错过重要警报\n\n" +
                 "建议立即设置。"
            else -> "AAPS需要后台运行权限以保证血糖数据实时接收。\n\n" +
                   "请将AAPS设置为『不优化』或『允许后台活动』。"
        }
        
        AlertDialog.Builder(context)
            .setTitle(when (dialogCount) {
                0 -> "电池优化设置"
                1 -> "⚠️ 重要提醒"
                else -> "电池优化"
            })
            .setMessage(dialogMessage)
            .setPositiveButton("去设置") { dialog, which ->
                // 记录弹窗显示次数
                val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
                prefs.edit().putInt("total_dialogs_shown", dialogCount + 1).apply()
                
                // 关闭当前弹窗
                dialog.dismiss()
                isShowingDialog = false
                
                // 处理下一个弹窗（如果有）
                processNextDialog()
                
                // 打开设置
                openBatteryOptimizationSettings(context)
            }
            .setNegativeButton("稍后") { dialog, which ->
                // 记录弹窗显示次数
                val prefs = context.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE)
                prefs.edit().putInt("total_dialogs_shown", dialogCount + 1).apply()
                
                // 关闭当前弹窗
                dialog.dismiss()
                isShowingDialog = false
                
                // 处理下一个弹窗（如果有）
                processNextDialog()
                
                // 显示提示
                showToast(context, "请稍后在设置中手动开启")
            }
            .setOnDismissListener {
                isShowingDialog = false
                processNextDialog()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 处理下一个弹窗
     */
    private fun processNextDialog() {
        if (dialogQueue.isNotEmpty()) {
            val task = dialogQueue.removeAt(0)
            when (task.type) {
                DialogType.GUIDED -> showGuidedDialog(task.context as Activity, task.dialogCount)
                // 可以添加其他类型的弹窗处理
            }
        }
    }
    
    /**
     * 打开电池优化设置
     */
    private fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:${context.packageName}")
                } else {
                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    data = Uri.parse("package:${context.packageName}")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            Log.d(TAG, "已打开电池优化设置")
            
        } catch (e: Exception) {
            Log.e(TAG, "打开电池优化设置失败", e)
            // 显示错误提示
            mainHandler.post {
                if (context is Activity) {
                    AlertDialog.Builder(context)
                        .setTitle("设置打开失败")
                        .setMessage("无法打开电池优化设置页面，请手动前往：\n设置 > 应用 > AAPS > 电池 > 允许后台活动")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }
    
    /**
     * 检查是否在电池优化白名单中
     */
    fun isInBatteryWhitelist(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } else {
                true // Android 6.0以下不需要
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查白名单状态错误", e)
            false
        }
    }
    
    /**
     * 记录当前状态
     */
    private fun logCurrentStatus(context: Context) {
        val log = """
            === 白名单状态检查 ===
            应用: ${context.packageName}
            设备: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            在白名单: ${isInBatteryWhitelist(context)}
        """.trimIndent()
        
        Log.d(TAG, log)
    }
    
    /**
     * 显示Toast消息（防重复）
     */
    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            // 避免频繁显示Toast
            if (shouldShowToast(context, message)) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                recordToastShown(context, message)
            }
        }
    }
    
    /**
     * 判断是否应该显示Toast
     */
    private fun shouldShowToast(context: Context, message: String): Boolean {
        val prefs = context.getSharedPreferences("aaps_toast", Context.MODE_PRIVATE)
        val lastShownTime = prefs.getLong("last_toast_time_${message.hashCode()}", 0)
        val currentTime = System.currentTimeMillis()
        
        // 同一消息至少间隔30秒才显示
        return currentTime - lastShownTime > 30000
    }
    
    /**
     * 记录Toast显示时间
     */
    private fun recordToastShown(context: Context, message: String) {
        val prefs = context.getSharedPreferences("aaps_toast", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_toast_time_${message.hashCode()}", System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 检查是否已经显示过成功Toast
     */
    private fun hasShownSuccessToast(): Boolean {
        // 这里可以扩展为检查SharedPreferences
        return false
    }
    
    /**
     * 标记成功Toast已显示
     */
    private fun markSuccessToastShown() {
        // 这里可以扩展为记录到SharedPreferences
    }
    
    /**
     * 简化调用方法（推荐使用）
     * 在MainActivity中使用：BatteryWhitelistEnforcer.smartCall(this)
     */
    fun smartCall(context: Context) {
        smartWhitelist(context)
    }
    
    /**
     * 静默检查（不显示弹窗）
     * 适合在Service中调用
     */
    fun silentCheck(context: Context): Boolean {
        return isInBatteryWhitelist(context)
    }
    
    /**
     * 强制检查并提示（如果需要）
     * 返回是否在白名单中
     */
    fun checkAndNotify(context: Context): Boolean {
        val inWhitelist = isInBatteryWhitelist(context)
        
        if (!inWhitelist && context is Activity) {
            // 延迟显示，避免与其他UI冲突
            mainHandler.postDelayed({
                showGuidedDialog(context, 0)
            }, 1000) // 延迟1秒显示
        }
        
        return inWhitelist
    }
}

/**
 * 弹窗任务数据类
 */
private data class DialogTask(
    val context: Context,
    val dialogCount: Int,
    val type: DialogType
)

/**
 * 弹窗类型枚举
 */
private enum class DialogType {
    GUIDED
}
