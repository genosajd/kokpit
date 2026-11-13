package pl.lawinski.radiolauncher

import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/*
 * Notes asystenta: krótkie zapiski, które właściciel kazał zapamiętać. Zostają między rozmowami –
 * są dopisywane do opisu roli asystenta przy każdym zapytaniu. Podgląd i kasowanie: Ustawienia → Asystent AI → Rozmowa.
 */
object Notes {
    class Note(val text: String, val time: Long)

    const val MAX = 60
    private const val MAX_LEN = 400

    val items = mutableStateListOf<Note>()

    fun init() {
        items.clear()
        runCatching {
            val a = JSONArray(Prefs.aiNotes)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { items += Note(it.optString("text"), it.optLong("time")) }
        }
    }

    private fun store() {
        Prefs.aiNotes = JSONArray().apply { items.forEach { put(JSONObject().put("text", it.text).put("time", it.time)) } }.toString()
    }

    fun removeAt(i: Int) {
        if (i in items.indices) { items.removeAt(i); store() }
    }

    fun clear() {
        items.clear(); store()
    }

    private fun listing() = items.mapIndexed { i, n -> "${i + 1}. ${n.text}" }.joinToString("\n")

    /** Dopisek do opisu roli asystenta – zawsze aktualna zawartość notesu. */
    fun prompt(): String {
        if (!Access.allows("notes") || items.isEmpty()) return ""
        return "\n\nTwój notes – rzeczy, które właściciel kazał Ci zapamiętać (pamiętasz je między rozmowami; numer służy do usuwania):\n" + listing()
    }

    fun run(input: JSONObject): String = when (input.optString("action")) {
        "add" -> {
            val text = input.optString("text").trim().replace('\n', ' ')
            when {
                text.isEmpty() -> "Brak treści notatki"
                items.size >= MAX -> "Notes jest pełny ($MAX notatek). Zaproponuj usunięcie którejś – właściciel może też przejrzeć notes w Ustawieniach → Asystent AI."
                else -> {
                    items += Note(text.take(MAX_LEN), System.currentTimeMillis())
                    store()
                    "Zapisano w notesie jako nr ${items.size}" + if (text.length > MAX_LEN) " (skrócone do $MAX_LEN znaków)" else ""
                }
            }
        }
        "remove" -> {
            val n = input.optInt("number")
            if (n !in 1..items.size) "Nie ma notatki nr $n. Notes:\n" + listing().ifEmpty { "(pusty)" }
            else {
                val gone = items[n - 1].text
                removeAt(n - 1)
                "Usunięto z notesu: $gone"
            }
        }
        else -> if (items.isEmpty()) "Notes jest pusty" else listing()
    }
}
