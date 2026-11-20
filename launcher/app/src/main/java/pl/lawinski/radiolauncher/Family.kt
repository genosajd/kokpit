@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package pl.lawinski.radiolauncher

import android.location.Location
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/*
 * Blok „Bliscy”: osoby udostępniające położenie w Mapach Google i urządzenia z Find Hub. Dane daje
 * serwer MCP dodany w Ustawieniach (narzędzie „locations”, pole structuredContent) – ten sam, z którego
 * korzysta asystent. Położenia są trzymane tylko w pamięci (nie trafiają do ustawień ani do eksportu).
 */
object Family {
    class Entry(
        val name: String, val device: Boolean, val phone: Boolean, val lat: Double?, val lon: Double?,
        val accuracy: Int?, val time: Long?, val where: String, val battery: Int?, val charging: Boolean, val error: String,
    )

    val entries = mutableStateOf<List<Entry>>(emptyList())
    val error = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
    /** kiedy ostatnio udało się pobrać (ms), 0 = jeszcze nie */
    var loadedAt = 0L
        private set

    private const val TOOL = "locations"

    /** Serwer MCP, który ma narzędzie z położeniami (pierwszy pasujący). */
    fun server(): Mcp.Server? {
        val name = Mcp.tools().firstOrNull { it.name == TOOL }?.server ?: return null
        return Mcp.servers().firstOrNull { it.name == name }
    }

    private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

    /** Pobiera położenia (kilka–kilkanaście sekund, gdy serwer pyta Find Hub). Poza wątkiem UI. */
    fun load(fresh: Boolean) {
        val srv = server() ?: return
        try {
            val result = Mcp.raw(srv, TOOL, JSONObject().put("fresh", fresh))
            val data = result.optJSONObject("structuredContent")
                ?: throw Exception("Serwer lokalizacji jest w starej wersji – trzeba go zaktualizować")
            val out = ArrayList<Entry>()
            val people = data.optJSONArray("people")
            for (i in 0 until (people?.length() ?: 0)) {
                val p = people!!.getJSONObject(i)
                if (p.optBoolean("me")) continue // właściciel siedzi w aucie
                out += Entry(
                    p.optString("name"), device = false, phone = false, lat = p.num("lat"), lon = p.num("lon"),
                    accuracy = p.num("accuracy")?.toInt(), time = p.num("time")?.toLong(),
                    where = if (p.isNull("address")) "" else p.optString("address"),
                    battery = p.num("battery")?.toInt(), charging = p.optBoolean("charging"), error = "",
                )
            }
            val devices = data.optJSONArray("devices")
            for (i in 0 until (devices?.length() ?: 0)) {
                val v = devices!!.getJSONObject(i)
                out += Entry(
                    v.optString("name"), device = true, phone = v.optString("kind") == "phone", lat = v.num("lat"), lon = v.num("lon"),
                    accuracy = v.num("accuracy")?.toInt(), time = v.num("time")?.toLong(),
                    where = v.optString("place"), battery = null, charging = false, error = v.optString("error"),
                )
            }
            val errors = data.optJSONArray("errors")
            entries.value = out
            error.value = if (errors != null && errors.length() > 0) (0 until errors.length()).joinToString(" · ") { errors.optString(it) } else null
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            error.value = e.message ?: "nie udało się pobrać położeń"
        }
    }

    fun ago(time: Long?, now: Long): String {
        if (time == null || time <= 0) return "czas nieznany"
        val sec = ((now / 1000) - time).coerceAtLeast(0)
        return when {
            sec < 90 -> "przed chwilą"
            sec < 3600 -> "${sec / 60} min temu"
            sec < 86400 -> "${sec / 3600} godz. temu"
            else -> "${sec / 86400} dni temu"
        }
    }

    /** „Marszałkowska 1, 00-001 Warszawa, Polska” → „Marszałkowska 1, Warszawa” */
    fun shortAddress(address: String): String {
        val parts = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return address.trim()
        val city = parts[1].replace(Regex("^\\d{2}-\\d{3}\\s+"), "")
        return parts[0] + ", " + city
    }

    fun distance(e: Entry, here: Location?): String? {
        if (here == null || e.lat == null || e.lon == null) return null
        val out = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, e.lat, e.lon, out)
        val m = out[0]
        return when {
            m < 950 -> "${(m / 50).toInt() * 50 + 50} m"
            m < 20000 -> String.format(Locale("pl", "PL"), "%.1f km", m / 1000)
            else -> "${(m / 1000).toInt()} km"
        }
    }
}

/** co ile pobierać położenia, gdy Kokpit jest widoczny */
private const val FAMILY_REFRESH_MS = 5 * 60_000L

