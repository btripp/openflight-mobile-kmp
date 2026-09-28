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

    // Plan F8a2a: the range theme, added at the end to keep this file's diff mergeable (§4a A7).

    /** The driving range's look, defaulting to [RangeThemeSetting.DAY]. */
    val rangeTheme: Flow<RangeThemeSetting> get() = flowOf(RangeThemeSetting.DEFAULT)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setRangeTheme(theme: RangeThemeSetting) {}

    // Plan F8a2t: the shot trail, added at the end to keep this file's diff mergeable (§4a A7).

    /** How the range draws a shot's trail, defaulting to [ShotTrailStyle.CLASSIC]. */
    val shotTrail: Flow<ShotTrailStyle> get() = flowOf(ShotTrailStyle.DEFAULT)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setShotTrail(style: ShotTrailStyle) {}

    /** How many earlier live shots' trails stay on the range, faded: one of [SHOT_TRAIL_KEEP_OPTIONS]. */
    val shotTrailKeepLast: Flow<Int> get() = flowOf(DEFAULT_SHOT_TRAIL_KEEP_LAST)

    /** Default no-op, see [calloutsEnabled]. A count outside [SHOT_TRAIL_KEEP_OPTIONS] is ignored. */
    suspend fun setShotTrailKeepLast(count: Int) {}

    /** What marks a landing, defaulting to [LandingEffect.OFF]. */
    val landingEffect: Flow<LandingEffect> get() = flowOf(LandingEffect.DEFAULT)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setLandingEffect(effect: LandingEffect) {}

    // Plan F8f: range quick settings, added at the end to keep this file's diff mergeable (§4a A7).

    /** What the driving range shows when it opens: the last "Show" choice, defaulting to live. */
    val rangeShow: Flow<RangeShowSetting> get() = flowOf(RangeShowSetting.DEFAULT)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setRangeShow(show: RangeShowSetting) {}

    /**
     * Plan F5b/F8f "Show total distance": the estimated total and roll-out (labelled "est.") next
     * to carry. On by default.
     */
    val showTotalDistance: Flow<Boolean> get() = flowOf(DEFAULT_SHOW_TOTAL_DISTANCE)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setShowTotalDistance(show: Boolean) {}

    /**
     * Plan F8f: whose shots this device shows on the range ([ViewingProfile]). Device-local: the Pi's
     * active profile is global to the Pi, so it is never switched from here.
     */
    val viewingProfile: Flow<ViewingProfile> get() = flowOf(ViewingProfile.FollowActive)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setViewingProfile(profile: ViewingProfile) {}

    // Plan F14: Demo mode, added at the end to keep this file's diff mergeable (§4a A7).

    /** Whether Demo mode (a pretend Pi, no hardware) was left on. Off by default. */
    val demoMode: Flow<Boolean> get() = flowOf(false)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setDemoMode(enabled: Boolean) {}

    /** Seconds between Demo mode's automatic shots; 0 (the default) is off. */
    val demoAutoFireSeconds: Flow<Int> get() = flowOf(DemoModeRepository.DEFAULT_AUTO_FIRE_SECONDS)

    /** Default no-op, see [calloutsEnabled]. */
    suspend fun setDemoAutoFireSeconds(seconds: Int) {}
}

/**
 * Plan F8a2t: "Keep last shots" offers none or three faded earlier trails; plan F8f adds five and
 * ten (the older ones drawn as thin summary ribbons, so the fill budget holds).
 */
val SHOT_TRAIL_KEEP_OPTIONS: List<Int> = listOf(0, 3, 5, 10)

/** Plan F8a2t: no earlier trails until the user asks for them. */
const val DEFAULT_SHOT_TRAIL_KEEP_LAST: Int = 0

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

/**
 * Which look the driving range draws (plan F8a2a). `feature:range` maps each to its
 * `RangeTheme` palette; this is only the persisted choice.
 */
