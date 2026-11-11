package pl.lawinski.radiolauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.zip.ZipInputStream

/*
 * Hasło „OK BMW” – rozpoznawane w całości w radiu (Vosk, polski mały model z assets/vosk-pl.zip),
 * bez internetu i bez zewnętrznych usług. Rozpoznawanie ograniczone do kilku fraz (gramatyka),
 * więc lżejsze niż pełne dyktowanie.
 *
 * Domyślnie WYŁĄCZONE (Ustawienia → Asystent AI → „OK BMW”). Wyłączone = brak usługi, modelu
 * w pamięci i nasłuchu mikrofonu. Włączone: usługa pierwszoplanowa typu „mikrofon” z powiadomieniem;
 * po haśle oddaje mikrofon i otwiera okienko asystenta (VoiceActivity), po jego zamknięciu słucha dalej.
 * Słucha też, gdy gra muzyka (na nagraniach z muzyką w tle nie było fałszywych wywołań); wyłącza się tylko,
 * gdy mówi asystent. Czułość: Prefs.hotwordSens.
 * Dźwięk: automatyczne wzmocnienie cichego mikrofonu (Prefs.micGain) i wybór trybu mikrofonu (Prefs.micMode);
 * stan, poziom i rozpoznane słowa widać w Ustawieniach (status/level/heard).
 * Otwieranie okienka z tła wymaga zgody „Wyświetlanie nad innymi aplikacjami”.
 */
object Hotword {
    private const val TAG = "KokpitHotword"
    private const val MODEL_ASSET = "vosk-pl.zip"
    private const val MODEL_VERSION = "small-pl-0.22"

    /** domyślne hasła, które otwierają asystenta (własne: Prefs.hotwordPhrase) */
    val DEFAULT_PHRASES = listOf("ok bmw", "okej bmw", "hej bmw")

    /** Hasła, na które teraz reaguje (każde jako lista słów) – ustawiane przy starcie nasłuchu. */
    @Volatile
    internal var active: List<List<String>> = parse(DEFAULT_PHRASES.joinToString(","))
        private set

    /** Uwaga do własnego hasła (np. nieznane słowo) – pokazywana w Ustawieniach. */
    val note = mutableStateOf("")

