/**
 * SunCalculator.ets — 太阳方向计算
 *
 * 根据当前 UTC 时间和日期，计算太阳在地球坐标系中的单位方向向量。
 * 用于 EarthRenderer 的日/夜面着色和 GlOverlay 的晨昏线渲染。
 *
 * 算法：
 *   1. 根据UTC小时计算太阳时角 ha = -(bh/12)*π
 *   2. 根据年积日(DOY)计算太阳赤纬 dec = -23.44° * cos(2π/365 * (doy+10))
 *   3. 将赤纬和时角转换为3D方向向量 (sx, sy, sz)
 *   4. 归一化后返回 [sx/len, sy/len, sz/len]
 */
export class SunCalculator {
  sunDirection(date: Date): number[] {
    const bh = date.getUTCHours() + date.getUTCMinutes() / 60 + date.getUTCSeconds() / 3600
    const ha = -(bh / 12) * Math.PI
    const start = new Date(Date.UTC(date.getUTCFullYear(), 0, 1))
    const doy = Math.floor((date.getTime() - start.getTime()) / 86400000) + 1
    const dec = -23.44 * Math.cos((2 * Math.PI / 365) * (doy + 10)) * (Math.PI / 180)
    const sx = Math.cos(dec) * Math.cos(ha)
    const sy = Math.sin(dec)
    const sz = Math.cos(dec) * Math.sin(ha)
    const len = Math.sqrt(sx * sx + sy * sy + sz * sz)
    return [sx / len, sy / len, sz / len]
  }
}
