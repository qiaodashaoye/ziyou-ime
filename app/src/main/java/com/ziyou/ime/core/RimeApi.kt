package com.ziyou.ime.core

import kotlinx.coroutines.flow.SharedFlow

/**
 * Rime引擎API接口
 * 所有操作都是suspend函数，通过RimeDispatcher在专属线程执行
 * 调用方无需关心线程安全问题
 */
interface RimeApi {

    // ===== 生命周期 =====

    /**
     * 启动Rime引擎
     * @param sharedDir 共享数据目录（schema文件等）
     * @param userDir 用户数据目录（用户词典等）
     * @param version 版本名称
     * @param fullCheck 是否进行完整检查（首次启动或部署时为true）
     */
    suspend fun startup(sharedDir: String, userDir: String, version: String, fullCheck: Boolean)

    /** 关闭Rime引擎，释放所有资源 */
    suspend fun shutdown()

    // ===== 输入处理 =====

    /** 处理按键，返回是否被引擎消费 */
    suspend fun processKey(keycode: Int, mask: Int): Boolean

    /**
     * 批量处理按键（热路径）：把 processKey + getCommit + getContext 合并为单次引擎调度，
     * 减少线程往返与 JNI 跨界次数。未被消费时 commit/context 为 null。
     * 接口默认实现按三次调用组合（便于 fake/测试），生产实现单次跨界。
     */
    suspend fun processKeyBulk(keycode: Int, mask: Int): KeyEventResult {
        val consumed = processKey(keycode, mask)
        if (!consumed) return KeyEventResult(consumed = false, commit = null, context = null)
        return KeyEventResult(consumed = true, commit = getCommit(), context = getContext())
    }

    /** 提交当前编码区内容 */
    suspend fun commitComposition(): Boolean

    /** 清除当前编码 */
    suspend fun clearComposition()

    /** 替换编码中指定位置的键序列（用于九宫格拼音消歧） */
    suspend fun replaceKey(caretPos: Int, length: Int, replacement: String): Boolean

    // ===== 状态查询 =====

    /** 获取已提交的文本（调用后自动清除） */
    suspend fun getCommit(): CommitProto?

    /** 获取当前输入上下文 */
    suspend fun getContext(): ContextProto?

    /** 获取当前输入法状态 */
    suspend fun getStatus(): StatusProto?

    /** 获取候选词列表 */
    suspend fun getCandidates(startIndex: Int, limit: Int): List<CandidateProto>

    // ===== 候选操作 =====

    /** 选择指定索引的候选词 */
    suspend fun selectCandidate(index: Int, global: Boolean = false): Boolean

    /** 删除指定索引的候选词（从用户词典移除） */
    suspend fun deleteCandidate(index: Int, global: Boolean = false): Boolean

    /** 翻页 */
    suspend fun changePage(backward: Boolean): Boolean

    // ===== 方案管理 =====

    /** 获取所有可用方案列表 */
    suspend fun getSchemaList(): List<SchemaItem>

    /** 获取当前活跃方案ID */
    suspend fun getCurrentSchema(): String

    /** 切换到指定方案 */
    suspend fun selectSchema(schemaId: String): Boolean

    // ===== 运行时选项 =====

    /** 设置选项值 */
    suspend fun setOption(key: String, value: Boolean)

    /** 获取选项值 */
    suspend fun getOption(key: String): Boolean

    // ===== 同步 =====

    /** 同步用户数据到同步目录 */
    suspend fun syncUserData(): Boolean

    // ===== 消息流 =====

    /** Rime消息事件流（schema切换、option变更、deploy状态） */
    val messageFlow: SharedFlow<RimeMessage>
}
