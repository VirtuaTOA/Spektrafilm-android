/*
 * Spektrafilm for Android — in-app camera screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Phase 1 of docs/CAMERA_PLAN.md: a live viewfinder previewing the selected film stock
 * through a baked 3D LUT, with an explicit meter/lock button.
 *
 * METER/LOCK, NOT CONTINUOUS AE. Two exposures are in play — the SENSOR's (Camera2's
 * auto-exposure) and the ENGINE's digital gain. If the sensor keeps re-metering, the
 * RAW's linear values move underneath a pinned engine gain and it drifts out of
 * correctness. So the button locks BOTH in one action: CONTROL_AE_LOCK on the sensor,
 * and the engine's gain captured from the same moment. Pin both and the viewfinder's
 * exposure IS the capture's exposure, rather than an approximation of it. It also
 * removes per-frame metering, a smoothing filter, and gain flicker while panning.
 *
 * The gain is stock-INDEPENDENT (spk_meter_exposure_ev reads only the AE settings,
 * never the film profile), so swiping looks deliberately does not re-meter.
 */
package com.spectrafilm.app

import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One selectable look: a stable id, a label, and how to load it onto a fresh state. */
private class CameraLook(
    val id: String,
    val label: String,
    val applyTo: (ParamsState) -> Unit,
)

