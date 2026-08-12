package com.ziyou.ime.core.prediction

/**
 * LLM 续写请求触发策略（纯函数，可穷举单测）。
 *
 * 输入热路径只做 O(1)/O(N) 内存判定，零 IO；决策规则按序短路
 * （见 docs/智能预测可行性方案.md §4.2）：
 * 1. 窗口为空 → Skip（无上下文可发）；
 * 2. 距上次请求不足 [MIN_INTERVAL_MS] → Skip（硬限流，防连打刷屏请求）；
 * 3. 上屏文本含句末标点 → Trigger（强信号：librime-predict 恰在此时清预测，
 *    正是 LLM 续写最有价值的时刻，跳过防抖立即请求）；
 * 4. 上屏文本 trim 后无汉字 → Skip（纯英文/数字/符号上屏不值得请求）；
 * 5. 其余 → Debounce([DEBOUNCE_MS])（防抖窗口内连续上屏只发最后一次）。
 */
class TriggerPolicy private constructor() {

    /** 触发决策结果 */
    sealed interface TriggerDecision {
        /** 立即请求（句末标点强信号） */
        object Trigger : TriggerDecision

        /** 延迟 [delayMs] 毫秒后请求（期间上下文变化则放弃） */
        data class Debounce(val delayMs: Long) : TriggerDecision

        /** 放弃本次触发 */
        object Skip : TriggerDecision
    }

    companion object {
        /** 最小请求间隔（ms）：硬限流下限 */
        const val MIN_INTERVAL_MS = 800L

        /** 防抖时长（ms）：commit 后延迟发射，窗口内再次上屏重置计时 */
        const val DEBOUNCE_MS = 300L

        /** 句末标点集合（中英文句号/叹号/问号 + 省略号） */
        private const val SENTENCE_END_PUNCT = "。！？!?…"

        /** 汉字判定：基本区 + 扩展 A 区（生僻字上屏不应被误判为无汉字） */
        private fun containsHan(text: String): Boolean =
            text.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }

        /**
         * 给定上屏文本、距上次请求时长与当前词窗口，产出触发决策。
         *
         * @param committedText 本次上屏文本（未过滤标点）
         * @param timeSinceLastAttemptMs 距上次请求发起的毫秒数（首次为极大值）
         * @param windowWords 当前词窗口序列（时间序）
         */
        fun decide(
            committedText: String,
            timeSinceLastAttemptMs: Long,
            windowWords: List<String>
        ): TriggerDecision {
            if (windowWords.isEmpty()) return TriggerDecision.Skip
            if (timeSinceLastAttemptMs < MIN_INTERVAL_MS) return TriggerDecision.Skip
            if (committedText.any { it in SENTENCE_END_PUNCT }) return TriggerDecision.Trigger
            if (!containsHan(committedText.trim())) return TriggerDecision.Skip
            return TriggerDecision.Debounce(DEBOUNCE_MS)
        }
    }
}
