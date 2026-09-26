// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.bag

import dev.openflight.companion.core.data.ConditionsMode
import dev.openflight.companion.core.insights.GapFlag
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.Firmness
import dev.openflight.companion.core.model.GolfClub

/**
 * My Bag (plan F5): the active bag's clubs in bag order with their average carry and the gap to
 * the next club, the bag switcher, and the conditions card.
 *
 * @property loaded `false` until the active bag has been read (and seeded when there was none).
 * @property addableClubs the clubs not in the bag yet, in the usual bag order.
 * @property carryAdjusted the carries are adjusted for [conditions] (not the server's ISA air).
 */
data class BagUiState(
    val loaded: Boolean = false,
    val bagId: String? = null,
    val bagName: String = "",
    val bags: List<BagOption> = emptyList(),
    val clubs: List<BagClubRow> = emptyList(),
    val addableClubs: List<GolfClub> = emptyList(),
    val conditions: ConditionsCardState = ConditionsCardState(),
    val units: UnitSystem = UnitSystem.IMPERIAL,
    val carryAdjusted: Boolean = false,
    val error: String? = null,
)

/** A bag in the switcher. */
data class BagOption(
    val id: String,
    val name: String,
    val isActive: Boolean,
)

/**
 * One row of My Bag.
 *
 * @property carryLabel the mean carry ("155 yds"), or "—" without shots.
 * @property plusMinusLabel "± 5 yds", or `null` below two shots.
 * @property gap the chip between this row and the next club with enough shots.
 */
data class BagClubRow(
    val id: String,
    val club: GolfClub,
    val wireValue: String,
    val name: String,
    val shortLabel: String,
    val makeModel: String?,
    val make: String?,
    val model: String?,
    val loftDeg: Double?,
    val avgCarryYards: Double?,
    val carryLabel: String,
    val plusMinusLabel: String?,
    val shotCountLabel: String,
    val colorIndex: Int,
    val gap: GapChipState?,
) {
    /** One sentence for a screen reader. */
    val accessibilityLabel: String
        get() =
            listOfNotNull(
                name,
                makeModel,
                if (avgCarryYards == null) "no shots yet" else "$carryLabel average carry",
                plusMinusLabel,
                shotCountLabel,
                gap?.label,
            ).joinToString(", ")
}

/** The chip between two rows: "12 yds gap", flagged when the gapping analysis flags it. */
data class GapChipState(
    val label: String,
    val flag: GapFlag?,
)

/**
 * The conditions card (plan F5; manual until F6's automatic source).
 *
 * @property windNote [BagCopy.WIND_NEEDS_TARGET] when there's wind but no target direction.
 * @property form the editor, pre-filled with the current values.
 */
data class ConditionsCardState(
    val mode: ConditionsMode = ConditionsMode.MANUAL,
    val modeLabel: String = BagCopy.mode(ConditionsMode.MANUAL),
    val summary: String = "",
    val isStandard: Boolean = true,
    val windNote: String? = null,
    val form: ConditionsForm =
        ConditionsForm(
            altitude = "0",
            temperature = "59",
            windSpeed = "0",
            windFrom = "0",
            surface = Firmness.NORMAL,
            targetBearing = "",
        ),
    val altitudeUnit: String = "ft",
    val temperatureUnit: String = "°F",
    val windUnit: String = "mph",
    val formError: String? = null,
)

/** What My Bag's user can do. */
sealed interface BagEvent {
    /** Moves a club one place up or down in the bag. */
    data class MoveClub(
        val clubId: String,
        val up: Boolean,
    ) : BagEvent

    /** Puts the clubs in exactly this order (iOS drag to reorder). */
    data class Reorder(
        val clubIds: List<String>,
    ) : BagEvent

    data class RemoveClub(
        val clubId: String,
    ) : BagEvent

    data class AddClub(
        val club: GolfClub,
    ) : BagEvent

    /** Sets a club's make, model and loft (typed; a blank field clears it). */
    data class EditClub(
        val clubId: String,
        val make: String,
        val model: String,
        val loft: String,
    ) : BagEvent

    data class SelectBag(
        val bagId: String,
    ) : BagEvent

    /** Saves the conditions editor (switches the conditions to manual). */
    data class SaveConditions(
        val form: ConditionsForm,
    ) : BagEvent

    data object DismissError : BagEvent
}
