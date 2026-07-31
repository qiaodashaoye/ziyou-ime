package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键盘选择面板候选目录一致性测试：
 * [KeyboardType.PICKER_TYPES] 由 pickerLabel 声明驱动（可持久化主键盘入选单，
 * 符号/数字临时面板不入），新增键盘类型时由本测试保证选单目录不脱节。
 */
class KeyboardTypePickerTest {

    @Test
    fun `选单包含全部主键盘且不含临时面板`() {
        val picker = KeyboardType.PICKER_TYPES
        assertTrue("选单必须包含全键盘", KeyboardType.QWERTY in picker)
        assertTrue("选单必须包含九宫格", KeyboardType.NINE_GRID in picker)
        // 符号/数字为临时面板（不持久化，键盘内「返回」恢复），不作为选单选项
        assertTrue("符号键盘不入选单", KeyboardType.SYMBOL !in picker)
        assertTrue("数字键盘不入选单", KeyboardType.NUMBER !in picker)
    }

    @Test
    fun `选单展示名非空且唯一`() {
        val labels = KeyboardType.PICKER_TYPES.map { it.pickerLabel }
        for (label in labels) {
            assertTrue("入选单的布局必须声明非空展示名", !label.isNullOrBlank())
        }
        assertEquals("选单展示名不允许重复", labels.size, labels.distinct().size)
    }

    @Test
    fun `临时面板不声明选单展示名`() {
        // pickerLabel 是「可持久化主键盘」的标记，与 saveKeyboardType 的
        // 不持久化名单（SYMBOL/NUMBER）保持一致语义
        assertEquals(null, KeyboardType.SYMBOL.pickerLabel)
        assertEquals(null, KeyboardType.NUMBER.pickerLabel)
    }
}
