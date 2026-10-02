package com.yinpage.link.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.yinpage.link.NOTIFICATION_CHANNEL_ID
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog
import com.yinpage.link.protocol.BatteryState
import com.yinpage.link.protocol.PodState

/**
 * ============================================================================
 *  连接保活前台服务（foregroundServiceType=connectedDevice）
 * ============================================================================
 *  作用只有一个：连接期间让进程不被系统回收，保证 SPP/BLE 长连接与轮询不断。
 *
 *  - Android 14+ 的 connectedDevice 类型需要在 Manifest 声明
 *    FOREGROUND_SERVICE_CONNECTED_DEVICE（已声明），并且调用方需已持有
 *    BLUETOOTH_CONNECT 权限，否则 startForeground 会失败。
 *  - 通知内容 = 耳机名 + 电量摘要，直接读 [AppState.pod]（单一数据源）。
 *  - onDestroy **不主动断开**连接：断连由 UI 决定，服务只负责保活。
 * ============================================================================
 */
class PodConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            EventLog.info(TAG, "前台服务已启动（保活中）")
            START_STICKY
        } catch (error: Throwable) {
            // 典型原因：没有 BLUETOOTH_CONNECT 权限 / 系统限制后台启动前台服务。
            EventLog.info(TAG, "前台服务启动失败：${error.message ?: error.javaClass.simpleName}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        EventLog.info(TAG, "前台服务销毁（不主动断开连接）")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pod = AppState.pod.value
        val title = pod.device?.name?.takeIf { it.isNotBlank() } ?: "YINPAGE-LINK"
        val text = batterySummary(pod)

        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            // 使用框架内置 drawable，保证不依赖 res/ 下由 UI 模块维护的图标资源；
            // 后续若要换成专用耳机图标，替换成 R.drawable.xxx 即可。
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        // 用启动器 Intent，避免 service 层反向依赖 ui.MainActivity。
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    /** 例：`已连接 · 左 82% · 右 80% · 盒 55%`。 */
    private fun batterySummary(pod: PodState): String {
        val battery: BatteryState = pod.battery
        val parts = buildList {
            battery.left.percent?.let { add("左 $it%${if (battery.left.charging) "⚡" else ""}") }
            battery.right.percent?.let { add("右 $it%${if (battery.right.charging) "⚡" else ""}") }
            battery.case.percent?.let { add("盒 $it%${if (battery.case.charging) "⚡" else ""}") }
        }
        return if (parts.isEmpty()) {
            if (pod.connected) "已连接 · 电量待上报" else "未连接"
        } else {
            parts.joinToString(" · ")
        }
    }

    private companion object {
        const val TAG = "保活服务"
        const val NOTIFICATION_ID = 1
    }
}
