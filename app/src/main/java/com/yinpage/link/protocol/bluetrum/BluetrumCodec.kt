package com.yinpage.link.protocol.bluetrum

import com.yinpage.link.protocol.BatteryState
import com.yinpage.link.protocol.EqMode
import com.yinpage.link.protocol.Log
import com.yinpage.link.protocol.NoiseMode
import com.yinpage.link.protocol.PodCodec
import com.yinpage.link.protocol.PodCommand
import com.yinpage.link.protocol.PodUpdate
import com.yinpage.link.protocol.Bytes

/**
 * ============================================================================
 *  中科蓝讯（Bluetrum）AB 系协议 —— YINPAGE 音贝奇 Feel 1 Pro 的真实协议实现
 * ============================================================================
 *  与旧版 `yscoco` 推测实现的区别：**本实现基于官方 App 反编译的确证结论**，
 *  不是猜的。命令码、帧格式、电量布局都有 code_off 级证据（见 doc/PROTOCOL.md）。
 *
 *  协议要点：
 *   - 通道：经典蓝牙 SPP，UUID 00001101-0000-1000-8000-00805F9B34FB
 *   - 帧：5 字节头 + payload，无 CRC
 *   - 查询统一走 DEVICE_INFO(0x27)，payload = [INFO_x, 0x00]
 *   - 通知与响应同码不同 type，路由必须用 (type, command)
 * ============================================================================
 */
class BluetrumCodec : PodCodec {

    override val id: String = "bluetrum-ab"

    override val displayName: String = "中科蓝讯 AB 系（音贝奇 Feel 1 Pro）"

    private val frames = BtFrameCodec()

    /** 上次"无法解析"提示的帧头，避免同一段垃圾数据反复刷屏。 */
    private var lastUnparsedHead: String = ""

    /** 最近一次解析到的状态，用于补齐只上报部分字段的场景。 */
    private var lastBattery = BatteryState()
    private var lastNoise = NoiseMode.OFF
    private var lastEq = EqMode.BALANCED
    private var lastGame = false
    private var lastInEar = false
    private var lastDual = false

    /** 耳机上报的能力位图（0xFE），决定 UI 展示哪些开关。 */
    @Volatile
    var capabilities: Int = -1
        private set

    // ------------------------------------------------------------------ 握手

    /**
     * 连接后的初始化序列。
     * 先查电量（最常用），再查能力位图与固件版本，最后同步 ANC / EQ / 入耳状态。
     */
    override fun handshake(): List<Bytes> = buildList {
        add(query(BtInfo.DEVICE_POWER))
        add(query(BtInfo.DEVICE_CAPABILITIES))
        add(query(BtInfo.FIRMWARE_VERSION))
        add(query(BtInfo.ANC_MODE))
        add(query(BtInfo.EQ_SETTING))
        add(query(BtInfo.IN_EAR_STATUS))
    }

    /** 构造一条设备信息查询：DEVICE_INFO(0x27) + payload [infoId, 0x00]。 */
    private fun query(infoId: Int): Bytes =
        frames.encode(
            command = BtCommand.DEVICE_INFO,
            type = BtCommand.TYPE_REQUEST,
            payload = intArrayOf(infoId and 0xFF, 0x00),
        ).firstOrNull() ?: IntArray(0)

    // ------------------------------------------------------------------ 编码

