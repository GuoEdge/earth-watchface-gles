# CMakeLists.txt — Native 渲染库构建配置
#
# 构建目标：libnativerender.so
# 此 .so 被 XComponent(libraryname='nativerender') 自动加载，
# 加载时调用 Init() 函数注册 NAPI 接口和 XComponent 回调。
#
# 依赖库说明：
#   EGL         — EGL 上下文管理（eglCreateContext, eglMakeCurrent 等）
#   GLESv3      — OpenGL ES 3.0 渲染 API（实际创建 ES 2.0 上下文，但使用 ES 3.0 头文件）
#   ace_ndk.z   — HarmonyOS ACE NDK 基础库（XComponent 所需）
#   native_window — 原生窗口操作（获取宽高）
#   hilog       — 日志输出
#   ace_napi    — NAPI 框架（napi_define_properties 等）
#   native_xcomponent — XComponent 回调注册（OH_NativeXComponent_RegisterCallback）

cmake_minimum_required(VERSION 3.10)
project(nativerender)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -Wall")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/render
    ${CMAKE_CURRENT_SOURCE_DIR}/render/earth
)

add_library(nativerender SHARED
    napi_init.cpp
    render/egl_core.cpp
    render/plugin_render.cpp
    render/earth/earth_renderer.cpp
    render/earth/gl_primitives.cpp
    render/earth/gl_overlay.cpp
    render/earth/earth_scene.cpp
)

find_library(EGL-lib EGL)
find_library(GLES-lib GLESv3)
find_library(ace-ndk-lib ace_ndk.z)
find_library(native-window-lib native_window)
find_library(hilog-lib hilog)
find_library(napi-lib ace_napi)
find_library(xcomponent-lib native_xcomponent)

target_link_libraries(nativerender PUBLIC
    ${EGL-lib}
    ${GLES-lib}
    ${ace-ndk-lib}
    ${native-window-lib}
    ${hilog-lib}
    ${napi-lib}
    ${xcomponent-lib}
)
