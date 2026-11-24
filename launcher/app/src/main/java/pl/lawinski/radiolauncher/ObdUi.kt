package pl.lawinski.radiolauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.min
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** numer grupy „Auto” w Ustawieniach (tam jest przełącznik OBD) */
const val OBD_SETTINGS_GROUP = 6

/** koniec skali obrotomierza i początek czerwonego pola (silnik Diesla) */
private const val RPM_MAX = 5000f
private const val RPM_RED = 4300f
private val Cold = Color(0xFF6FB6FF)

private fun pl(v: Float, digits: Int = 1) = String.format(Locale("pl", "PL"), "%.${digits}f", v)

/** Nagłówek klocka OBD i komunikat, gdy danych nie ma. Zwraca true, gdy są dane do pokazania. */
@Composable
private fun obdReady(s: Obd.State, onSettings: () -> Unit): Boolean {
    if (s.connected) return true
    Column(Modifier.fillMaxSize().clickable(onClick = onSettings), verticalArrangement = Arrangement.Center) {
        if (!Obd.enabled) {
            Text("Włącz odczyt OBD", color = Accent, fontSize = s(24))
            Text("Ustawienia → Auto → Dane silnika (OBD)", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
        } else {
            Text(s.note.ifEmpty { "Łączę z modułem OBD…" }, color = Dim, fontSize = s(22), lineHeight = s(28))
        }
    }
    return false
}

@Composable
fun EngineBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSettings: () -> Unit) {
    val s by Obd.state
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(start = d(24), end = d(24), top = d(24), bottom = d(16))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(d(44))) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.Speed, "Silnik") }
                if (s.connected && s.cold && s.running) Text("ZIMNY", color = Cold, fontSize = s(17), fontWeight = FontWeight.Medium, letterSpacing = s(2))
            }
            if (!obdReady(s, onSettings)) return@Column
            EngineBody(s, slot.w >= 2, slot.h >= 2)
        }
    }
}

/** Dane silnika w bloku auta: prawa część bloku, układ jak w klocku „Silnik” (z pełną listą usterek). */
@Composable
fun CarEnginePanel(modifier: Modifier) {
    val s by Obd.state
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= d(560)
        val tall = maxHeight >= d(500)
        Column(Modifier.fillMaxSize().padding(start = d(22), end = d(24), top = d(24), bottom = d(16))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(d(44))) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.Speed, "Silnik") }
                if (s.cold && s.running) Text("ZIMNY", color = Cold, fontSize = s(17), fontWeight = FontWeight.Medium, letterSpacing = s(2))
            }
            if (wide || !tall) EngineBody(s, wide, tall)
            else {
                // wąski, wysoki panel: obrotomierz, wszystkie odczyty, a resztę miejsca dostaje lista usterek
                val faults = s.dtcs.map { it to false } + s.pending.map { it to true }
                // łuk zegara jest u dołu otwarty – odczyty wchodzą w to wolne miejsce
                val side = if (faults.isEmpty()) 250 else 170
                Box(Modifier.fillMaxWidth().height(d(side * 4 / 5))) {
                    Tacho(s, Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(d(side)))
                }
                Readings(s, Modifier.fillMaxWidth().padding(top = d(4)), columns = 2, big = faults.isEmpty())
                if (faults.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Faults(s)
                } else FaultList(faults, false, Modifier.weight(1f).fillMaxWidth().padding(top = d(12)))
            }
        }
    }
}

