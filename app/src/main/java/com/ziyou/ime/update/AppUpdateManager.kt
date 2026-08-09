package com.ziyou.ime.update

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import com.ziyou.ime.util.AppVersionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新总控（蒲公英分发）
 *
 * 职责：更新检测时机与频控、版本对比、下载与安装编排。UI 弹窗委托 [UpdateDialogHelper]。
 *
 * 检测时机（参考成熟输入法 / 工具类应用的做法）：
 * - **自动检测**：由 [com.ziyou.ime.ZiyouApplication.onCreate] 在**主进程**触发，
 *   24 小时最多一次；键盘输入服务（ZiYouInputMethodService）不参与任何更新逻辑，
 *   进程若仅由键盘拉起，检测结果只暂存为待更新快照，不弹窗、不打扰输入；
 * - **待更新展示**：设置页等 Activity 前台时经 [showPendingUpdateIfNeeded] 弹窗，
 *   每个进程生命周期内最多弹一次；
 * - **手动检测**：设置页「检查更新」入口，不受频控限制；「忽略版本」仅屏蔽自动提示。
 *
 * 版本对比：优先数字版本号（远端 buildVersionNo vs 本地 versionCode）；
 * 远端未提供时回退 versionName 逐段比较（:core-logic 的 [AppVersionUtils]，含单测）。
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    /** 更新流程协程作用域（检测/下载均在 IO，UI 回调切主线程） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 本进程是否已展示过待更新弹窗（同一进程多 Activity 只弹一次） */
    @Volatile
    private var pendingDialogShownThisProcess = false

    /** 当前下载任务（防重复点击并发下载；取消时 cancel） */
    @Volatile
    private var downloadJob: Job? = null

    // ===== 主进程判定 =====

    /**
     * 是否为主进程。输入法进程可能仅由键盘服务拉起，更新检测只允许在主进程发起，
     * 确保不在 ZiYouInputMethodService 的运行路径上执行更新逻辑。
     */
    fun isMainProcess(context: Context): Boolean {
        return try {
            val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
            processName == context.packageName
        } catch (e: Exception) {
            Log.w(TAG, "主进程判定失败，按非主进程处理: ${e.message}")
            false
        }
    }

    // ===== 自动检测（Application.onCreate 调用，仅主进程）=====

    /**
     * 启动时自动检测更新：未配置 / 频控未到期直接跳过；
     * 发现新版本且未被忽略时暂存待更新快照，等 Activity 前台时弹窗。
     */
    fun scheduleAutoCheckIfNeeded(context: Context) {
        if (!UpdateConfig.isConfigured) {
            Log.i(TAG, "更新服务未配置，跳过自动检测")
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            if (!UpdateSettings.shouldAutoCheck(appContext)) return@launch
            // 发起即记录：网络失败也不在间隔内反复重试
            UpdateSettings.markAutoChecked(appContext)
            delayForStartup()
            val result = checkUpdateInternal(appContext)
            if (result is UpdateCheckResult.UpdateAvailable) {
                val info = result.info
                if (UpdateSettings.getIgnoredVersion(appContext) == info.versionName) {
                    Log.i(TAG, "新版本 ${info.versionName} 已被用户忽略，自动检测不提示")
                    return@launch
                }
                UpdateSettings.savePendingUpdate(appContext, info)
                Log.i(TAG, "自动检测发现新版本 ${info.versionName}，已暂存待更新提示")
            }
        }
    }

    /** 启动后稍延再发网络请求，避免与引擎初始化/资源部署抢占启动期资源 */
    private suspend fun delayForStartup() = withContext(Dispatchers.IO) {
        Thread.sleep(3_000L)
    }

    // ===== 待更新弹窗（Activity 前台时调用）=====

    /**
     * 展示后台检测到的待更新（每进程最多一次）。
     * 版本已失效（本地已升级）或已被忽略时静默清理快照。
     */
    fun showPendingUpdateIfNeeded(activity: Activity) {
        if (pendingDialogShownThisProcess) return
        val pending = UpdateSettings.getPendingUpdate(activity) ?: return
        if (!isNewerThanLocal(activity, pending) ||
            UpdateSettings.getIgnoredVersion(activity) == pending.versionName
        ) {
            UpdateSettings.clearPendingUpdate(activity)
            return
        }
        pendingDialogShownThisProcess = true
        UpdateDialogHelper.showUpdateDialog(activity, pending)
    }

    // ===== 手动检测（设置页「检查更新」入口）=====

    /**
     * 手动检查更新：不受 24h 频控限制；「忽略版本」不屏蔽手动结果
     * （用户主动查询视为明确意图）。
     */
    fun checkUpdateManually(activity: Activity) {
        if (!UpdateConfig.isConfigured) {
            toast(activity, "更新服务未配置，请先在代码中填入蒲公英 API Key / App Key")
            return
        }
        toast(activity, "正在检查更新…")
        scope.launch {
            when (val result = checkUpdateInternal(activity)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    pendingDialogShownThisProcess = true
                    UpdateDialogHelper.showUpdateDialog(activity, result.info)
                }
                is UpdateCheckResult.UpToDate -> {
                    UpdateSettings.clearPendingUpdate(activity)
                    toast(activity, "已是最新版本")
                }
                is UpdateCheckResult.Failed -> toast(activity, "检查更新失败：${result.message}")
                is UpdateCheckResult.NotConfigured -> Unit // 入口已拦截，理论不可达
            }
        }
    }

    // ===== 检测与版本对比 =====

    private suspend fun checkUpdateInternal(context: Context): UpdateCheckResult {
        if (!UpdateConfig.isConfigured) return UpdateCheckResult.NotConfigured
        val localCode = localVersionCode(context)
        val info = PgyerUpdateChecker.checkUpdate(localCode.toString())
            ?: return UpdateCheckResult.Failed("网络异常或服务暂不可用")
        return if (isNewerThanLocal(context, info)) {
            UpdateCheckResult.UpdateAvailable(info)
        } else {
            UpdateCheckResult.UpToDate
        }
    }

    /**
     * 版本对比：远端 buildVersionNo 有效时按数字版本号（versionCode）比较，
     * 否则回退 versionName 逐段比较。
     */
    private fun isNewerThanLocal(context: Context, info: AppUpdateInfo): Boolean {
        return if (info.versionNo > 0) {
            info.versionNo > localVersionCode(context)
        } else {
            AppVersionUtils.compareVersionNames(info.versionName, localVersionName(context)) > 0
        }
    }

    private fun localVersionCode(context: Context): Long = try {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    } catch (e: Exception) {
        0L
    }

    private fun localVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    // ===== 下载与安装 =====

    /**
     * 下载新版本并引导安装。下载中重复调用直接忽略；
     * 下载完成自动唤起系统安装器，缺「安装未知应用」权限时引导授权后重试。
     */
    fun startDownloadAndInstall(activity: Activity, info: AppUpdateInfo) {
        if (downloadJob?.isActive == true) {
            toast(activity, "正在下载中，请稍候")
            return
        }

        // 已下载包与新版本不一致时清理，避免装到旧包
        val apk = UpdateApkDownloader.downloadedApk(activity)
        if (apk.exists() && UpdateSettings.getDownloadedVersion(activity) != info.versionName) {
            apk.delete()
            UpdateSettings.clearDownloadedVersion(activity)
        }

        val progress = UpdateDialogHelper.showDownloadProgressDialog(activity) {
            downloadJob?.cancel()
        }

        downloadJob = scope.launch {
            val file = UpdateApkDownloader.downloadApk(
                activity.applicationContext, info.downloadUrl
            ) { downloaded, total ->
                // IO 线程回调，切主线程刷新进度条
                scope.launch { progress.update(downloaded, total) }
            }
            progress.dismiss()

            if (file == null) {
                toast(activity, "下载失败，请检查网络后重试")
                return@launch
            }
            UpdateSettings.setDownloadedVersion(activity, info.versionName)
            requestInstall(activity, file)
        }
    }

    /** 唤起安装；缺权限时引导用户授权后重试 */
    private fun requestInstall(activity: Activity, apk: File) {
        if (ApkInstaller.install(activity, apk)) return
        UpdateDialogHelper.showInstallPermissionDialog(activity, apk)
    }

    /** 忽略某版本：自动检测不再提示，手动检查仍可见 */
    fun ignoreVersion(context: Context, versionName: String) {
        UpdateSettings.setIgnoredVersion(context, versionName)
        UpdateSettings.clearPendingUpdate(context)
    }

    private fun toast(context: Context, message: String) {
        scope.launch {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
