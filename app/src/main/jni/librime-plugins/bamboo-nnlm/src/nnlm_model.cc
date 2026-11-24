// 字级神经网络语言模型：模型装载与一次前向打分。
//
// 文本里的每个字先查模型自带的字表，取不到就用 UNK；再按位置加词向量，
// 逐层做「层归一化 → 多头注意力 → 残差 → 层归一化 → 前馈 → 残差」，
// 最后用词向量矩阵当输出层（权重共享）算 logits。
#include "nnlm_model.h"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <unordered_map>
#include <vector>

namespace bamboo {
namespace nnlm {
namespace {

constexpr int kD = 256;
constexpr int kHeads = 4;
constexpr int kHeadDim = 64;
constexpr int kFF = 1024;
constexpr int kMaxPos = 512;
constexpr int kLayers = 3;
constexpr uint32_t kUnk = 1;
constexpr uint32_t kBos = 2;
constexpr int kMaxContext = 24;

std::string g_last_error;

struct Layer {
  std::vector<float> ln1_g, ln1_b;
  std::vector<float> attn_w, attn_b;
  std::vector<float> proj_w, proj_b;
  std::vector<float> ln2_g, ln2_b;
  std::vector<float> fc_w, fc_b;
  std::vector<float> out_w, out_b;
};

struct Tensor {
  std::vector<int> shape;
  std::vector<float> data;
};

// ---- 权重读取工具 ---------------------------------------------------------

bool U32At(const std::vector<uint8_t>& d, size_t at, uint32_t* out) {
  if (at + 4 > d.size()) return false;
  *out = static_cast<uint32_t>(d[at]) | (static_cast<uint32_t>(d[at + 1]) << 8) |
         (static_cast<uint32_t>(d[at + 2]) << 16) |
         (static_cast<uint32_t>(d[at + 3]) << 24);
  return true;
}

bool U16At(const std::vector<uint8_t>& d, size_t at, uint16_t* out) {
  if (at + 2 > d.size()) return false;
  *out = static_cast<uint16_t>(d[at] | (d[at + 1] << 8));
  return true;
}

float F16ToF32(uint16_t h) {
  const uint32_t sign = static_cast<uint32_t>(h >> 15) << 31;
  uint32_t exp = (h >> 10) & 0x1f;
  uint32_t frac = h & 0x3ff;
  uint32_t bits = 0;
  if (exp == 0) {
    if (frac == 0) {
      bits = sign;
    } else {
      int e = 127 - 15 + 1;
      uint32_t f = frac;
      while ((f & 0x400) == 0) {
        f <<= 1;
        --e;
      }
      bits = sign | (static_cast<uint32_t>(e) << 23) | ((f & 0x3ff) << 13);
    }
  } else if (exp == 0x1f) {
    bits = sign | 0x7f800000u | (frac << 13);
  } else {
    bits = sign | ((exp + 127 - 15) << 23) | (frac << 13);
  }
  float v = 0;
  std::memcpy(&v, &bits, 4);
  return v;
}

// 最小的 FlatBuffers 读取：只为从 TFLite 模型里取 float32 常量张量。
class Fb {
 public:
  explicit Fb(const std::vector<uint8_t>& d) : d_(d) {}

  bool U32(size_t at, uint32_t* out) const { return U32At(d_, at, out); }

  size_t Deref(size_t pos) const {
    uint32_t off = 0;
    if (!U32At(d_, pos, &off)) return 0;
    return pos + off;
  }

  // 取表字段位置；不存在返回 kNone。
  bool Field(size_t table, int id, size_t* out) const {
    const size_t vt = table - static_cast<size_t>(static_cast<int32_t>(
                                  static_cast<uint32_t>(ReadU32Raw(table))));
    uint16_t vt_size = 0, obj_size = 0;
    if (!U16At(d_, vt, &vt_size) || !U16At(d_, vt + 2, &obj_size)) {
      return false;
    }
    const size_t idx = 4 + 2 * static_cast<size_t>(id);
    if (idx >= vt_size) return false;
    uint16_t off = 0;
    if (!U16At(d_, vt + idx, &off) || off == 0) return false;
    *out = table + off;
    return true;
  }

