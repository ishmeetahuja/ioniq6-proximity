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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
 * Walk-away detection.
 *
 *   DRIVING  — phone is connected to the car's own Bluetooth. (The ESP32 is usually OFF now:
 *              the power bank cuts its output while the car charges it.)
 *   PARKED   — car Bluetooth dropped (you switched off and got out). The ESP32 boots as the
 *              car cuts USB power; we scan for it actively and connect directly, then watch
 *              its signal.
 *   AWAY     — tag signal faded below the threshold, or the link dropped after fading:
 *              we've told the server, which locks the car.
 *
 * A link that drops while the signal was still STRONG isn't you walking away — it's the ESP32
 * losing power (you started the car, so the bank cut its output). That never locks by itself;
 * if the car's Bluetooth doesn't connect within POWERCUT_WAIT_MS we hand over to the server
 * with check-first, which skips the lock if the car is on.
 *
 * Without a drive (app just started, or AWAY), being right next to the tag for 30 s arms too —
 * e.g. you opened the car to grab something. Those walk-aways, and repeats in the same stop,
 * ask the server to check the car first, so an already-locked car is left alone. If the tag never shows up after parking (dead power
 * bank…), we fall back to car Bluetooth alone with the server's normal grace period.
 *
 *   HOME     — (home mode on) parked at home: on the home Wi-Fi, or within the home circle.
 *              Locks ONCE, homeDelaySec after parking (or not at all if a walk-away already
 *              locked it this stop), then ignores the tag until the next drive — walking around
 *              the house near the driveway doesn't keep locking the car.
 */
class ProximityService : Service() {

    companion object {
        const val ACTION_ARM = "ca.ishmeet.ioniq6prox.ARM"
        const val ACTION_PAUSE = "ca.ishmeet.ioniq6prox.PAUSE"
        const val ACTION_RESUME = "ca.ishmeet.ioniq6prox.RESUME"
        const val ACTION_CHECK_SERVER = "ca.ishmeet.ioniq6prox.CHECK"
        private const val CHANNEL = "monitor"
        private const val ALERT_CHANNEL = "alerts"
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2

        private const val SETTLE_MS = 10_000L        // ignore tag hiccups right after parking
        private const val LINK_HOLD_MS = 5_000L      // link must stay down this long to count as "gone"
        private const val RSSI_HOLD_MS = 3_000L      // signal must stay weak this long
        private const val FALLBACK_MS = 45_000L      // tag never connected after parking → car-Bluetooth fallback
        private const val SCAN_MS = 45_000L          // active search for the ESP32 after parking
        private const val REARM_MS = 30_000L         // near the tag this long without driving → arm
        private const val AWAY_COUNT_RESET_MS = 30 * 60_000L
        private const val POWERCUT_WAIT_MS = 90_000L // after a power cut, wait this long for car Bluetooth
        private const val POWERCUT_MARGIN_DB = 12    // dropped while this much stronger than threshold → power cut
        private const val HEALTH_EVERY_MS = 15 * 60_000L
        private const val HYSTERESIS_DB = 8
        private const val MAX_AWAYS_PER_STOP = 5
        private const val HOME_DETECT_MS = 2 * 60_000L // after parking, look for "home" this long
        private const val LOCATE_RETRY_MS = 20_000L

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

        /** Sends an action to the running service (pause, resume, server check, test arm). */
        fun send(c: Context, action: String) {
            c.startService(Intent(c, ProximityService::class.java).setAction(action))
        }
    }

    private enum class Mode(val label: String) { UNKNOWN("Waiting for car"), DRIVING("Driving"), PARKED("Parked — watching"), AWAY("Away"), HOME("At home") }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var net: ExecutorService
    private var gatt: BluetoothGatt? = null
    private var gattIsDirect = false

