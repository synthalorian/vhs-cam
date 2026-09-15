# VHS Cam (spike)

Retro VHS camcorder app — feasibility spike for a CameraX + OpenGL fragment-shader
effects pipeline on Android.

## What this validates

- Real-time fragment-shader effects (scanlines, chromatic aberration, vignette,
  tracking-band glitch, tape wobble, static noise) on the **live camera preview**
- Same shader applied inside the CameraX pipeline via a custom
  `CameraEffect` + `SurfaceProcessor` (`VhsEffectProcessor`), so the effect is
  **baked into recorded video** (no post-processing)
- Photo capture of the filtered frame (FBO readback of the shader output),
  VHS OSD timestamp drawn on top, saved to `Pictures/VhsCam` via MediaStore
- Video recording with audio to `Movies/VhsCam` via CameraX `VideoCapture`/`Recorder`
- Orientation handled via `SurfaceRequest.TransformationInfo.rotationDegrees`
  applied in the vertex shader, plus `targetRotation` on all use cases with an
  `OrientationEventListener` (no portrait lock)

## Architecture

```
CameraX UseCaseGroup( Preview + VideoCapture, effect = VhsEffect )
        │  input SurfaceRequest (sensor-orientation frames)
        ▼
VhsEffectProcessor (GL thread, EGL14, GLES2)
        │  OES texture → vertex shader (rotate by rotationDegrees)
        │  → fragment shader (VHS look)
        ├──► PreviewView output surface
        └──► VideoCapture (MediaCodec input surface, EGL_RECORDABLE_ANDROID)
        └──► FBO readback → Bitmap → MediaStore (photo button)
```

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Notes / limits (spike-grade)

- Photo resolution = stream resolution (~1080p), not full sensor resolution
- Timestamp OSD is baked into photos but **not** into recorded video
  (would need text rendering in GL or a second overlay pass)
- Back camera only; front-camera mirroring not handled
- Verified on Pixel 8a (Android 16)

Made by synth with blackclaw ⚫🦞
