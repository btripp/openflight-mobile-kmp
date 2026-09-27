// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeCameraMode
import dev.openflight.companion.core.data.RangeShowSetting
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.SHOT_TRAIL_KEEP_OPTIONS
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.pickerLabel
import dev.openflight.companion.core.data.shotTrailKeepLabel
import dev.openflight.companion.core.data.unitSystemLabel
import dev.openflight.companion.core.designsystem.OfBottomSheet
import dev.openflight.companion.core.designsystem.OfChip
import dev.openflight.companion.core.designsystem.OfClubPalette
import dev.openflight.companion.core.designsystem.OfColorTokens
import dev.openflight.companion.core.designsystem.OfIconButton
import dev.openflight.companion.core.designsystem.OfIcons
import dev.openflight.companion.core.designsystem.OfOutlinedButton
import dev.openflight.companion.core.designsystem.OfSpacing
import dev.openflight.companion.core.designsystem.OfSwitchRow
import dev.openflight.companion.core.designsystem.OfText
import dev.openflight.companion.core.designsystem.OfTextRole
import dev.openflight.companion.core.designsystem.OfTheme
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A trail style: its swatch, drawn by the shared [ShotTrail] ([ShotTrailSwatch]), over its name. */
@Composable
internal fun TrailStyleChoice(
    style: ShotTrailStyle,
    theme: RangeTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(SwatchWidth)
                .clip(SwatchShape)
                .border(if (selected) 2.dp else 1.dp, if (selected) OfColorTokens.Gold else SwatchBorder, SwatchShape)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription = "${style.pickerLabel} trail"
                }.padding(OfSpacing.Xs)
                .testTag(RangeTestTags.quickTrail(style)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TrailSwatch(style, theme, Modifier.size(width = SwatchWidth - 8.dp, height = SwatchHeight))
        OfText(
            text = style.pickerLabel,
            role = OfTextRole.BodySmall,
            color = if (selected) OfColorTokens.Gold else OfColorTokens.Cream,
            maxLines = 1,
        )
    }
}

/** The swatch itself: built once per style and theme off the main thread, then filled like the range's trail. */
@Composable
private fun TrailSwatch(
    style: ShotTrailStyle,
    theme: RangeTheme,
    modifier: Modifier = Modifier,
) {
    val swatch by produceState<ShotTrailSwatch<ComposePathSink>?>(initialValue = null, style, theme) {
        value = withContext(Dispatchers.Default) { ShotTrailSwatch(style, theme) { ComposePathSink() } }
    }
    val padding = with(LocalDensity.current) { SWATCH_PADDING_DP.dp.toPx() }
    Canvas(modifier = modifier.clip(RoundedCornerShape(6.dp))) {
        val current = swatch
        drawRect(current?.background?.toColor() ?: OfColorTokens.BgDeep)
        if (current == null) return@Canvas
        val (scale, offsetX, offsetY) = current.fit(size.width, size.height, padding)
        translate(offsetX, offsetY) {
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                for (layer in current.trail.layers) {
                    if (!layer.visible) continue
                    val color =
                        if (layer.paletteIndex >= 0) {
                            OfClubPalette.color(layer.paletteIndex).copy(
                                alpha =
                                    (layer.argb ushr ALPHA_SHIFT) / CHANNEL_MAX,
                            )
                        } else {
                            Color(layer.argb)
                        }
                    drawPath(layer.path.path, color)
                }
                drawCircle(Color.White, radius = current.ballRadius, center = Offset(current.ballX, current.ballY))
            }
        }
    }
}

private val SwatchWidth = 96.dp
private val SwatchHeight = 44.dp
private val SwatchShape = RoundedCornerShape(10.dp)
private val SwatchBorder = Color.White.copy(alpha = 0.12f)
private const val SWATCH_PADDING_DP = 4
private const val ALPHA_SHIFT = 24
private const val CHANNEL_MAX = 255f
