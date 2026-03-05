package com.vzor.ai.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent

class MusicAction(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun play(query: String): ActionResult {
        return try {
            if (query.isNotBlank()) {
                // Try to play specific content via MediaStore search
                val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(SearchManager_QUERY, query)
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (context.packageManager.resolveActivity(mediaIntent, 0) != null) {
                    context.startActivity(mediaIntent)
                    ActionResult(true, "Воспроизведение: $query")
                } else {
                    // Fallback: try opening a music app with search
                    val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                        putExtra(SearchManager_QUERY, query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    // Try popular music apps
                    val musicApps = listOf(
                        "com.spotify.music",
                        "com.google.android.apps.youtube.music",
                        "ru.yandex.music",
                        "deezer.android.app"
                    )

                    var launched = false
                    for (app in musicApps) {
                        try {
                            val appIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                                putExtra(SearchManager_QUERY, query)
                                setPackage(app)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            if (context.packageManager.resolveActivity(appIntent, 0) != null) {
                                context.startActivity(appIntent)
                                launched = true
                                break
                            }
                        } catch (_: Exception) {
                            continue
                        }
                    }

                    if (launched) {
                        ActionResult(true, "Воспроизведение: $query")
                    } else {
                        // Last resort: send play key event to resume any player
                        sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                        ActionResult(true, "Воспроизведение музыки")
                    }
                }
            } else {
                // No query — just resume playback
                sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY)
                ActionResult(true, "Воспроизведение музыки")
            }
        } catch (e: Exception) {
            ActionResult(false, "Не удалось воспроизвести музыку: ${e.message}")
        }
    }

    fun pause(): ActionResult {
        return try {
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ActionResult(true, "Музыка на паузе")
        } catch (e: Exception) {
            ActionResult(false, "Не удалось поставить на паузу: ${e.message}")
        }
    }

    fun next(): ActionResult {
        return try {
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionResult(true, "Следующий трек")
        } catch (e: Exception) {
            ActionResult(false, "Не удалось переключить трек: ${e.message}")
        }
    }

    fun previous(): ActionResult {
        return try {
            sendMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ActionResult(true, "Предыдущий трек")
        } catch (e: Exception) {
            ActionResult(false, "Не удалось переключить трек: ${e.message}")
        }
    }

    private fun sendMediaKeyEvent(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()

        val downEvent = KeyEvent(
            eventTime,
            eventTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0
        )
        audioManager.dispatchMediaKeyEvent(downEvent)

        val upEvent = KeyEvent(
            eventTime,
            eventTime,
            KeyEvent.ACTION_UP,
            keyCode,
            0
        )
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    companion object {
        // Using string constant to avoid direct SearchManager dependency import issues
        private const val SearchManager_QUERY = "query"
    }
}