    private var mode = Mode.UNKNOWN
    private var espConnected = false
    private var smoothed: Double? = null
    private var parkedAt = 0L
    private var linkDownSince = 0L
    private var dropWasPowerCut = false
    private var powerCutHandled = false
    private var belowSince = 0L
    private var aboveSince = 0L
    private var tagSeenThisStop = false
    private var fallbackSent = false
    private var awaysThisStop = 0
    private var armedByProximity = false   // armed by standing near the car, not by a drive ending
    private var lastAwayAt = 0L
    private var scanning = false
    private var scanGen = 0
    private var lastHealthAt = 0L
    private var lastNotifText = ""
    private var lastNotifAt = 0L
    private var homeCheckUntil = 0L      // >0: still working out whether we parked at home
    private var nextLocateAt = 0L
    private var locating = false
    @Volatile private var wifiName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        net = Executors.newSingleThreadExecutor()
        createChannels()
        try {
            startForeground(NOTIF_ID, buildNotif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            Status.log("Couldn't start in background: ${e.message}")
            stopSelf()
            return
        }
        Status.running = true
        registerAclReceiver()
        registerWifiWatch()
        connectTag(direct = false)
        checkCarAlreadyConnected()
        handler.post(tick)
        Status.log("Monitoring started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm("armed manually (test)")
            ACTION_PAUSE -> pause(120)
            ACTION_RESUME -> pause(0)
            ACTION_CHECK_SERVER -> checkServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try { unregisterReceiver(aclReceiver) } catch (_: Exception) {}
        try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifiCb) } catch (_: Exception) {}
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
        homeCheckUntil = 0
        stopScan()
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
        armedByProximity = false
        fallbackSent = false
        belowSince = 0
        aboveSince = 0
        dropWasPowerCut = false
        powerCutHandled = false
        tagSeenThisStop = espConnected
        linkDownSince = if (espConnected) 0 else now()
        setMode(Mode.PARKED, why)
        if (!espConnected) startScan()
        if (Home.enabled(prefs)) {
            homeCheckUntil = parkedAt + HOME_DETECT_MS
            nextLocateAt = parkedAt
            checkHome(parkedAt)
        } else homeCheckUntil = 0
    }

    // ------------------------------------------------------------------ home

    // Android tells us the Wi-Fi name as it joins (needs location permission, "all the time").
    private val wifiCb = object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            val ssid = Home.clean((caps.transportInfo as? WifiInfo)?.ssid) ?: return
            handler.post {
                if (ssid == wifiName) return@post
                wifiName = ssid
                if (homeCheckUntil > 0) checkHome(now())
            }
        }
        override fun onLost(n: Network) { handler.post { wifiName = null } }
    }

    private fun registerWifiWatch() {
        try {
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(req, wifiCb)
        } catch (e: Exception) { Status.log("Can't watch Wi-Fi: ${e.message}") }
    }

    private fun wifiNow(): String? = wifiName ?: Home.currentWifi(this)

    /** While the post-parking window is open: home Wi-Fi now? Otherwise ask for a location fix. */
    private fun checkHome(t: Long) {
        if (homeCheckUntil == 0L || mode == Mode.HOME || mode == Mode.DRIVING) return
        if (t > homeCheckUntil) {
            homeCheckUntil = 0
            Status.log("Not at home (no home Wi-Fi or location within 2 min of parking)")
            return
        }
        val wifi = wifiNow()
        if (Home.isHomeWifi(prefs, wifi)) { enterHome("on home Wi-Fi “$wifi”"); return }
        if (prefs.homeLat == null || locating || t < nextLocateAt) return
        if (!Home.hasLocation(this)) { nextLocateAt = Long.MAX_VALUE; Status.log("Home mode: no location permission — using Wi-Fi only"); return }
        locating = true
        Home.locate(this) { loc ->
            locating = false
            if (homeCheckUntil == 0L || mode == Mode.HOME || mode == Mode.DRIVING) return@locate
            if (loc == null) { nextLocateAt = now() + LOCATE_RETRY_MS; return@locate }
            val d = Home.distance(prefs, loc) ?: return@locate
            if (Home.inside(prefs, loc)) enterHome("parked ${Home.describe(d)} from home")
            else if (loc.accuracy > 300) nextLocateAt = now() + LOCATE_RETRY_MS   // too vague to rule home out
            else { nextLocateAt = Long.MAX_VALUE; Status.log("Parked ${Home.describe(d)} from home — normal walk-away") }
        }
    }

    /** Parked at home: one lock (unless one was already sent this stop), then quiet until the next drive. */
    private fun enterHome(why: String) {
        if (mode == Mode.HOME) return
        homeCheckUntil = 0
        stopScan()
        val alreadySent = awaysThisStop > 0
        awaysThisStop++
        belowSince = 0
        aboveSince = 0
        if (alreadySent) {
            setMode(Mode.HOME, "$why — already locked once, staying quiet until the next drive")
            return
        }
        val left = (prefs.homeDelaySec - (now() - parkedAt) / 1000).coerceIn(0, 600)
        setMode(Mode.HOME, "$why — locking once in ${left}s, then quiet until the next drive")
        lastAwayAt = now()
        send("/v1/autolock/away", """{"source":"home","graceSeconds":$left}""", "away")
    }

    // ------------------------------------------------------------------ ESP32 tag

    /**
     * direct=false: background "auto" connection — Android reconnects whenever the tag is around
     * (slow but battery-friendly). direct=true: immediate connection to a tag we just saw in a scan.
     */
    private fun connectTag(direct: Boolean) {
        val addr = prefs.espAddress ?: run { Status.log("No ESP32 chosen yet"); return }
        val dev = try { adapter()?.getRemoteDevice(addr) } catch (_: IllegalArgumentException) { null } ?: return
        try {
            gatt?.close()
            gattIsDirect = direct
            gatt = dev.connectGatt(this, !direct, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (!direct) Status.log("Watching ESP32 $addr")
        } catch (e: SecurityException) {
            Status.log("No Bluetooth permission: ${e.message}")
        }
    }

    // Active search right after parking: the ESP32 has only just booted, and Android's background
    // reconnect can take a long time to notice it. A filtered scan finds it within seconds.
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            if (r.device.address != prefs.espAddress) return
            handler.post {
                if (!scanning) return@post
                stopScan()
                if (!espConnected) {
                    Status.log("Found ESP32 (${r.rssi} dBm) — connecting")
                    connectTag(direct = true)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post { scanning = false; Status.log("ESP32 scan failed ($errorCode)") }
        }
    }

    private fun startScan() {
        if (scanning || prefs.espAddress == null) return
        val scanner = adapter()?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCb)
            scanning = true
            val gen = ++scanGen
            handler.postDelayed({ if (scanning && gen == scanGen) { stopScan(); Status.log("ESP32 not found by scan") } }, SCAN_MS)
        } catch (e: Exception) {
            Status.log("Couldn't scan: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post   // a connection object we've already replaced
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    espConnected = true
                    linkDownSince = 0
                    dropWasPowerCut = false
                    if (mode == Mode.PARKED) tagSeenThisStop = true
                    Status.espConnected = true
                    stopScan()
                    Status.log("ESP32 connected")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val was = espConnected
                    val last = smoothed
                    espConnected = false
                    smoothed = null
                    linkDownSince = now()
                    // Still strong when it vanished → it lost power (car started), not you walking away.
                    dropWasPowerCut = was && last != null && last > prefs.awayRssi + POWERCUT_MARGIN_DB
                    powerCutHandled = false
                    Status.espConnected = false
                    Status.rssi = null
                    Status.rawRssi = null
                    if (was) Status.log("ESP32 disconnected" + (if (dropWasPowerCut) " at full signal (lost power?)" else "") + " (status $status)")
                    // Go back to the battery-friendly background connection.
                    if (gattIsDirect || status == 133) handler.postDelayed({ connectTag(direct = false) }, 1_000)
                    else try { g.connect() } catch (_: SecurityException) {}
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
        if (t - lastHealthAt > HEALTH_EVERY_MS) { lastHealthAt = t; checkServer() }
        val threshold = prefs.awayRssi
        val s = smoothed
        if (homeCheckUntil > 0) checkHome(t)

        when (mode) {
            Mode.PARKED -> {
                if (!tagSeenThisStop && !fallbackSent && t - parkedAt > FALLBACK_MS) {
                    fallbackSent = true
                    awaysThisStop++
                    stopScan()
                    setMode(Mode.AWAY, "ESP32 not reachable — using car Bluetooth only")
                    send("/v1/autolock/away", """{"source":"car-bluetooth"}""", "away")
                    return
                }
                val settled = t - parkedAt > SETTLE_MS
                if (!espConnected) {
                    belowSince = 0
                    if (tagSeenThisStop && settled && linkDownSince > 0) {
                        if (dropWasPowerCut) {
                            // Most likely you started the car. Wait for its Bluetooth; if it never
                            // comes, let the server decide with check-first (skips if the car is on).
                            if (!powerCutHandled && t - linkDownSince > POWERCUT_WAIT_MS) {
                                powerCutHandled = true
                                awaysThisStop++
                                setMode(Mode.AWAY, "ESP32 lost power and car Bluetooth never came back")
                                send("/v1/autolock/away", """{"source":"esp32-power-lost","checkFirst":true}""", "away")
                            }
                        } else if (t - linkDownSince > LINK_HOLD_MS) {
                            walkedAway("ESP32 out of range")
                        }
                    }
                } else if (s != null && s < threshold) {
                    if (belowSince == 0L) belowSince = t
                    if (settled && t - belowSince > RSSI_HOLD_MS) walkedAway("signal ${s.toInt()} dBm (threshold $threshold)")
                } else {
                    belowSince = 0
                }
            }
            // Not driving, but you're right by the car (e.g. you opened it to grab something,
            // or came back after walking away): after REARM_MS close to the tag, arm. The next
            // walk-away asks the server to check first, so an already-locked car is left alone.
            // HOME too: if you're near the car somewhere that isn't home (a drive the phone missed),
            // normal walk-away takes over again.
            Mode.AWAY, Mode.UNKNOWN, Mode.HOME -> {
                if (espConnected && s != null && s > threshold + HYSTERESIS_DB) {
                    if (aboveSince == 0L) aboveSince = t
                    if (t - aboveSince > REARM_MS && Home.atHomeNow(this, prefs, wifiName)) {
                        // At home the tag is often in range from the house: never re-arm there.
                        aboveSince = 0
                        if (mode != Mode.HOME) setMode(Mode.HOME, "near the car at home — staying quiet until the next drive")
                    } else if (t - aboveSince > REARM_MS) {
                        val why = if (mode == Mode.AWAY) "back near the car — re-armed" else "near the car (no drive) — armed"
                        aboveSince = 0
                        belowSince = 0
                        tagSeenThisStop = true
                        dropWasPowerCut = false
                        armedByProximity = true
                        if (t - lastAwayAt > AWAY_COUNT_RESET_MS) awaysThisStop = 0
                        parkedAt = t - SETTLE_MS // already settled
                        setMode(Mode.PARKED, why)
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
        lastAwayAt = now()
        // First walk-away after a drive: lock immediately (the tag already proves you left).
        // Otherwise (armed by proximity, or a repeat in the same stop): have the server check
        // first, so an already-locked car gets no redundant lock.
        val body = if (awaysThisStop == 1 && !armedByProximity) """{"source":"esp32","graceSeconds":0}"""
                   else """{"source":"esp32","graceSeconds":0,"checkFirst":true}"""
        send("/v1/autolock/away", body, "away")
    }

    // ------------------------------------------------------------------ server calls

    private fun send(path: String, json: String, label: String) {
        if (!prefs.configured) { Status.log("Server not set up — skipped $label"); return }
        val critical = label == "away"
        net.execute {
            var delay = 5_000L
            var alerted = false
            for (attempt in 1..12) {
                try {
                    val r = Api.request(prefs, "POST", path, json)
                    Status.log("$label → HTTP ${r.code}")
                    if (r.code in 200..299) {
                        serverOk()
                        if (alerted) handler.post { clearAlert() }
                        return@execute
                    }
                    if (r.code == 401) { serverProblem("server rejected the phone token"); return@execute }
                    if (r.code in 400..499 && r.code != 429) return@execute  // our problem; retrying won't help
                    serverProblem("server error HTTP ${r.code}")
                } catch (e: Exception) {
                    serverProblem("can't reach the server (${e.javaClass.simpleName})")
                    Status.log("$label failed (${e.javaClass.simpleName}); retry in ${delay / 1000}s")
                }
                if (critical && !alerted) {
                    alerted = true
                    handler.post { alert("Auto-lock can't reach the server", "Still retrying. Lock the car yourself to be safe.") }
                }
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return@execute }
                delay = (delay * 2).coerceAtMost(60_000)
            }
            Status.log("$label gave up after 12 tries")
            if (critical) handler.post { alert("Auto-lock FAILED", "Couldn't reach the server. Check that the car is locked.") }
        }
    }

    /** Confirms the server and token work, and picks up the pause state. */
    private fun checkServer() {
        if (!prefs.configured) return
        net.execute {
            val before = Status.serverProblem
            Api.check(prefs)
            if (before != Status.serverProblem) Status.log(Status.serverProblem?.let { "Server problem: $it" } ?: "Server reachable")
            handler.post { refreshNotif(force = true) }
        }
    }

    private fun pause(minutes: Int) {
        if (!prefs.configured) { Status.log("Server not set up — can't pause"); return }
        net.execute {
            Status.log(Api.pause(prefs, minutes))
            handler.post { refreshNotif(force = true) }
        }
    }

    private fun serverOk() {
        if (Status.serverProblem != null) Status.log("Server reachable again")
        Status.serverProblem = null
    }

    private fun serverProblem(what: String) {
        if (Status.serverProblem != what) Status.log("Server problem: $what")
        Status.serverProblem = what
    }

    // ------------------------------------------------------------------ notifications

    private fun setMode(m: Mode, why: String) {
        mode = m
        Status.mode = m.label
        Status.log("${m.label}: $why")
        refreshNotif(force = true)
    }

    private fun describe(): String {
        val tag = if (espConnected) "ESP32 ${Status.rssi ?: "…"} dBm" else "ESP32 not connected"
        val extra = when {
            Status.serverProblem != null -> " · ⚠ ${Status.serverProblem}"
            Status.pausedUntil != null -> " · paused"
            else -> ""
        }
        return "${mode.label} · $tag$extra"
    }

    private fun refreshNotif(force: Boolean = false) {
        val text = describe()
        if (!force && (text == lastNotifText || now() - lastNotifAt < 10_000)) return
        lastNotifText = text
        lastNotifAt = now()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text))
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Proximity monitor", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows that walk-away auto-lock is running"
        })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Auto-lock problems", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the car might not have locked"
        })
    }

    private fun buildNotif(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = Status.pausedUntil != null
        val action = Intent(this, ProximityService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE)
        val actionPi = PendingIntent.getService(this, 1, action, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("IONIQ 6 auto-lock")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause 2 h", actionPi).build())
            .build()
    }

    private fun alert(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, n)
    }

    private fun clearAlert() {
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)
    }

    private fun now() = System.currentTimeMillis()
}
