package com.lectern.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lectern.app.R
import com.lectern.app.player.Reader

/**
 * "Terminal Void Audio": deep void dark, acid lime for what is active, cyber cyan for
 * secondary readouts, sharp corners and hard black shadows. From the Stitch design in
 * UI/stitch_marathon_pixel_audiobook_player.
 */
@Immutable
data class Palette(
    val dark: Boolean = true,
    val void: Color = Color(0xFF0C0E10),
    val surface: Color = Color(0xFF121416),
    val low: Color = Color(0xFF1A1C1E),
    val container: Color = Color(0xFF1E2022),
    val high: Color = Color(0xFF282A2C),
    val highest: Color = Color(0xFF333537),
    val ink: Color = Color(0xFFE2E2E5),
    val muted: Color = Color(0xFFC4C9AC),
    val outline: Color = Color(0xFF8E9379),
    val edge: Color = Color(0xFF444933),
    val seam: Color = Color(0xFF2B3034),
    /** Active things: buttons, bars, badges. Acid lime, or the system accent. */
    val accent: Color = Color(0xFFC3F400),
    val onAccent: Color = Color(0xFF161E00),
    /** Recessed wells and badge backgrounds. */
    val well: Color = Color.Black,
    /** Titles. */
    val strong: Color = Color.White,
    val cyan: Color = Color(0xFF00EEFC),
    val cyanSoft: Color = Color(0xFF7DF4FF),
    val alert: Color = Color(0xFFFF5252),
    val amber: Color = Color(0xFFF59E0B),
)

/** The same system in daylight: paper greys, olive lime so text stays readable. */
val LightPalette = Palette(
    dark = false,
    void = Color(0xFFE9EBE2), surface = Color(0xFFF4F5EF), low = Color(0xFFEEF0E7), container = Color(0xFFFFFFFF),
    high = Color(0xFFE2E5D8), highest = Color(0xFFD3D7C6), ink = Color(0xFF15181A), muted = Color(0xFF4F5541),
    outline = Color(0xFF7A806A), edge = Color(0xFFB5BBA2), seam = Color(0xFFCDD1C0),
    accent = Color(0xFF5B7A00), onAccent = Color.White, cyan = Color(0xFF00727A), cyanSoft = Color(0xFF00616A),
    alert = Color(0xFFC62828), amber = Color(0xFFB86E00), well = Color(0xFFE6E8DE), strong = Color(0xFF0C0E10),
)

val LocalPalette = staticCompositionLocalOf { Palette() }

/** Reading highlight: bright marker colors with black text, or the accent. */
fun Palette.highlight(h: Reader.Highlight) = when (h) {
    Reader.Highlight.ACENTO -> accent
    Reader.Highlight.LIMA -> Color(0xFFC3F400)
    Reader.Highlight.CIAN -> Color(0xFF00EEFC)
    Reader.Highlight.AMBAR -> Color(0xFFF5B40B)
}

fun Palette.onHighlight(h: Reader.Highlight) = if (h == Reader.Highlight.ACENTO) onAccent else Color.Black

/* ---------------- Fonts ---------------- */

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w -> Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) },
)

/** Big pixel titles. */
val PixelTitle = FontFamily(Font(R.font.press_start_2p))
/** Small pixel labels and buttons. */
val PixelUi = FontFamily(Font(R.font.silkscreen), Font(R.font.silkscreen_bold, FontWeight.Bold))
/** Telemetry readouts: times, counts, badges. */
val Telemetry = variable(R.font.jetbrains_mono, 400, 500, 700)
/** Reading text and headlines. */
val Grotesk = variable(R.font.space_grotesk, 400, 500, 600, 700)

fun pixelTitle(size: Int, color: Color) = TextStyle(fontFamily = PixelTitle, fontSize = size.sp, color = color, lineHeight = (size * 1.5).sp)
fun pixelUi(size: Int, color: Color, bold: Boolean = false) =
    TextStyle(fontFamily = PixelUi, fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.06.em, lineHeight = (size * 1.4).sp)
fun telemetry(size: Int, color: Color, weight: Int = 500) =
    TextStyle(fontFamily = Telemetry, fontSize = size.sp, color = color, fontWeight = FontWeight(weight), letterSpacing = 0.06.em, lineHeight = (size * 1.4).sp)
fun grotesk(size: Int, color: Color, weight: Int = 400, line: Float = 1.45f) =
    TextStyle(fontFamily = Grotesk, fontSize = size.sp, color = color, fontWeight = FontWeight(weight), lineHeight = (size * line).sp)

/* ---------------- Pixel boxes ---------------- */

/** Flat fill, sharp border and a hard black shadow drawn down-right (no blur, no rounding). */
fun Modifier.pixelBox(fill: Color, border: Color, borderWidth: Dp = 2.dp, shadow: Dp = 3.dp, glow: Color? = null) = drawBehind {
    val s = shadow.toPx()
    val b = borderWidth.toPx()
    if (glow != null) drawRect(glow, topLeft = Offset(s + 2.dp.toPx(), s + 2.dp.toPx()), size = size)
    if (s > 0) drawRect(Color.Black, topLeft = Offset(s, s), size = size)
    drawRect(fill)
    if (b > 0) drawRect(border, topLeft = Offset(b / 2, b / 2), size = Size(size.width - b, size.height - b), style = Stroke(b))
}

