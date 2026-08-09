package com.ziyou.ime.update

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * 应用更新弹窗集合（纯代码构建视图，遵循项目无 XML 布局约定）
 *
 * 三个弹窗：
 * - [showUpdateDialog]：发现新版本提示（更新说明 + 立即更新 / 稍后提醒 / 忽略该版本）；
 * - [showDownloadProgressDialog]：下载进度（水平进度条 + 百分比，可取消）；
 * - [showInstallPermissionDialog]：缺「安装未知应用」权限时的授权引导与重试。
 */
object UpdateDialogHelper {

    /** 下载进度句柄：供下载任务在主线程刷新与收尾 */
    interface DownloadProgressHandle {
        /** 刷新进度（主线程调用）；total 未知（-1）时展示不确定态 */
        fun update(downloaded: Long, total: Long)

        fun dismiss()
    }

    /**
     * 发现新版本弹窗。
     * 「稍后提醒」保留待更新快照，下次进程启动再提示；
     * 「忽略该版本」后自动检测不再提示（手动检查仍可见）。
     */
    fun showUpdateDialog(activity: Activity, info: AppUpdateInfo) {
        if (!isShowable(activity)) return

        val description = TextView(activity).apply {
            text = info.updateDescription.ifBlank { "有新版本可用，建议更新体验最新功能。" }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF555555.toInt())
            setLineSpacing(dp(activity, 4f).toFloat(), 1f)
        }
        val content = ScrollView(activity).apply {
            addView(description, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val container = FrameLayout(activity).apply {
            val pad = dp(activity, 20f)
            setPadding(pad, dp(activity, 8f), pad, 0)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.versionName}")
            .setView(container)
            .setPositiveButton("立即更新") { _, _ ->
                AppUpdateManager.startDownloadAndInstall(activity, info)
            }
            .setNegativeButton("稍后提醒") { dialog, _ ->
                // 保留快照：下次启动应用时再次提示
                dialog.dismiss()
            }
            .setNeutralButton("忽略该版本") { _, _ ->
                AppUpdateManager.ignoreVersion(activity, info.versionName)
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 下载进度弹窗：不可返回键取消，仅经「取消」按钮中断下载。
     */
    fun showDownloadProgressDialog(
        activity: Activity,
        onCancel: () -> Unit
    ): DownloadProgressHandle {
        if (!isShowable(activity)) return NoopHandle

        val percentText = TextView(activity).apply {
            text = "0%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF666666.toInt())
            gravity = Gravity.END
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(activity, 20f)
            setPadding(pad, dp(activity, 16f), pad, 0)
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(percentText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 8f) })
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载新版本")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("取消") { d, _ ->
                onCancel()
                d.dismiss()
            }
            .show()

        return object : DownloadProgressHandle {
            override fun update(downloaded: Long, total: Long) {
                if (!isShowable(activity)) return
                if (total > 0) {
                    val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                    bar.isIndeterminate = false
                    bar.progress = percent
                    percentText.text = "$percent%"
                } else {
                    // 服务端未给 Content-Length：不确定态 + 已下载体积
                    bar.isIndeterminate = true
                    percentText.text = formatBytes(downloaded)
                }
            }

            override fun dismiss() {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    /**
     * 「安装未知应用」权限引导：用户从系统授权页返回后经此弹窗一键重试安装。
     */
    fun showInstallPermissionDialog(activity: Activity, apk: File) {
        if (!isShowable(activity)) return
        AlertDialog.Builder(activity)
            .setTitle("需要安装权限")
            .setMessage("安装新版本需要允许「安装未知应用」权限，请在系统设置中允许后继续。")
            .setPositiveButton("已允许，继续安装") { _, _ ->
                if (!ApkInstaller.install(activity, apk)) {
                    // 仍未授权：系统会再次打开授权页，用户可往返直到允许
                    showInstallPermissionDialog(activity, apk)
                }
            }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }

    // ===== 工具 =====

    private fun isShowable(activity: Activity): Boolean =
        !activity.isFinishing && !activity.isDestroyed

    private fun dp(activity: Activity, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
        ).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Activity 不可用时返回的空句柄 */
    private object NoopHandle : DownloadProgressHandle {
        override fun update(downloaded: Long, total: Long) = Unit
        override fun dismiss() = Unit
    }
}
