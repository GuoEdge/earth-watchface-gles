package com.earthwatch.face

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.os.BatteryManager
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.SurfaceHolder
import androidx.wear.watchface.*
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import java.io.PrintWriter
import java.time.*
import java.util.*
import kotlin.math.*

private data class Palette(
    val time: Int,
    val hand: Int,
    val second: Int,
    val date: Int,
    val lunar: Int,
    val gz: Int,
    val rim: Int,
    val arcs: List<Int>,
)

private val PALETTES = listOf(
    Palette(0xFFF5F9FF.toInt(), 0xFFF0F4FF.toInt(), 0xFFFF6B6B.toInt(), 0xFFCAD8F0.toInt(),
        0xFFC0D4EE.toInt(), 0xFFA0B8D8.toInt(), 0xFF4A90D9.toInt(),
        listOf(0xFFFF9800.toInt(), 0xFF00BFA5.toInt(), 0xFFE85D75.toInt(), 0xFF5B8DEF.toInt())),
    Palette(0xFFE8F4FD.toInt(), 0xFFD6ECFA.toInt(), 0xFF64B5F6.toInt(), 0xFFB3D9F2.toInt(),
        0xFFA8D4F0.toInt(), 0xFF80B9E0.toInt(), 0xFF0277BD.toInt(),
        listOf(0xFF4FC3F7.toInt(), 0xFF29B6F6.toInt(), 0xFF03A9F4.toInt(), 0xFF039BE5.toInt())),
    Palette(0xFFFCFAF7.toInt(), 0xFFF5F0EB.toInt(), 0xFFBCAAA4.toInt(), 0xFFF0EBE3.toInt(),
        0xFFEDE5DB.toInt(), 0xFFDDD3C7.toInt(), 0xFFA09080.toInt(),
        listOf(0xFFF5F0EB.toInt(), 0xFFEDE4D9.toInt(), 0xFFE0D4C5.toInt(), 0xFFD4C4B0.toInt())),
    Palette(0xFFFFECE8.toInt(), 0xFFFFD4C0.toInt(), 0xFFFF5252.toInt(), 0xFFFFAB91.toInt(),
        0xFFFFAB91.toInt(), 0xFFFF8A65.toInt(), 0xFFD50000.toInt(),
        listOf(0xFFFF7043.toInt(), 0xFFE53935.toInt(), 0xFFD50000.toInt(), 0xFFBF360C.toInt())),
    Palette(0xFFE8F0E0.toInt(), 0xFFD4E0C8.toInt(), 0xFF81C784.toInt(), 0xFFB8D0A8.toInt(),
        0xFFA8CCA0.toInt(), 0xFF8DB580.toInt(), 0xFF2E7D32.toInt(),
        listOf(0xFF66BB6A.toInt(), 0xFF43A047.toInt(), 0xFF388E3C.toInt(), 0xFF2E7D32.toInt())),
)

private data class ArcSlot(val slotId: Int, val startAngle: Float, val endAngle: Float)

private val ARC_SLOTS = listOf(
    ArcSlot(0, 185f, 265f),
    ArcSlot(1, 275f, 355f),
    ArcSlot(2, 95f, 175f),
    ArcSlot(3, 5f, 85f),
)

class EarthAssets : Renderer.SharedAssets {
    override fun onDestroy() {}
}

