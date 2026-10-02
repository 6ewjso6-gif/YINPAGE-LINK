package com.yinpage.link.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yinpage.link.R
import com.yinpage.link.ui.theme.ChargingGreen
import com.yinpage.link.ui.theme.batteryColor

/**
 * 电量环。用 Canvas 画两段圆弧：底色轨道 + 按百分比扫描的进度弧。
 *
 * @param percent  null 表示耳机未上报该值，此时显示 "--" 且环为灰色。
 * @param charging 是否充电中（右下角出现闪电角标）。
 */
@Composable
fun BatteryRing(
    percent: Int?,
    label: String,
    modifier: Modifier = Modifier,
    charging: Boolean = false,
    diameter: Dp = 78.dp,
    stroke: Dp = 8.dp,
) {
    val target = ((percent ?: 0).coerceIn(0, 100)) / 100f
    val animated by animateFloatAsState(
        targetValue = if (percent == null) 0f else target,
        animationSpec = tween(durationMillis = 700),
        label = "batteryRing",
    )

    val ringColor: Color = batteryColor(percent)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (percent == null) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = stroke.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                val topLeft = Offset(inset, inset)

                // 轨道
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                // 进度（从 12 点方向顺时针）
                if (animated > 0.001f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (percent == null) {
                        stringResource(R.string.battery_unknown)
                    } else {
                        "$percent%"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    fontSize = 16.sp,
                )
                if (charging) {
                    Text(
                        text = stringResource(R.string.battery_charging),
                        style = MaterialTheme.typography.labelSmall,
                        color = ChargingGreen,
                    )
                }
            }

            if (charging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = ChargingGreen,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
