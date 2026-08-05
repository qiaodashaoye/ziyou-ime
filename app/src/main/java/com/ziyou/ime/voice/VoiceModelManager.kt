package com.ziyou.ime.voice

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.voice.VoiceModelFiles
import com.ziyou.ime.voice.VoiceModelCatalog.VoiceModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音模型管理器：内部存储中的模型目录生命周期
 * （枚举 / 就绪判定 / 下载 / 删除 / 激活选择），见 docs/实时语音输入可行性方案.md §5.4。
 *
 * 目录约定：`filesDir/voice-models/<model-id>/`，与 [SherpaOnnxEngine.loadModel]
 * 的目录结构约定一致；就绪判定口径委托 :core-logic 的 [VoiceModelFiles]。
 *
 * 隐私纪律：模型目录只存放权重与出处说明，绝不存放任何音频/识别文本。
 */
object VoiceModelManager {

    private const val TAG = "VoiceModelManager"

    /** 模型根目录名（filesDir 下） */
    private const val MODELS_DIR_NAME = "voice-models"

    /** 出处说明文件名（许可证审计留痕，随下载写入） */
    private const val SOURCE_FILE_NAME = "SOURCE.txt"

    private const val PREF_NAME = "ziyou_voice"
    private const val KEY_ACTIVE_MODEL = "active_model_id"
    private const val KEY_PANEL_MODE = "panel_mode"

    /** 面板上屏策略（持久化键值与 [VoiceCommitMode] 名称一致）。 */
    enum class VoiceCommitMode {
        /** 策略 A：每句端点确认后自动上屏（聊天场景默认） */
        AUTO_COMMIT,

        /** 策略 B：面板内缓冲，用户点「发送」一次上屏（长段落场景） */
        BUFFER_SEND,
    }

    // ===== 目录与状态查询 =====

    /** 模型根目录 */
    fun modelsRoot(context: Context): File = File(context.filesDir, MODELS_DIR_NAME)

    /** 指定模型的本地目录 */
    fun modelDir(context: Context, spec: VoiceModelSpec): File = File(modelsRoot(context), spec.id)

    /** 模型是否已就绪（全部必备文件存在且非空） */
    fun isInstalled(context: Context, spec: VoiceModelSpec): Boolean {
        val dir = modelDir(context, spec)
        if (!dir.isDirectory) return false
        val names = dir.list()?.toList() ?: return false
        return VoiceModelFiles.checkReady(names).isEmpty()
    }

    /** 当前激活模型（回退目录默认；激活项被删除时同样回退） */
    fun getActiveSpec(context: Context): VoiceModelSpec {
        val id = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_MODEL, null)
        val spec = id?.let { VoiceModelCatalog.byId(it) }
        return if (spec != null && isInstalled(context, spec)) spec
        else VoiceModelCatalog.DEFAULT
    }

    /** 设置激活模型（仅允许已安装的模型） */
    fun setActiveSpec(context: Context, spec: VoiceModelSpec): Boolean {
        if (!isInstalled(context, spec)) return false
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_MODEL, spec.id).apply()
        return true
    }

    /** 用户是否曾显式选过激活模型（下载完成后的「首个自动激活」据此判断）。 */
    fun hasExplicitActiveChoice(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_MODEL, null) != null

    /** 面板上屏策略偏好 */
    fun getCommitMode(context: Context): VoiceCommitMode {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PANEL_MODE, null)
        return raw?.let {
            runCatching { VoiceCommitMode.valueOf(it) }.getOrNull()
        } ?: VoiceCommitMode.AUTO_COMMIT
    }

    /** 保存面板上屏策略偏好 */
    fun setCommitMode(context: Context, mode: VoiceCommitMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PANEL_MODE, mode.name).apply()
    }

    // ===== 下载与删除 =====

    /**
     * 下载模型全部文件。断点续传粒度 = 单文件：已存在且 sha256 校验通过的
     * 文件直接跳过；校验不通过（截断/损坏）则重下，避免「已下载但永远加载失败」死循环。
     *
     * @param onProgress 进度回调 (已完成文件数, 文件总数, 当前文件已下载字节, 当前文件总字节)
     * @return null 成功；否则失败原因（已下载的部分文件保留，重试可续）
     */
    suspend fun downloadModel(
        context: Context,
        spec: VoiceModelSpec,
        onProgress: ((Int, Int, Long, Long) -> Unit)? = null,
    ): String? {
        val dir = modelDir(context, spec)
        withContext(Dispatchers.IO) { dir.mkdirs() }
        spec.files.forEachIndexed { index, fileName ->
            val target = File(dir, fileName)
            val expectedSha256 = spec.sha256s[fileName]
                ?: return "目录缺少 ${fileName} 的 sha256 锚定值，拒绝下载"
            if (target.isFile && VoiceModelDownloader.sha256Of(target)
                    .equals(expectedSha256, ignoreCase = true)
            ) {
                // 已存在且校验通过的完整文件直接跳过
                onProgress?.invoke(index + 1, spec.files.size, 0, 0)
                return@forEachIndexed
            }
            val error = VoiceModelDownloader.downloadFile(
                spec.repoPath, fileName, target, expectedSha256
            ) { downloaded, total ->
                onProgress?.invoke(index, spec.files.size, downloaded, total)
            }
            if (error != null) return error
        }
        // 出处留痕：许可证审计与版本追溯（不影响就绪判定）
        runCatching {
            File(dir, SOURCE_FILE_NAME).writeText(
                "model: ${spec.id}\nsource: ${spec.sourceUrl}\nlicense: see source repo\n"
            )
        }.onFailure { Log.w(TAG, "写入模型出处说明失败: ${it.message}") }
        return if (isInstalled(context, spec)) null else "模型文件不完整，请重试"
    }

    /** 删除模型目录（幂等）。删除的是当前激活模型时，下次读取自动回退默认。 */
    fun deleteModel(context: Context, spec: VoiceModelSpec) {
        modelDir(context, spec).deleteRecursively()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE_MODEL, null) == spec.id) {
            prefs.edit().remove(KEY_ACTIVE_MODEL).apply()
        }
    }
}
