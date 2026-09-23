package com.example.streamswitcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.streamswitcher.databinding.ActivityMainBinding

enum class DisplayMode { CAMERA, IMAGE, VIDEO }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentLensFacing = CameraSelector.LENS_FACING_BACK
    private var currentMode = DisplayMode.CAMERA
    private var isRecording = false

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // ---- Permissions ----
    private val requiredPermissions: Array<String> by lazy {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startCamera()
        }
    }

    // ---- Gallery picker (image or video) ----
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) handlePickedMedia(uri)
    }

    // ---- Screen-capture consent for recording ----
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            isRecording = true
        } else {
            isRecording = false
        }
        updateRecordUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.btnCamera.setOnClickListener { switchMode(DisplayMode.CAMERA) }
        binding.btnPickMedia.setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }
        binding.btnSwitchLens.setOnClickListener { flipLens() }
        binding.btnRecord.setOnClickListener { toggleRecording() }

        if (hasAllPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Camera (CameraX) ----
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            if (currentMode == DisplayMode.CAMERA) bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }
        val selector = CameraSelector.Builder().requireLensFacing(currentLensFacing).build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview)
    }

    private fun flipLens() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (currentMode == DisplayMode.CAMERA) bindCameraUseCases()
    }

    // ---- Mode switching (camera / image / video) ----
    private fun switchMode(mode: DisplayMode) {
        currentMode = mode
        binding.previewView.visibility = if (mode == DisplayMode.CAMERA) View.VISIBLE else View.GONE
        binding.imageView.visibility = if (mode == DisplayMode.IMAGE) View.VISIBLE else View.GONE
        binding.videoView.visibility = if (mode == DisplayMode.VIDEO) View.VISIBLE else View.GONE
        binding.modeLabel.text = mode.name

        if (mode == DisplayMode.CAMERA) {
            bindCameraUseCases()
        } else {
            binding.videoView.stopPlayback()
        }
    }

    private fun handlePickedMedia(uri: Uri) {
        val type = contentResolver.getType(uri) ?: ""
        if (type.startsWith("video")) {
            binding.videoView.setVideoURI(uri)
            binding.videoView.setOnPreparedListener { player ->
                player.isLooping = false
                player.start()
            }
            switchMode(DisplayMode.VIDEO)
        } else {
            binding.imageView.setImageURI(uri)
            switchMode(DisplayMode.IMAGE)
        }
    }

    // ---- Recording (screen + mic, via MediaProjection) ----
    private fun toggleRecording() {
        if (!isRecording) {
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
            isRecording = false
            updateRecordUi()
        }
    }

    private fun updateRecordUi() {
        binding.recIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
        binding.btnRecord.text = if (isRecording) "Stop" else "Record"
    }
}
