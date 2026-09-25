// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.data

import dev.openflight.companion.core.model.pi.CameraPreview
import dev.openflight.companion.core.model.pi.CameraReplay
import dev.openflight.companion.core.socketio.SocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** A started [DefaultPiSessionRepository] over [FakePiSocket]s, for the repository tests. */
internal class PiHarness(
    scope: CoroutineScope,
    transport: TransportType,
    host: String,
) {
    val settings = FakeSettingsRepository(transport = transport, host = host)
    val sockets = mutableListOf<FakePiSocket>()
    val camera = FakePiCameraSource()
    val logs = mutableListOf<String>()
    val repository =
        DefaultPiSessionRepository(
            settings = settings,
            socketFactory = { socketHost, _ -> FakePiSocket(socketHost).also { sockets += it } },
            cameraSource = camera,
            scope = scope,
            log = { logs += it },
        )

    val socket: FakePiSocket get() = sockets.last()

    /** Connects the current socket and forgets the automatic on-connect requests. */
    fun connected(): FakePiSocket =
        socket.apply {
            serverAcks()
            emitted.clear()
        }

    /** A transient drop: the socket is reconnecting on its own. */
    fun drop() {
        socket.state.value = SocketConnectionState.Reconnecting(1, 500, "Connection closed")
    }
}

internal fun runPiTest(
    transport: TransportType = TransportType.WIFI,
    host: String = "pi.local:8080",
    body: suspend TestScope.(PiHarness) -> Unit,
) = runTest(UnconfinedTestDispatcher()) {
    val harness = PiHarness(backgroundScope, transport, host)
    harness.repository.start()
    body(harness)
}

/** A scripted [PiCameraSource]: records every host it was asked on. */
internal class FakePiCameraSource : PiCameraSource {
    val hosts = mutableListOf<String>()
    var preview: CameraPreview = CameraPreview.Frame(byteArrayOf(1, 2))
    var replay: (String) -> CameraReplay = { CameraReplay(id = it, videoUrl = "/api/camera/replays/$it/video") }

    override suspend fun preview(host: String): CameraPreview {
        hosts += host
        return preview
    }

    override suspend fun prepareReplay(
        host: String,
        replayId: String,
    ): CameraReplay {
        hosts += host
        return replay(replayId)
    }

    override fun videoUrl(
        host: String,
        replay: CameraReplay,
    ): String? = replay.videoUrl?.let { "http://$host$it" }
}
