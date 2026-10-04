package com.yinpage.link.module.hook

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.yinpage.link.module.ModuleConfigStore
import com.yinpage.link.module.ipc.ModuleIpc
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================================
 *  作用域：com.android.settings —— 复用系统原生高级耳机页（P1）
 * ============================================================================
 *  路线选择：**复用系统原生页**（OppoPods 路线），而不是自绘界面。
 *  原因：Feel 1 Pro 支持四档 ANC，系统原生页白送四档 UI + 电量卡片；
 *  自绘界面还要跨模块启动 Activity，复杂且容易崩。
 *
 *  三处要拦：
 *   1. `MiuiHeadsetActivity` / `MiuiHeadsetActivityPlugin`（HyperOS 4 双入口）
 *      —— 给 Intent 打补丁 + 伪造 getDeviceID()/getSupport()，让页面认为
 *      打开的是"受支持的小米耳机"；
 *   2. `HeadsetIDConstants` —— checkSupport 放行、按 TWS01 形态伪装、MMA 判定放行；
 *   3. `IMiuiHeadsetService$Stub$Proxy` —— Settings 侧跨进程代理再拦一遍
 *      （MiLink 进程里那层由 [MiLinkServiceHook] 负责）。
 *
 *  另外**必须吞掉 MMA 连接失败提示**：伪设备没有真实 MMA 通道，
 *  不吞就会一直弹"连接失败"。
 *
 *  ⚠️ 所有 Hook 独立 try-catch，符号缺失静默跳过；伪造值按真实返回类型校正（见 [fake]）。
 * ============================================================================
 */
class SettingsHeadsetHook : HookContext() {

    /** 状态接收器只注册一次（本进程要读电量，必须接收蓝牙进程的 PODS_STATE 广播）。 */
    private val receiverRegistered = AtomicBoolean(false)

    /** 最近一次见到的 Activity Context：发广播用（比反射取 Application 更可靠）。 */
    @Volatile
    private var activityContext: Context? = null

    override fun onHook() {
        ModuleLog.i(TAG, "开始在 $packageName 安装设置页 Hook（原生高级耳机页路线）")

        var count = 0
        count += hookHeadsetActivities()
        count += hookHeadsetIdConstants()
        count += hookServiceProxy()
        count += hookMmaFailure()
        ensureStateReceiver()

        ModuleLog.i(TAG, "设置页 Hook 安装完成：$count 个方法")
    }

    // ------------------------------------------------------------------ 1) 高级耳机页入口

    private fun hookHeadsetActivities(): Int {
        var count = 0
        for (className in ACTIVITY_CLASSES) {
            if (findClassOrNull(className) == null) {
                ModuleLog.d(TAG, "未找到 $className，跳过")
                continue
            }

            // onCreate(Bundle)：改 Intent，让页面按"受支持设备"进入
            val onCreate = findMethodOrNull(className, "onCreate", Bundle::class.java)
                ?: findMethodByParamCount(className, "onCreate", 1)
            if (onCreate != null) {
                hookBefore(onCreate) { patchHeadsetIntent() }
                count++
            }

            // getDeviceID() / getSupport()：页面直接读这两个值时也要给伪值
            findMethodByParamCount(className, "getDeviceID", 0)?.let { method ->
                hookBefore(method) {
                    runCatching { fake(fakeDeviceId()) }
                        .onFailure { ModuleLog.w(TAG, "getDeviceID 伪造失败：${it.message}") }
                }
                count++
            }
            findMethodByParamCount(className, "getSupport", 0)?.let { method ->
                hookBefore(method) {
                    runCatching { fakeSupport() }
                        .onFailure { ModuleLog.w(TAG, "getSupport 伪造失败：${it.message}") }
                }
                count++
            }
        }
        return count
    }

    /**
     * 给高级耳机页的 Intent 打补丁：
     * `MIUI_HEADSET_SUPPORT` = 支持串、`DEVICE_ID` = 伪设备 ID、`COME_FROM` = 蓝牙设置页。
     * key 优先从目标类的常量字段读（MIUI 改字面量时仍能命中），读不到再用字面量。
     */
    private fun HookParam.patchHeadsetIntent() {
        runCatching {
            (instance as? Context)?.let {
                activityContext = it
                ensureStateReceiver()
            }
            if (!nativePageEnabled()) return@runCatching

            val intent = callMethodOrNull(instance, "getIntent") as? Intent ?: return@runCatching
            val config = ModuleConfigStore.current()
            intent.putExtra(extraKey(FIELD_SUPPORT), config.supportString())
            intent.putExtra(extraKey(FIELD_DEVICE_ID), config.fakeDeviceId)
            intent.putExtra(extraKey(FIELD_COME_FROM), COME_FROM_VALUE)
            ModuleLog.i(TAG, "${instance?.javaClass?.name} 的 Intent 已打补丁：${config.supportString()}")
        }.onFailure { ModuleLog.w(TAG, "Intent 补丁失败：${it.message}") }
    }

