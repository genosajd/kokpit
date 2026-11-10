package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Specjalny skrót otwierający listę wszystkich aplikacji. */
const val APPS_DRAWER = "@apps"

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    }

    /** Skróty jako "pakiet/klasa" (albo [APPS_DRAWER]), w kolejności wyświetlania. */
    var shortcuts: List<String>
        get() = sp.getString("shortcuts", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit().putString("shortcuts", v.joinToString("\n")).apply()

    val hasShortcuts: Boolean get() = sp.contains("shortcuts")

    var home: String
        get() = sp.getString("home", "") ?: ""
        set(v) = sp.edit().putString("home", v).apply()

    var work: String
        get() = sp.getString("work", "") ?: ""
        set(v) = sp.edit().putString("work", v).apply()

    /** Trzecie miejsce w bloku Nawigacja: nazwa = etykieta w Mapach Google, adres opcjonalny */
    var place3Name: String
        get() = sp.getString("place3_name", "Praca Gabi") ?: ""
        set(v) = sp.edit().putString("place3_name", v).apply()
    var place3Address: String
        get() = sp.getString("place3_address", "") ?: ""
        set(v) = sp.edit().putString("place3_address", v).apply()

    var cameraWidgetId: Int
        get() = sp.getInt("camera_widget", -1)
        set(v) = sp.edit().putInt("camera_widget", v).apply()

    /** widżet dowolnej aplikacji w klocku „Widżet” nr n (n = 0 zapisany jak w 0.3.5) */
    fun blockWidgetId(n: Int) = sp.getInt(blockWidgetKey(n), -1)
    fun setBlockWidgetId(n: Int, v: Int) = sp.edit().putInt(blockWidgetKey(n), v).apply()
    private fun blockWidgetKey(n: Int) = if (n == 0) "block_widget" else "block_widget_$n"

    var layout: String?
        get() = sp.getString("layout", null)
        set(v) = sp.edit().putString("layout", v).apply()

    /** Nazwa kamery do podglądu na żywo z Xiaomi Cam; pusta = pokazuj widżet. */
    var liveCamera: String
        get() = sp.getString("live_camera", "") ?: ""
        set(v) = sp.edit().putString("live_camera", v).apply()

    /** Ścieżka do własnego modelu 3D auta (.glb) skopiowanego do plików aplikacji. */
    var carModel: String
        get() = sp.getString("car_model", "") ?: ""
        set(v) = sp.edit().putString("car_model", v).apply()

    /** Model 3D zamiast zdjęcia auta; domyślnie zdjęcie 2D (3D na radiu nie jest płynne). */
    /** ostatnio otwarta grupa w Ustawieniach */
    var settingsGroup: Int
        get() = sp.getInt("settings_group", 0)
        set(v) = sp.edit().putInt("settings_group", v).apply()

    /** wygląd Kokpitu (Themes.kt) */
    var theme: String
        get() = sp.getString("theme", "flat") ?: "flat"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Klimatyzator Smart Life przez chmurę Tuya (Tuya.kt) */
    var tuyaId: String
        get() = sp.getString("tuya_id", "") ?: ""
        set(v) = sp.edit().putString("tuya_id", v).apply()
    var tuyaSecret: String
        get() = sp.getString("tuya_secret", "") ?: ""
        set(v) = sp.edit().putString("tuya_secret", v).apply()
    var tuyaDevice: String
        get() = sp.getString("tuya_device", "") ?: ""
        set(v) = sp.edit().putString("tuya_device", v).apply()
    var tuyaDeviceName: String
        get() = sp.getString("tuya_device_name", "") ?: ""
        set(v) = sp.edit().putString("tuya_device_name", v).apply()

    /** czujnik temperatury w domu (Tuya): "" = sam wybierz (najpierw „T & H”), "-" = bez czujnika, inaczej id */
    var tuyaSensor: String
        get() = sp.getString("tuya_sensor", "") ?: ""
        set(v) = sp.edit().putString("tuya_sensor", v).apply()

    /** Liczba stron ekranu głównego (1–3), przewijanych w bok */
    var gridPages: Int
        get() = sp.getInt("grid_pages", 1)
        set(v) = sp.edit().putInt("grid_pages", v).apply()

    /** Tryb jasny (false = ciemny) */
    var lightMode: Boolean
        get() = sp.getBoolean("light_mode", false)
        set(v) = sp.edit().putBoolean("light_mode", v).apply()

    /** Własne kolory i pismo dla wyglądu (JSON, ThemeCustom); null = bez zmian */
    fun themeCustom(id: String): String? = sp.getString("custom_$id", null)
    fun setThemeCustom(id: String, json: String?) = sp.edit().apply { if (json == null) remove("custom_$id") else putString("custom_$id", json) }.apply()

    /** Asystent AI: dostawca (Assistant.ANTHROPIC / OPENAI), klucze API, modele, czytanie odpowiedzi */
    var aiProvider: String
        get() = sp.getString("ai_provider", Assistant.ANTHROPIC) ?: Assistant.ANTHROPIC
        set(v) = sp.edit().putString("ai_provider", v).apply()
    var anthropicKey: String
        get() = sp.getString("anthropic_key", "") ?: ""
        set(v) = sp.edit().putString("anthropic_key", v).apply()
    var openaiKey: String
        get() = sp.getString("openai_key", "") ?: ""
        set(v) = sp.edit().putString("openai_key", v).apply()
    var anthropicModel: String
        get() = sp.getString("anthropic_model", "")?.ifBlank { null } ?: Assistant.DEFAULT_CLAUDE_MODEL
        set(v) = sp.edit().putString("anthropic_model", v).apply()
    var openaiModel: String
        get() = sp.getString("openai_model", "")?.ifBlank { null } ?: Assistant.DEFAULT_OPENAI_MODEL
        set(v) = sp.edit().putString("openai_model", v).apply()
    /** Głos odpowiedzi: Speech.SYSTEM / Speech.OPENAI, nazwa głosu systemowego, głos i model TTS OpenAI */
    var ttsEngine: String
        get() = sp.getString("tts_engine", Speech.SYSTEM) ?: Speech.SYSTEM
        set(v) = sp.edit().putString("tts_engine", v).apply()
    var ttsVoice: String
        get() = sp.getString("tts_voice", "") ?: ""
        set(v) = sp.edit().putString("tts_voice", v).apply()
    var openaiVoice: String
        get() = sp.getString("openai_voice", Speech.DEFAULT_OPENAI_VOICE) ?: Speech.DEFAULT_OPENAI_VOICE
        set(v) = sp.edit().putString("openai_voice", v).apply()
    var openaiTtsModel: String
        get() = sp.getString("openai_tts_model", "")?.ifBlank { null } ?: Speech.DEFAULT_OPENAI_TTS_MODEL
        set(v) = sp.edit().putString("openai_tts_model", v).apply()
    /** Własne instrukcje dla asystenta (dopisywane do promptu systemowego) */
    var aiInstructions: String
        get() = sp.getString("ai_instructions", "") ?: ""
        set(v) = sp.edit().putString("ai_instructions", v).apply()
    /** hasło „OK BMW” (Hotword.kt); domyślnie wyłączone – nie zużywa zasobów */
    var hotword: Boolean
        get() = sp.getBoolean("hotword", false)
        set(v) = sp.edit().putBoolean("hotword", v).apply()

    /** czułość hasła: 0 = niska (najmniej fałszywych), 1 = średnia, 2 = wysoka */
    var hotwordSens: Int
        get() = sp.getInt("hotword_sens", 1)
        set(v) = sp.edit().putInt("hotword_sens", v).apply()

    /** rozmowa z asystentem zaczyna się od nowa po przekroczeniu tylu tokenów */
    var aiTokenLimit: Int
        get() = sp.getInt("ai_token_limit", 500_000)
        set(v) = sp.edit().putInt("ai_token_limit", v).apply()
    var aiSpeak: Boolean
        get() = sp.getBoolean("ai_speak", true)
        set(v) = sp.edit().putBoolean("ai_speak", v).apply()

    /** Ostrzeżenie „Zapnij pasy” z CAN; domyślnie wyłączone, bo sygnał obejmuje też puste fotele. */
    var beltWarn: Boolean
        get() = sp.getBoolean("belt_warn", false)
        set(v) = sp.edit().putBoolean("belt_warn", v).apply()

    var show3d: Boolean
        get() = sp.getBoolean("show_3d", false)
        set(v) = sp.edit().putBoolean("show_3d", v).apply()

    var carModelInitialized: Boolean
        get() = sp.getBoolean("car_model_init", false)
        set(v) = sp.edit().putBoolean("car_model_init", v).apply()

    var splitLeft: String
        get() = sp.getString("split_left", "com.google.android.apps.maps") ?: ""
        set(v) = sp.edit().putString("split_left", v).apply()

    var splitRight: String
        get() = sp.getString("split_right", "com.google.android.youtube") ?: ""
        set(v) = sp.edit().putString("split_right", v).apply()
}

