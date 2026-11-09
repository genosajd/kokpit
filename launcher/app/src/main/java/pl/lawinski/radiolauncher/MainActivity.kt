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
    val blockWidgetId = mutableIntStateOf(-1)
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
    /** true: dodawany widżet trafi do klocka „Widżet”, false: do klocka kamery */
    private var pendingForBlock = false

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
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        location = LocationRepo(this)
        cameraWidgetId.intValue = Prefs.cameraWidgetId
        blockWidgetId.intValue = Prefs.blockWidgetId
        liveCamera.value = Prefs.liveCamera
        CarModel.installDefault(this)
        CanSource.start(this)
        carModelPath.value = Prefs.carModel
        layout.value = LayoutStore.decode(Prefs.layout)

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
        unregisterReceiver(packagesChanged)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePressed.intValue++
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
        pendingForBlock = false
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

    /** Widżet wybrany z listy Kokpitu do klocka „Widżet”. */
    fun addBlockWidget(provider: android.content.ComponentName) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingForBlock = true
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

    fun removeBlockWidget() {
        val id = Prefs.blockWidgetId
        if (id != -1) widgetHost.deleteAppWidgetId(id)
        Prefs.blockWidgetId = -1
        blockWidgetId.intValue = -1
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
        if (pendingForBlock) {
            val old = Prefs.blockWidgetId
            if (old != -1 && old != id) widgetHost.deleteAppWidgetId(old)
            Prefs.blockWidgetId = id
            blockWidgetId.intValue = id
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
        const val XIAOMI_CAM = "pl.lawinski.xiaomicam"
    }
}
