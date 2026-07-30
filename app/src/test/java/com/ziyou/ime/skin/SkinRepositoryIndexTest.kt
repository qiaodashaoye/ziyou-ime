package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.testing.FakeSkinContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SkinRepository] 已安装索引测试：索引损坏时的磁盘扫描自愈、
 * 目录缺失静默剔除、同 id 覆盖登记。
 */
class SkinRepositoryIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: FakeSkinContext

    @Before
    fun setUp() {
        context = FakeSkinContext(tempFolder.root)
    }

    /** 写入一个合法的导入皮肤安装目录（目录名 = 皮肤 id）。 */
    private fun installSkinDir(skinId: String, name: String = skinId) {
        val dir = SkinRepository.skinDir(context, skinId).apply { mkdirs() }
        dir.resolve("skin.json").writeText(
            """{"specVersion":1,"meta":{"id":"$skinId","name":"$name","version":"2.0.0"}}"""
        )
    }

    private fun skinPrefs() =
        context.getSharedPreferences("ziyou_skin", 0)

    @Test
    fun corruptedIndex_rebuiltFromDiskScan() {
        installSkinDir("com.test.alpha", "Alpha")
        installSkinDir("com.test.beta", "Beta")
        // 损坏目录：skin.json 非法，重建时应跳过而不中断
        val brokenDir = SkinRepository.skinDir(context, "com.test.broken").apply { mkdirs() }
        brokenDir.resolve("skin.json").writeText("{ not-valid-json")
        // 目录名与规格 id 不一致：视为非法安装，跳过
        val mismatchDir = SkinRepository.skinDir(context, "com.test.mismatch").apply { mkdirs() }
        mismatchDir.resolve("skin.json").writeText(
            """{"specVersion":1,"meta":{"id":"com.test.other","name":"X"}}""")

        skinPrefs().edit().putString("installed_index", "corrupted{{{").apply()

        val installed = SkinRepository.listInstalled(context)
        val importedIds = installed.filterNot { it.isBuiltin }.map { it.id }
        assertEquals(setOf("com.test.alpha", "com.test.beta"), importedIds.toSet())
        // 重建结果已回写：索引 JSON 恢复为合法内容
        val rewritten = skinPrefs().getString("installed_index", null)
        assertNotNull(rewritten)
        assertTrue(rewritten!!.contains("com.test.alpha"))
        assertFalse(rewritten.contains("com.test.broken"))
        // 版本等元数据从 skin.json 恢复
        assertEquals("2.0.0",
            installed.first { it.id == "com.test.alpha" }.version)
    }

    @Test
    fun missingInstallDir_silentlyDropped() {
        installSkinDir("com.test.kept")
        SkinRepository.addToIndex(context,
            SkinSpecCodec.decodeSpec(
                """{"specVersion":1,"meta":{"id":"com.test.kept","name":"Kept"}}"""))
        SkinRepository.addToIndex(context,
            SkinSpecCodec.decodeSpec(
                """{"specVersion":1,"meta":{"id":"com.test.ghost","name":"Ghost"}}"""))

        val importedIds = SkinRepository.listInstalled(context)
            .filterNot { it.isBuiltin }.map { it.id }
        assertEquals(listOf("com.test.kept"), importedIds)
    }

    @Test
    fun addToIndex_sameIdOverwrites() {
        installSkinDir("com.test.dup")
        SkinRepository.addToIndex(context,
            SkinSpecCodec.decodeSpec(
                """{"specVersion":1,"meta":{"id":"com.test.dup","name":"旧名"}}"""))
        SkinRepository.addToIndex(context,
            SkinSpecCodec.decodeSpec(
                """{"specVersion":1,"meta":{"id":"com.test.dup","name":"新名","version":"3.0.0"}}"""))

        val imported = SkinRepository.listInstalled(context).filterNot { it.isBuiltin }
        assertEquals(1, imported.size)
        assertEquals("新名", imported[0].name)
        assertEquals("3.0.0", imported[0].version)
    }

    @Test
    fun builtinSkins_alwaysListedFirst() {
        val installed = SkinRepository.listInstalled(context)
        assertTrue(installed.isNotEmpty())
        assertTrue(installed.first().isBuiltin)
        assertNull(installed.first().installDir)
        assertTrue(installed.any { it.id == SkinDefaults.DEFAULT_SKIN_ID })
    }
}
