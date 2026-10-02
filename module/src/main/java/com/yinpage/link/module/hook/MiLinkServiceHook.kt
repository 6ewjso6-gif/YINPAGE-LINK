package com.yinpage.link.module.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import com.yinpage.link.module.ModuleConfigStore
import com.yinpage.link.module.ipc.ModuleIpc
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 *  作用域：com.milink.service —— 融合设备中心的数据源（P0）
 * ============================================================================
 *  MiLink 是"设备中心 / 耳机卡片"真正读数据的地方：它通过
 *  `com.xiaomi.mxbluetoothsdk.*` 查询设备身份与能力，通过
 *  `com.miui.headset.runtime.*` 读取电量 / ANC，UI 则读 `com.miui.headset.api.HeadsetInfo`。
 *
 *  只要这些方法对音贝奇耳机返回"小米 TWS 的标准答案"，系统就会把耳机
 *  当成自家耳机渲染出卡片与四档 ANC 控件；用户点 ANC 时再经
 *  [setAncStateBlock] 反向通知 `com.android.bluetooth` 进程下发真实 SPP 命令。
 *
 *  ⚠️ 三条硬性纪律（系统进程里出错 = 用户手机出问题）：
 *   1. 每个 Hook 独立 try-catch，符号找不到就静默跳过（[findClassOrNull] 返回 null）；
 *   2. 伪造返回值一律经 [fake] 按**方法真实返回类型**校正，避免返回值类型不符
 *      在调用方拆箱时抛 ClassCastException；
 *   3. 不新增依赖、不 import 独立 App（`:app`）的任何类。
 *
 *  注：本类也被 `com.xiaomi.bluetooth` 作用域复用（见 [com.yinpage.link.module.YinpageModule]），
 *  在那边大部分 MiLink 类不存在，所有查找会静默失败——这是预期行为。
 * ============================================================================
 */
class MiLinkServiceHook : HookContext() {

    /** 状态广播接收器只注册一次；onHook 时拿不到 Application 就留到首个 Hook 触发时重试。 */
    private val receiverRegistered = AtomicBoolean(false)

    override fun onHook() {
        ModuleLog.i(TAG, "开始在 $packageName 安装 MiLink Hook")

        var count = 0
        count += hookSdkClasses()
        count += hookProfileContext()
        count += hookAncBatteryController()
        count += hookHeadsetInfo()
        ensureStateReceiver()

        ModuleLog.i(TAG, "MiLink Hook 安装完成：$count 个方法")
    }

    // ------------------------------------------------------------------ 小米 SDK：服务与管理器

    /**
     * `MxBluetoothService` 与 `MxBluetoothManager` 两个类**都装**（不同版本走不同入口），
     * 任一类不存在就跳过。
     */
    private fun hookSdkClasses(): Int {
        var count = 0
        for (className in SDK_CLASSES) {
            if (findClassOrNull(className) == null) {
                ModuleLog.d(TAG, "未找到 $className（版本不同），跳过")
                continue
            }
            val installed = installFakes(className, sdkFakePoints())
            count += installed
            ModuleLog.i(TAG, "$className：安装 $installed 个伪造点")
        }
        return count
    }

    /** 身份 / 能力 / 状态类方法的伪造表（值在调用时求值，配置与状态变化即时生效）。 */
    private fun sdkFakePoints(): List<FakePoint> = listOf(
        // ---- 身份与能力：让系统认成"受支持的小米 TWS" ----
        FakePoint("checkIsMiTWS", BluetoothDevice::class.java, 1) { 1 },
        FakePoint("getDeviceId", BluetoothDevice::class.java, 1) { fakeDeviceId() },
        FakePoint("isMiTWS", String::class.java, 1) { true },
        FakePoint("isSupportAudioSwitch", String::class.java, 1) { 1 },
        FakePoint("isLeAudio", BluetoothDevice::class.java, 1) { false },
        // ---- 状态：电量 / ANC / 佩戴 / 空间音频 ----
        FakePoint("getBatteryLevel", BluetoothDevice::class.java, 1) { 1 },
        FakePoint("getAncState", BluetoothDevice::class.java, 1) { miLinkAncState() },
        FakePoint("getDeviceRunInfo", BluetoothDevice::class.java, 1) { 0 },
        FakePoint("getSpatialMode", BluetoothDevice::class.java, 1) { 0 },
        FakePoint("getWearStatus", BluetoothDevice::class.java, 1) { "0,0" },
        FakePoint("getRingFindState", String::class.java, 1) { false },
    )

