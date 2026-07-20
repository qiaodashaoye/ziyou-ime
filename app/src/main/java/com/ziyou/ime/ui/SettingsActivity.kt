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
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.ThemeManager
import com.ziyou.ime.core.RimeNative

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
    }

    // UI组件引用
    private lateinit var schemaValueText: TextView
    private lateinit var themeValueText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildSettingsView())
        title = "字由输入法 设置"

        // 更新显示状态
        refreshDisplay()
    }

    override fun onResume() {
        super.onResume()
        refreshDisplay()
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

        // ===== 数据同步 =====
        rootLayout.addView(createSectionHeader("数据"))
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
        val schemas = RimeNative.getRimeSchemaList()
        if (schemas.isNullOrEmpty()) {
            showToast("无法获取方案列表，请确保Rime引擎已启动")
            return
        }

        val schemaNames = schemas.map { it.name }.toTypedArray()
        val currentSchema = RimeNative.getCurrentRimeSchema()
        val currentIndex = schemas.indexOfFirst { it.schemaId == currentSchema }

        AlertDialog.Builder(this)
            .setTitle("选择输入方案")
            .setSingleChoiceItems(schemaNames, currentIndex) { dialog, which ->
                val selectedSchema = schemas[which]
                RimeNative.selectRimeSchema(selectedSchema.schemaId)
                schemaValueText.text = selectedSchema.name
                Log.i(TAG, "切换方案: ${selectedSchema.schemaId}")
                showToast("已切换到: ${selectedSchema.name}")
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun syncUserData() {
        showToast("开始同步用户词典...")
        val success = RimeNative.syncRimeUserData()
        if (success) {
            showToast("用户词典同步完成")
            Log.i(TAG, "用户词典同步成功")
        } else {
            showToast("同步失败，请确保Rime引擎已启动")
            Log.w(TAG, "用户词典同步失败")
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
        if (::schemaValueText.isInitialized) {
            val currentSchema = RimeNative.getCurrentRimeSchema()
            val schemas = RimeNative.getRimeSchemaList()
            val schemaName = schemas?.firstOrNull { it.schemaId == currentSchema }?.name
            schemaValueText.text = schemaName ?: "未知方案"
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
