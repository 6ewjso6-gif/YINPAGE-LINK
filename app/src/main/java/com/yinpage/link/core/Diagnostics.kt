package com.yinpage.link.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yinpage.link.protocol.bluetrum.BluetrumCodec
import com.yinpage.link.protocol.bluetrum.BtCommand
import com.yinpage.link.protocol.bluetrum.BtInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ============================================================================
 *  一键诊断
 * ============================================================================
 *  目的：用户报"功能不生效"时，光靠 UI 状态无法判断卡在哪一环。
 *  本工具把**所有关键事实**收集成一段可复制的文本：
 *
 *    1. 应用 / 系统 / 权限状态
 *    2. 蓝牙适配器状态、已配对设备清单
 *    3. 目标耳机是否已配对、名称、连接状态
 *    4. **逐个 SPP UUID 的真实连接测试**（这是最关键的一环：
 *       能区分"耳机不支持 SPP" / "SPP 被别的 App 占用" / "通道正常"）
 *    5. 生成的命令帧的十六进制（确认协议编码正确）
 *
 *  全程不抛异常，结果直接可读。
 * ============================================================================
 */
object Diagnostics {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /**
     * 生成诊断报告。
     * @param address 目标耳机地址；为空时报告列出所有已配对设备供选择
     */
    fun buildReport(context: Context?, address: String?): String {
        val sb = StringBuilder()
        sb.appendLine("===== YINPAGE-LINK 诊断报告 =====")
        sb.appendLine("时间：${timeFmt.format(Date())}")

        if (context == null) {
            sb.appendLine("❌ 无法获取应用上下文，诊断终止")
            return sb.toString()
        }

        appendEnvironment(context, sb)
        val adapter = appendAdapter(context, sb)
        appendPairedDevices(context, adapter, address, sb)
        appendProtocolFrames(context, sb)
        appendSppTest(context, adapter, address, sb)
        appendBleTest(context, adapter, address, sb)

        sb.appendLine("===== 报告结束 =====")
        return sb.toString()
    }

    // ------------------------------------------------------------------ BLE 测试

