package app.aaps.utils // 请保持与你的项目包名一致

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

object BatteryOptimizationUtil {

    private const val TAG = "BatteryOptimizationUtil"
    private const val PREFS_KEY_HAS_REQUESTED = "has_requested_battery_optimization_v2"

    /**
     * 检查并请求忽略电池优化（仅在首次且必要时）
     * @return true:已拥有权限; false:无权限(可能已弹窗请求)
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun checkAndRequestIfNeeded(
        activity: Activity,
        dialogTitle: String,
        dialogMessage: String,
        positiveButtonText: String,
        negativeButtonText: String
    ): Boolean {
        val packageName = activity.packageName
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 已有权限，直接返回
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Permission already granted.")
            return true
        }

        // 2. 已请求过，不再弹窗
        val prefs = activity.getSharedPreferences("aaps_battery_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_KEY_HAS_REQUESTED, false)) {
            Log.i(TAG, "Already requested before, skipping.")
            return false
        }

        // 3. 标记并弹窗
        prefs.edit().putBoolean(PREFS_KEY_HAS_REQUESTED, true).apply()
        showRequestDialog(activity, packageName, dialogTitle, dialogMessage, positiveButtonText, negativeButtonText)
        return false
    }

    /**
     * 直接权限检查
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 显示请求对话框
     */
    private fun showRequestDialog(
        activity: Activity,
        packageName: String,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String
    ) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { dialog, _ ->
                dialog.dismiss()
                requestIgnoreBatteryOptimizations(activity, packageName)
            }
            .setNegativeButton(negativeText) { dialog, _ ->
                dialog.dismiss()
                Log.i(TAG, "User postponed.")
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 跳转系统设置页
     */
    private fun requestIgnoreBatteryOptimizations(activity: Activity, packageName: String) {
        try {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                activity.startActivityForResult(this, 1001)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "System setting not found", e)
            // 降级：跳转应用详情页
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                activity.startActivity(this)
            }
        }
    }
}
