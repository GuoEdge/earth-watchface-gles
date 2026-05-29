#ifndef EARTH_WATCH_PLUGIN_RENDER_H
#define EARTH_WATCH_PLUGIN_RENDER_H

#include <native_xcomponent/native_xcomponent.h>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <unordered_map>
#include <string>
#include <chrono>

#include "egl_core.h"

namespace earthwatch {

class PluginRender {
public:
    static void RegisterCallback(OH_NativeXComponent* nativeXComponent);

    void OnSurfaceCreated(OH_NativeXComponent* component, void* window);
    void OnSurfaceChanged(OH_NativeXComponent* component, void* window);
    void OnSurfaceDestroyed(OH_NativeXComponent* component, void* window);
    void DispatchTouchEvent(OH_NativeXComponent* component, void* window);

private:
    PluginRender();
    ~PluginRender();

    PluginRender(const PluginRender&) = delete;
    PluginRender& operator=(const PluginRender&) = delete;

    static void OnSurfaceCreatedCallback(OH_NativeXComponent* component, void* window);
    static void OnSurfaceChangedCallback(OH_NativeXComponent* component, void* window);
    static void OnSurfaceDestroyedCallback(OH_NativeXComponent* component, void* window);
    static void DispatchTouchEventCallback(OH_NativeXComponent* component, void* window);

    static PluginRender* GetInstanceById(const std::string& id);
    static void AddInstance(const std::string& id, PluginRender* instance);
    static void RemoveInstance(const std::string& id);

    void StartRenderLoop();
    void StopRenderLoop();
    void RenderLoop();
    void RenderFrame();

    EglCore eglCore_;
    std::thread renderThread_;
    std::mutex frameMutex_;
    std::condition_variable frameCv_;
    std::atomic<bool> isRendering_;
    std::atomic<bool> surfaceReady_;
    OH_NativeXComponent* xComponent_;
    std::string xComponentId_;
    int surfaceWidth_;
    int surfaceHeight_;

    static std::unordered_map<std::string, PluginRender*> instances_;
    static std::mutex instancesMutex_;

    static constexpr int32_t FRAME_INTERVAL_MS = 33;
};

}

#endif
