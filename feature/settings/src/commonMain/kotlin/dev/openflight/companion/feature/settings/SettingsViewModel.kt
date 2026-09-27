// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.AppLifecycle
import dev.openflight.companion.core.data.AppLifecycleState
import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.CalloutComposer
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.CalloutInput
import dev.openflight.companion.core.insights.CalloutVerbosity
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.PowerStatus
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SimState
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.speech.ScreenReaderMonitor
import dev.openflight.companion.core.speech.SpeechEngine
import dev.openflight.companion.core.speech.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The settings screen's state holder (plan R6b): units, the transport and link status, and the
 * Pi's Wi-Fi-only controls (simulators, radar/debug, cloud upload, shutdown), each
 * disabled with a reason unless the Pi's Socket.IO link is connected.
 *
 * Plan R8f adds the device cards (power, launch monitor, debug hidden until loaded), the
 * connection problem in words, and the shutdown phase machine ([ShutdownPhase]): the target is
 * captured at confirm, [ShutdownPhase.TIMEOUT_MILLIS] bounds the request, a retry reuses the
 * target, a 200 followed by the link dropping is success, an outcome is dropped once a new
 * connection comes up, and a pending stop fails when [lifecycle] goes to the background.
 */
@Suppress("TooManyFunctions") // One handler per settings action plus the shutdown machine.
class SettingsViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val piSession: PiSessionRepository,
    private val lifecycle: AppLifecycle,
    // Plan F7: audio call-outs, added at the end to keep this constructor's diff mergeable (§4a A7).
    private val speechEngine: SpeechEngine,
    private val screenReader: ScreenReaderMonitor,
) : ViewModel() {
    private val shutdownPhase = MutableStateFlow<ShutdownPhase>(ShutdownPhase.Idle)
    private var shutdownJob: Job? = null

    private val phone =
        combine(settings.transport, settings.host, settings.units, shots.connectionState, ::PhoneState)

    private val device =
        combine(
            piSession.profiles,
            piSession.simState,
            piSession.cloudUploadStatus,
            piSession.mockMode,
            ::DeviceState,
        )

    private val radar =
        combine(
            piSession.radarConfig,
            piSession.triggerStatus,
            piSession.debugState,
            piSession.powerStatus,
            ::RadarState,
        )

    // Plan F7: audio call-outs. A 6th combine source doesn't fit the 5-argument `combine` overload,
    // so the callout settings combine on their own first, then join the rest through a 2-way outer
    // combine below (keeps every existing combine untouched, per §4a A7's mergeable-diff intent).
    private val calloutPrefs =
        combine(
            settings.calloutsEnabled,
            settings.calloutTrigger,
            settings.calloutVoiceId,
            settings.calloutRate,
            settings.calloutFields,
            ::CalloutPrefs,
        )

    private val callout = combine(calloutPrefs, speechEngine.voices, ::CalloutState)

    private val baseState =
        combine(
            phone,
            piSession.linkState,
            device,
            radar,
            shutdownPhase,
            ::BaseState,
        )

    // Plan F8a2t: the shot trail picker.
    private val shotTrail =
        combine(settings.shotTrail, settings.shotTrailKeepLast, settings.landingEffect) { style, keepLast, effect ->
            ShotTrailUiState(selected = style, keepLast = keepLast, landingEffect = effect)
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(baseState, callout, settings.rangeTheme, shotTrail) { base, calloutState, theme, trail ->
            buildState(base.phone, base.link, base.device, base.radar, base.phase, calloutState)
                .copy(rangeTheme = RangeThemeUiState(selected = theme), shotTrail = trail)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue =
                buildState(
                    PhoneState(
                        SettingsRepository.DEFAULT_TRANSPORT,
                        SettingsRepository.DEFAULT_HOST,
                        SettingsRepository.DEFAULT_UNITS,
                        ConnectionState.Idle,
                    ),
                    PiLinkState.Idle,
                    DeviceState(ProfilesState(), SimState(), CloudUploadStatus(), null),
                    RadarState(null, null, DebugState(), null),
                    ShutdownPhase.Idle,
                    CalloutState(
                        CalloutPrefs(
                            SettingsRepository.DEFAULT_CALLOUTS_ENABLED,
                            SettingsRepository.DEFAULT_CALLOUT_TRIGGER,
                            null,
                            SettingsRepository.DEFAULT_CALLOUT_RATE,
                            SettingsRepository.DEFAULT_CALLOUT_FIELDS,
                        ),
                        emptyList(),
                    ),
                ),
        )

    private val settingsEffects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = settingsEffects.receiveAsFlow()

    init {
        viewModelScope.launch {
            piSession.notices.collect { notice ->
                noticeText(notice)?.let { settingsEffects.send(SettingsEffect.Message(it)) }
            }
        }
        viewModelScope.launch { followConnectionForShutdown() }
        viewModelScope.launch {
            // Only changes count: the shell's first report isn't a trip to the background.
            lifecycle.state.drop(1).collect { state ->
                if (state == AppLifecycleState.BACKGROUND) onBackgroundForShutdown()
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetUnits -> {
                viewModelScope.launch { settings.setUnits(event.units) }
            }

            is SettingsEvent.SetRadarValue -> {
                send { piSession.setRadarConfig(event.toUpdate()) }
            }

            SettingsEvent.RefreshRadarConfig -> {
                send { piSession.refreshRadarConfig() }
            }

            SettingsEvent.ToggleDebug -> {
                send { piSession.toggleDebug() }
            }

            SettingsEvent.UploadCloud -> {
                send { piSession.uploadCloud() }
            }

            SettingsEvent.RequestShutdown,
            SettingsEvent.ConfirmShutdown,
            SettingsEvent.CancelShutdown,
            SettingsEvent.RetryShutdown,
            SettingsEvent.DismissShutdown,
            -> {
                onShutdownEvent(event)
            }

            is SettingsEvent.SetCalloutsEnabled,
            is SettingsEvent.SetCalloutTrigger,
            is SettingsEvent.SetCalloutVoice,
            is SettingsEvent.SetCalloutRate,
            is SettingsEvent.ToggleCalloutField,
            is SettingsEvent.MoveCalloutField,
            SettingsEvent.PreviewCallout,
            -> {
                onCalloutEvent(event)
            }

            is SettingsEvent.SetRangeTheme -> {
                viewModelScope.launch { settings.setRangeTheme(event.theme) }
            }

            is SettingsEvent.SetShotTrail -> {
                viewModelScope.launch { settings.setShotTrail(event.style) }
            }

            is SettingsEvent.SetShotTrailKeepLast -> {
                viewModelScope.launch { settings.setShotTrailKeepLast(event.count) }
            }

            is SettingsEvent.SetLandingEffect -> {
                viewModelScope.launch { settings.setLandingEffect(event.effect) }
            }
        }
    }

    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).
    private fun onCalloutEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetCalloutsEnabled -> {
                viewModelScope.launch { settings.setCalloutsEnabled(event.enabled) }
            }

            is SettingsEvent.SetCalloutTrigger -> {
                viewModelScope.launch { settings.setCalloutTrigger(event.trigger) }
            }

            is SettingsEvent.SetCalloutVoice -> {
                viewModelScope.launch { settings.setCalloutVoiceId(event.voiceId) }
            }

            is SettingsEvent.SetCalloutRate -> {
                viewModelScope.launch { settings.setCalloutRate(event.rate) }
            }

            is SettingsEvent.ToggleCalloutField -> {
                viewModelScope.launch {
                    val current = settings.calloutFields.first()
                    val updated = if (event.field in current) current - event.field else current + event.field
                    settings.setCalloutFields(updated)
                }
            }

            is SettingsEvent.MoveCalloutField -> {
                viewModelScope.launch {
                    val current = settings.calloutFields.first()
                    val index = current.indexOf(event.field)
                    val target = if (event.up) index - 1 else index + 1
                    if (index < 0 || target !in current.indices) return@launch
                    settings.setCalloutFields(
                        current.toMutableList().apply {
                            val moved = removeAt(index)
                            add(target, moved)
                        },
                    )
                }
            }

            SettingsEvent.PreviewCallout -> {
                previewCallout()
            }

            else -> {
                // Every other SettingsEvent is dispatched by onEvent's own when, never reaches here.
            }
        }
    }

    /**
     * Speaks [CalloutSettingsUiState.previewText] as-is (what's on screen is what's heard), unless
     * a screen reader is talking (plan A11y).
     */
    private fun previewCallout() {
        if (screenReader.isActive.value) return
        val text = uiState.value.callouts.previewText
        if (text.isEmpty()) return
        viewModelScope.launch {
            val prefs = calloutPrefs.first()
            speechEngine.speak(text = text, voiceId = prefs.voiceId, rate = prefs.rate)
        }
    }

    private fun onShutdownEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.RequestShutdown -> {
                requestShutdown()
            }

            SettingsEvent.ConfirmShutdown -> {
                confirmShutdown()
            }

            SettingsEvent.CancelShutdown -> {
                if (shutdownPhase.value == ShutdownPhase.Confirming) shutdownPhase.value = ShutdownPhase.Idle
            }

            SettingsEvent.RetryShutdown -> {
                (shutdownPhase.value as? ShutdownPhase.Failed)?.let { sendShutdown(it.target) }
            }

            SettingsEvent.DismissShutdown -> {
                val phase = shutdownPhase.value
                if (phase is ShutdownPhase.Done || phase is ShutdownPhase.Failed) {
                    shutdownPhase.value = ShutdownPhase.Idle
                }
            }

            else -> {
                // Nothing to change in the other phases.
            }
        }
    }

    /** Asks for confirmation only when the shutdown could run; otherwise says why it can't. */
    private fun requestShutdown() {
        if (shutdownPhase.value is ShutdownPhase.Pending) return
        val reason = PiFeatureAvailability.of(piSession.linkState.value).disabledReason
        if (reason != null) {
            viewModelScope.launch { settingsEffects.send(SettingsEffect.Message(reason)) }
        } else {
            shutdownPhase.value = ShutdownPhase.Confirming
        }
    }

    /** Captures the address of the Pi the user confirmed stopping, then sends to it. */
    private fun confirmShutdown() {
        if (shutdownPhase.value != ShutdownPhase.Confirming) return
        viewModelScope.launch {
            val target = settings.host.first().trim()
            if (shutdownPhase.value != ShutdownPhase.Confirming) return@launch
            if (target.isEmpty()) {
                shutdownPhase.value = ShutdownPhase.Failed(target, ShutdownPhase.NO_TARGET)
            } else {
                sendShutdown(target)
            }
        }
    }

    // Anything but a 200 means the server is still running; a timeout is reported as its own reason.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun sendShutdown(target: String) {
        shutdownJob?.cancel()
        val pending = ShutdownPhase.Pending(target)
        shutdownPhase.value = pending
        shutdownJob =
            viewModelScope.launch {
                val outcome =
                    try {
                        withTimeout(ShutdownPhase.TIMEOUT_MILLIS) { shots.shutdownPi(target) }
                        ShutdownPhase.Done(target)
                    } catch (timeout: TimeoutCancellationException) {
                        ShutdownPhase.Failed(target, ShutdownPhase.TIMED_OUT)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        ShutdownPhase.Failed(target, failure.message ?: ShutdownPhase.STILL_RUNNING)
                    }
                // A background that already failed this request wins over a late answer.
                if (shutdownPhase.value == pending) shutdownPhase.value = outcome
            }
    }

    /**
     * Keyed per connection (Expo `device.tsx`): nothing sent yet collapses when the link drops, and
     * an outcome is dropped once a new connection comes up, since it described the previous one.
     * A pending request, and the drop a successful stop causes, are left alone.
     */
    private suspend fun followConnectionForShutdown() {
        var wasConnected = piSession.linkState.value == PiLinkState.Connected
        piSession.linkState.collect { link ->
            val connected = link == PiLinkState.Connected
            val phase = shutdownPhase.value
            val outcome = phase is ShutdownPhase.Done || phase is ShutdownPhase.Failed
            when {
                !connected && phase == ShutdownPhase.Confirming -> shutdownPhase.value = ShutdownPhase.Idle
                connected && !wasConnected && outcome -> shutdownPhase.value = ShutdownPhase.Idle
            }
            wasConnected = connected
        }
    }

    /** Plan R8d: transports stop in the background, so a pending stop can't be confirmed any more. */
    private fun onBackgroundForShutdown() {
        when (val phase = shutdownPhase.value) {
            is ShutdownPhase.Pending -> {
                shutdownJob?.cancel()
                shutdownPhase.value = ShutdownPhase.Failed(phase.target, ShutdownPhase.CONNECTION_DROPPED)
            }

            ShutdownPhase.Confirming -> {
                shutdownPhase.value = ShutdownPhase.Idle
            }

            else -> {
                // Nothing to change in the other phases.
            }
        }
    }

    /** Runs a Pi command; a failure (e.g. "Not connected ...") becomes a [SettingsEffect.Message]. */
    @Suppress("TooGenericExceptionCaught") // Every command failure is shown the same way.
    private fun send(command: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                settingsEffects.send(SettingsEffect.Message(failure.message ?: COMMAND_FAILED))
            }
        }
    }

    private data class PhoneState(
        val transport: TransportType,
        val host: String,
        val units: UnitSystem,
        val connection: ConnectionState,
    )

    private data class DeviceState(
        val profiles: ProfilesState,
        val sim: SimState,
        val cloud: CloudUploadStatus,
        val mockMode: Boolean?,
    )

    private data class RadarState(
        val config: RadarConfig?,
        val trigger: TriggerStatus?,
        val debug: DebugState,
        val power: PowerStatus?,
    )

    private data class BaseState(
        val phone: PhoneState,
        val link: PiLinkState,
        val device: DeviceState,
        val radar: RadarState,
        val phase: ShutdownPhase,
    )

    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

    private data class CalloutPrefs(
        val enabled: Boolean,
        val trigger: CalloutTrigger,
        val voiceId: String?,
        val rate: Float,
        val fields: List<CalloutField>,
    )

    private data class CalloutState(
        val prefs: CalloutPrefs,
        val voices: List<Voice>,
    )

    companion object {
        const val COMMAND_FAILED = "The Pi didn't accept that."
        const val UPLOADING = "Uploading"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        // Plan F7 tipped this over the LongParameterList threshold; each param is one combine
        // source (see BaseState/CalloutState above), not a bundle a caller would misorder.
        @Suppress("LongParameterList")
        private fun buildState(
            phone: PhoneState,
            link: PiLinkState,
            device: DeviceState,
            radar: RadarState,
            phase: ShutdownPhase,
            callout: CalloutState,
        ): SettingsUiState {
            val available = PiFeatureAvailability.of(link)
            val mock = device.mockMode == true
            return SettingsUiState(
                transport = phone.transport,
                host = phone.host,
                connectionState = phone.connection,
                linkState = link,
                linkDescription = link.description,
                units = phone.units,
                link = available,
                simulators = SettingsPanels.simulators(device.sim.connectors),
                radar = SettingsPanels.radar(radar.config, radar.trigger, radar.debug, mock, available),
                debug =
                    DebugSettings(
                        enabled = radar.debug.enabled,
                        loaded = radar.debug.loaded,
                        logPath = radar.debug.logPath,
                        readingCount = radar.debug.readings.size,
                        shotLogCount = radar.debug.shotLogs.size,
                        toggle = available,
                    ),
                cloud =
                    CloudSettings(
                        state = device.cloud.state,
                        message = device.cloud.message,
                        upload =
                            if (available.isAvailable && device.cloud.state == CloudUploadState.RUNNING) {
                                PiFeatureAvailability.Unavailable(UPLOADING)
                            } else {
                                available
                            },
                    ),
                shutdown =
                    ShutdownSettings(
                        // A confirmation only makes sense while the Pi is reachable.
                        phase =
                            if (phase == ShutdownPhase.Confirming && !available.isAvailable) {
                                ShutdownPhase.Idle
                            } else {
                                phase
                            },
                        shutdown = available,
                    ),
                mockMode = mock,
                connectionProblem = ConnectionProblem.of(phone.connection, link),
                power = DevicePanels.power(radar.power),
                trigger = DevicePanels.trigger(radar.trigger, available),
                callouts = buildCalloutState(callout, phone.units),
            )
        }

        private fun noticeText(notice: PiNotice): String? =
            when (notice) {
                is PiNotice.RadarConfigFailed, is PiNotice.SimShotDropped, is PiNotice.ShuttingDown -> notice.message

                is PiNotice.SimSendFailed -> "${notice.target}: ${notice.message}"

                // Shown by the training and camera screens.
                is PiNotice.TrainingImplementFailed, is PiNotice.CameraSettingsFailed -> null
            }

        // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

        private fun buildCalloutState(
            callout: CalloutState,
            units: UnitSystem,
        ): CalloutSettingsUiState {
            val prefs = callout.prefs
            // DETAILED (labelled), unlike the coordinator's real call-outs (F7's own CONCISE
            // default): the on-screen preview is meant to be read, so it spells out which number
            // is which; the "Preview" button speaks this same, already-labelled text verbatim.
            val previewText =
                CalloutComposer.compose(SAMPLE_CALLOUT_INPUT, prefs.fields, units, CalloutVerbosity.DETAILED)
            return CalloutSettingsUiState(
                enabled = prefs.enabled,
                trigger = prefs.trigger,
                rate = prefs.rate,
                voiceGroups = groupVoices(callout.voices),
                selectedVoiceId = prefs.voiceId,
                fields = fieldRows(prefs.fields),
                previewText = previewText,
            )
        }

        /**
         * Grouped by locale, each group sorted by quality (best first) then name.
         * `Map.toSortedMap()` is JVM-only (backed by `java.util.TreeMap`), so this sorts the
         * grouped entries by key itself to stay `commonMain`-safe (iOS builds too).
         */
        private fun groupVoices(voices: List<Voice>): List<VoiceGroup> {
            val byQualityThenName = compareByDescending<Voice> { it.quality.ordinal }.thenBy { it.displayName }
            return voices
                .groupBy { it.locale }
                .toList()
                .sortedBy { (locale, _) -> locale }
                .map { (locale, group) -> VoiceGroup(locale = locale, voices = group.sortedWith(byQualityThenName)) }
        }

        /** Every [CalloutField], selected ones first in speaking order, then the rest. */
        private fun fieldRows(selected: List<CalloutField>): List<CalloutFieldRow> {
            val rest = CalloutField.entries.filterNot { it in selected }
            return (selected + rest).map { field ->
                val index = selected.indexOf(field)
                CalloutFieldRow(
                    field = field,
                    label = CalloutComposer.label(field),
                    selected = index >= 0,
                    canMoveUp = index > 0,
                    canMoveDown = index in 0 until selected.lastIndex,
                )
            }
        }

        /**
         * A fixed sample shot for [CalloutSettingsUiState.previewText] (plan F7's "live preview
         * line"): representative numbers, not a real shot, so the preview never depends on one
         * having arrived.
         */
        private val SAMPLE_CALLOUT_INPUT =
            CalloutInput(
                carryYards = SAMPLE_CARRY_YARDS,
                totalYards = SAMPLE_TOTAL_YARDS,
                ballSpeedMph = SAMPLE_BALL_SPEED_MPH,
                clubSpeedMph = SAMPLE_CLUB_SPEED_MPH,
                smash = SAMPLE_SMASH,
                launchAngleDegrees = SAMPLE_LAUNCH_DEGREES,
                spinRpm = SAMPLE_SPIN_RPM,
                club = GolfClub.DRIVER,
                targetDeltaYards = SAMPLE_TARGET_DELTA_YARDS,
            )

        private const val SAMPLE_CARRY_YARDS = 152.0
        private const val SAMPLE_TOTAL_YARDS = 165.0
        private const val SAMPLE_BALL_SPEED_MPH = 118.0
        private const val SAMPLE_CLUB_SPEED_MPH = 98.0
        private const val SAMPLE_SMASH = 1.48
        private const val SAMPLE_LAUNCH_DEGREES = 13.5
        private const val SAMPLE_SPIN_RPM = 2_600.0
        private const val SAMPLE_TARGET_DELTA_YARDS = -12.0
    }
}

private fun SettingsEvent.SetRadarValue.toUpdate(): RadarConfigUpdate =
    when (field) {
        RadarField.MIN_SPEED -> RadarConfigUpdate(minSpeed = value)
        RadarField.MAX_SPEED -> RadarConfigUpdate(maxSpeed = value)
        RadarField.MIN_MAGNITUDE -> RadarConfigUpdate(minMagnitude = value)
        RadarField.TRANSMIT_POWER -> RadarConfigUpdate(transmitPower = value)
    }
