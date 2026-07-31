package com.ziyou.ime.ui

import android.net.Uri
import android.os.Bundle
import android.content.Intent
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
import com.ziyou.ime.core.skill.SkillManifest
import com.ziyou.ime.skill.SkillInfo
import com.ziyou.ime.skill.SkillManager
import com.ziyou.ime.skill.SkillPackageInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 技能插件管理页面（Phase 2）。
 *
 * - 列出内置与已安装技能（名称/版本/权限摘要），点击查看详情、卸载
 * - 本地导入：SAF 选择 `.skill` 包
 * - URL 导入：输入 https 直链下载
 * 两种导入共用 [SkillPackageInstaller] 两段式流水线：校验 → 权限确认弹窗 → 落盘。
 *
 * 与 SettingsActivity 一致采用纯代码 View 布局，保持简单轻量。
 */
class SkillManagerActivity : AppCompatActivity() {

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 15_000
    }

    private lateinit var listContainer: LinearLayout

    /** SAF 选包（.skill 实为 zip，SAF 无法按后缀过滤，接受任意文件由校验兜底） */
    private val pickSkillFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithTitleBar("技能插件", buildView())
        refreshList()
    }

    // ===== 布局 =====

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 顶部操作行：本地导入 / URL 导入 / 开发文档
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "导入 .skill 文件"
            setOnClickListener { pickSkillFile.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "从 URL 导入"
            setOnClickListener { showUrlImportDialog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "开发文档"
            setOnClickListener {
                startActivity(Intent(this@SkillManagerActivity, SkillDevGuideActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        root.addView(TextView(this).apply {
            text = "第三方技能由作者负责，导入前请确认来源可信；技能运行在受限沙箱中，" +
                "权限与网络域名以安装时确认的清单为准。想开发自己的技能？点「开发文档」查看完整指南。"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val skills = SkillManager.listSkills(this)
        if (skills.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "暂无技能"
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
                setTextColor(0xFF757575.toInt())
            })
            return
        }
        skills.forEach { skill -> listContainer.addView(createSkillItem(skill)) }
    }

    private fun createSkillItem(skill: SkillInfo): View {
        val manifest = skill.manifest
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showSkillDetail(skill) }

            addView(TextView(context).apply {
                text = "${manifest.iconText ?: "⚙"} ${manifest.name}  v${manifest.version}" +
                    if (skill.builtin) "（内置）" else ""
                textSize = 16f
                setTextColor(0xFF212121.toInt())
            })
            addView(TextView(context).apply {
                text = buildString {
                    append(manifest.id)
                    if (manifest.permissions.isNotEmpty()) {
                        append("  ·  权限: ${manifest.permissions.joinToString(", ") { it.id }}")
                    }
                }
                textSize = 12f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    // ===== 详情 / 卸载 =====

    private fun showSkillDetail(skill: SkillInfo) {
        val manifest = skill.manifest
        val builder = AlertDialog.Builder(this)
            .setTitle("${manifest.iconText ?: ""} ${manifest.name}")
            .setMessage(describeManifest(manifest, skill.builtin))
            .setNegativeButton("关闭", null)
        if (!skill.builtin) {
            builder.setPositiveButton("卸载") { _, _ -> confirmUninstall(skill) }
        }
        builder.show()
    }

    private fun confirmUninstall(skill: SkillInfo) {
        AlertDialog.Builder(this)
            .setTitle("卸载技能")
            .setMessage("确定卸载「${skill.manifest.name}」？其存储数据将一并清除。")
            .setPositiveButton("卸载") { _, _ ->
                if (SkillPackageInstaller.uninstall(this, skill)) {
                    showToast("已卸载")
                    refreshList()
                } else {
                    showToast("卸载失败")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 导入 =====

    private fun importFromUri(uri: Uri) {
        launchImport {
            contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法读取所选文件")
        }
    }

    private fun showUrlImportDialog() {
        val input = EditText(this).apply { hint = "https://…/xxx.skill" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("从 URL 导入技能")
            .setView(container)
            .setPositiveButton("下载") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://")) {
                    showToast("仅支持 HTTPS 链接")
                    return@setPositiveButton
                }
                launchImport { openDownloadStream(url) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 导入流水线：IO 线程取流并校验 → 主线程权限确认 → IO 线程落盘。 */
    private fun launchImport(openStream: () -> java.io.InputStream) {
        showToast("正在校验技能包…")
        lifecycleScope.launch {
            val pending = try {
                withContext(Dispatchers.IO) {
                    SkillPackageInstaller.inspect(this@SkillManagerActivity, openStream())
                }
            } catch (e: Exception) {
                showToast(e.message ?: "技能包校验失败")
                return@launch
            }
            showInstallConfirm(pending)
        }
    }

    /** 权限确认弹窗（落盘前的最后一道确认，方案 §5.2 流水线第 5 步）。 */
    private fun showInstallConfirm(pending: SkillPackageInstaller.PendingInstall) {
        val manifest = pending.manifest
        val action = pending.upgradeFromVersion
            ?.let { "升级（$it → ${manifest.version}）" } ?: "安装"
        AlertDialog.Builder(this)
            .setTitle("$action「${manifest.name}」？")
            .setMessage(describeManifest(manifest, builtin = false) +
                "\n\n⚠ 第三方技能由作者负责，请确认来源可信。")
            .setPositiveButton(action) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            SkillPackageInstaller.commit(this@SkillManagerActivity, pending)
                        }
                        showToast("${manifest.name} $action 完成")
                        refreshList()
                    } catch (e: Exception) {
                        showToast(e.message ?: "安装失败")
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> SkillPackageInstaller.abort(pending) }
            .setOnCancelListener { SkillPackageInstaller.abort(pending) }
            .show()
    }

    /** HTTPS 直链下载流（大小上限由安装器 inspect 边读边卡）。 */
    private fun openDownloadStream(url: String): java.io.InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_TIMEOUT_MS
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IllegalArgumentException("下载失败: HTTP ${connection.responseCode}")
        }
        return connection.inputStream
    }

    // ===== 辅助 =====

    private fun describeManifest(manifest: SkillManifest, builtin: Boolean): String = buildString {
        append("id: ${manifest.id}\n")
        append("版本: ${manifest.version}")
        manifest.author?.let { append("\n作者: $it") }
        manifest.description?.let { append("\n简介: $it") }
        append("\n来源: ${if (builtin) "内置" else "用户安装"}")
        append("\n权限: ")
        append(if (manifest.permissions.isEmpty()) "无"
            else manifest.permissions.joinToString(", ") { it.id })
        if (manifest.networkDomains.isNotEmpty()) {
            append("\n可访问域名: ${manifest.networkDomains.joinToString(", ")}")
        }
        if (manifest.needsInput) {
            append("\n需要面板输入（键盘文本将注入技能页面）")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
