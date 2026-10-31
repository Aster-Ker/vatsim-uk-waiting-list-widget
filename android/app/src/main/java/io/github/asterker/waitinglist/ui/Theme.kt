package io.github.asterker.waitinglist.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Colour tokens from PanelUI's default "Panel" theme (https://panelui.dev).
@Immutable
data class PanelColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val foreground: Color,
    val muted: Color,
    val border: Color,
    val secondary: Color,
    val primary: Color,
    val primaryForeground: Color,
    val success: Role,
    val warning: Role,
    val destructive: Role,
    val info: Role,
)

// A status colour: solid for dots, foreground for text, tint for badge and alert backgrounds.
@Immutable
data class Role(val solid: Color, val foreground: Color, val tint: Color)

private fun role(card: Color, solid: Long, foreground: Long, tint: Float) =
    Role(Color(solid), Color(foreground), lerp(card, Color(solid), tint))

private val LightCard = Color(0xFFFFFFFF)
private val DarkCard = Color(0xFF191919)

val LightPanel = PanelColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    card = LightCard,
    foreground = Color(0xFF262626),
    muted = Color(0xFF686868),
    border = lerp(LightCard, Color.Black, 0.08f),
    secondary = lerp(LightCard, Color.Black, 0.06f),
    primary = Color(0xFF262626),
    primaryForeground = Color(0xFFFAFAFA),
    success = role(LightCard, 0xFF10B981, 0xFF047857, 0.08f),
    warning = role(LightCard, 0xFFF59E0B, 0xFFB45309, 0.08f),
    destructive = role(LightCard, 0xFFEF4444, 0xFFB91C1C, 0.08f),
    info = role(LightCard, 0xFF3B82F6, 0xFF1D4ED8, 0.08f),
)

val DarkPanel = PanelColors(
    isDark = true,
    background = Color(0xFF141414),
    card = DarkCard,
    foreground = Color(0xFFF5F5F5),
    muted = Color(0xFF878787),
    border = lerp(DarkCard, Color.White, 0.08f),
    secondary = lerp(DarkCard, Color.White, 0.08f),
    primary = Color(0xFFF5F5F5),
    primaryForeground = Color(0xFF262626),
    success = role(DarkCard, 0xFF10B981, 0xFF34D399, 0.14f),
    warning = role(DarkCard, 0xFFF59E0B, 0xFFFBBF24, 0.14f),
    destructive = role(DarkCard, 0xFFF15757, 0xFFF87171, 0.14f),
    info = role(DarkCard, 0xFF3B82F6, 0xFF60A5FA, 0.14f),
)

val LocalPanel = staticCompositionLocalOf { LightPanel }

@Composable
fun PanelTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkPanel else LightPanel
    val scheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.primaryForeground,
            background = colors.background, onBackground = colors.foreground,
            surface = colors.card, onSurface = colors.foreground,
            outline = colors.border, error = colors.destructive.solid,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = colors.primaryForeground,
            background = colors.background, onBackground = colors.foreground,
            surface = colors.card, onSurface = colors.foreground,
            outline = colors.border, error = colors.destructive.solid,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPanel provides colors, content = content)
    }
}
