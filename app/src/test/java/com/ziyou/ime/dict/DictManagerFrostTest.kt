package com.ziyou.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DictManager] 白霜迁移 Phase 3 守护单测：
 * 双后端主词库模板与 catalog v4 legacy id 迁移规划（纯逻辑部分）。
 */
class DictManagerFrostTest {

    private fun installed(id: String, enabled: Boolean = true, at: Long = 1L) =
        InstalledDictInfo(id = id, version = "1.0.0", enabled = enabled, installedAt = at)

    // ===== 双后端模板 =====

    @Test
    fun `FROST模板含白霜核心四表且不含英文表`() {
        val content = DictManager.buildMainDictContent(DictManager.DictBackend.FROST, emptyList())
        assertTrue(content.contains("name: rime_frost"))
        assertTrue(content.contains("  - cn_dicts/8105"))
        assertTrue(content.contains("  - cn_dicts/base"))
        assertTrue(content.contains("  - cn_dicts/others"))
        assertTrue(content.contains("  - cn_dicts/corrections"))
        // 英文由 melt_eng 方案独立挂载，不入 FROST 主词库
        assertFalse(content.contains("en_dicts/base"))
    }

    @Test
    fun `LUNA模板保持迁移前既有清单`() {
        val content = DictManager.buildMainDictContent(DictManager.DictBackend.LUNA, emptyList())
        assertTrue(content.contains("name: luna_pinyin"))
        assertTrue(content.contains("  - cn_dicts/8105"))
        assertTrue(content.contains("  - cn_dicts/base"))
        assertTrue(content.contains("  - en_dicts/base"))
        // LUNA 回滚路径不挂 frost 专属表
        assertFalse(content.contains("cn_dicts/corrections"))
    }

    @Test
    fun `启用扩展词库按ext_dicts路径追加在基础表之后`() {
        val dicts = listOf(installed("frost_ext"), installed("legacy_tencent", enabled = true))
        val content = DictManager.buildMainDictContent(DictManager.DictBackend.FROST, dicts)
        val lines = content.lines()
        val baseIdx = lines.indexOfFirst { it.trim() == "- cn_dicts/corrections" }
        val extIdx = lines.indexOfFirst { it.trim() == "- ext_dicts/frost_ext" }
        val tencentIdx = lines.indexOfFirst { it.trim() == "- ext_dicts/legacy_tencent" }
        assertTrue(baseIdx >= 0 && extIdx > baseIdx && tencentIdx > extIdx)
        assertTrue(content.contains("..."))
    }

    // ===== legacy id 迁移规划 =====

    @Test
    fun `legacy旧id幂等改名`() {
        val installed = listOf(installed("ext", at = 1), installed("tencent", at = 2), installed("others", at = 3))
        val migrated = DictManager.planLegacyIdMigration(installed)
        assertEquals(listOf("legacy_ext", "legacy_tencent", "legacy_others"), migrated.map { it.id })
        // 其余字段保留
        assertEquals(1L, migrated[0].installedAt)
        assertTrue(migrated.all { it.enabled })
    }

    @Test
    fun `无legacy旧id时零改动返回原列表`() {
        val installed = listOf(installed("frost_ext"), installed("poetry_predict"))
        val migrated = DictManager.planLegacyIdMigration(installed)
        // 引用相等即零开销 no-op（regenerateMainDict 每次启动都会走此路径）
        assertSame(installed, migrated)
    }

    @Test
    fun `二次迁移幂等不重复处理`() {
        val installed = listOf(installed("legacy_ext"), installed("legacy_tencent"))
        assertSame(installed, DictManager.planLegacyIdMigration(installed))
    }

    @Test
    fun `新id已占用时丢弃旧记录避免重复注入`() {
        // 历史已迁移出 legacy_ext，旧 ext 记录残留 → 丢弃旧记录而非双注入
        val installed = listOf(installed("ext", at = 1), installed("legacy_ext", at = 2))
        val migrated = DictManager.planLegacyIdMigration(installed)
        assertEquals(listOf("legacy_ext"), migrated.map { it.id })
        assertEquals(2L, migrated[0].installedAt)
    }

    @Test
    fun `legacy与非legacy混排仅迁移命中项`() {
        val installed = listOf(installed("frost_cell_geo", at = 1), installed("ext", at = 2))
        val migrated = DictManager.planLegacyIdMigration(installed)
        assertEquals(listOf("frost_cell_geo", "legacy_ext"), migrated.map { it.id })
    }

    // ===== 模型字段 =====

    @Test
    fun `deprecatedBy默认空串保证旧catalog向后兼容`() {
        val info = RemoteDictInfo(
            id = "ext", name = "x", category = "base", description = "",
            version = "1.0.0", url = "https://gitee.com/x/y", size = 1, author = "a"
        )
        assertEquals("", info.deprecatedBy)
    }
}
