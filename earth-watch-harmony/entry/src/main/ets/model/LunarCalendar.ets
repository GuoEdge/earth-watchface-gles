/**
 * LunarCalendar.ets — 农历与干支计算
 *
 * 提供两个核心功能：
 *   toLunar(date) → 农历日期字符串（如"农历正月初一"）
 *   toGanzhi(date) → 干支纪年/月/日字符串（如"甲子年 丙寅月 庚午日"）
 *
 * 算法说明：
 *   - lunarInfo[] 数组编码了 1900-2100 年每年的农历月大小和闰月信息
 *     每个元素 16 位：bit[15:4] = 12个月的大小(1=30天/0=29天)，
 *     bit[3:0] = 闰月月份(0=无闰月)，bit[16] = 闰月大小
 *   - 基准日期：1900年1月31日（农历正月初一）
 *   - 干支计算：年干支=(年-4)%60，月干支由年干推算，日干支由距1900-01-01的天数推算
 */
export class LunarCalendar {
  private lunarInfo: number[] = [
    0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
    0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
    0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
    0x06566, 0x0d4a0, 0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
    0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
    0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
    0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
    0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
    0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
    0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x05ac0, 0x0ab60, 0x096d5, 0x092e0,
    0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
    0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
    0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
    0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
    0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
    0x14b63
  ]

  private lunarMonths: string[] = ['正', '二', '三', '四', '五', '六', '七', '八', '九', '十', '冬', '腊']
  private lunarDays: string[] = ['', '初一', '初二', '初三', '初四', '初五', '初六', '初七', '初八', '初九', '初十',
    '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九', '二十',
    '廿一', '廿二', '廿三', '廿四', '廿五', '廿六', '廿七', '廿八', '廿九', '三十']
  private gan: string[] = ['甲', '乙', '丙', '丁', '戊', '己', '庚', '辛', '壬', '癸']
  private zhi: string[] = ['子', '丑', '寅', '卯', '辰', '巳', '午', '未', '申', '酉', '戌', '亥']

  private baseDateMs: number = new Date(1900, 0, 31).getTime()

  private lunarYearDays(y: number): number {
    let sum = 348
    let i = 0x8000
    while (i > 0x8) {
      if ((this.lunarInfo[y - 1900] & i) !== 0) sum++
      i = i >>> 1
    }
    return sum + this.leapDays(y)
  }

  private leapDays(y: number): number {
    if (this.leapMonth(y) !== 0) {
      return (this.lunarInfo[y - 1900] & 0x10000) !== 0 ? 30 : 29
    }
    return 0
  }

  private leapMonth(y: number): number {
    return this.lunarInfo[y - 1900] & 0xf
  }

  private monthDays(y: number, m: number): number {
    return (this.lunarInfo[y - 1900] & (0x10000 >>> m)) !== 0 ? 30 : 29
  }

  toLunar(date: Date): string {
    const dateMs = date.getTime()
    let offset = Math.floor((dateMs - this.baseDateMs) / 86400000)
    let year = 1900
    let daysInYear = this.lunarYearDays(year)
    while (offset >= daysInYear) {
      offset -= daysInYear
      year++
      daysInYear = this.lunarYearDays(year)
    }

    const leap = this.leapMonth(year)
    let month = 1
    let isLeap = false
    for (let i = 1; i <= 12; i++) {
      if (leap > 0 && i === leap + 1 && !isLeap) {
        month = i - 1
        const ld = this.leapDays(year)
        if (offset < ld) {
          isLeap = true
          break
        }
        offset -= ld
      }
      const md = this.monthDays(year, i)
      if (offset < md) {
        month = i
        break
      }
      offset -= md
    }

    const day = offset + 1
    const mStr = isLeap ? `闰${this.lunarMonths[month - 1]}` : this.lunarMonths[month - 1]
    return `农历${mStr}月${this.lunarDays[day]}`
  }

  toGanzhi(date: Date): string {
    const year = date.getFullYear()
    const yrGz = this.gan[(year - 4) % 10] + this.zhi[(year - 4) % 12]

    const yrStem = (year - 4) % 10
    const stems = [2, 4, 6, 8, 0, 2, 4, 6, 8, 0]
    const stem0 = stems[yrStem]
    const mo = date.getMonth() + 1
    const moStem = (stem0 + mo - 2) % 10
    const moBranch = mo % 12
    const moGz = this.gan[moStem] + this.zhi[moBranch]

    const ref = new Date(1900, 0, 1)
    const offD = Math.floor((date.getTime() - ref.getTime()) / 86400000)
    const gzIdx = ((offD + 10) % 60 + 60) % 60
    const dayGz = this.gan[gzIdx % 10] + this.zhi[gzIdx % 12]

    return `${yrGz}年 ${moGz}月 ${dayGz}日`
  }
}
