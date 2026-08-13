#include "predict_engine.h"

#include "predict_db.h"
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/key_event.h>
#include <rime/menu.h>
#include <rime/segmentation.h>
#include <rime/service.h>
#include <rime/ticket.h>
#include <rime/translation.h>
#include <rime/schema.h>
#include <rime/dict/db_pool_impl.h>

namespace rime {

static const ResourceType kPredictDbResourceType = {"predict_db", "", ""};

// 字由输入法定制（docs/联想功能优化调研与方案.md §4.2 运行期方案）：
// 返回字符串中紧跟 pos 处字符之后的下一个 UTF-8 字符起始偏移。
// predict.db 键以汉字为主（UTF-8 每字 3 字节），后缀回退需按字而非按字节截断。
static size_t NextUtf8CharOffset(const string& s, size_t pos) {
  size_t i = pos + 1;
  while (i < s.size() && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80)
    ++i;
  return i;
}

PredictEngine::PredictEngine(an<PredictDb> db,
                             int max_iterations,
                             int max_candidates)
    : db_(db),
      max_iterations_(max_iterations),
      max_candidates_(max_candidates) {}

PredictEngine::~PredictEngine() {}

bool PredictEngine::Predict(Context* ctx, const string& context_query) {
  DLOG(INFO) << "PredictEngine::Predict [" << context_query << "]";
  if (const auto* candidates = db_->Lookup(context_query)) {
    query_ = context_query;
    candidates_ = candidates;
    return true;
  }
  // 字由定制：最长后缀回退。predict.db 仅支持精确匹配，整句/长词上屏
  // 必然 miss；按字逐步截前缀（保留最长后缀）重试，取首个命中，使
  // 「今天天气真好」这类上屏仍能以「真好」等尾词命中预测。
  // 回退最短保留 2 字：单字后缀分布太平缓，泛化误召回大于收益
  //（与调研结论「准确优先于丰富」一致）。本段为可选增强，未重编
  // 预编译库前不生效；重编命令见 AGENTS.md「常用命令」。
  vector<size_t> boundaries;
  for (size_t i = 0; i < context_query.size();) {
    boundaries.push_back(i);
    i = NextUtf8CharOffset(context_query, i);
  }
  // 后缀自长而短：boundaries[b] 起始的后缀含 N-b 个字，要求 N-b >= 2
  for (size_t b = 1; b + 2 <= boundaries.size(); ++b) {
    const string suffix = context_query.substr(boundaries[b]);
    if (const auto* candidates = db_->Lookup(suffix)) {
      DLOG(INFO) << "PredictEngine::Predict suffix hit [" << suffix << "]";
      query_ = suffix;
      candidates_ = candidates;
      return true;
    }
  }
  Clear();
  return false;
}

void PredictEngine::Clear() {
  DLOG(INFO) << "PredictEngine::Clear";
  query_.clear();
  candidates_ = nullptr;
}

void PredictEngine::CreatePredictSegment(Context* ctx) const {
  DLOG(INFO) << "PredictEngine::CreatePredictSegment";
  int end = int(ctx->input().length());
  Segment segment(end, end);
  segment.tags.insert("prediction");
  segment.tags.insert("placeholder");
  ctx->composition().AddSegment(segment);
  ctx->composition().back().tags.erase("raw");
  DLOG(INFO) << "segments: " << ctx->composition();
}

an<Translation> PredictEngine::Translate(const Segment& segment) const {
  DLOG(INFO) << "PredictEngine::Translate";
  auto translation = New<FifoTranslation>();
  size_t end = segment.end;
  int i = 0;
  for (auto* it = candidates_->begin(); it != candidates_->end(); ++it) {
    translation->Append(
        New<SimpleCandidate>("prediction", end, end, db_->GetEntryText(*it)));
    i++;
    if (max_candidates_ > 0 && i >= max_candidates_)
      break;
  }
  return translation;
}

PredictEngineComponent::PredictEngineComponent()
    : db_pool_(the<ResourceResolver>(
          Service::instance().CreateResourceResolver(kPredictDbResourceType))) {
}

PredictEngineComponent::~PredictEngineComponent() {}

PredictEngine* PredictEngineComponent::Create(const Ticket& ticket) {
  string db_name = "predict.db";
  int max_candidates = 0;
  int max_iterations = 0;
  if (auto* schema = ticket.schema) {
    auto* config = schema->config();
    if (config->GetString("predictor/db", &db_name)) {
      LOG(INFO) << "custom predictor/db: " << db_name;
    }
    if (!config->GetInt("predictor/max_candidates", &max_candidates)) {
      LOG(INFO) << "predictor/max_candidates is not set in schema";
    }
    if (!config->GetInt("predictor/max_iterations", &max_iterations)) {
      LOG(INFO) << "predictor/max_iterations is not set in schema";
    }
  }
  if (auto db = db_pool_.GetDb(db_name)) {
    if (db->IsOpen() || db->Load()) {
      return new PredictEngine(db, max_iterations, max_candidates);
    } else {
      LOG(ERROR) << "failed to load predict db: " << db_name;
    }
  }
  return nullptr;
}

an<PredictEngine> PredictEngineComponent::GetInstance(const Ticket& ticket) {
  if (Schema* schema = ticket.schema) {
    auto found = predict_engine_by_schema_id.find(schema->schema_id());
    if (found != predict_engine_by_schema_id.end()) {
      if (auto instance = found->second.lock()) {
        return instance;
      }
    }
    an<PredictEngine> new_instance{Create(ticket)};
    if (new_instance) {
      predict_engine_by_schema_id[schema->schema_id()] = new_instance;
      return new_instance;
    }
  }
  return nullptr;
}

}  // namespace rime
