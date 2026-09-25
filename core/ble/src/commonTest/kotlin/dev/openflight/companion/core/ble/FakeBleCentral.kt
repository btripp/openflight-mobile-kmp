// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.CONTROL_V2_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SERVICE_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_CHARACTERISTIC_UUID
import dev.openflight.companion.core.ble.OpenFlightBleProfile.SHOT_V2_CHARACTERISTIC_UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription

/** Scriptable [BleCentral]: the adapter state is settable and each advertised peripheral satisfies one scan. */
internal class FakeBleCentral(
    initialAdapterState: BleAdapterState = BleAdapterState.PoweredOn,
) : BleCentral {
    val adapter = MutableStateFlow(initialAdapterState)

    /** Every service filter a scan was started with, in order (the Swift `scannedServices`). */
    val scannedServices = mutableListOf<String>()
    var activeScans = 0
        private set
    var scanError: Throwable? = null

    private val advertisements = Channel<FakePeripheralLink>(Channel.UNLIMITED)

    override val adapterState: Flow<BleAdapterState> get() = adapter

    override fun currentAdapterState(): BleAdapterState = adapter.value

    override fun scan(serviceUuid: String): Flow<BlePeripheralLink> =
        flow {
            scannedServices += serviceUuid
            scanError?.let { throw it }
            activeScans++
            try {
                while (true) emit(advertisements.receive())
            } finally {
                activeScans--
            }
        }

    fun advertise(peripheral: FakePeripheralLink) {
        advertisements.trySend(peripheral)
    }
}

/** Scriptable [BlePeripheralLink] that replays notification byte arrays pushed by the test. */
internal class FakePeripheralLink(
    private val characteristics: Set<String>? = setOf(SHOT_CHARACTERISTIC_UUID, CONTROL_CHARACTERISTIC_UUID),
) : BlePeripheralLink {
    var connectError: Throwable? = null

    /** When set, connect() suspends until it completes. */
    var connectGate: CompletableDeferred<Unit>? = null

    /** When set, notifications are enabled (onSubscription runs) only once it completes. */
    var subscribeGate: CompletableDeferred<Unit>? = null
    var controlSubscribeError: Throwable? = null

    /** When set, every write waits for one permit: the write-with-response callback. */
    var writePermits: Channel<Unit>? = null
    var writeError: Throwable? = null
    val writes = mutableListOf<ByteArray>()

    /** The characteristic each entry of [writes] went to, in order. */
    val writeTargets = mutableListOf<String>()

    /** Called after each accepted write: the scripted Pi's hook to answer a command. */
    var onWrite: ((characteristicUuid: String, frame: ByteArray) -> Unit)? = null

    /** Every characteristic notifications were enabled on, in order. */
    val subscriptions = mutableListOf<String>()

    /** Characteristics with notifications enabled right now (removed when the observer is cancelled). */
    val activeSubscriptions = mutableSetOf<String>()

    val shotNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val controlNotifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val shotV2Notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)
    val controlV2Notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)

    var releaseCount = 0
        private set

    private val connected = MutableStateFlow(false)

    override suspend fun connect() {
        connectGate?.await()
        connectError?.let { throw it }
        connected.value = true
    }

    override suspend fun awaitDisconnected() {
        connected.first { !it }
    }

    /** The peripheral drops the link (the reference's `didDisconnectPeripheral`). */
    fun dropConnection() {
        connected.value = false
    }

    override fun characteristicUuids(serviceUuid: String): Set<String>? =
        characteristics.takeIf { serviceUuid == SERVICE_UUID }

    override fun observe(
        serviceUuid: String,
        characteristicUuid: String,
        onSubscription: suspend () -> Unit,
    ): Flow<ByteArray> =
        flow {
            check(characteristics?.contains(characteristicUuid) == true) { "observed an absent characteristic" }
            subscribeGate?.await()
            if (characteristicUuid == CONTROL_CHARACTERISTIC_UUID) controlSubscribeError?.let { throw it }
            val source =
                when (characteristicUuid) {
                    SHOT_CHARACTERISTIC_UUID -> shotNotifications
                    SHOT_V2_CHARACTERISTIC_UUID -> shotV2Notifications
                    CONTROL_V2_CHARACTERISTIC_UUID -> controlV2Notifications
                    else -> controlNotifications
                }
            try {
                emitAll(
                    source.onSubscription {
                        subscriptions += characteristicUuid
                        activeSubscriptions += characteristicUuid
                        onSubscription()
                    },
                )
            } finally {
                activeSubscriptions -= characteristicUuid
            }
        }

    override suspend fun writeWithResponse(
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
    ) {
        check(characteristicUuid == CONTROL_CHARACTERISTIC_UUID || characteristicUuid == CONTROL_V2_CHARACTERISTIC_UUID)
        writePermits?.receive()
        writeError?.let { throw it }
        writes += data
        writeTargets += characteristicUuid
        onWrite?.invoke(characteristicUuid, data)
    }

    override suspend fun release() {
        releaseCount++
        connected.value = false
    }

    fun notifyShot(frames: List<ByteArray>) {
        frames.forEach { check(shotNotifications.tryEmit(it)) }
    }

    fun notifyControl(frames: List<ByteArray>) {
        frames.forEach { check(controlNotifications.tryEmit(it)) }
    }

    fun notifyShotV2(frames: List<ByteArray>) {
        frames.forEach { check(shotV2Notifications.tryEmit(it)) }
    }

    fun notifyControlV2(frames: List<ByteArray>) {
        frames.forEach { check(controlV2Notifications.tryEmit(it)) }
    }
}
