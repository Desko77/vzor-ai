package com.vzor.ai.vision

import com.vzor.ai.domain.model.SceneData
import javax.inject.Inject
import javax.inject.Singleton

enum class VisionEventType {
    TEXT_APPEARED,
    TEXT_CHANGED,
    SCENE_CHANGED,
    NEW_OBJECT,
    OBJECT_REMOVED,
    FACE_DETECTED,
    FACE_LOST,
    HAND_GESTURE_DETECTED,
    HAND_GESTURE_LOST
}

data class VisionEvent(
    val type: VisionEventType,
    val description: String,
    val timestamp: Long
)

@Singleton
class EventBuilder @Inject constructor() {

    /**
     * Compares two consecutive [SceneData] snapshots and returns a list of
     * meaningful [VisionEvent]s that describe what changed between them.
     *
     * If [previous] is null, the current snapshot is treated as the initial
     * observation: any text produces TEXT_APPEARED, and the scene itself
     * triggers SCENE_CHANGED.
     */
    fun detectEvents(previous: SceneData?, current: SceneData): List<VisionEvent> {
        val events = mutableListOf<VisionEvent>()
        val now = current.timestamp

        if (previous == null) {
            // Initial observation
            if (current.text.isNotEmpty()) {
                events.add(
                    VisionEvent(
                        type = VisionEventType.TEXT_APPEARED,
                        description = "Text detected: ${current.text.joinToString("; ").take(200)}",
                        timestamp = now
                    )
                )
            }
            if (current.sceneSummary.isNotBlank()) {
                events.add(
                    VisionEvent(
                        type = VisionEventType.SCENE_CHANGED,
                        description = "Initial scene: ${current.sceneSummary.take(200)}",
                        timestamp = now
                    )
                )
            }
            return events
        }

        // --- Text events ---
        val prevTextSet = previous.text.toSet()
        val currTextSet = current.text.toSet()

        if (prevTextSet.isEmpty() && currTextSet.isNotEmpty()) {
            events.add(
                VisionEvent(
                    type = VisionEventType.TEXT_APPEARED,
                    description = "Text appeared: ${current.text.joinToString("; ").take(200)}",
                    timestamp = now
                )
            )
        } else if (prevTextSet.isNotEmpty() && currTextSet.isNotEmpty() && prevTextSet != currTextSet) {
            val added = currTextSet - prevTextSet
            val removed = prevTextSet - currTextSet
            val changeDesc = buildString {
                if (added.isNotEmpty()) append("Added: ${added.joinToString("; ").take(100)}")
                if (removed.isNotEmpty()) {
                    if (isNotEmpty()) append(". ")
                    append("Removed: ${removed.joinToString("; ").take(100)}")
                }
            }
            events.add(
                VisionEvent(
                    type = VisionEventType.TEXT_CHANGED,
                    description = "Text changed. $changeDesc",
                    timestamp = now
                )
            )
        }

        // --- Object events ---
        val prevLabels = previous.objects.map { it.label }.toSet()
        val currLabels = current.objects.map { it.label }.toSet()

        val newObjects = currLabels - prevLabels
        val removedObjects = prevLabels - currLabels

        for (label in newObjects) {
            events.add(
                VisionEvent(
                    type = VisionEventType.NEW_OBJECT,
                    description = "New object detected: $label",
                    timestamp = now
                )
            )
        }

        for (label in removedObjects) {
            events.add(
                VisionEvent(
                    type = VisionEventType.OBJECT_REMOVED,
                    description = "Object no longer visible: $label",
                    timestamp = now
                )
            )
        }

        // --- Face events ---
        val prevFaces = previous.faceCount
        val currFaces = current.faceCount

        if (prevFaces == 0 && currFaces > 0) {
            events.add(
                VisionEvent(
                    type = VisionEventType.FACE_DETECTED,
                    description = "Face detected: $currFaces ${if (currFaces == 1) "face" else "faces"} visible",
                    timestamp = now
                )
            )
        } else if (prevFaces > 0 && currFaces == 0) {
            events.add(
                VisionEvent(
                    type = VisionEventType.FACE_LOST,
                    description = "All faces lost from view",
                    timestamp = now
                )
            )
        }

        // --- Hand gesture events ---
        val prevGestures = previous.gestures.toSet()
        val currGestures = current.gestures.toSet()

        if (prevGestures.isEmpty() && currGestures.isNotEmpty()) {
            events.add(
                VisionEvent(
                    type = VisionEventType.HAND_GESTURE_DETECTED,
                    description = "Hand gesture detected: ${currGestures.joinToString(", ")}",
                    timestamp = now
                )
            )
        } else if (prevGestures.isNotEmpty() && currGestures.isEmpty()) {
            events.add(
                VisionEvent(
                    type = VisionEventType.HAND_GESTURE_LOST,
                    description = "Hand gesture no longer visible",
                    timestamp = now
                )
            )
        } else if (prevGestures != currGestures && currGestures.isNotEmpty()) {
            val newGestures = currGestures - prevGestures
            if (newGestures.isNotEmpty()) {
                events.add(
                    VisionEvent(
                        type = VisionEventType.HAND_GESTURE_DETECTED,
                        description = "New hand gesture: ${newGestures.joinToString(", ")}",
                        timestamp = now
                    )
                )
            }
        }

        // --- Scene change ---
        val sceneSummaryChanged = previous.sceneSummary != current.sceneSummary
        val significantObjectChange = (newObjects.size + removedObjects.size) >= 2

        if (sceneSummaryChanged || significantObjectChange) {
            // Only emit SCENE_CHANGED if we haven't already captured the change
            // through individual object/text events, or if the summary itself changed
            if (sceneSummaryChanged) {
                events.add(
                    VisionEvent(
                        type = VisionEventType.SCENE_CHANGED,
                        description = "Scene changed: ${current.sceneSummary.take(200)}",
                        timestamp = now
                    )
                )
            }
        }

        return events
    }
}
