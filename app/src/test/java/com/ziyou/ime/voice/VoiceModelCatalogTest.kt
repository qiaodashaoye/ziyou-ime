package com.ziyou.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VoiceModelCatalog] 目录约束测试：
 * 硬编码清单是模型下载与加载的单一来源，id/文件名的合法性在此把关。
 */
class VoiceModelCatalogTest {

    @Test
    fun `id 为合法目录名（无路径分隔符与相对路径）`() {
        for (spec in VoiceModelCatalog.ALL) {
            assertTrue("非法 id: ${spec.id}", spec.id.isNotEmpty())
            assertTrue("id 含路径分隔符: ${spec.id}", !spec.id.contains('/') && !spec.id.contains('\\'))
            assertTrue("id 含相对路径: ${spec.id}", !spec.id.contains(".."))
        }
    }

    @Test
    fun `每个模型必备 tokens 与三段权重`() {
        for (spec in VoiceModelCatalog.ALL) {
            assertTrue("${spec.id} 缺 tokens.txt", "tokens.txt" in spec.files)
            assertTrue("${spec.id} 缺 encoder", spec.files.any { it.startsWith("encoder") && it.endsWith(".onnx") })
            assertTrue("${spec.id} 缺 decoder", spec.files.any { it.startsWith("decoder") && it.endsWith(".onnx") })
            assertTrue("${spec.id} 缺 joiner", spec.files.any { it.startsWith("joiner") && it.endsWith(".onnx") })
        }
    }

    @Test
    fun `文件名不含路径成分（只允许平铺文件）`() {
        for (spec in VoiceModelCatalog.ALL) {
            for (file in spec.files) {
                assertTrue("${spec.id} 文件名含路径: $file", !file.contains('/'))
            }
        }
    }

    @Test
    fun `默认模型在目录内且出处为 HTTPS`() {
        assertTrue(VoiceModelCatalog.byId(VoiceModelCatalog.DEFAULT.id) != null)
        for (spec in VoiceModelCatalog.ALL) {
            assertTrue("${spec.id} 出处非 HTTPS", spec.sourceUrl.startsWith("https://"))
        }
    }

    @Test
    fun `byId 未知 id 返回 null`() {
        assertNotNull(VoiceModelCatalog.byId(VoiceModelCatalog.ZH_STANDARD.id))
        assertNull(VoiceModelCatalog.byId("no-such-model"))
    }

    @Test
    fun `id 全局唯一`() {
        val ids = VoiceModelCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `每个文件都有 sha256 锚定值且为合法十六进制`() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (spec in VoiceModelCatalog.ALL) {
            for (file in spec.files) {
                val hash = spec.sha256s[file]
                assertNotNull("${spec.id} 缺 $file 的 sha256", hash)
                assertTrue("${spec.id}/$file 哈希格式非法: $hash", hash!!.matches(hex))
            }
        }
    }
}
