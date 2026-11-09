@file:OptIn(ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Pełnoekranowe okno nad launcherem z tytułem i przyciskiem zamknięcia. */
@Composable
fun Sheet(title: String, onClose: () -> Unit, widthFraction: Float = 0.9f, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(widthFraction).fillMaxSize(0.92f)
                .clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block).padding(d(30))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Txt, fontSize = s(34), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(d(64)).kokpitSurface(d(32))
                        .combinedClickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, "Zamknij", tint = Txt, modifier = Modifier.size(d(36))) }
            }
            Spacer(Modifier.height(d(20)))
            content()
        }
    }
}

@Composable
fun AppPickerDialog(title: String, apps: List<AppEntry>, onClose: () -> Unit, onPick: (String) -> Unit) {
    Sheet(title, onClose) {
        LazyVerticalGrid(
            GridCells.Adaptive(d(190)),
            verticalArrangement = Arrangement.spacedBy(d(14)),
            horizontalArrangement = Arrangement.spacedBy(d(14)),
        ) {
            if (title.startsWith("Dodaj") || title.startsWith("Zamień")) {
                item {
                    PickerCell(null, "Wszystkie aplikacje") { onPick(APPS_DRAWER) }
                }
            }
            items(apps, key = { it.key }) { app ->
                PickerCell(app, app.label) { onPick(app.key) }
            }
        }
    }
}

