package pl.lawinski.radiolauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * YouTube dla asystenta przez oficjalne YouTube Data API v3 (zamiast czytania strony wyników):
 *  – z samym kluczem Google (ten sam co „Klucz Google Maps”, w projekcie włączone „YouTube Data API v3”):
 *    szukanie filmów, playlist, kanałów i transmisji, najnowsze filmy kanału;
 *  – po zalogowaniu konta (OAuth „TV i urządzenia z ograniczoną możliwością wpisywania”: radio pokazuje kod,
 *    właściciel wpisuje go na telefonie na google.com/device): własne playlisty, polubione, subskrypcje.
 * Zakres tylko do odczytu (youtube.readonly). Identyfikator i sekret klienta OAuth oraz token odświeżania
 * zostają w Prefs radia. Samo odtwarzanie dalej robi aplikacja YouTube.
 */
object YouTubeApi {
    private const val API = "https://www.googleapis.com/youtube/v3/"
    private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val NO_LOGIN = "Konto YouTube nie jest zalogowane – właściciel loguje je w Ustawieniach Kokpitu → Asystent AI → Dostępy → „Konto YouTube”."

    val hasKey get() = Prefs.googleKey.isNotBlank()
    val hasClient get() = Prefs.ytClientId.isNotBlank() && Prefs.ytClientSecret.isNotBlank()
    val signedIn get() = Prefs.ytRefresh.isNotBlank()
    /** da się pytać API (kluczem albo kontem) */
    val usable get() = hasKey || signedIn

    /** odświeżanie kafelków w Ustawieniach */
    val version = mutableStateOf(0)

    class ApiError(message: String) : Exception(message)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(method: String, url: String, form: String? = null, bearer: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.requestMethod = method
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        if (form != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    }

    private fun form(vararg p: Pair<String, String>) = p.joinToString("&") { enc(it.first) + "=" + enc(it.second) }

    // ---------- logowanie kodem (OAuth 2.0 dla urządzeń) ----------

    class Login(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int, val expiresAt: Long)

    fun startLogin(): Login {
        if (!hasClient) throw ApiError("Najpierw wpisz identyfikator i sekret klienta OAuth.")
        val (code, text) = http("POST", "https://oauth2.googleapis.com/device/code", form("client_id" to Prefs.ytClientId, "scope" to SCOPE))
        val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code !in 200..299) throw ApiError(
            when (j.optString("error")) {
                "invalid_client" -> "Google nie zna tego identyfikatora klienta – sprawdź, czy jest wklejony w całości i czy klient ma typ „TV i urządzenia z ograniczoną możliwością wpisywania”."
                "invalid_scope" -> "Ten klient OAuth nie może prosić o dostęp do YouTube."
                else -> "Logowanie nie ruszyło (HTTP $code ${j.optString("error")})."
            }
        )
        return Login(
            j.getString("device_code"), j.getString("user_code"), j.optString("verification_url", "https://www.google.com/device"),
            j.optInt("interval", 5).coerceAtLeast(3), System.currentTimeMillis() + j.optInt("expires_in", 1800) * 1000L,
        )
    }

