package com.synth.vhscam

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX [SurfaceProcessor] that runs the VHS fragment shader on every
 * camera frame and renders it into every output surface CameraX hands us
 * (Preview + VideoCapture). Because the effect sits inside the CameraX
 * pipeline, the recorded video has the shader baked in.
 *
 * Also supports grabbing the current filtered frame as a [Bitmap] for
 * photo capture.
 */
class VhsEffectProcessor : SurfaceProcessor {

    private val glThread = HandlerThread("vhs-gl").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { cmd -> glHandler.post(cmd) }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglTempSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglReady = false

    private var inputTexId = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputSize: Size? = null
    @Volatile private var rotationDegrees = 0

    private val outputs = LinkedHashMap<SurfaceOutput, EGLSurface>()

    private var program = 0
    private var osdProgram = 0
    private var osdTexId = 0
    private var osdUploaded: Bitmap? = null
    private var osdTexW = 0
    private var osdTexH = 0
    private val texMatrix = FloatArray(16)
    private val startTimeNanos = System.nanoTime()

    /** Timestamp OSD, baked into preview, video AND photos. MainActivity
     *  pushes a fresh bitmap ~2x/sec; GL thread uploads on change. */
    val osdBitmap = AtomicReference<Bitmap?>(null)

    private val osdQuadData = floatArrayOf(
        // x, y, u, v  (v flipped: GL tex origin is bottom-left, bitmap top-left)
        0f, 0f, 0f, 1f,
        1f, 0f, 1f, 1f,
        0f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )
    private val osdQuadBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(osdQuadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(osdQuadData).apply { position(0) }

    private val quadData = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f
    )
    private val quadBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(quadData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(quadData).apply { position(0) }

    // ---- SurfaceProcessor ----

    override fun onInputSurface(request: SurfaceRequest) {
        request.setTransformationInfoListener(glExecutor) { info ->
            if (info.rotationDegrees != rotationDegrees) {
                Log.d(TAG, "rotationDegrees: $rotationDegrees -> ${info.rotationDegrees}, " +
                    "targetRotation=${info.targetRotation}, mirroring=${info.isMirroring}")
            }
            rotationDegrees = info.rotationDegrees
        }
        glHandler.post {
            ensureEgl()
            inputTexId = createOesTexture()
            inputSize = request.resolution
            val st = SurfaceTexture(inputTexId)
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(st)
            inputSurfaceTexture = st
            inputSurface = surface
            request.provideSurface(surface, glExecutor) {
                glHandler.post { releaseInput() }
            }
            st.setOnFrameAvailableListener({ onFrameAvailable() }, glHandler)
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            ensureEgl()
            val surface = surfaceOutput.getSurface(glExecutor) { event ->
                if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    glHandler.post { removeOutput(surfaceOutput) }
                }
            }
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Failed to create EGLSurface for output, err=0x${Integer.toHexString(EGL14.eglGetError())}")
                surfaceOutput.close()
                return@post
            }
            outputs[surfaceOutput] = eglSurface
        }
    }

    // ---- Frame rendering ----

    private fun onFrameAvailable() {
        val st = inputSurfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (e: Exception) {
            return
        }
        val timestamp = st.timestamp

        for ((output, eglSurface) in outputs) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) continue
            val size = output.size
            GLES20.glViewport(0, 0, size.width, size.height)
            drawFrame()
            drawOsd(size.width, size.height)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestamp)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
    }

    /** Alpha-blend the timestamp OSD quad into the bottom-left corner. */
    private fun drawOsd(viewW: Int, viewH: Int) {
        val bmp = osdBitmap.get() ?: return
        if (osdTexId == 0) {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            osdTexId = tex[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        if (bmp !== osdUploaded && !bmp.isRecycled) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTexId)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            osdUploaded?.let { old -> if (!old.isRecycled) old.recycle() }
            osdUploaded = bmp
            osdTexW = bmp.width
            osdTexH = bmp.height
        }
        if (osdTexW == 0) return

        // Bottom-left, ~52% of viewport width, aspect preserved
        val drawW = viewW * 0.52f
        val drawH = drawW * osdTexH / osdTexW
        val x0 = -1f + 0.03f * 2f
        val y0 = -1f + 0.05f * 2f
        val x1 = x0 + 2f * drawW / viewW
        val y1 = y0 + 2f * drawH / viewH

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(osdProgram)

        val posLoc = GLES20.glGetAttribLocation(osdProgram, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(osdProgram, "aUV")
        val scaleLoc = GLES20.glGetUniformLocation(osdProgram, "uScale")
        val offLoc = GLES20.glGetUniformLocation(osdProgram, "uOffset")
        GLES20.glUniform2f(scaleLoc, (x1 - x0) / 2f, (y1 - y0) / 2f)
        GLES20.glUniform2f(offLoc, (x0 + x1) / 2f, (y0 + y1) / 2f)

        osdQuadBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, osdQuadBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        osdQuadBuffer.position(2)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, osdQuadBuffer)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(osdProgram, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawFrame() {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val uvLoc = GLES20.glGetAttribLocation(program, "aUV")
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(uvLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTime"),
            (System.nanoTime() - startTimeNanos) / 1_000_000_000f)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uRotation"), rotationDegrees)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    // ---- EGL plumbing ----

    private fun ensureEgl() {
        if (eglReady) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (!EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] <= 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        eglConfig = configs[0]

        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        eglTempSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglTempSurface, eglTempSurface, eglContext)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        osdProgram = buildProgram(OSD_VERTEX_SHADER, OSD_FRAGMENT_SHADER)
        eglReady = true
    }

    private fun removeOutput(output: SurfaceOutput) {
        outputs.remove(output)?.let { eglSurface ->
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        output.close()
    }

    private fun releaseInput() {
        inputSurfaceTexture?.setOnFrameAvailableListener(null)
        inputSurfaceTexture?.release()
        inputSurfaceTexture = null
        inputSurface?.release()
        inputSurface = null
        if (inputTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTexId), 0)
            inputTexId = 0
        }
    }

    fun release() {
        glHandler.post {
            releaseInput()
            outputs.keys.toList().forEach { removeOutput(it) }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglTempSurface != EGL14.EGL_NO_SURFACE)
                    EGL14.eglDestroySurface(eglDisplay, eglTempSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglReady = false
            glThread.quitSafely()
        }
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                throw RuntimeException("Shader compile failed")
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            throw RuntimeException("Program link failed")
        }
        return prog
    }

    companion object {
        private const val TAG = "VhsEffectProcessor"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val OSD_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aUV;
            uniform vec2 uScale;
            uniform vec2 uOffset;
            varying vec2 vUV;
            void main() {
                gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
                vUV = aUV;
            }
        """

        private const val OSD_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUV;
            void main() {
                gl_FragColor = texture2D(uTexture, vUV);
            }
        """

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aUV;
            uniform mat4 uTexMatrix;
            uniform int uRotation;
            varying vec2 vUV;

            vec2 rotateUV(vec2 uv, int rot) {
                vec2 c = uv - 0.5;
                if (rot == 90)       c = vec2(c.y, -c.x);
                else if (rot == 180) c = vec2(-c.x, -c.y);
                else if (rot == 270) c = vec2(-c.y, c.x);
                return c + 0.5;
            }

            void main() {
                gl_Position = aPosition;
                vec2 uv = (uTexMatrix * vec4(aUV, 0.0, 1.0)).xy;
                uv = rotateUV(uv, uRotation);
                vUV = uv;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform samplerExternalOES uTexture;
            uniform float uTime;
            varying vec2 vUV;

            float rand(vec2 co) {
                return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
            }

            void main() {
                vec2 uv = vUV;

                // Subtle horizontal wobble (tape instability)
                uv.x += sin(uv.y * 40.0 + uTime * 3.0) * 0.0012;

                // Tracking band glitch: occasional rolling offset
                float band = fract(uTime * 0.13);
                if (abs(uv.y - band) < 0.015) {
                    uv.x += (rand(vec2(uTime, uv.y)) - 0.5) * 0.06;
                }

                // Chromatic aberration, stronger at edges
                vec2 fromCenter = uv - 0.5;
                float aberr = 0.0025 * (1.0 + 4.0 * dot(fromCenter, fromCenter));
                vec2 rUV = uv + fromCenter * aberr * 8.0;
                vec2 bUV = uv - fromCenter * aberr * 8.0;
                float r = texture2D(uTexture, rUV).r;
                float g = texture2D(uTexture, uv).g;
                float b = texture2D(uTexture, bUV).b;
                vec3 col = vec3(r, g, b);

                // Scanlines
                float scan = sin(uv.y * 800.0) * 0.5 + 0.5;
                col *= 0.85 + 0.15 * scan;

                // VHS softening: slight desaturation + lifted blacks
                float luma = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(col, vec3(luma), 0.25);
                col = col * 0.92 + 0.04;

                // Static noise
                float noise = (rand(uv * uTime) - 0.5) * 0.07;
                col += noise;

                // Vignette
                float vig = 1.0 - dot(fromCenter, fromCenter) * 1.1;
                col *= clamp(vig, 0.0, 1.0);

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }
}
