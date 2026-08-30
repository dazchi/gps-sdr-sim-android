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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
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
import com.example.livegpssdrsim.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private enum class Mode { RAW, LIVE, MAP }

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tx: HackrfTxController? = null

    private var mode: Mode = Mode.RAW

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

    private var routePlaying = false
    private var routePaused = false
    private var routeProgress = 0.0                    // metres travelled along the route
    private var lastRouteTickMs: Long = 0L
    private var routeLoopRunning = false

    private val mapHandler = Handler(Looper.getMainLooper())
    private val routeTick = object : Runnable {
        override fun run() {
            stepRoute()
            if (routeLoopRunning) mapHandler.postDelayed(this, TICK_MS)
        }
    }
    private val routeFetchRunnable = Runnable { fetchRoute() }

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
                leftMargin = bars.left + gap
                topMargin = bars.top + gap
            }
            binding.fabColumn.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = bars.right + edge
                bottomMargin = bars.bottom + edge
            }
            insets
        }

        binding.statusText.movementMethod = ScrollingMovementMethod()
        appendStatus(stringFromJNI())
        binding.txButton.setOnClickListener { onTxClicked() }

        setupModeSelector()
        setupRfControls()
        setupLiveControls()
        setupMap()
        setupMapControls()
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
        tx?.stop()
        tx = null
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
                    R.id.mode_live -> Mode.LIVE
                    R.id.mode_map  -> Mode.MAP
                    else           -> Mode.RAW
                }
            )
        }
        selectMode(Mode.RAW)
    }

    private fun selectMode(newMode: Mode) {
        mode = newMode
        // Content: full-screen map for Map mode, the Raw/Live forms otherwise.
        binding.map.visibility          = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (mode == Mode.MAP) View.GONE else View.VISIBLE
        binding.panelRaw.visibility  = if (mode == Mode.RAW)  View.VISIBLE else View.GONE
        binding.panelLive.visibility = if (mode == Mode.LIVE) View.VISIBLE else View.GONE
        // Drawer: map options only in Map mode; Start-TX button is replaced by
        // Play/Pause/Stop there.
        binding.panelMap.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.fabColumn.visibility = if (mode == Mode.MAP) View.VISIBLE else View.GONE
        binding.txButton.visibility = if (mode == Mode.MAP) View.GONE else View.VISIBLE
        updateMapFabs()
    }

    private fun setupRfControls() {
        binding.ampSwitch.isChecked = ampEnabled
        binding.ampSwitch.setOnCheckedChangeListener { _, checked ->
            ampEnabled = checked
            tx?.setAmp(checked)
            appendStatus("amp: ${if (checked) "ON (+14 dB)" else "off"}")
        }

        binding.vgaSeek.progress = vgaGain
        binding.vgaLabel.text = "TX VGA Gain: $vgaGain dB"
        binding.vgaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                vgaGain = value
                binding.vgaLabel.text = "TX VGA Gain: $value dB"
                if (fromUser) tx?.setVgaGain(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
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
    }

    // ======================================================================
    //  Start / Stop TX
    // ======================================================================

    private fun onTxClicked() {
        if (tx != null) { stopEverything(); return }
        when (mode) {
            Mode.RAW  -> startRawTx()
            Mode.LIVE -> startLiveTx()
            Mode.MAP  -> {} // Map uses Play/Pause/Stop instead of this button
        }
    }

    private fun stopEverything() {
        appendStatus("stopping TX...")
        routePlaying = false
        routePaused = false
        stopMotionLoop()
        stopRouteLoop()
        tx?.stop()
        tx = null
        binding.txButton.text = "Start TX"
        setLiveControlsEnabled(false)
        // Reset route playback UI + cursor.
        routeProgress = 0.0
        removeCursor()
        setMapEditEnabled(true)
        updateMapButtons()
        setTabsEnabled(true)
        appendStatus("TX stopped.")
    }

    /** Static single-position TX — the original "Raw LLH" path. */
    private fun startRawTx() {
        val locationArg = readRawLocationOrNull() ?: run {
            appendStatus("fix the location fields first"); return
        }
        val retimeNow = binding.timenowSwitch.isChecked
        binding.txButton.isEnabled = false
        setTabsEnabled(false)
        appendStatus("opening HackRF over USB at $locationArg...")
        scope.launch {
            val result = runCatching {
                val ctrl = newController()
                ctrl.openAsync()
                appendStatus("HackRF ready; starting generator + TX")
                withContext(Dispatchers.IO) {
                    val ephem = copyAssetToFiles("brdc0010.22n")
                    appendStatus("ephemeris: ${ephem.absolutePath}")
                    val dummyOut = File(filesDir, "unused.bin")
                    val args = buildList {
                        add("gps-sdr-sim")
                        add("-e"); add(ephem.absolutePath)
                        add("-l"); add(locationArg)
                        add("-b"); add("8")
                        if (retimeNow) { add("-T"); add("now") }
                        add("-H")
                        add("-o"); add(dummyOut.absolutePath)
                    }.toTypedArray()
                    NativeGen.startStreaming(args)
                }
                ctrl.startTx()
                ctrl
            }
            result.fold(
                onSuccess = { ctrl ->
                    tx = ctrl
                    binding.txButton.text = "Stop TX"
                    appendStatus("TX running.")
                },
                onFailure = { t -> onTxStartFailed(t) }
            )
            binding.txButton.isEnabled = true
        }
    }

    private fun startLiveTx() {
        val seed = readLiveSeedOrNull() ?: run {
            appendStatus("fix the location fields first"); return
        }
        beginLiveTx(seed.first, seed.second, seed.third) {
            setLiveControlsEnabled(true)
            startMotionLoop()
            appendStatus("Live TX running. Push the joystick to move.")
        }
    }

    /**
     * Shared live/socket-mode TX launch used by both the Live tab and Map
     * playback. Seeds the generator at (lat,lon,hgt), starts streaming with -S,
     * and invokes [onRunning] on success.
     */
    private fun beginLiveTx(seedLat: Double, seedLon: Double, seedHgt: Double, onRunning: () -> Unit) {
        liveLat = seedLat; liveLon = seedLon; liveHgt = seedHgt
        velE = 0.0; velN = 0.0
        val retimeNow = binding.timenowSwitch.isChecked
        binding.txButton.isEnabled = false
        setTabsEnabled(false)
        appendStatus("opening HackRF over USB (live) at $seedLat,$seedLon,$seedHgt...")
        scope.launch {
            val result = runCatching {
                val ctrl = newController()
                ctrl.openAsync()
                appendStatus("HackRF ready; starting live generator + TX")
                withContext(Dispatchers.IO) {
                    val ephem = copyAssetToFiles("brdc0010.22n")
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
                    NativeGen.startStreaming(args)
                    NativeGen.pushPosition(liveLat, liveLon, liveHgt)
                }
                ctrl.startTx()
                ctrl
            }
            result.fold(
                onSuccess = { ctrl ->
                    tx = ctrl
                    binding.txButton.text = "Stop TX"
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
        tx?.stop(); tx = null
        setLiveControlsEnabled(false)
        stopRouteLoop()
        routePlaying = false; routePaused = false
        setMapEditEnabled(true)
        updateMapButtons()
        setTabsEnabled(true)
    }

    private fun newController(): HackrfTxController =
        HackrfTxController(applicationContext).apply {
            setAmp(ampEnabled)
            setVgaGain(vgaGain)
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
            binding.liveHgtInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { liveHgt = it }
            binding.liveLatInput.setText(fmt(liveLat, 6))
            binding.liveLonInput.setText(fmt(liveLon, 6))
            NativeGen.pushPosition(liveLat, liveLon, liveHgt)
        }
        binding.liveReadout.text =
            "v = ${fmt(speed * 3.6, 1)} km/h   ${fmt(liveLat, 6)}, ${fmt(liveLon, 6)}"
    }

    private fun setLiveControlsEnabled(enabled: Boolean) {
        binding.joystick.isEnabled = enabled
        binding.speedSeek.isEnabled = enabled
        binding.presetWalk.isEnabled = enabled
        binding.presetRun.isEnabled = enabled
        binding.presetMoto.isEnabled = enabled
        binding.presetCar.isEnabled = enabled
        binding.liveLatInput.isEnabled = !enabled
        binding.liveLonInput.isEnabled = !enabled
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
        map.controller.setZoom(12.0)
        map.controller.setCenter(GeoPoint(35.68, 139.76))

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (tx != null) return false   // route is locked while transmitting
                addWaypoint(p.latitude, p.longitude, null)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }
        map.overlays.add(MapEventsOverlay(receiver))

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
        } else {
            appendStatus("acquiring GPS fix for current location…")
            // runOnFirstFix fires on a background thread.
            ov.runOnFirstFix {
                runOnUiThread {
                    val p = ov.myLocation ?: return@runOnUiThread
                    binding.map.controller.animateTo(p)
                    binding.map.controller.setZoom(17.0)
                }
            }
        }
    }

    private fun setupMapControls() {
        // Restore any saved GraphHopper key.
        val prefs = getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
        binding.ghKeyInput.setText(prefs.getString("gh_key", ""))
        binding.ghKeyInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("gh_key", s?.toString().orEmpty()).apply()
                scheduleRouteFetch()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.roadSwitch.setOnCheckedChangeListener { _, checked ->
            val vis = if (checked) View.VISIBLE else View.GONE
            binding.profileGroup.visibility = vis
            binding.ghKeyLayout.visibility = vis
            if (!checked) routeGeo = emptyList()
            scheduleRouteFetch()
            redrawMap()
        }
        binding.profileGroup.setOnCheckedChangeListener { _, _ -> scheduleRouteFetch() }

        binding.btnWpUndo.setOnClickListener {
            if (tx != null || waypoints.isEmpty()) return@setOnClickListener
            waypoints.removeAt(waypoints.size - 1)
            updateWpCount(); redrawMap(); scheduleRouteFetch()
        }
        binding.btnWpClear.setOnClickListener { clearWaypoints() }

        binding.mapSpeedLabel.text = "Speed: ${binding.mapSpeedSeek.progress} km/h"
        binding.mapSpeedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                binding.mapSpeedLabel.text = "Speed: $value km/h"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
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

        binding.btnGpxLoad.setOnClickListener {
            if (tx != null) return@setOnClickListener
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

    private fun selectWaypoint(index: Int) {
        if (tx != null) return           // route is locked while transmitting
        selectedWaypoint = index
        redrawMap()
        updateMapFabs()
        setRouteStatus("Waypoint ${index + 1} selected — tap the trash button to delete")
    }

    private fun deleteSelectedWaypoint() {
        val i = selectedWaypoint
        if (tx != null || i !in waypoints.indices) return
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
        if (tx != null) return
        waypoints.clear(); routeGeo = emptyList(); selectedWaypoint = -1
        updateWpCount(); redrawMap()
        setRouteStatus("Tap the map to add waypoints")
        updateMapButtons(); updateMapFabs()
    }

    private fun updateMapFabs() {
        val editable = mode == Mode.MAP && tx == null
        binding.btnDeleteWp.visibility =
            if (editable && selectedWaypoint in waypoints.indices) View.VISIBLE else View.GONE
        binding.btnDeleteAll.visibility =
            if (editable && waypoints.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateWpCount() {
        binding.wpCount.text = "${waypoints.size} waypoint${if (waypoints.size == 1) "" else "s"}"
    }

    /** Geometry used for display, distance and playback interpolation. */
    private fun activePts(): List<GeoPt> =
        if (routeGeo.size >= 2) routeGeo else waypoints.toList()

    private fun mapAlt(): Double =
        binding.mapAltInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 100.0

    private fun setRouteStatus(text: String) { binding.routeStatus.text = text }

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
        // Waypoint markers on top of the line. Tapping one selects it (for
        // deletion); the selected marker is tinted.
        waypoints.forEachIndexed { i, w ->
            val m = Marker(map).apply {
                position = GeoPoint(w.lat, w.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = makeNumberedMarker(i + 1, i == selectedWaypoint)
                title = "Waypoint ${i + 1}"
                infoWindow = null   // no gray info bubble on tap
                setOnMarkerClickListener { _, _ -> selectWaypoint(i); true }
            }
            wpMarkers.add(m)
            map.overlays.add(m)
        }
        cursorMarker?.let { if (!map.overlays.contains(it)) map.overlays.add(it) }
        map.invalidate()

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
        mapHandler.postDelayed(routeFetchRunnable, ROUTE_DEBOUNCE_MS)
    }

    private fun fetchRoute() {
        routeJob?.cancel()
        if (!binding.roadSwitch.isChecked || waypoints.size < 2) {
            routeGeo = emptyList()
            redrawMap()
            return
        }
        val key = binding.ghKeyInput.text?.toString()?.trim().orEmpty()
        if (key.isBlank()) { setRouteStatus("Enter your GraphHopper API key for road routing"); return }
        val profile = when (binding.profileGroup.checkedRadioButtonId) {
            R.id.profile_bike -> "bike"
            R.id.profile_car  -> "car"
            else              -> "foot"
        }
        val wps = waypoints.toList()
        setRouteStatus("Calculating route…")
        routeJob = scope.launch {
            try {
                val geo = GraphHopperRouter.route(wps, profile, key)
                routeGeo = geo
                redrawMap()
                setRouteStatus("Road route ($profile) · ${fmtDist(RouteGeometry.totalDistance(geo))}")
            } catch (e: Exception) {
                routeGeo = emptyList()
                redrawMap()
                setRouteStatus("Routing failed: ${e.message} — using straight line")
            }
        }
    }

    // ======================================================================
    //  Map: route playback (feeds the same pushPosition path as the joystick)
    // ======================================================================

    private fun onPlayClicked() {
        val pts = activePts()
        if (pts.isEmpty()) { setRouteStatus("Add at least one waypoint"); return }

        if (tx == null) {
            val seed = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
            setMapEditEnabled(false)
            beginLiveTx(seed.lat, seed.lon, seed.alt ?: mapAlt()) {
                routePlaying = true; routePaused = false
                startRouteLoop()
                updateMapButtons()
                appendStatus("Route playback started.")
            }
        } else {
            // Resume from pause.
            routePaused = false; routePlaying = true
            if (!routeLoopRunning) startRouteLoop()
            updateMapButtons()
        }
    }

    private fun onPauseClicked() {
        if (tx == null) return
        routePaused = true
        setRouteStatus("Paused · ${routeProgressLabel()}")
        updateMapButtons()
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
        val speed = binding.mapSpeedSeek.progress.toDouble() / 3.6 // km/h -> m/s
        routeProgress += speed * dt

        var done = false
        if (routeProgress >= total) { routeProgress = total; done = true }

        val p = RouteGeometry.interpolateAt(pts, routeProgress, mapAlt()) ?: return
        liveLat = p.lat; liveLon = p.lon; liveHgt = p.alt ?: mapAlt()
        NativeGen.pushPosition(liveLat, liveLon, liveHgt)
        updateCursor(p)
        setRouteStatus("Playing · ${routeProgressLabel()} · ${binding.mapSpeedSeek.progress} km/h")

        if (done) {
            routePaused = true
            updateMapButtons()
            appendStatus("Route complete.")
            setRouteStatus("Complete · ${fmtDist(total)}")
        }
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
        val transmitting = tx != null
        // Play: available when there's a route and we're either idle or paused.
        binding.btnPlay.isEnabled = hasRoute && (!transmitting || routePaused)
        binding.btnPause.isEnabled = transmitting && !routePaused
        binding.btnStop.isEnabled = transmitting
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

    private fun readRawLocationOrNull(): String? {
        val lat = binding.rawLatInput.text?.toString()?.trim()?.toDoubleOrNull()
        val lon = binding.rawLonInput.text?.toString()?.trim()?.toDoubleOrNull()
        val hgt = binding.rawHgtInput.text?.toString()?.trim()?.toDoubleOrNull()
        binding.rawLatInputLayout.error = latError(lat)
        binding.rawLonInputLayout.error = lonError(lon)
        binding.rawHgtInputLayout.error = if (hgt == null) "Enter a number" else null
        if (latError(lat) != null || lonError(lon) != null || hgt == null) return null
        return "$lat,$lon,$hgt"
    }

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
        // Lock mode switching while a transmission is in flight.
        binding.modeRaw.isEnabled = enabled
        binding.modeLive.isEnabled = enabled
        binding.modeMap.isEnabled = enabled
    }

    private fun appendStatus(line: String) {
        runOnUiThread {
            val tv = binding.statusText
            val prev = tv.text
            tv.text = if (prev.isEmpty()) line else "$prev\n$line"
            tv.post {
                val layout = tv.layout ?: return@post
                val bottom = layout.getLineTop(tv.lineCount) - tv.height
                tv.scrollTo(0, if (bottom > 0) bottom else 0)
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
        private const val LOCATION_BLUE = 0xFF1E88E5.toInt()  // real phone location
        private const val CURSOR_ORANGE = 0xFFFF9800.toInt()  // simulated route position
    }
}
