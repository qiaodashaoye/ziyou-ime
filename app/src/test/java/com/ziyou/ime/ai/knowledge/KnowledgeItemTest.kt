package com.ziyou.ime.ai.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/** [KnowledgeItem] JSON 序列化单元测试：toJson/fromJson 往返与向前兼容。 */
class KnowledgeItemTest {

    @Test
    fun `序列化往返保持全部字段`() {
        val item = KnowledgeItem(
            id = "kb_abc123",
            name = "测试文档.md",
            sourceType = KnowledgeItem.SourceType.FOLDER,
            sourceUri = "content://docs/document/1",
            folderUri = "content://docs/tree/root",
            chunkCount = 12,
            totalChars = 4800,
            importedAt = 1700000000000L,
            lastModified = 1690000000000L
        )
        val restored = KnowledgeItem.fromJson(item.toJson())
        assertEquals(item, restored)
    }

    @Test
    fun `TEXT类型的null来源字段往返保持null`() {
        val item = KnowledgeItem(
            id = "kb_text1",
            name = "自定义文本",
            sourceType = KnowledgeItem.SourceType.TEXT,
            sourceUri = null,
            folderUri = null
        )
        val restored = KnowledgeItem.fromJson(item.toJson())
        assertNull(restored.sourceUri)
        assertNull(restored.folderUri)
        assertEquals(item, restored)
    }

    @Test
    fun `缺失可选字段时按默认值兜底`() {
        // 模拟旧版本仅有 id 的元数据（向前兼容）
        val restored = KnowledgeItem.fromJson(JSONObject().put("id", "kb_old"))
        assertEquals("kb_old", restored.id)
        assertEquals("", restored.name)
        assertEquals(KnowledgeItem.SourceType.TEXT, restored.sourceType)
        assertEquals(0, restored.chunkCount)
        assertEquals(0L, restored.importedAt)
    }

    @Test
    fun `未知sourceType回退为TEXT`() {
        val restored = KnowledgeItem.fromJson(
            JSONObject().put("id", "kb_x").put("sourceType", "FUTURE_TYPE"))
        assertEquals(KnowledgeItem.SourceType.TEXT, restored.sourceType)
    }
}
