package pl.lawinski.radiolauncher

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as CPhone
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/*
 * Telefon przez Bluetooth radia (pkt 82). Moduł Feasycom radia (com.wits.bt) steruje się rozgłoszeniami,
 * a stan rozmowy sam rozgłasza. Potwierdzone na żywo 2026-11-14:
 *   - zadzwoń:  com.wits.BROADCAST_CMD_TO_BT  cmd=1  data=<numer>
 *   - odbierz:  com.can.ACTION_APP_DO_KEY     key_code=46
 *   - rozłącz:  com.can.ACTION_APP_DO_KEY     key_code=47
 *   - stan:     com.can.ACTION_BT_INFO        state(3 bezczynny,4 wychodzące,5 dzwoni,6 rozmowa,7 koniec),
 *               number, name, przy łączeniu phoneName
 * Kontakty: konto Google w radiu (ContactsContract) – jedna osoba = jeden kontakt z listą numerów,
 * ulubione = gwiazdka Google (zmiana gwiazdki w Kokpicie zapisuje się na koncie).
 * Cała obsługa jest w Kokpicie (blok Telefon + okno rozmowy), bez osobnej aplikacji i bez uprawnień systemowych.
 */
object Phone {
    const val IDLE = 3
    const val OUTGOING = 4
    const val INCOMING = 5
    const val ACTIVE = 6
    const val ENDED = 7

    private const val ACT_DIAL = "com.wits.BROADCAST_CMD_TO_BT"
    private const val ACT_KEY = "com.can.ACTION_APP_DO_KEY"
    private const val ACT_INFO = "com.can.ACTION_BT_INFO"
    private const val KEY_ANSWER = 46
    private const val KEY_HANGUP = 47

    /**
     * Bieżąca rozmowa. [since] = początek rozmowy (stan ACTIVE) albo dzwonienia;
     * [inbound] = ktoś dzwoni do nas; [answered] = doszło do rozmowy.
     */
    data class Call(
        val state: Int, val number: String, val name: String, val since: Long,
        val inbound: Boolean = false, val answered: Boolean = false,
    ) {
        val active get() = state == OUTGOING || state == INCOMING || state == ACTIVE
        val ringing get() = state == INCOMING
    }

    /** Bieżąca rozmowa (IDLE = brak). */
    val call = mutableStateOf(Call(IDLE, "", "", 0L))

    /** Czy telefon jest połączony z Bluetoothem radia (stan z radia; poza radiem – dopiero po rozgłoszeniu). */
    val connected = mutableStateOf(false)

    /** Nazwa połączonego telefonu (z rozgłoszenia radia), pusta gdy nieznana. */
    val phoneName = mutableStateOf("")

    private var installed = false
    private var historyFile: File? = null
    private val main = Handler(Looper.getMainLooper())

