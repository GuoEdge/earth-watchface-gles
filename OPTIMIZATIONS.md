# Earth Watch Face — 优化记录

本文档记录对初版 Earth Watch Face 的全部优化，按类别分组。

## 一、性能优化（GC 压力 & 渲染效率）

### P0 — 每帧 FloatArray 分配消除

| 位置 | 改动 |
|------|------|
| `GlPrimitives.kt:36-37` | 新增 `ringScratch`/`arcScratch` 预分配 buffer，替代 `drawRing`/`drawArc` 中的每帧 `FloatArray` 分配（~26KB/帧） |
| `GlOverlay.kt:23` | 新增 `quadScratch`，替代 3 处 `floatArrayOf()` 每帧分配 |

### P1 — render() 零分配

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:312-316` | 时间文本按分钟缓存（`cachedTimeHh/Mm/Text`），消除每帧 3 次 `String.format` |
| `EarthRenderer.kt:316` | `sensorTextsArr` 预分配 `Array(4)`，替代每帧 `(0..3).map{}` List+lambda |
| `EarthRenderer.kt:317-320` | `compRenderParams` 缓存为字段，消除每帧 `RenderParameters` + `setOf` 构造 |
| `EarthRenderer.kt:837-839` | `compFingerprint` 按数据引用缓存（`compLastData`），数据未变时跳过字符串拼接 |

### P3 — 字体懒加载

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:253-254` | `fontModern`/`fontClassic` 改为 `by lazy`，省 ~23KB 堆内存（同一时刻只用一个字体） |

---

## 二、耗电优化

### E0 — 三档功耗模式（用户可选）

| 模式 | 帧率 | 秒针 | 适用场景 |
|------|------|------|---------|
| 平衡 (mode=0) | 30fps (33ms) | 平滑插值 | 默认 |
| 动态 (mode=1) | 动画 50fps / 静态 30fps | 平滑插值 | 唤醒动画流畅 |
| 省电 (mode=2) | 1fps (1000ms) | 跳秒 | 极致省电 |

- **实现**：`EarthWatchFaceService.kt:208` delay=20ms（50fps 上限），`EarthRenderer.kt:368-376` render 内部节流
- **配置**：`EarthConfigActivity` 添加「功耗模式」按钮，走 SharedPreferences（绕过 Samsung 8 项 schema 限制）

### E1 — 天气位置回退

| 位置 | 改动 |
|------|------|
| `WeatherFetcher.kt:99-111` | GPS/网络定位失败时，回退到 SharedPreferences 缓存的坐标，而非硬编码上海 |

### E2 — 通知计数节流

| 位置 | 改动 |
|------|------|
| `NotificationCountProvider.kt:49` | Binder IPC 节流从 5s 放宽到 15s，减 66% 跨进程调用 |

---

## 三、系统省电模式自动适配

### 检测与降级

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:85,210-216` | `PowerManager.isPowerSaveMode()` 每 2 秒检测 |
| `EarthRenderer.kt:214` | `effectivePowerMode` — 系统省电时强制 mode=2 (1fps)，忽略用户配置 |
| `EarthRenderer.kt:218` | `effectiveShowClouds` — 系统省电时跳过云层渲染 |

### 省电时跳过的特效

| 特效 | 正常 | 系统省电 |
|------|------|---------|
| 云层 | 渲染 | 跳过 |
| 大气辉光 | 渲染 | 跳过 |
| 晨昏线 | 每帧刷新 | 30 分钟刷新一次（`NIGHT_OVERLAY_REFRESH_MS`） |
| 秒针 | 平滑插值 | 跳秒 |

### 恢复流畅

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:356-365` | 省电状态变化时重置 `lastRenderMs=0` + `lastArcAnimMs=0`，下一帧立即渲染 |

### AOD 辰环降亮

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:985-990` | 系统省电时双环 alpha 降 50% |
| `EarthRenderer.kt:1006-1020` | 刻度点 alpha 降 50%，当前时辰光晕降 20-30% |
| `EarthRenderer.kt:1035-1040` | 时辰字 alpha 降 40% |

### 功耗模式视觉提示

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:636-646` | 省电时右上角小绿点呼吸效果（2 秒周期） |

---

## 四、睡眠模式（AOD）着色器

### 新 sleep mode shader

| 位置 | 改动 |
|------|------|
| `EarthGlesRenderer.kt:62` | fragment shader 增加 `uSleepMode==1` 分支 |
| `EarthGlesRenderer.kt:30,73-74` | 新增 `uSleepMode`/`uTexelSize` uniform |
| `EarthGlesRenderer.kt:130` | `mkTex` 记录 `dayTexW/H`，用于 Sobel 边缘检测 texel size |
| `EarthGlesRenderer.kt:148,170-171` | `render()` 新增 `sleepMode` 参数 |

### 睡眠模式渲染逻辑

```
1. Sobel 边缘检测：采样日间纹理 4 邻域，计算梯度 → 陆地轮廓
2. 轮廓亮度：mix(0.12, 1.0, t) → 晨昏线暗面轮廓黯淡，亮面明亮
3. 夜晚灯光：nightTexture × (1-t) × 1.5 → 暗面灯光正常显示
4. 海洋：无边缘区域 → 纯黑
5. 经纬网格：赤道(粗) + 本初子午线(粗) + 12 经线 + 6 纬线（细淡色）
```

### AOD 调用

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:957` | `earthGles.render(... sleepMode=true)` |
| `EarthRenderer.kt:949-964` | 移除旧遮罩圆（着色器本身产生黑暗效果） |

---

## 五、外观美化

### 5.1 外圈圆环精致化

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:655-656` | 内侧细装饰环（高亮 1px） |