    // ------------------------------------------------------------------ ProfileContext

    private fun hookProfileContext(): Int {
        val className = CLASS_PROFILE_CONTEXT
        if (findClassOrNull(className) == null) {
            ModuleLog.d(TAG, "未找到 $className，跳过")
            return 0
        }
        return installFakes(
            className,
            listOf(
                FakePoint("getDeviceId", BluetoothDevice::class.java, 1) { fakeDeviceId() },
            ),
        )
    }

    // ------------------------------------------------------------------ AncBatteryController

    private fun hookAncBatteryController(): Int {
        val className = CLASS_ANC_BATTERY
        if (findClassOrNull(className) == null) {
            ModuleLog.d(TAG, "未找到 $className，跳过")
            return 0
        }
        var count = installFakes(
            className,
            listOf(
                FakePoint("getDeviceId", BluetoothDevice::class.java, 1) { fakeDeviceId() },
                FakePoint("getAncState", BluetoothDevice::class.java, 1) { miLinkAncState() },
                FakePoint("getBatteryLevelCache", BluetoothDevice::class.java, 1) { miLinkBatteryLevels() },
                FakePoint("getHeadsetPropertyBlock", BluetoothDevice::class.java, 1) { headsetPropertyBlock() },
            ),
        )
        count += hookSetAncStateBlock(className)
        return count
    }

    /**
     * **核心双向点**：用户在设备中心/控制中心点 ANC → MiLink 调
     * `AncBatteryController.setAncStateBlock(BluetoothDevice, int)`。
     *
     * 处理三步：
     *  1. 记下当前 ANC（乐观更新本地快照）并把"用户切了 ANC"广播给
     *     `com.android.bluetooth` 进程（由蓝牙侧下发真实 SPP 命令）；
     *  2. 通过 `headsetPropertyChangeListener` 通知 UI 刷新（updateType 8=ANC、4=电量，
     *     与参考实现一致）；
     *  3. 回填系统期望的 MiLink 三态，避免控件立刻弹回旧档位。
     */
    private fun hookSetAncStateBlock(className: String): Int {
        val method = findFlexible(
            className, "setAncStateBlock", 2,
            // javaPrimitiveType 的静态类型是 Class<Int>?，这里断言非空以满足 vararg Class<*>
            BluetoothDevice::class.java, Int::class.javaPrimitiveType!!,
        )
        if (method == null) {
            ModuleLog.d(TAG, "未找到 $className.setAncStateBlock(BluetoothDevice,int)，ANC 双向链路不可用")
            return 0
        }
        hookBefore(method) {
            runCatching {
                if (!ModuleConfigStore.current().hookAnc) return@runCatching
                val device = args.filterIsInstance<BluetoothDevice>().firstOrNull()
                val miLinkMode = args.filterIsInstance<Int>().firstOrNull()
                if (miLinkMode == null) {
                    ModuleLog.w(TAG, "setAncStateBlock 参数异常：$args")
                    return@runCatching
                }

                // 1) 记录当前 ANC + 广播给蓝牙进程
                val ownCode = ownAncCodeFromMiLink(miLinkMode)
                ModuleIpc.updateSnapshot(ModuleIpc.snapshot().copy(anc = ownCode))
                sendAncSelect(ownCode)

                // 2) 通知 UI 刷新
                if (device != null) {
                    notifyHeadsetPropertyChanged(instance, device, UPDATE_TYPE_ANC)
                    notifyHeadsetPropertyChanged(instance, device, UPDATE_TYPE_BATTERY)
                }

                // 3) 回填三态，防止 UI 回弹
                result = coerceReturn(executable, miLinkAncState())
                ModuleLog.i(TAG, "ANC 切换：MiLink=$miLinkMode → 自家=$ownCode → 回填=${miLinkAncState()}")
            }.onFailure { ModuleLog.w(TAG, "setAncStateBlock 处理失败：${it.message}") }
        }
        return 1
    }

