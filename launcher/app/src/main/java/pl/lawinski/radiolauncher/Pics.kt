@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/*
 * Obrazy, które asystent pokazuje rozmówcy: zdjęcia z kamer (bieżące i z nagrań – zbierane same,
 * gdy asystent je ogląda), zdjęcia z Wikipedii / Wikimedia Commons, zdjęcia miejsca z Google Maps
 * i dowolny obraz spod adresu (np. miniatura filmu YouTube). Każdy obraz jest pobierany raz,
 * zmniejszany i zapisywany w plikach aplikacji przy rozmowie (files/assistant_pics/<rozmowa>/),
 * więc miniatury pod odpowiedzią widać też po restarcie. Do modelu obrazy stąd nie wracają.
 * Podgląd na cały ekran: PicActivity (nad dowolną aplikacją, jak okno rozmowy telefonicznej).
 */

/** Jeden obraz przy odpowiedzi asystenta: plik JPEG w pamięci radia i podpis (co to, skąd). */
data class Pic(val file: String, val caption: String)

object Pics {
    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 12 * 1024 * 1024
    private const val AGENT = "Mozilla/5.0 (Linux; Android 13) car-launcher/1.0"

    private var root: File? = null
    private val pending = ArrayList<Pic>()

    fun init(ctx: Context) {
        if (root == null) root = File(ctx.filesDir, "assistant_pics")
    }

    /** Początek nowej wypowiedzi: obrazy zebrane wcześniej, a nieprzypięte do odpowiedzi, przepadają. */
    fun begin() = synchronized(pending) { pending.clear() }

    /** Obrazy zebrane w tej wypowiedzi – trafiają do odpowiedzi asystenta. */
    fun take(): List<Pic> = synchronized(pending) { pending.toList().also { pending.clear() } }

    private fun collected(): List<Pic> = synchronized(pending) { pending.toList() }

    private fun newFile(): File? {
        val dir = File(root ?: return null, Assistant.session.ifEmpty { "0" }).apply { mkdirs() }
        return File(dir, "${System.currentTimeMillis()}_${(Math.random() * 100000).toInt()}.jpg")
    }

    private fun keep(bmp: Bitmap, caption: String): Pic? {
        val f = newFile() ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val pic = Pic(f.path, caption.trim().take(200))
        synchronized(pending) { pending += pic }
        return pic
    }

