// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.TransportType

/**
 * Debug-only launch hooks, the equivalent of the reference's `--ui-testing`, `--preview-shot`,
 * `--range-mode` and `--preview-flight` process arguments, plus two settings seeds for scripted
 * end-to-end runs.
 *
 * - iOS reads them from `NSProcessInfo.arguments`: `--ui-testing`, `--preview-shot`,
 *   `--range-mode`, `--preview-flight`, `--transport wifi|bluetooth`, `--host <host[:port]>`,
 *   `--preview-history`, `--preview-history-stuck`, `--preview-pi-session`, `--preview-pi-session-stuck`.
 * - Android reads intent extras: `--ez ui_testing true`, `--ez preview_shot true`,
 *   `--ez range_mode true`, `--ez preview_flight true`, `--es transport wifi`,
 *   `--es host 10.0.2.2:8091`, `--ez preview_history true`, `--ez preview_history_stuck true`,
 *   `--ez preview_pi_session true`, `--ez preview_pi_session_stuck true`.
 *
 * @property uiTesting swap in [PreviewShotRepository] so no transport ever starts.
 * @property previewShot like [uiTesting], and the fake history holds the preview shot.
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
 */
data class LaunchOptions(
    val uiTesting: Boolean = false,
    val previewShot: Boolean = false,
    val rangeMode: Boolean = false,
    val previewFlight: Boolean = false,
    val transport: TransportType? = null,
    val host: String? = null,
    val previewHistory: Boolean = false,
    val previewHistoryStuck: Boolean = false,
    val previewPiSession: Boolean = false,
    val previewPiSessionStuck: Boolean = false,
) {
    val usesFakeRepository: Boolean
        get() = uiTesting || previewShot

    companion object {
        const val UI_TESTING = "--ui-testing"
        const val PREVIEW_SHOT = "--preview-shot"
        const val RANGE_MODE = "--range-mode"
        const val PREVIEW_FLIGHT = "--preview-flight"
        const val TRANSPORT = "--transport"
        const val HOST = "--host"
        const val PREVIEW_HISTORY = "--preview-history"
        const val PREVIEW_HISTORY_STUCK = "--preview-history-stuck"
        const val PREVIEW_PI_SESSION = "--preview-pi-session"
        const val PREVIEW_PI_SESSION_STUCK = "--preview-pi-session-stuck"

        /** Parses process arguments; unknown arguments (Xcode adds its own) are ignored. */
        fun fromArguments(arguments: List<String>): LaunchOptions {
            fun valueAfter(flag: String): String? {
                val index = arguments.indexOf(flag)
                return if (index < 0) null else arguments.getOrNull(index + 1)
            }
            return LaunchOptions(
                uiTesting = UI_TESTING in arguments,
                previewShot = PREVIEW_SHOT in arguments,
                rangeMode = RANGE_MODE in arguments,
                previewFlight = PREVIEW_FLIGHT in arguments,
                transport = valueAfter(TRANSPORT)?.let(TransportType::fromStorageValue),
                host = valueAfter(HOST)?.takeIf { it.isNotBlank() && !it.startsWith("--") },
                previewHistory = PREVIEW_HISTORY in arguments,
                previewHistoryStuck = PREVIEW_HISTORY_STUCK in arguments,
                previewPiSession = PREVIEW_PI_SESSION in arguments,
                previewPiSessionStuck = PREVIEW_PI_SESSION_STUCK in arguments,
            )
        }
    }
}
