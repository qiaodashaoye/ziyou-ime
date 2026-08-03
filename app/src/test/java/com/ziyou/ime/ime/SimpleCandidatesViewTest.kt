package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimpleCandidatesView] 伴生纯逻辑函数测试。
 *
 * 覆盖：
 * - [SimpleCandidatesView.toLocalIndex] 累积索引 → 页内局部索引映射
 * - [SimpleCandidatesView.isForwardPage] 翻页方向判定
 */
class SimpleCandidatesViewTest {

    // ===== toLocalIndex =====

    @Test
    fun toLocalIndex_firstPage_noOffset() {
        // 首页（pageNumber=0, pageStart=0），索引无偏移
        assertEquals(0, SimpleCandidatesView.toLocalIndex(0, 0, 0, 5))
        assertEquals(3, SimpleCandidatesView.toLocalIndex(3, 0, 0, 5))
        assertEquals(4, SimpleCandidatesView.toLocalIndex(4, 0, 0, 5))
    }

    @Test
    fun toLocalIndex_secondPage_withOffset() {
        // 第二页（pageNumber=1, pageStart=0, pageSize=5），偏移 = 1*5 = 5
        // 累积索引 5 → 局部索引 0
        assertEquals(0, SimpleCandidatesView.toLocalIndex(5, 1, 0, 5))
        // 累积索引 7 → 局部索引 2
        assertEquals(2, SimpleCandidatesView.toLocalIndex(7, 1, 0, 5))
    }

    @Test
    fun toLocalIndex_thirdPage_withOffset() {
        // 第三页（pageNumber=2, pageStart=0, pageSize=5），偏移 = 2*5 = 10
        assertEquals(0, SimpleCandidatesView.toLocalIndex(10, 2, 0, 5))
        assertEquals(3, SimpleCandidatesView.toLocalIndex(13, 2, 0, 5))
    }

    @Test
    fun toLocalIndex_backwardPage_resetsAccumulated() {
        // 后翻页后 accumulatedPageStart 重置为当前页
        // pageNumber=0, pageStart=0 → 偏移 0
        assertEquals(2, SimpleCandidatesView.toLocalIndex(2, 0, 0, 5))
    }

    @Test
    fun toLocalIndex_clampNegative() {
        // 极端情况：索引小于偏移时，coerceAtLeast(0) 保护
        assertEquals(0, SimpleCandidatesView.toLocalIndex(3, 1, 0, 5)) // 3 - 5 = -2 → 0
    }

    @Test
    fun toLocalIndex_pageSizeOne() {
        // pageSize=1 时每页仅一个候选
        assertEquals(0, SimpleCandidatesView.toLocalIndex(0, 0, 0, 1))
        assertEquals(0, SimpleCandidatesView.toLocalIndex(3, 3, 0, 1))
    }

    // ===== toGlobalIndex =====

    @Test
    fun toGlobalIndex_firstPage_equalsAccumulatedIndex() {
        // 起始页 0：全局索引 == 累积索引
        assertEquals(0, SimpleCandidatesView.toGlobalIndex(0, 0, 5))
        assertEquals(3, SimpleCandidatesView.toGlobalIndex(3, 0, 5))
    }

    @Test
    fun toGlobalIndex_secondPage_addsPageOffset() {
        // 起始页 0，翻到第二页后累积缓冲含两页；点击累积索引 7 → 全局 7
        assertEquals(7, SimpleCandidatesView.toGlobalIndex(7, 0, 5))
    }

    @Test
    fun toGlobalIndex_nonZeroStart_addsStartOffset() {
        // 后翻重置后起始页 = 2，pageSize=5；累积索引 0 → 全局 10
        assertEquals(10, SimpleCandidatesView.toGlobalIndex(0, 2, 5))
        // 累积索引 3 → 全局 13
        assertEquals(13, SimpleCandidatesView.toGlobalIndex(3, 2, 5))
    }

    @Test
    fun toGlobalIndex_pageSizeOne() {
        // pageSize=1：全局索引 = 起始页 + 累积索引
        assertEquals(4, SimpleCandidatesView.toGlobalIndex(1, 3, 1))
    }

    // ===== resolveHighlight =====

    @Test
    fun resolveHighlight_inputChanged_keepsEngineHighlight() {
        // 新按键/删除：沿用引擎页内高亮（新组合的默认选中态）
        assertEquals(0, SimpleCandidatesView.resolveHighlight(true, 0))
        assertEquals(2, SimpleCandidatesView.resolveHighlight(true, 2))
    }

    @Test
    fun resolveHighlight_inputChanged_engineNoHighlight() {
        // 引擎无高亮时保持无高亮
        assertEquals(-1, SimpleCandidatesView.resolveHighlight(true, -1))
    }

    @Test
    fun resolveHighlight_pageFlip_clearsHighlight() {
        // 翻页（输入未变）：无论引擎报告什么高亮都必须清除，
        // 新页在用户重新选择前不带选中项
        assertEquals(-1, SimpleCandidatesView.resolveHighlight(false, 0))
        assertEquals(-1, SimpleCandidatesView.resolveHighlight(false, 3))
        assertEquals(-1, SimpleCandidatesView.resolveHighlight(false, -1))
    }

    // ===== isForwardPage =====

    @Test
    fun isForwardPage_emptyBuffer_firstLoad() {
        // 首次加载（缓冲为空），任何页码都视为前翻
        assertTrue(SimpleCandidatesView.isForwardPage(0, 0, 0, 5))
    }

    @Test
    fun isForwardPage_nextPage() {
        // 缓冲含 5 个候选（1页），当前页码 1 → 前翻页
        assertTrue(SimpleCandidatesView.isForwardPage(1, 0, 5, 5))
    }

    @Test
    fun isForwardPage_samePage() {
        // 缓冲含 5 个候选，当前页码 0 → 非前翻（重复加载）
        assertFalse(SimpleCandidatesView.isForwardPage(0, 0, 5, 5))
    }

    @Test
    fun isForwardPage_backwardPage() {
        // 缓冲含 10 个候选（2页），回翻到页码 0 → 非前翻
        assertFalse(SimpleCandidatesView.isForwardPage(0, 0, 10, 5))
    }

    @Test
    fun isForwardPage_multiplePagesLoaded() {
        // 缓冲含 15 个候选（3页），当前页码 3 → 前翻页
        assertTrue(SimpleCandidatesView.isForwardPage(3, 0, 15, 5))
        // 当前页码 2 → 非前翻（已在缓冲内）
        assertFalse(SimpleCandidatesView.isForwardPage(2, 0, 15, 5))
    }

    @Test
    fun isForwardPage_nonZeroStart() {
        // 后翻重置后 pageStart=2，缓冲含 5 个候选
        // 当前页码 3 → 前翻页
        assertTrue(SimpleCandidatesView.isForwardPage(3, 2, 5, 5))
        // 当前页码 2 → 非前翻
        assertFalse(SimpleCandidatesView.isForwardPage(2, 2, 5, 5))
    }

    @Test
    fun isForwardPage_pageSizeZero() {
        // 边界：pageSize=0 时使用 fallback pagesLoaded=1
        assertTrue(SimpleCandidatesView.isForwardPage(1, 0, 0, 0))
        assertFalse(SimpleCandidatesView.isForwardPage(0, 0, 0, 0))
    }
}
