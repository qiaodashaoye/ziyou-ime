package com.ziyou.ime.ime

import com.ziyou.ime.core.CandidateProto
import com.ziyou.ime.core.CompositionProto
import com.ziyou.ime.core.ContextProto
import com.ziyou.ime.util.T9PinYinUtils

/**
 * 九宫格拼音提示/预览生成器（纯逻辑，无 Android 依赖，便于单元测试）。
 *
 * 从 [ZiYouInputMethodService] 中剥离，仅依赖 [ContextProto] 与 [T9PinYinUtils]。
 * 键盘类型判定（是否九宫格）由调用方负责，本类只处理"给定上下文时该展示什么"。
 */
object PinyinHintProvider {

    /** 侧栏拼音候选最大展示数量。 */
    private const val MAX_HINTS = 8

    /** 单个音节最多击键数（最长拼音 zhuang = 6 键）。 */
    private const val MAX_SYLLABLE_KEYS = 6

    /**
     * 生成九宫格拼音候选列表。
     * 优先从 Rime 原始输入串的**未确认部分**提取"首个未消歧数字段"，
     * 用本地 T9 表还原候选拼音（更精确）；
     * 回退到从候选词 comment（spelling_hints）提取。
     *
     * @param confirmedRawLength 引擎已确认段在原始输入串中占用的字符数
     *        （来自九宫格状态机；分段确认后提示必须针对首个**未确认**音节）
     * @return 候选拼音列表；无可用提示返回 null。
     */
    fun buildHints(context: ContextProto?, confirmedRawLength: Int = 0): List<String>? {
        if (context == null) return null
        // 优先：从未确认输入（数字与已锁定拼音 + 分词符混排，如 "guo'486"）提取首个数字段；
        // 引擎存在确认段而确认偏移不可信（降级态）时跳过，直接走 comment 回退
        val digitSegment = unconfirmedInput(context, confirmedRawLength)
            ?.split('\'', ' ')
            ?.firstOrNull { seg -> seg.isNotEmpty() && seg.all { it in '2'..'9' } }
        if (digitSegment != null) {
            val pinyins = T9PinYinUtils.t9KeyToPinyin(digitSegment).filter { it.isNotBlank() }
            if (pinyins.isNotEmpty()) return pinyins.take(MAX_HINTS)
        }
        // 回退：从候选词 comment 提取（分段确认后候选已是未确认段的，天然对齐）；
        // 经 [normalizePinyinComment] 统一归一化：剥离［］包裹、丢弃 */∞ 标记与
        // 含数字的标记码（如内置英文词注音 0ok）
        val candidates = context.menu?.candidates ?: return null
        if (candidates.isEmpty()) return null
        val hints = LinkedHashSet<String>()
        for (candidate in candidates) {
            val py = normalizePinyinComment(candidate.comment)
            if (py != null) hints.add(py)
            if (hints.size >= MAX_HINTS) break
        }
        return hints.toList().takeIf { it.isNotEmpty() }
    }

    /**
     * 生成顶部编码区的"当前拼音"单串预览。
     *
     * 以高亮候选的真实读音（spelling_hints comment）为消歧依据，
     * 同时以用户实际击键（[ContextProto.input]）为长度约束：
     * - 引擎已确认前缀（分段确认产生的汉字，如“你”）原样展示在最前；
     * - 已锁定拼音段原样展示；
     * - 未消歧数字段逐音节对齐候选读音：击键覆盖完整音节则展示该音节，
     *   音节未打完则截断到实际击键数，确保字母数与击键数一一对应；
     * - 无候选或读音与击键不兼容时，回退到本地 T9 表还原。
     *
     * 这样编码区与候选区始终同源：选“你”后编码区展示 你hao（主流输入法行为）。
     *
     * @param confirmedRawLength 引擎已确认段在原始输入串中占用的字符数（来自九宫格状态机）
     * @return 预览串（未确认音节间以 ' 分隔）；无可用内容或确认偏移不可信（降级态）
     *         返回 null，由调用方回退到 Rime 原始 preedit。
     */
    fun buildPreview(context: ContextProto?, confirmedRawLength: Int = 0): String? {
        if (context == null) return null
        val confirmed = confirmedPrefix(context.composition).orEmpty()
        // 确认偏移不可信（如同步失败后栈已降级清空）时返回 null，回退 Rime 原始 preedit
        val input = unconfirmedInput(context, confirmedRawLength) ?: return null
        if (input.isBlank()) return confirmed.takeIf { it.isNotEmpty() }
        val segments = input.split('\'', ' ').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return confirmed.takeIf { it.isNotEmpty() }
        // 高亮候选的读音音节队列，供数字段逐音节消费对齐
        // （分段确认后候选仅覆盖未确认部分，与未确认输入天然对齐）
        val syllables = ArrayDeque(highlightedSyllables(context))
        val body = segments.joinToString("'") { seg ->
            if (seg.all { it in '2'..'9' }) {
                renderDigitRun(seg, syllables)
            } else {
                // 已锁定拼音段：原样展示；候选读音覆盖该段时同步消费对应音节
                if (syllables.firstOrNull() == seg) syllables.removeFirst()
                seg
            }
        }
        return confirmed + body
    }

