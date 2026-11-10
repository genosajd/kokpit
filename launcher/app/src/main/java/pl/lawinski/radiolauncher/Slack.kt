package pl.lawinski.radiolauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Slack dla asystenta: czytanie i wysyłanie wiadomości w imieniu właściciela.
 * Własna aplikacja Slack „Kokpit F11” w workspace użytkownika (uprawnienia użytkownika: *:history, *:read,
 * users:read, search:read, chat:write); jej User OAuth Token (xoxp-…) wpisuje się w Ustawieniach →
 * Asystent AI → Slack i zostaje tylko w radiu.
 *
 * Wysyłanie zawsze w dwóch krokach: send bez potwierdzenia zapisuje szkic i zwraca jego treść do
 * przeczytania; wysyła dopiero send z confirmed=true w KOLEJNEJ wypowiedzi użytkownika (sprawdzane tu,
 * nie tylko w opisie narzędzia), więc model nie może wysłać wiadomości bez Twojego „tak”.
 */
object Slack {
    /** numer wypowiedzi użytkownika (zwiększany w Assistant.send) */
    @Volatile
    var turn = 0

    private class Draft(val channel: String, val label: String, val text: String, val thread: String, val turn: Int)

    @Volatile
    private var draft: Draft? = null

    val configured get() = Prefs.slackToken.isNotBlank()

