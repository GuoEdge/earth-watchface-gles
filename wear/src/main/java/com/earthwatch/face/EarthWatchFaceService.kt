package com.earthwatch.face

import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.*
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer

class EarthWatchFaceService : WatchFaceService() {

    companion object {
        val ACCENT_COLOR = ListUserStyleSetting(
            id = UserStyleSetting.Id("accent_color"),
            displayName = "主题色",
            description = "",
            icon = null,
            options = listOf(
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("0"), "默认多彩", "", null
                ),
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("1"), "冰蓝", "", null
                ),
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("2"), "纯白", "", null
                ),
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("3"), "烈焰", "", null
                ),
                ListUserStyleSetting.ListOption(
                    UserStyleSetting.Option.Id("4"), "森绿", "", null
                ),
            ),
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        private val ON_OFF = listOf(
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "开启", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), "关闭", "", null),
        )

        val SHOW_LUNAR = ListUserStyleSetting(
            id = UserStyleSetting.Id("show_lunar"),
            displayName = "显示农历",
            description = "",
            icon = null,
            options = ON_OFF,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        val SHOW_SENSORS = ListUserStyleSetting(
            id = UserStyleSetting.Id("show_sensors"),
            displayName = "显示传感器",
            description = "",
            icon = null,
            options = ON_OFF,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        // Intentionally excluded from UserStyleSchema — Samsung One UI Watch
        // only shows 8 items. Controlled via EarthConfigActivity instead.
        val ANIMATION_MODE = ListUserStyleSetting(
            id = UserStyleSetting.Id("animation_mode"),
            displayName = "动画模式",
            description = "",
            icon = null,
            options = listOf(
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "点击动画", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), "慢速旋转", "", null),
            ),
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        val SHOW_CLOUDS = ListUserStyleSetting(
            id = UserStyleSetting.Id("show_clouds"),
            displayName = "云层效果",
            description = "",
            icon = null,
            options = ON_OFF,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        val FONT_STYLE = ListUserStyleSetting(
            id = UserStyleSetting.Id("font_style"),
            displayName = "字体样式",
            description = "",
            icon = null,
            options = listOf(
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "现代数码", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), "复古经典", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), "系统默认", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), "纤细字体", "", null),
            ),
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        // Data-source IDs:
        // 0=Battery, 3=Weather, 4=Notifications, 6=UV Index, 8=FeelsLike, 9=PrecipProb, 99=Off
        private const val OFF = "99"

        private val ARC_OPTS_TL = listOf(
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "电量", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("6"), "紫外线", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("9"), "降水概率", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("8"), "体感温度", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), "天气", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("4"), "通知", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id(OFF), "关闭", "", null),
        )

        private val ARC_OPTS_TR = listOf(
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("6"), "紫外线", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "电量", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("9"), "降水概率", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("8"), "体感温度", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), "天气", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("4"), "通知", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id(OFF), "关闭", "", null),
        )

        private val ARC_OPTS_BL = listOf(
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("9"), "降水概率", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("8"), "体感温度", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "电量", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("6"), "紫外线", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), "天气", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("4"), "通知", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id(OFF), "关闭", "", null),
        )

        private val ARC_OPTS_BR = listOf(
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("8"), "体感温度", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("9"), "降水概率", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "电量", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("6"), "紫外线", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("3"), "天气", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("4"), "通知", "", null),
            ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id(OFF), "关闭", "", null),
        )

        val ARC_TOPLEFT = ListUserStyleSetting(
            id = UserStyleSetting.Id("arc_tl"), displayName = "左上弧数据", description = "",
            icon = null, options = ARC_OPTS_TL,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )
        val ARC_TOPRIGHT = ListUserStyleSetting(
            id = UserStyleSetting.Id("arc_tr"), displayName = "右上弧数据", description = "",
            icon = null, options = ARC_OPTS_TR,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )
        val ARC_BOTLEFT = ListUserStyleSetting(
            id = UserStyleSetting.Id("arc_bl"), displayName = "左下弧数据", description = "",
            icon = null, options = ARC_OPTS_BL,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )
        val ARC_BOTRIGHT = ListUserStyleSetting(
            id = UserStyleSetting.Id("arc_br"), displayName = "右下弧数据", description = "",
            icon = null, options = ARC_OPTS_BR,
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        val SHICHEN_FONT = ListUserStyleSetting(
            id = UserStyleSetting.Id("shichen_font"),
            displayName = "时辰字体",
            description = "",
            icon = null,
            options = listOf(
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "系统默认", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), "宋体", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), "关闭", "", null),
            ),
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        val POWER_MODE = ListUserStyleSetting(
            id = UserStyleSetting.Id("power_mode"),
            displayName = "功耗模式",
            description = "",
            icon = null,
            options = listOf(
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("0"), "平衡 (30fps)", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("1"), "动态 (动画50fps)", "", null),
                ListUserStyleSetting.ListOption(UserStyleSetting.Option.Id("2"), "省电 (1fps跳秒)", "", null),
            ),
            affectsWatchFaceLayers = setOf(WatchFaceLayer.BASE)
        )

        /** Helper: get string id from ListOption */
        fun optionId(option: UserStyleSetting.Option?): Int {
            if (option !is ListUserStyleSetting.ListOption) return 0
            return option.id.value.decodeToString().toIntOrNull() ?: 0
        }
    }

    override fun createUserStyleSchema(): UserStyleSchema {
        val settings = listOf(
            ACCENT_COLOR, SHOW_LUNAR, SHOW_SENSORS,
            ARC_TOPLEFT, ARC_TOPRIGHT, ARC_BOTLEFT, ARC_BOTRIGHT,
            FONT_STYLE,
        )
        val schema = UserStyleSchema(settings)
        Log.d("EarthWatchFace", "Schema created with ${schema.userStyleSettings.size} settings")
        return schema
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        val renderer = EarthRenderer(
            surfaceHolder, currentUserStyleRepository, watchState, 20L, applicationContext,
            complicationSlotsManager
        )
        return WatchFace(
            watchFaceType = WatchFaceType.ANALOG,
            renderer = renderer
        ).setTapListener(object : WatchFace.TapListener {
            override fun onTapEvent(
                tapType: Int,
                tapEvent: TapEvent,
                complicationSlot: ComplicationSlot?
            ) {
                if (tapType == TapType.UP && complicationSlot == null) {
                    renderer.requestSpin()
                }
            }
        })
    }

    override fun createComplicationSlotsManager(
        currentUserStyleRepository: CurrentUserStyleRepository
    ): ComplicationSlotsManager {
        val supportedTypes = listOf(
            ComplicationType.SHORT_TEXT,
            ComplicationType.RANGED_VALUE,
            ComplicationType.LONG_TEXT
        )

        fun slotBounds(l: Float, t: Float, r: Float, b: Float) =
            ComplicationSlotBounds(
                ComplicationType.entries.associateWith { RectF(l, t, r, b) }
            )

        fun defaultPolicy() = DefaultComplicationDataSourcePolicy()

        val slots = listOf(
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id = 0,
                canvasComplicationFactory = CanvasComplicationFactory { _, _ ->
                    EarthCanvasComplication()
                },
                supportedTypes = supportedTypes,
                defaultDataSourcePolicy = defaultPolicy(),
                bounds = slotBounds(0.13f, 0.33f, 0.30f, 0.41f)
            ).build(),
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id = 1,
                canvasComplicationFactory = CanvasComplicationFactory { _, _ ->
                    EarthCanvasComplication()
                },
                supportedTypes = supportedTypes,
                defaultDataSourcePolicy = defaultPolicy(),
                bounds = slotBounds(0.70f, 0.33f, 0.87f, 0.41f)
            ).build(),
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id = 2,
                canvasComplicationFactory = CanvasComplicationFactory { _, _ ->
                    EarthCanvasComplication()
                },
                supportedTypes = supportedTypes,
                defaultDataSourcePolicy = defaultPolicy(),
                bounds = slotBounds(0.13f, 0.63f, 0.30f, 0.71f)
            ).build(),
            ComplicationSlot.createRoundRectComplicationSlotBuilder(
                id = 3,
                canvasComplicationFactory = CanvasComplicationFactory { _, _ ->
                    EarthCanvasComplication()
                },
                supportedTypes = supportedTypes,
                defaultDataSourcePolicy = defaultPolicy(),
                bounds = slotBounds(0.70f, 0.63f, 0.87f, 0.71f)
            ).build()
        )

        return ComplicationSlotsManager(slots, currentUserStyleRepository)
    }
}
