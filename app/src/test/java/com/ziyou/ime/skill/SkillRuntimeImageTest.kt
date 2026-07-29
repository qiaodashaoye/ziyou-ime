package com.ziyou.ime.skill

import android.content.Context
import android.util.Base64
import com.ziyou.ime.core.skill.SkillManifest
import com.ziyou.ime.core.skill.SkillPanelMode
import com.ziyou.ime.core.skill.SkillPermission
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SkillRuntime 图片能力（API v3：image.send / image.saveToGallery）单元测试。
 *
 * 覆盖：权限拒绝、编辑器不支持图片、空/非法 base64、非 PNG 数据、
 * 发送成功（含 data URL 前缀容忍与文件落盘位置）、宿主提交失败、低系统版本存相册拒绝。
 *
 * android.util.Base64 经 mockk 静态桥接到 java.util.Base64（JVM 桩默认返回 null）；
 * 主调度器替换为 Unconfined，配合 CountDownLatch 等待 IO 协程回调。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillRuntimeImageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var host: FakeHost

    /** 最小 PNG 数据：8 字节文件头魔数 + 少量占位内容 */
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
    )
    private val pngBase64: String = java.util.Base64.getEncoder().encodeToString(pngBytes)

    private class FakeHost : SkillRuntime.Host {
        var acceptsImage = true
        var commitResult = true
        var committedFile: File? = null
        var committedDescription: String? = null

        override fun commitText(text: String) {}
        override fun closePanel() {}
        override fun setPanelTitle(title: String) {}
        override fun editorPackageName(): String? = null
        override fun editorInputType(): String = "text"
        override fun performHaptic() {}
        override fun requestInputRouting(active: Boolean) {}
        override fun setImeExpanded(expanded: Boolean) {}
        override fun setPanelHeightRatio(ratio: Float) {}
        override fun editorAcceptsImage(): Boolean = acceptsImage

        override fun commitImage(file: File, description: String): Boolean {
            committedFile = file
            committedDescription = description
            return commitResult
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // JVM 桩的 android.util.Base64 返回默认值 null，桥接到 java.util.Base64
        //（非法输入抛 IllegalArgumentException，与 Android 真机行为一致）
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        context = mockk(relaxed = true)
        every { context.cacheDir } returns tempFolder.root
        host = FakeHost()
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun runtime(vararg permissions: SkillPermission): SkillRuntime {
        val manifest = SkillManifest(
            manifestVersion = 1,
            id = "com.test.image",
            name = "图片测试",
            version = "1.0",
            minHostApi = 3,
            author = null,
            description = null,
            iconText = null,
            entry = "index.html",
            panelMode = SkillPanelMode.EMBED,
            permissions = permissions.toSet(),
            networkDomains = emptyList(),
            needsInput = false
        )
        val info = SkillInfo(manifest, builtin = true, assetDir = "skills/test", installDir = null)
        return SkillRuntime(context, info, host)
    }

    /** params 用 mockk 替身（JVM 桩的 org.json 方法返回默认值，不可直接使用） */
    private fun paramsWithData(data: String?): JSONObject {
        val params = mockk<JSONObject>(relaxed = true)
        every { params.optString("data") } returns (data ?: "")
        return params
    }

    private fun call(runtime: SkillRuntime, method: String, params: JSONObject): Result<String?> {
        var result: Result<String?>? = null
        val latch = CountDownLatch(1)
        runtime.handle(method, params) {
            result = it
            latch.countDown()
        }
        assertTrue("回调未在超时内交付", latch.await(5, TimeUnit.SECONDS))
        return result!!
    }

    private fun assertRejectedWith(result: Result<String?>, expectedMessage: String) {
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("应为 SkillApiException，实际 $error", error is SkillApiException)
        assertEquals(expectedMessage, error!!.message)
    }

    // ===== 权限与前置条件（主线程同步失败路径）=====

    @Test
    fun `未声明image权限时send被拒绝`() {
        val result = call(runtime(), "image.send", paramsWithData(pngBase64))
        assertRejectedWith(result, "权限拒绝：manifest 未声明 image")
    }

    @Test
    fun `未声明image权限时saveToGallery被拒绝`() {
        val result = call(runtime(), "image.saveToGallery", paramsWithData(pngBase64))
        assertRejectedWith(result, "权限拒绝：manifest 未声明 image")
    }

    @Test
    fun `编辑器不接受图片时send被拒绝`() {
        host.acceptsImage = false
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData(pngBase64))
        assertRejectedWith(result, "当前输入框不支持接收图片")
    }

    @Test
    fun `空数据被拒绝`() {
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData(""))
        assertRejectedWith(result, "图片数据不能为空")
    }

    @Test
    fun `未知image子方法被拒绝`() {
        val result = call(runtime(SkillPermission.IMAGE), "image.rotate", paramsWithData(pngBase64))
        assertRejectedWith(result, "未知方法: image.rotate")
    }

    @Test
    fun `低系统版本saveToGallery被拒绝`() {
        // JVM 单测环境 Build.VERSION.SDK_INT == 0（< Q）
        val result = call(
            runtime(SkillPermission.IMAGE), "image.saveToGallery", paramsWithData(pngBase64))
        assertRejectedWith(result, "保存到相册需要 Android 10 及以上系统")
    }

    // ===== 解码与校验（IO 协程路径）=====

    @Test
    fun `非法base64被拒绝`() {
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData("!!!not-base64!!!"))
        assertRejectedWith(result, "图片数据无效")
    }

    @Test
    fun `非PNG数据被拒绝`() {
        val jpegLike = java.util.Base64.getEncoder()
            .encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 1, 2, 3, 4, 5))
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData(jpegLike))
        assertRejectedWith(result, "仅支持 PNG 图片")
        assertNull(host.committedFile)
    }

    // ===== 发送成功与宿主失败 =====

    @Test
    fun `send成功时文件落盘共享缓存目录并经宿主提交`() {
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData(pngBase64))
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        val file = host.committedFile
        assertNotNull(file)
        // 位于 FileProvider 暴露的 cache/ime_images/，文件名带技能 id 前缀
        assertEquals("ime_images", file!!.parentFile?.name)
        assertTrue(file.name.startsWith("skill_com.test.image_"))
        assertTrue(pngBytes.contentEquals(file.readBytes()))
        assertEquals("图片测试", host.committedDescription)
    }

    @Test
    fun `send容忍dataURL前缀`() {
        val result = call(
            runtime(SkillPermission.IMAGE), "image.send",
            paramsWithData("data:image/png;base64,$pngBase64"))
        assertTrue(result.isSuccess)
        assertNotNull(host.committedFile)
    }

    @Test
    fun `send重复调用保留宽限窗口内的历史文件`() {
        val runtime = runtime(SkillPermission.IMAGE)
        call(runtime, "image.send", paramsWithData(pngBase64))
        val first = host.committedFile!!
        call(runtime, "image.send", paramsWithData(pngBase64))
        val second = host.committedFile!!
        assertTrue(second.exists())
        // 前一张图可能尚未被对端应用异步读走（commitContent 异步拉取），
        // 过期清理策略下宽限窗口内的历史文件不可误删
        assertTrue(first.exists())
    }

    @Test
    fun `宿主提交失败时reject明确错误`() {
        host.commitResult = false
        val result = call(runtime(SkillPermission.IMAGE), "image.send", paramsWithData(pngBase64))
        assertRejectedWith(result, "图片发送失败，当前输入框可能不支持")
    }
}
