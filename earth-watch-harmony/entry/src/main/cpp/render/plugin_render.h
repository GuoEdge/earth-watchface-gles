/**
 * plugin_render.h — XComponent 渲染桥接
 *
 * 架构角色：HarmonyOS XComponent (TEXTURE 模式) 与 EGL/OpenGL ES 之间的桥梁。
 *
 * 生命周期：
 *   1. .so 加载时 Init() 被调用 → 通过 OH_NATIVE_XCOMPONENT_OBJ 获取 OH_NativeXComponent*
 *   2. RegisterCallback() 注册 Surface 生命周期回调
 *   3. OnSurfaceCreated → 初始化 EGL 上下文 + 创建 Surface
 *   4. ArkTS 主线程通过 NAPI 调用 MakeCurrentForRender() / SwapBuffersAfterRender()
 *   5. OnSurfaceDestroyed → 释放 EGL 资源
 *
 * 关键设计：
 *   - 使用 XComponentType.TEXTURE（不是 SURFACE），因为 TEXTURE 模式允许 ArkUI
 *     叠加层（文字、日期等）显示在 GL 渲染内容之上。
 *   - 渲染循环由 ArkTS 侧 setInterval 驱动，C++ 侧不启动独立渲染线程。
 *   - MakeCurrentForRender / SwapBuffersAfterRender 是静态方法，供 NAPI 层在
 *     ArkTS 主线程上调用，确保 EGL 上下文操作在正确线程执行。
 */

#ifndef EARTH_WATCH_PLUGIN_RENDER_H
#define EARTH_WATCH_PLUGIN_RENDER_H

#include "egl_core.h"
#include <native_xcomponent/native_xcomponent.h>
#include <unordered_map>
#include <mutex>
#include <atomic>

namespace earthwatch {

class PluginRender {
public:
    static void RegisterCallback(OH_NativeXComponent* nativeXComponent);

    static void MakeCurrentForRender();
    static void SwapBuffersAfterRender();

    int getSurfaceWidth() const { return surfaceWidth_; }
    int getSurfaceHeight() const { return surfaceHeight_; }

    void OnSurfaceCreated(OH_NativeXComponent* component, void* window);
    void OnSurfaceChanged(OH_NativeXComponent* component, void* window);
    void OnSurfaceDestroyed(OH_NativeXComponent* component, void* window);
    void DispatchTouchEvent(OH_NativeXComponent* component, void* window);

private:
    PluginRender();
    ~PluginRender();

    static void AddInstance(const std::string& id, PluginRender* instance);
    static void RemoveInstance(const std::string& id);
    static PluginRender* GetInstanceById(const std::string& id);

    static void OnSurfaceCreatedCallback(OH_NativeXComponent* component, void* window);
    static void OnSurfaceChangedCallback(OH_NativeXComponent* component, void* window);
    static void OnSurfaceDestroyedCallback(OH_NativeXComponent* component, void* window);
    static void DispatchTouchEventCallback(OH_NativeXComponent* component, void* window);

    EglCore eglCore_;
    std::atomic<bool> surfaceReady_;

    OH_NativeXComponent* xComponent_ = nullptr;
    std::string xComponentId_;
    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    static std::unordered_map<std::string, PluginRender*> instances_;
    static std::mutex instancesMutex_;
};

}

#endif
