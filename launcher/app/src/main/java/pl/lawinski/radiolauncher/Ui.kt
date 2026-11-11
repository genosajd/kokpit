@file:OptIn(ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.focusable
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// kolory zależne od trybu jasny/ciemny (LightMode); odczyt w kompozycji = przerysowanie po zmianie
val Bg: Color get() = if (LightMode.on) Color(0xFFE9EEF4) else Color(0xFF07090D)
val CardBg: Color get() = if (LightMode.on) Color(0xFFFFFFFF) else Color(0xFF11161E)
val CardHi: Color get() = if (LightMode.on) Color(0xFFEDF1F6) else Color(0xFF161D27)
val Line: Color get() = if (LightMode.on) Color(0x1A000000) else Color(0x12FFFFFF)
val Txt: Color get() = if (LightMode.on) Color(0xFF111824) else Color(0xFFEEF2F7)
val Dim: Color get() = if (LightMode.on) Color(0xFF5A6678) else Color(0xFF8A96A8)
/** Kolor akcentu: z Dostosuj, inaczej niebieski. Odczyt w kompozycji = przerysowanie po zmianie. */
val Accent: Color get() = Color(ThemeCustoms.current().accent ?: DEFAULT_ACCENT)
val Warn = Color(0xFFFFB547)

/** Jednostka projektu: ekran zaprojektowany jest na 2560×720, a ta wartość przelicza go na dp tego ekranu. */
val LocalU = staticCompositionLocalOf { 1.dp }

@Composable
fun d(n: Number): Dp = LocalU.current * n.toFloat()

@Composable
fun s(n: Number): TextUnit = with(LocalDensity.current) { (LocalU.current * n.toFloat() * LocalFontScale.current).toSp() }

/** Okna otwierane nad ekranem głównym. */
sealed interface Dlg {
    data object Settings : Dlg
    data class Picker(val title: String, val drawer: Boolean = false, val onPick: (String) -> Unit) : Dlg
    data class Menu(val index: Int) : Dlg
    data class Address(val work: Boolean) : Dlg
    data object Place3 : Dlg
    data class Ai(val field: AiField) : Dlg
    data object Voices : Dlg
    data class Custom(val field: CustomField) : Dlg
    data object Instructions : Dlg
    data object Cameras : Dlg
    /** wybór widżetu do klocka „Widżet” nr n */
    data class Widgets(val n: Int) : Dlg
    /** wybór urządzenia do klocka „Urządzenie Tuya” nr n */
    data class TuyaDevices(val n: Int) : Dlg
}

/** Edycja układu tylko na postoju: powyżej tej prędkości z GPS przycisk jest nieaktywny. */
const val EDIT_MAX_KMH = 5

@Composable
fun LauncherScreen(act: MainActivity) {
    val apps by act.apps
    val slots by act.layout
    var shortcuts by remember { mutableStateOf(Prefs.shortcuts) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    var edit by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(false) }
    val speed by act.location.speedKmh.collectAsState()

    LaunchedEffect(apps) { shortcuts = Prefs.shortcuts }
    LaunchedEffect(act.homePressed.intValue) {
        dialog = null
        edit = false
    }
    LaunchedEffect(speed) { if ((speed ?: 0) > EDIT_MAX_KMH) edit = false }
    // pokaz danych auta: paliwo i kolejno otwierane drzwi; po wyłączeniu wracają dane z CAN
    LaunchedEffect(demo) {
        CanSource.demo = demo
        if (!demo) {
            CarData.status.value = CanSource.last
            return@LaunchedEffect
        }
        val steps = listOf(emptySet(), setOf(Door.FL), setOf(Door.FL, Door.RR), setOf(Door.TRUNK), setOf(Door.HOOD, Door.FR), emptySet<Door>())
        while (true) for (st in steps) {
            CarData.status.value = CarStatus(fuelL = 48f, rangeKm = 610, open = st)
            delay(3000)
        }
    }

    fun saveShortcuts(list: List<String>) {
        Prefs.shortcuts = list
        shortcuts = list
    }

    val theme = effectiveTheme()
    val custom = ThemeCustoms.current()
    val animate = theme.animated && act.visible.value && !edit
    MaterialTheme(colorScheme = if (LightMode.on) androidx.compose.material3.lightColorScheme(primary = Accent, surface = CardBg, background = Bg)
        else darkColorScheme(primary = Accent, surface = CardBg, background = Bg)) {
      CompositionLocalProvider(
          LocalTheme provides theme, LocalAnimate provides animate, LocalFontScale provides custom.fontScale,
          androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(fontFamily = Fonts.family(custom.font)),
      ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(theme.screen)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
        ) {
            val u = minOf(maxWidth / 2560f, maxHeight / 720f)
            if (animate) Aurora()
            CompositionLocalProvider(LocalU provides u) {
                Column(Modifier.fillMaxSize().padding(horizontal = d(28))) {
                    if (edit) {
                        EditBar(slots,
                            onAdd = { id ->
                                val next = LayoutStore.place(slots, id)
                                if (next != null) act.saveLayout(next)
                                else android.widget.Toast.makeText(act, "Brak miejsca – zmniejsz albo schowaj klocek, albo dodaj stronę ekranu (Ustawienia → Ekran)", android.widget.Toast.LENGTH_LONG).show()
                            },
                            onReset = { act.saveLayout(LayoutStore.DEFAULT) },
                            onStore = {
                                act.storeLayout()
                                android.widget.Toast.makeText(act, "Zapisano układ – „Przywróć zapisany” wróci do niego", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onRestore = if (act.hasSavedLayout.value) ({ act.restoreLayout() }) else null,
                            onDone = { edit = false })
                    } else {
                        StatusBar(
                            onSettings = { dialog = Dlg.Settings },
                            onEdit = { if ((speed ?: 0) <= EDIT_MAX_KMH) edit = true },
                            editAllowed = (speed ?: 0) <= EDIT_MAX_KMH,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = d(8), bottom = d(28))) {
                        BlockGrid(slots, edit, d(20), onChange = { act.saveLayout(it) },
                            editExtra = { slot ->
                                if (slot.id == BlockId.WIDGET) WidgetChangeButton { dialog = Dlg.Widgets(slot.n) }
                                if (slot.id == BlockId.TUYA) WidgetChangeButton { dialog = Dlg.TuyaDevices(slot.n) }
                            }) { slot ->
                            val m = Modifier.fillMaxSize()
                            when (slot.id) {
                                BlockId.SHORTCUTS -> ShortcutsBlock(m, slot, apps, shortcuts,
                                    onAdd = { dialog = Dlg.Picker("Dodaj skrót") { key -> saveShortcuts(shortcuts + key) } },
                                    onMenu = { dialog = Dlg.Menu(it) },
                                    onMove = { from, to ->
                                        val l = shortcuts.toMutableList()
                                        l.add(to, l.removeAt(from))
                                        saveShortcuts(l)
                                    },
                                    onDrawer = { dialog = Dlg.Picker("Aplikacje", drawer = true) { Apps.launch(act, it) } })
                                BlockId.CAR -> CarBlock(m, act, apps, edit, onSplitSettings = { dialog = Dlg.Settings })
                                BlockId.NAV -> NavBlock(m, slot, act, onAddress = { dialog = Dlg.Address(it) }, onPlace3 = { dialog = Dlg.Place3 })
                                BlockId.CAMERA -> CameraBlock(m, act, onChoose = { dialog = Dlg.Cameras })
                                BlockId.WEATHER -> WeatherBlock(m, slot, act)
                                BlockId.CLIMATE -> ClimateBlock(m, slot, act, onSettings = { Prefs.settingsGroup = 8; dialog = Dlg.Settings })
                                BlockId.TUYA -> TuyaDeviceBlock(m, slot, act, onChoose = { dialog = Dlg.TuyaDevices(slot.n) }, onSettings = { Prefs.settingsGroup = 8; dialog = Dlg.Settings })
                                BlockId.ASSISTANT -> AssistantBlock(m, slot, act, onSettings = { Prefs.settingsGroup = 7; dialog = Dlg.Settings })
                                BlockId.WIDGET -> WidgetBlock(m, act, slot.n, onChoose = { dialog = Dlg.Widgets(slot.n) })
                            }
                        }
                    }
                }

                when (val dl = dialog) {
                    null -> {}
                    is Dlg.Settings -> SettingsDialog(act, apps, demo,
                        onClose = { dialog = null },
                        onPicker = { dialog = it },
                        onAddress = { dialog = Dlg.Address(it) },
                        onPlace3 = { dialog = Dlg.Place3 },
                        onAiField = { dialog = Dlg.Ai(it) },
                        onVoices = { dialog = Dlg.Voices },
                        onCustom = { dialog = Dlg.Custom(it) },
                        onInstructions = { dialog = Dlg.Instructions },
                        onCameras = { dialog = Dlg.Cameras },
                        onEditLayout = { dialog = null; edit = true },
                        onDemo = { demo = it },
                        onResetShortcuts = { saveShortcuts(Apps.defaultShortcuts(apps)) })
                    is Dlg.Picker -> AppPickerDialog(dl.title, apps, onClose = { dialog = null }) { key ->
                        dialog = null
                        dl.onPick(key)
                    }
                    is Dlg.Menu -> ShortcutMenuDialog(
                        onClose = { dialog = null },
                        onReplace = {
                            dialog = Dlg.Picker("Zamień skrót") { key ->
                                saveShortcuts(shortcuts.toMutableList().also { it[dl.index] = key })
                            }
                        },
                        onRemove = {
                            saveShortcuts(shortcuts.toMutableList().also { it.removeAt(dl.index) })
                            dialog = null
                        },
                    )
                    is Dlg.Address -> AddressDialog(dl.work, onClose = { dialog = null })
                    is Dlg.Place3 -> Place3Dialog(onClose = { dialog = null })
                    is Dlg.Ai -> AiFieldDialog(dl.field, onClose = { dialog = Dlg.Settings })
                    is Dlg.Voices -> VoicePickerDialog(act, onClose = { dialog = Dlg.Settings })
                    is Dlg.Custom -> CustomizeDialog(dl.field, onClose = { dialog = Dlg.Settings })
                    is Dlg.Instructions -> InstructionsDialog(onClose = { dialog = Dlg.Settings })
                    is Dlg.Cameras -> CameraPickerDialog(act, onClose = { dialog = null })
                    is Dlg.TuyaDevices -> TuyaDevicePickerDialog(act, dl.n, onClose = { dialog = null })
                    is Dlg.Widgets -> WidgetPickerDialog(act, onClose = { dialog = null }) { provider ->
                        dialog = null
                        act.addBlockWidget(dl.n, provider)
                    }
                }
            }
        }
      }
    }
}

/** Radio Witstek (YA32): jego pasek stanu nie daje się ukryć i zasłania górę ekranu. */
val isWitstek: Boolean by lazy {
    runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.wits.machine.id") as String }
        .getOrNull().orEmpty().isNotBlank()
}

@Composable
fun Block(modifier: Modifier, content: @Composable () -> Unit) {
    val animate = LocalAnimate.current
    // wygląd animowany: bloki wjeżdżają od dołu przy każdym powrocie na ekran główny
    val appear = remember { androidx.compose.animation.core.Animatable(1f) }
    val delay = remember { (blockEntrance++ % 6) * 80 }
    LaunchedEffect(animate) {
        if (animate) {
            appear.snapTo(0f)
            appear.animateTo(1f, androidx.compose.animation.core.tween(700, delayMillis = delay, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else appear.snapTo(1f)
    }
    Box(
        modifier
            .graphicsLayer { translationY = (1f - appear.value) * 40f; alpha = 0.35f + 0.65f * appear.value }
            .clip(RoundedCornerShape(d(28)))
            .background(LocalTheme.current.block)
            .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28)))
    ) {
        content()
        if (animate) BlockSweep()
    }
}

private var blockEntrance = 0

@Composable
fun Label(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(d(26)))
        Spacer(Modifier.width(d(10)))
        Text(text.uppercase(), color = Dim, fontSize = s(19), fontWeight = FontWeight.Medium, letterSpacing = s(2.5), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val OK_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

/**
 * Tło kafelka, przycisku lub wiersza w bieżącym wyglądzie (Themes.kt) razem z wyraźnym
 * zaznaczeniem elementu wybranego pokrętłem iDrive: kolor wyboru, gruba ramka, lekkie powiększenie.
 * Musi stać w łańcuchu przed clickable/focusable, żeby widzieć jego fokus.
 * fill = false: bez tła (np. kafelek „Dodaj”), active = element włączony (np. „3D”).
 */
fun Modifier.kokpitSurface(radius: Dp, fill: Boolean = true, active: Boolean = false): Modifier = composed {
    val t = LocalTheme.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(radius)
    val ring = d(3)
    val pulse = if (focused && LocalAnimate.current) loop(1600) else null
    // naciśnięcie: element się wciska i rozjaśnia, żeby było widać, że dotyk zadziałał
    var pressed by remember { mutableStateOf(false) }
    val press by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 1f else 0f,
        androidx.compose.animation.core.tween(if (pressed) 70 else 220),
        label = "press",
    )
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .pointerInput(Unit) {
            // tylko obserwacja dotyku – nie zabiera zdarzeń przyciskom i przewijaniu
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                try {
                    do {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    } while (ev.changes.any { it.pressed })
                } finally {
                    pressed = false
                }
            }
        }
        .graphicsLayer {
            // delikatne powiększenie przy wyborze pokrętłem (w wyglądzie animowanym „oddycha”) i wciśnięcie przy dotyku
            val grow = if (focused && t.selectedGrow) 1.03f + 0.015f * (pulse?.value ?: 0f) else 1f
            val sc = grow * (1f - 0.07f * press)
            scaleX = sc; scaleY = sc
        }
        .clip(shape)
        .then(
            when {
                // wybór: przezroczysty kolor wyboru na zwykłym tle elementu
                focused -> Modifier.background(if (fill) t.surface else SolidColor(Color.Transparent)).background(t.selected)
                active -> Modifier.background(t.active)
                fill -> Modifier.background(t.surface)
                else -> Modifier
            }
        )
        .then(
            when {
                focused -> Modifier.border(ring, t.selectedRing, shape)
                fill && !active && t.surfaceEdge != null -> Modifier.border(1.dp, t.surfaceEdge, shape)
                else -> Modifier
            }
        )
        .drawWithContent {
            drawContent()
            if (press > 0f) drawRect(t.selectedRing.copy(alpha = 0.35f * press))
        }
}

@Composable
fun Pill(icon: ImageVector?, text: String, color: Color = Txt, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.kokpitSurface(d(14), active = active)
            .combinedClickable(onClick = onClick)
            .padding(horizontal = d(20), vertical = d(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(d(28)))
            Spacer(Modifier.width(d(10)))
        }
        Text(text, color = color, fontSize = s(22), fontWeight = FontWeight.Medium)
    }
}

