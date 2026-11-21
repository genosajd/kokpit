package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID

/*
 * Dane silnika z gniazda diagnostycznego (OBD) przez moduł ELM327.
 *
 * Moduł (np. „V-LINK”) jest sparowany z Bluetoothem Androida w radiu; Kokpit łączy się z nim po SPP i w kółko
 * pyta o parametry trybu 01 (obroty, prędkość, temperatury, doładowanie…), a co minutę o kody usterek (tryby 03 i 07).
 * To same zapytania odczytu – Kokpit niczego w aucie nie zmienia i nie kasuje usterek.
 *
 * Do prób bez auta: symulator obd/elm327_sim.py po TCP – adres w Prefs.obdTcp (np. 10.0.2.2:35000 na emulatorze),
 * ustawiany hakiem ADB: am broadcast -a pl.lawinski.radiolauncher.TEST --es obdtcp 10.0.2.2:35000 (pusty = Bluetooth).
 *
 * Zapis z auta (BMW F11 Diesel, 2026-11-21): odpowiadają sterowniki 7E8 (silnik) i 7E9 (skrzynia); auto NIE podaje
 * poziomu paliwa, chwilowego spalania ani temperatury oleju. Jedno zapytanie trwa 60–130 ms.
 */
object Obd {
    private const val TAG = "KokpitObd"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val NAMES = Regex("v-?link|obd|elm|icar|viecar|vgate", RegexOption.IGNORE_CASE)
    private const val ENGINE = 0x7E8

    data class State(
        val connected: Boolean = false,
        /** co się dzieje, gdy nie ma danych (do pokazania w klocku) */
        val note: String = "",
        val rpm: Int? = null,
        val speed: Int? = null,
        val coolant: Int? = null,
        val intake: Int? = null,
        val load: Int? = null,
        /** doładowanie ponad ciśnienie otoczenia, w barach */
        val boost: Float? = null,
        val egt: Int? = null,
        /** ciśnienie paliwa w listwie, w barach */
        val rail: Int? = null,
        val pedal: Int? = null,
        val voltage: Float? = null,
        /** czas pracy silnika od uruchomienia, w sekundach */
        val runtime: Int? = null,
        /** kontrolka silnika zapalona */
        val mil: Boolean = false,
        val dtcs: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        /** czy kody usterek zostały już odczytane w tym połączeniu */
        val dtcRead: Boolean = false,
        val at: Long = 0L,
    ) {
        val running: Boolean get() = (rpm ?: 0) > 300

        /** moc chwilowa w KM – szacunek z obciążenia i obrotów (patrz [Obd.power]); null, gdy brak danych albo wyłączone */
        val powerKm: Int? get() = if (running && rpm != null && load != null) power(rpm, load) else null
        val cold: Boolean get() = coolant != null && coolant < COLD_BELOW
    }

    /** podsumowanie jazdy od połączenia (albo od ponownego uruchomienia silnika) */
    data class Trip(val since: Long = 0L, val km: Float = 0f, val moving: Float = 0f, val idle: Float = 0f, val maxSpeed: Int = 0) {
        val movingSec: Int get() = moving.toInt()
        val idleSec: Int get() = idle.toInt()
        val avgSpeed: Int? get() = if (moving + idle < 20f) null else (km / ((moving + idle) / 3600f)).toInt()
    }

    const val COLD_BELOW = 60

    /** silniki Diesla serii F10/F11 do wyboru w Ustawieniach: nazwa, moc w KM, moment w Nm */
    val ENGINES = listOf(Triple("518d", 143, 360), Triple("520d", 184, 380), Triple("520d (190 KM)", 190, 400), Triple("525d", 218, 450), Triple("530d", 258, 560), Triple("535d", 313, 630))

