package com.yinpage.link.module.ipc

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.yinpage.link.module.hook.ModuleLog
import java.util.concurrent.atomic.AtomicReference

/**
 * ============================================================================
 *  模块内部跨进程通信
 * ============================================================================
 *  五个被 Hook 的进程各自独立，耳机数据是在 `com.android.bluetooth` 进程里
 *  通过 SPP 读到的，但消费方分散在：
 *    - `com.milink.service`      → 电量 / ANC 状态
 *    - `com.android.settings`    → 高级耳机页
 *    - `com.android.systemui`    → 设备中心卡片
 *    - `com.xiaomi.bluetooth`    → 连接通知 / 灵动岛
 *
 *  因此用显式广播（带 setPackage，避免泄漏到无关应用）在进程间同步状态。
 *
 *  ⚠️ 所有 action 都带 `com.yinpage.link.action.` 前缀，且广播**必须** setPackage。
 * ============================================================================
 */
object ModuleIpc {

    private const val TAG = "IPC"

    // ---------------------------------------------------------------- Actions

    /** 蓝牙进程 → 各消费进程：耳机状态快照（电量 / ANC / 佩戴）。 */
    const val ACTION_PODS_STATE = "com.yinpage.link.action.PODS_STATE"

    /** 系统 UI → 蓝牙进程：用户在四档 ANC 控件上做了选择。 */
    const val ACTION_ANC_SELECT = "com.yinpage.link.action.ANC_SELECT"

    /** 任意 → 蓝牙进程：请求立刻重新查询一次耳机状态。 */
    const val ACTION_REFRESH_STATUS = "com.yinpage.link.action.REFRESH_STATUS"

    /** 蓝牙进程 → 自身：连接状态变化（内部用）。 */
    const val ACTION_PODS_CONNECTED = "com.yinpage.link.action.PODS_CONNECTED"
    const val ACTION_PODS_DISCONNECTED = "com.yinpage.link.action.PODS_DISCONNECTED"

    // ---------------------------------------------------------------- Extras

    const val EXTRA_ADDRESS = "address"
    const val EXTRA_NAME = "device_name"
    const val EXTRA_LEFT = "left"
    const val EXTRA_RIGHT = "right"
    const val EXTRA_CASE = "case"
    const val EXTRA_LEFT_CHARGING = "left_charging"
    const val EXTRA_RIGHT_CHARGING = "right_charging"
    const val EXTRA_CASE_CHARGING = "case_charging"
    const val EXTRA_ANC = "anc"
    const val EXTRA_CONNECTED = "connected"

    /** 自家 ANC 码（与 HyperOriG 保持一致，便于对照参考实现）。 */
    const val ANC_OFF = 1
    const val ANC_TRANSPARENCY = 2
    const val ANC_NC = 3

    /** 未知电量。 */
    const val UNKNOWN_LEVEL = -1

    // ---------------------------------------------------------------- 状态

    /**
     * 耳机状态快照。`com.android.bluetooth` 进程写入，其它进程读取。
     * 对未连接项用 [UNKNOWN_LEVEL] 表示，消费方负责翻译成各系统 API 要求的编码
     * （MiLink 用 -1、Settings 用 255）。
     */
    data class PodSnapshot(
        val address: String = "",
        val name: String = "",
        val connected: Boolean = false,
        val left: Int = UNKNOWN_LEVEL,
        val right: Int = UNKNOWN_LEVEL,
        val case: Int = UNKNOWN_LEVEL,
        val leftCharging: Boolean = false,
        val rightCharging: Boolean = false,
        val caseCharging: Boolean = false,
        /** 自家 ANC 码：1=OFF / 2=通透 / 3=降噪 */
        val anc: Int = ANC_OFF,
    ) {
        /**
         * MiLink 侧的电量列表，**顺序已确证**：
         * `[盒, 左, 右, 盒充电, 左充电, 右充电]`，未连接用 -1。
         */
        fun miLinkBatteryLevels(): List<Int> = listOf(
            level(case), level(left), level(right),
            charging(caseCharging), charging(leftCharging), charging(rightCharging),
        )

        /** MiLink 三态：0=OFF / 1=NC / 2=Transparency。 */
        fun miLinkAncState(): Int = when (anc) {
            ANC_NC -> 1
            ANC_TRANSPARENCY -> 2
            else -> 0
        }

        /** 左右耳取较小值作为"整机电量"，供 getHeadsetPropertyBlock 用。 */
        fun headsetPropertyBlock(): Int {
            val candidates = listOf(left, right).filter { it != UNKNOWN_LEVEL }
            return candidates.minOrNull() ?: UNKNOWN_LEVEL
        }

        /**
         * Settings 侧 16 字段 payload 中的电量编码：
         * **255 = 未连接，充电则 `值 or 128`**（已确证）。
         */
        fun settingsBatteryValue(level: Int, charging: Boolean): String {
            if (level == UNKNOWN_LEVEL) return "255"
            val v = level.coerceIn(0, 100)
            // 注意括号：`if (…) v or 128 else v` 会被解析成 `if (…) (v or 128 else v)`
            val encoded = if (charging) (v or 128) else v
            return encoded.toString()
        }

        private fun level(v: Int): Int = v
        private fun charging(b: Boolean): Int = if (b) 1 else 0
    }

