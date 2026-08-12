package com.ziyou.ime.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ContextLruCache] 单元测试：读写、key 语义、LRU 淘汰与清空。 */
class ContextLruCacheTest {

    @Test
    fun `put后可按相同词序列get命中`() {
        val cache = ContextLruCache()
        cache.put(listOf("今天", "天气"), listOf("不错", "很好"))
        assertEquals(listOf("不错", "很好"), cache.get(listOf("今天", "天气")))
        assertEquals(1, cache.size())
    }

    @Test
    fun `未命中返回null`() {
        val cache = ContextLruCache()
        assertNull(cache.get(listOf("不存在")))
        cache.put(listOf("今天"), listOf("明天"))
        assertNull(cache.get(listOf("明天")))
    }

    @Test
    fun `key对词序敏感且边界不歧义`() {
        val cache = ContextLruCache()
        cache.put(listOf("今天", "天气"), listOf("序A"))
        // 词序不同视为不同 key
        assertNull(cache.get(listOf("天气", "今天")))
        // 拼接歧义防护：["ab","c"] 与 ["a","bc"] 不可碰撞
        cache.put(listOf("ab", "c"), listOf("词组1"))
        assertNull(cache.get(listOf("a", "bc")))
    }

    @Test
    fun `同key覆盖写入`() {
        val cache = ContextLruCache()
        cache.put(listOf("你好"), listOf("旧"))
        cache.put(listOf("你好"), listOf("新"))
        assertEquals(listOf("新"), cache.get(listOf("你好")))
        assertEquals(1, cache.size())
    }

    @Test
    fun `超出容量淘汰最久未访问项`() {
        val cache = ContextLruCache()
        repeat(ContextLruCache.CAPACITY + 1) { cache.put(listOf("词$it"), listOf("候选$it")) }
        assertEquals(ContextLruCache.CAPACITY, cache.size())
        // 最旧一项被淘汰，最新一项在缓存中
        assertNull(cache.get(listOf("词0")))
        assertEquals(listOf("候选${ContextLruCache.CAPACITY}"), cache.get(listOf("词${ContextLruCache.CAPACITY}")))
    }

    @Test
    fun `get命中会刷新访问序从而影响淘汰顺序`() {
        val cache = ContextLruCache()
        repeat(ContextLruCache.CAPACITY) { cache.put(listOf("词$it"), listOf("候选$it")) }
        // 访问最旧项使其成为最近使用
        cache.get(listOf("词0"))
        // 再写入一项触发淘汰：被淘汰的应是次旧的「词1」而非「词0」
        cache.put(listOf("新词"), listOf("新候选"))
        assertEquals(listOf("候选0"), cache.get(listOf("词0")))
        assertNull(cache.get(listOf("词1")))
    }

    @Test
    fun `clear清空全部条目`() {
        val cache = ContextLruCache()
        cache.put(listOf("你好"), listOf("世界"))
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get(listOf("你好")))
    }
}