@Composable
fun CameraScreen() {
    // Direct SDK_INT check, not CameraInventory.isSupported: lint's NewApi analysis
    // follows a literal Build.VERSION comparison but cannot see through a property,
    // and the body below calls API-28 camera APIs for real.
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
                "Camera permission was declined.\n\n" +
                    "The camera needs it to show a viewfinder. You can grant it in " +
                    "Android Settings > Apps > Spektrafilm > Permissions, then come back."
            } else {
                "Waiting for camera permission…"
            }
        )
        return
    }

    // Rear lenses, including the physical sub-cameras that getCameraIdList() hides —
    // on this device the 3x telephoto is only reachable that way.
    val lenses = remember { CameraInventory.rearLenses(ctx) }
    if (lenses.isEmpty()) {
        CameraMessage("No rear camera reported RAW-capable output on this device.")
        return
    }
    var lens by remember {
        mutableStateOf(lenses.firstOrNull { it.label == "1x" } ?: lenses.first())
    }

    // Looks: "Neutral" is a bare ParamsState — its defaults are already kodak_portra_400
    // + kodak_portra_endura, the neutral rendering. User presets come first because they
    // are the user's own looks; the bundled ones follow.
    val looks = remember {
        buildList {
            add(CameraLook("neutral", "Neutral") { /* defaults are the neutral look */ })
            runCatching { Presets.list(ctx) }.getOrDefault(emptyList()).forEach { name ->
                add(CameraLook("user:$name", name) { st ->
                    runCatching { Presets.load(ctx, name, st) }
                })
            }
            runCatching { BuiltInPresets.load(ctx) }.getOrDefault(emptyList()).forEach { p ->
                add(CameraLook("builtin:${p.id}", p.name) { st -> BuiltInPresets.apply(p, st) })
            }
        }
    }
    var lookIndex by remember { mutableIntStateOf(0) }
    val look = looks[lookIndex]
    // A FRESH ParamsState per look, so switching never inherits the previous look's
    // parameters — applying a preset only overwrites the fields it declares.
    val camState = remember(lookIndex) { ParamsState().also { look.applyTo(it) } }

    var status by remember { mutableStateOf("starting camera…") }
    var glBroken by remember { mutableStateOf(false) }
    var lut by remember { mutableStateOf<CubeLut?>(null) }
    var baking by remember { mutableStateOf(false) }
    var gain by remember { mutableFloatStateOf(1f) }
    var meterEv by remember { mutableStateOf<Double?>(null) }
    var aeLocked by remember { mutableStateOf(false) }

    var engine by remember { mutableStateOf<SpektraEngine?>(null) }
    LaunchedEffect(Unit) {
        engine = runCatching { withContext(Dispatchers.IO) { EngineHolder.get(ctx) } }.getOrNull()
    }

    val session = remember { CameraSession(ctx) { msg -> status = "camera error: $msg" } }
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
    // applies the sensor->display rotation. A device that does NOT pre-rotate would need
    // `rotation` here, which is why it stays a parameter (see CameraGlPreview).
    val uvRotation = 0

    var surface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(surface, lens) {
        val s = surface ?: return@LaunchedEffect
        session.open(lens, s, previewSize)
        aeLocked = false
    }

    // Bake the look. Heavy on a miss (size^3 lattice points through the pipeline), free on
    // a cache hit, so swiping back to a previous stock is instant.
    LaunchedEffect(engine, lookIndex) {
        val e = engine ?: return@LaunchedEffect
        baking = true
        lut = withContext(Dispatchers.Default) { LutBakery.bake(e, camState, look.id) }
        baking = false
        if (lut == null) Diag.w("camera: LUT bake failed for ${look.id} -> passthrough")
    }

    /** Lock the sensor's AE and capture the engine's matching gain in one action. */
    fun meterAndLock() {
        val e = engine ?: return
        scope.launch {
            val img = session.latestLumaImage()
            if (img == null) { status = "no frame to meter yet"; return@launch }
            val ev = runCatching {
                withContext(Dispatchers.Default) { img.use { e.meterExposureEv(it, camState.toParams()) } }
            }.getOrNull()
            if (ev == null) { status = "metering failed"; return@launch }
            meterEv = ev
            gain = Math.pow(2.0, ev).toFloat()
            session.setAeLock(true)
            aeLocked = true
            Diag.i("camera: metered ev=%.3f gain=%.3f, AE locked".format(ev, gain))
        }
    }

    // Meter once automatically shortly after the stream starts, so the viewfinder is never
    // wildly wrong before the user touches anything. Left UNLOCKED: the sensor keeps
    // adapting until the user deliberately locks.
    LaunchedEffect(surface, lens, engine) {
        val e = engine ?: return@LaunchedEffect
        if (surface == null) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        val img = session.latestLumaImage() ?: return@LaunchedEffect
        val ev = runCatching {
            withContext(Dispatchers.Default) { img.use { e.meterExposureEv(it, camState.toParams()) } }
        }.getOrNull() ?: return@LaunchedEffect
        if (!aeLocked) { meterEv = ev; gain = Math.pow(2.0, ev).toFloat() }
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
            onUnavailable = { glBroken = true },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glBroken) {
                Text(
                    "GPU viewfinder unavailable — shader or GL context failed.",
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                buildString {
                    append("${lens.label} · ${lens.focalMm}mm · ")
                    append(if (baking) "baking look…" else if (lut != null) look.label else "passthrough")
                    meterEv?.let { append(" · %+.2f EV".format(it)) }
                    if (aeLocked) append(" · AE LOCK")
                },
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            // Film looks.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                looks.forEachIndexed { i, l ->
                    Button(
                        onClick = { lookIndex = i },
                        enabled = i != lookIndex,
                        colors = if (i == lookIndex) {
                            ButtonDefaults.buttonColors(containerColor = Color(0xFF6650A4))
                        } else ButtonDefaults.buttonColors(),
                    ) { Text(l.label, maxLines = 1) }
                }
            }
            // Lens + metering. Horizontally scrollable: a fixed Row silently pushed
            // "Meter + lock" off the right edge once there were enough lens buttons, so
            // the control simply vanished rather than wrapping.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (l in lenses) {
                    Button(onClick = { lens = l }, enabled = l != lens) {
                        Text("${l.label}${if (l.supportsRaw) "" else "*"}")
                    }
                }
                Button(onClick = { meterAndLock() }) { Text(if (aeLocked) "Re-meter" else "Meter + lock") }
                if (aeLocked) {
                    Button(onClick = { session.setAeLock(false); aeLocked = false }) { Text("Unlock") }
                }
            }
            Text(
                "Grain and halation cannot be previewed — a 3D LUT carries colour and tone " +
                    "only. They are applied on capture.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
