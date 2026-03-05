package com.vzor.ai.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vzor.ai.MainActivity
import com.vzor.ai.R
import com.vzor.ai.domain.model.GlassesState
import com.vzor.ai.glasses.AudioStreamHandler
import com.vzor.ai.glasses.GlassesManager
import com.vzor.ai.speech.NoiseProfileDetector
import com.vzor.ai.speech.WakeWordListener
import com.vzor.ai.speech.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the Vzor AI assistant running in the background.
 *
 * Responsibilities:
 * 1. Maintain a persistent notification showing glasses connection state
 * 2. Manage the audio capture lifecycle (start/stop with glasses connection)
 * 3. Run wake word detection in the background during IDLE state
 * 4. Periodically update the noise profile detector
 * 5. Keep the process alive for continuous assistant availability
 *
 * Lifecycle:
 * - Started from MainActivity or when glasses connect
 * - Runs as a foreground service with FOREGROUND_SERVICE_CONNECTED_DEVICE type
 * - Stopped explicitly or when the user disables the assistant
 *
 * Actions (received via Intent):
 * - ACTION_START: Start the service and begin monitoring
 * - ACTION_STOP: Stop the service gracefully
 * - ACTION_CONNECT_GLASSES: Trigger glasses connection
 * - ACTION_DISCONNECT_GLASSES: Disconnect glasses
 */
@AndroidEntryPoint
class VzorAssistantService : Service() {

