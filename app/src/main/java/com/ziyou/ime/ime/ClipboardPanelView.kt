package com.ziyou.ime.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ziyou.ime.skin.SkinTheme
import com.ziyou.ime.core.clipboard.ClipboardEntry
import com.ziyou.ime.core.clipboard.ClipboardHistoryLogic

/**
 * 粘贴板历史面板：内部结构为「标题栏 + 条目列表」。
 *
 * 挂载在输入视图内容根容器顶部并立即收起键盘/候选区（见 [ClipboardPanelCoordinator]），
 * 与涂鸦面板同一高度守恒策略。面板无文字输入需求，**不接管 commitTarget 输入路由**，
 * 点击条目经宿主走 commitDirectToEditor 直达输入框，对输入链路零侵入。
 *
 * 标题栏：标题（含条目计数）+ 清空 + 关闭 ✕；「清空」带二次确认
 * （首次点击变为「确认清空？」强调色态，超时未再点自动复原），避免误触毁掉全部历史。
 * 列表区：ScrollView 纵向滚动（容量上限 10 条，无需 RecyclerView），
 * 每行 = 文本（最多两行省略）+ 相对时间 + 删除 ✕；空态显示占位文案。
 *
 * 配色全部映射自当前 [SkinTheme]，与 [DoodlePanelView] 同一视觉语言；
 * 面板整体 clickable，阻断触摸穿透到下层视图。
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val theme: SkinTheme,
    private val host: Host
) : LinearLayout(context) {

    /** 宿主（协调器）需提供的能力。 */
    interface Host {
        /** 用户请求关闭面板（宿主负责移除视图） */
        fun onRequestClose()

        /** 点击条目：粘贴文本到宿主输入框（宿主粘贴后负责关闭面板） */
        fun onPasteEntry(entry: ClipboardEntry)

        /** 删除单条（宿主更新存储后回调 [submitEntries] 刷新列表） */
        fun onDeleteEntry(entry: ClipboardEntry)

        /** 清空全部历史（已经过面板内二次确认） */
        fun onClearAll()

        /** 按键震动反馈 */
        fun performHaptic()
    }

    companion object {
        /** 「清空」二次确认态自动复原的超时（ms） */
        private const val CLEAR_CONFIRM_TIMEOUT_MS = 3000L
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    /** 标题（含条目计数） */
    private val titleView: TextView

    /** 清空按钮（二次确认态切换文案与强调色） */
    private val clearButton: TextView

    /** 条目列表容器（ScrollView 内） */
    private val listLayout: LinearLayout

    /** 空态占位文案 */
    private val emptyView: TextView

    /** 是否处于「清空」二次确认态 */
    private var clearConfirming = false

    /** 二次确认态超时复原任务（childCount 含索引 0 的空态占位，>1 才有真条目） */
    private val clearConfirmReset = Runnable {
        clearConfirming = false
        refreshClearButton(hasEntries = listLayout.childCount > 1)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.keyboardBackground)
        // 阻断触摸穿透到下层视图
        isClickable = true

        // ── 标题栏：标题 + 清空 + 关闭 ✕ ──
        titleView = TextView(context).apply {
            text = "粘贴板历史"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER_VERTICAL
        }
        clearButton = createToolButton("清空") { handleClearClick() }
        val closeButton = createToolButton("✕") { host.onRequestClose() }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(10f), dp(8f))
            addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // 弹性占位：标题靠左，操作钮靠右
            addView(View(context), LayoutParams(0, 0, 1f))
            addView(clearButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)))
            addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(32f)).apply {
                marginStart = dp(6f)
            })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // ── 列表区：ScrollView 接管剩余空间 ──
        listLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(10f), 0, dp(10f), dp(8f))
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(listLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // ── 空态占位（与列表区叠放语义：无条目时代替列表展示）──
        emptyView = TextView(context).apply {
            text = "暂无粘贴板记录\n复制文本后自动收录（最多 ${ClipboardHistoryLogic.MAX_ENTRIES} 条）"
            textSize = 13f
            setTextColor(theme.keyTextColor)
            alpha = 0.5f
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
            visibility = GONE
        }
        listLayout.addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ===== 数据驱动刷新 =====

    /** 提交条目列表并整体重建行视图（≤10 条，重建开销可忽略；含空态切换） */
    fun submitEntries(entries: List<ClipboardEntry>) {
        // 保留空态占位（索引 0），移除其余行
        while (listLayout.childCount > 1) {
            listLayout.removeViewAt(listLayout.childCount - 1)
        }
        emptyView.visibility = if (entries.isEmpty()) VISIBLE else GONE
        titleView.text = if (entries.isEmpty()) "粘贴板历史"
            else "粘贴板历史 (${entries.size}/${ClipboardHistoryLogic.MAX_ENTRIES})"
        val now = System.currentTimeMillis()
        for (entry in entries) {
            listLayout.addView(createEntryRow(entry, now), LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            })
        }
        // 数据变化即退出清空确认态
        removeCallbacks(clearConfirmReset)
        clearConfirming = false
        refreshClearButton(hasEntries = entries.isNotEmpty())
    }

    // ===== 行构建 =====

    /** 单条目行：文本（两行省略）+ 相对时间（左，点击粘贴）+ 删除 ✕（右） */
    private fun createEntryRow(entry: ClipboardEntry, now: Long): View {
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = entry.text
                textSize = 13f
                setTextColor(theme.keyTextColor)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = ClipboardHistoryLogic.formatRelativeTime(entry.timestamp, now)
                textSize = 10f
                setTextColor(theme.keyTextColor)
                alpha = 0.5f
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2f)
            })
        }
        val deleteButton = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(theme.keyTextColor)
            alpha = 0.6f
            gravity = Gravity.CENTER
            contentDescription = "删除该条"
            setOnClickListener {
                host.performHaptic()
                host.onDeleteEntry(entry)
            }
        }
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(theme.keyBackground, 10f, theme.borderColor)
            setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
            addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(deleteButton, LayoutParams(dp(36f), dp(36f)))
            contentDescription = "粘贴：${entry.text}"
            setOnClickListener {
                host.performHaptic()
                host.onPasteEntry(entry)
            }
        }
    }

    // ===== 清空二次确认 =====

    /** 首次点击进入确认态，确认态内再点执行清空（超时自动复原） */
    private fun handleClearClick() {
        if (listLayout.childCount <= 1) return // 仅剩空态占位，无可清空
        if (clearConfirming) {
            removeCallbacks(clearConfirmReset)
            clearConfirming = false
            host.onClearAll()
        } else {
            clearConfirming = true
            refreshClearButton(hasEntries = true)
            postDelayed(clearConfirmReset, CLEAR_CONFIRM_TIMEOUT_MS)
        }
    }

    /** 刷新清空按钮：确认态换强调色文案，无条目时置灰 */
    private fun refreshClearButton(hasEntries: Boolean) {
        clearButton.text = if (clearConfirming) "确认清空？" else "清空"
        clearButton.alpha = if (hasEntries) 1f else 0.4f
        clearButton.background = if (clearConfirming) {
            roundedBg(theme.keyPressedBackground, 8f, theme.candidateHighlightColor)
        } else {
            roundedBg(theme.keyBackground, 8f, theme.borderColor)
        }
        clearButton.setTextColor(
            if (clearConfirming) theme.candidateHighlightColor else theme.keyTextColor
        )
    }

    // ===== 通用构件（与 DoodlePanelView 同一视觉基因）=====

    /** 工具按钮（清空/关闭共用样式） */
    private fun createToolButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(theme.keyTextColor)
            gravity = Gravity.CENTER
            setPadding(dp(10f), 0, dp(10f), 0)
            background = roundedBg(theme.keyBackground, 8f, theme.borderColor)
            setOnClickListener {
                host.performHaptic()
                onClick()
            }
        }
    }

    /** 圆角背景（可选描边），与键盘主题色一致。 */
    private fun roundedBg(color: Int, radiusDp: Float, strokeColor: Int?): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1f), it) }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(clearConfirmReset)
        super.onDetachedFromWindow()
    }
}
