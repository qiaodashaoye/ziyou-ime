package com.ziyou.ime.ime

import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.util.T9PinYinUtils

/**
 * 九宫格拼音提示/预览生成器（纯逻辑，无 Android 依赖，便于单元测试）。
 *
 * 从 [ZiYouInputMethodService] 中剥离，仅依赖 [ContextProto] 与 [T9PinYinUtils]。
 * 键盘类型判定（是否九宫格）由调用方负责，本类只处理"给定上下文时该展示什么"。
 */
object PinyinHintProvider {

    /** 侧栏拼音候选最大展示数量。 */
    private const val MAX_HINTS = 8

    /**
     * 生成九宫格拼音候选列表。
     * 优先从 Rime 原始输入串提取"首个未消歧数字段"，用本地 T9 表还原候选拼音（更精确）；
     * 回退到从候选词 comment（spelling_hints）提取真实拼音。
     *
     * @return 候选拼音列表；无可用提示返回 null。
     */
    fun buildHints(context: ContextProto?): List<String>? {
        if (context == null) return null
        // 优先：从输入串（数字与已锁定拼音 + 分词符混排，如 "guo'486"）提取首个数字段
        val digitSegment = context.input
            .split('\'', ' ')
            .firstOrNull { seg -> seg.isNotEmpty() && seg.all { it in '2'..'9' } }
        if (digitSegment != null) {
            val pinyins = T9PinYinUtils.t9KeyToPinyin(digitSegment).filter { it.isNotBlank() }
            if (pinyins.isNotEmpty()) return pinyins.take(MAX_HINTS)
        }
        // 回退：从候选词 comment 提取
        val candidates = context.menu?.candidates ?: return null
        if (candidates.isEmpty()) return null
        val hints = LinkedHashSet<String>()
        for (candidate in candidates) {
            val py = candidate.comment.trim()
            if (py.isNotEmpty()) hints.add(py)
            if (hints.size >= MAX_HINTS) break
        }
        return hints.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * 生成顶部编码区的"当前拼音"单串预览。
     * 取高亮候选的拼音读音（spelling_hints comment）作为当前解释；无候选时回退到首个拼音提示。
     *
     * @return 预览串；无可用内容返回 null。
     */
    fun buildPreview(context: ContextProto?, hints: List<String>?): String? {
        val menu = context?.menu
        val candidates = menu?.candidates
        val highlighted = menu?.highlightedCandidateIndex ?: -1
        if (candidates != null && highlighted in candidates.indices) {
            val comment = candidates[highlighted].comment.trim()
            if (comment.isNotEmpty()) return comment
        }
        return hints?.firstOrNull()
    }
}
