package pl.lawinski.radiolauncher

import android.Manifest
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    lateinit var widgetHost: AppWidgetHost
    lateinit var widgetManager: AppWidgetManager
    lateinit var location: LocationRepo

    /** Lista aplikacji; odświeżana po instalacji lub usunięciu aplikacji. */
    val apps = mutableStateOf<List<AppEntry>>(emptyList())
    val cameraWidgetId = mutableIntStateOf(-1)
    /** widżety klocków „Widżet”: numer klocka (Slot.n) → id widżetu */
    val blockWidgets = androidx.compose.runtime.mutableStateMapOf<Int, Int>()
    val liveCamera = mutableStateOf("")
    val carModelPath = mutableStateOf("")
    val layout = mutableStateOf(LayoutStore.DEFAULT)

    /** Zwiększany po naciśnięciu "home", żeby UI zamknęło otwarte okna. */
    val homePressed = mutableIntStateOf(0)

    /** Zwiększany przy każdym powrocie na ekran, żeby odświeżyć stan uprawnień i usług. */
    val resumeTick = mutableIntStateOf(0)

    /** Launcher na ekranie: tylko wtedy działa podgląd kamery na żywo i model 3D. */
    val visible = mutableStateOf(false)

    private var pendingWidgetId = -1
    /** numer klocka „Widżet”, do którego trafi dodawany widżet; -1 = klocek kamery */
    private var pendingBlock = -1

    private val bindLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) configureWidget(pendingWidgetId) else dropPending()
    }
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = r.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (r.resultCode == RESULT_OK && id != -1) {
            pendingWidgetId = id
            configureWidget(id)
        } else dropPending()
    }
    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) saveWidget(pendingWidgetId) else dropPending()
    }
    /** Mowa → tekst (Google) dla asystenta. */
    private var onSpeech: ((String) -> Unit)? = null
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) onSpeech?.invoke(text)
        onSpeech = null
    }

    /** Okienko rozpoznawania mowy; false, gdy radio go nie ma. */
    fun listen(onText: (String) -> Unit): Boolean {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Powiedz, o co chcesz zapytać")
        return try {
            onSpeech = onText
            speechLauncher.launch(i)
            true
        } catch (_: Exception) {
            onSpeech = null
            false
        }
    }

    /** Tekst → mowa: odpowiedzi asystenta czytane na głos (Speech.kt). */
    fun speak(text: String) = Speech.speak(this, text)

    fun stopSpeaking() = Speech.stop()

    private var onMic: ((Boolean) -> Unit)? = null
    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onMic?.invoke(ok)
        onMic = null
    }

    /** Zgoda na mikrofon (hasło „OK BMW”). */
    fun requestMic(then: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return then(true)
        onMic = then
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private var onPerms: ((Boolean) -> Unit)? = null
    private val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        onPerms?.invoke(r.values.all { it })
        onPerms = null
    }

    /** Zgoda pytana z wątku narzędzia asystenta: okienko Androida na ekranie, czeka na odpowiedź (maks. 60 s). */
    fun requestPermissionsBlocking(perms: Array<String>): Boolean {
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runOnUiThread {
            onPerms = { ok = it; latch.countDown() }
            runCatching { permsLauncher.launch(perms) }.onFailure { latch.countDown() }
        }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.start()
    }

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && CarModel.import(this, uri)) carModelPath.value = Prefs.carModel
    }

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Assistant.init(this)
        live = this
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        location = LocationRepo(this)
        cameraWidgetId.intValue = Prefs.cameraWidgetId
        liveCamera.value = Prefs.liveCamera
        CarModel.installDefault(this)
        CanSource.start(this)
        carModelPath.value = Prefs.carModel
        layout.value = LayoutStore.decode(Prefs.layout)
        layout.value.filter { it.id == BlockId.WIDGET }.forEach { blockWidgets[it.n] = Prefs.blockWidgetId(it.n) }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        reloadApps()
        ContextCompat.registerReceiver(this, packagesChanged, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent { LauncherScreen(this) }
        handleVoiceIntent(intent)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            widgetHost.startListening()
        } catch (_: Exception) {
        }
        location.start()
        visible.value = true
        Hotword.sync(this)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onStop() {
        super.onStop()
        visible.value = false
        try {
            widgetHost.stopListening()
        } catch (_: Exception) {
        }
        location.stop()
    }

    override fun onDestroy() {
        if (live === this) live = null
        unregisterReceiver(packagesChanged)
        Speech.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.intValue++
        handleVoiceIntent(intent)
    }

    /** Przycisk głosowy / ASSIST / VOICE_COMMAND: od razu słuchamy pytania do asystenta. */
    private fun handleVoiceIntent(intent: Intent?) {
        val a = intent?.action ?: return
        if (a == Intent.ACTION_VOICE_COMMAND || a == Intent.ACTION_ASSIST || a == "android.intent.action.VOICE_ASSIST") startVoiceAssistant()
    }

    /** Okienko asystenta (VoiceActivity) – słucha od razu. */
    fun startVoiceAssistant() {
        stopSpeaking()
        VoiceActivity.start(this)
    }

    /** Klawisze asystenta/wyszukiwania (np. z kierownicy), gdy Kokpit jest na ekranie. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val voiceKeys = setOf(android.view.KeyEvent.KEYCODE_VOICE_ASSIST, android.view.KeyEvent.KEYCODE_ASSIST, android.view.KeyEvent.KEYCODE_SEARCH)
        if (event.keyCode in voiceKeys) {
            if (event.action == android.view.KeyEvent.ACTION_UP) startVoiceAssistant()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Ekran główny nie ma dokąd wrócić")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        homePressed.intValue++
    }

    /** Ikony aplikacji dekodują się w tle, żeby start ekranu głównego nie czekał na nie. */
    fun reloadApps() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.Default) { Apps.load(this@MainActivity) }
            if (!Prefs.hasShortcuts && list.isNotEmpty()) Prefs.shortcuts = Apps.defaultShortcuts(list)
            apps.value = list
        }
    }

    fun pickCarModel() = modelPicker.launch(arrayOf("*/*"))

    fun restoreCarModel() {
        if (CarModel.installDefault(this, force = true)) carModelPath.value = Prefs.carModel
    }

    fun removeCarModel() {
        CarModel.remove(this)
        carModelPath.value = ""
    }

    fun saveLayout(slots: List<Slot>) {
        // ukryty klocek „Widżet” zwalnia swój widżet; ponowne dodanie zaczyna od wyboru
        val kept = slots.filter { it.id == BlockId.WIDGET }.map { it.n }.toSet()
        layout.value.filter { it.id == BlockId.WIDGET && it.n !in kept }.forEach { removeBlockWidget(it.n) }
        layout.value = slots
        Prefs.layout = LayoutStore.encode(slots)
    }

    fun setLiveCamera(name: String) {
        Prefs.liveCamera = name
        liveCamera.value = name
    }

    fun locationGranted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestLocation() =
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    // ---------- widżet kamery ----------

    /** Dodaje widżet: najpierw Xiaomi Cam, jeśli jest zainstalowana, inaczej systemowa lista widżetów. */
    fun addCameraWidget() {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingBlock = -1
        val provider = widgetManager.installedProviders.firstOrNull { it.provider.packageName == XIAOMI_CAM }?.provider
        if (provider == null) {
            pickLauncher.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            return
        }
        if (widgetManager.bindAppWidgetIdIfAllowed(id, provider)) {
            configureWidget(id)
        } else {
            bindLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            )
        }
    }

    /** Widżet wybrany z listy Kokpitu do klocka „Widżet” nr [n]. */
    fun addBlockWidget(n: Int, provider: android.content.ComponentName) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingBlock = n
        if (widgetManager.bindAppWidgetIdIfAllowed(id, provider)) {
            configureWidget(id)
        } else {
            bindLauncher.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            )
        }
    }

    /** Usuwa widżet klocka nr [n] (klocek ukryty w edycji układu). */
    fun removeBlockWidget(n: Int) {
        val id = Prefs.blockWidgetId(n)
        if (id != -1) widgetHost.deleteAppWidgetId(id)
        Prefs.setBlockWidgetId(n, -1)
        blockWidgets.remove(n)
    }

    fun removeCameraWidget() {
        val id = Prefs.cameraWidgetId
        if (id != -1) widgetHost.deleteAppWidgetId(id)
        Prefs.cameraWidgetId = -1
        cameraWidgetId.intValue = -1
    }

    private fun configureWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                configureLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                        .setComponent(info.configure)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                )
                return
            } catch (_: Exception) {
                // konfiguracja niedostępna dla obcej aplikacji: zapisujemy widżet bez niej
            }
        }
        saveWidget(id)
    }

    private fun saveWidget(id: Int) {
        if (pendingBlock != -1) {
            val n = pendingBlock
            val old = Prefs.blockWidgetId(n)
            if (old != -1 && old != id) widgetHost.deleteAppWidgetId(old)
            Prefs.setBlockWidgetId(n, id)
            blockWidgets[n] = id
            pendingWidgetId = -1
            return
        }
        val old = Prefs.cameraWidgetId
        if (old != -1 && old != id) widgetHost.deleteAppWidgetId(old)
        Prefs.cameraWidgetId = id
        cameraWidgetId.intValue = id
        pendingWidgetId = -1
    }

    private fun dropPending() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1
    }

    companion object {
        const val WIDGET_HOST_ID = 1027

        /** działający ekran Kokpitu – okienko asystenta (VoiceActivity) wykonuje przez niego narzędzia */
        @Volatile
        var live: MainActivity? = null
        const val XIAOMI_CAM = "pl.lawinski.xiaomicam"
    }
}
