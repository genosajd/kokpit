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
 *  - ELEVEN: głos z ElevenLabs (/v1/text-to-speech/{głos}) – własny klucz, głosy z konta właściciela (także
 *    sklonowane), płatny według liczby znaków. Błąd = ta odpowiedź głosem systemowym; wyczerpany limit znaków =
 *    ElevenLabs pomijany przez kilka godzin (bez czekania na odmowę przy każdej odpowiedzi), potem wraca sam.
 * Na czas czytania muzyka jest ściszana (fokus audio „transient may duck” – system ścisza inne odtwarzacze).
 */
object Speech {
    const val SYSTEM = "system"
    const val OPENAI = "openai"
    const val ELEVEN = "eleven"
    const val DEFAULT_OPENAI_TTS_MODEL = "gpt-4o-mini-tts"

    /** Modele ElevenLabs: id z API i nazwa w Ustawieniach (pierwszy = domyślny). */
    val ELEVEN_MODELS = listOf(
        "eleven_flash_v2_5" to "szybki",
        "eleven_multilingual_v2" to "dokładny",
        "eleven_v3" to "ekspresyjny",
    )

    /** Modele ElevenLabs, które grają znaczniki emocji w nawiasach kwadratowych; pozostałe czytałyby je na głos. */
    private val TAG_MODELS = setOf("eleven_v3")

    private val TAG = Regex("\\[[^\\[\\]\\n]{1,40}]")

    /** Czy odpowiedź przeczyta teraz głos, który rozumie znaczniki emocji, np. [sighs] (tylko ElevenLabs „ekspresyjny”). */
    fun tagsOn() = Prefs.ttsEngine == ELEVEN && Prefs.elevenKey.isNotBlank() && !elevenPaused() && Prefs.elevenModel in TAG_MODELS