    override fun encode(command: PodCommand): List<Bytes> = when (command) {
        is PodCommand.SetNoise -> request(BtCommand.ANC_MODE, BtAnc.toDevice(command.mode))

        is PodCommand.SetEq -> {
            // EQ 写入：payload = [bandCount, mode, gains...]
            // 使用预设档位时 gains 为空，bandCount 取 SDK 默认 10
            val mode = BtEq.toDevice(command.mode)
            request(
                BtCommand.EQ,
                BtEq.DEFAULT_BAND_COUNT and 0xFF,
                mode and 0xFF,
            )
        }

        is PodCommand.SetGameMode -> request(BtCommand.WORK_MODE, if (command.enabled) 0x01 else 0x00)

        is PodCommand.SetInEarDetection -> request(BtCommand.IN_EAR_DETECT, if (command.enabled) 0x01 else 0x00)

        is PodCommand.SetDualConnection -> request(BtCommand.DUAL_DEVICE, if (command.enabled) 0x01 else 0x00)

        // 抗风噪：AB 系没有独立的风噪命令，用降噪强度(0x41)的最大档近似
        is PodCommand.SetWindSuppression ->
            request(BtCommand.NOISE_REDUCTION, if (command.enabled) BtAnc.GAIN_MAX else BtAnc.GAIN_MIN)

        PodCommand.QueryAll -> handshake()

        PodCommand.QueryBattery -> listOf(query(BtInfo.DEVICE_POWER))

        PodCommand.QueryFirmware -> listOf(query(BtInfo.FIRMWARE_VERSION))
    }

    /** 构造一条请求帧。 */
    private fun request(command: Int, vararg payload: Int): List<Bytes> =
        frames.encode(command, BtCommand.TYPE_REQUEST, payload)

    /** 直接发任意命令（调试面板用）。 */
    fun rawRequest(command: Int, payload: Bytes, type: Int = BtCommand.TYPE_REQUEST): List<Bytes> =
        frames.encode(command, type, payload)

    /** 构造设备信息查询（调试面板用）。 */
    fun rawQuery(infoId: Int): Bytes = query(infoId)

    // ------------------------------------------------------------------ 解码

    override fun decode(chunk: Bytes, sink: (PodUpdate) -> Unit) {
        if (chunk.isEmpty()) return
        val decoded = try {
            frames.decodeChunk(chunk)
        } catch (error: Throwable) {
            Log.i(TAG, "解码异常：${error.message}")
            return
        }
        for (frame in decoded) {
            try {
                handleFrame(frame, sink)
            } catch (error: Throwable) {
                Log.i(TAG, "处理帧失败 cmd=0x%02X：%s".format(frame.command, error.message))
            }
        }

        // 收到了数据却没产出任何帧 → 无法按 AB 五段式解析。
        // 常见原因：耳机固件对 SPP 数据加密、或该型号用的不是 AB 系帧格式。
        // 必须把**完整原文**（不是截断的头部）展示出来，用户才能对照抓包进一步确证协议。
        if (decoded.isEmpty() && frames.bufferedBytes > 0) {
            val shown = chunk.take(MAX_UNPARSED_SHOW_BYTES)
            val body = shown.joinToString(" ") { "%02X".format(it and 0xFF) }
            val clipped = if (chunk.size > shown.size) "…(+${chunk.size - shown.size}B)" else ""
            if (body != lastUnparsedHead) {
                lastUnparsedHead = body
                Log.i(
                    TAG,
                    "⚠️ 收到 ${chunk.size}B 但无法按 AB 帧解析（原文=$body$clipped，缓冲=${frames.bufferedBytes}B）" +
                        "——可能是加密帧或协议变体",
                )
                sink(PodUpdate.Raw("⚠️ 无法识别的数据：$body$clipped"))
            }
        }
    }

    private fun handleFrame(frame: BtFrameCodec.Frame, sink: (PodUpdate) -> Unit) {
        Log.d(
            TAG,
            "RX type=${frame.type} cmd=0x%02X seq=%d payload=%s".format(
                frame.command, frame.seq, hex(frame.payload),
            ),
        )

        // 设备信息响应/通知：payload[0] = infoId，其后为数据
        if (frame.command == BtCommand.DEVICE_INFO && frame.payload.size >= 2) {
            handleDeviceInfo(frame.type, frame.payload, sink)
            return
        }

        // 通知方向的命令码与查询子码同值，需要单独处理
        when (frame.command) {
            BtInfo.DEVICE_POWER -> BtPower.decodeState(frame.payload)?.let { publishBattery(it, sink) }
            BtInfo.ANC_MODE -> frame.payload.firstOrNull()?.let { publishNoise(it, sink) }
            BtInfo.EQ_SETTING -> frame.payload.firstOrNull()?.let { publishEq(it, sink) }
            BtInfo.IN_EAR_STATUS -> frame.payload.firstOrNull()?.let { publishInEar(it, sink) }
            else -> sink(PodUpdate.Raw("cmd=0x%02X %s".format(frame.command, hex(frame.payload))))
        }
    }

