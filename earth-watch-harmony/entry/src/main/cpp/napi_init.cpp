#include <napi/native_api.h>
#include <native_xcomponent/native_xcomponent.h>
#include <hilog/log.h>
#include "render/plugin_render.h"
#include "render/earth/earth_scene.h"

static EarthScene* g_scene = nullptr;

static napi_value InitScene(napi_env env, napi_callback_info info) {
    size_t argc = 2;
    napi_value args[2];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    int32_t width = 0, height = 0;
    napi_get_value_int32(env, args[0], &width);
    napi_get_value_int32(env, args[1], &height);

    if (!g_scene) g_scene = new EarthScene();
    g_scene->init(width, height);

    return nullptr;
}

static napi_value RenderFrame(napi_env env, napi_callback_info info) {
    size_t argc = 12;
    napi_value args[12];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene) return nullptr;

    int32_t width = 0, height = 0;
    int64_t timeMs = 0;
    int32_t hour = 0, minute = 0, second = 0, nano = 0;
    int32_t month = 0, day = 0, dayOfWeek = 0;
    napi_valuetype type;
    napi_typeof(env, args[11], &type);
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

    size_t lunarLen = 0;
    napi_value lunarVal = args[10];
    napi_get_value_string_utf8(env, lunarVal, nullptr, 0, &lunarLen);
    std::string lunarText(lunarLen, '\0');
    napi_get_value_string_utf8(env, lunarVal, &lunarText[0], lunarLen + 1, &lunarLen);

    g_scene->renderFrame(width, height, timeMs, hour, minute, second, nano,
                         month, day, dayOfWeek, lunarText.c_str(), "", isAmbient);

    return nullptr;
}

static napi_value UpdateSunDirection(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene) return nullptr;

    EarthSceneData data;
    napi_value arr = args[0];
    for (int i = 0; i < 3; i++) {
        napi_value elem;
        napi_get_element(env, arr, i, &elem);
        double val = 0;
        napi_get_value_double(env, elem, &val);
        data.sunDir[i] = static_cast<float>(val);
    }
    g_scene->updateData(data);

    return nullptr;
}

static napi_value UpdateConfig(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    if (!g_scene) return nullptr;

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

    napi_value arcsArr;
    napi_get_named_property(env, obj, "arcDataSource", &arcsArr);
    for (int i = 0; i < 4; i++) {
        napi_value elem;
        napi_get_element(env, arcsArr, i, &elem);
        napi_get_value_int32(env, elem, &cfg.arcDataSource[i]);
    }

    g_scene->updateConfig(cfg);
    return nullptr;
}

static napi_value RequestSpin(napi_env env, napi_callback_info info) {
    if (g_scene) g_scene->requestSpin();
    return nullptr;
}

static napi_value RegisterCallback(napi_env env, napi_callback_info info) {
    size_t argc = 1;
    napi_value args[1];
    napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);

    napi_value exportInstance = args[0];
    OH_NativeXComponent* nativeXComponent = nullptr;
    napi_unwrap(env, exportInstance, reinterpret_cast<void**>(&nativeXComponent));

    if (nativeXComponent) {
        earthwatch::PluginRender::RegisterCallback(nativeXComponent);
    }

    return nullptr;
}

EXTERN_C_START
napi_value Init(napi_env env, napi_value exports) {
    napi_property_descriptor desc[] = {
        {"initScene", nullptr, InitScene, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"renderFrame", nullptr, RenderFrame, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateSunDirection", nullptr, UpdateSunDirection, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"updateConfig", nullptr, UpdateConfig, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"requestSpin", nullptr, RequestSpin, nullptr, nullptr, nullptr, napi_default, nullptr},
        {"registerCallback", nullptr, RegisterCallback, nullptr, nullptr, nullptr, napi_default, nullptr},
    };
    napi_define_properties(env, exports, sizeof(desc) / sizeof(desc[0]), desc);
    return exports;
}
EXTERN_C_END

static napi_module demoModule = {
    .nm_version = 1,
    .nm_flags = 0,
    .nm_filename = nullptr,
    .nm_register_func = Init,
    .nm_modname = "nativerender",
    .nm_priv = ((void*)0),
    .reserved = {0},
};

extern "C" __attribute__((constructor)) void RegisterModule(void) {
    napi_module_register(&demoModule);
}
