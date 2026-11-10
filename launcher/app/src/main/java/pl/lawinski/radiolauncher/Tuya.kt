package pl.lawinski.radiolauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/*
 * Klimatyzator ze Smart Life przez chmurę Tuya (projekt „Kokpit F11” na platform.tuya.com,
 * centrum danych Central Europe, konto Smart Life połączone kodem QR).
 * Access ID i Access Secret wpisuje się w Ustawieniach → Dom; zostają tylko w radiu.
 * Funkcje klimatyzatora (kategoria „kt”): switch, temp_set (×10 °C), mode, temp_current, humidity_current.
 */

/** Stan klimatyzatora do bloku i asystenta. */
data class AcState(
    val name: String,
    val on: Boolean,
    /** temperatura w pokoju (°C) */
    val room: Float?,
    val humidity: Int?,
    /** ustawiona temperatura (°C) */
    val target: Float?,
    val mode: String?,
    val online: Boolean,
    /** prędkość nawiewu (wartość z [Tuya.fanValues]) albo null, gdy urządzenie jej nie ma */
    val fan: String? = null,
)

/** Czujnik temperatury i wilgotności w domu (np. „T & H Sensor”) – dokładniejszy niż czujnik w klimatyzatorze. */
data class SensorState(val id: String, val name: String, val temp: Float?, val humidity: Int?, val battery: Int?, val online: Boolean)

object Tuya {
    /** EU = Central Europe Data Center */
    private const val HOST = "https://openapi.tuyaeu.com"

    val state = MutableStateFlow<AcState?>(null)
    val sensor = MutableStateFlow<SensorState?>(null)

    /** czujniki temperatury w projekcie Tuya: id → nazwa (do wyboru w Ustawieniach) */
    var sensors: List<Pair<String, String>> = emptyList()
        private set
    private var sensorsChecked = false
    val error = MutableStateFlow<String?>(null)

    private var token: String? = null
    private var tokenUntil = 0L

