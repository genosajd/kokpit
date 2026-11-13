package pl.lawinski.radiolauncher

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/*
 * Asystent AI w bloku „Asystent”: rozmowa z Claude (Anthropic) albo z modelem OpenAI.
 * Dostawcę, klucz API i model ustawia się w Ustawieniach → Asystent; klucz zostaje tylko w radiu.
 * Asystent zna stan auta i może włączyć nawigację (narzędzia niżej).
 */

/** Jedna wypowiedź w rozmowie widoczna w bloku. */
data class ChatLine(val fromUser: Boolean, val text: String)

/** Wynik narzędzia: tekst i opcjonalnie zdjęcie JPEG (base64). */
private class ToolOut(val text: String, val jpeg: String? = null)

/** Narzędzia asystenta – wspólne dla obu dostawców. */
private object AssistantTools {
    class Def(val name: String, val description: String, val props: Map<String, Map<String, Any>>, val required: List<String>)

    val all = listOf(
        Def(
            "car_status",
            "Bieżący stan auta i otoczenia: paliwo, zasięg, temperatura zewnętrzna, bieg, prędkość, otwarte drzwi, położenie GPS, trwająca trasa w nawigacji. Użyj przy każdym pytaniu o auto, paliwo, pogodę za oknem albo o to, gdzie jesteśmy.",
            emptyMap(), emptyList(),
        ),
        Def(
            "navigate",
            "Włącza nawigację w Mapach Google do podanego celu (adres, nazwa miejsca albo np. „najbliższa stacja Orlen”). Użyj, gdy rozmówca prosi o trasę albo zawiezienie gdzieś.",
            mapOf(
                "destination" to mapOf("type" to "string", "description" to "Cel: adres albo nazwa miejsca, po polsku"),
                "stops" to mapOf("type" to "string", "description" to "Przystanki po drodze w kolejności, oddzielone średnikiem (np. „Orlen Marki; Biedronka Radzymin”); bez przystanków pusty tekst"),
            ),
            listOf("destination", "stops"),
        ),
        Def(
            "places",
            "Google Maps: szukanie miejsc i czas dojazdu z korkami. search = miejsca pasujące do query (np. „stacja benzynowa”, „pizzeria”, „apteka całodobowa”) w pobliżu auta albo koło near; zwraca nazwę, adres, odległość, ocenę i czy teraz otwarte. route = czas i długość dojazdu autem do destination przy obecnym ruchu (z origin albo z bieżącego położenia, opcjonalnie przez stops), też trasy alternatywne. Samo places niczego nie włącza – nawigację włącza navigate.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("search", "route")),
                "query" to mapOf("type" to "string", "description" to "search: czego szukać; inaczej pusty"),
                "near" to mapOf("type" to "string", "description" to "search: miejscowość albo adres, koło którego szukać; pusty = koło auta"),
                "open_now" to mapOf("type" to "boolean", "description" to "search: true = tylko teraz otwarte; inaczej false"),
                "fuel_prices" to mapOf("type" to "boolean", "description" to "search: true tylko, gdy pytanie dotyczy cen paliw na stacjach (droższe zapytanie); inaczej false"),
                "destination" to mapOf("type" to "string", "description" to "route: cel; inaczej pusty"),
                "origin" to mapOf("type" to "string", "description" to "route: skąd; pusty = bieżące położenie auta"),
                "stops" to mapOf("type" to "string", "description" to "route: przystanki po drodze oddzielone średnikiem; inaczej pusty"),
            ),
            listOf("action", "query", "near", "open_now", "fuel_prices", "destination", "origin", "stops"),
        ),
        Def(
            "open_saved_place",
            "Włącza nawigację do zapisanego miejsca: home = Dom, work = Praca, place3 = trzecie miejsce (nazwa w opisie car_status).",
            mapOf("place" to mapOf("type" to "string", "enum" to listOf("home", "work", "place3"))),
            listOf("place"),
        ),
        Def(
            "open_app",
            "Uruchamia aplikację zainstalowaną w radiu (np. YouTube, SoundCloud, Spotify, Mapy, Chrome). Z niepustym search od razu wyszukuje w niej (np. utwór albo film w YouTube). Gdy nie wiesz, jakie aplikacje są w radiu, wywołaj z pustym app – dostaniesz listę.",
            mapOf(
                "app" to mapOf("type" to "string", "description" to "Nazwa aplikacji, np. YouTube"),
                "search" to mapOf("type" to "string", "description" to "Czego szukać w aplikacji; pusty tekst = tylko otwórz"),
            ),
            listOf("app", "search"),
        ),
        Def(
            "play_youtube",
            "Od razu odtwarza w aplikacji YouTube pierwszy film znaleziony dla zapytania (utwór, wykonawca, film, podcast). Użyj, gdy ktoś prosi o włączenie czegoś konkretnego do słuchania lub oglądania.",
            mapOf("query" to mapOf("type" to "string", "description" to "Czego szukać, np. „Dawid Podsiadło Małomiasteczkowy”")),
            listOf("query"),
        ),
        Def(
            "media_control",
            "Steruje odtwarzaczem w radiu (YouTube, SoundCloud, Spotify…) tak jak przyciski w widżecie odtwarzacza: status = co teraz leci, w którym miejscu i ile trwa; play = wznów albo włącz odtwarzanie w tle (aplikacja nie pokazuje się na ekranie); pause, next, previous; seek_by = przewiń o tyle sekund (ujemne = do tyłu); seek_to = ustaw na danej sekundzie od początku (0 = od początku). Prośby typu „wznów / puść dalej / włącz SoundCloud / puść muzykę z SoundCloud” to play tutaj z nazwą w app – NIE otwieraj aplikacji przez open_app. Aplikację otwórz tylko, gdy to narzędzie zwróci, że nie da się włączyć, albo gdy ktoś chce ją zobaczyć.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("status", "play", "pause", "next", "previous", "seek_by", "seek_to")),
                "app" to mapOf("type" to "string", "description" to "Którego odtwarzacza dotyczy, np. SoundCloud; pusty tekst = ten, który gra albo grał ostatnio"),
                "seconds" to mapOf("type" to "integer", "description" to "Dla seek_by: o ile sekund przewinąć (10 minut = 600, do tyłu ujemne); dla seek_to: sekunda od początku (45. minuta = 2700); inaczej 0"),
            ),
            listOf("action", "app", "seconds"),
        ),
        Def(
            "camera_snapshot",
            "Robi zdjęcie z kamery Xiaomi (aktualna klatka) i pokazuje Ci je, żebyś mógł opisać, co widać – np. czy ktoś jest przed domem, czy auto stoi, jaka pogoda. Pusta nazwa = kamera z bloku Kamera; zła nazwa zwraca listę kamer.",
            mapOf("camera" to mapOf("type" to "string", "description" to "Nazwa kamery, np. „Dom przód”; pusty tekst = bieżąca")),
            listOf("camera"),
        ),
        Def(
            "camera_control",
            "Steruje kamerą Xiaomi z obrotem: list = kamery i co umieją, move = obrót w kierunku direction przez amount sekund (0.1–4; ok. 1 s to niewielki obrót), points = zapisane punkty 1–3, goto = obrót do punktu nr amount, zoom = przybliżenie amount (1–9). Po obrocie zrób camera_snapshot, żeby zobaczyć efekt. Pusta nazwa kamery = kamera z bloku Kamera.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("list", "move", "points", "goto", "zoom")),
                "camera" to mapOf("type" to "string", "description" to "Nazwa kamery; pusty tekst = bieżąca"),
                "direction" to mapOf("type" to "string", "enum" to listOf("left", "right", "up", "down", "none")),
                "amount" to mapOf("type" to "number", "description" to "move: sekundy, goto: numer punktu, zoom: przybliżenie; inaczej 0"),
            ),
            listOf("action", "camera", "direction", "amount"),
        ),
        Def(
            "home_ac",
            "Klimatyzator w domu (Smart Life): status = temperatura w domu, wilgotność, czy działa, ustawiona temperatura i tryb; on / off; set_temp = ustaw temperaturę value (16–31 °C, co 0,5); mode = tryb (value: 0 chłodzenie, 1 grzanie, 2 osuszanie, 3 wentylator, 4 auto); fan = prędkość nawiewu (value = numer z listy w status, od 0). Np. przed powrotem do domu włącz chłodzenie.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("status", "on", "off", "set_temp", "mode", "fan")),
                "value" to mapOf("type" to "number", "description" to "set_temp: °C, mode: numer trybu; inaczej 0"),
            ),
            listOf("action", "value"),
        ),
        Def(
            "calendar",
            "Kalendarz Google właściciela (zsynchronizowany w radiu). list = terminy od dnia date (RRRR-MM-DD, pusty = dziś) przez days dni; zwraca też dzisiejszą datę i godzinę. add = nowy termin: title, date = początek „RRRR-MM-DD GG:MM” (sama data = cały dzień), minutes = czas trwania, location = miejsce (może być puste). Przed add, gdy nie znasz dzisiejszej daty (np. „jutro”), najpierw wywołaj list. Gdy termin ma miejsce, a ktoś chce tam jechać, użyj navigate.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("list", "add")),
                "date" to mapOf("type" to "string", "description" to "list: pierwszy dzień RRRR-MM-DD albo pusty; add: RRRR-MM-DD GG:MM albo RRRR-MM-DD"),
                "days" to mapOf("type" to "integer", "description" to "list: liczba dni (1 = jeden dzień, 7 = tydzień); add: 0"),
                "title" to mapOf("type" to "string", "description" to "add: tytuł terminu; list: pusty"),
                "minutes" to mapOf("type" to "integer", "description" to "add: czas trwania w minutach (np. 60); list: 0"),
                "location" to mapOf("type" to "string", "description" to "add: miejsce albo pusty; list: pusty"),
            ),
            listOf("action", "date", "days", "title", "minutes", "location"),
        ),
        Def(
            "slack",
            "Slack właściciela (workspace Web Systems), w jego imieniu. unread = rozmowy z nieprzeczytanymi; read = ostatnie wiadomości z rozmowy target (kanał np. „#ogolny” albo imię osoby; thread = ts wątku, żeby przeczytać odpowiedzi); search = szukaj query w wiadomościach (składnia Slacka, np. „from:@Marek faktura”); send = wiadomość text do target (thread = ts, żeby odpowiedzieć w wątku). WYSYŁANIE: pierwsze send (confirmed=false) tylko przygotowuje szkic – przeczytaj adresata i treść i zapytaj, czy wysłać; send z confirmed=true dopiero po wyraźnym „tak” w następnej wypowiedzi. Czytając wiadomości na głos, streszczaj zwięźle.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf("unread", "read", "search", "send")),
                "target" to mapOf("type" to "string", "description" to "read/send: kanał albo osoba; inaczej pusty"),
                "query" to mapOf("type" to "string", "description" to "search: czego szukać; inaczej pusty"),
                "text" to mapOf("type" to "string", "description" to "send: treść wiadomości; inaczej pusty"),
                "thread" to mapOf("type" to "string", "description" to "ts wątku albo pusty"),
                "count" to mapOf("type" to "integer", "description" to "read/search: ile wiadomości (np. 10); inaczej 0"),
                "confirmed" to mapOf("type" to "boolean", "description" to "send: true tylko po wyraźnym potwierdzeniu użytkownika; inaczej false"),
            ),
            listOf("action", "target", "query", "text", "thread", "count", "confirmed"),
        ),
        Def(
            "radio_control",
            "Obsługa radia i Kokpitu. action: volume_set (value 0–100 %), volume_up, volume_down, mute, unmute, brightness_set (value 0–100 %), idrive_screen (oryginalny ekran BMW iDrive), split_screen (Mapy + YouTube obok siebie), radio_settings (ustawienia radia Witstek), android_settings, home (ekran Kokpitu), theme (value: 0 = Płaski, 1 = BMW iDrive, 2 = M Sport), status (głośność i jasność). Dla akcji bez liczby podaj value 0.",
            mapOf(
                "action" to mapOf("type" to "string", "enum" to listOf(
                    "status", "volume_set", "volume_up", "volume_down", "mute", "unmute", "brightness_set",
                    "idrive_screen", "split_screen", "radio_settings", "android_settings", "home", "theme",
                )),
                "value" to mapOf("type" to "integer", "description" to "Liczba dla volume_set, brightness_set i theme; inaczej 0"),
            ),
            listOf("action", "value"),
        ),
    )

    fun run(act: MainActivity, name: String, input: JSONObject): ToolOut =
        if (!Access.allows(name)) ToolOut("Właściciel wyłączył ten dostęp w Ustawieniach Kokpitu → Asystent AI → Dostępy.")
        else if (name == "camera_snapshot") snapshot(act, input.optString("camera").trim()) else ToolOut(runText(act, name, input))

    /** Klatka z kamery: go2rtc w Xiaomi Cam daje krótki plik api/frame.mp4, z którego bierzemy pierwszy obraz. */
    fun snapshot(act: MainActivity, wanted: String): ToolOut {
        val names = kotlinx.coroutines.runBlocking { XiaomiCamServer.camerasWaking(act) }
            ?: return ToolOut("Xiaomi Cam nie odpowiada – kamera niedostępna (aplikacja i jej serwer w tle muszą działać)")
        val cams = names.filter { !it.startsWith("__") }
        val w = wanted.lowercase()
        val cam = when {
            wanted.isEmpty() -> act.liveCamera.value.takeIf { it in cams } ?: cams.firstOrNull()
            else -> cams.firstOrNull { it.lowercase() == w } ?: cams.firstOrNull { w in it.lowercase() || it.lowercase() in w }
        } ?: return ToolOut("Nie ma kamery „$wanted”. Kamery: ${cams.joinToString()}")
        val f = java.io.File(act.cacheDir, "frame.mp4")
        val c = URL("http://127.0.0.1:11984/api/frame.mp4?src=" + java.net.URLEncoder.encode(cam, "UTF-8").replace("+", "%20")).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 30000
        if (c.responseCode != 200) return ToolOut("Kamera „$cam” nie dała obrazu (HTTP ${c.responseCode})")
        c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        val mmr = android.media.MediaMetadataRetriever()
        val bmp = try {
            mmr.setDataSource(f.path)
            mmr.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            mmr.release()
        } ?: return ToolOut("Nie udało się odczytać klatki z kamery „$cam”")
        val scale = minOf(1f, 1280f / bmp.width)
        val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        val time = java.text.SimpleDateFormat("HH:mm", Locale("pl", "PL")).format(java.util.Date())
        return ToolOut("Zdjęcie z kamery „$cam” zrobione o $time.", android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
    }

    private fun runText(act: MainActivity, name: String, input: JSONObject): String = when (name) {
        "car_status" -> carStatus(act)
        "navigate" -> {
            val dest = input.optString("destination").trim()
            val stops = Places.stops(input.optString("stops"))
            if (dest.isEmpty()) "Brak celu" else {
                act.runOnUiThread { navigateTo(act, dest, stops) }
                "Nawigacja do „$dest” uruchomiona w Mapach Google" + if (stops.isEmpty()) "" else " z przystankami: " + stops.joinToString()
            }
        }
        "places" -> Places.run(act, input)
        "open_saved_place" -> {
            when (input.optString("place")) {
                "home" -> act.runOnUiThread { goSaved(act, Prefs.home, false) }
                "work" -> act.runOnUiThread { goSaved(act, Prefs.work, true) }
                else -> act.runOnUiThread { goPlace3(act) }
            }
            "Nawigacja uruchomiona"
        }
        "play_youtube" -> playYoutube(act, input.optString("query").trim())
        "media_control" -> mediaControl(act, input.optString("action"), input.optString("app").trim(), input.optLong("seconds"))
        "camera_control" -> cameraControl(act, input)
        "calendar" -> calendar(act, input)
        "slack" -> Slack.run(input)
        "home_ac" -> homeAc(input.optString("action"), input.optDouble("value", 0.0))
        "radio_control" -> radioControl(act, input.optString("action"), input.optInt("value"))
        "open_app" -> openApp(act, input.optString("app").trim(), input.optString("search").trim())
        else -> "Nieznane narzędzie $name"
    }

    private const val YOUTUBE = "com.google.android.youtube"

    private fun calendar(act: MainActivity, input: JSONObject): String {
        if (!KokpitCalendar.granted(act) && !act.requestPermissionsBlocking(KokpitCalendar.PERMISSIONS))
            return "Brak zgody na dostęp do kalendarza – na ekranie radia trzeba zezwolić Kokpitowi na kalendarz."
        return when (input.optString("action")) {
            "add" -> KokpitCalendar.add(act, input.optString("title"), input.optString("date"), input.optInt("minutes", 60).takeIf { it > 0 } ?: 60, input.optString("location"))
            else -> KokpitCalendar.list(act, input.optString("date"), input.optInt("days", 1).coerceAtLeast(1))
        }
    }

    /** Pierwszy film z wyników wyszukiwania YouTube (strona www, bez klucza API) i odtworzenie go w aplikacji. */
    private fun playYoutube(act: MainActivity, query: String): String {
        if (query.isEmpty()) return "Brak zapytania"
        val c = URL("https://www.youtube.com/results?hl=pl&search_query=" + java.net.URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
        c.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
        // bez tego w UE YouTube pokazuje stronę zgody na ciasteczka zamiast wyników
        c.setRequestProperty("Cookie", "SOCS=CAI; CONSENT=YES+1")
        val html = c.inputStream.bufferedReader().use { it.readText() }
        val m = Regex(""""videoRenderer":\{"videoId":"([\w-]{11})".*?"title":\{"runs":\[\{"text":"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL).find(html)
        val id = m?.groupValues?.get(1) ?: Regex(""""videoId":"([\w-]{11})"""").find(html)?.groupValues?.get(1)
            ?: return "Nic nie znaleziono w YouTube dla „$query”"
        val title = m?.groupValues?.get(2)?.let { runCatching { JSONObject("{\"t\":\"$it\"}").getString("t") }.getOrDefault(it) }
        act.runOnUiThread {
            val uri = android.net.Uri.parse("https://www.youtube.com/watch?v=$id")
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                act.startActivity(android.content.Intent(i).setPackage(YOUTUBE))
            } catch (_: Exception) {
                runCatching { act.startActivity(i) }
            }
        }
        return "Odtwarzam w YouTube: ${title ?: query}"
    }

    /** Sterowanie odtwarzaczem przez MediaSession; wymaga dostępu do powiadomień (ten sam co trasa z Map). */
    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Pozycja odtwarzania teraz (stan sesji podaje ją z chwili ostatniej zmiany). */
    private fun position(st: android.media.session.PlaybackState?): Long {
        st ?: return 0
        if (st.state != android.media.session.PlaybackState.STATE_PLAYING || st.lastPositionUpdateTime <= 0) return st.position
        val speed = st.playbackSpeed.takeIf { it > 0f } ?: 1f
        return st.position + ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * speed).toLong()
    }

    private var browser: android.media.browse.MediaBrowser? = null

    /**
     * Włącza odtwarzanie w aplikacji, która nie ma sesji (nie działa albo dawno wstrzymana), bez pokazywania jej:
     * łączy się z jej usługą multimediów (tak jak Android Auto) i wysyła „graj”. Zwraca false, gdy aplikacja
     * nie ma takiej usługi albo nie wpuszcza Kokpitu.
     */
    private fun playInBackground(act: MainActivity, pkg: String): Boolean {
        val service = runCatching {
            act.packageManager.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
        }.getOrDefault(emptyList()).firstOrNull()?.serviceInfo ?: return false
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            runCatching { browser?.disconnect() }
            var b: android.media.browse.MediaBrowser? = null
            b = android.media.browse.MediaBrowser(act, android.content.ComponentName(service.packageName, service.name),
                object : android.media.browse.MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        ok = runCatching {
                            android.media.session.MediaController(act, b!!.sessionToken).transportControls.play()
                        }.isSuccess
                        done.countDown()
                    }
                    override fun onConnectionFailed() = done.countDown()
                    override fun onConnectionSuspended() = done.countDown()
                }, null)
            browser = b
            runCatching { b.connect() }.onFailure { done.countDown() }
            // połączenie trzyma usługę odtwarzacza przy życiu, aż sama zacznie grać na pierwszym planie
            main.postDelayed({ if (browser === b) { runCatching { b.disconnect() }; browser = null } }, 20000)
        }
        done.await(8, java.util.concurrent.TimeUnit.SECONDS)
        android.util.Log.i("KokpitMedia", "w tle $pkg: ${if (ok) "wysłano graj" else "brak połączenia z usługą"}")
        return ok
    }

    private fun mediaControl(act: MainActivity, action: String, wanted: String, seconds: Long): String {
        val pm = act.packageManager
        fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        fun matches(pkg: String) = wanted.isEmpty() || label(pkg).contains(wanted, true) || pkg.contains(wanted.replace(" ", ""), true)
        fun active() = if (NavListener.isEnabled(act)) runCatching {
            act.getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(android.content.ComponentName(act, NavListener::class.java))
        }.getOrDefault(emptyList()) else emptyList()
        val sessions = active()
        val playingState = android.media.session.PlaybackState.STATE_PLAYING
        val s = sessions.filter { matches(it.packageName) }.let { list ->
            list.firstOrNull { it.playbackState?.state == playingState } ?: list.firstOrNull()
        }
        if (s == null) {
            if (action == "status") return if (sessions.isEmpty()) "Żaden odtwarzacz nie jest otwarty" else "Otwarte odtwarzacze: " + sessions.joinToString { label(it.packageName) }
            if (action == "seek_by" || action == "seek_to") return "Nie ma czego przewijać – ${if (wanted.isEmpty()) "żaden odtwarzacz" else wanted} teraz nie gra. Najpierw włącz odtwarzanie (play)."
            val target = wanted.takeIf { it.isNotEmpty() }?.let { w -> act.apps.value.firstOrNull { it.label.contains(w, true) }?.packageName }
                ?: Prefs.lastMediaPkg.takeIf { wanted.isEmpty() && it.isNotEmpty() }
            if (wanted.isNotEmpty() && target == null) return "Nie ma w radiu aplikacji „$wanted”"
            fun playsNow() = active().any { it.playbackState?.state == playingState && (target == null || it.packageName == target) }
            fun waitPlaying(ms: Long): Boolean {
                val until = android.os.SystemClock.elapsedRealtime() + ms
                while (android.os.SystemClock.elapsedRealtime() < until) { if (playsNow()) return true; Thread.sleep(300) }
                return playsNow()
            }
            val name = target?.let(::label) ?: "ostatniego odtwarzacza"
            // 1. usługa multimediów aplikacji – działa też, gdy aplikacja w ogóle nie jest uruchomiona
            if (action == "play" && target != null && playInBackground(act, target) && waitPlaying(6000)) return "$name gra w tle."
            // 2. przycisk multimediów jak w widżecie – odtwarzacz sam wznawia muzykę bez otwierania aplikacji
            val code = when (action) {
                "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                else -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            if (target != null) {
                for (a in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    act.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(target)
                        .putExtra(android.content.Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(a, code)))
                }
            } else {
                val am = act.getSystemService(android.media.AudioManager::class.java)
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            }
            val nowPlaying = NavListener.isEnabled(act) && waitPlaying(3000)
            return if (nowPlaying) "$name gra w tle."
            else "Wysłano przycisk $action do $name, ale nie widać, żeby grał. Jeśli rozmówca potwierdzi, że nie gra, otwórz aplikację przez open_app."
        }
        val app = label(s.packageName)
        val md = s.metadata
        val st = s.playbackState
        val now = listOfNotNull(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" – ")
        val playing = st?.state == playingState
        val length = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val t = s.transportControls
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "seek_by", "seek_to" -> {
                if ((st?.actions ?: 0L) and android.media.session.PlaybackState.ACTION_SEEK_TO == 0L)
                    return "$app nie pozwala teraz przewijać (np. transmisja na żywo albo reklama)"
                var to = if (action == "seek_by") position(st) + seconds * 1000 else seconds * 1000
                var note = ""
                if (to < 0) to = 0
                if (length > 0 && to > length - 3000) {
                    if (action == "seek_to" || to >= length) note = " – dalej się nie da, całość trwa ${clock(length)}"
                    to = (length - 3000).coerceAtLeast(0)
                }
                t.seekTo(to)
                return "$app: ustawiono na ${clock(to)}" + (if (length > 0) " z ${clock(length)}" else "") + note + (if (now.isNotBlank()) " ($now)" else "")
            }
            else -> return "$app: ${if (playing) "gra" else "wstrzymane"}${if (now.isNotBlank()) " – $now" else ""}" +
                (if (length > 0) "; miejsce ${clock(position(st))} z ${clock(length)}" else if (position(st) > 0) "; miejsce ${clock(position(st))}" else "") +
                if ((st?.actions ?: 0L) and android.media.session.PlaybackState.ACTION_SEEK_TO == 0L) "; bez przewijania" else ""
        }
        return "$app: wykonano $action${if (now.isNotBlank()) " (było: $now)" else ""}"
    }

    /** Obrót, punkty i zoom przez Xiaomi Cam (ControlProvider przyjmuje tylko Kokpit). */
    private fun cameraControl(act: MainActivity, input: JSONObject): String {
        val action = input.optString("action")
        val amount = input.optDouble("amount", 0.0)
        val uri = android.net.Uri.parse("content://pl.lawinski.xiaomicam.control")
        fun call(method: String, cam: String, ex: android.os.Bundle = android.os.Bundle()): String {
            val b = try {
                act.contentResolver.call(uri, method, cam, ex)
            } catch (e: IllegalArgumentException) {
                return "Xiaomi Cam nie ma sterowania dla Kokpitu – zaktualizuj ją do wersji 1.35 lub nowszej"
            } catch (e: SecurityException) {
                return "Xiaomi Cam nie pozwala Kokpitowi sterować kamerą"
            } ?: return "Xiaomi Cam nie odpowiada"
            return if (b.getBoolean("ok")) b.getString("result").orEmpty() else "Błąd kamery: ${b.getString("error")}"
        }
        val list = call("cameras", "")
        if (action == "list" || !list.trimStart().startsWith("[")) return list
        val cams = JSONArray(list).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val wanted = input.optString("camera").trim().lowercase()
        val cam = (if (wanted.isEmpty()) cams.firstOrNull { it.getString("name") == act.liveCamera.value } ?: cams.firstOrNull { it.optBoolean("ptz") }
            else cams.firstOrNull { it.getString("name").lowercase() == wanted } ?: cams.firstOrNull { wanted in it.getString("name").lowercase() })
            ?: return "Nie ma takiej kamery. Kamery: $list"
        val name = cam.getString("name")
        return name + ": " + when (action) {
            "move" -> call("move", name, android.os.Bundle().apply {
                putString("direction", input.optString("direction"))
                putInt("ms", (amount.coerceIn(0.1, 4.0) * 1000).toInt())
            })
            "points" -> call("points", name)
            "goto" -> call("goto", name, android.os.Bundle().apply { putInt("slot", amount.toInt()) })
            "zoom" -> call("zoom", name, android.os.Bundle().apply { putDouble("value", amount) })
            else -> "nieznana akcja $action"
        }
    }

    private fun homeAc(action: String, value: Double): String = kotlinx.coroutines.runBlocking {
        if (!Tuya.configured) return@runBlocking "Klimatyzator nie jest połączony (Ustawienia → Dom: klucze Tuya)"
        val r = when (action) {
            "on" -> Tuya.setPower(true)
            "off" -> Tuya.setPower(false)
            "set_temp" -> Tuya.setTarget(value.toFloat())
            "mode" -> Tuya.setMode(Tuya.MODES.getOrNull(value.toInt())?.first ?: return@runBlocking "Zły numer trybu")
            "fan" -> Tuya.setFan(Tuya.fanValues.getOrNull(value.toInt()) ?: return@runBlocking "Zły numer nawiewu")
            else -> "ok"
        }
        if (r != "ok") return@runBlocking r
        val s = Tuya.state.value ?: Tuya.refresh() ?: return@runBlocking Tuya.error.value ?: "Brak odczytu klimatyzatora"
        val sn = Tuya.sensor.value
        "${s.name}: ${if (!s.online) "offline" else if (s.on) "włączony, ${Tuya.modeName(s.mode)}, ustawione ${s.target} °C" else "wyłączony"}; " +
            (if (sn?.temp != null) "w domu ${sn.temp} °C, wilgotność ${sn.humidity ?: s.humidity ?: "?"} % (czujnik „${sn.name}” – to jest prawdziwa temperatura w pokoju; czujnik klimatyzatora pokazuje zawyżone ${s.room ?: "?"} °C)"
            else "w domu ${s.room ?: "?"} °C, wilgotność ${s.humidity ?: "?"} %") +
            (if (s.fan != null) "; nawiew ${Tuya.fanName(s.fan)} (lista: ${Tuya.fanValues.mapIndexed { i, v -> "$i ${Tuya.fanName(v)}" }.joinToString()})" else "")
    }

    private fun radioControl(act: MainActivity, action: String, value: Int): String {
        val am = act.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        fun volPct() = am.getStreamVolume(stream) * 100 / max
        fun ui(block: () -> Unit) = act.runOnUiThread(block)
        val flags = android.media.AudioManager.FLAG_SHOW_UI
        // radio Witstek: słyszalna głośność to wzmacniacz MCU (0–40), Android stoi na maksimum
        val car = CarVolume.mcu(act)
        if (car != null && action in setOf("volume_set", "volume_up", "volume_down")) {
            val target = when (action) {
                "volume_set" -> (value.coerceIn(0, 100) * CarVolume.MAX + 50) / 100
                "volume_up" -> car + 4
                else -> car - 4
            }.coerceIn(0, CarVolume.MAX)
            CarVolume.set(act, car, target)
            return "Głośność radia $target z ${CarVolume.MAX} (${target * 100 / CarVolume.MAX} %)"
        }
        return when (action) {
            "volume_set" -> { am.setStreamVolume(stream, (value.coerceIn(0, 100) * max + 50) / 100, flags); "Głośność ${volPct()} %" }
            "volume_up" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, flags); am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, flags); "Głośność ${volPct()} %" }
            "volume_down" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, flags); am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, flags); "Głośność ${volPct()} %" }
            "mute" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, flags); "Wyciszone" }
            "unmute" -> { am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, flags); "Dźwięk włączony, ${volPct()} %" }
            "brightness_set" -> {
                // zmiana jasności wymaga zgody „Modyfikowanie ustawień systemu” (raz, w ustawieniach Androida)
                if (!android.provider.Settings.System.canWrite(act)) {
                    ui {
                        runCatching {
                            act.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + act.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    "Kokpit potrzebuje zgody na zmianę ustawień systemu – otworzyłem ekran zgody, włącz ją i poproś jeszcze raz"
                } else {
                    val cr = act.contentResolver
                    android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS, (value.coerceIn(1, 100) * 255 / 100).coerceAtLeast(3))
                    "Jasność ${value.coerceIn(1, 100)} %"
                }
            }
            "idrive_screen" -> { ui { openCarScreen(act) }; "Przełączam na ekran BMW iDrive" }
            "split_screen" -> { ui { openSplit(act, Prefs.splitLeft, Prefs.splitRight) }; "Otwieram podzielony ekran" }
            "radio_settings" -> { ui { openRadioSettings(act) }; "Otwieram ustawienia radia" }
            "android_settings" -> { ui { runCatching { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }; "Otwieram ustawienia Androida" }
            "home" -> { ui { act.startActivity(android.content.Intent(act, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }; "Wracam do Kokpitu" }
            "theme" -> {
                val t = Themes.all.getOrNull(value) ?: return "Nie ma wyglądu nr $value"
                ui { themeState.value = t; Prefs.theme = t.id }
                "Wygląd: ${t.name}"
            }
            else -> {
                val bright = runCatching { android.provider.Settings.System.getInt(act.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) * 100 / 255 }.getOrNull()
                "Głośność ${volPct()} %${if (bright != null) ", jasność ok. $bright %" else ""}, wygląd ${themeState.value.name}"
            }
        }
    }

    private fun openApp(act: MainActivity, name: String, search: String): String {
        val apps = act.apps.value
        val list = apps.joinToString { it.label }
        if (name.isEmpty()) return "Aplikacje w radiu: $list"
        val n = name.lowercase()
        val app = apps.firstOrNull { it.label.lowercase() == n }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(n) }
            ?: apps.firstOrNull { n in it.label.lowercase() || it.label.lowercase() in n }
            ?: return "Nie ma aplikacji „$name”. Aplikacje w radiu: $list"
        var searched = false
        act.runOnUiThread {
            if (search.isNotEmpty()) {
                // wyszukiwanie w aplikacji (YouTube, YouTube Music, SoundCloud, Spotify obsługują ACTION_SEARCH)
                searched = try {
                    act.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage(app.packageName)
                            .putExtra(android.app.SearchManager.QUERY, search).putExtra("query", search)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (!searched) Apps.launch(act, app.key)
        }
        return if (search.isNotEmpty()) "Otwieram ${app.label} i szukam „$search”" else "Otwieram ${app.label}"
    }

    private fun carStatus(act: MainActivity): String {
        val c = CarData.status.value
        val loc = act.location.location.value
        val nav = NavState.info.value
        return buildString {
            appendLine("Auto: BMW F11 Touring (kombi), bak ${CarData.TANK_L.toInt()} l")
            appendLine("Paliwo: ${c.fuelL?.let { "${it.toInt()} l" } ?: "brak danych"}")
            c.rangeKm?.let { appendLine("Zasięg: $it km") }
            appendLine("Temperatura zewnętrzna: ${c.outsideC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "brak danych"}")
            c.gear?.let { appendLine("Bieg: $it") }
            appendLine("Prędkość: ${act.location.speedKmh.value?.let { "$it km/h" } ?: "brak danych"}")
            appendLine("Otwarte: ${if (c.open.isEmpty()) "nic" else c.open.joinToString { it.label }}")
            appendLine("Położenie GPS: ${loc?.let { String.format(Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "nieznane"}")
            WeatherState.current.value?.let { w -> appendLine("Pogoda (Open-Meteo): ${w.temp} °C${if (w.place.isNotBlank()) ", ${w.place}" else ""}") }
            appendLine("Trasa w nawigacji: ${nav?.let { listOf(it.title, it.text, it.sub).filter { s -> s.isNotBlank() }.joinToString(" · ") } ?: "brak"}")
            appendLine("Zapisane miejsca: Dom ${Prefs.home.ifBlank { "(z Map Google)" }}, Praca ${Prefs.work.ifBlank { "(z Map Google)" }}, trzecie: ${Prefs.place3Name.ifBlank { "brak" }}")
        }
    }
}

private const val BASE_PROMPT = """Jesteś asystentem w aucie: ekran radia w BMW F11 Touring z launcherem Kokpit F11. Rozmawia z Tobą kierowca albo pasażer.
Odpowiadaj po polsku, krótko i naturalnie, zwykle 1–3 zdaniami – odpowiedzi są czytane na głos, więc pisz zwykłym tekstem, bez list, nagłówków, tabel i znaczników Markdown.
Kierowca nie powinien czytać długich tekstów w czasie jazdy, dlatego najważniejsze mów na początku.
Masz narzędzia: car_status (stan auta, położenie, trasa), navigate i open_saved_place (włączają nawigację w Mapach Google), places (szuka miejsc w Google Maps z ocenami i godzinami otwarcia oraz podaje czas dojazdu z korkami – używaj zamiast zgadywania, gdy ktoś pyta o miejsca w pobliżu albo ile się jedzie), open_app (uruchamia aplikację w radiu i może w niej wyszukać), play_youtube (od razu odtwarza w YouTube to, o co ktoś prosi), media_control (pauza, wznowienie, następny, poprzedni, co teraz gra, przewijanie o podany czas lub do podanego miejsca, włączenie wybranego odtwarzacza w tle) i radio_control (głośność, jasność, ekran BMW iDrive, podzielony ekran, ustawienia, wygląd Kokpitu) camera_control (obrót kamery, punkty, zoom), home_ac (klimatyzator w domu: temperatura, wł./wył., ustawienie), calendar (kalendarz Google właściciela: terminy i dodawanie nowych), slack (Slack właściciela: nieprzeczytane, czytanie, szukanie i wysyłanie – zawsze z potwierdzeniem) i camera_snapshot (aktualne zdjęcie z kamery Xiaomi – gdy ktoś pyta, co widać na kamerze, zawsze zrób nowe zdjęcie). Gdy ktoś prosi o włączenie muzyki lub filmu, użyj play_youtube. Gdy pytanie dotyczy auta, paliwa, miejsca albo trasy, najpierw sprawdź car_status. Gdy ktoś prosi o trasę, od razu włącz nawigację i krótko to potwierdź."""

/** Prompt systemowy: stały opis roli + instrukcje właściciela z Ustawień (jak się zwracać, styl…). */
private fun systemPrompt(): String {
    val own = Prefs.aiInstructions.trim()
    val base = BASE_PROMPT + Access.promptNote() + Speech.tagNote()
    return if (own.isEmpty()) base
    else "$base\n\nInstrukcje od właściciela auta (stosuj je zawsze, chyba że przeczą bezpieczeństwu w czasie jazdy):\n$own"
}

/** Rozmowa z jednym dostawcą; trzyma całą historię, żeby asystent pamiętał kontekst. */
private interface ChatEngine {
    /** Wysyła wypowiedź i zwraca odpowiedź (po wykonaniu narzędzi). Wywoływać poza wątkiem UI. */
    fun send(text: String, jpeg: String? = null): String

    /** Rozmiar rozmowy w tokenach po ostatnim zapytaniu (cała historia + odpowiedź). */
    val tokens: Long

    /** Historia do zapisu w pliku (przetrwa restart radia) i jej odtworzenie. */
    fun save(): JSONArray
    fun load(saved: JSONArray, tokens: Long)
}

private class ClaudeEngine(private val act: MainActivity, apiKey: String, private val model: String) : ChatEngine {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    private val history = mutableListOf<MessageParam>()
    override var tokens = 0L
        private set

    override fun save() = JSONArray().apply { history.forEach { put(JSONObject(jsonMapper().writeValueAsString(it))) } }

    override fun load(saved: JSONArray, tokens: Long) {
        history.clear()
        for (i in 0 until saved.length()) history += jsonMapper().readValue(saved.getJSONObject(i).toString(), MessageParam::class.java)
        this.tokens = tokens
    }

    private val tools = AssistantTools.all.filter { Access.allows(it.name) }.map { t ->
        Tool.builder()
            .name(t.name)
            .description(t.description)
            .strict(true)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(Tool.InputSchema.Properties.builder().apply {
                        t.props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                    }.build())
                    .required(t.required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .build()
    }

    override fun send(text: String, jpeg: String?): String {
        history += if (jpeg == null) MessageParam.ofUser(text) else MessageParam.ofUserOfContentBlockParams(listOf(
            ContentBlockParam.ofImage(
                com.anthropic.models.messages.ImageBlockParam.builder().source(
                    com.anthropic.models.messages.Base64ImageSource.builder()
                        .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG).data(jpeg).build()
                ).build()
            ),
            ContentBlockParam.ofText(com.anthropic.models.messages.TextBlockParam.builder().text(text).build()),
        ))
        repeat(8) {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt())
                .messages(history.toList())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .apply { tools.forEach { addTool(it) } }
                .apply { if (Access.allows(Access.WEB)) addTool(WebSearchTool20260209.builder().maxUses(3L).build()) }
                // przy odmowie filtrów bezpieczeństwa serwer sam ponawia na innym modelu
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                // długa rozmowa: wcześniejsza część z pamięci podręcznej – znacznie taniej
                .cacheControl(CacheControlEphemeral.builder().build())
                .build()
            val msg = client.messages().create(params)
            msg.usage().let { u ->
                tokens = u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L) + u.outputTokens()
            }
            // całą odpowiedź (z blokami myślenia) oddajemy bez zmian w kolejnym zapytaniu
            history += msg.toParam()
            val reply = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
            when (msg.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val results = msg.content().mapNotNull { b -> b.toolUse().orElse(null) }.map { use ->
                        val out = try {
                            AssistantTools.run(act, use.name(), JSONObject(use._input().convert(Map::class.java) as Map<*, *>))
                        } catch (e: Exception) {
                            ToolOut("Błąd: ${e.message}")
                        }
                        val b = ToolResultBlockParam.builder().toolUseId(use.id())
                        if (out.jpeg == null) b.content(out.text)
                        else b.contentOfBlocks(listOf(
                            ToolResultBlockParam.Content.Block.ofText(out.text),
                            ToolResultBlockParam.Content.Block.ofImage(
                                com.anthropic.models.messages.Base64ImageSource.builder()
                                    .mediaType(com.anthropic.models.messages.Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(out.jpeg).build()
                            ),
                        ))
                        ContentBlockParam.ofToolResult(b.build())
                    }
                    history += MessageParam.ofUserOfContentBlockParams(results)
                }
                StopReason.PAUSE_TURN -> {} // wyszukiwanie w sieci trwa – kontynuujemy
                StopReason.REFUSAL -> return reply.ifEmpty { "Nie mogę w tym pomóc." }
                StopReason.MAX_TOKENS -> return reply.ifEmpty { "Odpowiedź była za długa." }
                else -> return reply.ifEmpty { "…" }
            }
        }
        return "Nie udało się dokończyć odpowiedzi."
    }
}

/** OpenAI: Chat Completions przez zwykłe HTTPS (bez dodatkowej biblioteki). */
private class OpenAiEngine(private val act: MainActivity, private val apiKey: String, private val model: String) : ChatEngine {
    private var history = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt()))
    override var tokens = 0L
        private set

    override fun save() = history

    override fun load(saved: JSONArray, tokens: Long) {
        if (saved.length() == 0) return
        history = saved
        history.put(0, JSONObject().put("role", "system").put("content", systemPrompt()))
        this.tokens = tokens
    }

    private val tools = JSONArray().apply {
        AssistantTools.all.filter { Access.allows(it.name) }.forEach { t ->
            put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", t.name)
                .put("description", t.description)
                .put("strict", true)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject(t.props))
                    .put("required", JSONArray(t.required))
                    .put("additionalProperties", false))))
        }
    }

    override fun send(text: String, jpeg: String?): String {
        history.put(JSONObject().put("role", "user").put("content", if (jpeg == null) text else JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpeg")))))
        repeat(8) {
            val body = JSONObject().put("model", model).put("messages", history).put("tools", tools)
            val res = post(body)
            res.optJSONObject("usage")?.let { tokens = it.optLong("total_tokens", tokens) }
            val msg = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            val images = mutableListOf<String>()
            if (calls == null || calls.length() == 0) return msg.optString("content").trim().ifEmpty { "…" }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val out = try {
                    AssistantTools.run(act, fn.getString("name"), JSONObject(fn.optString("arguments").ifBlank { "{}" }))
                } catch (e: Exception) {
                    ToolOut("Błąd: ${e.message}")
                }
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out.text + if (out.jpeg != null) " Zdjęcie w następnej wiadomości." else ""))
                out.jpeg?.let { images += it }
            }
            // wiadomość narzędzia w Chat Completions nie przyjmuje obrazu – wysyłamy go zaraz po wynikach
            if (images.isNotEmpty()) {
                history.put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", "Zdjęcie z kamery do opisania:"))
                    images.forEach { put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it"))) }
                }))
                images.clear()
            }
        }
        return "Nie udało się dokończyć odpowiedzi."
    }

    private fun post(body: JSONObject): JSONObject {
        val c = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val err = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
            throw OpenAiError(code, err ?: text.take(200))
        }
        return JSONObject(text)
    }
}

private class OpenAiError(val code: Int, message: String) : Exception(message)

/** Stan rozmowy widoczny w bloku; przetrwa zamknięcie bloku i zmianę układu. */
object Assistant {
    /** Do testów przez ADB (MainActivity.testHook): narzędzie asystenta bez pytania modelu. */
    fun testTool(act: MainActivity, name: String, input: JSONObject): String = AssistantTools.run(act, name, input).text

    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai"
    const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    const val DEFAULT_OPENAI_MODEL = "gpt-5"

    val lines = mutableStateListOf<ChatLine>()
    val busy = mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: ChatEngine? = null
    private var engineKey = ""

    fun providerName() = if (Prefs.aiProvider == OPENAI) "OpenAI" else "Claude"

    fun hasKey() = (if (Prefs.aiProvider == OPENAI) Prefs.openaiKey else Prefs.anthropicKey).isNotBlank()

    /** rozmiar bieżącej rozmowy w tokenach (pokazywany w bloku i w Ustawieniach) */
    val tokens = mutableStateOf(0L)

    /** Zapis rozmowy w plikach aplikacji – po restarcie radia ta sama rozmowa z historią. */
    private var file: java.io.File? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Wczytuje zapisaną rozmowę (wołane w MainActivity.onCreate). */
    fun init(ctx: android.content.Context) {
        if (file != null) return
        val f = java.io.File(ctx.filesDir, "assistant_chat.json")
        file = f
        val j = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val l = j.optJSONArray("lines") ?: JSONArray()
        lines.clear()
        for (i in 0 until l.length()) l.optJSONObject(i)?.let { lines += ChatLine(it.optBoolean("user"), it.optString("text")) }
        tokens.value = j.optLong("tokens")
    }

    /** Nowa rozmowa (też po zmianie dostawcy, klucza albo modelu). */
    fun reset() {
        engine = null
        engineKey = ""
        lines.clear()
        tokens.value = 0L
        val f = file
        io.execute { f?.delete() }
    }

    private fun engineId(): String {
        val p = Prefs.aiProvider
        val key = if (p == OPENAI) Prefs.openaiKey else Prefs.anthropicKey
        val model = if (p == OPENAI) Prefs.openaiModel else Prefs.anthropicModel
        return "$p|$key|$model|${Prefs.aiInstructions.hashCode()}"
    }

    /** w pliku tylko skrót – bez klucza API */
    private fun engineTag(id: String) = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    /** Silnik rozmowy; nowy odtwarza historię z pliku, jeśli to ten sam dostawca, model i instrukcje. Poza wątkiem UI. */
    private fun engine(act: MainActivity): ChatEngine {
        val p = Prefs.aiProvider
        val id = engineId()
        engine?.let { if (id == engineKey) return it }
        val key = if (p == OPENAI) Prefs.openaiKey else Prefs.anthropicKey
        val model = if (p == OPENAI) Prefs.openaiModel else Prefs.anthropicModel
        val e: ChatEngine = if (p == OPENAI) OpenAiEngine(act, key, model) else ClaudeEngine(act, key, model)
        runCatching {
            val j = JSONObject(file!!.readText())
            if (j.optString("engine") == engineTag(id)) e.load(j.getJSONArray("history"), j.optLong("tokens"))
        }
        engine = e
        engineKey = id
        return e
    }

    private fun save(e: ChatEngine) {
        val f = file ?: return
        val l = JSONArray().apply { lines.forEach { put(JSONObject().put("user", it.fromUser).put("text", it.text)) } }
        val tag = engineTag(engineKey)
        val history = e.save().toString()
        val t = e.tokens
        io.execute {
            runCatching {
                val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(JSONObject().put("engine", tag).put("tokens", t).put("lines", l).put("history", JSONArray(history)).toString())
                tmp.renameTo(f)
            }
        }
    }

    /**
     * [shown] – co pokazać w rozmowie zamiast pełnej treści (reguły wysyłają dłuższe polecenie);
     * [speak] – czytaj odpowiedź na głos nawet przy wyłączonym czytaniu (reguły).
     */
    fun send(act: MainActivity, text: String, shown: String? = null, speak: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        if (!hasKey()) {
            lines += ChatLine(false, "Wpisz klucz API ${providerName()} w Ustawieniach → Asystent.")
            return
        }
        // limit z Ustawień: rozmowa za długa (drogo i wolno) – zaczynamy czystą
        if (tokens.value >= Prefs.aiTokenLimit) {
            reset()
            lines += ChatLine(false, "Rozmowa przekroczyła limit tokenów – zaczynam nową.")
        }
        lines += ChatLine(true, shown ?: t)
        Slack.turn++ // potwierdzenie wysłania wiadomości Slack musi przyjść w nowej wypowiedzi
        busy.value = true
        scope.launch {
            var ok: ChatEngine? = null
            val reply = try {
                withContext(Dispatchers.IO) {
                    val e = engine(act)
                    e.send(t).also { ok = e }
                }
            } catch (e: Exception) {
                engine = null // historia mogła się urwać w połowie narzędzi – wracamy do ostatniego zapisu
                errorText(e)
            }
            busy.value = false
            lines += ChatLine(false, reply)
            ok?.let { tokens.value = it.tokens; save(it) }
            if (Prefs.aiSpeak || speak) act.speak(reply)
            // bez bloku „Asystent” na ekranie (wywołanie przyciskiem głosowym) pokaż odpowiedź w dymku
            if (!VoiceActivity.open && act.layout.value.none { it.id == BlockId.ASSISTANT }) android.widget.Toast.makeText(act, Speech.plain(reply), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Wynik [look]: ok = jest odpowiedź asystenta; inaczej [text] mówi, czego zabrakło. */
    class Look(val ok: Boolean, val text: String)

    /**
     * Jednorazowe pytanie o aktualny obraz z kamery (reguły z kamerą): osobna, pusta historia – nie miesza się
     * z rozmową właściciela. [prompt] dostaje opis zdjęcia (kamera i godzina). Wywoływać poza wątkiem UI.
     */
    fun look(act: MainActivity, camera: String, prompt: (String) -> String): Look {
        if (!hasKey()) return Look(false, "brak klucza API")
        if (!Access.allows("camera_snapshot")) return Look(false, "dostęp asystenta do kamer jest wyłączony")
        val shot = try {
            AssistantTools.snapshot(act, camera)
        } catch (e: Exception) {
            return Look(false, "kamera: ${e.message}")
        }
        val jpeg = shot.jpeg ?: return Look(false, shot.text)
        return try {
            val p = Prefs.aiProvider
            val e: ChatEngine = if (p == OPENAI) OpenAiEngine(act, Prefs.openaiKey, Prefs.openaiModel) else ClaudeEngine(act, Prefs.anthropicKey, Prefs.anthropicModel)
            Look(true, e.send(prompt(shot.text), jpeg))
        } catch (e: Exception) {
            Look(false, errorText(e))
        }
    }

    private fun errorText(e: Exception): String = when (e) {
        is UnauthorizedException -> "Zły klucz API Anthropic (Ustawienia → Asystent)."
        is PermissionDeniedException -> "Klucz API Anthropic nie ma dostępu do modelu ${Prefs.anthropicModel}."
        is RateLimitException -> "Za dużo zapytań albo brak środków na koncie Anthropic. Spróbuj za chwilę."
        is OverloadedException -> "Serwery Claude są przeciążone. Spróbuj za chwilę."
        is AnthropicServiceException -> "Błąd Claude (${e.statusCode()}): ${e.message}"
        is AnthropicIoException -> "Brak połączenia z internetem."
        is OpenAiError -> when (e.code) {
            401 -> "Zły klucz API OpenAI (Ustawienia → Asystent)."
            429 -> "Limit zapytań albo brak środków na koncie OpenAI."
            else -> "Błąd OpenAI (${e.code}): ${e.message}"
        }
        is java.io.IOException -> "Brak połączenia z internetem."
        else -> "Błąd: ${e.message}"
    }
}
