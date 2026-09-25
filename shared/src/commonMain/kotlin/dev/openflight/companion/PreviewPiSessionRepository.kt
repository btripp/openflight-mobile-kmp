// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.SessionStats
import dev.openflight.companion.core.model.pi.ShotDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The `--preview-pi-session` launch hook's [PiSessionRepository] (debug builds only, plan R8f): a
 * connected Pi with two profiles (Ann active: a driver and a 7-iron; Bo: a driver), so the Session
 * screen's server-confirmed delete and clear can be seen and UI-tested without a Pi.
 *
 * A delete or clear is confirmed after [answerDelayMillis], as the server would; with `null`
 * (`--preview-pi-session-stuck`) the Pi never answers: a delete stays pending until the Session
 * screen gives up, and a clear fails after [ClearState.TIMEOUT_MILLIS] like the real repository.
 * Everything else is [delegate]'s, which is never started.
 */
internal class PreviewPiSessionRepository(
    private val delegate: PiSessionRepository,
    private val answerDelayMillis: Long? = DEFAULT_ANSWER_DELAY_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : PiSessionRepository by delegate {
    override val linkState: StateFlow<PiLinkState> = MutableStateFlow(PiLinkState.Connected)
    override val bluetoothSchemaV2: StateFlow<Boolean> = MutableStateFlow(false)
    override val sessionShots = MutableStateFlow(SEED)
    override val shotDetails = MutableStateFlow(SEED.associateBy { it.timestamp })
    override val stats: StateFlow<SessionStats?> = MutableStateFlow(null)
    override val profiles: StateFlow<ProfilesState> =
        MutableStateFlow(
            ProfilesState(
                profiles = listOf(Profile(id = ANN, name = "Ann"), Profile(id = BO, name = "Bo")),
                activeProfileId = ANN,
                loaded = true,
            ),
        )
    override val club: StateFlow<String?> = MutableStateFlow("driver")
    override val mockMode: StateFlow<Boolean?> = MutableStateFlow(false)
    override val deletionState = MutableStateFlow<DeletionState>(DeletionState.Idle)
    override val clearState = MutableStateFlow<ClearState>(ClearState.Idle)

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun refreshSession() = Unit

    override suspend fun deleteShot(timestamp: String) {
        if (deletionState.value is DeletionState.Pending) return
        deletionState.value = DeletionState.Pending(timestamp)
        answer {
            sessionShots.update { shots -> shots.filterNot { it.timestamp == timestamp } }
            deletionState.update { it.succeed(timestamp) }
        }
    }

    override fun dismissDeletion() {
        deletionState.value = DeletionState.Idle
    }

    override suspend fun clearSession(profileId: String) {
        if (clearState.value is ClearState.Pending) return
        clearState.value = ClearState.Pending(profileId)
        if (answerDelayMillis == null) {
            scope.launch {
                delay(ClearState.TIMEOUT_MILLIS)
                clearState.update {
                    if (it ==
                        ClearState.Pending(profileId)
                    ) {
                        ClearState.Failed(profileId, ClearState.NO_CONFIRMATION)
                    } else {
                        it
                    }
                }
            }
        }
        answer {
            sessionShots.update { shots -> shots.filterNot { it.profileId == profileId } }
            clearState.update { if (it == ClearState.Pending(profileId)) ClearState.Cleared(profileId) else it }
        }
    }

    override fun dismissClear() {
        clearState.value = ClearState.Idle
    }

    private fun answer(reply: () -> Unit) {
        val delayMillis = answerDelayMillis ?: return
        scope.launch {
            delay(delayMillis)
            reply()
        }
    }

    companion object {
        /** Long enough for a UI test to see the pending state, short enough not to slow it down. */
        const val DEFAULT_ANSWER_DELAY_MILLIS = 4_000L

        private const val ANN = "ann"
        private const val BO = "bo"

        /** Newest first, like `session_state` once the client has reversed it. */
        @Suppress("MagicNumber") // Made-up shots, like `PreviewShotRepository.PREVIEW_SHOT`.
        private val SEED: List<ShotDetail> =
            listOf(
                shot("2026-09-25T11:05:00.000000", 3, "7-iron", 118.2, 165.0, 1.5, ANN, "Ann"),
                shot("2026-09-25T11:02:00.000000", 2, "driver", 148.9, 251.0, 2.0, BO, "Bo"),
                shot("2026-09-25T11:00:00.000000", 1, "driver", 151.4, 264.0, -1.3, ANN, "Ann"),
            )

        @Suppress("LongParameterList", "MagicNumber")
        private fun shot(
            timestamp: String,
            number: Int,
            club: String,
            ballSpeedMph: Double,
            carryYards: Double,
            launchAngleHorizontal: Double,
            profileId: String,
            profileName: String,
        ) = ShotDetail(
            timestamp = timestamp,
            shotNumber = number,
            ballSpeedMph = ballSpeedMph,
            estimatedCarryYards = carryYards,
            club = club,
            profileId = profileId,
            profileName = profileName,
            launchAngleVertical = 12.6,
            launchAngleHorizontal = launchAngleHorizontal,
            spinRpm = 2_380.0,
        )
    }
}
