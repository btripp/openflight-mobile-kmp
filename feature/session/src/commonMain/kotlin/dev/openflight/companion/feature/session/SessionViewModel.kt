// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.ClubChip
import dev.openflight.companion.core.insights.ExportShot
import dev.openflight.companion.core.insights.buildExportCsv
import dev.openflight.companion.core.insights.buildShotsCsvFilename
import dev.openflight.companion.core.insights.computeClubChips
import dev.openflight.companion.core.insights.computeClubStats
import dev.openflight.companion.core.insights.computeDetailClubChips
import dev.openflight.companion.core.insights.computeDetailStats
import dev.openflight.companion.core.insights.computeSwingSpeedStats
import dev.openflight.companion.core.insights.forProfile
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.ShotEvent
import dev.openflight.companion.core.model.pi.ClearState
import dev.openflight.companion.core.model.pi.DeletionState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.model.pi.TriggerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
 *
 * Plan R8f: delete and clear ask for confirmation, then show pending, done or failed
 * ([SessionUiState.action], a [SessionActionState]); the club tabs follow the server's club.
 */
@Suppress("TooManyFunctions") // One screen's intents; split by region below.
@OptIn(ExperimentalTime::class)
class SessionViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val piSession: PiSessionRepository,
    private val now: () -> String = { Clock.System.now().toString() },
) : ViewModel() {
    /** The club tab and the selected shot change together, so one state never mixes old and new. */
    private val selection = MutableStateFlow(Selection(club = null, shotId = null, following = true))
    private val dispersion = DispersionCalculator()

    /** The delete or clear in progress (plan R8f), and whether its request went to the Pi. */
    private val action = MutableStateFlow(ActionRecord())
    private var actionJob: Job? = null

    private val piView =
        combine(piSession.linkState, piSession.sessionShots, piSession.profiles, piSession.shotDetails, ::PiView)

    private val piFlags = combine(piSession.mockMode, piSession.triggerStatus, settings.transport, ::PiFlags)

    /**
     * The club the Pi (or else the BLE/SSE transport) says is in use: the club tabs follow it
     * (Expo `stats.tsx`, like the kiosk).
     */
    private val serverClub =
        combine(piSession.linkState, piSession.club, shots.activeClub) { link, piClub, active ->
            (if (link == PiLinkState.Connected) piClub else null) ?: active?.wireValue
        }.distinctUntilChanged()

    private val controls = combine(selection, action, serverClub, shots.connectionState, ::Controls)

    val uiState: StateFlow<SessionUiState> =
        combine(shots.history, settings.units, controls, piView, piFlags) { history, units, control, pi, flags ->
            val picked = control.selection
            val base =
                if (pi.connected) {
                    piState(pi, picked, control.serverClub)
                } else {
                    localState(history, pi.details, picked, control.serverClub)
                }
            val points =
                if (pi.connected) {
                    dispersion.piPoints(pi.activeSession)
                } else {
                    dispersion.localPoints(history, pi.details)
                }
            val chart = dispersionState(points, base.selectedClub, units)
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
                action = control.action.forDisplay(pi),
                profileName = if (pi.connected) pi.profiles.activeProfile?.name else null,
                staleNote =
                    SessionUiState.STALE_NOTE.takeIf {
                        !pi.connected && control.connection != ConnectionState.Connected && base.hasShots
                    },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SessionUiState(),
        )

    private val sessionEffects = Channel<SessionEffect>(Channel.BUFFERED)
    val effects: Flow<SessionEffect> = sessionEffects.receiveAsFlow()

    init {
        // Expo `stats.tsx`: a club or profile change re-opens the tabs on the club in use.
        viewModelScope.launch {
            combine(serverClub, piSession.profiles) { club, profiles -> club to profiles.activeProfileId }
                .distinctUntilChanged()
                .drop(1)
                .collect { selection.value = Selection(club = null, shotId = null, following = true) }
        }
        // A confirmation is only good for what it was opened against. Nothing has been sent yet, so
        // it simply closes once that goes away (Expo `stats.tsx`).
        viewModelScope.launch {
            val context = combine(piSession.linkState, piSession.profiles, settings.transport, ::Triple)
            context.collect { (link, profiles, transport) ->
                val confirming = action.value.state as? SessionActionState.Confirming ?: return@collect
                if (isStale(confirming.action, link, profiles, transport)) action.value = ActionRecord()
            }
        }
    }

    fun onEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.SelectClub -> selectClub(event.club)
            is SessionEvent.SelectShot -> selectShot(event.id)
            is SessionEvent.DeleteShot -> ifEditable { confirmDelete(event.id) }
            SessionEvent.ClearHistory -> ifEditable { confirmClear() }
            SessionEvent.ConfirmAction -> confirmAction()
            SessionEvent.CancelAction -> cancelAction()
            SessionEvent.RetryAction -> retryAction()
            SessionEvent.DismissAction -> dismissAction()
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

    /** A tapped tab stops following the server's club until the club or the profile changes. */
    private fun selectClub(club: String?) {
        selection.value = Selection(club = club, shotId = null, following = false)
    }

    /** A shot from another club than the selected tab switches back to "All", so its dot shows. */
    private fun selectShot(id: String?) {
        val shotClub = id?.let(::clubOf)
        val tab = uiState.value.selectedClub
        selection.update { current ->
            val keepTab = tab == null || shotClub == null || shotClub == tab
            if (keepTab) current.copy(shotId = id) else Selection(club = null, shotId = id, following = false)
        }
    }

    // region destructive actions (plan R8f)

    private fun confirmDelete(id: String) {
        if (action.value.state.isBusy) return
        val row = uiState.value.shots.firstOrNull { it.id == id }
        val target =
            SessionAction.DeleteShot(
                id = id,
                shotNumber = row?.shotNumber ?: 0,
                clubName = row?.let { it.implementLabel ?: clubName(it.club) }.orEmpty(),
            )
        action.value = ActionRecord(SessionActionCopy.confirmDelete(target, onPi = piConnected()))
    }

    private fun confirmClear() {
        if (action.value.state.isBusy) return
        val profiles = piSession.profiles.value
        val target =
            if (piConnected() && profiles.activeProfileId.isNotEmpty()) {
                SessionAction.ClearSession(profiles.activeProfileId, profiles.activeProfile?.name)
            } else {
                SessionAction.ClearSession(profileId = null, profileName = null)
            }
        action.value = ActionRecord(SessionActionCopy.confirmClear(target))
    }

    private fun isStale(
        target: SessionAction,
        link: PiLinkState,
        profiles: ProfilesState,
        transport: TransportType,
    ): Boolean {
        val profileId = (target as? SessionAction.ClearSession)?.profileId
        return transport == TransportType.BLUETOOTH ||
            (profileId != null && (link != PiLinkState.Connected || profiles.activeProfileId != profileId))
    }

    private fun confirmAction() {
        val confirming = action.value.state as? SessionActionState.Confirming ?: return
        run(confirming.action)
    }

    private fun cancelAction() {
        if (action.value.state is SessionActionState.Confirming) action.value = ActionRecord()
    }

    private fun retryAction() {
        val failed = uiState.value.action as? SessionActionState.Failed ?: return
        if (failed.canRetry) run(failed.action)
    }

    private fun dismissAction() {
        val state = action.value.state
        if (state is SessionActionState.Done || state is SessionActionState.Failed) {
            if (piSession.deletionState.value is DeletionState.Failed) piSession.dismissDeletion()
            if (piSession.clearState.value is ClearState.Failed) piSession.dismissClear()
            action.value = ActionRecord()
        }
    }

    private fun run(target: SessionAction) {
        actionJob?.cancel()
        when (target) {
            is SessionAction.DeleteShot -> runDelete(target)
            is SessionAction.ClearSession -> runClear(target)
            SessionAction.ClearAllHistory -> Unit
        }
    }

    /**
     * Without a Pi link the phone's history changes at once. With one, the shot goes only once the
     * Pi confirms: [PiSessionRepository.deletionState] settles it, a drop fails it, and a Pi that
     * never answers fails it after [ACTION_TIMEOUT_MILLIS] (freeing the one-at-a-time slot).
     */
    private fun runDelete(target: SessionAction.DeleteShot) {
        if (!piConnected()) {
            deleteShot(target.id)
            action.value = ActionRecord(SessionActionState.Done(target, SessionActionCopy.done(target)))
            return
        }
        val timestamp = timestampOf(target.id)
        // Start clean, so an earlier outcome can't settle this request.
        piSession.dismissDeletion()
        action.value = ActionRecord(SessionActionState.Pending(target, SessionActionCopy.pending(target)), onPi = true)
        deleteShot(target.id)
        actionJob =
            viewModelScope.launch {
                val timeout =
                    launch {
                        delay(ACTION_TIMEOUT_MILLIS)
                        piSession.dismissDeletion()
                        fail(target, SessionActionCopy.DELETE_NOT_CONFIRMED)
                    }
                val (outcome, _) =
                    combine(piSession.deletionState, piSession.linkState, ::Pair).first { (state, link) ->
                        (state is DeletionState.Deleted && state.timestamp == timestamp) ||
                            (state is DeletionState.Failed && state.timestamp == timestamp) ||
                            // Never sent: the link went down before the request left.
                            (state is DeletionState.Idle && link != PiLinkState.Connected)
                    }
                timeout.cancel()
                when (outcome) {
                    is DeletionState.Deleted -> succeed(target)
                    is DeletionState.Failed -> fail(target, outcome.reason)
                    else -> fail(target, DeletionState.CONNECTION_DROPPED)
                }
            }
    }

    /**
     * A Pi clear waits for `session_cleared`; the repository fails it after
     * [ClearState.TIMEOUT_MILLIS] or on a drop. This ViewModel's own timeout is a little longer and
     * only a safety net for a request that never left.
     */
    private fun runClear(target: SessionAction.ClearSession) {
        val profileId = target.profileId
        if (profileId == null || !piConnected()) {
            shots.clearHistory()
            action.value = ActionRecord(SessionActionState.Done(target, SessionActionCopy.done(target)))
            return
        }
        piSession.dismissClear()
        action.value = ActionRecord(SessionActionState.Pending(target, SessionActionCopy.pending(target)), onPi = true)
        shots.clearHistory()
        actionJob =
            viewModelScope.launch {
                val timeout =
                    launch {
                        delay(ClearState.TIMEOUT_MILLIS + CLEAR_GRACE_MILLIS)
                        piSession.dismissClear()
                        fail(target, ClearState.NO_CONFIRMATION)
                    }
                val (outcome, _) =
                    combine(piSession.clearState, piSession.linkState, ::Pair).first { (state, link) ->
                        (state is ClearState.Cleared && state.profileId == profileId) ||
                            (state is ClearState.Failed && state.profileId == profileId) ||
                            (state is ClearState.Idle && link != PiLinkState.Connected)
                    }
                timeout.cancel()
                when (outcome) {
                    is ClearState.Cleared -> succeed(target)
                    is ClearState.Failed -> fail(target, outcome.reason)
                    else -> fail(target, ClearState.CONNECTION_DROPPED)
                }
            }
    }

    private fun succeed(target: SessionAction) {
        action.update {
            if (it.state is SessionActionState.Pending && it.state.action == target) {
                it.copy(state = SessionActionState.Done(target, SessionActionCopy.done(target)))
            } else {
                it
            }
        }
    }

    private fun fail(
        target: SessionAction,
        reason: String,
    ) {
        action.update {
            if (it.state is SessionActionState.Pending && it.state.action == target) {
                it.copy(
                    state =
                        SessionActionState.Failed(
                            action = target,
                            title = SessionActionCopy.failedTitle(target),
                            message = reason,
                            canRetry = true,
                        ),
                )
            } else {
                it
            }
        }
    }

    /**
     * A failed Pi action can be retried only while the link is up, and a clear only for the profile
     * it was asked for (Expo `stats.tsx`); the message then says why not.
     */
    private fun ActionRecord.forDisplay(pi: PiView): SessionActionState {
        val failed = state as? SessionActionState.Failed
        val clearedProfile = (failed?.action as? SessionAction.ClearSession)?.profileId
        val blocker =
            when {
                failed == null || !onPi -> {
                    null
                }

                !pi.connected -> {
                    SessionActionCopy.RECONNECT_TO_RETRY
                }

                clearedProfile != null && clearedProfile != pi.profiles.activeProfileId -> {
                    SessionActionCopy.PROFILE_CHANGED
                }

                else -> {
                    null
                }
            }
        return if (failed == null || blocker == null) {
            state
        } else {
            failed.copy(message = "${failed.message} $blocker", canRetry = false)
        }
    }

    private fun piConnected(): Boolean = piSession.linkState.value == PiLinkState.Connected

    /** A row id is an event id locally and already a timestamp on the Pi. */
    private fun timestampOf(id: String): String =
        shots.history.value
            .firstOrNull { it.eventId == id }
            ?.timestamp ?: id

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

    // endregion

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
        picked: Selection,
        serverClub: String?,
    ): SessionUiState {
        val chips = computeClubChips(history)
        val selected = picked.tab(chips, serverClub)
        val filtered = if (selected == null) history else history.filter { it.club == selected }
        return SessionUiState(
            source = SessionSource.LOCAL,
            allCount = history.size,
            clubChips = chips,
            selectedClub = selected,
            stats = computeClubStats(filtered),
            shots = localRows(history, details).forTab(selected),
        )
    }

    private fun piState(
        pi: PiView,
        picked: Selection,
        serverClub: String?,
    ): SessionUiState {
        val session = pi.activeSession
        val chips = computeDetailClubChips(session)
        val selected = picked.tab(chips, serverClub)
        val filtered = if (selected == null) session else session.filter { it.club == selected }
        val isSwingSession = filtered.isNotEmpty() && filtered.all { it.isSwingSpeed }
        return SessionUiState(
            source = SessionSource.PI,
            allCount = session.size,
            clubChips = chips,
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

    /**
     * The tab and the selected shot. While [following], the tab is the server's club (when it has
     * shots); a tapped tab stops following until the club or the profile changes.
     */
    private data class Selection(
        val club: String?,
        val shotId: String?,
        val following: Boolean,
    ) {
        /** The tab to show: a club with no shots (left) has no tab, so it falls back to "All". */
        fun tab(
            chips: List<ClubChip>,
            serverClub: String?,
        ): String? {
            val wanted = if (following) serverClub else club
            return wanted?.takeIf { club -> chips.any { it.club == club } }
        }
    }

    private data class Controls(
        val selection: Selection,
        val action: ActionRecord,
        val serverClub: String?,
        val connection: ConnectionState,
    )

    /** [state], and whether its request went to the Pi (so a retry needs the link). */
    private data class ActionRecord(
        val state: SessionActionState = SessionActionState.Idle,
        val onPi: Boolean = false,
    )

    private data class PiFlags(
        val mockMode: Boolean?,
        val triggerStatus: TriggerStatus?,
        val transport: TransportType,
    )

    companion object {
        const val SIMULATE_FAILED = "Couldn't simulate a shot."

        /** How long a Pi delete may go unanswered before it is reported as not confirmed. */
        const val ACTION_TIMEOUT_MILLIS: Long = 10_000
        private const val CLEAR_GRACE_MILLIS = 1_000L
        private const val SWING_SPEED_MODE = "swing-speed"
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun clubName(wire: String): String = GolfClub.fromWireValue(wire)?.displayName ?: wire.ifEmpty { "Unknown" }

/** The selected tab's rows; each keeps its session-wide `#n`. */
private fun List<SessionShotRow>.forTab(club: String?): List<SessionShotRow> =
    if (club == null) this else filter { it.club == club }
