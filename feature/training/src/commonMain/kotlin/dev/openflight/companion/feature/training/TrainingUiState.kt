// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import dev.openflight.companion.core.insights.SwingSpeedStats
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.pi.PiFeatureAvailability

/**
 * The swing-speed training screen (plan R6b), ported from the web UI's swing-speed
 * `ShotDisplay.tsx` view and `TrainingImplementPicker.tsx`. Wi-Fi only.
 *
 * @property availability whether the Pi's live session is reachable. [TrainingEvent.SelectImplement]
 *   and [TrainingEvent.SimulateSwing] are disabled with its reason otherwise.
 * @property implementGroups the picker: "General" (Driver, Custom) and then one group per training
 *   system, built from the server's valid implements (`TRAINING_IMPLEMENT_LABELS`).
 * @property selectedImplement the selected implement: the one being set, else the Pi's, else Driver.
 * @property triggerMode the Pi's trigger mode (`swing-speed`, `rolling-buffer` or `mock`), or
 *   `null` before the first `trigger_status`.
 * @property isSwingSpeedMode training reps only arrive in `swing-speed` mode.
 * @property profileName the Pi's active profile ([NO_PROFILE] before the roster arrives).
 * @property stats Last, Best and Average over the session's reps for the active profile and
 *   [selectedImplement] (the web UI's filter, by profile instead of player); all zero with no reps.
 * @property lastRep the newest rep of any profile or implement, for the "Last Swing" gauge.
 * @property error the last failure: a Pi error ("Unknown training implement") or a failed command.
 * @property showSimulateSwing visible only when the Pi runs `--mock`.
 */
data class TrainingUiState(
    val availability: PiFeatureAvailability,
    val units: UnitSystem,
    val implementGroups: List<ImplementGroup>,
    val selectedImplement: ImplementOption,
    val triggerMode: String?,
    val isSwingSpeedMode: Boolean,
    val profileName: String,
    val stats: SwingSpeedStats,
    val lastRep: SwingRep?,
    val error: String?,
    val showSimulateSwing: Boolean,
) {
    val hasSwings: Boolean get() = stats.count > 0

    companion object {
        /** Shown until the Pi reports its roster. */
        const val NO_PROFILE: String = "—"
    }
}

/** One picker section, e.g. "TheStack" with its weights. */
data class ImplementGroup(
    val name: String,
    val options: List<ImplementOption>,
)

/** A training implement: the server's key ([id]) and its display [label]. */
data class ImplementOption(
    val id: String,
    val label: String,
)

/** A swing-speed rep's details (`ShotDisplay.tsx`'s swing-speed cards). */
data class SwingRep(
    val speedMph: Double,
    val implementLabel: String?,
    val readingCount: Int?,
    val triggerSpeedMph: Double?,
    val durationMs: Double?,
    val profileName: String?,
)
