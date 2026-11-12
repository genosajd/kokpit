@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

/*
 * Ustawienia → Radio i system: eksport wszystkich ustawień Kokpitu do pliku JSON, import z pliku
 * i powrót do stanu początkowego (kasuje też klucze, tokeny i rozmowę z asystentem).
 * Po imporcie i po resecie Kokpit uruchamia się od nowa, żeby wszystko wczytało się z czystego stanu.
 */
object Backup {
    const val FILE_NAME = "kokpit-ustawienia.json"
    private const val MARK = "kokpit_ustawienia"

    /** klucze API i tokeny – eksportowane tylko na wyraźne życzenie */
    val SECRET = setOf("anthropic_key", "openai_key", "tuya_id", "tuya_secret", "slack_token", "google_key", "eleven_key")

    /** stan tego jednego radia, bez sensu w innym: numery widżetów Androida, stan chwilowy */
    private fun local(key: String) = key == "camera_widget" || key.startsWith("block_widget") ||
        key == "nav_popups_hidden" || key == "settings_group" || key == "settings_ai_tab" || key == "eleven_paused_until" || key == "car_model"

    fun export(withSecrets: Boolean, version: String): String {
        val prefs = JSONObject()
        for ((k, v) in Prefs.store.all.toSortedMap()) {
            if (v == null || local(k) || (!withSecrets && k in SECRET)) continue
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            prefs.put(k, o)
        }
        return JSONObject().put(MARK, 1).put("wersja", version).put("klucze", withSecrets)
            .put("data", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
            .put("ustawienia", prefs).toString(2)
    }

    /**
     * Zastępuje ustawienia tymi z pliku. Zostają tylko numery widżetów tego radia oraz klucze,
     * których w pliku nie ma (eksport „bez kluczy”). Rzuca wyjątek, gdy plik nie jest eksportem Kokpitu –
     * wtedy nic się nie zmienia.
     */
    fun import(text: String): Int {
        val j = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("to nie jest plik JSON") }
        val prefs = j.optJSONObject("ustawienia")
        if (!j.has(MARK) || prefs == null) throw IllegalArgumentException("to nie jest eksport ustawień Kokpitu")
        // najpierw cały plik musi się dać odczytać, dopiero potem ruszamy zapisane ustawienia
        val values = ArrayList<Pair<String, Any>>()
        for (k in prefs.keys()) {
            if (local(k)) continue
            val o = prefs.optJSONObject(k) ?: throw IllegalArgumentException("zły wpis „$k”")
            val v: Any = when (o.optString("t")) {
                "s" -> o.getString("v")
                "b" -> o.getBoolean("v")
                "i" -> o.getInt("v")
                "l" -> o.getLong("v")
                "f" -> o.getDouble("v").toFloat()
                "ss" -> o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> throw IllegalArgumentException("zły wpis „$k”")
            }
            values += k to v
        }
        val inFile = values.map { it.first }.toSet()
        val e = Prefs.store.edit()
        for (k in Prefs.store.all.keys) if (!local(k) && !(k in SECRET && k !in inFile)) e.remove(k)
        for ((k, v) in values) when (v) {
            is String -> e.putString(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
        }
        if (!e.commit()) throw IllegalStateException("nie udało się zapisać ustawień")
        return values.size
    }

    /** Stan początkowy: wszystkie ustawienia, klucze i tokeny, rozmowa z asystentem, widżety, pliki tymczasowe. */
    fun reset(act: MainActivity) {
        runCatching { NavPopups.apply(act, false) } // oddaj systemowi wyskakujące powiadomienia, jeśli Kokpit je wyłączył
        runCatching { act.widgetHost.deleteHost() }
        Prefs.store.edit().clear().commit()
        runCatching { java.io.File(act.filesDir, "assistant_chat.json").delete() }
        runCatching { java.io.File(act.filesDir, "assistant_chat.json.tmp").delete() }
        runCatching { act.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        restart(act)
    }

    /** Kokpit od nowa: ustawienia siedzą w wielu obiektach w pamięci, więc najpewniej wczytać je świeżym procesem. */
    fun restart(act: MainActivity) {
        runCatching { act.stopService(Intent(act, HotwordService::class.java)) }
        act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        act.finishAffinity()
        kotlin.system.exitProcess(0)
    }

    fun write(act: MainActivity, uri: Uri, withSecrets: Boolean) {
        try {
            // "wt": krótszy plik zastępuje dłuższy w całości
            act.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(export(withSecrets, act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "").toByteArray()) }
            Toast.makeText(act, if (withSecrets) "Ustawienia zapisane – plik zawiera klucze, nie udostępniaj go" else "Ustawienia zapisane (bez kluczy)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(act, "Nie udało się zapisać pliku: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun read(act: MainActivity, uri: Uri) {
        try {
            val text = act.contentResolver.openInputStream(uri)!!.use { inp ->
                val b = inp.readNBytes(2_000_001)
                if (b.size > 2_000_000) throw IllegalArgumentException("plik jest za duży")
                String(b)
            }
            val n = import(text)
            Toast.makeText(act, "Wczytano $n ustawień – Kokpit uruchamia się od nowa", Toast.LENGTH_LONG).show()
            restart(act)
        } catch (e: Exception) {
            Toast.makeText(act, "Nie wczytano – ${e.message}. Nic nie zostało zmienione.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun DlgButton(text: String, on: Boolean = false, color: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.kokpitSurface(d(14), active = on).combinedClickable(onClick = onClick).padding(horizontal = d(22), vertical = d(12)),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color ?: if (on) LocalTheme.current.activeText else Txt, fontSize = s(22), maxLines = 1) }
}

@Composable
private fun BackupDialog(title: String, text: String, onClose: () -> Unit, buttons: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(d(28))).background(LocalTheme.current.block)
                .border(1.dp, LocalTheme.current.blockEdge, RoundedCornerShape(d(28))).padding(d(28)),
            verticalArrangement = Arrangement.spacedBy(d(18)),
        ) {
            Text(title, color = Txt, fontSize = s(28), fontWeight = FontWeight.Medium)
            Text(text, color = Dim, fontSize = s(22))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(10)), verticalAlignment = Alignment.CenterVertically) {
                buttons()
            }
        }
    }
}

enum class BackupAction { EXPORT, IMPORT, RESET }

/** Okno potwierdzenia dla eksportu (z kluczami / bez), importu i resetu. */
@Composable
fun BackupConfirm(action: BackupAction, act: MainActivity, onClose: () -> Unit) {
    when (action) {
        BackupAction.EXPORT -> BackupDialog(
            "Eksport ustawień",
            "Zapisuje do pliku ${Backup.FILE_NAME} układ, skróty, wygląd, adresy, reguły i pozostałe ustawienia. " +
                "Plik z kluczami (Claude, OpenAI, Tuya, Slack) pozwala przenieść wszystko do innego radia, ale kto go dostanie, ten ma Twoje klucze.",
            onClose,
        ) {
            Spacer(Modifier.weight(1f))
            DlgButton("Anuluj") { onClose() }
            DlgButton("Z kluczami", color = Warn) { onClose(); act.exportSettings(true) }
            DlgButton("Bez kluczy", on = true) { onClose(); act.exportSettings(false) }
        }
        BackupAction.IMPORT -> BackupDialog(
            "Import ustawień",
            "Ustawienia z wybranego pliku zastąpią wszystkie obecne (układ, skróty, wygląd, reguły, klucze – jeśli są w pliku). " +
                "Widżety Androida trzeba będzie wybrać ponownie. Potem Kokpit uruchomi się od nowa.",
            onClose,
        ) {
            Spacer(Modifier.weight(1f))
            DlgButton("Anuluj") { onClose() }
            DlgButton("Wybierz plik", on = true) { onClose(); act.importSettings() }
        }
        BackupAction.RESET -> BackupDialog(
            "Przywrócić stan początkowy?",
            "Kokpit usunie wszystkie swoje ustawienia: klucze API i tokeny (Claude, OpenAI, Tuya, Slack), adresy, układ, skróty, widżety, " +
                "wygląd, reguły, instrukcje i rozmowę z asystentem. Tego nie da się cofnąć – jeśli chcesz coś zachować, zrób najpierw eksport.",
            onClose,
        ) {
            // kasowanie po lewej – z dala od miejsca, w którym inne okna mają przycisk potwierdzenia
            DlgButton("Usuń wszystko", color = Warn) { onClose(); Backup.reset(act) }
            Spacer(Modifier.weight(1f))
            DlgButton("Anuluj", on = true) { onClose() }
        }
    }
}
