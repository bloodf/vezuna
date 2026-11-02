package app.vezuna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vezuna.domain.ProfileData
import app.vezuna.localization.L10nKey
import app.vezuna.localization.vitaText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DeviceHealthSettings(state: AppUiState, data: ProfileData, controller: AppController, scope: CoroutineScope) {
    val linkedId = state.healthLinkedProfileId
    val linked = state.profiles.firstOrNull { it.profileId == linkedId }
    SectionHeading(vitaText(L10nKey.SECTION_DEVICE_HEALTH))
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(vitaText(L10nKey.LINKED_PROFILE_EXPLANATION), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            linked?.let { Text(vitaText(L10nKey.LINKED_PROFILE_CURRENT, it.displayName), style = MaterialTheme.typography.titleSmall) }
            if (data.profile.profileId != linkedId) {
                VitaButton(vitaText(L10nKey.LINK_THIS_PROFILE), Icons.Rounded.Link, { scope.launch { controller.setLinkedHealthProfile(data.profile.profileId) } }, outlined = true)
            }
            LinkRow(vitaText(L10nKey.VIEW_VITALS)) { controller.navigate(AppScreen.Vitals) }
        }
    }
}
