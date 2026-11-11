package pl.lawinski.radiolauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Dane auta z modułu CAN radia Witstek (YA32, CAN NBT).
 *
 * Usługa radia (CenterService, com.wits.pms) rozsyła ramki CAN broadcastem
 * `com.can.ACTION_CAN_CENTER_SEND`, ale tylko do własnych pakietów Witstek (setPackage).
 * Dlatego Kokpit czyta ramki z logu systemu: CenterService wypisuje każdą jako
 * `McuManager: doMcuDataYA82:09 12 ...`. Wymaga READ_LOGS nadanego przez ADB:
 * `adb shell pm grant pl.lawinski.radiolauncher android.permission.READ_LOGS`.
 * Log daje też ostatnią ramkę sprzed startu Kokpitu. Ramka stanu auta ma `data[1] == 0x12`:
 * - `data[6]`: bity 7/6/5/4 = drzwi LP/PP/LT/PT (przy kierownicy po lewej), bit 3 = klapa;
 * - `data[7]`: paliwo w litrach (0..127);
 * - `data[3]`: bieg 0=P 1=R 2=N 4=D 5=S 6=M;
 * - `data[8]` bity 7..6 == 1: niezapięty pas;
 * - `data[9]`: temperatura zewnętrzna × 2 (bit 7 = minus); np. 0x0D = 6,5 °C.
 * Prędkość auta usługa radia wystawia we właściwości systemowej `can.speed` (km/h), a bajt drzwi w `can.door`
 * – te dwie rzeczy działają bez dzienników (Prefs.canLogs wyłączone = bez okienka zgody Androida 13,
 * ale też bez paliwa na żywo, biegu, temperatury, telefonu → iDrive i przechyłów pokrętła).
 * Układ odczytany z launchera Witstek (com.middle.GlobalDef). Launcher Witstek zapisuje też
 * ostatnie paliwo w Settings.System „OIlValue”, więc to jest wartość startowa.
 */
object CanSource {
    private const val ACTION = "com.can.ACTION_CAN_CENTER_SEND"
    private const val CMD_CAN = 4096
    private const val FRAME_CAR = 0x12
    private const val KEY_FUEL = "OIlValue"

    /** Ostatni prawdziwy stan auta (bez danych pokazowych). */
    @Volatile
    var last = CarStatus()
        private set

    /** Pokaz danych auta: wtedy nie nadpisujemy CarData.status. */
    @Volatile
    var demo = false

    private var started = false

