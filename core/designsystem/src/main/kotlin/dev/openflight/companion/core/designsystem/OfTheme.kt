// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val OfDarkColorScheme =
    darkColorScheme(
        primary = OfColorTokens.Gold,
        onPrimary = OfColorTokens.BgDeep,
        primaryContainer = OfColorTokens.GoldDim,
        onPrimaryContainer = OfColorTokens.Cream,
        secondary = OfColorTokens.GoldBright,
        onSecondary = OfColorTokens.BgDeep,
        background = OfColorTokens.BgDeep,
        onBackground = OfColorTokens.Cream,
        surface = OfColorTokens.BgCard,
        onSurface = OfColorTokens.Cream,
        surfaceVariant = OfColorTokens.BgElevated,
        onSurfaceVariant = OfColorTokens.CreamDim,
        surfaceContainerHighest = OfColorTokens.BgHover,
        outline = OfColorTokens.CreamMuted,
        outlineVariant = OfColorTokens.BgElevated,
        error = OfColorTokens.Danger,
        onError = OfColorTokens.BgDeep,
    )

// The app is dark-first (invariant: the reference is `.preferredColorScheme(.dark)`),
// but Material3 always needs a light scheme too, for example when the OS forces one.
private val OfLightColorScheme =
    lightColorScheme(
        primary = OfColorTokens.GoldDim,
        onPrimary = OfColorTokens.Cream,
        primaryContainer = OfColorTokens.GoldBright,
        onPrimaryContainer = OfColorTokens.BgDeep,
        secondary = OfColorTokens.Gold,
        onSecondary = OfColorTokens.BgDeep,
        background = OfColorTokens.Cream,
        onBackground = OfColorTokens.BgDeep,
        surface = OfColorTokens.Cream,
        onSurface = OfColorTokens.BgDeep,
        surfaceVariant = OfColorTokens.CreamDim,
        onSurfaceVariant = OfColorTokens.BgElevated,
        outline = OfColorTokens.GoldDim,
        error = OfColorTokens.Danger,
        onError = OfColorTokens.Cream,
    )

/**
 * The web UI's type pairing (`index.css`: `--font-display` / `--font-body`), bundled as OFL font
 * resources (licenses in `assets/licenses/`): **DM Serif Display** for headings and large values,
 * **Outfit** (a variable font, weight axis 100..900) for everything else.
 */
private val DmSerifDisplay = FontFamily(Font(R.font.dm_serif_display_regular, FontWeight.Normal))

@OptIn(ExperimentalTextApi::class)
private fun outfit(weight: FontWeight): Font =
    Font(
        R.font.outfit_variable,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
    )

@OptIn(ExperimentalTextApi::class)
private val Outfit =
    FontFamily(
        outfit(FontWeight.Light),
        outfit(FontWeight.Normal),
        outfit(FontWeight.Medium),
        outfit(FontWeight.SemiBold),
        outfit(FontWeight.Bold),
    )

private fun TextStyle.display(): TextStyle = copy(fontFamily = DmSerifDisplay, fontWeight = FontWeight.Normal)

private fun TextStyle.body(weight: FontWeight? = fontWeight): TextStyle = copy(fontFamily = Outfit, fontWeight = weight)

private val OfTypography =
    Typography().let { base ->
        base.copy(
            displayLarge = base.displayLarge.display(),
            displayMedium = base.displayMedium.display(),
            displaySmall = base.displaySmall.display(),
            headlineLarge = base.headlineLarge.display(),
            headlineMedium = base.headlineMedium.display(),
            headlineSmall = base.headlineSmall.display(),
            titleLarge = base.titleLarge.display(),
            titleMedium = base.titleMedium.body(FontWeight.SemiBold),
            titleSmall = base.titleSmall.body(FontWeight.Medium),
            bodyLarge = base.bodyLarge.body(FontWeight.Normal),
            bodyMedium = base.bodyMedium.body(FontWeight.Normal),
            bodySmall = base.bodySmall.body(FontWeight.Normal),
            labelLarge = base.labelLarge.body(FontWeight.Medium),
            labelSmall =
                base.labelSmall.body(FontWeight.Bold).copy(
                    letterSpacing = 0.14.em,
                ),
            labelMedium =
                base.labelMedium.body(FontWeight.Bold).copy(
                    letterSpacing = 0.1.em,
                ),
        )
    }

private val OfShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/**
 * The eyebrow/section-label text style used above titles and metric groups, for
 * example "OPENFLIGHT", "LATEST SHOT" and "PREVIOUS SHOTS" in the reference
 * `ContentView`. Exposed for the rare case a feature needs it directly rather than
 * through [OfTopBar] or a metric wrapper.
 */
val OfEyebrowTextStyle: TextStyle
    @Composable
    get() =
        MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 0.14.em,
            fontWeight = FontWeight.Bold,
        )

internal val OfMetricValueFontSize: TextUnit = 36.sp

/**
 * The design-system theme every screen must be wrapped in. Composes Material3's
 * [MaterialTheme] with the OpenFlight color scheme, typography and shapes so that
 * `Of*` components (and any raw Material3 use inside `core:designsystem` itself)
 * pick them up automatically.
 *
 * @param darkTheme Whether to use the dark (default, and reference-matching) scheme.
 */
@Composable
fun OfTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) OfDarkColorScheme else OfLightColorScheme,
        typography = OfTypography,
        shapes = OfShapes,
        content = content,
    )
}