/** Obrotomierz, odczyty i usterki – wspólne dla klocka „Silnik” i bloku auta. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EngineBody(s: Obd.State, wide: Boolean, tall: Boolean) {
            val faults = s.dtcs.map { it to false } + s.pending.map { it to true }
            Box(Modifier.weight(if (faults.isEmpty() || tall) 1f else 0.85f).fillMaxWidth()) {
                when {
                    wide -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Tacho(s, Modifier.weight(1f).fillMaxSize())
                        Spacer(Modifier.width(d(20)))
                        Readings(s, Modifier.weight(1f), columns = if (tall && faults.isEmpty()) 1 else 2, big = tall && faults.isEmpty())
                    }
                    tall -> Column(Modifier.fillMaxSize()) {
                        Tacho(s, Modifier.weight(1.2f).fillMaxWidth())
                        Readings(s, Modifier.weight(1f).fillMaxWidth().padding(top = d(8)), columns = 2, big = false, max = 4)
                    }
                    else -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Tacho(s, Modifier.weight(1.15f).fillMaxSize())
                        Spacer(Modifier.width(d(10)))
                        Readings(s, Modifier.weight(1f), columns = 1, big = false, max = 2)
                    }
                }
            }
            if (faults.isEmpty()) Faults(s)
            else FaultList(faults, wide, Modifier.weight(if (tall) 0.8f else 1f).fillMaxWidth().padding(top = d(6)))
}

/** Lista usterek: kod, opis po polsku, zapisana / oczekująca; przewijana, gdy jest ich więcej. */
@Composable
private fun FaultList(faults: List<Pair<String, Boolean>>, wide: Boolean, modifier: Modifier) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(d(6))) {
        items(faults.size) { i ->
            val (code, pending) = faults[i]
            val kind = if (pending) "oczekująca" else "zapisana"
            Row(Modifier.fillMaxWidth().kokpitSurface(d(14)).padding(horizontal = d(12), vertical = d(8)), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = if (pending) Dim else Warn, modifier = Modifier.size(d(if (wide) 30 else 24)))
                Spacer(Modifier.width(d(10)))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(code, color = if (pending) Txt else Warn, fontSize = s(20), lineHeight = s(24), fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("  $kind", color = Dim, fontSize = s(15), maxLines = 1)
                    }
                    Text(ObdCodes.describe(code), color = Txt, fontSize = s(if (wide) 19 else 16), lineHeight = s(if (wide) 23 else 20), maxLines = if (wide) 2 else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Obrotomierz: łuk 240° z czerwonym polem, obroty w środku. */
@Composable
private fun Tacho(s: Obd.State, modifier: Modifier) = Gauge(s.rpm?.let { it / 10 * 10 }, RPM_MAX, RPM_RED, 5, "obr./min", modifier)

/** Prędkościomierz tarczowy do bloku auta (skala do 240 km/h, kreski co 40). */
@Composable
fun SpeedDial(speed: Int?, modifier: Modifier) = Gauge(speed, 240f, null, 6, "km/h", modifier)

/** Zegar: łuk 240° (z czerwonym polem od [red]), wartość w środku. */
@Composable
private fun Gauge(value: Int?, max: Float, red: Float?, ticks: Int, unit: String, modifier: Modifier) {
    val rpm by animateFloatAsState((value ?: 0).toFloat().coerceIn(0f, max), tween(350), label = "gauge")
    val accent = Accent
    val track = Line
    val numbers = LocalTheme.current.numbers
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth, maxHeight)
        Canvas(Modifier.size(side)) {
            val w = size.minDimension * 0.085f
            val inset = w / 2 + 1f
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val at = Offset(inset, inset)
            val start = 150f
            val sweep = 240f
            drawArc(track.copy(alpha = (track.alpha * 3f).coerceAtMost(0.35f)), start, sweep, false, at, arc, style = Stroke(w, cap = StrokeCap.Round))
            if (red != null) {
                val from = sweep * (red / max)
                drawArc(Warn.copy(alpha = 0.55f), start + from, sweep - from, false, at, arc, style = Stroke(w, cap = StrokeCap.Round))
            }
            if (rpm > 20f) drawArc(
                Brush.sweepGradient(0f to accent.copy(alpha = 0.55f), 0.66f to accent, 1f to accent.copy(alpha = 0.55f), center = center),
                start, sweep * rpm / max, false, at, arc, style = Stroke(w, cap = StrokeCap.Round),
            )
            // kreski co 1000 obr./min
            val r = size.minDimension / 2
            for (k in 0..ticks) {
                val a = Math.toRadians((start + sweep * k / ticks.toFloat()).toDouble())
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                drawLine(track.copy(alpha = 0.5f), center + dir * (r - w * 1.9f), center + dir * (r - w * 1.25f), strokeWidth = 2f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (value == null) "–" else "$value", color = if (red != null && (value ?: 0) > red) Warn else Txt,
                fontSize = (side.value * 0.22f).let { s(it / LocalU.current.value) }, fontWeight = FontWeight.Medium, fontFamily = numbers, maxLines = 1,
            )
            Text(unit, color = Dim, fontSize = (side.value * 0.085f).let { s(it / LocalU.current.value) }, maxLines = 1)
        }
    }
}

private class Reading(val label: String, val value: String, val color: Color? = null)

@Composable
private fun Readings(s: Obd.State, modifier: Modifier, columns: Int, big: Boolean, max: Int = if (columns == 2) 6 else 4) {
    val list = readings(s).take(max)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d(if (big) 14 else 6), Alignment.CenterVertically)) {
        for (row in list.chunked(columns)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(12))) {
            for (r in row) Column(Modifier.weight(1f)) {
                Text(r.label.uppercase(), color = Dim, fontSize = s(if (big) 16 else 14), letterSpacing = s(1.5), maxLines = 1)
                Text(r.value, color = r.color ?: Txt, fontSize = s(if (big) 34 else 25), lineHeight = s(if (big) 38 else 28), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Clip)
            }
            if (row.size < columns) Spacer(Modifier.weight(1f))
        }
    }
}

