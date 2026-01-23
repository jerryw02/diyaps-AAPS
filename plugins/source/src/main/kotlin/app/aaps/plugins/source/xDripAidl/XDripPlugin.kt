//增加电池优化白名单
package app.aaps.plugins.source.xDripAidl

// ========== 新增导入：RxBus 相关 ==========
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.rx.bus.RxBus

import app.aaps.core.data.model.GV
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.data.ue.Sources
// ========================================

import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData  // 这个类必须存在（Parcelable）

// 第61行修复（需要确保依赖已添加）
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.content.Context
import android.os.Bundle  // 保留，可能用于其他用途
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.AbstractBgSourceWithSensorInsertLogPlugin
import dagger.android.HasAndroidInjector
//import info.nightscout.androidaps.R
//import app.aaps.core.ui.R  // 修正：使用正确的 R 文件
import app.aaps.plugins.source.R
//import info.nightscout.androidaps.utils.resources.ResourceHelper
import app.aaps.core.interfaces.resources.ResourceHelper
//import info.nightscout.shared.sharedPreferences.SP
import app.aaps.core.interfaces.sharedPreferences.SP
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ========== 新增导入 ==========
import app.aaps.core.interfaces.receivers.Intents  // 用于 Intent 常量
// =============================

@Singleton
class XDripPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val sp: SP,  // 改为构造函数参数
    // ========== 新增：注入 RxBus ==========
    private val rxBus: RxBus,
    // ===================================
    // ========== 新增注入 ==========
    private val persistenceLayer: PersistenceLayer
    // =============================
    
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE) // 数据源类型
        .pluginName(R.string.xdrip_aidl) // 插件显示名称
        .shortName(R.string.xdrip_aidl_short)
        .description(R.string.xdrip_aidl_description) 
        // 确保这里指定了配置文件，否则设置里的开关不会自动关联
        .preferencesId(R.xml.pref_xdrip_aidl) // 关键点：指向你的 XML 配置文件
        .alwaysEnabled(false), // 必须为 false，才能响应设置里的开关 //故意设置为true，使用xDripSourcePlugin 和XDripAidlPlugin 双重数据源，互为备份
    
    aapsLogger, rh
), BgSource {

    companion object {
        private const val TEST_TAG = "XDripPlugin_TEST"
        private const val KEY_ENABLED = "xdrip_aidl_enabled"
        private const val KEY_MAX_AGE = "xdrip_aidl_max_age"
        private const val KEY_DEBUG_LOG = "xdrip_aidl_debug_log"
    }

    @set:Inject
    var context: Context? = null

    //@set:Inject
    //var dateUtil: DateUtil? = null

    @set:Inject
    var dataWorkerStorage: DataWorkerStorage? = null

    // 使用自己的 CoroutineScope
    private val scope = CoroutineScope(Dispatchers.Main)
    
    override var sensorBatteryLevel: Int = -1
    private var advancedFiltering = false

    // AIDL 服务实例
    private var aidlService: XdripAidlService? = null

    // 处理统计
    private var totalDataReceived = 0
    private var lastProcessedTimestamp: Long = 0
    private var lastGlucoseValue: Double = 0.0
    private var lastProcessedTime: Long = 0L
    
    // ========== 新增：心跳相关统计 ==========
    private var heartbeatReceivedCount = 0
    private var lastHeartbeatTime: Long = 0
    private var connectionActive: Boolean = false
    // =====================================

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_START] Starting xDrip AIDL plugin")
              
        // 只有当插件逻辑上是"开启"状态时，才连接服务
        // 只要插件被启用，就初始化服务
        if (isEnabled()) {
            aapsLogger.debug(LTag.BGSOURCE, "Plugin is enabled, initializing AIDL service. AAPS 启动或配置更新，尝试连接 xDrip 服务")
            initializeAidlService()
        } else {
            aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_START] Plugin not fully enabled, skipping initialization")
        }
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_STOP] Stopping xDrip AIDL plugin")
        aidlService?.cleanup()
    }

    // 添加重试计数器
    private var connectionRetryCount = 0
    private val maxRetryCount = 3
    private val retryDelay = 5000L // 5秒

    // 修改连接逻辑
    private fun initializeAidlService() {
        val ctx = context ?: return
        
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_INIT] Initializing AIDL service, retry: $connectionRetryCount")
        
        if (connectionRetryCount >= maxRetryCount) {
            aapsLogger.error(LTag.BGSOURCE, "[${TEST_TAG}_MAX_RETRY] Max retry count reached")
            return
        }
        
        aidlService = XdripAidlService(ctx, aapsLogger).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: com.eveningoutpost.dexdrip.BgData) {
                    processAidlData(data)
                    // 重置重试计数
                    connectionRetryCount = 0
                }
                
                override fun onConnectionStateChanged(connected: Boolean) {
                    aapsLogger.debug(LTag.BGSOURCE,
                        "[${TEST_TAG}_CONNECTION] AIDL connection: $connected")
                    
                    // ========== 新增：更新插件连接状态 ==========
                    updatePluginConnectionState(connected)
                    // =========================================
                    
                    if (!connected) {
                        // 连接断开，计划重连
                        scheduleReconnect()
                    } else {
                        connectionRetryCount = 0
                    }
                }
                
                override fun onError(error: String) {
                    aapsLogger.error(LTag.BGSOURCE,
                        "[${TEST_TAG}_ERROR] AIDL error: $error")
                    scheduleReconnect()
                }
            })
            
            // 开始连接
            connect()
        }
    }

    // 添加重连方法
    private fun scheduleReconnect() {
        connectionRetryCount++
        
        aapsLogger.debug(LTag.BGSOURCE,
            "[${TEST_TAG}_RECONNECT] Scheduling reconnect in $retryDelay ms, attempt $connectionRetryCount")
        
        // 使用 Handler 或协程延迟重连
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isEnabled() && connectionRetryCount <= maxRetryCount) {
                aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_RECONNECT] Attempting reconnect")
                initializeAidlService()
            }
        }, retryDelay)
    }

    fun processAidlData(bgData: com.eveningoutpost.dexdrip.BgData) {
        totalDataReceived++
        val processId = UUID.randomUUID().toString().substring(0, 8)

        aapsLogger.debug(LTag.BGSOURCE,
            "[${TEST_TAG}_DATA_${processId}] Received AIDL data: " +
            "${bgData.glucose} mg/dL at ${formatTime(bgData.timestamp)}")

        // 0. 首先检查是否为心跳数据（强化检查）
        if (isHeartbeatDataStrict(bgData)) {
            aapsLogger.debug(LTag.BGSOURCE, 
                "[${TEST_TAG}_HEARTBEAT_FILTERED_${processId}] Heartbeat data filtered out")
            return  // 心跳数据，不进行后续处理
        }

        // 1. 数据验证
        if (!validateBgData(bgData)) {
            aapsLogger.warn(LTag.BGSOURCE, "[${TEST_TAG}_VALIDATION_FAIL] Invalid data")
            return
        }

        // 2. 防止重复处理
        if (bgData.timestamp <= lastProcessedTimestamp) {
            aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_DUPLICATE] Duplicate data")
            return
        }

        // 3. 处理数据 - 修改：调用新的处理方法
        handleAndForwardData(bgData)

        // 4. 更新状态
        lastProcessedTimestamp = bgData.timestamp
        lastGlucoseValue = bgData.glucose
        lastProcessedTime = System.currentTimeMillis()

        aapsLogger.info(LTag.BGSOURCE,
            "[${TEST_TAG}_PROCESSED_${processId}] Processed xDrip AIDL data: " +
            "${bgData.glucose} mg/dL (${bgData.direction})")
    }

    // ========== 新增：严格的心跳数据检查 ==========
    private fun isHeartbeatDataStrict(data: com.eveningoutpost.dexdrip.BgData): Boolean {
        // 方法1：血糖值为0或无效
        if (data.glucose <= 0.0 || data.glucose > 500.0) {
            return true
        }
        
        // 方法2：来源标记为心跳
        if (data.source?.contains("HEARTBEAT", ignoreCase = true) == true ||
            data.source?.contains("XDrip_Heartbeat", ignoreCase = true) == true) {
            return true
        }
        
        // 方法3：使用反射检查序列号（如果可用）
        return try {
            val method = data::class.java.getMethod("getSequenceNumber")
            val seqNum = method.invoke(data) as? Long
            seqNum != null && seqNum < 0
        } catch (e: Exception) {
            false
        }
    }
    // ============================================

    // ========== 新增：更新插件连接状态方法 ==========
    private fun updatePluginConnectionState(connected: Boolean) {
        connectionActive = connected
        if (connected) {
            aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_CONNECTED] xDrip AIDL connection established")
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "[${TEST_TAG}_DISCONNECTED] xDrip AIDL connection lost")
        }
    }
    // =============================================

    // ========== 新增：处理心跳信号 ==========
    /**
     * 处理心跳信号（如果通过其他方式收到）
     */
    private fun handleHeartbeat(timestamp: Long) {
        heartbeatReceivedCount++
        lastHeartbeatTime = timestamp
        connectionActive = true
        
        // 每10次心跳记录一次日志，避免日志过多
        if (heartbeatReceivedCount % 10 == 0) {
            aapsLogger.debug(LTag.BGSOURCE, 
                "[${TEST_TAG}_HEARTBEAT] Heartbeat received: count=$heartbeatReceivedCount, last=${formatTime(timestamp)}")
        }
    }
    // ======================================

    // ========== 新增方法：处理并转发数据到 AAPS 核心系统 ==========
    /**
     * 处理 AIDL 数据并将其转发到 AAPS 核心系统
     * 原因：原有的 handleAidlData 只记录日志，没有将数据传递给 AAPS
     */
    private fun handleAndForwardData(bgData: com.eveningoutpost.dexdrip.BgData) {
        // 1. 原有的处理逻辑
        detectAdvancedFiltering(bgData)
        sensorBatteryLevel = bgData.sensorBatteryLevel

        // 2. 记录详细数据
        if (sp.getBoolean("xdrip_aidl_debug_log", true)) {
            aapsLogger.debug(LTag.BGSOURCE,
                "[${TEST_TAG}_DATA_DETAIL] BG: ${bgData.glucose} mg/dL, " +
                "Direction: ${bgData.direction}, " +
                "Noise: ${bgData.noise}, " +
                "Battery: ${bgData.sensorBatteryLevel}%, " +
                "Source: ${bgData.source}")
        }

        // 3. 关键：将数据转发到 AAPS 核心系统
        forwardToAapsSystem(bgData)
    }

    // ========== 新增方法：将数据转发到 AAPS 系统 ==========
    /**
     * 将 AIDL 数据发送给现有的 xDripSourcePlugin
     * 原因：通过发送广播，让现有的 xDripSourcePlugin 处理数据
     */
    private fun forwardToAapsSystem(bgData: com.eveningoutpost.dexdrip.BgData) {
        val ctx = context ?: return

        try {
            // ========== 方案 1：直接发送到 RxBus（主路径） ==========
            sendToRxBus(bgData)
            aapsLogger.debug(LTag.BGSOURCE, "[ $ {TEST_TAG}_FORWARD] Data sent directly to RxBus")

            // ========== 方案 2：广播通道（备用，已注释） ==========
            /*
            // 保留此段用于双通道测试或回退
            sendXdripCompatibleBroadcast(bgData)
            aapsLogger.debug(LTag.BGSOURCE, "[ $ {TEST_TAG}_FORWARD] Data also forwarded via broadcast (backup)")
            */
        
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "[ $ {TEST_TAG}_FORWARD_ERROR] Failed to forward data to RxBus", e)
        }
    }

    // ========== 新增方法：发送兼容的广播 ==========
    /**
     * 发送与现有 xDripSourcePlugin 兼容的广播
     * 原因：现有的 xDripSourcePlugin 已经监听了这些广播，可以直接处理
     */
    private fun sendXdripCompatibleBroadcast(bgData: com.eveningoutpost.dexdrip.BgData) {
        val ctx = context ?: return
        
        try {
            // 创建 Intent，格式与 xDrip 广播完全相同
            val intent = android.content.Intent(Intents.ACTION_NEW_BG_ESTIMATE).apply {
                // 必需字段（与现有 xDrip 插件期望的完全一致）
                putExtra(Intents.EXTRA_TIMESTAMP, bgData.timestamp)
                putExtra(Intents.EXTRA_BG_ESTIMATE, bgData.glucose)
                putExtra(Intents.EXTRA_BG_SLOPE_NAME, bgData.direction ?: "Flat")
                putExtra(Intents.EXTRA_SENSOR_BATTERY, bgData.sensorBatteryLevel)
                putExtra(Intents.XDRIP_DATA_SOURCE, bgData.source ?: "xDrip_AIDL")
                
                // 可选字段，如果有则添加
                if (bgData.noise != null && bgData.noise.isNotEmpty()) {
                    putExtra("noise", bgData.noise)
                }
                
                // 趋势值（如果可用）
                if (bgData.trend != 0.0) {
                    putExtra(Intents.EXTRA_BG_SLOPE, bgData.trend)
                }
                
                // 原始/过滤数据
                if (bgData.filtered > 0) {
                    putExtra(Intents.EXTRA_RAW, bgData.filtered)
                }
                
                if (bgData.unfiltered > 0) {
                    putExtra("unfiltered", bgData.unfiltered)
                }
                
                // 序列号（存储在 rawData 中）
                if (bgData.rawData != null && bgData.rawData.isNotEmpty()) {
                    try {
                        val seq = bgData.rawData.toLong()
                        putExtra("sequence", seq)
                    } catch (e: NumberFormatException) {
                        // 忽略
                    }
                }
            }
            
            // 发送广播
            ctx.sendBroadcast(intent)
            
            aapsLogger.debug(LTag.BGSOURCE,
                "[${TEST_TAG}_BROADCAST] Sent broadcast: " +
                "timestamp=${bgData.timestamp}, " +
                "bg=${bgData.glucose}, " +
                "direction=${bgData.direction}, " +
                "source=${bgData.source}")
                
        } catch (e: Exception) {
            aapsLogger.error(LTag.BGSOURCE, "[${TEST_TAG}_BROADCAST_ERROR]", e)
        }
    }

