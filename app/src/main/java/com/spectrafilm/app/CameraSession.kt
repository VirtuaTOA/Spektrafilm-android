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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** One selectable lens: a physical camera id behind [logicalId], with its RAW capability. */
data class LensOption(
    val logicalId: String,
    val physicalId: String?,
    val focalMm: Float,
    val label: String,
    val rawSize: Size?,
    val hardwareLevel: Int,
) {
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
        return LensOption(
            logicalId = logicalId, physicalId = physId, focalMm = focal,
            label = labelFor(focal), rawSize = rawSize, hardwareLevel = level,
        )
    }

    /** Human label from actual focal length — the shortest rear lens is the ultrawide. */
    private fun labelFor(focalMm: Float): String = when {
        focalMm < 3.5f -> "UW"
        focalMm < 6.0f -> "1x"
        else -> "Tele"
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

    @Volatile var aeLocked: Boolean = false
        private set

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
        val executor = Executor { r -> h.post(r) }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    applyIspDisables(this)
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
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(outCfg), executor, cb)
            )
        }.onFailure { onError("createCaptureSession failed: ${it.message}") }
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
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        b.set(CaptureRequest.COLOR_CORRECTION_MODE,
              CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
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

    fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null; device = null; request = null
        thread?.quitSafely()
        thread = null; handler = null
    }
}

