package com.yinpage.link.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yinpage.link.R
import com.yinpage.link.protocol.EqMode
import com.yinpage.link.protocol.NoiseMode
import com.yinpage.link.protocol.PodState
import com.yinpage.link.ui.components.NoiseSelector
import com.yinpage.link.ui.components.PodCard
import com.yinpage.link.ui.components.SettingRow
import com.yinpage.link.ui.components.SettingSwitchRow
import com.yinpage.link.ui.components.StatusChip
import com.yinpage.link.ui.components.connectionColor
import com.yinpage.link.ui.components.connectionLabel
import com.yinpage.link.ui.components.noiseLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 首页：主控制页。
 *
 * 只依赖冻结契约里的 [PodState]，不认识 core 的 AppState，
 * 所有动作通过回调上抛给 ui/App.kt 统一接线，页面本身可独立预览。
 */
@Composable
fun HomePage(
    pod: PodState,
    modifier: Modifier = Modifier,
    debugEnabled: Boolean = false,
    onOpenDevices: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onNoise: (NoiseMode) -> Unit = {},
    onEq: (EqMode) -> Unit = {},
    onGameMode: (Boolean) -> Unit = {},
    onInEarDetection: (Boolean) -> Unit = {},
    onDualConnection: (Boolean) -> Unit = {},
    onWindSuppression: (Boolean) -> Unit = {},
    onOpenDebug: () -> Unit = {},
) {
    val connected = pod.connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        HomeHeader(pod = pod)

        Spacer(modifier = Modifier.height(14.dp))
        PodCard(pod = pod, onOpenDevices = onOpenDevices, onRefresh = onRefresh)

        Spacer(modifier = Modifier.height(14.dp))
        SectionCard(
            title = stringResource(R.string.section_noise),
            subtitle = stringResource(R.string.section_noise_desc),
            icon = Icons.Rounded.Tune,
        ) {
            NoiseSelector(
                selected = pod.noise,
                onSelect = onNoise,
                enabled = connected,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.noise_current, noiseLabel(pod.noise)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        SectionCard(
            title = stringResource(R.string.section_eq),
            subtitle = stringResource(R.string.section_eq_desc),
            icon = Icons.Rounded.GraphicEq,
        ) {
            EqGrid(selected = pod.eq, enabled = connected, onSelect = onEq)
        }

        Spacer(modifier = Modifier.height(14.dp))
        SectionCard(
            title = stringResource(R.string.section_features),
            icon = Icons.Rounded.SportsEsports,
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            SettingSwitchRow(
                title = stringResource(R.string.feature_game),
                subtitle = stringResource(R.string.feature_game_desc),
                icon = Icons.Rounded.SportsEsports,
                checked = pod.gameMode,
                onCheckedChange = onGameMode,
                enabled = connected,
            )
            SettingSwitchRow(
                title = stringResource(R.string.feature_in_ear),
                subtitle = stringResource(R.string.feature_in_ear_desc),
                icon = Icons.Rounded.Hearing,
                checked = pod.inEarDetection,
                onCheckedChange = onInEarDetection,
                enabled = connected,
            )
            SettingSwitchRow(
                title = stringResource(R.string.feature_dual),
                subtitle = stringResource(R.string.feature_dual_desc),
                icon = Icons.Rounded.Link,
                checked = pod.dualConnection,
                onCheckedChange = onDualConnection,
                enabled = connected,
            )
            SettingSwitchRow(
                title = stringResource(R.string.feature_wind),
                subtitle = stringResource(R.string.feature_wind_desc),
                icon = Icons.Rounded.Air,
                checked = pod.windSuppression,
                onCheckedChange = onWindSuppression,
                enabled = connected,
                showDivider = false,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        FooterCard(pod = pod, debugEnabled = debugEnabled, onOpenDebug = onOpenDebug)

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun HomeHeader(pod: PodState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusChip(
            text = connectionLabel(pod.connection),
            color = connectionColor(pod.connection),
            icon = Icons.Rounded.Bluetooth,
            filled = pod.connected,
        )
    }
}

/** 分组卡片：标题 + 说明 + 内容。 */
@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(contentPadding),
                content = content,
            )
        }
    }
}

/** EQ 六档网格（每行三个，最后一行自动补空位）。 */
@Composable
private fun EqGrid(
    selected: EqMode,
    enabled: Boolean,
    onSelect: (EqMode) -> Unit,
) {
    val columns = 3
    val options = EqMode.entries

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { mode ->
                    val isSelected = mode == selected
                    val background by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "eqChip",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(background)
                            .clickable(enabled = enabled) { onSelect(mode) }
                            .padding(vertical = 11.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 底部状态小字：协议 / 最后通信 / 连接状态 + 调试入口。 */
@Composable
private fun FooterCard(
    pod: PodState,
    debugEnabled: Boolean,
    onOpenDebug: () -> Unit,
) {
    val lastMessage = pod.lastMessage?.takeIf { it.isNotBlank() }
    val never = stringResource(R.string.footer_never)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                InfoLine(
                    label = stringResource(R.string.footer_link_state),
                    value = connectionLabel(pod.connection),
                )
                InfoLine(
                    label = stringResource(R.string.footer_protocol),
                    value = pod.protocolName ?: "--",
                )
                InfoLine(
                    label = stringResource(R.string.footer_last_seen),
                    value = formatTimestamp(pod.lastSeenAt, never),
                )
                if (lastMessage != null) {
                    InfoLine(
                        label = stringResource(R.string.debug_latest),
                        value = lastMessage,
                    )
                }
            }
            SettingRow(
                title = stringResource(R.string.footer_open_debug),
                icon = Icons.Rounded.BugReport,
                enabled = debugEnabled,
                showDivider = false,
                onClick = onOpenDebug,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 时间戳 → 本地时间；0 表示还没成功通信过。 */
private fun formatTimestamp(timestamp: Long, never: String): String {
    if (timestamp <= 0L) return never
    return runCatching {
        DateTimeFormatter
            .ofPattern("MM-dd HH:mm:ss")
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(never)
}