@Composable
private fun PickerCell(app: AppEntry?, label: String, onClick: () -> Unit) {
    Column(
        Modifier.kokpitSurface(d(20))
            .combinedClickable(onClick = onClick).padding(vertical = d(20), horizontal = d(10)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (app != null) Image(app.icon, label, Modifier.size(d(80)))
        else Box(Modifier.size(d(80)).clip(RoundedCornerShape(d(20))).background(Color(0xFF2B3340)))
        Spacer(Modifier.height(d(10)))
        Text(label, color = Txt, fontSize = s(20), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun ShortcutMenuDialog(
    onClose: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    Sheet("Skrót", onClose, widthFraction = 0.4f) {
        Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
            MenuRow("Zamień na inną aplikację", onClick = onReplace)
            MenuRow("Usuń skrót", color = Color(0xFFFF6B6B), onClick = onRemove)
            Text("Aby przesunąć skrót, przytrzymaj go i przeciągnij", color = Dim, fontSize = s(20))
        }
    }
}

@Composable
fun MenuRow(title: String, sub: String? = null, color: Color = Txt, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().kokpitSurface(d(18))
            .combinedClickable(onClick = onClick).padding(horizontal = d(26), vertical = d(20))
    ) {
        Text(title, color = color, fontSize = s(26))
        if (sub != null) Text(sub, color = Dim, fontSize = s(20), modifier = Modifier.padding(top = d(4)))
    }
}

@Composable
fun SettingsDialog(
    act: MainActivity,
    apps: List<AppEntry>,
    demo: Boolean,
    onClose: () -> Unit,
    onPicker: (Dlg.Picker) -> Unit,
    onAddress: (Boolean) -> Unit,
    onCameras: () -> Unit,
    onWidgets: () -> Unit,
    onEditLayout: () -> Unit,
    onDemo: (Boolean) -> Unit,
    onResetShortcuts: () -> Unit,
) {
    val tick = act.resumeTick.intValue
    val navOn = remember(tick) { NavListener.isEnabled(act) }
    val splitOn = remember(tick) { SplitService.instance != null }
    val locOn = remember(tick) { act.locationGranted() }
    var splitLeft by remember { mutableStateOf(Prefs.splitLeft) }
    var splitRight by remember { mutableStateOf(Prefs.splitRight) }
    val live by act.liveCamera
    val model by act.carModelPath
    fun open(action: String) = try {
        act.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
    fun label(key: String) = Apps.find(apps, key)?.label ?: "nie wybrano"
    var group by remember { mutableStateOf(Prefs.settingsGroup.coerceIn(0, SETTINGS_GROUPS.size - 1)) }

    Sheet("Ustawienia", onClose, widthFraction = 0.94f) {
        Row(Modifier.fillMaxSize()) {
            // menu grup po lewej; pokrętłem iDrive najpierw wybierasz grupę, potem kafelek
            Column(Modifier.width(d(400)).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d(10))) {
                SETTINGS_GROUPS.forEachIndexed { i, (title, icon) ->
                    val on = i == group
                    Row(
                        Modifier.fillMaxWidth().kokpitSurface(d(18), fill = on, active = on)
                            .combinedClickable(onClick = { group = i; Prefs.settingsGroup = i })
                            .padding(horizontal = d(22), vertical = d(18)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (on) LocalTheme.current.activeText else Accent, modifier = Modifier.size(d(32)))
                        Spacer(Modifier.width(d(16)))
                        Text(title, color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(24), fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Kokpit F11 0.3.5", color = Dim, fontSize = s(18), modifier = Modifier.padding(start = d(22), top = d(10)))
            }
            Spacer(Modifier.width(d(26)))
            LazyVerticalGrid(
                GridCells.Adaptive(d(440)),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(d(14)),
                horizontalArrangement = Arrangement.spacedBy(d(14)),
            ) {
                when (group) {
                    0 -> {
                items(Themes.all, key = { "theme-" + it.id }) { t ->
                    ThemeTile(t, selected = themeState.value.id == t.id) {
                        themeState.value = t
                        Prefs.theme = t.id
                    }
                }
                    }
                    1 -> {
                item { SettingTile(Icons.Outlined.Tune, "Ustawienia radia Witstek", "Dźwięk, system, nawigacja; fabryczne z PIN-em 8888") { openRadioSettings(act) } }
                item { SettingTile(Icons.Outlined.Settings, "Ustawienia Androida", "Wi-Fi, Bluetooth, aplikacje") { open(Settings.ACTION_SETTINGS) } }
                item { SettingTile(Icons.Outlined.Home, "Ekran główny", "Wybierz „Kokpit F11” jako domyślny launcher") { open(Settings.ACTION_HOME_SETTINGS) } }
                if (!locOn) item { SettingTile(Icons.Outlined.MyLocation, "Lokalizacja", "Potrzebna do prędkości i pogody", on = false) { act.requestLocation() } }
                    }
                    2 -> {
                item { SettingTile(Icons.Outlined.DashboardCustomize, "Edytuj układ bloków", "Przesuwanie, rozmiar, ukrywanie – tylko na postoju") { onEditLayout() } }
                item { SettingTile(Icons.Outlined.RestartAlt, "Przywróć domyślne skróty", "Zastępuje obecne skróty") { onResetShortcuts(); onClose() } }
                if (act.blockWidgetId.intValue != -1) {
                    item { SettingTile(Icons.Outlined.Widgets, "Widżet: zmień", "Wybierz inny widżet do bloku „Widżet”") { onWidgets() } }
                    item { SettingTile(Icons.Outlined.Delete, "Widżet: usuń", "Blok „Widżet” wróci do przycisku dodawania") { act.removeBlockWidget() } }
                }
                    }
                    3 -> {
                item {
                    SettingTile(Icons.Outlined.Navigation, "Podgląd trasy", if (navOn) "Manewry z Map Google i Waze" else "Zezwól na dostęp do powiadomień", on = navOn) {
                        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                }
                item { SettingTile(Icons.Outlined.Home, "Adres: Dom", Prefs.home.ifBlank { "z Map Google (zapisany Dom)" }) { onAddress(false) } }
                item { SettingTile(Icons.Outlined.Work, "Adres: Praca", Prefs.work.ifBlank { "z Map Google (zapisana Praca)" }) { onAddress(true) } }
                    }
                    4 -> {
                item {
                    SettingTile(Icons.Outlined.VerticalSplit, "Usługa podzielonego ekranu", if (splitOn) "Włączona" else "Włącz „Kokpit F11 – podzielony ekran” w ułatwieniach dostępu", on = splitOn) {
                        open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                }
                item { SettingTile(Icons.Outlined.VerticalSplit, "Lewa strona", label(splitLeft)) { onPicker(Dlg.Picker("Lewa strona ekranu") { Prefs.splitLeft = it; splitLeft = it }) } }
                item { SettingTile(Icons.Outlined.VerticalSplit, "Prawa strona", label(splitRight)) { onPicker(Dlg.Picker("Prawa strona ekranu") { Prefs.splitRight = it; splitRight = it }) } }
                    }
                    5 -> {
                item { SettingTile(Icons.Outlined.Videocam, "Kamera na żywo", live.ifBlank { "wyłączona" }, on = live.isNotBlank()) { onCameras() } }
                item {
                    SettingTile(Icons.Outlined.Widgets, "Widżet Xiaomi Cam", if (act.cameraWidgetId.intValue != -1) "Ustawiony (gdy obraz na żywo wyłączony)" else "Brak widżetu") {
                        onClose(); act.addCameraWidget()
                    }
                }
                if (act.cameraWidgetId.intValue != -1) item { SettingTile(Icons.Outlined.Delete, "Usuń widżet kamery", "Blok kamery wróci do wyboru") { act.removeCameraWidget() } }
                    }
                    6 -> {
                item {
                    SettingTile(Icons.Outlined.Warning, "Ostrzeżenie „Zapnij pasy”", if (beltOn.value) "Tylko w czasie jazdy; sygnał z CAN może obejmować puste fotele" else "Ostrzega zestaw wskaźników BMW", on = beltOn.value) {
                        beltOn.value = !beltOn.value; Prefs.beltWarn = beltOn.value
                    }
                }
                item { SettingTile(Icons.Outlined.PlayCircle, "Pokaz danych auta", if (demo) "Paliwo i drzwi to dane przykładowe" else "Paliwo i drzwi z modułu CAN radia", on = demo) { onDemo(!demo) } }
                item { SettingTile(Icons.Outlined.ViewInAr, "Model 3D: wczytaj własny", if (model.isBlank()) "Wybierz plik .glb (np. z pendrive’a)" else "Wczytany – dotknij, aby zamienić") { act.pickCarModel() } }
                item { SettingTile(Icons.Outlined.DirectionsCar, "Model 3D: białe F11", "Wbudowany model, obracany palcem") { act.restoreCarModel() } }
                if (model.isNotBlank()) item { SettingTile(Icons.Outlined.Delete, "Usuń model 3D", "Wróci zdjęcie auta") { act.removeCarModel() } }
                    }
                }
            }
        }
    }
}

/** Grupy ustawień w menu po lewej (kolejność = numery w SettingsDialog). */
private val SETTINGS_GROUPS = listOf(
        "Wygląd" to Icons.Outlined.Palette,
        "Radio i system" to Icons.Outlined.Tune,
        "Ekran" to Icons.Outlined.DashboardCustomize,
        "Nawigacja" to Icons.Outlined.Navigation,
        "Podzielony ekran" to Icons.Outlined.VerticalSplit,
        "Kamera" to Icons.Outlined.Videocam,
        "Auto" to Icons.Outlined.DirectionsCar,
)

/** Kafelek ustawienia: ikona, tytuł, opis; `on` pokazuje stan przełącznika (Wł./Wył.). */
@Composable
fun SettingTile(icon: ImageVector, title: String, sub: String? = null, on: Boolean? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(d(170)).kokpitSurface(d(20))
            .combinedClickable(onClick = onClick).padding(horizontal = d(22), vertical = d(18)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(d(34)))
            Spacer(Modifier.weight(1f))
            if (on != null) {
                Text(
                    if (on) "WŁ." else "WYŁ.", color = if (on) Color(0xFF031426) else Dim, fontSize = s(16), fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(d(10))).background(if (on) Accent else Color(0x1FFFFFFF))
                        .padding(horizontal = d(10), vertical = d(3)),
                )
            }
        }
        Spacer(Modifier.height(d(12)))
        Text(title, color = Txt, fontSize = s(24), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = Dim, fontSize = s(18), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = d(4)))
    }
}

/** Kafelek wyglądu z małym podglądem: tło ekranu, blok, kafelek i kafelek wybrany pokrętłem. */
@Composable
fun ThemeTile(t: KokpitTheme, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().kokpitSurface(d(20), active = false)
            .then(if (selected) Modifier.border(d(3), Accent, RoundedCornerShape(d(20))) else Modifier)
            .combinedClickable(onClick = onClick).padding(d(14)),
    ) {
        Box(Modifier.fillMaxWidth().height(d(120)).clip(RoundedCornerShape(d(14))).background(t.screen).padding(d(12))) {
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(d(12))).background(t.block).border(1.dp, t.blockEdge, RoundedCornerShape(d(12))).padding(d(12)),
                horizontalArrangement = Arrangement.spacedBy(d(10)),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(d(10))).background(t.surface).then(t.surfaceEdge?.let { Modifier.border(1.dp, it, RoundedCornerShape(d(10))) } ?: Modifier))
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(d(10))).background(t.selected).border(d(3), t.selectedRing, RoundedCornerShape(d(10))))
                Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text("48", color = Txt, fontSize = s(34), fontWeight = FontWeight.Light,
                        style = t.speed?.let { androidx.compose.ui.text.TextStyle(brush = it) } ?: androidx.compose.ui.text.TextStyle.Default)
                    Box(Modifier.padding(top = d(6)).fillMaxWidth().height(d(8)).clip(RoundedCornerShape(d(4))).background(Color(0x14FFFFFF))) {
                        Box(Modifier.fillMaxWidth(0.6f).fillMaxHeight().background(t.fuel))
                    }
                }
            }
        }
        Spacer(Modifier.height(d(10)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, color = Txt, fontSize = s(24), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Outlined.CheckCircle, "Wybrany", tint = Accent, modifier = Modifier.size(d(30)))
        }
        Text(t.description, color = Dim, fontSize = s(18), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Wybór kamery do podglądu na żywo z serwera aplikacji Xiaomi Cam działającej na radiu. */
@Composable
fun CameraPickerDialog(act: MainActivity, onClose: () -> Unit) {
    val cameras by produceState<List<String>?>(null) { value = XiaomiCamServer.camerasWaking(act) ?: emptyList() }
    Sheet("Kamera na żywo", onClose, widthFraction = 0.5f) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d(14))) {
            when {
                cameras == null -> Text("Szukam kamer w Xiaomi Cam…", color = Dim, fontSize = s(24))
                cameras!!.isEmpty() -> {
                    Text("Nie widzę kamer z Xiaomi Cam. Otwórz Xiaomi Cam, dodaj kamerę i w jej menu włącz „Obraz dla innych aplikacji w tle”, potem wróć tutaj.", color = Dim, fontSize = s(24))
                    MenuRow("Otwórz Xiaomi Cam") { Apps.launch(act, MainActivity.XIAOMI_CAM) }
                }
                else -> cameras!!.forEach { name ->
                    MenuRow(name, if (name == act.liveCamera.value) "wybrana" else null) { act.setLiveCamera(name); onClose() }
                }
            }
            if (act.liveCamera.value.isNotBlank()) {
                MenuRow("Wyłącz obraz na żywo", "Pokaż widżet Xiaomi Cam") { act.setLiveCamera(""); onClose() }
            }
        }
    }
}

