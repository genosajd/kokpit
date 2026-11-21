package pl.lawinski.radiolauncher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

/*
 * Próba odczytu OBD przez Bluetooth (moduł ELM327 sparowany z Bluetoothem Androida w radiu, np. „V-LINK”).
 * Tylko do badań przez ADB: am broadcast -a pl.lawinski.radiolauncher.TEST --es obd "ATZ;ATE0;0100" [--es obdname V-LINK]
 * Każde wywołanie łączy się, wysyła polecenia po kolei i rozłącza; wynik w logu KokpitTest i w files/obd_probe.txt.
 */
object ObdProbe {
    private const val TAG = "KokpitTest"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile
    private var busy = false

    @SuppressLint("MissingPermission")
    fun run(ctx: Context, commands: String, name: String) {
        if (busy) { Log.i(TAG, "obd: poprzednia próba jeszcze trwa"); return }
        busy = true
        Thread {
            val out = StringBuilder()
            fun say(s: String) { Log.i(TAG, "obd: $s"); out.append(s).append('\n') }
            var socket: BluetoothSocket? = null
            try {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) { say("Bluetooth Androida wyłączony"); return@Thread }
                val dev = adapter.bondedDevices.firstOrNull { (it.name ?: "").contains(name, ignoreCase = true) }
                if (dev == null) { say("brak sparowanego urządzenia „$name”; sparowane: " + adapter.bondedDevices.joinToString { it.name ?: "?" }); return@Thread }
                say("łączę z ${dev.name}")
                runCatching { adapter.cancelDiscovery() } // wymaga BLUETOOTH_SCAN – bez niej pomijamy
                socket = connect(dev, ::say) ?: return@Thread
                say("połączono")
                val input = socket.inputStream
                val output = socket.outputStream
                for (cmd in commands.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                    val t0 = System.currentTimeMillis()
                    output.write((cmd + "\r").toByteArray()); output.flush()
                    val sb = StringBuilder()
                    val limit = if (cmd.equals("ATZ", true)) 6000 else 12000
                    while (System.currentTimeMillis() - t0 < limit) {
                        if (input.available() > 0) {
                            val c = input.read()
                            if (c == '>'.code) break
                            if (c >= 0) sb.append(c.toChar())
                        } else Thread.sleep(15)
                    }
                    val ms = System.currentTimeMillis() - t0
                    say("$cmd -> " + sb.toString().replace('\r', '|').replace("\n", "").trim('|', ' ') + "  (${ms} ms)")
                }
            } catch (e: Exception) {
                say("błąd: $e")
            } finally {
                runCatching { socket?.close() }
                runCatching { File(ctx.filesDir, "obd_probe.txt").appendText(out.toString() + "---\n") }
                say("koniec")
                busy = false
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice, say: (String) -> Unit): BluetoothSocket? {
        val tries = listOf<Pair<String, () -> BluetoothSocket>>(
            "SPP bez szyfrowania" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "SPP" to { dev.createRfcommSocketToServiceRecord(SPP) },
            "kanał 1" to { dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
        )
        for ((label, make) in tries) {
            try {
                val s = make()
                s.connect()
                say("sposób: $label")
                return s
            } catch (e: Exception) {
                say("$label: ${e.message}")
            }
        }
        return null
    }
}
