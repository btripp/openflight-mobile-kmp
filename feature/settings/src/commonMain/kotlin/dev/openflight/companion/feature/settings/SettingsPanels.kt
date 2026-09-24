// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.model.pi.DebugState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.SimStatus
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus

/** Pure builders for the settings panels, ported from the web UI's components. */
internal object SettingsPanels {
    const val MOCK_DISABLED: String = "Disabled in mock mode"
    const val TUNING_DISABLED_IN_MOCK: String = "Radar tuning disabled in mock mode"
    const val MOCK_SWING_SLIDERS: String = "Mock swing speed sliders shape simulated reps only"
    const val SWING_HINT: String =
        "Lower speed updates the OPS filter; upper speed rejects implausible high swing-speed outliers."
    const val TX_POWER_HINT: String = "TX Power: 0 = max range, 7 = min range"
    private const val SWING_SPEED_MODE = "swing-speed"
    private const val RECENT_TRIGGERS = 20

    /** `SimStatus.tsx`'s `DISPLAY_NAMES`. */
    private val SIM_NAMES = mapOf("gspro" to "GSPro", "opengolfsim" to "OpenGolfSim")

    /** `DebugPanel.tsx`'s `REASON_DISPLAY`. */
    private val REASONS =
        mapOf(
            "accepted" to "Shot detected",
            "no_response" to "No data from radar after trigger",
            "parse_failed" to "Failed to parse radar data",
            "no_outbound_speed" to "No outbound speed >= 15 mph",
            "processing_failed" to "Failed to process capture data",
            "shot_validation_failed" to "Ball speed too low for shot",
        )

    fun simulators(connectors: Map<String, SimStatus>): List<SimulatorRow> =
        connectors.values.map { status ->
            SimulatorRow(
                target = status.target,
                displayName = SIM_NAMES[status.target] ?: status.target,
                state = status.state,
                severity = severity(status.state),
                detail = simDetail(status),
            )
        }

    private fun severity(state: String): SimSeverity =
        when (state) {
            "connected" -> SimSeverity.OK
            "connecting", "reconnecting" -> SimSeverity.WARN
            "error" -> SimSeverity.ERROR
            else -> SimSeverity.OFF
        }

    /** `SimStatus.tsx`'s `pillTitle`. */
    private fun simDetail(status: SimStatus): String {
        val where = status.host?.let { "$it:${status.port ?: ""}" }.orEmpty()
        val retry = status.nextRetryInS?.takeIf { it != 0.0 }
        return when {
            status.state == "reconnecting" && retry != null -> {
                "$where — retry in ${plain(retry)}s (attempt ${status.attempt ?: ""})".trim()
            }

            status.state == "error" && !status.message.isNullOrEmpty() -> {
                "$where — ${status.message}".trim()
            }

            else -> {
                where.ifEmpty { status.state }
            }
        }
    }

    /** JavaScript's number-to-string for the values the server sends: `5`, not `5.0`. */
    private fun plain(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    fun reasonText(reason: String?): String = reason?.let { REASONS[it] ?: it }.orEmpty()

    /** The last [RECENT_TRIGGERS] diagnostics, newest first. */
    fun diagnostics(debug: DebugState): List<TriggerDiagnosticRow> =
        debug.triggerDiagnostics
            .asReversed()
            .take(RECENT_TRIGGERS)
            .map(::row)

    fun radar(
        config: RadarConfig?,
        trigger: TriggerStatus?,
        debug: DebugState,
        mockMode: Boolean,
        link: PiFeatureAvailability,
    ): RadarPanel {
        val isSwingSpeed = trigger?.mode == SWING_SPEED_MODE
        val tuningDisabled = mockMode && !isSwingSpeed
        return RadarPanel(
            config = config,
            triggerStatus = trigger,
            isSwingSpeedMode = isSwingSpeed,
            sliders = config?.let { sliders(it, isSwingSpeed, tuningDisabled, mockMode, link) }.orEmpty(),
            tuningNotice =
                when {
                    tuningDisabled -> TUNING_DISABLED_IN_MOCK
                    mockMode && isSwingSpeed -> MOCK_SWING_SLIDERS
                    else -> null
                },
            hint = if (isSwingSpeed) SWING_HINT else TX_POWER_HINT,
            diagnostics = diagnostics(debug),
            refresh = link,
        )
    }

    @Suppress("MagicNumber") // The slider ranges are DebugPanel.tsx's, verbatim.
    private fun sliders(
        config: RadarConfig,
        isSwingSpeed: Boolean,
        tuningDisabled: Boolean,
        mockMode: Boolean,
        link: PiFeatureAvailability,
    ): List<RadarSlider> {
        val tuning = gate(link, tuningDisabled)
        val speedSliders =
            if (isSwingSpeed) {
                listOf(
                    RadarSlider(RadarField.MIN_SPEED, "Lower Speed", config.minSpeed, 30, 100, 1, " mph", link),
                    RadarSlider(RadarField.MAX_SPEED, "Upper Speed", config.maxSpeed, 90, 170, 1, " mph", link),
                )
            } else {
                listOf(
                    RadarSlider(RadarField.MIN_SPEED, "Min Speed", config.minSpeed, 0, 50, 1, " mph", tuning),
                    RadarSlider(
                        RadarField.MIN_MAGNITUDE,
                        "Min Magnitude",
                        config.minMagnitude,
                        0,
                        2000,
                        50,
                        "",
                        tuning,
                    ),
                )
            }
        val txPower =
            RadarSlider(RadarField.TRANSMIT_POWER, "TX Power", config.transmitPower, 0, 7, 1, "", gate(link, mockMode))
        return speedSliders + txPower
    }

    private fun gate(
        link: PiFeatureAvailability,
        disabledByMock: Boolean,
    ): PiFeatureAvailability =
        when {
            !link.isAvailable -> link
            disabledByMock -> PiFeatureAvailability.Unavailable(MOCK_DISABLED)
            else -> PiFeatureAvailability.Available
        }

    private fun row(diagnostic: TriggerDiagnostic): TriggerDiagnosticRow =
        TriggerDiagnosticRow(diagnostic.timestamp, diagnostic.accepted, reasonText(diagnostic.reason), diagnostic)
}
