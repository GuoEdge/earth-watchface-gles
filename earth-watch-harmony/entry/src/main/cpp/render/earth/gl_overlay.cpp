#include "gl_overlay.h"
#include <cmath>
#include <cstring>

static const char* kTexVS = R"(
attribute vec2 aPos;
attribute vec2 aUV;
uniform mat4 uMVP;
varying vec2 vUV;
void main(){
    vUV=aUV;
    gl_Position=uMVP*vec4(aPos,0.0,1.0);
}
)";

static const char* kTexFS = R"(
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uOffU;
void main(){
    vec2 uv=vec2(vUV.x+uOffU,vUV.y);
    gl_FragColor=texture2D(uTex,uv)*vec4(1.0,1.0,1.0,uAlpha);
}
)";

static const char* kCloudVS = R"(
attribute vec2 aPos;
attribute vec2 aUV;
uniform mat4 uMVP;
varying vec2 vUV;
varying vec2 vPos;
void main(){
    vUV=aUV;
    vPos=aPos;
    gl_Position=uMVP*vec4(aPos,0.0,1.0);
}
)";

static const char* kCloudFS = R"(
precision mediump float;
varying vec2 vUV;
varying vec2 vPos;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uOffU;
uniform vec2 uCenter;
uniform float uRadius;
uniform float uRotY;
uniform vec3 uSunDir;
uniform float uMode;
void main(){
    vec2 off=vPos-uCenter;
    float d=length(off)/uRadius;
    if(d>1.0)discard;
    float edge=smoothstep(1.0,0.92,d);
    float nx=off.x/uRadius;
    float ny=off.y/uRadius;
    float z=sqrt(1.0-d*d);
    float cy=cos(uRotY);
    float sy=sin(uRotY);
    float rx=nx*cy+z*sy;
    float rz=-nx*sy+z*cy;
    float cx2=cos(-0.13962634);
    float sx2=sin(-0.13962634);
    float ry2=ny*cx2-rz*sx2;
    float rz2=ny*sx2+rz*cx2;
    vec2 uv;
    if(uMode>0.5){
        vec3 n=vec3(rx,ry2,rz2);
        float illum=dot(n,uSunDir);
        uv=vec2(illum*0.5+0.5,0.5);
    }else{
        float lon=atan(rx,rz2);
        float lat=asin(ry2);
        uv=vec2(lon/6.28318530+0.5+uOffU,lat/3.14159265+0.5);
    }
    vec4 tc=texture2D(uTex,uv);
    gl_FragColor=tc*vec4(1.0,1.0,1.0,uAlpha*edge);
}
)";

