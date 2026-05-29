# Earth Watch Face — GLES 版 完整技术文档

## 一、项目概览

Wear OS 3D 地球表盘。核心视觉是一颗 30° 俯视角的 3D 地球，带真实日夜晨昏线、球面云层、大气辉光，叠加指针、刻度、数字时钟、农历等表盘元素。

| 项目 | 说明 |
|------|------|
| 设备 | Samsung Galaxy Watch 4 (SM-R8950, Mali-G68, API 36) |
| 技术栈 | Kotlin / OpenGL ES 2.0 / Gradle |
| 编译SDK | 34 (Android 14) |
| 最低要求 | Wear OS 3.0+ (API 30+) |
| 入口 | `EarthWatchFaceService.kt` |

### 构建步骤

**前提**: JDK 17+, Android SDK 34+

```bash
cd wearos-gles
./gradlew :wear:assembleDebug
```

用 Android Studio 打开 `wearos-gles/` 目录，等待 Gradle 同步完成后连接手表 Run 即可。

### 项目目录结构

```
wearos-gles/
├── build.gradle.kts              # 根构建
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── ARCHITECTURE.md               # ← 本文档
└── wear/
    ├── build.gradle.kts           # minSdk=30, targetSdk=34
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── textures/
        │   │   ├── earth_day.jpg    (NASA Blue Marble 日面卫星图)
        │   │   ├── earth_night.png  (城市灯光夜景图)
        │   │   └── earth_cloud.png  (云层透明贴图)
        │   └── fonts/
        │       ├── DSEG7Modern-Bold.ttf
        │       └── DSEG7Classic-Bold.ttf
        ├── res/
        │   ├── drawable-nodpi/preview_earth.png
        │   ├── values/strings.xml
        │   └── xml/watch_face.xml
        └── java/com/earthwatch/face/
            ├── EarthWatchFaceService.kt   ← 入口：Wear OS 服务注册、配置面板定义
            ├── EarthConfigActivity.kt     ← 用户界面：颜色/功能/字体切换
            ├── EarthRenderer.kt           ← 核心：渲染管线主控 (~950行)
            ├── EarthGlesRenderer.kt       ← 3D 地球球体渲染 (1个 shader program)
            ├── GlPrimitives.kt            ← 2D 图元库 (3个 shader program)
            ├── GlOverlay.kt               ← 叠加层 (云层球面投影+大气+晨昏线, 2个 program)
            ├── SunCalculator.kt           ← 太阳方向 (赤纬+时角, 纯计算)
            ├── LunarCalendar.kt           ← 农历+干支纪日
            ├── TextTextureCache.kt        ← 文字→GL纹理缓存
            ├── WeatherFetcher.kt          ← Open-Meteo 天气 (异步拉取)
            ├── NotificationCountProvider.kt
            ├── EarthCanvasComplication.kt
            └── InstanceRemovedReceiver.kt
```

### 模块依赖拓扑

```
EarthWatchFaceService → WatchFace → EarthRenderer (GlesRenderer2<EarthAssets>)
  ├── EarthGlesRenderer    ← 透视投影 + rotateY(ry) + rotateX(-8°)
  ├── GlPrimitives         ← prog(纯色) / progTex(纹理) / progSdf(矢量反锯齿)
  ├── GlOverlay            ← progTex(大气) / progCloud(云层球面投影)
  ├── SunCalculator        ← sunDirection() → FloatArray(3)
  ├── LunarCalendar        ← toLunar() / toGanzhi()
  ├── TextTextureCache     ← getOrUpdate(key, paint, text) → texId
  ├── WeatherFetcher       ← fetch() 后台线程
  └── NotificationCountProvider
```

---

## 二、渲染管线（render() 每帧完整执行顺序）

### 阶段0 — GL上下文检测 & 熄屏分流

```
validateGlContext(): glIsProgram() + GL_LINK_STATUS 双重验证
→ 上下文丢失 → 重置所有状态 → bmpSize=0 标记需重初始化
→ 熄屏(isAmbient=true) → renderAmbient() 直接return
```

