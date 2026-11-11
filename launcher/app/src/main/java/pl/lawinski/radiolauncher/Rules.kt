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
 * Kamera: w wybranym momencie (co ustawioną przerwę, po ruszeniu, o godzinie) asystent dostaje zdjęcie i polecenie właściciela;
 * każde sprawdzenie to płatne zapytanie, a odzywa się tylko, gdy jest co zgłosić.
 * Reguła liczbowa działa na zboczu: odzywa się, gdy warunek zaczyna być spełniony, i nie częściej niż co ustawioną przerwę.
 */
enum class RuleSource(val title: String, val unit: String, val slack: Boolean = false, val target: String = "", val camera: Boolean = false) {
    FUEL("Paliwo", "l"),
    BATTERY("Akumulator", "V"),
    OUTSIDE("Temperatura na zewnątrz", "°C"),
    SPEED("Prędkość", "km/h"),
    HOME_TEMP("Temperatura w domu", "°C"),
    HOME_HUM("Wilgotność w domu", "%"),
    SLACK_PERSON("Slack: napisała do mnie osoba", "", true, "Osoba (imię albo nazwisko)"),
    SLACK_CHANNEL("Slack: wiadomość na kanale", "", true, "Kanał (np. ogólny)"),
    SLACK_MENTION("Slack: ktoś mnie oznaczył", "", true),
    /** asystent dostaje zdjęcie z kamery i polecenie; odzywa się tylko, gdy jest co zgłosić */
    CAMERA("Kamera: sprawdź obraz", "", target = "Kamera (puste = ta z bloku Kamera)", camera = true),
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
    /** kamera: kiedy sprawdzać – 0 co ustawioną przerwę, 1 po ruszeniu, 2 o godzinie [time] */
    val moment: Int = 0,
    val time: String = "8:00",
) {
    /** godzina sprawdzenia w minutach od północy albo null, gdy wpisana źle */
    fun minuteOfDay(): Int? {
        val p = time.trim().replace('.', ':').split(':')
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = if (p.size > 1) p[1].trim().toIntOrNull() ?: return null else 0
        return if (p.size <= 2 && h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun number() = value.trim().replace(',', '.').toFloatOrNull()

    fun summary(): String = when {
        source == RuleSource.SLACK_MENTION -> source.title
        source.camera -> "Kamera" + (if (value.isBlank()) "" else " „" + value.trim() + "”") + " – " + when (moment) {
            1 -> "po ruszeniu"
            2 -> "o " + time.trim()
            else -> "co " + (COOLDOWNS.firstOrNull { it.first == cooldownMin }?.second ?: "$cooldownMin min")
        }
        source.slack -> source.title + " „" + value.trim().ifEmpty { "?" } + "”"
        else -> source.title + (if (less) " mniej niż " else " więcej niż ") + value.trim().ifEmpty { "?" } + " " + source.unit
    }

    /** Czy reguła ma wszystko, żeby działać. */
    fun complete() = text.isNotBlank() && when {
        source == RuleSource.SLACK_MENTION -> true
        source.camera -> moment != 2 || minuteOfDay() != null
        source.slack -> value.isNotBlank()
        else -> number() != null
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("on", on).put("source", source.name).put("less", less).put("value", value)
        .put("text", text).put("ai", ai).put("cooldown", cooldownMin).put("fired", lastFired).put("slackTs", slackTs).put("moment", moment).put("time", time)

    companion object {
        val COOLDOWNS = listOf(5 to "5 min", 15 to "15 min", 60 to "godzinę", 240 to "4 godziny", 1440 to "dobę")

        fun from(o: JSONObject) = Rule(
            id = o.optLong("id"), on = o.optBoolean("on", true),
            source = runCatching { RuleSource.valueOf(o.optString("source")) }.getOrDefault(RuleSource.FUEL),
            less = o.optBoolean("less", true), value = o.optString("value"), text = o.optString("text"), ai = o.optBoolean("ai", true),
            cooldownMin = o.optInt("cooldown", 60), lastFired = o.optLong("fired"), slackTs = o.optString("slackTs"),
            moment = o.optInt("moment"), time = o.optString("time", "8:00"),
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
        for (r in active.filter { !it.source.slack && !it.source.camera }) {
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
        cameras(act, active.filter { it.source.camera })
    }

    /** jazda trwa (do reguł „po ruszeniu”): zaczyna się przy 10 km/h, kończy po 5 min postoju */
    private var driving = false
    private var stillTicks = 0
    /** reguły z kamerą czekające na sprawdzenie (id → liczba nieudanych prób) */
    private val pending = HashMap<Long, Int>()

    private suspend fun cameras(act: MainActivity, rules: List<Rule>) {
        var started = false
        current(RuleSource.SPEED, act)?.let { v ->
            if (v >= 10f) { if (!driving) started = true; driving = true; stillTicks = 0 }
            else if (v < 3f && driving && ++stillTicks >= 10) driving = false
        }
        val now = System.currentTimeMillis()
        for (r in rules) {
            val rested = now - r.lastFired >= r.cooldownMin * 60_000L
            val due = when (r.moment) {
                1 -> started && rested
                2 -> r.minuteOfDay()?.let { m ->
                    val at = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    // do 10 min po godzinie (radio mogło właśnie wstać), raz na dzień
                    now in at until at + 600_000L && r.lastFired < at
                } ?: false
                else -> rested
            }
            if (due && r.id !in pending) pending[r.id] = 0
        }
        pending.keys.retainAll(rules.map { it.id }.toSet())
        for (r in rules.filter { it.id in pending }) camera(act, r)
    }

    /** Jedno sprawdzenie obrazu: zdjęcie + polecenie właściciela do asystenta; odpowiedź „NIC” = nie ma czego zgłaszać. */
    private suspend fun camera(act: MainActivity, r: Rule) {
        // nie zaczynamy, gdy właściciel właśnie rozmawia z asystentem – spróbujemy za chwilę
        if (Assistant.busy.value || VoiceActivity.open) return
        val look = withContext(Dispatchers.IO) {
            Assistant.look(act, r.value.trim()) { shot ->
                "[Automatyczna reguła Kokpitu – tego nikt teraz nie napisał ani nie powiedział]\n$shot Zdjęcie jest w tej wiadomości.\n" +
                    "Polecenie właściciela dla tej reguły: ${r.text}\n" +
                    "Jeśli według tego polecenia na zdjęciu nie ma nic do zgłoszenia, odpowiedz dokładnie jednym słowem: NIC. " +
                    "W przeciwnym razie odpowiedz krótko, do przeczytania na głos."
            }
        }
        if (!look.ok) {
            val tries = (pending[r.id] ?: 0) + 1
            Log.w(TAG, "kamera (${r.summary()}), próba $tries: ${look.text}")
            if (tries < 5) { pending[r.id] = tries; return }
            // kamera albo asystent nie odpowiadają – odpuszczamy do następnego razu, raz pokazując powód
            pending.remove(r.id)
            update(r.id) { it.copy(lastFired = System.currentTimeMillis()) }
            Assistant.lines += ChatLine(true, "⚡ " + r.summary())
            Assistant.lines += ChatLine(false, "Nie udało się sprawdzić kamery: ${look.text}")
            return
        }
        pending.remove(r.id)
        update(r.id) { it.copy(lastFired = System.currentTimeMillis()) }
        val msg = look.text.trim()
        if (msg.trimEnd('.', '!').equals("NIC", ignoreCase = true)) { Log.i(TAG, "kamera (${r.summary()}): nic do zgłoszenia"); return }
        Log.i(TAG, "reguła: ${r.summary()}")
        Assistant.lines += ChatLine(true, "⚡ " + r.summary())
        Assistant.lines += ChatLine(false, msg)
        // odpowiedź czytamy, gdy nikt akurat nie mówi (czekamy najwyżej minutę; tekst i tak jest w rozmowie)
        repeat(20) { if (Assistant.busy.value || Speech.isSpeaking() || VoiceActivity.open) delay(3000) }
        if (!Assistant.busy.value && !VoiceActivity.open) act.speak(msg)
        if (act.layout.value.none { it.id == BlockId.ASSISTANT }) android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show()
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
