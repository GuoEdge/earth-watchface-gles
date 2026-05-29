#include "gl_primitives.h"
#include <cmath>
#include <vector>
#include <algorithm>

static constexpr float kPi = 3.14159265358979323846f;
static constexpr float k2Pi = 6.28318530717958647692f;

static const char* kVsColor =
    "attribute vec4 aPos;uniform mat4 uMVP;void main(){gl_Position=uMVP*aPos;}";

static const char* kFsColor =
    "precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}";

static const char* kVsTex =
    "attribute vec4 aPos;attribute vec2 aUV;uniform mat4 uMVP;varying vec2 vUV;"
    "void main(){vUV=aUV;gl_Position=uMVP*aPos;}";

static const char* kFsTex =
    "precision mediump float;varying vec2 vUV;uniform sampler2D uTex;uniform float uAlpha;"
    "void main(){gl_FragColor=texture2D(uTex,vUV)*vec4(1.0,1.0,1.0,uAlpha);}";

static const char* kVsSdf =
    "attribute vec2 aPos;uniform mat4 uMVP;varying vec2 vPos;"
    "void main(){vPos=aPos;gl_Position=uMVP*vec4(aPos,0.0,1.0);}";

static const char* kFsSdf =
    "#extension GL_OES_standard_derivatives : enable\n"
    "precision mediump float;varying vec2 vPos;uniform vec4 uColor;uniform vec4 uP1;"
    "uniform vec4 uP2;uniform vec4 uP3;\n"
    "float sdRoundLine(vec2 p,vec2 a,vec2 b,float r)"
    "{vec2 pa=p-a,ba=b-a;float h=clamp(dot(pa,ba)/dot(ba,ba),0.0,1.0);"
    "return length(pa-ba*h)-r;}\n"
    "float sdArc(vec2 p,vec2 c,float r,float sa,float sw,float w)"
    "{vec2 q=p-c;float dr=abs(length(q)-r)-w*0.5;float ang=atan(q.y,q.x);"
    "float da=mod(ang-sa+6.28318530,6.28318530);if(da<=sw)return dr;"
    "vec2 c1=c+r*vec2(cos(sa),sin(sa));vec2 c2=c+r*vec2(cos(sa+sw),sin(sa+sw));"
    "return min(length(p-c1),length(p-c2))-w*0.5;}\n"
    "void main(){float d;if(uP3.w<0.5){d=sdRoundLine(vPos,uP1.xy,uP1.zw,uP2.x);}"
    "else{d=sdArc(vPos,uP1.xy,uP1.z,uP2.x,uP2.y,uP1.w);}"
    "float aa=fwidth(d);float a=1.0-smoothstep(-aa*0.5,aa*0.5,d);"
    "gl_FragColor=vec4(uColor.rgb,uColor.a*a);}";

