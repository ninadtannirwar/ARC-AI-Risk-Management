package com.pheonex.arc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// --- Groww-style Modern Fintech Palette ---
val GrowwGreen    = Color(0xFF00D09C) // Groww Primary Green
val GrowwRed      = Color(0xFFEB5B3C) // Modern Red
val GrowwBlue     = Color(0xFF5367FF) // Info Blue
val GrowwAmber    = Color(0xFFFFB340) // Warning Amber

// Dark Mode Palette (Deep & Professional)
val DarkBg        = Color(0xFF0F1217) 
val DarkSurface   = Color(0xFF181C24)
val DarkSurfaceVar = Color(0xFF222731)

// Light Mode Palette (Clean & Spacious)
val LightBg       = Color(0xFFF8F9FA)
val LightSurface  = Color(0xFFFFFFFF)
val LightSurfaceVar = Color(0xFFE9ECEF)

// Legacy compatibility aliases (so other screens don't break immediately)
val ArcBlue       = GrowwBlue
val ArcGreen      = GrowwGreen
val ArcRed        = GrowwRed
val ArcOrange     = GrowwAmber
val ArcPurple     = Color(0xFF9B51E0)
val ArcCyan       = Color(0xFF2D9CDB)
val ArcAmber      = GrowwAmber
val ArcDarkBg     = DarkBg
val ArcSurface    = DarkSurface
val ArcSurfaceVar = DarkSurfaceVar

private val DarkColors = darkColorScheme(
    primary           = GrowwGreen,
    onPrimary         = Color.Black,
    primaryContainer  = GrowwGreen.copy(alpha = 0.1f),
    onPrimaryContainer= GrowwGreen,
    secondary         = GrowwBlue,
    background        = DarkBg,
    surface           = DarkSurface,
    surfaceVariant    = DarkSurfaceVar,
    onSurface         = Color.White,
    onSurfaceVariant  = Color.White.copy(0.6f)
)

private val LightColors = lightColorScheme(
    primary           = GrowwGreen,
    onPrimary         = Color.White,
    primaryContainer  = GrowwGreen.copy(alpha = 0.1f),
    secondary         = GrowwBlue,
    background        = LightBg,
    surface           = LightSurface,
    surfaceVariant    = LightSurfaceVar,
    onSurface         = Color(0xFF1A1D23),
    onSurfaceVariant  = Color(0xFF6C757D)
)

val ArcTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    )
)

@Composable
fun ArcTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = ArcTypography,
        content     = content
    )
}