`validateGlContext` 不仅检查 `glIsProgram(progId)`，还验证 `GL_LINK_STATUS`。仅 `glIsProgram` 不可靠——GL 驱动可能复用已失效的 program ID。

### 阶段1 — 配置读取

```
readStyle(): SharedPreferences → accentIdx / showLunar / showSensors / showClouds / animMode / fontStyle / palette
readBattery(): 每120秒读 BatteryManager
```

`ConfigActivity` 直接写 `SharedPreferences`（绕过 `UserStyle` 序列化），`readStyle` 从 `configPrefs` 读取。配置变化由 `ConfigActivity` 按钮事件直接触发。

### 阶段2 — GL资源初始化（仅尺寸变化时执行）

```
sz = min(w, h) × GL_SCALE (1.00f).coerceAtLeast(64)
if sz != bmpSize:
  ensureTextures() → 加载 earth_day.jpg + earth_night.png
  earthGles.init(sz, dayBmp, nightBmp) → mkShaders + mkSphere + mkTex
  glPrimitives.init() → 编译 3 个 shader program
  glOverlay.init()    → 编译 2 个 shader program
  initCloudTexture()  → 加载 earth_cloud.png
```

`GL_SCALE = 1.00f` 是渲染精度系数：>1.0 更清晰更费显存，<1.0 反之。当前与屏幕 1:1。

### 阶段3 — 旋转计算

三种模式（`animMode` 配置切换）：

| 模式 | 行为 |
|------|------|
| 唤醒动画 | 720° × easeOutCubic(1.5秒)，表盘点亮时播放 |
| 慢旋 (mode=1) | 4°/秒 持续旋转 |
| 静态 (mode=0) | `CHINA_RY = 380°`（中国区域对屏） |

所有模式初始方向均为 `CHINA_RY = 380°`。旋转角度 `ry` 同时驱动 3D 地球、云层球面 UV 和晨昏线遮罩。

### 阶段4 — 3D地球渲染（独立viewport）

```
viewport(earthVpX, earthVpY, sz, sz)     ← 地球在屏幕居中+上移(off=ir*0.45)
ENable CULL_FACE
glClear(dark blue #000006)

透视投影: FOV=26°, 视距=5.6, near=0.1, far=100
模型矩阵: rotateY(ry) × rotateX(-8°) × scale(1.1)

sunDir 从模型空间→世界空间 (mat3(mod) 上3×3 变换)

glUseProgram(progEarth)
→ uMVPMatrix / uModelMatrix / uSunDir
→ bind day texture (GL_TEXTURE0) + night texture (GL_TEXTURE1)
→ drawElements(~30K triangles, GL_TRIANGLES)
```

Fragment shader 核心：
```glsl
float NdotL = dot(normalize(vNormal), normalize(uSunDir));
float t = smoothstep(-0.18, 0.10, NdotL);
// t=0 → 纯夜景;  t=1 → 纯日景
vec3 dayLit = dayColor.rgb * (0.85 + t * 0.15);
vec3 bc = mix(nightColor.rgb, dayLit, t);
// 镜面高光（海洋反光）
float sp = pow(max(dot(reflect(-uSunDir, vNormal), viewDir), 0), 30.0) * 0.65 * t;
```

smoothstep(-0.18, 0.10): -0.18 对应法线-太阳夹角约 100°（暗面过渡起点），0.10 对应约 84°（亮面终点）。过渡硬度 0.28 弧度。

### 阶段5 — resetGlState → 2D 正交投影

```
resetGlState(): glUseProgram(0), glBindBuffer(x, 0) × 2, unbind textures × 2
viewport(0, 0, w, h) 全屏
CULL_FACE(OFF), STENCIL(OFF), BLEND(ON, SRC_ALPHA)
mvp = orthoMvp(w, h)  屏幕坐标系: (0,0)左上 → (w,h)右下
```

### 阶段6 — 叠加层

```
drawClouds(rotYRad)            ← progCloud 球面UV投影, 匹配地球旋转
drawNightOverlay(ang, rotYRad)  ← LinearGradient→球面映射晨昏线遮罩
drawAtmosphere(ar)              ← progTex 简单纹理 quad 大气辉光
```

