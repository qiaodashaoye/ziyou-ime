package com.ziyou.ime.ai

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan

/**
 * 轻量 Markdown 渲染器
 *
 * 将 AI 返回的 Markdown 文本解析为 [Spanned] 富文本，供答案气泡的 TextView
 * 直接渲染（TextView 原生支持 Spanned，无需 Canvas 手绘或 WebView）。
 * 不引入第三方 Markdown 库，与项目轻量纯代码 UI 的纪律一致。
 *
 * 支持的语法子集（覆盖 LLM 回答常见格式）：
 * - 标题 `#`~`######`：粗体 + 按层级相对放大（RelativeSizeSpan，随基准字号自适应）
 * - 粗体 `**x**` / `__x__`、斜体 `*x*` / `_x_`、删除线 `~~x~~`（可嵌套）
 * - 行内代码 `` `x` `` 与围栏代码块 ``` ```：等宽字体 + 主题按压色底 + 略缩字号
 * - 无序列表 `-`/`*`/`+`（渲染为 •）、有序列表 `1.`（保留编号）、缩进保留
 * - 引用 `>`：主题强调色竖条前缀 + 次要文字色
 * - 分隔线 `---`：次要色横线
 * - 链接 `[文本](url)`：仅展示文本，主题强调色 + 下划线（面板内不可跳转）
 *
 * 颜色经 [Palette] 从当前键盘主题取值，保证浅色/深色/Material 主题下均协调。
 */
object MarkdownRenderer {

    /** 主题取色（由调用方从 KeyboardTheme 映射，保持渲染器与 config 层解耦）。 */
    data class Palette(
        /** 行内代码 / 代码块背景（建议取主题按压色） */
        val codeBackground: Int,
        /** 引用竖条与链接颜色（建议取主题强调色） */
        val accentColor: Int,
        /** 次要文字色：引用正文 / 分隔线（建议取主题 preedit 色） */
        val secondaryColor: Int
    )

    private const val SPAN_FLAG = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    /** 代码字号相对比例（相对气泡基准字号缩放，屏幕适配随基准走） */
    private const val CODE_SIZE_RATIO = 0.9f

    // ===== 块级语法 =====
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UNORDERED = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val HRULE = Regex("^\\s*([-*_])\\1{2,}\\s*$")
    private val FENCE = Regex("^\\s*```.*$")

    // ===== 行内语法（按优先级排列：行内代码 > 粗体 > 删除线 > 斜体 > 链接）=====
    private val INLINE = Regex(
        "(`[^`\n]+`)" +
            "|(\\*\\*[^*\n]+\\*\\*)" +
            "|(__[^_\n]+__)" +
            "|(~~[^~\n]+~~)" +
            "|(\\*[^*\n]+\\*)" +
            "|(_[^_\n]+_)" +
            "|(\\[[^\\]\n]+]\\([^)\n]*\\))"
    )

    /**
     * 解析 Markdown 为可直接交给 TextView 的富文本。
     * 解析失败或纯文本输入均安全降级为原样字符（逐行处理，无整体抛错路径）。
     */
    fun render(markdown: String, palette: Palette): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var inCodeBlock = false
        var codeStart = 0

