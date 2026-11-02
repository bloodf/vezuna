package app.vezuna.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Vezuna Living Color tokens (docs/redesign/specs/DESIGN_SYSTEM.md). The values are mirrored in
 * brand/tokens/tokens.json ("livingColor") and iosApp/Vezuna/VezunaStyle.swift; check_brand.py fails on drift
 * and measures every text/background pair below in both themes.
 */
object VezunaTokens {
    val Pine = Color(0xFF123B46)
    val Navy = Pine
    val Teal = Color(0xFF087F8C)
    val Mint = Color(0xFF71E2CC)
    val Aqua = Mint
    val Coral = Color(0xFFFF8A65)
    val Lavender = Color(0xFFB8ABFF)
    val Citron = Color(0xFFDDF27C)
    val Paper = Color(0xFFF6F7F2)
    val Offwhite = Color(0xFFF5FAF9)
    val Ink = Color(0xFF152E35)
    val Deep = Color(0xFF0C2027)
    val Mist = Color(0xFFE7F5F2)

    /** Ink on category fills; never white small text on mint, coral, lavender or citron. */
    val OnCategory = Pine

    class Roles(
        val background: Color,
        val surface: Color,
        val surfaceSubtle: Color,
        val text: Color,
        val textMuted: Color,
        val primary: Color,
        val onPrimary: Color,
        /** Required control outline (fields, outlined buttons). */
        val border: Color,
        /** Decorative divider; never the only boundary of a control. */
        val divider: Color,
        val focus: Color,
        /** Teal for text and links; #087F8C stays a fill (4.41:1 as text on paper). */
        val link: Color,
        val warning: Color,
        val danger: Color,
        val success: Color,
        val info: Color,
        /** Pine upper band of root screens and its content. */
        val header: Color,
        val onHeader: Color,
        /** Secondary text on the pine band; never textMuted or teal there. */
        val onHeaderMuted: Color,
        /** Filled action placed on the header: mint with dark label. */
        val headerAction: Color,
        val onHeaderAction: Color,
        val accent: Color,
        val onAccent: Color,
    )

    val Light = Roles(
        background = Paper,
        surface = Color(0xFFFFFFFF),
        surfaceSubtle = Color(0xFFE7F3F1),
        text = Ink,
        textMuted = Color(0xFF526C74),
        primary = Teal,
        onPrimary = Color(0xFFFFFFFF),
        border = Color(0xFF6C878F),
        divider = Color(0xFFDCE3DF),
        focus = Color(0xFF086A78),
        link = Color(0xFF076F7B),
        warning = Color(0xFF805500),
        danger = Color(0xFFB3263B),
        success = Color(0xFF176B50),
        info = Color(0xFF245DA8),
        header = Pine,
        onHeader = Color(0xFFFFFFFF),
        onHeaderMuted = Color(0xFFADC7CE),
        headerAction = Mint,
        onHeaderAction = Pine,
        accent = Mint,
        onAccent = Pine,
    )

    val Dark = Roles(
        background = Deep,
        surface = Color(0xFF14313A),
        surfaceSubtle = Color(0xFF1A3E48),
        text = Mist,
        textMuted = Color(0xFFADC7CE),
        primary = Mint,
        onPrimary = Deep,
        border = Color(0xFF72969F),
        divider = Color(0xFF35545D),
        focus = Mint,
        link = Mint,
        warning = Color(0xFFF5CB73),
        danger = Color(0xFFFF9AAA),
        success = Color(0xFF87DEB4),
        info = Color(0xFF9DC4FF),
        header = Pine,
        onHeader = Mist,
        onHeaderMuted = Color(0xFFADC7CE),
        headerAction = Mint,
        onHeaderAction = Deep,
        accent = Color(0xFF35B7B8),
        onAccent = Deep,
    )

    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceSmd = 12.dp
    val SpaceMd = 16.dp
    val SpaceLg = 24.dp
    val SpaceXl = 32.dp
    val SpaceXxl = 48.dp

    /** Phone side gutter, 20 on narrow displays. */
    val Gutter = 24.dp
    val GutterNarrow = 20.dp
    val ControlRadius = 12.dp
    val CardRadius = 20.dp
    val SheetRadius = 28.dp
    val PillRadius = 999.dp
    val TouchTarget = 48.dp

    /** Typical row/control height. */
    val RowHeight = 52.dp

    /** Navigation rail replaces the bottom bar at this width. */
    val RailBreakpoint = 600.dp

    val TitleSize = 32.sp
    val HeadingSize = 22.sp
    val BodySize = 16.sp
    val SecondarySize = 14.sp
    val CaptionSize = 12.sp

    const val MotionFastMs = 120
    const val MotionNormalMs = 200
    const val MotionReducedMs = 0
}

/**
 * Content identity colors. They name a destination or kind of content, never a health status.
 * [fill] is a background for ink text only. Lines, marks and informative icons use [series]: the fills are
 * too light on paper (1.1 to 2.2:1), so light theme uses darker variants that reach 3:1.
 */
enum class Category(val fill: Color, private val seriesLight: Color) {
    RECORDS(VezunaTokens.Mint, Color(0xFF0B6B5C)),
    CARE(VezunaTokens.Coral, Color(0xFFC0552F)),
    TRENDS(VezunaTokens.Lavender, Color(0xFF6F5FD6)),
    ACTIVITY(VezunaTokens.Citron, Color(0xFF5E7A00)),
    ;

    val onFill: Color get() = VezunaTokens.OnCategory

    fun series(darkTheme: Boolean): Color = if (darkTheme) fill else seriesLight
}
