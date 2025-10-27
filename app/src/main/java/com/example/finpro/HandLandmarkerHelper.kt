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

        // 1. Dapatkan semua koordinat
        val coords = landmarks.map { Triple(it.x(), it.y(), it.z()) }

        // 2. Koordinat relatif terhadap pergelangan tangan (index 0)
        val wrist = coords[0]
        val relativeCoords = coords.map {
            Triple(it.first - wrist.first, it.second - wrist.second, it.third - wrist.third)
        }

        // 3. Hitung jarak Euclidean dari pergelangan tangan dan cari max distance
        var maxDist = 0f
        for (coord in relativeCoords) {
            val dist = sqrt(coord.first * coord.first + coord.second * coord.second + coord.third * coord.third)
            if (dist > maxDist) {
                maxDist = dist
            }
        }

        // Handle jika maxDist 0 (misal hanya 1 landmark terdeteksi)
        if (maxDist < 1e-6) { // Gunakan epsilon untuk perbandingan float
            return FloatArray(63) { 0f }
        }

        // 4. Normalisasi dengan membagi maxDist dan flatten
        val normalizedFlattened = FloatArray(63)
        var index = 0
        for (coord in relativeCoords) {
            if (index >= 63) break // Safety break
            normalizedFlattened[index++] = coord.first / maxDist
            normalizedFlattened[index++] = coord.second / maxDist
            normalizedFlattened[index++] = coord.third / maxDist
        }

        // Isi sisa array dengan 0 jika landmark < 21 (seharusnya tidak terjadi)
        while (index < 63) {
            normalizedFlattened[index++] = 0f
        }

        return normalizedFlattened
    }
}