        for (line in lines) {
            // 围栏行本身不输出，只切换代码块状态
            if (FENCE.matches(line)) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeStart = sb.length
                } else {
                    inCodeBlock = false
                    applyCodeBlockSpans(sb, codeStart, palette)
                }
                continue
            }
            if (inCodeBlock) {
                sb.append(line).append('\n')
                continue
            }
            appendBlockLine(sb, line, palette)
            sb.append('\n')
        }
        // 未闭合代码块兜底（模型截断输出时不丢样式）
        if (inCodeBlock) applyCodeBlockSpans(sb, codeStart, palette)

        // 去除尾部多余空行
        while (sb.isNotEmpty() && sb[sb.length - 1] == '\n') {
            sb.delete(sb.length - 1, sb.length)
        }
        return sb
    }

    /** 为 [start, 当前末尾) 的代码块整体套等宽字体 + 背景色 + 缩小字号。 */
    private fun applyCodeBlockSpans(sb: SpannableStringBuilder, start: Int, palette: Palette) {
        // 背景不覆盖块尾换行符，避免多渲染一行色块
        var end = sb.length
        if (end > start && sb[end - 1] == '\n') end--
        if (end <= start) return
        sb.setSpan(TypefaceSpan("monospace"), start, end, SPAN_FLAG)
        sb.setSpan(BackgroundColorSpan(palette.codeBackground), start, end, SPAN_FLAG)
        sb.setSpan(RelativeSizeSpan(CODE_SIZE_RATIO), start, end, SPAN_FLAG)
    }

    /** 解析单个非代码块行：标题 / 分隔线 / 引用 / 列表 / 普通段落。 */
    private fun appendBlockLine(sb: SpannableStringBuilder, line: String, palette: Palette) {
        val heading = HEADING.find(line)
        if (heading != null) {
            val start = sb.length
            appendInline(sb, heading.groupValues[2].trim(), palette)
            val size = when (heading.groupValues[1].length) {
                1 -> 1.3f
                2 -> 1.2f
                3 -> 1.1f
                else -> 1.05f
            }
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, SPAN_FLAG)
            sb.setSpan(RelativeSizeSpan(size), start, sb.length, SPAN_FLAG)
            return
        }
        if (HRULE.matches(line)) {
            val start = sb.length
            sb.append("──────────")
            sb.setSpan(ForegroundColorSpan(palette.secondaryColor), start, sb.length, SPAN_FLAG)
            return
        }
        val quote = QUOTE.find(line)
        if (quote != null) {
            // 用着色竖条字符替代 QuoteSpan（段落级 span 在逐行拼接场景下绘制不稳定）
            val barStart = sb.length
            sb.append("▎ ")
            sb.setSpan(ForegroundColorSpan(palette.accentColor), barStart, sb.length, SPAN_FLAG)
            val textStart = sb.length
            appendInline(sb, quote.groupValues[1], palette)
            sb.setSpan(ForegroundColorSpan(palette.secondaryColor), textStart, sb.length, SPAN_FLAG)
            return
        }
        val unordered = UNORDERED.find(line)
        if (unordered != null) {
            sb.append(unordered.groupValues[1]).append("• ")
            appendInline(sb, unordered.groupValues[2], palette)
            return
        }
        val ordered = ORDERED.find(line)
        if (ordered != null) {
            sb.append(ordered.groupValues[1]).append(ordered.groupValues[2]).append(". ")
            appendInline(sb, ordered.groupValues[3], palette)
            return
        }
        appendInline(sb, line, palette)
    }

    /** 解析行内语法（粗体内可嵌斜体等，经递归处理），无标记文本原样追加。 */
    private fun appendInline(sb: SpannableStringBuilder, text: String, palette: Palette) {
        var cursor = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > cursor) {
                sb.append(text, cursor, match.range.first)
            }
            val token = match.value
            val start = sb.length
            when {
                token.startsWith("`") -> {
                    sb.append(token, 1, token.length - 1)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, SPAN_FLAG)
                    sb.setSpan(BackgroundColorSpan(palette.codeBackground), start, sb.length, SPAN_FLAG)
                    sb.setSpan(RelativeSizeSpan(CODE_SIZE_RATIO), start, sb.length, SPAN_FLAG)
                }
                token.startsWith("**") || token.startsWith("__") -> {
                    appendInline(sb, token.substring(2, token.length - 2), palette)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, SPAN_FLAG)
                }
                token.startsWith("~~") -> {
                    appendInline(sb, token.substring(2, token.length - 2), palette)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, SPAN_FLAG)
                }
                token.startsWith("*") || token.startsWith("_") -> {
                    appendInline(sb, token.substring(1, token.length - 1), palette)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, SPAN_FLAG)
                }
                token.startsWith("[") -> {
                    appendInline(sb, token.substring(1, token.indexOf(']')), palette)
                    sb.setSpan(ForegroundColorSpan(palette.accentColor), start, sb.length, SPAN_FLAG)
                    sb.setSpan(UnderlineSpan(), start, sb.length, SPAN_FLAG)
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            sb.append(text, cursor, text.length)
        }
    }
}
