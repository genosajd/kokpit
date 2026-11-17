package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/*
 * Widok cofania Kokpitu (radio Witstek w BMW z ekranem NBT): obraz kamery cofania i osiem czujników parkowania.
 *
 * Źródła (sprawdzone w aucie 2026-11-16):
 * - ramka MCU `0c 61 F x S1…S8 y z` (z dziennika radia, ok. 5 razy na sekundę): F = 1 → radio samo otwiera
 *   swój widok kamery (CenterService „updateBackCar value=1”, wsteczny bieg); S1…S8 = czujniki: przód lewy,
 *   lewy środek, prawy środek, prawy, potem tył w tej samej kolejności; 0 = tuż przy zderzaku, 2…7 coraz dalej,
 *   0x0a = brak przeszkody. Skali w centymetrach nie znamy.
 * - obraz: wejście wideo radia widoczne jako kamera Androida „1”, 1280×480 = cały ekran iDrive; kamera cofania
 *   BMW zajmuje w nim pas x 206…926 (reszta to menu iDrive i schemat czujników rysowany przez BMW).
 *   Linie toru jazdy rysuje BMW – są w obrazie.
 * Wejście obrazu może mieć jednego odbiorcę: otwarcie go przez Kokpit odbiera je widokowi Witstek
 * (com.wits.misc), który zostaje pod spodem. Dlatego widok Kokpitu jest opcją (Prefs.reverseView),
 * a gdy Kokpit nie działa albo nie ma zgody na dzienniki, zostaje widok oryginalny.
 */
object Reverse {
    const val NONE = 10

    data class State(val active: Boolean = false, val sensors: List<Int> = List(8) { NONE })

    val state = mutableStateOf(State())

    /** kiedy przyszła ostatnia ramka czujników (0 = jeszcze żadna) */
    @Volatile
    var lastAt = 0L
        private set

    /** użytkownik zamknął widok – nie otwierać ponownie do końca tego cofania */
    @Volatile
    var dismissed = false

    /** stan ustawiony testem przez ADB – widok nie zamyka się z braku ramek */
    @Volatile
    var test = false

    /** ile razy w tym cofaniu widok wracał na wierzch po przykryciu przez widok radia */
    @Volatile
    var relaunches = 0

    private val h = Handler(Looper.getMainLooper())

    val enabled: Boolean get() = Radio.witstek && Prefs.reverseView == "kokpit"

    /** Ramka czujników z dziennika radia (wątek główny). [fresh] = nie zaległa sprzed startu Kokpitu. */
    fun frame(ctx: Context, b: ByteArray, fresh: Boolean) {
        if (!fresh || b.size < 12 || b[0].toInt() != 0x0c || b[1].toInt() != 0x61) return
        test = false
        set(ctx, b[2].toInt() == 1, (4..11).map { (b[it].toInt() and 0xFF).let { v -> if (v > 7) NONE else v } })
    }

    fun set(ctx: Context, active: Boolean, sensors: List<Int>) {
        lastAt = System.currentTimeMillis()
        val old = state.value
        val now = State(active, sensors)
        if (now != old) state.value = now
        if (active && !old.active) {
            dismissed = false
            relaunches = 0
            // chwilę po widoku radia, żeby okno Kokpitu znalazło się nad nim
            if (enabled) h.postDelayed({ if (state.value.active && !dismissed && enabled) ReverseActivity.open(ctx.applicationContext) }, 600)
        }
        if (!active && old.active) {
            dismissed = false
            ReverseActivity.closeIfOpen()
        }
    }

    /** najbliższa przeszkoda: indeks czujnika i stopień albo null, gdy nic nie widać */
    fun nearest(s: List<Int>): Pair<Int, Int>? = s.withIndex().filter { it.value < NONE }.minByOrNull { it.value }?.let { it.index to it.value }

    val NAMES = listOf("Przód, lewy róg", "Przód, lewy środek", "Przód, prawy środek", "Przód, prawy róg", "Tył, lewy róg", "Tył, lewy środek", "Tył, prawy środek", "Tył, prawy róg")

    /** ile z czterech pasów świeci przy danym stopniu (0 = żaden) */
    fun bands(v: Int) = when { v >= NONE -> 0; v >= 6 -> 1; v >= 4 -> 2; v >= 2 -> 3; else -> 4 }

