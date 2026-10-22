package com.yinpage.link.protocol

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ============================================================================
 *  传输层抽象  ——  冻结契约（v1）
 * ============================================================================
 *  目前实现两条物理通道，二选一或并存：
 *   1. [RfcommTransport]  —— 经典蓝牙 SPP/RFCOMM（白牌 TWS 私有协议最常见形态）
 *   2. [BleGattTransport] —— BLE GATT 特征值读写（部分方案 / OTA 通道）
 *
 *  传输层只负责"把字节送到耳机 / 从耳机收字节"，不理解任何协议语义。
 *  因此它可以被替换、被 mock，协议层可以拿到纯字节流做单元测试。
 * ============================================================================
 */

/** 一条已解码的接收帧（传输层只做分帧与校验剥离，不做语义解析）。 */
data class TransportFrame(
    val bytes: Bytes,
    /** 接收时间戳（毫秒） */
    val at: Long = System.currentTimeMillis(),
)

interface TransportListener {
    /** 通道已就绪（RFCOMM socket connect 成功 / GATT 服务发现完成）。 */
    fun onTransportReady() {}
    /** 收到一帧数据。 */
    fun onFrame(frame: TransportFrame)
    /** 通道断开。 */
    fun onTransportClosed(reason: String)
    /** 底层日志，供调试面板显示。 */
    fun onTransportLog(message: String) {}
}

interface PodTransport {
    /** 用于 UI 展示的数据源名，例如 "RFCOMM" / "BLE"。 */
    val name: String

    /** 建立通道。挂起直到就绪或抛出 [PodTransportException]。 */
    suspend fun connect(listener: TransportListener)

    /** 发送一帧。挂起直到写入完成。 */
    suspend fun write(bytes: Bytes)

    /** 关闭通道，释放资源。必须可重复调用。 */
    fun close()

    /** 连接是否有效。 */
    val isConnected: Boolean

    /** 该通道要求的 MTU / 最大帧长，编码器据此分片。 */
    val maxFrameSize: Int
}

/**
 * 可选能力：一个传输实例内含**多个候选通道**（典型为 SPP 的多 UUID /
 * secure-insecure 组合），需要把「建链 + 握手 + 活性确认」当成一个原子尝试
 * 单元逐个试。
 *
 * 为什么需要：[PodTransport.connect] 一旦返回成功，上层就认为通道可用；
 * 但 SPP 的标准串口 UUID 可能只是"哑通道"（连得上、发得出、收不到）。
 * 若不在这里继续轮换，活性确认失败后只能整体切到别的传输类型（BLE），
 * 永远试不到下一个 UUID。
 */
interface ProbeableTransport : PodTransport {
    /**
     * 依次尝试候选通道，每建立一个就调用 [probe] 做活性确认：
     *  - [probe] 返回 true：保留该候选并返回 true（连接与读循环均已就绪）；
     *  - [probe] 返回 false：关闭该候选，继续下一个；
     *  - 所有候选都失败：抛 [PodTransportException]（带完整尝试记录）。
     */
    suspend fun connectWithProbe(
        listener: TransportListener,
        probe: suspend (PodTransport) -> Boolean,
    ): Boolean
}

// ------------------------------- 异常 -------------------------------

sealed class PodTransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** 蓝牙未开启。 */
class BluetoothOffException : PodTransportException("蓝牙未开启")

/** 缺少运行时权限。 */
class MissingPermissionException(detail: String) : PodTransportException("缺少权限：$detail")

/** 目标设备未配对 / 未在范围内。 */
class DeviceUnavailableException(detail: String, cause: Throwable? = null) :
    PodTransportException("设备不可用：$detail", cause)

/** SPP UUID 不存在 —— 说明这台耳机不走经典蓝牙私有协议。 */
class ServiceNotFoundException(detail: String) : PodTransportException("未发现服务通道：$detail")

/** 连接超时。 */
class TransportTimeoutException(detail: String) : PodTransportException("超时：$detail")

