package com.blokqr.app.ui.theme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
/** Choix de thème offert à l'utilisateur. */
enum class ThemeChoice(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    WARM("warm"),
    COLD("cold");
    companion object {
        fun fromKey(k: String?): ThemeChoice = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}
private val DarkScheme = darkColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Navy, onPrimaryContainer = OnSurfaceStrong,
    secondary = BlueBright, onSecondary = NavyDeep,
    background = NavyDeep, onBackground = OnSurfaceDim,
    surface = Surface, onSurface = OnSurfaceStrong,
    surfaceVariant = SurfaceVariant, onSurfaceVariant = OnSurfaceDim,
    outline = OutlineDim, outlineVariant = OutlineDim,
    error = MaliciousRed, onError = Color.White, scrim = Color.Black,
)
private val LightScheme = lightColorScheme(
    primary = Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6F6), onPrimaryContainer = Navy,
    secondary = Navy, onSecondary = Color.White,
    background = LightBg, onBackground = LightOnBg,
    surface = LightSurface, onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant, onSurfaceVariant = LightOnSurfaceVar,
    outline = LightOutline, outlineVariant = LightOutline,
    error = MaliciousRed, onError = Color.White, scrim = Color.Black,
)
private val WarmScheme = darkColorScheme(
    primary = WarmPrimary, onPrimary = Color(0xFF2A1B0A),
    primaryContainer = Color(0xFF4A382A), onPrimaryContainer = WarmOnSurface,
    secondary = WarmPrimary, onSecondary = Color(0xFF2A1B0A),
    background = WarmBg, onBackground = WarmOnSurface,
    surface = WarmSurface, onSurface = WarmOnSurface,
    surfaceVariant = WarmSurfaceVariant, onSurfaceVariant = WarmOnSurfaceVar,
    outline = WarmOutline, outlineVariant = WarmOutline,
    error = MaliciousRed, onError = Color.White, scrim = Color.Black,
)
private val ColdScheme = darkColorScheme(
    primary = ColdPrimary, onPrimary = Color(0xFF04222A),
    primaryContainer = Color(0xFF143A49), onPrimaryContainer = ColdOnSurface,
    secondary = ColdPrimary, onSecondary = Color(0xFF04222A),
    background = ColdBg, onBackground = ColdOnSurface,
    surface = ColdSurface, onSurface = ColdOnSurface,
    surfaceVariant = ColdSurfaceVariant, onSurfaceVariant = ColdOnSurfaceVar,
    outline = ColdOutline, outlineVariant = ColdOutline,
    error = MaliciousRed, onError = Color.White, scrim = Color.Black,
)
@Composable
fun BlokQrTheme(
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = when (choice) {
        ThemeChoice.SYSTEM -> if (systemDark) DarkScheme else LightScheme
        ThemeChoice.LIGHT -> LightScheme
        ThemeChoice.DARK -> DarkScheme
        ThemeChoice.WARM -> WarmScheme
        ThemeChoice.COLD -> ColdScheme
    }
    // Edge-to-edge : contraste des icônes des barres système lié au thème RÉSOLU
    // de l'app (et non au mode du système). Fond clair -> icônes sombres ; fond
    // sombre -> icônes claires. Garantit des icônes toujours lisibles, y compris
    // si l'utilisateur force un thème opposé à celui du téléphone. Seuls LIGHT et
    // SYSTEM-clair sont des fonds clairs ; DARK / WARM / COLD sont sombres.
    val lightBars = when (choice) {
        ThemeChoice.LIGHT -> true
        ThemeChoice.SYSTEM -> !systemDark
        else -> false
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = lightBars
                controller.isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = BlokQrTypography,
        shapes = BlokQrShapes,
        content = content,
    )
}
/** Remonte la chaîne de Context pour retrouver l'Activity hôte (robuste face à
 *  un ContextThemeWrapper). Renvoie null hors d'une Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
