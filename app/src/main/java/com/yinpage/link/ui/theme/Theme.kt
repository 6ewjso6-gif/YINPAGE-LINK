package com.yinpage.link.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 「澎湃」观感的关键之一是大圆角。这里整体把 Material3 的圆角档位调大，
 * 卡片默认 24~28dp，按钮/胶囊 16dp 以上。
 */
val YinpageShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/**
 * 应用主题。
 *
 * @param darkTheme   是否深色，默认跟随系统。
 * @param dynamicColor Material You 动态取色（Android 12+）。取色失败/版本不足时
 *                     自动回落到 [LightColorScheme] / [DarkColorScheme]。
 */
@Composable
fun YinpageLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = YinpageTypography,
        shapes = YinpageShapes,
        content = content,
    )
}
