package com.ziyou.ime.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案惩罚机制守护测试（防回归门禁）。
 *
 * 背景：候选排序依赖拼写代数的权重标签，这些标签在部署编译期烤入棱镜，
 * 运行时无任何 API 可恢复（[com.ziyou.ime.core.RimeApi] 仅有布尔 option）。
 * 一旦方案文件更新时丢失标签，纠错/模糊音候选将以错误权重参与排序
 * （历史事故：模糊音无标签导致“求职经历”排在“数字经济”之前），
 * 且问题只能在真机输入时发现。本测试在纯 JVM 层断言资产中的方案配置，
 * 使任何破坏惩罚机制的改动在单测阶段即失败。
 *
 * 标签语义（librime algo/calculus.cc）：
 * - `/fuzz`：kFuzzySpelling + log(0.5) 半权，剪枝豁免（候选仍展示，排序让位）
 * - `/correction`：is_correction + log(0.01) 低权，不与精确匹配竞争
 * - 无标签：全权重（仅用于派生拼写不与真实拼音冲突的换位容错）
 */
class SchemaPenaltyConfigTest {

    companion object {
        private val LUNA = File("src/main/assets/rime/luna_pinyin.schema.yaml")
        private val T9 = File("src/main/assets/rime/t9.schema.yaml")

        /** 三条模糊音规则（平翘舌 + 前后鼻音双向），两方案必须一致 */
        private val FUZZY_PATTERNS = listOf(
            "derive/^([zcs])h/\$1",
            "derive/([ei])n$/\$1ng",
            "derive/([ei])ng$/\$1n"
        )

        /** 换位容错规则组：派生拼写不与真实拼音冲突，刻意保持全权重 */
        private val FULL_WEIGHT_SWAP_PATTERNS = listOf(
            "derive/([aeiou])ng$/n\$1g",
            "derive/([aeiou])n$/n\$1",
            "derive/([iu])an$/a\$1n"
        )
    }

    /** 提取 speller/algebra 规则（去除 YAML 注释，仅保留 derive/abbrev/erase 条目） */
    private fun algebraRules(file: File): List<String> {
        assertTrue("方案文件不存在: ${file.path}（单测工作目录应为 app 模块目录）", file.exists())
        return file.readLines()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").substringBefore('#').trim() }
            .filter { it.startsWith("derive/") || it.startsWith("abbrev/") || it.startsWith("erase/") }
    }

    @Test
    fun `模糊音规则必须携带 fuzz 标签（两方案）`() {
        for (file in listOf(LUNA, T9)) {
            val rules = algebraRules(file)
            for (pattern in FUZZY_PATTERNS) {
                assertTrue(
                    "${file.name} 缺少带 fuzz 标签的模糊音规则 $pattern/fuzz —— " +
                        "丢失后模糊匹配将与精确匹配同权重竞争，破坏候选排序",
                    rules.contains("$pattern/fuzz")
                )
            }
        }
    }

    @Test
    fun `禁止出现无标签的模糊音规则`() {
        for (file in listOf(LUNA, T9)) {
            val rules = algebraRules(file)
            for (pattern in FUZZY_PATTERNS) {
                assertFalse(
                    "${file.name} 存在无标签模糊音规则 $pattern —— " +
                        "必须使用 /fuzz 标签施加半权惩罚",
                    rules.contains(pattern)
                )
            }
        }
    }

    @Test
    fun `错拼容错规则组必须携带 correction 标签`() {
        for (file in listOf(LUNA, T9)) {
            val rules = algebraRules(file)
            // 代表性规则抽查（全量规则随方案演进可能增删，抽查核心项）
            for (rule in listOf("derive/un$/uen/correction", "derive/ie$/ei/correction")) {
                assertTrue("${file.name} 缺少容错规则 $rule", rules.contains(rule))
            }
            // ou/uo 与真实音节双向冲突，必须降权
            assertTrue("${file.name} ou→uo 换位必须带 correction 标签", rules.contains("derive/ou$/uo/correction"))
            assertTrue("${file.name} uo→ou 换位必须带 correction 标签", rules.contains("derive/uo$/ou/correction"))
            // 总量下限：防批量误删（当前 luna 15 条 / t9 13 条，下限取保守值）
            val correctionCount = rules.count { it.endsWith("/correction") }
            assertTrue(
                "${file.name} correction 标签规则数=$correctionCount，疑似批量丢失",
                correctionCount >= 10
            )
        }
    }

    @Test
    fun `全键盘启用邻键纠错而九键禁用（性能红线）`() {
        // 按有效配置行断言（注释中也提及 enable_correction，不能用全文包含）
        val lunaLines = LUNA.readLines().map { it.trim() }
        val t9Lines = T9.readLines().map { it.trim() }
        assertTrue(
            "luna_pinyin 必须启用 enable_correction（邻键误触纠正）",
            lunaLines.contains("enable_correction: true")
        )
        // 九键棱镜数字节点稠密，NearSearchCorrector 容错 BFS 剪枝失效导致
        // 单键耗时随编码长度超线性增长，禁止开启（详见 t9.schema.yaml 注释）
        assertFalse(
            "t9 禁止启用 enable_correction —— 会引发容错搜索组合爆炸（性能红线）",
            t9Lines.any { it.startsWith("enable_correction") }
        )
    }

    @Test
    fun `九键换位容错规则组保持全权重`() {
        val rules = algebraRules(T9)
        for (pattern in FULL_WEIGHT_SWAP_PATTERNS) {
            assertTrue(
                "t9 缺少全权重换位规则 $pattern —— 带 correction 标签会使纠错候选沉底",
                rules.contains(pattern)
            )
            assertFalse(
                "t9 换位规则 $pattern 不应带 correction 标签",
                rules.contains("$pattern/correction")
            )
        }
    }

    @Test
    fun `九键数字映射顺序约束（0 前缀英文词规则必须在数字 derive 之后）`() {
        val rules = algebraRules(T9)
        val digitIdx = rules.indexOf("derive/[abc]/2/")
        val zeroIdx = rules.indexOfFirst { it.startsWith("erase/^0") }
        assertTrue("t9 缺少数字映射规则", digitIdx >= 0)
        assertTrue("t9 缺少 0 前缀去标记规则", zeroIdx >= 0)
        assertTrue(
            "t9 的 0 前缀规则($zeroIdx)必须位于数字映射($digitIdx)之后，" +
                "否则英文词整码会以 normal 拼写挤掉中文候选",
            zeroIdx > digitIdx
        )
    }

    @Test
    fun `九键不得挂载 is_in_user_dict（comment 改写切断预览读音源）`() {
        // 回归守护：is_in_user_dict 把 user_phrase 的 comment 改写为 *，
        // 用户词高亮时 PinyinHintProvider 读音源降级到兄弟扫描，曾复现
        // 64426 高亮「你好」但预览显示 ni'gan 的脱钩（用户词升权修复
        // max_homophones:4 后用户词频繁首位，触发面扩大）。九键预览
        // 完整性优先于用户词标记；全拼 rime_frost 挂载不受影响（无预览依赖）
        assertFalse(
            "t9 不得挂载 lua_filter@*is_in_user_dict",
            T9.readText().contains("lua_filter@*is_in_user_dict")
        )
    }
}