    /** „Hej Kokpit, okej auto” → [[hej, kokpit], [okej, auto]] */
    internal fun parse(text: String): List<List<String>> = text.lowercase().split(',', ';', '\n')
        .map { p -> p.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() } }
        .filter { it.isNotEmpty() }.distinct()

    /** Hasła jako tekst do pokazania: „ok bmw / hej bmw”. */
    fun activeText() = active.joinToString(" / ") { it.joinToString(" ") }

    /** Czy model mowy zna słowo (inaczej Vosk go nie rozpozna). */
    private interface VoskWords : com.sun.jna.Library {
        fun vosk_model_find_word(model: com.sun.jna.Pointer, word: String): Int
    }
    private val voskWords by lazy { com.sun.jna.Native.load("vosk", VoskWords::class.java) }

    /** Wybiera hasła: własne z Ustawień (jeśli model zna wszystkie słowa), inaczej domyślne. */
    internal fun resolve(m: Model) {
        val def = parse(DEFAULT_PHRASES.joinToString(","))
        val own = parse(Prefs.hotwordPhrase)
        if (own.isEmpty()) { active = def; note.value = ""; return }
        val unknown = runCatching {
            own.flatten().distinct().filter { voskWords.vosk_model_find_word(m.pointer, it) < 0 }
        }.getOrElse { Log.w(TAG, "słownik", it); emptyList() }
        val good = own.filter { p -> p.none { it in unknown } }
        if (good.isEmpty()) {
            active = def
            note.value = "Nie znam: " + unknown.joinToString(", ") { "„$it”" } + " – zostaje „ok bmw”. Pisz tak, jak się mówi, zwykłymi słowami"
        } else {
            active = good
            note.value = if (unknown.isEmpty()) "" else "Nie znam: " + unknown.joinToString(", ") { "„$it”" } + " – te hasła pominięte"
        }
        Log.i(TAG, "hasła: ${activeText()}" + if (unknown.isEmpty()) "" else ", nieznane słowa: $unknown")
    }

    /**
     * Słowa-wabiki: bez nich rozpoznawanie z samą gramatyką „OK BMW” dopasowuje do hasła prawie każdą mowę
     * (sprawdzone na nagraniach – zwykłe zdania dawały „ok bmw”). Z wabikami zwykła mowa trafia w nie.
     */
    private val DECOYS = listOf("to", "jest", "nie", "tak", "co", "no", "dobrze", "a", "i", "w", "na", "do", "po", "be", "em", "wu",
        // „okej, bez…”, „okej, bo…” – bez tych słów trafiały w „bmw”
        "bez", "bo", "bardzo", "ale", "tu", "ten", "ma")

    /**
     * Minimalna pewność „bmw” i słowa przed nim dla czułości 0 = niska, 1 = średnia, 2 = wysoka.
     * „bmw” jest charakterystyczne, więc musi być pewne; „ok/hej” przy muzyce bywa niewyraźne.
     */
    private val MIN_BMW = doubleArrayOf(0.95, 0.8, 0.6)
    private val MIN_LEAD = doubleArrayOf(0.8, 0.4, 0.3)

    /** Tryby mikrofonu (Prefs.micMode) – różne radia różnie przetwarzają dźwięk. */
    val MIC_MODES = listOf(
        "standardowy" to "Mikrofon do rozpoznawania mowy, z usuwaniem echa głośników",
        "bez usuwania echa" to "Surowy dźwięk – gdy usuwanie echa tłumi głos przy muzyce",
        "jak w rozmowie" to "Tor rozmów telefonicznych radia: redukcja szumu i echa",
        "zwykły mikrofon" to "Domyślne nagrywanie radia",
    )

    /** Stan nasłuchu do pokazania w Ustawieniach. */
    val status = mutableStateOf("wyłączone")
    /** Głośność z mikrofonu 0..1 (przed wzmocnieniem) i bieżące wzmocnienie. */
    val level = mutableFloatStateOf(0f)
    val gain = mutableFloatStateOf(1f)
    /** Ostatnio rozpoznane słowa (z pewnością) – do sprawdzenia, czy radio w ogóle słyszy. */
    val heard = mutableStateOf("")

    /** Zmiana trybu mikrofonu / wzmocnienia: nagrywanie zaczyna się od nowa. */
    fun restart() = HotwordService.instance?.restartListening()

    fun hasModel(ctx: Context) = runCatching { ctx.assets.open(MODEL_ASSET).close() }.isSuccess

    /** Włącza/wyłącza zgodnie z ustawieniem (wołane, gdy Kokpit jest na ekranie). */
    fun sync(ctx: Context) {
        val i = Intent(ctx, HotwordService::class.java)
        if (Prefs.hotword && hasModel(ctx) && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { ctx.startForegroundService(i) }.onFailure { Log.w(TAG, "start", it) }
        } else {
            ctx.stopService(i)
            status.value = "wyłączone"
        }
    }

    /** Okienko asystenta zajmuje mikrofon – wstrzymaj / wznów. */
    fun pause() = HotwordService.instance?.pauseListening()
    fun resume() = HotwordService.instance?.resumeListening()

    /** Rozpakowanie modelu do plików aplikacji (raz, ~90 MB). */
    internal fun modelDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "vosk-pl")
        val mark = File(dir, ".version")
        if (mark.exists() && mark.readText() == MODEL_VERSION) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(ctx.assets.open(MODEL_ASSET).buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // w ZIP-ie pliki są w katalogu vosk-model-small-pl-0.22/…
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty() || rel.contains("..")) continue
                val out = File(dir, rel)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        mark.writeText(MODEL_VERSION)
        return dir
    }

    /** Słowa z wyniku końcowego Vosk (z pewnością i czasem). */
    internal class Word(val word: String, val conf: Double, val start: Double, val end: Double)

    internal fun words(json: String): List<Word> {
        val a = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Word(it.optString("word"), it.optDouble("conf", 0.0), it.optDouble("start"), it.optDouble("end")) }
    }

    /**
     * Hasło: wszystkie jego słowa po kolei, dość pewne (ostatnie słowo – np. „bmw” – musi być pewniejsze niż
     * początek). [prev] = końcówka poprzedniego wyniku – przy wolnej mowie albo muzyce Vosk potrafi uciąć
     * wypowiedź między „okej” a „bmw”.
     */
    internal fun isWake(prev: List<Word>, words: List<Word>): Boolean {
        val s = Prefs.hotwordSens.coerceIn(0, 2)
        val seq = prev + words
        for (p in active) {
            val n = p.size
            for (end in n - 1 until seq.size) {
                var ok = true
                for (k in 0 until n) {
                    val w = seq[end - n + 1 + k]
                    if (w.word != p[k] || w.conf < (if (k == n - 1) MIN_BMW[s] else MIN_LEAD[s]) ||
                        (k > 0 && w.start - seq[end - n + k].end >= 1.0)) { ok = false; break }
                }
                if (ok) return true
            }
        }
        return false
    }

    /** ile słów poprzedniego wyniku trzeba pamiętać (najdłuższe hasło bez ostatniego słowa) */
    internal fun tailSize() = (active.maxOfOrNull { it.size } ?: 2) - 1

    internal fun grammar(): String {
        val used = active.flatten().toSet()
        return JSONArray(active.map { it.joinToString(" ") } + DECOYS.filter { it !in used } + "[unk]").toString()
    }
}

