package app.vezuna.ui.trends

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.vezuna.ui.components.clearOfArt
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.vezuna.domain.ProfileData
import app.vezuna.domain.trends.VitalSeriesKind
import app.vezuna.localization.L10nKey
import app.vezuna.localization.formatUiDateTime
import app.vezuna.localization.vitaText
import app.vezuna.ui.AppScreen
import app.vezuna.ui.Category
import app.vezuna.ui.components.CategoryPanel
import app.vezuna.ui.components.ConfirmDialog
import app.vezuna.ui.components.EmptyState
import app.vezuna.ui.components.GroupedRows
import app.vezuna.ui.components.ListRow
import app.vezuna.ui.components.PrimaryButton
import app.vezuna.ui.components.SecondaryButton
import app.vezuna.ui.nav.NativeAction
import app.vezuna.ui.nav.RouteContext
import kotlinx.coroutines.launch
import vezuna.shared.generated.resources.Res
import vezuna.shared.generated.resources.ic_connect
import vezuna.shared.generated.resources.ic_refresh
import vezuna.shared.generated.resources.ill_device_link
import vezuna.shared.generated.resources.ill_privacy_device

/**
 * Images 075 / 076 and #64: the one place for device health. Status, last import, link choice, the system
 * permission handoff (the OS shows its own screen) and how to revoke access. Same on Android and iOS.
 */
@Composable
internal fun ConnectionsBody(ctx: RouteContext, data: ProfileData) {
    TrendColumn {
        if (NativeAction.HEALTH !in ctx.nativeActions) {
            EmptyState(Res.drawable.ill_privacy_device, vitaText(L10nKey.TR_HEALTH_UNAVAILABLE_TITLE), vitaText(L10nKey.TR_HEALTH_UNAVAILABLE_BODY))
            return@TrendColumn
        }
        CategoryPanel(Category.ACTIVITY, illustration = Res.drawable.ill_device_link) {
            Text(vitaText(L10nKey.TR_LINK_HEADING), style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearOfArt().semantics { heading() })
            Text(vitaText(L10nKey.LINKED_PROFILE_EXPLANATION), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearOfArt())
        }
        val linkedId = ctx.state.healthLinkedProfileId
        val linked = ctx.state.profiles.firstOrNull { it.profileId == linkedId }
        val current = data.profile
        CardHeading(vitaText(L10nKey.TR_STATUS_HEADING))
        when {
            linked == null -> {
                TrendCard {
                    Text(vitaText(L10nKey.TR_NOT_LINKED), style = MaterialTheme.typography.titleMedium)
                    MutedText(vitaText(L10nKey.TR_NOT_LINKED_BODY))
                }
                var linking by remember { mutableStateOf(false) }
                PrimaryButton(vitaText(L10nKey.TR_LINK_PROFILE, current.displayName), {
                    linking = true
                    ctx.scope.launch {
                        try { ctx.controller.setLinkedHealthProfile(current.profileId) } finally { linking = false }
                    }
                }, Modifier.fillMaxWidth(), loading = linking, icon = Res.drawable.ic_connect)
            }
            linked.profileId == current.profileId -> {
                TrendCard {
                    Text(vitaText(L10nKey.TR_LINKED_TO, linked.displayName), style = MaterialTheme.typography.titleMedium)
                    val last = data.vitals.lastSync
                    MutedText(if (last != null) vitaText(L10nKey.TR_LAST_IMPORT, formatUiDateTime(last)) else vitaText(L10nKey.TR_NO_IMPORT_YET))
                }
                PrimaryButton(vitaText(L10nKey.TR_IMPORT_NOW), { ctx.nativeActions.perform(NativeAction.HEALTH) }, Modifier.fillMaxWidth(), icon = Res.drawable.ic_refresh)
                MutedText(vitaText(L10nKey.TR_PERMISSION_NOTE))
                SecondaryButton(vitaText(L10nKey.VIEW_VITALS), { ctx.navigate(AppScreen.Vitals) }, Modifier.fillMaxWidth())
            }
            else -> LinkedElsewhere(ctx, linked.displayName, linked.profileId, current.displayName, current.profileId)
        }
        GroupedRows(title = vitaText(L10nKey.TR_DATA_TYPES)) {
            VitalSeriesKind.entries.forEach { kind ->
                val copy = kind.copy()
                ListRow(vitaText(copy.title), supporting = vitaText(copy.granularity), leadingIcon = copy.icon, category = copy.category)
            }
        }
        MutedText(vitaText(L10nKey.TR_READ_ONLY_NOTE))
        TrendCard {
            CardHeading(vitaText(L10nKey.TR_REVOKE_HEADING))
            Text(vitaText(L10nKey.TR_REVOKE_BODY), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Changing the link moves future imports only; data already saved stays where it is. */
@Composable
internal fun RelinkDialog(ctx: RouteContext, newName: String, newId: String, oldName: String, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = vitaText(L10nKey.TR_RELINK_TITLE, newName),
        body = vitaText(L10nKey.TR_RELINK_BODY, newName, oldName),
        confirmLabel = vitaText(L10nKey.TR_RELINK, newName),
        onConfirm = {
            onDismiss()
            ctx.scope.launch { ctx.controller.setLinkedHealthProfile(newId) }
        },
        onDismiss = onDismiss,
        destructive = false,
    )
}
