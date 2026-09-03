package com.dazchi.livegpssdrsim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.dazchi.livegpssdrsim.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private enum class Mode { LIVE, MAP, SETTINGS }   // LIVE = merged LLH TX

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    // The HackRF connection + TX stream live in a foreground service so they
    // survive backgrounding / swipe-away.
    private var txService: TxService? = null
    private var connecting = false
    private val connected: Boolean get() = txService?.connected == true
    private val txRunning: Boolean get() = txService?.txRunning == true

    private val serviceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, b: android.os.IBinder?) {
            txService = (b as TxService.LocalBinder).service.also {
                it.onStateChanged = { runOnUiThread { onServiceState() } }
                it.onUnplugged = { runOnUiThread { appendStatus("HackRF unplugged — disconnected.") } }
            }
            onServiceState()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) { txService = null }
    }

    private var mode: Mode = Mode.MAP

    private val settingsPrefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private var ampEnabled: Boolean = false
    private var vgaGain: Int = 0

    // ---- Live-mode motion state (joystick) --------------------------------
    private var liveLat: Double = 0.0
    private var liveLon: Double = 0.0
    private var liveHgt: Double = 0.0
    private var velE: Double = 0.0
    private var velN: Double = 0.0

    private val motionHandler = Handler(Looper.getMainLooper())
    private var lastTickMs: Long = 0L
    private var motionRunning = false

    private val motionTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            var dt = (now - lastTickMs) / 1000.0
            lastTickMs = now
            if (dt <= 0.0) dt = TICK_S
            if (dt > 0.5) dt = 0.5
            stepMotion(dt)
            if (motionRunning) motionHandler.postDelayed(this, TICK_MS)
        }
    }

    // Feedback poller: reads back the position the generator is actually encoding
    // (NativeGen.currentPosition) and drives the orange map cursor from it, so the
    // dot reflects what's being transmitted rather than the last commanded push.
    private val feedbackHandler = Handler(Looper.getMainLooper())
    private val feedbackBuf = DoubleArray(3)
    private var feedbackRunning = false
    private val feedbackTick = object : Runnable {
        override fun run() {
            if (NativeGen.currentPosition(feedbackBuf) == 1)
                updateCursor(GeoPt(feedbackBuf[0], feedbackBuf[1], feedbackBuf[2]))
            if (feedbackRunning) feedbackHandler.postDelayed(this, TICK_MS)
        }
    }

    // ---- Map / route state ------------------------------------------------
    private val waypoints = mutableListOf<GeoPt>()
    private var routeGeo: List<GeoPt> = emptyList()   // road geometry (empty = straight-line)
    private val wpMarkers = mutableListOf<Marker>()
    // Just the numbered waypoint markers, indexed by waypoint index (wpMarkers
    // also holds the trkpt dots, so it isn't 1:1 with `waypoints`).
    private val waypointMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var cursorMarker: Marker? = null
    private var routeJob: Job? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var activeToast: android.widget.Toast? = null   // reused so clicks don't stack toasts
    // Orange "center on sim" FAB cycle: 0 = none, 1 = centered once, 2 = follow
    // (map keeps re-centering on the sim position as it moves).
    private var centerSimMode = 0
    private val logTimeFmt = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.US)
    private var myLocationEnabled = false
    private var selectedWaypoint = -1                  // index into waypoints, -1 = none

    private enum class RepeatMode { OFF, BOUNCE, LOOP }
    private var repeatMode = RepeatMode.OFF
    private var direction = 1                           // +1 forward, -1 bouncing back
    private var loopsDone = 0
    private var elevJob: Job? = null
    private var ghRetrySecs = 0
    private var syncingSpeed = false

    private var routePlaying = false
    private var routePaused = false
    // Route editing is locked only while a route playback is active (playing or
    // paused). A static TX — long-press "Start TX here", or after Stop clears a
    // playback — leaves the map editable even though the HackRF is transmitting.
    private val routeLocked: Boolean get() = routePlaying
    private var routeProgress = 0.0                    // metres travelled along the route
    private var routeSpeed = 0.0                        // current playback speed (m/s), ramped
    private var lastRouteTickMs: Long = 0L
    private var routeLoopRunning = false
    private var stopRemainingMs = 0.0                  // random-stop hold timer
    private var stopCheckpoints = listOf<Double>()     // waypoint distances along the route

    private val mapHandler = Handler(Looper.getMainLooper())
    private val routeTick = object : Runnable {
        override fun run() {
            stepRoute()
            if (routeLoopRunning) mapHandler.postDelayed(this, TICK_MS)
        }
    }
    private val routeFetchRunnable = Runnable { fetchRoute() }
    private val ghRetryRunnable = object : Runnable {
        override fun run() {
            if (ghRetrySecs <= 0) { scheduleRouteFetch(); return }
            setRouteStatus("GraphHopper rate limit — retrying in ${ghRetrySecs}s…")
            ghRetrySecs--
            mapHandler.postDelayed(this, 1000)
        }
    }

    // SAF pickers for GPX
    private val openGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadGpx(it) }
    }
    private val createGpx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let { exportGpx(it) } }

    // SAF picker for a user-supplied broadcast ephemeris (RINEX brdc) file.
    private val openBrdc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBrdc(it) }
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableAndCenterLocation()
        else appendStatus("location permission denied")
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is best-effort; the service runs regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // osmdroid must be configured before any MapView is inflated.
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // OSM's tile policy blocks the default "com.example.*" user agent, so use a
        // distinct, identifying one. Heavy use should point at a self-hosted or
        // licensed tile source. Cache to app storage (no storage permission needed).
        Configuration.getInstance().apply {
            userAgentValue = "LiveGpsSdrSim/1.0 (+https://github.com/osqzss/gps-sdr-sim)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            // Speed up tile loading. osmdroid defaults to ~2 concurrent network
            // fetches and a tiny in-RAM cache; a screen of tiles then trickles in.
            // Fetch many in parallel, keep more decoded tiles in memory, and hold
            // cached tiles for a month so revisits don't re-download.
            tileDownloadThreads = 8.toShort()
            tileFileSystemThreads = 8.toShort()
            tileDownloadMaxQueueSize = 64.toShort()
            tileFileSystemMaxQueueSize = 64.toShort()
            cacheMapTileCount = 24.toShort()
            cacheMapTileOvershoot = 12.toShort()
            expirationExtendedDuration = 30L * 24 * 60 * 60 * 1000 // 30 days
        }

        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val d = resources.displayMetrics.density
        val edge = (16 * d).toInt()
        val gap = (8 * d).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.contentScroll.updatePadding(
                bars.left + edge, bars.top + edge, bars.right + edge, bars.bottom + edge
            )
            binding.drawerInner.updatePadding(top = edge + bars.top, bottom = edge + bars.bottom)
            binding.btnMenu.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = bars.left + edge + gap   // nudged in from the edge
                topMargin = bars.top + gap
            }
            binding.fabColumn.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = bars.right + edge
                bottomMargin = bars.bottom + edge
            }
            binding.playColumn.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = bars.left + edge
                bottomMargin = bars.bottom + edge
            }
            insets
        }

        setupScrollableLog(binding.statusText)
        setupScrollableLog(binding.statusTextSettings)
        appendStatus(stringFromJNI())
        binding.txButton.setOnClickListener { onTxClicked() }

        // Ask for notification permission so the foreground-TX notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Bind the TX service (it's also started as a foreground service on connect).
        bindService(
            android.content.Intent(this, TxService::class.java),
            serviceConn, android.content.Context.BIND_AUTO_CREATE
        )

        setupModeSelector()
        setupDeviceControls()
        setupRfControls()
        setupLiveControls()
        setupMap()
        setupMapControls()
        setupSettingsControls()
        applyDrawerWidth()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDrawerWidth()   // full-width drawer in portrait, fixed in landscape
    }

    /**
     * Drawer fills the screen in portrait (MATCH_PARENT, which DrawerLayout leaves
     * a 64dp swipe-to-dismiss margin on); a fixed 320dp in landscape.
     */
    private fun applyDrawerWidth() {
        val portrait =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        binding.drawerScroll.updateLayoutParams {
            width = if (portrait) android.view.ViewGroup.LayoutParams.MATCH_PARENT
            else (320 * resources.displayMetrics.density).toInt()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        if (myLocationEnabled) myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        // Stop location updates while backgrounded to save battery.
        myLocationOverlay?.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMotionLoop()
        stopRouteLoop()
        // Unbind but DON'T stop TX — the started foreground service keeps
        // transmitting after the Activity is gone (stop it from the notification).
        txService?.onStateChanged = null
        runCatching { unbindService(serviceConn) }
        txService = null
    }

    // ======================================================================
    //  Tabs + shared RF controls
    // ======================================================================

    private fun setupModeSelector() {
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.modeToggle.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectMode(
                when (checkedId) {
                    R.id.mode_map      -> Mode.MAP
                    R.id.mode_settings -> Mode.SETTINGS
                    else               -> Mode.LIVE
                }
            )
        }
        selectMode(Mode.MAP)
    }

    private fun selectMode(newMode: Mode) {
        val prev = mode
        mode = newMode
        // Content: full-screen map for Map mode; the Raw/Live/Settings forms
        // (in the scroll) otherwise.
        binding.map.visibility           = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (mode == Mode.MAP) View.GONE else View.VISIBLE
        binding.panelLive.visibility     = if (mode == Mode.LIVE)     View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (mode == Mode.SETTINGS) View.VISIBLE else View.GONE
        // Drawer: map options only in Map mode. The LLH tab drives TX from its own
        // Start-TX / Set-position button, so the shared drawer Start-TX button is
        // never shown (Map uses Play/Pause/Stop; Settings has no TX action).
        binding.panelMap.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.fabColumn.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.playColumn.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.txButton.visibility = View.GONE
        // Mid-TX mode change: hand the shared LLH FIFO over to the new mode's
        // position source (joystick loop ↔ route/static) without touching TX.
        if (txRunning && prev != newMode) handleTxSourceSwitch(newMode)
        updateMapFabs()
        updateMapJoystick()
        updateStopTxButton()
    }

    /**
     * Swap which loop feeds the LLH FIFO when the user changes mode while
     * transmitting. Keyed off the loop currently running (not [prev]) so
     * round-trips through Settings don't disturb an ongoing playback.
     */
    private fun handleTxSourceSwitch(to: Mode) {
        when (to) {
            Mode.LIVE -> {
                // The LLH joystick takes over from the current sim position. The
                // motion loop may already be running (handed over from a map static
                // hold), so just seed the fields and enable the LLH controls.
                if (routePlaying) stopPlayback()            // end map playback (keeps TX)
                binding.liveLatInput.setText(fmt(liveLat, 6))
                binding.liveLonInput.setText(fmt(liveLon, 6))
                binding.liveHgtInput.setText(fmt(liveHgt, 1))
                binding.joystick.reset(); velE = 0.0; velN = 0.0
                setLiveControlsEnabled(true)
                if (!motionRunning) startMotionLoop()
                appendStatus("TX source → LLH (joystick).")
            }
            Mode.MAP -> {
                // Hand the FIFO to the map's static hold (driveable with the on-map
                // joystick). If a playback is already running (came in via Settings),
                // leave it as the source instead.
                if (!routePlaying) {
                    binding.mapJoystick.reset(); velE = 0.0; velN = 0.0
                    setLiveControlsEnabled(false)   // disable the LLH panel's controls
                    if (!motionRunning) startMotionLoop()   // keep driving via the map joystick
                    updateCursor(GeoPt(liveLat, liveLon, liveHgt))
                    setMapEditEnabled(true)
                    redrawMap()
                    updateMapButtons()
                    appendStatus("TX source → Map (joystick / static hold — press Play to run a route).")
                }
            }
            Mode.SETTINGS -> { /* neither source changes while adjusting settings */ }
        }
    }

    // ---- HackRF connect / disconnect --------------------------------------

    private fun setupDeviceControls() {
        binding.btnConnect.setOnClickListener {
            if (connected) onDisconnect() else onConnect()
        }
        binding.btnStopTx.setOnClickListener { onStopTxClicked() }
        updateConnUi()
    }

    /**
     * Drawer Stop-TX button, available in every mode. If a route playback is
     * running/paused it stops just the playback (leaving the HackRF transmitting a
     * static hold, per the Map stop behaviour); otherwise it stops transmitting
     * entirely — including a static TX started by long-pressing the map.
     */
    private fun onStopTxClicked() {
        if (routePlaying) stopPlayback() else stopEverything()
    }

    /** Enable Stop-TX whenever the HackRF is transmitting (playback or static). */
    private fun updateStopTxButton() {
        binding.btnStopTx.isEnabled = txRunning
    }

    /** Called when the bound service's connection/TX state changes. */
    private fun onServiceState() {
        if (!txRunning) {
            // TX ended (e.g. via the notification's Stop) while we thought it ran.
            stopMotionLoop(); stopRouteLoop()
            routePlaying = false; routePaused = false
            binding.txButton.text = "Start TX"
            setLiveControlsEnabled(false)
            setMapEditEnabled(true)
        }
        updateConnUi()
    }

    private fun onConnect() {
        if (connected || connecting) return
        connecting = true
        updateConnUi()
        appendStatus("connecting to HackRF over USB...")
        scope.launch {
            val result = runCatching {
                HackrfTxController(applicationContext).apply {
                    setAmp(ampEnabled)
                    setVgaGain(vgaGain)
                    openAsync()
                }
            }
            connecting = false
            result.fold(
                onSuccess = { ctrl ->
                    // Hand the open device to the foreground service, starting it.
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@MainActivity, android.content.Intent(this@MainActivity, TxService::class.java)
                    )
                    txService?.adopt(ctrl) ?: ctrl.stopTx()
                    appendStatus("HackRF connected.")
                },
                onFailure = { t -> appendStatus("connect failed: ${t.message}") }
            )
            updateConnUi()
        }
    }

    private fun onDisconnect() {
        if (txRunning) stopEverything()
        txService?.disconnect()
        updateConnUi()
        appendStatus("HackRF disconnected.")
    }

    private fun updateConnUi() {
        // Single toggle button: label + icon reflect the connection state.
        binding.btnConnect.isEnabled = !connecting
        binding.btnConnect.text = when {
            connecting -> "⏳ Connecting…"
            connected  -> "⛔ Disconnect"
            else       -> "🔌 Connect"
        }
        binding.connStatus.text = when {
            connecting            -> "Connecting…"
            connected && txRunning -> "Connected — transmitting"
            connected             -> "Connected"
            else                  -> "Disconnected"
        }
        // Start TX / Play need a connected device.
        binding.txButton.isEnabled = connected
        // LLH button doubles as Start-TX (idle) / Set-position override (running).
        binding.setPosButton.text = if (txRunning) "Set position (override)" else "Start TX"
        updateMapButtons()
        updateStopTxButton()
        updateEphemStatus()   // keep the brdc buttons locked while transmitting
    }

    private fun setupRfControls() {
        // Restore persisted TX settings.
        ampEnabled = settingsPrefs.getBoolean("amp", false)
        vgaGain = settingsPrefs.getInt("vga", 0)

        binding.timenowSwitch.isChecked = settingsPrefs.getBoolean("retime", true)
        binding.timenowSwitch.setOnCheckedChangeListener { _, checked ->
            settingsPrefs.edit().putBoolean("retime", checked).apply()
        }

        binding.ampSwitch.isChecked = ampEnabled
        binding.ampSwitch.setOnCheckedChangeListener { _, checked ->
            ampEnabled = checked
            settingsPrefs.edit().putBoolean("amp", checked).apply()
            txService?.setAmp(checked)
            appendStatus("amp: ${if (checked) "ON (+14 dB)" else "off"}")
        }

        binding.vgaSeek.progress = vgaGain
        binding.vgaLabel.text = "TX VGA Gain: $vgaGain dB"
        binding.vgaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                vgaGain = value
                binding.vgaLabel.text = "TX VGA Gain: $value dB"
                if (fromUser) txService?.setVga(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                settingsPrefs.edit().putInt("vga", vgaGain).apply()
                appendStatus("vga gain: $vgaGain dB")
            }
        })

        binding.ephemChooseButton.setOnClickListener {
            if (txRunning) return@setOnClickListener   // can't swap ephemeris mid-TX
            // Accept any file — brdc/RINEX files use many extensions (.22n, .rnx,
            // .yyn, …) and providers rarely report a useful MIME type.
            openBrdc.launch(arrayOf("*/*"))
        }
        binding.ephemResetButton.setOnClickListener { resetBrdc() }
        updateEphemStatus()
    }

    private fun setupLiveControls() {
        binding.speedLabel.text = "Move speed: ${binding.speedSeek.progress} km/h"
        binding.speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                binding.speedLabel.text = "Move speed: $value km/h"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.presetWalk.setOnClickListener { binding.speedSeek.progress = 3 }
        binding.presetRun.setOnClickListener  { binding.speedSeek.progress = 9 }
        binding.presetMoto.setOnClickListener { binding.speedSeek.progress = 40 }
        binding.presetCar.setOnClickListener  { binding.speedSeek.progress = 100 }
        // Doubles as the Start-TX button: starts live TX when idle, overrides the
        // position once transmitting. Its label follows the TX state (updateConnUi).
        binding.setPosButton.setOnClickListener {
            if (txRunning) onSetPosition() else startLiveTx()
        }
    }

    // ======================================================================
    //  Start / Stop TX
    // ======================================================================

    private fun onTxClicked() {
        if (txRunning) { stopEverything(); return }
        when (mode) {
            Mode.LIVE -> startLiveTx()
            Mode.MAP, Mode.SETTINGS -> {} // no Start-TX button in these modes
        }
    }

    /** Stop transmitting. The USB device stays connected (use Disconnect for that). */
    private fun stopEverything() {
        appendStatus("stopping TX...")
        routePlaying = false
        routePaused = false
        stopMotionLoop()
        stopRouteLoop()
        stopFeedbackLoop()
        txService?.stopTx()
        binding.txButton.text = "Start TX"
        setLiveControlsEnabled(false)
        // Reset route playback UI + cursor.
        routeProgress = 0.0
        direction = 1
        loopsDone = 0
        stopRemainingMs = 0.0
        routeSpeed = 0.0
        removeCursor()
        setMapEditEnabled(true)
        updateMapButtons()
        setTabsEnabled(true)
        updateConnUi()
        appendStatus("TX stopped.")
    }

    private fun startLiveTx() {
        val seed = readLiveSeedOrNull() ?: run {
            appendStatus("fix the location fields first"); return
        }
        beginLiveTx(seed.first, seed.second, seed.third) {
            setLiveControlsEnabled(true)
            startMotionLoop()
            appendStatus("TX running. Type an LLH + Set to override, or use the joystick.")
        }
    }

    /** Override the current position with the typed LLH (teleport-capable). */
    private fun onSetPosition() {
        val seed = readLiveSeedOrNull() ?: run {
            appendStatus("fix the location fields first"); return
        }
        liveLat = seed.first; liveLon = seed.second; liveHgt = seed.third
        velE = 0.0; velN = 0.0
        binding.joystick.reset()
        NativeGen.pushPositionForce(liveLat, liveLon, liveHgt)
        appendStatus("position set: ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m")
    }

    /**
     * Shared live/socket-mode TX launch used by both the Live tab and Map
     * playback. Seeds the generator at (lat,lon,hgt), starts streaming with -S,
     * and invokes [onRunning] on success.
     */
    private fun beginLiveTx(seedLat: Double, seedLon: Double, seedHgt: Double, onRunning: () -> Unit) {
        val svc = txService?.takeIf { it.connected } ?: run {
            appendStatus("connect the HackRF first (Controls → Connect)")
            showToast("Connect the HackRF first (Controls → Connect)")
            return
        }
        liveLat = seedLat; liveLon = seedLon; liveHgt = seedHgt
        velE = 0.0; velN = 0.0
        val retimeNow = binding.timenowSwitch.isChecked
        binding.txButton.isEnabled = false
        setTabsEnabled(false)
        appendStatus("starting generator + TX at $seedLat,$seedLon,$seedHgt...")
        scope.launch {
            val ephem = withContext(Dispatchers.IO) { resolveEphemerisFile() }
            appendStatus("ephemeris: ${ephem.name}")
            val dummyOut = File(filesDir, "unused.bin")
            val args = buildList {
                add("gps-sdr-sim")
                add("-e"); add(ephem.absolutePath)
                add("-b"); add("8")
                // IQ rate must match the HackRF TX rate — drive both from the one
                // constant so they can't drift apart.
                add("-s"); add(HackrfTxController.SAMPLE_RATE_HZ.toString())
                // NOTE: no fixed gain (-p). Verified on-device that -p 128 doesn't
                // clip (wrap ~0.00%) but does ~double the IQ amplitude (peak8 ~135
                // vs ~69), pushing the signal to full 8-bit scale. That extra ~6 dB
                // overloads a nearby receiver's front-end, so it drops lock after a
                // few seconds. Path-loss gain keeps a healthy ~half-scale level.
                if (retimeNow) { add("-T"); add("now") }
                add("-S"); add("-H")
                add("-o"); add(dummyOut.absolutePath)
            }.toTypedArray()
            svc.startTx(args, liveLat, liveLon, liveHgt).fold(
                onSuccess = {
                    binding.txButton.text = "Stop TX"
                    startFeedbackLoop()   // dot tracks the generator's actual output
                    updateConnUi()
                    onRunning()
                },
                onFailure = { t -> onTxStartFailed(t) }
            )
            binding.txButton.isEnabled = true
        }
    }

    private fun onTxStartFailed(t: Throwable) {
        Log.e("MainActivity", "TX startup failed", t)
        appendStatus("TX failed: ${t.message}")
        txService?.stopTx()       // stop the stream but stay connected
        stopMotionLoop()
        stopFeedbackLoop()
        setLiveControlsEnabled(false)
        stopRouteLoop()
        routePlaying = false; routePaused = false
        setMapEditEnabled(true)
        updateMapButtons()
        setTabsEnabled(true)
        updateConnUi()
    }

    // ======================================================================
    //  Live joystick motion loop
    // ======================================================================

    private fun startMotionLoop() {
        if (motionRunning) return
        motionRunning = true
        lastTickMs = SystemClock.elapsedRealtime()
        motionHandler.postDelayed(motionTick, TICK_MS)
    }

    private fun stopMotionLoop() {
        motionRunning = false
        motionHandler.removeCallbacks(motionTick)
        velE = 0.0; velN = 0.0
    }

    private fun startFeedbackLoop() {
        if (feedbackRunning) return
        feedbackRunning = true
        feedbackHandler.postDelayed(feedbackTick, TICK_MS)
    }

    private fun stopFeedbackLoop() {
        feedbackRunning = false
        feedbackHandler.removeCallbacks(feedbackTick)
    }

    private fun stepMotion(dt: Double) {
        // Drive from whichever joystick / speed belongs to the current mode: the
        // LLH panel's in LIVE mode, the on-map joystick's in Map mode. Both feed
        // the same velocity integrator and the shared LLH FIFO.
        val js = activeJoystick()
        val maxSpeed = activeMaxSpeedKmh().toDouble() / 3.6 // km/h -> m/s
        val jx = js.xNorm.toDouble()
        val jy = js.yNorm.toDouble()
        val targetVE = jx * maxSpeed
        val targetVN = jy * maxSpeed

        // Trapezoidal ramp: approach the target velocity at the configured
        // acceleration (used for both speeding up and slowing down).
        val maxDelta = activeAccel() * dt

        val dvE = targetVE - velE
        val dvN = targetVN - velN
        val dvMag = hypot(dvE, dvN)
        if (dvMag <= maxDelta || dvMag == 0.0) { velE = targetVE; velN = targetVN }
        else { velE += dvE / dvMag * maxDelta; velN += dvN / dvMag * maxDelta }

        val speed = hypot(velE, velN)
        if (speed > 1e-4) {
            liveLat = (liveLat + velN * dt / METERS_PER_DEG_LAT).coerceIn(-90.0, 90.0)
            val cosLat = max(cos(Math.toRadians(liveLat)), 1e-6)
            liveLon += velE * dt / (METERS_PER_DEG_LAT * cosLat)
            if (liveLon > 180.0) liveLon -= 360.0
            if (liveLon < -180.0) liveLon += 360.0
            // In LLH mode, track height from its field (unless being edited) and
            // reflect the moving position back into the lat/lon fields. In Map mode
            // those fields belong to the hidden LLH panel, so leave height alone and
            // hold the seeded/ground altitude instead.
            if (mode == Mode.LIVE) {
                if (!binding.liveHgtInput.hasFocus())
                    binding.liveHgtInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { liveHgt = it }
                if (!binding.liveLatInput.hasFocus()) binding.liveLatInput.setText(fmt(liveLat, 6))
                if (!binding.liveLonInput.hasFocus()) binding.liveLonInput.setText(fmt(liveLon, 6))
            }
            NativeGen.pushPosition(liveLat, liveLon, liveHgt)
        }
        binding.liveReadout.text =
            "TX: ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m\n" +
                "v = ${fmt(speed * 3.6, 1)} km/h"
        // The orange map cursor is driven by the feedback poller (the generator's
        // actual encoded position), not the commanded value pushed here.
    }

    /** The joystick that drives the sim position in the current mode. */
    private fun activeJoystick(): JoystickView =
        if (mode == Mode.MAP) binding.mapJoystick else binding.joystick

    /** The move-speed setting (km/h) for the current mode. */
    private fun activeMaxSpeedKmh(): Int =
        if (mode == Mode.MAP) binding.mapSpeedSeek.progress else binding.speedSeek.progress

    private fun liveAccel(): Double =
        (binding.liveAccelInput.text?.toString()?.trim()?.toDoubleOrNull() ?: DEFAULT_ACCEL)
            .coerceIn(0.1, 100.0)

    private fun mapAccel(): Double =
        (binding.mapAccelInput.text?.toString()?.trim()?.toDoubleOrNull() ?: DEFAULT_ACCEL)
            .coerceIn(0.1, 100.0)

    /** Acceleration (m/s²) driving the ramp in the current mode. */
    private fun activeAccel(): Double = if (mode == Mode.MAP) mapAccel() else liveAccel()

    /**
     * Show the on-map joystick whenever the map is transmitting, and let it drive
     * only while no route playback owns the FIFO. Driving reuses the shared motion
     * loop (stepMotion reads [activeJoystick]).
     */
    private fun updateMapJoystick() {
        // Always visible in Map mode (like the LLH joystick in its tab), but it can
        // only drive the sim position while transmitting a static hold: the route
        // loop owns the FIFO during playback, and there's nothing to drive while
        // idle. When it can't drive it renders dimmed and passes touches through to
        // the map (JoystickView.onTouchEvent returns false while disabled).
        binding.mapJoystick.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        val drive = mode == Mode.MAP && txRunning && !routePlaying
        binding.mapJoystick.isEnabled = drive
        if (!drive) binding.mapJoystick.reset()
        if (drive && !motionRunning) startMotionLoop()
    }

    private fun setLiveControlsEnabled(enabled: Boolean) {
        binding.joystick.isEnabled = enabled
        binding.speedSeek.isEnabled = enabled
        binding.presetWalk.isEnabled = enabled
        binding.presetRun.isEnabled = enabled
        binding.presetMoto.isEnabled = enabled
        binding.presetCar.isEnabled = enabled
        binding.liveAccelInput.isEnabled = enabled
        // setPosButton stays enabled throughout — it's the Start-TX button while
        // idle and the position-override button while transmitting. The motion loop
        // is started/stopped by the caller, not here (Map mode keeps it running for
        // the on-map joystick even though these LLH controls are disabled).
        if (!enabled) {
            binding.joystick.reset()
            binding.liveReadout.text = "idle"
        }
    }

    // ======================================================================
    //  Map: setup, waypoints, routing, GPX
    // ======================================================================

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupMap() {
        val map = binding.map
        // Standard OpenStreetMap (Mapnik) tiles from tile.openstreetmap.org. This
        // works keyless as long as we send a distinctive User-Agent (set in onCreate)
        // and cache tiles locally (osmdroid does, configured in onCreate) — as the
        // OSMF tile usage policy requires. Fine for light/personal use; an app with a
        // large user base should switch to its own or a commercial tile source.
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Mapnik tiles are served up to z19; allow a couple of over-zoom levels
        // (osmdroid upscales the z19 tiles) so waypoints can be placed precisely.
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 21.0
        // Center + zoom on the last "my location" fix (defaults to Tokyo @ z12).
        val mp = getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
        map.controller.setZoom(mp.getFloat("loc_zoom", 12f).toDouble())
        map.controller.setCenter(
            GeoPoint(
                mp.getFloat("loc_lat", 35.68f).toDouble(),
                mp.getFloat("loc_lon", 139.76f).toDouble()
            )
        )

        // Single tap: if it lands on an existing waypoint, select it (tap it
        // again to deselect); otherwise drop a new waypoint. Handled on the raw
        // tap-up (no ~300 ms double-tap-confirm wait) so it feels instant.
        val tapOverlay = object : Overlay() {
            override fun onSingleTapUp(e: android.view.MotionEvent, mapView: org.osmdroid.views.MapView): Boolean {
                if (routeLocked) return false   // route is locked while a playback runs
                val idx = nearestWaypointToPixel(e.x, e.y, mapView)
                if (idx >= 0) {
                    if (selectedWaypoint == idx) {
                        selectedWaypoint = -1; redrawMap(); updateMapFabs()
                    } else {
                        selectWaypoint(idx)
                    }
                } else {
                    val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                    addWaypoint(p.latitude, p.longitude, null)
                }
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent, mapView: org.osmdroid.views.MapView): Boolean {
                // Consume double-tap so it neither zooms nor disturbs waypoints
                // (a stray double-tap must never delete a waypoint). Pinch / the
                // +/- buttons remain for zooming.
                return true
            }
            override fun onLongPress(e: android.view.MotionEvent, mapView: org.osmdroid.views.MapView): Boolean {
                // Long-press anywhere opens the "Set sim position" dialog (like
                // the reference frontend's right-click). While transmitting, Send
                // teleports the simulated position; when idle, Send starts a
                // static TX seeded at that spot.
                val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                promptSetSimPosition(p.latitude, p.longitude)
                return true
            }
        }
        map.overlays.add(tapOverlay)

        // "My location" overlay; only starts receiving updates once the user taps
        // the button and grants permission. Use a centered blue dot so it sits
        // exactly on the fix (the default person icon is offset).
        val dot = makeDot(LOCATION_BLUE)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
            it.setPersonIcon(dot)
            it.setDirectionIcon(dot)
            it.setPersonAnchor(0.5f, 0.5f)
            it.setDirectionAnchor(0.5f, 0.5f)
            map.overlays.add(it)
        }

        // In follow mode, swallow single-finger drags so the user can't pan the
        // map away from the sim dot; multi-touch (pinch-zoom) and taps pass through.
        map.setOnTouchListener { _, ev ->
            centerSimMode == 2 &&
                ev.pointerCount == 1 &&
                ev.actionMasked == android.view.MotionEvent.ACTION_MOVE
        }

        redrawMap()
        updateWpCount()
        updateMapButtons()
    }

    /**
     * Confirmation dialog for placing the simulated position (long-press on the
     * map). Shows the tapped coordinates and asynchronously fetches the SRTM
     * ground elevation there. While transmitting, Send teleports the sim position;
     * when idle, Send starts a static TX seeded at that point. Cancel does nothing.
     */
    private fun promptSetSimPosition(lat: Double, lon: Double) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val body = android.widget.TextView(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            textSize = 16f
            text = "${fmt(lat, 6)}, ${fmt(lon, 6)}\nElevation: …"
        }
        var targetHgt = liveHgt
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (txRunning) "Set sim position" else "Start TX here")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                if (txRunning) {
                    liveLat = lat; liveLon = lon; liveHgt = targetHgt
                    velE = 0.0; velN = 0.0
                    NativeGen.pushPositionForce(liveLat, liveLon, liveHgt)
                    updateCursor(GeoPt(liveLat, liveLon, liveHgt))
                    appendStatus("sim position set: ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m")
                } else {
                    // Idle: Send starts a static TX seeded at this point. The map
                    // stays editable — this is a static hold, not a locked playback.
                    beginLiveTx(lat, lon, targetHgt) {
                        setMapEditEnabled(true)
                        updateCursor(GeoPt(liveLat, liveLon, liveHgt))
                        updateMapButtons()
                        updateMapFabs()
                        appendStatus("TX started at ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m")
                    }
                }
            }
            .show()

        scope.launch {
            val g = ElevationClient.elevationAt(lat, lon)
            if (!dialog.isShowing) return@launch
            if (g != null) {
                targetHgt = g
                body.text = "${fmt(lat, 6)}, ${fmt(lon, 6)}\nElevation: ${fmt(g, 1)} m"
            } else {
                body.text = "${fmt(lat, 6)}, ${fmt(lon, 6)}\nElevation: ${fmt(liveHgt, 1)} m (current)"
            }
        }
    }

    private fun onMyLocationClicked() {
        if (hasLocationPermission()) enableAndCenterLocation()
        else locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enableAndCenterLocation() {
        val ov = myLocationOverlay ?: return
        ov.enableMyLocation()
        myLocationEnabled = true
        val now = ov.myLocation
        if (now != null) {
            binding.map.controller.animateTo(now)
            binding.map.controller.setZoom(17.0)
            saveDefaultLocation(now.latitude, now.longitude)
        } else {
            appendStatus("acquiring GPS fix for current location…")
            // runOnFirstFix fires on a background thread.
            ov.runOnFirstFix {
                runOnUiThread {
                    val p = ov.myLocation ?: return@runOnUiThread
                    binding.map.controller.animateTo(p)
                    binding.map.controller.setZoom(17.0)
                    saveDefaultLocation(p.latitude, p.longitude)
                }
            }
        }
    }

    /** Remember the phone's location + zoom (from the my-location FAB) as the default. */
    private fun saveDefaultLocation(lat: Double, lon: Double) {
        getSharedPreferences("map_prefs", Context.MODE_PRIVATE).edit()
            .putFloat("loc_lat", lat.toFloat())
            .putFloat("loc_lon", lon.toFloat())
            .putFloat("loc_zoom", binding.map.zoomLevelDouble.toFloat())
            .apply()
    }

    /** GraphHopper key lives in the Settings tab; only valid keys are saved. */
    private fun setupSettingsControls() {
        val prefs = getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
        binding.ghKeyInput.setText(prefs.getString("gh_key", ""))
        binding.ghTestButton.setOnClickListener { onTestGhKey() }
    }

    private fun onTestGhKey() {
        val key = binding.ghKeyInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) { binding.ghKeyStatus.text = "Enter a key first"; return }
        binding.ghTestButton.isEnabled = false
        binding.ghKeyStatus.text = "Testing…"
        scope.launch {
            val result = runCatching {
                // A trivial two-point route: succeeds only with a working key.
                GraphHopperRouter.route(
                    listOf(GeoPt(35.680, 139.760), GeoPt(35.690, 139.770)), "car", key
                )
            }
            binding.ghTestButton.isEnabled = true
            result.fold(
                onSuccess = {
                    getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
                        .edit().putString("gh_key", key).apply()
                    binding.ghKeyStatus.text = "Key valid — saved ✓"
                    scheduleRouteFetch()   // re-route now that a key is available
                },
                onFailure = { e -> binding.ghKeyStatus.text = "Invalid: ${e.message}" }
            )
        }
    }

    private fun setupMapControls() {
        // Restore persisted map toggles (on by default), then wire listeners.
        val prefs = getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
        binding.roadSwitch.isChecked = prefs.getBoolean("road", true)
        binding.autoAltSwitch.isChecked = prefs.getBoolean("auto_alt", true)
        binding.profileGroup.visibility = if (binding.roadSwitch.isChecked) View.VISIBLE else View.GONE

        binding.roadSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("road", checked).apply()
            binding.profileGroup.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) routeGeo = emptyList()
            scheduleRouteFetch()
            redrawMap()
        }
        binding.profileGroup.setOnCheckedChangeListener { _, _ -> scheduleRouteFetch() }

        binding.btnWpUndo.setOnClickListener {
            if (routeLocked || waypoints.isEmpty()) return@setOnClickListener
            waypoints.removeAt(waypoints.size - 1)
            selectedWaypoint = -1
            updateWpCount(); redrawMap(); scheduleRouteFetch(); updateMapFabs()
        }
        binding.btnWpClear.setOnClickListener { clearWaypoints() }
        binding.btnWpAdd.setOnClickListener { onManualAddWaypoint() }

        binding.autoAltSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_alt", checked).apply()
            scheduleRouteFetch()
        }
        binding.showTrkptSwitch.setOnCheckedChangeListener { _, _ -> redrawMap() }
        binding.stopTrkptSwitch.setOnCheckedChangeListener { _, _ ->
            // Recompute the stop set live so toggling this takes effect mid-flight
            // (the numeric dynamics fields are already read fresh every tick).
            if (routePlaying) stopCheckpoints = computeCheckpoints(activePts())
        }

        // Stop probability is a 0–1 chance: flag out-of-range input as you type,
        // then snap it back into range when the field loses focus.
        binding.dynStopprobInput.doAfterTextChanged { e ->
            val s = e?.toString()?.trim().orEmpty()
            val v = s.toDoubleOrNull()
            binding.dynStopprobLayout.error =
                if (s.isNotEmpty() && (v == null || v < 0.0 || v > 1.0)) "Enter 0–1" else null
        }
        binding.dynStopprobInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val v = binding.dynStopprobInput.text?.toString()?.trim()?.toDoubleOrNull()
            val clamped = (v ?: 0.0).coerceIn(0.0, 1.0)
            if (v == null || clamped != v) {
                binding.dynStopprobInput.setText(if (clamped == clamped.toLong().toDouble())
                    clamped.toLong().toString() else clamped.toString())
            }
            binding.dynStopprobLayout.error = null
        }
        binding.mapAltInput.doAfterTextChanged { updateWpList(); updateRouteInfo() }
        binding.repeatGroup.setOnCheckedChangeListener { _, id ->
            repeatMode = when (id) {
                R.id.repeat_bounce -> RepeatMode.BOUNCE
                R.id.repeat_loop   -> RepeatMode.LOOP
                else               -> RepeatMode.OFF
            }
            binding.loopCountRow.visibility = if (repeatMode == RepeatMode.OFF) View.GONE else View.VISIBLE
            direction = 1
            scheduleRouteFetch()   // re-route so the road loop's closing leg is added/removed
            redrawMap()            // straight-line loop shows the closing leg immediately
            updateRouteInfo()
        }

        binding.mapSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!syncingSpeed) {
                    syncingSpeed = true
                    binding.mapSpeedInput.setText(value.toString())
                    syncingSpeed = false
                }
                updateRouteInfo()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.mapSpeedInput.doAfterTextChanged {
            if (syncingSpeed) return@doAfterTextChanged
            val v = it?.toString()?.trim()?.toDoubleOrNull() ?: return@doAfterTextChanged
            syncingSpeed = true
            binding.mapSpeedSeek.progress = v.toInt().coerceIn(0, binding.mapSpeedSeek.max)
            syncingSpeed = false
            updateRouteInfo()
        }
        binding.mapPresetWalk.setOnClickListener { binding.mapSpeedSeek.progress = 3 }
        binding.mapPresetRun.setOnClickListener  { binding.mapSpeedSeek.progress = 9 }
        binding.mapPresetMoto.setOnClickListener { binding.mapSpeedSeek.progress = 40 }
        binding.mapPresetCar.setOnClickListener  { binding.mapSpeedSeek.progress = 100 }

        binding.btnMyLocation.setOnClickListener { onMyLocationClicked() }
        binding.btnCenterSim.setOnClickListener { onCenterSimClicked() }
        binding.btnDeleteWp.setOnClickListener { deleteSelectedWaypoint() }
        binding.btnDeleteAll.setOnClickListener { clearWaypoints() }

        binding.btnPlay.setOnClickListener { onPlayClicked() }
        binding.btnPause.setOnClickListener { onPauseClicked() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnPlayPause.setOnClickListener {
            if (routePlaying && !routePaused) onPauseClicked() else onPlayClicked()
        }
        binding.btnStopFab.setOnClickListener { stopPlayback() }

        binding.btnGpxLoad.setOnClickListener {
            if (routeLocked) return@setOnClickListener
            openGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "*/*"))
        }
        binding.btnGpxExport.setOnClickListener {
            if (activePts().size < 2) { setRouteStatus("Add at least two waypoints to export"); return@setOnClickListener }
            createGpx.launch("route.gpx")
        }
    }

    private fun addWaypoint(lat: Double, lon: Double, alt: Double?) {
        waypoints.add(GeoPt(lat, lon, alt))
        selectedWaypoint = -1            // tapping empty map clears any selection
        updateWpCount(); redrawMap(); scheduleRouteFetch()
        updateMapButtons(); updateMapFabs()
    }

    /** Index of the waypoint whose marker is nearest the pixel, or -1 if none close. */
    private fun nearestWaypointToPixel(px: Float, py: Float, mapView: org.osmdroid.views.MapView): Int {
        val proj = mapView.projection
        val threshold = 48f * resources.displayMetrics.density
        val pt = android.graphics.Point()
        var best = -1; var bestD = Float.MAX_VALUE
        waypoints.forEachIndexed { i, w ->
            proj.toPixels(GeoPoint(w.lat, w.lon), pt)
            val d = hypot((pt.x - px).toDouble(), (pt.y - py).toDouble()).toFloat()
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD <= threshold) best else -1
    }

    private fun selectWaypoint(index: Int) {
        if (routeLocked) return         // route is locked while a playback runs
        selectedWaypoint = index
        redrawMap()
        updateMapFabs()
        setRouteStatus("Waypoint ${index + 1} selected — tap the trash button to delete")
    }

    private fun deleteSelectedWaypoint() {
        val i = selectedWaypoint
        if (routeLocked || i !in waypoints.indices) return
        waypoints.removeAt(i)
        selectedWaypoint = -1
        updateWpCount(); redrawMap(); scheduleRouteFetch()
        updateMapButtons(); updateMapFabs()
        setRouteStatus(
            if (waypoints.size >= 2) "Straight-line · ${fmtDist(RouteGeometry.totalDistance(activePts()))}"
            else "Tap the map to add waypoints"
        )
    }

    private fun clearWaypoints() {
        if (routeLocked) return
        // Cancel any pending/in-flight route fetch so a late result can't repaint
        // a route after the waypoints are gone.
        mapHandler.removeCallbacks(routeFetchRunnable)
        mapHandler.removeCallbacks(ghRetryRunnable)
        ghRetrySecs = 0
        routeJob?.cancel()
        waypoints.clear(); routeGeo = emptyList(); selectedWaypoint = -1
        updateWpCount(); redrawMap()
        setRouteStatus("Tap the map to add waypoints")
        updateMapButtons(); updateMapFabs()
    }

    private fun updateMapFabs() {
        val editable = mode == Mode.MAP && !routeLocked
        binding.btnDeleteWp.visibility =
            if (editable && selectedWaypoint in waypoints.indices) View.VISIBLE else View.GONE
        binding.btnDeleteAll.visibility =
            if (editable && waypoints.isNotEmpty()) View.VISIBLE else View.GONE
        updateCenterSimFab()
    }

    private fun updateWpCount() {
        binding.wpCount.text = "${waypoints.size} waypoint${if (waypoints.size == 1) "" else "s"}"
        updateWpList()
        updateRouteInfo()
    }

    private fun updateWpList() {
        val fallback = mapAlt()
        binding.wpList.text = waypoints.mapIndexed { i, w ->
            // Show the waypoint's own altitude, or the map Altitude fallback that
            // playback uses when a point has none.
            val h = (w.alt ?: fallback).toInt()
            "${i + 1}: ${fmt(w.lat, 5)}, ${fmt(w.lon, 5)}  ${h} m"
        }.joinToString("\n")
    }

    private fun onManualAddWaypoint() {
        if (routeLocked) return
        val lat = binding.wpLatInput.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = binding.wpLngInput.text?.toString()?.trim()?.toDoubleOrNull()
        if (lat == null || abs(lat) > 90.0 || lon == null || abs(lon) > 180.0) {
            setRouteStatus("Enter a valid lat / lon to add"); return
        }
        addWaypoint(lat, lon, null)
        binding.wpLatInput.setText(""); binding.wpLngInput.setText("")
        binding.map.controller.animateTo(GeoPoint(lat, lon))
    }

    /**
     * Geometry used for display, distance and playback interpolation. In Loop
     * mode the route closes back to waypoint 1: for a road route the closing leg
     * is routed by GraphHopper (fetchRoute appends the first waypoint), so
     * routeGeo already closes; for a straight-line route we append it here.
     */
    private fun activePts(): List<GeoPt> {
        val road = routeGeo.size >= 2
        val base = if (road) routeGeo else waypoints.toList()
        return if (repeatMode == RepeatMode.LOOP && !road && base.size >= 2) base + base.first() else base
    }

    private fun mapAlt(): Double =
        binding.mapAltInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 10.0

    private fun setRouteStatus(text: String) { binding.routeStatus.text = text }

    /** The structured Route Info panel (distance / ETA / trkpt / live progress). */
    private fun updateRouteInfo() {
        val pts = activePts()
        val total = RouteGeometry.totalDistance(pts)
        val speedKmh = binding.mapSpeedSeek.progress.coerceAtLeast(1)
        val etaMin = if (total > 0) (total / 1000.0) / speedKmh * 60.0 else 0.0
        val sb = StringBuilder()
        sb.append("Distance   ${fmtDist(total)}\n")
        sb.append("Est. time  ${fmtDuration(etaMin)}\n")
        sb.append("Trkpt      ${pts.size}")
        if (txRunning) {
            val pct = if (total > 0) (routeProgress / total * 100).toInt() else 0
            sb.append("\nProgress   $pct%")
            if (repeatMode != RepeatMode.OFF) sb.append("\nLap        $loopsDone${loopLimitLabel()}")
            // Currently transmitted position (fed to gps-sdr-sim this tick).
            sb.append("\nSim LLH    ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m")
        }
        binding.routeInfo.text = sb.toString()
    }

    private fun fmtDuration(minutes: Double): String {
        if (minutes <= 0) return "—"
        val total = minutes.toInt()
        return if (total < 60) "$total min" else "${total / 60}h ${total % 60}m"
    }

    private fun loopLimit(): Int =
        binding.loopCountInput.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    private fun loopLimitLabel(): String {
        val n = loopLimit()
        return if (n > 0) " / $n" else ""
    }

    private fun redrawMap() {
        val map = binding.map
        wpMarkers.forEach { map.overlays.remove(it) }
        wpMarkers.clear()
        waypointMarkers.clear()
        routeLine?.let { map.overlays.remove(it) }
        routeLine = null

        val pts = activePts()
        if (pts.size >= 2) {
            val line = Polyline(map).apply {
                setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.strokeWidth = 8f
                infoWindow = null   // no gray info bubble when the line is tapped
            }
            routeLine = line
            map.overlays.add(line)
        }
        // Track points: small dots at each route vertex (sampled to stay light).
        if (binding.showTrkptSwitch.isChecked && pts.size >= 2) {
            val trkDot = BitmapDrawable(resources, makeSmallDot(0xFF7E57C2.toInt()))
            val stepT = max(1, pts.size / MAX_TRKPT_MARKERS)
            var i = 0
            while (i < pts.size) {
                val tp = pts[i]
                val m = Marker(map).apply {
                    position = GeoPoint(tp.lat, tp.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = trkDot
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> false }
                }
                wpMarkers.add(m)
                map.overlays.add(m)
                i += stepT
            }
        }
        // Waypoint markers on top of the line. Tap to select (for deletion);
        // drag to move (when not transmitting). The selected marker is tinted.
        waypoints.forEachIndexed { i, w ->
            val m = Marker(map).apply {
                position = GeoPoint(w.lat, w.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = makeNumberedMarker(i + 1, i == selectedWaypoint)
                title = "Waypoint ${i + 1}"
                infoWindow = null   // no gray info bubble on tap
                isDraggable = !routeLocked
                // Let the tap fall through to the tap overlay, which does the
                // hit-test (select this waypoint) vs. add-new decision in one place.
                setOnMarkerClickListener { _, _ -> false }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(mk: Marker) {
                        if (routeLocked || i !in waypoints.indices) return
                        // Mark the waypoint selected (turns it red) as the drag
                        // begins. Recolour just the affected markers rather than a
                        // full redraw, which would destroy the marker mid-gesture.
                        val prev = selectedWaypoint
                        if (prev != i && prev in waypointMarkers.indices) {
                            waypointMarkers[prev].icon = makeNumberedMarker(prev + 1, false)
                        }
                        selectedWaypoint = i
                        mk.icon = makeNumberedMarker(i + 1, true)
                        updateMapFabs()
                        map.invalidate()
                    }
                    override fun onMarkerDrag(mk: Marker) {}
                    override fun onMarkerDragEnd(mk: Marker) {
                        if (routeLocked || i !in waypoints.indices) return
                        waypoints[i] = GeoPt(mk.position.latitude, mk.position.longitude, null)
                        updateWpCount(); redrawMap(); scheduleRouteFetch(); updateRouteInfo()
                    }
                })
            }
            wpMarkers.add(m)
            waypointMarkers.add(m)
            map.overlays.add(m)
        }
        // Re-adding the line/markers above pushes them to the top of the draw
        // order, so lift the simulated-position cursor back to the very top to
        // keep it from being buried under waypoints or the route line.
        cursorMarker?.let { map.overlays.remove(it); map.overlays.add(it) }
        map.invalidate()
        updateMapFabs()   // keep the delete/clear FABs in sync with the waypoints

        // Refresh the straight-line status when not showing a road route.
        if (routeGeo.size < 2 && waypoints.size >= 2) {
            setRouteStatus("Straight-line · ${fmtDist(RouteGeometry.totalDistance(activePts()))}")
        }
    }

    /** A filled dot with a white ring — symmetric, so it anchors dead-center. */
    private fun makeDot(color: Int): Bitmap {
        val d = resources.displayMetrics.density
        val size = (22 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; this.color = Color.WHITE
        })
        canvas.drawCircle(c, c, c - 3f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; this.color = color
        })
        return bmp
    }

    /** A tiny solid dot for track-point markers. */
    private fun makeSmallDot(color: Int): Bitmap {
        val d = resources.displayMetrics.density
        val size = (7 * d).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; this.color = color
        })
        return bmp
    }

    /** A round numbered marker — green normally, red when selected. */
    private fun makeNumberedMarker(number: Int, selected: Boolean): BitmapDrawable {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        val r = c - 2f * d

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (selected) 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt()
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * d
            color = Color.WHITE
        }
        canvas.drawCircle(c, c, r, fill)
        canvas.drawCircle(c, c, r, border)

        val label = number.toString()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * when {
                label.length >= 3 -> 0.34f
                label.length == 2 -> 0.44f
                else              -> 0.54f
            }
        }
        val baseline = c - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, c, baseline, text)
        return BitmapDrawable(resources, bmp)
    }

    private fun scheduleRouteFetch() {
        mapHandler.removeCallbacks(routeFetchRunnable)
        mapHandler.removeCallbacks(ghRetryRunnable)   // a fresh request cancels any retry countdown
        mapHandler.postDelayed(routeFetchRunnable, ROUTE_DEBOUNCE_MS)
    }

    private fun fetchRoute() {
        routeJob?.cancel()
        if (!binding.roadSwitch.isChecked || waypoints.size < 2) {
            routeGeo = emptyList()
            redrawMap()
            maybeEnrichElevation()   // straight-line: fill waypoint altitudes
            return
        }
        val key = binding.ghKeyInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) { setRouteStatus("Set your GraphHopper API key in Settings for road routing"); return }
        val profile = when (binding.profileGroup.checkedRadioButtonId) {
            R.id.profile_bike -> "bike"
            R.id.profile_car  -> "car"
            else              -> "foot"
        }
        // In loop mode, append the first waypoint so GraphHopper routes the
        // closing leg (last → first) along roads.
        val loop = repeatMode == RepeatMode.LOOP && waypoints.size >= 2
        val wps = if (loop) waypoints.toList() + waypoints.first() else waypoints.toList()
        // Snapshot the waypoints this request was built from; if they change or get
        // cleared while the network call is in flight, the result is stale and must
        // be discarded (otherwise a deleted route can reappear on the map).
        val requested = waypoints.toList()
        setRouteStatus("Calculating route…")
        routeJob = scope.launch {
            try {
                val geo = GraphHopperRouter.route(wps, profile, key)
                if (waypoints != requested) return@launch   // waypoints changed — stale result
                routeGeo = geo
                redrawMap()
                updateRouteInfo()
                setRouteStatus("Road route ($profile) · ${fmtDist(RouteGeometry.totalDistance(activePts()))}")
                maybeEnrichElevation()
            } catch (e: Exception) {
                if (waypoints != requested) return@launch   // stale (cleared/changed) — ignore
                routeGeo = emptyList()
                redrawMap()
                val msg = e.message?.lowercase().orEmpty()
                if ("limit" in msg || "rate" in msg || "quota" in msg || "429" in msg) {
                    // GraphHopper free-tier throttle: count down and auto-retry.
                    ghRetrySecs = 15
                    mapHandler.removeCallbacks(ghRetryRunnable)
                    mapHandler.post(ghRetryRunnable)
                } else {
                    setRouteStatus("Routing failed: ${e.message} — using straight line")
                }
            }
        }
    }

    /**
     * When "Auto altitude" is on, get SRTM heights for the *waypoints only* and
     * interpolate them onto the route's trackpoints (rather than querying SRTM
     * for every trackpoint). For a straight-line route the waypoints are the
     * route, so they just carry their own measured heights.
     */
    private fun maybeEnrichElevation() {
        if (!binding.autoAltSwitch.isChecked) return
        val road = routeGeo.size >= 2
        val wpsSnapshot = waypoints.toList()
        if (wpsSnapshot.size < 2) return
        val capturedRoute = routeGeo
        val loop = road && repeatMode == RepeatMode.LOOP
        elevJob?.cancel()
        elevJob = scope.launch {
            try {
                // One SRTM request, sampled at the waypoints only.
                val wpsAlt = ElevationClient.enrich(wpsSnapshot)

                // Store the measured heights back on the waypoints, but ONLY if
                // they're unchanged since the snapshot (so a waypoint added or
                // moved during the fetch isn't clobbered by this stale result).
                val wpsUnchanged = waypoints.size == wpsSnapshot.size &&
                    waypoints.indices.none {
                        waypoints[it].lat != wpsSnapshot[it].lat ||
                            waypoints[it].lon != wpsSnapshot[it].lon
                    }
                if (wpsUnchanged) {
                    for (i in wpsAlt.indices) waypoints[i] = wpsAlt[i]
                }

                if (road) {
                    if (routeGeo !== capturedRoute) return@launch   // route changed; stale
                    // Loop mode closes back to the first waypoint, so add it as a
                    // trailing control for the closing leg's interpolation.
                    val controls = if (loop) wpsAlt + wpsAlt.first() else wpsAlt
                    routeGeo = RouteGeometry.interpolateAltitudes(capturedRoute, controls)
                } else if (!wpsUnchanged) {
                    return@launch   // straight-line: nothing valid to apply
                }

                redrawMap(); updateWpList(); updateMapFabs()
                val alts = (if (road) routeGeo else waypoints).mapNotNull { it.alt }
                val range = if (alts.isNotEmpty()) " · ${alts.min().toInt()}–${alts.max().toInt()} m" else ""
                setRouteStatus("Route · ${fmtDist(RouteGeometry.totalDistance(activePts()))}$range")
            } catch (e: Exception) {
                setRouteStatus("Elevation fetch failed: ${e.message}")
            }
        }
    }

    // ======================================================================
    //  Map: route playback (feeds the same pushPosition path as the joystick)
    // ======================================================================

    private fun onPlayClicked() {
        val pts = activePts()
        if (pts.isEmpty()) { setRouteStatus("Add at least one waypoint"); return }

        when {
            !txRunning -> {
                // Idle: start the HackRF TX and route playback together.
                val seed = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
                direction = 1; loopsDone = 0; stopRemainingMs = 0.0; routeSpeed = 0.0
                stopCheckpoints = computeCheckpoints(pts)
                beginLiveTx(seed.lat, seed.lon, seed.alt ?: mapAlt()) {
                    setMapEditEnabled(false)
                    routePlaying = true; routePaused = false
                    redrawMap()         // rebuild markers as non-draggable now locked
                    startRouteLoop()
                    updateMapButtons()
                    updateRouteInfo()
                    appendStatus("Route playback started (${repeatMode.name.lowercase()}).")
                }
            }
            !routePlaying -> {
                // Already transmitting (static): begin route playback on the live
                // TX without restarting the generator; teleport to the route start.
                val seed = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
                direction = 1; loopsDone = 0; stopRemainingMs = 0.0; routeSpeed = 0.0
                stopCheckpoints = computeCheckpoints(pts)
                liveLat = seed.lat; liveLon = seed.lon; liveHgt = seed.alt ?: mapAlt()
                velE = 0.0; velN = 0.0
                stopMotionLoop()        // route loop takes over the FIFO from the joystick
                NativeGen.pushPositionForce(liveLat, liveLon, liveHgt)
                setMapEditEnabled(false)
                routePlaying = true; routePaused = false
                redrawMap()             // rebuild markers as non-draggable now locked
                startRouteLoop()
                updateMapButtons()
                updateRouteInfo()
                appendStatus("Route playback started (${repeatMode.name.lowercase()}).")
            }
            else -> {
                // Resume from pause.
                routePaused = false; routePlaying = true
                if (!routeLoopRunning) startRouteLoop()
                updateMapButtons()
            }
        }
    }

    private fun onPauseClicked() {
        if (!routePlaying) return
        routePaused = true
        setRouteStatus("Paused · ${routeProgressLabel()}")
        updateMapButtons()
        updateRouteInfo()
    }

    /**
     * Stop route playback but keep the HackRF transmitting the current position
     * (now a static hold). Clears the playback state and re-enables route editing;
     * use the drawer's Stop TX / Disconnect to end transmission entirely.
     */
    private fun stopPlayback() {
        if (!routePlaying) return
        stopRouteLoop()
        routePlaying = false; routePaused = false
        routeProgress = 0.0; direction = 1; loopsDone = 0; stopRemainingMs = 0.0; routeSpeed = 0.0
        velE = 0.0; velN = 0.0
        setMapEditEnabled(true)
        redrawMap()                 // rebuild markers as draggable again
        updateMapButtons()
        updateRouteInfo()
        setRouteStatus("Playback stopped — still transmitting; map editable")
        appendStatus("Route playback stopped (TX still running).")
    }

    private fun startRouteLoop() {
        if (routeLoopRunning) return
        routeLoopRunning = true
        lastRouteTickMs = SystemClock.elapsedRealtime()
        mapHandler.postDelayed(routeTick, TICK_MS)
    }

    private fun stopRouteLoop() {
        routeLoopRunning = false
        mapHandler.removeCallbacks(routeTick)
    }

    private fun stepRoute() {
        val now = SystemClock.elapsedRealtime()
        var dt = (now - lastRouteTickMs) / 1000.0
        lastRouteTickMs = now
        if (dt <= 0.0) dt = TICK_S
        if (dt > 0.5) dt = 0.5
        if (!routePlaying || routePaused) return

        val pts = activePts()
        val total = RouteGeometry.totalDistance(pts)
        if (total <= 0.0) return
        val speedKmh = binding.mapSpeedSeek.progress

        // Random-stop hold: sit still until the timer expires. Clamp at zero so
        // the countdown doesn't overshoot negative (which would keep the
        // checkpoint-arming guard below from ever firing another stop).
        if (stopRemainingMs > 0.0) {
            stopRemainingMs = (stopRemainingMs - dt * 1000.0).coerceAtLeast(0.0)
            routeSpeed = 0.0
            pushAndShow(pts, total, speedKmh, "Stopped ${(stopRemainingMs / 1000).toInt() + 1}s")
            return
        }

        // Speed jitter: ± a fraction of the base cruise speed each tick.
        val jitter = dynJitter()
        val jf = if (jitter > 0.0) 1.0 + (Random.nextDouble() * 2 - 1) * jitter else 1.0
        val cruise = speedKmh.toDouble() / 3.6 * jf         // target cruise speed (m/s)

        // Trapezoidal velocity profile: accelerate from rest, cruise, then
        // decelerate so the sim arrives at rest wherever it must stop (route end,
        // a bounce turnaround, or the final lap's end). A loop's wrap is continuous
        // (no stop), so distToStop is null there and it keeps cruising.
        val accel = mapAccel()
        val loopN = loopLimit()
        val distToStop: Double? = when (repeatMode) {
            RepeatMode.OFF    -> total - routeProgress
            RepeatMode.BOUNCE -> if (direction > 0) total - routeProgress else routeProgress
            RepeatMode.LOOP   ->
                if (loopN > 0 && loopsDone >= loopN - 1) total - routeProgress else null
        }
        // Brake cap: the fastest speed from which we can still stop within
        // distToStop at this deceleration (v ≤ √(2·a·d)).
        val vCap = distToStop?.let { sqrt(2.0 * accel * max(0.0, it)) } ?: Double.MAX_VALUE
        val vTarget = min(cruise, vCap)
        routeSpeed =
            if (routeSpeed < vTarget) min(vTarget, routeSpeed + accel * dt)
            else max(vTarget, routeSpeed - accel * dt)
        val speed = routeSpeed

        val stopProb = dynStopProb()
        val prev = routeProgress
        routeProgress += direction * speed * dt

        var done = false
        if (routeProgress >= total) {
            when (repeatMode) {
                RepeatMode.OFF    -> { routeProgress = total; routeSpeed = 0.0; done = true }
                RepeatMode.BOUNCE -> { routeProgress = total; direction = -1; routeSpeed = 0.0; rollStop() }
                RepeatMode.LOOP   -> {
                    routeProgress -= total; loopsDone++
                    if (reachedLoopLimit()) { routeProgress = total; routeSpeed = 0.0; done = true } else rollStop()
                }
            }
        } else if (routeProgress <= 0.0) {
            routeProgress = 0.0
            if (repeatMode == RepeatMode.BOUNCE && direction == -1) {
                loopsDone++; direction = 1; routeSpeed = 0.0
                if (reachedLoopLimit()) done = true else rollStop()
            }
        }

        // Random stop when crossing an intermediate waypoint.
        if (stopRemainingMs <= 0.0 && stopProb > 0.0) {
            for (cp in stopCheckpoints) {
                val crossed = (prev < cp && routeProgress >= cp) || (prev > cp && routeProgress <= cp)
                if (crossed && Random.nextDouble() < stopProb) {
                    routeProgress = cp
                    routeSpeed = 0.0
                    stopRemainingMs = randomStopMs()
                    break
                }
            }
        }

        pushAndShow(pts, total, speedKmh, null)
        if (done) {
            routePaused = true
            updateMapButtons()
            appendStatus("Route complete.")
        }
    }

    /** Push the current interpolated position and update the realtime info line. */
    private fun pushAndShow(pts: List<GeoPt>, total: Double, speedKmh: Int, holdNote: String?) {
        val p = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
        liveLat = p.lat; liveLon = p.lon; liveHgt = p.alt ?: mapAlt()
        NativeGen.pushPosition(liveLat, liveLon, liveHgt)
        // The cursor is driven by the feedback poller (the generator's actual
        // encoded position), so it isn't moved from the commanded value here.
        updateRouteInfo()

        val pct = (routeProgress / total * 100).toInt()
        val modeTag = when (repeatMode) {
            RepeatMode.OFF -> ""; RepeatMode.BOUNCE -> " ⇄"; RepeatMode.LOOP -> " ↻"
        }
        val loops = if (repeatMode != RepeatMode.OFF) " · lap $loopsDone" else ""
        val head = holdNote ?: "Playing$modeTag"
        // Show the live (ramped) speed, not the setpoint, so accel/decel is visible.
        val kmh = if (holdNote != null) "0" else fmt(routeSpeed * 3.6, 0)
        setRouteStatus(
            "$head · $pct% · ${fmtDist(routeProgress)}/${fmtDist(total)} · " +
                "$kmh km/h · ${fmt(p.lat, 5)}, ${fmt(p.lon, 5)} · ${liveHgt.toInt()} m$loops"
        )
    }

    // ---- Dynamics helpers -------------------------------------------------

    private fun dynJitter(): Double =
        (binding.dynJitterInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 0.99)

    private fun dynStopProb(): Double =
        (binding.dynStopprobInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1.0)

    private fun randomStopMs(): Double {
        val minS = binding.dynStopminInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        val maxS = max(minS, binding.dynStopmaxInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0)
        return (minS + Random.nextDouble() * (maxS - minS)) * 1000.0
    }

    private fun rollStop() {
        if (dynStopProb() > 0.0 && Random.nextDouble() < dynStopProb()) stopRemainingMs = randomStopMs()
    }

    /** Distances (along the route) where a random stop may occur. */
    private fun computeCheckpoints(pts: List<GeoPt>): List<Double> {
        if (pts.size < 2) return emptyList()
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] +
                RouteGeometry.haversine(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        // "Also stop at track points" → every vertex; otherwise only user waypoints.
        return if (binding.stopTrkptSwitch.isChecked) {
            cum.drop(1).dropLast(1)   // interior vertices (endpoints handled separately)
        } else {
            waypoints.map { w ->
                var best = 0; var bestD = Double.MAX_VALUE
                pts.forEachIndexed { i, p ->
                    val dd = RouteGeometry.haversine(w.lat, w.lon, p.lat, p.lon)
                    if (dd < bestD) { bestD = dd; best = i }
                }
                cum[best]
            }
        }
    }

    private fun reachedLoopLimit(): Boolean {
        val n = loopLimit()
        return n > 0 && loopsDone >= n
    }

    private fun routeProgressLabel(): String {
        val total = RouteGeometry.totalDistance(activePts())
        return "${fmtDist(routeProgress)} / ${fmtDist(total)}"
    }

    private fun updateCursor(p: GeoPt) {
        val map = binding.map
        val fresh = cursorMarker == null
        val c = cursorMarker ?: Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(resources, makeDot(CURSOR_ORANGE)) // symmetric = no offset
            title = "Position"
            infoWindow = null
            cursorMarker = this
            map.overlays.add(this)
        }
        c.position = GeoPoint(p.lat, p.lon)
        // Follow mode: keep the map locked on the sim position as it moves.
        if (centerSimMode == 2) applyFollowLock()
        map.invalidate()
        if (fresh) updateCenterSimFab()   // the "center on sim" FAB appears with the dot
    }

    /**
     * Follow lock: clamp the map's scrollable area to a zero-size box at the sim
     * dot. This pins the dot to the centre through zoom gestures and blocks the
     * user from panning away; cleared when not following.
     */
    private fun applyFollowLock() {
        val map = binding.map
        val c = cursorMarker
        if (centerSimMode == 2 && c != null) {
            val lat = c.position.latitude; val lon = c.position.longitude
            map.setScrollableAreaLimitDouble(org.osmdroid.util.BoundingBox(lat, lon, lat, lon))
            map.controller.setCenter(c.position)
        } else {
            map.setScrollableAreaLimitDouble(null)
        }
    }

    private fun removeCursor() {
        cursorMarker?.let { binding.map.overlays.remove(it) }
        cursorMarker = null
        binding.map.invalidate()
        updateCenterSimFab()
    }

    /**
     * Orange FAB press cycles: center-once → follow → center-once … Both actions
     * recentre immediately; follow additionally keeps the map centred as the sim
     * position moves (see updateCursor).
     */
    private fun onCenterSimClicked() {
        val c = cursorMarker ?: return
        centerSimMode = if (centerSimMode == 1) 2 else 1
        if (centerSimMode == 2) {
            applyFollowLock()                                  // lock to centre + block panning
        } else {
            binding.map.setScrollableAreaLimitDouble(null)     // release the lock
            binding.map.controller.animateTo(c.position)       // one-time recentre
        }
        updateCenterSimFab()
        appendStatus(if (centerSimMode == 2) "following simulated position" else "centered on simulated position")
    }

    /**
     * The orange "center on simulated position" FAB tracks the cursor's presence;
     * it deepens (locked look) while following, and resets when the dot is gone.
     */
    private fun updateCenterSimFab() {
        if (cursorMarker == null) centerSimMode = 0
        if (centerSimMode != 2) binding.map.setScrollableAreaLimitDouble(null)  // release stale lock
        binding.btnCenterSim.visibility =
            if (mode == Mode.MAP && cursorMarker != null) View.VISIBLE else View.GONE
        val tint = if (centerSimMode == 2) 0xFFE65100.toInt() else 0xFFFF9800.toInt()
        binding.btnCenterSim.backgroundTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    private fun updateMapButtons() {
        val hasRoute = activePts().isNotEmpty()
        val active = routePlaying                 // a playback is running (playing or paused)
        val playing = routePlaying && !routePaused
        // Play: start (idle / static TX) or resume (paused) — whenever not already playing.
        binding.btnPlay.isEnabled = hasRoute && !playing
        binding.btnPause.isEnabled = playing
        // Stop ends the playback only (TX keeps running), so it's meaningful only
        // while a playback is active.
        binding.btnStop.isEnabled = active

        // Quick FAB: shows pause while actively playing, play otherwise.
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.isEnabled = hasRoute || playing
        binding.btnStopFab.isEnabled = active
        updateStopTxButton()
        updateMapJoystick()
    }

    private fun setMapEditEnabled(enabled: Boolean) {
        binding.btnWpUndo.isEnabled = enabled
        binding.btnWpClear.isEnabled = enabled
        binding.btnGpxLoad.isEnabled = enabled
        binding.roadSwitch.isEnabled = enabled
        updateMapFabs()   // hide the delete FAB while transmitting
    }

    // ---- GPX --------------------------------------------------------------

    private fun loadGpx(uri: Uri) {
        try {
            val pts = contentResolver.openInputStream(uri).use { input ->
                if (input == null) emptyList() else GpxIo.parse(input)
            }
            if (pts.size < 2) { setRouteStatus("GPX has no usable points"); return }
            waypoints.clear()
            waypoints.addAll(pts)
            routeGeo = emptyList()
            selectedWaypoint = -1
            // A GPX already carries its own track geometry, so drive it straight
            // through those points rather than re-routing them via GraphHopper.
            // (Setting the switch fires its listener, which clears routeGeo and
            // reschedules — so guard the road path off before the fetch below.)
            binding.roadSwitch.isChecked = false
            updateWpCount(); redrawMap(); scheduleRouteFetch()
            updateMapButtons(); updateMapFabs()
            // Fit the map to the loaded set.
            val box = BoundingBox.fromGeoPointsSafe(pts.map { GeoPoint(it.lat, it.lon) })
            binding.map.post { binding.map.zoomToBoundingBox(box, true, 48) }
            setRouteStatus("Loaded ${pts.size} points from GPX")
            appendStatus("GPX loaded: ${pts.size} points")
        } catch (e: Exception) {
            setRouteStatus("Cannot read GPX: ${e.message}")
        }
    }

    private fun exportGpx(uri: Uri) {
        try {
            val gpx = GpxIo.build(activePts())
            contentResolver.openOutputStream(uri)?.use { it.write(gpx.toByteArray()) }
            setRouteStatus("Exported ${activePts().size} points to GPX")
            appendStatus("GPX exported: ${uri.lastPathSegment}")
        } catch (e: Exception) {
            setRouteStatus("Export failed: ${e.message}")
        }
    }

    // ======================================================================
    //  Input validation + misc
    // ======================================================================

    private fun readLiveSeedOrNull(): Triple<Double, Double, Double>? {
        val lat = binding.liveLatInput.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = binding.liveLonInput.text?.toString()?.trim()?.toDoubleOrNull()
        val hgt = binding.liveHgtInput.text?.toString()?.trim()?.toDoubleOrNull()
        binding.liveLatInputLayout.error = latError(lat)
        binding.liveLonInputLayout.error = lonError(lon)
        binding.liveHgtInputLayout.error = if (hgt == null) "Enter a number" else null
        if (latError(lat) != null || lonError(lon) != null || hgt == null) return null
        return Triple(lat!!, lon!!, hgt)
    }

    private fun latError(lat: Double?) = when {
        lat == null      -> "Enter a number"
        abs(lat) > 90.0  -> "Must be in -90..90"
        else             -> null
    }

    private fun lonError(lon: Double?) = when {
        lon == null       -> "Enter a number"
        abs(lon) > 180.0  -> "Must be in -180..180"
        else              -> null
    }

    private fun fmt(v: Double, decimals: Int): String = "%.${decimals}f".format(v)

    private fun fmtDist(m: Double): String =
        if (m < 1000.0) "${m.toInt()} m" else "%.2f km".format(m / 1000.0)

    private fun setTabsEnabled(enabled: Boolean) {
        // Modes stay switchable even mid-TX: LLH and Map both feed the same LLH
        // FIFO, so switching only swaps the position source, never restarts the
        // generator (see selectMode → handleTxSourceSwitch). Kept as a no-op hook
        // in case a mode ever needs locking again.
        binding.modeLive.isEnabled = true
        binding.modeMap.isEnabled = true
        binding.modeSettings.isEnabled = true
    }

    /**
     * Let a fixed-height log TextView scroll on its own and be selected/copied
     * (so the user can grab log text to report errors). textIsSelectable installs
     * a movement method that both scrolls and selects; on touch we ask the
     * enclosing scroll view to stop intercepting so the drag scrolls the log
     * rather than the whole panel.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupScrollableLog(tv: android.widget.TextView) {
        tv.setTextIsSelectable(true)
        tv.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false   // let the movement method handle the actual scrolling
        }
    }

    /** True when the log is scrolled to (or near) the bottom. */
    private fun logAtBottom(tv: android.widget.TextView): Boolean {
        val layout = tv.layout ?: return true
        val bottom = layout.getLineTop(tv.lineCount) - tv.height
        return tv.scrollY >= bottom - tv.lineHeight   // within one line of the end
    }

    private fun appendStatus(line: String) {
        val stamped = "[${logTimeFmt.format(java.util.Date())}] $line"
        runOnUiThread {
            // Mirror the log to both the drawer and the Settings tab.
            for (tv in listOf(binding.statusText, binding.statusTextSettings)) {
                // Only follow the tail if the user was already at the bottom, so
                // scrolling up to read history isn't yanked back down by new lines.
                val follow = logAtBottom(tv)
                val prev = tv.text
                tv.text = if (prev.isEmpty()) stamped else "$prev\n$stamped"
                if (follow) tv.post {
                    val layout = tv.layout ?: return@post
                    val bottom = layout.getLineTop(tv.lineCount) - tv.height
                    tv.scrollTo(0, if (bottom > 0) bottom else 0)
                }
            }
        }
    }

    /**
     * Show a toast, cancelling any previous one first so repeatedly tapping a
     * button (e.g. Start TX / Play with no HackRF) doesn't stack up a long queue
     * of identical toasts that linger on screen.
     */
    private fun showToast(msg: String) {
        activeToast?.cancel()
        activeToast = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG)
        activeToast?.show()
    }

    private fun copyAssetToFiles(name: String): File {
        val dst = File(filesDir, name)
        if (!dst.exists()) {
            assets.open(name).use { input -> FileOutputStream(dst).use { out -> input.copyTo(out) } }
        }
        return dst
    }

    // ---- Ephemeris (brdc) selection --------------------------------------

    /** The ephemeris file the generator should use: the user's if set, else bundled. */
    private fun resolveEphemerisFile(): File {
        val user = File(filesDir, USER_BRDC_FILE)
        return if (settingsPrefs.getString("ephem_name", null) != null && user.exists()) user
        else copyAssetToFiles("brdc0010.22n")
    }

    /** Copy a user-picked brdc file into app storage and remember its name. */
    private fun importBrdc(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "custom brdc"
            val dst = File(filesDir, USER_BRDC_FILE)
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) { appendStatus("could not open the selected file"); return }
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            settingsPrefs.edit().putString("ephem_name", name).apply()
            updateEphemStatus()
            appendStatus("ephemeris set to: $name")
        } catch (e: Exception) {
            appendStatus("failed to load brdc: ${e.message}")
        }
    }

    private fun resetBrdc() {
        settingsPrefs.edit().remove("ephem_name").apply()
        File(filesDir, USER_BRDC_FILE).delete()
        updateEphemStatus()
        appendStatus("ephemeris reset to bundled brdc0010.22n")
    }

    private fun updateEphemStatus() {
        val name = settingsPrefs.getString("ephem_name", null)
        val hasFile = File(filesDir, USER_BRDC_FILE).exists()
        binding.ephemStatus.text =
            if (name != null && hasFile) "Using: $name" else "Using bundled brdc0010.22n"
        // The ephemeris is read once at TX start, so only allow changing it while idle.
        binding.ephemChooseButton.isEnabled = !txRunning
        binding.ephemResetButton.isEnabled = !txRunning && name != null && hasFile
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    external fun stringFromJNI(): String

    companion object {
        init { System.loadLibrary("livegpssdrsim") }

        private const val USER_BRDC_FILE = "user_brdc"   // copied user-selected ephemeris
        private const val TICK_MS = 100L
        private const val TICK_S = TICK_MS / 1000.0
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val DEFAULT_ACCEL = 3.0   // m/s² fallback for the accel fields
        private const val ROUTE_DEBOUNCE_MS = 700L
        private const val MAX_TRKPT_MARKERS = 250
        private const val LOCATION_BLUE = 0xFF1E88E5.toInt()  // real phone location
        private const val CURSOR_ORANGE = 0xFFFF9800.toInt()  // simulated route position
    }
}
