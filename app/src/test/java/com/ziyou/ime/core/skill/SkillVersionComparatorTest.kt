package com.ziyou.ime.core.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillVersionComparator] 版本比较单元测试。
 */
class SkillVersionComparatorTest {

    @Test
    fun `基本大小比较`() {
        assertTrue(SkillVersionComparator.compare("1.0.0", "1.0.1") < 0)
        assertTrue(SkillVersionComparator.compare("1.1.0", "1.0.9") > 0)
        assertTrue(SkillVersionComparator.compare("2.0", "1.9.9") > 0)
        assertEquals(0, SkillVersionComparator.compare("1.2.3", "1.2.3"))
    }

    @Test
    fun `缺段视为0`() {
        assertEquals(0, SkillVersionComparator.compare("1.0", "1.0.0"))
        assertTrue(SkillVersionComparator.compare("1", "1.0.1") < 0)
    }

    @Test
    fun `多位数字按数值而非字典序`() {
        assertTrue(SkillVersionComparator.compare("1.10.0", "1.9.0") > 0)
        assertTrue(SkillVersionComparator.compare("1.0.100", "1.0.99") > 0)
    }

    @Test
    fun `升级判定严格更高`() {
        assertTrue(SkillVersionComparator.isUpgrade("1.0.0", "1.0.1"))
        assertFalse(SkillVersionComparator.isUpgrade("1.0.0", "1.0.0")) // 同版本拒绝
        assertFalse(SkillVersionComparator.isUpgrade("1.0.1", "1.0.0")) // 降级拒绝
    }
}
