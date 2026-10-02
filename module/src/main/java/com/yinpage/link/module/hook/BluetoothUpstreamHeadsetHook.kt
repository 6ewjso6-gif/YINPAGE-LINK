package com.yinpage.link.module.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.yinpage.link.module.ModuleConfigStore
import com.yinpage.link.module.ipc.ModuleIpc
import com.yinpage.link.module.pods.RfcommController
import com.yinpage.link.protocol.BatteryState
import com.yinpage.link.protocol.NoiseMode
import com.yinpage.link.protocol.PodUpdate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 *  作用域：com.android.bluetooth  ——  接入链路的核心（P0）
 * ============================================================================
 *  这里是"让系统认为音贝奇耳机是一只支持高级功能的小米 TWS"的地方，
 *  也是唯一真正与耳机通信的地方（SPP 通道在本进程）。
 *
 *  涉及的类（HyperOS 4 实测名与旧版并存）：
 *    - `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService`  服务本体
 *    - `com.android.bluetooth.ble.app.headset.BinderC6776v` / `...headset.v`  Binder 实现
 *    - `com.android.bluetooth.a2dp.A2dpService`  A2DP 连接事件
 *
 *  设计要点：
 *   1. **所有 Hook 都独立 try-catch**，符号缺失只记日志，绝不让系统进程崩溃；
 *   2. **方法名多候选**：OS4 混淆后 `isMiTWS` 可能叫 `mo19771O0` / `O0`；
 *   3. **不做设备数据库写入**：系统的支持性判定完全走运行时 API（已确证），
 *      因此这里只改返回值，不碰 bt_config.conf / config.xml；
 *   4. SPP 连接放在单线程执行器里，绝不阻塞 Binder 线程。
 * ============================================================================
 */
class BluetoothUpstreamHeadsetHook : HookContext() {

    /** 单线程执行器：串行化 SPP 连接/断开，避免并发建链。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yinpage-pods-io").apply { isDaemon = true }
    }

    private val controller = AtomicReference<RfcommController?>(null)

    /** 当前正在控制的耳机地址。 */
    private val currentAddress = AtomicReference<String?>(null)

    @Volatile
    private var lastConnectedAddress: String? = null

    override fun onHook() {
        ModuleLog.i(TAG, "开始在 $packageName 安装蓝牙 Hook")

        hookHeadsetService()
        hookBinderImplementation()
        hookA2dpService()
        registerAncReceiver()

        ModuleLog.i(TAG, "蓝牙 Hook 安装完成")
    }

    // ------------------------------------------------------------------ 服务与 Binder 定位

    /**
     * Hook `BluetoothHeadsetService.onBind`：拿到 Binder 实现类的真实类型。
     * 之所以不在编译期写死类名，是因为 OS 每个小版本都可能改混淆名。
     */
    private fun hookHeadsetService() {
        val serviceClass = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
        findMethodOrNull(serviceClass, "onBind", android.content.Intent::class.java)?.let { m ->
            hookAfter(m) {
                val binder = result ?: return@hookAfter
                installBinderHooks(binder.javaClass)
            }
        } ?: ModuleLog.d(TAG, "未找到 BluetoothHeadsetService.onBind（可能版本不同）")

        // 有些版本在 onCreate 里就创建了 Binder，这里做一次兜底探测
        findMethodOrNull(serviceClass, "onCreate")?.let { m ->
            hookAfter(m) { installBinderHooks(instance?.javaClass) }
        }
    }

    /** 对已知的 Binder 实现类名逐个装 hook。 */
    private fun hookBinderImplementation() {
        BINDER_CLASS_CANDIDATES.forEach { className ->
            val clazz = findClassOrNull(className) ?: return@forEach
            ModuleLog.i(TAG, "找到 Binder 实现类：$className")
            installBinderHooks(clazz)
        }
    }

