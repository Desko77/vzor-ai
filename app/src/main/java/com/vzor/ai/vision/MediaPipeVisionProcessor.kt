package com.vzor.ai.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device face, object and gesture detection через MediaPipe Tasks Vision.
 * Lazy-init: детекторы создаются при первом вызове.
 * Bundled models: blaze_face_short_range.tflite, efficientdet_lite0.tflite,
 * gesture_recognizer.task.
 */
@Singleton
class MediaPipeVisionProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "MediaPipeVision"
        private const val FACE_MODEL = "blaze_face_short_range.tflite"
        private const val OBJECT_MODEL = "efficientdet_lite0.tflite"
        private const val GESTURE_MODEL = "gesture_recognizer.task"
        private const val MAX_RESULTS = 10
        private const val MIN_CONFIDENCE = 0.4f
        private const val GESTURE_MIN_CONFIDENCE = 0.5f
    }

    private val _faceDetector = lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(FACE_MODEL)
            .build()
        val options = FaceDetector.FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinDetectionConfidence(MIN_CONFIDENCE)
            .build()
        FaceDetector.createFromOptions(context, options)
    }
    private val faceDetector: FaceDetector by _faceDetector

    private val _objectDetector = lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(OBJECT_MODEL)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(MIN_CONFIDENCE)
            .build()
        ObjectDetector.createFromOptions(context, options)
    }
    private val objectDetector: ObjectDetector by _objectDetector

    private val _gestureRecognizer = lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(GESTURE_MODEL)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(GESTURE_MIN_CONFIDENCE)
            .setMinHandPresenceConfidence(GESTURE_MIN_CONFIDENCE)
            .setMinTrackingConfidence(GESTURE_MIN_CONFIDENCE)
            .build()
        GestureRecognizer.createFromOptions(context, options)
    }
    private val gestureRecognizer: GestureRecognizer by _gestureRecognizer

    /**
     * Обнаруживает лица на изображении.
     * @return Список [SceneElement.FaceDetection] или пустой список при ошибке.
     */
    fun detectFaces(imageBytes: ByteArray): List<SceneElement.FaceDetection> {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return emptyList()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = faceDetector.detect(mpImage)

            result.detections().map { detection ->
                val keypoints = detection.keypoints().orElse(emptyList())
                val landmarks = keypoints.map { kp ->
                    PointF(kp.x(), kp.y())
                }
                SceneElement.FaceDetection(
                    landmarks = landmarks,
                    confidence = detection.categories().firstOrNull()?.score() ?: 0f,
                    headEulerAngles = emptyList()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face detection failed", e)
            emptyList()
        }
    }

    /**
     * Обнаруживает объекты на изображении.
     * @return Список [SceneElement.DetectedObject] или пустой список при ошибке.
     */
    fun detectObjects(imageBytes: ByteArray): List<SceneElement.DetectedObject> {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return emptyList()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = objectDetector.detect(mpImage)

            result.detections().flatMap { detection ->
                val bbox = detection.boundingBox()
                val rectF = RectF(bbox.left.toFloat(), bbox.top.toFloat(),
                    bbox.right.toFloat(), bbox.bottom.toFloat())
                detection.categories().map { category ->
                    SceneElement.DetectedObject(
                        label = category.categoryName() ?: "unknown",
                        confidence = category.score(),
                        bbox = rectF
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Object detection failed", e)
            emptyList()
        }
    }

    /**
     * Обнаруживает жесты рук на изображении через MediaPipe GestureRecognizer.
     * Поддерживаемые жесты: Closed_Fist, Open_Palm, Pointing_Up, Thumb_Down,
     * Thumb_Up, Victory, ILoveYou, None.
     *
     * @return Список строк с именами обнаруженных жестов, или пустой список при ошибке.
     */
    fun detectGestures(imageBytes: ByteArray): List<String> {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return emptyList()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = gestureRecognizer.recognize(mpImage)

            result.gestures().flatMap { gestureList ->
                gestureList
                    .filter { it.score() >= GESTURE_MIN_CONFIDENCE }
                    .filter { it.categoryName() != "None" }
                    .map { it.categoryName() ?: "unknown" }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Gesture detection failed", e)
            emptyList()
        }
    }

    /**
     * Результат пакетного анализа всех детекторов за один проход.
     */
    data class BatchResult(
        val faces: List<SceneElement.FaceDetection>,
        val objects: List<SceneElement.DetectedObject>,
        val gestures: List<String>
    )

    /**
     * Пакетный анализ: декодирует Bitmap один раз и прогоняет через все три детектора.
     * Экономит 2 лишних декодирования по сравнению с вызовом detect* по отдельности.
     */
    fun detectAll(imageBytes: ByteArray): BatchResult {
        val bitmap = try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode bitmap for batch detection", e)
            null
        }

        if (bitmap == null) {
            return BatchResult(emptyList(), emptyList(), emptyList())
        }

        val mpImage = BitmapImageBuilder(bitmap).build()

        val faces = try {
            faceDetector.detect(mpImage).detections().map { detection ->
                val keypoints = detection.keypoints().orElse(emptyList())
                val landmarks = keypoints.map { kp -> PointF(kp.x(), kp.y()) }
                SceneElement.FaceDetection(
                    landmarks = landmarks,
                    confidence = detection.categories().firstOrNull()?.score() ?: 0f,
                    headEulerAngles = emptyList()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face detection failed in batch", e)
            emptyList()
        }

        val objects = try {
            objectDetector.detect(mpImage).detections().flatMap { detection ->
                val bbox = detection.boundingBox()
                val rectF = RectF(bbox.left.toFloat(), bbox.top.toFloat(),
                    bbox.right.toFloat(), bbox.bottom.toFloat())
                detection.categories().map { category ->
                    SceneElement.DetectedObject(
                        label = category.categoryName() ?: "unknown",
                        confidence = category.score(),
                        bbox = rectF
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Object detection failed in batch", e)
            emptyList()
        }

        val gestures = try {
            gestureRecognizer.recognize(mpImage).gestures().flatMap { gestureList ->
                gestureList
                    .filter { it.score() >= GESTURE_MIN_CONFIDENCE }
                    .filter { it.categoryName() != "None" }
                    .map { it.categoryName() ?: "unknown" }
            }.distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Gesture detection failed in batch", e)
            emptyList()
        }

        return BatchResult(faces, objects, gestures)
    }

    /**
     * Освобождает ресурсы детекторов.
     */
    fun release() {
        if (_faceDetector.isInitialized()) {
            try {
                faceDetector.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close faceDetector", e)
            }
        }
        if (_objectDetector.isInitialized()) {
            try {
                objectDetector.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close objectDetector", e)
            }
        }
        if (_gestureRecognizer.isInitialized()) {
            try {
                gestureRecognizer.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close gestureRecognizer", e)
            }
        }
    }
}
