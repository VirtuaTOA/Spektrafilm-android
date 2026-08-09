/*
 * Spektrafilm for Android — GL viewfinder for the in-app camera. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A GLES 3.0 surface that draws the live camera stream and (optionally) applies a
 * baked film-stock LUT to it. The camera-fed sibling of LutGpuPreview: same 3D-LUT
 * upload and trilinear sample, but the source is a samplerExternalOES fed by a
 * SurfaceTexture instead of a still LinearImage.
 *
 * WHY A LUT AND NOT THE ENGINE: Phase 0 measured a 384px draft render at ~220 ms
 * (~4.5 fps), and even if that were fast enough the engine saturates every CPU core
 * — a viewfinder runs for minutes and would thermally throttle out of any frame
 * rate it hit cold. The GPU LUT is nearly free. See docs/CAMERA_PLAN.md §4.
 *
 * PIPELINE PER FRAGMENT (order matters):
 *   external camera sample (near-linear: CameraSession disables the ISP tone curve,
 *   sharpening, NR and the AWB colour matrix)
 *     -> exposure gain          (the LUT carries none; see below)
 *     -> clamp to [0,1]         (the LUT's linear-ProPhoto domain)
 *     -> trilinear 3D LUT       (the film look)
 *
 * EXPOSURE: the baked LUT is emitted at UNITY gain because a pointwise LUT cannot
 * carry auto-exposure — AE meters a whole image and the bake's input is a synthetic
 * lattice. The gain therefore arrives as uExposureGain, captured by the meter/lock
 * button from SpektraEngine.exposureGain (the engine's own metering, so it equals
 * what a capture's simulate() will apply). Without it the image renders dark with
 * lifted shadows — the scene sits in the film curve's toe.
 *
 * ORIENTATION: the camera buffer arrives in SENSOR orientation, so it must be
 * rotated for display. [rotationDegrees] is (sensorOrientation - displayRotation)
 * for a back camera; the rotation is applied to the quad's UVs BEFORE the
 * SurfaceTexture transform matrix (which handles the source crop and vertical flip),
 * and the letterbox aspect swaps w/h at 90/270. THIS IS THE PART TO VERIFY FIRST on
 * a real device — a sideways or stretched viewfinder and a wrong LUT look alike
 * from a distance, which is why [lut] is nullable: null draws a plain passthrough.
 */
package com.spectrafilm.app

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Compose host for the camera viewfinder.
 *
 * [onSurfaceReady] is invoked (main thread) with a Surface wrapping the GL
 * SurfaceTexture once the GL context exists — that is the signal to open the camera.
 * It can fire again if the GL context is recreated (backgrounding), in which case the
 * caller must reopen the session against the NEW surface; the old one is dead.
 *
 * [lut] null => plain camera passthrough (the orientation checkpoint).
 */
@Composable
fun CameraGlPreview(
    uvRotationDegrees: Int,
    displayAspect: Float,
    bufferWidth: Int,
    bufferHeight: Int,
    modifier: Modifier = Modifier,
    lut: CubeLut? = null,
    exposureGain: Float = 1f,
    onSurfaceReady: (Surface) -> Unit,
    onUnavailable: () -> Unit = {},
) {
    val ready = rememberUpdatedState(onSurfaceReady)
    val unavailable = rememberUpdatedState(onUnavailable)
    val renderer = remember {
        CameraLutRenderer(
            onSurfaceReady = { s -> ready.value(s) },
            onUnavailable = { unavailable.value() },
        )
    }
    renderer.setBufferSize(bufferWidth, bufferHeight)
    renderer.setDisplayAspect(displayAspect)
    DisposableEffect(Unit) { onDispose { renderer.release() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                // Camera-driven: SurfaceTexture.onFrameAvailable requests each render,
                // so we draw exactly one frame per camera frame instead of spinning.
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                renderer.attachView(this)
            }
        },
        update = { renderer.submit(uvRotationDegrees, lut, exposureGain) },
    )
}