    /** Zdjęcie z kamery, które asystent właśnie obejrzał (JPEG). */
    fun addJpeg(jpeg: ByteArray, caption: String) {
        runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { keep(it, caption) } }
    }

    /** Adres spoza radia i sieci domowej: obraz wskazuje model, więc nie może sięgać do usług lokalnych. */
    private fun publicUrl(u: URL): Boolean {
        if (u.protocol != "https" && u.protocol != "http") return false
        return runCatching {
            InetAddress.getAllByName(u.host).none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }
        }.getOrDefault(false)
    }

    private fun bytes(address: String): ByteArray {
        var url = URL(address)
        repeat(5) {
            if (!publicUrl(url)) throw IllegalArgumentException("adres niedozwolony")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "image/*,*/*;q=0.5")
            val code = c.responseCode
            if (code in 300..399) {
                url = URL(url, c.getHeaderField("Location") ?: throw IllegalStateException("przekierowanie bez adresu"))
                c.disconnect()
                return@repeat
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            if (c.contentLengthLong > MAX_BYTES) throw IllegalStateException("plik za duży")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { inp ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IllegalStateException("plik za duży")
                }
            }
            return out.toByteArray()
        }
        throw IllegalStateException("za dużo przekierowań")
    }

    private fun decode(data: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Pobiera obraz spod adresu i dopisuje go do zebranych; zwraca opis błędu albo null. */
    private fun addUrl(address: String, caption: String): String? = try {
        val bmp = decode(bytes(address)) ?: throw IllegalStateException("to nie jest obraz (JPEG, PNG, WebP, GIF)")
        if (keep(bmp, caption) == null) "brak miejsca na zapis" else null
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    private fun getJson(address: String): JSONObject {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", AGENT)
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    /** Strony z wyników API MediaWiki w kolejności trafności. */
    private fun pages(o: JSONObject): List<JSONObject> {
        val p = o.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return p.keys().asSequence().map { p.getJSONObject(it) }.sortedBy { it.optInt("index", 999) }.toList()
    }

    /** nazwa pliku z adresu miniatury Wikimedia (bez rozmiaru i parametrów) – do pomijania powtórek */
    private fun wikiFile(url: String) = url.substringBefore('?').substringAfterLast('/').substringAfter("px-")

    /**
     * Obrazy do hasła: główne zdjęcie pasującego artykułu Wikipedii (pl i en), potem zdjęcia z Wikimedia
     * Commons – szukane po haśle, a gdy to za mało (Commons słabo zna polskie nazwy), po tytułach znalezionych artykułów.
     */
    private fun wikipedia(query: String, count: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val titles = ArrayList<String>()
        fun add(url: String, caption: String) {
            if (url.isNotEmpty() && out.size < count && out.none { wikiFile(it.first) == wikiFile(url) }) out += url to caption
        }
        for (lang in listOf("pl", "en")) runCatching {
            val page = pages(getJson("https://$lang.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch=${enc(query)}&gsrlimit=1&prop=pageimages&piprop=thumbnail&pilicense=any&pithumbsize=$MAX_SIDE"))
                .firstOrNull() ?: return@runCatching
            titles += page.optString("title")
            page.optJSONObject("thumbnail")?.let { add(it.optString("source"), "${page.optString("title")} · Wikipedia") }
        }
        for (q in (listOf(query) + titles.reversed()).distinct()) {
            if (out.size >= count) break
            runCatching {
                pages(getJson("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrsearch=${enc("$q filetype:bitmap")}&gsrlimit=${count + 3}&prop=imageinfo&iiprop=url&iiurlwidth=$MAX_SIDE"))
                    .forEach { p ->
                        val info = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@forEach
                        val name = p.optString("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
                        add(info.optString("thumburl").ifEmpty { info.optString("url") }, "$name · Wikimedia Commons")
                    }
            }
        }
        return out
    }

    /**
     * Narzędzie asystenta `show_image`: zbiera obrazy z wybranego źródła (staną się miniaturami pod
     * odpowiedzią) i – na wyraźną prośbę rozmówcy – od razu otwiera je na cały ekran.
     */
    fun run(act: MainActivity, input: JSONObject): String {
        val query = input.optString("query").trim()
        val count = input.optInt("count", 3).coerceIn(1, 6)
        val full = input.optBoolean("fullscreen")
        val before = collected().size
        val problems = ArrayList<String>()
        when (input.optString("source")) {
            "collected" -> if (before == 0) return "Nie ma jeszcze żadnego obrazu w tej odpowiedzi – najpierw zrób zdjęcie z kamery albo wybierz inne źródło."
            "url" -> {
                val urls = query.split(Regex("\\s+")).filter { it.startsWith("http") }.take(6)
                if (urls.isEmpty()) return "Podaj w query adres obrazu (http…); kilka adresów oddziel spacją."
                val caption = input.optString("caption").trim()
                urls.forEach { u -> addUrl(u, caption.ifEmpty { runCatching { URL(u).host }.getOrDefault("") })?.let { problems += "$u – $it" } }
            }
            "wikipedia" -> {
                if (query.isEmpty()) return "Podaj w query hasło, np. „BMW serii 5 F11”."
                val found = try { wikipedia(query, count) } catch (e: Exception) { return "Wikipedia nie odpowiedziała: ${e.message}" }
                if (found.isEmpty()) return "W Wikipedii i Wikimedia Commons nie ma zdjęć do hasła „$query”. Spróbuj innego hasła (np. po angielsku) albo podaj adres obrazu (source url)."
                found.forEach { (u, c) -> addUrl(u, c)?.let { problems += "$c – $it" } }
            }
            "place" -> {
                if (query.isEmpty()) return "Podaj w query nazwę miejsca z miejscowością."
                val found = try { Places.photos(act, query, count) } catch (e: Exception) { return "Google nie podał zdjęć: ${e.message}" }
                if (found.isEmpty()) return "Google Maps nie ma zdjęć miejsca „$query”."
                found.forEach { (u, c) -> addUrl(u, c)?.let { problems += "$c – $it" } }
            }
            else -> return "Nieznane źródło"
        }
        val all = collected()
        val added = all.drop(before)
        if (added.isEmpty() && input.optString("source") != "collected")
            return "Nie udało się pobrać żadnego obrazu: " + problems.joinToString("; ").take(400)
        if (full) act.runOnUiThread { PicActivity.open(act, all, if (added.isEmpty()) 0 else before) }
        return buildString {
            if (added.isNotEmpty()) append("Pobrane obrazy (${added.size}): ").append(added.joinToString("; ") { it.caption.ifEmpty { "bez podpisu" } }).append(". ")
            append(if (full) "Rozmówca widzi je teraz na całym ekranie radia" else "Rozmówca zobaczy je jako miniatury pod Twoją odpowiedzią (dotknięcie powiększa)")
            append("; Ty ich nie widzisz – nie opisuj, co na nich jest, poza tym, co wynika z podpisów.")
            if (problems.isNotEmpty()) append(" Nie udało się: ").append(problems.joinToString("; ").take(300))
        }
    }

    // ---------- zapis przy rozmowie ----------

    fun toJson(pics: List<Pic>) = JSONArray().apply { pics.forEach { put(JSONObject().put("file", it.file).put("caption", it.caption)) } }

    fun fromJson(a: JSONArray?): List<Pic> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Pic(it.optString("file"), it.optString("caption")) } }
            .filter { it.file.isNotEmpty() }

    fun delete(pics: List<Pic>) = pics.forEach { runCatching { File(it.file).delete() } }

    fun deleteSession(id: String) {
        runCatching { File(root ?: return, id).deleteRecursively() }
    }

    fun deleteAll() {
        runCatching { root?.deleteRecursively() }
    }

    /** Obraz z pliku zmniejszony do [maxSide] (miniatury nie wczytują pełnego zdjęcia). */
    fun load(path: String, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
private fun picBitmap(path: String, maxSide: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path, maxSide) {
        value = withContext(Dispatchers.IO) { Pics.load(path, maxSide)?.asImageBitmap() }
    }
    return bmp
}

/** Miniatury obrazów pod odpowiedzią asystenta; dotknięcie otwiera podgląd na cały ekran. */
@Composable
fun PicThumbs(ctx: Context, pics: List<Pic>, height: Dp, modifier: Modifier = Modifier) {
    if (pics.isEmpty()) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(height / 12)) {
        items(pics.size) { i ->
            val bmp = picBitmap(pics[i].file, 480)
            Box(
                Modifier.height(height).width(height * 16 / 9).clip(RoundedCornerShape(height / 10)).background(Color(0x33808A99))
                    .clickable { PicActivity.open(ctx, pics, i) },
            ) {
                if (bmp != null) Image(bmp, pics[i].caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Podgląd obrazów na cały ekran, nad dowolną aplikacją: przewijanie w bok, podpis, zamknięcie. */
class PicActivity : ComponentActivity() {
    private val target = mutableIntStateOf(0) // obraz wybrany klawiszami (kontroler iDrive)

    private val moves = mutableIntStateOf(0)
    private var page = 0 // obraz na ekranie
    private var lastKey = 0L

    /** Kolejne szybkie naciśnięcia liczą od wybranego obrazu; po przerwie – od tego, który jest na ekranie (po przewinięciu palcem). */
    private fun move(by: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val from = if (now - lastKey < 700) target.intValue else page
        lastKey = now
        target.intValue = (from + by).coerceIn(0, (shown.size - 1).coerceAtLeast(0))
        moves.intValue++ // także gdy cel się nie zmienił (po przewinięciu palcem obraz na ekranie jest inny)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val pics = shown
        if (pics.isEmpty()) { finish(); return }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        target.intValue = start.coerceIn(0, pics.size - 1)
        setContent {
            val pager = rememberPagerState(initialPage = target.intValue) { pics.size }
            val scope = rememberCoroutineScope()
            val moved by moves
            LaunchedEffect(moved) { if (moved > 0) pager.animateScrollToPage(target.intValue.coerceIn(0, pics.size - 1)) }
            page = pager.currentPage
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                    val bmp = picBitmap(pics[page].file, 2600)
                    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { finish() }, contentAlignment = Alignment.Center) {
                        if (bmp != null) Image(bmp, pics[page].caption, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                val caption = pics[pager.currentPage].caption
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 28.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(caption, color = Color.White, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (pics.size > 1) Text("${pager.currentPage + 1} / ${pics.size}", color = Color(0xFFB8C2D0), fontSize = 20.sp, modifier = Modifier.padding(start = 20.dp))
                }
                RoundButton(Icons.Filled.Close, "Zamknij", Modifier.align(Alignment.TopEnd).padding(18.dp)) { finish() }
                if (pager.currentPage > 0) RoundButton(Icons.Filled.ChevronLeft, "Poprzedni", Modifier.align(Alignment.CenterStart).padding(18.dp)) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                }
                if (pager.currentPage < pics.size - 1) RoundButton(Icons.Filled.ChevronRight, "Następny", Modifier.align(Alignment.CenterEnd).padding(18.dp)) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.size(72.dp).clip(CircleShape).background(Color(0x99101622)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(38.dp))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); return true }
            KeyEvent.KEYCODE_TAB -> { move(if (event.isShiftPressed) -1 else 1); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { finish(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recreate() // nowy zestaw obrazów przy otwartym podglądzie
    }

    companion object {
        @Volatile private var shown: List<Pic> = emptyList()
        @Volatile private var start = 0

        fun open(ctx: Context, pics: List<Pic>, index: Int) {
            if (pics.isEmpty()) return
            shown = pics
            start = index
            ctx.startActivity(Intent(ctx, PicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
