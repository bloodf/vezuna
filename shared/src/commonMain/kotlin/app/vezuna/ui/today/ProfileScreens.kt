package app.vezuna.ui.today

import app.vezuna.ui.components.clearOfNavigationBar
import app.vezuna.ui.components.returnFocusAfter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileAge
import app.vezuna.localization.L10nKey
import app.vezuna.localization.UserText
import app.vezuna.localization.message
import app.vezuna.localization.resolve
import app.vezuna.localization.vitaText
import app.vezuna.platform.currentInstant
import app.vezuna.ui.AppScreen
import app.vezuna.ui.VezunaTokens
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.DestructiveButton
import app.vezuna.ui.components.FocusedTaskScaffold
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.LabeledField
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.ProfileAvatar
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.nav.RouteContext
import app.vezuna.ui.vita
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.painterResource
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_info

private val PageModifier = Modifier.fillMaxSize()

/** The error the controller reported for [key], so it can be shown next to its own field. */
private fun UserText?.ifKey(key: L10nKey): UserText? = takeIf { (it as? UserText.Key)?.key == key }

/** Image 005 (More > Profiles): every household profile, each opening its edit page, plus Add profile. */
@Composable
internal fun ProfilesScreen(ctx: RouteContext, focusNewName: Boolean = false) {
    val state = ctx.state
    val currentId = state.data?.profile?.profileId
    var newName by remember { mutableStateOf("") }
    var newRelationship by remember { mutableStateOf("") }
    val today = ProfileAge.today(currentInstant(), TimeZone.currentSystemDefault())
    FocusedTaskScaffold(vitaText(L10nKey.PROFILES_TITLE), ctx::back) {
        Column(
            PageModifier.verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(VezunaTokens.GutterNarrow),
            verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
        ) {
            Text(vitaText(L10nKey.PROFILES_SUBTITLE), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GroupedRows {
                state.profiles.forEach { profile ->
                    val details = listOfNotNull(
                        vitaText(L10nKey.PROFILE_CURRENT).takeIf { profile.profileId == currentId },
                        ProfileAge.years(profile.birthDate, today)?.let { vitaText(L10nKey.PROFILE_AGE, it) },
                    ).joinToString(" · ").ifEmpty { null }
                    ListRow(
                        profile.displayName,
                        onClick = { ctx.navigate(AppScreen.ProfileEdit(profile.profileId)) },
                        supporting = details,
                        leading = { ProfileAvatar(profile.displayName) },
                    )
                }
            }
            val nameFocus = remember { FocusRequester() }
            LaunchedEffect(focusNewName) { if (focusNewName) runCatching { nameFocus.requestFocus() } }
            LabeledField(vitaText(L10nKey.NEW_PROFILE_NAME), newName, { newName = it }, Modifier.focusRequester(nameFocus))
            LabeledField(vitaText(L10nKey.PROFILE_RELATIONSHIP_OPTIONAL), newRelationship, { newRelationship = it })
            PrimaryButton(
                vitaText(L10nKey.ADD_PROFILE),
                {
                    val name = newName.trim()
                    ctx.scope.launch {
                        // Creating switches to the new profile, which resets every stack to its root (profile isolation).
                        ctx.controller.createProfile(name, newRelationship)
                        if (ctx.controller.state.value.error == null) {
                            newName = ""
                            newRelationship = ""
                            ctx.controller.announce(message(L10nKey.PROFILE_CREATED, name))
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = newName.isNotBlank(),
                loading = state.loading,
            )
        }
    }
}

/** Images 006, 007 and 147: rename, birth date, switch and delete (typed-name confirmation; never the last profile). */
@Composable
internal fun ProfileEditScreen(profileId: String, ctx: RouteContext) {
    val state = ctx.state
    val profile = state.profiles.firstOrNull { it.profileId == profileId }
    // Gone after a delete: the delete action itself leaves this page.
    profile ?: return
    FocusedTaskScaffold(vitaText(L10nKey.PROFILE_EDIT_TITLE), ctx::back) {
        ProfileEditForm(profile, ctx)
    }
}

@Composable
private fun ProfileEditForm(profile: Profile, ctx: RouteContext) {
    val state = ctx.state
    val isCurrent = state.data?.profile?.profileId == profile.profileId
    var name by remember(profile.profileId) { mutableStateOf(profile.displayName) }
    var birthDate by remember(profile.profileId) { mutableStateOf(profile.birthDate) }
    var relationship by remember(profile.profileId) { mutableStateOf(profile.relationship.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = name.trim() != profile.displayName || birthDate != profile.birthDate || relationship.trim().ifEmpty { null } != profile.relationship
    val nameError = if (name.isBlank()) vitaText(L10nKey.PROFILE_NAME_REQUIRED) else null
    Column(
        PageModifier.verticalScroll(rememberScrollState()).clearOfNavigationBar().padding(VezunaTokens.GutterNarrow),
        verticalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceMd),
    ) {
        ProfileAvatar(profile.displayName, Modifier.align(Alignment.CenterHorizontally), size = 96)
        LabeledField(vitaText(L10nKey.ONBOARDING_NAME), name, { name = it }, error = nameError)
        BirthDateField(birthDate, { birthDate = it }, error = state.error.ifKey(L10nKey.BIRTH_DATE_INVALID)?.resolve())
        LabeledField(vitaText(L10nKey.PROFILE_RELATIONSHIP_OPTIONAL), relationship, { relationship = it })
        PrimaryButton(
            vitaText(L10nKey.PROFILE_SAVE),
            {
                ctx.scope.launch {
                    val controller = ctx.controller
                    if (name.trim() != profile.displayName) controller.renameProfile(profile.profileId, name)
                    if (controller.state.value.error == null && birthDate != profile.birthDate) controller.setBirthDate(profile.profileId, birthDate)
                    if (controller.state.value.error == null && relationship.trim().ifEmpty { null } != profile.relationship) controller.setRelationship(profile.profileId, relationship)
                    if (controller.state.value.error == null) controller.announce(message(L10nKey.PROFILE_SAVED))
                }
            },
            Modifier.fillMaxWidth(),
            enabled = changed && nameError == null,
            loading = state.loading,
        )
        if (!isCurrent) {
            SecondaryButton(vitaText(L10nKey.SWITCH_TO_PROFILE, profile.displayName), { ctx.switchProfile(profile.profileId) }, Modifier.fillMaxWidth(), enabled = !state.loading)
        }
        val lastProfile = state.profiles.size <= 1
        DestructiveButton(vitaText(L10nKey.DELETE_PROFILE), { ctx.controller.clearMessages(); confirmDelete = true }, Modifier.fillMaxWidth().returnFocusAfter(confirmDelete), enabled = !lastProfile && !state.loading)
        if (lastProfile) LastProfileNotice()
    }
    if (confirmDelete) {
        var typed by remember { mutableStateOf("") }
        ConfirmDialog(
            title = vitaText(L10nKey.PROFILE_DELETE_TITLE, profile.displayName),
            body = vitaText(L10nKey.DELETE_PROFILE_WARNING, profile.displayName),
            confirmLabel = vitaText(L10nKey.DELETE_PROFILE),
            confirmEnabled = typed.trim() == profile.displayName.trim() && !state.loading,
            onConfirm = {
                ctx.scope.launch {
                    ctx.controller.deleteProfile(profile.profileId, typed)
                    // On failure the dialog stays open with the reason; it closes only once the profile is gone.
                    if (ctx.controller.state.value.error == null) {
                        confirmDelete = false
                        // Deleting the active profile already reset the stacks; otherwise leave the deleted profile's page.
                        if (!isCurrent) ctx.back()
                        ctx.controller.announce(message(L10nKey.PROFILE_DELETED, profile.displayName))
                    }
                }
            },
            onDismiss = { confirmDelete = false },
            extra = {
                LabeledField(vitaText(L10nKey.PROFILE_DELETE_TYPE_NAME, profile.displayName), typed, { typed = it }, error = state.error?.resolve())
            },
        )
    }
}

/** Image 147: why Delete is unavailable, as text next to the disabled button (not color or state alone). */
@Composable
private fun LastProfileNotice() {
    val warning = MaterialTheme.vita.warning
    Surface(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(VezunaTokens.CardRadius),
        color = warning.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.padding(VezunaTokens.SpaceMd), horizontalArrangement = Arrangement.spacedBy(VezunaTokens.SpaceSmd)) {
            Icon(painterResource(Res.drawable.ic_info), contentDescription = null, tint = warning, modifier = Modifier.size(24.dp))
            Column {
                Text(vitaText(L10nKey.PROFILE_LAST_TITLE), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(vitaText(L10nKey.PROFILE_LAST_BODY), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