  // 取向量：(数据起点, 元素个数)。
  bool Vector(size_t table, int id, size_t* at, size_t* n) const {
    size_t field = 0;
    if (!Field(table, id, &field)) return false;
    const size_t vec = Deref(field);
    uint32_t len = 0;
    if (!U32At(d_, vec, &len)) return false;
    *at = vec + 4;
    *n = len;
    return true;
  }

  bool TableField(size_t table, int id, size_t* out) const {
    size_t field = 0;
    if (!Field(table, id, &field)) return false;
    *out = Deref(field);
    return true;
  }

 private:
  uint32_t ReadU32Raw(size_t pos) const {
    uint32_t v = 0;
    U32At(d_, pos, &v);
    return v;
  }
  const std::vector<uint8_t>& d_;
};

bool ReadTfliteTensors(const std::vector<uint8_t>& d,
                       std::map<std::string, Tensor>* out) {
  Fb fb(d);
  const size_t root = fb.Deref(0);
  size_t subgraphs = 0, n_sub = 0, buffers = 0, n_buf = 0;
  if (!fb.Vector(root, 2, &subgraphs, &n_sub)) {
    g_last_error = "TFLite 里没有 subgraphs";
    return false;
  }
  if (!fb.Vector(root, 4, &buffers, &n_buf)) {
    g_last_error = "TFLite 里没有 buffers";
    return false;
  }
  if (n_sub == 0) {
    g_last_error = "subgraphs 为空";
    return false;
  }
  const size_t sub = fb.Deref(subgraphs);
  size_t tensors = 0, n_t = 0;
  if (!fb.Vector(sub, 0, &tensors, &n_t)) {
    g_last_error = "subgraph 里没有 tensors";
    return false;
  }
  for (size_t i = 0; i < n_t; ++i) {
    const size_t t = fb.Deref(tensors + 4 * i);
    size_t type_at = 0;
    uint8_t type = 0;
    if (fb.Field(t, 1, &type_at) && type_at < d.size()) type = d[type_at];
    if (type != 0) continue;  // 只取 FLOAT32
    size_t buf_at = 0;
    uint32_t buf_idx = 0;
    if (fb.Field(t, 2, &buf_at)) {
      fb.U32(buf_at, &buf_idx);
    }
    if (buf_idx == 0 || buf_idx >= n_buf) continue;
    const size_t buffer = fb.Deref(buffers + 4 * buf_idx);
    size_t data_at = 0, len = 0;
    if (!fb.Vector(buffer, 0, &data_at, &len)) continue;
    if (len == 0 || len % 4 != 0 || data_at + len > d.size()) continue;
    size_t name_at = 0;
    if (!fb.TableField(t, 3, &name_at)) continue;
    uint32_t name_len = 0;
    if (!fb.U32(name_at, &name_len) || name_at + 4 + name_len > d.size()) {
      continue;
    }
    const std::string name(reinterpret_cast<const char*>(d.data() + name_at + 4),
                           name_len);
    Tensor tensor;
    size_t shape_at = 0, shape_n = 0;
    if (fb.Vector(t, 0, &shape_at, &shape_n)) {
      for (size_t j = 0; j < shape_n; ++j) {
        uint32_t dim = 0;
        if (!fb.U32(shape_at + 4 * j, &dim)) break;
        tensor.shape.push_back(static_cast<int>(dim));
      }
    }
    tensor.data.resize(len / 4);
    for (size_t j = 0; j < tensor.data.size(); ++j) {
      const uint8_t* b = d.data() + data_at + 4 * j;
      uint32_t bits = static_cast<uint32_t>(b[0]) |
                      (static_cast<uint32_t>(b[1]) << 8) |
                      (static_cast<uint32_t>(b[2]) << 16) |
                      (static_cast<uint32_t>(b[3]) << 24);
      std::memcpy(&tensor.data[j], &bits, 4);
    }
    (*out)[name] = std::move(tensor);
  }
  return !out->empty();
}

// 微信模型的字表：桶式哈希表，值里直接带码位与编号。
bool ReadWechatVocab(const std::vector<uint8_t>& d, size_t from,
                     std::unordered_map<uint32_t, uint32_t>* vocab) {
  size_t at = from;
  size_t table = 0;
  bool found = false;
  while (at + 12 <= d.size() && at <= from + 4096) {
    uint32_t flags = 0, buckets = 0;
    U32At(d, at, &flags);
    U32At(d, at + 4, &buckets);
    if (flags == 0x80000001u && buckets >= 1000 && buckets < 100000) {
      table = at;
      found = true;
      break;
    }
    ++at;
  }
  if (!found) {
    g_last_error = "字表没有找到";
    return false;
  }
  uint32_t buckets = 0;
  U32At(d, table + 4, &buckets);
  for (uint32_t b = 0; b < buckets; ++b) {
    uint32_t off = 0;
    if (!U32At(d, table + 8 + 4 * static_cast<size_t>(b), &off)) continue;
    const size_t base = table + off;
    uint32_t n = 0;
    if (!U32At(d, base, &n)) continue;
    const size_t ends = base + 4 + 4 * static_cast<size_t>(n);
    const size_t values = ends + 2 * static_cast<size_t>(n);
    size_t prev = 0;
    for (uint32_t i = 0; i < n; ++i) {
      uint16_t end = 0;
      if (!U16At(d, ends + 2 * static_cast<size_t>(i), &end)) break;
      if (values + end > d.size() || end < prev) break;
      const uint8_t* v = d.data() + values + prev;
      const size_t len = end - prev;
      prev = end;
      if (len == 5 && v[0] == 0x04 && v[1] == 0x01) {
        const uint32_t cp = static_cast<uint32_t>(v[2] | (v[3] << 8));
        (*vocab)[cp] = v[4];
      } else if (len == 6 && v[0] == 0x14 && v[1] == 0x01) {
        const uint32_t cp = static_cast<uint32_t>(v[2] | (v[3] << 8));
        (*vocab)[cp] = static_cast<uint32_t>(v[4] | (v[5] << 8));
      }
    }
  }
  if (vocab->size() < 1000) {
    g_last_error = "字表读出的字太少";
    return false;
  }
  return true;
}

}  // namespace

struct Model {
  std::unordered_map<uint32_t, uint32_t> vocab;
  std::vector<float> wte, wpe;
  std::vector<Layer> layers;
  std::vector<float> lnf_g, lnf_b;
  int vocab_size = 0;
  std::string meta;
};

namespace {

bool CheckSize(const std::vector<float>& v, size_t expect,
               const char* what) {
  if (v.size() != expect) {
    g_last_error = std::string("权重形状不对：") + what;
    return false;
  }
  return true;
}

// WTNN：自家训练导出的格式，权重是 f16。
bool LoadWtnn(const std::vector<uint8_t>& d, Model* m) {
  uint32_t version = 0, meta_len = 0;
  if (!U32At(d, 4, &version) || version != 1) {
    g_last_error = "WTNN 版本不认识";
    return false;
  }
  if (!U32At(d, 8, &meta_len) || 12 + meta_len > d.size()) {
    g_last_error = "WTNN 说明段越界";
    return false;
  }
  m->meta.assign(reinterpret_cast<const char*>(d.data() + 12), meta_len);
  size_t at = 12 + static_cast<size_t>(meta_len);
  uint32_t vocab_size = 0, n = 0;
  if (!U32At(d, at, &vocab_size) || !U32At(d, at + 4, &n)) {
    g_last_error = "WTNN 字表头越界";
    return false;
  }
  if (vocab_size < 1000 || vocab_size > 65535 || n > vocab_size) {
    g_last_error = "WTNN 词表大小不对";
    return false;
  }
  at += 8;
  for (uint32_t i = 0; i < n; ++i) {
    uint32_t cp = 0, id = 0;
    if (!U32At(d, at, &cp) || !U32At(d, at + 4, &id)) {
      g_last_error = "WTNN 字表越界";
      return false;
    }
    at += 8;
    if (id >= vocab_size) {
      g_last_error = "WTNN 字表编号越界";
      return false;
    }
    m->vocab[cp] = id;
  }
  auto take = [&](size_t len, std::vector<float>* out) {
    if (at + 2 * len > d.size()) {
      g_last_error = "WTNN 权重不完整";
      return false;
    }
    out->resize(len);
    for (size_t i = 0; i < len; ++i) {
      uint16_t h = 0;
      U16At(d, at + 2 * i, &h);
      (*out)[i] = F16ToF32(h);
    }
    at += 2 * len;
    return true;
  };
  m->vocab_size = static_cast<int>(vocab_size);
  if (!take(static_cast<size_t>(vocab_size) * kD, &m->wte)) return false;
  if (!take(static_cast<size_t>(kMaxPos) * kD, &m->wpe)) return false;
  m->layers.resize(kLayers);
  for (int l = 0; l < kLayers; ++l) {
    Layer& L = m->layers[l];
    if (!take(kD, &L.ln1_g) || !take(kD, &L.ln1_b) ||
        !take(static_cast<size_t>(3) * kD * kD, &L.attn_w) ||
        !take(static_cast<size_t>(3) * kD, &L.attn_b) ||
        !take(static_cast<size_t>(kD) * kD, &L.proj_w) || !take(kD, &L.proj_b) ||
        !take(kD, &L.ln2_g) || !take(kD, &L.ln2_b) ||
        !take(static_cast<size_t>(kFF) * kD, &L.fc_w) || !take(kFF, &L.fc_b) ||
        !take(static_cast<size_t>(kD) * kFF, &L.out_w) || !take(kD, &L.out_b)) {
      return false;
    }
  }
  if (!take(kD, &m->lnf_g) || !take(kD, &m->lnf_b)) return false;
  return true;
}

// 微信输入法：TFLite flatbuffer + 桶式字表。
bool LoadWechat(const std::vector<uint8_t>& d, Model* m) {
  if (d.size() < 16) {
    g_last_error = "文件太短";
    return false;
  }
  uint32_t tfl_len = 0;
  if (!U32At(d, 1, &tfl_len) || 5 + tfl_len > d.size()) {
    g_last_error = "TFLite 段越界";
    return false;
  }
  std::vector<uint8_t> tfl(d.begin() + 5, d.begin() + 5 + tfl_len);
  std::map<std::string, Tensor> tensors;
  if (!ReadTfliteTensors(tfl, &tensors)) return false;
  if (!ReadWechatVocab(d, 5 + tfl_len, &m->vocab)) return false;

  const std::string prefix = "tfgpt2lm_head_custom_model/transformer/";
  auto find_one = [&](const std::vector<std::string>& parts,
                      Tensor* out) -> bool {
    std::string hit;
    int hits = 0;
    for (const auto& kv : tensors) {
      bool ok = true;
      for (const auto& p : parts) {
        if (kv.first.find(p) == std::string::npos) {
          ok = false;
          break;
        }
      }
      if (ok) {
        hit = kv.first;
        ++hits;
      }
    }
    if (hits != 1) {
      g_last_error = "权重命中数不对";
      return false;
    }
    *out = tensors[hit];
    return true;
  };
  Tensor wte;
  if (!find_one({prefix + "wte/Gather/resource"}, &wte)) return false;
  if (wte.data.size() % kD != 0) {
    g_last_error = "wte 形状不对";
    return false;
  }
  m->vocab_size = static_cast<int>(wte.data.size() / kD);
  if (m->vocab_size < 1000) {
    g_last_error = "词表太小";
    return false;
  }
  for (const auto& kv : m->vocab) {
    if (static_cast<int>(kv.second) >= m->vocab_size) {
      g_last_error = "字表和模型对不上";
      return false;
    }
  }
  m->wte = std::move(wte.data);
  Tensor wpe;
  if (!find_one({prefix + "Gather/resource"}, &wpe)) return false;
  if (!CheckSize(wpe.data, static_cast<size_t>(kMaxPos) * kD, "wpe")) {
    return false;
  }
  m->wpe = std::move(wpe.data);
  auto take = [&](const std::vector<std::string>& parts, size_t expect,
                  std::vector<float>* out) -> bool {
    Tensor t;
    if (!find_one(parts, &t)) return false;
    if (!CheckSize(t.data, expect, parts.back().c_str())) return false;
    *out = std::move(t.data);
    return true;
  };
  m->layers.resize(kLayers);
  for (int l = 0; l < kLayers; ++l) {
    const std::string h = "h_._" + std::to_string(l) + "/";
    Layer& L = m->layers[l];
    if (!take({h, "ln_1/batchnorm/mul/"}, kD, &L.ln1_g) ||
        !take({h, "ln_1/batchnorm/ReadVariableOp/"}, kD, &L.ln1_b) ||
        !take({h, "attn/c_attn/MatMul"}, static_cast<size_t>(3) * kD * kD,
              &L.attn_w) ||
        !take({h, "attn/c_attn/add/"}, static_cast<size_t>(3) * kD, &L.attn_b) ||
        !take({h, "attn/c_proj/MatMul"}, static_cast<size_t>(kD) * kD,
              &L.proj_w) ||
        !take({h, "attn/c_proj/add/"}, kD, &L.proj_b) ||
        !take({h, "ln_2/batchnorm/mul/"}, kD, &L.ln2_g) ||
        !take({h, "ln_2/batchnorm/ReadVariableOp/"}, kD, &L.ln2_b) ||
        !take({h, "mlp/c_fc/MatMul"}, static_cast<size_t>(kFF) * kD, &L.fc_w) ||
        !take({h, "mlp/c_fc/add/"}, kFF, &L.fc_b) ||
        !take({h, "mlp/c_proj/MatMul"}, static_cast<size_t>(kD) * kFF,
              &L.out_w) ||
        !take({h, "mlp/c_proj/add/"}, kD, &L.out_b)) {
      return false;
    }
  }
  if (!take({prefix + "ln_f/batchnorm/mul/"}, kD, &m->lnf_g) ||
      !take({prefix + "ln_f/batchnorm/ReadVariableOp/"}, kD, &m->lnf_b)) {
    return false;
  }
  return true;
}

// ---- 前向 ----------------------------------------------------------------

inline void LayerNorm(const float* x, const float* g, const float* b, float* out,
                      int n) {
  float sum = 0;
  for (int i = 0; i < n; ++i) sum += x[i];
  const float mean = sum / static_cast<float>(n);
  float var = 0;
  for (int i = 0; i < n; ++i) {
    const float d = x[i] - mean;
    var += d * d;
  }
  var /= static_cast<float>(n);
  const float inv = 1.0f / std::sqrt(var + 1e-5f);
  for (int i = 0; i < n; ++i) out[i] = (x[i] - mean) * inv * g[i] + b[i];
}

inline float Gelu(float x) {
  constexpr float kC = 0.7978846f;
  return 0.5f * x * (1.0f + std::tanh(kC * (x + 0.044715f * x * x * x)));
}

struct State {
  std::vector<float> k, v;  // [kLayers][kMaxPos][kHeads][kHeadDim]
  std::vector<float> x;     // 当前隐藏状态（已做最终层归一化）
  int n = 0;
  State() {
    const size_t per_layer =
        static_cast<size_t>(kMaxPos) * kHeads * kHeadDim;
    k.assign(per_layer * kLayers, 0.0f);
    v.assign(per_layer * kLayers, 0.0f);
    x.assign(kD, 0.0f);
  }
};

void Step(const Model& m, State* s, uint32_t tok) {
  std::vector<float> x(kD), h(kD), qkv(3 * kD), attn(kD), tmp(kD),
      mff(kFF);
  const int pos = s->n;
  const float* emb = &m.wte[static_cast<size_t>(tok) * kD];
  const float* pos_emb = &m.wpe[static_cast<size_t>(pos) * kD];
  for (int i = 0; i < kD; ++i) x[i] = emb[i] + pos_emb[i];
  const float scale = 1.0f / std::sqrt(static_cast<float>(kHeadDim));
  for (int l = 0; l < kLayers; ++l) {
    const Layer& L = m.layers[l];
    LayerNorm(x.data(), L.ln1_g.data(), L.ln1_b.data(), h.data(), kD);
    // qkv = attn_w * h + attn_b
    for (int r = 0; r < 3 * kD; ++r) {
      const float* row = &L.attn_w[static_cast<size_t>(r) * kD];
      float acc = L.attn_b[r];
      for (int i = 0; i < kD; ++i) acc += row[i] * h[i];
      qkv[r] = acc;
    }
    float* kbase = &s->k[(static_cast<size_t>(l) * kMaxPos + pos) * kHeads *
                         kHeadDim];
    float* vbase = &s->v[(static_cast<size_t>(l) * kMaxPos + pos) * kHeads *
                         kHeadDim];
    for (int hh = 0; hh < kHeads; ++hh) {
      std::memcpy(kbase + hh * kHeadDim, &qkv[kD + hh * kHeadDim],
                  sizeof(float) * kHeadDim);
      std::memcpy(vbase + hh * kHeadDim, &qkv[2 * kD + hh * kHeadDim],
                  sizeof(float) * kHeadDim);
    }
    for (int hh = 0; hh < kHeads; ++hh) {
      const float* q = &qkv[hh * kHeadDim];
      std::vector<float> scores(static_cast<size_t>(pos) + 1);
      float max_score = -1e30f;
      for (int j = 0; j <= pos; ++j) {
        const float* kk =
            &s->k[(static_cast<size_t>(l) * kMaxPos + j) * kHeads * kHeadDim +
                  hh * kHeadDim];
        float acc = 0;
        for (int d = 0; d < kHeadDim; ++d) acc += q[d] * kk[d];
        scores[j] = acc * scale;
        if (scores[j] > max_score) max_score = scores[j];
      }
      float sum = 0;
      for (int j = 0; j <= pos; ++j) {
        scores[j] = std::exp(scores[j] - max_score);
        sum += scores[j];
      }
      const float inv = 1.0f / sum;
      for (int d = 0; d < kHeadDim; ++d) attn[hh * kHeadDim + d] = 0;
      for (int j = 0; j <= pos; ++j) {
        const float w = scores[j] * inv;
        const float* vv =
            &s->v[(static_cast<size_t>(l) * kMaxPos + j) * kHeads * kHeadDim +
                  hh * kHeadDim];
        for (int d = 0; d < kHeadDim; ++d) attn[hh * kHeadDim + d] += w * vv[d];
      }
    }
    for (int r = 0; r < kD; ++r) {
      const float* row = &L.proj_w[static_cast<size_t>(r) * kD];
      float acc = L.proj_b[r];
      for (int i = 0; i < kD; ++i) acc += row[i] * attn[i];
      x[r] += acc;
    }
    LayerNorm(x.data(), L.ln2_g.data(), L.ln2_b.data(), h.data(), kD);
    for (int r = 0; r < kFF; ++r) {
      const float* row = &L.fc_w[static_cast<size_t>(r) * kD];
      float acc = L.fc_b[r];
      for (int i = 0; i < kD; ++i) acc += row[i] * h[i];
      mff[r] = Gelu(acc);
    }
    for (int r = 0; r < kD; ++r) {
      const float* row = &L.out_w[static_cast<size_t>(r) * kFF];
      float acc = L.out_b[r];
      for (int i = 0; i < kFF; ++i) acc += row[i] * mff[i];
      x[r] += acc;
    }
  }
  LayerNorm(x.data(), m.lnf_g.data(), m.lnf_b.data(), s->x.data(), kD);
  s->n = pos + 1;
}

// 给定状态，返回下一个字为 tok 的对数概率。
float NextLogProb(const Model& m, const State* s, uint32_t tok) {
  const int idx = static_cast<int>(tok) < m.vocab_size
                      ? static_cast<int>(tok)
                      : m.vocab_size - 1;
  float max_logit = -1e30f;
  std::vector<float> logits(static_cast<size_t>(m.vocab_size));
  for (int t = 0; t < m.vocab_size; ++t) {
    const float* row = &m.wte[static_cast<size_t>(t) * kD];
    float acc = 0;
    for (int i = 0; i < kD; ++i) acc += row[i] * s->x[i];
    logits[static_cast<size_t>(t)] = acc;
    if (acc > max_logit) max_logit = acc;
  }
  double sum = 0;
  for (int t = 0; t < m.vocab_size; ++t) {
    sum += std::exp(static_cast<double>(logits[static_cast<size_t>(t)] -
                                        max_logit));
  }
  const double lse = std::log(sum) + max_logit;
  return static_cast<float>(logits[static_cast<size_t>(idx)] - lse);
}

std::vector<uint32_t> ToTokens(const Model& m, const std::string& utf8) {
  std::vector<uint32_t> out;
  size_t i = 0;
  while (i < utf8.size()) {
    const unsigned char c = static_cast<unsigned char>(utf8[i]);
    uint32_t cp = 0;
    int len = 1;
    if (c < 0x80) {
      cp = c;
    } else if ((c & 0xE0) == 0xC0 && i + 1 < utf8.size()) {
      cp = ((c & 0x1Fu) << 6) |
           (static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu);
      len = 2;
    } else if ((c & 0xF0) == 0xE0 && i + 2 < utf8.size()) {
      cp = ((c & 0x0Fu) << 12) |
           ((static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu) << 6) |
           (static_cast<unsigned char>(utf8[i + 2]) & 0x3Fu);
      len = 3;
    } else if ((c & 0xF8) == 0xF0 && i + 3 < utf8.size()) {
      cp = ((c & 0x07u) << 18) |
           ((static_cast<unsigned char>(utf8[i + 1]) & 0x3Fu) << 12) |
           ((static_cast<unsigned char>(utf8[i + 2]) & 0x3Fu) << 6) |
           (static_cast<unsigned char>(utf8[i + 3]) & 0x3Fu);
      len = 4;
    } else {
      cp = '?';
    }
    auto it = m.vocab.find(cp);
    out.push_back(it == m.vocab.end() ? kUnk : it->second);
    i += static_cast<size_t>(len);
  }
  return out;
}

}  // namespace

Model* Load(const std::string& path) {
  g_last_error.clear();
  std::ifstream in(path, std::ios::binary);
  if (!in) {
    g_last_error = "模型文件打不开";
    return nullptr;
  }
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(in)),
                            std::istreambuf_iterator<char>());
  if (data.size() < 16) {
    g_last_error = "模型文件太短";
    return nullptr;
  }
  auto model = std::make_unique<Model>();
  bool ok = false;
  if (data.size() >= 4 && std::memcmp(data.data(), "WTNN", 4) == 0) {
    ok = LoadWtnn(data, model.get());
  } else if (data[0] == 1) {
    ok = LoadWechat(data, model.get());
  } else {
    g_last_error = "模型文件头不认识";
  }
  if (!ok) return nullptr;
  return model.release();
}

