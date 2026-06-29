# Earth Watch Face — GLES 版 完整技术文档

## 一、项目概览

Wear OS 3D 地球表盘。核心视觉是一颗 30° 俯视角的 3D 地球，带真实日夜晨昏线、球面云层、大气辉光，叠加指针、刻度、数字时钟、农历等表盘元素。熄屏模式展示 **辰环 (Shichen Ring)**——以传统十二时辰流转替代不可更新的数字时钟/指针。

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
  │                         + drawTexturedQuadRotated() (辰环径向文字)
  ├── GlOverlay            ← progTex(大气) / progCloud(云层球面投影)
  ├── SunCalculator        ← sunDirection() → FloatArray(3)
  ├── LunarCalendar        ← toLunar() / toGanzhi()
  ├── TextTextureCache     ← getOrUpdate(key, paint, text) → texId
  ├── WeatherFetcher       ← fetch() 后台线程
  ├── NotificationCountProvider
  └── drawShichenRing()    ← 熄屏辰环渲染 (双环+12刻点+12径向旋转字)
```

---

## 二、渲染管线（render() 每帧完整执行顺序）

### 阶段0 — GL上下文检测 & 熄屏分流

```
validateGlContext(): 仅检查 glPrimitives.progDebug（2D program）
  → glIsProgram() + GL_LINK_STATUS
→ 校验失败 → 部分重置（bmpSize=0 + 清 overlay/文字缓存）
  → 不调用 earthGles.release()，3D 纹理与 progEarth 保留
→ 熄屏(isAmbient=true) → loadPrefs() → renderAmbient() 直接 return
```

**重要**：上下文校验失败时只做「轻量重置」，**不得**全量 `earthGles.release()`。详见第六节「地球偏暗回归」。

### 阶段1 — 配置读取

```
readStyle()（亮屏）: loadPrefs() + 节流同步 UserStyle → SharedPreferences
loadPrefs()（熄屏也会调用）: accentIdx / showLunar / showSensors / showClouds / animMode / shichenFont / fontStyle / palette
readBattery(): 每120秒读 BatteryManager
weatherFetcher.ensureFresh(): 每帧最多触发一次后台天气刷新
```

`ConfigActivity` 直接写 `SharedPreferences`（绕过 `UserStyle` 序列化）。熄屏路径必须单独调用 `loadPrefs()`，否则辰环开关等配置不同步。

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
- **不渲染**指针、数字时钟、外圈圆环、12 刻度、中心红点——这些时间相关元素熄屏后不会更新
- **新增辰环 (Shichen Ring)**：双线轨道 + 12 时辰刻度点 + 12 径向旋转汉字（6 级梯度流转，当前时辰暖金高亮）

熄屏 GL 状态机（当前）：
```
Viewport(full) → Clear(black) → CULL(OFF) → Viewport(earth)
  → 3D render → resetGlState
  → Viewport(full) → CULL(OFF) → BLEND(ON, SRC_ALPHA)
  → dim circle(cx,cy+off)
  → drawShichenRing()  ← 双环 + 点 + 径向字
```

### 3.8 辰环 (Shichen Ring) — 熄屏时辰显示

**设计理念**：熄屏无法更新时间信息（Samsung DisplayOffload 只显示静态帧），但传统十二时辰每 2 小时才变一次，远优于分钟级过期的时钟。辰环用 12 个汉字环绕地球、按 6 级梯度强调当前时辰位置，既有实用性又有传统书法美感。

**视觉层次**：
```
  子 ← 43px 暖金色 浮凸高光 (当前时辰)
 丑寅 ← 32px 银白 (相邻)
 卯辰 ← 26px → 21px → 18px → 16px → 14px 渐淡
  午 ← 14px 极暗 (对面方位，6小时前/后)