class EarthRenderer(
    surfaceHolder: SurfaceHolder,
    private val curStyleRepo: CurrentUserStyleRepository,
    watchState: WatchState,
    interactiveDrawModeUpdateDelayMillis: Long,
    private val context: Context,
    private val complicationSlotsManager: ComplicationSlotsManager
) : Renderer.GlesRenderer2<EarthAssets>(
    surfaceHolder, curStyleRepo, watchState,
    interactiveDrawModeUpdateDelayMillis
) {
    private val sunCalc = SunCalculator()
    private val lunarCalc = LunarCalendar()
    private val earthGles = EarthGles()
    private val glPrimitives = GlPrimitives()
    private val glOverlay = GlOverlay()
    private val textCache = TextTextureCache()
    private val notifProvider = NotificationCountProvider(context)
    private val wfState = watchState

    private var dayTex: Bitmap? = null
    private var dayTexLoaded = false
    private var nightTex: Bitmap? = null
    private var nightTexLoaded = false
    private fun ensureTextures() {
        if (!dayTexLoaded) {
            try { dayTex = BitmapFactory.decodeStream(context.assets.open("textures/earth_day.jpg")) }
            catch (e: Exception) { android.util.Log.w("EarthRenderer", "Failed to load day texture", e) }
            dayTexLoaded = true
        }
        if (!nightTexLoaded) {
            try { nightTex = BitmapFactory.decodeStream(context.assets.open("textures/earth_night.png")) }
            catch (e: Exception) { android.util.Log.w("EarthRenderer", "Failed to load night texture", e) }
            nightTexLoaded = true
        }
    }

    private var wasInteractive = false
    private var isAnimating = false
    private var wakeTime = 0L
    private val ANIM_MS = 1500L
    private var slowRotMs = 0L

    @Volatile private var bmpSize = 0
    private val GL_SCALE = 1.00f

    private var lastBatteryMs = 0L
    private val BATTERY_READ_MS = 120000L

    fun requestSpin() {
        if (animMode == 0) {
            isAnimating = true
            wakeTime = System.currentTimeMillis()
        }
    }
    private val CHINA_RY = 380f

    private var batteryPct = 0
    private var isCharging = false
    private var batteryIsLow = false
    private fun readBattery() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = if (android.os.Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                context.registerReceiver(null, filter)
            isCharging = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
            batteryIsLow = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)?.let { it <= 15 } ?: false
        } catch (_: Exception) { }
    }

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences("earth_watch_config", Context.MODE_PRIVATE)

    init { migrateArcDefaults() }

    private fun migrateArcDefaults() {
        val version = configPrefs.getInt("config_version", 0)
        if (version >= 7) return
        val edit = configPrefs.edit()
        if (version < 1) {
            edit.putString("accent_color", "0")
            edit.putString("show_lunar", "0")
            edit.putString("show_sensors", "0")
            edit.putString("arc_tl", "0")
            edit.putString("arc_tr", "6")
            edit.putString("arc_bl", "9")
            edit.putString("arc_br", "8")
        }
        if (!configPrefs.contains("font_style")) edit.putString("font_style", "0")
        if (!configPrefs.contains("show_clouds")) edit.putString("show_clouds", "0")
        if (!configPrefs.contains("animation_mode")) edit.putString("animation_mode", "0")
        edit.putInt("config_version", 7)
        edit.apply()
    }

    private fun syncStyleToPrefs(style: UserStyle) {
        val settings = listOf(
            EarthWatchFaceService.ACCENT_COLOR to "accent_color",
            EarthWatchFaceService.SHOW_LUNAR to "show_lunar",
            EarthWatchFaceService.SHOW_SENSORS to "show_sensors",
            EarthWatchFaceService.ARC_TOPLEFT to "arc_tl",
            EarthWatchFaceService.ARC_TOPRIGHT to "arc_tr",
            EarthWatchFaceService.ARC_BOTLEFT to "arc_bl",
            EarthWatchFaceService.ARC_BOTRIGHT to "arc_br",
            EarthWatchFaceService.FONT_STYLE to "font_style",
        )
        val edit = configPrefs.edit()
        for ((setting, key) in settings) {
            val opt = style[setting]
            if (opt != null) edit.putString(key, EarthWatchFaceService.optionId(opt).toString())
        }
        edit.apply()
    }

    private val STYLE_SETTINGS = listOf(
        EarthWatchFaceService.ACCENT_COLOR,
        EarthWatchFaceService.SHOW_LUNAR,
        EarthWatchFaceService.SHOW_SENSORS,
        EarthWatchFaceService.ARC_TOPLEFT,
        EarthWatchFaceService.ARC_TOPRIGHT,
        EarthWatchFaceService.ARC_BOTLEFT,
        EarthWatchFaceService.ARC_BOTRIGHT,
        EarthWatchFaceService.FONT_STYLE,
    )
    private var lastStyleFingerprint = ""
    private var accentIdx = 0
    private var showLunar = true
    private var showSensors = true
    private var showClouds = true
    private var animMode = 0
    private val arcDataSource = IntArray(4) { 0 }
    private var lastStyleReadMs = 0L

    private fun readStyle() {
        val now = System.currentTimeMillis()
        if (now - lastStyleReadMs < 500L) return
        lastStyleReadMs = now
        val style = curStyleRepo.userStyle.value
        val fp = computeFingerprint(style)
        if (fp != lastStyleFingerprint) {
            lastStyleFingerprint = fp
            syncStyleToPrefs(style)
        }
        accentIdx = prefGet("accent_color")
        showLunar = prefGet("show_lunar") == 0
        showSensors = prefGet("show_sensors") == 0
        arcDataSource[0] = prefGet("arc_tl")
        arcDataSource[1] = prefGet("arc_tr")
        arcDataSource[2] = prefGet("arc_bl")
        arcDataSource[3] = prefGet("arc_br")
        showClouds = prefGet("show_clouds") == 0
        animMode = prefGet("animation_mode")
        val fontStyle = prefGet("font_style")
        timeP.typeface = when (fontStyle) { 1 -> fontClassic; 2 -> fontDefault; 3 -> fontLight; else -> fontModern }
        val idx = accentIdx.coerceIn(0, PALETTES.lastIndex)
        palette = PALETTES[idx]
    }

    private val fingerprintBuilder = StringBuilder(32)
    private fun computeFingerprint(style: UserStyle): String {
        fingerprintBuilder.clear()
        for (s in STYLE_SETTINGS) {
            val opt = style[s]
            if (opt != null) fingerprintBuilder.append(opt.id.value.decodeToString())
            fingerprintBuilder.append('|')
        }
        return fingerprintBuilder.toString()
    }

    private fun prefGet(key: String): Int =
        configPrefs.getString(key, null)?.toIntOrNull() ?: 0

    private var palette = PALETTES[0]

    private val timeP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; letterSpacing = 0.04f }
    private val fontModern = Typeface.createFromAsset(context.assets, "fonts/DSEG7Modern-Bold.ttf")
    private val fontClassic = Typeface.createFromAsset(context.assets, "fonts/DSEG7Classic-Bold.ttf")
    private val fontDefault = Typeface.create("sans-serif", Typeface.BOLD)
    private val fontLight = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val gregP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val lunarP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val gzP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val weatherFetcher = WeatherFetcher(context)
    private val senP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val _activeSlotIds = mutableSetOf<Int>()

    private var compTexIds = IntArray(4) { 0 }
    private var compBmps = arrayOfNulls<Bitmap>(4)
    private var compBmpSizes = Array(4) { intArrayOf(0, 0) }
    private var compDataFp = Array(4) { "" }

    private var cloudLoaded = false
    private var cloudRenderLogged = false

    private var atmoLoaded = false
    private var atmoIr = 0f

    private var nightOverlayTex = 0
    private var lastNightAng = Float.NaN

    override suspend fun createSharedAssets(): EarthAssets = EarthAssets()

    override fun render(zdt: ZonedDateTime, sharedAssets: EarthAssets) {
        val holder = surfaceHolder
        val surface = holder.surfaceFrame
        val w = surface.width(); val h = surface.height()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f
        val ir = r - 12f
        val f = r / 200f
        val off = ir * 0.45f
        val isInteractive = wfState.isAmbient.value == false

        if (bmpSize > 0 && !validateGlContext()) {
            android.util.Log.w("EarthRenderer", "GL context lost, forcing re-init")
            bmpSize = 0
            textCache.release()
            cloudLoaded = false; atmoLoaded = false; atmoIr = 0f
            lastNightAng = Float.NaN; nightOverlayTex = 0
            for (i in compTexIds.indices) { compTexIds[i] = 0 }; compDataFp = Array(4) { "" }
        }

        if (!isInteractive) {
            renderAmbient(zdt, w, h, cx, cy, ir, f)
            return
        }

        readStyle()
        if (System.currentTimeMillis() - lastBatteryMs > BATTERY_READ_MS) {
            readBattery(); lastBatteryMs = System.currentTimeMillis()
        }

        val sz = (min(w, h) * GL_SCALE).toInt().coerceAtLeast(64)

        if (sz > 0 && sz != bmpSize) {
            ensureTextures(); bmpSize = sz
            android.util.Log.i("EarthRenderer", "init GL sz=$sz")
            earthGles.init(sz, dayTex, nightTex)
            glPrimitives.init()
            glOverlay.init()
            cloudLoaded = false; initCloudTexture()
            atmoIr = 0f; atmoLoaded = false
            lastNightAng = Float.NaN
            android.util.Log.i("EarthRenderer", "GL init done progEarth=${earthGles.progEarthDebug} prog2d=${glPrimitives.progDebug}")
        }

        if (!wasInteractive) {
            wakeTime = System.currentTimeMillis()
            isAnimating = true
        }
        wasInteractive = true

        var ry = CHINA_RY

        if (isAnimating) {
            val elapsed = System.currentTimeMillis() - wakeTime
            if (elapsed < ANIM_MS) {
                val t = elapsed.toFloat() / ANIM_MS
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                ry = CHINA_RY + 720f * eased
            } else {
                ry = CHINA_RY
                isAnimating = false
                if (animMode == 1) slowRotMs = System.currentTimeMillis()
            }
        } else if (animMode == 1) {
            if (slowRotMs == 0L) slowRotMs = System.currentTimeMillis()
            val dt = (System.currentTimeMillis() - slowRotMs) / 1000f
            ry = CHINA_RY + (dt * 4f) % 360f
        } else {
            ry = CHINA_RY
        }

        val sunDir = sunCalc.sunDirection(
            zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        )
        val ang = atan2(sunDir[0].toDouble(), sunDir[2].toDouble()).toFloat()

        val timeText = String.format(Locale.US, "%02d:%02d", zdt.hour, zdt.minute)
        val dateText = "${zdt.monthValue}月${zdt.dayOfMonth}日 ${dow(zdt.dayOfWeek)}"
        val lunarText = if (showLunar) lunarCalc.toLunar(zdt.toLocalDate()) else ""
        val gzText = if (showLunar) lunarCalc.toGanzhi(zdt.toLocalDate()) else ""
        updateActiveSlotIds()

        val sensorTexts = (0..3).map { i ->
            val ds = arcDataSource[i]
            if (ds == 99 || ARC_SLOTS[i].slotId in _activeSlotIds) "" else dataForSource(ds).second
        }

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0.024f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // ── 3D地球 ──
        val earthVpX = (w - sz) / 2
        val earthVpY = ((h - sz) / 2 - off.toInt())
        earthGles.render(sz, ry, sunDir, earthVpX, earthVpY)

        resetGlState()

        // ── 2D叠加层（正交投影） ──
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val mvp = orthoMvp(w, h)

        // 云层 + 晨昏线 + 大气
        val rotYRad = Math.toRadians(ry.toDouble()).toFloat()
        if (showClouds && glOverlay.cloudInited) {
            val cw = glOverlay.cloudW
            val cloudDrift = if (cw <= 0) 0f else {
                val periodMs = 1000L * cw
                val phaseMs = System.currentTimeMillis() % periodMs
                val timeShift = phaseMs.toFloat() / 1000f
                (timeShift * 2.5f % cw.toFloat()) / cw.toFloat()
            }
            glOverlay.drawClouds(mvp, cx, cy + off, ir * 0.95f, cloudDrift, 150 / 255f, rotYRad)
        } else if (!cloudRenderLogged) {
            cloudRenderLogged = true
            android.util.Log.i("EarthRenderer", "Clouds NOT rendering: showClouds=$showClouds cloudInited=${glOverlay.cloudInited}")
        }
        drawNightOverlay(mvp, cx, cy + off, ir, ang, rotYRad)

        ensureAtmoTexture(ir)
        if (atmoLoaded) {
            val ar = ir * 1.20f
            glOverlay.drawAtmosphere(mvp, cx, cy + off, ar, 1f)
        }

        // 表盘元素：边缘 + 刻度 + 弧形 + 指针 + 文字 + 复杂功能
        drawGlRim(mvp, cx, cy, ir)
        drawGlRimTicks(mvp, cx, cy, ir, f)
        drawGlArcs(mvp, cx, cy, ir, f)
        drawGlHands(mvp, cx, cy, ir, f, zdt)
        drawGlTime(mvp, cx, cy, ir, f, timeText)
        drawGlDate(mvp, cx, cy, ir, f, dateText, lunarText, gzText)
        if (showSensors) drawGlSensors(mvp, cx, cy, ir, f, sensorTexts)
        drawGlComplications(mvp, cx, cy, ir, zdt)

        GLES20.glUseProgram(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private var cachedMvpW = 0; private var cachedMvpH = 0
    private val cachedMvp = FloatArray(16)

    private fun orthoMvp(w: Int, h: Int): FloatArray {
        if (cachedMvpW == w && cachedMvpH == h) return cachedMvp
        android.opengl.Matrix.orthoM(cachedMvp, 0, 0f, w.toFloat(), h.toFloat(), 0f, -1f, 1f)
        cachedMvpW = w; cachedMvpH = h
        return cachedMvp
    }

    private fun resetGlState() {
        GLES20.glUseProgram(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun initCloudTexture() {
        if (cloudLoaded) return
        try {
            val b = BitmapFactory.decodeStream(context.assets.open("textures/earth_cloud.png"))
            glOverlay.initClouds(b)
            b.recycle()
            cloudLoaded = true
        } catch (e: Exception) {
            android.util.Log.w("EarthRenderer", "Failed to load cloud texture", e)
        }
        android.util.Log.i("EarthRenderer", "initCloudTexture done cloudLoaded=$cloudLoaded cloudInited=${glOverlay.cloudInited} cloudW=${glOverlay.cloudW}")
    }

    private fun ensureAtmoTexture(ir: Float) {
        if (atmoIr == ir && atmoLoaded) return
        val d = (ir * 2.4f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tcx = d / 2f; val tcy = d / 2f; val tr = d / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(tcx, tcy, tr,
            intArrayOf(
                Color.argb(0, 50, 153, 242), Color.argb(0, 50, 153, 242),
                Color.argb(45, 50, 153, 242), Color.argb(85, 50, 153, 242),
                Color.argb(135, 50, 153, 242), Color.argb(0, 50, 153, 242)
            ),
            floatArrayOf(0.0f, 0.76f, 0.83f, 0.88f, 0.96f, 1.0f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(tcx, tcy, tr, p)
        glOverlay.uploadAtmosphere(bmp)
        bmp.recycle()
        atmoLoaded = true
        atmoIr = ir
    }

    private fun drawNightOverlay(mvp: FloatArray, cx: Float, cy: Float, ir: Float, ang: Float, rotY: Float) {
        if (!ang.equals(lastNightAng)) {
            lastNightAng = ang
            val d = (ir * 2.4f).toInt().coerceAtLeast(64)
            val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val tcx = d / 2f; val tcy = d / 2f; val tr = d / 2f
            val gdx = tr * 0.75f * cos(ang).toFloat()
            val gdy = -tr * 0.75f * sin(ang).toFloat()
            val np = Paint(Paint.ANTI_ALIAS_FLAG)
            np.shader = LinearGradient(
                tcx + gdx, tcy + gdy, tcx - gdx, tcy - gdy,
                intArrayOf(0x00000000, 0x00000000, 0x60000000.toInt()),
                floatArrayOf(0.0f, 0.60f, 1.0f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(tcx, tcy, tr, np)
            if (nightOverlayTex == 0) {
                val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
                nightOverlayTex = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nightOverlayTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
        }
        if (nightOverlayTex != 0) {
            glOverlay.drawNightOverlay(mvp, cx, cy, ir, nightOverlayTex, rotY)
        }
    }

    private fun drawGlRim(mvp: FloatArray, cx: Float, cy: Float, ir: Float) {
        for (k in 0..2) {
            val alpha = ((2 - k) * 10 / 2) / 255f
            val color = intToGlColor(palette.rim, alpha)
            val strokeWidth = 2f + k * 1.5f
            glPrimitives.drawRing(mvp, cx, cy, ir - 2f, strokeWidth, color)
        }
    }

    private fun drawGlRimTicks(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float) {
        val innerR = ir - 8f * f; val outerR = ir - 2f
        for (i in 0 until 12) {
            val deg = i * 30f
            val rad = Math.toRadians((deg - 90).toDouble())
            val alpha = if (i % 3 == 0) 220 / 255f else 150 / 255f
            val width = if (i % 3 == 0) 5f * f else 3.5f * f
            val color = intToGlColor(palette.rim, alpha)
            glPrimitives.drawLine(mvp,
                cx + innerR * cos(rad).toFloat(), cy + innerR * sin(rad).toFloat(),
                cx + outerR * cos(rad).toFloat(), cy + outerR * sin(rad).toFloat(),
                width, color)
        }
    }

    private fun drawGlArcs(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float) {
        if (!showSensors) return
        val ar = ir * 0.90f; val aw = 9f * f
        for ((slotIdx, slot) in ARC_SLOTS.withIndex()) {
            val ds = arcDataSource[slotIdx]
            if (ds == 99) continue
            val (pct, _) = dataForSource(ds)
            val effectivePct = if (slot.slotId in _activeSlotIds) 0f else pct
            val color = palette.arcs.getOrElse(slotIdx) { 0xFF42A5F5.toInt() }

            val startRad = Math.toRadians(slot.startAngle.toDouble()).toFloat()
            val sweepRad = Math.toRadians((slot.endAngle - slot.startAngle).toDouble()).toFloat()

            val trackColor = intToGlColor(color, 25 / 255f)
            glPrimitives.drawArc(mvp, cx, cy, ar, startRad, sweepRad, aw, trackColor)

            if (effectivePct > 0f) {
                val fillRad = sweepRad * effectivePct.coerceIn(0f, 1f)
                val glowColor = intToGlColor(color, 35 / 255f)
                glPrimitives.drawArc(mvp, cx, cy, ar, startRad, fillRad, aw * 2.2f, glowColor)
                val fillColor = intToGlColor(color, 1f)
                glPrimitives.drawSdfArc(mvp, cx, cy, ar, startRad, fillRad, aw, fillColor)
            }
        }
    }

    private fun drawGlHands(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, zdt: ZonedDateTime) {
        val h = zdt.hour % 12; val m = zdt.minute; val s = zdt.second
        val ms = zdt.nano / 1_000_000_000f

        val hourDeg = (h + m / 60f) * 30f
        val minDeg = (m + s / 60f + ms / 60f) * 6f
        val secDeg = (s + ms) * 6f

        val shadowColor = floatArrayOf(0f, 0f, 0f, 0.38f)
        val sdx = 1.2f * f; val sdy = 2f * f

        val hourWidth = 7f * f; val hourLen = ir * 0.45f
        drawGlHand(mvp, cx + sdx, cy + sdy, hourLen, hourDeg, hourWidth * 1.3f, shadowColor)
        drawGlHand(mvp, cx, cy, hourLen, hourDeg, hourWidth, intToGlColor(palette.hand, 1f))

        val minWidth = 5f * f; val minLen = ir * 0.68f
        drawGlHand(mvp, cx + sdx, cy + sdy, minLen, minDeg, minWidth * 1.3f, shadowColor)
        drawGlHand(mvp, cx, cy, minLen, minDeg, minWidth, intToGlColor(palette.hand, 1f))

        val secWidth = 3.5f * f; val secLen = ir * 0.78f
        drawGlHand(mvp, cx + sdx, cy + sdy, secLen, secDeg, secWidth * 1.3f, shadowColor)
        drawGlHand(mvp, cx, cy, secLen, secDeg, secWidth, intToGlColor(palette.second, 1f))

        glPrimitives.drawCircle(mvp, cx + sdx, cy + sdy, 5f * f * 1.3f, shadowColor)
        val dotColor = floatArrayOf(1f, 0.27f, 0f, 1f)
        glPrimitives.drawCircle(mvp, cx, cy, 5f * f, dotColor)
    }

    private fun drawGlHand(mvp: FloatArray, cx: Float, cy: Float, len: Float, deg: Float, width: Float, color: FloatArray) {
        val a = Math.toRadians((deg - 90).toDouble())
        val ex = cx + len * cos(a).toFloat()
        val ey = cy + len * sin(a).toFloat()
        glPrimitives.drawSdfRoundLine(mvp, cx, cy, ex, ey, width, color)
    }

    private var cachedTimeFontKey = ""
    private var cachedColonW = 0f; private var cachedHw = 0f; private var cachedMw = 0f
    private var cachedHh = ""; private var cachedMm = ""

    private fun drawGlTime(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, timeText: String) {
        timeP.textSize = ir * 0.32f
        val baseY = cy - ir * 0.42f

        val hh = String.format(Locale.US, "%02d", timeText.substringBefore(':').toIntOrNull() ?: 0)
        val mm = String.format(Locale.US, "%02d", timeText.substringAfter(':').toIntOrNull() ?: 0)

        timeP.textAlign = Paint.Align.LEFT
        val fontKey = "${timeP.typeface}/${timeP.textSize}"
        if (fontKey != cachedTimeFontKey) {
            cachedTimeFontKey = fontKey
            cachedColonW = timeP.measureText(":")
            cachedHw = timeP.measureText(hh); cachedHh = hh
            cachedMw = timeP.measureText(mm); cachedMm = mm
        }
        if (cachedHh != hh) { cachedHw = timeP.measureText(hh); cachedHh = hh }
        if (cachedMm != mm) { cachedMw = timeP.measureText(mm); cachedMm = mm }

        timeP.color = palette.time
        timeP.setShadowLayer(6f * f, 0f, 4f * f, 0xCC000000.toInt())

        val hResult = textCache.getOrUpdate("time_h", timeP, hh)
        val cResult = textCache.getOrUpdate("time_colon", timeP, ":")
        val mResult = textCache.getOrUpdate("time_m", timeP, mm)
        if (hResult == null || cResult == null || mResult == null) { timeP.textAlign = Paint.Align.CENTER; return }

        val totalW = cachedHw + cachedColonW + cachedMw
        val startX = cx - totalW / 2f
        val fm = timeP.fontMetrics
        val blt = hResult.pad.toFloat() - fm.ascent

        glPrimitives.drawTexturedQuad(mvp, hResult.texId, startX - hResult.pad.toFloat(), baseY - blt, hResult.w.toFloat(), hResult.h.toFloat(), 1f)
        glPrimitives.drawTexturedQuad(mvp, cResult.texId, startX + cachedHw - cResult.pad.toFloat(), baseY - blt, cResult.w.toFloat(), cResult.h.toFloat(), 1f)
        glPrimitives.drawTexturedQuad(mvp, mResult.texId, startX + cachedHw + cachedColonW - mResult.pad.toFloat(), baseY - blt, mResult.w.toFloat(), mResult.h.toFloat(), 1f)

        timeP.textAlign = Paint.Align.CENTER
    }

    private fun drawGlDate(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float,
                           dateText: String, lunarText: String, gzText: String) {
        val by = cy + ir * 0.40f
        gregP.color = palette.date; gregP.textSize = ir * 0.13f
        gregP.setShadowLayer(14f * f, 0f, 4f * f, 0xCC000000.toInt())
        textCache.getOrUpdate("date", gregP, dateText)?.let { (texId, tw, th, pad) ->
            val blt = pad.toFloat() - gregP.fontMetrics.ascent
            glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, by - blt, tw.toFloat(), th.toFloat(), 1f)
        }

        if (showLunar) {
            lunarP.color = palette.lunar; lunarP.textSize = ir * 0.11f
            lunarP.setShadowLayer(10f * f, 0f, 3f * f, 0xBB000000.toInt())
            textCache.getOrUpdate("lunar", lunarP, lunarText)?.let { (texId, tw, th, pad) ->
                val ly = by + ir * 0.16f
                val blt = pad.toFloat() - lunarP.fontMetrics.ascent
                glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, ly - blt, tw.toFloat(), th.toFloat(), 1f)
            }
            gzP.color = palette.gz; gzP.textSize = ir * 0.085f
            gzP.setShadowLayer(8f * f, 0f, 2f * f, 0xAA000000.toInt())
            textCache.getOrUpdate("gz", gzP, gzText)?.let { (texId, tw, th, pad) ->
                val gy = by + ir * 0.30f
                val blt = pad.toFloat() - gzP.fontMetrics.ascent
                glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, gy - blt, tw.toFloat(), th.toFloat(), 1f)
            }
        }
    }

    private fun drawGlSensors(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, sensorTexts: List<String>) {
        senP.textSize = ir * 0.095f
        senP.setShadowLayer(9f * f, 0f, 2f * f, 0xAA000000.toInt())
        for ((slotIdx, slot) in ARC_SLOTS.withIndex()) {
            val txt = sensorTexts[slotIdx]
            if (txt.isEmpty()) continue
            val color = palette.arcs.getOrElse(slotIdx) { 0xFF42A5F5.toInt() }
            senP.color = color
            val (xOff, yOff) = when (slotIdx) {
                0 -> -ir * 0.60f to -ir * 0.28f
                1 -> ir * 0.60f to -ir * 0.28f
                2 -> -ir * 0.60f to ir * 0.35f
                else -> ir * 0.60f to ir * 0.35f
            }
            textCache.getOrUpdate("sensor_$slotIdx", senP, txt)?.let { (texId, tw, th, pad) ->
                val drawX = cx + xOff - tw / 2f
                val drawY = cy + yOff - (pad.toFloat() - senP.fontMetrics.ascent)
                glPrimitives.drawTexturedQuad(mvp, texId, drawX, drawY, tw.toFloat(), th.toFloat(), 1f)
            }
        }
    }

    private fun drawGlComplications(mvp: FloatArray, cx: Float, cy: Float, ir: Float, zdt: ZonedDateTime) {
        val rp = RenderParameters(
            drawMode = DrawMode.INTERACTIVE,
            watchFaceLayers = setOf(WatchFaceLayer.COMPLICATIONS)
        )
        for (slot in complicationSlotsManager.complicationSlots.values) {
            val data = slot.complicationData.value
            if (data is EmptyComplicationData || data is NoDataComplicationData || data is NotConfiguredComplicationData) continue
            val bounds = computeSlotPixelBounds(slot.id, cx, cy, ir)
            if (bounds.isEmpty) continue

            val slotIdx = slot.id
            if (slotIdx < 0 || slotIdx >= compTexIds.size) continue

            val fp = compFingerprint(data)
            val bw = bounds.width(); val bh = bounds.height()
            val sizeChanged = compBmpSizes[slotIdx][0] != bw || compBmpSizes[slotIdx][1] != bh
            val dataChanged = compDataFp[slotIdx] != fp

            if (sizeChanged || dataChanged) {
                compDataFp[slotIdx] = fp
                if (compBmps[slotIdx] == null || sizeChanged) {
                    compBmps[slotIdx]?.recycle()
                    compBmps[slotIdx] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    compBmpSizes[slotIdx] = intArrayOf(bw, bh)
                }
                val bmp = compBmps[slotIdx]!!
                bmp.eraseColor(0)
                val canvas = Canvas(bmp)
                slot.renderer.render(canvas, bounds, zdt, rp, slot.id)

                if (compTexIds[slotIdx] == 0) {
                    val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0)
                    compTexIds[slotIdx] = ids[0]
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compTexIds[slotIdx])
                if (sizeChanged) {
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                }
            }

            if (compTexIds[slotIdx] != 0) {
                glPrimitives.drawTexturedQuad(mvp, compTexIds[slotIdx],
                    bounds.left.toFloat(), bounds.top.toFloat(),
                    bw.toFloat(), bh.toFloat(), 1f)
            }
        }
    }

    private fun compFingerprint(data: ComplicationData): String {
        return when (data) {
            is ShortTextComplicationData -> "s:${data.text.hashCode()}:${data.title?.hashCode()}"
            is LongTextComplicationData -> "l:${data.text.hashCode()}:${data.title?.hashCode()}"
            is RangedValueComplicationData -> "r:${data.value}:${data.min}:${data.max}:${data.text?.hashCode()}"
            else -> data.hashCode().toString()
        }
    }

    private fun updateActiveSlotIds() {
        _activeSlotIds.clear()
        for (slot in complicationSlotsManager.complicationSlots.values) {
            val d = slot.complicationData.value
            if (d !is EmptyComplicationData && d !is NoDataComplicationData && d !is NotConfiguredComplicationData) {
                _activeSlotIds.add(slot.id)
            }
        }
    }

    private fun renderAmbient(zdt: ZonedDateTime, w: Int, h: Int, cx: Float, cy: Float, ir: Float, f: Float) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val sz = (min(w, h) * GL_SCALE).toInt().coerceAtLeast(64)
        if (sz > 0 && sz != bmpSize) {
            ensureTextures(); bmpSize = sz
            earthGles.init(sz, dayTex, nightTex)
            glPrimitives.init()
            glOverlay.init()
        }

        val mvp = orthoMvp(w, h)

        if (bmpSize > 0) {
            val sz = bmpSize
            val off = ir * 0.45f
            val sunDir = sunCalc.sunDirection(
                zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
            )
            val earthVpX = (w - sz) / 2
            val earthVpY = ((h - sz) / 2 - off.toInt())
            earthGles.render(sz, CHINA_RY, sunDir, earthVpX, earthVpY)

            resetGlState()
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            glPrimitives.drawCircle(mvp, cx, cy + off, ir, floatArrayOf(0f, 0f, 0f, 1f - 180f / 255f))
        }

        val rimAlpha = 100 / 255f
        glPrimitives.drawRing(mvp, cx, cy, ir - 2f, 2f, intToGlColor(0xFFCCCCCC.toInt(), rimAlpha))

        val innerR = ir - 8f * f; val outerR = ir - 2f
        val tickColor = intToGlColor(0xFFE0E0E0.toInt(), 1f)
        for (i in 0 until 12) {
            val deg = i * 30f
            val rad = Math.toRadians((deg - 90).toDouble())
            val alpha = if (i % 3 == 0) 255 / 255f else 180 / 255f
            val width = if (i % 3 == 0) 5f * f else 3.5f * f
            val c = floatArrayOf(tickColor[0], tickColor[1], tickColor[2], alpha)
            glPrimitives.drawLine(mvp,
                cx + innerR * cos(rad).toFloat(), cy + innerR * sin(rad).toFloat(),
                cx + outerR * cos(rad).toFloat(), cy + outerR * sin(rad).toFloat(),
                width, c)
        }

        val hr = zdt.hour % 12; val mn = zdt.minute
        val handColor = intToGlColor(palette.hand, 200 / 255f)
        drawGlHand(mvp, cx, cy, ir * 0.45f, (hr + mn / 60f) * 30f, 7f * f, handColor)
        drawGlHand(mvp, cx, cy, ir * 0.68f, (mn + zdt.second / 60f) * 6f, 5f * f, handColor)

        val dotColor = floatArrayOf(1f, 0.27f, 0f, 220 / 255f)
        glPrimitives.drawCircle(mvp, cx, cy, 5f * f, dotColor)

        val off = ir * 0.45f
        val timeText = String.format(Locale.US, "%02d:%02d", zdt.hour, zdt.minute)
        timeP.textSize = ir * 0.32f
        timeP.color = palette.time
        timeP.textAlign = Paint.Align.LEFT
        timeP.setShadowLayer(0f, 0f, 0f, 0)
        val hh = String.format(Locale.US, "%02d", zdt.hour)
        val mm = String.format(Locale.US, "%02d", zdt.minute)
        val colonW = timeP.measureText(":")
        val hw = timeP.measureText(hh)
        val mw = timeP.measureText(mm)
        val totalW = hw + colonW + mw
        val baseY = cy - ir * 0.42f
        val startX = cx - totalW / 2f
        val ambAlpha = 180 / 255f

        textCache.getOrUpdate("amb_h", timeP, hh)?.let { (texId, tw, th, pad) ->
            val blt = pad.toFloat() - timeP.fontMetrics.ascent
            glPrimitives.drawTexturedQuad(mvp, texId, startX - pad.toFloat(), baseY - blt, tw.toFloat(), th.toFloat(), ambAlpha)
        }
        textCache.getOrUpdate("amb_c", timeP, ":")?.let { (texId, tw, th, pad) ->
            val blt = pad.toFloat() - timeP.fontMetrics.ascent
            glPrimitives.drawTexturedQuad(mvp, texId, startX + hw - pad.toFloat(), baseY - blt, tw.toFloat(), th.toFloat(), ambAlpha)
        }
        textCache.getOrUpdate("amb_m", timeP, mm)?.let { (texId, tw, th, pad) ->
            val blt = pad.toFloat() - timeP.fontMetrics.ascent
            glPrimitives.drawTexturedQuad(mvp, texId, startX + hw + colonW - pad.toFloat(), baseY - blt, tw.toFloat(), th.toFloat(), ambAlpha)
        }

        timeP.textAlign = Paint.Align.CENTER
        wasInteractive = false
    }

    private fun dataForSource(id: Int): Pair<Float, String> = when (id) {
        0 -> {
            val pct = batteryPct / 100f
            val low = if (batteryIsLow) "⚠" else ""
            val txt = if (isCharging) "${batteryPct}%⚡" else "$low${batteryPct}%"
            pct to txt
        }
        3 -> {
            val t = weatherFetcher.temperature
            val pct = if (!t.isNaN()) ((t + 5f) / 60f).coerceIn(0f, 1f) else 0f
            pct to weatherFetcher.display
        }
        4 -> {
            notifProvider.refresh()
            val n = notifProvider.count
            val pct = (n / 10f).coerceIn(0f, 1f)
            val txt = if (n > 0) "🔔$n" else "🔔0"
            pct to txt
        }
        6 -> {
            val uv = weatherFetcher.uvIndex
            val pct = if (uv >= 0f) (uv / 11f).coerceIn(0f, 1f) else 0f
            val txt = if (uv >= 0f) "☀${uv.toInt()}" else "UV--"
            pct to txt
        }
        8 -> {
            val fl = weatherFetcher.feelsLike
            val pct = if (!fl.isNaN()) ((fl + 5f) / 60f).coerceIn(0f, 1f) else 0f
            val txt = if (!fl.isNaN()) "体感${fl.toInt()}°" else "体感--"
            pct to txt
        }
        9 -> {
            val pp = weatherFetcher.precipProb
            val pct = if (pp >= 0) (pp / 100f).coerceIn(0f, 1f) else 0f
            val txt = if (pp >= 0) "🌧${pp}%" else "🌧--"
            pct to txt
        }
        else -> 0f to ""
    }

    private fun computeSlotPixelBounds(slotId: Int, cx: Float, cy: Float, ir: Float): Rect {
        val (xOff, yOff) = when (slotId) {
            0 -> -ir * 0.60f to -ir * 0.28f
            1 -> ir * 0.60f to -ir * 0.28f
            2 -> -ir * 0.60f to ir * 0.35f
            3 -> ir * 0.60f to ir * 0.35f
            else -> return Rect()
        }
        val hw = (ir * 0.14f).toInt().coerceIn(40, 80)
        val hh = (ir * 0.06f).toInt().coerceIn(18, 30)
        val x = (cx + xOff).toInt()
        val y = (cy + yOff).toInt()
        return Rect(x - hw, y - hh, x + hw, y + hh)
    }

    private fun dow(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "周一"; DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"; DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"; DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    private fun intToGlColor(color: Int, alpha: Float): FloatArray {
        return floatArrayOf(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            alpha
        )
    }

    private fun validateGlContext(): Boolean {
        val progId = glPrimitives.progDebug
        if (progId == 0) return false
        if (!GLES20.glIsProgram(progId)) return false
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(progId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        return linkStatus[0] == GLES20.GL_TRUE
    }

    override fun renderHighlightLayer(zdt: ZonedDateTime, sharedAssets: EarthAssets) {}

    override fun onDump(writer: PrintWriter) {
        writer.println("Earth Live GLES — state dump")
        writer.println("  accent=$accentIdx lunar=$showLunar sensors=$showSensors clouds=$showClouds")
        writer.println("  arcs=${arcDataSource.contentToString()} battery=$batteryPct% chg=$isCharging")
    }

    override fun onDestroy() {
        earthGles.release()
        glPrimitives.release()
        glOverlay.release()
        textCache.release()
        for (id in compTexIds) {
            if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
        }
        for (bmp in compBmps) bmp?.recycle()
        if (nightOverlayTex != 0) GLES20.glDeleteTextures(1, intArrayOf(nightOverlayTex), 0)
        if (dayTexLoaded) dayTex?.recycle()
        if (nightTexLoaded) nightTex?.recycle()
    }
}
