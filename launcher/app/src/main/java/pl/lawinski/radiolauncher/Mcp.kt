@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Serwery MCP dla asystenta (Ustawienia → Asystent AI → Dostępy → „Serwery MCP”): właściciel podaje
 * adres i klucz, Kokpit pobiera listę narzędzi serwera i daje je asystentowi (Claude i OpenAI) pod
 * nazwami mcp_<serwer>_<narzędzie>. Protokół: Streamable HTTP – POST z JSON-RPC, odpowiedź JSON albo
 * strumień zdarzeń (bierzemy z niego odpowiedź na nasze zapytanie). Lista narzędzi jest pamiętana
 * w ustawieniach, żeby po starcie radia asystent miał ten sam zestaw bez czekania na sieć.
 */
object Mcp {
    class Server(val name: String, val url: String, val token: String)

    /** [def] – nazwa narzędzia dla modelu; [schema] – inputSchema z serwera */
    class Tool(val def: String, val server: String, val name: String, val description: String, val schema: JSONObject)

    private class McpError(message: String) : Exception(message)

    /** zmienia się po zmianie serwerów albo listy narzędzi (odświeża kafelki i okno) */
    val version = mutableStateOf(0)

    private const val PROTOCOL = "2025-06-18"
    private val sessions = HashMap<String, String?>() // adres -> identyfikator sesji (null: serwer bezstanowy), po initialize
    private var nextId = 1

    fun servers(): List<Server> = runCatching {
        val a = JSONArray(Prefs.mcpServers)
        (0 until a.length()).map { a.getJSONObject(it) }.map { Server(it.optString("name"), it.optString("url"), it.optString("token")) }
    }.getOrDefault(emptyList())