@Composable
fun AddressDialog(work: Boolean, onClose: () -> Unit) {
    var text by remember { mutableStateOf(if (work) Prefs.work else Prefs.home) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text(if (work) "Adres pracy" else "Adres domu", color = Txt) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("ul. Przykładowa 1, Warszawa") })
        },
        confirmButton = {
            TextButton(onClick = {
                if (work) Prefs.work = text.trim() else Prefs.home = text.trim()
                onClose()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } },
    )
}


/** Widżet do klocka „Widżet”: lista dostawców widżetów z zainstalowanych aplikacji. */
private class WidgetChoice(val provider: android.content.ComponentName, val label: String, val app: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

@Composable
fun WidgetPickerDialog(act: MainActivity, onClose: () -> Unit, onPick: (android.content.ComponentName) -> Unit) {
    val list by produceState<List<WidgetChoice>?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = act.packageManager
            val dpi = act.resources.displayMetrics.densityDpi
            act.widgetManager.installedProviders.mapNotNull { info ->
                try {
                    val app = pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                    val icon = (info.loadIcon(act, dpi) ?: pm.getApplicationIcon(info.provider.packageName))
                        .toBitmap(96, 96).asImageBitmap()
                    WidgetChoice(info.provider, info.loadLabel(pm) ?: app, app, icon)
                } catch (_: Exception) {
                    null
                }
            }.sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
        }
    }
    Sheet("Wybierz widżet", onClose) {
        when {
            list == null -> Text("Wczytywanie…", color = Dim, fontSize = s(22))
            list!!.isEmpty() -> Text("Żadna aplikacja nie ma widżetów", color = Dim, fontSize = s(22))
            else -> LazyVerticalGrid(
                GridCells.Adaptive(d(260)),
                verticalArrangement = Arrangement.spacedBy(d(14)),
                horizontalArrangement = Arrangement.spacedBy(d(14)),
            ) {
                items(list!!, key = { it.provider.flattenToString() }) { w ->
                    Column(
                        Modifier.kokpitSurface(d(20))
                            .combinedClickable(onClick = { onPick(w.provider) }).padding(vertical = d(20), horizontal = d(12)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (w.icon != null) Image(w.icon, w.label, Modifier.size(d(80)))
                        Spacer(Modifier.height(d(10)))
                        Text(w.label, color = Txt, fontSize = s(20), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(w.app, color = Dim, fontSize = s(17), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
