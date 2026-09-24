// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.ble

/**
 * Why a BLE control command (`set_club`, `get_club`, calibration) failed. Ported from
 * `ios/OpenFlight/BluetoothManager.swift`'s `BluetoothControlError`; the messages are the
 * reference's user-facing strings.
 */
sealed class BleControlException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Unavailable : BleControlException("Connect to OpenFlight over Bluetooth first.")

    class Unsupported :
        BleControlException("This OpenFlight Pi does not support phone controls. Update OpenFlight on the Pi.")

    class Busy : BleControlException("Another phone command is still in progress.")

    class Disconnected : BleControlException("Bluetooth disconnected before OpenFlight replied.")

    class TimedOut(
        cause: Throwable? = null,
    ) : BleControlException("OpenFlight did not reply over Bluetooth. Try again.", cause)

    class InvalidResponse(
        cause: Throwable? = null,
    ) : BleControlException("OpenFlight returned an invalid Bluetooth response.", cause)

    /** The Pi answered `ok:false`; [message] is its `error` string. */
    class Rejected(
        message: String,
    ) : BleControlException(message)

    /**
     * A GATT write or the control subscription failed. The reference surfaces the raw
     * CoreBluetooth error here; this wraps the platform error so no Kable type escapes.
     */
    class Failed(
        cause: Throwable,
    ) : BleControlException(cause.message ?: "Bluetooth control request failed.", cause)
}
