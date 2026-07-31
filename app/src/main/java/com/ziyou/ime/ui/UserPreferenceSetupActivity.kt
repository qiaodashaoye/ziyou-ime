package com.ziyou.ime.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ziyou.ime.data.AssociationManager
import com.ziyou.ime.data.UserPreferenceRepository
import com.ziyou.ime.ime.KeyboardType

/**
 * 字由输入法 首启偏好向导
 *
 * 在用户首次完成输入法启用与激活（ImeSetupActivity 检测到 ACTIVE）后自动展示，
 * 收集初始输入偏好：键盘布局、候选词数量、中文联想开关。
 * 完成后经 [UserPreferenceRepository.markSetupDone] 记录，后续启动不再展示。
 *
 * 视觉风格参考主流输入法的首启引导页：浅灰背景 + 白色圆角卡片单选
 * （选中态绿色描边 + ✓ 徽标）+ 迷你键盘布局预览 + 底部通栏绿色确认按钮。
 *
 * 交互约定：
 * - 每个选项点击即保存到仓库（而非等到「完成」统一提交）；
 * - 底部按钮仅负责标记向导完成并进入设置页。
 *
 * 与项目规范一致：纯代码布局（不依赖 XML），统一 [TitleBarView] 标题栏。
 */
class UserPreferenceSetupActivity : AppCompatActivity() {

    companion object {
        // 参考图配色：微信绿强调色 + 浅灰页面底 + 白色卡片
        private const val COLOR_ACCENT = 0xFF07C160.toInt()
        private const val COLOR_PAGE_BG = 0xFFF2F3F5.toInt()
        private const val COLOR_CARD_BG = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF212121.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF757575.toInt()
        private const val COLOR_RING_UNSELECTED = 0xFFC7C7C7.toInt()
    }

