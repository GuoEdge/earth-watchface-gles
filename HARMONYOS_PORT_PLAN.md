# EarthWatch 地球表盘 — 华为手表移植方案说明文档

> 版本: 1.0 | 日期: 2026-05-29

---

## 一、项目现状

### 1.1 源项目概况

EarthWatch 是一个 Wear OS 3D 地球表盘，核心视觉是一颗 30° 俯视角的 3D 地球，带真实日夜晨昏线、球面云层、大气辉光，叠加指针、刻度、数字时钟、农历等表盘元素。

| 项目 | 说明 |
|------|------|
| 语言 | Kotlin |
| 渲染 | OpenGL ES 2.0, 6 个 Shader Program |
| 框架 | Wear OS `WatchFaceService` + `GlesRenderer2` |
| 设备 | Samsung Galaxy Watch 4 (Mali-G68, API 36) |
| 最低要求 | Wear OS 3.0+ (API 30+) |

### 1.2 源文件清单与职责

| 文件 | 行数 | 职责 | 移植难度 |
|------|------|------|---------|
| `EarthWatchFaceService.kt` | 271 | 入口：Wear OS 服务注册、配置面板定义、Complication 槽位 | 🔴 重写 |
| `EarthConfigActivity.kt` | 117 | 配置界面：颜色/功能/字体切换 | 🟡 重写 |
| `EarthRenderer.kt` | 957 | 渲染管线主控：初始化、每帧调度、2D 叠加、熄屏 | 🔴 重写 |
| `EarthGlesRenderer.kt` | 169 | 3D 地球球体渲染（1 个 shader program） | 🟡 C++/ArkTS 移植 |
| `GlPrimitives.kt` | 271 | 2D 图元库：纯色/纹理/SDF（3 个 shader program） | 🟢 ArkUI Canvas 替代 |
| `GlOverlay.kt` | 199 | 云层球面投影 + 大气辉光 + 晨昏线（2 个 shader program） | 🔴 架构重构 |
| `SunCalculator.kt` | 17 | 太阳方向计算（赤纬+时角） | 🟢 ArkTS 直译 |
| `LunarCalendar.kt` | 108 | 农历 + 干支纪日 | 🟢 ArkTS 直译 |
| `WeatherFetcher.kt` | 177 | Open-Meteo 天气异步拉取 | 🟢 ArkTS 重写 |
| `NotificationCountProvider.kt` | 51 | 通知计数 | 🟡 `@ohos.notification` |
| `TextTextureCache.kt` | 82 | 文字→GL 纹理缓存 | 🟢 ArkUI Text 替代 |
| `EarthCanvasComplication.kt` | 132 | Complication 渲染 | 🔴 Wear Engine 替代 |

### 1.3 渲染管线每帧执行顺序

```
① validateGlContext() → GL 上下文丢失检测
② readStyle() → 配置读取 (SharedPreferences)
③ 旋转计算 → 唤醒动画/慢旋/静态
④ glClear(dark blue #000006)
⑤ 3D 地球渲染 (独立 viewport, 透视投影, 92K 三角形)
⑥ resetGlState() → 解绑 VBO/IBO/纹理
⑦ 2D 正交投影叠加:
   ├─ 云层 (progCloud 球面 UV 投影, 匹配地球旋转)
   ├─ 晨昏线 (LinearGradient→球面映射遮罩)
   ├─ 大气辉光 (径向渐变纹理)
   ├─ 边缘环 ×3 + 刻度 ×12
   ├─ 弧形数据条 ×4 (SDF 渲染)
   ├─ 指针 ×3 + 阴影 ×3 + 中心点 ×2 (SDF 渲染)
   ├─ 时间文字 ×3 (纹理四边形)
   ├─ 日期 + 农历 + 干支 (纹理四边形)
   ├─ 传感器文字 ×4 (纹理四边形)
   └─ Complication ×4 (纹理四边形)
```

### 1.4 精确资源消耗

| 资源 | 规格 | GPU 显存 |
|------|------|---------|
| 地球日面纹理 | 2048×1024 RGB + mipmap | 8 MB |
| 地球夜景纹理 | 2048×1024 RGB + mipmap | 8 MB |
| 云层纹理 | 1024×512 索引色 | 0.67 MB |
| 大气辉光纹理 | 运行时生成 | ~0.5 MB |
| 晨昏线纹理 | 运行时生成 | ~0.5 MB |
| 文字纹理缓存 | ~10 个小纹理 | ~1 MB |
| 球体 VBO/IBO | 15,360 顶点 + 92,160 indices | 0.66 MB |
| **合计** | | **~19 MB** |

### 1.5 每帧 Draw Call 统计

