// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the platform's screen reader (TalkBack on Android, VoiceOver on iOS) is currently
 * speaking (plan F7 A11y): call-out speech must not talk over it. A consumer checks [isActive]
 * right before calling [SpeechEngine.speak] and skips the utterance (rather than queuing it
 * behind the screen reader, which could read a stale shot well after it landed) when it's `true`.
 *
 * Neither platform type appears in this API (no Android/`platform.*` types in a `core:*` public
 * API).
 */
interface ScreenReaderMonitor {
    /** `true` while the platform screen reader is running. Starts at whatever it reads at construction. */
    val isActive: StateFlow<Boolean>
}
