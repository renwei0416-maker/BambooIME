// SPDX-FileCopyrightText: 2026 BambooIME contributors
//
// SPDX-License-Identifier: GPL-3.0-or-later
//
// 拍照识字（PP-OCRv5）推理。模型来自 PaddleOCR（Apache-2.0），
// 运行时复用 sherpa-onnx 已经下载好的 onnxruntime-android（C API），
// 所以这里不引入额外 AAR，APK 的 native 体积不增加。
//
// 流程与 CyIME（GPL-3.0）的 DocumentOcrPipeline 对齐：
//   det（DB 检测）→ 概率图二值化 → 连通域 → 最小外接框 + unclip → 按行归并
//   → 逐框裁图（高 48）→ rec（CTC 解码）。
//
// 与 CyIME 的差异（因为不能为此引入 OpenCV）：
//   * 外接框用轴对齐矩形，不做旋转矩形 / 透视矫正 —— 只保证水平拍摄的文字；
//   * 归一化、缩放、阈值、unclip 公式、CTC 规则全部照抄 CyIME。

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "onnxruntime_c_api.h"

#define LOG_TAG "bamboo_ocr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

const OrtApi* g_api = nullptr;
OrtEnv* g_env = nullptr;
OrtSessionOptions* g_opts = nullptr;
OrtMemoryInfo* g_meminfo = nullptr;
OrtSession* g_det = nullptr;
OrtSession* g_rec = nullptr;
std::string g_det_in, g_det_out, g_rec_in, g_rec_out;
std::vector<std::string> g_alphabet;
std::mutex g_mutex;

// 检测阶段的输入限制，照抄 CyIME。
constexpr float kDetMinSide = 320.f;
constexpr float kDetMaxSide = 2560.f;
constexpr float kDetMaxArea = 1048576.f;
// 下面三个阈值可由设置页调整（nativeSetThresholds），所以不能是 constexpr。
float gDetThreshold = .3f;
float gDetBoxScore = .5f;
constexpr int kRecHeight = 48;
constexpr int kRecMaxWidth = 1600;
constexpr int kRecMinTensorWidth = 320;
float gRecMinConfidence = .6f;

struct RgbImage {
    int width = 0;
    int height = 0;
    std::vector<uint8_t> pixels;  // RGBRGB...
};

