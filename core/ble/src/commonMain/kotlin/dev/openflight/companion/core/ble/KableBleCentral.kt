// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.State
import com.juul.kable.UnmetRequirementException
import com.juul.kable.UnmetRequirementReason
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The platform's adapter-state source. Kable 0.45.0 deprecates `Bluetooth.availability` at
 * `DeprecationLevel.ERROR` (its getter just throws; JuulLabs/kable#737), so each platform
 * supplies its own: a `BluetoothAdapter.ACTION_STATE_CHANGED` receiver on Android and a
 * `CBCentralManagerDelegate` on iOS.
 */
internal interface BleAdapterMonitor {
    val states: Flow<BleAdapterState>

    fun current(): BleAdapterState
}

/** [BleCentral] over Kable 0.45.0. No Kable type crosses this file's boundary. */
@OptIn(ExperimentalUuidApi::class)
internal class KableBleCentral(
    private val adapter: BleAdapterMonitor,
) : BleCentral {
    override val adapterState: Flow<BleAdapterState> get() = adapter.states

    override fun currentAdapterState(): BleAdapterState = adapter.current()

    override fun scan(serviceUuid: String): Flow<BlePeripheralLink> {
        val scanner = Scanner { filters { match { services = listOf(Uuid.parse(serviceUuid)) } } }
        return scanner.advertisements
            .map { advertisement -> KablePeripheralLink(Peripheral(advertisement)) as BlePeripheralLink }
            .catch { error ->
                // Scanning with the adapter off (both platforms) or, on Android ≤ 11, with
                // location services off, is a requirement problem the user must fix, not a
                // transient failure worth retrying every second.
                if (error is UnmetRequirementException) throw BleUnavailableException(error.reason.message())
                throw error
            }
    }

    private fun UnmetRequirementReason.message(): String =
        when (this) {
            UnmetRequirementReason.BluetoothDisabled -> {
                "Bluetooth is turned off"
            }

            UnmetRequirementReason.LocationServicesDisabled -> {
                "Turn on Location to find OpenFlight over Bluetooth"
            }
        }
}

@OptIn(ExperimentalUuidApi::class)
private class KablePeripheralLink(
    private val peripheral: Peripheral,
) : BlePeripheralLink {
    /** Kable's connect() covers GATT connect and service discovery. */
    override suspend fun connect() {
        peripheral.connect()
    }

    override suspend fun awaitDisconnected() {
        peripheral.state.first { it is State.Disconnected }
    }

    override fun characteristicUuids(serviceUuid: String): Set<String>? {
        val target = Uuid.parse(serviceUuid)
        val service = peripheral.services.value?.firstOrNull { it.serviceUuid == target } ?: return null
        return service.characteristics.map { it.characteristicUuid.toString().lowercase() }.toSet()
    }

    override fun observe(
        serviceUuid: String,
        characteristicUuid: String,
        onSubscription: suspend () -> Unit,
    ): Flow<ByteArray> =
        // onSubscription runs after the CCCD write enables notifications: the reference's
        // didUpdateNotificationStateFor(isNotifying == true).
        peripheral.observe(lazyCharacteristic(serviceUuid, characteristicUuid), onSubscription)

    override suspend fun writeWithResponse(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
    ) {
        peripheral.write(lazyCharacteristic(serviceUuid, characteristicUuid), data, WriteType.WithResponse)
    }

    override suspend fun release() {
        try {
            peripheral.disconnect()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (
            @Suppress("TooGenericExceptionCaught") ignored: Exception,
        ) {
            // Best effort: close() below tears the connection down regardless.
        } finally {
            peripheral.close()
        }
    }

    private fun lazyCharacteristic(
        serviceUuid: String,
        characteristicUuid: String,
    ) = characteristicOf(Uuid.parse(serviceUuid), Uuid.parse(characteristicUuid))
}
