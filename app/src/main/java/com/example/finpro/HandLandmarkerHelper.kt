package com.example.finpro

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.min
import kotlin.math.sqrt
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark


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
                .setNumHands(2) // Detect 2 hands
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


    fun detectHand(bitmap: Bitmap) {
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker?.detect(mpImage)

            val flatLandmarks = FloatArray(126) { 0f } // Final array 126

            if (result != null && result.landmarks().isNotEmpty()) {
                val detectedHandsLandmarks = result.landmarks()
                val detectedHandsHandedness = result.handednesses()

                for (i in detectedHandsLandmarks.indices) {
                    if (i >= detectedHandsHandedness.size) break

                    val rawHandLandmarks = detectedHandsLandmarks[i] // List<NormalizedLandmark> mentah
                    val handednessInfoList = detectedHandsHandedness[i]
                    val handednessLabel = handednessInfoList.firstOrNull()?.categoryName()


                    val normalizedDataForOneHand = normalizeLandmarks(rawHandLandmarks) // Output FloatArray(63)
                    // --- AKHIR PANGGILAN NORMALISASI ---

                    val baseIdxOffset = when (handednessLabel) {
                        "Right" -> 0
                        "Left"  -> 63
                        else    -> continue
                    }

                    // --- SALIN DATA YANG SUDAH DINORMALISASI ---
                    // Salin 63 float hasil normalisasi ke bagian array 126 yang benar
                    normalizedDataForOneHand.copyInto(
                        destination = flatLandmarks,
                        destinationOffset = baseIdxOffset,
                        startIndex = 0,
                        endIndex = min(normalizedDataForOneHand.size, 63) // Salin maksimal 63
                    )
                    // --- AKHIR PENYALINAN ---
                }
                onResult(flatLandmarks) // Kirim hasil 126 yang sudah dinormalisasi & diurutkan
            } else {
                onResult(flatLandmarks) // Kirim array nol
            }

        } catch (e: Exception) {
            Log.e("HandLandmarker", "Error detecting hand: ${e.message}", e)
            onError("Error: ${e.message}")
        }
    }

    fun close() {
        handLandmarker?.close()
    }
    private fun normalizeLandmarks(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): FloatArray {
        if (landmarks.isEmpty()) {
            return FloatArray(63) { 0f }
        }

        // 1. Dapatkan semua koordinat sebagai array 2D (sama persis dengan Python)
        val coords = Array(landmarks.size) { i ->
            floatArrayOf(landmarks[i].x(), landmarks[i].y(), landmarks[i].z())
        }

        // 2. Koordinat relatif terhadap pergelangan tangan (index 0) - SAMA PERSIS dengan Python
        val wrist = coords[0]
        val relativeCoords = Array(coords.size) { i ->
            floatArrayOf(
                coords[i][0] - wrist[0],
                coords[i][1] - wrist[1], 
                coords[i][2] - wrist[2]
            )
        }

        // 3. Hitung jarak Euclidean dan cari max distance - SAMA PERSIS dengan Python
        var maxDist = 0f
        for (coord in relativeCoords) {
            val dist = sqrt(coord[0] * coord[0] + coord[1] * coord[1] + coord[2] * coord[2])
            if (dist > maxDist) {
                maxDist = dist
            }
        }

        // Handle jika maxDist 0 (sama dengan Python)
        if (maxDist < 1e-6) {
            return FloatArray(63) { 0f }
        }

        // 4. Normalisasi dengan membagi maxDist dan flatten - SAMA PERSIS dengan Python
        val normalizedFlattened = FloatArray(63)
        var index = 0
        for (coord in relativeCoords) {
            if (index >= 63) break
            normalizedFlattened[index++] = coord[0] / maxDist
            normalizedFlattened[index++] = coord[1] / maxDist
            normalizedFlattened[index++] = coord[2] / maxDist
        }

        // Debug logging untuk memverifikasi normalisasi
        Log.d("NormalizeDebug", "Max distance: $maxDist")
        Log.d("NormalizeDebug", "First 6 normalized values: ${normalizedFlattened.take(6).joinToString(", ")}")

        return normalizedFlattened
    }
}