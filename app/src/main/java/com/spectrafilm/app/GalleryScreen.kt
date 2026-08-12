/*
 * Spektrafilm for Android — in-app gallery. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A camera roll for the app's own photos: a grid of what you have shot, and a full-screen
 * viewer you can swipe through. Reached from the preview button beside the shutter.
 *
 * WHY A SEPARATE SCREEN AND NOT AN OVERLAY ON THE CAMERA: composing this in place of
 * CameraScreen takes the camera out of composition, which releases the session. Browsing with a
 * live camera held open costs power and heats the phone for no benefit — and this app's captures
 * are already competing for the CPU with a 30-second render.
 *
 * THUMBNAILS LOAD OFF THE MAIN THREAD, one per cell, cached by Gallery. A 12 MP JPEG takes long
 * enough to decode that doing it inline would drop frames on every scroll.
 */
package com.spectrafilm.app

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

private val GALLERY_DIM = Color(0xFF8A8A8A)

/** Longest side, in px, decoded for a grid cell. Small: cells are ~120dp and never larger. */
private const val THUMB_PX = 320

/** Longest side for the expanding hero — screen-sized, not 12 MP. See Gallery.display(). */
private const val DISPLAY_PX = 1440

/** Open/close transition, ms. Slightly faster out than in — the standard asymmetry; a slow
 *  dismissal feels like the app is hesitating. */
private const val OPEN_MS = 420

/**
 * A modest ramp in, then a long deceleration: the photo gets moving, then arrives and settles.
 *
 * NOT FastOutSlowIn (accelerates first, so the ease-out at the end is too brief to read), and NOT
 * the aggressive ease-out that replaced it — (0.08, 0.82, 0.17, 1) covered roughly 80% of the
 * distance in the first 15% of the time, so by frame two the photo was already near full size and
 * the whole thing read as a jump followed by a crawl.
 */
private val OPEN_EASING = CubicBezierEasing(0.35f, 0f, 0.15f, 1f)

/** Slide settle, used when a back GESTURE is released (or a plain back fires with no drag).
 *  Short and decelerating: the gesture has usually done most of the travel already. */
private const val SLIDE_MS = 260
private val SLIDE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** How far a photo can be pinched in. 8x lets you inspect grain on a 12 MP frame. */
private const val MAX_ZOOM = 8f

/**
 * The camera's preview button: the most recent frame, as a small rounded thumbnail.
 *
 * [refreshKey] re-queries when it changes — pass the pending-render count so a frame that has
 * just finished processing appears without the user leaving the screen.
 */
@Composable
fun GalleryButton(
    size: androidx.compose.ui.unit.Dp,
    refreshKey: Any,
    /**
     * Receives the button's bounds in root coordinates AND the bitmap it is currently showing —
     * the rect and the pixels the gallery expands from. The bitmap matters: loading it again
     * asynchronously meant frame 1 of the animation had nothing to draw and rendered black.
     */
    onClick: (Rect, Bitmap?) -> Unit,
) {
    val ctx = LocalContext.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    // remember + LaunchedEffect rather than produceState: lint's
    // ProduceStateDoesNotAssignValue cannot see an assignment whose value comes back through
    // withContext, and it fails the build. This is the same thing, spelled where lint can read it.
    var latest by remember { mutableStateOf<Gallery.Item?>(null) }
    LaunchedEffect(refreshKey) {
        latest = withContext(Dispatchers.IO) { Gallery.latest(ctx) }
    }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(latest?.id) {
        val item = latest
        bmp = if (item == null) null
        else withContext(Dispatchers.IO) { Gallery.thumbnail(ctx, item, THUMB_PX) }
    }
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A1A1A))
            .border(1.dp, GALLERY_DIM, RoundedCornerShape(6.dp))
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable { onClick(bounds, bmp) },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Gallery",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // No placeholder glyph when empty: an empty frame reads as "nothing shot yet", which is
        // the truth, and a broken-image icon would read as a fault.
    }
}

/**
 * The gallery: a grid, or a full-screen pager once a frame is opened.
 *
 * [onBack] leaves the gallery entirely. Back from the VIEWER returns to the grid first, so the
 * hardware gesture unwinds one level at a time the way a photo app should.
 */
