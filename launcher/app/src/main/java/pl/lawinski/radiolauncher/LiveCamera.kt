package pl.lawinski.radiolauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Lokalny serwer go2rtc aplikacji Xiaomi Cam działającej na tym samym radiu. */
object XiaomiCamServer {
    private const val API = "http://127.0.0.1:11984/api/streams"
    private const val RTSP = "rtsp://127.0.0.1:18554/"

    /** Nazwy kamer albo null, gdy Xiaomi Cam nie działa. Strumienie techniczne zaczynają się od "__". */
    suspend fun cameras(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(body).keys().asSequence().filter { !it.startsWith("__") }.sorted().toList()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Budzi proces Xiaomi Cam (go2rtc startuje razem z nim), a gdy w Xiaomi Cam włączono
     * "Obraz dla innych aplikacji w tle", także jej usługę, która trzyma serwer stale.
     */
    fun wake(ctx: Context) {
        try {
            ctx.sendBroadcast(Intent(ACTION_WAKE).setPackage(MainActivity.XIAOMI_CAM))
        } catch (_: Exception) {
        }
    }

    /** Lista kamer po obudzeniu Xiaomi Cam; serwer potrzebuje chwili na start. */
    suspend fun camerasWaking(ctx: Context): List<String>? {
        cameras()?.let { return it }
        wake(ctx)
        repeat(5) {
            delay(1000)
            cameras()?.let { return it }
        }
        return null
    }

    private const val ACTION_WAKE = "pl.lawinski.xiaomicam.action.WAKE_SERVER"

    // sam obraz: odtwarzacz nie czyta dźwięku Opus z go2rtc, a w aucie i tak nie jest potrzebny
    fun url(name: String) = RTSP + Uri.encode(name) + "?video"
}

/**
 * Obraz z kamery na żywo (RTSP po TCP). Przy błędzie co 10 s próbuje ponownie,
 * a [onState] dostaje true, gdy obraz leci.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveCameraView(name: String, modifier: Modifier = Modifier, onProblem: (String?) -> Unit = {}, onState: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val player = remember(name, attempt) { ExoPlayer.Builder(ctx).build().apply { volume = 0f } }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    onProblem(null)
                    onState(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // dekoder radia (SM6125) przyjmuje najwyżej 1920x1088; strumień 2.5K go przerasta
                val tooBig = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    // dekoder rusza, ale pada na pierwszej klatce (tak było z 2560x1440 HEVC)
                    ((error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.let { it.width > 1920 || it.height > 1088 } == true)
                onProblem(if (tooBig) "Radio nie odtworzy obrazu 2.5K – w Xiaomi Cam wybierz 720p" else null)
                failed = true
                onState(false)
            }
        }
        player.addListener(listener)
        val src = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
            .createMediaSource(MediaItem.fromUri(XiaomiCamServer.url(name)))
        player.setMediaSource(src)
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // najpierw obudzić Xiaomi Cam i poczekać, aż jej serwer odpowie; dopiero potem łączyć
    LaunchedEffect(player) {
        XiaomiCamServer.camerasWaking(ctx)
        player.prepare()
    }

    LaunchedEffect(failed) {
        if (failed) {
            delay(10_000)
            failed = false
            attempt++
        }
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        modifier = modifier.fillMaxSize(),
    )
}
