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
 * 覆盖：从数字输入串还原候选拼音、回退到候选 comment、
 * 预览与高亮候选读音同源且长度匹配实际击键、空上下文边界。
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
    fun buildPreview_followsHighlightedCandidatePinyin() {
        // 核心缺陷场景：输入 48，首位候选是"乎"(hu)，
        // 编码区必须展示 hu（与候选读音一致），而非本地 T9 表序的 gu
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "hu"), candidate("顾", "gu")),
            highlighted = 0
        )
        assertEquals("hu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_highlightSwitch_followsNewCandidate() {
        // 高亮切到"顾"(gu) 时，编码区同步展示 gu
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "hu"), candidate("顾", "gu")),
            highlighted = 1
        )
        assertEquals("gu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_partialSyllable_truncatesToKeyCount() {
        // 仅击 1 键（4），候选"好"完整拼音 hao：只展示已击键部分 h，不展示完整拼音
        val ctx = context(
            input = "4",
            candidates = listOf(candidate("好", "hao")),
            highlighted = 0
        )
        assertEquals("h", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_multiSyllable_alignsCommentSyllables() {
        // 连续击键 64426（ni=64, hao=426），候选"你好"comment "ni hao" → ni'hao
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("你好", "ni hao")),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_lockedPinyinKeptVerbatim() {
        // 已锁定拼音 guo 原样保留；数字段 486 按候选读音第二音节 hun 还原
        val ctx = context(
            input = "guo'486",
            candidates = listOf(candidate("国魂", "guo hun")),
            highlighted = 0
        )
        assertEquals("guo'hun", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_noCandidate_fallsBackToLocalT9Table() {
        // 无候选时回退本地 T9 表：486 → GTM 首个等长匹配 gun
        assertEquals("gun", PinyinHintProvider.buildPreview(context(input = "486")))
    }

    @Test
    fun buildPreview_incompatibleComment_fallsBackToLocalT9Table() {
        // 候选读音与击键不兼容（ma 的键序 62 与 486 无任何前缀关系）时回退本地还原
        val ctx = context(
            input = "486",
            candidates = listOf(candidate("妈", "ma")),
            highlighted = 0
        )
        assertEquals("gun", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_letterCountAlwaysMatchesKeyCount() {
        // 无论是否有候选消歧，预览字母总数必须等于击键数
        val preview = PinyinHintProvider.buildPreview(context(input = "64426"))
        assertEquals(5, preview!!.replace("'", "").length)
    }

    @Test
    fun buildPreview_emptyInput_returnsNull() {
        assertNull(PinyinHintProvider.buildPreview(context(input = "")))
        assertNull(PinyinHintProvider.buildPreview(null))
    }
}
