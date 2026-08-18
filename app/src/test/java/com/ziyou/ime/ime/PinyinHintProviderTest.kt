package com.ziyou.ime.ime

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.CompositionProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.core.MenuProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinyinHintProvider] 纯逻辑回归测试。
 *
 * 覆盖：从数字输入串还原候选拼音、回退到候选 comment、
 * 预览与高亮候选读音同源且长度匹配实际击键、分段确认后的汉字前缀展示、空上下文边界。
 */
class PinyinHintProviderTest {

    private fun candidate(text: String, comment: String) =
        CandidateProto(text = text, comment = comment, label = "")

    private fun context(
        input: String,
        candidates: List<CandidateProto> = emptyList(),
        highlighted: Int = 0,
        preedit: String? = null,
        selStartCodePoints: Int = 0
    ): ContextProto {
        val menu = if (candidates.isEmpty()) null else MenuProto(
            pageSize = candidates.size,
            pageNumber = 0,
            isLastPage = true,
            highlightedCandidateIndex = highlighted,
            candidates = candidates.toTypedArray(),
            selectKeys = "",
            selectLabels = emptyArray()
        )
        // 与 JNI 层（utf8::unchecked::distance）一致：各偏移均按 Unicode 码点计
        val composition = preedit?.let {
            val codePoints = it.codePointCount(0, it.length)
            CompositionProto(
                length = codePoints,
                cursorPos = codePoints,
                selStart = selStartCodePoints,
                selEnd = codePoints,
                preedit = it,
                commitTextPreview = null
            )
        }
        return ContextProto(composition = composition, menu = menu, input = input, caretPos = input.length)
    }

    @Test
    fun buildHints_fromDigitSegment_returnsT9Pinyins() {
        // 输入 486 → guo/gun/huo/hun...
        val hints = PinyinHintProvider.buildHints(context(input = "486"))
        assertTrue(hints != null && hints.contains("guo"))
    }

    @Test
    fun buildHints_fromLockedPinyinPlusDigits_extractsFirstDigitSegment() {
        // "guo'486" → 首个数字段是 486
        val hints = PinyinHintProvider.buildHints(context(input = "guo'486"))
        assertTrue(hints != null && hints.contains("guo"))
    }

    @Test
    fun buildHints_noDigitSegment_fallsBackToCandidateComments() {
        val ctx = context(
            input = "guo",
            candidates = listOf(candidate("过", "guo"), candidate("国", "guo"))
        )
        val hints = PinyinHintProvider.buildHints(ctx)
        assertEquals(listOf("guo"), hints)
    }

    @Test
    fun buildHints_nullContext_returnsNull() {
        assertNull(PinyinHintProvider.buildHints(null))
    }

    @Test
    fun buildHints_fallback_skipsEnglishWordMarkedComments() {
        // 内置英文词/表情候选的注音是标记码（如 0ok，含数字），
        // 回退提取时必须跳过，不能当拼音提示展示
        val ctx = context(
            input = "guo",
            candidates = listOf(candidate("👌", "0ok"), candidate("过", "guo"))
        )
        assertEquals(listOf("guo"), PinyinHintProvider.buildHints(ctx))
    }

