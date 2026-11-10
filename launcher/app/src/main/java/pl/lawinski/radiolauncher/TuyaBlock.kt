@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/*
 * Blok „Urządzenie Tuya” (można dodać kilka): dowolne urządzenie z projektu Tuya wybrane w bloku.
 * Przyciski dobierane z funkcji urządzenia: włączniki, jasność (−/+), tryb (lista); odczyty:
 * temperatura, wilgotność, moc, bateria. Po poleceniu przyciski są zablokowane, aż urządzenie
 * potwierdzi wykonanie (Tuya.command czeka na zmianę stanu).
 */

/** Włączniki: najpierw główny. */
private val SWITCH_ORDER = listOf("switch_led", "switch", "switch_1", "switch_2", "switch_3", "switch_4", "switch_usb1")

/** Jasność i podobne liczby sterowane −/+ (pierwsza pasująca). */
private val LEVEL_CODES = listOf("bright_value_v2", "bright_value", "bright_value_1", "temp_set", "percent_control")

private val MODE_CODES = listOf("work_mode", "mode")

private val TEMP_CODES = listOf("va_temperature", "temp_current", "temp_value", "temperature")
private val HUM_CODES = listOf("va_humidity", "humidity_value", "humidity_current", "humidity")
private val POWER_CODES = listOf("cur_power")

private fun Map<String, JSONObject>.scale(code: String) = this[code]?.optInt("scale", 0) ?: 0

private fun num(st: Tuya.DeviceState, code: String): Float? {
    val v = (st.status[code] as? Number)?.toFloat() ?: return null
    val sc = st.specs.scale(code).takeIf { st.specs.containsKey(code) }
        ?: if (code.contains("temp") && v > 60) 1 else 0
    return v / Math.pow(10.0, sc.toDouble()).toFloat()
}

private fun fmt(v: Float, decimals: Int) = String.format(Locale("pl", "PL"), "%.${decimals}f", v)

private fun categoryIcon(cat: String): ImageVector = when (cat) {
    "dj", "dd", "xdd", "fwd", "dc", "tgq", "tgkg" -> Icons.Outlined.Lightbulb
    "cz", "pc", "kg", "tdq" -> Icons.Outlined.Power
    "kt", "ktkzq" -> Icons.Outlined.AcUnit
    "wsdcg", "wnykq", "co2bj", "hjjcy" -> Icons.Outlined.Thermostat
    "pir", "mcs", "ywbj", "rqbj", "sj" -> Icons.Outlined.Sensors
    else -> Icons.Outlined.Devices
}

@Composable
fun TuyaDeviceBlock(modifier: Modifier, slot: Slot, act: MainActivity, onChoose: () -> Unit, onSettings: () -> Unit) {
    val id = act.tuyaBlockDevices[slot.n] ?: ""
    val visible by act.visible
    val scope = rememberCoroutineScope()
    var st by remember(id) { mutableStateOf<Tuya.DeviceState?>(null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    var busy by remember(id) { mutableStateOf(false) }
    var picker by remember(id) { mutableStateOf<Tuya.Function?>(null) }

    suspend fun reload() {
        try {
            st = Tuya.readDevice(id)
            err = null
        } catch (e: Exception) {
            err = Tuya.errorText(e)
        }
    }
    LaunchedEffect(id, visible) {
        while (visible && id.isNotBlank() && Tuya.configured) {
            if (!busy) reload()
            delay(60_000)
        }
    }
    fun send(code: String, value: Any) {
        if (busy) return
        busy = true
        scope.launch {
            err = Tuya.command(id, code, value)
            val e = err
            reload()
            if (e != null) err = e
            busy = false
        }
    }

    picker?.let { f ->
        val range = f.values.optJSONArray("range")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block)
                    .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28))).padding(d(28)),
            ) {
                Text("Tryb", color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(d(18)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    range.forEach { v ->
                        val on = st?.status?.get(f.code)?.toString() == v
                        Box(
                            Modifier.height(d(84)).width(d(150)).kokpitSurface(d(18), active = on)
                                .combinedClickable(onClick = { picker = null; send(f.code, v) }),
                            contentAlignment = Alignment.Center,
                        ) { Text(modeLabel(v), color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(20), textAlign = TextAlign.Center, maxLines = 2) }
                    }
                }
            }
        }
    }

    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(d(24))) {
            val s0 = st
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(categoryIcon(s0?.category.orEmpty()), s0?.name ?: "Urządzenie Tuya") }
                if (busy) CircularProgressIndicator(Modifier.size(d(26)), color = Accent, strokeWidth = d(3))
            }
            when {
                !Tuya.configured -> Column(Modifier.fillMaxSize().combinedClickable(onClick = onSettings), verticalArrangement = Arrangement.Center) {
                    Text("Połącz Tuya", color = Accent, fontSize = s(24))
                    Text("Ustawienia → Dom (Smart Life): klucze Tuya", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                }
                id.isBlank() -> Column(Modifier.fillMaxSize().combinedClickable(onClick = onChoose), verticalArrangement = Arrangement.Center) {
                    Text("Wybierz urządzenie", color = Accent, fontSize = s(24))
                    Text("Dotknij, aby wybrać z projektu Tuya", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                }
                s0 == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(err ?: "Łączenie z Tuya…", color = if (err != null) Warn else Dim, fontSize = s(20))
                }
                else -> DeviceControls(s0, slot, busy, err, onSend = ::send, onMode = { picker = it })
            }
        }
    }
}

