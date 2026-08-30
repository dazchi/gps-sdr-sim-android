package com.example.livegpssdrsim

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.livegpssdrsim.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private enum class Mode { RAW, LIVE }

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tx: HackrfTxController? = null

    // Which tab is active. The single Start button acts on this mode.
    private var mode: Mode = Mode.RAW

    // Cached UI-side RF settings. Source of truth when TX is not running; kept
    // in sync with the device while running. Conservative defaults — amp off,
    // gain at 0 dB.
    private var ampEnabled: Boolean = false
    private var vgaGain: Int = 0

    // ---- Live-mode motion state ------------------------------------------
    // Position (degrees / metres) integrated from the joystick velocity.
    private var liveLat: Double = 0.0
    private var liveLon: Double = 0.0
    private var liveHgt: Double = 0.0
    // Current velocity (metres/second), east & north components. Ramped toward
    // the joystick's target velocity each tick (trapezoidal accel/decel).
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
            if (dt > 0.5) dt = 0.5   // guard against scheduling stalls
            stepMotion(dt)
            if (motionRunning) motionHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()   // targetSdk 37 draws it over content on API 35+
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Extra breathing room around the content, on top of the system-bar /
        // cutout insets so nothing hugs the screen edge.
        val edge = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left + edge,
                top = bars.top + edge,
                right = bars.right + edge,
                bottom = bars.bottom + edge
            )
            insets
        }

        binding.statusText.movementMethod = ScrollingMovementMethod()
        appendStatus(stringFromJNI())
        binding.txButton.setOnClickListener { onTxClicked() }

        // --- Tabs: swap the visible mode panel, remember the active mode ---
        binding.modeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mode = if (tab.position == 1) Mode.LIVE else Mode.RAW
                binding.panelRaw.visibility  = if (mode == Mode.RAW)  View.VISIBLE else View.GONE
                binding.panelLive.visibility = if (mode == Mode.LIVE) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // --- Amp / VGA controls (shared across both tabs) ---
        binding.ampSwitch.isChecked = ampEnabled
        binding.ampSwitch.setOnCheckedChangeListener { _, checked ->
            ampEnabled = checked
            tx?.setAmp(checked)   // no-op if TX not running yet
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

        // --- Live: speed slider + presets + joystick ---
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

    override fun onDestroy() {
        super.onDestroy()
        stopMotionLoop()
        tx?.stop()
        tx = null
    }

    // ---- Start / Stop -----------------------------------------------------

    private fun onTxClicked() {
        val current = tx
        if (current != null) {
            appendStatus("stopping TX...")
            current.stop()
            tx = null
            binding.txButton.text = "Start TX"
            setLiveControlsEnabled(false)
            setTabsEnabled(true)
            appendStatus("TX stopped.")
            return
        }

        when (mode) {
            Mode.RAW  -> startRawTx()
            Mode.LIVE -> startLiveTx()
        }
    }

    /** Static single-position TX — the original "Raw LLH" path. */
    private fun startRawTx() {
        val locationArg = readRawLocationOrNull() ?: run {
            appendStatus("fix the location fields first")
            return
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
                        if (retimeNow) { add("-T"); add("now") } // retime ephemeris to current UTC
                        add("-H")
                        add("-o"); add(dummyOut.absolutePath)
                    }.toTypedArray()
                    NativeGen.startStreaming(args)
                }
                ctrl.startTx()
                ctrl
            }
            finishStart(result, live = false)
        }
    }

    /** Live moving-position TX — socket mode fed directly via pushPosition(). */
    private fun startLiveTx() {
        val seed = readLiveSeedOrNull() ?: run {
            appendStatus("fix the location fields first")
            return
        }
        liveLat = seed.first
        liveLon = seed.second
        liveHgt = seed.third
        velE = 0.0
        velN = 0.0

        val retimeNow = binding.timenowSwitch.isChecked
        binding.txButton.isEnabled = false
        setTabsEnabled(false)
        appendStatus("opening HackRF over USB (live) at $liveLat,$liveLon,$liveHgt...")
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
                        if (retimeNow) { add("-T"); add("now") } // retime ephemeris to current UTC
                        // -S = socket/live mode. On Android there is no TCP
                        // listener; positions are fed via pushPosition() below.
                        add("-S")
                        add("-H")
                        add("-o"); add(dummyOut.absolutePath)
                    }.toTypedArray()
                    NativeGen.startStreaming(args)
                    // Seed the initial position so the generator's wait unblocks
                    // and it can open the RF link and start filling the ring.
                    NativeGen.pushPosition(liveLat, liveLon, liveHgt)
                }
                ctrl.startTx()
                ctrl
            }
            finishStart(result, live = true)
        }
    }

    private fun finishStart(result: Result<HackrfTxController>, live: Boolean) {
        result.fold(
            onSuccess = { ctrl ->
                tx = ctrl
                binding.txButton.text = "Stop TX"
                if (live) {
                    setLiveControlsEnabled(true)
                    startMotionLoop()
                    appendStatus("Live TX running. Push the joystick to move.")
                } else {
                    appendStatus("TX running.")
                }
            },
            onFailure = { t ->
                Log.e("MainActivity", "TX startup failed", t)
                appendStatus("TX failed: ${t.message}")
                tx?.stop()
                tx = null
                setLiveControlsEnabled(false)
                setTabsEnabled(true)
            }
        )
        binding.txButton.isEnabled = true
    }

    private fun newController(): HackrfTxController =
        HackrfTxController(applicationContext).apply {
            // Seed with whatever the UI currently shows.
            setAmp(ampEnabled)
            setVgaGain(vgaGain)
        }

    // ---- Live motion loop -------------------------------------------------

    private fun startMotionLoop() {
        if (motionRunning) return
        motionRunning = true
        lastTickMs = SystemClock.elapsedRealtime()
        motionHandler.postDelayed(motionTick, TICK_MS)
    }

    private fun stopMotionLoop() {
        motionRunning = false
        motionHandler.removeCallbacks(motionTick)
        velE = 0.0
        velN = 0.0
    }

    /**
     * One integration step. The joystick sets a *target* velocity vector; the
     * current velocity ramps toward it at a bounded rate (trapezoidal
     * acceleration on push, deceleration on release), then position is
     * integrated and pushed to the generator.
     */
    private fun stepMotion(dt: Double) {
        // Slider is in km/h; the motion model works in m/s.
        val maxSpeed = binding.speedSeek.progress.toDouble() / 3.6   // m/s at full deflection

        // Target velocity = joystick vector × maxSpeed (east = x, north = y).
        val jx = binding.joystick.xNorm.toDouble()
        val jy = binding.joystick.yNorm.toDouble()
        val targetVE = jx * maxSpeed
        val targetVN = jy * maxSpeed

        // Ramp the velocity vector toward the target. Accelerate faster than we
        // decelerate feels sluggish; use a slightly quicker stop. Scale the rate
        // with maxSpeed so time-to-full-speed stays constant as the slider moves.
        val curSpeed = hypot(velE, velN)
        val targetSpeed = hypot(targetVE, targetVN)
        val rampTime = if (targetSpeed >= curSpeed) RAMP_UP_S else RAMP_DOWN_S
        val accel = if (maxSpeed > 0.0) maxSpeed / rampTime else FALLBACK_ACCEL
        val maxDelta = accel * dt

        val dvE = targetVE - velE
        val dvN = targetVN - velN
        val dvMag = hypot(dvE, dvN)
        if (dvMag <= maxDelta || dvMag == 0.0) {
            velE = targetVE
            velN = targetVN
        } else {
            velE += dvE / dvMag * maxDelta
            velN += dvN / dvMag * maxDelta
        }

        val speed = hypot(velE, velN)
        if (speed > 1e-4) {
            liveLat = (liveLat + velN * dt / METERS_PER_DEG_LAT).coerceIn(-90.0, 90.0)
            val cosLat = max(cos(Math.toRadians(liveLat)), 1e-6) // guard near poles
            liveLon += velE * dt / (METERS_PER_DEG_LAT * cosLat)
            if (liveLon > 180.0) liveLon -= 360.0
            if (liveLon < -180.0) liveLon += 360.0

            // Height is the one live-editable scalar in this mode.
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
        // Lat/lon are seed inputs before TX and live-driven displays during TX.
        binding.liveLatInput.isEnabled = !enabled
        binding.liveLonInput.isEnabled = !enabled
        if (!enabled) {
            stopMotionLoop()
            binding.joystick.reset()
            binding.liveReadout.text = "idle"
        }
    }

    // ---- Input validation -------------------------------------------------

    /** Raw tab: returns "lat,lon,hgt" for gpssim's -l flag, or null on error. */
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

    /** Live tab: returns (lat, lon, height) or null on error. */
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
        lat == null         -> "Enter a number"
        abs(lat) > 90.0     -> "Must be in -90..90"
        else                -> null
    }

    private fun lonError(lon: Double?) = when {
        lon == null         -> "Enter a number"
        abs(lon) > 180.0    -> "Must be in -180..180"
        else                -> null
    }

    // ---- misc -------------------------------------------------------------

    private fun fmt(v: Double, decimals: Int): String = "%.${decimals}f".format(v)

    /** Prevent switching modes while a transmission is in flight. */
    private fun setTabsEnabled(enabled: Boolean) {
        val strip = binding.modeTabs.getChildAt(0) as? ViewGroup ?: return
        strip.isEnabled = enabled
        for (i in 0 until strip.childCount) strip.getChildAt(i).isEnabled = enabled
    }

    private fun appendStatus(line: String) {
        runOnUiThread {
            val tv = binding.statusText
            val prev = tv.text
            tv.text = if (prev.isEmpty()) line else "$prev\n$line"
            // Auto-scroll to the newest line. Post so the layout is measured
            // with the appended text before we compute the scroll offset.
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
            assets.open(name).use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
        }
        return dst
    }

    external fun stringFromJNI(): String

    companion object {
        init { System.loadLibrary("livegpssdrsim") }

        private const val TICK_MS = 100L                 // motion/push rate (10 Hz)
        private const val TICK_S = TICK_MS / 1000.0
        private const val METERS_PER_DEG_LAT = 111_320.0 // ~constant with latitude
        private const val RAMP_UP_S = 1.2                // time to reach full speed
        private const val RAMP_DOWN_S = 0.8              // time to glide to a stop
        private const val FALLBACK_ACCEL = 5.0           // m/s² when maxSpeed == 0
    }
}
