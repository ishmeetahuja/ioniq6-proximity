package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** One scrolling settings/status screen, built in code (no layout files). */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var carView: TextView
    private lateinit var espView: TextView
    private lateinit var urlEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var thrEdit: EditText
    private lateinit var runSwitch: Switch
    private lateinit var pauseView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
        if (prefs.configured) Thread { Api.check(prefs) }.start()   // fresh server + pause state
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(16), bars.top + dp(12), dp(16), bars.bottom + dp(12))
            insets
        }
        setContentView(scroll)

        title("IONIQ 6 auto-lock")
        statusView = text("", 15f, bold = true)

        runSwitch = Switch(this).apply {
            text = "Monitoring"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> if (on) startMonitoring() else stopMonitoring() }
        }
        root.addView(runSwitch)

        pauseView = text("", 14f)
        row(button("Pause 2 h") { doPause(120) }, button("Resume") { doPause(0) })

        section("1 · Server")
        urlEdit = edit("https://car.example.com", prefs.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        tokenEdit = edit("PHONE_TOKEN", prefs.token, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        row(button("Save") { saveServer() }, button("Test") { testServer() })

        section("2 · Your car's Bluetooth")
        carView = text("")
        button("Choose car") { chooseCar() }

        section("3 · ESP32 in the car")
        espView = text("")
        button("Find ESP32") { findEsp() }

        section("4 · Walk-away threshold")
        text("Weaker than this (dBm) counts as walked away. Stand where you want it to trigger and tap \"Use current signal\".", 13f)
        thrEdit = edit("-85", prefs.awayRssi.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        row(button("Save") { saveThreshold() }, button("Use current signal") { useCurrentSignal() })

        section("Extras")
        button("Let it run in the background (battery)") { askBatteryExemption() }
        button("Test: arm as if I just parked") {
            if (Status.running) startService(Intent(this, ProximityService::class.java).setAction(ProximityService.ACTION_ARM))
            else toast("Turn Monitoring on first")
        }

        section("Log")
        logView = text("", 12f).apply { typeface = Typeface.MONOSPACE }
        render()
    }

    private fun title(t: String) = text(t, 22f, bold = true)

    private fun section(t: String) {
        text(t, 17f, bold = true).setPadding(0, dp(20), 0, dp(4))
    }

    private fun text(t: String, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            root.addView(this)
        }

    private fun edit(hint: String, value: String, type: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            isSingleLine = true
            root.addView(this)
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            root.addView(this)
        }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            root.removeView(v)
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(r)
    }

    private fun render() {
        val sig = Status.rssi?.let { "$it dBm (raw ${Status.rawRssi})" } ?: "—"
        val car = when (Status.carConnected) { true -> "connected"; false -> "disconnected"; null -> "unknown" }
        val server = Status.serverProblem?.let { "⚠ $it" } ?: "OK"
        statusView.text = "${Status.mode}\nESP32: ${if (Status.espConnected) "connected · $sig" else "not connected"}\nCar Bluetooth: $car\nServer: $server"
        pauseView.text = when {
            !Status.autolockEnabled -> "Auto-lock is turned off on the server"
            Status.pausedUntil != null -> "Auto-lock paused until ${localTime(Status.pausedUntil!!)}"
            else -> "Auto-lock active"
        }
        if (runSwitch.isChecked != Status.running) {
            runSwitch.setOnCheckedChangeListener(null)
            runSwitch.isChecked = Status.running
            runSwitch.setOnCheckedChangeListener { _, on -> if (on) startMonitoring() else stopMonitoring() }
        }
        carView.text = prefs.carName?.let { "$it (${prefs.carAddress})" } ?: "Not chosen"
        espView.text = prefs.espAddress ?: "Not chosen"
        logView.text = Status.logText().ifEmpty { "(nothing yet)" }
    }

    private fun localTime(iso: String): String = try {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
    } catch (_: Exception) { iso }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ actions

    private fun startMonitoring() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); runSwitch.isChecked = false; return }
        if (prefs.espAddress == null || prefs.carAddress == null) toast("Choose your car and the ESP32 first")
        prefs.enabled = true
        ProximityService.start(this)
    }

    private fun stopMonitoring() {
        prefs.enabled = false
        ProximityService.stop(this)
    }

    private fun restartIfRunning() {
        if (Status.running) {
            ProximityService.stop(this)
            handler.postDelayed({ ProximityService.start(this) }, 500)
        }
    }

    private fun saveServer() {
        val url = urlEdit.text.toString().trim()
        if (!url.startsWith("https://")) { toast("The server address must start with https://"); return }
        prefs.serverUrl = url
        prefs.token = tokenEdit.text.toString()
        toast("Saved")
    }

    private fun testServer() {
        saveServer()
        if (!prefs.configured) return
        Thread {
            val msg = Api.check(prefs)
            Status.log(msg)
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun doPause(minutes: Int) {
        if (!prefs.configured) { toast("Set up the server first"); return }
        Thread {
            val msg = Api.pause(prefs, minutes)
            Status.log(msg)
            runOnUiThread { toast(msg); render() }
        }.start()
    }

    private fun chooseCar() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            val devices = adapter.bondedDevices.toList().sortedBy { it.name ?: "" }
            if (devices.isEmpty()) { toast("No paired Bluetooth devices. Pair the phone with the car first."); return }
            val names = devices.map { "${it.name ?: "Unnamed"}  (${it.address})" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Which one is your car?")
                .setItems(names) { _, i ->
                    prefs.carAddress = devices[i].address
                    prefs.carName = devices[i].name ?: devices[i].address
                    restartIfRunning()
                    render()
                }
                .show()
        } catch (e: SecurityException) {
            askPermissions()
        }
    }

    private fun findEsp() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); return }
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            ?: run { toast("Turn Bluetooth on"); return }
        val found = LinkedHashMap<String, Int>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { found[r.device.address] = r.rssi }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, cb)
        } catch (e: SecurityException) { askPermissions(); return }
        toast("Looking for the ESP32 for 8 seconds…")
        handler.postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            if (found.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("No ESP32 found")
                    .setMessage("Make sure it's powered (LED blinks every 3 s) and close by. If this phone is already connected to it, it stops advertising — turn Monitoring off and try again.")
                    .setPositiveButton("OK", null)
                    .show()
                return@postDelayed
            }
            val list = found.entries.sortedByDescending { it.value }
            val names = list.map { "IONIQ6-PROX  ${it.key}  (${it.value} dBm)" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Pick your ESP32 (strongest first)")
                .setItems(names) { _, i ->
                    prefs.espAddress = list[i].key
                    Status.log("ESP32 set to ${list[i].key}")
                    restartIfRunning()
                    render()
                }
                .show()
        }, 8_000)
    }

    private fun saveThreshold() {
        val v = thrEdit.text.toString().trim().toIntOrNull()
        if (v == null || v > -30 || v < -110) { toast("Use a number between -110 and -30"); return }
        prefs.awayRssi = v
        toast("Threshold $v dBm saved")
    }

    private fun useCurrentSignal() {
        val r = Status.rssi ?: run { toast("No live signal — is Monitoring on and the ESP32 connected?"); return }
        thrEdit.setText(r.toString())
        saveThreshold()
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already allowed to run in the background"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
