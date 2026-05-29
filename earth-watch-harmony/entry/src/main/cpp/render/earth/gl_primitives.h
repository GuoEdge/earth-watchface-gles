/**
 * gl_primitives.h — 2D 图元渲染库
 *
 * 提供三种着色器程序，用于绘制表盘的 2D 元素：
 *
 *   1. progColor_ — 纯色图元（drawColorTriangleFan / Strip / Triangles）
 *      用于：表圈环、刻度线、中心圆点
 *
 *   2. progTex_ — 纹理图元（drawTexQuad）
 *      用于：大气辉光纹理贴图
 *
 *   3. progSdf_ — SDF 有符号距离场图元（drawSdfLine / drawSdfArc）
 *      用于：时针/分针/秒针（线条）、弧线进度条
 *      SDF 渲染优势：任意宽度/曲率的线条，无锯齿，GPU 友好
 *      注意：SDF shader 使用 GL_OES_standard_derivatives 扩展（ES 2.0 上下文必需）
 *
 * VBO 管理：
 *   - vboDyn_：动态 VBO，每帧上传顶点数据（GL_DYNAMIC_DRAW）
 *   - vboQuadUv_：静态 VBO，预存四边形 UV 坐标（GL_STATIC_DRAW）
 *   所有绘制函数都使用 VBO，不使用客户端顶点指针（ES 3.0 兼容）
 */

#pragma once

#include <GLES3/gl3.h>

class GlPrimitives {
public:
    void init();
    void release();

    void drawColorTriangleFan(const GLfloat* mvp, GLfloat cx, GLfloat cy,
                              GLfloat radius, const GLfloat* color, int segments = 64);
    void drawColorStrip(const GLfloat* mvp, const GLfloat* verts,
                        GLsizei vertCount, const GLfloat* color);
    void drawColorTriangles(const GLfloat* mvp, const GLfloat* verts,
                            GLsizei vertCount, const GLfloat* color);
    void drawTexQuad(const GLfloat* mvp, GLuint texId, GLfloat x, GLfloat y,
                     GLfloat w, GLfloat h, GLfloat alpha);
    void drawSdfLine(const GLfloat* mvp, GLfloat x1, GLfloat y1, GLfloat x2,
                     GLfloat y2, GLfloat width, const GLfloat* color);
    void drawSdfArc(const GLfloat* mvp, GLfloat cx, GLfloat cy, GLfloat radius,
                    GLfloat startAngle, GLfloat sweepAngle, GLfloat width,
                    const GLfloat* color);

private:
    GLuint progColor_ = 0;
    GLint uMvpColor_ = -1;
    GLint uColorColor_ = -1;
    GLint aPosColor_ = -1;

    GLuint progTex_ = 0;
    GLint uMvpTex_ = -1;
    GLint uAlphaTex_ = -1;
    GLint uTexTex_ = -1;
    GLint aPosTex_ = -1;
    GLint aUvTex_ = -1;

    GLuint progSdf_ = 0;
    GLint uMvpSdf_ = -1;
    GLint uColorSdf_ = -1;
    GLint uP1Sdf_ = -1;
    GLint uP2Sdf_ = -1;
    GLint uP3Sdf_ = -1;
    GLint aPosSdf_ = -1;

    GLuint vboDyn_ = 0;
    GLuint vboQuadUv_ = 0;

    bool initialized_ = false;

    static GLuint compileShader(GLenum type, const char* source);
    static GLuint linkProgram(const char* vsSource, const char* fsSource);
};
