package com.ziyou.ime.ai.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [LlmPredictionStats] 单元测试：验收指标的打点结算与汇总格式。
 *
 * 口径对应 docs/联想功能优化调研与方案.md §7.7：
 * 命中率/链式轮次/预取/请求数/首条延迟 p50。
 */
class LlmPredictionStatsTest {

    @Before
    fun setUp() = LlmPredictionStats.reset()

    @Test
    fun `命中率按命中除以总查询数`() {
        repeat(3) { LlmPredictionStats.onCacheHit() }
        repeat(7) { LlmPredictionStats.onCacheMiss() }
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("hits=3"))
        assertTrue(dump.contains("misses=7"))
        assertTrue(dump.contains("hitRate=30.0%"))
    }

    @Test
    fun `采纳后的下一次查询结算为一轮链式`() {
        LlmPredictionStats.onAdoption()
        LlmPredictionStats.onCacheHit() // 链式命中
        LlmPredictionStats.onAdoption()
        LlmPredictionStats.onCacheMiss() // 链式未命中
        LlmPredictionStats.onCacheHit() // 无挂起采纳，不计轮次
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("chain=1/2"))
    }

    @Test
    fun `功能关闭期的上屏消费挂起采纳不误结算`() {
        LlmPredictionStats.onAdoption()
        LlmPredictionStats.onLookupSkipped()
        LlmPredictionStats.onCacheHit()
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("chain=0/0"))
    }

    @Test
    fun `预取发起与预热命中分别计数`() {
        repeat(2) { LlmPredictionStats.onPrefetchIssued() }
        LlmPredictionStats.onPrefetchWarmHit()
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("prefetch=1/2"))
    }

    @Test
    fun `首条延迟取p50且每请求只记账一次`() {
        // 三次请求：首批交付延迟 300/400/500ms，p50 = 400
        var now = 1_000L
        repeat(3) { i ->
            LlmPredictionStats.onRequestStarted(now)
            LlmPredictionStats.onFirstCandidate(now + 300L + i * 100L)
            // 同请求的二次交付不重复记账
            LlmPredictionStats.onFirstCandidate(now + 900L)
            now += 10_000L
        }
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("reqs=3"))
        assertTrue(dump.contains("p50ms=400"))
    }

    @Test
    fun `dump后全部清零`() {
        LlmPredictionStats.onCacheHit()
        LlmPredictionStats.onRequestStarted(100L)
        LlmPredictionStats.dumpAndReset()
        val second = LlmPredictionStats.dumpAndReset()
        assertEquals(
            "hits=0 misses=0 hitRate=0.0% chain=0/0 prefetch=0/0 reqs=0 p50ms=-1" +
                " engineShown=0 gap=0 llmAdopt=0/0 poetry=0", second)
    }

    @Test
    fun `诗词链路采纳单独计数`() {
        // 一句诗联想整首诗：仅计数无词内容（隐私红线）
        repeat(4) { LlmPredictionStats.onPoetryAdoption() }
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("poetry=4"))
    }

    @Test
    fun `引擎联想在场与空档分别计数`() {
        // S6 决策数据：上屏后引擎预测在场 / 无任何联想（句末等时刻）
        repeat(3) { LlmPredictionStats.onEnginePredictionShown() }
        repeat(2) { LlmPredictionStats.onAssociationGap() }
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("engineShown=3"))
        assertTrue(dump.contains("gap=2"))
    }

    @Test
    fun `LLM采纳按引擎候选在场与否分桶`() {
        // S8 决策数据：位置策略 A/B 依据
        LlmPredictionStats.onLlmAdoption(withEngine = true)
        repeat(2) { LlmPredictionStats.onLlmAdoption(withEngine = false) }
        val dump = LlmPredictionStats.dumpAndReset()
        assertTrue(dump.contains("llmAdopt=1/2"))
    }
}
