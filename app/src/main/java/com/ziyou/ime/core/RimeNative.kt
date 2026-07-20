package com.ziyou.ime.core

import android.util.Log

/**
 * Rime引擎JNI接口声明
 * 所有native方法对应C++层的JNI导出函数
 * 注意：这些方法不是线程安全的，必须通过RimeDispatcher在单一线程调用
 */
object RimeNative {
    private const val TAG = "RimeNative"

    init {
        try {
            System.loadLibrary("rime_jni")
            Log.i(TAG, "rime_jni 库加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "rime_jni 库加载失败: ${e.message}")
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

    /** 提交当前编码 */
    @JvmStatic
    external fun commitRimeComposition(): Boolean

    /** 清除当前编码 */
    @JvmStatic
    external fun clearRimeComposition()

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
