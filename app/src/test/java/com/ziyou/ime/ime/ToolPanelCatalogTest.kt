package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具面板目录一致性测试：
 * 面板网格须全量覆盖 [ToolbarItem] 功能栏目录；「设置」入口已移至
 * 面板标题栏（[ToolPanelView]「编辑」按钮左侧），不再入网格目录，
 * 新增功能栏按钮时由本测试保证面板不脱节。
 */
class ToolPanelCatalogTest {

    @Test
    fun `面板全量覆盖功能栏目录且设置不入网格`() {
        val tools = ToolPanelCatalog.allTools()
        val codes = tools.map { it.keyCode }
        // 功能栏目录逐项入面板（顺序一致，toolbarId 回指目录 id 供编辑模式增删排序）
        for ((index, item) in ToolbarItem.entries.withIndex()) {
            assertEquals("面板第 $index 项应与功能栏目录同序同码",
                item.keyCode, tools[index].keyCode)
            assertEquals("面板第 $index 项 toolbarId 应回指目录 id",
                item.id, tools[index].toolbarId)
        }
        // 设置入口移至面板标题栏（编辑按钮左侧），网格目录不再包含
        assertTrue("设置入口不应在面板网格目录内",
            KeyCode.KEYCODE_OPEN_SETTINGS !in codes)
        assertEquals("面板条目 = 功能栏目录", ToolbarItem.entries.size, tools.size)
    }

    @Test
    fun `面板条目功能码唯一且展示文本非空`() {
        val tools = ToolPanelCatalog.allTools()
        val codes = tools.map { it.keyCode }
        assertEquals("功能码不允许重复", codes.size, codes.distinct().size)
        for (tool in tools) {
            assertTrue("label 不能为空: ${tool.name}", tool.label.isNotBlank())
            assertTrue("name 不能为空: ${tool.keyCode}", tool.name.isNotBlank())
        }
    }
}
