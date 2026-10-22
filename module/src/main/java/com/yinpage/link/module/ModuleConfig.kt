package com.yinpage.link.module

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * ============================================================================
 *  模块运行时配置（进程内单例）
 * ============================================================================
 *  配置通过 LSPosed 的 remote preferences 在各进程间共享：
 *  ModuleActivity 写入 → `getRemotePreferences("yinpage_link_settings")`
 *  → 各被 Hook 进程读取。这样用户改设置后不需要重装模块。
 *
 *  ⚠️ 与 HyperOriG 的差异：本项目**不提供多档"岛模式"与自绘弹窗**，
 *  专注于"让系统原生 UI 认识这只耳机"这一件事，配置项因此很少。
 * ============================================================================
 */
data class ModuleConfig(
    /**
     * 伪装的小米耳机 deviceId。
     * `01010607` 是 HyperOS 侧通用的「高级耳机能力兼容位」，不是某个真实型号
     * ——HyperOriG / PuddingPods / OppoPods 三家都用同一个值（已确证，见文档）。
     */
    val fakeDeviceId: String = DEFAULT_FAKE_DEVICE_ID,
    /**
     * 目标耳机蓝牙地址（大写，形如 `AA:BB:CC:DD:EE:FF`）。
     * 空字符串表示"自动判断"：对**不在小米白名单**内的已配对设备生效，
     * 这样可以避免把用户的小米自家耳机也伪装掉。
     */
    val targetAddress: String = "",
    /** 日志级别：0 关 / 1 基本 / 2 详细 */
    val logLevel: Int = LOG_BASIC,
    /** 是否接管 ANC 控件（关闭则只显示电量） */
    val hookAnc: Boolean = true,
    /** 是否重建焦点通知（灵动岛） */
    val focusIsland: Boolean = true,
    /** 是否在系统设置页复用原生高级耳机界面 */
    val useNativeHeadsetPage: Boolean = true,
    /**
     * 跨进程广播鉴权令牌（各被 Hook 进程共享同一份 remote prefs 中的值）。
     *
     * 为什么需要：模块的 IPC 走显式广播（`com.yinpage.link.action.*`），
     * 而蓝牙 / SystemUI / MiLink / Settings 是**不同 UID 的系统进程**，
     * 不能用 `RECEIVER_NOT_EXPORTED` 一刀切（那只收同 UID，会切断合法链路）。
     * 因此改用共享令牌：任意第三方 App 无法读到该令牌，也就无法伪造
     * ANC 控制或电量/状态广播。令牌在模块首次启动时用 [SecureRandom] 生成。
     */
    val ipcToken: String = "",
) {
    /**
     * 支持性判定字符串：`<8位设备ID>,<24位能力位掩码>`。
     * 第二个字段来自 HyperOriG `ConfigManager.fakeSupport()`，格式与内容照抄
     * ——三家不同品牌耳机共用同一常量，说明它是"标准小米 TWS 全功能集"。
     */
    fun supportString(): String = "$fakeDeviceId,$SUPPORT_CAPABILITY"

    /**
     * 判断某个蓝牙设备是否应被本模块接管。
     *
     * 规则：
     *  1. 配置了 [targetAddress] 时，只接管该地址（精确匹配，忽略大小写）；
     *  2. 未配置时，接管"名称不含小米自家关键词"的设备，避免影响用户的小米耳机；
     *  3. 名称为空（未解析出名称）时**不接管**，避免误伤。
     */
    fun shouldHookDevice(name: String?, address: String?): Boolean {
        if (targetAddress.isNotBlank()) {
            return !address.isNullOrBlank() && address.equals(targetAddress, ignoreCase = true)
        }
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        return XIAOMI_KEYWORDS.none { n.contains(it, ignoreCase = true) }
    }

    companion object {
        const val DEFAULT_FAKE_DEVICE_ID = "01010607"
        const val SUPPORT_CAPABILITY = "000000000000000010000000"
        const val LOG_OFF = 0
        const val LOG_BASIC = 1
        const val LOG_DEBUG = 2

        /** 小米/红米自家耳机关键词：命中则不接管，避免伪装自家设备。 */
        private val XIAOMI_KEYWORDS = listOf(
            "Xiaomi", "Redmi", "MI ", "Mi Buds", "MIUI", "小米", "红米",
            "Air2", "Air3", "Buds 3", "Buds 4", "Buds 5",
        )
    }
}

/**
 * 配置读写。被 Hook 的进程通过 [install] 注入 SharedPreferences
 * （由 XposedModule 的 getRemotePreferences 提供）。
 */
object ModuleConfigStore {

    const val PREFS_NAME = "yinpage_link_settings"
    private const val K_FAKE_ID = "fake_device_id"
    private const val K_TARGET_ADDR = "target_address"
    private const val K_LOG_LEVEL = "log_level"
    private const val K_HOOK_ANC = "hook_anc"
    private const val K_FOCUS_ISLAND = "focus_island"
    private const val K_NATIVE_PAGE = "native_headset_page"

    /** 跨进程广播鉴权令牌（首次使用时生成并持久化）。 */
    private const val K_IPC_TOKEN = "ipc_token"

    /** 每个目标进程的 Hook 安装结果，key 前缀：`hook_ok_<包名>`。 */
    private const val K_HOOK_RESULT_PREFIX = "hook_ok_"

    private val _config = MutableStateFlow(ModuleConfig())
    val config: StateFlow<ModuleConfig> = _config.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 由模块入口在拿到 remote preferences 后调用。 */
    fun install(sharedPreferences: SharedPreferences) {
        prefs = sharedPreferences
        reload()
    }