// ---------- pasek stanu ----------

@Composable
fun StatusBar(onSettings: () -> Unit, onEdit: () -> Unit, editAllowed: Boolean) {
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000)
        }
    }
    val time = remember(now.time / 60000) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val date = remember(now.time / 60000) { SimpleDateFormat("EEEE, d MMMM", Locale("pl", "PL")).format(now) }
    val weather by WeatherState.current.collectAsState()
    val car by CarData.status.collectAsState()
    // temperatura z czujnika auta; z internetu tylko, gdy auto jej nie podaje
    val outside = car.outsideC?.let { String.format(Locale("pl", "PL"), "%.1f°C", it) } ?: weather?.let { "${it.temp}°C" }

    Row(Modifier.fillMaxWidth().height(d(76)), verticalAlignment = Alignment.CenterVertically) {
        Text(time, color = Txt, fontSize = s(36), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(d(26)))
        Text(date, color = Dim, fontSize = s(26))
        if (outside != null) {
            Spacer(Modifier.width(d(30)))
            weather?.let { Icon(weatherIcon(it.code), null, tint = Warn, modifier = Modifier.size(d(30))) }
            Spacer(Modifier.width(d(8)))
            Text(outside, color = Dim, fontSize = s(26))
        }
        Spacer(Modifier.weight(1f))
        SystemIcons()
        Spacer(Modifier.width(d(20)))
        VolumeControl()
        Spacer(Modifier.width(d(14)))
        val ctx = LocalContext.current
        Pill(Icons.Outlined.Tune, "Radio", onClick = { openRadioSettings(ctx) })
        Spacer(Modifier.width(d(14)))
        Pill(Icons.Outlined.DashboardCustomize, "Układ", color = if (editAllowed) Txt else Dim, onClick = onEdit)
        Spacer(Modifier.width(d(14)))
        Pill(Icons.Outlined.Settings, "Ustawienia", onClick = onSettings)
    }
}

