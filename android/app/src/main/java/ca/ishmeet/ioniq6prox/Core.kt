package ca.ishmeet.ioniq6prox

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Must match firmware/src/main.cpp. */
object Ble {
    val SERVICE_UUID: UUID = UUID.fromString("7805ea83-4cbf-4a8d-b043-9e4a07355fc4")
}

/** Persistent settings (app-private SharedPreferences). */
class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("prox", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) { sp.edit().putString("url", v.trim().trimEnd('/')).apply() }
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v.trim()).apply() }
    var carAddress: String?
        get() = sp.getString("car", null)
        set(v) { sp.edit().putString("car", v).apply() }
    var carName: String?
        get() = sp.getString("carName", null)
        set(v) { sp.edit().putString("carName", v).apply() }
    var espAddress: String?
        get() = sp.getString("esp", null)
        set(v) { sp.edit().putString("esp", v).apply() }
    /** Smoothed signal (dBm) below which you count as "walked away". */
    var awayRssi: Int
        get() = sp.getInt("thr", -85)
        set(v) { sp.edit().putInt("thr", v).apply() }
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }
    /** Optional: the server's ADMIN token, needed for unlock / climate / charging / find car. */
    var adminToken: String
        get() = sp.getString("admin", "") ?: ""
        set(v) { sp.edit().putString("admin", v.trim()).apply() }
    var climateTemp: Float
        get() = sp.getFloat("ctemp", 21f)
        set(v) { sp.edit().putFloat("ctemp", v).apply() }
    var climateMinutes: Int
        get() = sp.getInt("cmin", 15)
        set(v) { sp.edit().putInt("cmin", v).apply() }

    val configured: Boolean get() = serverUrl.startsWith("https://") && token.isNotBlank()
    val hasAdmin: Boolean get() = adminToken.isNotBlank()
}

/** The car photo the owner picked (app-private file). */
object CarPhoto {
    fun file(c: Context) = java.io.File(c.filesDir, "car-photo.jpg")

    fun load(c: Context): android.graphics.Bitmap? {
        val f = file(c)
        if (!f.exists()) return null
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= 1400) sample *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) { null }
    }

    /** Copies the picked image into app storage. Returns false if it couldn't be read. */
    fun save(c: Context, uri: android.net.Uri): Boolean = try {
        c.contentResolver.openInputStream(uri)?.use { input -> file(c).outputStream().use { input.copyTo(it) } } != null
    } catch (_: Exception) { false }

    fun clear(c: Context) { file(c).delete() }
}

/** Live state shared between the service and the screen (same process). */
object Status {
    @Volatile var running = false
    @Volatile var mode = "Stopped"
    @Volatile var espConnected = false
    @Volatile var rssi: Int? = null      // smoothed
    @Volatile var rawRssi: Int? = null
    @Volatile var carConnected: Boolean? = null
    @Volatile var serverProblem: String? = null   // null = last contact worked
    @Volatile var pausedUntil: String? = null     // ISO time from the server, null = not paused
    @Volatile var autolockEnabled: Boolean = true

    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun log(msg: String) {
        lines.addFirst("${fmt.format(Date())}  $msg")
        while (lines.size > 100) lines.removeLast()
        Log.i("Ioniq6Prox", msg)
    }

    @Synchronized fun logText(): String = lines.joinToString("\n")
}

/** Minimal HTTPS client for the ioniq6 API. Call off the main thread. */
object Api {
    class Result(val code: Int, val body: String)

    private val PAUSED = Regex("\"pausedUntil\":\\s*\"([^\"]+)\"")

    /** minutes = 0 resumes. Updates Status; returns a message for the user. */
    fun pause(p: Prefs, minutes: Int): String = try {
        val r = request(p, "POST", "/v1/autolock/pause", "{\"minutes\":$minutes}")
        if (r.code == 200) {
            Status.serverProblem = null
            Status.pausedUntil = PAUSED.find(r.body)?.groupValues?.get(1)
            if (minutes > 0) "Auto-lock paused for ${minutes / 60} h" else "Auto-lock resumed"
        } else "Server answered HTTP ${r.code}"
    } catch (e: Exception) {
        Status.serverProblem = "can't reach the server (${e.javaClass.simpleName})"
        "Couldn't reach the server"
    }

    /** Refreshes Status from GET /v1/autolock; returns a message for the user. */
    fun check(p: Prefs): String = try {
        val r = request(p, "GET", "/v1/autolock")
        when (r.code) {
            200 -> {
                Status.serverProblem = null
                Status.pausedUntil = PAUSED.find(r.body)?.groupValues?.get(1)
                Status.autolockEnabled = !r.body.contains("\"enabled\": false")
                "Server OK — auto-lock " + (if (Status.autolockEnabled) "enabled" else "disabled") +
                    (if (Status.pausedUntil != null) " (paused)" else "")
            }
            401 -> { Status.serverProblem = "server rejected the phone token"; "Server reached, but the token was rejected" }
            else -> { Status.serverProblem = "server error HTTP ${r.code}"; "Server answered HTTP ${r.code}" }
        }
    } catch (e: Exception) {
        Status.serverProblem = "can't reach the server (${e.javaClass.simpleName})"
        "Couldn't reach the server: ${e.message}"
    }

