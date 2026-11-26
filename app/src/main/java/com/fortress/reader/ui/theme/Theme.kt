package com.fortress.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class ExtendedColors(
    val divider: Color,
    val success: Color,
    val warning: Color,
    val surfaceAlt: Color,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        divider = FRColor.DividerLight,
        success = FRColor.SuccessLight,
        warning = FRColor.WarningLight,
        surfaceAlt = FRColor.SurfaceAltLight,
    )
}

private val LightColors = lightColorScheme(
    primary = FRColor.AccentLight,
    onPrimary = Color.White,
    secondary = FRColor.InkLight,
    onSecondary = FRColor.SurfaceLight,
    background = FRColor.BgLight,
    onBackground = FRColor.InkLight,
    surface = FRColor.SurfaceLight,
    onSurface = FRColor.InkLight,
    surfaceVariant = FRColor.SurfaceAltLight,
    onSurfaceVariant = FRColor.InkSecondaryLight,
    outline = FRColor.InkSecondaryLight,
    outlineVariant = FRColor.DividerLight,
    error = FRColor.ErrorLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = FRColor.AccentDark,
    onPrimary = Color.White,
    secondary = FRColor.InkDark,
    onSecondary = FRColor.BgDark,
    background = FRColor.BgDark,
    onBackground = FRColor.InkDark,
    surface = FRColor.SurfaceDark,
    onSurface = FRColor.InkDark,
    surfaceVariant = FRColor.SurfaceAltDark,
    onSurfaceVariant = FRColor.InkSecondaryDark,
    outline = FRColor.InkSecondaryDark,
    outlineVariant = FRColor.DividerDark,
    error = FRColor.ErrorDark,
    onError = Color.White,
)

/** Swiss Industrial: rectangular components, minimal corner radius (0-4 dp). */
private val FRShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

@Composable
fun FortressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    val extended = if (darkTheme) {
        ExtendedColors(FRColor.DividerDark, FRColor.SuccessDark, FRColor.WarningDark, FRColor.SurfaceAltDark)
    } else {
        ExtendedColors(FRColor.DividerLight, FRColor.SuccessLight, FRColor.WarningLight, FRColor.SurfaceAltLight)
    }
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            typography = FRTypography,
            shapes = FRShapes,
            content = content,
        )
    }
}
