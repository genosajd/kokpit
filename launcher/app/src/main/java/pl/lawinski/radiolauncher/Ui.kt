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
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material.icons.outlined.Work
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Bg = Color(0xFF07090D)
val CardBg = Color(0xFF11161E)
val CardHi = Color(0xFF161D27)
val Line = Color(0x12FFFFFF)
val Txt = Color(0xFFEEF2F7)
val Dim = Color(0xFF8A96A8)
val Accent = Color(0xFF4DA3FF)
val Warn = Color(0xFFFFB547)

/** Jednostka projektu: ekran zaprojektowany jest na 2560×720, a ta wartość przelicza go na dp tego ekranu. */
val LocalU = staticCompositionLocalOf { 1.dp }

@Composable
fun d(n: Number): Dp = LocalU.current * n.toFloat()

@Composable
fun s(n: Number): TextUnit = with(LocalDensity.current) { (LocalU.current * n.toFloat()).toSp() }

/** Okna otwierane nad ekranem głównym. */
sealed interface Dlg {
    data object Settings : Dlg
    data class Picker(val title: String, val drawer: Boolean = false, val onPick: (String) -> Unit) : Dlg
    data class Menu(val index: Int) : Dlg
    data class Address(val work: Boolean) : Dlg
    data object Cameras : Dlg
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = CardBg, background = Bg)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Bg)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
        ) {
            val u = minOf(maxWidth / 2560f, maxHeight / 720f)
            CompositionLocalProvider(LocalU provides u) {
                Column(Modifier.fillMaxSize().padding(horizontal = d(28))) {
                    if (edit) {
                        EditBar(slots,
                            onAdd = { id -> LayoutStore.place(slots, id)?.let { act.saveLayout(it) } },
                            onReset = { act.saveLayout(LayoutStore.DEFAULT) },
                            onDone = { edit = false })
                    } else {
                        StatusBar(
                            onSettings = { dialog = Dlg.Settings },
                            onEdit = { if ((speed ?: 0) <= EDIT_MAX_KMH) edit = true },
                            editAllowed = (speed ?: 0) <= EDIT_MAX_KMH,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = d(8), bottom = d(28))) {
                        BlockGrid(slots, edit, d(20), onChange = { act.saveLayout(it) }) { slot ->
                            val m = Modifier.fillMaxSize()
                            when (slot.id) {
                                BlockId.SHORTCUTS -> ShortcutsBlock(m, slot, apps, shortcuts,
                                    onAdd = { dialog = Dlg.Picker("Dodaj skrót") { key -> saveShortcuts(shortcuts + key) } },
                                    onMenu = { dialog = Dlg.Menu(it) },
                                    onDrawer = { dialog = Dlg.Picker("Aplikacje", drawer = true) { Apps.launch(act, it) } })
                                BlockId.CAR -> CarBlock(m, act, apps, edit, onSplitSettings = { dialog = Dlg.Settings })
                                BlockId.NAV -> NavBlock(m, slot, act, onAddress = { dialog = Dlg.Address(it) })
                                BlockId.CAMERA -> CameraBlock(m, act, onChoose = { dialog = Dlg.Cameras })
                                BlockId.WEATHER -> WeatherBlock(m, slot, act)
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
                        onCameras = { dialog = Dlg.Cameras },
                        onEditLayout = { dialog = null; edit = true },
                        onDemo = { demo = it },
                        onResetShortcuts = { saveShortcuts(Apps.defaultShortcuts(apps)) })
                    is Dlg.Picker -> AppPickerDialog(dl.title, apps, onClose = { dialog = null }) { key ->
                        dialog = null
                        dl.onPick(key)
                    }
                    is Dlg.Menu -> ShortcutMenuDialog(
                        index = dl.index, count = shortcuts.size,
                        onClose = { dialog = null },
                        onReplace = {
                            dialog = Dlg.Picker("Zamień skrót") { key ->
                                saveShortcuts(shortcuts.toMutableList().also { it[dl.index] = key })
                            }
                        },
                        onMove = { delta ->
                            val l = shortcuts.toMutableList()
                            val j = dl.index + delta
                            if (j in l.indices) {
                                l[dl.index] = l[j].also { l[j] = l[dl.index] }
                                saveShortcuts(l)
                            }
                            dialog = null
                        },
                        onRemove = {
                            saveShortcuts(shortcuts.toMutableList().also { it.removeAt(dl.index) })
                            dialog = null
                        },
                    )
                    is Dlg.Address -> AddressDialog(dl.work, onClose = { dialog = null })
                    is Dlg.Cameras -> CameraPickerDialog(act, onClose = { dialog = null })
                }
            }
        }
    }
}