    private fun api(method: String, params: Map<String, String> = emptyMap(), post: Boolean = false): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL("https://slack.com/api/$method" + if (!post && query.isNotEmpty()) "?$query" else "").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer ${Prefs.slackToken}")
        if (post) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { it.write(query.toByteArray()) }
        }
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        if (!o.optBoolean("ok")) throw SlackError(o.optString("error", "błąd"))
        return o
    }

    class SlackError(val code: String) : Exception(code)

    // ---------- ludzie i rozmowy ----------

    private var users: Map<String, String>? = null
    private var me: String? = null

    /** id → imię i nazwisko (albo nazwa wyświetlana) */
    private fun users(): Map<String, String> = users ?: run {
        val out = HashMap<String, String>()
        var cursor = ""
        repeat(5) {
            val r = api("users.list", mapOf("limit" to "500", "cursor" to cursor))
            val a = r.optJSONArray("members") ?: JSONArray()
            for (i in 0 until a.length()) {
                val u = a.getJSONObject(i)
                if (u.optBoolean("deleted")) continue
                val p = u.optJSONObject("profile")
                val name = p?.optString("real_name").orEmpty().ifBlank { p?.optString("display_name").orEmpty() }.ifBlank { u.optString("name") }
                out[u.getString("id")] = name
            }
            cursor = r.optJSONObject("response_metadata")?.optString("next_cursor").orEmpty()
            if (cursor.isEmpty()) return@run out.also { users = it }
        }
        out.also { users = it }
    }

    private fun me(): String = me ?: api("auth.test").getString("user_id").also { me = it }

    private class Conv(val id: String, val name: String, val isIm: Boolean, val user: String?)

    private var convs: List<Conv>? = null

    /** Rozmowy, w których jesteś: kanały (#nazwa), prywatne i wiadomości bezpośrednie (imię osoby). */
    private fun convs(): List<Conv> = convs ?: run {
        val u = users()
        val out = mutableListOf<Conv>()
        var cursor = ""
        repeat(5) {
            val r = api("users.conversations", mapOf("types" to "public_channel,private_channel,mpim,im", "exclude_archived" to "true", "limit" to "200", "cursor" to cursor))
            val a = r.optJSONArray("channels") ?: JSONArray()
            for (i in 0 until a.length()) {
                val c = a.getJSONObject(i)
                if (c.optBoolean("is_im")) {
                    val uid = c.optString("user")
                    out += Conv(c.getString("id"), u[uid] ?: uid, true, uid)
                } else out += Conv(c.getString("id"), "#" + c.optString("name"), false, null)
            }
            cursor = r.optJSONObject("response_metadata")?.optString("next_cursor").orEmpty()
            if (cursor.isEmpty()) return@run out.also { convs = it }
        }
        out.also { convs = it }
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace("ł", "l").removePrefix("#").trim()

    /** Kanał albo osoba po nazwie (bez polskich znaków, fragment wystarczy). */
    private fun find(target: String): Conv? {
        val t = norm(target)
        if (t.isEmpty()) return null
        val all = convs()
        all.firstOrNull { norm(it.name) == t }?.let { return it }
        all.firstOrNull { norm(it.name).startsWith(t) }?.let { return it }
        all.firstOrNull { norm(it.name).contains(t) }?.let { return it }
        // osoba bez otwartej rozmowy: wiadomość na jej id otwiera rozmowę bezpośrednią
        users().entries.firstOrNull { norm(it.value).contains(t) }?.let { (id, name) -> return Conv(id, name, true, id) }
        return null
    }

    private val time = SimpleDateFormat("d.MM HH:mm", Locale("pl", "PL"))

    /** Treść Slacka czytelnie: <@U123> → imię, <#C1|kanał> → #kanał, linki bez nawiasów. */
    private fun clean(text: String): String {
        val u = users()
        return text
            .replace(Regex("<@([A-Z0-9]+)(\\|[^>]*)?>")) { "@" + (u[it.groupValues[1]] ?: it.groupValues[1]) }
            .replace(Regex("<#[A-Z0-9]+\\|([^>]*)>")) { "#" + it.groupValues[1] }
            .replace(Regex("<(https?://[^|>]+)\\|([^>]+)>")) { it.groupValues[2] }
            .replace(Regex("<(https?://[^>]+)>")) { it.groupValues[1] }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun line(m: JSONObject, withChannel: String? = null): String {
        val who = users()[m.optString("user")] ?: m.optString("username").ifBlank { "?" }
        val ts = m.optString("ts").substringBefore('.').toLongOrNull()?.let { time.format(Date(it * 1000)) } ?: ""
        val replies = m.optInt("reply_count", 0).takeIf { it > 0 }?.let { " [wątek: $it odp., ts ${m.optString("ts")}]" } ?: ""
        return (withChannel?.let { "$it · " } ?: "") + "$ts $who: ${clean(m.optString("text")).take(400)}$replies"
    }

    // ---------- działania narzędzia ----------

    fun run(input: JSONObject): String {
        if (!configured) return "Slack nie jest połączony – wpisz User OAuth Token (xoxp-…) w Ustawieniach → Asystent AI → Slack."
        return try {
            when (input.optString("action")) {
                "unread" -> unread()
                "read" -> read(input.optString("target"), input.optInt("count", 10), input.optString("thread"))
                "search" -> search(input.optString("query"), input.optInt("count", 10))
                "send" -> send(input.optString("target"), input.optString("text"), input.optString("thread"), input.optBoolean("confirmed"))
                else -> "Nieznana akcja Slack"
            }
        } catch (e: SlackError) {
            when (e.code) {
                "invalid_auth", "not_authed", "token_revoked", "account_inactive" -> "Zły albo wycofany token Slack (Ustawienia → Asystent AI → Slack)."
                "missing_scope" -> "Aplikacja Slack „Kokpit F11” nie ma potrzebnego uprawnienia."
                "channel_not_found", "not_in_channel" -> "Nie mam dostępu do tej rozmowy."
                "ratelimited" -> "Slack chwilowo ogranicza zapytania – spróbuj za chwilę."
                else -> "Slack: ${e.code}"
            }
        } catch (e: java.io.IOException) {
            "Brak połączenia ze Slackiem"
        }
    }

    /** Rozmowy z nieprzeczytanymi wiadomościami (do 40 sprawdzonych). */
    private fun unread(): String {
        val out = mutableListOf<String>()
        for (c in convs().take(60)) {
            val info = runCatching { api("conversations.info", mapOf("channel" to c.id)).getJSONObject("channel") }.getOrNull() ?: continue
            val n = info.optInt("unread_count_display", info.optInt("unread_count", 0))
            if (n > 0) out += "${c.name}: $n nieprzeczytanych"
            if (out.size >= 15) break
        }
        return if (out.isEmpty()) "Brak nieprzeczytanych wiadomości (sprawdzone rozmowy, w których jesteś)." else "Nieprzeczytane:\n" + out.joinToString("\n")
    }

    private fun read(target: String, count: Int, thread: String): String {
        val c = find(target) ?: return "Nie znam rozmowy „$target”. Rozmowy: " + convs().take(40).joinToString { it.name }
        if (c.id == c.user) return "Z ${c.name} nie ma jeszcze rozmowy."
        val n = count.coerceIn(1, 30).toString()
        val r = if (thread.isNotBlank()) api("conversations.replies", mapOf("channel" to c.id, "ts" to thread, "limit" to n))
        else api("conversations.history", mapOf("channel" to c.id, "limit" to n))
        val a = r.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("subtype").isEmpty() || it.optString("subtype") == "thread_broadcast" }
        val list = if (thread.isNotBlank()) msgs else msgs.reversed()
        if (list.isEmpty()) return "${c.name}: brak wiadomości"
        return "${c.name} (od najstarszej):\n" + list.joinToString("\n") { line(it) }
    }

    private fun search(query: String, count: Int): String {
        if (query.isBlank()) return "Podaj, czego szukać"
        val r = api("search.messages", mapOf("query" to query, "count" to count.coerceIn(1, 20).toString(), "sort" to "timestamp"))
        val a = r.optJSONObject("messages")?.optJSONArray("matches") ?: JSONArray()
        if (a.length() == 0) return "Nic nie znaleziono dla „$query”"
        return (0 until a.length()).joinToString("\n") { i ->
            val m = a.getJSONObject(i)
            val ch = m.optJSONObject("channel")
            val where = if (ch?.optBoolean("is_im") == true) "prywatnie" else "#" + ch?.optString("name").orEmpty()
            line(m, where)
        }
    }

    private fun send(target: String, text: String, thread: String, confirmed: Boolean): String {
        if (confirmed) {
            val d = draft ?: return "Nie ma szkicu do wysłania – najpierw przygotuj wiadomość (send bez confirmed) i przeczytaj ją do potwierdzenia."
            // potwierdzenie musi przyjść w następnej wypowiedzi użytkownika, nie w tej samej odpowiedzi
            if (d.turn >= turn) return "Najpierw przeczytaj szkic użytkownikowi i poczekaj na jego wyraźne „tak” – wtedy wywołaj send z confirmed=true."
            if (turn - d.turn > 2) { draft = null; return "Szkic jest nieaktualny – przygotuj wiadomość jeszcze raz." }
            val params = mutableMapOf("channel" to d.channel, "text" to d.text)
            if (d.thread.isNotBlank()) params["thread_ts"] = d.thread
            api("chat.postMessage", params, post = true)
            draft = null
            return "Wysłano do ${d.label}: „${d.text}”"
        }
        if (text.isBlank()) return "Pusta wiadomość"
        val c = find(target) ?: return "Nie znam rozmowy ani osoby „$target”. Rozmowy: " + convs().take(40).joinToString { it.name }
        draft = Draft(c.id, c.name, text.trim(), thread, turn)
        return "SZKIC (jeszcze NIE wysłany) do ${c.name}${if (thread.isNotBlank()) " (w wątku)" else ""}: „${text.trim()}”. " +
            "Przeczytaj użytkownikowi adresata i treść i zapytaj, czy wysłać. Wyślij (send z confirmed=true) dopiero po jego wyraźnym potwierdzeniu w następnej wypowiedzi; przy zmianach przygotuj nowy szkic."
    }

    /** Nowy token – zapomnij dane poprzedniego konta. */
    fun reset() {
        users = null
        me = null
        convs = null
        draft = null
    }
}
