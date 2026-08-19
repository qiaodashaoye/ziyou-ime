package com.ziyou.ime.core.skill

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillPanelSpecTest {

    @Test
    fun `合法区间内的比例原样返回`() {
        assertEquals(0.6f, SkillPanelSpec.clampHeightRatio(0.6f))
        assertEquals(1.0f, SkillPanelSpec.clampHeightRatio(1.0f))
        assertEquals(SkillPanelSpec.MIN_HEIGHT_RATIO,
            SkillPanelSpec.clampHeightRatio(SkillPanelSpec.MIN_HEIGHT_RATIO))
        assertEquals(SkillPanelSpec.MAX_HEIGHT_RATIO,
            SkillPanelSpec.clampHeightRatio(SkillPanelSpec.MAX_HEIGHT_RATIO))
    }

    @Test
    fun `越界比例钳制到边界`() {
        assertEquals(SkillPanelSpec.MIN_HEIGHT_RATIO, SkillPanelSpec.clampHeightRatio(0.1f))
        assertEquals(SkillPanelSpec.MIN_HEIGHT_RATIO, SkillPanelSpec.clampHeightRatio(-3f))
        assertEquals(SkillPanelSpec.MAX_HEIGHT_RATIO, SkillPanelSpec.clampHeightRatio(5f))
    }

    @Test
    fun `非有限值回退默认比例`() {
        assertEquals(SkillPanelSpec.DEFAULT_HEIGHT_RATIO, SkillPanelSpec.clampHeightRatio(Float.NaN))
        assertEquals(SkillPanelSpec.DEFAULT_HEIGHT_RATIO,
            SkillPanelSpec.clampHeightRatio(Float.POSITIVE_INFINITY))
        assertEquals(SkillPanelSpec.DEFAULT_HEIGHT_RATIO,
            SkillPanelSpec.clampHeightRatio(Float.NEGATIVE_INFINITY))
    }

    private fun assertEquals(expected: Float, actual: Float) =
        assertEquals(expected, actual, 0f)
}
