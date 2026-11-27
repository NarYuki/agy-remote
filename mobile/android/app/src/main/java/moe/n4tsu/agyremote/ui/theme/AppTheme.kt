package moe.n4tsu.agyremote.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.PI

/** Design tokens, spec section 1. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val surface: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val sidebarActivePill: Color,
    val userBubble: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceMuted: Color,
    val outlineVariant: Color,
    val primary: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val accentFillMuted: Color,
    val stopButtonFill: Color,
    val accentRing: Color,
    val connectedGreen: Color,
    val warningAmber: Color,
    val errorRed: Color,
) {
    /** Brand ramp; washed 30 % toward white in light mode. */
    val ramp: List<Color> = Brand.ramp.map { if (isDark) it else lerp(it, Color.White, 0.30f) }
}

object Brand {
    val mint = Color(0xFF1AD1B8)
    val sky = Color(0xFF38A8FA)
    val violet = Color(0xFF8F6BFF)
    val rose = Color(0xFFFF709E)
    val ramp = listOf(mint, sky, violet, rose)
}

val LightColors = AppColors(
    isDark = false,
    surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F9F9),
    surfaceContainer = Color(0xFFEEF3F3),
    surfaceContainerHigh = Color(0xFFE6EDED),
    surfaceContainerHighest = Color(0xFFD9E2E2),
    sidebarActivePill = Color(0xFFE3ECEC),
    userBubble = Color(0xFFE6EDED),
    onSurface = Color(0xFF1A1F20),
    onSurfaceVariant = Color(0xFF5A6668),
    onSurfaceMuted = Color(0xFF6F7A7C),
    outlineVariant = Color(0xFFC2CBCB),
    primary = Color(0xFF0A66D8),
    accentFill = Color(0xFF111111),
    onAccentFill = Color(0xFFFFFFFF),
    accentFillMuted = Color(0xFFC9CDD1),
    stopButtonFill = Color(0xFFDDE5E5),
    accentRing = Color(0xFF9AA0A6),
    connectedGreen = Color(0xFF15803D),
    warningAmber = Color(0xFFB45309),
    errorRed = Color(0xFFB42318),
)

val DarkColors = AppColors(
    isDark = true,
    surface = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0E0F),
    surfaceContainer = Color(0xFF14181A),
    surfaceContainerHigh = Color(0xFF1B2023),
    surfaceContainerHighest = Color(0xFF262C30),
    sidebarActivePill = Color(0xFF12171A),
    userBubble = Color(0xFF182024),
    onSurface = Color(0xFFEEF2F1),
    onSurfaceVariant = Color(0xFF98A3A6),
    onSurfaceMuted = Color(0xFF6F7A7D),
    outlineVariant = Color(0xFF343C40),
    primary = Color(0xFF4C9BFF),
    accentFill = Color(0xFFF2F2F2),
    onAccentFill = Color(0xFF0B0B0C),
    accentFillMuted = Color(0xFF3A3D42),
    stopButtonFill = Color(0xFF262C30),
    accentRing = Color(0xFF5F6368),
    connectedGreen = Color(0xFF6EE7A0),
    warningAmber = Color(0xFFFBBF4A),
    errorRed = Color(0xFFFDA29B),
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

object AppType {
    fun sans(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) =
        TextStyle(fontSize = size.sp, fontWeight = weight, color = color, fontFamily = FontFamily.Default)

    fun mono(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified) =
        TextStyle(fontSize = size.sp, fontWeight = weight, color = color, fontFamily = FontFamily.Monospace)

    val sidebarTitle = sans(22f)
    val sidebarItem = sans(16.5f)
    val headerBrand = sans(17.5f, FontWeight.Medium)
    val headerModel = sans(17.5f)
    val body = sans(16.5f)
    val secondaryBody = sans(14.5f)
    val label = sans(14f, FontWeight.Medium)
    val smallLabel = sans(12.5f)
    val code = mono(13f)
}

/** Metrics, spec section 1. */
object Metrics {
    const val HEADER_HEIGHT = 46f
    const val HEADER_BUTTON = 42f
    const val SIDEBAR_BUTTON = 44f
    const val COMPOSER_HEIGHT = 58f
    const val COMPOSER_MARGIN_IDLE = 24f
    const val COMPOSER_MARGIN_FOCUSED = 14f
    const val COMPOSER_BOTTOM = 10f
    const val COMPOSER_MAX_WIDTH = 660f
    const val BUBBLE_RADIUS = 24f
    const val MESSAGE_SPACING = 22f
    const val USER_SIDE_PADDING = 16f
    const val AGENT_SIDE_PADDING = 20f
}

/**
 * SwiftUI-style springs (response, damping fraction) mapped to Compose springs:
 * stiffness = (2π / response)², dampingRatio = dampingFraction.
 */
object Motion {
    fun <T> spring(response: Float, damping: Float, visibilityThreshold: T? = null): FiniteAnimationSpec<T> {
        val omega = 2 * PI.toFloat() / response
        return spring(dampingRatio = damping, stiffness = omega * omega, visibilityThreshold = visibilityThreshold)
    }

    fun <T> drawerOpen() = spring<T>(0.34f, 0.92f)
    fun <T> drawerClose() = spring<T>(0.26f, Spring.DampingRatioNoBouncy)
    fun <T> segmented() = spring<T>(0.28f, 0.84f)
    fun <T> inputMorph() = spring<T>(0.30f, 0.86f)
    fun <T> listPress() = spring<T>(0.22f, 0.88f)

    /** Duration of the melt transition (seconds → ms). */
    const val MELT_MS = 500
}

enum class ThemeSetting { SYSTEM, DARK, LIGHT }

@Composable
fun AppTheme(setting: ThemeSetting, content: @Composable () -> Unit) {
    val dark = when (setting) {
        ThemeSetting.SYSTEM -> isSystemInDarkTheme()
        ThemeSetting.DARK -> true
        ThemeSetting.LIGHT -> false
    }
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.primary, background = colors.surface, surface = colors.surface,
            onSurface = colors.onSurface, onBackground = colors.onSurface,
            surfaceContainer = colors.surfaceContainer, surfaceContainerLow = colors.surfaceContainerLow,
            surfaceContainerHigh = colors.surfaceContainerHigh, surfaceContainerHighest = colors.surfaceContainerHighest,
            onSurfaceVariant = colors.onSurfaceVariant, outlineVariant = colors.outlineVariant, error = colors.errorRed,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, background = colors.surface, surface = colors.surface,
            onSurface = colors.onSurface, onBackground = colors.onSurface,
            surfaceContainer = colors.surfaceContainer, surfaceContainerLow = colors.surfaceContainerLow,
            surfaceContainerHigh = colors.surfaceContainerHigh, surfaceContainerHighest = colors.surfaceContainerHighest,
            onSurfaceVariant = colors.onSurfaceVariant, outlineVariant = colors.outlineVariant, error = colors.errorRed,
        )
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val C: AppColors
    @Composable get() = LocalAppColors.current