    companion object {
        private const val TAG = "VzorAssistantService"

        const val ACTION_START = "com.vzor.ai.action.START_ASSISTANT"
        const val ACTION_STOP = "com.vzor.ai.action.STOP_ASSISTANT"
        const val ACTION_CONNECT_GLASSES = "com.vzor.ai.action.CONNECT_GLASSES"
        const val ACTION_DISCONNECT_GLASSES = "com.vzor.ai.action.DISCONNECT_GLASSES"

        private const val NOTIFICATION_CHANNEL_ID = "vzor_assistant_channel"
        private const val NOTIFICATION_ID = 1001

        /** Interval for noise profile updates during IDLE state (ms). */
        private const val NOISE_PROFILE_UPDATE_INTERVAL_MS = 2000L

        /**
         * Start the assistant service.
         */
        fun start(context: Context) {
            val intent = Intent(context, VzorAssistantService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the assistant service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, VzorAssistantService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var glassesManager: GlassesManager
    @Inject lateinit var audioStreamHandler: AudioStreamHandler
    @Inject lateinit var wakeWordService: WakeWordService
    @Inject lateinit var noiseProfileDetector: NoiseProfileDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var glassesStateObserverJob: Job? = null
    private var audioCaptureJob: Job? = null
    private var noiseProfileJob: Job? = null

    // -----------------------------------------------------------------
    // Service lifecycle
    // -----------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Starting assistant service")
                startForegroundWithNotification()
                startObservingGlassesState()
                startAudioPipeline()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping assistant service")
                stopSelf()
            }
            ACTION_CONNECT_GLASSES -> {
                serviceScope.launch {
                    glassesManager.connect()
                }
            }
            ACTION_DISCONNECT_GLASSES -> {
                serviceScope.launch {
                    glassesManager.disconnect()
                }
            }
            else -> {
                // Service restarted by system — resume monitoring
                Log.d(TAG, "Service restarted, resuming")
                startForegroundWithNotification()
                startObservingGlassesState()
                startAudioPipeline()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopAudioPipeline()
        glassesStateObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Vzor AI Ассистент",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомление фонового сервиса Vzor AI"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(glassesManager.state.value)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(glassesState: GlassesState): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text, icon) = when (glassesState) {
            GlassesState.DISCONNECTED -> Triple(
                "Vzor AI",
                "Ассистент активен • Очки не подключены",
                android.R.drawable.stat_sys_data_bluetooth
            )
            GlassesState.CONNECTING -> Triple(
                "Vzor AI",
                "Подключение к очкам…",
                android.R.drawable.stat_sys_data_bluetooth
            )
            GlassesState.CONNECTED -> Triple(
                "Vzor AI",
                "Очки подключены • Готов к работе",
                android.R.drawable.stat_sys_data_bluetooth
            )
            GlassesState.STREAMING_AUDIO -> Triple(
                "Vzor AI",
                "Слушаю через очки…",
                android.R.drawable.ic_btn_speak_now
            )
            GlassesState.CAPTURING_PHOTO -> Triple(
                "Vzor AI",
                "Съёмка через очки…",
                android.R.drawable.ic_menu_camera
            )
            GlassesState.ERROR -> Triple(
                "Vzor AI",
                "Ошибка подключения к очкам",
                android.R.drawable.stat_notify_error
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(glassesState: GlassesState) {
        val notification = buildNotification(glassesState)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // -----------------------------------------------------------------
    // Glasses state observation
    // -----------------------------------------------------------------

    private fun startObservingGlassesState() {
        glassesStateObserverJob?.cancel()
        glassesStateObserverJob = serviceScope.launch {
            glassesManager.state.collectLatest { state ->
                Log.d(TAG, "Glasses state changed: $state")
                updateNotification(state)

                when (state) {
                    GlassesState.CONNECTED -> {
                        // Glasses connected — restart audio pipeline to use BT mic
                        restartAudioPipeline()
                    }
                    GlassesState.DISCONNECTED -> {
                        // Glasses disconnected — restart audio pipeline to use device mic
                        restartAudioPipeline()
                    }
                    GlassesState.ERROR -> {
                        // On error, keep using whatever audio source is available
                        Log.w(TAG, "Glasses in error state, continuing with current audio source")
                    }
                    else -> { /* STREAMING_AUDIO, CAPTURING_PHOTO, CONNECTING — no pipeline change */ }
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Audio pipeline management
    // -----------------------------------------------------------------

    /**
     * Start the audio capture pipeline:
     * 1. Begin capturing audio (from BT mic or device mic)
     * 2. Feed audio to wake word detection
     * 3. Periodically update noise profile
     */
    private fun startAudioPipeline() {
        stopAudioPipeline()

        val audioFlow = audioStreamHandler.startCapture()

        // Start wake word detection
        wakeWordService.setListener(object : WakeWordListener {
            override fun onWakeWordDetected() {
                Log.i(TAG, "Wake word detected! Transitioning to LISTENING state")
                // The VoiceOrchestrator will handle the state transition
                // via the VoiceEvent.WakeWordDetected event
            }
        })
        wakeWordService.startListening(audioFlow)

        // Start noise profile updates
        noiseProfileJob = serviceScope.launch(Dispatchers.Default) {
            val noiseBuffer = mutableListOf<ByteArray>()
            var lastUpdateTime = System.currentTimeMillis()

            audioStreamHandler.startCapture().collect { chunk ->
                if (!isActive) return@collect

                noiseBuffer.add(chunk)

                val now = System.currentTimeMillis()
                if (now - lastUpdateTime >= NOISE_PROFILE_UPDATE_INTERVAL_MS) {
                    // Merge buffered chunks and update noise profile
                    val totalSize = noiseBuffer.sumOf { it.size }
                    if (totalSize > 0) {
                        val merged = ByteArray(totalSize)
                        var offset = 0
                        for (buf in noiseBuffer) {
                            buf.copyInto(merged, offset)
                            offset += buf.size
                        }
                        noiseProfileDetector.updateFromAudio(merged)
                    }
                    noiseBuffer.clear()
                    lastUpdateTime = now
                }
            }
        }

        Log.d(TAG, "Audio pipeline started")
    }

    /**
     * Stop the audio capture pipeline and release resources.
     */
    private fun stopAudioPipeline() {
        wakeWordService.stopListening()
        audioStreamHandler.stopCapture()
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        noiseProfileJob?.cancel()
        noiseProfileJob = null
        Log.d(TAG, "Audio pipeline stopped")
    }

    /**
     * Restart the audio pipeline (e.g., when audio source changes).
     */
    private fun restartAudioPipeline() {
        Log.d(TAG, "Restarting audio pipeline")
        noiseProfileDetector.reset()
        stopAudioPipeline()
        // Small delay to let BT SCO settle
        serviceScope.launch {
            delay(500)
            startAudioPipeline()
        }
    }
}
