package com.bandsync.app.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bandsync.app.ui.theme.BeatColor
import com.bandsync.app.ui.theme.DownbeatColor
import com.bandsync.core.metronome.BeatTick
import com.bandsync.core.metronome.Playhead
import com.bandsync.core.metronome.TransportState
import com.bandsync.core.metronome.playheadAt
import kotlin.math.exp
import kotlinx.coroutines.flow.StateFlow

private const val FLASH_DECAY_NS = 110_000_000.0

/** Below this nobody can see it, so a faded flash stops causing redraws. */
private const val MIN_VISIBLE_FLASH = 0.01f

/**
 * Works out the playhead once per display frame from the shared timeline. Equal values cause no
 * recomposition, so this is cheap while the readout isn't changing.
 */
@Composable
fun rememberPlayhead(transport: TransportState, masterNow: () -> Long): State<Playhead> {
    val playhead = remember { mutableStateOf<Playhead>(Playhead.Stopped) }
    val currentMasterNow by rememberUpdatedState(masterNow)
    LaunchedEffect(transport) {
        if (transport == TransportState.Stopped) {
            playhead.value = Playhead.Stopped
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { playhead.value = playheadAt(transport, currentMasterNow()) }
        }
    }
    return playhead
}

/**
 * Brightness is measured from when the beat was due, not from when this code heard about it, so a slow
 * frame can't push the flash back. Drawing reads the state directly, which skips recomposition.
 */
@Composable
fun BeatFlash(ticks: StateFlow<BeatTick?>, localNow: () -> Long, modifier: Modifier = Modifier) {
    var brightness by remember { mutableFloatStateOf(0f) }
    var color by remember { mutableStateOf(BeatColor) }
    val currentLocalNow by rememberUpdatedState(localNow)
    LaunchedEffect(ticks) {
        while (true) {
            withFrameNanos {
                val tick = ticks.value
                val level = tick?.let { exp(-(currentLocalNow() - it.localNs) / FLASH_DECAY_NS).toFloat() } ?: 0f
                brightness = if (level < MIN_VISIBLE_FLASH) 0f else level
                color = if (tick?.beatInBar == 1) DownbeatColor else BeatColor
            }
        }
    }
    Box(modifier.drawBehind { drawRect(color, alpha = brightness.coerceIn(0f, 1f)) })
}

@Composable
fun LiveReadout(
    playhead: Playhead,
    ticks: StateFlow<BeatTick?>,
    localNow: () -> Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        BeatFlash(ticks, localNow, Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("BAR", style = MaterialTheme.typography.labelMedium)
                Text(barText(playhead), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            BeatDots(playhead)
            Column(horizontalAlignment = Alignment.End) {
                Text(statusText(playhead), style = MaterialTheme.typography.labelMedium)
                Text(timeText(playhead), fontSize = 28.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BeatDots(playhead: Playhead) {
    val (beat, beatsPerBar) = when (playhead) {
        is Playhead.Playing -> playhead.beat to playhead.beatsPerBar
        is Playhead.Paused -> playhead.beat to playhead.beatsPerBar
        is Playhead.CountIn -> playhead.count to playhead.beatsPerBar
        else -> return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (dot in 1..beatsPerBar) {
            val color = when {
                dot != beat -> Color.White.copy(alpha = 0.25f)
                dot == 1 -> DownbeatColor
                else -> BeatColor
            }
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        }
    }
}

/** The big number over the sheet, as the band gets ready to come in. */
@Composable
fun CountdownOverlay(playhead: Playhead, modifier: Modifier = Modifier) {
    val text = when (playhead) {
        is Playhead.Countdown -> "%.1f".format(playhead.tenthsLeft / 10.0)
        is Playhead.CountIn -> playhead.count.toString()
        else -> return
    }
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                if (playhead is Playhead.Countdown) "Get ready" else "Count-in",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
    }
}

private fun barText(playhead: Playhead): String = when (playhead) {
    is Playhead.Playing -> playhead.bar.toString()
    is Playhead.Paused -> playhead.bar.toString()
    else -> "–"
}

private fun statusText(playhead: Playhead): String = when (playhead) {
    Playhead.Stopped -> "STOPPED"
    is Playhead.Countdown -> "STARTING"
    is Playhead.CountIn -> "COUNT-IN · ${playhead.bpm} BPM"
    is Playhead.Playing -> "PLAYING · ${playhead.bpm} BPM"
    is Playhead.Paused -> "PAUSED · ${playhead.bpm} BPM"
}

private fun timeText(playhead: Playhead): String {
    val seconds = when (playhead) {
        is Playhead.Playing -> playhead.seconds
        is Playhead.Paused -> playhead.seconds
        else -> 0
    }
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