    /**
     * 安装 Binder 上的全部 Hook 点。
     * 每个点都独立 try-catch（[hookBefore]/[hookAfter] 内部已包），
     * 因此某一项失败不影响其它项。
     */
    private fun installBinderHooks(binderClass: Class<*>?) {
        if (binderClass == null) return
        val className = binderClass.name

        // ---- 1) 核心：让系统认为耳机"受支持" ----
        //    这是整条链路的总开关。
        for (methodName in listOf("checkSupport")) {
            findMethodOrNull(className, methodName, BluetoothDevice::class.java)?.let { m ->
                hookBefore(m) {
                    val device = arg<BluetoothDevice>(0) ?: return@hookBefore
                    if (!shouldHook(device)) return@hookBefore
                    result = ModuleConfigStore.current().supportString()
                    ModuleLog.i(TAG, "checkSupport(${device.address}) -> 受支持")
                }
            }
        }

        // 部分版本用 String（MAC）参数版本
        for (methodName in listOf("getDeviceInfo")) {
            findMethodOrNull(className, methodName, String::class.java)?.let { m ->
                hookBefore(m) {
                    val mac = arg<String>(0)
                    if (mac == null || !shouldHookAddress(mac)) return@hookBefore
                    result = ModuleConfigStore.current().supportString()
                }
            }
            // 无参版本（Settings 侧代理）
            findMethodOrNull(className, methodName)?.let { m ->
                hookBefore(m) {
                    val device = getObjectFieldAny(instance, "mDevice", "device") as? BluetoothDevice
                    if (device == null || !shouldHook(device)) return@hookBefore
                    result = ModuleConfigStore.current().supportString()
                }
            }
        }

        // ---- 2) 让它被当成 TWS 而非普通耳机 ----
        hookStringFlag(className, listOf("isSupportAudioSwitch", "mo19775z1", "z1"), "1")
        hookBooleanFlagByName(className, listOf("isMiTWS", "mo19771O0", "O0"), true)
        hookBooleanFlagByName(className, listOf("checkIsMiTWS", "mo19766B", "B"), true)
        hookBooleanFlagByName(className, listOf("getRingFindState", "mo19772m0", "m0"), false)

        // ---- 3) setCommonCommand 的能力探测 ----
        findMethodOrNull(
            className, "setCommonCommand",
            Int::class.javaPrimitiveType!!, String::class.java, BluetoothDevice::class.java,
        )?.let { m ->
            hookBefore(m) {
                val command = arg<Int>(0) ?: return@hookBefore
                result = when (command) {
                    123 -> "4"   // 疑似"支持 4 档 ANC"（三家参考实现都写死 4）
                    else -> "1"
                }
            }
        }

        // ---- 4) 拦截连接/配置类调用：吞掉 + 触发一次状态刷新 ----
        listOf(
            "connect" to arrayOf(BluetoothDevice::class.java),
            "getDeviceConfig" to arrayOf(BluetoothDevice::class.java),
            "getCommonConfig" to arrayOf(BluetoothDevice::class.java, String::class.java),
        ).forEach { (name, params) ->
            findMethodOrNull(className, name, *params)?.let { m ->
                hookBefore(m) {
                    val device = arg<BluetoothDevice>(0)
                    if (device == null || !shouldHook(device)) return@hookBefore
                    result = null
                    requestRefresh(device)
                }
            }
        }

        // ---- 5) ANC 控制：系统 UI 点四档 → 转成耳机 SPP 命令 ----
        if (ModuleConfigStore.current().hookAnc) {
            findMethodOrNull(
                className, "changeAncMode",
                Int::class.javaPrimitiveType!!, BluetoothDevice::class.java,
            )?.let { m ->
                hookBefore(m) {
                    val mode = arg<Int>(0) ?: return@hookBefore
                    val device = arg<BluetoothDevice>(1) ?: return@hookBefore
                    if (!shouldHook(device)) return@hookBefore
                    result = null
                    applyAncFromMode(mode, device)
                }
            }
            findMethodOrNull(
                className, "changeAncLevel",
                String::class.java, BluetoothDevice::class.java,
            )?.let { m ->
                hookBefore(m) {
                    val level = arg<String>(0) ?: return@hookBefore
                    val device = arg<BluetoothDevice>(1) ?: return@hookBefore
                    if (!shouldHook(device)) return@hookBefore
                    result = null
                    applyAncFromLevel(level, device)
                }
            }
        }

        // ---- 6) 回调注册：缓存起来以便主动推送状态 ----
        //     回调类名在不同版本不同，这里按"参数是接口且首参为回调"的形态找。
        cacheCallbackMethods(className)

        ModuleLog.i(TAG, "Binder Hook 安装完成：$className")
    }

