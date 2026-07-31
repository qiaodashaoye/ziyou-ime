package com.ziyou.ime.skin

import com.ziyou.ime.core.skin.SkinPackConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [SkinPackLoader.validateZip] 单元测试：用真实 zip 文件验证皮肤包安装的
 * 安全校验流水线（Zip Slip / 扩展名白名单 / 条目数 / skin.json / 引用资源 /
 * 内置 id 冒充）。合法包必须通过，恶意包必须整包拒绝。
 */
class SkinPackLoaderValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validSkinJson = """
        {
          "specVersion": 1,
          "meta": { "id": "com.test.ocean", "name": "Ocean" },
          "background": { "image": "images/bg.png" }
        }
    """.trimIndent()

    private fun buildZip(vararg entries: Pair<String, ByteArray>): File {
        val file = tempFolder.newFile("pack.zyskin")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    /** 最小合法 PNG 头（校验只看路径与引用，无需真实图像内容） */
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

    @Test
    fun validZip_passes() {
        val zip = buildZip(
            "skin.json" to validSkinJson.toByteArray(),
            "preview.png" to pngBytes,
            "images/bg.png" to pngBytes
        )
        val result = SkinPackLoader.validateZip(zip)
        assertTrue(result is SkinPackLoader.ValidateResult.Ok)
        assertEquals("com.test.ocean", (result as SkinPackLoader.ValidateResult.Ok).spec.meta.id)
    }

    @Test
    fun zipSlipEntry_rejected() {
        val zip = buildZip(
            "skin.json" to validSkinJson.toByteArray(),
            "images/bg.png" to pngBytes,
            "../../evil.png" to pngBytes
        )
        val result = SkinPackLoader.validateZip(zip)
        assertTrue(result is SkinPackLoader.ValidateResult.Bad)
        assertTrue((result as SkinPackLoader.ValidateResult.Bad)
            .errors.any { it.contains("非法包内条目") })
    }

    @Test
    fun executableEntry_rejected() {
        val zip = buildZip(
            "skin.json" to validSkinJson.toByteArray(),
            "images/bg.png" to pngBytes,
            "script.js" to "alert(1)".toByteArray()
        )
        assertTrue(SkinPackLoader.validateZip(zip) is SkinPackLoader.ValidateResult.Bad)
    }

    @Test
    fun missingSkinJson_rejected() {
        val zip = buildZip("preview.png" to pngBytes)
        val result = SkinPackLoader.validateZip(zip)
        assertTrue(result is SkinPackLoader.ValidateResult.Bad)
        assertTrue((result as SkinPackLoader.ValidateResult.Bad)
            .errors.any { it.contains("skin.json") })
    }

    @Test
    fun corruptSkinJson_rejected() {
        val zip = buildZip("skin.json" to "{ not valid".toByteArray())
        assertTrue(SkinPackLoader.validateZip(zip) is SkinPackLoader.ValidateResult.Bad)
    }

    @Test
    fun builtinIdImpersonation_rejected() {
        // builtin. 为保留前缀：现行内置与历史上已移除的内置 id 均不可冒用
        val impostor = """
            { "specVersion": 1, "meta": { "id": "builtin.fake", "name": "Fake" } }
        """.trimIndent()
        val result = SkinPackLoader.validateZip(buildZip("skin.json" to impostor.toByteArray()))
        assertTrue(result is SkinPackLoader.ValidateResult.Bad)
        assertTrue((result as SkinPackLoader.ValidateResult.Bad)
            .errors.any { it.contains("内置皮肤冲突") })
    }

    @Test
    fun missingReferencedResource_rejected() {
        // skin.json 声明了 images/bg.png 但包内不存在
        val result = SkinPackLoader.validateZip(
            buildZip("skin.json" to validSkinJson.toByteArray()))
        assertTrue(result is SkinPackLoader.ValidateResult.Bad)
        assertTrue((result as SkinPackLoader.ValidateResult.Bad)
            .errors.any { it.contains("不存在于包内") })
    }

    @Test
    fun tooManyEntries_rejected() {
        val entries = mutableListOf("skin.json" to validSkinJson.toByteArray(),
            "images/bg.png" to pngBytes)
        repeat(SkinPackConstraints.MAX_ENTRIES) { i ->
            entries += "images/extra_$i.png" to pngBytes
        }
        val result = SkinPackLoader.validateZip(buildZip(*entries.toTypedArray()))
        assertTrue(result is SkinPackLoader.ValidateResult.Bad)
        assertTrue((result as SkinPackLoader.ValidateResult.Bad)
            .errors.any { it.contains("条目数超限") })
    }
}