enum class RangeThemeSetting(
    /** The value persisted in settings. */
    val storageValue: String,
) {
    DAY("day"),
    DUSK("dusk"),
    NIGHT("night"),
    LINKS("links"),
    ;

    companion object {
        val DEFAULT: RangeThemeSetting = DAY

        fun fromStorageValue(value: String?): RangeThemeSetting? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * How the driving range draws a live shot's trail (plan F8a2t). `feature:range` draws each from
 * the shared geometry; this is only the persisted choice. The overlay's many-shot view keeps its
 * thin club-coloured trails whatever this is.
 */
enum class ShotTrailStyle(
    /** The value persisted in settings (and the `--shot-trail` launch argument). */
    val storageValue: String,
) {
    /** Today's tracer: a ribbon with a soft glow. */
    CLASSIC("classic"),

    /** A bright core in a wide, soft broadcast-style glow. */
    BROADCAST_GLOW("broadcast_glow"),

    /** The tail fades out behind the ball. */
    COMET("comet"),

    /** The shot's club colour from the club palette. */
    CLUB_COLOUR("club_colour"),

    /** Beads evenly spaced in time, so the ball's speed shows in their spacing. */
    DOTTED("dotted"),

    /** A trail that widens and fades as it ages. */
    SMOKE("smoke"),

    /** A thin, saturated, crisp line. */
    NEON("neon"),

    /** Coloured by the ball's speed at each point, hot (fast) to cool (slow). */
    SPEED_HEAT("speed_heat"),

    /** The hue runs along the trail's length. */
    RAINBOW("rainbow"),

    /** A flat ribbon twisting at a rate set by the spin; high-spin wedges twist more. */
    SPIN_RIBBON("spin_ribbon"),

    /** The tracer plus a faint line on the ground under it. */
    GROUND_TRACK("ground_track"),
    ;

    companion object {
        val DEFAULT: ShotTrailStyle = CLASSIC

        fun fromStorageValue(value: String?): ShotTrailStyle? = entries.firstOrNull { it.storageValue == value }
    }
}

/** What marks a landing on the range (plan F8a2t). Both are drawn static under reduced motion. */
enum class LandingEffect(
    /** The value persisted in settings. */
    val storageValue: String,
) {
    OFF("off"),

    /** A ring expanding over the ground from the landing spot. */
    RING("ring"),

    /** A burst of dust and sparkle thrown out from the landing spot. */
    BURST("burst"),
    ;

    companion object {
        val DEFAULT: LandingEffect = OFF

        fun fromStorageValue(value: String?): LandingEffect? = entries.firstOrNull { it.storageValue == value }
    }
}

/** Plan F8f: the estimated total shows by default (plan F5b's "Show total distance"). */
const val DEFAULT_SHOW_TOTAL_DISTANCE: Boolean = true

/**
 * What the driving range shows (plan F8f's quick settings "Show"): the live shot only, the newest
 * [lastShots] of the current session, the whole current session, or every stored session, each as
 * the range's overlay. `feature:range` maps it onto its browse modes; this is only the persisted
 * choice.
 */
@Suppress("MagicNumber") // The counts are the options themselves.
enum class RangeShowSetting(
    /** The value persisted in settings. */
    val storageValue: String,
    /** How many of the current session's newest shots to draw, or `null` for no limit. */
    val lastShots: Int? = null,
) {
    LIVE("live"),
    LAST_5("last_5", 5),
    LAST_10("last_10", 10),
    LAST_20("last_20", 20),
    THIS_SESSION("this_session"),
    ALL_SESSIONS("all_sessions"),
    ;

    /** The current session's overlay (LAST N or THIS SESSION), which keeps up with new live shots. */
    val followsCurrentSession: Boolean get() = this != LIVE && this != ALL_SESSIONS

    companion object {
        val DEFAULT: RangeShowSetting = LIVE

        fun fromStorageValue(value: String?): RangeShowSetting? = entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * Plan F8f: whose shots this device shows when several people share one Pi (and so one session):
 * the Pi's active profile ([FollowActive], the default), one [Pinned] profile, or [AllProfiles]
 * (one bay, taking turns). Kept on the device, because the Pi's active profile is global: switching
 * it here would switch every other phone on the Pi too.
 */
sealed interface ViewingProfile {
    /** The value persisted in settings. */
    val storageValue: String

    data object FollowActive : ViewingProfile {
        override val storageValue: String = "follow_active"
    }

    data object AllProfiles : ViewingProfile {
        override val storageValue: String = "all_profiles"
    }

    data class Pinned(
        val profileId: String,
    ) : ViewingProfile {
        override val storageValue: String get() = PINNED_PREFIX + profileId
    }

    companion object {
        private const val PINNED_PREFIX = "profile:"

        fun fromStorageValue(value: String?): ViewingProfile? =
            when {
                value == FollowActive.storageValue -> {
                    FollowActive
                }

                value == AllProfiles.storageValue -> {
                    AllProfiles
                }

                value != null && value.startsWith(PINNED_PREFIX) && value.length > PINNED_PREFIX.length -> {
                    Pinned(value.removePrefix(PINNED_PREFIX))
                }

                else -> {
                    null
                }
            }
    }
}