| 阶段 | Draw Calls | GPU 开销 |
|------|-----------|---------|
| 3D 地球 | 1 | 高 (92K 三角形, 2 次纹理采样) |
| 云层 | 1 | 中 (球面投影 shader) |
| 晨昏线 | 1 | 中 |
| 大气辉光 | 1 | 低 |
| 边缘环 | 3 | 低 |
| 刻度线 | 12 | 低 |
| 弧形 | 8 | 中 (SDF) |
| 指针+阴影 | 8 | 中 (SDF) |
| 中心点 | 2 | 低 |
| 文字 | ~10 | 低 |
| Complication | ~4 | 低 |
| **合计** | **~51** | |

---

## 二、华为手表生态分析

### 2.1 设备分类

| 类别 | 设备 | 系统 | OpenGL ES | 表盘开发方式 |
|------|------|------|-----------|------------|
| 智能穿戴 | **Watch 5** | HarmonyOS 5+ | ✅ 支持 | 应用开发 (ArkTS + C++ NDK) |
| 轻量穿戴 | GT 2/3/4/5/6, Fit 2/3/4, D/D2, Ultimate | LiteOS | ❌ 不支持 | Theme Studio 拖拽 |

**结论：仅 Watch 5 支持自定义 3D 渲染表盘。**

### 2.2 Watch 5 硬件规格

| 参数 | 46mm 版 | 42mm 版 |
|------|---------|---------|
| 屏幕 | 1.5" LTPO 2.0 AMOLED | 1.38" LTPO 2.0 AMOLED |
| 分辨率 | 466×466, 310 PPI | 466×466, 338 PPI |
| 刷新率 | 最高 120Hz (自适应) | 最高 120Hz (自适应) |
| 峰值亮度 | 3000 nits | 3000 nits |
| 电池 | ~790mAh | ~540mAh |
| 续航 (全能+AOD) | 3 天 | 2 天 |
| GPU | 未公开 (推测 Mali-G57/G68 级别) | 同左 |

### 2.3 HarmonyOS 应用架构 (Stage 模型)

Wear OS 的 `WatchFaceService` 在 HarmonyOS 上不存在。表盘在 HarmonyOS 上是一个**普通应用**，使用 `UIAbility` 作为入口。

```
Wear OS:                              HarmonyOS:
WatchFaceService                      UIAbility
  ├─ onCreateEngine()                   ├─ onCreate()
  └─ Engine                             ├─ onWindowStageCreate()
       └─ GlesRenderer2                 │    └─ loadContent(Index)
            ├─ onGlContextCreated()      ├─ onForeground()
            ├─ onSurfaceChanged()        ├─ onBackground()
            └─ onRenderFrame()           └─ onDestroy()
```

**关键差异**：
- 没有系统级表盘服务，需自行管理生命周期
- 没有系统 `onAmbientModeChanged` 回调，需自行检测屏幕状态
- 没有 Complication 系统，需用 `@ohos.sensor` + Wear Engine 替代

---

## 三、两种技术方案

### 方案 A：XComponent + OpenGL ES (C++ NDK)

**原理**：通过 XComponent 获取 NativeWindow，在 C++ 层直接调用 EGL/OpenGL ES，与现有 Wear OS 渲染管线最接近。

```
┌──────────────────────────────────────┐
│  ArkTS 应用层                         │
│  UIAbility + ArkUI 页面               │
├──────────────────────────────────────┤
│  XComponent (SURFACE/TEXTURE)         │
│  ←→ NativeWindow ←→ EGL Surface      │
├──────────────────────────────────────┤
│  C++ Native 渲染层 (NDK)              │
│  EGL 上下文 + OpenGL ES 渲染          │
│  地球/云层/晨昏线/大气/2D 图元        │
├──────────────────────────────────────┤
│  NAPI 桥接                            │
│  ArkTS ←→ C++ (太阳方向/配置/时间)    │
├──────────────────────────────────────┤
│  ArkTS 业务逻辑层                     │
│  SunCalculator / LunarCalendar / ...  │
└──────────────────────────────────────┘
```

**优势**：
- 渲染管线与现有代码几乎 1:1 对应，C++ 移植量可控
- 完全控制 GL 状态机，可复用云层球面投影等非标准渲染
- 只需 OpenGL ES 2.0，GPU 要求最低
- 性能上限最高，可精确优化每个 draw call

**劣势**：
- 需要 C++ 代码 (~3000-5000 行)，双层语言维护
- 手动管理 EGL 上下文生命周期
- 调试困难 (C++ + GL 联调)
- 开发工期 18-26 天

### 方案 D：ArkGraphics 3D + Component3D (纯 ArkTS)

