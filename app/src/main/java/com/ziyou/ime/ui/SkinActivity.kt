package com.ziyou.ime.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ziyou.ime.core.level.LevelEngine
import com.ziyou.ime.core.skin.SkinColor
import com.ziyou.ime.core.skin.SkinColorScheme
import com.ziyou.ime.core.skin.SkinDimens
import com.ziyou.ime.core.skin.SkinEffects
import com.ziyou.ime.core.skin.SkinKeyStyle
import com.ziyou.ime.core.skin.SkinLayer
import com.ziyou.ime.core.skin.SkinShadowSpec
import com.ziyou.ime.core.skin.SkinSpecValidator
import com.ziyou.ime.core.skin.SkinTypography
import com.ziyou.ime.level.LevelRepository
import com.ziyou.ime.skin.SkinAssetCache
import com.ziyou.ime.skin.SkinCustomizer
import com.ziyou.ime.skin.SkinInfo
import com.ziyou.ime.skin.SkinManager
import com.ziyou.ime.skin.SkinPackLoader
import com.ziyou.ime.skin.SkinPreviewRenderer
import com.ziyou.ime.skin.SkinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 皮肤管理页：预览网格 + 应用 / 导入 / 卸载 / 自定义编辑器。
 *
 * 遵循项目纯代码布局规范（无 XML）：ScrollView + LinearLayout + GridLayout 组装。
 * - 网格：每皮肤一张预览图（包内 preview.png 优先，缺失时 [SkinPreviewRenderer] 现渲）
 *   + 名称 + 解锁等级角标；当前皮肤高亮描边；点击应用（未解锁提示等级）；
 *   长按导入皮肤可卸载。
 * - 导入：SAF 选择 `.zyskin` 包 → [SkinPackLoader.install]，校验错误明细直接展示。
 * - 自定义：对当前皮肤叠加稀疏覆盖（滑杆/开关/取色/背景图），顶部实时预览，
 *   保存 / 恢复默认经 [SkinCustomizer]，键盘侧由 SkinManager 监听自动换肤。
 */
class SkinActivity : AppCompatActivity() {

    private lateinit var gridLayout: GridLayout