private class CameraLutRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onUnavailable: () -> Unit,
) : GLSurfaceView.Renderer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: GLSurfaceView? = null

    private var program = 0
    private var camTex = 0
    private var lutTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val texMatrix = FloatArray(16)
    private var viewW = 0
    private var viewH = 0

    // REQUIRED for a standalone SurfaceTexture used as a camera output: without
    // setDefaultBufferSize the producer has no agreed buffer geometry and the
    // consumer can end up with nothing to sample. Set before the Surface is handed
    // to the camera.
    @Volatile private var bufW = 1920
    @Volatile private var bufH = 1080

    // TWO SEPARATE ROTATIONS — conflating them was the "stretched, black bars" bug.
    //
    //   uvRotation   rotates the SAMPLED CONTENT in the shader.
    //   displayAspect is the shape of the scene ON SCREEN.
    //
    // They are not the same thing, because this device's SurfaceTexture transform
    // matrix ALREADY carries the sensor->display rotation (measured tm column0 =
    // [0,-1,0,0]). So the content arrives upright with uvRotation = 0, while the
    // scene's displayed aspect is still the sensor buffer's aspect INVERTED (a
    // landscape 16:9 sensor showing a portrait scene). Driving the letterbox from
    // uvRotation assumed a landscape scene and stretched the portrait content to fit.
    @Volatile private var rotation = 0
    @Volatile private var displayAspect = 9f / 16f
    @Volatile private var gain = 1f
    @Volatile private var pendingLut: CubeLut? = null
    @Volatile private var haveLut = false
    private var reportedFail = false
    @Volatile private var frameCount = 0L
    private var drawCount = 0L
    private val logged = HashSet<String>()

    /** Log a diagnostic once per distinct message — onDrawFrame runs at frame rate. */
    private fun logOnce(msg: String) {
        if (logged.add(msg)) Diag.w("camera gl: $msg")
    }

    fun attachView(v: GLSurfaceView) { view = v }

    fun setBufferSize(w: Int, h: Int) {
        if (w > 0 && h > 0) { bufW = w; bufH = h }
    }

    fun setDisplayAspect(a: Float) {
        if (a.isFinite() && a > 0f) displayAspect = a
    }

    fun submit(rotationDegrees: Int, lut: CubeLut?, exposureGain: Float) {
        rotation = ((rotationDegrees % 360) + 360) % 360
        gain = if (exposureGain.isFinite() && exposureGain > 0f) exposureGain else 1f
        if (lut !== null) pendingLut = lut else haveLut = false
        view?.requestRender()
    }

    fun release() {
        // Detach the frame callback before tearing down: a frame arriving after the
        // SurfaceTexture is released would call into freed native state.
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surface?.release() }
        runCatching { surfaceTexture?.release() }
        surface = null
        surfaceTexture = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram()
        if (program == 0) {
            if (!reportedFail) { reportedFail = true; mainHandler.post(onUnavailable) }
            return
        }
        val tex = IntArray(2)
        GLES30.glGenTextures(2, tex, 0)
        camTex = tex[0]
        lutTex = tex[1]

        // A 1x1x1 placeholder so texture unit 1 ALWAYS holds a valid sampler3D-compatible
        // texture. GL validates EVERY active sampler at draw time, regardless of dynamic
        // branching in the shader: leaving uLut defaulted to unit 0 (where the EXTERNAL_OES
        // camera texture lives) is a sampler-type/target mismatch, which makes glDrawArrays
        // fail with GL_INVALID_OPERATION and DISCARDS THE DRAW — a black screen with a
        // perfectly working camera and shader. Measured: 2144 x 0x502 before this fix.
        val zero = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        zero.put(floatArrayOf(0f, 0f, 0f)); zero.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, 1, 1, 1, 0,
                            GLES30.GL_RGB, GLES30.GL_FLOAT, zero)

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // A fresh GL context invalidates any previous SurfaceTexture, so drop the old
        // one and hand the caller a new Surface to reopen the camera against.
        release()
        haveLut = false
        val st = SurfaceTexture(camTex)
        st.setDefaultBufferSize(bufW, bufH)
        st.setOnFrameAvailableListener {
            frameCount++
            view?.requestRender()
        }
        Diag.i("camera gl: surface ready tex=$camTex buffer=${bufW}x$bufH")
        surfaceTexture = st
        val s = Surface(st)
        surface = s
        mainHandler.post { onSurfaceReady(s) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        if (program == 0) { logOnce("draw: program == 0, nothing to draw"); return }

        // Pull the newest camera frame into the external texture and take its transform.
        runCatching {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        }.onFailure {
            logOnce("draw: updateTexImage failed: ${it.message}")
            return
        }
        drawCount++
        if (drawCount == 1L || drawCount == 30L) {
            Diag.i("camera gl: draw #$drawCount frames=$frameCount rot=$rotation " +
                "view=${viewW}x$viewH tm=[${texMatrix.take(4).joinToString()}]")
        }

        pendingLut?.let { uploadLut(it); pendingLut = null }

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCam"), 0)
        // Unconditional: see the placeholder note in onSurfaceCreated. uLut must resolve
        // to a bound 3D texture on every draw or the whole draw is rejected.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uUseLut"), if (haveLut) 1 else 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uExposureGain"), gain)
        GLES30.glUniformMatrix4fv(
            GLES30.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0,
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(program, "uRotation"),
            Math.toRadians(rotation.toDouble()).toFloat(),
        )
        // Letterbox: the camera stream is 16:9 in SENSOR orientation, so at 90/270 the
        // displayed aspect is its reciprocal. Bars stay black rather than stretching.
        // Letterbox to the SCENE's on-screen shape, supplied by the caller from the
        // sensor-vs-display geometry — never inferred from uvRotation (see the field note).
        val imgA = displayAspect
        var sx = 1f
        var sy = 1f
        if (viewW > 0 && viewH > 0) {
            val viewA = viewW.toFloat() / viewH
            if (viewA > imgA) sx = imgA / viewA else sy = viewA / imgA
        }
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uScale"), sx, sy)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // A draw rejected by GL renders NOTHING and is otherwise silent, so check once.
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            logOnce("draw rejected, glGetError=0x${Integer.toHexString(err)} (draw is discarded)")
        }
    }

    /** Same (B,G,R)-axis mapping as LutGpuPreview — bakeCubeLut emits blue-fastest. */
    private fun uploadLut(lut: CubeLut) {
        val n = lut.size
        val fb: FloatBuffer = ByteBuffer.allocateDirect(lut.rgb.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(lut.rgb); fb.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0,
            GLES30.GL_RGB, GLES30.GL_FLOAT, fb,
        )
        haveLut = true
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, VERT)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, FRAG)
        if (vs == 0 || fs == 0) return 0
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Diag.e("camera gl: program link FAILED: ${GLES30.glGetProgramInfoLog(p)}", null)
            GLES30.glDeleteProgram(p)
            return 0
        }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val kind = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
            Diag.e("camera gl: $kind shader FAILED: ${GLES30.glGetShaderInfoLog(s)}", null)
            GLES30.glDeleteShader(s)
            return 0
        }
        return s
    }

    companion object {
        // Quad from gl_VertexID. The DISPLAY rotation is applied to the UVs about their
        // centre before uTexMatrix (which carries the source crop + vertical flip), so
        // the geometry stays a plain letterboxed rectangle.
        private const val VERT = """#version 300 es
            uniform mat4 uTexMatrix;
            uniform vec2 uScale;
            uniform float uRotation;
            out vec2 vUv;
            void main() {
                float x = float(gl_VertexID & 1);
                float y = float((gl_VertexID >> 1) & 1);
                vec2 q = vec2(x, y);
                float s = sin(uRotation), c = cos(uRotation);
                vec2 d = q - 0.5;
                vec2 r = vec2(c * d.x - s * d.y, s * d.x + c * d.y) + 0.5;
                vUv = (uTexMatrix * vec4(r, 0.0, 1.0)).xy;
                gl_Position = vec4((q * 2.0 - 1.0) * uScale, 0.0, 1.0);
            }
        """
        private const val FRAG = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision highp sampler3D;
            precision highp samplerExternalOES;
            in vec2 vUv;
            uniform samplerExternalOES uCam;
            uniform sampler3D uLut;
            uniform int uUseLut;
            uniform float uExposureGain;
            out vec4 fragColor;
            // Cheap per-pixel hash for dithering. Static (no time term) so the noise does
            // not shimmer between frames.
            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            void main() {
                vec3 cam = texture(uCam, vUv).rgb;
                if (uUseLut == 0) { fragColor = vec4(cam, 1.0); return; }
                // The stream is 8-bit and SCENE-LINEAR, which spends very few code values
                // in the shadows, so gradients arrive already quantised into visible bands
                // — and uExposureGain multiplies those steps. A +/- half-LSB offset
                // decorrelates the quantisation, turning bands into fine noise. It cannot
                // restore lost information; only a 10-bit stream would, and that turned out
                // to be an HDR-pipeline change rather than a bit-depth one (HLG10 re-exposes
                // the whole session about a stop down to reserve specular headroom, which
                // has to be modelled properly). Recorded in docs/CAMERA_PLAN.md.
                float d = (hash12(gl_FragCoord.xy) - 0.5) * (1.0 / 255.0);
                vec3 lin = clamp((cam + d) * uExposureGain, 0.0, 1.0);
                fragColor = vec4(texture(uLut, vec3(lin.b, lin.g, lin.r)).rgb, 1.0);
            }
        """
    }
}
