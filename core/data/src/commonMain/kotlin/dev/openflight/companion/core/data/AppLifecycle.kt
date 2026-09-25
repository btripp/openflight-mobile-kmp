// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Whether the app is visible to the user. */
enum class AppLifecycleState {
    FOREGROUND,
    BACKGROUND,
}

/**
 * The app-wide foreground/background signal (plan R8d). The platform shell feeds it: Android from
 * `ProcessLifecycleOwner` (`ON_START`/`ON_STOP`, so a rotation or a permission dialog doesn't
 * count), iOS from the scene phase (`.active`/`.background`). It starts in [AppLifecycleState.BACKGROUND]
 * until the shell reports otherwise. Call from the main thread.
 */
class AppLifecycle {
    private val mutableState = MutableStateFlow(AppLifecycleState.BACKGROUND)
    val state: StateFlow<AppLifecycleState> = mutableState.asStateFlow()

    fun onForeground() {
        mutableState.value = AppLifecycleState.FOREGROUND
    }

    fun onBackground() {
        mutableState.value = AppLifecycleState.BACKGROUND
    }
}

/**
 * The shared connection policy (plan R8d): every transport runs only while the app is in the
 * foreground. On [AppLifecycleState.BACKGROUND] it stops [shots], which disconnects the SSE/BLE
 * transport and the Pi's Socket.IO session (and with it the camera's host); any delete or clear
 * still waiting for the Pi fails with its "connection dropped" outcome. On
 * [AppLifecycleState.FOREGROUND] it starts them again, reconnecting to the same settings.
 *
 * [scope] should run on the thread the lifecycle is reported on (production: an unconfined scope,
 * so [ShotRepository.start]/[ShotRepository.stop] run on the main thread that reported the change).
 */
class LifecycleConnectionPolicy(
    private val lifecycle: Flow<AppLifecycleState>,
    private val shots: ShotRepository,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Starts following [lifecycle]. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.distinctUntilChanged().collect { state ->
                    when (state) {
                        AppLifecycleState.FOREGROUND -> shots.start()
                        AppLifecycleState.BACKGROUND -> shots.stop()
                    }
                }
            }
    }
}
