// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.insights.UnitSystem
import dev.openflight.companion.core.model.GolfClub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * The user's unit preference (plan R5a), defaulting to [UnitSystem.IMPERIAL] like the web UI's
     * `useUnitPreferenceStore`. A default getter keeps every existing [SettingsRepository]
     * implementation (fakes in other feature modules) source-compatible without overriding it.
     */
    val units: Flow<UnitSystem> get() = flowOf(DEFAULT_UNITS)

    suspend fun setTransport(transport: TransportType)

    /** Call on submit, not per keystroke: every distinct value builds a new Wi-Fi transport. */
    suspend fun setHost(host: String)

    suspend fun setSelectedClub(club: GolfClub)

    /** Default no-op so existing implementations don't need to override it (see [units]). */
    suspend fun setUnits(units: UnitSystem) {}

    companion object {
        val DEFAULT_TRANSPORT: TransportType = TransportType.BLUETOOTH
        const val DEFAULT_HOST: String = "raspberrypi.local:8080"
        val DEFAULT_CLUB: GolfClub = GolfClub.DRIVER
        val DEFAULT_UNITS: UnitSystem = UnitSystem.IMPERIAL
    }
}
