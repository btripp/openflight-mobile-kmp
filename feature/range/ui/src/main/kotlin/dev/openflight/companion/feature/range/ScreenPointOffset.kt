// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.range

import androidx.compose.ui.geometry.Offset

/**
 * The shared projection math's [ScreenPoint] as a Compose [Offset]. NaN coordinates carry over, so
 * [ScreenPoint.Unspecified] becomes [Offset.Unspecified] (both are the NaN/NaN pair).
 */
internal fun ScreenPoint.toOffset(): Offset = Offset(x, y)