    private fun saveServers(list: List<Server>) {
        Prefs.mcpServers = JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) } }.toString()
    }

    private fun slug(v: String) = v.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("").trim('_').ifEmpty { "serwer" }

    /** Narzędzia wszystkich dodanych serwerów (z pamięci – bez sieci). */
    fun tools(): List<Tool> = runCatching {
        val known = servers().map { it.name }.toSet()
        val a = JSONArray(Prefs.mcpTools)
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("server") in known }.map {
            Tool(it.optString("def"), it.optString("server"), it.optString("name"), it.optString("description"), it.optJSONObject("schema") ?: JSONObject())
        }
    }.getOrDefault(emptyList())

    /** Wskazówki serwerów (pole instructions z initialize) do opisu roli asystenta. */
    fun promptNote(): String {
        val t = tools()
        if (t.isEmpty() || !Access.enabled("mcp")) return ""
        val notes = runCatching { JSONObject(Prefs.mcpNotes) }.getOrDefault(JSONObject())
        return "\n\nMasz też narzędzia z serwerów dodanych przez właściciela – ich nazwy zaczynają się od mcp_: " +
            t.groupBy { it.server }.entries.joinToString("; ") { (server, list) ->
                "serwer „$server” (${list.joinToString { it.def }})" + notes.optString(server).trim().let { if (it.isEmpty()) "" else " – $it" }
            } + ". Używaj ich zgodnie z opisami."
    }

    // ---------- protokół

    private fun post(server: Server, body: JSONObject, session: String?): Pair<JSONObject?, String?> {
        val url = URL(server.url)
        if (url.protocol != "https" && url.host != "127.0.0.1" && url.host != "localhost") throw McpError("adres musi zaczynać się od https://")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 120000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        c.setRequestProperty("MCP-Protocol-Version", PROTOCOL)
        if (server.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${server.token.trim()}")
        if (session != null) c.setRequestProperty("Mcp-Session-Id", session)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val newSession = c.getHeaderField("Mcp-Session-Id")
        if (code == 202 || code == 204) return null to newSession
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw McpError(when (code) {
            401, 403 -> "serwer odrzucił klucz (HTTP $code)"
            404 -> "pod tym adresem nie ma serwera MCP (HTTP 404)"
            429 -> "za dużo błędnych prób – serwer chwilowo blokuje ten adres (HTTP 429)"
            else -> "HTTP $code" + text.take(160).let { if (it.isBlank()) "" else ": $it" }
        })
        val id = body.opt("id")
        // strumień zdarzeń: odpowiedź na nasze zapytanie jest w jednej z linii „data:”
        val candidates = if (c.contentType.orEmpty().contains("event-stream"))
            text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList() else listOf(text)
        for (cand in candidates) {
            val o = runCatching { JSONObject(cand) }.getOrNull() ?: continue
            if (id == null || o.opt("id")?.toString() == id.toString()) return o to newSession
        }
        throw McpError("serwer nie odpowiedział w formacie MCP")
    }

    /** Zapytanie z odpowiedzią; pierwsze do danego serwera poprzedza powitanie (initialize). */
    private fun rpc(server: Server, method: String, params: JSONObject?, retry: Boolean = true): JSONObject {
        val session: String?
        synchronized(sessions) {
            if (!sessions.containsKey(server.url)) {
                val hello = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", "initialize").put("params", JSONObject()
                    .put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "car-launcher").put("version", "1")))
                val (answer, sid) = post(server, hello, null)
                answer?.optJSONObject("error")?.let { throw McpError(it.optString("message", "błąd powitania")) }
                answer?.optJSONObject("result")?.optString("instructions")?.let { note ->
                    val notes = runCatching { JSONObject(Prefs.mcpNotes) }.getOrDefault(JSONObject())
                    if (notes.optString(server.name) != note.take(600)) Prefs.mcpNotes = notes.put(server.name, note.take(600)).toString()
                }
                sessions[server.url] = sid
                runCatching { post(server, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), sid) }
            }
            session = sessions[server.url]
        }
        val body = JSONObject().put("jsonrpc", "2.0").put("id", synchronized(sessions) { nextId++ }).put("method", method)
        if (params != null) body.put("params", params)
        val answer = try {
            post(server, body, session).first
        } catch (e: McpError) {
            // sesja mogła wygasnąć po stronie serwera – jedno ponowienie z nowym powitaniem
            if (!retry || session == null || e.message?.contains("HTTP 4") != true) throw e
            synchronized(sessions) { sessions.remove(server.url) }
            return rpc(server, method, params, retry = false)
        } ?: throw McpError("pusta odpowiedź serwera")
        answer.optJSONObject("error")?.let { throw McpError(it.optString("message", "błąd serwera")) }
        return answer.optJSONObject("result") ?: JSONObject()
    }

    private fun describe(e: Exception): String = when (e) {
        is McpError -> e.message ?: "błąd"
        is java.net.UnknownHostException -> "nie ma takiego adresu albo brak internetu"
        is java.net.SocketTimeoutException -> "serwer nie odpowiedział na czas"
        is javax.net.ssl.SSLException -> "błąd szyfrowanego połączenia (certyfikat serwera)"
        is java.net.MalformedURLException -> "zły adres"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Pobiera listy narzędzi ze wszystkich serwerów i zapamiętuje je. Serwer, który nie odpowiada,
     * zachowuje dotychczasową listę. Zwraca opis wyniku dla każdego serwera. Poza wątkiem UI.
     */
    fun refresh(): Map<String, String> {
        val before = Prefs.mcpTools
        val old = runCatching { JSONArray(before) }.getOrDefault(JSONArray())
        val all = JSONArray()
        val status = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for (s in servers()) {
            try {
                synchronized(sessions) { sessions.remove(s.url) }
                val list = rpc(s, "tools/list", null).optJSONArray("tools") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val t = list.getJSONObject(i)
                    var def = ("mcp_" + slug(s.name) + "_" + t.optString("name")).filter { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }.take(64)
                    while (!used.add(def)) def = def.take(60) + "_" + used.size
                    all.put(JSONObject().put("def", def).put("server", s.name).put("name", t.optString("name"))
                        .put("description", t.optString("description").take(1500)).put("schema", t.optJSONObject("inputSchema") ?: JSONObject()))
                }
                status[s.name] = if (list.length() == 0) "połączono, serwer nie ma narzędzi"
                else "połączono – narzędzia (${list.length()}): " + (0 until list.length()).joinToString { list.getJSONObject(it).optString("name") }
            } catch (e: Exception) {
                status[s.name] = "nie udało się: " + describe(e)
                for (i in 0 until old.length()) old.getJSONObject(i).let { if (it.optString("server") == s.name) { all.put(it); used.add(it.optString("def")) } }
            }
        }
        val now = all.toString()
        if (now != before) {
            Prefs.mcpTools = now
            version.value++
        }
        return status
    }

    /** Wywołanie narzędzia serwera; obrazy z odpowiedzi trafiają do miniatur pod odpowiedzią asystenta. */
    fun call(def: String, input: JSONObject): String {
        val tool = tools().firstOrNull { it.def == def } ?: return "Nie ma już takiego narzędzia – serwer MCP został usunięty albo zmienił listę narzędzi."
        val server = servers().firstOrNull { it.name == tool.server } ?: return "Serwer „${tool.server}” został usunięty z Ustawień."
        return try {
            val result = rpc(server, "tools/call", JSONObject().put("name", tool.name).put("arguments", input))
            val content = result.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                when (c.optString("type")) {
                    "text" -> text.append(c.optString("text")).append('\n')
                    "image" -> runCatching {
                        Pics.addJpeg(android.util.Base64.decode(c.optString("data"), android.util.Base64.DEFAULT), "${tool.server}: ${tool.name}")
                        text.append("(obraz – rozmówca zobaczy go jako miniaturę pod odpowiedzią)\n")
                    }
                    else -> text.append("(treść typu ${c.optString("type")} – pominięta)\n")
                }
            }
            val out = text.toString().trim().ifEmpty { "(serwer nie zwrócił treści)" }.take(12000)
            if (result.optBoolean("isError")) "Serwer zgłosił błąd: $out" else out
        } catch (e: Exception) {
            "Serwer „${tool.server}” nie wykonał polecenia: ${describe(e)}"
        }
    }

    fun add(name: String, url: String, token: String): String? {
        val n = name.trim()
        val u = url.trim()
        if (n.isEmpty()) return "Podaj nazwę serwera"
        if (!u.startsWith("https://") && !u.startsWith("http://127.0.0.1") && !u.startsWith("http://localhost")) return "Adres musi zaczynać się od https://"
        val list = servers().filter { it.name != n }
        if (list.any { slug(it.name) == slug(n) }) return "Jest już serwer o bardzo podobnej nazwie"
        saveServers(list + Server(n, u, token.trim()))
        synchronized(sessions) { sessions.remove(u) }
        version.value++
        return null
    }

    fun remove(name: String) {
        saveServers(servers().filter { it.name != name })
        runCatching {
            val a = JSONArray(Prefs.mcpTools)
            Prefs.mcpTools = JSONArray().apply { for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.optString("server") != name) put(it) } }.toString()
        }
        version.value++
    }

    /** JSON → zwykłe mapy i listy (opis pól narzędzia dla obu dostawców). */
    fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }
}

