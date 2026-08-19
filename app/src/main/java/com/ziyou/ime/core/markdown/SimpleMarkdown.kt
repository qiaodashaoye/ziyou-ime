package com.ziyou.ime.core.markdown

/**
 * 极简 Markdown → HTML 转换器（纯逻辑，零依赖）。
 *
 * 面向 App 内文档查看场景（技能开发指南等），支持文档实际用到的语法子集：
 * 标题(#~######)、围栏代码块(```)、表格、无序/有序列表、引用(>)、
 * 分隔线(---)、加粗(**)、行内代码(`)、链接（仅保留文字）。
 *
 * 安全约定：全部文本先做 HTML 实体转义再套标签，杜绝文档内容注入
 * （文档中含 `<input>`、`<img src>` 等字面量，必须按文本渲染）。
 * 不支持的语法按普通段落文本降级，不会丢内容。
 */
object SimpleMarkdown {

    /** 转换 Markdown 文本为 HTML 片段（不含 <html>/<body> 外壳，由调用方包裹）。 */
    fun toHtml(markdown: String): String {
        val lines = markdown.lines()
        val html = StringBuilder()
        var index = 0
        // 段落缓冲：连续普通文本行合并为一个 <p>（硬换行以 <br> 保留）
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            html.append("<p>")
                .append(paragraph.joinToString("<br>") { inline(it) })
                .append("</p>\n")
            paragraph.clear()
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            when {
                // ── 围栏代码块：内容原样转义，不做任何行内格式化 ──
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].trim().startsWith("```")) {
                        code.append(escape(lines[index])).append('\n')
                        index++
                    }
                    index++ // 跳过闭合 ```
                    html.append("<pre><code>").append(code).append("</code></pre>\n")
                    continue
                }

                // ── 表格：连续以 | 开头的行，第二行为分隔行 ──
                trimmed.startsWith("|") && isTableSeparator(lines.getOrNull(index + 1)) -> {
                    flushParagraph()
                    html.append("<table>\n<tr>")
                    cells(trimmed).forEach { html.append("<th>").append(inline(it)).append("</th>") }
                    html.append("</tr>\n")
                    index += 2 // 跳过表头与分隔行
                    while (index < lines.size && lines[index].trim().startsWith("|")) {
                        html.append("<tr>")
                        cells(lines[index].trim()).forEach {
                            html.append("<td>").append(inline(it)).append("</td>")
                        }
                        html.append("</tr>\n")
                        index++
                    }
                    html.append("</table>\n")
                    continue
                }

                // ── 标题 ──
                trimmed.startsWith("#") -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                    val text = trimmed.drop(level).trim()
                    html.append("<h$level>").append(inline(text)).append("</h$level>\n")
                }

                // ── 分隔线 ──
                trimmed == "---" || trimmed == "***" -> {
                    flushParagraph()
                    html.append("<hr>\n")
                }

                // ── 引用块：连续 > 行合并 ──
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (index < lines.size && lines[index].trim().startsWith(">")) {
                        quote += lines[index].trim().removePrefix(">").trim()
                        index++
                    }
                    html.append("<blockquote>")
                        .append(quote.joinToString("<br>") { inline(it) })
                        .append("</blockquote>\n")
                    continue
                }

                // ── 无序列表 ──
                trimmed.startsWith("- ") -> {
                    flushParagraph()
                    html.append("<ul>\n")
                    while (index < lines.size && lines[index].trim().startsWith("- ")) {
                        html.append("<li>")
                            .append(inline(lines[index].trim().removePrefix("- ")))
                            .append("</li>\n")
                        index++
                    }
                    html.append("</ul>\n")
                    continue
                }

                // ── 有序列表 ──
                ORDERED_ITEM.matches(trimmed) -> {
                    flushParagraph()
                    html.append("<ol>\n")
                    while (index < lines.size && ORDERED_ITEM.matches(lines[index].trim())) {
                        html.append("<li>")
                            .append(inline(lines[index].trim().replace(ORDERED_PREFIX, "")))
                            .append("</li>\n")
                        index++
                    }
                    html.append("</ol>\n")
                    continue
                }

                // ── 空行：段落分隔 ──
                trimmed.isEmpty() -> flushParagraph()

                // ── 普通文本行：进入段落缓冲 ──
                else -> paragraph += trimmed
            }
            index++
        }
        flushParagraph()
        return html.toString()
    }

    // ===== 内部 =====

    private val ORDERED_ITEM = Regex("^\\d+\\. .*")
    private val ORDERED_PREFIX = Regex("^\\d+\\. ")
    private val TABLE_SEPARATOR_CELL = Regex("^:?-{3,}:?$")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val INLINE_CODE = Regex("`([^`]+)`")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")

    /** 行内格式化：先整体转义，再应用 加粗 / 行内代码 / 链接文字。 */
    private fun inline(text: String): String {
        var result = escape(text)
        result = LINK.replace(result) { it.groupValues[1] }
        result = BOLD.replace(result) { "<b>${it.groupValues[1]}</b>" }
        result = INLINE_CODE.replace(result) { "<code>${it.groupValues[1]}</code>" }
        return result
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 判断某行是否为表格分隔行（如 |---|:---:|）。 */
    private fun isTableSeparator(line: String?): Boolean {
        val trimmed = line?.trim() ?: return false
        if (!trimmed.startsWith("|")) return false
        val parts = cells(trimmed)
        return parts.isNotEmpty() && parts.all { TABLE_SEPARATOR_CELL.matches(it.trim()) }
    }

    /** 拆分表格行单元格（去掉首尾管道符）。 */
    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
}
