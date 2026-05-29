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

    float quadUv_[8] = {};

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
