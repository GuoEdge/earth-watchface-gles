import nativerender from 'libnativerender.so'
import { SunCalculator } from '../model/SunCalculator'
import { LunarCalendar } from '../model/LunarCalendar'
import { WeatherFetcher } from '../model/WeatherFetcher'

@Entry
@Component
struct Index {
  @StorageLink('accent_color') accentColorStr: string = '0'
  @StorageLink('show_lunar') showLunarStr: string = '0'
  @StorageLink('show_sensors') showSensorsStr: string = '0'
  @StorageLink('show_clouds') showCloudsStr: string = '0'
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
  private xComponentController: XComponentController = new XComponentController()
  private surfaceWidth: number = 466
  private surfaceHeight: number = 466
  private animTimer: number = -1

  aboutToAppear() {
    this.weatherFetcher.startFetching()
  }

  aboutToDisappear() {
    if (this.animTimer >= 0) {
      clearInterval(this.animTimer)
    }
    this.weatherFetcher.stopFetching()
  }

  pushConfig() {
    nativerender.updateConfig({
      accentIdx: parseInt(this.accentColorStr) || 0,
      showLunar: this.showLunarStr === '0',
      showSensors: this.showSensorsStr === '0',
      showClouds: this.showCloudsStr === '0',
      animMode: parseInt(this.animModeStr) || 0,
      arcDataSource: [
        parseInt(this.arcTlStr) || 0,
        parseInt(this.arcTrStr) || 6,
        parseInt(this.arcBlStr) || 9,
        parseInt(this.arcBrStr) || 8
      ]
    })
  }

  startRenderLoop() {
    if (this.renderStarted) return
    this.renderStarted = true

    nativerender.initScene(this.surfaceWidth, this.surfaceHeight)
    this.pushConfig()

    this.animTimer = setInterval(() => {
      const now = new Date()
      const timeMs = now.getTime()
      this.currentTime = `${this.pad2(now.getHours())}:${this.pad2(now.getMinutes())}`
      this.currentDate = `${now.getMonth() + 1}月${now.getDate()}日 ${this.dow(now.getDay())}`
      this.lunarText = this.showLunarStr === '0' ? this.lunarCalc.toLunar(now) : ''
      this.gzText = this.showLunarStr === '0' ? this.lunarCalc.toGanzhi(now) : ''

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
        type: XComponentType.TEXTURE,
        controller: this.xComponentController
      })
        .width('100%')
        .height('100%')
        .onLoad((context?: object) => {
          if (context) {
            nativerender.registerCallback(context)
          }
          this.startRenderLoop()
        })
        .onDestroy(() => {
          this.renderStarted = false
          if (this.animTimer >= 0) {
            clearInterval(this.animTimer)
            this.animTimer = -1
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

        if (this.showLunarStr === '0' && this.lunarText) {
          Text(this.lunarText)
            .fontSize(14)
            .fontColor('#C0D4EE')
            .textAlign(TextAlign.Center)
            .shadow({ radius: 10, offsetY: 3, color: 0xBB000000 })
            .margin({ top: 2 })
        }

        if (this.showLunarStr === '0' && this.gzText) {
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
