package com.yinpage.link.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinpage.link.R
import com.yinpage.link.config.AppConfig
import com.yinpage.link.core.AppState
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
}