GLuint GlOverlay::compileShader(GLenum type, const char* source) {
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

GLuint GlOverlay::createProgram(const char* vs, const char* fs) {
    GLuint vertShader = compileShader(GL_VERTEX_SHADER, vs);
    GLuint fragShader = compileShader(GL_FRAGMENT_SHADER, fs);
    if (vertShader == 0 || fragShader == 0) {
        if (vertShader != 0) glDeleteShader(vertShader);
        if (fragShader != 0) glDeleteShader(fragShader);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vertShader);
    glAttachShader(program, fragShader);
    glLinkProgram(program);
    GLint status = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &status);
    glDeleteShader(vertShader);
    glDeleteShader(fragShader);
    if (status == 0) {
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void GlOverlay::generateTerminatorTexture() {
    if (terminatorTexId_ != 0) {
        glDeleteTextures(1, &terminatorTexId_);
        terminatorTexId_ = 0;
    }
    const int kTexSize = 256;
    uint8_t data[kTexSize * 4];
    for (int i = 0; i < kTexSize; i++) {
        float t = static_cast<float>(i) / static_cast<float>(kTexSize - 1);
        float dist = (t - 0.5f) * 6.0f;
        float alpha = expf(-dist * dist) * 0.8f;
        data[i * 4 + 0] = 255;
        data[i * 4 + 1] = 180;
        data[i * 4 + 2] = 80;
        data[i * 4 + 3] = static_cast<uint8_t>(alpha * 255.0f);
    }
    glGenTextures(1, &terminatorTexId_);
    glBindTexture(GL_TEXTURE_2D, terminatorTexId_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, kTexSize, 1, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, data);
}

void GlOverlay::init(int width, int height, float innerRadius) {
    width_ = width;
    height_ = height;
    innerRadius_ = innerRadius;
    centerX_ = static_cast<float>(width) * 0.5f;
    centerY_ = static_cast<float>(height) * 0.5f;

    float w = static_cast<float>(width);
    float h = static_cast<float>(height);
    mvp_[0]  = 2.0f / w;  mvp_[4] = 0.0f;      mvp_[8]  = 0.0f; mvp_[12] = -1.0f;
    mvp_[1]  = 0.0f;      mvp_[5] = -2.0f / h;  mvp_[9]  = 0.0f; mvp_[13] = 1.0f;
    mvp_[2]  = 0.0f;      mvp_[6] = 0.0f;       mvp_[10] = -1.0f; mvp_[14] = 0.0f;
    mvp_[3]  = 0.0f;      mvp_[7] = 0.0f;       mvp_[11] = 0.0f;  mvp_[15] = 1.0f;

    if (progTex_ != 0) { glDeleteProgram(progTex_); progTex_ = 0; }
    if (progCloud_ != 0) { glDeleteProgram(progCloud_); progCloud_ = 0; }

    progTex_ = createProgram(kTexVS, kTexFS);
    if (progTex_ != 0) {
        uMvpTex_  = glGetUniformLocation(progTex_, "uMVP");
        uAlphaTex_ = glGetUniformLocation(progTex_, "uAlpha");
        uOffUTex_ = glGetUniformLocation(progTex_, "uOffU");
        uTexTex_  = glGetUniformLocation(progTex_, "uTex");
        aPosTex_  = glGetAttribLocation(progTex_, "aPos");
        aUvTex_   = glGetAttribLocation(progTex_, "aUV");
    }

    progCloud_ = createProgram(kCloudVS, kCloudFS);
    if (progCloud_ != 0) {
        uMvpCloud_   = glGetUniformLocation(progCloud_, "uMVP");
        uAlphaCloud_ = glGetUniformLocation(progCloud_, "uAlpha");
        uOffUCloud_  = glGetUniformLocation(progCloud_, "uOffU");
        uTexCloud_   = glGetUniformLocation(progCloud_, "uTex");
        uCenterCloud_ = glGetUniformLocation(progCloud_, "uCenter");
        uRadiusCloud_ = glGetUniformLocation(progCloud_, "uRadius");
        uRotYCloud_  = glGetUniformLocation(progCloud_, "uRotY");
        uSunDirCloud_ = glGetUniformLocation(progCloud_, "uSunDir");
        uModeCloud_  = glGetUniformLocation(progCloud_, "uMode");
        aPosCloud_   = glGetAttribLocation(progCloud_, "aPos");
        aUvCloud_    = glGetAttribLocation(progCloud_, "aUV");
    }

    static const GLfloat quadUvData[] = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};

    if (vboDyn_ != 0) { glDeleteBuffers(1, &vboDyn_); vboDyn_ = 0; }
    if (vboQuadUv_ != 0) { glDeleteBuffers(1, &vboQuadUv_); vboQuadUv_ = 0; }
    glGenBuffers(1, &vboDyn_);
    glGenBuffers(1, &vboQuadUv_);
    glBindBuffer(GL_ARRAY_BUFFER, vboQuadUv_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quadUvData), quadUvData, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    sunDir_[0] = 0.0f;
    sunDir_[1] = 0.0f;
    sunDir_[2] = 1.0f;
    customSunDirSet_ = false;

    generateTerminatorTexture();
}

void GlOverlay::initClouds(const uint8_t* data, int w, int h) {
    if (cloudTexId_ != 0) {
        glDeleteTextures(1, &cloudTexId_);
        cloudTexId_ = 0;
    }
    cloudW_ = w;
    cloudH_ = h;
    glGenTextures(1, &cloudTexId_);
    glBindTexture(GL_TEXTURE_2D, cloudTexId_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, data);
    cloudInited_ = true;
}

void GlOverlay::uploadAtmosphere(const uint8_t* data, int w, int h) {
    if (atmoTexId_ != 0) {
        glDeleteTextures(1, &atmoTexId_);
        atmoTexId_ = 0;
    }
    glGenTextures(1, &atmoTexId_);
    glBindTexture(GL_TEXTURE_2D, atmoTexId_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, data);
}

void GlOverlay::drawTexturedQuad(GLuint texId, float x, float y, float w,
                                  float h, float alpha, float uOffset) {
    float pos[8] = {
        x,     y + h,
        x + w, y + h,
        x,     y,
        x + w, y
    };

    glUseProgram(progTex_);
    glUniformMatrix4fv(uMvpTex_, 1, GL_FALSE, mvp_);
    glUniform1f(uAlphaTex_, alpha);
    glUniform1f(uOffUTex_, uOffset);
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

void GlOverlay::drawCloudQuad(GLuint texId, float uOffset, float alpha,
                               float rotY, float mode, const float* sunDir) {
    float r = innerRadius_;
    float x = centerX_ - r;
    float y = centerY_ - r;
    float s = r * 2.0f;
    float pos[8] = {
        x,     y + s,
        x + s, y + s,
        x,     y,
        x + s, y
    };

    glUseProgram(progCloud_);
    glUniformMatrix4fv(uMvpCloud_, 1, GL_FALSE, mvp_);
    glUniform1f(uAlphaCloud_, alpha);
    glUniform1f(uOffUCloud_, uOffset);
    glUniform2f(uCenterCloud_, centerX_, centerY_);
    glUniform1f(uRadiusCloud_, innerRadius_);
    glUniform1f(uRotYCloud_, rotY);
    glUniform3f(uSunDirCloud_, sunDir[0], sunDir[1], sunDir[2]);
    glUniform1f(uModeCloud_, mode);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texId);
    glUniform1i(uTexCloud_, 0);

    glBindBuffer(GL_ARRAY_BUFFER, vboDyn_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(pos), pos, GL_DYNAMIC_DRAW);
    glEnableVertexAttribArray(aPosCloud_);
    glVertexAttribPointer(aPosCloud_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glBindBuffer(GL_ARRAY_BUFFER, vboQuadUv_);
    glEnableVertexAttribArray(aUvCloud_);
    glVertexAttribPointer(aUvCloud_, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(aPosCloud_);
    glDisableVertexAttribArray(aUvCloud_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
}

void GlOverlay::renderCloud(float rotY, float cloudOffset) {
    if (!cloudInited_ || cloudTexId_ == 0 || progCloud_ == 0) return;
    float defaultSunDir[3] = {0.0f, 0.0f, 1.0f};
    drawCloudQuad(cloudTexId_, cloudOffset, 0.85f, rotY, 0.0f, defaultSunDir);
}

void GlOverlay::renderTerminator(float rotY, float sunDeclination) {
    if (terminatorTexId_ == 0 || progCloud_ == 0) return;
    float sd[3];
    if (customSunDirSet_) {
        sd[0] = sunDir_[0];
        sd[1] = sunDir_[1];
        sd[2] = sunDir_[2];
    } else {
        sd[0] = 0.0f;
        sd[1] = sinf(sunDeclination);
        sd[2] = cosf(sunDeclination);
    }
    drawCloudQuad(terminatorTexId_, 0.0f, 1.0f, rotY, 1.0f, sd);
}

void GlOverlay::renderAtmosphere() {
    if (atmoTexId_ == 0 || progTex_ == 0) return;
    float atmoRadius = innerRadius_ * 1.15f;
    drawTexturedQuad(atmoTexId_,
                     centerX_ - atmoRadius, centerY_ - atmoRadius,
                     atmoRadius * 2.0f, atmoRadius * 2.0f,
                     0.6f, 0.0f);
}

void GlOverlay::setSunDirection(const float sunDir[3]) {
    sunDir_[0] = sunDir[0];
    sunDir_[1] = sunDir[1];
    sunDir_[2] = sunDir[2];
    customSunDirSet_ = true;
}

void GlOverlay::setCenter(float cx, float cy) {
    centerX_ = cx;
    centerY_ = cy;
}

void GlOverlay::release() {
    if (progTex_ != 0) { glDeleteProgram(progTex_); progTex_ = 0; }
    if (progCloud_ != 0) { glDeleteProgram(progCloud_); progCloud_ = 0; }
    if (cloudTexId_ != 0) { glDeleteTextures(1, &cloudTexId_); cloudTexId_ = 0; }
    if (atmoTexId_ != 0) { glDeleteTextures(1, &atmoTexId_); atmoTexId_ = 0; }
    if (terminatorTexId_ != 0) { glDeleteTextures(1, &terminatorTexId_); terminatorTexId_ = 0; }
    if (vboDyn_ != 0) { glDeleteBuffers(1, &vboDyn_); vboDyn_ = 0; }
    if (vboQuadUv_ != 0) { glDeleteBuffers(1, &vboQuadUv_); vboQuadUv_ = 0; }
    cloudInited_ = false;
    customSunDirSet_ = false;
}