@Composable
private fun DeviceControls(st: Tuya.DeviceState, slot: Slot, busy: Boolean, err: String?, onSend: (String, Any) -> Unit, onMode: (Tuya.Function) -> Unit) {
    val fn = st.functions.associateBy { it.code }
    val switches = SWITCH_ORDER.filter { fn[it]?.type == "Boolean" } +
        st.functions.filter { it.type == "Boolean" && it.code.startsWith("switch") && it.code !in SWITCH_ORDER }.map { it.code }
    val level = LEVEL_CODES.firstOrNull { fn[it]?.type == "Integer" }?.let { fn[it] }
    val mode = MODE_CODES.firstOrNull { fn[it]?.type == "Enum" }?.let { fn[it] }
    val temp = TEMP_CODES.firstOrNull { st.status[it] is Number }?.let { num(st, it) }
    val hum = HUM_CODES.firstOrNull { st.status[it] is Number }?.let { num(st, it) }
    val power = POWER_CODES.firstOrNull { st.status[it] is Number }?.let { num(st, it) }
    val battery = (st.status["battery_percentage"] as? Number)?.toInt()
    val mainOn = switches.firstOrNull()?.let { st.status[it] as? Boolean }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        // odczyt: temperatura (czujnik), inaczej stan włącznika
        Row(verticalAlignment = Alignment.Bottom) {
            val big = when {
                !st.online -> "offline"
                temp != null && switches.isEmpty() -> fmt(temp, 1) + "°"
                mainOn != null -> if (mainOn) "wł." else "wył."
                temp != null -> fmt(temp, 1) + "°"
                else -> "–"
            }
            Text(big, color = if (!st.online) Warn else Txt, fontSize = s(if (slot.h > 1) 80 else 54), fontWeight = FontWeight.Light, maxLines = 1)
            Column(Modifier.padding(start = d(14), bottom = d(10))) {
                hum?.let { Text("wilg. ${it.toInt()} %", color = Dim, fontSize = s(18)) }
                if (temp != null && switches.isNotEmpty()) Text("${fmt(temp, 1)}°", color = Dim, fontSize = s(18))
                power?.let { Text("${fmt(it, if (it < 10) 1 else 0)} W", color = Dim, fontSize = s(18)) }
                battery?.let { Text("bateria $it %", color = if (it < 15) Warn else Dim, fontSize = s(18)) }
                level?.let { f ->
                    val v = (st.status[f.code] as? Number)?.toFloat()
                    if (v != null) Text(levelText(f, v), color = Dim, fontSize = s(18))
                }
            }
        }
        if (mode != null) {
            val cur = st.status[mode.code]?.toString()
            Row(
                Modifier.padding(top = d(6)).kokpitSurface(d(14)).combinedClickable(onClick = { if (!busy) onMode(mode) })
                    .padding(horizontal = d(12), vertical = d(6)),
            ) { Text(modeLabel(cur ?: "–"), color = Txt, fontSize = s(18), maxLines = 1) }
        }
        Spacer(Modifier.weight(1f))
        // przyciski dzielą szerokość po równo; zablokowane do potwierdzenia polecenia
        val buttons = buildList<Triple<ImageVector?, String, () -> Unit>> {
            switches.take(if (level != null) 2 else 4).forEachIndexed { i, code ->
                val on = st.status[code] as? Boolean ?: false
                add(Triple(if (i == 0) Icons.Outlined.PowerSettingsNew else null, if (i == 0) "" else "${i + 1}") { onSend(code, !on) })
            }
            level?.let { f ->
                val v = (st.status[f.code] as? Number)?.toInt() ?: 0
                val min = f.values.optInt("min", 0)
                val max = f.values.optInt("max", 100)
                val step = if (f.code.startsWith("bright")) ((max - min) / 10).coerceAtLeast(1) else f.values.optInt("step", 1).coerceAtLeast(1)
                add(Triple(Icons.Outlined.Remove, "") { onSend(f.code, (v - step).coerceIn(min, max)) })
                add(Triple(Icons.Outlined.Add, "") { onSend(f.code, (v + step).coerceIn(min, max)) })
            }
        }
        if (buttons.isNotEmpty() && st.online) {
            val btn = if (slot.w == 1) d(50) else d(64)
            Row(Modifier.fillMaxWidth().alpha(if (busy) 0.45f else 1f), horizontalArrangement = Arrangement.spacedBy(d(8))) {
                buttons.forEachIndexed { i, (icon, label, action) ->
                    val active = i < switches.size && switches.getOrNull(i)?.let { st.status[it] as? Boolean } == true
                    Box(
                        Modifier.weight(1f).height(btn).kokpitSurface(d(16), active = active)
                            .combinedClickable(enabled = !busy, onClick = action),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tint = if (active) LocalTheme.current.activeText else Txt
                        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(d(30)))
                        else Text(label, color = tint, fontSize = s(24), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        err?.let { Text(it, color = Warn, fontSize = s(16), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = d(6))) }
    }
}

private fun levelText(f: Tuya.Function, v: Float): String {
    val min = f.values.optInt("min", 0)
    val max = f.values.optInt("max", 100)
    return if (f.code.startsWith("bright") || f.code == "percent_control") "${((v - min) * 100 / (max - min).coerceAtLeast(1)).toInt()} %"
    else {
        val sc = f.values.optInt("scale", 0)
        val unit = f.values.optString("unit", "")
        fmt(v / Math.pow(10.0, sc.toDouble()).toFloat(), sc) + unit
    }
}

private fun modeLabel(v: String) = when (v.lowercase()) {
    "white" -> "Biały"
    "colour", "color" -> "Kolor"
    "scene" -> "Scena"
    "music" -> "Muzyka"
    "cold" -> "Chłodzenie"
    "hot" -> "Grzanie"
    "wet" -> "Osuszanie"
    "wind" -> "Wentylator"
    "auto" -> "Auto"
    else -> v
}

/** Wybór urządzenia Tuya do bloku nr [n]. */
@Composable
fun TuyaDevicePickerDialog(act: MainActivity, n: Int, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<Tuya.Device>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { list = Tuya.devices() } catch (e: Exception) { err = Tuya.errorText(e) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(d(1500)).clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block)
                .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28))).padding(d(28)),
        ) {
            Text("Urządzenie Tuya", color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(d(18)))
            val l = list
            when {
                err != null -> Text(err!!, color = Warn, fontSize = s(22))
                l == null -> Text("Wczytywanie…", color = Dim, fontSize = s(22))
                l.isEmpty() -> Text("W projekcie Tuya nie ma urządzeń", color = Dim, fontSize = s(22))
                else -> LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(d(12)), verticalArrangement = Arrangement.spacedBy(d(12))) {
                    items(l) { dev ->
                        val on = act.tuyaBlockDevices[n] == dev.id
                        Row(
                            Modifier.height(d(84)).kokpitSurface(d(18), active = on)
                                .combinedClickable(onClick = { act.setTuyaBlockDevice(n, dev.id); onClose() })
                                .padding(horizontal = d(18)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(categoryIcon(dev.category), null, tint = if (on) LocalTheme.current.activeText else Accent, modifier = Modifier.size(d(30)))
                            Spacer(Modifier.width(d(12)))
                            Text(dev.name, color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(20), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
