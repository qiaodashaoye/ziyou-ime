package com.ziyou.ime.di

import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.daemon.RimeDeployStep
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.daemon.RimeSession
import com.ziyou.ime.dict.DictManager
import com.ziyou.ime.level.LevelStats

/**
 * 轻量依赖容器（手写 DI 的组合根 composition root）。
 *
 * 目的：把"全局单例硬依赖"收敛到唯一可替换的装配点——
 * 调用方（IME 服务 / 设置页 / 词库页）经 [AppContainer] 获取 [RimeEngine] 等协作对象，
 * 而非直接引用 [RimeSession] 等单例；测试时可通过 [overrideRimeEngine] 注入 fake 实现。
 *
 * 装配职责：
 * - [RimeSession.deploySteps]：引擎启动前的部署步骤（资源部署 → 扩展词库注入），
 *   使 daemon 层不直接依赖 config / dict 业务模块（依赖方向经此反转）。
 * - [commitListeners]：编辑器路径上屏后的横切监听（等级计分），
 *   使输入热路径（InputLogicController）不硬编码业务单例。
 *
 * 后续可平滑迁移到 Hilt/Koin。
 */
object AppContainer {

    @Volatile
    private var rimeEngineOverride: RimeEngine? = null

    /** 生产引擎：首次访问时完成部署步骤装配（懒装配，线程安全由 lazy 保证）。 */
    private val defaultEngine: RimeEngine by lazy {
        RimeSession.deploySteps = listOf(
            // 第一步：部署资源文件（首次安装/升级时从 assets 复制到内部存储）
            RimeDeployStep { context -> AssetDeployer.deployIfNeeded(context) },
            // 第二步：注入已启用的扩展词库到主词库文件
            // （AssetDeployer 可能覆盖了 luna_pinyin.dict.yaml，需重新追加扩展词库引用）
            RimeDeployStep { context -> DictManager.regenerateMainDict(context) }
        )
        RimeSession
    }

    /** Rime 引擎（默认生产实现为 [RimeSession]，可被测试覆盖）。 */
    val rimeEngine: RimeEngine
        get() = rimeEngineOverride ?: defaultEngine

    /**
     * 编辑器路径上屏监听（注入 InputLogicController）：
     * 当前仅等级计分（O(1) 内存自增，热路径安全）；参数为脱敏的 Unicode 码点数。
     */
    val commitListeners: List<(codePoints: Int) -> Unit> = listOf(
        { codePoints -> LevelStats.onCommit(codePoints) }
    )

    /** 测试注入点：用 fake 引擎替换默认实现。 */
    fun overrideRimeEngine(engine: RimeEngine?) {
        rimeEngineOverride = engine
    }
}