/** Ustawienia → Asystent AI → Dostępy → „Serwery MCP”: lista, dodawanie, usuwanie, sprawdzenie połączenia. */
@Composable
fun McpDialog(onClose: () -> Unit) {
    val version by Mcp.version
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirm by remember { mutableStateOf("") }
    val servers = remember(version) { Mcp.servers() }
    val tools = remember(version) { Mcp.tools() }

    fun check() {
        if (busy) return
        busy = true
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching { Mcp.refresh() }.getOrDefault(emptyMap()) }
            busy = false
        }
    }

    Sheet("Serwery MCP", onClose) {
        Text(
            "Serwer MCP daje asystentowi dodatkowe narzędzia, np. położenia bliskich. Podaj nazwę, adres (https://…) i klucz dostępu. Klucz zostaje tylko w radiu.",
            color = Dim, fontSize = s(19),
        )
        Spacer(Modifier.height(d(12)))
        // pola u góry okna, żeby klawiatura ich nie zasłaniała
        Row(horizontalArrangement = Arrangement.spacedBy(d(10)), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nazwa") }, modifier = Modifier.weight(0.7f))
            OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Adres") }, placeholder = { Text("https://…/mcp") }, modifier = Modifier.weight(1.6f))
            OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Klucz") }, modifier = Modifier.weight(1.2f))
            Box(
                Modifier.height(d(84)).kokpitSurface(d(16), active = true).combinedClickable(enabled = !busy, onClick = {
                    note = Mcp.add(name, url, token)
                    if (note == null) { name = ""; url = ""; token = ""; check() }
                }).padding(horizontal = d(22)),
                contentAlignment = Alignment.Center,
            ) { Text("Dodaj", color = LocalTheme.current.activeText, fontSize = s(20)) }
        }
        note?.let { Text(it, color = Warn, fontSize = s(18), modifier = Modifier.padding(top = d(6))) }
        Spacer(Modifier.height(d(14)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(14))) {
            Text(
                if (servers.isEmpty()) "Nie ma jeszcze żadnego serwera." else if (busy) "Sprawdzam połączenie…" else "Dodane serwery:",
                color = Dim, fontSize = s(19), modifier = Modifier.weight(1f),
            )
            if (servers.isNotEmpty()) Box(
                Modifier.height(d(60)).kokpitSurface(d(16)).combinedClickable(enabled = !busy, onClick = { check() }).padding(horizontal = d(20)),
                contentAlignment = Alignment.Center,
            ) { Text("Sprawdź połączenie", color = Txt, fontSize = s(19)) }
        }
        Spacer(Modifier.height(d(10)))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(d(10)), modifier = Modifier.fillMaxSize()) {
            items(servers.size) { i ->
                val srv = servers[i]
                val own = tools.filter { it.server == srv.name }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                    Column(Modifier.weight(1f).kokpitSurface(d(16)).padding(horizontal = d(18), vertical = d(12))) {
                        Text(srv.name, color = Txt, fontSize = s(22))
                        Text(srv.url + if (srv.token.isBlank()) " · bez klucza" else " · klucz zapisany", color = Dim, fontSize = s(16), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            status[srv.name] ?: if (own.isEmpty()) "narzędzia jeszcze niepobrane – dotknij „Sprawdź połączenie”" else "narzędzia (${own.size}): " + own.joinToString { it.name },
                            color = if (status[srv.name]?.startsWith("nie udało") == true) Warn else Dim, fontSize = s(17), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(d(84)).kokpitSurface(d(16), active = confirm == srv.name).combinedClickable(onClick = {
                            if (confirm == srv.name) { Mcp.remove(srv.name); confirm = "" } else confirm = srv.name
                        }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Delete, if (confirm == srv.name) "Potwierdź usunięcie serwera" else "Usuń serwer",
                            tint = if (confirm == srv.name) LocalTheme.current.activeText else Txt, modifier = Modifier.size(d(34)))
                    }
                }
            }
        }
    }
}
