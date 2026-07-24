package com.ziyou.ime.ime

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.MenuProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinyinHintProvider] 纯逻辑回归测试。
 *
 * 覆盖：从数字输入串还原候选拼音、回退到候选 comment、预览优先取高亮候选读音、空上下文边界。
 */
class PinyinHintProviderTest {

    private fun candidate(text: String, comment: String) =
        CandidateProto(text = text, comment = comment, label = "")

    private fun context(
        input: String,
        candidates: List<CandidateProto> = emptyList(),
        highlighted: Int = 0
    ): ContextProto {
        val menu = if (candidates.isEmpty()) null else MenuProto(
            pageSize = candidates.size,
            pageNumber = 0,
            isLastPage = true,
            highlightedCandidateIndex = highlighted,
            candidates = candidates.toTypedArray(),
            selectKeys = "",
            selectLabels = emptyArray()
        )
        return ContextProto(composition = null, menu = menu, input = input, caretPos = input.length)
    }

    @Test
    fun buildHints_fromDigitSegment_returnsT9Pinyins() {
        // 输入 486 → guo/gun/huo/hun...
        val hints = PinyinHintProvider.buildHints(context(input = "486"))
        assertTrue(hints != null && hints.contains("guo"))
    }

    @Test
    fun buildHints_fromLockedPinyinPlusDigits_extractsFirstDigitSegment() {
        // "guo'486" → 首个数字段是 486
        val hints = PinyinHintProvider.buildHints(context(input = "guo'486"))
        assertTrue(hints != null && hints.contains("guo"))
    }

    @Test
    fun buildHints_noDigitSegment_fallsBackToCandidateComments() {
        val ctx = context(
            input = "guo",
            candidates = listOf(candidate("过", "guo"), candidate("国", "guo"))
        )
        val hints = PinyinHintProvider.buildHints(ctx)
        assertEquals(listOf("guo"), hints)
    }

    @Test
    fun buildHints_nullContext_returnsNull() {
        assertNull(PinyinHintProvider.buildHints(null))
    }

    @Test
    fun buildPreview_prefersHighlightedCandidateComment() {
        val ctx = context(
            input = "486",
            candidates = listOf(candidate("过", "guo"), candidate("锅", "guo")),
            highlighted = 1
        )
        assertEquals("guo", PinyinHintProvider.buildPreview(ctx, listOf("gun")))
    }

    @Test
    fun buildPreview_noCandidate_fallsBackToFirstHint() {
        assertEquals("gun", PinyinHintProvider.buildPreview(context(input = "486"), listOf("gun", "guo")))
    }
}
