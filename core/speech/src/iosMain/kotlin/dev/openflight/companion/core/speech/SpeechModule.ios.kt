// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformSpeechModule: Module =
    module {
        single<SpeechEngine> { IosSpeechEngine() }
        // Plan F7: VoiceOver detection for the call-out coordinator and the voice preview.
        single<ScreenReaderMonitor> { IosScreenReaderMonitor() }
    }
