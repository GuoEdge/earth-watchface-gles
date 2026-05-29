/**
 * ScreenStateProvider.ets — 屏幕状态检测
 *
 * 使用 @ohos.power.isActive() 检测设备亮/熄屏状态。
 * 亮屏 → Interactive 模式（完整3D渲染）
 * 熄屏 → Ambient/AOD 模式（仅指针+刻度，≤20%非黑像素）
 *
 * 官方文档：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-power
 *
 * 支持设备：Phone/PC/2in1/Tablet/TV/Wearable
 *
 * API 说明：
 *   power.isActive() — 有屏设备亮屏返回 true，熄屏返回 false
 *   不需要额外权限声明
 *
 * 替代方案（已废弃）：
 *   power.isScreenOn() — API 9 已废弃，建议使用 isActive()
 *   screenLock.isScreenLocked() — API 9 已废弃，且仅系统应用可用
 *
 * 检测策略：
 *   在每帧渲染前调用 isActive() 检测屏幕状态，
 *   状态变化时自动切换渲染模式。
 *   检测频率：每帧（~30fps），isActive() 是同步调用，开销极小。
 */

import { power } from '@kit.BasicServicesKit'

export class ScreenStateProvider {
  private lastActive: boolean = true

  get isAmbient(): boolean {
    try {
      return !power.isActive()
    } catch (e) {
      return false
    }
  }

  get isActive(): boolean {
    try {
      return power.isActive()
    } catch (e) {
      return true
    }
  }

  hasChanged(): boolean {
    let current = this.isActive
    let changed = current !== this.lastActive
    this.lastActive = current
    return changed
  }
}