    /**
     * Szacunek mocy chwilowej. Auto nie podaje momentu obrotowego, więc liczymy: obciążenie (ułamek momentu dostępnego
     * przy tych obrotach) × typowa krzywa momentu Diesla z turbo (narasta do 1750 obr./min, stała do 2750, potem opada
     * tak, żeby moc szczytowa wypadła przy 4000) × obroty. Dokładność rzędu kilkunastu procent – to wskaźnik, nie hamownia.
     */
    fun power(rpm: Int, loadPct: Int): Int? {
        val p = Prefs.engineSpec.split('/')
        val maxKm = p.getOrNull(0)?.toFloatOrNull() ?: return null
        val maxNm = p.getOrNull(1)?.toFloatOrNull() ?: return null
        val peakNm = maxKm / 1.36f * 9549f / 4000f // moment przy mocy szczytowej
        fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x.coerceIn(0f, 1f)
        val r = rpm.toFloat()
        val nm = when {
            r < 1000f -> lerp(0.45f, 0.6f, (r - 700f) / 300f) * maxNm
            r < 1750f -> lerp(0.6f, 1f, (r - 1000f) / 750f) * maxNm
            r < 2750f -> maxNm
            r < 4000f -> lerp(maxNm, peakNm, (r - 2750f) / 1250f)
            else -> lerp(peakNm, peakNm * 0.68f, (r - 4000f) / 1000f)
        }
        return (loadPct / 100f * nm * r / 9549f * 1.36f).toInt().coerceAtMost(maxKm.toInt())
    }

    val state = mutableStateOf(State())
    val trip = mutableStateOf(Trip())

    val enabled: Boolean get() = Prefs.obd

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var generation = 0
    private lateinit var app: Context

    /** Wołane przy starcie Kokpitu i po zmianie ustawień: uruchamia albo zatrzymuje odczyt. */
    fun sync(ctx: Context) {
        app = ctx.applicationContext
        val gen = ++generation
        worker?.interrupt()
        if (!enabled) { state.value = State(); return }
        worker = Thread({ loop(gen) }, "KokpitObd").apply { isDaemon = true; start() }
    }

    private fun alive(gen: Int) = gen == generation && enabled