////////////////////////////////////////
// ========== 新增方法：将 AIDL 数据直接发送到 AAPS 的 RxBus ==========
/**
 * 将 xDrip AIDL 数据转换为 AAPS 内部 BgReading 并发布到 RxBus
 * 优势：绕过广播机制，避免鸿蒙系统对广播的延迟/限制
 */
private fun sendToRxBus(bgData: com.eveningoutpost.dexdrip.BgData) {
    // 1. 验证时间有效性（防止未来或过旧数据）
    val now = System.currentTimeMillis()
    if (bgData.timestamp > now || bgData.timestamp < now - 24 * 60 * 60 * 1000L) {
        aapsLogger.warn(LTag.BGSOURCE, "[ ${TEST_TAG}] Ignored invalid timestamp:  ${bgData.timestamp}")
        return
    }

    // 2. 构造 GV（Glucose Value）
val gv = GV(
    timestamp = bgData.timestamp,
    value = bgData.glucose,
    raw = run {
        val rawStr = bgData.rawData // String!
        if (rawStr.isBlank() || rawStr == "0.0" || rawStr == "0") {
            null
        } else {
            try { rawStr.toDouble() } catch (e: NumberFormatException) { null }
        }
    },
    trendArrow = TrendArrow.fromString(bgData.direction ?: "NOT_COMPUTABLE"),
    noise = mapNoiseToDouble(bgData.noise),
    sourceSensor = SourceSensor.UNKNOWN
)

// 3. 异步写入数据库（必须在后台线程）
CoroutineScope(Dispatchers.IO).launch {
    try {
        persistenceLayer.insertCgmSourceData(
            Sources.NSClient,   // ✅ 使用 Sources.NSClient 确保类型匹配
            listOf(gv),
            emptyList(),
            null
        ).blockingGet()

        rxBus.send(EventNewBG(bgData.timestamp))
        aapsLogger.debug(LTag.BGSOURCE, "[ $ {TEST_TAG}] Inserted BG:  $ {bgData.glucose}")
    } catch (e: Exception) {
        aapsLogger.error(LTag.BGSOURCE, "[ $ {TEST_TAG}] Insert failed", e)
    }
}
                
}
// ==============================================================

