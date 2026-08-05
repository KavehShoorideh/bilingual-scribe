package dev.bscribe.app.ui.timeline

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bscribe.app.util.formatClock
import dev.bscribe.core.asr.TimelineLayout
import kotlin.math.roundToInt

/**
 * The two readings of the same audio, side by side on a shared clock.
 *
 * Both lanes are positioned by *time*, not by text order, so a word sits at
 * the same place on screen as the moment it was spoken — which is the only way
 * two scripts running in opposite directions can be compared at all.
 *
 * The Farsi lane is mirrored: time runs right-to-left, so Farsi reads
 * naturally while still lining up with English above it. The lanes visibly
 * travel in opposite directions, which is the honest consequence of the two
 * languages disagreeing about which way a sentence goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(sessionId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TimelineViewModel = viewModel(factory = TimelineViewModel.factory(context, sessionId))

    val lanes by vm.lanes.collectAsState()
    val positionMs by vm.positionMs.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val durationMs by vm.durationMs.collectAsState()
    val lastChoice by vm.lastChoice.collectAsState()

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(lastChoice) {
        lastChoice?.let {
            snackbars.showSnackbar(it)
            vm.clearLastChoice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Both readings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "What each language made of the same audio. A lane is empty where " +
                    "that language was not what was spoken, and both can agree — " +
                    "whisper often transcribes English as English even when asked " +
                    "for Farsi. Tap a line that got it right; your answer is kept " +
                    "as a label.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )

            val measurer = rememberTextMeasurer()
            val style = MaterialTheme.typography.bodyLarge
            val density = LocalDensity.current
            val chipPaddingPx = with(density) { 12.dp.toPx() }

            // One layout for both lanes. Laying each out on its own would let
            // the denser lane drift right of the other, and the marker would
            // then sit on two different moments — which is precisely the
            // comparison this screen exists to make.
            val layout = remember(lanes, style) {
                fun items(ws: List<TimelineWord>) = ws.map { w ->
                    TimelineLayout.Item(
                        w.t0Ms,
                        w.t1Ms,
                        measurer.measure(w.text, style).size.width + chipPaddingPx,
                    )
                }
                TimelineLayout.place(
                    listOf(items(lanes.english), items(lanes.farsi)),
                    PX_PER_MS,
                )
            }
            val playhead = layout.mapping.xOf(positionMs)

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    // Drag anywhere on the timeline to scrub.
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures { _, drag ->
                            vm.scrubBy((-drag / PX_PER_MS).toLong())
                        }
                    },
            ) {
                Lane(
                    words = lanes.english,
                    placed = layout.lanes[0],
                    playheadX = playhead,
                    mirrored = false,
                    label = "English",
                    style = style,
                    onTap = { w -> vm.chooseLanguage("en", w.t0Ms, w.t1Ms) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                Lane(
                    words = lanes.farsi,
                    placed = layout.lanes[1],
                    playheadX = playhead,
                    mirrored = true,
                    label = "فارسی",
                    style = style,
                    onTap = { w -> vm.chooseLanguage("fa", w.t0Ms, w.t1Ms) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                // The fixed "now" line everything passes under.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .width(2.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = vm::togglePlayback) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                Text(
                    "  ${formatClock(positionMs)} / ${formatClock(durationMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * One language's words laid out along a time axis.
 *
 * Only words near the playhead are composed — a long recording has thousands,
 * and placing them all would cost far more than it shows.
 */
@Composable
private fun Lane(
    words: List<TimelineWord>,
    placed: List<TimelineLayout.Placed>,
    playheadX: Float,
    mirrored: Boolean,
    label: String,
    style: androidx.compose.ui.text.TextStyle,
    onTap: (TimelineWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val halfWidthPx = with(density) { LANE_HALF_WIDTH_DP.dp.toPx() }

    Box(modifier.fillMaxWidth().height(120.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp),
        )

        placed.forEachIndexed { i, p ->
            val relative = p.xPx - playheadX
            // Skip anything off-screen: a long recording has thousands of
            // words and composing them all would cost far more than it shows.
            if (relative + p.widthPx < -halfWidthPx || relative > halfWidthPx) return@forEachIndexed

            val word = words.getOrNull(i) ?: return@forEachIndexed
            // Mirroring is what lets Farsi read right-to-left while still
            // lining up in time with the English above it. The width is
            // subtracted so the word's trailing edge meets the playhead.
            val dx = if (mirrored) -(relative + p.widthPx) else relative

            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(dx.roundToInt(), 0) }
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (word.chosen) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onTap(word) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    word.text,
                    style = style,
                    maxLines = 1,
                    fontWeight = if (word.chosen) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (word.chosen) 1f else 0.6f,
                    ),
                )
            }
        }
    }
}

/** Horizontal pixels per millisecond of audio. 60 px per second reads well. */
private const val PX_PER_MS = 0.09f
private const val LANE_HALF_WIDTH_DP = 400
