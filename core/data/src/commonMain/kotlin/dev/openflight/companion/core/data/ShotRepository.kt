// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for live shots, whichever transport is active (plan Step 6). The UI
 * never talks to a transport directly: it reads these flows and calls these methods, and the
 * repository follows [SettingsRepository.transport] and [SettingsRepository.host] to pick the
 * transport.
 *
 * Lifecycle methods ([start], [stop], [retry], [disconnect]) must be called from one thread (the
 * main thread), like a ViewModel's.
 */
@Suppress("TooManyFunctions") // Lifecycle, club/calibration control, and history editing (R5a/R6b).
interface ShotRepository {
    /** The active transport's state; [ConnectionState.Idle] while stopped or between transports. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Shots newest first, capped at 100 and deduplicated by `eventId`. Unlike the reference (one
     * history per transport), this history survives transport switches.
     */
    val history: StateFlow<List<ShotEvent>>

    val latestShot: StateFlow<ShotEvent?>

    /** The club the Pi last reported through a `club_changed` event, or `null`. */
    val activeClub: StateFlow<GolfClub?>

    /** Whether the active transport can send control commands (BLE: once the control characteristic is found). */
    val supportsControls: StateFlow<Boolean>

    /**
     * App foreground: start following settings and connect the selected transport. Idempotent.
     * Also starts the Wi-Fi-only [PiSessionRepository] (plan R6b), which connects to the same
     * host only while the transport is Wi-Fi, so both platforms keep calling just this.
     */
    fun start()

    /** App background: disconnect the active transport (and the [PiSessionRepository]) and stop following settings. */
    fun stop()

    /** Reconnect the active transport (after an error, or after the Bluetooth permission grant); starts if stopped. */
    fun retry()

    /** Explicit user disconnect of the active transport. [retry] reconnects it. */
    fun disconnect()

    /**
     * Asks the Pi to switch clubs. The selection is persisted to [SettingsRepository] only after
     * the Pi confirms it, using the club in the Pi's response.
     *
     * @throws NoActiveTransportException when not started.
     */
    suspend fun setClub(club: GolfClub): ClubSelection

    /** Reads the Pi's current club and persists it, like the sync that runs on connect. */
    suspend fun currentClub(): ClubSelection

    suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult

    /**
     * Removes one shot from [history]. While the Pi's Socket.IO link is
     * [connected][dev.openflight.companion.core.model.pi.PiLinkState.Connected] it asks the Pi to
     * delete it, **by timestamp** (`delete_shot`; the SSE/BLE `event_id` is a per-publish UUID the
     * Pi doesn't know), and removes it locally only once the Pi confirms
     * ([PiSessionRepository.deletionState]). Otherwise it's a local edit (plan R5a/R6b/R8c). A
     * no-op if [eventId] isn't in [history].
     *
     * Default no-op so every existing [ShotRepository] implementation (fakes in other feature
     * modules, [dev.openflight.companion.PreviewShotRepository]) stays source-compatible without
     * overriding it; [DefaultShotRepository] is the real implementation.
     */
    fun deleteShot(eventId: String) {}

    /**
     * Deletes the shot with this [timestamp] (the Pi's session key) like [deleteShot]: from the
     * Pi's session while its link is connected, and from [history] when a shot there has it. Used
     * for a Pi session row the phone may never have received over SSE/BLE. Default no-op, see
     * [deleteShot].
     */
    fun deleteShotByTimestamp(timestamp: String) {}

    /**
     * Clears [history]. While the Pi's link is connected it instead clears the **active
     * profile's** rows on the Pi (`clear_session {profile_id}`) and, once confirmed
     * ([PiSessionRepository.clearState]), the local shots filed under that profile (plan R8c).
     * See [deleteShot] for why this has a default body.
     */
    fun clearHistory() {}

    /**
     * Asks the Pi to shut itself down (`POST /api/shutdown`, plan R5a), only while the active
     * transport is Wi-Fi -- Bluetooth has no equivalent endpoint.
     *
     * @throws PiShutdownUnsupportedException when the active transport isn't Wi-Fi, or the
     *   repository isn't started.
     */
    suspend fun shutdownPi(): Unit = throw PiShutdownUnsupportedException()
}

/** A control call was made while no transport is active (the repository is stopped). */
class NoActiveTransportException : IllegalStateException("Not connected to OpenFlight.")

/** [ShotRepository.shutdownPi] was called while the active transport isn't Wi-Fi. */
class PiShutdownUnsupportedException : IllegalStateException("Pi shutdown needs the Wi-Fi transport.")
