package com.bloodf.vitahearth.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bloodf.vitahearth.localization.L10nKey
import com.bloodf.vitahearth.localization.vitaText
import vitahearth.shared.generated.resources.*

@Composable
fun MiniBrand(onDevice: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = org.jetbrains.compose.resources.painterResource(if (isSystemInDarkTheme()) Res.drawable.vezuna_lockup_dark else Res.drawable.vezuna_lockup_color),
            contentDescription = vitaText(L10nKey.VITAHEARTH),
            modifier = Modifier.weight(1f).height(40.dp).padding(end = 12.dp),
        )
        TextButton(onClick = onDevice, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
            Icon(VezunaIcons.Connect, vitaText(L10nKey.DEVICE))
            Spacer(Modifier.size(5.dp))
            Text(vitaText(L10nKey.DEVICE))
        }
    }
}

@Composable
fun ScreenColumn(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
fun SectionHeading(title: String, supporting: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun WorkflowTile(label: String, icon: ImageVector, container: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 100.dp).vitaAnimateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row {
                Icon(icon, vitaText(L10nKey.WORKFLOW_ACCESSIBILITY, label), Modifier.size(28.dp), tint = MaterialTheme.vita.onAura)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.vita.onAura)
            }
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.vita.onAura)
        }
    }
}

@Composable
fun VitaButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, outlined: Boolean = false) {
    val content: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text)
    }
    if (outlined) {
        OutlinedButton(onClick, modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled, content = { content() })
    } else {
        Button(onClick, modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled, content = { content() })
    }
}

@Composable
fun StatusPill(text: String, icon: ImageVector = Icons.Rounded.CheckCircle, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = content)
            Spacer(Modifier.size(5.dp))
            Text(text, color = content, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun PrivacyStatus() {
    var confirmed by remember { mutableStateOf(false) }
    val motionEnabled = LocalVitaAnimationsEnabled.current
    LaunchedEffect(Unit) { confirmed = true }
    val targetScale by animateFloatAsState(if (confirmed) 1f else 0.88f, if (motionEnabled) tween(VezunaTokens.MotionNormalMs) else androidx.compose.animation.core.snap(), label = "privacy-check")
    val tint by animateColorAsState(MaterialTheme.vita.success, if (motionEnabled) tween(VezunaTokens.MotionNormalMs) else androidx.compose.animation.core.snap(), label = "privacy-color")
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.vita.successContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HealthAndSafety, vitaText(L10nKey.RECORDS_PROTECTED), Modifier.size(26.dp).scale(targetScale), tint = tint)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(vitaText(L10nKey.ON_DEVICE), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.vita.onSuccessContainer)
                Text(vitaText(L10nKey.PRIVATE_BY_DESIGN), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.vita.onSuccessContainer)
            }
            Icon(Icons.Rounded.CheckCircle, vitaText(L10nKey.LOCAL_PRIVACY_ACTIVE), tint = tint)
        }
    }
}

@Composable
fun EmptyState(title: String, explanation: String, icon: ImageVector = Icons.Rounded.Info) {
    Card(
        Modifier.fillMaxWidth().semantics { contentDescription = "$title. $explanation" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InlineNotice(text: String, icon: ImageVector = Icons.Rounded.Info) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.size(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
fun LinkRow(text: String, onClick: () -> Unit) {
    TextButton(onClick, Modifier.defaultMinSize(minHeight = 48.dp)) {
        Text(text)
        Spacer(Modifier.size(4.dp))
        Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp))
    }
}
