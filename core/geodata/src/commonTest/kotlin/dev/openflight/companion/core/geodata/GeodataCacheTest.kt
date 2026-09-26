// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class GeodataCacheTest {
    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "openflight-geodata-cache-${Random.nextLong().toULong()}"
    private var clockMillis = 0L

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory)
    }

    private fun cache(): GeodataCache =
        GeodataCache(FileSystem.SYSTEM, directory, ttl = 15.minutes, now = { clockMillis })

    @Test
    fun missingKeyReadsAsNull() {
        assertThat(cache().read("nowhere")).isNull()
    }

    @Test
    fun aWrittenEntryReadsBackVerbatim() {
        val instance = cache()
        instance.write("denver", "the body")

        assertThat(instance.read("denver")).isEqualTo("the body")
    }

    @Test
    fun anEntryOlderThanTheTtlReadsAsNull() {
        val instance = cache()
        instance.write("denver", "the body")

        clockMillis = 15.minutes.inWholeMilliseconds + 1

        assertThat(instance.read("denver")).isNull()
    }

    @Test
    fun anEntryStillWithinTheTtlReadsBack() {
        val instance = cache()
        instance.write("denver", "the body")

        clockMillis = 15.minutes.inWholeMilliseconds - 1

        assertThat(instance.read("denver")).isEqualTo("the body")
    }

    @Test
    fun writingOverwritesTheSameKey() {
        val instance = cache()
        instance.write("denver", "first")
        instance.write("denver", "second")

        assertThat(instance.read("denver")).isEqualTo("second")
    }

    @Test
    fun aKeyWithUnsafeCharactersStillRoundTrips() {
        val instance = cache()
        instance.write("lat39.74_lon-104.99", "denver")

        assertThat(instance.read("lat39.74_lon-104.99")).isEqualTo("denver")
    }
}
