/**
 * Index.ets — 表盘主页面
 *
 * 架构角色：HarmonyOS 表盘的 UI 入口，协调 ArkUI 叠加层和 Native GL 渲染。
 *
 * 渲染架构：
 *   Stack {
 *     XComponent(TEXTURE模式) ← Native OpenGL ES 渲染（3D地球+2D表盘元素）
 *     Column(文字叠加层)      ← ArkUI 文字（时间/日期/农历），hitTestBehavior=Transparent
 *   }
 *   TEXTURE 模式允许 ArkUI 组件叠加在 GL 渲染之上（SURFACE 模式不行）。
 *
 * 渲染循环（由 ArkTS 主线程驱动，~30fps）：
 *   setInterval(33ms) → {
 *     计算时间/农历/太阳方向,
 *     updateSunDirection(),
 *     pushSceneData() → updateData() [天气/电池/通知],
 *     renderFrame() → MakeCurrent → g_scene->renderFrame() → SwapBuffers
 *   }
 *
 * 纹理加载（异步，仅一次）：
 *   loadTexturesAsync() → resourceManager.getRawFileContent() → nativerender.loadTextures()
 *   → NAPI 层 stb_image 解码 → g_scene->loadDay/Night/CloudTexture()
 *
 * 配置持久化：
 *   @StorageLink 装饰器自动同步 PersistentStorage，
 *   字符串 '0'/'1' 表示关闭/开启（showLunar, showSensors, showClouds）。
 *
 * 生命周期：
 *   aboutToAppear → 启动天气获取
 *   onLoad → 启动渲染循环 + 加载纹理 + 注册字体
 *   onPageHide → 暂停渲染循环（省电）
 *   onPageShow → 恢复渲染循环
 *   onDestroy → 停止渲染 + 销毁 Native 场景
 *
 * 已知限制（功能增强项）：
 *   - 电池信息硬编码为 0（需接入 @ohos.batteryInfo）
 *   - 通知计数硬编码为 0（需接入 @ohos.notification）
 *   - 天气位置硬编码上海（需接入 @ohos.geoLocationManager）
 *   - AOD 状态始终为 false（需监听屏幕状态）
 */

import nativerender from 'libnativerender.so'
import { SunCalculator } from '../model/SunCalculator'
import { LunarCalendar } from '../model/LunarCalendar'
import { WeatherFetcher } from '../model/WeatherFetcher'
import font from '@ohos.font'
import resourceManager from '@ohos.resourceManager'

@Entry
@Component
struct Index {
  @StorageLink('accent_color') accentColorStr: string = '0'
  @StorageLink('show_lunar') showLunarStr: string = '1'
  @StorageLink('show_sensors') showSensorsStr: string = '1'
  @StorageLink('show_clouds') showCloudsStr: string = '1'
  @StorageLink('animation_mode') animModeStr: string = '0'
  @StorageLink('arc_tl') arcTlStr: string = '0'
  @StorageLink('arc_tr') arcTrStr: string = '6'
  @StorageLink('arc_bl') arcBlStr: string = '9'
  @StorageLink('arc_br') arcBrStr: string = '8'
  @State currentTime: string = '00:00'
  @State currentDate: string = ''
  @State lunarText: string = ''
  @State gzText: string = ''
  @State isAmbient: boolean = false
  @State renderStarted: boolean = false

  private sunCalc: SunCalculator = new SunCalculator()
  private lunarCalc: LunarCalendar = new LunarCalendar()
  private weatherFetcher: WeatherFetcher = new WeatherFetcher()
  private surfaceWidth: number = 0
  private surfaceHeight: number = 0
  private animTimer: number = -1
  private fontRegistered: boolean = false
  private texturesLoaded: boolean = false

  aboutToAppear() {
    this.weatherFetcher.startFetching()
  }

  aboutToDisappear() {
    this.stopRenderLoop()
    this.weatherFetcher.stopFetching()
  }

  onPageShow() {
    if (!this.renderStarted && this.surfaceWidth > 0) {
      this.startRenderLoop()
    }
  }

  onPageHide() {
    this.stopRenderLoop()
  }

  stopRenderLoop() {
    if (this.animTimer >= 0) {
      clearInterval(this.animTimer)
      this.animTimer = -1
    }
    this.renderStarted = false
  }

  pushConfig() {
    nativerender.updateConfig({
      accentIdx: parseInt(this.accentColorStr) || 0,
      showLunar: this.showLunarStr === '1',
      showSensors: this.showSensorsStr === '1',
      showClouds: this.showCloudsStr === '1',
      animMode: parseInt(this.animModeStr) || 0,
      arcDataSource: [
        parseInt(this.arcTlStr) || 0,
        parseInt(this.arcTrStr) || 6,
        parseInt(this.arcBlStr) || 9,
        parseInt(this.arcBrStr) || 8
      ]
    })
  }