    /** Czeka, aż właściciel wpisze kod na telefonie. null = zalogowano, inaczej powód; [alive] = okno wciąż otwarte. */
    fun awaitLogin(login: Login, alive: () -> Boolean): String? {
        var wait = login.intervalSec
        while (alive() && System.currentTimeMillis() < login.expiresAt) {
            Thread.sleep(wait * 1000L)
            if (!alive()) return "przerwane"
            val (code, text) = try {
                http("POST", TOKEN_URL, form(
                    "client_id" to Prefs.ytClientId, "client_secret" to Prefs.ytClientSecret,
                    "device_code" to login.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ))
            } catch (_: Exception) { continue } // chwilowy brak sieci
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code in 200..299 && j.optString("refresh_token").isNotBlank()) {
                Prefs.ytRefresh = j.getString("refresh_token")
                Prefs.ytAccess = j.optString("access_token")
                Prefs.ytAccessUntil = System.currentTimeMillis() + j.optInt("expires_in", 3600) * 1000L
                version.value++
                return null
            }
            when (j.optString("error")) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                "access_denied" -> return "Odmówiono dostępu na ekranie Google."
                "expired_token" -> return "Kod wygasł – zacznij logowanie od nowa."
                "invalid_client" -> return "Zły sekret klienta OAuth – wpisz go ponownie."
                else -> return "Logowanie nie powiodło się (HTTP $code ${j.optString("error")})."
            }
        }
        return if (alive()) "Kod wygasł – zacznij logowanie od nowa." else "przerwane"
    }

    fun signOut() {
        Prefs.ytRefresh = ""
        Prefs.ytAccess = ""
        Prefs.ytAccessUntil = 0
        version.value++
    }

    @Synchronized
    private fun token(): String {
        if (!signedIn) throw ApiError(NO_LOGIN)
        if (Prefs.ytAccess.isNotBlank() && Prefs.ytAccessUntil > System.currentTimeMillis() + 60_000) return Prefs.ytAccess
        val (code, text) = http("POST", TOKEN_URL, form(
            "client_id" to Prefs.ytClientId, "client_secret" to Prefs.ytClientSecret,
            "refresh_token" to Prefs.ytRefresh, "grant_type" to "refresh_token",
        ))
        val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        if (code !in 200..299) {
            if (j.optString("error") == "invalid_grant") {
                signOut()
                throw ApiError("Logowanie do YouTube wygasło albo zostało cofnięte – właściciel musi zalogować konto ponownie w Ustawieniach Kokpitu.")
            }
            throw ApiError("Nie udało się odświeżyć logowania do YouTube (HTTP $code ${j.optString("error")}).")
        }
        Prefs.ytAccess = j.getString("access_token")
        Prefs.ytAccessUntil = System.currentTimeMillis() + j.optInt("expires_in", 3600) * 1000L
        return Prefs.ytAccess
    }

    // ---------- zapytania ----------

    /** [mine] = dane konta (wymaga logowania); inaczej klucz API, a bez klucza – zalogowane konto. */
    private fun get(path: String, mine: Boolean, vararg params: Pair<String, String>): JSONObject {
        val useToken = mine || !hasKey
        if (!useToken && !hasKey) throw ApiError("Brak klucza Google – Ustawienia Kokpitu → Nawigacja → „Klucz Google Maps”.")
        val q = params.toMutableList()
        if (!useToken) q += "key" to Prefs.googleKey
        val (code, text) = http("GET", API + path + "?" + form(*q.toTypedArray()), bearer = if (useToken) token() else null)
        if (code in 200..299) return JSONObject(text)
        val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val status = err?.optString("status").orEmpty()
        val msg = err?.optString("message").orEmpty()
        throw ApiError(
            when {
                reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> "Dzienny limit zapytań do YouTube jest wyczerpany – wróci jutro."
                reason == "accessNotConfigured" || msg.contains("has not been used") -> "W projekcie Google nie jest włączone „YouTube Data API v3”."
                msg.contains("blocked", true) && status == "PERMISSION_DENIED" -> "Klucz Google nie ma dopisanego „YouTube Data API v3” w ograniczeniach interfejsów API."
                reason == "playlistNotFound" || code == 404 -> "Nie ma takiej playlisty albo jest prywatna."
                code == 401 -> "YouTube odrzucił logowanie – właściciel musi zalogować konto ponownie."
                else -> "YouTube: HTTP $code " + msg.take(200)
            }
        )
    }

    /** tytuły z wyszukiwania przychodzą z encjami HTML (&amp;, &#39;) */
    private fun JSONObject.title(): String = optString("title").let { if ('&' in it) android.text.Html.fromHtml(it, 0).toString() else it }

    private fun JSONObject.items(): List<JSONObject> = optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /** PT1H2M3S → sekundy */
    private fun seconds(iso: String): Int {
        val m = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(iso) ?: return 0
        return (m.groupValues[1].toIntOrNull() ?: 0) * 3600 + (m.groupValues[2].toIntOrNull() ?: 0) * 60 + (m.groupValues[3].toIntOrNull() ?: 0)
    }

    private fun length(sec: Int) = when {
        sec <= 0 -> ""
        sec < 60 -> "$sec s"
        sec < 3600 -> "${sec / 60} min"
        else -> "${sec / 3600} godz. ${sec % 3600 / 60} min"
    }

    private fun day(iso: String) = iso.take(10)

    /** długości filmów o podanych id (jedno tanie zapytanie) */
    private fun durations(ids: List<String>): Map<String, Int> {
        if (ids.isEmpty()) return emptyMap()
        return runCatching {
            get("videos", false, "part" to "contentDetails", "id" to ids.take(50).joinToString(","))
                .items().associate { it.getString("id") to seconds(it.getJSONObject("contentDetails").optString("duration")) }
        }.getOrDefault(emptyMap())
    }

    class Video(val id: String, val title: String)

    /** Pierwszy film dla zapytania – dla play_youtube. */
    fun firstVideo(query: String): Video? =
        get("search", false, "part" to "snippet", "type" to "video", "maxResults" to "1", "q" to query, "regionCode" to "PL", "relevanceLanguage" to "pl")
            .items().firstOrNull()?.let { Video(it.getJSONObject("id").getString("videoId"), it.getJSONObject("snippet").title()) }

    private fun search(query: String, kind: String): String {
        if (query.isBlank()) return "Brak zapytania"
        val type = when (kind) { "playlist" -> "playlist"; "channel" -> "channel"; else -> "video" }
        val p = mutableListOf("part" to "snippet", "type" to type, "maxResults" to "6", "q" to query, "regionCode" to "PL", "relevanceLanguage" to "pl")
        if (kind == "live") p += "eventType" to "live"
        val items = get("search", false, *p.toTypedArray()).items()
        if (items.isEmpty()) return "Nic nie znaleziono w YouTube dla „$query”."
        val len = if (type == "video" && kind != "live") durations(items.map { it.getJSONObject("id").optString("videoId") }) else emptyMap()
        return "Wyniki YouTube dla „$query”:\n" + items.mapIndexed { i, it ->
            val id = it.getJSONObject("id")
            val s = it.getJSONObject("snippet")
            when (type) {
                "playlist" -> "${i + 1}. playlista „${s.title()}” · kanał ${s.optString("channelTitle")} · id ${id.optString("playlistId")}"
                "channel" -> "${i + 1}. kanał „${s.title()}” · id ${id.optString("channelId")}"
                else -> {
                    val vid = id.optString("videoId")
                    "${i + 1}. „${s.title()}” · kanał ${s.optString("channelTitle")}" +
                        (len[vid]?.let { l -> length(l) }?.takeIf { l -> l.isNotEmpty() }?.let { l -> " · $l" } ?: "") +
                        (if (kind == "live") " · na żywo" else " · ${day(s.optString("publishedAt"))}") + " · id $vid"
                }
            }
        }.joinToString("\n")
    }

    private fun playlistItems(id: String, mine: Boolean, max: Int): List<JSONObject> =
        get("playlistItems", mine, "part" to "snippet,contentDetails", "playlistId" to id, "maxResults" to max.toString()).items()

    private fun listPlaylist(id: String): String {
        if (id.isBlank()) return "Podaj id playlisty (z my_playlists albo search)."
        val items = playlistItems(id, signedIn, 25)
        if (items.isEmpty()) return "Playlista jest pusta."
        return "Filmy na playliście (pierwsze ${items.size}):\n" + items.mapIndexed { i, it ->
            val s = it.getJSONObject("snippet")
            "${i + 1}. „${s.title()}” · ${s.optString("videoOwnerChannelTitle")} · id ${it.getJSONObject("contentDetails").optString("videoId")}"
        }.joinToString("\n")
    }

    private fun myPlaylists(): String {
        val items = get("playlists", true, "part" to "snippet,contentDetails", "mine" to "true", "maxResults" to "50").items()
        return "Playlisty właściciela:\n" + (listOf("Polubione filmy · id LL") + items.map {
            "„${it.getJSONObject("snippet").title()}” · ${it.getJSONObject("contentDetails").optInt("itemCount")} filmów · id ${it.getString("id")}"
        }).joinToString("\n")
    }

    private fun subscriptions(): List<Pair<String, String>> =
        get("subscriptions", true, "part" to "snippet", "mine" to "true", "maxResults" to "50", "order" to "relevance").items().map {
            val s = it.getJSONObject("snippet")
            s.title() to s.getJSONObject("resourceId").optString("channelId")
        }

    /** Najnowsze filmy kanału: jego playlista „przesłane” ma id kanału z UU zamiast UC. */
    private fun uploads(channelId: String, mine: Boolean, max: Int): List<JSONObject> =
        if (!channelId.startsWith("UC")) emptyList() else playlistItems("UU" + channelId.drop(2), mine, max)

    private fun channelVideos(id: String): String {
        if (id.isBlank()) return "Podaj id kanału (z search z kind=channel albo z subscriptions)."
        val items = uploads(id, false, 8)
        if (items.isEmpty()) return "Kanał nie ma filmów albo id jest złe."
        return "Najnowsze filmy kanału ${items.first().getJSONObject("snippet").optString("channelTitle")}:\n" + items.mapIndexed { i, it ->
            val c = it.getJSONObject("contentDetails")
            "${i + 1}. „${it.getJSONObject("snippet").title()}” · ${day(c.optString("videoPublishedAt"))} · id ${c.optString("videoId")}"
        }.joinToString("\n")
    }

    private fun newFromSubscriptions(): String {
        val subs = subscriptions().take(20)
        if (subs.isEmpty()) return "Konto nie ma subskrypcji."
        val all = mutableListOf<Triple<String, String, String>>() // data, opis, id
        for ((name, ch) in subs) runCatching {
            uploads(ch, true, 2).forEach {
                val c = it.getJSONObject("contentDetails")
                all += Triple(c.optString("videoPublishedAt"), "„${it.getJSONObject("snippet").title()}” · kanał $name", c.optString("videoId"))
            }
        }
        if (all.isEmpty()) return "Nie udało się pobrać nowości z subskrypcji."
        return "Najnowsze filmy z subskrypcji (z ${subs.size} najważniejszych kanałów):\n" +
            all.sortedByDescending { it.first }.take(15).mapIndexed { i, t -> "${i + 1}. ${t.second} · ${day(t.first)} · id ${t.third}" }.joinToString("\n")
    }

    /**
     * Narzędzie asystenta „youtube”. [play] włącza adres w aplikacji YouTube (w tle albo na ekranie) i zwraca opis.
     */
    fun run(input: JSONObject, play: (uri: String, label: String, show: Boolean) -> String): String = try {
        val id = input.optString("id").trim()
        val kind = input.optString("kind").trim()
        when (input.optString("action")) {
            "search" -> search(input.optString("query").trim(), kind)
            "channel_videos" -> channelVideos(id)
            "playlist" -> listPlaylist(id)
            "my_playlists" -> myPlaylists()
            "subscriptions" -> subscriptions().let { s ->
                if (s.isEmpty()) "Konto nie ma subskrypcji." else "Subskrypcje właściciela:\n" + s.joinToString("\n") { "${it.first} · id ${it.second}" }
            }
            "new_from_subscriptions" -> newFromSubscriptions()
            "play" -> when {
                id.isBlank() -> "Podaj id filmu albo playlisty (z search, playlist, my_playlists)."
                kind == "playlist" || id.startsWith("PL") || id == "LL" || id.startsWith("UU") || id.startsWith("OL") -> {
                    val first = playlistItems(id, signedIn, 1).firstOrNull() ?: return "Playlista jest pusta."
                    val vid = first.getJSONObject("contentDetails").optString("videoId")
                    play("https://www.youtube.com/watch?v=$vid&list=$id", "playlista od „${first.getJSONObject("snippet").title()}”", input.optBoolean("show"))
                }
                else -> play("https://www.youtube.com/watch?v=$id", input.optString("query").trim().ifBlank { "film $id" }, input.optBoolean("show"))
            }
            else -> "Nieznana akcja"
        }
    } catch (e: ApiError) {
        e.message ?: "Błąd YouTube"
    } catch (e: Exception) {
        "YouTube nie odpowiada: ${e.javaClass.simpleName}"
    }
}

