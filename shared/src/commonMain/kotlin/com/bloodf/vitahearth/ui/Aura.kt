package com.bloodf.vitahearth.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bloodf.vitahearth.localization.L10nKey
import com.bloodf.vitahearth.localization.vitaText

@Composable
fun PrivacyAura() {
    val motionEnabled = LocalVitaAnimationsEnabled.current
    val transition = if (motionEnabled) rememberInfiniteTransition(label = "privacy-aura") else null
    val breathe = if (transition == null) {
        1f
    } else {
        transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
            label = "aura-breath",
        ).value
    }
    val mint = MaterialTheme.vita.mintAura
    val lilac = MaterialTheme.vita.lilacAura
    val apricot = MaterialTheme.vita.apricotAura
    val sky = MaterialTheme.vita.skyAura
    val description = vitaText(L10nKey.PRIVACY_AURA_ACCESSIBILITY)
    Box(
        Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = description
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to mint.copy(alpha = 0.72f),
                    0.62f to lilac.copy(alpha = 0.40f),
                    1f to Color.Transparent,
                    center = center,
                    radius = size.minDimension * 0.52f * breathe,
                ),
                center = center,
                radius = size.minDimension * 0.52f * breathe,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to apricot.copy(alpha = 0.54f),
                    1f to Color.Transparent,
                    center = center.copy(x = center.x - 34f * breathe, y = center.y - 18f),
                    radius = size.minDimension * 0.31f * breathe,
                ),
                center = center.copy(x = center.x - 34f * breathe, y = center.y - 18f),
                radius = size.minDimension * 0.31f * breathe,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to sky.copy(alpha = 0.48f),
                    1f to Color.Transparent,
                    center = center.copy(x = center.x + 38f * breathe, y = center.y + 22f),
                    radius = size.minDimension * 0.34f * breathe,
                ),
                center = center.copy(x = center.x + 38f * breathe, y = center.y + 22f),
                radius = size.minDimension * 0.34f * breathe,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.HealthAndSafety, null, Modifier.size(32.dp), tint = MaterialTheme.vita.onAura)
            Text("100%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.vita.onAura)
            Text(vitaText(L10nKey.ON_DEVICE_LABEL), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.vita.onAura)
            Text(vitaText(L10nKey.PRIVACY_AURA_SUBTITLE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.vita.onAura, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
