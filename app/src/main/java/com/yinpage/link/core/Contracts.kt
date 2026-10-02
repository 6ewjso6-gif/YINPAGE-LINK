package com.yinpage.link.core

import com.yinpage.link.protocol.EqMode
import com.yinpage.link.protocol.NoiseMode
import com.yinpage.link.protocol.PodCodec
import com.yinpage.link.protocol.PodCommand
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.PodTransportException
import com.yinpage.link.protocol.PodUpdate

/** 传输通道类型。 */
enum class TransportKind(val label: String) {
    RFCOMM("经典蓝牙 SPP"),
    BLE("低功耗 BLE"),
    AUTO("自动探测");

    companion object {
        fun fromName(name: String?): TransportKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

/** 连接的注入工厂 —— 让 AppState 可以在测试中替换为假实现。 */
fun interface TransportFactory {
    fun create(kind: TransportKind, address: String): PodTransport
}

/**
 * ============================================================================
 *  连接协调器契约  ——  冻结契约（v1）
 * ============================================================================
 *  AppState 只依赖本接口：不关心底层是 RFCOMM 还是 BLE，也不关心品牌协议。
 *  传输实现者实现 [PodTransport]，协议实现者实现 [PodCodec]，
 *  协调器实现负责把两者串起来并维护连接状态机。
 * ============================================================================
 */
interface PodCoordinator {
    /** 当前活动通道名（"RFCOMM"/"BLE"），未连接为 null。 */
    val activeTransportName: String?

    /** 当前活动协议 id，未连接为 null。 */
    val activeCodecId: String?

    /** 通道是否已就绪且握手完成。 */
    val isReady: Boolean

    /**
     * 连接并完成握手。实现方必须把所有异常转换成 [ConnectResult.Failure]，
     * 不允许把异常抛给 UI。
     */
    suspend fun connect(
        address: String,
        deviceName: String?,
        kind: TransportKind,
        codec: PodCodec,
        onUpdate: (PodUpdate) -> Unit,
        onLog: (String) -> Unit,
    ): ConnectResult

    /** 断开并释放资源，可重复调用。 */
    fun disconnect()

    /** 发送一条高层命令；未就绪时返回 false。 */
    suspend fun send(command: PodCommand): Boolean
}

/** 连接结果，UI 据此给出人话提示。 */
sealed interface ConnectResult {
    data object Success : ConnectResult
    data class Failure(val reason: String, val cause: PodTransportException? = null) : ConnectResult
}

/** 便捷标签。 */
object Labels {
    fun noise(mode: NoiseMode): String = mode.labelZh
    fun eq(mode: EqMode): String = mode.label
}
