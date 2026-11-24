// 字级神经网络语言模型：给整句候选打分。
//
// 支持两种模型文件，结构与权重完全一致，只有容器不同：
//   - WTNN（自家训练导出，权重为 f16）
//   - 微信输入法 nlrgpt2_nnlm.bin（TFLite flatbuffer，权重为 f32）
//
// 结构：3 层 GPT-2，d=256，4 头，FF=1024，最长 512 位置，词表按模型文件自带。
#ifndef BAMBOO_NNLM_MODEL_H_
#define BAMBOO_NNLM_MODEL_H_

#include <string>
#include <vector>

namespace bamboo {
namespace nnlm {

struct Model;

// 读取模型文件；失败返回 nullptr（原因可通过 LastError() 取得）。
Model* Load(const std::string& path);
void Free(Model* model);

// 上一次 Load 失败的原因，供日志使用。
const std::string& LastError();

int VocabSize(const Model* model);

// 给定光标前的上下文和候选文本，返回该文本的对数似然之和（越大越通顺）。
// 上下文与文本都按 UTF-8 输入；模型不认识的字按 UNK 处理。
float ScoreText(const Model* model, const std::string& context,
                const std::string& text);

}  // namespace nnlm
}  // namespace bamboo

#endif  // BAMBOO_NNLM_MODEL_H_
