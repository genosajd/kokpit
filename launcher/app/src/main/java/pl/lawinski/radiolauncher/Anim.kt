package pl.lawinski.radiolauncher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.State
import kotlinx.coroutines.launch
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/*
 * Animacje wyglądów z KokpitTheme.animated (BMW iDrive, M Sport).
 * Działają tylko, gdy Kokpit jest na ekranie i nie trwa edycja układu (LocalAnimate).
 * Każda zmienia wyłącznie właściwości warstwy (przesunięcie, krycie, skalę) w graphicsLayer,
 * więc w każdej klatce radio tylko składa gotowe warstwy, bez przerysowywania treści bloków.
 */

/** true: animacje włączone (wygląd animowany, Kokpit widoczny, bez edycji układu). */
val LocalAnimate = compositionLocalOf { false }

/**
 * Odstęp klatek animacji wyglądu (ok. 20 kl./s). Animacje są powolne, a pełne 60 kl./s
 * w radiu zajmowało ok. 70% rdzenia samym składaniem warstw (RenderThread).
 */
private const val FRAME_MS = 80L

/** Wspólny zegar animacji (ms): jeden licznik dla wszystkich, żeby klatki się pokrywały. */
private object AnimClock {
    val now = androidx.compose.runtime.mutableLongStateOf(0L)
    var users = 0
    var job: kotlinx.coroutines.Job? = null
}

/** Wartość 0..1 w pętli: tam i z powrotem (reverse) albo od nowa; odświeżana co [FRAME_MS] (wspólny zegar). */
@Composable
fun loop(durationMs: Int, reverse: Boolean = true, delayMs: Int = 0): State<Float> {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(Unit) {
        if (AnimClock.users++ == 0) AnimClock.job = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (true) {
                AnimClock.now.longValue = System.nanoTime() / 1_000_000
                kotlinx.coroutines.delay(FRAME_MS)
            }
        }
        onDispose {
            if (--AnimClock.users == 0) { AnimClock.job?.cancel(); AnimClock.job = null }
        }
    }
    val start = remember { System.nanoTime() / 1_000_000 + delayMs }
    return remember(durationMs, reverse) {
        androidx.compose.runtime.derivedStateOf {
            val t = (AnimClock.now.longValue - start).coerceAtLeast(0)
            if (reverse) {
                // tam i z powrotem z łagodnym początkiem i końcem
                val cycle = (t % (2L * durationMs)).toFloat() / durationMs
                FastOutSlowInEasing.transform(if (cycle <= 1f) cycle else 2f - cycle)
            } else (t % durationMs).toFloat() / durationMs
        }
    }
}

/** Dwie wolno płynące poświaty za blokami. */
@Composable
fun Aurora() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        val (c1, c2) = LocalTheme.current.aurora
        val a = loop(14000)
        val b = loop(19000, delayMs = 6000)
        Glow(c1, Alignment.TopStart) { translationX = w * (0.05f + 0.6f * a.value); translationY = -size.height * 0.55f }
        Glow(c2, Alignment.BottomStart) { translationX = w * (0.65f - 0.6f * b.value); translationY = size.height * 0.55f }
    }
}

@Composable
private fun BoxScope.Glow(color: Color, align: Alignment, move: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) {
    Box(
        Modifier.align(align).fillMaxHeight().aspectRatio(1f)
            .graphicsLayer { scaleX = 2.4f; move() }
            .drawBehind { drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, size.minDimension / 2), size.minDimension / 2) }
    )
}

private var blockCounter = 0

/** Światło przesuwające się co kilka sekund po górnej krawędzi bloku. */
@Composable
fun BoxScope.BlockSweep() {
    val delay = remember { (blockCounter++ % 6) * 1100 }
    val p = loop(7000, reverse = false, delayMs = delay)
    val c = LocalTheme.current.sweep
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth(0.4f).height(2.dp)
            .graphicsLayer {
                val x = (p.value / 0.45f).coerceAtMost(1f)
                translationX = (x * 3.6f - 1f) * size.width
                alpha = if (p.value > 0.45f) 0f else 1f
            }
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c, Color.Transparent))) }
    )
}

/** „Oddychająca” poświata pod autem. */
@Composable
fun BoxScope.CarGlow(color: Color) {
    val p = loop(4000)
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.8f).fillMaxHeight(0.35f)
            .graphicsLayer { alpha = 0.55f + 0.45f * p.value; scaleX = 0.92f + 0.13f * p.value }
            .drawBehind {
                scale(1f, 0.35f) {
                    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, size.width / 2), size.width / 2)
                }
            }
    )
}

/** Błysk przesuwający się po pasku paliwa. */
@Composable
fun BoxScope.Shimmer() {
    val p = loop(3000, reverse = false)
    Box(
        Modifier.fillMaxHeight().fillMaxWidth(0.35f)
            .graphicsLayer { translationX = (p.value * 4f - 1f) * size.width }
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x99FFFFFF), Color.Transparent))) }
    )
}

/*
 * Przebłyski wyglądu z KokpitTheme.gloss (Individual). Muszą być płynne, więc idą w pełnym tempie ekranu,
 * ale tylko w krótkich seriach: co kilka sekund jedna seria (ok. 3 s), w której po każdym bloku
 * przechodzi ukośna smuga światła, po posadzce pod autem pas światła, a na aucie migają iskry.
 * Między seriami nic się nie rysuje. Każdy efekt to gotowa warstwa, której zmienia się tylko
 * przesunięcie, krycie albo skala (graphicsLayer) – treść bloków nie jest przerysowywana.
 */

