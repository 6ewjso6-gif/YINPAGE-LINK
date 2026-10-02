package com.yinpage.link.module

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        _config.value = ModuleConfig(
            fakeDeviceId = (p.getString(K_FAKE_ID, null) ?: ModuleConfig.DEFAULT_FAKE_DEVICE_ID)
                .trim().ifEmpty { ModuleConfig.DEFAULT_FAKE_DEVICE_ID },
            targetAddress = p.getString(K_TARGET_ADDR, "").orEmpty().trim().uppercase(),
            logLevel = p.getInt(K_LOG_LEVEL, ModuleConfig.LOG_BASIC).coerceIn(0, 2),
            hookAnc = p.getBoolean(K_HOOK_ANC, true),
            focusIsland = p.getBoolean(K_FOCUS_ISLAND, true),
            useNativeHeadsetPage = p.getBoolean(K_NATIVE_PAGE, true),
        )
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
}
