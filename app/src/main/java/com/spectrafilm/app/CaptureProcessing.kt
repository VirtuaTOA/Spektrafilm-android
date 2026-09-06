/*
 * Spektrafilm for Android — capture queue + background processing. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A full-resolution film render takes tens of seconds, so the shutter must not block on
 * it. Capture writes the DNG (fast) and enqueues a job; a FOREGROUND SERVICE drains the
 * queue while the user keeps shooting, leaves the app, or locks the phone.
 *
 * WHY A FOREGROUND SERVICE AND NOT A COROUTINE: Android freezes cached processes. This
 * was measured directly during development — an export appeared to hang with zero CPU
 * consumed for seconds on end, purely because the app had been backgrounded with the
 * screen off. A plain background coroutine would be suspended the moment the user locks
 * the phone, which is exactly when they expect processing to continue.
 *
 * WHY THE QUEUE IS ON DISK: the process can still be killed under memory pressure — and
 * this pipeline allocates over a gigabyte at full resolution, so that is a real risk. A
 * queue held only in memory would silently lose shots the user already took. The DNG is
 * on disk the instant the shutter fires; the job record points at it, so a killed process
 * resumes rather than forgets.
 */
package com.spectrafilm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spectrafilm.libraw.WhiteBalance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One pending render: a captured DNG plus the look chosen when the shutter fired. */
data class CaptureJob(
    val dngPath: String,
    val presetId: String?,
    val createdMs: Long,
    /** Human-readable film stock ("PORTRA 400") and the 35mm-equivalent focal length shown on
     *  the lens chip. Captured HERE because this is the only place that knows them — the render
     *  runs later in a service with nothing but this record, and the preset could have changed by
     *  then. Nullable so queue entries written before this existed still parse. */
    val stockName: String? = null,
    val equivFocalMm: Int? = null,
    /** Exposure time in nanoseconds, from the still's own CaptureResult. */
    val shutterNs: Long? = null,
    val iso: Int? = null,
    /** Black Pro-Mist strength, or null for no filter. Carried HERE because the render rebuilds
     *  params from the preset id alone — a filter chosen in the viewfinder would otherwise never
     *  reach the export. */
    val diffusionStrength: Float? = null,
    /** Diffusion family id ("cinebloom" etc). Null keeps the preset's own. */
    val diffusionFamily: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dng", dngPath)
        .put("preset", presetId ?: JSONObject.NULL)
        .put("created", createdMs)
        .put("stockName", stockName ?: JSONObject.NULL)
        .put("equivFocalMm", equivFocalMm ?: JSONObject.NULL)
        .put("shutterNs", shutterNs ?: JSONObject.NULL)
        .put("iso", iso ?: JSONObject.NULL)
        .put("diffusion", diffusionStrength?.toDouble() ?: JSONObject.NULL)
        .put("diffusionFamily", diffusionFamily ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): CaptureJob? {
            val dng = o.optString("dng").takeIf { it.isNotBlank() } ?: return null
            return CaptureJob(
                dngPath = dng,
                presetId = o.optString("preset").takeIf { it.isNotBlank() && it != "null" },
                createdMs = o.optLong("created"),
                stockName = o.optString("stockName").takeIf { it.isNotBlank() && it != "null" },
                equivFocalMm = o.optInt("equivFocalMm", -1).takeIf { it > 0 },
                shutterNs = o.optLong("shutterNs", -1L).takeIf { it > 0L },
                iso = o.optInt("iso", -1).takeIf { it > 0 },
                diffusionStrength = o.optDouble("diffusion", -1.0).takeIf { it > 0.0 }?.toFloat(),
                diffusionFamily = o.optString("diffusionFamily")
                    .takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/**
 * Disk-backed FIFO of pending captures. Small and synchronous — the file holds at most a
 * handful of entries and every operation rewrites it, which is far simpler to reason about
 * than incremental updates and costs nothing at this size.
 */
object CaptureQueue {

    private fun file(ctx: Context) = File(ctx.filesDir, "capture_queue.json")

    /** Directory the captured DNGs live in. App-private: these are working files. */
    fun captureDir(ctx: Context): File =
        File(ctx.filesDir, "captures").apply { mkdirs() }

    @Synchronized
    fun list(ctx: Context): List<CaptureJob> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { CaptureJob.fromJson(it) }?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(ctx: Context, jobs: List<CaptureJob>) {
        runCatching {
            val arr = JSONArray()
            jobs.forEach { arr.put(it.toJson()) }
            file(ctx).writeText(arr.toString())
        }
    }

    @Synchronized
    fun add(ctx: Context, job: CaptureJob) = write(ctx, list(ctx) + job)

    /** Remove by DNG path — the job's identity, since one DNG is rendered once. */
    @Synchronized
    fun remove(ctx: Context, dngPath: String) =
        write(ctx, list(ctx).filterNot { it.dngPath == dngPath })

    fun pending(ctx: Context): Int = list(ctx).size

    /**
     * What the retained DNGs cost on disk, split by whether they are safe to delete.
     *
     * Captures keep their source DNG after rendering (see [ProcessingService.drain]) so a
     * frame can be re-imported by hand later. Nothing prunes them, so at ~20-25 MB each they
     * accumulate for the life of the install — invisibly, since app-private storage is not
     * reachable from any file browser.
     */
    data class DngStorage(
        val clearableFiles: Int,
        val clearableBytes: Long,
        val queuedFiles: Int,
        val queuedBytes: Long,
    ) {
        val totalFiles: Int get() = clearableFiles + queuedFiles
        val totalBytes: Long get() = clearableBytes + queuedBytes
    }

    /** Stats every file in the capture directory — call this off the main thread. */
    fun dngStorage(ctx: Context): DngStorage {
        val queued = list(ctx).mapTo(HashSet()) { it.dngPath }
        var cf = 0
        var cb = 0L
        var qf = 0
        var qb = 0L
        captureDir(ctx).listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (f.path in queued) {
                qf++
                qb += f.length()
            } else {
                cf++
                cb += f.length()
            }
        }
        return DngStorage(cf, cb, qf, qb)
    }

    /**
     * Delete every retained DNG that is NOT still queued, returning the bytes freed.
     *
     * SKIPPING QUEUED JOBS IS A CORRECTNESS REQUIREMENT, NOT AN OPTIMISATION. Deleting a
     * pending job's DNG loses that photograph outright: [ProcessingService.drain] finds the
     * file missing, logs it, drops the job, and the frame is never rendered — with the RAW
     * already gone there is nothing left to render it from.
     */
    fun clearRetainedDngs(ctx: Context): Long {
        val queued = list(ctx).mapTo(HashSet()) { it.dngPath }
        var freed = 0L
        captureDir(ctx).listFiles()?.forEach { f ->
            if (f.isFile && f.path !in queued) {
                val size = f.length()
                if (f.delete()) freed += size
            }
        }
        Diag.i("capture: cleared retained DNGs, freed $freed bytes")
        return freed
    }
}

/**
 * Drains [CaptureQueue] in the background. Started after every capture; stops itself once
 * the queue is empty, so it never lingers holding a notification for no reason.
 */
/**
 * Crop [src] to the 35 mm 3:2 frame, about its centre, keeping the longer side. Returns
 * [src] unchanged when it is already 3:2 (within a pixel), so nothing is copied needlessly.
 */
private fun cropToFilmAspect(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return src
    val landscape = w >= h
    val targetW: Int
    val targetH: Int
    // FILM_ASPECT, not a literal: CameraInventory.equivalentFocal mirrors this crop to work
    // out the 35 mm-equivalent focal length, so if the two ever disagreed the lens labels
    // would describe a field of view the saved photograph does not have.
    if (landscape) {
        targetH = minOf(h, Math.round(w / FILM_ASPECT))
        targetW = minOf(w, Math.round(targetH * FILM_ASPECT))
    } else {
        targetW = minOf(w, Math.round(h / FILM_ASPECT))
        targetH = minOf(h, Math.round(targetW * FILM_ASPECT))
    }
    if (targetW >= w && targetH >= h) return src
    val x = ((w - targetW) / 2).coerceAtLeast(0)
    val y = ((h - targetH) / 2).coerceAtLeast(0)
    return runCatching {
        android.graphics.Bitmap.createBitmap(src, x, y, targetW, targetH)
    }.getOrDefault(src)
}

/**
 * EXIF for a finished frame: what the camera recorded, plus what the SIMULATION chose.
 *
 * The shutter values come from the DNG, which DngCreator wrote from the exact CaptureResult that
 * produced the frame — authoritative in a way nothing reconstructed later could be. The film stock
 * and the 35mm-equivalent focal length come from the job record, because they are decisions the UI
 * made and are not sensor facts: the stock is a look, and the equivalent focal length depends on
 * this app's 3:2 crop.
 *
 * The stock goes in USER_COMMENT rather than a private tag so it survives being copied, shared or
 * opened in anything else that reads EXIF — the point is that the photo remembers how it was made.
 */
private val CAMERA_EXIF_TAGS = listOf(
    androidx.exifinterface.media.ExifInterface.TAG_MAKE,
    androidx.exifinterface.media.ExifInterface.TAG_MODEL,
    androidx.exifinterface.media.ExifInterface.TAG_LENS_MODEL,
)

private fun captureExif(job: CaptureJob): SourceExif {
    val tags = HashMap<String, String>()
    runCatching {
        val src = androidx.exifinterface.media.ExifInterface(job.dngPath)
        for (t in CAMERA_EXIF_TAGS) src.getAttribute(t)?.let { tags[t] = it }
    }.onFailure { Diag.w("capture: could not read DNG exif: ${it.message}") }

    job.stockName?.let { tags[androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT] = it }
    job.equivFocalMm?.let {
        tags[androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM] = it.toString()
    }
    // From the CaptureResult, not the DNG: reading it back out of the DNG did not work in
    // practice, and this is the same number the sensor actually used.
    // Same reasoning as the shutter above: taken from the CaptureResult rather than the
    // DNG. Verified empirically — exports carried NO ISO tag when this relied on the DNG.
    job.iso?.let {
        tags[androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] =
            it.toString()
    }
    job.shutterNs?.let {
        tags[androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME] =
            (it / 1_000_000_000.0).toString()
    }
    // From the queue record, not the DNG: this is when the SHUTTER fired, which is what a
    // photographer means by when the photo was taken. The render may finish a minute later.
    val stamp = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(job.createdMs))
    tags[androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL] = stamp
    tags[androidx.exifinterface.media.ExifInterface.TAG_DATETIME] = stamp
    return SourceExif(tags)
}

class ProcessingService : Service() {

    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(0, CaptureQueue.pending(this))
        // Guard against re-entry: onStartCommand fires again for every capture, but one
        // drain loop already picks up whatever has since been added to the queue.
        if (running.compareAndSet(false, true)) {
            thread(name = "spk-capture-processing") {
                runCatching { drain() }
                    .onFailure { Diag.e("capture: processing loop failed", it) }
                running.set(false)
                stopForegroundCompat()
                stopSelf()
            }
        }
        // START_STICKY would restart with a null intent after a kill; the disk queue is the
        // real durability mechanism, and the app restarts the service when it next opens.
        return START_NOT_STICKY
    }

    private var doneThisRun = 0
    private var totalThisRun = 0

    private fun drain() {
        val engine = runCatching { EngineHolder.get(this) }.getOrNull() ?: run {
            Diag.e("capture: engine unavailable, cannot process", null)
            return
        }
        totalThisRun = CaptureQueue.pending(this)
        doneThisRun = 0
        while (true) {
            val job = CaptureQueue.list(this).firstOrNull() ?: break
            // A capture taken while this loop is running extends the run rather than
            // starting a second one, so the bar has to grow with it.
            totalThisRun = maxOf(totalThisRun, doneThisRun + CaptureQueue.pending(this))
            val dng = File(job.dngPath)
            if (!dng.isFile) {
                Diag.w("capture: queued DNG missing, dropping: ${dng.name}")
                CaptureQueue.remove(this, job.dngPath)
                continue
            }
            notify(doneThisRun, totalThisRun)
            val t0 = System.currentTimeMillis()
            val ok = runCatching { render(engine, job, dng) }
                .onFailure { Diag.e("capture: render failed for ${dng.name}", it) }
                .isSuccess
            Diag.i("capture: ${dng.name} ${if (ok) "rendered" else "FAILED"} in " +
                "${System.currentTimeMillis() - t0}ms")
            // Dequeue either way: a job that fails deterministically would otherwise block
            // the queue forever, and the DNG is retained so it can be imported by hand.
            CaptureQueue.remove(this, job.dngPath)
            doneThisRun++
            notify(doneThisRun, totalThisRun)
        }
    }

    private fun render(engine: com.spectrafilm.engine.SpektraEngine, job: CaptureJob, dng: File) {
        val state = ParamsState()
        job.presetId?.let { id ->
            BuiltInPresets.byId(this, id)?.let { BuiltInPresets.apply(it, state) }
        }
        // AS_SHOT: the DNG carries the white balance the camera metered, which is what the
        // viewfinder was showing.
        val image = decodeRawToLinear(
            this, Uri.fromFile(dng), WhiteBalance.AS_SHOT,
            state.rawTemperature.toDouble(), state.rawTint.toDouble(), EXPORT_MAX_EDGE_PX,
            // FBDD light. Camera captures are single-frame RAW with the ISP fully disabled
            // (CameraSession.applyIspDisables), so nothing upstream has ever touched their
            // noise. Measured on this device's own DNGs, that noise is chromatic — R and B
            // carry ~half of green's signal and roughly double it after white balance —
            // which is precisely what a pre-demosaic pass suppresses before the
            // interpolator can turn it into coloured speckle.
            //
            // Strength 1 (light) rather than 2 on purpose: the engine adds film grain
            // downstream, and scrubbing the luminance floor flat before re-adding
            // synthetic grain is what makes a film simulation read as plastic. The goal
            // is to remove the colour blotches, not the texture.
            //
            // MEASURED, do not "turn it up" — 2 is WORSE than 1. Decoding a real capture
            // (SPK_1786753212833.dng) at each strength and taking the median per-tile
            // sigma over shadow/midtone tiles gives, vs fbdd=0:
            //     strength 1 -> R -22% G -19% B -27%   (mean -22.6%)
            //     strength 2 -> R -15% G -16% B -18%   (mean -16.3%)
            // LibRaw's "full" mode runs an extra fbdd_correction2() pass that puts
            // high-frequency variation back. 1 is the optimum, not a conservative pick.
            //
            // Also measured: this is worth ~0.3 stops and is near the edge of visibility
            // once film grain lands on top. It is a cheap improvement, NOT a fix for
            // chromatic shadow noise — that needs a chroma-specific filter or multi-frame
            // capture. See docs/AUDIT.md.
            //
            // Deliberately NOT applied to the editor's import path, which stays byte-equal
            // to desktop spektrafilm's rawpy decode (docs/RAW_DNG.md) — and whose previews
            // are half-size, where FBDD cannot run anyway, so enabling it there would make
            // previews and exports disagree.
            fbddNoiseReduction = 1,
            // Edge-aware chroma denoise. This is the one aimed squarely at the defect:
            // measured in structurally flat patches of a real capture, where all variation
            // really is noise, blue-yellow chroma noise (sigma 89.3) is LARGER than the
            // luminance noise (81.3). A guided filter at this radius cut chroma noise ~69%
            // while leaving luminance untouched — about 3x what FBDD manages, and unlike
            // FBDD it costs no sharpness at all, so the film grain still has texture to
            // sit on. 8 full-res pixels matches the sigma=4 Gaussian that was measured.
            chromaDenoiseRadius = 8,
        )
        val result = try {
            // Applied AFTER the preset so a filter chosen at the shutter wins over the preset's
            // default. Diffusion is spatial, so it exists only here — the viewfinder's LUT is
            // pointwise and cannot show it.
            // Diffusion was PREVIEW-ONLY here for a long time: the engine convolved the PSF
            // directly, and the kernel radius scales with the frame (~0.37 x the longest edge),
            // so a full-resolution export reached ~3.1M taps per pixel — about 3.6 hours a
            // frame. The queue looked hung at 40% CPU and 1.5 GB while it ground away.
            //
            // model/diffusion.cpp now convolves via FFT (spk::convolve_valid_2d_ola), which is
            // what the oracle does too (scipy.signal.fftconvolve), so this is exportable at a
            // sane cost. Both parity gates still pass — test_diffusion bit-exact, and
            // test_diffusion_e2e's on-vs-off check confirms the filter is genuinely applied.
            job.diffusionStrength?.let { strength ->
                state.cameraDiffusionState.active = true
                state.cameraDiffusionState.strength = strength
                job.diffusionFamily?.let { state.cameraDiffusionState.family = it }
            }
            engine.simulate(image, state.toParams())
        } finally {
            image.close()
        }
        try {
            val full = simResultToBitmapGraded(
                result, state.savingCctfEncoding, state.saturation, state.vibrance,
                state.gamutCompress, state.localAdjustments,
            )
            // 35 mm is 3:2 and the sensor is 4:3, so the saved frame is cropped to the
            // same region the viewfinder framed. Without this the photo would contain a
            // band the user never saw — the mismatch that made captures look wider than
            // the viewfinder before the preview aspect was fixed.
            val bmp = cropToFilmAspect(full)
            if (bmp !== full) full.recycle()
            val name = "SPK_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(job.createdMs))
            saveToGallery(
                this, bmp, ExportFormat.JPEG, 95,
                sourceExif = captureExif(job),
                displayName = name,
            )
        } finally {
            result.close()
        }
    }

    // ---- notification -----------------------------------------------------------------

    private fun notify(done: Int, total: Int) = startForegroundCompat(done, total)

    private fun startForegroundCompat(done: Int, total: Int) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "Film processing", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Rendering captured photos" }
            mgr?.createNotificationChannel(ch)
        }
        val remaining = (total - done).coerceAtLeast(0)
        val text = when {
            total <= 0 -> "Finishing up…"
            remaining <= 0 -> "Finishing up…"
            total == 1 -> "Developing your photo…"
            else -> "Developing ${done + 1} of $total…"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Spektrafilm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Tapping it returns to the app rather than doing nothing.
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        // A single render reports no internal progress, so the bar is indeterminate WITHIN
        // a photo but determinate ACROSS the queue — which is the part the user can act on.
        if (total > 1) b.setProgress(total, done, false) else b.setProgress(0, 0, true)
        val n: Notification = b.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        private const val CHANNEL = "spk_processing"
        private const val NOTIF_ID = 4711

        /** Enqueue [job] and make sure the drain loop is running. */
        fun enqueue(ctx: Context, job: CaptureJob) {
            CaptureQueue.add(ctx, job)
            start(ctx)
        }

        /** Resume any queue left over from a previous run (called on app start). */
        fun resumeIfPending(ctx: Context) {
            if (CaptureQueue.pending(ctx) > 0) start(ctx)
        }

        private fun start(ctx: Context) {
            val i = Intent(ctx, ProcessingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }.onFailure { Diag.w("capture: could not start processing service: ${it.message}") }
        }
    }
}
