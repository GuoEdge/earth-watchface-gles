#pragma once

#include <GLES3/gl3.h>
#include <cstdint>

class EarthRenderer {
public:
    EarthRenderer();
    ~EarthRenderer();

    void init(int surfaceSize);
    void loadDayTexture(const uint8_t* data, int w, int h);
    void loadNightTexture(const uint8_t* data, int w, int h);
    void render(int surfaceSize, float rotY, const float sunDir[3],
                int vpX, int vpY);
    void release();

    GLuint progDebug() const { return progEarth_; }

private:
    void createSphere(int stacks, int slices);
    void compileShaders();
    void loadTexture(const uint8_t* data, int w, int h, bool isRgb, GLuint& texId);

    GLuint progEarth_ = 0;
    GLint uMvpEarth_ = -1;
    GLint uModelEarth_ = -1;
    GLint uSunDirEarth_ = -1;
    GLint uTexDayEarth_ = -1;
    GLint uTexNightEarth_ = -1;
    GLint aPosEarth_ = -1;
    GLint aNormalEarth_ = -1;
    GLint aUvEarth_ = -1;

    GLuint vbo_ = 0;
    GLuint ibo_ = 0;
    GLsizei indexCount_ = 0;

    GLuint dayTex_ = 0;
    GLuint nightTex_ = 0;

    int initSize_ = 0;
    bool initialized_ = false;
};
