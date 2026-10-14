package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Walk-away detection.
 *
 *   DRIVING  — phone is connected to the car's own Bluetooth.
 *   PARKED   — car Bluetooth dropped (you switched off and got out). Watching the ESP32 tag.
 *   AWAY     — tag signal faded below the threshold, or the link dropped, long enough:
 *              we've told the server, which locks the car.
 *
 * From AWAY, coming back near the tag for a minute re-arms (PARKED) — e.g. you went back to
 * grab something. Later walk-aways in the same stop ask the server to check the car first, so
 * pacing around a car you'd already locked doesn't fire lock commands every time.
 * If the tag never shows up after parking (dead power bank…), we fall back to car Bluetooth
 * alone and let the server use its normal grace period.
 */
class ProximityService : Service() {

    companion object {
        const val ACTION_ARM = "ca.ishmeet.ioniq6prox.ARM"
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1

        private const val SETTLE_MS = 10_000L      // ignore tag hiccups right after parking (power-bank switchover)
        private const val LINK_HOLD_MS = 5_000L    // link must stay down this long to count as "gone"
        private const val RSSI_HOLD_MS = 3_000L    // signal must stay weak this long
        private const val FALLBACK_MS = 25_000L    // tag never connected after parking → car-Bluetooth fallback
        private const val REARM_MS = 60_000L       // strong signal this long while AWAY → re-arm
        private const val HYSTERESIS_DB = 8
        private const val MAX_AWAYS_PER_STOP = 5

        fun hasPermissions(c: Context): Boolean {
            val need = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            return need.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        }

        fun start(c: Context) {
            if (!hasPermissions(c)) { Status.log("Can't start: Bluetooth permission missing"); return }
            c.startForegroundService(Intent(c, ProximityService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ProximityService::class.java))
        }
    }

    private enum class Mode(val label: String) { UNKNOWN("Waiting for car"), DRIVING("Driving"), PARKED("Parked — watching"), AWAY("Away") }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var net: ExecutorService
    private var gatt: BluetoothGatt? = null

