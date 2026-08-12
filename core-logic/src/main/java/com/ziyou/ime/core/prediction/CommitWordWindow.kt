package com.ziyou.ime.core.prediction

/**
 * 最近上屏词环形缓冲（LLM 智能续写的唯一上下文数据源）。
 *
 * 设计约束（见 docs/智能预测可行性方案.md §4.6）：
 * - 纯内存、零持久化：词窗口不落盘、不进日志；
 * - 容量双上限：最多 [MAX_WORDS] 个词且总字符数 ≤ [MAX_TOTAL_CHARS]，
 *   超限从最旧淘汰，内存占用恒有界（O(N)，N ≤ 8）；
 * - 收录全部非空白上屏：标点/符号原样保留——标点携带句法信号，既是
 *   续写模型判断续新句/接旧句的上下文，也是 [AutoPunctPolicy] 自动补标点
 *   判定「前文是否已有标点」的唯一依据（独立标点提交不入窗会导致双逗号）；
 *   句末标点的触发语义由 [TriggerPolicy] 在上屏文本上判定，不经此处。
 *
 * 本类非线程安全：调用方（应用层协调器）保证只在主线程访问。
 */
class CommitWordWindow {

    companion object {
        /** 窗口最大词数（固定短窗口已足够，压缩/加权属于过度设计） */
        const val MAX_WORDS = 8

        /** 窗口总字符数上限（限制请求上下文体积，防单词超长撑爆预算） */
        const val MAX_TOTAL_CHARS = 64
    }

    /** 时间序队列：队首最旧、队尾最新 */
    private val words = ArrayDeque<String>()

    /** 当前总字符数（随增删同步维护，避免每次遍历时重算） */
    private var totalChars = 0

    /**
     * 追加一个上屏词。
     *
     * 仅空白（trim 后为空）静默忽略，标点/符号原样保留（携带句法信号，
     * 见类注释）；单个词超过总预算时也忽略——淘汰全部旧词仍放不下，收录无意义。
     * 超限淘汰从最旧开始，保证窗口恒满足双上限。
     */
    fun add(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        if (w.length > MAX_TOTAL_CHARS) return
        words.addLast(w)
        totalChars += w.length
        while (words.size > MAX_WORDS || totalChars > MAX_TOTAL_CHARS) {
            val removed = words.removeFirst()
            totalChars -= removed.length
        }
    }

    /** 当前窗口词序列（时间序：最旧在前，最新在后） */
    fun words(): List<String> = words.toList()

    /** 清空窗口（切换输入框 / 输入视图收起时调用，防跨会话上下文泄漏） */
    fun clear() {
        words.clear()
        totalChars = 0
    }
}