/** Pasek w trybie edycji: ukryte klocki do dodania, przywrócenie układu i "Gotowe". */
@Composable
fun EditBar(slots: List<Slot>, onAdd: (BlockId) -> Unit, onReset: () -> Unit, onStore: () -> Unit, onRestore: (() -> Unit)?, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(d(76)), verticalAlignment = Alignment.CenterVertically) {
        Text("Przeciągnij klocek · niebieski uchwyt zmienia rozmiar", color = Dim, fontSize = s(24))
        Spacer(Modifier.width(d(30)))
        var picker by remember { mutableStateOf(false) }
        Pill(Icons.Outlined.Add, "Widżety", onClick = { picker = true })
        if (picker) BlockPickerDialog(slots, onAdd = { picker = false; onAdd(it) }, onClose = { picker = false })
        Spacer(Modifier.weight(1f))
        Pill(Icons.Outlined.Save, "Zapisz układ", onClick = onStore)
        Spacer(Modifier.width(d(14)))
        if (onRestore != null) {
            Pill(Icons.Outlined.Restore, "Przywróć zapisany", onClick = onRestore)
            Spacer(Modifier.width(d(14)))
        }
        Pill(null, "Domyślny", onClick = onReset)
        Spacer(Modifier.width(d(14)))
        Pill(null, "Gotowe", color = LocalTheme.current.activeText, active = true, onClick = onDone)
    }
}

/** Opis klocka w oknie „Widżety”: ikona, przykładowa treść do podglądu i jedno zdanie, co pokazuje. */
private class BlockInfo(val icon: ImageVector, val sample: String, val desc: String)

private fun blockInfo(id: BlockId) = when (id) {
    BlockId.SHORTCUTS -> BlockInfo(Icons.Outlined.Apps, "YouTube · Mapy · …", "Twoje aplikacje na przewijanych stronach")
    BlockId.CAR -> BlockInfo(Icons.Outlined.DirectionsCar, "0 km/h · 48 l", "Prędkość, paliwo, otwarte drzwi, ekran iDrive")
    BlockId.NAV -> BlockInfo(Icons.Outlined.Navigation, "Za 300 m w lewo", "Trasa z Map Google i Waze, Dom i Praca")
    BlockId.CAMERA -> BlockInfo(Icons.Outlined.Videocam, "Obraz na żywo", "Podgląd kamer z Xiaomi Cam")
    BlockId.WEATHER -> BlockInfo(Icons.Outlined.WbSunny, "3° · pochmurno", "Pogoda teraz i prognoza")
    BlockId.WIDGET -> BlockInfo(Icons.Outlined.Widgets, "Dowolny widżet", "Widżet innej aplikacji – można dodać kilka")
    BlockId.ASSISTANT -> BlockInfo(Icons.Outlined.SmartToy, "„Jaka będzie pogoda?”", "Rozmowa z asystentem AI, pisana i głosowa")
    BlockId.CLIMATE -> BlockInfo(Icons.Outlined.AcUnit, "20,6° w domu", "Klimatyzator w domu (Smart Life)")
    BlockId.TUYA -> BlockInfo(Icons.Outlined.Lightbulb, "Światło · czujnik", "Urządzenie Smart Life – można dodać kilka")
}

