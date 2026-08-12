package com.ziyou.ime.di

import com.ziyou.ime.ZiyouApplication
import com.ziyou.ime.ai.prediction.LlmPredictionConfig
import com.ziyou.ime.ai.prediction.LlmPredictionCoordinator
import com.ziyou.ime.config.AssetDeployer
import com.ziyou.ime.daemon.RimeDeployStep
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.daemon.RimeSession
import com.ziyou.ime.dict.DictManager
import com.ziyou.ime.level.LevelStats
import com.ziyou.ime.voice.SherpaOnnxEngine
import com.ziyou.ime.voice.SpeechRecognizerEngine

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
 * - [commitTextObservers]：编辑器路径上屏后的文本观察者（LLM 智能续写），
 *   与脱敏的 [commitListeners] 语义隔离，由组合根统一装配。
 *
 * 后续可平滑迁移到 Hilt/Koin。
 */
object AppContainer {

    @Volatile
    private var rimeEngineOverride: RimeEngine? = null

    @Volatile
    private var speechEngineOverride: SpeechRecognizerEngine? = null

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
     * 语音识别引擎默认实现（懒装配：首次访问才构造，不触发 JNI 库加载与模型加载）。
     *
     * 不用 `by lazy`：Service onDestroy 会 [SpeechRecognizerEngine.release] 归还
     * native 内存，但进程可能存活且 Service 随后重建——已释放实例的
     * `destroyed` 是单向闩锁，懒获取处须能重建全新实例（见 speechEngine）。
     */
    @Volatile
    private var defaultSpeechEngine: SpeechRecognizerEngine? = null

    /** 流式语音识别引擎（默认生产实现为 [SherpaOnnxEngine]，可被测试覆盖）。 */
    val speechEngine: SpeechRecognizerEngine
        get() {
            speechEngineOverride?.let { return it }
            val current = defaultSpeechEngine
            if (current != null && !current.isReleased) return current
            return synchronized(this) {
                val rechecked = defaultSpeechEngine
                if (rechecked != null && !rechecked.isReleased) {
                    rechecked
                } else {
                    SherpaOnnxEngine().also { defaultSpeechEngine = it }
                }
            }
        }

    /** 测试注入点：用 fake 引擎替换默认语音识别实现。 */
    fun overrideSpeechEngine(engine: SpeechRecognizerEngine?) {
        speechEngineOverride = engine
    }

    /**
     * 编辑器路径上屏监听（注入 InputLogicController）：
     * 当前仅等级计分（O(1) 内存自增，热路径安全）；参数为脱敏的 Unicode 码点数。
     */
    val commitListeners: List<(codePoints: Int) -> Unit> = listOf(
        { codePoints -> LevelStats.onCommit(codePoints) }
    )

    /**
     * LLM 智能续写协调器（懒装配：首次访问才构造）。注意 Service 生命周期回调
     * （onStartInput/onStartInputView/renderContext 等）无条件访问本属性，故首次
     * 进入输入即触发构造；构造成本极低（空词窗口 + 空 LRU），关闭功能时运行期
     * 每次上屏仅一次 SharedPreferences 内存布尔读。构造需 app context，
     * 取 [ZiyouApplication] 实例。
     */
    val llmPredictionCoordinator: LlmPredictionCoordinator by lazy {
        LlmPredictionCoordinator(ZiyouApplication.instance.applicationContext)
    }

    /**
     * 编辑器路径上屏文本观察者（注入 InputLogicController）：
     * 当前仅 LLM 智能续写。lambda 内先查开关——关闭时热路径只有一次
     * SharedPreferences 内存缓存布尔读，保证零额外开销；文本观察者与
     * 脱敏的 [commitListeners] 是两个列表，语义隔离（等级链路不见内容）。
     */
    val commitTextObservers: List<(String) -> Unit> by lazy {
        listOf { text ->
            val context = ZiyouApplication.instance.applicationContext
            if (LlmPredictionConfig.isEnabled(context)) {
                llmPredictionCoordinator.onCommitText(text)
            }
        }
    }

    /** 测试注入点：用 fake 引擎替换默认实现。 */
    fun overrideRimeEngine(engine: RimeEngine?) {
        rimeEngineOverride = engine
    }
}
