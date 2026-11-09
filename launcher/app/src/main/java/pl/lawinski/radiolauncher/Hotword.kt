package pl.lawinski.radiolauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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
 * Gdy radio gra (muzyka, film, odpowiedź asystenta), mikrofon jest wyłączony. Czułość: Prefs.hotwordSens.
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

    /** Minimalna pewność słów hasła dla czułości 0 = niska, 1 = średnia, 2 = wysoka. */
    private val MIN_CONF = doubleArrayOf(0.99, 0.9, 0.7)

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

    /** Hasło w wyniku końcowym (z pewnością słów): „ok/okej/hej” zaraz przed „bmw”, oba dość pewne. */
    internal fun isWake(json: String): Boolean {
        val words = runCatching { JSONObject(json).optJSONArray("result") }.getOrNull() ?: return false
        val min = MIN_CONF[Prefs.hotwordSens.coerceIn(0, 2)]
        for (i in 1 until words.length()) {
            val a = words.getJSONObject(i - 1)
            val b = words.getJSONObject(i)
            if (a.optString("word") in LEAD && b.optString("word") == "bmw" &&
                a.optDouble("conf", 0.0) >= min && b.optDouble("conf", 0.0) >= min) return true
        }
        return false
    }

    internal fun grammar() = JSONArray(PHRASES + DECOYS + "[unk]").toString()
}

class HotwordService : Service() {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
    /** radio gra albo asystent mówi – wtedy nie słuchamy (głośniki mylą rozpoznawanie) */
    private var muted = false
    private var quietSince = 0L
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var lastWake = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile
    private var stopped = false

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
                    watchAudio()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "model", e)
                main.post { stopSelf() }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    /** Co sekundę: gdy radio gra (muzyka, film, odpowiedź asystenta) – mikrofon wyłączony. */
    private fun watchAudio() {
        if (stopped) return
        val playing = runCatching { audio.isMusicActive }.getOrDefault(false) || Speech.isSpeaking()
        val now = System.currentTimeMillis()
        if (playing) {
            quietSince = 0L
            if (!muted) { muted = true; stopListening(); Log.i(TAG, "radio gra – nie słucham") }
        } else {
            if (quietSince == 0L) quietSince = now
            // po ucichnięciu chwila przerwy (koniec zdania, przerwa między utworami)
            if (muted && now - quietSince >= 1500) { muted = false; Log.i(TAG, "cisza – słucham") }
        }
        if (!muted && !paused) startListening()
        main.postDelayed({ watchAudio() }, 1000)
    }

    private fun startListening() {
        val m = model ?: return
        if (speech != null || muted || paused || stopped) return
        try {
            val rec = Recognizer(m, 16000f, Hotword.grammar())
            rec.setWords(true)
            speech = SpeechService(rec, 16000f).also {
                it.startListening(object : RecognitionListener {
                    // wyniki częściowe są zbyt zawodne – tylko końcowe, z pewnością słów
                    override fun onPartialResult(hypothesis: String) {}
                    override fun onResult(hypothesis: String) {
                        if (Hotword.isWake(hypothesis)) wake()
                    }
                    override fun onFinalResult(hypothesis: String) {}
                    override fun onError(exception: Exception) {
                        Log.w(TAG, "mic", exception)
                        // mikrofon zajęty przez inną aplikację – spróbuj za chwilę
                        stopListening()
                        main.postDelayed({ startListening() }, 5000)
                    }
                    override fun onTimeout() {}
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            speech = null
            main.postDelayed({ startListening() }, 5000)
        }
    }

    private fun stopListening() {
        speech?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speech = null
    }

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        if (muted || Speech.isSpeaking()) return
        lastWake = now
        Log.i(TAG, "OK BMW")
        pauseListening() // mikrofon dla okienka asystenta
        // okienko już otwarte: zaczyna nowe pytanie (onNewIntent → begin)
        main.post { Speech.stop(); VoiceActivity.start(this) }
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
        stopListening()
        model?.close()
        model = null
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
