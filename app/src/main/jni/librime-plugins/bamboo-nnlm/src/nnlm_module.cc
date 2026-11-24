#include <rime/common.h>
#include <rime/component.h>
#include <rime/registry.h>
#include <rime_api.h>

#include "nnlm_filter.h"

namespace {

void rime_bamboo_nnlm_initialize() {
  rime::Registry& r = rime::Registry::instance();
  r.Register("nnlm_filter", new rime::Component<bamboo::nnlm::NnlmFilter>);
}

void rime_bamboo_nnlm_finalize() {}

}  // namespace

RIME_REGISTER_MODULE(bamboo_nnlm)
