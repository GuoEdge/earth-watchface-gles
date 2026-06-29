package com.earthwatch.face

import java.time.*
import kotlin.math.abs

class LunarCalendar {

    private val lunarInfo = intArrayOf(
        0x04bd8,0x04ae0,0x0a570,0x054d5,0x0d260,0x0d950,0x16554,0x056a0,0x09ad0,0x055d2,
        0x04ae0,0x0a5b6,0x0a4d0,0x0d250,0x1d255,0x0b540,0x0d6a0,0x0ada2,0x095b0,0x14977,
        0x04970,0x0a4b0,0x0b4b5,0x06a50,0x06d40,0x1ab54,0x02b60,0x09570,0x052f2,0x04970,
        0x06566,0x0d4a0,0x0ea50,0x16a95,0x05ad0,0x02b60,0x186e3,0x092e0,0x1c8d7,0x0c950,
        0x0d4a0,0x1d8a6,0x0b550,0x056a0,0x1a5b4,0x025d0,0x092d0,0x0d2b2,0x0a950,0x0b557,
        0x06ca0,0x0b550,0x15355,0x04da0,0x0a5b0,0x14573,0x052b0,0x0a9a8,0x0e950,0x06aa0,
        0x0aea6,0x0ab50,0x04b60,0x0aae4,0x0a570,0x05260,0x0f263,0x0d950,0x05b57,0x056a0,
        0x096d0,0x04dd5,0x04ad0,0x0a4d0,0x0d4d4,0x0d250,0x0d558,0x0b540,0x0b6a0,0x195a6,
        0x095b0,0x049b0,0x0a974,0x0a4b0,0x0b27a,0x06a50,0x06d40,0x0af46,0x0ab60,0x09570,
        0x04af5,0x04970,0x064b0,0x074a3,0x0ea50,0x06b58,0x05ac0,0x0ab60,0x096d5,0x092e0,
        0x0c960,0x0d954,0x0d4a0,0x0da50,0x07552,0x056a0,0x0abb7,0x025d0,0x092d0,0x0cab5,
        0x0a950,0x0b4a0,0x0baa4,0x0ad50,0x055d9,0x04ba0,0x0a5b0,0x15176,0x052b0,0x0a930,
        0x07954,0x06aa0,0x0ad50,0x05b52,0x04b60,0x0a6e6,0x0a4e0,0x0d260,0x0ea65,0x0d530,
        0x05aa0,0x076a3,0x096d0,0x04afb,0x04ad0,0x0a4d0,0x1d0b6,0x0d250,0x0d520,0x0dd45,
        0x0b5a0,0x056d0,0x055b2,0x049b0,0x0a577,0x0a4b0,0x0aa50,0x1b255,0x06d20,0x0ada0,
        0x14b63
    )

    private val lunarMonths = arrayOf("正","二","三","四","五","六","七","八","九","十","冬","腊")
    private val lunarDays = arrayOf("","初一","初二","初三","初四","初五","初六","初七","初八","初九","初十",
        "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十",
        "廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九","三十")
    private val gan = arrayOf("甲","乙","丙","丁","戊","己","庚","辛","壬","癸")
    private val zhi = arrayOf("子","丑","寅","卯","辰","巳","午","未","申","酉","戌","亥")

    private val baseDate = LocalDate.of(1900, 1, 31)
    private val maxYear = 1900 + lunarInfo.size - 1  // 2050

    private fun lunarYearDays(y: Int): Int {
        if (y - 1900 >= lunarInfo.size) return 354  // 越界保护：回落平年
        var sum = 348
        var i = 0x8000
        while (i > 0x8) {
            if ((lunarInfo[y - 1900] and i) != 0) sum++
            i = i shr 1
        }
        return sum + leapDays(y)
    }

    private fun leapDays(y: Int): Int {
        if (y - 1900 >= lunarInfo.size) return 0
        if (leapMonth(y) != 0) {
            return if ((lunarInfo[y - 1900] and 0x10000) != 0) 30 else 29
        }
        return 0
    }

    private fun leapMonth(y: Int): Int {
        if (y - 1900 >= lunarInfo.size) return 0
        return lunarInfo[y - 1900] and 0xf
    }

    private fun monthDays(y: Int, m: Int): Int {
        if (y - 1900 >= lunarInfo.size) return 29
        return if ((lunarInfo[y - 1900] and (0x10000 shr m)) != 0) 30 else 29
    }

