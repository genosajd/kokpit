package pl.lawinski.radiolauncher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap

/** Czyta trwające powiadomienie nawigacji Map Google lub Waze (następny manewr, odległość, czas dojazdu). */
class NavListener : NotificationListenerService() {
    private var currentKey: String? = null

    override fun onListenerConnected() {
        live = this
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NAV_APPS || !sbn.isOngoing) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        currentKey = sbn.key
        NavPopups.channel = sbn.packageName to n.channelId
        NavPopups.apply(this, true)
        NavState.info.value = NavInfo(sbn.packageName, title, text, sub, largeIcon(n), n.contentIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == currentKey) {
            currentKey = null
            NavState.info.value = null
            NavPopups.apply(this, false)
        }
    }

    override fun onListenerDisconnected() {
        if (live === this) live = null
        NavState.info.value = null
        NavPopups.apply(this, false)
    }

    private fun largeIcon(n: Notification): Bitmap? = try {
        val icon: Icon? = n.getLargeIcon()
        icon?.loadDrawable(this)?.toBitmap(128, 128)
    } catch (e: Exception) {
        null
    }

    companion object {
        val NAV_APPS = setOf("com.google.android.apps.maps", "com.waze")

        @Volatile
        private var live: NavListener? = null

        /** Duży obrazek z powiadomienia odtwarzacza [pkg] (okładka utworu), jeśli takie powiadomienie jest. */
        fun largeIcon(pkg: String): android.graphics.Bitmap? {
            val svc = live ?: return null
            return try {
                svc.activeNotifications.orEmpty().filter { it.packageName == pkg }.firstNotNullOfOrNull { sbn ->
                    val d = sbn.notification.getLargeIcon()?.loadDrawable(svc) ?: return@firstNotNullOfOrNull null
                    val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
                    val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
                    // rysujemy na zwykłej bitmapie – obrazek z powiadomienia bywa „sprzętowy” i nie da się go skalować
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        d.setBounds(0, 0, w, h)
                        d.draw(android.graphics.Canvas(b))
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
}

/**
 * Wyskakujące z góry komunikaty nawigacji (Mapy Google w tle pokazują każdy manewr jako okienko) – zbędne,
 * bo manewry są w bloku „Nawigacja”. Dwie drogi:
 * - automatycznie: na czas nawigacji Kokpit wyłącza w systemie wyskakujące powiadomienia
 *   (`heads_up_notifications_enabled`), po niej włącza z powrotem. Wymaga zgody nadanej raz przez ADB:
 *   `pm grant pl.lawinski.radiolauncher android.permission.WRITE_SECURE_SETTINGS`;
 * - ręcznie: Kokpit otwiera ustawienia powiadomień Map, gdzie wyłącza się „Wyświetlaj na ekranie”.
 */
object NavPopups {
    private const val KEY = "heads_up_notifications_enabled"

    /** pakiet i kanał ostatniego powiadomienia nawigacji – do otwarcia jego ustawień */
    @Volatile
    var channel: Pair<String, String?>? = null

    fun canAuto(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Ustawia system wg ustawienia Kokpitu i tego, czy trwa nawigacja. Przywraca tylko to, co sam wyłączył. */
    fun apply(ctx: Context, navActive: Boolean) {
        if (!canAuto(ctx)) return
        Prefs.init(ctx)
        val cr = ctx.contentResolver
        try {
            if (Prefs.hideNavPopups && navActive) {
                if (android.provider.Settings.Global.getInt(cr, KEY, 1) != 0) {
                    android.provider.Settings.Global.putInt(cr, KEY, 0)
                    Prefs.navPopupsHidden = true
                }
            } else if (Prefs.navPopupsHidden) {
                android.provider.Settings.Global.putInt(cr, KEY, 1)
                Prefs.navPopupsHidden = false
            }
        } catch (e: Exception) {
            android.util.Log.w("NavPopups", "ustawienie: $e")
        }
    }

    /** Ustawienia powiadomień Map (kanał nawigacji, jeśli znany) – tam „Wyświetlaj na ekranie”. */
    fun openSettings(ctx: Context) {
        val (pkg, ch) = channel ?: ("com.google.android.apps.maps" to null)
        val app = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val one = ch?.let {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(one ?: app)
        } catch (_: Exception) {
            runCatching { ctx.startActivity(app) }
        }
    }
}

/** Usługa ułatwień dostępu tylko po to, by przełączyć podzielony ekran; nie czyta zawartości okien. */
class SplitService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Przycisk głosowy na kierownicy: przechwycony, zanim system zamieni go na wyszukiwanie Google
     * (które w radiu otwierało ustawienia domyślnego asystenta). Otwiera okienko asystenta Kokpitu.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            android.util.Log.i("KokpitKeys", "klawisz ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) z ${event.deviceId}")
        if (event.keyCode !in VOICE_KEYS) return false
        if (event.action == KeyEvent.ACTION_UP) {
            Speech.stop()
            VoiceActivity.start(this)
        }
        return true
    }

    companion object {
        @Volatile
        var instance: SplitService? = null

        private val VOICE_KEYS = setOf(KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH)
    }
}

/**
 * Otwiera dwie aplikacje obok siebie: lewą na pełnym ekranie, potem dzieli ekran
 * (przez [SplitService], jeśli jest włączona) i otwiera prawą w drugiej połowie.
 * Bez usługi próbuje samej flagi LAUNCH_ADJACENT, co część systemów też obsługuje.
 */
fun openSplit(ctx: Context, left: String, right: String) {
    val handler = Handler(Looper.getMainLooper())
    val adjacent = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
    if (!Apps.launch(ctx, left)) return
    val svc = SplitService.instance
    if (svc != null) {
        handler.postDelayed({
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            handler.postDelayed({ Apps.launch(ctx, right, adjacent) }, 900)
        }, 900)
    } else {
        handler.postDelayed({ Apps.launch(ctx, right, adjacent) }, 700)
    }
}
