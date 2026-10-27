// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import kotlin.test.Test

class ShotCsvTest {
    @Test
    fun headerRowHasNoComparatorColumns() {
        val csv = csvOf(emptyList())

        assertThat(csv).isEqualTo(
            "shot_number,event_id,timestamp,club,ball_speed_mph,club_speed_mph,smash_factor," +
                "estimated_carry_yards,launch_angle_vertical,launch_angle_horizontal,spin_rpm," +
                "club_path_deg,spin_axis_deg",
        )
    }

    @Test
    fun aMultiSessionExportLeadsWithTheSessionAndNumbersEachSessionFromOne() {
        val first = ExportShot.of(shot(eventId = "00000000-0000-4000-8000-000000000001"), null)
        val second = ExportShot.of(shot(eventId = "00000000-0000-4000-8000-000000000002"), null)
        val third = ExportShot.of(shot(eventId = "00000000-0000-4000-8000-000000000003"), null)
        val csv =
            buildExportCsv(
                listOf(
                    first.copy(session = "2026-09-25 10:00"),
                    second.copy(session = "2026-09-25 10:00"),
                    third.copy(session = "2026-09-27 18:30"),
                ),
            )

        val rows = csv.lines().map { it.split(",") }
        assertThat(rows[0].take(2)).isEqualTo(listOf("session", "shot_number"))
        assertThat(rows.drop(1).map { it.take(2) }).isEqualTo(
            listOf(listOf("2026-09-25 10:00", "1"), listOf("2026-09-25 10:00", "2"), listOf("2026-09-27 18:30", "1")),
        )
    }

    @Test
    fun aSingleSessionExportHasNoSessionColumn() {
        val csv = buildExportCsv(listOf(ExportShot.of(shot(eventId = "00000000-0000-4000-8000-000000000001"), null)))

        assertThat(csv.lines().first().startsWith("shot_number,")).isEqualTo(true)
    }

    @Test
    fun estimatedRollAndTotalAreAppendedAsEstColumnsAndAddUp() {
        val shots = listOf(ExportShot.of(shot(launchAngleVertical = 12.0, spinRpm = 2_600.0), null))
        val rows = buildExportCsv(shots.withEstimatedTotals(Conditions.ISA, null)).lines().map { it.split(",") }

        assertThat(rows[0].takeLast(2)).isEqualTo(listOf("roll_est_yards", "total_est_yards"))
        val (roll, total) = rows[1].takeLast(2).map(String::toDouble)
        assertThat(roll).isGreaterThan(0.0)
        // At ISA the carry is the server's 240 yd, so total = carry + roll (to the 0.1 yd rounding).
        assertThat(total).isCloseTo(240.0 + roll, 0.11)
    }

    @Test
    fun withoutAnEstimateThereAreNoEstColumns() {
        val csv = buildExportCsv(listOf(ExportShot.of(shot(), null)))

        assertThat(csv.lines().first().endsWith("spin_axis_deg")).isTrue()
    }

    @Test
    fun theSessionsFilenameIsFilesystemSafe() {
        assertThat(buildSessionsCsvFilename("2026-10-11T09:30:00.123Z"))
            .isEqualTo("openflight-sessions-2026-10-11T09-30-00-123Z.csv")
    }

    @Test
    fun oneRowPerShotNumberedFromOne() {
        val csv =
            csvOf(
                listOf(
                    shot(eventId = "00000000-0000-4000-8000-000000000001", club = "driver", ballSpeedMph = 151.4),
                    shot(eventId = "00000000-0000-4000-8000-000000000002", club = "7-iron", ballSpeedMph = 120.2),
                ),
            )

        val rows = csv.lines()
        assertThat(rows[1]).isEqualTo(
            "1,00000000-0000-4000-8000-000000000001,2026-08-05T23:54:00,driver,151.4,,,240.0,,,,,",
        )
        assertThat(rows[2]).isEqualTo(
            "2,00000000-0000-4000-8000-000000000002,2026-08-05T23:54:00,7-iron,120.2,,,240.0,,,,,",
        )
    }

    @Test
    fun nullFieldsRenderAsEmptyColumns() {
        val csv =
            csvOf(
                listOf(
                    shot(
                        clubSpeedMph = 103.2,
                        smashFactor = 1.47,
                        launchAngleVertical = 12.6,
                        launchAngleHorizontal = -1.3,
                        spinRpm = 2380.0,
                        clubPathDeg = 2.1,
                        spinAxisDeg = -3.4,
                    ),
                ),
            )

        assertThat(csv.lines()[1]).isEqualTo(
            "1,00000000-0000-4000-8000-000000000000,2026-08-05T23:54:00,driver,140.0,103.2,1.47,240.0," +
                "12.6,-1.3,2380.0,2.1,-3.4",
        )
    }

    @Test
    fun aClubValueContainingACommaIsQuotedAndEscaped() {
        val csv = csvOf(listOf(shot(club = "driver, \"pro\"")))

        assertThat(csv.lines()[1]).isEqualTo(
            "1,00000000-0000-4000-8000-000000000000,2026-08-05T23:54:00,\"driver, \"\"pro\"\"\",140.0,,,240.0,,,,,",
        )
    }

    @Test
    fun filenameSanitizesColonsAndDotsAndKeepsTheCsvExtension() {
        val filename = buildShotsCsvFilename("2026-09-24T18:30:05.123Z")

        assertThat(filename).isEqualTo("openflight-shots-2026-09-24T18-30-05-123Z.csv")
    }
}

/** A live-shots-only export, as the screens build it ([ExportShot] without a Pi detail). */
private fun csvOf(shots: List<ShotEvent>): String = buildExportCsv(shots.map { ExportShot.of(it, null) })

private fun shot(
    eventId: String = "00000000-0000-4000-8000-000000000000",
    club: String = "driver",
    ballSpeedMph: Double = 140.0,
    clubSpeedMph: Double? = null,
    smashFactor: Double? = null,
    launchAngleVertical: Double? = null,
    launchAngleHorizontal: Double? = null,
    spinRpm: Double? = null,
    clubPathDeg: Double? = null,
    spinAxisDeg: Double? = null,
): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = eventId,
        timestamp = "2026-08-05T23:54:00",
        club = club,
        ballSpeedMph = ballSpeedMph,
        clubSpeedMph = clubSpeedMph,
        smashFactor = smashFactor,
        estimatedCarryYards = 240.0,
        launchAngleVertical = launchAngleVertical,
        launchAngleHorizontal = launchAngleHorizontal,
        spinRpm = spinRpm,
        clubPathDeg = clubPathDeg,
        spinAxisDeg = spinAxisDeg,
    )