class AppEntry(val component: ComponentName, val label: String, val icon: ImageBitmap) {
    val key: String get() = component.flattenToString()
    val packageName: String get() = component.packageName
}

object Apps {
    /** Pakiety, z których przy pierwszym uruchomieniu powstają domyślne skróty. */
    private val DEFAULTS = listOf(
        "com.google.android.youtube",
        "com.soundcloud.android",
        "com.waze",
        "com.google.android.apps.maps",
        "pl.lawinski.xiaomicam",
        "com.android.chrome",
    )

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val size = (96 * ctx.resources.displayMetrics.density).toInt().coerceIn(64, 256)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                val ai = it.activityInfo
                AppEntry(
                    ComponentName(ai.packageName, ai.name),
                    it.loadLabel(pm).toString(),
                    it.loadIcon(pm).toBitmap(size, size).asImageBitmap(),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    fun defaultShortcuts(apps: List<AppEntry>): List<String> =
        DEFAULTS.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg }?.key } + APPS_DRAWER

    fun launch(ctx: Context, key: String, extraFlags: Int = 0): Boolean {
        val cn = ComponentName.unflattenFromString(key)
        val intent = if (cn != null) {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
        } else {
            ctx.packageManager.getLaunchIntentForPackage(key)
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or extraFlags)
        return try {
            ctx.startActivity(intent); true
        } catch (e: Exception) {
            false
        }
    }

    /** Wpis po kluczu "pakiet/klasa" albo po samej nazwie pakietu. */
    fun find(apps: List<AppEntry>, key: String): AppEntry? =
        apps.firstOrNull { it.key == key } ?: apps.firstOrNull { it.packageName == key }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