@Composable
fun GalleryScreen(origin: Rect? = null, hero: Bitmap? = null, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Gallery.Item>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var openAt by remember { mutableIntStateOf(0) }   // -1 = grid

    // OPENING keeps the hero expand: the preview square grows out of the button into the newest
    // frame. CLOSING is a horizontal slide instead of a collapse, at both levels.
    val grow = remember { Animatable(0f) }
    var arrived by remember { mutableStateOf(false) }
    val showHero = !arrived

    // Back progress, 0..1, one per level. Driven DIRECTLY by the back gesture where the platform
    // offers it, so the slide tracks the finger and snaps back if the gesture is abandoned —
    // rather than being a fixed animation that fires after the fact.
    val photoBack = remember { Animatable(0f) }    // photo -> grid
    val galleryBack = remember { Animatable(0f) }  // grid  -> camera

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { Gallery.list(ctx) }
        loaded = true
    }
    LaunchedEffect(Unit) {
        grow.animateTo(1f, tween(OPEN_MS, easing = OPEN_EASING))
        arrived = true
    }

    var heroFull by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(Unit) {
        heroFull = withContext(Dispatchers.IO) {
            Gallery.latest(ctx)?.let { Gallery.full(ctx, it) }
        }
    }

    // ONE handler, because only the innermost enabled one receives the gesture — two would fight.
    // Which level it drives depends on whether a photo is open.
    PredictiveBackHandler(enabled = true) { progress ->
        val inPhoto = openAt >= 0
        val bar = if (inPhoto) photoBack else galleryBack
        try {
            progress.collect { event -> bar.snapTo(event.progress.coerceIn(0f, 1f)) }
            // Released past the threshold: finish the slide, then commit.
            bar.animateTo(1f, tween(SLIDE_MS, easing = SLIDE_EASING))
            if (inPhoto) {
                openAt = -1
                bar.snapTo(0f)
            } else {
                onBack()
            }
        } catch (_: CancellationException) {
            // Abandoned mid-gesture — slide back to where it was.
            bar.animateTo(0f, tween(SLIDE_MS, easing = SLIDE_EASING))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Everything that belongs to the gallery moves as ONE unit on the way out, so the camera
        // is revealed behind it rather than the pieces leaving separately.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = -size.width * galleryBack.value
            },
        ) {
            // Backdrop. Squared so the camera stays readable through the early part of the open.
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { val p = grow.value; alpha = p * p }
                    .background(Color.Black),
            )

            if (arrived) {
                // GRID IS ALWAYS COMPOSED once open, with the photo layered over it — that is
                // what makes the photo's departure reveal something rather than a hole.
                val photoOpen = openAt >= 0
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        // Only follow photoBack WHILE a photo is actually open. Once the photo has
                        // gone, photoBack is snapped back to 0 ready for next time — and reading it
                        // unconditionally then drove the grid's own alpha to 0, which is why
                        // completing the back gesture landed on a black screen.
                        val reveal = if (photoOpen) photoBack.value else 1f
                        // Slight parallax: the grid settles in from the right as the photo leaves.
                        translationX = size.width * 0.18f * (1f - reveal)
                        alpha = reveal
                    },
                ) {
                    GalleryGrid(items, loaded) { openAt = it }
                }
                if (openAt >= 0 && openAt < items.size) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = -size.width * photoBack.value
                        },
                    ) {
                        GalleryViewer(items, openAt)
                    }
                }
            }

            // The expanding photo, only while opening.
            val from = origin?.takeIf { it.width > 0f }
            val shownHero = heroFull ?: hero
            if (shownHero != null && from != null && showHero) {
                Image(
                    bitmap = shownHero.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val p = grow.value
                            val fullW = constraints.maxWidth.toFloat()
                            val fullH = constraints.maxHeight.toFloat()
                            val ar = shownHero.width.toFloat() / shownHero.height.toFloat()
                            val tw: Float
                            val th: Float
                            if (fullW / fullH > ar) {
                                th = fullH; tw = th * ar
                            } else {
                                tw = fullW; th = tw / ar
                            }
                            val tl = (fullW - tw) / 2f
                            val tt = (fullH - th) / 2f
                            val w = from.width + (tw - from.width) * p
                            val h = from.height + (th - from.height) * p
                            val x = from.left + (tl - from.left) * p
                            val y = from.top + (tt - from.top) * p
                            val pl = measurable.measure(
                                Constraints.fixed(
                                    w.roundToInt().coerceAtLeast(1),
                                    h.roundToInt().coerceAtLeast(1),
                                ),
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                pl.place(x.roundToInt(), y.roundToInt())
                            }
                        }
                        .graphicsLayer {
                            val p = grow.value
                            clip = true
                            shape = RoundedCornerShape(6.dp.toPx() * (1f - p))
                        },
                )
            }
        }
    }
}

