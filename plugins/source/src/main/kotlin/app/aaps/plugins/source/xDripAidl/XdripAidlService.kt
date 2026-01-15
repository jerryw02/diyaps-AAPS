package app.aaps.plugins.source.xDripAidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.eveningoutpost.dexdrip.BgData
import com.eveningoutpost.dexdrip.IBgDataCallback
import com.eveningoutpost.dexdrip.IBgDataService
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class XdripAidlService(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val lifecycle: Lifecycle? = null
) : LifecycleObserver {

    companion object {
        private const val XDRIP_PACKAGE = "com.eveningoutpost.dexdrip"
        private const val SERVICE_ACTION = "com.eveningoutpost.dexdrip.BG_DATA_SERVICE"
        private const val BIND_FLAGS = Context.BIND_AUTO_CREATE or
                Context.BIND_IMPORTANT or
                Context.BIND_ABOVE_CLIENT
        
        // 测试标记
        private const val TEST_TAG = "XDripAIDL_TEST"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 状态流
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _latestBgData = MutableStateFlow<BgData?>(null)
    val latestBgData: StateFlow<BgData?> = _latestBgData

    private var service: IBgDataService? = null
    private val isBound = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)

    // 测试计数器
    private var callbackReceivedCount = 0
    private var lastCallbackTime = 0L
    
    // 数据接收统计
    private val dataStatistics = mutableMapOf<String, Any>()

    private val callback = object : IBgDataCallback.Stub() {
        override fun onNewBgData(data: BgData?) {
            callbackReceivedCount++
            lastCallbackTime = System.currentTimeMillis()
            
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_CALLBACK_RECEIVED] Received callback #$callbackReceivedCount")
            
            if (data == null) {
                aapsLogger.warn(LTag.XDRIP, "[${TEST_TAG}_ERROR] Received null data from xDrip")
                dataStatistics["null_data_count"] = (dataStatistics["null_data_count"] as? Int ?: 0) + 1
                return
            }

            // 详细记录接收到的数据
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_DATA_RECEIVED] New BG data: ${data.glucose} mg/dL (${data.direction}) at ${formatTime(data.timestamp)}")
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_DATA_DETAILS] Timestamp: ${data.timestamp} (${formatTime(data.timestamp)}), " +
                "Data age: ${getDataAge(data.timestamp)}s, " +
                "Noise: ${data.noise}, " +
                "Source: ${data.source}, " +
                "Filtered: ${data.filtered}, " +
                "Unfiltered: ${data.unfiltered}")
            
            // 记录数据统计
            dataStatistics["total_received"] = (dataStatistics["total_received"] as? Int ?: 0) + 1
            dataStatistics["last_received_time"] = System.currentTimeMillis()
            dataStatistics["last_bg_value"] = data.glucose
            dataStatistics["last_bg_timestamp"] = data.timestamp

            // 更新最新数据
            _latestBgData.value = data
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_FLOW_UPDATE] Updated latestBgData flow with new data")

            // 记录数据流转
            logDataFlow("AIDL_CALLBACK_RECEIVED", data, "Data received from xDrip AIDL callback")

            // 通知监听器
            val listenerCount = listeners.size
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_NOTIFY_LISTENERS] Notifying $listenerCount listeners")
            
            listeners.forEachIndexed { index, listener ->
                aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_LISTENER_${index}_CALL] Calling listener.onNewBgData()")
                try {
                    listener.onNewBgData(data)
                    aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_LISTENER_${index}_SUCCESS] Listener processed successfully")
                } catch (e: Exception) {
                    aapsLogger.error(LTag.XDRIP, "[${TEST_TAG}_LISTENER_${index}_ERROR] Listener failed: ${e.message}")
                    dataStatistics["listener_errors"] = (dataStatistics["listener_errors"] as? Int ?: 0) + 1
                }
            }
            
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_CALLBACK_COMPLETE] Callback processing completed successfully")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            aapsLogger.info(LTag.XDRIP, 
                "[${TEST_TAG}_SERVICE_CONNECTED] xDrip BG data service connected successfully")
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_CONNECTION_DETAILS] Connected to component: ${name?.className}, " +
                "Binder valid: ${binder != null}")
            
            val startTime = System.currentTimeMillis()

            service = IBgDataService.Stub.asInterface(binder)
            isBound.set(true)
            isConnecting.set(false)
            _connectionState.value = ConnectionState.Connected
            
            // 记录连接成功
            dataStatistics["service_connected_count"] = (dataStatistics["service_connected_count"] as? Int ?: 0) + 1
            dataStatistics["last_service_connect"] = System.currentTimeMillis()

            try {
                // 注册回调
                aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_REGISTER_CALLBACK] Registering callback with xDrip service")
                val registerStart = System.currentTimeMillis()
                service?.registerCallback(callback)
                val registerTime = System.currentTimeMillis() - registerStart
                aapsLogger.info(LTag.XDRIP, 
                    "[${TEST_TAG}_REGISTER_SUCCESS] Callback registered successfully in ${registerTime}ms")

                // 重置计数器
                callbackReceivedCount = 0
                dataStatistics["callback_registration_time"] = registerTime

                // 立即获取最新数据
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_FETCH_AFTER_CONNECT] Fetching latest data after successful connection")
                fetchLatestBgData()

                val totalTime = System.currentTimeMillis() - startTime
                aapsLogger.info(LTag.XDRIP, 
                    "[${TEST_TAG}_CONNECTION_COMPLETE] Service connection and setup completed in ${totalTime}ms")

            } catch (e: RemoteException) {
                aapsLogger.error(LTag.XDRIP, 
                    "[${TEST_TAG}_REGISTER_ERROR] Failed to register callback with xDrip service", e)
                handleError("Failed to register callback: ${e.message}")
                dataStatistics["register_error"] = e.message
                dataStatistics["last_error_time"] = System.currentTimeMillis()
            } catch (e: Exception) {
                aapsLogger.error(LTag.XDRIP, 
                    "[${TEST_TAG}_UNEXPECTED_ERROR] Unexpected error during service connection", e)
                handleError("Unexpected error: ${e.message}")
                dataStatistics["unexpected_error"] = e.message
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_SERVICE_DISCONNECTED] xDrip BG data service disconnected unexpectedly")
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_DISCONNECT_DETAILS] Disconnected from component: ${name?.className}, " +
                "Total callbacks received: $callbackReceivedCount")
            
            val disconnectTime = System.currentTimeMillis()

            isBound.set(false)
            service = null
            _connectionState.value = ConnectionState.Disconnected
            
            // 记录断开连接
            dataStatistics["service_disconnected_count"] = (dataStatistics["service_disconnected_count"] as? Int ?: 0) + 1
            dataStatistics["last_service_disconnect"] = disconnectTime

            // 尝试重新连接
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_RECONNECT_SCHEDULE] Scheduling reconnect in 5 seconds")
            scheduleReconnect(5000)
        }

        override fun onBindingDied(name: ComponentName?) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_BINDING_DIED] xDrip service binding died - serious error")
            isBound.set(false)
            _connectionState.value = ConnectionState.Error("Binding died - need to restart connection")
            
            dataStatistics["binding_died_count"] = (dataStatistics["binding_died_count"] as? Int ?: 0) + 1
            dataStatistics["last_binding_died"] = System.currentTimeMillis()
            
            // 更长时间后重试
            scheduleReconnect(10000)
        }

        override fun onNullBinding(name: ComponentName?) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_NULL_BINDING] xDrip service returned null binding")
            isBound.set(false)
            _connectionState.value = ConnectionState.Error("Null binding - service may not be running")
            
            dataStatistics["null_binding_count"] = (dataStatistics["null_binding_count"] as? Int ?: 0) + 1
            dataStatistics["last_null_binding"] = System.currentTimeMillis()
        }
    }

    // 监听器接口 - 根据 xDrip 实际接口调整
    interface XdripDataListener {
        fun onNewBgData(data: BgData)
        fun onConnectionStateChanged(connected: Boolean)
        fun onError(error: String)
    }

    private val listeners = mutableListOf<XdripDataListener>()

    init {
        // 注册生命周期观察者
        lifecycle?.addObserver(this)
        aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_INITIALIZED] XdripAidlService initialized")
        dataStatistics["service_start_time"] = System.currentTimeMillis()
    }

    fun addListener(listener: XdripDataListener) {
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_ADD_LISTENER] Adding new listener, total listeners: ${listeners.size + 1}")
        listeners.add(listener)
        dataStatistics["total_listeners_added"] = (dataStatistics["total_listeners_added"] as? Int ?: 0) + 1
    }

    fun removeListener(listener: XdripDataListener) {
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_REMOVE_LISTENER] Removing listener, remaining listeners: ${listeners.size - 1}")
        listeners.remove(listener)
        dataStatistics["total_listeners_removed"] = (dataStatistics["total_listeners_removed"] as? Int ?: 0) + 1
    }

    fun connect() {
        if (isBound.get() || isConnecting.get()) {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_CONNECT_SKIP] Already connected or connecting. " +
                "isBound: ${isBound.get()}, isConnecting: ${isConnecting.get()}")
            return
        }

        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CONNECT_START] Initiating connection to xDrip BG data service")
        isConnecting.set(true)
        _connectionState.value = ConnectionState.Connecting
        
        dataStatistics["connect_attempts"] = (dataStatistics["connect_attempts"] as? Int ?: 0) + 1
        dataStatistics["last_connect_attempt"] = System.currentTimeMillis()

        val intent = Intent().apply {
            action = SERVICE_ACTION
            `package` = XDRIP_PACKAGE
        }

        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_CONNECT_INTENT] Intent details - " +
            "Action: $SERVICE_ACTION, " +
            "Package: $XDRIP_PACKAGE, " +
            "Intent: $intent")

        try {
            val connectStart = System.currentTimeMillis()
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_BIND_SERVICE] Calling bindService()")
            val result = context.bindService(intent, serviceConnection, BIND_FLAGS)
            val bindTime = System.currentTimeMillis() - connectStart
            
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_BIND_RESULT] bindService() completed in ${bindTime}ms, result: $result")
            dataStatistics["last_bind_time"] = bindTime

            if (!result) {
                aapsLogger.error(LTag.XDRIP, 
                    "[${TEST_TAG}_BIND_FAILED] bindService() returned false - service may not exist or permission denied")
                isConnecting.set(false)
                _connectionState.value = ConnectionState.Error("bindService() returned false")
                handleError("Failed to bind to xDrip service - bindService() returned false")
                
                dataStatistics["bind_failures"] = (dataStatistics["bind_failures"] as? Int ?: 0) + 1
                dataStatistics["last_bind_failure"] = System.currentTimeMillis()
            } else {
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_BIND_SUCCESS] bindService() succeeded, waiting for onServiceConnected")
            }

        } catch (e: SecurityException) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_SECURITY_EXCEPTION] Security exception when binding to xDrip service", e)
            isConnecting.set(false)
            _connectionState.value = ConnectionState.Error("Permission denied: ${e.message}")
            handleError("Permission denied: ${e.message}")
            
            dataStatistics["security_exceptions"] = e.message
            dataStatistics["last_security_exception"] = System.currentTimeMillis()
        } catch (e: Exception) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_BIND_EXCEPTION] Exception when binding to xDrip service", e)
            isConnecting.set(false)
            _connectionState.value = ConnectionState.Error("Exception: ${e.message}")
            handleError("Exception: ${e.message}")
            
            dataStatistics["bind_exceptions"] = e.message
            dataStatistics["last_bind_exception"] = System.currentTimeMillis()
        }
    }

    fun disconnect() {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_DISCONNECT_START] Manually disconnecting from xDrip service")
        
        dataStatistics["manual_disconnects"] = (dataStatistics["manual_disconnects"] as? Int ?: 0) + 1
        dataStatistics["last_manual_disconnect"] = System.currentTimeMillis()

        try {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_UNREGISTER_CALLBACK] Attempting to unregister callback before disconnect")
            service?.unregisterCallback(callback)
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_UNREGISTER_SUCCESS] Callback unregistered successfully")
        } catch (e: RemoteException) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_UNREGISTER_ERROR] Failed to unregister callback during disconnect", e)
            dataStatistics["unregister_errors"] = (dataStatistics["unregister_errors"] as? Int ?: 0) + 1
        } catch (e: Exception) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_UNREGISTER_EXCEPTION] Exception during callback unregistration", e)
        }

        aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_UNBIND_SERVICE] Calling unbindService()")
        try {
            context.unbindService(serviceConnection)
            aapsLogger.debug(LTag.XDRIP, "[${TEST_TAG}_UNBIND_SUCCESS] unbindService() succeeded")
        } catch (e: Exception) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_UNBIND_ERROR] Exception during unbindService()", e)
            dataStatistics["unbind_errors"] = (dataStatistics["unbind_errors"] as? Int ?: 0) + 1
        }
        
        isBound.set(false)
        service = null
        _connectionState.value = ConnectionState.Disconnected
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_DISCONNECT_COMPLETE] Manual disconnect completed successfully")
    }

    suspend fun getLatestBgData(): BgData? = withContext(Dispatchers.IO) {
        if (!isBound.get()) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_GET_DATA_UNBOUND] Service not bound, cannot get data")
            dataStatistics["get_data_fail_unbound"] = (dataStatistics["get_data_fail_unbound"] as? Int ?: 0) + 1
            return@withContext null
        }

        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_GET_DATA_START] Requesting latest BG data from xDrip via getLatestBgData()")
        dataStatistics["manual_data_requests"] = (dataStatistics["manual_data_requests"] as? Int ?: 0) + 1

        return@withContext try {
            val startTime = System.currentTimeMillis()
            val data = service?.getLatestBgData()
            val duration = System.currentTimeMillis() - startTime
            
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_GET_DATA_TIMING] Data retrieval took ${duration}ms")
            
            if (data == null) {
                aapsLogger.warn(LTag.XDRIP, 
                    "[${TEST_TAG}_GET_DATA_NULL] getLatestBgData() returned null - no data available")
                dataStatistics["get_data_null"] = (dataStatistics["get_data_null"] as? Int ?: 0) + 1
            } else {
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_GET_DATA_SUCCESS] Manual fetch successful: " +
                    "BG=${data.glucose} mg/dL, " +
                    "Time=${formatTime(data.timestamp)}, " +
                    "Direction=${data.direction}")
                dataStatistics["manual_data_success"] = (dataStatistics["manual_data_success"] as? Int ?: 0) + 1
                dataStatistics["last_manual_fetch_time"] = duration
                
                // 记录数据流转
                logDataFlow("MANUAL_FETCH_SUCCESS", data, "Data manually fetched from service")
            }
            
            data
        } catch (e: RemoteException) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_GET_DATA_REMOTE_ERROR] RemoteException when getting latest BG data", e)
            handleRemoteException(e)
            dataStatistics["get_data_remote_errors"] = e.message
            null
        } catch (e: Exception) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_GET_DATA_EXCEPTION] Exception when getting latest BG data", e)
            dataStatistics["get_data_exceptions"] = e.message
            null
        }
    }

    fun getLatestBgDataSync(): BgData? {
        if (!isBound.get()) {
            aapsLogger.warn(LTag.XDRIP, 
                "[${TEST_TAG}_GET_SYNC_UNBOUND] Service not bound for sync call")
            return null
        }

        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_GET_SYNC_START] Synchronous data request via getLatestBgData()")
        dataStatistics["sync_data_requests"] = (dataStatistics["sync_data_requests"] as? Int ?: 0) + 1
        
        return try {
            val data = service?.getLatestBgData()
            if (data == null) {
                aapsLogger.warn(LTag.XDRIP, 
                    "[${TEST_TAG}_GET_SYNC_NULL] Synchronous call returned null data")
                dataStatistics["sync_data_null"] = (dataStatistics["sync_data_null"] as? Int ?: 0) + 1
            } else {
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_GET_SYNC_SUCCESS] Sync fetch: BG=${data.glucose} mg/dL")
                dataStatistics["sync_data_success"] = (dataStatistics["sync_data_success"] as? Int ?: 0) + 1
            }
            data
        } catch (e: RemoteException) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_GET_SYNC_REMOTE_ERROR] RemoteException in sync call", e)
            dataStatistics["sync_data_remote_errors"] = e.message
            null
        } catch (e: Exception) {
            aapsLogger.error(LTag.XDRIP, 
                "[${TEST_TAG}_GET_SYNC_EXCEPTION] Exception in sync call", e)
            dataStatistics["sync_data_exceptions"] = e.message
            null
        }
    }

    // 注意：xDrip 没有 getRecentBgData 方法，所以移除这个方法
    // 原来的 getRecentBgData() 方法已删除

    private fun fetchLatestBgData() {
        scope.launch {
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_AUTO_FETCH_START] Auto-fetching latest data after connection")
            val data = getLatestBgData()
            if (data != null) {
                _latestBgData.value = data
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_AUTO_FETCH_SUCCESS] Auto-fetch updated flow with new data")
                
                listeners.forEachIndexed { index, listener ->
                    aapsLogger.debug(LTag.XDRIP, 
                        "[${TEST_TAG}_AUTO_NOTIFY_${index}] Notifying listener with auto-fetched data")
                    try {
                        listener.onNewBgData(data)
                        aapsLogger.debug(LTag.XDRIP, 
                            "[${TEST_TAG}_AUTO_NOTIFY_${index}_SUCCESS] Listener processed auto-fetched data")
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.XDRIP, 
                            "[${TEST_TAG}_AUTO_NOTIFY_${index}_ERROR] Listener failed with auto-fetched data")
                    }
                }
            } else {
                aapsLogger.warn(LTag.XDRIP, 
                    "[${TEST_TAG}_AUTO_FETCH_FAIL] Auto-fetch returned null - no data available from xDrip")
                dataStatistics["auto_fetch_failures"] = (dataStatistics["auto_fetch_failures"] as? Int ?: 0) + 1
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long) {
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_RECONNECT_SCHEDULE] Scheduling reconnect in ${delayMs}ms")
        dataStatistics["reconnect_scheduled"] = (dataStatistics["reconnect_scheduled"] as? Int ?: 0) + 1
        
        scope.launch {
            delay(delayMs)
            if (!isBound.get() && !isConnecting.get()) {
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_RECONNECT_EXECUTE] Executing scheduled reconnect")
                dataStatistics["reconnect_executed"] = (dataStatistics["reconnect_executed"] as? Int ?: 0) + 1
                connect()
            } else {
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_RECONNECT_SKIP] Skipping reconnect: " +
                    "isBound=${isBound.get()}, " +
                    "isConnecting=${isConnecting.get()}")
                dataStatistics["reconnect_skipped"] = (dataStatistics["reconnect_skipped"] as? Int ?: 0) + 1
            }
        }
    }

    private fun handleRemoteException(e: RemoteException) {
        aapsLogger.error(LTag.XDRIP, 
            "[${TEST_TAG}_REMOTE_EXCEPTION_HANDLED] Handling RemoteException", e)
        isBound.set(false)
        service = null
        _connectionState.value = ConnectionState.Error("Remote exception: ${e.message}")
        
        dataStatistics["remote_exceptions_handled"] = (dataStatistics["remote_exceptions_handled"] as? Int ?: 0) + 1
        dataStatistics["last_remote_exception"] = e.message
        dataStatistics["last_remote_exception_time"] = System.currentTimeMillis()

        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_AUTO_RECONNECT] Scheduling auto-reconnect in 3 seconds due to RemoteException")
        scheduleReconnect(3000)
    }

    private fun handleError(error: String) {
        aapsLogger.error(LTag.XDRIP, 
            "[${TEST_TAG}_ERROR_HANDLING] Handling error: $error")
        dataStatistics["total_errors_handled"] = (dataStatistics["total_errors_handled"] as? Int ?: 0) + 1
        
        listeners.forEachIndexed { index, listener ->
            aapsLogger.debug(LTag.XDRIP, 
                "[${TEST_TAG}_ERROR_NOTIFY_${index}] Notifying listener of error")
            try {
                listener.onError(error)
                aapsLogger.debug(LTag.XDRIP, 
                    "[${TEST_TAG}_ERROR_NOTIFY_${index}_SUCCESS] Listener handled error")
            } catch (e: Exception) {
                aapsLogger.error(LTag.XDRIP, 
                    "[${TEST_TAG}_ERROR_NOTIFY_${index}_FAIL] Listener error notification failed", e)
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun getDataAge(timestamp: Long): Long {
        return (System.currentTimeMillis() - timestamp) / 1000
    }
    
    private fun logDataFlow(stage: String, data: BgData, message: String) {
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_DATA_FLOW_${stage}] $message | " +
            "BG: ${data.glucose} mg/dL | " +
            "Time: ${formatTime(data.timestamp)} | " +
            "Age: ${getDataAge(data.timestamp)}s | " +
            "Direction: ${data.direction} | " +
            "Noise: ${data.noise} | " +
            "Active Listeners: ${listeners.size} | " +
            "Total Callbacks: $callbackReceivedCount")
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onAppBackgrounded() {
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_APP_BACKGROUND] App backgrounded, maintaining xDrip connection. " +
            "Total callbacks received: $callbackReceivedCount, " +
            "Active listeners: ${listeners.size}")
        dataStatistics["app_background_count"] = (dataStatistics["app_background_count"] as? Int ?: 0) + 1
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        aapsLogger.debug(LTag.XDRIP, 
            "[${TEST_TAG}_LIFECYCLE_DESTROY] Lifecycle ON_DESTROY, cleaning up service")
        cleanup()
    }

    fun cleanup() {
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CLEANUP_START] Cleaning up xDrip AIDL service")
        
        // 输出详细的统计信息
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_SERVICE_STATISTICS] ========== xDrip Service Statistics ==========")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_CONNECTION] Connection Stats: " +
            "Connect Attempts: ${dataStatistics["connect_attempts"] ?: 0} | " +
            "Service Connected: ${dataStatistics["service_connected_count"] ?: 0} | " +
            "Service Disconnected: ${dataStatistics["service_disconnected_count"] ?: 0}")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_DATA] Data Stats: " +
            "Total Callbacks Received: $callbackReceivedCount | " +
            "Total Data Received: ${dataStatistics["total_received"] ?: 0} | " +
            "Manual Requests: ${dataStatistics["manual_data_requests"] ?: 0} | " +
            "Manual Success: ${dataStatistics["manual_data_success"] ?: 0}")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_ERRORS] Error Stats: " +
            "Null Data: ${dataStatistics["null_data_count"] ?: 0} | " +
            "Bind Failures: ${dataStatistics["bind_failures"] ?: 0} | " +
            "Remote Exceptions: ${dataStatistics["remote_exceptions_handled"] ?: 0}")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_STATS_STATE] Final State: " +
            "isBound: ${isBound.get()} | " +
            "isConnecting: ${isConnecting.get()} | " +
            "Connection State: ${_connectionState.value} | " +
            "Active Listeners: ${listeners.size}")
        aapsLogger.info(LTag.XDRIP,
            "[${TEST_TAG}_SERVICE_STATISTICS_END] =========================================")
        
        disconnect()
        listeners.clear()
        scope.cancel()
        lifecycle?.removeObserver(this)
        
        aapsLogger.info(LTag.XDRIP, 
            "[${TEST_TAG}_CLEANUP_COMPLETE] xDrip AIDL service cleanup completed")
    }
    
    // 添加手动检查连接状态的方法（因为 xDrip 没有 isConnected() 方法）
    fun checkConnectionStatus(): Boolean {
        val bound = isBound.get()
        val connecting = isConnecting.get()
        val state = _connectionState.value
        
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_CHECK_CONNECTION] Connection check: " +
            "isBound: $bound, " +
            "isConnecting: $connecting, " +
            "State: $state, " +
            "Service instance: ${service != null}")
        
        // 如果已绑定且服务实例存在，则认为连接正常
        val isConnected = bound && service != null && state is ConnectionState.Connected
        
        aapsLogger.debug(LTag.XDRIP,
            "[${TEST_TAG}_CONNECTION_RESULT] Connection status: $isConnected")
        
        return isConnected
    }
    
    fun getStatistics(): Map<String, Any> {
        val stats = HashMap<String, Any>()
        stats.putAll(dataStatistics)
        stats["callback_received_count"] = callbackReceivedCount
        stats["active_listeners"] = listeners.size
        stats["is_bound"] = isBound.get()
        stats["is_connecting"] = isConnecting.get()
        stats["current_state"] = _connectionState.value.toString()
        stats["last_callback_time"] = lastCallbackTime
        stats["service_instance_exists"] = service != null
        stats["service_uptime"] = System.currentTimeMillis() - (dataStatistics["service_start_time"] as? Long ?: 0)
        return stats
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
        
        override fun toString(): String {
            return when (this) {
                is Disconnected -> "Disconnected"
                is Connecting -> "Connecting"
                is Connected -> "Connected"
                is Error -> "Error: $message"
            }
        }
    }
}
