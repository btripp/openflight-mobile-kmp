// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion

import android.app.Application
import org.koin.android.ext.koin.androidContext

/**
 * Starts the Koin graph once per process. `core:data` builds the Bluetooth transport and the
 * settings DataStore from the Android context.
 */
class OpenFlightApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@OpenFlightApplication)
        }
        // Plan F7: the shot call-out coordinator starts from OpenFlightApp's AppLifecycleSource,
        // not here — same reason LifecycleConnectionPolicy does: starting it here would eagerly
        // build its ConditionsRepository (SharingStarted.Eagerly) against the real settings
        // DataStore before a debug launch hook or an instrumented test gets to replace it,
        // and a later stopKoin()+initKoin() in the same process (the app flow tests' pattern)
        // would then collide with that still-open DataStore file.
    }
}
