package com.vzor.ai.vision

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles images shared to Vzor via Android ACTION_SEND intent.
 *
 * Primary use case: user imports full-resolution 12MP photos from
 * Ray-Ban Meta glasses via Meta View app, then shares them to Vzor
 * for AI vision analysis (scene description, text reading, etc.).
 *
 * Emits image bytes to [sharedImages] flow, which is collected by
 * VisionService or ChatViewModel for analysis routing.
 */
@Singleton
class SharedImageHandler @Inject constructor() {

    companion object {
        private const val TAG = "SharedImageHandler"
        private const val MAX_IMAGE_SIZE = 20L * 1024 * 1024 // 20MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sharedImages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)

    /** Emits image bytes when a photo is shared to Vzor via ACTION_SEND. */
    val sharedImages: SharedFlow<ByteArray> = _sharedImages.asSharedFlow()

    /**
     * Reads image bytes from the shared URI and emits to [sharedImages].
     * Checks file size before loading into RAM to prevent OOM.
     */
    fun handleSharedImage(imageUri: Uri, contentResolver: ContentResolver) {
        scope.launch {
            try {
                // Check file size before reading into memory
                val fileSize = getFileSize(imageUri, contentResolver)
                if (fileSize != null && fileSize > MAX_IMAGE_SIZE) {
                    Log.w(TAG, "Image too large ($fileSize bytes, max=$MAX_IMAGE_SIZE), skipping")
                    return@launch
                }

                val bytes = contentResolver.openInputStream(imageUri)?.use { stream ->
                    stream.readBytes()
                }

                if (bytes != null && bytes.size <= MAX_IMAGE_SIZE) {
                    Log.d(TAG, "Shared image loaded: ${bytes.size} bytes")
                    _sharedImages.emit(bytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read shared image", e)
            }
        }
    }

    private fun getFileSize(uri: Uri, contentResolver: ContentResolver): Long? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