    /**
     * 提取原始输入串中「未被引擎确认」的部分。
     *
     * 引擎存在确认前缀（composition.selStart > 0）而调用方给不出可信的已确认
     * 原始键长度时返回 null（降级：调用方回退到候选 comment / Rime 原始 preedit）。
     */
    private fun unconfirmedInput(context: ContextProto, confirmedRawLength: Int): String? {
        return when {
            confirmedRawLength in 1..context.input.length ->
                context.input.substring(confirmedRawLength)
            confirmedPrefix(context.composition) != null -> null
            else -> context.input
        }
    }

    /**
     * 引擎已确认前缀（分段确认后 preedit 头部的汉字，如 "你hao" 中的 "你"）。
     * [CompositionProto.selStart] 由 JNI 层经 utf8::unchecked::distance 按 **Unicode 码点**
     * 计偏移（"你hao" 的 selStart=1），而 Kotlin String 是 UTF-16，需经
     * offsetByCodePoints 换算为字符索引后再切分，不可直接当字符/字节偏移使用
     * （字节切分会截断多字节汉字产生乱码）；无确认前缀返回 null。
     */
    private fun confirmedPrefix(composition: CompositionProto?): String? {
        val preedit = composition?.preedit ?: return null
        val selStart = composition.selStart
        if (selStart <= 0) return null
        val codePointCount = preedit.codePointCount(0, preedit.length)
        if (selStart > codePointCount) return null
        val endIndex = preedit.offsetByCodePoints(0, selStart)
        return preedit.substring(0, endIndex)
    }

    /**
     * 提取高亮候选（无高亮时取首位）的读音音节列表；无可用读音源返回空。
     * comment 先经 [normalizePinyinComment] 归一化（兼容［］包裹与星号/∞ 标记）；
     * 高亮候选 comment 不可用时经 [usableComment] 在菜单内回退扫描。
     */
    private fun highlightedSyllables(context: ContextProto): List<String> {
        val menu = context.menu ?: return emptyList()
        val candidates = menu.candidates
        if (candidates.isEmpty()) return emptyList()
        val index = menu.highlightedCandidateIndex.takeIf { it in candidates.indices } ?: 0
        val source = usableComment(candidates, index) ?: return emptyList()
        return source.split('\'', ' ')
            .filter { seg -> seg.isNotEmpty() && seg.all { it.isLetter() } }
    }

    /**
     * 预览读音 comment 源选择：高亮候选优先；不可用时菜单内回退扫描。
     *
     * 高亮候选 comment 不可用的典型场景：
     * - custom_phrase 数字编码短语（table_translator 候选无拼音 comment，
     *   librime 词条格式仅三列，见 table_db.cc）；
     * - 用户词/联想句被 is_in_user_dict 改写为星号/∞ 标记。
     * 回退策略：同码候选共享读音空间，优先取**同字数**候选的可用 comment
     * （等长候选音节数必然一致，与高亮词读音最贴近），其次任意可用
     * comment；均优于本地 T9 表字母序还原（其首匹配几乎从不等于引擎首词
     * 读音，如 64426 还原为 mi'han 而非 ni'hao）。
     */
    private fun usableComment(candidates: Array<CandidateProto>, index: Int): String? {
        normalizePinyinComment(candidates[index].comment)?.let { return it }
        val highlightedLength = candidates[index].text.length
        for (candidate in candidates) {
            if (candidate.text.length == highlightedLength) {
                normalizePinyinComment(candidate.comment)?.let { return it }
            }
        }
        for (candidate in candidates) {
            normalizePinyinComment(candidate.comment)?.let { return it }
        }
        return null
    }

