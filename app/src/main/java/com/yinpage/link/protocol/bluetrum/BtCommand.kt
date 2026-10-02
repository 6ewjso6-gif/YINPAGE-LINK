package com.yinpage.link.protocol.bluetrum

import com.yinpage.link.protocol.Bytes

/**
 * ============================================================================
 *  中科蓝讯（Bluetrum）AB 系私有协议 —— 常量表
 * ============================================================================
 *  来源：官方 App `com.yscoco.yinpage` v1.4.27（MD5 D31F2892632776AC597F9680B13A43A8）
 *        反编译 `com.bluetrum.devicemanager.**`（157 个类）逐指令确证。
 *
 *  ✅ 已确证（有 code_off 级证据，见 doc/PROTOCOL.md）：
 *    - 帧格式：5 字节头 + payload，无 CRC
 *    - 命令码：下表全部来自 `Command` 类 static_values
 *    - 电量布局：payload = [左, 右, 盒]，每字节 bit7=充电中、bit0..6=电量
 *    - ANC 取值：OFF=0 / ON=1 / TRANSPARENCY=2
 *    - 能力位图 INFO_DEVICE_CAPABILITIES(0xFE)：bit0=TWS bit1=3D bit2=多点 bit3=ANC
 *
 *  ⚠️ 仍待真机抓包确认（不影响主流程）：
 *    - RFCOMM channel 号 / 是否使用自定义 SPP UUID（BLE 广播里带 POS_CUSTOM_SPP_UUID）
 *    - EQ 列表精确字节序
 * ============================================================================
 */
object BtCommand {

    // ---------------------------------------------------------------- 请求命令
    /** EQ 设置。payload = [bandCount, mode, gains...]（2 参构造器固定 10 段） */
    const val EQ = 0x20
    /** 媒体控制（播放/暂停/上一首/下一首） */
    const val MUSIC_CONTROL = 0x21
    /** 按键自定义 */
    const val KEY = 0x22
    /** 自动关机 */
    const val AUTO_SHUTDOWN = 0x23
    /** 恢复出厂设置 */
    const val FACTORY_RESET = 0x24
    /** 工作模式 */
    const val WORK_MODE = 0x25
    /** 入耳检测开关。payload = [0|1] */
    const val IN_EAR_DETECT = 0x26
    /** 设备信息查询。payload = [INFO_x, 0x00] 可重复拼接 */
    const val DEVICE_INFO = 0x27
    /** 查找耳机 */
    const val FIND_DEVICE = 0x2A
    /** 自动接听。payload = [0|1] */
    const val AUTO_ANSWER = 0x2B
    /** 蓝牙名设置 */
    const val BLUETOOTH_NAME = 0x2D
    /** LED 开关 */
    const val LED_MODE = 0x2E
    /** 清除配对记录 */
    const val CLEAR_PAIR_RECORD = 0x2F
    /** ANC 降噪增益。payload = [gain] */
    const val ANC_GAIN = 0x30
    /** 通透模式增益。payload = [gain] */
    const val TRANSPARENCY_GAIN = 0x31
    /** 3D 音效 */
    const val SOUND_EFFECT_3D = 0x32
    /** 一拖二 */
    const val ONE_DRAG_TWO = 0x33
    /** 低音增强。payload = [mode] */
    const val BASS_BOOST_MODE = 0x38
    /** 人声增强。payload = [mode] */
    const val VOCAL_BOOST_MODE = 0x39
    /** 音量档位 */
    const val VOLUME_GEAR = 0x3C
    /** 双设备连接。payload = [0|1] */
    const val DUAL_DEVICE = 0x3D
    /** 重置按键 */
    const val RESET_KEY = 0x3F
    /** 降噪强度（与 ANC 模式区分：0x41 在请求方向表示降噪强度） */
    const val NOISE_REDUCTION = 0x41
    /** 关机（与 0x41 同码，靠 type 区分方向） */
    const val SHUTDOWN = 0x41
    /** ANC 模式设置。payload = [ancMode] —— 注意 0x2C 也在 INFO_ANC_MODE 复用 */
    const val ANC_MODE = 0x2C
    /** 自定义命令 */
    const val CUSTOM = 0x40

