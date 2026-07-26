package com.ziyou.ime.core

import android.util.Log

/**
 * Rime引擎JNI接口声明
 * 所有native方法对应C++层的JNI导出函数
 * 注意：这些方法不是线程安全的，必须通过RimeDispatcher在单一线程调用
 */
object RimeNative {
    private const val TAG = "RimeNative"

    /** 标记 native 库是否成功加载 */
    @JvmStatic
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("rime_jni")
            isLoaded = true
            Log.i(TAG, "rime_jni 库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            Log.e(TAG, "rime_jni 库加载失败: ${e.message}", e)
        }
    }

    /**
     * 检查 native 库是否已加载，未加载则抛出异常
     * 应在调用任何 native 方法前调用
     */
    private fun ensureLoaded() {
        if (!isLoaded) {
            throw IllegalStateException(
                "rime_jni 库未加载，无法调用 native 方法。" +
                "可能原因：ABI 不匹配（仅支持 arm64-v8a）或 .so 文件缺失"
            )
        }
    }

    // ===== 生命周期管理 =====

    /** 启动Rime引擎 */
    @JvmStatic
    external fun startupRime(sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean)

    /** 退出Rime引擎，释放资源 */
    @JvmStatic
    external fun exitRime()

    // ===== 输入处理（热路径） =====

    /** 处理按键事件，返回是否被Rime消费 */
    @JvmStatic
    external fun processRimeKey(keycode: Int, mask: Int): Boolean

    /**
     * 批量处理按键（热路径）：一次 JNI 跨界完成 processKey + getCommit + getContext。
     * 返回 [consumed: Boolean, commit: CommitProto?, context: ContextProto?]；未消费时后两项为 null。
     */
    @JvmStatic
    external fun processRimeKeyBulk(keycode: Int, mask: Int): Array<Any?>?

    /** 提交当前编码 */
    @JvmStatic
    external fun commitRimeComposition(): Boolean

    /** 清除当前编码 */
    @JvmStatic
    external fun clearRimeComposition()

    /** 替换编码中指定位置的键序列（用于九宫格拼音消歧） */
    @JvmStatic
    external fun replaceRimeKey(caretPos: Int, length: Int, key: String): Boolean

    // ===== 状态获取 =====

    /** 获取已提交的文本 */
    @JvmStatic
    external fun getRimeCommit(): CommitProto?

    /** 获取当前输入上下文（编码、候选词等） */
    @JvmStatic
    external fun getRimeContext(): ContextProto?

    /** 获取当前状态（方案、模式等） */
    @JvmStatic
    external fun getRimeStatus(): StatusProto?

    /** 获取候选词列表 */
    @JvmStatic
    external fun getRimeCandidates(startIndex: Int, limit: Int): Array<CandidateProto>?

    /** 批量获取候选词（包含总数和高亮索引） */
    @JvmStatic
    external fun getRimeBulkCandidates(): Array<Any>?

    // ===== 候选操作 =====

    /** 选择候选词 */
    @JvmStatic
    external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

    /** 删除候选词 */
    @JvmStatic
    external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

    /** 翻页 */
    @JvmStatic
    external fun changeRimeCandidatePage(backward: Boolean): Boolean

    // ===== 方案管理 =====

    /** 获取可用方案列表 */
    @JvmStatic
    external fun getRimeSchemaList(): Array<SchemaItem>?

    /** 获取当前方案ID */
    @JvmStatic
    external fun getCurrentRimeSchema(): String?

    /** 切换方案 */
    @JvmStatic
    external fun selectRimeSchema(schemaId: String): Boolean

    // ===== 运行时选项 =====

    /** 设置选项（如ascii_mode, simplification等） */
    @JvmStatic
    external fun setRimeOption(option: String, value: Boolean)

    /** 获取选项值 */
    @JvmStatic
    external fun getRimeOption(option: String): Boolean

    // ===== 同步 =====

    /** 同步用户数据 */
    @JvmStatic
    external fun syncRimeUserData(): Boolean

    // ===== 消息回调（由JNI层调用） =====

    /**
     * 处理Rime通知消息
     * @param type 消息类型: 1=schema, 2=option, 3=deploy
     * @param args 消息参数数组
     */
    @JvmStatic
    fun handleRimeMessage(type: Int, args: Array<Any>) {
        val messageValue = args.firstOrNull()?.toString() ?: return
        val message = when (type) {
            1 -> RimeMessage.SchemaMessage(messageValue)
            2 -> RimeMessage.OptionMessage(messageValue)
            3 -> RimeMessage.DeployMessage(messageValue)
            else -> RimeMessage.UnknownMessage(type, messageValue)
        }
        RimeMessageHandler.onMessage(message)
    }
}