    fun install(ctx: Context) {
        if (installed || !Radio.witstek) return // moduł telefonu jest tylko w radiu Witstek
        installed = true
        val app = ctx.applicationContext
        historyFile = File(app.filesDir, "call_history.json")
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACT_INFO), ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            app.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        main.removeCallbacks(reloadLater)
                        main.postDelayed(reloadLater, 1500)
                    }
                },
            )
        }
        appCtx = app
        // nazwa telefonu znana modułowi BT radia (np. „F06C…_xiaomi”) – do czasu pierwszego rozgłoszenia
        runCatching {
            val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "vendor.blueware.hfp.device") as? String
            val n = v.orEmpty().substringAfter('_', "").trim()
            if (n.isNotEmpty() && phoneName.value.isEmpty()) phoneName.value = n.replaceFirstChar { it.uppercase() }
        }
        // stan połączenia: radio trzyma go w Settings.System „wits.hfp” (3+ = telefon połączony)
        readHfp(app)
        runCatching {
            app.contentResolver.registerContentObserver(
                android.provider.Settings.System.getUriFor(HFP_KEY), false,
                object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { readHfp(app) } },
            )
        }
        reload(app)
    }

    private const val HFP_KEY = "wits.hfp"

    private fun readHfp(ctx: Context) {
        val v = runCatching { android.provider.Settings.System.getString(ctx.contentResolver, HFP_KEY)?.toIntOrNull() }.getOrNull()
            ?: runCatching {
                ctx.contentResolver.query(android.provider.Settings.System.CONTENT_URI, arrayOf("name", "value"), "name=?", arrayOf(HFP_KEY), null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(1)?.toIntOrNull() else null }
            }.getOrNull()
        Log.i("KokpitPhone", "wits.hfp=$v")
        if (v != null) connected.value = v >= IDLE
    }

    private var appCtx: Context? = null
    private val reloadLater = Runnable { appCtx?.let { reload(it) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACT_INFO) return
            val state = i.getIntExtra("state", IDLE)
            // rozgłoszenie stanu połączenia (phoneName) albo rozmowy (number/name)
            if (i.hasExtra("phoneName") && !i.hasExtra("number")) {
                connected.value = state >= IDLE
                phoneName.value = if (state >= IDLE) i.getStringExtra("phoneName").orEmpty().trim() else ""
                return
            }
            connected.value = true
            onState(c, state, i.getStringExtra("number").orEmpty().trim(), i.getStringExtra("name").orEmpty().trim())
        }
    }

    private fun onState(ctx: Context, state: Int, rawNumber: String, rawName: String) {
        Log.i("KokpitPhone", "stan=$state numer=${rawNumber.take(4)}…")
        val prev = call.value
        val now = System.currentTimeMillis()
        when (state) {
            OUTGOING, INCOMING, ACTIVE -> {
                val number = rawNumber.ifBlank { prev.number }
                val name = find(number)?.first?.name ?: rawName.ifBlank { prev.name }
                val fresh = !prev.active
                val answered = state == ACTIVE || (!fresh && prev.answered)
                val since = if (state == ACTIVE && !prev.answered) now else if (fresh) now else prev.since
                val inbound = if (fresh) state == INCOMING else prev.inbound
                call.value = Call(state, number, name, since, inbound, answered)
                // zaczyna się rozmowa (np. asystent właśnie zadzwonił) – okienko asystenta znika, zostaje okno rozmowy
                val voice = fresh && VoiceActivity.closeIfOpen()
                when {
                    // przychodzące: okno zawsze (nawet gdy Kokpit na wierzchu, żeby nie przegapić)
                    state == INCOMING && prev.state != INCOMING -> { minimized.value = false; CallPill.hide(); CallActivity.open(ctx) }
                    // wychodzące: okno tylko, gdy Kokpit nie jest na ekranie (inaczej widać blok / okno telefonu)
                    // (po zamknięciu okienka asystenta okno rozmowy zawsze, żeby było widać, że dzwoni)
                    state == OUTGOING && prev.state != OUTGOING -> if (voice) CallActivity.open(ctx) else showWindow(ctx)
                }
            }
            else -> {
                if (prev.active) {
                    val type = if (!prev.inbound) "out" else if (prev.answered) "in" else "missed"
                    val seconds = if (prev.answered) ((now - prev.since) / 1000).coerceAtLeast(0) else 0
                    record(ctx, prev.number, prev.name, type, seconds)
                }
                call.value = Call(IDLE, "", "", 0L)
                minimized.value = false
                CallPill.hide()
                CallActivity.closeIfOpen()
            }
        }
    }

    // --- zwinięte okno rozmowy ---

    /** okno rozmowy zwinięte do paska u góry ekranu (rozmowa trwa) */
    val minimized = mutableStateOf(false)

    fun minimize(ctx: Context) {
        if (!call.value.active) return
        minimized.value = true
        CallActivity.closeIfOpen()
        CallPill.show(ctx)
    }

    /** Powrót do pełnego okna rozmowy. */
    fun restore(ctx: Context) {
        minimized.value = false
        CallPill.hide()
        if (call.value.active) CallActivity.open(ctx)
    }

    private fun showWindow(ctx: Context) {
        val act = MainActivity.live
        if (act != null && act.visible.value) return
        CallActivity.open(ctx)
    }

    // --- kierownica w oknie telefonu: przewijanie ulubionych i dzwonienie drugim naciśnięciem ---

    /** okno telefonu jest na ekranie */
    val windowOpen = mutableStateOf(false)

    /** ulubiony wybrany przyciskami kierownicy (pozycja na liście ulubionych), -1 = żaden */
    val favIndex = mutableIntStateOf(-1)

    fun favourites(): List<Contact> = book.value.filter { it.starred }

    /** Następny / poprzedni ulubiony (przyciski kierownicy). Zwraca true, gdy okno telefonu to obsłużyło. */
    fun wheel(delta: Int): Boolean {
        if (!windowOpen.value || call.value.active) return false
        val n = favourites().size
        if (n == 0) return false
        favIndex.intValue = if (favIndex.intValue < 0) 0 else ((favIndex.intValue + delta) % n + n) % n
        return true
    }

    /**
     * Przycisk telefonu przy otwartym oknie: pierwsze naciśnięcie zaznacza ulubionego,
     * następne dzwoni na jego numer główny. Zwraca true, gdy okno telefonu to obsłużyło.
     */
    fun phoneKeyInWindow(ctx: Context): Boolean {
        if (!windowOpen.value || call.value.active) return false
        val favs = favourites()
        if (favs.isEmpty()) return true
        val i = favIndex.intValue
        if (i !in favs.indices) { favIndex.intValue = 0; return true }
        if (connected.value) dial(ctx, favs[i].main.number)
        return true
    }

    /**
     * Przycisk telefonu w czasie połączenia: dzwoni → odbierz, rozmowa / wybieranie → rozłącz.
     * Tuż po odebraniu (2,5 s) nic nie robi – radio mogło odebrać tym samym naciśnięciem i rozmowa
     * nie może się od razu rozłączyć. Zwraca true, gdy trwa połączenie.
     */
    fun phoneKeyInCall(ctx: Context): Boolean {
        val c = call.value
        if (!c.active) return false
        when {
            c.ringing -> answer(ctx)
            c.state == ACTIVE && System.currentTimeMillis() - c.since < 2500 -> {}
            else -> hangup(ctx)
        }
        return true
    }

    // --- sterowanie ---

    fun dial(ctx: Context, rawNumber: String) {
        val number = rawNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isBlank()) return
        if (!connected.value) Log.w("KokpitPhone", "telefon niepołączony z BT radia")
        ctx.sendBroadcast(Intent(ACT_DIAL).putExtra("cmd", 1).putExtra("data", number))
        Log.i("KokpitPhone", "dzwonię ${number.take(4)}…")
    }

    fun answer(ctx: Context) = sendKey(ctx, KEY_ANSWER)
    fun hangup(ctx: Context) = sendKey(ctx, KEY_HANGUP)

    private fun sendKey(ctx: Context, code: Int) {
        ctx.sendBroadcast(Intent(ACT_KEY).putExtra("key_code", code))
    }

    // --- kontakty Google: jedna osoba = jeden kontakt z listą numerów ---

    data class Num(val number: String, val label: String, val primary: Boolean)

    data class Contact(val id: Long, val name: String, val photo: String?, val starred: Boolean, val numbers: List<Num>) {
        /** numer główny: oznaczony w kontaktach jako domyślny, inaczej komórka, inaczej pierwszy */
        val main: Num get() = numbers.firstOrNull { it.primary } ?: numbers.firstOrNull { it.label == "Komórka" } ?: numbers.first()
    }

    /** Wszystkie kontakty z numerem telefonu, alfabetycznie. */
    val book = mutableStateOf<List<Contact>>(emptyList())

    /** Kontakty wczytane choć raz (odróżnia „brak kontaktów” od „jeszcze czytam”). */
    val bookLoaded = mutableStateOf(false)

    @Volatile
    private var byDigits: Map<String, Pair<Contact, Num>> = emptyMap()

    /** Klucz porównywania numerów: ostatnie 9 cyfr (pomija +48 i spacje). */
    fun digits(n: String) = n.filter { it.isDigit() }.takeLast(9)

    fun contactsGranted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun label(type: Int, custom: String?): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE, ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "Komórka"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Dom"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK, ContactsContract.CommonDataKinds.Phone.TYPE_COMPANY_MAIN -> "Praca"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Główny"
        ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> custom?.takeIf { it.isNotBlank() } ?: "Inny"
        else -> "Inny"
    }

    @Volatile
    private var loading = false

    /** Czyta kontakty w tle. */
    fun reload(ctx: Context) {
        if (!contactsGranted(ctx) || loading) return
        loading = true
        val app = ctx.applicationContext
        thread(name = "kokpit-contacts") {
            try {
                class Tmp(val name: String, val photo: String?, val starred: Boolean, val nums: ArrayList<Num> = ArrayList())
                val map = LinkedHashMap<Long, Tmp>()
                app.contentResolver.query(
                    CPhone.CONTENT_URI,
                    arrayOf(CPhone.CONTACT_ID, CPhone.DISPLAY_NAME, CPhone.NUMBER, CPhone.TYPE, CPhone.LABEL, CPhone.IS_SUPER_PRIMARY, CPhone.PHOTO_THUMBNAIL_URI, CPhone.STARRED),
                    null, null, CPhone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
                )?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(1).orEmpty().trim()
                        val num = it.getString(2).orEmpty().trim()
                        if (name.isBlank() || digits(num).length < 3) continue
                        val t = map.getOrPut(it.getLong(0)) { Tmp(name, it.getString(6), it.getInt(7) != 0) }
                        if (t.nums.none { n -> digits(n.number) == digits(num) })
                            t.nums.add(Num(num, label(it.getInt(3), it.getString(4)), it.getInt(5) != 0))
                    }
                }
                val list = map.map { (id, t) -> Contact(id, t.name, t.photo, t.starred, t.nums) }
                val index = HashMap<String, Pair<Contact, Num>>()
                list.forEach { c -> c.numbers.forEach { n -> index.putIfAbsent(digits(n.number), c to n) } }
                byDigits = index
                main.post { book.value = list; bookLoaded.value = true }
                Log.i("KokpitPhone", "kontakty: ${list.size}, ulubione: ${list.count { it.starred }}")
            } catch (e: Exception) {
                Log.w("KokpitPhone", "kontakty: ${e.message}")
                main.post { bookLoaded.value = true }
            } finally {
                loading = false
            }
        }
    }

    /** Kontakt i jego numer pasujący do [number] (po ostatnich 9 cyfrach). */
    fun find(number: String): Pair<Contact, Num>? {
        val k = digits(number)
        return if (k.length < 5) null else byDigits[k]
    }

    /** Podpowiedź przy wpisywaniu numeru: pierwszy kontakt, którego numer zaczyna się od wpisanych cyfr. */
    fun suggest(typed: String): Pair<Contact, Num>? {
        val t = typed.filter { it.isDigit() }
        if (t.length < 3) return null
        find(t)?.let { return it }
        book.value.forEach { c -> c.numbers.forEach { n -> if (n.number.filter { it.isDigit() }.removePrefix("48").startsWith(t)) return c to n } }
        return null
    }

    fun nameFor(number: String): String = find(number)?.first?.name.orEmpty()

    /** Gwiazdka Google (ulubione) – zapis na koncie; wymaga zgody WRITE_CONTACTS. */
    fun setStarred(ctx: Context, c: Contact, on: Boolean): Boolean = try {
        val v = ContentValues().apply { put(ContactsContract.Contacts.STARRED, if (on) 1 else 0) }
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, c.id), v, null, null)
        // od razu w widoku, potem odświeżenie z bazy
        book.value = book.value.map { if (it.id == c.id) it.copy(starred = on) else it }
        reload(ctx)
        n > 0
    } catch (e: Exception) {
        Log.w("KokpitPhone", "gwiazdka: ${e.message}")
        false
    }

    private val photos = LruCache<String, Bitmap>(80)

    /** Zdjęcie kontaktu (miniatura) – wołać poza wątkiem głównym. */
    fun photo(ctx: Context, uri: String): Bitmap? {
        photos.get(uri)?.let { return it }
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.also { photos.put(uri, it) }
        } catch (_: Exception) { null }
    }

    fun cachedPhoto(uri: String): Bitmap? = photos.get(uri)

    // --- historia (plik call_history.json; typ: in/out/missed; zapis po zakończeniu rozmowy) ---

    data class Entry(val number: String, val name: String, val type: String, val time: Long, val seconds: Long)

    /** zwiększany po każdym zapisie, żeby widoki odświeżyły listę */
    val historyTick = mutableIntStateOf(0)

    fun history(): List<Entry> {
        val f = historyFile ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Entry(it.optString("number"), it.optString("name"), it.optString("type"), it.optLong("time"), it.optLong("seconds"))
            }.reversed()
        } catch (_: Exception) { emptyList() }
    }

    /** Wcześniejsze połączenia z tym numerem albo z kontaktem, do którego należy. */
    fun historyFor(number: String): List<Entry> {
        val keys = find(number)?.first?.numbers?.map { digits(it.number) }?.toSet() ?: setOf(digits(number))
        return history().filter { digits(it.number) in keys }
    }

    private fun record(ctx: Context, number: String, name: String, type: String, seconds: Long) {
        if (number.isBlank()) return
        val f = historyFile ?: File(ctx.filesDir, "call_history.json").also { historyFile = it }
        try {
            val arr = if (f.exists()) JSONArray(f.readText()) else JSONArray()
            arr.put(JSONObject().apply {
                put("number", number); put("name", name); put("type", type)
                put("time", System.currentTimeMillis()); put("seconds", seconds)
            })
            val keep = JSONArray()
            for (k in maxOf(0, arr.length() - 100) until arr.length()) keep.put(arr.get(k))
            f.writeText(keep.toString())
        } catch (_: Exception) {}
        main.post { historyTick.intValue++ }
    }

    fun clearHistory() { historyFile?.delete(); historyTick.intValue++ }

    /** Narzędzie asystenta: zadzwoń / odbierz / rozłącz. */
    fun run(ctx: Context, input: JSONObject): String {
        return when (input.optString("action")) {
            "call" -> {
                val target = input.optString("number").trim()
                if (target.isBlank()) return "Podaj numer albo imię kontaktu."
                val number = resolve(target) ?: return "Nie znalazłem kontaktu „$target”. Podaj numer."
                if (!connected.value) return "Telefon nie jest połączony z Bluetoothem radia."
                if (call.value.active) return "Trwa już rozmowa."
                dial(ctx, number)
                "Dzwonię do ${nameFor(number).ifBlank { number }}."
            }
            "answer" -> { if (!call.value.ringing) return "Nie ma połączenia przychodzącego."; answer(ctx); "Odbieram." }
            "hangup" -> { if (!call.value.active) return "Nie ma aktywnej rozmowy."; hangup(ctx); "Rozłączam." }
            "status" -> {
                val who = call.value.name.ifBlank { call.value.number }
                if (!connected.value) "Telefon niepołączony z BT radia."
                else when (call.value.state) {
                    OUTGOING -> "Dzwoni do $who."
                    INCOMING -> "Połączenie przychodzące od $who."
                    ACTIVE -> "Trwa rozmowa z $who."
                    else -> "Brak rozmowy, telefon połączony."
                }
            }
            else -> "Nieznana akcja."
        }
    }

    /** Numer z tekstu: same cyfry albo kontakt po nazwie (numer główny). */
    private fun resolve(target: String): String? {
        val d = target.filter { it.isDigit() || it == '+' }
        if (d.count { it.isDigit() } >= 6 && target.none { it.isLetter() }) return d
        val q = target.lowercase()
        val list = book.value
        val hit = list.firstOrNull { it.name.equals(target, ignoreCase = true) }
            ?: list.firstOrNull { it.name.lowercase().split(' ').any { w -> w == q } }
            ?: list.firstOrNull { it.name.lowercase().contains(q) }
        return hit?.main?.number
    }
}
