// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

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