    /** 指令类型（帧头第 3 字节）。 */
    const val TYPE_REQUEST = 1
    const val TYPE_RESPONSE = 2
    const val TYPE_NOTIFY = 3
}

/**
 * 设备信息子码（`DEVICE_INFO(0x27)` 的 payload 第一个字节）。
 * 用法：查询电量 = `[INFO_DEVICE_POWER, 0x00]`。
 */
object BtInfo {
    /** 电量。响应 payload = [左, 右, 盒] */
    const val DEVICE_POWER = 0x01
    /** 固件版本 */
    const val FIRMWARE_VERSION = 0x02
    /** 蓝牙名 */
    const val BLUETOOTH_NAME = 0x03
    /** EQ 设置 */
    const val EQ_SETTING = 0x04
    /** 按键设置 */
    const val KEY_SETTINGS = 0x05
    /** 设备音量 */
    const val DEVICE_VOLUME = 0x06
    /** 播放状态 */
    const val PLAY_STATE = 0x07
    /** 工作模式 */
    const val WORK_MODE = 0x08
    /** 入耳状态 */
    const val IN_EAR_STATUS = 0x09
    /** 语言设置 */
    const val LANGUAGE_SETTING = 0x0A
    /** 自动接听 */
    const val AUTO_ANSWER = 0x0B
    /** ANC 模式 */
    const val ANC_MODE = 0x0C
    /** 是否 TWS */
    const val IS_TWS = 0x0D
    /** TWS 是否已连接 */
    const val TWS_CONNECTED = 0x0E
    /** LED 开关 */
    const val LED_SWITCH = 0x0F
    /** 固件校验值 */
    const val FW_CHECKSUM = 0x10
    /** ANC 增益 */
    const val ANC_GAIN = 0x11
    /** 通透增益 */
    const val TRANSPARENCY_GAIN = 0x12
    /** ANC 增益档位数 */
    const val ANC_GAIN_NUM = 0x13
    /** 通透增益档位数 */
    const val TRANSPARENCY_GAIN_NUM = 0x14
    /** 全部 EQ 设置 */
    const val ALL_EQ_SETTINGS = 0x15
    /** 主耳侧 */
    const val MAIN_SIDE = 0x16
    /** 产品配色 */
    const val PRODUCT_COLOR = 0x17
    /** 一拖二 */
    const val ONE_DRAG_TWO = 0x19
    /** 低音增强模式 */
    const val BASS_BOOST_MODE = 0x26
    /** 人声增强模式 */
    const val VOCAL_BOOST_MODE = 0x27
    /** 通话状态 UI */
    const val CALL_STATUS_UI_INFO = 0x28
    /** 一键关闭按键 */
    const val ONE_CLOSE_KEY = 0x29
    /** 设备序列号 */
    const val DEVICE_SN = 0x31
    /** 提示音 */
    const val LISTEN_TIP = 0x32
    /** 音量上限 */
    const val VOLUME_LIMIT = 0x33
    /** 设备能力位图：bit0=TWS bit1=3D bit2=多点 bit3=ANC */
    const val DEVICE_CAPABILITIES = 0xFE
    /** 单包最大长度（覆盖默认 15 字节分包） */
    const val MAX_PACKET_SIZE = 0xFF
}

/** ANC 模式取值（确证：仅三档）。 */
object BtAnc {
    const val OFF = 0x00
    const val ON = 0x01
    const val TRANSPARENCY = 0x02