/** Light/dark follows the phone unless set in Ajustes; the accent can come from the wallpaper (Android 12+). */
@Composable
fun LecternTheme(theme: Reader.ThemeMode, accent: Reader.AccentMode, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Reader.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        Reader.ThemeMode.DARK -> true
        Reader.ThemeMode.LIGHT -> false
    }
    var p = if (dark) Palette() else LightPalette
    if (accent == Reader.AccentMode.SYSTEM && Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        val dyn = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        p = p.copy(accent = dyn.primary, onAccent = dyn.onPrimary, cyan = dyn.tertiary, cyanSoft = dyn.tertiary)
    }
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.cyan, background = p.surface,
        surface = p.container, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.high,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.onAccent, secondary = p.cyan, background = p.surface,
        surface = p.container, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.high,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/* ---------------- Pixel icons (paths from the Stitch design) ---------------- */

private fun svg(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
}.build()

object Icons {
    val Play = svg("play", "M7 4H10V6H12V8H14V10H16V14H14V16H12V18H10V20H7Z")
    val Pause = svg("pause", "M6 4H10V20H6ZM14 4H18V20H14Z")
    val Prev = svg("prev", "M5 5H7V19H5ZM16 5H19V19H16V17H14V15H12V13H10V11H12V9H14V7H16Z")
    val Next = svg("next", "M17 5H19V19H17ZM5 5H8V7H10V9H12V11H14V13H12V15H10V17H8V19H5Z")
    val PrevChapter = svg("prevch", "M3 5H5V19H3ZM12 5H14V19H12V17H10V15H8V13H6V11H8V9H10V7H12ZM20 5H22V19H20V17H18V15H16V13H14V11H16V9H18V7H20Z")
    val NextChapter = svg("nextch", "M19 5H21V19H19ZM2 5H4V7H6V9H8V11H10V13H8V15H6V17H4V19H2ZM10 5H12V7H14V9H16V11H18V13H16V15H14V17H12V19H10Z")
    val Book = svg("book", "M4 4H11V6H4ZM13 4H20V6H13ZM4 8H11V10H4ZM13 8H20V10H13ZM4 12H11V14H4ZM13 12H20V14H13ZM4 16H11V18H4ZM13 16H20V18H13ZM2 18H22V20H2Z")
    val Headphones = svg("headphones", "M7 3H17V5H7ZM4 6H7V8H4ZM17 6H20V8H17ZM2 9H4V19H2ZM20 9H22V19H20ZM4 15H8V19H4ZM16 15H20V19H16Z")
    val Screen = svg("reader", "M3 4H21V6H3ZM3 6H5V18H3ZM19 6H21V18H19ZM3 18H21V20H3ZM7 9H17V11H7ZM7 13H14V15H7Z")
    val Sliders = svg("sliders", "M3 6H7V8H3ZM9 6H21V8H9ZM6 4H8V10H6ZM14 12H21V14H14ZM3 12H9V14H3ZM8 10H10V16H8ZM12 18H19V20H12ZM2 18H7V20H2ZM8 16H10V22H8Z")
    val Upload = svg("upload", "M11 2H13V4H11ZM9 4H11V6H9ZM15 4H17V6H15ZM9 6H11V8H9ZM15 6H17V8H15ZM11 8H13V14H11ZM5 16H7V18H5ZM7 18H17V20H7ZM17 16H19V18H17Z")
    val Search = svg("search", "M4 4H14V6H4ZM14 6H16V8H14ZM16 8H18V14H16ZM14 14H12V16H14ZM12 16H4V14H12ZM2 6H4V16H2ZM18 16H20V18H18ZM20 18H22V20H20ZM22 20H24V22H22Z")
    val Doc = svg("doc", "M4 2H14V4H4ZM14 4H16V6H14ZM16 6H18V8H16ZM18 8H20V20H6V18H20V8H14V4H4V20H6V22H2V2H4Z")
    val Check = svg("check", "M19 3H5V5H19V3ZM21 5H19V19H21V5ZM19 19H5V21H19V19ZM5 19H3V5H5V19ZM10 12H8V14H10V12ZM12 14H10V16H12V14ZM14 12H12V14H14V12ZM16 10H14V12H16V10ZM16 8H14V10H16V8Z")
    val Moon = svg("moon", "M9 3H15V5H9ZM7 5H9V7H7ZM5 7H7V17H5ZM7 17H9V19H7ZM9 19H15V21H9ZM15 17H17V19H15ZM17 15H19V17H17ZM11 7H13V9H11ZM9 9H11V13H9ZM11 13H13V15H11ZM13 15H17V17H13Z")
    val Voice = svg("voice", "M8 3H12V5H8ZM6 5H8V11H6ZM12 5H14V11H12ZM8 11H12V13H8ZM4 15H16V17H4ZM2 17H4V21H2ZM16 17H18V21H16ZM18 3H20V5H18ZM20 5H22V11H20ZM18 11H20V13H18Z")
    val List = svg("list", "M3 5H5V7H3ZM7 5H21V7H7ZM3 11H5V13H3ZM7 11H21V13H7ZM3 17H5V19H3ZM7 17H21V19H7Z")
    val Close = svg("close", "M5 5H7V7H5ZM7 7H9V9H7ZM9 9H11V11H9ZM11 11H13V13H11ZM13 9H15V11H13ZM15 7H17V9H15ZM17 5H19V7H17ZM9 13H11V15H9ZM7 15H9V17H7ZM5 17H7V19H5ZM13 13H15V15H13ZM15 15H17V17H15ZM17 17H19V19H17Z")
    val Back = svg("back", "M9 5H11V7H9ZM7 7H9V9H7ZM5 9H7V11H5ZM3 11H21V13H3ZM5 13H7V15H5ZM7 15H9V17H7ZM9 17H11V19H9Z")
}