    fun current(): ModuleConfig = _config.value

    /** 从 SharedPreferences 重新读取（配置变化时由框架通知）。 */
    fun reload() {
        val p = prefs ?: return
        // 鉴权令牌：**只读**，绝不在 Hook 进程里生成。
        // LSPosed 的 remote preferences 没有跨进程原子写：多个进程同时"读到空就生成"
        // 会互相覆盖，结果各自缓存不同令牌 → 合法广播全部被对方丢弃（且时好时坏）。
        // 令牌唯一由模块 App 进程通过 [ensureIpcToken] 生成并落盘，其余进程只读；
        // 读到空值时 IPC 按"鉴权不通过"处理（fail-closed），不会退化成无鉴权。
        val token = p.getString(K_IPC_TOKEN, "").orEmpty().trim()
        cachedIpcToken = token
        _config.value = ModuleConfig(
            fakeDeviceId = (p.getString(K_FAKE_ID, null) ?: ModuleConfig.DEFAULT_FAKE_DEVICE_ID)
                .trim().ifEmpty { ModuleConfig.DEFAULT_FAKE_DEVICE_ID },
            targetAddress = p.getString(K_TARGET_ADDR, "").orEmpty().trim().uppercase(),
            logLevel = p.getInt(K_LOG_LEVEL, ModuleConfig.LOG_BASIC).coerceIn(0, 2),
            hookAnc = p.getBoolean(K_HOOK_ANC, true),
            focusIsland = p.getBoolean(K_FOCUS_ISLAND, true),
            useNativeHeadsetPage = p.getBoolean(K_NATIVE_PAGE, true),
            ipcToken = token,
        )
    }

    /** 鉴权令牌的进程内缓存，避免每次收发广播都走一遍配置读取。 */
    @Volatile
    private var cachedIpcToken: String? = null

    /**
     * **仅模块 App 进程调用**：首次生成并持久化 IPC 令牌。
     *
     * 为什么只有写侧能生成：见 [reload] 的说明——多进程并发"首次生成"会互相覆盖。
     * 这里用 `commit()` 同步落盘，保证随后启动的 Hook 进程立刻能读到同一个值。
     */
    fun ensureIpcToken() {
        val p = prefs ?: return
        val existing = p.getString(K_IPC_TOKEN, "").orEmpty().trim()
        if (existing.isNotBlank()) {
            cachedIpcToken = existing
            if (_config.value.ipcToken != existing) {
                _config.value = _config.value.copy(ipcToken = existing)
            }
            return
        }
        val token = generateIpcToken()
        runCatching { p.edit().putString(K_IPC_TOKEN, token).commit() }
        cachedIpcToken = token
        _config.value = _config.value.copy(ipcToken = token)
    }

    /**
     * 当前跨进程鉴权令牌。
     *
     * ⚠️ 不再"兜底生成"：读不到就返回空串，IPC 收发双方都会因令牌为空而拒绝
     * （[com.yinpage.link.module.ipc.ModuleIpc.verifyToken] 明确把空令牌视为不通过）。
     * 这样宁可暂时不同步状态，也不会出现"各进程各自生成、互相不认"的随机故障。
     */
    fun ipcToken(): String = cachedIpcToken?.takeIf { it.isNotBlank() }
        ?: _config.value.ipcToken.takeIf { it.isNotBlank() }
        ?: ""

    /** 生成 128 位随机令牌（32 位十六进制）。 */
    private fun generateIpcToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 写入（仅 ModuleActivity 所在进程使用本地 prefs，其它进程只读）。 */
    fun update(block: (ModuleConfig) -> ModuleConfig) {
        val next = block(current())
        _config.value = next
        val p = prefs ?: return
        p.edit()
            .putString(K_FAKE_ID, next.fakeDeviceId)
            .putString(K_TARGET_ADDR, next.targetAddress)
            .putInt(K_LOG_LEVEL, next.logLevel)
            .putBoolean(K_HOOK_ANC, next.hookAnc)
            .putBoolean(K_FOCUS_ISLAND, next.focusIsland)
            .putBoolean(K_NATIVE_PAGE, next.useNativeHeadsetPage)
            .apply()
    }

    /** 便捷：给模块外（Activity）用的本地偏好。 */
    fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { prefs = it }

    // ------------------------------------------------------------------ Hook 安装状态

    /**
     * 记录某目标进程的 Hook 安装结果，写入 remote preferences，
     * 供 [ModuleStatusProvider]（模块自身进程）查询出真实激活状态。
     */
    fun reportHookResult(pkg: String, ok: Boolean) {
        val p = prefs ?: return
        runCatching {
            p.edit().putBoolean("$K_HOOK_RESULT_PREFIX$pkg", ok).apply()
        }.onFailure { /* 记录失败不影响 Hook 本身 */ }
    }

    /**
     * 读取各目标进程的 Hook 安装结果（Provider 进程用）。
     * MODE_MULTI_PROCESS 强制每次从磁盘重读，避免拿到其它进程写入前的旧缓存。
     */
    fun hookResults(context: Context): Map<String, Boolean> {
        val p = @Suppress("DEPRECATION")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
        val out = HashMap<String, Boolean>()
        for ((k, v) in p.all) {
            if (k.startsWith(K_HOOK_RESULT_PREFIX) && v is Boolean) {
                out[k.removePrefix(K_HOOK_RESULT_PREFIX)] = v
            }
        }
        return out
    }
}
