package com.yinpage.link.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.yinpage.link.config.ConfigManager
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog
import com.yinpage.link.protocol.BluetoothOffException
import com.yinpage.link.protocol.Bytes
import com.yinpage.link.protocol.DeviceUnavailableException
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.ProbeableTransport
import com.yinpage.link.protocol.ServiceNotFoundException
import com.yinpage.link.protocol.TransportFrame
import com.yinpage.link.protocol.TransportIoException
import com.yinpage.link.protocol.TransportListener
import com.yinpage.link.protocol.TransportTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 *  经典蓝牙 SPP / RFCOMM 传输 —— 免 root，只用公开 API
 * ============================================================================
 *  白牌 TWS 的私有协议绝大多数跑在这条通道上（参见 doc/yinpage-tws-research.md
 *  推测 C/D）：标准串口 UUID `00001101-...`，双向流式字节。
 *
 *  - 连接：先 cancelDiscovery，再按 [AppState.SPP_UUID_CANDIDATES] 逐个
 *    secure / insecure 组合尝试；全部失败抛 [ServiceNotFoundException]。
 *  - 读循环：本类自己持有（[PodTransport] 契约没有 read()，只有 listener.onFrame）。
 *    设置 soTimeout 后 read() 会周期性抛 [SocketTimeoutException]，
 *    借此检查协程取消 / 关闭标志，从而让 close() 能及时生效。
 *  - 写：outputStream.write + flush，串行化在 [writeGate] 内。
 *
 *  ⚠️ 关于隐藏 API（S6 如实说明）：本类**不使用** createRfcommSocket 之类的私有连接
 *  手段，建链全程走公开 API。唯一的例外是 `connect()` 里对 `BluetoothSocket.setSoTimeout`
 *  的反射调用 —— 该方法是 @hide，在 minSdk=27 下没有公开替代品；它只影响读超时，
 *  调用失败会退化为阻塞读（功能仍可用），且已被 runCatching 兜住。
 * ============================================================================
 */
