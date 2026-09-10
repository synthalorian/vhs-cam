package com.synth.vhscam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var osdTimestamp: TextView
    private lateinit var tvRecElapsed: TextView
    private lateinit var btnPhoto: Button
    private lateinit var btnRecord: Button

    private val effectExecutor = Executors.newSingleThreadExecutor()
    private val processor = VhsEffectProcessor()
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = previewView.display?.rotation ?: return
                preview?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
    }

    private val osdHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("MMM dd yyyy\nhh:mm:ss a", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var startMillis = System.currentTimeMillis()

    private val osdTicker = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - startMillis) / 1000
            osdTimestamp.text = "PLAY \u25B6  SP %d:%02d:%02d\n%s"
                .format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60,
                    dateFormat.format(Date()))
            osdHandler.postDelayed(this, 500)
        }
    }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) startCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        osdTimestamp = findViewById(R.id.osdTimestamp)
        tvRecElapsed = findViewById(R.id.tvRecElapsed)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnRecord = findViewById(R.id.btnRecord)

        btnPhoto.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }

        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCamera()
        else permissionsLauncher.launch(missing.toTypedArray())

        osdHandler.post(osdTicker)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val displayRotation = previewView.display?.rotation
                ?: android.view.Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(displayRotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            this.preview = preview

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
            val videoCapture = VideoCapture.Builder(recorder)
                .setTargetRotation(displayRotation)
                .build()
            this.videoCapture = videoCapture

            val effect = VhsEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                effectExecutor, processor)

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .addEffect(effect)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Photo capture (filtered frame) ----

    private fun takePhoto() {
        btnPhoto.isEnabled = false
        processor.takePhoto { bitmap ->
            runOnUiThread { btnPhoto.isEnabled = true }
            val stamped = drawOsdOn(bitmap)
            savePhoto(stamped)
        }
    }

    private fun drawOsdOn(bitmap: Bitmap): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val textSize = out.width / 22f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F5F5.toInt()
            this.textSize = textSize
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(textSize / 6f, 0f, 0f, 0xFFFFFFFF.toInt())
        }
        val elapsed = (System.currentTimeMillis() - startMillis) / 1000
        val lines = listOf(
            "PLAY \u25B6  SP %d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60),
            dateFormat.format(Date())
        )
        val margin = out.width / 18f
        var y = out.height - margin - textSize * (lines.size - 1) * 1.2f
        for (line in lines) {
            canvas.drawText(line, margin, y, paint)
            y += textSize * 1.2f
        }
        if (out != bitmap) bitmap.recycle()
        return out
    }

    private fun savePhoto(bitmap: Bitmap) {
        val name = "VHS_${fileStamp.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VhsCam")
            }
        }
        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val ok = uri?.let {
            contentResolver.openOutputStream(it)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            } == true
        } == true
        bitmap.recycle()
        runOnUiThread {
            Toast.makeText(this,
                if (ok) "Saved $name" else "Photo save failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Video recording (effect in pipeline) ----

    private fun toggleRecording() {
        activeRecording?.let {
            it.stop()
            activeRecording = null
            return
        }
        val vc = videoCapture ?: return

        val name = "VHS_${fileStamp.format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VhsCam")
            }
        }
        val outputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        var pending = vc.output.prepareRecording(this, outputOptions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    tvRecElapsed.visibility = TextView.VISIBLE
                    btnRecord.text = "\u25A0 STOP"
                }
                is VideoRecordEvent.Status -> {
                    val ns = event.recordingStats.recordedDurationNanos
                    val sec = ns / 1_000_000_000
                    tvRecElapsed.text = "\u25CF REC %d:%02d".format(sec / 60, sec % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    tvRecElapsed.visibility = TextView.GONE
                    btnRecord.text = "\u25CF RECORD"
                    activeRecording = null
                    if (!event.hasError()) {
                        Toast.makeText(this, "Saved $name", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this,
                            "Recording error: ${event.error}", Toast.LENGTH_LONG).show()
                    }
                }
                else -> {}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        osdHandler.post(osdTicker)
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        osdHandler.removeCallbacks(osdTicker)
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        processor.release()
        effectExecutor.shutdown()
    }
}