    /**
     * 本项目 UI 的四档 → 耳机实际的三档。
     *
     * 耳机只区分「关 / 开(降噪) / 通透」，而系统的四档控件需要四档。
     * 映射策略：
     *   OFF         -> OFF
     *   TRANSPARENT -> TRANSPARENCY
     *   NORMAL      -> ON（标准降噪 = 降噪开）
     *   DEEP        -> ON（深度降噪同样下发 ON，靠 ANC 增益区分强度）
     */
    fun toDevice(mode: com.yinpage.link.protocol.NoiseMode): Int = when (mode) {
        com.yinpage.link.protocol.NoiseMode.OFF -> OFF
        com.yinpage.link.protocol.NoiseMode.TRANSPARENT -> TRANSPARENCY
        com.yinpage.link.protocol.NoiseMode.NORMAL -> ON
        com.yinpage.link.protocol.NoiseMode.DEEP -> ON
        com.yinpage.link.protocol.NoiseMode.ADAPTIVE -> ON
        com.yinpage.link.protocol.NoiseMode.WIND -> ON
    }

    /** 耳机上报的三档 → UI 四档（降噪开时显示为"标准降噪"）。 */
    fun toUiMode(raw: Int): com.yinpage.link.protocol.NoiseMode = when (raw and 0xFF) {
        TRANSPARENCY -> com.yinpage.link.protocol.NoiseMode.TRANSPARENT
        ON -> com.yinpage.link.protocol.NoiseMode.NORMAL
        else -> com.yinpage.link.protocol.NoiseMode.OFF
    }

    /** 深度降噪对应的 ANC 增益（耳机支持多档增益时用最大档）。 */
    const val GAIN_MAX = 0xFF
    const val GAIN_MIN = 0x00
}

/** EQ 自定义档位的编码规则。 */
object BtEq {
    /** mode >= 0x20 表示自定义 EQ，customIndex = mode and 0xE0。 */
    const val CUSTOM_FLAG = 0x20
    /** 2 参构造器固定段数。 */
    const val DEFAULT_BAND_COUNT = 10

    /** UI 的 EQ 档位 → 耳机 mode 值（内置预设 0..N）。 */
    fun toDevice(mode: com.yinpage.link.protocol.EqMode): Int = when (mode) {
        com.yinpage.link.protocol.EqMode.BALANCED -> 0x00
        com.yinpage.link.protocol.EqMode.BASS -> 0x01
        com.yinpage.link.protocol.EqMode.VOCAL -> 0x02
        com.yinpage.link.protocol.EqMode.TREBLE -> 0x03
        com.yinpage.link.protocol.EqMode.LIVE -> 0x04
        com.yinpage.link.protocol.EqMode.GAME -> 0x05
    }

    fun toUiMode(raw: Int): com.yinpage.link.protocol.EqMode {
        val v = raw and 0xFF
        if (v >= CUSTOM_FLAG) return com.yinpage.link.protocol.EqMode.BALANCED
        return com.yinpage.link.protocol.EqMode.fromCode(v)
    }
}

/** 设备能力位图。 */
object BtCapability {
    const val TWS = 1 shl 0
    const val SOUND_3D = 1 shl 1
    const val MULTI_POINT = 1 shl 2
    const val ANC = 1 shl 3

    fun describe(bits: Int): String = buildList {
        if (bits and TWS != 0) add("TWS")
        if (bits and SOUND_3D != 0) add("3D音效")
        if (bits and MULTI_POINT != 0) add("多点连接")
        if (bits and ANC != 0) add("主动降噪")
    }.joinToString("、").ifEmpty { "无" }
}

/** 电量字节编解码（确证布局）。 */
object BtPower {
    /**
     * 解析单只耳机的电量字节。
     * bit7 = 充电中，bit0..6 = 电量（0..127）。
     */
    fun decode(raw: Int): com.yinpage.link.protocol.BatteryLevel {
        val v = raw and 0xFF
        val charging = (v and 0x80) != 0
        val percent = v and 0x7F
        return com.yinpage.link.protocol.BatteryLevel(
            percent = percent.takeIf { it in 0..100 },
            charging = charging,
        )
    }

    /** payload = [左, 右, 盒]。 */
    fun decodeState(payload: Bytes): com.yinpage.link.protocol.BatteryState? {
        if (payload.size < 3) return null
        return com.yinpage.link.protocol.BatteryState(
            left = decode(payload[0]),
            right = decode(payload[1]),
            case = decode(payload[2]),
        )
    }
}