    /** Tekst bez znaczników emocji – do pokazania na ekranie i dla głosów, które ich nie rozumieją. */
    fun plain(text: String) = if ('[' !in text) text else
        text.replace(TAG, "").replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" ([.,!?;:])"), "$1").trim()

    /** Dopisek do promptu asystenta, gdy głos gra znaczniki emocji. */
    fun tagNote() = if (!tagsOn()) "" else "\n\nTwoje odpowiedzi czyta głos ElevenLabs v3, który odgrywa znaczniki w nawiasach kwadratowych. " +
        "Wstawiaj je w tekst tam, gdzie pasują do treści i nastroju – emocje i sposób mówienia, np. [ze złością], [z ekscytacją], [ze znudzeniem], [groźnie], [figlarnie], [słabym głosem], [zmęczonym głosem], [szeptem], " +
        "oraz odgłosy, np. [śmieje się], [śmieje się pod nosem], [wzdycha], [dyszy], [łapie powietrze], [ostry wydech], [prycha], [odchrząkuje], [kaszle], [ziewa], [pociąga nosem], [nuci]. " +
        "Znaczniki pisz po polsku, małymi literami, przed fragmentem, którego dotyczą; możesz użyć też innych w tym stylu. Zwykle jeden lub dwa na odpowiedź, w rzeczowych odpowiedziach mogą nie być potrzebne. " +
        "Słuchacz ich nie widzi ani nie słyszy jako słów, więc nie zastępują treści. Nawiasów kwadratowych nie używaj do niczego innego."

    /** Po wyczerpaniu limitu znaków ElevenLabs: tyle czasu czyta głos systemowy, potem kolejna próba. */
    private const val ELEVEN_PAUSE_MS = 6 * 60 * 60 * 1000L

    class ElevenVoice(val id: String, val name: String, val info: String)

    /** Limit znaków na koncie ElevenLabs wyczerpany. */
    private class QuotaError(message: String) : Exception(message)

    /** Czy ElevenLabs jest teraz pomijany po wyczerpaniu limitu. */
    fun elevenPaused() = System.currentTimeMillis() < Prefs.elevenPausedUntil
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
        // znaczniki emocji zostają tylko dla głosu, który je odgrywa – inne głosy czytałyby je jako słowa
        val clean = (if (tagsOn()) text else plain(text)).replace(Regex("[*_#`>|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        // do diagnozy: który głos i jakie znaczniki poszły (bez treści odpowiedzi)
        android.util.Log.i("KokpitSpeech", "głos=${Prefs.ttsEngine} znaczniki=${if (tagsOn()) TAG.findAll(clean).joinToString(" ") { it.value }.ifEmpty { "brak" } else "wyłączone"}")
        stop(keepDuck = true)
        when {
            Prefs.ttsEngine == OPENAI && Prefs.openaiKey.isNotBlank() -> speakOpenAi(ctx, clean, Prefs.openaiVoice)
            Prefs.ttsEngine == ELEVEN && Prefs.elevenKey.isNotBlank() && !elevenPaused() -> speakEleven(ctx, clean, Prefs.elevenVoice)
            else -> speakSystem(ctx, clean)
        }
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

    fun previewEleven(ctx: Context, voiceId: String) {
        stop()
        speakEleven(ctx, PL_SAMPLE, voiceId)
    }

    private fun speakOpenAi(ctx: Context, text: String, voice: String) =
        speakCloud(ctx, text, "OpenAI", "openai|$voice|${Prefs.openaiTtsModel}|$text") { fetchOpenAi(ctx, text, voice) }

    private fun speakEleven(ctx: Context, text: String, voiceId: String) =
        speakCloud(ctx, text, "ElevenLabs", "eleven|$voiceId|${Prefs.elevenModel}|$text") { fetchEleven(ctx, text, voiceId) }

    /** co jest w pliku z ostatnio pobraną mową (dostawca|głos|model|tekst) – to samo drugi raz gra z pliku, bez zapytania */
    private var cachedKey = ""

    /**
     * Głos z chmury: [fetch] pobiera plik z mową (w tle); gdy się nie uda – ten tekst czyta głos systemowy.
     * Ten sam tekst tym samym głosem po raz drugi (przycisk „powtórz”) jest odtwarzany z pliku.
     */
    private fun speakCloud(ctx: Context, text: String, name: String, key: String, fetch: () -> File) {
        job = scope.launch {
            val file = try {
                val ready = File(ctx.cacheDir, "tts.mp3")
                if (key == cachedKey && ready.length() > 0) ready
                else withContext(Dispatchers.IO) { cachedKey = ""; fetch() }.also { cachedKey = key }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: QuotaError) {
                Prefs.elevenPausedUntil = System.currentTimeMillis() + ELEVEN_PAUSE_MS
                android.widget.Toast.makeText(ctx, "Limit ElevenLabs wyczerpany – przez kilka godzin czytam głosem systemowym", android.widget.Toast.LENGTH_LONG).show()
                speakSystem(ctx, plain(text))
                return@launch
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Głos $name nie działa (${e.message}) – czytam głosem systemowym", android.widget.Toast.LENGTH_LONG).show()
                speakSystem(ctx, plain(text))
                return@launch
            }
            android.util.Log.i("KokpitSpeech", "$name: plik z mową gotowy (${file.length() / 1024} kB)")
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

    // ---------- ElevenLabs ----------

    private fun elevenOpen(path: String, post: Boolean): HttpURLConnection {
        val c = URL("https://api.elevenlabs.io$path").openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("xi-api-key", Prefs.elevenKey)
        if (post) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
        }
        return c
    }

    /** Odpowiedź z błędem: {"detail": {"status": …, "message": …}} albo {"detail": "tekst"}. */
    private fun elevenFail(c: HttpURLConnection): Nothing {
        val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(err).opt("detail") }.getOrNull()
        val status = (detail as? JSONObject)?.optString("status").orEmpty()
        val msg = ((detail as? JSONObject)?.optString("message") ?: detail as? String).orEmpty().ifBlank { "HTTP ${c.responseCode}" }.take(90)
        if (status == "quota_exceeded" || "quota" in msg.lowercase()) throw QuotaError(msg)
        // darmowe konto: głosy dodane z biblioteki głosów nie działają przez API
        if ("library voices" in msg.lowercase()) throw Exception("ten głos jest z biblioteki głosów – w darmowym planie wybierz głos bez dopisku „z biblioteki”")
        throw Exception(msg)
    }

    /** Głosy z konta ElevenLabs właściciela (gotowe, własne, sklonowane). Wołać poza wątkiem głównym. */
    fun elevenVoices(): List<ElevenVoice> {
        val c = elevenOpen("/v1/voices", post = false)
        if (c.responseCode !in 200..299) elevenFail(c)
        val a = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("voices") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val v = a.getJSONObject(i)
            val labels = v.optJSONObject("labels")
            val info = listOfNotNull(
                when (v.optString("category")) { "cloned" -> "sklonowany"; "generated" -> "własny"; "professional" -> "z biblioteki (płatny plan)"; else -> null },
                labels?.optString("gender")?.takeIf { it.isNotBlank() },
                labels?.optString("age")?.takeIf { it.isNotBlank() },
                labels?.optString("accent")?.takeIf { it.isNotBlank() },
                labels?.optString("description")?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            ElevenVoice(v.getString("voice_id"), v.optString("name").ifBlank { "głos ${i + 1}" }, info)
        }
    }

    /** Zużycie na koncie ElevenLabs w bieżącym okresie: wykorzystane znaki, limit, czas odnowienia (ms; 0 = nieznany). */
    class ElevenUsage(val used: Long, val limit: Long, val resetAt: Long)

    /** Wołać poza wątkiem głównym. Klucz musi mieć dostęp do danych konta. */
    fun elevenUsage(): ElevenUsage {
        val c = elevenOpen("/v1/user/subscription", post = false)
        if (c.responseCode !in 200..299) elevenFail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return ElevenUsage(o.optLong("character_count"), o.optLong("character_limit"), o.optLong("next_character_count_reset_unix") * 1000)
    }

    private fun fetchEleven(ctx: Context, text: String, voiceId: String): File {
        // bez wybranego głosu: pierwszy z konta (i zapamiętany)
        val voice = voiceId.ifBlank {
            val first = elevenVoices().firstOrNull() ?: throw Exception("brak głosów na koncie")
            Prefs.elevenVoice = first.id; Prefs.elevenVoiceName = first.name
            first.id
        }
        val model = Prefs.elevenModel
        val c = elevenOpen("/v1/text-to-speech/" + java.net.URLEncoder.encode(voice, "UTF-8") + "?output_format=mp3_44100_128", post = true)
        val body = JSONObject().put("text", text).put("model_id", model)
        // wskazanie języka przyjmują tylko modele 2.5 – bez niego krótkie polskie zdania bywają czytane z obcym akcentem
        if (model.endsWith("_v2_5") || model in TAG_MODELS) body.put("language_code", "pl")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) elevenFail(c)
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