    val configured get() = Prefs.tuyaId.isNotBlank() && Prefs.tuyaSecret.isNotBlank()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun hmac(key: String, s: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(s.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    /** Zapytanie podpisane według Tuya OpenAPI (HMAC-SHA256). */
    private fun call(method: String, path: String, body: String = "", auth: Boolean = true): JSONObject {
        val id = Prefs.tuyaId
        val secret = Prefs.tuyaSecret
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val stringToSign = "$method\n${sha256(body.toByteArray())}\n\n$path"
        val access = if (auth) accessToken() else ""
        val sign = hmac(secret, id + access + t + nonce + stringToSign)
        val c = URL(HOST + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("client_id", id)
        c.setRequestProperty("t", t)
        c.setRequestProperty("nonce", nonce)
        c.setRequestProperty("sign_method", "HMAC-SHA256")
        c.setRequestProperty("sign", sign)
        if (auth) c.setRequestProperty("access_token", access)
        if (body.isNotEmpty()) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val o = JSONObject(text.ifBlank { "{}" })
        if (!o.optBoolean("success")) {
            if (o.optInt("code") == 1010 || o.optInt("code") == 1011) token = null // token wygasł
            throw TuyaError(o.optInt("code"), o.optString("msg", "HTTP ${c.responseCode}"))
        }
        return o
    }

    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        token?.takeIf { now < tokenUntil }?.let { return it }
        val r = call("GET", "/v1.0/token?grant_type=1", auth = false).getJSONObject("result")
        token = r.getString("access_token")
        tokenUntil = now + (r.optLong("expire_time", 7200) - 120) * 1000
        return token!!
    }

    /** Id klimatyzatora: zapisany albo pierwsze urządzenie kategorii „kt” w projekcie. */
    private fun deviceId(): String {
        Prefs.tuyaDevice.takeIf { it.isNotBlank() }?.let { return it }
        val list = call("GET", "/v2.0/cloud/thing/device?page_size=20").optJSONArray("result") ?: JSONArray()
        val ac = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("category") == "kt" }
            ?: throw TuyaError(0, "W projekcie Tuya nie ma klimatyzatora")
        Prefs.tuyaDevice = ac.getString("id")
        Prefs.tuyaDeviceName = ac.optString("name", ac.optString("customName", "Klimatyzator"))
        return Prefs.tuyaDevice
    }

    /*
     * Nawiew nie jest w standardowym zestawie poleceń tego klimatyzatora, więc czytamy model rzeczy
     * (/v2.0/cloud/thing/{id}/model) i szukamy właściwości typu enum z „fan”/„wind” w kodzie lub nazwie.
     */
    private var fanCode: String? = null
    private var fanChecked = false
    var fanValues: List<String> = emptyList()
        private set

    private fun findFan(id: String) {
        if (fanChecked) return
        fanChecked = true
        try {
            val model = JSONObject(call("GET", "/v2.0/cloud/thing/$id/model").getJSONObject("result").getString("model"))
            val services = model.optJSONArray("services") ?: return
            for (i in 0 until services.length()) {
                val props = services.getJSONObject(i).optJSONArray("properties") ?: continue
                for (j in 0 until props.length()) {
                    val p = props.getJSONObject(j)
                    val code = p.optString("code")
                    val name = p.optString("name")
                    val type = p.optJSONObject("typeSpec")
                    android.util.Log.i("Tuya", "property $code ($name) ${type?.optString("type")} ${type?.optJSONArray("range")}")
                    val looksFan = listOf("fan", "wind").any { code.contains(it, true) || name.contains(it, true) } && !code.contains("swing", true)
                    if (fanCode == null && looksFan && type?.optString("type") == "enum") {
                        fanCode = code
                        // od najsłabszego: Auto, Cicho, Wolno … Szybko, Turbo
                        val order = listOf("auto", "mute", "quiet", "silent", "low", "mid_low", "mid", "middle", "mid_high", "high", "strong", "turbo")
                        fanValues = type.optJSONArray("range")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                            .sortedBy { v -> order.indexOf(v.lowercase()).let { if (it < 0) 99 else it } }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Tuya", "model: ${e.message}")
        }
    }

    /** Bieżąca wartość nawiewu z „cienia” urządzenia (wszystkie DP, także spoza zestawu standardowego). */
    private fun readFan(id: String): String? {
        val code = fanCode ?: return null
        val props = call("GET", "/v2.0/cloud/thing/$id/shadow/properties?codes=$code").getJSONObject("result").optJSONArray("properties") ?: return null
        return (0 until props.length()).map { props.getJSONObject(it) }.firstOrNull { it.optString("code") == code }?.opt("value")?.toString()
    }

    suspend fun setFan(value: String): String = withContext(Dispatchers.IO) {
        try {
            val id = deviceId()
            val code = fanCode ?: return@withContext "Ten klimatyzator nie ma regulacji nawiewu"
            call("POST", "/v2.0/cloud/thing/$id/shadow/properties/issue", JSONObject().put("properties", JSONObject().put(code, value).toString()).toString())
            kotlinx.coroutines.delay(1500)
            refresh()
            "ok"
        } catch (e: Exception) {
            describe(e).also { error.value = it }
        }
    }

    /** Polska nazwa prędkości nawiewu (wartości różnią się między modelami). */
    fun fanName(v: String?): String = when (v?.lowercase()) {
        null -> "–"
        "auto" -> "Auto"
        "low", "1", "level_1", "min" -> "Wolno"
        "mid", "middle", "medium", "2", "level_2" -> "Średnio"
        "high", "3", "level_3", "max" -> "Szybko"
        "strong", "turbo", "super", "powerful", "4" -> "Turbo"
        "mute", "quiet", "silent", "sleep" -> "Cicho"
        "mid_low", "middle_low" -> "Średnio-wolno"
        "mid_high", "middle_high" -> "Średnio-szybko"
        else -> v
    }

    /** kategorie Tuya czujników temperatury: wsdcg = temp./wilg., wnykq = pilot IR z czujnikiem, co2bj/hjjcy = jakość powietrza */
    private val SENSOR_CATEGORIES = setOf("wsdcg", "wnykq", "co2bj", "hjjcy", "ywbj")

    private fun findSensors() {
        if (sensorsChecked) return
        val list = call("GET", "/v2.0/cloud/thing/device?page_size=20").optJSONArray("result") ?: JSONArray()
        sensors = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("category") in SENSOR_CATEGORIES || it.optString("name").contains("T & H", true) || it.optString("name").contains("T&H", true) }
            .map { it.getString("id") to it.optString("name", it.optString("customName", "Czujnik")) }
        sensorsChecked = true
        android.util.Log.i("Tuya", "czujniki: $sensors")
    }

    private fun sensorId(): String? {
        val p = Prefs.tuyaSensor
        if (p == "-") return null
        if (p.isNotBlank()) return p
        findSensors()
        return (sensors.firstOrNull { it.second.contains("T & H", true) || it.second.contains("T&H", true) } ?: sensors.firstOrNull())?.first
    }

    /** skala liczb z opisu funkcji urządzenia (np. va_temperature 215 przy scale 1 = 21,5 °C) */
    private val scales = HashMap<String, Map<String, Int>>()

    private fun scalesOf(id: String): Map<String, Int> = scales.getOrPut(id) {
        runCatching {
            val st = call("GET", "/v1.0/devices/$id/specifications").getJSONObject("result").optJSONArray("status") ?: JSONArray()
            (0 until st.length()).map { st.getJSONObject(it) }.associate { o ->
                o.optString("code") to (runCatching { JSONObject(o.optString("values")).optInt("scale", 0) }.getOrDefault(0))
            }
        }.getOrDefault(emptyMap())
    }

    private fun readSensor() {
        val id = sensorId() ?: run { sensor.value = null; return }
        val st = call("GET", "/v1.0/devices/$id/status").getJSONArray("result")
        val m = (0 until st.length()).associate { st.getJSONObject(it).let { o -> o.getString("code") to o.get("value") } }
        val sc = scalesOf(id)
        fun num(vararg codes: String): Float? {
            val c = codes.firstOrNull { m[it] is Number } ?: return null
            val v = (m[c] as Number).toFloat()
            // bez opisu skali: czujniki Tuya zwykle podają temperaturę ×10
            val scale = sc[c] ?: if (c.contains("temp") && v > 60) 1 else 0
            return v / Math.pow(10.0, scale.toDouble()).toFloat()
        }
        val info = runCatching { call("GET", "/v1.0/devices/$id").getJSONObject("result") }.getOrNull()
        val name = info?.optString("name")?.takeIf { it.isNotBlank() } ?: sensors.firstOrNull { it.first == id }?.second ?: "Czujnik"
        sensor.value = SensorState(
            id = id,
            name = name,
            temp = num("va_temperature", "temp_current", "temp_value", "temperature"),
            humidity = num("va_humidity", "humidity_value", "humidity_current", "humidity")?.toInt(),
            battery = (m["battery_percentage"] as? Number)?.toInt(),
            online = info?.optBoolean("online", true) ?: true,
        )
    }

    /** Lista czujników do Ustawień (wywoływać poza wątkiem UI). */
    suspend fun sensorList(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching { findSensors() }
        sensors
    }

    /** Odświeża stan (wywoływać poza wątkiem UI). */
    suspend fun refresh(): AcState? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            val id = deviceId()
            findFan(id)
            val fan = runCatching { readFan(id) }.getOrNull()
            val st = call("GET", "/v1.0/devices/$id/status").getJSONArray("result")
            val m = (0 until st.length()).associate { st.getJSONObject(it).let { o -> o.getString("code") to o.get("value") } }
            // nazwa nadana w Smart Life („Klima salon”) i stan online z opisu urządzenia
            val info = runCatching { call("GET", "/v1.0/devices/$id").getJSONObject("result") }.getOrNull()
            val online = info?.optBoolean("online", true) ?: true
            info?.optString("name")?.takeIf { it.isNotBlank() }?.let { Prefs.tuyaDeviceName = it }
            val s = AcState(
                name = Prefs.tuyaDeviceName.ifBlank { "Klimatyzator" },
                on = m["switch"] as? Boolean ?: false,
                room = (m["temp_current"] as? Number)?.toFloat(),
                // 0 % = klimatyzator nie ma czujnika wilgotności
                humidity = (m["humidity_current"] as? Number)?.toInt()?.takeIf { it > 0 },
                target = (m["temp_set"] as? Number)?.toFloat()?.div(10f),
                mode = m["mode"] as? String,
                online = online,
                fan = fan,
            )
            state.value = s
            error.value = null
            // czujnik w pokoju – błąd czujnika nie psuje odczytu klimatyzatora
            runCatching { readSensor() }.onFailure { android.util.Log.w("Tuya", "czujnik: ${it.message}") }
            s
        } catch (e: Exception) {
            error.value = describe(e)
            null
        }
    }

    /** Wysyła polecenia (np. "switch" to true, "temp_set" to 225). */
    suspend fun send(vararg cmds: Pair<String, Any>): String = withContext(Dispatchers.IO) {
        try {
            val id = deviceId()
            val arr = JSONArray().apply { cmds.forEach { (k, v) -> put(JSONObject().put("code", k).put("value", v)) } }
            call("POST", "/v1.0/devices/$id/commands", JSONObject().put("commands", arr).toString())
            kotlinx.coroutines.delay(1500)
            refresh()
            "ok"
        } catch (e: Exception) {
            describe(e).also { error.value = it }
        }
    }

    suspend fun setPower(on: Boolean) = send("switch" to on)

    /** Temperatura 16–31 °C co 0,5 °C. */
    suspend fun setTarget(c: Float) = send("temp_set" to ((c.coerceIn(16f, 31f) * 2).toInt() * 5))

    suspend fun setMode(mode: String) = send("mode" to mode)

    val MODES = listOf("cold" to "Chłodzenie", "hot" to "Grzanie", "wet" to "Osuszanie", "wind" to "Wentylator", "auto" to "Auto")

    fun modeName(m: String?) = MODES.firstOrNull { it.first == m }?.second ?: (m ?: "–")

    private fun describe(e: Exception): String = when {
        e is TuyaError && e.code == 1004 -> "Zły Access Secret Tuya (Ustawienia → Dom)"
        e is TuyaError && (e.code == 1106 || e.code == 2017) -> "Brak uprawnień – w projekcie Tuya ustaw klimatyzator na „Controllable”"
        e is TuyaError && e.code == 28841105 -> "Darmowy okres API Tuya minął – przedłuż IoT Core na platform.tuya.com"
        e is TuyaError -> "Tuya: ${e.message} (${e.code})"
        e is java.io.IOException -> "Brak połączenia z internetem"
        else -> "Błąd: ${e.message}"
    }

    /** Nowe klucze albo nowe urządzenie – zapomnij token i wybór. */
    fun reset() {
        token = null
        tokenUntil = 0
        Prefs.tuyaDevice = ""
        fanChecked = false
        fanCode = null
        fanValues = emptyList()
        sensorsChecked = false
        sensors = emptyList()
        scales.clear()
        sensor.value = null
        state.value = null
        error.value = null
    }
}

class TuyaError(val code: Int, message: String) : Exception(message)