**原理**：使用华为原生 3D 引擎 ArkGraphics 3D，通过 Component3D 渲染 3D 场景，2D 表盘元素用 ArkUI Canvas/Text 叠加。

```
┌──────────────────────────────────────┐
│  ArkUI 叠加层 (2D 表盘元素)           │
│  Text(时间) / Canvas(指针/弧形/刻度)  │
├──────────────────────────────────────┤
│  Component3D (TEXTURE 模式)           │
│  ArkGraphics 3D 场景                  │
│  ├─ 地球球体 (GLB) + 自定义 Shader    │
│  ├─ 云层球体 (稍大, 半透明)           │
│  ├─ DirectionalLight (太阳方向)       │
│  └─ Camera (固定视角)                 │
├──────────────────────────────────────┤
│  ArkTS 业务逻辑层                     │
│  SunCalculator / LunarCalendar / ...  │
└──────────────────────────────────────┘
```

**优势**：
- 纯 ArkTS，无 C++ 代码，开发效率高
- 引擎自动管理 GL 上下文，无需手动 EGL
- 2D 部分用 ArkUI Canvas/Text，draw calls 大幅减少
- 文字渲染零纹理开销

**劣势**：
- 引擎 API 灵活性受限：无法做 2D 球面投影 shader、多 pass 渲染
- 云层/晨昏线需用 3D 球体替代，架构需重构
- 自定义 Shader 需编译为 SPIR-V，工具链复杂
- 要求 OpenGL ES 3.2+ / Vulkan 1.0+
- 文档和示例极少，踩坑风险高
- 开发工期 12-18 天 (如果顺利)

---

## 四、推荐方案：混合架构

综合两种方案的优势，推荐**分阶段混合架构**：

### 4.1 总体策略

```
阶段 1: XComponent + OpenGL ES 做最小验证 (3-5 天)
  → 验证 Watch 5 GPU 能力
  → 验证 XComponent + EGL 渲染管线可行性
  → 产出: 旋转球体 demo

阶段 2: 根据验证结果选择路线
  → 路线 A: XComponent + OpenGL ES (完整 C++ 移植)
  → 路线 D: ArkGraphics 3D + Component3D (纯 ArkTS)
  → 路线 E: 混合 (ArkGraphics 3D 做 3D + ArkUI Canvas 做 2D)
```

### 4.2 推荐路线 E：ArkGraphics 3D (3D) + ArkUI Canvas (2D)

**理由**：
1. 3D 地球用 ArkGraphics 3D 引擎渲染，省去 C++ EGL 管理
2. 2D 表盘元素用 ArkUI Canvas/Text，性能更优 (批量渲染, 零纹理开销)
3. 云层用 3D 球体替代 2D 球面投影，视觉等效
4. 纯 ArkTS，维护成本低

**如果 ArkGraphics 3D 遇到不可解决的问题**（如 SPIR-V 编译失败、API 不支持所需功能），降级到路线 A (XComponent + OpenGL ES)。

### 4.3 模块映射

| Wear OS 模块 | HarmonyOS 替代 | 实现方式 | 移植难度 |
|-------------|---------------|---------|---------|
| `WatchFaceService` | `UIAbility` | 重写 | 🔴 |
| `GlesRenderer2` | `Component3D` (TEXTURE) | 替换 | 🔴 |
| `EarthGlesRenderer` | ArkGraphics 3D Scene + GLB 模型 | 重构 | 🟡 |
| 地球 shader (GLSL) | 自定义 Shader 材质 (.shader + SPIR-V) | 改写+编译 | 🟡 |
| `GlOverlay` 云层 | 3D 云层球体 (ArkGraphics 3D) | 架构重构 | 🔴 |
| `GlOverlay` 晨昏线 | DirectionalLight 阴影 / 自定义 shader uniform | 重新设计 | 🔴 |
| `GlOverlay` 大气辉光 | ArkUI Canvas 径向渐变 | 简化 | 🟢 |
| `GlPrimitives` 边缘环 | `Canvas.drawCircle(STROKE)` | 替代 | 🟢 |
| `GlPrimitives` 刻度线 | `Canvas.drawLine()` | 替代 | 🟢 |
| `GlPrimitives` SDF 弧形 | `Canvas.drawArc()` + `Paint.strokeCap=ROUND` | 替代 | 🟢 |
| `GlPrimitives` SDF 指针 | `Canvas.drawLine(ROUND_CAP)` | 替代 | 🟢 |
| `TextTextureCache` | ArkUI `Text()` 组件 | 替代 | 🟢 |
| `SunCalculator` | ArkTS 直译 | 翻译 | 🟢 |
| `LunarCalendar` | ArkTS 直译 | 翻译 | 🟢 |
| `WeatherFetcher` | ArkTS `@ohos.net.http` | 重写 | 🟢 |
| `NotificationCountProvider` | `@ohos.notification` | 重写 | 🟡 |
| `EarthConfigActivity` | ArkUI 声明式配置页面 | 重写 | 🟡 |
| Complication 系统 | `@ohos.sensor` + 自实现 | 重新设计 | 🔴 |
| AOD 熄屏 | 自检测 + 简化渲染 | 重新设计 | 🔴 |
| `SharedPreferences` | `@StorageLink` / `PersistentStorage` | 替换 | 🟢 |

