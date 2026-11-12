package pl.lawinski.radiolauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Okienko asystenta głosowego nad dowolną aplikacją (jak Asystent Google):
 * wywołane przez ASSIST / VOICE_COMMAND / VOICE_ASSIST (Kokpit jako domyślny asystent) albo przycisk
 * głosowy. Od razu słucha, pokazuje na żywo, co słyszy, potem odpowiedź asystenta (czytaną na głos).
 * Rozmowa jest ta sama co w bloku „Asystent”. Dotknięcie poza okienkiem zamyka je.
 */
class VoiceActivity : ComponentActivity() {
    private enum class Phase { LISTENING, THINKING, ANSWER, ERROR }

    private val phase = mutableStateOf(Phase.LISTENING)
    private val heard = mutableStateOf("")
    private val answer = mutableStateOf("")
    private val level = mutableStateOf(0f)
    /** licznik dotknięć/zdarzeń – przesuwa automatyczne zamknięcie */
    private val activity = mutableStateOf(0)
    private var recognizer: SpeechRecognizer? = null
    private var waitingLines = -1

    // Dalszy ciąg zdania: rozpoznawanie Google kończy przy krótkiej pauzie, więc po wyniku słuchamy jeszcze chwilę –
    // gdy ktoś mówi dalej, kolejne słowa są doklejane; cisza = pytanie idzie do asystenta.
    /** to, co już rozpoznane w tym pytaniu */
    private var collected = ""
    /** słuchamy dalszego ciągu (po pierwszym wyniku) */
    private var more = false
    /** „skończ mówić” dotknięte – bez czekania na dalszy ciąg */
    private var manualStop = false
    private val waitHandler by lazy { android.os.Handler(mainLooper) }
    private var waitRun: Runnable? = null

    private fun pauseMs() = when (Prefs.voicePause) { 0 -> 0L; 2 -> 2600L; else -> 1600L }

    private fun cancelWait() {
        waitRun?.let { waitHandler.removeCallbacks(it) }
        waitRun = null
    }

