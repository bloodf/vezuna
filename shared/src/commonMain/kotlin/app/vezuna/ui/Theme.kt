package app.vezuna.ui

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
    primary = VezunaTokens.Light.primary,
    onPrimary = VezunaTokens.Light.onPrimary,
    primaryContainer = VezunaTokens.Light.surfaceSubtle,
    onPrimaryContainer = VezunaTokens.Light.text,
    secondary = VezunaTokens.Navy,
    onSecondary = VezunaTokens.Offwhite,
    secondaryContainer = VezunaTokens.Light.surfaceSubtle,
    onSecondaryContainer = VezunaTokens.Light.text,
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
    surfaceContainer = VezunaTokens.Light.surface,
    surfaceContainerHigh = VezunaTokens.Light.surfaceSubtle,
    outline = VezunaTokens.Light.border,
    outlineVariant = VezunaTokens.Light.border,
    error = VezunaTokens.Light.danger,
    onError = VezunaTokens.Light.onPrimary,
    errorContainer = VezunaTokens.Light.surfaceSubtle,
    onErrorContainer = VezunaTokens.Light.danger,
)

private val DarkColors = darkColorScheme(
    primary = VezunaTokens.Dark.primary,
    onPrimary = VezunaTokens.Dark.onPrimary,
    primaryContainer = VezunaTokens.Dark.surfaceSubtle,
    onPrimaryContainer = VezunaTokens.Dark.text,
    secondary = VezunaTokens.Dark.accent,
    onSecondary = VezunaTokens.Dark.onAccent,
    secondaryContainer = VezunaTokens.Dark.surfaceSubtle,
    onSecondaryContainer = VezunaTokens.Dark.text,
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
    surfaceContainer = VezunaTokens.Dark.surface,
    surfaceContainerHigh = VezunaTokens.Dark.surfaceSubtle,
    outline = VezunaTokens.Dark.border,
    outlineVariant = VezunaTokens.Dark.border,
    error = VezunaTokens.Dark.danger,
    onError = VezunaTokens.Dark.onPrimary,
    errorContainer = VezunaTokens.Dark.surfaceSubtle,
    onErrorContainer = VezunaTokens.Dark.danger,
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
    ink = VezunaTokens.Navy, onInk = VezunaTokens.Offwhite, accentOnInk = VezunaTokens.Aqua,
    mintAura = VezunaTokens.Light.accent, lilacAura = VezunaTokens.Light.surfaceSubtle,
    apricotAura = VezunaTokens.Light.surfaceSubtle, skyAura = VezunaTokens.Light.surfaceSubtle, onAura = VezunaTokens.Light.onAccent,
)
private val DarkSemantic = VitaSemanticColors(
    success = VezunaTokens.Dark.success, onSuccess = VezunaTokens.Dark.onPrimary,
    successContainer = VezunaTokens.Dark.surfaceSubtle, onSuccessContainer = VezunaTokens.Dark.success,
    warning = VezunaTokens.Dark.warning, onWarning = VezunaTokens.Dark.onPrimary,
    warningContainer = VezunaTokens.Dark.surfaceSubtle, onWarningContainer = VezunaTokens.Dark.warning,
    ink = VezunaTokens.Deep, onInk = VezunaTokens.Mist, accentOnInk = VezunaTokens.Aqua,
    mintAura = VezunaTokens.Dark.surfaceSubtle, lilacAura = VezunaTokens.Dark.surfaceSubtle,
    apricotAura = VezunaTokens.Dark.surfaceSubtle, skyAura = VezunaTokens.Dark.surfaceSubtle, onAura = VezunaTokens.Dark.text,
)

val LocalVitaColors = staticCompositionLocalOf { LightSemantic }
val LocalVitaAnimationsEnabled = staticCompositionLocalOf { true }
val MaterialTheme.vita: VitaSemanticColors
    @Composable get() = LocalVitaColors.current

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
        displayLarge = bold(40, 48), displayMedium = bold(40, 48), displaySmall = bold(32, 38),
        headlineMedium = bold(28, 34), headlineSmall = bold(24, 29),
        titleLarge = bold(24, 29), titleMedium = bold(20, 26), titleSmall = bold(16, 24),
        bodyLarge = regular(16, 24), bodyMedium = regular(16, 24), bodySmall = regular(14, 21),
        labelLarge = bold(16, 24), labelMedium = regular(14, 21), labelSmall = regular(12, 18),
    )
}

@Composable
fun VezunaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalVitaColors provides semantic,
            LocalVitaAnimationsEnabled provides animationsEnabled,
            content = content,
        )
    }
}

@Composable
fun Modifier.vitaAnimateContentSize(): Modifier =
    if (LocalVitaAnimationsEnabled.current) animateContentSize() else this