void Free(Model* model) { delete model; }

const std::string& LastError() { return g_last_error; }

int VocabSize(const Model* model) { return model ? model->vocab_size : 0; }

float ScoreText(const Model* model, const std::string& context,
                const std::string& text) {
  if (!model || text.empty()) return 0.0f;
  auto ctx_tokens = ToTokens(*model, context);
  if (static_cast<int>(ctx_tokens.size()) > kMaxContext) {
    ctx_tokens.erase(ctx_tokens.begin(),
                     ctx_tokens.end() - kMaxContext);
  }
  auto text_tokens = ToTokens(*model, text);
  std::vector<uint32_t> all = ctx_tokens;
  all.insert(all.end(), text_tokens.begin(), text_tokens.end());
  if (all.size() < 2 || all.size() > static_cast<size_t>(kMaxPos)) return 0.0f;

  State s;
  Step(*model, &s, all[0]);
  float sum = 0.0f;
  const size_t ctx_len = ctx_tokens.size();
  for (size_t i = 1; i < all.size(); ++i) {
    // 只累加候选文本本身的对数概率，上下文只作为条件。
    if (i >= ctx_len) sum += NextLogProb(*model, &s, all[i]);
    Step(*model, &s, all[i]);
  }
  return sum;
}

}  // namespace nnlm
}  // namespace bamboo
