package com.lesspass.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Mima 主题系统（Material 3 + Material You 动态取色）。
 *
 * - 动态取色开启时：跟随系统壁纸生成主题色（Android 12+，本应用 minSdk 34 全支持）。
 * - 动态取色关闭时：使用预设种子色 [PresetSeed] 经 [material3SchemeFromSeed] 派生整套配色。
 * - 亮/暗由 [ThemeMode] 决定，默认跟随系统。
 *
 * 设计规范：seed 默认 #4F5BD5（靛蓝）；表面保持中性，仅品牌色随取色变化。
 *
 * 说明：当前 Material3 版本（1.3.1）不提供 `ColorScheme.fromSeed` / `Brightness`，
 * 故关闭动态取色时由本文件内的 [material3SchemeFromSeed] 基于 HSL 从种子色派生 M3 色板。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 预设种子色板（设置页「外观」可选），下标即持久化索引。 */
enum class PresetSeed(val argb: Long, val label: String) {
    INDIGO(0xFF4F5BD5, "靛蓝"),
    TEAL(0xFF00796B, "青绿"),
    ROSE(0xFFD81B60, "玫红"),
    GREEN(0xFF2E7D32, "翠绿"),
    ORANGE(0xFFE65100, "橙"),
    PURPLE(0xFF7B1FA2, "紫");

    companion object {
        val DEFAULT = INDIGO
        fun fromIndex(i: Int): PresetSeed = entries.getOrElse(i) { DEFAULT }
    }
}

@Composable
fun MimaTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    seed: PresetSeed = PresetSeed.DEFAULT,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        // 动态取色：跟随壁纸（Android 12+；minSdk 34 恒可用）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 预设种子色：按亮/暗分别派生整套 M3 配色
        darkTheme -> material3SchemeFromSeed(Color(seed.argb), dark = true)
        else -> material3SchemeFromSeed(Color(seed.argb), dark = false)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

/**
 * 基于 HSL 构造颜色：色相归一化到 [0,360)，饱和度/亮度夹紧到 [0,1]。
 *
 * 色相必须归一化：派生的 secondary/tertiary 会对种子色做 ±30° 偏移，
 * 若种子色色相 >330（如玫红 #D81B60）偏移后会 >360，直接传给 Color.hsl 会抛
 * IllegalArgumentException（HSL must be in range），导致选择该预设色时闪退。
 */
private fun Color.hsl(hue: Float, saturation: Float, lightness: Float): Color =
    Color.hsl(
        ((hue % 360f) + 360f) % 360f,
        saturation.coerceIn(0f, 1f),
        lightness.coerceIn(0f, 1f)
    )

/** 从 RGB(0..1) 计算 HSL（hue 0..360, saturation/lightness 0..1）。 */
private fun rgbToHsl(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) / 2f
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))
    return Triple(h, s, l)
}

/**
 * 从种子色派生整套 Material 3 色板（亮/暗两组）。
 *
 * 以种子色的色调为中心：primary 取种子色，secondary/tertiary 分别做 +30°/-30° 色调偏移，
 * 表面/背景保持中性（不随品牌色变，保证对比度），error 用 M3 标准红。
 * 输出为内存态构造，编译期即可校验，不依赖运行时的动态取色。
 */
private fun material3SchemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val (h, s, _) = rgbToHsl(seed.red, seed.green, seed.blue)

    return if (dark) {
        darkColorScheme(
            primary = seed.hsl(h, s, 0.82f),
            onPrimary = Color.Black,
            primaryContainer = seed.hsl(h, s, 0.30f),
            onPrimaryContainer = seed.hsl(h, s * 0.5f, 0.90f),
            inversePrimary = seed.hsl(h, s, 0.42f),
            secondary = seed.hsl(h + 30f, 0.22f, 0.76f),
            onSecondary = Color.Black,
            secondaryContainer = seed.hsl(h + 30f, 0.25f, 0.28f),
            onSecondaryContainer = seed.hsl(h + 30f, 0.18f, 0.90f),
            tertiary = seed.hsl(h - 30f, 0.28f, 0.76f),
            onTertiary = Color.Black,
            tertiaryContainer = seed.hsl(h - 30f, 0.25f, 0.28f),
            onTertiaryContainer = seed.hsl(h - 30f, 0.18f, 0.90f),
            background = Color(0xFF141218),
            onBackground = Color(0xFFE6E1E5),
            surface = Color(0xFF141218),
            onSurface = Color(0xFFE6E1E5),
            surfaceVariant = Color(0xFF49454F),
            onSurfaceVariant = Color(0xFFCAC4D0),
            surfaceTint = seed.hsl(h, s, 0.82f),
            inverseSurface = Color(0xFFE6E1E5),
            inverseOnSurface = Color(0xFF2F2D33),
            surfaceContainerLowest = Color(0xFF0F0D13),
            surfaceContainerLow = Color(0xFF1A171C),
            surfaceContainer = Color(0xFF1D1B20),
            surfaceContainerHigh = Color(0xFF211F26),
            surfaceContainerHighest = Color(0xFF2B2930),
            outline = Color(0xFF948F99),
            outlineVariant = Color(0xFF49454F),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = seed.hsl(h, s, 0.90f),
            onPrimaryContainer = seed.hsl(h, s, 0.15f),
            inversePrimary = seed.hsl(h, s, 0.42f),
            secondary = seed.hsl(h + 30f, 0.30f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = seed.hsl(h + 30f, 0.35f, 0.88f),
            onSecondaryContainer = seed.hsl(h + 30f, 0.30f, 0.15f),
            tertiary = seed.hsl(h - 30f, 0.30f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = seed.hsl(h - 30f, 0.35f, 0.88f),
            onTertiaryContainer = seed.hsl(h - 30f, 0.30f, 0.15f),
            background = Color(0xFFFDF8FF),
            onBackground = Color(0xFF1B1B1F),
            surface = Color(0xFFFDF8FF),
            onSurface = Color(0xFF1B1B1F),
            surfaceVariant = Color(0xFFE7E0EC),
            onSurfaceVariant = Color(0xFF49454E),
            surfaceTint = seed,
            inverseSurface = Color(0xFF2F2D33),
            inverseOnSurface = Color(0xFFF2EFF4),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF8F2FA),
            surfaceContainer = Color(0xFFF3EDF7),
            surfaceContainerHigh = Color(0xFFEDE7F1),
            surfaceContainerHighest = Color(0xFFE7E1EB),
            outline = Color(0xFF7A757F),
            outlineVariant = Color(0xFFCAC4D0),
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
        )
    }
}
