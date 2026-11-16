package com.cybernomicon.grimoire.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cybernomicon palette: dark-fantasy occult + cyberpunk.
// Deep void backgrounds, violet/purple primaries, subtle cyan arcane accents.
val Void = Color(0xFF0B0614)
val VoidSurface = Color(0xFF140B24)
val VoidSurfaceHi = Color(0xFF1E1236)
val Violet = Color(0xFF9A6BFF)
val VioletDeep = Color(0xFF6D3BD1)
val Arcane = Color(0xFF34E7E4)      // cyan accent
val Ember = Color(0xFFFF5C8A)       // warning / high severity
val Ash = Color(0xFFB9A7E0)
val Bone = Color(0xFFE9E2F7)

private val CybernomiconColors = darkColorScheme(
    primary = Violet,
    onPrimary = Bone,
    primaryContainer = VioletDeep,
    secondary = Arcane,
    onSecondary = Void,
    tertiary = Ember,
    background = Void,
    onBackground = Bone,
    surface = VoidSurface,
    onSurface = Bone,
    surfaceVariant = VoidSurfaceHi,
    onSurfaceVariant = Ash,
    error = Ember,
)

@Composable
fun CybernomiconTheme(content: @Composable () -> Unit) {
    // The grimoire is always dark; isSystemInDarkTheme is read only so a
    // future light ritual mode is a one-line change.
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = CybernomiconColors,
        typography = Typography(),
        content = content,
    )
}
