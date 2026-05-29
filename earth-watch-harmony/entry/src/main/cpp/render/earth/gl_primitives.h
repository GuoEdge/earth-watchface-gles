#pragma once

#include <GLES2/gl2.h>

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