```

**GL 实现**：
| 元素 | 方法 | 数量 |
|------|------|------|
| 双线轨道 | `drawRing()` ×2 | 外环 R=ir\*0.935, 内环 R=外环-15px, 各 2.5px 宽 |
| 刻度点 | `drawCircle()` ×12 | 6 级梯度半径 (4.5→1.0px), 当前加 7px 光晕 |
| 时辰字 | `TextTextureCache` + `drawTexturedQuadRotated()` ×12 | 6 级梯度字号 (43→14px), 径向旋转面向圆心 |

**径向文字旋转**：
- 每个字位于环内侧 `ringInnerR - 20px`
- 由 `GlPrimitives.drawTexturedQuadRotated()` 绕其中心旋转，旋转角 = 字所在方位角 + π/2
- 效果：文字基线垂直于圆边，"子"在正上方向下阅读，"酉"在右侧向左阅读

**时辰计算**：
```kotlin
val shichen = ((zdt.hour + 1) % 24) / 2  // 23-0→子(0), 1-2→丑(1), ...
```

**字体配置**（`shichen_font` SharedPreference 键）：
| 值 | 含义 | Typeface |
|----|------|----------|
| 0 | 系统默认 | `Typeface.DEFAULT_BOLD` |
| 1 | 宋体 | `Typeface.create(Typeface.SERIF, Typeface.NORMAL)` |
| 2 | 关闭 | `drawShichenRing()` 直接 return，熄屏只显示地球 |

配置通过 `EarthConfigActivity` 按钮直接写 `SharedPreferences`（绕过 `UserStyleSchema`，因 Samsung 8 项限制）。

---

## 四、GL 资源生命周期

```
init 流程:     ensureTextures（dayTex/nightTex 常驻，init 后不 recycle）
               → earthGles.init → glPrimitives.init → glOverlay.init → initCloudTexture
