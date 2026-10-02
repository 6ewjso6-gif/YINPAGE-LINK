package com.yinpage.link.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yinpage.link.R
import com.yinpage.link.protocol.ConnectionState
import com.yinpage.link.ui.theme.ConnectedGreen
import com.yinpage.link.ui.theme.DangerRed
import com.yinpage.link.ui.theme.NeutralGrey
import com.yinpage.link.ui.theme.WarningAmber

/**
 * 小胶囊标签：用来显示「已连接」「协议名」「已配对」这类短状态。
 *
 * @param filled true = 实心（用于最需要抢眼的那一个状态），false = 半透明底。
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (filled) Color.White else color,
                modifier = Modifier.size(13.dp),
            )
        }
        Text(
            text = text,
            color = if (filled) Color.White else color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 纯色小圆点，用于列表项前的连接/信号指示。 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** 连接状态 → 中文文案。 */
@Composable
fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.BLUETOOTH_OFF -> stringResource(R.string.state_bluetooth_off)
    ConnectionState.IDLE -> stringResource(R.string.state_idle)
    ConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
    ConnectionState.HANDSHAKING -> stringResource(R.string.state_handshaking)
    ConnectionState.CONNECTED -> stringResource(R.string.state_connected)
    ConnectionState.RECONNECTING -> stringResource(R.string.state_reconnecting)
    ConnectionState.FAILED -> stringResource(R.string.state_failed)
}

/** 连接状态 → 语义色。 */
fun connectionColor(state: ConnectionState): Color = when (state) {
    ConnectionState.CONNECTED -> ConnectedGreen
    ConnectionState.CONNECTING, ConnectionState.HANDSHAKING, ConnectionState.RECONNECTING -> WarningAmber
    ConnectionState.FAILED, ConnectionState.BLUETOOTH_OFF -> DangerRed
    ConnectionState.IDLE -> NeutralGrey
}
