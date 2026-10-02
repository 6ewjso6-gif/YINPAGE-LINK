package com.yinpage.link.enhance

import android.bluetooth.BluetoothDevice

/**
 * ============================================================================
 *  系统侧蓝牙信息读取（免 root，公开 API）
 * ============================================================================
 *  Android 14（API 34）给 `BluetoothDevice` 增加了公开方法 `getBatteryLevel()`，
 *  可以读到系统从**标准蓝牙电量服务（GATT 0x180F Battery Service）**解析出的
 *  电量值——不需要任何私有协议。
 *
 *  对本项目的价值：
 *    - 如果音贝奇耳机的 BLE 广播里带了标准电量服务，系统就能读到真实电量，
 *      这比我们走 SPP 私有协议更稳（协议万一不匹配还有兜底）；
 *    - 走公开 API，不涉及反射隐藏接口（这里用反射只是为了**在不提高 minSdk 的
 *      前提下调用新 API**，属于标准做法）。
 *
 *  ⚠️ 说明：这个值来自系统缓存，可能比 SPP 直读滞后；因此只作为**兜底**，
 *  优先仍用 SPP 协议读到的值。
 * ============================================================================
 */
object SystemBluetoothInfo {

    /** 系统缓存里该设备的电量（0..100）；读不到返回 null。 */
    fun batteryLevel(device: BluetoothDevice?): Int? {
        if (device == null) return null
        return runCatching {
            // Android 14+ 公开 API：BluetoothDevice.getBatteryLevel()
            val method = device.javaClass.getMethod("getBatteryLevel")
            val value = method.invoke(device) as? Int ?: return null
            // 约定：BluetoothDevice.BATTERY_LEVEL_UNKNOWN = -1
            if (value in 0..100) value else null
        }.getOrNull()
    }

    /** 该设备的蓝牙类别是否为"音频/耳机"（用于设备列表排序与识别）。 */
    fun isAudioDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return runCatching {
            val major = device.bluetoothClass?.majorDeviceClass
            // 0x0400 = AUDIO_VIDEO
            major == 0x0400
        }.getOrDefault(false)
    }
}
