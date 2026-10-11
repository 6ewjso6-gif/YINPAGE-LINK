package com.yinpage.link

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.yinpage.link.config.ConfigManager
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog
import com.yinpage.link.core.SessionCoordinator
import com.yinpage.link.protocol.PodLog
import com.yinpage.link.protocol.ProtocolRegistry
import com.yinpage.link.protocol.bluetrum.BluetrumCodec
import com.yinpage.link.service.BluetoothStateReceiver
import com.yinpage.link.transport.TransportEnv
import com.yinpage.link.transport.TransportFactoryImpl

/** 通知渠道 id：前台保活通知与低电量提醒共用。 */
const val NOTIFICATION_CHANNEL_ID = "yinpage_link"

/**
 * ============================================================================
 *  Application 入口：装配「传输 + 协议 + 会话」三件套
 * ============================================================================
 *  这里只做装配与轻量初始化，任何耗时操作（扫描、连接、IO）都不在此发生。
 *  顺序有讲究：
 *    1. TransportEnv.install  —— BLE connectGatt 需要 Context；
 *    2. ConfigManager.init    —— AppState.init 会读配置；
 *    3. ProtocolRegistry      —— AppState.connect 需要候选协议；
 *    4. AppState.install+init —— 注入协调器并建立 UI 状态。
 * ============================================================================
 */
class YinpageApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // S6：直接把 Application Context 注入 core，替代原先的 ActivityThread 反射。
            AppState.attach(this)
            TransportEnv.install(this)
            ConfigManager.init(this)

            // 协议层日志桥接到应用内的事件日志（调试面板可见）
            PodLog.default = object : PodLog {
                override fun debug(tag: String, message: String) = EventLog.debug(tag, message)
                override fun info(tag: String, message: String) = EventLog.info(tag, message)
            }

            // 协议注册：新增品牌只需在此追加一行（协议实现不依赖 UI / 传输层）。
            // bluetrum-ab 是逆向官方 App 得到的真实协议（中科蓝讯 AB 系）。
            ProtocolRegistry.register(BluetrumCodec())

            // 会话协调器：AUTO 回退、握手、异常翻译都在它内部完成。
            AppState.install(SessionCoordinator(TransportFactoryImpl()))
            AppState.init()

            ensureNotificationChannel()
            // F14：蓝牙状态监听改为**动态注册**（见下），替代 manifest 静态注册。
            registerBluetoothStateReceiver()
            EventLog.info(
                "启动",
                "Application 装配完成，已注册协议=[${ProtocolRegistry.all().joinToString { it.id }}]",
            )
        } catch (error: Throwable) {
            // Application 崩溃会让整个 App 无法启动，这里兜底记录后继续。
            EventLog.info("启动", "初始化异常：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** F14：动态注册的蓝牙状态接收器（进程级存活，持有引用避免被 GC）。 */
    private var bluetoothStateReceiver: BluetoothStateReceiver? = null

    /**
     * F14：动态注册蓝牙状态接收器。
     *
     * 为何不静态注册：ACL / STATE_CHANGED / HFP / A2DP 四个 action 均为**隐式广播**，
     * Android 8+ 对 manifest 注册的隐式广播有后台限制（不在豁免清单内）→ 断连同步
     * 可能收不到，且任何蓝牙插拔都会尝试拉起本进程。动态注册 + NOT_EXPORTED
     * 既能稳定收到系统广播，又不会被第三方 App 注入（这些广播只有系统 UID 会发）。
     */
    private fun registerBluetoothStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        val receiver = BluetoothStateReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        bluetoothStateReceiver = receiver
    }

    /** 创建前台服务 / 低电量提醒共用的通知渠道（幂等）。 */
    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "耳机连接状态",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "连接保活通知与耳机低电量提醒"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        EventLog.info("通知", "已创建通知渠道 $NOTIFICATION_CHANNEL_ID")
    }
}
