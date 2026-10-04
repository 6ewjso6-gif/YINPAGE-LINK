package com.yinpage.link.core

import com.yinpage.link.protocol.BluetoothOffException
import com.yinpage.link.protocol.DeviceUnavailableException
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodCodec
import com.yinpage.link.protocol.PodCommand
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.PodTransportException
import com.yinpage.link.protocol.PodUpdate
import com.yinpage.link.protocol.ServiceNotFoundException
import com.yinpage.link.protocol.TransportFrame
import com.yinpage.link.protocol.TransportIoException
import com.yinpage.link.protocol.TransportListener
import com.yinpage.link.protocol.TransportTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * ============================================================================
 *  会话协调器 —— [PodCoordinator] 的默认实现
 * ============================================================================
 *  职责边界：
 *   - 传输层（[PodTransport]）只负责「把字节搬过去 / 搬回来」；读循环由传输
 *     实现自己持有（[PodTransport] 契约里没有 read()，只有 listener.onFrame），
 *     每读到一段字节就通过 [TransportListener.onFrame] 推上来；
 *   - 本类负责选择通道、驱动握手、把收到的字节喂给 [PodCodec]，
 *     并把所有底层异常翻译成人话的 [ConnectResult.Failure]，绝不外抛。
 *
 *  线程模型：
 *   - connect / send 共用一把 [Mutex] 串行化，避免「边连边发」；
 *   - disconnect 不挂起、可在任意线程调用、可重复调用；
 *   - 每次会话有独立代次号 generation，旧会话的迟到回调一律丢弃。
 * ============================================================================
 */