/** Nawigacja do zapisanego adresu w Mapach Google (albo w dowolnej aplikacji obsługującej geo:). */
fun navigateTo(ctx: Context, address: String) {
    val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(address)))
        .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(maps)
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/**
 * Miejsce z własną etykietą w Mapach Google (np. „Praca Gabi”). Mapy nie udostępniają
 * takich etykiet innym aplikacjom, więc Kokpit wyszukuje nazwę w Mapach – wyszukiwarka Map
 * podpowiada etykiety zalogowanego konta jako pierwszy wynik. Potem „Trasa” w Mapach.
 */
fun searchInMaps(ctx: Context, query: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(Intent(i).setPackage("com.google.android.apps.maps"))
    } catch (_: Exception) {
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }
}

/**
 * Nawigacja do miejsca „Dom” albo „Praca” zapisanego w Mapach Google: ten sam skrót,
 * co po przytrzymaniu ikony Map. Mapy pozwalają na to tylko domyślnemu ekranowi głównemu
 * (LauncherApps); ich aktywność skrótu nie jest dostępna dla innych aplikacji.
 * false, gdy Kokpit nie jest ekranem głównym albo Mapy nie mają skrótu.
 */
fun navigateSaved(ctx: Context, work: Boolean): Boolean = try {
    val la = ctx.getSystemService(android.content.pm.LauncherApps::class.java)
    if (!la.hasShortcutHostPermission()) false
    else {
        la.startShortcut("com.google.android.apps.maps", if (work) "manifest_work" else "manifest_home", null, null, android.os.Process.myUserHandle())
        true
    }
} catch (_: Exception) {
    false
}

/**
 * Przełącza ekran na oryginalny system auta (NBT iDrive), tak jak skrót w launcherze Witstek:
 * CenterService (McuManager.openCarInfo) uruchamia aktywność obrazu auta w com.wits.misc.
 */
