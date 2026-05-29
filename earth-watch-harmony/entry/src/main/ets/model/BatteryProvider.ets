/**
 * BatteryProvider.ets — 电池信息提供者
 *
 * 使用 @ohos.batteryInfo 获取设备电池状态。
 * 官方文档：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-battery-info-V5
 *
 * 支持设备：Phone/PC/2in1/Tablet/Wearable（Watch 5 属于 Wearable）
 *
 * API 说明：
 *   batteryInfo.batterySOC      — 剩余电量百分比 (0-100)
 *   batteryInfo.chargingStatus  — 充电状态枚举 (NONE=0, ENABLE=1, DISABLE=2, FULL=3)
 *   batteryInfo.batteryCapacityLevel — 电量等级 (LEVEL_HIGH/LEVEL_NORMAL/LEVEL_LOW/LEVEL_WARNING/LEVEL_CRITICAL)
 *
 * 注意：不需要额外权限声明即可读取基本电池信息。
 * 如果需要监听电池变化事件，需要 ohos.permission.BATTERY_STATS 权限。
 */

import { batteryInfo } from '@kit.BasicServicesKit'

export class BatteryProvider {
  get batteryPct(): number {
    try {
      return batteryInfo.batterySOC
    } catch (e) {
      return 0
    }
  }

  get isCharging(): boolean {
    try {
      return batteryInfo.chargingStatus === batteryInfo.BatteryChargeState.ENABLE ||
             batteryInfo.chargingStatus === batteryInfo.BatteryChargeState.FULL
    } catch (e) {
      return false
    }
  }

  get isLow(): boolean {
    try {
      let level = batteryInfo.batteryCapacityLevel
      return level === batteryInfo.BatteryCapacityLevel.LEVEL_LOW ||
             level === batteryInfo.BatteryCapacityLevel.LEVEL_WARNING ||
             level === batteryInfo.BatteryCapacityLevel.LEVEL_CRITICAL
    } catch (e) {
      return false
    }
  }
}
