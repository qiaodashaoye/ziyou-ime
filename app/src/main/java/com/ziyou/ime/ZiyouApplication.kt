package com.ziyou.ime

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.di.AppContainer
import com.ziyou.ime.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 字由输入法 应用入口
 * 负责全局初始化；进程启动时按需后台预热 Rime 引擎（资源部署 + 词库编译），
 * 使首次选择输入法或版本升级后键盘拉起时引擎已就绪，其余初始化延迟到 RimeSession 中异步执行
 */
class ZiyouApplication : Application() {

    companion object {
        private const val TAG = "Ziyou"

        lateinit var instance: ZiyouApplication
            private set
    }

    /** 进程级后台作用域：预热等一次性任务；SupervisorJob 隔离失败，不波及其他任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "字由输入法 Application 初始化")

        // 后台预热 Rime 引擎：把耗时的资源部署与词库编译从「键盘首次弹出」提前到「进程启动」
        prewarmRimeEngineIfNeeded()

        // 应用内更新自动检测：仅主进程触发（频控 24h/次，后台静默检测只暂存结果），
        // 键盘输入法服务不参与任何更新逻辑，弹窗等有 Activity 前台时才展示，
        // 不打扰输入（见 update/AppUpdateManager）
        if (AppUpdateManager.isMainProcess(this)) {
            AppUpdateManager.scheduleAutoCheckIfNeeded(this)
        }
    }

    /**
     * 按需后台预热 Rime 引擎（fire-and-forget）。
     *
     * 触发条件（二者满足其一）：
     * - [AssetDeployer.needsDeploy]：首次安装/版本升级，存在资源复制 + 词库编译的重活，
     *   必须提前到进程启动时做掉，否则全部落在键盘首次弹出的时刻；
     * - 本输入法已被用户启用：键盘随时可能拉起，引擎保持热态。
     * 其余进程启动（如仅跑更新检测）不加载引擎，避免无谓的内存与电量开销。
     *
     * 与 IME 服务/设置页的 initialize 幂等兼容：[com.ziyou.ime.daemon.RimeSession]
     * 内部 lifecycleMutex + isInitialized 守卫保证不会双重初始化。
     */
    private fun prewarmRimeEngineIfNeeded() {
        val context = applicationContext
        val needsDeploy = AssetDeployer.needsDeploy(context)
        if (!needsDeploy && !isImeEnabledByUser(context)) {
            return
        }
        appScope.launch {
            try {
                if (!AppContainer.rimeEngine.initialized) {
                    Log.i(TAG, "后台预热 Rime 引擎（fullCheck=$needsDeploy）")
                    AppContainer.rimeEngine.initialize(context, fullCheck = needsDeploy)
                    Log.i(TAG, "后台预热 Rime 引擎完成")
                }
            } catch (e: Exception) {
                // 预热失败不影响正确性：IME 服务 onCreate 会再次触发初始化兜底
                Log.e(TAG, "后台预热 Rime 引擎失败: ${e.message}", e)
            }
        }
    }

    /** 本输入法是否已被用户在系统设置中启用。 */
    private fun isImeEnabledByUser(context: Context): Boolean {
        return try {
            val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
            imm.enabledInputMethodList.any { it.packageName == context.packageName }
        } catch (e: Exception) {
            false
        }
    }
}
