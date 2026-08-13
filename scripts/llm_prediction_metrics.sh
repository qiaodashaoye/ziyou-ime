#!/bin/sh
# 字由输入法 LLM 预测验收指标采集（联想优化方案 §7.7 真机验证）
#
# 用法：
#   scripts/llm_prediction_metrics.sh            # 输出已缓冲的统计行
#   scripts/llm_prediction_metrics.sh --follow   # 实时跟踪（边打字边看）
#
# 统计行由 LlmPredictionCoordinator.flush() 在输入视图收起/服务销毁时输出
#（TAG=LlmPredictCoord，格式见 LlmPredictionStats.dumpAndReset）。
# 验收口径：hitRate≥40% · p50ms<1000 · chain 命中比≥60% · reqs 对照基线下降≥50%
set -e

PATTERN="LLM 预测统计"
if [ "$1" = "--follow" ]; then
  adb logcat -c
  echo "开始采集：请正常输入（收起键盘时输出一行统计）……"
  exec adb logcat -s LlmPredictCoord:I | grep --line-buffered "$PATTERN"
else
  adb logcat -d -s LlmPredictCoord:I | grep "$PATTERN" || echo "（暂无统计行：需先收起一次输入视图触发 flush）"
fi
