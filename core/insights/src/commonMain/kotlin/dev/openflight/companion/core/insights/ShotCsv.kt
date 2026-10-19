// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.flight.FlightMeasurements
import dev.openflight.companion.core.flight.ShotDistanceEstimator
import dev.openflight.companion.core.flight.toFlightMeasurements
import dev.openflight.companion.core.model.Conditions
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.ShotMetricFormatter
import dev.openflight.companion.core.model.TargetBearing
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlin.math.round

/**
 * CSV export, ported from the web UI's shot export (`ShotList.tsx`'s `buildValidationCsv` +
 * `csvValue`/`downloadCsv`), minus the comparator-device columns (`comparator_device`,
 * `comparator_speed_mph`, `difference_mph`) and the free-text validation `notes`, which the plan
 * (R5a) drops.
 *
 * The base columns are every measurement [ShotEvent] carries. When at least one row has the
 * Pi's Socket.IO [ShotDetail] (plan R6b), the web export's session columns are appended too
 * ([DETAIL_HEADERS]: profile, mode, implement, the swing speed as `openflight_speed_mph`, and the
 * swing-speed/peak-magnitude values); rows without a detail leave them empty.
 *
 * Plan F5b: rows given an estimate ([withEstimatedTotals]) add `roll_est_yards` and
 * `total_est_yards` at the end; the `_est` names mark them as the app's estimates, not the Pi's.
 *
 * Rows are numbered in list order (`shot_number` = index + 1); pass the list oldest-first, the
 * way the web UI's underlying shot array is ordered, so shot 1 is the oldest -- callers reading
 * from [dev.openflight.companion.core.data.ShotRepository.history] (newest first) should reverse
 * it first.
 */
private val CSV_HEADERS =
    listOf(
        "shot_number",
        "event_id",
        "timestamp",
        "club",
        "ball_speed_mph",
        "club_speed_mph",
        "smash_factor",
        "estimated_carry_yards",
        "launch_angle_vertical",
        "launch_angle_horizontal",
        "spin_rpm",
        "club_path_deg",
        "spin_axis_deg",
    )

/** The web export's session columns (`buildValidationCsv`), appended when a [ShotDetail] is known. */
private val DETAIL_HEADERS =
    listOf(
        "profile",
        "mode",
        "implement",
        "openflight_speed_mph",
        "reading_count",
        "trigger_speed_mph",
        "duration_ms",
        "peak_magnitude",
    )

/** Plan F5b's estimated columns, appended when any row has an estimate. */
private val ESTIMATE_HEADERS = listOf("roll_est_yards", "total_est_yards")

/**
 * One exported row: an SSE/BLE shot, a Pi session row, or both joined on the timestamp.
 * [eventId] is `null` for a Pi session row the phone never received over SSE/BLE.
 * [session] names the stored session the row came from in a multi-session export
 * ([buildExportCsv] then adds a leading `session` column); `null` for a single-session export.
 */
data class ExportShot(
    val eventId: String?,
    val timestamp: String,
    val club: String,
    val ballSpeedMph: Double?,
    val clubSpeedMph: Double?,
    val smashFactor: Double?,
    val estimatedCarryYards: Double?,
    val launchAngleVertical: Double?,
    val launchAngleHorizontal: Double?,
    val spinRpm: Double?,
    val clubPathDeg: Double?,
    val spinAxisDeg: Double?,
    val detail: ShotDetail?,
    val session: String? = null,
    val rollEstYards: Double? = null,
    val totalEstYards: Double? = null,
) {
    /** What the estimator needs: the Pi's row when known (it has the spin-adjusted carry), else the shot's own. */
    internal val measurements: FlightMeasurements?
        get() {
            if (detail != null) return detail.toFlightMeasurements()
            val speed = ballSpeedMph ?: return null
            val carry = estimatedCarryYards ?: return null
            return FlightMeasurements(
                id = eventId ?: timestamp,
                club = club,
                ballSpeedMph = speed,
                carryYards = carry,
                launchAngleVertical = launchAngleVertical,
                launchAngleHorizontal = launchAngleHorizontal,
                spinRpm = spinRpm,
                spinAxisDeg = spinAxisDeg,
            )
        }

    companion object {
        /** An SSE/BLE shot, with the Pi's detail for it when known. */
        fun of(
            shot: ShotEvent,
            detail: ShotDetail?,
        ): ExportShot =
            ExportShot(
                eventId = shot.eventId,
                timestamp = shot.timestamp,
                club = shot.club,
                ballSpeedMph = shot.ballSpeedMph,
                clubSpeedMph = shot.clubSpeedMph,
                smashFactor = shot.smashFactor,
                estimatedCarryYards = shot.estimatedCarryYards,
                launchAngleVertical = shot.launchAngleVertical,
                launchAngleHorizontal = shot.launchAngleHorizontal,
                spinRpm = shot.spinRpm,
                clubPathDeg = shot.clubPathDeg,
                spinAxisDeg = shot.spinAxisDeg,
                detail = detail,
            )

        /** A Pi session row, with the matching SSE/BLE shot's [eventId] when the phone has one. */
        fun of(
            detail: ShotDetail,
            eventId: String?,
        ): ExportShot =
            ExportShot(
                eventId = eventId,
                timestamp = detail.timestamp,
                club = detail.club.orEmpty(),
                ballSpeedMph = detail.ballSpeedMph,
                clubSpeedMph = detail.clubSpeedMph,
                smashFactor = detail.smashFactor,
                estimatedCarryYards = detail.estimatedCarryYards,
                launchAngleVertical = detail.launchAngleVertical,
                launchAngleHorizontal = detail.launchAngleHorizontal,
                spinRpm = detail.spinRpm,
                clubPathDeg = detail.clubPathDeg,
                spinAxisDeg = detail.spinAxisDeg,
                detail = detail,
            )
    }
}

