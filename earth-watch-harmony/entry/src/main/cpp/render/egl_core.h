#ifndef EARTH_WATCH_EGL_CORE_H
#define EARTH_WATCH_EGL_CORE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

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