    private fun handleDeviceInfo(type: Int, payload: Bytes, sink: (PodUpdate) -> Unit) {
        val infoId = payload[0] and 0xFF
        val data = if (payload.size > 1) payload.copyOfRange(1, payload.size) else IntArray(0)

        when (infoId) {
            BtInfo.DEVICE_POWER -> BtPower.decodeState(data)?.let { publishBattery(it, sink) }

            BtInfo.ANC_MODE -> data.firstOrNull()?.let { publishNoise(it, sink) }

            BtInfo.EQ_SETTING -> data.firstOrNull()?.let { publishEq(it, sink) }

            BtInfo.IN_EAR_STATUS -> {
                // 入耳状态：0 表示检测开启/已入耳，具体语义随固件，这里按非零为开
                data.firstOrNull()?.let {
                    lastInEar = it != 0
                    sink(PodUpdate.InEarDetection(lastInEar))
                }
            }

            BtInfo.DEVICE_CAPABILITIES -> {
                data.firstOrNull()?.let { raw ->
                    capabilities = raw and 0xFF
                    Log.i(TAG, "设备能力：${BtCapability.describe(capabilities)}")
                    sink(PodUpdate.Raw("能力：${BtCapability.describe(capabilities)}"))
                }
            }

            BtInfo.FIRMWARE_VERSION -> {
                val version = formatVersion(data)
                if (version != null) sink(PodUpdate.Firmware(version))
            }

            BtInfo.IS_TWS -> data.firstOrNull()?.let {
                sink(PodUpdate.Raw(if (it != 0) "TWS 已组队" else "单耳模式"))
            }

            BtInfo.TWS_CONNECTED -> data.firstOrNull()?.let {
                sink(PodUpdate.Raw(if (it != 0) "双耳已连接" else "双耳未连接"))
            }

            BtInfo.ONE_DRAG_TWO -> data.firstOrNull()?.let {
                lastDual = it != 0
                sink(PodUpdate.DualConnection(lastDual))
            }

            BtInfo.PLAY_STATE -> data.firstOrNull()?.let {
                sink(PodUpdate.Raw("播放状态=$it"))
            }

            BtInfo.WORK_MODE -> data.firstOrNull()?.let {
                lastGame = it != 0
                sink(PodUpdate.GameMode(lastGame))
            }

            BtInfo.DEVICE_SN -> sink(PodUpdate.Raw("序列号=${hex(data)}"))

            BtInfo.DEVICE_VOLUME -> data.firstOrNull()?.let { sink(PodUpdate.Raw("音量档位=$it")) }

            BtInfo.MAIN_SIDE -> data.firstOrNull()?.let {
                sink(PodUpdate.Raw(if (it == 0) "主耳：左" else "主耳：右"))
            }

            BtInfo.ALL_EQ_SETTINGS, BtInfo.KEY_SETTINGS, BtInfo.LANGUAGE_SETTING,
            BtInfo.LED_SWITCH, BtInfo.VOLUME_LIMIT, BtInfo.PRODUCT_COLOR,
            BtInfo.ANC_GAIN, BtInfo.TRANSPARENCY_GAIN, BtInfo.ANC_GAIN_NUM,
            BtInfo.TRANSPARENCY_GAIN_NUM, BtInfo.BLUETOOTH_NAME, BtInfo.FW_CHECKSUM,
            BtInfo.AUTO_ANSWER, BtInfo.LISTEN_TIP, BtInfo.CALL_STATUS_UI_INFO,
            BtInfo.ONE_CLOSE_KEY,
            BtInfo.BASS_BOOST_MODE, BtInfo.VOCAL_BOOST_MODE,
            -> sink(PodUpdate.Raw("INFO 0x%02X = %s".format(infoId, hex(data))))

            else -> sink(PodUpdate.Raw("INFO 0x%02X = %s".format(infoId, hex(data))))
        }
    }