    fun request(p: Prefs, method: String, path: String, json: String? = null, token: String = p.token, readTimeoutMs: Int = 20_000): Result {
        val conn = URL(p.serverUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Result(code, body)
        } finally {
            conn.disconnect()
        }
    }
}

/** Car state as the server reports it (GET /v1/status). */
data class CarStatus(
    val vehicle: String?,
    val updated: String?,
    val battery: Int?,
    val rangeKm: Int?,
    val pluggedIn: Boolean,
    val charging: Boolean,
    val chargeMinutesLeft: Int?,
    val locked: Boolean?,
    val carOn: Boolean,
    val open: List<String>,
    val climateOn: Boolean,
    val climateSetC: Double?,
)

/** Car controls. Calls block — run them off the main thread. */
object CarApi {
    class Failure(message: String) : Exception(message)

    private fun explain(r: Api.Result): String {
        val err = try { org.json.JSONObject(r.body).optString("error") } catch (_: Exception) { "" }
        return when (r.code) {
            401 -> "The server didn't accept the token. Check it in Settings."
            403 -> "This needs the admin token. Add it in Settings."
            409 -> "The server needs Hyundai's one-time email code. Run: sudo bash install.sh login"
            else -> err.ifBlank { "Server answered HTTP ${r.code}" }
        }
    }

    private fun json(r: Api.Result): org.json.JSONObject {
        if (r.code !in 200..299) throw Failure(explain(r))
        return org.json.JSONObject(r.body)
    }

    private fun org.json.JSONObject.intOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optDouble(k).let { if (it.isNaN()) null else it.toInt() }

    fun status(p: Prefs, live: Boolean = false): CarStatus {
        val r = try {
            Api.request(p, "GET", "/v1/status" + if (live) "?refresh=true" else "", readTimeoutMs = if (live) 90_000 else 30_000)
        } catch (e: Exception) { throw Failure("Couldn't reach the server") }
        val j = json(r)
        val open = mutableListOf<String>()
        j.optJSONArray("doors_open")?.let { a -> for (i in 0 until a.length()) open += a.getString(i).replace(Regex("([A-Z])"), " $1").lowercase() + " door" }
        if (j.optBoolean("trunk_open")) open += "trunk"
        if (j.optBoolean("frunk_open")) open += "frunk"
        return CarStatus(
            vehicle = j.optString("vehicle").ifBlank { null },
            updated = j.optString("updated").ifBlank { null },
            battery = j.intOrNull("battery"),
            rangeKm = j.intOrNull("range_km"),
            pluggedIn = j.optBoolean("plugged_in"),
            charging = j.optBoolean("charging"),
            chargeMinutesLeft = j.intOrNull("charge_time_left_min"),
            locked = if (j.isNull("locked") || !j.has("locked")) null else j.optBoolean("locked"),
            carOn = j.optBoolean("car_on"),
            open = open,
            climateOn = j.optBoolean("climate_on"),
            climateSetC = if (j.isNull("climate_set_c") || !j.has("climate_set_c")) null else j.optDouble("climate_set_c"),
        )
    }

    /**
     * Sends a command and waits for the car to confirm (the server returns a job; we poll it).
     * Lock works with the phone token; everything else needs the admin token.
     */
    fun command(p: Prefs, method: String, path: String, body: String? = null, needsAdmin: Boolean = true,
                onWaiting: (() -> Unit)? = null): String {
        if (needsAdmin && !p.hasAdmin) throw Failure("This needs the admin token. Add it in Settings.")
        val token = if (p.hasAdmin) p.adminToken else p.token
        val r = try { Api.request(p, method, path, body ?: "{}", token = token) } catch (e: Exception) { throw Failure("Couldn't reach the server") }
        val job = json(r)
        val id = job.optString("id")
        if (id.isBlank()) return job.optString("status", "sent")
        onWaiting?.invoke()
        val deadline = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3_000)
            val jr = try { Api.request(p, "GET", "/v1/jobs/$id", token = token) } catch (_: Exception) { continue }
            if (jr.code != 200) continue
            val j = org.json.JSONObject(jr.body)
            when (j.optString("status")) {
                "queued", "running" -> continue
                "success" -> return "success"
                "error" -> throw Failure(j.optString("error").ifBlank { "The command failed" })
                "timeout" -> throw Failure("Sent, but the car hasn't confirmed yet. It may still happen.")
                else -> {
                    val detail = j.optJSONObject("result")?.optString("detail").orEmpty()
                    throw Failure("The car refused" + if (detail.isNotBlank() && detail != "null") " ($detail)" else "")
                }
            }
        }
        throw Failure("Sent, but the car hasn't confirmed yet. It may still happen.")
    }

    /** Returns (lat, lon). */
    fun location(p: Prefs): Pair<Double, Double> {
        if (!p.hasAdmin) throw Failure("Finding the car needs the admin token. Add it in Settings.")
        val r = try { Api.request(p, "GET", "/v1/location", token = p.adminToken, readTimeoutMs = 60_000) } catch (e: Exception) { throw Failure("Couldn't reach the server") }
        val j = json(r)
        return j.getDouble("lat") to j.getDouble("lon")
    }
}