    fun toLunar(date: LocalDate): String {
        var offset = (date.toEpochDay() - baseDate.toEpochDay()).toInt()
        var year = 1900
        var daysInYear = lunarYearDays(year)
        while (offset >= daysInYear) {
            offset -= daysInYear
            year++
            daysInYear = lunarYearDays(year)
        }

        val leap = leapMonth(year)
        var month = 1
        var isLeap = false
        for (i in 1..12) {
            if (leap > 0 && i == leap + 1 && !isLeap) {
                month = i - 1
                val ld = leapDays(year)
                if (offset < ld) { isLeap = true; break }
                offset -= ld
            }
            val md = monthDays(year, i)
            if (offset < md) { month = i; break }
            offset -= md
        }

        val day = offset + 1
        val mStr = if (isLeap) "闰${lunarMonths[month - 1]}" else lunarMonths[month - 1]
        return "农历${mStr}月${lunarDays[day]}"
    }

    fun toGanzhi(date: LocalDate): String {
        val year = date.year
        val yrGz = gan[(year - 4) % 10] + zhi[(year - 4) % 12]

        val yrStem = (year - 4) % 10
        val stems = intArrayOf(2,4,6,8,0,2,4,6,8,0)
        val stem0 = stems[yrStem]
        val mo = date.monthValue
        val moStem = (stem0 + mo - 2) % 10
        val moBranch = mo % 12
        val moGz = gan[moStem] + zhi[moBranch]

        val ref = LocalDate.of(1900, 1, 1)
        val offD = (date.toEpochDay() - ref.toEpochDay()).toInt()
        val gzIdx = (offD + 10) % 60
        val dayGz = gan[gzIdx % 10] + zhi[gzIdx % 12]

        return "${yrGz}年 ${moGz}月 ${dayGz}日"
    }

    // 24 节气名称（按黄经 15° 递增，从立春=315° 开始）
    private val solarTerms = arrayOf(
        "立春","雨水","惊蛰","春分","清明","谷雨",
        "立夏","小满","芒种","夏至","小暑","大暑",
        "立秋","处暑","白露","秋分","寒露","霜降",
        "立冬","小雪","大雪","冬至","小寒","大寒"
    )

    /**
     * 精确节气计算：基于太阳黄经（VSOP87 简化公式）。
     *
     * 24 节气对应太阳黄经每 15° 一个（立春=315°, 雨水=330°, ..., 大寒=300°）。
     * 算法：计算当天正午太阳黄经，若恰好在某个节气点 ±1.0° 内则返回该节气名。
     * 太阳每日移动约 0.985°，±1° 窗口恰好覆盖节气当天及相邻半天。
     */
    fun currentSolarTerm(date: LocalDate): String {
        val solarLongitude = solarLongitude(date)
        val lon = ((solarLongitude % 360.0) + 360.0) % 360.0
        for (i in 0 until 24) {
            val termLon = (315.0 + i * 15.0) % 360.0
            val diff = abs(angularDiff(lon, termLon))
            if (diff <= 1.0) {
                return solarTerms[i]
            }
        }
        return ""
    }

    /** 计算两天之间的角差（度），结果在 [0, 180]。 */
    private fun angularDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * 计算给定日期太阳黄经（视黄经，度）。
     * 基于 Jean Meeus《天文算法》低精度公式，精度 ±0.01° 足够节气判定。
     */
    private fun solarLongitude(date: LocalDate): Double {
        // J2000.0 儒略日
        val jd = date.toEpochDay() + 2440587.5 + 0.5  // 当天正午
        val t = (jd - 2451545.0) / 36525.0

        // 太阳平黄经
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        // 太阳近地点平近点角
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val mRad = Math.toRadians(m)

        // 中心差（地球轨道椭圆修正）
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * Math.sin(mRad) +
                (0.019993 - 0.000101 * t) * Math.sin(2 * mRad) +
                0.000289 * Math.sin(3 * mRad)

        // 太阳视黄经
        val trueLong = l0 + c

        // 章动修正（约 ±0.005°）
        val omega = 125.04 - 1934.136 * t
        val omegaRad = Math.toRadians(omega)
        val apparent = trueLong - 0.00569 - 0.00478 * Math.sin(omegaRad)

        return apparent
    }
}
