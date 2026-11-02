package app.vezuna.ui

import app.vezuna.ui.more.MorePlatform
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.FontResource
import vezuna.shared.generated.resources.*

private val LightColors = lightColorScheme(
    // Teal #087F8C reaches only 4.41:1 on paper, so text actions and fills use the darker focus teal (5.9:1).
    primary = VezunaTokens.Light.focus,
    onPrimary = VezunaTokens.Light.onPrimary,
    primaryContainer = VezunaTokens.Light.surfaceSubtle,
    onPrimaryContainer = VezunaTokens.Light.text,
    inversePrimary = VezunaTokens.Mint,
    secondary = VezunaTokens.Pine,
    onSecondary = VezunaTokens.Light.onPrimary,
    secondaryContainer = VezunaTokens.Mint,
    onSecondaryContainer = VezunaTokens.OnCategory,
    tertiary = VezunaTokens.Light.warning,
    onTertiary = VezunaTokens.Light.onPrimary,
    tertiaryContainer = VezunaTokens.Light.surfaceSubtle,
    onTertiaryContainer = VezunaTokens.Light.warning,
    background = VezunaTokens.Light.background,
    onBackground = VezunaTokens.Light.text,
    surface = VezunaTokens.Light.surface,
    onSurface = VezunaTokens.Light.text,
    surfaceVariant = VezunaTokens.Light.surfaceSubtle,
    onSurfaceVariant = VezunaTokens.Light.textMuted,
    surfaceTint = VezunaTokens.Light.surface,
    inverseSurface = VezunaTokens.Light.text,
    inverseOnSurface = VezunaTokens.Light.background,
    surfaceBright = VezunaTokens.Light.surface,
    surfaceDim = VezunaTokens.Light.background,
    surfaceContainerLowest = VezunaTokens.Light.surface,
    surfaceContainerLow = VezunaTokens.Light.surface,
    surfaceContainer = VezunaTokens.Light.surface,
    surfaceContainerHigh = VezunaTokens.Light.surfaceSubtle,
    surfaceContainerHighest = VezunaTokens.Light.surfaceSubtle,
    outline = VezunaTokens.Light.border,
    outlineVariant = VezunaTokens.Light.divider,
    error = VezunaTokens.Light.danger,
    onError = VezunaTokens.Light.onPrimary,
    errorContainer = VezunaTokens.Light.surfaceSubtle,
    onErrorContainer = VezunaTokens.Light.danger,
    scrim = VezunaTokens.Deep,
)

private val DarkColors = darkColorScheme(
    primary = VezunaTokens.Dark.primary,
    onPrimary = VezunaTokens.Dark.onPrimary,
    primaryContainer = VezunaTokens.Dark.surfaceSubtle,
    onPrimaryContainer = VezunaTokens.Dark.text,
    inversePrimary = VezunaTokens.Teal,
    secondary = VezunaTokens.Mint,
    onSecondary = VezunaTokens.Deep,
    secondaryContainer = VezunaTokens.Mint,
    onSecondaryContainer = VezunaTokens.OnCategory,
    tertiary = VezunaTokens.Dark.warning,
    onTertiary = VezunaTokens.Dark.onPrimary,
    tertiaryContainer = VezunaTokens.Dark.surfaceSubtle,
    onTertiaryContainer = VezunaTokens.Dark.warning,
    background = VezunaTokens.Dark.background,
    onBackground = VezunaTokens.Dark.text,
    surface = VezunaTokens.Dark.surface,
    onSurface = VezunaTokens.Dark.text,
    surfaceVariant = VezunaTokens.Dark.surfaceSubtle,
    onSurfaceVariant = VezunaTokens.Dark.textMuted,
    surfaceTint = VezunaTokens.Dark.surface,
    inverseSurface = VezunaTokens.Dark.text,
    inverseOnSurface = VezunaTokens.Dark.background,
    surfaceBright = VezunaTokens.Dark.surface,
    surfaceDim = VezunaTokens.Dark.background,
    surfaceContainerLowest = VezunaTokens.Dark.surface,
    surfaceContainerLow = VezunaTokens.Dark.surface,
    surfaceContainer = VezunaTokens.Dark.surface,
    surfaceContainerHigh = VezunaTokens.Dark.surfaceSubtle,
    surfaceContainerHighest = VezunaTokens.Dark.surfaceSubtle,
    outline = VezunaTokens.Dark.border,
    outlineVariant = VezunaTokens.Dark.divider,
    error = VezunaTokens.Dark.danger,
    onError = VezunaTokens.Dark.onPrimary,
    errorContainer = VezunaTokens.Dark.surfaceSubtle,
    onErrorContainer = VezunaTokens.Dark.danger,
    scrim = VezunaTokens.Deep,
)

