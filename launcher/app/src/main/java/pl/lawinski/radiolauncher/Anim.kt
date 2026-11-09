package pl.lawinski.radiolauncher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.State
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

/** Wartość 0..1 w pętli: tam i z powrotem (reverse) albo od nowa. */
@Composable
fun loop(durationMs: Int, reverse: Boolean = true, delayMs: Int = 0): State<Float> =
    rememberInfiniteTransition(label = "loop").animateFloat(
        0f, 1f,
        infiniteRepeatable(
            tween(durationMs, easing = if (reverse) FastOutSlowInEasing else LinearEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
            initialStartOffset = StartOffset(delayMs),
        ),
        label = "loop",
    )

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
