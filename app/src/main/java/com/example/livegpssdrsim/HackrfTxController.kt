package com.example.livegpssdrsim

import android.content.Context
import android.util.Log
import com.mantz_it.hackrf_android.Hackrf
import com.mantz_it.hackrf_android.HackrfCallbackInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the HackRF USB device and pumps I/Q samples from gpssim's ring buffer
 * into the hackrf_android TX queue. One-shot; construct a new instance per
 * transmission.
 *
 * Threading:
 *   - openAsync() runs on the caller's coroutine; hackrf_android performs USB
 *     enumeration + permission on its own thread and calls back into us.
 *   - Once open, startTx() spawns a producer thread that pulls PACKET_SIZE
 *     bytes at a time from NativeGen and offers them to the TX queue.
 *   - stop() signals the producer to exit and closes the USB device.
 */
class HackrfTxController(private val appContext: Context) {

    companion object {
        private const val TAG = "HackrfTx"

        // GPS L1 C/A sample rate used by gps-sdr-sim (2.6 MHz).
        // Must match the -s flag passed to gpssim.
        const val SAMPLE_RATE_HZ = 2_600_000
        const val CENTER_FREQ_HZ = 1_575_420_000L
        const val QUEUE_SIZE     = 16  // hackrf_android internal buffer pool depth
    }

    private var hackrf: Hackrf? = null
    private var txQueue: ArrayBlockingQueue<ByteArray>? = null
    private var producerThread: Thread? = null
    private val running = AtomicBoolean(false)

    // Live-adjustable TX settings. These are the values that will be pushed
    // to the HackRF the next time startTx() runs or setAmp/setVgaGain is
    // called with the device already open.
    @Volatile private var ampEnabled: Boolean = false
    @Volatile private var vgaGain: Int = 0   // 0..47 dB

    /** Requests USB permission and opens the HackRF. Suspends until ready. */
    suspend fun openAsync(): Hackrf = suspendCancellableCoroutine { cont ->
        val requested = Hackrf.initHackrf(appContext, object : HackrfCallbackInterface {
            override fun onHackrfReady(dev: Hackrf) {
                hackrf = dev
                if (cont.isActive) cont.resume(dev)
            }
            override fun onHackrfError(msg: String) {
                Log.e(TAG, "onHackrfError: $msg")
                if (cont.isActive) cont.resumeWithException(RuntimeException("HackRF: $msg"))
            }
        }, QUEUE_SIZE)

        if (!requested) {
            cont.resumeWithException(RuntimeException(
                "HackRF not found on the USB bus (is the OTG cable connected and the device powered?)"))
        }
    }

    /**
     * Configures the device for L1 TX, starts the TX stream, and spawns the
     * producer. Suspends on Dispatchers.IO because every setter here is a
     * blocking USB control transfer — running them on the main thread will
     * ANR or crash on modern Android with StrictMode enforcement.
     */
    suspend fun startTx() = withContext(Dispatchers.IO) {
        val dev = hackrf ?: error("HackRF not opened; call openAsync() first")

        dev.setSampleRate(SAMPLE_RATE_HZ, 1)
        // Filter bandwidth: pick the closest supported one to the sample rate.
        dev.setBasebandFilterBandwidth(Hackrf.computeBasebandFilterBandwidth(SAMPLE_RATE_HZ))
        dev.setFrequency(CENTER_FREQ_HZ)
        dev.setTxVGAGain(vgaGain.coerceIn(0, 47))
        dev.setAmp(ampEnabled)

        val queue = dev.startTX()
        txQueue = queue
        running.set(true)

        val packetSize = dev.getPacketSize()
        Log.i(TAG, "TX started; packetSize=$packetSize sampleRate=$SAMPLE_RATE_HZ freq=$CENTER_FREQ_HZ")

        producerThread = Thread({ producerLoop(dev, queue, packetSize) }, "hackrf-tx-producer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun producerLoop(dev: Hackrf, queue: ArrayBlockingQueue<ByteArray>, packetSize: Int) {
        try {
            while (running.get()) {
                val buf = dev.getBufferFromBufferPool() ?: ByteArray(packetSize)
                if (buf.size != packetSize) {
                    // Shouldn't happen — the pool always hands us packetSize.
                    Log.w(TAG, "unexpected buffer size ${buf.size}, wanted $packetSize")
                }

                val got = NativeGen.rbPullBytes(buf, packetSize)
                if (got <= 0) {
                    Log.i(TAG, "generator drained; stopping producer")
                    running.set(false)
                    break
                }
                if (got < packetSize) {
                    // Final short read on shutdown — zero-fill the tail so we
                    // don't transmit whatever was in the buffer previously.
                    java.util.Arrays.fill(buf, got, packetSize, 0.toByte())
                }

                // Block up to 1 s if the USB thread is behind.
                if (!queue.offer(buf, 1000, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "TX queue backed up; dropping buffer")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "producer loop crashed", t)
        }
    }

    // -----------------------------------------------------------------
    // Live setters. Update the cached value immediately, and if the
    // device is currently open, push the change over USB from a
    // background thread (both setters block on USB control transfers).
    // -----------------------------------------------------------------

    fun setAmp(enabled: Boolean) {
        ampEnabled = enabled
        val dev = hackrf ?: return
        Thread({
            try { dev.setAmp(enabled) }
            catch (t: Throwable) { Log.w(TAG, "setAmp($enabled) failed", t) }
        }, "hackrf-setAmp").start()
    }

    fun setVgaGain(gain: Int) {
        val clamped = gain.coerceIn(0, 47)
        vgaGain = clamped
        val dev = hackrf ?: return
        Thread({
            try { dev.setTxVGAGain(clamped) }
            catch (t: Throwable) { Log.w(TAG, "setTxVGAGain($clamped) failed", t) }
        }, "hackrf-setVga").start()
    }

    /**
     * Stop the TX stream but keep the USB device open so it can be re-armed with
     * another startTx(). Safe to call multiple times. Use for "Stop TX"; the app
     * drops its reference to this controller to fully disconnect.
     */
    fun stopTx() {
        running.set(false)
        NativeGen.requestShutdown()

        // hackrf.stop() does USB I/O — must not run on the main thread.
        val dev = hackrf
        if (dev != null) {
            Thread({
                try { dev.stop() } catch (t: Throwable) { Log.w(TAG, "hackrf.stop() failed", t) }
            }, "hackrf-stop").start()
        }

        try { producerThread?.join(2000) } catch (_: InterruptedException) {}
        producerThread = null
        txQueue = null
    }
}
