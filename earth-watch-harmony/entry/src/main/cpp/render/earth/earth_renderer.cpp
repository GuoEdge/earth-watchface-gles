#include "earth_renderer.h"
#include <cmath>
#include <vector>
#include <hilog/log.h>

static constexpr float PI = 3.14159265358979323846f;

EarthRenderer::EarthRenderer() = default;
EarthRenderer::~EarthRenderer() { release(); }

void EarthRenderer::createSphere(int stacks, int slices) {
    std::vector<GLfloat> verts;
    verts.reserve((stacks + 1) * (slices + 1) * 8);

    for (int i = 0; i <= stacks; i++) {
        float phi = PI * i / stacks;
        float sp = sinf(phi);
        float cp = cosf(phi);
        for (int j = 0; j <= slices; j++) {
            float theta = 2.0f * PI * j / slices;
            float x = sp * sinf(theta);
            float y = cp;
            float z = sp * cosf(theta);
            verts.push_back(x);
            verts.push_back(y);
            verts.push_back(z);
            verts.push_back(x);
            verts.push_back(y);
            verts.push_back(z);
            verts.push_back(theta / (2.0f * PI));
            verts.push_back(1.0f - phi / PI);
        }
    }

    std::vector<GLushort> indices;
    indices.reserve(stacks * slices * 6);
    for (int i = 0; i < stacks; i++) {
        for (int j = 0; j < slices; j++) {
            int a = i * (slices + 1) + j;
            int b = a + slices + 1;
            indices.push_back(a);
            indices.push_back(b);
            indices.push_back(a + 1);
            indices.push_back(a + 1);
            indices.push_back(b);
            indices.push_back(b + 1);
        }
    }
    indexCount_ = static_cast<GLsizei>(indices.size());

    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, verts.size() * sizeof(GLfloat),
                 verts.data(), GL_STATIC_DRAW);

    glGenBuffers(1, &ibo_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo_);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size() * sizeof(GLushort),
                 indices.data(), GL_STATIC_DRAW);

    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
}

static const char* kEarthVS = R"(
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aTexCoord;
uniform mat4 uMVP;
uniform mat4 uModel;
varying vec3 vNormal;
varying vec3 vWorldPos;
varying vec2 vTexCoord;
void main() {
    vNormal = normalize(mat3(uModel) * aNormal);
    vec4 wp = uModel * vec4(aPosition, 1.0);
    vWorldPos = wp.xyz;
    vTexCoord = aTexCoord;
    gl_Position = uMVP * vec4(aPosition, 1.0);
}
)";

static const char* kEarthFS = R"(
precision mediump float;
varying vec3 vNormal;
varying vec3 vWorldPos;
varying vec2 vTexCoord;
uniform vec3 uSunDir;
uniform sampler2D uTexDay;
uniform sampler2D uTexNight;
void main() {
    float NdotL = dot(normalize(vNormal), normalize(uSunDir));
    float t = smoothstep(-0.18, 0.10, NdotL);
    vec4 dc = texture2D(uTexDay, vTexCoord);
    vec4 nc = texture2D(uTexNight, vTexCoord);
    vec3 dayLit = dc.rgb * (0.85 + t * 0.15);
    vec3 bc = mix(nc.rgb, dayLit, t);
    vec3 viewDir = normalize(vec3(0.0, 0.0, 5.6) - vWorldPos);
    float sp = pow(max(dot(reflect(-normalize(uSunDir), normalize(vNormal)), viewDir), 0.0), 30.0) * 0.65 * t;
    gl_FragColor = vec4(bc + sp, 1.0);
}
)";

