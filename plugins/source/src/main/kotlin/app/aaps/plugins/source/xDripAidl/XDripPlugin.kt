package app.aaps.plugins.source.xDripAidl

import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData  // 这个类必须存在（Parcelable）

// 第61行修复（需要确保依赖已添加）
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import android.content.Context
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

@Singleton
class XDripPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val sp: SP  // 改为构造函数参数
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE) // 数据源类型
        .pluginName(R.string.xdrip_aidl) // 插件显示名称
        .shortName(R.string.xdrip_aidl_short)
        .description(R.string.xdrip_aidl_description) 
        // 确保这里指定了配置文件，否则设置里的开关不会自动关联
        .preferencesId(R.xml.pref_xdrip_aidl) // 关键点：指向你的 XML 配置文件
        .alwaysEnabled(false), // 必须为 false，才能响应设置里的开关
    
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

    @set:Inject
    var dateUtil: DateUtil? = null

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

    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_START] Starting xDrip AIDL plugin")

        // 只有当插件逻辑上是“开启”状态时，才连接服务
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

    private fun initializeAidlService() {
        val ctx = context ?: return

        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_INIT] Initializing AIDL service")

        aidlService = XdripAidlService(ctx, aapsLogger).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: com.eveningoutpost.dexdrip.BgData) {
                    processAidlData(data)
                }

                override fun onConnectionStateChanged(connected: Boolean) {
                    aapsLogger.debug(LTag.BGSOURCE,
                        "[${TEST_TAG}_CONNECTION] AIDL connection: $connected")
                }

                override fun onError(error: String) {
                    aapsLogger.error(LTag.BGSOURCE,
                        "[${TEST_TAG}_ERROR] AIDL error: $error")
                }
            })

            // 开始连接
            connect()
        }
    }

    fun processAidlData(bgData: com.eveningoutpost.dexdrip.BgData) {
        totalDataReceived++
        val processId = UUID.randomUUID().toString().substring(0, 8)

        aapsLogger.debug(LTag.BGSOURCE,
            "[${TEST_TAG}_DATA_${processId}] Received AIDL data: " +
            "${bgData.glucose} mg/dL at ${formatTime(bgData.timestamp)}")

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

        // 3. 处理数据
        handleAidlData(bgData)

        // 4. 更新状态
        lastProcessedTimestamp = bgData.timestamp
        lastGlucoseValue = bgData.glucose
        lastProcessedTime = System.currentTimeMillis()

        aapsLogger.info(LTag.BGSOURCE,
            "[${TEST_TAG}_PROCESSED_${processId}] Processed xDrip AIDL data: " +
            "${bgData.glucose} mg/dL (${bgData.direction})")
    }

    private fun validateBgData(data: com.eveningoutpost.dexdrip.BgData): Boolean {
        // 检查数据有效性
        if (!data.isValid()) {
            aapsLogger.error(LTag.BGSOURCE,
                "[${TEST_TAG}_VALIDATION] Invalid glucose value: ${data.glucose}")
            return false
        }

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

    private fun handleAidlData(data: com.eveningoutpost.dexdrip.BgData) {
        // 检测高级过滤支持
        detectAdvancedFiltering(data)

        // 更新传感器电量
        sensorBatteryLevel = data.sensorBatteryLevel

        // 记录详细数据
        if (sp.getBoolean("xdrip_aidl_debug_log", true)) {
            aapsLogger.debug(LTag.BGSOURCE,
                "[${TEST_TAG}_DATA_DETAIL] BG: ${data.glucose} mg/dL, " +
                "Direction: ${data.direction}, " +
                "Noise: ${data.noise}, " +
                "Battery: ${data.sensorBatteryLevel}%, " +
                "Source: ${data.source}")
        }
    // === 关键修改：将数据传递给 AAPS 核心系统 ===
    processAndNotifyBgData(data)
}

/**
 * 处理并通知血糖数据到 AAPS 系统
 */
private fun processAndNotifyBgData(bgData: com.eveningoutpost.dexdrip.BgData) {
    val context = context ?: return
    val dateUtil = dateUtil ?: return
    
    // 1. 创建 AAPS 内部的 GlucoseValue 对象
    val glucoseValue = createGlucoseValue(bgData)
    
    // 2. 保存到数据库
    saveToDatabase(glucoseValue)
    
    // 3. 通知系统有新数据
    notifyNewData(glucoseValue)
    
    // 4. 更新插件状态
    updatePluginState(bgData)
}

/**
 * 创建 AAPS 内部的 GlucoseValue 对象
 */
private fun createGlucoseValue(bgData: com.eveningoutpost.dexdrip.BgData): app.aaps.core.interfaces.GlucoseValue {
    return object : app.aaps.core.interfaces.GlucoseValue {
        override fun getValue(): Double = bgData.glucose
        override fun getValueMgdl(): Double = bgData.glucose
        override fun getTimestamp(): Long = bgData.timestamp
        override fun getSourceSensor(): app.aaps.core.interfaces.GlucoseValue.SourceSensor = 
            app.aaps.core.interfaces.GlucoseValue.SourceSensor.UNKNOWN
        override fun getTrendArrow(): app.aaps.core.interfaces.GlucoseUnit.TrendArrow? {
            return when (bgData.direction?.lowercase()) {
                "doubleup" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.DOUBLE_UP
                "singleup" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.SINGLE_UP
                "fortyfiveup" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.FORTY_FIVE_UP
                "flat" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.FLAT
                "fortyfivedown" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.FORTY_FIVE_DOWN
                "singledown" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.SINGLE_DOWN
                "doubledown" -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.DOUBLE_DOWN
                else -> app.aaps.core.interfaces.GlucoseUnit.TrendArrow.NONE
            }
        }
        override fun getSensorId(): String? = bgData.source
        override fun getSensorBatteryLevel(): Int? = bgData.sensorBatteryLevel
    }
}

/**
 * 保存数据到数据库
 */
private fun saveToDatabase(glucoseValue: app.aaps.core.interfaces.GlucoseValue) {
    try {
        // 使用 DataWorkerStorage 保存数据
        dataWorkerStorage?.storeData(
            glucoseValue,
            app.aaps.core.interfaces.DataWorker.DataType.BG
        )
        
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_DB_SAVE] Data saved to database")
    } catch (e: Exception) {
        aapsLogger.error(LTag.BGSOURCE, "[${TEST_TAG}_DB_ERROR] Failed to save data", e)
    }
}