---

## 五、详细实现路径

### 5.1 阶段 1：环境搭建与最小验证 (3-5 天)

**目标**：验证 Watch 5 上 3D 渲染的可行性

**步骤**：

1. 安装 DevEco Studio 6.0+，配置 HarmonyOS SDK (Wearable)
2. 注册华为开发者账号，配置真机调试证书
3. 创建 Wearable 项目 (ArkTS + C++ NDK)
4. 实现 XComponent + EGL + OpenGL ES 最小 demo：
   - 创建 80×192 球体
   - 加载 earth_day.jpg 纹理
   - 透视投影 + 旋转动画
5. 在 Watch 5 真机上测试帧率

**验证指标**：

| 指标 | 通过标准 |
|------|---------|
| EGL 上下文创建 | 成功 |
| 球体渲染 | 可见，无闪烁 |
| 帧率 (单纹理) | ≥ 30fps |
| GPU 显存占用 | < 50MB |
| 设备温度 | < 38°C (5 分钟运行) |

**如果验证失败**：说明 Watch 5 GPU 不满足要求，终止移植。

### 5.2 阶段 2：3D 地球核心渲染 (5-7 天)

**目标**：实现完整的 3D 地球渲染（日夜混合 + 太阳方向）

**ArkGraphics 3D 路线**：

1. 用 Blender 生成 80×192 球体，导出为 `earth.glb`
2. 编写地球自定义 Shader：
   - 顶点着色器：MVP 变换 + 法线变换 + UV 传递
   - 片段着色器：日夜纹理混合 + smoothstep 晨昏线 + 镜面高光
   - 编译为 SPIR-V：`glslangValidator -V earth.vert -o earth.vert.spv`
   - 编写 `.shader` JSON 描述文件
3. ArkTS 场景搭建：
   - `Scene.load($rawfile('gltf/earth/earth.glb'))`
   - `createCamera()` + `createLight(DIRECTIONAL)`
   - `createShader()` + `createMaterial(SHADER)` + 绑定纹理
4. 动态更新太阳方向：
   - `AnimatorResult` 帧驱动
   - 每帧 `ShaderMaterial.setProperty('SUN_DIRECTION', sunDir)`
   - 每帧 `DirectionalLight.rotation = ...`

**XComponent 路线 (备选)**：

1. 将 `EarthGlesRenderer.kt` 的 GL 调用移植到 C++
2. 通过 NAPI 传入太阳方向和旋转角度
3. Shader 代码 (GLSL) 直接复用

### 5.3 阶段 3：云层 + 晨昏线 + 大气 (3-5 天)

**ArkGraphics 3D 方案**：

| 效果 | 实现方式 | 说明 |
|------|---------|------|
| 云层 | 创建第二个稍大球体 (scale 1.02)，贴云层纹理，半透明 Shader 材质 | 替代原有 2D 球面投影 |
| 晨昏线 | 在地球 Shader 中增加 `uSunDirection` uniform，fragment shader 中根据法线-太阳夹角叠加暗色 | 替代原有 2D 遮罩 |
| 大气辉光 | ArkUI Canvas 2D 绘制径向渐变圆，叠加在 Component3D 上方 | 简化实现 |

**关键：晨昏线 shader 改造**

现有地球 shader 已包含日夜混合逻辑 (`smoothstep(-0.18, 0.10, NdotL)`)，晨昏线效果已内置。只需确保 `uSunDirection` 正确传入即可。原有的 2D 晨昏线遮罩可以**直接去掉**，因为 3D 地球 shader 本身已实现了等效效果。

**关键：云层球体实现**

```
1. 创建第二个 GLB 球体 (与地球相同几何，scale 1.02)
2. 自定义云层 Shader:
   - 采样云层纹理 (uCloudTex)
   - 应用地球相同的旋转 (uRotY)
   - UV 偏移实现云层漂移 (uCloudOffset)
   - 半透明混合 (alpha = cloudColor.r * 0.6)
3. 放置在地球球体同一位置，渲染顺序在地球之后
```

### 5.4 阶段 4：2D 表盘元素 (3-4 天)

**目标**：用 ArkUI Canvas + Text 实现所有 2D 表盘元素