bool ensureApi() {
    if (g_api != nullptr) return true;
    g_api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (g_api == nullptr) {
        LOGE("OrtGetApiBase() returned null");
        return false;
    }
    OrtStatus* status = g_api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "bamboo_ocr", &g_env);
    if (status != nullptr) {
        LOGE("CreateEnv failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        g_env = nullptr;
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

/** 取会话第一个输入/输出名（两个模型的输入输出都只有一个）。 */
bool queryIoNames(OrtSession* session, std::string& in_name, std::string& out_name) {
    size_t count = 0;
    if (g_api->SessionGetInputCount(session, &count) != nullptr || count == 0) return false;
    OrtAllocator* allocator = nullptr;
    if (g_api->GetAllocatorWithDefaultOptions(&allocator) != nullptr) return false;
    char* name = nullptr;
    if (g_api->SessionGetInputName(session, 0, allocator, &name) != nullptr || name == nullptr) return false;
    in_name.assign(name);
    g_api->AllocatorFree(allocator, name);

    if (g_api->SessionGetOutputCount(session, &count) != nullptr || count == 0) return false;
    if (g_api->SessionGetOutputName(session, 0, allocator, &name) != nullptr || name == nullptr) {
        return false;
    }
    out_name.assign(name);
    g_api->AllocatorFree(allocator, name);
    return true;
}

bool createSession(const std::string& path, OrtSession** out_session, std::string& in_name,
                   std::string& out_name) {
    OrtStatus* status = g_api->CreateSession(g_env, path.c_str(), g_opts, out_session);
    if (status != nullptr) {
        LOGE("CreateSession(%s) failed: %s", path.c_str(), g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return false;
    }
    if (!queryIoNames(*out_session, in_name, out_name)) {
        LOGE("queryIoNames(%s) failed", path.c_str());
        g_api->ReleaseSession(*out_session);
        *out_session = nullptr;
        return false;
    }
    return true;
}

void releaseSessions() {
    if (g_det != nullptr) {
        g_api->ReleaseSession(g_det);
        g_det = nullptr;
    }
    if (g_rec != nullptr) {
        g_api->ReleaseSession(g_rec);
        g_rec = nullptr;
    }
    if (g_opts != nullptr) {
        g_api->ReleaseSessionOptions(g_opts);
        g_opts = nullptr;
    }
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

/** 双线性缩放（输入 RGB，输出 RGB）。 */
void resizeRgb(const RgbImage& src, RgbImage& dst, int width, int height) {
    dst.width = width;
    dst.height = height;
    dst.pixels.assign(static_cast<size_t>(width) * height * 3, 0);
    if (src.width <= 0 || src.height <= 0) return;
    const float sx = static_cast<float>(src.width) / width;
    const float sy = static_cast<float>(src.height) / height;
    for (int y = 0; y < height; y++) {
        float fy = (y + .5f) * sy - .5f;
        if (fy < 0) fy = 0;
        int y0 = static_cast<int>(fy);
        int y1 = std::min(y0 + 1, src.height - 1);
        float wy = fy - y0;
        for (int x = 0; x < width; x++) {
            float fx = (x + .5f) * sx - .5f;
            if (fx < 0) fx = 0;
            int x0 = static_cast<int>(fx);
            int x1 = std::min(x0 + 1, src.width - 1);
            float wx = fx - x0;
            const uint8_t* p00 = &src.pixels[(static_cast<size_t>(y0) * src.width + x0) * 3];
            const uint8_t* p01 = &src.pixels[(static_cast<size_t>(y0) * src.width + x1) * 3];
            const uint8_t* p10 = &src.pixels[(static_cast<size_t>(y1) * src.width + x0) * 3];
            const uint8_t* p11 = &src.pixels[(static_cast<size_t>(y1) * src.width + x1) * 3];
            uint8_t* out = &dst.pixels[(static_cast<size_t>(y) * width + x) * 3];
            for (int c = 0; c < 3; c++) {
                float top = p00[c] * (1 - wx) + p01[c] * wx;
                float bottom = p10[c] * (1 - wx) + p11[c] * wx;
                out[c] = static_cast<uint8_t>(std::lround(top * (1 - wy) + bottom * wy));
            }
        }
    }
}

/**
 * 组装 CHW 浮点张量。
 * detection=true：整幅缩放到 width×height，按 ImageNet 均值方差归一化，右/下补零到 padded；
 * detection=false：内容占左侧 width 列，其余补零（rec 的宽度可变，最短 320）。
 */
std::vector<float> buildTensor(const RgbImage& image, int width, int height, int padded_width,
                               bool detection) {
    std::vector<float> tensor(static_cast<size_t>(3) * height * padded_width, 0.f);
    const float mean[3] = {.485f, .456f, .406f};
    const float stddev[3] = {.229f, .224f, .225f};
    for (int c = 0; c < 3; c++) {
        for (int y = 0; y < height; y++) {
            const uint8_t* row = &image.pixels[(static_cast<size_t>(y) * image.width) * 3];
            float* out = &tensor[(static_cast<size_t>(c) * height + y) * padded_width];
            for (int x = 0; x < width; x++) {
                float pixel = row[x * 3 + c] / 255.f;
                out[x] = detection ? (pixel - mean[c]) / stddev[c] : (pixel - .5f) / .5f;
            }
        }
    }
    return tensor;
}

/** 跑一次模型，返回输出的 float 数据与形状。 */
bool runModel(OrtSession* session, const std::string& in_name, const std::string& out_name,
              std::vector<float>& input, const std::vector<int64_t>& shape,
              std::vector<float>& output, std::vector<int64_t>& out_shape) {
    OrtValue* in_tensor = nullptr;
    OrtStatus* status = g_api->CreateTensorWithDataAsOrtValue(
        g_meminfo, input.data(), input.size() * sizeof(float), shape.data(),
        static_cast<size_t>(shape.size()), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in_tensor);
    if (status != nullptr) {
        LOGE("create tensor failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return false;
    }
    const char* in_names[1] = {in_name.c_str()};
    const OrtValue* in_values[1] = {in_tensor};
    const char* out_names[1] = {out_name.c_str()};
    OrtValue* out_tensor = nullptr;
    status = g_api->Run(session, nullptr, in_names, in_values, 1, out_names, 1, &out_tensor);
    g_api->ReleaseValue(in_tensor);
    if (status != nullptr) {
        LOGE("Run failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return false;
    }
    float* data = nullptr;
    status = g_api->GetTensorMutableData(out_tensor, reinterpret_cast<void**>(&data));
    if (status != nullptr || data == nullptr) {
        if (status != nullptr) g_api->ReleaseStatus(status);
        g_api->ReleaseValue(out_tensor);
        return false;
    }
    OrtTensorTypeAndShapeInfo* info = nullptr;
    out_shape.clear();
    if (g_api->GetTensorTypeAndShape(out_tensor, &info) == nullptr) {
        size_t dims = 0;
        if (g_api->GetDimensionsCount(info, &dims) == nullptr && dims > 0) {
            out_shape.resize(dims);
            g_api->GetDimensions(info, out_shape.data(), dims);
        }
        g_api->ReleaseTensorTypeAndShapeInfo(info);
    }
    if (out_shape.empty()) {
        g_api->ReleaseValue(out_tensor);
        return false;
    }
    size_t total = 1;
    for (int64_t dim : out_shape) total *= static_cast<size_t>(dim);
    output.assign(data, data + total);
    g_api->ReleaseValue(out_tensor);
    return true;
}

int multiple32(double size) {
    return std::max(32, static_cast<int>(std::lrint(size / 32.0) * 32));
}

struct TextLine {
    float left = 0, top = 0, right = 0, bottom = 0;
    float score = 0;
};

/** det 概率图 → 文本行框（det 图坐标）。 */
std::vector<TextLine> detectLines(const std::vector<float>& map, int width, int height) {
    std::vector<uint8_t> binary(static_cast<size_t>(width) * height, 0);
    for (size_t i = 0; i < binary.size(); i++) binary[i] = map[i] > gDetThreshold ? 1 : 0;

    // 2×2 膨胀：把断开的笔画连起来（与 CyIME 的 dilate(2×2) 等价）。
    std::vector<uint8_t> dilated(binary.size(), 0);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            if (!binary[static_cast<size_t>(y) * width + x]) continue;
            dilated[static_cast<size_t>(y) * width + x] = 1;
            if (x + 1 < width) dilated[static_cast<size_t>(y) * width + x + 1] = 1;
            if (y + 1 < height) {
                dilated[static_cast<size_t>(y + 1) * width + x] = 1;
                if (x + 1 < width) dilated[static_cast<size_t>(y + 1) * width + x + 1] = 1;
            }
        }
    }

    // 连通域（8 邻域，迭代式洪水填充，避免递归爆栈）。
    std::vector<int> labels(dilated.size(), 0);
    std::vector<TextLine> lines;
    std::vector<int> stack;
    int next_label = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            const size_t start = static_cast<size_t>(y) * width + x;
            if (!dilated[start] || labels[start] != 0) continue;
            next_label++;
            stack.clear();
            stack.push_back(static_cast<int>(start));
            labels[start] = next_label;
            int min_x = x, max_x = x, min_y = y, max_y = y;
            double score_sum = 0;
            int count = 0;
            while (!stack.empty()) {
                int index = stack.back();
                stack.pop_back();
                int px = index % width;
                int py = index / width;
                min_x = std::min(min_x, px);
                max_x = std::max(max_x, px);
                min_y = std::min(min_y, py);
                max_y = std::max(max_y, py);
                score_sum += map[index];
                count++;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue;
                        const size_t ni = static_cast<size_t>(ny) * width + nx;
                        if (!dilated[ni] || labels[ni] != 0) continue;
                        labels[ni] = next_label;
                        stack.push_back(static_cast<int>(ni));
                    }
                }
            }
            if (count <= 0) continue;
            const int box_w = max_x - min_x + 1;
            const int box_h = max_y - min_y + 1;
            if (std::min(box_w, box_h) < 3) continue;
            const double score = score_sum / count;
            if (score < gDetBoxScore) continue;
            // DB 的 unclip：周长外扩 offset（CyIME 用 rect 面积 ×1.4 / 周长）。
            const double offset = static_cast<double>(box_w) * box_h * 1.4 / (2.0 * (box_w + box_h));
            TextLine line;
            line.left = static_cast<float>(std::max(0.0, min_x - offset));
            line.top = static_cast<float>(std::max(0.0, min_y - offset));
            line.right = static_cast<float>(std::min<double>(width - 1, max_x + offset));
            line.bottom = static_cast<float>(std::min<double>(height - 1, max_y + offset));
            if (line.right - line.left < 5 || line.bottom - line.top < 5) continue;
            line.score = static_cast<float>(score);
            lines.push_back(line);
        }
    }

    // 同一行被拆成多块时按「纵向重叠 + 横向相邻」归并。
    std::sort(lines.begin(), lines.end(), [](const TextLine& a, const TextLine& b) {
        return a.top < b.top;
    });
    std::vector<TextLine> merged;
    for (const TextLine& line : lines) {
        bool absorbed = false;
        for (TextLine& target : merged) {
            const float overlap_top = std::max(target.top, line.top);
            const float overlap_bottom = std::min(target.bottom, line.bottom);
            const float overlap = overlap_bottom - overlap_top;
            const float min_height = std::min(target.bottom - target.top, line.bottom - line.top);
            const float gap = std::max(target.left, line.left) - std::min(target.right, line.right);
            if (min_height > 0 && overlap > min_height * .5f && gap < min_height) {
                target.left = std::min(target.left, line.left);
                target.top = std::min(target.top, line.top);
                target.right = std::max(target.right, line.right);
                target.bottom = std::max(target.bottom, line.bottom);
                absorbed = true;
                break;
            }
        }
        if (!absorbed) merged.push_back(line);
    }

    std::sort(merged.begin(), merged.end(), [](const TextLine& a, const TextLine& b) {
        if (std::fabs(a.top - b.top) > 8.f) return a.top < b.top;
        return a.left < b.left;
    });
    return merged;
}

