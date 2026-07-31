package com.ziyou.ime.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ziyou.ime.core.markdown.SimpleMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 技能开发文档页面：展示随 APK 打包的《技能插件开发指南》。
 *
 * 文档来源：docs/技能插件开发指南.md（构建时经 syncSkillDevGuide 任务拷入
 * assets/docs/skill_dev_guide.md，单一来源不双维护）。
 * 渲染：core-logic 的 [SimpleMarkdown] 转 HTML 后由 WebView 展示——
 * 本页 WebView 为纯本地静态文档，JS/文件/网络访问全部关闭，与技能沙箱 WebView 无关。
 */
class SkillDevGuideActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SkillDevGuideActivity"
        private const val GUIDE_ASSET = "docs/skill_dev_guide.md"

        /** 文档样式：适配浅色阅读，代码块横向滚动，表格描边 */
        private const val STYLE = """
            body { font-family: sans-serif; margin: 14px; line-height: 1.65;
                   font-size: 14px; color: #212121; word-break: break-word; }
            h1 { font-size: 20px; } h2 { font-size: 17px; border-bottom: 1px solid #E0E0E0;
                 padding-bottom: 4px; margin-top: 28px; }
            h3 { font-size: 15px; margin-top: 22px; } h4 { font-size: 14px; }
            code { background: #F0F0F0; border-radius: 3px; padding: 1px 4px;
                   font-size: 12.5px; }
            pre { background: #F5F5F5; border-radius: 6px; padding: 10px;
                  overflow-x: auto; }
            pre code { background: none; padding: 0; }
            table { border-collapse: collapse; width: 100%; margin: 8px 0;
                    font-size: 12.5px; }
            th, td { border: 1px solid #D0D0D0; padding: 5px 7px; text-align: left; }
            th { background: #F0F0F0; }
            blockquote { border-left: 3px solid #1976D2; margin: 8px 0;
                         padding: 4px 10px; background: #F5F9FF; color: #424242; }
            hr { border: none; border-top: 1px solid #E0E0E0; margin: 20px 0; }
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this).apply {
            // 纯本地静态文档：一切能力关死
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
        }
        setContentViewWithTitleBar("技能开发文档", webView)

        // 读取 + 转换在 IO 线程，避免阻塞首帧（文档约 30KB）
        lifecycleScope.launch {
            val html = withContext(Dispatchers.IO) { buildGuideHtml() }
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    private fun buildGuideHtml(): String {
        val markdown = try {
            assets.open(GUIDE_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "开发指南读取失败: ${e.message}")
            "# 文档加载失败\n\n请重新安装应用，或访问仓库查看 docs/技能插件开发指南.md"
        }
        val body = SimpleMarkdown.toHtml(markdown)
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>$STYLE</style></head><body>$body</body></html>
        """.trimIndent()
    }
}
