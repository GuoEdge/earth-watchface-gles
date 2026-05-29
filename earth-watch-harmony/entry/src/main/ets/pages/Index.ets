import nativerender from 'libnativerender.so'
import { SunCalculator } from '../model/SunCalculator'
import { LunarCalendar } from '../model/LunarCalendar'
import { WeatherFetcher } from '../model/WeatherFetcher'
import display from '@ohos.display'

@Entry
@Component
struct Index {
  @State currentTime: string = '00:00'
  @State currentDate: string = ''
  @State lunarText: string = ''
  @State gzText: string = ''
  @State isAmbient: boolean = false
  @State accentIdx: number = 0
  @State showLunar: boolean = true
  @State showSensors: boolean = true
  @State showClouds: boolean = true
  @State animMode: number = 0
  @State arcDataSource: number[] = [0, 6, 9, 8]
  @State fontStyle: number = 0

  private sunCalc: SunCalculator = new SunCalculator()
  private lunarCalc: LunarCalendar = new LunarCalendar()
  private weatherFetcher: WeatherFetcher = new WeatherFetcher()
  private xComponentId: string = 'earthCanvas'
  private surfaceWidth: number = 466
  private surfaceHeight: number = 466
  private lastFrameMs: number = 0
  private animTimer: number = -1

  aboutToAppear() {
    this.loadConfig()
    this.weatherFetcher.startFetching()
    this.startRenderLoop()
  }

  aboutToDisappear() {
    if (this.animTimer >= 0) {
      clearInterval(this.animTimer)
    }
    this.weatherFetcher.stopFetching()
  }

  loadConfig() {
    try {
      const accent = PersistentStorage.getPersistProp('accent_color', '0')
      this.accentIdx = parseInt(accent as string) || 0
      const lunar = PersistentStorage.getPersistProp('show_lunar', '0')
      this.showLunar = (lunar as string) === '0'
      const sensors = PersistentStorage.getPersistProp('show_sensors', '0')
      this.showSensors = (sensors as string) === '0'
      const clouds = PersistentStorage.getPersistProp('show_clouds', '0')
      this.showClouds = (clouds as string) === '0'
      const anim = PersistentStorage.getPersistProp('animation_mode', '0')
      this.animMode = parseInt(anim as string) || 0
      const arcTl = PersistentStorage.getPersistProp('arc_tl', '0')
      const arcTr = PersistentStorage.getPersistProp('arc_tr', '6')
      const arcBl = PersistentStorage.getPersistProp('arc_bl', '9')
      const arcBr = PersistentStorage.getPersistProp('arc_br', '8')
      this.arcDataSource = [
        parseInt(arcTl as string) || 0,
        parseInt(arcTr as string) || 6,
        parseInt(arcBl as string) || 9,
        parseInt(arcBr as string) || 8
      ]
    } catch (e) {
    }
  }

  pushConfig() {
    nativerender.updateConfig({
      accentIdx: this.accentIdx,
      showLunar: this.showLunar,
      showSensors: this.showSensors,
      showClouds: this.showClouds,
      animMode: this.animMode,
      arcDataSource: this.arcDataSource
    })
  }

  startRenderLoop() {
    nativerender.initScene(this.surfaceWidth, this.surfaceHeight)
    this.pushConfig()

    this.animTimer = setInterval(() => {
      const now = new Date()
      const timeMs = now.getTime()
      this.currentTime = `${this.pad2(now.getHours())}:${this.pad2(now.getMinutes())}`
      this.currentDate = `${now.getMonth() + 1}月${now.getDate()}日 ${this.dow(now.getDay())}`
      this.lunarText = this.showLunar ? this.lunarCalc.toLunar(now) : ''
      this.gzText = this.showLunar ? this.lunarCalc.toGanzhi(now) : ''

      const sunDir = this.sunCalc.sunDirection(now)
      nativerender.updateSunDirection(sunDir)
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
        id: this.xComponentId,
        type: XComponentType.SURFACE,
        libraryname: 'nativerender'
      })
        .width('100%')
        .height('100%')
        .onLoad(() => {
          this.startRenderLoop()
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

        if (this.showLunar && this.lunarText) {
          Text(this.lunarText)
            .fontSize(14)
            .fontColor('#C0D4EE')
            .textAlign(TextAlign.Center)
            .shadow({ radius: 10, offsetY: 3, color: 0xBB000000 })
            .margin({ top: 2 })
        }

        if (this.showLunar && this.gzText) {
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
    }
    .width('100%')
    .height('100%')
  }
}