每帧流程:     validateGlContext（仅 2D）→ 3D render → resetGlState → 2D overlay + elements
上下文丢失:   部分重置 bmpSize + overlay 缓存；不销毁 earthGles 的 GPU 纹理
onDestroy:    各模块 release() → glDeleteProgram / glDeleteTexture / glDeleteBuffer
```

**纹理常驻**：`earth_day.jpg` / `earth_night.png` 由 `EarthRenderer` 与 `EarthGles` 共同持有至 `onDestroy()`。禁止在 `initGlResources()` 上传 GPU 后立即 `recycle()` CPU 位图。

### 每帧 GL 状态机流转

```
[交互模式]
Viewport(full) → Clear(dark #000006) → Viewport(earth) → CULL(ON)
  → 3D render → resetGlState → glDisable(CULL_FACE)
  → Viewport(full) → STENCIL(OFF) → BLEND(ON, SRC_ALPHA)
  → clouds → drawNightOverlay → atmosphere → 表盘 UI → BLEND(OFF)

[熄屏模式]
Viewport(full) → Clear(black) → Viewport(earth) → 3D render → resetGlState
  → Viewport(full) → BLEND(ON) → dim circle(cx,cy+off) → drawShichenRing()
```

**resetGlState 必须执行**: 3D 地球渲染后 VBO/IBO 仍绑定，纹理单元0和1仍指向地球贴图。如果不解绑，后续 `glVertexAttribPointer` 会将客户端内存指针误读为 VBO 偏移量，导致 UI 图元读取错误顶点数据。

---

## 五、关键设计决策

| 决策 | 原因 |
|------|------|
| `GlPrimitives` 从 `object` 改为 `class` | Wear OS 自定义界面创建预览 Renderer 实例，若为单例则预览调 init() 会删除主实例 GL programs |
| `validateGlContext` 只查 2D program + `GL_LINK_STATUS` | 查 3D program 并在失败时全量 release 曾导致地球全黑；2D 校验足以检测 context 丢失 |
| `dayTex`/`nightTex` 常驻，不在 init 后 recycle | 上传 GPU 后立刻回收会使 `mkTex()` 早退路径删纹理却无法重传 |
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

### 地球偏暗回归（2026-06）—— 给后续 AI / 开发者的必读

**现象**：优化后亮屏地球近乎全黑，大陆轮廓与城市灯光不可见；指针、数字、弧区、云层仍正常。

**根因（非 shader 亮度问题）**：一次「性能/稳定性优化」改坏了 **3D 纹理生命周期**，而非光照公式。典型故障链：

```
validateGlContext 过严（同时查 3D program）
  → handleGlContextLoss() 全量 earthGles.release()，删除 tDay/tNight
  → initGlResources 重建；若 CPU 位图已被 releaseCpuBitmaps() 回收
  → mkTex() 先删旧纹理、再以 null bitmap 上传失败
  → render 绑定 texture 0 → 地球全黑，仅云层/大气微弱可见
```

优化前即使 validate 失败，**旧 3D GPU 纹理仍存活**，表盘「还能看」——这是行为差异的关键。

| 改动 | 安全？ | 说明 |
|------|--------|------|
| `@Volatile` 动画字段、`loadPrefs()` 拆分、日期缓存、`ensureFresh` | 是 | 不改变 GL 纹理 |
| `GlPrimitives` 零分配 buffer | 是 | 注意 `intToGlColor` 勿返回共享数组引用 |
| `releaseCpuBitmaps()` 上传后立刻 recycle | **否** | 使 `mkTex()` 早退时无 bitmap 可传 |
| `validateGlContext` 查 3D + 失败时 `earthGles.release()` | **否** | Mali-G68 上易触发纹理销毁循环 |
| 亮屏去掉 `drawNightOverlay` | 次要 | 移除遮罩应更亮，不是变暗主因 |
| 实验性 shader 提亮 | 次要 | 纹理失效时调 shader 无效 |

**正确修复顺序**（以后遇到「地球变暗」时）：

1. `git diff HEAD` 对比地球相关文件（`EarthRenderer.kt`、`EarthGlesRenderer.kt`）
2. 恢复纹理常驻 + 部分 context 重置 + 亮屏 `drawNightOverlay`
3. 真机验证：`adb logcat -s EarthGles EarthRenderer`，确认 `mkTex tDay=… tNight=…` **均非 0**，且无 `GL context lost` 日志风暴
4. 在**已应用表盘**的亮屏下截图（表盘选择器预览可能是静态帧，不可作依据）
5. 确认恢复后，若夜面仍略暗，再**单独**小步调 shader 或遮罩 alpha

**暂缓重新引入的优化**（需单独真机回归后再评估）：

- `releaseCpuBitmaps()` 内存优化
- 扩展版 `validateGlContext` + 全量 `handleGlContextLoss`
- shader 亮度实验性改动

**诊断特征**：若 UI/云层正常而仅地球黑，优先查 `tDay`/`tNight` 与 bitmap 生命周期，不要先调 shader。

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

10. **修改辰环视觉**: 所有参数集中在 `drawShichenRing()` 方法内：
    - 字号/颜色梯度：`when(dist)` 块中 `fontSize`、`colorR/G/B`、`alpha`
    - 环半径/间距：`ringOuterR`、`ringInnerR`
    - 字距环内侧距离：`textR = ringInnerR - 20f`
    - 旋转角度公式：`a + π/2`（使文字基线垂直于圆边面向圆心）
    - 刻度点尺寸梯度：`ptAlpha`、`ptSize` 的 `when` 块

11. **更改时辰字体**：直接改 `shenP.typeface`，可用值：
    - `Typeface.DEFAULT_BOLD` — 系统无衬线粗体
    - `Typeface.create(Typeface.SERIF, Typeface.NORMAL)` — 系统衬线（宋体）
    - `Typeface.createFromAsset(context.assets, "fonts/YourFont.ttf")` — 自定义字体（需放入 assets/fonts/）

12. **添加新的表盘配置项（绕过 Schema 限制）**：Samsung 限制 8 个 UserStyleSchema 项。超出限制的配置通过以下流程添加：
    (a) `EarthWatchFaceService` 定义 `ListUserStyleSetting`（不加入 `createUserStyleSchema()`）
    (b) `EarthConfigActivity.settings` 列表追加该项（生成 UI 按钮）
    (c) `EarthRenderer.migrateArcDefaults()` 设置默认值
    (d) `EarthRenderer.readStyle()` 用 `prefGet("key")` 读取
    (e) 渲染逻辑中根据读取值分支

13. **旋转表圈 (Galaxy Watch 4 Classic)**：ConfigActivity 需覆写 `onGenericMotionEvent`，将 `AXIS_SCROLL` 事件转发给 `scrollView.scrollBy()`。详见第 10.3 节。

14. **地球变暗排查**：先查 logcat `mkTex tDay/tNight` 是否非 0，再查是否误加了 `releaseCpuBitmaps` 或全量 `earthGles.release()`。见第六节「地球偏暗回归」。

15. **GL 上下文恢复分级**：
    - **轻量**（每帧 validate 失败）：`bmpSize=0` + 清 `textCache`/cloud/atmo/comp 缓存；**保留** `earthGles` 的 GPU 纹理
    - **全量**（仅 `onDestroy`）：各模块 `release()`，删除 program/texture/buffer

16. **性能优化分类**：凡涉及 `init`/`release`/`mkTex`/纹理 upload-recycle 的改动，单独 commit 并真机截图；不可与「零视觉变化」的 prefs/缓存优化混为一谈。

17. **`intToGlColor`**：循环内多次调用时必须每次返回新 `floatArrayOf(...)`，不可复用同一 buffer 引用（会导致 2D 弧/刻度颜色串扰）。

18. **真机验证清单**：安装 APK → 应用表盘（非预览）→ 亮屏截图 → `logcat -s EarthGles EarthRenderer` → 熄屏看辰环。

---

## 九、熄屏（AOD）调研报告 —— 2026-06-02

### Samsung DisplayOffload 机制

Galaxy Watch 4 (One UI Watch) 的熄屏由 Samsung 自研 **DisplayOffload** 协处理器（MCU）控制：

```
亮屏:  AP (Mali-G68) 完整驱动 OpenGL ES 渲染 → 全帧率、全效果
熄屏:  MCU 捕获一帧 → 接管显示屏 → AP 进入 DOZE_SUSPEND 深度休眠
```

MCU 的优势是极致省电，但它**只能重放标准 Canvas 绘制命令**（drawText、drawLine、drawCircle）。OpenGL ES 的 shader、纹理采样、矩阵变换等 GPU 命令 MCU 完全无法理解，只能显示捕获的静态帧。

AP 休眠后 `render()` 只被调用一次（进入熄屏那一刻）。之后再也不会被调用。使用 `adb logcat` 验证：进入熄屏后只有一条 `AMB render` 日志，之后数十分钟内零调用。

### 核心结论：GLES 自定义表盘在三星设备上熄屏无法走时

**系统自带表盘**走时的原因：它们使用 `CanvasRenderer2`，MCU 原生支持重放 Canvas 绘制命令，每分钟可以自行更新指针位置和数字。

**本项目表盘**不走时的原因：使用 `GlesRenderer2` + OpenGL ES 3D 渲染（30K 三角形的 3D 地球、日夜纹理混合 shader、SDF 矢量反锯齿），这些全部发生在 GPU 上，MCU 看不懂。

### 尝试过的方案 & 结果

| 方案 | 结果 | 原因 |
|------|------|------|
| `WatchFaceType.DIGITAL` | ❌ 无效 | 不影响 DisplayOffload 行为 |
| `watch_face.xml` 加 `app:lowBitAmbient="false"` | ❌ 编译失败 (AAPT: attribute not found) | WatchFace API 1.2.x 不支持该属性 |
| `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` 分钟唤醒 | ❌ 导致异常 | 唤醒时 `isAmbient` 短暂变 false，触发 720° 旋转动画 + 屏幕亮起 |
| `AlarmManager` 30 分钟唤醒 + `alarmWake` 动画抑制 | ❌ 表盘重启 | Samsung 系统检测到 RTC_WAKEUP 来自第三方表盘后杀死进程 |
| 切换到 `CanvasRenderer2` | ❌ 不可行 | 须 `glReadPixels` 把 3D 地球从 GPU 读到 CPU，Mali-G68 实测 24-53ms |
| FBO 缓存 + 帧节流 | ❌ 无效 | 减少每帧工作量但无法降低框架调用频率 |
| `GlPrimitives.init()` 增加 `flippedUvBuf` | ❌ 不相关 | 反转的 V 轴坐标和时钟无任何联系 |

### 最终决策：辰环 (Shichen Ring)

在三星设备硬件架构约束下，GLES 表盘熄屏时钟走时**原理上无法实现**。采用十二时辰环替代时间显示：

- **熄屏画面**：3D 地球 + 半透明遮罩 + 辰环（双线轨道 + 12 刻点 + 12 径向旋转汉字，当前时辰暖金高亮）
- **时辰有效期**：2 小时（远优于分钟级过期的时钟/指针）
- **辰环可关闭**：用户可选择"时辰字体 → 关闭"，熄屏仅显示地球

### 系统 UI 覆盖层（飞行模式 / 通知图标）

熄屏时出现的飞行模式图标、通知指示符等来自 **`com.samsung.android.wearable.sysui`** 系统覆盖层，不由表盘代码控制。APK 已申请 Samsung 权限：

```xml
com.samsung.android.watch.watchface.hideinformation.setting.READ_HIDE_INFORMATION
```

用户可通过**手表设置 → 表盘自定义 → "隐藏信息"** 或 Galaxy Wearable App 关闭这些图标。

### 物理按键故障排查

开发过程中发现手表每隔 4-5 秒从熄屏状态唤醒，日志显示 `WAKE_REASON_WAKE_KEY (keyCode=264, KEYCODE_STEM_PRIMARY, repeatCount=465)`。经排查为**物理电源键卡键**——以约 50ms 间隔持续发送按键事件。清洁按键缝隙后恢复正常。

**排查方法**：`adb logcat | grep -E "Screen__On|WAKE_REASON|interceptKeyTi"` 查看唤醒原因和按键码。

---

## 十、新增踩坑记录

### Samsung Wear OS 系统字体限制

`Typeface.create("kaiti", ...)` 在 Wear OS 上找不到楷体字体文件，会静默回退到默认字体。**Wear OS 可用的系统字体非常有限**——`sans-serif`（默认）、`serif`（宋体/明体）、`monospace`。如需特殊字体，必须将 .ttf 放入 `assets/fonts/` 并通过 `Typeface.createFromAsset()` 加载。

### TextTextureCache 与旋转 quad

`TextTextureCache` 的 `drawTexturedQuad` 不直接支持旋转。通过新增 `GlPrimitives.drawTexturedQuadRotated(cx, cy, w, h, angleRad, alpha)` 方法实现——传入 quad 中心坐标和旋转弧度，方法内部计算旋转后的 4 个顶点再 draw。**教训**：做径向文字时不要在 Canvas 里旋转渲染（会产生大量不同角度的纹理缓存），而是在 GL 层面旋转 quad。

### Galaxy Watch 4 Classic 旋转表圈

Galaxy Watch 4 Classic 的物理旋转表圈在标准 `ScrollView` 中**不会自动触发滚动**。需要在 Activity 中覆写 `onGenericMotionEvent`，检测 `MotionEvent.ACTION_SCROLL` + `AXIS_SCROLL` 轴值，手动调用 `scrollView.scrollBy(0, delta)`。

**参考实现**：
```kotlin
override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.action == MotionEvent.ACTION_SCROLL) {
        val v = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (v != 0f) { scrollView.scrollBy(0, (v * 30).toInt()); return true }
    }
    return super.onGenericMotionEvent(event)
}
```

### Samsung UserStyleSchema 8 项硬限制

Samsung One UI Watch 仅支持 **8 个** `UserStyleSetting`。超出限制的设置（`animation_mode`、`show_clouds`、`shichen_font`）通过 `ConfigActivity` 按钮直接写 `SharedPreferences`，不加入 `UserStyleSchema`。**教训**：添加新配置项时先在 `EarthWatchFaceService` 定义 `ListUserStyleSetting`（用于 UI 渲染），加入 `EarthConfigActivity.settings` 列表（生成按钮），但**不加入** `createUserStyleSchema()` 返回的列表。

### 熄屏渲染变量作用域

`renderAmbient()` 中 `val sz = (min(w,h)*GL_SCALE)...` 和 `if(bmpSize>0){ val earthSz=bmpSize; ... }` 存在内部变量遮蔽——这是有意为之，确保熄屏渲染使用正确的纹理尺寸。同理 `off = ir*0.45f` 在函数内多处重声明。**教训**：修改该函数时注意作用域，勿将内层局部变量误当作外层使用。

### 表盘选择器预览 ≠ 实际渲染

Wear OS 表盘列表中的预览可能是静态图或缓存帧，与佩戴时的 GLES 实时渲染不是同一路径。**教训**：验证地球亮度、夜景、辰环等 GL 效果时，必须在手表上**真正应用表盘**后截亮屏/熄屏图，不能只看选择器预览。