    private fun loop(gen: Int) {
        while (alive(gen)) {
            var link: Link? = null
            try {
                link = open()
                if (link != null) session(gen, link)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "połączenie: $e")
                state.value = State(note = "Moduł OBD nie odpowiada – włącz go przyciskiem")
            } finally {
                runCatching { link?.close() }
            }
            if (alive(gen)) pause(gen, 12_000)
        }
        if (gen == generation) state.value = State()
    }

    private fun pause(gen: Int, ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (alive(gen) && System.currentTimeMillis() < until) Thread.sleep(300)
    }

    // ---------- połączenie ----------

    private class Link(val input: InputStream, val output: OutputStream, private val closer: () -> Unit) {
        fun close() = closer()

        /** Wyślij polecenie i czytaj do znaku zachęty „>”. */
        fun ask(cmd: String, timeoutMs: Long = 4000): String {
            while (input.available() > 0) input.read()
            output.write((cmd + "\r").toByteArray()); output.flush()
            val sb = StringBuilder()
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                if (input.available() > 0) {
                    val c = input.read()
                    if (c < 0) throw java.io.IOException("połączenie zamknięte")
                    if (c == '>'.code) return sb.toString()
                    sb.append(c.toChar())
                } else Thread.sleep(8)
            }
            throw java.io.IOException("brak odpowiedzi na $cmd")
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): Link? {
        val tcp = Prefs.obdTcp.trim()
        if (tcp.isNotEmpty()) {
            val host = tcp.substringBeforeLast(':')
            val port = tcp.substringAfterLast(':').toIntOrNull() ?: 35000
            return try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                Link(s.getInputStream(), s.getOutputStream()) { s.close() }
            } catch (e: Exception) {
                state.value = State(note = "Symulator OBD ($tcp) nie odpowiada")
                null
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            state.value = State(note = "Brak zgody na Bluetooth – wyłącz i włącz OBD w Ustawieniach")
            return null
        }
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            state.value = State(note = "Bluetooth w radiu jest wyłączony")
            return null
        }
        val want = Prefs.obdDevice.trim()
        val dev = adapter.bondedDevices.firstOrNull { d ->
            val n = d.name ?: ""
            if (want.isNotEmpty()) n.contains(want, ignoreCase = true) else NAMES.containsMatchIn(n)
        }
        if (dev == null) {
            state.value = State(note = "Sparuj moduł OBD w ustawieniach Bluetooth Androida")
            return null
        }
        return try {
            val s = dev.createInsecureRfcommSocketToServiceRecord(SPP)
            s.connect()
            Link(s.inputStream, s.outputStream) { s.close() }
        } catch (e: Exception) {
            Log.i(TAG, "${dev.name}: ${e.message}")
            state.value = State(note = "Moduł OBD nie odpowiada – włącz go przyciskiem")
            null
        }
    }

    // ---------- rozmowa z modułem ----------

    /** Odpowiedź modułu (z nagłówkami, ATH1) → dane każdego sterownika, ramki wieloczęściowe sklejone. */
    internal fun parse(raw: String): Map<Int, ByteArray> {
        val parts = HashMap<Int, java.io.ByteArrayOutputStream>()
        val lengths = HashMap<Int, Int>()
        for (line in raw.split('\r', '\n')) {
            val t = line.trim().split(' ').filter { it.isNotEmpty() }
            if (t.size < 3 || t[0].length != 3) continue
            val id = t[0].toIntOrNull(16) ?: continue
            val b = t.drop(1).map { it.toIntOrNull(16) ?: return@map -1 }
            if (b.any { it < 0 }) continue
            val out = parts.getOrPut(id) { java.io.ByteArrayOutputStream() }
            val pci = b[0]
            when (pci and 0xF0) {
                0x00 -> { lengths[id] = pci; b.drop(1).forEach { out.write(it) } }
                0x10 -> { lengths[id] = ((pci and 0x0F) shl 8) or b[1]; b.drop(2).forEach { out.write(it) } }
                0x20 -> b.drop(1).forEach { out.write(it) }
            }
        }
        return parts.mapValues { (id, o) -> o.toByteArray().let { a -> a.copyOf(minOf(a.size, lengths[id] ?: a.size)) } }
    }

    /** Dane parametru trybu 01 ze sterownika silnika (bez „41 PID”) albo null. */
    private fun Link.pid(pid: Int): IntArray? {
        val p = parse(ask(String.format("01%02X", pid)))[ENGINE] ?: return null
        if (p.size < 3 || (p[0].toInt() and 0xFF) != 0x41 || (p[1].toInt() and 0xFF) != pid) return null
        return IntArray(p.size - 2) { p[it + 2].toInt() and 0xFF }
    }

    private fun codes(payloads: Collection<ByteArray>, reply: Int): List<String> {
        val out = LinkedHashSet<String>()
        for (p in payloads) {
            if (p.size < 2 || (p[0].toInt() and 0xFF) != reply) continue
            var i = 2 // CAN: po bajcie odpowiedzi idzie liczba kodów
            while (i + 1 < p.size) {
                val a = p[i].toInt() and 0xFF
                val b = p[i + 1].toInt() and 0xFF
                if (a != 0 || b != 0) out += "PCBU"[a shr 6] + String.format("%01X%01X%02X", (a shr 4) and 3, a and 0x0F, b)
                i += 2
            }
        }
        return out.toList()
    }

    private fun session(gen: Int, link: Link) {
        state.value = State(note = "Łączę z modułem OBD…")
        link.ask("ATZ", 6000)
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATSP0")) link.ask(c)
        // które parametry auto obsługuje
        val supported = HashSet<Int>()
        var base = 0
        while (base <= 0xA0) {
            val d = runCatching { link.pid(base) }.getOrNull()
            if (d == null || d.size < 4) {
                if (base == 0) { state.value = State(note = "Auto nie odpowiada – włącz zapłon"); return }
                break
            }
            for (i in 0 until 32) if (d[i / 8] and (0x80 shr (i % 8)) != 0) supported += base + i + 1
            if (base + 0x20 !in supported) break
            base += 0x20
        }
        Log.i(TAG, "połączono, parametrów: ${supported.size}")
        var s = State(connected = true, at = System.currentTimeMillis())
        state.value = s
        trip.value = Trip(since = System.currentTimeMillis())

        val slow = listOf(0x05, 0x04, 0x70, 0x0F, 0x78, 0x42, 0x49, 0x6D, 0x1F, 0x01).filter { it in supported }
        var baro = 100
        if (0x33 in supported) link.pid(0x33)?.let { baro = it[0] }
        var n = 0
        var lastDtc = 0L
        var lastTick = System.currentTimeMillis()
        var lastRuntime = -1
        var failures = 0
        while (alive(gen)) {
            val now = System.currentTimeMillis()
            try {
                if (0x0C in supported) link.pid(0x0C)?.let { s = s.copy(rpm = (it[0] * 256 + it[1]) / 4) }
                if (0x0D in supported) link.pid(0x0D)?.let { s = s.copy(speed = it[0]) }
                // obciążenie w każdym obiegu, gdy pokazujemy moc (inaczej wskazanie spóźniałoby się o kilka sekund)
                if (0x04 in supported && Prefs.engineSpec.isNotEmpty()) link.pid(0x04)?.let { s = s.copy(load = it[0] * 100 / 255) }
                if (slow.isNotEmpty()) {
                    val pid = slow[n++ % slow.size]
                    link.pid(pid)?.let { d ->
                        s = when (pid) {
                            0x05 -> s.copy(coolant = d[0] - 40)
                            0x04 -> s.copy(load = d[0] * 100 / 255)
                            0x0F -> s.copy(intake = d[0] - 40)
                            // doładowanie: bajt 0 = które pola są ważne, bajty 3–4 = ciśnienie rzeczywiste (1/32 kPa, bezwzględne)
                            0x70 -> if (d.size >= 5) s.copy(boost = (((d[3] * 256 + d[4]) / 32f - baro) / 100f).coerceAtLeast(0f)) else s
                            // temperatura spalin: bajty 1–2 = czujnik 1 (0,1 °C, przesunięcie −40)
                            0x78 -> if (d.size >= 3) s.copy(egt = (d[1] * 256 + d[2]) / 10 - 40) else s
                            0x42 -> s.copy(voltage = (d[0] * 256 + d[1]) / 1000f)
                            0x49 -> s.copy(pedal = d[0] * 100 / 255)
                            // ciśnienie paliwa: bajty 3–4 = rzeczywiste (10 kPa) → bary
                            0x6D -> if (d.size >= 5) s.copy(rail = (d[3] * 256 + d[4]) / 10) else s
                            0x1F -> s.copy(runtime = d[0] * 256 + d[1])
                            0x01 -> s.copy(mil = d[0] and 0x80 != 0)
                            else -> s
                        }
                    }
                }
                if (now - lastDtc > 60_000) {
                    lastDtc = now
                    val stored = codes(parse(link.ask("03")).values, 0x43)
                    val pend = codes(parse(link.ask("07")).values, 0x47)
                    if (stored != s.dtcs || pend != s.pending) Log.i(TAG, "usterki: $stored, oczekujące: $pend")
                    s = s.copy(dtcs = stored, pending = pend - stored.toSet(), dtcRead = true)
                }
                failures = 0
            } catch (e: java.io.IOException) {
                if (++failures >= 3) throw e
            }
            // podsumowanie jazdy; silnik uruchomiony na nowo = nowa jazda
            val dt = (now - lastTick) / 1000f
            lastTick = now
            val run = s.runtime ?: -1
            if (lastRuntime >= 0 && run in 0 until lastRuntime - 5) trip.value = Trip(since = now)
            lastRuntime = run
            if (s.running && dt in 0f..5f) {
                val v = s.speed ?: 0
                val t = trip.value
                trip.value = t.copy(
                    km = t.km + v * dt / 3600f,
                    moving = t.moving + if (v > 2) dt else 0f,
                    idle = t.idle + if (v <= 2) dt else 0f,
                    maxSpeed = maxOf(t.maxSpeed, v),
                )
            }
            s = s.copy(at = now)
            state.value = s
            // gdy Kokpit jest schowany, wystarczy rzadki odczyt (asystent, reguły)
            if (MainActivity.live?.visible?.value != true) Thread.sleep(1500)
        }
    }

    // ---------- tekst dla asystenta ----------

    private fun fresh(s: State) = s.connected && System.currentTimeMillis() - s.at < 20_000

    private fun dec(v: Float, digits: Int = 1) = String.format(Locale("pl", "PL"), "%.${digits}f", v)

    /** Krótki wiersz dopisywany do każdej wypowiedzi rozmówcy (asystent ma bieżące dane bez pytania narzędzia). */
    fun contextLine(): String {
        val s = state.value
        if (!enabled || !Prefs.obdContext || !Access.allows("engine_data") || !fresh(s)) return ""
        val parts = buildList {
            if (!s.running) add("silnik nie pracuje")
            s.rpm?.takeIf { s.running }?.let { add("obroty $it") }
            s.speed?.let { add("prędkość $it km/h") }
            s.coolant?.let { add("płyn chłodzący $it °C") }
            s.powerKm?.let { add("moc chwilowa ok. $it KM") }
            s.boost?.takeIf { s.running }?.let { add("doładowanie ${dec(it, 2)} bara") }
            s.egt?.let { add("spaliny $it °C") }
            s.voltage?.let { add("napięcie ${dec(it)} V") }
            s.runtime?.takeIf { s.running }?.let { add("silnik pracuje ${it / 60} min") }
            if (s.mil) add("kontrolka silnika ŚWIECI")
            if (s.dtcRead) {
                add("usterki zapisane: " + if (s.dtcs.isEmpty()) "brak" else s.dtcs.joinToString(", "))
                if (s.pending.isNotEmpty()) add("usterki oczekujące: " + s.pending.joinToString(", "))
            }
        }
        return "(Radio – dane silnika z OBD: ${parts.joinToString("; ")})\n"
    }

    /** Pełny stan dla narzędzia asystenta. */
    fun report(): String {
        val s = state.value
        if (!enabled) return "Odczyt OBD jest wyłączony (Ustawienia Kokpitu → Auto → „Dane silnika (OBD)”)."
        if (!fresh(s)) return "Brak danych z OBD: " + s.note.ifEmpty { "moduł niepołączony" }
        val t = trip.value
        return buildString {
            appendLine("Dane ze sterownika silnika (gniazdo OBD, odczyt sprzed chwili):")
            appendLine("Silnik: " + if (s.running) "pracuje" else "nie pracuje (sam zapłon)")
            s.rpm?.let { appendLine("Obroty: $it na minutę") }
            s.speed?.let { appendLine("Prędkość: $it km/h") }
            s.load?.let { appendLine("Obciążenie silnika: $it %") }
            s.powerKm?.let { appendLine("Moc chwilowa: ok. $it KM (szacunek z obciążenia i obrotów, silnik ${Prefs.engineSpec.replace("/", " KM / ")} Nm)") }
            s.pedal?.let { appendLine("Pedał gazu: $it %") }
            s.coolant?.let { appendLine("Płyn chłodzący: $it °C" + if (s.cold) " (silnik zimny – normalna temperatura pracy to ok. 85–95 °C)" else "") }
            s.intake?.let { appendLine("Powietrze w dolocie: $it °C") }
            s.boost?.let { appendLine("Doładowanie: ${dec(it, 2)} bara ponad ciśnienie otoczenia") }
            s.rail?.let { appendLine("Ciśnienie paliwa w listwie: $it barów") }
            s.egt?.let { appendLine("Temperatura spalin (czujnik 1): $it °C" + if (it > 550) " – tak wysoka zwykle oznacza wypalanie filtra cząstek stałych" else "") }
            s.voltage?.let { appendLine("Napięcie w sterowniku silnika: ${dec(it)} V") }
            s.runtime?.let { appendLine("Czas pracy silnika od uruchomienia: ${it / 60} min") }
            appendLine("Kontrolka silnika: " + if (s.mil) "świeci" else "nie świeci")
            if (s.dtcRead) {
                fun list(l: List<String>) = if (l.isEmpty()) "brak" else l.joinToString("; ") { "$it (${ObdCodes.describe(it)})" }
                appendLine("Zapisane kody usterek (silnik i skrzynia): " + list(s.dtcs))
                appendLine("Oczekujące kody usterek: " + list(s.pending))
            } else appendLine("Kody usterek: jeszcze nieodczytane")
            if (t.since > 0) {
                appendLine("Ta jazda (od połączenia z modułem): ${dec(t.km)} km, w ruchu ${t.movingSec / 60} min, postój z pracującym silnikiem ${t.idleSec / 60} min, największa prędkość ${t.maxSpeed} km/h" + (t.avgSpeed?.let { ", średnia $it km/h" } ?: ""))
            }
            append("Auto nie podaje przez OBD poziomu paliwa, chwilowego spalania ani temperatury oleju (paliwo jest w car_status).")
        }
    }
}
