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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    onPlace3: () -> Unit,
    onCameras: () -> Unit,
    onEditLayout: () -> Unit,
    onAiField: (AiField) -> Unit,
    onVoices: () -> Unit,
    onCustom: (CustomField) -> Unit,
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
    var aiProvider by remember { mutableStateOf(Prefs.aiProvider) }
    var aiSpeak by remember { mutableStateOf(Prefs.aiSpeak) }
    val ttsEngine = Prefs.ttsEngine
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
                Text("Kokpit F11 0.3.8", color = Dim, fontSize = s(18), modifier = Modifier.padding(start = d(22), top = d(10)))
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
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(d(14)), modifier = Modifier.padding(top = d(6))) {
                        listOf(false to "Ciemny", true to "Jasny").forEach { (light, label) ->
                            val on = LightMode.on == light
                            Row(
                                Modifier.kokpitSurface(d(18), active = on).combinedClickable(onClick = { LightMode.set(light) })
                                    .padding(horizontal = d(26), vertical = d(16)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (light) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null,
                                    tint = if (on) LocalTheme.current.activeText else Accent, modifier = Modifier.size(d(30)))
                                Spacer(Modifier.width(d(12)))
                                Text("Tryb $label".lowercase().replaceFirstChar { it.uppercase() }, color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(22))
                            }
                        }
                    }
                }
                val c = ThemeCustoms.current()
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text("Dostosuj: ${themeState.value.name}", color = Txt, fontSize = s(24), fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = d(14)))
                }
                fun colorSub(v: Long?) = if (v == null) "Jak w wyglądzie" else "Własny kolor"
                item { SettingTile(Icons.Outlined.AutoAwesome, "Kolor poświaty", colorSub(c.glow) + " – tło, krawędzie, blask pod autem") { onCustom(CustomField.GLOW) } }
                item { SettingTile(Icons.Outlined.Wallpaper, "Kolor tła", colorSub(c.screen)) { onCustom(CustomField.SCREEN) } }
                item { SettingTile(Icons.Outlined.Palette, "Kolor akcentu", colorSub(c.accent) + " – zaznaczenie, przyciski, ikony") { onCustom(CustomField.ACCENT) } }
                item { SettingTile(Icons.Outlined.TextFields, "Krój pisma", Fonts.all.firstOrNull { it.first == c.font }?.second ?: "Domyślny") { onCustom(CustomField.FONT) } }
                item { SettingTile(Icons.Outlined.FormatSize, "Rozmiar pisma", "${(c.fontScale * 100).toInt()} %") { onCustom(CustomField.SIZE) } }
                if (!c.isDefault) item { SettingTile(Icons.Outlined.RestartAlt, "Przywróć wygląd", "Usuwa zmiany dla „${themeState.value.name}”") { ThemeCustoms.set(themeState.value.id, ThemeCustom()) } }
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
                    }
                    3 -> {
                item {
                    SettingTile(Icons.Outlined.Navigation, "Podgląd trasy", if (navOn) "Manewry z Map Google i Waze" else "Zezwól na dostęp do powiadomień", on = navOn) {
                        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    }
                }
                item { SettingTile(Icons.Outlined.Home, "Adres: Dom", Prefs.home.ifBlank { "z Map Google (zapisany Dom)" }) { onAddress(false) } }
                item { SettingTile(Icons.Outlined.Work, "Adres: Praca", Prefs.work.ifBlank { "z Map Google (zapisana Praca)" }) { onAddress(true) } }
                item { SettingTile(Icons.Outlined.Place, "Trzecie miejsce: " + Prefs.place3Name.ifBlank { "wyłączone" }, Prefs.place3Address.ifBlank { "etykieta wyszukana w Mapach Google" }) { onPlace3() } }
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
                    7 -> {
                val claude = aiProvider == Assistant.ANTHROPIC
                item {
                    SettingTile(Icons.Outlined.SmartToy, "Dostawca: " + if (claude) "Anthropic (Claude)" else "OpenAI", "Dotknij, aby zmienić na " + if (claude) "OpenAI" else "Anthropic (Claude)") {
                        aiProvider = if (claude) Assistant.OPENAI else Assistant.ANTHROPIC
                        Prefs.aiProvider = aiProvider
                        Assistant.reset()
                    }
                }
                item {
                    val key = if (claude) Prefs.anthropicKey else Prefs.openaiKey
                    SettingTile(Icons.Outlined.Key, "Klucz API " + if (claude) "Anthropic" else "OpenAI",
                        if (key.isBlank()) (if (claude) "Nie wpisano – console.anthropic.com" else "Nie wpisano – platform.openai.com") else "Wpisany: …" + key.takeLast(4),
                        on = key.isNotBlank()) { onAiField(if (claude) AiField.ANTHROPIC_KEY else AiField.OPENAI_KEY) }
                }
                item {
                    SettingTile(Icons.Outlined.Tune, "Model", if (claude) Prefs.anthropicModel else Prefs.openaiModel) {
                        onAiField(if (claude) AiField.ANTHROPIC_MODEL else AiField.OPENAI_MODEL)
                    }
                }
                item {
                    SettingTile(Icons.Outlined.RecordVoiceOver, "Czytaj odpowiedzi na głos", if (aiSpeak) "Odpowiedzi czytane po polsku" else "Odpowiedzi tylko na ekranie", on = aiSpeak) {
                        aiSpeak = !aiSpeak; Prefs.aiSpeak = aiSpeak
                    }
                }
                item {
                    val oa = ttsEngine == Speech.OPENAI
                    SettingTile(Icons.Outlined.GraphicEq, "Głos: " + if (oa) "OpenAI (${Prefs.openaiVoice})" else "Google w radiu",
                        if (oa) "Najbardziej ludzki; płatny, klucz OpenAI. Dotknij, aby wybrać" else "Dotknij, aby wybrać głos albo OpenAI") { onVoices() }
                }
                item { SettingTile(Icons.Outlined.RestartAlt, "Nowa rozmowa", "Asystent zapomni dotychczasową rozmowę") { Assistant.reset() } }
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
        "Asystent AI" to Icons.Outlined.SmartToy,
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

/** Co zmienia okienko Dostosuj. */
enum class CustomField(val title: String) {
    GLOW("Kolor poświaty"), SCREEN("Kolor tła"), ACCENT("Kolor akcentu"), FONT("Krój pisma"), SIZE("Rozmiar pisma"),
}

/** Dostosuj bieżący wygląd: kolor (próbki), krój albo rozmiar pisma; zmiana widać od razu. */
@Composable
fun CustomizeDialog(field: CustomField, onClose: () -> Unit) {
    val id = themeState.value.id
    val c = ThemeCustoms.current()
    fun save(n: ThemeCustom) = ThemeCustoms.set(id, n)
    Sheet(field.title + " · " + themeState.value.name, onClose) {
        when (field) {
            CustomField.GLOW, CustomField.SCREEN, CustomField.ACCENT -> {
                val current = when (field) { CustomField.GLOW -> c.glow; CustomField.SCREEN -> c.screen; else -> c.accent }
                val list = if (field == CustomField.SCREEN) Swatches.dark else Swatches.bright
                fun pick(v: Long?) = save(when (field) {
                    CustomField.GLOW -> c.copy(glow = v)
                    CustomField.SCREEN -> c.copy(screen = v)
                    else -> c.copy(accent = v)
                })
                LazyVerticalGrid(GridCells.Adaptive(d(150)), verticalArrangement = Arrangement.spacedBy(d(18)), horizontalArrangement = Arrangement.spacedBy(d(18))) {
                    item {
                        Swatch(null, current == null, "Z wyglądu") { pick(null) }
                    }
                    items(list.size) { i -> Swatch(list[i], current == list[i], null) { pick(list[i]) } }
                }
            }
            CustomField.FONT -> LazyVerticalGrid(GridCells.Adaptive(d(440)), verticalArrangement = Arrangement.spacedBy(d(14)), horizontalArrangement = Arrangement.spacedBy(d(14))) {
                items(Fonts.all.size) { i ->
                    val (key, label) = Fonts.all[i]
                    val on = c.font == key
                    Column(
                        Modifier.fillMaxWidth().kokpitSurface(d(18), active = on).combinedClickable(onClick = { save(c.copy(font = key)) }).padding(d(20)),
                    ) {
                        Text(label, color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(26), fontFamily = Fonts.family(key) ?: androidx.compose.ui.text.font.FontFamily.Default)
                        Text("Zażółć gęślą jaźń 12:45", color = if (on) LocalTheme.current.activeText else Dim, fontSize = s(20), fontFamily = Fonts.family(key) ?: androidx.compose.ui.text.font.FontFamily.Default)
                    }
                }
            }
            CustomField.SIZE -> LazyVerticalGrid(GridCells.Adaptive(d(260)), verticalArrangement = Arrangement.spacedBy(d(14)), horizontalArrangement = Arrangement.spacedBy(d(14))) {
                val sizes = listOf(0.85f, 0.92f, 1f, 1.08f, 1.15f, 1.25f, 1.35f)
                items(sizes.size) { i ->
                    val v = sizes[i]
                    val on = kotlin.math.abs(c.fontScale - v) < 0.01f
                    Box(
                        Modifier.fillMaxWidth().height(d(120)).kokpitSurface(d(18), active = on).combinedClickable(onClick = { save(c.copy(fontScale = v)) }),
                        contentAlignment = Alignment.Center,
                    ) { Text("${(v * 100).toInt()} %", color = if (on) LocalTheme.current.activeText else Txt, fontSize = s(28)) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(color: Long?, selected: Boolean, label: String?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(d(110)).clip(RoundedCornerShape(d(20)))
            .background(if (color != null) Color(color) else CardHi)
            .border(if (selected) d(5) else 1.dp, if (selected) Txt else Line, RoundedCornerShape(d(20)))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = Txt, fontSize = s(20))
        else if (selected) Icon(Icons.Outlined.Check, null, tint = if (Color(color!!).let { it.red + it.green + it.blue } > 1.8f) Color.Black else Color.White, modifier = Modifier.size(d(44)))
    }
}

/** Wybór głosu odpowiedzi: polskie głosy systemowe (Google) albo głosy OpenAI, z odsłuchem. */
@Composable
fun VoicePickerDialog(act: MainActivity, onClose: () -> Unit) {
    var engine by remember { mutableStateOf(Prefs.ttsEngine) }
    var sysVoice by remember { mutableStateOf(Prefs.ttsVoice) }
    var oaVoice by remember { mutableStateOf(Prefs.openaiVoice) }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>?>(null) }
    LaunchedEffect(Unit) { Speech.polishVoices(act) { voices = it } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Speech.stop() } }
    Sheet("Głos odpowiedzi", { Speech.stop(); onClose() }) {
        LazyVerticalGrid(
            GridCells.Adaptive(d(380)),
            verticalArrangement = Arrangement.spacedBy(d(14)),
            horizontalArrangement = Arrangement.spacedBy(d(14)),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("Google w radiu – bezpłatnie; głosy „sieciowe” brzmią naturalniej, ale potrzebują internetu", color = Dim, fontSize = s(20))
            }
            val list = voices
            if (list == null) item { Text("Wczytywanie…", color = Dim, fontSize = s(22)) }
            else if (list.isEmpty()) item { Text("Brak polskich głosów – zainstaluj je w ustawieniach Androida (Zamiana tekstu na mowę)", color = Warn, fontSize = s(20)) }
            else items(list.size) { i ->
                val v = list[i]
                val on = engine == Speech.SYSTEM && (sysVoice == v.name || (sysVoice.isBlank() && i == 0))
                SettingTile(Icons.Outlined.RecordVoiceOver, "Głos ${i + 1}" + if (v.isNetworkConnectionRequired) " · sieciowy" else " · w radiu",
                    v.name, on = on) {
                    engine = Speech.SYSTEM; sysVoice = v.name
                    Prefs.ttsEngine = Speech.SYSTEM; Speech.setSystemVoice(v.name)
                    Speech.previewSystem(act, v)
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    if (Prefs.openaiKey.isBlank()) "OpenAI – najbardziej ludzkie głosy; najpierw wpisz klucz API OpenAI (dostawcą rozmowy może zostać Claude)"
                    else "OpenAI – najbardziej ludzkie głosy, płatne według długości tekstu (model ${Prefs.openaiTtsModel})",
                    color = Dim, fontSize = s(20), modifier = Modifier.padding(top = d(10)),
                )
            }
            items(Speech.OPENAI_VOICES.size) { i ->
                val v = Speech.OPENAI_VOICES[i]
                SettingTile(Icons.Outlined.GraphicEq, "OpenAI: $v", "Dotknij, aby odsłuchać i wybrać", on = engine == Speech.OPENAI && oaVoice == v) {
                    if (Prefs.openaiKey.isBlank()) return@SettingTile
                    engine = Speech.OPENAI; oaVoice = v
                    Prefs.ttsEngine = Speech.OPENAI; Prefs.openaiVoice = v
                    Speech.previewOpenAi(act, v)
                }
            }
        }
    }
}

/** Pola asystenta wpisywane w okienku. */
enum class AiField(val title: String, val hint: String, val secret: Boolean) {
    ANTHROPIC_KEY("Klucz API Anthropic", "sk-ant-…", true),
    OPENAI_KEY("Klucz API OpenAI", "sk-…", true),
    ANTHROPIC_MODEL("Model Claude", Assistant.DEFAULT_CLAUDE_MODEL, false),
    OPENAI_MODEL("Model OpenAI", Assistant.DEFAULT_OPENAI_MODEL, false),
}

/** Wpisanie klucza API albo nazwy modelu asystenta; puste pole modelu = domyślny. */
@Composable
fun AiFieldDialog(field: AiField, onClose: () -> Unit) {
    var text by remember {
        mutableStateOf(
            when (field) {
                AiField.ANTHROPIC_KEY -> Prefs.anthropicKey
                AiField.OPENAI_KEY -> Prefs.openaiKey
                AiField.ANTHROPIC_MODEL -> Prefs.anthropicModel
                AiField.OPENAI_MODEL -> Prefs.openaiModel
            }
        )
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text(field.title, color = Txt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
                OutlinedTextField(
                    text, { text = it.trim() }, singleLine = true, placeholder = { Text(field.hint) },
                    // bez podpowiedzi klawiatury: klucz nie trafi do słownika
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (field.secret) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                    ),
                    visualTransformation = if (field.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
                Text(
                    if (field.secret) "Klucz zostaje tylko w radiu. Za zapytania płacisz u dostawcy według zużycia."
                    else "Puste pole = model domyślny (${field.hint}).",
                    color = Dim, fontSize = s(18),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (field) {
                    AiField.ANTHROPIC_KEY -> Prefs.anthropicKey = text
                    AiField.OPENAI_KEY -> Prefs.openaiKey = text
                    AiField.ANTHROPIC_MODEL -> Prefs.anthropicModel = text
                    AiField.OPENAI_MODEL -> Prefs.openaiModel = text
                }
                Assistant.reset()
                onClose()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Anuluj") } },
    )
}

/**
 * Trzecie miejsce w bloku Nawigacja. Nazwa jest też etykietą szukaną w Mapach Google;
 * adres (opcjonalny) daje od razu nawigację. Pusta nazwa chowa przycisk.
 */
@Composable
fun Place3Dialog(onClose: () -> Unit) {
    var name by remember { mutableStateOf(Prefs.place3Name) }
    var address by remember { mutableStateOf(Prefs.place3Address) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        title = { Text("Trzecie miejsce", color = Txt) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nazwa (etykieta w Mapach Google)") }, placeholder = { Text("Praca Gabi") })
                OutlinedTextField(address, { address = it }, singleLine = true, label = { Text("Adres (opcjonalnie)") }, placeholder = { Text("puste = szukaj etykiety w Mapach") })
                Text("Bez adresu Kokpit wyszuka nazwę w Mapach Google – etykieta z Twojego konta pojawi się jako wynik, wtedy „Trasa”. Pusta nazwa chowa przycisk.", color = Dim, fontSize = s(18))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.place3Name = name.trim()
                Prefs.place3Address = address.trim()
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
