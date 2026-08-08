/*
 * Spektrafilm for Android — Phase 0 camera probe. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * THROWAWAY DIAGNOSTIC — see docs/CAMERA_PLAN.md §4. Delete this file (and its
 * Diagnostics-screen section) once Phase 0's questions are answered; nothing in the
 * shipping camera should depend on it.
 *
 * Answers the three questions that decide the shape of Phases 1-2:
 *
 *   A. What RAW can a THIRD-PARTY app actually get on this device? Galaxy phones often
 *      hand third parties a binned sensor mode and only the main lens, so the ceiling
 *      has to be measured, not assumed.
 *   B. How much of Samsung's ISP can we switch off on a preview stream? The viewfinder
 *      LUT preview is only representative of a RAW capture if the ISP's tone curve,
 *      sharpening, noise reduction and saturation can be disabled. We REQUEST each
 *      control and then read the CaptureResult BACK — a request that is silently
 *      ignored still "succeeds", so the result metadata is the only honest answer.
 *   C. How long does the engine take at draft resolution? Full-res 12 MP measures 1-2 s
 *      on this device; if 384 px lands near a frame budget then running the real engine
 *      on a live viewfinder becomes an option, not just a baked LUT.
 *
 * Uses plain Camera2 (already in the platform) rather than CameraX: a probe should not
 * drag in a dependency or a compileSdk bump before we know the answers.
 *
 * Camera work is headless — a small YUV ImageReader, no preview Surface — so this needs
 * no UI plumbing and cannot leave a camera open behind a composable.
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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object CameraProbe {

    /** Whether CAMERA has been granted; section B is skipped without it. */
    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Run the whole probe and return a plain-text report. Call off the main thread —
     * section C runs real engine renders.
     */
    suspend fun run(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("SPEKTRAFILM CAMERA PROBE (Phase 0)")
        sb.appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine()

        runCatching { sectionA(ctx, sb) }
            .onFailure { sb.appendLine("A. FAILED: ${it.javaClass.simpleName}: ${it.message}").appendLine() }
        runCatching { sectionB(ctx, sb) }
            .onFailure { sb.appendLine("B. FAILED: ${it.javaClass.simpleName}: ${it.message}").appendLine() }
        runCatching { sectionC(ctx, sb) }
            .onFailure { sb.appendLine("C. FAILED: ${it.javaClass.simpleName}: ${it.message}").appendLine() }

        return sb.toString()
    }

    // ---------------------------------------------------------------- A. inventory

    private fun sectionA(ctx: Context, sb: StringBuilder) {
        sb.appendLine("== A. CAMERA INVENTORY (what RAW can we get?) ==")
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList
        sb.appendLine("logical camera ids (getCameraIdList): ${ids.joinToString()}")
        sb.appendLine()
        for (id in ids) {
            val c = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            describeCamera(sb, c, "--- camera $id (${facing(c)}, ${focal(c)}mm) ---", full = true)

            // PHYSICAL SUB-CAMERAS. getCameraIdList() deliberately omits the physical
            // lenses behind a logical multi-camera, so a telephoto can be fully usable
            // (MotionCam records RAW from it) while never appearing above. Enumerate them
            // and report each one's RAW support; streaming from one means opening the
            // LOGICAL camera and setting OutputConfiguration.setPhysicalCameraId(id).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val physIds = runCatching { c.physicalCameraIds }.getOrNull().orEmpty()
                if (physIds.isEmpty()) {
                    sb.appendLine("  physical sub-cameras: none (not a logical multi-camera)")
                } else {
                    sb.appendLine("  physical sub-cameras: ${physIds.joinToString()}")
                    for (pid in physIds.sorted()) {
                        val pc = runCatching { mgr.getCameraCharacteristics(pid) }.getOrNull()
                        if (pc == null) {
                            sb.appendLine("    [$pid] characteristics unavailable")
                            continue
                        }
                        describeCamera(sb, pc, "    [physical $pid] ${focal(pc)}mm", full = false)
                    }
                }
            } else {
                sb.appendLine("  physical sub-cameras: needs API 28+ (this device is API ${android.os.Build.VERSION.SDK_INT})")
            }
            sb.appendLine()
        }
        sb.appendLine("NOTE: a physical id listing RAW_SENSOR sizes is the one to shoot with for that")
        sb.appendLine("focal length. Phase 2 targets it via OutputConfiguration.setPhysicalCameraId,")
        sb.appendLine("not by opening the physical id directly.")
        sb.appendLine()
    }

    /** Print one camera's capabilities. [full] adds the ISP-mode lists (noise for sub-cameras). */
    private fun describeCamera(sb: StringBuilder, c: CameraCharacteristics, header: String, full: Boolean) {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val pad = if (full) "  " else "      "
        sb.appendLine(header)
        sb.appendLine("${pad}hardware level : ${hardwareLevel(c)}")
        sb.appendLine("${pad}RAW capable    : ${if (raw) "YES" else "no"}")
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            val rawSizes = runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR) }.getOrNull()
            sb.appendLine("${pad}RAW_SENSOR     : ${sizeList(rawSizes)}")
            if (full) {
                val jpegSizes = runCatching { map.getOutputSizes(ImageFormat.JPEG) }.getOrNull()
                sb.appendLine("${pad}JPEG (largest) : ${sizeList(jpegSizes?.take(1)?.toTypedArray())}")
            }
        }
        // The sensor's full array vs the largest RAW output is how binning shows up.
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sb.appendLine("${pad}active array   : ${active?.width()}x${active?.height()}")
        if (full) {
            sb.appendLine("${pad}available ISP-off modes:")
            sb.appendLine("$pad  edge          : ${modeList(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), ::edgeName)}")
            sb.appendLine("$pad  noise redux   : ${modeList(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), ::nrName)}")
            sb.appendLine("$pad  tonemap       : ${modeList(c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES), ::tonemapName)}")
            sb.appendLine("$pad  tonemap pts   : ${c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: "?"}")
        }
    }

    private fun facing(c: CameraCharacteristics) = when (c.get(CameraCharacteristics.LENS_FACING)) {
        CameraCharacteristics.LENS_FACING_BACK -> "back"
        CameraCharacteristics.LENS_FACING_FRONT -> "front"
        else -> "external"
    }

    private fun focal(c: CameraCharacteristics) =
        c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.joinToString { "%.1f".format(it) } ?: "?"

    // ------------------------------------------------------- B. how much ISP is off?

    @SuppressLint("MissingPermission")  // guarded by hasCameraPermission below
    private suspend fun sectionB(ctx: Context, sb: StringBuilder) {
        sb.appendLine("== B. ISP DISABLE TEST (requested vs actually applied) ==")
        if (!hasCameraPermission(ctx)) {
            sb.appendLine("SKIPPED — camera permission not granted.").appendLine()
            return
        }
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        // Prefer a back camera that advertises RAW — that's the one Phase 2 will shoot with.
        val id = mgr.cameraIdList.firstOrNull { cid ->
            val c = runCatching { mgr.getCameraCharacteristics(cid) }.getOrNull() ?: return@firstOrNull false
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0))
                    .contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        } ?: mgr.cameraIdList.firstOrNull()
        if (id == null) { sb.appendLine("no camera available.").appendLine(); return }
        sb.appendLine("probing camera $id")

        val chars = mgr.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        // Probe at a REALISTIC viewfinder resolution, not the smallest available size.
        // OEM pipelines can route small legacy streams differently from a 1080p preview,
        // so an ISP control that sticks at 176x144 does not prove it sticks where Phase 1
        // will actually run. Pick the supported size nearest 1920x1080.
        val target = 1920L * 1080
        val previewSize = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - target) }
            ?: Size(1920, 1080)
        sb.appendLine("probe stream  : ${previewSize.width}x${previewSize.height} YUV_420_888 (target ~1920x1080)")

        val thread = HandlerThread("spk-probe").apply { start() }
        val handler = Handler(thread.looper)
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            reader = ImageReader.newInstance(
                previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2,
            ).apply {
                // Drain frames or the pipeline stalls after maxImages.
                setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, handler)
            }
            val surface = reader.surface

            device = withTimeoutOrNull(5_000) { openCamera(mgr, id, handler) }
            if (device == null) { sb.appendLine("FAILED: camera did not open within 5 s").appendLine(); return }

            session = withTimeoutOrNull(5_000) { createSession(device, listOf(surface), handler) }
            if (session == null) { sb.appendLine("FAILED: capture session not configured within 5 s").appendLine(); return }

            val linear = floatArrayOf(0f, 0f, 1f, 1f)   // identity tone curve: in == out
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linear, linear, linear))
                // COLOR_CORRECTION_MODE is overridden by the AWB routine unless AWB is OFF,
                // so both have to be set for the saturation/matrix question to mean anything.
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            }.build()

            // Let AE settle, then read a frame's metadata back.
            val result = withTimeoutOrNull(8_000) { firstResult(session, req, handler, skipFrames = 8) }
            if (result == null) { sb.appendLine("FAILED: no capture result within 8 s").appendLine(); return }

            sb.appendLine()
            sb.appendLine("  control              requested            -> applied")
            sb.appendLine("  " + "-".repeat(58))
            row(sb, "EDGE_MODE", edgeName(CameraMetadata.EDGE_MODE_OFF),
                result.get(TotalCaptureResult.EDGE_MODE)?.let(::edgeName))
            row(sb, "NOISE_REDUCTION_MODE", nrName(CameraMetadata.NOISE_REDUCTION_MODE_OFF),
                result.get(TotalCaptureResult.NOISE_REDUCTION_MODE)?.let(::nrName))
            row(sb, "TONEMAP_MODE", tonemapName(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE),
                result.get(TotalCaptureResult.TONEMAP_MODE)?.let(::tonemapName))
            row(sb, "CONTROL_AWB_MODE", "OFF",
                result.get(TotalCaptureResult.CONTROL_AWB_MODE)?.let { if (it == 0) "OFF" else "ON($it)" })
            row(sb, "COLOR_CORRECTION_MODE", "TRANSFORM_MATRIX",
                result.get(TotalCaptureResult.COLOR_CORRECTION_MODE)?.let(::ccName))
            sb.appendLine()
            sb.appendLine("  VERDICT: a control that reads back as requested is one we can rely on")
            sb.appendLine("  in Phase 1's viewfinder. Anything that snapped back to a different")
            sb.appendLine("  value is ISP processing we cannot remove and must approximate instead.")
            sb.appendLine()
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------ C. engine draft timing

    private fun sectionC(ctx: Context, sb: StringBuilder) {
        sb.appendLine("== C. ENGINE DRAFT RENDER TIMING ==")
        val engine = EngineHolder.get(ctx)
        val state = ParamsState()
        val img = syntheticLinearImage(1024)
        try {
            // Warm-up: the first call builds and caches the film tc_lut, so timing it
            // would measure setup rather than the per-frame cost.
            engine.simulatePreview(
                img, state.toParams(previewMaxSizeOverride = DRAFT_RENDER_MAX_PX, skipGrainHalation = true),
            ).close()
            val runs = LongArray(5)
            for (i in runs.indices) {
                val t0 = System.nanoTime()
                engine.simulatePreview(
                    img, state.toParams(previewMaxSizeOverride = DRAFT_RENDER_MAX_PX, skipGrainHalation = true),
                ).close()
                runs[i] = (System.nanoTime() - t0) / 1_000_000
            }
            runs.sort()
            sb.appendLine("draft ${DRAFT_RENDER_MAX_PX}px, grain/halation off, 5 runs after warm-up:")
            sb.appendLine("  times (ms) : ${runs.joinToString()}")
            sb.appendLine("  median     : ${runs[runs.size / 2]} ms")
            sb.appendLine()
            sb.appendLine("  ~33 ms would mean 30 fps of the REAL engine is conceivable;")
            sb.appendLine("  well above that confirms the baked-LUT viewfinder is the only option.")
            sb.appendLine()
        } finally {
            img.close()
        }
    }

    // ------------------------------------------------------------- camera2 helpers

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(mgr: CameraManager, id: String, handler: Handler): CameraDevice? =
        suspendCancellableCoroutine { cont ->
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); if (cont.isActive) cont.resume(null)
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); if (cont.isActive) cont.resume(null)
                }
            }, handler)
        }

    @Suppress("DEPRECATION")  // SessionConfiguration needs API 28; minSdk is 24
    private suspend fun createSession(
        device: CameraDevice, surfaces: List<android.view.Surface>, handler: Handler,
    ): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { if (cont.isActive) cont.resume(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) { if (cont.isActive) cont.resume(null) }
        }, handler)
    }

    /** Start the repeating request and return the result for frame [skipFrames], so AE/AWB have settled. */
    private suspend fun firstResult(
        session: CameraCaptureSession, req: CaptureRequest, handler: Handler, skipFrames: Int,
    ): TotalCaptureResult? = suspendCancellableCoroutine { cont ->
        var seen = 0
        session.setRepeatingRequest(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult,
            ) {
                if (++seen < skipFrames) return
                if (cont.isActive) {
                    runCatching { s.stopRepeating() }
                    cont.resume(result)
                }
            }
            override fun onCaptureFailed(
                s: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure,
            ) {
                if (cont.isActive) cont.resume(null)
            }
        }, handler)
    }

    // ------------------------------------------------------------------ formatting

    private fun row(sb: StringBuilder, name: String, requested: String, applied: String?) {
        val got = applied ?: "(not reported)"
        val mark = if (applied != null && applied == requested) "OK " else "!! "
        sb.appendLine("  $mark${name.padEnd(21)}${requested.padEnd(21)}-> $got")
    }

    private fun sizeList(sizes: Array<Size>?): String =
        if (sizes.isNullOrEmpty()) "none" else sizes.joinToString { "${it.width}x${it.height}" }

    private fun modeList(modes: IntArray?, name: (Int) -> String): String =
        if (modes == null || modes.isEmpty()) "?" else modes.joinToString { name(it) }

    private fun hardwareLevel(c: CameraCharacteristics): String =
        when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY (no manual control)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (best)"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "unknown"
        }

    private fun edgeName(v: Int) = when (v) {
        CameraMetadata.EDGE_MODE_OFF -> "OFF"
        CameraMetadata.EDGE_MODE_FAST -> "FAST"
        CameraMetadata.EDGE_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.EDGE_MODE_ZERO_SHUTTER_LAG -> "ZERO_SHUTTER_LAG"
        else -> "mode$v"
    }

    private fun nrName(v: Int) = when (v) {
        CameraMetadata.NOISE_REDUCTION_MODE_OFF -> "OFF"
        CameraMetadata.NOISE_REDUCTION_MODE_FAST -> "FAST"
        CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL -> "MINIMAL"
        CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG -> "ZERO_SHUTTER_LAG"
        else -> "mode$v"
    }

    private fun tonemapName(v: Int) = when (v) {
        CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE -> "CONTRAST_CURVE"
        CameraMetadata.TONEMAP_MODE_FAST -> "FAST"
        CameraMetadata.TONEMAP_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        CameraMetadata.TONEMAP_MODE_GAMMA_VALUE -> "GAMMA_VALUE"
        CameraMetadata.TONEMAP_MODE_PRESET_CURVE -> "PRESET_CURVE"
        else -> "mode$v"
    }

    private fun ccName(v: Int) = when (v) {
        CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX -> "TRANSFORM_MATRIX"
        CameraMetadata.COLOR_CORRECTION_MODE_FAST -> "FAST"
        CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY -> "HIGH_QUALITY"
        else -> "mode$v"
    }
}
