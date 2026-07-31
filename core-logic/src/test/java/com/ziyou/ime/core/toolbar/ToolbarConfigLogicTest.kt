package com.ziyou.ime.core.toolbar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ToolbarConfigLogic 单元测试：清洗、排序与编解码。
 */
class ToolbarConfigLogicTest {

    private val catalog = listOf("skill", "theme", "doodle", "ai", "floating")
    private val fallback = listOf("skill", "floating")

    // ===== sanitize =====

    @Test
    fun `sanitize 保留合法id并维持顺序`() {
        val result = ToolbarConfigLogic.sanitize(
            listOf("ai", "skill", "theme"), catalog, fallback
        )
        assertEquals(listOf("ai", "skill", "theme"), result)
    }

    @Test
    fun `sanitize 剔除未知id与重复项`() {
        val result = ToolbarConfigLogic.sanitize(
            listOf("ai", "unknown", "ai", "floating"), catalog, fallback
        )
        assertEquals(listOf("ai", "floating"), result)
    }

    @Test
    fun `sanitize 清洗后为空时回退默认`() {
        val result = ToolbarConfigLogic.sanitize(
            listOf("legacy1", "legacy2"), catalog, fallback
        )
        assertEquals(fallback, result)
    }

    @Test
    fun `sanitize 空输入回退默认`() {
        assertEquals(fallback, ToolbarConfigLogic.sanitize(emptyList(), catalog, fallback))
    }

    // ===== move =====

    @Test
    fun `move 向前移动一位`() {
        val result = ToolbarConfigLogic.move(listOf("a", "b", "c"), 2, -1)
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun `move 向后移动一位`() {
        val result = ToolbarConfigLogic.move(listOf("a", "b", "c"), 0, 1)
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `move 越界钳制到边界`() {
        val result = ToolbarConfigLogic.move(listOf("a", "b", "c"), 1, 10)
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun `move 首位向前与非法index均原样返回`() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids, ToolbarConfigLogic.move(ids, 0, -1))
        assertEquals(ids, ToolbarConfigLogic.move(ids, -1, 1))
        assertEquals(ids, ToolbarConfigLogic.move(ids, 3, 1))
    }

    // ===== add / remove =====

    @Test
    fun `add 追加到末尾且幂等`() {
        assertEquals(listOf("ai", "skill", "theme"), ToolbarConfigLogic.add(listOf("ai", "skill"), "theme"))
        val ids = listOf("ai", "skill")
        assertEquals(ids, ToolbarConfigLogic.add(ids, "skill"))
    }

    @Test
    fun `remove 剔除目标并保持其余顺序`() {
        assertEquals(listOf("ai", "theme"), ToolbarConfigLogic.remove(listOf("ai", "skill", "theme"), "skill"))
    }

    @Test
    fun `remove 不存在的id原样返回`() {
        val ids = listOf("ai", "skill")
        assertEquals(ids, ToolbarConfigLogic.remove(ids, "doodle"))
    }

    @Test
    fun `remove 拒绝删空最后一项`() {
        val ids = listOf("ai")
        assertEquals(ids, ToolbarConfigLogic.remove(ids, "ai"))
    }

    // ===== encode / decode =====

    @Test
    fun `encode decode 往返一致`() {
        val ids = listOf("skill", "theme", "ai")
        assertEquals(ids, ToolbarConfigLogic.decode(ToolbarConfigLogic.encode(ids)))
    }

    @Test
    fun `decode 空与空白输入返回空列表`() {
        assertEquals(emptyList<String>(), ToolbarConfigLogic.decode(null))
        assertEquals(emptyList<String>(), ToolbarConfigLogic.decode(""))
        assertEquals(emptyList<String>(), ToolbarConfigLogic.decode("  "))
    }

    @Test
    fun `decode 容忍多余空白与空段`() {
        assertEquals(
            listOf("skill", "ai"),
            ToolbarConfigLogic.decode(" skill , , ai ,")
        )
    }
}
