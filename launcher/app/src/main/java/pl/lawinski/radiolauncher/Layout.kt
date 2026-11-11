package pl.lawinski.radiolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Klocki ekranu głównego z najmniejszym rozmiarem w komórkach siatki. */
/** multi = klocek można dodać kilka razy (każdy egzemplarz ma własny numer [Slot.n]). */
enum class BlockId(val title: String, val minW: Int, val minH: Int, val multi: Boolean = false) {
    SHORTCUTS("Skróty", 1, 1),
    CAR("Auto", 2, 1),
    NAV("Nawigacja", 1, 1),
    CAMERA("Kamera", 1, 1),
    WEATHER("Pogoda", 1, 1),
    WIDGET("Widżet", 1, 1, multi = true),
    ASSISTANT("Asystent", 1, 1),
    CLIMATE("Klima", 1, 1),
    TUYA("Urządzenie Tuya", 1, 1, multi = true),
    MEDIA("Multimedia", 1, 1),
}

/** Położenie klocka na siatce 8 × 2: kolumna, rząd, szerokość, wysokość; n = numer egzemplarza. */
data class Slot(val id: BlockId, val c: Int, val r: Int, val w: Int, val h: Int, val n: Int = 0) {
    /** Unikalny klucz klocka, np. "NAV" albo "WIDGET#2" (zapisywany w układzie). */
    val key get() = if (n == 0) id.name else "${id.name}#$n"
    fun same(o: Slot) = id == o.id && n == o.n
    fun overlaps(o: Slot) = c < o.c + o.w && o.c < c + w && r < o.r + o.h && o.r < r + h
    val inBounds get() = c >= 0 && r >= 0 && w >= id.minW && h >= id.minH && c + w <= GRID_COLS && r + h <= GRID_ROWS
}

/** Liczba stron ekranu (po 8 kolumn), Ustawienia → Ekran. */
object GridPages {
    val count by lazy { androidx.compose.runtime.mutableIntStateOf(Prefs.gridPages.coerceIn(1, 3)) }
}

val GRID_COLS: Int get() = 8 * GridPages.count.intValue
const val GRID_ROWS = 2

object LayoutStore {
    val DEFAULT = listOf(
        Slot(BlockId.SHORTCUTS, 0, 0, 2, 2),
        Slot(BlockId.CAR, 2, 0, 3, 2),
        Slot(BlockId.NAV, 5, 0, 1, 2),
        Slot(BlockId.CAMERA, 6, 0, 2, 1),
        Slot(BlockId.WEATHER, 6, 1, 2, 1),
    )

    fun decode(s: String?): List<Slot> {
        if (s.isNullOrBlank()) return DEFAULT
        return try {
            s.split(';').filter { it.isNotBlank() }.map { part ->
                val (key, rest) = part.split(':')
                val (c, r, w, h) = rest.split(',').map { it.toInt() }
                val id = BlockId.valueOf(key.substringBefore('#'))
                Slot(id, c, r, w, h, key.substringAfter('#', "0").toInt())
            }.filter { it.inBounds }.distinctBy { it.key }
        } catch (e: Exception) {
            DEFAULT
        }
    }

    fun encode(slots: List<Slot>) = slots.joinToString(";") { "${it.key}:${it.c},${it.r},${it.w},${it.h}" }

    fun fits(slots: List<Slot>, s: Slot) = s.inBounds && slots.none { !it.same(s) && it.overlaps(s) }

    private fun area(a: Slot, b: Slot): Int {
        val w = minOf(a.c + a.w, b.c + b.w) - maxOf(a.c, b.c)
        val h = minOf(a.r + a.h, b.r + b.h) - maxOf(a.r, b.r)
        return if (w > 0 && h > 0) w * h else 0
    }

    private fun valid(slots: List<Slot>) =
        slots.all { it.inBounds } && slots.indices.all { i -> (i + 1 until slots.size).none { j -> slots[i].overlaps(slots[j]) } }

    private fun replace(slots: List<Slot>, vararg n: Slot) = slots.map { s -> n.firstOrNull { it.same(s) } ?: s }