**实现方式**：

```typescript
build() {
  Stack() {
    // 3D 地球层
    Component3D({ sceneOptions: this.sceneOpt })
      .width('100%').height('100%')

    // 2D 叠加层 (Canvas)
    Canvas(this.canvasContext)
      .width('100%').height('100%')
      .onReady(() => this.drawWatchFace())

    // 文字层 (Text 组件)
    Column() {
      Text(this.timeText)
        .fontSize(48).fontColor(Color.White)
        .fontFamily(this.fontFamily)
        .shadow({ radius: 6, offsetY: 4, color: 0xCC000000 })
      Text(this.dateText).fontSize(16).fontColor(Color.White)
      // ...
    }
  }
}

drawWatchFace() {
  const ctx = this.canvasContext
  // 边缘环
  ctx.strokeStyle = this.palette.rim
  ctx.lineWidth = 2
  ctx.beginPath(); ctx.arc(cx, cy, ir, 0, Math.PI * 2); ctx.stroke()
  // 刻度
  for (let i = 0; i < 12; i++) { /* ... */ }
  // 弧形数据条
  for (let i = 0; i < 4; i++) { /* ... */ }
  // 指针
  this.drawHand(ctx, cx, cy, hourLen, hourDeg, 7, this.palette.hand)
  this.drawHand(ctx, cx, cy, minLen, minDeg, 5, this.palette.hand)
  this.drawHand(ctx, cx, cy, secLen, secDeg, 3.5, this.palette.second)
  // 中心点
  ctx.fillStyle = '#FF4500'
  ctx.beginPath(); ctx.arc(cx, cy, 5, 0, Math.PI * 2); ctx.fill()
}
```

**性能优势**：
- Canvas 内部批量提交 GPU 指令，draw calls 从 ~51 降到 ~5
- Text 组件直接渲染，零纹理转换开销
- 无需 TextTextureCache

### 5.5 阶段 5：业务逻辑移植 (2-3 天)

| 模块 | 移植方式 | 工作量 |
|------|---------|--------|
| `SunCalculator.kt` (17 行) | ArkTS 直译，逻辑不变 | 0.5 天 |
| `LunarCalendar.kt` (108 行) | ArkTS 直译，lunarInfo 数组搬过去 | 1 天 |
| `WeatherFetcher.kt` (177 行) | `@ohos.net.http` 替代 `HttpURLConnection` | 1 天 |
| `NotificationCountProvider.kt` | `@ohos.notification` API | 0.5 天 |

### 5.6 阶段 6：配置系统 + 交互 (2-3 天)

| Wear OS | HarmonyOS | 说明 |
|---------|-----------|------|
| `UserStyleSchema` | `@State` + `PersistentStorage` | 配置项定义 |
| `EarthConfigActivity` | ArkUI 声明式页面 | 配置界面 |
| `SharedPreferences` | `@StorageLink` | 持久化 |
| `WatchFace.TapListener` | `GestureGroup(Tap)` | 点击交互 |
| Complication slots | `@ohos.sensor` + 自绘 | 数据展示 |

**配置项映射**：

| 配置项 | Wear OS | HarmonyOS |
|--------|---------|-----------|
| 主题色 | `ListUserStyleSetting` | `@StorageLink('accent_color') number` |
| 显示农历 | `ListUserStyleSetting` | `@StorageLink('show_lunar') number` |
| 显示传感器 | `ListUserStyleSetting` | `@StorageLink('show_sensors') number` |
| 动画模式 | `ListUserStyleSetting` | `@StorageLink('animation_mode') number` |
| 云层效果 | `ListUserStyleSetting` | `@StorageLink('show_clouds') number` |
| 字体样式 | `ListUserStyleSetting` | `@StorageLink('font_style') number` |
| 4 个弧形数据源 | `ListUserStyleSetting` ×4 | `@StorageLink('arc_tl')` 等 |

### 5.7 阶段 7：AOD 熄屏适配 (2-3 天)

**HarmonyOS AOD 机制**：

| 维度 | Wear OS | HarmonyOS |
|------|---------|-----------|
| 触发方式 | 系统 `onAmbientModeChanged` | 自检测 `@ohos.display` 屏幕状态 |
| 像素限制 | ≤ 10% 像素点亮 | ≤ 20% 非黑像素 |
| 帧率 | ~1fps | ~1fps |

**实现方案**：
1. 监听 `@ohos.display` 屏幕状态变化
2. 熄屏时切换到 AOD 渲染模式：
   - 3D 地球：保留但降低 alpha，覆盖半透明黑色遮罩
   - 2D 元素：只保留时间 + 日期 + 时分指针
   - 去掉秒针、传感器、弧形、云层
