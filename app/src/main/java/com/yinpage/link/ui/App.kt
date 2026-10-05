package com.yinpage.link.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinpage.link.R
import com.yinpage.link.config.AppConfig
import com.yinpage.link.core.AppState
import com.yinpage.link.core.UpdateManager
import kotlinx.coroutines.launch

/**
 * ============================================================================
 *  应用骨架：底部导航（首页 / 设备 / 设置）+ 页面切换 + 调试面板全屏覆盖层。
 * ============================================================================
 *  ⚠️ 本文件是整个 UI 层与 core 的唯一接线点：
 *      - 向上：只认识 AppState（core）、ConfigManager（config）；
 *      - 向下：把状态与回调交给 HomePage / DevicePage / SettingsPage / DebugPage，
 *        页面本身不 import core，core 侧签名的任何改动只需修这里。
 *
 *  期望的 core API（与 doc/CONTRACT 中 AppState 约定一致）：
 *      AppState.ui      : StateFlow<PodUiState>
 *      AppState.devices : StateFlow<List<BluetoothDeviceItem>>
 *      AppState.startScan() / stopScan() / connect(item) / disconnect() / refresh()
 *      AppState.setNoise(NoiseMode) / setEq(EqMode)
 *      AppState.setGameMode(Boolean) / setInEar(Boolean)
 *      AppState.setDualConnection(Boolean) / setWindSuppression(Boolean)
 * ============================================================================
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    permissionGranted: Boolean? = null,
) {
    val ui by AppState.ui.collectAsStateWithLifecycle()
    val devices by AppState.devices.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 配置以 core 的 ui.config 为唯一来源：core 侧的任何写入
    // （含 rememberDevice 这类不经过 UI 的更新）都能在 UI 上如实反映，
    // 不再用本地 pendingConfig 永久遮蔽真实配置。
    val config: AppConfig = ui.config

    var tab by rememberSaveable { mutableStateOf(0) }
    var showDebug by rememberSaveable { mutableStateOf(false) }

    val applyConfig: (AppConfig) -> Unit = { updated ->
        AppState.updateConfig { updated }
    }

    // ---------------------------- 应用内更新（检查 / 下载 / 安装） ----------------------------
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateManager.ReleaseInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0L to 0L) }

    val funToast: (Int) -> Unit = { resId ->
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    /** 检查 GitHub 最新 Release：有新版本就弹窗，已最新则提示。 */
    val checkUpdate: () -> Unit = {
        if (!checkingUpdate && !downloading) {
            checkingUpdate = true
            Toast.makeText(context, R.string.update_checking, Toast.LENGTH_SHORT).show()
            scope.launch {
                val result = UpdateManager.checkForUpdate()
                checkingUpdate = false
                when (result) {
                    is UpdateManager.CheckResult.Available -> updateInfo = result.info
                    is UpdateManager.CheckResult.UpToDate -> funToast(R.string.update_latest)
                    is UpdateManager.CheckResult.Failed ->
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 自动下载最新 APK，完成后调起系统安装器。 */
    val startDownload: (UpdateManager.ReleaseInfo) -> Unit = { info ->
        if (!downloading) {
            downloading = true
            downloadProgress = 0L to 0L
            updateInfo = null
            scope.launch {
                runCatching {
                    UpdateManager.download(context.applicationContext, info) { done, total ->
                        downloadProgress = done to total
                    }
                }.onSuccess { apk ->
                    downloading = false
                    if (!UpdateManager.install(context.applicationContext, apk)) {
                        Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    downloading = false
                    // 展示具体线路错误（如"所有 4 条下载线路均失败"），便于反馈排查
                    val msg = error.message
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.update_download_failed)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val permissionOk = permissionGranted ?: ui.permissionGranted

    if (showDebug) {
        DebugPage(
            lines = ui.debugLines,
            pod = ui.pod,
            debugEnabled = config.debugPanel,
            onBack = { showDebug = false },
            onClear = { AppState.clearLog() },
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(imageVector = Icons.Rounded.Home, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.tab_home)) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(imageVector = Icons.Rounded.Devices, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.tab_devices)) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(imageVector = Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(text = stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                0 -> HomePage(
                    pod = ui.pod,
                    debugEnabled = config.debugPanel,
                    onOpenDevices = { tab = 1 },
                    onRefresh = { AppState.refresh() },
                    onNoise = { AppState.setNoise(it) },
                    onEq = { AppState.setEq(it) },
                    onGameMode = { AppState.setGameMode(it) },
                    onInEarDetection = { AppState.setInEar(it) },
                    onDualConnection = { AppState.setDualConnection(it) },
                    onWindSuppression = { AppState.setWindSuppression(it) },
                    onOpenDebug = { showDebug = true },
                )

                1 -> DevicePage(
                    devices = devices.map { item ->
                        DeviceItem(
                            name = item.name,
                            address = item.address,
                            bonded = item.bonded,
                            rssi = item.rssi,
                        )
                    },
                    pod = ui.pod,
                    scanning = ui.scanning,
                    permissionGranted = permissionOk,
                    bluetoothEnabled = ui.bluetoothEnabled,
                    onStartScan = { scope.launch { AppState.startScan() } },
                    onStopScan = { AppState.stopScan() },
                    onConnect = { device ->
                        devices
                            .firstOrNull { it.address.equals(device.address, ignoreCase = true) }
                            ?.let { AppState.connect(it) }
                    },
                    onDisconnect = { AppState.disconnect() },
                )

                else -> SettingsPage(
                    config = config,
                    pod = ui.pod,
                    onConfigChange = applyConfig,
                    onOpenDebug = { showDebug = true },
                    onCheckUpdate = checkUpdate,
                    onRunDiagnostics = {
                        // 诊断含阻塞式蓝牙连接测试（最长可达数十秒），
                        // 必须在后台线程执行，否则主线程卡死 → ANR → 崩溃。
                        Toast.makeText(
                            context,
                            "正在诊断，请稍候（约 10~60 秒）…",
                            Toast.LENGTH_SHORT,
                        ).show()
                        AppState.buildDiagnosticsAsync { report ->
                            runCatching {
                                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("YINPAGE-LINK 诊断", report),
                                )
                            }
                            Toast.makeText(
                                context,
                                "诊断报告已复制到剪贴板，粘贴发给开发者即可",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
            }
        }
    }

        // 发现新版本：展示发布说明，点击「立即更新」自动下载
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = {
                    Text(text = "${stringResource(R.string.update_found)} ${info.tagName}")
                },
                text = {
                    Column {
                        Text(
                            text = run {
                                val body = info.body.trim().takeIf { it.isNotBlank() } ?: return@run ""
                                if (body.length > 500) body.take(500) + "…" else body
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "发布于 ${info.publishedAt.take(10)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { startDownload(info) }) {
                        Text(text = stringResource(R.string.update_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateInfo = null }) {
                        Text(text = stringResource(R.string.update_cancel))
                    }
                },
            )
        }

        // 下载中：显示进度，不可关闭（避免下载中断半包）
        if (downloading) {
            val (done, total) = downloadProgress
            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(text = stringResource(R.string.update_downloading, percent))
                },
                text = {
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {},
            )
        }
}
