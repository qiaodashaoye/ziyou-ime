package com.ziyou.ime.ime

import android.os.SystemClock
import android.util.Log
import com.ziyou.ime.config.SchemaPreference
import com.ziyou.ime.core.t9.KeyRecordStack
import com.ziyou.ime.daemon.RimeEngine
import com.ziyou.ime.data.AssociationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 键盘布局与引擎状态同步协调器。
 *
 * 从 [ZiYouInputMethodService] 剥离「布局切换 → 引擎方案/模式同步 → UI 刷新」
 * 全链路职责，使 Service 聚焦于 Android 生命周期与事件分发
 * （与 [InputLogicController] / [KeyboardLayoutManager] 同一拆分纪律）。
 *
 * 核心职责：
 * - [switchKeyboard]：布局切换入口，重建键盘视图并触发引擎同步
 * - [switchToQwertyEnglish]：九宫格「中→英」专用入口
 * - [scheduleEngineSync]：latest-wins 引擎同步调度（取消旧任务，等引擎就绪后执行）
 * - [applyEngineForKeyboard]：按键盘类型同步 Rime 方案与中英模式
 * - [awaitEngineReady]：等待引擎就绪轮询（重部署窗口期不失败）
 *
 * 所有方法均须在主线程调用（[scheduleEngineSync] 内部 launch 到 [Host.serviceScope]）。
 */
