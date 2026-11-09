package pl.lawinski.radiolauncher

import androidx.compose.foundation.background
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
enum class BlockId(val title: String, val minW: Int, val minH: Int) {
    SHORTCUTS("Skróty", 1, 1),
    CAR("Auto", 2, 1),
    NAV("Nawigacja", 1, 1),
    CAMERA("Kamera", 1, 1),
    WEATHER("Pogoda", 1, 1),
    WIDGET("Widżet", 1, 1),
}

/** Położenie klocka na siatce 8 × 2: kolumna, rząd, szerokość, wysokość. */
data class Slot(val id: BlockId, val c: Int, val r: Int, val w: Int, val h: Int) {
    fun overlaps(o: Slot) = c < o.c + o.w && o.c < c + w && r < o.r + o.h && o.r < r + h
    val inBounds get() = c >= 0 && r >= 0 && w >= id.minW && h >= id.minH && c + w <= GRID_COLS && r + h <= GRID_ROWS
}

const val GRID_COLS = 8
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
                val (id, rest) = part.split(':')
                val (c, r, w, h) = rest.split(',').map { it.toInt() }
                Slot(BlockId.valueOf(id), c, r, w, h)
            }.filter { it.inBounds }.distinctBy { it.id }
        } catch (e: Exception) {
            DEFAULT
        }
    }

    fun encode(slots: List<Slot>) = slots.joinToString(";") { "${it.id.name}:${it.c},${it.r},${it.w},${it.h}" }

    fun fits(slots: List<Slot>, s: Slot) = s.inBounds && slots.none { it.id != s.id && it.overlaps(s) }

    private fun area(a: Slot, b: Slot): Int {
        val w = minOf(a.c + a.w, b.c + b.w) - maxOf(a.c, b.c)
        val h = minOf(a.r + a.h, b.r + b.h) - maxOf(a.r, b.r)
        return if (w > 0 && h > 0) w * h else 0
    }

    private fun valid(slots: List<Slot>) =
        slots.all { it.inBounds } && slots.indices.all { i -> (i + 1 until slots.size).none { j -> slots[i].overlaps(slots[j]) } }

    private fun replace(slots: List<Slot>, vararg n: Slot) = slots.map { s -> n.firstOrNull { it.id == s.id } ?: s }

    /**
     * Przesunięcie klocka. Na wolne miejsce wprost; upuszczony na inny klocek zamienia się z nim
     * miejscami: najpierw z zachowaniem rozmiarów, a gdy się nie mieszczą – zamieniają się prostokątami.
     */
    fun move(slots: List<Slot>, from: Slot, to: Slot, finger: Pair<Int, Int>? = null): List<Slot> {
        val cur = slots.firstOrNull { it.id == from.id } ?: return slots
        val t = to.copy(c = to.c.coerceIn(0, GRID_COLS - cur.w), r = to.r.coerceIn(0, GRID_ROWS - cur.h))
        if (fits(slots, t)) return replace(slots, t)
        val others = slots.filter { it.id != cur.id }
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
        val cur = slots.firstOrNull { it.id == s.id } ?: return slots
        val n = cur.copy(w = w.coerceIn(cur.id.minW, GRID_COLS - cur.c), h = h.coerceIn(cur.id.minH, GRID_ROWS - cur.r))
        if (n == cur) return slots
        val out = slots.map { o ->
            if (o.id == n.id) return@map n
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

    /** Pierwsze wolne miejsce na ukryty klocek, w najmniejszym rozmiarze. */
    fun place(slots: List<Slot>, id: BlockId): List<Slot>? {
        for (r in 0 until GRID_ROWS) for (c in 0 until GRID_COLS) {
            val s = Slot(id, c, r, id.minW, id.minH)
            if (fits(slots, s)) return slots + s
        }
        // siatka pełna: zwęź najszerszy klocek o jedną kolumnę z prawej i wstaw nowy w zwolnione miejsce
        val wide = slots.filter { it.w > it.id.minW }.maxByOrNull { it.w } ?: return null
        val narrowed = wide.copy(w = wide.w - 1)
        val s = Slot(id, wide.c + wide.w - 1, wide.r, 1, wide.h)
        val next = slots.map { if (it == wide) narrowed else it } + s
        return if (s.inBounds && next.none { a -> next.any { b -> a !== b && a.overlaps(b) } }) next else null
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
    content: @Composable (Slot) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cellW = (maxWidth - gap * (GRID_COLS - 1)) / GRID_COLS
        val cellH = (maxHeight - gap * (GRID_ROWS - 1)) / GRID_ROWS
        val stepX = with(density) { (cellW + gap).toPx() }
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
            androidx.compose.runtime.key(slot.id) {
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
                                .pointerInput(slot.id) {
                                    fun target(): Slot {
                                        val s0 = cur.first { it.id == slot.id }
                                        return s0.copy(
                                            c = (s0.c + (drag.x / stepX).roundToInt()).coerceIn(0, GRID_COLS - s0.w),
                                            r = (s0.r + (drag.y / stepY).roundToInt()).coerceIn(0, GRID_ROWS - s0.h),
                                        )
                                    }
                                    detectDragGestures(
                                        onDragStart = { start = it },
                                        onDragEnd = {
                                            val s0 = cur.first { it.id == slot.id }
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
                                .pointerInput(slot.id) {
                                    detectDragGestures { c, _ -> c.consume() }
                                }
                                .then(Modifier.clickableNoRipple { change(cur.filter { it.id != slot.id }) }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.Close, "Ukryj", tint = Color.White, modifier = Modifier.size(d(32))) }
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(d(12)).size(d(64))
                                .clip(RoundedCornerShape(d(18))).background(accent)
                                .pointerInput(slot.id) {
                                    fun target(): Slot {
                                        val s0 = cur.first { it.id == slot.id }
                                        return s0.copy(
                                            w = (s0.w + (grow.x / stepX).roundToInt()).coerceIn(s0.id.minW, GRID_COLS - s0.c),
                                            h = (s0.h + (grow.y / stepY).roundToInt()).coerceIn(s0.id.minH, GRID_ROWS - s0.r),
                                        )
                                    }
                                    detectDragGestures(
                                        onDragEnd = {
                                            val s0 = cur.first { it.id == slot.id }
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
                    }
                }
            }
        }
    }
}

/** Dotknięcie bez efektu falowania (w trybie edycji tło i tak jest przyciemnione). */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.pointerInput(onClick) {
    detectTapGestures(onTap = { onClick() })
}