    @Test
    fun buildPreview_followsHighlightedCandidatePinyin() {
        // 核心缺陷场景：输入 48，首位候选是"乎"(hu)，
        // 编码区必须展示 hu（与候选读音一致），而非本地 T9 表序的 gu
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "hu"), candidate("顾", "gu")),
            highlighted = 0
        )
        assertEquals("hu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_highlightSwitch_followsNewCandidate() {
        // 高亮切到"顾"(gu) 时，编码区同步展示 gu
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "hu"), candidate("顾", "gu")),
            highlighted = 1
        )
        assertEquals("gu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_partialSyllable_truncatesToKeyCount() {
        // 仅击 1 键（4），候选"好"完整拼音 hao：只展示已击键部分 h，不展示完整拼音
        val ctx = context(
            input = "4",
            candidates = listOf(candidate("好", "hao")),
            highlighted = 0
        )
        assertEquals("h", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_multiSyllable_alignsCommentSyllables() {
        // 连续击键 64426（ni=64, hao=426），候选"你好"comment "ni hao" → ni'hao
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("你好", "ni hao")),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_lockedPinyinKeptVerbatim() {
        // 已锁定拼音 guo 原样保留；数字段 486 按候选读音第二音节 hun 还原
        val ctx = context(
            input = "guo'486",
            candidates = listOf(candidate("国魂", "guo hun")),
            highlighted = 0
        )
        assertEquals("guo'hun", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_noCandidate_fallsBackToLocalT9Table() {
        // 无候选时回退本地 T9 表：486 → GTM 首个等长匹配 gun
        assertEquals("gun", PinyinHintProvider.buildPreview(context(input = "486")))
    }

    @Test
    fun buildPreview_incompatibleComment_fallsBackToLocalT9Table() {
        // 候选读音与击键不兼容（ma 的键序 62 与 486 无任何前缀关系）时回退本地还原
        val ctx = context(
            input = "486",
            candidates = listOf(candidate("妈", "ma")),
            highlighted = 0
        )
        assertEquals("gun", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_letterCountAlwaysMatchesKeyCount() {
        // 无论是否有候选消歧，预览字母总数必须等于击键数
        val preview = PinyinHintProvider.buildPreview(context(input = "64426"))
        assertEquals(5, preview!!.replace("'", "").length)
    }

    @Test
    fun buildPreview_emptyInput_returnsNull() {
        assertNull(PinyinHintProvider.buildPreview(context(input = "")))
        assertNull(PinyinHintProvider.buildPreview(null))
    }

    // ===== 分段确认（部分选词）=====

    @Test
    fun buildPreview_partialConfirm_showsHanziPrefixPlusRemainingPinyin() {
        // nihao 击键 64426，选“你”分段确认：preedit "你426"、selStart=1（码点偏移），
        // 状态机已确认原始键 2 个（ni=64），候选已切到 hao 段 → 编码区展示 你hao
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("好", "hao")),
            preedit = "你426",
            selStartCodePoints = 1
        )
        assertEquals("你hao", PinyinHintProvider.buildPreview(ctx, confirmedRawLength = 2))
    }

    @Test
    fun buildPreview_partialConfirm_multiHanziPrefix_noMojibake() {
        // 乱码回归：多字确认前缀（码点偏移 2）必须按码点切分，
        // 若误当字节/字符偏移会截断多字节汉字产生 �
        // 击键 64426+94（ni hao 已确认，94 是 xia=942 的前缀 → 截断展示 xi）
        val ctx = context(
            input = "6442694",
            candidates = listOf(candidate("下", "xia")),
            preedit = "你好94",
            selStartCodePoints = 2
        )
        val preview = PinyinHintProvider.buildPreview(ctx, confirmedRawLength = 5)
        assertEquals("你好xi", preview)
        assertTrue(!preview!!.contains('\uFFFD'))
    }

    @Test
    fun buildPreview_secondPartialConfirm_accumulatesConfirmedPrefix() {
        // 连续分段确认（逐段空格确认 你→好）：confirmedRawLength 累计为 5（ni+hao），
        // 预览保持「汉字前缀 + 剩余拼音」混合形态，已确认音节不得被打回数字
        val ctx = context(
            input = "6442662",
            candidates = listOf(candidate("吗", "ma")),
            preedit = "你好62",
            selStartCodePoints = 2
        )
        assertEquals("你好ma", PinyinHintProvider.buildPreview(ctx, confirmedRawLength = 5))
    }

    @Test
    fun buildPreview_partialConfirmWithoutTrustedOffset_returnsNull() {
        // 引擎存在确认段但状态机降级（无可信确认偏移）→ 返回 null 回退 Rime 原始 preedit
        val ctx = context(input = "64426", preedit = "你426", selStartCodePoints = 1)
        assertNull(PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_noConfirm_zeroSelStartKeepsLegacyBehavior() {
        // 未分段确认（selStart=0）时行为与无 composition 完全一致
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "hu")),
            preedit = "48",
            selStartCodePoints = 0
        )
        assertEquals("hu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildHints_partialConfirm_targetsFirstUnconfirmedSegment() {
        // 分段确认后侧栏提示必须针对未确认段 426（hao 等），而非已确认的 64
        val ctx = context(input = "64426", preedit = "你426", selStartCodePoints = 1)
        val hints = PinyinHintProvider.buildHints(ctx, confirmedRawLength = 2)
        assertTrue(hints != null && hints.contains("hao"))
        assertTrue(hints != null && !hints.contains("ni"))
    }

    @Test
    fun buildHints_partialConfirmWithoutTrustedOffset_fallsBackToComments() {
        // 降级态：不从原始串取数字段（会错指已确认段），回退到候选 comment
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("好", "hao")),
            preedit = "你426",
            selStartCodePoints = 1
        )
        assertEquals(listOf("hao"), PinyinHintProvider.buildHints(ctx))
    }

    // ===== T9 白霜深度集成：comment 契约归一化（缺陷修复：拼音与首候选脱钩）=====

    @Test
    fun buildHints_fallback_extractsWrappedComments() {
        // comment_format 包裹形态［guo］：归一化剥离［］后提取内层拼音，
        // 与净形态 comment 去重后等价（防配置回退再次切断读音源）
        val ctx = context(
            input = "guo",
            candidates = listOf(candidate("过", "［guo］"), candidate("国", "guo"))
        )
        assertEquals(listOf("guo"), PinyinHintProvider.buildHints(ctx))
    }

    @Test
    fun buildHints_fallback_allCommentsWrapped_extractsPinyins() {
        // 全部候选 comment 均被包裹时仍能提取拼音列表（旧行为返回 null，
        // 归一化后侧栏提示不再降级为符号模式）
        val ctx = context(
            input = "guo",
            candidates = listOf(candidate("过", "［guo］"), candidate("国", "［guo］"))
        )
        assertEquals(listOf("guo"), PinyinHintProvider.buildHints(ctx))
    }

    @Test
    fun buildHints_fallback_skipsUserDictMarkComments() {
        // is_in_user_dict 将用户词/联想句 comment 改写为星号/∞，不过白名单需跳过
        val ctx = context(
            input = "guo",
            candidates = listOf(
                candidate("过", "*"),
                candidate("国魂", "∞"),
                candidate("国", "guo")
            )
        )
        assertEquals(listOf("guo"), PinyinHintProvider.buildHints(ctx))
    }

    @Test
    fun buildHints_mainPath_unaffectedByCommentContract() {
        // 主路径（T9PinYinUtils 本地还原数字段）与 comment 契约无关：
        // 即使全部 comment 被包裹，486 仍还原出 guo 等候选拼音
        val ctx = context(
            input = "486",
            candidates = listOf(candidate("过", "［guo］"))
        )
        val hints = PinyinHintProvider.buildHints(ctx)
        assertTrue(hints != null && hints.contains("guo"))
    }

    @Test
    fun buildPreview_followsHighlightedCandidate_wrappedComment() {
        // 缺陷修复守护：包裹形态 comment 归一化后仍能驱动预览消歧，
        // 编码区拼音与高亮候选读音同源（不再脱钩回退本地表）
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "［hu］"), candidate("顾", "［gu］")),
            highlighted = 0
        )
        assertEquals("hu", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_emptyComment_fallsBackToLocalT9Table() {
        // 菜单内无任何可用 comment（如 keep_comments 配置回退）时预览不崩：
        // 回退本地 T9 表还原，字母数与击键数一致（数字不上屏）
        val ctx = context(
            input = "48",
            candidates = listOf(candidate("乎", "")),
            highlighted = 0
        )
        val preview = PinyinHintProvider.buildPreview(ctx)
        assertEquals(2, preview!!.length)
        assertTrue(preview.all { it.isLetter() })
    }

    // ===== 首候选脱钩缺陷回归（custom_phrase 数字码/用户词星号标记）=====

    @Test
    fun buildPreview_customPhraseNoComment_scansSameLengthSiblings() {
        // 缺陷复现场景：custom_phrase 你好固顶但 table 候选无拼音 comment，
        // 旧行为回退本地表还原为 mi'han（字母序首匹配）；修复后扫描同字数
        // 兄弟候选的可用 comment，预览与首候选读音同源（ni'hao）
        val ctx = context(
            input = "64426",
            candidates = listOf(
                candidate("你好", ""),
                candidate("尼好", "ni hao"),
                candidate("迷汉", "mi han")
            ),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_userPhraseStarMark_scansNextUsableComment() {
        // 用户词被 is_in_user_dict 改标星号后，预览仍从同码候选取读音
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("你好", "*"), candidate("尼好", "ni hao")),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx))
    }

    @Test
    fun buildPreview_scanPrefersSameTextLengthOverAny() {
        // 同字数优先：单字候选的可用 comment 不得越级覆盖同长度候选读音
        // （64426 的二字词读音是两音节，单字 ni 会导致音节数错位）
        val ctx = context(
            input = "64426",
            candidates = listOf(
                candidate("你好", "64426"),
                candidate("你", "ni"),
                candidate("尼好", "ni hao")
            ),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx))
    }

    // ===== 自定义短语读音字典（根治固顶候选与编码区脱钩）=====

    @Test
    fun buildPreview_customPhraseMap_beatsSiblingScanning() {
        // 缺陷根治场景：高亮「你好」无 comment，兄弟候选含错误读音 ni gan
        // （同码读音空间多成员）；读音字典按词命中精确读音，优先于扫描
        val ctx = context(
            input = "64426",
            candidates = listOf(
                candidate("你好", ""),
                candidate("拟稿", "ni gan"),
                candidate("尼好", "ni hao")
            ),
            highlighted = 0
        )
        val map = mapOf("你好" to "ni hao")
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx, 0, map))
    }

    @Test
    fun buildPreview_customPhraseMap_singleChar() {
        // 单字固顶：输 96 高亮「我」，字典命中 wo（非本地表字母序首匹配）
        val ctx = context(
            input = "96",
            candidates = listOf(candidate("我", ""), candidate("握", "wo")),
            highlighted = 0
        )
        assertEquals("wo", PinyinHintProvider.buildPreview(ctx, 0, mapOf("我" to "wo")))
    }

    @Test
    fun buildPreview_customPhraseMap_missFallsBackToScanning() {
        // 字典未收录的词仍走兄弟扫描兜底（字典不是唯一读音源）
        val ctx = context(
            input = "64426",
            candidates = listOf(candidate("尼好", ""), candidate("你好", "ni hao")),
            highlighted = 0
        )
        assertEquals("ni'hao", PinyinHintProvider.buildPreview(ctx, 0, mapOf("你好" to "ni hao")))
    }

    @Test
    fun parseCustomPhrasePinyins_parsesFourthColumnWithTolerance() {
        val content = """
            # 注释行跳过
            你好	64426	870	ni hao
            我	96	1000	wo
            旧格式三列条目	96	500
            非法拼音丢弃	28	100	bu8
            你好	999	1	重复取首条
        """.trimIndent()
        val map = PinyinHintProvider.parseCustomPhrasePinyins(content)
        assertEquals(2, map.size)
        assertEquals("ni hao", map["你好"])
        assertEquals("wo", map["我"])
    }
}
