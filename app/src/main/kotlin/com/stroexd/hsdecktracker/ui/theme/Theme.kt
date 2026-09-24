package com.stroexd.hsdecktracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.stroexd.hsdecktracker.core.cards.HsClass
import com.stroexd.hsdecktracker.core.cards.Rarity

object HsColors {
    val Gold = Color(0xFFF2B544)
    val GoldDark = Color(0xFFB8862E)
    val Background = Color(0xFF101318)
    val Surface = Color(0xFF171B22)
    val SurfaceHigh = Color(0xFF1F242D)
    val SurfaceHighest = Color(0xFF282E39)
    val Mana = Color(0xFF3FA0FF)
    val ManaDark = Color(0xFF1D5FA8)
    val Win = Color(0xFF55C271)
    val Loss = Color(0xFFE5534B)
    val Draw = Color(0xFFB0B7C3)
    val Dust = Color(0xFFB69CFF)
    val Warning = Color(0xFFFFB74D)
    val TextPrimary = Color(0xFFE8EAEE)
    val TextSecondary = Color(0xFFA3ACBA)
}

private val DarkScheme = darkColorScheme(
    primary = HsColors.Gold,
    onPrimary = Color(0xFF261A00),
    primaryContainer = Color(0xFF3F300E),
    onPrimaryContainer = Color(0xFFFFE2A6),
    secondary = HsColors.Mana,
    onSecondary = Color(0xFF002746),
    secondaryContainer = Color(0xFF15365A),
    onSecondaryContainer = Color(0xFFD2E6FF),
    tertiary = HsColors.Dust,
    onTertiary = Color(0xFF22174A),
    tertiaryContainer = Color(0xFF3A2F66),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = HsColors.Background,
    onBackground = HsColors.TextPrimary,
    surface = HsColors.Surface,
    onSurface = HsColors.TextPrimary,
    surfaceVariant = HsColors.SurfaceHigh,
    onSurfaceVariant = HsColors.TextSecondary,
    surfaceTint = HsColors.Gold,
    surfaceBright = HsColors.SurfaceHighest,
    surfaceDim = HsColors.Background,
    surfaceContainerLowest = Color(0xFF0C0F13),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = HsColors.Surface,
    surfaceContainerHigh = HsColors.SurfaceHigh,
    surfaceContainerHighest = HsColors.SurfaceHighest,
    outline = Color(0xFF465062),
    outlineVariant = Color(0xFF2C333F),
    error = HsColors.Loss,
    onError = Color(0xFF2D0004),
)

@Composable
fun HsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = Typography(), content = content)
}

val HsClass.uiColor: Color get() = Color(color)
val Rarity.uiColor: Color get() = Color(color)

fun winRateColor(rate: Double?): Color = when {
    rate == null -> HsColors.TextSecondary
    rate >= 0.53 -> HsColors.Win
    rate <= 0.47 -> HsColors.Loss
    else -> HsColors.TextPrimary
}
