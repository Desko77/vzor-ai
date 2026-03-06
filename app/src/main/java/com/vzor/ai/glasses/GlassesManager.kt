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
import android.app.Activity
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission as DatPermission
import com.meta.wearable.dat.core.types.PermissionStatus as DatPermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Manages connection to Meta Ray-Ban glasses via Meta Wearables DAT SDK
 * with Bluetooth HFP fallback for audio.
 *
 * Architecture:
 * - DAT SDK handles device registration, camera streaming, photo capture
 * - BT HFP used for audio routing (mic/speaker) independently
 * - StateFlow for reactive glasses state propagation
 * - SharedFlow for audio/camera frame streaming with back-pressure handling
 */
@Singleton
class GlassesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "GlassesManager"
        private const val BT_CONNECT_TIMEOUT_MS = 15_000L
        private const val SCO_CONNECT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_FRAME_RATE = 24
    }

    // --- Public state ---

    private val _state = MutableStateFlow(GlassesState.DISCONNECTED)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    /** PCM 16kHz mono 16-bit audio frames from BT HFP mic. */
    private val _audioFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<ByteArray> = _audioFrames.asSharedFlow()

    /** Camera frames (JPEG bytes) from glasses camera via DAT SDK. */
    private val _cameraFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val cameraFrames: SharedFlow<ByteArray> = _cameraFrames.asSharedFlow()

    private val _registrationState = MutableStateFlow(false)
    /** Зарегистрировано ли устройство через Meta DAT SDK. */
    val isRegistered: StateFlow<Boolean> = _registrationState.asStateFlow()

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
    private var registrationObserverJob: Job? = null
    private var streamStateObserverJob: Job? = null
    private val isAudioCapturing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isCameraStreaming = java.util.concurrent.atomic.AtomicBoolean(false)

    private var scoReceiver: BroadcastReceiver? = null
    private var headsetReceiver: BroadcastReceiver? = null

    /** Активная сессия камеры DAT SDK. */
    private var streamSession: StreamSession? = null
    private var isDatInitialized = false

    // --- DAT SDK Initialization ---

    /**
     * Инициализирует Meta Wearables DAT SDK.
     * Должен быть вызван при старте приложения (из Application.onCreate или Activity).
     */
    fun initializeDatSdk() {
        if (isDatInitialized) return

        try {
            Wearables.initialize(context)
            isDatInitialized = true
            Log.d(TAG, "Meta Wearables DAT SDK initialized")

            // Наблюдаем за состоянием регистрации
            registrationObserverJob = scope.launch {
                Wearables.registrationState.collectLatest { regState ->
                    val isRegisteredNow = regState == RegistrationState.REGISTERED
                    _registrationState.value = isRegisteredNow
                    Log.d(TAG, "DAT registration state: $regState")

                    if (isRegisteredNow && _state.value == GlassesState.CONNECTING) {
                        _state.value = GlassesState.CONNECTED
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DAT SDK", e)
        }
    }

    /**
     * Запускает регистрацию DAT SDK из Activity.
     * Требуется Activity context для DAT SDK 0.4.0+.
     */
    fun startRegistration(activity: Activity) {
        initializeDatSdk()
        if (!_registrationState.value) {
            Wearables.startRegistration(activity)
            Log.d(TAG, "DAT registration started from Activity")
        }
    }

    /**
     * Отменяет регистрацию DAT SDK (отвязывает очки от приложения).
     */
    fun unregisterDat(activity: Activity) {
        if (isDatInitialized) {
            Wearables.startUnregistration(activity)
            Log.d(TAG, "DAT unregistration started")
        }
    }

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
     * Connect to glasses via DAT SDK registration + Bluetooth HFP for audio.
     *
     * Steps:
     * 1. Initialize DAT SDK if needed
     * 2. Start DAT SDK registration flow
     * 3. Obtain BT HFP profile proxy for audio routing
     * 4. Monitor registration state for connection
     */
    @SuppressLint("MissingPermission")
    suspend fun connect() {
        if (_state.value == GlassesState.CONNECTED || _state.value == GlassesState.STREAMING_AUDIO) {
            Log.d(TAG, "Already connected or streaming, ignoring connect()")
            return
        }

        _state.value = GlassesState.CONNECTING

        try {
            // Initialize DAT SDK
            initializeDatSdk()

            // Start DAT registration (opens Meta AI companion app flow)
            // DAT SDK 0.4.0 requires Activity for registration
            if (!_registrationState.value) {
                val activity = context as? Activity
                if (activity != null) {
                    Wearables.startRegistration(activity)
                    Log.d(TAG, "DAT registration started")
                } else {
                    Log.w(TAG, "Cannot start DAT registration — Activity context required. " +
                        "Call startRegistration(activity) explicitly from Activity.")
                }
            }

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
                // If DAT SDK is registered, we're still connected for camera
                if (_registrationState.value) {
                    _state.value = GlassesState.CONNECTED
                    Log.d(TAG, "Connected via DAT SDK (no BT HFP device)")
                    return
                }
                Log.w(TAG, "No HFP device or DAT registration within timeout")
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
        if (!isAudioCapturing.compareAndSet(false, true)) {
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
            _state.value = if (connectedDevice != null || _registrationState.value) {
                GlassesState.CONNECTED
            } else {
                GlassesState.DISCONNECTED
            }
        }
    }

    // -----------------------------------------------------------------
    // Camera capture
    // -----------------------------------------------------------------

    /**
     * Проверяет разрешение на камеру очков через DAT SDK.
     *
     * @return true если разрешение выдано, false если нужно запросить.
     */
    fun hasCameraPermission(): Boolean {
        if (!isDatInitialized) return false
        return try {
            val result = Wearables.checkPermissionStatus(DatPermission.CAMERA)
            // DatResult — проверяем через getOrNull()
            val status = result.getOrNull()
            status == DatPermissionStatus.Granted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check DAT camera permission", e)
            false
        }
    }

    /**
     * Capture a single photo from the glasses camera via DAT SDK.
     *
     * Returns JPEG-encoded bytes, or null if glasses are not connected or
     * capture fails.
     */
    suspend fun capturePhoto(): ByteArray? {
        if (_state.value != GlassesState.CONNECTED && _state.value != GlassesState.STREAMING_AUDIO) {
            Log.w(TAG, "Cannot capture photo — glasses not connected (state=${_state.value})")
            return null
        }

        if (!isDatInitialized || !_registrationState.value) {
            Log.w(TAG, "Cannot capture photo — DAT SDK not ready")
            return null
        }

        val previousState = _state.value
        _state.value = GlassesState.CAPTURING_PHOTO

        return try {
            withContext(Dispatchers.IO) {
                // Используем существующую стрим-сессию или создаём временную
                val session = streamSession ?: startTemporaryStreamSession()
                if (session == null) {
                    Log.e(TAG, "Failed to create stream session for photo capture")
                    return@withContext null
                }

                val photoResult = session.capturePhoto()
                var photoBytes: ByteArray? = null

                photoResult
                    ?.onSuccess { photoData ->
                        photoBytes = extractPhotoBytes(photoData)
                    }
                    ?.onFailure { error ->
                        Log.e(TAG, "Photo capture returned error: $error")
                    }

                // Если сессия была временная, закрываем
                if (streamSession == null) {
                    session.close()
                }

                if (photoBytes != null) {
                    _cameraFrames.tryEmit(photoBytes!!)
                    Log.d(TAG, "Photo captured: ${photoBytes!!.size} bytes")
                }

                photoBytes
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
     * Start continuous camera frame streaming from glasses via DAT SDK.
     *
     * Frames are emitted to [cameraFrames] as JPEG-encoded byte arrays.
     *
     * @param quality Качество видео (MEDIUM по умолчанию для баланса BW/quality)
     * @param frameRate Частота кадров (24 fps по умолчанию)
     */
    fun startCameraStream(
        quality: VideoQuality = VideoQuality.MEDIUM,
        frameRate: Int = DEFAULT_FRAME_RATE
    ) {
        if (!isCameraStreaming.compareAndSet(false, true)) {
            Log.d(TAG, "Camera stream already running")
            return
        }

        if (!isDatInitialized) {
            Log.w(TAG, "Cannot start camera stream — DAT SDK not initialized")
            isCameraStreaming.set(false)
            return
        }

        cameraStreamJob = scope.launch {
            try {
                val config = StreamConfiguration(
                    videoQuality = quality,
                    frameRate = frameRate
                )

                val session = Wearables.startStreamSession(
                    context,
                    AutoDeviceSelector(),
                    config
                )
                streamSession = session

                Log.d(TAG, "DAT camera stream session started (quality=$quality, fps=$frameRate)")

                // Наблюдаем за состоянием стрима
                streamStateObserverJob = launch {
                    session.state.collectLatest { streamState ->
                        Log.d(TAG, "Stream state: $streamState")
                        if (streamState == StreamSessionState.STOPPED) {
                            Log.d(TAG, "Camera stream stopped by system")
                            stopCameraStreamInternal()
                        }
                    }
                }

                // Собираем видеокадры
                session.videoStream.collectLatest { frame ->
                    if (!isCameraStreaming.get()) return@collectLatest

                    val jpegBytes = extractFrameBytes(frame)
                    if (jpegBytes != null) {
                        _cameraFrames.tryEmit(jpegBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera stream error", e)
            } finally {
                streamSession?.close()
                streamSession = null
                streamStateObserverJob?.cancel()
                streamStateObserverJob = null
                isCameraStreaming.set(false)
                Log.d(TAG, "Camera stream ended")
            }
        }
    }

    /**
     * Stop continuous camera frame streaming.
     */
    fun stopCameraStream() {
        stopCameraStreamInternal()
    }

    /**
     * Проверяет, доступна ли камера очков (DAT SDK зарегистрирован + разрешение).
     */
    fun isCameraAvailable(): Boolean {
        return isDatInitialized && _registrationState.value && hasCameraPermission()
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
        isAudioCapturing.set(false)
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
        isCameraStreaming.set(false)
        cameraStreamJob?.cancel()
        cameraStreamJob = null
        streamStateObserverJob?.cancel()
        streamStateObserverJob = null

        streamSession?.close()
        streamSession = null

        Log.d(TAG, "Camera stream stopped")
    }

    /**
     * Создаёт временную стрим-сессию для одиночного фото.
     */
    private fun startTemporaryStreamSession(): StreamSession? {
        return try {
            val config = StreamConfiguration(
                videoQuality = VideoQuality.HIGH,
                frameRate = 1
            )
            Wearables.startStreamSession(context, AutoDeviceSelector(), config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start temporary stream session", e)
            null
        }
    }

    /**
     * Конвертирует VideoFrame (I420 YUV) в JPEG bytes.
     *
     * VideoFrame.buffer содержит I420 YUV данные, которые нужно
     * сконвертировать в NV21, затем в JPEG через YuvImage.
     */
    private fun extractFrameBytes(frame: VideoFrame): ByteArray? {
        return try {
            val buffer = frame.buffer
            val originalPosition = buffer.position()
            val i420Bytes = ByteArray(buffer.remaining())
            buffer.get(i420Bytes)
            buffer.position(originalPosition) // restore position for SDK reuse

            val width = frame.width
            val height = frame.height

            // Конвертация I420 → NV21 для Android YuvImage
            val nv21 = convertI420toNV21(i420Bytes, width, height)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract frame bytes", e)
            null
        }
    }

    /**
     * Конвертирует I420 (YUV 4:2:0 planar) в NV21 (YUV 4:2:0 semi-planar).
     *
     * I420 layout: [Y plane][U plane][V plane]
     * NV21 layout: [Y plane][V U interleaved]
     */
    private fun convertI420toNV21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        // Copy Y plane
        System.arraycopy(i420, 0, nv21, 0, ySize)

        // Interleave U and V planes: V first (NV21), then U
        val uOffset = ySize
        val vOffset = ySize + uvSize
        var nv21Offset = ySize

        for (i in 0 until uvSize) {
            nv21[nv21Offset++] = i420[vOffset + i] // V
            nv21[nv21Offset++] = i420[uOffset + i] // U
        }

        return nv21
    }

    /**
     * Извлекает JPEG bytes из PhotoData DAT SDK.
     *
     * PhotoData — sealed class:
     * - PhotoData.Bitmap → compress to JPEG
     * - PhotoData.HEIC → extract raw bytes (decode если нужно)
     */
    private fun extractPhotoBytes(photo: PhotoData): ByteArray? {
        return try {
            when (photo) {
                is PhotoData.Bitmap -> {
                    val stream = ByteArrayOutputStream()
                    photo.bitmap.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        90,
                        stream
                    )
                    stream.toByteArray()
                }
                is PhotoData.HEIC -> {
                    val data = photo.data
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    bytes
                }
                else -> {
                    Log.w(TAG, "Unknown PhotoData type: ${photo::class.simpleName}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract photo bytes", e)
            null
        }
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
