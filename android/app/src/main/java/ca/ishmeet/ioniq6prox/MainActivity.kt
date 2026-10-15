package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ca.ishmeet.ioniq6prox.Sheets.sheet
import ca.ishmeet.ioniq6prox.Ui.card
import ca.ishmeet.ioniq6prox.Ui.chip
import ca.ishmeet.ioniq6prox.Ui.darkWindow
import ca.ishmeet.ioniq6prox.Ui.dp
import ca.ishmeet.ioniq6prox.Ui.dpf
import ca.ishmeet.ioniq6prox.Ui.pillButton
import ca.ishmeet.ioniq6prox.Ui.pressable
import ca.ishmeet.ioniq6prox.Ui.row
import ca.ishmeet.ioniq6prox.Ui.setChip
import ca.ishmeet.ioniq6prox.Ui.text
import java.util.concurrent.Executors

/** Home: the car, its state, the controls, and the walk-away lock panel. Setup lives in Settings. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var bar: Sheets.MessageBar

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var art: CarArtView
    private lateinit var stateWord: TextView
    private lateinit var meta: TextView
    private lateinit var updated: TextView
    private lateinit var chips: LinearLayout
    private lateinit var autoChip: TextView
    private lateinit var serverChip: TextView
    private lateinit var openChip: TextView
    private lateinit var lockTile: Tile
    private lateinit var unlockTile: Tile
    private lateinit var climateTile: Tile
    private lateinit var chargeTile: Tile
    private lateinit var findTile: Tile
    private lateinit var runSwitch: Switch
    private lateinit var modeWord: TextView
    private lateinit var modeWhy: TextView
    private lateinit var meter: SignalMeterView
    private lateinit var pauseBtn: TextView

    private var car: CarStatus? = null
    private var carError: String? = null
    private var busy = false            // one car command at a time
    private var lastStatusAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        darkWindow()
        buildUi()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        art.photo = CarPhoto.load(this)
        handler.post(ticker)
        if (prefs.configured) {
            io.execute { Api.check(prefs) }
            if (System.currentTimeMillis() - lastStatusAt > 60_000) loadStatus(live = false)
        }
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() { renderAutoLock(); handler.postDelayed(this, 1_000) }
    }

    // ------------------------------------------------------------------ layout

    private inner class Tile(icon: Icon.Kind, iconColor: Int, label: String, big: Boolean, onClick: () -> Unit) {
        val icon = Icon(icon, iconColor, dp(24), knob = Ui.SURFACE)
        val text = text(label, if (big) 17f else 13f, Ui.TEXT, if (big) Ui.semibold else Ui.medium)
        val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = if (big) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (big) Gravity.CENTER_VERTICAL else Gravity.CENTER
            background = pressable(Ui.SURFACE, dpf(if (big) 24f else 20f))
            minimumHeight = dp(if (big) 76 else 84)
            setPadding(dp(20), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(View(context).apply { background = this@Tile.icon }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(this@Tile.text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (big) marginStart = dp(12) else topMargin = dp(8)
            })
        }
        private val label0 = label
        fun busy(label: String?) {
            text.text = label ?: label0
            view.alpha = if (label != null) 0.6f else 1f
        }
    }

    private fun buildUi() {
        val host = FrameLayout(this)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        host.addView(scroll)
        bar = Sheets.MessageBar(host)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(dp(16), b.top + dp(12), dp(16), b.bottom + dp(24))
            insets
        }
        setContentView(host)

        // top bar
        title = text("IONIQ 6", 24f, Ui.TEXT, Ui.semibold)
        subtitle = text("", 14f, Ui.MUTED)
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle, Ui.lp(top = dp(4)))
        }
        val settingsBtn = FrameLayout(this).apply {
            background = pressable(Ui.SURFACE, dpf(999f))
            contentDescription = "Settings"
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            addView(View(context).apply { background = Icon(Icon.Kind.SETTINGS, Ui.TEXT, dp(22), knob = Ui.SURFACE) },
                FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(settingsBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        })

        // setup prompt (only until the server is configured)
        setupCard = card().apply {
            addView(text("Finish setup", 17f, Ui.TEXT, Ui.semibold))
            addView(text("Add your server address and phone token so the app can reach your car.", 14f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(top = dp(6)))
            addView(pillButton("Open settings", Ui.GLACIER, Ui.ON_ACCENT) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }, Ui.lp(top = dp(14)))
        }
        col.addView(setupCard, Ui.lp(top = dp(16)))

        // hero
        art = CarArtView(this)
        col.addView(art, Ui.lp(top = dp(18)))
        stateWord = text("…", 46f, Ui.MUTED, Ui.display).apply { letterSpacing = -0.01f }
        meta = text("", 16f, Ui.MUTED)
        updated = text("", 13f, Ui.FAINT).apply {
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(Icon(Icon.Kind.REFRESH, Ui.FAINT, dp(16)), null, null, null)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = pressable(0, dpf(999f))
            isClickable = true
            setOnClickListener { askLiveRefresh() }
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(4), 0, 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(stateWord)
                addView(meta, Ui.lp(top = dp(6)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(updated)
        }, Ui.lp(top = dp(2)))

        // chips: only things worth noticing
        autoChip = chip(); serverChip = chip(); openChip = chip()
        chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoChip)
            addView(openChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(serverChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, Ui.lp(top = dp(16)))

        // controls
        lockTile = Tile(Icon.Kind.LOCK, Ui.GLACIER, "Lock", true) { doLock() }
        unlockTile = Tile(Icon.Kind.UNLOCK, Ui.AMBER, "Unlock", true) { confirmUnlock() }
        col.addView(row(dp(10), lockTile.view, unlockTile.view), Ui.lp(top = dp(18)))
        climateTile = Tile(Icon.Kind.CLIMATE, Ui.TEXT, "Climate", false) { climateSheet() }
        chargeTile = Tile(Icon.Kind.CHARGE, Ui.TEXT, "Charging", false) { chargeSheet() }
        findTile = Tile(Icon.Kind.FIND, Ui.TEXT, "Find car", false) { findCar() }
        col.addView(row(dp(10), climateTile.view, chargeTile.view, findTile.view), Ui.lp(top = dp(10)))

        // walk-away lock panel
        runSwitch = Switch(this).apply {
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.ON_ACCENT, Ui.MUTED))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Ui.GLACIER, Ui.RAISED))
            contentDescription = "Walk-away lock"
            setOnCheckedChangeListener { _, on -> if (on) startMonitoring() else stopMonitoring() }
        }
        modeWord = text("", 28f, Ui.TEXT, Ui.display)
        modeWhy = text("", 14f, Ui.MUTED).apply { setLineSpacing(0f, 1.2f) }
        meter = SignalMeterView(this)
        pauseBtn = pillButton("Pause for 2 hours") { togglePause() }
        col.addView(card().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Walk-away lock", 17f, Ui.TEXT, Ui.semibold), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(runSwitch)
            })
            addView(modeWord, Ui.lp(top = dp(12)))
            addView(modeWhy, Ui.lp(top = dp(6)))
            addView(meter, Ui.lp(top = dp(16)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("Far", 12f, Ui.FAINT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text("Near", 12f, Ui.FAINT))
            }, Ui.lp(top = dp(6)))
            addView(pauseBtn, Ui.lp(top = dp(16)))
        }, Ui.lp(top = dp(18)))

        render()
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        setupCard.visibility = if (prefs.configured) View.GONE else View.VISIBLE
        renderCar()
        renderAutoLock()
    }

    private fun renderCar() {
        val c = car
        val v = c?.vehicle
        val m = v?.let { Regex("^(.*?)\\s*\"(.+)\"$").find(it) }
        if (m != null) { title.text = m.groupValues[2]; subtitle.text = m.groupValues[1] }
        else { title.text = "IONIQ 6"; subtitle.text = v ?: (prefs.carName ?: "") }
        subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val (word, color) = when {
            c == null -> (if (carError != null) "No contact" else if (prefs.configured) "Checking…" else "Not set up") to Ui.FAINT
            c.carOn -> "Car on" to Ui.GLACIER
            c.locked == true -> "Locked" to Ui.GLACIER
            c.locked == false -> "Unlocked" to Ui.AMBER
            else -> "Unknown" to Ui.FAINT
        }
        stateWord.text = word
        stateWord.setTextColor(if (color == Ui.FAINT) Ui.MUTED else color)
        art.glowColor = color

        meta.text = when {
            c == null -> carError ?: ""
            else -> SpannableStringBuilder().apply {
                fun part(s: String, col: Int) {
                    if (isNotEmpty()) append("    ")
                    val st = length; append(s); setSpan(ForegroundColorSpan(col), st, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                c.battery?.let { part("$it%", Ui.TEXT) }
                c.rangeKm?.let { part("$it km", Ui.MUTED) }
                when {
                    c.charging -> part("Charging" + (c.chargeMinutesLeft?.let { ", ${fmtMinutes(it)} left" } ?: ""), Ui.GLACIER)
                    c.pluggedIn -> part("Plugged in", Ui.MUTED)
                }
                if (c.climateOn) part("Climate ${c.climateSetC?.let { fmtTemp(it) } ?: "on"}", Ui.MUTED)
            }
        }
        updated.text = c?.updated?.let { fmtUpdated(it) } ?: "Refresh"

        openChip.visibility = if (c != null && c.open.isNotEmpty()) View.VISIBLE else View.GONE
        if (c != null && c.open.isNotEmpty()) openChip.setChip(c.open.joinToString(", ").replaceFirstChar { it.uppercase() } + " open", Ui.AMBER)
    }

    private fun renderAutoLock() {
        if (!::runSwitch.isInitialized) return
        if (runSwitch.isChecked != Status.running) {
            runSwitch.setOnCheckedChangeListener(null)
            runSwitch.isChecked = Status.running
            runSwitch.setOnCheckedChangeListener { _, on -> if (on) startMonitoring() else stopMonitoring() }
        }
        val paused = Status.pausedUntil
        val thr = prefs.awayRssi
        val (word, why) = when {
            !Status.running -> "Off" to "Turn it on to lock the car when you walk away."
            paused != null -> "Paused" to "Back on at ${Ui.localTime(paused)}."
            !Status.autolockEnabled -> "Off on the server" to "Turn it back on in the server settings."
            Status.mode.startsWith("Driving") -> "Driving" to "Watches again when you park."
            Status.mode.startsWith("Parked") -> "Parked, watching" to "Locks when the tag's signal drops below $thr dBm."
            Status.mode.startsWith("Away") -> "Away" to "You walked off; the car was told to lock."
            Status.mode.startsWith("At home") -> "At home" to "Locks once after you park at home, then stays quiet until your next drive."
            else -> "Waiting for the car" to "Arms when you finish a drive, or after 30 s right by the car."
        }
        modeWord.text = word
        modeWord.setTextColor(if (paused != null) Ui.AMBER else Ui.TEXT)
        modeWhy.text = why
        meter.threshold = thr
        meter.rssi = if (Status.espConnected) Status.rssi else null
        pauseBtn.text = if (paused != null) "Resume now" else "Pause for 2 hours"
        pauseBtn.setTextColor(if (paused != null) Ui.AMBER else Ui.TEXT)

        when {
            !Status.running -> autoChip.setChip("Walk-away lock off", Ui.MUTED)
            paused != null -> autoChip.setChip("Paused until ${Ui.localTime(paused)}", Ui.AMBER)
            else -> autoChip.setChip("Walk-away lock on", Ui.GLACIER)
        }
        val problem = Status.serverProblem
        serverChip.visibility = if (problem != null) View.VISIBLE else View.GONE
        if (problem != null) serverChip.setChip(problem.replaceFirstChar { it.uppercase() }, Ui.CORAL)
        setupCard.visibility = if (prefs.configured) View.GONE else View.VISIBLE
    }

    private fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    private fun fmtTemp(t: Double) = if (t % 1.0 == 0.0) "${t.toInt()}°" else "$t°"
    private fun fmtUpdated(s: String): String = try {
        val t = java.time.LocalDateTime.parse(s.replace(' ', 'T'))
        val today = java.time.LocalDate.now()
        val f = if (t.toLocalDate() == today) "h:mm a" else "MMM d, h:mm a"
        "Updated " + t.format(java.time.format.DateTimeFormatter.ofPattern(f))
    } catch (_: Exception) { "Updated $s" }

    // ------------------------------------------------------------------ car

    private fun loadStatus(live: Boolean) {
        if (!prefs.configured) return
        if (live) updated.text = "Asking the car…"
        io.execute {
            try {
                val s = CarApi.status(prefs, live)
                runOnUiThread { car = s; carError = null; lastStatusAt = System.currentTimeMillis(); renderCar() }
            } catch (e: CarApi.Failure) {
                runOnUiThread { if (car == null) carError = e.message; renderCar(); if (live) bar.show(e.message ?: "", Ui.CORAL) }
            } catch (e: Exception) {
                runOnUiThread { carError = "Couldn't read the car's status"; renderCar() }
            }
        }
    }

    private fun askLiveRefresh() {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        loadStatus(live = true)
    }

    /** Runs a car command off the main thread, showing progress on its tile. */
    private fun runCommand(tile: Tile, doing: String, done: String, needsAdmin: Boolean, after: ((Boolean) -> Unit)? = null, call: () -> String) {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        if (needsAdmin && !prefs.hasAdmin) { bar.show("This needs the admin token. Add it in Settings.", Ui.AMBER); return }
        if (busy) { bar.show("Wait for the current command to finish."); return }
        busy = true
        tile.busy(doing)
        io.execute {
            val result = try { call(); null } catch (e: CarApi.Failure) { e.message } catch (e: Exception) { "Something went wrong: ${e.message}" }
            runOnUiThread {
                busy = false
                tile.busy(null)
                if (result == null) bar.show(done, Ui.GLACIER) else bar.show(result, Ui.CORAL, 5_000)
                after?.invoke(result == null)
            }
        }
    }

    private fun doLock() = runCommand(lockTile, "Locking…", "Locked", needsAdmin = false, after = { ok ->
        if (ok) { car = car?.copy(locked = true); renderCar() }
    }) { CarApi.command(prefs, "POST", "/v1/lock", needsAdmin = false) }

    private fun confirmUnlock() {
        if (!prefs.hasAdmin) { bar.show("Unlocking needs the admin token. Add it in Settings.", Ui.AMBER); return }
        sheet("Unlock the car?", "Anyone near it will be able to open the doors.") { d ->
            val alsoPause = CheckBox(context).apply {
                text = "Also pause walk-away lock for 2 hours"
                setTextColor(Ui.TEXT)
                textSize = 15f
                buttonTintList = ColorStateList.valueOf(Ui.AMBER)
                isChecked = Status.running && Status.pausedUntil == null
            }
            addView(alsoPause, Ui.lp(top = dp(14)))
            addView(row(dp(10),
                pillButton("Cancel") { d.dismiss() },
                pillButton("Unlock", Ui.AMBER, Ui.ON_ACCENT) {
                    d.dismiss()
                    val pause = alsoPause.isChecked
                    runCommand(unlockTile, "Unlocking…", "Unlocked", needsAdmin = true, after = { ok ->
                        if (ok) { car = car?.copy(locked = false); renderCar() }
                    }) {
                        if (pause) Api.pause(prefs, 120)
                        CarApi.command(prefs, "POST", "/v1/unlock")
                    }
                }), Ui.lp(top = dp(20)))
        }
    }

    private fun climateSheet() {
        if (!prefs.hasAdmin) { bar.show("Climate needs the admin token. Add it in Settings.", Ui.AMBER); return }
        var temp = prefs.climateTemp.toDouble()
        var minutes = prefs.climateMinutes
        val on = car?.climateOn == true
        sheet("Climate", if (on) "It's running now." else "Heats or cools the cabin before you go.") { d ->
            val tempText = text(fmtTemp(temp), 56f, Ui.TEXT, Ui.displayLight).apply { gravity = Gravity.CENTER }
            fun step(delta: Double) { temp = (temp + delta).coerceIn(16.0, 30.0); tempText.text = fmtTemp(temp) }
            val minus = pillButton("−") { step(-0.5) }.apply { textSize = 24f }
            val plus = pillButton("+") { step(0.5) }.apply { textSize = 24f }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(tempText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(plus, LinearLayout.LayoutParams(dp(64), dp(64)))
            }, Ui.lp(top = dp(18)))
            addView(text("Run for", 14f, Ui.MUTED), Ui.lp(top = dp(18)))
            val opts = listOf(10, 15, 20, 30)
            val pills = opts.map { m -> pillButton("$m min") {} }
            fun paint() = pills.forEachIndexed { i, b ->
                val sel = opts[i] == minutes
                b.background = pressable(if (sel) Ui.alpha(Ui.GLACIER, 0.16f) else Ui.RAISED, dpf(999f), stroke = if (sel) dp(1) else 0, strokeColor = Ui.GLACIER)
                b.setTextColor(if (sel) Ui.GLACIER else Ui.TEXT)
            }
            pills.forEachIndexed { i, b -> b.setOnClickListener { minutes = opts[i]; paint() } }
            paint()
            addView(row(dp(8), *pills.toTypedArray()), Ui.lp(top = dp(8)))
            addView(row(dp(10),
                pillButton("Stop") {
                    d.dismiss()
                    runCommand(climateTile, "Stopping…", "Climate off", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(climateOn = false); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/climate/stop")
                    }
                },
                pillButton("Start", Ui.GLACIER, Ui.ON_ACCENT) {
                    d.dismiss()
                    prefs.climateTemp = temp.toFloat(); prefs.climateMinutes = minutes
                    val t = temp; val mins = minutes
                    runCommand(climateTile, "Starting…", "Climate on: ${fmtTemp(t)} for $mins min", needsAdmin = true, after = { ok ->
                        if (ok) { car = car?.copy(climateOn = true, climateSetC = t); renderCar() }
                    }) {
                        CarApi.command(prefs, "POST", "/v1/climate/start", """{"temp":$t,"duration":$mins}""")
                    }
                }), Ui.lp(top = dp(22)))
        }
    }

    private fun chargeSheet() {
        if (!prefs.hasAdmin) { bar.show("Charging controls need the admin token. Add it in Settings.", Ui.AMBER); return }
        val c = car
        val state = when {
            c == null -> "Charging state unknown."
            c.charging -> "Charging now" + (c.chargeMinutesLeft?.let { ", ${fmtMinutes(it)} left." } ?: ".")
            c.pluggedIn -> "Plugged in, not charging."
            else -> "Not plugged in."
        }
        sheet("Charging", state) { d ->
            addView(row(dp(10),
                pillButton("Stop charging") {
                    d.dismiss()
                    runCommand(chargeTile, "Stopping…", "Charging stopped", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(charging = false); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/charge/stop")
                    }
                },
                pillButton("Start charging", Ui.GLACIER, Ui.ON_ACCENT) {
                    d.dismiss()
                    runCommand(chargeTile, "Starting…", "Charging started", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(charging = true); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/charge/start")
                    }
                }), Ui.lp(top = dp(20)))
        }
    }

    private fun findCar() {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        if (!prefs.hasAdmin) { bar.show("Finding the car needs the admin token. Add it in Settings.", Ui.AMBER); return }
        if (busy) { bar.show("Wait for the current command to finish."); return }
        busy = true
        findTile.busy("Locating…")
        io.execute {
            val res = try { Result.success(CarApi.location(prefs)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                busy = false
                findTile.busy(null)
                res.onSuccess { (lat, lon) ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(My car)")))
                    } catch (_: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon")))
                    }
                }.onFailure { bar.show(it.message ?: "Couldn't find the car", Ui.CORAL) }
            }
        }
    }

    // ------------------------------------------------------------------ walk-away lock

    private fun startMonitoring() {
        if (!ProximityService.hasPermissions(this)) { askPermissions(); runSwitch.isChecked = false; return }
        if (prefs.espAddress == null || prefs.carAddress == null) bar.show("Choose your car and the ESP32 in Settings first.", Ui.AMBER)
        prefs.enabled = true
        ProximityService.start(this)
    }

    private fun stopMonitoring() {
        prefs.enabled = false
        ProximityService.stop(this)
    }

    private fun togglePause() {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        val resume = Status.pausedUntil != null
        io.execute {
            val msg = Api.pause(prefs, if (resume) 0 else 120)
            Status.log(msg)
            runOnUiThread { bar.show(msg, if (resume) Ui.GLACIER else Ui.AMBER); renderAutoLock() }
        }
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