    fun word(v: Int) = when (bands(v)) { 4 -> "tuż przy zderzaku"; 3 -> "blisko"; 2 -> "średnio"; else -> "daleko" }

    val COLORS = listOf(Color(0xFF3DDC84), Color(0xFFF2D94A), Color(0xFFFF9A3C), Color(0xFFFF4B4B))

    fun color(v: Int): Color = COLORS[(bands(v) - 1).coerceIn(0, 3)]
}

/** Obraz z wejścia wideo radia na TextureView; kadruje pas kamery cofania z ekranu iDrive. */
private class BmwCamera(private val view: TextureView, private val onStatus: (String?) -> Unit) : TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var started = false
    private var tries = 0
    private var bw = 1280
    private var bh = 480
    /** wypełnij cały widok (przycinając) zamiast pokazać cały kadr */
    var cover = false
        set(v) { field = v; applyTransform() }

    init { view.surfaceTextureListener = this }

    fun start() { started = true; tries = 0; if (view.isAvailable) open() }

    fun stop() {
        started = false
        main.removeCallbacksAndMessages(null)
        runCatching { device?.close() }
        device = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (started) open() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { device?.close() }; device = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        st.getTransformMatrix(texTmp)
        if (!texTmp.contentEquals(tex)) { tex = texTmp.copyOf(); Log.i("KokpitReverse", "macierz obrazu: ${tex.joinToString(" ") { "%.2f".format(it) }}"); applyTransform() }
        if (tries != -1) { tries = -1; onStatus(null) }
    }

    private fun retry(why: String) {
        Log.w("KokpitReverse", why)
        runCatching { device?.close() }
        device = null
        if (!started) return
        if (tries in 0..3) { tries++; onStatus("Łączenie z kamerą…"); main.postDelayed({ if (started && device == null) open() }, 800) }
        else onStatus("Brak obrazu z kamery")
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        val ctx = view.context
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onStatus("Kokpit nie ma zgody na kamerę – włącz ją w Ustawieniach Androida"); return
        }
        val st = view.surfaceTexture ?: return
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val ids = cm.cameraIdList
            // w radiu Witstek ekran BMW to kamera „1”; gdzie indziej (emulator) pierwsza z listy
            val id = if ("1" in ids && Radio.detected) "1" else ids.firstOrNull() ?: run { onStatus("Radio nie ma wejścia obrazu"); return }
            val sizes = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
            val size = sizes.firstOrNull { it.width == 1280 && it.height == 480 }
                ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: sizes.firstOrNull()
            if (size != null) { bw = size.width; bh = size.height }
            st.setDefaultBufferSize(bw, bh)
            applyTransform()
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (!started) { d.close(); return }
                    device = d
                    runCatching {
                        val surface = Surface(st)
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { runCatching { s.setRepeatingRequest(req.build(), null, main) }.onFailure { retry("podgląd: $it") } }
                            override fun onConfigureFailed(s: CameraCaptureSession) = retry("sesja odrzucona")
                        }, main)
                    }.onFailure { retry("sesja: $it") }
                }
                override fun onDisconnected(d: CameraDevice) = retry("wejście obrazu odebrane")
                override fun onError(d: CameraDevice, e: Int) = retry("błąd wejścia obrazu $e")
            }, main)
        } catch (e: Exception) {
            retry("otwarcie: $e")
        }
    }

    /** macierz obrazu podana przez Androida (obrót / odbicie bufora kamery); domyślnie samo odwrócenie w pionie */
    private var tex = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    private val texTmp = FloatArray(16)

    /**
     * Kadr: z obrazu 1280×480 (ekran iDrive) tylko pas kamery; inne rozmiary w całości.
     * Wejście obrazu radia zgłasza się jako kamera obrócona o 270°, więc Android obraca podgląd w TextureView.
     * Zamiast zgadywać obrót, bierzemy macierz SurfaceTexture: punkt widoku (u, v) pokazuje piksel bufora
     * M·(u, 1−v). Składamy: widok → bufor (odwrócenie tego, co robi Android) → docelowe miejsce kadru.
     */
    private fun applyTransform() {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        if (w <= 0 || h <= 0) return
        val bmw = bw == 1280 && bh == 480
        val rx = if (bmw) 206f else 0f
        val rw = if (bmw) 720f else bw.toFloat()
        val rh = bh.toFloat()
        val s = if (cover) maxOf(w / rw, h / rh) else minOf(w / rw, h / rh)
        val ox = (w - rw * s) / 2f
        val oy = (h - rh * s) * (if (cover) 0.78f else 0.5f) // przy przycinaniu zostaje dół – najbliżej zderzaka
        val m = Matrix()
        m.setScale(1f / w, -1f / h); m.postTranslate(0f, 1f)                 // punkt widoku → (u, 1−v)
        m.postConcat(Matrix().apply { setValues(floatArrayOf(tex[0], tex[4], tex[12], tex[1], tex[5], tex[13], 0f, 0f, 1f)) }) // → współrzędne bufora 0…1
        m.postScale(bw.toFloat(), bh.toFloat())                              // → piksel bufora
        m.postTranslate(-rx, 0f); m.postScale(s, s); m.postTranslate(ox, oy) // → miejsce kadru w widoku
        view.setTransform(m)
    }
}