class EngineSyncController(
    private val host: Host
) {

    companion object {
        private const val TAG = "EngineSyncCtrl"

        /** 等待引擎就绪的轮询间隔（ms） */
        private const val ENGINE_READY_POLL_MS = 50L
        /** 视图同步类操作等待引擎就绪的超时（ms）：词库重部署可能耗时较长 */
        const val ENGINE_READY_TIMEOUT_MS = 10_000L
        /** 按键处理等待引擎就绪的短超时（ms）：避免按键响应长时间挂起 */
        const val KEY_ENGINE_READY_TIMEOUT_MS = 3_000L
    }

    /** 协调器需要 Service 提供的能力：引擎访问、视图回调与键盘管理。 */
    interface Host {
        /** Rime 引擎（经 DI 容器，测试可注入 fake） */
        val rime: RimeEngine

        /** 服务协程作用域 */
        val serviceScope: CoroutineScope

        /** 当前键盘布局类型 */
        val currentKeyboardType: KeyboardType

        /** 九宫格输入状态追踪栈 */
        val keyRecordStack: KeyRecordStack

        /** 安装指定类型的键盘到容器（委托 [KeyboardLayoutManager]） */
        fun installKeyboard(type: KeyboardType)

        /** 持久化键盘布局偏好（符号/数字为临时面板，不持久化） */
        fun saveKeyboardType(type: KeyboardType)

        /** 清除编码区预览文本（布局切换前残留） */
        fun clearPreeditPreview()

        /** 从引擎获取最新上下文并刷新全部 UI（候选词/编码区/拼音侧栏/工具栏） */
        suspend fun renderFromEngine()

        /** 同步键盘视图的中英文模式显示 */
        fun setKeyboardChineseMode(isChinese: Boolean)

        /** 当前 Service 上下文（用于读取 SharedPreferences 与资源） */
        val serviceContext: android.content.Context
    }

    /** 引擎状态同步任务（latest-wins 串行化：新同步请求到来时取消上一次，
     *  避免快速切换键盘/部署完成/形态切换的并发同步交错导致迟到写入） */
    private var engineSyncJob: Job? = null

    /**
     * 九宫格"中→英"专用标志。
     * 当为 true 时，[applyEngineForKeyboard] 强制设置 ascii_mode=true，
     * handleSoftKeyPress 跳过 KEYCODE_SWITCH_LANGUAGE 的异步 toggle，避免竞态。
     */
    var pendingEnglishMode = false

    /** 九宫格"中→英"进入 QWERTY 英文前的布局，用于英→中返回原布局
     *  （null 表示非九宫格中→英入口；任何手动布局切换都会清除，避免陈旧恢复） */
    var qwertyEnglishOrigin: KeyboardType? = null

    /**
     * 切换键盘布局，重建视图并同步方案 / 中英文模式 / 编码区。
     * 幂等：相同类型且键盘视图已存在时短路。
     */
    fun switchKeyboard(type: KeyboardType) {
        // 任何布局切换都使"中→英"的英→中返回标记失效（经其他路径手动切换后不再自动返回）
        qwertyEnglishOrigin = null
        if (type == host.currentKeyboardType && type != KeyboardType.SYMBOL && type != KeyboardType.NUMBER) return
        host.keyRecordStack.clear()
        host.installKeyboard(type)
        host.saveKeyboardType(type)
        // 清除切换前残留的预览
        host.clearPreeditPreview()
        scheduleEngineSync()
    }

    /**
     * 九宫格"中→英"专用切换：强制 ascii_mode=true 并切到 QWERTY。
     * 不走 handleSoftKeyPress 异步路径，避免与 [applyEngineForKeyboard] 竞态。
     * 同时记录进入前布局，供 QWERTY 上英→中时返回原布局（如九宫格）。
     */
    fun switchToQwertyEnglish() {
        pendingEnglishMode = true
        val origin = host.currentKeyboardType
        switchKeyboard(KeyboardType.QWERTY)
        // 在 switchKeyboard 之后记录（switchKeyboard 会统一清除该标记）
        qwertyEnglishOrigin = origin
    }

    /**
     * 调度一次引擎状态同步（latest-wins）：取消进行中的旧任务，等引擎就绪后
     * 按「执行时」的当前键盘类型执行 [applyEngineForKeyboard]。
     * 所有入口（键盘切换 / 获焦 / 部署完成 / 形态与主题切换 / 方案一致性守护）
     * 共用本方法，串行化消除并发同步交错导致的迟到写入（如快速
     * 九宫格→全键盘切换时，滞后的九宫格同步把引擎切回 t9）。
     *
     * @param timeoutMs 等待引擎就绪的超时；超时放弃本次，由部署完成消息触发的重同步兑底
     * @param beforeSync 引擎就绪后、同步前的前置操作（如 onStartInputView 清编码）
     */
    fun scheduleEngineSync(
        timeoutMs: Long = KEY_ENGINE_READY_TIMEOUT_MS,
        beforeSync: (suspend () -> Unit)? = null
    ) {
        engineSyncJob?.cancel()
        engineSyncJob = host.serviceScope.launch {
            try {
                if (!awaitEngineReady(timeoutMs)) {
                    Log.w(TAG, "引擎状态同步放弃：Rime引擎未就绪（可能正在重新部署，待部署完成消息重同步）")
                    return@launch
                }
                beforeSync?.invoke()
                applyEngineForKeyboard(host.currentKeyboardType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "引擎状态同步异常: ${e.message}")
                // 同步失败（如重部署窗口期 rime.api 抛异常）时清除中→英标志，
                // 避免残留的 pendingEnglishMode 吞掉后续一次中英切换
                pendingEnglishMode = false
            }
        }
    }

    /**
     * 根据当前键盘类型同步 Rime 方案与状态（「布局 ↔ 方案」映射元数据见 [KeyboardType]）：
     * - 九宫格：切到专用 T9 方案（[KeyboardType.forcedSchemaId]）并保证中文模式
     *   （多击字母才能匹配拼音候选）
     * - 全键盘：对齐到用户持久化的全键盘方案偏好（[SchemaPreference]，
     *   替代早期易失的 schemeBeforeT9 内存记忆，进程重建后选择不丢）
     * 所有 selectSchema 均检查返回值，失败时记日志不静默吞掉；最后把状态同步到 UI。
     */
    private suspend fun applyEngineForKeyboard(type: KeyboardType) {
        val rime = host.rime
        when (type) {
            KeyboardType.NINE_GRID -> {
                val forced = requireNotNull(type.forcedSchemaId)
                if (rime.api.getCurrentSchema() != forced) {
                    if (!rime.api.selectSchema(forced)) {
                        Log.e(TAG, "切换九宫格专用方案失败: $forced（方案可能未编译/部署异常，可尝试设置页重新部署）")
                    }
                }
                if (rime.api.getOption("ascii_mode")) {
                    rime.api.setOption("ascii_mode", false)
                }
            }
            KeyboardType.QWERTY -> {
                // 对齐到用户的全键盘方案偏好（覆盖 t9 残留与外部意外切换）；
                // 偏好方案切换失败（如方案已移除）时回退默认方案兼底
                val ctx = host.serviceContext
                val preferred = SchemaPreference.getQwertySchema(ctx)
                if (rime.api.getCurrentSchema() != preferred) {
                    if (!rime.api.selectSchema(preferred)) {
                        Log.e(TAG, "恢复全键盘方案失败: $preferred，回退默认方案 ${SchemaPreference.DEFAULT_SCHEMA_ID}")
                        if (preferred != SchemaPreference.DEFAULT_SCHEMA_ID &&
                            !rime.api.selectSchema(SchemaPreference.DEFAULT_SCHEMA_ID)
                        ) {
                            Log.e(TAG, "回退默认方案也失败: ${SchemaPreference.DEFAULT_SCHEMA_ID}")
                        }
                    }
                }
                // 九宫格"中→英"触发：强制英文模式，避免与 handleSoftKeyPress 竞态
                if (pendingEnglishMode) {
                    rime.api.setOption("ascii_mode", true)
                    pendingEnglishMode = false
                }
            }
            KeyboardType.SYMBOL -> {
                // 符号键盘为临时面板：清除活跃编码避免残留 preedit，
                // 方案与 ascii_mode 保持不变，「返回」后无感恢复原键盘状态
                rime.api.clearComposition()
                host.keyRecordStack.clear()
            }
            KeyboardType.NUMBER -> {
                // 数字键盘与符号键盘同模式：临时面板，清编码不动方案与 ascii_mode
                rime.api.clearComposition()
                host.keyRecordStack.clear()
            }
        }
        val isAscii = rime.api.getOption("ascii_mode")
        // 引擎级联想（librime-predict）选项联动：与应用层联想总开关同步。
        // 当前预编译库未启用 predict 模块 / schema 未挂 predictor 时为无害 no-op，
        // 启用后无需改动即可由同一开关控制引擎预测（选项名见 predictor 源码 "prediction"）
        rime.api.setOption("prediction", AssociationManager.isEnabled(host.serviceContext))
        // 同步键盘中英态显示后，刷新全部 UI（候选词/编码区/拼音侧栏/工具栏）
        withContext(Dispatchers.Main) {
            host.setKeyboardChineseMode(!isAscii)
            host.renderFromEngine()
        }
    }

    /**
     * 等待 Rime 引擎就绪（初始化完成）。
     *
     * 词库下载/启用后 [com.ziyou.ime.sdk.RimeSdk.redeploy] 会销毁并重建引擎，
     * 窗口期内 `rime.api` 直接抛 IllegalStateException。所有非热路径的引擎访问
     * （状态同步、模式切换）先经本方法等待，避免在重部署期间直接失败且无重试。
     *
     * @return true 表示引擎已就绪；false 表示等待超时（调用方应放弃本次操作，
     *         由部署完成消息触发的重同步兑底）
     */
    suspend fun awaitEngineReady(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!host.rime.initialized) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(ENGINE_READY_POLL_MS)
        }
        return true
    }
}
