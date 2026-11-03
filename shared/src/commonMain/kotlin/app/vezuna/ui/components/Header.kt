package app.vezuna.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vezuna.domain.Profile
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.nav.LocalRouteFocus
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import app.vezuna.ui.vezuna
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_check
import vezuna.shared.generated.resources.ic_chevron_down
import vezuna.shared.generated.resources.ic_plus
import vezuna.shared.generated.resources.ic_users
import vezuna.shared.generated.resources.ill_foliage

/** Attaches the shell's route-change focus target (see LocalRouteFocus); [focusable] for plain text. */
@Composable
internal fun Modifier.routeFocus(focusable: Boolean = true): Modifier {
    val requester = LocalRouteFocus.current ?: return this
    val target = focusRequester(requester)
    return if (focusable) target.focusable() else target
}

/** Up to two initials from the live profile name; never a raster avatar. */
fun profileInitials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

@Composable
fun ProfileAvatar(name: String, modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(VezunaTokens.Mint),
        contentAlignment = Alignment.Center,
    ) {
        Text(profileInitials(name), color = VezunaTokens.OnCategory, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Pine band at the top of every root screen: profile chip on the start side, then the title and an optional
 * decorative illustration. No settings gear; settings live under More.
 */
@Composable
fun RootHeader(
    title: String,
    profiles: List<Profile>,
    currentProfileId: String?,
    onSwitchProfile: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    illustration: DrawableResource? = Res.drawable.ill_foliage,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val roles = MaterialTheme.vezuna
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = roles.header,
        contentColor = roles.onHeader,
        shape = RoundedCornerShape(bottomStart = VezunaTokens.SheetRadius, bottomEnd = VezunaTokens.SheetRadius),
    ) {
        Box {
            if (illustration != null && decorativeArtShown()) {
                Image(
                    painterResource(illustration),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).width(132.dp).heightIn(max = 104.dp),
                )
            }
            // The band reaches under the status bar; its content starts below it.
            Column(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(start = VezunaTokens.Gutter, end = VezunaTokens.SpaceMd, top = VezunaTokens.SpaceSm, bottom = VezunaTokens.SpaceLg)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    val current = profiles.firstOrNull { it.profileId == currentProfileId }
                    // The name may take the whole row at large text sizes; actions keep their own width.
                    if (current != null) {
                        ProfileSwitcher(profiles, current, onSwitchProfile, onManageProfiles, onAddProfile, Modifier.weight(1f, fill = false))
                    }
                    actions()
                }
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall.cappedScale(),
                    modifier = Modifier.padding(top = VezunaTokens.SpaceSm).clearOfArt(120.dp).routeFocus().semantics { heading() },
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt(120.dp))
                }
            }
        }
    }
}

/** Compact avatar and name; opens the profile switcher sheet. */
@Composable
fun ProfileSwitcher(
    profiles: List<Profile>,
    current: Profile,
    onSwitchProfile: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val description = vitaText(L10nKey.PROFILE_SWITCH_ACTION, current.displayName)
    Row(
        modifier
            .clip(RoundedCornerShape(VezunaTokens.PillRadius))
            .returnFocusAfter(open)
            .clickable(role = Role.Button) { open = true }
            .heightIn(min = VezunaTokens.TouchTarget)
            .padding(end = VezunaTokens.SpaceSm)
            .clearAndSetSemantics { contentDescription = description; role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd),
    ) {
        ProfileAvatar(current.displayName)
        Text(current.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(painterResource(Res.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(20.dp))
    }
    if (open) {
        ProfileSwitcherSheet(
            profiles = profiles,
            currentProfileId = current.profileId,
            onSwitchProfile = { open = false; if (it != current.profileId) onSwitchProfile(it) },
            onManageProfiles = { open = false; onManageProfiles() },
            onAddProfile = { open = false; onAddProfile() },
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherSheet(
    profiles: List<Profile>,
    currentProfileId: String,
    onSwitchProfile: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onAddProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    VezunaSheet(title = vitaText(L10nKey.PROFILE_SWITCHER_TITLE), onDismiss = onDismiss) {
        GroupedRows {
            val selectedLabel = vitaText(L10nKey.PROFILE_SELECTED)
            profiles.forEach { profile ->
                val selected = profile.profileId == currentProfileId
                ListRow(
                    title = profile.displayName,
                    onClick = { onSwitchProfile(profile.profileId) },
                    supporting = if (selected) selectedLabel else null,
                    leading = { ProfileAvatar(profile.displayName) },
                    trailing = if (selected) {
                        { Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                    showChevron = false,
                    modifier = Modifier.semantics { this.selected = selected },
                )
            }
            ListRow(
                title = vitaText(L10nKey.ADD_PROFILE),
                onClick = onAddProfile,
                leadingIcon = Res.drawable.ic_plus,
            )
            ListRow(
                title = vitaText(L10nKey.MANAGE_PROFILES),
                onClick = onManageProfiles,
                leadingIcon = Res.drawable.ic_users,
            )
        }
    }
}
