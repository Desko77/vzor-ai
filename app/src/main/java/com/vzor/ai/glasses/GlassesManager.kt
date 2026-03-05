package com.vzor.ai.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.vzor.ai.domain.model.GlassesState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Manages connection to Meta Ray-Ban glasses via Bluetooth HFP profile.
 *
 * Architecture (VisionClaw-based):
 * - Uses StateFlow for reactive glasses state propagation
 * - SharedFlow for audio/camera frame streaming with back-pressure handling
 * - BluetoothManager/BluetoothProfile for HFP connection lifecycle
 * - Foreground service-compatible: does not hold Activity references
 *
 * When the Meta Wearables DAT SDK becomes available, the connect/disconnect
 * methods and camera frame capture will delegate to the DAT SDK while
 * preserving the same public API surface.
 */
@Singleton
class GlassesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GlassesManager"
        private const val BT_CONNECT_TIMEOUT_MS = 15_000L
        private const val SCO_CONNECT_TIMEOUT_MS = 10_000L
    }

    // --- Public state ---

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    /** PCM 16kHz mono 16-bit audio frames from BT HFP mic. */
    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ByteArray> = _audioFrames.asSharedFlow()

    /** Camera frames (JPEG bytes) from glasses camera. */
    private val _cameraFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val cameraFrames: SharedFlow<ByteArray> = _cameraFrames.asSharedFlow()

    // --- Internal state ---

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var bluetoothHeadset: BluetoothHeadset? = null
    private var connectedDevice: BluetoothDevice? = null
    private var audioCaptureJob: Job? = null
    private var cameraStreamJob: Job? = null
    @Volatile private var isAudioCapturing = false
    @Volatile private var isCameraStreaming = false

    private var scoReceiver: BroadcastReceiver? = null
    private var headsetReceiver: BroadcastReceiver? = null

    // --- Bluetooth HFP Profile Listener ---

    private var profileConnectionContinuation: CancellableContinuation<Boolean>? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as BluetoothHeadset
                Log.d(TAG, "HFP profile proxy connected")

                // Check if glasses are already connected
                val devices = proxy.connectedDevices
                if (devices.isNotEmpty()) {
                    connectedDevice = devices[0]
                    Log.d(TAG, "Found already-connected HFP device: ${connectedDevice?.name}")
                    _state.value = GlassesState.CONNECTED
                    profileConnectionContinuation?.resume(true)
                    profileConnectionContinuation = null
                } else {
                    // No device connected yet — wait for headset state change broadcast
                    Log.d(TAG, "HFP proxy ready, waiting for device connection")
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                Log.w(TAG, "HFP profile proxy disconnected")
                bluetoothHeadset = null
                connectedDevice = null
                stopAudioCaptureInternal()
                stopCameraStreamInternal()
                _state.value = GlassesState.DISCONNECTED
            }
        }
    }

    // -----------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------

    /**
     * Connect to glasses via Bluetooth HFP profile.
     *
     * Steps:
     * 1. Obtain BluetoothAdapter and verify BT is enabled
     * 2. Request HFP profile proxy from the system
     * 3. Wait for profile proxy callback with connected device
     * 4. Register broadcast receiver for headset state changes
     *
     * When Meta DAT SDK is integrated, this method will instead call:
     *   WearableDeviceManager.getInstance(context).startDeviceDiscovery(callback)
     */
    @SuppressLint("MissingPermission")
    suspend fun connect() {
        if (_state.value == GlassesState.CONNECTED || _state.value == GlassesState.STREAMING_AUDIO) {
            Log.d(TAG, "Already connected or streaming, ignoring connect()")
            return
        }

        _state.value = GlassesState.CONNECTING

        try {
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.e(TAG, "Bluetooth is not available or not enabled")
                _state.value = GlassesState.ERROR
                return
            }

            // Register headset state change receiver
            registerHeadsetReceiver()

            // Request HFP profile proxy
            val proxyObtained = withTimeoutOrNull(BT_CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    profileConnectionContinuation = cont
                    val opened = adapter.getProfileProxy(
                        context,
                        profileListener,
                        BluetoothProfile.HEADSET
                    )
                    if (!opened) {
                        Log.e(TAG, "Failed to open HFP profile proxy")
                        cont.resume(false)
                        profileConnectionContinuation = null
                    }

                    cont.invokeOnCancellation {
                        profileConnectionContinuation = null
                    }
                }
            }

            if (proxyObtained != true) {
                // Profile proxy opened but no device found within timeout — check one more time
                val headset = bluetoothHeadset
                if (headset != null) {
                    val devices = headset.connectedDevices
                    if (devices.isNotEmpty()) {
                        connectedDevice = devices[0]
                        _state.value = GlassesState.CONNECTED
                        Log.d(TAG, "Connected to ${connectedDevice?.name} after timeout check")
                        return
                    }
                }
                Log.w(TAG, "No HFP device found within timeout")
                _state.value = GlassesState.ERROR
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            _state.value = GlassesState.ERROR
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _state.value = GlassesState.ERROR
        }
    }

    /**
     * Disconnect from glasses and release all Bluetooth resources.
     */
    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Disconnecting from glasses")

            stopAudioCaptureInternal()
            stopCameraStreamInternal()
            unregisterHeadsetReceiver()
            unregisterScoReceiver()

            bluetoothHeadset?.let { headset ->
                bluetoothManager?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, headset)
            }
            bluetoothHeadset = null
            connectedDevice = null

            _state.value = GlassesState.DISCONNECTED
            Log.d(TAG, "Disconnected")
        }
    }

    // -----------------------------------------------------------------
    // Audio streaming (BT HFP mic → PCM 16kHz)
    // -----------------------------------------------------------------

    /**
     * Start capturing audio from the BT HFP microphone on the glasses.
     *
     * Opens a Bluetooth SCO (Synchronous Connection-Oriented) link so the
     * system routes the AudioRecord source to the glasses microphone.
     * Audio frames are emitted to [audioFrames] as PCM 16kHz mono 16-bit chunks.
     *
     * The actual AudioRecord capture loop is managed by [AudioStreamHandler],
     * but this method handles the SCO connection setup and state transitions.
     */
    suspend fun startAudioCapture() {
        if (connectedDevice == null) {
            Log.w(TAG, "Cannot start audio capture — no glasses connected")
            return
        }
        if (isAudioCapturing) {
            Log.d(TAG, "Audio capture already running")
            return
        }

        _state.value = GlassesState.STREAMING_AUDIO

        try {
            // Start Bluetooth SCO to route audio through glasses mic
            val scoConnected = startBluetoothSco()
            if (!scoConnected) {
                Log.w(TAG, "SCO connection failed, audio will use device mic as fallback")
            }

            isAudioCapturing = true
            Log.d(TAG, "Audio capture started (SCO=${scoConnected})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            _state.value = GlassesState.ERROR
        }
    }

    /**
     * Stop audio capture and close the SCO link.
     */
    fun stopAudioCapture() {
        stopAudioCaptureInternal()
        if (_state.value == GlassesState.STREAMING_AUDIO) {
            _state.value = if (connectedDevice != null) GlassesState.CONNECTED else GlassesState.DISCONNECTED
        }
    }

    // -----------------------------------------------------------------
    // Camera capture
    // -----------------------------------------------------------------

    /**
     * Capture a single photo from the glasses camera.
     *
     * Returns JPEG-encoded bytes, or null if glasses are not connected or
     * capture fails.
     *
     * TODO: When Meta DAT SDK is available, replace with:
     *   val cameraStream = device.getCameraStream()
     *   return cameraStream.capturePhoto()
     */
    suspend fun capturePhoto(): ByteArray? {
        if (_state.value != GlassesState.CONNECTED && _state.value != GlassesState.STREAMING_AUDIO) {
            Log.w(TAG, "Cannot capture photo — glasses not connected (state=${_state.value})")
            return null
        }

        val previousState = _state.value
        _state.value = GlassesState.CAPTURING_PHOTO

        return try {
            withContext(Dispatchers.IO) {
                // TODO: Replace with Meta DAT SDK camera capture
                // For now, return null as we cannot access the glasses camera
                // without the proprietary SDK. The camera frame pipeline is
                // ready — captured bytes will be emitted to _cameraFrames.
                Log.d(TAG, "Photo capture requested (awaiting DAT SDK integration)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture failed", e)
            _state.value = GlassesState.ERROR
            null
        } finally {
            if (_state.value == GlassesState.CAPTURING_PHOTO) {
                _state.value = previousState
            }
        }
    }

    /**
     * Start continuous camera frame streaming from glasses.
     *
     * Frames are emitted to [cameraFrames] as JPEG-encoded byte arrays.
     *
     * TODO: When Meta DAT SDK is available, replace with:
     *   device.getCameraStream().startStreaming { frame ->
     *       _cameraFrames.tryEmit(frame.toByteArray())
     *   }
     */
    fun startCameraStream() {
        if (isCameraStreaming) {
            Log.d(TAG, "Camera stream already running")
            return
        }

        isCameraStreaming = true
        cameraStreamJob = scope.launch {
            Log.d(TAG, "Camera stream started (awaiting DAT SDK integration)")
            // TODO: Replace with DAT SDK continuous camera stream
            // The loop below is a placeholder that keeps the job alive
            // so stopCameraStream() can cancel it cleanly.
            while (isActive && isCameraStreaming) {
                delay(1000)
            }
        }
    }

    /**
     * Stop continuous camera frame streaming.
     */
    fun stopCameraStream() {
        stopCameraStreamInternal()
    }

    // -----------------------------------------------------------------
    // Query helpers
    // -----------------------------------------------------------------

    /**
     * Returns true if a Bluetooth audio device (SCO headset) is currently
     * connected and can serve as the audio input source.
     */
    fun isBluetoothAudioConnected(): Boolean {
        return audioManager.isBluetoothScoOn || hasBluetoothAudioInputDevice()
    }

    /**
     * Returns the currently connected [BluetoothDevice], or null.
     */
    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    // -----------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------

    /**
     * Emit an audio frame to the shared flow (called by AudioStreamHandler).
     */
    internal fun emitAudioFrame(frame: ByteArray) {
        _audioFrames.tryEmit(frame)
    }

    /**
     * Emit a camera frame to the shared flow.
     */
    internal fun emitCameraFrame(frame: ByteArray) {
        _cameraFrames.tryEmit(frame)
    }

    @Suppress("DEPRECATION")
    private suspend fun startBluetoothSco(): Boolean {
        return suspendCancellableCoroutine { cont ->
            var resumed = false

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                        val scoState = intent.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED
                        )
                        when (scoState) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                Log.d(TAG, "SCO audio connected")
                                if (!resumed) {
                                    resumed = true
                                    cont.resume(true)
                                }
                            }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                Log.d(TAG, "SCO audio disconnected")
                            }
                            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                Log.e(TAG, "SCO audio error")
                                if (!resumed) {
                                    resumed = true
                                    cont.resume(false)
                                }
                            }
                        }
                    }
                }
            }

            scoReceiver = receiver
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true

            // If SCO is already connected, resume immediately
            if (audioManager.isBluetoothScoOn) {
                scope.launch {
                    delay(500) // Small delay to let SCO stabilize
                    if (!resumed) {
                        resumed = true
                        cont.resume(true)
                    }
                }
            }

            cont.invokeOnCancellation {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) { }
                scoReceiver = null
            }
        }
    }

    private fun stopAudioCaptureInternal() {
        isAudioCapturing = false
        audioCaptureJob?.cancel()
        audioCaptureJob = null

        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SCO", e)
        }

        unregisterScoReceiver()
        Log.d(TAG, "Audio capture stopped")
    }

    private fun stopCameraStreamInternal() {
        isCameraStreaming = false
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        Log.d(TAG, "Camera stream stopped")
    }

    @SuppressLint("MissingPermission")
    private fun registerHeadsetReceiver() {
        if (headsetReceiver != null) return

        headsetReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val btState = intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE,
                            BluetoothProfile.STATE_DISCONNECTED
                        )
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }

                        when (btState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedDevice = device
                                _state.value = GlassesState.CONNECTED
                                Log.d(TAG, "Headset connected: ${device?.name}")
                                profileConnectionContinuation?.resume(true)
                                profileConnectionContinuation = null
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                if (device == connectedDevice || connectedDevice == null) {
                                    connectedDevice = null
                                    stopAudioCaptureInternal()
                                    stopCameraStreamInternal()
                                    _state.value = GlassesState.DISCONNECTED
                                    Log.d(TAG, "Headset disconnected: ${device?.name}")
                                }
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(headsetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(headsetReceiver, filter)
        }
    }

    private fun unregisterHeadsetReceiver() {
        headsetReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        headsetReceiver = null
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        scoReceiver = null
    }

    private fun hasBluetoothAudioInputDevice(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }
}
