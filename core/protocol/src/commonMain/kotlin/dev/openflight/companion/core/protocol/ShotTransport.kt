// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.protocol

import dev.openflight.companion.core.model.CalibrationResult
import dev.openflight.companion.core.model.ClubSelection
import dev.openflight.companion.core.model.ConnectionState
import dev.openflight.companion.core.model.GolfClub
import dev.openflight.companion.core.model.PhoneOrientationMeasurement
import dev.openflight.companion.core.model.ShotEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The transport-agnostic contract both the BLE (step 5) and Wi-Fi (step 4) transports implement,
 * owned here so those two steps can be built in parallel. [core:data] (step 6) is the only
 * caller that decides which implementation is active.
 *
 * Each Wi-Fi transport instance is bound to one host; a host change creates a new instance
 * rather than mutating this one (plan §0.2, §5 step 2).
 */
interface ShotTransport {
    /** Decode and `club_changed` errors surface here as [ConnectionState.Error]. */
    val state: StateFlow<ConnectionState>

    /** Decoded and de-duplicated shots (see [ShotEventDecoder] and plan §0.3). */
    val shots: Flow<ShotEvent>

    val activeClub: StateFlow<GolfClub?>

    val supportsControls: StateFlow<Boolean>

    fun start()

    fun retry()

    fun disconnect()

    suspend fun setClub(club: GolfClub): ClubSelection

    suspend fun currentClub(): ClubSelection

    suspend fun submitCalibration(measurement: PhoneOrientationMeasurement): CalibrationResult
}
