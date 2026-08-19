package com.ziyou.ime.sdk

import android.content.Context
import android.util.Log
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.config.RimeConfigManager
import com.ziyou.ime.daemon.RimeDeployStep
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.daemon.RimeSession

/**
 * SDK 初始化配置（docs/SDK模块拆分重构方案.md §4.1）。
 *
 * @property deployVersion 部署版本号：assets 资源版本对比依据。宿主显式传入
 *           （通常即自身 versionCode），使 SDK 版本与宿主版本解耦；
 *           传 null 回退读宿主 versionCode（存量行为）。
 * @property preDeploySteps 资源部署之后、引擎启动之前的宿主业务步骤
 *           （如 predict.db 回盖、扩展词库注入主词库），按列表顺序执行。
 */
data class RimeSdkConfig(
    val deployVersion: Long? = null,
    val preDeploySteps: List<RimeDeployStep> = emptyList()
)

/**
 * 字由 Rime SDK 门面（唯一推荐入口）。
 *
 * 封装引擎生命周期与部署步骤装配，宿主不再直接触碰 [RimeSession] 等内部单例：
 * ```
 * RimeSdk.init(context, RimeSdkConfig(
 *     deployVersion = BuildConfig.VERSION_CODE.toLong(),
 *     preDeploySteps = listOf(...)          // 宿主业务部署步骤
 * ))
 * serviceScope.launch { RimeSdk.start(context, fullCheck = needsDeploy) }
 * val engine = RimeSdk.engine                 // RimeApi / messageFlow
 * ```
 *
 * 线程与互斥语义沿用 [RimeSession]：initialize/redeploy/destroy 由
 * lifecycleMutex 串行化，重复 [init] 幂等（覆盖装配点），[start] 幂等（已初始化早返回）。
 */
object RimeSdk {

    private const val TAG = "RimeSdk"

    @Volatile
    private var config: RimeSdkConfig? = null

    /** 引擎（生命周期 + RimeApi + 消息流）。init 之前访问返回的生产实现未装配部署步骤。 */
    val engine: RimeEngine get() = RimeSession

    /** 引擎配置读取（default.yaml / schema 配置）。 */
    val configManager: RimeConfigManager get() = RimeConfigManager

    /** 是否已初始化。 */
    val initialized: Boolean get() = RimeSession.initialized

    /**
     * 装配部署步骤（幂等，可重复调用；不启动引擎）。
     *
     * 装配顺序：SDK 通用资源部署（[AssetDeployer]，承接 deployVersion）→
     * 宿主 [RimeSdkConfig.preDeploySteps]。daemon 层不直接依赖宿主业务模块，
     * 依赖方向经 [RimeDeployStep] 反转（与既有 AppContainer 装配语义一一对应）。
     */
    fun init(context: Context, sdkConfig: RimeSdkConfig) {
        config = sdkConfig
        RimeSession.deploySteps = buildList {
            add(RimeDeployStep { ctx ->
                AssetDeployer.deployIfNeeded(ctx, sdkConfig.deployVersion)
            })
            addAll(sdkConfig.preDeploySteps)
        }
        Log.i(TAG, "已装配 ${1 + sdkConfig.preDeploySteps.size} 个部署步骤")
    }

    /**
     * 启动引擎（幂等：已初始化时早返回）。fullCheck=true 触发完整维护
     * （首次安装/升级后编译新方案）。
     */
    suspend fun start(context: Context, fullCheck: Boolean) {
        if (config == null) {
            Log.w(TAG, "start() 前未调用 init()，使用无业务步骤的默认装配")
        }
        RimeSession.initialize(context, fullCheck)
    }

    /** 词库/配置变更后重新部署引擎使变更生效。 */
    suspend fun redeploy(context: Context) = RimeSession.redeploy(context)

    /** 销毁会话释放资源。 */
    suspend fun shutdown() = RimeSession.destroy()
}
