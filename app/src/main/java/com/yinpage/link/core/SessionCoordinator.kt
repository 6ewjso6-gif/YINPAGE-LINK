package com.yinpage.link.core

import com.yinpage.link.config.ConfigManager
import com.yinpage.link.protocol.BluetoothOffException
import com.yinpage.link.protocol.DeviceUnavailableException
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodCodec
import com.yinpage.link.protocol.PodCommand
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.PodTransportException
import com.yinpage.link.protocol.PodUpdate
import com.yinpage.link.protocol.ProbeableTransport
import com.yinpage.link.protocol.ServiceNotFoundException
import com.yinpage.link.protocol.TransportFrame
import com.yinpage.link.protocol.TransportIoException
import com.yinpage.link.protocol.TransportListener
import com.yinpage.link.protocol.TransportTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 握手活性看门狗信号：通道建立 + 握手帧发出后，等第一个到达的字节。
     * 若超时仍未收到任何数据，判定「连到了非控制通道（哑串口）」，自动换下一个候选。
     */
    @Volatile
    private var firstDataSignal: CompletableDeferred<Unit>? = null

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
     * v0.11 修订：**统一 SPP 优先，BLE 兜底**，不再按地址类型启发。
     *
     * 为什么撤销 v0.10 的「BLE 随机地址 → BLE 优先」：
     * 真机（YINPAGE Relink，7A:70:96:4E:48:14）实测 BLE GATT 服务枚举 10 秒超时未完成，
     * 而 SPP 通道能稳定建连——地址类型只是「启发」，BLE 枚举超时说明这条设备上 BLE
     * 链路很慢/不可用，BLE 优先只会白白消耗 15~20 秒超时。
     * 顺序固定为 RFCOMM → BLE：SPP 是全白牌 TWS 事实上的控制通道标准，
     * BLE GATT 仅当 SPP 完全失败时兜底。
     *
     * N3 修复：AUTO 恢复读取 `AppConfig.bleTransport`（默认 **false**，与
     * 「默认禁用所有 BLE 行为」的设计保持一致）——关闭时 AUTO 只走 SPP，
     * 不再无条件在 SPP 失败后去建 GATT、白耗 15~20 秒。同时删除了自始至终
     * 未被使用的 `address` 死参数。
     */
    private fun connectionPlan(kind: TransportKind): List<TransportKind> {
        return when (kind) {
            TransportKind.RFCOMM -> listOf(TransportKind.RFCOMM)
            TransportKind.BLE -> listOf(TransportKind.BLE)
            TransportKind.AUTO -> {
                // 配置尚未初始化时 ConfigManager.get() 会抛 IllegalStateException，
                // 这里兜底为「只用 SPP」，不让一次配置读取失败把连接整体打断。
                val bleEnabled = runCatching { ConfigManager.get().current.bleTransport }
                    .getOrDefault(false)
                if (bleEnabled) {
                    listOf(TransportKind.RFCOMM, TransportKind.BLE)
                } else {
                    listOf(TransportKind.RFCOMM)
                }
            }
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

        // N2 修复：活性信号必须在通道建立**之前**就绪。
        // 通道一建立，读循环就开始投递 onFrame；若设备在握手帧发出前主动推送
        // 电量/状态通知，早到的数据会因 firstDataSignal 仍为 null 而被丢弃，
        // 随后握手窗口内设备不再发数据 → 误判「哑通道」并断开一条正常连接。
        // 提前赋值即可彻底消除该竞态。
        val firstData = CompletableDeferred<Unit>()
        firstDataSignal = firstData

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
                // 通道活性：首个到达的字节就解锁握手看门狗（无论能否解析——
                // 设备回加密帧也算「通道选对了」，只有全程静默才判定连到了哑通道）。
                firstDataSignal?.takeIf { !it.isCompleted }?.complete(Unit)
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

        // ---- 1. 握手帧先构造好 ----
        // 它要在**每个候选通道**上重复发送，因此必须能在「建链 + 活性确认」
        // 的原子尝试单元里执行，不能再等 connect 返回后才构造。
        val handshake = try {
            codec.handshake()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            teardown("握手帧构造失败")
            return ConnectResult.Failure("协议握手帧构造失败：${describe(error)}")
        }

        /** 最近一次活性确认失败的原因，用于最终失败提示（不再只报"超时"）。 */
        var probeFailure: String? = null

        /**
         * 本次尝试的总预算起点。活性确认（等首帧）必须和建链共享这一份预算，
         * 否则「5 秒建链 + 4 秒等首帧」会突破对用户承诺的 15 秒上限。
         */
        val connectDeadlineNanos = System.nanoTime() + CONNECT_WATCHDOG_MS * 1_000_000L

        /**
         * 活性确认：在**当前已建立的候选**上发握手帧并等待首帧。
         * 返回 false = 该候选是哑通道，由传输层关掉并换下一个 UUID。
         *
         * 注：firstData 在通道建立**之前**就已就绪（见上），设备若在握手前
         * 主动推送数据，await() 会立即返回，不再消耗 4 秒窗口。
         */
        suspend fun probe(target: PodTransport): Boolean {
            for ((index, frame) in handshake.withIndex()) {
                if (frame.isEmpty()) continue
                try {
                    target.write(frame)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is MissingPermissionException) throw error
                    val mapped = mapError(error, kind)
                    probeFailure = "握手第 ${index + 1}/${handshake.size} 帧发送失败（${human(mapped, kind)}）"
                    return false
                }
            }

            if (generation.get() != myGeneration) {
                probeFailure = "连接已被新的连接请求取代"
                return false
            }

            // 无握手帧的协议（handshake 为空）不需要活性确认，直接通过。
            if (handshake.isEmpty()) return true

            // 活性窗口取「协议默认窗口」与「总预算剩余」的较小值：
            // 剩余不足时不再傻等满 4 秒，直接把预算耗尽的真实原因报出来。
            val remainingMs = (connectDeadlineNanos - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) {
                probeFailure =
                    "连接总预算（${CONNECT_WATCHDOG_MS / 1000} 秒）已耗尽，握手未完成"
                return false
            }
            val windowMs = minOf(HANDSHAKE_RESPONSE_WINDOW_MS, remainingMs)
            val responded = withTimeoutOrNull(windowMs) { firstData.await() } != null
            if (!responded) {
                probeFailure =
                    "通道已建立，但握手帧发出后 ${windowMs}ms 内无任何数据返回——" +
                        "大概率连到了非控制通道（哑串口）"
                return false
            }
            return true
        }

        // ---- 2. 建立通道 + 逐候选活性确认 ----
        // ProbeableTransport（RFCOMM）把「建链 + probe」放进 UUID 循环：
        // 某个候选连上却不应答，会自动关掉并试下一个候选；只有全部候选都失败
        // 才会切到计划里的下一种传输类型（BLE）。
        val connected = try {
            withTimeout(CONNECT_WATCHDOG_MS) {
                if (channel is ProbeableTransport) {
                    channel.connectWithProbe(listener) { target -> probe(target) }
                } else {
                    channel.connect(listener)
                    probe(channel)
                }
            }
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

        if (!connected) {
            teardown("握手后无数据响应")
            val detail = probeFailure
                ?: "通道已建立，但握手帧发出后 ${HANDSHAKE_RESPONSE_WINDOW_MS / 1000} 秒内无任何数据返回——" +
                    "大概率连到了非控制通道（哑串口）"
            val mapped = TransportTimeoutException(detail)
            return ConnectResult.Failure(human(mapped, kind), mapped)
        }

        if (generation.get() != myGeneration) {
            teardown("连接已被新请求取代")
            return ConnectResult.Failure("连接已被新的连接请求取代")
        }

        EventLog.debug(TAG, "${channel.name} 握手活性确认：已收到首帧数据")

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
        firstDataSignal = null
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

        /**
         * 握手活性窗口：通道建立 + 握手帧写出后，等待设备返回首个字节的最长时间。
         * 设备对 6 帧查询的响应通常在几百毫秒内；4 秒足以覆盖慢设备，
         * 同时避免「哑通道」把一次连接拖到 15 秒才暴露。
         */
        const val HANDSHAKE_RESPONSE_WINDOW_MS = 4_000L
    }
}
