@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package pl.lawinski.radiolauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/*
 * Blok „Multimedia”: steruje odtwarzaczem, który gra albo grał ostatnio (YouTube, SoundCloud, dowolny inny) –
 * przez sesje multimediów Androida, bez wiedzy o konkretnej aplikacji. Pokazuje okładkę, tytuł i wykonawcę,
 * pozwala wstrzymać/wznowić, zmienić utwór i przewinąć. Lista sesji wymaga dostępu do powiadomień
 * (tego samego, co podgląd trasy – NavListener). Gdy odtwarzacz dawno wstrzymany nie ma już sesji,
 * przycisk „graj” wysyła mu przycisk multimediów (wznawia bez otwierania aplikacji).
 */
object MediaHub {
    private const val TAG = "KokpitMedia"

    class Now(
        val pkg: String,
        val app: String,
        val title: String,
        val artist: String,
        val art: ImageBitmap?,
        val playing: Boolean,
        /** pozycja w ms w chwili [positionAt] (zegar elapsedRealtime) */
        val position: Long,
        val positionAt: Long,
        val speed: Float,
        val duration: Long,
        val canSeek: Boolean,
        /** jest żywa sesja (inaczej tylko zapamiętany ostatni odtwarzacz) */
        val live: Boolean,
    ) {
        fun positionNow(): Long {
            if (!playing || duration <= 0) return position.coerceAtLeast(0)
            return (position + ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()).coerceIn(0, duration)
        }
    }

