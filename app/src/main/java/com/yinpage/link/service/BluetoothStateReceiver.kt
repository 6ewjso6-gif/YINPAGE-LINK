package com.yinpage.link.service

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog

/**
 * ============================================================================
 *  系统蓝牙状态广播接收器
 * ============================================================================
 *  监听 ACL 连接 / 断开、适配器开关、HFP 与 A2DP 连接变化，
 *  用于在系统层面耳机状态变化时刷新 UI 可观察状态。
 *
 *  约束：
 *   - 只做「读广播 + 刷状态 + 打日志」，绝不做连接、扫描等耗时动作；
 *   - onReceive 运行在主线程，所有设备信息读取都用 runCatching 包住
 *     （Android 12+ 读 name/address 需要 BLUETOOTH_CONNECT）。
 *
 *  ⚠️ 能力边界：本接收器只负责「把系统广播翻译成 AppState 状态更新」。
 *  蓝牙开关与设备断开都会同步到 UI，避免出现"系统已断开但界面仍显示已连接"的幽灵状态。
 * ============================================================================
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                EventLog.info(TAG, "蓝牙适配器状态变化：${describeAdapterState(state)}")
                // 只有真正落到 OFF/ON 才刷新，TURNING_* 中间态不处理
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_ON) {
                    runCatching { AppState.refreshBluetoothState() }
                        .onFailure { EventLog.debug(TAG, "refreshBluetoothState 失败：${it.message}") }
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                EventLog.info(TAG, "ACL 已连接：${describeDevice(intent)}")
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                EventLog.info(TAG, "ACL 已断开：${describeDevice(intent)}")
                // 若是当前正在控制的耳机掉线，把会话状态同步为已断开
                deviceAddress(intent)?.let { address ->
                    runCatching { AppState.onDeviceDisconnected(address) }
                        .onFailure { EventLog.debug(TAG, "onDeviceDisconnected 失败：${it.message}") }
                }
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                // HFP 断开 ≠ SPP 控制通道断开（与下方 A2DP 分支同一结论）：
                // TWS 在通话结束 / 音频 profile 释放时 HFP 会先掉，而 SPP 控制通道仍然活着。
                // 旧实现在这里调 onDeviceDisconnected() 拆 SPP 会话，导致会话被无故拆掉。
                EventLog.info(TAG, "HFP 通话音频状态=${describeProfileState(state)} 设备=${describeDevice(intent)}")
            }

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                EventLog.info(TAG, "A2DP 媒体音频状态=${describeProfileState(state)} 设备=${describeDevice(intent)}")
                // A2DP 断开不代表 SPP 控制通道断开（反之亦然），只记录不置断连态
            }

            else -> EventLog.debug(TAG, "未处理的广播 $action")
        }

        // 统一触发一次 UI 同步（权限在系统设置里被改动时用得上）。
        runCatching { AppState.refreshPermissions() }
            .onFailure { EventLog.debug(TAG, "refreshPermissions 失败：${it.message}") }
    }

    /** 从广播里取出设备地址；读不到就返回 null（Android 12+ 需要 BLUETOOTH_CONNECT）。 */
    private fun deviceAddress(intent: Intent): String? = runCatching {
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        }
        device?.address
    }.getOrNull()

    private fun describeDevice(intent: Intent): String {
        val device = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
            }
        }.getOrNull() ?: return "未知设备"

        val name = runCatching { device.name }.getOrNull() ?: "无名"
        val address = runCatching { device.address }.getOrNull() ?: "?"
        return "$name($address)"
    }

    private fun describeAdapterState(state: Int): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "已关闭 STATE_OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "正在开启 STATE_TURNING_ON"
        BluetoothAdapter.STATE_ON -> "已开启 STATE_ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "正在关闭 STATE_TURNING_OFF"
        else -> "未知 state=$state"
    }

    private fun describeProfileState(state: Int): String = when (state) {
        BluetoothProfile.STATE_DISCONNECTED -> "已断开"
        BluetoothProfile.STATE_CONNECTING -> "连接中"
        BluetoothProfile.STATE_CONNECTED -> "已连接"
        BluetoothProfile.STATE_DISCONNECTING -> "断开中"
        else -> "state=$state"
    }

    private companion object {
        const val TAG = "蓝牙广播"
    }
}