3. 限制非黑像素 ≤ 20% (466×466 = 217156 像素，最多 43431 非黑)

### 5.8 阶段 8：性能调优 + 真机测试 (3-5 天)

**优化策略**：

| 策略 | 效果 | 实现方式 |
|------|------|---------|
| 纹理降采样 | GPU 显存 -12MB | 2048×1024 → 1024×512 |
| KTX 压缩纹理 | GPU 显存 -60%, 带宽 -60% | PNG/JPG → ETC2/KTX |
| 球体降精度 | 三角形 -75% | 80×192 → 40×96 |
| 帧率限制 | 功耗 -50% | 交互 30fps, 慢旋 15fps |
| 省电模式 | 功耗 -80% | 静态地球 + 2D 表盘 |
| 温度监控 | 防止过热降频 | `@ohos.batteryInfo` 温度读取 |

---

## 六、工程目录结构

```
EarthWatchHarmony/
├── AppScope/
│   └── app.json5
├── entry/
│   ├── src/main/
│   │   ├── cpp/                              # C++ Native 层 (仅 XComponent 路线)
│   │   │   ├── CMakeLists.txt
│   │   │   ├── napi_init.cpp
│   │   │   └── render/
│   │   │       ├── egl_core.cpp / .h
│   │   │       └── plugin_render.cpp / .h
│   │   ├── ets/
│   │   │   ├── entryability/
│   │   │   │   └── EntryAbility.ets          # 应用入口
│   │   │   ├── pages/
│   │   │   │   ├── Index.ets                 # 主表盘页面
│   │   │   │   └── ConfigPage.ets            # 配置页面
│   │   │   ├── model/
│   │   │   │   ├── SunCalculator.ets         # 太阳方向
│   │   │   │   ├── LunarCalendar.ets         # 农历
│   │   │   │   ├── WeatherFetcher.ets        # 天气
│   │   │   │   └── SensorDataProvider.ets    # 传感器数据
│   │   │   ├── viewmodel/
│   │   │   │   └── EarthViewModel.ets        # 表盘状态管理
│   │   │   └── view/
│   │   │       ├── EarthSceneView.ets        # 3D 地球组件
│   │   │       ├── WatchFaceOverlay.ets      # 2D 叠加层 (Canvas)
│   │   │       └── AodView.ets               # AOD 熄屏视图
│   │   └── resources/
│   │       ├── base/
│   │       │   ├── element/                  # 颜色/字符串
│   │       │   └── profile/                  # 页面路由
│   │       └── rawfile/
│   │           ├── gltf/earth/
│   │           │   └── earth.glb             # 地球球体模型
│   │           ├── textures/
│   │           │   ├── earth_day.ktx         # 日面纹理 (KTX 压缩)
│   │           │   ├── earth_night.ktx       # 夜景纹理
│   │           │   └── earth_cloud.ktx       # 云层纹理
│   │           ├── shaders/
│   │           │   ├── earth/
│   │           │   │   ├── earth.shader      # 地球 shader 描述
│   │           │   │   ├── earth.vert.spv    # 顶点着色器 SPIR-V
│   │           │   │   └── earth.frag.spv    # 片段着色器 SPIR-V
│   │           │   └── cloud/
│   │           │       ├── cloud.shader
│   │           │       ├── cloud.vert.spv
│   │           │       └── cloud.frag.spv
│   │           └── fonts/
│   │               ├── DSEG7Modern-Bold.ttf
│   │               └── DSEG7Classic-Bold.ttf
│   ├── build-profile.json5
│   └── oh-package.json5
├── build-profile.json5
└── oh-package.json5
```

---

## 七、性能预测

### 7.1 帧时间估算

| 阶段 | XComponent 方案 | ArkGraphics 3D 方案 |
|------|----------------|-------------------|
| 3D 地球 | 2-4ms | 3-6ms (引擎封装开销) |
| 云层 | 1-2ms | 1-2ms (3D 球体) |
| 晨昏线 | 0.5ms | 0ms (内置在地球 shader) |
| 大气辉光 | 0.3ms | 0.3ms (Canvas 渐变) |
| 2D 表盘 | 1-2ms (35+ draw calls) | 0.5-1ms (Canvas 批量) |
| 文字 | 0.5-1ms (纹理转换) | 0.1ms (Text 直接渲染) |
| **合计** | **5-10ms** | **5-10ms** |
| **等效帧率** | **100-200fps** | **100-200fps** |
| **限制后** | **30fps** | **30fps** |

### 7.2 GPU 显存对比

