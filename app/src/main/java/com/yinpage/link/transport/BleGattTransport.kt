@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.yinpage.link.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.yinpage.link.core.EventLog
import com.yinpage.link.protocol.BluetoothOffException
import com.yinpage.link.protocol.Bytes
import com.yinpage.link.protocol.DeviceUnavailableException
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.ServiceNotFoundException
import com.yinpage.link.protocol.TransportFrame
import com.yinpage.link.protocol.TransportIoException
import com.yinpage.link.protocol.TransportListener
import com.yinpage.link.protocol.TransportTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * ============================================================================
 *  BLE GATT 传输 —— 免 root，只用公开 API
 * ============================================================================
 *  与 RFCOMM 并列的第二条物理通道（部分方案把状态/OTA 放在 GATT 上）。
 *
 *  - 连接目标是已知 MAC：直接 getRemoteDevice(address).connectGatt(TRANSPORT_LE)，
 *    因此不需要 BluetoothLeScanner，也不需要 BLUETOOTH_SCAN。
 *  - 所有 GATT 回调统一 post 到主线程处理（Android 对 GATT 调用的线程要求），
 *    这样 discoverServices / writeCharacteristic / close 永远在主线程串行执行。
 *  - 超时：连接 15 秒、服务发现 15 秒、单次写入 5 秒。
 *  - 特征值选择：优先「既可写又可通知」的那个；否则退回「写 + 通知」分离的一对；
 *    仍然找不到就抛 [ServiceNotFoundException]，detail 里带上完整 GATT 结构。
 *  - 服务发现时会把所有 Service / Characteristic / Descriptor 的 UUID 与属性
 *    以 tag=GATT 的固定格式写进 EventLog，供逆向枚举（见 [logGattTree]）。
 * ============================================================================
 */