    /**
     * 候选 comment 归一化：去空白、剥离 corrector/comment_format 的全角包裹符
     * ［］，仅保留纯字母/'/空格的拼音串；其余形态（is_in_user_dict 的星号/∞
     * 标记、纠错提示的带调注音、英文标记码等）返回 null 跳过。
     *
     * T9 白霜集成后 comment 数据链：comment_format 包裹 → corrector 以
     * keep_comments 保留净拼音输出；本归一化对两种形态（净/包裹）均健壮，
     * 防止配置回退或他方案包裹形态再次切断预览读音源。
     */
    private fun normalizePinyinComment(comment: String): String? {
        val stripped = comment.trim().removeSurrounding("［", "］").trim()
        if (stripped.isEmpty()) return null
        return stripped.takeIf { py -> py.all { it == '\'' || it == ' ' || it.isLetter() } }
    }

    /**
     * 将连续数字击键按候选读音逐音节还原：
     * - 音节键序是剩余击键前缀 → 完整音节已打完，原样展示；
     * - 剩余击键是音节键序前缀 → 音节未打完，截断到实际击键数；
     * - 首键相同（简拼）→ 取音节首字母；
     * - 读音与击键不兼容或音节耗尽 → 回退本地 T9 表还原。
     */
    private fun renderDigitRun(digits: String, syllables: ArrayDeque<String>): String {
        val parts = mutableListOf<String>()
        var rest = digits
        while (rest.isNotEmpty()) {
            val syllable = syllables.firstOrNull()
            val keys = syllable?.let { T9PinYinUtils.pinyin2Key(it) }.orEmpty()
            when {
                // 完整音节：候选读音的键序是剩余击键的前缀
                keys.isNotEmpty() && rest.startsWith(keys) -> {
                    parts.add(syllable!!)
                    syllables.removeFirst()
                    rest = rest.substring(keys.length)
                }
                // 音节未打完：剩余击键是该音节键序的前缀，截断到实际击键数
                keys.isNotEmpty() && keys.startsWith(rest) -> {
                    parts.add(syllable!!.take(rest.length))
                    syllables.removeFirst()
                    rest = ""
                }
                // 简拼：单键对应音节首字母
                keys.isNotEmpty() && keys[0] == rest[0] -> {
                    parts.add(syllable!!.take(1))
                    syllables.removeFirst()
                    rest = rest.substring(1)
                }
                // 无候选读音或读音与击键不匹配 → 回退本地 T9 表还原
                else -> {
                    parts.add(restoreDigitsLocally(rest))
                    rest = ""
                }
            }
        }
        return parts.joinToString("'")
    }

    /**
     * 本地兜底：将连续数字击键贪心切分为音节，每次取能命中「等长拼音」的最长前缀还原，
     * 无法成音节的剩余数字原样保留，保证展示的字母数与击键数一一对应。
     */
    private fun restoreDigitsLocally(digits: String): String {
        val syllables = mutableListOf<String>()
        var rest = digits
        while (rest.isNotEmpty()) {
            var matched: String? = null
            for (len in minOf(rest.length, MAX_SYLLABLE_KEYS) downTo 1) {
                matched = T9PinYinUtils.t9KeyToPinyin(rest.take(len))
                    .firstOrNull { it.length == len }
                if (matched != null) {
                    rest = rest.drop(len)
                    break
                }
            }
            if (matched == null) {
                // 理论上单个 2-9 数字必有单字母匹配，此处兜底保留原始数字
                syllables.add(rest)
                break
            }
            syllables.add(matched)
        }
        return syllables.joinToString("'")
    }
}
