package app.aaps.utils // 请保持与你的项目包名一致

import android.app.Activity;
import android.app.AlertDialog;
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
 * 电池白名单强制执行器
 * 封装所有复杂逻辑，提供简单接口
 * 参考 xDrip 的 JoH.forceBatteryWhitelisting() 设计
 */
/**
 * 电池白名单强制执行器 - 纯Java版本
 * 避免使用Kotlin反射，解决KSP编译问题
 */
public class BatteryWhitelistEnforcer {
    private static final String TAG = "BatteryWhitelist";
    
    // 单例实例
    private static BatteryWhitelistEnforcer instance;
    
    // 上下文引用
    private Context appContext;
    
    // 私有构造函数
    private BatteryWhitelistEnforcer(Context context) {
        this.appContext = context.getApplicationContext();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized BatteryWhitelistEnforcer getInstance(Context context) {
        if (instance == null) {
            instance = new BatteryWhitelistEnforcer(context);
        }
        return instance;
    }
    
    /**
     * 主方法：强制加入电池优化白名单
     * 使用方式：BatteryWhitelistEnforcer.getInstance(this).forceWhitelisting();
     */
    public void forceWhitelisting() {
        Log.d(TAG, "开始强制电池白名单处理...");
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. 先检查是否已经在白名单中
                    if (isInBatteryWhitelistInternal()) {
                        Log.d(TAG, "已在电池白名单中，跳过");
                        showToast("已在电池优化白名单中");
                        return;
                    }
                    
                    // 2. 记录当前状态
                    logCurrentStatus();
                    
                    // 3. 尝试多种方法（按优先级）
                    boolean success = false;
                    
                    // 方法1：系统级反射调用（xDrip方式）
                    success = forceSystemWhitelistingViaReflection();
                    
                    // 方法2：标准Android API
                    if (!success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        success = forceStandardWhitelisting();
                    }
                    
                    // 方法3：鸿蒙特定方法
                    if (!success && isHarmonyOS()) {
                        success = forceHarmonyWhitelisting();
                    }
                    
                    // 4. 处理结果
                    handleWhitelistResult(success);
                    
                    // 5. 验证最终状态
                    verifyAndLogFinalStatus();
                    
                } catch (Exception e) {
                    Log.e(TAG, "白名单处理错误: " + e.getMessage(), e);
                    showToast("白名单设置失败，请手动设置");
                }
            }
        }).start();
    }
    
    /**
     * 方法1：系统级反射调用（xDrip核心方法）
     */
    private boolean forceSystemWhitelistingViaReflection() {
        Log.d(TAG, "尝试通过反射进行系统级白名单设置(xDrip方法)...");
        
        try {
            // 获取ServiceManager
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            
            // 获取deviceidle服务
            IBinder binder = (IBinder) getServiceMethod.invoke(null, "deviceidle");
            
            if (binder == null) {
                Log.e(TAG, "无法获取deviceidle服务binder");
                return false;
            }
            
            Log.d(TAG, "成功获取deviceidle服务binder");
            
            // 调用addPowerSaveWhitelistApp方法
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            
            try {
                data.writeInterfaceToken("android.os.IDeviceIdleController");
                data.writeString(appContext.getPackageName());
                
                // 交易代码：FIRST_CALL_TRANSACTION = 1
                binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                reply.readException();
                
                Log.d(TAG, "通过反射成功调用addPowerSaveWhitelistApp");
                return true;
                
            } finally {
                data.recycle();
                reply.recycle();
            }
            
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ServiceManager类未找到", e);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "getService方法未找到", e);
        } catch (Exception e) {
            Log.e(TAG, "反射错误: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    /**
     * 方法2：标准Android API
     */
    private boolean forceStandardWhitelisting() {
        Log.d(TAG, "尝试标准Android白名单设置...");
        
        try {
            PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                Log.e(TAG, "无法获取PowerManager服务");
                return false;
            }
            
            String packageName = appContext.getPackageName();
            
            // 检查是否已经在白名单中
            if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "已在标准电池白名单中");
                return true;
            }
            
            // 需要用户交互，在UI线程中执行
            if (appContext instanceof Activity) {
                final Activity activity = (Activity) appContext;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showStandardWhitelistDialog(activity);
                    }
                });
            } else {
                // 非Activity上下文，直接打开设置
                openBatteryOptimizationSettings();
            }
            
            // 返回true表示已触发设置流程
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "标准白名单设置错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 方法3：鸿蒙特定方法
     */
    private boolean forceHarmonyWhitelisting() {
        Log.d(TAG, "尝试鸿蒙系统特定白名单设置...");
        
        try {
            // 尝试多种鸿蒙方法
            
            // 方法3.1：系统属性设置
            boolean success = setHarmonySystemProperties();
            
            // 方法3.2：特殊服务调用
            if (!success) {
                success = callHarmonySpecialService();
            }
            
            // 方法3.3：引导用户设置
            if (!success && appContext instanceof Activity) {
                final Activity activity = (Activity) appContext;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showHarmonyWhitelistGuide(activity);
                    }
                });
                success = true; // 已触发引导流程
            }
            
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "鸿蒙白名单设置错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 设置鸿蒙系统属性
     */
    private boolean setHarmonySystemProperties() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method setMethod = systemProperties.getMethod("set", String.class, String.class);
            
            String packageName = appContext.getPackageName();
            String[] properties = {
                "sys.power.whitelist_app",
                "persist.sys.power.whitelist_app",
                "hw_power.whitelist",
                "deviceidle.whitelist"
            };
            
            for (String prop : properties) {
                try {
                    setMethod.invoke(null, prop, packageName);
                    Log.d(TAG, "设置鸿蒙属性: " + prop + " = " + packageName);
                } catch (Exception e) {
                    Log.d(TAG, "设置属性失败: " + prop);
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.w(TAG, "设置鸿蒙系统属性失败", e);
            return false;
        }
    }
    
    /**
     * 调用鸿蒙特殊服务
     */
    private boolean callHarmonySpecialService() {
        try {
            // 尝试鸿蒙特有的服务名称
            String[] serviceNames = {
                "deviceidle_harmony",
                "ohos.powermanager",
                "power_harmony"
            };
            
            for (String serviceName : serviceNames) {
                try {
                    Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                    Method getService = serviceManager.getMethod("getService", String.class);
                    IBinder binder = (IBinder) getService.invoke(null, serviceName);
                    
                    if (binder != null) {
                        Log.d(TAG, "发现鸿蒙服务: " + serviceName);
                        return callHarmonyWhitelistMethod(binder);
                    }
                } catch (Exception e) {
                    // 继续尝试下一个服务名
                }
            }
            
            return false;
            
        } catch (Exception e) {
            Log.w(TAG, "调用鸿蒙服务错误", e);
            return false;
        }
    }
    
    /**
     * 调用鸿蒙白名单方法
     */
    private boolean callHarmonyWhitelistMethod(IBinder binder) {
        try {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            
            // 尝试不同的接口描述符
            String[] descriptors = {
                "ohos.powermanager.IDeviceIdleManager",
                "android.os.IDeviceIdleController",
                "huawei.power.IDeviceIdleController"
            };
            
            for (String descriptor : descriptors) {
                try {
                    data.writeInterfaceToken(descriptor);
                    data.writeString(appContext.getPackageName());
                    
                    binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0);
                    reply.readException();
                    
                    Log.d(TAG, "成功调用鸿蒙白名单方法，描述符: " + descriptor);
                    return true;
                    
                } catch (Exception e) {
                    // 重置Parcel，尝试下一个描述符
                    data.setDataPosition(0);
                    reply.setDataPosition(0);
                }
            }
            
            data.recycle();
            reply.recycle();
            
        } catch (Exception e) {
            Log.e(TAG, "鸿蒙白名单方法调用错误", e);
        }
        
        return false;
    }
    
    /**
     * 内部检查是否在白名单中
     */
    private boolean isInBatteryWhitelistInternal() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    return powerManager.isIgnoringBatteryOptimizations(appContext.getPackageName());
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "检查白名单状态错误", e);
            return false;
        }
    }
    
    /**
     * 检查是否为鸿蒙系统
     */
    private boolean isHarmonyOS() {
        try {
            // 方法1：检查Build属性
            String manufacturer = Build.MANUFACTURER.toLowerCase();
            if (manufacturer.contains("huawei") || manufacturer.contains("honor")) {
                // 尝试检测鸿蒙
                try {
                    Class.forName("ohos.system.version.SystemVersion");
                    return true;
                } catch (ClassNotFoundException e) {
                    // 不是鸿蒙或没有鸿蒙类
                }
            }
            return false;
        } catch (Exception e) {
            // 方法2：检查其他特征
            return Build.MANUFACTURER.equalsIgnoreCase("HUAWEI") && 
                   Build.VERSION.SDK_INT >= 31; // 鸿蒙6.0+基于Android 12+
        }
    }
    
    /**
     * 显示标准白名单对话框
     */
    private void showStandardWhitelistDialog(final Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
        final int shownCount = prefs.getInt("standard_dialog_shown", 0);
        
        if (shownCount < 2) { // 最多显示2次
            new AlertDialog.Builder(activity)
                .setTitle("电池优化设置")
                .setMessage("为了确保AAPS能持续监控血糖数据，请允许忽略电池优化。\n\n" +
                           "系统将打开设置页面，请将AAPS设置为『不优化』或『允许后台活动』。")
                .setPositiveButton("去设置", (dialog, which) -> {
                    openBatteryOptimizationSettings();
                    prefs.edit().putInt("standard_dialog_shown", shownCount + 1).apply();
                })
                .setNegativeButton("稍后", (dialog, which) -> {
                    prefs.edit().putInt("standard_dialog_shown", shownCount + 1).apply();
                })
                .setCancelable(false)
                .show();
        } else {
            // 直接打开设置，不再显示对话框
            openBatteryOptimizationSettings();
        }
    }
    
    /**
     * 显示鸿蒙白名单引导
     */
    private void showHarmonyWhitelistGuide(final Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
        final int shownCount = prefs.getInt("harmony_guide_shown", 0);
        
        if (shownCount < 2) {
            new AlertDialog.Builder(activity)
                .setTitle("鸿蒙系统设置")
                .setMessage("请在鸿蒙系统中进行以下设置：\n\n" +
                           "1. 进入『设置』>『应用』>『应用启动管理』\n" +
                           "2. 找到AAPS，关闭『自动管理』\n" +
                           "3. 手动开启『允许自启动』和『允许后台活动』\n\n" +
                           "点击确定查看详细教程")
                .setPositiveButton("确定", (dialog, which) -> {
                    // 打开鸿蒙设置页面
                    openHarmonyAppSettings();
                    prefs.edit().putInt("harmony_guide_shown", shownCount + 1).apply();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    prefs.edit().putInt("harmony_guide_shown", shownCount + 1).apply();
                })
                .setCancelable(false)
                .show();
        } else {
            openHarmonyAppSettings();
        }
    }
    
    /**
     * 打开电池优化设置
     */
    private void openBatteryOptimizationSettings() {
        try {
            Intent intent = new Intent();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + appContext.getPackageName()));
            } else {
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + appContext.getPackageName()));
            }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
            
        } catch (Exception e) {
            Log.e(TAG, "打开电池优化设置失败", e);
            openAppSettings();
        }
    }
    
    /**
     * 打开鸿蒙应用设置
     */
    private void openHarmonyAppSettings() {
        try {
            Intent intent = new Intent();
            
            // 尝试鸿蒙特定的设置入口
            intent.setAction("com.huawei.systemmanager.optimize.process.ProtectActivity");
            
            if (intent.resolveActivity(appContext.getPackageManager()) != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
                return;
            }
            
            // 备用：标准应用设置
            openAppSettings();
            
        } catch (Exception e) {
            Log.e(TAG, "打开鸿蒙应用设置失败", e);
            openAppSettings();
        }
    }
    
    /**
     * 打开标准应用设置
     */
    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + appContext.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "打开应用设置失败", e);
        }
    }
    
    /**
     * 处理白名单结果
     */
    private void handleWhitelistResult(boolean success) {
        // 记录结果
        SharedPreferences prefs = appContext.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
        
        int totalAttempts = prefs.getInt("total_attempts", 0);
        int successfulAttempts = prefs.getInt("successful_attempts", 0);
        
        totalAttempts++;
        if (success) {
            successfulAttempts++;
            showToast("已成功设置电池优化白名单");
        } else {
            showToast("自动设置失败，请手动设置");
        }
        
        // 保存记录
        String history = prefs.getString("attempt_history", "");
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        String record = String.format(Locale.US, "[%s] %s\n",
            sdf.format(new Date()),
            success ? "SUCCESS" : "FAILED"
        );
        
        // 保留最近10条记录
        String[] records = (record + history).split("\n");
        List<String> recordList = new ArrayList<>();
        for (int i = 0; i < Math.min(records.length, 10); i++) {
            if (records[i] != null && !records[i].trim().isEmpty()) {
                recordList.add(records[i]);
            }
        }
        
        prefs.edit()
            .putInt("total_attempts", totalAttempts)
            .putInt("successful_attempts", successfulAttempts)
            .putString("attempt_history", joinStrings(recordList, "\n"))
            .putLong("last_attempt", System.currentTimeMillis())
            .putBoolean("last_success", success)
            .apply();
        
        Log.d(TAG, "白名单尝试记录: " + (success ? "成功" : "失败"));
    }
    
    /**
     * 字符串连接辅助方法
     */
    private String joinStrings(List<String> strings, String delimiter) {
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < strings.size(); i++) {
            sb.append(strings.get(i));
            if (i < strings.size() - 1) {
                sb.append(delimiter);
            }
        }
        return sb.toString();
    }
    
    /**
     * 验证并记录最终状态
     */
    private void verifyAndLogFinalStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 等待系统更新
                    Thread.sleep(2000);
                    
                    // 检查最终状态
                    boolean finalStatus = isInBatteryWhitelistInternal();
                    
                    // 记录详细状态
                    StringBuilder status = new StringBuilder();
                    status.append("=== 电池白名单最终状态 ===\n");
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    status.append("时间: ").append(sdf.format(new Date())).append("\n");
                    status.append("包名: ").append(appContext.getPackageName()).append("\n");
                    status.append("制造商: ").append(Build.MANUFACTURER).append("\n");
                    status.append("型号: ").append(Build.MODEL).append("\n");
                    status.append("Android: ").append(Build.VERSION.RELEASE).append("\n");
                    status.append("鸿蒙系统: ").append(isHarmonyOS() ? "是" : "否").append("\n");
                    status.append("在白名单: ").append(finalStatus ? "是" : "否").append("\n");
                    
                    Log.d(TAG, status.toString());
                    
                    // 保存状态日志
                    SharedPreferences prefs = appContext.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
                    prefs.edit()
                        .putString("last_status_log", status.toString())
                        .putLong("last_status_check", System.currentTimeMillis())
                        .putBoolean("last_in_whitelist", finalStatus)
                        .apply();
                    
                    if (!finalStatus && appContext instanceof Activity) {
                        // 如果最终不在白名单中，显示重要警告
                        final Activity activity = (Activity) appContext;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showCriticalWarning(activity);
                            }
                        });
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "最终状态验证错误", e);
                }
            }
        }).start();
    }
    
    /**
     * 显示关键警告
     */
    private void showCriticalWarning(final Activity activity) {
        final SharedPreferences prefs = activity.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
        final int warningCount = prefs.getInt("critical_warning_shown", 0);
        
        if (warningCount < 1) { // 只显示一次
            new AlertDialog.Builder(activity)
                .setTitle("⚠️ 重要警告")
                .setMessage("AAPS未在电池优化白名单中！\n\n" +
                           "这可能导致：\n" +
                           "• 血糖数据接收延迟\n" +
                           "• 后台运行被限制\n" +
                           "• 可能错过重要警报\n\n" +
                           "强烈建议立即设置。")
                .setPositiveButton("立即设置", (dialog, which) -> {
                    openBatteryOptimizationSettings();
                    prefs.edit().putInt("critical_warning_shown", warningCount + 1).apply();
                })
                .setNegativeButton("了解风险", (dialog, which) -> {
                    prefs.edit().putInt("critical_warning_shown", warningCount + 1).apply();
                })
                .setCancelable(false)
                .show();
        }
    }
    
    /**
     * 记录当前状态
     */
    private void logCurrentStatus() {
        StringBuilder log = new StringBuilder();
        log.append("=== 开始白名单处理 ===\n");
        log.append("应用: ").append(appContext.getPackageName()).append("\n");
        log.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        log.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        log.append("鸿蒙系统: ").append(isHarmonyOS()).append("\n");
        log.append("初始白名单状态: ").append(isInBatteryWhitelistInternal() ? "在" : "不在").append("\n");
        
        Log.d(TAG, log.toString());
    }
    
    /**
     * 显示Toast消息
     */
    private void showToast(final String message) {
        if (appContext instanceof Activity) {
            final Activity activity = (Activity) appContext;
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
        } else {
            // 使用应用上下文显示Toast需要特殊处理
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 获取调试信息（用于调试界面）
     */
    public String getDebugInfo() {
        SharedPreferences prefs = appContext.getSharedPreferences("aaps_whitelist", Context.MODE_PRIVATE);
        
        StringBuilder info = new StringBuilder();
        info.append("=== 电池白名单调试信息 ===\n\n");
        
        // 基本信息
        info.append("应用包名: ").append(appContext.getPackageName()).append("\n");
        info.append("设备型号: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        info.append("系统版本: Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        info.append("鸿蒙系统: ").append(isHarmonyOS() ? "是" : "否").append("\n\n");
        
        // 当前状态
        info.append("当前白名单状态: ").append(isInBatteryWhitelistInternal() ? "✅ 已加入" : "❌ 未加入").append("\n\n");
        
        // 统计信息
        info.append("=== 统计信息 ===\n");
        info.append("总尝试次数: ").append(prefs.getInt("total_attempts", 0)).append("\n");
        info.append("成功次数: ").append(prefs.getInt("successful_attempts", 0)).append("\n");
        
        long lastAttempt = prefs.getLong("last_attempt", 0);
        if (lastAttempt > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            info.append("最后尝试: ").append(sdf.format(new Date(lastAttempt))).append("\n");
            info.append("最后结果: ").append(prefs.getBoolean("last_success", false) ? "成功" : "失败").append("\n");
        }
        
        info.append("\n=== 最近记录 ===\n");
        String history = prefs.getString("attempt_history", "无记录");
        info.append(history);
        
        return info.toString();
    }
    
    /**
     * 复制调试信息到剪贴板
     */
    public void copyDebugInfoToClipboard() {
        String debugInfo = getDebugInfo();
        
        ClipboardManager clipboard = (ClipboardManager) appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("AAPS白名单调试信息", debugInfo);
            clipboard.setPrimaryClip(clip);
            showToast("调试信息已复制到剪贴板");
        }
    }
    
    /**
     * 检查并更新白名单状态（供Service等非Activity组件调用）
     */
    public void checkAndUpdateWhitelist() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean inWhitelist = isInBatteryWhitelistInternal();
                    
                    if (!inWhitelist) {
                        Log.w(TAG, "应用不在电池白名单中，尝试添加...");
                        forceWhitelisting();
                    } else {
                        Log.d(TAG, "应用在电池白名单中 - 良好！");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "检查更新白名单错误", e);
                }
            }
        }).start();
    }
    
    /**
     * 简化调用：在Activity中只需要这一行代码
     * 使用方式：BatteryWhitelistEnforcer.getInstance(this).simpleForce();
     */
    public void simpleForce() {
        Log.d(TAG, "简单强制白名单调用");
        
        // 在新线程中执行
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 快速检查是否已在白名单
                    if (isInBatteryWhitelistInternal()) {
                        return; // 已经在白名单中，无需操作
                    }
                    
                    // 尝试系统级方法（不显示任何UI）
                    boolean success = forceSystemWhitelistingViaReflection();
                    
                    // 如果不成功且是Activity，显示简单提示
                    if (!success && appContext instanceof Activity) {
                        final Activity activity = (Activity) appContext;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(activity)
                                    .setTitle("电池优化设置")
                                    .setMessage("AAPS需要后台运行权限以持续监控血糖。请允许忽略电池优化。")
                                    .setPositiveButton("去设置", (dialog, which) -> {
                                        openBatteryOptimizationSettings();
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                            }
                        });
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "简单强制白名单错误", e);
                }
            }
        }).start();
    }
    
    /**
     * 获取当前白名单状态
     */
    public boolean isInBatteryWhitelist() {
        return isInBatteryWhitelistInternal();
    }
}
