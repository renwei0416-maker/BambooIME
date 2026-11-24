#ifndef BAMBOO_NNLM_FILTER_H_
#define BAMBOO_NNLM_FILTER_H_

#include <memory>

#include <rime/common.h>
#include <rime/filter.h>

namespace bamboo {
namespace nnlm {

struct Model;

// 用神经网络语言模型给「整句候选」重新排序，其余候选保持原有顺序。
class NnlmFilter : public rime::Filter {
 public:
  explicit NnlmFilter(const rime::Ticket& ticket);

  rime::an<rime::Translation> Apply(rime::an<rime::Translation> translation,
                                   rime::CandidateList* candidates) override;

  bool AppliesToSegment(rime::Segment* segment) override { return true; }

 private:
  bool EnsureModel();

  double weight_ = 1.5;
  int rerank_ = 8;
  int min_syllables_ = 5;
  int max_chars_ = 64;
  std::shared_ptr<Model> model_;
  bool model_tried_ = false;
};

}  // namespace nnlm
}  // namespace bamboo

#endif  // BAMBOO_NNLM_FILTER_H_
