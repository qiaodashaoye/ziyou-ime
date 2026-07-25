package com.ziyou.ime.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.DisplayModeManager
import com.ziyou.ime.config.ThemeManager
import com.ziyou.ime.daemon.RimeSession
import com.ziyou.ime.data.AssociationManager
import com.ziyou.ime.data.SideSymbol
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.level.LevelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字由输入法 设置页面
 * 提供以下功能：
 * - 输入方案选择（列表展示可用schema）
 * - 主题切换（Light/Dark/Material三选一）
 * - 同步用户词典
 * - 关于信息（版本号等）
 * - 跳转系统输入法设置
 *
 * 使用传统View + LinearLayout实现，保持简单轻量
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"

        /** Intent 额外项：打开时直接弹出九宫格拼音侧栏符号管理（由输入法侧栏「＋」触发） */
        const val EXTRA_OPEN_SIDE_SYMBOLS = "open_side_symbols"
    }

    // UI组件引用
    private lateinit var schemaValueText: TextView
    private lateinit var themeValueText: TextView
    private lateinit var levelValueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildSettingsView())
        title = "字由输入法 设置"

        // 通过 RimeSession 统一初始化引擎（异步，避免主线程阻塞和双重初始化）
        ensureRimeStarted()

        // 由输入法侧栏「＋」拉起时，直接弹出侧栏符号管理
        if (intent?.getBooleanExtra(EXTRA_OPEN_SIDE_SYMBOLS, false) == true) {
            window.decorView.post { showSideSymbolManager() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (RimeSession.initialized) {
            refreshDisplay()
        }
    }

    /**
     * 通过 RimeSession 统一初始化 Rime 引擎
     * 避免直接调用 RimeNative（线程不安全）并防止与 IMS 服务双重初始化
     */
    private fun ensureRimeStarted() {
        lifecycleScope.launch {
            try {
                if (!RimeSession.initialized) {
                    Log.i(TAG, "SettingsActivity: 开始初始化 RimeSession")
                    RimeSession.initialize(applicationContext, fullCheck = false)
                    Log.i(TAG, "SettingsActivity: RimeSession 初始化完成")
                }
                // 初始化完成后刷新显示
                withContext(Dispatchers.Main) {
                    refreshDisplay()
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动Rime引擎失败: ${e.message}", e)
            }
        }
    }

    /**
     * 构建设置页面的View层级
     * 使用代码创建布局，避免依赖XML布局文件
     */
    private fun buildSettingsView(): View {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // ===== 输入法启用提示 =====
        rootLayout.addView(createSectionHeader("输入法设置"))
        rootLayout.addView(createSettingItem(
            title = "启用输入法",
            summary = "在系统设置中启用字由输入法",
            onClick = { openInputMethodSettings() }
        ))
        rootLayout.addView(createDivider())

        // ===== 输入方案 =====
        rootLayout.addView(createSectionHeader("输入方案"))
        val schemaItem = createSettingItemWithValue(
            title = "当前方案",
            valueHolder = { schemaValueText = it }
        )
        schemaItem.setOnClickListener { showSchemaSelector() }
        rootLayout.addView(schemaItem)
        rootLayout.addView(createDivider())

        // ===== 主题设置 =====
        rootLayout.addView(createSectionHeader("外观"))
        val themeItem = createSettingItemWithValue(
            title = "键盘主题",
            valueHolder = { themeValueText = it }
        )
        themeItem.setOnClickListener { showThemeSelector() }
        rootLayout.addView(themeItem)
        rootLayout.addView(createDivider())

        // ===== 成长（等级体系）=====
        rootLayout.addView(createSectionHeader("成长"))
        val levelItem = createSettingItemWithValue(
            title = "我的等级",
            valueHolder = { levelValueText = it }
        )
        levelItem.setOnClickListener {
            startActivity(Intent(this, LevelActivity::class.java))
        }
        rootLayout.addView(levelItem)
        rootLayout.addView(createDivider())

        // ===== 输入 =====
        rootLayout.addView(createSectionHeader("输入"))
        rootLayout.addView(createSwitchItem(
            title = "中文联想",
            summary = "上屏后展示引擎预测的联想词（需启用 librime-predict 模块）",
            checked = AssociationManager.isEnabled(this),
            onChange = { enabled -> AssociationManager.setEnabled(this, enabled) }
        ))
        rootLayout.addView(createDivider())

        // ===== 悬浮键盘（游戏场景） =====
        rootLayout.addView(createSectionHeader("悬浮键盘"))
        rootLayout.addView(createSwitchItem(
            title = "悬浮键盘模式",
            summary = "键盘缩小为可拖拽的悬浮面板，面板外触摸穿透给应用（也可经键盘上的「浮」键切换）",
            checked = DisplayModeManager.isFloatingEnabled(this),
            onChange = { enabled -> DisplayModeManager.setFloatingEnabled(this, enabled) }
        ))
        rootLayout.addView(createSwitchItem(
            title = "横屏自动悬浮",
            summary = "横屏输入（如游戏内聊天）时自动切换为悬浮键盘",
            checked = DisplayModeManager.isAutoFloatInLandscape(this),
            onChange = { enabled -> DisplayModeManager.setAutoFloatInLandscape(this, enabled) }
        ))
        rootLayout.addView(createDivider())

        // ===== 九宫格 =====
        rootLayout.addView(createSectionHeader("九宫格"))
        rootLayout.addView(createSettingItem(
            title = "拼音侧栏符号",
            summary = "自定义九宫格左侧拼音栏无候选时的常用符号 / 短语",
            onClick = { showSideSymbolManager() }
        ))
        rootLayout.addView(createDivider())

        // ===== 数据同步 =====
        rootLayout.addView(createSectionHeader("数据"))
        rootLayout.addView(createSettingItem(
            title = "扩展词库",
            summary = "下载并管理专业词库扩展包",
            onClick = { startActivity(Intent(this, DictManagerActivity::class.java)) }
        ))
        rootLayout.addView(createSettingItem(
            title = "同步用户词典",
            summary = "同步用户自定义词组和输入历史",
            onClick = { syncUserData() }
        ))
        rootLayout.addView(createSettingItem(
            title = "重新部署",
            summary = "重新部署Rime配置文件（解决配置异常）",
            onClick = { redeployRime() }
        ))
        rootLayout.addView(createDivider())

        // ===== 关于 =====
        rootLayout.addView(createSectionHeader("关于"))
        rootLayout.addView(createSettingItem(
            title = "版本",
            summary = getVersionInfo(),
            onClick = null
        ))
        rootLayout.addView(createSettingItem(
            title = "字由输入法",
            summary = "基于Rime引擎的简洁中文输入法",
            onClick = null
        ))

        scrollView.addView(rootLayout)
        return scrollView
    }

    // ===== 功能方法 =====

    private fun openInputMethodSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "无法打开输入法设置", e)
            showToast("无法打开系统设置")
        }
    }

    private fun showSchemaSelector() {
        lifecycleScope.launch {
            try {
                val schemas = RimeSession.api.getSchemaList()
                if (schemas.isEmpty()) {
                    showToast("无法获取方案列表，请确保Rime引擎已启动")
                    return@launch
                }

                val currentSchema = RimeSession.api.getCurrentSchema()
                val currentIndex = schemas.indexOfFirst { it.schemaId == currentSchema }
                val schemaNames = schemas.map { it.name }.toTypedArray()

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("选择输入方案")
                        .setSingleChoiceItems(schemaNames, currentIndex) { dialog, which ->
                            val selectedSchema = schemas[which]
                            lifecycleScope.launch {
                                RimeSession.api.selectSchema(selectedSchema.schemaId)
                                withContext(Dispatchers.Main) {
                                    schemaValueText.text = selectedSchema.name
                                    showToast("已切换到: ${selectedSchema.name}")
                                }
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取方案列表异常: ${e.message}", e)
                showToast("获取方案列表失败")
            }
        }
    }

    private fun showThemeSelector() {
        val themeNames = ThemeManager.getAllThemeNames().toTypedArray()
        val displayNames = arrayOf("Light（浅色）", "Dark（深色）", "Material（蓝色调）")
        val currentTheme = ThemeManager.getCurrentThemeName(this)
        val currentIndex = themeNames.indexOf(currentTheme)

        AlertDialog.Builder(this)
            .setTitle("选择键盘主题")
            .setSingleChoiceItems(displayNames, currentIndex) { dialog, which ->
                val selectedTheme = themeNames[which]
                ThemeManager.setTheme(this, selectedTheme)
                themeValueText.text = displayNames[which]
                Log.i(TAG, "切换主题: $selectedTheme")
                showToast("主题已切换为: ${displayNames[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 九宫格拼音侧栏符号管理 =====

    /**
     * 侧栏符号管理入口：列出已有符号（点击删除），并提供添加 / 恢复默认。
     * 对应 yuyansdk 中的侧边符号设置页。
     */
    private fun showSideSymbolManager() {
        val symbols = SideSymbolRepository.getPinyinSideSymbols(this)
        val labels = symbols.map { "${it.display}    →    ${it.value}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("拼音侧栏符号")
            .setItems(labels) { _, which -> confirmDeleteSideSymbol(symbols[which]) }
            .setPositiveButton("添加") { _, _ -> showAddSideSymbolDialog() }
            .setNeutralButton("恢复默认") { _, _ ->
                SideSymbolRepository.resetToDefault(this)
                showToast("已恢复默认侧栏符号")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 添加侧栏符号：输入显示文字 + 上屏内容 */
    private fun showAddSideSymbolDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val displayInput = EditText(this).apply { hint = "显示文字（如 ， 或 邮箱）" }
        val valueInput = EditText(this).apply { hint = "上屏内容（留空则与显示文字相同）" }
        container.addView(displayInput)
        container.addView(valueInput)
        AlertDialog.Builder(this)
            .setTitle("添加侧栏符号")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val display = displayInput.text.toString().trim()
                if (display.isEmpty()) {
                    showToast("显示文字不能为空")
                    return@setPositiveButton
                }
                val value = valueInput.text.toString().ifEmpty { display }
                SideSymbolRepository.addPinyinSideSymbol(this, SideSymbol(display, value))
                showToast("已添加")
                showSideSymbolManager()
            }
            .setNegativeButton("取消") { _, _ -> showSideSymbolManager() }
            .show()
    }

    /** 删除确认 */
    private fun confirmDeleteSideSymbol(symbol: SideSymbol) {
        AlertDialog.Builder(this)
            .setTitle("删除符号")
            .setMessage("确定删除「${symbol.display}」？")
            .setPositiveButton("删除") { _, _ ->
                SideSymbolRepository.removePinyinSideSymbol(this, symbol.display)
                showToast("已删除")
                showSideSymbolManager()
            }
            .setNegativeButton("取消") { _, _ -> showSideSymbolManager() }
            .show()
    }

    private fun syncUserData() {
        showToast("开始同步用户词典...")
        lifecycleScope.launch {
            try {
                val success = RimeSession.api.syncUserData()
                withContext(Dispatchers.Main) {
                    if (success) {
                        showToast("用户词典同步完成")
                    } else {
                        showToast("同步失败，请确保Rime引擎已启动")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "同步用户词典异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showToast("同步失败")
                }
            }
        }
    }

    private fun redeployRime() {
        AlertDialog.Builder(this)
            .setTitle("重新部署")
            .setMessage("将重新部署所有Rime配置文件，可能需要几秒钟。是否继续？")
            .setPositiveButton("确定") { _, _ ->
                showToast("开始重新部署...")
                val success = AssetDeployer.forceDeploy(this)
                if (success) {
                    showToast("部署完成，重启输入法后生效")
                    Log.i(TAG, "重新部署成功")
                } else {
                    showToast("部署失败")
                    Log.e(TAG, "重新部署失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDisplay() {
        if (::schemaValueText.isInitialized && RimeSession.initialized) {
            lifecycleScope.launch {
                try {
                    val currentSchema = RimeSession.api.getCurrentSchema()
                    val schemas = RimeSession.api.getSchemaList()
                    val schemaName = schemas.firstOrNull { it.schemaId == currentSchema }?.name
                    withContext(Dispatchers.Main) {
                        schemaValueText.text = schemaName ?: "未知方案"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "刷新方案显示失败: ${e.message}")
                }
            }
        }

        if (::themeValueText.isInitialized) {
            val themeName = ThemeManager.getCurrentThemeName(this)
            themeValueText.text = when (themeName) {
                ThemeManager.THEME_LIGHT -> "Light（浅色）"
                ThemeManager.THEME_DARK -> "Dark（深色）"
                ThemeManager.THEME_MATERIAL -> "Material（蓝色调）"
                else -> themeName
            }
        }

        if (::levelValueText.isInitialized) {
            val levelState = LevelRepository.load(this)
            levelValueText.text = "Lv.${levelState.level} ${LevelEngine.levelName(levelState.level)}"
        }
    }

    // ===== UI辅助方法 =====

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
            setPadding(dp(4), dp(16), dp(4), dp(8))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    private fun createSettingItem(title: String, summary: String, onClick: (() -> Unit)?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(12), dp(4), dp(12))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onClick() }
            }

            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(0xFF212121.toInt())
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 13f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun createSettingItemWithValue(title: String, valueHolder: (TextView) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(0xFF212121.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })

            val valueText = TextView(context).apply {
                textSize = 14f
                setTextColor(0xFF757575.toInt())
                text = "..."
            }
            valueHolder(valueText)
            addView(valueText)

            addView(TextView(context).apply {
                text = " ›"
                textSize = 18f
                setTextColor(0xFFBDBDBD.toInt())
            })
        }
    }

    /** 带开关的设置项（标题 + 说明 + 右侧 Switch），用于布尔型开关如中文联想 */
    private fun createSwitchItem(
        title: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(12), dp(4), dp(12))
            gravity = Gravity.CENTER_VERTICAL

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    textSize = 16f
                    setTextColor(0xFF212121.toInt())
                })
                addView(TextView(context).apply {
                    text = summary
                    textSize = 13f
                    setTextColor(0xFF757575.toInt())
                    setPadding(0, dp(2), 0, 0)
                })
            })

            addView(Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
    }

    private fun dp(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getVersionInfo(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "v${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "未知版本"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
