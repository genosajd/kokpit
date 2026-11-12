package pl.lawinski.radiolauncher

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/*
 * Miejsca i trasy dla asystenta: Google Places API (New) i Routes API.
 * search = miejsca pasujące do zapytania (nazwa, adres, odległość, ocena, czy otwarte, opcjonalnie ceny paliw),
 * route = czas i długość dojazdu z bieżącym ruchem, też z przystankami.
 * Klucz Google Maps Platform wpisuje właściciel w Ustawieniach → Nawigacja; zostaje tylko w radiu.
 * W projekcie Google Cloud muszą być włączone „Places API (New)” i „Routes API”.
 */
object Places {
    val configured get() = Prefs.googleKey.isNotBlank()

    private class GoogleError(message: String) : Exception(message)

    private fun post(url: String, fields: String, body: JSONObject): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Goog-Api-Key", Prefs.googleKey)
        c.setRequestProperty("X-Goog-FieldMask", fields)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val msg = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }
            throw GoogleError("HTTP $code" + if (msg.isNotEmpty()) ": " + msg.take(300) else "")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun latLng(l: Location) = JSONObject().put("latitude", l.latitude).put("longitude", l.longitude)

    private fun km(m: Float) = if (m < 950) "${(m / 50).toInt() * 50 + 50} m" else String.format(Locale("pl", "PL"), "%.1f km", m / 1000)

    private fun minutes(seconds: Long): String {
        val min = (seconds + 30) / 60
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    /** „1234s” → 1234 */
    private fun secs(v: String) = v.removeSuffix("s").toDoubleOrNull()?.toLong() ?: 0L

    fun run(act: MainActivity, input: JSONObject): String {
        if (!configured) return "Brak klucza Google Maps – właściciel wpisuje go w Ustawieniach Kokpitu → Nawigacja → „Klucz Google Maps”."
        return try {
            when (input.optString("action")) {
                "search" -> search(act, input)
                "route" -> route(act, input)
                else -> "Nieznana akcja"
            }
        } catch (e: GoogleError) {
            "Google odrzuciło zapytanie (${e.message}). Sprawdź klucz i czy w projekcie Google Cloud włączone są Places API (New) i Routes API."
        } catch (e: Exception) {
            "Nie udało się połączyć z Google: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun search(act: MainActivity, input: JSONObject): String {
        val query = input.optString("query").trim()
        if (query.isEmpty()) return "Brak zapytania"
        val near = input.optString("near").trim()
        val here = act.location.location.value
        val fuel = input.optBoolean("fuel_prices")
        val body = JSONObject()
            .put("textQuery", if (near.isEmpty()) query else "$query, $near")
            .put("languageCode", "pl")
            .put("regionCode", "PL")
            .put("maxResultCount", 8)
        if (input.optBoolean("open_now")) body.put("openNow", true)
        if (near.isEmpty() && here != null) {
            body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", latLng(here)).put("radius", 30000.0)))
            body.put("rankPreference", "DISTANCE")
        }
        var fields = "places.displayName,places.formattedAddress,places.location,places.rating,places.userRatingCount," +
            "places.currentOpeningHours.openNow,places.businessStatus"
        if (fuel) fields += ",places.fuelOptions"
        val places = post("https://places.googleapis.com/v1/places:searchText", fields, body).optJSONArray("places") ?: JSONArray()
        if (places.length() == 0) return "Nic nie znaleziono dla „$query”"
        return buildString {
            if (near.isEmpty() && here == null) appendLine("Brak położenia GPS – wyniki nie są ułożone według odległości.")
            for (i in 0 until places.length()) {
                val p = places.getJSONObject(i)
                append(i + 1).append(". ").append(p.optJSONObject("displayName")?.optString("text").orEmpty())
                append(" – ").append(p.optString("formattedAddress"))
                val loc = p.optJSONObject("location")
                if (here != null && loc != null) {
                    val d = FloatArray(1)
                    Location.distanceBetween(here.latitude, here.longitude, loc.optDouble("latitude"), loc.optDouble("longitude"), d)
                    append("; w linii prostej ").append(km(d[0]))
                }
                if (p.has("rating")) append("; ocena ").append(p.optDouble("rating")).append(" (").append(p.optInt("userRatingCount")).append(" opinii)")
                when {
                    p.optString("businessStatus").startsWith("CLOSED") -> append("; zamknięte na stałe lub czasowo")
                    p.optJSONObject("currentOpeningHours")?.has("openNow") == true ->
                        append(if (p.getJSONObject("currentOpeningHours").optBoolean("openNow")) "; teraz otwarte" else "; teraz zamknięte")
                }
                val prices = p.optJSONObject("fuelOptions")?.optJSONArray("fuelPrices")
                if (prices != null && prices.length() > 0) {
                    append("; paliwa: ")
                    for (j in 0 until prices.length()) {
                        val f = prices.getJSONObject(j)
                        val m = f.optJSONObject("price") ?: continue
                        val value = m.optString("units", "0").toDouble() + m.optInt("nanos") / 1e9
                        if (j > 0) append(", ")
                        append(f.optString("type")).append(' ').append(String.format(Locale("pl", "PL"), "%.2f", value)).append(' ').append(m.optString("currencyCode"))
                    }
                }
                appendLine()
            }
            append("Trasę do wybranego miejsca włącz przez navigate, podając nazwę z adresem.")
        }
    }

    private fun waypoint(text: String) = JSONObject().put("address", text)

    private fun route(act: MainActivity, input: JSONObject): String {
        val dest = input.optString("destination").trim()
        if (dest.isEmpty()) return "Brak celu"
        val from = input.optString("origin").trim()
        val here = act.location.location.value
        if (from.isEmpty() && here == null) return "Brak położenia GPS – podaj origin (skąd)."
        val stops = stops(input.optString("stops"))
        val body = JSONObject()
            .put("origin", if (from.isEmpty()) JSONObject().put("location", JSONObject().put("latLng", latLng(here!!))) else waypoint(from))
            .put("destination", waypoint(dest))
            .put("travelMode", "DRIVE")
            .put("routingPreference", "TRAFFIC_AWARE")
            .put("languageCode", "pl")
            .put("regionCode", "PL")
            .put("units", "METRIC")
        if (stops.isNotEmpty()) body.put("intermediates", JSONArray(stops.map { waypoint(it) }))
        else body.put("computeAlternativeRoutes", true)
        val routes = post("https://routes.googleapis.com/directions/v2:computeRoutes",
            "routes.duration,routes.staticDuration,routes.distanceMeters,routes.description", body).optJSONArray("routes") ?: JSONArray()
        if (routes.length() == 0) return "Google nie znalazło trasy do „$dest”"
        return buildString {
            append("Dojazd autem ").append(if (from.isEmpty()) "z bieżącego położenia" else "z „$from”").append(" do „$dest”")
            if (stops.isNotEmpty()) append(" przez: ").append(stops.joinToString())
            appendLine(" (ruch w tej chwili):")
            for (i in 0 until minOf(routes.length(), 3)) {
                val r = routes.getJSONObject(i)
                val now = secs(r.optString("duration"))
                val free = secs(r.optString("staticDuration"))
                append(i + 1).append(". ")
                r.optString("description").takeIf { it.isNotBlank() }?.let { append("przez ").append(it).append(": ") }
                append(minutes(now)).append(", ").append(km(r.optInt("distanceMeters").toFloat()))
                if (free > 0 && now - free >= 120) append("; korki wydłużają o ").append(minutes(now - free))
                appendLine()
            }
            append("Nawigację włącza navigate (przystanki w polu stops).")
        }
    }

    /** „A; B” → [A, B] */
    fun stops(text: String) = text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}
