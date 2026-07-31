package com.ziyou.ime.ui

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ziyou.ime.R

/**
 * 统一顶部标题栏（纯代码构建，遵循项目「禁止 XML 布局」约定）。
 *
 * 应用主题为 [R.style.Theme_Ziyou]（parent = Theme.AppCompat.DayNight.NoActionBar），
 * 系统 ActionBar 不存在，因此各 Activity 里的 `title = "..."` 只会写入任务管理器标题，
 * 界面上不显示任何标题栏——本组件即为该缺口提供统一实现。
 *
 * 配色取自 [R.color.primary]，与 Theme.Ziyou 的 colorPrimary 及设置页分区标题同源，
 * 不引入新色值。
 *
 * 注意：本组件仅服务于 Activity（App 内设置类界面），与输入法服务
 * [com.ziyou.ime.ime.ZiYouInputMethodService] 无关——IME 渲染在系统输入窗口中，
 * 没有 Activity 装饰区，不得挂载标题栏。
 */
class TitleBarView(
    context: Context,
    title: CharSequence,
    showBack: Boolean,
    onBack: (() -> Unit)?
) : LinearLayout(context) {

    private val titleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.primary))
        // 与内容区形成层次，滚动内容从标题栏下方穿过时不至于糊在一起
        elevation = dp(4).toFloat()
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP))

        if (showBack && onBack != null) {
            addView(TextView(context).apply {
                text = "←"
                textSize = 22f
                setTextColor(COLOR_ON_PRIMARY)
                gravity = Gravity.CENTER
                // 无障碍：箭头字符本身不可读，显式给出朗读文案
                contentDescription = "返回"
                minimumWidth = dp(TOUCH_TARGET_DP)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onBack() }
                layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT
                )
            })
        }

        titleView = TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(COLOR_ON_PRIMARY)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            // 长标题截断而非换行，保证标题栏高度恒定
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            // 有返回键时与箭头留出间距；无返回键时与屏幕左边缘对齐留白
            val startPad = if (showBack && onBack != null) dp(4) else dp(16)
            setPadding(startPad, 0, dp(16), 0)
        }
        addView(titleView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    /** 动态更新标题文案（如页面内状态切换）。 */
    fun setTitleText(text: CharSequence) {
        titleView.text = text
    }

    /** 供 [setContentViewWithTitleBar] 施加状态栏内边距，避免与状态栏重叠。 */
    internal fun applyTopInset(inset: Int) {
        layoutParams = layoutParams.also { it.height = dp(BAR_HEIGHT_DP) + inset }
        setPadding(paddingLeft, inset, paddingRight, paddingBottom)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BAR_HEIGHT_DP = 56
        const val TOUCH_TARGET_DP = 48
        const val COLOR_ON_PRIMARY = 0xFFFFFFFF.toInt()
    }
}

/**
 * 为 Activity 装配统一标题栏：纵向排布「标题栏 + 原内容」并设为 contentView。
 *
 * 同时把 [title] 写入 Activity 的 title 属性（任务管理器/无障碍读取），
 * 调用处因此不需要再单独赋值 `title = ...`。
 *
 * 接收者取 [ComponentActivity]（AppCompatActivity 的父类），使 View 页面与 Compose 页面
 * 共用同一个标题栏实例：Compose 页面把 `ComposeView` 作为 [content] 传入即可，
 * 从而避免「View 版 + Compose 版」两套标题栏样式各自漂移。
 *
 * 窗口内边距：targetSdk 35 起 Android 15 强制 edge-to-edge，内容会绘制到状态栏/
 * 导航栏之下。这里统一给标题栏补状态栏高度、给根容器补导航栏高度；
 * 在旧版本非 edge-to-edge 场景下 DecorView 已消费该内边距，取到 0 不会重复留白，
 * 因此同一份代码在各显示形态下都能正确显示。
 *
 * @param showBack 是否显示返回键。启动器根页面（[ImeSetupActivity]）应传 false，
 *                 因为其上层没有可返回的页面。
 * @return 标题栏实例，便于后续调用 [TitleBarView.setTitleText]。
 */
fun ComponentActivity.setContentViewWithTitleBar(
    title: CharSequence,
    content: View,
    showBack: Boolean = true
): TitleBarView {
    this.title = title

    val titleBar = TitleBarView(
        context = this,
        title = title,
        showBack = showBack,
        onBack = { onBackPressedDispatcher.onBackPressed() }
    )

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(titleBar)
        // 内容区占满剩余高度：内部为 ScrollView/WebView 时由其自行滚动
        addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        titleBar.applyTopInset(bars.top)
        view.setPadding(0, 0, 0, bars.bottom)
        // 不消费：内容区内部组件可能仍需读取内边距
        insets
    }

    setContentView(root)
    return titleBar
}
