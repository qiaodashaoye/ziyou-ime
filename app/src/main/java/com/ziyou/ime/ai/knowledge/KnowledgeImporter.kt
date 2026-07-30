package com.ziyou.ime.ai.knowledge

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.ziyou.ime.core.rag.SensitiveWordFilter
import com.ziyou.ime.core.rag.TextChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * 知识库导入器
 *
 * 三类数据源的导入流水线（全部 suspend，IO 线程执行）：
 * - [importFile]：SAF 单文件（txt/md），流式读入 → 敏感词清洗 → 分块 → 入库；
 * - [importFolder]：SAF 文件夹（[DocumentsContract] 递归枚举，深度/数量受限），
 *   逐文件复用单文件流程，treeUri 持久化授权供后续增量同步；
 * - [importText]：用户自定义文本块。
 *
 * 纪律：先完成全部校验与分块（内存态）、最后一步才写入仓库（借鉴
 * SkillPackageInstaller 的先校验后落盘，失败无残留）；容量超限直接拒绝
 * 并返回用户可读错误。
 */
object KnowledgeImporter {

    private const val TAG = "KnowledgeImporter"

    /** 单文件字节上限（2MB 文本足够覆盖常见知识文档） */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /** 文件夹枚举：最大递归深度 */
    private const val MAX_FOLDER_DEPTH = 3

    /** 文件夹枚举：文件总数上限 */
    private const val MAX_FOLDER_FILES = 100

    /** 可导入的文件扩展名（小写） */
    private val ALLOWED_EXTENSIONS = setOf("txt", "md", "markdown")

    /** 导入内容清洗过滤器（内置最小词表） */
    private val filter = SensitiveWordFilter(SensitiveWordFilter.DEFAULT_WORDS)

    // ===== 单文件导入 =====

