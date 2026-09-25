// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.insights.CalloutField
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
 * How the driving range's virtual camera behaves (plan R7a): [FIXED] is the reference's tee
 * camera, [FOLLOW] chases the ball and settles over where it lands.
 */
enum class RangeCameraMode(
    /** The value persisted in settings. */
    val storageValue: String,
) {
    FIXED("fixed"),
    FOLLOW("follow"),
    ;

    companion object {
        fun fromStorageValue(value: String?): RangeCameraMode? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * The user's persisted choices: the same three values the iOS app keeps in `@AppStorage`
 * (`shotTransport`, `piHost`, `selectedClub`). Every flow emits the stored value, or the default
 * when nothing (or something unreadable) is stored.
 */
@Suppress("TooManyFunctions") // One getter/setter pair per persisted key; the surface grows with each new setting.
interface SettingsRepository {
    val transport: Flow<TransportType>

    /**
     * The Wi-Fi host to connect to, exactly as typed ([ShotRepository] normalizes it per request):
     * the one submitted this launch ([setHost]), else the last host that reached `Connected`
     * ([rememberConnectedHost]), else [DEFAULT_HOST]. Only a host that connected survives a
     * relaunch (plan R8d, Expo `socket.ts`), so a typo can't strand the next launch.
     */
    val host: Flow<String>

    val selectedClub: Flow<GolfClub>

    /**
     * The user's unit preference (plan R5a), defaulting to [UnitSystem.IMPERIAL] like the web UI's
     * `useUnitPreferenceStore`. A default getter keeps every existing [SettingsRepository]
     * implementation (fakes in other feature modules) source-compatible without overriding it.
     */
    val units: Flow<UnitSystem> get() = flowOf(DEFAULT_UNITS)

    /**
     * The driving range's camera (plan R7a), defaulting to [RangeCameraMode.FOLLOW]. A default
     * getter for the same source-compatibility reason as [units].
     */
    val rangeCameraMode: Flow<RangeCameraMode> get() = flowOf(DEFAULT_RANGE_CAMERA_MODE)

    suspend fun setTransport(transport: TransportType)

    /**
     * The host the user submitted. Call on submit, not per keystroke: every distinct value builds
     * a new Wi-Fi transport. Kept for this launch only; see [rememberConnectedHost].
     */
    suspend fun setHost(host: String)

    /**
     * Persists [host] as the last good host once a connection to it reached `Connected`. Default
     * no-op so implementations without persistence (test fakes) needn't override it.
     */
    suspend fun rememberConnectedHost(host: String) {}

    suspend fun setSelectedClub(club: GolfClub)

    /** Default no-op so existing implementations don't need to override it (see [units]). */
    suspend fun setUnits(units: UnitSystem) {}

    /** Default no-op so existing implementations don't need to override it (see [rangeCameraMode]). */
    suspend fun setRangeCameraMode(mode: RangeCameraMode) {}

    companion object {
        val DEFAULT_TRANSPORT: TransportType = TransportType.BLUETOOTH
        const val DEFAULT_HOST: String = "raspberrypi.local:8080"
        val DEFAULT_CLUB: GolfClub = GolfClub.DRIVER
        val DEFAULT_UNITS: UnitSystem = UnitSystem.IMPERIAL
        val DEFAULT_RANGE_CAMERA_MODE: RangeCameraMode = RangeCameraMode.FOLLOW

        // Plan F4: audio call-outs default off, and speak carry then ball speed on every shot.
        const val DEFAULT_CALLOUTS_ENABLED: Boolean = false
        const val DEFAULT_CALLOUT_RATE: Float = 1f
        val DEFAULT_CALLOUT_FIELDS: List<CalloutField> = listOf(CalloutField.CARRY, CalloutField.BALL_SPEED)
        val DEFAULT_CALLOUT_TRIGGER: CalloutTrigger = CalloutTrigger.EVERY_SHOT
    }

    // Plan F4: audio call-outs, added at the end to keep this file's diff mergeable with the
    // other wave-1/wave-2 steps that also touch it (see the plan's §4a A7).

    /** Whether shot call-outs are spoken. Off by default: audio needs an explicit opt-in. */
    val calloutsEnabled: Flow<Boolean> get() = flowOf(DEFAULT_CALLOUTS_ENABLED)

    /** The `core:speech` voice id call-outs are spoken with; `null` uses the platform default voice. */
    val calloutVoiceId: Flow<String?> get() = flowOf(null)

    /** The call-out speech rate (`SpeechEngine.speak`'s `rate`); `1f` is the platform default. */
    val calloutRate: Flow<Float> get() = flowOf(DEFAULT_CALLOUT_RATE)

    /** Which metrics a call-out speaks, and in what order. */
    val calloutFields: Flow<List<CalloutField>> get() = flowOf(DEFAULT_CALLOUT_FIELDS)

    /** When call-outs are spoken: every final shot, or only shots taken inside a game. */
    val calloutTrigger: Flow<CalloutTrigger> get() = flowOf(DEFAULT_CALLOUT_TRIGGER)

    /** Default no-op so existing implementations don't need to override it (see [calloutsEnabled]). */
    suspend fun setCalloutsEnabled(enabled: Boolean) {}

    /** Default no-op, see [calloutsEnabled]. A `null` [voiceId] restores the platform default voice. */
    suspend fun setCalloutVoiceId(voiceId: String?) {}

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setCalloutRate(rate: Float) {}

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setCalloutFields(fields: List<CalloutField>) {}

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setCalloutTrigger(trigger: CalloutTrigger) {}
}

/**
 * When shot call-outs are spoken (plan F4): every final shot, or only shots taken during a game
 * (`feature:games`, F9, sets `ActiveGameRepository`; F7's coordinator reads it to gate this).
 */
enum class CalloutTrigger(
    /** The value persisted in settings. */
    val storageValue: String,
) {
    EVERY_SHOT("every_shot"),
    GAMES_ONLY("games_only"),
    ;

    companion object {
        fun fromStorageValue(value: String?): CalloutTrigger? = entries.firstOrNull { it.storageValue == value }
    }
}
