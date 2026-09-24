// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.test.Test

class SessionInsightsTest {
    // region enrichment (ShotDisplay.tsx)

    @Test
    fun launchAngleConfidenceMapsToThreeDotBadges() {
        assertThat(ConfidenceLevel.fromLaunchAngleConfidence(0.72)).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(ConfidenceLevel.fromLaunchAngleConfidence(0.7)).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(ConfidenceLevel.fromLaunchAngleConfidence(0.4)).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(ConfidenceLevel.fromLaunchAngleConfidence(0.39)).isEqualTo(ConfidenceLevel.LOW)
        assertThat(ConfidenceLevel.fromLaunchAngleConfidence(null)).isNull()
        assertThat(ConfidenceLevel.HIGH.filledDots).isEqualTo(3)
        assertThat(ConfidenceLevel.MEDIUM.filledDots).isEqualTo(2)
        assertThat(ConfidenceLevel.LOW.filledDots).isEqualTo(1)
        assertThat(ConfidenceLevel.EXPERIMENTAL.showsDots).isFalse()
    }

    @Test
    fun enrichmentCarriesTheBadgesRangeAndPlayer() {
        val enrichment = ShotEnrichment.from(mockShotDetail())

        assertThat(enrichment.launchAngleConfidence).isEqualTo(ConfidenceLevel.HIGH)
        assertThat(enrichment.angleSource).isEqualTo("mock")
        assertThat(enrichment.spinQuality).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(enrichment.spinSource).isEqualTo(SpinSource.ESTIMATED)
        assertThat(enrichment.carryRangeText(UnitSystem.IMPERIAL)).isEqualTo("231-255 yds")
        assertThat(enrichment.carryRangeText(UnitSystem.METRIC)).isEqualTo("211-233 m")
        assertThat(enrichment.carrySpinAdjustedYards).isNull()
        assertThat(enrichment.playerName).isEqualTo("Player 1")
    }

    @Test
    fun badgesAreAbsentWithoutTheMeasurementTheyQualify() {
        val enrichment =
            ShotEnrichment.from(mockShotDetail().copy(launchAngleVertical = null, spinRpm = null, carryRange = null))

        assertThat(enrichment.launchAngleConfidence).isNull()
        assertThat(enrichment.spinQuality).isNull()
        assertThat(enrichment.spinSource).isNull()
        assertThat(enrichment.hasCarryRange).isFalse()
        assertThat(enrichment.carryRangeText(UnitSystem.IMPERIAL)).isNull()
    }

    @Test
    fun measuredSpinShowsAsRadarAndSpinAdjustedCarryIsKept() {
        val enrichment =
            ShotEnrichment.from(mockShotDetail().copy(spinSource = "measured", carrySpinAdjusted = 248.0))

        assertThat(enrichment.spinSource).isEqualTo(SpinSource.MEASURED)
        assertThat(enrichment.spinSource?.label).isEqualTo("radar")
        assertThat(enrichment.carrySpinAdjustedYards).isEqualTo(248.0)
    }

    // endregion

    // region swing speed (computeSwingSpeedStats)

    @Test
    fun swingSpeedStatsUseClubSpeedAndTheLastRepIsTheNewest() {
        val shots = listOf(swing(90.0), swing(100.0), swing(95.0), mockShotDetail())

        val stats = computeSwingSpeedStats(shots, playerName = null, trainingImplement = null)

        assertThat(
            stats,
        ).isEqualTo(SwingSpeedStats(count = 3, lastSpeedMph = 95.0, bestSpeedMph = 100.0, avgSpeedMph = 95.0))
    }

    @Test
    fun swingSpeedStatsFilterByPlayerAndImplement() {
        val shots =
            listOf(
                swing(90.0, player = "Ann", implement = "stack-100g", label = "Stack 100g"),
                swing(100.0, player = "Bob", implement = "stack-100g", label = "Stack 100g"),
                swing(80.0, player = " ann ", implement = "driver", label = "Driver"),
                swing(85.0, player = null, implement = "stack-100g", label = "Stack 100g"),
            )

        assertThat(computeSwingSpeedStats(shots, "ANN", "stack-100g").count).isEqualTo(1)
        assertThat(computeSwingSpeedStats(shots, "Ann", null).count).isEqualTo(2)
        assertThat(computeSwingSpeedStats(shots, "Player 1", "Stack 100g").lastSpeedMph).isEqualTo(85.0)
        assertThat(computeSwingSpeedStats(shots, "Nobody", null)).isEqualTo(SwingSpeedStats.EMPTY)
    }

    // endregion

    // region session stats

