package com.earthwatch.face

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.os.BatteryManager
import android.os.PowerManager
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
    val atmo: Int,
)

private val PALETTES = listOf(
    Palette(0xFFF5F9FF.toInt(), 0xFFF0F4FF.toInt(), 0xFFFF6B6B.toInt(), 0xFFCAD8F0.toInt(),
        0xFFC0D4EE.toInt(), 0xFFA0B8D8.toInt(), 0xFF4A90D9.toInt(),
        listOf(0xFFFF9800.toInt(), 0xFF00BFA5.toInt(), 0xFFE85D75.toInt(), 0xFF5B8DEF.toInt()),
        0xFF3296F0.toInt()),
    Palette(0xFFE8F4FD.toInt(), 0xFFD6ECFA.toInt(), 0xFF64B5F6.toInt(), 0xFFB3D9F2.toInt(),
        0xFFA8D4F0.toInt(), 0xFF80B9E0.toInt(), 0xFF0277BD.toInt(),
        listOf(0xFF4FC3F7.toInt(), 0xFF29B6F6.toInt(), 0xFF03A9F4.toInt(), 0xFF039BE5.toInt()),
        0xFF1E88E5.toInt()),
    Palette(0xFFFCFAF7.toInt(), 0xFFF5F0EB.toInt(), 0xFFBCAAA4.toInt(), 0xFFF0EBE3.toInt(),
        0xFFEDE5DB.toInt(), 0xFFDDD3C7.toInt(), 0xFFA09080.toInt(),
        listOf(0xFFF5F0EB.toInt(), 0xFFEDE4D9.toInt(), 0xFFE0D4C5.toInt(), 0xFFD4C4B0.toInt()),
        0xFFB0A090.toInt()),
    Palette(0xFFFFECE8.toInt(), 0xFFFFD4C0.toInt(), 0xFFFF5252.toInt(), 0xFFFFAB91.toInt(),
        0xFFFFAB91.toInt(), 0xFFFF8A65.toInt(), 0xFFD50000.toInt(),
        listOf(0xFFFF7043.toInt(), 0xFFE53935.toInt(), 0xFFD50000.toInt(), 0xFFBF360C.toInt()),
        0xFFFF5722.toInt()),
    Palette(0xFFE8F0E0.toInt(), 0xFFD4E0C8.toInt(), 0xFF81C784.toInt(), 0xFFB8D0A8.toInt(),
        0xFFA8CCA0.toInt(), 0xFF8DB580.toInt(), 0xFF2E7D32.toInt(),
        listOf(0xFF66BB6A.toInt(), 0xFF43A047.toInt(), 0xFF388E3C.toInt(), 0xFF2E7D32.toInt()),
        0xFF43A047.toInt()),
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
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var dayTex: Bitmap? = null
    private var nightTex: Bitmap? = null
    private fun ensureTextures() {
        if (dayTex == null) {
            try { dayTex = BitmapFactory.decodeStream(context.assets.open("textures/earth_day.jpg")) }
            catch (e: Exception) { android.util.Log.w("EarthRenderer", "Failed to load day texture", e) }
        }
        if (nightTex == null) {
            try { nightTex = BitmapFactory.decodeStream(context.assets.open("textures/earth_night.png")) }
            catch (e: Exception) { android.util.Log.w("EarthRenderer", "Failed to load night texture", e) }
        }
    }

    private var wasInteractive = false
    @Volatile private var isAnimating = false
    @Volatile private var wakeTime = 0L
    private val ANIM_MS = 1500L
    @Volatile private var slowRotMs = 0L

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
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            batteryIsLow = if (level >= 0 && scale > 0) level * 100 / scale <= 15 else false
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
        if (!configPrefs.contains("shichen_font")) edit.putString("shichen_font", "0")
        if (!configPrefs.contains("power_mode")) edit.putString("power_mode", "0")
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
    @Volatile private var animMode = 0
    private var shichenFont = 0  // 0=系统默认 1=宋体 2=关闭
    @Volatile private var powerMode = 0  // 0=平衡30fps 1=动态 2=省电1fps跳秒
    @Volatile private var isSystemPowerSave = false
    private var lastRenderMs = 0L
    private var lastPowerSaveCheckMs = 0L

    /** 当系统省电模式开启时，自动降级到最省电模式（2），忽略用户配置。 */
    private val effectivePowerMode: Int get() = if (isSystemPowerSave) 2 else powerMode

    /** 省电降级：系统省电时跳过云层、大气、晨昏线等重 GPU 特效。 */
    private val effectiveShowClouds: Boolean get() = showClouds && !isSystemPowerSave
    private val arcDataSource = IntArray(4) { 0 }
    private val arcDisplayPct = FloatArray(4) { 0f }  // 动画当前值
    private val arcTargetPct = FloatArray(4) { 0f }    // 目标值
    private var lastArcAnimMs = 0L
    private var lastStyleReadMs = 0L

    private fun loadPrefs() {
        accentIdx = prefGet("accent_color")
        showLunar = prefGet("show_lunar") == 0
        showSensors = prefGet("show_sensors") == 0
        arcDataSource[0] = prefGet("arc_tl")
        arcDataSource[1] = prefGet("arc_tr")
        arcDataSource[2] = prefGet("arc_bl")
        arcDataSource[3] = prefGet("arc_br")
        showClouds = prefGet("show_clouds") == 0
        animMode = prefGet("animation_mode")
        shichenFont = prefGet("shichen_font")
        powerMode = prefGet("power_mode")
        val fontStyle = prefGet("font_style")
        timeP.typeface = when (fontStyle) { 1 -> fontClassic; 2 -> fontDefault; 3 -> fontLight; else -> fontModern }
        palette = PALETTES[accentIdx.coerceIn(0, PALETTES.lastIndex)]
    }

    private fun readStyle() {
        loadPrefs()
        val now = System.currentTimeMillis()
        if (now - lastStyleReadMs < 500L) return
        lastStyleReadMs = now
        val style = curStyleRepo.userStyle.value
        val fp = computeFingerprint(style)
        if (fp != lastStyleFingerprint) {
            lastStyleFingerprint = fp
            syncStyleToPrefs(style)
            loadPrefs()
        }
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
    private val fontModern by lazy { Typeface.createFromAsset(context.assets, "fonts/DSEG7Modern-Bold.ttf") }
    private val fontClassic by lazy { Typeface.createFromAsset(context.assets, "fonts/DSEG7Classic-Bold.ttf") }
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
    private val compLastData = arrayOfNulls<ComplicationData>(4)

    private var cloudLoaded = false
    private var cloudRenderLogged = false

    private var atmoLoaded = false
    private var atmoIr = 0f
    private var atmoPaletteIdx = -1

    private var nightOverlayTex = 0
    private var lastNightAng = Float.NaN
    private var lastNightIr = Float.NaN
    private var lastNightOverlayBuildMs = 0L
    private val NIGHT_OVERLAY_REFRESH_MS = 30 * 60 * 1000L

    private val sunDirBuf = FloatArray(3)
    private val shadowHandColor = floatArrayOf(0f, 0f, 0f, 0.38f)
    private val centerDotColor = floatArrayOf(1f, 0.27f, 0f, 1f)
    private var nightDimFactor = 1f  // 1=白天, 0.6=深夜, 平滑过渡

    private var cachedMinuteKey = -1L
    private var cachedDateText = ""
    private var cachedLunarText = ""
    private var cachedGzText = ""
    private var cachedSolarTerm = ""
    private var cachedTimeKey = -1L
    private var cachedTimeText = ""
    private var cachedTimeHh = ""
    private var cachedTimeMm = ""
    private val sensorTextsArr = Array(4) { "" }
    private val compRenderParams = RenderParameters(
        drawMode = DrawMode.INTERACTIVE,
        watchFaceLayers = setOf(WatchFaceLayer.COMPLICATIONS)
    )

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
            lastNightAng = Float.NaN; lastNightIr = Float.NaN; nightOverlayTex = 0
            for (i in compTexIds.indices) { compTexIds[i] = 0 }
            compDataFp = Array(4) { "" }
            for (i in compLastData.indices) { compLastData[i] = null }
        }

        if (!isInteractive) {
            loadPrefs()
            renderAmbient(zdt, w, h, cx, cy, ir, f)
            return
        }

        readStyle()

        if (System.currentTimeMillis() - lastPowerSaveCheckMs > 2000L) {
            val newPowerSave = powerManager?.isPowerSaveMode == true
            if (newPowerSave != isSystemPowerSave) {
                android.util.Log.i("EarthRenderer", "PowerSave changed: $isSystemPowerSave -> $newPowerSave")
                isSystemPowerSave = newPowerSave
                // 状态变化时立即恢复：清空节流计时器，让下一帧立即渲染
                lastRenderMs = 0L
                lastArcAnimMs = 0L
            }
            lastPowerSaveCheckMs = System.currentTimeMillis()
        }

        val nowMs = System.currentTimeMillis()
        val effMode = effectivePowerMode
        val targetInterval = when (effMode) {
            0 -> 33L
            1 -> if (isAnimating) 20L else 33L
            2 -> if (isAnimating) 33L else 1000L
            else -> 33L
        }
        if (lastRenderMs != 0L && nowMs - lastRenderMs < targetInterval) return
        lastRenderMs = nowMs
        weatherFetcher.ensureFresh()
        notifProvider.refresh()
        if (System.currentTimeMillis() - lastBatteryMs > BATTERY_READ_MS) {
            readBattery(); lastBatteryMs = System.currentTimeMillis()
        }

        val sz = (min(w, h) * GL_SCALE).toInt().coerceAtLeast(64)
        if (sz > 0 && sz != bmpSize) {
            initGlResources(sz)
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

        sunCalc.sunDirection(
            zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), sunDirBuf
        )
        val ang = atan2(sunDirBuf[0].toDouble(), sunDirBuf[2].toDouble()).toFloat()
        // 夜晚文字降亮：sunDir.y > 0 = 北半球白天, < 0 = 夜晚
        val sunY = sunDirBuf[1]
        val targetDim = if (sunY > 0.1f) 1f else if (sunY < -0.1f) 0.65f else 0.65f + 0.35f * ((sunY + 0.1f) / 0.2f)
        nightDimFactor = targetDim

        val minuteKey = zdt.toLocalDate().toEpochDay() * 1440L + zdt.hour * 60L + zdt.minute
        if (minuteKey != cachedTimeKey) {
            cachedTimeKey = minuteKey
            cachedTimeHh = String.format(Locale.US, "%02d", zdt.hour)
            cachedTimeMm = String.format(Locale.US, "%02d", zdt.minute)
            cachedTimeText = "$cachedTimeHh:$cachedTimeMm"
        }
        val (dateText, lunarText, gzText) = dateStrings(zdt)
        updateActiveSlotIds()

        for (i in 0..3) {
            val ds = arcDataSource[i]
            sensorTextsArr[i] = if (ds == 99 || ARC_SLOTS[i].slotId in _activeSlotIds) "" else dataForSource(ds).second
        }

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0.024f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // ── 3D地球 ──
        val earthVpX = (w - sz) / 2
        val earthVpY = ((h - sz) / 2 - off.toInt())
        earthGles.render(sz, ry, sunDirBuf, earthVpX, earthVpY)

        resetGlState()

        // ── 2D叠加层（正交投影） ──
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val mvp = orthoMvp(w, h)

        // 云层 + 晨昏线 + 大气（省电降级：系统省电时跳过重 GPU 特效）
        val rotYRad = Math.toRadians(ry.toDouble()).toFloat()
        if (effectiveShowClouds && glOverlay.cloudInited) {
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
            android.util.Log.i("EarthRenderer", "Clouds NOT rendering: showClouds=$showClouds effClouds=$effectiveShowClouds powerSave=$isSystemPowerSave cloudInited=${glOverlay.cloudInited}")
        }
        if (!isSystemPowerSave) {
            ensureAtmoTexture(ir)
            if (atmoLoaded) {
                val ar = ir * 1.20f
                glOverlay.drawAtmosphere(mvp, cx, cy + off, ar, 1f)
            }
        }
        drawNightOverlay(mvp, cx, cy + off, ir, ang, rotYRad)

        // 表盘元素：边缘 + 刻度 + 弧形 + 指针 + 文字 + 复杂功能
        drawGlRim(mvp, cx, cy, ir)
        drawGlRimTicks(mvp, cx, cy, ir, f)
        drawGlArcs(mvp, cx, cy, ir, f)
        drawGlHands(mvp, cx, cy, ir, f, zdt)
        drawGlTime(mvp, cx, cy, ir, f, cachedTimeHh, cachedTimeMm)
        drawGlDate(mvp, cx, cy, ir, f, dateText, lunarText, gzText)
        if (showSensors) drawGlSensors(mvp, cx, cy, ir, f, sensorTextsArr)
        drawGlComplications(mvp, cx, cy, ir, zdt)
        drawPowerModeIndicator(mvp, cx, cy, ir, f)

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
        if (atmoIr == ir && atmoLoaded && atmoPaletteIdx == accentIdx) return
        atmoPaletteIdx = accentIdx
        val d = (ir * 2.4f).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tcx = d / 2f; val tcy = d / 2f; val tr = d / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 主题色大气辉光：从 palette.atmo 派生
        val ar = Color.red(palette.atmo)
        val ag = Color.green(palette.atmo)
        val ab = Color.blue(palette.atmo)
        p.shader = RadialGradient(tcx, tcy, tr,
            intArrayOf(
                Color.argb(0, ar, ag, ab), Color.argb(0, ar, ag, ab),
                Color.argb(45, ar, ag, ab), Color.argb(85, ar, ag, ab),
                Color.argb(135, ar, ag, ab), Color.argb(0, ar, ag, ab)
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

    private fun initGlResources(sz: Int) {
        ensureTextures()
        bmpSize = sz
        android.util.Log.i("EarthRenderer", "init GL sz=$sz")
        earthGles.init(sz, dayTex, nightTex)
        glPrimitives.init()
        glOverlay.init()
        cloudLoaded = false; initCloudTexture()
        atmoIr = 0f; atmoLoaded = false
        lastNightAng = Float.NaN; lastNightIr = Float.NaN
        android.util.Log.i("EarthRenderer", "GL init done progEarth=${earthGles.progEarthDebug} prog2d=${glPrimitives.progDebug}")
    }

    private fun dateStrings(zdt: ZonedDateTime): Triple<String, String, String> {
        val key = zdt.toLocalDate().toEpochDay() * 1440L + zdt.hour * 60L + zdt.minute
        if (key != cachedMinuteKey) {
            cachedMinuteKey = key
            val localDate = zdt.toLocalDate()
            cachedDateText = "${zdt.monthValue}月${zdt.dayOfMonth}日 ${dow(zdt.dayOfWeek)}"
            cachedLunarText = if (showLunar) lunarCalc.toLunar(localDate) else ""
            cachedGzText = if (showLunar) lunarCalc.toGanzhi(localDate) else ""
            cachedSolarTerm = lunarCalc.currentSolarTerm(localDate)
        }
        return Triple(cachedDateText, cachedLunarText, cachedGzText)
    }

    private fun drawNightOverlay(mvp: FloatArray, cx: Float, cy: Float, ir: Float, ang: Float, rotY: Float) {
        val needRebuild = if (effectivePowerMode == 2) {
            lastNightAng.isNaN() || ir != lastNightIr ||
            System.currentTimeMillis() - lastNightOverlayBuildMs > NIGHT_OVERLAY_REFRESH_MS
        } else {
            !ang.equals(lastNightAng) || ir != lastNightIr
        }
        if (needRebuild) {
            lastNightOverlayBuildMs = System.currentTimeMillis()
            lastNightAng = ang
            lastNightIr = ir
            val d = (ir * 2.4f).toInt().coerceAtLeast(64)
            val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val tcx = d / 2f; val tcy = d / 2f; val tr = d / 2f
            val gdx = tr * 0.75f * cos(ang).toFloat()
            val gdy = -tr * 0.75f * sin(ang).toFloat()
            val np = Paint(Paint.ANTI_ALIAS_FLAG)
            // 晨昏线染橙：在过渡带加入暖色调（日出日落橙红）
            np.shader = LinearGradient(
                tcx + gdx, tcy + gdy, tcx - gdx, tcy - gdy,
                intArrayOf(
                    0x00000000, 0x00000000,
                    0x28FF8C00.toInt(), 0x38005060.toInt(), 0x60000000.toInt()
                ),
                floatArrayOf(0.0f, 0.52f, 0.60f, 0.68f, 1.0f),
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

    private fun drawPowerModeIndicator(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float) {
        if (!isSystemPowerSave && effectivePowerMode != 2) return
        // 省电指示：右上角小圆点（呼吸效果）
        val breath = if (isSystemPowerSave) {
            0.4f + 0.3f * (0.5f - 0.5f * cos(System.currentTimeMillis() / 1000f * 2f * PI.toFloat()))
        } else 0.5f
        val ix = cx + ir * 0.82f
        val iy = cy - ir * 0.82f
        glPrimitives.drawCircle(mvp, ix, iy, 3f * f,
            floatArrayOf(0.3f, 0.9f, 0.3f, breath))
    }

    private fun drawGlRim(mvp: FloatArray, cx: Float, cy: Float, ir: Float) {
        // 主圆环：3 层由外到内渐淡，模拟金属厚度
        for (k in 0..2) {
            val alpha = ((2 - k) * 10 / 2) / 255f
            val color = intToGlColor(palette.rim, alpha)
            val strokeWidth = 2f + k * 1.5f
            glPrimitives.drawRing(mvp, cx, cy, ir - 2f, strokeWidth, color)
        }
        // 内侧细装饰环（高亮 1px）
        glPrimitives.drawRing(mvp, cx, cy, ir - 7f, 1f, intToGlColor(palette.rim, 0.18f))

        // 电池低时外圈红色脉冲（2 秒周期）
        if (batteryIsLow && !isCharging) {
            val pulse = (System.currentTimeMillis() % 2000L) / 2000f
            val pulseAlpha = (0.25f + 0.35f * (0.5f - 0.5f * cos(pulse * 2f * PI.toFloat())))
            glPrimitives.drawRing(mvp, cx, cy, ir + 2f, 2.5f,
                floatArrayOf(1f, 0.15f, 0.1f, pulseAlpha))
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
        val nowMs = System.currentTimeMillis()
        val dtMs = if (lastArcAnimMs > 0) (nowMs - lastArcAnimMs).coerceAtMost(100L) else 16L
        lastArcAnimMs = nowMs
        // 缓动：每帧朝目标值靠近，300ms 到达 ~95%
        val lerp = 1f - Math.pow(0.05, (dtMs / 300f).toDouble()).toFloat()

        for ((slotIdx, slot) in ARC_SLOTS.withIndex()) {
            val ds = arcDataSource[slotIdx]
            if (ds == 99) continue
            val (pct, _) = dataForSource(ds)
            val effectivePct = if (slot.slotId in _activeSlotIds) 0f else pct
            arcTargetPct[slotIdx] = effectivePct
            // 动画缓动
            arcDisplayPct[slotIdx] += (arcTargetPct[slotIdx] - arcDisplayPct[slotIdx]) * lerp
            val displayPct = arcDisplayPct[slotIdx]
            val color = palette.arcs.getOrElse(slotIdx) { 0xFF42A5F5.toInt() }

            val startRad = Math.toRadians(slot.startAngle.toDouble()).toFloat()
            val sweepRad = Math.toRadians((slot.endAngle - slot.startAngle).toDouble()).toFloat()

            val trackColor = intToGlColor(color, 25 / 255f)
            glPrimitives.drawArc(mvp, cx, cy, ar, startRad, sweepRad, aw, trackColor)

            if (displayPct > 0.005f) {
                val fillRad = sweepRad * displayPct.coerceIn(0f, 1f)
                val glowColor = intToGlColor(color, 35 / 255f)
                glPrimitives.drawArc(mvp, cx, cy, ar, startRad, fillRad, aw * 2.2f, glowColor)
                val fillColor = intToGlColor(color, 1f)
                glPrimitives.drawSdfArc(mvp, cx, cy, ar, startRad, fillRad, aw, fillColor)
            }
        }
    }

    private fun drawGlHands(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, zdt: ZonedDateTime) {
        val h = zdt.hour % 12; val m = zdt.minute; val s = zdt.second
        val ms = if (effectivePowerMode == 2) 0f else zdt.nano / 1_000_000_000f

        val hourDeg = (h + m / 60f) * 30f
        val minDeg = (m + s / 60f + ms / 60f) * 6f
        val secDeg = (s + ms) * 6f

        val sdx = 1.2f * f; val sdy = 2f * f

        val hourWidth = 7f * f; val hourLen = ir * 0.45f
        drawGlHand(mvp, cx + sdx, cy + sdy, hourLen, hourDeg, hourWidth * 1.3f, shadowHandColor)
        drawGlHand(mvp, cx, cy, hourLen, hourDeg, hourWidth, intToGlColor(palette.hand, 1f))

        val minWidth = 5f * f; val minLen = ir * 0.68f
        drawGlHand(mvp, cx + sdx, cy + sdy, minLen, minDeg, minWidth * 1.3f, shadowHandColor)
        drawGlHand(mvp, cx, cy, minLen, minDeg, minWidth, intToGlColor(palette.hand, 1f))

        val secWidth = 3.5f * f; val secLen = ir * 0.78f
        drawGlHand(mvp, cx + sdx, cy + sdy, secLen, secDeg, secWidth * 1.3f, shadowHandColor)
        drawGlHand(mvp, cx, cy, secLen, secDeg, secWidth, intToGlColor(palette.second, 1f))

        glPrimitives.drawCircle(mvp, cx + sdx, cy + sdy, 5f * f * 1.3f, shadowHandColor)
        glPrimitives.drawCircle(mvp, cx, cy, 5f * f, centerDotColor)
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

    private fun drawGlTime(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, hh: String, mm: String) {
        timeP.textSize = ir * 0.32f
        val baseY = cy - ir * 0.42f

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

        // 阴影颜色跟随主题暗色 + 夜晚降亮
        val shadowColor = (palette.rim and 0xFF000000.toInt()) or ((palette.rim shr 16 and 0xFF) / 3 shl 16) or ((palette.rim shr 8 and 0xFF) / 3 shl 8) or (palette.rim and 0xFF) / 3
        timeP.color = dimColor(palette.time, nightDimFactor)
        timeP.setShadowLayer(8f * f, 0f, 3f * f, shadowColor)

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
        val shadowC = 0xCC000000.toInt()
        gregP.color = dimColor(palette.date, nightDimFactor); gregP.textSize = ir * 0.13f
        gregP.setShadowLayer(14f * f, 0f, 4f * f, shadowC)
        textCache.getOrUpdate("date", gregP, dateText)?.let { (texId, tw, th, pad) ->
            val blt = pad.toFloat() - gregP.fontMetrics.ascent
            glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, by - blt, tw.toFloat(), th.toFloat(), 1f)
        }

        if (showLunar) {
            lunarP.color = dimColor(palette.lunar, nightDimFactor); lunarP.textSize = ir * 0.11f
            lunarP.setShadowLayer(10f * f, 0f, 3f * f, shadowC)
            textCache.getOrUpdate("lunar", lunarP, lunarText)?.let { (texId, tw, th, pad) ->
                val ly = by + ir * 0.16f
                val blt = pad.toFloat() - lunarP.fontMetrics.ascent
                glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, ly - blt, tw.toFloat(), th.toFloat(), 1f)
            }
            // 干支行：当天是节气时显示节气名，否则显示干支
            val gzDisplay = if (cachedSolarTerm.isNotEmpty()) cachedSolarTerm else gzText
            gzP.color = dimColor(palette.gz, nightDimFactor); gzP.textSize = ir * 0.085f
            gzP.setShadowLayer(8f * f, 0f, 2f * f, shadowC)
            textCache.getOrUpdate("gz", gzP, gzDisplay)?.let { (texId, tw, th, pad) ->
                val gy = by + ir * 0.30f
                val blt = pad.toFloat() - gzP.fontMetrics.ascent
                glPrimitives.drawTexturedQuad(mvp, texId, cx - tw / 2f, gy - blt, tw.toFloat(), th.toFloat(), 1f)
            }
        }
    }

    private fun drawGlSensors(mvp: FloatArray, cx: Float, cy: Float, ir: Float, f: Float, sensorTexts: Array<String>) {
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
        val rp = compRenderParams
        for (slot in complicationSlotsManager.complicationSlots.values) {
            val data = slot.complicationData.value
            if (data is EmptyComplicationData || data is NoDataComplicationData || data is NotConfiguredComplicationData) continue
            val bounds = computeSlotPixelBounds(slot.id, cx, cy, ir)
            if (bounds.isEmpty) continue

            val slotIdx = slot.id
            if (slotIdx < 0 || slotIdx >= compTexIds.size) continue

            val lastData = compLastData[slotIdx]
            compLastData[slotIdx] = data
            val fp = if (lastData !== data) compFingerprint(data) else compDataFp[slotIdx]
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
                // 传入主题色
                val compRenderer = slot.renderer
                if (compRenderer is EarthCanvasComplication) {
                    compRenderer.textColor = dimColor(palette.time, nightDimFactor)
                    compRenderer.titleColor = dimColor(palette.lunar, nightDimFactor)
                    compRenderer.accentColor = palette.arcs.getOrElse(slotIdx) { palette.rim }
                }
                compRenderer.render(canvas, bounds, zdt, rp, slot.id)

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
        if (System.currentTimeMillis() - lastPowerSaveCheckMs > 2000L) {
            val newPowerSave = powerManager?.isPowerSaveMode == true
            if (newPowerSave != isSystemPowerSave) {
                android.util.Log.i("EarthRenderer", "PowerSave changed (ambient): $isSystemPowerSave -> $newPowerSave")
                isSystemPowerSave = newPowerSave
            }
            lastPowerSaveCheckMs = System.currentTimeMillis()
        }

        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val sz = (min(w, h) * GL_SCALE).toInt().coerceAtLeast(64)
        if (sz > 0 && sz != bmpSize) {
            initGlResources(sz)
        }

        val mvp = orthoMvp(w, h)

        if (bmpSize > 0) {
            val earthSz = bmpSize
            val off = ir * 0.45f
            sunCalc.sunDirection(
                zdt.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), sunDirBuf
            )
            val earthVpX = (w - earthSz) / 2
            val earthVpY = ((h - earthSz) / 2 - off.toInt())
            earthGles.render(earthSz, CHINA_RY, sunDirBuf, earthVpX, earthVpY, sleepMode = true)

            resetGlState()
            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        drawShichenRing(mvp, cx, cy, ir, zdt)

        wasInteractive = false
    }

    private val shichenNames = arrayOf("子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥")
    private val shenP = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val shenTypeDefault = Typeface.DEFAULT_BOLD
    private val shenTypeSerif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

    private fun drawShichenRing(mvp: FloatArray, cx: Float, cy: Float, ir: Float, zdt: ZonedDateTime) {
        if (shichenFont == 2) return  // 用户关闭了时辰显示
        shenP.typeface = if (shichenFont == 1) shenTypeSerif else shenTypeDefault
        val shichen = ((zdt.hour + 1) % 24) / 2
        val ringOuterR = ir * 0.935f
        val ringInnerR = ringOuterR - 15f
        val step = (Math.PI * 2.0 / 12.0).toFloat()
        val baseAngle = (-Math.PI / 2.0).toFloat() // 子在上方

        // 双环（加粗 + 15px 间距）— 系统省电时降低轨道亮度
        val ringDim = if (isSystemPowerSave) 0.5f else 1f
        glPrimitives.drawRing(mvp, cx, cy, ringOuterR, 2.5f,
            floatArrayOf(0.71f, 0.75f, 0.82f, 0.24f * ringDim))
        glPrimitives.drawRing(mvp, cx, cy, ringInnerR, 2.5f,
            floatArrayOf(0.59f, 0.63f, 0.71f, 0.20f * ringDim))

        for (i in 0 until 12) {
            val a = baseAngle + step * i
            val cosA = Math.cos(a.toDouble()).toFloat()
            val sinA = Math.sin(a.toDouble()).toFloat()
            val isCur = i == shichen
            val dist = ((i - shichen + 6) % 12 - 6).let { if (it < 0) -it else it }

            // ── 刻度点 ── 系统省电时降低非当前时辰的亮度
            val ptDim = if (isSystemPowerSave) 0.5f else 1f
            val (ptAlpha, ptSize) = when (dist) {
                0 -> 0.92f to 4.5f
                1 -> 0.45f to 3.0f
                2 -> 0.25f to 2.5f
                3 -> 0.14f to 2.0f
                4 -> 0.08f to 1.5f
                5 -> 0.04f to 1.2f
                else -> 0.02f to 1.0f
            }
            val dotR = (ringOuterR + ringInnerR) / 2f  // 点在双环中间
            val dotX = cx + dotR * cosA; val dotY = cy + dotR * sinA
            if (isCur) {
                glPrimitives.drawCircle(mvp, dotX, dotY, 7f,
                    floatArrayOf(1f, 0.82f, 0.61f, 0.55f * (if (isSystemPowerSave) 0.7f else 1f)))
                glPrimitives.drawCircle(mvp, dotX, dotY, ptSize,
                    floatArrayOf(1f, 0.90f, 0.75f, 0.95f * (if (isSystemPowerSave) 0.8f else 1f)))
            } else {
                glPrimitives.drawCircle(mvp, dotX, dotY, ptSize,
                    floatArrayOf(0.65f, 0.71f, 0.78f, ptAlpha * ptDim))
            }

            // ── 时辰字（内环内侧 20px，垂直于圆边面向圆心）──
            val textR = ringInnerR - 20f
            val fontSize: Float; val colorR: Int; val colorG: Int; val colorB: Int; val alpha: Float
            when (dist) {
                0 -> { fontSize = 43f; colorR = 0xF0; colorG = 0xD0; colorB = 0xAA; alpha = 1.0f }
                1 -> { fontSize = 32f; colorR = 0xB8; colorG = 0xC8; colorB = 0xDD; alpha = 0.65f }
                2 -> { fontSize = 26f; colorR = 0x90; colorG = 0xA0; colorB = 0xBB; alpha = 0.36f }
                3 -> { fontSize = 21f; colorR = 0x6A; colorG = 0x7A; colorB = 0x98; alpha = 0.20f }
                4 -> { fontSize = 18f; colorR = 0x4E; colorG = 0x5E; colorB = 0x7A; alpha = 0.12f }
                5 -> { fontSize = 16f; colorR = 0x38; colorG = 0x48; colorB = 0x62; alpha = 0.07f }
                else -> { fontSize = 14f; colorR = 0x2E; colorG = 0x3E; colorB = 0x55; alpha = 0.05f }
            }
            shenP.textSize = fontSize
            val effAlpha = if (isSystemPowerSave) alpha * 0.6f else alpha
            shenP.color = android.graphics.Color.argb((effAlpha * 255f).toInt(), colorR, colorG, colorB)
            shenP.setShadowLayer(if (isCur) 2f else 0f, 0.8f, 1.2f,
                if (isCur) 0x80000000.toInt() else 0)
            val cxText = cx + textR * cosA; val cyText = cy + textR * sinA
            val cacheKey = "sR_${i}_${fontSize}_${colorR}_${colorG}_${colorB}_${(effAlpha*100).toInt()}"
            textCache.getOrUpdate(cacheKey, shenP, shichenNames[i])?.let { (texId, tw, th, _) ->
                // 旋转角 = 字符所在方位 + 90°，使文字面向圆心
                val rotAngle = a + (Math.PI / 2.0).toFloat()
                glPrimitives.drawTexturedQuadRotated(mvp, texId, cxText, cyText,
                    tw.toFloat(), th.toFloat(), rotAngle, 1f)
            }
        }
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

    /** 按因子降低颜色亮度（1=原色, 0.5=半亮），保留 alpha。 */
    private fun dimColor(color: Int, factor: Float): Int {
        val a = color and 0xFF000000.toInt()
        val r = ((color shr 16 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
        return a or (r shl 16) or (g shl 8) or b
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
        writer.println("  powerMode=$powerMode effMode=$effectivePowerMode sysPowerSave=$isSystemPowerSave")
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
        dayTex?.recycle()
        nightTex?.recycle()
    }
}