    /** 缓存 register/registerCallbackDevice 的回调对象，用于后续 refreshStatus 推送。 */
    private val callbacks = java.util.Collections.synchronizedMap(
        LinkedHashMap<android.os.IBinder, Any>()
    )

    private fun cacheCallbackMethods(className: String) {
        val classesToWrap = listOf("register", "registerCallbackDevice", "unregister")
        for (name in classesToWrap) {
            findClassOrNull(className)?.declaredMethods
                ?.filter { it.name == name && it.parameterTypes.isNotEmpty() }
                ?.forEach { m ->
                    runCatching { m.isAccessible = true }
                    hookBefore(m) {
                        val callback = args.firstOrNull { it != null && it.javaClass.name.contains("Callback") }
                            ?: args.getOrNull(0)
                        val binder = runCatching {
                            callMethodOrNull(callback, "asBinder") as? android.os.IBinder
                        }.getOrNull()
                        if (binder != null) {
                            if (name == "unregister") callbacks.remove(binder)
                            else callbacks[binder] = callback
                        }
                        // 不真正注册：伪设备的真实回调永远不会来，注册了反而让系统拿到空响应
                        result = null
                        val device = args.filterIsInstance<BluetoothDevice>().firstOrNull()
                        if (device != null) pushStatusToCallbacks(device.address)
                    }
                }
        }
    }

    // ------------------------------------------------------------------ A2DP 连接事件

    /**
     * A2DP 连接状态变化 → 拉起 / 断开 SPP。
     * 用 `handleConnectionStateChanged(BluetoothDevice, int, int)` 这个已确证存在的签名。
     */
    private fun hookA2dpService() {
        val className = "com.android.bluetooth.a2dp.A2dpService"
        val m = findMethodByParamCount(className, "handleConnectionStateChanged", 3)
            ?: findMethodOrNull(
                className, "handleConnectionStateChanged",
                BluetoothDevice::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            )
            ?: run {
                ModuleLog.d(TAG, "未找到 A2dpService.handleConnectionStateChanged")
                return
            }
        hookAfter(m) {
            val device = arg<BluetoothDevice>(0) ?: return@hookAfter
            if (!shouldHook(device)) return@hookAfter
            val state = arg<Int>(1) ?: return@hookAfter
            when (state) {
                A2DP_STATE_CONNECTED -> connectPods(device)
                A2DP_STATE_DISCONNECTED -> disconnectPods("A2DP 已断开")
            }
        }
    }

    // ------------------------------------------------------------------ SPP 生命周期

    private fun connectPods(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        if (controller.get()?.isConnected == true && currentAddress.get() == address) return

        ioExecutor.execute {
            runCatching {
                // 先关掉旧的
                controller.getAndSet(null)?.close()
                currentAddress.set(address)

                val ctrl = RfcommController(
                    device = device,
                    onUpdate = { update -> handlePodUpdate(address, update) },
                    onDisconnected = { reason -> disconnectPods(reason) },
                )
                if (ctrl.connect()) {
                    controller.set(ctrl)
                    lastConnectedAddress = address
                    ModuleLog.i(TAG, "耳机通道就绪：$address")
                } else {
                    ModuleLog.w(TAG, "耳机通道建立失败：$address")
                }
            }.onFailure { ModuleLog.w(TAG, "connectPods 异常：${it.message}") }
        }
    }

    private fun disconnectPods(reason: String) {
        ioExecutor.execute {
            runCatching {
                controller.getAndSet(null)?.close()
                currentAddress.set(null)
                ModuleLog.i(TAG, "耳机通道已断开：$reason")
                // 通知各消费进程：断连
                ModuleIpc.broadcastState(
                    appContext,
                    ModuleIpc.PodSnapshot(
                        address = lastConnectedAddress.orEmpty(),
                        connected = false,
                    ),
                )
            }
        }
    }

    /** 耳机状态更新 → 转成跨进程快照 + 推给系统回调。 */
    private fun handlePodUpdate(address: String, update: PodUpdate) {
        runCatching {
            val previous = ModuleIpc.snapshot()
            val next = when (update) {
                is PodUpdate.Battery -> previous.copy(
                    connected = true,
                    address = address,
                    left = update.battery.left.percent ?: ModuleIpc.UNKNOWN_LEVEL,
                    right = update.battery.right.percent ?: ModuleIpc.UNKNOWN_LEVEL,
                    case = update.battery.case.percent ?: ModuleIpc.UNKNOWN_LEVEL,
                    leftCharging = update.battery.left.charging,
                    rightCharging = update.battery.right.charging,
                    caseCharging = update.battery.case.charging,
                )

                is PodUpdate.Noise -> previous.copy(anc = noiseToAncCode(update.mode))

                else -> previous
            }
            ModuleIpc.broadcastState(appContext, next)
            pushStatusToCallbacks(address)
        }.onFailure { ModuleLog.w(TAG, "处理耳机状态失败：${it.message}") }
    }