    /**
     * 反射通知 `headsetPropertyChangeListener`。
     * 监听器在不同版本可能是 lambda（`invoke`）或接口（`onXxxChanged`），逐个候选名尝试，
     * 一个都不匹配就静默放弃（UI 不刷新而已，绝不能让调用方崩）。
     */
    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice, updateType: Int) {
        runCatching {
            val listener = getObjectFieldAny(controller, "headsetPropertyChangeListener")
                ?: getObjectFieldAny(controller, "mHeadsetPropertyChangeListener")
                ?: return
            for (name in LISTENER_METHOD_NAMES) {
                callMethodOrNull(listener, name, device, updateType)
            }
            ModuleLog.d(TAG, "已通知 UI 刷新：updateType=$updateType")
        }.onFailure { ModuleLog.w(TAG, "通知 UI 刷新失败：${it.message}") }
    }

    // ------------------------------------------------------------------ HeadsetInfo（data class）

    /**
     * `HeadsetInfo` 是 UI 直接读的数据类：`getXxx()` 与 `componentN()` 两组访问器都要伪造，
     * 否则页面上的电量/模式会各读各的。
     */
    private fun hookHeadsetInfo(): Int {
        val className = CLASS_HEADSET_INFO
        if (findClassOrNull(className) == null) {
            ModuleLog.d(TAG, "未找到 $className，跳过")
            return 0
        }
        var count = 0
        count += installZeroArgFakes(className, listOf("getDeviceId", "component3")) { fakeDeviceId() }
        count += installZeroArgFakes(className, listOf("getPowers", "component4")) { miLinkBatteryLevels() }
        count += installZeroArgFakes(className, listOf("getMode", "component5")) { miLinkAncState() }
        return count
    }

    /** 装一组 0 参访问器：方法名多候选，找到几个装几个。 */
    private fun installZeroArgFakes(className: String, names: List<String>, value: () -> Any?): Int {
        var count = 0
        for (name in names) {
            val method = findMethodByParamCount(className, name, 0)
            if (method == null) {
                ModuleLog.d(TAG, "未找到 $className.$name()，跳过")
                continue
            }
            hookBefore(method) {
                runCatching {
                    if (!shouldFakeHeadsetInfo(instance)) return@runCatching
                    fake(value())
                }.onFailure { ModuleLog.w(TAG, "$name 伪造失败：${it.message}") }
            }
            count++
        }
        return count
    }

    // ------------------------------------------------------------------ 通用伪造安装

    private fun installFakes(className: String, points: List<FakePoint>): Int {
        var count = 0
        for (point in points) {
            val method = findFlexible(className, point.name, point.paramCount, point.paramType)
            if (method == null) {
                ModuleLog.d(TAG, "未找到 $className.${point.name}（${point.paramCount} 参），跳过")
                continue
            }
            hookBefore(method) {
                runCatching {
                    ensureStateReceiver()
                    if (!guardArgs(args)) return@runCatching
                    val value = point.value()
                    fake(value)
                    ModuleLog.d(TAG, "${point.name} → $value")
                }.onFailure { ModuleLog.w(TAG, "${point.name} 伪造失败：${it.message}") }
            }
            count++
        }
        return count
    }

    /**
     * 先用精确签名，再退化为"参数个数"匹配。
     * 系统类在不同 OS 小版本可能改包名/泛型/装箱形态，两级查找能多兜一层。
     */
    private fun findFlexible(className: String, name: String, paramCount: Int, vararg exact: Class<*>): Method? {
        if (exact.isNotEmpty()) {
            findMethodOrNull(className, name, *exact)?.let { return it }
        }
        return findMethodByParamCount(className, name, paramCount)
    }

    // ------------------------------------------------------------------ 状态来源

    /** MiLink 三态回填值（0=OFF / 1=NC / 2=Transparency），来自本地快照的自家码。 */
    private fun miLinkAncState(): Int =
        runCatching { ModuleIpc.snapshot().miLinkAncState() }.getOrDefault(MI_LINK_ANC_OFF)

    /** 固定 6 元素：`[盒, 左, 右, 盒充电, 左充电, 右充电]`，`-1` = 未连接。 */
    private fun miLinkBatteryLevels(): List<Int> =
        runCatching { ModuleIpc.snapshot().miLinkBatteryLevels() }.getOrDefault(UNKNOWN_LEVELS)

    /** 整机电量：左右耳取较小值（未连接为 -1）。 */
    private fun headsetPropertyBlock(): Int =
        runCatching { ModuleIpc.snapshot().headsetPropertyBlock() }.getOrDefault(ModuleIpc.UNKNOWN_LEVEL)

    private fun fakeDeviceId(): String =
        runCatching { ModuleConfigStore.current().fakeDeviceId }.getOrDefault(DEFAULT_FAKE_DEVICE_ID)

    /** 自家 ANC 码 → MiLink 三态；MiLink 三态 → 自家码。 */
    private fun ownAncCodeFromMiLink(miLinkMode: Int): Int = when (miLinkMode) {
        MI_LINK_ANC_OFF -> ModuleIpc.ANC_OFF
        MI_LINK_ANC_NC -> ModuleIpc.ANC_NC
        MI_LINK_ANC_TRANSPARENCY -> ModuleIpc.ANC_TRANSPARENCY
        // 少数版本传的是四档 index（0/1/2/3）：0=关、2=通透、其余=降噪
        0 -> ModuleIpc.ANC_OFF
        2 -> ModuleIpc.ANC_TRANSPARENCY
        else -> ModuleIpc.ANC_NC
    }

    // ------------------------------------------------------------------ 跨进程：广播

    /** 把"用户切了 ANC"发给 `com.android.bluetooth` 进程（只有它握着 SPP 通道）。 */
    private fun sendAncSelect(ancCode: Int) {
        val context = appContext
        if (context == null) {
            ModuleLog.w(TAG, "拿不到 Context，无法把 ANC 选择广播给蓝牙进程")
            return
        }
        runCatching {
            val intent = Intent(ModuleIpc.ACTION_ANC_SELECT).apply {
                // 蓝牙进程读 EXTRA_ANC（"anc"）；任务书早期约定的 "status" 也一并带上，双保险
                putExtra(ModuleIpc.EXTRA_ANC, ancCode)
                putExtra(EXTRA_ANC_STATUS, ancCode)
                setPackage(BLUETOOTH_PACKAGE)
            }
            context.sendBroadcast(intent)
            ModuleLog.i(TAG, "已广播 ANC 选择：自家码=$ancCode")
        }.onFailure { ModuleLog.w(TAG, "广播 ANC 选择失败：${it.message}") }
    }

    /**
     * 注册耳机状态接收器（蓝牙进程 → 本进程）。
     * onHook 阶段 Application 可能还没创建，因此这里允许失败，
     * 由首个 Hook 触发时调用 [ensureStateReceiver] 重试。
     */
    private fun ensureStateReceiver() {
        if (receiverRegistered.get()) return
        val context = appContext
        if (context == null) {
            ModuleLog.d(TAG, "暂无 Application Context，稍后重试注册状态接收器")
            return
        }
        if (!receiverRegistered.compareAndSet(false, true)) return
        ModuleIpc.registerStateReceiver(context) { snap ->
            ModuleLog.i(
                TAG,
                "耳机状态更新：连接=${snap.connected} 左=${snap.left} 右=${snap.right} " +
                    "盒=${snap.case} ANC=${snap.anc}",
            )
        }
    }

    // ------------------------------------------------------------------ 设备归属判定

    /**
     * 参数里带 BluetoothDevice 时按设备判定；带 MAC 字符串按地址判定；
     * 与具体设备无关的调用（deviceId / 配置查询）放行。
     */
    private fun guardArgs(args: List<Any?>): Boolean {
        args.filterIsInstance<BluetoothDevice>().firstOrNull()?.let { return isTargetDevice(it) }
        args.filterIsInstance<String>().firstOrNull { it.contains(":") }?.let { return isTargetAddress(it) }
        return true
    }

    /**
     * 是否应该对这台设备伪造数据。
     *
     *  - 配置了 `targetAddress`：只认该地址（用户显式指定，最可靠）；
     *  - 否则：地址与蓝牙进程推来的快照一致 → 是我们的耳机；
     *  - 否则：名称可判定时按 [ModuleConfigStore] 的规则排除小米自家耳机；
     *  - 名称拿不到（未解析）时**放行**——否则可能在设备刚连接时漏掉真耳机。
     */
    private fun isTargetDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val config = ModuleConfigStore.current()
        val address = runCatching { device.address }.getOrNull()
        if (config.targetAddress.isNotBlank()) {
            return !address.isNullOrBlank() && address.equals(config.targetAddress, ignoreCase = true)
        }
        if (sameAsSnapshot(address)) return true
        val name = runCatching { device.name }.getOrNull()?.trim().orEmpty()
        return name.isEmpty() || config.shouldHookDevice(name, address)
    }

    /** 只有 MAC 字符串时的归属判定：必要时反查一次设备名。 */
    private fun isTargetAddress(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val mac = address.trim()
        val config = ModuleConfigStore.current()
        if (config.targetAddress.isNotBlank()) {
            return mac.equals(config.targetAddress, ignoreCase = true)
        }
        if (sameAsSnapshot(mac)) return true
        val name = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)?.name
        }.getOrNull()?.trim().orEmpty()
        return name.isEmpty() || config.shouldHookDevice(name, mac)
    }

    private fun sameAsSnapshot(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val snap = runCatching { ModuleIpc.snapshot() }.getOrNull() ?: return false
        return snap.address.isNotBlank() && address.equals(snap.address, ignoreCase = true)
    }

    /**
     * `HeadsetInfo` 的归属判定：它是 data class，字段名不可知，
     * 这里尽力从字段/component 里找出设备地址；**找不到地址就按任务书要求直接伪造**。
     */
    private fun shouldFakeHeadsetInfo(instance: Any?): Boolean {
        if (instance == null) return true
        val address = headsetInfoAddress(instance) ?: return true
        return isTargetAddress(address)
    }

    private fun headsetInfoAddress(instance: Any?): String? {
        if (instance == null) return null
        for (field in HEAD_SET_INFO_ADDRESS_FIELDS) {
            val value = getObjectFieldOrNull(instance, field) ?: continue
            when (value) {
                is BluetoothDevice -> runCatching { value.address }.getOrNull()?.let { return it }
                is String -> if (looksLikeMac(value)) return value
            }
        }
        for (accessor in HEAD_SET_INFO_ADDRESS_ACCESSORS) {
            val value = callMethodOrNull(instance, accessor) ?: continue
            when (value) {
                is BluetoothDevice -> runCatching { value.address }.getOrNull()?.let { return it }
                is String -> if (looksLikeMac(value)) return value
                else -> Unit
            }
        }
        return null
    }

    private fun looksLikeMac(value: String): Boolean = MAC_REGEX.matches(value.trim())

    // ------------------------------------------------------------------ 内部类型

    /** 一个伪造点：方法名 + 参数形态 + 取值函数。 */
    private data class FakePoint(
        val name: String,
        val paramType: Class<*>,
        val paramCount: Int,
        val value: () -> Any?,
    )

    private companion object {
        const val TAG = "MiLink"

        val SDK_CLASSES = listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
        )

        const val CLASS_PROFILE_CONTEXT = "com.miui.headset.runtime.ProfileContext"
        const val CLASS_ANC_BATTERY = "com.miui.headset.runtime.AncBatteryController"
        const val CLASS_HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"

        /** MiLink 三态（系统期望值）。 */
        const val MI_LINK_ANC_OFF = 0
        const val MI_LINK_ANC_NC = 1
        const val MI_LINK_ANC_TRANSPARENCY = 2

        /** `headsetPropertyChangeListener` 的调用入口候选名（lambda / 接口两种形态）。 */
        val LISTENER_METHOD_NAMES = listOf(
            "invoke",
            "onHeadsetPropertyChanged",
            "onPropertyChanged",
            "accept",
            "onChanged",
        )

        /** updateType：8 = ANC，4 = 电量（与参考实现一致）。 */
        const val UPDATE_TYPE_ANC = 8
        const val UPDATE_TYPE_BATTERY = 4

        /** 广播：蓝牙进程包名与"早期约定的 status extra"。 */
        const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        const val EXTRA_ANC_STATUS = "status"

        const val DEFAULT_FAKE_DEVICE_ID = "01010607"
        val UNKNOWN_LEVELS = listOf(-1, -1, -1, 0, 0, 0)

        val HEAD_SET_INFO_ADDRESS_FIELDS = listOf(
            "device", "mDevice", "bluetoothDevice", "address", "mAddress", "deviceAddress", "mac", "mMac",
        )
        val HEAD_SET_INFO_ADDRESS_ACCESSORS = listOf("getAddress", "getDevice", "component1", "component2")

        val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }
}