### 阶段7 — 表盘元素

```
drawGlRim(3层极淡圆环) → drawGlRimTicks(12刻度) → drawGlArcs(4段SDF弧形数据条)
→ drawGlHands(SDF指针+偏移阴影+橙红中心点)
→ drawGlTime(3段文字纹理拼合: "时" + ":" + "分")
→ drawGlDate(日期+农历+干支)
→ drawGlSensors(温度/体感/UV/降水, 4 传感器位)
→ drawGlComplications(4个系统Complication槽位)
```

---

## 三、各模块详解

### 3.1 EarthGlesRenderer — 3D地球

**球体几何**: 80 纬度 × 192 经度 = 15,360 顶点, ~30K 三角形。`PHI×THETA` 参数化。

**两个纹理**: `earth_day.jpg` (日面, RGB) + `earth_night.png` (夜景城市灯光, RGBA)，均为 equirectangular 投影。

**太阳方向坐标系变换（关键）**: `SunCalculator` 输出是**地球固连坐标系**下的方向。但 shader 中 `vNormal = mat3(uModelMatrix) * aNormal` 已变换到世界空间。必须在 CPU 端用模型矩阵上 3×3 变换：

```kotlin
slo[0] = mod[0]*sunDir[0] + mod[4]*sunDir[1] + mod[8]*sunDir[2]
slo[1] = mod[1]*sunDir[0] + mod[5]*sunDir[1] + mod[9]*sunDir[2]
slo[2] = mod[2]*sunDir[0] + mod[6]*sunDir[1] + mod[10]*sunDir[2]
```

### 3.2 GlPrimitives — 2D图元

| Program | 用途 | 关键特性 |
|---------|------|---------|
| `prog` | 纯色几何 | `GL_TRIANGLES`/`STRIP`/`FAN`, GPU 光栅化, 无 AA |
| `progTex` | 纹理四边形 | 文字纹理 + Complication 纹理 |
| `progSdf` | SDF 矢量渲染 | `fwidth()` + `smoothstep()` 完美反锯齿 |

**SDF 实现要点**: `uP3.w` 复用为类型标记（<0.5 = 线段, ≥0.5 = 弧形），用一个 shader 统一两种图元。

**降级路径**: 设备不支持 `GL_OES_standard_derivatives` → `progSdf == 0` → `drawSdfArc` 回退到 `drawArcRounded → drawArc + drawCircle(caps)`。保留 `drawArcRounded` 不是死代码。

### 3.3 GlOverlay — 叠加层

**球面投影数学**（`progCloud` fragment shader）:

```
输入: 屏幕像素 (vPos), 圆心 uCenter, 半径 uRadius

1. 归一化: nx = (vPos.x - cx)/r,  ny = (vPos.y - cy)/r
2. 球面 Z:  z = sqrt(1 - (nx² + ny²)),   d > 1.0 → discard
3. 边缘淡入: edge = smoothstep(1.0, 0.92, d)
4. 逆旋转 Y(-rotY):  rx = nx·cos + z·sin,  rz = -nx·sin + z·cos
5. 逆旋转 X(+8°):   ry2 = ny·cos(8°) - rz·sin(8°),  rz2 = ny·sin(8°) + rz·cos(8°)
6. 经纬度: lon = atan2(rx, rz2),  lat = asin(ry2)
7. UV: u = lon/(2π) + 0.5 + uOffU,  v = lat/π + 0.5
8. 采样: texture2D(uTex, uv) × uAlpha × edge
```

**为何 2D 晨昏线也用球面投影**: 原版 Canvas 用直线 LinearGradient 画晨昏线，边缘不弯曲。本实现用 `progCloud` 球面映射，线性渐变纹理在球面边缘自然弯曲成椭圆弧——更接近真实太空中观测到的效果。`uRotY` 传入 `rotYRad` 使遮罩与 3D 地球旋转同步。

### 3.4 SunCalculator

