package com.family.barbafruit

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Immutable snapshot of the tracked face, published to the game thread.
 *
 * All values are normalized to [0,1] in *screen* orientation (already
 * mirrored for the front camera, so moving your head LEFT moves the
 * value LEFT on screen — natural "mirror" control for a child).
 */
data class FaceFrame(
    val hasFace: Boolean,
    val normX: Float,      // smoothed horizontal center of the face
    val normY: Float,      // smoothed vertical center of the face
    val sizeRatio: Float,  // face box width / frame width (distance proxy)
    val timestampMs: Long
)

/**
 * CameraX [ImageAnalysis.Analyzer] that streams frames into ML Kit's
 * on-device fast face detector and applies a low-pass filter to the
 * resulting coordinates before handing them to the game.
 *
 * Tuned for 1st-gen Portal (Snapdragon 624-class SoC):
 *  - PERFORMANCE_MODE_FAST, no landmarks, no classification
 *  - enableTracking() locks onto one face ID so a sibling walking by
 *    in the background doesn't steal the bear
 *  - KEEP_ONLY_LATEST backpressure (set on the ImageAnalysis use case)
 *    means we never queue stale frames when the detector falls behind
 */
class FaceTrackerAnalyzer(
    private val onFrame: (FaceFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.15f) // ignore tiny background faces
            .enableTracking()
            .build()
    )

    // ---- Low-pass filter state -------------------------------------------
    // smoothed += ALPHA * (raw - smoothed)
    // ALPHA 0.35 ≈ responsive but jitter-free at ~25-30 fps analysis rate.
    private var smoothX = 0.5f
    private var smoothY = 0.5f
    private var smoothSize = 0f
    private var initialized = false
    private var lockedTrackingId: Int? = null
    private var framesWithoutFace = 0

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        // ML Kit reports bounding boxes in the ROTATED coordinate space,
        // so swap width/height for 90°/270° rotations when normalizing.
        val frameW = if (rotation == 90 || rotation == 270)
            imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val frameH = if (rotation == 90 || rotation == 270)
            imageProxy.width.toFloat() else imageProxy.height.toFloat()

        detector.process(input)
            .addOnSuccessListener { faces ->
                // Prefer the face we already locked onto; otherwise the largest.
                val face = faces.firstOrNull { it.trackingId == lockedTrackingId }
                    ?: faces.maxByOrNull { it.boundingBox.width() }

                if (face != null) {
                    lockedTrackingId = face.trackingId ?: lockedTrackingId
                    framesWithoutFace = 0

                    val box = face.boundingBox
                    // Mirror X for the front camera (selfie view).
                    val rawX = 1f - (box.exactCenterX() / frameW)
                    val rawY = box.exactCenterY() / frameH
                    val rawSize = box.width() / frameW

                    if (!initialized) {
                        smoothX = rawX; smoothY = rawY; smoothSize = rawSize
                        initialized = true
                    } else {
                        smoothX += ALPHA * (rawX - smoothX)
                        smoothY += ALPHA * (rawY - smoothY)
                        smoothSize += ALPHA * (rawSize - smoothSize)
                    }

                    onFrame(
                        FaceFrame(
                            hasFace = true,
                            normX = smoothX.coerceIn(0f, 1f),
                            normY = smoothY.coerceIn(0f, 1f),
                            sizeRatio = smoothSize,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                } else {
                    // Brief dropouts are normal on this hardware; only report
                    // "no face" after a few consecutive misses, and keep the
                    // last smoothed position so the bear doesn't snap away.
                    framesWithoutFace++
                    if (framesWithoutFace > MISS_TOLERANCE) {
                        lockedTrackingId = null
                        onFrame(
                            FaceFrame(false, smoothX, smoothY, smoothSize,
                                System.currentTimeMillis())
                        )
                    }
                }
            }
            .addOnCompleteListener {
                // CRITICAL: close the frame or CameraX stops delivering.
                imageProxy.close()
            }
    }

    fun shutdown() = detector.close()

    private companion object {
        const val ALPHA = 0.35f
        const val MISS_TOLERANCE = 5
    }
}
