// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.RangeThemeSetting
import dev.openflight.companion.core.data.TransportType

/**
 * Debug-only launch hooks, the equivalent of the reference's `--ui-testing`, `--preview-shot`,
 * `--range-mode` and `--preview-flight` process arguments, plus two settings seeds for scripted
 * end-to-end runs.
 *
 * - iOS reads them from `NSProcessInfo.arguments`: `--ui-testing`, `--preview-shot`,
 *   `--preview-pi`, `--range-mode`, `--preview-flight`, `--transport wifi|bluetooth`,
 *   `--host <host[:port]>`, `--preview-history`, `--preview-history-stuck`, `--preview-pi-session`,
 *   `--preview-pi-session-stuck`.
 * - Android reads intent extras: `--ez ui_testing true`, `--ez preview_shot true`,
 *   `--ez preview_pi true`,
 *   `--ez range_mode true`, `--ez preview_flight true`, `--es transport wifi`,
 *   `--es host 10.0.2.2:8091`, `--ez preview_history true`, `--ez preview_history_stuck true`,
 *   `--ez preview_pi_session true`, `--ez preview_pi_session_stuck true`.
 *
 * @property uiTesting swap in [PreviewShotRepository] so no transport ever starts.
 * @property previewShot like [uiTesting], and the fake history holds the preview shot.
 * @property previewPi like [uiTesting], plus [PreviewDevicePiSessionRepository] (plan R8f): a
 *   profile roster, power and trigger status and a swing being calculated, for the picker, device
 *   cards and shutdown UI tests. (The session screen's deletes use [previewPiSession] instead.)
 * @property rangeMode open the driving range over the dashboard at launch (ContentView.swift:33-35).
 * @property previewFlight fly the displayed shot whenever the range opens (DrivingRangeView.swift).
 * @property transport persisted as the selected transport before the UI starts.
 * @property host persisted as the Wi-Fi host before the UI starts.
 * @property previewHistory swap in [PreviewShotHistoryRepository]: two stored sessions whose
 *   deletes land after a short delay (plan R8f, so the pending state can be seen).
 * @property previewHistoryStuck like [previewHistory], but deletes never land (the failure state).
 * @property previewPiSession swap in [PreviewPiSessionRepository]: a connected Pi whose deletes and
 *   clears are confirmed after a short delay (plan R8f). Use with [uiTesting].
 * @property previewPiSessionStuck like [previewPiSession], but the Pi never answers.
 * @property rangeFreezeProgress plan F8c2 (iOS) and F8a2a (Android), for screenshots:
 *   `--range-freeze-progress 0.5` (Android `--ef range_freeze_progress 0.5`) holds every range
 *   flight at that playback progress (0..1, clamped). At 1 the flight lands and the follow camera
 *   is shown fully settled.
 * @property rangeRealityKit iOS only (plan F8c2): `--range-realitykit` draws the range with the
 *   previous RealityKit scene instead of the Canvas renderer. Kept for one release (ADR 0002).
 * @property previewLiveShots plan F8b: with [usesFakeRepository], the preview Pi "hits" a new copy
 *   of the preview shot every [PREVIEW_LIVE_SHOT_INTERVAL_MILLIS], so a live shot arrives while the
 *   range replays or overlays (the "New shot · Return to live" chip). `--preview-live-shots`.
 * @property previewHistoryBulk plan F8b: [PreviewShotHistoryRepository] with a third, older session
 *   of [PREVIEW_BULK_SHOTS] shots, more than the range overlay's 200-shot cap (the performance
 *   check). Implies [previewHistory]. `--preview-history-bulk`.
 * @property previewPiMock plan F8d-B: like [previewPi], but the preview Pi runs in `mock_mode`, so
 *   the range, Session and Games offer "Simulate shot"; each simulate delivers a new, different
 *   preview shot ([PreviewShotRepository.simulatedShot]) through the live path, with no server.
 *   `--preview-pi-mock`.
 * @property rangeTheme plan F8a2a: persisted as the range theme before the UI starts (a settings
 *   seed like [transport]). `--range-theme day|dusk|night|links` (Android `--es range_theme night`).
 */
