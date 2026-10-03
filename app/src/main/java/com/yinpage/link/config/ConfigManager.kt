package com.yinpage.link.config

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户可配置项。持久化到 SharedPreferences（无 root，纯应用私有存储）。
 */
data class AppConfig(
    /** 自动记住并重连最后一次使用的设备 */
    val autoReconnect: Boolean = true,
    /** 首选通道类型 */
    val transport: String = "AUTO",
    /** 首选协议 id，null 表示自动探测 */
    val preferredCodecId: String? = null,
    /** 是否在连接后立即查询全量状态 */
    val queryOnConnect: Boolean = true,
    /** 调试面板开关（显示收发字节流） */
    val debugPanel: Boolean = false,
    /** 日志级别：0 关 / 1 基本 / 2 详细 */
    val logLevel: Int = 1,
    /** 是否发送本地通知（电量低提醒等） */
    val notifications: Boolean = true,
    /** 低电量阈值（%） */
    val lowBatteryThreshold: Int = 20,
    /** 记住的最后连接设备 */
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,

    /**
     * 是否允许使用 **BLE GATT** 作为控制通道。
     *
     * ⚠️ **默认关闭**，原因是实测反馈的"系统蓝牙与应用不能同时使用"：
     * 本应用对耳机调用 `connectGatt` 会建立**第二条 GATT 连接**，
     * 而耳机通常已由系统（A2DP/HFP/LE）连着，同一个 GATT 客户端资源被两方争用，
     * 会导致系统侧连接异常。经典蓝牙 SPP 才是这只耳机的控制通道，
     * 因此默认只走 SPP，不再额外建立 GATT。
     */
    val bleTransport: Boolean = false,

    /**
     * 连接后是否探测耳机是否支持 **标准 BLE 电量服务（BAS 0x180F）**。
     *
     * ⚠️ **默认关闭**，同上：探测需要建 GATT 连接，会干扰系统已建立的连接。
     * 开启后可用于诊断"耳机是否暴露标准电量服务"，但可能影响系统蓝牙使用。
     */
    val bleBatteryProbe: Boolean = false,
) {
    companion object {
        const val LOG_OFF = 0
        const val LOG_BASIC = 1
        const val LOG_DEBUG = 2
    }
}

/**
 * 配置中心。单例，进程内共享；UI 与 core 都通过 StateFlow 观察。
 * 注意：协议层不直接依赖本类，避免循环依赖（协议实现只接收参数）。
 */
class ConfigManager private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val current: AppConfig get() = _config.value

    private fun read(): AppConfig = AppConfig(
        autoReconnect = prefs.getBoolean(K_AUTO_RECONNECT, true),
        transport = prefs.getString(K_TRANSPORT, "AUTO") ?: "AUTO",
        preferredCodecId = prefs.getString(K_CODEC, null),
        queryOnConnect = prefs.getBoolean(K_QUERY_ON_CONNECT, true),
        debugPanel = prefs.getBoolean(K_DEBUG_PANEL, false),
        logLevel = prefs.getInt(K_LOG_LEVEL, AppConfig.LOG_BASIC).coerceIn(0, 2),
        notifications = prefs.getBoolean(K_NOTIFICATIONS, true),
        lowBatteryThreshold = prefs.getInt(K_LOW_BATTERY, 20).coerceIn(5, 50),
        lastDeviceAddress = prefs.getString(K_LAST_ADDR, null),
        lastDeviceName = prefs.getString(K_LAST_NAME, null),
        bleTransport = prefs.getBoolean(K_BLE_TRANSPORT, false),
        bleBatteryProbe = prefs.getBoolean(K_BLE_PROBE, false),
    )

    private fun write(cfg: AppConfig) {
        prefs.edit {
            putBoolean(K_AUTO_RECONNECT, cfg.autoReconnect)
            putString(K_TRANSPORT, cfg.transport)
            putString(K_CODEC, cfg.preferredCodecId)
            putBoolean(K_QUERY_ON_CONNECT, cfg.queryOnConnect)
            putBoolean(K_DEBUG_PANEL, cfg.debugPanel)
            putInt(K_LOG_LEVEL, cfg.logLevel)
            putBoolean(K_NOTIFICATIONS, cfg.notifications)
            putInt(K_LOW_BATTERY, cfg.lowBatteryThreshold)
            putString(K_LAST_ADDR, cfg.lastDeviceAddress)
            putString(K_LAST_NAME, cfg.lastDeviceName)
            putBoolean(K_BLE_TRANSPORT, cfg.bleTransport)
            putBoolean(K_BLE_PROBE, cfg.bleBatteryProbe)
        }
        _config.value = cfg
    }

    fun update(block: (AppConfig) -> AppConfig) = write(block(current))

    fun rememberDevice(address: String?, name: String?) =
        update { it.copy(lastDeviceAddress = address, lastDeviceName = name) }

    companion object {
        const val PREFS_NAME = "yinpage_link_settings"
        private const val K_AUTO_RECONNECT = "auto_reconnect"
        private const val K_TRANSPORT = "transport"
        private const val K_CODEC = "preferred_codec"
        private const val K_QUERY_ON_CONNECT = "query_on_connect"
        private const val K_DEBUG_PANEL = "debug_panel"
        private const val K_LOG_LEVEL = "log_level"
        private const val K_NOTIFICATIONS = "notifications"
        private const val K_LOW_BATTERY = "low_battery"
        private const val K_LAST_ADDR = "last_addr"
        private const val K_LAST_NAME = "last_name"
        private const val K_BLE_TRANSPORT = "ble_transport"
        private const val K_BLE_PROBE = "ble_battery_probe"

        @Volatile
        private var instance: ConfigManager? = null

        fun init(context: Context): ConfigManager =
            instance ?: synchronized(this) {
                instance ?: ConfigManager(context).also { instance = it }
            }

        fun get(): ConfigManager = instance
            ?: error("ConfigManager 未初始化：请在 Application.onCreate 中调用 ConfigManager.init(context)")

        val initialized: Boolean get() = instance != null
    }
}