class BleGattTransport(
    private val address: String,
) : PodTransport {

    override val name: String = "BLE"

    @Volatile
    private var negotiatedMtu: Int = DEFAULT_MTU

    /** 协商后的单帧有效载荷上限（ATT 头 3 字节：opcode + handle）。 */
    override val maxFrameSize: Int
        get() = (negotiatedMtu - ATT_HEADER).coerceAtLeast(MIN_PAYLOAD)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeGate = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var listener: TransportListener? = null

    private var connectSignal: CompletableDeferred<Unit>? = null
    private var discoverSignal: CompletableDeferred<Unit>? = null
    private var writeSignal: CompletableDeferred<Unit>? = null

    override val isConnected: Boolean
        get() = connected && !closed && gatt != null

    // ------------------------------------------------------------------ 连接

    override suspend fun connect(listener: TransportListener) {
        this.listener = listener
        this.closed = false
        this.connected = false
        this.negotiatedMtu = DEFAULT_MTU

        val context = TransportEnv.context()
            ?: throw DeviceUnavailableException("应用上下文不可用")
        val adapter = TransportEnv.adapter()
            ?: throw DeviceUnavailableException("本机不支持蓝牙")
        if (!adapter.isEnabled) throw BluetoothOffException()
        TransportEnv.requireConnectPermission(context)

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (error: IllegalArgumentException) {
            throw DeviceUnavailableException("非法蓝牙地址 $address", error)
        }

        val connectWaiter = CompletableDeferred<Unit>()
        val discoverWaiter = CompletableDeferred<Unit>()
        connectSignal = connectWaiter
        discoverSignal = discoverWaiter

        val client = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (error: SecurityException) {
            throw MissingPermissionException("BLUETOOTH_CONNECT")
        } catch (error: IllegalArgumentException) {
            throw DeviceUnavailableException("connectGatt() 参数被拒绝", error)
        }
        if (client == null) {
            throw DeviceUnavailableException("connectGatt() 返回 null（$address）")
        }
        gatt = client
        EventLog.info(TAG, "开始连接 $address（TRANSPORT_LE，默认 mtu=$DEFAULT_MTU）")

        // ---- 1. 建链 ----
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connectWaiter.await() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw TransportTimeoutException("BLE 连接 ${CONNECT_TIMEOUT_MS / 1000} 秒未完成（$address）")
        } catch (cancel: CancellationException) {
            close()
            throw cancel
        } catch (error: Throwable) {
            close()
            throw TransportEnv.mapGattFailure(error, "BLE 连接失败")
        }

        // ---- 2. 服务发现 ----
        try {
            withTimeout(DISCOVER_TIMEOUT_MS) { discoverWaiter.await() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw TransportTimeoutException("BLE 服务发现 ${DISCOVER_TIMEOUT_MS / 1000} 秒未完成（$address）")
        } catch (cancel: CancellationException) {
            close()
            throw cancel
        } catch (error: Throwable) {
            close()
            throw TransportEnv.mapGattFailure(error, "BLE 服务发现失败")
        }

        if (closed) throw TransportIoException("通道在建立过程中已被关闭")

        EventLog.info(
            TAG,
            "已就绪：$address 写入特征=${writeCharacteristic?.uuid} 通知特征=${notifyCharacteristic?.uuid} " +
                "mtu=$negotiatedMtu 单帧上限=${maxFrameSize}B",
        )
        listener.onTransportReady()
    }

    // ------------------------------------------------------------------ GATT 回调

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnMain { handleConnectionStateChange(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnMain { handleServicesDiscovered(gatt, status) }
        }

        /** API < 33 的回调路径。 */
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val value = characteristic.value ?: return
            runOnMain { handleNotification(value) }
        }

        /** API >= 33 的回调路径。 */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            runOnMain { handleNotification(value) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            runOnMain { handleCharacteristicWrite(characteristic, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            EventLog.info(TAG, "CCCD 写入 ${descriptor.uuid} status=$status（${TransportEnv.describeGattStatus(status)}）")
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            runOnMain {
                if (status == GATT_SUCCESS && mtu > 0) {
                    negotiatedMtu = mtu
                    EventLog.info(
                        TAG,
                        "MTU 协商成功：$mtu（单帧上限 ${(mtu - ATT_HEADER).coerceAtLeast(MIN_PAYLOAD)}B）",
                    )
                } else {
                    EventLog.info(
                        TAG,
                        "MTU 协商未成功 status=$status（${TransportEnv.describeGattStatus(status)}），保持 $negotiatedMtu",
                    )
                }
            }
        }
    }

    private fun handleConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
        EventLog.info(
            TAG,
            "onConnectionStateChange status=$status（${TransportEnv.describeGattStatus(status)}） " +
                "newState=${describeState(newState)}",
        )
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status == GATT_SUCCESS) {
                    connected = true
                    connectSignal?.complete(Unit)
                    val started = runCatching { client.discoverServices() }.getOrDefault(false)
                    EventLog.info(TAG, "discoverServices() -> $started")
                    if (!started) {
                        discoverSignal?.completeExceptionally(
                            TransportIoException("discoverServices() 调用失败"),
                        )
                    }
                } else {
                    connected = false
                    connectSignal?.completeExceptionally(
                        DeviceUnavailableException(
                            "连接被拒绝或设备忙（status=$status ${TransportEnv.describeGattStatus(status)}）",
                        ),
                    )
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val wasConnected = connected
                connected = false
                connectSignal?.takeIf { !it.isCompleted }?.completeExceptionally(
                    DeviceUnavailableException(
                        "设备拒绝连接或已关机（status=$status ${TransportEnv.describeGattStatus(status)}）",
                    ),
                )
                discoverSignal?.takeIf { !it.isCompleted }?.completeExceptionally(
                    TransportIoException("服务发现前连接已断开（status=$status）"),
                )
                if (closed) return
                if (wasConnected || writeCharacteristic != null) {
                    releaseGatt()
                    listener?.onTransportClosed(
                        "BLE 连接断开（status=$status ${TransportEnv.describeGattStatus(status)}）",
                    )
                }
            }
        }
    }

    private fun handleServicesDiscovered(client: BluetoothGatt, status: Int) {
        if (status != GATT_SUCCESS) {
            val detail = "服务发现失败 status=$status（${TransportEnv.describeGattStatus(status)}）"
            EventLog.info(TAG, detail)
            discoverSignal?.completeExceptionally(ServiceNotFoundException(detail))
            return
        }

        val services = client.services ?: emptyList()
        val tree = logGattTree(services)

        // 1) Bluetrum AB 专属通道优先（官方 ABMate SDK 确证）：
        //    服务 0000FDB3 / 写特征 0000FF17 / 读-通知特征 0000FF18。
        //    这类耳机服务里常有多对可写/可通知特征，通用启发式会选错，
        //    命中官方 UUID 时直接锁定，不参与启发式打分。
        val ab = findBluetrumChannel(services)

        // 2) 通用启发式（无 Bluetrum 服务时兜底）
        val all = services.flatMap { it.characteristics }
        val combined = all
            .filter { isWritable(it) && isNotifiable(it) }
            .maxByOrNull { propertyScore(it) }

        val writeChar = ab?.write
            ?: combined
            ?: all.filter { isWritable(it) }.maxByOrNull { propertyScore(it) }
        val notifyChar = ab?.notify
            ?: combined
            ?: all.filter { isNotifiable(it) }.maxByOrNull { propertyScore(it) }

        if (writeChar == null || notifyChar == null) {
            val detail = buildString {
                append("未找到可用的私有通道特征值")
                append("（可写=${writeChar != null} 可通知=${notifyChar != null}）。已发现：")
                append(tree)
            }
            EventLog.info(TAG, detail)
            discoverSignal?.completeExceptionally(ServiceNotFoundException(detail))
            return
        }

        writeCharacteristic = writeChar
        notifyCharacteristic = notifyChar
        EventLog.info(
            TAG,
            if (ab != null) {
                "选定 Bluetrum AB 通道：写=${writeChar.uuid} 通知=${notifyChar.uuid}"
            } else if (writeChar == notifyChar) {
                "选定特征（读写合一）：${writeChar.uuid} props=[${describeProperties(writeChar.properties)}]"
            } else {
                "选定特征（读写分离）：写=${writeChar.uuid} 通知=${notifyChar.uuid}"
            },
        )
        EventLog.info(
            TAG_ENUM,
            "PICKED write=${writeChar.uuid} notify=${notifyChar.uuid} combined=${writeChar == notifyChar} " +
                "props=0x%02X [%s]".format(writeChar.properties, describeProperties(writeChar.properties)),
        )

        val enabled = runCatching { client.setCharacteristicNotification(notifyChar, true) }.getOrDefault(false)
        EventLog.info(TAG, "setCharacteristicNotification(${notifyChar.uuid}) -> $enabled")

        val cccd = notifyChar.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val value = if (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(client, cccd, value)
        } else {
            EventLog.info(TAG, "特征 ${notifyChar.uuid} 没有 CCCD（$CCCD_UUID），跳过通知使能描述符")
        }

        requestMtu(client)
        discoverSignal?.complete(Unit)
    }

    /**
     * 把发现的 GATT 结构完整写进 EventLog，供逆向枚举 UUID。
     * 固定格式（tag=GATT）：
     *   BEGIN services=N address=...
     *   SERVICE <service-uuid>
     *   CHAR <service-uuid> <char-uuid> props=0xNN [READ|WRITE|...]
     *   DESC <service-uuid> <char-uuid> <desc-uuid> [CCCD]
     *   STATS services=N chars=N descs=N
     *   END services=N address=...
     *   PICKED write=... notify=... combined=true/false props=0xNN [...]
     * 返回同样的结构（用于异常 detail）。
     */
    private fun logGattTree(services: List<BluetoothGattService>): String {
        val sb = StringBuilder()
        var charCount = 0
        var descCount = 0
        EventLog.info(TAG_ENUM, "BEGIN services=${services.size} address=$address")
        for (service in services) {
            EventLog.info(TAG_ENUM, "SERVICE ${service.uuid}")
            sb.append("\n  SERVICE ${service.uuid}")
            for (characteristic in service.characteristics) {
                charCount++
                val props = describeProperties(characteristic.properties)
                EventLog.info(
                    TAG_ENUM,
                    "CHAR ${service.uuid} ${characteristic.uuid} props=0x%02X [%s]".format(
                        characteristic.properties,
                        props,
                    ),
                )
                sb.append(
                    "\n    CHAR ${characteristic.uuid} props=0x%02X [%s]".format(
                        characteristic.properties,
                        props,
                    ),
                )
                for (descriptor in characteristic.descriptors) {
                    descCount++
                    val cccdMark = if (descriptor.uuid == CCCD_UUID) " [CCCD]" else ""
                    EventLog.info(TAG_ENUM, "DESC ${service.uuid} ${characteristic.uuid} ${descriptor.uuid}$cccdMark")
                    sb.append("\n      DESC ${descriptor.uuid}$cccdMark")
                }
            }
        }
        EventLog.info(TAG_ENUM, "STATS services=${services.size} chars=$charCount descs=$descCount")
        EventLog.info(TAG_ENUM, "END services=${services.size} address=$address")
        return sb.toString()
    }

    /**
     * 在已发现的服务里找 Bluetrum AB 专属通道：
     * 服务 0000FDB3 + 写 0000FF17 + 通知 0000FF18（官方 ABMate SDK UUID）。
     * 返回 null 表示没找到（走通用启发式）。
     */
    private data class BluetrumChannel(val write: BluetoothGattCharacteristic, val notify: BluetoothGattCharacteristic)

    private fun findBluetrumChannel(services: List<BluetoothGattService>): BluetrumChannel? {
        val service = services.firstOrNull { it.uuid.toString().lowercase() == BLUETRUM_SERVICE_UUID } ?: return null
        val write = service.characteristics.firstOrNull {
            it.uuid.toString().lowercase() == BLUETRUM_WRITE_UUID && isWritable(it)
        } ?: return null
        val notify = service.characteristics.firstOrNull {
            it.uuid.toString().lowercase() == BLUETRUM_NOTIFY_UUID && isNotifiable(it)
        } ?: return null
        return BluetrumChannel(write, notify)
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        val payload = value.copyOf()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, payload) == GATT_SUCCESS
            } else {
                descriptor.value = payload
                gatt.writeDescriptor(descriptor)
            }
        }.getOrDefault(false)
        EventLog.info(
            TAG,
            "写 CCCD ${descriptor.uuid} -> $started（值 ${payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}）",
        )
    }

    private fun requestMtu(gatt: BluetoothGatt) {
        val started = runCatching { gatt.requestMtu(DESIRED_MTU) }.getOrDefault(false)
        EventLog.info(TAG, "requestMtu($DESIRED_MTU) -> $started（当前 mtu=$negotiatedMtu）")
    }

    private fun handleCharacteristicWrite(characteristic: BluetoothGattCharacteristic, status: Int) {
        EventLog.debug(TAG, "onCharacteristicWrite ${characteristic.uuid} status=$status")
        val signal = writeSignal ?: return
        if (status == GATT_SUCCESS) {
            signal.takeIf { !it.isCompleted }?.complete(Unit)
        } else {
            signal.takeIf { !it.isCompleted }?.completeExceptionally(
                TransportIoException("写入特征值失败 status=$status（${TransportEnv.describeGattStatus(status)}）"),
            )
        }
    }

    private fun handleNotification(value: ByteArray) {
        if (closed || value.isEmpty()) return
        val data = IntArray(value.size) { value[it].toInt() and 0xFF }
        EventLog.bytes(TAG, "RX", data)
        listener?.onFrame(TransportFrame(data))
    }

    // ------------------------------------------------------------------ 写

    override suspend fun write(bytes: Bytes) {
        if (bytes.isEmpty()) return
        val characteristic = writeCharacteristic
            ?: throw TransportIoException("未选定 BLE 写入特征值")
        val client = gatt ?: throw TransportIoException("BLE 通道未建立")
        if (!connected || closed) throw TransportIoException("BLE 通道未就绪")

        val payload = ByteArray(bytes.size) { bytes[it].toByte() }
        EventLog.bytes(TAG, "TX", bytes)

        writeGate.withLock {
            val chunkSize = maxFrameSize
            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + chunkSize, payload.size)
                writeChunk(client, characteristic, payload.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    /** 按 MTU 分片写入；有响应写等回调，无响应写给协议栈留出排队时间。 */
    private suspend fun writeChunk(
        client: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ) {
        val supportsWrite = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsWriteNoResponse =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val withResponse = supportsWrite && !supportsWriteNoResponse
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val signal = CompletableDeferred<Unit>()
        writeSignal = signal

        runOnMain {
            val started = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    client.writeCharacteristic(characteristic, chunk, writeType) == GATT_SUCCESS
                } else {
                    characteristic.value = chunk
                    characteristic.writeType = writeType
                    client.writeCharacteristic(characteristic)
                }
            } catch (error: Throwable) {
                signal.completeExceptionally(TransportEnv.mapGattFailure(error, "写入特征值异常"))
                return@runOnMain
            }
            if (!started) {
                signal.completeExceptionally(
                    TransportIoException("writeCharacteristic() 调用失败（上一次 GATT 操作可能仍在进行）"),
                )
            } else if (!withResponse) {
                signal.complete(Unit)
            }
        }

        if (withResponse) {
            try {
                withTimeout(WRITE_TIMEOUT_MS) { signal.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw TransportTimeoutException("BLE 写入 ${chunk.size}B 超时（${WRITE_TIMEOUT_MS}ms 内未收到 onCharacteristicWrite）")
            }
        } else {
            delay(WRITE_PACING_MS)
        }
    }

    // ------------------------------------------------------------------ 关闭

    override fun close() {
        val hadChannel = gatt != null
        closed = true
        connected = false
        releaseGatt()
        connectSignal?.takeIf { !it.isCompleted }?.completeExceptionally(TransportIoException("BLE 通道已关闭"))
        discoverSignal?.takeIf { !it.isCompleted }?.completeExceptionally(TransportIoException("BLE 通道已关闭"))
        connectSignal = null
        discoverSignal = null
        writeSignal = null
        if (hadChannel) EventLog.info(TAG, "已释放 BLE 通道")
    }

    /** 释放 GATT 客户端（可重入）。 */
    private fun releaseGatt() {
        val previous = gatt
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (previous != null) {
            runCatching { previous.disconnect() }
            runCatching { previous.close() }
        }
        writeSignal?.takeIf { !it.isCompleted }?.completeExceptionally(TransportIoException("BLE 通道已释放"))
    }

    // ------------------------------------------------------------------ 工具

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private companion object {
        const val TAG = "BLE"

        /** GATT 结构枚举专用 tag（固定前缀便于逆向队友 grep）。 */
        const val TAG_ENUM = "GATT"

        const val DEFAULT_MTU = 23
        const val DESIRED_MTU = 247
        const val ATT_HEADER = 3
        const val MIN_PAYLOAD = 20
        const val GATT_SUCCESS = 0

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVER_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val WRITE_PACING_MS = 20L

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Bluetrum AB 系官方控制通道（ABMate SDK 确证）：服务 + 写 + 通知。 */
        val BLUETRUM_SERVICE_UUID = "0000fdb3-0000-1000-8000-00805f9b34fb"
        val BLUETRUM_WRITE_UUID = "0000ff17-0000-1000-8000-00805f9b34fb"
        val BLUETRUM_NOTIFY_UUID = "0000ff18-0000-1000-8000-00805f9b34fb"

        fun isWritable(characteristic: BluetoothGattCharacteristic): Boolean =
            characteristic.properties and (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0

        fun isNotifiable(characteristic: BluetoothGattCharacteristic): Boolean =
            characteristic.properties and (
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                ) != 0

        /** 打分：优先「同时支持两种写 + 通知」的特征值。 */
        fun propertyScore(characteristic: BluetoothGattCharacteristic): Int {
            var score = 0
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) score += 8
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) score += 4
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) score += 2
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) score += 1
            return score
        }

        fun describeProperties(properties: Int): String {
            val parts = buildList {
                if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
                if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
                if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXTENDED_PROPS")
            }
            return if (parts.isEmpty()) "NONE" else parts.joinToString("|")
        }

        fun describeState(state: Int): String = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED($state)"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING($state)"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED($state)"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING($state)"
            else -> "STATE($state)"
        }
    }
}
