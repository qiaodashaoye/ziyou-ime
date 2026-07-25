package com.ziyou.ime.skill

import android.content.Context
import android.util.Log
import com.ziyou.ime.core.skill.SkillManifest
import java.io.File
import java.io.InputStream

/**
 * 已就绪技能的描述：manifest + 资源来源。
 *
 * 内置技能资源位于 `assets/skills/<dir>/`，用户安装技能（Phase 2）位于
 * `files/skills/<id>/`；[openResource] 统一两种来源的资源读取入口，
 * 供 WebView 资源拦截按相对路径取文件。
 */
data class SkillInfo(
    val manifest: SkillManifest,
    /** true = 内置技能（assets），false = 用户安装（内部存储） */
    val builtin: Boolean,
    /** 内置技能的 assets 目录（如 skills/calculator），builtin=true 时非空 */
    val assetDir: String?,
    /** 安装技能的根目录，builtin=false 时非空 */
    val installDir: File?
) {
    /**
     * 打开技能包内相对路径对应的资源流。
     * 调用方须先经 ZipEntryValidator 校验 [relativePath]；不存在返回 null。
     */
    fun openResource(context: Context, relativePath: String): InputStream? = try {
        if (builtin) {
            context.assets.open("$assetDir/$relativePath")
        } else {
            val file = File(installDir, relativePath)
            // 双保险：canonicalPath 必须落在安装目录内（Zip Slip 纵深防御）
            if (file.canonicalPath.startsWith(installDir!!.canonicalPath + File.separator)) {
                file.inputStream()
            } else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 技能管理器：枚举内置（assets）与已安装（内部存储）技能。
 *
 * Phase 1 仅内置技能；`files/skills/` 扫描为 Phase 2 安装能力预留，
 * 目录不存在时自然为空列表。manifest 非法的技能记日志跳过，不影响其他技能。
 */
object SkillManager {
    private const val TAG = "SkillManager"

    /** 内置技能的 assets 根目录 */
    private const val ASSETS_ROOT = "skills"

    /** 用户安装技能的内部存储根目录名 */
    private const val INSTALL_ROOT = "skills"

    private const val MANIFEST_FILE = "manifest.json"

    /** 列出全部可用技能（内置在前，按名称稳定排序）。 */
    fun listSkills(context: Context): List<SkillInfo> {
        val skills = mutableListOf<SkillInfo>()
        skills += listBuiltinSkills(context)
        skills += listInstalledSkills(context)
        return skills
    }

    /** 用户安装技能的根目录（Phase 2 安装器写入处）。 */
    fun installRoot(context: Context): File = File(context.filesDir, INSTALL_ROOT)

    private fun listBuiltinSkills(context: Context): List<SkillInfo> {
        val dirs = try {
            context.assets.list(ASSETS_ROOT)?.toList().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "枚举内置技能目录失败: ${e.message}")
            emptyList()
        }
        return dirs.sorted().mapNotNull { dir ->
            val assetDir = "$ASSETS_ROOT/$dir"
            try {
                val json = context.assets.open("$assetDir/$MANIFEST_FILE")
                    .bufferedReader().use { it.readText() }
                val manifest = SkillManifestParser.parse(json)
                SkillInfo(manifest, builtin = true, assetDir = assetDir, installDir = null)
            } catch (e: Exception) {
                Log.w(TAG, "内置技能 $dir 加载失败: ${e.message}")
                null
            }
        }
    }

    private fun listInstalledSkills(context: Context): List<SkillInfo> {
        val root = installRoot(context)
        // 过滤隐藏目录（安装器的 .staging_* 中间目录）
        val dirs = root.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name }.orEmpty()
        return dirs.mapNotNull { dir ->
            try {
                val json = File(dir, MANIFEST_FILE).readText()
                val manifest = SkillManifestParser.parse(json)
                SkillInfo(manifest, builtin = false, assetDir = null, installDir = dir)
            } catch (e: Exception) {
                Log.w(TAG, "已安装技能 ${dir.name} 加载失败: ${e.message}")
                null
            }
        }
    }
}
