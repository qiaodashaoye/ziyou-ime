package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.testing.FakeSkinContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SkinRepository] 规格缓存测试：导入皮肤的 skin.json 解码产物进程级缓存、
 * [SkinRepository.evictSpec] 定向失效（安装/卸载时机调用）。
 */
class SkinRepositorySpecCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: FakeSkinContext

    /** 每用例独占皮肤 id，避免进程级 specCache 跨用例串扰。 */
    private var testSkinId = ""
    private var counter = 0

    @Before
    fun setUp() {
        context = FakeSkinContext(tempFolder.root)
        testSkinId = "com.test.speccache${counter++}_${System.nanoTime()}"
        SkinRepository.evictSpec(testSkinId)
    }

    private fun writeSkinJson(name: String) {
        val dir = SkinRepository.skinDir(context, testSkinId).apply { mkdirs() }
        dir.resolve("skin.json").writeText(
            """{"specVersion":1,"meta":{"id":"$testSkinId","name":"$name"}}""")
    }

    @Test
    fun loadSpec_cachesDecodedSpec() {
        writeSkinJson("原始")
        val first = SkinRepository.loadSpec(context, testSkinId)
        assertEquals("原始", first.meta.name)

        // 磁盘文件损坏后仍命中缓存（读热路径零磁盘 IO 的证据）
        SkinRepository.skinDir(context, testSkinId)
            .resolve("skin.json").writeText("{ broken")
        val cached = SkinRepository.loadSpec(context, testSkinId)
        assertSame(first, cached)
    }

    @Test
    fun evictSpec_forcesReloadFromDisk() {
        writeSkinJson("v1")
        SkinRepository.loadSpec(context, testSkinId)

        writeSkinJson("v2")
        SkinRepository.evictSpec(testSkinId)
        assertEquals("v2", SkinRepository.loadSpec(context, testSkinId).meta.name)
    }

    @Test
    fun evictSpec_thenCorruptedFile_throws() {
        writeSkinJson("ok")
        SkinRepository.loadSpec(context, testSkinId)

        SkinRepository.skinDir(context, testSkinId)
            .resolve("skin.json").writeText("{ broken")
        SkinRepository.evictSpec(testSkinId)
        assertThrows(IllegalArgumentException::class.java) {
            SkinRepository.loadSpec(context, testSkinId)
        }
    }

    @Test
    fun builtinSpec_bypassesCacheAndDisk() {
        // 内置皮肤直接取内存规格，与磁盘/缓存无关
        val spec = SkinRepository.loadSpec(context, SkinDefaults.ID_FLOAT3D)
        assertEquals(SkinDefaults.ID_FLOAT3D, spec.meta.id)
    }
}