/** true: przebłyski włączone (wygląd z gloss, Kokpit widoczny, bez edycji układu). */
val LocalGloss = compositionLocalOf { false }

object Glint {
    /** sekundy od początku bieżącej serii; ujemne = przerwa */
    val t = androidx.compose.runtime.mutableFloatStateOf(-1f)
    const val ACTIVE = 3.4f
    const val PAUSE_MS = 5200L

    /** postęp 0..1 efektu zaczynającego się [start] s po początku serii i trwającego [dur] s; null = poza efektem */
    fun progress(start: Float, dur: Float): Float? {
        val x = (t.floatValue - start) / dur
        return if (x < 0f || x > 1f) null else x
    }
}

/** Zegar serii przebłysków: klatki ekranu tylko w czasie serii. */
@Composable
fun GlintDriver() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.delay(1200)
            while (true) {
                val start = androidx.compose.runtime.withFrameNanos { it }
                while (true) {
                    val now = androidx.compose.runtime.withFrameNanos { it }
                    val sec = (now - start) / 1_000_000_000f
                    if (sec > Glint.ACTIVE) break
                    Glint.t.floatValue = sec
                }
                Glint.t.floatValue = -1f
                kotlinx.coroutines.delay(Glint.PAUSE_MS)
            }
        } finally {
            Glint.t.floatValue = -1f
        }
    }
}

private var glossCounter = 0

/** Ukośna smuga światła przechodząca przez blok i jasny punkt biegnący po jego górnej krawędzi. */
@Composable
fun BoxScope.BlockGloss() {
    val start = remember { (glossCounter++ % 7) * 0.28f }
    val hi = LocalTheme.current.sweep
    // smuga: wyższa niż blok i pochylona, żeby po obrocie zakrywała całą wysokość
    Box(
        Modifier.align(Alignment.CenterStart).fillMaxWidth(0.30f).fillMaxHeight()
            .graphicsLayer {
                val p = Glint.progress(start, 1.5f)
                if (p == null) { alpha = 0f; return@graphicsLayer }
                val e = FastOutSlowInEasing.transform(p)
                val full = size.width / 0.30f
                translationX = -size.width * 1.4f + e * (full + size.width * 2.6f)
                rotationZ = 16f
                scaleY = 1.7f
                alpha = (kotlin.math.sin(p * Math.PI).toFloat() * 1.6f).coerceAtMost(1f)
            }
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi.copy(alpha = 0.07f), hi.copy(alpha = 0.17f), hi.copy(alpha = 0.07f), Color.Transparent)))
            }
    )
    Box(
        Modifier.align(Alignment.TopStart).fillMaxWidth(0.34f).height(2.dp)
            .graphicsLayer {
                val p = Glint.progress(start, 1.5f)
                if (p == null) { alpha = 0f; return@graphicsLayer }
                val e = FastOutSlowInEasing.transform(p)
                translationX = -size.width + e * (size.width / 0.34f + size.width)
                alpha = 1f
            }
            .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hi, Color.White, hi, Color.Transparent))) }
    )
}

/** Pas światła przesuwający się po posadzce pod autem. */
@Composable
fun BoxScope.FloorGlint(top: androidx.compose.ui.unit.Dp) {
    val hi = LocalTheme.current.sweep
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(0.22f).fillMaxHeight().graphicsLayer {
            val p = Glint.progress(0.5f, 2.4f)
            if (p == null) { alpha = 0f; return@graphicsLayer }
            translationX = -size.width + p * (size.width / 0.22f + size.width)
            alpha = kotlin.math.sin(p * Math.PI).toFloat()
        }.drawBehind {
            val y = top.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, hi.copy(alpha = 0.16f), Color.Transparent)),
                topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, size.height - y),
            )
        }
    )
}

/** Iskra (czteroramienna gwiazdka) rozbłyskująca na chwilę w serii; [x], [y] – ułamki bloku. */
@Composable
fun BoxScope.Spark(x: Float, y: Float, size: androidx.compose.ui.unit.Dp, start: Float) {
    val hi = LocalTheme.current.sweep
    Box(
        Modifier.align(androidx.compose.ui.BiasAlignment(x * 2f - 1f, y * 2f - 1f)).size(size).graphicsLayer {
            val p = Glint.progress(start, 0.9f)
            if (p == null) { alpha = 0f; return@graphicsLayer }
            val k = kotlin.math.sin(p * Math.PI).toFloat()
            alpha = k
            scaleX = 0.3f + 0.7f * k
            scaleY = scaleX
            rotationZ = 90f * p
        }.drawBehind {
            val c = center
            val r = this.size.minDimension / 2f
            val w = r * 0.14f
            val star = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x, c.y - r); lineTo(c.x + w, c.y - w); lineTo(c.x + r, c.y); lineTo(c.x + w, c.y + w)
                lineTo(c.x, c.y + r); lineTo(c.x - w, c.y + w); lineTo(c.x - r, c.y); lineTo(c.x - w, c.y - w); close()
            }
            drawCircle(Brush.radialGradient(listOf(hi.copy(alpha = 0.5f), Color.Transparent), c, r * 0.7f), r * 0.7f, c)
            drawPath(star, Color.White)
        }
    )
}
