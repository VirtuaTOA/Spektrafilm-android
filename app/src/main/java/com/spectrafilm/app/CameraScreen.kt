/*
 * Spektrafilm for Android — in-app camera screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * A stock-camera-style viewfinder: the film stock is chosen from a snapping horizontal
 * scroller in place of a phone camera's mode picker, so changing film feels like changing
 * mode rather than opening a settings panel.
 *
 * NEGATIVE / SLIDE is not merely a filter over names. Reversal (slide) film IS a positive,
 * so it is scanned directly instead of being printed — `scanFilm = true` — which is a
 * genuinely different render route through the engine. Selecting SLIDE therefore swaps
 * both the stock list and the route.
 *
 * METER/LOCK, NOT CONTINUOUS AE. Two exposures are in play — the SENSOR's (Camera2's
 * auto-exposure) and the ENGINE's digital gain. If the sensor keeps re-metering, the RAW's
 * linear values move underneath a pinned engine gain and it drifts out of correctness. The
 * lock pins both in one action. The gain is stock-INDEPENDENT (spk_meter_exposure_ev reads
 * only the AE settings, never the film profile), so swiping stocks does not re-meter.
 */
package com.spectrafilm.app

import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private val SELECTED = Color.White
private val UNSELECTED = Color(0xFF8A8A8A)
// Narrow enough that the previous/next stock stay readable either side of the centred
// one. Wider items pushed the neighbours off the edges entirely.
private val STOCK_ITEM_WIDTH = 124.dp
private val FOCUS_ITEM_WIDTH = 74.dp

/** 35 mm frame: 36x24 mm. The sensor is 4:3; this is what the app keeps. */
/** 35 mm is 3:2. Shared: the viewfinder frame, the saved crop and the focal-length maths must
 *  all agree — a mismatch would report a field of view the photograph does not have. */
internal const val FILM_ASPECT = 3f / 2f

/** Narrowest the landscape control panel may become. The viewfinder takes a full-height 3:2
 *  frame and the panel takes the rest, so this is the backstop that keeps the controls usable
 *  on a screen too wide (or too short) for both to fit at full size.
 *
 *  A BACKSTOP, not the usual outcome: with the bars hidden in landscape the frame is
 *  height-limited and the panel gets whatever is left (~211dp here), comfortably above this. */
private val LANDSCAPE_PANEL_MIN = 200.dp

/**
 * Breathing room between the frame's edge and whatever is beyond it — the screen edge in
 * portrait, the top and bottom edges in landscape. Applied to the viewfinder box in BOTH
 * orientations, so the frame keeps its 3:2 shape and simply sits a little smaller inside.
 *
 * The frame filled its available space exactly in both layouts, which put its edge hard against
 * the screen and read as cramped.
 */
private val FRAME_MARGIN = 10.dp

/** Inset of the AE/AF readout from the frame's top-right corner. Matches [FRAME_MARGIN], so the
 *  readout sits the same distance inside the frame as the frame sits from the screen edge. */
private val TOGGLE_INSET = 10.dp

/** Shutter button diameter. Shared, because the portrait layout centres NEGATIVE/SLIDE in the
 *  gap between the shutter's edge and the screen edge and needs its radius to find that edge. */
private val SHUTTER_DIAMETER = 70.dp

/**
 * Left inset for the whole landscape layout, so the frame is not flush against the screen edge.
 *
 * Chosen to BALANCE the two gaps either side of the frame: the one to the screen edge, and the
 * one to the lens row that starts the control panel. The panel keeps its content centred in
 * whatever width is left, so with screen width W, frame width F and lens-row width L, the right
 * gap is (W - P - F)/2 - L/2. Setting that equal to P gives P = (W - F - L)/3, which measured
 * off the device lands near 29dp NOW THAT THE BARS ARE HIDDEN: the balance depends on the frame
 * width, and reclaiming the bars grew the frame from 444dp to 540dp, which left less room either
 * side to share out. At the old 58dp the frame would hit the panel cap and go back to being
 * width-limited — i.e. the black bars would return — so this is no longer purely cosmetic.
 *
 * W=780dp, F=540dp, L~153dp -> P = (780 - 540 - 153)/3 = 29dp.
 */
