package com.mynok.myagent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mynok.myagent.data.repo.ThemeColor
import com.mynok.myagent.data.repo.ThemeMode

/**
 * 从种子色派生一整套配色。
 *
 * 不引 material-color-utilities：那会多一个依赖，而这里只需要「主色 + 几档容器色」，
 * 用 HSL 直接推色调足够，且换色时视觉差异明显。
 */
private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHslLocal(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, lightness)
    val delta = max - min
    val saturation = if (lightness > 0.5f) {
        delta / (2f - max - min)
    } else {
        delta / (max + min)
    }
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> ((b - r) / delta + 2f)
        else -> ((r - g) / delta + 4f)
    } / 6f
    return Hsl(hue, saturation, lightness)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    if (saturation <= 0f) return Color(lightness, lightness, lightness)
    val q = if (lightness < 0.5f) {
        lightness * (1f + saturation)
    } else {
        lightness + saturation - lightness * saturation
    }
    val p = 2f * lightness - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(hue + 1f / 3f), channel(hue), channel(hue - 1f / 3f))
}

private fun Color.tone(lightness: Float, saturationScale: Float = 1f): Color {
    val hsl = toHslLocal()
    return hslColor(
        hue = hsl.hue,
        saturation = (hsl.saturation * saturationScale).coerceIn(0f, 1f),
        lightness = lightness.coerceIn(0f, 1f),
    )
}

private fun lightSchemeFrom(seed: Color): ColorScheme = lightColorScheme(
    primary = seed.tone(0.42f),
    onPrimary = Color.White,
    primaryContainer = seed.tone(0.90f, 0.85f),
    onPrimaryContainer = seed.tone(0.20f),
    inversePrimary = seed.tone(0.78f, 0.70f),
    secondary = seed.tone(0.50f, 0.45f),
    onSecondary = Color.White,
    secondaryContainer = seed.tone(0.92f, 0.50f),
    onSecondaryContainer = seed.tone(0.22f),
    tertiary = seed.tone(0.55f, 0.35f),
    onTertiary = Color.White,
    tertiaryContainer = seed.tone(0.93f, 0.40f),
    onTertiaryContainer = seed.tone(0.24f),
    background = seed.tone(0.99f, 0.30f),
    onBackground = seed.tone(0.13f, 0.35f),
    surface = seed.tone(0.99f, 0.30f),
    onSurface = seed.tone(0.13f, 0.35f),
    surfaceVariant = seed.tone(0.93f, 0.30f),
    onSurfaceVariant = seed.tone(0.32f, 0.25f),
    outline = seed.tone(0.55f, 0.20f),
    outlineVariant = seed.tone(0.82f, 0.25f),
    // 容器色系列：NavigationBar / Drawer / Card 都靠它们着色，
    // 不定义的话会退回 Material 默认的紫，跟主题色对不上
    surfaceDim = seed.tone(0.86f, 0.25f),
    surfaceBright = seed.tone(0.99f, 0.30f),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = seed.tone(0.97f, 0.35f),
    surfaceContainer = seed.tone(0.95f, 0.38f),
    surfaceContainerHigh = seed.tone(0.93f, 0.38f),
    surfaceContainerHighest = seed.tone(0.91f, 0.38f),
)

private fun darkSchemeFrom(seed: Color): ColorScheme = darkColorScheme(
    primary = seed.tone(0.80f, 0.70f),
    onPrimary = seed.tone(0.22f),
    primaryContainer = seed.tone(0.34f, 0.75f),
    onPrimaryContainer = seed.tone(0.93f, 0.65f),
    inversePrimary = seed.tone(0.42f),
    secondary = seed.tone(0.78f, 0.40f),
    onSecondary = seed.tone(0.20f),
    secondaryContainer = seed.tone(0.32f, 0.45f),
    onSecondaryContainer = seed.tone(0.92f, 0.50f),
    tertiary = seed.tone(0.80f, 0.30f),
    onTertiary = seed.tone(0.22f),
    tertiaryContainer = seed.tone(0.34f, 0.35f),
    onTertiaryContainer = seed.tone(0.93f, 0.40f),
    background = seed.tone(0.07f, 0.12f),
    onBackground = seed.tone(0.92f, 0.25f),
    surface = seed.tone(0.08f, 0.12f),
    onSurface = seed.tone(0.92f, 0.25f),
    surfaceVariant = seed.tone(0.20f, 0.16f),
    onSurfaceVariant = seed.tone(0.80f, 0.20f),
    outline = seed.tone(0.60f, 0.20f),
    outlineVariant = seed.tone(0.32f, 0.20f),
    surfaceDim = seed.tone(0.06f, 0.12f),
    surfaceBright = seed.tone(0.24f, 0.16f),
    surfaceContainerLowest = seed.tone(0.04f, 0.12f),
    surfaceContainerLow = seed.tone(0.10f, 0.14f),
    surfaceContainer = seed.tone(0.12f, 0.14f),
    surfaceContainerHigh = seed.tone(0.16f, 0.16f),
    surfaceContainerHighest = seed.tone(0.20f, 0.16f),
)

@Composable
fun MyAgentTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.PURPLE,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val seed = Color(themeColor.seed)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkSchemeFrom(seed)
        else -> lightSchemeFrom(seed)
    }

    // 状态栏/导航栏是透明的（edge-to-edge），图标明暗必须跟随 App 内的主题，
    // 不能跟系统深色模式走：用户把 App 强制成浅色时，白色图标会看不见。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MyAgentTypography,
        content = content,
    )
}

/** 从 Compose 的 Context 往上找到宿主 Activity（可能被 ContextThemeWrapper 包了几层）。 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
