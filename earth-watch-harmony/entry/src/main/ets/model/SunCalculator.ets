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
