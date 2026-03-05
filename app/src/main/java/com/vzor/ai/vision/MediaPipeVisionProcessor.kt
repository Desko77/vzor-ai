package com.vzor.ai.vision

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facedetector.FaceDetectorOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device face and object detection через MediaPipe Tasks Vision.
 * Lazy-init: детекторы создаются при первом вызове.
 * Bundled models: blaze_face_short_range.tflite, efficientdet_lite0.tflite.
 */
@Singleton
class MediaPipeVisionProcessor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val FACE_MODEL = "blaze_face_short_range.tflite"
        private const val OBJECT_MODEL = "efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 10
        private const val MIN_CONFIDENCE = 0.4f
    }

    private val faceDetector: FaceDetector by lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(FACE_MODEL)
            .build()
        val options = FaceDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinDetectionConfidence(MIN_CONFIDENCE)
            .build()
        FaceDetector.createFromOptions(context, options)
    }

    private val objectDetector: ObjectDetector by lazy {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(OBJECT_MODEL)
            .build()
        val options = ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(MIN_CONFIDENCE)
            .build()
        ObjectDetector.createFromOptions(context, options)
    }

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
            emptyList()
        }
    }

    /**
     * Освобождает ресурсы детекторов.
     */
    fun release() {
        try { faceDetector.close() } catch (_: Exception) {}
        try { objectDetector.close() } catch (_: Exception) {}
    }
}
