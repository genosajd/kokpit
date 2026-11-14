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
                        CallWindow(this@CallActivity, call)
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
