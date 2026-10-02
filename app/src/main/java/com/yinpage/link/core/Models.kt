package com.yinpage.link.core

import com.yinpage.link.config.AppConfig
import com.yinpage.link.protocol.PodState

/**
 * ============================================================================
 *  UI 层与 core 层共享的模型  ——  冻结契约（v1）
 * ============================================================================
 */

/** 设备列表项（已配对或扫描发现的设备）。 */
data class BluetoothDeviceItem(
    val name: String,
    val address: String,
    val bonded: Boolean,
    /** 扫描到的信号强度；已配对但未在扫描结果中出现时为 null */
    val rssi: Int? = null,
    /** 该地址是否被识别为音贝奇/疑似目标耳机 */
    val suspectedTarget: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "未知设备" }
    val shortAddress: String get() = address.uppercase()
}

/** 界面总状态：一个对象喂给整个 UI，避免多处 collect 造成状态不同步。 */
data class PodUiState(
    val pod: PodState = PodState(),
    val config: AppConfig = AppConfig(),
    val debugLines: List<String> = emptyList(),
    val permissionGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val scanning: Boolean = false,
    /** 设备列表 */
    val devices: List<BluetoothDeviceItem> = emptyList(),
    /** 一次性提示消息（Snackbar 用），消费后由 UI 调 clearToast() */
    val toast: String? = null,
)
