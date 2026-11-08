package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Colors
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.File
import java.nio.ByteBuffer

/** Model 3D auta wczytany z pliku .glb, obracany palcem. */
object CarModel {
    private var ready = false

    fun init() {
        if (!ready) {
            Utils.init()
            ready = true
        }
    }

    fun file(ctx: Context) = File(ctx.filesDir, "car.glb")

    /** Kopiuje wybrany plik .glb do plików aplikacji, żeby działał też po wyjęciu pendrive'a. */
    fun import(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            file(ctx).outputStream().use { input.copyTo(it) }
        }
        val ok = file(ctx).length() > 0
        Prefs.carModel = if (ok) file(ctx).absolutePath else ""
        ok
    } catch (e: Exception) {
        false
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
        Prefs.carModel = ""
    }

    /**
     * Wbudowany model białego F11 (z AI w ComfyUI, przygotowany skryptem model3d/przygotuj.py).
     * Przy pierwszym uruchomieniu staje się modelem auta; po "Usuń model 3D" już nie wraca sam.
     */
    fun installDefault(ctx: Context, force: Boolean = false): Boolean {
        if (!force && Prefs.carModelInitialized) return false
        Prefs.carModelInitialized = true
        return try {
            ctx.assets.open(DEFAULT_ASSET).use { input -> file(ctx).outputStream().use { input.copyTo(it) } }
            Prefs.carModel = file(ctx).absolutePath
            true
        } catch (e: Exception) {
            false
        }
    }

    private const val DEFAULT_ASSET = "car_f11.glb"
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun Car3DView(path: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val holder = remember(path) { Car3DHolder(ctx, path) }
    DisposableEffect(holder) {
        holder.start()
        onDispose { holder.destroy() }
    }
    AndroidView(factory = { holder.view }, modifier = modifier)
}

/**
 * TextureView z Filament: przezroczyste tło, światło słoneczne i rozproszone, obrót palcem.
 * Radio ma słabe GPU, więc model rysuje się tylko wtedy, gdy coś się zmienia: przez chwilę po
 * wczytaniu (tekstury dochodzą asynchronicznie), w czasie dotyku i zaraz po nim. Bez dotyku
 * obraz stoi i nie obciąża procesora ani karty graficznej.
 */
private class Car3DHolder(ctx: Context, path: String) : Choreographer.FrameCallback {
    val view: TextureView
    private val viewer: ModelViewer
    private val choreographer = Choreographer.getInstance()
    private var running = false
    private var scheduled = false
    private var framesLeft = START_FRAMES

    init {
        CarModel.init()
        view = TextureView(ctx)
        val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
        // kamera bliżej i lekko z góry niż domyślna, żeby auto wypełniało klocek
        val orbit = Manipulator.Builder()
            .targetPosition(0f, 0f, -4f)
            .orbitHomePosition(0f, 0.75f, -2.0f)
            .orbitSpeed(0.006f, 0.006f)
            .zoomSpeed(0.02f)
            .build(Manipulator.Mode.ORBIT)
        viewer = ModelViewer(view, uiHelper = ui, manipulator = orbit)
        viewer.view.blendMode = View.BlendMode.TRANSLUCENT
        viewer.renderer.clearOptions = viewer.renderer.clearOptions.apply { clear = true }
        viewer.scene.skybox = null
        // mniej pikseli do policzenia: 70% rozdzielczości klocka, bez cieni i okluzji otoczenia
        viewer.view.dynamicResolutionOptions = viewer.view.dynamicResolutionOptions.apply {
            enabled = true
            homogeneousScaling = true
            minScale = 0.7f
            maxScale = 0.7f
        }
        viewer.view.setShadowingEnabled(false)
        viewer.view.ambientOcclusionOptions = viewer.view.ambientOcclusionOptions.apply { enabled = false }

        val engine = viewer.engine
        viewer.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(1f, 1f, 1f))
            .intensity(28_000f)
            .build(engine)
        val sun = EntityManager.get().create()
        val (r, g, b) = Colors.cct(6000f)
        LightManager.Builder(LightManager.Type.SUN)
            .color(r, g, b)
            .intensity(90_000f)
            .direction(0.4f, -1f, -0.6f)
            .castShadows(false)
            .build(engine, sun)
        viewer.scene.addEntity(sun)

        val bytes = File(path).readBytes()
        viewer.loadModelGlb(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() })
        viewer.transformToUnitCube()
        view.setOnTouchListener { _, e ->
            viewer.onTouchEvent(e)
            wake(AFTER_TOUCH_FRAMES)
            true
        }
        // nowy rozmiar klocka (np. po edycji układu): narysować na nowo
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> wake(AFTER_TOUCH_FRAMES) }
    }

    private fun wake(frames: Int) {
        framesLeft = maxOf(framesLeft, frames)
        if (running && !scheduled) {
            scheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        running = true
        wake(START_FRAMES)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        if (!running) return
        viewer.render(frameTimeNanos)
        if (--framesLeft > 0) {
            scheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    // ModelViewer sam zwalnia silnik i model, gdy TextureView zostaje odpięty od okna
    fun destroy() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    companion object {
        /** ok. 3 s po wczytaniu, aż dojdą wszystkie tekstury */
        const val START_FRAMES = 180

        /** ok. 0,3 s po każdym ruchu palca */
        const val AFTER_TOUCH_FRAMES = 20
    }
}
