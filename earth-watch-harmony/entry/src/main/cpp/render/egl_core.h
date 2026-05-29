/**
 * egl_core.h — EGL 上下文管理
 *
 * 封装 EGL Display/Context/Surface 的完整生命周期。
 *
 * 关键设计：
 *   - EGL_CONTEXT_CLIENT_VERSION 设为 2（创建 ES 2.0 上下文）。
 *     虽然链接了 GLESv3，但 ES 3.0 上下文需要更严格的驱动支持，
 *     ES 2.0 上下文可以访问大部分 ES 3.0 功能（如 glGenerateMipmap），
 *     且兼容性更好。着色器使用 GLSL ES 1.0 语法。
 *   - IsContextLost() 简化为只检查 initialized_ 标志，
 *     不调用 eglGetError()（避免清除错误状态的副作用）。
 */

#ifndef EARTH_WATCH_EGL_CORE_H
#define EARTH_WATCH_EGL_CORE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

namespace earthwatch {

class EglCore {
public:
    EglCore();
    ~EglCore();

    bool Init(EGLNativeDisplayType display);
    bool CreateSurface(EGLNativeWindowType window);
    void MakeCurrent();
    void SwapBuffers();
    void Release();
    bool IsContextLost() const;
    int GetWidth() const;
    int GetHeight() const;
    EGLSurface GetSurface() const;
    EGLDisplay GetDisplay() const;
    EGLContext GetContext() const;

private:
    EGLDisplay eglDisplay_;
    EGLContext eglContext_;
    EGLSurface eglSurface_;
    EGLConfig eglConfig_;
    int width_;
    int height_;
    bool initialized_;
};

}

#endif