    // ------------------------------------------------------------------ 状态发布

    private fun publishBattery(state: BatteryState, sink: (PodUpdate) -> Unit) {
        lastBattery = state
        sink(PodUpdate.Battery(state))
    }

    private fun publishNoise(raw: Int, sink: (PodUpdate) -> Unit) {
        val mode = BtAnc.toUiMode(raw)
        lastNoise = mode
        sink(PodUpdate.Noise(mode))
    }

    private fun publishEq(raw: Int, sink: (PodUpdate) -> Unit) {
        val mode = BtEq.toUiMode(raw)
        lastEq = mode
        sink(PodUpdate.Eq(mode))
    }

    private fun publishInEar(raw: Int, sink: (PodUpdate) -> Unit) {
        lastInEar = raw != 0
        sink(PodUpdate.InEarDetection(lastInEar))
    }

    /** 固件版本：常见布局为 3 段版本号。 */
    private fun formatVersion(data: Bytes): String? {
        if (data.isEmpty()) return null
        return when {
            data.size >= 3 -> "%d.%d.%d".format(data[0] and 0xFF, data[1] and 0xFF, data[2] and 0xFF)
            data.size == 2 -> "%d.%d".format(data[0] and 0xFF, data[1] and 0xFF)
            else -> "%d".format(data[0] and 0xFF)
        }
    }

    // ------------------------------------------------------------------ 探测

    /**
     * 置信度：AB 系帧头没有魔数，靠字段语义判断。
     * type 必须为 1/2/3，且 len 与缓冲内的可见长度自洽。
     */
    override fun confidence(chunk: Bytes): Double {
        if (chunk.size < BtFrameCodec.HEADER_SIZE) return 0.0
        var score = 0.0
        for (i in 0 until minOf(4, chunk.size - 4)) {
            val type = chunk[i + 2] and 0xFF
            val len = chunk[i + 4] and 0xFF
            if (type in 1..3 && len <= 200) score += 0.25
        }
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 设备匹配：AB 系方案无法从设备名可靠区分，
     * 但音贝奇耳机名通常含 YINPAGE / FEEL / 型号关键字，命中则提高优先级。
     */
    override fun matchesDevice(deviceName: String?, serviceUuids: List<String>): Boolean = true

    // ------------------------------------------------------------------ 调试

    fun debugSnapshot(): String =
        "协议=$id 缓冲=${frames.bufferedBytes}B seq=${frames.currentSequence} " +
            "电量=${lastBattery.left.percent}/${lastBattery.right.percent}/${lastBattery.case.percent} " +
            "ANC=${lastNoise.name} EQ=${lastEq.name} 游戏=$lastGame 佩戴=$lastInEar 双连=$lastDual " +
            if (capabilities >= 0) "能力=${BtCapability.describe(capabilities)}" else "能力=未知"

    fun resetDecoder() = frames.reset()

    /** 通道复位：新会话开始前由 [com.yinpage.link.core.SessionCoordinator] 调用。 */
    override fun reset() = frames.reset()

    private fun hex(data: Bytes): String = data.joinToString(" ") { "%02X".format(it and 0xFF) }

    companion object {
        private const val TAG = "bluetrum"

        /** 无法解析时展示的原始字节数（贴完整原文便于对照抓包）。 */
        private const val MAX_UNPARSED_SHOW_BYTES = 48
    }
}