    // 各选项组（组内单选，点击互斥刷新）
    private val layoutCards = mutableListOf<Pair<KeyboardType, ChoiceCard>>()
    private val countCards = mutableListOf<Pair<Int, ChoiceCard>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithTitleBar("初始偏好设置", buildView())
    }

    // ===== 视图构建 =====

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAGE_BG)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // ===== 键盘布局 =====
        content.addView(createHeadline("选择你喜欢的键盘"))
        content.addView(createSubline("可随时在键盘功能栏或设置页中切换"))
        val layoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val currentLayout = UserPreferenceRepository.getKeyboardLayout(this)
        listOf(
            KeyboardType.NINE_GRID to "九宫格拼音",
            KeyboardType.QWERTY to "全键盘拼音",
        ).forEachIndexed { index, (type, label) ->
            val card = ChoiceCard(label, MiniKeyboardView(this, type)) {
                UserPreferenceRepository.setKeyboardLayout(this, type)
                selectIn(layoutCards, type)
            }
            card.view.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                setMargins(if (index == 0) 0 else dp(6), 0, if (index == 0) dp(6) else 0, 0)
            }
            layoutCards.add(type to card)
            layoutRow.addView(card.view)
        }
        content.addView(layoutRow)
        selectIn(layoutCards, currentLayout)

        // ===== 候选词数量 =====
        content.addView(createHeadline("候选词数量"))
        content.addView(createSubline("每页展示的候选词个数"))
        val countRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val options = UserPreferenceRepository.CANDIDATE_PAGE_SIZE_OPTIONS
        options.forEachIndexed { index, size ->
            val card = ChoiceCard("$size 个", preview = null) {
                UserPreferenceRepository.setCandidatePageSize(this, size)
                selectIn(countCards, size)
            }
            card.view.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                setMargins(
                    if (index == 0) 0 else dp(6), 0,
                    if (index == options.lastIndex) 0 else dp(6), 0
                )
            }
            countCards.add(size to card)
            countRow.addView(card.view)
        }
        content.addView(countRow)
        selectIn(countCards, UserPreferenceRepository.getCandidatePageSize(this))

        // ===== 输入习惯 =====
        content.addView(createHeadline("输入习惯"))
        content.addView(createAssociationSwitchCard())

        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ===== 底部通栏确认按钮（参考图样式） =====
        root.addView(TextView(this).apply {
            text = "开始体验 字由输入法"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(COLOR_ACCENT)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
            )
            setOnClickListener { finishSetup() }
        })

        return root
    }

    private fun createHeadline(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
    }

    private fun createSubline(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
    }

    /** 中文联想开关卡片（白色圆角卡片 + Switch，直接读写 AssociationManager） */
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun createAssociationSwitchCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(COLOR_CARD_BG)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(12), 0, 0)
            }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = "中文联想"
                    textSize = 16f
                    setTextColor(COLOR_TEXT_PRIMARY)
                })
                addView(TextView(context).apply {
                    text = "上屏后展示引擎预测的联想词"
                    textSize = 13f
                    setTextColor(COLOR_TEXT_SECONDARY)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            addView(Switch(context).apply {
                isChecked = AssociationManager.isEnabled(this@UserPreferenceSetupActivity)
                setOnCheckedChangeListener { _, checked ->
                    AssociationManager.setEnabled(this@UserPreferenceSetupActivity, checked)
                }
            })
        }
    }

    // ===== 单选卡片 =====

    /** 刷新一组单选卡片：仅 [selectedKey] 对应的卡片呈选中态 */
    private fun <K> selectIn(group: List<Pair<K, ChoiceCard>>, selectedKey: K) {
        group.forEach { (key, card) -> card.setSelected(key == selectedKey) }
    }

    /**
     * 单选卡片：白色圆角底 + 标题 + 右上角圆形选中指示 + 可选预览区。
     * 选中态为绿色描边 + 绿底 ✓ 徽标（参考图样式）。
     */
    private inner class ChoiceCard(
        title: String,
        preview: View?,
        onClick: () -> Unit,
    ) {
        private val cardBg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(COLOR_CARD_BG)
        }
        private val titleView: TextView
        private val badge: TextView
        private val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_CARD_BG)
            setStroke(dp(2), COLOR_RING_UNSELECTED)
        }

        val view: LinearLayout = LinearLayout(this@UserPreferenceSetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBg
            isClickable = true
            isFocusable = true
            contentDescription = "选择$title"
            setOnClickListener { onClick() }
        }

        init {
            val header = LinearLayout(this@UserPreferenceSetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleView = TextView(this@UserPreferenceSetupActivity).apply {
                text = title
                textSize = 15f
                setTextColor(COLOR_TEXT_PRIMARY)
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            badge = TextView(this@UserPreferenceSetupActivity).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                background = badgeBg
            }
            header.addView(titleView)
            header.addView(badge)
            view.addView(header)

            if (preview != null) {
                preview.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92)
                ).apply {
                    setMargins(0, dp(10), 0, 0)
                }
                view.addView(preview)
            }
            setSelected(false)
        }

        fun setSelected(selected: Boolean) {
            if (selected) {
                cardBg.setStroke(dp(2), COLOR_ACCENT)
                titleView.setTextColor(COLOR_ACCENT)
                badge.text = "✓"
                badge.setTextColor(0xFFFFFFFF.toInt())
                badgeBg.setColor(COLOR_ACCENT)
                badgeBg.setStroke(0, 0)
            } else {
                cardBg.setStroke(dp(2), 0x00000000)
                titleView.setTextColor(COLOR_TEXT_PRIMARY)
                badge.text = ""
                badgeBg.setColor(COLOR_CARD_BG)
                badgeBg.setStroke(dp(2), COLOR_RING_UNSELECTED)
            }
        }
    }

    /**
     * 迷你键盘布局预览（纯 Canvas 绘制，参考图中卡片内的缩略键盘）：
     * 灰色面板底 + 白色圆角按键，按 QWERTY / 九宫格分别绘制简化键位。
     */
    private class MiniKeyboardView(
        context: Context,
        private val layout: KeyboardType,
    ) : View(context) {

        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE9EAEC.toInt()
        }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5F6368.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        // 简化键位：仅示意布局差异，不承载交互
        private val rows: List<List<String>> = when (layout) {
            KeyboardType.NINE_GRID -> listOf(
                listOf("@#", "ABC", "DEF"),
                listOf("GHI", "JKL", "MNO"),
                listOf("PQRS", "TUV", "WXYZ"),
                listOf("符", "123", "中")
            )
            else -> listOf(
                "QWERTYUIOP".map { it.toString() },
                "ASDFGHJKL".map { it.toString() },
                "ZXCVBNM".map { it.toString() }
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // 面板底
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), panelPaint)

            val pad = dp(6f)
            val gap = dp(3f)
            val rowH = (h - pad * 2 - gap * (rows.size - 1)) / rows.size
            textPaint.textSize = rowH * 0.42f

            rows.forEachIndexed { rowIndex, keys ->
                val keyW = (w - pad * 2 - gap * (keys.size - 1)) / keys.size
                // 字母行少于满宽时居中（QWERTY 第二、三行）
                val rowW = keyW * keys.size + gap * (keys.size - 1)
                var x = (w - rowW) / 2
                val top = pad + rowIndex * (rowH + gap)
                keys.forEach { label ->
                    rect.set(x, top, x + keyW, top + rowH)
                    canvas.drawRoundRect(rect, dp(3f), dp(3f), keyPaint)
                    val textY = rect.centerY() -
                        (textPaint.ascent() + textPaint.descent()) / 2
                    canvas.drawText(label, rect.centerX(), textY, textPaint)
                    x += keyW + gap
                }
            }
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }

    // ===== 完成流程 =====

    /**
     * 完成向导：各选项已在点击时落库，这里只需标记完成并进入设置页。
     * CLEAR_TOP 清掉栈中的引导页，避免返回键回到向导流程。
     */
    private fun finishSetup() {
        UserPreferenceRepository.markSetupDone(this)
        Toast.makeText(this, "偏好设置已保存", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
