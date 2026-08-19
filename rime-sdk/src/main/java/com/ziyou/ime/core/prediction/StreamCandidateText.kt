package com.ziyou.ime.core.prediction

/**
 * 流式续写候选的增量解析器（纯逻辑，可穷举单测）。
 *
 * LLM 流式（SSE）响应以 token 粒度到达，文本可能在任意位置被截断；
 * 本类维护行缓冲，把增量片段解析为「逐条完成」的候选：
 * 仅按换行切分（智能预测可行性方案 §4.4 纪律——不得按逗号/顿号切分，
 * 否则「疑是地上霜，低头思故乡。」会被拆成无标点碎片）。
 *
 * 单行清洗规则与非流式路径完全一致（同一管线，防两路行为漂移）：
 * 剥离列表序号前缀（模型未必遵守无编号约束）→ 剥离**前导标点**
 * （前文与续写词之间的标点由 [AutoPunctPolicy] 统一负责，候选自带
 * 前导逗号遇前文已有标点会造成「，，」叠加；句尾标点有实义不剥离）
 * → trim → 截断到 [MAX_CANDIDATE_CHARS]。
 *
 * 本类有状态（行缓冲 + 已发射条数），非线程安全，供单个请求生命周期独享。
 */
class StreamCandidateText {

    companion object {
        /** 单次请求候选条数上限（与非流式路径一致） */
        const val MAX_CANDIDATES = 5

        /** 单条候选字符数上限（超出截断，防异常输出撑爆候选栏） */
        const val MAX_CANDIDATE_CHARS = 20

        /** 列表序号前缀（数字/汉字序数 + 常见分隔符，防御性剥离） */
        private val LIST_MARKER_REGEX =
            Regex("^(?:[0-9]+|[一二三四五六七八九十]+)[.、)）]\\s*|^[①②③④⑤⑥⑦⑧⑨⑩]\\s*")

        /**
         * 清洗一行原始文本为候选（无状态工具，非流式整包路径同样复用）。
         *
         * @param line 原始行文本（可含序号前缀/前导标点/首尾空白）
         * @return 清洗后的候选；空白或纯标点行返回空串（调用方据此过滤）
         */
        fun cleanLine(line: String): String {
            val stripped = LIST_MARKER_REGEX.replace(line.trim(), "")
            // 前导非字母数字字符（标点/符号/空白）整体剥离；句尾标点保留
            val body = stripped.dropWhile { c -> !c.isLetterOrDigit() }.trim()
            return body.take(MAX_CANDIDATE_CHARS)
        }

        /** 把整段非流式响应内容解析为候选列表（换行切分 + 逐行清洗 + 限数） */
        fun parseWhole(content: String): List<String> =
            content.trim()
                .split('\n', '\r')
                .map { cleanLine(it) }
                .filter { it.isNotEmpty() }
                .take(MAX_CANDIDATES)
    }

    /** 尚未构成完整行的增量缓冲（token 可能在词中间被截断） */
    private val buffer = StringBuilder()

    /** 已成功发射的候选条数（达到 [MAX_CANDIDATES] 后静默忽略后续行） */
    private var emitted = 0

    /** 流是否已到达候选上限（[offer]/[flush] 据此短路，防无谓解析） */
    private val saturated: Boolean get() = emitted >= MAX_CANDIDATES

    /**
     * 喂入一个增量文本片段，返回本次**新完成**的候选（可能为空）。
     *
     * 片段按换行切分：最后一段（可能不完整）留在缓冲，其余完整行逐行
     * 清洗，非空者发射。已达条数上限时直接返回空列表。
     *
     * @param delta SSE 增量文本（token 粒度，任意截断位置）
     * @return 本次新完成的候选列表（按到达顺序）
     */
    fun offer(delta: String): List<String> {
        if (saturated || delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val lines = buffer.split('\n', '\r')
        // split 的末段为未完结残片（无换行结尾时即全部缓冲），留在缓冲
        buffer.setLength(0)
        buffer.append(lines.last())
        val result = ArrayList<String>(lines.size - 1)
        for (i in 0 until lines.size - 1) {
            if (saturated) break
            val candidate = cleanLine(lines[i])
            if (candidate.isNotEmpty()) {
                result.add(candidate)
                emitted++
            }
        }
        return result
    }

    /**
     * 流结束时冲刷缓冲中的残行（末行无换行结尾的常态）。
     *
     * @return 残行清洗后的候选（0 或 1 条）；已达上限或残行为空时为空
     */
    fun flush(): List<String> {
        if (saturated) return emptyList()
        val candidate = cleanLine(buffer.toString())
        buffer.setLength(0)
        return if (candidate.isEmpty()) emptyList() else {
            emitted++
            listOf(candidate)
        }
    }

    /** 已发射候选总数 */
    fun emittedCount(): Int = emitted
}
