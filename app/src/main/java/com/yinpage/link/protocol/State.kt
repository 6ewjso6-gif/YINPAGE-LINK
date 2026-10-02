package com.yinpage.link.protocol

/**
 * ============================================================================
 *  YINPAGE-LINK 协议层公共数据模型  ——  冻结契约（v1）
 * ============================================================================
 *  约定：
 *   - 字节统一用 Int（0..255），避免 Kotlin Byte 的有符号/无符号转换错误；
 *     仅在收发包的瞬间做 Byte <-> Int 转换。
 *   - 状态类全部为不可变 data class，UI 用 StateFlow 观察，天然支持 diff。
 *   - 本文件是 protocol / core / ui 三个模块的唯一共享词汇表。
 *     修改这里必须同步改动 doc/CONTRACT.md，并通知所有模块。
 * ============================================================================
 */

/** 便捷类型：无符号字节值的数组（每个元素 0..255）。 */
typealias Bytes = IntArray

/** 单只耳机的电量。 */
data class BatteryLevel(
    /** 0..100；null 表示耳机未上报该值（例如未佩戴/未在盒内）。 */
    val percent: Int?,
    /** 是否正在充电（在充电盒内或插线）。 */
    val charging: Boolean = false,
) {
    val known: Boolean get() = percent != null
    companion object {
        val UNKNOWN = BatteryLevel(null, false)
        fun of(percent: Int, charging: Boolean = false) =
            BatteryLevel(percent.coerceIn(0, 100), charging)
    }
}

/** 整机电量快照。 */
data class BatteryState(
    val left: BatteryLevel = BatteryLevel.UNKNOWN,
    val right: BatteryLevel = BatteryLevel.UNKNOWN,
    val case: BatteryLevel = BatteryLevel.UNKNOWN,
) {
    val anyKnown: Boolean get() = left.known || right.known || case.known
}

/**
 * 降噪模式。四档 ANC 控件的第一档是 OFF，随后是系统 UI 上常见的
 * "通透 / 标准 / 深度" 三档，与 HyperOS 原生控件的档位语义保持一致。
 */
enum class NoiseMode(val code: Int, val labelZh: String) {
    OFF(0x00, "关闭"),
    TRANSPARENT(0x01, "通透模式"),
    NORMAL(0x02, "标准降噪"),
    DEEP(0x03, "深度降噪"),
    /** 部分白牌方案提供的额外档位：自适应/实验档 */
    ADAPTIVE(0x10, "自适应"),
    /** 抗风噪 */
    WIND(0x11, "抗风噪");

    companion object {
        /** 系统四档控件的顺序（UI 主控件用这个）。 */
        val SWITCHABLE: List<NoiseMode> = listOf(OFF, TRANSPARENT, NORMAL, DEEP)
        fun fromCode(code: Int): NoiseMode? = entries.firstOrNull { it.code == code }
    }
}

/** EQ 音效档位。label 跟随官方 App 的命名体系（逆向得出前先用通用命名）。 */
enum class EqMode(val code: Int, val label: String) {
    BALANCED(0x00, "均衡"),
    BASS(0x01, "重低音"),
    VOCAL(0x02, "人声"),
    TREBLE(0x03, "高音增强"),
    LIVE(0x04, "现场"),
    GAME(0x05, "游戏增强");

    companion object {
        fun fromCode(code: Int): EqMode = entries.firstOrNull { it.code == code } ?: BALANCED
    }
}

/** 音频编解码器（只读展示）。 */
enum class AudioCodec(val label: String) {
    SBC("SBC"), AAC("AAC"), LDAC("LDAC"), LHDC("LHDC"),
    APTX("aptX"), APTX_ADAPTIVE("aptX Adaptive"), UNKNOWN("未知")
}

/** SPP/BLE 通道状态。 */
enum class ConnectionState {
    /** 蓝牙适配器关闭 */
    BLUETOOTH_OFF,
    /** 未选择设备 */
    IDLE,
    /** 正在建立通道 */
    CONNECTING,
    /** 通道就绪，正在同步状态 */
    HANDSHAKING,
    /** 已就绪 */
    CONNECTED,
    /** 连接中断，正在自动重连 */
    RECONNECTING,
    FAILED,
}

