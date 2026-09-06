/*
 * Spektrafilm for Android — in-app gallery data source. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Enumerates the photos this app has produced, newest first, straight from MediaStore.
 *
 * WHY MEDIASTORE AND NOT A DIRECTORY LISTING: the export path already inserts finished frames
 * with RELATIVE_PATH = Pictures/Spektrafilm (see ImagePipeline), so MediaStore is the record of
 * record — it knows the display name and capture time, it survives the app's own cache being
 * cleared, and it is the only route that works under scoped storage. Walking the filesystem
 * would need permissions the app deliberately does not hold on API 29+.
 *
 * NO PERMISSION IS REQUIRED for what this does. An app can always read the MediaStore rows it
 * inserted itself; READ_MEDIA_IMAGES would only be needed to see OTHER apps' pictures, which is
 * not what a camera roll for this app should show. Below API 29 the manifest's
 * WRITE_EXTERNAL_STORAGE (maxSdkVersion 28) covers it, and the camera needs API 28+ regardless.
 */
package com.spectrafilm.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

object Gallery {

    /**
     * The ONE dispatcher every gallery decode runs on, deliberately narrow.
     *
     * Dispatchers.IO allows up to 64 threads, and the grid launches a decode per cell — so a
     * scroll fired twenty-odd concurrent JPEG decodes that saturated the CPU and left the UI
     * thread fighting for a core. Measured on device: individual decodes were fine (9ms median)
     * while the UI thread stalled on 63 frames with zero slow bitmap uploads, which is what
     * starvation looks like rather than a slow decode.
     *
     * Two keeps the queue moving without monopolising the machine. Decoding slightly later is
     * invisible; dropping scroll frames is not.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /** One finished photo. [uri] is a content:// URI, usable directly by the viewer. */
    data class Item(
        val id: Long,
        val uri: Uri,
        val name: String,
        /** Seconds since epoch (MediaStore's unit for DATE_ADDED). */
        val dateAdded: Long,
    )

    /** Folder the export path writes into; must match ImagePipeline's RELATIVE_PATH. */
    private const val FOLDER = "Spektrafilm"

