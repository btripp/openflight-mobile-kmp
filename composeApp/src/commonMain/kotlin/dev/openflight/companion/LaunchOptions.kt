// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import dev.openflight.companion.core.data.TransportType

/**
 * Debug-only launch hooks, the equivalent of the reference's `--ui-testing` and `--preview-shot`
 * process arguments, plus two settings seeds for scripted end-to-end runs.
 *
 * - iOS reads them from `NSProcessInfo.arguments`: `--ui-testing`, `--preview-shot`,
 *   `--transport wifi|bluetooth`, `--host <host[:port]>`.
 * - Android reads intent extras: `--ez ui_testing true`, `--ez preview_shot true`,
 *   `--es transport wifi`, `--es host 10.0.2.2:8091`.
 *
 * @property uiTesting swap in [PreviewShotRepository] so no transport ever starts.
 * @property previewShot like [uiTesting], and the fake history holds the preview shot.
 * @property transport persisted as the selected transport before the UI starts.
 * @property host persisted as the Wi-Fi host before the UI starts.
 */
data class LaunchOptions(
    val uiTesting: Boolean = false,
    val previewShot: Boolean = false,
    val transport: TransportType? = null,
    val host: String? = null,
) {
    val usesFakeRepository: Boolean
        get() = uiTesting || previewShot

    companion object {
        const val UI_TESTING = "--ui-testing"
        const val PREVIEW_SHOT = "--preview-shot"
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
                transport = valueAfter(TRANSPORT)?.let(TransportType::fromStorageValue),
                host = valueAfter(HOST)?.takeIf { it.isNotBlank() && !it.startsWith("--") },
            )
        }
    }
}