    /**
     * BLE GATT 服务枚举。
     *
     * 为什么重要：如果目标设备是 **BLE 随机地址**（首字节最高两位为 01，如 `7A:...`），
     * 它大概率**不走经典蓝牙 SPP**，而是通过 BLE GATT 特征值通信。
     * 把它的服务/特征值全列出来，就能判断控制协议在哪。
     */
    private fun appendBleTest(
        context: Context,
        adapter: BluetoothAdapter?,
        target: String?,
        sb: StringBuilder,
    ) {
        sb.appendLine()
        sb.appendLine("--- 7. BLE GATT 服务枚举（关键）---")
        if (adapter == null || target.isNullOrBlank()) {
            sb.appendLine("跳过：缺少适配器或目标地址")
            return
        }
        sb.appendLine("目标地址类型：${addressTypeHint(target)}")

        val device = runCatching { adapter.getRemoteDevice(target) }.getOrNull()
        if (device == null) {
            sb.appendLine("跳过：无法构造 BluetoothDevice")
            return
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultText = "未完成"
        var gatt: android.bluetooth.BluetoothGatt? = null

        val callback = object : android.bluetooth.BluetoothGattCallback() {
            override fun onConnectionStateChange(g: android.bluetooth.BluetoothGatt?, status: Int, newState: Int) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    resultText = "已连接，正在发现服务…"
                    runCatching { g?.discoverServices() }
                } else {
                    resultText = "连接失败或被断开（status=$status newState=$newState）"
                    latch.countDown()
                }
            }

            override fun onServicesDiscovered(g: android.bluetooth.BluetoothGatt?, status: Int) {
                val text = StringBuilder()
                if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                    text.appendLine("服务发现失败：status=$status")
                } else {
                    val services = runCatching { g?.services }.getOrNull().orEmpty()
                    text.appendLine("发现 ${services.size} 个服务：")
                    services.forEach { svc ->
                        text.appendLine("  SERVICE ${svc.uuid}")
                        svc.characteristics?.forEach { ch ->
                            text.appendLine("    CHAR ${ch.uuid}  props=0x${Integer.toHexString(ch.properties)}${propsText(ch.properties)}")
                            ch.descriptors?.forEach { d ->
                                text.appendLine("      DESC ${d.uuid}")
                            }
                        }
                    }
                }
                resultText = text.toString().trimEnd()
                latch.countDown()
            }
        }

        runCatching {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                sb.appendLine("❌ connectGatt 返回 null（设备不在范围内？）")
                return
            }
            val finished = latch.await(BLE_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            sb.appendLine(if (finished) resultText else "⏱ 超时（${BLE_TIMEOUT_SEC}s）：$resultText")
        }.onFailure {
            sb.appendLine("❌ BLE 测试异常：${it.javaClass.simpleName} ${it.message}")
        }.also {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }

        sb.appendLine()
        sb.appendLine("提示：若上面出现非标准 UUID（非 0000xxxx-0000-1000-8000-00805f9b34fb 形式），")
        sb.appendLine("那很可能就是厂商私有的控制通道 —— 协议应基于它来逆向。")
    }

    /** 从地址推断是公共地址还是 BLE 随机地址。 */
    private fun addressTypeHint(address: String): String {
        val first = address.substringBefore(':').toIntOrNull(16) ?: return "无法解析"
        val top2 = (first shr 6) and 0x03
        return when (top2) {
            0b00 -> "公共地址（经典蓝牙为主）"
            0b01 -> "⚠️ BLE 随机静态地址 → 大概率是 BLE 设备，不走 SPP"
            0b10 -> "保留"
            else -> "BLE 随机私有地址（可解析）"
        }
    }

    private fun propsText(props: Int): String {
        val list = buildList {
            if (props and 0x02 != 0) add("READ")
            if (props and 0x08 != 0) add("WRITE")
            if (props and 0x04 != 0) add("WRITE_NO_RESPONSE")
            if (props and 0x10 != 0) add("NOTIFY")
            if (props and 0x20 != 0) add("INDICATE")
        }
        return if (list.isEmpty()) "" else " [${list.joinToString("|")}]"
    }

    // ------------------------------------------------------------------ 环境

    private fun appendEnvironment(context: Context, sb: StringBuilder) {
        sb.appendLine()
        sb.appendLine("--- 1. 环境 ---")
        sb.appendLine("应用版本：${appVersion(context)}")
        sb.appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("是否为 HyperOS：${runCatching { SystemPropertiesProxy.get("ro.mi.os.version.code") }.getOrNull() ?: "未知"}")

        sb.appendLine("权限：")
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        perms.forEach { p ->
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            sb.appendLine("  ${if (granted) "✅" else "❌"} ${p.substringAfterLast('.')}")
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未知")

    // ------------------------------------------------------------------ 适配器

    private fun appendAdapter(context: Context, sb: StringBuilder): BluetoothAdapter? {
        sb.appendLine()
        sb.appendLine("--- 2. 蓝牙适配器 ---")
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()
        if (adapter == null) {
            sb.appendLine("❌ 本机没有蓝牙适配器")
            return null
        }
        sb.appendLine("名称：${runCatching { adapter.name }.getOrNull() ?: "读取失败（缺权限）"}")
        sb.appendLine("地址：${runCatching { adapter.address }.getOrNull() ?: "读取失败（缺权限）"}")
        sb.appendLine("已开启：${runCatching { adapter.isEnabled }.getOrDefault(false)}")
        sb.appendLine("正在扫描：${runCatching { adapter.isDiscovering }.getOrDefault(false)}")
        return adapter
    }

    // ------------------------------------------------------------------ 设备

    private fun appendPairedDevices(
        context: Context,
        adapter: BluetoothAdapter?,
        target: String?,
        sb: StringBuilder,
    ) {
        sb.appendLine()
        sb.appendLine("--- 3. 已配对设备 ---")
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull()
        if (bonded.isNullOrEmpty()) {
            sb.appendLine("（没有已配对设备，或读取失败）")
        } else {
            bonded.forEach { d ->
                val name = runCatching { d.name }.getOrNull() ?: "?"
                val addr = runCatching { d.address }.getOrNull() ?: "?"
                val mark = if (addr.equals(target, ignoreCase = true)) "⭐ 目标" else ""
                sb.appendLine("  $name  ($addr) $mark")
            }
        }

        sb.appendLine()
        sb.appendLine("--- 4. 目标设备 ---")
        if (target.isNullOrBlank()) {
            sb.appendLine("未指定目标地址（请先在设备页选择并连接一次）")
            return
        }
        val device = runCatching { adapter?.getRemoteDevice(target) }.getOrNull()
        if (device == null) {
            sb.appendLine("❌ 无法通过地址构造 BluetoothDevice：$target")
            return
        }
        sb.appendLine("地址：$target")
        sb.appendLine("名称：${runCatching { device.name }.getOrNull() ?: "读取失败"}")
        sb.appendLine("配对状态：${bondText(runCatching { device.bondState }.getOrDefault(-1))}")
        sb.appendLine("类型：${deviceTypeText(device)}")
    }

    private fun bondText(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "已配对"
        BluetoothDevice.BOND_BONDING -> "配对中"
        BluetoothDevice.BOND_NONE -> "未配对"
        else -> "未知($state)"
    }

    private fun deviceTypeText(device: BluetoothDevice): String = runCatching {
        // Android 9+ 的公开 API
        val m = device.javaClass.getMethod("getBluetoothClass")
        val clazz = m.invoke(device) as? android.bluetooth.BluetoothClass
        "major=${clazz?.majorDeviceClass} device=${clazz?.deviceClass}"
    }.getOrElse { "读取失败：${it.message}" }

    // ------------------------------------------------------------------ 协议帧

    private fun appendProtocolFrames(context: Context, sb: StringBuilder) {
        sb.appendLine()
        sb.appendLine("--- 5. 协议帧编码自检 ---")
        runCatching {
            val codec = BluetrumCodec()
            val frames = codec.handshake()
            sb.appendLine("握手帧数量：${frames.size}")
            frames.take(4).forEach { f ->
                sb.appendLine("  ${hex(f)}  （长度 ${f.size}）")
            }
            // 手动构造一条"查询电量"验证编码
            val expect = intArrayOf(0x00, BtCommand.DEVICE_INFO, BtCommand.TYPE_REQUEST, 0x00, 0x02, BtInfo.DEVICE_POWER, 0x00)
            val actual = codec.handshake().firstOrNull()
            val ok = actual != null && actual.contentEquals(expect)
            sb.appendLine("首帧是否符合预期 [00 27 01 00 02 01 00]：${if (ok) "✅ 是" else "❌ 否，实际=${actual?.let { hex(it) }}"}")
        }.onFailure {
            sb.appendLine("协议帧自检失败：${it.message}")
        }
    }

    // ------------------------------------------------------------------ SPP 测试（核心）

    /**
     * 逐个候选 UUID 做**真实连接测试**。
     * 这是整份报告最有价值的部分：能直接区分
     *   - 耳机不支持 SPP（全部失败）
     *   - SPP 被官方 App 等占用（连接被拒/超时）
     *   - 通道正常（某个 UUID 成功）
     */
    private fun appendSppTest(
        context: Context,
        adapter: BluetoothAdapter?,
        target: String?,
        sb: StringBuilder,
    ) {
        sb.appendLine()
        sb.appendLine("--- 6. SPP 通道实测（关键）---")
        if (adapter == null || target.isNullOrBlank()) {
            sb.appendLine("跳过：缺少适配器或目标地址")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(target) }.getOrNull()
        if (device == null) {
            sb.appendLine("跳过：无法构造 BluetoothDevice")
            return
        }

        runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }

        val uuids = listOf(
            "00001101-0000-1000-8000-00805F9B34FB",
            "00001102-0000-1000-8000-00805F9B34FB",
            "0000A100-1000-8000-4E48-434B4354524C",
        )
        var anySuccess = false
        for (uuidText in uuids) {
            val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull() ?: continue
            for (insecure in listOf(false, true)) {
                val label = "${uuidText.substring(0, 8)}/${if (insecure) "insecure" else "secure"}"
                val result = tryConnect(device, uuid, insecure)
                sb.appendLine("  $label → $result")
                if (result.startsWith("成功")) {
                    anySuccess = true
                    break
                }
            }
            if (anySuccess) break
        }

        sb.appendLine()
        sb.appendLine(
            if (anySuccess) "结论：✅ SPP 通道可用，协议层可以工作"
            else "结论：❌ 所有 SPP 通道都连不上 —— 请确认：\n" +
                "   · 官方音贝奇 App 是否已完全退出（它会独占 SPP 通道）\n" +
                "   · 耳机是否已在系统蓝牙里连接（部分耳机要求先连 A2DP）\n" +
                "   · 耳机是否支持经典蓝牙 SPP（有些白牌只提供 BLE）",
        )
    }

    /** 单次连接尝试，返回人话结果。 */
    private fun tryConnect(device: BluetoothDevice, uuid: UUID, insecure: Boolean): String {
        var socket: android.bluetooth.BluetoothSocket? = null
        return try {
            socket = if (insecure) {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            } else {
                device.createRfcommSocketToServiceRecord(uuid)
            }
            // 用独立线程做超时控制，避免诊断卡住
            val thread = Thread {
                runCatching { socket?.connect() }
            }
            thread.isDaemon = true
            thread.start()
            thread.join(CONNECT_TIMEOUT_MS)
            if (thread.isAlive) {
                runCatching { socket?.close() }
                "超时（${CONNECT_TIMEOUT_MS / 1000}s 未完成）"
            } else {
                val ok = runCatching { socket?.isConnected == true }.getOrDefault(false)
                if (ok) "成功 ✅ 已建立通道" else "失败（未建立）"
            }
        } catch (e: SecurityException) {
            "权限不足（BLUETOOTH_CONNECT）"
        } catch (e: Throwable) {
            "失败：${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        } finally {
            runCatching { socket?.close() }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 8_000L

    /** BLE 服务发现超时（秒）。 */
    private const val BLE_TIMEOUT_SEC = 15L

    private fun hex(data: IntArray): String = data.joinToString(" ") { "%02X".format(it and 0xFF) }

    /** 读取系统属性（用于判断是否 HyperOS），失败返回 null。 */
    private object SystemPropertiesProxy {
        fun get(key: String): String? = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
