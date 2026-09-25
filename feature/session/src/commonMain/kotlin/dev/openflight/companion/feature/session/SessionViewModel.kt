// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ExportShot
import dev.openflight.companion.core.insights.buildExportCsv
import dev.openflight.companion.core.insights.buildShotsCsvFilename
import dev.openflight.companion.core.insights.computeClubChips
import dev.openflight.companion.core.insights.computeClubStats
import dev.openflight.companion.core.insights.computeDetailClubChips
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.insights.computeSwingSpeedStats
import dev.openflight.companion.core.insights.forProfile
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The session/stats screen's state holder, ported from the web UI's `StatsView.tsx` (stats and
 * tabs) and `ShotList.tsx` (list, delete, export).
 *
 * Plan R6b: while the Pi's Socket.IO link is connected the screen shows the **Pi's** session
 * (`sessionShots` and its server-computed stats), like the web UI; otherwise it shows the phone's
 * own history with `core:insights` stats ([SessionSource]). Delete and clear go through
 * [ShotRepository], which also routes them to the Pi (by timestamp) while the link is connected.
 */
@OptIn(ExperimentalTime::class)
class SessionViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val piSession: PiSessionRepository,
    private val now: () -> String = { Clock.System.now().toString() },
) : ViewModel() {
    /** The club tab and the selected shot change together, so one state never mixes old and new. */
    private val selection = MutableStateFlow(Selection(club = null, shotId = null))
    private val dispersion = DispersionCalculator()

    private val piView =
        combine(piSession.linkState, piSession.sessionShots, piSession.profiles, piSession.shotDetails, ::PiView)

    private val piFlags = combine(piSession.mockMode, piSession.triggerStatus, settings.transport, ::PiFlags)

    val uiState: StateFlow<SessionUiState> =
        combine(shots.history, settings.units, selection, piView, piFlags) { history, units, picked, pi, flags ->
            val selected = picked.club
            val base =
                if (pi.connected) {
                    piState(pi, selected)
                } else {
                    localState(history, pi.details, selected)
                }
            val points =
                if (pi.connected) {
                    dispersion.piPoints(pi.activeSession)
                } else {
                    dispersion.localPoints(history, pi.details)
                }
            val chart = dispersionState(points, selected, units)
            base.copy(
                units = units,
                dispersion = chart,
                selectedShot = selectedShotCard(picked.shotId, chart, base.shots),
                showSimulateShot = flags.mockMode == true,
                simulateLabel =
                    if (flags.triggerStatus?.mode == SWING_SPEED_MODE) {
                        SessionUiState.SIMULATE_SWING
                    } else {
                        SessionUiState.SIMULATE_SHOT
                    },
                simulateAvailability = PiFeatureAvailability.of(pi.link),
                editAvailability =
                    PiFeatureAvailability.forDeleteAndClear(overBluetooth = flags.transport == TransportType.BLUETOOTH),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionUiState(),
        )

    private val sessionEffects = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = sessionEffects.receiveAsFlow()

    init {
        // A server-confirmed delete or clear that failed (refused, dropped link, no confirmation) is
        // shown once. The pending/confirmed states themselves are plan R8f's UI.
        viewModelScope.launch {
            piSession.deletionState.collect { state ->
                if (state is DeletionState.Failed) {
                    sessionEffects.send(SessionEffect.Message(state.reason))
                    piSession.dismissDeletion()
                }
            }
        }
        viewModelScope.launch {
            piSession.clearState.collect { state ->
                if (state is ClearState.Failed) {
                    sessionEffects.send(SessionEffect.Message(state.reason))
                    piSession.dismissClear()
                }
            }
        }
    }

    fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.SelectClub -> selectClub(event.club)
            is SessionEvent.SelectShot -> selectShot(event.id)
            is SessionEvent.DeleteShot -> ifEditable { deleteShot(event.id) }
            SessionEvent.ClearHistory -> ifEditable { shots.clearHistory() }
            SessionEvent.ExportCsv -> exportCsv()
            SessionEvent.SimulateShot -> simulateShot()
        }
    }

    /** Plan R8e: over Bluetooth delete and clear are off; a stray request explains why instead. */
    private inline fun ifEditable(action: () -> Unit) {
        val reason = uiState.value.editAvailability.disabledReason
        if (reason == null) {
            action()
        } else {
            sessionEffects.trySend(SessionEffect.Message(reason))
        }
    }

    private fun selectClub(club: String?) {
        selection.value = Selection(club = club, shotId = null)
    }

    /** A shot from another club than the selected tab switches back to "All", so its dot shows. */
    private fun selectShot(id: String?) {
        val shotClub = id?.let(::clubOf)
        selection.update { current ->
            val keepTab = current.club == null || shotClub == null || shotClub == current.club
            Selection(club = if (keepTab) current.club else null, shotId = id)
        }
    }

    /**
     * A row's id is an event id on [SessionSource.LOCAL] and a timestamp on [SessionSource.PI].
     * Routing on the id itself (event ids are UUIDs, never timestamps) stays right even if the link
     * changed between rendering the row and the tap.
     */
    private fun deleteShot(id: String) {
        if (shots.history.value.any { it.eventId == id }) {
            shots.deleteShot(id)
        } else {
            shots.deleteShotByTimestamp(id)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Any failure becomes a message, like a Pi error.
    private fun simulateShot() {
        viewModelScope.launch {
            try {
                piSession.simulateShot()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                sessionEffects.send(SessionEffect.Message(error.message ?: SIMULATE_FAILED))
            }
        }
    }

    /**
     * Exports the whole session (not just the selected tab), oldest first, like the web UI's
     * export. On [SessionSource.PI] the rows are the Pi's; locally they're the phone's history. Rows
     * with a known [ShotDetail] add the web export's player/mode/implement/swing columns.
     */
    private fun exportCsv() {
        viewModelScope.launch {
            val history = shots.history.value
            val rows =
                if (piSession.linkState.value == PiLinkState.Connected) {
                    val eventIds = history.associate { it.timestamp to it.eventId }
                    piSession.sessionShots.value
                        .asReversed()
                        .map { ExportShot.of(it, eventIds[it.timestamp]) }
                } else {
                    history.asReversed().map { ExportShot.of(it, piSession.detailFor(it)) }
                }
            sessionEffects.send(SessionEffect.CsvReady(buildExportCsv(rows), buildShotsCsvFilename(now())))
        }
    }

    private fun localState(
        history: List<ShotEvent>,
        details: Map<String, ShotDetail>,
        selected: String?,
    ): SessionUiState {
        val filtered = if (selected == null) history else history.filter { it.club == selected }
        return SessionUiState(
            source = SessionSource.LOCAL,
            allCount = history.size,
            clubChips = computeClubChips(history),
            selectedClub = selected,
            stats = computeClubStats(filtered),
            shots = localRows(history, details).forTab(selected),
        )
    }

    private fun piState(
        pi: PiView,
        selected: String?,
    ): SessionUiState {
        val session = pi.activeSession
        val filtered = if (selected == null) session else session.filter { it.club == selected }
        val isSwingSession = filtered.isNotEmpty() && filtered.all { it.isSwingSpeed }
        return SessionUiState(
            source = SessionSource.PI,
            allCount = session.size,
            clubChips = computeDetailClubChips(session),
            selectedClub = selected,
            // Computed here: the server's stats cover every profile.
            stats = computeDetailStats(filtered),
            swingStats =
                if (isSwingSession) {
                    computeSwingSpeedStats(filtered.asReversed(), profileId = null, trainingImplement = null)
                } else {
                    null
                },
            shots = piRows(session).forTab(selected),
        )
    }

    private data class PiView(
        val link: PiLinkState,
        val session: List<ShotDetail>,
        val profiles: ProfilesState,
        val details: Map<String, ShotDetail>,
    ) {
        val connected: Boolean get() = link == PiLinkState.Connected

        /**
         * The Pi's session holds every profile's rows: the screen (list, stats and dispersion
         * chart) shows the active profile's (plan 9.2). A Pi that never sends a roster (before
         * profiles) keeps the whole session.
         */
        val activeSession: List<ShotDetail>
            get() = if (profiles.loaded) session.forProfile(profiles.activeProfileId) else session
    }

    /**
     * The club of the shot with row id [id], looked up in the whole session: the rows in [uiState]
     * only cover the selected tab. Ids are event ids locally and timestamps on the Pi.
     */
    private fun clubOf(id: String): String? =
        shots.history.value
            .firstOrNull { it.eventId == id }
            ?.club
            ?: piSession.sessionShots.value
                .firstOrNull { it.timestamp == id }
                ?.club

    private data class Selection(
        val club: String?,
        val shotId: String?,
    )

    private data class PiFlags(
        val mockMode: Boolean?,
        val triggerStatus: TriggerStatus?,
        val transport: TransportType,
    )

    companion object {
        const val SIMULATE_FAILED = "Couldn't simulate a shot."
        private const val SWING_SPEED_MODE = "swing-speed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The selected tab's rows; each keeps its session-wide `#n`. */
private fun List<SessionShotRow>.forTab(club: String?): List<SessionShotRow> =
    if (club == null) this else filter { it.club == club }
