/**
 * napi_init.cpp — NAPI 桥接层
 *
 * 架构角色：ArkTS (Index.ets) 与 C++ Native 渲染层之间的唯一接口。
 * 所有 ArkTS → C++ 的调用都经过此文件中定义的 NAPI 函数。
 *
 * 数据流：
 *   ArkTS setInterval(33ms) → renderFrame() → MakeCurrent → g_scene->renderFrame() → SwapBuffers
 *   ArkTS onLoad           → initScene()   → g_scene->init()
 *   ArkTS async            → loadTextures() → stb_image 解码 → g_scene->loadDay/Night/CloudTexture()
 *   ArkTS setInterval      → updateSunDirection() / updateData() / updateConfig()
 *
 * 关键设计决策：
 *   - 渲染由 ArkTS 主线程驱动（setInterval），不使用 C++ 渲染线程，
 *     避免 EGL 上下文在两个线程间竞争。
 *   - 纹理通过 ArkTS 读取 rawfile → ArrayBuffer → NAPI 传递 → stb_image 解码，
 *     因为 HarmonyOS Native 层没有直接的 rawfile 读取 API。
 *   - g_scene 是全局单例，生命周期由 destroyScene() 管理。
 */

#include <napi/native_api.h>
#include <native_xcomponent/native_xcomponent.h>
#include <hilog/log.h>
#include "render/plugin_render.h"
#include "render/earth/earth_scene.h"

#define STB_IMAGE_IMPLEMENTATION
#include "render/earth/stb_image.h"

EarthScene* g_scene = nullptr;

static napi_value InitScene(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (argc < 2) return nullptr;

    int32_t width = 0, height = 0;
    napi_get_value_int32(env, args[0], &width);
    napi_get_value_int32(env, args[1], &height);

    if (g_scene) {
        delete g_scene;
        g_scene = nullptr;
    }
    g_scene = new EarthScene();

    earthwatch::PluginRender::MakeCurrentForRender();

    g_scene->init(width, height);

    OH_LOG_Print(LOG_APP, LOG_INFO, 0x3200, "EarthWatch",
                 "InitScene %{public}d x %{public}d", width, height);

    napi_value result;
    napi_get_undefined(env, &result);
    return result;
}

static napi_value RenderFrame(napi_env env, napi_callback_info info) {
    size_t argc = 12;
    napi_value args[12];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene || argc < 12) return nullptr;

    int32_t width = 0, height = 0;
    int64_t timeMs = 0;
    int32_t hour = 0, minute = 0, second = 0, nano = 0;
    int32_t month = 0, day = 0, dayOfWeek = 0;
    bool isAmbient = false;

    napi_get_value_int32(env, args[0], &width);
    napi_get_value_int32(env, args[1], &height);
    napi_get_value_int64(env, args[2], &timeMs);
    napi_get_value_int32(env, args[3], &hour);
    napi_get_value_int32(env, args[4], &minute);
    napi_get_value_int32(env, args[5], &second);
    napi_get_value_int32(env, args[6], &nano);
    napi_get_value_int32(env, args[7], &month);
    napi_get_value_int32(env, args[8], &day);
    napi_get_value_int32(env, args[9], &dayOfWeek);
    napi_get_value_bool(env, args[11], &isAmbient);

    std::string lunarText;
    napi_value lunarVal = args[10];
    if (lunarVal != nullptr) {
        napi_valuetype vtype = napi_undefined;
        napi_typeof(env, lunarVal, &vtype);
        if (vtype == napi_string) {
            size_t lunarLen = 0;
            napi_get_value_string_utf8(env, lunarVal, nullptr, 0, &lunarLen);
            if (lunarLen > 0) {
                lunarText.resize(lunarLen);
                napi_get_value_string_utf8(env, lunarVal, &lunarText[0], lunarLen + 1, &lunarLen);
            }
        }
    }

    earthwatch::PluginRender::MakeCurrentForRender();

    g_scene->renderFrame(width, height, timeMs, hour, minute, second, nano,
                         month, day, dayOfWeek, lunarText.c_str(), "", isAmbient);

    earthwatch::PluginRender::SwapBuffersAfterRender();

    return nullptr;
}

static napi_value UpdateSunDirection(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene || argc < 1) return nullptr;

    napi_value arr = args[0];
    float sunDir[3] = {0, 0, 1};
    napi_value xVal, yVal, zVal;
    napi_get_element(env, arr, 0, &xVal);
    napi_get_element(env, arr, 1, &yVal);
    napi_get_element(env, arr, 2, &zVal);
    double xd = 0, yd = 0, zd = 0;
    napi_get_value_double(env, xVal, &xd);
    napi_get_value_double(env, yVal, &yd);
    napi_get_value_double(env, zVal, &zd);
    sunDir[0] = static_cast<float>(xd);
    sunDir[1] = static_cast<float>(yd);
    sunDir[2] = static_cast<float>(zd);

    g_scene->updateSunDirection(sunDir);
    return nullptr;
}

