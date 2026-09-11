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
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var osdTimestamp: TextView
    private lateinit var tvRecElapsed: TextView
    private lateinit var btnPhoto: Button
    private lateinit var btnRecord: Button
    private lateinit var btnSwitch: Button

    private val effectExecutor = Executors.newSingleThreadExecutor()
    private val processor = VhsEffectProcessor()
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var activeRecording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val orientationListener by lazy {
        object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = previewView.display?.rotation ?: return
                preview?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
            }
        }
    }

    private val osdHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("MMM dd yyyy  hh:mm:ss a", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var startMillis = System.currentTimeMillis()

    private val osdTicker = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - startMillis) / 1000
            val line1 = "PLAY \u25B6  SP %d:%02d:%02d"
                .format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
            val line2 = dateFormat.format(Date())
            osdTimestamp.text = "$line1\n$line2"
            processor.osdBitmap.set(buildOsdBitmap(line1, line2))
            osdHandler.postDelayed(this, 500)
        }
    }

    /** Transparent bitmap carrying the two OSD lines; uploaded to GL by the
     *  effect processor so the stamp is baked into preview, video, photos. */
    private fun buildOsdBitmap(line1: String, line2: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF5F5F5.toInt()
            textSize = 46f
            typeface = android.graphics.Typeface.MONOSPACE
            setShadowLayer(8f, 0f, 0f, 0xFFFFFFFF.toInt())
        }
        val w = (maxOf(paint.measureText(line1), paint.measureText(line2)) + 24).toInt()
        val h = (46f * 2 * 1.35f + 24).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawText(line1, 12f, 12f + 46f, paint)
        canvas.drawText(line2, 12f, 12f + 46f + 46f * 1.35f, paint)
        return bmp
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
        btnSwitch = findViewById(R.id.btnSwitch)

        btnPhoto.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnSwitch.setOnClickListener { switchCamera() }

        // Tap to focus/meter at the touched point
        previewView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }

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

            val imageCapture = ImageCapture.Builder()
                .setTargetRotation(displayRotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            this.imageCapture = imageCapture

            val effect = VhsEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE
                    or CameraEffect.IMAGE_CAPTURE,
                effectExecutor, processor)

            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .addUseCase(imageCapture)
                .addEffect(effect)
                .build()

            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, useCaseGroup)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchCamera() {
        activeRecording?.stop()
        activeRecording = null
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        startCamera()
    }

    // ---- Photo capture: full-res JPEG through the effect (shader + OSD baked) ----

    private fun takePhoto() {
        val ic = imageCapture ?: return
        btnPhoto.isEnabled = false

        val name = "VHS_${fileStamp.format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VhsCam")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()

        ic.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    btnPhoto.isEnabled = true
                    Toast.makeText(this@MainActivity, "Saved $name", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exception: ImageCaptureException) {
                    btnPhoto.isEnabled = true
                    Toast.makeText(this@MainActivity,
                        "Photo failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    // ---- Video recording (effect + OSD in pipeline) ----

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
