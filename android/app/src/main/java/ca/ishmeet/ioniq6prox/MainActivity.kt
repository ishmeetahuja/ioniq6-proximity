package ca.ishmeet.ioniq6prox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
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
    private lateinit var climateCard: Card
    private lateinit var vehicleCard: Card
    private lateinit var chargeCard: Card
    private lateinit var chargeBar: ChargeBarView
    private lateinit var findCard: Card
    private lateinit var runSwitch: Switch
    private lateinit var modeWord: TextView
    private lateinit var modeWhy: TextView
    private lateinit var meter: SignalMeterView
    private lateinit var pauseBtn: TextView

    private var car: CarStatus? = null
    private var carError: String? = null
    private var busy = false            // one car command at a time
    private var lastAttemptAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        car = CarApi.cached(prefs)   // show the last known state straight away
        darkWindow()
        buildUi()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        art.photo = CarPhoto.load(this)
        handler.post(ticker)
        if (prefs.configured) io.execute { Api.check(prefs) }
        maybeRefresh()
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
        override fun run() { renderAutoLock(); maybeRefresh(); handler.postDelayed(this, 1_000) }
    }

    /**
     * Asks the server for the car's status at most every statusEveryMin minutes (Settings), counted
     * from the last answer even across app restarts. 0 = only on Refresh (or once if we have nothing).
     */
    private fun maybeRefresh() {
        if (!prefs.configured) return
        val now = System.currentTimeMillis()
        val every = prefs.statusEveryMin
        if (every == 0 && car != null) return
        val gap = (if (every == 0) 1 else every) * 60_000L
        if (now - maxOf(prefs.lastStatusAt, lastAttemptAt) < gap) return
        lastAttemptAt = now
        loadStatus(live = false)
    }

    // ------------------------------------------------------------------ layout

    /** Anything that shows a command running inside itself, then confirms. */
    private interface Control {
        val view: View
        fun busy(label: String)
        fun done(ok: Boolean, label: String?)
    }

    private fun shape(r: Float) = Ui.rounded(Ui.SURFACE, r)
    private fun ripple(fill: android.graphics.drawable.GradientDrawable, r: Float) =
        RippleDrawable(ColorStateList.valueOf(Ui.alpha(Ui.TEXT, 0.14f)), fill, Ui.rounded(Color.WHITE, r))

    /** Big Lock / Unlock button: icon becomes a spinning ring while it runs, flashes white when done. */
    private inner class Tile(kind: Icon.Kind, private val iconColor: Int, private val label0: String, onClick: () -> Unit) : Control {
        private val fill = shape(dpf(24f))
        private val icon = Icon(kind, iconColor, dp(24), knob = Ui.SURFACE)
        private val iconView = View(this@MainActivity).apply { background = icon }
        private val ring = RingView(this@MainActivity, iconColor).apply { visibility = View.GONE }
        private val text = text(label0, 17f, Ui.TEXT, Ui.semibold)
        override val view: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(fill, dpf(24f))
            minimumHeight = dp(76)
            setPadding(dp(20), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(FrameLayout(context).apply {
                addView(iconView, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                addView(ring, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        override fun busy(label: String) {
            text.text = label
            iconView.visibility = View.INVISIBLE
            ring.start()
        }
        override fun done(ok: Boolean, label: String?) {
            ring.stop()
            iconView.visibility = View.VISIBLE
            text.text = if (ok && label != null) label else label0
            if (ok && label != null) handler.postDelayed({ text.text = label0 }, 1_600)
            Motion.result(view, fill, Ui.SURFACE, ok) { f, ink ->
                icon.setColor(Motion.blend(iconColor, ink, f))
                text.setTextColor(Motion.blend(Ui.TEXT, ink, f))
            }
        }
    }

    /** A status card (climate, vehicle, charging, find): title, detail, a big word; ring in the corner while busy. */
    private inner class Card(titleText: String, minH: Int, onClick: () -> Unit) : Control {
        private val fill = shape(dpf(24f))
        val title = text(titleText, 16f, Ui.TEXT, Ui.semibold)
        val sub = text("", 14f, Ui.MUTED).apply { setLineSpacing(0f, 1.15f) }
        val big = text("", 34f, Ui.TEXT, Ui.display)
        var bigColor = Ui.TEXT
            set(v) { field = v; big.setTextColor(v) }
        val body: LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            addView(title)
            addView(sub, Ui.lp(top = dp(5)))
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(big, Ui.lp(top = dp(14)))
        }
        private val ring = RingView(this@MainActivity, Ui.TEXT).apply { visibility = View.GONE }
        private var busyLabel: String? = null
        override val view: FrameLayout = FrameLayout(this@MainActivity).apply {
            background = ripple(fill, dpf(24f))
            minimumHeight = minH
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ring, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { topMargin = dp(16); marginEnd = dp(16) })
        }
        fun detail(t: CharSequence) { if (busyLabel == null) sub.text = t }
        override fun busy(label: String) { busyLabel = label; sub.text = label; ring.start() }
        override fun done(ok: Boolean, label: String?) {
            busyLabel = null
            ring.stop()
            renderCar()
            Motion.result(view, fill, Ui.SURFACE, ok) { f, ink ->
                title.setTextColor(Motion.blend(Ui.TEXT, ink, f))
                sub.setTextColor(Motion.blend(Ui.MUTED, ink, f))
                big.setTextColor(Motion.blend(bigColor, ink, f))
            }
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
        lockTile = Tile(Icon.Kind.LOCK, Ui.GLACIER, "Lock") { doLock() }
        unlockTile = Tile(Icon.Kind.UNLOCK, Ui.AMBER, "Unlock") { confirmUnlock() }
        col.addView(row(dp(10), lockTile.view, unlockTile.view), Ui.lp(top = dp(18)))

        // status cards
        climateCard = Card("Climate", dp(150)) { climateSheet() }
        vehicleCard = Card("Vehicle status", dp(150)) { vehicleSheet() }
        col.addView(row(dp(10), climateCard.view, vehicleCard.view), Ui.lp(top = dp(10)))
        chargeCard = Card("Charging", 0) { chargeSheet() }
        chargeBar = ChargeBarView(this)
        chargeCard.big.visibility = View.GONE
        chargeCard.body.addView(chargeBar, Ui.lp(top = dp(16)))
        col.addView(chargeCard.view, Ui.lp(top = dp(10)))
        findCard = Card("Find car", 0) { findCar() }
        findCard.big.visibility = View.GONE
        findCard.detail("Opens where the car is in Maps")
        col.addView(findCard.view, Ui.lp(top = dp(10)))

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

        renderCards(c)

        openChip.visibility = if (c != null && c.open.isNotEmpty()) View.VISIBLE else View.GONE
        if (c != null && c.open.isNotEmpty()) openChip.setChip(c.open.joinToString(", ").replaceFirstChar { it.uppercase() } + " open", Ui.AMBER)
    }

    private fun renderCards(c: CarStatus?) {
        if (!::climateCard.isInitialized) return
        // Climate
        val set = c?.climateSetC ?: prefs.climateTemp.toDouble()
        climateCard.detail("Set to ${fmtTemp(set)}C")
        climateCard.big.text = when { c == null -> "–"; c.climateOn -> "On"; else -> "Off" }
        climateCard.bigColor = if (c?.climateOn == true) Ui.GLACIER else Ui.TEXT
        // Vehicle health
        val issues = c?.let { issues(it) } ?: emptyList()
        vehicleCard.detail(when {
            c == null -> "Tires, 12 V battery, fluids"
            issues.isEmpty() -> "Tires · 12 V ${c.aux12v?.let { "$it%" } ?: "OK"} · fluids"
            else -> issues.joinToString(" · ")
        })
        vehicleCard.big.text = when { c == null -> "–"; issues.isEmpty() -> "Normal"; else -> "Check" }
        vehicleCard.bigColor = if (issues.isEmpty()) Ui.TEXT else Ui.AMBER
        // Charging
        chargeCard.title.text = when { c == null -> "Charging"; c.charging -> "Charging"; c.pluggedIn -> "Plugged in"; else -> "Unplugged" }
        chargeCard.detail(when {
            c == null -> "Charge level and limit"
            else -> listOfNotNull(
                c.battery?.let { "$it%" },
                c.chargeLimitAc?.let { "limit $it%" },
                if (c.charging) c.chargeMinutesLeft?.let { "${fmtMinutes(it)} left" } else null,
            ).joinToString(" · ").replaceFirstChar { it.uppercase() }
        })
        chargeBar.level = c?.battery
        chargeBar.limit = c?.chargeLimitAc
        chargeBar.charging = c?.charging == true
    }

    private fun issues(c: CarStatus): List<String> = buildList {
        if (c.tireWarning) add("Tire pressure low")
        c.aux12v?.let { if (it < 60) add("12 V battery $it%") }
        if (c.washerLow) add("Washer fluid low")
        if (c.open.isNotEmpty()) add(c.open.joinToString(", ").replaceFirstChar { it.uppercase() } + " open")
        if (c.windowsOpen) add("Window open")
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
            Status.mode.startsWith("At home") -> "At home" to "Locks once each time you've been at the car and then left it for ${prefs.homeDelaySec} s. Walking around the house doesn't count."
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
    /** Hyundai's times are UTC: "…Z" from current servers, or a bare "2026-10-13 13:15:00" from older ones. */
    private fun fmtUpdated(s: String): String = try {
        val utc = if (s.endsWith("Z")) java.time.Instant.parse(s)
                  else java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC)
        val t = utc.atZone(java.time.ZoneId.systemDefault())
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
                runOnUiThread { car = s; carError = null; renderCar() }
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

    /**
     * Runs a car command off the main thread. The button shows a ring while it runs, then flashes
     * white when the car confirms (or shakes coral if it didn't), with a haptic tick either way.
     */
    private fun runCommand(control: Control, doing: String, done: String, needsAdmin: Boolean, after: ((Boolean) -> Unit)? = null, call: () -> String) {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        if (needsAdmin && !prefs.hasAdmin) { bar.show("This needs the admin token. Add it in Settings.", Ui.AMBER); return }
        if (busy) { bar.show("Wait for the current command to finish."); return }
        busy = true
        control.view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        control.busy(doing)
        io.execute {
            val result = try { call(); null } catch (e: CarApi.Failure) { e.message } catch (e: Exception) { "Something went wrong: ${e.message}" }
            runOnUiThread {
                busy = false
                after?.invoke(result == null)
                control.done(result == null, done)
                if (result != null) bar.show(result, Ui.CORAL, 5_000)
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
                    runCommand(climateCard, "Stopping…", "Climate off", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(climateOn = false); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/climate/stop")
                    }
                },
                pillButton("Start", Ui.GLACIER, Ui.ON_ACCENT) {
                    d.dismiss()
                    prefs.climateTemp = temp.toFloat(); prefs.climateMinutes = minutes
                    val t = temp; val mins = minutes
                    runCommand(climateCard, "Starting…", "Climate on: ${fmtTemp(t)} for $mins min", needsAdmin = true, after = { ok ->
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
            // Charge limits (Cadillac-style target): 50–100 % in steps of 10, as the car accepts.
            var ac = c?.chargeLimitAc ?: 80
            var dc = c?.chargeLimitDc ?: 90
            fun limitRow(name: String, get: () -> Int, set: (Int) -> Unit): View {
                val value = text("${get()}%", 22f, Ui.TEXT, Ui.display).apply { gravity = Gravity.CENTER }
                fun step(d: Int) { set((get() + d).coerceIn(50, 100)); value.text = "${get()}%" }
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(name, 15f, Ui.TEXT, Ui.medium), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(pillButton("−") { step(-10) }.apply { textSize = 20f }, LinearLayout.LayoutParams(dp(52), dp(48)))
                    addView(value, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(pillButton("+") { step(10) }.apply { textSize = 20f }, LinearLayout.LayoutParams(dp(52), dp(48)))
                }
            }
            addView(text("Charge limit", 14f, Ui.MUTED), Ui.lp(top = dp(18)))
            addView(limitRow("Home charger (AC)", { ac }, { ac = it }), Ui.lp(top = dp(10)))
            addView(limitRow("Fast charger (DC)", { dc }, { dc = it }), Ui.lp(top = dp(10)))
            addView(pillButton("Save limits") {
                d.dismiss()
                val a = ac; val b = dc
                runCommand(chargeCard, "Setting limits…", "Limits set", needsAdmin = true, after = { ok ->
                    if (ok) { car = car?.copy(chargeLimitAc = a, chargeLimitDc = b); renderCar() }
                }) { CarApi.command(prefs, "PUT", "/v1/charge-limit", """{"ac":$a,"dc":$b}""") }
            }, Ui.lp(top = dp(14)))
            addView(row(dp(10),
                pillButton("Stop charging") {
                    d.dismiss()
                    runCommand(chargeCard, "Stopping…", "Charging stopped", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(charging = false); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/charge/stop")
                    }
                },
                pillButton("Start charging", Ui.GLACIER, Ui.ON_ACCENT) {
                    d.dismiss()
                    runCommand(chargeCard, "Starting…", "Charging started", needsAdmin = true, after = { ok -> if (ok) { car = car?.copy(charging = true); renderCar() } }) {
                        CarApi.command(prefs, "POST", "/v1/charge/start")
                    }
                }), Ui.lp(top = dp(20)))
        }
    }

    private fun vehicleSheet() {
        val c = car ?: run { bar.show("No status yet. Tap Refresh."); return }
        sheet("Vehicle status", c.updated?.let { fmtUpdated(it) }) { d ->
            fun line(name: String, value: String, warn: Boolean = false) = addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(name, 15f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(text(value, 15f, if (warn) Ui.AMBER else Ui.TEXT, Ui.medium))
            }, Ui.lp(top = dp(14)))
            line("Tire pressure", if (c.tireWarning) "Low" else "Normal", c.tireWarning)
            line("12 V battery", c.aux12v?.let { "$it%" } ?: "–", (c.aux12v ?: 100) < 60)
            line("Washer fluid", if (c.washerLow) "Low" else "OK", c.washerLow)
            line("Doors, trunk, frunk", if (c.open.isEmpty()) "Closed" else c.open.joinToString(", "), c.open.isNotEmpty())
            line("Windows", if (c.windowsOpen) "Open" else "Closed", c.windowsOpen)
            line("Odometer", c.odometerKm?.let { String.format(java.util.Locale.CANADA, "%,d km", it) } ?: "–")
            addView(pillButton("Done") { d.dismiss() }, Ui.lp(top = dp(22)))
        }
    }

    private fun findCar() {
        if (!prefs.configured) { bar.show("Set up the server in Settings first."); return }
        if (!prefs.hasAdmin) { bar.show("Finding the car needs the admin token. Add it in Settings.", Ui.AMBER); return }
        if (busy) { bar.show("Wait for the current command to finish."); return }
        busy = true
        findCard.busy("Locating…")
        io.execute {
            val res = try { Result.success(CarApi.location(prefs)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                busy = false
                findCard.done(res.isSuccess, null)
                findCard.detail("Opens where the car is in Maps")
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
