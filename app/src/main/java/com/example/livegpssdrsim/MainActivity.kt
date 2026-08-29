package com.example.livegpssdrsim

import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.livegpssdrsim.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tx: HackrfTxController? = null

    // Cached UI-side settings. These are the source of truth when TX is not
    // running; when TX is running they're kept in sync with what we push to
    // the HackRF. Defaults chosen conservatively — amp off, gain at 0 dB.
    private var ampEnabled: Boolean = false
    private var vgaGain: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()   // targetSdk 37 draws it over content on API 35+
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                top = bars.top + 16,      // 16dp of visual breathing room
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }

        binding.sampleText.text = stringFromJNI()
        binding.txButton.setOnClickListener { onTxClicked() }

        // --- Amp / VGA controls ---
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
                // Push while the user is dragging — HackRF's TX VGA responds
                // in microseconds, and dev.setTxVGAGain is queued to a worker
                // thread so we don't stall the UI.
                if (fromUser) tx?.setVgaGain(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                appendStatus("vga gain: $vgaGain dB")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        tx?.stop()
        tx = null
    }

    // ---- Live TX path -----------------------------------------------------

    /**
     * Reads and validates the lat/lon inputs. Returns the "lat,lon,10" string
     * gpssim expects for its -l flag, or null if either field is missing /
     * out of range (with a UI error shown).
     */
    private fun readLocationOrNull(): String? {
        val latStr = binding.latInput.text?.toString()?.trim().orEmpty()
        val lonStr = binding.lonInput.text?.toString()?.trim().orEmpty()

        val lat = latStr.toDoubleOrNull()
        val lon = lonStr.toDoubleOrNull()

        binding.latInputLayout.error = when {
            lat == null            -> "Enter a number"
            lat !in -90.0..90.0    -> "Must be in -90..90"
            else                   -> null
        }
        binding.lonInputLayout.error = when {
            lon == null              -> "Enter a number"
            lon !in -180.0..180.0    -> "Must be in -180..180"
            else                     -> null
        }
        if (lat == null || lon == null ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        return "$lat,$lon,10"
    }

    private fun onTxClicked() {
        val current = tx
        if (current != null) {
            appendStatus("stopping TX...")
            current.stop()
            tx = null
            binding.txButton.text = "Start TX"
            appendStatus("TX stopped.")
            return
        }

        val locationArg = readLocationOrNull() ?: run {
            appendStatus("fix the location fields first")
            return
        }

        binding.txButton.isEnabled = false
        appendStatus("opening HackRF over USB at $locationArg...")
        scope.launch {
            val result = runCatching {
                val ctrl = HackrfTxController(applicationContext)
                // Seed the fresh controller with whatever the UI currently
                // shows — startTx() will read these when it configures the
                // device.
                ctrl.setAmp(ampEnabled)
                ctrl.setVgaGain(vgaGain)
                ctrl.openAsync()
                appendStatus("HackRF ready; starting generator + TX")

                withContext(Dispatchers.IO) {
                    // Start the generator with -H so it feeds the ring buffer.
                    // -o is still parsed but never written in streaming mode.
                    val ephem = copyAssetToFiles("brdc0010.22n")
                    val dummyOut = File(filesDir, "unused.bin")
                    val args = arrayOf(
                        "gps-sdr-sim",
                        "-e", ephem.absolutePath,
                        "-l", locationArg,
                        "-b", "8",
                        "-H",
                        "-o", dummyOut.absolutePath
                    )
                    NativeGen.startStreaming(args)
                }
                ctrl.startTx()   // suspend fun, dispatches to IO internally
                ctrl
            }
            result.fold(
                onSuccess = { ctrl ->
                    tx = ctrl
                    binding.txButton.text = "Stop TX"
                    appendStatus("TX running.")
                },
                onFailure = { t ->
                    Log.e("MainActivity", "TX startup failed", t)
                    appendStatus("TX failed: ${t.message}")
                    tx?.stop()
                    tx = null
                }
            )
            binding.txButton.isEnabled = true
        }
    }

    // ---- misc -------------------------------------------------------------

    private fun appendStatus(line: String) {
        runOnUiThread {
            val prev = binding.statusText.text
            binding.statusText.text = if (prev.isEmpty()) line else "$prev\n$line"
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
    }
}