/** 一条文本行 → 识别结果（空字符串表示没认出来）。 */
std::string readLine(const RgbImage& image, const TextLine& line) {
    const int left = static_cast<int>(std::floor(line.left));
    const int top = static_cast<int>(std::floor(line.top));
    const int right = static_cast<int>(std::ceil(line.right));
    const int bottom = static_cast<int>(std::ceil(line.bottom));
    const int box_w = right - left + 1;
    const int box_h = bottom - top + 1;
    if (box_w <= 1 || box_h <= 1) return {};

    RgbImage crop;
    crop.width = box_w;
    crop.height = box_h;
    crop.pixels.resize(static_cast<size_t>(box_w) * box_h * 3);
    for (int y = 0; y < box_h; y++) {
        const int src_y = std::min(image.height - 1, top + y);
        std::memcpy(&crop.pixels[static_cast<size_t>(y) * box_w * 3],
                    &image.pixels[(static_cast<size_t>(src_y) * image.width + left) * 3],
                    static_cast<size_t>(box_w) * 3);
    }

    int content_width = static_cast<int>(std::ceil(
        static_cast<double>(kRecHeight) * crop.width / crop.height));
    content_width = std::max(1, std::min(kRecMaxWidth, content_width));
    const int tensor_width = std::max(kRecMinTensorWidth, content_width);

    RgbImage resized;
    resizeRgb(crop, resized, content_width, kRecHeight);
    std::vector<float> input = buildTensor(resized, content_width, kRecHeight, tensor_width, false);

    std::vector<float> output;
    std::vector<int64_t> shape;
    const std::vector<int64_t> input_shape = {1, 3, kRecHeight, tensor_width};
    if (!runModel(g_rec, g_rec_in, g_rec_out, input, input_shape, output, shape)) return {};
    if (shape.size() != 3) return {};
    const int steps = static_cast<int>(shape[1]);
    const int classes = static_cast<int>(shape[2]);
    // 类别数以模型为准：字典少几条也不该整行丢弃（越界的类别按 blank 处理）。
    static bool warned = false;
    if (!warned && classes != static_cast<int>(g_alphabet.size())) {
        warned = true;
        LOGE("alphabet size %zu != model classes %d (out-of-range -> blank)",
             g_alphabet.size(), classes);
    }
    const int alphabet_size = static_cast<int>(g_alphabet.size());

    std::string text;
    float confidence = 0;
    int chars = 0;
    int previous = -1;
    for (int t = 0; t < steps; t++) {
        const float* row = &output[static_cast<size_t>(t) * classes];
        int best = 0;
        for (int c = 1; c < classes; c++) {
            if (row[c] > row[best]) best = c;
        }
        if (best != 0 && best != previous && best < alphabet_size) {
            text.append(g_alphabet[best]);
            confidence += row[best];
            chars++;
        }
        previous = best;
    }
    if (chars == 0) return {};
    confidence /= chars;
    if (confidence < gRecMinConfidence) return {};
    return text;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_actions_ocr_OcrNative_nativeInit(JNIEnv* env, jobject /*unused*/,
                                                       jstring det_path, jstring rec_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!ensureApi()) return JNI_FALSE;
    const std::string det = fromJString(env, det_path);
    const std::string rec = fromJString(env, rec_path);
    if (det.empty() || rec.empty()) return JNI_FALSE;

    releaseSessions();
    OrtStatus* status = g_api->CreateSessionOptions(&g_opts);
    if (status != nullptr) {
        LOGE("CreateSessionOptions failed: %s", g_api->GetErrorMessage(status));
        g_api->ReleaseStatus(status);
        return JNI_FALSE;
    }
    g_api->SetIntraOpNumThreads(g_opts, 2);
    g_api->SetSessionGraphOptimizationLevel(g_opts, ORT_ENABLE_ALL);

    if (!createSession(det, &g_det, g_det_in, g_det_out)) {
        releaseSessions();
        return JNI_FALSE;
    }
    if (!createSession(rec, &g_rec, g_rec_in, g_rec_out)) {
        releaseSessions();
        return JNI_FALSE;
    }
    LOGI("ocr models loaded: det=%s rec=%s", det.c_str(), rec.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_actions_ocr_OcrNative_nativeSetAlphabet(JNIEnv* env, jobject /*unused*/,
                                                              jobjectArray chars) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (chars == nullptr) return JNI_FALSE;
    const jsize count = env->GetArrayLength(chars);
    if (count <= 0) return JNI_FALSE;
    std::vector<std::string> alphabet;
    alphabet.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto entry = static_cast<jstring>(env->GetObjectArrayElement(chars, i));
        alphabet.push_back(fromJString(env, entry));
        env->DeleteLocalRef(entry);
    }
    g_alphabet = std::move(alphabet);
    LOGI("alphabet loaded: %zu", g_alphabet.size());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_osfans_trime_actions_ocr_OcrNative_nativeRecognize(JNIEnv* env, jobject /*unused*/,
                                                            jbyteArray pixels, jint width,
                                                            jint height, jboolean single) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_det == nullptr || g_rec == nullptr || g_alphabet.empty()) return nullptr;
    if (pixels == nullptr || width <= 0 || height <= 0) return nullptr;
    const jsize size = env->GetArrayLength(pixels);
    if (size != width * height * 3) {
        LOGE("unexpected pixel buffer: %d (want %d)", size, width * height * 3);
        return nullptr;
    }

    RgbImage image;
    image.width = width;
    image.height = height;
    image.pixels.resize(static_cast<size_t>(size));
    env->GetByteArrayRegion(pixels, 0, size, reinterpret_cast<jbyte*>(image.pixels.data()));

    // 检测输入尺寸：短边不小于 320，长边不超过 2560，面积不超过 1M，最后对齐到 32。
    const int short_side = std::min(width, height);
    const int long_side = std::max(width, height);
    float scale = std::max(1.f, kDetMinSide / static_cast<float>(short_side));
    scale = std::min(scale, kDetMaxSide / static_cast<float>(long_side));
    while (static_cast<float>(width) * height * scale * scale > kDetMaxArea && scale > .05f) {
        scale *= .9f;
    }
    const int det_width = multiple32(width * scale);
    const int det_height = multiple32(height * scale);

    RgbImage det_image;
    resizeRgb(image, det_image, det_width, det_height);
    std::vector<float> det_input = buildTensor(det_image, det_width, det_height, det_width, true);

    std::vector<float> map;
    std::vector<int64_t> map_shape;
    const std::vector<int64_t> det_input_shape = {1, 3, det_height, det_width};
    if (!runModel(g_det, g_det_in, g_det_out, det_input, det_input_shape, map, map_shape)) {
        return nullptr;
    }
    if (map_shape.size() != 4) return nullptr;
    const int map_height = static_cast<int>(map_shape[2]);
    const int map_width = static_cast<int>(map_shape[3]);
    if (map_width <= 0 || map_height <= 0) return nullptr;

    std::vector<TextLine> lines = detectLines(map, map_width, map_height);

    // 单字模式：只认离画面中心最近的那一行，避免把旁边的字一起带进来。
    if (single && lines.size() > 1) {
        const float cx = static_cast<float>(map_width) / 2.f;
        const float cy = static_cast<float>(map_height) / 2.f;
        size_t best = 0;
        float best_dist = -1.f;
        for (size_t i = 0; i < lines.size(); i++) {
            const float dx = (lines[i].left + lines[i].right) / 2.f - cx;
            const float dy = (lines[i].top + lines[i].bottom) / 2.f - cy;
            const float dist = dx * dx + dy * dy;
            if (best_dist < 0.f || dist < best_dist) {
                best_dist = dist;
                best = i;
            }
        }
        const TextLine picked = lines[best];
        lines.clear();
        lines.push_back(picked);
    }

    // det 图坐标 → 原图坐标
    const float sx = static_cast<float>(width) / map_width;
    const float sy = static_cast<float>(height) / map_height;
    std::string text;
    for (const TextLine& line : lines) {
        TextLine scaled;
        scaled.left = std::max(0.f, line.left * sx);
        scaled.top = std::max(0.f, line.top * sy);
        scaled.right = std::min(static_cast<float>(width - 1), line.right * sx);
        scaled.bottom = std::min(static_cast<float>(height - 1), line.bottom * sy);
        std::string value = readLine(image, scaled);
        if (value.empty()) continue;
        if (!text.empty()) text.append("\n");
        text.append(value);
    }
    if (text.empty()) return nullptr;
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_osfans_trime_actions_ocr_OcrNative_nativeSetThresholds(JNIEnv* /*env*/,
                                                                jobject /*unused*/,
                                                                jfloat confidence,
                                                                jfloat det,
                                                                jfloat box_score) {
    if (confidence > 0.f && confidence < 1.f) gRecMinConfidence = confidence;
    if (det > 0.f && det < 1.f) gDetThreshold = det;
    if (box_score > 0.f && box_score < 1.f) gDetBoxScore = box_score;
    LOGI("thresholds: conf=%.2f det=%.2f box=%.2f", gRecMinConfidence, gDetThreshold, gDetBoxScore);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_osfans_trime_actions_ocr_OcrNative_nativeRelease(JNIEnv* /*env*/, jobject /*unused*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    releaseSessions();
    g_alphabet.clear();
    LOGI("ocr models released");
}
