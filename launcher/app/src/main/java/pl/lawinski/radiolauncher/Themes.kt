package pl.lawinski.radiolauncher

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/*
 * Wygląd Kokpitu (Ustawienia → Wygląd).
 *
 * JAK DODAĆ NOWY WYGLĄD:
 *  1. Skopiuj jeden z obiektów niżej (np. BMW_IDRIVE), nadaj mu nowe `id`, `name` i `description`.
 *     `id` nie zmieniaj później – po nim Kokpit pamięta wybór.
 *  2. Zmień kolory. Kolor zapisuje się jako 0xAARRGGBB (AA = krycie, FF = pełne).
 *     `v(a, b, c)` to gradient pionowy od góry (a) do dołu (c), `solid(x)` to jeden kolor.
 *  3. Dopisz obiekt do listy `Themes.all`. Pojawi się w Ustawieniach z podglądem.
 * Kolory tekstu (Txt, Dim, Accent, Warn w Ui.kt) są wspólne dla wszystkich wyglądów.
 * Gradienty rysują się raz. Ruch (animated = true, Anim.kt) kosztuje radio trochę mocy.
 */
data class KokpitTheme(
    val id: String,
    val name: String,
    val description: String,
    /** tło całego ekranu */
    val screen: Brush,
    /** tło bloków (Skróty, Auto, Nawigacja…) i okien */
    val block: Brush,
    /** obwódka bloków */
    val blockEdge: Brush,
    /** kafelki skrótów, przyciski, wiersze ustawień */
    val surface: Brush,
    /** jasna krawędź kafelków i przycisków (null = bez) */
    val surfaceEdge: Brush? = null,
    /** element wybrany pokrętłem iDrive */
    val selected: Brush,
    val selectedRing: Color,
    /** wybrany element lekko się powiększa */
    val selectedGrow: Boolean = true,
    /** element włączony, np. przycisk „3D” */
    val active: Brush,
    val activeText: Color,
    /** tło pod autem w bloku Auto */
    val carBack: Brush,
    /** pasek paliwa (przy rezerwie zawsze pomarańczowy) */
    val fuel: Brush,
    /** cyfry prędkości (null = zwykły biały tekst) */
    val speed: Brush? = null,
    /** animacje (Anim.kt): płynąca poświata, światło na krawędziach, pulsujący wybór… */
    val animated: Boolean = false,
    /** kolor poświaty pod autem przy animacjach */
    val glow: Color = Color(0x384DA3FF),
    /** dwie płynące poświaty w tle ekranu (górna, dolna) */
    val aurora: Pair<Color, Color> = Color(0x524DA3FF) to Color(0x2E785AFF),
    /** światło przesuwające się po górnej krawędzi bloków */
    val sweep: Color = Color(0xE6A0D2FF),
    /** ścięte rogi bloków i przycisków (prawy górny, lewy dolny) zamiast zaokrąglonych */
    val cut: Boolean = false,
    /** dwie ukośne płaszczyzny światła w tle ekranu (rysowane raz, bez ruchu); null = bez */
    val planes: Pair<Color, Color>? = null,
) {
    /** Kształt bloku, okna albo przycisku o promieniu [r] w tym wyglądzie. */
    fun shape(r: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape =
        if (cut) androidx.compose.foundation.shape.CutCornerShape(topStart = 0f, topEnd = 0f, bottomEnd = 0f, bottomStart = 0f)
            .copy(topEnd = androidx.compose.foundation.shape.CornerSize(r * 0.8f), bottomStart = androidx.compose.foundation.shape.CornerSize(r * 0.8f))
        else androidx.compose.foundation.shape.RoundedCornerShape(r)
}

private fun v(vararg c: Long) = Brush.verticalGradient(c.map { Color(it) })
private fun h(vararg c: Long) = Brush.horizontalGradient(c.map { Color(it) })
private fun solid(c: Long) = SolidColor(Color(c))
/** gradient ukośny: od lewego górnego do prawego dolnego rogu */
private fun diag(vararg c: Pair<Float, Long>) = Brush.linearGradient(
    *c.map { it.first to Color(it.second) }.toTypedArray(),
    start = androidx.compose.ui.geometry.Offset.Zero, end = androidx.compose.ui.geometry.Offset.Infinite,
)

object Themes {
    /** Pierwszy wygląd 0.3.x: płaskie, ciemne kolory. */
    val FLAT = KokpitTheme(
        id = "flat",
        name = "Płaski (0.3.4)",
        description = "Ciemne, płaskie kolory – pierwotny wygląd Kokpitu",
        screen = solid(0xFF07090D),
        block = solid(0xFF11161E),
        blockEdge = solid(0x12FFFFFF),
        surface = solid(0xFF161D27),
        selected = solid(0x2E4DA3FF),
        selectedRing = Color(0xB34DA3FF),
        active = solid(0xFF4DA3FF),
        activeText = Color(0xFF031426),
        carBack = v(0xFF11161E, 0xFF0B0F15),
        fuel = solid(0xFF4DA3FF),
    )

    /** Gradienty jak w menu BMW NBT: grafit, chromowane krawędzie, niebieski BMW, w ruchu. */
    val BMW_IDRIVE = KokpitTheme(
        id = "bmw",
        name = "BMW iDrive",
        description = "Grafit i niebieski BMW w ruchu: płynąca poświata, światło na krawędziach, pulsujący wybór",
        screen = v(0xFF0F1A2C, 0xFF070B12, 0xFF030406),
        block = v(0xFF1F2A3A, 0xFF121923, 0xFF0A0E14),
        blockEdge = v(0x59BED2EB, 0x14788CAA, 0x2E7896BE),
        surface = v(0xFF252F3E, 0xFF181F2A, 0xFF121821),
        surfaceEdge = v(0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        selected = v(0x592C6FCF, 0x381A4F9D, 0x2212386F),
        selectedRing = Color(0xB38CC6FF),
        active = v(0xFF2A78E4, 0xFF1553AD),
        activeText = Color.White,
        carBack = v(0x00000000, 0x00000000, 0x2E1C69D4),
        fuel = h(0xFFFF8A3D, 0xFFFFB547, 0xFFFFFFFF),
        speed = v(0xFFFFFFFF, 0xFFFFFFFF, 0xFF9FB3CC),
        animated = true,
    )

    /** Sportowy: czerń z czerwienią i paskami w barwach M, w ruchu. */
    val M_SPORT = KokpitTheme(
        id = "msport",
        name = "M Sport",
        description = "Grafit i czerń z czerwienią w ruchu, pasek paliwa w barwach M",
        screen = v(0xFF16090B, 0xFF08080A, 0xFF030303),
        block = v(0xFF22252B, 0xFF15171B, 0xFF0C0D10),
        blockEdge = v(0x40FFFFFF, 0x0DFFFFFF, 0x26E0283C),
        surface = v(0xFF2A2D33, 0xFF1B1D22, 0xFF141519),
        surfaceEdge = v(0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        selected = v(0x59D8283A, 0x38A51525, 0x226E0C17),
        selectedRing = Color(0xB3FF7A86),
        glow = Color(0x33E0283C),
        active = v(0xFFD8283A, 0xFF8E1220),
        activeText = Color.White,
        carBack = v(0x00000000, 0x00000000, 0x26E0283C),
        fuel = h(0xFF5CC4F2, 0xFF1C3F94, 0xFFE0283C),
        speed = v(0xFFFFFFFF, 0xFFFFFFFF, 0xFFC9CDD4),
        animated = true,
        aurora = Color(0x4DE0283C) to Color(0x2E1C3F94),
        sweep = Color(0xE6FF8C96),
    )

    /** Jak w nowych BMW: ukośne płaszczyzny światła, ścięte rogi, przejścia niebieski → fiolet, w ruchu. */
    val IDRIVE8 = KokpitTheme(
        id = "idrive8",
        name = "iDrive 8",
        description = "Ścięte rogi, ukośne światło w tle, niebiesko-fioletowe przejścia w ruchu",
        screen = v(0xFF080A16, 0xFF05060D, 0xFF070A1C),
        block = diag(0f to 0xFF1D2650, 0.45f to 0xFF11162F, 1f to 0xFF090B18),
        blockEdge = diag(0f to 0xCC96AFFF, 0.35f to 0x2E7864FF, 0.7f to 0x0DFFFFFF, 1f to 0x80785AFF),
        surface = v(0x29FFFFFF, 0x0FFFFFFF),
        surfaceEdge = v(0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF),
        selected = diag(0f to 0x663D7BFF, 1f to 0x4D8A5CFF),
        selectedRing = Color(0xFFA9BCFF),
        active = h(0xFF3D7BFF, 0xFF8A5CFF),
        activeText = Color.White,
        carBack = v(0x00000000, 0x00000000, 0x4D3C6EFF),
        fuel = h(0xFF3D7BFF, 0xFF9A5CFF, 0xFFE08CFF),
        speed = v(0xFFFFFFFF, 0xFFFFFFFF, 0xFF8FB0FF),
        glow = Color(0x4D3C6EFF),
        animated = true,
        aurora = Color(0x4D3D7BFF) to Color(0x389650FF),
        sweep = Color(0xE6B4C4FF),
        cut = true,
        planes = Color(0x29466EFF) to Color(0x269650FF),
    )

    /** Wszystkie wyglądy do wyboru, w tej kolejności w Ustawieniach. */
    val all = listOf(FLAT, BMW_IDRIVE, M_SPORT, IDRIVE8)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
        ?: if (id == "anim") BMW_IDRIVE else FLAT // "anim" = dawny „Animowany” (0.3.5)
}

/** Bieżący wygląd; zmiana w Ustawieniach od razu przerysowuje ekran. */
val themeState by lazy { mutableStateOf(Themes.byId(Prefs.theme)) }

val LocalTheme = compositionLocalOf { Themes.FLAT }

/*
 * Własne zmiany wyglądu (Ustawienia → Wygląd → Dostosuj), osobno dla każdego wyglądu.
 * Puste pole = kolor albo pismo z wyglądu. Zapis: Prefs "custom_<id>" jako JSON.
 */
data class ThemeCustom(
    /** poświata w tle, światło na krawędziach i blask pod autem */
    val glow: Long? = null,
    /** tło całego ekranu (u góry; w dół ciemnieje) */
    val screen: Long? = null,
    /** zaznaczenie pokrętłem, włączone przyciski, ikony */
    val accent: Long? = null,
    /** krój pisma: klucz z [Fonts] */
    val font: String = "",
    /** rozmiar pisma, 1.0 = 100 % */
    val fontScale: Float = 1f,
) {
    val isDefault get() = this == ThemeCustom()

    fun toJson(): String = org.json.JSONObject().apply {
        glow?.let { put("glow", it) }
        screen?.let { put("screen", it) }
        accent?.let { put("accent", it) }
        if (font.isNotEmpty()) put("font", font)
        if (fontScale != 1f) put("fontScale", fontScale.toDouble())
    }.toString()

    companion object {
        fun fromJson(s: String?): ThemeCustom = try {
            val o = org.json.JSONObject(s ?: "{}")
            ThemeCustom(
                o.optLong("glow").takeIf { o.has("glow") },
                o.optLong("screen").takeIf { o.has("screen") },
                o.optLong("accent").takeIf { o.has("accent") },
                o.optString("font"),
                o.optDouble("fontScale", 1.0).toFloat(),
            )
        } catch (_: Exception) {
            ThemeCustom()
        }
    }
}

object ThemeCustoms {
    private val cache = HashMap<String, ThemeCustom>()

    /** Zmienia się przy każdym zapisie – ekran od razu się przerysowuje. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun get(id: String): ThemeCustom = cache.getOrPut(id) { ThemeCustom.fromJson(Prefs.themeCustom(id)) }

    fun set(id: String, c: ThemeCustom) {
        cache[id] = c
        Prefs.setThemeCustom(id, if (c.isDefault) null else c.toJson())
        version.intValue++
    }

    /** Bieżące zmiany wyglądu (odczyt w kompozycji – przerysowanie po zmianie). */
    fun current(): ThemeCustom {
        version.intValue
        return get(themeState.value.id)
    }
}

/** Kroje pisma dostępne w Androidzie bez pobierania. */
object Fonts {
    val all = listOf(
        "" to "Domyślny (Roboto)",
        "light" to "Roboto Light",
        "medium" to "Roboto Medium",
        "condensed" to "Roboto Condensed",
        "serif" to "Szeryfowy",
        "mono" to "Maszynowy",
        "casual" to "Odręczny",
    )

    fun family(key: String): androidx.compose.ui.text.font.FontFamily? = when (key) {
        "light" -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))
        "medium" -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))
        "condensed" -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
        "serif" -> androidx.compose.ui.text.font.FontFamily.Serif
        "mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
        "casual" -> androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("casual", android.graphics.Typeface.NORMAL))
        else -> null
    }
}

