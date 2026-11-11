package pl.lawinski.radiolauncher

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Reguły właściciela (Ustawienia → Reguły): „jeśli <warunek>, to asystent ma powiedzieć / zrobić <tekst>”.
 * Warunki liczbowe (paliwo, akumulator, temperatury, prędkość…) Kokpit sprawdza sam co 30 s – bez internetu
 * i bez tokenów. Slack (wiadomość od osoby, na kanale, oznaczenie) – jedno zapytanie do Slacka co 2 min na regułę.
 * Dopiero spełniony warunek uruchamia asystenta (albo samo odczytanie tekstu, gdy reguła ma wyłączone „przez asystenta”).
 * Reguła działa na zboczu: odzywa się, gdy warunek zaczyna być spełniony, i nie częściej niż co ustawioną przerwę.
 */
enum class RuleSource(val title: String, val unit: String, val slack: Boolean = false, val target: String = "") {
    FUEL("Paliwo", "l"),
    BATTERY("Akumulator", "V"),
    OUTSIDE("Temperatura na zewnątrz", "°C"),
    SPEED("Prędkość", "km/h"),
    HOME_TEMP("Temperatura w domu", "°C"),
    HOME_HUM("Wilgotność w domu", "%"),
    SLACK_PERSON("Slack: napisała do mnie osoba", "", true, "Osoba (imię albo nazwisko)"),
    SLACK_CHANNEL("Slack: wiadomość na kanale", "", true, "Kanał (np. ogólny)"),
    SLACK_MENTION("Slack: ktoś mnie oznaczył", "", true),
}

