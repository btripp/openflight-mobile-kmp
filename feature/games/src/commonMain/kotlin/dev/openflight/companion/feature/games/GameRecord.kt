// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.games

import dev.openflight.companion.core.data.Activity
import dev.openflight.companion.core.insights.UnitSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A player as stored in [Activity.playersJson]. */
@Serializable
data class PlayerRecord(
    val id: String,
    val name: String,
    val colorIndex: Int,
)

/** One player's final line in [GameResultRecord]. */
@Serializable
data class StandingRecord(
    val playerId: String,
    val shotsTaken: Int,
    val total: Double? = null,
    val totalLabel: String,
)

/** One scored shot in [GameResultRecord]. `eventId` ties it back to the history's shot. */
@Serializable
data class ShotRecord(
    val eventId: String,
    val playerId: String,
    val club: String,
    val targetYards: Double,
    val targetLateralYards: Double = 0.0,
    val carryYards: Double,
    val totalYards: Double? = null,
    val points: Double,
    val deltaYards: Double? = null,
    val label: String,
    val lateralUnknown: Boolean = false,
    val estimated: Boolean = false,
)

/**
 * A finished game as stored in [Activity.resultJson] (schema [VERSION]). Unknown keys are ignored
 * when read, so later versions can add fields.
 */
@Serializable
data class GameResultRecord(
    val version: Int = VERSION,
    val type: String,
    val title: String,
    val completed: Boolean,
    val winnerIds: List<String>,
    val standings: List<StandingRecord>,
    val shots: List<ShotRecord>,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Reads and writes the games' [Activity] payloads. */
object GameRecords {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val playersSerializer = ListSerializer(PlayerRecord.serializer())

    /**
     * The [Activity] for a finished [state]: its title, headline, players and result, filed under
     * [sessionId] (the history session its shots went to) when known.
     */
    fun activity(
        id: String,
        state: GameState,
        units: UnitSystem,
        sessionId: String?,
    ): Activity {
        val mode = state.mode
        val players = state.players.map { PlayerRecord(it.id, it.name, it.colorIndex) }
        val result =
            GameResultRecord(
                type = mode.type.storageValue,
                title = GameCopy.title(mode, units),
                completed = state.isComplete,
                winnerIds = state.winners.map { it.id },
                standings =
                    state.standings.map {
                        StandingRecord(
                            it.player.id,
                            it.shotsTaken,
                            it.total,
                            GameCopy.totalLabel(mode, it.total, units),
                        )
                    },
                shots =
                    state.attributions.mapNotNull { attribution ->
                        val shot = attribution.shot ?: return@mapNotNull null
                        val score = attribution.score ?: return@mapNotNull null
                        ShotRecord(
                            eventId = attribution.eventId,
                            playerId = attribution.playerId,
                            club = shot.club,
                            targetYards = attribution.target.distanceYards,
                            targetLateralYards = attribution.target.lateralYards,
                            carryYards = shot.carryYards,
                            totalYards = shot.totalYards,
                            points = score.points,
                            deltaYards = score.deltaYards,
                            label = GameCopy.scoreLabel(mode, score, units),
                            lateralUnknown = score.lateralUnknown,
                            estimated = score.metricEstimated,
                        )
                    },
            )
        val started = state.startedAtEpochMillis ?: state.endedAtEpochMillis ?: 0L
        return Activity(
            id = id,
            type = mode.type.storageValue,
            startedAtEpochMillis = started,
            endedAtEpochMillis = state.endedAtEpochMillis,
            title = result.title,
            headline = GameCopy.headline(state, units),
            playersJson = json.encodeToString(playersSerializer, players),
            resultJson = json.encodeToString(GameResultRecord.serializer(), result),
            sessionId = sessionId,
        )
    }

    /** [Activity.playersJson] read back, or empty when it isn't a games payload. */
    fun players(activity: Activity): List<PlayerRecord> =
        decode { json.decodeFromString(playersSerializer, activity.playersJson) } ?: emptyList()

    /** [Activity.resultJson] read back, or `null` when it isn't a games payload. */
    fun result(activity: Activity): GameResultRecord? =
        decode { json.decodeFromString(GameResultRecord.serializer(), activity.resultJson) }

    private inline fun <T> decode(block: () -> T): T? =
        try {
            block()
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: malformed or foreign JSON.
            null
        }
}
