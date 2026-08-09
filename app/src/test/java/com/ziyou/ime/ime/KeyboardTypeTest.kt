package com.ziyou.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「键盘布局 ↔ 输入方案」映射元数据的回归防线：
 * applyEngineForKeyboard 的方案同步、设置页的方案过滤均以
 * [KeyboardType.forcedSchemaId] / [KeyboardType.allowsSchemaChoice] 为单一来源，
 * 由本测试锁定映射关系，防止新增布局或改动枚举时静默破坏既有约定。
 */
class KeyboardTypeTest {

    @Test
    fun `九宫格强制绑定t9专用方案`() {
        assertEquals(KeyboardType.T9_SCHEMA_ID, KeyboardType.NINE_GRID.forcedSchemaId)
        assertFalse("九宫格不允许自选方案", KeyboardType.NINE_GRID.allowsSchemaChoice)
    }

    @Test
    fun `全键盘不强制方案且允许自选`() {
        assertNull(KeyboardType.QWERTY.forcedSchemaId)
        assertTrue(KeyboardType.QWERTY.allowsSchemaChoice)
    }

    @Test
    fun `符号键盘为临时面板不强制方案也不允许自选`() {
        assertNull(KeyboardType.SYMBOL.forcedSchemaId)
        assertFalse(KeyboardType.SYMBOL.allowsSchemaChoice)
    }

    @Test
    fun `专用方案id集合与各布局声明一致`() {
        // 设置页按此集合过滤，不作为用户选项暴露
        assertEquals(setOf(KeyboardType.T9_SCHEMA_ID), KeyboardType.FORCED_SCHEMA_IDS)
    }

    @Test
    fun `允许自选方案的布局不得同时声明专用方案`() {
        for (type in KeyboardType.entries) {
            if (type.allowsSchemaChoice) {
                assertNull("允许自选方案的布局不应强制专用方案: $type", type.forcedSchemaId)
            }
        }
    }

    @Test
    fun `fromName未知名称回退QWERTY`() {
        assertEquals(KeyboardType.QWERTY, KeyboardType.fromName(null))
        assertEquals(KeyboardType.QWERTY, KeyboardType.fromName("UNKNOWN"))
        assertEquals(KeyboardType.NINE_GRID, KeyboardType.fromName("NINE_GRID"))
        assertEquals(KeyboardType.SYMBOL, KeyboardType.fromName("SYMBOL"))
    }
}
