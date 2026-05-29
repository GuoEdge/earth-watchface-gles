#include "plugin_render.h"
#include "earth/earth_scene.h"
#include <hilog/log.h>
#include <native_window/external_window.h>
#include <cstring>

extern EarthScene* g_scene;

#undef LOG_DOMAIN
#undef LOG_TAG
#define LOG_DOMAIN 0x3200
#define LOG_TAG "EarthWatch"

namespace earthwatch {

std::unordered_map<std::string, PluginRender*> PluginRender::instances_;
std::mutex PluginRender::instancesMutex_;

PluginRender::PluginRender()
    : eglCore_()
    , surfaceReady_(false)
    , xComponent_(nullptr)
    , xComponentId_()
    , surfaceWidth_(0)
    , surfaceHeight_(0)
{
}

PluginRender::~PluginRender()
{
}

void PluginRender::RegisterCallback(OH_NativeXComponent* nativeXComponent)
{
    if (nativeXComponent == nullptr) {
        OH_LOG_Error(LOG_APP, "RegisterCallback: nativeXComponent is null");
        return;
    }

    char idBuf[256] = {0};
    int64_t idSize = sizeof(idBuf);
    int32_t ret = OH_NativeXComponent_GetXComponentId(nativeXComponent, idBuf, &idSize);
    if (ret != 0) {
        OH_LOG_Error(LOG_APP, "GetXComponentId failed: %{public}d", ret);
        return;
    }

    std::string id(idBuf, static_cast<size_t>(idSize));
    OH_LOG_Info(LOG_APP, "RegisterCallback for XComponent: %{public}s", id.c_str());

    PluginRender* instance = nullptr;
    {
        std::lock_guard<std::mutex> lock(instancesMutex_);
        auto it = instances_.find(id);
        if (it != instances_.end()) {
            instance = it->second;
        } else {
            instance = new PluginRender();
            instance->xComponentId_ = id;
            instances_[id] = instance;
        }
    }

    instance->xComponent_ = nativeXComponent;

    static OH_NativeXComponent_Callback callback;
    callback.OnSurfaceCreated = OnSurfaceCreatedCallback;
    callback.OnSurfaceChanged = OnSurfaceChangedCallback;
    callback.OnSurfaceDestroyed = OnSurfaceDestroyedCallback;
    callback.DispatchTouchEvent = DispatchTouchEventCallback;

    ret = OH_NativeXComponent_RegisterCallback(nativeXComponent, &callback);
    if (ret != 0) {
        OH_LOG_Error(LOG_APP, "RegisterCallback failed: %{public}d", ret);
    } else {
        OH_LOG_Info(LOG_APP, "XComponent callback registered successfully");
    }
}

PluginRender* PluginRender::GetInstanceById(const std::string& id)
{
    std::lock_guard<std::mutex> lock(instancesMutex_);
    if (id.empty() && !instances_.empty()) {
        return instances_.begin()->second;
    }
    auto it = instances_.find(id);
    if (it != instances_.end()) {
        return it->second;
    }
    return nullptr;
}

void PluginRender::MakeCurrentForRender()
{
    std::lock_guard<std::mutex> lock(instancesMutex_);
    if (!instances_.empty()) {
        auto it = instances_.begin();
        it->second->eglCore_.MakeCurrent();
    }
}

void PluginRender::SwapBuffersAfterRender()
{
    std::lock_guard<std::mutex> lock(instancesMutex_);
    if (!instances_.empty()) {
        auto it = instances_.begin();
        it->second->eglCore_.SwapBuffers();
    }
}

void PluginRender::AddInstance(const std::string& id, PluginRender* instance)
{
    std::lock_guard<std::mutex> lock(instancesMutex_);
    instances_[id] = instance;
}

void PluginRender::RemoveInstance(const std::string& id)
{
    std::lock_guard<std::mutex> lock(instancesMutex_);
    auto it = instances_.find(id);
    if (it != instances_.end()) {
        delete it->second;
        instances_.erase(it);
    }
}

void PluginRender::OnSurfaceCreatedCallback(OH_NativeXComponent* component, void* window)
{
    if (component == nullptr) {
        return;
    }

    char idBuf[256] = {0};
    int64_t idSize = sizeof(idBuf);
    OH_NativeXComponent_GetXComponentId(component, idBuf, &idSize);
    std::string id(idBuf, static_cast<size_t>(idSize));

    PluginRender* instance = GetInstanceById(id);
    if (instance != nullptr) {
        instance->OnSurfaceCreated(component, window);
    }
}

void PluginRender::OnSurfaceChangedCallback(OH_NativeXComponent* component, void* window)
{
    if (component == nullptr) {
        return;
    }

    char idBuf[256] = {0};
    int64_t idSize = sizeof(idBuf);
    OH_NativeXComponent_GetXComponentId(component, idBuf, &idSize);
    std::string id(idBuf, static_cast<size_t>(idSize));

    PluginRender* instance = GetInstanceById(id);
    if (instance != nullptr) {
        instance->OnSurfaceChanged(component, window);
    }
}

void PluginRender::OnSurfaceDestroyedCallback(OH_NativeXComponent* component, void* window)
{
    if (component == nullptr) {
        return;
    }

    char idBuf[256] = {0};
    int64_t idSize = sizeof(idBuf);
    OH_NativeXComponent_GetXComponentId(component, idBuf, &idSize);
    std::string id(idBuf, static_cast<size_t>(idSize));

    PluginRender* instance = GetInstanceById(id);
    if (instance != nullptr) {
        instance->OnSurfaceDestroyed(component, window);
    }
}

void PluginRender::DispatchTouchEventCallback(OH_NativeXComponent* component, void* window)
{
    if (component == nullptr) {
        return;
    }

    char idBuf[256] = {0};
    int64_t idSize = sizeof(idBuf);
    OH_NativeXComponent_GetXComponentId(component, idBuf, &idSize);
    std::string id(idBuf, static_cast<size_t>(idSize));

    PluginRender* instance = GetInstanceById(id);
    if (instance != nullptr) {
        instance->DispatchTouchEvent(component, window);
    }
}

void PluginRender::OnSurfaceCreated(OH_NativeXComponent* component, void* window)
{
    OH_LOG_Info(LOG_APP, "OnSurfaceCreated");

    if (window == nullptr) {
        OH_LOG_Error(LOG_APP, "OnSurfaceCreated: window is null");
        return;
    }

    OH_NativeWindow* nativeWindow = static_cast<OH_NativeWindow*>(window);
    EGLNativeWindowType eglWindow = static_cast<EGLNativeWindowType>(nativeWindow);

    if (!eglCore_.Init(EGL_DEFAULT_DISPLAY)) {
        OH_LOG_Error(LOG_APP, "EGL init failed");
        return;
    }

    if (!eglCore_.CreateSurface(eglWindow)) {
        OH_LOG_Error(LOG_APP, "Create EGL surface failed");
        eglCore_.Release();
        return;
    }

    eglCore_.MakeCurrent();

    surfaceWidth_ = eglCore_.GetWidth();
    surfaceHeight_ = eglCore_.GetHeight();

    glViewport(0, 0, surfaceWidth_, surfaceHeight_);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

    surfaceReady_.store(true);

    OH_LOG_Info(LOG_APP, "OnSurfaceCreated completed: %{public}d x %{public}d", surfaceWidth_, surfaceHeight_);
}

void PluginRender::OnSurfaceChanged(OH_NativeXComponent* component, void* window)
{
    OH_LOG_Info(LOG_APP, "OnSurfaceChanged");

    if (window == nullptr || !surfaceReady_.load()) {
        return;
    }

    OH_NativeWindow* nativeWindow = static_cast<OH_NativeWindow*>(window);
    int32_t width = 0;
    int32_t height = 0;
    int32_t ret = OH_NativeWindow_GetWidth(nativeWindow, &width);
    if (ret != 0) {
        OH_LOG_Error(LOG_APP, "GetNativeWindowWidth failed: %{public}d", ret);
        return;
    }
    ret = OH_NativeWindow_GetHeight(nativeWindow, &height);
    if (ret != 0) {
        OH_LOG_Error(LOG_APP, "GetNativeWindowHeight failed: %{public}d", ret);
        return;
    }

    if (width != surfaceWidth_ || height != surfaceHeight_) {
        surfaceWidth_ = width;
        surfaceHeight_ = height;

        eglCore_.MakeCurrent();
        glViewport(0, 0, surfaceWidth_, surfaceHeight_);

        OH_LOG_Info(LOG_APP, "Surface changed: %{public}d x %{public}d", surfaceWidth_, surfaceHeight_);
    }
}

void PluginRender::OnSurfaceDestroyed(OH_NativeXComponent* component, void* window)
{
    OH_LOG_Info(LOG_APP, "OnSurfaceDestroyed");

    surfaceReady_.store(false);

    eglCore_.MakeCurrent();
    eglCore_.Release();

    std::string id = xComponentId_;
    RemoveInstance(id);

    OH_LOG_Info(LOG_APP, "OnSurfaceDestroyed completed");
}

void PluginRender::DispatchTouchEvent(OH_NativeXComponent* component, void* window)
{
    if (component == nullptr) {
        return;
    }

    OH_NativeXComponent_TouchEvent touchEvent;
    int32_t ret = OH_NativeXComponent_GetTouchEvent(component, &touchEvent);
    if (ret != 0) {
        OH_LOG_Error(LOG_APP, "GetTouchEvent failed: %{public}d", ret);
        return;
    }

    OH_LOG_Info(LOG_APP, "Touch: type=%{public}d x=%{public}f y=%{public}f",
        touchEvent.type, touchEvent.x, touchEvent.y);
}

}
