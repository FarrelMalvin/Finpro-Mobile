package com.example.finpro

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

private data class LandmarkData(val x: Float, val y: Float, val z: Float)

class HandLandmarkerHelper(
    private val context: Context,
    private val onResult: (FloatArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d("HandLandmarker", "✅ MediaPipe initialized successfully")
        } catch (e: Exception) {
            Log.e("HandLandmarker", "❌ Error initializing MediaPipe", e)
            onError("Error loading hand landmarker: ${e.message}")
        }
    }

    fun detectHand(bitmap: Bitmap, isFrontCamera: Boolean = true) {
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker?.detect(mpImage)

            Log.d("DEBUG_HELPER", "Hands detected: ${result?.landmarks()?.size ?: 0}")

            val keypoints = processLandmarksForModel(
                result?.landmarks() ?: emptyList(),
                result?.handedness() ?: emptyList(),
                isFrontCamera
            )

            val nonZeroCount = keypoints.count { it != 0f }
            Log.d("DEBUG_HELPER", "Non-zero values: $nonZeroCount/126")

            if (nonZeroCount > 0) {
                val first9 = keypoints.take(9).joinToString(", ") { "%.3f".format(it) }
                Log.d("DEBUG_HELPER", "First 9 (Left Slot): [$first9]")

                val rightSlot = keypoints.drop(63).take(9).joinToString(", ") { "%.3f".format(it) }
                Log.d("DEBUG_HELPER", "First 9 (Right Slot): [$rightSlot]")
            }

            onResult(keypoints)

        } catch (e: Exception) {
            Log.e("HandLandmarker", "Error detecting hand: ${e.message}", e)
            onError("Error: ${e.message}")
        }
    }

    private fun normalizeLandmarks(hand: List<LandmarkData>): FloatArray {
        val totalFeatures = 63
        val result = FloatArray(totalFeatures)
        if (hand.isEmpty()) return result

        val wrist = hand[0]
        val relativeCoords = mutableListOf<Triple<Float, Float, Float>>()

        for (lm in hand) {
            relativeCoords.add(Triple(
                lm.x - wrist.x,
                lm.y - wrist.y,
                lm.z - wrist.z
            ))
        }

        var maxDist = 0.0f
        for (coords in relativeCoords) {
            val dist = sqrt(
                coords.first * coords.first +
                        coords.second * coords.second +
                        coords.third * coords.third
            )
            if (dist > maxDist) {
                maxDist = dist
            }
        }

        if (maxDist < 1e-6f) {
            return result
        }

        for (i in relativeCoords.indices) {
            result[i * 3 + 0] = relativeCoords[i].first / maxDist
            result[i * 3 + 1] = relativeCoords[i].second / maxDist
            result[i * 3 + 2] = relativeCoords[i].third / maxDist
        }
        return result
    }

    /**
     * FUNGSI YANG DIPERBAIKI: Deteksi handedness SETELAH mirroring
     */
    private fun processLandmarksForModel(
        allHands: List<List<NormalizedLandmark>>,
        handednessList: List<List<Category>>,
        isFrontCamera: Boolean
    ): FloatArray {
        val totalSize = 126
        val result = FloatArray(totalSize)

        for (i in allHands.indices) {
            val rawHand = allHands[i]
            val originalHandedness = handednessList[i][0].categoryName()

            // STEP 1: Mirror data RAW & Flip Y untuk konsistensi dengan Python
            val mirroredHand = mutableListOf<LandmarkData>()
            for (lm in rawHand) {
                mirroredHand.add(LandmarkData(
                    x = if (isFrontCamera) 1f - lm.x() else lm.x(),
                    y = 1f - lm.y(),  // PENTING: Flip Y untuk match dengan Python
                    z = lm.z()
                ))
            }

            // STEP 2: Normalisasi
            val normalizedFeatures = normalizeLandmarks(mirroredHand)

            // STEP 3: PERBAIKAN - Tentukan handedness SETELAH mirroring
            // Jika front camera, handedness harus di-flip juga!
            val actualHandedness = if (isFrontCamera) {
                // Front camera: flip handedness
                if (originalHandedness == "Right") "Left" else "Right"
            } else {
                // Back camera: pakai original
                originalHandedness
            }

            // STEP 4: Slotting berdasarkan handedness yang sudah benar
            val offset = if (actualHandedness == "Right") 63 else 0

            Log.d("DEBUG_HELPER", "Hand $i: Original=$originalHandedness, After mirror=$actualHandedness, Offset=$offset")

            System.arraycopy(normalizedFeatures, 0, result, offset, normalizedFeatures.size)
        }
        return result
    }

    fun close() {
        handLandmarker?.close()
    }
}