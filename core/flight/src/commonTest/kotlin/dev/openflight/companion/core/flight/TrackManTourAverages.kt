// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.flight

/**
 * TrackMan PGA and LPGA Tour averages, 2009 (plan F2b goldens).
 *
 * Source: TrackMan's tour-average tables, which TrackMan publishes only as images
 * (trackman.com/blog/introducing-updated-tour-averages; the help-centre article
 * "Shot Analysis - Tour Averages On PGA & LPGA Tour"). The numbers below come from the text
 * transcription at 3jack.blogspot.com/2010/02/trackman-pga-lpga-tour-averages.html (9 Feb 2010).
 *
 * **Transcription caveat:** a blog transcription of an image, so a digit may be off. The LPGA
 * 3-wood's spin (2705 rpm, against 4501 for the 5-wood) looks mis-transcribed, so that row is left
 * out. The table has no landing-angle or hang-time columns; the later (c. 2014) table's driver
 * landing angle is 38°.
 *
 * Columns: ball speed (mph), vertical launch (°), spin (rpm), max height (yd), carry (yd).
 */
internal object TrackManTourAverages {
    data class Row(
        val club: String,
        val ballSpeedMph: Double,
        val launchDegrees: Double,
        val spinRpm: Double,
        val maxHeightYards: Double,
        val carryYards: Double,
    )

    val PGA_2009 =
        listOf(
            Row("driver", 165.0, 11.2, 2_685.0, 31.0, 269.0),
            Row("3-wood", 158.0, 9.2, 3_655.0, 30.0, 243.0),
            Row("5-wood", 152.0, 9.4, 4_350.0, 31.0, 230.0),
            Row("3-hybrid", 146.0, 10.2, 4_437.0, 29.0, 225.0),
            Row("3-iron", 142.0, 10.4, 4_640.0, 27.0, 212.0),
            Row("4-iron", 137.0, 11.0, 4_836.0, 28.0, 203.0),
            Row("5-iron", 132.0, 12.1, 5_361.0, 31.0, 194.0),
            Row("6-iron", 127.0, 14.1, 6_231.0, 30.0, 183.0),
            Row("7-iron", 120.0, 16.3, 7_097.0, 32.0, 172.0),
            Row("8-iron", 115.0, 18.1, 7_998.0, 31.0, 160.0),
            Row("9-iron", 109.0, 20.4, 8_647.0, 30.0, 148.0),
            Row("pw", 102.0, 24.2, 9_304.0, 29.0, 136.0),
        )

    /** The LPGA 3-wood row is excluded (see the transcription caveat). */
    val LPGA_2009 =
        listOf(
            Row("driver", 139.0, 14.0, 2_628.0, 25.0, 220.0),
            Row("5-wood", 128.0, 12.2, 4_501.0, 26.0, 185.0),
            Row("7-wood", 123.0, 12.7, 4_693.0, 25.0, 174.0),
            Row("4-iron", 116.0, 14.3, 4_801.0, 24.0, 169.0),
            Row("5-iron", 112.0, 14.8, 5_081.0, 23.0, 161.0),
            Row("6-iron", 109.0, 17.1, 5_943.0, 25.0, 152.0),
            Row("7-iron", 104.0, 19.0, 6_699.0, 26.0, 141.0),
            Row("8-iron", 100.0, 20.8, 7_494.0, 25.0, 130.0),
            Row("9-iron", 93.0, 23.9, 7_586.0, 26.0, 119.0),
            Row("pw", 86.0, 25.6, 8_402.0, 23.0, 107.0),
        )
}
