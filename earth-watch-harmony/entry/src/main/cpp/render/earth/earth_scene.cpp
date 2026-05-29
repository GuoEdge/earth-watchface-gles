#include "earth_scene.h"
#include <GLES2/gl2.h>
#include <cmath>
#include <cstring>
#include <vector>
#include <hilog/log.h>

static constexpr float PI = 3.14159265358979323846f;
static constexpr float k2Pi = 6.28318530717958647692f;

constexpr EarthScene::Palette EarthScene::PALETTES[5];
constexpr EarthScene::ArcSlot EarthScene::ARC_SLOTS[4];

EarthScene::EarthScene() = default;
EarthScene::~EarthScene() { release(); }

void EarthScene::intToGlColor(int color, float alpha, float* out) {
    out[0] = ((color >> 16) & 0xFF) / 255.0f;
    out[1] = ((color >> 8) & 0xFF) / 255.0f;
    out[2] = (color & 0xFF) / 255.0f;
    out[3] = alpha;
}

void EarthScene::orthoMvp(float* out, int w, int h) {
    if (cachedMvpW_ == w && cachedMvpH_ == h) {
        memcpy(out, cachedMvp_, 64);
        return;
    }
    memset(cachedMvp_, 0, 64);
    cachedMvp_[0] = 2.0f / w;
    cachedMvp_[5] = -2.0f / h;
    cachedMvp_[10] = -1.0f;
    cachedMvp_[12] = -1.0f;
    cachedMvp_[13] = 1.0f;
    cachedMvp_[15] = 1.0f;
    cachedMvpW_ = w;
    cachedMvpH_ = h;
    memcpy(out, cachedMvp_, 64);
}