private val LANDSCAPE_FRAME_START = 29.dp

@Composable
fun CameraScreen() {
    // Direct SDK_INT check, not CameraInventory.isSupported: lint's NewApi analysis follows
    // a literal Build.VERSION comparison but cannot see through a property.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        CameraMessage(
            "The in-app camera needs Android 9 or newer.\n\n" +
                "Selecting the telephoto lens and configuring the capture session both use " +
                "APIs added in Android 9. Importing photos still works on this device."
        )
        return
    }
    CameraScreenSupported()
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun CameraScreenSupported() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var granted by remember { mutableStateOf(CameraInventory.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) permission.launch(android.Manifest.permission.CAMERA) }

    // POST_NOTIFICATIONS is separate and, on Android 13+, ungranted means the foreground
    // service's notification is silently suppressed — the service still runs, but the user
    // gets no sign that anything is happening. Asked once, after camera, so the two prompts
    // do not collide.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* processing continues regardless; only the progress UI depends on it */ }
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!granted) {
        CameraMessage(
            if (denied) {
                "Camera permission was declined.\n\nGrant it in Android Settings > Apps > " +
                    "Spektrafilm > Permissions, then come back."
            } else {
                "Waiting for camera permission…"
            }
        )
        return
    }

    val lenses = remember { CameraInventory.rearLenses(ctx) }
    if (lenses.isEmpty()) {
        CameraMessage("No rear camera reported RAW-capable output on this device.")
        return
    }
    var lens by remember {
        mutableStateOf(lenses.minByOrNull { abs(it.equivFocalMm - 24) } ?: lenses.first())
    }

    // --- film stocks, split by process --------------------------------------------------
    // One preset per film stock, each carrying its own process: reversal stocks set
    // scanFilm (scanned as a positive rather than printed), negatives do not. So the
    // toggle filters on the preset's own group rather than second-guessing the stock.
    var slideMode by remember { mutableStateOf(false) }
    val allPresets = remember { runCatching { BuiltInPresets.load(ctx) }.getOrDefault(emptyList()) }
    val stocks = remember(slideMode, allPresets) {
        allPresets.filter { (it.group == "Slide") == slideMode }
    }
    if (stocks.isEmpty()) {
        CameraMessage("No film stocks available for this mode.")
        return
    }

    // A separate list state per process, so switching does not leave the scroller parked
    // at an index the new (shorter) list does not have. The chosen stock is REMEMBERED
    // across configuration changes: rotating recreates the Activity, and a fresh
    // LazyListState would silently reset the selection to the first stock.
    var savedStock by rememberSaveable(slideMode) { mutableIntStateOf(0) }
    val listState = remember(slideMode) {
        LazyListState(firstVisibleItemIndex = savedStock)
    }
    // The centred item IS the selection — the point of a snapping scroller.
    val centred by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - mid) }?.index ?: 0
        }
    }
    val stockIndex = centred.coerceIn(0, stocks.lastIndex)
    LaunchedEffect(stockIndex) { savedStock = stockIndex }
    val stock = stocks[stockIndex]

    // A FRESH ParamsState per stock, so switching never inherits the previous one; the
    // preset then sets film + print stock, process, grain, halation, couplers and grade.
    val camState = remember(stock.id) {
        ParamsState().also { BuiltInPresets.apply(stock, it) }
    }

    var error by remember { mutableStateOf<String?>(null) }
    var lut by remember { mutableStateOf<CubeLut?>(null) }
    var gain by remember { mutableFloatStateOf(1f) }
    var aeLocked by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var canCapture by remember(lens) { mutableStateOf(false) }

    // MediaActionSound, not a bundled asset: it is the platform shutter click, and using it
    // keeps the app compliant in regions that require an audible shutter.
    val shutterSound = remember { android.media.MediaActionSound() }
    DisposableEffect(Unit) {
        shutterSound.load(android.media.MediaActionSound.SHUTTER_CLICK)
        onDispose { shutterSound.release() }
    }
    // Brief blackout over the viewfinder — the visual half of the shutter. Driven by an
    // Animatable rather than a boolean so the fade cannot be cut short by recomposition.
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    var queued by remember { mutableStateOf(0) }
    var manualFocus by remember { mutableStateOf(false) }
    // Focus as a CONTINUOUS value in dioptres (1/m), not a step index. Dioptres are the
    // space focus physically moves in, so dragging is perceptually even across the range;
    // metres would spend most of the throw between 5 m and infinity where nothing changes.
    var focusDiopters by remember(lens) { mutableFloatStateOf(0f) }

    var engine by remember { mutableStateOf<SpektraEngine?>(null) }
    LaunchedEffect(Unit) {
        engine = runCatching { withContext(Dispatchers.IO) { EngineHolder.get(ctx) } }.getOrNull()
    }

    val session = remember { CameraSession(ctx) { msg -> error = msg } }
    DisposableEffect(Unit) { onDispose { session.close() } }

    // The app theme is light, so the system navigation bar renders as a white strip under
    // a black viewfinder. Paint it black for the camera only, and restore on the way out.
    val window = (ctx as? android.app.Activity)?.window
    DisposableEffect(window) {
        @Suppress("DEPRECATION")
        val previous = window?.navigationBarColor
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.BLACK
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        val hadLightIcons = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            @Suppress("DEPRECATION")
            if (previous != null) window.navigationBarColor = previous
            if (hadLightIcons != null) controller?.isAppearanceLightNavigationBars = hadLightIcons
        }
    }

    // LANDSCAPE GOES IMMERSIVE. The frame is height-limited there, so every dp of vertical
    // inset comes straight off it — and because the bars have to be cleared EVENLY to keep the
    // frame centred, the larger of the two is paid at both edges, roughly 64dp in total. Hiding
    // them returns all of it, and full-screen is what a camera normally does when turned
    // sideways. The bars stay available on a swipe.
    //
    // PORTRAIT deliberately keeps its bars: the frame is not height-limited there, so hiding
    // them would buy no size, and it would undo the downward shift that stops the top corners
    // being clipped by the status bar.
    DisposableEffect(window, landscape) {
        val c = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        if (landscape) {
            c?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        // Always restore on the way out — leaving the launcher or the editor without its
        // status bar would look like the app had broken the system UI.
        onDispose { c?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    val rotation = remember(lens) {
        val sensor = CameraInventory.sensorOrientation(ctx, lens.logicalId)
        ((sensor - displayRotationDegrees(ctx)) + 360) % 360
    }
    // Preview aspect follows the RAW aspect, so the viewfinder frames what the capture
    // will actually record rather than a 16:9 crop of a 4:3 sensor.
    val previewSize = remember(lens) {
        CameraInventory.previewSize(ctx, lens.logicalId, lens.rawSize)
    }
    // 35 mm film is 3:2. The sensor is 4:3, so the viewfinder shows — and the capture
    // keeps — the central 3:2 region: the app's output is film-shaped, not phone-shaped.
    val displayRotation = remember(configuration) { displayRotationDegrees(ctx) }
    val srcAspect = previewSize.width.toFloat() / previewSize.height.toFloat()
    // Everything below is in SCREEN space, because that is where the shader crops.
    val turned = rotation == 90 || rotation == 270
    val sceneAspect = if (turned) 1f / srcAspect else srcAspect     // scene as displayed
    val displayAspect = if (turned) 1f / FILM_ASPECT else FILM_ASPECT
    val crop = remember(sceneAspect, displayAspect) {
        // Trim whichever axis has surplus, so the sampled region's aspect equals the
        // letterbox's. Get this the wrong way round and the image is simply stretched.
        if (sceneAspect > displayAspect) (displayAspect / sceneAspect) to 1f
        else 1f to (sceneAspect / displayAspect)
    }
    // The driver's SurfaceTexture transform already carries the sensor->display rotation
    // FOR THE ORIENTATION THE STREAM WAS CONFIGURED IN — device-verified as 0 in portrait.
    // It does not re-derive when the device turns, so rotating to landscape left the image
    // turned by the display rotation. Counter-rotating by it keeps portrait at 0 (matching
    // the verified case) and corrects every other orientation.
    val uvRotation = ((360 - displayRotation) % 360 + 360) % 360

    // The camera must be RELEASED when the app leaves the foreground. Android reclaims it
    // for whatever comes next, so holding it across a background trip meant the session was
    // already dead on return and reopening failed ("camera error 3") until a force-stop.
    // Closing on ON_STOP and reopening on ON_START makes returning to the app just work.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<Surface?>(null) }
    // The session decides whether P3 was actually granted; the shader must only use the P3
    // matrix if it really was, so this is read back rather than assumed.
    var wideGamut by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, surface, lens) {
        // Only REopen what a stop actually closed. Without the flag this fires alongside
        // the LaunchedEffect below on first entry, opening the camera twice and racing the
        // two attempts against each other.
        var closedByStop = false
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    closedByStop = true
                    session.close()
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (closedByStop) {
                        closedByStop = false
                        surface?.let { session.open(lens, it, previewSize) }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(surface, lens) {
        val s = surface ?: return@LaunchedEffect
        session.open(lens, s, previewSize)
        aeLocked = false
        manualFocus = false
        // Poll rather than sleep once: CameraSession has a fallback ladder (RAW+P3 -> RAW
        // -> preview-only), so the final configuration is not known for a while. These are
        // plain fields on a non-observable object, so Compose has to be told — without this
        // the shutter stays disabled forever even once RAW configures successfully.
        repeat(12) {
            kotlinx.coroutines.delay(300)
            wideGamut = session.previewIsDisplayP3
            canCapture = session.canCapture
        }
    }

    // Bake only once the scroll settles — mid-swipe the selection changes every frame, and
    // baking each one would be wasted work even with the cache.
    LaunchedEffect(engine, stock.id, slideMode, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val e = engine ?: return@LaunchedEffect
        val baked = withContext(Dispatchers.Default) { LutBakery.bake(e, camState, stock.id) }
        if (baked == null) Diag.w("camera: LUT bake failed for ${stock.id} -> passthrough")
        lut = baked
    }

    fun meterAndLock() {
        val e = engine ?: return
        scope.launch {
            val img = session.latestLumaImage() ?: return@launch
            val ev = runCatching {
                withContext(Dispatchers.Default) {
                    img.use { e.meterExposureEv(it, camState.toParams()) }
                }
            }.getOrNull() ?: return@launch
            gain = Math.pow(2.0, ev).toFloat()
            session.setAeLock(true)
            aeLocked = true
            Diag.i("camera: metered ev=%.3f gain=%.3f, AE locked".format(ev, gain))
        }
    }

    /**
     * Re-meter WITHOUT locking and update the shader gain.
     *
     * WHY THIS EXISTS: the baked LUT carries no exposure, so the viewfinder's brightness is
     * whatever [gain] was last set to. That was metered ONCE, 1.2s after the stream started
     * — measured on device to be while `aeState` was still SEARCHING, against a frame 0.67 EV
     * darker than the one AE eventually settled on. The gain is therefore stale almost from
     * the moment it is taken, and stays stale until the user happens to meter or switch lens.
     * That is why tapping AE-L "fixes" a viewfinder that looks wrong after a capture: it is
     * not the lock that helps, it is the re-meter that comes with it.
     *
     * [delayMs] lets a few fresh preview frames land first — right after a still,
     * latestLumaImage() can still be holding a frame from during the capture. Kept SHORT
     * (~4 frames at 30fps): the correction is visible as a brightness step, so it needs to
     * land inside the 260ms shutter-flash animation that is covering the viewfinder anyway.
     * At 400ms the step landed after the flash had cleared and read as a visible jump.
     *
     * Does nothing when the user has locked AE: they pinned that exposure deliberately.
     */
    fun meterUnlocked(why: String, delayMs: Long = 0L) {
        val e = engine ?: return
        if (aeLocked) return
        scope.launch {
            if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
            if (aeLocked) return@launch          // may have been locked during the delay
            val img = session.latestLumaImage() ?: return@launch
            val ev = runCatching {
                withContext(Dispatchers.Default) {
                    img.use { e.meterExposureEv(it, camState.toParams()) }
                }
            }.getOrNull() ?: return@launch
            gain = Math.pow(2.0, ev).toFloat()
            Diag.i("camera: re-meter [$why] ev=%.3f gain=%.3f".format(ev, gain))
        }
    }

    // One automatic meter shortly after the stream starts, left UNLOCKED, so the viewfinder
    // is never wildly wrong before the user touches anything.
    LaunchedEffect(surface, lens, engine) {
        if (surface == null) return@LaunchedEffect
        // 1800ms, not 1200: on device the 1200ms meter landed while CONTROL_AE_STATE was
        // still SEARCHING, so it metered a frame 0.67 EV darker than the one AE settled on
        // and pinned a gain that was wrong before the user had touched anything.
        kotlinx.coroutines.delay(1800)
        meterUnlocked("startup")
    }

    LaunchedEffect(Unit) {
        while (true) {
            queued = CaptureQueue.pending(ctx)
            kotlinx.coroutines.delay(1500)
        }
    }

    // Extracted so portrait and landscape share one definition: the arrangement differs,
    // the controls do not.
    val toggles: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TwoToneToggle(
                left = "AE", right = "AE-L", rightActive = aeLocked,
                onClick = {
                    if (aeLocked) {
                        session.setAeLock(false); aeLocked = false
                    } else {
                        meterAndLock()
                    }
                },
            )
            if (lens.supportsManualFocus) {
                TwoToneToggle(
                    left = "AF", right = "MF", rightActive = manualFocus,
                    onClick = {
                        manualFocus = !manualFocus
                        session.setFocus(manualFocus, focusDiopters)
                    },
                )
            }
        }
    }

    val controls: @Composable ColumnScope.() -> Unit = {
        error?.let {
            Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
        }

        if (manualFocus) {
            FocusWheel(
                minDiopters = lens.minFocusDiopters,
                diopters = focusDiopters,
                onChange = { d ->
                    focusDiopters = d
                    session.setFocus(true, d)
                },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (l in lenses) {
                LensChip(label = l.label, selected = l == lens, onClick = { lens = l })
            }
        }

        StockScroller(
            stocks = stocks,
            selectedIndex = stockIndex,
            listState = listState,
            onPick = { i -> scope.launch { listState.animateScrollToItem(i) } },
        )

        val shutter: @Composable () -> Unit = {
        ShutterButton(
            enabled = canCapture && !capturing,
            onClick = {
                if (!canCapture) {
                    error = "This lens cannot capture RAW"
                    return@ShutterButton
                }
                capturing = true
                shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                scope.launch {
                    flash.snapTo(1f)
                    flash.animateTo(
                        0f,
                        androidx.compose.animation.core.tween(durationMillis = 260),
                    )
                }
                val target = java.io.File(
                    CaptureQueue.captureDir(ctx),
                    "SPK_${System.currentTimeMillis()}.dng",
                )
                session.capture(target, displayRotationDegrees(ctx)) { file, err ->
                    capturing = false
                    // Re-meter after the shutter. This is exactly what the user was doing by
                    // hand with AE-L to clear the post-capture brightness jump; doing it here
                    // makes it automatic. No-op when AE is locked.
                    meterUnlocked("after capture", delayMs = 120)
                    if (file == null) {
                        error = err ?: "capture failed"
                    } else {
                        error = null
                        ProcessingService.enqueue(
                            ctx,
                            CaptureJob(file.absolutePath, stock.id, System.currentTimeMillis()),
                        )
                        queued = CaptureQueue.pending(ctx)
                    }
                }
            },
        )
        }

        // PORTRAIT puts NEGATIVE/SLIDE BESIDE the shutter rather than on its own row. That row
        // cost the controls column ~30dp of height, and since the viewfinder takes whatever the
        // column leaves, it came straight out of the frame — pushing its top edge up under the
        // status bar, where the rounded corners were being clipped.
        //
        // LANDSCAPE keeps them stacked: that panel is only ~254dp wide, so beside a 70dp
        // shutter the toggle would have ~92dp to live in and would be cut off.
        if (landscape) {
            ProcessToggle(slideMode = slideMode, onToggle = { slideMode = !slideMode })
            shutter()
        } else {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.Center)) { shutter() }
                // Taking the right HALF and insetting it by the shutter's radius makes this
                // region exactly [shutter's right edge .. screen edge]. Centring the toggle in
                // it makes the two gaps equal BY CONSTRUCTION, so it stays correct on any
                // screen width instead of relying on a padding tuned to this phone.
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .fillMaxWidth(0.5f)
                        .padding(start = SHUTTER_DIAMETER / 2),
                    contentAlignment = Alignment.Center,
                ) {
                    ProcessToggle(slideMode = slideMode, onToggle = { slideMode = !slideMode })
                }
            }
        }
    }

    val viewfinder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            CameraGlPreview(
                uvRotationDegrees = uvRotation,
                displayAspect = displayAspect,
                cropU = crop.first,
                cropV = crop.second,
                wideGamut = wideGamut,
                bufferWidth = previewSize.width,
                bufferHeight = previewSize.height,
                modifier = Modifier.fillMaxSize(),
                lut = lut,
                exposureGain = gain,
                onSurfaceReady = { s -> surface = s },
                onUnavailable = { error = "GPU viewfinder unavailable" },
            )
            // INSIDE the frame, not over the layout. Both orientations size the viewfinder box
            // to the 3:2 shape, so a TopEnd alignment here lands in the frame's own top-right
            // corner either way — one placement and one inset, consistent by construction.
            // Portrait used to overlay the SCREEN's top-right (which sat above the frame, hard
            // against the status bar) and landscape stacked these at the top of the side panel:
            // two placements that agreed on nothing. Declared BEFORE the flash overlay so a
            // capture blacks the readout out along with the image.
            Box(Modifier.align(Alignment.TopEnd).padding(TOGGLE_INSET)) { toggles() }
            if (flash.value > 0f) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = flash.value.coerceIn(0f, 1f)))
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (landscape) {
            // Side-by-side: the viewfinder takes the width it can and the controls become a
            // right-hand panel. Stacking them vertically (the portrait arrangement) left the
            // viewfinder a squashed strip with a wall of black beneath it.
            // systemBarsPadding on the ROW: in landscape the status bar sits over the
            // viewfinder's top edge, so its icons showed through the image.
            // VERTICAL CENTRING: systemBarsPadding() applies each edge's REAL inset, and in
            // landscape the top (status bar) and bottom (gesture bar) differ — so the
            // letterboxed frame ended up with visibly more black above it than below. Using
            // the LARGER of the two on both edges keeps the frame clear of each bar while
            // leaving it centred on the screen.
            val bars = WindowInsets.systemBars.asPaddingValues()
            val evenVertical = maxOf(bars.calculateTopPadding(), bars.calculateBottomPadding())
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
                    .padding(top = evenVertical, bottom = evenVertical)
                    // On the CONSTRAINTS, not the inner Row: maxWidth below must already have
                    // this taken out, or the frame-vs-panel split would be computed against
                    // width the layout does not actually have.
                    .padding(start = LANDSCAPE_FRAME_START),
            ) {
                // Size the viewfinder box to EXACTLY a full-height 3:2 frame, rather than
                // handing it the leftover width and letting the GL view letterbox inside it.
                //
                // Before, the panel's fixed 300.dp left the frame width-limited: it filled that
                // width and paid for it with black bars above and below. Deriving the width
                // from the height instead makes the box the frame's own shape, so the letterbox
                // collapses to nothing and the frame is as large as the screen height allows —
                // the aspect ratio is unchanged and nothing is cropped, so the finder still
                // shows precisely what the file will contain.
                //
                // Capped so the panel keeps LANDSCAPE_PANEL_MIN: on a proportionally wider or
                // shorter screen a full-height frame could otherwise crowd the controls out,
                // and there the bars come back rather than the buttons becoming unusable.
                val frameWidth = minOf(maxHeight * FILM_ASPECT, maxWidth - LANDSCAPE_PANEL_MIN)
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.width(frameWidth).fillMaxHeight().padding(FRAME_MARGIN),
                    ) { viewfinder() }
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(Color.Black)
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    ) {
                        controls()
                    }
                }
            }
        } else {
            // COLUMN, not an overlay. The viewfinder used to fillMaxSize() with the controls
            // painted over its lower part at BottomCenter — so the GL view letterboxed a
            // correct 3:2 frame into the WHOLE screen (1080x2340 -> 1080x1620, centred) and
            // the opaque control panel then covered its bottom ~340px. The finder was framing
            // the full frame all along; the user simply could not see the bottom fifth of it,
            // so every capture came back containing things that were never on screen.
            //
            // Giving the viewfinder weight(1f) hands it only the space actually left over, and
            // it letterboxes within that — so what is visible is the whole frame. Landscape
            // already did this with a Row + fixed side panel, which is why it never showed
            // the fault.
            // statusBarsPadding: without it the frame's top edge ran under the status bar and
            // its rounded corners were clipped by the icons. Everything shifts down by the bar's
            // height, which the reclaimed NEGATIVE/SLIDE row more than pays for.
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(FRAME_MARGIN),
                ) { viewfinder() }
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.Black)
                        .navigationBarsPadding()
                        .padding(top = 10.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = controls,
                )
            }
        }

        // Outside the if/else, so ONE placement serves both orientations — and outside both
        // layouts, so appearing and disappearing cannot move the viewfinder.
        if (queued > 0) {
            RenderingIndicator(
                queued = queued,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .systemBarsPadding()
                    .padding(end = 14.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * Continuous manual focus, modelled on a lens barrel rather than a list of presets.
 *
 * Drag anywhere along it and focus follows the finger LIVE — no detents, no waiting for a
 * release to commit. The throw is deliberately long (the full range spans about 2.2 screen
 * widths) so fine adjustment is possible near the close end, the way a long-throw vintage
 * helicoid behaves. The scale runs CLOSE on the left to INFINITY on the right, so dragging
 * left brings infinity toward the centre marker.
 *
 * Ticks are drawn in dioptre space so their spacing matches the actual focus travel.
 */
@Composable
private fun FocusWheel(minDiopters: Float, diopters: Float, onChange: (Float) -> Unit) {
    val current = rememberUpdatedState(diopters)
    val cb = rememberUpdatedState(onChange)
    var widthPx by remember { mutableFloatStateOf(1f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            focusLabel(diopters),
            color = SELECTED,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(minDiopters) {
                    detectHorizontalDragGestures { _, dx ->
                        if (minDiopters <= 0f) return@detectHorizontalDragGestures
                        val perPx = minDiopters / (widthPx * 2.2f)
                        val next = (current.value + dx * perPx).coerceIn(0f, minDiopters)
                        cb.value(next)
                    }
                }
        ) {
            if (minDiopters <= 0f) return@Canvas
            val perPx = minDiopters / (widthPx * 2.2f)
            val cx = size.width / 2f
            val midY = size.height / 2f
            // Minor ticks every 1/40 of the range, major every 1/8 — dense enough to read
            // as movement under the finger without becoming a grey smear.
            val minor = minDiopters / 40f
            val major = minDiopters / 8f
            var k = 0
            val firstD = current.value - cx * perPx
            var d = kotlin.math.ceil(firstD / minor) * minor
            while (d <= current.value + cx * perPx) {
                if (d >= 0f && d <= minDiopters) {
                    val x = cx - (d - current.value) / perPx
                    val isMajor = kotlin.math.abs(d / major - (d / major).toInt()) < 0.02f
                    val h = if (isMajor) size.height * 0.42f else size.height * 0.22f
                    drawLine(
                        color = if (isMajor) Color(0xFFBBBBBB) else Color(0xFF6A6A6A),
                        start = androidx.compose.ui.geometry.Offset(x, midY - h),
                        end = androidx.compose.ui.geometry.Offset(x, midY + h),
                        strokeWidth = 2f,
                    )
                }
                d += minor
                if (++k > 400) break
            }
            // Fixed centre marker: the point the current distance is read against.
            drawLine(
                color = Color.White,
                start = androidx.compose.ui.geometry.Offset(cx, midY - size.height * 0.5f),
                end = androidx.compose.ui.geometry.Offset(cx, midY + size.height * 0.5f),
                strokeWidth = 4f,
            )
        }
    }
}

private fun focusLabel(diopters: Float): String {
    if (diopters <= 0.0001f) return "\u221e"
    val metres = 1f / diopters
    return if (metres >= 1f) {
        val v = (metres * 10f).roundToInt() / 10f
        if (v % 1f == 0f) "${v.toInt()} m" else "$v m"
    } else {
        "${(metres * 100f).roundToInt()} cm"
    }
}

/** NEGATIVE / SLIDE. */
@Composable
private fun ProcessToggle(slideMode: Boolean, onToggle: () -> Unit) =
    TwoToneToggle("NEGATIVE", "SLIDE", rightActive = slideMode, onClick = onToggle)

/**
 * "N photos rendering" with a cycling 1-2-3 stop animation, drawn as an OVERLAY.
 *
 * WHY AN OVERLAY: this was a Text inside the controls Column, so it appeared on every capture
 * and vanished again when the queue drained — resizing the column, and with it the viewfinder,
 * on every single shot. Drawn over the layout it takes no space in either orientation, so the
 * frame stays exactly where it was composed.
 *
 * The stops sit in a FIXED-WIDTH slot. Letting the string grow from one to three would change
 * the row's width, and against a bottom-RIGHT alignment that walks the whole label sideways
 * once a second — swapping one kind of twitch for another.
 */
@Composable
private fun RenderingIndicator(queued: Int, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(420)
            step = (step + 1) % 3
        }
    }
    val label = if (queued == 1) "1 photo rendering" else "$queued photos rendering"
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            color = UNSELECTED,
            fontSize = 9.sp,
            fontStyle = FontStyle.Italic,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
        Box(Modifier.width(14.dp)) {
            Text(
                ".".repeat(step + 1),
                color = UNSELECTED,
                fontSize = 9.sp,
                fontStyle = FontStyle.Italic,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * A stock-camera style two-tone label: the active half white and bold, the other grey.
 * Shared by NEGATIVE/SLIDE, AE/AE-L and AF/MF so they all read as one control idiom.
 */
@Composable
private fun TwoToneToggle(
    left: String,
    right: String,
    rightActive: Boolean,
    onClick: () -> Unit,
) {
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = if (!rightActive) SELECTED else UNSELECTED,
                    fontWeight = if (!rightActive) FontWeight.Bold else FontWeight.Normal,
                )
            ) { append(left) }
            withStyle(SpanStyle(color = UNSELECTED)) { append("  /  ") }
            withStyle(
                SpanStyle(
                    color = if (rightActive) SELECTED else UNSELECTED,
                    fontWeight = if (rightActive) FontWeight.Bold else FontWeight.Normal,
                )
            ) { append(right) }
        },
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Snapping horizontal stock picker; the centred item is the selection. */
@Composable
private fun StockScroller(
    stocks: List<BuiltInPreset>,
    selectedIndex: Int,
    listState: LazyListState,
    onPick: (Int) -> Unit,
) {
    // Padding is measured from THIS COMPOSABLE's width, not the screen's. In landscape the
    // scroller sits in a 300dp panel while the screen is ~800dp wide, so screen-derived
    // padding pushed every item off both edges — the list looked empty until you swiped it.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val sidePad = ((maxWidth - STOCK_ITEM_WIDTH) / 2).coerceAtLeast(0.dp)
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        contentPadding = PaddingValues(horizontal = sidePad),
        modifier = Modifier.fillMaxWidth().height(34.dp),
    ) {
        itemsIndexed(stocks) { i, s ->
            val on = i == selectedIndex
            Box(
                Modifier.width(STOCK_ITEM_WIDTH).fillMaxHeight().clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.name,
                    color = if (on) SELECTED else UNSELECTED,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    }
}

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(23.dp))
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) SELECTED else UNSELECTED, fontSize = 11.sp)
    }
}

/** Classic shutter: filled disc inside a ring. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else UNSELECTED
    Canvas(
        Modifier.size(SHUTTER_DIAMETER).clip(RoundedCornerShape(SHUTTER_DIAMETER / 2))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        val r = size.minDimension / 2f
        drawCircle(tint, radius = r - 2.dp.toPx(), style = Stroke(width = 3.dp.toPx()))
        drawCircle(tint, radius = r - 9.dp.toPx())
    }
}

@Composable
private fun CameraMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Display rotation in degrees (0/90/180/270). */
@Suppress("DEPRECATION")
private fun displayRotationDegrees(ctx: android.content.Context): Int {
    val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ctx.display?.rotation
    } else {
        (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?.defaultDisplay?.rotation
    } ?: Surface.ROTATION_0
    return when (r) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
