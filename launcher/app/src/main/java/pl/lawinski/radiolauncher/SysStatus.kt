package pl.lawinski.radiolauncher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Stan łączności i zasilania do górnego paska: Wi-Fi (siła sygnału), sieć komórkowa (LTE/5G/3G, siła),
 * akumulator. Odczyt co 5 s, bez dodatkowych zgód (ACCESS_NETWORK_STATE i ACCESS_WIFI_STATE są zwykłe).
 * Akumulator: gdy system podaje napięcie w zakresie instalacji auta (9–16,5 V), pokazujemy napięcie,
 * inaczej poziom w %.
 */
data class SysStatus(
    val wifiLevel: Int? = null,      // 0–3; null = brak Wi-Fi
    val cellLevel: Int? = null,      // 0–4; null = brak sieci komórkowej / karty SIM
    val cellType: String = "",
    val online: Boolean = false,     // czy jest internet (sprawdzony przez system)
    val batteryPct: Int? = null,
    val batteryVolt: Float? = null,
    val charging: Boolean = false,
    val cpuPct: Int? = null,         // obciążenie procesora od poprzedniego odczytu
    val cpuOwn: Boolean = false,     // true = tylko Kokpit (system nie udostępnia całości)
    val ramPct: Int? = null,         // zajęta pamięć
)

object SysStatusReader {
    fun read(ctx: Context): SysStatus {
        var wifi: Int? = null
        var cellData = false
        var online = false
        runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) online = true
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val rssi = if (Build.VERSION.SDK_INT >= 29 && c.signalStrength != Int.MIN_VALUE) c.signalStrength
                    else @Suppress("DEPRECATION") ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.rssi
                    wifi = when {
                        rssi >= -55 -> 3
                        rssi >= -67 -> 2
                        rssi >= -80 -> 1
                        else -> 0
                    }
                }
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellData = true
            }
        }
        var cell: Int? = null
        var type = ""
        runCatching {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            if (tm != null && tm.simState == TelephonyManager.SIM_STATE_READY && Build.VERSION.SDK_INT >= 28) {
                val ss = tm.signalStrength
                if (ss != null) {
                    cell = ss.level.coerceIn(0, 4)
                    type = when (ss.cellSignalStrengths.firstOrNull()) {
                        is CellSignalStrengthNr -> "5G"
                        is CellSignalStrengthLte -> "LTE"
                        is CellSignalStrengthWcdma, is CellSignalStrengthTdscdma -> "3G"
                        is CellSignalStrengthGsm -> "2G"
                        else -> ""
                    }
                }
            }
        }
        if (cell == null && cellData) cell = 4
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var pct: Int? = null
        var volt: Float? = null
        var charging = false
        if (b != null && b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) pct = level * 100 / scale
            val mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            if (mv in 9000..16500) volt = mv / 1000f
            val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        }
        // radio Witstek: bateria Androida jest udawana, a napięcie akumulatora auta podaje MCU (143 = 14,3 V)
        val ram = runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else null
        }.getOrNull()
        val cpu = CpuLoad.read()
        carBatteryVolt()?.let { return SysStatus(wifi, cell, type, online, null, it, false, cpu?.first, cpu?.second ?: false, ram) }
        return SysStatus(wifi, cell, type, online, pct, volt, charging, cpu?.first, cpu?.second ?: false, ram)
    }
}

/**
 * Obciążenie procesora między kolejnymi odczytami. Cały system z /proc/stat (radio Witstek na to pozwala);
 * gdzie Android tego zabrania, sam proces Kokpitu jako część wszystkich rdzeni.
 */
object CpuLoad {
    private var busy = 0L
    private var total = 0L
    private var ownCpu = 0L
    private var ownWall = 0L

