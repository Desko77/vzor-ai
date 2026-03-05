package com.vzor.ai.glasses

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
 * Manages camera frame capture from smart glasses.
 *
 * This handler controls frame emission rate via the [FrameSampler] and exposes
 * captured JPEG frames through a [SharedFlow]. It uses DROP_OLDEST buffering
 * to prevent frame drops from blocking the producer.
 *
 * Note: The actual glasses camera SDK integration is a placeholder. In production
 * this would connect to the DAT SDK camera APIs.
 */
class CameraStreamHandler(
    private val frameSampler: FrameSampler
) {

    companion object {
        /** Maximum number of frames buffered before dropping the oldest. */
        private const val FRAME_BUFFER_SIZE = 10

        /** Polling interval when checking if a frame should be captured (ms). */
        private const val POLL_INTERVAL_MS = 10L
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
     * Starts capturing frames from the glasses camera.
     * Frames are emitted to [frames] at the rate controlled by [frameSampler].
     * Does nothing if already capturing.
     */
    fun startCapture() {
        if (_isCapturing.getAndSet(true)) return

        frameSampler.reset()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        captureScope = scope

        captureJob = scope.launch {
            while (isActive && _isCapturing.get()) {
                if (frameSampler.shouldCaptureFrame()) {
                    val frame = captureFrameFromGlasses()
                    if (frame != null) {
                        _frames.emit(frame)
                    }
                }
                // Short sleep to avoid busy-waiting; actual frame timing is
                // controlled by FrameSampler.shouldCaptureFrame()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops capturing frames and releases resources.
     */
    fun stopCapture() {
        if (!_isCapturing.getAndSet(false)) return

        captureJob?.cancel()
        captureJob = null
        captureScope?.cancel()
        captureScope = null
    }

    /**
     * Placeholder for actual glasses camera frame capture.
     * In production, this would call into the DAT SDK to grab the
     * latest camera frame as a JPEG-encoded byte array.
     *
     * @return JPEG bytes of the captured frame, or null if capture failed.
     */
    private fun captureFrameFromGlasses(): ByteArray? {
        // DAT SDK integration placeholder.
        // Replace with actual glasses camera API call, e.g.:
        //   return datSdk.camera.captureFrame()?.toJpeg()
        return null
    }
}