    /** Intent extra 的 key：先试目标类常量，再退回字面量。 */
    private fun extraKey(fieldName: String): String {
        for (className in CONSTANT_CLASSES) {
            val clazz = findClassOrNull(className) ?: continue
            val field = findFieldOrNull(clazz, fieldName) ?: continue
            val value = runCatching { field.get(null) }.getOrNull()
            if (value is String && value.isNotEmpty()) return value
        }
        return fieldName
    }

    // ------------------------------------------------------------------ 2) HeadsetIDConstants

    private fun hookHeadsetIdConstants(): Int {
        val className = CLASS_HEAD_SET_ID_CONSTANTS
        if (findClassOrNull(className) == null) {
            ModuleLog.d(TAG, "未找到 $className，跳过")
            return 0
        }
        var count = 0
        count += installBoolFlag(className, "checkSupport", true)
        count += installBoolFlag(className, "isTWS01Headset", true)
        count += installBoolFlag(className, "isK77sHeadset", false)

        // isBleMmaConnect 有两个重载（首参分别是 Context / IMiuiHeadsetService），两个都要放行
        count += hookAllOverloads(findClassOrNull(className), "isBleMmaConnect") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                fake(true)
            }.onFailure { ModuleLog.w(TAG, "isBleMmaConnect 伪造失败：${it.message}") }
        }
        return count
    }

    /** 装一个返回固定布尔的单参/无参判定方法。 */
    private fun installBoolFlag(className: String, name: String, value: Boolean): Int {
        val method = findMethodByParamCount(className, name, 1)
            ?: findMethodByParamCount(className, name, 0)
        if (method == null) {
            ModuleLog.d(TAG, "未找到 $className.$name，跳过")
            return 0
        }
        hookBefore(method) {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                fake(value)
            }.onFailure { ModuleLog.w(TAG, "$name 伪造失败：${it.message}") }
        }
        return 1
    }

    // ------------------------------------------------------------------ 3) 跨进程代理

    /**
     * `IMiuiHeadsetService$Stub$Proxy`：Settings 进程里对 MiLink 服务的代理。
     * 方法名与 transaction code（已确证）：
     *   checkSupport(1) / getDeviceInfo(11) / isSupportAudioSwitch(20) / isMiTWS(18) /
     *   checkIsMiTWS(19) / getRingFindState(24) / setCommonCommand(14) / changeAncMode(9) /
     *   changeAncLevel(10) / connect(4) / getDeviceConfig(12) / getCommonConfig(15)
     *
     * 按**方法名**装（同名重载全装），不依赖 transaction code。
     */
    private fun hookServiceProxy(): Int {
        val className = PROXY_CLASS
        val clazz = findClassOrNull(className)
        if (clazz == null) {
            ModuleLog.d(TAG, "未找到 $className（本机 Settings 未链接该 AIDL），跳过")
            return 0
        }
        var count = 0

        // ---- 身份 / 能力：与 MiLink 侧结论保持一致 ----
        count += hookAllOverloads(clazz, "checkSupport") {
            runCatching {
                ensureStateReceiver()
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                fakeSupport()
            }.onFailure { ModuleLog.w(TAG, "checkSupport 伪造失败：${it.message}") }
        }
        count += hookAllOverloads(clazz, "isMiTWS") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                fake(true)
            }.onFailure { ModuleLog.w(TAG, "isMiTWS 伪造失败：${it.message}") }
        }
        count += hookAllOverloads(clazz, "checkIsMiTWS") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                fake(true)
            }.onFailure { ModuleLog.w(TAG, "checkIsMiTWS 伪造失败：${it.message}") }
        }
        count += hookAllOverloads(clazz, "isSupportAudioSwitch") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                fake(1)
            }.onFailure { ModuleLog.w(TAG, "isSupportAudioSwitch 伪造失败：${it.message}") }
        }
        count += hookAllOverloads(clazz, "getRingFindState") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                fake(false)
            }.onFailure { ModuleLog.w(TAG, "getRingFindState 伪造失败：${it.message}") }
        }

        // ---- 设备信息 / 配置：页面靠这条渲染电量与 ANC 档位 ----
        count += hookAllOverloads(clazz, "getDeviceInfo") {
            runCatching {
                ensureStateReceiver()
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                val payload = buildStatePayload()
                fake(payload)
                ModuleLog.i(TAG, "getDeviceInfo → $payload")
            }.onFailure { ModuleLog.w(TAG, "getDeviceInfo 伪造失败：${it.message}") }
        }
        for (name in listOf("getDeviceConfig", "getCommonConfig")) {
            count += hookAllOverloads(clazz, name) {
                runCatching {
                    if (!nativePageEnabled()) return@runCatching
                    if (!guardProxyArgs(args)) return@runCatching
                    // 与蓝牙进程侧一致：吞掉真实查询，改为请求蓝牙进程刷新一次真实状态
                    fake(null)
                    ModuleIpc.broadcastRefresh(appContext)
                    ModuleLog.i(TAG, "$name → 已置空并请求蓝牙进程刷新")
                }.onFailure { ModuleLog.w(TAG, "$name 处理失败：${it.message}") }
            }
        }

        // ---- 能力探测：123 是"支持几档 ANC"的探测命令 ----
        count += hookAllOverloads(clazz, "setCommonCommand") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                if (!guardProxyArgs(args)) return@runCatching
                val command = args.filterIsInstance<Int>().firstOrNull()
                fake(if (command == COMMAND_ANC_LEVEL_COUNT) "4" else "1")
            }.onFailure { ModuleLog.w(TAG, "setCommonCommand 伪造失败：${it.message}") }
        }

        // ---- ANC：转成自家码广播给蓝牙进程（不改返回值，避免误吞系统流程） ----
        count += hookAllOverloads(clazz, "changeAncMode") { applyAncFromProxyMode() }
        count += hookAllOverloads(clazz, "changeAncLevel") { applyAncFromProxyLevel() }

        // ---- 连接：伪设备已由蓝牙进程维护连接，这里只请求刷新 ----
        count += hookAllOverloads(clazz, "connect") {
            runCatching {
                if (!nativePageEnabled()) return@runCatching
                ensureStateReceiver()
                ModuleIpc.broadcastRefresh(appContext)
                ModuleLog.i(TAG, "connect() → 已请求蓝牙进程刷新真实状态")
            }.onFailure { ModuleLog.w(TAG, "connect 处理失败：${it.message}") }
        }

        return count
    }

    /** `changeAncMode(int, BluetoothDevice)`：MIUI 模式索引 → 自家 ANC 码 → 广播。 */
    private fun HookParam.applyAncFromProxyMode() {
        runCatching {
            if (!ModuleConfigStore.current().hookAnc) return@runCatching
            if (!guardProxyArgs(args)) return@runCatching
            val mode = args.filterIsInstance<Int>().firstOrNull() ?: return@runCatching
            val ancCode = ancCodeFromMiuiMode(mode)
            ModuleIpc.updateSnapshot(ModuleIpc.snapshot().copy(anc = ancCode))
            sendAncSelect(ancCode)
            ModuleLog.i(TAG, "changeAncMode($mode) → 自家码=$ancCode → 已广播给蓝牙进程")
        }.onFailure { ModuleLog.w(TAG, "changeAncMode 处理失败：${it.message}") }
    }

    /** `changeAncLevel(String, BluetoothDevice)`：`0103` 这类 level 码 → 自家 ANC 码 → 广播。 */
    private fun HookParam.applyAncFromProxyLevel() {
        runCatching {
            if (!ModuleConfigStore.current().hookAnc) return@runCatching
            if (!guardProxyArgs(args)) return@runCatching
            val level = args.filterIsInstance<String>().firstOrNull() ?: return@runCatching
            val ancCode = ancCodeFromMiuiLevel(level)
            ModuleIpc.updateSnapshot(ModuleIpc.snapshot().copy(anc = ancCode))
            sendAncSelect(ancCode)
            ModuleLog.i(TAG, "changeAncLevel($level) → 自家码=$ancCode → 已广播给蓝牙进程")
        }.onFailure { ModuleLog.w(TAG, "changeAncLevel 处理失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 4) 吞掉 MMA 连接失败

    /**
     * 伪设备没有真实 MMA 通道，`refreshStatus` 收到 `MMA_CONNECTION_FAILED...`
     * 或 `handleConnectMmaFailed(...)` 被调用时直接吞掉，否则会一直弹"连接失败"。
     *
     * `handleConnectMmaFailed` 无条件吞掉：它的语义就是"处理 MMA 连接失败"，
     * 参数可能是失败原因，也可能是设备地址，无法据此判断，索性整体跳过。
     */
    private fun hookMmaFailure(): Int {
        var count = 0
        for (className in FRAGMENT_CLASSES) {
            if (findClassOrNull(className) == null) {
                ModuleLog.d(TAG, "未找到 $className，跳过")
                continue
            }

            val refresh = findMethodOrNull(className, "refreshStatus", String::class.java, String::class.java)
                ?: findMethodByParamCount(className, "refreshStatus", 2)
            if (refresh != null) {
                hookBefore(refresh) {
                    runCatching {
                        val failed = args.filterIsInstance<String>().any { isMmaFailure(it) }
                        if (!failed) return@runCatching
                        fake(null)
                        ModuleLog.i(TAG, "已吞掉 MMA 连接失败提示：refreshStatus")
                    }.onFailure { ModuleLog.w(TAG, "refreshStatus 处理失败：${it.message}") }
                }
                count++
            }

            val handle = findMethodOrNull(className, "handleConnectMmaFailed", String::class.java)
                ?: findMethodByParamCount(className, "handleConnectMmaFailed", 1)
            if (handle != null) {
                hookBefore(handle) {
                    runCatching {
                        fake(null)
                        ModuleLog.i(TAG, "已吞掉 MMA 连接失败回调：handleConnectMmaFailed")
                    }.onFailure { ModuleLog.w(TAG, "handleConnectMmaFailed 处理失败：${it.message}") }
                }
                count++
            }
        }
        return count
    }

    private fun isMmaFailure(message: String?): Boolean {
        val text = message ?: return false
        return text.startsWith(MMA_FAILED_PREFIX) || text.contains(MMA_FAILED_PREFIX)
    }

    // ------------------------------------------------------------------ 状态与广播

    /**
     * 注册耳机状态接收器：页面渲染用的电量来自 [ModuleIpc.snapshot]，
     * 不注册就永远读到"未连接"。
     *
     * ⚠️ 只允许用 **Application Context** 注册（[HookContext.appContext]），
     * 否则 Activity 创建但没走完销毁流程时，广播接收器会持有 Activity 实例
     * 导致 `IntentReceiverLeaked` 与内存泄漏。此处拿不到 appContext 就等下次
     * Activity 打开时再重试 —— 注册是幂等的（[receiverRegistered] 去重）。
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

    /** 把"用户切了 ANC"发给 `com.android.bluetooth` 进程。 */
    private fun sendAncSelect(ancCode: Int) {
        val context = activityContext ?: appContext
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
        }.onFailure { ModuleLog.w(TAG, "广播 ANC 选择失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 状态串与映射

    /**
     * 16 字段状态串，索引与蓝牙进程 `refreshStatus` 推送的格式**逐字一致**：
     *   `[0]`=左耳 `[1]`=右耳 `[2]`=盒 电量（255=未连接，充电 = 值 or 128）、
     *   `[7]`=ANC 档位码、`[8]`="true"、`[11]`/`[13]`/`[14]`="00"，其余留空。
     *
     * ⚠️ 若 Lead 调整该布局，这里必须同步（见交付说明中的待仲裁项）。
     */
    private fun buildStatePayload(): String {
        val snap = runCatching { ModuleIpc.snapshot() }.getOrDefault(ModuleIpc.PodSnapshot())
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

    /** 自家 ANC 码 → MIUI level 码（对外代表值）。 */
    private fun miuiAncLevel(ancCode: Int): String = when (ancCode) {
        ModuleIpc.ANC_OFF -> "0000"
        ModuleIpc.ANC_TRANSPARENCY -> "0200"
        else -> "0100"
    }

    /**
     * MIUI level 码 → 自家 ANC 码（映射已确证）：
     * `0103`=智能 `0101`=轻度 `0100`=均衡 `0102`=深度 `0200`=通透 `0201`=通透+人声 `0000`=关。
     */
    private fun ancCodeFromMiuiLevel(level: String): Int = when (level.trim()) {
        "0000" -> ModuleIpc.ANC_OFF
        "0200", "0201" -> ModuleIpc.ANC_TRANSPARENCY
        else -> ModuleIpc.ANC_NC
    }

    /** `changeAncMode(int)` 的模式索引 → 自家 ANC 码（与蓝牙进程侧映射一致）。 */
    private fun ancCodeFromMiuiMode(mode: Int): Int = when (mode) {
        0 -> ModuleIpc.ANC_OFF
        2 -> ModuleIpc.ANC_TRANSPARENCY
        else -> ModuleIpc.ANC_NC
    }

    private fun fakeDeviceId(): String =
        runCatching { ModuleConfigStore.current().fakeDeviceId }.getOrDefault(DEFAULT_FAKE_DEVICE_ID)

    private fun nativePageEnabled(): Boolean =
        runCatching { ModuleConfigStore.current().useNativeHeadsetPage }.getOrDefault(true)

    // ------------------------------------------------------------------ 设备归属判定

    /**
     * 代理方法的参数判定：带 BluetoothDevice → 按设备；带 MAC 串 → 按地址；
     * 带 8 位 deviceId → 只认我们自己伪造的 ID（保护小米自家耳机）；其余放行。
     */
    private fun guardProxyArgs(args: List<Any?>): Boolean {
        args.filterIsInstance<BluetoothDevice>().firstOrNull()?.let { return isTargetDevice(it) }
        args.filterIsInstance<String>().firstOrNull { MAC_REGEX.matches(it.trim()) }?.let {
            return isTargetAddress(it)
        }
        args.filterIsInstance<String>().firstOrNull { DEVICE_ID_REGEX.matches(it.trim()) }?.let {
            return it.contains(ModuleConfigStore.current().fakeDeviceId)
        }
        return true
    }

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

    // ------------------------------------------------------------------ 通用安装

    /** 把某个方法名的**所有重载**都装上（签名漂移时最稳）。 */
    private fun hookAllOverloads(clazz: Class<*>?, name: String, block: HookParam.() -> Unit): Int {
        if (clazz == null) return 0
        val methods = runCatching {
            clazz.declaredMethods.filter { it.name == name && !it.isSynthetic }
        }.getOrNull().orEmpty()
        if (methods.isEmpty()) {
            ModuleLog.d(TAG, "未找到 ${clazz.name}.$name 的任何重载，跳过")
            return 0
        }
        for (method in methods) {
            runCatching { method.isAccessible = true }
            hookBefore(method, block)
        }
        return methods.size
    }

    private companion object {
        const val TAG = "Settings"

        val ACTIVITY_CLASSES = listOf(
            "com.android.settings.bluetooth.MiuiHeadsetActivity",
            "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin",
        )

        /** Intent extra 常量可能挂在这几个类上。 */
        val CONSTANT_CLASSES = listOf(
            "com.android.settings.bluetooth.HeadsetIDConstants",
            "com.android.settings.bluetooth.MiuiHeadsetActivity",
            "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin",
        )

        const val CLASS_HEAD_SET_ID_CONSTANTS = "com.android.settings.bluetooth.HeadsetIDConstants"
        const val PROXY_CLASS = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"

        val FRAGMENT_CLASSES = listOf(
            "com.android.settings.bluetooth.MiuiHeadsetFragment",
            "com.android.settings.bluetooth.headset.MiuiHeadsetFragment",
        )

        const val FIELD_SUPPORT = "MIUI_HEADSET_SUPPORT"
        const val FIELD_DEVICE_ID = "DEVICE_ID"
        const val FIELD_COME_FROM = "COME_FROM"
        const val COME_FROM_VALUE = "MIUI_BLUETOOTH_SETTINGS"

        /** `setCommonCommand(123, ...)` = "支持几档 ANC"探测，回 "4"。 */
        const val COMMAND_ANC_LEVEL_COUNT = 123

        const val MMA_FAILED_PREFIX = "MMA_CONNECTION_FAILED"

        const val BLUETOOTH_PACKAGE = "com.android.bluetooth"
        const val EXTRA_ANC_STATUS = "status"
        const val DEFAULT_FAKE_DEVICE_ID = "01010607"

        val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        val DEVICE_ID_REGEX = Regex("\\d{8}")
    }
}

// ============================================================================
//  文件级私有工具（与 MiLinkServiceHook.kt 内的同名工具各自独立：
//  三个作用域互不依赖，避免顶层符号冲突）
// ============================================================================

/** 按方法**真实返回类型**校正伪造值，防止拆箱 ClassCastException 打崩系统进程。 */
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

/**
 * 伪造"受支持"结论：返回类型是布尔就给 true，是整型就给 1，
 * 其余（String）给完整的支持串 `deviceId,能力位掩码`。
 * 不能直接用 [fake]——支持串转整数会得到 0（= 不支持），方向正好相反。
 */
private fun HookParam.fakeSupport() {
    val type = (executable as? Method)?.returnType
    val support = runCatching { ModuleConfigStore.current().supportString() }
        .getOrDefault(DEFAULT_FAKE_SUPPORT)
    result = when {
        type == null -> support
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> true
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 1
        else -> support
    }
}

private const val DEFAULT_FAKE_SUPPORT = "01010607,000000000000000010000000"