GLuint GlPrimitives::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint status = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status == 0) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint GlPrimitives::linkProgram(const char* vsSource, const char* fsSource) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSource);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSource);
    if (vs == 0 || fs == 0) {
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint status = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (status == 0) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void GlPrimitives::init() {
    release();

    progColor_ = linkProgram(kVsColor, kFsColor);
    if (progColor_ != 0) {
        uMvpColor_ = glGetUniformLocation(progColor_, "uMVP");
        uColorColor_ = glGetUniformLocation(progColor_, "uColor");
        aPosColor_ = glGetAttribLocation(progColor_, "aPos");
    }

    progTex_ = linkProgram(kVsTex, kFsTex);
    if (progTex_ != 0) {
        uMvpTex_ = glGetUniformLocation(progTex_, "uMVP");
        uAlphaTex_ = glGetUniformLocation(progTex_, "uAlpha");
        uTexTex_ = glGetUniformLocation(progTex_, "uTex");
        aPosTex_ = glGetAttribLocation(progTex_, "aPos");
        aUvTex_ = glGetAttribLocation(progTex_, "aUV");
    }

    progSdf_ = linkProgram(kVsSdf, kFsSdf);
    if (progSdf_ != 0) {
        uMvpSdf_ = glGetUniformLocation(progSdf_, "uMVP");
        uColorSdf_ = glGetUniformLocation(progSdf_, "uColor");
        uP1Sdf_ = glGetUniformLocation(progSdf_, "uP1");
        uP2Sdf_ = glGetUniformLocation(progSdf_, "uP2");
        uP3Sdf_ = glGetUniformLocation(progSdf_, "uP3");
        aPosSdf_ = glGetAttribLocation(progSdf_, "aPos");
    }

    glGenBuffers(1, &vboDyn_);
    glGenBuffers(1, &vboQuadUv_);

    static const GLfloat kQuadUv[] = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    glBindBuffer(GL_ARRAY_BUFFER, vboQuadUv_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadUv), kQuadUv, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    initialized_ = true;
}

void GlPrimitives::release() {
    if (progColor_ != 0) { glDeleteProgram(progColor_); progColor_ = 0; }
    if (progTex_ != 0) { glDeleteProgram(progTex_); progTex_ = 0; }
    if (progSdf_ != 0) { glDeleteProgram(progSdf_); progSdf_ = 0; }
    if (vboDyn_ != 0) { glDeleteBuffers(1, &vboDyn_); vboDyn_ = 0; }
    if (vboQuadUv_ != 0) { glDeleteBuffers(1, &vboQuadUv_); vboQuadUv_ = 0; }
    uMvpColor_ = -1; uColorColor_ = -1; aPosColor_ = -1;
    uMvpTex_ = -1; uAlphaTex_ = -1; uTexTex_ = -1; aPosTex_ = -1; aUvTex_ = -1;
    uMvpSdf_ = -1; uColorSdf_ = -1; uP1Sdf_ = -1; uP2Sdf_ = -1; uP3Sdf_ = -1; aPosSdf_ = -1;
    initialized_ = false;
}

void GlPrimitives::drawColorTriangleFan(const GLfloat* mvp, GLfloat cx, GLfloat cy,
                                         GLfloat radius, const GLfloat* color, int segments) {
    if (progColor_ == 0) return;
    const int vertCount = segments + 2;
    std::vector<GLfloat> data(vertCount * 2);
    data[0] = cx;
    data[1] = cy;
    for (int i = 0; i <= segments; ++i) {
        float angle = k2Pi * i / segments;
        data[(i + 1) * 2]     = cx + radius * cosf(angle);
        data[(i + 1) * 2 + 1] = cy + radius * sinf(angle);
    }

    glUseProgram(progColor_);
    glUniformMatrix4fv(uMvpColor_, 1, GL_FALSE, mvp);
    glUniform4fv(uColorColor_, 1, color);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(data.size() * sizeof(GLfloat)),
                 data.data(), GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosColor_);
    glVertexAttribPointer(aPosColor_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_FAN, 0, vertCount);
    glDisableVertexAttribArray(aPosColor_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlPrimitives::drawColorStrip(const GLfloat* mvp, const GLfloat* verts,
                                   GLsizei vertCount, const GLfloat* color) {
    if (progColor_ == 0 || vertCount == 0) return;

    glUseProgram(progColor_);
    glUniformMatrix4fv(uMvpColor_, 1, GL_FALSE, mvp);
    glUniform4fv(uColorColor_, 1, color);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, vertCount * 2 * sizeof(GLfloat), verts, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosColor_);
    glVertexAttribPointer(aPosColor_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, vertCount);
    glDisableVertexAttribArray(aPosColor_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlPrimitives::drawColorTriangles(const GLfloat* mvp, const GLfloat* verts,
                                       GLsizei vertCount, const GLfloat* color) {
    if (progColor_ == 0 || vertCount == 0) return;

    glUseProgram(progColor_);
    glUniformMatrix4fv(uMvpColor_, 1, GL_FALSE, mvp);
    glUniform4fv(uColorColor_, 1, color);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, vertCount * 2 * sizeof(GLfloat), verts, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosColor_);
    glVertexAttribPointer(aPosColor_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLES, 0, vertCount);
    glDisableVertexAttribArray(aPosColor_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlPrimitives::drawTexQuad(const GLfloat* mvp, GLuint texId, GLfloat x, GLfloat y,
                                GLfloat w, GLfloat h, GLfloat alpha) {
    if (progTex_ == 0) return;
    GLfloat pos[] = {x, y + h, x + w, y + h, x, y, x + w, y};

    glUseProgram(progTex_);
    glUniformMatrix4fv(uMvpTex_, 1, GL_FALSE, mvp);
    glUniform1f(uAlphaTex_, alpha);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texId);
    glUniform1i(uTexTex_, 0);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(pos), pos, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosTex_);
    glVertexAttribPointer(aPosTex_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glBindBuffer(GL_ARRAY_BUFFER, vboQuadUv_);
    glEnableVertexAttribArray(aUvTex_);
    glVertexAttribPointer(aUvTex_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPosTex_);
    glDisableVertexAttribArray(aUvTex_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlPrimitives::drawSdfLine(const GLfloat* mvp, GLfloat x1, GLfloat y1, GLfloat x2,
                                GLfloat y2, GLfloat width, const GLfloat* color) {
    if (progSdf_ == 0) return;
    float pad = width * 0.5f + 2.0f;
    float minX = std::min(x1, x2) - pad;
    float minY = std::min(y1, y2) - pad;
    float maxX = std::max(x1, x2) + pad;
    float maxY = std::max(y1, y2) + pad;
    GLfloat pos[] = {minX, maxY, maxX, maxY, minX, minY, maxX, minY};

    glUseProgram(progSdf_);
    glUniformMatrix4fv(uMvpSdf_, 1, GL_FALSE, mvp);
    glUniform4fv(uColorSdf_, 1, color);
    glUniform4f(uP1Sdf_, x1, y1, x2, y2);
    glUniform4f(uP2Sdf_, width * 0.5f, 0.0f, 0.0f, 0.0f);
    glUniform4f(uP3Sdf_, 0.0f, 0.0f, 0.0f, 0.0f);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(pos), pos, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosSdf_);
    glVertexAttribPointer(aPosSdf_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPosSdf_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlPrimitives::drawSdfArc(const GLfloat* mvp, GLfloat cx, GLfloat cy, GLfloat radius,
                               GLfloat startAngle, GLfloat sweepAngle, GLfloat width,
                               const GLfloat* color) {
    if (progSdf_ == 0) return;
    float pad = width * 0.5f + 2.0f;
    float outerR = radius + pad;
    GLfloat pos[] = {cx - outerR, cy + outerR, cx + outerR, cy + outerR,
                     cx - outerR, cy - outerR, cx + outerR, cy - outerR};

    glUseProgram(progSdf_);
    glUniformMatrix4fv(uMvpSdf_, 1, GL_FALSE, mvp);
    glUniform4fv(uColorSdf_, 1, color);
    glUniform4f(uP1Sdf_, cx, cy, radius, width);
    glUniform4f(uP2Sdf_, startAngle, sweepAngle, 0.0f, 0.0f);
    glUniform4f(uP3Sdf_, 0.0f, 0.0f, 0.0f, 1.0f);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(pos), pos, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosSdf_);
    glVertexAttribPointer(aPosSdf_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPosSdf_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}