void EarthRenderer::compileShaders() {
    auto compile = [](GLenum type, const char* src) -> GLuint {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512];
            glGetShaderInfoLog(s, sizeof(log), nullptr, log);
            OH_LOG_Print(LOG_APP, LOG_ERROR, 0x3200, "EarthWatch", "Shader compile error: %{public}s", log);
            glDeleteShader(s);
            return 0;
        }
        return s;
    };

    GLuint vs = compile(GL_VERTEX_SHADER, kEarthVS);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kEarthFS);
    if (!vs || !fs) { glDeleteShader(vs); glDeleteShader(fs); return; }

    progEarth_ = glCreateProgram();
    glAttachShader(progEarth_, vs);
    glAttachShader(progEarth_, fs);
    glBindAttribLocation(progEarth_, 0, "aPosition");
    glBindAttribLocation(progEarth_, 1, "aNormal");
    glBindAttribLocation(progEarth_, 2, "aTexCoord");
    glLinkProgram(progEarth_);

    GLint linkOk = 0;
    glGetProgramiv(progEarth_, GL_LINK_STATUS, &linkOk);
    if (!linkOk) {
        char log[512];
        glGetProgramInfoLog(progEarth_, sizeof(log), nullptr, log);
        OH_LOG_Print(LOG_APP, LOG_ERROR, 0x3200, "EarthWatch", "Program link error: %{public}s", log);
        glDeleteProgram(progEarth_);
        progEarth_ = 0;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);

    if (progEarth_) {
        uMvpEarth_ = glGetUniformLocation(progEarth_, "uMVP");
        uModelEarth_ = glGetUniformLocation(progEarth_, "uModel");
        uSunDirEarth_ = glGetUniformLocation(progEarth_, "uSunDir");
        uTexDayEarth_ = glGetUniformLocation(progEarth_, "uTexDay");
        uTexNightEarth_ = glGetUniformLocation(progEarth_, "uTexNight");
        aPosEarth_ = 0;
        aNormalEarth_ = 1;
        aUvEarth_ = 2;
    }
}

