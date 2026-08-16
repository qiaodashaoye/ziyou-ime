package com.ziyou.ime.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ziyou.ime.ai.AiPersona
import com.ziyou.ime.ai.PersonaRepository
import com.ziyou.ime.ai.knowledge.AiMemoryStore
import com.ziyou.ime.ai.knowledge.KnowledgeRepository

/**
 * AI 人设管理页面
 *
 * 统一查看、切换、编辑、删除人设（内置 + 自定义），并在创建/编辑人设时
 * 直接勾选绑定知识库条目——知识库与人设强绑定，绑定关系是 RAG 检索的
 * 唯一驱动（问答与润色面板均仅检索当前人设绑定的条目）。
 *
 * 列表交互（与原设置页人设管理对话框一致）：
 * - 单击：切换当前人设（问答/润色面板即时生效）；
 * - 长按：操作菜单（预览；自定义人设另有编辑/删除）。
 * 每行三行展示：名称 + 徽标（当前/内置/📚绑定数）→ 简介 → 绑定知识条目名。
 *
 * 入口：设置页「AI 人设」项与键盘人设浮层「新建角色」。
 */
class PersonaManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithTitleBar("AI 人设管理", buildView())
    }

    override fun onResume() {
        super.onResume()
        // 从其他页面返回（如知识库导入）时刷新绑定关系展示
        refreshList()
    }

    private lateinit var listContainer: LinearLayout

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "人设决定 AI 的口吻；长按人设可编辑提示词并勾选绑定知识库，" +
                "绑定后该人设的问答与润色仅检索其专属知识。"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        root.addView(Button(this).apply {
            text = "＋ 新增人设"
            setOnClickListener { showPersonaEditDialog(null) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        refreshList()
        return root
    }

    // ===== 列表 =====

    private fun refreshList() {
        if (!::listContainer.isInitialized) return
        val personas = PersonaRepository.getAllPersonas(this)
        val currentId = PersonaRepository.getCurrentPersonaId(this)
        val kbNames = KnowledgeRepository.getItems(this).associate { it.id to it.name }
        listContainer.removeAllViews()
        personas.forEach { persona ->
            listContainer.addView(createPersonaRow(persona, persona.id == currentId, kbNames))
            listContainer.addView(View(this).apply {
                setBackgroundColor(0x1E000000)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    /** 单个人设行：名称+徽标 / 简介 / 绑定知识条目名（三行）。 */
    private fun createPersonaRow(
        persona: AiPersona,
        isCurrent: Boolean,
        kbNames: Map<String, String>
    ): View {
        val check = if (isCurrent) "✓ " else ""
        val builtinBadge = if (persona.isBuiltin) " [内置]" else ""
        val boundNames = persona.knowledgeItemIds.mapNotNull { kbNames[it] }
        val kbBadge = if (boundNames.isNotEmpty()) " 📚${boundNames.size}" else ""
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            // 单击：切换当前人设
            setOnClickListener {
                PersonaRepository.setCurrentPersona(this@PersonaManagerActivity, persona.id)
                showToast("已切换到: ${persona.name}")
                refreshList()
            }
            // 长按：操作菜单
            setOnLongClickListener {
                showPersonaActionsDialog(persona)
                true
            }

            addView(TextView(context).apply {
                text = "$check${persona.name}$builtinBadge$kbBadge"
                textSize = 16f
                setTextColor(if (isCurrent) 0xFF1976D2.toInt() else 0xFF212121.toInt())
            })
            val desc = if (persona.description.isNotBlank()) persona.description else "（无简介）"
            addView(TextView(context).apply {
                text = desc
                textSize = 13f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(2), 0, 0)
                maxLines = 2
            })
            addView(TextView(context).apply {
                text = if (boundNames.isEmpty()) "未绑定知识库（纯人设，无专属检索）"
                else "已绑定：" + boundNames.joinToString("、")
                textSize = 12f
                setTextColor(if (boundNames.isEmpty()) 0xFF9E9E9E.toInt() else 0xFF1976D2.toInt())
                setPadding(0, dp(2), 0, 0)
                maxLines = 2
            })
        }
    }

    // ===== 操作菜单 / 预览 / 删除 =====

    /** 人设操作菜单（长按触发）：内置人设仅可预览，自定义人设支持预览/编辑/删除。 */
    private fun showPersonaActionsDialog(persona: AiPersona) {
        val actions = if (persona.isBuiltin) {
            arrayOf("预览")
        } else {
            arrayOf("预览", "编辑", "删除")
        }
        AlertDialog.Builder(this)
            .setTitle(persona.name)
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "预览" -> showPersonaPreview(persona)
                    "编辑" -> showPersonaEditDialog(persona)
                    "删除" -> confirmDeletePersona(persona)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 预览人设：只读展示名称、简介、系统提示词与绑定知识条目。 */
    private fun showPersonaPreview(persona: AiPersona) {
        val kbNames = KnowledgeRepository.getItems(this)
            .filter { it.id in persona.knowledgeItemIds }
            .joinToString("、") { it.name }
        val content = buildString {
            append("名称：${persona.name}\n")
            if (persona.description.isNotBlank()) append("简介：${persona.description}\n")
            append("绑定知识库：").append(kbNames.ifEmpty { "无" }).append("\n")
            append("\n系统提示词：\n${persona.systemPrompt}")
        }
        AlertDialog.Builder(this)
            .setTitle("人设预览")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 确认删除自定义人设（同步清除其名下跨会话摘要槽）。 */
    private fun confirmDeletePersona(persona: AiPersona) {
        val boundCount = persona.knowledgeItemIds.size
        val warning = if (boundCount > 0) "\n其绑定的 $boundCount 个知识库条目不会被删除，仅解除绑定。" else ""
        AlertDialog.Builder(this)
            .setTitle("删除人设")
            .setMessage("确定删除「${persona.name}」？删除后不可恢复。$warning")
            .setPositiveButton("删除") { _, _ ->
                PersonaRepository.removeCustomPersona(this, persona.id)
                AiMemoryStore.clearPersona(this, persona.id)
                showToast("已删除")
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 编辑 / 新增（含知识库绑定多选） =====

    /**
     * 人设编辑弹窗：角色名称 / 简介 / 系统提示词 + 知识库绑定多选。
     * [existing] 为 null 时新建，否则编辑现有自定义人设。
     * 绑定是知识库使用的唯一入口（强绑定）：勾选的条目在该人设问答/润色
     * 时作为专属检索范围。
     */
    private fun showPersonaEditDialog(existing: AiPersona?) {
        val isEdit = existing != null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val nameInput = EditText(this).apply {
            hint = "角色名称（必填，上限 20 字）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
            setText(existing?.name ?: "")
        }
        val descInput = EditText(this).apply {
            hint = "人设简介（选填，上限 50 字）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(50))
            setText(existing?.description ?: "")
        }
        val promptInput = EditText(this).apply {
            hint = "系统提示词（必填，上限 500 字）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(500))
            minLines = 3
            gravity = Gravity.TOP
            setText(existing?.systemPrompt ?: "")
        }
        container.addView(nameInput)
        container.addView(descInput)
        container.addView(promptInput)

        // ── 知识库绑定多选（强绑定：人设问答/润色仅检索勾选项） ──
        val boundIds = existing?.knowledgeItemIds.orEmpty().toSet()
        val kbItems = KnowledgeRepository.getItems(this)
        container.addView(TextView(this).apply {
            text = if (kbItems.isEmpty())
                "绑定知识库：暂无知识条目，可先在「AI 知识库」页导入"
            else "绑定知识库（问答/润色时仅检索勾选条目，不选则纯人设）"
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(12), 0, dp(4))
        })
        val kbChecks = mutableListOf<Pair<String, CheckBox>>()
        kbItems.forEach { item ->
            val box = CheckBox(this).apply {
                text = "${item.name}（${item.chunkCount} 块）"
                textSize = 14f
                isChecked = item.id in boundIds
            }
            kbChecks += item.id to box
            container.addView(box)
        }

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "编辑人设" else "新增人设")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (name.isEmpty()) {
                    showToast("角色名称不能为空")
                    return@setPositiveButton
                }
                if (prompt.isEmpty()) {
                    showToast("系统提示词不能为空")
                    return@setPositiveButton
                }
                val desc = descInput.text.toString().trim()
                val selectedKb = kbChecks.filter { it.second.isChecked }.map { it.first }
                if (isEdit) {
                    PersonaRepository.updateCustomPersona(this, existing!!.copy(
                        name = name, description = desc, systemPrompt = prompt,
                        knowledgeItemIds = selectedKb
                    ))
                    showToast("人设已更新")
                } else {
                    val saved = PersonaRepository.addCustomPersona(this, AiPersona(
                        id = name, name = name, description = desc,
                        systemPrompt = prompt, isBuiltin = false,
                        knowledgeItemIds = selectedKb
                    ))
                    // 新建后自动切换
                    PersonaRepository.setCurrentPersona(this, saved.id)
                    showToast("已新增并切换到: ${saved.name}")
                }
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 辅助 =====

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
