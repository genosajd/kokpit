package pl.lawinski.radiolauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
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

    /** Silnik systemowy (tworzony przy pierwszym użyciu); [then] po inicjalizacji. */
    private fun system(ctx: Context, then: (TextToSpeech) -> Unit) {
        val t = tts
        if (t != null && ready) return then(t)
        pending += { tts?.let(then) }
        if (t != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyVoice()
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

    /** ostatnio czytany tekst (Hotword ignoruje „BMW” z własnych głośników) */
    var lastText = ""
        private set

    fun isSpeaking() = tts?.isSpeaking == true || runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun speak(ctx: Context, text: String) {
        lastText = text
        val clean = text.replace(Regex("[*_#`>|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        stop()
        if (Prefs.ttsEngine == OPENAI && Prefs.openaiKey.isNotBlank()) speakOpenAi(ctx, clean, Prefs.openaiVoice)
        else speakSystem(ctx, clean)
    }

    private fun speakSystem(ctx: Context, text: String) = system(ctx) {
        applyVoice() // po odsłuchu w Ustawieniach wraca zapisany głos
        it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai")
    }

    /** Odsłuch głosu systemowego w Ustawieniach (bez zapisywania wyboru). */
    fun previewSystem(ctx: Context, voice: Voice) = system(ctx) { t ->
        stop()
        t.voice = voice
        t.speak(PL_SAMPLE, TextToSpeech.QUEUE_FLUSH, null, "preview")
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
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.path)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
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

    fun stop() {
        job?.cancel()
        tts?.stop()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