data class Rule(
    val id: Long,
    val on: Boolean = true,
    val source: RuleSource = RuleSource.FUEL,
    /** warunek liczbowy: true = „mniej niż”, false = „więcej niż” */
    val less: Boolean = true,
    /** liczba (warunek liczbowy) albo osoba / kanał (Slack) */
    val value: String = "",
    /** co asystent ma powiedzieć albo zrobić */
    val text: String = "",
    /** true = polecenie dla asystenta AI; false = Kokpit czyta dokładnie ten tekst (bez tokenów) */
    val ai: Boolean = true,
    /** najkrótsza przerwa między kolejnymi komunikatami tej reguły */
    val cooldownMin: Int = 60,
    val lastFired: Long = 0L,
    /** Slack: znacznik czasu ostatniej widzianej wiadomości */
    val slackTs: String = "",
) {
    fun number() = value.trim().replace(',', '.').toFloatOrNull()

    fun summary(): String = when {
        source == RuleSource.SLACK_MENTION -> source.title
        source.slack -> source.title + " „" + value.trim().ifEmpty { "?" } + "”"
        else -> source.title + (if (less) " mniej niż " else " więcej niż ") + value.trim().ifEmpty { "?" } + " " + source.unit
    }

    /** Czy reguła ma wszystko, żeby działać. */
    fun complete() = text.isNotBlank() && when {
        source == RuleSource.SLACK_MENTION -> true
        source.slack -> value.isNotBlank()
        else -> number() != null
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("on", on).put("source", source.name).put("less", less).put("value", value)
        .put("text", text).put("ai", ai).put("cooldown", cooldownMin).put("fired", lastFired).put("slackTs", slackTs)

    companion object {
        val COOLDOWNS = listOf(5 to "5 min", 15 to "15 min", 60 to "godzinę", 240 to "4 godziny", 1440 to "dobę")

        fun from(o: JSONObject) = Rule(
            id = o.optLong("id"), on = o.optBoolean("on", true),
            source = runCatching { RuleSource.valueOf(o.optString("source")) }.getOrDefault(RuleSource.FUEL),
            less = o.optBoolean("less", true), value = o.optString("value"), text = o.optString("text"), ai = o.optBoolean("ai", true),
            cooldownMin = o.optInt("cooldown", 60), lastFired = o.optLong("fired"), slackTs = o.optString("slackTs"),
        )
    }
}

object Rules {
    private const val TAG = "KokpitRules"
    private const val TICK_MS = 30_000L

    val list = mutableStateListOf<Rule>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    /** czy warunek był spełniony przy poprzednim sprawdzeniu (id reguły) – komunikat tylko przy zmianie */
    private val wasTrue = HashMap<Long, Boolean>()

    private fun load() {
        list.clear()
        val a = runCatching { JSONArray(Prefs.rules) }.getOrDefault(JSONArray())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { list += Rule.from(it) }
    }

    private fun save() {
        Prefs.rules = JSONArray().apply { list.forEach { put(it.json()) } }.toString()
    }

    /** Dodaje albo zmienia regułę (z okna edycji). Zmieniona reguła ocenia warunek od nowa. */
    fun put(r: Rule) {
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        wasTrue.remove(r.id)
        save()
    }

    fun remove(id: Long) {
        list.removeAll { it.id == id }
        wasTrue.remove(id)
        save()
    }

    private fun update(id: Long, change: (Rule) -> Rule) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = change(list[i]); save() }
    }

    /** Bieżąca wartość źródła liczbowego albo null, gdy nie ma danych. */
    fun current(source: RuleSource, act: MainActivity): Float? = when (source) {
        RuleSource.FUEL -> CanSource.last.fuelL
        RuleSource.BATTERY -> carBatteryVolt()
        RuleSource.OUTSIDE -> CanSource.last.outsideC
        RuleSource.SPEED -> act.location.speedKmh.value?.toFloat()
        RuleSource.HOME_TEMP -> Tuya.sensor.value?.temp ?: Tuya.state.value?.room
        RuleSource.HOME_HUM -> (Tuya.sensor.value?.humidity ?: Tuya.state.value?.humidity)?.toFloat()
        else -> null
    }

    fun format(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)).replace('.', ',')

    fun start() {
        if (started) return
        started = true
        load()
        scope.launch {
            var n = 0
            while (true) {
                delay(TICK_MS)
                val act = MainActivity.live ?: continue
                runCatching { tick(act, n++) }.onFailure { Log.w(TAG, "tick", it) }
            }
        }
    }

    private suspend fun tick(act: MainActivity, n: Int) {
        val active = list.filter { it.on && it.complete() }
        if (active.isEmpty()) return
        // dane z domu odświeża blok Klima tylko, gdy widać Kokpit – reguły potrzebują ich zawsze (co 5 min)
        if (n % 10 == 0 && Tuya.configured && active.any { it.source == RuleSource.HOME_TEMP || it.source == RuleSource.HOME_HUM }) {
            runCatching { Tuya.refresh() }
        }
        for (r in active.filter { !it.source.slack }) {
            val v = current(r.source, act) ?: continue
            val x = r.number() ?: continue
            val cond = if (r.less) v < x else v > x
            if (!cond) { wasTrue[r.id] = false; continue }
            if (wasTrue[r.id] == true) continue
            // przerwa po poprzednim komunikacie jeszcze trwa: ten raz przepada (odezwie się przy następnym przekroczeniu progu)
            if (System.currentTimeMillis() - r.lastFired < r.cooldownMin * 60_000L) { wasTrue[r.id] = true; continue }
            // zbocze: warunek właśnie zaczął być spełniony; przy zajętym asystencie spróbujemy za chwilę
            if (fire(act, r, "${r.source.title}: ${format(v)} ${r.source.unit} (próg: ${if (r.less) "mniej niż" else "więcej niż"} ${format(x)} ${r.source.unit})", format(v))) {
                wasTrue[r.id] = true
            }
        }
        if (n % 4 == 0 && Slack.configured) for (r in active.filter { it.source.slack }) slack(act, r)
    }

    private suspend fun slack(act: MainActivity, r: Rule) {
        // nowa reguła: zaczynamy od teraz, stare wiadomości nie wywołują komunikatu
        if (r.slackTs.isEmpty()) return update(r.id) { it.copy(slackTs = "%.6f".format(java.util.Locale.US, System.currentTimeMillis() / 1000.0)) }
        val (msgs, newest) = try {
            withContext(Dispatchers.IO) { Slack.fresh(r.source, r.value, r.slackTs) }
        } catch (e: Exception) {
            Log.w(TAG, "slack: $e")
            return
        }
        if (msgs.isEmpty()) return
        if (fire(act, r, r.summary() + ". Nowe wiadomości:\n" + msgs.joinToString("\n"), msgs.size.toString())) {
            update(r.id) { it.copy(slackTs = newest) }
        }
    }

    /** Komunikat reguły; false = teraz się nie da (przerwa nie minęła, asystent zajęty) – spróbujemy przy następnym sprawdzeniu. */
    private fun fire(act: MainActivity, r: Rule, context: String, value: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - r.lastFired < r.cooldownMin * 60_000L) return false
        if (Assistant.busy.value || Speech.isSpeaking() || VoiceActivity.open) return false
        Log.i(TAG, "reguła: ${r.summary()}")
        if (r.ai && Assistant.hasKey()) {
            Assistant.send(
                act,
                "[Automatyczna reguła Kokpitu – tego nikt teraz nie napisał ani nie powiedział]\nSpełniony warunek: $context\n" +
                    "Polecenie właściciela dla tej reguły: ${r.text}\nWykonaj je i powiedz wynik krótko, na głos.",
                shown = "⚡ " + r.summary(), speak = true,
            )
        } else {
            val msg = r.text.replace("{wartość}", value).replace("{wartosc}", value)
            Assistant.lines += ChatLine(true, "⚡ " + r.summary())
            Assistant.lines += ChatLine(false, msg)
            act.speak(msg)
            if (act.layout.value.none { it.id == BlockId.ASSISTANT }) android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show()
        }
        update(r.id) { it.copy(lastFired = now) }
        return true
    }
}