    /** 全局快照（每个进程各持一份，由广播更新）。 */
    private val snapshotRef = AtomicReference(PodSnapshot())

    fun snapshot(): PodSnapshot = snapshotRef.get()

    fun updateSnapshot(snapshot: PodSnapshot) {
        snapshotRef.set(snapshot)
    }

    // ---------------------------------------------------------------- 发送

    /** 发送状态快照给所有消费进程。 */
    fun broadcastState(context: Context?, snapshot: PodSnapshot) {
        if (context == null) return
        updateSnapshot(snapshot)
        val intent = Intent(ACTION_PODS_STATE).apply {
            putExtra(EXTRA_ADDRESS, snapshot.address)
            putExtra(EXTRA_NAME, snapshot.name)
            putExtra(EXTRA_CONNECTED, snapshot.connected)
            putExtra(EXTRA_LEFT, snapshot.left)
            putExtra(EXTRA_RIGHT, snapshot.right)
            putExtra(EXTRA_CASE, snapshot.case)
            putExtra(EXTRA_LEFT_CHARGING, snapshot.leftCharging)
            putExtra(EXTRA_RIGHT_CHARGING, snapshot.rightCharging)
            putExtra(EXTRA_CASE_CHARGING, snapshot.caseCharging)
            putExtra(EXTRA_ANC, snapshot.anc)
        }
        sendToAll(context, intent)
    }

    /** 把"用户切了 ANC"通知给蓝牙进程。 */
    fun broadcastAncSelect(context: Context?, ancCode: Int) {
        if (context == null) return
        val intent = Intent(ACTION_ANC_SELECT).apply { putExtra(EXTRA_ANC, ancCode) }
        sendToBluetooth(context, intent)
    }

    /** 请求蓝牙进程立刻重新查询耳机状态。 */
    fun broadcastRefresh(context: Context?) {
        if (context == null) return
        sendToBluetooth(context, Intent(ACTION_REFRESH_STATUS))
    }

    private fun sendToBluetooth(context: Context, intent: Intent) {
        runCatching {
            intent.setPackage("com.android.bluetooth")
            context.sendBroadcast(intent)
        }.onFailure { ModuleLog.w(TAG, "发送到蓝牙进程失败：${it.message}") }
    }

    /** 逐个 setPackage 发送——不广播给全系统，避免被无关应用监听。 */
    private fun sendToAll(context: Context, intent: Intent) {
        for (pkg in CONSUMER_PACKAGES) {
            runCatching {
                val copy = Intent(intent).setPackage(pkg)
                context.sendBroadcast(copy)
            }
        }
    }

    private val CONSUMER_PACKAGES = listOf(
        "com.milink.service",
        "com.android.settings",
        "com.android.systemui",
        "com.xiaomi.bluetooth",
        "com.android.bluetooth",
    )

    // ---------------------------------------------------------------- 接收

    /**
     * 注册状态接收器。**必须在 try-catch 内调用**，
     * 且注册失败不能影响宿主进程。
     */
    fun registerStateReceiver(context: Context?, onState: (PodSnapshot) -> Unit) {
        if (context == null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != ACTION_PODS_STATE) return
                runCatching {
                    val snap = PodSnapshot(
                        address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty(),
                        name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                        connected = intent.getBooleanExtra(EXTRA_CONNECTED, false),
                        left = intent.getIntExtra(EXTRA_LEFT, UNKNOWN_LEVEL),
                        right = intent.getIntExtra(EXTRA_RIGHT, UNKNOWN_LEVEL),
                        case = intent.getIntExtra(EXTRA_CASE, UNKNOWN_LEVEL),
                        leftCharging = intent.getBooleanExtra(EXTRA_LEFT_CHARGING, false),
                        rightCharging = intent.getBooleanExtra(EXTRA_RIGHT_CHARGING, false),
                        caseCharging = intent.getBooleanExtra(EXTRA_CASE_CHARGING, false),
                        anc = intent.getIntExtra(EXTRA_ANC, ANC_OFF),
                    )
                    updateSnapshot(snap)
                    onState(snap)
                }.onFailure { ModuleLog.w(TAG, "解析状态广播失败：${it.message}") }
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION_PODS_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            ModuleLog.i(TAG, "状态接收器已注册（${context.packageName}）")
        }.onFailure { ModuleLog.w(TAG, "注册状态接收器失败：${it.message}") }
    }

    /** 便捷：从 Intent 里取设备（跨进程传递用）。 */
    fun deviceExtra(intent: Intent?): BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as? BluetoothDevice
        }
    }.getOrNull()
}
