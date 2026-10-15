package com.synthalorian.vhscam

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import java.util.concurrent.Executor

/**
 * Applies the VHS shader inside the CameraX pipeline so the effect lands
 * in both the preview and the recorded video.
 *
 * The constructor that takes a transformation mode is library-group restricted in
 * CameraX 1.4; we need TRANSFORMATION_CAMERA_AND_SURFACE_ROTATION so the shader
 * output keeps the camera orientation, so the RestrictedApi check is suppressed here.
 */
@SuppressLint("RestrictedApi")
class VhsEffect(
    targets: Int,
    executor: Executor,
    processor: SurfaceProcessor,
) : CameraEffect(
    targets,
    TRANSFORMATION_CAMERA_AND_SURFACE_ROTATION,
    executor,
    processor,
    { t -> Log.e("VhsEffect", "Effect error", t) }
)
