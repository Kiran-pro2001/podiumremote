package com.podium.remote.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onRecordingStateChanged: (isRecording: Boolean, uriString: String?) -> Unit,
    private val onTimerTick: (durationSeconds: Long) -> Unit,
    private val onError: (message: String) -> Unit
) {

    private val TAG = "PodiumCameraManager"
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    var isFrontCamera = false
        private set

    var isStopping: Boolean = false
        private set

    val isRecording: Boolean
        get() = currentRecording != null && !isStopping

    private var recordingStartTime = 0L
    private var targetRotation = android.view.Surface.ROTATION_0

    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        try {
            imageCapture?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update targetRotation: ${e.message}")
        }
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                onError("Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Preview UseCase
        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // ImageCapture UseCase
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(targetRotation)
            .build()

        // VideoCapture UseCase with 4K preference falling back to 1080p
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        videoCapture = VideoCapture.withOutput(recorder).apply {
            targetRotation = this@CameraManager.targetRotation
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
            Log.d(TAG, "Camera bound successfully. FrontCamera: $isFrontCamera")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera use cases", e)
            onError("Camera bind error: ${e.message}")
        }
    }

    fun switchCamera() {
        if (isRecording) {
            onError("Cannot switch lens while actively recording.")
            return
        }
        isFrontCamera = !isFrontCamera
        bindCameraUseCases()
    }

    fun startRecording() {
        val videoCap = videoCapture ?: run {
            onError("VideoCapture not ready.")
            return
        }

        if (currentRecording != null) {
            Log.w(TAG, "Recording already in progress.")
            return
        }

        val name = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/PodiumRemote")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = videoCap.output
            .prepareRecording(context, mediaStoreOutputOptions)

        // Enable audio if permission granted
        try {
            pendingRecording.withAudioEnabled()
        } catch (e: SecurityException) {
            Log.w(TAG, "Audio permission not granted for video: ${e.message}")
        }

        isStopping = false
        recordingStartTime = System.currentTimeMillis()

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(context), Consumer { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    Log.d(TAG, "Recording Started.")
                    isStopping = false
                    onTimerTick(0)
                    onRecordingStateChanged(true, null)
                }
                is VideoRecordEvent.Status -> {
                    if (!isStopping && currentRecording != null) {
                        val durationNanos = recordEvent.recordingStats.recordedDurationNanos
                        val seconds = durationNanos / 1_000_000_000
                        onTimerTick(seconds)
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    val uri = recordEvent.outputResults.outputUri.toString()
                    Log.d(TAG, "Recording Finalized: $uri, error: ${recordEvent.error}")
                    isStopping = false
                    currentRecording = null
                    onTimerTick(0)
                    if (recordEvent.hasError()) {
                        onError("Recording error: ${recordEvent.error}")
                    } else {
                        onRecordingStateChanged(false, uri)
                    }
                }
            }
        })
    }

    fun stopRecording() {
        if (isStopping) return
        isStopping = true
        onTimerTick(0)
        currentRecording?.let {
            it.stop()
            currentRecording = null
        } ?: run {
            isStopping = false
            Log.w(TAG, "No active recording to stop.")
        }
    }

    fun capturePhoto(onSuccess: (String) -> Unit) {
        val imgCap = imageCapture ?: run {
            onError("ImageCapture not ready.")
            return
        }

        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/PodiumRemote")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imgCap.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri?.toString() ?: name
                    Log.d(TAG, "Photo saved: $uri")
                    onSuccess(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    onError("Photo capture failed: ${exception.message}")
                }
            }
        )
    }

    /**
     * Grabs an in-memory lightweight thumbnail without saving full-res file to disk,
     * encoding directly to Base64 for instant laptop viewfinder preview.
     */
    fun captureFramingSnapshot(onResult: (String) -> Unit) {
        val imgCap = imageCapture ?: run {
            onError("Camera not ready for framing snapshot.")
            return
        }

        imgCap.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val matrix = android.graphics.Matrix().apply {
                            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        }
                        val rotated = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        val targetWidth = 640
                        val targetHeight = (targetWidth.toFloat() * rotated.height / rotated.width).toInt()
                        val scaled = android.graphics.Bitmap.createScaledBitmap(rotated, targetWidth, targetHeight, true)
                        val baos = java.io.ByteArrayOutputStream()
                        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, baos)
                        val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                        onResult("data:image/jpeg;base64,$base64")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to encode snapshot", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Framing snapshot error", exception)
                }
            }
        )
    }
}
