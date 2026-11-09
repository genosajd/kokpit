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
        NavState.info.value = NavInfo(sbn.packageName, title, text, sub, largeIcon(n), n.contentIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == currentKey) {
            currentKey = null
            NavState.info.value = null
        }
    }

    override fun onListenerDisconnected() {
        NavState.info.value = null
    }

    private fun largeIcon(n: Notification): Bitmap? = try {
        val icon: Icon? = n.getLargeIcon()
        icon?.loadDrawable(this)?.toBitmap(128, 128)
    } catch (e: Exception) {
        null
    }

    companion object {
        val NAV_APPS = setOf("com.google.android.apps.maps", "com.waze")

        fun isEnabled(ctx: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
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
