@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
private fun Chip(text: String, on: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.kokpitSurface(d(14), active = on).combinedClickable(onClick = onClick).padding(horizontal = d(18), vertical = d(10)),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color ?: if (on) LocalTheme.current.activeText else Txt, fontSize = s(20), maxLines = 1) }
}

/** Okno reguły: warunek, tekst dla asystenta, przerwa między komunikatami; zapis, usunięcie. */
@Composable
fun RuleDialog(rule: Rule, isNew: Boolean, act: MainActivity, onClose: () -> Unit) {
    var r by remember { mutableStateOf(rule) }
    var group by remember { mutableStateOf(rule.source.group) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.8f).clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block)
                .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28)))
                .verticalScroll(rememberScrollState()).padding(d(28)),
            verticalArrangement = Arrangement.spacedBy(d(14)),
        ) {
            Text(if (isNew) "Nowa reguła" else "Reguła", color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium)
            // 1. co sprawdzać
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d(10)),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Jeśli", color = Dim, fontSize = s(22))
                val usable = RuleSource.entries.filter { Radio.witstek || it == r.source || !it.witstekOnly }
                RuleSource.GROUPS.filter { g -> usable.any { it.group == g } }.forEach { g ->
                    Chip(g, on = group == g) {
                        group = g
                        // jedyne źródło w grupie wybiera się od razu
                        usable.filter { it.group == g }.singleOrNull()?.let { src -> if (src != r.source) r = r.copy(source = src, value = "", slackTs = "", ai = r.ai || src.camera) }
                    }
                }
            }
            // 1b. źródła z wybranej grupy
            val inGroup = RuleSource.entries.filter { it.group == group && (Radio.witstek || it == r.source || !it.witstekOnly) }
            if (inGroup.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d(10)),
                verticalAlignment = Alignment.CenterVertically) {
                inGroup.forEach { src ->
                    Chip(src.title, on = r.source == src) {
                        // przy zmianie rodzaju źródła (liczba ↔ tekst ↔ zdarzenie) stara wartość nie pasuje
                        val sameKind = src.slack == r.source.slack && src.camera == r.source.camera && src.event == r.source.event && src.unit == r.source.unit
                        r = r.copy(source = src, value = if (sameKind) r.value else "", ai = r.ai || src.camera, slackTs = "")
                    }
                }
            }
            // 2. próg albo osoba / kanał
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                if (r.source.camera) {
                    OutlinedTextField(
                        r.value, { r = r.copy(value = it) }, singleLine = true, modifier = Modifier.width(d(520)),
                        placeholder = { Text(r.source.target) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Text("sprawdzaj", color = Dim, fontSize = s(20))
                    Chip("co ustawioną przerwę", on = r.moment == 0) { r = r.copy(moment = 0) }
                    Chip("po ruszeniu", on = r.moment == 1) { r = r.copy(moment = 1) }
                    Chip("o godzinie", on = r.moment == 2) { r = r.copy(moment = 2) }
                    if (r.moment == 2) OutlinedTextField(
                        r.time, { r = r.copy(time = it) }, singleLine = true, modifier = Modifier.width(d(170)),
                        placeholder = { Text("8:00") }, isError = r.minuteOfDay() == null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                } else if (r.source.event) {
                    Text(r.source.hint, color = Dim, fontSize = s(20))
                } else if (!r.source.slack) {
                    if (r.source.who.isNotEmpty()) OutlinedTextField(
                        r.who, { r = r.copy(who = it) }, singleLine = true, modifier = Modifier.width(d(480)),
                        placeholder = { Text(r.source.who) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    Chip(if (r.source.who.isNotEmpty()) "bliżej niż" else "mniej niż", on = r.less) { r = r.copy(less = true) }
                    Chip(if (r.source.who.isNotEmpty()) "dalej niż" else "więcej niż", on = !r.less) { r = r.copy(less = false) }
                    OutlinedTextField(
                        r.value, { r = r.copy(value = it) }, singleLine = true, modifier = Modifier.width(d(260)),
                        placeholder = { Text("liczba") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    )
                    Text(r.source.unit, color = Txt, fontSize = s(22))
                    val now = Rules.current(r, act)
                    Text(if (now != null) "teraz: ${Rules.format(now)} ${r.source.unit}" else "teraz: brak danych", color = Dim, fontSize = s(20),
                        modifier = Modifier.padding(start = d(16)))
                } else if (r.source.target.isNotEmpty()) {
                    OutlinedTextField(
                        r.value, { r = r.copy(value = it, slackTs = "") }, singleLine = true, modifier = Modifier.width(d(700)),
                        placeholder = { Text(r.source.target) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    if (!Slack.configured) Text("Slack nie jest połączony (Asystent AI → Slack)", color = Warn, fontSize = s(20))
                } else {
                    Text(if (Slack.configured) "Sprawdzane co minutę we wszystkich rozmowach" else "Slack nie jest połączony (Asystent AI → Slack)",
                        color = if (Slack.configured) Dim else Warn, fontSize = s(20))
                }
            }
            // 3. co ma powiedzieć / zrobić
            OutlinedTextField(
                r.text, { r = r.copy(text = it) }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(if (r.source.camera) "Co sprawdzić na zdjęciu i kiedy się odezwać, np.: Jeśli furtka jest otwarta, powiedz mi o tym"
                    else if (r.source == RuleSource.ENGINE_FAULT) "Co ma zrobić asystent, np.: Powiedz, co to za usterka i czy mogę jechać dalej"
                    else if (r.source == RuleSource.PHONE_MISSED) "Co ma zrobić asystent, np.: Powiedz, kto dzwonił, i zapytaj, czy oddzwonić"
                    else if (r.source == RuleSource.FAMILY_DIST) "Co ma zrobić asystent, np.: Powiedz, że Wojtek jest już blisko"
                    else if (r.source == RuleSource.CAL_NEXT) "Co ma zrobić asystent, np.: Przypomnij o terminie i powiedz, ile zajmie dojazd"
                    else if (r.source == RuleSource.TRIP_TIME) "Co ma zrobić asystent, np.: Zaproponuj przerwę i znajdź stację z kawą po drodze"
                    else if (r.ai) "Co ma zrobić asystent, np.: Powiedz, że trzeba zatankować, i zaproponuj najbliższą stację"
                    else "Tekst do przeczytania, np.: Zostało {wartość} litrów paliwa, jedź na stację")
                },
            )
            // 4. sposób i przerwa
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d(10))) {
                if (r.source.camera) {
                    Text("Każde sprawdzenie to płatne zapytanie do asystenta ze zdjęciem.", color = Dim, fontSize = s(20))
                } else {
                    Chip("Asystent wykonuje polecenie", on = r.ai) { r = r.copy(ai = true) }
                    Chip("Czytaj dokładnie ten tekst", on = !r.ai) { r = r.copy(ai = false) }
                }
                Text(if (r.source.camera && r.moment == 0) "co" else "nie częściej niż co", color = Dim, fontSize = s(20), modifier = Modifier.padding(start = d(16)))
                val ci = Rule.COOLDOWNS.indexOfFirst { it.first == r.cooldownMin }.coerceAtLeast(0)
                Chip(Rule.COOLDOWNS[ci].second) { r = r.copy(cooldownMin = Rule.COOLDOWNS[(ci + 1) % Rule.COOLDOWNS.size].first) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(10))) {
                Chip(if (r.on) "Włączona" else "Wyłączona", on = r.on) { r = r.copy(on = !r.on) }
                if (!isNew) Chip("Usuń", color = Warn) { Rules.remove(r.id); onClose() }
                Spacer(Modifier.weight(1f))
                if (!r.complete()) Text("Uzupełnij warunek i tekst", color = Dim, fontSize = s(20))
                Chip("Anuluj") { onClose() }
                Chip("Zapisz", on = true) {
                    if (r.complete()) { Rules.put(r.copy(value = r.value.trim(), who = r.who.trim(), text = r.text.trim())); onClose() }
                }
            }
        }
    }
}