    private fun publish(st: CarStatus) {
        last = st
        if (!demo) CarData.status.value = st
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.getIntExtra("cmd", 0) != CMD_CAN) return
            val d = intent.getByteArrayExtra("data") ?: return
            parse(ctx, d)
        }
    }

    private fun bit(b: Byte, i: Int) = (b.toInt() shr i) and 1 == 1

    fun parse(ctx: Context, d: ByteArray) {
        if (d.size < 8 || d[1].toInt() != FRAME_CAR) return
        val b6 = d[6]
        val rhd = Settings.System.getInt(ctx.contentResolver, "DRIVINGS_SEAT", 0) == 1
        val open = buildSet {
            val (fl, fr, rl, rr) = if (rhd) listOf(6, 7, 4, 5) else listOf(7, 6, 5, 4)
            if (bit(b6, fl)) add(Door.FL)
            if (bit(b6, fr)) add(Door.FR)
            if (bit(b6, rl)) add(Door.RL)
            if (bit(b6, rr)) add(Door.RR)
            if (bit(b6, 3)) add(Door.TRUNK)
        }
        val fuel = (d[7].toInt() and 0xFF).takeIf { it in 1..127 }?.toFloat() ?: last.fuelL
        val gear = when (d[3].toInt()) {
            0 -> "P"; 1 -> "R"; 2 -> "N"; 4 -> "D"; 5 -> "S"; 6 -> "M"; else -> null
        }
        val belt = d.size > 8 && (d[8].toInt() shr 6) and 3 == 1
        val temp = if (d.size > 9) {
            val t = d[9].toInt()
            (t and 0x7F) / 2f * (if (t and 0x80 != 0) -1 else 1)
        } else last.outsideC
        publish(last.copy(fuelL = fuel, open = open, gear = gear, beltWarn = belt, outsideC = temp))
    }

    private fun readFuelSetting(ctx: Context) {
        val v = try {
            Settings.System.getInt(ctx.contentResolver, KEY_FUEL, -1)
        } catch (e: Exception) {
            Log.w("CanSource", "OIlValue: $e")
            -1
        }
        if (v in 1..127) publish(last.copy(fuelL = v.toFloat()))
    }

    /**
     * Przycisk telefonu: przy rozmowie BMW wysyła przez CAN klawisz telefonu (ramka MCU `02 22 12 …`,
     * w CenterService doKey=0x2c), na który radio otwierało okienko telefonu Witstek (wyłączone przez
     * `pm disable com.wits.bt/com.wits.bt.StartActivity`). Kokpit otwiera wtedy ekran iDrive, gdzie jest telefon BMW.
     */
    private fun isPhoneKey(b: ByteArray) = b.size >= 4 && b[0].toInt() == 0x02 && b[1].toInt() == 0x22 && b[2].toInt() == 0x12 && b[3].toInt() == 0x00

    private var lastPhoneKey = 0L

    private fun phoneKey(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPhoneKey < 3000) return
        lastPhoneKey = now
        Log.i("CanSource", "telefon BMW – ekran iDrive")
        if (Prefs.phoneToIdrive) openCarScreen(ctx)
    }

    /**
     * Przechył pokrętła iDrive w górę / w dół: ramki MCU `02 22 21 01` / `02 22 22 01` (bez puszczenia),
     * których CenterService nie zamienia na klawisz Androida (lewo/prawo `23`/`24` – tak, jako strzałki).
     * Kokpit robi z nich strzałki góra/dół we własnym oknie – przesuwają zaznaczenie między blokami.
     */
    private fun tiltKey(b: ByteArray): Int? {
        if (b.size < 4 || b[0].toInt() != 0x02 || b[1].toInt() != 0x22 || b[3].toInt() != 0x01) return null
        return when (b[2].toInt()) {
            0x21 -> android.view.KeyEvent.KEYCODE_DPAD_UP
            0x22 -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            else -> null
        }
    }

    private fun tilt(code: Int) {
        val act = MainActivity.live ?: return
        // tylko gdy Kokpit jest na ekranie (nie przy okienku asystenta ani innej aplikacji)
        if (!act.visible.value || !act.hasWindowFocus()) return
        act.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        act.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    private val getProp: java.lang.reflect.Method? = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    } catch (_: Exception) {
        null
    }

    /** Prędkość z auta w km/h albo null, gdy radio jej nie podaje. */
    fun speedKmh(): Int? = try {
        (getProp?.invoke(null, "can.speed") as? String)?.trim()?.toIntOrNull()?.takeIf { it in 0..300 }
    } catch (_: Exception) {
        null
    }

    /**
     * Drzwi bez dzienników: CenterService zapisuje bajt drzwi (szesnastkowo, strony już zamienione wg DRIVINGS_SEAT)
     * we właściwości `can.door`. Używane, gdy odczyt dzienników jest wyłączony albo jeszcze bez zgody.
     */
    private fun doorsFromProp(): Set<Door>? {
        val v = try {
            (getProp?.invoke(null, "can.door") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.toInt(16)
        } catch (_: Exception) {
            null
        } ?: return null
        return buildSet {
            if (v and 0x80 != 0) add(Door.FL)
            if (v and 0x40 != 0) add(Door.FR)
            if (v and 0x20 != 0) add(Door.RL)
            if (v and 0x10 != 0) add(Door.RR)
            if (v and 0x08 != 0) add(Door.TRUNK)
        }
    }

    /** proces logcat czytający ramki; null = dzienniki nie są czytane */
    @Volatile
    private var logProc: Process? = null
    /** kiedy ostatnio przyszła ramka z dziennika – wtedy drzwi bierzemy z ramek, nie z właściwości */
    @Volatile
    private var lastFrameAt = 0L

    /** Włączenie/wyłączenie odczytu dzienników w Ustawieniach (wyłączenie kończy logcat od razu). */
    fun logsChanged() {
        if (!Prefs.canLogs) runCatching { logProc?.destroy() }
    }

    private val FRAME = Regex("doMcuData[A-Za-z0-9]*:\\s*((?:[0-9a-fA-F]{2}\\s*)+)$")

    /** Czyta ramki z logu CenterService; po zakończeniu procesu logcat próbuje ponownie. */
    private fun startLogReader(ctx: Context) {
        if (ctx.checkSelfPermission("android.permission.READ_LOGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("CanSource", "brak READ_LOGS – drzwi, bieg i temperatura z CAN niedostępne")
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                if (!Prefs.canLogs) { Thread.sleep(3000); continue }
                try {
                    val since = System.currentTimeMillis()
                    val p = ProcessBuilder("logcat", "-v", "epoch", "-T", "500", "-s", "McuManager:D").redirectErrorStream(true).start()
                    logProc = p
                    p.inputStream.bufferedReader().forEachLine { line ->
                        val hex = FRAME.find(line.trim())?.groupValues?.get(1) ?: return@forEachLine
                        val bytes = hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
                        lastFrameAt = System.currentTimeMillis()
                        if (bytes.size > 1 && bytes[1].toInt() == FRAME_CAR) main.post { parse(ctx, bytes) }
                        // przycisk telefonu z BMW (rozmowa) – tylko świeże ramki, nie zaległe z logu
                        val at = line.trim().substringBefore(' ').toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
                        if (isPhoneKey(bytes) && at >= since - 2000) main.post { phoneKey(ctx) }
                        if (at >= since - 2000) tiltKey(bytes)?.let { code -> main.post { tilt(code) } }
                    }
                    p.waitFor()
                    logProc = null
                } catch (e: Exception) {
                    Log.w("CanSource", "logcat: $e")
                }
                Thread.sleep(5000)
            }
        }, "can-log").apply { isDaemon = true }.start()
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        readFuelSetting(ctx)
        startLogReader(ctx)
        // drzwi z właściwości systemu co 1 s – tylko gdy nie ma ramek z dziennika (wyłączone / brak zgody)
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                if (logProc == null || lastFrameAt == 0L) {
                    val doors = doorsFromProp()
                    if (doors != null && doors != last.open) publish(last.copy(open = doors))
                }
                h.postDelayed(this, 1000)
            }
        })
        try {
            ctx.contentResolver.registerContentObserver(
                Settings.System.getUriFor(KEY_FUEL), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = readFuelSetting(ctx)
                },
            )
        } catch (e: Exception) {
            Log.w("CanSource", "observer: $e")
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
    }
}