/** Kolory do wyboru w Dostosuj. */
object Swatches {
    val bright = listOf(
        0xFF1C69D4, 0xFF4DA3FF, 0xFF00C2FF, 0xFF00BFA5, 0xFF3DDC84, 0xFFA6E22E, 0xFFFFD54F, 0xFFFF8A3D,
        0xFFE0283C, 0xFFFF4D8D, 0xFF8E5CFF, 0xFF5B5BFF, 0xFFE8EEF5, 0xFF9FB3CC,
    )
    val dark = listOf(
        0xFF0F1A2C, 0xFF0A2342, 0xFF0B2A30, 0xFF0B2018, 0xFF1B0F2E, 0xFF2A0A12, 0xFF241A12, 0xFF1A1D22,
        0xFF101318, 0xFF050608, 0xFF16090B, 0xFF1F2A3A,
    )
}

private fun Color.dim(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Wygląd z nałożonymi zmianami użytkownika. */
fun KokpitTheme.customized(c: ThemeCustom): KokpitTheme {
    var t = this
    c.glow?.let { Color(it) }?.let { g ->
        t = t.copy(
            aurora = g.copy(alpha = 0.32f) to g.dim(0.8f).copy(alpha = 0.18f),
            sweep = Color(minOf(1f, g.red * 0.6f + 0.4f), minOf(1f, g.green * 0.6f + 0.4f), minOf(1f, g.blue * 0.6f + 0.4f), 0.9f),
            glow = g.copy(alpha = 0.22f),
            carBack = Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, g.copy(alpha = 0.18f))),
        )
    }
    c.screen?.let { Color(it) }?.let { b ->
        t = t.copy(screen = Brush.verticalGradient(listOf(b, b.dim(0.45f), Color(0xFF030406))))
    }
    c.accent?.let { Color(it) }?.let { a ->
        val light = a.red * 0.3f + a.green * 0.59f + a.blue * 0.11f > 0.7f
        t = t.copy(
            selected = Brush.verticalGradient(listOf(a.copy(alpha = 0.35f), a.copy(alpha = 0.22f), a.copy(alpha = 0.13f))),
            selectedRing = a.copy(alpha = 0.75f),
            active = Brush.verticalGradient(listOf(a, a.dim(0.7f))),
            activeText = if (light) Color(0xFF0B0E13) else Color.White,
        )
    }
    return t
}

