package com.podium.remote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.podium.remote.camera.CameraManager
import com.podium.remote.databinding.ActivityMainBinding
import com.podium.remote.server.NetworkUtils
import com.podium.remote.server.RemoteServer
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var remoteServer: RemoteServer? = null

    private var isBlackoutActive = false
    private var isAddressCardCollapsed = false
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private lateinit var telemetryRunnable: Runnable
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentSensorRotation = android.view.Surface.ROTATION_0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            setupCameraAndServer()
        } else {
            Toast.makeText(this, "Camera permission is strictly required for Podium Remote.", Toast.LENGTH_LONG).show()
        }
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { updateNetworkAndQrCode() }
        }
        override fun onLost(network: Network) {
            runOnUiThread { updateNetworkAndQrCode() }
        }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            runOnUiThread { updateNetworkAndQrCode() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while controlling camera
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupBlackoutGestures()

        if (allPermissionsGranted()) {
            setupCameraAndServer()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupCameraAndServer() {
        // Register network listener for auto hotspot IP discovery
        try {
            val request = NetworkRequest.Builder().build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        updateNetworkAndQrCode()
        updateTelemetryUI()

        // 1. Initialize CameraX Manager
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.viewFinder,
            onRecordingStateChanged = { isRecording, uriString ->
                runOnUiThread {
                    if (isRecording) {
                        binding.tvTimer.text = "00:00:00"
                        binding.tvDimTimer.text = "00:00:00"
                        binding.tvTally.text = "REC ● LIVE"
                        binding.tvTally.setTextColor(ContextCompat.getColor(this, R.color.white))
                        binding.tvTally.setBackgroundResource(R.drawable.bg_pill_recording)
                        binding.tvDimTally.text = "● REC LIVE"
                        binding.tvDimTally.setTextColor(android.graphics.Color.RED)

                        binding.btnRecordStart.isEnabled = false
                        binding.btnRecordStart.backgroundTintList = ContextCompat.getColorStateList(this, R.color.border_subtle)
                        binding.btnRecordStop.isEnabled = true
                        binding.btnRecordStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_coral)

                        showStudioHudToast("● 4K Recording Started — Saving to DCIM/PodiumRemote")
                        remoteServer?.broadcast("RECORDING_STARTED")
                    } else {
                        smoothResetTimer()
                        binding.tvTally.text = "● STANDBY"
                        binding.tvTally.setTextColor(ContextCompat.getColor(this, R.color.accent_sage_light))
                        binding.tvTally.setBackgroundResource(R.drawable.bg_pill_standby)
                        binding.tvDimTally.text = "● STANDBY"
                        binding.tvDimTally.setTextColor(android.graphics.Color.GRAY)

                        binding.btnRecordStart.isEnabled = true
                        binding.btnRecordStart.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                        binding.btnRecordStop.isEnabled = false
                        binding.btnRecordStop.backgroundTintList = ContextCompat.getColorStateList(this, R.color.border_subtle)

                        if (uriString != null) {
                            showStudioHudToast("✓ Video Saved to /DCIM/PodiumRemote/")
                        }
                        remoteServer?.broadcast("RECORDING_STOPPED", JSONObject().apply {
                            put("uri", uriString ?: "")
                        })
                    }
                }
            },
            onTimerTick = { seconds ->
                runOnUiThread {
                    if (cameraManager.isRecording && !cameraManager.isStopping) {
                        val formatted = formatTime(seconds)
                        binding.tvTimer.text = formatted
                        binding.tvDimTimer.text = formatted
                        remoteServer?.broadcast("TIMER_TICK", JSONObject().apply {
                            put("duration_seconds", seconds)
                            put("formatted", formatted)
                            put("time", formatted)
                        })
                    } else {
                        binding.tvTimer.text = "00:00:00"
                        binding.tvDimTimer.text = "00:00:00"
                    }
                }
            },
            onError = { errorMsg ->
                runOnUiThread {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                    remoteServer?.broadcast("ERROR", JSONObject().apply {
                        put("message", errorMsg)
                    })
                }
            }
        )

        cameraManager.startCamera()

        // 2. Initialize Embedded Remote Server (HTTP :8080, WebSocket :8088)
        remoteServer = RemoteServer(
            context = this,
            httpPort = 8080,
            wsPort = 8088,
            onCommandReceived = { command, requestId, params ->
                handleRemoteCommand(command, requestId, params)
            }
        )
        remoteServer?.start()

        // 3. UI Buttons
        binding.btnRecordStart.setOnClickListener {
            if (!cameraManager.isRecording) {
                binding.tvTimer.text = "00:00:00"
                binding.tvDimTimer.text = "00:00:00"
                cameraManager.startRecording()
            }
        }

        binding.btnRecordStop.setOnClickListener {
            if (cameraManager.isRecording) {
                smoothResetTimer()
                cameraManager.stopRecording()
            }
        }

        binding.btnCheckFraming.setOnClickListener {
            showStudioHudToast("⚡ Framing Synced with Laptop")
            val snapshotInfo = getInstantViewfinderSnapshot()
            if (snapshotInfo != null) {
                val (instantSnapshot, isLandscape) = snapshotInfo
                remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                    put("image_data", instantSnapshot)
                    put("is_landscape", isLandscape)
                })
            }
        }

        binding.btnCapturePhoto.setOnClickListener {
            showStudioHudToast("📷 Capturing Full-Res Photo...")
            cameraManager.capturePhoto { uri ->
                runOnUiThread {
                    showStudioHudToast("📷 Photo Saved to Gallery!")
                    val snapshotInfo = getInstantViewfinderSnapshot()
                    if (snapshotInfo != null) {
                        val (instantSnapshot, isLandscape) = snapshotInfo
                        remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                            put("image_data", instantSnapshot)
                            put("is_landscape", isLandscape)
                        })
                    }
                }
                remoteServer?.broadcast("PHOTO_CAPTURED", JSONObject().apply {
                    put("uri", uri)
                })
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            performCameraSwitch()
        }

        binding.btnDimScreen.setOnClickListener {
            toggleBlackoutMode(!isBlackoutActive)
            if (isBlackoutActive) {
                showStudioHudToast("🌙 Screen Dimmed — Saving Power & Heat")
            } else {
                showStudioHudToast("☀️ Screen Woken Up — Full Brightness")
            }
        }

        binding.btnWakeFromDim.setOnClickListener {
            toggleBlackoutMode(false)
            showStudioHudToast("☀️ Screen Woken Up — Full Brightness")
        }

        binding.blackoutOverlay.setOnClickListener {
            toggleBlackoutMode(false)
            showStudioHudToast("☀️ Screen Woken Up — Full Brightness")
        }

        binding.btnCopyLink.setOnClickListener {
            val url = binding.tvServerAddress.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Podium Remote Cockpit", url)
            clipboard.setPrimaryClip(clip)
            showStudioHudToast("✓ Link Copied to Clipboard")
        }

        binding.btnShareToPc.setOnClickListener {
            val url = binding.tvServerAddress.text.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Podium Remote Cockpit")
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(intent, "Share to PC (Quick Share / Chrome / WhatsApp)"))
        }

        binding.btnToggleQr.setOnClickListener {
            binding.qrContainer.visibility = View.VISIBLE
        }

        binding.btnCloseQr.setOnClickListener {
            binding.qrContainer.visibility = View.GONE
        }

        binding.btnToggleCollapseAddress.setOnClickListener {
            isAddressCardCollapsed = !isAddressCardCollapsed
            binding.layoutAddressBody.visibility = if (isAddressCardCollapsed) View.GONE else View.VISIBLE
            binding.ivCollapseChevron.setImageResource(
                if (isAddressCardCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
            )
            binding.tvMinAddressPill.visibility = if (isAddressCardCollapsed) View.VISIBLE else View.GONE
        }

        startTelemetryBroadcastLoop()
        setupOrientationListener()
    }

    private fun updateNetworkAndQrCode() {
        val localIp = NetworkUtils.getLocalIpAddress()
        val url = "http://$localIp:8080"
        binding.tvServerAddress.text = url
        binding.tvMinAddressPill.text = "$localIp:8080"

        // Generate QR code
        val qrBitmap = NetworkUtils.generateQrBitmap(url, 280)
        if (qrBitmap != null) {
            binding.ivQrCode.setImageBitmap(qrBitmap)
        }
    }

    private fun handleRemoteCommand(command: String, requestId: String, params: JSONObject) {
        runOnUiThread {
            when (command) {
                "START_RECORDING" -> {
                    if (!cameraManager.isRecording) {
                        binding.tvTimer.text = "00:00:00"
                        binding.tvDimTimer.text = "00:00:00"
                        cameraManager.startRecording()
                        remoteServer?.sendAck(requestId, "SUCCESS", "STARTING_RECORDING")
                    } else {
                        remoteServer?.sendAck(requestId, "IGNORED", "ALREADY_RECORDING")
                    }
                }
                "STOP_RECORDING" -> {
                    if (cameraManager.isRecording) {
                        smoothResetTimer()
                        cameraManager.stopRecording()
                        remoteServer?.sendAck(requestId, "SUCCESS", "STOPPING_RECORDING")
                    } else {
                        remoteServer?.sendAck(requestId, "IGNORED", "NOT_RECORDING")
                    }
                }
                "CHECK_FRAMING" -> {
                    showStudioHudToast("⚡ Framing Synced to Laptop")
                    val snapshotInfo = getInstantViewfinderSnapshot()
                    if (snapshotInfo != null) {
                        val (instantSnapshot, isLandscape) = snapshotInfo
                        remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                            put("image_data", instantSnapshot)
                            put("is_landscape", isLandscape)
                        })
                        remoteServer?.sendAck(requestId, "SUCCESS", "FRAMING_SNAPSHOT_READY")
                    } else {
                        cameraManager.captureFramingSnapshot { base64Jpeg ->
                            remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                                put("image_data", base64Jpeg)
                                put("is_landscape", true)
                            })
                            remoteServer?.sendAck(requestId, "SUCCESS", "FRAMING_SNAPSHOT_READY")
                        }
                    }
                }
                "CLIENT_CONNECTED" -> {
                    val battery = NetworkUtils.getBatteryLevel(this@MainActivity)
                    val storage = NetworkUtils.getFreeStorageGb()
                    val lensName = if (cameraManager.isFrontCamera) "FRONT (1080p)" else "REAR (4K)"
                    val isLandscape = (currentSensorRotation == android.view.Surface.ROTATION_90 || currentSensorRotation == android.view.Surface.ROTATION_270)
                    remoteServer?.broadcast("TELEMETRY", JSONObject().apply {
                        put("battery", battery)
                        put("storage_gb", storage)
                        put("lens", lensName)
                        put("is_front", cameraManager.isFrontCamera)
                        put("is_landscape", isLandscape)
                    })
                    listOf(200L, 700L).forEach { delayMs ->
                        binding.root.postDelayed({
                            val snapshotInfo = getInstantViewfinderSnapshot()
                            if (snapshotInfo != null) {
                                val (instantSnapshot, isLand) = snapshotInfo
                                remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                                    put("image_data", instantSnapshot)
                                    put("is_landscape", isLand)
                                })
                            }
                        }, delayMs)
                    }
                }
                "SWITCH_CAMERA" -> {
                    performCameraSwitch(requestId)
                }
                "CAPTURE_PHOTO" -> {
                    cameraManager.capturePhoto { uri ->
                        showStudioHudToast("📷 Photo Saved to Gallery!")
                        remoteServer?.sendAck(requestId, "SUCCESS", "PHOTO_CAPTURED", JSONObject().apply {
                            put("uri", uri)
                        })
                        remoteServer?.broadcast("PHOTO_CAPTURED", JSONObject().apply {
                            put("uri", uri)
                        })
                        val snapshotInfo = getInstantViewfinderSnapshot()
                        if (snapshotInfo != null) {
                            val (instantSnapshot, isLandscape) = snapshotInfo
                            remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                                put("image_data", instantSnapshot)
                                put("is_landscape", isLandscape)
                            })
                        }
                    }
                }
                "TOGGLE_DIM" -> {
                    toggleBlackoutMode(!isBlackoutActive)
                    remoteServer?.sendAck(requestId, "SUCCESS", "SCREEN_DIM_CHANGED", JSONObject().apply {
                        put("is_dimmed", isBlackoutActive)
                    })
                }
                "DIM_SCREEN" -> {
                    val enable = params.optBoolean("enable", !isBlackoutActive)
                    toggleBlackoutMode(enable)
                    remoteServer?.sendAck(requestId, "SUCCESS", "SCREEN_DIM_CHANGED", JSONObject().apply {
                        put("is_dimmed", isBlackoutActive)
                    })
                }
                "GET_STATUS" -> {
                    val status = JSONObject().apply {
                        put("is_recording", cameraManager.isRecording)
                        put("lens", if (cameraManager.isFrontCamera) "FRONT" else "REAR")
                        put("battery", NetworkUtils.getBatteryLevel(this@MainActivity))
                        put("storage_gb", NetworkUtils.getFreeStorageGb())
                    }
                    remoteServer?.sendAck(requestId, "SUCCESS", "STATUS_REPORT", status)
                }
            }
        }
    }

    private fun performCameraSwitch(requestId: String? = null) {
        if (cameraManager.isRecording) {
            showStudioHudToast("Cannot flip lens while recording")
            if (requestId != null) {
                remoteServer?.sendAck(requestId, "ERROR", "CANNOT_SWITCH_WHILE_RECORDING")
            }
            return
        }

        cameraManager.switchCamera()
        updateLensUI()

        val lensName = if (cameraManager.isFrontCamera) "FRONT (1080p)" else "REAR (4K)"
        showStudioHudToast("🔄 Switched to $lensName")

        val payload = JSONObject().apply {
            put("lens", lensName)
            put("is_front", cameraManager.isFrontCamera)
        }
        remoteServer?.broadcast("CAMERA_SWITCHED", payload)
        if (requestId != null) {
            remoteServer?.sendAck(requestId, "SUCCESS", "CAMERA_SWITCHED", payload)
        }

        // Auto-refresh framing preview with staggered checks to ensure the newly bound sensor is ready
        listOf(450L, 850L, 1300L).forEach { delayMs ->
            binding.root.postDelayed({
                val snapshotInfo = getInstantViewfinderSnapshot()
                if (snapshotInfo != null) {
                    val (instantSnapshot, isLandscape) = snapshotInfo
                    remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                        put("image_data", instantSnapshot)
                        put("is_landscape", isLandscape)
                    })
                }
            }, delayMs)
        }
    }

    private fun toggleBlackoutMode(enable: Boolean) {
        isBlackoutActive = enable
        binding.blackoutOverlay.visibility = if (isBlackoutActive) View.VISIBLE else View.GONE

        val layoutParams = window.attributes
        layoutParams.screenBrightness = if (isBlackoutActive) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = layoutParams

        if (isBlackoutActive) {
            binding.btnDimScreen.text = "Wake"
            binding.btnDimScreen.setIconResource(R.drawable.ic_wake_sun)
        } else {
            binding.btnDimScreen.text = "Dim"
            binding.btnDimScreen.setIconResource(R.drawable.ic_dim_moon)
        }
        remoteServer?.broadcast("SCREEN_DIM_CHANGED", JSONObject().apply {
            put("is_dimmed", isBlackoutActive)
        })
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45..134 -> android.view.Surface.ROTATION_270
                    in 135..224 -> android.view.Surface.ROTATION_180
                    in 225..314 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }

                if (rotation != currentSensorRotation) {
                    currentSensorRotation = rotation
                    cameraManager.setTargetRotation(rotation)

                    val isLandscape = (rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270)
                    remoteServer?.broadcast("ORIENTATION_CHANGED", JSONObject().apply {
                        put("is_landscape", isLandscape)
                        put("orientation", if (isLandscape) "landscape" else "portrait")
                    })
                    binding.root.postDelayed({
                        val snapshotInfo = getInstantViewfinderSnapshot()
                        if (snapshotInfo != null) {
                            val (instantSnapshot, isLand) = snapshotInfo
                            remoteServer?.broadcast("FRAMING_SNAPSHOT", JSONObject().apply {
                                put("image_data", instantSnapshot)
                                put("is_landscape", isLand)
                            })
                        }
                    }, 250)
                }
            }
        }
        orientationEventListener?.enable()
    }

    private fun getInstantViewfinderSnapshot(): Pair<String, Boolean>? {
        return try {
            val bitmap = binding.viewFinder.bitmap ?: return null
            val isLandscape = (currentSensorRotation == android.view.Surface.ROTATION_90 || currentSensorRotation == android.view.Surface.ROTATION_270)
            val rotatedBitmap = if (isLandscape) {
                val matrix = android.graphics.Matrix()
                val degrees = if (currentSensorRotation == android.view.Surface.ROTATION_90) 270f else 90f
                matrix.postRotate(degrees)
                android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            val targetWidth = if (isLandscape) 720 else 420
            val targetHeight = (targetWidth.toFloat() * rotatedBitmap.height / rotatedBitmap.width).toInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)
            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos)
            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            Pair("data:image/jpeg;base64,$base64", isLandscape)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setupBlackoutGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isBlackoutActive) {
                    toggleBlackoutMode(false)
                    return true
                }
                return false
            }
        })

        binding.blackoutOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun startTelemetryBroadcastLoop() {
        telemetryRunnable = object : Runnable {
            override fun run() {
                val battery = NetworkUtils.getBatteryLevel(this@MainActivity)
                val storage = NetworkUtils.getFreeStorageGb()
                updateTelemetryUI()

                remoteServer?.broadcast("TELEMETRY", JSONObject().apply {
                    put("battery", battery)
                    put("storage_gb", storage)
                })

                telemetryHandler.postDelayed(this, 5000)
            }
        }
        telemetryHandler.postDelayed(telemetryRunnable, 5000)
    }

    private fun updateLensUI() {
        val lensName = if (cameraManager.isFrontCamera) "FRONT (1080p)" else "REAR (4K)"
        binding.tvLensInfo.text = lensName
    }

    private fun updateTelemetryUI() {
        val storage = NetworkUtils.getFreeStorageGb()
        val battery = NetworkUtils.getBatteryLevel(this)
        binding.tvStorageInfo.text = "$storage GB Free"
        binding.tvBatteryInfo.text = "$battery%"
    }

    private var toastRunnable: Runnable? = null

    private fun showStudioHudToast(message: String) {
        runOnUiThread {
            toastRunnable?.let { telemetryHandler.removeCallbacks(it) }

            binding.tvToastMessage.text = message
            binding.layoutInAppToast.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .setDuration(160)
                    .start()
            }

            toastRunnable = Runnable {
                binding.layoutInAppToast.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction {
                        binding.layoutInAppToast.visibility = View.GONE
                    }
                    .start()
            }
            telemetryHandler.postDelayed(toastRunnable!!, 2200)
        }
    }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun smoothResetTimer() {
        runOnUiThread {
            binding.tvTimer.animate()
                .alpha(0.25f)
                .setDuration(70)
                .withEndAction {
                    binding.tvTimer.text = "00:00:00"
                    binding.tvDimTimer.text = "00:00:00"
                    binding.tvTimer.animate()
                        .alpha(1.0f)
                        .setDuration(130)
                        .start()
                }
                .start()
            binding.tvDimTimer.text = "00:00:00"
            remoteServer?.broadcast("TIMER_TICK", JSONObject().apply {
                put("duration_seconds", 0)
                put("formatted", "00:00:00")
                put("time", "00:00:00")
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
        telemetryHandler.removeCallbacksAndMessages(null)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        remoteServer?.stop()
    }
}