    private var mode = Mode.UNKNOWN
    private var espConnected = false
    private var smoothed: Double? = null
    private var parkedAt = 0L
    private var linkDownSince = 0L
    private var belowSince = 0L
    private var aboveSince = 0L
    private var tagSeenThisStop = false
    private var fallbackSent = false
    private var awaysThisStop = 0
    private var lastNotifText = ""
    private var lastNotifAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        net = Executors.newSingleThreadExecutor()
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Status.log("Couldn't start in background: ${e.message}")
            stopSelf()
            return
        }
        Status.running = true
        registerAclReceiver()
        connectTag()
        checkCarAlreadyConnected()
        handler.post(tick)
        Status.log("Monitoring started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM) arm("armed manually (test)")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(aclReceiver) } catch (_: Exception) {}
        try { gatt?.close() } catch (_: SecurityException) {}
        gatt = null
        if (::net.isInitialized) net.shutdown()
        Status.running = false
        Status.mode = "Stopped"
        Status.espConnected = false
        Status.rssi = null
        Status.log("Monitoring stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ car Bluetooth

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.bluetoothDevice() ?: return
            if (dev.address != prefs.carAddress) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> handler.post { onCarConnected() }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handler.post { onCarDisconnected() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun registerAclReceiver() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(aclReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(aclReceiver, f)
    }

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    /** At start-up there's no "connected" broadcast for a link that already exists, so ask the audio profiles. */
    private fun checkCarAlreadyConnected() {
        val a = adapter() ?: return
        val car = prefs.carAddress ?: return
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        if (proxy.connectedDevices.any { it.address == car }) handler.post { if (mode != Mode.DRIVING) onCarConnected() }
                    } catch (_: SecurityException) {
                    } finally {
                        a.closeProfileProxy(p, proxy)
                    }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    private fun onCarConnected() {
        Status.carConnected = true
        if (mode == Mode.DRIVING) return
        setMode(Mode.DRIVING, "car Bluetooth connected")
        send("/v1/autolock/near", """{"source":"car-bluetooth"}""", "near")
    }

    private fun onCarDisconnected() {
        Status.carConnected = false
        arm("car Bluetooth disconnected")
    }

    private fun arm(why: String) {
        parkedAt = now()
        awaysThisStop = 0
        fallbackSent = false
        belowSince = 0
        aboveSince = 0
        tagSeenThisStop = espConnected
        linkDownSince = if (espConnected) 0 else now()
        setMode(Mode.PARKED, why)
    }

    // ------------------------------------------------------------------ ESP32 tag

    private fun connectTag() {
        val addr = prefs.espAddress ?: run { Status.log("No ESP32 chosen yet"); return }
        val dev = try { adapter()?.getRemoteDevice(addr) } catch (_: IllegalArgumentException) { null } ?: return
        try {
            gatt?.close()
            // autoConnect = true: Android reconnects by itself whenever the tag is in range.
            gatt = dev.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Status.log("Watching ESP32 $addr")
        } catch (e: SecurityException) {
            Status.log("No Bluetooth permission: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    espConnected = true
                    linkDownSince = 0
                    if (mode == Mode.PARKED) tagSeenThisStop = true
                    Status.espConnected = true
                    Status.log("ESP32 connected")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = espConnected
                    espConnected = false
                    smoothed = null
                    linkDownSince = now()
                    Status.espConnected = false
                    Status.rssi = null
                    Status.rawRssi = null
                    if (was) Status.log("ESP32 disconnected (status $status)")
                    if (status == 133) {
                        // Generic stack error: start a fresh connection object.
                        handler.postDelayed({ connectTag() }, 2_000)
                    } else {
                        try { g.connect() } catch (_: SecurityException) {}
                    }
                }
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            handler.post {
                val s = smoothed
                // Exponential smoothing: RSSI swings ±10 dB on its own; this damps it over ~3 readings.
                smoothed = if (s == null) rssi.toDouble() else s * 0.7 + rssi * 0.3
                Status.rawRssi = rssi
                Status.rssi = smoothed?.toInt()
            }
        }
    }

    // ------------------------------------------------------------------ decision loop (1 Hz)

    private val tick = object : Runnable {
        override fun run() {
            try { evaluate() } catch (e: Exception) { Status.log("Error: ${e.message}") }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun evaluate() {
        val t = now()
        if (espConnected) try { gatt?.readRemoteRssi() } catch (_: SecurityException) {}
        val threshold = prefs.awayRssi
        val s = smoothed

        when (mode) {
            Mode.PARKED -> {
                if (!tagSeenThisStop && !fallbackSent && t - parkedAt > FALLBACK_MS) {
                    fallbackSent = true
                    awaysThisStop++
                    setMode(Mode.AWAY, "ESP32 not reachable — using car Bluetooth only")
                    send("/v1/autolock/away", """{"source":"car-bluetooth"}""", "away")
                    return
                }
                val settled = t - parkedAt > SETTLE_MS
                if (!espConnected) {
                    belowSince = 0
                    if (tagSeenThisStop && settled && linkDownSince > 0 && t - linkDownSince > LINK_HOLD_MS) walkedAway("ESP32 out of range")
                } else if (s != null && s < threshold) {
                    if (belowSince == 0L) belowSince = t
                    if (settled && t - belowSince > RSSI_HOLD_MS) walkedAway("signal ${s.toInt()} dBm (threshold $threshold)")
                } else {
                    belowSince = 0
                }
            }
            Mode.AWAY -> {
                if (espConnected && s != null && s > threshold + HYSTERESIS_DB) {
                    if (aboveSince == 0L) aboveSince = t
                    if (t - aboveSince > REARM_MS) {
                        aboveSince = 0
                        belowSince = 0
                        tagSeenThisStop = true
                        parkedAt = t - SETTLE_MS // already settled
                        setMode(Mode.PARKED, "back near the car — re-armed")
                        send("/v1/autolock/near", """{"source":"esp32"}""", "near")
                    }
                } else {
                    aboveSince = 0
                }
            }
            else -> {}
        }
        refreshNotif()
    }

    private fun walkedAway(reason: String) {
        awaysThisStop++
        belowSince = 0
        aboveSince = 0
        if (awaysThisStop > MAX_AWAYS_PER_STOP) {
            setMode(Mode.AWAY, "$reason — not sending (limit for this stop)")
            return
        }
        setMode(Mode.AWAY, reason)
        // First walk-away: lock immediately (the tag already proves you left).
        // Later ones in the same stop: have the server check first, so no redundant lock.
        val body = if (awaysThisStop == 1) """{"source":"esp32","graceSeconds":0}"""
                   else """{"source":"esp32","graceSeconds":0,"checkFirst":true}"""
        send("/v1/autolock/away", body, "away")
    }

    // ------------------------------------------------------------------ server calls

    private fun send(path: String, json: String, label: String) {
        if (!prefs.configured) { Status.log("Server not set up — skipped $label"); return }
        net.execute {
            var delay = 5_000L
            for (attempt in 1..12) {
                try {
                    val r = Api.request(prefs, "POST", path, json)
                    Status.log("$label → HTTP ${r.code}")
                    if (r.code in 200..299) return@execute
                    if (r.code in 400..499 && r.code != 429) return@execute  // our problem; retrying won't help
                } catch (e: Exception) {
                    Status.log("$label failed (${e.javaClass.simpleName}); retry in ${delay / 1000}s")
                }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@execute }
                delay = (delay * 2).coerceAtMost(60_000)
            }
            Status.log("$label gave up after 12 tries")
        }
    }

    // ------------------------------------------------------------------ notification

    private fun setMode(m: Mode, why: String) {
        mode = m
        Status.mode = m.label
        Status.log("${m.label}: $why")
        refreshNotif(force = true)
    }

    private fun describe(): String {
        val tag = if (espConnected) "ESP32 ${Status.rssi ?: "…"} dBm" else "ESP32 not connected"
        return "${mode.label} · $tag"
    }

    private fun refreshNotif(force: Boolean = false) {
        val text = describe()
        if (!force && (text == lastNotifText || now() - lastNotifAt < 10_000)) return
        lastNotifText = text
        lastNotifAt = now()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Proximity monitor", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shows that walk-away auto-lock is running"
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("IONIQ 6 auto-lock")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun now() = System.currentTimeMillis()
}