/** Okno „Widżety” w edycji układu: siatka klocków z podglądem i nazwą; dotknięcie dodaje klocek do ekranu. */
@Composable
fun BlockPickerDialog(slots: List<Slot>, onAdd: (BlockId) -> Unit, onClose: () -> Unit) {
    val free = BlockId.entries.filter { id -> id.multi || slots.none { it.id == id } }
    Sheet("Dodaj widżet", onClose) {
        if (free.isEmpty()) {
            Text("Wszystkie widżety są już na ekranie.", color = Dim, fontSize = s(24), modifier = Modifier.padding(top = d(30)))
            return@Sheet
        }
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(top = d(20)),
            horizontalArrangement = Arrangement.spacedBy(d(18)),
            verticalArrangement = Arrangement.spacedBy(d(18)),
        ) {
            items(free.size) { i ->
                val id = free[i]
                val info = blockInfo(id)
                Column(Modifier.fillMaxWidth().kokpitSurface(d(22)).combinedClickable(onClick = { onAdd(id) }).padding(d(16))) {
                    // podgląd: pomniejszony klocek z nagłówkiem i przykładową treścią
                    Column(
                        Modifier.fillMaxWidth().height(d(190)).clip(RoundedCornerShape(d(18))).background(LocalTheme.current.screen)
                            .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(18))).padding(d(18)),
                    ) {
                        Label(info.icon, id.title)
                        Spacer(Modifier.weight(1f))
                        Icon(info.icon, null, tint = Accent, modifier = Modifier.size(d(54)))
                        Spacer(Modifier.height(d(8)))
                        Text(info.sample, color = Txt, fontSize = s(24), fontWeight = FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(id.title, color = Txt, fontSize = s(24), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = d(12)))
                    Text(info.desc, color = Dim, fontSize = s(18), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- skróty ----------

private const val ADD_TILE = "+"

/** Stan przeciągania skrótu: współrzędne w układzie całego okna (root). */
private class ShortcutDrag {
    var from by mutableIntStateOf(-1)
    var target by mutableIntStateOf(-1)
    var finger by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero
    var size = Size.Zero
    var moved = false
    val rects = HashMap<Int, Rect>()
    var pager = Rect.Zero
}

private const val DRAG_SLOP_PX = 24f

@Composable
fun ShortcutsBlock(
    modifier: Modifier,
    slot: Slot,
    apps: List<AppEntry>,
    shortcuts: List<String>,
    onAdd: () -> Unit,
    onMenu: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDrawer: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // niski klocek: jeden rząd ikon; wysoki: trzy rzędy; zawsze dwie kolumny ikon na komórkę siatki
    val rows = if (slot.h == 1) 1 else 3
    val cols = slot.w * 2
    val perPage = rows * cols
    val pages = (shortcuts + ADD_TILE).chunked(perPage)
    val pager = rememberPagerState { pages.size }
    val drag = remember { ShortcutDrag() }
    val dragging = drag.from >= 0
    val count = shortcuts.size
    val menu by rememberUpdatedState(onMenu)
    val move by rememberUpdatedState(onMove)
    var blockOrigin by remember { mutableStateOf(Offset.Zero) }

    fun updateTarget() {
        val f = drag.finger
        drag.rects.entries.firstOrNull { (i, r) -> i <= count && r.width > 0f && r.contains(f) }
            ?.let { drag.target = it.key.coerceAtMost(count - 1) }
    }

    // palec przy lewej/prawej krawędzi bloku: po chwili przełącz stronę
    val edgeDir = when {
        !dragging || pages.size < 2 -> 0
        drag.finger.x < drag.pager.left + drag.pager.width * 0.08f && pager.currentPage > 0 -> -1
        drag.finger.x > drag.pager.right - drag.pager.width * 0.08f && pager.currentPage < pages.size - 1 -> 1
        else -> 0
    }
    LaunchedEffect(edgeDir) {
        while (edgeDir != 0) {
            delay(650)
            val next = (pager.currentPage + edgeDir).coerceIn(0, pages.size - 1)
            pager.animateScrollToPage(next)
            updateTarget()
        }
    }

    fun endDrag(commit: Boolean) {
        val from = drag.from
        val to = drag.target
        val moved = drag.moved
        drag.from = -1
        drag.target = -1
        if (!commit || from < 0) return
        if (!moved) menu(from) else if (to in 0 until count && to != from) move(from, to)
    }

    // jeden detektor na kafelek: dotknięcie = uruchom, przytrzymanie = menu, przytrzymanie + ruch = przesuń
    fun Modifier.draggableTile(index: Int, key: String, onTap: () -> Unit): Modifier = this
        .onGloballyPositioned { drag.rects[index] = it.boundsInRoot() }
        .graphicsLayer { alpha = if (drag.from == index) 0f else 1f }
        .pointerInput(index, count, key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val long = awaitLongPressOrCancellation(down.id)
                if (long == null) {
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && !up.pressed && !up.isConsumed) {
                        up.consume()
                        onTap()
                    }
                    return@awaitEachGesture
                }
                val r = drag.rects[index] ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                long.consume()
                drag.grab = long.position
                drag.size = r.size
                drag.finger = r.topLeft + long.position
                drag.moved = false
                drag.target = index
                drag.from = index
                val start = drag.finger
                val ok = drag(long.id) { change ->
                    drag.finger += change.positionChange()
                    change.consume()
                    if (!drag.moved && (drag.finger - start).getDistance() > DRAG_SLOP_PX) drag.moved = true
                    updateTarget()
                }
                endDrag(ok)
            }
        }

    Block(modifier.onGloballyPositioned { blockOrigin = it.boundsInRoot().topLeft }) {
        Column(Modifier.fillMaxSize().padding(vertical = d(22))) {
            HorizontalPager(
                pager,
                Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { drag.pager = it.boundsInRoot() },
                userScrollEnabled = !dragging,
                // wszystkie strony zostają w kompozycji, żeby gest przeciągania przetrwał zmianę strony
                beyondViewportPageCount = pages.size,
            ) { p ->
                val items = pages[p]
                Column(Modifier.fillMaxSize().padding(horizontal = d(22)), verticalArrangement = Arrangement.spacedBy(d(16))) {
                    for (row in 0 until rows) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(16))) {
                            for (col in 0 until cols) {
                                val i = row * cols + col
                                val key = items.getOrNull(i)
                                val index = p * perPage + i
                                val hl = dragging && drag.target == index && drag.from != index
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    when (key) {
                                        null -> {}
                                        ADD_TILE -> Tile(null, Icons.Outlined.Add, "Dodaj", dashed = true,
                                            modifier = Modifier.onGloballyPositioned { drag.rects[index] = it.boundsInRoot() },
                                            onClick = onAdd)
                                        APPS_DRAWER -> Tile(null, Icons.Outlined.Apps, "Aplikacje", highlight = hl,
                                            gestures = Modifier.draggableTile(index, key, onDrawer), onClick = onDrawer)
                                        else -> {
                                            val app = Apps.find(apps, key)
                                            Tile(app?.icon, Icons.Outlined.Apps, app?.label ?: "Brak aplikacji", highlight = hl,
                                                gestures = Modifier.draggableTile(index, key) { if (!Apps.launch(ctx, key)) onMenu(index) },
                                                onClick = { if (!Apps.launch(ctx, key)) onMenu(index) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = d(14)), horizontalArrangement = Arrangement.Center) {
                    repeat(pages.size) { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier.padding(horizontal = d(6))
                                .size(width = if (on) d(30) else d(12), height = d(12))
                                .clip(RoundedCornerShape(d(6)))
                                .background(if (on) Txt else Color(0x33FFFFFF))
                        )
                    }
                }
            }
        }
        // przeciągana ikona pod palcem
        if (dragging) {
            val key = shortcuts.getOrNull(drag.from)
            val pos = drag.finger - drag.grab - blockOrigin
            with(density) {
                Box(
                    Modifier
                        .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                        .size(drag.size.width.toDp(), drag.size.height.toDp())
                        .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.92f }
                ) {
                    if (key == APPS_DRAWER) Tile(null, Icons.Outlined.Apps, "Aplikacje", onClick = {})
                    else if (key != null) {
                        val app = Apps.find(apps, key)
                        Tile(app?.icon, Icons.Outlined.Apps, app?.label ?: "Brak aplikacji", onClick = {})
                    }
                }
            }
        }
    }
}

@Composable
fun Tile(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    fallback: ImageVector,
    label: String,
    dashed: Boolean = false,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
    /** własna obsługa dotyku zamiast kliknięcia (przeciąganie skrótów) */
    gestures: Modifier? = null,
    onClick: () -> Unit = {},
    onLong: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize()
            .kokpitSurface(d(20), fill = !dashed)
            .then(
                when {
                    highlight -> Modifier.border(3.dp, Accent, RoundedCornerShape(d(20)))
                    dashed -> Modifier.border(2.dp, if (LightMode.on) Color(0x33000000) else Color(0x26FFFFFF), RoundedCornerShape(d(20)))
                    else -> Modifier
                }
            )
            .then(
                if (gestures != null) gestures.onKeyEvent { e ->
                    // pokrętło iDrive / przycisk OK: uruchom jak dotknięciem
                    if (e.type == KeyEventType.KeyUp && e.key in OK_KEYS) { onClick(); true } else false
                }.focusable()
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, label, Modifier.size(d(80)))
        } else {
            Box(Modifier.size(d(80)).clip(RoundedCornerShape(d(20))).background(if (dashed) Color.Transparent else if (LightMode.on) Color(0xFFDDE4EE) else Color(0xFF2B3340)), contentAlignment = Alignment.Center) {
                Icon(fallback, null, tint = if (dashed) Dim else Txt, modifier = Modifier.size(d(46)))
            }
        }
        Spacer(Modifier.height(d(12)))
        Text(label, color = Dim, fontSize = s(21), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = d(8)))
    }
}

