@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/*
 * Głośność Androida (strumień muzyki) w górnym pasku: ikonka z liczbą jak w panelu radia,
 * dotknięcie otwiera suwak z przyciskami −/+. Zmiany z kierownicy/pokrętła widać od razu
 * (rozgłoszenie VOLUME_CHANGED_ACTION, awaryjnie odczyt co 3 s).
 */
private const val STREAM = AudioManager.STREAM_MUSIC

@Composable
fun VolumeControl() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val max = remember { runCatching { am.getStreamMaxVolume(STREAM) }.getOrDefault(15).coerceAtLeast(1) }
    var vol by remember { mutableIntStateOf(runCatching { am.getStreamVolume(STREAM) }.getOrDefault(0)) }
    var muted by remember { mutableStateOf(runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)) }
    var open by remember { mutableStateOf(false) }
    fun read() {
        vol = runCatching { am.getStreamVolume(STREAM) }.getOrDefault(vol)
        muted = runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read()
        }
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction("android.media.STREAM_MUTE_CHANGED_ACTION") }
        runCatching { ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) }
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(3000); read() }
    }
    fun set(v: Int) {
        val n = v.coerceIn(0, max)
        runCatching {
            if (muted && n > 0) am.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0)
            am.setStreamVolume(STREAM, n, 0)
        }
        read()
    }
    val icon = when {
        muted || vol == 0 -> Icons.Rounded.VolumeOff
        vol < max / 3 -> Icons.Rounded.VolumeMute
        vol < max * 2 / 3 -> Icons.Rounded.VolumeDown
        else -> Icons.Rounded.VolumeUp
    }

    val popupY = with(LocalDensity.current) { d(64).roundToPx() }
    Box {
        Row(
            Modifier.kokpitSurface(d(14), active = open)
                .combinedClickable(onClick = { open = !open })
                .padding(horizontal = d(14), vertical = d(8)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (muted) Warn else Txt, modifier = Modifier.size(d(28)))
            Spacer(Modifier.width(d(8)))
            Text(if (muted) "wycisz." else "$vol", color = Txt, fontSize = s(22), fontWeight = FontWeight.Medium)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, popupY),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Row(
                    Modifier.background(CardBg, RoundedCornerShape(d(20)))
                        .border(androidx.compose.ui.unit.Dp(1f), Line, RoundedCornerShape(d(20)))
                        .padding(horizontal = d(18), vertical = d(14)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d(14)),
                ) {
                    VolButton(if (muted) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff) {
                        runCatching { am.adjustStreamVolume(STREAM, if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0) }
                        read()
                    }
                    VolButton(Icons.Rounded.Remove) { set(vol - 1) }
                    Slider(
                        value = vol.toFloat(),
                        onValueChange = { set(Math.round(it)) },
                        valueRange = 0f..max.toFloat(),
                        modifier = Modifier.width(d(520)),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line),
                    )
                    VolButton(Icons.Rounded.Add) { set(vol + 1) }
                    Text("$vol / $max", color = Dim, fontSize = s(22), modifier = Modifier.width(d(110)))
                }
            }
        }
    }
}

@Composable
private fun VolButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.kokpitSurface(d(14)).combinedClickable(onClick = onClick).padding(d(12)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Txt, modifier = Modifier.size(d(32)))
    }
}