/** Logowanie konta YouTube: kod do wpisania na telefonie; okno samo się zamyka po zalogowaniu. */
@Composable
fun YouTubeLoginDialog(onClose: () -> Unit) {
    var login by remember { mutableStateOf<YouTubeApi.Login?>(null) }
    var note by remember { mutableStateOf("Łączę z Google…") }
    DisposableEffect(Unit) {
        var alive = true
        Thread {
            try {
                val l = YouTubeApi.startLogin()
                login = l
                note = "Czekam, aż wpiszesz kod…"
                val err = YouTubeApi.awaitLogin(l) { alive }
                if (err == null) { note = "Zalogowano."; Assistant.reset(); if (alive) onClose() }
                else if (alive) { login = null; note = err }
            } catch (e: Exception) {
                login = null
                note = (e as? YouTubeApi.ApiError)?.message ?: "Brak połączenia z Google."
            }
        }.start()
        onDispose { alive = false }
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text("Konto YouTube", color = Txt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
                login?.let { l ->
                    Text("Na telefonie albo komputerze otwórz:", color = Dim, fontSize = s(20))
                    Text(l.url.removePrefix("https://"), color = Txt, fontSize = s(34), fontWeight = FontWeight.Bold)
                    Text("i wpisz kod:", color = Dim, fontSize = s(20))
                    Text(l.userCode, color = Accent, fontSize = s(56), fontWeight = FontWeight.Bold)
                    Text("Kokpit dostanie tylko odczyt: Twoje playlisty, polubione i subskrypcje. Niczego nie zmienia na koncie.", color = Dim, fontSize = s(18))
                }
                Text(note, color = Dim, fontSize = s(20))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Zamknij") } },
    )
}
