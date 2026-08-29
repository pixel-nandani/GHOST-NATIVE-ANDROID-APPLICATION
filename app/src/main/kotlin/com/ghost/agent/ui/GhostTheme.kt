package com.ghost.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val WarmCream = Color(0xFFF5EFE0)
private val MutedOlive = Color(0xFF8B8B6B)
private val WarmMustard = Color(0xFFD4A017)
private val TerracottaRed = Color(0xFFB04A41)
private val TerracottaOrange = Color(0xFFCD7F32)
private val DustyBlue = Color(0xFF7B9095)
private val TileBeige = Color(0xFFE8E0D0)
private val InkBlack = Color(0xFF2C2C2C)

private val VintageScheme = lightColorScheme(
    primary = MutedOlive,
    onPrimary = Color.White,
    secondary = WarmMustard,
    tertiary = DustyBlue,
    background = WarmCream,
    surface = TileBeige,
    surfaceVariant = Color(0xFFDDD5C5),
    error = TerracottaRed,
)

@Composable
fun GhostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VintageScheme,
        typography = MaterialTheme.typography.copy(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.SansSerif, 
                fontWeight = FontWeight.Black,
                color = InkBlack
            ),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            labelSmall = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.SansSerif, 
                fontWeight = FontWeight.Bold
            ),
        ),
        content = content,
    )
}

object VintageColors {
    val Cream = WarmCream
    val Olive = MutedOlive
    val Mustard = WarmMustard
    val Red = TerracottaRed
    val Orange = TerracottaOrange
    val Blue = DustyBlue
    val Beige = TileBeige
    val Ink = InkBlack
}
