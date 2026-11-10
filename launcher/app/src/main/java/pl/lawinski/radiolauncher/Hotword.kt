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
 * Otwieranie okienka z tła wymaga zgody „Wyświetlanie nad innymi aplikacjami”.
 */
object Hotword {
    private const val TAG = "KokpitHotword"
    private const val MODEL_ASSET = "vosk-pl.zip"
    private const val MODEL_VERSION = "small-pl-0.22"

    /** frazy, które otwierają asystenta */
    private val PHRASES = listOf("ok bmw", "okej bmw", "hej bmw")
    private val LEAD = setOf("ok", "okej", "hej")

    /**
     * Słowa-wabiki: bez nich rozpoznawanie z samą gramatyką „OK BMW” dopasowuje do hasła prawie każdą mowę
     * (sprawdzone na nagraniach – zwykłe zdania dawały „ok bmw”). Z wabikami zwykła mowa trafia w nie.
     */
    private val DECOYS = listOf("to", "jest", "nie", "tak", "co", "no", "dobrze", "a", "i", "w", "na", "do", "się", "że", "po", "be", "em", "wu")

    /**
     * Minimalna pewność „bmw” i słowa przed nim dla czułości 0 = niska, 1 = średnia, 2 = wysoka.
     * „bmw” jest charakterystyczne, więc musi być pewne; „ok/hej” przy muzyce bywa niewyraźne.
     */
    private val MIN_BMW = doubleArrayOf(0.95, 0.8, 0.6)
    private val MIN_LEAD = doubleArrayOf(0.8, 0.4, 0.3)

    fun hasModel(ctx: Context) = runCatching { ctx.assets.open(MODEL_ASSET).close() }.isSuccess

    /** Włącza/wyłącza zgodnie z ustawieniem (wołane, gdy Kokpit jest na ekranie). */
    fun sync(ctx: Context) {
        val i = Intent(ctx, HotwordService::class.java)
        if (Prefs.hotword && hasModel(ctx) && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { ctx.startForegroundService(i) }.onFailure { Log.w(TAG, "start", it) }
        } else ctx.stopService(i)
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
     * Hasło: „ok/okej/hej” zaraz przed „bmw”, oba dość pewne. [prev] = ostatnie słowo poprzedniego wyniku –
     * przy wolnej mowie albo muzyce Vosk potrafi uciąć wypowiedź między „okej” a „bmw”.
     */
    internal fun isWake(prev: Word?, words: List<Word>): Boolean {
        val s = Prefs.hotwordSens.coerceIn(0, 2)
        val seq = listOfNotNull(prev) + words
        for (i in 1 until seq.size) {
            val a = seq[i - 1]
            val b = seq[i]
            if (a.word in LEAD && b.word == "bmw" && b.conf >= MIN_BMW[s] && a.conf >= MIN_LEAD[s] && b.start - a.end < 1.0) return true
        }
        return false
    }

    internal fun grammar() = JSONArray(PHRASES + DECOYS + "[unk]").toString()
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hasło OK BMW", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kokpit słucha hasła „OK BMW”")
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
            if (!muted) { muted = true; stopListening(); Log.i(TAG, "asystent mówi – nie słucham") }
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
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord")
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            }
            Log.i(TAG, "mikrofon włączony, usuwanie echa: " + (if (aec?.enabled == true) "tak" else "brak"))
            recognizer = Recognizer(m, rate.toFloat(), Hotword.grammar()).apply { setWords(true) }
            val buf = ShortArray(rate / 5) // 0,2 s
            rec.startRecording()
            var prev: Hotword.Word? = null
            var prevAt = 0L
            while (listener === me && !stopped) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("read $n")
                if (n == 0 || !recognizer.acceptWaveForm(buf, n)) continue
                val words = Hotword.words(recognizer.result)
                if (words.isEmpty()) continue
                // ostatnie słowo poprzedniego wyniku liczy się tylko chwilę
                val p = prev.takeIf { System.currentTimeMillis() - prevAt < 2500 }
                if (Hotword.isWake(p, words)) {
                    prev = null
                    main.post { wake() }
                } else {
                    prev = words.last(); prevAt = System.currentTimeMillis()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "mic", e)
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

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        if (muted || paused || Speech.isSpeaking()) return
        lastWake = now
        Log.i(TAG, "OK BMW")
        pauseListening() // mikrofon dla okienka asystenta
        // okienko już otwarte: zaczyna nowe pytanie (onNewIntent → begin)
        Speech.stop(); VoiceActivity.start(this)
        // gdyby okienko się nie otworzyło (brak zgody na wyświetlanie nad innymi) – słuchaj dalej
        main.postDelayed({ if (!VoiceActivity.open) resumeListening() }, 4000)
    }

    fun pauseListening() {
        paused = true
        stopListening()
    }

    fun resumeListening() {
        paused = false
        startListening()
    }

    override fun onDestroy() {
        stopped = true
        instance = null
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
        @Volatile
        var instance: HotwordService? = null
    }
}
