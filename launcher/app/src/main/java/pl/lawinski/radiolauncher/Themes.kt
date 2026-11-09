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
)

private fun v(vararg c: Long) = Brush.verticalGradient(c.map { Color(it) })
private fun h(vararg c: Long) = Brush.horizontalGradient(c.map { Color(it) })
private fun solid(c: Long) = SolidColor(Color(c))

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

    /** Wszystkie wyglądy do wyboru, w tej kolejności w Ustawieniach. */
    val all = listOf(FLAT, BMW_IDRIVE, M_SPORT)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
        ?: if (id == "anim") BMW_IDRIVE else FLAT // "anim" = dawny „Animowany” (0.3.5)
}

/** Bieżący wygląd; zmiana w Ustawieniach od razu przerysowuje ekran. */
val themeState by lazy { mutableStateOf(Themes.byId(Prefs.theme)) }

val LocalTheme = compositionLocalOf { Themes.FLAT }
