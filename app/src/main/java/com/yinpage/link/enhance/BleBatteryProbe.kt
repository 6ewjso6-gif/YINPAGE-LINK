package com.yinpage.link.enhance

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * ============================================================================
 *  BLE 标准电量服务（BAS, 0x180F）探测与读取
 * ============================================================================
 *  背景：系统蓝牙设置页的电量来自哪里？
 *
 *  免 root 的前提下只有一条**理论可行**的路：
 *  AOSP 蓝牙栈自带 BAS(Battery Service) 客户端
 *  （`packages/modules/Bluetooth/.../bas/BatteryService.java`），
 *  当耳机在 **BLE/GATT 侧暴露标准电量服务 0x180F** 时，
 *  系统会自己读到电量并调 `AdapterService.setBatteryLevel(..., isBas=true)`，
 *  随后系统 UI（蓝牙设置页、状态栏）就有机会显示。
 *
 *  本类做两件事：
 *    1. **探测**：连接耳机 GATT，枚举服务，看有没有 0x180F；
 *    2. **读取**：读 0x2A19（Battery Level）特征值，拿到 0-100 的电量。
 *
 *  这条路径完全走公开 API，不需要 root、不需要 Shizuku、不碰任何系统进程。
 *  它能达到的效果上限是"让系统知道电量"，**不等于**能让耳机进融合设备中心。
 *
 *  ⚠️ 如果耳机不在 BLE 侧暴露 0x180F（很多白牌 TWS 只在私有 SPP 协议里报电量），
 *  这个探测会返回 null，属于正常结果，不影响其它功能。
 * ============================================================================
 */
object BleBatteryProbe {

    /** 标准 Battery Service */
    private const val UUID_BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"

    /** 标准 Battery Level 特征值 */
    private const val UUID_BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    /** 探测结果。 */
    data class Result(
        /** 是否发现标准电量服务 */
        val hasBatteryService: Boolean,
        /** 电量（0..100），读不到为 null */
        val level: Int?,
        /** 枚举到的全部服务 UUID（用于诊断） */
        val serviceUuids: List<String>,
    )

    /**
     * 连接 GATT 并探测标准电量服务。
     *
     * @return 探测结果；连接失败返回 null（不抛异常）
     */
    suspend fun probe(context: Context, device: BluetoothDevice): Result? {
        var gatt: BluetoothGatt? = null
        return try {
            withTimeout(GATT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    // 阶段状态：连接成功后依次 服务发现 → 读取电量 → 返回结果
                    var discoveredServices = false
                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                            if (newState != BluetoothProfile.STATE_CONNECTED) {
                                if (cont.isActive) cont.resume(null)
                                return
                            }
                            runCatching { g?.discoverServices() }
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS || g == null) {
                                if (cont.isActive) cont.resume(null)
                                return
                            }
                            discoveredServices = true
                            // 找标准电量服务；不存在时直接返回"无此服务"
                            val services = runCatching { g!!.services }.getOrNull().orEmpty()
                            val uuids = services.map { it.uuid.toString().lowercase() }
                            val batteryService = services.firstOrNull {
                                it.uuid.toString().equals(UUID_BATTERY_SERVICE, ignoreCase = true)
                            }
                            val levelChar = batteryService?.characteristics?.firstOrNull {
                                it.uuid.toString().equals(UUID_BATTERY_LEVEL, ignoreCase = true)
                            }
                            if (levelChar == null) {
                                if (cont.isActive) {
                                    cont.resume(
                                        Result(
                                            hasBatteryService = batteryService != null,
                                            level = null,
                                            serviceUuids = uuids,
                                        ),
                                    )
                                }
                                return
                            }
                            // 发起异步读，值在 onCharacteristicRead 里拿
                            @Suppress("DEPRECATION")
                            val ok = runCatching { g!!.readCharacteristic(levelChar) }.getOrDefault(false)
                            if (!ok && cont.isActive) {
                                cont.resume(Result(hasBatteryService = true, level = null, serviceUuids = uuids))
                            }
                        }

                        // API < 33 用旧签名；API >= 33 框架会先填 value 再回调此旧签名
                        @Suppress("DEPRECATION")
                        override fun onCharacteristicRead(
                            g: BluetoothGatt?,
                            characteristic: BluetoothGattCharacteristic,
                            status: Int,
                        ) {
                            if (!discoveredServices || !cont.isActive) return
                            val value = runCatching { characteristic.value }.getOrNull()
                            val level = if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.isNotEmpty()) {
                                (value[0].toInt() and 0xFF).takeIf { it in 0..100 }
                            } else {
                                null
                            }
                            val services = runCatching { g?.services }.getOrNull().orEmpty()
                            cont.resume(
                                Result(
                                    hasBatteryService = true,
                                    level = level,
                                    serviceUuids = services.map { it.uuid.toString().lowercase() },
                                ),
                            )
                        }
                    }
                    gatt = runCatching {
                        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    }.getOrNull()
                    if (gatt == null && cont.isActive) cont.resume(null)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            null
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    private const val GATT_TIMEOUT_MS = 12_000L
}