    /**
     * 主动把状态推给系统注册的回调（系统 UI 真正读的是这条）。
     * 回调方法名 `refreshStatus(String, String)` 在两个参考项目里一致。
     */
    private fun pushStatusToCallbacks(address: String) {
        if (callbacks.isEmpty()) return
        val payload = buildMiuiRefreshPayload()
        synchronized(callbacks) {
            callbacks.values.toList().forEach { callback ->
                runCatching {
                    callMethodOrNull(callback, "refreshStatus", address, payload)
                }.onFailure { ModuleLog.d(TAG, "refreshStatus 推送失败：${it.message}") }
            }
        }
    }

    /**
     * 16 字段状态串（格式已确证，两参考实现逐字一致）：
     *   [0]=左耳电量 [1]=右耳 [2]=盒 [7]=ANC 档位码 [8]="true" [11]/[13]/[14]="00"
     *   未连接 = "255"，充电 = 值 or 128
     */
    private fun buildMiuiRefreshPayload(): String {
        val snap = ModuleIpc.snapshot()
        val values = MutableList(16) { "" }
        values[0] = snap.settingsBatteryValue(snap.left, snap.leftCharging)
        values[1] = snap.settingsBatteryValue(snap.right, snap.rightCharging)
        values[2] = snap.settingsBatteryValue(snap.case, snap.caseCharging)
        values[7] = miuiAncLevel(snap.anc)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    // ------------------------------------------------------------------ ANC 映射

    /** MIUI level 码 → 自家 ANC 码（映射关系已确证）。 */
    private fun ancCodeFromMiuiLevel(level: String): Int = when (level.trim()) {
        "0000" -> ModuleIpc.ANC_OFF
        "0200", "0201" -> ModuleIpc.ANC_TRANSPARENCY
        "0100", "0101", "0102", "0103" -> ModuleIpc.ANC_NC
        else -> ModuleIpc.ANC_NC
    }

    /** 自家 ANC 码 → MIUI level 码。 */
    private fun miuiAncLevel(ancCode: Int): String = when (ancCode) {
        ModuleIpc.ANC_OFF -> "0000"
        ModuleIpc.ANC_TRANSPARENCY -> "0200"
        else -> "0100"   // 降噪：用中等档作为对外代表值
    }

    private fun noiseToAncCode(mode: NoiseMode): Int = when (mode) {
        NoiseMode.OFF -> ModuleIpc.ANC_OFF
        NoiseMode.TRANSPARENT -> ModuleIpc.ANC_TRANSPARENCY
        else -> ModuleIpc.ANC_NC
    }

    private fun ancCodeToNoise(code: Int): NoiseMode = when (code) {
        ModuleIpc.ANC_OFF -> NoiseMode.OFF
        ModuleIpc.ANC_TRANSPARENCY -> NoiseMode.TRANSPARENT
        else -> NoiseMode.NORMAL
    }

    /** 系统四档（int 模式）→ 耳机命令。 */
    private fun applyAncFromMode(mode: Int, device: BluetoothDevice) {
        val ancCode = when (mode) {
            0 -> ModuleIpc.ANC_OFF
            2 -> ModuleIpc.ANC_TRANSPARENCY
            else -> ModuleIpc.ANC_NC
        }
        applyAnc(ancCode, device)
    }

    private fun applyAncFromLevel(level: String, device: BluetoothDevice) {
        applyAnc(ancCodeFromMiuiLevel(level), device)
    }

    /** 统一下发 ANC：更新快照 → 发 SPP 命令 → 广播给其它进程 → 推送系统回调。 */
    private fun applyAnc(ancCode: Int, device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val snap = ModuleIpc.snapshot().copy(address = address, anc = ancCode)
        ModuleIpc.broadcastState(appContext, snap)
        ioExecutor.execute {
            runCatching {
                controller.get()?.setNoise(ancCodeToNoise(ancCode))
            }.onFailure { ModuleLog.w(TAG, "下发 ANC 失败：${it.message}") }
        }
        pushStatusToCallbacks(address)
        ModuleLog.i(TAG, "ANC 已切换：code=$ancCode -> $address")
    }

    // ------------------------------------------------------------------ 其它

    /** 接收来自系统 UI / MiLink 的 ANC 选择广播。 */
    private fun registerAncReceiver() {
        val ctx = appContext ?: run {
            ModuleLog.w(TAG, "拿不到 Context，跳过 ANC 广播接收器")
            return
        }
        runCatching {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                    runCatching {
                        when (intent?.action) {
                            ModuleIpc.ACTION_ANC_SELECT -> {
                                val code = intent.getIntExtra(ModuleIpc.EXTRA_ANC, ModuleIpc.ANC_NC)
                                val address = currentAddress.get() ?: lastConnectedAddress
                                if (address != null) {
                                    val snap = ModuleIpc.snapshot().copy(anc = code)
                                    ModuleIpc.broadcastState(appContext, snap)
                                    ioExecutor.execute {
                                        controller.get()?.setNoise(ancCodeToNoise(code))
                                    }
                                    pushStatusToCallbacks(address)
                                    ModuleLog.i(TAG, "收到 ANC 选择广播：code=$code")
                                }
                            }

                            ModuleIpc.ACTION_REFRESH_STATUS -> {
                                ioExecutor.execute { controller.get()?.queryAll() }
                            }
                        }
                    }.onFailure { ModuleLog.w(TAG, "处理广播失败：${it.message}") }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(ModuleIpc.ACTION_ANC_SELECT)
                addAction(ModuleIpc.ACTION_REFRESH_STATUS)
            }
            // Android 14+ / targetSdk>=34 必须显式声明导出标志，否则抛 SecurityException。
            // 跨进程广播必须用 RECEIVER_EXPORTED。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            ModuleLog.i(TAG, "ANC/刷新广播接收器已注册")
        }.onFailure { ModuleLog.w(TAG, "注册广播接收器失败：${it.message}") }
    }

