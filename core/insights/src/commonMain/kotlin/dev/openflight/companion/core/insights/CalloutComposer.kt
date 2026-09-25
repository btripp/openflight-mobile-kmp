// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.insights

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * Composes a spoken shot call-out from a [CalloutInput] (plan F4): pure text in, text out, no
 * platform TTS involved (that is `core:speech`'s [dev.openflight.companion.core.speech.SpeechEngine]).
 *
 * Rules, in order:
 * - a `null` field in [CalloutInput] is left out entirely (never spoken as "null" or a fabricated
 *   zero);
 * - a field named in [CalloutInput.estimated] is prefixed "about";
 * - units are spoken in full ("yards", "miles per hour", …), never abbreviated;
 * - every numeric field is rounded to the nearest whole unit for speech, except [CalloutField.SMASH],
 *   which is rounded to 2 decimal places and spelled out as words (`"1.48"` read aloud by a TTS
 *   engine is not reliably "one point four eight", so the composer spells it out itself);
 * - [CalloutField.TARGET_DELTA] and [CalloutField.OFFLINE] speak a rounded distance and a
 *   direction word ("long"/"short", "right"/"left") instead of a signed number, and read as "on
 *   target"/"on line" when they round to zero.
 *
 * Requested [fields] are spoken in the order given, joined by `", "`. An empty result (every
 * requested field was `null`) is `""`, so a caller can skip speaking entirely.
 */
object CalloutComposer {
    fun compose(
        input: CalloutInput,
        fields: List<CalloutField>,
        units: UnitSystem = UnitSystem.IMPERIAL,
        verbosity: CalloutVerbosity = CalloutVerbosity.CONCISE,
    ): String = fields.mapNotNull { field -> phrase(field, input, units, verbosity) }.joinToString(", ")

    private fun phrase(
        field: CalloutField,
        input: CalloutInput,
        units: UnitSystem,
        verbosity: CalloutVerbosity,
    ): String? {
        val value = valueText(field, input, units) ?: return null
        val spoken = if (field in input.estimated) "about $value" else value
        return if (verbosity == CalloutVerbosity.DETAILED) "${label(field)} $spoken" else spoken
    }

    // A straightforward one-branch-per-CalloutField dispatch table; each branch is trivial, but
    // there are ten of them, which trips detekt's generic complexity threshold.
    @Suppress("CyclomaticComplexMethod")
    private fun valueText(
        field: CalloutField,
        input: CalloutInput,
        units: UnitSystem,
    ): String? =
        when (field) {
            CalloutField.CARRY -> {
                input.carryYards?.let { distancePhrase(it, units) }
            }

            CalloutField.TOTAL -> {
                input.totalYards?.let { distancePhrase(it, units) }
            }

            CalloutField.BALL_SPEED -> {
                input.ballSpeedMph?.let { speedPhrase(it, units) }
            }

            CalloutField.CLUB_SPEED -> {
                input.clubSpeedMph?.let { speedPhrase(it, units) }
            }

            CalloutField.SMASH -> {
                input.smash?.let { spellDecimal(it, SMASH_DECIMALS) }
            }

            CalloutField.LAUNCH -> {
                input.launchAngleDegrees?.let { "${roundedInt(it)} degrees" }
            }

            CalloutField.SPIN -> {
                input.spinRpm?.let { "${roundedInt(it)} revolutions per minute" }
            }

            CalloutField.CLUB -> {
                input.club?.displayName
            }

            CalloutField.OFFLINE -> {
                input.offlineYards?.let {
                    signedDistancePhrase(
                        it,
                        units,
                        zero = "on line",
                        positive = "right",
                        negative = "left",
                    )
                }
            }

            CalloutField.TARGET_DELTA -> {
                input.targetDeltaYards?.let {
                    signedDistancePhrase(it, units, zero = "on target", positive = "long", negative = "short")
                }
            }
        }

    private fun label(field: CalloutField): String =
        when (field) {
            CalloutField.CARRY -> "Carry"
            CalloutField.TOTAL -> "Total"
            CalloutField.BALL_SPEED -> "Ball speed"
            CalloutField.CLUB_SPEED -> "Club speed"
            CalloutField.SMASH -> "Smash factor"
            CalloutField.LAUNCH -> "Launch angle"
            CalloutField.SPIN -> "Spin rate"
            CalloutField.OFFLINE -> "Offline"
            CalloutField.CLUB -> "Club"
            CalloutField.TARGET_DELTA -> "Target"
        }

    private fun distancePhrase(
        yards: Double,
        units: UnitSystem,
    ): String = "${roundedInt(convertDistanceFromYards(yards, units))} ${spokenDistanceUnit(units)}"

    private fun speedPhrase(
        mph: Double,
        units: UnitSystem,
    ): String = "${roundedInt(convertSpeedFromMph(mph, units))} ${spokenSpeedUnit(units)}"

    /**
     * A rounded distance read as a direction word instead of a sign, e.g. `-8.0` with
     * (`"on line"`, `"right"`, `"left"`) -> `"8 yards left"`; `0` reads as [zero]. Shared by
     * [CalloutField.OFFLINE] and [CalloutField.TARGET_DELTA].
     */
    private fun signedDistancePhrase(
        yards: Double,
        units: UnitSystem,
        zero: String,
        positive: String,
        negative: String,
    ): String {
        val rounded = roundedInt(convertDistanceFromYards(yards, units))
        return when {
            rounded == 0L -> zero
            rounded > 0 -> "$rounded ${spokenDistanceUnit(units)} $positive"
            else -> "${-rounded} ${spokenDistanceUnit(units)} $negative"
        }
    }

    private fun spokenDistanceUnit(units: UnitSystem): String = if (units == UnitSystem.METRIC) "meters" else "yards"

    private fun spokenSpeedUnit(units: UnitSystem): String =
        if (units == UnitSystem.METRIC) "kilometers per hour" else "miles per hour"

    private fun roundedInt(value: Double): Long = value.roundToLong()

    private const val SMASH_DECIMALS = 2
}

private val ONES =
    arrayOf(
        "zero",
        "one",
        "two",
        "three",
        "four",
        "five",
        "six",
        "seven",
        "eight",
        "nine",
        "ten",
        "eleven",
        "twelve",
        "thirteen",
        "fourteen",
        "fifteen",
        "sixteen",
        "seventeen",
        "eighteen",
        "nineteen",
    )
private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

private const val TEN = 10L
private const val TWENTY = 20L
private const val HUNDRED = 100L
private const val THOUSAND = 1000L

/** Spells a non-negative whole number out as words, e.g. `148` -> `"one hundred forty-eight"`. */
private fun spellWholeNumber(n: Long): String {
    require(n >= 0) { "spellWholeNumber only handles non-negative values, was $n" }
    return when {
        n < TWENTY -> {
            ONES[n.toInt()]
        }

        n < HUNDRED -> {
            val tens = TENS[(n / TEN).toInt()]
            val ones = n % TEN
            if (ones == 0L) tens else "$tens-${ONES[ones.toInt()]}"
        }

        n < THOUSAND -> {
            val hundreds = n / HUNDRED
            val rest = n % HUNDRED
            val prefix = "${ONES[hundreds.toInt()]} hundred"
            if (rest == 0L) prefix else "$prefix ${spellWholeNumber(rest)}"
        }

        else -> {
            val thousands = n / THOUSAND
            val rest = n % THOUSAND
            val prefix = "${spellWholeNumber(thousands)} thousand"
            if (rest == 0L) prefix else "$prefix ${spellWholeNumber(rest)}"
        }
    }
}

/**
 * Rounds [value] to [decimals] places and spells it out as words, digit by digit after the
 * decimal point, e.g. `spellDecimal(1.4821, 2)` -> `"one point four eight"`.
 */
private fun spellDecimal(
    value: Double,
    decimals: Int,
): String {
    val scale = 10.0.pow(decimals)
    val scaled = round(abs(value) * scale).toLong()
    val scaleLong = scale.toLong()
    val whole = scaled / scaleLong
    val fractionDigits = (scaled % scaleLong).toString().padStart(decimals, '0')
    val fractionWords = fractionDigits.map { digit -> ONES[digit - '0'] }.joinToString(" ")
    val words = "${spellWholeNumber(whole)} point $fractionWords"
    return if (value < 0) "negative $words" else words
}