// ---------- auto ----------

@Composable
fun CarBlock(modifier: Modifier, act: MainActivity, apps: List<AppEntry>, edit: Boolean, onSplitSettings: () -> Unit) {
    val speed by act.location.speedKmh.collectAsState()
    val car by CarData.status.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            act.location.checkStale()
            delay(1000)
        }
    }
    Block(modifier) {
        val theme = LocalTheme.current
        Box(Modifier.fillMaxSize().background(theme.carBack)) {
            if (LocalAnimate.current) CarGlow(theme.glow)
            when {
                car.open.isNotEmpty() -> Row(Modifier.fillMaxSize().padding(vertical = d(24)), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TopDownCar(car.open)
                    Spacer(Modifier.width(d(60)))
                    Column(Modifier.weight(1.4f)) {
                        Text("Otwarte", color = Warn, fontSize = s(30), fontWeight = FontWeight.Medium)
                        car.open.sortedBy { it.ordinal }.forEach {
                            Text(it.label, color = Txt, fontSize = s(26), modifier = Modifier.padding(top = d(8)))
                        }
                    }
                }
                else -> Image(
                    painterResource(R.drawable.car_f11), "BMW F11",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.95f).padding(top = d(70)),
                )
            }

            Row(Modifier.padding(start = d(34), top = d(30)), verticalAlignment = Alignment.Bottom) {
                // wygląd animowany: prędkość dochodzi płynnie do nowej wartości
                val shown by androidx.compose.animation.core.animateIntAsState(speed ?: 0, label = "speed")
                Text(
                    if (speed == null) "—" else (if (LocalAnimate.current) shown else speed).toString(), color = Txt, fontSize = s(96), fontWeight = FontWeight.Light,
                    style = theme.speed?.let { androidx.compose.ui.text.TextStyle(brush = it) } ?: androidx.compose.ui.text.TextStyle.Default,
                )
                Spacer(Modifier.width(d(10)))
                Text("km/h", color = Dim, fontSize = s(24), modifier = Modifier.padding(bottom = d(16)))
                car.gear?.let { g ->
                    Spacer(Modifier.width(d(26)))
                    Text(
                        g, color = if (g == "R") Warn else Accent, fontSize = s(40), fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = d(10)).clip(RoundedCornerShape(d(12)))
                            .border(2.dp, if (g == "R") Warn else Accent, RoundedCornerShape(d(12)))
                            .padding(horizontal = d(14), vertical = d(2)),
                    )
                }
            }
            // tylko w czasie jazdy i gdy włączone w Ustawieniach
            if (car.beltWarn && beltOn.value && (speed ?: 0) >= 10) {
                Row(
                    Modifier.padding(start = d(34), top = d(150)).clip(RoundedCornerShape(d(14))).background(Color(0x33FF5252))
                        .padding(horizontal = d(16), vertical = d(8)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = Warn, modifier = Modifier.size(d(28)))
                    Spacer(Modifier.width(d(10)))
                    Text("Zapnij pasy", color = Warn, fontSize = s(24), fontWeight = FontWeight.Medium)
                }
            }

            car.fuelL?.let { fuel ->
                Column(Modifier.align(Alignment.TopEnd).padding(top = d(34), end = d(34)).fillMaxWidth(0.36f).widthIn(max = d(330)), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocalGasStation, null, tint = Dim, modifier = Modifier.size(d(30)))
                        Spacer(Modifier.width(d(8)))
                        Text("${fuel.toInt()} l", color = Txt, fontSize = s(40), fontWeight = FontWeight.Medium)
                        car.rangeKm?.let { Text("  · ${it} km", color = Dim, fontSize = s(22)) }
                    }
                    Box(Modifier.padding(top = d(10)).fillMaxWidth().height(d(8)).clip(RoundedCornerShape(d(8))).background(Color(0x14FFFFFF))) {
                        Box(Modifier.fillMaxWidth((fuel / CarData.TANK_L).coerceIn(0f, 1f)).fillMaxHeight().clipToBounds().background(if (fuel < 10) SolidColor(Warn) else theme.fuel)) {
                            if (LocalAnimate.current) Shimmer()
                        }
                    }
                }
            }

            Row(Modifier.align(Alignment.BottomEnd).padding(d(26)), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.kokpitSurface(d(18))
                        .combinedClickable(onClick = { openCarScreen(act) })
                        .padding(horizontal = d(16), vertical = d(14)),
                ) { Text("iDrive", color = Txt, fontSize = s(24), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(d(14)))
                val left = Apps.find(apps, Prefs.splitLeft)
                val right = Apps.find(apps, Prefs.splitRight)
                Row(
                    Modifier.kokpitSurface(d(18))
                        .combinedClickable(
                            onClick = { openSplit(act, Prefs.splitLeft, Prefs.splitRight) },
                            onLongClick = onSplitSettings,
                        )
                        .padding(horizontal = d(18), vertical = d(12)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallAppIcon(left)
                    Box(Modifier.padding(horizontal = d(12)).size(width = d(2), height = d(30)).background(Color(0x4DFFFFFF)))
                    SmallAppIcon(right)
                }
            }
        }
    }
}

@Composable
fun SmallAppIcon(app: AppEntry?) {
    if (app != null) Image(app.icon, app.label, Modifier.size(d(40)))
    else Icon(Icons.Outlined.VerticalSplit, null, tint = Dim, modifier = Modifier.size(d(36)))
}

// ---------- nawigacja ----------

