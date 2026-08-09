/*
 * Spektrafilm for Android — in-app camera screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Phase 1 of docs/CAMERA_PLAN.md: a live viewfinder that will preview the selected
 * film stock via a baked 3D LUT. This is the CHECKPOINT build — camera plumbing,
 * permission flow, lens selection and the GL surface, drawing a plain passthrough.
 * The LUT + meter/lock button land next (steps 1d-1f); the plumbing for both is
 * already threaded through CameraGlPreview so wiring them is additive.
 *
 * Verify the passthrough BEFORE the LUT goes in: a sideways or stretched viewfinder
 * and a wrong LUT are hard to tell apart once both are in play.
 */
package com.spectrafilm.app

import android.os.Build
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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

    var granted by remember { mutableStateOf(CameraInventory.hasPermission(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) {
        if (!granted) permission.launch(android.Manifest.permission.CAMERA)
    }

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
    // Default to the main lens: the widest RAW-capable one that is not the ultrawide,
    // falling back to whatever exists. Phase 0 found main = LEVEL_3, the others LIMITED.
    var lens by remember {
        mutableStateOf(lenses.firstOrNull { it.label == "1x" } ?: lenses.first())
    }
    var status by remember { mutableStateOf("starting camera…") }
    // Kept separate from `status`: the lens LaunchedEffect overwrites status, which
    // would silently swallow a GL failure reported at roughly the same moment.
    var glBroken by remember { mutableStateOf(false) }

    val session = remember { CameraSession(ctx) { msg -> status = "camera error: $msg" } }
    DisposableEffect(Unit) { onDispose { session.close() } }

    // Sensor orientation vs the display: the camera buffer arrives in sensor space, so
    // the viewfinder rotates it for display. Back camera => sensor - display.
    val rotation = remember(lens) {
        val sensor = CameraInventory.sensorOrientation(ctx, lens.logicalId)
        val display = displayRotationDegrees(ctx)
        ((sensor - display) + 360) % 360
    }
    val previewSize = remember(lens) { CameraInventory.previewSize(ctx, lens.logicalId) }

    // The scene's shape on screen. `rotation` is the sensor-vs-display difference, so at
    // 90/270 a landscape sensor buffer is showing a PORTRAIT scene and the letterbox must
    // use the inverted aspect. This is geometry and is always true — unlike the UV
    // rotation below, which depends on what the driver already did.
    val displayAspect = remember(rotation, previewSize) {
        val srcA = previewSize.width.toFloat() / previewSize.height.toFloat()
        if (rotation == 90 || rotation == 270) 1f / srcA else srcA
    }

    // UV rotation is SEPARATE from displayAspect (see CameraGlPreview). Device-verified
    // 0 on SM-S931B: this driver's SurfaceTexture transform matrix already applies the
    // sensor->display rotation, so the content arrives upright with no rotation of our
    // own. That is a driver behaviour rather than a guarantee — a device that does NOT
    // pre-rotate would need `rotation` here instead of 0, which is why this stays a
    // parameter rather than being hardcoded into the shader.
    val uvRotation = 0

    // The Surface only exists once GL has built it; reopening on a new Surface is
    // required after a context loss, so this is keyed on the surface identity.
    var surface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(surface, lens) {
        val s = surface ?: return@LaunchedEffect
        session.open(lens, s, previewSize)
        status = "${lens.label} · ${lens.focalMm}mm · preview ${previewSize.width}x${previewSize.height} · " +
            (lens.rawSize?.let { "RAW ${it.width}x${it.height}" } ?: "no RAW") +
            " · scene ${"%.2f".format(displayAspect)}:1"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraGlPreview(
            uvRotationDegrees = uvRotation,
            displayAspect = displayAspect,
            bufferWidth = previewSize.width,
            bufferHeight = previewSize.height,
            modifier = Modifier.fillMaxSize(),
            lut = null,             // step 1d/1e: the baked stock LUT goes here
            exposureGain = 1f,      // step 1e: from the meter/lock button
            onSurfaceReady = { s -> surface = s },
            onUnavailable = { glBroken = true },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glBroken) {
                Text(
                    "GPU viewfinder unavailable — shader or GL context failed. " +
                        "See Settings > Diagnostics > logcat for the shader log.",
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                status,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in lenses) {
                    Button(onClick = { lens = l }, enabled = l != lens) {
                        Text("${l.label}${if (l.supportsRaw) "" else "*"}")
                    }
                }
            }
            Text(
                "Passthrough — no film look yet. The flat, low-contrast rendering is " +
                    "correct: the ISP tone curve is switched off so the stream stays " +
                    "near-linear, which is what the film engine needs.",
                color = Color.White.copy(alpha = 0.75f),
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
