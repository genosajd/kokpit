package pl.lawinski.radiolauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Otwieranie okienka z tła wymaga zgody „Wyświetlanie nad innymi aplikacjami”.
 */
object Hotword {
    private const val TAG = "KokpitHotword"
    private const val MODEL_ASSET = "vosk-pl.zip"
    private const val MODEL_VERSION = "small-pl-0.22"

    /** frazy, które otwierają asystenta; [unk] pochłania całą resztę mowy */
    private val PHRASES = listOf("ok bmw", "okej bmw", "hej bmw")

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

    internal fun isWake(json: String, key: String): Boolean {
        val t = runCatching { JSONObject(json).optString(key) }.getOrDefault("")
        return PHRASES.any { t.contains(it) }
    }

    internal fun grammar() = JSONArray(PHRASES + "[unk]").toString()
}

class HotwordService : Service() {
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
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
                    if (!paused) startListening()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "model", e)
                main.post { stopSelf() }
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private fun startListening() {
        val m = model ?: return
        if (speech != null) return
        try {
            val rec = Recognizer(m, 16000f, Hotword.grammar())
            speech = SpeechService(rec, 16000f).also {
                it.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String) {
                        if (Hotword.isWake(hypothesis, "partial")) wake()
                    }
                    override fun onResult(hypothesis: String) {
                        if (Hotword.isWake(hypothesis, "text")) wake()
                    }
                    override fun onFinalResult(hypothesis: String) {}
                    override fun onError(exception: Exception) {
                        Log.w(TAG, "mic", exception)
                        // mikrofon zajęty przez inną aplikację – spróbuj za chwilę
                        stopListening()
                        main.postDelayed({ if (!paused) startListening() }, 5000)
                    }
                    override fun onTimeout() {}
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            speech = null
            main.postDelayed({ if (!paused && !stopped) startListening() }, 5000)
        }
    }

    private fun stopListening() {
        speech?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        speech = null
    }

    private fun wake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        // asystent czyta odpowiedź ze słowem „BMW” – głośniki mogłyby same wywołać hasło
        if (Speech.isSpeaking() && Speech.lastText.contains("bmw", true)) return
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