    /** Za [ms] bez dalszej mowy pytanie zostaje wysłane. */
    private fun waitMore(ms: Long) {
        cancelWait()
        waitRun = Runnable { recognizer?.cancel(); finishQuestion() }.also { waitHandler.postDelayed(it, ms) }
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private fun finishQuestion() {
        cancelWait()
        more = false
        val t = collected.trim()
        collected = ""
        if (t.isNotBlank()) ask(t) else showError("Nic nie usłyszałem. Dotknij mikrofonu i powiedz jeszcze raz.")
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else fallbackDialog()
    }

    /** Okienko mowy Google – gdy w radiu nie ma usługi rozpoznawania albo zgody na mikrofon. */
    private val speechDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        Hotword.resume()
        if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) ask(text) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent { VoiceOverlay() }
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ponowne naciśnięcie przycisku: słuchamy od nowa
        begin()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        open = false
        Hotword.resume()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        open = true
    }

    override fun onStop() {
        super.onStop()
        open = false
        cancelWait()
        recognizer?.cancel()
        // okienko przykryła inna aplikacja (np. nawigacja włączona przez asystenta) – nie wracamy z nim
        if (!isChangingConfigurations && MainActivity.live != null) finish()
    }

    private fun begin() {
        cancelWait()
        Speech.stop()
        heard.value = ""
        answer.value = ""
        activity.value++
        if (!Assistant.hasKey()) return showError("Wpisz klucz API ${Assistant.providerName()} w Ustawieniach Kokpitu → Asystent AI.")
        if (MainActivity.live == null) return wakeKokpit()
        if (Assistant.busy.value) {
            phase.value = Phase.THINKING
            heard.value = Assistant.lines.lastOrNull { it.fromUser }?.text.orEmpty()
            waitingLines = Assistant.lines.size
            Hotword.resume()
            return
        }
        when {
            !SpeechRecognizer.isRecognitionAvailable(this) -> fallbackDialog()
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> startListening()
        }
    }

    /** Ekran Kokpitu jeszcze nie działa (np. po aktualizacji): uruchamiamy go i wracamy z okienkiem. */
    private fun wakeKokpit() {
        phase.value = Phase.THINKING
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val h = android.os.Handler(mainLooper)
        var tries = 0
        h.post(object : Runnable {
            override fun run() {
                when {
                    MainActivity.live != null -> start(this@VoiceActivity)
                    ++tries < 30 -> h.postDelayed(this, 200)
                    else -> showError("Kokpit nie działa – naciśnij Home i spróbuj jeszcze raz.")
                }
            }
        })
    }

    private fun fallbackDialog() {
        Hotword.pause()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Powiedz, o co chcesz zapytać")
        try {
            speechDialog.launch(i)
        } catch (_: Exception) {
            showError("Radio nie ma rozpoznawania mowy (Google).")
        }
    }

    private fun startListening(continued: Boolean = false) {
        // mikrofon tylko dla pytania; „OK BMW” wraca, gdy skończymy słuchać (ask/showError)
        Hotword.pause()
        phase.value = Phase.LISTENING
        more = continued
        if (!continued) {
            collected = ""
            manualStop = false
            heard.value = ""
        }
        level.value = 0f
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                // ktoś (albo hałas) zaczął: jeszcze chwila, żeby zdążyły przyjść pierwsze słowa
                if (more && waitRun != null) waitMore(pauseMs() + 1500)
            }
            override fun onRmsChanged(rmsdB: Float) {
                level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                level.value = 0f
            }
            override fun onError(error: Int) {
                // spóźnione zdarzenie po przerwaniu słuchania (pytanie już poszło albo okienko pokazuje co innego)
                if (phase.value != Phase.LISTENING) return
                // w czasie czekania na dalszy ciąg każdy błąd (cisza, zajęty mikrofon) = koniec pytania z tym, co już jest
                if (more && collected.isNotBlank()) return finishQuestion()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (heard.value.isNotBlank()) ask(heard.value) else showError("Nic nie usłyszałem. Dotknij mikrofonu i powiedz jeszcze raz.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fallbackDialog()
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        showError("Rozpoznawanie mowy potrzebuje internetu.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        recognizer?.destroy()
                        recognizer = null
                        showError("Mikrofon jest zajęty. Dotknij mikrofonu, żeby spróbować jeszcze raz.")
                    }
                    else -> showError("Nie udało się rozpoznać mowy (błąd $error).")
                }
            }
            override fun onResults(results: Bundle?) {
                if (phase.value != Phase.LISTENING) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                cancelWait()
                collected = join(collected, t)
                heard.value = collected
                val pause = pauseMs()
                when {
                    collected.isBlank() -> finishQuestion()
                    manualStop || pause == 0L -> finishQuestion()
                    // słuchamy jeszcze chwilę, czy zdanie ma dalszy ciąg (nowy start chwilę po wyniku – inaczej „mikrofon zajęty”)
                    else -> waitHandler.postDelayed({
                        if (phase.value == Phase.LISTENING && !isFinishing) {
                            startListening(continued = true)
                            waitMore(pause)
                        }
                    }, 60)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (phase.value != Phase.LISTENING) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let {
                        heard.value = join(collected, it)
                        // są nowe słowa: czekamy na wynik (z zapasem, gdyby rozpoznawanie się zawiesiło)
                        if (more) waitMore(10_000)
                    }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName),
        )
    }

    private fun ask(text: String) {
        val act = MainActivity.live ?: return showError("Kokpit nie działa – naciśnij Home i spróbuj jeszcze raz.")
        Hotword.resume()
        heard.value = text
        phase.value = Phase.THINKING
        activity.value++
        Assistant.send(act, text)
        // odpowiedź = pierwsza linia po pytaniu (send mógł zacząć nową rozmowę po limicie tokenów);
        // bez zapytania (np. brak klucza) ostatnia linia to już komunikat
        waitingLines = if (Assistant.busy.value) Assistant.lines.size else Assistant.lines.size - 1
    }

    private fun showError(text: String) {
        Hotword.resume()
        answer.value = text
        phase.value = Phase.ERROR
        activity.value++
    }

    private fun stopAndClose() {
        cancelWait()
        recognizer?.cancel()
        finish()
    }

    @Composable
    private fun VoiceOverlay() {
        val p by phase
        val busy by Assistant.busy
        // odpowiedź asystenta: pierwsza nowa linia po pytaniu
        LaunchedEffect(Assistant.lines.size, busy) {
            if (phase.value == Phase.THINKING && !busy && waitingLines >= 0 && Assistant.lines.size > waitingLines) {
                answer.value = Assistant.lines.last().text
                phase.value = Phase.ANSWER
                activity.value++
            }
        }
        // samo się zamyka po chwili bez rozmowy (odpowiedź dalej czytana na głos)
        val idle by activity
        LaunchedEffect(idle, p) {
            if (p == Phase.ANSWER || p == Phase.ERROR) {
                delay(if (p == Phase.ANSWER) 25_000 else 10_000)
                finish()
            }
        }
        val accent = Accent
        // okienko w wybranym wyglądzie Kokpitu (kolory, kształt rogów, krój pisma, tryb jasny)
        val theme = effectiveTheme()
        val shape = theme.shape(28.dp)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalTheme provides theme,
            androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = Fonts.family(ThemeCustoms.current().font)),
        ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(interactionSource = remember0(), indication = null) { Speech.stop(); stopAndClose() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .padding(bottom = 28.dp)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.62f)
                    .clip(shape)
                    .background(if (LightMode.on) Color(0xFFF4F6FA) else Color(0xFF0A0D14)) // pod spodem pełne tło: bloki bywają półprzezroczyste
                    .background(theme.block)
                    .border(1.5.dp, theme.blockEdge, shape)
                    .clickable(interactionSource = remember0(), indication = null) { activity.value++ }
                    .padding(horizontal = 28.dp, vertical = 22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Asystent · ${Assistant.providerName()}",
                        color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.Close, "Zamknij", tint = Dim,
                        modifier = Modifier.size(34.dp).clip(CircleShape).clickable { Speech.stop(); stopAndClose() }.padding(5.dp),
                    )
                }
                Spacer(Modifier.size(10.dp))
                val h by heard
                if (h.isNotBlank()) {
                    Text("„$h”", color = Dim, fontSize = 20.sp)
                    Spacer(Modifier.size(12.dp))
                }
                val a by answer
                when (p) {
                    Phase.LISTENING -> if (h.isBlank()) Text("Słucham…", color = Txt, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    Phase.THINKING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Myślę…", color = Txt, fontSize = 22.sp)
                    }
                    Phase.ANSWER, Phase.ERROR -> Text(
                        a,
                        color = if (p == Phase.ERROR) Warn else Txt,
                        fontSize = 22.sp, lineHeight = 30.sp,
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.size(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    MicButton(listening = p == Phase.LISTENING, enabled = p != Phase.THINKING, accent = accent) {
                        if (p == Phase.LISTENING) {
                            manualStop = true
                            // w czasie czekania na dalszy ciąg nie ma na co czekać – wysyłamy to, co jest
                            if (more && collected.isNotBlank() && waitRun != null) { recognizer?.cancel(); finishQuestion() }
                            else recognizer?.stopListening()
                        } else { Speech.stop(); begin() }
                    }
                    // odpowiedź jeszcze raz na głos (ten sam tekst gra z pamięci, bez nowego zapytania o mowę)
                    if (p == Phase.ANSWER && a.isNotBlank()) {
                        Spacer(Modifier.width(18.dp))
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                            Box(
                                Modifier.size(60.dp).clip(CircleShape).background(Color(0x33808A99))
                                    .clickable { activity.value++; Speech.speak(this@VoiceActivity, a) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Replay, "Powtórz odpowiedź", tint = Txt, modifier = Modifier.size(30.dp)) }
                        }
                    }
                }
            }
        }
        }
    }

    @Composable
    private fun MicButton(listening: Boolean, enabled: Boolean, accent: Color, onClick: () -> Unit) {
        val pulse = rememberInfiniteTransition(label = "mic")
        val wave by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "wave")
        val lvl by level
        val s = if (listening) wave + lvl * 0.25f else 1f
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
            if (listening) Box(Modifier.size(64.dp).scale(s).clip(CircleShape).background(accent.copy(alpha = 0.25f)))
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .then(if (enabled) Modifier.background(LocalTheme.current.active) else Modifier.background(Color(0xFF3A4350)))
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, if (listening) "Skończ mówić" else "Mów", tint = if (enabled) LocalTheme.current.activeText else Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }

    @Composable
    private fun remember0() = androidx.compose.runtime.remember { MutableInteractionSource() }

    companion object {
        /** okienko jest na ekranie – asystent nie pokazuje wtedy odpowiedzi w dymku */
        @Volatile
        var open = false

        fun start(ctx: android.content.Context) {
            ctx.startActivity(Intent(ctx, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
