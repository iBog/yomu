package com.yomu.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import kotlinx.coroutines.delay

@Composable
fun PaperLoading(message: String = stringResource(R.string.paper_loading)) {
    val colors = LocalYomuColors.current
    val shimmer = remember { Animatable(0f) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) shimmer.animateTo(1f, tween(900))
    }
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            listOf(1f, 0.7f, 0.85f).forEach { width ->
                Box(Modifier.fillMaxWidth(width).height(10.dp).clip(MaterialTheme.shapes.small).background(
                    Brush.linearGradient(
                        listOf(Color(colors.edge), Color(colors.paperRaised), Color(colors.edge)),
                        start = Offset(shimmer.value * 800 - 400, 0f), end = Offset(shimmer.value * 800, 0f)
                    )
                ))
            }
        }
    }
}

@Composable
fun PaperError(message: String, onRetry: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.paper_error), tint = MaterialTheme.colorScheme.error)
                Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            PaperButton(stringResource(R.string.paper_try_again), onRetry)
        }
    }
}

@Composable
fun PaperEmpty(message: String, action: String, onAction: () -> Unit) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.DateRange, contentDescription = null)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            PaperButton(action, onAction)
        }
    }
}

@Composable
fun PaperSuccess(message: String, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message) {
        delay(accessibility?.calculateRecommendedTimeoutMillis(2400, containsIcons = true, containsText = true) ?: 2400)
        dismiss()
    }
    PaperSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.paper_success), tint = MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
