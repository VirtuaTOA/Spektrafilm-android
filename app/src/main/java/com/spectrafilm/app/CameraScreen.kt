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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
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

private val SELECTED = Color.White
private val UNSELECTED = Color(0xFF8A8A8A)
private val STOCK_ITEM_WIDTH = 170.dp

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

    var granted by remember { mutableStateOf(CameraInventory.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) permission.launch(android.Manifest.permission.CAMERA) }

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
        mutableStateOf(lenses.firstOrNull { it.label == "1x" } ?: lenses.first())
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
    // at an index the new (shorter) list does not have.
    val listState = remember(slideMode) { LazyListState() }
    // The centred item IS the selection — the point of a snapping scroller.
    val centred by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2) - mid) }?.index ?: 0
        }
    }
    val stockIndex = centred.coerceIn(0, stocks.lastIndex)
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

    var engine by remember { mutableStateOf<SpektraEngine?>(null) }
    LaunchedEffect(Unit) {
        engine = runCatching { withContext(Dispatchers.IO) { EngineHolder.get(ctx) } }.getOrNull()
    }

    val session = remember { CameraSession(ctx) { msg -> error = msg } }
    DisposableEffect(Unit) { onDispose { session.close() } }

    val rotation = remember(lens) {
        val sensor = CameraInventory.sensorOrientation(ctx, lens.logicalId)
        ((sensor - displayRotationDegrees(ctx)) + 360) % 360
    }
    val previewSize = remember(lens) { CameraInventory.previewSize(ctx, lens.logicalId) }
    val displayAspect = remember(rotation, previewSize) {
        val srcA = previewSize.width.toFloat() / previewSize.height.toFloat()
        if (rotation == 90 || rotation == 270) 1f / srcA else srcA
    }
    // Device-verified 0 on SM-S931B: this driver's SurfaceTexture transform matrix already
    // applies the sensor->display rotation (see CameraGlPreview).
    val uvRotation = 0

    var surface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(surface, lens) {
        val s = surface ?: return@LaunchedEffect
        session.open(lens, s, previewSize)
        aeLocked = false
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

    // One automatic meter shortly after the stream starts, left UNLOCKED, so the viewfinder
    // is never wildly wrong before the user touches anything.
    LaunchedEffect(surface, lens, engine) {
        val e = engine ?: return@LaunchedEffect
        if (surface == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        if (aeLocked) return@LaunchedEffect
        val img = session.latestLumaImage() ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.Default) {
                img.use { e.meterExposureEv(it, camState.toParams()) }
            }
        }.getOrNull()?.let { gain = Math.pow(2.0, it).toFloat() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraGlPreview(
            uvRotationDegrees = uvRotation,
            displayAspect = displayAspect,
            bufferWidth = previewSize.width,
            bufferHeight = previewSize.height,
            modifier = Modifier.fillMaxSize(),
            lut = lut,
            exposureGain = gain,
            onSurfaceReady = { s -> surface = s },
            onUnavailable = { error = "GPU viewfinder unavailable" },
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black)
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let {
                Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }

            ProcessToggle(slideMode = slideMode, onToggle = { slideMode = !slideMode })

            StockScroller(
                stocks = stocks,
                selectedIndex = stockIndex,
                listState = listState,
                onPick = { i -> scope.launch { listState.animateScrollToItem(i) } },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                for (l in lenses) {
                    LensChip(label = l.label, selected = l == lens, onClick = { lens = l })
                }
                AeChip(locked = aeLocked, onClick = {
                    if (aeLocked) { session.setAeLock(false); aeLocked = false } else meterAndLock()
                })
            }

            ShutterButton(onClick = { error = "Capture arrives in the next step" })
        }
    }
}

/** NEGATIVE / SLIDE, two-tone like a stock camera's mode label. */
@Composable
private fun ProcessToggle(slideMode: Boolean, onToggle: () -> Unit) {
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = if (!slideMode) SELECTED else UNSELECTED,
                    fontWeight = if (!slideMode) FontWeight.Bold else FontWeight.Normal,
                )
            ) { append("NEGATIVE") }
            withStyle(SpanStyle(color = UNSELECTED)) { append("   /   ") }
            withStyle(
                SpanStyle(
                    color = if (slideMode) SELECTED else UNSELECTED,
                    fontWeight = if (slideMode) FontWeight.Bold else FontWeight.Normal,
                )
            ) { append("SLIDE") }
        },
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 6.dp),
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
    // Half a screen minus half an item, so the first and last entries can reach the centre.
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val sidePad = ((screenW - STOCK_ITEM_WIDTH) / 2).coerceAtLeast(0.dp)
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

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) SELECTED else UNSELECTED, fontSize = 11.sp)
    }
}

@Composable
private fun AeChip(locked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(38.dp).clip(RoundedCornerShape(19.dp))
            .background(if (locked) Color(0x33FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (locked) "AE-L" else "AE",
            color = if (locked) SELECTED else UNSELECTED,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
    }
}

/** Classic shutter: filled disc inside a ring. */
@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Canvas(Modifier.size(70.dp).clip(RoundedCornerShape(35.dp)).clickable(onClick = onClick)) {
        val r = size.minDimension / 2f
        drawCircle(Color.White, radius = r - 2.dp.toPx(), style = Stroke(width = 3.dp.toPx()))
        drawCircle(Color.White, radius = r - 9.dp.toPx())
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
