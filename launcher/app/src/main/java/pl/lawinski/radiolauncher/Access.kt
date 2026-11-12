package pl.lawinski.radiolauncher

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Dostępy asystenta (Ustawienia → Asystent AI → Dostępy): właściciel w każdej chwili włącza albo
 * wyłącza, z czego asystent może korzystać. Wyłączony dostęp: narzędzie nie trafia do modelu,
 * a gdyby jednak zostało wywołane, AssistantTools.run je odrzuca. Zmiana zaczyna nową rozmowę.
 */
object Access {
    class Group(val id: String, val title: String, val description: String, val icon: ImageVector, val tools: Set<String>)

    /** web_search to wyszukiwarka po stronie Claude (nie narzędzie Kokpitu) */
    const val WEB = "web"

    val groups = listOf(
        Group("car", "Auto", "Paliwo, drzwi, prędkość, położenie GPS, trasa", Icons.Outlined.DirectionsCar, setOf("car_status")),
        Group("nav", "Nawigacja", "Włączanie trasy w Mapach Google", Icons.Outlined.Navigation, setOf("navigate", "open_saved_place")),
        Group("places", "Miejsca i trasy (Google)", "Szukanie miejsc w pobliżu, oceny, czas dojazdu z korkami", Icons.Outlined.Place, setOf("places")),
        Group("apps", "Aplikacje", "Uruchamianie aplikacji w radiu", Icons.Outlined.Apps, setOf("open_app")),
        Group("media", "Muzyka i YouTube", "Odtwarzanie, pauza, następny utwór", Icons.Outlined.MusicNote, setOf("play_youtube", "media_control")),
        Group("radio", "Radio", "Głośność, jasność, ekran iDrive, wygląd", Icons.Outlined.Radio, setOf("radio_control")),
        Group("camera", "Kamery", "Zdjęcia z kamer Xiaomi, obrót, zoom", Icons.Outlined.Videocam, setOf("camera_snapshot", "camera_control")),
        Group("home", "Dom (Tuya)", "Klimatyzator i czujnik w domu", Icons.Outlined.AcUnit, setOf("home_ac")),
        Group("calendar", "Kalendarz Google", "Odczyt i dodawanie terminów", Icons.Outlined.CalendarMonth, setOf("calendar")),
        Group("slack", "Slack", "Czytanie i wysyłanie wiadomości w Twoim imieniu (z potwierdzeniem)", Icons.Outlined.Chat, setOf("slack")),
        Group("web", "Internet", "Wyszukiwanie w sieci (tylko Claude)", Icons.Outlined.Language, setOf(WEB)),
    )

    /** wersja do odświeżania kafelków w Ustawieniach */
    val version = mutableStateOf(0)

    fun enabled(groupId: String) = groupId !in Prefs.aiDisabled

    fun set(groupId: String, on: Boolean) {
        Prefs.aiDisabled = if (on) Prefs.aiDisabled - groupId else Prefs.aiDisabled + groupId
        version.value++
        Assistant.reset()
    }

    /** Czy asystent może użyć narzędzia [tool]. */
    fun allows(tool: String): Boolean {
        val g = groups.firstOrNull { tool in it.tools } ?: return true
        return enabled(g.id)
    }

    /** Dopisek do promptu: czego asystent nie może, żeby odpowiedział wprost zamiast próbować. */
    fun promptNote(): String {
        val off = groups.filter { !enabled(it.id) }
        if (off.isEmpty()) return ""
        return "\n\nWłaściciel wyłączył Ci dostęp do: ${off.joinToString { it.title }}. Gdy ktoś o to prosi, powiedz krótko, że ten dostęp jest wyłączony w Ustawieniach Kokpitu → Asystent AI → Dostępy."
    }
}
