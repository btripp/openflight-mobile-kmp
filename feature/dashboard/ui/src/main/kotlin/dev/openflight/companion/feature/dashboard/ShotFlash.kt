// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.designsystem.OfColorTokens
import kotlin.math.max

private const val FLASH_MILLIS = 600
private const val HALF = 0.5f
private const val PEAK_RADIUS = 0.7f
private const val MID_ALPHA = 0.6f

/**
 * The web UI's gold shot-flash (`App.css` `.shot-flash` / `@keyframes shot-impact`): a radial gold
 * burst that grows to 70% over the first half of 0.6 s while fading to 0.6, then fades out. It
 * replays whenever [trigger] changes, and draws nothing (and leaves the tree) once finished, or
 * while [trigger] is 0 so a first composition never flashes.
 */
@Composable
internal fun ShotFlash(
    trigger: Int,
    modifier: Modifier = Modifier,
) {
    if (trigger == 0) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.animateTo(1f, tween(durationMillis = FLASH_MILLIS, easing = FastOutSlowInEasing))
    }
    if (progress.value >= 1f) return
    Canvas(modifier = modifier.testTag(DashboardUiTags.SHOT_FLASH)) {
        val p = progress.value
        val alpha = if (p < HALF) 1f - (1f - MID_ALPHA) * (p / HALF) else MID_ALPHA * (1f - (p - HALF) / HALF)
        val radius = max(size.width, size.height) * PEAK_RADIUS * (p / HALF).coerceIn(0.05f, 1f)
        drawRoundRect(
            brush =
                Brush.radialGradient(
                    colors = listOf(OfColorTokens.Gold.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
            cornerRadius = CornerRadius(20.dp.toPx()),
        )
    }
}
