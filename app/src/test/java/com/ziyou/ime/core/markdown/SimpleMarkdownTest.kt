package com.ziyou.ime.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimpleMarkdown] 转换器单元测试（覆盖技能开发指南实际用到的语法子集）。
 */
class SimpleMarkdownTest {

    @Test
    fun `标题转换`() {
        assertEquals("<h1>标题</h1>\n", SimpleMarkdown.toHtml("# 标题"))
        assertEquals("<h3>三级</h3>\n", SimpleMarkdown.toHtml("### 三级"))
    }

    @Test
    fun `HTML实体转义防注入`() {
        val html = SimpleMarkdown.toHtml("面板里的 <input> 与 <script>alert(1)</script>")
        assertFalse(html.contains("<input>"))
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;input&gt;"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `围栏代码块原样转义且不做行内格式化`() {
        val html = SimpleMarkdown.toHtml("```\nvar a = \"**x**\" < 3;\n```")
        assertTrue(html.contains("<pre><code>"))
        assertTrue(html.contains("**x**"))          // 代码内不转粗体
        assertTrue(html.contains("&lt; 3"))         // 内容转义
        assertTrue(html.contains("&quot;"))
    }

    @Test
    fun `表格转换`() {
        val html = SimpleMarkdown.toHtml("| 字段 | 规则 |\n|------|------|\n| id | 反向域名 |")
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<th>字段</th>"))
        assertTrue(html.contains("<td>id</td>"))
        assertTrue(html.contains("<td>反向域名</td>"))
    }

    @Test
    fun `列表转换`() {
        val unordered = SimpleMarkdown.toHtml("- 甲\n- 乙")
        assertTrue(unordered.contains("<ul>"))
        assertTrue(unordered.contains("<li>甲</li>"))

        val ordered = SimpleMarkdown.toHtml("1. 第一步\n2. 第二步")
        assertTrue(ordered.contains("<ol>"))
        assertTrue(ordered.contains("<li>第二步</li>"))
    }

    @Test
    fun `行内格式`() {
        val html = SimpleMarkdown.toHtml("这是 **加粗** 与 `code` 与 [链接文字](https://x.com)")
        assertTrue(html.contains("<b>加粗</b>"))
        assertTrue(html.contains("<code>code</code>"))
        assertTrue(html.contains("链接文字"))
        assertFalse(html.contains("https://x.com"))  // 链接只保留文字
    }

    @Test
    fun `引用与分隔线`() {
        val html = SimpleMarkdown.toHtml("> 状态：已上线\n> 结论先行\n\n---")
        assertTrue(html.contains("<blockquote>"))
        assertTrue(html.contains("状态：已上线<br>结论先行"))
        assertTrue(html.contains("<hr>"))
    }

    @Test
    fun `段落合并与硬换行保留`() {
        val html = SimpleMarkdown.toHtml("第一行\n第二行\n\n新段落")
        assertTrue(html.contains("<p>第一行<br>第二行</p>"))
        assertTrue(html.contains("<p>新段落</p>"))
    }

    @Test
    fun `表头误判防护 普通竖线行不当表格`() {
        // 只有 | 开头但下一行不是分隔行 → 按普通段落处理，内容不丢
        val html = SimpleMarkdown.toHtml("|仅一行竖线文本|")
        assertFalse(html.contains("<table>"))
        assertTrue(html.contains("仅一行竖线文本"))
    }
}
