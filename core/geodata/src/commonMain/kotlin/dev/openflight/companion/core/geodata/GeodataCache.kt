// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.geodata

import okio.FileSystem
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * A tiny Okio disk cache keyed by a caller-chosen string, each entry stamped with its write time
 * so [read] can enforce [ttl] itself (plan F6 task 2: 15 minutes for weather). One file per key:
 * its first line is the write time in epoch millis, the rest is the cached body verbatim.
 */
@OptIn(ExperimentalTime::class)
internal class GeodataCache(
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val ttl: Duration,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** The cached body for [key], or `null` if there's none, it's unreadable, or older than [ttl]. */
    fun read(key: String): String? =
        runCatching {
            fileSystem.read(pathFor(key)) {
                val writtenAtEpochMillis = readUtf8Line()?.toLongOrNull() ?: return@read null
                if (now() - writtenAtEpochMillis > ttl.inWholeMilliseconds) return@read null
                readUtf8()
            }
        }.getOrNull()

    /** Overwrites [key]'s entry with [body], stamped with the current time. */
    fun write(
        key: String,
        body: String,
    ) {
        runCatching {
            fileSystem.createDirectories(directory)
            fileSystem.write(pathFor(key)) {
                writeUtf8("${now()}\n")
                writeUtf8(body)
            }
        }
    }

    private fun pathFor(key: String): Path = directory / "$FILE_PREFIX${key.fileSafe()}.cache"

    private fun String.fileSafe(): String =
        map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")

    private companion object {
        const val FILE_PREFIX = "geodata-"
    }
}
