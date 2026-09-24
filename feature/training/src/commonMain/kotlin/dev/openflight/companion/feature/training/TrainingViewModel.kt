// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.insights.DEFAULT_PLAYER
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.insights.computeSwingSpeedStats
import dev.openflight.companion.core.insights.swingSpeedMph
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.TrainingImplement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The swing-speed training screen's state holder (plan R6b), mirroring the web UI's swing-speed
 * view: pick a training implement, then read Last, Best and Average swing speed for the current
 * player and implement. Wi-Fi only ([PiSessionRepository]).
 */
class TrainingViewModel(
    private val piSession: PiSessionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    /** The implement being set, until the Pi confirms it with `training_implement_changed` (or rejects it). */
    private val pendingImplement = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val local = combine(pendingImplement, error, settings.units, ::LocalState)

    private val pi =
        combine(
            piSession.linkState,
            piSession.sessionShots,
            piSession.playerName,
            piSession.trainingImplement,
            piSession.triggerStatus,
        ) { link, shots, player, implement, trigger ->
            PiState(PiFeatureAvailability.of(link), shots, player, implement, trigger?.mode)
        }

    val uiState: StateFlow<TrainingUiState> =
        combine(pi, local, piSession.mockMode) { pi, local, mock ->
            val selectedId = local.pendingImplement ?: pi.implement?.implement ?: TrainingImplements.DEFAULT_ID
            val selected =
                TrainingImplements.find(selectedId)
                    ?: pi.implement?.let { ImplementOption(it.implement, it.label) }
                    ?: TrainingImplements.default
            val player = pi.playerName?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYER
            TrainingUiState(
                availability = pi.availability,
                units = local.units,
                implementGroups = TrainingImplements.groups,
                selectedImplement = selected,
                triggerMode = pi.triggerMode,
                isSwingSpeedMode = pi.triggerMode == SWING_SPEED_MODE,
                playerName = player,
                // The Pi lists newest first; the stats want the latest rep last.
                stats = computeSwingSpeedStats(pi.shots.asReversed(), player, selected.id),
                lastRep = pi.shots.firstOrNull { it.isSwingSpeed }?.toSwingRep(),
                error = local.error,
                showSimulateSwing = mock == true,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = initialState(),
        )

    init {
        viewModelScope.launch {
            piSession.trainingImplement.collect { confirmed ->
                if (confirmed != null && confirmed.implement == pendingImplement.value) pendingImplement.value = null
            }
        }
        viewModelScope.launch {
            piSession.notices.collect { notice ->
                if (notice is PiNotice.TrainingImplementFailed) {
                    pendingImplement.value = null
                    error.value = notice.message
                }
            }
        }
    }

    fun onEvent(event: TrainingEvent) {
        when (event) {
            is TrainingEvent.SelectImplement -> selectImplement(event.id)
            TrainingEvent.SimulateSwing -> send { piSession.simulateShot() }
            TrainingEvent.DismissError -> error.value = null
        }
    }

    private fun selectImplement(id: String) {
        error.value = null
        pendingImplement.value = id
        send(onFailure = { pendingImplement.value = null }) { piSession.setTrainingImplement(id) }
    }

    /** Runs a Pi command; a failure (e.g. "Not connected ...") becomes [TrainingUiState.error]. */
    @Suppress("TooGenericExceptionCaught") // Every command failure is shown the same way.
    private fun send(
        onFailure: () -> Unit = {},
        command: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure()
                error.value = failure.message ?: COMMAND_FAILED
            }
        }
    }

    private data class LocalState(
        val pendingImplement: String?,
        val error: String?,
        val units: UnitSystem,
    )

    private data class PiState(
        val availability: PiFeatureAvailability,
        val shots: List<ShotDetail>,
        val playerName: String?,
        val implement: TrainingImplement?,
        val triggerMode: String?,
    )

    companion object {
        const val COMMAND_FAILED = "The Pi didn't accept that."
        private const val SWING_SPEED_MODE = "swing-speed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private fun initialState(): TrainingUiState =
            TrainingUiState(
                availability = PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED),
                units = SettingsRepository.DEFAULT_UNITS,
                implementGroups = TrainingImplements.groups,
                selectedImplement = TrainingImplements.default,
                triggerMode = null,
                isSwingSpeedMode = false,
                playerName = DEFAULT_PLAYER,
                stats = computeSwingSpeedStats(emptyList(), null, null),
                lastRep = null,
                error = null,
                showSimulateSwing = false,
            )
    }
}

private fun ShotDetail.toSwingRep(): SwingRep? {
    val speed = swingSpeedMph ?: return null
    return SwingRep(
        speedMph = speed,
        implementLabel = trainingImplementLabel ?: club,
        readingCount = swingSpeedReadingCount,
        triggerSpeedMph = swingSpeedTriggerMph,
        durationMs = swingSpeedDurationMs,
        playerName = playerName,
    )
}
