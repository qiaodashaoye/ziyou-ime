package com.ziyou.ime.ai.prediction

import android.content.Context
import com.ziyou.ime.core.prediction.ContextLruCache
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PredictionCacheStore] / [AdoptionStore] 持久化单元测试（联想优化方案 §4.3/§4.6）。
 *
 * 验收点：
 * - 缓存快照 JSON 往返一致（词序与候选序保持，restore 可还原 LRU 访问序）；
 * - 攒批词对 JSON 往返一致；
 * - 文件缺失/损坏/超限一律降级为空（缓存是优化项不是正确性依赖）；
 * - 原子落盘（.tmp 就位替换）后不残留临时文件。
 */
class PredictionPersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk()
        every { context.filesDir } answers { tempFolder.root }
    }

    @Test
    fun `缓存快照落盘与装载往返一致`() {
        val snapshot = listOf(
            ContextLruCache.Entry(listOf("今天", "天气"), listOf("不错", "很好")),
            ContextLruCache.Entry(listOf("收到"), listOf("谢谢", "好的"))
        )
        assertTrue(PredictionCacheStore.save(context, snapshot))
        assertEquals(snapshot, PredictionCacheStore.load(context))
    }

    @Test
    fun `缓存文件不存在时装载为空`() {
        assertEquals(emptyList<ContextLruCache.Entry>(), PredictionCacheStore.load(context))
    }

    @Test
    fun `缓存文件损坏时降级为空而非抛异常`() {
        tempFolder.newFile("llm_prediction_cache.json").writeText("{这不是合法JSON", Charsets.UTF_8)
        assertEquals(emptyList<ContextLruCache.Entry>(), PredictionCacheStore.load(context))
    }

    @Test
    fun `缓存快照中的空条目在装载时被过滤`() {
        // 直接构造含空词条目的 JSON，验证 parse 侧防御
        val json = """{"v":1,"entries":[{"w":[],"c":["x"]},{"w":["好"],"c":[]},{"w":["你好"],"c":["世界"]}]}"""
        tempFolder.newFile("llm_prediction_cache.json").writeText(json, Charsets.UTF_8)
        val loaded = PredictionCacheStore.load(context)
        assertEquals(1, loaded.size)
        assertEquals(listOf("你好"), loaded.single().words)
    }

    @Test
    fun `落盘后无临时文件残留`() {
        PredictionCacheStore.save(
            context,
            listOf(ContextLruCache.Entry(listOf("词"), listOf("候选")))
        )
        assertTrue(tempFolder.root.listFiles()?.none { it.name.endsWith(".tmp") } == true)
    }

    @Test
    fun `delete清除缓存文件`() {
        PredictionCacheStore.save(
            context,
            listOf(ContextLruCache.Entry(listOf("词"), listOf("候选")))
        )
        PredictionCacheStore.delete(context)
        assertEquals(emptyList<ContextLruCache.Entry>(), PredictionCacheStore.load(context))
    }

    @Test
    fun `攒批词对落盘与装载往返一致`() {
        val data = mapOf(
            "今天" to mapOf("天气" to 3, "开心" to 1),
            "收到" to mapOf("谢谢" to 5)
        )
        assertTrue(AdoptionStore.save(context, data))
        assertEquals(data, AdoptionStore.load(context))
    }

    @Test
    fun `攒批文件不存在或损坏时降级为空`() {
        assertEquals(emptyMap<String, Map<String, Int>>(), AdoptionStore.load(context))
        tempFolder.newFile("prediction_adoptions.json").writeText("{{", Charsets.UTF_8)
        assertEquals(emptyMap<String, Map<String, Int>>(), AdoptionStore.load(context))
    }
}
