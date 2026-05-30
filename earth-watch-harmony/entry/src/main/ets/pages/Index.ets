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
 * 触摸交互：
 *   - 点击（Tap）→ requestSpin() 触发地球旋转动画（720° easeOutCubic）
 *   - 拖拽（Drag）→ 实时旋转地球，手指水平移动控制经度旋转
 *   触摸事件由 XComponent 接收（文字叠加层 hitTestBehavior=Transparent 不拦截）
 *
 * 渲染循环（由 ArkTS 主线程驱动，~30fps）：
 *   setInterval(33ms) → {
 *     检测屏幕状态(AOD/Interactive),
 *     计算时间/农历/太阳方向,
 *     updateSunDirection(),
 *     pushSceneData() → updateData() [天气/电池],
 *     renderFrame() → MakeCurrent → g_scene->renderFrame() → SwapBuffers
 *   }
 *
 * 数据提供者（Provider）：
 *   BatteryProvider     — 电池电量/充电状态（@kit.BasicServicesKit batteryInfo）
 *   LocationProvider    — GPS定位（@kit.LocationKit，需用户授权 APPROXIMATELY_LOCATION）
 *   ScreenStateProvider — 屏幕亮/熄状态（@kit.BasicServicesKit power.isActive）
 *   WeatherFetcher      — 天气数据（Open-Meteo API，使用 LocationProvider 的坐标）
 *
 * 配置持久化：
 *   @StorageLink 装饰器自动同步 PersistentStorage，
 *   字符串 '0'/'1' 表示关闭/开启（showLunar, showSensors, showClouds）。
 *
 * 生命周期：
 *   aboutToAppear → 启动天气获取 + 请求定位权限
 *   onLoad → 启动渲染循环 + 加载纹理 + 注册字体
 *   onPageHide → 暂停渲染循环（省电）
 *   onPageShow → 恢复渲染循环
 *   onDestroy → 停止渲染 + 销毁 Native 场景
 */