void EarthRenderer::loadTexture(const uint8_t* data, int w, int h, bool isRgb, GLuint& texId) {
    if (texId) glDeleteTextures(1, &texId);
    glGenTextures(1, &texId);
    glBindTexture(GL_TEXTURE_2D, texId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    GLenum fmt = isRgb ? GL_RGB : GL_RGBA;
    glTexImage2D(GL_TEXTURE_2D, 0, fmt, w, h, 0, fmt, GL_UNSIGNED_BYTE, data);
    glGenerateMipmap(GL_TEXTURE_2D);
    glBindTexture(GL_TEXTURE_2D, 0);
}

void EarthRenderer::init(int surfaceSize) {
    if (initialized_ && initSize_ == surfaceSize) return;
    release();
    initSize_ = surfaceSize;

    createSphere(80, 192);
    compileShaders();

    initialized_ = true;
    OH_LOG_Print(LOG_APP, LOG_INFO, 0x3200, "EarthWatch",
                 "EarthRenderer init sz=%{public}d prog=%{public}u", surfaceSize, progEarth_);
}

void EarthRenderer::loadDayTexture(const uint8_t* data, int w, int h) {
    if (!data) return;
    loadTexture(data, w, h, true, dayTex_);
}

void EarthRenderer::loadNightTexture(const uint8_t* data, int w, int h) {
    if (!data) return;
    loadTexture(data, w, h, false, nightTex_);
}

static void mat4Perspective(float* out, float fov, float aspect, float near, float far) {
    float f = 1.0f / tanf(fov / 2.0f);
    float nf = 1.0f / (near - far);
    for (int i = 0; i < 16; i++) out[i] = 0;
    out[0] = f / aspect;
    out[5] = f;
    out[10] = (far + near) * nf;
    out[11] = -1.0f;
    out[14] = 2.0f * far * near * nf;
}

static void mat4LookAt(float* out, float ex, float ey, float ez,
                        float cx, float cy, float cz, float ux, float uy, float uz) {
    float fx = cx - ex, fy = cy - ey, fz = cz - ez;
    float fl = sqrtf(fx*fx + fy*fy + fz*fz);
    fx /= fl; fy /= fl; fz /= fl;
    float sx = fy*uz - fz*uy, sy = fz*ux - fx*uz, sz = fx*uy - fy*ux;
    float sl = sqrtf(sx*sx + sy*sy + sz*sz);
    sx /= sl; sy /= sl; sz /= sl;
    float ux2 = sy*fz - sz*fy, uy2 = sz*fx - sx*fz, uz2 = sx*fy - sy*fx;
    out[0]=sx; out[1]=ux2; out[2]=-fx; out[3]=0;
    out[4]=sy; out[5]=uy2; out[6]=-fy; out[7]=0;
    out[8]=sz; out[9]=uz2; out[10]=-fz; out[11]=0;
    out[12]=-(sx*ex+sy*ey+sz*ez);
    out[13]=-(ux2*ex+uy2*ey+uz2*ez);
    out[14]=-(-fx*ex-fy*ey-fz*ez);
    out[15]=1;
}

static void mat4Mul(float* out, const float* a, const float* b) {
    float t[16];
    for (int i = 0; i < 4; i++)
        for (int j = 0; j < 4; j++)
            t[j*4+i] = a[i]*b[j*4] + a[4+i]*b[j*4+1] + a[8+i]*b[j*4+2] + a[12+i]*b[j*4+3];
    for (int i = 0; i < 16; i++) out[i] = t[i];
}

static void mat4RotateY(float* out, const float* m, float angle) {
    float c = cosf(angle), s = sinf(angle);
    float r[16] = {};
    r[0]=c; r[2]=-s; r[5]=1; r[8]=s; r[10]=c; r[15]=1;
    mat4Mul(out, m, r);
}

static void mat4RotateX(float* out, const float* m, float angle) {
    float c = cosf(angle), s = sinf(angle);
    float r[16] = {};
    r[0]=1; r[5]=c; r[6]=s; r[9]=-s; r[10]=c; r[15]=1;
    mat4Mul(out, m, r);
}

static void mat4Identity(float* out) {
    for (int i = 0; i < 16; i++) out[i] = 0;
    out[0] = out[5] = out[10] = out[15] = 1.0f;
}

void EarthRenderer::render(int surfaceSize, float rotY, const float sunDir[3],
                            int vpX, int vpY) {
    if (!initialized_ || !progEarth_) return;

    glViewport(vpX, vpY, surfaceSize, surfaceSize);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);
    glCullFace(GL_BACK);

    glUseProgram(progEarth_);

    float proj[16], view[16], vp[16], mvp[16];
    mat4Perspective(proj, 0.7854f, 1.0f, 0.1f, 100.0f);
    mat4LookAt(view, 0, 1.2f, 5.6f, 0, 0, 0, 0, 1, 0);
    mat4Mul(vp, proj, view);

    float model[16], modelRotY[16], modelRotX[16];
    mat4Identity(model);
    mat4RotateY(modelRotY, model, rotY * PI / 180.0f);
    mat4RotateX(modelRotX, modelRotY, -0.13962634f);
    mat4Mul(mvp, vp, modelRotX);

    glUniformMatrix4fv(uMvpEarth_, 1, GL_FALSE, mvp);
    glUniformMatrix4fv(uModelEarth_, 1, GL_FALSE, modelRotX);
    glUniform3fv(uSunDirEarth_, 1, sunDir);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, dayTex_);
    glUniform1i(uTexDayEarth_, 0);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, nightTex_);
    glUniform1i(uTexNightEarth_, 1);

    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo_);

    glEnableVertexAttribArray(aPosEarth_);
    glVertexAttribPointer(aPosEarth_, 3, GL_FLOAT, GL_FALSE, 32, nullptr);

    glEnableVertexAttribArray(aNormalEarth_);
    glVertexAttribPointer(aNormalEarth_, 3, GL_FLOAT, GL_FALSE, 32,
                          reinterpret_cast<const void*>(12));

    glEnableVertexAttribArray(aUvEarth_);
    glVertexAttribPointer(aUvEarth_, 2, GL_FLOAT, GL_FALSE, 32,
                          reinterpret_cast<const void*>(24));

    glDrawElements(GL_TRIANGLES, indexCount_, GL_UNSIGNED_SHORT, nullptr);

    glDisableVertexAttribArray(aPosEarth_);
    glDisableVertexAttribArray(aNormalEarth_);
    glDisableVertexAttribArray(aUvEarth_);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    glUseProgram(0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
}

void EarthRenderer::release() {
    if (vbo_) { glDeleteBuffers(1, &vbo_); vbo_ = 0; }
    if (ibo_) { glDeleteBuffers(1, &ibo_); ibo_ = 0; }
    if (progEarth_) { glDeleteProgram(progEarth_); progEarth_ = 0; }
    if (dayTex_) { glDeleteTextures(1, &dayTex_); dayTex_ = 0; }
    if (nightTex_) { glDeleteTextures(1, &nightTex_); nightTex_ = 0; }
    uMvpEarth_ = uModelEarth_ = uSunDirEarth_ = uTexDayEarth_ = uTexNightEarth_ = -1;
    indexCount_ = 0;
    initialized_ = false;
    initSize_ = 0;
}
