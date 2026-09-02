package com.example.livegpssdrsim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the HackRF connection and the TX stream so they outlive the Activity —
 * a foreground service keeps the process alive (and shows a persistent
 * notification) while transmitting, so TX survives backgrounding and swipe-away.
 *
 * The Activity opens the HackRF (the USB permission dialog is friendlier from an
 * Activity) and hands the controller here via [adopt]. Position updates keep
 * flowing straight to the native generator (process-level) from the Activity;
 * only device operations (start/stop/amp/gain) go through this service.
 */
class TxService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.livegpssdrsim.action.STOP_TX"
        private const val CHANNEL_ID = "tx"
        private const val NOTIF_ID = 42
        private const val HACKRF_VENDOR_ID = 7504 // 0x1D50 (HackRF One / Jawbreaker / RAD1O)
    }

    inner class LocalBinder : Binder() {
        val service: TxService get() = this@TxService
    }

    private val binder = LocalBinder()
    private var hackrf: HackrfTxController? = null

    var txRunning = false
        private set
    val connected: Boolean get() = hackrf != null

    /** Invoked (on the main thread) whenever connected/txRunning changes. */
    var onStateChanged: (() -> Unit)? = null

    /** Invoked when the HackRF is physically unplugged. */
    var onUnplugged: (() -> Unit)? = null

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            if (hackrf == null) return
            @Suppress("DEPRECATION")
            val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (dev == null || dev.vendorId == HACKRF_VENDOR_ID) {
                onUnplugged?.invoke()
                disconnect()   // stops TX, releases, removes notification
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ContextCompat.registerReceiver(
            this, usbDetachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) disconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(usbDetachReceiver) }
        hackrf?.stopTx()
        hackrf = null
    }

    /** Take ownership of an already-opened HackRF and go to the foreground. */
    fun adopt(controller: HackrfTxController) {
        hackrf = controller
        startForeground(NOTIF_ID, buildNotification())
        notifyState()
    }

    fun disconnect() {
        hackrf?.stopTx()
        hackrf = null
        txRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyState()
    }

    /** Start the generator (already-built args) and the HackRF TX stream. */
    suspend fun startTx(args: Array<String>, lat: Double, lon: Double, hgt: Double): Result<Unit> {
        val c = hackrf ?: return Result.failure(IllegalStateException("HackRF not connected"))
        return runCatching {
            withContext(Dispatchers.IO) {
                NativeGen.startStreaming(args)
                NativeGen.pushPosition(lat, lon, hgt)
            }
            c.startTx()
            txRunning = true
            updateNotification()
            notifyState()
        }
    }

    /** Stop the TX stream but keep the device connected. */
    fun stopTx() {
        hackrf?.stopTx()
        txRunning = false
        updateNotification()
        notifyState()
    }

    fun setAmp(enabled: Boolean) { hackrf?.setAmp(enabled) }
    fun setVga(gain: Int) { hackrf?.setVgaGain(gain) }

    // ---- notification ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "GPS TX", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "HackRF GPS transmission" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (txRunning) "Transmitting GPS L1 (1575.42 MHz)" else "HackRF connected"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveGpsSdrSim")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (hackrf == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun notifyState() = onStateChanged?.invoke()
}
