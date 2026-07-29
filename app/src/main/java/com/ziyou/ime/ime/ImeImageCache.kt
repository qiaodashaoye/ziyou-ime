package com.ziyou.ime.ime

import android.content.Context
import java.io.File

/**
 * 输入法待发送图片共享缓存目录（单一来源）。
 *
 * cacheDir 下的 [CACHE_DIR_NAME] 子目录已经由 FileProvider（authority
 * `${applicationId}.imecontent`，路径配置见 res/xml/ime_file_paths.xml）暴露为
 * content:// URI，供 commitContent 富媒体提交时临时授权目标应用读取。
 * AI 答案转图（[TextImageRenderer]）、涂鸦导出（[DoodleImageExporter]）与
 * 技能图片输出（SkillRuntime）共用此目录。
 *
 * 清理策略：写入新图前经 [pruneExpired] 只删过期文件，而非全目录清空——
 * commitContent 提交后目标应用是**异步**拉取 content:// URI 的，立即全清会
 * 删掉尚未被对端读走的图片（快速连续发图时对端收到空图）。
 */
object ImeImageCache {

    /** 待发送图片共享缓存子目录名（cacheDir 下，FileProvider 已暴露） */
    const val CACHE_DIR_NAME = "ime_images"

    /** 过期阈值（ms）：对端应用异步读取 URI 的宽限窗口，超过即可安全删除 */
    private const val EXPIRE_MS = 5 * 60 * 1000L

    /** 过期清理后仍保留的文件数上限（防宽限窗口内爆发式发图累积） */
    private const val MAX_KEEP = 20

    /** 获取图片缓存目录（确保已创建）。 */
    fun dir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /**
     * 清理 [dir] 下的过期文件：删除 mtime 超过 [EXPIRE_MS] 的文件；
     * 剩余文件仍超过 [MAX_KEEP] 时按 mtime 从旧到新补删。
     * 各图片写入方（AI 转图 / 涂鸦导出 / 技能发图）在写新文件前调用。
     */
    fun pruneExpired(dir: File, nowMs: Long = System.currentTimeMillis()) {
        val files = dir.listFiles() ?: return
        val deadline = nowMs - EXPIRE_MS
        val survivors = files.filter { file ->
            if (file.lastModified() < deadline) {
                file.delete()
                false
            } else true
        }
        if (survivors.size > MAX_KEEP) {
            survivors.sortedBy { it.lastModified() }
                .take(survivors.size - MAX_KEEP)
                .forEach { it.delete() }
        }
    }
}
