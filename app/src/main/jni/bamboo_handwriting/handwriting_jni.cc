// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 手写识别推理层。
//
// 模型：ochwpro（MIT，Copyright (c) 2026 ochwpro，https://github.com/ximeiorg/ochwpro），
// 这里是 1.1 版（CyIME 用用户样本微调过 100 个常用字的输出层，结构不变）。
//
// 输入输出（已用 onnxruntime 1.31 实测确认）：
//   input  float32[1,200,5]   (x, y, dx, dy, pen_down)，按整幅字归一化
//   mask   bool[1,200]        前 seqLen 个为 true
//   logits float32[1,7356]
//
// 笔画 → 上述张量的预处理在 Kotlin 侧（HandwritingRecognizer），与上游实现逐行对齐；
// 这里只负责推理，不碰 Android API，可被任意线程调用（内部有锁）。

#include <android/log.h>
#include <jni.h>

#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#define LOG_TAG "BambooHW"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kFixedLen = 200;
constexpr int kFeatDim = 5;

const OrtApi* g_api = nullptr;
OrtEnv* g_env = nullptr;
OrtSession* g_session = nullptr;
OrtSessionOptions* g_opts = nullptr;
OrtMemoryInfo* g_meminfo = nullptr;

char g_input_name[128] = {0};
char g_mask_name[128] = {0};
char g_output_name[128] = {0};

std::string g_model_path;
std::mutex g_mutex;

void releaseSession() {
    if (g_api == nullptr) return;
    if (g_session != nullptr) {
        g_api->ReleaseSession(g_session);
        g_session = nullptr;
    }
    if (g_opts != nullptr) {
        g_api->ReleaseSessionOptions(g_opts);
        g_opts = nullptr;
    }
    g_model_path.clear();
}

bool ensureApi() {
    if (g_api != nullptr) return true;
    g_api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (g_api == nullptr) {
        LOGE("OrtGetApiBase() returned null");
        return false;
    }
    OrtStatus* status = g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "bamboo_hw", &g_env);
    if (status != nullptr) {
        LOGE("CreateEnv failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        g_api = nullptr;
        return false;
    }
    status = g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &g_meminfo);
    if (status != nullptr) {
        LOGE("CreateCpuMemoryInfo failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return false;
    }
    return true;
}