data class VitaSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val ink: Color,
    val onInk: Color,
    val accentOnInk: Color,
    val mintAura: Color,
    val lilacAura: Color,
    val apricotAura: Color,
    val skyAura: Color,
    val onAura: Color,
)

private val LightSemantic = VitaSemanticColors(
    success = VezunaTokens.Light.success, onSuccess = VezunaTokens.Light.onPrimary,
    successContainer = VezunaTokens.Light.surfaceSubtle, onSuccessContainer = VezunaTokens.Light.success,
    warning = VezunaTokens.Light.warning, onWarning = VezunaTokens.Light.onPrimary,
    warningContainer = VezunaTokens.Light.surfaceSubtle, onWarningContainer = VezunaTokens.Light.warning,
    ink = VezunaTokens.Light.header, onInk = VezunaTokens.Light.onHeader, accentOnInk = VezunaTokens.Mint,
    mintAura = VezunaTokens.Mint, lilacAura = VezunaTokens.Lavender,
    apricotAura = VezunaTokens.Coral, skyAura = VezunaTokens.Citron, onAura = VezunaTokens.OnCategory,
)
private val DarkSemantic = VitaSemanticColors(
    success = VezunaTokens.Dark.success, onSuccess = VezunaTokens.Dark.onPrimary,
    successContainer = VezunaTokens.Dark.surfaceSubtle, onSuccessContainer = VezunaTokens.Dark.success,
    warning = VezunaTokens.Dark.warning, onWarning = VezunaTokens.Dark.onPrimary,
    warningContainer = VezunaTokens.Dark.surfaceSubtle, onWarningContainer = VezunaTokens.Dark.warning,
    ink = VezunaTokens.Dark.header, onInk = VezunaTokens.Dark.onHeader, accentOnInk = VezunaTokens.Mint,
    mintAura = VezunaTokens.Mint, lilacAura = VezunaTokens.Lavender,
    apricotAura = VezunaTokens.Coral, skyAura = VezunaTokens.Citron, onAura = VezunaTokens.OnCategory,
)

val LocalVitaColors = staticCompositionLocalOf { LightSemantic }
val LocalVezunaRoles = staticCompositionLocalOf { VezunaTokens.Light }
val LocalVitaAnimationsEnabled = staticCompositionLocalOf { true }
val MaterialTheme.vita: VitaSemanticColors
    @Composable get() = LocalVitaColors.current

/** Living Color roles (header band, divider, focus) that Material's scheme has no slot for. */
val MaterialTheme.vezuna: VezunaTokens.Roles
    @Composable get() = LocalVezunaRoles.current

/** Tabular figures so values and dates line up in lists and tables. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
private fun vezunaTypography(): Typography {
    val family = FontFamily(
        Font(Res.font.NotoSans_Regular, FontWeight.Normal),
        Font(Res.font.NotoSans_Bold, FontWeight.Bold),
    )
    val base = Typography()
    fun regular(size: Int, lineHeight: Int) = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = lineHeight.sp)
    fun bold(size: Int, lineHeight: Int) = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = lineHeight.sp)
    return base.copy(
        displayLarge = bold(40, 48), displayMedium = bold(36, 44), displaySmall = bold(32, 40),
        headlineLarge = bold(32, 40), headlineMedium = bold(28, 36), headlineSmall = bold(24, 32),
        titleLarge = bold(22, 28), titleMedium = bold(17, 26), titleSmall = bold(16, 24),
        bodyLarge = regular(17, 26), bodyMedium = regular(16, 24), bodySmall = regular(14, 21),
        labelLarge = bold(16, 24), labelMedium = bold(14, 21), labelSmall = regular(12, 18),
    )
}

@Composable
fun VezunaTheme(
    darkTheme: Boolean = MorePlatform.themeMode.isDark(isSystemInDarkTheme()),
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = vezunaTypography(),
        shapes = Shapes(
            small = RoundedCornerShape(VezunaTokens.ControlRadius),
            medium = RoundedCornerShape(VezunaTokens.CardRadius),
            large = RoundedCornerShape(VezunaTokens.CardRadius),
            extraLarge = RoundedCornerShape(VezunaTokens.SheetRadius),
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalVitaColors provides semantic,
            LocalVezunaRoles provides if (darkTheme) VezunaTokens.Dark else VezunaTokens.Light,
            LocalVitaAnimationsEnabled provides animationsEnabled,
            content = content,
        )
    }
}

@Composable
fun Modifier.vitaAnimateContentSize(): Modifier =
    if (LocalVitaAnimationsEnabled.current) animateContentSize() else this
