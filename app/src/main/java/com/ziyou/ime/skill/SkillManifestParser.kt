package com.ziyou.ime.skill

import com.ziyou.ime.core.skill.SkillManifest
import com.ziyou.ime.core.skill.SkillManifestValidator
import com.ziyou.ime.core.skill.SkillPanelMode
import com.ziyou.ime.core.skill.SkillPermission
import org.json.JSONObject

/**
 * manifest.json 解析器（app 层，依赖平台 org.json）。
 *
 * 仅做 JSON → [SkillManifest] 的结构转换，字段合法性统一交给
 * [SkillManifestValidator]（core-logic 纯逻辑，可单测）；解析或校验失败抛
 * [IllegalArgumentException]，由调用方决定跳过该技能或向用户展示原因。
 */
object SkillManifestParser {

    /**
     * 解析并校验 manifest JSON 文本。
     * @throws IllegalArgumentException JSON 结构错误 / 字段校验失败
     */
    fun parse(json: String): SkillManifest {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("manifest.json 不是合法 JSON: ${e.message}")
        }

        val permissions = mutableSetOf<SkillPermission>()
        obj.optJSONArray("permissions")?.let { array ->
            for (i in 0 until array.length()) {
                val id = array.optString(i)
                val permission = SkillPermission.fromId(id)
                    ?: throw IllegalArgumentException("未知权限: $id")
                permissions += permission
            }
        }

        val domains = mutableListOf<String>()
        obj.optJSONArray("network_domains")?.let { array ->
            for (i in 0 until array.length()) {
                domains += array.optString(i)
            }
        }

        val panelModeId = obj.optString("panel_mode", SkillPanelMode.EMBED.id)
        val panelMode = SkillPanelMode.fromId(panelModeId)
            ?: throw IllegalArgumentException("未知 panel_mode: $panelModeId")

        val manifest = SkillManifest(
            manifestVersion = obj.optInt("manifest_version", 0),
            id = obj.optString("id"),
            name = obj.optString("name"),
            version = obj.optString("version"),
            minHostApi = obj.optInt("min_host_api", 1),
            author = obj.optString("author").takeIf { it.isNotEmpty() },
            description = obj.optString("description").takeIf { it.isNotEmpty() },
            iconText = obj.optString("icon_text").takeIf { it.isNotEmpty() },
            entry = obj.optString("entry"),
            panelMode = panelMode,
            permissions = permissions,
            networkDomains = domains,
            needsInput = obj.optBoolean("needs_input", false)
        )

        val errors = SkillManifestValidator.validate(manifest)
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException("manifest 校验失败: ${errors.joinToString("; ")}")
        }
        return manifest
    }
}