class RfcommTransport(
    private val address: String,
) : ProbeableTransport {

    override val name: String = "RFCOMM"

    /** 流式通道：分帧由协议解码器负责，因此不限制帧长。 */
    override val maxFrameSize: Int = Int.MAX_VALUE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeGate = Mutex()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var closeNotified: Boolean = false

    @Volatile
    private var listener: TransportListener? = null

    private var readJob: Job? = null

    /** 读循环代次：只有最新一代的读循环才有资格上报断开。 */
    @Volatile
    private var readGeneration: Int = 0

    override val isConnected: Boolean
        get() = connected && !closed && socket != null

    // ------------------------------------------------------------------ 连接

    override suspend fun connect(listener: TransportListener) {
        // 没有活性回调 = "连上即成功"，与其他传输的语义保持一致。
        if (!connectWithProbe(listener) { true }) {
            throw ServiceNotFoundException("所有 SPP UUID 候选均未通过活性确认")
        }
    }

    /**
     * 逐个候选执行「建链 → 活性确认」，**活性确认失败时继续下一个 UUID**。
     *
     * v0.14 修复：此前 `openSocket` 只在 connect 抛错时才换 UUID；"连上但无响应"
     * 的判定在协调器的 4 秒看门狗里，失败后直接切到 BLE，标准串口这种哑通道会
     * 让厂商控制 UUID（0000A100-…CKCTRL）永远没有尝试机会。
     *
     * 预算：所有候选共享 [CONNECT_TIMEOUT_MS] 总预算，单个候选的 connect 再额外
     * 限制为 [PER_CANDIDATE_CONNECT_MS]，避免第一个候选卡死吃满全部预算。
     */
    override suspend fun connectWithProbe(
        listener: TransportListener,
        probe: suspend (PodTransport) -> Boolean,
    ): Boolean {
        this.listener = listener
        this.closed = false
        this.closeNotified = false
        this.connected = false

        val context = TransportEnv.context()
            ?: throw DeviceUnavailableException("应用上下文不可用")
        val adapter = TransportEnv.adapter()
            ?: throw DeviceUnavailableException("本机不支持蓝牙")
        if (!adapter.isEnabled) throw BluetoothOffException()
        TransportEnv.requireConnectPermission(context)

        runCatching { adapter.cancelDiscovery() }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            throw DeviceUnavailableException("非法蓝牙地址 $address", error)
        }

        EventLog.info(TAG, "开始连接 $address（bondState=${bondText(device)}）")

        val uuids = AppState.SPP_UUID_CANDIDATES.mapNotNull { text ->
            runCatching { UUID.fromString(text) }.getOrNull()
        }.let { candidates ->
            // 上次握手成功的 UUID 置顶：固定顺序下，若排在前面但不应答的候选
            // 每次都要先白耗一次建链（最长 5 秒）。记住成功项即可一次命中。
            val lastGood = runCatching { ConfigManager.get().current.lastSppUuid }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (lastGood == null) {
                candidates
            } else {
                // sortedBy 是稳定排序，其余候选的相对顺序保持不变
                candidates.sortedBy { if (it.toString().equals(lastGood, ignoreCase = true)) 0 else 1 }
            }
        }
        val attempts = mutableListOf<String>()
        var lastError = "无"
        val deadlineNanos = System.nanoTime() + CONNECT_TIMEOUT_MS * 1_000_000L

        return withContext(Dispatchers.IO) {
            // issue #3-2：按「方式」分两轮扫描，而不是每个 UUID 连试 secure+insecure。
            // 6 个 UUID × 2 种方式共享 15 秒预算，单候选最坏 5s 建链 + 4s 活性；
            // 若内层是方式，两个哑通道就能吃光预算，靠后的 UUID 永远试不到。
            // 外层先扫 insecure（官方 `b/b.java` 用的就是 createInsecureRfcommSocket…），
            // 全部失败再扫 secure —— 预算利用率翻倍，且与官方行为一致。
            for (insecure in listOf(true, false)) {
                for (uuid in uuids) {
                    if (closed) throw TransportIoException("通道已关闭")

                    val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                    if (remainingMs <= 0L) {
                        throw TransportTimeoutException(
                            "RFCOMM 连接 ${CONNECT_TIMEOUT_MS / 1000} 秒总预算已耗尽（$device）"
                        )
                    }

                    val label =
                        "${uuid.toString().substring(0, 8)}${if (insecure) "/insecure" else "/secure"}"
                    val sliceMs = minOf(remainingMs, PER_CANDIDATE_CONNECT_MS)

                    val candidate = openCandidate(device, uuid, insecure, sliceMs, label, attempts)
                        ?: continue

                    if (closed) {
                        runCatching { candidate.close() }
                        throw TransportIoException("通道已关闭")
                    }

                    // BluetoothSocket.setSoTimeout 属于 @hide API，SDK 存根里不可见，
                    // 因此用反射调用；失败不影响连接（只是读循环改为阻塞式）。
                    runCatching {
                        candidate.javaClass
                            .getMethod("setSoTimeout", Int::class.javaPrimitiveType)
                            .invoke(candidate, SO_TIMEOUT_MS)
                    }.onFailure { e ->
                        EventLog.debug(TAG, "setSoTimeout 反射调用失败（忽略）：${e.javaClass.simpleName} ${e.message}")
                    }

                    // 先挂上 socket 并启动读循环：probe 要写握手帧、并等待首帧到达。
                    socket = candidate
                    connected = true
                    startReadLoop(candidate)

                    // 总预算检查：活性确认（握手等待窗口）也要花时间，
                    // 若建链后预算已耗尽就不该再开始 probe，否则整体必然超时。
                    if ((deadlineNanos - System.nanoTime()) / 1_000_000L <= 0L) {
                        discardCandidate(candidate)
                        throw TransportTimeoutException(
                            "RFCOMM 连接 ${CONNECT_TIMEOUT_MS / 1000} 秒总预算已耗尽（$device）"
                        )
                    }

                    val verified = try {
                        probe(this@RfcommTransport)
                    } catch (cancel: CancellationException) {
                        discardCandidate(candidate)
                        throw cancel
                    } catch (error: Throwable) {
                        EventLog.info(TAG, "$label 活性确认异常：${describe(error)}")
                        false
                    }

                    if (closed) {
                        discardCandidate(candidate)
                        throw TransportIoException("通道在活性确认期间被关闭")
                    }

                    if (verified) {
                        EventLog.info(
                            TAG,
                            "SPP 通道建立成功：$label（soTimeout=${SO_TIMEOUT_MS}ms，最大帧长=不限）",
                        )
                        // 记住这条 UUID，下次连接直接置顶
                        runCatching { ConfigManager.get().rememberSppUuid(uuid.toString()) }
                        listener.onTransportReady()
                        return@withContext true
                    }

                    // 连上却没人应答 → 哑通道：关掉换下一个 UUID，
                    // 而不是直接放弃 SPP 去建 BLE。
                    discardCandidate(candidate)
                    attempts += "$label:握手无响应"
                    lastError = "通道已建立但握手后无数据返回（疑似哑通道）"
                    EventLog.info(TAG, "$label 握手无响应，已关闭并尝试下一个候选")
                }
            }

            throw ServiceNotFoundException(
                "已尝试 ${attempts.size} 种 SPP UUID/方式（${attempts.joinToString("，")}），最后错误=$lastError"
            )
        }
    }

    /**
     * 打开单个候选 socket（secure / insecure），最多等待 [sliceMs]。
     *
     * 失败返回 null 并把原因记入 [attempts]；权限异常直接抛出（不能吞）。
     *
     * 阻塞式 connect 无法被协程取消：withTimeout 超时只是抛异常，native 的
     * connect 会继续跑、最终把 socket 返回给一个已死的协程，且没人 close →
     * 反复连接（耳机关机是常见场景）持续泄漏 FD。因此用独立线程 +
     * `join(切片预算)`，超时后 close() 打断 native connect。
     *
     * N5：权限异常槽每个候选独立（局部 AtomicReference），避免上一轮
     * 「已超时被 close」的线程稍后才抛 SecurityException 并误报给下一个候选。
     */
    private fun openCandidate(
        device: BluetoothDevice,
        uuid: UUID,
        insecure: Boolean,
        sliceMs: Long,
        label: String,
        attempts: MutableList<String>,
    ): BluetoothSocket? {
        val candidate = try {
            if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
            else device.createRfcommSocketToServiceRecord(uuid)
        } catch (error: SecurityException) {
            throw MissingPermissionException("BLUETOOTH_CONNECT")
        } catch (error: IOException) {
            attempts += "$label:建socket失败"
            // 用 info 级别：这些是用户排查"功能不生效"的第一手信息，
            // 不应被 debug 开关隐藏
            EventLog.info(TAG, "$label 创建 socket 失败：${describe(error)}")
            return null
        }

        if (closed) {
            runCatching { candidate.close() }
            throw TransportIoException("通道已关闭")
        }

        val permRef = AtomicReference<MissingPermissionException?>(null)
        val connectWorker = Thread(
            {
                runCatching { candidate.connect() }.onFailure { connectError ->
                    if (connectError is SecurityException) {
                        // 权限异常要冒泡，不能吞掉
                        permRef.set(MissingPermissionException("BLUETOOTH_CONNECT"))
                        EventLog.info(TAG, "SPP 连接被权限拦截：${connectError.message}")
                    }
                }
            },
            "yinpage-rfcomm-connect",
        ).apply { isDaemon = true }
        connectWorker.start()
        connectWorker.join(sliceMs)
        if (connectWorker.isAlive) {
            runCatching { candidate.close() }
            attempts += "$label:超时"
            EventLog.info(TAG, "$label 连接超时（${sliceMs}ms，已 close 打断）")
            return null
        }
        permRef.get()?.let { failure ->
            runCatching { candidate.close() }
            throw failure
        }
        if (candidate.isConnected) return candidate
        // 连接失败但未抛异常（isConnected=false）
        attempts += "$label:连接失败"
        EventLog.info(TAG, "$label 连接失败（isConnected=false）")
        runCatching { candidate.close() }
        return null
    }

    /**
     * 丢弃一个未通过活性确认的候选：先作废读循环代次，避免 socket.close()
     * 触发读异常后被误报成"通道断开"（上层会以为真通道掉了）。
     */
    private fun discardCandidate(target: BluetoothSocket) {
        readGeneration++
        readJob?.cancel()
        readJob = null
        connected = false
        socket = null
        runCatching { target.close() }
    }

    // ------------------------------------------------------------------ 读循环

    private fun startReadLoop(target: BluetoothSocket) {
        readJob?.cancel()
        val token = ++readGeneration
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var exitReason = "对端关闭了连接"
            try {
                val input = target.inputStream
                while (isActive && !closed) {
                    val read = try {
                        input.read(buffer)
                    } catch (timeout: SocketTimeoutException) {
                        // soTimeout 到点：回到循环顶部检查取消 / 关闭标志
                        continue
                    }
                    when {
                        read < 0 -> {
                            exitReason = "对端关闭了连接（EOF）"
                            break
                        }
                        read == 0 -> Unit
                        else -> {
                            // issue #3-6：候选被 discard 后，旧读线程可能仍阻塞在
                            // input.read()（soTimeout 到期才返回）。若它读到残留字节，
                            // 会把下一个候选的 probe 窗口误判成"有响应"。
                            // readGeneration 已在 discardCandidate 里 +1，这里按 token
                            // 校验，非当前读循环一律丢弃。
                            if (readGeneration != token) continue
                            val data = IntArray(read) { buffer[it].toInt() and 0xFF }
                            EventLog.bytes(TAG, "RX", data)
                            listener?.onFrame(TransportFrame(data))
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: IOException) {
                exitReason = "读取失败：${describe(error)}"
            } catch (error: Throwable) {
                exitReason = "读取异常：${describe(error)}"
            } finally {
                // 只有「仍是当前读循环」时才上报断开，避免被新连接取代的旧循环误报。
                if (readGeneration == token) notifyClosed(exitReason)
            }
        }
    }

    /** 只在「非主动关闭」时通知上层一次。 */
    private fun notifyClosed(reason: String) {
        if (closed || closeNotified) return
        closeNotified = true
        connected = false
        EventLog.info(TAG, "通道关闭：$reason")
        listener?.onTransportClosed(reason)
    }

    // ------------------------------------------------------------------ 写

    override suspend fun write(bytes: Bytes) {
        if (bytes.isEmpty()) return
        val target = socket
        if (target == null || !connected || closed) throw TransportIoException("RFCOMM 通道未就绪")

        val payload = ByteArray(bytes.size) { bytes[it].toByte() }
        EventLog.bytes(TAG, "TX", bytes)

        writeGate.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val output = target.outputStream
                    output.write(payload)
                    output.flush()
                } catch (error: IOException) {
                    connected = false
                    val detail = "写入失败：${describe(error)}"
                    EventLog.info(TAG, detail)
                    notifyClosed(detail)
                    throw TransportIoException(detail, error)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 关闭

    override fun close() {
        val hadChannel = socket != null || connected
        closed = true
        closeNotified = true
        connected = false
        readJob?.cancel()
        readJob = null
        val previous = socket
        socket = null
        if (previous != null) {
            runCatching { previous.close() }
                .onFailure { EventLog.debug(TAG, "socket.close() 失败：${describe(it)}") }
        }
        if (hadChannel) EventLog.info(TAG, "已释放 RFCOMM 通道")
    }

    // ------------------------------------------------------------------ 工具

    private fun bondText(device: BluetoothDevice): String = runCatching {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
            else -> "BOND_NONE"
        }
    }.getOrDefault("unknown")

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val TAG = "RFCOMM"

        /** 建链超时（含 SDP 查询），与任务约定一致：15 秒（所有候选共享）。 */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /**
         * 单个候选建链的时间上限。为什么要单独限时：6 个 UUID × 2 种方式，
         * 若第一个候选阻塞到总预算耗尽，后面的候选（包括厂商控制 UUID）
         * 就永远没有尝试机会——这正是 v0.12 报告里"SDP 只有哑串口"的后果。
         */
        const val PER_CANDIDATE_CONNECT_MS = 5_000L

        /** read() 的阻塞上限，用于让读循环能周期性检查取消 / 关闭。 */
        const val SO_TIMEOUT_MS = 700

        const val READ_BUFFER_SIZE = 512
    }
}
