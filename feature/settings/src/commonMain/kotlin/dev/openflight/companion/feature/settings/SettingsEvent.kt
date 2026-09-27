// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem

/** User intents from the settings screen, sent up to [SettingsViewModel.onEvent]. */
sealed interface SettingsEvent {
    /** Persists the unit preference (works on any transport). */
    data class SetUnits(
        val units: UnitSystem,
    ) : SettingsEvent

    /** A slider was released: `set_radar_config` with just this [field]. */
    data class SetRadarValue(
        val field: RadarField,
        val value: Int,
    ) : SettingsEvent

    /** `get_radar_config`. */
    data object RefreshRadarConfig : SettingsEvent

    /** `toggle_debug`. */
    data object ToggleDebug : SettingsEvent

    /** `upload_cloud`. */
    data object UploadCloud : SettingsEvent

    /** "Stop OpenFlight": asks for confirmation first ([ShutdownPhase.Confirming]). */
    data object RequestShutdown : SettingsEvent

    /** The confirmation's "Stop now": captures the Pi's address and posts `/api/shutdown` to it. */
    data object ConfirmShutdown : SettingsEvent

    /** The confirmation's "Cancel". */
    data object CancelShutdown : SettingsEvent

    /** "Try again" after [ShutdownPhase.Failed]: posts to the same captured address. */
    data object RetryShutdown : SettingsEvent

    /** Clears a [ShutdownPhase.Done] or [ShutdownPhase.Failed] outcome. */
    data object DismissShutdown : SettingsEvent

    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    /** The "Audio call-outs" enable switch. */
    data class SetCalloutsEnabled(
        val enabled: Boolean,
    ) : SettingsEvent

    /** Every final shot, or only shots inside a game. */
    data class SetCalloutTrigger(
        val trigger: CalloutTrigger,
    ) : SettingsEvent

    /** A voice picked from the grouped list; `null` restores the platform default. */
    data class SetCalloutVoice(
        val voiceId: String?,
    ) : SettingsEvent

    /** The rate slider, released. */
    data class SetCalloutRate(
        val rate: Float,
    ) : SettingsEvent

    /** A field checklist checkbox: selects it (appended to the end) or deselects it. */
    data class ToggleCalloutField(
        val field: CalloutField,
    ) : SettingsEvent

    /** Moves a selected field one slot up (`up = true`) or down in the speaking order. */
    data class MoveCalloutField(
        val field: CalloutField,
        val up: Boolean,
    ) : SettingsEvent

    /** The voice picker's "Preview" button: speaks [CalloutSettingsUiState.previewText]. */
    data object PreviewCallout : SettingsEvent

    /** Plan F8a2a: the range theme picker; persisted for the driving range. */
    data class SetRangeTheme(
        val theme: RangeThemeSetting,
    ) : SettingsEvent

    /** Plan F8a2t: the shot trail picker; persisted for the driving range. */
    data class SetShotTrail(
        val style: ShotTrailStyle,
    ) : SettingsEvent

    /** Plan F8a2t: "Keep last shots": none or three faded earlier trails. */
    data class SetShotTrailKeepLast(
        val count: Int,
    ) : SettingsEvent

    /** Plan F8a2t: what marks a landing. */
    data class SetLandingEffect(
        val effect: LandingEffect,
    ) : SettingsEvent

    /** Plan F14: the Demo mode switch; takes effect at once, no relaunch. */
    data class SetDemoMode(
        val enabled: Boolean,
    ) : SettingsEvent

    /** Plan F14: seconds between automatic demo shots, one of the offered options (0 is off). */
    data class SetDemoAutoFire(
        val seconds: Int,
    ) : SettingsEvent

    /** Plan F14: "Clear demo data" asks for confirmation first. */
    data object RequestClearDemoData : SettingsEvent

    data object ConfirmClearDemoData : SettingsEvent

    data object CancelClearDemoData : SettingsEvent
}

/** One-shot signals from [SettingsViewModel]. */
sealed interface SettingsEffect {
    /**
     * A message for a snackbar/toast: a Pi notice ("Radar not connected", a simulator failure,
     * the shutdown acknowledgement) or a failed command.
     */
    data class Message(
        val text: String,
    ) : SettingsEffect
}
