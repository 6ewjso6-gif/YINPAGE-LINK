package com.yinpage.link.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog
import com.yinpage.link.protocol.BluetoothOffException
import com.yinpage.link.protocol.Bytes
import com.yinpage.link.protocol.DeviceUnavailableException
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.PodTransportException
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

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
 *  ⚠️ 不使用任何隐藏 API（createRfcommSocket / 反射调用 BluetoothSocket 私有方法
 *  等一律没有），保证在无 root 设备上可用。
 * ============================================================================
 */
class RfcommTransport(
    private val address: String,
) : PodTransport {

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

    /** 连接线程里的权限异常传递槽（openSocket 与连接线程共享）。 */
    @Volatile
    private var connectFailureRef: MissingPermissionException? = null

    /** 从连接线程安全地记录权限异常（最终由主线程抛出）。 */
    private fun lockConnectFailure(error: SecurityException) {
        connectFailureRef = MissingPermissionException("BLUETOOTH_CONNECT")
        EventLog.info(TAG, "SPP 连接被权限拦截：${error.message}")
    }

    private var readJob: Job? = null

    /** 读循环代次：只有最新一代的读循环才有资格上报断开。 */
    @Volatile
    private var readGeneration: Int = 0

    override val isConnected: Boolean
        get() = connected && !closed && socket != null

    // ------------------------------------------------------------------ 连接

    override suspend fun connect(listener: TransportListener) {
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

        val opened = try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { openSocket(device) }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw TransportTimeoutException("RFCOMM 连接 ${CONNECT_TIMEOUT_MS / 1000} 秒未完成（$address）")
        } catch (error: PodTransportException) {
            throw error
        } catch (error: SecurityException) {
            throw MissingPermissionException("BLUETOOTH_CONNECT")
        } catch (error: IOException) {
            throw TransportIoException("RFCOMM 连接失败：${describe(error)}", error)
        }

        if (closed) {
            runCatching { opened.close() }
            throw TransportIoException("通道在建立过程中已被关闭")
        }

        // BluetoothSocket.setSoTimeout 属于 @hide API，SDK 存根里不可见，
        // 因此用反射调用；失败不影响连接（只是读循环改为阻塞式）。
        runCatching {
            opened.javaClass
                .getMethod("setSoTimeout", Int::class.javaPrimitiveType)
                .invoke(opened, SO_TIMEOUT_MS)
        }.onFailure { e ->
            EventLog.debug(TAG, "setSoTimeout 反射调用失败（忽略）：${e.javaClass.simpleName} ${e.message}")
        }
        socket = opened
        connected = true
        EventLog.info(TAG, "已连接 $address（soTimeout=${SO_TIMEOUT_MS}ms，最大帧长=不限）")
        listener.onTransportReady()
        startReadLoop(opened)
    }

    /**
     * 逐个 SPP UUID + secure/insecure 组合尝试建链。
     * 全部失败时抛出携带完整尝试记录的 [ServiceNotFoundException]。
     */
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        val uuids = AppState.SPP_UUID_CANDIDATES.mapNotNull { text ->
            runCatching { UUID.fromString(text) }.getOrNull()
        }
        val attempts = mutableListOf<String>()
        var lastError: String = "无"

        for (uuid in uuids) {
            for (insecure in listOf(false, true)) {
                if (closed) throw TransportIoException("通道已关闭")

                val label = "${uuid.toString().substring(0, 8)}${if (insecure) "/insecure" else "/secure"}"
                val candidate = try {
                    if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
                    else device.createRfcommSocketToServiceRecord(uuid)
                } catch (error: SecurityException) {
                    throw MissingPermissionException("BLUETOOTH_CONNECT")
                } catch (error: IOException) {
                    lastError = describe(error)
                    attempts += "$label:建socket失败"
                    // 用 info 级别：这些是用户排查"功能不生效"的第一手信息，
                    // 不应被 debug 开关隐藏
                    EventLog.info(TAG, "$label 创建 socket 失败：${describe(error)}")
                    continue
                }

                if (closed) {
                    runCatching { candidate.close() }
                    throw TransportIoException("通道已关闭")
                }

                // 阻塞式 connect 无法被协程取消：withTimeout 超时只是抛异常，
                // native 的 connect 会继续跑、最终把 socket 返回给一个已死的协程，
                // 且没人 close → 反复连接（耳机关机是常见场景）持续泄漏 FD。
                // 改用独立线程 + join(超时)：超时后 close() 打断 native connect。
                val connectWorker = Thread(
                    {
                        runCatching { candidate.connect() }.onFailure { connectError ->
                            if (connectError is SecurityException) {
                                // 权限异常要冒泡，不能吞掉（与旧行为一致）
                                lockConnectFailure(connectError)
                            }
                        }
                    },
                    "yinpage-rfcomm-connect",
                ).apply { isDaemon = true }
                // 权限异常由这个字段传递到主线程
                connectFailureRef = null
                connectWorker.start()
                connectWorker.join(CONNECT_TIMEOUT_MS)
                if (connectWorker.isAlive) {
                    runCatching { candidate.close() }
                    lastError = "连接超时（${CONNECT_TIMEOUT_MS / 1000} 秒）"
                    attempts += "$label:超时"
                    EventLog.info(TAG, "$label 连接超时（已 close 打断）")
                    continue
                }
                val permFailure = connectFailureRef
                if (permFailure != null) {
                    runCatching { candidate.close() }
                    throw permFailure
                }
                if (candidate.isConnected) {
                    EventLog.info(TAG, "SPP 通道建立成功：$label")
                    return candidate
                }
                // 连接失败但未抛异常（isConnected=false）
                lastError = "连接失败（未知原因）"
                attempts += "$label:连接失败"
                EventLog.info(TAG, "$label 连接失败（isConnected=false）")
                runCatching { candidate.close() }
            }
        }

        throw ServiceNotFoundException(
            "已尝试 ${attempts.size} 种 SPP UUID/方式（${attempts.joinToString("，")}），最后错误=$lastError"
        )
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

        /** 建链超时（含 SDP 查询），与任务约定一致：15 秒。 */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** read() 的阻塞上限，用于让读循环能周期性检查取消 / 关闭。 */
        const val SO_TIMEOUT_MS = 700

        const val READ_BUFFER_SIZE = 512
    }
}
