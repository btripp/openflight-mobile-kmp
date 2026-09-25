// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus

/**
 * The settings screen (plan R6b): the phone-side preferences plus everything the web UI keeps in
 * its header and Debug tab (`App.tsx`, `SimStatus.tsx`, `DebugPanel.tsx`,
 * the cloud upload in `ShotList.tsx` and the shutdown dialog). Every Wi-Fi-only action carries a
 * [PiFeatureAvailability] with the reason it's disabled.
 *
 * @property connectionState the shot stream's state (SSE or BLE).
 * @property linkState the Wi-Fi-only Socket.IO link; [linkDescription] is its display text.
 * @property link whether the Pi's Wi-Fi-only controls can run now, and why not. The active
 *   profile is picked on the dashboard (plan R8f).
 * @property mockMode the Pi runs `--mock` (from the on-connect `session_state`).
 * @property connectionProblem why the phone can't reach the Pi, in words (plan R8f), or `null`.
 * @property power the Pi's power card, or `null` until a `power_status` arrived (plan R8f).
 * @property trigger the launch monitor card (plan R8f).
 */
data class SettingsUiState(
    val transport: TransportType,
    val host: String,
    val connectionState: ConnectionState,
    val linkState: PiLinkState,
    val linkDescription: String,
    val units: UnitSystem,
    val link: PiFeatureAvailability,
    val simulators: List<SimulatorRow>,
    val radar: RadarPanel,
    val debug: DebugSettings,
    val cloud: CloudSettings,
    val shutdown: ShutdownSettings,
    val mockMode: Boolean,
    val connectionProblem: ConnectionProblem? = null,
    val power: PowerCard? = null,
    val trigger: TriggerCard = TriggerCard.Waiting,
)

/** A simulator connector's severity bucket (`SimStatus.tsx`'s `severity`). */
enum class SimSeverity {
    OK,
    WARN,
    ERROR,
    OFF,
}

/**
 * One simulator connector pill (`SimStatus.tsx`).
 *
 * @property displayName "GSPro", "OpenGolfSim", or the raw target.
 * @property state the server's state (`connected`, `connecting`, `reconnecting`, `disabled`,
 *   `stopped` or `error`).
 * @property detail the pill's tooltip text: `host:port`, plus the retry or error detail.
 */
data class SimulatorRow(
    val target: String,
    val displayName: String,
    val state: String,
    val severity: SimSeverity,
    val detail: String,
)

/** Which radar setting a [RadarSlider] edits (`set_radar_config`'s keys). */
enum class RadarField {
    MIN_SPEED,
    MAX_SPEED,
    MIN_MAGNITUDE,
    TRANSMIT_POWER,
}

/**
 * One radar tuning slider (`DebugPanel.tsx`'s `SliderControl`). [unit] is appended to the value
 * as-is (e.g. " mph").
 */
data class RadarSlider(
    val field: RadarField,
    val label: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val unit: String,
    val availability: PiFeatureAvailability,
)

/**
 * The radar/debug panel's status and tuning tabs (`DebugPanel.tsx`).
 *
 * @property config the Pi's radar config, or `null` until it reports one (then [sliders] is empty).
 * @property sliders swing-speed mode tunes the lower/upper speed; other modes tune the minimum
 *   speed and magnitude. TX power is always last.
 * @property tuningNotice "Radar tuning disabled in mock mode" and similar, or `null`.
 * @property hint the text under the sliders.
 * @property diagnostics the last 20 trigger diagnostics, newest first.
 * @property refresh whether [SettingsEvent.RefreshRadarConfig] can run.
 */
data class RadarPanel(
    val config: RadarConfig?,
    val triggerStatus: TriggerStatus?,
    val isSwingSpeedMode: Boolean,
    val sliders: List<RadarSlider>,
    val tuningNotice: String?,
    val hint: String,
    val diagnostics: List<TriggerDiagnosticRow>,
    val refresh: PiFeatureAvailability,
)

/**
 * One trigger in the history (`DebugPanel.tsx`'s `TriggerRow`).
 *
 * @property reasonText the web UI's text for the reason code, e.g. "Shot detected".
 */
data class TriggerDiagnosticRow(
    val timestamp: String?,
    val accepted: Boolean,
    val reasonText: String,
    val diagnostic: TriggerDiagnostic,
)

/**
 * @property loaded the Pi reported its debug mode. Debug mode is server-global, so until then the
 *   card stays hidden rather than offer a "Start" that could stop a running capture (plan R8f).
 * @property logPath the server-side JSONL log file while debug mode is on.
 * @property toggle whether [SettingsEvent.ToggleDebug] can run.
 */
data class DebugSettings(
    val enabled: Boolean,
    val loaded: Boolean,
    val logPath: String?,
    val readingCount: Int,
    val shotLogCount: Int,
    val toggle: PiFeatureAvailability,
)

/**
 * The manual FlightWeb cloud upload (`ShotList.tsx`'s "Upload Cloud").
 *
 * @property upload disabled while the link is down, or while an upload runs ("Uploading").
 */
data class CloudSettings(
    val state: CloudUploadState,
    val message: String?,
    val upload: PiFeatureAvailability,
)

/**
 * Stopping OpenFlight (plan R8f): the power card follows [phase]; [ShutdownPhase.Confirming]
 * shows the confirmation.
 *
 * @property shutdown whether [SettingsEvent.RequestShutdown] can run (the live link).
 */
data class ShutdownSettings(
    val phase: ShutdownPhase,
    val shutdown: PiFeatureAvailability,
) {
    /** Show the confirmation; [SettingsEvent.ConfirmShutdown] sends, [SettingsEvent.CancelShutdown] dismisses. */
    val confirmationRequired: Boolean get() = phase == ShutdownPhase.Confirming

    companion object {
        const val CONFIRMATION_TEXT: String = ShutdownPhase.CONFIRM_TITLE
    }
}