@Composable
fun FamilyBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSettings: () -> Unit) {
    val visible by act.visible
    val mcp by Mcp.version
    val scope = rememberCoroutineScope()
    val entries by Family.entries
    val error by Family.error
    val busy by Family.busy
    val here by act.location.location.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val server = remember(mcp) { Family.server() }
    val pull = rememberPullToRefreshState()

    fun reload(fresh: Boolean) {
        if (Family.busy.value) return
        Family.busy.value = true
        scope.launch {
            withContext(Dispatchers.IO) { Family.load(fresh) }
            now = System.currentTimeMillis()
            Family.busy.value = false
        }
    }
    LaunchedEffect(visible, server?.url) {
        while (visible && server != null) {
            now = System.currentTimeMillis()
            if (now - Family.loadedAt > FAMILY_REFRESH_MS) reload(false)
            delay(60_000)
        }
    }

    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(start = d(24), end = d(24), top = d(24), bottom = d(14))) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(d(44))) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.People, "Bliscy") }
                if (busy) CircularProgressIndicator(Modifier.size(d(26)), color = Accent, strokeWidth = d(3))
                else if (server != null) Box(
                    Modifier.size(d(44)).kokpitSurface(d(12), fill = false).combinedClickable(onClick = { reload(true) }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Refresh, "Odśwież położenia", tint = Dim, modifier = Modifier.size(d(28))) }
            }
            when {
                server == null -> Column(Modifier.fillMaxSize().combinedClickable(onClick = onSettings), verticalArrangement = Arrangement.Center) {
                    Text("Dodaj serwer lokalizacji", color = Accent, fontSize = s(24))
                    Text("Ustawienia → Asystent AI → Dostępy → Serwery MCP", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                }
                // pociągnięcie listy w dół = odświeżenie (to samo co przycisk w nagłówku)
                else -> PullToRefreshBox(
                    isRefreshing = busy, onRefresh = { reload(true) }, state = pull, modifier = Modifier.fillMaxSize().padding(top = d(8)),
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pull, isRefreshing = busy, modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = CardBg, color = Accent,
                        )
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d(6))) {
                        if (entries.isEmpty()) item {
                            Text(
                                error ?: if (Family.loadedAt == 0L) "Pobieram położenia…" else "Nikt nie udostępnia położenia",
                                color = if (error != null) Warn else Dim, fontSize = s(20), modifier = Modifier.padding(top = d(40)),
                            )
                        } else {
                            items(entries.size) { i -> FamilyRow(entries[i], here, now, wide = slot.w > 1) { e -> navigateTo(act, "${e.lat},${e.lon}") } }
                            error?.let { msg -> item { Text(msg, color = Warn, fontSize = s(16), maxLines = 3, overflow = TextOverflow.Ellipsis) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyRow(e: Family.Entry, here: Location?, now: Long, wide: Boolean, onNavigate: (Family.Entry) -> Unit) {
    val located = e.lat != null && e.lon != null
    val where = when {
        e.error.isNotBlank() -> e.error
        e.where.isNotBlank() -> if (e.device) "miejsce „${e.where}”" else Family.shortAddress(e.where)
        located -> if (e.accuracy != null && e.accuracy > 0) "położenie ±${e.accuracy} m" else "położenie znane"
        else -> "położenie nieznane"
    }
    val old = e.time != null && now / 1000 - e.time > 3600
    val meta = buildList {
        Family.distance(e, here)?.let { add(it) }
        if (e.error.isBlank()) add(Family.ago(e.time, now))
        e.battery?.let { add("$it %" + if (e.charging) " ⚡" else "") }
    }.joinToString(" · ")

    // wąski klocek: bez znaczka z inicjałem i samo imię – inaczej na tekst zostaje za mało miejsca
    val name = if (wide || e.device) e.name else e.name.trim().substringBefore(' ')
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (wide) {
            Box(Modifier.size(d(52)).kokpitSurface(d(14)), contentAlignment = Alignment.Center) {
                if (e.device) Icon(if (e.phone) Icons.Outlined.PhoneAndroid else Icons.Outlined.LocationOn, null, tint = Dim, modifier = Modifier.size(d(28)))
                else Text(e.name.trim().take(1).uppercase(), color = Txt, fontSize = s(24), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(d(12)))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!wide && e.device) Icon(if (e.phone) Icons.Outlined.PhoneAndroid else Icons.Outlined.LocationOn, null, tint = Dim, modifier = Modifier.padding(end = d(6)).size(d(20)))
                Text(name, color = Txt, fontSize = s(21), lineHeight = s(25), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (wide && meta.isNotEmpty()) Text("  $meta", color = if (old) Warn else Dim, fontSize = s(16), maxLines = 1)
            }
            Text(where, color = if (e.error.isNotBlank()) Warn else Dim, fontSize = s(16), lineHeight = s(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!wide && meta.isNotEmpty()) Text(meta, color = if (old) Warn else Dim, fontSize = s(15), lineHeight = s(19), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (located) {
            Spacer(Modifier.width(d(8)))
            Box(
                Modifier.size(d(if (wide) 60 else 54)).kokpitSurface(d(16)).combinedClickable(onClick = { onNavigate(e) }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Navigation, "Prowadź do: ${e.name}", tint = Accent, modifier = Modifier.size(d(32))) }
        }
    }
}
