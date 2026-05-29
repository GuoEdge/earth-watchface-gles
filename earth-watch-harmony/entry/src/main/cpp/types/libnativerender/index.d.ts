export const initScene: (width: number, height: number) => void;
export const renderFrame: (width: number, height: number, timeMs: number,
  hour: number, minute: number, second: number, nano: number,
  month: number, day: number, dayOfWeek: number, lunarText: string,
  isAmbient: boolean) => void;
export const updateSunDirection: (sunDir: number[]) => void;
export const updateConfig: (config: object) => void;
export const requestSpin: () => void;
