package com.example.livegpssdrsim

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
import android.text.method.ScrollingMovementMethod
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
import com.example.livegpssdrsim.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
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

    private var mode: Mode = Mode.LIVE

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

    // ---- Map / route state ------------------------------------------------
    private val waypoints = mutableListOf<GeoPt>()
    private var routeGeo: List<GeoPt> = emptyList()   // road geometry (empty = straight-line)
    private val wpMarkers = mutableListOf<Marker>()
    private var routeLine: Polyline? = null
    private var cursorMarker: Marker? = null
    private var routeJob: Job? = null
    private var myLocationOverlay: MyLocationNewOverlay? = null
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
    private var routeProgress = 0.0                    // metres travelled along the route
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

        binding.statusText.movementMethod = ScrollingMovementMethod()
        binding.statusTextSettings.movementMethod = ScrollingMovementMethod()
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
        selectMode(Mode.LIVE)
    }

    private fun selectMode(newMode: Mode) {
        mode = newMode
        // Content: full-screen map for Map mode; the Raw/Live/Settings forms
        // (in the scroll) otherwise.
        binding.map.visibility           = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (mode == Mode.MAP) View.GONE else View.VISIBLE
        binding.panelLive.visibility     = if (mode == Mode.LIVE)     View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (mode == Mode.SETTINGS) View.VISIBLE else View.GONE
        // Drawer: map options only in Map mode; the Start-TX button applies to
        // the LLH tab (Map uses Play/Pause/Stop; Settings has no TX action).
        binding.panelMap.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.fabColumn.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.playColumn.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.txButton.visibility = if (mode == Mode.LIVE) View.VISIBLE else View.GONE
        updateMapFabs()
    }

    // ---- HackRF connect / disconnect --------------------------------------

    private fun setupDeviceControls() {
        binding.btnConnect.setOnClickListener {
            if (connected) onDisconnect() else onConnect()
        }
        updateConnUi()
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
        updateMapButtons()
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
        binding.setPosButton.setOnClickListener { onSetPosition() }
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
        txService?.stopTx()
        binding.txButton.text = "Start TX"
        setLiveControlsEnabled(false)
        // Reset route playback UI + cursor.
        routeProgress = 0.0
        direction = 1
        loopsDone = 0
        stopRemainingMs = 0.0
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
            appendStatus("connect the HackRF first (Controls → Connect)"); return
        }
        liveLat = seedLat; liveLon = seedLon; liveHgt = seedHgt
        velE = 0.0; velN = 0.0
        val retimeNow = binding.timenowSwitch.isChecked
        binding.txButton.isEnabled = false
        setTabsEnabled(false)
        appendStatus("starting generator + TX at $seedLat,$seedLon,$seedHgt...")
        scope.launch {
            val ephem = withContext(Dispatchers.IO) { copyAssetToFiles("brdc0010.22n") }
            appendStatus("ephemeris: ${ephem.absolutePath}")
            val dummyOut = File(filesDir, "unused.bin")
            val args = buildList {
                add("gps-sdr-sim")
                add("-e"); add(ephem.absolutePath)
                add("-b"); add("8")
                if (retimeNow) { add("-T"); add("now") }
                add("-S"); add("-H")
                add("-o"); add(dummyOut.absolutePath)
            }.toTypedArray()
            svc.startTx(args, liveLat, liveLon, liveHgt).fold(
                onSuccess = {
                    binding.txButton.text = "Stop TX"
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

    private fun stepMotion(dt: Double) {
        val maxSpeed = binding.speedSeek.progress.toDouble() / 3.6 // km/h -> m/s
        val jx = binding.joystick.xNorm.toDouble()
        val jy = binding.joystick.yNorm.toDouble()
        val targetVE = jx * maxSpeed
        val targetVN = jy * maxSpeed

        val curSpeed = hypot(velE, velN)
        val targetSpeed = hypot(targetVE, targetVN)
        val rampTime = if (targetSpeed >= curSpeed) RAMP_UP_S else RAMP_DOWN_S
        val accel = if (maxSpeed > 0.0) maxSpeed / rampTime else FALLBACK_ACCEL
        val maxDelta = accel * dt

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
            // Track height from its field (unless the user is editing it), and
            // reflect the moving position back into the lat/lon fields — but not
            // while a field has focus, so a manual override isn't clobbered.
            if (!binding.liveHgtInput.hasFocus())
                binding.liveHgtInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { liveHgt = it }
            if (!binding.liveLatInput.hasFocus()) binding.liveLatInput.setText(fmt(liveLat, 6))
            if (!binding.liveLonInput.hasFocus()) binding.liveLonInput.setText(fmt(liveLon, 6))
            NativeGen.pushPosition(liveLat, liveLon, liveHgt)
        }
        binding.liveReadout.text =
            "TX: ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}, ${fmt(liveHgt, 1)} m\n" +
                "v = ${fmt(speed * 3.6, 1)} km/h"
    }

    private fun setLiveControlsEnabled(enabled: Boolean) {
        binding.joystick.isEnabled = enabled
        binding.speedSeek.isEnabled = enabled
        binding.presetWalk.isEnabled = enabled
        binding.presetRun.isEnabled = enabled
        binding.presetMoto.isEnabled = enabled
        binding.presetCar.isEnabled = enabled
        // The lat/lon/height fields stay editable throughout (they seed the run,
        // then override it via "Set position"), which is only actionable while TX.
        binding.setPosButton.isEnabled = enabled
        if (!enabled) {
            stopMotionLoop()
            binding.joystick.reset()
            binding.liveReadout.text = "idle"
        }
    }

    // ======================================================================
    //  Map: setup, waypoints, routing, GPX
    // ======================================================================

    private fun setupMap() {
        val map = binding.map
        // OSM's own tile server blocks unlisted apps, and Carto watermarks keyless
        // tiles, so use Esri's World Street Map (keyless, unwatermarked for dev use).
        // Esri tile paths are z/y/x, so build the URL explicitly.
        val esri = object : OnlineTileSourceBase(
            "EsriWorldStreet", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
            "Tiles © Esri"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
        }
        map.setTileSource(esri)
        map.setMultiTouchControls(true)
        // Esri's tiles top out around z19; cap zoom there so we never show blurry
        // upscaled tiles (which look like "map data not yet available").
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 22.0
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
                if (txRunning) return false   // route is locked while transmitting
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
                if (txRunning) {
                    // Transmitting: long-press sets the simulated position (teleport).
                    val p = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
                    liveLat = p.latitude; liveLon = p.longitude; velE = 0.0; velN = 0.0
                    NativeGen.pushPositionForce(liveLat, liveLon, liveHgt)
                    updateCursor(GeoPt(liveLat, liveLon, liveHgt))
                    appendStatus("sim position set: ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}")
                    return true
                }
                // Editing: long-press clears any selection.
                if (selectedWaypoint != -1) {
                    selectedWaypoint = -1; redrawMap(); updateMapFabs()
                }
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

        redrawMap()
        updateWpCount()
        updateMapButtons()
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
            if (txRunning || waypoints.isEmpty()) return@setOnClickListener
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
            if (txRunning) stopCheckpoints = computeCheckpoints(activePts())
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
        binding.btnDeleteWp.setOnClickListener { deleteSelectedWaypoint() }
        binding.btnDeleteAll.setOnClickListener { clearWaypoints() }

        binding.btnPlay.setOnClickListener { onPlayClicked() }
        binding.btnPause.setOnClickListener { onPauseClicked() }
        binding.btnStop.setOnClickListener { stopEverything() }
        binding.btnPlayPause.setOnClickListener {
            if (txRunning && !routePaused) onPauseClicked() else onPlayClicked()
        }
        binding.btnStopFab.setOnClickListener { stopEverything() }

        binding.btnGpxLoad.setOnClickListener {
            if (txRunning) return@setOnClickListener
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
        if (txRunning) return           // route is locked while transmitting
        selectedWaypoint = index
        redrawMap()
        updateMapFabs()
        setRouteStatus("Waypoint ${index + 1} selected — tap the trash button to delete")
    }

    private fun deleteSelectedWaypoint() {
        val i = selectedWaypoint
        if (txRunning || i !in waypoints.indices) return
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
        if (txRunning) return
        waypoints.clear(); routeGeo = emptyList(); selectedWaypoint = -1
        updateWpCount(); redrawMap()
        setRouteStatus("Tap the map to add waypoints")
        updateMapButtons(); updateMapFabs()
    }

    private fun updateMapFabs() {
        val editable = mode == Mode.MAP && !txRunning
        binding.btnDeleteWp.visibility =
            if (editable && selectedWaypoint in waypoints.indices) View.VISIBLE else View.GONE
        binding.btnDeleteAll.visibility =
            if (editable && waypoints.isNotEmpty()) View.VISIBLE else View.GONE
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
        if (txRunning) return
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
                isDraggable = !txRunning
                // Let the tap fall through to the tap overlay, which does the
                // hit-test (select this waypoint) vs. add-new decision in one place.
                setOnMarkerClickListener { _, _ -> false }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(mk: Marker) {}
                    override fun onMarkerDrag(mk: Marker) {}
                    override fun onMarkerDragEnd(mk: Marker) {
                        if (txRunning || i !in waypoints.indices) return
                        waypoints[i] = GeoPt(mk.position.latitude, mk.position.longitude, null)
                        updateWpCount(); redrawMap(); scheduleRouteFetch(); updateRouteInfo()
                    }
                })
            }
            wpMarkers.add(m)
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
        setRouteStatus("Calculating route…")
        routeJob = scope.launch {
            try {
                val geo = GraphHopperRouter.route(wps, profile, key)
                routeGeo = geo
                redrawMap()
                updateRouteInfo()
                setRouteStatus("Road route ($profile) · ${fmtDist(RouteGeometry.totalDistance(activePts()))}")
                maybeEnrichElevation()
            } catch (e: Exception) {
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

        if (!txRunning) {
            val seed = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
            setMapEditEnabled(false)
            direction = 1; loopsDone = 0; stopRemainingMs = 0.0
            stopCheckpoints = computeCheckpoints(pts)
            beginLiveTx(seed.lat, seed.lon, seed.alt ?: mapAlt()) {
                routePlaying = true; routePaused = false
                startRouteLoop()
                updateMapButtons()
                updateRouteInfo()
                appendStatus("Route playback started (${repeatMode.name.lowercase()}).")
            }
        } else {
            // Resume from pause.
            routePaused = false; routePlaying = true
            if (!routeLoopRunning) startRouteLoop()
            updateMapButtons()
        }
    }

    private fun onPauseClicked() {
        if (!txRunning) return
        routePaused = true
        setRouteStatus("Paused · ${routeProgressLabel()}")
        updateMapButtons()
        updateRouteInfo()
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
            pushAndShow(pts, total, speedKmh, "Stopped ${(stopRemainingMs / 1000).toInt() + 1}s")
            return
        }

        // Speed jitter: ± a fraction of the base speed each tick.
        val jitter = dynJitter()
        val jf = if (jitter > 0.0) 1.0 + (Random.nextDouble() * 2 - 1) * jitter else 1.0
        val speed = speedKmh.toDouble() / 3.6 * jf

        val stopProb = dynStopProb()
        val prev = routeProgress
        routeProgress += direction * speed * dt

        var done = false
        if (routeProgress >= total) {
            when (repeatMode) {
                RepeatMode.OFF    -> { routeProgress = total; done = true }
                RepeatMode.BOUNCE -> { routeProgress = total; direction = -1; rollStop() }
                RepeatMode.LOOP   -> {
                    routeProgress -= total; loopsDone++
                    if (reachedLoopLimit()) { routeProgress = total; done = true } else rollStop()
                }
            }
        } else if (routeProgress <= 0.0) {
            routeProgress = 0.0
            if (repeatMode == RepeatMode.BOUNCE && direction == -1) {
                loopsDone++; direction = 1
                if (reachedLoopLimit()) done = true else rollStop()
            }
        }

        // Random stop when crossing an intermediate waypoint.
        if (stopRemainingMs <= 0.0 && stopProb > 0.0) {
            for (cp in stopCheckpoints) {
                val crossed = (prev < cp && routeProgress >= cp) || (prev > cp && routeProgress <= cp)
                if (crossed && Random.nextDouble() < stopProb) {
                    routeProgress = cp
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
        updateCursor(p)
        updateRouteInfo()

        val pct = (routeProgress / total * 100).toInt()
        val modeTag = when (repeatMode) {
            RepeatMode.OFF -> ""; RepeatMode.BOUNCE -> " ⇄"; RepeatMode.LOOP -> " ↻"
        }
        val loops = if (repeatMode != RepeatMode.OFF) " · lap $loopsDone" else ""
        val head = holdNote ?: "Playing$modeTag"
        val kmh = if (holdNote != null) "0" else "$speedKmh"
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
        val c = cursorMarker ?: Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = BitmapDrawable(resources, makeDot(CURSOR_ORANGE)) // symmetric = no offset
            title = "Position"
            infoWindow = null
            cursorMarker = this
            map.overlays.add(this)
        }
        c.position = GeoPoint(p.lat, p.lon)
        map.invalidate()
    }

    private fun removeCursor() {
        cursorMarker?.let { binding.map.overlays.remove(it) }
        cursorMarker = null
        binding.map.invalidate()
    }

    private fun updateMapButtons() {
        val hasRoute = activePts().isNotEmpty()
        val transmitting = txRunning
        // Play: available when there's a route and we're either idle or paused.
        binding.btnPlay.isEnabled = hasRoute && (!transmitting || routePaused)
        binding.btnPause.isEnabled = transmitting && !routePaused
        binding.btnStop.isEnabled = transmitting

        // Quick FAB: shows pause while actively playing, play otherwise.
        val playing = transmitting && !routePaused
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.isEnabled = playing || (hasRoute && (!transmitting || routePaused))
        binding.btnStopFab.isEnabled = transmitting
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
        // While a transmission is in flight, lock the TX modes so the generation
        // can't be switched — but keep the active mode and Settings reachable so
        // amp / VGA gain can still be adjusted live.
        binding.modeLive.isEnabled = enabled || mode == Mode.LIVE
        binding.modeMap.isEnabled = enabled || mode == Mode.MAP
        binding.modeSettings.isEnabled = true
    }

    private fun appendStatus(line: String) {
        runOnUiThread {
            // Mirror the log to both the drawer and the Settings tab.
            for (tv in listOf(binding.statusText, binding.statusTextSettings)) {
                val prev = tv.text
                tv.text = if (prev.isEmpty()) line else "$prev\n$line"
                tv.post {
                    val layout = tv.layout ?: return@post
                    val bottom = layout.getLineTop(tv.lineCount) - tv.height
                    tv.scrollTo(0, if (bottom > 0) bottom else 0)
                }
            }
        }
    }

    private fun copyAssetToFiles(name: String): File {
        val dst = File(filesDir, name)
        if (!dst.exists()) {
            assets.open(name).use { input -> FileOutputStream(dst).use { out -> input.copyTo(out) } }
        }
        return dst
    }

    external fun stringFromJNI(): String

    companion object {
        init { System.loadLibrary("livegpssdrsim") }

        private const val TICK_MS = 100L
        private const val TICK_S = TICK_MS / 1000.0
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val RAMP_UP_S = 1.2
        private const val RAMP_DOWN_S = 0.8
        private const val FALLBACK_ACCEL = 5.0
        private const val ROUTE_DEBOUNCE_MS = 700L
        private const val MAX_TRKPT_MARKERS = 250
        private const val LOCATION_BLUE = 0xFF1E88E5.toInt()  // real phone location
        private const val CURSOR_ORANGE = 0xFFFF9800.toInt()  // simulated route position
    }
}
