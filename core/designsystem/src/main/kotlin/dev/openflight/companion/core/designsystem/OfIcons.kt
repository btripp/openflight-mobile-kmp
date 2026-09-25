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

    /** A warning triangle with an exclamation mark: a problem the user should read (plan R8f). */
    val Warning: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "OfWarning",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    moveTo(12f, 2.5f)
                    lineTo(22.5f, 21f)
                    lineTo(1.5f, 21f)
                    close()
                    // The exclamation mark's bar and dot are holes in the triangle.
                    moveTo(11f, 9f)
                    lineTo(13f, 9f)
                    lineTo(12.6f, 15f)
                    lineTo(11.4f, 15f)
                    close()
                    moveTo(11f, 16.5f)
                    lineTo(13f, 16.5f)
                    lineTo(13f, 18.5f)
                    lineTo(11f, 18.5f)
                    close()
                }
            }.build()
    }

    /** A check mark in a circle: something the Pi confirmed (plan R8f). */
    val Check: ImageVector by lazy {
        ImageVector
            .Builder(
                name = "OfCheck",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                    moveTo(12f, 2f)
                    arcToRelative(10f, 10f, 0f, true, true, 0f, 20f)
                    arcToRelative(10f, 10f, 0f, true, true, 0f, -20f)
                    close()
                    // The tick is a hole in the disc.
                    moveTo(6.5f, 12.5f)
                    lineTo(8f, 11f)
                    lineTo(10.5f, 13.5f)
                    lineTo(16f, 8f)
                    lineTo(17.5f, 9.5f)
                    lineTo(10.5f, 16.5f)
                    close()
                }
            }.build()
    }

    /** A house: the dashboard (home) entry of the app navigation (F1a). */
    val Home: ImageVector by lazy {
        icon("OfHome") {
            moveTo(12f, 3f)
            lineTo(21f, 11f)
            lineTo(18.5f, 11f)
            lineTo(18.5f, 20f)
            lineTo(14f, 20f)
            lineTo(14f, 14f)
            lineTo(10f, 14f)
            lineTo(10f, 20f)
            lineTo(5.5f, 20f)
            lineTo(5.5f, 11f)
            lineTo(3f, 11f)
            close()
        }
    }

    /** Three bars of a list: the session (shot list and stats) entry (F1a). */
    val Session: ImageVector by lazy {
        icon("OfSession") {
            for (top in listOf(5f, 10.75f, 16.5f)) {
                moveTo(4f, top)
                lineTo(20f, top)
                lineTo(20f, top + 2.5f)
                lineTo(4f, top + 2.5f)
                close()
            }
        }
    }

    /** A lightning bolt: the swing-speed training entry (F1a). */
    val Training: ImageVector by lazy {
        icon("OfTraining") {
            moveTo(13.5f, 2f)
            lineTo(5f, 13.5f)
            lineTo(11f, 13.5f)
            lineTo(10f, 22f)
            lineTo(19f, 10f)
            lineTo(13f, 10f)
            close()
        }
    }

    /** Three sliders: the settings entry (F1a). */
    val Settings: ImageVector by lazy {
        icon("OfSettings") {
            // (track y, knob x) per slider.
            for ((y, knob) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                moveTo(3f, y - 1f)
                lineTo(21f, y - 1f)
                lineTo(21f, y + 1f)
                lineTo(3f, y + 1f)
                close()
                moveTo(knob - 2f, y - 2.5f)
                lineTo(knob + 2f, y - 2.5f)
                lineTo(knob + 2f, y + 2.5f)
                lineTo(knob - 2f, y + 2.5f)
                close()
            }
        }
    }

    private fun icon(
        name: String,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply { path(fill = SolidColor(Color.Black), pathBuilder = pathBuilder) }
            .build()
}
