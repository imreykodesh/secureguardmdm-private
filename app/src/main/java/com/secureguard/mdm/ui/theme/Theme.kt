package com.secureguard.mdm.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import android.util.Log
import java.util.Locale

/**
 * ערכת הצבעים הבהירה של האפליקציה, בגוונים של טורקיז.
 *
 * כל תפקידי הצבע מוגדרים במפורש ולא רק חלקם, כי רכיבים כמו Card, Surface ו-Chip
 * צורכים primaryContainer/tertiaryContainer/surfaceVariant. כאשר תפקידים אלו נשארו
 * בברירת המחדל של Material 3 הם היו סגלגלים ולא תאמו את המיתוג.
 */
private val LightColorScheme = lightColorScheme(
    primary = TurquoisePrimary,
    onPrimary = TurquoiseOnPrimary,
    primaryContainer = TurquoisePrimaryContainer,
    onPrimaryContainer = TurquoiseOnPrimaryContainer,
    inversePrimary = TurquoiseInversePrimary,
    secondary = TurquoiseSecondary,
    onSecondary = TurquoiseOnSecondary,
    secondaryContainer = TurquoiseSecondaryContainer,
    onSecondaryContainer = TurquoiseOnSecondaryContainer,
    tertiary = TurquoiseTertiary,
    onTertiary = TurquoiseOnTertiary,
    tertiaryContainer = TurquoiseTertiaryContainer,
    onTertiaryContainer = TurquoiseOnTertiaryContainer,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondary,
    surfaceTint = TurquoisePrimary,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceLight,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = OutlineColor,
    outlineVariant = OutlineVariantColor,
    error = ErrorColor,
    onError = OnErrorColor,
    errorContainer = ErrorContainerColor,
    onErrorContainer = OnErrorContainerColor
)

/**
 * ה-Theme הראשי של האפליקציה.
 * כל ממשק המשתמש של האפליקציה ייעטף בקומפוזיציה זו.
 */
@Composable
fun SecureGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    overrideStatusBarColor: Color? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = (overrideStatusBarColor ?: colorScheme.primary).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    val systemDirection = if (Locale.getDefault().language == "iw" || Locale.getDefault().language == "he" ) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    // זהו החלק שכופה על כל האפליקציה לעבוד במצב מימין-לשמאל (RTL).
    // כל קומפוזיציה שתהיה תחת ה-Provider הזה "תירש" את הכיווניות.
    Log.d("SecureGuardTheme", "Providing forced layoutDirection=RTL (Theme-level)")
    CompositionLocalProvider(LocalLayoutDirection provides systemDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
