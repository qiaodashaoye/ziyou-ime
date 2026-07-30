package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具面板目录一致性测试：
 * 面板须全量覆盖 [ToolbarItem] 功能栏目录并追加「设置」入口
 * （原功能栏固定设置按钮被 Logo 取代后设置的唯一软键盘入口），
 * 新增功能栏按钮时由本测试保证面板不脱节。
 */
class ToolPanelCatalogTest {

    @Test
    fun `面板全量覆盖功能栏目录且含设置入口`() {
        val tools = ToolPanelCatalog.allTools()
        val codes = tools.map { it.keyCode }
        // 功能栏目录逐项入面板（顺序一致）
        for ((index, item) in ToolbarItem.entries.withIndex()) {
            assertEquals("面板第 $index 项应与功能栏目录同序同码",
                item.keyCode, tools[index].keyCode)
        }
        // 设置入口为面板专属追加项
        assertTrue("面板必须包含设置入口", KeyCode.KEYCODE_OPEN_SETTINGS in codes)
        assertEquals("面板条目 = 功能栏目录 + 设置", ToolbarItem.entries.size + 1, tools.size)
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
