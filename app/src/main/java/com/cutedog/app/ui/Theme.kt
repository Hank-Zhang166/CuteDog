package com.cutedog.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF376A58), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EDE3), onPrimaryContainer = Color(0xFF244B3C),
    secondary = Color(0xFF947452), secondaryContainer = Color(0xFFF2E7D6),
    background = Color(0xFFFAF8F2), onBackground = Color(0xFF2E352E),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF2E352E),
    surfaceVariant = Color(0xFFF1EEE6), onSurfaceVariant = Color(0xFF777B71),
    outline = Color(0xFFC5C8BC), outlineVariant = Color(0xFFE8E9DF),
    error = Color(0xFFAB4848), errorContainer = Color(0xFFF9E5DF)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FCDB4), onPrimary = Color(0xFF163C2D),
    primaryContainer = Color(0xFF294B3D), onPrimaryContainer = Color(0xFFD0EDDB),
    secondary = Color(0xFFDFC4A3), secondaryContainer = Color(0xFF514334),
    background = Color(0xFF191E1B), onBackground = Color(0xFFE7E9DF),
    surface = Color(0xFF222924), onSurface = Color(0xFFE7E9DF),
    surfaceVariant = Color(0xFF303830), onSurfaceVariant = Color(0xFFB2BBAF),
    outline = Color(0xFF73806F), outlineVariant = Color(0xFF3B463D)
)

@Composable
fun CuteDogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 39.sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 34.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
        ),
        content = content
    )
}