/** Tryb jasny / ciemny – wspólny dla wszystkich wyglądów (Ustawienia → Wygląd). */
object LightMode {
    val state by lazy { androidx.compose.runtime.mutableStateOf(Prefs.lightMode) }
    val on: Boolean get() = state.value

    fun set(v: Boolean) {
        state.value = v
        Prefs.lightMode = v
    }
}

/**
 * Jasna wersja wyglądu: jasne tła bloków i kafelków, ciemne krawędzie; akcent, poświata
 * i paliwo zostają z wyglądu (albo z Dostosuj).
 */
fun KokpitTheme.light(): KokpitTheme = copy(
    screen = v(0xFFF4F7FB, 0xFFE3E9F1, 0xFFD5DDE8),
    block = v(0xFFFFFFFF, 0xFFF6F8FB, 0xFFEEF2F7),
    blockEdge = v(0x26000000, 0x0F000000, 0x1F000000),
    surface = v(0xFFF7F9FC, 0xFFEDF1F6, 0xFFE5EAF1),
    surfaceEdge = if (surfaceEdge != null) v(0xCCFFFFFF, 0x00FFFFFF, 0x00FFFFFF) else null,
    carBack = v(0x00000000, 0x00000000, 0x14000000),
    speed = v(0xFF111824, 0xFF111824, 0xFF4A5566),
    aurora = aurora.first.copy(alpha = aurora.first.alpha * 0.6f) to aurora.second.copy(alpha = aurora.second.alpha * 0.6f),
)

/** Wygląd na ekranie: wybrany w Ustawieniach + zmiany z Dostosuj + tryb jasny. */
fun effectiveTheme(): KokpitTheme {
    val c = ThemeCustoms.current()
    val base = themeState.value.let { if (LightMode.on) it.light() else it }
    // w trybie jasnym własny kolor tła jest pomijany (to kolory ciemne)
    return base.customized(if (LightMode.on) c.copy(screen = null) else c)
}

const val DEFAULT_ACCENT = 0xFF4DA3FF

/** Rozmiar pisma z Dostosuj (mnożnik w s()). */
val LocalFontScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }
