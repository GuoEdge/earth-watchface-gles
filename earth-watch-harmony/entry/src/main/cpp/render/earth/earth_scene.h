/**
 * earth_scene.h — 渲染主控
 *
 * 架构角色：协调 EarthRenderer（3D球体）、GlOverlay（云层/晨昏线/大气）、
 * GlPrimitives（2D图元：表圈/刻度/指针/弧线）三个子模块的渲染顺序和状态。
 *
 * 渲染模式：
 *   - Interactive（正常模式）：3D地球 + 云层 + 晨昏线 + 大气辉光 + 2D表盘元素
 *   - Ambient（AOD熄屏模式）：仅表圈 + 刻度 + 时/分指针（华为AOD要求≤20%非黑像素）
 *
 * 数据更新接口：
 *   - updateSunDirection()：太阳方向（影响日/夜面和晨昏线）
 *   - updateConfig()：用户配置（调色板、显示开关、弧线数据源）
 *   - updateData()：传感器数据（电池/天气/通知），保留已有的 sunDir 不被覆盖
 *
 * 纹理加载接口：
 *   - loadDayTexture / loadNightTexture / loadCloudTexture：
 *     在 init() 之后异步调用，因为纹理数据来自 ArkTS 侧的 rawfile 读取。
 *
 * 弧线数据源映射（arcDataSource[] → drawArcs 中的 switch）：
 *   0=电池, 3=温度, 6=UV指数, 8=体感温度, 9=降水概率, 99=隐藏
 */

#pragma once

#include "earth_renderer.h"
#include "gl_primitives.h"
#include "gl_overlay.h"
#include <cstdint>

extern EarthScene* g_scene;

struct EarthSceneConfig {
    int accentIdx = 0;
    bool showLunar = true;
    bool showSensors = true;
    bool showClouds = true;
    int animMode = 0;
    int arcDataSource[4] = {0, 6, 9, 8};
    int fontStyle = 0;
};

struct EarthSceneData {
    float sunDir[3] = {0.0f, 0.0f, 1.0f};
    float batteryPct = 0;
    bool isCharging = false;
    bool batteryIsLow = false;
    float temperature = NAN;
    float uvIndex = -1;
    float feelsLike = NAN;
    float precipProb = -1;
};

class EarthScene {
public:
    EarthScene();
    ~EarthScene();

    void init(int width, int height);
    void renderFrame(int width, int height, int64_t timeMs,
                     int hour, int minute, int second, int nano,
                     int month, int day, int dayOfWeek,
                     const char* lunarText, const char* gzText,
                     bool isAmbient);
    void requestSpin();
    void onDragDelta(float dx);
    void updateConfig(const EarthSceneConfig& cfg);
    void updateSunDirection(const float sunDir[3]);
    void updateData(const EarthSceneData& data);
    void loadDayTexture(const uint8_t* data, int w, int h);
    void loadNightTexture(const uint8_t* data, int w, int h);
    void loadCloudTexture(const uint8_t* data, int w, int h);
    void release();

    bool isInitialized() const { return initialized_; }

private:
    void initGl(int width, int height);
    void renderInteractive(int width, int height, int64_t timeMs,
                           int hour, int minute, int second, int nano,
                           int month, int day, int dayOfWeek,
                           const char* lunarText, const char* gzText);
    void renderAmbientMode(int width, int height,
                           int hour, int minute, int second,
                           int month, int day, int dayOfWeek);
    void resetGlState();
    void orthoMvp(float* out, int w, int h);
    void intToGlColor(int color, float alpha, float* out);
    void drawRim(const float* mvp, float cx, float cy, float ir);
    void drawRimTicks(const float* mvp, float cx, float cy, float ir, float f);
    void drawArcs(const float* mvp, float cx, float cy, float ir, float f);
    void drawHands(const float* mvp, float cx, float cy, float ir, float f,
                   int hour, int minute, int second, int nano);
    void drawHand(const float* mvp, float cx, float cy, float len,
                  float deg, float width, const float* color);
    void ensureAtmoTexture(float ir);

    EarthRenderer earth_;
    GlPrimitives primitives_;
    GlOverlay overlay_;

    EarthSceneConfig config_;
    EarthSceneData data_;

    int bmpSize_ = 0;
    int cachedMvpW_ = 0;
    int cachedMvpH_ = 0;
    float cachedMvp_[16] = {};

    bool wasInteractive_ = false;
    bool isAnimating_ = false;
    int64_t wakeTime_ = 0;
    int64_t slowRotMs_ = 0;
    float dragOffset_ = 0.0f;
    static constexpr int64_t ANIM_MS = 1500;
    static constexpr float CHINA_RY = 380.0f;
    static constexpr float GL_SCALE = 1.00f;

    bool cloudLoaded_ = false;
    bool atmoLoaded_ = false;
    float atmoIr_ = 0;

    struct Palette {
        int time; int hand; int second; int date; int lunar; int gz; int rim;
        int arcs[4];
    };

    static constexpr Palette PALETTES[5] = {
        {0xFFF5F9FF, 0xFFF0F4FF, 0xFFFF6B6B, 0xFFCAD8F0, 0xFFC0D4EE, 0xFFA0B8D8, 0xFF4A90D9,
         {0xFFFF9800, 0xFF00BFA5, 0xFFE85D75, 0xFF5B8DEF}},
        {0xFFE8F4FD, 0xFFD6ECFA, 0xFF64B5F6, 0xFFB3D9F2, 0xFFA8D4F0, 0xFF80B9E0, 0xFF0277BD,
         {0xFF4FC3F7, 0xFF29B6F6, 0xFF03A9F4, 0xFF039BE5}},
        {0xFFFCFAF7, 0xFFF5F0EB, 0xFFBCAAA4, 0xFFF0EBE3, 0xFFEDE5DB, 0xFFDDD3C7, 0xFFA09080,
         {0xFFF5F0EB, 0xFFEDE4D9, 0xFFE0D4C5, 0xFFD4C4B0}},
        {0xFFFFECE8, 0xFFFFD4C0, 0xFFFF5252, 0xFFFFAB91, 0xFFFFAB91, 0xFFFF8A65, 0xFFD50000,
         {0xFFFF7043, 0xFFE53935, 0xFFD50000, 0xFFBF360C}},
        {0xFFE8F0E0, 0xFFD4E0C8, 0xFF81C784, 0xFFB8D0A8, 0xFFA8CCA0, 0xFF8DB580, 0xFF2E7D32,
         {0xFF66BB6A, 0xFF43A047, 0xFF388E3C, 0xFF2E7D32}},
    };

    struct ArcSlot { int slotId; float startAngle; float endAngle; };
    static constexpr ArcSlot ARC_SLOTS[4] = {
        {0, 185, 265}, {1, 275, 355}, {2, 95, 175}, {3, 5, 85}
    };

    bool dayTexLoaded_ = false;
    bool nightTexLoaded_ = false;
    bool initialized_ = false;
};