    private fun requestRefresh(device: BluetoothDevice) {
        ioExecutor.execute {
            runCatching {
                val ctrl = controller.get()
                if (ctrl?.isConnected == true) {
                    ctrl.queryAll()
                } else {
                    connectPods(device)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 设备识别

    /** 是否应该接管这台设备：优先精确地址，否则按名称排除小米自家耳机。 */
    private fun shouldHook(device: BluetoothDevice): Boolean = runCatching {
        val config = ModuleConfigStore.current()
        val name = runCatching { device.name }.getOrNull()
        val address = runCatching { device.address }.getOrNull()
        val ok = config.shouldHookDevice(name, address)
        if (ok) ModuleLog.d(TAG, "接管设备：$name / $address")
        ok
    }.getOrDefault(false)

    private fun shouldHookAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val config = ModuleConfigStore.current()
        if (config.targetAddress.isNotBlank()) {
            return address.equals(config.targetAddress, ignoreCase = true)
        }
        // 只有地址时无法排除小米自家设备，用 adapter 查名字
        val name = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)?.name
        }.getOrNull()
        return config.shouldHookDevice(name, address)
    }

    private companion object {
        const val TAG = "BT-Hook"

        /** Binder 实现类候选名（OS4 混淆名 + 旧版名）。 */
        val BINDER_CLASS_CANDIDATES = listOf(
            "com.android.bluetooth.ble.app.headset.BinderC6776v",
            "com.android.bluetooth.ble.app.headset.v",
        )

        const val A2DP_STATE_CONNECTED = 2
        const val A2DP_STATE_DISCONNECTED = 0
    }

    /** 装一个返回固定字符串的 String 参数标志位方法。 */
    private fun hookStringFlag(className: String, names: List<String>, value: String) {
        val m = findMethodByNames(className, names, String::class.java) ?: return
        hookBefore(m) { result = value }
    }

    /** 装一个返回固定布尔的 String 参数标志位方法。 */
    private fun hookBooleanFlagByName(className: String, names: List<String>, value: Boolean) {
        val m = findMethodByNames(className, names, String::class.java) ?: return
        hookBefore(m) { result = value }
    }
}
