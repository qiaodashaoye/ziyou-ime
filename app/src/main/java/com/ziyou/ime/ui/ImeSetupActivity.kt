package com.ziyou.ime.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ziyou.ime.core.routing.StartupRouteLogic
import com.ziyou.ime.data.UserPreferenceRepository

/**
 * 字由输入法 启用引导页（应用启动页 / Launcher Activity）
 *
 * 专注于输入法的启用与切换引导流程（参考 Gboard / 搜狗输入法的首次设置向导）：
 * 1. 检测字由输入法是否已在系统中启用，未启用则引导前往系统输入法设置；
 * 2. 检测当前激活的输入法是否为字由，未激活则唤起系统输入法选择器切换；
 * 3. 两步均完成（ACTIVE）后展示就绪状态，此时才允许进入设置页 [SettingsActivity]。
 *
 * 访问控制：本页是设置页的唯一入口门禁 —— SettingsActivity 在 onCreate 中会
 * 反向校验 [isImeReady]，未就绪时自动跳回本页，确保用户不会在未正确配置
 * 输入法的情况下使用设置功能。
 *
 * 启动路由：输入法已就绪（ACTIVE）时，从桌面再次打开应用不再停留在本页，
 * 而是按 [StartupRouteLogic] 直达设置页（或首启偏好向导）；仅在未就绪、
 * 或经设置页「启用与切换引导」入口（携带 [EXTRA_SHOW_GUIDE]）打开时才展示引导。
 *
 * 状态在 onResume / onWindowFocusChanged 时自动刷新：
 * 用户从系统设置页返回、或关闭输入法选择器对话框后，步骤卡片即时更新。
 *
 * 与项目规范一致：纯代码布局（不依赖 XML），Material 风格配色。
 */
class ImeSetupActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ImeSetupActivity"

        /** Intent 额外项：即使输入法已就绪也强制展示引导页（设置页「启用与切换引导」入口） */
        const val EXTRA_SHOW_GUIDE = "show_guide"

        // Material 配色
        private const val COLOR_PRIMARY = 0xFF1976D2.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF212121.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF616161.toInt()
        private const val COLOR_DONE = 0xFF43A047.toInt()
        private const val COLOR_CARD_PENDING = 0xFFF5F5F5.toInt()
        private const val COLOR_CARD_ACTIVE = 0xFFE3F2FD.toInt()
        private const val COLOR_CARD_DONE = 0xFFE8F5E9.toInt()

        /** 输入法在系统中的启用/激活状态 */
        enum class ImeState {
            /** 未在系统输入法列表中启用 */
            NOT_ENABLED,
            /** 已启用但当前激活的不是字由输入法 */
            NOT_CURRENT,
            /** 已启用且正在使用 */
            ACTIVE,
        }

        /**
         * 检测字由输入法的系统状态：
         * - 是否启用：经 [InputMethodManager.getEnabledInputMethodList] 按包名匹配；
         * - 是否激活：读取 Settings.Secure.DEFAULT_INPUT_METHOD（当前输入法的组件 ID，
         *   无需额外权限；InputMethodManager 直到 API 34 才提供等价的公开 API）。
         */
        fun detectImeState(context: Context): ImeState {
            return try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                val enabled = imm.enabledInputMethodList
                    .any { it.packageName == context.packageName }
                if (!enabled) return ImeState.NOT_ENABLED

                val currentImeId = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                )
                val isCurrent = currentImeId
                    ?.let {
                        ComponentName.unflattenFromString(it)?.packageName ==
                            context.packageName
                    }
                    ?: false
                if (isCurrent) ImeState.ACTIVE else ImeState.NOT_CURRENT
            } catch (e: Exception) {
                // 检测失败时按已就绪处理，避免误导用户去做多余操作
                Log.w(TAG, "检测输入法状态失败: ${e.message}", e)
                ImeState.ACTIVE
            }
        }

        /** 输入法是否已启用且激活（供外部快速判断是否需要展示引导页） */
        fun isImeReady(context: Context): Boolean =
            detectImeState(context) == ImeState.ACTIVE
    }

    // 步骤卡片组件引用（在 refreshState 中按状态动态更新）
    private lateinit var step1Card: StepCard
    private lateinit var step2Card: StepCard
    private lateinit var doneLayout: LinearLayout

    /** 首启偏好向导是否已自动拉起过（防止焦点变化反复触发） */
    private var autoShownPreferenceSetup = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启动路由：输入法已就绪时不再展示引导，直达设置页（或首启偏好向导）；
        // 判定逻辑下沉在 :core-logic 的 StartupRouteLogic，配套 JVM 单测
        val destination = StartupRouteLogic.route(
            imeReady = isImeReady(this),
            preferenceSetupDone = UserPreferenceRepository.isSetupDone(this),
            forceShowGuide = intent?.getBooleanExtra(EXTRA_SHOW_GUIDE, false) == true
        )
        when (destination) {
            StartupRouteLogic.Destination.SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
                return
            }
            StartupRouteLogic.Destination.PREFERENCE_WIZARD -> {
                // 向导完成后自动进入设置页（见 UserPreferenceSetupActivity.finishSetup）
                startActivity(Intent(this, UserPreferenceSetupActivity::class.java))
                finish()
                return
            }
            StartupRouteLogic.Destination.SETUP_GUIDE -> Unit // 继续展示引导
        }

        // 启动器根页面：上层无可返回的页面，故不展示返回键
        setContentViewWithTitleBar("设置字由输入法", buildView(), showBack = false)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统输入法设置页返回，重新检测状态
        refreshState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统输入法选择器是覆盖式对话框，关闭时不触发 onResume，
        // 因此在重新获得焦点时也刷新一次状态
        if (hasFocus) {
            refreshState()
        }
    }

    // ===== 视图构建 =====

    private fun buildView(): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        // 标题与说明
        root.addView(TextView(this).apply {
            text = "欢迎使用字由输入法"
            textSize = 24f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "完成以下两步设置后即可进入设置页开始使用。" +
                "设置过程中不会收集任何个人信息。"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(24))
        })

        // 步骤一：启用输入法
        step1Card = StepCard(
            stepNumber = "1",
            title = "启用字由输入法",
            description = "点击下方按钮前往系统设置，在输入法列表中打开「字由输入法」开关，然后返回本页。",
            buttonText = "启用 字由输入法",
            onButtonClick = { openInputMethodSettings() }
        )
        root.addView(step1Card.view)

        // 步骤二：切换输入法
        step2Card = StepCard(
            stepNumber = "2",
            title = "切换到字由输入法",
            description = "点击下方按钮，在弹出的系统选择器中选择「字由输入法」作为当前输入法。",
            buttonText = "切换到 字由输入法",
            onButtonClick = { showInputMethodPicker() }
        )
        root.addView(step2Card.view)

        // 完成区域：两步均就绪后展示
        doneLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
            visibility = View.GONE

            addView(TextView(context).apply {
                text = "✓"
                textSize = 48f
                setTextColor(COLOR_DONE)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "设置完成，字由输入法已就绪！"
                textSize = 16f
                setTextColor(COLOR_TEXT_PRIMARY)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(16))
            })
            addView(Button(context).apply {
                text = "进入设置页"
                isAllCaps = false
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = ColorStateList.valueOf(COLOR_DONE)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { enterSettings() }
            })
        }
        root.addView(doneLayout)

        scrollView.addView(root)
        return scrollView
    }

    /**
     * 步骤卡片：编号徽标 + 标题 + 说明 + 操作按钮。
     * 通过 [setState] 在待办 / 进行中 / 已完成三种视觉状态间切换。
     */
    private inner class StepCard(
        stepNumber: String,
        title: String,
        description: String,
        buttonText: String,
        onButtonClick: () -> Unit,
    ) {
        private val badge: TextView
        private val button: Button
        private val cardBg = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(COLOR_CARD_PENDING)
        }

        val view: LinearLayout = LinearLayout(this@ImeSetupActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(16))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = cardBg
        }

        init {
            // 标题行：圆形编号徽标 + 标题
            val titleRow = LinearLayout(this@ImeSetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            badge = TextView(this@ImeSetupActivity).apply {
                text = stepNumber
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    setMargins(0, 0, dp(12), 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(COLOR_PRIMARY)
                }
            }
            titleRow.addView(badge)
            titleRow.addView(TextView(this@ImeSetupActivity).apply {
                text = title
                textSize = 16f
                setTextColor(COLOR_TEXT_PRIMARY)
                setTypeface(null, Typeface.BOLD)
            })
            view.addView(titleRow)

            view.addView(TextView(this@ImeSetupActivity).apply {
                text = description
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(dp(40), dp(6), 0, 0)
            })

            button = Button(this@ImeSetupActivity).apply {
                text = buttonText
                isAllCaps = false
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = ColorStateList.valueOf(COLOR_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(40), dp(12), 0, 0)
                }
                setOnClickListener { onButtonClick() }
            }
            view.addView(button)
        }

        /**
         * 更新卡片视觉状态。
         * @param done 该步骤是否已完成（徽标变 ✓，隐藏按钮）
         * @param current 是否为当前待操作步骤（高亮底色，展示按钮）
         */
        fun setState(done: Boolean, current: Boolean) {
            when {
                done -> {
                    cardBg.setColor(COLOR_CARD_DONE)
                    badge.text = "✓"
                    (badge.background as GradientDrawable).setColor(COLOR_DONE)
                    button.visibility = View.GONE
                }
                current -> {
                    cardBg.setColor(COLOR_CARD_ACTIVE)
                    (badge.background as GradientDrawable).setColor(COLOR_PRIMARY)
                    button.visibility = View.VISIBLE
                }
                else -> {
                    // 待办（前序步骤未完成）：置灰并隐藏按钮，引导用户按顺序操作
                    cardBg.setColor(COLOR_CARD_PENDING)
                    (badge.background as GradientDrawable).setColor(0xFFBDBDBD.toInt())
                    button.visibility = View.GONE
                }
            }
        }
    }

    // ===== 状态刷新与系统跳转 =====

    /** 按当前系统状态刷新两张步骤卡片与完成区域 */
    private fun refreshState() {
        if (!::step1Card.isInitialized) return
        when (detectImeState(this)) {
            ImeState.NOT_ENABLED -> {
                step1Card.setState(done = false, current = true)
                step2Card.setState(done = false, current = false)
                doneLayout.visibility = View.GONE
            }
            ImeState.NOT_CURRENT -> {
                step1Card.setState(done = true, current = false)
                step2Card.setState(done = false, current = true)
                doneLayout.visibility = View.GONE
            }
            ImeState.ACTIVE -> {
                step1Card.setState(done = true, current = false)
                step2Card.setState(done = true, current = false)
                doneLayout.visibility = View.VISIBLE
                // 首次完成启用与激活后，自动展示初始偏好向导（仅一次）
                if (!UserPreferenceRepository.isSetupDone(this) && !autoShownPreferenceSetup) {
                    autoShownPreferenceSetup = true
                    startActivity(Intent(this, UserPreferenceSetupActivity::class.java))
                }
            }
        }
    }

    /**
     * 进入下一页（仅就绪后可达）：首次使用先进入初始偏好向导，
     * 向导完成过则直接进入设置页。
     * CLEAR_TOP + SINGLE_TOP：若本页是从设置页的「启用与切换引导」入口打开的，
     * 复用栈中已有的设置页而不是叠加新实例。
     */
    private fun enterSettings() {
        if (!UserPreferenceRepository.isSetupDone(this)) {
            startActivity(Intent(this, UserPreferenceSetupActivity::class.java))
        } else {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }

    /** 跳转系统输入法设置页（「语言和输入法 / 虚拟键盘」） */
    private fun openInputMethodSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "无法打开输入法设置", e)
            showToast("无法打开系统设置，请手动前往「设置 → 语言和输入法」启用")
        }
    }

    /** 唤起系统输入法选择器（标准切换流程）；异常时兜底跳转系统输入法设置页 */
    private fun showInputMethodPicker() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "无法唤起输入法选择器", e)
            showToast("无法唤起输入法选择器，请在系统设置中切换")
            openInputMethodSettings()
        }
    }

    // ===== 工具方法 =====

    private fun dp(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