/** [this] with each row's estimated roll and total in [conditions] (plan F5b); rows that can't be flown keep none. */
fun List<ExportShot>.withEstimatedTotals(
    conditions: Conditions,
    targetBearing: TargetBearing?,
): List<ExportShot> {
    val estimator = ShotDistanceEstimator()
    return map { shot ->
        val measurements = shot.measurements ?: return@map shot
        val estimate = estimator.estimate(measurements, conditions, targetBearing) ?: return@map shot
        shot.copy(rollEstYards = estimate.rollYards.tenths(), totalEstYards = estimate.totalYards.tenths())
    }
}

// Rounded to 0.1 yd: the estimate is nowhere near that precise, and the cells stay readable.
private fun Double.tenths(): Double = round(this * TENTHS) / TENTHS

private const val TENTHS = 10.0

/** Builds the CSV document's full text for SSE/BLE shots only, including the header row, `\n`-joined. */
fun buildShotsCsv(shots: List<ShotEvent>): String = buildExportCsv(shots.map { ExportShot.of(it, null) })

/**
 * Builds the CSV document's full text, including the header row, `\n`-joined. When any row names
 * its [ExportShot.session] (a multi-session export), a leading `session` column is added and
 * `shot_number` restarts at 1 for each session; otherwise the document is unchanged.
 */
fun buildExportCsv(shots: List<ExportShot>): String {
    val withDetail = shots.any { it.detail != null }
    val withSession = shots.any { it.session != null }
    val withEstimate = shots.any { it.totalEstYards != null }
    val headers =
        (if (withSession) listOf(SESSION_HEADER) else emptyList()) +
            (if (withDetail) CSV_HEADERS + DETAIL_HEADERS else CSV_HEADERS) +
            (if (withEstimate) ESTIMATE_HEADERS else emptyList())
    val numbers = shotNumbers(shots, withSession)
    val rows =
        shots.mapIndexed { index, shot ->
            val base =
                listOf(
                    numbers[index].toString(),
                    shot.eventId.orEmpty(),
                    shot.timestamp,
                    shot.club,
                    shot.ballSpeedMph.cell(),
                    shot.clubSpeedMph.cell(),
                    shot.smashFactor.cell(),
                    shot.estimatedCarryYards.cell(),
                    shot.launchAngleVertical.cell(),
                    shot.launchAngleHorizontal.cell(),
                    shot.spinRpm.cell(),
                    shot.clubPathDeg.cell(),
                    shot.spinAxisDeg.cell(),
                )
            val detailed = if (withDetail) base + detailCells(shot) else base
            val cells =
                if (withEstimate) {
                    detailed +
                        listOf(
                            shot.rollEstYards.cell(),
                            shot.totalEstYards.cell(),
                        )
                } else {
                    detailed
                }
            if (withSession) listOf(shot.session.orEmpty()) + cells else cells
        }
    return (listOf(headers) + rows).joinToString("\n") { row -> row.joinToString(",", transform = ::csvField) }
}

/** 1, 2, 3… down the list, or restarting at 1 whenever the session changes. */
private fun shotNumbers(
    shots: List<ExportShot>,
    perSession: Boolean,
): List<Int> {
    var number = 0
    var session: String? = null
    return shots.map { shot ->
        if (perSession && shot.session != session) {
            session = shot.session
            number = 0
        }
        ++number
    }
}

private const val SESSION_HEADER = "session"

/** The web export's session cells for one row, or empty cells without a detail. */
private fun detailCells(shot: ExportShot): List<String> {
    val detail = shot.detail ?: return List(DETAIL_HEADERS.size) { "" }
    // `openflightSpeed = isSwingSpeedShot(shot) ? getSwingSpeedMph(shot) : shot.ball_speed_mph`, `.toFixed(1)`.
    val speed = if (detail.isSwingSpeed) detail.swingSpeedMph else detail.ballSpeedMph ?: shot.ballSpeedMph
    return listOf(
        detail.profileName.orEmpty(),
        detail.mode.orEmpty(),
        detail.trainingImplementLabel ?: detail.club ?: shot.club,
        speed?.let { ShotMetricFormatter.number(it, 1).replace(",", "") }.orEmpty(),
        detail.swingSpeedReadingCount?.toString().orEmpty(),
        detail.swingSpeedTriggerMph.cell(),
        detail.swingSpeedDurationMs.cell(),
        detail.peakMagnitude.cell(),
    )
}

private fun Double?.cell(): String = this?.toString().orEmpty()

/**
 * `openflight-shots-<stamp>.csv`, mirroring the web UI's `openflight-validation-${stamp}.csv`
 * (`ShotList.tsx`'s `handleExport`), where `stamp` is [nowIso] (an ISO-8601 instant, e.g. from
 * `kotlin.time.Clock.System.now()`) with `:` and `.` replaced by `-` so it's filesystem-safe.
 */
fun buildShotsCsvFilename(nowIso: String): String {
    val stamp = nowIso.replace(Regex("[:.]"), "-")
    return "openflight-shots-$stamp.csv"
}

/** `openflight-sessions-<stamp>.csv`: several stored sessions in one export, like [buildShotsCsvFilename]. */
fun buildSessionsCsvFilename(nowIso: String): String {
    val stamp = nowIso.replace(Regex("[:.]"), "-")
    return "openflight-sessions-$stamp.csv"
}

/** Mirrors the web UI's `csvValue` (`ShotList.tsx`): quotes and escapes only when a field needs it. */
private fun csvField(value: String): String =
    if (NEEDS_QUOTING.containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value

private val NEEDS_QUOTING = Regex("[\",\n\r]")