    /**
     * Przesunięcie klocka. Na wolne miejsce wprost; upuszczony na inny klocek zamienia się z nim
     * miejscami: najpierw z zachowaniem rozmiarów, a gdy się nie mieszczą – zamieniają się prostokątami.
     */
    fun move(slots: List<Slot>, from: Slot, to: Slot, finger: Pair<Int, Int>? = null): List<Slot> {
        val cur = slots.firstOrNull { it.same(from) } ?: return slots
        val t = to.copy(c = to.c.coerceIn(0, GRID_COLS - cur.w), r = to.r.coerceIn(0, GRID_ROWS - cur.h))
        if (fits(slots, t)) return replace(slots, t)
        val others = slots.filter { !it.same(cur) }
        // klocek pod palcem; gdy palec jest na pustym polu – ten, na który najbardziej nachodzi
        val other = finger?.let { (fc, fr) -> others.firstOrNull { fc in it.c until it.c + it.w && fr in it.r until it.r + it.h } }
            ?: others.maxByOrNull { area(it, t) }?.takeIf { area(it, t) > 0 } ?: return slots
        val tries = buildList {
            // sąsiedzi w tym samym pasie: zamiana kolejności, każdy zachowuje swój rozmiar
            if (cur.r == other.r && cur.h == other.h && (cur.c + cur.w == other.c || other.c + other.w == cur.c)) {
                val left = minOf(cur.c, other.c)
                add(if (cur.c < other.c) cur.copy(c = left + other.w) to other.copy(c = left)
                    else cur.copy(c = left) to other.copy(c = left + cur.w))
            }
            if (cur.c == other.c && cur.w == other.w && (cur.r + cur.h == other.r || other.r + other.h == cur.r)) {
                val top = minOf(cur.r, other.r)
                add(if (cur.r < other.r) cur.copy(r = top + other.h) to other.copy(r = top)
                    else cur.copy(r = top) to other.copy(r = top + cur.h))
            }
            // ten sam rozmiar: każdy w miejsce drugiego
            add(cur.copy(c = other.c, r = other.r) to other.copy(c = cur.c, r = cur.r))
            // zamiana prostokątów
            add(cur.copy(c = other.c, r = other.r, w = other.w, h = other.h) to other.copy(c = cur.c, r = cur.r, w = cur.w, h = cur.h))
        }
        for ((a, b) in tries) {
            val n = replace(slots, a, b)
            if (valid(n)) return n
        }
        return slots
    }

    /** Zmiana rozmiaru. Gdy powiększony klocek wchodzi na sąsiada, sąsiad się zwęża (jeśli może). */
    fun resize(slots: List<Slot>, s: Slot, w: Int, h: Int): List<Slot> {
        val cur = slots.firstOrNull { it.same(s) } ?: return slots
        val n = cur.copy(w = w.coerceIn(cur.id.minW, GRID_COLS - cur.c), h = h.coerceIn(cur.id.minH, GRID_ROWS - cur.r))
        if (n == cur) return slots
        val out = slots.map { o ->
            if (o.same(n)) return@map n
            if (!o.overlaps(n)) return@map o
            when {
                // sąsiad po prawej: przesuwa się w prawo i zwęża
                o.c >= cur.c + cur.w -> { val d = n.c + n.w - o.c; o.copy(c = o.c + d, w = o.w - d) }
                // sąsiad poniżej: przesuwa się w dół i obniża
                o.r >= cur.r + cur.h -> { val d = n.r + n.h - o.r; o.copy(r = o.r + d, h = o.h - d) }
                else -> o
            }
        }
        return if (valid(out)) out else slots
    }

    /** Pierwszy wolny numer egzemplarza klocka (dla klocków [BlockId.multi]). */
    fun nextN(slots: List<Slot>, id: BlockId) = generateSequence(0) { it + 1 }.first { n -> slots.none { it.id == id && it.n == n } }

    /** Pierwsze wolne miejsce na ukryty (albo kolejny) klocek, w najmniejszym rozmiarze. */
    fun place(slots: List<Slot>, id: BlockId): List<Slot>? {
        val n = nextN(slots, id)
        for (r in 0 until GRID_ROWS) for (c in 0 until GRID_COLS) {
            val s = Slot(id, c, r, id.minW, id.minH, n)
            if (fits(slots, s)) return slots + s
        }
        // siatka pełna: zwęź szeroki klocek o jedną kolumnę z prawej i wstaw nowy w zwolnione miejsce
        // (od najszerszego; pierwszy, przy którym nowy klocek się mieści)
        for (wide in slots.filter { it.w > it.id.minW }.sortedByDescending { it.w }) {
            val narrowed = wide.copy(w = wide.w - 1)
            val s = Slot(id, wide.c + wide.w - 1, wide.r, id.minW, wide.h.coerceAtLeast(id.minH), n)
            val next = slots.map { if (it.same(wide)) narrowed else it } + s
            if (s.inBounds && next.indices.all { i -> (i + 1 until next.size).none { j -> next[i].overlaps(next[j]) } }) return next
        }
        return null
    }
}

/**
 * Siatka klocków. W trybie edycji klocek przesuwa się przeciągnięciem, a rozmiar zmienia
 * uchwytem w prawym dolnym rogu; po puszczeniu wszystko przyciąga się do komórek.
 */
