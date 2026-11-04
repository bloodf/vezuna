package app.vezuna.ui.security

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDate
import app.vezuna.localization.vitaText
import app.vezuna.security.PassphraseStrengthLevel
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.decorativeArtShown
import app.vezuna.ui.components.StatusKind
import app.vezuna.ui.vita
import kotlin.time.Instant
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_check_circle
import vezuna.shared.generated.resources.ic_error
import vezuna.shared.generated.resources.ic_eye
import vezuna.shared.generated.resources.ic_eye_off
import vezuna.shared.generated.resources.ic_info
import vezuna.shared.generated.resources.ic_warning

/** Scrolling body of a security page: gutters, spacing, room for the keyboard and the navigation bar. */
@Composable
internal fun SecurityPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(horizontal = VezunaTokens.Gutter, vertical = VezunaTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        content = content,
    )
}

/** Centered illustration, heading and body for waiting, success and failure states. */
@Composable
internal fun SecurityHero(illustration: DrawableResource, title: String, body: String? = null, live: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().then(if (live) Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        Image(painterResource(illustration), contentDescription = null, modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().heightIn(max = 165.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * A short status line with an icon, so the meaning never rests on color. Errors are announced assertively,
 * everything else politely, when the text appears or changes.
 */
@Composable
internal fun SecurityNotice(text: String, kind: StatusKind, modifier: Modifier = Modifier, detail: String? = null) {
    val (color, icon) = when (kind) {
        StatusKind.ERROR -> MaterialTheme.colorScheme.error to Res.drawable.ic_error
        StatusKind.WARNING, StatusKind.NEEDS_REVIEW -> MaterialTheme.vita.warning to Res.drawable.ic_warning
        StatusKind.CONFIRMED -> MaterialTheme.vita.success to Res.drawable.ic_check_circle
        StatusKind.INFO -> MaterialTheme.colorScheme.primary to Res.drawable.ic_info
    }
    Surface(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            liveRegion = if (kind == StatusKind.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite
        },
        shape = RoundedCornerShape(VezunaTokens.CardRadius),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(VezunaTokens.SpaceMd), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
                Text(text, style = MaterialTheme.typography.titleSmall, color = if (kind == StatusKind.ERROR) color else MaterialTheme.colorScheme.onSurface)
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * Passphrase input with a show/hide toggle. [newPassword] tells password managers to offer a generated one.
 * The error is part of the field's semantics, so screen readers read it with the label.
 */
@Composable
internal fun PassphraseField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    newPassword: Boolean,
    error: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = RoundedCornerShape(VezunaTokens.ControlRadius),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) Res.drawable.ic_eye_off else Res.drawable.ic_eye),
                    contentDescription = vitaText(if (visible) L10nKey.PASSPHRASE_HIDE else L10nKey.PASSPHRASE_SHOW),
                )
            }
        },
        supportingText = error?.let { text -> { Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
        modifier = Modifier.fillMaxWidth().semantics {
            contentType = if (newPassword) ContentType.NewPassword else ContentType.Password
            if (error != null) error(error)
        },
    )
}

/** Three-step meter plus text and icon; the text is announced once when the level changes, not per keystroke. */
@Composable
internal fun StrengthMeter(level: PassphraseStrengthLevel) {
    val (steps, kind, key) = when (level) {
        PassphraseStrengthLevel.TOO_SHORT -> Triple(1, StatusKind.ERROR, L10nKey.BACKUP_STRENGTH_TOO_SHORT)
        PassphraseStrengthLevel.FAIR -> Triple(2, StatusKind.WARNING, L10nKey.BACKUP_STRENGTH_FAIR)
        PassphraseStrengthLevel.STRONG -> Triple(3, StatusKind.CONFIRMED, L10nKey.BACKUP_STRENGTH_STRONG)
    }
    val color = when (kind) {
        StatusKind.ERROR -> MaterialTheme.colorScheme.error
        StatusKind.WARNING -> MaterialTheme.vita.warning
        else -> MaterialTheme.vita.success
    }
    val icon = when (kind) {
        StatusKind.ERROR -> Res.drawable.ic_error
        StatusKind.WARNING -> Res.drawable.ic_warning
        else -> Res.drawable.ic_check_circle
    }
    Column(verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceXs)) {
            repeat(3) { index ->
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(VezunaTokens.PillRadius)).background(if (index < steps) color else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(vitaText(key), style = MaterialTheme.typography.bodyMedium, color = if (kind == StatusKind.ERROR) color else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** "Last backup 2 Nov 2026 (all profiles)", "Never backed up", or nothing when no history store exists. */
@Composable
internal fun lastBackupText(history: BackupHistory, now: Instant, hasData: Boolean): String {
    val saved = history.savedAt ?: return vitaText(L10nKey.BACKUP_NEVER)
    val date = formatUiDate(saved)
    val text = vitaText(if (history.partial) L10nKey.BACKUP_READY_LEFT_OUT else if (history.household) L10nKey.BACKUP_LAST_HOUSEHOLD else L10nKey.BACKUP_LAST_PROFILE, date)
    return if (history.reminderDue(now, hasData)) "$text. ${vitaText(L10nKey.BACKUP_DUE_SHORT)}" else text
}

/** File sizes for people: "820 KB", "3.4 MB". */
internal fun fileSizeText(bytes: Int): String = when {
    bytes < 1024 * 1024 -> "${(bytes + 1023) / 1024} KB"
    else -> {
        val tenths = (bytes * 10L + 512 * 1024) / (1024 * 1024)
        "${tenths / 10}.${tenths % 10} MB"
    }
}

/** Mint tile behind a decorative illustration, used as the top card of overview pages. */
@Composable
internal fun SecurityPanel(title: String, body: String, illustration: DrawableResource, container: Color = VezunaTokens.Mint) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(VezunaTokens.CardRadius), color = container, contentColor = VezunaTokens.OnCategory) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSm)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            // Gives its width back at very large text, like the other panels' art.
            if (decorativeArtShown()) Image(painterResource(illustration), contentDescription = null, modifier = Modifier.size(88.dp))
        }
    }
}
