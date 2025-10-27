package com.example.finpro

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import com.example.finpro.ml.ModelBisindo
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                HandSignApp()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HandSignApp() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current
    var prediction by remember { mutableStateOf("Menunggu input...") }
    var debug by remember { mutableStateOf("") }
    var showDebugInfo by remember { mutableStateOf(true) }
    var accuracyStats by remember { mutableStateOf("Akurasi: 0%") }

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreviewWithAnalyzer(context) { label, debugInfo ->
                prediction = label
                debug = debugInfo
                // Update accuracy stats based on confidence
                val confidenceMatch = Regex("""(\d+\.?\d*)%""").find(debugInfo)
                confidenceMatch?.let {
                    val confidence = it.groupValues[1].toFloatOrNull() ?: 0f
                    accuracyStats = "Akurasi: ${String.format("%.1f%%", confidence)}"
                }
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("Mohon izinkan akses kamera", modifier = Modifier.padding(16.dp))
            }
        }

        // Debug toggle dan info di top
        Box(
            contentAlignment = Alignment.TopEnd,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Toggle debug info
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "Debug",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Switch(
                            checked = showDebugInfo,
                            onCheckedChange = { showDebugInfo = it },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                // Accuracy stats
                if (showDebugInfo) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = accuracyStats,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
        
        // Debug info detail
        if (showDebugInfo && debug.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.TopStart,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = debug,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // Prediction overlay dengan tampilan yang lebih jelas
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Huruf yang terdeteksi dengan ukuran besar
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = prediction,
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
                
                // Status singkat di bawah huruf
                if (showDebugInfo && debug.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = debug,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewWithAnalyzer(
    context: Context,
    onResult: (String, String) -> Unit
) {
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(
                            Executors.newSingleThreadExecutor(),
                            HandSignAnalyzer(ctx, onResult)
                        )
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        ctx as ComponentActivity,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analyzer
                    )
                } catch (e: Exception) {
                    Log.e("Camera", "Error binding camera: ", e)
                }
            }, executor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

class HandSignAnalyzer(
    private val context: Context,
    private val onResult: (String, String) -> Unit
) : ImageAnalysis.Analyzer {

    private val model = InterpreterHelper(context)
    private val labels = try {
        context.assets.open("labels.txt").bufferedReader().readLines()
    } catch (e: Exception) {
        Log.e("HandSignAnalyzer", "Error loading labels", e)
        ('A'..'Z').map { it.toString() }
    }

    // Smoothing untuk mengurangi flickering
    private val predictionHistory = mutableListOf<String>()
    private val maxHistorySize = 5
    private var lastStablePrediction = ""
    private var stableCount = 0
    private val minStableCount = 3

    // Dynamic confidence threshold
    private var dynamicThreshold = 0.6f
    private val minThreshold = 0.4f
    private val maxThreshold = 0.8f

    private val handLandmarkerHelper: HandLandmarkerHelper = HandLandmarkerHelper(
        context = context,
        onResult = { landmarks ->
            val results = model.classify(landmarks)

            if (results.isNotEmpty()) {
                val best = results.maxByOrNull { it.second }
                val label = labels.getOrNull(best?.first ?: -1) ?: "?"
                val confidence = best?.second ?: 0f

                // Update dynamic threshold berdasarkan confidence
                updateDynamicThreshold(confidence)

                // Tambahkan prediksi ke history untuk smoothing
                predictionHistory.add(label)
                if (predictionHistory.size > maxHistorySize) {
                    predictionHistory.removeAt(0)
                }

                // Hitung prediksi yang paling sering muncul
                val mostFrequentPrediction = predictionHistory.groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }?.key ?: label

                // Cek apakah prediksi sudah stabil
                if (mostFrequentPrediction == lastStablePrediction) {
                    stableCount++
                } else {
                    stableCount = 1
                    lastStablePrediction = mostFrequentPrediction
                }

                // Hitung statistik landmarks
                val validLandmarks = landmarks.count { it != 0f }
                val landmarkRatio = validLandmarks.toFloat() / landmarks.size
                
                // Tampilkan hasil berdasarkan confidence dan stabilitas
                when {
                    confidence > dynamicThreshold && stableCount >= minStableCount -> {
                        onResult(
                            "🔤 $mostFrequentPrediction",
                            "✅ Akurat: ${String.format("%.1f%%", confidence * 100)} | Stabil: $stableCount | Landmarks: ${String.format("%.1f%%", landmarkRatio * 100)}"
                        )
                    }
                    confidence > minThreshold -> {
                        onResult(
                            "🔤 $mostFrequentPrediction",
                            "⚠️ Sedang: ${String.format("%.1f%%", confidence * 100)} | Stabil: $stableCount | Landmarks: ${String.format("%.1f%%", landmarkRatio * 100)}"
                        )
                    }
                    else -> {
                        onResult(
                            "Menunggu input...",
                            "❌ Rendah: ${String.format("%.1f%%", confidence * 100)} | Landmarks: ${String.format("%.1f%%", landmarkRatio * 100)} | Threshold: ${String.format("%.1f%%", dynamicThreshold * 100)}"
                        )
                    }
                }
            } else {
                onResult("Model error", "❌ Klasifikasi gagal")
            }
        },
        onError = { error ->
            Log.e("HandSignAnalyzer", "MediaPipe Error: $error")
            onResult("Error: $error", "❌ MediaPipe error")
        }
    )

    private fun updateDynamicThreshold(confidence: Float) {
        // Sesuaikan threshold berdasarkan confidence rata-rata
        when {
            confidence > 0.8f -> dynamicThreshold = maxThreshold
            confidence < 0.3f -> dynamicThreshold = minThreshold
            else -> dynamicThreshold = (minThreshold + maxThreshold) / 2f
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            if (bitmap != null) {
                handLandmarkerHelper.detectHand(bitmap)
            }
        } catch (e: Exception) {
            Log.e("HandSignAnalyzer", "Error analyzing image", e)
            onResult("Error: ${e.message}", "❌ ${e.javaClass.simpleName}")
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Rotate and mirror for front camera
            val rotationDegrees = imageInfo.rotationDegrees
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e("ImageProxy", "Error converting to bitmap", e)
            null
        }
    }
}

class InterpreterHelper(private val context: Context) {

    private var model: ModelBisindo? = null

    init {
        try {
            model = ModelBisindo.newInstance(context)
            Log.d("InterpreterHelper", "✅ Model loaded successfully")
        } catch (e: Exception) {
            Log.e("InterpreterHelper", "❌ Error loading model", e)
        }
    }

    fun classify(landmarks: FloatArray): List<Pair<Int, Float>> {
        if (landmarks.size != 126) {
            Log.e("InterpreterHelper", "Invalid landmark data size: ${landmarks.size}. Expected 126.")
            return emptyList()
        }

        return try {
            val modelInstance = model ?: run {
                Log.e("InterpreterHelper", "Model is null")
                return emptyList()
            }

            // Validasi data landmarks
            val validLandmarks = landmarks.count { it != 0f }
            val landmarkRatio = validLandmarks.toFloat() / landmarks.size
            
            Log.d("InterpreterHelper", "Landmark validation: $validLandmarks/126 (${String.format("%.1f%%", landmarkRatio * 100)})")
            
            // Debug: Log beberapa nilai landmarks untuk verifikasi
            Log.d("InterpreterDebug", "First 10 landmarks: ${landmarks.take(10).joinToString(", ")}")
            Log.d("InterpreterDebug", "Last 10 landmarks: ${landmarks.takeLast(10).joinToString(", ")}")

            val byteBuffer = ByteBuffer.allocateDirect(4 * 126)
            byteBuffer.order(ByteOrder.nativeOrder())

            for (value in landmarks) {
                byteBuffer.putFloat(value)
            }
            byteBuffer.rewind()

            val inputFeature0 = TensorBuffer.createFixedSize(
                intArrayOf(1, 126),
                DataType.FLOAT32
            )
            inputFeature0.loadBuffer(byteBuffer)

            val startTime = System.currentTimeMillis()
            val outputs = modelInstance.process(inputFeature0)
            val inferenceTime = System.currentTimeMillis() - startTime
            
            val scores = outputs.outputFeature0AsTensorBuffer.floatArray

            Log.d("InterpreterHelper", "✅ Inference complete in ${inferenceTime}ms. Output size: ${scores.size}")

            val results = scores.mapIndexed { index, score ->
                index to score
            }.sortedByDescending { it.second }.take(5)
            
            // Log top 3 predictions untuk debug
            results.take(3).forEachIndexed { idx, (labelIdx, confidence) ->
                Log.d("InterpreterHelper", "Top ${idx + 1}: Label $labelIdx = ${String.format("%.3f", confidence)}")
            }

            results

        } catch (e: Exception) {
            Log.e("InterpreterHelper", "❌ Classification error: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        model?.close()
    }
}