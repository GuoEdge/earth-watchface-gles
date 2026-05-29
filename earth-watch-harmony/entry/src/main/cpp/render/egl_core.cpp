#include "egl_core.h"
#include <hilog/log.h>

#undef LOG_DOMAIN
#undef LOG_TAG
#define LOG_DOMAIN 0x3200
#define LOG_TAG "EarthWatch"

namespace earthwatch {

EglCore::EglCore()
    : eglDisplay_(EGL_NO_DISPLAY)
    , eglContext_(EGL_NO_CONTEXT)
    , eglSurface_(EGL_NO_SURFACE)
    , eglConfig_(nullptr)
    , width_(0)
    , height_(0)
    , initialized_(false)
{
}

EglCore::~EglCore()
{
    Release();
}

bool EglCore::Init(EGLNativeDisplayType display)
{
    if (initialized_) {
        OH_LOG_Info(LOG_APP, "EGL already initialized");
        return true;
    }

    eglDisplay_ = eglGetDisplay(display);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        OH_LOG_Error(LOG_APP, "eglGetDisplay failed: %{public}d", eglGetError());
        return false;
    }

    EGLint majorVersion = 0;
    EGLint minorVersion = 0;
    if (!eglInitialize(eglDisplay_, &majorVersion, &minorVersion)) {
        OH_LOG_Error(LOG_APP, "eglInitialize failed: %{public}d", eglGetError());
        eglDisplay_ = EGL_NO_DISPLAY;
        return false;
    }

    OH_LOG_Info(LOG_APP, "EGL version %{public}d.%{public}d", majorVersion, minorVersion);

    const EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, attribs, &eglConfig_, 1, &numConfigs)) {
        OH_LOG_Error(LOG_APP, "eglChooseConfig failed: %{public}d", eglGetError());
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
        return false;
    }

    if (numConfigs < 1) {
        OH_LOG_Error(LOG_APP, "No suitable EGL config found");
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };

    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        OH_LOG_Error(LOG_APP, "eglCreateContext failed: %{public}d", eglGetError());
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
        return false;
    }

    initialized_ = true;
    OH_LOG_Info(LOG_APP, "EGL context created successfully");
    return true;
}

bool EglCore::CreateSurface(EGLNativeWindowType window)
{
    if (!initialized_) {
        OH_LOG_Error(LOG_APP, "EGL not initialized, cannot create surface");
        return false;
    }

    if (eglSurface_ != EGL_NO_SURFACE) {
        eglDestroySurface(eglDisplay_, eglSurface_);
        eglSurface_ = EGL_NO_SURFACE;
    }

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        OH_LOG_Error(LOG_APP, "eglCreateWindowSurface failed: %{public}d", eglGetError());
        return false;
    }

    if (!eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &width_) ||
        !eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &height_)) {
        OH_LOG_Error(LOG_APP, "eglQuerySurface failed: %{public}d", eglGetError());
        eglDestroySurface(eglDisplay_, eglSurface_);
        eglSurface_ = EGL_NO_SURFACE;
        return false;
    }

    OH_LOG_Info(LOG_APP, "Surface created: %{public}d x %{public}d", width_, height_);
    return true;
}

void EglCore::MakeCurrent()
{
    if (!initialized_ || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }
    eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_);
}

void EglCore::SwapBuffers()
{
    if (!initialized_ || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }
    eglSwapBuffers(eglDisplay_, eglSurface_);
}

void EglCore::Release()
{
    if (!initialized_) {
        return;
    }

    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }

        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }

        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }

    width_ = 0;
    height_ = 0;
    initialized_ = false;
    OH_LOG_Info(LOG_APP, "EGL resources released");
}

bool EglCore::IsContextLost() const
{
    if (!initialized_) {
        return true;
    }
    EGLint error = eglGetError();
    return error == EGL_CONTEXT_LOST;
}

int EglCore::GetWidth() const
{
    return width_;
}

int EglCore::GetHeight() const
{
    return height_;
}

EGLSurface EglCore::GetSurface() const
{
    return eglSurface_;
}

EGLDisplay EglCore::GetDisplay() const
{
    return eglDisplay_;
}

EGLContext EglCore::GetContext() const
{
    return eglContext_;
}

}
