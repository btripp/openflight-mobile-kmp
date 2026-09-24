// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.insights.UnitSystem

/** User intents from the settings screen, sent up to [SettingsViewModel.onEvent]. */
sealed interface SettingsEvent {
    /** Persists the unit preference (works on any transport). */
    data class SetUnits(
        val units: UnitSystem,
    ) : SettingsEvent

    /** `set_player`, trimmed and capped at [PlayerSettings.MAX_NAME_LENGTH]; blank becomes "Player 1" on the Pi. */
    data class SetPlayer(
        val name: String,
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

    /** The power button: asks for confirmation first ([ShutdownSettings.confirmationRequired]). */
    data object RequestShutdown : SettingsEvent

    /** The dialog's "Shut Down": sends `shutdown`. */
    data object ConfirmShutdown : SettingsEvent

    /** The dialog's "Cancel". */
    data object CancelShutdown : SettingsEvent
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
