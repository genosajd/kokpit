package pl.lawinski.radiolauncher

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CallMade
import androidx.compose.material.icons.outlined.CallMissed
import androidx.compose.material.icons.outlined.CallReceived
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/*
 * Wygląd telefonu (koncept zatwierdzony 2026-11-14): blok dopasowany do rozmiaru (ulubione + ostatnie),
 * okno z trzema kolumnami (ulubione i ostatnie | wyszukiwarka i kontakty | klawiatura), okno rozmowy
 * ze szczegółami. Zasada: dotknięcie kontaktu nigdy nie dzwoni – dzwoni tylko zielona słuchawka.
 */

private val Green = Brush.linearGradient(listOf(Color(0xFF35C467), Color(0xFF178A41)))
private val Red = Brush.linearGradient(listOf(Color(0xFFF0555A), Color(0xFFB81F29)))
private val Off = Brush.linearGradient(listOf(Color(0xFF3A4350), Color(0xFF2A313B)))
private val GreenTxt = Color(0xFF5FE08A)
private val Missed = Color(0xFFFF8A90)

private val AVATARS = listOf(
    Color(0xFF4F7DFF) to Color(0xFF8B5CF6), Color(0xFF0EA5A4) to Color(0xFF2563EB),
    Color(0xFFD946EF) to Color(0xFF7C3AED), Color(0xFFC2410C) to Color(0xFF9A3412),
    Color(0xFF0D9488) to Color(0xFF115E59), Color(0xFFBE185D) to Color(0xFF831843),
)

private fun initials(name: String): String {
    val n = name.trim()
    if (n.isEmpty() || !n[0].isLetter()) return "?"
    val parts = n.split(' ').filter { it.isNotBlank() && it[0].isLetter() }
    return (parts.take(2).joinToString("") { it.take(1) }).uppercase()
}

/** „600100200” → „600 100 200”; inne numery bez zmian. */
private fun pretty(number: String): String {
    val d = number.filter { it.isDigit() }
    return when {
        number.startsWith("+48") && d.length == 11 -> "+48 " + d.drop(2).chunked(3).joinToString(" ")
        d.length == 9 && !number.startsWith("+") -> d.chunked(3).joinToString(" ")
        else -> number
    }
}

private fun fmtTimer(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)

private fun fmtDur(sec: Long) = if (sec < 60) "$sec s" else "${sec / 60} min"

private fun fmtWhen(t: Long): String {
    val pl = java.util.Locale("pl")
    val hm = java.text.SimpleDateFormat("H:mm", pl).format(java.util.Date(t))
    return when {
        android.text.format.DateUtils.isToday(t) -> "dziś $hm"
        android.text.format.DateUtils.isToday(t + 86_400_000L) -> "wczoraj $hm"
        else -> java.text.SimpleDateFormat("d MMM", pl).format(java.util.Date(t)) + ", $hm"
    }
}

/** Zdjęcie kontaktu z Google albo inicjały na kolorowym tle. */
@Composable
fun ContactAvatar(name: String, photo: String?, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(photo) { mutableStateOf(photo?.let { Phone.cachedPhoto(it) }) }
    LaunchedEffect(photo) {
        if (photo != null && bmp == null) bmp = withContext(Dispatchers.IO) { Phone.photo(ctx, photo) }
    }
    val (c1, c2) = if (initials(name) == "?") Color(0xFF475569) to Color(0xFF1E293B)
    else AVATARS[(name.hashCode() and 0x7fffffff) % AVATARS.size]
    Box(
        modifier.size(size).clip(LocalTheme.current.shape(size * 0.2f)).background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }, maxLines = 1)
    }
}

/** Zielona słuchawka (albo czerwona) – jedyny element, który dzwoni / rozłącza. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallBtn(modifier: Modifier, desc: String, text: String? = null, red: Boolean = false, enabled: Boolean = true, big: Boolean = false, onClick: () -> Unit) {
    val shape = LocalTheme.current.shape(d(12))
    // zielona słuchawka działa tylko przy telefonie połączonym z Bluetoothem radia
    val on = enabled && (red || Phone.connected.value)
    Row(
        modifier.clip(shape).background(if (!on) Off else if (red) Red else Green)
            .kokpitSurface(d(12), fill = false).combinedClickable(onClick = { if (on) onClick() }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(if (red) Icons.Filled.CallEnd else Icons.Filled.Call, desc, tint = Color.White, modifier = Modifier.size(d(if (big) 38 else 30)))
        if (text != null) {
            Spacer(Modifier.width(d(14)))
            Text(text, color = Color.White, fontSize = s(if (big) 27 else 23), fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Ciemniejsze pole wewnątrz okna / bloku. */
private fun Modifier.pane(r: Int = 13): Modifier = composed {
    val shape = LocalTheme.current.shape(d(r))
    clip(shape).background(if (LightMode.on) Color(0x0F000000) else Color(0x73050816)).border(1.dp, Line, shape)
}