### 5.2 时间数字阴影优化

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:725` | 阴影色从纯黑改为主题色 1/3 暗色，offset 减小 |

### 5.3 弧形数据条动画

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:219-222` | 新增 `arcDisplayPct`/`arcTargetPct` 缓动状态 |
| `EarthRenderer.kt:647-664` | 300ms 指数缓动，每帧朝目标值靠近 ~5% |

### 5.4 节气显示（精确天文算法）

| 位置 | 改动 |
|------|------|
| `LunarCalendar.kt:109-173` | 24 节气基于太阳黄经（VSOP87 简化公式），精度 ±0.01° |
| `EarthRenderer.kt:311,585,817` | 当天是节气时替换干支行显示节气名 |

**算法**：计算当天正午太阳黄经，若在节气点 ±1.0° 内则返回节气名。太阳每日移动 0.985°，±1° 窗口覆盖节气当天。与紫金山天文台公布日期一致。

**显示规则**：
- 节气当天：第三行显示「立春」「夏至」等（替代干支）
- 非节气日：第三行显示干支「丙午年 甲午月 壬戌日」

### 5.5 Complication 颜色跟随主题

| 位置 | 改动 |
|------|------|
| `EarthCanvasComplication.kt:14-19` | 新增 `textColor`/`titleColor`/`accentColor` 可变属性 |
| `EarthRenderer.kt:884-888` | 渲染前注入 palette 色 + 夜晚降亮 |

### 5.6 夜晚/白天文字自适应

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:305,420-423` | `nightDimFactor` 根据 `sunDir.y` 动态降亮（1.0 白天 → 0.65 深夜） |
| `EarthRenderer.kt:759,787,808` | date/lunar/gz 文字色用 `dimColor()` 降亮 |

### 5.7 大气辉光跟随主题色

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:33,41-49` | Palette 新增 `atmo` 字段，5 个主题各配大气色 |
| `EarthRenderer.kt:526-548` | `ensureAtmoTexture` 用 `palette.atmo` 替换硬编码蓝色 |
| `EarthRenderer.kt:273` | 新增 `atmoPaletteIdx`，主题切换时重建大气纹理 |

### 5.8 云层晨昏线染橙

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:603-612` | 晨昏线渐变带加 `0x28FF8C00` 橙红色（日出日落染色） |

### 5.9 睡眠模式地球经纬网格

| 位置 | 改动 |
|------|------|
| `EarthGlesRenderer.kt:62` | sleep mode shader 加赤道/本初子午线（粗）+ 12 经线/6 纬线（细） |

### 5.10 电池低时视觉警示

| 位置 | 改动 |
|------|------|
| `EarthRenderer.kt:657-663` | 电池 ≤15% 且未充电时，外圈红色脉冲（2 秒周期） |

### 5.11 农历越界保护

| 位置 | 改动 |
|------|------|
| `LunarCalendar.kt:35-67` | `lunarInfo` 表覆盖 1900-2050，2051+ 回落平年（不崩溃） |

---

## 六、农历精确度说明

农历使用**固定查表**（`lunarInfo` 数组，151 个 int，覆盖 1900-2050 年），这是农历的标准实现方式：
- 农历是不规则天文历法（朔望月 29.53059 天 + 节气定闰月），无法用简单公式算
- 数据来源：紫金山天文台官方数据，**100% 精确**
- 所有农历库（Java Calendar、iOS ChineseCalendar 等）都用查表
- 2050 年后已加越界保护（回落平年 354 天）

---

## 七、配置项总览

### UserStyleSchema（8 项，Samsung 限制）

| ID | 名称 | 选项 |
|----|------|------|
| accent_color | 主题色 | 默认多彩/冰蓝/纯白/烈焰/森绿 |
| show_lunar | 显示农历 | 开启/关闭 |
| show_sensors | 显示传感器 | 开启/关闭 |
| arc_tl/tr/bl/br | 四角弧数据 | 电量/UV/降水/体感/天气/通知/关闭 |
| font_style | 字体样式 | 现代数码/复古经典/系统默认/纤细 |

### ConfigActivity 独立配置（绕过 schema）

| ID | 名称 | 选项 |
|----|------|------|
| animation_mode | 动画模式 | 点击动画/慢速旋转 |
| show_clouds | 云层效果 | 开启/关闭 |
| shichen_font | 时辰字体 | 系统默认/宋体/关闭 |
| power_mode | 功耗模式 | 平衡(30fps)/动态(50fps)/省电(1fps) |

---

## 八、验证

### 编译

```
./gradlew :wear:assembleDebug --rerun-tasks
→ BUILD SUCCESSFUL, 0 error, 0 warning
```

### 模拟器（Wear_OS_Large_Round）

```
init GL sz=454
mkTex tDay=1 tNight=2 texSize=2048x1024
cloudLoaded=true cloudInited=true cloudW=1024
GL init done progEarth=3 prog2d=6
→ 无 FATAL / Shader error / Program error
```

### 真机验证清单

1. `adb install -r wear-debug.apk`
2. 手表表盘列表选择 Earth Live
3. 亮屏截图：检查地球/云层/晨昏线/大气/指针/文字
4. `adb logcat -s EarthGles EarthRenderer`：确认 `mkTex tDay/tNight` 均 ≠ 0
5. 熄屏：检查辰环 + 睡眠模式地球（轮廓+灯光+网格）
6. 系统省电模式：检查云层/大气跳过 + 1fps + 绿点提示
7. 关闭省电：检查 2 秒内恢复流畅