private fun readings(s: Obd.State): List<Reading> = buildList {
        if (Prefs.engineSpec.isNotEmpty()) add(Reading("Moc", s.powerKm?.let { "$it KM" } ?: "–"))
        add(Reading("Płyn", s.coolant?.let { "$it°" } ?: "–", when {
            s.coolant == null -> null
            s.coolant > 108 -> Warn
            s.cold -> Cold
            else -> null
        }))
        add(Reading("Turbo", s.boost?.let { pl(it, 2) + " bar" } ?: "–"))
        add(Reading("Napięcie", s.voltage?.let { pl(it) + " V" } ?: "–", if (s.voltage != null && s.running && s.voltage < 12.8f) Warn else null))
        add(Reading("Spaliny", s.egt?.let { "$it°" } ?: "–", if ((s.egt ?: 0) > 550) Warn else null))
}

/**
 * Dane silnika w bloku auta (gdy moduł OBD jest połączony): obroty z paskiem pod prędkością,
 * odczyty w dolnym wierszu (tyle, ile mieści się obok przycisków), usterki pod paliwem.
 */
@Composable
fun androidx.compose.foundation.layout.BoxScope.CarEngine() {
    val s by Obd.state
    if (!s.connected) return
    val rpm by animateFloatAsState((s.rpm ?: 0).toFloat().coerceIn(0f, RPM_MAX), tween(350), label = "carRpm")
    val accent = Accent
    val track = Line
    Column(Modifier.padding(start = d(36), top = d(146)).width(d(300))) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.rpm == null) "–" else "${(s.rpm ?: 0) / 10 * 10}", color = if ((s.rpm ?: 0) > RPM_RED) Warn else Txt,
                fontSize = s(34), lineHeight = s(38), fontWeight = FontWeight.Medium, fontFamily = LocalTheme.current.numbers, maxLines = 1)
            Text(" obr./min", color = Dim, fontSize = s(18), modifier = Modifier.padding(bottom = d(5)), maxLines = 1)
            if (s.cold && s.running) Text("   ZIMNY", color = Cold, fontSize = s(16), fontWeight = FontWeight.Medium, letterSpacing = s(2), modifier = Modifier.padding(bottom = d(6)), maxLines = 1)
        }
        Canvas(Modifier.padding(top = d(6)).fillMaxWidth().height(d(8))) {
            val h = size.height
            val round = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
            drawRoundRect(track.copy(alpha = (track.alpha * 3f).coerceAtMost(0.35f)), cornerRadius = round)
            val red = size.width * RPM_RED / RPM_MAX
            drawRoundRect(Warn.copy(alpha = 0.55f), Offset(red, 0f), Size(size.width - red, h), round)
            if (rpm > 20f) drawRoundRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent)), size = Size(size.width * rpm / RPM_MAX, h), cornerRadius = round)
        }
    }
    val faults = s.dtcs + s.pending
    if (faults.isNotEmpty() || s.mil) Column(
        Modifier.align(Alignment.TopEnd).padding(top = d(118), end = d(34)).fillMaxWidth(0.44f),
        horizontalAlignment = Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Warn, modifier = Modifier.size(d(26)))
            Spacer(Modifier.width(d(8)))
            Text(if (faults.isEmpty()) "Kontrolka silnika" else faults.take(2).joinToString(", ") + if (faults.size > 2) " +${faults.size - 2}" else "",
                color = Warn, fontSize = s(22), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        faults.firstOrNull()?.let {
            Text(ObdCodes.describe(it), color = Dim, fontSize = s(16), lineHeight = s(20), maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        // dolny wiersz: obok są przyciski ekranu iDrive i podzielonego ekranu
        val cell = d(118)
        val all = readings(s)
        val beside = ((maxWidth - d(34) - d(if (Radio.witstek) 340 else 210)) / cell).toInt().coerceIn(0, all.size)
        // wąski blok: czego nie da się zmieścić obok przycisków, idzie do wiersza nad nimi
        val above = all.take(all.size - beside).take(((maxWidth - d(60)) / cell).toInt().coerceAtLeast(0))
        Column(Modifier.align(Alignment.BottomStart).padding(start = d(36), bottom = d(24)), verticalArrangement = Arrangement.spacedBy(d(10))) {
            for (line in listOf(above, all.drop(above.size).take(beside))) if (line.isNotEmpty()) Row {
                for (r in line) Column(Modifier.width(cell)) {
                    Text(r.label.uppercase(), color = Dim, fontSize = s(13), letterSpacing = s(1.5), maxLines = 1)
                    Text(r.value, color = r.color ?: Txt, fontSize = s(24), lineHeight = s(28), fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** Dolny wiersz klocka: usterki albo spokojne „brak”. */
@Composable
private fun Faults(s: Obd.State) {
    val all = s.dtcs + s.pending
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(d(30))) {
        when {
            all.isNotEmpty() || s.mil -> {
                Icon(Icons.Outlined.WarningAmber, null, tint = Warn, modifier = Modifier.size(d(24)))
                Spacer(Modifier.width(d(8)))
                val text = when {
                    all.isEmpty() -> "Kontrolka silnika"
                    all.size == 1 -> (if (s.dtcs.isEmpty()) "Oczekująca: " else "Usterka: ") + all[0]
                    else -> "Usterki: ${all.take(2).joinToString(", ")}" + if (all.size > 2) " +${all.size - 2}" else ""
                }
                Text(text, color = Warn, fontSize = s(19), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            !s.dtcRead -> Text("Sprawdzam usterki…", color = Dim, fontSize = s(18), maxLines = 1)
            else -> Text("Brak usterek", color = Dim, fontSize = s(18), maxLines = 1)
        }
    }
}

@Composable
fun TripBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSettings: () -> Unit) {
    val s by Obd.state
    val t by Obd.trip
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(start = d(24), end = d(24), top = d(24), bottom = d(16))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(d(44))) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.Timeline, "Jazda") }
            }
            if (!obdReady(s, onSettings)) return@Column
            val big = slot.h >= 2
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(pl(t.km), color = Txt, fontSize = s(if (big) 84 else 60), lineHeight = s(if (big) 88 else 62), fontWeight = FontWeight.Medium, fontFamily = LocalTheme.current.numbers, maxLines = 1)
                    Text(" km", color = Dim, fontSize = s(if (big) 30 else 24), modifier = Modifier.padding(bottom = d(if (big) 14 else 8)))
                }
                Spacer(Modifier.height(d(if (big) 22 else 8)))
                val total = t.movingSec + t.idleSec
                val items = listOf(
                    "Czas" to duration(total),
                    "Średnia" to (t.avgSpeed?.let { "$it km/h" } ?: "–"),
                    "Postój" to (if (total > 30) "${t.idleSec * 100 / total} %" else "–"),
                    "Silnik" to (s.runtime?.let { duration(it) } ?: "–"),
                )
                val perRow = if (slot.w >= 2) 4 else 2
                for (row in items.take(if (big || slot.w >= 2) 4 else 2).chunked(perRow)) Row(Modifier.fillMaxWidth().padding(top = d(6)), horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    for ((label, value) in row) Column(Modifier.weight(1f)) {
                        Text(label.uppercase(), color = Dim, fontSize = s(14), letterSpacing = s(1.5), maxLines = 1)
                        Text(value, color = Txt, fontSize = s(25), lineHeight = s(28), fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun duration(sec: Int): String = if (sec >= 3600) "${sec / 3600} h ${sec % 3600 / 60} min" else "${sec / 60} min"
