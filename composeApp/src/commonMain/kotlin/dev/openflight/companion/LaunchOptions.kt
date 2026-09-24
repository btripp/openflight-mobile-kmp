// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.TransportType

/**
 * Debug-only launch hooks, the equivalent of the reference's `--ui-testing`, `--preview-shot`,
 * `--range-mode` and `--preview-flight` process arguments, plus two settings seeds for scripted
 * end-to-end runs.
 *
 * - iOS reads them from `NSProcessInfo.arguments`: `--ui-testing`, `--preview-shot`,
 *   `--range-mode`, `--preview-flight`, `--transport wifi|bluetooth`, `--host <host[:port]>`.
 * - Android reads intent extras: `--ez ui_testing true`, `--ez preview_shot true`,
 *   `--ez range_mode true`, `--ez preview_flight true`, `--es transport wifi`,
 *   `--es host 10.0.2.2:8091`.
 *
 * @property uiTesting swap in [PreviewShotRepository] so no transport ever starts.
 * @property previewShot like [uiTesting], and the fake history holds the preview shot.
 * @property rangeMode open the driving range over the dashboard at launch (ContentView.swift:33-35).
 * @property previewFlight fly the displayed shot whenever the range opens (DrivingRangeView.swift).
 * @property transport persisted as the selected transport before the UI starts.
 * @property host persisted as the Wi-Fi host before the UI starts.
 */
data class LaunchOptions(
    val uiTesting: Boolean = false,
    val previewShot: Boolean = false,
    val rangeMode: Boolean = false,
    val previewFlight: Boolean = false,
    val transport: TransportType? = null,
    val host: String? = null,
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
            )
        }
    }
}
