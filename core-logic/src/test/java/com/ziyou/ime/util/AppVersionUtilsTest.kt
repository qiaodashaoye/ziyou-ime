package com.ziyou.ime.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppVersionUtils] 单元测试：应用内更新的版本名对比
 */
class AppVersionUtilsTest {

    @Test
    fun `相同版本号判定相等`() {
        assertEquals(0, AppVersionUtils.compareVersionNames("1.2.3", "1.2.3"))
    }

    @Test
    fun `段数不同缺失段视为零`() {
        assertEquals(0, AppVersionUtils.compareVersionNames("1.2", "1.2.0"))
        assertEquals(0, AppVersionUtils.compareVersionNames("1.2.0.0", "1.2"))
    }

    @Test
    fun `数值比较而非字典序`() {
        // 字典序下 "1.10" < "1.9"，数值比较必须反转
        assertTrue(AppVersionUtils.compareVersionNames("1.10.0", "1.9.0") > 0)
        assertTrue(AppVersionUtils.compareVersionNames("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun `主次修订号逐级比较`() {
        assertTrue(AppVersionUtils.compareVersionNames("1.1.0", "1.0.9") > 0)
        assertTrue(AppVersionUtils.compareVersionNames("1.0.2", "1.0.10") < 0)
    }

    @Test
    fun `前导零不影响数值比较`() {
        assertEquals(0, AppVersionUtils.compareVersionNames("1.02.3", "1.2.3"))
    }

    @Test
    fun `发布版高于预发布标记`() {
        assertTrue(AppVersionUtils.compareVersionNames("1.0", "1.0-beta") > 0)
        assertTrue(AppVersionUtils.compareVersionNames("1.0-alpha", "1.0") < 0)
    }

    @Test
    fun `非数字段按字典序比较`() {
        assertTrue(AppVersionUtils.compareVersionNames("1.0-beta", "1.0-alpha") > 0)
    }

    @Test
    fun `空白与非法输入按零处理不抛异常`() {
        assertEquals(0, AppVersionUtils.compareVersionNames("", "0"))
        assertTrue(AppVersionUtils.compareVersionNames("1.0", "  ") > 0)
        // 非法段视为非数字，确定性地低于数字段即可，不要求具体排序语义
        assertTrue(AppVersionUtils.compareVersionNames("abc", "0") < 0)
    }

    @Test
    fun `超长数字段退化为字典序不溢出`() {
        // 超过 18 位的段不做数值解析，仅保证不抛异常且有确定性结果
        val long = "1." + "9".repeat(30)
        assertEquals(0, AppVersionUtils.compareVersionNames(long, long))
    }

    @Test
    fun `前后空白被忽略`() {
        assertEquals(0, AppVersionUtils.compareVersionNames(" 1.2.3 ", "1.2.3"))
    }
}