/** 耳机身份信息（来自蓝牙栈 + 协议上报）。 */
data class DeviceInfo(
    val name: String,
    /** 蓝牙地址，形如 AA:BB:CC:DD:EE:FF */
    val address: String,
    val firmware: String? = null,
    val hardware: String? = null,
    val serial: String? = null,
    val codec: AudioCodec = AudioCodec.UNKNOWN,
)

/** 实时状态。UI 直接渲染这个对象。 */
data class PodState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val device: DeviceInfo? = null,
    val battery: BatteryState = BatteryState(),
    val noise: NoiseMode = NoiseMode.OFF,
    val eq: EqMode = EqMode.BALANCED,
    val gameMode: Boolean = false,
    val inEarDetection: Boolean = false,
    val dualConnection: Boolean = false,
    val windSuppression: Boolean = false,
    /** 当前生效的协议实现名，例如 "yscoco-v1" / "gaia-v4" / "raw-spp" */
    val protocolName: String? = null,
    /** 上一次成功通信的时间戳（毫秒） */
    val lastSeenAt: Long = 0L,
    /** 最近一条人类可读的诊断信息（UI 底部小字展示） */
    val lastMessage: String? = null,
) {
    val connected: Boolean
        get() = connection == ConnectionState.CONNECTED || connection == ConnectionState.HANDSHAKING
}

/**
 * 协议给出的"结果"：一组可应用的状态变更。
 * 用 delta 而不是完整快照，避免协议只上报单耳电量时覆盖另一只。
 */
sealed interface PodUpdate {
    data class Battery(val battery: BatteryState) : PodUpdate
    data class Noise(val mode: NoiseMode) : PodUpdate
    data class Eq(val mode: EqMode) : PodUpdate
    data class GameMode(val enabled: Boolean) : PodUpdate
    data class InEarDetection(val enabled: Boolean) : PodUpdate
    data class DualConnection(val enabled: Boolean) : PodUpdate
    data class WindSuppression(val enabled: Boolean) : PodUpdate
    data class Firmware(val version: String) : PodUpdate
    data class Codec(val codec: AudioCodec) : PodUpdate
    /** 协议层解析出的原始信息，仅用于调试面板 */
    data class Raw(val text: String) : PodUpdate
}

/** 控制命令。Transport 负责把它编码成字节发出去。 */
sealed interface PodCommand {
    data class SetNoise(val mode: NoiseMode) : PodCommand
    data class SetEq(val mode: EqMode) : PodCommand
    data class SetGameMode(val enabled: Boolean) : PodCommand
    data class SetInEarDetection(val enabled: Boolean) : PodCommand
    data class SetDualConnection(val enabled: Boolean) : PodCommand
    data class SetWindSuppression(val enabled: Boolean) : PodCommand
    /** 全量状态查询（连接后立即发一次） */
    data object QueryAll : PodCommand
    data object QueryBattery : PodCommand
    data object QueryFirmware : PodCommand
}

/** 把 PodUpdate 叠加到 PodState 上。集中在这里，保证 UI 与 core 语义一致。 */
fun PodState.apply(update: PodUpdate): PodState = when (update) {
    is PodUpdate.Battery -> copy(battery = update.battery)
    is PodUpdate.Noise -> copy(noise = update.mode)
    is PodUpdate.Eq -> copy(eq = update.mode)
    is PodUpdate.GameMode -> copy(gameMode = update.enabled)
    is PodUpdate.InEarDetection -> copy(inEarDetection = update.enabled)
    is PodUpdate.DualConnection -> copy(dualConnection = update.enabled)
    is PodUpdate.WindSuppression -> copy(windSuppression = update.enabled)
    is PodUpdate.Firmware -> copy(device = device?.copy(firmware = update.version))
    is PodUpdate.Codec -> copy(device = device?.copy(codec = update.codec))
    is PodUpdate.Raw -> copy(lastMessage = update.text)
}

fun Iterable<PodUpdate>.applyAllTo(state: PodState): PodState = fold(state) { acc, u -> acc.apply(u) }
