package com.ziyou.ime.ai.knowledge

import org.json.JSONObject

/**
 * 知识条目数据模型
 *
 * 一个条目对应一次导入的知识来源（单文件 / 文件夹内单文件 / 自定义文本块），
 * 元数据经 [KnowledgeRepository] 以 JSON 持久化，chunk 正文另存文件。
 *
 * @param id          条目唯一 ID（`kb_` 前缀 + 时间戳 + 序号）
 * @param name        展示名称（文件名或自定义文本标题）
 * @param sourceType  来源类型
 * @param sourceUri   来源 URI（FILE 为文档 URI；FOLDER 成员为「treeUri|文档 URI」；TEXT 为 null）
 * @param folderUri   所属文件夹的 treeUri（仅文件夹导入的成员非空，用于增量同步分组）
 * @param chunkCount  分块数
 * @param totalChars  清洗后正文总字符数
 * @param importedAt  导入时间戳（毫秒）
 * @param lastModified 来源文件的 lastModified（文件夹增量同步比对用；TEXT 为 0）
 */
data class KnowledgeItem(
    val id: String,
    val name: String,
    val sourceType: SourceType,
    val sourceUri: String? = null,
    val folderUri: String? = null,
    val chunkCount: Int = 0,
    val totalChars: Int = 0,
    val importedAt: Long = 0L,
    val lastModified: Long = 0L
) {

    /** 知识来源类型 */
    enum class SourceType {
        /** SAF 单文件导入（txt/md） */
        FILE,

        /** SAF 文件夹导入的成员文件 */
        FOLDER,

        /** 用户自定义粘贴文本块 */
        TEXT;

        companion object {
            fun from(value: String): SourceType =
                entries.firstOrNull { it.name == value } ?: TEXT
        }
    }

    /** 序列化为 JSON（与 [fromJson] 互逆）。 */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("sourceType", sourceType.name)
        .put("sourceUri", sourceUri ?: JSONObject.NULL)
        .put("folderUri", folderUri ?: JSONObject.NULL)
        .put("chunkCount", chunkCount)
        .put("totalChars", totalChars)
        .put("importedAt", importedAt)
        .put("lastModified", lastModified)

    companion object {
        /** 从 JSON 反序列化；缺字段按默认值兜底（向前兼容）。 */
        fun fromJson(obj: JSONObject): KnowledgeItem = KnowledgeItem(
            id = obj.getString("id"),
            name = obj.optString("name", ""),
            sourceType = SourceType.from(obj.optString("sourceType", "TEXT")),
            sourceUri = if (obj.isNull("sourceUri")) null else obj.optString("sourceUri"),
            folderUri = if (obj.isNull("folderUri")) null else obj.optString("folderUri"),
            chunkCount = obj.optInt("chunkCount", 0),
            totalChars = obj.optInt("totalChars", 0),
            importedAt = obj.optLong("importedAt", 0L),
            lastModified = obj.optLong("lastModified", 0L)
        )
    }
}