@Composable
fun NavBlock(modifier: Modifier, slot: Slot, act: MainActivity, onAddress: (Boolean) -> Unit, onPlace3: () -> Unit) {
    val nav by NavState.info.collectAsState()
    val listenerOn = remember(act.resumeTick.intValue) { NavListener.isEnabled(act) }
    val compact = slot.h == 1
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(d(26))) {
            Label(Icons.Outlined.Navigation, "Nawigacja")
            val n = nav
            if (n != null) {
                Row(
                    Modifier.padding(top = d(if (compact) 16 else 30)).combinedClickable(onClick = {
                        try {
                            n.open?.send()
                        } catch (_: Exception) {
                            Apps.launch(act, n.pkg)
                        }
                    }),
                ) {
                    Column {
                        if (n.icon != null) {
                            val nudge = if (LocalAnimate.current) loop(1400) else null
                            val nudgePx = with(LocalDensity.current) { d(8).toPx() }
                            Box(Modifier.size(d(if (compact) 64 else 84)).graphicsLayer { translationX = (nudge?.value ?: 0f) * nudgePx }
                                .clip(RoundedCornerShape(d(20))).background(CardHi), contentAlignment = Alignment.Center) {
                                Image(n.icon.asImageBitmap(), null, Modifier.size(d(if (compact) 48 else 64)))
                            }
                            Spacer(Modifier.height(d(14)))
                        }
                        Text(n.title, color = Txt, fontSize = s(if (compact) 30 else 36), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = s(40))
                        if (n.text.isNotBlank()) Text(n.text, color = Dim, fontSize = s(20), maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = d(8)))
                        if (n.sub.isNotBlank() && !compact) Text(n.sub, color = Txt, fontSize = s(20), maxLines = 2, modifier = Modifier.padding(top = d(12)))
                    }
                }
            } else {
                Text("Brak aktywnej trasy", color = Dim, fontSize = s(22), modifier = Modifier.padding(top = d(if (compact) 14 else 30)))
                if (!listenerOn) {
                    Text(
                        "Włącz podgląd trasy",
                        color = Accent, fontSize = s(20),
                        modifier = Modifier.padding(top = d(14)).combinedClickable(onClick = {
                            act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // zapisane miejsca: same ikony obok siebie (przytrzymanie = zmiana adresu)
            val place3 = Prefs.place3Name.ifBlank { null }
            Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                DestButton(Icons.Outlined.Home, "Dom", onClick = { goSaved(act, Prefs.home, false) }) { onAddress(false) }
                DestButton(Icons.Outlined.Work, "Praca", onClick = { goSaved(act, Prefs.work, true) }) { onAddress(true) }
                if (place3 != null) DestButton(Icons.Outlined.Place, place3, onClick = { goPlace3(act) }, onEdit = onPlace3)
            }
        }
    }
}

/** Dom/Praca: własny adres, a bez niego miejsce zapisane w Mapach Google. */
fun goSaved(act: MainActivity, address: String, work: Boolean) {
    if (address.isNotBlank()) navigateTo(act, address)
    else if (!navigateSaved(act, work)) android.widget.Toast.makeText(
        act, "Dom i Praca z Map Google działają, gdy Kokpit F11 jest ekranem głównym. Przytrzymaj, aby wpisać adres.",
        android.widget.Toast.LENGTH_LONG,
    ).show()
}

/** Trzecie miejsce: adres wpisany w Kokpicie, a bez niego etykieta wyszukana w Mapach Google. */
fun goPlace3(act: MainActivity) {
    val address = Prefs.place3Address
    if (address.isNotBlank()) navigateTo(act, address) else searchInMaps(act, Prefs.place3Name)
}

/** Przycisk celu (sama ikona; [name] jako opis dla czytnika ekranu); przytrzymanie = zmiana adresu. */
@Composable
fun DestButton(icon: ImageVector, name: String, onClick: () -> Unit, onEdit: () -> Unit) {
    Box(
        Modifier.size(d(72)).kokpitSurface(d(18))
            .combinedClickable(onClick = onClick, onLongClick = onEdit),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, name, tint = Accent, modifier = Modifier.size(d(34)))
    }
}

// ---------- kamera ----------

@Composable
fun CameraBlock(modifier: Modifier, act: MainActivity, onChoose: () -> Unit) {
    val live by act.liveCamera
    val visible by act.visible
    val id = act.cameraWidgetId.intValue
    var playing by remember(live) { mutableStateOf(false) }
    var problem by remember(live) { mutableStateOf<String?>(null) }
    val cameras by produceState<List<String>?>(null, live) { value = XiaomiCamServer.camerasWaking(act) }
    Block(modifier) {
        when {
            live.isNotBlank() && visible -> Box(Modifier.fillMaxSize()) {
                LiveCameraView(live, onProblem = { problem = it }, onState = { playing = it })
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent, Color.Transparent, Color(0x99000000)))))
                Row(Modifier.fillMaxWidth().padding(horizontal = d(24), vertical = d(18)), verticalAlignment = Alignment.CenterVertically) {
                    Text(live, color = Color(0xFFEEF2F7), fontSize = s(24), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // bez znaczka „na żywo” (użytkownik uznał go za zbędny); tylko stan łączenia
                    if (!playing) Text(
                        "łączenie…", color = Dim, fontSize = s(18), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(d(10))).background(Color(0x73000000)).padding(horizontal = d(12), vertical = d(6)),
                    )
                }
                if (!playing) {
                    Text(
                        problem ?: "Brak obrazu – dotknij, aby otworzyć Xiaomi Cam",
                        color = if (problem != null) Color(0xFFFFB74D) else Dim, fontSize = s(20),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).combinedClickable(onClick = { Apps.launch(act, MainActivity.XIAOMI_CAM) }),
                    )
                }
                val list = cameras.orEmpty()
                if (list.size > 1) {
                    val i = list.indexOf(live).coerceAtLeast(0)
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(d(16)), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChevronLeft, "Poprzednia", tint = Color(0xFFEEF2F7),
                            modifier = Modifier.size(d(52)).combinedClickable(onClick = { act.setLiveCamera(list[(i - 1 + list.size) % list.size]) }))
                        Spacer(Modifier.weight(1f))
                        Text("${i + 1} / ${list.size}", color = Color(0xFFB8C2D0), fontSize = s(20))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, "Następna", tint = Color(0xFFEEF2F7),
                            modifier = Modifier.size(d(52)).combinedClickable(onClick = { act.setLiveCamera(list[(i + 1) % list.size]) }))
                    }
                }
            }
            id != -1 && act.widgetManager.getAppWidgetInfo(id) != null -> HostedWidget(act, id)
            else -> Column(
                Modifier.fillMaxSize().combinedClickable(onClick = onChoose),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Videocam, null, tint = Accent, modifier = Modifier.size(d(64)))
                Spacer(Modifier.height(d(14)))
                Text("Dodaj kamerę", color = Txt, fontSize = s(26))
                Text("obraz na żywo lub widżet Xiaomi Cam", color = Dim, fontSize = s(20))
            }
        }
    }
}

// ---------- widżet dowolnej aplikacji ----------

@Composable
fun WidgetBlock(modifier: Modifier, act: MainActivity, n: Int, onChoose: () -> Unit) {
    val id = act.blockWidgets[n] ?: -1
    val ok = id != -1 && remember(id) { act.widgetManager.getAppWidgetInfo(id) } != null
    Block(modifier) {
        if (ok) {
            // zmiana widżetu: Układ → przycisk w lewym dolnym rogu klocka; usunięcie: ukrycie klocka
            HostedWidget(act, id)
        } else {
            Column(
                Modifier.fillMaxSize().combinedClickable(onClick = onChoose),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.Widgets, null, tint = Accent, modifier = Modifier.size(d(64)))
                Spacer(Modifier.height(d(14)))
                Text("Dodaj widżet", color = Txt, fontSize = s(26))
                Text("dowolnej zainstalowanej aplikacji", color = Dim, fontSize = s(20), textAlign = TextAlign.Center)
            }
        }
    }
}

