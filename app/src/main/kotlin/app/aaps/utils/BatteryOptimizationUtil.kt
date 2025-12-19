package app.aaps.utils

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
// 关键：导入你的应用资源R类
import app.aaps.core.R


object BatteryOptimizationUtil {

    private const val TAG = "BatteryOptimizationUtil"
    private const val PREFS_KEY_HAS_REQUESTED = "has_requested_battery_optimization_v2" // 使用新键名，避免冲突

    /**
     * 检查并请求忽略电池优化（仅在首次且必要时）
     * @return true 表示已拥有权限或无需请求；false 表示需要请求并已弹出对话框
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun checkAndRequestIfNeeded(activity: Activity): Boolean {
        val packageName = activity.packageName
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 检查是否已拥有权限
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return true // 已有权限，无需处理
        }

        // 2. 检查是否已经请求过（防骚扰关键）
        val prefs = activity.getSharedPreferences("prefs_battery", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_KEY_HAS_REQUESTED, false)) {
            Log.i(TAG, "Already requested battery optimization before, skipping.")
            return false // 已请求过，不再弹窗，但返回false表示无权限
        }

        // 3. 标记为已请求（即使本次用户取消，也避免频繁弹窗）
        prefs.edit().putBoolean(PREFS_KEY_HAS_REQUESTED, true).apply()

        // 4. 弹出解释对话框并引导至系统设置
        showRequestDialog(activity, packageName)
        return false // 表示已触发请求流程
    }

    /**
     * 直接检查当前是否拥有权限
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 显示请求对话框（移植自xDrip的对话框逻辑）
     */
    private fun showRequestDialog(activity: Activity, packageName: String) {
        // 使用与AndroidAPS UI风格一致的对话框，例如AlertDialog
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.please_allow_permission)) // 请在strings.xml定义
            .setMessage(activity.getString(R.string.aaps_needs_whitelisting_for_proper_performance)) // 请在strings.xml定义
            .setPositiveButton(activity.getString(R.string.go_to_settings)) { dialog, _ ->
                dialog.dismiss()
                requestIgnoreBatteryOptimizations(activity, packageName)
            }
            .setNegativeButton(activity.getString(R.string.later)) { dialog, _ ->
                dialog.dismiss()
                // 用户选择稍后，可记录日志或什么都不做
                Log.i(TAG, "User postponed battery optimization request.")
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 调用系统API打开“忽略电池优化”设置页（核心方法，移植自xDrip）
     */
    private fun requestIgnoreBatteryOptimizations(activity: Activity, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            // 使用一个合理的请求码，以便在onActivityResult中处理（可选）
            activity.startActivityForResult(intent, 1001)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Battery optimization setting not found on this device", e)
            // 可选：显示一个更详细的引导，告诉用户如何手动设置
            // 例如：跳转到应用详情页 Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }
}
