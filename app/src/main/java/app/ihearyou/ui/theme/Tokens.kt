package app.ihearyou.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ihearyou.R

/**
 * The instrument palette.
 *
 * The product is a measuring and control instrument for two ears, so the surface is built from the
 * world of the sealed utility meter: enamel panel, black printed ink, one vermilion seal, hairline
 * rules, recessed wells, glass windows. Nothing here is a decorative tint; every role is either
 * ground, ink, a rule, or a state that also carries a word.
 */
@Immutable
data class PanelColors(
    val ground: Color,
    val plate: Color,
    val plateAlt: Color,
    val recess: Color,
    val ink: Color,
    val inkMuted: Color,
    val rule: Color,
    val ruleStrong: Color,
    val seal: Color,
    val onSeal: Color,
    val live: Color,
    val warn: Color,
    val fault: Color,
    val onFault: Color,
    val dark: Boolean,
)

val LightPanel = PanelColors(
    ground = Color(0xFFE4E7DB),
    plate = Color(0xFFEDEFE5),
    plateAlt = Color(0xFFDFE2D5),
    recess = Color(0xFFD2D6C7),
    ink = Color(0xFF14170F),
    inkMuted = Color(0xFF4C5247),
    rule = Color(0xFFB0B6A6),
    ruleStrong = Color(0xFF8C9383),
    seal = Color(0xFFB23A1E),
    onSeal = Color(0xFFF7F4E7),
    live = Color(0xFF256B45),
    warn = Color(0xFF7A5200),
    fault = Color(0xFFA3221A),
    onFault = Color(0xFFF7F4E7),
    dark = false,
)

val DarkPanel = PanelColors(
    ground = Color(0xFF15180F),
    plate = Color(0xFF1C2016),
    plateAlt = Color(0xFF20251A),
    recess = Color(0xFF10130B),
    ink = Color(0xFFECE9DA),
    inkMuted = Color(0xFFA9AE9F),
    rule = Color(0xFF3B4134),
    ruleStrong = Color(0xFF58604E),
    seal = Color(0xFFE8633F),
    onSeal = Color(0xFF1A0F0A),
    live = Color(0xFF74C596),
    warn = Color(0xFFE0A94A),
    fault = Color(0xFFFF9A87),
    onFault = Color(0xFF200A05),
    dark = true,
)

/**
 * Barlow: a grotesque drawn from Californian plate lettering — public-signage DNA, a flat-sided
 * figure set, and long descenders that stay legible at 200 % font scale. Condensed cut carries the
 * counter-drum numerals.
 */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

object InstrumentType {
    val wordmark = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.06.sp,
    )
    val reading = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 38.sp,
        fontFeatureSettings = "tnum",
    )
    val readingSmall = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum",
    )
    val number = TextStyle(
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    )
    /** Engraved plate capitals: tracked, small, upper case. */
    val plate = TextStyle(
        fontFamily = Barlow,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.3.sp,
    )
    val plateLarge = TextStyle(
        fontFamily = Barlow,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.4.sp,
    )
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

object Space {
    val hair = 1.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}