@Composable
private fun Section(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Dim, modifier = Modifier.size(d(21)))
        Spacer(Modifier.width(d(9)))
        Text(text.uppercase(), color = Dim, fontSize = s(15), fontWeight = FontWeight.Bold, letterSpacing = s(2.5), maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlainBtn(modifier: Modifier, icon: ImageVector, text: String?, tint: Color = Txt, onClick: () -> Unit) {
    Row(
        modifier.kokpitSurface(d(12)).combinedClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, text, tint = tint, modifier = Modifier.size(d(28)))
        if (text != null) {
            Spacer(Modifier.width(d(10)))
            Text(text, color = Txt, fontSize = s(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DirIcon(type: String, size: Int = 28) {
    val (icon, tint, desc) = when (type) {
        "out" -> Triple(Icons.Outlined.CallMade, Dim, "Wychodzące")
        "missed" -> Triple(Icons.Outlined.CallMissed, Missed, "Nieodebrane")
        else -> Triple(Icons.Outlined.CallReceived, GreenTxt, "Przychodzące")
    }
    Icon(icon, desc, tint = tint, modifier = Modifier.size(d(size)))
}

/** Wiersz ostatniego połączenia; słuchawka oddzwania, dotknięcie wiersza tylko otwiera [onOpen]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(ctx: Context, e: Phone.Entry, avatar: Int, detailed: Boolean, onOpen: () -> Unit) {
    val hit = Phone.find(e.number)
    val name = hit?.first?.name ?: e.name.ifBlank { pretty(e.number) }
    val sub = buildString {
        if (e.type == "missed") append(if (detailed) "Nieodebrane · " else "nieodebrane · ")
        else if (detailed && hit != null) append(hit.second.label + " · ")
        append(fmtWhen(e.time))
        if (detailed && e.seconds > 0) append(" · " + fmtDur(e.seconds))
    }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        DirIcon(e.type, if (detailed) 28 else 24)
        Spacer(Modifier.width(d(if (detailed) 12 else 8)))
        if (avatar > 0) {
            ContactAvatar(name, hit?.first?.photo, d(avatar))
            Spacer(Modifier.width(d(14)))
        }
        Column(Modifier.weight(1f)) {
            Text(name, color = if (e.type == "missed") Missed else Txt, fontSize = s(if (detailed) 22 else 20), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Dim, fontSize = s(if (detailed) 17 else 16), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(d(6)))
        CallBtn(Modifier.size(d(if (detailed) 60 else 54)), "Zadzwoń: $name") { Phone.dial(ctx, e.number) }
    }
}

/** Ulubiony jako sam awatar z imieniem (małe bloki) – dotknięcie otwiera okno, nie dzwoni. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavChip(c: Phone.Contact, size: Int, modifier: Modifier, onOpen: () -> Unit) {
    Column(modifier.combinedClickable(onClick = onOpen), horizontalAlignment = Alignment.CenterHorizontally) {
        ContactAvatar(c.name, c.photo, d(size))
        Spacer(Modifier.height(d(5)))
        Text(c.name.substringBefore(' '), color = Txt, fontSize = s(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ulubiony jako kafelek ze słuchawką (duże bloki i okno). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavTile(ctx: Context, c: Phone.Contact, avatar: Int, modifier: Modifier, selected: Boolean = false, onOpen: () -> Unit) {
    val mark = if (selected) Modifier.border(d(4), Accent, LocalTheme.current.shape(d(13))) else Modifier
    Column(modifier.pane().then(mark).combinedClickable(onClick = onOpen).padding(d(10)), horizontalAlignment = Alignment.CenterHorizontally) {
        ContactAvatar(c.name, c.photo, d(avatar))
        Spacer(Modifier.height(d(6)))
        Text(c.name.substringBefore(' '), color = Txt, fontSize = s(18), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(d(7)))
        CallBtn(Modifier.fillMaxWidth().height(d(58)), "Zadzwoń: ${c.name}") { Phone.dial(ctx, c.main.number) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavRow(ctx: Context, c: Phone.Contact, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(c.name, c.photo, d(64))
        Spacer(Modifier.width(d(12)))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Txt, fontSize = s(21), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (c.numbers.size > 1) "${c.numbers.size} numery" else c.main.label, color = Dim, fontSize = s(17), maxLines = 1)
        }
        CallBtn(Modifier.size(d(60)), "Zadzwoń: ${c.name}") { Phone.dial(ctx, c.main.number) }
    }
}

/** Blok „Telefon”: im większy, tym więcej ulubionych i ostatnich połączeń; w czasie rozmowy – rozmowa. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSearch: () -> Unit, onOpen: () -> Unit) {
    val call by Phone.call
    val connected by Phone.connected
    val phone by Phone.phoneName
    val book by Phone.book
    val tick by Phone.historyTick
    val recents = remember(tick) { Phone.history() }
    val favs = remember(book) { book.filter { it.starred } }
    val wide = slot.w >= 2
    val tall = slot.h >= 2
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(d(22))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Label(Icons.Outlined.Phone, when {
                        call.ringing -> "Połączenie przychodzące"
                        call.active -> "Rozmowa"
                        else -> "Telefon"
                    })
                }
                if (wide && !call.active) {
                    Box(Modifier.size(d(11)).background(if (connected) Color(0xFF35C467) else Missed))
                    Spacer(Modifier.width(d(8)))
                    Text(if (connected) phone.ifBlank { "połączony" } else "niepołączony", color = Dim, fontSize = s(17), maxLines = 1)
                } else if (!connected && !call.active) Box(Modifier.size(d(11)).background(Missed))
                if (!call.active && !(wide && tall)) {
                    Spacer(Modifier.width(d(10)))
                    Box(Modifier.size(d(46)).kokpitSurface(d(10)).combinedClickable(onClick = onOpen), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Dialpad, "Klawiatura i kontakty", tint = Txt, modifier = Modifier.size(d(26)))
                    }
                }
            }
            Spacer(Modifier.height(d(12)))
            if (!connected && !call.active) {
                Row(
                    Modifier.fillMaxWidth().pane().border(1.dp, Missed.copy(alpha = 0.6f), LocalTheme.current.shape(d(13))).padding(horizontal = d(14), vertical = d(10)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.BluetoothDisabled, null, tint = Missed, modifier = Modifier.size(d(30)))
                    Spacer(Modifier.width(d(12)))
                    Column {
                        Text(if (Radio.witstek) "Telefon niepołączony" else "Telefon niedostępny", color = Missed, fontSize = s(20), fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(
                            if (!Radio.witstek) "Działa tylko w radiu Witstek" else if (wide) "Połącz telefon z Bluetoothem radia, żeby dzwonić" else "Połącz go z Bluetoothem radia",
                            color = Dim, fontSize = s(16), maxLines = 2,
                        )
                    }
                }
                Spacer(Modifier.height(d(10)))
            }
            when {
                call.active -> CallCompact(act, call, wide, tall)
                wide && tall -> {
                    Section(Icons.Filled.Star, "Ulubione")
                    Spacer(Modifier.height(d(8)))
                    if (favs.isEmpty()) NoFavs(act, onOpen) else LazyRow(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                        items(favs, key = { it.id }) { FavTile(act, it, 76, Modifier.width(d(138)), onOpen = onOpen) }
                    }
                    Spacer(Modifier.height(d(12)))
                    Section(Icons.Outlined.History, "Ostatnie połączenia")
                    Spacer(Modifier.height(d(8)))
                    Box(Modifier.weight(1f).fillMaxWidth().pane().padding(horizontal = d(14))) {
                        if (recents.isEmpty()) Text("Brak połączeń", color = Dim, fontSize = s(19), modifier = Modifier.padding(vertical = d(12)))
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(recents.take(40)) { e -> Box(Modifier.padding(vertical = d(6))) { RecentRow(act, e, 46, true, onOpen) } }
                        }
                    }
                    Spacer(Modifier.height(d(12)))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                        PlainBtn(Modifier.weight(1f).height(d(60)), Icons.Filled.Dialpad, "Klawiatura", onClick = onOpen)
                        PlainBtn(Modifier.weight(1f).height(d(60)), Icons.Filled.Search, "Szukaj kontaktu", onClick = onSearch)
                    }
                }
                wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d(18))) {
                    Column(Modifier.width(d(280))) {
                        if (favs.isEmpty()) NoFavs(act, onOpen) else LazyRow(horizontalArrangement = Arrangement.spacedBy(d(8))) {
                            items(favs.chunked(2)) { pair ->
                                Column(verticalArrangement = Arrangement.spacedBy(d(8))) {
                                    pair.forEach { FavChip(it, 78, Modifier.width(d(132)), onOpen) }
                                }
                            }
                        }
                    }
                    if (recents.isEmpty()) PlainBtn(Modifier.weight(1f).height(d(60)), Icons.Filled.Dialpad, "Klawiatura i kontakty", onClick = onOpen)
                    else LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d(8))) {
                        items(recents.take(40)) { e ->
                            Box(Modifier.fillMaxWidth().pane().padding(start = d(12), end = d(6), top = d(5), bottom = d(5))) { RecentRow(act, e, 0, false, onOpen) }
                        }
                    }
                }
                tall -> {
                    Section(Icons.Filled.Star, "Ulubione")
                    Spacer(Modifier.height(d(8)))
                    if (favs.isEmpty()) NoFavs(act, onOpen) else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d(10))) {
                        items(favs, key = { it.id }) { FavRow(act, it, onOpen) }
                    }
                    Spacer(Modifier.height(d(14)))
                    Section(Icons.Outlined.History, "Ostatnie")
                    Spacer(Modifier.height(d(8)))
                    Box(Modifier.weight(1f).fillMaxWidth().pane().padding(start = d(12), end = d(6))) {
                        if (recents.isEmpty()) Text("Brak połączeń", color = Dim, fontSize = s(19), modifier = Modifier.padding(vertical = d(12)))
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(recents.take(40)) { e -> Box(Modifier.padding(vertical = d(6))) { RecentRow(act, e, 0, false, onOpen) } }
                        }
                    }
                    Spacer(Modifier.height(d(12)))
                    PlainBtn(Modifier.fillMaxWidth().height(d(60)), Icons.Filled.Dialpad, "Klawiatura", onClick = onOpen)
                }
                else -> {
                    if (favs.isEmpty()) NoFavs(act, onOpen) else LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(6))) {
                        items(favs, key = { it.id }) { FavChip(it, 66, Modifier.width(d(88)), onOpen) }
                    }
                    Spacer(Modifier.weight(1f))
                    recents.firstOrNull()?.let {
                        Box(Modifier.fillMaxWidth().pane().padding(start = d(12), end = d(6), top = d(5), bottom = d(5))) { RecentRow(act, it, 0, false, onOpen) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoFavs(act: MainActivity, onOpen: () -> Unit) {
    val loaded by Phone.bookLoaded
    val text = when {
        !Phone.contactsGranted(act) -> "Dotknij, aby dać dostęp do kontaktów"
        !loaded -> "Wczytuję kontakty…"
        else -> "Oznacz kontakty gwiazdką, a pojawią się tutaj"
    }
    Text(text, color = Dim, fontSize = s(18), modifier = Modifier.combinedClickable(onClick = onOpen).padding(vertical = d(8)))
}

/** Rozmowa w bloku. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallCompact(ctx: Context, call: Phone.Call, wide: Boolean, tall: Boolean) {
    val hit = Phone.find(call.number)
    val name = call.name.ifBlank { pretty(call.number).ifBlank { "Nieznany numer" } }
    val now = rememberNow(call)
    Column(Modifier.fillMaxSize()) {
        // dotknięcie rozmowy w bloku otwiera pełne okno rozmowy (powrót po zwinięciu)
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { Phone.restore(ctx) }), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(name, hit?.first?.photo, d(if (wide) 124 else 82))
            Spacer(Modifier.width(d(if (wide) 22 else 14)))
            Column(Modifier.weight(1f)) {
                Text(name, color = Txt, fontSize = s(if (wide) 40 else if (call.name.isBlank()) 22 else 26), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (call.name.isNotBlank()) Text(pretty(call.number) + (hit?.let { " · " + it.second.label } ?: ""), color = Dim, fontSize = s(if (wide) 22 else 17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (wide) Text(stateText(call, now), color = if (call.ringing) GreenTxt else Dim, fontSize = s(22), maxLines = 1)
            }
        }
        if (!wide) {
            Spacer(Modifier.height(d(8)))
            Text(stateText(call, now), color = if (call.state == Phone.ACTIVE) Txt else if (call.ringing) GreenTxt else Dim, fontSize = s(if (call.state == Phone.ACTIVE) 62 else 24), fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(d(14))) {
            if (call.ringing) CallBtn(Modifier.weight(1f).height(d(if (wide) 80 else 66)), "Odbierz", if (wide) "Odbierz" else null) { Phone.answer(ctx) }
            CallBtn(Modifier.weight(1f).height(d(if (wide) 80 else 66)), "Rozłącz", if (wide || !call.ringing) (if (call.ringing) "Odrzuć" else "Rozłącz") else null, red = true) { Phone.hangup(ctx) }
        }
    }
}

@Composable
private fun rememberNow(call: Phone.Call): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.state, call.since) {
        while (call.state == Phone.ACTIVE) { now = System.currentTimeMillis(); delay(500) }
    }
    return now
}

private fun stateText(call: Phone.Call, now: Long) = when (call.state) {
    Phone.OUTGOING -> "Dzwonię…"
    Phone.INCOMING -> "dzwoni"
    Phone.ACTIVE -> fmtTimer(((now - call.since) / 1000).coerceAtLeast(0))
    else -> ""
}

/** Głośność rozmowy = głośność wyjścia radia (poza radiem: głośność muzyki Androida). */
@Composable
private fun CallVolume(ctx: Context) {
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val mcu = remember { CarVolume.mcu(ctx) != null }
    val max = remember { if (mcu) CarVolume.MAX else am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    fun read() = if (mcu) CarVolume.mcu(ctx) ?: 0 else am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
    var vol by remember { mutableStateOf(read()) }
    LaunchedEffect(Unit) { while (true) { delay(700); vol = read() } }
    fun step(up: Boolean) {
        if (mcu) CarVolume.step(ctx, up)
        else am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER, 0)
        vol = (vol + if (up) 1 else -1).coerceIn(0, max)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlainBtn(Modifier.size(d(80), d(68)), Icons.Filled.Remove, null) { step(false) }
        Spacer(Modifier.width(d(14)))
        Box(Modifier.weight(1f).height(d(18)).pane(0)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(vol / max.toFloat()).background(LocalTheme.current.active))
        }
        Spacer(Modifier.width(d(14)))
        Text("$vol", color = Txt, fontSize = s(26), fontWeight = FontWeight.Medium)
        Text(" / $max", color = Dim, fontSize = s(18))
        Spacer(Modifier.width(d(14)))
        PlainBtn(Modifier.size(d(80), d(68)), Icons.Filled.Add, null) { step(true) }
    }
}

@Composable
private fun InfoRow(k: String, v: String, vColor: Color = Txt) {
    Row(Modifier.fillMaxWidth().height(d(40)), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = Dim, fontSize = s(20), modifier = Modifier.weight(1f), maxLines = 1)
        Text(v, color = vColor, fontSize = s(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Pełne okno rozmowy (CallActivity i okno telefonu): więcej szczegółów niż w bloku. */
@Composable
fun CallWindow(ctx: Context, call: Phone.Call, onMinimize: (() -> Unit)? = null) {
    val hit = Phone.find(call.number)
    val name = call.name.ifBlank { pretty(call.number).ifBlank { "Nieznany numer" } }
    val phone by Phone.phoneName
    val tick by Phone.historyTick
    val earlier = remember(call.number, tick) { Phone.historyFor(call.number) }
    val now = rememberNow(call)

    @Composable
    fun Who() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(name, hit?.first?.photo, d(170))
            Spacer(Modifier.width(d(30)))
            Column {
                Text(name, color = Txt, fontSize = s(58), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (call.name.isNotBlank()) Row(verticalAlignment = Alignment.Bottom) {
                    Text(pretty(call.number), color = Txt, fontSize = s(30))
                    hit?.let { Text(" · " + it.second.label, color = Dim, fontSize = s(22)) }
                }
            }
        }
    }

    if (call.ringing) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(d(12)).background(Color(0xFF35C467)))
                Spacer(Modifier.width(d(10)))
                Text("POŁĄCZENIE PRZYCHODZĄCE", color = GreenTxt, fontSize = s(16), fontWeight = FontWeight.Bold, letterSpacing = s(2.5))
            }
            Spacer(Modifier.height(d(18)))
            Who()
            Spacer(Modifier.height(d(20)))
            Column(Modifier.fillMaxWidth().pane().padding(horizontal = d(20), vertical = d(8))) {
                val last = earlier.firstOrNull { it.type != "missed" }
                val missed = earlier.filter { it.type == "missed" }
                InfoRow("Ostatnia rozmowa", last?.let { fmtWhen(it.time) + (if (it.seconds > 0) " · " + fmtDur(it.seconds) else "") } ?: "brak")
                if (missed.isNotEmpty()) InfoRow("Nieodebrane", "${missed.size} · ${fmtWhen(missed.first().time)}", Missed)
                InfoRow("Połączenie", (if (phone.isBlank()) "" else "$phone · ") + "Bluetooth radia")
            }
            Spacer(Modifier.height(d(24)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(20))) {
                CallBtn(Modifier.weight(1f).height(d(96)), "Odbierz", "Odbierz", big = true) { Phone.answer(ctx) }
                CallBtn(Modifier.weight(1f).height(d(96)), "Odrzuć", "Odrzuć", red = true, big = true) { Phone.hangup(ctx) }
            }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(d(32))) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (call.inbound) "ROZMOWA PRZYCHODZĄCA" else "ROZMOWA WYCHODZĄCA") + " · PRZEZ BLUETOOTH RADIA",
                color = Dim, fontSize = s(16), fontWeight = FontWeight.Bold, letterSpacing = s(2.5), maxLines = 1,
            )
            Spacer(Modifier.height(d(16)))
            Who()
            Spacer(Modifier.height(d(14)))
            if (call.state == Phone.ACTIVE) Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtTimer(((now - call.since) / 1000).coerceAtLeast(0)), color = Txt, fontSize = s(104), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(d(18)))
                Text("od " + java.text.SimpleDateFormat("H:mm", java.util.Locale("pl")).format(java.util.Date(call.since)), color = Dim, fontSize = s(22), modifier = Modifier.padding(bottom = d(22)))
            } else Text("Dzwonię…", color = Dim, fontSize = s(54), modifier = Modifier.padding(vertical = d(24)))
            Spacer(Modifier.height(d(14)))
            Row(horizontalArrangement = Arrangement.spacedBy(d(16))) {
                CallBtn(Modifier.weight(1f).height(d(96)), "Rozłącz", "Rozłącz", red = true, big = true) { Phone.hangup(ctx) }
                // zwinięcie okna: rozmowa trwa, wraca się z paska u góry ekranu albo z bloku Telefon
                if (onMinimize != null) PlainBtn(Modifier.width(d(230)).height(d(96)), Icons.Filled.Minimize, "Zwiń", onClick = onMinimize)
            }
        }
        Column(Modifier.width(d(560))) {
            Text("GŁOŚNOŚĆ", color = Dim, fontSize = s(16), fontWeight = FontWeight.Bold, letterSpacing = s(2.5))
            Spacer(Modifier.height(d(12)))
            CallVolume(ctx)
            Spacer(Modifier.height(d(22)))
            Text(if (call.name.isBlank()) "WCZEŚNIEJ Z TYM NUMEREM" else ("WCZEŚNIEJ: " + name.substringBefore(' ')).uppercase(), color = Dim, fontSize = s(16), fontWeight = FontWeight.Bold, letterSpacing = s(2.5), maxLines = 1)
            Spacer(Modifier.height(d(12)))
            Column(Modifier.fillMaxWidth().pane().padding(horizontal = d(20), vertical = d(8))) {
                if (earlier.isEmpty()) InfoRow("Pierwsza rozmowa z tym numerem", "")
                earlier.take(4).forEach { e ->
                    val what = when (e.type) { "missed" -> "nieodebrana"; "out" -> "wychodząca"; else -> "przychodząca" }
                    InfoRow(fmtWhen(e.time), what + (if (e.seconds > 0) " · " + fmtDur(e.seconds) else ""), if (e.type == "missed") Missed else Dim)
                }
            }
        }
    }
}

