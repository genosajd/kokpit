package pl.lawinski.radiolauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/*
 * Okno rozmowy nad dowolną aplikacją (przychodzące zawsze; wychodzące, gdy Kokpit nie jest na wierzchu) –
 * ten sam mechanizm co okienko asystenta (VoiceActivity). Pokazuje CallWindow (zdjęcie, numer, historia,
 * głośność, odbierz / rozłącz), samo się zamyka, gdy rozmowa się kończy (Phone woła closeIfOpen()).
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Phone.install(this)
        live = this
        setContent {
            val call by Phone.call
            if (!call.active) { finish(); return@setContent }
            val theme = effectiveTheme()
            BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                // ta sama skala co na ekranie głównym: projekt 2560×720
                val u = min(maxWidth / 2560f, maxHeight / 720f)
                CompositionLocalProvider(
                    LocalTheme provides theme,
                    LocalU provides u,
                    androidx.compose.material3.LocalTextStyle provides androidx.compose.material3.LocalTextStyle.current.copy(
                        fontFamily = Fonts.family(ThemeCustoms.current().font),
                    ),
                ) {
                    val shape = theme.shape(d(28))
                    Box(
                        Modifier.fillMaxWidth(if (call.ringing) 0.46f else 0.8f).clip(shape)
                            .background(if (LightMode.on) Color(0xFFF4F6FA) else Color(0xFF0A0D14))
                            .background(theme.block)
                            .border(1.dp, theme.blockEdge, shape).padding(horizontal = d(36), vertical = d(30)),
                    ) {
                        CallWindow(this@CallActivity, call, onMinimize = { Phone.minimize(this@CallActivity) })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var live: CallActivity? = null

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun closeIfOpen() {
            live?.let { it.runOnUiThread { it.finish() } }
        }
    }
}

/**
 * Pasek zwiniętej rozmowy u góry ekranu, nad dowolną aplikacją: kto i ile trwa; dotknięcie wraca do okna rozmowy.
 * Zwykłe widoki Androida (bez Compose), wymaga zgody „wyświetlanie nad innymi”; bez niej wraca się z bloku Telefon.
 */
object CallPill {
    private var view: android.widget.TextView? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { update(); h.postDelayed(this, 1000) }
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        if (view != null || !android.provider.Settings.canDrawOverlays(app)) return
        val dm = app.resources.displayMetrics
        val u = minOf(dm.widthPixels / 2560f, dm.heightPixels / 720f)
        val tv = android.widget.TextView(app).apply {
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * u)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            minWidth = (520 * u).toInt()
            gravity = android.view.Gravity.CENTER
            setPadding((28 * u).toInt(), (14 * u).toInt(), (28 * u).toInt(), (14 * u).toInt())
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xF235C467.toInt(), 0xF2178A41.toInt()),
            ).apply { setStroke((2 * u).toInt().coerceAtLeast(1), 0x66FFFFFF) }
            setOnClickListener { Phone.restore(app) }
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = (6 * u).toInt()
        }
        runCatching {
            view = tv
            update() // tekst przed dodaniem, żeby pasek od razu miał właściwą szerokość
            (app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).addView(tv, lp)
            h.postDelayed(tick, 1000)
        }
    }

    fun hide() {
        h.removeCallbacks(tick)
        val v = view ?: return
        view = null
        runCatching { (v.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }
    }

    private fun update() {
        val v = view ?: return
        val c = Phone.call.value
        if (!c.active) { hide(); return }
        val who = c.name.ifBlank { c.number }
        val what = if (c.state == Phone.ACTIVE) {
            val sec = ((System.currentTimeMillis() - c.since) / 1000).coerceAtLeast(0)
            "%02d:%02d".format(sec / 60, sec % 60)
        } else "dzwonię…"
        v.text = "Rozmowa · $who · $what"
    }
}
