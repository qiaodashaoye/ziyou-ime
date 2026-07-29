package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 图片缓存过期清理策略测试：
 * commitContent 后对端应用异步拉取 content:// URI，写前清理必须只删过期文件
 * （宽限窗口内的新图不可误删），并在爆发式发图时按 mtime 补删兜底。
 */
class ImeImageCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_000_000_000_000L

    /** 过期阈值须与 [ImeImageCache] 内部 EXPIRE_MS 一致（5 分钟） */
    private val expireMs = 5 * 60 * 1000L

    @Test
    fun `过期文件被删除且宽限窗口内文件保留`() {
        val dir = tempFolder.newFolder("ime_images")
        val expired = dir.resolve("old.png").apply {
            writeBytes(ByteArray(1))
            setLastModified(now - expireMs - 1_000)
        }
        val fresh = dir.resolve("fresh.png").apply {
            writeBytes(ByteArray(1))
            setLastModified(now - 1_000)
        }

        ImeImageCache.pruneExpired(dir, nowMs = now)

        assertFalse("过期文件应被删除", expired.exists())
        assertTrue("宽限窗口内文件不可误删", fresh.exists())
    }

    @Test
    fun `超过保留上限时按mtime从旧到新补删`() {
        val dir = tempFolder.newFolder("ime_images")
        // 25 个未过期文件，mtime 递增（index 越小越旧）
        val files = (0 until 25).map { index ->
            dir.resolve("img_$index.png").apply {
                writeBytes(ByteArray(1))
                setLastModified(now - 60_000 + index * 1_000)
            }
        }

        ImeImageCache.pruneExpired(dir, nowMs = now)

        val survivors = dir.listFiles().orEmpty()
        assertEquals("补删后应只保留上限数量", 20, survivors.size)
        // 最旧的 5 个被删，最新的 20 个保留
        files.take(5).forEach { assertFalse("最旧文件应被补删: ${it.name}", it.exists()) }
        files.drop(5).forEach { assertTrue("较新文件应保留: ${it.name}", it.exists()) }
    }

    @Test
    fun `目录不存在时安全早返回`() {
        val missing = tempFolder.root.resolve("not_exist")
        // 不抛异常即通过
        ImeImageCache.pruneExpired(missing, nowMs = now)
        assertFalse(missing.exists())
    }
}