/**
 * 通知系统有新数据
 */
private fun notifyNewData(glucoseValue: app.aaps.core.interfaces.GlucoseValue) {
    // 发送广播通知数据更新
    val intent = android.content.Intent(app.aaps.core.interfaces.Constants.ACTION_NEW_BG_ESTIMATE)
    intent.putExtra("timestamp", glucoseValue.timestamp)
    intent.putExtra("bg", glucoseValue.value)
    intent.putExtra("trend", glucoseValue.trendArrow?.name)
    
    context?.sendBroadcast(intent)
    
    aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_NOTIFY] Sent broadcast for new BG data")
    
    // 也可以使用 EventBus
    // EventBus.getDefault().post(NewBgDataEvent(glucoseValue))
}

/**
 * 更新插件状态
 */
private fun updatePluginState(bgData: com.eveningoutpost.dexdrip.BgData) {
    // 更新最后处理的数据
    lastGlucoseValue = bgData.glucose
    lastProcessedTimestamp = bgData.timestamp
    
    // 通知 UI 更新
    notifyPluginChanged()
}
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
        return aidlService?.checkConnectionStatus() ?: false
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        return aidlService?.connectionState?.value
    }

    fun getServiceStatistics(): Map<String, Any>? {
        return aidlService?.getStatistics()
    }

    fun getPluginStatistics(): Map<String, Any> {
        return mapOf(
            "total_data_received" to totalDataReceived,
            "last_glucose_value" to lastGlucoseValue,
            "last_processed_timestamp" to lastProcessedTimestamp,
            "last_processed_time" to lastProcessedTime,
            "sensor_battery_level" to sensorBatteryLevel,
            "advanced_filtering" to advancedFiltering,
            "service_connected" to (aidlService?.checkConnectionStatus() ?: false)
        )
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
}
