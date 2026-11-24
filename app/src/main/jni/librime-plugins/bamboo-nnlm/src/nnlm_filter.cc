#include "nnlm_filter.h"

#include <algorithm>
#include <string>
#include <vector>

#include <rime/candidate.h>
#include <rime/config.h>
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/translation.h>
#include <rime_api.h>

#include "nnlm_model.h"

namespace bamboo {
namespace nnlm {
namespace {

const char* kSentenceType = "sentence";

bool IsSentence(const rime::an<rime::Candidate>& cand) {
  return cand && cand->type() == kSentenceType;
}

// 模型搜索顺序：schema 指定的路径 → 用户目录下两个约定名。
std::vector<std::string> ModelCandidates(const std::string& from_config) {
  std::vector<std::string> out;
  if (!from_config.empty()) out.push_back(from_config);
  RimeApi* api = rime_get_api();
  if (api && api->get_user_data_dir) {
    const char* dir = api->get_user_data_dir();
    if (dir && *dir) {
      const std::string base(dir);
      out.push_back(base + "/nnlm/nnlm.bin");
      out.push_back(base + "/nnlm/nnlm_wechat.bin");
      out.push_back(base + "/nnlm.bin");
    }
  }
  return out;
}

}  // namespace

NnlmFilter::NnlmFilter(const rime::Ticket& ticket) : rime::Filter(ticket) {
  if (!engine_ || !engine_->schema()) return;
  rime::Config* config = engine_->schema()->config();
  if (!config) return;
  const std::string ns = ticket.name_space;
  config->GetDouble(ns + "/weight", &weight_);
  config->GetInt(ns + "/rerank", &rerank_);
  config->GetInt(ns + "/min_syllables", &min_syllables_);
  config->GetInt(ns + "/max_chars", &max_chars_);
  if (rerank_ < 1) rerank_ = 1;
  if (rerank_ > 32) rerank_ = 32;
  if (max_chars_ < 8) max_chars_ = 8;
}

bool NnlmFilter::EnsureModel() {
  if (model_) return true;
  if (model_tried_) return false;
  model_tried_ = true;
  std::string from_config;
  if (engine_ && engine_->schema()) {
    rime::Config* config = engine_->schema()->config();
    if (config) config->GetString(name_space_ + "/model", &from_config);
  }
  for (const std::string& path : ModelCandidates(from_config)) {
    Model* raw = Load(path);
    if (raw) {
      model_ = std::shared_ptr<Model>(raw, [](Model* p) { Free(p); });
      return true;
    }
  }
  return false;
}

rime::an<rime::Translation> NnlmFilter::Apply(
    rime::an<rime::Translation> translation, rime::CandidateList* candidates) {
  std::vector<rime::an<rime::Candidate>> all;
  if (translation) {
    while (translation->Next()) {
      all.push_back(translation->Peek());
    }
  }
  auto result = rime::New<rime::FifoTranslation>();
  if (all.empty()) return result;

  std::vector<size_t> sentence_at;
  for (size_t i = 0; i < all.size(); ++i) {
    if (IsSentence(all[i])) sentence_at.push_back(i);
  }
  if (sentence_at.empty() || !EnsureModel()) {
    for (const auto& cand : all) result->Append(cand);
    return result;
  }

  // 只给前 rerank_ 条整句打分，控制耗时。
  if (static_cast<int>(sentence_at.size()) > rerank_) {
    sentence_at.resize(static_cast<size_t>(rerank_));
  }
  std::vector<double> scores(sentence_at.size(), 0.0);
  bool any = false;
  for (size_t k = 0; k < sentence_at.size(); ++k) {
    const rime::an<rime::Candidate>& cand = all[sentence_at[k]];
    std::string text = cand->text();
    if (static_cast<int>(text.size()) > max_chars_ * 4) {
      text = text.substr(0, static_cast<size_t>(max_chars_) * 4);
    }
    // 上下文留空：候选本身的语言概率已能区分流畅度。
    scores[k] = ScoreText(model_.get(), std::string(), text);
    if (scores[k] != 0.0) any = true;
  }
  if (!any) {
    for (const auto& cand : all) result->Append(cand);
    return result;
  }

  const double best = *std::max_element(scores.begin(), scores.end());
  for (size_t k = 0; k < sentence_at.size(); ++k) {
    // 与最优句的相对差，夹在 [-20, 0]，避免压过词条太多。
    double delta = scores[k] - best;
    if (delta < -20.0) delta = -20.0;
    if (delta > 0.0) delta = 0.0;
    rime::an<rime::Candidate>& cand = all[sentence_at[k]];
    cand->set_quality(cand->quality() + weight_ * delta);
  }

  // 整句按新质量降序，非整句原位替换。
  std::vector<size_t> sentences = sentence_at;
  std::stable_sort(sentences.begin(), sentences.end(),
                   [&all](size_t a, size_t b) {
                     return all[a]->quality() > all[b]->quality();
                   });
  size_t next_sentence = 0;
  for (size_t i = 0; i < all.size(); ++i) {
    if (IsSentence(all[i])) {
      result->Append(all[sentences[next_sentence++]]);
    } else {
      result->Append(all[i]);
    }
  }
  return result;
}

}  // namespace nnlm
}  // namespace bamboo
