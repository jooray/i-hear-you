package app.ihearyou.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** System / Light / Dark, resolved before the first frame by the activity. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalPanel = staticCompositionLocalOf { LightPanel }

private val InstrumentShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(3.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

private fun panelTypography(): Typography {
    fun base(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f, family: androidx.compose.ui.text.font.FontFamily = Barlow) =
        TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.sp,
        )

    val defaults = Typography()
    return defaults.copy(
        displayLarge = base(40, FontWeight.Bold, 42, -0.2f, BarlowCondensed),
        displayMedium = base(32, FontWeight.Bold, 34, 0f, BarlowCondensed),
        displaySmall = base(26, FontWeight.SemiBold, 28, 0f, BarlowCondensed),
        headlineLarge = base(26, FontWeight.SemiBold, 30),
        headlineMedium = base(22, FontWeight.SemiBold, 26),
        headlineSmall = base(20, FontWeight.SemiBold, 24),
        titleLarge = base(18, FontWeight.SemiBold, 22),
        titleMedium = base(16, FontWeight.SemiBold, 20),
        titleSmall = base(14, FontWeight.Medium, 18),
        bodyLarge = base(17, FontWeight.Normal, 24),
        bodyMedium = base(15, FontWeight.Normal, 21),
        bodySmall = base(13, FontWeight.Normal, 18),
        labelLarge = base(14, FontWeight.SemiBold, 18, 0.4f),
        labelMedium = base(12, FontWeight.SemiBold, 16, 0.9f),
        labelSmall = base(10, FontWeight.Medium, 14, 1.2f),
    )
}

private fun panelScheme(p: PanelColors) = if (p.dark) {
    darkColorScheme(
        primary = p.seal,
        onPrimary = p.onSeal,
        primaryContainer = p.seal,
        onPrimaryContainer = p.onSeal,
        secondary = p.ink,
        onSecondary = p.plate,
        secondaryContainer = p.plateAlt,
        onSecondaryContainer = p.ink,
        tertiary = p.live,
        onTertiary = p.plate,
        background = p.ground,
        onBackground = p.ink,
        surface = p.plate,
        onSurface = p.ink,
        surfaceVariant = p.plateAlt,
        onSurfaceVariant = p.inkMuted,
        surfaceContainerLowest = p.recess,
        surfaceContainerLow = p.plate,
        surfaceContainer = p.plate,
        surfaceContainerHigh = p.plateAlt,
        surfaceContainerHighest = p.plateAlt,
        outline = p.rule,
        outlineVariant = p.rule,
        error = p.fault,
        onError = p.onFault,
        errorContainer = p.fault,
        onErrorContainer = p.onFault,
        scrim = Color(0xFF000000),
    )
} else {
    lightColorScheme(
        primary = p.seal,
        onPrimary = p.onSeal,
        primaryContainer = p.seal,
        onPrimaryContainer = p.onSeal,
        secondary = p.ink,
        onSecondary = p.plate,
        secondaryContainer = p.plateAlt,
        onSecondaryContainer = p.ink,
        tertiary = p.live,
        onTertiary = p.plate,
        background = p.ground,
        onBackground = p.ink,
        surface = p.plate,
        onSurface = p.ink,
        surfaceVariant = p.plateAlt,
        onSurfaceVariant = p.inkMuted,
        surfaceContainerLowest = p.recess,
        surfaceContainerLow = p.plate,
        surfaceContainer = p.plate,
        surfaceContainerHigh = p.plateAlt,
        surfaceContainerHighest = p.plateAlt,
        outline = p.rule,
        outlineVariant = p.rule,
        error = p.fault,
        onError = p.onFault,
        errorContainer = p.fault,
        onErrorContainer = p.onFault,
        scrim = Color(0xFF000000),
    )
}

@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val panel = if (dark) DarkPanel else LightPanel
    CompositionLocalProvider(
        LocalPanel provides panel,
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = panel.seal,
            backgroundColor = panel.seal.copy(alpha = 0.28f),
        ),
    ) {
        MaterialTheme(
            colorScheme = panelScheme(panel),
            typography = panelTypography(),
            shapes = InstrumentShapes,
            content = content,
        )
    }
}
