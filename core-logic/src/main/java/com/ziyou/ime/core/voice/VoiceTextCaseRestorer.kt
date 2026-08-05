package com.ziyou.ime.core.voice

/**
 * 语音识别英文大小写恢复器（纯逻辑，不依赖 Android 类型）。
 *
 * 背景：sherpa-onnx 中英混合流式模型（bilingual-zh-en）的英文 token 词表
 * 为大写字母，识别产出的英文单词全部大写（如 "I LOVE 中国"）。本类对识别
 * 文本做确定性的句子大小写恢复，保证同一输入永远得到同一输出（流式 partial
 * 反复改写时不会抖动）。
 *
 * 规则（保守、可预测，不依赖词典）：
 * 1. 英文单词按连续字母段切分，逐词处理：
 *    - 单字母 "i"/"I" 恒大写（英文第一人称）；其余单字母转小写，
 *      若处于句首由句首规则重新大写（避免句中冠词 "a" 被误大写）；
 *    - 全大写词命中 [ACRONYMS] 常见缩写白名单则保留（AI/OK/USA…）；
 *    - 其余全大写词转小写（"LOVE" → "love"）；
 *    - 混合大小写原样保留（尊重模型可能产出的专名形态，如 "iPhone"）。
 * 2. 句首大写：仅当英文单词是句子的第一个词时才大写其首字母——
 *    句子边界（文本开头 / 句末标点）与英文词之间只允许空白/数字/非句末符号；
 *    一旦出现中文字符（或非 ASCII 字母），说明句子以非英文开头，
 *    后续嵌入的英文词不再享受句首大写（"我的 iPhone" 不会被改成 "我的 IPhone"）。
 *
 * 已知局限：未建模真正的专有名词识别（"JOHN" 会被转为 "john"），
 * 白名单只覆盖高频缩写；这是不引入词典/语言模型前提下的最优折中。
 */
object VoiceTextCaseRestorer {

    /** 高频英文缩写白名单：全大写命中时原样保留。
     *  注：不收与普通单词同形的缩写——AM（动词 am）、APP（app）、IT（代词 it），
     *  误保留代价高于误转小写。 */
    private val ACRONYMS = setOf(
        "AI", "API", "CEO", "CPU", "CTO", "DIY", "DNA", "EU",
        "FBI", "GDP", "GPT", "GPS", "ID", "IP", "KFC", "MBA", "NASA",
        "NBA", "NFL", "OK", "OS", "PC", "PDF", "PPT", "TV", "UK", "UN",
        "US", "USA", "USB", "VIP", "VPN", "WTO"
    )

    /** 句末标点：其后第一个英文字母视为句首。 */
    private val SENTENCE_ENDS = setOf('.', '!', '?', '。', '！', '？', '…', '\n')

    private val WORD = Regex("[A-Za-z]+")

    /** 对识别文本做大小写恢复；空串与纯中文文本原样返回（零改动安全）。 */
    fun restore(text: String): String {
        if (text.isEmpty()) return text
        // 第一步：逐英文单词改大小写
        val sb = StringBuilder(text.length)
        var last = 0
        for (match in WORD.findAll(text)) {
            sb.append(text, last, match.range.first)
            sb.append(recaseWord(match.value))
            last = match.range.last + 1
        }
        sb.append(text, last, text.length)
        // 第二步：句首字母大写
        return capitalizeSentenceStarts(sb.toString())
    }

    /** 单词级改形：i/I 恒大写 / 白名单缩写保留 / 其余全大写转小写 / 混合大小写保留。 */
    private fun recaseWord(word: String): String = when {
        word.length == 1 -> if (word == "i" || word == "I") "I" else word.lowercase()
        word.all { it.isUpperCase() } -> if (word in ACRONYMS) word else word.lowercase()
        else -> word
    }

    /**
     * 句首大写：仅当英文单词是句子的第一个词时大写其首字母。
     *
     * 状态机：句子边界（起始/句末标点）后进入 AWAITING；
     * - AWAITING 中遇到英文字母 → 大写并进入句子（句首命中）；
     * - AWAITING 中遇到非 ASCII 字母（中文等）→ 直接进入句子（句首是中文，
     *   后续嵌入英文不再大写）；
     * - 空白/数字/非句末符号不改变状态。
     */
    private fun capitalizeSentenceStarts(text: String): String {
        val chars = text.toCharArray()
        var awaitingFirstWord = true
        for (i in chars.indices) {
            val c = chars[i]
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    if (awaitingFirstWord) chars[i] = c.uppercaseChar()
                    awaitingFirstWord = false
                }

                c in SENTENCE_ENDS -> awaitingFirstWord = true

                c.isLetter() -> {
                    // 中文等非 ASCII 字母：句子以非英文开头，取消句首大写资格
                    awaitingFirstWord = false
                }

                else -> Unit // 空白/数字/符号保持等待状态
            }
        }
        return String(chars)
    }
}