import nativerender from 'libnativerender.so'
import { SunCalculator } from '../model/SunCalculator'
import { LunarCalendar } from '../model/LunarCalendar'
import { WeatherFetcher } from '../model/WeatherFetcher'
import { BatteryProvider } from '../model/BatteryProvider'
import { LocationProvider } from '../model/LocationProvider'
import { ScreenStateProvider } from '../model/ScreenStateProvider'

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
  @State sceneInited: boolean = false

  private sunCalc: SunCalculator = new SunCalculator()
  private lunarCalc: LunarCalendar = new LunarCalendar()
  private weatherFetcher: WeatherFetcher = new WeatherFetcher()
  private batteryProvider: BatteryProvider = new BatteryProvider()
  private locationProvider: LocationProvider = new LocationProvider()
  private screenState: ScreenStateProvider = new ScreenStateProvider()
  private surfaceWidth: number = 0
  private surfaceHeight: number = 0
  private animTimer: number = -1
  private fontRegistered: boolean = false
  private texturesLoaded: boolean = false
  private lastTouchX: number = -1
  private isDragging: boolean = false

  aboutToAppear() {
    console.info('[EarthWatch] aboutToAppear: starting services')
    this.weatherFetcher.startFetching()
    this.locationProvider.requestLocation(getContext(this)).then(() => {
      let loc = this.locationProvider.currentLocation
      console.info(`[EarthWatch] location acquired: ${loc.latitude.toFixed(4)}, ${loc.longitude.toFixed(4)}`)
      this.weatherFetcher.updateLocation(loc.latitude, loc.longitude)
    }).catch((e: Error) => {
      console.error(`[EarthWatch] location failed: ${e.message}, using default city`)
    })
  }

  aboutToDisappear() {
    console.info('[EarthWatch] aboutToDisappear: stopping all services')
    this.stopRenderLoop()
    this.weatherFetcher.stopFetching()
  }

  onPageShow() {
    console.info('[EarthWatch] onPageShow')
    if (!this.renderStarted) {
      this.startRenderLoop()
    }
  }

  onPageHide() {
    console.info('[EarthWatch] onPageHide: pausing render')
    this.stopRenderLoop()
  }

  stopRenderLoop() {
    if (this.animTimer >= 0) {
      console.info('[EarthWatch] stopping render loop')
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
      batteryPct: this.batteryProvider.batteryPct,
      isCharging: this.batteryProvider.isCharging,
      batteryIsLow: this.batteryProvider.isLow,
      temperature: this.weatherFetcher.temperature,
      uvIndex: this.weatherFetcher.uvIndex,
      feelsLike: this.weatherFetcher.feelsLike,
      precipProb: this.weatherFetcher.precipProb
    })
  }

  registerFonts() {
    if (this.fontRegistered) return
    try {
      let fontObj = this.getUIContext().getFont()
      fontObj.registerFont({
        familyName: 'DSEG7Modern-Bold',
        familySrc: '$rawfile/fonts/DSEG7Modern-Bold.ttf'
      })
      fontObj.registerFont({
        familyName: 'DSEG7Classic-Bold',
        familySrc: '$rawfile/fonts/DSEG7Classic-Bold.ttf'
      })
      this.fontRegistered = true
      console.info('[EarthWatch] fonts registered: DSEG7Modern-Bold, DSEG7Classic-Bold')
    } catch (e) {
      console.error(`[EarthWatch] font registration failed: ${JSON.stringify(e)}`)
    }
  }

  async loadTexturesAsync() {
    if (this.texturesLoaded) return
    try {
      console.info('[EarthWatch] loading textures from rawfile...')
      let mgr = getContext(this).resourceManager
      let dayBuf = await mgr.getRawFileContent('textures/earth_day.jpg')
      let nightBuf = await mgr.getRawFileContent('textures/earth_night.png')
      let cloudBuf = await mgr.getRawFileContent('textures/earth_cloud.png')
      console.info(`[EarthWatch] textures read: day=${dayBuf.byteLength}B, night=${nightBuf.byteLength}B, cloud=${cloudBuf.byteLength}B`)
      let dayArr = dayBuf.buffer.slice(dayBuf.byteOffset, dayBuf.byteOffset + dayBuf.byteLength)
      let nightArr = nightBuf.buffer.slice(nightBuf.byteOffset, nightBuf.byteOffset + nightBuf.byteLength)
      let cloudArr = cloudBuf.buffer.slice(cloudBuf.byteOffset, cloudBuf.byteOffset + cloudBuf.byteLength)
      nativerender.loadTextures(dayArr, nightArr, cloudArr)
      this.texturesLoaded = true
      console.info('[EarthWatch] textures loaded and passed to native')
    } catch (e) {
      console.error(`[EarthWatch] texture loading failed: ${JSON.stringify(e)}`)
    }
  }

  startRenderLoop() {
    if (this.renderStarted) return
    this.renderStarted = true
    console.info('[EarthWatch] render loop starting')

    this.registerFonts()

    let frameCount = 0
    this.animTimer = setInterval(() => {
      if (!this.sceneInited) {
        let size = nativerender.getSurfaceSize()
        if (size.width > 0 && size.height > 0) {
          this.surfaceWidth = size.width
          this.surfaceHeight = size.height
          console.info(`[EarthWatch] surface ready: ${size.width}x${size.height}, initializing scene`)
          nativerender.initScene(this.surfaceWidth, this.surfaceHeight)
          this.pushConfig()
          this.pushSceneData()
          this.loadTexturesAsync()
          this.sceneInited = true
          console.info('[EarthWatch] scene initialized and textures loading')
        }
        return
      }

      if (frameCount === 0) {
        console.info('[EarthWatch] first render frame dispatched')
      }

      this.isAmbient = this.screenState.isAmbient

      if (frameCount % 90 === 0) {
        console.info(`[EarthWatch] frame #${frameCount} | ambient=${this.isAmbient} | time=${this.currentTime} | batt=${this.batteryProvider.batteryPct}%`)
      }
      frameCount++

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
          console.info('[EarthWatch] XComponent.onLoad triggered')
          this.startRenderLoop()
        })
        .onDestroy(() => {
          console.info('[EarthWatch] XComponent.onDestroy triggered')
          this.stopRenderLoop()
          this.sceneInited = false
          nativerender.destroyScene()
        })
        .onTouch((event: TouchEvent) => {
          if (this.isAmbient) return
          let touch = event.touches[0]
          if (event.type === TouchType.Down) {
            this.lastTouchX = touch.x
            this.isDragging = false
          } else if (event.type === TouchType.Move) {
            if (this.lastTouchX >= 0) {
              let dx = touch.x - this.lastTouchX
              if (Math.abs(dx) > 2) {
                this.isDragging = true
                nativerender.onDragDelta(dx)
              }
              this.lastTouchX = touch.x
            }
          } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            if (!this.isDragging) {
              nativerender.requestSpin()
            }
            this.lastTouchX = -1
            this.isDragging = false
          }
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
