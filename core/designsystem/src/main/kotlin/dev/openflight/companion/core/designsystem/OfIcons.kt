// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's own vector icons, for [OfIcon]. The project doesn't pull in an icon library, so the
 * few icons it needs are drawn here on a 24 x 24 grid.
 */
object OfIcons {
    /** A camera body with a lens: the driving range's camera-mode toggle (plan R7a). */
    val Camera: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "OfCamera",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    // Body with the viewfinder hump.
                    moveTo(3f, 7.5f)
                    lineTo(7.5f, 7.5f)
                    lineTo(9.2f, 5f)
                    lineTo(14.8f, 5f)
                    lineTo(16.5f, 7.5f)
                    lineTo(21f, 7.5f)
                    lineTo(21f, 19f)
                    lineTo(3f, 19f)
                    close()
                    // Lens ring (a hole in the body)...
                    moveTo(12f, 8.75f)
                    arcToRelative(4.25f, 4.25f, 0f, true, true, 0f, 8.5f)
                    arcToRelative(4.25f, 4.25f, 0f, true, true, 0f, -8.5f)
                    close()
                    // ...around a solid lens.
                    moveTo(12f, 10.75f)
                    arcToRelative(2.25f, 2.25f, 0f, true, true, 0f, 4.5f)
                    arcToRelative(2.25f, 2.25f, 0f, true, true, 0f, -4.5f)
                    close()
                }
            }.build()
    }
}
