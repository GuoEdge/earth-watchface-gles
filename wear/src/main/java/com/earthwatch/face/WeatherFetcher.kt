package com.earthwatch.face

import android.content.Context
import android.location.LocationManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class WeatherFetcher(private val context: Context) {
    @Volatile private var cachedTemp = "--"
    @Volatile private var cachedIcon = ""
    @Volatile private var cachedUvIndex = -1f
    @Volatile private var cachedCondition = ""
    @Volatile private var cachedTemperature = Float.NaN
    @Volatile private var cachedFeelsLike = Float.NaN
    @Volatile private var cachedPrecipProb = -1
    /** Hourly forecast conditions (up to 3 hours ahead). */
    @Volatile private var hourlyConditions = listOf<String>("", "", "")
    @Volatile private var lastFetch = 0L
    @Volatile private var lastLoc = 0L
    @Volatile private var cachedLat = Double.NaN
    @Volatile private var cachedLon = Double.NaN
    private val REFRESH_MS = 30 * 60 * 1000L
    private val LOC_REFRESH_MS = 10 * 60 * 1000L
    private val fetchLock = Any()

    fun ensureFresh() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
    }

    val display: String get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return "$cachedIcon$cachedTemp"
    }

    /** UV index value (0–11+), -1 if unavailable. */
    val uvIndex: Float get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return cachedUvIndex
    }

    /** Actual temperature in Celsius, NaN if unavailable. */
    val temperature: Float get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return cachedTemperature
    }

    /** Precipitation probability (0–100), -1 if unavailable. */
    val precipProb: Int get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return cachedPrecipProb
    }

    /** Apparent / feels-like temperature in Celsius, NaN if unavailable. */
    val feelsLike: Float get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return cachedFeelsLike
    }

    /** WMO weather code for current conditions. */
    val conditionCode: String get() {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return cachedCondition
    }

    val displayCharSequence: CharSequence get() = display

    /** Returns the weather condition emoji for +N hours from now. N in 0..2. */
    fun forecastCondition(hoursAhead: Int): String {
        if (System.currentTimeMillis() - lastFetch > REFRESH_MS) fetch()
        return hourlyConditions.getOrElse(hoursAhead.coerceIn(0, 2)) { "" }
    }

    /** Returns the device's last known location, refreshing at most every LOC_REFRESH_MS. */
    private fun getLocation(): Pair<Double, Double>? {
        val now = System.currentTimeMillis()
        if (now - lastLoc <= LOC_REFRESH_MS && !cachedLat.isNaN()) return cachedLat to cachedLon
        lastLoc = now
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                val loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (loc != null) {
                    cachedLat = loc.latitude; cachedLon = loc.longitude
                    saveLocation(loc.latitude, loc.longitude)
                    Log.i("Weather", "Device location: $cachedLat, $cachedLon")
                    return cachedLat to cachedLon
                }
            }
        } catch (e: SecurityException) {
            Log.w("Weather", "Location permission denied: ${e.message}")
        }
        val prefs = context.getSharedPreferences("earth_watch_config", Context.MODE_PRIVATE)
        val latStr = prefs.getString("weather_lat", null)
        val lonStr = prefs.getString("weather_lon", null)
        if (latStr != null && lonStr != null) {
            val lat = latStr.toDoubleOrNull()
            val lon = lonStr.toDoubleOrNull()
            if (lat != null && lon != null) {
                cachedLat = lat; cachedLon = lon
                Log.i("Weather", "Using saved location: $lat, $lon")
                return cachedLat to cachedLon
            }
        }
        return null
    }

    /** Save current location to SharedPreferences for future use. */
    private fun saveLocation(lat: Double, lon: Double) {
        val prefs = context.getSharedPreferences("earth_watch_config", Context.MODE_PRIVATE)
        val latStr = "%.2f".format(lat); val lonStr = "%.2f".format(lon)
        prefs.edit().putString("weather_lat", latStr).putString("weather_lon", lonStr).apply()
    }

    @Volatile private var isFetching = false

    private fun fetch() {
        synchronized(fetchLock) {
            if (System.currentTimeMillis() - lastFetch <= REFRESH_MS || isFetching) return
            isFetching = true
        }
        Thread {
            try {
                val loc = getLocation()
                val lat = loc?.first ?: 31.2304
                val lon = loc?.second ?: 121.4737
                val url = URL("https://api.open-meteo.com/v1/forecast?" +
                    "latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,weather_code,uv_index" +
                    "&hourly=weather_code,precipitation_probability" +
                    "&timezone=auto" +
                    "&forecast_hours=4")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    val text = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    val root = JSONObject(text)

                    // Current conditions
                    val cur = root.getJSONObject("current")
                    val t = cur.getDouble("temperature_2m")
                    val wc = cur.optInt("weather_code", 0)
                    val uv = cur.optDouble("uv_index", Double.NaN)

                    cachedTemp = "${t.toInt()}°"
                    cachedTemperature = t.toFloat()
                    cachedFeelsLike = cur.optDouble("apparent_temperature", Double.NaN).toFloat()
                    cachedIcon = wmoIcon(wc)
                    cachedCondition = wc.toString()
                    cachedUvIndex = if (uv.isNaN()) -1f else uv.toFloat()

                    // Hourly forecast — extract next 3 hours and precipitation probability
                    val hourly = root.optJSONObject("hourly")
                    if (hourly != null) {
                        val codes = hourly.optJSONArray("weather_code")
                        if (codes != null && codes.length() >= 4) {
                            hourlyConditions = (1..3).map { h ->
                                wmoIcon(codes.optInt(h, 0))
                            }
                        }
                        val pp = hourly.optJSONArray("precipitation_probability")
                        cachedPrecipProb = if (pp != null && pp.length() > 0) pp.optInt(0, 0) else -1
                    }

                    synchronized(fetchLock) { lastFetch = System.currentTimeMillis() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w("Weather", "fetch failed", e)
            } finally {
                isFetching = false
            }
        }.start()
    }

    private fun wmoIcon(code: Int): String = when (code) {
        0 -> "☀"
        in 1..3 -> "⛅"
        45, 48 -> "🌫"
        51, 53, 55, 56, 57 -> "🌦"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧"
        71, 73, 75, 77, 85, 86 -> "🌨"
        95, 96, 99 -> "⛈"
        else -> ""
    }
}
