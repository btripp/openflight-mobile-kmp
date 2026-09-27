// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.settings

import dev.openflight.companion.core.data.CalloutTrigger
import dev.openflight.companion.core.data.DEFAULT_SHOT_TRAIL_KEEP_LAST
import dev.openflight.companion.core.data.DemoModeRepository
import dev.openflight.companion.core.data.LandingEffect
import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.SHOT_TRAIL_KEEP_OPTIONS
import dev.openflight.companion.core.data.ShotTrailStyle
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.insights.CalloutField
import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.ConnectionProblem
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.pi.CloudUploadState
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.RadarConfig
import dev.openflight.companion.core.model.pi.TriggerDiagnostic
import dev.openflight.companion.core.model.pi.TriggerStatus
import dev.openflight.companion.core.speech.Voice

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
    // Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).
    val callouts: CalloutSettingsUiState = CalloutSettingsUiState(),
    // Plan F8a2a: the range theme, added at the end to keep this file's diff mergeable (§4a A7).
    val rangeTheme: RangeThemeUiState = RangeThemeUiState(),
    // Plan F8a2t: the shot trail, added at the end to keep this file's diff mergeable (§4a A7).
    val shotTrail: ShotTrailUiState = ShotTrailUiState(),
    // Plan F14: Demo mode, added at the end to keep this file's diff mergeable (§4a A7).
    val demo: DemoSettingsUiState = DemoSettingsUiState(),
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

// Plan F7: audio call-outs, added at the end to keep this file's diff mergeable (§4a A7).

/**
 * The "Audio call-outs" settings section: enable, trigger, voice, rate, and which fields a
 * call-out speaks and in what order.
 *
 * @property voiceGroups every voice the platform reports, grouped by [VoiceGroup.locale] (sorted
 *   locale then quality then name within a group), for a voice picker "grouped by locale".
 * @property selectedVoiceId `null` means the platform default voice.
 * @property fields every [CalloutField], selected ones first in speaking order, then the rest
 *   (plan F7: "field list with checkboxes + drag reorder" — reorder is exposed as move
 *   up/down, which is both simpler and more accessible than a drag gesture with no
 *   keyboard/switch-access equivalent).
 * @property previewText what a call-out with the current [fields] and units would say for a
 *   fixed sample shot (plan F7's "live preview line"); empty when no field is selected.
 *   [SettingsEvent.PreviewCallout] speaks it, unless a screen reader is currently talking (plan
 *   A11y): that check happens where the speech call is made, not in this state, so it can read
 *   the screen reader's status at the moment "Preview" is tapped rather than whenever it was
 *   last recomposed.
 */
data class CalloutSettingsUiState(
    val enabled: Boolean = false,
    val trigger: CalloutTrigger = CalloutTrigger.EVERY_SHOT,
    // Fixed, and listed here (not just `CalloutTrigger.entries`) so SwiftUI's trigger Picker can
    // build its tags from an existing Kotlin instance per option, the same way `RadarSlider.field`
    // does for the radar sliders — never by spelling a multi-word enum case out by hand in Swift.
    val availableTriggers: List<CalloutTrigger> = CalloutTrigger.entries,
    val rate: Float = 1f,
    val voiceGroups: List<VoiceGroup> = emptyList(),
    val selectedVoiceId: String? = null,
    val fields: List<CalloutFieldRow> = emptyList(),
    val previewText: String = "",
)

/** One locale's voices for the picker, e.g. `"en-US"` -> Samantha, Alex, ... */
data class VoiceGroup(
    val locale: String,
    val voices: List<Voice>,
)

