package com.yinpage.link.transport

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yinpage.link.core.TransportFactory
import com.yinpage.link.core.TransportKind
import com.yinpage.link.protocol.MissingPermissionException
import com.yinpage.link.protocol.PodTransport
import com.yinpage.link.protocol.PodTransportException
import com.yinpage.link.protocol.TransportIoException

/**
 * [TransportFactory] 默认实现：把 [TransportKind] 映射到具体传输通道。
 *
 * 注意 AUTO 的「先 RFCOMM 失败再 BLE」回退逻辑**不在这里**，
 * 而在 `SessionCoordinator`（它需要拿到每次尝试的失败原因来拼人话提示）。
 * 这里对 AUTO 只给出首选通道。
 */
class TransportFactoryImpl : TransportFactory {

    override fun create(kind: TransportKind, address: String): PodTransport = when (kind) {
        TransportKind.RFCOMM -> RfcommTransport(address)
        TransportKind.BLE -> BleGattTransport(address)
        TransportKind.AUTO -> RfcommTransport(address)
    }
}

/**
 * 传输层公共环境：应用上下文 / 蓝牙适配器 / 权限检查 / GATT status 翻译。
 *
 * 为什么需要它：[TransportFactory.create] 的签名只有 (kind, address)，拿不到 Context；
 * 而 BLE 的 connectGatt 必须要 Context。这里的上下文优先使用
 * `YinpageApp.onCreate()` 显式注入的实例，仅在未注入时（例如单元测试）
 * 才退回与 `AppState` 相同的反射兜底（`ActivityThread.currentApplication()`）。
 *
 * 这里**不涉及任何蓝牙隐藏 API**，只处理 Context 获取与权限检查。
 */
internal object TransportEnv {

    @Volatile
    private var installedContext: Context? = null

    /** 由 `YinpageApp.onCreate()` 调用。 */
    fun install(context: Context) {
        installedContext = context.applicationContext
    }

    fun context(): Context? = installedContext ?: reflectApplicationContext()

    private fun reflectApplicationContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentApplication").invoke(null) as? Context
        current?.applicationContext
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun adapter(): BluetoothAdapter? = runCatching {
        val ctx = context()
        if (ctx != null) {
            val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.let { return@runCatching it }
        }
        BluetoothAdapter.getDefaultAdapter()
    }.getOrNull()

    /** 缺少 BLUETOOTH_CONNECT 时抛 [MissingPermissionException]，避免直接吃 SecurityException。 */
    fun requireConnectPermission(context: Context) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!granted) throw MissingPermissionException("BLUETOOTH_CONNECT（附近的设备）")
    }

    /** 把 GATT 回调里的异常统一翻译成协议层异常。 */
    fun mapGattFailure(error: Throwable, prefix: String): PodTransportException = when (error) {
        is PodTransportException -> error
        is SecurityException -> MissingPermissionException("BLUETOOTH_CONNECT")
        else -> TransportIoException("$prefix：${error.message ?: error.javaClass.simpleName}", error)
    }

    /**
     * GATT / HCI status 码转人话。常量用字面量写，避免引用 API 31 才公开的
     * `BluetoothGatt.GATT_CONN_*`（保证在 minSdk 27 上不会触发类解析问题）。
     */
    fun describeGattStatus(status: Int): String = when (status) {
        0 -> "成功"
        2 -> "读取无权限"
        3 -> "写入无权限"
        5 -> "认证不足（可能需要重新配对）"
        6 -> "请求不被支持"
        7 -> "偏移非法"
        8 -> "连接超时 0x08（耳机无响应或已关机）"
        13 -> "属性长度非法"
        15 -> "加密不足（可能需要重新配对）"
        19 -> "对端主动断开 0x13"
        22 -> "本机主动断开 0x16"
        34 -> "链路层超时 0x22"
        62 -> "连接建立失败 0x3E"
        133 -> "连接失败/设备忙 0x85(133)：信号弱、耳机已被官方 App 占用或未回连"
        143 -> "连接拥塞 0x8F"
        257 -> "GATT 失败 0x101"
        else -> "未知 status=$status（0x%02X）".format(status and 0xFFFF)
    }
}
