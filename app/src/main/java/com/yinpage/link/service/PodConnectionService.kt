package com.yinpage.link.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.yinpage.link.NOTIFICATION_CHANNEL_ID
import com.yinpage.link.core.AppState
import com.yinpage.link.core.EventLog
import com.yinpage.link.protocol.BatteryState
import com.yinpage.link.protocol.NoiseMode
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
        // 通知栏的降噪按钮走这里：循环切换档位后刷新通知
        if (intent?.action == ACTION_CYCLE_NOISE) {
            cycleNoise()
            refresh(applicationContext)
            return START_STICKY
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            running = true
            EventLog.info(TAG, "前台服务已启动（保活中）")
            START_STICKY
        } catch (error: Throwable) {
            // 典型原因：没有 BLUETOOTH_CONNECT 权限 / 系统限制后台启动前台服务。
            EventLog.info(TAG, "前台服务启动失败：${error.message ?: error.javaClass.simpleName}")
            running = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 循环切换降噪：关闭 → 通透 → 标准 → 深度 → 关闭。 */
    private fun cycleNoise() {
        val next = when (AppState.pod.value.noise) {
            NoiseMode.OFF -> NoiseMode.TRANSPARENT
            NoiseMode.TRANSPARENT -> NoiseMode.NORMAL
            NoiseMode.NORMAL -> NoiseMode.DEEP
            else -> NoiseMode.OFF
        }
        EventLog.info(TAG, "通知按钮切换降噪：${next.labelZh}")
        AppState.setNoise(next)
    }

    override fun onDestroy() {
        running = false
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

        // 降噪快捷按钮：直接在通知里循环切换 关闭 → 通透 → 标准 → 深度
        // （无 root 的前提下，这是最接近"控制中心耳机控件"的体验）
        builder.addAction(
            Notification.Action.Builder(
                null,
                nextNoiseLabel(pod.noise),
                noiseActionIntent(this),
            ).build(),
        )
        return builder.build()
    }

    /** 下一个降噪档位的按钮文案。 */
    private fun nextNoiseLabel(current: NoiseMode): String = "切到" + when (current) {
        NoiseMode.OFF -> "通透"
        NoiseMode.TRANSPARENT -> "标准降噪"
        NoiseMode.NORMAL -> "深度降噪"
        else -> "关闭"
    }

    /** 例：`已连接 · 左 82% · 右 80% · 盒 55%`。 */
    private fun batterySummary(pod: PodState): String {
        val battery: BatteryState = pod.battery
        val parts = buildList {
            battery.left.percent?.let { add("左 $it%${if (battery.left.charging) "⚡" else ""}") }
            battery.right.percent?.let { add("右 $it%${if (battery.right.charging) "⚡" else ""}") }
            battery.case.percent?.let { add("盒 $it%${if (battery.case.charging) "⚡" else ""}") }
        }
        val noise = pod.noise.labelZh
        return if (parts.isEmpty()) {
            "${if (pod.connected) "已连接" else "未连接"} · $noise · 电量待上报"
        } else {
            "$noise · " + parts.joinToString(" · ")
        }
    }

    companion object {
        private const val TAG = "保活服务"
        private const val NOTIFICATION_ID = 1

        /** 服务是否在运行（决定要不要刷新通知）。 */
        @Volatile
        private var running = false

        /**
         * 启动保活服务（幂等）。
         * 连接耳机后由 [AppState] 调用；启动失败只记日志，不影响连接本身。
         */
        fun start(context: Context?) {
            if (context == null) return
            runCatching {
                val intent = Intent(context, PodConnectionService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { EventLog.debug(TAG, "启动保活服务失败：${it.message}") }
        }

        /**
         * 刷新保活通知内容。
         * 由 [AppState] 在电量/降噪变化时调用 —— 这样用户拉下通知栏就能看到
         * 实时电量并一键切降噪，弥补无 root 拿不到系统耳机卡片的缺口。
         */
        fun refresh(context: Context?) {
            if (!running || context == null) return
            runCatching {
                val pod = AppState.pod.value
                val manager = context.getSystemService(NotificationManager::class.java) ?: return
                // 通知已取消（用户划掉或系统清理）时不再重建，避免"通知复活"
                manager.notify(NOTIFICATION_ID, buildNotificationStatic(context, pod))
            }.onFailure { EventLog.debug(TAG, "刷新通知失败：${it.message}") }
        }

        /** 与实例方法同逻辑，供静态刷新使用。 */
        private fun buildNotificationStatic(context: Context, pod: PodState): Notification {
            val title = pod.device?.name?.takeIf { it.isNotBlank() } ?: "YINPAGE-LINK"
            val battery = pod.battery
            val parts = buildList {
                battery.left.percent?.let { add("左 $it%${if (battery.left.charging) "⚡" else ""}") }
                battery.right.percent?.let { add("右 $it%${if (battery.right.charging) "⚡" else ""}") }
                battery.case.percent?.let { add("盒 $it%${if (battery.case.charging) "⚡" else ""}") }
            }
            val text = if (parts.isEmpty()) {
                "${if (pod.connected) "已连接" else "未连接"} · ${pod.noise.labelZh} · 电量待上报"
            } else {
                "${pod.noise.labelZh} · " + parts.joinToString(" · ")
            }

            val builder = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)

            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            builder.addAction(
                Notification.Action.Builder(null, nextNoiseLabelStatic(pod.noise), noiseActionIntent(context)).build(),
            )
            return builder.build()
        }

        private fun nextNoiseLabelStatic(current: NoiseMode): String = "切到" + when (current) {
            NoiseMode.OFF -> "通透"
            NoiseMode.TRANSPARENT -> "标准降噪"
            NoiseMode.NORMAL -> "深度降噪"
            else -> "关闭"
        }

        /** 降噪切换的 PendingIntent：发给本服务，由 [onStartCommand] 处理。 */
        private fun noiseActionIntent(context: Context): PendingIntent {
            val intent = Intent(context, PodConnectionService::class.java).apply {
                action = ACTION_CYCLE_NOISE
            }
            return PendingIntent.getService(
                context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** 通知按钮的 action。 */
        const val ACTION_CYCLE_NOISE = "com.yinpage.link.action.CYCLE_NOISE"
    }
}