data class LaunchOptions(
    val uiTesting: Boolean = false,
    val previewShot: Boolean = false,
    val previewPi: Boolean = false,
    val rangeMode: Boolean = false,
    val previewFlight: Boolean = false,
    val transport: TransportType? = null,
    val host: String? = null,
    val previewHistory: Boolean = false,
    val previewHistoryStuck: Boolean = false,
    val previewPiSession: Boolean = false,
    val previewPiSessionStuck: Boolean = false,
    val rangeFreezeProgress: Double? = null,
    val rangeRealityKit: Boolean = false,
    val previewLiveShots: Boolean = false,
    val previewHistoryBulk: Boolean = false,
    val previewPiMock: Boolean = false,
    val rangeTheme: RangeThemeSetting? = null,
) {
    val usesFakeRepository: Boolean
        get() = uiTesting || previewShot || previewPi || previewPiMock

    companion object {
        const val UI_TESTING = "--ui-testing"
        const val PREVIEW_SHOT = "--preview-shot"
        const val PREVIEW_PI = "--preview-pi"
        const val RANGE_MODE = "--range-mode"
        const val PREVIEW_FLIGHT = "--preview-flight"
        const val TRANSPORT = "--transport"
        const val HOST = "--host"
        const val PREVIEW_HISTORY = "--preview-history"
        const val PREVIEW_HISTORY_STUCK = "--preview-history-stuck"
        const val PREVIEW_PI_SESSION = "--preview-pi-session"
        const val PREVIEW_PI_SESSION_STUCK = "--preview-pi-session-stuck"
        const val RANGE_FREEZE_PROGRESS = "--range-freeze-progress"
        const val RANGE_REALITYKIT = "--range-realitykit"
        const val PREVIEW_LIVE_SHOTS = "--preview-live-shots"
        const val PREVIEW_HISTORY_BULK = "--preview-history-bulk"
        const val PREVIEW_PI_MOCK = "--preview-pi-mock"
        const val RANGE_THEME = "--range-theme"

        /** How often `--preview-live-shots` delivers a shot. */
        const val PREVIEW_LIVE_SHOT_INTERVAL_MILLIS = 5_000L

        /** The `--preview-history-bulk` session's size: more than the overlay's cap of 200. */
        const val PREVIEW_BULK_SHOTS = 220

        /** Parses process arguments; unknown arguments (Xcode adds its own) are ignored. */
        fun fromArguments(arguments: List<String>): LaunchOptions {
            fun valueAfter(flag: String): String? {
                val index = arguments.indexOf(flag)
                return if (index < 0) null else arguments.getOrNull(index + 1)
            }
            return LaunchOptions(
                uiTesting = UI_TESTING in arguments,
                previewShot = PREVIEW_SHOT in arguments,
                previewPi = PREVIEW_PI in arguments,
                rangeMode = RANGE_MODE in arguments,
                previewFlight = PREVIEW_FLIGHT in arguments,
                transport = valueAfter(TRANSPORT)?.let(TransportType::fromStorageValue),
                host = valueAfter(HOST)?.takeIf { it.isNotBlank() && !it.startsWith("--") },
                previewHistory = PREVIEW_HISTORY in arguments,
                previewHistoryStuck = PREVIEW_HISTORY_STUCK in arguments,
                previewPiSession = PREVIEW_PI_SESSION in arguments,
                previewPiSessionStuck = PREVIEW_PI_SESSION_STUCK in arguments,
                rangeFreezeProgress =
                    valueAfter(RANGE_FREEZE_PROGRESS)
                        ?.toDoubleOrNull()
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.0, 1.0),
                rangeRealityKit = RANGE_REALITYKIT in arguments,
                previewLiveShots = PREVIEW_LIVE_SHOTS in arguments,
                previewHistoryBulk = PREVIEW_HISTORY_BULK in arguments,
                previewPiMock = PREVIEW_PI_MOCK in arguments,
                rangeTheme = valueAfter(RANGE_THEME)?.let(RangeThemeSetting::fromStorageValue),
            )
        }
    }
}
