/*
 * Spektrafilm for Android — Camera2 session for the in-app camera. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Opens a camera and drives a repeating preview request into a caller-supplied
 * Surface (the GL viewfinder's SurfaceTexture). See docs/CAMERA_PLAN.md §5.
 *
 * WHY CAMERA2 AND NOT CAMERAX: the three things this feature needs are all
 * Camera2-native and awkward or unsupported through CameraX —
 * OutputConfiguration.setPhysicalCameraId (the only way to reach the telephoto,
 * which getCameraIdList() hides by design), the four ISP-disable CaptureRequest
 * keys, and RAW_SENSOR + DngCreator for capture. CameraX's Preview use case is
 * not wanted either: the viewfinder renders through our own GL surface.
 *
 * ISP DISABLE: the engine is a radiometric simulation — the linear pixel value IS
 * an irradiance, which then meets the film's log-sensitivity curves. A tone-mapped,
 * sharpened, saturation-boosted preview is a lie about how much light there was, so
 * the viewfinder would not resemble the RAW capture. Phase 0 measured that this
 * device honours all four disables at 1920x1080 (docs/CAMERA_PLAN.md §4), so we
 * request them and get a near-linear stream. They are requested unconditionally:
 * a device that ignores one simply keeps that processing, which is exactly the
 * pre-existing behaviour, so there is nothing to fall back to.
 *
 * API 28+: the camera screen requires Android 9. Physical-camera selection
 * (setPhysicalCameraId) and SessionConfiguration both land at 28, and maintaining
 * a second legacy session path for a feature that cannot reach the telephoto
 * anyway is not worth the surface area. Callers gate on [isSupported].
 */
package com.spectrafilm.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import java.io.File
import java.io.FileOutputStream
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** One selectable lens: a physical camera id behind [logicalId], with its RAW capability. */
data class LensOption(
    val logicalId: String,
    val physicalId: String?,
    val focalMm: Float,
    /** 35mm-equivalent focal length, rounded — what a photographer actually reads. */
    val equivFocalMm: Int,
    val label: String,
    val rawSize: Size?,
    val hardwareLevel: Int,
    /** Closest focus in diopters (1/m); 0 = fixed-focus lens, so no manual focus. */
    val minFocusDiopters: Float = 0f,
) {
    val supportsManualFocus: Boolean get() = minFocusDiopters > 0f
    val supportsRaw: Boolean get() = rawSize != null
}

object CameraInventory {

    /** The camera screen needs API 28 (SessionConfiguration + setPhysicalCameraId). */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Enumerate the rear lenses, newest-useful-first (main, then ultrawide, then tele
     * by focal length). Physical sub-cameras are included because a telephoto is
     * normally reachable ONLY that way — `getCameraIdList()` omits the physical lenses
     * behind a logical multi-camera by design (Phase 0 found the 3x tele as physical
     * id 6 on this device, absent from the public list).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    fun rearLenses(ctx: Context): List<LensOption> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<LensOption>()
        for (logicalId in runCatching { mgr.cameraIdList }.getOrDefault(emptyArray())) {
            val c = runCatching { mgr.getCameraCharacteristics(logicalId) }.getOrNull() ?: continue
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val physIds = runCatching { c.physicalCameraIds }.getOrNull().orEmpty()
            if (physIds.isEmpty()) {
                out += lensOf(c, logicalId, null) ?: continue
            } else {
                for (pid in physIds) {
                    val pc = runCatching { mgr.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    out += lensOf(pc, logicalId, pid) ?: continue
                }
            }
        }
        // Dedupe by focal length (the ultrawide is commonly exposed both as its own
        // logical id AND as a physical sub-camera); prefer the RAW-capable entry.
        return out.groupBy { "%.1f".format(it.focalMm) }
            .map { (_, v) -> v.firstOrNull { it.supportsRaw } ?: v.first() }
            .sortedBy { it.focalMm }
    }