    /** SAF 单文件导入：校验扩展名/大小 → 清洗分块 → 入库。 */
    suspend fun importFile(context: Context, uri: Uri): Result<KnowledgeItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(context, uri)
                    ?: throw IOException("无法读取所选文件名")
                requireAllowedExtension(name)
                val text = readBoundedText(context, uri)
                storeItem(
                    context = context,
                    id = "kb_" + stableHash(uri.toString()),
                    name = name,
                    sourceType = KnowledgeItem.SourceType.FILE,
                    sourceUri = uri.toString(),
                    folderUri = null,
                    lastModified = 0L,
                    rawText = text
                )
            }
        }

    // ===== 文件夹导入与增量同步 =====

    /**
     * SAF 文件夹导入：持久化授权 → 递归枚举 txt/md → 逐文件导入。
     * 返回成功导入的条目列表；单个文件失败跳过不中断（日志记录）。
     */
    suspend fun importFolder(context: Context, treeUri: Uri): Result<List<KnowledgeItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 持久化读权限，供后续增量同步使用（授权失败不阻断本次导入）
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    Log.w(TAG, "持久化文件夹授权失败: ${e.message}")
                }
                val files = listFolderTextFiles(context, treeUri)
                if (files.isEmpty()) throw IOException("文件夹中没有 txt/md 文本文件")
                importFolderMembers(context, treeUri, files)
            }
        }

    /**
     * 文件夹增量同步：对比 lastModified 重导变更/新增文件，清理已删除文件的
     * 条目。返回发生变更的条目数。
     */
    suspend fun syncFolder(context: Context, folderUri: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(folderUri)
                val current = listFolderTextFiles(context, treeUri)
                val existing = KnowledgeRepository.getItems(context)
                    .filter { it.folderUri == folderUri }
                var changed = 0
                // 删除：文件夹中已不存在的成员条目
                val currentUris = current.map { it.documentUri.toString() }.toSet()
                for (item in existing) {
                    if (item.sourceUri !in currentUris) {
                        KnowledgeRepository.removeItem(context, item.id)
                        changed++
                    }
                }
                // 新增/变更：lastModified 不一致的重新导入（同 ID 覆盖）
                val existingByUri = existing.associateBy { it.sourceUri }
                val toImport = current.filter { file ->
                    existingByUri[file.documentUri.toString()]
                        ?.let { it.lastModified != file.lastModified } ?: true
                }
                changed += importFolderMembers(context, treeUri, toImport).size
                changed
            }
        }

    // ===== 自定义文本块 =====

    /** 用户自定义文本块导入。 */
    suspend fun importText(context: Context, title: String, text: String): Result<KnowledgeItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmedTitle = title.trim().ifEmpty { "自定义文本" }
                if (text.isBlank()) throw IOException("文本内容不能为空")
                storeItem(
                    context = context,
                    id = "kb_" + stableHash("text:$trimmedTitle:${System.currentTimeMillis()}"),
                    name = trimmedTitle,
                    sourceType = KnowledgeItem.SourceType.TEXT,
                    sourceUri = null,
                    folderUri = null,
                    lastModified = 0L,
                    rawText = text
                )
            }
        }

    // ===== 内部：共用入库流程 =====

    /** 清洗 → 分块 → 容量校验 → 写入仓库（校验全部通过才落盘）。 */
    private fun storeItem(
        context: Context,
        id: String,
        name: String,
        sourceType: KnowledgeItem.SourceType,
        sourceUri: String?,
        folderUri: String?,
        lastModified: Long,
        rawText: String
    ): KnowledgeItem {
        val sanitized = filter.sanitize(rawText)
        val chunks = TextChunker.chunk(sanitized)
        if (chunks.isEmpty()) throw IOException("「$name」内容为空，无可导入的文本")
        if (chunks.size > KnowledgeRepository.MAX_CHUNKS_PER_ITEM) {
            throw IOException("「$name」内容过大（分块数超过 ${KnowledgeRepository.MAX_CHUNKS_PER_ITEM}）")
        }
        val totalChars = chunks.sumOf { it.length }
        // 覆盖导入（同 ID）时先扣除旧条目占用再校验总量
        val occupied = KnowledgeRepository.getItems(context)
            .filter { it.id != id }
            .sumOf { it.totalChars }
        if (occupied + totalChars > KnowledgeRepository.MAX_TOTAL_CHARS) {
            throw IOException("知识库容量已满（上限约 ${KnowledgeRepository.MAX_TOTAL_CHARS / 1024 / 1024}MB 文本），请先删除部分条目")
        }
        val item = KnowledgeItem(
            id = id,
            name = name,
            sourceType = sourceType,
            sourceUri = sourceUri,
            folderUri = folderUri,
            chunkCount = chunks.size,
            totalChars = totalChars,
            importedAt = System.currentTimeMillis(),
            lastModified = lastModified
        )
        if (!KnowledgeRepository.addItem(context, item, chunks)) {
            throw IOException("「$name」写入知识库失败")
        }
        KnowledgeSearcher.invalidate()
        return item
    }

    /** 批量导入文件夹成员：单文件失败跳过（日志），返回成功列表。 */
    private fun importFolderMembers(
        context: Context,
        treeUri: Uri,
        files: List<FolderFile>
    ): List<KnowledgeItem> {
        if (files.size > MAX_FOLDER_FILES) {
            throw IOException("文件夹内文本文件过多（上限 $MAX_FOLDER_FILES 个）")
        }
        val imported = mutableListOf<KnowledgeItem>()
        for (file in files) {
            try {
                val text = readBoundedText(context, file.documentUri)
                imported += storeItem(
                    context = context,
                    // 以文档 URI 哈希为稳定 ID：增量同步时同文件覆盖更新
                    id = "kb_" + stableHash(file.documentUri.toString()),
                    name = file.name,
                    sourceType = KnowledgeItem.SourceType.FOLDER,
                    sourceUri = file.documentUri.toString(),
                    folderUri = treeUri.toString(),
                    lastModified = file.lastModified,
                    rawText = text
                )
            } catch (e: Exception) {
                Log.w(TAG, "跳过导入失败的文件「${file.name}」: ${e.message}")
            }
        }
        if (imported.isEmpty()) throw IOException("文件夹内没有可成功导入的文件")
        return imported
    }

    // ===== 内部：SAF 文件枚举与读取 =====

    /** 文件夹枚举结果 */
    private data class FolderFile(val documentUri: Uri, val name: String, val lastModified: Long)

    /** 递归枚举文件夹内的 txt/md 文件（深度 ≤ [MAX_FOLDER_DEPTH]）。 */
    private fun listFolderTextFiles(context: Context, treeUri: Uri): List<FolderFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val result = mutableListOf<FolderFile>()
        listChildren(context, treeUri, rootDocId, depth = 0, result = result)
        return result
    }

    private fun listChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        depth: Int,
        result: MutableList<FolderFile>
    ) {
        if (depth > MAX_FOLDER_DEPTH || result.size > MAX_FOLDER_FILES) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mimeType = cursor.getString(2) ?: ""
                val lastModified = cursor.getLong(3)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    listChildren(context, treeUri, docId, depth + 1, result)
                } else if (hasAllowedExtension(name)) {
                    result += FolderFile(
                        documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        name = name,
                        lastModified = lastModified
                    )
                }
            }
        }
    }

    /** 读取 URI 内容为 UTF-8 文本，边读边卡 [MAX_FILE_BYTES] 上限。 */
    private fun readBoundedText(context: Context, uri: Uri): String {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取文件内容")
        input.use { stream ->
            val buffer = StringBuilder()
            val chunk = ByteArray(8192)
            var total = 0L
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_FILE_BYTES) {
                    throw IOException("文件过大（上限 ${MAX_FILE_BYTES / 1024 / 1024}MB）")
                }
                bytes.write(chunk, 0, read)
            }
            buffer.append(bytes.toString(Charsets.UTF_8.name()))
            return buffer.toString()
        }
    }

    /** 查询 SAF 文档展示名。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun requireAllowedExtension(name: String) {
        if (!hasAllowedExtension(name)) {
            throw IOException("仅支持导入 txt / md 文本文件")
        }
    }

    private fun hasAllowedExtension(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in ALLOWED_EXTENSIONS

    /** 稳定短哈希（MD5 前 16 位十六进制），用作条目 ID 的确定性部分。 */
    private fun stableHash(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
