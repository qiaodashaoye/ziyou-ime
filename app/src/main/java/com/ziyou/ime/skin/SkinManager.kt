package com.ziyou.ime.skin

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.core.skin.SkinDefaults
import com.ziyou.ime.core.skin.SkinResolver
import com.ziyou.ime.level.LevelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 皮肤管理器（门面，取代旧 ThemeManager 的全部调用点）。
 *
 * 线程模型：
 * - 读热路径 [getCurrentSkin]：volatile 快照命中即返回，O(1) 无 IO；
 *   未命中时同步构建**不含背景图/字体**的轻量快照（内置皮肤纯内存，成本等价旧
 *   ThemeManager.getCurrentTheme），随后台异步补齐资源并经 [SkinChangeListener] 通知重建。
 * - 写路径（[setSkin] / [invalidate]）：IO 线程解析 + 解码 → 主线程通知监听者，
 *   由 Service 层走既有 setInputView(buildInputView) 重建路径套用新皮肤。
 *
 * 解锁沿用等级体系：内置皮肤按展示名查 [LevelEngine] 解锁表（Light/Dark/Material
 * 原解锁等级不变），导入皮肤不在表中 → 默认 Lv.1 解锁。
 */
object SkinManager {
    private const val TAG = "SkinManager"

    /** 皮肤变更监听（快照就绪 / 切换 / 自定义保存 / 深浅色变化后回调，主线程）。 */
    fun interface SkinChangeListener {
        fun onSkinChanged(skin: SkinTheme)
    }

    @Volatile
    private var cached: SkinTheme? = null

    /** 缓存快照对应的 (skinId, isDark, 覆盖修订号)，任一变化即失效 */
    @Volatile
    private var cachedKey: String? = null

    /** 覆盖修订号：自定义保存/重置时自增，废弃旧快照 */
    @Volatile
    private var overrideRevision = 0

    private val listeners = CopyOnWriteArrayList<SkinChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ===== 读路径 =====

    /** 当前皮肤快照（缓存命中 O(1)；未命中同步构建轻量快照，从不返回 null）。 */
    fun getCurrentSkin(context: Context): SkinTheme {
        val appContext = context.applicationContext
        val key = currentKey(appContext)
        cached?.let { if (cachedKey == key) return it }

        // 轻量同步构建（无背景图/字体解码），失败回退内置默认皮肤
        val theme = buildTheme(appContext, includeAssets = false)
        cached = theme
        cachedKey = key
        val needsAssets = theme.resolved.backgroundImage != null || theme.resolved.fontFamily != null
        if (needsAssets) {
            // 后台补齐资源后经监听者触发一次视图重建（与引擎异步部署+就绪重同步同模式）
            rebuildAsync(appContext)
        }
        return theme
    }

    fun getCurrentSkinId(context: Context): String =
        SkinRepository.getCurrentSkinId(context)

    // ===== 写路径 =====

    /**
     * 切换皮肤。校验存在性 + 等级解锁；成功后后台重建快照并通知监听者。
     * @return false = 皮肤不存在或未解锁（未发生任何变更）
     */
    fun setSkin(context: Context, skinId: String): Boolean {
        val appContext = context.applicationContext
        if (SkinRepository.findInstalled(appContext, skinId) == null) {
            Log.w(TAG, "未知的皮肤 id: $skinId")
            return false
        }
        if (!isSkinUnlocked(appContext, skinId)) {
            Log.w(TAG, "皮肤未解锁（等级不足）: $skinId")
            return false
        }
        SkinRepository.setCurrentSkinId(appContext, skinId)
        Log.i(TAG, "皮肤已切换为: $skinId")
        rebuildAsync(appContext)
        return true
    }

    /** 使当前快照失效并后台重建（自定义保存/重置、皮肤重装等场景）。 */
    fun invalidate(context: Context) {
        overrideRevision++
        rebuildAsync(context.applicationContext)
    }