```
输入:  UTC LocalDateTime（无时区信息）
赤纬:  dec = -23.44° × cos(2π/365 × (doy + 10))     ← 季节变化
时角:  ha = -(hour/12) × π                            ← 昼夜变化
输出:  normalize(cos(dec)·cos(ha), sin(dec), cos(dec)·sin(ha))
```

赤纬随 day-of-year 变化: 冬至 -23.44° → 北半球夜长, 夏至 +23.44° → 北半球昼长, 春秋分 0°。晨昏线随季节自然变化，无需手动干预。

### 3.5 TextTextureCache

缓存键 = `"category"` + `paintFingerprint(color, textSize, shadowRadius, shadowDx, shadowDy, shadowColor, typeface)`。

阴影 padding 计算: `max(shadowRadius + |shadowDx|, shadowRadius + |shadowDy|)`，确保 Bitmap 尺寸足够容纳完整阴影纹理。

**Baseline 定位**: `blt = pad - fm.ascent`。`pad` 是纹理中文字到上边缘的 padding，`-fm.ascent` 是 baseline 到文字顶部的距离。quad 顶部坐标 = `baseY - blt`。

### 3.6 WeatherFetcher

Open-Meteo 免费 API（无需 Key），每 30 分钟异步拉取。字段: 当前温度、体感温度、UV 指数、WMO 天气码(→emoji)、未来 3 小时逐时预报、降水概率。定位优先级: GPS → 网络 → 回落上海(31.23, 121.47)。最近一次成功定位缓存到 `SharedPreferences`。

### 3.7 熄屏模式 (renderAmbient)

与交互模式的关键区别：
- 黑底 (`glClearColor(0,0,0,1)`)
- 3D 地球仍渲染（实现日夜纹理混合），`CHINA_RY` 固定不旋转
- 地球之上覆纯黑半透明圆 (alpha ≈ 29%)，轻微压暗保留轮廓
- 遮罩圆心在 `(cx, cy+off)` 而非 `(cx, cy)`，与地球中心对齐
- 数字时钟 + 日期（大字体，无秒针，无传感器文字）
- 外圈圆环 + 12 刻度 + 时/分指针 + 橙红中心点

---

## 四、GL 资源生命周期

```
init 流程:     ensureTextures → gles.init → primitives.init → overlay.init → initCloudTexture
每帧流程:     validateGlContext → 3D render → resetGlState → viewport(full) → 2D elements
release 流程: 各模块 release() → glDeleteProgram / glDeleteTexture / glDeleteBuffer
```

### 每帧 GL 状态机流转

```
[交互模式]
Viewport(full) → Clear(dark #000006) → Viewport(earth) → CULL(ON)
  → 3D render → resetGlState
  → Viewport(full) → CULL(OFF) → STENCIL(OFF) → BLEND(ON, SRC_ALPHA)
  → 2D overlay + elements → BLEND(OFF)

[熄屏模式]
Viewport(full) → Clear(black) → CULL(OFF) → Viewport(earth)
  → 3D render → resetGlState
  → Viewport(full) → CULL(OFF) → BLEND(ON, SRC_ALPHA)
  → dim circle(cx,cy+off) → rim → ticks → hands → dot → time → BLEND(OFF)
```

**resetGlState 必须执行**: 3D 地球渲染后 VBO/IBO 仍绑定，纹理单元0和1仍指向地球贴图。如果不解绑，后续 `glVertexAttribPointer` 会将客户端内存指针误读为 VBO 偏移量，导致 UI 图元读取错误顶点数据。

---

## 五、关键设计决策

| 决策 | 原因 |
|------|------|
| `GlPrimitives` 从 `object` 改为 `class` | Wear OS 自定义界面创建预览 Renderer 实例，若为单例则预览调 init() 会删除主实例 GL programs |
| `validateGlContext` 加 `GL_LINK_STATUS` 验证 | 仅 `glIsProgram` 不可靠，驱动可能复用已失效 ID |
| 太阳方向 CPU 端 mat3(mod) 变换到世界空间 | shader 中 vNormal 已变换，必须同坐标系 |
| 2D 晨昏线用 `progCloud` 球面投影 | 边缘自然弯曲，比原版 Canvas 直线更物理正确 |
| `drawArcRounded` 保留 | SDF 编译失败时的降级回退路径，非死代码 |
| `GL_SCALE = 1.00f` 保留为命名常量 | 类型标记调优意图，非魔法数字 |
| `ConfigActivity` 直接写 `SharedPreferences` | 绕过 `UserStyle` 序列化，逻辑简单直接 |