  pushSceneData() {
    nativerender.updateData({
      batteryPct: 0,
      isCharging: false,
      batteryIsLow: false,
      temperature: this.weatherFetcher.temperature,
      uvIndex: this.weatherFetcher.uvIndex,
      feelsLike: this.weatherFetcher.feelsLike,
      precipProb: this.weatherFetcher.precipProb,
      notifCount: 0
    })
  }

  registerFonts() {
    if (this.fontRegistered) return
    try {
      font.registerFont({
        familyName: 'DSEG7Modern-Bold',
        familySrc: '$rawfile/textures/fonts/DSEG7Modern-Bold.ttf'
      })
      font.registerFont({
        familyName: 'DSEG7Classic-Bold',
        familySrc: '$rawfile/textures/fonts/DSEG7Classic-Bold.ttf'
      })
      this.fontRegistered = true
    } catch (e) {
    }
  }

  async loadTexturesAsync() {
    if (this.texturesLoaded) return
    try {
      let mgr = getContext(this).resourceManager
      let dayBuf = await mgr.getRawFileContent('textures/earth_day.jpg')
      let nightBuf = await mgr.getRawFileContent('textures/earth_night.png')
      let cloudBuf = await mgr.getRawFileContent('textures/earth_cloud.png')
      nativerender.loadTextures(dayBuf.buffer, nightBuf.buffer, cloudBuf.buffer)
      this.texturesLoaded = true
    } catch (e) {
    }
  }

  startRenderLoop() {
    if (this.renderStarted) return
    this.renderStarted = true

    this.registerFonts()

    if (this.surfaceWidth <= 0 || this.surfaceHeight <= 0) {
      this.surfaceWidth = 466
      this.surfaceHeight = 466
    }

    nativerender.initScene(this.surfaceWidth, this.surfaceHeight)
    this.pushConfig()
    this.pushSceneData()
    this.loadTexturesAsync()

    this.animTimer = setInterval(() => {
      const now = new Date()
      const timeMs = now.getTime()
      this.currentTime = `${this.pad2(now.getHours())}:${this.pad2(now.getMinutes())}`
      this.currentDate = `${now.getMonth() + 1}月${now.getDate()}日 ${this.dow(now.getDay())}`
      this.lunarText = this.showLunarStr === '1' ? this.lunarCalc.toLunar(now) : ''
      this.gzText = this.showLunarStr === '1' ? this.lunarCalc.toGanzhi(now) : ''

      const sunDir = this.sunCalc.sunDirection(now)
      nativerender.updateSunDirection(sunDir)
      this.pushSceneData()
      nativerender.renderFrame(
        this.surfaceWidth, this.surfaceHeight,
        timeMs,
        now.getHours(), now.getMinutes(), now.getSeconds(),
        now.getMilliseconds() * 1000000,
        now.getMonth() + 1, now.getDate(), now.getDay(),
        this.lunarText,
        this.isAmbient
      )
    }, 33)
  }

  pad2(n: number): string {
    return n < 10 ? `0${n}` : `${n}`
  }

  dow(d: number): string {
    const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    return days[d]
  }

  build() {
    Stack() {
      XComponent({
        id: 'earthCanvas',
        type: XComponentType.TEXTURE,
        libraryname: 'nativerender'
      })
        .width('100%')
        .height('100%')
        .onLoad((context?: object) => {
          if (context) {
            let ctx = context as Record<string, number>
            this.surfaceWidth = ctx.width || 466
            this.surfaceHeight = ctx.height || 466
          }
          this.startRenderLoop()
        })
        .onDestroy(() => {
          this.stopRenderLoop()
          nativerender.destroyScene()
        })

      Column() {
        Text(this.currentTime)
          .fontSize(48)
          .fontColor(Color.White)
          .fontFamily('DSEG7Modern-Bold')
          .textAlign(TextAlign.Center)
          .shadow({ radius: 6, offsetY: 4, color: 0xCC000000 })
          .margin({ top: '18%' })

        Text(this.currentDate)
          .fontSize(16)
          .fontColor('#CAD8F0')
          .textAlign(TextAlign.Center)
          .shadow({ radius: 14, offsetY: 4, color: 0xCC000000 })
          .margin({ top: 4 })

        if (this.showLunarStr === '1' && this.lunarText) {
          Text(this.lunarText)
            .fontSize(14)
            .fontColor('#C0D4EE')
            .textAlign(TextAlign.Center)
            .shadow({ radius: 10, offsetY: 3, color: 0xBB000000 })
            .margin({ top: 2 })
        }

        if (this.showLunarStr === '1' && this.gzText) {
          Text(this.gzText)
            .fontSize(11)
            .fontColor('#A0B8D8')
            .textAlign(TextAlign.Center)
            .shadow({ radius: 8, offsetY: 2, color: 0xAA000000 })
            .margin({ top: 2 })
        }
      }
      .width('100%')
      .alignItems(HorizontalAlign.Center)
      .hitTestBehavior(HitTestMode.Transparent)
    }
    .width('100%')
    .height('100%')
  }
}