@Composable
fun Block(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(d(28)))
            .background(CardBg)
            .border(1.dp, Line, RoundedCornerShape(d(28)))
    ) { content() }
}

@Composable
fun Label(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(d(26)))
        Spacer(Modifier.width(d(10)))
        Text(text.uppercase(), color = Dim, fontSize = s(19), fontWeight = FontWeight.Medium, letterSpacing = s(2.5), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Pill(icon: ImageVector?, text: String, color: Color = Txt, bg: Color = CardHi, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(d(14))).background(bg)
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
fun EditBar(slots: List<Slot>, onAdd: (BlockId) -> Unit, onReset: () -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(d(76)), verticalAlignment = Alignment.CenterVertically) {
        Text("Przeciągnij klocek · niebieski uchwyt zmienia rozmiar", color = Dim, fontSize = s(24))
        Spacer(Modifier.width(d(30)))
        BlockId.entries.filter { id -> slots.none { it.id == id } }.forEach { id ->
            Pill(Icons.Outlined.Add, id.title, onClick = { onAdd(id) })
            Spacer(Modifier.width(d(12)))
        }
        Spacer(Modifier.weight(1f))
        Pill(null, "Przywróć układ", onClick = onReset)
        Spacer(Modifier.width(d(14)))
        Pill(null, "Gotowe", color = Color(0xFF031426), bg = Accent, onClick = onDone)
    }
}

// ---------- skróty ----------

private const val ADD_TILE = "+"