    /** SAF 选包（.zyskin 无注册 mime，放开类型由安装器校验内容） */
    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importSkin(it) } }

    /** 自定义编辑器的背景图选择回调（编辑器打开期间有效） */
    private var backgroundPickCallback: ((Uri) -> Unit)? = null

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { backgroundPickCallback?.invoke(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "键盘皮肤"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        // 顶部操作行：导入 + 自定义当前皮肤
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(Button(this).apply {
            text = "导入皮肤包"
            setOnClickListener { importPicker.launch(arrayOf("*/*")) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(Button(this).apply {
            text = "自定义当前皮肤"
            setOnClickListener { showCustomizerDialog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actionRow)

        root.addView(TextView(this).apply {
            text = "点击应用皮肤，长按卸载导入的皮肤"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })

        gridLayout = GridLayout(this).apply { columnCount = 2 }
        root.addView(gridLayout)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshGrid()
    }

    override fun onDestroy() {
        SkinAssetCache.trimPreviews()
        super.onDestroy()
    }

    // ===== 皮肤网格 =====

    private fun refreshGrid() {
        gridLayout.removeAllViews()
        val skins = SkinManager.getInstalledSkins(this)
        val currentId = SkinManager.getCurrentSkinId(this)
        val level = LevelRepository.load(this).level
        val cellWidth = (resources.displayMetrics.widthPixels - dp(12) * 2 - dp(8) * 2) / 2

        for (info in skins) {
            gridLayout.addView(
                createSkinCard(info, info.id == currentId, level, cellWidth),
                GridLayout.LayoutParams().apply {
                    width = cellWidth
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createSkinCard(
        info: SkinInfo,
        isCurrent: Boolean,
        level: Int,
        cellWidth: Int
    ): LinearLayout {
        val unlocked = SkinManager.isSkinUnlocked(this, info.id)
        val requiredLevel = LevelEngine.themeUnlockLevel(info.name)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), if (isCurrent) 0xFF1976D2.toInt() else 0xFFDDDDDD.toInt())
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // 预览图：包内 preview.png 优先，缺失现渲（后台线程，占位后回填）
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        val previewHeight = cellWidth * 2 / 3
        card.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))
        loadPreviewInto(preview, info, cellWidth - dp(16), previewHeight)

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        labelRow.addView(TextView(this).apply {
            text = if (isCurrent) "${info.name}（当前）" else info.name
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!unlocked) {
            labelRow.addView(TextView(this).apply {
                text = "Lv.$requiredLevel 解锁"
                textSize = 11f
                setTextColor(0xFFE65100.toInt())
            })
        }
        card.addView(labelRow)

        card.addView(TextView(this).apply {
            text = if (info.isBuiltin) "内置皮肤" else "v${info.version}" +
                (info.author?.let { " · $it" } ?: "")
            textSize = 11f
            setTextColor(Color.GRAY)
        })

        card.setOnClickListener { applySkin(info, unlocked, requiredLevel) }
        if (!info.isBuiltin) {
            card.setOnLongClickListener {
                confirmUninstall(info)
                true
            }
        }
        return card
    }

    private fun loadPreviewInto(view: ImageView, info: SkinInfo, width: Int, height: Int) {
        lifecycleScope.launch {
            val bitmap: Bitmap? = withContext(Dispatchers.IO) {
                try {
                    SkinAssetCache.loadPreview(info)
                        ?: SkinPreviewRenderer.render(
                            this@SkinActivity,
                            SkinCustomizer.previewWith(
                                this@SkinActivity, info.id,
                                SkinCustomizer.getOverride(this@SkinActivity, info.id)
                            ),
                            width.coerceAtLeast(1), height.coerceAtLeast(1)
                        )
                } catch (_: Exception) {
                    null
                }
            }
            bitmap?.let { view.setImageBitmap(it) }
        }
    }

    private fun applySkin(info: SkinInfo, unlocked: Boolean, requiredLevel: Int) {
        if (!unlocked) {
            Toast.makeText(this, "该皮肤需达到 Lv.$requiredLevel 解锁", Toast.LENGTH_SHORT).show()
            return
        }
        if (SkinManager.setSkin(this, info.id)) {
            Toast.makeText(this, "已应用皮肤：${info.name}", Toast.LENGTH_SHORT).show()
            refreshGrid()
        } else {
            Toast.makeText(this, "应用皮肤失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 导入 / 卸载 =====

    private fun importSkin(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = contentResolver.openInputStream(uri)
                        ?: return@withContext SkinPackLoader.Result.Failed("无法读取所选文件")
                    SkinPackLoader.install(this@SkinActivity, stream)
                } catch (e: Exception) {
                    SkinPackLoader.Result.Failed(e.message ?: "未知错误")
                }
            }
            when (result) {
                is SkinPackLoader.Result.Success -> {
                    Toast.makeText(this@SkinActivity,
                        (if (result.upgraded) "皮肤已更新：" else "皮肤已导入：") + result.info.name,
                        Toast.LENGTH_SHORT).show()
                    refreshGrid()
                }
                is SkinPackLoader.Result.Invalid -> showErrorDialog(
                    "皮肤包校验失败", result.errors.joinToString("\n"))
                is SkinPackLoader.Result.Failed -> showErrorDialog("导入失败", result.cause)
            }
        }
    }

    private fun confirmUninstall(info: SkinInfo) {
        AlertDialog.Builder(this)
            .setTitle("卸载皮肤")
            .setMessage("确定卸载「${info.name}」？其自定义设置将一并清除。")
            .setPositiveButton("卸载") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        SkinPackLoader.uninstall(this@SkinActivity, info.id)
                    }
                    Toast.makeText(this@SkinActivity,
                        if (ok) "已卸载：${info.name}" else "卸载失败", Toast.LENGTH_SHORT).show()
                    refreshGrid()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 自定义编辑器 =====

    /** 编辑器可调色项：标签 → 从覆盖层读 / 写入覆盖层的访问器。 */
    private data class ColorItem(
        val label: String,
        val get: (SkinColorScheme) -> Int?,
        val set: (SkinColorScheme, Int?) -> SkinColorScheme
    )

    private val colorItems = listOf(
        ColorItem("键盘背景", { it.keyboardBackground },
            { s, c -> s.copy(keyboardBackground = c) }),
        ColorItem("按键颜色", { it.keyBackground }, { s, c -> s.copy(keyBackground = c) }),
        ColorItem("按键文字", { it.keyTextColor }, { s, c -> s.copy(keyTextColor = c) }),
        ColorItem("候选背景", { it.candidateBackground },
            { s, c -> s.copy(candidateBackground = c) }),
        ColorItem("强调色", { it.candidateHighlightColor },
            { s, c -> s.copy(candidateHighlightColor = c) }),
        ColorItem("边框颜色", { it.borderColor }, { s, c -> s.copy(borderColor = c) })
    )

    /** 取色器色板（Material 常用色 + 黑白灰） */
    private val palette = listOf(
        "#FFFFFF", "#F5F5F5", "#E0E0E0", "#9E9E9E", "#616161", "#424242", "#212121", "#000000",
        "#EF5350", "#EC407A", "#AB47BC", "#5C6BC0", "#42A5F5", "#26A69A", "#66BB6A", "#FFCA28",
        "#FF7043", "#8D6E63", "#1976D2", "#0D47A1", "#004D40", "#33691E", "#E65100", "#880E4F"
    ).map { SkinColor.parse(it) }

    @SuppressLint("SetTextI18n")
    private fun showCustomizerDialog() {
        val skinId = SkinManager.getCurrentSkinId(this)
        // 工作副本：从已保存覆盖出发，编辑器内所有控件读写该副本
        var layer = SkinCustomizer.getOverride(this, skinId)
        // 当前生效值（覆盖 + 规格 + 默认合并后），用于滑杆初值
        var effective = SkinCustomizer.previewWith(this, skinId, layer)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        // 实时预览
        val previewView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        val previewWidth = resources.displayMetrics.widthPixels - dp(64)
        val previewHeight = previewWidth * 3 / 5
        content.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))

        fun renderPreview(includeAssets: Boolean = true) {
            effective = SkinCustomizer.previewWith(this, skinId, layer, includeAssets)
            previewView.setImageBitmap(
                SkinPreviewRenderer.render(this, effective, previewWidth, previewHeight))
        }

        // —— 滑杆区 ——
        fun addSlider(
            label: String,
            range: ClosedFloatingPointRange<Float>,
            initial: Float,
            format: (Float) -> String = { "%.0f".format(it) },
            onChange: (Float) -> Unit
        ) {
            val labelView = TextView(this).apply {
                text = "$label：${format(initial)}"
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            }
            content.addView(labelView)
            content.addView(SeekBar(this).apply {
                max = 100
                progress = (((initial - range.start) / (range.endInclusive - range.start)) * 100)
                    .toInt().coerceIn(0, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val value = range.start + (range.endInclusive - range.start) * p / 100f
                        labelView.text = "$label：${format(value)}"
                        onChange(value)
                        // 拖动中走纯样式合成（零 IO），避免高频回调阻塞主线程
                        renderPreview(includeAssets = false)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) {}

                    // 松手后补齐背景图/字体完整预览
                    override fun onStopTrackingTouch(bar: SeekBar?) = renderPreview()
                })
            })
        }

        fun dimens() = layer.dimens ?: SkinDimens()
        fun typography() = layer.typography ?: SkinTypography()
        fun effects() = layer.effects ?: SkinEffects()

        addSlider("按键圆角 (dp)", SkinSpecValidator.CORNER_RADIUS_RANGE,
            effective.keyCornerRadiusDp) {
            layer = layer.copy(dimens = dimens().copy(keyCornerRadiusDp = it))
        }
        addSlider("按键间距 (dp)", SkinSpecValidator.KEY_GAP_RANGE, effective.keyGapDp) {
            layer = layer.copy(dimens = dimens().copy(keyGapDp = it))
        }
        addSlider("键高倍率", SkinSpecValidator.KEY_HEIGHT_SCALE_RANGE,
            effective.keyHeightScale, { "%.2f".format(it) }) {
            layer = layer.copy(dimens = dimens().copy(keyHeightScale = it))
        }
        addSlider("按键字号 (sp)", SkinSpecValidator.TEXT_SIZE_RANGE, effective.keyTextSizeSp) {
            layer = layer.copy(typography = typography().copy(keyTextSizeSp = it))
        }
        addSlider("候选字号 (sp)", SkinSpecValidator.TEXT_SIZE_RANGE,
            effective.candidateTextSizeSp) {
            layer = layer.copy(typography = typography().copy(candidateTextSizeSp = it))
        }
        addSlider("键面不透明度", SkinSpecValidator.BACKGROUND_ALPHA_RANGE,
            effective.backgroundAlpha, { "%.0f%%".format(it * 100) }) {
            layer = layer.copy(effects = effects().copy(backgroundAlpha = it))
        }

        // —— 风格 / 开关区 ——
        val styleButton = Button(this).apply {
            text = "按键风格：${styleLabel(effective.keyStyle)}"
            setOnClickListener {
                val next = SkinKeyStyle.entries[
                    (SkinKeyStyle.entries.indexOf(currentStyle(layer, effective)) + 1) %
                        SkinKeyStyle.entries.size]
                layer = layer.copy(effects = effects().copy(keyStyle = next))
                text = "按键风格：${styleLabel(next)}"
                renderPreview()
            }
        }
        content.addView(styleButton)

        val shadowCheck = CheckBox(this).apply {
            text = "按键阴影"
            isChecked = effective.keyShadow != null
            setOnCheckedChangeListener { _, checked ->
                layer = layer.copy(effects = effects().copy(
                    keyShadow = SkinShadowSpec(enabled = checked)))
                renderPreview()
            }
        }
        content.addView(shadowCheck)

        val boldCheck = CheckBox(this).apply {
            text = "按键文字加粗"
            isChecked = effective.keyTextBold
            setOnCheckedChangeListener { _, checked ->
                layer = layer.copy(typography = typography().copy(keyTextBold = checked))
                renderPreview()
            }
        }
        content.addView(boldCheck)

        // —— 背景图区 ——
        // 自选图片拷入皮肤目录后以相对路径写入覆盖层；
        // 「清除」写入空 background 节点 = 强制无背景（可盖掉皮肤包自带背景图）
        val bgRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bgRow.addView(Button(this).apply {
            text = "选择背景图"
            setOnClickListener {
                backgroundPickCallback = { uri ->
                    lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val dir = SkinRepository.skinDir(this@SkinActivity, skinId)
                                    .apply { mkdirs() }
                                contentResolver.openInputStream(uri)?.use { input ->
                                    dir.resolve(CUSTOM_BG_FILE).outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                } != null
                            } catch (_: Exception) {
                                false
                            }
                        }
                        if (ok) {
                            SkinAssetCache.evict(skinId)
                            layer = layer.copy(background =
                                com.ziyou.ime.core.skin.SkinBackgroundSpec(image = CUSTOM_BG_FILE))
                            renderPreview()
                        } else {
                            Toast.makeText(this@SkinActivity, "背景图读取失败",
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                backgroundPicker.launch("image/*")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bgRow.addView(Button(this).apply {
            text = "清除背景图"
            setOnClickListener {
                layer = layer.copy(background = com.ziyou.ime.core.skin.SkinBackgroundSpec())
                renderPreview()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(bgRow)

        // —— 颜色区 ——
        content.addView(TextView(this).apply {
            text = "配色"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })
        for (item in colorItems) {
            val isDarkVariant = effective.isDark
            fun scheme(): SkinColorScheme =
                (if (isDarkVariant) layer.colorsDark else layer.colorsLight) ?: SkinColorScheme()

            fun writeScheme(s: SkinColorScheme) {
                layer = if (isDarkVariant) layer.copy(colorsDark = s)
                else layer.copy(colorsLight = s)
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = item.label
                textSize = 13f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val swatch = TextView(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(currentColorOf(item, layer, effective))
                    setStroke(dp(1), 0xFF999999.toInt())
                }
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(36), dp(24)))
            row.setOnClickListener {
                showPaletteDialog(item.label) { picked ->
                    writeScheme(item.set(scheme(), picked))
                    (swatch.background as GradientDrawable)
                        .setColor(picked ?: currentColorOf(item, layer, effective))
                    renderPreview()
                }
            }
            content.addView(row)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("自定义皮肤")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("保存", null)
            .setNeutralButton("恢复默认", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 手动接管按钮点击：保存失败（校验错误）时不关闭对话框
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val errors = SkinCustomizer.saveOverride(this, skinId, layer)
            if (errors.isEmpty()) {
                Toast.makeText(this, "已保存自定义皮肤", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                refreshGrid()
            } else {
                showErrorDialog("保存失败", errors.joinToString("\n"))
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            SkinCustomizer.resetOverride(this, skinId)
            Toast.makeText(this, "已恢复默认外观", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            refreshGrid()
        }
        renderPreview()
    }

    /** 当前编辑态的按键风格（覆盖优先，其次生效值）。 */
    private fun currentStyle(
        layer: SkinLayer,
        effective: com.ziyou.ime.skin.SkinTheme
    ): SkinKeyStyle = layer.effects?.keyStyle ?: effective.keyStyle

    private fun currentColorOf(
        item: ColorItem,
        layer: SkinLayer,
        effective: com.ziyou.ime.skin.SkinTheme
    ): Int {
        val scheme = if (effective.isDark) layer.colorsDark else layer.colorsLight
        scheme?.let { item.get(it) }?.let { return it }
        // 回退到生效值对应字段
        return when (item.label) {
            "键盘背景" -> effective.keyboardBackground
            "按键颜色" -> effective.keyBackground
            "按键文字" -> effective.keyTextColor
            "候选背景" -> effective.candidateBackground
            "强调色" -> effective.candidateHighlightColor
            else -> effective.borderColor
        }
    }

    private fun styleLabel(style: SkinKeyStyle): String = when (style) {
        SkinKeyStyle.FILLED -> "填充"
        SkinKeyStyle.OUTLINE -> "描边"
        SkinKeyStyle.FLAT -> "无边框"
    }

    /** 色板取色对话框（附「使用默认」清除覆盖项）。 */
    private fun showPaletteDialog(title: String, onPick: (Int?) -> Unit) {
        val grid = GridLayout(this).apply {
            columnCount = 8
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        lateinit var dialog: AlertDialog
        for (color in palette) {
            grid.addView(TextView(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(color)
                    setStroke(dp(1), 0xFFBBBBBB.toInt())
                }
                setOnClickListener {
                    onPick(color)
                    dialog.dismiss()
                }
            }, GridLayout.LayoutParams().apply {
                width = dp(32)
                height = dp(32)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("选择$title")
            .setView(grid)
            .setNeutralButton("使用默认") { _, _ -> onPick(null) }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 编辑器自选背景图在皮肤目录内的固定文件名 */
        private const val CUSTOM_BG_FILE = "custom_bg.png"
    }
}
