package com.yinpage.link.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * ============================================================================
 *  YINPAGE-LINK 配色
 * ============================================================================
 *  风格取向：澎湃 OS 那种「大圆角卡片 + 低饱和」的观感。
 *  - 主色取低饱和青蓝，避免大面积高亮刺眼；
 *  - 深色模式是主场景（夜里戴耳机看电量），因此深色档做了完整定义；
 *  - 动态取色（Material You）可用时优先用系统色，取不到再回落到这里的静态配色。
 * ============================================================================
 */

// ------------------------------ 品牌基色 ------------------------------

internal val Blue80 = Color(0xFFAEC3FF)
internal val BlueGrey80 = Color(0xFFC3C9DC)
internal val Teal80 = Color(0xFFA2D2C8)
internal val Blue40 = Color(0xFF3F63C8)
internal val BlueGrey40 = Color(0xFF5A6B84)
internal val Teal40 = Color(0xFF4E7A72)

// ------------------------------ 亮色方案 ------------------------------

internal val LightColorScheme = lightColorScheme(
    primary = Blue40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FA),
    onPrimaryContainer = Color(0xFF12234F),
    inversePrimary = Blue80,

    secondary = BlueGrey40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F2),
    onSecondaryContainer = Color(0xFF1B2130),

    tertiary = Teal40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCFE9E2),
    onTertiaryContainer = Color(0xFF102B26),

    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE6E9F1),
    onSurfaceVariant = Color(0xFF454A57),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEDEFF6),
    surfaceContainerHigh = Color(0xFFE8EBF3),
    surfaceContainerHighest = Color(0xFFE3E7F0),
    surfaceTint = Blue40,
    inverseSurface = Color(0xFF2E3138),
    inverseOnSurface = Color(0xFFF1F2F6),

    outline = Color(0xFF8A90A0),
    outlineVariant = Color(0xFFC9CEDB),
    scrim = Color(0xFF000000),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

// ------------------------------ 深色方案 ------------------------------

internal val DarkColorScheme = darkColorScheme(
    primary = Blue80,
    onPrimary = Color(0xFF0A1B45),
    primaryContainer = Color(0xFF2A4488),
    onPrimaryContainer = Color(0xFFDCE4FA),
    inversePrimary = Blue40,

    secondary = BlueGrey80,
    onSecondary = Color(0xFF232A38),
    secondaryContainer = Color(0xFF343B4B),
    onSecondaryContainer = Color(0xFFDEE3F0),

    tertiary = Teal80,
    onTertiary = Color(0xFF0B2C27),
    tertiaryContainer = Color(0xFF2A4B45),
    onTertiaryContainer = Color(0xFFCFE9E2),

    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF2A2E38),
    onSurfaceVariant = Color(0xFFB8BDCA),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF14161B),
    surfaceContainer = Color(0xFF191C22),
    surfaceContainerHigh = Color(0xFF1F232A),
    surfaceContainerHighest = Color(0xFF262A33),
    surfaceTint = Blue80,
    inverseSurface = Color(0xFFE6E8EF),
    inverseOnSurface = Color(0xFF2E3138),

    outline = Color(0xFF6A7080),
    outlineVariant = Color(0xFF3A3F4B),
    scrim = Color(0xFF000000),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

// ------------------------------ 语义色（电量 / 状态） ------------------------------
// 这两组颜色在亮色与深色下都保持可读性，因此不跟随 ColorScheme 走。

/** 电量充足 */
val BatteryGood = Color(0xFF3FA46A)

/** 电量中等 */
val BatteryMid = Color(0xFFE0A64B)

/** 电量偏低 */
val BatteryLow = Color(0xFFE0655A)

/** 充电中 */
val ChargingGreen = Color(0xFF43B581)

/** 已连接 */
val ConnectedGreen = Color(0xFF3FA46A)

/** 进行中 / 警告 */
val WarningAmber = Color(0xFFE0A64B)

/** 失败 / 关闭 */
val DangerRed = Color(0xFFE0655A)

/** 中性灰（未连接、未知值） */
val NeutralGrey = Color(0xFF8A90A0)

/** 降噪强调色 */
val NoiseAccent = Color(0xFF4C7DF0)

/** 游戏模式强调色 */
val GameAccent = Color(0xFF8A6BF0)

/** 根据电量百分比取语义色。null 表示未上报。 */
fun batteryColor(percent: Int?): Color = when {
    percent == null -> NeutralGrey
    percent >= 60 -> BatteryGood
    percent >= 20 -> BatteryMid
    else -> BatteryLow
}
