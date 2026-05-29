package com.earthwatch.face

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting

class EarthConfigActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private val settings = listOf(
        EarthWatchFaceService.ACCENT_COLOR,
        EarthWatchFaceService.SHOW_LUNAR,
        EarthWatchFaceService.SHOW_SENSORS,
        EarthWatchFaceService.ANIMATION_MODE,
        EarthWatchFaceService.SHOW_CLOUDS,
        EarthWatchFaceService.FONT_STYLE,
        EarthWatchFaceService.ARC_TOPLEFT,
        EarthWatchFaceService.ARC_TOPRIGHT,
        EarthWatchFaceService.ARC_BOTLEFT,
        EarthWatchFaceService.ARC_BOTRIGHT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocationPermission()
        prefs = getSharedPreferences("earth_watch_config", MODE_PRIVATE)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }

        for (setting in settings) {
            container.addView(createSettingRow(setting))
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun optionLabel(option: UserStyleSetting.Option): String {
        return (option as? ListUserStyleSetting.ListOption)?.displayName?.toString()
            ?: option.id.toString()
    }

    private fun createSettingRow(setting: UserStyleSetting): View {
        val key = setting.id.toString()
        val allOptions = setting.options
        val currentId = prefs.getString(key, null) ?: allOptions.first().id.toString()

        val label = TextView(this).apply {
            text = setting.displayName
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(4, 6, 4, 2)
        }

        val button = Button(this).apply {
            text = optionLabel(allOptions.first { it.id.toString() == currentId })
            textSize = 15f
            gravity = Gravity.CENTER
            setOnClickListener {
                val cur = prefs.getString(key, null) ?: allOptions.first().id.toString()
                val idx = allOptions.indexOfFirst { it.id.toString() == cur }
                val nextIdx = if (idx >= 0) (idx + 1) % allOptions.size else 0
                val next = allOptions[nextIdx]
                prefs.edit().putString(key, next.id.toString()).apply()
                text = optionLabel(next)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 3, 4, 5)
            addView(label)
            addView(button)
        }
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQ
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQ) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            android.util.Log.i("EarthConfig", "Location permission: ${if (granted) "granted" else "denied"}")
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQ = 42
    }
}
