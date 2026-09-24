// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.Flow

/** Which transport the app streams shots over. Mirrors the reference's `ShotTransport` enum. */
enum class TransportType(
    /** The value persisted in settings (the reference's `@AppStorage("shotTransport")` raw values). */
    val storageValue: String,
) {
    BLUETOOTH("bluetooth"),
    WIFI("wifi"),
    ;

    companion object {
        fun fromStorageValue(value: String?): TransportType? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * The user's persisted choices: the same three values the iOS app keeps in `@AppStorage`
 * (`shotTransport`, `piHost`, `selectedClub`). Every flow emits the stored value, or the default
 * when nothing (or something unreadable) is stored.
 */
interface SettingsRepository {
    val transport: Flow<TransportType>

    /** The Wi-Fi host exactly as the user submitted it; [ShotRepository] normalizes it per request. */
    val host: Flow<String>

    val selectedClub: Flow<GolfClub>

    suspend fun setTransport(transport: TransportType)

    /** Call on submit, not per keystroke: every distinct value builds a new Wi-Fi transport. */
    suspend fun setHost(host: String)

    suspend fun setSelectedClub(club: GolfClub)

    companion object {
        val DEFAULT_TRANSPORT: TransportType = TransportType.BLUETOOTH
        const val DEFAULT_HOST: String = "raspberrypi.local:8080"
        val DEFAULT_CLUB: GolfClub = GolfClub.DRIVER
    }
}
