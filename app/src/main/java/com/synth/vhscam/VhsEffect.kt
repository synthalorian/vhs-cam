package com.synth.vhscam

import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import java.util.concurrent.Executor

/**
 * Applies the VHS shader inside the CameraX pipeline so the effect lands
 * in both the preview and the recorded video.
 */
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