/** Przycisk „Zmień widżet” w lewym dolnym rogu klocka w trybie edycji układu. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.WidgetChangeButton(onClick: () -> Unit) {
    Row(
        Modifier.align(Alignment.BottomStart).padding(d(12)).height(d(64))
            .clip(RoundedCornerShape(d(18))).background(Accent)
            .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume() } }
            .clickableNoRipple(onClick)
            .padding(horizontal = d(16)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Widgets, "Zmień widżet", tint = Color(0xFF031426), modifier = Modifier.size(d(34)))
        Spacer(Modifier.width(d(8)))
        Text("Zmień", color = Color(0xFF031426), fontSize = s(22), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun HostedWidget(act: MainActivity, id: Int) {
    val info = remember(id) { act.widgetManager.getAppWidgetInfo(id) } ?: return
    androidx.compose.runtime.key(id) {
        AndroidView(
            factory = { ctx ->
                act.widgetHost.createView(ctx, id, info).apply {
                    setPadding(0, 0, 0, 0)
                    addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
                        if (r - l != or - ol || b - t != ob - ot) {
                            val dens = v.resources.displayMetrics.density
                            val w = ((r - l) / dens).toInt()
                            val h = ((b - t) / dens).toInt()
                            @Suppress("DEPRECATION")
                            (v as AppWidgetHostView).updateAppWidgetSize(Bundle(), w, h, w, h)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------- klimatyzacja (Smart Life / Tuya) ----------

private fun modeIcon(m: String?): ImageVector = when (m) {
    "cold" -> Icons.Outlined.AcUnit
    "hot" -> Icons.Outlined.WbSunny
    "wet" -> Icons.Outlined.WaterDrop
    "wind" -> Icons.Outlined.Air
    else -> Icons.Outlined.Autorenew
}

@Composable
fun ClimateBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSettings: () -> Unit) {
    val st by Tuya.state.collectAsState()
    val sens by Tuya.sensor.collectAsState()
    val err by Tuya.error.collectAsState()
    val visible by act.visible
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // odczyt co minutę, tylko gdy Kokpit jest na ekranie
    LaunchedEffect(visible, Tuya.configured) {
        while (visible && Tuya.configured) {
            Tuya.refresh()
            delay(60_000)
        }
    }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { block(); busy = false }
    }
    // wąski blok: mniejsze przyciski, żeby zmieścił się też nawiew
    val btn = if (slot.w == 1) d(50) else d(64)
    // wybór nawiewu albo trybu: okienko z osobnym przyciskiem dla każdej wartości
    var picker by remember { mutableStateOf<String?>(null) }
    picker?.let { kind ->
        val cur = Tuya.state.value
        val options = if (kind == "fan") Tuya.fanValues.map { it to Tuya.fanName(it) } else Tuya.MODES
        val selected = if (kind == "fan") cur?.fan else cur?.mode
        androidx.compose.ui.window.Dialog(onDismissRequest = { picker = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block)
                    .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28))).padding(d(28)),
            ) {
                Text(if (kind == "fan") "Nawiew" else "Tryb", color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(d(18)))
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    options.forEach { (value, label) ->
                        val on = value == selected
                        Box(
                            Modifier.height(d(84)).width(d(150)).kokpitSurface(d(18), active = on)
                                .combinedClickable(onClick = {
                                    picker = null
                                    run { if (kind == "fan") Tuya.setFan(value) else Tuya.setMode(value) }
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(20), textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(d(24))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.AcUnit, st?.name ?: "Klimatyzacja") }
                // polecenie czeka na potwierdzenie z urządzenia – przyciski zablokowane
                if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(d(26)), color = Accent, strokeWidth = d(3))
            }
            val s0 = st
            when {
                !Tuya.configured -> Column(Modifier.fillMaxSize().combinedClickable(onClick = onSettings), verticalArrangement = Arrangement.Center) {
                    Text("Połącz klimatyzator", color = Accent, fontSize = s(24))
                    Text("Ustawienia → Dom (Smart Life): klucze Tuya", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                }
                s0 == null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(err ?: "Łączenie z Tuya…", color = if (err != null) Warn else Dim, fontSize = s(20))
                }
                else -> {
                    Spacer(Modifier.weight(1f))
                    // temperatura z czujnika w pokoju (dokładniejsza), czujnik klimatyzatora mniejszym tekstem
                    val room = sens?.temp
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text((room ?: s0.room)?.let { if (room != null) "%.1f°".format(it).replace('.', ',') else "${it.toInt()}°" } ?: "–",
                            color = Txt, fontSize = s(if (slot.h > 1) 92 else 64), fontWeight = FontWeight.Light)
                        Column(Modifier.padding(start = d(14), bottom = d(14))) {
                            Text("w domu", color = Dim, fontSize = s(18))
                            (sens?.humidity ?: s0.humidity)?.let { Text("wilg. $it %", color = Dim, fontSize = s(18)) }
                            if (room != null && s0.room != null) Text("klima ${s0.room.toInt()}°", color = Dim, fontSize = s(18))
                        }
                    }
                    // tryb jako przycisk: dotknięcie otwiera wybór (grzanie, chłodzenie, auto…)
                    Row(Modifier.padding(top = d(6)), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.kokpitSurface(d(14), active = s0.on).combinedClickable(onClick = { picker = "mode" })
                                .padding(horizontal = d(12), vertical = d(6)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(modeIcon(s0.mode), null, tint = if (s0.on) LocalTheme.current.activeText else Accent, modifier = Modifier.size(d(24)))
                            Spacer(Modifier.width(d(6)))
                            Text(Tuya.modeName(s0.mode), color = if (s0.on) LocalTheme.current.activeText else Txt, fontSize = s(18), maxLines = 1)
                        }
                        Spacer(Modifier.width(d(10)))
                        Text(
                            if (!s0.online) "offline" else if (s0.on) "${s0.target?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "–"}°" else "wył.",
                            color = if (s0.on) Accent else Dim, fontSize = s(20), maxLines = 1,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // przyciski dzielą szerokość bloku po równo – zawsze mieszczą się wszystkie (też nawiew w wąskim bloku)
                    val showFan = slot.h == 1 && s0.fan != null && Tuya.fanValues.isNotEmpty()
                    val icon = if (slot.w == 1) d(28) else d(32)
                    Row(Modifier.fillMaxWidth().alpha(if (busy) 0.45f else 1f), horizontalArrangement = Arrangement.spacedBy(if (slot.w == 1) d(8) else d(10)), verticalAlignment = Alignment.CenterVertically) {
                        val cell = Modifier.weight(1f).height(btn)
                        Box(
                            cell.kokpitSurface(d(16), active = s0.on).combinedClickable(onClick = { run { Tuya.setPower(!s0.on) } }),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PowerSettingsNew, if (s0.on) "Wyłącz" else "Włącz", tint = if (s0.on) LocalTheme.current.activeText else Txt, modifier = Modifier.size(icon)) }
                        val t = s0.target ?: 22f
                        Box(cell.kokpitSurface(d(16)).combinedClickable(onClick = { run { Tuya.setTarget(t - 0.5f) } }), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Remove, "Chłodniej", tint = Txt, modifier = Modifier.size(icon))
                        }
                        Box(cell.kokpitSurface(d(16)).combinedClickable(onClick = { run { Tuya.setTarget(t + 0.5f) } }), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, "Cieplej", tint = Txt, modifier = Modifier.size(icon))
                        }
                        if (showFan) {
                            Box(cell.kokpitSurface(d(16)).combinedClickable(onClick = { picker = "fan" }), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Air, "Nawiew: " + Tuya.fanName(s0.fan), tint = Accent, modifier = Modifier.size(icon))
                            }
                        }
                    }
                    // prędkość nawiewu (osobny wiersz w wysokim bloku; w niskim – przycisk obok − +)
                    if (slot.h > 1 && s0.fan != null && Tuya.fanValues.isNotEmpty()) {
                        val vals = Tuya.fanValues
                        val fi = vals.indexOf(s0.fan)
                        Row(
                            Modifier.padding(top = d(10)).fillMaxWidth().height(d(56)).kokpitSurface(d(16))
                                .combinedClickable(onClick = { picker = "fan" })
                                .padding(horizontal = d(16)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Air, null, tint = Accent, modifier = Modifier.size(d(28)))
                            Spacer(Modifier.width(d(10)))
                            Text("Nawiew: " + Tuya.fanName(s0.fan), color = Txt, fontSize = s(20), maxLines = 1)
                        }
                    }
                    err?.let { Text(it, color = Warn, fontSize = s(16), maxLines = 2, modifier = Modifier.padding(top = d(6))) }
                }
            }
        }
    }
}

// ---------- asystent AI ----------

/** 12 345 → „12 tys.”, 1 000 000 → „1 mln” (tokeny rozmowy) */
fun tokensText(t: Long) = when {
    t >= 1_000_000 -> "%.1f mln".format(t / 1_000_000.0).replace(".0 ", " ")
    t >= 1000 -> "${t / 1000} tys."
    else -> "$t"
}