@Composable
private fun GalleryGrid(
    items: List<Gallery.Item>,
    loaded: Boolean,
    onOpen: (Int) -> Unit,
) {
    if (loaded && items.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "No photos yet",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "Frames you shoot appear here once they finish developing.",
                color = GALLERY_DIM,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val idx = items.indexOf(item)
            GalleryCell(item) { onOpen(idx) }
        }
    }
}

@Composable
private fun GalleryCell(item: Gallery.Item, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(item.id) { mutableStateOf(Gallery.cachedThumbnail(item.id, THUMB_PX)) }
    LaunchedEffect(item.id) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { Gallery.thumbnail(ctx, item, THUMB_PX) }
    }
    Box(
        // 3:2 cells, matching the frame the camera actually shoots, so the grid reads as
        // contact sheet rather than as a set of arbitrary crops.
        Modifier.fillMaxWidth().aspectRatio(FILM_ASPECT)
            .background(Color(0xFF141414))
            .clickable(onClick = onClick),
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = item.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun GalleryViewer(items: List<Gallery.Item>, startAt: Int) {
    val pager = rememberPagerState(initialPage = startAt, pageCount = { items.size })
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    // Every frame opens fit-to-screen; the zoom belongs to the photo you are looking at.
    LaunchedEffect(pager.settledPage) { scale = 1f; offset = Offset.Zero }

    HorizontalPager(
        state = pager,
        // A pinched-in photo must PAN, not page. Restored the moment it returns to fit.
        userScrollEnabled = scale <= 1.01f,
        modifier = Modifier.fillMaxSize().onSizeChanged { box = it },
    ) { page ->
        val ctx = LocalContext.current
        val item = items[page]
        val settled = pager.settledPage == page

        // SEEDED FROM CACHE, synchronously. Starting these at null meant the viewer's first
        // frame had no bitmap and drew black — and since it appears exactly as the expanding hero
        // is removed, that black frame landed right at the end of the animation. For the photo
        // the hero just finished expanding, cachedFull hits and the handover is seamless.
        var thumb by remember(item.id) {
            mutableStateOf(Gallery.cachedThumbnail(item.id, THUMB_PX))
        }
        LaunchedEffect(item.id) {
            if (thumb == null) {
                thumb = withContext(Dispatchers.IO) { Gallery.thumbnail(ctx, item, THUMB_PX) }
            }
        }
        // Full resolution for the settled page only — holding full frames for the neighbours too
        // would be ~150 MB of bitmaps mid-swipe.
        // Seeded from whatever is already decoded, best first — the hero's screen-sized bitmap
        // counts, so the handover has real pixels rather than falling back to a 320px thumbnail.
        var full by remember(item.id) {
            mutableStateOf(
                Gallery.cachedFull(item.id) ?: Gallery.cachedDisplay(item.id, DISPLAY_PX),
            )
        }
        LaunchedEffect(item.id, settled) {
            full = when {
                !settled -> null
                full != null -> full          // already have it; do not drop and re-decode
                else -> withContext(Dispatchers.IO) { Gallery.full(ctx, item) }
            }
        }

        val shown = full ?: thumb
        Box(
            Modifier.fillMaxSize()
                .pointerInput(item.id) {
                    // HAND-ROLLED, not detectTransformGestures: that helper CONSUMES every drag
                    // it sees, including a one-finger swipe, so the pager never received the
                    // gesture and paging silently did nothing. Here the events are only consumed
                    // when this photo should own them — a pinch, or a drag while zoomed in.
                    // A one-finger drag at fit is deliberately left unconsumed so it bubbles to
                    // the pager and pages.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val ours = fingers > 1 || scale > 1.01f
                            if (ours) {
                                if (zoom != 1f) {
                                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                }
                                offset = if (scale <= 1.001f) {
                                    Offset.Zero
                                } else {
                                    // Clamp so the frame cannot be flung off screen and lost.
                                    val maxX = box.width * (scale - 1f) / 2f
                                    val maxY = box.height * (scale - 1f) / 2f
                                    val o = offset + pan
                                    Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(item.id) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 3f
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            val b = shown
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
                    // FIT, not Crop: this is the frame as shot, and cropping it to the screen
                    // would hide part of the photograph.
                    contentScale = ContentScale.Fit,
                    // The default is Low, which is a cheap bilinear sample — visibly soft on a
                    // downscaled 12 MP frame, and the whole point here is to see the real image.
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}
