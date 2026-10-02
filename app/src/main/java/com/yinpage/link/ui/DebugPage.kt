package com.yinpage.link.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yinpage.link.R
import com.yinpage.link.protocol.PodState
import com.yinpage.link.ui.components.connectionLabel
import com.yinpage.link.ui.theme.MonoLogStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 调试面板：等宽字体滚动显示收发字节流，支持一键复制。
 *
 * 「清空」只在 UI 层记录已读游标（不需要 core 提供清空接口），
 * 这样 core 的 AppState 不必为 UI 额外暴露 API。
 */
@Composable
fun DebugPage(
    lines: List<String>,
    pod: PodState,
    modifier: Modifier = Modifier,
    debugEnabled: Boolean = true,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var autoScroll by remember { mutableStateOf(true) }
    var hiddenBefore by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }

    val visibleLines = if (hiddenBefore <= 0) {
        lines
    } else {
        lines.drop(hiddenBefore.coerceAtMost(lines.size))
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    LaunchedEffect(visibleLines.size, autoScroll, debugEnabled) {
        if (debugEnabled && autoScroll && visibleLines.isNotEmpty()) {
            listState.scrollToItem(visibleLines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // ---------------------------- 标题栏 ----------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.debug_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.debug_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val text = lines.joinToString(separator = "\n")
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("YINPAGE-LINK debug", text))
                    copied = true
                },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.debug_action_copy),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { hiddenBefore = lines.size },
                enabled = visibleLines.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.debug_action_clear),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // ---------------------------- 状态摘要 ----------------------------
        if (debugEnabled) {
            SummaryCard(lines = lines, pod = pod, visibleCount = visibleLines.size)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.debug_autoscroll),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (copied) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_copied),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
            }
        } else {
            HintBlock(
                title = stringResource(R.string.debug_disabled_title),
                body = stringResource(R.string.debug_disabled_body),
            )
        }

        // ---------------------------- 字节流 ----------------------------
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        ) {
            if (visibleLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.debug_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(count = visibleLines.size) { index ->
                        val line = visibleLines[index]
                        Text(
                            text = line,
                            style = MonoLogStyle,
                            color = logLineColor(line),
                        )
                    }
                }
            }
        }
    }
}

/** 一帧日志的颜色：接收 / 发送 / 其他 三种语义色。 */
@Composable
private fun logLineColor(line: String): Color = when {
    line.contains("←") || line.contains("RX") || line.contains("recv") ->
        MaterialTheme.colorScheme.primary

    line.contains("→") || line.contains("TX") || line.contains("send") ->
        MaterialTheme.colorScheme.tertiary

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun SummaryCard(lines: List<String>, pod: PodState, visibleCount: Int) {
    val never = stringResource(R.string.footer_never)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InfoPair(
                    label = stringResource(R.string.footer_link_state),
                    value = connectionLabel(pod.connection),
                    modifier = Modifier.weight(1f),
                )
                InfoPair(
                    label = stringResource(R.string.footer_protocol),
                    value = pod.protocolName ?: "--",
                    modifier = Modifier.weight(1f),
                )
                InfoPair(
                    label = stringResource(R.string.debug_lines),
                    value = "$visibleCount / ${lines.size}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            InfoPair(
                label = stringResource(R.string.footer_last_seen),
                value = formatDebugTime(pod.lastSeenAt, never),
            )
            val lastMessage = pod.lastMessage?.takeIf { it.isNotBlank() }
            if (lastMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                InfoPair(
                    label = stringResource(R.string.debug_latest),
                    value = lastMessage,
                )
            }
        }
    }
}

@Composable
private fun InfoPair(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun HintBlock(title: String, body: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDebugTime(timestamp: Long, never: String): String {
    if (timestamp <= 0L) return never
    return runCatching {
        DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(never)
}