class ReverseActivity : ComponentActivity() {
    private var userClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        live = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val st by Reverse.state
            // koniec cofania albo dzienniki przestały przychodzić → wraca widok radia / poprzedni ekran
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    if (!Reverse.test && System.currentTimeMillis() - Reverse.lastAt > 5000) { finish(); break }
                }
            }
            if (!st.active) { LaunchedEffect(Unit) { finish() } }
            val theme = effectiveTheme()
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).background(theme.screen)) {
                val u = min(maxWidth / 2560f, maxHeight / 720f)
                CompositionLocalProvider(
                    LocalTheme provides theme,
                    LocalU provides u,
                    androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
                        fontFamily = Fonts.family(ThemeCustoms.current().font),
                    ),
                ) {
                    ReverseScreen(
                        st,
                        onClose = { close() },
                        onBmw = { close(); openCarScreen(this@ReverseActivity) },
                    )
                }
            }
        }
    }

    private fun close() {
        userClosed = true
        Reverse.dismissed = true
        finish()
    }

    override fun onStop() {
        super.onStop()
        // widok radia otworzył się później i przykrył Kokpit – wróć na wierzch (najwyżej dwa razy na jedno cofanie)
        if (!userClosed && !isFinishing && Reverse.state.value.active && !Reverse.dismissed && Reverse.enabled && Reverse.relaunches < 2) {
            Reverse.relaunches++
            val app = applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ if (Reverse.state.value.active && !Reverse.dismissed) open(app) }, 500)
        }
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var live: ReverseActivity? = null

        fun open(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(ctx, ReverseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.onFailure { Log.w("KokpitReverse", "okno: $it") }
        }

        fun closeIfOpen() {
            live?.let { it.runOnUiThread { it.finish() } }
        }
    }
}

