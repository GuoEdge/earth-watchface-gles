/**
 * NotifProvider.ets — 通知计数提供者
 *
 * 使用 notificationManager.getActiveNotificationCount() 获取当前应用未删除的通知数。
 * 官方文档：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-notificationmanager
 *
 * 支持设备：Phone/PC/2in1/Tablet/Car/Wearable
 *
 * 重要限制：
 *   getActiveNotificationCount() 只返回本应用的活跃通知数，不是系统全局未读数。
 *   HarmonyOS 三方应用无法获取系统全局未读通知数（需要系统应用权限）。
 *   作为替代方案，可监听通知变化事件来估算。
 *
 * 需要权限：ohos.permission.NOTIFICATION_CONTROLLER（系统权限，三方应用不可申请）
 *   因此本模块在三方应用中始终返回 0，仅在系统签名应用中有效。
 */

import { notificationManager } from '@kit.NotificationKit'

export class NotifProvider {
  private count: number = 0

  async refreshCount(): Promise<number> {
    try {
      this.count = await notificationManager.getActiveNotificationCount()
    } catch (e) {
      this.count = 0
    }
    return this.count
  }

  getCount(): number {
    return this.count
  }
}
