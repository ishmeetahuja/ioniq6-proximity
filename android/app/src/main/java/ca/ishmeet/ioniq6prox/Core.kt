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

    val configured: Boolean get() = serverUrl.startsWith("https://") && token.isNotBlank()
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

    fun request(p: Prefs, method: String, path: String, json: String? = null): Result {
        val conn = URL(p.serverUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Authorization", "Bearer ${p.token}")
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