    val now = mutableStateOf<Now?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var msm: MediaSessionManager? = null
    private val callbacks = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    /** skąd jest bieżąca okładka: bitmapa z sesji, adres okładki albo „powiadomienie pakietu + tytuł” */
    private var artKey: Any? = null
    private var art: ImageBitmap? = null
    private val artIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Okładka utworu. Część odtwarzaczy (np. SoundCloud) nie wkłada obrazka do sesji multimediów –
     * podaje tylko jego adres albo pokazuje go wyłącznie w swoim powiadomieniu. Wtedy pobieramy ją w tle.
     */
    private fun cover(c: MediaController, md: MediaMetadata?, title: String) {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            if (bmp !== artKey) {
                artKey = bmp
                art = runCatching { shrink(bmp).asImageBitmap() }.getOrNull()
            }
            return
        }
        val uri = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }
        val pkg = c.packageName
        val key = uri ?: "powiadomienie:$pkg|$title"
        if (key == artKey) return
        artKey = key
        art = null
        artIo.execute {
            var b = uri?.let { runCatching { load(it) }.getOrNull() } ?: NavListener.largeIcon(pkg)
            if (b == null) {
                // powiadomienie odtwarzacza bywa odświeżane chwilę po zmianie utworu
                Thread.sleep(1500)
                b = NavListener.largeIcon(pkg)
            }
            android.util.Log.d("KokpitMedia", "okładka $pkg: sesja bez obrazka, adres=${uri != null}, wynik=${b != null}")
            val img = b?.let { runCatching { shrink(it).asImageBitmap() }.getOrNull() }
            main.post {
                if (artKey == key && img != null) {
                    art = img
                    pick()
                }
            }
        }
    }

    private fun load(uri: String): Bitmap? {
        val stream = if (uri.startsWith("http://") || uri.startsWith("https://")) {
            (java.net.URL(uri).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }.inputStream
        } else ctx?.contentResolver?.openInputStream(android.net.Uri.parse(uri))
        return stream?.use { android.graphics.BitmapFactory.decodeStream(it) }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { setControllers(it.orEmpty()) }

    /** Zaczyna śledzić odtwarzacze; false = brak dostępu do powiadomień. */
    fun attach(c: Context): Boolean {
        if (msm != null) return true
        val app = c.applicationContext
        ctx = app
        return try {
            val m = app.getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(app, NavListener::class.java)
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            msm = m
            setControllers(m.getActiveSessions(cn))
            true
        } catch (e: SecurityException) {
            showLast()
            false
        }
    }

    fun detach() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        msm = null
        callbacks.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        current = null
    }

    private fun setControllers(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it !in tokens }.forEach { t ->
            callbacks.remove(t)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        }
        for (c in list) {
            if (callbacks.containsKey(c.sessionToken)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
                override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
                override fun onSessionDestroyed() = pick()
            }
            runCatching { c.registerCallback(cb, main); callbacks[c.sessionToken] = c to cb }
        }
        pick()
    }

    /** Wybiera odtwarzacz: ten, który gra; inaczej dotychczasowy; inaczej pierwszy z listy (ostatnio używany). */
    private fun pick() {
        if (msm == null) return
        val list = callbacks.values.map { it.first }
        val playing = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val cur = current?.takeIf { c -> list.any { it.sessionToken == c.sessionToken } }
        // system podaje sesje od ostatnio aktywnej
        val order = runCatching { msm?.getActiveSessions(ComponentName(ctx!!, NavListener::class.java)) }.getOrNull().orEmpty()
        val c = playing ?: cur ?: order.firstOrNull()?.let { o -> list.firstOrNull { it.sessionToken == o.sessionToken } } ?: list.firstOrNull()
        current = c
        if (c == null) return showLast()
        val md = c.metadata
        val st = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        cover(c, md, title)
        if (Prefs.lastMediaPkg != c.packageName) Prefs.lastMediaPkg = c.packageName
        if (title.isNotEmpty() && Prefs.lastMediaTitle != title) Prefs.lastMediaTitle = title
        now.value = Now(
            pkg = c.packageName, app = label(c.packageName), title = title, artist = artist, art = art,
            playing = st?.state == PlaybackState.STATE_PLAYING,
            position = st?.position ?: 0L,
            positionAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = st?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            canSeek = (st?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            live = true,
        )
    }

    /** Brak sesji: pokazujemy ostatni odtwarzacz, żeby dało się go wznowić. */
    private fun showLast() {
        val pkg = Prefs.lastMediaPkg
        now.value = if (pkg.isEmpty()) null else Now(pkg, label(pkg), Prefs.lastMediaTitle, "", null, false, 0, 0, 1f, 0, false, false)
    }

    private fun label(pkg: String): String {
        val pm = ctx?.packageManager ?: return pkg
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Okładki bywają duże (np. 1280 px z YouTube) – do bloku wystarczy 400 px. */
    private fun shrink(b: Bitmap): Bitmap {
        val max = maxOf(b.width, b.height)
        if (max <= 400) return b
        val k = 400f / max
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    fun playPause() {
        val c = current
        if (c == null) return key(KeyEvent.KEYCODE_MEDIA_PLAY)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = current?.transportControls?.skipToNext() ?: key(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = current?.transportControls?.skipToPrevious() ?: key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    fun seekTo(ms: Long) { current?.transportControls?.seekTo(ms) }

    /** Przycisk multimediów wprost do ostatniego odtwarzacza (gdy nie ma już sesji). */
    private fun key(code: Int) {
        val c = ctx ?: return
        val pkg = Prefs.lastMediaPkg
        try {
            if (pkg.isNotEmpty()) {
                for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(a, code)))
                }
            } else {
                val am = c.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        } catch (e: Exception) {
            Log.w(TAG, "przycisk: $e")
        }
    }

    /** Otwiera aplikację odtwarzacza (jej ekran „teraz gra”, jeśli go podaje). */
    fun open(act: Context) {
        val pkg = now.value?.pkg ?: return
        val pi = current?.sessionActivity
        if (pi != null && runCatching { pi.send() }.isSuccess) return
        act.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
            runCatching { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MediaBlock(modifier: Modifier, slot: Slot, act: MainActivity) {
    val visible by act.visible
    val tick = act.resumeTick.intValue
    var access by remember { mutableStateOf(true) }
    // śledzenie odtwarzaczy tylko, gdy Kokpit jest na ekranie
    DisposableEffect(visible, tick) {
        if (visible) access = MediaHub.attach(act)
        onDispose { MediaHub.detach() }
    }
    val now by MediaHub.now
    // pozycja utworu: co sekundę, tylko gdy gra i blok widać
    var pos by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(now, visible) {
        val n = now ?: return@LaunchedEffect
        pos = n.positionNow()
        while (visible && n.playing && n.duration > 0) {
            delay(1000)
            pos = n.positionNow()
        }
    }
    Block(modifier) {
        val n = now
        if (n == null) {
            Column(
                Modifier.fillMaxSize().combinedClickable(onClick = {
                    if (!access) runCatching { act.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }).padding(d(24)),
            ) {
                Label(Icons.Outlined.MusicNote, "Multimedia")
                Spacer(Modifier.weight(1f))
                Text(if (access) "Nic nie gra" else "Zezwól na dostęp do powiadomień", color = if (access) Dim else Accent, fontSize = s(24))
                Text(if (access) "Włącz muzykę albo film w dowolnej aplikacji" else "Kokpit zobaczy wtedy, co gra, i będzie tym sterować",
                    color = Dim, fontSize = s(19), modifier = Modifier.padding(top = d(8)))
                Spacer(Modifier.weight(1f))
            }
            return@Block
        }
        val wide = slot.w >= 2
        val tall = slot.h >= 2
        val controls: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                if (n.live && n.duration > 0) {
                    val frac = if (drag >= 0f) drag else (pos.toFloat() / n.duration).coerceIn(0f, 1f)
                    Slider(
                        value = frac,
                        onValueChange = { if (n.canSeek) drag = it },
                        onValueChangeFinished = {
                            if (drag >= 0f) {
                                val to = (drag * n.duration).toLong()
                                MediaHub.seekTo(to); pos = to; drag = -1f
                            }
                        },
                        enabled = n.canSeek,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Line,
                            disabledThumbColor = Dim, disabledActiveTrackColor = Dim, disabledInactiveTrackColor = Line,
                        ),
                        modifier = Modifier.fillMaxWidth().height(d(40)),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(clock(if (drag >= 0f) (drag * n.duration).toLong() else pos), color = Dim, fontSize = s(16))
                        Spacer(Modifier.weight(1f))
                        Text(clock(n.duration), color = Dim, fontSize = s(16))
                    }
                    Spacer(Modifier.height(d(8)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d(10)), verticalAlignment = Alignment.CenterVertically) {
                    val h = if (slot.w == 1) d(54) else d(64)
                    MediaButton(Icons.Rounded.SkipPrevious, "Poprzedni", Modifier.weight(1f).height(h), false) { MediaHub.previous() }
                    MediaButton(if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (n.playing) "Wstrzymaj" else "Graj",
                        Modifier.weight(1.3f).height(h), true) { MediaHub.playPause() }
                    MediaButton(Icons.Rounded.SkipNext, "Następny", Modifier.weight(1f).height(h), false) { MediaHub.next() }
                }
            }
        }
        val titles: @Composable () -> Unit = {
            Text(n.title.ifEmpty { n.app }, color = Txt, fontSize = s(if (wide) 26 else 22), fontWeight = FontWeight.Medium,
                maxLines = if (tall || !wide) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(n.artist, n.app).filter { it.isNotEmpty() && it != n.title }.distinct().joinToString(" · "),
                color = Dim, fontSize = s(18), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = d(4)))
        }
        val cover: @Composable (Modifier) -> Unit = { m ->
            // przesunięcie palcem po okładce (w dowolną stronę) przełącza okładkę i słupki dźwięku; wybór zostaje
            var view by remember { androidx.compose.runtime.mutableIntStateOf(Prefs.mediaView.coerceIn(0, BAR_STYLES)) }
            val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { d(36).toPx() }
            Box(
                m.clip(RoundedCornerShape(d(18))).background(LocalTheme.current.blockEdge)
                    .combinedClickable(onClick = { MediaHub.open(act) })
                    .pointerInput(Unit) {
                        var moved = androidx.compose.ui.geometry.Offset.Zero
                        var done = false
                        detectDragGestures(
                            onDragStart = { moved = androidx.compose.ui.geometry.Offset.Zero; done = false },
                            onDrag = { change, by ->
                                change.consume()
                                moved += by
                                if (!done && moved.getDistance() > swipe) {
                                    done = true
                                    // w lewo albo w górę = następny widok, w prawo albo w dół = poprzedni; bez okładki same słupki
                                    val main = if (kotlin.math.abs(moved.x) >= kotlin.math.abs(moved.y)) moved.x else moved.y
                                    val first = if (n.art != null) 0 else 1
                                    val all = BAR_STYLES + 1 - first
                                    view = first + ((view.coerceAtLeast(first) - first + (if (main < 0) 1 else -1) + all) % all)
                                    Prefs.mediaView = view
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val a = n.art
                val shown = if (a == null) view.coerceAtLeast(1) else view
                Crossfade(shown, label = "okładka") { v ->
                    if (v == 0 && a != null) Image(a, "Okładka", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else SoundBars(n.playing && n.live, v.coerceAtLeast(1), Modifier.fillMaxSize().padding(start = d(22), end = d(22), top = d(22), bottom = d(26)))
                }
                // kropki: który widok jest wybrany (okładka, potem kolejne rodzaje słupków)
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = d(8)), horizontalArrangement = Arrangement.spacedBy(d(6))) {
                    ((if (a != null) 0 else 1)..BAR_STYLES).forEach { i ->
                        val on = i == shown
                        Box(Modifier.size(d(8)).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color.White.copy(alpha = if (on) 0.9f else 0.35f)))
                    }
                }
            }
        }
        when {
            // wysoki blok: okładka na górze, pod nią tytuł i przyciski
            tall -> Column(Modifier.fillMaxSize().padding(d(24))) {
                cover(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(d(14)))
                titles()
                Spacer(Modifier.height(d(10)))
                controls(Modifier.fillMaxWidth())
            }
            // szeroki niski: okładka z lewej
            wide -> Row(Modifier.fillMaxSize().padding(d(22)), verticalAlignment = Alignment.CenterVertically) {
                cover(Modifier.fillMaxHeight().aspectRatio(1f))
                Spacer(Modifier.width(d(22)))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    titles()
                    Spacer(Modifier.weight(1f))
                    controls(Modifier.fillMaxWidth())
                }
            }
            // wąski niski: sam tytuł i przyciski (dotknięcie tytułu otwiera aplikację)
            else -> Column(Modifier.fillMaxSize().padding(d(20))) {
                Column(Modifier.weight(1f).fillMaxWidth().combinedClickable(onClick = { MediaHub.open(act) })) { titles() }
                controls(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, desc: String, modifier: Modifier, main: Boolean, onClick: () -> Unit) {
    Box(modifier.kokpitSurface(d(16), active = main).combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (main) LocalTheme.current.activeText else Txt, modifier = Modifier.size(d(36)))
    }
}


/**
 * Słupki w rytm muzyki – zamiast okładki, gdy odtwarzacz jej nie podaje.
 * Prawdziwy dźwięk bierze z systemowego Visualizer (to, co radio właśnie gra; wymaga zgody na mikrofon,
 * tej samej co hasło głosowe). Gdy system go nie daje albo podaje ciszę, słupki poruszają się same.
 * Działa tylko, gdy coś gra i blok jest widoczny.
 */
@Composable
private fun SoundBars(playing: Boolean, style: Int, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val count = 18
    val levels = remember { FloatArray(count) { 0.06f } }
    // znaczniki szczytów: zostają chwilę na górze i powoli opadają
    val peaks = remember { FloatArray(count) { 0.06f } }
    val frame = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    DisposableEffect(playing) {
        val main = Handler(Looper.getMainLooper())
        var vis: android.media.audiofx.Visualizer? = null
        var silentSince = 0L
        var t = 0f
        fun fake() {
            t += 0.09f
            for (i in 0 until count) {
                val v = 0.2f + 0.6f * kotlin.math.abs(kotlin.math.sin(t * (0.9f + i % 5 * 0.23f) + i * 1.7f)) * (0.55f + 0.45f * kotlin.math.sin(t * 0.6f + i * 0.4f))
                levels[i] = maxOf(v, levels[i] * 0.8f)
            }
        }
        var ticking = true
        val ticker = object : Runnable {
            override fun run() {
                if (!ticking) return
                if (!playing) for (i in 0 until count) levels[i] = maxOf(0.06f, levels[i] * 0.8f)
                else if (vis == null || (silentSince != 0L && SystemClock.elapsedRealtime() - silentSince > 1500)) fake()
                for (i in 0 until count) peaks[i] = maxOf(levels[i], peaks[i] - 0.025f)
                frame.intValue++
                main.postDelayed(this, 80)
            }
        }
        if (playing && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            vis = runCatching {
                android.media.audiofx.Visualizer(0).apply {
                    captureSize = 256.coerceIn(android.media.audiofx.Visualizer.getCaptureSizeRange()[0], android.media.audiofx.Visualizer.getCaptureSizeRange()[1])
                    setDataCaptureListener(object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, d: ByteArray?, rate: Int) {}
                        override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, rate: Int) {
                            fft ?: return
                            val bins = fft.size / 2 - 1
                            var energy = 0f
                            for (i in 0 until count) {
                                // słupki rozłożone gęściej w basie (skala logarytmiczna), do ok. 2/3 pasma
                                val a = Math.pow(bins * 0.66, i / count.toDouble()).toInt().coerceIn(1, bins)
                                val b = Math.pow(bins * 0.66, (i + 1) / count.toDouble()).toInt().coerceIn(a, bins)
                                var m = 0f
                                for (k in a..b) m = maxOf(m, kotlin.math.hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()))
                                energy += m
                                val lv = (kotlin.math.ln(1f + m) / kotlin.math.ln(128f)).coerceIn(0.06f, 1f)
                                levels[i] = maxOf(lv, levels[i] * 0.82f)
                            }
                            silentSince = if (energy > 1f) 0L else if (silentSince == 0L) SystemClock.elapsedRealtime() else silentSince
                        }
                    }, minOf(android.media.audiofx.Visualizer.getMaxCaptureRate(), 12000), false, true)
                    enabled = true
                }
            }.getOrNull()
        }
        main.post(ticker)
        onDispose {
            ticking = false
            main.removeCallbacks(ticker)
            runCatching { vis?.enabled = false; vis?.release() }
        }
    }
    val top = Accent
    val violet = androidx.compose.ui.graphics.Color(0xFF8B5CF6)
    val hot = Warn
    androidx.compose.foundation.Canvas(modifier) {
        frame.intValue // przerysuj przy każdej nowej klatce
        val w = size.width
        val h = size.height
        fun rect(brush: androidx.compose.ui.graphics.Brush, x: Float, y: Float, bw: Float, bh: Float, r: Float = bw / 3) =
            drawRoundRect(brush, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(r))
        when (style) {
            // 2. lustro: bas na środku, słupki rosną w górę i w dół od osi
            2 -> {
                val n = count * 2
                val step = w / n
                val bw = step * 0.62f
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(violet.copy(alpha = 0.5f), top, violet.copy(alpha = 0.5f)))
                for (k in 0 until n) {
                    val lv = levels[if (k < count) count - 1 - k else k - count]
                    val bh = h * lv
                    rect(brush, k * step + (step - bw) / 2, (h - bh) / 2, bw, bh, bw / 2)
                }
            }
            // 3. segmenty jak we wskaźniku wysterowania: klocki, najwyższe w ostrzegawczym kolorze, osobny klocek szczytu
            3 -> {
                val cols = 12
                val rows = 10
                val step = w / cols
                val bw = step * 0.74f
                val rowH = h / rows
                val segH = rowH * 0.7f
                for (c in 0 until cols) {
                    val src = c * count / cols
                    val lit = (levels[src] * rows + 0.5f).toInt()
                    val peak = (peaks[src] * rows + 0.5f).toInt().coerceIn(1, rows)
                    for (r in 0 until rows) {
                        val color = if (r >= rows - 2) hot else if (r >= rows - 5) violet else top
                        val on = r < lit || r == peak - 1
                        rect(androidx.compose.ui.graphics.SolidColor(color.copy(alpha = if (on) 1f else 0.13f)),
                            c * step + (step - bw) / 2, h - (r + 1) * rowH + (rowH - segH) / 2, bw, segH, segH / 4)
                    }
                }
            }
            // 4. cienkie kreski z opadającymi znacznikami szczytów
            4 -> {
                val n = count * 2
                val step = w / n
                val bw = step * 0.34f
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(violet, top.copy(alpha = 0.5f)))
                for (k in 0 until n) {
                    // między dwoma pomiarami wartość pośrednia, żeby kresek było dwa razy więcej
                    val a = k / 2
                    val b = minOf(count - 1, a + k % 2)
                    val lv = (levels[a] + levels[b]) / 2
                    val pk = (peaks[a] + peaks[b]) / 2
                    val x = k * step + (step - bw) / 2
                    rect(brush, x, h - h * lv, bw, h * lv, bw / 2)
                    rect(androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f)),
                        x - bw * 0.4f, (h - h * pk - bw * 1.6f).coerceAtLeast(0f), bw * 1.8f, bw * 0.9f, bw / 2)
                }
            }
            // 5. koło: słupki rozchodzą się promieniście od pierścienia, lewa i prawa połowa symetrycznie
            5 -> {
                val n = count * 2
                val cx = w / 2
                val cy = h / 2
                val r0 = minOf(w, h) * 0.2f
                val rMax = minOf(w, h) * 0.5f
                val stroke = (2f * Math.PI.toFloat() * r0 / n) * 0.55f
                for (k in 0 until n) {
                    val lv = levels[if (k < count) k else n - 1 - k]
                    val ang = (-90f + 360f * (k + 0.5f) / n) * (Math.PI.toFloat() / 180f)
                    val r1 = r0 + (rMax - r0) * lv
                    val dx = kotlin.math.cos(ang)
                    val dy = kotlin.math.sin(ang)
                    drawLine(
                        androidx.compose.ui.graphics.lerp(top, violet, (if (k < count) k else n - 1 - k).toFloat() / count),
                        androidx.compose.ui.geometry.Offset(cx + dx * r0, cy + dy * r0), androidx.compose.ui.geometry.Offset(cx + dx * r1, cy + dy * r1),
                        strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                drawCircle(top.copy(alpha = 0.25f), r0 * 0.78f, androidx.compose.ui.geometry.Offset(cx, cy))
            }
            // 1. zwykłe słupki od dołu
            else -> {
                val gap = w / count * 0.32f
                val bw = w / count - gap
                val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, top.copy(alpha = 0.35f)))
                for (i in 0 until count) rect(brush, i * (bw + gap) + gap / 2, h - h * levels[i], bw, h * levels[i])
            }
        }
    }
}

/** Liczba rodzajów słupków w bloku Multimedia (widok 0 to okładka). */
private const val BAR_STYLES = 5
