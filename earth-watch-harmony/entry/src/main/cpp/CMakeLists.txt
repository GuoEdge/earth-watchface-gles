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
find_library(GLES-lib GLESv2)
find_library(native-window-lib native_window)
find_library(hilog-lib hilog)
find_library(napi-lib ace_napi)
find_library(xcomponent-lib native_xcomponent)

target_link_libraries(nativerender PUBLIC
    ${EGL-lib}
    ${GLES-lib}
    ${native-window-lib}
    ${hilog-lib}
    ${napi-lib}
    ${xcomponent-lib}
)
