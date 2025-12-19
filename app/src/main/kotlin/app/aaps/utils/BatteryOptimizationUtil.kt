package app.aaps.utils // 确保这是正确的包名

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
     * @param activity 当前的Activity
     * @param titleResId 对话框标题的资源ID
     * @param messageResId 对话框内容的资源ID
     * @param positiveBtnResId 确定按钮文本的资源ID
     * @param negativeBtnResId 取消按钮文本的资源ID
     * @return true 表示已拥有权限或无需请求；false 表示需要请求并已弹出对话框
     */
    @RequiresApi(Build.VERSION_CODES.M)
    fun checkAndRequestIfNeeded(
        activity: Activity,
        titleResId: Int,
        messageResId: Int,
        positiveBtnResId: Int,
        negativeBtnResId: Int
    ): Boolean {
        val packageName = activity.packageName
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 检查是否已拥有权限
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return true
        }

        // 2. 检查是否已经请求过
        val prefs = activity.getSharedPreferences("aaps_battery_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_KEY_HAS_REQUESTED, false)) {
            Log.i(TAG, "Already requested battery optimization, skipping.")
            return false
        }

        // 3. 标记为已请求
        prefs.edit().putBoolean(PREFS_KEY_HAS_REQUESTED, true).apply()

        // 4. 弹出解释对话框
        showRequestDialog(activity, packageName, titleResId, messageResId, positiveBtnResId, negativeBtnResId)
        return false
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun showRequestDialog(
        activity: Activity,
        packageName: String,
        titleResId: Int,
        messageResId: Int,
        positiveBtnResId: Int,
        negativeBtnResId: Int
    ) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(titleResId))
            .setMessage(activity.getString(messageResId))
            .setPositiveButton(activity.getString(positiveBtnResId)) { dialog, _ ->
                dialog.dismiss()
                requestIgnoreBatteryOptimizations(activity, packageName)
            }
            .setNegativeButton(activity.getString(negativeBtnResId)) { dialog, _ ->
                dialog.dismiss()
                Log.i(TAG, "User postponed battery optimization request.")
            }
            .setCancelable(false)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations(activity: Activity, packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            activity.startActivityForResult(intent, 1001)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Battery optimization setting not found", e)
            // 备选方案：跳转到应用详情页
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            activity.startActivity(intent)
        }
    }
}