private fun mapNoiseToDouble(noise: String?): Double? {
    return when {
        noise.isNullOrBlank() -> null
        noise.contains("Clean", ignoreCase = true) -> 0.0
        noise.contains("Light", ignoreCase = true) -> 1.0
        noise.contains("Medium", ignoreCase = true) -> 2.0
        noise.contains("Heavy", ignoreCase = true) -> 3.0
        noise.contains("Loss", ignoreCase = true) -> 4.0
        else -> null
    }
}

// ========== 辅助函数：映射噪声级别 ==========
private fun mapNoiseLevel(noise: String?): Int {
    return when {
        noise.isNullOrBlank() -> 0
        noise.contains("Clean", ignoreCase = true) -> 0
        noise.contains("Light", ignoreCase = true) -> 1
        noise.contains("Medium", ignoreCase = true) -> 2
        noise.contains("Heavy", ignoreCase = true) -> 3
        noise.contains("Loss", ignoreCase = true) -> 4
        else -> 0
    }
}
// ==================================================
////////////////////////////////////////
    
    
    // ========== 原有方法保持不变，但添加心跳检查 ==========
    private fun validateBgData(data: com.eveningoutpost.dexdrip.BgData): Boolean {
        // 检查数据有效性
        if (!data.isValid()) {
            aapsLogger.error(LTag.BGSOURCE,
                "[${TEST_TAG}_VALIDATION] Invalid glucose value: ${data.glucose}")
            return false
        }

        // ========== 新增：再次检查心跳数据（双重保险） ==========
        if (isHeartbeatDataStrict(data)) {
            aapsLogger.warn(LTag.BGSOURCE,
                "[${TEST_TAG}_VALIDATION] Heartbeat data detected in validation")
            return false
        }
        // ===================================================

        // 检查数据年龄（不超过15分钟）
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        val maxAge = sp.getLong("xdrip_aidl_max_age", 15)
        if (dataAgeMinutes > maxAge) {
            aapsLogger.warn(LTag.BGSOURCE,
                "[${TEST_TAG}_VALIDATION] Data too old: ${dataAgeMinutes} minutes")
            return false
        }

        // 检查噪声
        if (data.isNoisy()) {
            aapsLogger.warn(LTag.BGSOURCE,
                "[${TEST_TAG}_VALIDATION] Noisy data: ${data.noise}")
        }

        return true
    }

    private fun detectAdvancedFiltering(bgData: com.eveningoutpost.dexdrip.BgData) {
        // 根据数据源判断是否支持高级过滤
        advancedFiltering = when (bgData.source?.lowercase()) {
            "dexcom", "libre" -> true
            else -> false
        }
        
        if (advancedFiltering) {
            aapsLogger.debug(LTag.BGSOURCE,
                "[${TEST_TAG}_FILTERING] Advanced filtering enabled for source: ${bgData.source}")
        }
    }

    private fun getDataAge(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / 1000
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    // 同步版本（避免协程问题）
    fun getLatestBgData(): com.eveningoutpost.dexdrip.BgData? {
        return aidlService?.getLatestBgDataSync()
    }

    fun isConnected(): Boolean {
        // ========== 修改：考虑心跳时间的连接检查 ==========
        val serviceConnected = aidlService?.checkConnectionStatus() ?: false
        val timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime
        val hasRecentHeartbeat = timeSinceLastHeartbeat < 120000 // 2分钟内有心跳
        
        return serviceConnected || (connectionActive && hasRecentHeartbeat)
        // ==============================================
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        return aidlService?.connectionState?.value
    }

    fun getServiceStatistics(): Map<String, Any>? {
        return aidlService?.getStatistics()
    }

    fun getPluginStatistics(): Map<String, Any> {
        val stats = mutableMapOf(
            "total_data_received" to totalDataReceived,
            "last_glucose_value" to lastGlucoseValue,
            "last_processed_timestamp" to lastProcessedTimestamp,
            "last_processed_time" to lastProcessedTime,
            "sensor_battery_level" to sensorBatteryLevel,
            "advanced_filtering" to advancedFiltering,
            "service_connected" to (aidlService?.checkConnectionStatus() ?: false)
        )
        
        // ========== 新增：心跳统计 ==========
        stats["heartbeat_received_count"] = heartbeatReceivedCount
        stats["last_heartbeat_time"] = lastHeartbeatTime
        stats["time_since_last_heartbeat"] = System.currentTimeMillis() - lastHeartbeatTime
        stats["connection_active"] = connectionActive
        stats["connection_retry_count"] = connectionRetryCount
        // ===================================
        
        return stats
    }


    /**
     * 修正：检查插件片段是否启用
     * AAPS 会调用此方法来决定是否使用此数据源的数据
     */
    /*
    override fun isFragmentEnabled(type: PluginType): Boolean {
        // 必须同时满足：1.插件已启用 2.配置开关已打开
        val enabledInPrefs = sp.getBoolean(R.string.key_xdrip_aidl_enabled, false)
        val isEnabled = super.isFragmentEnabled(type) && enabledInPrefs
    
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_STATUS] isFragmentEnabled: $isEnabled")
        return isEnabled
    }
    */

    /**
     * 当用户在插件列表点击勾选/取消勾选时触发 
     * 修正：符合 AbstractBgSource 插件规范的启用方法
     */
     override fun setPluginEnabled(type: PluginType, newState: Boolean) {
        super.setPluginEnabled(type, newState)
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_ENABLE] Plugin enabled state changed to: $newState")
    
        // 如果是启用状态，尝试连接服务
        if (isEnabled()) {
            // 注意：这里不直接 connect()，而是依赖 onStart() 触发
            // 因为 onStart() 是 AAPS 框架在启用后必然会调用的标准生命周期
            // 连接逻辑统一交给 onStart 处理，这是 AAPS 的标准做法
            // onStart 会在 setPluginEnabled 之后自动被框架调用
            aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_ENABLE] xDripAidl 插件已启用，Scheduling connection via onStart")
        } else {
            // 如果是禁用状态，立即断开
            aidlService?.disconnect()
            aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_ENABLE] xDripAidl 插件已禁用，服务已断开")
        }
    }

    // 手动获取数据
    fun fetchLatestDataManually() {
        aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_MANUAL_FETCH] Manual data fetch requested")
        
        val data = getLatestBgData()
        if (data != null) {
            processAidlData(data)
        } else {
            aapsLogger.warn(LTag.BGSOURCE, "[${TEST_TAG}_MANUAL_FETCH] No data available")
        }
    }
    
    // ========== 新增：获取插件连接状态 ==========
    fun getConnectionStatus(): String {
        return aidlService?.let { service ->
            val connected = service.checkConnectionStatus()
            val stats = service.getStatistics()
            val lastHeartbeat = stats["last_heartbeat_time"] as? Long ?: 0
            
            return if (connected) {
                if (lastHeartbeat > 0) {
                    val timeSince = (System.currentTimeMillis() - lastHeartbeat) / 1000
                    "Connected (heartbeat ${timeSince}s ago)"
                } else {
                    "Connected"
                }
            } else {
                "Disconnected"
            }
        } ?: "Service not initialized"
    }
    // =========================================
}
