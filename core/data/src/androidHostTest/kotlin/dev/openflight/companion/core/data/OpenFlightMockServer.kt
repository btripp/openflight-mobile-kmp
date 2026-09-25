// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * One `openflight-server --mock` process from a backend checkout, for [MockServerIT] (plan R8g).
 *
 * Isolated from the developer's machine: a temporary `HOME`, `--log-dir` and `--profiles-path`,
 * and a free port that is never the 8199 a developer's own mock may hold. It runs
 * `.venv/bin/openflight-server` when the checkout has been `uv sync`ed, and `uv run
 * openflight-server` otherwise (keeping uv's own cache and Python installs out of the temporary
 * `HOME`). [close] kills the process tree and deletes the temporary directory; a JVM shutdown
 * hook does the same if the test run dies first.
 */
internal class OpenFlightMockServer private constructor(
    val port: Int,
    private val process: Process,
    private val workDir: File,
    private val logFile: File,
) : AutoCloseable {
    private val shutdownHook = Thread { kill() }

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /** `host:port` as the app's settings hold it. */
    val host: String get() = "127.0.0.1:$port"

    val isAlive: Boolean get() = process.isAlive

    /** The HTTP status of a `GET` to [path], or `null` when nothing answers. */
    fun status(path: String): Int? =
        runCatching {
            val connection = URI("http://$host$path").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = PROBE_TIMEOUT_MILLIS
            connection.readTimeout = PROBE_TIMEOUT_MILLIS
            try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /** Waits for the process to exit by itself; returns its exit code, or `null` on a timeout. */
    fun awaitExit(timeoutMillis: Long): Int? =
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) process.exitValue() else null

    /** The last [lines] lines of the server's combined stdout/stderr, for failure messages. */
    fun logTail(lines: Int = LOG_TAIL_LINES): String =
        runCatching { logFile.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("(no server log)")

    override fun close() {
        kill()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private fun kill() {
        // `uv run` keeps Python as a child process, so stop the children before the parent.
        destroyDescendants(force = false)
        process.destroy()
        if (!process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)) {
            destroyDescendants(force = true)
            process.destroyForcibly()
            process.waitFor(GRACEFUL_STOP_MILLIS, TimeUnit.MILLISECONDS)
        }
        workDir.deleteRecursively()
    }

    /**
     * `ProcessHandle` through reflection: host tests compile against `android.jar`, which lacks
     * it, but they run on a desktop JVM that has it.
     */
    private fun destroyDescendants(force: Boolean) {
        runCatching {
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            val descendants = handleClass.getMethod("descendants").invoke(handle) as Stream<*>
            val destroy = handleClass.getMethod(if (force) "destroyForcibly" else "destroy")
            descendants.collect(Collectors.toList()).forEach { destroy.invoke(it) }
        }.onFailure { println("[mock-server-it] couldn't stop the server's child processes: $it") }
    }

    companion object {
        private const val PROBE_TIMEOUT_MILLIS = 2_000
        private const val GRACEFUL_STOP_MILLIS = 5_000L
        private const val READY_POLL_MILLIS = 250L
        private const val LOG_TAIL_LINES = 40

        /** The developer's own mock (plan Execution Log); never bind it. */
        private const val RESERVED_PORT = 8199

        /** Engine.IO's polling handshake: every backend version answers it once Flask is up. */
        private const val READY_PATH = "/socket.io/?EIO=4&transport=polling"

        /**
         * Starts the mock from [backendDir] with [extraArgs] and waits until it answers HTTP.
         *
         * @throws IllegalStateException if it exits early or isn't ready within [readyTimeoutMillis].
         */
        fun start(
            backendDir: File,
            extraArgs: List<String> = emptyList(),
            readyTimeoutMillis: Long,
        ): OpenFlightMockServer {
            require(File(backendDir, "pyproject.toml").isFile) { "$backendDir is not an openflight checkout" }
            val port = freePort()
            val workDir = Files.createTempDirectory("openflight-mock-it").toFile()
            val home = File(workDir, "home").apply { mkdirs() }
            val logFile = File(workDir, "server.log")
            val command =
                serverCommand(backendDir) +
                    listOf(
                        "--mock",
                        "--web-port",
                        port.toString(),
                        "--host",
                        "127.0.0.1",
                        "--log-dir",
                        File(workDir, "sessions").absolutePath,
                        "--profiles-path",
                        File(workDir, "profiles.json").absolutePath,
                    ) + extraArgs
            val builder =
                ProcessBuilder(command)
                    .directory(backendDir)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
            val originalHome = System.getProperty("user.home")
            builder.environment().apply {
                put("HOME", home.absolutePath)
                // uv would otherwise re-download everything into the temporary HOME.
                putIfAbsent("UV_CACHE_DIR", "$originalHome/.cache/uv")
                putIfAbsent("UV_PYTHON_INSTALL_DIR", "$originalHome/.local/share/uv/python")
                put("PYTHONUNBUFFERED", "1")
                remove("OPENFLIGHT_PROFILES_PATH")
            }
            println("[mock-server-it] starting: ${command.joinToString(" ")} (cwd $backendDir)")
            val server = OpenFlightMockServer(port, builder.start(), workDir, logFile)
            try {
                server.awaitReady(readyTimeoutMillis)
            } catch (failure: IllegalStateException) {
                val tail = server.logTail()
                server.close()
                throw IllegalStateException("${failure.message}\n--- server log ---\n$tail", failure)
            }
            return server
        }

        private fun serverCommand(backendDir: File): List<String> {
            val venvServer = File(backendDir, ".venv/bin/openflight-server")
            return if (venvServer.canExecute()) {
                listOf(venvServer.absolutePath)
            } else {
                // No --frozen: upstream gitignores uv.lock, so a fresh checkout has none.
                listOf(uvExecutable(), "run", "openflight-server")
            }
        }

        private fun uvExecutable(): String {
            System.getenv("OPENFLIGHT_UV")?.let { return it }
            val home = System.getProperty("user.home")
            val candidates =
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .map { File(it, "uv") } +
                    listOf(File(home, ".local/bin/uv"), File(home, ".cargo/bin/uv"), File("/opt/homebrew/bin/uv"))
            return candidates.firstOrNull { it.canExecute() }?.absolutePath
                ?: error("uv not found; install it or set OPENFLIGHT_UV, or run `uv sync` in the backend checkout")
        }

        private fun freePort(): Int {
            while (true) {
                val port = ServerSocket(0).use { it.localPort }
                if (port != RESERVED_PORT) return port
            }
        }

        private fun OpenFlightMockServer.awaitReady(timeoutMillis: Long) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                check(isAlive) { "openflight-server exited early (code ${process.exitValue()})" }
                if (status(READY_PATH) == HttpURLConnection.HTTP_OK) return
                Thread.sleep(READY_POLL_MILLIS)
            }
            error("openflight-server wasn't ready on port $port within $timeoutMillis ms")
        }
    }
}
