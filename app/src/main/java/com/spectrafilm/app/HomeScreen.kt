/*
 * Spektrafilm for Android — launch screen. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The app now has two genuinely different modes — take a photo, or edit one — and which
 * you want is not something the app can guess. Opening straight into the editor made the
 * camera feel buried; this puts the fork up front.
 *
 * The two icons are drawn with Canvas rather than declared as ImageVectors because they are
 * MULTI-COLOURED (a yellow canister with a grey leader, an orange brush with a red tip).
 * ImageVector in this codebase is built for single-tint UI glyphs, and tinting cannot carry
 * two colours in one mark.
 */
package com.spectrafilm.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(onShoot: () -> Unit, onEdit: () -> Unit) {
    // PORTRAIT-LOCKED. This screen is laid out with fixed vertical spacing — the title sits below
    // a 168.dp spacer — which is over half the height of a landscape window, so turning the phone
    // here pushed the buttons off the screen. Locking is the right call rather than making it
    // responsive: it is a launcher with two buttons, shown for a second on the way to somewhere
    // else, and neither destination wants a landscape home screen behind it.
    //
    // Restores the PREVIOUS setting on the way out, not a hardcoded default, so leaving for the
    // camera or the editor hands orientation control straight back to them — the camera in
    // particular is built to work both ways round.
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            activity?.requestedOrientation =
                previous ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(168.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SPEKTRAFILM",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = BRAND,
            )
            Spacer(Modifier.width(10.dp))
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        }

        Text(
            "Spectral simulation of analog photography processes",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModeCard(
                label = "SHOOT",
                modifier = Modifier.weight(1f),
                onClick = onShoot,
            ) { ModeIcon(R.drawable.ic_mode_shoot) }
            ModeCard(
                label = "EDIT",
                modifier = Modifier.weight(1f),
                onClick = onEdit,
            ) { ModeIcon(R.drawable.ic_mode_edit) }
        }

        Spacer(Modifier.weight(1.2f))

        // GPLv3 attribution — required, and it belongs where it is actually seen.
        Text(
            "Film modeling powered by spektrafilm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun ModeCard(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.aspectRatio(0.85f).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = Color.White,
            )
            Box(contentAlignment = Alignment.Center) { icon() }
        }
    }
}

/** Spektrafilm brand orange-yellow. */
private val BRAND = Color(0xFFFFAE42)

/**
 * Mode artwork. Drawn at its own colours — no tint — because these are illustrations
 * rather than UI glyphs, so the vector's fills are the point.
 */
@Composable
private fun ModeIcon(resId: Int) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = Modifier.size(84.dp),
    )
}
