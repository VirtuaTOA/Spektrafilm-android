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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("dng", dngPath)
        .put("preset", presetId ?: JSONObject.NULL)
        .put("created", createdMs)

    companion object {
        fun fromJson(o: JSONObject): CaptureJob? {
            val dng = o.optString("dng").takeIf { it.isNotBlank() } ?: return null
            return CaptureJob(
                dngPath = dng,
                presetId = o.optString("preset").takeIf { it.isNotBlank() && it != "null" },
                createdMs = o.optLong("created"),
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
    if (landscape) {
        targetH = minOf(h, Math.round(w / 1.5f))
        targetW = minOf(w, Math.round(targetH * 1.5f))
    } else {
        targetW = minOf(w, Math.round(h / 1.5f))
        targetH = minOf(h, Math.round(targetW * 1.5f))
    }
    if (targetW >= w && targetH >= h) return src
    val x = ((w - targetW) / 2).coerceAtLeast(0)
    val y = ((h - targetH) / 2).coerceAtLeast(0)
    return runCatching {
        android.graphics.Bitmap.createBitmap(src, x, y, targetW, targetH)
    }.getOrDefault(src)
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
        )
        val result = try {
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
            saveToGallery(this, bmp, ExportFormat.JPEG, 95, displayName = name)
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