@Composable
private fun ReverseScreen(st: Reverse.State, onClose: () -> Unit, onBmw: () -> Unit) {
    val theme = LocalTheme.current
    var full by remember { mutableStateOf(Prefs.reverseFull) }
    var status by remember { mutableStateOf<String?>("Łączenie z kamerą…") }
    var cam by remember { mutableStateOf<BmwCamera?>(null) }
    DisposableEffect(Unit) { onDispose { cam?.stop() } }
    LaunchedEffect(full, cam) { cam?.cover = full }
    fun setFull(v: Boolean) { full = v; Prefs.reverseFull = v }
    // Kamera jest zawsze pierwszym dzieckiem tego samego Boxa – zmiana trybu zmienia tylko jej rozmiar,
    // więc wejście obrazu nie jest otwierane od nowa.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val shape = theme.shape(d(28))
        val camW = (maxHeight - d(52)) * 1.5f
        Box(
            (if (full) Modifier.fillMaxSize()
            else Modifier.align(Alignment.CenterStart).padding(start = d(26)).width(camW).aspectRatio(1.5f).clip(shape).border(1.dp, theme.blockEdge, shape))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { tv -> cam = BmwCamera(tv) { s -> status = s }.also { it.cover = full; it.start() } } },
                modifier = Modifier.fillMaxSize(),
            )
            status?.let { Text(it, color = Color.White, fontSize = s(30), textAlign = TextAlign.Center, modifier = Modifier.padding(d(30))) }
        }
        if (full) {
            Row(Modifier.align(Alignment.BottomEnd).padding(d(30)), horizontalArrangement = Arrangement.spacedBy(d(18))) {
                RvButton("Czujniki", Modifier.width(d(240)), solid = true) { setFull(false) }
                RvButton("Ekran BMW", Modifier.width(d(240)), solid = true, onClick = onBmw)
                RvButton("Zamknij", Modifier.width(d(240)), primary = true, onClick = onClose)
            }
        } else {
            Row(
                Modifier.fillMaxSize().padding(start = d(26) + camW + d(24), top = d(26), end = d(26), bottom = d(26)),
                horizontalArrangement = Arrangement.spacedBy(d(24)),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(shape).background(theme.block).border(1.dp, theme.blockEdge, shape).padding(d(14)),
                    contentAlignment = Alignment.Center,
                ) { SensorMap(st.sensors, Modifier.fillMaxHeight().aspectRatio(0.56f)) }
                Column(
                    Modifier.width(d(430)).fillMaxHeight().clip(shape).background(theme.block).border(1.dp, theme.blockEdge, shape).padding(d(28)),
                    verticalArrangement = Arrangement.spacedBy(d(18)),
                ) {
                    Column {
                        Text("BIEG", color = Dim, fontSize = s(22))
                        Text(CarData.status.value.gear ?: "R", color = Accent, fontSize = s(84), fontWeight = FontWeight.SemiBold)
                    }
                    Column {
                        Text("NAJBLIŻEJ", color = Dim, fontSize = s(22))
                        val near = Reverse.nearest(st.sensors)
                        if (near == null) Text("Brak przeszkód", color = Txt, fontSize = s(32))
                        else {
                            Text(Reverse.NAMES[near.first], color = Reverse.color(near.second), fontSize = s(32), fontWeight = FontWeight.SemiBold)
                            Text(Reverse.word(near.second), color = Txt, fontSize = s(32))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(d(14))) {
                        RvButton("Pełny ekran", Modifier.weight(1f)) { setFull(true) }
                        RvButton("Ekran BMW", Modifier.weight(1f), onClick = onBmw)
                    }
                    RvButton("Zamknij", Modifier.fillMaxWidth(), primary = true, onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun RvButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, solid: Boolean = false, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val shape = theme.shape(d(16))
    val back: Brush = when {
        primary -> theme.active
        solid -> SolidColor(Color(0xE6101630))
        else -> theme.surface
    }
    Box(
        modifier.clip(shape).background(back).border(1.dp, theme.blockEdge, shape).clickable(onClick = onClick).padding(vertical = d(20), horizontal = d(10)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) theme.activeText else if (solid) Color.White else Txt, fontSize = s(26), maxLines = 1)
    }
}

/** Auto z góry i osiem czujników: po cztery pasy na czujnik, świecą od najdalszego; kolor = najbliższy stopień. */
@Composable
fun SensorMap(sensors: List<Int>, modifier: Modifier = Modifier) {
    val off = Txt.copy(alpha = 0.10f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.height / 500f
            val cx = size.width / 2f
            fun fan(vals: List<Int>, cy: Float, start: Float, dir: Int) {
                vals.forEachIndexed { i, v ->
                    val n = Reverse.bands(v)
                    val a = start + dir * i * 30f
                    val a0 = minOf(a, a + dir * 30f) + 2f
                    for (b in 0 until 4) {
                        val r = (92f + b * 24f + 8.5f) * k
                        drawArc(
                            color = if (3 - b < n) Reverse.color(v) else off,
                            startAngle = a0, sweepAngle = 26f, useCenter = false,
                            topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2),
                            style = Stroke(width = 17f * k),
                        )
                    }
                }
            }
            fan(sensors.take(4), size.height / 2f - 58f * k, -150f, 1)       // przód: od lewej do prawej
            fan(sensors.drop(4).take(4), size.height / 2f + 58f * k, 150f, -1) // tył: od lewej do prawej
        }
        Image(painterResource(R.drawable.car_f11_top), "Auto z góry", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxHeight(0.6f))
    }
}
