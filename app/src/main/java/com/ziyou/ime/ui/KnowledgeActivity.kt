package com.ziyou.ime.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ziyou.ime.ai.PersonaRepository
import com.ziyou.ime.ai.knowledge.AiUsageStats
import com.ziyou.ime.ai.knowledge.KnowledgeImporter
import com.ziyou.ime.ai.knowledge.KnowledgeItem
import com.ziyou.ime.ai.knowledge.KnowledgeRepository
import com.ziyou.ime.ai.knowledge.KnowledgeSearcher
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 知识库管理页面
 *
 * 知识库与人设强绑定：本页只负责知识条目的导入/删除/同步，
 * 绑定关系在「人设管理」页创建/编辑人设时直接勾选建立，无全局启用开关。
 *
 * - 顶部：使用统计摘要（条目数/总字数/知识库命中率）
 * - 列表：知识条目（名称/来源类型/分块数/绑定角色数/导入时间），
 *   点击查看详情/删除，文件夹来源附「同步」入口（增量重导变更文件）
 * - 底部导入入口：txt/md 文件（SAF OpenDocument）、文件夹（OpenDocumentTree）、
 *   自定义文本（弹窗输入）
 *
 * 与 SkillManagerActivity 一致采用纯代码 View 布局，保持简单轻量。
 */
class KnowledgeActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var statsText: TextView

    /** SAF 单文件选择（txt/md；SAF 无法按后缀过滤，接受文本类 MIME 由导入侧兜底校验） */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFile(it) } }

    /** SAF 文件夹选择 */
    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { importFolder(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithTitleBar("AI 知识库", buildView())
        refreshList()
    }

    // ===== 布局 =====

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "在此导入知识条目，然后在「人设管理」页创建或编辑人设时勾选绑定：" +
                "该人设问答/润色时将仅检索其绑定的知识（RAG）。知识内容仅存储在本机。"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        // 统计摘要
        statsText = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(statsText)

        // 导入操作行
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "导入文件"
            setOnClickListener { pickFile.launch(arrayOf("text/*", "application/octet-stream")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "导入文件夹"
            setOnClickListener { pickFolder.launch(null) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "添加文本"
            setOnClickListener { showAddTextDialog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        root.addView(TextView(this).apply {
            text = "支持 txt / md 文本文件，单文件不超过 2MB，全库不超过约 10MB 文本。"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        // 条目列表
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ===== 列表刷新 =====

    private fun refreshList() {
        val items = KnowledgeRepository.getItems(this)
        refreshStats(items)
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "暂无知识条目，请先导入"
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
                setTextColor(0xFF757575.toInt())
            })
            return
        }
        items.forEach { item -> listContainer.addView(createItemView(item)) }
    }

    private fun refreshStats(items: List<KnowledgeItem>) {
        val stats = AiUsageStats.getSnapshot(this)
        val hitRate = if (stats.questions > 0) {
            "%.0f%%".format(stats.kbHitQuestions * 100.0 / stats.questions)
        } else "—"
        statsText.text = "共 ${items.size} 条 · ${formatChars(items.sumOf { it.totalChars })} · " +
            "累计提问 ${stats.questions} 次 · 知识库命中率 $hitRate"
    }

    private fun createItemView(item: KnowledgeItem): View {
        val typeBadge = when (item.sourceType) {
            KnowledgeItem.SourceType.FILE -> "[文件]"
            KnowledgeItem.SourceType.FOLDER -> "[文件夹]"
            KnowledgeItem.SourceType.TEXT -> "[文本]"
        }
        // 已绑定人设徽标（润色时作为该角色的专属检索范围）
        val boundCount = PersonaRepository.countPersonasBoundTo(this, item.id)
        val kbBadge = if (boundCount > 0) " · 已绑定 $boundCount 个角色" else ""
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showItemDetail(item) }

            addView(TextView(context).apply {
                text = "$typeBadge ${item.name}"
                textSize = 16f
                setTextColor(0xFF212121.toInt())
            })
            addView(TextView(context).apply {
                text = "${item.chunkCount} 个知识块 · ${formatChars(item.totalChars)}$kbBadge · " +
                    "导入于 ${formatDate(item.importedAt)}"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    // ===== 详情 / 删除 / 同步 =====

    private fun showItemDetail(item: KnowledgeItem) {
        val builder = AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(buildString {
                append("来源：")
                append(when (item.sourceType) {
                    KnowledgeItem.SourceType.FILE -> "本地文件"
                    KnowledgeItem.SourceType.FOLDER -> "文件夹成员"
                    KnowledgeItem.SourceType.TEXT -> "自定义文本"
                })
                append("\n知识块：${item.chunkCount} 个")
                append("\n字数：${formatChars(item.totalChars)}")
                append("\n导入时间：${formatDate(item.importedAt)}")
            })
            .setNegativeButton("关闭", null)
            .setPositiveButton("删除") { _, _ -> confirmRemove(item) }
        // 文件夹成员条目提供整个文件夹的增量同步入口
        if (item.sourceType == KnowledgeItem.SourceType.FOLDER && item.folderUri != null) {
            builder.setNeutralButton("同步文件夹") { _, _ -> syncFolder(item.folderUri) }
        }
        builder.show()
    }

    private fun confirmRemove(item: KnowledgeItem) {
        // 已绑定人设时二次确认文案提示影响面（删除后绑定自动解除）
        val boundCount = PersonaRepository.countPersonasBoundTo(this, item.id)
        val warning = if (boundCount > 0)
            "\n\n该条目已绑定 $boundCount 个角色，删除后相关角色的润色将不再检索它。"
        else ""
        AlertDialog.Builder(this)
            .setTitle("删除知识条目")
            .setMessage("确定删除「${item.name}」？$warning")
            .setPositiveButton("删除") { _, _ ->
                if (KnowledgeRepository.removeItem(this, item.id)) {
                    KnowledgeSearcher.invalidate()
                    showToast("已删除")
                    refreshList()
                } else {
                    showToast("删除失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 导入 =====

    private fun importFile(uri: Uri) {
        showToast("正在导入…")
        lifecycleScope.launch {
            KnowledgeImporter.importFile(this@KnowledgeActivity, uri)
                .onSuccess { item ->
                    showToast("已导入「${item.name}」（${item.chunkCount} 个知识块）")
                    refreshList()
                }
                .onFailure { e -> showToast(e.message ?: "导入失败") }
        }
    }

    private fun importFolder(treeUri: Uri) {
        showToast("正在扫描文件夹…")
        lifecycleScope.launch {
            KnowledgeImporter.importFolder(this@KnowledgeActivity, treeUri)
                .onSuccess { items ->
                    showToast("已导入 ${items.size} 个文件")
                    refreshList()
                }
                .onFailure { e -> showToast(e.message ?: "导入失败") }
        }
    }

    private fun syncFolder(folderUri: String) {
        showToast("正在同步…")
        lifecycleScope.launch {
            KnowledgeImporter.syncFolder(this@KnowledgeActivity, folderUri)
                .onSuccess { changed ->
                    showToast(if (changed > 0) "同步完成，更新 $changed 项" else "已是最新")
                    refreshList()
                }
                .onFailure { e ->
                    showToast("同步失败：${e.message ?: "请重新授权文件夹"}")
                }
        }
    }

    private fun showAddTextDialog() {
        val titleInput = EditText(this).apply { hint = "标题（如：产品FAQ）" }
        val contentInput = EditText(this).apply {
            hint = "粘贴或输入知识内容…"
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(titleInput)
            addView(contentInput)
        }
        AlertDialog.Builder(this)
            .setTitle("添加自定义文本")
            .setView(container)
            .setPositiveButton("添加") { _, _ ->
                val text = contentInput.text.toString()
                if (text.isBlank()) {
                    showToast("内容不能为空")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    KnowledgeImporter.importText(
                        this@KnowledgeActivity, titleInput.text.toString(), text)
                        .onSuccess { item ->
                            showToast("已添加「${item.name}」")
                            refreshList()
                        }
                        .onFailure { e -> showToast(e.message ?: "添加失败") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 辅助 =====

    private fun formatChars(chars: Int): String = when {
        chars >= 10000 -> "%.1f万字".format(chars / 10000.0)
        else -> "${chars}字"
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