/** One row of the call-out field checklist. */
data class CalloutFieldRow(
    val field: CalloutField,
    val label: String,
    val selected: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/**
 * Plan F8a2a: the Practice group's range theme picker: the persisted choice, and every option in
 * order with its label (so SwiftUI builds its picker tags from Kotlin instances, like
 * [CalloutSettingsUiState.availableTriggers]).
 */
data class RangeThemeUiState(
    val selected: RangeThemeSetting = RangeThemeSetting.DEFAULT,
    val options: List<RangeThemeOption> = RangeThemeOption.ALL,
)

/** One range theme the picker offers. */
data class RangeThemeOption(
    val theme: RangeThemeSetting,
    val label: String,
) {
    companion object {
        val ALL: List<RangeThemeOption> =
            listOf(
                RangeThemeOption(RangeThemeSetting.DAY, "Day"),
                RangeThemeOption(RangeThemeSetting.DUSK, "Dusk"),
                RangeThemeOption(RangeThemeSetting.NIGHT, "Night"),
                RangeThemeOption(RangeThemeSetting.LINKS, "Links"),
            )
    }
}

/**
 * Plan F8a2t: the Practice group's "Shot trail" picker: the persisted style, "Keep last shots" and
 * landing effect, and every option in order with its label (so SwiftUI builds its pickers from
 * Kotlin instances, like [RangeThemeUiState]).
 */
data class ShotTrailUiState(
    val selected: ShotTrailStyle = ShotTrailStyle.DEFAULT,
    val keepLast: Int = DEFAULT_SHOT_TRAIL_KEEP_LAST,
    val landingEffect: LandingEffect = LandingEffect.DEFAULT,
    val styles: List<ShotTrailOption> = ShotTrailOption.ALL,
    val keepOptions: List<ShotTrailKeepOption> = ShotTrailKeepOption.ALL,
    val landingEffects: List<LandingEffectOption> = LandingEffectOption.ALL,
) {
    val selectedLabel: String get() = styles.firstOrNull { it.style == selected }?.label.orEmpty()
    val keepLastLabel: String get() = keepOptions.firstOrNull { it.count == keepLast }?.label.orEmpty()
    val landingEffectLabel: String get() = landingEffects.firstOrNull { it.effect == landingEffect }?.label.orEmpty()
}

/** One shot trail style the picker offers. */
data class ShotTrailOption(
    val style: ShotTrailStyle,
    val label: String,
) {
    companion object {
        val ALL: List<ShotTrailOption> =
            listOf(
                ShotTrailOption(ShotTrailStyle.CLASSIC, "Classic"),
                ShotTrailOption(ShotTrailStyle.BROADCAST_GLOW, "Broadcast glow"),
                ShotTrailOption(ShotTrailStyle.COMET, "Comet"),
                ShotTrailOption(ShotTrailStyle.CLUB_COLOUR, "Club colour"),
                ShotTrailOption(ShotTrailStyle.DOTTED, "Dotted"),
                ShotTrailOption(ShotTrailStyle.SMOKE, "Smoke"),
                ShotTrailOption(ShotTrailStyle.NEON, "Neon"),
                ShotTrailOption(ShotTrailStyle.SPEED_HEAT, "Speed heat"),
                ShotTrailOption(ShotTrailStyle.RAINBOW, "Rainbow"),
                ShotTrailOption(ShotTrailStyle.SPIN_RIBBON, "Spin ribbon"),
                ShotTrailOption(ShotTrailStyle.GROUND_TRACK, "Ground track"),
            )
    }
}

/** One "Keep last shots" choice. */
data class ShotTrailKeepOption(
    val count: Int,
    val label: String,
) {
    companion object {
        val ALL: List<ShotTrailKeepOption> =
            SHOT_TRAIL_KEEP_OPTIONS.map { count ->
                ShotTrailKeepOption(
                    count,
                    if (count ==
                        0
                    ) {
                        "Off"
                    } else {
                        "Last " + count
                    },
                )
            }
    }
}

/** One landing effect the picker offers. */
data class LandingEffectOption(
    val effect: LandingEffect,
    val label: String,
) {
    companion object {
        val ALL: List<LandingEffectOption> =
            listOf(
                LandingEffectOption(LandingEffect.OFF, "Off"),
                LandingEffectOption(LandingEffect.RING, "Ring"),
                LandingEffectOption(LandingEffect.BURST, "Burst"),
            )
    }
}

/**
 * Plan F14: Settings › Device › Demo mode: the switch, the automatic-shot interval and "Clear demo
 * data" (confirmed first, like every destructive action).
 *
 * @property confirmingClear the "Clear demo data?" confirmation is showing.
 */
data class DemoSettingsUiState(
    val enabled: Boolean = false,
    val autoFireSeconds: Int = DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS,
    val autoFireOptions: List<Int> = DemoModeRepository.AUTO_FIRE_OPTIONS,
    val confirmingClear: Boolean = false,
) {
    companion object {
        const val TITLE = "Demo mode"
        const val SUMMARY =
            "Try every screen with a pretend Pi and made-up shots. No hardware needed. " +
                "Demo sessions are kept apart from your own."
        const val AUTO_FIRE_LABEL = "Automatic shots"
        const val CLEAR_LABEL = "Clear demo data"
        const val CLEAR_CONFIRM_TITLE = "Clear demo data?"
        const val CLEAR_CONFIRM_MESSAGE =
            "This deletes every demo session and shot. Your own sessions stay. The sample sessions " +
                "come back the next time you turn Demo mode on."
        const val CLEAR_CONFIRM_ACTION = "Clear"
        const val CLEARED_MESSAGE = "Demo data cleared."

        /** "Off" or "Every 10 s". */
        fun autoFireLabel(seconds: Int): String = if (seconds <= 0) "Off" else "Every $seconds s"
    }
}
