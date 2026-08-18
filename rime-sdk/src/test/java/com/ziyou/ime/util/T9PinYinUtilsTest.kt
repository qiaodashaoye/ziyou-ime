package com.ziyou.ime.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T9PinYinUtils] 双向映射回归测试。
 *
 * 覆盖：数字键序列 → 候选拼音（由长到短、去重保序）、拼音 → 数字键序列（O(1) 反查）、
 * 单字符映射、非法输入边界，以及正反向往返一致性。
 */
class T9PinYinUtilsTest {

    @Test
    fun t9KeyToPinyin_486_returnsMultiSyllableCandidatesLongestFirst() {
        // 486 → 组代表字母 GTM → pinyinMap["GTM"] = "gun,guo,hun,huo"
        val result = T9PinYinUtils.t9KeyToPinyin("486")
        // 由长到短匹配：先给出完整音节（3 位）再给更短前缀
        assertArrayEquals(
            arrayOf("gun", "guo", "hun", "huo"),
            result.copyOfRange(0, 4)
        )
        assertTrue("应包含 guo", result.contains("guo"))
    }

    @Test
    fun t9KeyToPinyin_nullOrEmpty_returnsEmpty() {
        assertArrayEquals(emptyArray<String>(), T9PinYinUtils.t9KeyToPinyin(null))
        assertArrayEquals(emptyArray<String>(), T9PinYinUtils.t9KeyToPinyin(""))
    }

    @Test
    fun t9KeyToPinyin_illegalDigit_returnsEmpty() {
        // '1' 不在 2-9 数字键映射内，整体视为非法
        assertArrayEquals(emptyArray<String>(), T9PinYinUtils.t9KeyToPinyin("1"))
        assertArrayEquals(emptyArray<String>(), T9PinYinUtils.t9KeyToPinyin("40a"))
    }

    @Test
    fun pinyin2Key_guo_returns486() {
        assertEquals("486", T9PinYinUtils.pinyin2Key("guo"))
    }

    @Test
    fun pinyin2Key_unknownOrEmpty_returnsEmptyString() {
        assertEquals("", T9PinYinUtils.pinyin2Key(""))
        assertEquals("", T9PinYinUtils.pinyin2Key(null))
        assertEquals("", T9PinYinUtils.pinyin2Key("zzz"))
    }

    @Test
    fun roundTrip_pinyinToKeyToPinyin_isConsistent() {
        val samples = listOf("guo", "hao", "zhang", "shuang", "ni", "wo")
        for (py in samples) {
            val key = T9PinYinUtils.pinyin2Key(py)
            assertTrue("拼音 $py 应能反查到数字键", key.isNotEmpty())
            assertTrue(
                "数字键 $key 还原的候选应包含原拼音 $py",
                T9PinYinUtils.t9KeyToPinyin(key).contains(py)
            )
        }
    }

    @Test
    fun pinyin2T9Key_mapsLetterToGroupRepresentative() {
        assertEquals('A', T9PinYinUtils.pinyin2T9Key('a'))
        assertEquals('W', T9PinYinUtils.pinyin2T9Key('z'))
        // 非字母字符原样返回
        assertEquals('2', T9PinYinUtils.pinyin2T9Key('2'))
    }
}