    /**
     * 系统深浅色变化入口（Service onConfigurationChanged 调用）。
     * 仅当变化会影响当前皮肤的解析结果时才重建。
     */
    fun onSystemDarkModeChanged(context: Context) {
        val appContext = context.applicationContext
        if (cachedKey != currentKey(appContext)) {
            rebuildAsync(appContext)
        }
    }

    // ===== 列表 / 解锁 =====

    fun getInstalledSkins(context: Context): List<SkinInfo> =
        SkinRepository.listInstalled(context)

    fun getUnlockedSkinIds(context: Context): List<String> =
        getInstalledSkins(context).filter { isSkinUnlocked(context, it.id) }.map { it.id }

    /** 皮肤解锁判定：按展示名查等级解锁表（表外皮肤默认 Lv.1 解锁）。 */
    fun isSkinUnlocked(context: Context, skinId: String): Boolean {
        val info = SkinRepository.findInstalled(context, skinId) ?: return false
        val level = LevelRepository.load(context).level
        return LevelEngine.isThemeUnlocked(info.name, level)
    }

    // ===== 监听 =====

    fun addListener(listener: SkinChangeListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: SkinChangeListener) {
        listeners.remove(listener)
    }

    // ===== 内部 =====

    /** 缓存键：皮肤 id + 深浅色 + 覆盖修订号，任一变化即快照失效。 */
    private fun currentKey(context: Context): String =
        "${SkinRepository.getCurrentSkinId(context)}|${isEffectiveDark(context)}|$overrideRevision"

    /** 深浅色生效值：用户策略优先，followSystem 时读系统 uiMode。 */
    private fun isEffectiveDark(context: Context): Boolean =
        when (SkinRepository.getDarkModePolicy(context)) {
            SkinRepository.DarkModePolicy.FORCE_LIGHT -> false
            SkinRepository.DarkModePolicy.FORCE_DARK -> true
            SkinRepository.DarkModePolicy.FOLLOW_SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }

    /**
     * 构建皮肤快照。规格损坏时回退内置默认皮肤并复位当前 id（单次降级，不抛出）。
     * @param includeAssets 是否加载背景图/字体（IO + 解码，仅后台线程传 true）
     */
    private fun buildTheme(context: Context, includeAssets: Boolean): SkinTheme {
        val skinId = SkinRepository.getCurrentSkinId(context)
        val spec = try {
            SkinRepository.loadSpec(context, skinId)
        } catch (e: Exception) {
            Log.e(TAG, "皮肤加载失败，回退默认: $skinId, ${e.message}")
            SkinRepository.setCurrentSkinId(context, SkinDefaults.DEFAULT_SKIN_ID)
            SkinDefaults.builtinSpec(SkinDefaults.DEFAULT_SKIN_ID)!!
        }
        val override = SkinRepository.getOverride(context, spec.meta.id)
        val resolved = SkinResolver.resolve(spec, override, isEffectiveDark(context))
        if (!includeAssets) {
            return SkinTheme(resolved)
        }
        val skinDir = SkinRepository.skinDir(context, spec.meta.id)
        return SkinTheme(
            resolved = resolved,
            backgroundBitmap = SkinAssetCache.loadBackground(context, skinDir, resolved),
            typeface = SkinAssetCache.loadTypeface(skinDir, resolved)
        )
    }

    /** IO 线程完整重建快照 → 主线程更新缓存并通知监听者。 */
    private fun rebuildAsync(context: Context) {
        val key = currentKey(context)
        scope.launch {
            try {
                val theme = buildTheme(context, includeAssets = true)
                mainHandler.post {
                    // 构建期间目标皮肤/深浅色/覆盖又变了则丢弃本次结果（后到的重建自会覆盖）
                    val nowKey = currentKey(context)
                    if (nowKey == key) {
                        cached = theme
                        cachedKey = nowKey
                        for (listener in listeners) {
                            listener.onSkinChanged(theme)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "皮肤快照重建失败: ${e.message}", e)
            }
        }
    }
}
