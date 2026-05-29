/**
 * LocationProvider.ets — 位置信息提供者
 *
 * 使用 @ohos.geoLocationManager 获取设备当前位置（经纬度），
 * 供 WeatherFetcher 使用实际位置获取天气数据。
 *
 * 官方文档：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-geolocationmanager
 *
 * 支持设备：Phone/Tablet/PC/2in1/Wearable
 *
 * 权限要求（需在 module.json5 中声明）：
 *   ohos.permission.APPROXIMATELY_LOCATION — 模糊定位（5公里精度，足够天气查询）
 *   ohos.permission.LOCATION              — 精准定位（米级，本应用不需要）
 *
 * 注意：
 *   APPROXIMATELY_LOCATION 是 user_grant 权限，需要用户授权。
 *   如果用户拒绝授权，将回退到默认坐标（上海）。
 *   表盘应用应在首次启动时请求权限，之后缓存位置。
 */

import { geoLocationManager } from '@kit.LocationKit'
import { abilityAccessCtrl, bundleManager, Permissions } from '@kit.AbilityKit'

export interface LocationData {
  latitude: number
  longitude: number
}

export class LocationProvider {
  private location: LocationData = { latitude: 31.2304, longitude: 121.4737 }
  private located: boolean = false

  get currentLocation(): LocationData {
    return this.location
  }

  get hasLocation(): boolean {
    return this.located
  }

  async requestLocation(context: Context): Promise<void> {
    try {
      let atManager = abilityAccessCtrl.createAtManager()
      let grantStatus = await atManager.checkAccessToken(
        await this.getTokenId(context),
        'ohos.permission.APPROXIMATELY_LOCATION'
      )

      if (grantStatus !== abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED) {
        let result = await atManager.requestPermissionsFromUser(
          context,
          ['ohos.permission.APPROXIMATELY_LOCATION']
        )
        if (result.authResults[0] !== 0) {
          return
        }
      }

      let requestInfo: geoLocationManager.SingleLocationRequest = {
        locationTimeout: 10000,
        locatingTimeout: 15000,
      }

      let loc = await geoLocationManager.getCurrentLocation(requestInfo)
      if (loc && loc.latitude !== 0 && loc.longitude !== 0) {
        this.location = { latitude: loc.latitude, longitude: loc.longitude }
        this.located = true
      }
    } catch (e) {
    }
  }

  private async getTokenId(context: Context): Promise<number> {
    let bundleInfo = await bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT)
    return bundleInfo.appInfo.accessTokenId
  }
}
