
package app.aaps.plugins.source.xDripAidl

import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData

import android.content.Context
import app.aaps.core.data.plugin.PluginType
//import app.aaps.core.interfaces.config.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.R
import app.aaps.plugins.source.AbstractBgSourceWithSensorInsertLogPlugin
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton
import java.text.SimpleDateFormat
import java.util.*
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.atomic.AtomicLong

//import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.database.AppRepository
import app.aaps.database.entities.GlucoseValue

@Singleton
class XDripPlugin @Inject constructor(
    injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val sp: SP,
    private val context: Context?,
    private val dateUtil: DateUtil?,
    private val dataWorkerStorage: DataWorkerStorage?,
    private val uiInteraction: UiInteraction?
    //private val config: Config  // 添加Config依赖
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .pluginName(R.string.xdrip_aidl)
        .shortName(R.string.xdrip_aidl_short)
        .description(R.string.xdrip_aidl_description)
        .preferencesId(R.xml.pref_xdrip_aidl)
        .showInList(true)
        .visibleByDefault(true)
        .setDefault(),
    aapsLogger, rh, injector
), BgSource {

    companion object {
        private const val TEST_TAG = "XDripPlugin_TEST"
        private const val KEY_ENABLED = "xdrip_aidl_enabled"
        private const val KEY_MAX_AGE = "xdrip_aidl_max_age"
        private const val KEY_DEBUG_LOG = "xdrip_aidl_debug_log"
    }

    // AIDL 服务实例
    private var aidlService: XdripAidlService? = null

    // BgSource接口需要的属性
    override var sensorBatteryLevel: Int = -1
    private var advancedFiltering = false

    // 处理统计
    private var totalDataReceived = 0
    private var lastProcessedTimestamp: Long = 0
    private var lastGlucoseValue: Double = 0.0
    private var lastProcessedTime: Long = 0L

    // ========== PluginBase 接口实现 ==========
    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_START] Starting xDrip AIDL plugin")

        if (isEnabled()) {
            initializeAidlService()
        }
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_STOP] Stopping xDrip AIDL plugin")
        aidlService?.cleanup()
        aidlService = null
    }

    // ========== BgSource 接口实现 ==========
    override fun advancedFilteringSupported(): Boolean = advancedFiltering

    override fun noSensorRequired(): Boolean = true

    override fun uploadOnly(): Boolean = false

    override fun shouldUploadToNs(nsUpload: Boolean): Boolean {
        return isEnabled() && super.shouldUploadToNs(nsUpload)
    }

    override fun getBgData(): List<BgSource.BgData> {
        val data = getLatestBgData() ?: return emptyList()

        return listOf(
            BgSource.BgData(
                value = data.glucose,
                timestamp = data.timestamp,
                raw = data.glucose,
                noise = data.noise?.toString() ?: "",
                trendArrow = convertDirectionToTrend(data.direction),
                source = data.source ?: "xDrip+ AIDL",
                sensorBatteryLevel = data.sensorBatteryLevel,
                filtered = data.filtered,
                unfiltered = data.unfiltered
            )
        )
    }

    override fun getStatus(): String {
        return when (val state = getConnectionState()) {
            is XdripAidlService.ConnectionState.Connected -> "Connected to xDrip+"
            is XdripAidlService.ConnectionState.Disconnected -> "Disconnected from xDrip+"
            is XdripAidlService.ConnectionState.Connecting -> "Connecting to xDrip+..."
            is XdripAidlService.ConnectionState.Error -> "Error: ${state.message}"
            else -> "Unknown state"
        }
    }

    override fun getStatusShort(): String {
        return if (isConnected()) "✓" else "✗"
    }

    // ========== 核心AIDL功能 ==========
    private fun initializeAidlService() {
        val ctx = context ?: run {
            aapsLogger.error(LTag.BGSOURCE, "[${TEST_TAG}_INIT_ERROR] Context is null!")
            return
        }

        aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_INIT] Initializing AIDL service")

        aidlService = XdripAidlService(ctx, aapsLogger).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: com.eveningoutpost.dexdrip.BgData) {
                    processAidlData(data)
                }

                override fun onConnectionStateChanged(connected: Boolean) {
                    aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_CONNECTION] AIDL connection changed: $connected")
                }

                override fun onError(error: String) {
                    aapsLogger.error(LTag.BGSOURCE, "[${TEST_TAG}_ERROR] AIDL error: $error")
                }
            })

            aapsLogger.info(LTag.BGSOURCE, "[${TEST_TAG}_CONNECT] Initiating AIDL connection")
            connect()
        }
    }

    private fun processAidlData(bgData: com.eveningoutpost.dexdrip.BgData) {
        totalDataReceived++
        val processId = UUID.randomUUID().toString().substring(0, 8)

        aapsLogger.info(LTag.BGSOURCE,
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

        // 5. 保存到数据库（如果需要）
        saveToDatabase(bgData)
    }

    private fun saveToDatabase(data: com.eveningoutpost.dexdrip.BgData) {
        // 如果需要保存到AAPS数据库，这里实现
        aapsLogger.debug(LTag.BGSOURCE, "[${TEST_TAG}_DB] Data ready for database: ${data.glucose} mg/dL")
    }

    // ========== 辅助方法 ==========
    override fun isEnabled(): Boolean {
        return sp.getBoolean(KEY_ENABLED, false)
    }

    fun getLatestBgData(): com.eveningoutpost.dexdrip.BgData? {
        return aidlService?.getLatestBgDataSync()
    }

    fun isConnected(): Boolean {
        return aidlService?.checkConnectionStatus() ?: false
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        return aidlService?.connectionState?.value
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    // 添加一个简单的诊断方法用于测试
    fun testConnection(): String {
        return "xDrip AIDL Plugin: " +
               "Enabled=${isEnabled()}, " +
               "Connected=${isConnected()}, " +
               "DataReceived=$totalDataReceived"
    }
}

/*
package app.aaps.plugins.source.xDripAidl

import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData

//import app.aaps.core.interfaces.Constants.TEST_TAG
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
//import app.aaps.core.interfaces.plugin.DataSourcePlugin
//import app.aaps.core.interfaces.plugin.RawDisplayData
import app.aaps.core.interfaces.plugin.PluginDescription
//import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.data.plugin.PluginType

import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
//import app.aaps.core.interfaces.rx.RxBus
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
//import app.aaps.database.impl.AppRepository
import app.aaps.database.AppRepository
import app.aaps.database.entities.GlucoseValue

//import app.aaps.core.interfaces.events.Event
//import app.aaps.core.interfaces.events.EventNewHistoryData

import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// 1. 使用 @Inject 注解主构造函数
// 2. 显式声明父类 PluginBase 所需的参数
class XDripPlugin @Inject constructor(
    // --- 以下参数由 Dagger 自动注入，无需手动创建 ---
    aapsLogger: AAPSLogger,       // 日志工具
    rh: ResourceHelper,           // 资源工具（字符串、颜色等）
    private val sp: SP,           // SharedPreferences
    private val rxBus: RxBus,      // 事件总线
    private val fabricPrivacy: FabricPrivacy, // 崩溃报告
    private val profileFunction: ProfileFunction, // 仪表盘功能
    private val uiInteraction: UiInteraction, // UI 交互
    private val repository: AppRepository, // 数据库仓库
    // --- 以上是注入参数 ---
    
    // --- 以下是你插件自己的初始化参数 ---
    private val pluginDescription: PluginDescription // 插件描述（通常在伴生对象中初始化）
) : PluginBase(pluginDescription, aapsLogger, rh) { // 3. 将 aapsLogger 和 rh 传递给父类

    // 4. 覆盖父类属性 
    // 注意：这里必须使用 override，不能重新声明为 val/var
    override val aapsLogger: AAPSLogger = aapsLogger
    override val rh: ResourceHelper = rh

    // --- 其他成员变量 ---
    private val xdripAidlService = XdripAidlService(this)

    // --- 生命周期方法 ---
    override fun onStart() {
        super.onStart()
        // 启动服务逻辑
        xdripAidlService.bindService()
    }

    override fun onStop() {
        super.onStop()
        // 停止服务逻辑
        xdripAidlService.unbindService()
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
*/


/*

// 修改插件主类以适应 AAPS 的依赖注入

package app.aaps.plugins.source.xDripAidl

// ✅ 正确 import AIDL 生成的接口和数据类
import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData  // 这个类必须存在（Parcelable）

import android.content.Context
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import app.aaps.core.ui.R // 👈 R 文件替换为新版
import app.aaps.core.utils.T

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.DataSourcePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

//import app.aaps.database.entities.GlucoseValue
import app.aaps.database.AppRepository
import app.aaps.database.entities.GlucoseValue

import app.aaps.core.interfaces.plugin.RawDisplayData
import app.aaps.core.interfaces.events.Event
import app.aaps.core.interfaces.events.EventNewHistoryData

import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// ✅ 使用正确的构造函数注入
class XDripPlugin @Inject constructor(
    private val context: Context,
    private val sp: SP
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.DATASOURCE)
        .fragmentClass("app.aaps.plugins.source.xDripAidl.XDripFragment")
        .pluginName(R.string.xdrip_aidl)
        .shortName(R.string.xdrip_aidl_short)
        .preferencesId(R.xml.pref_xdrip_aidl)
        .description(R.string.xdrip_aidl_description)
), DataSourcePlugin {

    // ✅ 使用字段注入（AAPS 会在创建后自动 inject）
    @Inject override lateinit var aapsLogger: AAPSLogger
    @Inject override lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var database: AppRepository
    @Inject lateinit var activePlugin: app.aaps.core.interfaces.plugin.ActivePlugin

    companion object {
        private const val TEST_TAG = "XDripPlugin_TEST"
        
        // 配置键名常量
        const val KEY_XDRIP_AIDL_ENABLED = "key_xdrip_aidl_enabled"
        const val KEY_XDRIP_AIDL_MAX_AGE = "key_xdrip_aidl_max_age"
        const val KEY_XDRIP_AIDL_MIN_DELTA = "key_xdrip_aidl_min_delta"
        const val KEY_XDRIP_AIDL_DEBUG_LOG = "key_xdrip_aidl_debug_log"
    }

    private val disposable = CompositeDisposable()
    private var xdripService: XdripAidlService? = null
    private val lastProcessedTimestamp = AtomicLong(0)
    private var lastGlucoseValue: Double = 0.0

    // 统计 & 配置字段保持不变...
    private var totalDataReceived = 0
    private var totalDataProcessed = 0
    private var totalDataRejected = 0
    private var totalDataErrors = 0
    private var lastProcessedTime = 0L
    private val processingTimes = mutableListOf<Long>()

    private val isEnabled: Boolean
        get() = sp.getBoolean(KEY_XDRIP_AIDL_ENABLED, true)

    private val maxDataAgeMinutes: Long
        get() = sp.getLong(KEY_XDRIP_AIDL_MAX_AGE, 15)

    private val minGlucoseDelta: Double
        get() = sp.getDouble(KEY_XDRIP_AIDL_MIN_DELTA, 2.0)

    private val debugLogging: Boolean
        get() = sp.getBoolean(KEY_XDRIP_AIDL_DEBUG_LOG, true)

    init {
        // ✅ 触发 Dagger 注入（AAPS 要求）
        (context as? HasAndroidInjector)?.androidInjector()?.inject(this)
            ?: throw IllegalStateException("Context must implement HasAndroidInjector")
    }

    override fun advancedFilteringSupported(): Boolean = false

    override fun onStartJob(pluginStartReason: String): Boolean {
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PLUGIN_START] ========== xDrip AIDL Plugin Starting ($pluginStartReason) ==========")

        if (isEnabled) {
            logPluginFlow("INITIALIZATION", "Initializing xDrip AIDL service")
            initializeService()
        }
        return true
    }

    override fun onStopJob(reason: String) {
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PLUGIN_STOP] ========== xDrip AIDL Plugin Stopping ($reason) ==========")

        xdripService?.cleanup()
        disposable.clear()
    }

    override fun specialEnableCondition(): Boolean = isEnabled

    private fun initializeService() {
        // 注意：现在可以安全使用 rxBus, aapsSchedulers 等（因为已注入）
        xdripService = XdripAidlService(context, aapsLogger).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: BgData) {
                    handleNewBgData(data)
                }
                override fun onConnectionStateChanged(connected: Boolean) {
                    handleConnectionStateChanged(connected)
                }
                override fun onError(error: String) {
                    handleServiceError(error)
                }
            })
            connect()
        }
    }

    private fun handleNewBgData(data: BgData) {
        totalDataReceived++
        val startTime = System.currentTimeMillis()
        val processId = UUID.randomUUID().toString().substring(0, 8)
        
        logPluginFlow("DATA_PROCESS_START_$processId", 
            "Starting data processing for BG: ${data.glucose} mg/dL")

        // 1. 数据验证
        logPluginFlow("VALIDATION_START_$processId", "Starting data validation")
        if (!validateBgData(data)) {
            totalDataRejected++
            logPluginFlow("VALIDATION_FAIL_$processId", "Data validation failed")
            return
        }

        // 2. 防止重复处理
        val lastTimestamp = lastProcessedTimestamp.get()
        if (data.timestamp <= lastTimestamp) {
            totalDataRejected++
            logPluginFlow("DUPLICATE_SKIP_$processId", "Duplicate data, skipping processing")
            return
        }

        // 3. 检查血糖变化是否显著
        val glucoseDelta = Math.abs(data.glucose - lastGlucoseValue)
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (glucoseDelta < minGlucoseDelta && dataAgeMinutes < 2) {
            totalDataRejected++
            logPluginFlow("SMALL_DELTA_SKIP_$processId", "Glucose change too small, skipping")
            return
        }

        // 4. 转换为AAPS格式
        val glucoseValue = convertToGlucoseValue(data)

        // 5. 存储到数据库
        storeGlucoseValue(glucoseValue)

        // 6. 更新内部状态
        lastProcessedTimestamp.set(data.timestamp)
        lastGlucoseValue = data.glucose
        lastProcessedTime = System.currentTimeMillis()

        // 7. 触发数据更新事件
        triggerDataUpdateEvents(glucoseValue)

        // 8. 检查是否需要触发循环
        if (shouldTriggerLoop(data)) {
            logPluginFlow("LOOP_TRIGGER_$processId", "Loop conditions met, triggering loop processing")
            triggerLoopProcessing(data)
        }

        val processTime = System.currentTimeMillis() - startTime
        processingTimes.add(processTime)
        totalDataProcessed++
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PROCESS_SUCCESS_$processId] Successfully processed xDrip data: " +
            "${data.glucose} mg/dL (${data.direction}) in ${processTime}ms")
    }

    private fun validateBgData(data: BgData): Boolean {
        // 检查数据有效性 - 使用更可靠的方法
        if (data.glucose <= 0 || data.glucose > 1000) {
            totalDataErrors++
            return false
        }
        
        if (data.timestamp <= 0) {
            totalDataErrors++
            return false
        }

        // 检查数据年龄
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (dataAgeMinutes > maxDataAgeMinutes) {
            totalDataErrors++
            return false
        }

        return true
    }

    private fun convertToGlucoseValue(data: BgData): GlucoseValue {
        val noiseValue = mapNoise(data.noise)
        val direction = mapDirection(data.direction)
        
        return GlucoseValue(
            timestamp = data.timestamp,
            value = data.glucose,
            raw = data.unfiltered,
            noise = noiseValue,
            trendArrow = direction,
            source = "xDrip AIDL"
        )
    }

    private fun mapNoise(noise: String?): Double {
        return when {
            noise == null -> 0.0
            noise.contains("high", ignoreCase = true) -> 2.0
            noise.contains("medium", ignoreCase = true) -> 1.0
            noise.contains("low", ignoreCase = true) -> 0.5
            else -> 0.0
        }
    }

    private fun mapDirection(direction: String?): String {
        return when (direction) {
            "DoubleUp" -> "DoubleUp"
            "SingleUp" -> "SingleUp"
            "FortyFiveUp" -> "FortyFiveUp"
            "Flat" -> "Flat"
            "FortyFiveDown" -> "FortyFiveDown"
            "SingleDown" -> "SingleDown"
            "DoubleDown" -> "DoubleDown"
            else -> direction ?: "NONE"
        }
    }

    private fun storeGlucoseValue(glucoseValue: GlucoseValue) {
        disposable.add(
            database.insert(glucoseValue)
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe(
                    { 
                        aapsLogger.info(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_SUCCESS] Successfully stored glucose value " +
                            "${glucoseValue.value} mg/dL to database")
                    },
                    { error -> 
                        totalDataErrors++
                        aapsLogger.error(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_ERROR] Error storing glucose to database", error)
                    }
                )
        )
    }

    private fun triggerDataUpdateEvents(glucoseValue: GlucoseValue) {
        // 触发新历史数据事件
        rxBus.send(EventNewHistoryData(timestamp = glucoseValue.timestamp))
    }

    private fun shouldTriggerLoop(data: BgData): Boolean {
        val loopPlugin = activePlugin.activeLoop
        if (loopPlugin == null || !loopPlugin.isEnabled) {
            return false
        }

        // 检查是否达到最小处理间隔
        val timeSinceLastLoop = System.currentTimeMillis() - loopPlugin.lastRun
        val minLoopInterval = T.mins(5).msecs()
        
        if (timeSinceLastLoop < minLoopInterval) {
            return false
        }

        return true
    }

    private fun triggerLoopProcessing(data: BgData) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_LOOP_TRIGGER] Triggering loop processing from xDrip data: " +
            "${data.glucose} mg/dL")
        
        val loopPlugin = activePlugin.activeLoop
        if (loopPlugin != null) {
            val reason = "Triggered by xDrip AIDL (BG: ${data.glucose} mg/dL)"
            loopPlugin.invoke(reason, false)
        }
    }

    private fun handleConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CONNECTION_STATE_UPDATE] Connection state changed: $connected")
    }

    private fun handleServiceError(error: String) {
        totalDataErrors++
        aapsLogger.error(LTag.XDRIP, 
            "[${TEST_TAG}_SERVICE_ERROR_DETAIL] Service error received: $error")
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun getDataAge(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / 1000
    }
    
    private fun getAverageProcessTime(): Long {
        return if (processingTimes.isNotEmpty()) {
            processingTimes.average().toLong()
        } else {
            0
        }
    }
    
    private fun logPluginFlow(stage: String, message: String) {
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_FLOW_${stage}] $message")
        }
    }

    fun getLatestBgData(): BgData? {
        return xdripService?.getLatestBgData()
    }

    fun isConnected(): Boolean {
        return xdripService?.checkConnectionStatus() ?: false
    }

    override fun getRawData(): RawDisplayData {
        val data = RawDisplayData()
        data.add("Last Glucose Value", lastGlucoseValue.toString())
        data.add("Last Processed Time", formatTime(lastProcessedTimestamp.get()))
        data.add("Source", "xDrip+ AIDL")
        data.add("Connected", isConnected().toString())
        data.add("Total Processed", totalDataProcessed.toString())
        data.add("Total Errors", totalDataErrors.toString())
        return data
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        return xdripService?.connectionState?.value
    }
    
    fun getServiceStatistics(): Map<String, Any>? {
        return xdripService?.getStatistics()
    }

    // 手动获取数据（改为同步或使用 Rx）
    fun fetchLatestDataManually() {
        val data = getLatestBgData()
        data?.let { handleNewBgData(it) }
    }
    
    fun getPluginStatistics(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_data_received"] = totalDataReceived
        stats["total_data_processed"] = totalDataProcessed
        stats["total_data_rejected"] = totalDataRejected
        stats["total_data_errors"] = totalDataErrors
        stats["last_processed_time"] = lastProcessedTime
        stats["last_glucose_value"] = lastGlucoseValue
        stats["last_processed_timestamp"] = lastProcessedTimestamp.get()
        stats["average_process_time_ms"] = getAverageProcessTime()
        stats["debug_logging_enabled"] = debugLogging
        stats["plugin_enabled"] = isEnabled
        stats["service_connected"] = xdripService?.checkConnectionStatus() ?: false
        
        return stats
    }
}
*/



/*
//修改插件主类以适应 AAPS 的依赖注入

package app.aaps.plugins.source.xDripAidl

// ✅ 正确 import AIDL 生成的接口和数据类
import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData  // 这个类必须存在（Parcelable）

import android.content.Context
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
import app.aaps.core.ui.R // 👈 R 文件替换为新版
import app.aaps.core.data.time.T

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.DataSourcePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
//import app.aaps.core.interfaces.plugin.PluginType
import app.aaps.core.data.plugin.PluginType
//import app.aaps.core.interfaces.plugin.ActivePluginProvider
import app.aaps.core.interfaces.rx.bus.RxBus                    // ✅ 新路径
//import app.aaps.core.utils.resources.ResourceHelper             // ✅ 新路径
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers                    // ✅ 新路径
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.logging.AAPSLogger              
import app.aaps.core.interfaces.logging.LTag

import app.aaps.database.entities.GlucoseValue

import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

// ❌ 不要 @Inject on class or constructor!
class XDripPlugin(
    private val context: Context,      // ApplicationContext
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.DATASOURCE)
        .fragmentClass(XDripFragment::class.java.name)
        .pluginName(R.string.xdrip_aidl)
        .shortName(R.string.xdrip_aidl_short)
        .preferencesId(R.xml.pref_xdrip_aidl)
        .description(R.string.xdrip_aidl_description),
    aapsLogger, rh, sp
), DataSourcePlugin {

    // ✅ 使用字段注入（AAPS 会在创建后自动 inject）
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    //@Inject lateinit var activePlugin: ActivePluginProvider

    companion object {
        private const val TEST_TAG = "XDripPlugin_TEST"
    }

    private val disposable = CompositeDisposable()
    private var xdripService: XdripAidlService? = null
    private val lastProcessedTimestamp = AtomicLong(0)
    private var lastGlucoseValue: Double = 0.0


    // 统计 & 配置字段保持不变...
    private var totalDataReceived = 0
    private var totalDataProcessed = 0
    private var totalDataRejected = 0
    private var totalDataErrors = 0
    private var lastProcessedTime = 0L
    private val processingTimes = mutableListOf<Long>()

    private val isEnabled: Boolean
        get() = sp.getBoolean(R.string.key_xdrip_aidl_enabled, true)

    private val maxDataAgeMinutes: Long
        get() = sp.getLong(R.string.key_xdrip_aidl_max_age, 15)

    private val minGlucoseDelta: Double
        get() = sp.getDouble(R.string.key_xdrip_aidl_min_delta, 2.0)

    private val debugLogging: Boolean
        get() = sp.getBoolean(R.string.key_xdrip_aidl_debug_log, true)

     init {
        // ✅ 触发 Dagger 注入（AAPS 要求）
        (context as? HasAndroidInjector)?.androidInjector()?.inject(this)
            ?: throw IllegalStateException("Context must implement HasAndroidInjector")
    }   

    override fun advancedFilteringSupported(): Boolean = false

    override fun onStartJob(pluginStartReason: String): Boolean {
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PLUGIN_START] ========== xDrip AIDL Plugin Starting ($pluginStartReason) ==========")

        if (isEnabled) {
            logPluginFlow("INITIALIZATION", "Initializing xDrip AIDL service")
            initializeService()
        }
        return true
    }

    override fun onStopJob(reason: String) {
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PLUGIN_STOP] ========== xDrip AIDL Plugin Stopping ($reason) ==========")

        xdripService?.cleanup()
        disposable.clear()
    }

    override fun specialEnableCondition(): Boolean = isEnabled

    private fun initializeService() {
        // 注意：现在可以安全使用 rxBus, aapsSchedulers 等（因为已注入）
        xdripService = XdripAidlService(context, aapsLogger).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: BgData) {
                    handleNewBgData(data)
                }
                override fun onConnectionStateChanged(connected: Boolean) {
                    handleConnectionStateChanged(connected)
                }
                override fun onError(error: String) {
                    handleServiceError(error)
                }
            })
            connect()
        }
    }

    private fun handleNewBgData(data: BgData) {
        totalDataReceived++
        val startTime = System.currentTimeMillis()
        val processId = UUID.randomUUID().toString().substring(0, 8)
        
        logPluginFlow("DATA_PROCESS_START_$processId", 
            "Starting data processing for BG: ${data.glucose} mg/dL")

        // 1. 数据验证
        logPluginFlow("VALIDATION_START_$processId", "Starting data validation")
        if (!validateBgData(data)) {
            totalDataRejected++
            logPluginFlow("VALIDATION_FAIL_$processId", "Data validation failed")
            return
        }

        // 2. 防止重复处理
        val lastTimestamp = lastProcessedTimestamp.get()
        if (data.timestamp <= lastTimestamp) {
            totalDataRejected++
            logPluginFlow("DUPLICATE_SKIP_$processId", "Duplicate data, skipping processing")
            return
        }

        // 3. 检查血糖变化是否显著
        val glucoseDelta = Math.abs(data.glucose - lastGlucoseValue)
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (glucoseDelta < minGlucoseDelta && dataAgeMinutes < 2) {
            totalDataRejected++
            logPluginFlow("SMALL_DELTA_SKIP_$processId", "Glucose change too small, skipping")
            return
        }

        // 4. 转换为AAPS格式
        val glucoseValue = convertToGlucoseValue(data)

        // 5. 存储到数据库
        storeGlucoseValue(glucoseValue)

        // 6. 更新内部状态
        lastProcessedTimestamp.set(data.timestamp)
        lastGlucoseValue = data.glucose
        lastProcessedTime = System.currentTimeMillis()

        // 7. 触发数据更新事件
        triggerDataUpdateEvents(glucoseValue)

        // 8. 检查是否需要触发循环
        if (shouldTriggerLoop(data)) {
            logPluginFlow("LOOP_TRIGGER_$processId", "Loop conditions met, triggering loop processing")
            triggerLoopProcessing(data)
        }

        val processTime = System.currentTimeMillis() - startTime
        processingTimes.add(processTime)
        totalDataProcessed++
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PROCESS_SUCCESS_$processId] Successfully processed xDrip data: " +
            "${data.glucose} mg/dL (${data.direction}) in ${processTime}ms")
    }

    private fun validateBgData(data: BgData): Boolean {
        // 检查数据有效性
        if (!data.isValid()) {
            totalDataErrors++
            return false
        }

        // 检查数据年龄
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (dataAgeMinutes > maxDataAgeMinutes) {
            totalDataErrors++
            return false
        }

        return true
    }

    private fun convertToGlucoseValue(data: BgData): GlucoseValue {
        val noiseValue = mapNoise(data.noise)
        val direction = mapDirection(data.direction)
        
        return GlucoseValue(
            timestamp = data.timestamp,
            value = data.glucose,
            raw = data.unfiltered,
            noise = noiseValue,
            trendArrow = direction,
            source = "xDrip AIDL"
        ).apply {
            isValid = data.isValid()
        }
    }

    private fun mapNoise(noise: String?): Double {
        return when {
            noise == null -> 0.0
            noise.contains("high", ignoreCase = true) -> 2.0
            noise.contains("medium", ignoreCase = true) -> 1.0
            noise.contains("low", ignoreCase = true) -> 0.5
            else -> 0.0
        }
    }

    private fun mapDirection(direction: String?): String {
        return when (direction) {
            "DoubleUp" -> "DoubleUp"
            "SingleUp" -> "SingleUp"
            "FortyFiveUp" -> "FortyFiveUp"
            "Flat" -> "Flat"
            "FortyFiveDown" -> "FortyFiveDown"
            "SingleDown" -> "SingleDown"
            "DoubleDown" -> "DoubleDown"
            else -> direction ?: "NONE"
        }
    }

    private fun storeGlucoseValue(glucoseValue: GlucoseValue) {
        //val nsClient = activePlugin.activeNsClient
        //if (nsClient == null) {
        //    totalDataErrors++
        //    return
        //}

        disposable.add(
            nsClient.nsAdd(glucoseValue)
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe(
                    { 
                        aapsLogger.info(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_SUCCESS] Successfully stored glucose value " +
                            "${glucoseValue.value} mg/dL to Nightscout")
                    },
                    { error -> 
                        totalDataErrors++
                        aapsLogger.error(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_ERROR] Error storing glucose to Nightscout", error)
                    }
                )
        )
    }

    private fun triggerDataUpdateEvents(glucoseValue: GlucoseValue) {
        // 触发新历史数据事件
        rxBus.send(EventNewHistoryData(glucoseValue.timestamp))

        // 通知IobCobCalculator更新
        //val calculator = activePlugin.activeIobCobCalculator
        //if (calculator != null) {
        //    calculator.updateLatestBg(
        //        glucoseValue.value,
        //        glucoseValue.timestamp,
        //        glucoseValue.trendArrow ?: ""
        //    )
        //}
    }

    private fun shouldTriggerLoop(data: BgData): Boolean {
        //val loopPlugin = activePlugin.activeLoop
        //if (loopPlugin == null || !loopPlugin.isEnabled) {
        //    return false
        //}

        // 检查是否达到最小处理间隔
        val timeSinceLastLoop = System.currentTimeMillis() - loopPlugin.lastRun
        val minLoopInterval = T.mins(5).msecs()
        
        if (timeSinceLastLoop < minLoopInterval) {
            return false
        }

        return true
    }

    private fun triggerLoopProcessing(data: BgData) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_LOOP_TRIGGER] Triggering loop processing from xDrip data: " +
            "${data.glucose} mg/dL")
        
        //val loopPlugin = activePlugin.activeLoop
        //if (loopPlugin != null) {
        //    val reason = "Triggered by xDrip AIDL (BG: ${data.glucose} mg/dL)"
        //    loopPlugin.invoke(reason, false)
        //}
    }

    private fun handleConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CONNECTION_STATE_UPDATE] Connection state changed: $connected")
    }

    private fun handleServiceError(error: String) {
        totalDataErrors++
        aapsLogger.error(LTag.XDRIP, 
            "[${TEST_TAG}_SERVICE_ERROR_DETAIL] Service error received: $error")
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun getDataAge(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / 1000
    }
    
    private fun getAverageProcessTime(): Long {
        return if (processingTimes.isNotEmpty()) {
            processingTimes.average().toLong()
        } else {
            0
        }
    }
    
    private fun logPluginFlow(stage: String, message: String) {
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_FLOW_${stage}] $message")
        }
    }

    // ✅ 移除 suspend 函数（插件 API 应为同步或事件驱动）
    // 若必须异步，应通过回调或 Rx 返回 Observable
    fun getLatestBgData(): BgData? {
        return xdripService?.getLatestBgData()
    }

    fun isConnected(): Boolean {
        return xdripService?.checkConnectionStatus() ?: false
    }

    override fun getRawData(): RawDisplayData {
        return RawDisplayData().apply {
            val glucose = lastGlucoseValue
            val timestamp = lastProcessedTimestamp.get()
            val source = "xDrip+ AIDL"
        }
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        return xdripService?.connectionState?.value
    }
    
    fun getServiceStatistics(): Map<String, Any>? {
        return xdripService?.getStatistics()
    }

    // 手动获取数据（改为同步或使用 Rx）
    fun fetchLatestDataManually() {
        val data = getLatestBgData()
        data?.let { handleNewBgData(it) }
    }
    
    fun getPluginStatistics(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_data_received"] = totalDataReceived
        stats["total_data_processed"] = totalDataProcessed
        stats["total_data_rejected"] = totalDataRejected
        stats["total_data_errors"] = totalDataErrors
        stats["last_processed_time"] = lastProcessedTime
        stats["last_glucose_value"] = lastGlucoseValue
        stats["last_processed_timestamp"] = lastProcessedTimestamp.get()
        stats["average_process_time_ms"] = getAverageProcessTime()
        stats["debug_logging_enabled"] = debugLogging
        stats["plugin_enabled"] = isEnabled
        stats["service_connected"] = xdripService?.checkConnectionStatus() ?: false
        
        return stats
    }
}

*/

/* 简化版
package app.aaps.plugins.source.xDripAidl

// ✅ 正确 import AIDL 生成的接口和数据类
import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import com.eveningoutpost.dexdrip.BgData  // 这个类必须存在（Parcelable）

import android.content.Context
import dagger.android.AndroidInjector
import dagger.android.HasAndroidInjector
//import info.nightscout.androidaps.R
import app.aaps.core.ui.R // 👈 R 文件替换为新版

import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.DataSourcePlugin
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.plugin.PluginType
//import app.aaps.core.interfaces.plugin.ActivePluginProvider
import app.aaps.core.interfaces.rx.bus.RxBus                    // ✅ 新路径
import app.aaps.core.utils.resources.ResourceHelper             // ✅ 新路径
import app.aaps.core.interfaces.rx.AapsSchedulers                    // ✅ 新路径

import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.rx.events.EventNewBG

import app.aaps.core.interfaces.logging.AAPSLogger              
import app.aaps.core.interfaces.logging.LTag                
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject


class XDripPlugin(
    private val pluginDescription: PluginDescription,
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP,
    private val context: Context,
) : PluginBase(pluginDescription, aapsLogger, rh), DataSourcePlugin {

    @Inject lateinit var rxBus: app.aaps.core.interfaces.rx.bus.RxBus

    private companion object {
        const val PREF_ENABLED = "key_xdrip_aidl_enabled"
        const val TAG = "XDripAidlPlugin"
    }

    private var service: IBgDataService? = null
    private val callback = object : IBgDataCallback.Stub() {
        override fun onNewBgData(data: BgData?) {
            data?.let { bgData ->
                if (!bgData.isValid()) {
                    aapsLogger.debug("$TAG: Received invalid BgData (glucose=${bgData.glucose})")
                    return
                }
                val glucoseValue = convertToGlucoseValue(bgData)
                CoroutineScope(Dispatchers.Main).launch {
                    rxBus.send(EventNewBg(glucoseValue))
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IBgDataService.Stub.asInterface(binder)
            try {
                service?.registerCallback(callback)
                aapsLogger.info("$TAG: Connected to xDrip service")
            } catch (e: RemoteException) {
                aapsLogger.error("$TAG: Failed to register callback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            aapsLogger.warn("$TAG: xDrip service disconnected")
        }
    }

    override fun onStartJob() {
        if (!sp.getBoolean(PREF_ENABLED, false)) return

        val intent = Intent("com.eveningoutpost.dexdrip.BgDataService")
        intent.setPackage("com.eveningoutpost.dexdrip")
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            aapsLogger.error("$TAG: Failed to bind to xDrip", e)
        }
    }

    override fun onStopJob() {
        try {
            service?.unregisterCallback(callback)
        } catch (e: RemoteException) {
            aapsLogger.error("$TAG: Error unregistering callback", e)
        }
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // Not bound, ignore
        }
    }

    override suspend fun getLatestBgData(): GlucoseValue? {
        // Optional: implement pull-based retrieval if needed
        return null
    }

    private fun convertToGlucoseValue(bgData: BgData): GlucoseValue {
        return GlucoseValue(
            timestamp = bgData.timestamp,
            value = bgData.glucose,
            source = bgData.source ?: "xDripAidl"
        )
    }
}
*/


/*
package app.aaps.plugins.source.xDripAidl

import android.content.Context
import androidx.annotation.NonNull
import androidx.lifecycle.LifecycleOwner
import com.eveningoutpost.dexdrip.BgData
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.data.GlucoseValue
import info.nightscout.androidaps.extensions.convertTo
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.data.AutosensData
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventNewHistoryData
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class XDripPlugin @Inject constructor(
    injector: HasAndroidInjector,
    @NonNull private val aapsLogger: AAPSLogger,
    @NonNull private val rh: ResourceHelper,
    @NonNull private val sp: SP,
    @NonNull private val context: Context,
    @NonNull private val rxBus: RxBus,
    @NonNull private val aapsSchedulers: AapsSchedulers,
    @NonNull private val dateUtil: DateUtil,
    @NonNull private val activePlugin: ActivePluginProvider,
    @NonNull private val lifecycleOwner: LifecycleOwner
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.DATASOURCE)
        .fragmentClass(XDripFragment::class.java.name)
        .pluginName(R.string.xdrip_aidl)
        .shortName(R.string.xdrip_aidl_short)
        .preferencesId(R.xml.pref_xdrip_aidl)
        .description(R.string.xdrip_aidl_description),
    aapsLogger, rh, sp
), DataSourcePlugin {

    companion object {
        private const val TEST_TAG = "XDripPlugin_TEST"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val disposable = CompositeDisposable()

    private var xdripService: XdripAidlService? = null
    private val lastProcessedTimestamp = AtomicLong(0)
    private var lastGlucoseValue: Double = 0.0
    
    // 处理统计
    private var totalDataReceived = 0
    private var totalDataProcessed = 0
    private var totalDataRejected = 0
    private var totalDataErrors = 0
    private var lastProcessedTime = 0L
    private val processingTimes = mutableListOf<Long>()

    // 配置
    private val isEnabled: Boolean
        get() = sp.getBoolean(R.string.key_xdrip_aidl_enabled, true)

    private val maxDataAgeMinutes: Long
        get() = sp.getLong(R.string.key_xdrip_aidl_max_age, 15)

    private val minGlucoseDelta: Double
        get() = sp.getDouble(R.string.key_xdrip_aidl_min_delta, 2.0)
    
    // 调试模式
    private val debugLogging: Boolean
        get() = sp.getBoolean(R.string.key_xdrip_aidl_debug_log, true)  // 默认开启调试

    override fun advancedFilteringSupported(): Boolean = false

    override fun onStart() {
        super.onStart()
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PLUGIN_START] ========== xDrip AIDL Plugin Starting ==========")
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CONFIGURATION] Plugin Configuration - " +
            "Enabled: $isEnabled, " +
            "Max Age: ${maxDataAgeMinutes} minutes, " +
            "Min Delta: $minGlucoseDelta mg/dL, " +
            "Debug Logging: $debugLogging")

        if (isEnabled) {
            logPluginFlow("INITIALIZATION", "Initializing xDrip AIDL service")
            initializeService()
        } else {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_PLUGIN_DISABLED] Plugin is disabled in settings, will not connect to xDrip")
            logPluginFlow("DISABLED", "Plugin disabled in preferences")
        }
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PLUGIN_START_COMPLETE] =======================================")
    }

    override fun onStop() {
        super.onStop()
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PLUGIN_STOP] ========== xDrip AIDL Plugin Stopping ==========")
        
        // 输出详细的处理统计
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PROCESSING_STATISTICS] Plugin Processing Statistics:")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_DATA_FLOW] Data Flow Stats: " +
            "Total Received: $totalDataReceived | " +
            "Total Processed: $totalDataProcessed | " +
            "Total Rejected: $totalDataRejected | " +
            "Total Errors: $totalDataErrors")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_PERFORMANCE] Performance Stats: " +
            "Average Process Time: ${getAverageProcessTime()}ms | " +
            "Last Processed: ${formatTime(lastProcessedTime)} | " +
            "Last BG Value: $lastGlucoseValue mg/dL")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_STATE] Current State: " +
            "Last Timestamp: ${formatTime(lastProcessedTimestamp.get())} | " +
            "Service Connected: ${xdripService?.checkConnectionStatus() ?: false}")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_PLUGIN_STOP_COMPLETE] =======================================")
        
        xdripService?.cleanup()
        disposable.clear()
    }

    override fun specialEnableCondition(): Boolean = isEnabled

    private fun initializeService() {
        logPluginFlow("SERVICE_INIT_START", "Initializing xDrip BG data service")
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_SERVICE_INIT] Creating XdripAidlService instance")

        xdripService = XdripAidlService(context, aapsLogger, lifecycleOwner.lifecycle).apply {
            addListener(object : XdripAidlService.XdripDataListener {
                override fun onNewBgData(data: BgData) {
                    logPluginFlow("LISTENER_TRIGGERED", "Service listener received new BG data")
                    aapsLogger.debug(LTag.XDRIP,
                        "[${TEST_TAG}_LISTENER_CALL] onNewBgData() called with data: " +
                        "BG=${data.glucose} mg/dL, Time=${formatTime(data.timestamp)}")
                    handleNewBgData(data)
                }

                override fun onConnectionStateChanged(connected: Boolean) {
                    logPluginFlow("CONNECTION_CHANGE", "Connection state changed: $connected")
                    aapsLogger.info(LTag.XDRIP,
                        "[${TEST_TAG}_CONNECTION_UPDATE] Connection state updated: $connected")
                    handleConnectionStateChanged(connected)
                }

                override fun onError(error: String) {
                    logPluginFlow("SERVICE_ERROR", "Service reported error: $error")
                    aapsLogger.error(LTag.XDRIP,
                        "[${TEST_TAG}_SERVICE_ERROR] Service error received: $error")
                    handleServiceError(error)
                }
            })

            // 连接服务
            logPluginFlow("SERVICE_CONNECT_INIT", "Initiating service connection to xDrip")
            aapsLogger.info(LTag.XDRIP,
                "[${TEST_TAG}_CONNECT_INIT] Calling connect() on xDrip service")
            connect()
        }

        // 监听连接状态
        xdripService?.connectionState?.value?.let { state ->
            logPluginFlow("INITIAL_CONNECTION_STATE", "Initial connection state: $state")
            aapsLogger.info(LTag.XDRIP,
                "[${TEST_TAG}_INITIAL_STATE] Initial connection state: $state")
            
            when (state) {
                is XdripAidlService.ConnectionState.Connected -> {
                    aapsLogger.info(LTag.XDRIP, 
                        "[${TEST_TAG}_CONNECT_SUCCESS] Successfully connected to xDrip AIDL service")
                    logPluginFlow("CONNECTION_SUCCESS", "Connected to xDrip service")
                }
                is XdripAidlService.ConnectionState.Error -> {
                    aapsLogger.error(LTag.XDRIP, 
                        "[${TEST_TAG}_CONNECT_ERROR] Connection error: ${state.message}")
                    logPluginFlow("CONNECTION_ERROR", "Connection failed: ${state.message}")
                }
                else -> {
                    aapsLogger.debug(LTag.XDRIP, 
                        "[${TEST_TAG}_CONNECT_PENDING] Connection pending: $state")
                    logPluginFlow("CONNECTION_PENDING", "Connection in progress: $state")
                }
            }
        }
        
        logPluginFlow("SERVICE_INIT_COMPLETE", "xDrip service initialization completed")
    }

    private fun handleNewBgData(data: BgData) {
        totalDataReceived++
        val startTime = System.currentTimeMillis()
        val processId = UUID.randomUUID().toString().substring(0, 8)
        
        logPluginFlow("DATA_PROCESS_START_$processId", 
            "Starting data processing for BG: ${data.glucose} mg/dL (Process ID: $processId)")
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_DATA_PROCESS_$processId] Processing received data: " +
            "BG=${data.glucose} mg/dL, " +
            "Time=${formatTime(data.timestamp)}, " +
            "Direction=${data.direction}, " +
            "Total Received: $totalDataReceived")
        
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_DATA_DETAILS_$processId] Detailed data info: " +
            "Timestamp: ${data.timestamp} (${formatTime(data.timestamp)}) | " +
            "Data Age: ${getDataAge(data.timestamp)} seconds | " +
            "Direction: ${data.direction} | " +
            "Noise: ${data.noise} | " +
            "Source: ${data.source} | " +
            "Filtered: ${data.filtered} | " +
            "Unfiltered: ${data.unfiltered} | " +
            "Sensor Battery: ${data.sensorBatteryLevel}% | " +
            "Transmitter Battery: ${data.transmitterBatteryLevel}%")

        // 1. 数据验证
        logPluginFlow("VALIDATION_START_$processId", "Starting data validation")
        if (!validateBgData(data)) {
            totalDataRejected++
            aapsLogger.warn(LTag.XDRIP,
                "[${TEST_TAG}_VALIDATION_FAIL_$processId] Data validation failed, rejecting data")
            logPluginFlow("VALIDATION_FAIL_$processId", "Data validation failed")
            return
        }
        logPluginFlow("VALIDATION_PASS_$processId", "Data validation passed")

        // 2. 防止重复处理
        val lastTimestamp = lastProcessedTimestamp.get()
        if (data.timestamp <= lastTimestamp) {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_DUPLICATE_DATA_$processId] Duplicate data detected, skipping. " +
                "Current timestamp: ${formatTime(data.timestamp)} " +
                "Last processed: ${formatTime(lastTimestamp)} " +
                "Time difference: ${data.timestamp - lastTimestamp}ms")
            totalDataRejected++
            logPluginFlow("DUPLICATE_SKIP_$processId", "Duplicate data, skipping processing")
            return
        }
        logPluginFlow("UNIQUE_DATA_$processId", "Data is unique, continuing processing")

        // 3. 检查血糖变化是否显著
        val glucoseDelta = Math.abs(data.glucose - lastGlucoseValue)
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (glucoseDelta < minGlucoseDelta && dataAgeMinutes < 2) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_SMALL_DELTA_$processId] Glucose change too small, skipping. " +
                "Delta: $glucoseDelta mg/dL (min: $minGlucoseDelta) | " +
                "Data Age: $dataAgeMinutes minutes | " +
                "Last BG: $lastGlucoseValue mg/dL")
            totalDataRejected++
            logPluginFlow("SMALL_DELTA_SKIP_$processId", "Glucose change too small, skipping")
            return
        }
        logPluginFlow("SIGNIFICANT_DELTA_$processId", 
            "Significant glucose change detected: $glucoseDelta mg/dL")

        logPluginFlow("PREPROCESS_COMPLETE_$processId", 
            "All pre-processing checks passed, starting main processing")

        // 4. 转换为AAPS格式
        logPluginFlow("CONVERSION_START_$processId", "Converting BgData to GlucoseValue")
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_CONVERSION_START_$processId] Converting xDrip BgData to AAPS GlucoseValue")
        val glucoseValue = convertToGlucoseValue(data)
        logPluginFlow("CONVERSION_COMPLETE_$processId", 
            "Converted to GlucoseValue: ${glucoseValue.value} mg/dL at ${formatTime(glucoseValue.timestamp)}")
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_CONVERSION_RESULT_$processId] Conversion result: " +
            "Value: ${glucoseValue.value} mg/dL | " +
            "Raw: ${glucoseValue.raw} | " +
            "Noise: ${glucoseValue.noise} | " +
            "Trend: ${glucoseValue.trendArrow} | " +
            "Source: ${glucoseValue.source}")

        // 5. 存储到数据库
        logPluginFlow("STORAGE_START_$processId", "Storing GlucoseValue to database/Nightscout")
        storeGlucoseValue(glucoseValue)

        // 6. 更新内部状态
        lastProcessedTimestamp.set(data.timestamp)
        lastGlucoseValue = data.glucose
        lastProcessedTime = System.currentTimeMillis()
        
        logPluginFlow("STATE_UPDATED_$processId", 
            "Internal state updated - " +
            "Last BG: $lastGlucoseValue mg/dL | " +
            "Last time: ${formatTime(lastProcessedTimestamp.get())}")
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_STATE_UPDATE_$processId] Updated plugin state: " +
            "Last Glucose: $lastGlucoseValue mg/dL | " +
            "Last Timestamp: ${lastProcessedTimestamp.get()} (${formatTime(lastProcessedTimestamp.get())})")

        // 7. 触发数据更新事件
        logPluginFlow("EVENT_TRIGGER_START_$processId", "Triggering data update events in AAPS")
        triggerDataUpdateEvents(glucoseValue)

        // 8. 检查是否需要触发循环
        logPluginFlow("LOOP_CHECK_START_$processId", "Checking if loop should be triggered")
        if (shouldTriggerLoop(data)) {
            logPluginFlow("LOOP_TRIGGER_$processId", "Loop conditions met, triggering loop processing")
            triggerLoopProcessing(data)
        } else {
            logPluginFlow("LOOP_SKIP_$processId", "Loop conditions not met, skipping loop trigger")
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_LOOP_SKIP_$processId] Loop not triggered based on current conditions")
        }

        val processTime = System.currentTimeMillis() - startTime
        processingTimes.add(processTime)
        totalDataProcessed++
        
        logPluginFlow("PROCESS_COMPLETE_$processId", 
            "Data processing completed in ${processTime}ms. " +
            "Stats: Processed $totalDataProcessed/$totalDataReceived " +
            "(Rejected: $totalDataRejected, Errors: $totalDataErrors)")
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_PROCESS_SUCCESS_$processId] Successfully processed xDrip data: " +
            "${data.glucose} mg/dL (${data.direction}) in ${processTime}ms")
    }

    private fun validateBgData(data: BgData): Boolean {
        logPluginFlow("VALIDATION_DETAILED_START", "Starting detailed data validation")
        
        // 检查数据有效性
        if (!data.isValid()) {
            totalDataErrors++
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_VALIDATION_INVALID] Invalid glucose value: ${data.glucose} mg/dL")
            logPluginFlow("VALIDATION_FAIL_INVALID", "Invalid glucose value")
            return false
        }

        // 检查数据年龄
        val dataAgeMinutes = getDataAge(data.timestamp) / 60
        if (dataAgeMinutes > maxDataAgeMinutes) {
            totalDataErrors++
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_VALIDATION_OLD] Data too old: " +
                "${dataAgeMinutes} minutes > $maxDataAgeMinutes minutes limit")
            logPluginFlow("VALIDATION_FAIL_OLD", "Data too old: ${dataAgeMinutes} minutes")
            return false
        }

        // 检查噪声等级
        if (data.isNoisy()) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_VALIDATION_NOISY] Noisy data detected: ${data.noise}")
            logPluginFlow("VALIDATION_WARN_NOISY", "Noisy data: ${data.noise}")
            // 噪声数据仍然处理，但记录警告
        }

        // 检查传感器电量
        if (data.sensorBatteryLevel in 1..10) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_VALIDATION_LOW_BATTERY] Low sensor battery: ${data.sensorBatteryLevel}%")
            logPluginFlow("VALIDATION_WARN_BATTERY", "Low battery: ${data.sensorBatteryLevel}%")
        }

        // 检查发射器电量
        if (data.transmitterBatteryLevel in 1..10) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_VALIDATION_LOW_TRANSMITTER] Low transmitter battery: ${data.transmitterBatteryLevel}%")
            logPluginFlow("VALIDATION_WARN_TRANSMITTER", "Low transmitter battery")
        }

        logPluginFlow("VALIDATION_PASS_ALL", "All validation checks passed")
        return true
    }

    private fun convertToGlucoseValue(data: BgData): GlucoseValue {
        logPluginFlow("CONVERSION_DETAILED_START", "Starting detailed conversion")
        
        val noiseValue = mapNoise(data.noise)
        val direction = mapDirection(data.direction)
        
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_CONVERSION_PARAMS] Conversion parameters: " +
            "Noise mapping: '${data.noise}' -> $noiseValue | " +
            "Direction mapping: '${data.direction}' -> '$direction'")
        
        return GlucoseValue(
            timestamp = data.timestamp,
            value = data.glucose,
            raw = data.unfiltered,
            noise = noiseValue,
            trendArrow = direction,
            source = "xDrip AIDL"
        ).apply {
            isValid = data.isValid()
            
            if (debugLogging) {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_CONVERSION_RESULT] GlucoseValue created: " +
                    "Timestamp: $timestamp | " +
                    "Value: $value mg/dL | " +
                    "Raw: $raw | " +
                    "Noise: $noise | " +
                    "Trend: $trendArrow | " +
                    "Source: $source | " +
                    "IsValid: $isValid")
            }
            
            logPluginFlow("CONVERSION_RESULT_DETAILED", 
                "Created GlucoseValue with timestamp $timestamp, value $value")
        }
    }

    private fun mapNoise(noise: String?): Double {
        val result = when {
            noise == null -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_NOISE_MAP_NULL] Noise string is null, mapping to 0.0")
                0.0
            }
            noise.contains("high", ignoreCase = true) -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_NOISE_MAP_HIGH] High noise detected: '$noise' -> 2.0")
                2.0
            }
            noise.contains("medium", ignoreCase = true) -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_NOISE_MAP_MEDIUM] Medium noise detected: '$noise' -> 1.0")
                1.0
            }
            noise.contains("low", ignoreCase = true) -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_NOISE_MAP_LOW] Low noise detected: '$noise' -> 0.5")
                0.5
            }
            else -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_NOISE_MAP_DEFAULT] Default noise mapping: '$noise' -> 0.0")
                0.0
            }
        }
        
        return result
    }

    private fun mapDirection(direction: String?): String {
        val result = when (direction) {
            "DoubleUp" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_DOUBLE_UP] Mapping 'DoubleUp'")
                "DoubleUp"
            }
            "SingleUp" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_SINGLE_UP] Mapping 'SingleUp'")
                "SingleUp"
            }
            "FortyFiveUp" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_45_UP] Mapping 'FortyFiveUp'")
                "FortyFiveUp"
            }
            "Flat" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_FLAT] Mapping 'Flat'")
                "Flat"
            }
            "FortyFiveDown" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_45_DOWN] Mapping 'FortyFiveDown'")
                "FortyFiveDown"
            }
            "SingleDown" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_SINGLE_DOWN] Mapping 'SingleDown'")
                "SingleDown"
            }
            "DoubleDown" -> {
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_DOUBLE_DOWN] Mapping 'DoubleDown'")
                "DoubleDown"
            }
            else -> {
                val default = direction ?: "NONE"
                aapsLogger.debug(LTag.XDRIP,
                    "[${TEST_TAG}_DIRECTION_DEFAULT] Mapping '$direction' to default: '$default'")
                default
            }
        }
        
        return result
    }

    private fun storeGlucoseValue(glucoseValue: GlucoseValue) {
        val nsClient = activePlugin.activeNsClient
        if (nsClient == null) {
            totalDataErrors++
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_STORAGE_NO_CLIENT] No active Nightscout client available")
            logPluginFlow("STORAGE_FAIL_NO_CLIENT", "No Nightscout client available")
            return
        }

        logPluginFlow("STORAGE_NS_START", "Sending GlucoseValue to Nightscout")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STORAGE_START] Storing glucose value ${glucoseValue.value} mg/dL to Nightscout")
        
        disposable.add(
            nsClient.nsAdd(glucoseValue)
                .subscribeOn(aapsSchedulers.io)
                .observeOn(aapsSchedulers.main)
                .subscribe(
                    { 
                        aapsLogger.info(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_SUCCESS] Successfully stored glucose value " +
                            "${glucoseValue.value} mg/dL to Nightscout at ${formatTime(glucoseValue.timestamp)}")
                        logPluginFlow("STORAGE_SUCCESS", "Nightscout storage completed")
                    },
                    { error -> 
                        totalDataErrors++
                        aapsLogger.error(LTag.XDRIP, 
                            "[${TEST_TAG}_STORAGE_ERROR] Error storing glucose to Nightscout", error)
                        logPluginFlow("STORAGE_ERROR", "Nightscout storage failed: ${error.message}")
                    }
                )
        )
    }

    private fun triggerDataUpdateEvents(glucoseValue: GlucoseValue) {
        // 触发新历史数据事件
        logPluginFlow("EVENT_NEW_HISTORY", "Sending EventNewHistoryData to RxBus")
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_EVENT_SEND] Sending EventNewHistoryData for timestamp: ${glucoseValue.timestamp}")
        
        rxBus.send(EventNewHistoryData(glucoseValue.timestamp))
        
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_EVENT_SENT] EventNewHistoryData sent for timestamp: ${glucoseValue.timestamp}")

        // 通知IobCobCalculator更新
        val calculator = activePlugin.activeIobCobCalculator
        if (calculator != null) {
            logPluginFlow("UPDATE_IOB_COB", "Updating IobCobCalculator with new BG value")
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_CALCULATOR_UPDATE] Updating IobCobCalculator with: " +
                "BG=${glucoseValue.value} mg/dL, " +
                "Timestamp=${glucoseValue.timestamp}, " +
                "Trend=${glucoseValue.trendArrow}")
            
            calculator.updateLatestBg(
                glucoseValue.value,
                glucoseValue.timestamp,
                glucoseValue.trendArrow ?: ""
            )
            
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_CALCULATOR_UPDATED] IobCobCalculator updated successfully")
        } else {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_CALCULATOR_MISSING] No active IobCobCalculator available")
            logPluginFlow("CALCULATOR_MISSING", "IobCobCalculator not available")
        }
        
        logPluginFlow("EVENTS_COMPLETE", "All data update events triggered successfully")
    }

    private fun shouldTriggerLoop(data: BgData): Boolean {
        logPluginFlow("LOOP_CONDITION_CHECK", "Checking loop trigger conditions")
        
        val loopPlugin = activePlugin.activeLoop
        if (loopPlugin == null || !loopPlugin.isEnabled) {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_LOOP_DISABLED] Loop plugin is disabled or not available")
            logPluginFlow("LOOP_CONDITION_DISABLED", "Loop plugin disabled or unavailable")
            return false
        }

        // 检查是否达到最小处理间隔
        val timeSinceLastLoop = System.currentTimeMillis() - loopPlugin.lastRun
        val minLoopInterval = T.mins(5).msecs()
        
        if (timeSinceLastLoop < minLoopInterval) {
            val remainingSec = (minLoopInterval - timeSinceLastLoop) / 1000
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_LOOP_INTERVAL] Too soon since last loop: " +
                "${timeSinceLastLoop}ms < ${minLoopInterval}ms (wait ${remainingSec}s)")
            logPluginFlow("LOOP_CONDITION_INTERVAL", 
                "Interval too short: ${remainingSec}s remaining")
            return false
        }

        // 检查是否处于活动时段
        if (!loopPlugin.isSuperBolus && !loopPlugin.isTempTarget) {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_LOOP_INACTIVE] Loop not in active state " +
                "(super bolus or temp target required)")
            logPluginFlow("LOOP_CONDITION_INACTIVE", "Loop not in active state")
            return false
        }

        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_LOOP_CONDITIONS_MET] All loop conditions met: " +
            "Time since last: ${timeSinceLastLoop}ms, " +
            "SuperBolus: ${loopPlugin.isSuperBolus}, " +
            "TempTarget: ${loopPlugin.isTempTarget}")
        logPluginFlow("LOOP_CONDITIONS_MET", "All loop conditions satisfied")
        return true
    }

    private fun triggerLoopProcessing(data: BgData) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_LOOP_TRIGGER] Triggering loop processing from xDrip data: " +
            "${data.glucose} mg/dL (${data.direction})")
        logPluginFlow("LOOP_INVOKE_START", "Invoking loop plugin")
        
        val loopPlugin = activePlugin.activeLoop
        if (loopPlugin != null) {
            val reason = "Triggered by xDrip AIDL (BG: ${data.glucose} mg/dL, Direction: ${data.direction})"
            aapsLogger.info(LTag.XDRIP,
                "[${TEST_TAG}_LOOP_INVOKE] Calling loopPlugin.invoke() with reason: $reason")
            
            loopPlugin.invoke(reason, false)
            
            aapsLogger.info(LTag.XDRIP, 
                "[${TEST_TAG}_LOOP_INVOKED] Loop plugin invoked successfully")
            logPluginFlow("LOOP_INVOKE_SUCCESS", "Loop plugin invoked")
        } else {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_LOOP_FAIL] Failed to get loop plugin instance")
            logPluginFlow("LOOP_INVOKE_FAIL", "Loop plugin not available")
        }
    }

    private fun handleConnectionStateChanged(connected: Boolean) {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CONNECTION_STATE_UPDATE] Connection state changed: $connected")
        
        if (!connected) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_CONNECTION_LOST] xDrip connection lost, auto-reconnect will be attempted")
            logPluginFlow("CONNECTION_LOST", "Connection lost, auto-reconnect enabled")
        } else {
            aapsLogger.info(LTag.XDRIP,
                "[${TEST_TAG}_CONNECTION_RESTORED] xDrip connection restored successfully")
            logPluginFlow("CONNECTION_RESTORED", "Connection restored")
        }
    }

    private fun handleServiceError(error: String) {
        totalDataErrors++
        aapsLogger.error(LTag.XDRIP, 
            "[${TEST_TAG}_SERVICE_ERROR_DETAIL] Service error received: $error")
        logPluginFlow("SERVICE_ERROR_DETAILED", "Service error: $error")
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun getDataAge(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / 1000
    }
    
    private fun getAverageProcessTime(): Long {
        return if (processingTimes.isNotEmpty()) {
            processingTimes.average().toLong()
        } else {
            0
        }
    }
    
    private fun logPluginFlow(stage: String, message: String) {
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_FLOW_${stage}] $message")
        }
    }

    // 公开API
    suspend fun getLatestBgData(): BgData? {
        logPluginFlow("API_GET_DATA_START", "Manual data request via public API")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_API_GET_DATA] Manual data fetch requested via getLatestBgData()")
        
        val data = xdripService?.getLatestBgData()
        
        if (data != null) {
            aapsLogger.info(LTag.XDRIP,
                "[${TEST_TAG}_API_SUCCESS] Manual fetch successful: " +
                "BG=${data.glucose} mg/dL, " +
                "Time=${formatTime(data.timestamp)}")
            logPluginFlow("API_GET_DATA_SUCCESS", 
                "Manual fetch returned data: ${data.glucose} mg/dL")
        } else {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_API_NULL] Manual fetch returned null data")
            logPluginFlow("API_GET_DATA_NULL", "Manual fetch returned null")
        }
        
        return data
    }

    fun isConnected(): Boolean {
        val connected = xdripService?.checkConnectionStatus() ?: false
        
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_API_CONNECTION_CHECK] Connection check result: $connected")
        }
        
        return connected
    }

    override fun getRawData(): RawDisplayData {
        val bg = lastGlucoseValue
        val timestamp = lastProcessedTimestamp.get()
        
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_API_RAW_DATA] Raw data requested: " +
                "BG=$bg mg/dL, " +
                "Time=${formatTime(timestamp)}, " +
                "Source=xDrip+ AIDL")
        }
        
        return RawDisplayData().apply {
            glucose = bg
            timestamp = timestamp
            source = "xDrip+ AIDL"
        }
    }

    fun getConnectionState(): XdripAidlService.ConnectionState? {
        val state = xdripService?.connectionState?.value
        
        if (debugLogging && state != null) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_API_CONNECTION_STATE] Connection state: $state")
        }
        
        return state
    }
    
    fun getServiceStatistics(): Map<String, Any>? {
        val stats = xdripService?.getStatistics()
        
        if (debugLogging && stats != null) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_API_SERVICE_STATS] Service statistics retrieved: ${stats.size} items")
        }
        
        return stats
    }

    // 手动获取数据（用于调试）
    fun fetchLatestDataManually() {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_MANUAL_FETCH_START] Starting manual data fetch from xDrip")
        logPluginFlow("MANUAL_FETCH_INIT", "Manual fetch initiated by user")
        
        scope.launch {
            val data = getLatestBgData()
            if (data != null) {
                aapsLogger.info(LTag.XDRIP, 
                    "[${TEST_TAG}_MANUAL_FETCH_SUCCESS] Manual fetch successful, " +
                    "processing data: ${data.glucose} mg/dL")
                logPluginFlow("MANUAL_FETCH_PROCESS", 
                    "Processing manually fetched data: ${data.glucose} mg/dL")
                handleNewBgData(data)
            } else {
                aapsLogger.warn(LTag.XDRIP, 
                    "[${TEST_TAG}_MANUAL_FETCH_FAIL] Manual fetch returned no data from xDrip")
                logPluginFlow("MANUAL_FETCH_FAIL", "Manual fetch returned null")
            }
        }
    }
    
    fun getPluginStatistics(): Map<String, Any> {
        val stats = mutableMapOf<String, Any>()
        stats["total_data_received"] = totalDataReceived
        stats["total_data_processed"] = totalDataProcessed
        stats["total_data_rejected"] = totalDataRejected
        stats["total_data_errors"] = totalDataErrors
        stats["last_processed_time"] = lastProcessedTime
        stats["last_glucose_value"] = lastGlucoseValue
        stats["last_processed_timestamp"] = lastProcessedTimestamp.get()
        stats["average_process_time_ms"] = getAverageProcessTime()
        stats["debug_logging_enabled"] = debugLogging
        stats["plugin_enabled"] = isEnabled
        stats["service_connected"] = xdripService?.checkConnectionStatus() ?: false
        
        if (debugLogging) {
            aapsLogger.debug(LTag.XDRIP,
                "[${TEST_TAG}_PLUGIN_STATS] Plugin statistics calculated: ${stats.size} items")
        }
        
        return stats
    }
}
*/