    /** procent i znacznik „tylko Kokpit”; null przy pierwszym odczycie */
    @Synchronized
    fun read(): Pair<Int, Boolean>? {
        val line = runCatching { java.io.File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
        val f = line?.takeIf { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
        if (f != null && f.size >= 4) {
            val all = f.take(8).sum()
            val idle = f[3] + (f.getOrNull(4) ?: 0L)
            val dAll = all - total
            val dBusy = (all - idle) - busy
            val had = total > 0
            total = all; busy = all - idle
            return if (had && dAll > 0) (dBusy * 100 / dAll).toInt().coerceIn(0, 100) to false else null
        }
        val cpu = android.os.Process.getElapsedCpuTime()
        val wall = android.os.SystemClock.elapsedRealtime()
        val dCpu = cpu - ownCpu
        val dWall = wall - ownWall
        val had = ownWall > 0
        ownCpu = cpu; ownWall = wall
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return if (had && dWall > 0) (dCpu * 100 / (dWall * cores)).toInt().coerceIn(0, 100) to true else null
    }
}

/** Napięcie akumulatora auta z właściwości systemowej Witstek „wits.battery.vol” (dziesiąte części V). */
fun carBatteryVolt(): Float? {
    val raw = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "wits.battery.vol") as String
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return raw.toIntOrNull()?.takeIf { it in 50..200 }?.div(10f)
}

/** Ikonki zasięgu i akumulatora do górnego paska. */
@Composable
fun SystemIcons() {
    val ctx = LocalContext.current
    val st by produceState(SysStatus()) {
        while (true) {
            value = withContext(Dispatchers.Default) { runCatching { SysStatusReader.read(ctx) }.getOrDefault(SysStatus()) }
            delay(5000)
        }
    }
    val phoneOn by Phone.connected
    Row(verticalAlignment = Alignment.CenterVertically) {
        st.cpuPct?.let { v ->
            StatIcon(Icons.Rounded.DeveloperBoard, Dim)
            Spacer(Modifier.width(d(4)))
            Text("$v%", color = if (v >= 90) Warn else Txt, fontSize = s(22))
            Spacer(Modifier.width(d(16)))
        }
        st.ramPct?.let { v ->
            StatIcon(Icons.Rounded.Memory, Dim)
            Spacer(Modifier.width(d(4)))
            Text("$v%", color = if (v >= 90) Warn else Txt, fontSize = s(22))
            Spacer(Modifier.width(d(20)))
        }
        // telefon połączony z Bluetoothem radia (dzwonienie przez radio)
        if (phoneOn) {
            StatIcon(Icons.Rounded.Bluetooth)
            Spacer(Modifier.width(d(16)))
        }
        st.cellLevel?.let { lvl ->
            if (st.cellType.isNotEmpty()) {
                Text(st.cellType, color = Dim, fontSize = s(18), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(d(4)))
            }
            val icon = when {
                !st.online && st.wifiLevel == null -> Icons.Rounded.SignalCellularConnectedNoInternet0Bar
                lvl <= 0 -> Icons.Rounded.SignalCellular0Bar
                lvl == 1 -> Icons.Rounded.SignalCellularAlt1Bar
                lvl == 2 -> Icons.Rounded.SignalCellularAlt2Bar
                else -> Icons.Rounded.SignalCellularAlt
            }
            StatIcon(icon)
            Spacer(Modifier.width(d(16)))
        }
        st.wifiLevel?.let { lvl ->
            val icon = when {
                !st.online -> Icons.Rounded.SignalWifiStatusbarConnectedNoInternet4
                lvl <= 0 -> Icons.Rounded.SignalWifi0Bar
                lvl == 1 -> Icons.Rounded.Wifi1Bar
                lvl == 2 -> Icons.Rounded.Wifi2Bar
                else -> Icons.Rounded.Wifi
            }
            StatIcon(icon)
            Spacer(Modifier.width(d(16)))
        }
        if (st.wifiLevel == null && st.cellLevel == null) {
            StatIcon(Icons.Rounded.SignalWifiOff, Dim)
            Spacer(Modifier.width(d(16)))
        }
        val volt = st.batteryVolt
        val pct = st.batteryPct
        if (volt != null || pct != null) {
            val icon = when {
                volt != null -> if (volt < 11.8f) Icons.Rounded.BatteryAlert else Icons.Rounded.BatteryStd
                st.charging -> Icons.Rounded.BatteryChargingFull
                pct!! >= 95 -> Icons.Rounded.BatteryFull
                pct >= 80 -> Icons.Rounded.Battery6Bar
                pct >= 65 -> Icons.Rounded.Battery5Bar
                pct >= 50 -> Icons.Rounded.Battery4Bar
                pct >= 35 -> Icons.Rounded.Battery3Bar
                pct >= 20 -> Icons.Rounded.Battery2Bar
                pct >= 8 -> Icons.Rounded.Battery1Bar
                else -> Icons.Rounded.BatteryAlert
            }
            val low = (volt != null && volt < 11.8f) || (volt == null && pct != null && pct < 15 && !st.charging)
            StatIcon(icon, if (low) Warn else Txt, rotate = true)
            Spacer(Modifier.width(d(4)))
            Text(
                volt?.let { String.format(Locale("pl", "PL"), "%.1f V", it) } ?: "$pct%",
                color = if (low) Warn else Dim, fontSize = s(22),
            )
        }
    }
}

@Composable
private fun StatIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color = Txt, rotate: Boolean = false) {
    // ikony baterii Material są pionowe – na pasku poziomo
    Icon(icon, null, tint = tint, modifier = Modifier.size(d(28)).then(if (rotate) Modifier.rotate(90f) else Modifier))
}
