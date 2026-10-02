package com.yinpage.link.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinpage.link.R
import com.yinpage.link.protocol.ConnectionState
import com.yinpage.link.protocol.PodState
import com.yinpage.link.ui.components.StatusChip
import com.yinpage.link.ui.theme.ConnectedGreen
import com.yinpage.link.ui.theme.DangerRed
import com.yinpage.link.ui.theme.WarningAmber

/**
 * UI 层自己的设备条目模型。
 *
 * 存在的理由：core 的 BluetoothDeviceItem 只被 ui/App.kt 认识，
 * 页面组件只依赖本类型，这样 core 侧字段改名时只需要改 App.kt 一处适配。
 */
data class DeviceItem(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val rssi: Int?,
)

/**
 * 设备页：显示已配对 / 扫描到的设备，点击连接。
 * 未连接时给出「先去系统蓝牙配对」的引导。
 */
@Composable
fun DevicePage(
    devices: List<DeviceItem>,
    pod: PodState,
    modifier: Modifier = Modifier,
    scanning: Boolean = false,
    permissionGranted: Boolean = true,
    bluetoothEnabled: Boolean = true,
    onStartScan: () -> Unit = {},
    onStopScan: () -> Unit = {},
    onConnect: (DeviceItem) -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val connectedAddress = if (pod.connected) pod.device?.address else null
    val busy = pod.connection == ConnectionState.CONNECTING ||
        pod.connection == ConnectionState.HANDSHAKING ||
        pod.connection == ConnectionState.RECONNECTING

    val bonded = devices
        .filter { it.bonded }
        .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    val found = devices
        .filterNot { it.bonded }
        .sortedByDescending { it.rssi ?: Int.MIN_VALUE }

    val canScan = bluetoothEnabled && permissionGranted

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // ---------------- 标题 + 扫描按钮 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.device_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (devices.isNotEmpty()) {
                StatusChip(
                    text = stringResource(R.string.device_count, devices.size),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---------------- 前置条件提示 ----------------
        if (!bluetoothEnabled) {
            HintCard(
                title = stringResource(R.string.device_bt_off_title),
                body = stringResource(R.string.device_bt_off_body),
                icon = Icons.Rounded.BluetoothDisabled,
                tone = DangerRed,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!permissionGranted) {
            HintCard(
                title = stringResource(R.string.device_perm_title),
                body = stringResource(R.string.device_perm_body),
                icon = Icons.Rounded.Bluetooth,
                tone = WarningAmber,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---------------- 配对引导 ----------------
        HintCard(
            title = stringResource(R.string.device_guide_title),
            body = stringResource(R.string.device_guide_body),
            icon = Icons.Rounded.HelpOutline,
            tone = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ---------------- 当前连接 ----------------
        if (pod.connected) {
            CurrentConnectionCard(pod = pod, onDisconnect = onDisconnect)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---------------- 扫描控制 ----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.device_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.device_count, devices.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { if (scanning) onStopScan() else onStartScan() },
                    enabled = canScan,
                ) {
                    Icon(
                        imageVector = if (scanning) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (scanning) {
                            stringResource(R.string.device_action_stop)
                        } else {
                            stringResource(R.string.device_action_scan)
                        },
                    )
                }
            }
        }

        // ---------------- 已配对 ----------------
        Spacer(modifier = Modifier.height(18.dp))
        SectionLabel(text = stringResource(R.string.device_section_bonded))
        Spacer(modifier = Modifier.height(8.dp))
        if (bonded.isEmpty()) {
            EmptyLine(text = stringResource(R.string.device_empty_bonded))
        } else {
            DeviceList(
                items = bonded,
                connectedAddress = connectedAddress,
                busy = busy,
                clickable = canScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
        }

        // ---------------- 扫描结果 ----------------
        Spacer(modifier = Modifier.height(18.dp))
        SectionLabel(text = stringResource(R.string.device_section_found))
        Spacer(modifier = Modifier.height(8.dp))
        if (found.isEmpty()) {
            EmptyLine(text = stringResource(R.string.device_empty_scan))
        } else {
            DeviceList(
                items = found,
                connectedAddress = connectedAddress,
                busy = busy,
                clickable = canScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun DeviceList(
    items: List<DeviceItem>,
    connectedAddress: String?,
    busy: Boolean,
    clickable: Boolean,
    onConnect: (DeviceItem) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val isCurrent = connectedAddress != null && item.address.equals(connectedAddress, ignoreCase = true)
            DeviceRow(
                item = item,
                isConnected = isCurrent,
                isBusy = isCurrent && busy,
                enabled = clickable,
                onConnect = { onConnect(item) },
                onDisconnect = onDisconnect,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    item: DeviceItem,
    isConnected: Boolean,
    isBusy: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        if (isConnected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.Headphones else Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = if (isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(21.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { stringResource(R.string.device_unknown_name) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalBars(rssi = item.rssi)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.rssi?.let { stringResource(R.string.device_signal, it) }
                            ?: stringResource(R.string.device_signal_unknown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.bonded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip(
                            text = stringResource(R.string.device_bonded_tag),
                            color = ConnectedGreen,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            when {
                isConnected -> TextButton(onClick = onDisconnect) {
                    Text(text = stringResource(R.string.device_action_disconnect), color = DangerRed)
                }

                isBusy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )

                else -> FilledTonalButton(onClick = onConnect, enabled = enabled) {
                    Text(text = stringResource(R.string.device_action_connect))
                }
            }
        }
    }
}

@Composable
private fun CurrentConnectionCard(pod: PodState, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_current),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = pod.device?.name ?: stringResource(R.string.device_unknown_name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pod.device?.address ?: "-",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pod.protocolName != null) {
                StatusChip(text = pod.protocolName, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = onDisconnect) {
                Text(text = stringResource(R.string.device_action_disconnect), color = DangerRed)
            }
        }
    }
}

/** 简易信号强度柱：4 格，按 RSSI 分级点亮。 */
@Composable
private fun SignalBars(rssi: Int?) {
    val level = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((5 + index * 3).dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(
                        if (index < level) ConnectedGreen else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun HintCard(
    title: String,
    body: String,
    icon: ImageVector,
    tone: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