fun openCarScreen(ctx: Context): Boolean = try {
    ctx.startActivity(
        Intent("android.intent.action.CAMERA_CARINFO").setPackage("com.wits.misc")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (_: Exception) {
    false
}

/**
 * Ustawienia radia Witstek (dźwięk, system, nawigacja; dalej zakładka fabryczna z PIN-em 8888).
 * Wejście fabryczne nie jest eksportowane, więc otwieramy główny ekran ustawień Witstek.
 */
fun openRadioSettings(ctx: Context): Boolean = try {
    ctx.startActivity(
        Intent(Intent.ACTION_MAIN).setClassName("com.wits.settings", "com.wits.settings.SettingsActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (_: Exception) {
    try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

// ---------- lokalizacja i prędkość (z auta, awaryjnie z GPS) ----------

class LocationRepo(ctx: Context) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    val location = MutableStateFlow<Location?>(null)
    val speedKmh = MutableStateFlow<Int?>(null)
    private var running = false

    private val listener = LocationListener { loc ->
        if (loc.provider == LocationManager.GPS_PROVIDER || location.value == null) location.value = loc
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            gpsKmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
            lastGps = SystemClock.elapsedRealtime()
            update()
        }
    }
    private var lastGps = 0L
    private var gpsKmh: Int? = null
    private var canZeroWhileMoving = 0
    private var canBroken = false

    /**
     * Prędkość z auta (właściwość `can.speed` z modułu CAN radia), a gdy jej nie ma, z GPS.
     * Jeśli auto stale podaje 0, a GPS pokazuje jazdę, uznajemy CAN za niedziałający.
     */
    private fun update() {
        val gps = gpsKmh?.takeIf { SystemClock.elapsedRealtime() - lastGps <= 5000 }
        val car = if (canBroken) null else CanSource.speedKmh()
        if (car == 0 && (gps ?: 0) >= 20) {
            if (++canZeroWhileMoving >= 10) canBroken = true
        } else if ((car ?: 0) > 0) canZeroWhileMoving = 0
        speedKmh.value = if (canBroken) gps else car ?: gps
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    if (location.value == null) lm.getLastKnownLocation(p)?.let { location.value = it }
                }
            }
        } catch (e: SecurityException) {
            running = false
        } catch (e: IllegalArgumentException) {
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lm.removeUpdates(listener)
    }

    /** Wołane co sekundę: odświeża prędkość z auta; bez auta i świeżego GPS prędkość jest nieznana. */
    fun checkStale() = update()
}

// ---------- pogoda (Open-Meteo, bez klucza) ----------

class HourForecast(val hour: String, val code: Int, val temp: Int)
class Weather(val place: String, val temp: Int, val feels: Int, val code: Int, val hours: List<HourForecast>)

suspend fun fetchWeather(ctx: Context, loc: Location): Weather? = withContext(Dispatchers.IO) {
    try {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,apparent_temperature,weather_code" +
                    "&hourly=temperature_2m,weather_code&forecast_hours=13&timezone=auto",
                loc.latitude, loc.longitude,
            )
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val cur = json.getJSONObject("current")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val hours = (3 until times.length() step 3).take(4).map { i ->
            HourForecast(times.getString(i).substringAfter('T'), codes.getInt(i), Math.round(temps.getDouble(i)).toInt())
        }
        Weather(
            placeName(ctx, loc),
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            Math.round(cur.getDouble("apparent_temperature")).toInt(),
            cur.getInt("weather_code"),
            hours,
        )
    } catch (e: Exception) {
        null
    }
}

@Suppress("DEPRECATION")
private fun placeName(ctx: Context, loc: Location): String = try {
    val a = Geocoder(ctx, Locale("pl", "PL")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
    a?.locality ?: a?.subAdminArea ?: ""
} catch (e: Exception) {
    ""
}

/** Opis pogody według kodów WMO używanych przez Open-Meteo. */
fun weatherText(code: Int): String = when (code) {
    0 -> "bezchmurnie"
    1, 2 -> "częściowe zachmurzenie"
    3 -> "pochmurno"
    45, 48 -> "mgła"
    51, 53, 55, 56, 57 -> "mżawka"
    61, 63, 65, 66, 67, 80, 81, 82 -> "deszcz"
    71, 73, 75, 77, 85, 86 -> "śnieg"
    95, 96, 99 -> "burza"
    else -> ""
}

// ---------- trasa z powiadomień nawigacji ----------

class NavInfo(
    val pkg: String,
    val title: String,
    val text: String,
    val sub: String,
    val icon: Bitmap?,
    val open: PendingIntent?,
)

object NavState {
    val info = MutableStateFlow<NavInfo?>(null)
}
