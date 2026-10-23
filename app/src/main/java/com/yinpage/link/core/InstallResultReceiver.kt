package com.yinpage.link.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * ============================================================================
 *  PackageInstaller 会话结果接收器
 * ============================================================================
 *  [UpdateManager.launchSession] 通过 `session.commit(pendingIntent.intentSender)`
 *  把 APK 交给系统安装器后，安装结果会以显式广播回到本类：
 *
 *   - `STATUS_PENDING_USER_ACTION`：系统要求用户确认，广播附带一个待启动的
 *     安装确认界面（[Intent.EXTRA_INTENT]），必须由本应用主动 startActivity，
 *     否则用户看到的就是「点了更新但安装器根本没出现」；
 *   - `STATUS_SUCCESS` / `STATUS_FAILURE_*`：写 EventLog，便于用户在
 *     调试面板里看到真实失败原因（而不是只看到"安装失败"）。
 *
 *  ⚠️ 该广播属于本应用私有（manifest 中 `exported="false"`），
 *  只有系统安装器能通过我们签发的 PendingIntent 触发。
 * ============================================================================
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统把「确认安装」界面以 Intent 形式回传，需要由我们拉起。
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    EventLog.info(TAG, "系统要求用户确认安装，但未返回确认界面")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { EventLog.info(TAG, "无法拉起安装确认界面：${it.message}") }
            }

            PackageInstaller.STATUS_SUCCESS ->
                EventLog.info(TAG, "安装成功${if (message.isNotBlank()) "：$message" else ""}")

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                EventLog.info(TAG, "安装失败（status=$status）${if (message.isNotBlank()) "：$message" else ""}")

            else ->
                EventLog.info(TAG, "安装结果未知（status=$status）${if (message.isNotBlank()) "：$message" else ""}")
        }
    }

    private companion object {
        const val TAG = "更新"
    }
}