| 项目 | Wear OS (现有) | HarmonyOS (优化后) |
|------|---------------|-------------------|
| 日面纹理 | 8MB (2048×1024 RGB+mipmap) | 2MB (1024×512 ETC2) |
| 夜景纹理 | 8MB (2048×1024 RGB+mipmap) | 2MB (1024×512 ETC2) |
| 云层纹理 | 0.67MB | 0.25MB (512×256 ETC2) |
| 其他 | ~2.5MB | ~1MB |
| **合计** | **~19MB** | **~5MB** |

### 7.3 功耗预估

| 场景 | Wear OS | HarmonyOS (30fps 限制) | HarmonyOS (省电模式) |
|------|---------|----------------------|---------------------|
| 交互模式续航 | 基准 | 接近基准 | 优于基准 |
| AOD 续航 | 基准 | 接近基准 | 接近基准 |

---

## 八、风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| Watch 5 GPU 不支持 GLES 3.2 | 低 (10%) | ArkGraphics 3D 方案不可用 | 降级到 XComponent + GLES 2.0 |
| SPIR-V Shader 编译失败 | 中 (40%) | 自定义材质不可用 | 先用 PBR 材质验证，逐步迁移 |
| ArkGraphics 3D API 不支持所需功能 | 中高 (50%) | 云层/晨昏线无法实现 | 降级到 XComponent 方案 |
| Watch 5 GPU 性能不足 | 低 (15%) | 帧率低于 30fps | 降低球体精度 + 纹理分辨率 |
| 功耗过高续航缩短 | 中 (50%) | 用户卸载 | 提供省电模式 + 帧率限制 |
| AOD 机制适配困难 | 中 (40%) | 熄屏显示异常 | 简化 AOD 为纯 2D |
| 华为应用审核不通过 | 低 (20%) | 无法上架 | 提前了解审核要求 |

---

## 九、工期与里程碑

| 阶段 | 任务 | 工期 | 里程碑 |
|------|------|------|--------|
| **M1** | 环境搭建 + XComponent 最小验证 | 3-5 天 | 旋转球体在 Watch 5 上运行 |
| **M2** | 3D 地球核心渲染 (日夜混合) | 5-7 天 | 完整 3D 地球 + 太阳方向 |
| **M3** | 云层 + 晨昏线 + 大气 | 3-5 天 | 叠加层效果完成 |
| **M4** | 2D 表盘元素 (Canvas + Text) | 3-4 天 | 指针/刻度/弧形/文字 |
| **M5** | 业务逻辑移植 | 2-3 天 | 农历/天气/传感器 |
| **M6** | 配置系统 + 交互 | 2-3 天 | 配置页面 + 点击旋转 |
| **M7** | AOD 熄屏适配 | 2-3 天 | 熄屏显示正常 |
| **M8** | 性能调优 + 真机测试 | 3-5 天 | 达到 30fps + 省电模式 |
| **合计** | | **23-35 天** | |

---

## 十、决策点

| 决策点 | 时机 | 判断标准 | 选择 |
|--------|------|---------|------|
| 是否继续移植 | M1 结束 | Watch 5 GPU 能否跑 30K 三角形 @30fps | 是→继续, 否→终止 |
| 选择 3D 渲染路线 | M1 结束 | ArkGraphics 3D 是否可用 | 可用→路线 E, 不可用→路线 A |
| 是否需要纹理降采样 | M2 结束 | GPU 显存是否够用 | 够→保持 2048, 不够→降到 1024 |
| 是否需要球体降精度 | M2 结束 | 帧率是否达标 | 达标→保持 80×192, 不达标→降到 40×96 |
| AOD 实现方式 | M7 开始 | 是否需要 3D 地球 AOD | 需要→3D 简化, 不需要→纯 2D |

---

## 附录 A：Shader 移植对照

### 地球顶点着色器

**Wear OS (GLSL ES 2.0)**：
```glsl
uniform mat4 uMVPMatrix;
uniform mat4 uModelMatrix;
attribute vec4 aPosition;
attribute vec3 aNormal;
attribute vec2 aTexCoord;
varying vec3 vNormal;
varying vec3 vWorldPos;
varying vec2 vTexCoord;
void main() {
  vNormal = normalize(mat3(uModelMatrix) * aNormal);
  vec4 wp = uModelMatrix * aPosition;
  vWorldPos = wp.xyz;
  vTexCoord = aTexCoord;
  gl_Position = uMVPMatrix * aPosition;
}
```

