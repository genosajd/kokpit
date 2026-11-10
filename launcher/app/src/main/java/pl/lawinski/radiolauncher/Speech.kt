package pl.lawinski.radiolauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/*
 * Czytanie odpowiedzi asystenta na głos.
 *  - SYSTEM: silnik mowy Androida (Google); głos polski do wyboru, „sieciowe” brzmią najnaturalniej.
 *  - OPENAI: głos z OpenAI (/v1/audio/speech) – najbardziej ludzki, płatny, potrzebuje klucza OpenAI.
 *    Gdy się nie uda (brak sieci, zły klucz), odpowiedź czyta głos systemowy.
 * Na czas czytania muzyka jest ściszana (fokus audio „transient may duck” – system ścisza inne odtwarzacze).
 */
object Speech {
    const val SYSTEM = "system"
    const val OPENAI = "openai"
    const val DEFAULT_OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
    const val DEFAULT_OPENAI_VOICE = "coral"

    /** Głosy OpenAI do wyboru (nazwy z API). */
    val OPENAI_VOICES = listOf("coral", "sage", "nova", "shimmer", "alloy", "ash", "ballad", "echo", "fable", "onyx", "verse")

    private const val PL_SAMPLE = "Cześć, jestem Twoim asystentem w aucie. Tak będą brzmiały moje odpowiedzi."

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<() -> Unit>()
    private var player: MediaPlayer? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null

    /** Ścisza muzykę na czas mówienia. */
    private fun duck(ctx: Context) {
        if (focus != null) return
        val am = audio ?: ctx.applicationContext.getSystemService(AudioManager::class.java).also { audio = it }
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { am.requestAudioFocus(r) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = r
    }

    /** Muzyka wraca do zwykłej głośności. */
    private fun unduck() {
        val r = focus ?: return
        focus = null
        runCatching { audio?.abandonAudioFocusRequest(r) }
    }

    /** Po zakończeniu czytania (wołane z wątku silnika mowy). */
    private val ttsListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) = finished(utteranceId)
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    /** id bieżącej wypowiedzi – koniec starej (przerwanej nową) nie przywraca głośności */
    @Volatile
    private var utterance = ""
    private var counter = 0

    private fun finished(id: String?) {
        main.post { if (id == utterance && player == null) unduck() }
    }

    private fun nextId(): String { counter++; utterance = "ai$counter"; return utterance }

    /** Silnik systemowy (tworzony przy pierwszym użyciu); [then] po inicjalizacji. */
    private fun system(ctx: Context, then: (TextToSpeech) -> Unit) {
        val t = tts
        if (t != null && ready) return then(t)
        pending += { tts?.let(then) }
        if (t != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoice()
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(ttsListener)
            }
            val todo = pending.toList()
            pending.clear()
            if (ready) todo.forEach { it() }
        }
    }

    private fun applyVoice() {
        val t = tts ?: return
        val wanted = Prefs.ttsVoice
        val all = runCatching { t.voices }.getOrNull().orEmpty()
        // bez wyboru: pierwszy polski głos sieciowy (brzmi najnaturalniej), potem dowolny polski
        val v = all.firstOrNull { it.name == wanted }
            ?: all.filter { it.locale.language == "pl" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy({ !it.isNetworkConnectionRequired }, { it.name })).firstOrNull()
        if (v != null) t.voice = v else t.language = Locale("pl", "PL")
    }

    /** Polskie głosy systemowe: najpierw sieciowe (lepsze), potem lokalne. */
    fun polishVoices(ctx: Context, onResult: (List<Voice>) -> Unit) = system(ctx) { t ->
        val list = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "pl" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy({ !it.isNetworkConnectionRequired }, { it.name }))
        onResult(list)
    }

    fun isSpeaking() = tts?.isSpeaking == true || runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun speak(ctx: Context, text: String) {
        val clean = text.replace(Regex("[*_#`>|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        stop(keepDuck = true)
        if (Prefs.ttsEngine == OPENAI && Prefs.openaiKey.isNotBlank()) speakOpenAi(ctx, clean, Prefs.openaiVoice)
        else speakSystem(ctx, clean)
    }

    private fun speakSystem(ctx: Context, text: String) = system(ctx) {
        applyVoice() // po odsłuchu w Ustawieniach wraca zapisany głos
        duck(ctx)
        if (it.speak(text, TextToSpeech.QUEUE_FLUSH, null, nextId()) != TextToSpeech.SUCCESS) unduck()
    }

    /** Odsłuch głosu systemowego w Ustawieniach (bez zapisywania wyboru). */
    fun previewSystem(ctx: Context, voice: Voice) = system(ctx) { t ->
        stop()
        t.voice = voice
        duck(ctx)
        if (t.speak(PL_SAMPLE, TextToSpeech.QUEUE_FLUSH, null, nextId()) != TextToSpeech.SUCCESS) unduck()
    }

    fun previewOpenAi(ctx: Context, voice: String) {
        stop()
        speakOpenAi(ctx, PL_SAMPLE, voice)
    }

    fun setSystemVoice(name: String) {
        Prefs.ttsVoice = name
        applyVoice()
    }

    private fun speakOpenAi(ctx: Context, text: String, voice: String) {
        job = scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { fetchOpenAi(ctx, text, voice) }
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Głos OpenAI nie działa (${e.message}) – czytam głosem systemowym", android.widget.Toast.LENGTH_LONG).show()
                speakSystem(ctx, text)
                return@launch
            }
            duck(ctx)
            player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(file.path)
                    setOnCompletionListener { it.release(); if (player === it) { player = null; unduck() } }
                    prepare()
                    start()
                }
            }.onFailure { unduck() }.getOrNull()
        }
    }

    private fun fetchOpenAi(ctx: Context, text: String, voice: String): File {
        val c = URL("https://api.openai.com/v1/audio/speech").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${Prefs.openaiKey}")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", Prefs.openaiTtsModel)
            .put("voice", voice)
            .put("input", text)
            .put("response_format", "mp3")
            .put("instructions", "Mów po polsku, naturalnie i ciepło, w spokojnym tempie, jak życzliwy pasażer w aucie.")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw Exception(runCatching { JSONObject(err).getJSONObject("error").optString("message") }.getOrNull()?.take(80) ?: "HTTP ${c.responseCode}")
        }
        val f = File(ctx.cacheDir, "tts.mp3")
        c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return f
    }

    fun stop() = stop(keepDuck = false)

    /** [keepDuck] – zaraz zacznie się nowa wypowiedź, muzyka zostaje ściszona */
    private fun stop(keepDuck: Boolean) {
        job?.cancel()
        tts?.stop()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        utterance = ""
        if (!keepDuck) unduck()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
