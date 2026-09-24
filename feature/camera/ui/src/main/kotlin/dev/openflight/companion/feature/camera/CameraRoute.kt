// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.camera

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openflight.companion.core.designsystem.rememberOfMessageHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * The camera destination: owns the [CameraViewModel], decodes its MJPEG [CameraViewModel.frames]
 * into the latest [ImageBitmap], and hands [CameraScreen] both.
 */
@Composable
fun CameraRoute(
    onBack: () -> Unit,
    viewModel: CameraViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val frame by rememberLatestFrame(viewModel.frames)
    val messages = rememberOfMessageHostState()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CameraEffect.Message -> messages.show(effect.text)
            }
        }
    }
    CameraScreen(uiState = uiState, frame = frame, onEvent = viewModel::onEvent, onBack = onBack, messages = messages)
}

/**
 * The newest decodable JPEG from [frames], while the screen is at least STARTED (so the shared HTTP
 * stream closes in the background).
 *
 * Memory stays bounded: [conflate] keeps at most one undecoded frame waiting while another decodes
 * (older ones are dropped, never queued), and only the latest decoded bitmap is referenced; the
 * previous one becomes garbage as soon as the next replaces it. A frame that fails to decode is
 * skipped and the last good one stays on screen.
 */
@Composable
internal fun rememberLatestFrame(frames: Flow<ByteArray>): State<ImageBitmap?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<ImageBitmap?>(initialValue = null, frames, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            frames.conflate().collect { jpeg ->
                withContext(Dispatchers.Default) { decodeJpeg(jpeg) }?.let { value = it }
            }
        }
    }
}

/** Decodes one MJPEG part; `null` for a corrupt or truncated JPEG. */
internal fun decodeJpeg(jpeg: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

/** Test tags for the Android camera screen. */
object CameraTestTags {
    const val DONE = "camera.done"
    const val FEED = "camera.feed"
    const val FRAME = "camera.frame"
    const val PHASE_TITLE = "camera.phaseTitle"
    const val RETRY = "camera.retry"
    const val BALL_STATUS = "camera.ballStatus"
    const val TOGGLE_CAMERA = "camera.toggleCamera"
    const val TOGGLE_STREAM = "camera.toggleStream"
}
