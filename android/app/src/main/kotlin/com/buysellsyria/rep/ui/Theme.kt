package com.buysellsyria.rep.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

val Navy = Color(0xFF0E4C7E)
val Blue = Color(0xFF1C7FBF)
val Green = Color(0xFF5CB335)
val Orange = Color(0xFFE8892B)
val Red = Color(0xFFD64545)
val Bg = Color(0xFFF4F6F9)

private val colors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    tertiary = Blue,
    background = Bg,
    surface = Color.White,
    error = Red,
)

private val type = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
)

/** App theme; the whole UI is right-to-left Arabic. */
@Composable
fun RepTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = type, content = content)
    }
}