void EarthScene::resetGlState() {
    glUseProgram(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void EarthScene::initGl(int width, int height) {
    int sz = static_cast<int>(fmin(width, height) * GL_SCALE);
    if (sz < 64) sz = 64;
    if (sz == bmpSize_) return;

    bmpSize_ = sz;
    earth_.init(sz, nullptr, 0, 0, nullptr, 0, 0);
    primitives_.init();
    overlay_.init(width, height, fmin(width, height) / 2.0f - 12.0f);
    cloudLoaded_ = false;
    atmoLoaded_ = false;
    atmoIr_ = 0;
    lastNightAng_ = NAN;
    nightOverlayTex_ = 0;

    OH_LOG_Print(LOG_APP, LOG_INFO, 0x3200, "EarthWatch",
                 "initGl sz=%{public}d w=%{public}d h=%{public}d", sz, width, height);
}

void EarthScene::init(int width, int height) {
    initGl(width, height);
    initialized_ = true;
}

void EarthScene::loadDayTexture(const uint8_t* data, int w, int h) {
    if (!data) return;
    earth_.init(bmpSize_, data, w, h, nullptr, 0, 0);
    dayTexLoaded_ = true;
}

void EarthScene::loadNightTexture(const uint8_t* data, int w, int h) {
    if (!data) return;
    earth_.init(bmpSize_, nullptr, 0, 0, data, w, h);
    nightTexLoaded_ = true;
}

void EarthScene::loadCloudTexture(const uint8_t* data, int w, int h) {
    if (!data || cloudLoaded_) return;
    overlay_.initClouds(data, w, h);
    cloudLoaded_ = true;
}

void EarthScene::updateConfig(const EarthSceneConfig& cfg) {
    config_ = cfg;
}

void EarthScene::updateSunDirection(const float sunDir[3]) {
    data_.sunDir[0] = sunDir[0];
    data_.sunDir[1] = sunDir[1];
    data_.sunDir[2] = sunDir[2];
}

void EarthScene::updateData(const EarthSceneData& d) {
    data_ = d;
}

void EarthScene::requestSpin() {
    if (config_.animMode == 0) {
        isAnimating_ = true;
        wakeTime_ = 0;
    }
}

void EarthScene::ensureAtmoTexture(float ir) {
    if (atmoIr_ == ir && atmoLoaded_) return;
    int d = static_cast<int>(ir * 2.4f);
    if (d < 64) d = 64;
    std::vector<uint8_t> bmp(d * d * 4, 0);
    int cx = d / 2, cy = d / 2, tr = d / 2;

    float stops[] = {0.0f, 0.76f, 0.83f, 0.88f, 0.96f, 1.0f};
    uint8_t alphas[] = {0, 0, 45, 85, 135, 0};

    for (int y = 0; y < d; y++) {
        for (int x = 0; x < d; x++) {
            float dx = x - cx, dy = y - cy;
            float dist = sqrtf(dx*dx + dy*dy) / tr;
            if (dist > 1.0f) continue;

            float alpha = 0;
            for (int s = 0; s < 5; s++) {
                if (dist >= stops[s] && dist < stops[s+1]) {
                    float t = (dist - stops[s]) / (stops[s+1] - stops[s]);
                    alpha = alphas[s] + (alphas[s+1] - alphas[s]) * t;
                    break;
                }
            }
            int idx = (y * d + x) * 4;
            bmp[idx] = 50;
            bmp[idx+1] = 153;
            bmp[idx+2] = 242;
            bmp[idx+3] = static_cast<uint8_t>(alpha);
        }
    }
    overlay_.uploadAtmosphere(bmp.data(), d, d);
    atmoLoaded_ = true;
    atmoIr_ = ir;
}

void EarthScene::drawRim(const float* mvp, float cx, float cy, float ir) {
    const Palette& pal = PALETTES[config_.accentIdx];
    for (int k = 0; k <= 2; k++) {
        float alpha = ((2 - k) * 10 / 2) / 255.0f;
        float color[4];
        intToGlColor(pal.rim, alpha, color);
        float sw = 2.0f + k * 1.5f;
        float outerR = ir + sw * 0.5f;
        float innerR2 = ir - sw * 0.5f;
        int segs = 260;
        std::vector<GLfloat> verts(segs * 4);
        for (int i = 0; i < segs; i++) {
            float a1 = k2Pi * i / segs;
            float a2 = k2Pi * (i + 1) / segs;
            verts[i * 4]     = cx + outerR * cosf(a1);
            verts[i * 4 + 1] = cy + outerR * sinf(a1);
            verts[i * 4 + 2] = cx + innerR2 * cosf(a1);
            verts[i * 4 + 3] = cy + innerR2 * sinf(a1);
        }
        primitives_.drawColorStrip(mvp, verts.data(), segs * 2, color);
    }
}

void EarthScene::drawRimTicks(const float* mvp, float cx, float cy, float ir, float f) {
    const Palette& pal = PALETTES[config_.accentIdx];
    float innerR = ir - 8.0f * f;
    float outerR = ir - 2.0f;
    for (int i = 0; i < 12; i++) {
        float deg = i * 30.0f;
        float rad = (deg - 90.0f) * PI / 180.0f;
        float alpha = (i % 3 == 0) ? 220.0f / 255.0f : 150.0f / 255.0f;
        float color[4];
        intToGlColor(pal.rim, alpha, color);
        float x1 = cx + innerR * cosf(rad), y1 = cy + innerR * sinf(rad);
        float x2 = cx + outerR * cosf(rad), y2 = cy + outerR * sinf(rad);
        float w = (i % 3 == 0) ? 5.0f * f : 3.5f * f;
        float nx = -sinf(rad), ny = cosf(rad);
        GLfloat verts[] = {
            x1 + nx * w * 0.5f, y1 + ny * w * 0.5f,
            x2 + nx * w * 0.5f, y2 + ny * w * 0.5f,
            x1 - nx * w * 0.5f, y1 - ny * w * 0.5f,
            x2 - nx * w * 0.5f, y2 - ny * w * 0.5f,
            x1 - nx * w * 0.5f, y1 - ny * w * 0.5f,
            x2 + nx * w * 0.5f, y2 + ny * w * 0.5f,
        };
        primitives_.drawColorTriangles(mvp, verts, 6, color);
    }
}

void EarthScene::drawArcs(const float* mvp, float cx, float cy, float ir, float f) {
    if (!config_.showSensors) return;
    const Palette& pal = PALETTES[config_.accentIdx];
    float ar = ir * 0.90f;
    float aw = 9.0f * f;

    for (int si = 0; si < 4; si++) {
        int ds = config_.arcDataSource[si];
        if (ds == 99) continue;

        float pct = 0;
        switch (ds) {
            case 0: pct = data_.batteryPct / 100.0f; break;
            case 3: if (!isnan(data_.temperature)) pct = (data_.temperature + 5.0f) / 60.0f; break;
            case 6: if (data_.uvIndex >= 0) pct = data_.uvIndex / 11.0f; break;
            case 8: if (!isnan(data_.feelsLike)) pct = (data_.feelsLike + 5.0f) / 60.0f; break;
            case 9: if (data_.precipProb >= 0) pct = data_.precipProb / 100.0f; break;
            default: break;
        }
        if (pct < 0) pct = 0;
        if (pct > 1) pct = 1;

        float startRad = ARC_SLOTS[si].startAngle * PI / 180.0f;
        float sweepRad = (ARC_SLOTS[si].endAngle - ARC_SLOTS[si].startAngle) * PI / 180.0f;
        int arcColor = pal.arcs[si];

        float trackColor[4];
        intToGlColor(arcColor, 25.0f / 255.0f, trackColor);
        primitives_.drawSdfArc(mvp, cx, cy, ar, startRad, sweepRad, aw, trackColor);

        if (pct > 0) {
            float fillRad = sweepRad * pct;
            float glowColor[4];
            intToGlColor(arcColor, 35.0f / 255.0f, glowColor);
            primitives_.drawSdfArc(mvp, cx, cy, ar, startRad, fillRad, aw * 2.2f, glowColor);
            float fillColor[4];
            intToGlColor(arcColor, 1.0f, fillColor);
            primitives_.drawSdfArc(mvp, cx, cy, ar, startRad, fillRad, aw, fillColor);
        }
    }
}

void EarthScene::drawHand(const float* mvp, float cx, float cy, float len,
                           float deg, float width, const float* color) {
    float a = (deg - 90.0f) * PI / 180.0f;
    float ex = cx + len * cosf(a);
    float ey = cy + len * sinf(a);
    primitives_.drawSdfLine(mvp, cx, cy, ex, ey, width, color);
}

void EarthScene::drawHands(const float* mvp, float cx, float cy, float ir, float f,
                            int hour, int minute, int second, int nano) {
    const Palette& pal = PALETTES[config_.accentIdx];
    float ms = nano / 1e9f;
    float hourDeg = (hour % 12 + minute / 60.0f) * 30.0f;
    float minDeg = (minute + second / 60.0f + ms / 60.0f) * 6.0f;
    float secDeg = (second + ms) * 6.0f;

    float shadowColor[4] = {0, 0, 0, 0.38f};
    float sdx = 1.2f * f, sdy = 2.0f * f;

    float hourWidth = 7.0f * f, hourLen = ir * 0.45f;
    drawHand(mvp, cx + sdx, cy + sdy, hourLen, hourDeg, hourWidth * 1.3f, shadowColor);
    float handColor[4]; intToGlColor(pal.hand, 1.0f, handColor);
    drawHand(mvp, cx, cy, hourLen, hourDeg, hourWidth, handColor);

    float minWidth = 5.0f * f, minLen = ir * 0.68f;
    drawHand(mvp, cx + sdx, cy + sdy, minLen, minDeg, minWidth * 1.3f, shadowColor);
    drawHand(mvp, cx, cy, minLen, minDeg, minWidth, handColor);

    float secWidth = 3.5f * f, secLen = ir * 0.78f;
    drawHand(mvp, cx + sdx, cy + sdy, secLen, secDeg, secWidth * 1.3f, shadowColor);
    float secColor[4]; intToGlColor(pal.second, 1.0f, secColor);
    drawHand(mvp, cx, cy, secLen, secDeg, secWidth, secColor);

    primitives_.drawColorTriangleFan(mvp, cx + sdx, cy + sdy, 5.0f * f * 1.3f, shadowColor, 64);
    float dotColor[4] = {1.0f, 0.27f, 0.0f, 1.0f};
    primitives_.drawColorTriangleFan(mvp, cx, cy, 5.0f * f, dotColor, 64);
}

void EarthScene::renderInteractive(int width, int height, int64_t timeMs,
                                    int hour, int minute, int second, int nano,
                                    int month, int day, int dayOfWeek,
                                    const char* lunarText, const char* gzText) {
    float cx = width / 2.0f, cy = height / 2.0f;
    float r = fmin(width, height) / 2.0f;
    float ir = r - 12.0f;
    float f = r / 200.0f;
    float off = ir * 0.45f;

    if (!wasInteractive_) {
        wakeTime_ = timeMs;
        isAnimating_ = true;
    }
    wasInteractive_ = true;

    float ry = CHINA_RY;
    if (isAnimating_) {
        float elapsed = static_cast<float>(timeMs - wakeTime_);
        if (elapsed < ANIM_MS) {
            float t = elapsed / ANIM_MS;
            float eased = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
            ry = CHINA_RY + 720.0f * eased;
        } else {
            ry = CHINA_RY;
            isAnimating_ = false;
            if (config_.animMode == 1) slowRotMs_ = timeMs;
        }
    } else if (config_.animMode == 1) {
        if (slowRotMs_ == 0) slowRotMs_ = timeMs;
        float dt = (timeMs - slowRotMs_) / 1000.0f;
        ry = CHINA_RY + fmodf(dt * 4.0f, 360.0f);
    }

    float ang = atan2f(data_.sunDir[0], data_.sunDir[2]);

    glViewport(0, 0, width, height);
    glClearColor(0, 0, 0.024f, 1);
    glClear(GL_COLOR_BUFFER_BIT);

    int sz = bmpSize_;
    int earthVpX = (width - sz) / 2;
    int earthVpY = static_cast<int>((height - sz) / 2.0f - off);
    earth_.render(sz, ry, data_.sunDir, earthVpX, earthVpY);

    resetGlState();
    glViewport(0, 0, width, height);
    glDisable(GL_CULL_FACE);
    glDisable(GL_STENCIL_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    float mvp[16];
    orthoMvp(mvp, width, height);

    float rotYRad = ry * PI / 180.0f;
    if (config_.showClouds && cloudLoaded_) {
        float cloudDrift = 0;
        overlay_.renderCloud(rotYRad, cloudDrift);
    }

    overlay_.setSunDirection(data_.sunDir);
    overlay_.renderTerminator(rotYRad, asinf(data_.sunDir[1]));

    ensureAtmoTexture(ir);
    if (atmoLoaded_) {
        overlay_.renderAtmosphere();
    }

    drawRim(mvp, cx, cy, ir);
    drawRimTicks(mvp, cx, cy, ir, f);
    drawArcs(mvp, cx, cy, ir, f);
    drawHands(mvp, cx, cy, ir, f, hour, minute, second, nano);

    glUseProgram(0);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glDisable(GL_BLEND);
}

void EarthScene::renderAmbientMode(int width, int height,
                                    int hour, int minute, int second,
                                    int month, int day, int dayOfWeek) {
    float cx = width / 2.0f, cy = height / 2.0f;
    float r = fmin(width, height) / 2.0f;
    float ir = r - 12.0f;
    float f = r / 200.0f;
    float off = ir * 0.45f;

    glViewport(0, 0, width, height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    if (bmpSize_ > 0) {
        int sz = bmpSize_;
        int earthVpX = (width - sz) / 2;
        int earthVpY = static_cast<int>((height - sz) / 2.0f - off);
        earth_.render(sz, CHINA_RY, data_.sunDir, earthVpX, earthVpY);
        resetGlState();
        glViewport(0, 0, width, height);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        float mvp[16];
        orthoMvp(mvp, width, height);
        float darkCircle[4] = {0, 0, 0, 1.0f - 180.0f / 255.0f};
        primitives_.drawColorTriangleFan(mvp, cx, cy + off, ir, darkCircle, 64);
    }

    float mvp[16];
    orthoMvp(mvp, width, height);

    float rimAlpha = 100.0f / 255.0f;
    float rimColor[4]; intToGlColor(0xFFCCCCCC, rimAlpha, rimColor);
    float outerR2 = ir + 1.0f;
    float innerR3 = ir - 1.0f;
    int rimSegs = 260;
    std::vector<GLfloat> rimVerts(rimSegs * 4);
    for (int i = 0; i < rimSegs; i++) {
        float a1 = k2Pi * i / rimSegs;
        rimVerts[i * 4]     = cx + outerR2 * cosf(a1);
        rimVerts[i * 4 + 1] = cy + outerR2 * sinf(a1);
        rimVerts[i * 4 + 2] = cx + innerR3 * cosf(a1);
        rimVerts[i * 4 + 3] = cy + innerR3 * sinf(a1);
    }
    primitives_.drawColorStrip(mvp, rimVerts.data(), rimSegs * 2, rimColor);

    float innerR = ir - 8.0f * f, outerR = ir - 2.0f;
    for (int i = 0; i < 12; i++) {
        float deg = i * 30.0f;
        float rad = (deg - 90.0f) * PI / 180.0f;
        float alpha = (i % 3 == 0) ? 1.0f : 180.0f / 255.0f;
        float w = (i % 3 == 0) ? 5.0f * f : 3.5f * f;
        float tickColor[4] = {0.878f, 0.878f, 0.878f, alpha};
        float x1 = cx + innerR * cosf(rad), y1 = cy + innerR * sinf(rad);
        float x2 = cx + outerR * cosf(rad), y2 = cy + outerR * sinf(rad);
        float nx = -sinf(rad), ny = cosf(rad);
        GLfloat verts[] = {
            x1 + nx * w * 0.5f, y1 + ny * w * 0.5f,
            x2 + nx * w * 0.5f, y2 + ny * w * 0.5f,
            x1 - nx * w * 0.5f, y1 - ny * w * 0.5f,
            x2 - nx * w * 0.5f, y2 - ny * w * 0.5f,
            x1 - nx * w * 0.5f, y1 - ny * w * 0.5f,
            x2 + nx * w * 0.5f, y2 + ny * w * 0.5f,
        };
        primitives_.drawColorTriangles(mvp, verts, 6, tickColor);
    }

    const Palette& pal = PALETTES[config_.accentIdx];
    float handColor[4]; intToGlColor(pal.hand, 200.0f / 255.0f, handColor);
    drawHand(mvp, cx, cy, ir * 0.45f, (hour % 12 + minute / 60.0f) * 30.0f, 7.0f * f, handColor);
    drawHand(mvp, cx, cy, ir * 0.68f, (minute + second / 60.0f) * 6.0f, 5.0f * f, handColor);

    float dotColor[4] = {1.0f, 0.27f, 0.0f, 220.0f / 255.0f};
    primitives_.drawColorTriangleFan(mvp, cx, cy, 5.0f * f, dotColor, 64);

    wasInteractive_ = false;
}

void EarthScene::renderFrame(int width, int height, int64_t timeMs,
                              int hour, int minute, int second, int nano,
                              int month, int day, int dayOfWeek,
                              const char* lunarText, const char* gzText,
                              bool isAmbient) {
    if (width <= 0 || height <= 0) return;
    initGl(width, height);

    if (isAmbient) {
        renderAmbientMode(width, height, hour, minute, second, month, day, dayOfWeek);
    } else {
        renderInteractive(width, height, timeMs, hour, minute, second, nano,
                          month, day, dayOfWeek, lunarText, gzText);
    }
}

void EarthScene::release() {
    earth_.release();
    primitives_.release();
    overlay_.release();
    if (nightOverlayTex_) {
        glDeleteTextures(1, &nightOverlayTex_);
        nightOverlayTex_ = 0;
    }
    bmpSize_ = 0;
    initialized_ = false;
    cloudLoaded_ = false;
    atmoLoaded_ = false;
    dayTexLoaded_ = false;
    nightTexLoaded_ = false;
}
