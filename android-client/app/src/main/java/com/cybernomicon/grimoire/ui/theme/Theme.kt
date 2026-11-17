package com.cybernomicon.grimoire.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Cyberdeck palette: near-black voids, neon violet / cyan / magenta.
val Void = Color(0xFF05030A)
val VoidSurface = Color(0xFF0C0820)
val VoidSurfaceHi = Color(0xFF17102E)
val Violet = Color(0xFFB06BFF)
val VioletDeep = Color(0xFF7A33E0)
val Arcane = Color(0xFF2BF5E0)      // neon cyan
val Magenta = Color(0xFFFF2D7E)     // hot magenta (warnings / high severity)
val Ash = Color(0xFFB6A8E6)
val Bone = Color(0xFFEDE7FF)

private val CyberColors = darkColorScheme(
    primary = Violet,
    onPrimary = Void,
    primaryContainer = VioletDeep,
    onPrimaryContainer = Bone,
    secondary = Arcane,
    onSecondary = Void,
    tertiary = Magenta,
    onTertiary = Void,
    background = Void,
    onBackground = Bone,
    surface = VoidSurface,
    onSurface = Bone,
    surfaceVariant = VoidSurfaceHi,
    onSurfaceVariant = Ash,
    outline = VioletDeep,
    error = Magenta,
    onError = Void,
    errorContainer = Color(0xFF3A0A20),
    onErrorContainer = Magenta,
)

// A "deck" typography: monospaced, tracked-out headers for a terminal feel.
private val mono = FontFamily.Monospace
private val CyberType = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = mono,
            fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        titleLarge = titleLarge.copy(fontFamily = mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
        titleMedium = titleMedium.copy(fontFamily = mono,
            fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        labelLarge = labelLarge.copy(fontFamily = mono, letterSpacing = 1.sp),
        labelMedium = labelMedium.copy(fontFamily = mono, letterSpacing = 0.8.sp),
        labelSmall = labelSmall.copy(fontFamily = mono, letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, color = Bone),
    )
}

@Composable
fun CybernomiconTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = CyberColors, typography = CyberType, content = content)
}