    /**
     * Newest-first list of this app's photos, capped at [limit].
     *
     * Returns empty rather than throwing on any failure: a gallery that cannot read is an empty
     * gallery, not a crashed camera. Call off the main thread — a MediaStore query hits disk.
     */
    fun list(ctx: Context, limit: Int = 500): List<Item> {
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        // RELATIVE_PATH only exists on API 29+; below that the legacy DATA column holds the
        // absolute path. Matching on the folder name keeps this to OUR pictures either way.
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%$FOLDER%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf("%/$FOLDER/%")
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"

        return runCatching {
            val out = ArrayList<Item>()
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, sel, args, order,
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    out += Item(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
                        ),
                        name = c.getString(iName) ?: "",
                        dateAdded = c.getLong(iDate),
                    )
                }
            }
            out
        }.getOrElse {
            Diag.w("gallery: query failed: ${it.message}")
            emptyList()
        }
    }

    /** The most recent photo, for the camera's preview button. Cheap: LIMIT 1 via [list]. */
    fun latest(ctx: Context): Item? = list(ctx, limit = 1).firstOrNull()

    // Decoded thumbnails, keyed by "id@px". Bounded by BYTE SIZE rather than entry count: a grid
    // cell and a full-screen preview differ by orders of magnitude in cost, so counting entries
    // would let a handful of large ones blow past any sane budget. An eighth of the app's heap
    // is the conventional share for an image cache.
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(4 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // SEPARATE cache for display-resolution frames, and this separation is the point. A display
    // frame is ~5 MB against ~0.4 MB for a grid thumbnail, so sharing one budget let a couple of
    // preloaded viewer frames evict HUNDREDS of thumbnails — and the grid then re-decoded them
    // while scrolling, which is exactly what made scrolling choppy. Small by design: only the
    // current viewer page and its two neighbours are ever wanted.
    private val displayCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt().coerceAtLeast(24 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /**
     * A decoded thumbnail at roughly [px] on its longest side, or null.
     *
     * HEAVY — call off the main thread. On API 29+ this uses the platform's own thumbnail
     * service, which reads a stored thumbnail where one exists instead of decoding a 12 MP JPEG.
     * Below that it decodes with inSampleSize, which still avoids loading the full bitmap.
     */
    fun thumbnail(ctx: Context, item: Item, px: Int): Bitmap? {
        val key = "${item.id}@$px"
        cache.get(key)?.let { return it }
        // Measured on device: ~9ms median, and NOT the cause of scroll jank — profiling put the
        // stalls in the UI thread with zero slow bitmap uploads. Left as-is deliberately.
        val bmp = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
            } else {
                decodeSampled(ctx, item.uri, px)
            }
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Legacy path: measure first (inJustDecodeBounds), then decode at a power-of-two subsample. */
    private fun decodeSampled(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * The photo at FULL resolution and quality, or null.
     *
     * Deliberately NOT [thumbnail]: that goes through the platform's thumbnail service, which is
     * allowed to hand back a small stored preview — fine for a grid cell, wrong for actually
     * looking at a photograph.
     *
     * Uses ImageDecoder on API 28+ (which the camera requires anyway), letting it allocate a
     * HARDWARE bitmap where it can: a 12 MP frame is ~48 MB as ARGB_8888, and a hardware bitmap
     * keeps that in graphics memory instead of the ~256 MB Java heap this app already fills with
     * engine buffers. Not cached — one full frame at a time is the memory budget; the caller
     * shows the cached thumbnail until this arrives.
     *
     * VERY HEAVY — call off the main thread.
     */
    // The single most recent full decode. One entry, deliberately: it exists so the gallery's
    // expanding hero and the viewer that replaces it resolve to the SAME bitmap object, making
    // the handover pixel-identical instead of a second decode that has to be faded in.
    private var lastFull: Pair<Long, Bitmap>? = null

    fun full(ctx: Context, item: Item): Bitmap? {
        lastFull?.let { if (it.first == item.id) return it.second }
        val decoded = decodeFull(ctx, item)
        if (decoded != null) lastFull = item.id to decoded
        return decoded
    }

    private fun decodeFull(ctx: Context, item: Item): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(ctx.contentResolver, item.uri),
            ) { dec, _, _ ->
                // Immutable + default allocator => hardware bitmap when the device allows it.
                dec.isMutableRequired = false
            }
        } else {
            ctx.contentResolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrElse {
        // A frame too large for a GPU texture, or simply out of memory. Fall back to a large
        // sampled decode rather than showing nothing.
        Diag.w("gallery: full decode failed (${it.message}); falling back to sampled")
        runCatching { decodeSampled(ctx, item.uri, 2048) }.getOrNull()
    }

    /**
     * A decode sized for the SCREEN, not for zooming.
     *
     * The hero that expands out of the preview button is never drawn larger than the display, so
     * decoding 12 MP for it is waste that lands at the worst possible moment: allocating and
     * uploading a ~41 MB bitmap stalls the render thread in the middle of the animation, on a
     * panel with an 8.3ms budget. A power-of-two subsample to roughly display size is a fraction
     * of the cost and indistinguishable on screen. [full] is still used for the viewer, where
     * pinch-zoom genuinely needs the pixels.
     */
    fun display(ctx: Context, item: Item, px: Int): Bitmap? {
        val key = "${item.id}#d$px"
        displayCache.get(key)?.let { return it }
        val b = runCatching { decodeSampled(ctx, item.uri, px) }.getOrNull() ?: return null
        displayCache.put(key, b)
        return b
    }

    fun cachedDisplay(id: Long, px: Int): Bitmap? = displayCache.get("$id#d$px")

    /**
     * Cached bitmaps WITHOUT decoding, for seeding a composable's initial state.
     *
     * The async form always costs at least one frame, and a composable that renders nothing on
     * its first frame is a visible black flash — which is exactly what happened when the gallery's
     * expanding hero handed over to the viewer.
     */
    fun cachedFull(id: Long): Bitmap? = lastFull?.takeIf { it.first == id }?.second

    fun cachedThumbnail(id: Long, px: Int): Bitmap? = cache.get("$id@$px")

    /** What a photo remembers about how it was made. Any field may be null. */
    data class Info(
        val stock: String?,
        val shutter: String?,
        val iso: String?,
        val focal: String?,
        val taken: String?,
    ) {
        val isEmpty: Boolean
            get() = stock == null && shutter == null && iso == null &&
                focal == null && taken == null
    }

    /**
     * Read the capture metadata back out of the JPEG's EXIF. Off the main thread — this opens
     * and parses the file.
     *
     * Frames shot before the app wrote EXIF simply return nulls; the caller shows what it has
     * rather than inventing anything.
     */
    fun info(ctx: Context, item: Item): Info = runCatching {
        ctx.contentResolver.openInputStream(item.uri)?.use { input ->
            val e = androidx.exifinterface.media.ExifInterface(input)
            Info(
                stock = e.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT)
                    ?.takeIf { it.isNotBlank() },
                shutter = e.getAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME,
                )?.toDoubleOrNull()?.let { formatShutter(it) },
                // TAG_PHOTOGRAPHIC_SENSITIVITY is the current EXIF 2.3 name; older writers
                // (and the DNGs this pipeline copies from) may only carry the deprecated
                // TAG_ISO_SPEED_RATINGS, so fall back rather than showing nothing.
                iso = (
                    e.getAttribute(
                        androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ) ?: e.getAttribute(
                        androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS,
                    )
                    )?.toIntOrNull()?.let { "ISO $it" },
                focal = e.getAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                )?.toIntOrNull()?.let { "${it}mm" },
                taken = e.getAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
                )?.let { parseExifDate(it) } ?: formatTaken(item.dateAdded * 1000L),
            )
        } ?: Info(null, null, null, null, formatTaken(item.dateAdded * 1000L))
    }.getOrElse { Info(null, null, null, null, null) }

    /** Photographers read shutter speeds as fractions, not decimals: 0.008s means nothing. */
    private fun formatShutter(seconds: Double): String = when {
        seconds <= 0.0 -> "—"
        seconds >= 1.0 -> "%.1fs".format(seconds)
        else -> "1/${Math.round(1.0 / seconds)}s"
    }

    private fun formatTaken(ms: Long): String =
        java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))

    private fun parseExifDate(raw: String): String? = runCatching {
        val d = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(raw)
        d?.let { formatTaken(it.time) }
    }.getOrNull()

    /** Drop cached thumbnails — call when the list changes so a deleted item cannot linger. */
    /** Outcome of a delete attempt. [NeedsConsent] carries the system dialog to launch. */
    sealed interface DeleteResult {
        object Deleted : DeleteResult
        data class NeedsConsent(val request: android.content.IntentSender) : DeleteResult
        data class Failed(val message: String) : DeleteResult
    }

    /**
     * Delete one photo from MediaStore. Call off the main thread.
     *
     * A direct delete succeeds for media THIS INSTALL created, which is the normal case. It stops
     * being the normal case after a reinstall: ownership is recorded per install, so the rows
     * survive while the claim to them does not, and the delete then throws instead of silently
     * doing nothing. On API 30+ that is recoverable by asking the system to show its own consent
     * dialog ([NeedsConsent]); on 29 the exception carries the same thing. Below 29 the
     * WRITE_EXTERNAL_STORAGE grant covers it and there is nothing to recover from.
     *
     * Deleting the MediaStore row deletes the JPEG. The source DNG is NOT touched — it lives in
     * app-private storage and is managed by Settings > Storage.
     */
    fun delete(ctx: Context, item: Item): DeleteResult {
        return try {
            val n = ctx.contentResolver.delete(item.uri, null, null)
            if (n > 0) DeleteResult.Deleted
            else DeleteResult.Failed("The photo was already gone.")
        } catch (se: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(ctx.contentResolver, listOf(item.uri))
                        .intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    (se as? android.app.RecoverableSecurityException)
                        ?.userAction?.actionIntent?.intentSender
                else -> null
            }
            if (sender != null) DeleteResult.NeedsConsent(sender)
            else DeleteResult.Failed(se.message ?: "Not allowed to delete this photo.")
        } catch (t: Throwable) {
            DeleteResult.Failed(t.message ?: "Could not delete the photo.")
        }
    }

    fun clearCache() {
        cache.evictAll()
        displayCache.evictAll()
    }
}