    private fun lensOf(c: CameraCharacteristics, logicalId: String, physId: String?): LensOption? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return null
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSize = runCatching { map?.getOutputSizes(ImageFormat.RAW_SENSOR) }
            .getOrNull()?.maxByOrNull { it.width.toLong() * it.height }
        val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
        val equiv = equivalentFocal(c, focal)
        // Diopters (1/m) at the closest focus. 0 means a fixed-focus lens, which cannot be
        // focused manually — the UI hides MF for those rather than offering a dead control.
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        return LensOption(
            logicalId = logicalId, physicalId = physId, focalMm = focal,
            equivFocalMm = equiv, label = "${equiv}mm", rawSize = rawSize,
            hardwareLevel = level, minFocusDiopters = minFocus,
        )
    }

    /**
     * 35mm-equivalent focal length: actual focal x crop factor, where the crop factor is
     * the 35mm frame diagonal (43.27 mm) over this sensor's diagonal.
     *
     * The physical size reported covers the FULL pixel array, but only the active array is
     * imaged, so it is scaled by the active/pixel ratio first — otherwise a sensor that
     * crops for stills reports a wider equivalent than it actually delivers.
     */
    private fun equivalentFocal(c: CameraCharacteristics, focalMm: Float): Int {
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (phys == null || phys.width <= 0f || phys.height <= 0f) return focalMm.roundToInt()
        var wMm = phys.width
        var hMm = phys.height
        if (pixels != null && active != null && pixels.width > 0 && pixels.height > 0) {
            wMm *= active.width().toFloat() / pixels.width
            hMm *= active.height().toFloat() / pixels.height
        }
        val diag = kotlin.math.hypot(wMm.toDouble(), hMm.toDouble())
        if (diag <= 0.0) return focalMm.roundToInt()
        return (focalMm * (43.2666 / diag)).roundToInt()
    }

    /** Sensor orientation (degrees) for the logical camera driving the stream. */
    fun sensorOrientation(ctx: Context, logicalId: String): Int {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            mgr.getCameraCharacteristics(logicalId).get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 90
    }

    /** Supported preview size nearest 1920x1080 — the size Phase 0 validated the ISP keys at. */
    fun previewSize(ctx: Context, logicalId: String): Size {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val map = runCatching {
            mgr.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull() ?: return Size(1920, 1080)
        val target = 1920L * 1080
        return map.getOutputSizes(SurfaceTextureClass)
            ?.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) }
            ?: Size(1920, 1080)
    }

    private val SurfaceTextureClass = android.graphics.SurfaceTexture::class.java
}

// Metering stream size. Auto-exposure meters a max-256 downscale internally, so a
// larger stream would cost bandwidth for a number that would not change.
private const val METER_W = 320
private const val METER_H = 240