/** Okno telefonu: ulubione i ostatnie | wyszukiwarka i kontakty | klawiatura – w jednym widoku. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhoneDialog(act: MainActivity, search: Boolean, onClose: () -> Unit) {
    val call by Phone.call
    val connected by Phone.connected
    val phone by Phone.phoneName
    val book by Phone.book
    val loaded by Phone.bookLoaded
    val tick by Phone.historyTick
    var number by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Long?>(null) }
    var granted by remember { mutableStateOf(Phone.contactsGranted(act)) }
    val recents = remember(tick) { Phone.history() }
    val favs = remember(book) { book.filter { it.starred } }
    val list = remember(book, query) {
        val q = query.trim().lowercase()
        val qd = q.filter { it.isDigit() }
        if (q.isEmpty()) book else book.filter { c ->
            c.name.lowercase().contains(q) || (qd.length >= 3 && c.numbers.any { it.number.filter { ch -> ch.isDigit() }.contains(qd) })
        }
    }
    val listState = rememberLazyListState()
    // kierownica: zaznaczony ulubiony (Phone.favIndex) – przewijanie następny/poprzedni, przycisk telefonu dzwoni
    val favIndex by Phone.favIndex
    val favState = rememberLazyListState()
    androidx.compose.runtime.DisposableEffect(Unit) {
        Phone.windowOpen.value = true
        onDispose { Phone.windowOpen.value = false; Phone.favIndex.intValue = -1 }
    }
    LaunchedEffect(favIndex) { if (favIndex in favs.indices) favState.animateScrollToItem(favIndex) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // „Szukaj kontaktu”: od razu kursor w wyszukiwarce i klawiatura na ekranie
    LaunchedEffect(search, granted, loaded) {
        if (search && granted && loaded && !call.active) { delay(250); runCatching { searchFocus.requestFocus() } }
    }
    LaunchedEffect(Unit) { Phone.reload(act) }
    // wybór z ulubionych / ostatnich: pokaż kontakt na liście
    fun show(c: Phone.Contact) {
        if (list.none { it.id == c.id }) query = ""
        selected = c.id
    }
    LaunchedEffect(selected, list) {
        val i = list.indexOfFirst { it.id == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    val shape = LocalTheme.current.shape(d(28))
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // okno telefonu to osobne okno Androida: klawisze „następny / poprzedni utwór” z kierownicy zatrzymujemy tutaj,
        // inaczej system oddaje je odtwarzaczowi (kierownica ma wtedy tylko przewijać ulubionych)
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.DisposableEffect(dialogView) {
            val w = (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val old = w?.callback
            if (w != null && old != null) w.callback = object : android.view.Window.Callback by old {
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
                    Phone.takesMediaKey(event.keyCode) || old.dispatchKeyEvent(event)
            }
            onDispose { if (w != null && old != null) w.callback = old }
        }
        Column(
            Modifier.fillMaxWidth(0.955f).fillMaxHeight(if (call.active) 0.8f else 0.95f).clip(shape)
                .background(if (LightMode.on) Color(0xFFF4F6FA) else Color(0xFF0A0D14))
                .background(LocalTheme.current.block)
                .border(1.dp, LocalTheme.current.blockEdge, shape).padding(horizontal = d(24), vertical = d(18)),
        ) {
            Row(Modifier.height(d(56)), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Phone, null, tint = Accent, modifier = Modifier.size(d(30)))
                Spacer(Modifier.width(d(14)))
                Text("Telefon", color = Txt, fontSize = s(27), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(d(22)))
                Box(Modifier.size(d(12)).background(if (connected) Color(0xFF35C467) else Missed))
                Spacer(Modifier.width(d(10)))
                Text(
                    if (connected) phone.ifBlank { "Telefon" } + " · połączony przez Bluetooth radia" else if (Radio.witstek) "Telefon niepołączony z Bluetoothem radia" else "Telefon działa tylko w radiu Witstek",
                    color = if (connected) Dim else Missed, fontSize = s(19), maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (granted && loaded) Text("Kontakty Google · ${book.size}", color = Dim, fontSize = s(18))
                Spacer(Modifier.width(d(18)))
                PlainBtn(Modifier.height(d(54)).padding(horizontal = d(4)).width(d(170)), Icons.Filled.Close, "Zamknij", onClick = onClose)
            }
            Spacer(Modifier.height(d(12)))
            if (call.active) {
                CallWindow(act, call, onMinimize = { Phone.minimize(act); onClose() })
                return@Column
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d(18))) {
                // --- ulubione i ostatnie ---
                Column(Modifier.width(d(640)).fillMaxHeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Section(Icons.Filled.Star, "Ulubione")
                        favs.getOrNull(favIndex)?.let {
                            Spacer(Modifier.width(d(14)))
                            Text("przycisk telefonu dzwoni: ${it.name}", color = Accent, fontSize = s(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(d(10)))
                    if (favs.isEmpty()) Text(
                        if (!granted) "Daj dostęp do kontaktów, żeby zobaczyć ulubione" else "Oznacz kontakt gwiazdką – pojawi się tutaj",
                        color = Dim, fontSize = s(19), modifier = Modifier.padding(vertical = d(10)),
                    ) else LazyRow(state = favState, horizontalArrangement = Arrangement.spacedBy(d(12))) {
                        items(favs.size, key = { favs[it].id }) { i ->
                            val c = favs[i]
                            FavTile(act, c, 82, Modifier.width(d(150)), selected = i == favIndex) { show(c) }
                        }
                    }
                    Spacer(Modifier.height(d(16)))
                    Section(Icons.Outlined.History, "Ostatnie połączenia")
                    Spacer(Modifier.height(d(10)))
                    Box(Modifier.weight(1f).fillMaxWidth().pane().padding(horizontal = d(14))) {
                        if (recents.isEmpty()) Text("Brak połączeń", color = Dim, fontSize = s(20), modifier = Modifier.padding(vertical = d(16)))
                        else LazyColumn(Modifier.fillMaxSize()) {
                            items(recents) { e ->
                                Box(Modifier.padding(vertical = d(7))) {
                                    RecentRow(act, e, 52, true) {
                                        val hit = Phone.find(e.number)
                                        if (hit != null) show(hit.first) else number = e.number
                                    }
                                }
                            }
                        }
                    }
                }
                // --- wyszukiwarka i kontakty ---
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(d(66)).kokpitSurface(d(13), field = true).padding(horizontal = d(20)), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = Accent, modifier = Modifier.size(d(30)))
                        Spacer(Modifier.width(d(14)))
                        androidx.compose.foundation.text.BasicTextField(
                            query, { query = it; selected = null }, singleLine = true,
                            textStyle = TextStyle(color = Txt, fontSize = s(25)),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.weight(1f).focusRequester(searchFocus),
                            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Szukaj w kontaktach", color = Dim, fontSize = s(25)); inner() } },
                        )
                        if (query.isNotBlank()) Text(plural(list.size), color = Dim, fontSize = s(18))
                    }
                    Spacer(Modifier.height(d(12)))
                    when {
                        !granted -> Column(Modifier.fillMaxSize().pane().padding(d(24)), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Kontakty są pobierane z konta Google w radiu", color = Txt, fontSize = s(22))
                            Spacer(Modifier.height(d(16)))
                            PlainBtn(Modifier.height(d(64)).width(d(360)), Icons.Filled.Search, "Zezwól na kontakty") {
                                thread {
                                    val ok = act.requestPermissionsBlocking(arrayOf(android.Manifest.permission.READ_CONTACTS))
                                    act.runOnUiThread { granted = ok; if (ok) Phone.reload(act) }
                                }
                            }
                        }
                        !loaded -> Box(Modifier.fillMaxSize().pane(), contentAlignment = Alignment.Center) { Text("Wczytuję kontakty…", color = Dim, fontSize = s(22)) }
                        list.isEmpty() -> Box(Modifier.fillMaxSize().pane(), contentAlignment = Alignment.Center) {
                            Text(if (book.isEmpty()) "Brak kontaktów z numerem telefonu" else "Nic nie pasuje do „$query”", color = Dim, fontSize = s(22))
                        }
                        else -> LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(d(8))) {
                            items(list, key = { it.id }) { c ->
                                ContactItem(act, c, open = c.id == selected, onToggle = { selected = if (selected == c.id) null else c.id })
                            }
                        }
                    }
                }
                // --- klawiatura ---
                KeyPad(act, number, connected, Modifier.width(d(540)).fillMaxHeight()) { number = it }
            }
        }
    }
}

private fun plural(n: Int) = when {
    n == 1 -> "1 wynik"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n wyniki"
    else -> "$n wyników"
}

/** Kontakt na liście: zwinięty (nazwa + numer główny) albo rozwinięty (wszystkie numery ze słuchawkami, gwiazdka). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactItem(act: MainActivity, c: Phone.Contact, open: Boolean, onToggle: () -> Unit) {
    if (!open) {
        Row(
            Modifier.fillMaxWidth().pane().combinedClickable(onClick = onToggle).padding(horizontal = d(14), vertical = d(9)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(c.name, c.photo, d(54))
            Spacer(Modifier.width(d(16)))
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Txt, fontSize = s(23), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (c.numbers.size > 1) "${c.numbers.size} numery" else c.main.label + " " + pretty(c.main.number),
                    color = Dim, fontSize = s(18), maxLines = 1,
                )
            }
            if (c.starred) Icon(Icons.Filled.Star, "Ulubiony", tint = Color(0xFFFFD166), modifier = Modifier.size(d(24)))
            Spacer(Modifier.width(d(10)))
            Icon(Icons.Filled.ExpandMore, "Rozwiń", tint = Dim, modifier = Modifier.size(d(30)))
        }
        return
    }
    val shape = LocalTheme.current.shape(d(16))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Accent.copy(alpha = 0.16f)).border(1.dp, Accent.copy(alpha = 0.6f), shape)
            .padding(horizontal = d(16), vertical = d(14)),
    ) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            ContactAvatar(c.name, c.photo, d(80))
            Spacer(Modifier.width(d(18)))
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Txt, fontSize = s(29), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (c.numbers.size > 1) "${c.numbers.size} numery · jeden kontakt" else "Kontakt Google", color = Dim, fontSize = s(18))
            }
            PlainBtn(
                Modifier.size(d(62)), if (c.starred) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                tint = if (c.starred) Color(0xFFFFD166) else Txt,
            ) {
                thread {
                    val w = android.Manifest.permission.WRITE_CONTACTS
                    val ok = androidx.core.content.ContextCompat.checkSelfPermission(act, w) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        act.requestPermissionsBlocking(arrayOf(w))
                    if (ok) act.runOnUiThread { Phone.setStarred(act, c, !c.starred) }
                }
            }
            Spacer(Modifier.width(d(8)))
            Icon(Icons.Filled.ExpandLess, "Zwiń", tint = Dim, modifier = Modifier.size(d(30)))
        }
        c.numbers.forEach { n ->
            Spacer(Modifier.height(d(10)))
            Row(Modifier.fillMaxWidth().height(d(70)).pane().padding(start = d(18), end = d(6)), verticalAlignment = Alignment.CenterVertically) {
                Text(n.label, color = Dim, fontSize = s(19), modifier = Modifier.width(d(120)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pretty(n.number), color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                if (n === c.main && c.numbers.size > 1) {
                    Text("główny", color = Dim, fontSize = s(16))
                    Spacer(Modifier.width(d(12)))
                }
                CallBtn(Modifier.size(d(128), d(58)), "Zadzwoń: ${c.name}, ${n.label}") { Phone.dial(act, n.number) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyPad(ctx: Context, number: String, connected: Boolean, modifier: Modifier, onNumber: (String) -> Unit) {
    val hint = remember(number, Phone.book.value) { Phone.suggest(number) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d(11))) {
        Row(Modifier.fillMaxWidth().height(d(88)).pane().padding(start = d(22), end = d(8)), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (number.isBlank()) "Wpisz numer" else pretty(number), color = if (number.isBlank()) Dim else Txt,
                    fontSize = s(if (number.length > 12) 30 else 40), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (hint != null) Text("${hint.first.name} · ${hint.second.label}", color = Accent, fontSize = s(18), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (number.isNotEmpty()) Box(
                Modifier.size(d(76), d(66)).kokpitSurface(d(12)).combinedClickable(onClick = { onNumber(number.dropLast(1)) }, onLongClick = { onNumber("") }),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Backspace, "Usuń ostatnią cyfrę", tint = Txt, modifier = Modifier.size(d(30))) }
        }
        val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(d(11))) {
                row.forEach { (k, sub) -> Key(k, sub, onClick = { onNumber(number + k) }, onLong = { if (k == "0") onNumber("$number+") }) }
            }
        }
        val can = connected && number.isNotBlank()
        CallBtn(Modifier.fillMaxWidth().height(d(84)), "Zadzwoń", "Zadzwoń", enabled = can, big = true) {
            // wpisany początek numeru z podpowiedzią dzwoni pod wpisane cyfry, nie pod podpowiedź
            Phone.dial(ctx, number)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(k: String, sub: String, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.weight(1f).fillMaxHeight().kokpitSurface(d(13)).combinedClickable(onClick = onClick, onLongClick = onLong),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Text(k, color = Txt, fontSize = s(38), fontWeight = FontWeight.Medium)
        if (sub.isNotEmpty()) {
            Spacer(Modifier.width(d(12)))
            Text(sub, color = Dim, fontSize = s(15), letterSpacing = s(2))
        }
    }
}
