package com.ziyou.ime.core

import android.util.Log
import kotlinx.coroutines.flow.SharedFlow

/**
 * RimeApi接口的具体实现
 * 通过RimeDispatcher将所有操作调度到单一线程执行
 */
class SimpleRimeImpl(
    private val dispatcher: RimeDispatcher
) : RimeApi {

    companion object {
        private const val TAG = "SimpleRimeImpl"

        /**
         * 解析 JNI 层 processRimeKeyBulk 返回的原生数组为 [KeyEventResult]。
         *
         * 提取为伴生方法以便单元测试覆盖解析逻辑（无需加载 native 库）。
         * 数组格式：[consumed: Boolean, commit: CommitProto?, context: ContextProto?]
         */
        internal fun parseBulkResult(raw: Array<Any?>?): KeyEventResult = KeyEventResult(
            consumed = raw?.getOrNull(0) as? Boolean ?: false,
            commit = raw?.getOrNull(1) as? CommitProto,
            context = raw?.getOrNull(2) as? ContextProto
        )
    }

    // ===== 生命周期 =====

    override suspend fun startup(sharedDir: String, userDir: String, version: String, fullCheck: Boolean) {
        dispatcher.dispatch {
            if (!RimeNative.isLoaded) {
                Log.e(TAG, "rime_jni 库未加载，无法启动Rime引擎")
                throw IllegalStateException("rime_jni 库未加载，无法启动Rime引擎")
            }
            Log.i(TAG, "启动Rime引擎: shared=$sharedDir, user=$userDir, version=$version")
            RimeNative.startupRime(sharedDir, userDir, version, fullCheck)
            Log.i(TAG, "Rime引擎启动完成")
        }
    }

    override suspend fun shutdown() {
        dispatcher.dispatch {
            Log.i(TAG, "关闭Rime引擎")
            RimeNative.exitRime()
        }
    }

    // ===== 输入处理 =====

    override suspend fun processKey(keycode: Int, mask: Int): Boolean {
        return dispatcher.dispatch {
            RimeNative.processRimeKey(keycode, mask)
        }
    }

    override suspend fun processKeyBulk(keycode: Int, mask: Int): KeyEventResult {
        return dispatcher.dispatch {
            val raw = RimeNative.processRimeKeyBulk(keycode, mask)
            parseBulkResult(raw)
        }
    }

    override suspend fun commitComposition(): Boolean {
        return dispatcher.dispatch {
            RimeNative.commitRimeComposition()
        }
    }

    override suspend fun clearComposition() {
        dispatcher.dispatch {
            RimeNative.clearRimeComposition()
        }
    }

    override suspend fun replaceKey(caretPos: Int, length: Int, replacement: String): Boolean {
        return dispatcher.dispatch {
            RimeNative.replaceRimeKey(caretPos, length, replacement)
        }
    }

    // ===== 状态查询 =====

    override suspend fun getCommit(): CommitProto? {
        return dispatcher.dispatch {
            RimeNative.getRimeCommit()
        }
    }

    override suspend fun getContext(): ContextProto? {
        return dispatcher.dispatch {
            RimeNative.getRimeContext()
        }
    }

    override suspend fun getStatus(): StatusProto? {
        return dispatcher.dispatch {
            RimeNative.getRimeStatus()
        }
    }

    override suspend fun getCandidates(startIndex: Int, limit: Int): List<CandidateProto> {
        return dispatcher.dispatch {
            RimeNative.getRimeCandidates(startIndex, limit)?.toList() ?: emptyList()
        }
    }

    // ===== 候选操作 =====

    override suspend fun selectCandidate(index: Int, global: Boolean): Boolean {
        return dispatcher.dispatch {
            RimeNative.selectRimeCandidate(index, global)
        }
    }

    override suspend fun deleteCandidate(index: Int, global: Boolean): Boolean {
        return dispatcher.dispatch {
            RimeNative.deleteRimeCandidate(index, global)
        }
    }

    override suspend fun changePage(backward: Boolean): Boolean {
        return dispatcher.dispatch {
            RimeNative.changeRimeCandidatePage(backward)
        }
    }

    // ===== 方案管理 =====

    override suspend fun getSchemaList(): List<SchemaItem> {
        return dispatcher.dispatch {
            RimeNative.getRimeSchemaList()?.toList() ?: emptyList()
        }
    }

    override suspend fun getCurrentSchema(): String {
        return dispatcher.dispatch {
            RimeNative.getCurrentRimeSchema() ?: ""
        }
    }

    override suspend fun selectSchema(schemaId: String): Boolean {
        return dispatcher.dispatch {
            RimeNative.selectRimeSchema(schemaId)
        }
    }

    // ===== 运行时选项 =====

    override suspend fun setOption(key: String, value: Boolean) {
        dispatcher.dispatch {
            RimeNative.setRimeOption(key, value)
        }
    }

    override suspend fun getOption(key: String): Boolean {
        return dispatcher.dispatch {
            RimeNative.getRimeOption(key)
        }
    }

    // ===== 同步 =====

    override suspend fun syncUserData(): Boolean {
        return dispatcher.dispatch {
            RimeNative.syncRimeUserData()
        }
    }

    // ===== 消息流 =====

    override val messageFlow: SharedFlow<RimeMessage>
        get() = RimeMessageHandler.messageFlow
}
