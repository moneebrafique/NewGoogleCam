package com.example.streamswitcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.streamswitcher.databinding.ActivityMainBinding
import java.io.File

enum class Facing { BACK, FRONT }
enum class MediaType { IMAGE, VIDEO }

/** Everything remembered for one lens (front or back). */
data class LensState(
    var isLive: Boolean = true,
    var mediaPath: String? = null,
    var mediaType: MediaType? = null,
    var loop: Boolean = true
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var cameraProvider: ProcessCameraProvider? = null
    private var facing = Facing.BACK
    private var backState = LensState()
    private var frontState = LensState()
    private var isRecording = false

    // Media pending assignment (picked but not yet told which lens/loop mode)
    private var pendingMediaPath: String? = null
    private var pendingMediaType: MediaType? = null

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
        if (grants.values.all { it }) startCamera()
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) onMediaPicked(uri)
    }

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

        prefs = getSharedPreferences("stream_switcher_state", MODE_PRIVATE)
        loadState()

        binding.btnLive.setOnClickListener { showLiveForCurrentFacing() }
        binding.btnSwitchLens.setOnClickListener { flipLens() }
        binding.btnPickMedia.setOnClickListener {
            pickMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        }
        binding.btnRecord.setOnClickListener { toggleRecording() }

        if (hasAllPermissions()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        // Restore whatever was showing last time, instead of defaulting to live camera.
        renderCurrentState()
        updateRecordUi()
    }

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Camera (CameraX) ----
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            if (currentState().isLive) bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        val lensFacing = if (facing == Facing.BACK) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview)
    }

    // ---- Per-lens state ----
    private fun currentState(): LensState = if (facing == Facing.BACK) backState else frontState

    private fun flipLens() {
        facing = if (facing == Facing.BACK) Facing.FRONT else Facing.BACK
        saveState()
        renderCurrentState()
    }

    private fun showLiveForCurrentFacing() {
        currentState().isLive = true
        saveState()
        renderCurrentState()
    }

    private fun renderCurrentState() {
        val state = currentState()
        updateStatusChip()

        if (state.isLive || state.mediaPath == null) {
            binding.previewView.visibility = View.VISIBLE
            binding.imageView.visibility = View.GONE
            binding.videoView.visibility = View.GONE
            binding.videoView.stopPlayback()
            if (cameraProvider != null) bindCameraUseCases()
            return
        }

        binding.previewView.visibility = View.GONE
        cameraProvider?.unbindAll()
        val path = state.mediaPath!!
        if (state.mediaType == MediaType.VIDEO) {
            binding.imageView.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.videoView.setVideoURI(Uri.fromFile(File(path)))
            binding.videoView.setOnPreparedListener { player ->
                player.isLooping = state.loop
                player.start()
            }
        } else {
            binding.videoView.visibility = View.GONE
            binding.videoView.stopPlayback()
            binding.imageView.visibility = View.VISIBLE
            binding.imageView.setImageURI(Uri.fromFile(File(path)))
        }
    }

    private fun updateStatusChip() {
        val label = facing.name
        val state = currentState()
        val modeText = when {
            state.isLive || state.mediaPath == null -> "LIVE"
            state.mediaType == MediaType.VIDEO -> if (state.loop) "VIDEO (loop)" else "VIDEO (once)"
            else -> "PHOTO"
        }
        binding.statusChip.text = "$label \u00B7 $modeText"
    }

    // ---- Media picking flow ----
    private fun onMediaPicked(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: ""
        val type = if (mimeType.startsWith("video")) MediaType.VIDEO else MediaType.IMAGE
        val path = copyToInternalStorage(uri, type)
        if (path == null) return

        pendingMediaPath = path
        pendingMediaType = type
        askWhichLens()
    }

    private fun copyToInternalStorage(uri: Uri, type: MediaType): String? {
        return try {
            val extension = if (type == MediaType.VIDEO) "mp4" else "jpg"
            val fileName = "media_${System.currentTimeMillis()}.$extension"
            val outFile = File(filesDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun askWhichLens() {
        AlertDialog.Builder(this)
            .setTitle("Assign to which camera?")
            .setMessage("Choose which lens should show this when active.")
            .setPositiveButton("Back camera") { _, _ -> assignPendingMedia(Facing.BACK) }
            .setNegativeButton("Front camera") { _, _ -> assignPendingMedia(Facing.FRONT) }
            .setCancelable(false)
            .show()
    }

    private fun assignPendingMedia(target: Facing) {
        val path = pendingMediaPath ?: return
        val type = pendingMediaType ?: return

        if (type == MediaType.VIDEO) {
            askLoopOrOnce(target, path)
        } else {
            applyAssignment(target, path, type, loop = false)
        }
    }

    private fun askLoopOrOnce(target: Facing, path: String) {
        AlertDialog.Builder(this)
            .setTitle("Playback")
            .setMessage("Should this video loop, or play once?")
            .setPositiveButton("Loop") { _, _ -> applyAssignment(target, path, MediaType.VIDEO, loop = true) }
            .setNegativeButton("Play once") { _, _ -> applyAssignment(target, path, MediaType.VIDEO, loop = false) }
            .setCancelable(false)
            .show()
    }

    private fun applyAssignment(target: Facing, path: String, type: MediaType, loop: Boolean) {
        val state = if (target == Facing.BACK) backState else frontState
        state.isLive = false
        state.mediaPath = path
        state.mediaType = type
        state.loop = loop

        pendingMediaPath = null
        pendingMediaType = null
        saveState()

        // If we just assigned media to the lens that's currently on screen, show it now.
        if (target == facing) renderCurrentState()
    }

    // ---- Recording (screen + mic, via MediaProjection) ----
    private fun toggleRecording() {
        if (!isRecording) {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
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
        binding.btnRecord.text = if (isRecording) "STOP" else "REC"
    }

    // ---- Persistence ----
    private fun saveState() {
        prefs.edit().apply {
            putString("facing", facing.name)
            putBoolean("back_live", backState.isLive)
            putString("back_path", backState.mediaPath)
            putString("back_type", backState.mediaType?.name)
            putBoolean("back_loop", backState.loop)
            putBoolean("front_live", frontState.isLive)
            putString("front_path", frontState.mediaPath)
            putString("front_type", frontState.mediaType?.name)
            putBoolean("front_loop", frontState.loop)
            apply()
        }
    }

    private fun loadState() {
        facing = Facing.valueOf(prefs.getString("facing", Facing.BACK.name) ?: Facing.BACK.name)

        backState = LensState(
            isLive = prefs.getBoolean("back_live", true),
            mediaPath = prefs.getString("back_path", null),
            mediaType = prefs.getString("back_type", null)?.let { MediaType.valueOf(it) },
            loop = prefs.getBoolean("back_loop", true)
        )
        frontState = LensState(
            isLive = prefs.getBoolean("front_live", true),
            mediaPath = prefs.getString("front_path", null),
            mediaType = prefs.getString("front_type", null)?.let { MediaType.valueOf(it) },
            loop = prefs.getBoolean("front_loop", true)
        )

        // If a file was somehow removed, fall back to live rather than showing nothing.
        if (backState.mediaPath != null && !File(backState.mediaPath!!).exists()) {
            backState = LensState()
        }
        if (frontState.mediaPath != null && !File(frontState.mediaPath!!).exists()) {
            frontState = LensState()
        }
    }
}
