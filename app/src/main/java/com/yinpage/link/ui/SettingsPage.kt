package com.yinpage.link.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yinpage.link.BuildConfig
import com.yinpage.link.R
import com.yinpage.link.config.AppConfig
import com.yinpage.link.core.TransportKind
import com.yinpage.link.protocol.PodState
import com.yinpage.link.protocol.ProtocolRegistry
import com.yinpage.link.ui.components.SettingChipsRow
import com.yinpage.link.ui.components.SettingExpandableRow
import com.yinpage.link.ui.components.SettingRow
import com.yinpage.link.ui.components.SettingSliderRow
import com.yinpage.link.ui.components.SettingSwitchRow
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * 只依赖 config 模块的 [AppConfig] 与冻结的 [TransportKind] / [ProtocolRegistry]，
 * 写回通过 onConfigChange 上抛（由 ui/App.kt 落到 ConfigManager）。
 */
@Composable
fun SettingsPage(
    config: AppConfig,
    pod: PodState,
    modifier: Modifier = Modifier,
    onConfigChange: (AppConfig) -> Unit = {},
    onOpenDebug: () -> Unit = {},
) {
    var codecExpanded by rememberSaveable { mutableStateOf(false) }

    val codecs = remember { ProtocolRegistry.all() }
    val selectedTransport = remember(config.transport) {
        TransportKind.entries.firstOrNull { it.name.equals(config.transport, ignoreCase = true) }
            ?: TransportKind.AUTO
    }
    val selectedCodec = remember(config.preferredCodecId, codecs) {
        codecs.firstOrNull { it.id == config.preferredCodecId }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------- 连接 ----------------------------
        Spacer(modifier = Modifier.height(14.dp))
        SettingsSection(title = stringResource(R.string.settings_section_connection), icon = Icons.Rounded.Bluetooth) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_auto_reconnect),
                subtitle = stringResource(R.string.settings_auto_reconnect_desc),
                icon = Icons.Rounded.Refresh,
                checked = config.autoReconnect,
                onCheckedChange = { onConfigChange(config.copy(autoReconnect = it)) },
            )
            SettingChipsRow(
                title = stringResource(R.string.settings_transport),
                subtitle = stringResource(R.string.settings_transport_desc),
                icon = Icons.Rounded.Link,
                options = listOf(
                    TransportKind.AUTO to stringResource(R.string.transport_auto),
                    TransportKind.RFCOMM to stringResource(R.string.transport_rfcomm),
                    TransportKind.BLE to stringResource(R.string.transport_ble),
                ),
                selected = selectedTransport,
                onSelect = { onConfigChange(config.copy(transport = it.name)) },
            )
            SettingExpandableRow(
                title = stringResource(R.string.settings_codec),
                subtitle = stringResource(R.string.settings_codec_desc),
                icon = Icons.Rounded.Tune,
                valueText = selectedCodec?.displayName ?: stringResource(R.string.settings_codec_auto),
                expanded = codecExpanded,
                onExpandedChange = { codecExpanded = it },
            ) {
                CodecOptionRow(
                    label = stringResource(R.string.settings_codec_auto),
                    description = null,
                    selected = config.preferredCodecId == null,
                    onClick = {
                        onConfigChange(config.copy(preferredCodecId = null))
                        codecExpanded = false
                    },
                )
                codecs.forEach { codec ->
                    CodecOptionRow(
                        label = codec.displayName,
                        description = codec.id,
                        selected = config.preferredCodecId == codec.id,
                        onClick = {
                            onConfigChange(config.copy(preferredCodecId = codec.id))
                            codecExpanded = false
                        },
                    )
                }
            }
            SettingSwitchRow(
                title = stringResource(R.string.settings_query_on_connect),
                subtitle = stringResource(R.string.settings_query_on_connect_desc),
                icon = Icons.Rounded.Science,
                checked = config.queryOnConnect,
                onCheckedChange = { onConfigChange(config.copy(queryOnConnect = it)) },
                showDivider = false,
            )
        }

        // ---------------------------- 调试 ----------------------------
        Spacer(modifier = Modifier.height(14.dp))
        SettingsSection(title = stringResource(R.string.settings_section_debug), icon = Icons.Rounded.BugReport) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_debug_panel),
                subtitle = stringResource(R.string.settings_debug_panel_desc),
                icon = Icons.Rounded.BugReport,
                checked = config.debugPanel,
                onCheckedChange = { onConfigChange(config.copy(debugPanel = it)) },
            )
            SettingRow(
                title = stringResource(R.string.settings_open_debug),
                subtitle = stringResource(R.string.settings_open_debug_desc),
                icon = Icons.Rounded.Science,
                enabled = config.debugPanel,
                onClick = onOpenDebug,
            )
            SettingChipsRow(
                title = stringResource(R.string.settings_log_level),
                icon = Icons.Rounded.Tune,
                options = listOf(
                    AppConfig.LOG_OFF to stringResource(R.string.log_off),
                    AppConfig.LOG_BASIC to stringResource(R.string.log_basic),
                    AppConfig.LOG_DEBUG to stringResource(R.string.log_verbose),
                ),
                selected = config.logLevel,
                onSelect = { onConfigChange(config.copy(logLevel = it)) },
                showDivider = false,
            )
        }

        // ---------------------------- 提醒 ----------------------------
        Spacer(modifier = Modifier.height(14.dp))
        SettingsSection(title = stringResource(R.string.settings_section_notify), icon = Icons.Rounded.Notifications) {
            SettingSwitchRow(
                title = stringResource(R.string.settings_notifications),
                subtitle = stringResource(R.string.settings_notifications_desc),
                icon = Icons.Rounded.Notifications,
                checked = config.notifications,
                onCheckedChange = { onConfigChange(config.copy(notifications = it)) },
            )
            SettingSliderRow(
                title = stringResource(R.string.settings_low_battery),
                subtitle = stringResource(R.string.settings_low_battery_desc),
                icon = Icons.Rounded.BatteryAlert,
                valueText = "${config.lowBatteryThreshold}%",
                value = config.lowBatteryThreshold.toFloat(),
                valueRange = 5f..50f,
                steps = 8,
                onValueChange = {
                    val percent = it.roundToInt().coerceIn(5, 50)
                    if (percent != config.lowBatteryThreshold) {
                        onConfigChange(config.copy(lowBatteryThreshold = percent))
                    }
                },
                showDivider = false,
            )
        }

        // ---------------------------- 关于 ----------------------------
        Spacer(modifier = Modifier.height(14.dp))
        SettingsSection(title = stringResource(R.string.settings_section_about), icon = Icons.Rounded.Info) {
            SettingRow(
                title = stringResource(R.string.settings_version),
                icon = Icons.Rounded.Info,
                trailing = {
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingRow(
                title = stringResource(R.string.footer_link_state),
                icon = Icons.Rounded.Bluetooth,
                trailing = {
                    Text(
                        text = pod.protocolName ?: stringResource(R.string.state_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingRow(
                title = stringResource(R.string.settings_forget),
                subtitle = stringResource(R.string.settings_forget_desc),
                icon = Icons.Rounded.DeleteSweep,
                enabled = config.lastDeviceAddress != null,
                onClick = {
                    onConfigChange(config.copy(lastDeviceAddress = null, lastDeviceName = null))
                },
                showDivider = false,
            )
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.app_root_free_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** 单选行：协议列表用。 */
@Composable
private fun CodecOptionRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 62.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