**HarmonyOS (GLSL 460 → SPIR-V)**：
```glsl
#version 460 core
#extension GL_ARB_separate_shader_objects : enable
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec3 inNormal;
layout(set = 0, binding = 0) uniform UBO {
    mat4 uMVPMatrix;
    mat4 uModelMatrix;
} ubo;
layout(location = 0) out vec3 vNormal;
layout(location = 1) out vec3 vWorldPos;
layout(location = 2) out vec2 vTexCoord;
void main() {
  vNormal = normalize(mat3(ubo.uModelMatrix) * inNormal);
  vec4 wp = ubo.uModelMatrix * vec4(inPosition, 1.0);
  vWorldPos = wp.xyz;
  vTexCoord = inTexCoord;
  gl_Position = ubo.uMVPMatrix * vec4(inPosition, 1.0);
}
```

### 地球片段着色器

**Wear OS (GLSL ES 2.0)**：
```glsl
precision mediump float;
varying vec3 vNormal;
varying vec3 vWorldPos;
varying vec2 vTexCoord;
uniform vec3 uSunDir;
uniform sampler2D uTexDay;
uniform sampler2D uTexNight;
void main() {
  float NdotL = dot(normalize(vNormal), normalize(uSunDir));
  float t = smoothstep(-0.18, 0.10, NdotL);
  vec4 dc = texture2D(uTexDay, vTexCoord);
  vec4 nc = texture2D(uTexNight, vTexCoord);
  vec3 dayLit = dc.rgb * (0.85 + t * 0.15);
  vec3 bc = mix(nc.rgb, dayLit, t);
  vec3 viewDir = normalize(vec3(0.0, 0.0, 5.6) - vWorldPos);
  float sp = pow(max(dot(reflect(-normalize(uSunDir), normalize(vNormal)), viewDir), 0.0), 30.0) * 0.65 * t;
  gl_FragColor = vec4(bc + sp, 1.0);
}
```

**HarmonyOS (GLSL 460 → SPIR-V)**：
```glsl
#version 460 core
#extension GL_ARB_separate_shader_objects : enable
layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec3 vWorldPos;
layout(location = 2) in vec2 vTexCoord;
layout(set = 0, binding = 1) uniform sampler2D uTexDay;
layout(set = 0, binding = 2) uniform sampler2D uTexNight;
layout(set = 0, binding = 3) uniform SunData {
    vec3 uSunDir;
} sunData;
layout(location = 0) out vec4 outColor;
void main() {
  float NdotL = dot(normalize(vNormal), normalize(sunData.uSunDir));
  float t = smoothstep(-0.18, 0.10, NdotL);
  vec4 dc = texture(uTexDay, vTexCoord);
  vec4 nc = texture(uTexNight, vTexCoord);
  vec3 dayLit = dc.rgb * (0.85 + t * 0.15);
  vec3 bc = mix(nc.rgb, dayLit, t);
  vec3 viewDir = normalize(vec3(0.0, 0.0, 5.6) - vWorldPos);
  float sp = pow(max(dot(reflect(-normalize(sunData.uSunDir), normalize(vNormal)), viewDir), 0.0), 30.0) * 0.65 * t;
  outColor = vec4(bc + sp, 1.0);
}
```

**编译命令**：
```bash
glslangValidator -V earth.vert -o earth.vert.spv
glslangValidator -V earth.frag -o earth.frag.spv
```

---

## 附录 B：关键 API 对照表

| 功能 | Wear OS (Kotlin) | HarmonyOS (ArkTS) |
|------|-----------------|-------------------|
| 应用入口 | `WatchFaceService` | `UIAbility` |
| GL 渲染 | `GlesRenderer2` | `Component3D` / `XComponent` |
| 纹理加载 | `GLUtils.texImage2D()` | `SceneResourceFactory.createImage()` |
| Shader 编译 | `GLES20.glCompileShader()` | 预编译 SPIR-V + `.shader` JSON |
| 相机 | 手动 `Matrix.setLookAtM()` | `SceneResourceFactory.createCamera()` |
| 灯光 | 手动 uniform | `SceneResourceFactory.createLight()` |
| 矩阵运算 | `android.opengl.Matrix` | 手动实现或 `@ohos.matrix4` |
| 文字渲染 | `Canvas` → `Bitmap` → GL 纹理 | `Text()` 组件直接渲染 |
| 2D 绘图 | GL `drawArrays` + SDF shader | `Canvas.drawLine/arc/circle()` |
| 网络请求 | `HttpURLConnection` | `@ohos.net.http` |
| 位置 | `LocationManager` | `@ohos.geoLocationManager` |
| 电池 | `BatteryManager` | `@ohos.batteryInfo` |
| 通知 | `NotificationManager` | `@ohos.notification` |
| 传感器 | `SensorManager` | `@ohos.sensor` |
| 偏好存储 | `SharedPreferences` | `PersistentStorage` / `@StorageLink` |
| 帧驱动 | 系统 `onRenderFrame` | `AnimatorResult` |
| 屏幕状态 | `WatchState.isAmbient` | `@ohos.display` 监听 |
