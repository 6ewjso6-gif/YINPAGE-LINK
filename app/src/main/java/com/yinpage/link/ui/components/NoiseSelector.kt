package com.yinpage.link.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yinpage.link.R
import com.yinpage.link.protocol.NoiseMode

/** 降噪档位对应的图标。未知档位回落到通用调节图标。 */
fun noiseIcon(mode: NoiseMode): ImageVector = when (mode) {
    NoiseMode.OFF -> Icons.Rounded.VolumeOff
    NoiseMode.TRANSPARENT -> Icons.Rounded.Hearing
    NoiseMode.NORMAL -> Icons.Rounded.GraphicEq
    NoiseMode.DEEP -> Icons.Rounded.Waves
    NoiseMode.ADAPTIVE -> Icons.Rounded.Tune
    NoiseMode.WIND -> Icons.Rounded.Air
    else -> Icons.Rounded.Tune
}

/** 降噪档位的中文短名（四档控件上只放短名）。 */
@Composable
fun noiseLabel(mode: NoiseMode): String = when (mode) {
    NoiseMode.OFF -> stringResource(R.string.noise_off)
    NoiseMode.TRANSPARENT -> stringResource(R.string.noise_transparent)
    NoiseMode.NORMAL -> stringResource(R.string.noise_normal)
    NoiseMode.DEEP -> stringResource(R.string.noise_deep)
    else -> mode.labelZh
}

/**
 * 四档 ANC 分段控件（自绘，不依赖实验性 SegmentedButton）。
 * 顺序固定为 关闭 / 通透 / 标准 / 深度，与系统原生控件的档位语义一致。
 */
@Composable
fun NoiseSelector(
    selected: NoiseMode,
    onSelect: (NoiseMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    modes: List<NoiseMode> = NoiseMode.SWITCHABLE,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val background by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = tween(durationMillis = 200),
                    label = "noiseSegment",
                )
                val foreground = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(background)
                        .clickable(enabled = enabled) { onSelect(mode) }
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = noiseIcon(mode),
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = noiseLabel(mode),
                        style = MaterialTheme.typography.labelMedium,
                        color = foreground,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