    @Test
    fun detailStatsMirrorComputeStats() {
        val stats =
            computeDetailStats(
                listOf(
                    mockShotDetail().copy(ballSpeedMph = 140.0, estimatedCarryYards = 240.0, clubSpeedMph = null),
                    mockShotDetail().copy(ballSpeedMph = 150.0, estimatedCarryYards = 260.0, clubSpeedMph = 100.0),
                ),
            )

        assertThat(stats.shotCount).isEqualTo(2)
        assertThat(stats.avgBallSpeedMph).isEqualTo(145.0)
        assertThat(stats.maxBallSpeedMph).isEqualTo(150.0)
        assertThat(stats.avgCarryYards).isEqualTo(250.0)
        assertThat(stats.avgClubSpeedMph).isEqualTo(100.0)
        assertThat(computeDetailStats(emptyList())).isEqualTo(ClubStats.EMPTY)
    }

    @Test
    fun detailClubChipsCountInFirstAppearanceOrder() {
        val chips =
            computeDetailClubChips(
                listOf(
                    mockShotDetail().copy(club = "7-iron"),
                    mockShotDetail(),
                    mockShotDetail().copy(club = "7-iron"),
                ),
            )

        assertThat(chips).containsExactly(ClubChip("7-iron", 2), ClubChip("driver", 1))
    }

    @Test
    fun serverStatsConvertWithNullAveragesAsZero() {
        val stats = SessionStats(shotCount = 0, avgBallSpeed = null, avgClubSpeed = null).toClubStats()

        assertThat(stats.shotCount).isEqualTo(0)
        assertThat(stats.avgBallSpeedMph).isEqualTo(0.0)
        assertThat(stats.avgClubSpeedMph).isNull()
    }

    // endregion

    // region CSV with detail (ShotList.tsx buildValidationCsv)

    @Test
    fun withADetailTheWebExportColumnsAreAppended() {
        val event = event()
        val csv =
            buildExportCsv(
                listOf(
                    ExportShot.of(event, mockShotDetail().copy(peakMagnitude = 812.0)),
                    ExportShot.of(swing(97.4, player = "Ann", implement = "stack-100g", label = "Stack 100g"), null),
                ),
            ).lines()

        assertThat(
            csv[0].endsWith(
                ",player,mode,implement,openflight_speed_mph,reading_count," +
                    "trigger_speed_mph,duration_ms,peak_magnitude",
            ),
        ).isTrue()
        assertThat(csv[1]).isEqualTo(
            "1,${event.eventId},2026-09-24T15:38:33.264795,driver,143.3,,,243.0,,,,,,Player 1,,driver,143.3,,,,812.0",
        )
        assertThat(csv[2]).isEqualTo(
            "2,,2026-09-24T16:00:00.000001,Swing Speed,97.4,97.4,,0.0,,,,,," +
                "Ann,swing-speed,Stack 100g,97.4,5,80.1,1200.0,",
        )
    }

    @Test
    fun withoutAnyDetailTheExportKeepsTheBaseColumnsOnly() {
        val csv = buildExportCsv(listOf(ExportShot.of(event(), null)))

        assertThat(csv.lines()[0].endsWith("spin_axis_deg")).isTrue()
        assertThat(csv).isEqualTo(buildShotsCsv(listOf(event())))
    }

    // endregion
}

/** The first `shot` the mock server sends (`PiFixtures.SHOT_FRAME` in core:data), trimmed to what matters here. */
private fun mockShotDetail(): ShotDetail =
    ShotDetail(
        timestamp = "2026-09-24T15:38:33.264795",
        ballSpeedMph = 143.3,
        clubSpeedMph = 98.3,
        smashFactor = 1.46,
        estimatedCarryYards = 243.0,
        carryRange = listOf(231.0, 255.0),
        club = "driver",
        playerName = "Player 1",
        launchAngleVertical = 15.4,
        launchAngleConfidence = 0.72,
        angleSource = "mock",
        spinRpm = 2836.0,
        spinSource = "calculated",
        spinQuality = "medium",
    )

private fun swing(
    speedMph: Double,
    player: String? = "Player 1",
    implement: String? = "driver",
    label: String? = "Driver",
): ShotDetail =
    ShotDetail(
        timestamp = "2026-09-24T16:00:00.000001",
        ballSpeedMph = speedMph,
        clubSpeedMph = speedMph,
        estimatedCarryYards = 0.0,
        club = "Swing Speed",
        playerName = player,
        mode = "swing-speed",
        swingSpeedReadingCount = 5,
        swingSpeedTriggerMph = 80.1,
        swingSpeedDurationMs = 1200.0,
        trainingImplement = implement,
        trainingImplementLabel = label,
    )

private fun event(): ShotEvent =
    ShotEvent(
        schemaVersion = 1,
        eventId = "00000000-0000-4000-8000-000000000001",
        timestamp = "2026-09-24T15:38:33.264795",
        club = "driver",
        ballSpeedMph = 143.3,
        estimatedCarryYards = 243.0,
    )
