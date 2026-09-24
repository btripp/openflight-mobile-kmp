// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import dev.openflight.companion.core.model.ShotEvent

/**
 * CSV export, ported from the web UI's shot export (`ShotList.tsx`'s `buildValidationCsv` +
 * `csvValue`/`downloadCsv`), minus the comparator-device columns (`comparator_device`,
 * `comparator_speed_mph`, `difference_mph`) the plan (R5a) explicitly drops, and minus the
 * validation-only fields the web `Shot` type carries that [ShotEvent] doesn't (`player_name`,
 * `mode`, `training_implement*`, `swing_speed_*`, `peak_magnitude`, and the free-text validation
 * `notes`). The columns that remain are every measurement [ShotEvent] carries.
 *
 * [shots] is numbered in list order (`shot_number` = index + 1); pass the list oldest-first, the
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

/** Builds the CSV document's full text, including the header row, `\n`-joined. */
fun buildShotsCsv(shots: List<ShotEvent>): String {
    val rows =
        shots.mapIndexed { index, shot ->
            listOf(
                (index + 1).toString(),
                shot.eventId,
                shot.timestamp,
                shot.club,
                shot.ballSpeedMph.toString(),
                shot.clubSpeedMph?.toString().orEmpty(),
                shot.smashFactor?.toString().orEmpty(),
                shot.estimatedCarryYards.toString(),
                shot.launchAngleVertical?.toString().orEmpty(),
                shot.launchAngleHorizontal?.toString().orEmpty(),
                shot.spinRpm?.toString().orEmpty(),
                shot.clubPathDeg?.toString().orEmpty(),
                shot.spinAxisDeg?.toString().orEmpty(),
            )
        }
    return (listOf(CSV_HEADERS) + rows).joinToString("\n") { row -> row.joinToString(",", transform = ::csvField) }
}

/**
 * `openflight-shots-<stamp>.csv`, mirroring the web UI's `openflight-validation-${stamp}.csv`
 * (`ShotList.tsx`'s `handleExport`), where `stamp` is [nowIso] (an ISO-8601 instant, e.g. from
 * `kotlin.time.Clock.System.now()`) with `:` and `.` replaced by `-` so it's filesystem-safe.
 */
fun buildShotsCsvFilename(nowIso: String): String {
    val stamp = nowIso.replace(Regex("[:.]"), "-")
    return "openflight-shots-$stamp.csv"
}

/** Mirrors the web UI's `csvValue` (`ShotList.tsx`): quotes and escapes only when a field needs it. */
private fun csvField(value: String): String =
    if (NEEDS_QUOTING.containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value

private val NEEDS_QUOTING = Regex("[\",\n\r]")
