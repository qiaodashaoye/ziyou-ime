//
// Copyright (c) ziyou-ime
//
// predict.db 召回率查询工具（宿主机构建，用于联想优化方案 §7.7 的 A/B 验收；
// BUILD_TOOLS 关闭时不进 Android 产物）。
//
// 用法：
//   query_predict <db_path> [--fallback] < queries.txt
//
// 从 stdin 逐行读取查询文本（模拟上屏词/句），输出命中率统计。
// --fallback 开启时模拟 predict_engine.cc 的最长后缀回退（按 UTF-8 字截前缀，
// 最短保留 2 字），用于评估「自定义 db + 运行期回退补丁」组合的召回上限；
// 不带该参数时为纯精确匹配（对应当前线上行为与构建期双键的效果）。
//
// A/B 验收口径（docs/联想功能优化调研与方案.md §7.7）：
//   基线   = 官方 assets/predict.db        纯精确匹配
//   候选 A = 自建 db                       纯精确匹配（双键承担召回）
//   候选 B = 自建 db                       + --fallback（运行期补丁生效后）
//   验收线：候选命中率相对基线提升 ≥50%（相对提升：(A-B0)/B0）

#include <rime/common.h>
#include <iostream>
#include <string>

#include "predict_db.h"

using namespace rime;

// 与 src/predict_engine.cc NextUtf8CharOffset 保持一致的 UTF-8 步进
static size_t NextUtf8CharOffset(const string& s, size_t pos) {
  size_t i = pos + 1;
  while (i < s.size() && (static_cast<unsigned char>(s[i]) & 0xC0) == 0x80)
    ++i;
  return i;
}

// 精确匹配；开启 fallback 时追加最长后缀回退（与引擎补丁同语义）
static bool LookupWithFallback(PredictDb& db,
                               const string& query,
                               bool fallback) {
  if (db.Lookup(query))
    return true;
  if (!fallback)
    return false;
  vector<size_t> boundaries;
  for (size_t i = 0; i < query.size();) {
    boundaries.push_back(i);
    i = NextUtf8CharOffset(query, i);
  }
  for (size_t b = 1; b + 2 <= boundaries.size(); ++b) {
    if (db.Lookup(query.substr(boundaries[b])))
      return true;
  }
  return false;
}

int main(int argc, char* argv[]) {
  if (argc < 2) {
    std::cerr << "usage: query_predict <db_path> [--fallback] < queries.txt"
              << std::endl;
    return 1;
  }
  string db_path = argv[1];
  bool fallback = false;
  for (int i = 2; i < argc; ++i) {
    if (string(argv[i]) == "--fallback")
      fallback = true;
  }

  PredictDb db(db_path);
  if (!db.Load()) {
    std::cerr << "failed to load predict db: " << db_path << std::endl;
    return 1;
  }

  long total = 0;
  long hits = 0;
  string line;
  while (std::getline(std::cin, line)) {
    // 剥离行尾 \r 与首尾空白；跳过空行与 # 注释
    while (!line.empty() && (line.back() == '\r' || line.back() == ' '))
      line.pop_back();
    size_t start = line.find_first_not_of(' ');
    if (start == string::npos)
      continue;
    line = line.substr(start);
    if (line.empty() || line[0] == '#')
      continue;
    ++total;
    if (LookupWithFallback(db, line, fallback))
      ++hits;
  }

  const double rate = total > 0 ? 100.0 * hits / total : 0.0;
  printf("db=%s fallback=%s total=%ld hits=%ld rate=%.1f%%\n",
         db_path.c_str(), fallback ? "on" : "off", total, hits, rate);
  return 0;
}
