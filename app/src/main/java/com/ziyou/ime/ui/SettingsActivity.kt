package com.ziyou.ime.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope
import com.ziyou.ime.R
import com.ziyou.ime.ai.AiConfig
import com.ziyou.ime.ai.AiPersona
import com.ziyou.ime.ai.prediction.LlmPredictionConfig
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
import com.ziyou.ime.update.AppUpdateManager
import com.ziyou.ime.voice.VoiceModelCatalog
import com.ziyou.ime.voice.VoiceModelManager
import com.ziyou.ime.voice.VoiceModelManager.VoiceCommitMode
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
 * 使用传统View + LinearLayout纯代码实现（遵循项目禁用 XML 布局约定）；
 * 视觉上按功能模块分组为圆角卡片（通用 / 外观与主题 / 输入行为 /
 * 符号键盘偏好 / 悬浮键盘 / AI 服务 / 成长与技能 / 数据管理 / 关于），
 * 设计令牌集中在 companion object，宽屏下内容列限宽居中（见 [MaxWidthColumn]）。
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"

        /** Intent 额外项：打开时直接弹出九宫格拼音侧栏符号管理（由输入法侧栏「＋」触发） */
        const val EXTRA_OPEN_SIDE_SYMBOLS = "open_side_symbols"

        /** Intent 额外项：打开时直接弹出语音模型管理（由语音面板授权/下载引导触发） */
        const val EXTRA_OPEN_VOICE = "open_voice_settings"

        /** Intent 额外项：语音入口来意为「请求录音权限」时置 true，直接弹系统授权 */
        const val EXTRA_VOICE_REQUEST_PERMISSION = "voice_request_permission"

        /** 录音权限请求码 */
        private const val REQUEST_CODE_RECORD_AUDIO = 1001

        // ===== 设计令牌：页面统一配色与尺寸（集中定义，避免各处魔法数漂移） =====
        /** 宽屏（平板/横屏）下内容列的最大宽度，超出后限宽居中 */
        private const val CONTENT_MAX_WIDTH_DP = 640
        private val COLOR_PAGE_BG = 0xFFF2F4F8.toInt()
        private val COLOR_CARD_BG = 0xFFFFFFFF.toInt()
        private val COLOR_TITLE = 0xFF1B1C1F.toInt()
        private val COLOR_SUMMARY = 0xFF6F757D.toInt()
        private val COLOR_DIVIDER = 0xFFEFF1F4.toInt()
        private val COLOR_CHEVRON = 0xFFB6BBC2.toInt()
        private val COLOR_BADGE_BG = 0xFFF0F4FA.toInt()
    }

    // UI组件引用
    private lateinit var schemaValueText: TextView
    private lateinit var themeValueText: TextView
    private lateinit var levelValueText: TextView
    private lateinit var personaValueText: TextView
    private lateinit var knowledgeValueText: TextView
    private lateinit var voiceModelValueText: TextView

    /** 正在下载中的模型 id（防重复点击并发写同一 .part 损坏文件） */
    private val downloadingModelIds = mutableSetOf<String>()

    /** Rime 引擎（经 DI 容器获取，依赖接口而非 RimeSession 单例） */
    private val rime get() = AppContainer.rimeEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 访问门禁：仅在输入法已启用并激活（ACTIVE）时才允许进入设置页；
        // 未就绪时自动跳回引导页（检测逻辑与 ImeSetupActivity 共用同一实现，
        // 保证两侧判定一致）
        if (!ImeSetupActivity.isImeReady(this)) {
            showToast("请先完成字由输入法的启用与切换，再进入设置")
            startActivity(Intent(this, ImeSetupActivity::class.java))
            finish()
            return
        }

        setContentViewWithTitleBar("字由输入法 设置", buildSettingsView())

        // 经 DI 容器统一初始化引擎（异步，避免主线程阻塞和双重初始化）
        ensureRimeStarted()

        // 由输入法侧栏「＋」拉起时，直接弹出侧栏符号管理
        if (intent?.getBooleanExtra(EXTRA_OPEN_SIDE_SYMBOLS, false) == true) {
            window.decorView.post { showSideSymbolManager() }
        }

        // 由语音面板拉起时：「去授权」直达系统权限弹窗，「下载模型」弹模型管理
        if (intent?.getBooleanExtra(EXTRA_OPEN_VOICE, false) == true) {
            val forPermission = intent?.getBooleanExtra(EXTRA_VOICE_REQUEST_PERMISSION, false) == true
            window.decorView.post {
                if (forPermission) requestAudioPermission() else showVoiceModelManager()
            }
        }

        // 应用启动时后台检测到的新版本在此弹窗（每进程最多一次，不重复打扰）
        window.decorView.post { AppUpdateManager.showPendingUpdateIfNeeded(this) }
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
     *
     * 视觉结构：浅灰页面背景 + 按功能模块分组的白色圆角卡片，
     * 组内条目以缩进分隔线相连；宽屏（平板/横屏）下内容列限宽居中，
     * 避免设置行被拉伸过宽、阅读动线过长。
     */
    private fun buildSettingsView(): View {
        val column = MaxWidthColumn(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // ===== 通用 =====
        column.addView(createSectionHeader("通用"))
        column.addView(createCard(
            createSettingItem("🚀", "启用与切换引导",
                "检查输入法启用状态，引导完成启用与切换") {
                // 携带强制展示标记：已就绪时引导页的启动路由会直达设置页，
                // 本入口是用户主动查看引导，需绕过该路由
                startActivity(Intent(this, ImeSetupActivity::class.java).apply {
                    putExtra(ImeSetupActivity.EXTRA_SHOW_GUIDE, true)
                })
            },
            createSettingItem("⚙️", "系统输入法设置",
                "打开 Android 系统的「语言和输入法」设置页") {
                openInputMethodSettings()
            }
        ))

        // ===== 外观与主题 =====
        column.addView(createSectionHeader("外观与主题"))
        val themeItem = createSettingItemWithValue("🎨", "键盘皮肤") { themeValueText = it }
        themeItem.setOnClickListener { openSkinManager() }
        column.addView(createCard(
            themeItem,
            createSettingItem("🧰", "自定义功能栏",
                "选择键盘上方功能栏显示的按钮与排列顺序，支持预设模板") {
                showToolbarCustomizer()
            }
        ))

        // ===== 输入行为 =====
        column.addView(createSectionHeader("输入行为"))
        val schemaItem = createSettingItemWithValue("⌨️", "全键盘方案") { schemaValueText = it }
        schemaItem.setOnClickListener { showSchemaSelector() }
        column.addView(createCard(
            schemaItem,
            createSwitchItem("💡", "中文联想",
                "上屏后展示引擎预测的联想词（需启用 librime-predict 模块）",
                checked = AssociationManager.isEnabled(this),
                onChange = { enabled -> AssociationManager.setEnabled(this, enabled) }),
            createSettingItem("📌", "拼音侧栏符号",
                "自定义九宫格左侧拼音栏无候选时的常用符号 / 短语") {
                showSideSymbolManager()
            }
        ))

        // ===== 符号键盘偏好 =====
        column.addView(createSectionHeader("符号键盘偏好"))
        column.addView(createCard(
            createSettingItem("⭐", "常用符号",
                "自定义符号键盘「常用」分类的符号（键盘内长按符号也可加入/移除）") {
                showFavoriteSymbolManager()
            }
        ))

        // ===== 悬浮键盘（游戏场景） =====
        column.addView(createSectionHeader("悬浮键盘"))
        column.addView(createCard(
            createSwitchItem("🎮", "悬浮键盘模式",
                "键盘缩小为可拖拽的悬浮面板，面板外触摸穿透给应用（也可经键盘上的「浮」键切换）",
                checked = DisplayModeManager.isFloatingEnabled(this),
                onChange = { enabled -> DisplayModeManager.setFloatingEnabled(this, enabled) }),
            createSwitchItem("🔄", "横屏自动悬浮",
                "横屏输入（如游戏内聊天）时自动切换为悬浮键盘",
                checked = DisplayModeManager.isAutoFloatInLandscape(this),
                onChange = { enabled -> DisplayModeManager.setAutoFloatInLandscape(this, enabled) })
        ))

        // ===== 语音输入 =====
        column.addView(createSectionHeader("语音输入"))
        val voiceModelItem = createSettingItemWithValue("🎤", "语音识别模型") { voiceModelValueText = it }
        voiceModelItem.setOnClickListener { showVoiceModelManager() }
        column.addView(createCard(
            voiceModelItem,
            createSwitchItem("⏱", "逐句自动上屏",
                "开启：每句说完自动写入输入框；关闭：面板内攒句后点「发送」一次上屏",
                checked = VoiceModelManager.getCommitMode(this) == VoiceCommitMode.AUTO_COMMIT,
                onChange = { enabled ->
                    VoiceModelManager.setCommitMode(
                        this,
                        if (enabled) VoiceCommitMode.AUTO_COMMIT else VoiceCommitMode.BUFFER_SEND
                    )
                }),
            createSettingItem("🔐", "录音权限", audioPermissionSummary()) {
                requestAudioPermission()
            }
        ))

        // ===== AI 服务 =====
        column.addView(createSectionHeader("AI 服务"))
        val personaItem = createSettingItemWithValue("🎭", "AI 人设") { personaValueText = it }
        personaItem.setOnClickListener { showPersonaManager() }
        val knowledgeItem = createSettingItemWithValue("📚", "AI 知识库") { knowledgeValueText = it }
        knowledgeItem.setOnClickListener {
            startActivity(Intent(this, KnowledgeActivity::class.java))
        }
        column.addView(createCard(
            createSettingItem("🔧", "AI 服务配置",
                "配置键盘「AI」键问答的服务地址与 API Key（OpenAI 兼容接口）") {
                showAiConfigDialog()
            },
            personaItem,
            knowledgeItem,
            createLlmPredictionItem()
        ))

        // ===== 成长与技能 =====
        column.addView(createSectionHeader("成长与技能"))
        val levelItem = createSettingItemWithValue("🏆", "我的等级") { levelValueText = it }
        levelItem.setOnClickListener {
            startActivity(Intent(this, LevelActivity::class.java))
        }
        column.addView(createCard(
            levelItem,
            createSettingItem("🧩", "技能插件",
                "管理键盘「技」键唤出的技能，导入 .skill 技能包") {
                startActivity(Intent(this, SkillManagerActivity::class.java))
            }
        ))

        // ===== 数据管理 =====
        column.addView(createSectionHeader("数据管理"))
        column.addView(createCard(
            createSettingItem("📖", "扩展词库", "下载并管理专业词库扩展包") {
                startActivity(Intent(this, DictManagerActivity::class.java))
            },
            createSettingItem("☁️", "同步用户词典", "同步用户自定义词组和输入历史") {
                syncUserData()
            },
            createSettingItem("🔁", "重新部署", "重新部署配置文件（解决配置异常）") {
                redeployRime()
            }
        ))

        // ===== 关于 =====
        column.addView(createSectionHeader("关于"))
        column.addView(createCard(
            createSettingItem("ℹ️", "版本", getVersionInfo()),
            createSettingItem("📥", "检查更新", "自动检测每日最多一次，也可点击立即检查新版本") {
                AppUpdateManager.checkUpdateManually(this)
            }
        ))

        // 宽屏下内容列被限宽后由 FrameLayout 水平居中
        val frame = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            ))
        }

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 内容不足一屏时页面背景也要铺满
            isFillViewport = true
            setBackgroundColor(COLOR_PAGE_BG)
            isVerticalScrollBarEnabled = false
            addView(frame)
        }
    }

    // ===== 功能方法 =====

    /**
     * LLM 智能续写开关项（实验性）：数据外发功能，每次从关到开都弹明示确认，
     * 确认才置位、取消回滚开关（与 [createSwitchItem] 同构，但需持有开关引用回滚，
     * 故独立构建）。端点/Key/模型复用 AI 服务配置，本项只管启用状态。
     */
    private fun createLlmPredictionItem(): LinearLayout {
        val switch = SwitchCompat(this).apply {
            isChecked = LlmPredictionConfig.isEnabled(this@SettingsActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            onLlmPredictionToggle(switch, isChecked)
        }
        return createItemRow(onClick = { switch.toggle() }).apply {
            addView(createIconBadge("✨"))
            addView(createTextColumn("LLM 智能续写（实验性）",
                "开启后，将把您最近上屏的若干词语发送至您配置的 AI 服务以生成续写建议" +
                    "（需先在 AI 设置中配置服务）"))
            addView(switch)
        }
    }

    /** LLM 智能续写开关切换：关即落盘；开须先经明示确认（词语内容会离开本设备）。
     *  弹窗展示期间禁用开关，防确认前重复点按造成视觉状态与落盘值不一致。 */
    private fun onLlmPredictionToggle(switch: SwitchCompat, isChecked: Boolean) {
        if (!isChecked) {
            LlmPredictionConfig.setEnabled(this, false)
            return
        }
        switch.isEnabled = false
        AlertDialog.Builder(this)
            .setTitle("开启 LLM 智能续写？")
            .setMessage("开启后，将把您最近上屏的若干词语发送至您配置的 AI 服务以生成续写建议。" +
                "词语内容会离开本设备发送至您在「AI 服务配置」中设置的服务，请确认已知悉并同意。")
            .setPositiveButton("确认开启") { _, _ ->
                LlmPredictionConfig.setEnabled(this@SettingsActivity, true)
                switch.isEnabled = true
            }
            .setNegativeButton("取消") { _, _ ->
                // 回滚开关（触发的 onCheckedChangeListener 为关闭分支，幂等无副作用）
                switch.isChecked = false
                switch.isEnabled = true
            }
            .setOnCancelListener {
                switch.isChecked = false
                switch.isEnabled = true
            }
            .show()
    }

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
     * AI 服务配置弹窗：平台下拉选择 / API Key / 模型名三项，
     * 预置国内主流 AI 平台（OpenAI 兼容端点），选“自定义”时可手动录入地址。
     * API Key 与模型名不做任何预填充，留空时由 AiConfig 回退到内置默认值。
     * 保存前校验地址必须为 HTTPS（与 AiChatClient 的安全基线一致）。
     */
    private fun showAiConfigDialog() {
        val providers = AiConfig.PRESET_PROVIDERS
        val currentUrl = AiConfig.getApiUrl(this)
        // 匹配当前已保存的 URL 对应的预置平台；无匹配则选中「自定义」
        val matchedIndex = AiConfig.matchProviderIndex(currentUrl)
        val customIndex = providers.size // 「自定义」位于列表末尾

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        // 平台选择标签 + 下拉框
        container.addView(TextView(this).apply {
            text = "AI 平台"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
        })
        val spinner = Spinner(this)
        val spinnerItems = providers.map { it.name } + "自定义…"
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, spinnerItems
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        container.addView(spinner)

        // 自定义地址输入框（仅选择「自定义」时可见）
        val urlInput = EditText(this).apply {
            hint = "API 地址（OpenAI 兼容 chat/completions）"
            setText(currentUrl)
            visibility = if (matchedIndex >= 0) View.GONE else View.VISIBLE
        }
        container.addView(urlInput)

        // API Key 与模型名不预填充，由用户自行填写；留空保存时 AiConfig 会回退内置默认值
        val keyInput = EditText(this).apply {
            hint = "API Key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelInput = EditText(this).apply {
            hint = "模型名（如 ${AiConfig.DEFAULT_MODEL}）"
        }
        container.addView(keyInput)
        container.addView(modelInput)

        // 下拉选择联动：预置平台隐藏地址输入框；自定义则显示手动输入框
        // （切换平台仅影响地址，API Key 与模型名始终保持用户输入状态）
        spinner.setSelection(if (matchedIndex >= 0) matchedIndex else customIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                urlInput.visibility = if (position < providers.size) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("AI 服务配置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val position = spinner.selectedItemPosition
                val url = if (position < providers.size) {
                    providers[position].apiUrl
                } else {
                    urlInput.text.toString().trim()
                }
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
            .setMessage("将重新部署所有配置文件并重启引擎，可能需要几秒钟。是否继续？")
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

        if (::voiceModelValueText.isInitialized) {
            val active = VoiceModelManager.getActiveSpec(this)
            voiceModelValueText.text = if (VoiceModelManager.isInstalled(this, active)) {
                active.name
            } else {
                "未下载"
            }
        }
    }

    // ===== 语音输入：模型管理与录音权限 =====

    /** 录音权限状态文案（设置项说明行）。 */
    private fun audioPermissionSummary(): String {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) "已授权（仅本地识别，音频不上传）" else "未授权，点击授予（仅本地识别，音频不上传）"
    }

    /** 请求录音权限；已授权时仅提示。 */
    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showToast("录音权限已授予")
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_RECORD_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        showToast(if (granted) "录音权限已授予" else "未授予录音权限，语音输入不可用")
        // 重建页面刷新权限条目文案（纯代码布局下最简的局部刷新方式）
        setContentViewWithTitleBar("字由输入法 设置", buildSettingsView())
    }

    /**
     * 语音模型管理弹窗：列出全部可选模型（状态/下载/删除/设为激活）。
     * 模型清单硬编码于 [VoiceModelCatalog]，下载走白名单安全链路
     *（[com.ziyou.ime.voice.VoiceModelDownloader]）；文件不入 APK，按需下载。
     */
    private fun showVoiceModelManager() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(container, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        renderVoiceModels(container)
        AlertDialog.Builder(this)
            .setTitle("语音识别模型")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    /** 重建模型列表行（下载/删除/激活变更后整体刷新）。 */
    private fun renderVoiceModels(container: LinearLayout) {
        container.removeAllViews()
        val activeId = VoiceModelManager.getActiveSpec(this).id
        for (spec in VoiceModelCatalog.ALL) {
            val installed = VoiceModelManager.isInstalled(this, spec)
            val statusText = TextView(this).apply {
                textSize = 12f
                setTextColor(COLOR_SUMMARY)
                text = when {
                    installed && spec.id == activeId -> "已下载 · 使用中"
                    installed -> "已下载"
                    else -> "未下载"
                }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (!installed) {
                actions.addView(createVoiceActionButton("下载") {
                    startModelDownload(spec, container, statusText)
                })
            } else {
                if (spec.id != activeId) {
                    actions.addView(createVoiceActionButton("设为使用中") {
                        VoiceModelManager.setActiveSpec(this@SettingsActivity, spec)
                        renderVoiceModels(container)
                        refreshDisplay()
                    })
                }
                actions.addView(createVoiceActionButton("删除") {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("删除模型")
                        .setMessage("确定删除「${spec.name}」？删除后可重新下载。")
                        .setPositiveButton("删除") { _, _ ->
                            VoiceModelManager.deleteModel(this@SettingsActivity, spec)
                            renderVoiceModels(container)
                            refreshDisplay()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                })
            }
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(TextView(this@SettingsActivity).apply {
                    text = spec.name
                    textSize = 15f
                    setTextColor(COLOR_TITLE)
                    setTypeface(null, Typeface.BOLD)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = spec.summary
                    textSize = 13f
                    setTextColor(COLOR_SUMMARY)
                    setPadding(0, dp(2), 0, dp(4))
                })
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(statusText, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(actions)
                })
                addView(createInsetDivider())
            })
        }
    }

    /** 模型行操作小按钮（下载/删除/设为使用中）。 */
    private fun createVoiceActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.primary))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(COLOR_BADGE_BG)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    /** 启动模型下载：状态行实时展示进度，完成/失败后刷新列表。 */
    private fun startModelDownload(
        spec: VoiceModelCatalog.VoiceModelSpec,
        container: LinearLayout,
        statusText: TextView,
    ) {
        // 并发防护：同一模型重复点击直接忽略（两个协程并发写同一 .part 会损坏文件）
        if (!downloadingModelIds.add(spec.id)) {
            showToast("正在下载中，请稍候")
            return
        }
        statusText.text = "准备下载…"
        lifecycleScope.launch {
            try {
                val error = VoiceModelManager.downloadModel(
                    applicationContext, spec
                ) { done, total, curBytes, curTotal ->
                    runOnUiThread {
                        val percent = if (curTotal > 0) "${curBytes * 100 / curTotal}%" else "…"
                        statusText.text = "下载中 文件 ${done + 1}/$total · $percent"
                    }
                }
                if (error == null) {
                    // 仅当用户从未显式选过激活模型时才自动激活，不覆盖既有选择
                    if (!VoiceModelManager.hasExplicitActiveChoice(this@SettingsActivity)) {
                        VoiceModelManager.setActiveSpec(this@SettingsActivity, spec)
                    }
                    showToast("「${spec.name}」下载完成")
                } else {
                    showToast("下载失败：$error")
                }
                renderVoiceModels(container)
                refreshDisplay()
            } finally {
                downloadingModelIds.remove(spec.id)
            }
        }
    }

    // ===== UI辅助方法 =====

    /**
     * 宽屏自适应容器：竖向列表列，测量宽度超过 [CONTENT_MAX_WIDTH_DP] 时限宽，
     * 配合外层 FrameLayout 的居中 gravity 实现平板/横屏下的限宽居中布局。
     */
    private class MaxWidthColumn(context: android.content.Context) : LinearLayout(context) {
        init {
            orientation = VERTICAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = (CONTENT_MAX_WIDTH_DP * resources.displayMetrics.density).toInt()
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val spec = if (width > maxWidth) {
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY)
            } else {
                widthMeasureSpec
            }
            super.onMeasure(spec, heightMeasureSpec)
        }
    }

    /** 分区标题：主色小字加粗，与卡片左缘对齐，强化分组层次 */
    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.primary))
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.05f
            setPadding(dp(16), dp(20), dp(16), dp(8))
        }
    }

    /**
     * 分组卡片：白色圆角容器包裹同组设置项，项间自动插入缩进分隔线
     *（缩进量对齐文字区，避免分隔线穿过图标形成视觉噴声）。
     */
    private fun createCard(vararg items: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(COLOR_CARD_BG)
                cornerRadius = dp(16).toFloat()
            }
            // 裁切到圆角轮廓，保证首尾设置项的 ripple 不溢出卡片圆角
            clipToOutline = true
            elevation = dp(1).toFloat()
            items.forEachIndexed { index, item ->
                if (index > 0) addView(createInsetDivider())
                addView(item)
            }
        }
    }

    /** 图标徽章：圆角浅色底 + emoji，统一各设置项的视觉锚点（遵循项目入口徽章风格） */
    private fun createIconBadge(icon: String): TextView {
        return TextView(this).apply {
            text = icon
            textSize = 17f
            gravity = Gravity.CENTER
            // 装饰性图标，不参与无障碍朗读（行标题已提供语义）
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                setColor(COLOR_BADGE_BG)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(12)
            }
        }
    }

    /** 解析主题的 selectableItemBackground，为可点击行提供统一的 ripple 反馈 */
    private fun rippleBackground(): Drawable? {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return ContextCompat.getDrawable(this, outValue.resourceId)
    }

    /** 行内标题 + 可选说明的文字列（占满剩余宽度，供各类设置行复用） */
    private fun createTextColumn(title: String, summary: String?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(COLOR_TITLE)
            })
            if (!summary.isNullOrEmpty()) {
                addView(TextView(context).apply {
                    text = summary
                    textSize = 13f
                    setTextColor(COLOR_SUMMARY)
                    // 行间距略放宽，长说明文本换行后不拥挤
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(0, dp(3), 0, 0)
                })
            }
        }
    }

    /** 基础设置行容器：统一内边距、最小触控高度与点击反馈 */
    private fun createItemRow(onClick: (() -> Unit)?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = rippleBackground()
                setOnClickListener { onClick() }
            }
        }
    }

    /** 普通设置项：徽章 + 标题/说明，可点击时附右侧箭头与 ripple */
    private fun createSettingItem(
        icon: String,
        title: String,
        summary: String,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        return createItemRow(onClick).apply {
            addView(createIconBadge(icon))
            addView(createTextColumn(title, summary))
            if (onClick != null) addView(createChevron())
        }
    }

    /** 带当前值的设置项：徽章 + 标题，右侧展示当前值（超长省略）与箭头 */
    private fun createSettingItemWithValue(
        icon: String,
        title: String,
        valueHolder: (TextView) -> Unit
    ): LinearLayout {
        return createItemRow(onClick = {}).apply {
            // 点击监听由调用方通过 setOnClickListener 覆盖
            addView(createIconBadge(icon))
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(COLOR_TITLE)
            })
            val valueText = TextView(context).apply {
                textSize = 14f
                setTextColor(COLOR_SUMMARY)
                text = "..."
                gravity = Gravity.END
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }
            }
            valueHolder(valueText)
            addView(valueText)
            addView(createChevron())
        }
    }

    /** 带开关的设置项：整行可点击切换（扩大触控面积），右侧 SwitchCompat */
    private fun createSwitchItem(
        icon: String,
        title: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val switch = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        return createItemRow(onClick = { switch.toggle() }).apply {
            addView(createIconBadge(icon))
            addView(createTextColumn(title, summary))
            addView(switch)
        }
    }

    /** 右侧导航箭头（仅装饰，不参与无障碍朗读） */
    private fun createChevron(): TextView {
        return TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(COLOR_CHEVRON)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(8), 0, 0, 0)
        }
    }

    /** 卡片内分隔线：缩进对齐文字区（起点 = 左边距 16 + 徽章 40 + 间距 12） */
    private fun createInsetDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                marginStart = dp(68)
            }
            setBackgroundColor(COLOR_DIVIDER)
        }
    }

    private fun dp(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getVersionInfo(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "v${packageInfo.versionName}(${PackageInfoCompat.getLongVersionCode(packageInfo)})"
        } catch (e: Exception) {
            "未知版本"
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
