// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.openflight.companion.core.data.PiSessionRepository
import dev.openflight.companion.core.data.SettingsRepository
import dev.openflight.companion.core.data.ShotRepository
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.CloudUploadStatus
import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.PiNotice
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.RadarConfigUpdate
import dev.openflight.companion.core.model.pi.SimState
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
import kotlinx.coroutines.launch

/**
 * The settings screen's state holder (plan R6b): units, the transport and link status, and the
 * Pi's Wi-Fi-only controls (player, simulators, radar/debug, cloud upload, shutdown), each
 * disabled with a reason unless the Pi's Socket.IO link is connected.
 */
class SettingsViewModel(
    private val shots: ShotRepository,
    private val settings: SettingsRepository,
    private val piSession: PiSessionRepository,
) : ViewModel() {
    private val confirmingShutdown = MutableStateFlow(false)

    private val phone =
        combine(settings.transport, settings.host, settings.units, shots.connectionState, ::PhoneState)

    private val device =
        combine(
            piSession.playerName,
            piSession.simState,
            piSession.cloudUploadStatus,
            piSession.mockMode,
            ::DeviceState,
        )

    private val radar = combine(piSession.radarConfig, piSession.triggerStatus, piSession.debugState, ::RadarState)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            phone,
            piSession.linkState,
            device,
            radar,
            confirmingShutdown,
        ) { phone, link, device, radar, confirming ->
            buildState(phone, link, device, radar, confirming)
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
                    DeviceState(null, SimState(), CloudUploadStatus(), null),
                    RadarState(null, null, DebugState()),
                    confirming = false,
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
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetUnits -> {
                viewModelScope.launch { settings.setUnits(event.units) }
            }

            is SettingsEvent.SetPlayer -> {
                send {
                    piSession.setPlayer(
                        event.name.trim().take(PlayerSettings.MAX_NAME_LENGTH),
                    )
                }
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

            SettingsEvent.RequestShutdown -> {
                requestShutdown()
            }

            SettingsEvent.ConfirmShutdown -> {
                confirmShutdown()
            }

            SettingsEvent.CancelShutdown -> {
                confirmingShutdown.value = false
            }
        }
    }

    /** Asks for confirmation only when the shutdown could run; otherwise says why it can't. */
    private fun requestShutdown() {
        val reason = PiFeatureAvailability.of(piSession.linkState.value).disabledReason
        if (reason != null) {
            viewModelScope.launch { settingsEffects.send(SettingsEffect.Message(reason)) }
        } else {
            confirmingShutdown.value = true
        }
    }

    private fun confirmShutdown() {
        if (!confirmingShutdown.value) return
        confirmingShutdown.value = false
        send { piSession.shutdown() }
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
        val playerName: String?,
        val sim: SimState,
        val cloud: CloudUploadStatus,
        val mockMode: Boolean?,
    )

    private data class RadarState(
        val config: RadarConfig?,
        val trigger: TriggerStatus?,
        val debug: DebugState,
    )

    companion object {
        const val COMMAND_FAILED = "The Pi didn't accept that."
        const val UPLOADING = "Uploading"
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        private fun buildState(
            phone: PhoneState,
            link: PiLinkState,
            device: DeviceState,
            radar: RadarState,
            confirming: Boolean,
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
                player = PlayerSettings(device.playerName, available),
                simulators = SettingsPanels.simulators(device.sim.connectors),
                radar = SettingsPanels.radar(radar.config, radar.trigger, radar.debug, mock, available),
                debug =
                    DebugSettings(
                        enabled = radar.debug.enabled,
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
                        confirmationRequired = confirming && available.isAvailable,
                        shutdown = available,
                    ),
                mockMode = mock,
            )
        }

        private fun noticeText(notice: PiNotice): String? =
            when (notice) {
                is PiNotice.RadarConfigFailed, is PiNotice.SimShotDropped, is PiNotice.ShuttingDown -> notice.message

                is PiNotice.SimSendFailed -> "${notice.target}: ${notice.message}"

                // Shown by the session and training screens.
                is PiNotice.DeleteShotFailed, is PiNotice.TrainingImplementFailed -> null
            }
    }
}

private fun SettingsEvent.SetRadarValue.toUpdate(): RadarConfigUpdate =
    when (field) {
        RadarField.MIN_SPEED -> RadarConfigUpdate(minSpeed = value)
        RadarField.MAX_SPEED -> RadarConfigUpdate(maxSpeed = value)
        RadarField.MIN_MAGNITUDE -> RadarConfigUpdate(minMagnitude = value)
        RadarField.TRANSMIT_POWER -> RadarConfigUpdate(transmitPower = value)
    }
