package com.yinpage.link.module.pods

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.yinpage.link.module.hook.ModuleLog
import com.yinpage.link.protocol.Bytes
import com.yinpage.link.protocol.bluetrum.BluetrumCodec
import com.yinpage.link.protocol.bluetrum.BtCommand
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 *  耳机 SPP 控制器（运行在 com.android.bluetooth 进程内）
 * ============================================================================
 *  与免 root 独立应用里的 `RfcommTransport` 的区别：
 *    - 本类跑在**系统蓝牙进程**里，直接持有 BluetoothDevice，无需重新配对；
 *    - 只做"连上 + 收发 + 交给协议层解码"，不做重连退避策略（由上层调度）；
 *    - 线程模型：单读线程 + 同步写，写操作加锁。
 *
 *  协议层完全复用 `com.yinpage.link.protocol.bluetrum`（与独立应用同一份代码），
 *  已确证：中科蓝讯 AB 系，5 字节帧头 `[seq][command][type][chunk][len]`，无 CRC。
 * ============================================================================
 */
class RfcommController(
    private val device: BluetoothDevice,
    private val onUpdate: (com.yinpage.link.protocol.PodUpdate) -> Unit,
    private val onDisconnected: (String) -> Unit,
) {

    private val codec = BluetrumCodec()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    private val running = AtomicBoolean(false)

    /** 连接代次：防止旧读线程清理新连接（HyperOriG 同款做法）。 */
    private val generation = AtomicInteger(0)

    private val writeLock = Any()

    @Volatile
    var isConnected: Boolean = false
        private set

    // ------------------------------------------------------------------ 连接

    /** 在当前线程阻塞连接（调用方负责放到 IO 线程）。 */
    fun connect(): Boolean {
        if (isConnected) return true
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }

        val myGen = generation.incrementAndGet()
        var opened: BluetoothSocket? = null

        // 安全通道优先，失败退不安全通道，再失败遍历候选 UUID
        for (uuid in SPP_UUIDS) {
            opened = tryOpen(device, uuid, secure = true)
                ?: tryOpen(device, uuid, secure = false)
            if (opened != null) break
        }
        if (opened == null) {
            ModuleLog.w(TAG, "SPP 连接失败：所有候选 UUID 都不可用")
            return false
        }

        // setSoTimeout 是 @hide API，反射调用；失败不影响功能
        runCatching {
            opened.javaClass.getMethod("setSoTimeout", Int::class.javaPrimitiveType!!)
                .invoke(opened, READ_TIMEOUT_MS)
        }

        socket = opened
        input = runCatching { opened.inputStream }.getOrNull()
        output = runCatching { opened.outputStream }.getOrNull()
        if (input == null || output == null) {
            closeQuietly(opened)
            return false
        }
        isConnected = true
        running.set(true)
        ModuleLog.i(TAG, "SPP 已连接 ${device.address}")

        Thread({ readLoop(myGen) }, "yinpage-spp-read").apply {
            isDaemon = true
            start()
        }
        // 握手：查询电量 / 能力 / 固件 / ANC / EQ / 佩戴
        codec.handshake().forEach { sendRaw(it) }
        return true
    }

    private fun tryOpen(device: BluetoothDevice, uuid: UUID, secure: Boolean): BluetoothSocket? {
        var s: BluetoothSocket? = null
        return try {
            s = if (secure) {
                device.createRfcommSocketToServiceRecord(uuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            }
            s.connect()
            s
        } catch (e: Throwable) {
            // connect() 抛错时必须关闭已创建的 socket，否则 3 UUID × 2 通道反复尝试会累积 fd
            runCatching { s?.close() }
            ModuleLog.d(TAG, "连接尝试失败 uuid=$uuid secure=$secure: ${e.message}")
            null
        }
    }

    private fun readLoop(myGen: Int) {
        val buffer = ByteArray(1024)
        try {
            while (running.get() && generation.get() == myGen) {
                val len = input?.read(buffer) ?: break
                // read() 返回 -1 表示流已结束（对端断开），必须退出循环；
                // 只有返回 0（本次没读到）才 continue。旧实现把 -1 当空读 continue，
                // 会变成不阻塞的忙等：100% 占满一个核，且 finally 里的断连回调永不触发。
                if (len < 0) break
                if (len == 0) continue
                val chunk = IntArray(len) { buffer[it].toInt() and 0xFF }
                codec.decode(chunk) { update -> onUpdate(update) }
            }
        } catch (e: Throwable) {
            if (running.get() && generation.get() == myGen) {
                ModuleLog.w(TAG, "读循环结束：${e.message}")
            }
        } finally {
            if (generation.get() == myGen) {
                isConnected = false
                onDisconnected("SPP 通道已断开")
            }
        }
    }

    // ------------------------------------------------------------------ 发送

    /** 发送已编码好的帧。 */
    fun sendRaw(frame: Bytes): Boolean {
        if (frame.isEmpty()) return false
        val out = output ?: return false
        return synchronized(writeLock) {
            runCatching {
                val bytes = ByteArray(frame.size) { frame[it].toByte() }
                out.write(bytes)
                out.flush()
                true
            }.onFailure {
                ModuleLog.w(TAG, "写入失败：${it.message}")
                false
            }.getOrDefault(false)
        }
    }

    /** 发送一条高层命令。 */
    fun send(command: com.yinpage.link.protocol.PodCommand): Boolean {
        val frames = codec.encode(command)
        if (frames.isEmpty()) return false
        return frames.all { sendRaw(it) }
    }

    /** 查询电量。 */
    fun queryBattery(): Boolean = send(com.yinpage.link.protocol.PodCommand.QueryBattery)

    /** 查询全量状态。 */
    fun queryAll(): Boolean = send(com.yinpage.link.protocol.PodCommand.QueryAll)

    /** 设置降噪模式。 */
    fun setNoise(mode: com.yinpage.link.protocol.NoiseMode): Boolean =
        send(com.yinpage.link.protocol.PodCommand.SetNoise(mode))

    // ------------------------------------------------------------------ 关闭

    fun close() {
        running.set(false)
        generation.incrementAndGet()
        isConnected = false
        closeQuietly(socket)
        socket = null
        input = null
        output = null
        ModuleLog.i(TAG, "SPP 已关闭 ${device.address}")
    }

    private fun closeQuietly(s: BluetoothSocket?) {
        runCatching { s?.close() }
    }

    companion object {
        private const val TAG = "Rfcomm"

        /** 标准 SPP UUID（已确证该耳机走经典蓝牙 SPP）。 */
        private const val SPP_STANDARD = "00001101-0000-1000-8000-00805F9B34FB"

        /** 候选 UUID：标准 SPP 在前，另有 SDK 里出现过的自定义 SPP UUID 位（见文档）。 */
        private val SPP_UUIDS: List<UUID> = listOf(
            UUID.fromString(SPP_STANDARD),
            UUID.fromString("00001102-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("0000A100-1000-8000-4E48-434B4354524C"),
        )

        private const val READ_TIMEOUT_MS = 1500
    }
}
