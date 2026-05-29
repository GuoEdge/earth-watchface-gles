/**
 * libnativerender NAPI 类型声明
 *
 * ArkTS 侧通过此声明调用 Native 层函数。
 * 函数名必须与 napi_init.cpp 中 napi_property_descriptor 的名称一致。
 */
export const initScene: (width: number, height: number) => void;
export const renderFrame: (width: number, height: number, timeMs: number,
  hour: number, minute: number, second: number, nano: number,
  month: number, day: number, dayOfWeek: number, lunarText: string,
  isAmbient: boolean) => void;
export const updateSunDirection: (sunDir: number[]) => void;
export const updateConfig: (config: object) => void;
export const requestSpin: () => void;
export const updateData: (data: object) => void;
export const destroyScene: () => void;
export const loadTextures: (dayBuf: ArrayBuffer, nightBuf: ArrayBuffer, cloudBuf: ArrayBuffer) => void;
export const getSurfaceSize: () => { width: number, height: number };