// ============================================================================
//  文件级私有工具
//  刻意用 `private`（文件私有）：三个作用域各自独立编译单元，
//  避免与 Lead/其它队友的 Hook 文件产生顶层符号冲突。
// ============================================================================

/**
 * 按方法**真实返回类型**校正伪造值。
 *
 * MIUI 各版本同名方法的返回类型可能是 `int` / `boolean` / `String`，
 * 若直接返回类型不符的值，调用方拆箱时会抛 ClassCastException —— 在系统进程里就是崩溃。
 * 对象类型（List / String 之外的类）原样返回，`void` 返回 null。
 */
private fun coerceReturn(executable: java.lang.reflect.Executable, value: Any?): Any? {
    val type = (executable as? Method)?.returnType ?: return value

    // void / java.lang.Void：伪造值只能是 null（用类名判断，避免类映射歧义）
    if (type.name == "void" || type.name == "java.lang.Void") return null
    // String 及其它 CharSequence：统一转字符串
    if (CharSequence::class.java.isAssignableFrom(type)) return value?.toString() ?: ""

    if (type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType) {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    if (type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType) {
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    if (type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType) {
        return when (value) {
            is Number -> value.toLong()
            is Boolean -> if (value) 1L else 0L
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    if (type == Short::class.javaPrimitiveType || type == Short::class.javaObjectType) {
        return when (value) {
            is Number -> value.toShort()
            is Boolean -> if (value) 1.toShort() else 0.toShort()
            is String -> value.toShortOrNull() ?: 0.toShort()
            else -> 0.toShort()
        }
    }

    if (type == Byte::class.javaPrimitiveType || type == Byte::class.javaObjectType) {
        return when (value) {
            is Number -> value.toByte()
            is Boolean -> if (value) 1.toByte() else 0.toByte()
            is String -> value.toByteOrNull() ?: 0.toByte()
            else -> 0.toByte()
        }
    }

    if (type == Double::class.javaPrimitiveType || type == Double::class.javaObjectType) {
        return when (value) {
            is Number -> value.toDouble()
            is Boolean -> if (value) 1.0 else 0.0
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    if (type == Float::class.javaPrimitiveType || type == Float::class.javaObjectType) {
        return when (value) {
            is Number -> value.toFloat()
            is Boolean -> if (value) 1f else 0f
            is String -> value.toFloatOrNull() ?: 0f
            else -> 0f
        }
    }

    if (type == Char::class.javaPrimitiveType || type == Char::class.javaObjectType) {
        return value?.toString()?.firstOrNull() ?: '0'
    }

    // 其余（List / 自定义类等）原样返回
    return value
}

/** 伪造返回值（按真实返回类型校正）。 */
private fun HookParam.fake(value: Any?) {
    result = coerceReturn(executable, value)
}
