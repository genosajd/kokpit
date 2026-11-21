package pl.lawinski.radiolauncher

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/*
 * Usuwanie echa w rozmowie telefonicznej (tylko radio Witstek). Sam Kokpit (uid aplikacji) nie ma dostępu do
 * sprzętowego toru dźwięku rozmowy – to pętla w układzie DSP, do której trzeba roota. Dlatego właściwą pracę
 * robi osobny program „kokpit-echo” (źródła w katalogu echo/ repozytorium): przejmuje dźwięk rozmowy, przepuszcza
 * go przez bibliotekę WebRTC (usuwanie echa, odszumianie) i oddaje do głośnika i Bluetootha.
 *
 * Program jest dołączony do aplikacji jako zasób (arm64) i uruchamiany z prawami systemu przez fabryczną usługę
 * Witstek „witssudo” (ctl.start). Kokpit tylko wgrywa program, uruchamia go przy starcie i steruje nim po TCP
 * (127.0.0.1:47811 – polecenia on/off/status). Stan włączenia program pamięta po swojej stronie, więc działa też
 * po restarcie radia. Partycji systemowej to nie rusza.
 */
object Echo {
    private const val TAG = "KokpitEcho"
    private const val PORT = 47811
    private const val VERSION = 1 // podnosić przy zmianie programu – wymusza ponowne wgranie do radia
    const val BIN = "/data/local/tmp/kokpit-echo"

    /** funkcja dostępna tylko na radiu Witstek (tam jest fabryczna usługa roota i znany tor dźwięku) */
    val available: Boolean get() = Radio.witstek

    /** stan ustawienia (czego chce użytkownik) i ostatni opis z programu */
    val enabled = mutableStateOf(false)
    val note = mutableStateOf("")
    val running = mutableStateOf(false)

    private var synced = false

    fun init(ctx: Context) {
        enabled.value = Prefs.echo
    }

    /** Wywoływane przy starcie Kokpitu: wgraj program i dopilnuj, że działa (jeśli funkcja włączona lub już była). */
    fun sync(ctx: Context) {
        if (!available || synced) return
        synced = true
        val app = ctx.applicationContext
        thread(name = "KokpitEchoSync") {
            runCatching { deployAndStart(app) }.onFailure { Log.w(TAG, "wgranie programu: ${it.message}") }
            Thread.sleep(1200)
            // ustaw w programie stan zgodny z ustawieniem Kokpitu
            command(if (Prefs.echo) "on" else "off")
            refresh()
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        Prefs.echo = on
        enabled.value = on
        val app = ctx.applicationContext
        thread(name = "KokpitEchoSet") {
            if (on) runCatching { deployAndStart(app) }
            command(if (on) "on" else "off")
            refresh()
        }
    }

    /** Odśwież stan z programu (status) – aktualizuje [running] i [note]. */
    fun refresh() {
        val r = command("status") ?: run {
            running.value = false
            note.value = "program nie odpowiada"
            return
        }
        // enabled=1 running=1 aec=webrtc frames=… xruns=… note=…
        running.value = Regex("running=(\\d)").find(r)?.groupValues?.get(1) == "1"
        note.value = Regex("note=(.*)").find(r)?.groupValues?.get(1)?.trim().orEmpty()
    }

    /** Program działa (odpowiada na status). */
    fun alive(): Boolean = command("status") != null

    private fun command(cmd: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
            s.soTimeout = 1500
            s.getOutputStream().write((cmd + "\n").toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** Wgraj program do pamięci aplikacji i uruchom go z prawami systemu przez usługę Witstek. */
    private fun deployAndStart(ctx: Context) {
        val src = File(ctx.filesDir, "kokpit-echo")
        // zasób wypakowujemy tylko, gdy zmieniła się wersja (plik .ver w pamięci aplikacji)
        val verFile = File(ctx.filesDir, "kokpit-echo.ver")
        if (verFile.readTextOrNull()?.trim() != VERSION.toString() || !src.exists()) {
            ctx.assets.open("kokpit-echo").use { input -> src.outputStream().use { input.copyTo(it) } }
            verFile.writeText(VERSION.toString())
        }
        val start = File(ctx.filesDir, "kokpit-echo-start.sh")
        start.writeText(startScript(src.absolutePath))
        // „witssudo” (fabryczna usługa roota) uruchomi: sh <start.sh>; argumenty przez „:” (skrypt zamienia je na spacje)
        setControl("ctl.start", "witssudo:sh:${start.absolutePath}")
    }

    // Usługa witssudo jest „oneshot” – po zakończeniu skryptu init ubija jego dzieci. Dlatego skrypt na końcu
    // robi exec: sam staje się demonem i żyje w miejscu usługi (init widzi usługę jako działającą). Gdy demon
    // już działa, ctl.start niczego nie uruchamia, więc kolejne wywołania przy starcie Kokpitu są nieszkodliwe.
    private fun startScript(srcPath: String): String = """
        #!/system/bin/sh
        VER=/data/local/tmp/kokpit-echo.ver
        want=$VERSION
        if pgrep -x kokpit-echo > /dev/null; then exit 0; fi
        if [ "${'$'}want" != "${'$'}(cat ${'$'}VER 2>/dev/null)" ] || [ ! -f $BIN ]; then
          cp "$srcPath" $BIN
          chmod 755 $BIN
          echo ${'$'}want > ${'$'}VER
        fi
        exec $BIN > /data/local/tmp/kokpit-echo.log 2>&1
    """.trimIndent().replace("\n        ", "\n")

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    /**
     * Uruchom usługę roota radia. Dopisywanie argumentów do witssudo robi program „setprop” (dzieli wartość po „:”),
     * nie samo API właściwości – dlatego wołamy binarkę setprop tak samo jak z powłoki.
     */
    private fun setControl(key: String, value: String) {
        runCatching {
            val p = ProcessBuilder("setprop", key, value).redirectErrorStream(true).start()
            p.waitFor()
            Log.i(TAG, "uruchamiam program przez usługę radia (setprop $key)")
        }.onFailure { Log.w(TAG, "ctl.start: ${it.message}") }
    }
}