@Composable
fun BlockGrid(
    slots: List<Slot>,
    edit: Boolean,
    gap: Dp,
    onChange: (List<Slot>) -> Unit,
    /** dodatkowe przyciski klocka w trybie edycji (np. „zmień widżet”) */
    editExtra: @Composable androidx.compose.foundation.layout.BoxScope.(Slot) -> Unit = {},
    content: @Composable (Slot) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // kilka stron ekranu: zawsze 8 kolumn na szerokość ekranu, reszta przewijana w bok
        // (w edycji też – przewija się palcem po pustych polach, klocki przeciąga się jak zwykle)
        val pages = GridPages.count.intValue
        val scrolling = pages > 1
        val scroll = androidx.compose.foundation.rememberScrollState()
        val pageW = maxWidth
        val cellW = if (scrolling) (pageW - gap * 7) / 8 else (maxWidth - gap * (GRID_COLS - 1)) / GRID_COLS
        val innerW = if (scrolling) pageW * pages + gap * (pages - 1) else maxWidth
        // przewijanie po kawałku: po puszczeniu przyciąga do najbliższej kolumny, nie do całej strony
        val colStep = with(density) { (cellW + gap).toPx() }
        androidx.compose.runtime.LaunchedEffect(scroll.isScrollInProgress, scrolling, edit) {
            if (scrolling && !edit && !scroll.isScrollInProgress) {
                val target = (kotlin.math.round(scroll.value / colStep) * colStep).toInt().coerceIn(0, scroll.maxValue)
                if (target != scroll.value) scroll.animateScrollTo(target)
            }
        }
        // nowy klocek (np. dodany w edycji na innej stronie) – przewiń, żeby był widoczny
        val known = remember { mutableStateOf(slots.map { it.key }.toSet()) }
        androidx.compose.runtime.LaunchedEffect(slots) {
            val added = slots.filter { it.key !in known.value }
            known.value = slots.map { it.key }.toSet()
            val s = added.firstOrNull() ?: return@LaunchedEffect
            if (scrolling) {
                val x = (s.c * colStep).toInt()
                if (x < scroll.value || x + s.w * colStep > scroll.value + with(density) { pageW.toPx() })
                    scroll.animateScrollTo((x - colStep.toInt() * (8 - s.w).coerceAtLeast(0) / 2).coerceIn(0, scroll.maxValue))
            }
        }
        val cellH = (maxHeight - gap * (GRID_ROWS - 1)) / GRID_ROWS
        Box(Modifier.fillMaxSize().then(if (scrolling) Modifier.horizontalScroll(scroll) else Modifier)) {
        Box(Modifier.width(innerW).fillMaxHeight()) {
        val stepX = with(density) { (cellW + gap).toPx() }
        val viewW = with(density) { pageW.toPx() }
        val stepY = with(density) { (cellH + gap).toPx() }

        if (edit) {
            for (r in 0 until GRID_ROWS) for (c in 0 until GRID_COLS) {
                Box(
                    Modifier.offset(x = (cellW + gap) * c, y = (cellH + gap) * r).size(cellW, cellH)
                        .clip(RoundedCornerShape(d(28))).background(Color(0x0A4DA3FF))
                )
            }
        }

        val cur by rememberUpdatedState(slots)
        val change by rememberUpdatedState(onChange)
        var preview by remember { mutableStateOf<Slot?>(null) }

        // podgląd miejsca, w które trafi przeciągany albo powiększany klocek
        preview?.let { p ->
            Box(
                Modifier.offset(x = (cellW + gap) * p.c, y = (cellH + gap) * p.r)
                    .size((cellW + gap) * p.w - gap, (cellH + gap) * p.h - gap)
                    .clip(RoundedCornerShape(d(28))).background(Color(0x334DA3FF))
            )
        }

        for (slot in slots) {
            androidx.compose.runtime.key(slot.key) {
                var drag by remember { mutableStateOf(Offset.Zero) }
                var start by remember { mutableStateOf(Offset.Zero) }
                var grow by remember { mutableStateOf(Offset.Zero) }
                val active = drag != Offset.Zero || grow != Offset.Zero
                val w = (cellW + gap) * slot.w - gap
                val h = (cellH + gap) * slot.h - gap
                val growW = with(density) { grow.x.toDp() }
                val growH = with(density) { grow.y.toDp() }
                Box(
                    Modifier
                        .zIndex(if (active) 1f else 0f)
                        .offset(x = (cellW + gap) * slot.c, y = (cellH + gap) * slot.r)
                        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                        .size((w + growW).coerceAtLeast(cellW), (h + growH).coerceAtLeast(cellH))
                ) {
                    content(slot)
                    if (edit) {
                        val accent = Accent
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color(0x55000000), RoundedCornerShape(d(28)))
                                .drawBehind {
                                    drawRoundRect(
                                        accent, cornerRadius = CornerRadius(28f, 28f),
                                        style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(24f, 14f))),
                                    )
                                }
                                .pointerInput(slot.key) {
                                    fun target(): Slot {
                                        val s0 = cur.first { it.same(slot) }
                                        return s0.copy(
                                            c = (s0.c + (drag.x / stepX).roundToInt()).coerceIn(0, GRID_COLS - s0.w),
                                            r = (s0.r + (drag.y / stepY).roundToInt()).coerceIn(0, GRID_ROWS - s0.h),
                                        )
                                    }
                                    detectDragGestures(
                                        onDragStart = { start = it },
                                        onDragEnd = {
                                            val s0 = cur.first { it.same(slot) }
                                            val to = target()
                                            val p = start + drag
                                            val finger = (s0.c + kotlin.math.floor(p.x / stepX).toInt()) to
                                                (s0.r + kotlin.math.floor(p.y / stepY).toInt())
                                            drag = Offset.Zero
                                            preview = null
                                            if (to != s0) change(LayoutStore.move(cur, s0, to, finger))
                                        },
                                        onDragCancel = { drag = Offset.Zero; preview = null },
                                    ) { ch, amount ->
                                        ch.consume()
                                        drag += amount
                                        // klocek przy krawędzi ekranu: przewiń siatkę i przesuń klocek razem z nią
                                        if (scrolling) {
                                            val s0 = cur.first { it.same(slot) }
                                            val fingerX = s0.c * stepX + start.x + drag.x - scroll.value
                                            val edge = viewW * 0.08f
                                            val push = when {
                                                fingerX > viewW - edge -> 24f
                                                fingerX < edge -> -24f
                                                else -> 0f
                                            }
                                            if (push != 0f) {
                                                val moved = scroll.dispatchRawDelta(push)
                                                drag += Offset(moved, 0f)
                                            }
                                        }
                                        preview = target()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(slot.id.title, color = Txt, fontSize = s(30), fontWeight = FontWeight.Medium)
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(d(12)).size(d(56))
                                .clip(RoundedCornerShape(d(16))).background(Color(0xFFFF6B6B))
                                .pointerInput(slot.key) {
                                    detectDragGestures { c, _ -> c.consume() }
                                }
                                .then(Modifier.clickableNoRipple { change(cur.filter { !it.same(slot) }) }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Close, "Ukryj", tint = Color.White, modifier = Modifier.size(d(32))) }
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(d(12)).size(d(64))
                                .clip(RoundedCornerShape(d(18))).background(accent)
                                .pointerInput(slot.key) {
                                    fun target(): Slot {
                                        val s0 = cur.first { it.same(slot) }
                                        return s0.copy(
                                            w = (s0.w + (grow.x / stepX).roundToInt()).coerceIn(s0.id.minW, GRID_COLS - s0.c),
                                            h = (s0.h + (grow.y / stepY).roundToInt()).coerceIn(s0.id.minH, GRID_ROWS - s0.r),
                                        )
                                    }
                                    detectDragGestures(
                                        onDragEnd = {
                                            val s0 = cur.first { it.same(slot) }
                                            val t = target()
                                            grow = Offset.Zero
                                            preview = null
                                            if (t != s0) change(LayoutStore.resize(cur, s0, t.w, t.h))
                                        },
                                        onDragCancel = { grow = Offset.Zero; preview = null },
                                    ) { ch, amount ->
                                        ch.consume()
                                        grow += amount
                                        preview = target()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.OpenInFull, "Zmień rozmiar", tint = Color(0xFF031426), modifier = Modifier.size(d(34))) }
                        editExtra(slot)
                    }
                }
            }
        }
        }
        }
        if (scrolling) {
            // pasek przewijania na dole: jaka część całej siatki jest widoczna i gdzie
            val trackW = d(240)
            val thumbW = trackW / pages
            val frac = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
            Box(Modifier.align(Alignment.BottomCenter).offset(y = gap * 0.9f).size(trackW, d(8)).clip(RoundedCornerShape(d(4))).background(Dim.copy(alpha = 0.3f))) {
                Box(Modifier.offset(x = (trackW - thumbW) * frac).size(thumbW, d(8)).clip(RoundedCornerShape(d(4))).background(Txt.copy(alpha = 0.8f)))
            }
        }
    }
}

/** Dotknięcie bez efektu falowania (w trybie edycji tło i tak jest przyciemnione). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.pointerInput(onClick) {
    detectTapGestures(onTap = { onClick() })
}
