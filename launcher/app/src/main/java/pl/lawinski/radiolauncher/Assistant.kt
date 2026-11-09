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
            mapOf("destination" to mapOf("type" to "string", "description" to "Cel: adres albo nazwa miejsca, po polsku")),
            listOf("destination"),
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
            "Steruje odtwarzaczem, który gra w radiu (YouTube, SoundCloud, Spotify…): status = co teraz leci, play, pause, next, previous.",
            mapOf("action" to mapOf("type" to "string", "enum" to listOf("status", "play", "pause", "next", "previous"))),
            listOf("action"),
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

    fun run(act: MainActivity, name: String, input: JSONObject): String = when (name) {
        "car_status" -> carStatus(act)
        "navigate" -> {
            val dest = input.optString("destination").trim()
            if (dest.isEmpty()) "Brak celu" else {
                act.runOnUiThread { navigateTo(act, dest) }
                "Nawigacja do „$dest” uruchomiona w Mapach Google"
            }
        }
        "open_saved_place" -> {
            when (input.optString("place")) {
                "home" -> act.runOnUiThread { goSaved(act, Prefs.home, false) }
                "work" -> act.runOnUiThread { goSaved(act, Prefs.work, true) }
                else -> act.runOnUiThread { goPlace3(act) }
            }
            "Nawigacja uruchomiona"
        }
        "play_youtube" -> playYoutube(act, input.optString("query").trim())
        "media_control" -> mediaControl(act, input.optString("action"))
        "radio_control" -> radioControl(act, input.optString("action"), input.optInt("value"))
        "open_app" -> openApp(act, input.optString("app").trim(), input.optString("search").trim())
        else -> "Nieznane narzędzie $name"
    }

    private const val YOUTUBE = "com.google.android.youtube"

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
    private fun mediaControl(act: MainActivity, action: String): String {
        if (!NavListener.isEnabled(act)) return "Kokpit nie ma dostępu do powiadomień (Ustawienia → Nawigacja → Podgląd trasy) – bez niego nie steruje odtwarzaczem"
        val msm = act.getSystemService(android.media.session.MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(android.content.ComponentName(act, NavListener::class.java))
        val s = sessions.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.packageName == YOUTUBE } ?: sessions.firstOrNull()
            ?: return "Nic teraz nie gra i żaden odtwarzacz nie jest otwarty"
        val app = runCatching { act.packageManager.getApplicationLabel(act.packageManager.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
        val md = s.metadata
        val now = listOfNotNull(md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" – ")
        val playing = s.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        val t = s.transportControls
        when (action) {
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            else -> return "$app: ${if (playing) "gra" else "wstrzymane"}${if (now.isNotBlank()) " – $now" else ""}"
        }
        return "$app: wykonano $action${if (now.isNotBlank()) " (było: $now)" else ""}"
    }

    private fun radioControl(act: MainActivity, action: String, value: Int): String {
        val am = act.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream)
        fun volPct() = am.getStreamVolume(stream) * 100 / max
        fun ui(block: () -> Unit) = act.runOnUiThread(block)
        val flags = android.media.AudioManager.FLAG_SHOW_UI
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

private const val SYSTEM_PROMPT = """Jesteś asystentem w aucie: ekran radia w BMW F11 Touring z launcherem Kokpit F11. Rozmawia z Tobą kierowca albo pasażer.
Odpowiadaj po polsku, krótko i naturalnie, zwykle 1–3 zdaniami – odpowiedzi są czytane na głos, więc pisz zwykłym tekstem, bez list, nagłówków, tabel i znaczników Markdown.
Kierowca nie powinien czytać długich tekstów w czasie jazdy, dlatego najważniejsze mów na początku.
Masz narzędzia: car_status (stan auta, położenie, trasa), navigate i open_saved_place (włączają nawigację w Mapach Google), open_app (uruchamia aplikację w radiu i może w niej wyszukać), play_youtube (od razu odtwarza w YouTube to, o co ktoś prosi), media_control (pauza, wznowienie, następny, poprzedni, co teraz gra) i radio_control (głośność, jasność, ekran BMW iDrive, podzielony ekran, ustawienia, wygląd Kokpitu). Gdy ktoś prosi o włączenie muzyki lub filmu, użyj play_youtube. Gdy pytanie dotyczy auta, paliwa, miejsca albo trasy, najpierw sprawdź car_status. Gdy ktoś prosi o trasę, od razu włącz nawigację i krótko to potwierdź."""

/** Rozmowa z jednym dostawcą; trzyma całą historię, żeby asystent pamiętał kontekst. */
private interface ChatEngine {
    /** Wysyła wypowiedź i zwraca odpowiedź (po wykonaniu narzędzi). Wywoływać poza wątkiem UI. */
    fun send(text: String): String
}

private class ClaudeEngine(private val act: MainActivity, apiKey: String, private val model: String) : ChatEngine {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    private val history = mutableListOf<MessageParam>()

    private val tools = AssistantTools.all.map { t ->
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

    override fun send(text: String): String {
        history += MessageParam.ofUser(text)
        repeat(8) {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(SYSTEM_PROMPT)
                .messages(history.toList())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .apply { tools.forEach { addTool(it) } }
                .addTool(WebSearchTool20260209.builder().maxUses(3L).build())
                // przy odmowie filtrów bezpieczeństwa serwer sam ponawia na innym modelu
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val msg = client.messages().create(params)
            // całą odpowiedź (z blokami myślenia) oddajemy bez zmian w kolejnym zapytaniu
            history += msg.toParam()
            val reply = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("").trim()
            when (msg.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val results = msg.content().mapNotNull { b -> b.toolUse().orElse(null) }.map { use ->
                        val out = try {
                            AssistantTools.run(act, use.name(), JSONObject(use._input().convert(Map::class.java) as Map<*, *>))
                        } catch (e: Exception) {
                            "Błąd: ${e.message}"
                        }
                        ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(out).build())
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
    private val history = JSONArray().put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))

    private val tools = JSONArray().apply {
        AssistantTools.all.forEach { t ->
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

    override fun send(text: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        repeat(8) {
            val body = JSONObject().put("model", model).put("messages", history).put("tools", tools)
            val msg = post(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            history.put(msg)
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return msg.optString("content").trim().ifEmpty { "…" }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.getJSONObject("function")
                val out = try {
                    AssistantTools.run(act, fn.getString("name"), JSONObject(fn.optString("arguments").ifBlank { "{}" }))
                } catch (e: Exception) {
                    "Błąd: ${e.message}"
                }
                history.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", out))
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

    /** Nowa rozmowa (też po zmianie dostawcy, klucza albo modelu). */
    fun reset() {
        engine = null
        engineKey = ""
        lines.clear()
    }

    private fun engine(act: MainActivity): ChatEngine {
        val p = Prefs.aiProvider
        val key = if (p == OPENAI) Prefs.openaiKey else Prefs.anthropicKey
        val model = if (p == OPENAI) Prefs.openaiModel else Prefs.anthropicModel
        val id = "$p|$key|$model"
        if (engine == null || id != engineKey) {
            engine = if (p == OPENAI) OpenAiEngine(act, key, model) else ClaudeEngine(act, key, model)
            engineKey = id
        }
        return engine!!
    }

    fun send(act: MainActivity, text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        if (!hasKey()) {
            lines += ChatLine(false, "Wpisz klucz API ${providerName()} w Ustawieniach → Asystent.")
            return
        }
        lines += ChatLine(true, t)
        busy.value = true
        scope.launch {
            val reply = try {
                val e = engine(act)
                withContext(Dispatchers.IO) { e.send(t) }
            } catch (e: Exception) {
                engine = null // historia mogła się urwać w połowie narzędzi – zaczynamy od nowa
                errorText(e)
            }
            busy.value = false
            lines += ChatLine(false, reply)
            if (Prefs.aiSpeak) act.speak(reply)
            // bez bloku „Asystent” na ekranie (wywołanie przyciskiem głosowym) pokaż odpowiedź w dymku
            if (act.layout.value.none { it.id == BlockId.ASSISTANT }) android.widget.Toast.makeText(act, reply, android.widget.Toast.LENGTH_LONG).show()
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
