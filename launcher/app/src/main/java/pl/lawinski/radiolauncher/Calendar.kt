package pl.lawinski.radiolauncher

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * Kalendarz dla asystenta: czyta i dodaje terminy przez systemowy dostawcę kalendarza Androida
 * (CalendarContract) – to ten sam kalendarz, który synchronizuje konto Google w radiu.
 * Bez kluczy i logowania; wymaga zgody READ/WRITE_CALENDAR i włączonej synchronizacji kalendarza.
 */
object KokpitCalendar {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val PL = Locale("pl", "PL")

    fun granted(ctx: Context) = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun now(): String = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", PL).format(java.util.Date())

    /** Terminy od dnia [fromDate] (yyyy-MM-dd, pusty = dziś) przez [days] dni. */
    fun list(ctx: Context, fromDate: String, days: Int): String {
        val start = parseDay(fromDate) ?: return "Zła data „$fromDate” – podaj RRRR-MM-DD"
        val end = start + days.coerceIn(1, 31) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION,
        )
        val day = SimpleDateFormat("EEEE d.MM", PL)
        val hour = SimpleDateFormat("HH:mm", PL)
        val utcDay = SimpleDateFormat("EEEE d.MM", PL).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val out = mutableListOf<String>()
        ctx.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val title = c.getString(0).orEmpty().ifBlank { "(bez tytułu)" }
                val b = c.getLong(1)
                val e = c.getLong(2)
                val allDay = c.getInt(3) == 1
                val loc = c.getString(4).orEmpty().trim()
                val cal = c.getString(5).orEmpty()
                val desc = c.getString(6).orEmpty().replace(Regex("\\s+"), " ").trim().take(150)
                val time = if (allDay) "${utcDay.format(b)} cały dzień" else "${day.format(b)} ${hour.format(b)}–${hour.format(e)}"
                out += buildString {
                    append("$time: $title")
                    if (loc.isNotEmpty()) append(" · miejsce: $loc")
                    if (desc.isNotEmpty()) append(" · opis: $desc")
                    append(" · kalendarz: $cal")
                }
            }
        }
        val range = if (days <= 1) "w dniu ${day.format(start)}" else "od ${day.format(start)} przez $days dni"
        val head = "Teraz: ${now()}. Terminy $range"
        if (out.isEmpty()) {
            return if (calendars(ctx).isEmpty()) "$head: brak – w radiu nie ma żadnego kalendarza (włącz synchronizację kalendarza konta Google w Ustawieniach Androida → Konta)."
            else "$head: brak terminów."
        }
        return "$head:\n" + out.joinToString("\n")
    }

    /** Dodaje termin do głównego kalendarza Google (albo pierwszego, do którego można pisać). */
    fun add(ctx: Context, title: String, start: String, minutes: Int, location: String): String {
        if (title.isBlank()) return "Brak tytułu terminu"
        val cal = calendars(ctx).firstOrNull { it.writable }
            ?: return "Brak kalendarza, do którego można dopisać termin – włącz synchronizację kalendarza konta Google w radiu."
        val allDay = !start.contains(':')
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title.trim())
            if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location.trim())
            if (allDay) {
                val d = SimpleDateFormat("yyyy-MM-dd", PL).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val b = runCatching { d.parse(start.trim())!!.time }.getOrNull() ?: return "Zła data „$start” – podaj RRRR-MM-DD albo RRRR-MM-DD GG:MM"
                put(CalendarContract.Events.DTSTART, b)
                put(CalendarContract.Events.DTEND, b + 86_400_000L)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val f = SimpleDateFormat("yyyy-MM-dd HH:mm", PL)
                val b = runCatching { f.parse(start.trim().replace('T', ' '))!!.time }.getOrNull() ?: return "Zła data „$start” – podaj RRRR-MM-DD GG:MM"
                put(CalendarContract.Events.DTSTART, b)
                put(CalendarContract.Events.DTEND, b + minutes.coerceIn(5, 24 * 60) * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return "Nie udało się dodać terminu"
        if (allDay) return "Dodano „$title” na cały dzień $start do kalendarza ${cal.name}."
        val f = SimpleDateFormat("EEEE d MMMM, HH:mm", PL)
        return "Dodano „$title” – ${f.format(values.getAsLong(CalendarContract.Events.DTSTART))} (${minutes.coerceIn(5, 1440)} min) do kalendarza ${cal.name} (id ${ContentUris.parseId(uri)})."
    }

    private class Cal(val id: Long, val name: String, val writable: Boolean)

    /** Widoczne kalendarze: najpierw główny kalendarz konta Google. */
    private fun calendars(ctx: Context): List<Cal> {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val list = mutableListOf<Pair<Int, Cal>>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, "${CalendarContract.Calendars.VISIBLE}=1", null, null)?.use { c ->
            while (c.moveToNext()) {
                val writable = c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                val rank = (if (c.getInt(3) == 1) 0 else 2) + (if (c.getString(4) == "com.google") 0 else 1)
                list += rank to Cal(c.getLong(0), c.getString(1).orEmpty(), writable)
            }
        }
        return list.sortedBy { it.first }.map { it.second }
    }

    private fun parseDay(s: String): Long? {
        val c = Calendar.getInstance()
        if (s.isNotBlank()) {
            val d = runCatching { SimpleDateFormat("yyyy-MM-dd", PL).parse(s.trim()) }.getOrNull() ?: return null
            c.time = d
        }
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
