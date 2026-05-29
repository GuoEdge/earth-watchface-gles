/**
 * WeatherFetcher.ets — 天气数据获取
 *
 * 使用 Open-Meteo 免费 API（无需 API Key）获取当前天气数据。
 * 每 30 分钟自动刷新一次。
 *
 * 提供的数据（通过 getter 属性访问）：
 *   temperature  — 当前温度 (°C)
 *   feelsLike    — 体感温度 (°C)
 *   uvIndex      — UV 指数 (0-11+)
 *   precipProb   — 降水概率 (0-100%)
 *   display      — 显示字符串（图标+温度，如"⛅25°"）
 *
 * 已知限制：
 *   - 坐标硬编码为上海 (31.2304, 121.4737)
 *   - 应接入 @ohos.geoLocationManager 获取用户当前位置
 */
import http from '@ohos.net.http'

export class WeatherFetcher {
  private cachedTemp: string = '--'
  private cachedIcon: string = ''
  private cachedUvIndex: number = -1
  private cachedTemperature: number = NaN
  private cachedFeelsLike: number = NaN
  private cachedPrecipProb: number = -1
  private lastFetch: number = 0
  private isFetching: boolean = false
  private timer: number = -1
  private static readonly REFRESH_MS: number = 30 * 60 * 1000

  get display(): string {
    return `${this.cachedIcon}${this.cachedTemp}`
  }

  get uvIndex(): number {
    return this.cachedUvIndex
  }

  get temperature(): number {
    return this.cachedTemperature
  }

  get feelsLike(): number {
    return this.cachedFeelsLike
  }

  get precipProb(): number {
    return this.cachedPrecipProb
  }

  startFetching() {
    this.fetch()
    this.timer = setInterval(() => {
      this.fetch()
    }, WeatherFetcher.REFRESH_MS)
  }

  stopFetching() {
    if (this.timer >= 0) {
      clearInterval(this.timer)
      this.timer = -1
    }
  }

  private fetch() {
    const now = Date.now()
    if (now - this.lastFetch < WeatherFetcher.REFRESH_MS || this.isFetching) return
    this.isFetching = true

    const lat = 31.2304
    const lon = 121.4737
    const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}` +
      `&current=temperature_2m,apparent_temperature,weather_code,uv_index` +
      `&hourly=weather_code,precipitation_probability&timezone=auto&forecast_hours=4`

    const httpRequest = http.createHttp()
    httpRequest.request(url, {
      method: http.RequestMethod.GET,
      connectTimeout: 8000,
      readTimeout: 8000
    }).then((response) => {
      if (response.responseCode === 200 && response.result) {
        try {
          const root = JSON.parse(response.result as string)
          const cur = root['current']
          if (cur) {
            const t = cur['temperature_2m']
            const wc = cur['weather_code'] || 0
            const uv = cur['uv_index']
            this.cachedTemperature = typeof t === 'number' ? t : NaN
            this.cachedTemp = typeof t === 'number' ? `${Math.round(t)}°` : '--'
            this.cachedFeelsLike = typeof cur['apparent_temperature'] === 'number'
              ? cur['apparent_temperature'] : NaN
            this.cachedIcon = this.wmoIcon(wc)
            this.cachedUvIndex = typeof uv === 'number' ? uv : -1
          }
          const hourly = root['hourly']
          if (hourly) {
            const pp = hourly['precipitation_probability']
            this.cachedPrecipProb = (pp && pp.length > 0) ? pp[0] : -1
          }
          this.lastFetch = Date.now()
        } catch (e) {
        }
      }
    }).catch(() => {
    }).finally(() => {
      this.isFetching = false
      httpRequest.destroy()
    })
  }

  private wmoIcon(code: number): string {
    if (code === 0) return '☀'
    if (code >= 1 && code <= 3) return '⛅'
    if (code === 45 || code === 48) return '🌫'
    if (code >= 51 && code <= 57) return '🌦'
    if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return '🌧'
    if ((code >= 71 && code <= 77) || (code >= 85 && code <= 86)) return '🌨'
    if (code >= 95) return '⛈'
    return ''
  }
}