---

## 六、踩坑记录 & 经验教训

这一节记录开发过程中发现的非直觉性问题，对后续修改至关重要。

### GL 状态泄漏：VBO/IBO 未解绑

`EarthGles.render()` 使用 `glBindBuffer(VBO/IBO)` 后未解绑，后续 `GlPrimitives` 调用 `glVertexAttribPointer` 时，GL 将客户端内存指针当作 VBO 内偏移量解读——UI 图元读到错误顶点数据。**教训**: 切换 GL 上下文或渲染目标后，必须显式解绑所有 buffer 和纹理（`resetGlState()`）。

### 纹理的球面投影与平面投影混用

云层纹理需贴在球面上 → 用 `progCloud` (球面 UV 映射 + `discard` 圆形裁剪)。大气辉光是平面叠加 → 用 `progTex` (简单纹理采样)。**教训**: 纹理采样方式取决于内容语义，不可混用。晨昏线遮罩的渐变纹理虽然内容是 2D 的，但为了在球面边缘产生正确弯曲，也需要走球面投影。

### TextTextureCache 的 baseline 定位

Canvas 文字的 baseline 在 `y = -fm.ascent`（相对于 Bitmap 顶部 padding）。如果用 `baseY - textureHeight + descent` 计算会导致偏移。**教训**: 统一用 `baselineFromTop = shadowPad - fm.ascent` 计算，quad 顶部 y = `baseY - baselineFromTop`。

### glClear 的颜色缓冲区冲突

3D 地球渲染不应清除颜色缓冲区——背景色由 `EarthRenderer` 统一 `glClear` 设置。如果地球也清色，会覆盖背景。**教训**: 每个渲染阶段只清除自己需要的缓冲区（地球只需要深度）。

### glReadPixels 性能灾难（架构教训）

旧架构用 `glReadPixels`（24-53ms）从 GPU 回读像素到 CPU → Canvas 叠加，在 Mali-G68 上是严重瓶颈（慢转仅 25-30fps）。现架构改用纯 GL 渲染 + `GlesRenderer2` 框架管理 EGL，省去了所有 GPU→CPU 传输。**教训**: 移动 GPU 的 glReadPixels 是性能杀手，应避免在渲染热路径中使用。

### NotificationCountProvider 的 Binder IPC 阻塞

`NotificationManager.getActiveNotifications()` 是跨进程 Binder 调用，在渲染线程每帧执行会导致丢帧。**教训**: 系统 API 调用需加节流（当前 5 秒），且应在渲染线程快速返回缓存值，实际查询在后台线程执行。

### 模拟器不可靠

Wear OS 5 模拟器使用 Swiftshader 软件渲染，行为与 Mali-G68 硬件差异大（SDF fwidth 精度、GL 状态管理等）。**教训**: 所有 GL 相关验证必须在真机上做，模拟器仅用于 UI 布局测试。

### GL_CULL_FACE 状态泄漏

`EarthGles.render()` 内部 `glEnable(GL_CULL_FACE)` 用于 3D 球体背面剔除，但返回后未关闭。`resetGlState()` 只重置 program/VBO/纹理，不管 CULL_FACE。熄屏模式 `renderAmbient()` 在 `earthGles.render()` 之前调了 `glDisable(GL_CULL_FACE)`，但被后者覆盖。2D 图元（`drawCircle`/`drawRing`/`drawLine`）在正交投影 Y 轴向下的坐标系中绕序翻转，被 CULL_FACE 当作背面剔除——刻度、中心点、边框环全部消失。**教训**: `resetGlState()` 只管自己清理的范围，任何在子模块中启用的 GL 状态（CULL_FACE、DEPTH_TEST、STENCIL_TEST 等），调用方必须在子模块返回后显式关闭。交互模式在 `resetGlState()` 之后紧跟 `glDisable(GL_CULL_FACE)` 是正确的，熄屏模式也必须在 `earthGles.render()` 之后补上。

