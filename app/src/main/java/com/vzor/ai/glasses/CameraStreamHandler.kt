package com.vzor.ai.glasses

import android.util.Log
import com.vzor.ai.vision.FrameSampler
import com.vzor.ai.vision.SamplingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages camera frame capture from smart glasses via Meta DAT SDK.
 *
 * This handler controls frame emission rate via the [FrameSampler] and exposes
 * captured JPEG frames through a [SharedFlow]. It uses DROP_OLDEST buffering
 * to prevent frame drops from blocking the producer.
 *
 * Camera frames are sourced from [GlassesManager.cameraFrames] which receives
 * them from the DAT SDK StreamSession.videoStream. This handler applies
 * rate-limiting via FrameSampler before passing frames downstream.
 */
class CameraStreamHandler(
    private val frameSampler: FrameSampler,
    private val glassesManager: GlassesManager
) {

    companion object {
        private const val TAG = "CameraStreamHandler"

        /** Maximum number of frames buffered before dropping the oldest. */
        private const val FRAME_BUFFER_SIZE = 10
    }

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = FRAME_BUFFER_SIZE,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Emits JPEG-encoded frames from the glasses camera. */
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _isCapturing = AtomicBoolean(false)

    /** Whether the camera stream is currently active. */
    val isCapturing: Boolean get() = _isCapturing.get()

    private var captureScope: CoroutineScope? = null
    private var captureJob: Job? = null

    /**
     * Starts capturing frames from the glasses camera via DAT SDK.
     * Frames are emitted to [frames] at the rate controlled by [frameSampler].
     * Does nothing if already capturing.
     */
    fun startCapture() {
        if (_isCapturing.getAndSet(true)) return

        frameSampler.reset()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        captureScope = scope

        // Запускаем стриминг камеры через GlassesManager (DAT SDK)
        glassesManager.startCameraStream()

        captureJob = scope.launch {
            Log.d(TAG, "Camera capture started, collecting from DAT SDK stream")

            glassesManager.cameraFrames.collect { frame ->
                if (!isActive || !_isCapturing.get()) return@collect

                if (frameSampler.shouldCaptureFrame()) {
                    _frames.emit(frame)
                }
            }
        }
    }

    /**
     * Stops capturing frames and releases resources.
     */
    fun stopCapture() {
        if (!_isCapturing.getAndSet(false)) return

        glassesManager.stopCameraStream()

        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null

        Log.d(TAG, "Camera capture stopped")
    }
}
