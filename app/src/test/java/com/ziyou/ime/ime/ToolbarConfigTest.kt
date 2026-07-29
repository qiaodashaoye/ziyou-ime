package com.ziyou.ime.ime

import com.ziyou.ime.data.ToolbarConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能栏按钮目录与配置预设的一致性测试：
 * 预设/默认配置以字符串 id 引用 [ToolbarItem]，编译器无法校验，
 * 由本测试保证两处定义不脱节（新增按钮或改预设时的回归防线）。
 */
class ToolbarConfigTest {

    @Test
    fun `目录id唯一且fromId往返一致`() {
        val ids = ToolbarItem.entries.map { it.id }
        assertEquals("按钮 id 不允许重复", ids.size, ids.distinct().size)
        for (item in ToolbarItem.entries) {
            assertEquals(item, ToolbarItem.fromId(item.id))
        }
        assertEquals(null, ToolbarItem.fromId("unknown"))
    }

    @Test
    fun `目录功能码唯一且展示文本非空`() {
        val codes = ToolbarItem.entries.map { it.keyCode }
        assertEquals("功能码不允许重复", codes.size, codes.distinct().size)
        for (item in ToolbarItem.entries) {
            assertTrue("label 不能为空: ${item.id}", item.label.isNotBlank())
            assertTrue("description 不能为空: ${item.id}", item.description.isNotBlank())
        }
    }

    @Test
    fun `默认配置的id全部存在于目录且无重复`() {
        val defaults = ToolbarConfigRepository.DEFAULT_IDS
        assertTrue(defaults.isNotEmpty())
        assertEquals(defaults.size, defaults.distinct().size)
        for (id in defaults) {
            assertTrue("默认配置引用了不存在的按钮: $id", ToolbarItem.fromId(id) != null)
        }
    }

    @Test
    fun `预设模板的id全部存在于目录且无重复`() {
        assertTrue(ToolbarConfigRepository.PRESETS.isNotEmpty())
        for (preset in ToolbarConfigRepository.PRESETS) {
            assertTrue("预设不能为空: ${preset.name}", preset.itemIds.isNotEmpty())
            assertEquals(
                "预设内按钮重复: ${preset.name}",
                preset.itemIds.size, preset.itemIds.distinct().size
            )
            for (id in preset.itemIds) {
                assertTrue("预设「${preset.name}」引用了不存在的按钮: $id", ToolbarItem.fromId(id) != null)
            }
        }
    }

    @Test
    fun `默认配置按钮顺序符合预期`() {
        // 设置（settings）与收起键盘（hide）已改为功能栏常驻固定按钮，
        // 中英切换（language）、符号键盘（symbol）与图片选择（image）已移除，
        // 粘贴板（clipboard）与方案切换（schema）为新增按钮，其余按钮保持历史顺序不变
        assertEquals(
            listOf("theme", "schema", "doodle", "skill", "ai", "clipboard", "floating"),
            ToolbarConfigRepository.DEFAULT_IDS
        )
    }

    @Test
    fun `已移除按钮不在目录中`() {
        // settings/hide 常驻固定、language/symbol/image 已下线：目录与配置均不得再出现，
        // 历史配置中的这些 id 由 ToolbarConfigLogic.sanitize 自动清洗
        for (id in listOf("settings", "hide", "language", "symbol", "image")) {
            assertEquals("已移除按钮仍在目录: $id", null, ToolbarItem.fromId(id))
            assertTrue("默认配置仍含已移除按钮: $id", id !in ToolbarConfigRepository.DEFAULT_IDS)
            for (preset in ToolbarConfigRepository.PRESETS) {
                assertTrue("预设「${preset.name}」仍含已移除按钮: $id", id !in preset.itemIds)
            }
        }
    }
}
