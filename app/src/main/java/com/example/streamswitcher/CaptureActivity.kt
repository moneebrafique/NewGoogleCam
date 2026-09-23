package com.example.streamswitcher

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.example.streamswitcher.databinding.ActivityCaptureBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers external apps' camera-delegation intents (IMAGE_CAPTURE,
 * VIDEO_CAPTURE) and the system's generic "open camera" triggers
 * (STILL_IMAGE_CAMERA[_SECURE], VIDEO_CAMERA -- the lock-screen shortcut
 * and Assistant's "open camera"). Captures exactly one photo or video clip,
 * writes it to wherever the caller asked (EXTRA_OUTPUT if given, otherwise
 * a new MediaStore entry), and returns control immediately -- this is a
 * genuinely different contract from MainActivity's live-preview/recording
 * tool, so it's a separate screen.
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isVideoMode = false
    private var outputUri: Uri? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startCamera()
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isVideoMode = intent.action == "android.media.action.VIDEO_CAPTURE" ||
            intent.action == "android.media.action.VIDEO_CAMERA"

        outputUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)

        binding.captureButton.text = if (isVideoMode) "Record" else "Capture"
        binding.captureButton.setOnClickListener {
            if (isVideoMode) toggleRecording() else takePhoto()
        }

        val required = mutableListOf(Manifest.permission.CAMERA)
        if (isVideoMode) required.add(Manifest.permission.RECORD_AUDIO)

        if (required.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startCamera()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        if (isVideoMode) {
            val recorder = Recorder.Builder().build()
            videoCapture = VideoCapture.withOutput(recorder)
            provider.bindToLifecycle(this, selector, preview, videoCapture)
        } else {
            imageCapture = ImageCapture.Builder().build()
            provider.bindToLifecycle(this, selector, preview, imageCapture)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val targetUri = outputUri ?: createMediaStoreImageUri()

        try {
            val outputStream = contentResolver.openOutputStream(targetUri) ?: return
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()

            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val result = Intent().apply { data = targetUri }
                        setResult(RESULT_OK, result)
                        finish()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CaptureActivity", "Photo capture failed", exception)
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CaptureActivity", "Could not open output stream", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun toggleRecording() {
        val current = activeRecording
        if (current != null) {
            current.stop()
            activeRecording = null
            return
        }

        val capture = videoCapture ?: return
        val targetUri = outputUri ?: createMediaStoreVideoUri()

        try {
            val pfd = contentResolver.openFileDescriptor(targetUri, "rw") ?: return
            val outputOptions = FileDescriptorOutputOptions.Builder(pfd).build()

            binding.captureButton.text = "Stop"
            activeRecording = capture.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val result = Intent().apply { data = targetUri }
                        setResult(RESULT_OK, result)
                        finish()
                    }
                }
        } catch (e: Exception) {
            Log.e("CaptureActivity", "Could not start recording", e)
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun createMediaStoreImageUri(): Uri {
        val name = "IMG_${timestamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        return contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create image entry")
    }

    private fun createMediaStoreVideoUri(): Uri {
        val name = "VID_${timestamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create video entry")
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun onDestroy() {
        activeRecording?.stop()
        super.onDestroy()
    }
}