/** 写入失败 / 通道已断。 */
class TransportIoException(detail: String, cause: Throwable? = null) :
    PodTransportException("通道 IO 失败：$detail", cause)

// --------------------------- 协议编解码契约 ---------------------------

/**
 * 一个"耳机私有协议"的完整实现。新增任何品牌/方案只需实现本接口，
 * 然后注册到 [ProtocolRegistry]，App 会按探测顺序自动选用。
 */
interface PodCodec {
    /** 协议标识，用于日志与状态展示，例如 "yscoco-v1"。 */
    val id: String

    /** 人类可读名称，例如 "音贝奇 YSCOCO 通用协议"。 */
    val displayName: String

    /**
     * F17：显式优先级，值越大越优先；多个 codec 同时匹配一台设备时据此排序。
     * 默认 0 —— 同优先级时保持**注册顺序**（[ProtocolRegistry.register] 的追加顺序），
     * 因此"新增品牌只需追加一行"的行为是可预期的。
     */
    val priority: Int get() = 0

    /** 通道就绪后需要立刻发送的握手/同步帧；返回空列表表示无需握手。 */
    fun handshake(): List<Bytes>

    /** 把高层命令编码为字节帧。返回空列表表示本协议不支持该命令。 */
    fun encode(command: PodCommand): List<Bytes>

    /**
     * 字节流喂给解码器，返回解析出的状态增量和"是否需要回包"。
     * 分帧逻辑在解码器内部完成（不同协议的帧头/长度字段不同）。
     */
    fun decode(chunk: Bytes, sink: (PodUpdate) -> Unit)

    /**
     * 通道复位：新会话开始前调用，清空分帧缓冲、分片重组与发送序号。
     *
     * ⚠️ codec 在 Application 里注册为**单例**、所有连接共用。若不复位，
     * 上一次连接残留的半包字节 / 未凑齐的分片 / 发送序号会污染新会话解码。
     * 默认空实现，需要状态的 codec 应覆写。
     */
    fun reset() {}

    /**
     * 判断一段接收数据是否像本协议的帧 —— 用于多协议自动探测。
     * 返回 0.0 表示完全不像，1.0 表示高度确定。
     */
    fun confidence(chunk: Bytes): Double = 0.0

    /**
     * 判断某台蓝牙设备是否可能是本协议的目标，用于连接前的探测。
     * 可依据设备名、厂商 ID（SIG）、服务 UUID 列表判断。
     */
    fun matchesDevice(deviceName: String?, serviceUuids: List<String>): Boolean = true
}

/** 协议注册表：App 启动时注册所有已知 codec，按优先级探测。 */
object ProtocolRegistry {
    private val codecs = mutableListOf<PodCodec>()
    private val lock = Any()

    fun register(codec: PodCodec) {
        synchronized(lock) {
            codecs.removeAll { it.id == codec.id }
            codecs.add(codec)
        }
    }

    fun all(): List<PodCodec> = synchronized(lock) { codecs.toList() }

    fun byId(id: String): PodCodec? = synchronized(lock) { codecs.firstOrNull { it.id == id } }

    /**
     * 按设备信息挑选首选协议：先用 matchesDevice 过滤，再按其**显式优先级**
     * [PodCodec.priority] 排序（同优先级保持注册顺序）。
     *
     * F17 修复：原实现是 `sortedByDescending { it.id }`（按 id 字典序倒序），
     * 与注释承诺的语义不符 —— 一旦注册多个品牌，协议选择会退化为不可预期的字母序。
     * 若都不匹配则返回全部，让运行时按实际收包自动切换。
     */
    fun candidatesFor(deviceName: String?, serviceUuids: List<String>): List<PodCodec> {
        val list = all()
        val matched = list.filter { runCatching { it.matchesDevice(deviceName, serviceUuids) }.getOrDefault(false) }
        return (matched.ifEmpty { list }).sortedByDescending { it.priority }
    }
}
