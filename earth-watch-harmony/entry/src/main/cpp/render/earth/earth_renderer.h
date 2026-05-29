/**
 * earth_renderer.h — 3D 地球球体渲染器
 *
 * 负责创建 UV 球体网格、编译地球着色器、加载日/夜纹理、计算 MVP 矩阵并渲染。
 *
 * 纹理加载流程（重要）：
 *   init() 只创建球体网格和编译着色器，不加载纹理。
 *   loadDayTexture() / loadNightTexture() 在 init() 之后单独调用，
 *   因为纹理数据来自 ArkTS 侧的 rawfile 异步读取。
 *
 * 着色器说明：
 *   使用 GLSL ES 1.0 语法（attribute/varying），因为 EGL 上下文创建为 ES 2.0。
 *   ES 3.0 向后兼容 ES 2.0 着色器，所以虽然链接了 GLESv3，着色器仍用 1.0 语法。
 *
 * isRgb 参数说明：
 *   stb_image 解码始终输出 RGBA（4通道），因此 loadDayTexture 和 loadNightTexture
 *   都应传 isRgb=false，使用 GL_RGBA 格式上传纹理。
 */

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
