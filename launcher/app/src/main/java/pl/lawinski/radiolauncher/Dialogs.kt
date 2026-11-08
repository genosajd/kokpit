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
                .clip(RoundedCornerShape(d(28))).background(CardBg).padding(d(30))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Txt, fontSize = s(34), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(d(64)).clip(RoundedCornerShape(d(32))).background(CardHi)
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
        Modifier.clip(RoundedCornerShape(d(20))).background(CardHi)
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
    index: Int,
    count: Int,
    onClose: () -> Unit,
    onReplace: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Sheet("Skrót", onClose, widthFraction = 0.4f) {
        Column(verticalArrangement = Arrangement.spacedBy(d(14))) {
            MenuRow("Zamień na inną aplikację", onClick = onReplace)
            if (index > 0) MenuRow("Przesuń wcześniej", onClick = { onMove(-1) })
            if (index < count - 1) MenuRow("Przesuń dalej", onClick = { onMove(1) })
            MenuRow("Usuń skrót", color = Color(0xFFFF6B6B), onClick = onRemove)
        }
    }
}

@Composable
fun MenuRow(title: String, sub: String? = null, color: Color = Txt, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d(18))).background(CardHi)
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

    Sheet("Ustawienia", onClose, widthFraction = 0.6f) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(d(14))) {
            MenuRow("Ustawienia radia Witstek", "Dźwięk, system, nawigacja; ustawienia fabryczne w środku (PIN 8888)") { openRadioSettings(act) }
            MenuRow("Ustawienia Androida", "Wi-Fi, Bluetooth, aplikacje, ekran główny") { open(Settings.ACTION_SETTINGS) }
            MenuRow("Ustaw jako ekran główny", "Wybierz „Kokpit F11” jako domyślny launcher") { open(Settings.ACTION_HOME_SETTINGS) }
            MenuRow("Edytuj układ klocków", "Przesuwanie, zmiana rozmiaru, ukrywanie – tylko na postoju") { onEditLayout() }
            MenuRow("Podgląd trasy", if (navOn) "Włączony" else "Wyłączony – zezwól na dostęp do powiadomień") {
                open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            MenuRow("Podzielony ekran", if (splitOn) "Usługa włączona" else "Wyłączony – włącz „Kokpit F11 – podzielony ekran” w ułatwieniach dostępu") {
                open(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            MenuRow("Podzielony ekran: lewa strona", label(splitLeft)) {
                onPicker(Dlg.Picker("Lewa strona ekranu") { Prefs.splitLeft = it; splitLeft = it })
            }
            MenuRow("Podzielony ekran: prawa strona", label(splitRight)) {
                onPicker(Dlg.Picker("Prawa strona ekranu") { Prefs.splitRight = it; splitRight = it })
            }
            MenuRow("Kamera na żywo", live.ifBlank { "wyłączona" }) { onCameras() }
            MenuRow("Kamera: widżet Xiaomi Cam", if (act.cameraWidgetId.intValue != -1) "Ustawiony (gdy obraz na żywo wyłączony)" else "Brak widżetu") {
                onClose(); act.addCameraWidget()
            }
            if (act.cameraWidgetId.intValue != -1) MenuRow("Kamera: usuń widżet") { act.removeCameraWidget() }
            MenuRow("Model 3D auta: wczytaj własny", if (model.isBlank()) "Brak modelu – wybierz plik .glb (np. z pendrive’a)" else "Wczytany – dotknij, aby zamienić na plik .glb") { act.pickCarModel() }
            MenuRow("Model 3D auta: białe F11", "Wbudowany model, obracany palcem") { act.restoreCarModel() }
            if (model.isNotBlank()) MenuRow("Usuń model 3D", "Wróci zdjęcie auta") { act.removeCarModel() }
            MenuRow("Adres: Dom", Prefs.home.ifBlank { "z Map Google (zapisany Dom)" }) { onAddress(false) }
            MenuRow("Adres: Praca", Prefs.work.ifBlank { "z Map Google (zapisana Praca)" }) { onAddress(true) }
            if (!locOn) MenuRow("Lokalizacja", "Potrzebna do prędkości i pogody") { act.requestLocation() }
            MenuRow(
                "Pokaz danych auta", if (demo) "Włączony – paliwo i otwierane drzwi to dane przykładowe" else "Wyłączony – paliwo i drzwi z modułu CAN radia",
            ) { onDemo(!demo) }
            MenuRow("Przywróć domyślne skróty") { onResetShortcuts(); onClose() }
            MenuRow("Ustawienia Androida") { open(Settings.ACTION_SETTINGS) }
            Text("Kokpit F11 0.3.4", color = Dim, fontSize = s(18), modifier = Modifier.padding(top = d(6)))
        }
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

