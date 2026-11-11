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
 * Głośność w górnym pasku: ikonka z liczbą jak w panelu radia, dotknięcie otwiera suwak z przyciskami −/+.
 * W radiu Witstek słyszalną głośność ustawia wzmacniacz przez moduł MCU (0–40, ustawienie
 * „w19_mcu:040400”), a głośność Androida stoi na maksimum – dlatego sterujemy MCU: CenterService
 * przyjmuje rozgłoszenie com.can.ACTION_KEY_CODE z key_code 13 (głośniej) / 12 (ciszej), tak jak
 * przyciski radia. Poza radiem (emulator) – zwykła głośność muzyki Androida.
 */
private const val STREAM = AudioManager.STREAM_MUSIC

object CarVolume {
    const val KEY = "w19_mcu:040400"
    const val MAX = 40

    /** Głośność wzmacniacza radia albo null, gdy to nie radio Witstek. */
    fun mcu(ctx: Context): Int? {
        val direct = runCatching { android.provider.Settings.System.getString(ctx.contentResolver, KEY)?.toIntOrNull() }
        direct.getOrNull()?.let { return it }
        // Android 12+ może nie wpuścić odczytu niestandardowego klucza – próba przez zapytanie do tabeli
        val q = runCatching {
            ctx.contentResolver.query(android.provider.Settings.System.CONTENT_URI, arrayOf("name", "value"), "name=?", arrayOf(KEY), null)?.use { c ->
                if (c.moveToFirst()) c.getString(1)?.toIntOrNull() else null
            }
        }
        android.util.Log.i("KokpitVolume", "odczyt $KEY: ${direct.exceptionOrNull()?.message ?: "null"} / zapytanie: ${q.getOrNull() ?: q.exceptionOrNull()?.message}")
        return q.getOrNull()
    }

    /** Jeden stopień w górę albo w dół (jak przycisk na radiu). */
    fun step(ctx: Context, up: Boolean) {
        ctx.sendBroadcast(Intent("com.can.ACTION_KEY_CODE").putExtra("key_code", if (up) 13 else 12))
    }

    /** Ustawia [target] (0–40) wysyłając odpowiednią liczbę kroków od [from]. */
    fun set(ctx: Context, from: Int, target: Int) {
        val t = target.coerceIn(0, MAX)
        repeat(kotlin.math.abs(t - from)) { step(ctx, t > from) }
    }
}

@Composable
fun VolumeControl() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    // radio Witstek: wzmacniacz przez MCU; inaczej głośność Androida
    val mcu = remember { CarVolume.mcu(ctx) != null }
    val max = remember { if (mcu) CarVolume.MAX else runCatching { am.getStreamMaxVolume(STREAM) }.getOrDefault(15).coerceAtLeast(1) }
    fun current() = if (mcu) CarVolume.mcu(ctx) ?: 0 else runCatching { am.getStreamVolume(STREAM) }.getOrDefault(0)
    var vol by remember { mutableIntStateOf(current()) }
    var muted by remember { mutableStateOf(!mcu && runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)) }
    var open by remember { mutableStateOf(false) }
    // ostatnio zamówiona głośność MCU (kroki idą asynchronicznie – liczymy od niej, nie od odczytu)
    var requested by remember { mutableIntStateOf(vol) }
    var requestedAt by remember { mutableStateOf(0L) }
    fun read() {
        vol = current()
        if (System.currentTimeMillis() - requestedAt > 1500) requested = vol
        muted = !mcu && runCatching { am.isStreamMute(STREAM) }.getOrDefault(false)
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read()
        }
        val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction("android.media.STREAM_MUTE_CHANGED_ACTION") }
        runCatching { ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) }
        val obs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = read()
        }
        if (mcu) runCatching { ctx.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(CarVolume.KEY), false, obs) }
        onDispose {
            runCatching { ctx.unregisterReceiver(r) }
            runCatching { ctx.contentResolver.unregisterContentObserver(obs) }
        }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(3000); read() }
    }
    fun set(v: Int) {
        val n = v.coerceIn(0, max)
        if (mcu) {
            if (n == requested) return
            CarVolume.set(ctx, requested, n)
            requested = n
            requestedAt = System.currentTimeMillis()
            vol = n
            return
        }
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
                    if (!mcu) VolButton(if (muted) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff) {
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