class HotwordService : Service() {
    private var model: Model? = null
    private var paused = false
    /** asystent mówi – wtedy nie słuchamy (sam mówi „BMW”) */
    private var muted = false
    private var quietSince = 0L
    private var lastWake = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var stopped = false
    /** wątek nagrywania; null = nie słucha */
    @Volatile
    private var listener: Thread? = null
    /** ostatni wątek – nowy czeka, aż stary odda mikrofon */
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Hotword.status.value = "wczytuje model mowy…"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hasło OK BMW", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kokpit słucha hasła głosowego")
            .setContentText("Wyłączysz w Ustawieniach Kokpitu → Asystent AI")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, n)
        // model ładuje się kilka sekund – w tle
        Thread {
            try {
                val m = Model(Hotword.modelDir(this).path)
                if (stopped) { m.close(); return@Thread }
                main.post {
                    if (stopped) { m.close(); return@post }
                    model = m
                    Hotword.status.value = "słucha"
                    watchSpeech()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "model", e)
                main.post { stopSelf() }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    /** Co 0,5 s: gdy asystent czyta odpowiedź – mikrofon wyłączony; po niej chwila przerwy. */
    private fun watchSpeech() {
        if (stopped) return
        val now = System.currentTimeMillis()
        if (Speech.isSpeaking()) {
            quietSince = 0L
            if (!muted) { muted = true; stopListening(); Hotword.status.value = "asystent mówi – przerwa"; Log.i(TAG, "asystent mówi – nie słucham") }
        } else {
            if (quietSince == 0L) quietSince = now
            if (muted && now - quietSince >= 700) { muted = false; Log.i(TAG, "słucham") }
        }
        if (!muted && !paused) startListening()
        main.postDelayed({ watchSpeech() }, 500)
    }

    private fun startListening() {
        val m = model ?: return
        if (listener != null || muted || paused || stopped) return
        val old = worker
        listener = Thread({ runCatching { old?.join(1500) }; listen(m) }, "KokpitHotword").also { worker = it; it.start() }
    }

    /** Własne nagrywanie (zamiast SpeechService), żeby włączyć systemowe usuwanie echa głośników, jeśli radio je ma. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun listen(m: Model) {
        val me = Thread.currentThread()
        var rec: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var recognizer: Recognizer? = null
        try {
            val rate = 16000
            val mode = Prefs.micMode.coerceIn(0, Hotword.MIC_MODES.size - 1)
            val source = when (mode) {
                2 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                3 -> MediaRecorder.AudioSource.MIC
                else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord")
            if (mode == 0 && AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            }
            val boost = Prefs.micGain
            Log.i(TAG, "mikrofon włączony (${Hotword.MIC_MODES[mode].first}), usuwanie echa: " + (if (aec?.enabled == true) "tak" else "brak") +
                ", wzmocnienie: " + if (boost) "auto" else "wył.")
            Hotword.resolve(m)
            recognizer = Recognizer(m, rate.toFloat(), Hotword.grammar()).apply { setWords(true) }
            val tail = Hotword.tailSize()
            val buf = ShortArray(rate / 5) // 0,2 s
            rec.startRecording()
            Hotword.status.value = "słucha"
            var prev = emptyList<Hotword.Word>()
            var prevAt = 0L
            // automatyczne wzmocnienie: cichy mikrofon radia (głos z daleka) Vosk rozpoznaje dużo gorzej
            var env = 0f
            var g = 1f
            var silent = 0
            var logAt = System.currentTimeMillis()
            var logPeak = 0f
            while (listener === me && !stopped) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("read $n")
                if (n == 0) continue
                var peakI = 0
                for (i in 0 until n) { val v = buf[i].toInt(); val a = if (v < 0) -v else v; if (a > peakI) peakI = a }
                val peak = peakI / 32768f
                // same zera przez 30 s = system najpewniej odciął nam mikrofon (nagrywa inna aplikacja) – zacznij
                // od nowa; prawdziwy mikrofon zawsze trochę szumi
                silent = if (peakI == 0) silent + 1 else 0
                if (silent >= 150) {
                    Log.i(TAG, "mikrofon milczy od 30 s – zaczynam nagrywanie od nowa")
                    main.post { if (listener === me) { listener = null; startListening() } }
                    break
                }
                if (boost) {
                    env = maxOf(peak, env * 0.9f)
                    val want = (0.5f / maxOf(env, 1e-4f)).coerceIn(1f, MAX_GAIN)
                    g = if (want < g) want else g + (want - g) * 0.3f
                    if (g > 1.01f) for (i in 0 until n) buf[i] = (buf[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                } else g = 1f
                Hotword.level.floatValue = peak
                Hotword.gain.floatValue = g
                logPeak = maxOf(logPeak, peak)
                val now = System.currentTimeMillis()
                if (now - logAt >= 60_000) {
                    Log.i(TAG, "poziom mikrofonu (maks. z minuty): ${(logPeak * 100).toInt()}%, wzmocnienie ×${"%.1f".format(g)}")
                    logAt = now; logPeak = 0f
                }
                if (!recognizer.acceptWaveForm(buf, n)) continue
                val words = Hotword.words(recognizer.result)
                if (words.isEmpty()) continue
                Hotword.heard.value = words.joinToString(" ") { it.word + " " + (it.conf * 100).toInt() + "%" }
                Log.d(TAG, "słyszę: ${Hotword.heard.value} (głośność ${(peak * 100).toInt()}%, ×${"%.1f".format(g)})")
                // ostatnie słowo poprzedniego wyniku liczy się tylko chwilę
                val p = if (System.currentTimeMillis() - prevAt < 2500) prev else emptyList()
                if (Hotword.isWake(p, words)) {
                    prev = emptyList()
                    main.post { wake() }
                } else {
                    prev = (p + words).takeLast(tail); prevAt = System.currentTimeMillis()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mic", e)
            if (listener === me) Hotword.status.value = "mikrofon niedostępny (${e.message}) – ponawiam"
            // mikrofon zajęty przez inną aplikację – spróbuj za chwilę (watchSpeech uruchomi ponownie)
            main.postDelayed({ if (listener === me) listener = null }, 5000)
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { aec?.release() }
            runCatching { recognizer?.close() }
        }
    }

    private fun stopListening() {
        listener = null // wątek kończy się po bieżącym fragmencie
    }

    fun restartListening() {
        stopListening()
        if (!muted && !paused) startListening()
    }

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        if (muted || paused || Speech.isSpeaking()) return
        lastWake = now
        Log.i(TAG, "OK BMW") // stały znacznik w logu (testy), niezależnie od wpisanego hasła
        pauseListening() // mikrofon dla okienka asystenta
        // okienko już otwarte: zaczyna nowe pytanie (onNewIntent → begin)
        Speech.stop(); VoiceActivity.start(this)
        // gdyby okienko się nie otworzyło (brak zgody na wyświetlanie nad innymi) – słuchaj dalej
        main.postDelayed({ if (!VoiceActivity.open) resumeListening() }, 4000)
    }

    fun pauseListening() {
        paused = true
        Hotword.status.value = "przerwa – asystent słucha pytania"
        stopListening()
    }

    fun resumeListening() {
        paused = false
        startListening()
    }

    override fun onDestroy() {
        stopped = true
        instance = null
        Hotword.status.value = "wyłączone"
        main.removeCallbacksAndMessages(null)
        val t = worker
        stopListening()
        // model zwalniamy dopiero, gdy wątek nagrywania skończy
        val m = model
        model = null
        Thread { runCatching { t?.join(2000) }; m?.close() }.start()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KokpitHotword"
        private const val CHANNEL = "hotword"
        private const val ID = 7301
        /** największe wzmocnienie cichego mikrofonu (×30 ≈ +30 dB) */
        private const val MAX_GAIN = 30f
        @Volatile
        var instance: HotwordService? = null
    }
}