### 遮罩圆位置必须与地球中心对齐

地球在屏幕上并非居中，而是上移了 `off = ir * 0.45f`（3D viewport 的 `earthVpY = (h-sz)/2 - off`）。交互模式的云层和晨昏线都正确画在 `(cx, cy+off)`，但熄屏模式的遮罩圆最初画在 `(cx, cy)`，偏移了 `off` 像素——遮罩只盖住地球下半部分，上半部分裸露。**教训**: 正交投影坐标系中，所有叠加在地球上的 2D 元素（云层、晨昏线、遮罩）都必须使用 `(cx, cy+off)` 作为中心，与地球 viewport 位置一致。

### 遮罩 alpha 与地球夜间亮度的叠加效应

地球夜景面本身就很暗（城市灯光纹理），再叠加半透明黑遮罩时，人眼感知的暗化程度远超 alpha 值的数学预期。alpha=0.86 时地球几乎不可见，alpha=0.29 才是合适的轻微压暗。**教训**: 熄屏遮罩的 alpha 不能按直觉设定，必须考虑底层内容的实际亮度——暗底+半透明遮罩的叠加效果是非线性的，需要真机实测调整。

---

## 七、性能指标

| 指标 | 数值 | 备注 |
|------|------|------|
| GPU 每帧耗时 | 4-8ms | 50/90 百分位，Mali-G68 实测 |
| 3D 三角形 | ~30K | 80×192 球体 |
| 纹理内存 (GPU) | ~60MB | day(24) + night(32) + cloud(8) + 文字缓存(~2) |
| PSS 内存 (CPU) | ~85MB | 含纹理映射，正常水平 |
| 熄屏帧率 | ~1fps | frameDelay=20ms |
| GC 压力 | 极低 | FloatBuffer 预分配 + 纹理缓存复用 |

---

## 八、修改指南

1. **太阳方向**: `SunCalculator` 输出是模型空间，在 `EarthGles.render()` 中 `mat3(mod)` 变换后再传入 shader。2D 晨昏线遮罩用的 `atan2(sunDir[0], sunDir[2])` 是地球固连系方向，**不需要**再变换。

2. **GL 状态机隔离**: 3D 和 2D 之间**必须**调用 `resetGlState()`，否则 VBO/IBO 和纹理绑定残留会导致绘制错误。

3. **文字阴影**: 通过 `Paint.setShadowLayer()` 在 `TextTextureCache` 中烘焙到 Bitmap，绘制时直接贴纹理 quad，无需额外 GL 操作。

4. **新增表盘元素**: 在 `drawGlRimTicks` 之后、`drawGlComplications` 之前插入渲染调用，确保 BLEND 已开启。使用 `glPrimitives` 的公开方法。

5. **修改色彩方案**: 在 `Palette` 数据类（EarthRenderer 开头）和对应 Paint 初始化中同步修改。`lunarP` 与 `gzP` 是独立 Paint 实例（便于分别控制颜色和大小）。

6. **调渲染精度/显存**: 改 `GL_SCALE` 常量。>1.0 提高精度但更费显存，<1.0 反之。当前值 1.0 为最优平衡点。

7. **Complication 纹理更新**: `texSubImage2D` 仅在数据变但尺寸未变时复用；尺寸变化时重建纹理。

8. **添加新配置项**: 需在三个位置同步：(a) `EarthWatchFaceService` 定义 `ListUserStyleSetting`, (b) `EarthConfigActivity` 的 `settings` 列表, (c) `EarthRenderer.readStyle()` / `computeFingerprint()` / `syncStyleToPrefs()`。

9. **模拟器 vs 真机**: 所有 GL 效果（SDF 反锯齿、texture mipmap、球面投影精度）必须在真机 (Mali-G68) 上验证。模拟器 Swiftshader 行为不可信。
