@file:Suppress("DEPRECATION")

package com.yinpage.link.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.yinpage.link.config.ConfigManager
import com.yinpage.link.protocol.BatteryLevel
import com.yinpage.link.protocol.BatteryState
import com.yinpage.link.protocol.ConnectionState
import com.yinpage.link.protocol.DeviceInfo
import com.yinpage.link.protocol.EqMode
import com.yinpage.link.protocol.NoiseMode
import com.yinpage.link.protocol.PodCommand
import com.yinpage.link.protocol.PodState
import com.yinpage.link.protocol.PodUpdate
import com.yinpage.link.protocol.ProtocolRegistry
import com.yinpage.link.protocol.applyAllTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileWriter

/**
 * ============================================================================
 *  应用状态协调器  ——  这是 UI 层唯一需要依赖的对外 API
 * ============================================================================
 *  完全免 root：只使用公开的 Android 蓝牙 API（BluetoothAdapter / SPP / GATT）。
 *  没有 Xposed、没有系统服务注入、没有隐藏 API 调用。
 * ============================================================================
 */
object AppState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 应用上下文（反射获取，避免在 core 层持有 Activity 引用）。 */
    private val appContext: Context? by lazy {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentApplication").invoke(null) as? Context
            val system = activityThread.getMethod("currentActivityThread").invoke(null)
            val fromSystem = system?.let {
                activityThread.getMethod("getSystemContext").invoke(it) as? Context
            }
            (current ?: fromSystem)?.applicationContext
        }.getOrNull()
    }

    private val _pod = MutableStateFlow(PodState())
    private val _devices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    private val _bluetoothEnabled = MutableStateFlow(false)
    private val _permissionGranted = MutableStateFlow(false)
    private val _toast = MutableStateFlow<String?>(null)
    private val _debugLines = MutableStateFlow<List<String>>(emptyList())

    // 注意：_ui 必须在 ui 之前声明。Kotlin 按声明顺序初始化属性，
    // 若先写 `val ui = _ui` 会读到尚未初始化的 null。
    private val _ui = MutableStateFlow(PodUiState())

    /** 界面总状态：单一数据源，UI 只 collect 这一个。 */
    val ui: StateFlow<PodUiState> = _ui

    /** 调试日志（也可单独 collect，便于 DebugPage 只重组自身）。 */
    val debugLines: StateFlow<List<String>> = _debugLines.asStateFlow()

    /** 设备列表（DevicePage 单独 collect）。 */
    val devices: StateFlow<List<BluetoothDeviceItem>> = _devices.asStateFlow()

    /** 当前耳机状态（便捷入口）。 */
    val pod: StateFlow<PodState> = _pod.asStateFlow()

    @Volatile
    private var coordinator: PodCoordinator? = null
    private var connectJob: Job? = null
    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null

    /** 由 Application 层注入（协议实现与传输实现的装配）。 */
    fun install(coordinator: PodCoordinator) {
        this.coordinator = coordinator
    }

    private var initialized = false

    // ------------------------------------------------------------------ 初始化

    /** 幂等初始化。必须在任何 UI 读取状态之前调用一次。 */
    fun init() {
        if (initialized) return
        initialized = true
        _permissionGranted.value = hasBluetoothPermission()
        _bluetoothEnabled.value = adapter?.isEnabled == true
        EventLog.setVerbose(ConfigManager.initialized && ConfigManager.get().current.debugPanel)
        EventLog.info("启动", "YINPAGE-LINK 初始化完成（免 root 模式）")
        syncUi()
        scope.launch {
            EventLog.lines.collect { _debugLines.value = it }
        }
        // 配置以 ConfigManager 为唯一来源：core 侧任何写入
        // （含 rememberDevice 这类不经过 UI 的更新）都触发 UI 刷新
        if (ConfigManager.initialized) {
            scope.launch {
                ConfigManager.get().config.collect { syncUi() }
            }
        }
    }

    /** 由 Activity 在取得权限后调用。 */
    fun refreshPermissions() {
        _permissionGranted.value = hasBluetoothPermission()
        _bluetoothEnabled.value = isBluetoothEnabled()
        syncUi()
    }

    /**
     * 重新读取蓝牙开关状态。
     * 由蓝牙状态广播触发 —— 没有这一步，用户在系统里关掉蓝牙后
     * 界面会一直显示"已连接"。
     */
    fun refreshBluetoothState() {
        val enabled = isBluetoothEnabled()
        _bluetoothEnabled.value = enabled
        if (!enabled && _pod.value.connection != ConnectionState.BLUETOOTH_OFF) {
            // 蓝牙被关闭：链路上的会话已经失效，直接落到断连态
            connectJob?.cancel()
            stopHeartbeat()
            runCatching { coordinator?.disconnect() }
            updatePod {
                it.copy(connection = ConnectionState.BLUETOOTH_OFF, lastMessage = "蓝牙已关闭")
            }
            EventLog.info("蓝牙", "适配器已关闭，会话置为断开")
        }
        syncUi()
    }

    /**
     * 系统层面报告某设备断开（ACL_DISCONNECTED）。
     * 只处理当前连接的那台，避免误伤其它蓝牙设备的广播。
     */
    fun onDeviceDisconnected(address: String?) {
        val current = _pod.value.device?.address ?: return
        if (address == null || !address.equals(current, ignoreCase = true)) return
        if (_pod.value.connection == ConnectionState.IDLE) return
        connectJob?.cancel()
        connectJob = null
        stopHeartbeat()
        runCatching { coordinator?.disconnect() }
        updatePod {
            it.copy(
                connection = ConnectionState.IDLE,
                lastMessage = "耳机已断开",
                lastSeenAt = System.currentTimeMillis(),
            )
        }
        EventLog.info("连接", "系统报告设备断开：$address")
    }

    fun clearToast() {
        _toast.value = null
        syncUi()
    }

    private fun toast(message: String) {
        _toast.value = message
        syncUi()
    }

    private fun syncUi() {
        _ui.value = PodUiState(
            pod = _pod.value,
            config = if (ConfigManager.initialized) ConfigManager.get().current else com.yinpage.link.config.AppConfig(),
            debugLines = _debugLines.value,
            permissionGranted = _permissionGranted.value,
            bluetoothEnabled = _bluetoothEnabled.value,
            scanning = _scanning.value,
            devices = _devices.value,
            toast = _toast.value,
        )
        // 通知栏常驻卡片跟随状态刷新（无 root 下最接近"控制中心耳机控件"的体验）
        runCatching { com.yinpage.link.service.PodConnectionService.refresh(context()) }
    }

    private fun updatePod(transform: (PodState) -> PodState) {
        _pod.value = transform(_pod.value)
        syncUi()
    }

    private fun setDevices(list: List<BluetoothDeviceItem>) {
        // 已配对优先，其次疑似目标，最后按名称排序
        _devices.value = list
            .distinctBy { it.address }
            .sortedWith(compareByDescending<BluetoothDeviceItem> { it.bonded }
                .thenByDescending { it.suspectedTarget }
                .thenBy { it.displayName })
        syncUi()
    }

    // ------------------------------------------------------------------ 蓝牙基础

    private fun context(): Context? = appContext

    fun hasBluetoothPermission(): Boolean {
        val ctx = context() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    val adapter: BluetoothAdapter?
        get() = runCatching {
            val ctx = context() ?: return@runCatching null
            val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()

    fun isBluetoothEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    /** 申请开启蓝牙（需要 BLUETOOTH_CONNECT 权限，Activity 需用 startActivityForResult 处理）。 */
    fun enableBluetoothIntent(): Intent? = runCatching {
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    }.getOrNull()

    // ------------------------------------------------------------------ 设备发现

    /**
     * 开始扫描。冷流：UI 侧 collect 会驱动扫描，界面销毁自动停止。
     * 注意：Android 12+ 需要 BLUETOOTH_SCAN 运行时权限。
     */
    fun scanDevices(): kotlinx.coroutines.flow.Flow<List<BluetoothDeviceItem>> = channelFlow {
        val ctx = context()
        if (ctx == null) {
            send(emptyList())
            close()
            return@channelFlow
        }
        if (!hasBluetoothPermission()) {
            toast("缺少蓝牙权限，请先授权")
            send(emptyList())
            close()
            return@channelFlow
        }

        val found = LinkedHashMap<String, BluetoothDeviceItem>()

        // 先放入已配对设备
        runCatching {
            adapter?.bondedDevices?.forEach { d ->
                runCatching {
                    found[d.address] = d.toItem(bonded = true, rssi = null)
                }
            }
        }
        send(found.values.toList())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        }
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                            .takeIf { it != Short.MIN_VALUE }?.toInt()
                        device?.let {
                            runCatching {
                                found[it.address] = it.toItem(bonded = it.bondState == BluetoothDevice.BOND_BONDED, rssi = rssi)
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                        }
                        device?.let {
                            runCatching { found[it.address] = it.toItem(bonded = true, rssi = found[it.address]?.rssi) }
                        }
                    }
                }
                trySend(found.values.toList())
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        discoveryReceiver = receiver

        _scanning.value = true
        syncUi()
        EventLog.info("扫描", "开始扫描蓝牙设备")
        runCatching { adapter?.startDiscovery() }

        try {
            while (true) {
                delay(1000)
            }
        } finally {
            runCatching { adapter?.cancelDiscovery() }
            runCatching { ctx.unregisterReceiver(receiver) }
            discoveryReceiver = null
            _scanning.value = false
            setDevices(found.values.toList())
            EventLog.info("扫描", "扫描结束，共 ${found.size} 台设备")
            syncUi()
        }
    }.flowOn(Dispatchers.IO)

    private fun BluetoothDevice.toItem(bonded: Boolean, rssi: Int?): BluetoothDeviceItem {
        // 读 name / address 需要 BLUETOOTH_CONNECT（Android 12+），
        // 权限被撤销时会抛 SecurityException —— 必须兜住，否则扫描直接崩。
        val deviceName = runCatching { name }.getOrNull().orEmpty()
        val deviceAddress = runCatching { address }.getOrNull().orEmpty()
        return BluetoothDeviceItem(
            name = deviceName,
            address = deviceAddress,
            bonded = bonded,
            rssi = rssi,
            suspectedTarget = isSuspectedYinpage(deviceName),
        )
    }

    /** 名称启发式：音贝奇耳机通常以 YINPAGE / 音贝奇 / 型号名广播。 */
    fun isSuspectedYinpage(name: String): Boolean {
        val n = name.uppercase()
        return SUSPECT_KEYWORDS.any { n.contains(it) }
    }

    private val SUSPECT_KEYWORDS = listOf(
        "YINPAGE", "音贝奇", "FEEL", "REAL", "HEAR", "FREE", "YINPAGE-LINK",
        "YSCOCO", "ELEPHANTNOSE", "MAOXIN", "LANGSDOM",
    )

    /** 兼容旧 API：一次性触发扫描（不返回流）。 */
    fun startScan() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            scanDevices().collect { setDevices(it) }
        }.also { job ->
            job.invokeOnCompletion { discoveryJob = null }
        }
    }

    fun stopScan() {
        discoveryJob?.cancel()
        discoveryJob = null
        runCatching { adapter?.cancelDiscovery() }
        _scanning.value = false
        syncUi()
    }

    // ------------------------------------------------------------------ 连接

    /**
     * 连接到指定设备。
     * @param item 设备列表项
     */
    fun connect(item: BluetoothDeviceItem) {
        val ctx = context()
        if (ctx == null) {
            toast("应用上下文不可用")
            return
        }
        if (!hasBluetoothPermission()) {
            toast("缺少蓝牙权限，请先授权")
            return
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            toast("蓝牙未开启")
            updatePod { it.copy(connection = ConnectionState.BLUETOOTH_OFF) }
            return
        }
        val device = runCatching { btAdapter.getRemoteDevice(item.address) }.getOrNull()
        if (device == null) {
            toast("无效的蓝牙地址")
            return
        }

        connectJob?.cancel()
        stopScan()

        val config = if (ConfigManager.initialized) ConfigManager.get().current else com.yinpage.link.config.AppConfig()
        val kind = TransportKind.fromName(config.transport)
        val codec = ProtocolRegistry.candidatesFor(item.name, emptyList())
            .firstOrNull { config.preferredCodecId == null || it.id == config.preferredCodecId }
            ?: ProtocolRegistry.all().firstOrNull()
        val coordinator = this.coordinator

        if (codec == null) {
            toast("未注册任何协议实现")
            return
        }
        if (coordinator == null) {
            toast("连接协调器未安装")
            return
        }

        updatePod {
            it.copy(
                connection = ConnectionState.CONNECTING,
                device = DeviceInfo(name = item.displayName, address = item.address),
                protocolName = codec.id,
                lastMessage = "正在连接 ${item.displayName} …",
            )
        }
        EventLog.info("连接", "目标=${item.address} 通道=${kind.label} 协议=${codec.id}")

        // 保活前台服务在**进入 CONNECTING 时就启动**（此时用户还在前台 Activity 上），
        // 而不是等连接成功回调（可能 15~20 秒后，用户多半已按 Home）。
        // Android 12+ 对后台 startForegroundService 抛 ForegroundServiceStartNotAllowedException，
        // 旧实现把启动放在成功回调里，失败被 runCatching 静默吞掉 → 保活静默失效。
        com.yinpage.link.service.PodConnectionService.start(context())

        connectJob = scope.launch {
            val result = runCatching {
                coordinator.connect(
                    address = item.address,
                    deviceName = item.name,
                    kind = kind,
                    codec = codec,
                    onUpdate = { update -> applyUpdate(update) },
                    onLog = { message -> EventLog.debug("会话", message) },
                )
            }.getOrElse { error ->
                ConnectResult.Failure(error.message ?: error.javaClass.simpleName)
            }

            when (result) {
                is ConnectResult.Success -> {
                    updatePod {
                        it.copy(
                            connection = ConnectionState.CONNECTED,
                            lastSeenAt = System.currentTimeMillis(),
                            lastMessage = "已连接",
                        )
                    }
                    EventLog.info("连接", "成功：${item.displayName}")
                    if (ConfigManager.initialized) {
                        ConfigManager.get().rememberDevice(item.address, item.name)
                    }
                    if (config.queryOnConnect) {
                        send(PodCommand.QueryAll)
                    }
                    startHeartbeat()
                    probeBleBattery(device)
                }
                is ConnectResult.Failure -> {
                    // 连接失败：保活服务已在前台启动，这里收掉，避免空转
                    runCatching { com.yinpage.link.service.PodConnectionService.stop(context()) }
                    updatePod {
                        it.copy(
                            connection = ConnectionState.FAILED,
                            lastMessage = result.reason,
                        )
                    }
                    EventLog.info("连接", "失败：${result.reason}")
                    toast("连接失败：${result.reason}")
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        stopHeartbeat()
        runCatching { coordinator?.disconnect() }
        // 停止保活前台服务并移除前台通知（否则通知划不掉、服务 START_STICKY 常驻复活）
        runCatching { com.yinpage.link.service.PodConnectionService.stop(context()) }
        updatePod {
            it.copy(
                connection = ConnectionState.IDLE,
                lastMessage = "已断开",
                lastSeenAt = System.currentTimeMillis(),
            )
        }
        EventLog.info("连接", "已主动断开")
    }

    /** 重连到上次设备。 */
    fun reconnectLast() {
        val config = if (ConfigManager.initialized) ConfigManager.get().current else null
        val address = config?.lastDeviceAddress ?: return
        val name = config.lastDeviceName.orEmpty()
        connect(BluetoothDeviceItem(name = name, address = address, bonded = true))
    }

    // ------------------------------------------------------------------ 状态更新

    private fun applyUpdate(update: PodUpdate) {
        updatePod { current -> listOf(update).applyAllTo(current).copy(lastSeenAt = System.currentTimeMillis()) }
        when (update) {
            is PodUpdate.Battery -> {
                val merged = mergeSystemBattery(update.battery)
                if (merged !== update.battery) updatePod { it.copy(battery = merged) }
                EventLog.info("状态", "电量 左=${merged.left.percent}% 右=${merged.right.percent}% 盒=${merged.case.percent}%")
            }
            is PodUpdate.Noise -> EventLog.info("状态", "降噪=${update.mode.labelZh}")
            is PodUpdate.Eq -> EventLog.info("状态", "EQ=${update.mode.label}")
            is PodUpdate.GameMode -> EventLog.info("状态", "游戏模式=${update.enabled}")
            is PodUpdate.InEarDetection -> EventLog.info("状态", "佩戴检测=${update.enabled}")
            is PodUpdate.Firmware -> EventLog.info("状态", "固件=${update.version}")
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ BLE 电量探测

    /**
     * 探测耳机是否在 BLE 侧暴露**标准电量服务（BAS, 0x180F）**。
     *
     * 为什么做这件事：免 root 前提下，若耳机暴露了标准 BAS，
     * AOSP 蓝牙栈会自己把电量喂给系统，**系统蓝牙设置页就有机会显示电量**——
     * 这是唯一不碰系统进程、不需要 root 就能让系统"知道"电量的路径。
     *
     * 探测放在 SPP 查询之后（延迟几秒），若 SPP 已经读到电量就不再打扰耳机。
     */
    private fun probeBleBattery(device: BluetoothDevice) {
        // 默认关闭：探测要建第二条 GATT 连接，会与系统已建立的连接争用资源，
        // 这是"系统蓝牙与应用不能同时使用"的已知诱因之一。用户可在设置里显式开启。
        val cfg = if (ConfigManager.initialized) ConfigManager.get().current else null
        if (cfg?.bleBatteryProbe != true) {
            EventLog.debug("BLE", "BLE 电量探测已关闭（设置 → 高级 可开启）")
            return
        }
        scope.launch {
            delay(BLE_PROBE_DELAY_MS)
            // SPP 协议已经读到左右耳电量 → 说明私有通道可用，不必再走 BLE
            if (_pod.value.battery.left.known || _pod.value.battery.right.known) {
                EventLog.debug("BLE", "SPP 已提供电量，跳过 BAS 探测")
                return@launch
            }
            val ctx = context() ?: return@launch
            EventLog.info("BLE", "SPP 未提供电量，开始探测标准电量服务（0x180F）…")
            val result = runCatching {
                com.yinpage.link.enhance.BleBatteryProbe.probe(ctx, device)
            }.getOrNull()
            when {
                result == null -> EventLog.info("BLE", "BAS 探测失败（耳机可能不支持 BLE 连接）")
                !result.hasBatteryService -> {
                    EventLog.info("BLE", "耳机未暴露标准电量服务 0x180F（共发现 ${result.serviceUuids.size} 个服务）")
                    result.serviceUuids.take(12).forEach { EventLog.debug("BLE", "  服务 $it") }
                    EventLog.info("BLE", "结论：系统无法自行获知电量，只能由本应用通过 SPP 私有协议读取")
                }
                result.level == null -> EventLog.info("BLE", "发现 0x180F 但读取电量失败")
                else -> {
                    EventLog.info("BLE", "发现标准电量服务，电量=${result.level}%")
                    updatePod { current ->
                        current.copy(
                            battery = current.battery.copy(
                                left = com.yinpage.link.protocol.BatteryLevel.of(result.level),
                                right = com.yinpage.link.protocol.BatteryLevel.of(result.level),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 用系统侧电量补齐缺失项（免 root 兜底）。
     *
     * Android 14+ 提供了公开的 `BluetoothDevice.getBatteryLevel()`，
     * 系统会从标准蓝牙电量服务（GATT 0x180F）解析电量。若我们的 SPP 协议
     * 在某个固件上没有读到某一项，这里用系统值补上——两个来源互为备份。
     *
     * 注意：系统只给一个"整机"电量，因此只用于补齐**左右耳全未知**的场景，
     * 不覆盖已读到的单项。
     */
    private fun mergeSystemBattery(state: BatteryState): BatteryState {
        if (state.left.known || state.right.known) return state
        val address = _pod.value.device?.address ?: return state
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return state
        val level = com.yinpage.link.enhance.SystemBluetoothInfo.batteryLevel(device) ?: return state
        EventLog.debug("电量", "SPP 未上报，使用系统电量兜底：$level%")
        return state.copy(
            left = com.yinpage.link.protocol.BatteryLevel.of(level),
            right = com.yinpage.link.protocol.BatteryLevel.of(level),
        )
    }

    fun refresh() = send(PodCommand.QueryAll)

    fun setNoise(mode: NoiseMode) {
        // 乐观更新：立即反馈，随后由耳机回包纠正
        updatePod { it.copy(noise = mode) }
        send(PodCommand.SetNoise(mode))
    }

    fun setEq(mode: EqMode) {
        updatePod { it.copy(eq = mode) }
        send(PodCommand.SetEq(mode))
    }

    fun setGameMode(enabled: Boolean) {
        updatePod { it.copy(gameMode = enabled) }
        send(PodCommand.SetGameMode(enabled))
    }

    fun setInEar(enabled: Boolean) {
        updatePod { it.copy(inEarDetection = enabled) }
        send(PodCommand.SetInEarDetection(enabled))
    }

    fun setDualConnection(enabled: Boolean) {
        updatePod { it.copy(dualConnection = enabled) }
        send(PodCommand.SetDualConnection(enabled))
    }

    fun setWindSuppression(enabled: Boolean) {
        updatePod { it.copy(windSuppression = enabled) }
        send(PodCommand.SetWindSuppression(enabled))
    }

    private fun send(command: PodCommand) {
        val coordinator = this.coordinator ?: return
        if (!coordinator.isReady) {
            EventLog.debug("指令", "未就绪，忽略 $command")
            return
        }
        scope.launch {
            val ok = runCatching { coordinator.send(command) }.getOrDefault(false)
            EventLog.debug("指令", "$command -> ${if (ok) "已发送" else "发送失败"}")
        }
    }

    // ------------------------------------------------------------------ 心跳

    /**
     * 定期查询电量/状态。白牌 TWS 的电量不会主动推送，必须轮询。
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (coordinator?.isReady != true) break
                runCatching { coordinator?.send(PodCommand.QueryBattery) }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ------------------------------------------------------------------ 配置

    fun updateConfig(block: (com.yinpage.link.config.AppConfig) -> com.yinpage.link.config.AppConfig) {
        if (!ConfigManager.initialized) return
        ConfigManager.get().update(block)
        // 打开"调试面板"时立即开启详细日志，否则用户会看到空白的调试面板
        EventLog.setVerbose(ConfigManager.get().current.debugPanel)
        syncUi()
    }

    /** 导出日志到应用私有目录，返回路径；UI 可显示或分享。 */
    fun exportLog(): String? {
        val ctx = context() ?: return null
        return runCatching {
            val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "yinpage-link-log.txt")
            FileWriter(file, false).use { it.write(EventLog.dump()) }
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 生成一键诊断报告（可复制）。
     *
     * ⚠️ **必须在 IO 线程调用**（内部含阻塞式蓝牙连接测试）。
     * UI 侧请用 [buildDiagnosticsAsync]，不要直接在主线程调。
     */
    fun buildDiagnostics(): String {
        val ctx = context()
        val address = _pod.value.device?.address
        return runCatching {
            Diagnostics.buildReport(ctx, address)
        }.getOrElse { "诊断失败：${it.javaClass.simpleName} ${it.message}" }
    }

    /**
     * 异步版诊断：在 IO 线程执行后回调结果。
     *
     * 为什么必须异步：诊断里的 SPP 连接测试与 BLE 服务发现都是**阻塞**操作
     * （单次最长 8~15 秒，累计可达 1 分钟）。早期版本在主线程直接调用，
     * 导致界面卡死 → ANR → 进程被系统杀掉，表现为"一点诊断就崩溃"。
     */
    fun buildDiagnosticsAsync(onResult: (String) -> Unit) {
        scope.launch {
            val report = withContext(Dispatchers.IO) { buildDiagnostics() }
            withContext(Dispatchers.Main) { onResult(report) }
        }
    }

    fun clearLog() {
        EventLog.clear()
        _debugLines.value = emptyList()
        syncUi()
    }

    // ------------------------------------------------------------------ 收尾

    /** 进程退出或 Activity 销毁时调用。 */
    fun shutdown() {
        stopScan()
        stopHeartbeat()
        runCatching { coordinator?.disconnect() }
        runCatching { com.yinpage.link.service.PodConnectionService.stop(context()) }
    }

    private const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** SPP 查询之后再等这么久才做 BLE 探测，避免和私有协议握手抢时间。 */
    private const val BLE_PROBE_DELAY_MS = 6_000L

    /** SPP 服务 UUID 候选：标准串口 + 常见厂商自定义（供传输层遍历）。 */
    val SPP_UUID_CANDIDATES: List<String> = listOf(
        "00001101-0000-1000-8000-00805F9B34FB", // 标准 Serial Port
        "00001102-0000-1000-8000-00805F9B34FB", // 部分方案复用 LAN Access
        "0000FFF0-0000-1000-8000-00805F9B34FB",
        "0000FFE0-0000-1000-8000-00805F9B34FB",
        "00000000-0000-1000-8000-00805F9B34FB",
    )
}