class SessionCoordinator(
    private val factory: TransportFactory,
) : PodCoordinator {

    /** connect / send 的串行闸门。 */
    private val gate = Mutex()

    /** 会话代次：任何一次 teardown / 新连接都会 +1，使旧回调失效。 */
    private val generation = AtomicLong(0)

    @Volatile
    private var activeTransport: PodTransport? = null

    @Volatile
    private var activeCodec: PodCodec? = null

    @Volatile
    private var ready: Boolean = false

    override val activeTransportName: String?
        get() = activeTransport?.name

    override val activeCodecId: String?
        get() = activeCodec?.id

    /** 就绪 = 通道已建立且握手帧已全部写出；通道断开时由 [TransportListener.onTransportClosed] 复位。 */
    override val isReady: Boolean
        get() = ready

    // ------------------------------------------------------------------ 连接

    override suspend fun connect(
        address: String,
        deviceName: String?,
        kind: TransportKind,
        codec: PodCodec,
        onUpdate: (PodUpdate) -> Unit,
        onLog: (String) -> Unit,
    ): ConnectResult = gate.withLock {
        connectLocked(address, deviceName, kind, codec, onUpdate, onLog)
    }

    private suspend fun connectLocked(
        address: String,
        deviceName: String?,
        kind: TransportKind,
        codec: PodCodec,
        onUpdate: (PodUpdate) -> Unit,
        onLog: (String) -> Unit,
    ): ConnectResult {
        val plan = connectionPlan(kind)
        val target = deviceName?.takeIf { it.isNotBlank() } ?: address
        onLog("连接计划：${plan.joinToString(" → ") { it.label }}，目标=$target，协议=${codec.id}")
        EventLog.info(
            TAG,
            "开始连接：addr=$address name=${deviceName ?: "-"} " +
                "计划=${plan.joinToString("/") { it.name }} 协议=${codec.id}",
        )

        val failures = mutableListOf<String>()
        var lastCause: PodTransportException? = null

        for (candidate in plan) {
            when (val result = attempt(candidate, address, codec, onUpdate, onLog)) {
                is ConnectResult.Success -> return result
                is ConnectResult.Failure -> {
                    failures += "${candidate.label}：${result.reason}"
                    result.cause?.let { lastCause = it }
                    onLog("${candidate.label} 失败：${result.reason}")
                    EventLog.info(TAG, "${candidate.label} 失败：${result.reason}")
                }
            }
        }

        val reason = when (failures.size) {
            0 -> "没有可用的传输通道"
            1 -> failures.first()
            else -> "所有通道均失败（${failures.joinToString("；")}）"
        }
        return ConnectResult.Failure(reason, lastCause)
    }

    /**
     * 连接通道计划。
     *
     * ⚠️ **AUTO 默认只走经典蓝牙 SPP，不回退 BLE**。
     * 原因：实测反馈"系统蓝牙与应用不能同时使用" —— 对已由系统连着的耳机
     * 再调 `connectGatt` 会争用 GATT 客户端资源，导致系统侧连接异常。
     * 这只耳机的控制通道本来就是 SPP，故不再自动回退；
     * 用户确需 BLE 时可在设置里显式开启。
     */
    private fun connectionPlan(kind: TransportKind): List<TransportKind> {
        val bleAllowed = runCatching {
            com.yinpage.link.config.ConfigManager.initialized &&
                com.yinpage.link.config.ConfigManager.get().current.bleTransport
        }.getOrDefault(false)
        return when (kind) {
            TransportKind.RFCOMM -> listOf(TransportKind.RFCOMM)
            TransportKind.BLE -> listOf(TransportKind.BLE)
            TransportKind.AUTO ->
                if (bleAllowed) listOf(TransportKind.RFCOMM, TransportKind.BLE)
                else listOf(TransportKind.RFCOMM)
        }
    }

    /**
     * 一次完整的通道尝试：建通道 → 发握手 → 标记就绪。
     * 所有异常在这里翻译成 [ConnectResult.Failure] 返回；只有协程取消
     * [CancellationException] 会继续向上抛（结构化并发的硬约束，AppState 也已
     * 用 runCatching 兜住）。
     */
    private suspend fun attempt(
        kind: TransportKind,
        address: String,
        codec: PodCodec,
        onUpdate: (PodUpdate) -> Unit,
        onLog: (String) -> Unit,
    ): ConnectResult {
        teardown("准备新连接")
        val myGeneration = generation.incrementAndGet()

        val channel = try {
            factory.create(kind, address)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val mapped = TransportIoException("无法创建 ${kind.label} 通道：${describe(error)}", error)
            return ConnectResult.Failure(human(mapped, kind), mapped)
        }

        val listener = object : TransportListener {
            override fun onTransportReady() {
                if (generation.get() != myGeneration) return
                EventLog.info(TAG, "${channel.name} 通道就绪")
            }

            override fun onFrame(frame: TransportFrame) {
                if (generation.get() != myGeneration) return
                val decoder = this@SessionCoordinator.activeCodec ?: return
                try {
                    // 顺序敏感：RFCOMM 在 IO 读线程上投递，BLE 在主线程上投递，
                    // 两者都是单线程串行到达，因此这里直接解码可保证流式分帧顺序。
                    decoder.decode(frame.bytes) { update -> onUpdate(update) }
                } catch (error: Throwable) {
                    EventLog.info(TAG, "解码失败：${describe(error)}")
                    onLog("解码失败：${describe(error)}")
                }
            }

            override fun onTransportClosed(reason: String) {
                if (generation.get() != myGeneration) return
                ready = false
                EventLog.info(TAG, "${channel.name} 通道断开：$reason")
                onLog("${channel.name} 通道断开：$reason")
            }

            override fun onTransportLog(message: String) {
                if (generation.get() != myGeneration) return
                EventLog.debug(TAG, message)
            }
        }

        this.activeCodec = codec
        this.activeTransport = channel
        this.ready = false

        // ---- 1. 建立通道 ----
        try {
            withTimeout(CONNECT_WATCHDOG_MS) { channel.connect(listener) }
        } catch (timeout: TimeoutCancellationException) {
            teardown("连接超时")
            val mapped = TransportTimeoutException("${kind.label} 连接超过 ${CONNECT_WATCHDOG_MS / 1000} 秒未完成")
            return ConnectResult.Failure(human(mapped, kind), mapped)
        } catch (cancel: CancellationException) {
            teardown("连接被取消")
            throw cancel
        } catch (error: Throwable) {
            val mapped = mapError(error, kind)
            teardown("连接失败")
            return ConnectResult.Failure(human(mapped, kind), mapped)
        }

        if (generation.get() != myGeneration) {
            teardown("连接已被新请求取代")
            return ConnectResult.Failure("连接已被新的连接请求取代")
        }

        // ---- 2. 发送握手帧 ----
        val handshake = try {
            codec.handshake()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            teardown("握手帧构造失败")
            return ConnectResult.Failure("协议握手帧构造失败：${describe(error)}")
        }

        for ((index, frame) in handshake.withIndex()) {
            if (frame.isEmpty()) continue
            try {
                channel.write(frame)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val mapped = mapError(error, kind)
                teardown("握手发送失败")
                val reason = "握手第 ${index + 1}/${handshake.size} 帧发送失败（${human(mapped, kind)}）"
                return ConnectResult.Failure(reason, mapped)
            }
        }

        if (generation.get() != myGeneration) {
            teardown("连接已被新请求取代")
            return ConnectResult.Failure("连接已被新的连接请求取代")
        }

        // ---- 3. 标记就绪 ----
        ready = true
        EventLog.info(TAG, "会话就绪：通道=${channel.name} 协议=${codec.id} 握手帧=${handshake.size}")
        onLog("会话就绪：${channel.name} + ${codec.id}")
        return ConnectResult.Success
    }

    // ------------------------------------------------------------------ 发送

    override suspend fun send(command: PodCommand): Boolean = gate.withLock {
        val transport = activeTransport ?: return@withLock false
        val encoder = activeCodec ?: return@withLock false
        if (!ready || !transport.isConnected) {
            EventLog.debug(TAG, "未就绪，丢弃命令 $command")
            return@withLock false
        }

        val frames = try {
            encoder.encode(command)
        } catch (error: Throwable) {
            EventLog.info(TAG, "命令编码失败：${describe(error)}")
            return@withLock false
        }
        if (frames.isEmpty()) {
            EventLog.debug(TAG, "协议 ${encoder.id} 不支持 $command")
            return@withLock false
        }

        for (frame in frames) {
            if (frame.isEmpty()) continue
            try {
                transport.write(frame)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                EventLog.info(TAG, "发送失败（${transport.name}）：${describe(error)}")
                return@withLock false
            }
        }
        EventLog.debug(TAG, "$command → ${frames.size} 帧已写出（${transport.name}）")
        true
    }

    // ------------------------------------------------------------------ 断开

    override fun disconnect() {
        val previous = activeTransport?.name
        teardown("主动断开")
        if (previous != null) {
            EventLog.info(TAG, "已断开并释放通道：$previous")
        } else {
            EventLog.debug(TAG, "disconnect()：当前没有活动通道")
        }
    }

    /**
     * 释放会话：先让旧回调失效，再关闭通道，最后清空状态。
     * 顺序很重要 —— 先 bump 代次，迟到的 onTransportClosed 就不会再打回 UI。
     * 可重复调用（无通道时是空操作）。
     */
    private fun teardown(reason: String) {
        generation.incrementAndGet()
        ready = false
        val previousCodec = activeCodec
        val previous = activeTransport
        activeTransport = null
        activeCodec = null
        // 清理解码器跨会话残留：codec 是单例，buffer/partial/seq 不清理会污染新会话
        runCatching { previousCodec?.reset() }
            .onFailure { EventLog.info(TAG, "codec.reset() 失败：${describe(it)}") }
        if (previous != null) {
            try {
                previous.close()
            } catch (error: Throwable) {
                EventLog.info(TAG, "关闭通道时出错（$reason）：${describe(error)}")
            }
        }
    }

    // ------------------------------------------------------------------ 错误映射

    private fun mapError(error: Throwable, kind: TransportKind): PodTransportException = when (error) {
        is PodTransportException -> error
        is SecurityException -> MissingPermissionException(error.message ?: "BLUETOOTH_CONNECT")
        is IOException -> TransportIoException("${kind.label} IO 失败：${describe(error)}", error)
        else -> TransportIoException("${kind.label} 连接异常：${describe(error)}", error)
    }

    /** 把底层异常翻译成 UI 能直接展示的一句话。 */
    private fun human(error: PodTransportException, kind: TransportKind): String = when (error) {
        is BluetoothOffException -> "蓝牙未开启，请先打开系统蓝牙"
        is MissingPermissionException -> "${error.message.orEmpty()}（请在系统设置中授予「附近的设备」权限）"
        is DeviceUnavailableException -> error.message.orEmpty()
        is ServiceNotFoundException -> "${kind.label}：${error.message.orEmpty()}"
        is TransportTimeoutException -> "${kind.label}：${error.message.orEmpty()}"
        is TransportIoException -> "${kind.label}：${error.message.orEmpty()}"
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val TAG = "会话"

        /** 协调器级看门狗：比传输层自身的 15 秒超时更宽松，只兜底「完全不返回」的实现。 */
        const val CONNECT_WATCHDOG_MS = 20_000L
    }
}