static napi_value UpdateConfig(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene || argc < 1) return nullptr;

    EarthSceneConfig cfg;
    napi_value obj = args[0];
    napi_value val;

    napi_get_named_property(env, obj, "accentIdx", &val);
    napi_get_value_int32(env, val, &cfg.accentIdx);
    napi_get_named_property(env, obj, "showLunar", &val);
    napi_get_value_bool(env, val, &cfg.showLunar);
    napi_get_named_property(env, obj, "showSensors", &val);
    napi_get_value_bool(env, val, &cfg.showSensors);
    napi_get_named_property(env, obj, "showClouds", &val);
    napi_get_value_bool(env, val, &cfg.showClouds);
    napi_get_named_property(env, obj, "animMode", &val);
    napi_get_value_int32(env, val, &cfg.animMode);

    napi_value arcArr;
    napi_get_named_property(env, obj, "arcDataSource", &arcArr);
    for (int i = 0; i < 4; i++) {
        napi_value elem;
        napi_get_element(env, arcArr, i, &elem);
        int32_t v = 0;
        napi_get_value_int32(env, elem, &v);
        cfg.arcDataSource[i] = v;
    }

    g_scene->updateConfig(cfg);
    return nullptr;
}

static napi_value RequestSpin(napi_env env, napi_callback_info info) {
    if (g_scene) g_scene->requestSpin();
    return nullptr;
}

static napi_value UpdateData(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene || argc < 1) return nullptr;

    EarthSceneData data;
    napi_value obj = args[0];
    napi_value val;

    napi_get_named_property(env, obj, "batteryPct", &val);
    double bp = 0;
    napi_get_value_double(env, val, &bp);
    data.batteryPct = static_cast<float>(bp);

    napi_get_named_property(env, obj, "isCharging", &val);
    napi_get_value_bool(env, val, &data.isCharging);

    napi_get_named_property(env, obj, "batteryIsLow", &val);
    napi_get_value_bool(env, val, &data.batteryIsLow);

    napi_get_named_property(env, obj, "temperature", &val);
    double temp = 0;
    napi_get_value_double(env, val, &temp);
    data.temperature = static_cast<float>(temp);

    napi_get_named_property(env, obj, "uvIndex", &val);
    double uv = -1;
    napi_get_value_double(env, val, &uv);
    data.uvIndex = static_cast<float>(uv);

    napi_get_named_property(env, obj, "feelsLike", &val);
    double fl = 0;
    napi_get_value_double(env, val, &fl);
    data.feelsLike = static_cast<float>(fl);

    napi_get_named_property(env, obj, "precipProb", &val);
    double pp = -1;
    napi_get_value_double(env, val, &pp);
    data.precipProb = static_cast<float>(pp);

    napi_get_named_property(env, obj, "notifCount", &val);
    int32_t nc = 0;
    napi_get_value_int32(env, val, &nc);
    data.notifCount = nc;

    g_scene->updateData(data);
    return nullptr;
}

static napi_value DestroyScene(napi_env env, napi_callback_info info) {
    if (g_scene) {
        g_scene->release();
        delete g_scene;
        g_scene = nullptr;
    }
    return nullptr;
}

/**
 * 从 ArrayBuffer 解码图像并加载纹理。
 * stb_load_from_memory 强制请求 4 通道 (RGBA)，因此无论源文件是 JPEG 还是 PNG，
 * 解码后的像素数据始终是 RGBA 格式，loadTexture 应使用 isRgb=false。
 */
static bool decodeAndLoad(napi_env env, napi_value arrayBuffer,
                           void (EarthScene::*loader)(const uint8_t*, int, int)) {
    size_t byteLength = 0;
    void* data = nullptr;
    napi_get_arraybuffer_info(env, arrayBuffer, &data, &byteLength);
    if (!data || byteLength == 0) return false;

    int w = 0, h = 0, channels = 0;
    unsigned char* pixels = stbi_load_from_memory(
        static_cast<const stbi_uc*>(data), static_cast<int>(byteLength),
        &w, &h, &channels, 4);
    if (!pixels) return false;

    (g_scene->*loader)(pixels, w, h);
    stbi_image_free(pixels);
    return true;
}

static napi_value LoadTextures(napi_env env, napi_callback_info info) {
    size_t argc = 3;
    napi_value args[3];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene || argc < 3) return nullptr;

    earthwatch::PluginRender::MakeCurrentForRender();

    decodeAndLoad(env, args[0], &EarthScene::loadDayTexture);
    decodeAndLoad(env, args[1], &EarthScene::loadNightTexture);
    decodeAndLoad(env, args[2], &EarthScene::loadCloudTexture);

    return nullptr;
}

extern "C" __attribute__((visibility("default"))) napi_value Init(napi_env env, napi_value exports)
{
    napi_property_descriptor desc[] = {
        {"initScene", nullptr, InitScene, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderFrame", nullptr, RenderFrame, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateSunDirection", nullptr, UpdateSunDirection, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateConfig", nullptr, UpdateConfig, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"requestSpin", nullptr, RequestSpin, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateData", nullptr, UpdateData, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"destroyScene", nullptr, DestroyScene, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"loadTextures", nullptr, LoadTextures, nullptr, nullptr, nullptr, napi_default, nullptr},
    };

    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);

    napi_value exportObj;
    napi_get_named_property(env, exports, OH_NATIVE_XCOMPONENT_OBJ, &exportObj);

    OH_NativeXComponent* nativeXComponent = nullptr;
    napi_unwrap(env, exportObj, reinterpret_cast<void**>(&nativeXComponent));

    if (nativeXComponent != nullptr) {
        earthwatch::PluginRender::RegisterCallback(nativeXComponent);
    }

    return exports;
}
