package com.ziyou.ime.sdk.state

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.CompositionProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.MenuProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDK 状态服务快照派生测试（docs/SDK模块拆分重构方案.md §4.3/§4.4）。
 *
 * 守护两条契约：
 * - PreeditState 空值规范：null 上下文 / 无 composition / 空 preedit ⇒ isEmpty
 * - CandidatesSnapshot 联想态判定：菜单非空且编码为空 ⇒ isPrediction
 */
class StateServicesTest {

    private fun composition(preedit: String?) = CompositionProto(
        length = preedit?.length ?: 0,
        cursorPos = 2,
        selStart = 1,
        selEnd = 2,
        preedit = preedit,
        commitTextPreview = null
    )

    private fun menu(vararg texts: String) = MenuProto(
        pageSize = 5,
        pageNumber = 0,
        isLastPage = true,
        highlightedCandidateIndex = 0,
        candidates = Array(texts.size) { CandidateProto(texts[it], "", "$it.") },
        selectKeys = "",
        selectLabels = emptyArray()
    )

    private fun context(
        composition: CompositionProto? = null,
        menu: MenuProto? = null,
        input: String = ""
    ) = ContextProto(composition, menu, input, caretPos = 0)

    // ==================== PreeditState ====================

    @Test
    fun `preedit from null context is empty`() {
        assertTrue(PreeditState.from(null).isEmpty)
    }

    @Test
    fun `preedit from context without composition is empty`() {
        assertTrue(PreeditState.from(context()).isEmpty)
    }

    @Test
    fun `preedit from null preedit text is empty`() {
        assertTrue(PreeditState.from(context(composition = composition(null))).isEmpty)
    }

    @Test
    fun `preedit maps composition fields`() {
        val state = PreeditState.from(context(composition = composition("ni'hao")))
        assertEquals("ni'hao", state.rawText)
        assertEquals(2, state.caretPos)
        assertEquals(1, state.selStart)
        assertEquals(2, state.selEnd)
        assertFalse(state.isEmpty)
    }

    // ==================== CandidatesSnapshot ====================

    @Test
    fun `snapshot from null context is empty`() {
        val snapshot = CandidatesSnapshot.from(null)
        assertTrue(snapshot.items.isEmpty())
        assertEquals(-1, snapshot.highlightedIndex)
        assertFalse(snapshot.isPrediction)
    }

    @Test
    fun `snapshot converts candidates array`() {
        val snapshot = CandidatesSnapshot.from(
            context(menu = menu("你", "尼"), input = "ni")
        )
        assertEquals(listOf("你", "尼"), snapshot.items.map { it.text })
        assertEquals(2, snapshot.total)
        assertEquals(0, snapshot.highlightedIndex)
        assertFalse(snapshot.isPrediction)
    }

    @Test
    fun `snapshot detects prediction mode`() {
        // 联想态：commit 后引擎预测——菜单非空且编码串为空
        val snapshot = CandidatesSnapshot.from(context(menu = menu("你好"), input = ""))
        assertTrue(snapshot.isPrediction)
    }

    @Test
    fun `snapshot with active input is not prediction`() {
        val snapshot = CandidatesSnapshot.from(
            context(composition = composition("ni"), menu = menu("你"), input = "ni")
        )
        assertFalse(snapshot.isPrediction)
    }
}
