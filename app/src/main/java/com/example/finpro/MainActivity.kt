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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.*
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreviewWithAnalyzer(context) { label, debugInfo ->
                prediction = label
                debug = debugInfo
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("Mohon izinkan akses kamera", modifier = Modifier.padding(16.dp))
            }
        }

        // Debug info at top
        if (debug.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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

        // Prediction overlay
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = prediction,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
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

// Di HandSignAnalyzer class, update bagian ini:

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

    private var isFrontCamera = true // Track camera orientation

    private val handLandmarkerHelper: HandLandmarkerHelper = HandLandmarkerHelper(
        context = context,
        onResult = { landmarks ->
            // Debug: Check if landmarks are valid
            val nonZeroCount = landmarks.count { it != 0f }
            Log.d("Analyzer", "Received landmarks, non-zero: $nonZeroCount/126")

            if (nonZeroCount < 20) {
                onResult("Tidak ada tangan", "⚠️ Landmarks: $nonZeroCount/126")
                return@HandLandmarkerHelper
            }

            val results = model.classify(landmarks)

            if (results.isNotEmpty()) {
                val best = results.maxByOrNull { it.second }
                val label = labels.getOrNull(best?.first ?: -1) ?: "?"
                val confidence = best?.second ?: 0f

                // Log top 3 predictions
                val top3 = results.take(3).joinToString(", ") { (idx, conf) ->
                    "${labels.getOrNull(idx) ?: "?"}:${String.format("%.2f", conf)}"
                }
                Log.d("Analyzer", "Top 3: $top3")

                if (confidence > 0.5f) {
                    onResult(
                        "$label (${String.format("%.1f%%", confidence * 100)})",
                        "✅ Landmarks: ${nonZeroCount/3} | Top3: $top3"
                    )
                } else {
                    onResult(
                        "Tidak yakin: $label",
                        "⚠️ Confidence rendah: ${String.format("%.1f%%", confidence * 100)}"
                    )
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

    override fun analyze(image: ImageProxy) {
        try {
            val bitmap = image.toBitmap()
            if (bitmap != null) {
                // PENTING: Pass info apakah front camera atau bukan
                handLandmarkerHelper.detectHand(bitmap, isFrontCamera = isFrontCamera)
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

            // FIX: Rotate saja, JANGAN mirror
            // Biarkan HandLandmarkerHelper yang handle mirror effect
            val rotationDegrees = imageInfo.rotationDegrees
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())

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

            val outputs = modelInstance.process(inputFeature0)
            val scores = outputs.outputFeature0AsTensorBuffer.floatArray

            Log.d("InterpreterHelper", "✅ Inference complete. Output size: ${scores.size}")

            scores.mapIndexed { index, score ->
                index to score
            }.sortedByDescending { it.second }.take(5)

        } catch (e: Exception) {
            Log.e("InterpreterHelper", "❌ Classification error: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        model?.close()
    }
}