/**
 * A live Camera2 preview into [surface]. One session per [open]; call [close] when done.
 * All camera callbacks land on a private HandlerThread, never the main thread.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
class CameraSession(
    private val ctx: Context,
    private val onError: (String) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var meterReader: ImageReader? = null

    // RAW_SENSOR output for the shutter. Separate from the preview and metering streams
    // because a still capture wants the sensor's full resolution, and DngCreator needs the
    // untouched Bayer frame plus the exact CaptureResult that produced it.
    private var rawReader: ImageReader? = null
    private var captureChars: CameraCharacteristics? = null
    // A capture is an Image and a TotalCaptureResult that arrive on different callbacks;
    // DngCreator needs BOTH. One shot is in flight at a time, so a single pending slot each
    // is sufficient — and simpler than a timestamp-keyed map that could leak on a dropped
    // frame. Whichever arrives second does the write.
    @Volatile private var pendingImage: Image? = null
    @Volatile private var pendingResult: TotalCaptureResult? = null
    @Volatile private var captureTarget: File? = null
    @Volatile private var captureDone: ((File?, String?) -> Unit)? = null

    // Latest luma plane, kept up to date by the ImageReader callback. The viewfinder
    // itself renders from the GL external texture; this SECOND, tiny output exists only
    // so the exposure can be metered on the CPU — SpektraEngine.meterExposureEv needs a
    // pixel buffer, and the GL texture is not readable without a round trip.
    //
    // Copied on every frame rather than acquired on demand: an ImageReader whose images
    // are never acquired fills its queue and STALLS the capture session. Copying a
    // quarter-megapixel luma plane per frame is trivial next to the preview itself.
    @Volatile private var latestLuma: FloatArray? = null
    @Volatile private var lumaW = 0
    @Volatile private var lumaH = 0

    @Volatile var aeLocked: Boolean = false
        private set

    /**
     * Whether the preview stream was negotiated as Display P3 rather than sRGB/BT.709.
     * The viewfinder must use the matching primaries matrix — feeding P3 values through the
     * sRGB matrix would under-saturate by as much as the reverse over-saturated.
     */
    @Volatile var previewIsDisplayP3: Boolean = false
        private set

    @Volatile var manualFocus: Boolean = false
        private set

    /** Focus distance in diopters (1/m). 0 = infinity. Only applied in manual mode. */
    @Volatile private var focusDiopters: Float = 0f

    @SuppressLint("MissingPermission")  // caller gates on CameraInventory.hasPermission
    fun open(lens: LensOption, surface: Surface, previewSize: Size) {
        if (!CameraInventory.hasPermission(ctx)) { onError("camera permission not granted"); return }
        close()
        val t = HandlerThread("spk-camera").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        runCatching {
            mgr.openCamera(lens.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    configure(camera, lens, surface, h)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); device = null
                    onError("camera error $error")
                }
            }, h)
        }.onFailure { onError("openCamera failed: ${it.message}") }
    }

    private fun configure(camera: CameraDevice, lens: LensOption, surface: Surface, h: Handler) {
        val outCfg = OutputConfiguration(surface).apply {
            // Select the physical lens. This is the ONLY way to reach the telephoto —
            // it is not in getCameraIdList(). Null means "let the logical camera decide",
            // which lands on the main lens.
            if (lens.physicalId != null) setPhysicalCameraId(lens.physicalId)
        }
        // Metering output. Deliberately tiny: auto-exposure meters a max-256 downscale
        // internally anyway (see spk_meter_exposure_ev), so a larger stream would cost
        // bandwidth for a number that would not change.
        val reader = ImageReader.newInstance(METER_W, METER_H, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r -> captureLuma(r) }, h)
        meterReader = reader
        val meterCfg = OutputConfiguration(reader.surface).apply {
            if (lens.physicalId != null) setPhysicalCameraId(lens.physicalId)
        }

        // RAW still output. LEVEL_3 devices guarantee PRIV(preview) + YUV(small) + RAW(max)
        // as a simultaneous combination, which is exactly this session; LIMITED lenses may
        // refuse it, so the reader is only added when the lens advertises a RAW size and the
        // session falls back to preview-only if configuration fails.
        val rawCfgs = mutableListOf<OutputConfiguration>()
        val rawSize = lens.rawSize
        if (rawSize != null) {
            val rr = ImageReader.newInstance(
                rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2,
            )
            rr.setOnImageAvailableListener({ r ->
                pendingImage = runCatching { r.acquireNextImage() }.getOrNull()
                tryWriteDng()
            }, h)
            rawReader = rr
            captureChars = runCatching {
                (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                    .getCameraCharacteristics(lens.physicalId ?: lens.logicalId)
            }.getOrNull()
            rawCfgs += OutputConfiguration(rr.surface).apply {
                if (lens.physicalId != null) setPhysicalCameraId(lens.physicalId)
            }
        }
        val executor = Executor { r -> h.post(r) }
        val wideGamut = displayP3Supported(lens)
        previewIsDisplayP3 = wideGamut
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    meterReader?.surface?.let { addTarget(it) }
                    applyIspDisables(this)
                    applyFocus(this)
                    set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
                }
                request = b
                runCatching { s.setRepeatingRequest(b.build(), null, h) }
                    .onFailure { onError("setRepeatingRequest failed: ${it.message}") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("capture session configuration failed")
            }
        }
        runCatching {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outCfg, meterCfg) + rawCfgs, executor, cb,
                ).apply {
                    // WIDER PRIMARIES, SAME EXPOSURE. Unlike the HLG10 attempt, a colour-space
                    // profile carries no HDR reference levels — it changes only the primaries,
                    // so the session's exposure semantics are untouched. Requested only when
                    // BOTH outputs support it at STANDARD dynamic range; one session cannot mix.
                    // Explicit setter, not the property: SessionConfiguration's getter
                    // returns ColorSpace while its setter takes ColorSpace.Named, so Kotlin
                    // exposes it as a read-only property.
                    if (wideGamut && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setColorSpace(android.graphics.ColorSpace.Named.DISPLAY_P3)
                    }
                }
            )
        }.onFailure { onError("createCaptureSession failed: ${it.message}") }
    }

    /**
     * Whether Display P3 can be requested for BOTH session outputs at STANDARD dynamic
     * range. The camera stream is otherwise BT.709/sRGB, which CLIPS saturated colour
     * before the spectral engine ever sees it — and a clipped chromaticity makes the
     * spectral reconstruction wrong, not merely the displayed colour.
     *
     * Logs what it found: a Camera2 capability is not a Camera2 result, and this feature
     * has been bitten by assuming otherwise more than once.
     */
    private fun displayP3Supported(lens: LensOption): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching {
            mgr.getCameraCharacteristics(lens.physicalId ?: lens.logicalId)
        }.getOrNull() ?: return false
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES)
            ?: run { Diag.i("camera gamut: no colour-space profiles -> sRGB"); return false }
        val p3 = android.graphics.ColorSpace.Named.DISPLAY_P3
        val std = android.hardware.camera2.params.DynamicRangeProfiles.STANDARD
        // The SurfaceTexture viewfinder is PRIVATE; the metering reader is YUV_420_888.
        val ok = listOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888).all { fmt ->
            val spaces = runCatching {
                profiles.getSupportedColorSpacesForDynamicRange(fmt, std)
            }.getOrDefault(emptySet())
            val has = p3 in spaces
            Diag.i("camera gamut: format=$fmt standard-range spaces=$spaces p3=$has")
            has
        }
        Diag.i("camera gamut: ${if (ok) "requesting DISPLAY_P3" else "staying sRGB/BT.709"}")
        return ok
    }

    /**
     * Request the four ISP disables Phase 0 validated on this device, so the preview
     * stream is near-scene-linear and therefore comparable to the RAW we will capture.
     * COLOR_CORRECTION_MODE is overridden by the AWB routine unless AWB is OFF, so both
     * must be set for the saturation/matrix request to mean anything.
     */
    private fun applyIspDisables(b: CaptureRequest.Builder) {
        val linear = floatArrayOf(0f, 0f, 1f, 1f)  // identity tone curve: in == out
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linear, linear, linear))
        // AWB STAYS ON. Phase 0 proved AWB can be switched off, and the first device
        // test proved WHY it should not be: with AWB off and no colour transform
        // supplied, the stream is the sensor's raw spectral response — heavily green
        // (a Bayer array has twice as many green photosites) and unbalanced. The film
        // engine wants scene-linear light, not un-white-balanced sensor counts, and the
        // RAW capture will be white-balanced too (LibRaw, AS_SHOT), so leaving the ISP
        // to white-balance the preview brings the two CLOSER together, not further apart.
        // What matters for fidelity is the TONE CURVE, sharpening and NR being off —
        // those destroy the radiometric relationship the engine depends on. White
        // balance is a per-channel gain, which does not.
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
    }

    /**
     * Lock or unlock the SENSOR's auto-exposure. The meter button locks this at the same
     * moment it captures the engine's exposure gain: pinning only the engine gain would
     * leave the sensor free to keep changing the RAW's linear values underneath it, so
     * the locked gain would drift out of correctness. Pin both and the viewfinder's
     * exposure IS the capture's exposure. See docs/CAMERA_PLAN.md §5.
     */
    fun setAeLock(locked: Boolean) {
        aeLocked = locked
        val b = request ?: return
        val s = session ?: return
        val h = handler ?: return
        b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
        runCatching { s.setRepeatingRequest(b.build(), null, h) }
            .onFailure { onError("AE lock failed: ${it.message}") }
    }

    /**
     * Copy the newest frame's luma plane. Y is the ISP's luminance and, with the tone
     * curve disabled, is near-linear — which is what the engine's metering wants. Only
     * luminance is needed: measure_autoexposure_ev reduces RGB to Y before metering, so
     * feeding a neutral (Y,Y,Y) triple yields the same EV as the full-colour frame
     * would, with no YUV->RGB matrix and no colour-space assumptions to get wrong.
     *
     * This is also why no sRGB->ProPhoto conversion is applied here, unlike the viewfinder
     * shader: that matrix's rows each sum to 1, so a neutral triple maps to itself. The
     * primaries only matter for chromatic pixels, and metering never sees any.
     */
    private fun captureLuma(reader: ImageReader) {
        val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val w = img.width
            val hgt = img.height
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val out = FloatArray(w * hgt)
            val row = ByteArray(rowStride)
            var i = 0
            for (y in 0 until hgt) {
                buf.position(y * rowStride)
                val n = minOf(rowStride, buf.remaining())
                buf.get(row, 0, n)
                var x = 0
                var k = 0
                while (x < w) {
                    out[i++] = (row[k].toInt() and 0xFF) / 255f
                    k += pixStride
                    x++
                }
            }
            latestLuma = out; lumaW = w; lumaH = hgt
        } catch (_: Throwable) {
            // A torn frame is not worth failing metering over; the next one will do.
        } finally {
            runCatching { img.close() }
        }
    }

    /** The newest luma frame as an engine-ready neutral image, or null if none yet. */
    fun latestLumaImage(): com.spectrafilm.engine.LinearImage? {
        val luma = latestLuma ?: return null
        val w = lumaW
        val h = lumaH
        if (w <= 0 || h <= 0 || luma.size < w * h) return null
        val buf = java.nio.ByteBuffer.allocateDirect(w * h * 3 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        for (i in 0 until w * h) {
            val v = luma[i]
            f.put(i * 3, v); f.put(i * 3 + 1, v); f.put(i * 3 + 2, v)
        }
        return com.spectrafilm.engine.LinearImage(buf, w, h, colorSpace = "ProPhoto RGB")
    }

    /**
     * Switch between continuous autofocus and manual focus at [diopters] (1/m; 0 =
     * infinity). Manual focus needs CONTROL_AF_MODE off — leaving AF running would have
     * the lens hunt straight back off the chosen distance.
     */
    fun setFocus(manual: Boolean, diopters: Float) {
        manualFocus = manual
        focusDiopters = diopters
        val b = request ?: return
        val s = session ?: return
        val h = handler ?: return
        applyFocus(b)
        runCatching { s.setRepeatingRequest(b.build(), null, h) }
            .onFailure { onError("focus update failed: ${it.message}") }
    }

    private fun applyFocus(b: CaptureRequest.Builder) {
        if (manualFocus) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE,
                  CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    /** True when this session can take a RAW still (the lens advertised a RAW size). */
    val canCapture: Boolean get() = rawReader != null && captureChars != null

    /**
     * Take one RAW still into [target] as a DNG. [onDone] is called with the file on
     * success, or a message on failure — always exactly once.
     *
     * Deliberately RAW: the whole point is that the film simulation gets scene-linear
     * sensor data. The ISP disables applied to the preview are irrelevant here because a
     * RAW frame is captured BEFORE the ISP touches it — the tone curve, sharpening and
     * noise reduction we fight in the viewfinder simply do not exist in this path.
     */
    fun capture(target: File, onDone: (File?, String?) -> Unit) {
        val d = device
        val s = session
        val h = handler
        val rr = rawReader
        if (d == null || s == null || h == null || rr == null) {
            onDone(null, "camera not ready"); return
        }
        if (captureDone != null) { onDone(null, "a capture is already in flight"); return }
        captureTarget = target
        captureDone = onDone
        pendingImage = null
        pendingResult = null
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rr.surface)
            applyFocus(this)
            set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
        }.build()
        runCatching {
            s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    sess: CameraCaptureSession, request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    pendingResult = result
                    tryWriteDng()
                }
                override fun onCaptureFailed(
                    sess: CameraCaptureSession, request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    finishCapture(null, "capture failed (reason ${failure.reason})")
                }
            }, h)
        }.onFailure { finishCapture(null, "capture request failed: ${it.message}") }
    }

    /** Write the DNG once BOTH the frame and its metadata have arrived. */
    private fun tryWriteDng() {
        val img = pendingImage ?: return
        val res = pendingResult ?: return
        val chars = captureChars
        val target = captureTarget
        pendingImage = null
        pendingResult = null
        if (chars == null || target == null) {
            runCatching { img.close() }
            finishCapture(null, "capture state missing")
            return
        }
        val err = runCatching {
            DngCreator(chars, res).use { dng ->
                FileOutputStream(target).use { out -> dng.writeImage(out, img) }
            }
        }.exceptionOrNull()
        runCatching { img.close() }
        if (err != null) {
            runCatching { target.delete() }
            finishCapture(null, "DNG write failed: ${err.message}")
        } else {
            finishCapture(target, null)
        }
    }

    private fun finishCapture(file: File?, error: String?) {
        val cb = captureDone
        captureDone = null
        captureTarget = null
        cb?.invoke(file, error)
    }

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { meterReader?.close() }
        meterReader = null
        runCatching { pendingImage?.close() }
        pendingImage = null
        pendingResult = null
        runCatching { rawReader?.close() }
        rawReader = null
        captureChars = null
        latestLuma = null
        session = null; device = null; request = null
        thread?.quitSafely()
        thread = null; handler = null
    }
}

