package com.ziyou.ime.daemon

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Rime会话管理器（单例）
 *
 * 管理Rime引擎的完整生命周期：
 * 1. 初始化（资源部署、引擎启动）
 * 2. 运行（处理输入、获取结果）
 * 3. 销毁（释放资源、关闭引擎）
 *
 * 使用方式：
 * ```
 * // 在Application或Service中初始化
 * RimeSession.initialize(context)
 *
 * // 获取API进行操作
 * val api = RimeSession.api
 * val result = api.processKey(keycode, mask)
 *
 * // 应用退出时销毁
 * RimeSession.destroy()
 * ```
 */
object RimeSession : RimeEngine {

    private const val TAG = "RimeSession"

    /** Rime共享数据目录名 */
    private const val SHARED_DATA_DIR = "rime"
    /** Rime用户数据目录名 */
    private const val USER_DATA_DIR = "rime_user"

    private var dispatcher: RimeDispatcher? = null
    private var rimeApi: SimpleRimeImpl? = null
    private var sessionScope: CoroutineScope? = null

    /**
     * 引擎启动前的部署步骤（资源部署、词库注入等），由组合根
     * [com.ziyou.ime.di.AppContainer.install] 装配；daemon 层不直接依赖业务模块。
     */
    @Volatile
    var deploySteps: List<RimeDeployStep> = emptyList()

    @Volatile
    private var isInitialized = false

    /**
     * 生命周期互斥锁：串行化 initialize/destroy/redeploy。
     * librime 非线程安全，且上述操作会重建 dispatcher/scope；若并发调用（如 IME 服务
     * onCreate 与设置页同时触发），isInitialized 守卫会被双双穿过导致双重初始化与线程泄漏。
     */
    private val lifecycleMutex = Mutex()

    /** Rime引擎启动超时时间（毫秒） */
    private const val STARTUP_TIMEOUT_MS = 120_000L

    /** 获取RimeApi实例（需要先调用initialize） */
    override val api: RimeApi
        get() = rimeApi ?: throw IllegalStateException("RimeSession 未初始化，请先调用 initialize()")

    /** 获取消息流 */
    override val messageFlow: SharedFlow<RimeMessage>
        get() = RimeMessageHandler.messageFlow

    /** 是否已初始化 */
    override val initialized: Boolean
        get() = isInitialized

    /**
     * 初始化Rime会话
     * @param context 应用上下文
     * @param fullCheck 是否完整检查（首次安装/升级后为true）
     */
    override suspend fun initialize(context: Context, fullCheck: Boolean) = lifecycleMutex.withLock {
        doInitialize(context, fullCheck)
    }

    /** 实际初始化逻辑（不加锁，仅由已持有 [lifecycleMutex] 的调用方调用）。 */
    private suspend fun doInitialize(context: Context, fullCheck: Boolean) {
        if (isInitialized) {
            Log.w(TAG, "RimeSession 已初始化，跳过重复初始化")
            return
        }

        Log.i(TAG, "开始初始化 RimeSession")

        // 资源部署、主词库重写、目录创建均为阻塞磁盘 IO。调用方（IME 服务）通常在
        // Dispatchers.Main 上发起本挂起函数，若直接执行会阻塞主线程（首次安装/升级时
        // 需递归复制整包 assets，有 ANR 风险），故显式切到 IO 线程执行。
        withContext(Dispatchers.IO) {
            // 按组合根装配顺序执行部署步骤（资源部署 → 扩展词库注入）
            deploySteps.forEach { step ->
                step.beforeStartup(context)
            }
            Log.i(TAG, "部署步骤执行完成（共 ${deploySteps.size} 步）")

            // 准备目录
            ensureDirectories(getSharedDataDir(context), getUserDataDir(context))
        }

        val sharedDir = getSharedDataDir(context)
        val userDir = getUserDataDir(context)

        // 创建调度器和API实例
        val newDispatcher = RimeDispatcher()
        val newApi = SimpleRimeImpl(newDispatcher)

        dispatcher = newDispatcher
        rimeApi = newApi
        sessionScope = CoroutineScope(SupervisorJob() + newDispatcher.dispatcher)

        // 获取版本名
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }

        // 启动引擎（带超时保护，避免 start_maintenance 阻塞过久）
        Log.i(TAG, "启动 Rime 引擎（超时=${STARTUP_TIMEOUT_MS}ms）...")
        val started = withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
            newApi.startup(
                sharedDir = sharedDir.absolutePath,
                userDir = userDir.absolutePath,
                version = versionName,
                fullCheck = fullCheck
            )
            true
        }

        if (started == true) {
            isInitialized = true
            Log.i(TAG, "RimeSession 初始化完成")
        } else {
            Log.e(TAG, "Rime 引擎启动超时（${STARTUP_TIMEOUT_MS}ms），请检查词典文件或降低 fullCheck")
            // 即使超时也标记为已初始化，允许后续操作（引擎可能仍在后台维护）
            isInitialized = true
        }
    }

    /**
     * 销毁Rime会话，释放所有资源
     */
    override suspend fun destroy() = lifecycleMutex.withLock {
        doDestroy()
    }

    /** 实际销毁逻辑（不加锁，仅由已持有 [lifecycleMutex] 的调用方调用）。 */
    private suspend fun doDestroy() {
        if (!isInitialized) return

        Log.i(TAG, "销毁 RimeSession")

        try {
            rimeApi?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "关闭Rime引擎异常: ${e.message}")
        }

        sessionScope?.cancel()
        dispatcher?.shutdown()

        rimeApi = null
        dispatcher = null
        sessionScope = null
        isInitialized = false

        Log.i(TAG, "RimeSession 已销毁")
    }

    /**
     * 重新部署 RIME 引擎（词库变更后调用）
     * 关闭当前引擎 → 重新执行部署步骤 → 以 fullCheck 模式重启
     */
    override suspend fun redeploy(context: Context) {
        lifecycleMutex.withLock {
            Log.i(TAG, "开始重新部署 RIME 引擎")
            // 关闭当前引擎（doDestroy 在未初始化时安全早返回）
            doDestroy()
            // 以 fullCheck 模式重新启动（doInitialize 内部已在 IO 线程重新执行部署步骤）
            doInitialize(context, fullCheck = true)
            Log.i(TAG, "RIME 引擎重新部署完成")
        }
    }

    /**
     * 获取共享数据目录（存放schema等配置文件）
     */
    fun getSharedDataDir(context: Context): File {
        return File(context.filesDir, SHARED_DATA_DIR)
    }

    /**
     * 获取用户数据目录（存放用户词典、同步数据等）
     */
    fun getUserDataDir(context: Context): File {
        return File(context.filesDir, USER_DATA_DIR)
    }

    /**
     * 确保数据目录存在
     */
    private fun ensureDirectories(vararg dirs: File) {
        dirs.forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
                Log.d(TAG, "创建目录: ${dir.absolutePath}")
            }
        }
    }
}
