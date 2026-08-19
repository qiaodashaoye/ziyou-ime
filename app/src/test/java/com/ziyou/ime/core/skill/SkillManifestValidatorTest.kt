package com.ziyou.ime.core.skill

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillManifestValidator] manifest 校验单元测试。
 */
class SkillManifestValidatorTest {

    /** 构造一份全字段合法的 manifest，各用例在此基础上做单点破坏。 */
    private fun valid() = SkillManifest(
        manifestVersion = 1,
        id = "com.ziyou.skill.calculator",
        name = "计算器",
        version = "1.0.0",
        minHostApi = 1,
        author = "字由官方",
        description = "四则运算",
        iconText = "🧮",
        entry = "index.html",
        panelMode = SkillPanelMode.EMBED,
        permissions = emptySet(),
        networkDomains = emptyList()
    )

    private fun errorsOf(manifest: SkillManifest) = SkillManifestValidator.validate(manifest)

    @Test
    fun `合法manifest零错误`() {
        assertTrue(errorsOf(valid()).isEmpty())
    }

    @Test
    fun `manifest版本不支持`() {
        assertTrue(errorsOf(valid().copy(manifestVersion = 2)).any { it.contains("manifest_version") })
    }

    @Test
    fun `技能id必须为反向域名风格`() {
        assertTrue(errorsOf(valid().copy(id = "calculator")).isNotEmpty())       // 单段
        assertTrue(errorsOf(valid().copy(id = "Com.Upper.Case")).isNotEmpty())   // 大写
        assertTrue(errorsOf(valid().copy(id = "com..double")).isNotEmpty())      // 空段
        assertTrue(errorsOf(valid().copy(id = "1com.digit")).isNotEmpty())       // 数字开头
        assertTrue(errorsOf(valid().copy(id = "com.user.weather_v2")).isEmpty()) // 下划线合法
    }

    @Test
    fun `名称为空或超长被拒绝`() {
        assertTrue(errorsOf(valid().copy(name = "")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(name = "很".repeat(31))).isNotEmpty())
    }

    @Test
    fun `版本号必须数字点分`() {
        assertTrue(errorsOf(valid().copy(version = "v1.0")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(version = "1.0.0-beta")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(version = "2")).isEmpty())
    }

    @Test
    fun `min_host_api高于宿主版本被拒绝`() {
        val errors = errorsOf(valid().copy(minHostApi = SkillManifestValidator.HOST_API_VERSION + 1))
        assertTrue(errors.any { it.contains("宿主 API") })
    }

    @Test
    fun `入口路径逃逸或非html被拒绝`() {
        assertTrue(errorsOf(valid().copy(entry = "../evil.html")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(entry = "/abs/index.html")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(entry = "script.js")).isNotEmpty())
        assertTrue(errorsOf(valid().copy(entry = "pages/main.html")).isEmpty())
    }

    @Test
    fun `域名白名单需配套network权限`() {
        val noPerm = valid().copy(networkDomains = listOf("api.example.com"))
        assertTrue(errorsOf(noPerm).any { it.contains("network 权限") })

        val withPerm = noPerm.copy(permissions = setOf(SkillPermission.NETWORK))
        assertTrue(errorsOf(withPerm).isEmpty())
    }

    @Test
    fun `非法域名被拒绝`() {
        val base = valid().copy(permissions = setOf(SkillPermission.NETWORK))
        assertTrue(errorsOf(base.copy(networkDomains = listOf("https://api.example.com"))).isNotEmpty()) // 带协议
        assertTrue(errorsOf(base.copy(networkDomains = listOf("*.example.com"))).isNotEmpty())           // 通配符
        assertTrue(errorsOf(base.copy(networkDomains = listOf("example"))).isNotEmpty())                 // 单段
    }

    @Test
    fun `权限id解析`() {
        assertTrue(SkillPermission.fromId("network") == SkillPermission.NETWORK)
        assertTrue(SkillPermission.fromId("storage") == SkillPermission.STORAGE)
        assertTrue(SkillPermission.fromId("root") == null)
        assertTrue(SkillPermission.fromId(null) == null)
    }

    @Test
    fun `面板形态解析`() {
        assertTrue(SkillPanelMode.fromId("embed") == SkillPanelMode.EMBED)
        assertTrue(SkillPanelMode.fromId("card") == SkillPanelMode.CARD)
        assertTrue(SkillPanelMode.fromId("fullscreen") == null)
    }
}
