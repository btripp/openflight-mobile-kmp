// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isEqualTo
import dev.openflight.companion.core.model.GolfClub
import kotlin.test.Test

/**
 * Table-driven coverage of [CalloutComposer] (plan F4): null fields are skipped, estimated
 * fields are prefixed "about", units are spoken in full, numbers are rounded for speech (carry to
 * the nearest yard, smash to 2 dp spelled out as words), and `TARGET_DELTA` says "long"/"short".
 */
class CalloutComposerTest {
    private data class Case(
        val description: String,
        val input: CalloutInput,
        val fields: List<CalloutField>,
        val units: UnitSystem = UnitSystem.IMPERIAL,
        val verbosity: CalloutVerbosity = CalloutVerbosity.CONCISE,
        val expected: String,
    )

    private val cases =
        listOf(
            Case(
                description = "a null field is skipped, never spoken as \"null\" or zero",
                input = CalloutInput(carryYards = 152.0, ballSpeedMph = null),
                fields = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED),
                expected = "152 yards",
            ),
            Case(
                description = "every requested field null gives an empty call-out",
                input = CalloutInput(),
                fields = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED, CalloutField.CLUB),
                expected = "",
            ),
            Case(
                description = "an estimated field is prefixed \"about\"",
                input = CalloutInput(carryYards = 152.0, estimated = setOf(CalloutField.CARRY)),
                fields = listOf(CalloutField.CARRY),
                expected = "about 152 yards",
            ),
            Case(
                description = "a non-estimated field alongside an estimated one keeps its own prefix",
                input =
                    CalloutInput(
                        carryYards = 152.0,
                        ballSpeedMph = 118.0,
                        estimated = setOf(CalloutField.CARRY),
                    ),
                fields = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED),
                expected = "about 152 yards, 118 miles per hour",
            ),
            Case(
                description = "speed units are spoken in full, not \"mph\"",
                input = CalloutInput(ballSpeedMph = 118.3),
                fields = listOf(CalloutField.BALL_SPEED),
                expected = "118 miles per hour",
            ),
            Case(
                description = "metric distance units are spoken in full, not \"m\"",
                input = CalloutInput(carryYards = 150.0),
                fields = listOf(CalloutField.CARRY),
                units = UnitSystem.METRIC,
                expected = "137 meters",
            ),
            Case(
                description = "metric speed units are spoken in full",
                input = CalloutInput(ballSpeedMph = 100.0),
                fields = listOf(CalloutField.BALL_SPEED),
                units = UnitSystem.METRIC,
                expected = "161 kilometers per hour",
            ),
            Case(
                description = "carry rounds to the nearest yard",
                input = CalloutInput(carryYards = 151.6),
                fields = listOf(CalloutField.CARRY),
                expected = "152 yards",
            ),
            Case(
                description = "carry rounds down below the half-yard",
                input = CalloutInput(carryYards = 151.4),
                fields = listOf(CalloutField.CARRY),
                expected = "151 yards",
            ),
            Case(
                description = "total is spoken the same way as carry",
                input = CalloutInput(totalYards = 165.0),
                fields = listOf(CalloutField.TOTAL),
                expected = "165 yards",
            ),
            Case(
                description = "smash is rounded to 2 dp and spelled out as words",
                input = CalloutInput(smash = 1.4821),
                fields = listOf(CalloutField.SMASH),
                expected = "one point four eight",
            ),
            Case(
                description = "a whole-number smash still gets \"point zero zero\"",
                input = CalloutInput(smash = 1.0),
                fields = listOf(CalloutField.SMASH),
                expected = "one point zero zero",
            ),
            Case(
                description = "launch angle rounds to the nearest degree",
                input = CalloutInput(launchAngleDegrees = 12.6),
                fields = listOf(CalloutField.LAUNCH),
                expected = "13 degrees",
            ),
            Case(
                description = "spin rate is spoken in full, not \"rpm\"",
                input = CalloutInput(spinRpm = 6234.0),
                fields = listOf(CalloutField.SPIN),
                expected = "6234 revolutions per minute",
            ),
            Case(
                description = "club speed is spoken in full",
                input = CalloutInput(clubSpeedMph = 95.2),
                fields = listOf(CalloutField.CLUB_SPEED),
                expected = "95 miles per hour",
            ),
            Case(
                description = "club speaks its display name",
                input = CalloutInput(club = GolfClub.IRON_7),
                fields = listOf(CalloutField.CLUB),
                expected = "7-Iron",
            ),
            Case(
                description = "offline right of the target line",
                input = CalloutInput(offlineYards = 12.4),
                fields = listOf(CalloutField.OFFLINE),
                expected = "12 yards right",
            ),
            Case(
                description = "offline left of the target line",
                input = CalloutInput(offlineYards = -8.0),
                fields = listOf(CalloutField.OFFLINE),
                expected = "8 yards left",
            ),
            Case(
                description = "offline that rounds to zero reads as on the line",
                input = CalloutInput(offlineYards = 0.2),
                fields = listOf(CalloutField.OFFLINE),
                expected = "on line",
            ),
            Case(
                description = "TARGET_DELTA past the target says \"long\"",
                input = CalloutInput(targetDeltaYards = 3.4),
                fields = listOf(CalloutField.TARGET_DELTA),
                expected = "3 yards long",
            ),
            Case(
                description = "TARGET_DELTA short of the target says \"short\"",
                input = CalloutInput(targetDeltaYards = -2.3),
                fields = listOf(CalloutField.TARGET_DELTA),
                expected = "2 yards short",
            ),
            Case(
                description = "TARGET_DELTA that rounds to zero reads as on target",
                input = CalloutInput(targetDeltaYards = 0.1),
                fields = listOf(CalloutField.TARGET_DELTA),
                expected = "on target",
            ),
            Case(
                description = "DETAILED verbosity prefixes each field with its label",
                input = CalloutInput(carryYards = 152.0, ballSpeedMph = 118.0),
                fields = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED),
                verbosity = CalloutVerbosity.DETAILED,
                expected = "Carry 152 yards, Ball speed 118 miles per hour",
            ),
            Case(
                description = "DETAILED verbosity keeps the \"about\" prefix after the label",
                input = CalloutInput(carryYards = 152.0, estimated = setOf(CalloutField.CARRY)),
                fields = listOf(CalloutField.CARRY),
                verbosity = CalloutVerbosity.DETAILED,
                expected = "Carry about 152 yards",
            ),
            Case(
                description = "fields are spoken in the order requested, not enum order",
                input = CalloutInput(carryYards = 152.0, ballSpeedMph = 118.0),
                fields = listOf(CalloutField.BALL_SPEED, CalloutField.CARRY),
                expected = "118 miles per hour, 152 yards",
            ),
        )

    @Test
    fun tableDrivenCallouts() {
        cases.forEach { case ->
            val actual = CalloutComposer.compose(case.input, case.fields, case.units, case.verbosity)
            assertThat(actual, name = case.description).isEqualTo(case.expected)
        }
    }
}
