// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.speech

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformSpeechModule: Module =
    module {
        single<SpeechEngine> { AndroidSpeechEngine(context = androidContext()) }
        // Plan F7: TalkBack detection for the call-out coordinator and the voice preview.
        single<ScreenReaderMonitor> { AndroidScreenReaderMonitor(context = androidContext()) }
    }
