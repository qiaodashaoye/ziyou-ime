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
import com.ziyou.ime.ai.AiConfig
import com.ziyou.ime.ai.AiPersona
import com.ziyou.ime.ai.PersonaRepository
import com.ziyou.ime.ai.knowledge.KnowledgeRepository
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.DisplayModeManager
import com.ziyou.ime.config.SchemaPreference
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.data.AssociationManager
import com.ziyou.ime.data.SideSymbol
import com.ziyou.ime.data.SideSymbolRepository
import com.ziyou.ime.data.SymbolRepository
import com.ziyou.ime.data.ToolbarConfigRepository
import com.ziyou.ime.ime.KeyboardType
import com.ziyou.ime.ime.ToolbarItem
import com.ziyou.ime.core.toolbar.ToolbarConfigLogic
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.level.LevelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字由输入法 设置页面
 * 提供以下功能：
 * - 输入方案选择（列表展示可用schema）
 * - 皮肤管理（内置/导入皮肤、自定义外观，见 SkinActivity）
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
    private lateinit var personaValueText: TextView
    private lateinit var knowledgeValueText: TextView

    /** Rime 引擎（经 DI 容器获取，依赖接口而非 RimeSession 单例） */
    private val rime get() = AppContainer.rimeEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildSettingsView())
        title = "字由输入法 设置"

        // 经 DI 容器统一初始化引擎（异步，避免主线程阻塞和双重初始化）
        ensureRimeStarted()

        // 由输入法侧栏「＋」拉起时，直接弹出侧栏符号管理
        if (intent?.getBooleanExtra(EXTRA_OPEN_SIDE_SYMBOLS, false) == true) {
            window.decorView.post { showSideSymbolManager() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (rime.initialized) {
            refreshDisplay()
        }
    }

    /**
     * 经 DI 容器统一初始化 Rime 引擎
     * 避免直接调用 RimeNative（线程不安全）并防止与 IMS 服务双重初始化
     */
    private fun ensureRimeStarted() {
        lifecycleScope.launch {
            try {
                if (!rime.initialized) {
                    Log.i(TAG, "SettingsActivity: 开始初始化 Rime 引擎")
                    rime.initialize(applicationContext, fullCheck = false)
                    Log.i(TAG, "SettingsActivity: Rime 引擎初始化完成")
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
            title = "全键盘方案",
            valueHolder = { schemaValueText = it }
        )
        schemaItem.setOnClickListener { showSchemaSelector() }
        rootLayout.addView(schemaItem)
        rootLayout.addView(createDivider())

        // ===== 皮肤设置 =====
        rootLayout.addView(createSectionHeader("外观"))
        val themeItem = createSettingItemWithValue(
            title = "键盘皮肤",
            valueHolder = { themeValueText = it }
        )
        themeItem.setOnClickListener { openSkinManager() }
        rootLayout.addView(themeItem)
        rootLayout.addView(createSettingItem(
            title = "自定义功能栏",
            summary = "选择键盘上方功能栏显示的按钮与排列顺序，支持预设模板",
            onClick = { showToolbarCustomizer() }
        ))
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

        // ===== 符号键盘 =====
        rootLayout.addView(createSectionHeader("符号键盘"))
        rootLayout.addView(createSettingItem(
            title = "常用符号",
            summary = "自定义符号键盘「常用」分类的符号（键盘内长按符号也可加入/移除）",
            onClick = { showFavoriteSymbolManager() }
        ))
        rootLayout.addView(createDivider())

        // ===== 技能插件 =====
        rootLayout.addView(createSectionHeader("技能"))
        rootLayout.addView(createSettingItem(
            title = "技能插件",
            summary = "管理键盘「技」键唤出的技能，导入 .skill 技能包",
            onClick = { startActivity(Intent(this, SkillManagerActivity::class.java)) }
        ))
        rootLayout.addView(createDivider())

        // ===== AI 问答 =====
        rootLayout.addView(createSectionHeader("AI 问答"))
        rootLayout.addView(createSettingItem(
            title = "AI 服务配置",
            summary = "配置键盘「AI」键问答的服务地址与 API Key（OpenAI 兼容接口）",
            onClick = { showAiConfigDialog() }
        ))
        val personaItem = createSettingItemWithValue(
            title = "AI 人设",
            valueHolder = { personaValueText = it }
        )
        personaItem.setOnClickListener { showPersonaManager() }
        rootLayout.addView(personaItem)
        val knowledgeItem = createSettingItemWithValue(
            title = "AI 知识库",
            valueHolder = { knowledgeValueText = it }
        )
        knowledgeItem.setOnClickListener {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        }
        rootLayout.addView(knowledgeItem)
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
                // 布局专用方案（如九宫格的 t9）是实现细节，不作为用户选项暴露；
                // 此处选择的是「全键盘方案」，写入持久化偏好，由 IME 在
                // QWERTY 布局同步时对齐（九宫格仍强制使用专用 T9 方案）
                val schemas = rime.api.getSchemaList()
                    .filter { it.schemaId !in KeyboardType.FORCED_SCHEMA_IDS }
                if (schemas.isEmpty()) {
                    showToast("无法获取方案列表，请确保Rime引擎已启动")
                    return@launch
                }

                val preferredSchema = SchemaPreference.getQwertySchema(this@SettingsActivity)
                val currentIndex = schemas.indexOfFirst { it.schemaId == preferredSchema }
                val schemaNames = schemas.map { it.name }.toTypedArray()

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("选择全键盘方案")
                        .setSingleChoiceItems(schemaNames, currentIndex) { dialog, which ->
                            val selectedSchema = schemas[which]
                            lifecycleScope.launch {
                                // 先打引擎验证方案可用，成功才写入偏好（失败不静默吞掉）
                                val ok = rime.api.selectSchema(selectedSchema.schemaId)
                                withContext(Dispatchers.Main) {
                                    if (ok) {
                                        SchemaPreference.setQwertySchema(
                                            this@SettingsActivity, selectedSchema.schemaId)
                                        schemaValueText.text = selectedSchema.name
                                        showToast("已切换到: ${selectedSchema.name}")
                                    } else {
                                        Log.e(TAG, "切换方案失败: ${selectedSchema.schemaId}")
                                        showToast("切换方案失败: ${selectedSchema.name}")
                                    }
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

    /** 打开皮肤管理页（预览/切换/导入/自定义）。 */
    private fun openSkinManager() {
        startActivity(Intent(this, SkinActivity::class.java))
    }

    // ===== 功能栏定制 =====

    /**
     * 功能栏定制弹窗：勾选显示的按钮 + ↑↓ 调整顺序 + 套用预设模板。
     * 保存后由功能栏视图的配置监听即时生效，无需重启输入法。
     */
    private fun showToolbarCustomizer() {
        // 编辑态：已启用按钮（保存顺序）在前，未启用按钮按目录顺序追在后
        val savedIds = ToolbarConfigLogic.sanitize(
            ToolbarConfigRepository.getItemIds(this),
            ToolbarItem.ALL_IDS,
            ToolbarConfigRepository.DEFAULT_IDS
        )
        val savedItems = savedIds.mapNotNull { ToolbarItem.fromId(it) }
        var order = savedItems + ToolbarItem.entries.filter { it !in savedItems }
        val enabled = savedItems.toMutableSet()

        lateinit var adapter: BaseAdapter
        adapter = object : BaseAdapter() {
            override fun getCount() = order.size
            override fun getItem(position: Int) = order[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = order[position]
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                row.addView(CheckBox(this@SettingsActivity).apply {
                    isChecked = item in enabled
                    contentDescription = "显示${item.description}"
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) enabled.add(item) else enabled.remove(item)
                    }
                })
                row.addView(TextView(this@SettingsActivity).apply {
                    text = "${item.label}　${item.description}"
                    textSize = 15f
                    setTextColor(0xFF212121.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                // ↑↓ 排序按钮（移动逻辑复用 :core-logic 的 ToolbarConfigLogic）
                fun arrow(label: String, desc: String, offset: Int) = TextView(this@SettingsActivity).apply {
                    text = label
                    textSize = 18f
                    setTextColor(0xFF1976D2.toInt())
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    contentDescription = "$desc${item.description}"
                    isClickable = true
                    isFocusable = true
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener {
                        val moved = ToolbarConfigLogic.move(order.map { it.id }, position, offset)
                        order = moved.mapNotNull { ToolbarItem.fromId(it) }
                        adapter.notifyDataSetChanged()
                    }
                }
                row.addView(arrow("↑", "上移", -1))
                row.addView(arrow("↓", "下移", 1))
                return row
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        container.addView(TextView(this).apply {
            text = "勾选要显示的按钮，用 ↑↓ 调整顺序"
            textSize = 13f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        container.addView(Button(this).apply {
            text = "套用预设模板…"
            isAllCaps = false
            setOnClickListener {
                val presets = ToolbarConfigRepository.PRESETS
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("选择预设模板")
                    .setItems(presets.map { "${it.name}（${it.summary}）" }.toTypedArray()) { _, which ->
                        val presetItems = presets[which].itemIds.mapNotNull { ToolbarItem.fromId(it) }
                        order = presetItems + ToolbarItem.entries.filter { it !in presetItems }
                        enabled.clear()
                        enabled.addAll(presetItems)
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        container.addView(ListView(this).apply {
            this.adapter = adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)
            )
        })

        AlertDialog.Builder(this)
            .setTitle("自定义功能栏")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val ids = order.filter { it in enabled }.map { it.id }
                if (ids.isEmpty()) {
                    showToast("至少保留一个功能按钮")
                    return@setPositiveButton
                }
                ToolbarConfigRepository.setItemIds(this, ids)
                showToast("功能栏已更新")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                ToolbarConfigRepository.resetToDefault(this)
                showToast("已恢复默认功能栏")
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

    // ===== 符号键盘常用符号管理 =====

    /**
     * 常用符号管理入口：列出符号键盘「常用」分类的符号（点击删除），
     * 并提供添加 / 恢复默认。与拼音侧栏符号管理保持一致的交互风格。
     */
    private fun showFavoriteSymbolManager() {
        val symbols = SymbolRepository.getFavorites(this)
        AlertDialog.Builder(this)
            .setTitle("符号键盘常用符号")
            .setItems(symbols.toTypedArray()) { _, which -> confirmDeleteFavoriteSymbol(symbols[which]) }
            .setPositiveButton("添加") { _, _ -> showAddFavoriteSymbolDialog() }
            .setNeutralButton("恢复默认") { _, _ ->
                SymbolRepository.resetFavorites(this)
                showToast("已恢复默认常用符号")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 添加常用符号：输入要上屏的符号内容 */
    private fun showAddFavoriteSymbolDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val symbolInput = EditText(this).apply { hint = "符号内容（如 → 或 °C）" }
        container.addView(symbolInput)
        AlertDialog.Builder(this)
            .setTitle("添加常用符号")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val symbol = symbolInput.text.toString().trim()
                if (symbol.isEmpty()) {
                    showToast("符号内容不能为空")
                    return@setPositiveButton
                }
                SymbolRepository.addFavorite(this, symbol)
                showToast("已添加")
                showFavoriteSymbolManager()
            }
            .setNegativeButton("取消") { _, _ -> showFavoriteSymbolManager() }
            .show()
    }

    /** 删除确认 */
    private fun confirmDeleteFavoriteSymbol(symbol: String) {
        AlertDialog.Builder(this)
            .setTitle("移除常用符号")
            .setMessage("确定从「常用」中移除「$symbol」？")
            .setPositiveButton("移除") { _, _ ->
                SymbolRepository.removeFavorite(this, symbol)
                showToast("已移除")
                showFavoriteSymbolManager()
            }
            .setNegativeButton("取消") { _, _ -> showFavoriteSymbolManager() }
            .show()
    }

    // ===== AI 问答服务配置 =====

    /**
     * AI 服务配置弹窗：API 地址 / API Key / 模型名三项，
     * 保存前校验地址必须为 HTTPS（与 AiChatClient 的安全基线一致）。
     */
    private fun showAiConfigDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val urlInput = EditText(this).apply {
            hint = "API 地址（OpenAI 兼容 chat/completions）"
            setText(AiConfig.getApiUrl(this@SettingsActivity))
        }
        val keyInput = EditText(this).apply {
            hint = "API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(AiConfig.getApiKey(this@SettingsActivity))
        }
        val modelInput = EditText(this).apply {
            hint = "模型名（如 ${AiConfig.DEFAULT_MODEL}）"
            setText(AiConfig.getModel(this@SettingsActivity))
        }
        container.addView(urlInput)
        container.addView(keyInput)
        container.addView(modelInput)
        AlertDialog.Builder(this)
            .setTitle("AI 服务配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (!url.startsWith("https://")) {
                    showToast("API 地址必须以 https:// 开头")
                    return@setPositiveButton
                }
                AiConfig.save(this, url, keyInput.text.toString(), modelInput.text.toString())
                showToast("AI 服务配置已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== AI 人设管理 =====

    /**
     * 人设管理弹窗：自定义 ListView 支持点击切换 + 长按弹出操作菜单。
     *
     * AlertDialog.setItems 只能响应单击，无法满足“长按编辑/删除”的交互需求；
     * 这里改用 ListView + onItemClickListener / onItemLongClickListener 实现。
     */
    private fun showPersonaManager() {
        val personas = PersonaRepository.getAllPersonas(this)
        val currentId = PersonaRepository.getCurrentPersonaId(this)

        // 构建列表条目数据（双行布局：主行=名称+徽标，副行=简介）
        val items = personas.map { p ->
            val check = if (p.id == currentId) "✓ " else ""
            val badge = if (p.isBuiltin) " [内置]" else ""
            val desc = if (p.description.isNotBlank()) p.description else "（无简介）"
            PersonaListItem(title = "$check${p.name}$badge", subtitle = desc, persona = p)
        }

        val listView = ListView(this).apply {
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = items.size
                override fun getItem(position: Int) = items[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                    val item = items[position]
                    val row = (convertView as? TwoLineRow) ?: TwoLineRow(this@SettingsActivity)
                    row.title.text = item.title
                    row.subtitle.text = item.subtitle
                    row.subtitle.setTextColor(
                        if (item.persona.id == currentId) 0xFF1976D2.toInt()
                        else 0xFF757575.toInt()
                    )
                    return row
                }
            }
            dividerHeight = dp(1)
            // 单击：切换人设
            onItemClickListener = android.widget.AdapterView.OnItemClickListener { _, _, position, _ ->
                val selected = personas[position]
                PersonaRepository.setCurrentPersona(this@SettingsActivity, selected.id)
                personaValueText.text = selected.name
                showToast("已切换到: ${selected.name}")
            }
            // 长按：弹出操作菜单（预览 / 编辑 / 删除）
            onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, position, _ ->
                showPersonaActionsDialog(personas[position])
                true
            }
        }

        AlertDialog.Builder(this)
            .setTitle("AI 人设（长按管理）")
            .setView(listView)
            .setNeutralButton("新增人设") { _, _ -> showPersonaEditDialog(null) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 人设列表条目数据（主行标题 + 副行简介 + 关联 persona）。 */
    private data class PersonaListItem(
        val title: String, val subtitle: String, val persona: AiPersona
    )

    /** 人设列表双行行视图（复用 convertView 时减少重建开销）。 */
    private class TwoLineRow(context: android.content.Context) : LinearLayout(context) {
        val title: TextView
        val subtitle: TextView
        init {
            orientation = VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            title = TextView(context).apply {
                textSize = 16f
                setTextColor(0xFF212121.toInt())
            }
            subtitle = TextView(context).apply {
                textSize = 13f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(2), 0, 0)
                maxLines = 2
            }
            addView(title)
            addView(subtitle)
        }
        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    }

    /**
     * 人设操作菜单（长按触发）：内置人设仅可预览，自定义人设支持预览/编辑/删除。
     */
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

    /** 预览人设：以只读对话框展示角色名称、简介与系统提示词。 */
    private fun showPersonaPreview(persona: AiPersona) {
        val content = buildString {
            append("名称：${persona.name}\n")
            if (persona.description.isNotBlank()) append("简介：${persona.description}\n")
            append("\n系统提示词：\n${persona.systemPrompt}")
        }
        AlertDialog.Builder(this)
            .setTitle("人设预览")
            .setMessage(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 确认删除自定义人设。 */
    private fun confirmDeletePersona(persona: AiPersona) {
        AlertDialog.Builder(this)
            .setTitle("删除人设")
            .setMessage("确定删除「${persona.name}」？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                PersonaRepository.removeCustomPersona(this, persona.id)
                showToast("已删除")
                personaValueText.text = PersonaRepository.getCurrentPersona(this).name
                showPersonaManager()
            }
            .setNegativeButton("取消") { _, _ -> showPersonaManager() }
            .show()
    }

    /**
     * 人设编辑弹窗：角色名称 / 简介 / 系统提示词三字段。
     * [existing] 为 null 时新建，否则编辑现有自定义人设。
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
        AlertDialog.Builder(this)
            .setTitle(if (isEdit) "编辑人设" else "新增人设")
            .setView(container)
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
                if (isEdit) {
                    PersonaRepository.updateCustomPersona(this, existing!!.copy(
                        name = name, description = desc, systemPrompt = prompt
                    ))
                    showToast("人设已更新")
                } else {
                    val saved = PersonaRepository.addCustomPersona(this, AiPersona(
                        id = name, name = name, description = desc,
                        systemPrompt = prompt, isBuiltin = false
                    ))
                    // 新建后自动切换
                    PersonaRepository.setCurrentPersona(this, saved.id)
                    showToast("已新增并切换到: ${saved.name}")
                }
                personaValueText.text = PersonaRepository.getCurrentPersona(this).name
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun syncUserData() {
        showToast("开始同步用户词典...")
        lifecycleScope.launch {
            try {
                val success = rime.api.syncUserData()
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
            .setMessage("将重新部署所有Rime配置文件并重启引擎，可能需要几秒钟。是否继续？")
            .setPositiveButton("确定") { _, _ ->
                showToast("开始重新部署...")
                lifecycleScope.launch {
                    try {
                        // 强制重刷 assets（IO 线程，避免主线程递归复制整包资源 ANR）
                        val deployed = withContext(Dispatchers.IO) {
                            AssetDeployer.forceDeploy(applicationContext)
                        }
                        if (!deployed) {
                            showToast("部署失败")
                            return@launch
                        }
                        // 经 DI 容器热重启引擎：redeploy 会重新执行组合根装配的部署步骤
                        // （含扩展词库注入），避免 forceDeploy 覆盖主词库后扩展词库丢失，
                        // 且无需用户手动重启输入法
                        rime.redeploy(applicationContext)
                        showToast("重新部署完成")
                        refreshDisplay()
                        Log.i(TAG, "重新部署成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "重新部署失败: ${e.message}", e)
                        showToast("部署失败: ${e.message ?: "未知错误"}")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshDisplay() {
        if (::schemaValueText.isInitialized && rime.initialized) {
            lifecycleScope.launch {
                try {
                    // 展示持久化的全键盘方案偏好（而非引擎当前方案：
                    // 九宫格状态下引擎处于 t9，展示它会让用户困惑）
                    val preferredSchema = SchemaPreference.getQwertySchema(this@SettingsActivity)
                    val schemas = rime.api.getSchemaList()
                    val schemaName = schemas.firstOrNull { it.schemaId == preferredSchema }?.name
                    withContext(Dispatchers.Main) {
                        schemaValueText.text = schemaName ?: preferredSchema
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "刷新方案显示失败: ${e.message}")
                }
            }
        }

        if (::themeValueText.isInitialized) {
            // 展示当前皮肤名（含自定义标记）
            val skin = SkinManager.getCurrentSkin(this)
            val customized = com.ziyou.ime.skin.SkinCustomizer.hasOverride(this, skin.id)
            themeValueText.text = if (customized) "${skin.name}（已自定义）" else skin.name
        }

        if (::levelValueText.isInitialized) {
            val levelState = LevelRepository.load(this)
            levelValueText.text = "Lv.${levelState.level} ${LevelEngine.levelName(levelState.level)}"
        }

        if (::personaValueText.isInitialized) {
            personaValueText.text = PersonaRepository.getCurrentPersona(this).name
        }

        if (::knowledgeValueText.isInitialized) {
            val count = KnowledgeRepository.getItems(this).size
            val enabled = KnowledgeRepository.isEnabled(this)
            knowledgeValueText.text =
                "已导入 $count 条 · " + if (enabled) "已启用" else "未启用"
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