@Composable
fun ShortcutsBlock(
    modifier: Modifier,
    slot: Slot,
    apps: List<AppEntry>,
    shortcuts: List<String>,
    onAdd: () -> Unit,
    onMenu: (Int) -> Unit,
    onDrawer: () -> Unit,
) {
    val ctx = LocalContext.current
    // niski klocek: jeden rząd szerszych ikon; wysoki: trzy rzędy, półtorej kolumny ikon na komórkę siatki
    val rows = if (slot.h == 1) 1 else 3
    val cols = if (slot.h == 1) slot.w * 2 else (slot.w * 3 / 2).coerceAtLeast(1)
    val perPage = rows * cols
    val pages = (shortcuts + ADD_TILE).chunked(perPage)
    val pager = rememberPagerState { pages.size }
    Block(modifier) {
        Column(Modifier.fillMaxSize().padding(vertical = d(22))) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { p ->
                val items = pages[p]
                Column(Modifier.fillMaxSize().padding(horizontal = d(22)), verticalArrangement = Arrangement.spacedBy(d(16))) {
                    for (row in 0 until rows) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(16))) {
                            for (col in 0 until cols) {
                                val i = row * cols + col
                                val key = items.getOrNull(i)
                                val index = p * perPage + i
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    when (key) {
                                        null -> {}
                                        ADD_TILE -> Tile(null, Icons.Outlined.Add, "Dodaj", dashed = true, onClick = onAdd)
                                        APPS_DRAWER -> Tile(null, Icons.Outlined.Apps, "Aplikacje", onClick = onDrawer, onLong = { onMenu(index) })
                                        else -> {
                                            val app = Apps.find(apps, key)
                                            Tile(app?.icon, Icons.Outlined.Apps, app?.label ?: "Brak aplikacji",
                                                onClick = { if (!Apps.launch(ctx, key)) onMenu(index) },
                                                onLong = { onMenu(index) })
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
    }
}

@Composable
fun Tile(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    fallback: ImageVector,
    label: String,
    dashed: Boolean = false,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(d(20)))
            .background(if (dashed) Color.Transparent else CardHi)
            .then(if (dashed) Modifier.border(2.dp, Color(0x26FFFFFF), RoundedCornerShape(d(20))) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, label, Modifier.size(d(80)))
        } else {
            Box(Modifier.size(d(80)).clip(RoundedCornerShape(d(20))).background(if (dashed) Color.Transparent else Color(0xFF2B3340)), contentAlignment = Alignment.Center) {
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
    val model by act.carModelPath
    val visible by act.visible
    var show3d by remember { mutableStateOf(Prefs.show3d) }
    LaunchedEffect(Unit) {
        while (true) {
            act.location.checkStale()
            delay(1000)
        }
    }
    Block(modifier) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CardBg, Color(0xFF0B0F15))))) {
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
                model.isNotBlank() && show3d && visible && !edit -> Car3DView(model, Modifier.fillMaxSize().padding(top = d(90), bottom = d(20)))
                else -> Image(
                    painterResource(R.drawable.car_f11), "BMW F11",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(0.95f).padding(top = d(70)),
                )
            }

            Row(Modifier.padding(start = d(34), top = d(30)), verticalAlignment = Alignment.Bottom) {
                Text(speed?.toString() ?: "—", color = Txt, fontSize = s(96), fontWeight = FontWeight.Light)
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
            if (car.beltWarn) {
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
                        Box(Modifier.fillMaxWidth((fuel / CarData.TANK_L).coerceIn(0f, 1f)).fillMaxHeight().background(if (fuel < 10) Warn else Accent))
                    }
                }
            }

            Row(Modifier.align(Alignment.BottomEnd).padding(d(26)), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(d(18))).background(CardHi)
                        .combinedClickable(onClick = { openCarScreen(act) })
                        .padding(horizontal = d(16), vertical = d(14)),
                ) { Text("iDrive", color = Txt, fontSize = s(24), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(d(14)))
                if (model.isNotBlank() && car.open.isEmpty()) {
                    Box(
                        Modifier.clip(RoundedCornerShape(d(18))).background(if (show3d) Accent else CardHi)
                            .combinedClickable(onClick = { show3d = !show3d; Prefs.show3d = show3d })
                            .padding(d(14)),
                    ) { Text("3D", color = if (show3d) Color(0xFF031426) else Txt, fontSize = s(26), fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(d(14)))
                }
                val left = Apps.find(apps, Prefs.splitLeft)
                val right = Apps.find(apps, Prefs.splitRight)
                Row(
                    Modifier.clip(RoundedCornerShape(d(18))).background(CardHi)
                        .border(1.dp, Line, RoundedCornerShape(d(18)))
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
fun NavBlock(modifier: Modifier, slot: Slot, act: MainActivity, onAddress: (Boolean) -> Unit) {
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
                            Box(Modifier.size(d(if (compact) 64 else 84)).clip(RoundedCornerShape(d(20))).background(CardHi), contentAlignment = Alignment.Center) {
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
            if (compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(d(12))) {
                    DestButton(Icons.Outlined.Home, if (slot.w > 1) "Dom" else "", Prefs.home, false, act, Modifier.weight(1f)) { onAddress(false) }
                    DestButton(Icons.Outlined.Work, if (slot.w > 1) "Praca" else "", Prefs.work, true, act, Modifier.weight(1f)) { onAddress(true) }
                }
            } else {
                DestButton(Icons.Outlined.Home, "Dom", Prefs.home, false, act) { onAddress(false) }
                Spacer(Modifier.height(d(14)))
                DestButton(Icons.Outlined.Work, "Praca", Prefs.work, true, act) { onAddress(true) }
            }
        }
    }
}

@Composable
fun DestButton(icon: ImageVector, name: String, address: String, work: Boolean, act: MainActivity, modifier: Modifier = Modifier.fillMaxWidth(), onEdit: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(d(18))).background(CardHi)
            .combinedClickable(
                // bez własnego adresu: Dom/Praca zapisane w Mapach Google; przytrzymanie = własny adres
                onClick = {
                    if (address.isNotBlank()) navigateTo(act, address)
                    else if (!navigateSaved(act, work)) android.widget.Toast.makeText(
                        act, "Dom i Praca z Map Google działają, gdy Kokpit F11 jest ekranem głównym. Przytrzymaj, aby wpisać adres.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                },
                onLongClick = onEdit,
            )
            .padding(horizontal = d(20), vertical = d(18)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(d(30)))
        if (name.isNotEmpty()) {
            Spacer(Modifier.width(d(14)))
            Text(name, color = Txt, fontSize = s(24))
        }
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
                    Text(live, color = Txt, fontSize = s(24), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (playing) "● NA ŻYWO" else "łączenie…",
                        color = if (playing) Color(0xFFFF4D5E) else Dim, fontSize = s(18), fontWeight = FontWeight.SemiBold,
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
                        Icon(Icons.Outlined.ChevronLeft, "Poprzednia", tint = Txt,
                            modifier = Modifier.size(d(52)).combinedClickable(onClick = { act.setLiveCamera(list[(i - 1 + list.size) % list.size]) }))
                        Spacer(Modifier.weight(1f))
                        Text("${i + 1} / ${list.size}", color = Dim, fontSize = s(20))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, "Następna", tint = Txt,
                            modifier = Modifier.size(d(52)).combinedClickable(onClick = { act.setLiveCamera(list[(i + 1) % list.size]) }))
                    }
                }
            }
            id != -1 && act.widgetManager.getAppWidgetInfo(id) != null -> CameraWidget(act, id)
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

@Composable
fun CameraWidget(act: MainActivity, id: Int) {
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

// ---------- pogoda ----------

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