bool queryIoNames() {
    size_t count = 0;
    if (g_api->SessionGetInputCount(g_session, &count) != nullptr || count < 2) {
        LOGE("model expects 2 inputs, got %zu", count);
        return false;
    }
    OrtAllocator* allocator = nullptr;
    if (g_api->GetAllocatorWithDefaultOptions(&allocator) != nullptr) return false;

    for (size_t i = 0; i < count; i++) {
        char* name = nullptr;
        if (g_api->SessionGetInputName(g_session, i, allocator, &name) != nullptr) return false;
        // 第 0 个是坐标序列，第 1 个是 mask（按模型定义顺序，已实测）
        if (i == 0) {
            std::snprintf(g_input_name, sizeof(g_input_name), "%s", name);
        } else if (i == 1) {
            std::snprintf(g_mask_name, sizeof(g_mask_name), "%s", name);
        }
        allocator->Free(allocator, name);
    }

    size_t out_count = 0;
    if (g_api->SessionGetOutputCount(g_session, &out_count) != nullptr || out_count < 1) {
        LOGE("model has no output");
        return false;
    }
    char* out_name = nullptr;
    if (g_api->SessionGetOutputName(g_session, 0, allocator, &out_name) != nullptr) return false;
    std::snprintf(g_output_name, sizeof(g_output_name), "%s", out_name);
    allocator->Free(allocator, out_name);

    LOGI("io names: input=%s mask=%s output=%s", g_input_name, g_mask_name, g_output_name);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_actions_handwriting_HandwritingNative_nativeInit(JNIEnv* env, jobject /*unused*/,
                                                                      jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (model_path == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return JNI_FALSE;
    std::string wanted(path);
    env->ReleaseStringUTFChars(model_path, path);

    if (g_session != nullptr && g_model_path == wanted) return JNI_TRUE;

    if (!ensureApi()) return JNI_FALSE;
    releaseSession();

    OrtStatus* status = g_api->CreateSessionOptions(&g_opts);
    if (status != nullptr) {
        LOGE("CreateSessionOptions failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return JNI_FALSE;
    }
    g_api->SetIntraOpNumThreads(g_opts, 2);
    g_api->SetSessionGraphOptimizationLevel(g_opts, ORT_ENABLE_ALL);

    status = g_api->CreateSession(g_env, wanted.c_str(), g_opts, &g_session);
    if (status != nullptr) {
        LOGE("CreateSession failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        g_api->ReleaseSessionOptions(g_opts);
        g_opts = nullptr;
        g_session = nullptr;
        return JNI_FALSE;
    }
    g_model_path = wanted;
    if (!queryIoNames()) {
        releaseSession();
        return JNI_FALSE;
    }
    LOGI("model loaded: %s", wanted.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_osfans_trime_actions_handwriting_HandwritingNative_nativeRun(JNIEnv* env, jobject /*unused*/,
                                                                     jfloatArray input,
                                                                     jbooleanArray mask,
                                                                     jint top_k) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_session == nullptr) return nullptr;
    if (input == nullptr || mask == nullptr) return nullptr;
    if (env->GetArrayLength(input) != kFixedLen * kFeatDim || env->GetArrayLength(mask) != kFixedLen) {
        LOGE("unexpected tensor sizes: input=%d mask=%d", env->GetArrayLength(input),
             env->GetArrayLength(mask));
        return nullptr;
    }
    if (top_k <= 0) return nullptr;

    jfloat* in_data = env->GetFloatArrayElements(input, nullptr);
    jboolean* mask_data = env->GetBooleanArrayElements(mask, nullptr);
    if (in_data == nullptr || mask_data == nullptr) {
        if (in_data != nullptr) env->ReleaseFloatArrayElements(input, in_data, JNI_ABORT);
        if (mask_data != nullptr) env->ReleaseBooleanArrayElements(mask, mask_data, JNI_ABORT);
        return nullptr;
    }

    int64_t in_shape[3] = {1, kFixedLen, kFeatDim};
    int64_t mask_shape[2] = {1, kFixedLen};

    OrtValue* in_tensor = nullptr;
    OrtStatus* status = g_api->CreateTensorWithDataAsOrtValue(
        g_meminfo, in_data, sizeof(jfloat) * kFixedLen * kFeatDim, in_shape, 3,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in_tensor);
    if (status != nullptr) {
        LOGE("create input tensor failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        env->ReleaseFloatArrayElements(input, in_data, JNI_ABORT);
        env->ReleaseBooleanArrayElements(mask, mask_data, JNI_ABORT);
        return nullptr;
    }

    OrtValue* mask_tensor = nullptr;
    status = g_api->CreateTensorWithDataAsOrtValue(
        g_meminfo, mask_data, sizeof(jboolean) * kFixedLen, mask_shape, 2,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &mask_tensor);
    if (status != nullptr) {
        LOGE("create mask tensor failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        g_api->ReleaseValue(in_tensor);
        env->ReleaseFloatArrayElements(input, in_data, JNI_ABORT);
        env->ReleaseBooleanArrayElements(mask, mask_data, JNI_ABORT);
        return nullptr;
    }

    const char* in_names[2] = {g_input_name, g_mask_name};
    const OrtValue* in_values[2] = {in_tensor, mask_tensor};
    const char* out_names[1] = {g_output_name};
    OrtValue* out_tensor = nullptr;

    status = g_api->Run(g_session, nullptr, in_names, in_values, 2, out_names, 1, &out_tensor);

    g_api->ReleaseValue(in_tensor);
    g_api->ReleaseValue(mask_tensor);
    env->ReleaseFloatArrayElements(input, in_data, JNI_ABORT);
    env->ReleaseBooleanArrayElements(mask, mask_data, JNI_ABORT);

    if (status != nullptr) {
        LOGE("Run failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return nullptr;
    }

    float* logits = nullptr;
    status = g_api->GetTensorMutableData(out_tensor, reinterpret_cast<void**>(&logits));
    if (status != nullptr || logits == nullptr) {
        if (status != nullptr) g_api->ReleaseStatus(status);
        g_api->ReleaseValue(out_tensor);
        return nullptr;
    }

    OrtTensorTypeAndShapeInfo* info = nullptr;
    size_t classes = 0;
    if (g_api->GetTensorTypeAndShape(out_tensor, &info) == nullptr) {
        size_t dims = 0;
        if (g_api->GetDimensionsCount(info, &dims) == nullptr && dims > 0) {
            std::vector<int64_t> shape(dims);
            if (g_api->GetDimensions(info, shape.data(), dims) == nullptr) {
                classes = static_cast<size_t>(shape[dims - 1]);
            }
        }
        g_api->ReleaseTensorTypeAndShapeInfo(info);
    }
    if (classes == 0) {
        g_api->ReleaseValue(out_tensor);
        return nullptr;
    }

    // softmax（只为给出可读的分数，排序只依赖 logits 相对大小）
    float max_logit = logits[0];
    for (size_t i = 1; i < classes; i++) {
        if (logits[i] > max_logit) max_logit = logits[i];
    }
    std::vector<float> prob(classes);
    double sum = 0.0;
    for (size_t i = 0; i < classes; i++) {
        prob[i] = std::exp(logits[i] - max_logit);
        sum += prob[i];
    }
    if (sum <= 0.0) sum = 1.0;
    for (size_t i = 0; i < classes; i++) {
        prob[i] = static_cast<float>(prob[i] / sum);
    }

    int k = top_k > static_cast<jint>(classes) ? static_cast<int>(classes) : top_k;
    // 类别有七千多个，top-k 用局部选择就够，不做整表排序
    std::vector<int> order(classes);
    for (size_t i = 0; i < classes; i++) order[i] = static_cast<int>(i);
    std::partial_sort(order.begin(), order.begin() + k, order.end(),
                      [&prob](int a, int b) { return prob[a] > prob[b]; });

    std::vector<jfloat> out(static_cast<size_t>(k) * 2);
    for (int i = 0; i < k; i++) {
        out[i * 2] = static_cast<jfloat>(order[i]);
        out[i * 2 + 1] = prob[order[i]];
    }

    g_api->ReleaseValue(out_tensor);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_actions_handwriting_HandwritingNative_nativeRelease(JNIEnv* /*env*/,
                                                                        jobject /*unused*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseSession();
}