@Composable
fun AssistantBlock(modifier: Modifier, slot: Slot, act: MainActivity, onSettings: () -> Unit) {
    val lines = Assistant.lines
    val busy by Assistant.busy
    var input by remember { mutableStateOf("") }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size, busy) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1 + if (busy) 1 else 0) }
    fun send() {
        Assistant.send(act, input)
        input = ""
    }
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(d(22))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Label(Icons.Outlined.SmartToy, "Asystent · " + Assistant.providerName()) }
                val tok by Assistant.tokens
                if (tok > 0 && slot.w > 1) Text(tokensText(tok) + " / " + tokensText(Prefs.aiTokenLimit.toLong()), color = Dim, fontSize = s(17),
                    modifier = Modifier.padding(end = d(12)))
                // odświeżenie sesji: czysta rozmowa (inaczej zostaje też po restarcie radia)
                if (lines.isNotEmpty()) Box(Modifier.size(d(56)).kokpitSurface(d(14))
                    .combinedClickable(onClick = { act.stopSpeaking(); Assistant.reset() }), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.RestartAlt, "Nowa rozmowa", tint = Txt, modifier = Modifier.size(d(34)))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = d(12))) {
                if (lines.isEmpty() && !busy) {
                    Column(Modifier.fillMaxSize().combinedClickable(onClick = { if (!Assistant.hasKey()) onSettings() }), verticalArrangement = Arrangement.Center) {
                        if (Assistant.hasKey()) {
                            Text("Zapytaj o cokolwiek albo poproś o trasę", color = Txt, fontSize = s(24))
                            Text("np. „ile mam paliwa?”, „zawieź mnie do najbliższej stacji”", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                        } else {
                            Text("Wpisz klucz API", color = Accent, fontSize = s(24))
                            Text("Ustawienia → Asystent AI (Anthropic albo OpenAI)", color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(d(10)), modifier = Modifier.fillMaxSize()) {
                        items(lines.size) { i ->
                            val l = lines[i]
                            Box(Modifier.fillMaxWidth(), contentAlignment = if (l.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
                                Text(
                                    l.text, color = if (l.fromUser) LocalTheme.current.activeText else Txt, fontSize = s(22), lineHeight = s(30),
                                    modifier = Modifier.fillMaxWidth(0.88f).wrapContentWidth(if (l.fromUser) Alignment.End else Alignment.Start)
                                        .then(if (l.fromUser) Modifier.kokpitSurface(d(16), active = true) else Modifier.kokpitSurface(d(16)))
                                        .combinedClickable(onClick = {}, onLongClick = { if (!l.fromUser) act.speak(l.text) })
                                        .padding(horizontal = d(16), vertical = d(10)),
                                )
                            }
                        }
                        if (busy) item { Text("Myślę…", color = Dim, fontSize = s(20), modifier = Modifier.padding(start = d(8))) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                if (slot.w > 1) {
                    androidx.compose.foundation.text.BasicTextField(
                        input, { input = it }, singleLine = true,
                        textStyle = TextStyle(color = Txt, fontSize = s(22)),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f).kokpitSurface(d(16)).padding(horizontal = d(16), vertical = d(16)),
                        decorationBox = { inner ->
                            Box { if (input.isEmpty()) Text("Napisz…", color = Dim, fontSize = s(22)); inner() }
                        },
                    )
                } else Spacer(Modifier.weight(1f))
                if (input.isNotBlank()) {
                    Box(Modifier.size(d(64)).kokpitSurface(d(16), active = true).combinedClickable(onClick = { send() }), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Wyślij", tint = LocalTheme.current.activeText, modifier = Modifier.size(d(32)))
                    }
                }
                Box(
                    Modifier.size(d(64)).kokpitSurface(d(16), active = input.isBlank()).combinedClickable(onClick = {
                        act.stopSpeaking()
                        if (!Assistant.hasKey()) onSettings()
                        else if (!act.listen { Assistant.send(act, it) }) android.widget.Toast.makeText(act, "Radio nie ma rozpoznawania mowy (Google)", android.widget.Toast.LENGTH_LONG).show()
                    }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Mic, "Mów", tint = if (input.isBlank()) LocalTheme.current.activeText else Accent, modifier = Modifier.size(d(34))) }
            }
        }
    }
}

// ---------- pogoda ----------

/** Przełącznik ostrzeżenia o pasach (Ustawienia); stan Compose, żeby blok auta reagował od razu. */
val beltOn by lazy { mutableStateOf(Prefs.beltWarn) }

object WeatherState {
    val current = kotlinx.coroutines.flow.MutableStateFlow<Weather?>(null)
}

@Composable
fun WeatherBlock(modifier: Modifier, slot: Slot, act: MainActivity) {
    val weather by WeatherState.current.collectAsState()
    val granted = remember(act.resumeTick.intValue) { act.locationGranted() }
    LaunchedEffect(Unit) {
        while (true) {
            val loc = act.location.location.value
            val w = loc?.let { fetchWeather(act, it) }
            if (w != null) WeatherState.current.value = w
            delay(if (w != null) 30 * 60_000L else 30_000L)
        }
    }
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = d(30), vertical = d(26)), verticalArrangement = Arrangement.SpaceBetween) {
            val w = weather
            Label(Icons.Outlined.Cloud, if (w != null && w.place.isNotBlank()) "Pogoda · ${w.place}" else "Pogoda")
            if (w == null) {
                Text(
                    if (granted) "Czekam na lokalizację i internet…" else "Zezwól na lokalizację",
                    color = if (granted) Dim else Accent, fontSize = s(22),
                    modifier = if (granted) Modifier else Modifier.combinedClickable(onClick = { act.requestLocation() }),
                )
                Spacer(Modifier.height(1.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code), null, tint = Warn, modifier = Modifier.size(d(64)))
                    Spacer(Modifier.width(d(20)))
                    Column {
                        Text("${w.temp}°", color = Txt, fontSize = s(56), fontWeight = FontWeight.Light, lineHeight = s(58))
                        Text("${weatherText(w.code)} · odczuwalna ${w.feels}°", color = Dim, fontSize = s(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    w.hours.take(if (slot.w >= 2) 4 else 2).forEach { h ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.hour, color = Dim, fontSize = s(18))
                            Icon(weatherIcon(h.code), null, tint = Txt, modifier = Modifier.size(d(30)).padding(vertical = d(2)))
                            Text("${h.temp}°", color = Txt, fontSize = s(22), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

fun weatherIcon(code: Int): ImageVector = when (code) {
    0 -> Icons.Outlined.WbSunny
    1, 2 -> Icons.Outlined.WbCloudy
    51, 53, 55, 56, 57 -> Icons.Outlined.Grain
    61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Outlined.Umbrella
    71, 73, 75, 77, 85, 86 -> Icons.Outlined.AcUnit
    95, 96, 99 -> Icons.Outlined.Thunderstorm
    else -> Icons.Outlined.Cloud
}
