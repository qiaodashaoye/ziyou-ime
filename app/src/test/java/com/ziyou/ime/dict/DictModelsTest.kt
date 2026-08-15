package com.ziyou.ime.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RemoteDictInfo] 模型单测：产物类型（kind）的默认值与判定语义。 */
class DictModelsTest {

    private fun info(kind: String = RemoteDictInfo.KIND_DICT) = RemoteDictInfo(
        id = "poetry_predict",
        name = "诗词联想库",
        category = "classical",
        description = "",
        version = "1.0.0",
        url = "https://gitee.com/x/y/releases/download/v1/predict_poetry.db",
        size = 1024,
        author = "字由官方",
        kind = kind
    )

    @Test
    fun `kind默认值为dict保证旧catalog向后兼容`() {
        assertEquals(RemoteDictInfo.KIND_DICT, info().kind)
        assertFalse(info().isPredictDb)
    }

    @Test
    fun `predict_db类型判定命中`() {
        assertTrue(info(kind = RemoteDictInfo.KIND_PREDICT_DB).isPredictDb)
    }

    @Test
    fun `未知kind值不视为predict_db`() {
        // 防御：catalog 被篡改注入未知类型时按普通 dict 通道处理（下载仍受
        // 白名单/sha256 约束），不得误走 predict.db 整体替换通道
        assertFalse(info(kind = "unknown").isPredictDb)
    }

    @Test
    fun `分类解析回退默认值`() {
        assertEquals(DictCategory.CLASSICAL, info().dictCategory)
        assertEquals(DictCategory.PROFESSIONAL, info().copy(category = "no_such").dictCategory)
    }
}
