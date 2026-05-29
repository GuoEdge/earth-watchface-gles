/**
 * gl_overlay.h — 云层/晨昏线/大气辉光叠加层
 *
 * 在 3D 地球球体之上叠加三种视觉效果：
 *
 *   1. 云层（renderCloud）— 使用 progCloud_ 着色器
 *      将云层纹理球面投影到屏幕空间，根据 rotY 旋转计算经纬度 UV。
 *      uMode=0 时为云层模式（经纬度映射），uMode=1 时为晨昏线模式（光照映射）。
 *
 *   2. 晨昏线（renderTerminator）— 使用 progCloud_ 着色器（uMode=1）
 *      使用预生成的高斯渐变纹理，根据太阳方向在球面上绘制晨昏过渡带。
 *      太阳方向来自 setSunDirection() 或根据 sunDeclination 自动计算。
 *
 *   3. 大气辉光（renderAtmosphere）— 使用 progTex_ 着色器
 *      使用 ensureAtmoTexture() 在 CPU 侧生成的径向渐变纹理，
 *      以半透明蓝色圆环形式叠加在地球边缘。
 *
 * 坐标系统：
 *   - centerX_/centerY_ 是地球在屏幕上的中心（可能与屏幕中心不同，因为地球向上偏移）
 *   - innerRadius_ 是地球可视区域的半径
 *   - setCenter() 在每帧渲染前由 EarthScene 调用，确保叠加层与地球对齐
 *
 * VBO 管理：
 *   - vboDyn_：动态位置数据
 *   - vboQuadUv_：静态 UV 坐标
 */

#pragma once

#include <GLES3/gl3.h>
#include <cstdint>

class GlOverlay {
public:
    void init(int width, int height, float innerRadius);
    void renderCloud(float rotY, float cloudOffset);
    void renderTerminator(float rotY, float sunDeclination);
    void renderAtmosphere();
    void setSunDirection(const float sunDir[3]);
    void setCenter(float cx, float cy);
    void release();

    void initClouds(const uint8_t* data, int w, int h);
    void uploadAtmosphere(const uint8_t* data, int w, int h);

private:
    GLuint compileShader(GLenum type, const char* source);
    GLuint createProgram(const char* vs, const char* fs);
    void drawTexturedQuad(GLuint texId, float x, float y, float w, float h,
                          float alpha, float uOffset);
    void drawCloudQuad(GLuint texId, float uOffset, float alpha,
                       float rotY, float mode, const float* sunDir);
    void generateTerminatorTexture();

    int width_ = 0;
    int height_ = 0;
    float innerRadius_ = 0.0f;
    float centerX_ = 0.0f;
    float centerY_ = 0.0f;
    float mvp_[16] = {};
    float sunDir_[3] = {0.0f, 0.0f, 1.0f};
    bool customSunDirSet_ = false;

    GLuint cloudTexId_ = 0;
    int cloudW_ = 0;
    int cloudH_ = 0;
    bool cloudInited_ = false;
    GLuint atmoTexId_ = 0;
    GLuint terminatorTexId_ = 0;

    GLuint vboDyn_ = 0;
    GLuint vboQuadUv_ = 0;

    GLuint progTex_ = 0;
    GLint uMvpTex_ = -1;
    GLint uAlphaTex_ = -1;
    GLint uOffUTex_ = -1;
    GLint uTexTex_ = -1;
    GLint aPosTex_ = -1;
    GLint aUvTex_ = -1;

    GLuint progCloud_ = 0;
    GLint uMvpCloud_ = -1;
    GLint uAlphaCloud_ = -1;
    GLint uOffUCloud_ = -1;
    GLint uTexCloud_ = -1;
    GLint uCenterCloud_ = -1;
    GLint uRadiusCloud_ = -1;
    GLint uRotYCloud_ = -1;
    GLint uSunDirCloud_ = -1;
    GLint uModeCloud_ = -1;
    GLint aPosCloud_ = -1;
    GLint aUvCloud_ = -1;
};
