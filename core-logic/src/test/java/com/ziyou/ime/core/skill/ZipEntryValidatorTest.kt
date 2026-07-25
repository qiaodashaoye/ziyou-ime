package com.ziyou.ime.core.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZipEntryValidator] 路径安全校验单元测试（Zip Slip 防护）。
 */
class ZipEntryValidatorTest {

    @Test
    fun `普通相对路径合法`() {
        assertTrue(ZipEntryValidator.isSafeRelativePath("index.html"))
        assertTrue(ZipEntryValidator.isSafeRelativePath("assets/icon.png"))
        assertTrue(ZipEntryValidator.isSafeRelativePath("a/b/c/d.js"))
        assertTrue(ZipEntryValidator.isSafeRelativePath("manifest.json"))
    }

    @Test
    fun `父目录逃逸被拒绝`() {
        assertFalse(ZipEntryValidator.isSafeRelativePath("../evil.sh"))
        assertFalse(ZipEntryValidator.isSafeRelativePath("a/../../evil.sh"))
        assertFalse(ZipEntryValidator.isSafeRelativePath(".."))
        assertFalse(ZipEntryValidator.isSafeRelativePath("a/.."))
    }

    @Test
    fun `绝对路径与盘符被拒绝`() {
        assertFalse(ZipEntryValidator.isSafeRelativePath("/etc/passwd"))
        assertFalse(ZipEntryValidator.isSafeRelativePath("C:\\Windows\\evil"))
        assertFalse(ZipEntryValidator.isSafeRelativePath("c:/evil"))
    }

    @Test
    fun `反斜杠与NUL字符被拒绝`() {
        assertFalse(ZipEntryValidator.isSafeRelativePath("a\\b.html"))
        assertFalse(ZipEntryValidator.isSafeRelativePath("a\u0000.html"))
    }

    @Test
    fun `空路径与空路径段被拒绝`() {
        assertFalse(ZipEntryValidator.isSafeRelativePath(""))
        assertFalse(ZipEntryValidator.isSafeRelativePath("   "))
        assertFalse(ZipEntryValidator.isSafeRelativePath("a//b.html"))
        assertFalse(ZipEntryValidator.isSafeRelativePath("./index.html"))
    }

    @Test
    fun `超长路径被拒绝`() {
        val longPath = "a/".repeat(200) + "x.html"
        assertFalse(ZipEntryValidator.isSafeRelativePath(longPath))
    }
}
