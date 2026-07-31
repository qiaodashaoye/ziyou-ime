package com.ziyou.ime.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * 工具栏图标绘制器：以 24×24 逻辑网格定义的矢量 [Path] 图标，
 * 替代原纯文字标签（宿主见 [CandidateToolbarView] 与 [ToolPanelView]）。
 *
 * 设计规格：Material 线性描边风格 —— 统一 1.8 网格单位描边宽、
 * 圆线帽/圆拐角、单色绘制。颜色由调用方逐次传入（取皮肤
 * toolbarTextColor，按下态换候选高亮色），天然随皮肤染色；
 * 绘制经 Canvas 矩阵按目标尺寸整体等比缩放，描边宽度随之缩放，
 * 任意尺寸下视觉权重一致（悬浮模式缩放同样成立）。
 *
 * 全部路径在构造期一次性构建并缓存（每图标一个描边 Path +
 * 可选填充 Path），draw 为纯内存操作、零分配，可安全用于每帧 onDraw
 * （满足输入热路径零 IO / 低开销约束）。
 */
class ToolbarIconDrawer {

    /** 图标目录：动态按钮（对应 [ToolbarItem]）+ 固定收起/Logo 按钮 +
     *  工具面板专属设置项（[ToolPanelCatalog]） */
    enum class Icon { THEME, SCHEMA, DOODLE, SKILL, AI, CLIPBOARD, FLOATING, KEYBOARD, HIDE, SETTINGS, LOGO }

    /** 单个图标的绘制规格：描边主体 + 可选填充细节（如颜料点/键帽） */
    private class Spec(val stroke: Path, val fill: Path? = null)

    companion object {
        /** 逻辑网格边长：全部坐标按 24×24 定义，绘制时缩放到目标尺寸 */
        private const val GRID = 24f

        /** 描边宽度（网格单位），随 Canvas 矩阵等比缩放 */
        private const val STROKE_WIDTH = 1.8f
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val specs: Map<Icon, Spec> = mapOf(
        Icon.THEME to theme(),
        Icon.SCHEMA to schema(),
        Icon.DOODLE to doodle(),
        Icon.SKILL to skill(),
        Icon.AI to ai(),
        Icon.CLIPBOARD to clipboard(),
        Icon.FLOATING to floating(),
        Icon.KEYBOARD to keyboard(),
        Icon.HIDE to hide(),
        Icon.SETTINGS to settings(),
        Icon.LOGO to logo()
    )

    /**
     * 以 ([cx], [cy]) 为中心、[sizePx] 为边长绘制 [icon]，整体染 [color] 色。
     */
    fun draw(canvas: Canvas, icon: Icon, cx: Float, cy: Float, sizePx: Float, color: Int) {
        val spec = specs.getValue(icon)
        strokePaint.color = color
        fillPaint.color = color
        val scale = sizePx / GRID
        canvas.save()
        canvas.translate(cx - sizePx / 2f, cy - sizePx / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(spec.stroke, strokePaint)
        spec.fill?.let { canvas.drawPath(it, fillPaint) }
        canvas.restore()
    }

    // ===== 图标路径定义（坐标均为 24×24 网格） =====

    /** 主题：调色盘（圆弧主体 + 拇指凹口）+ 三颗填充颜料点 */
    private fun theme(): Spec {
        val stroke = Path().apply {
            // 主体圆弧：右侧留缺口（30° → 330°）
            addArc(RectF(4f, 4.5f, 20f, 20.5f), 30f, 300f)
            // 拇指凹口：连接缺口两端、向盘心深凹
            moveTo(18.93f, 8.5f)
            quadTo(13.2f, 12.5f, 18.93f, 16.5f)
        }
        val fill = Path().apply {
            addCircle(8.6f, 9.2f, 1.35f, Path.Direction.CW)
            addCircle(12.8f, 7.6f, 1.35f, Path.Direction.CW)
            addCircle(7.4f, 13.4f, 1.35f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** 方案：环形双箭头（切换语义）环绕简笔「文」（输入方案语义） */
    private fun schema(): Spec {
        val stroke = Path().apply {
            // 上弧（顺时针）+ 末端箭头
            addArc(RectF(3f, 3f, 21f, 21f), 205f, 105f)
            moveTo(16.63f, 1.91f)
            lineTo(17.79f, 5.11f)
            lineTo(14.44f, 4.52f)
            // 下弧（中心对称）+ 末端箭头
            addArc(RectF(3f, 3f, 21f, 21f), 25f, 105f)
            moveTo(7.37f, 22.09f)
            lineTo(6.21f, 18.89f)
            lineTo(9.56f, 19.48f)
            // 简笔「文」：点、横、撇、捺
            moveTo(12f, 6.6f)
            lineTo(12f, 7.8f)
            moveTo(8.2f, 10.2f)
            lineTo(15.8f, 10.2f)
            moveTo(14.2f, 10.2f)
            quadTo(12.6f, 13.8f, 8.4f, 16.6f)
            moveTo(9.8f, 12.2f)
            quadTo(12.4f, 14.4f, 15.6f, 16.6f)
        }
        return Spec(stroke)
    }

    /** 涂鸦：45° 铅笔轮廓 + 笔下波浪线 */
    private fun doodle(): Spec {
        val stroke = Path().apply {
            // 铅笔：笔尖 → 笔身两缘 → 笔尾封口（闭合轮廓）
            moveTo(8.8f, 14.1f)
            lineTo(10.3f, 10f)
            lineTo(17.5f, 2.8f)
            lineTo(20.1f, 5.4f)
            lineTo(12.9f, 12.6f)
            close()
            // 波浪线：铅笔画出的涂鸦痕迹
            moveTo(4.5f, 20f)
            cubicTo(7.5f, 16.5f, 9.5f, 23f, 12.5f, 19.8f)
            cubicTo(14.3f, 17.9f, 15.8f, 21.2f, 18f, 19.3f)
        }
        return Spec(stroke)
    }

    /** 技能：拼图块轮廓（顶部 + 右侧各一外凸圆钮，插件/扩展通用隐喻） */
    private fun skill(): Spec {
        val stroke = Path().apply {
            moveTo(5f, 20f)
            lineTo(5f, 8f)
            lineTo(8.6f, 8f)
            cubicTo(8.6f, 5.2f, 13.4f, 5.2f, 13.4f, 8f)
            lineTo(17f, 8f)
            lineTo(17f, 11.6f)
            cubicTo(19.8f, 11.6f, 19.8f, 16.4f, 17f, 16.4f)
            lineTo(17f, 20f)
            close()
        }
        return Spec(stroke)
    }

    /** AI：四角星光描边主体 + 右上角小星光填充点缀 */
    private fun ai(): Spec {
        val stroke = Path().apply {
            moveTo(11f, 4.5f)
            quadTo(12.7f, 11.3f, 19.5f, 13f)
            quadTo(12.7f, 14.7f, 11f, 21.5f)
            quadTo(9.3f, 14.7f, 2.5f, 13f)
            quadTo(9.3f, 11.3f, 11f, 4.5f)
            close()
        }
        val fill = Path().apply {
            moveTo(19.5f, 3.2f)
            quadTo(20.1f, 5.1f, 22f, 5.7f)
            quadTo(20.1f, 6.3f, 19.5f, 8.2f)
            quadTo(18.9f, 6.3f, 17f, 5.7f)
            quadTo(18.9f, 5.1f, 19.5f, 3.2f)
            close()
        }
        return Spec(stroke, fill)
    }

    /** 粘贴板：板身描边 + 顶部夹子填充 + 两行文本线 */
    private fun clipboard(): Spec {
        val stroke = Path().apply {
            addRoundRect(RectF(5f, 4.5f, 19f, 21.5f), 2f, 2f, Path.Direction.CW)
            moveTo(8.5f, 12f)
            lineTo(15.5f, 12f)
            moveTo(8.5f, 16f)
            lineTo(13f, 16f)
        }
        val fill = Path().apply {
            // 夹子填充：同色覆盖板身顶边穿过的线段，形成压盖层次
            addRoundRect(RectF(8.8f, 2.6f, 15.2f, 6.6f), 1.4f, 1.4f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** 悬浮：小键盘外弹右上箭头（画中画式「脱离停靠」隐喻） */
    private fun floating(): Spec {
        val stroke = Path().apply {
            addRoundRect(RectF(3.5f, 10.5f, 15.5f, 21f), 1.8f, 1.8f, Path.Direction.CW)
            // 空格条
            moveTo(7f, 17.8f)
            lineTo(12f, 17.8f)
            // 外弹箭头：斜杆 + L 形箭头角
            moveTo(15.8f, 8.2f)
            lineTo(20.8f, 3.2f)
            moveTo(17.2f, 3.2f)
            lineTo(20.8f, 3.2f)
            lineTo(20.8f, 6.8f)
        }
        val fill = Path().apply {
            // 键帽
            addRect(5.6f, 13f, 7.2f, 14.6f, Path.Direction.CW)
            addRect(8.7f, 13f, 10.3f, 14.6f, Path.Direction.CW)
            addRect(11.8f, 13f, 13.4f, 14.6f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** 键盘切换：键盘轮廓 + 三行键帽（与收起/悬浮图标同一键盘隐喻，
     *  无附加箭头——点击弹选择面板而非直接动作） */
    private fun keyboard(): Spec {
        val stroke = Path().apply {
            addRoundRect(RectF(2.5f, 6f, 21.5f, 18f), 1.8f, 1.8f, Path.Direction.CW)
            // 空格条
            moveTo(8f, 15f)
            lineTo(16f, 15f)
        }
        val fill = Path().apply {
            // 上行键帽
            addRect(5f, 8.2f, 6.6f, 9.8f, Path.Direction.CW)
            addRect(8.2f, 8.2f, 9.8f, 9.8f, Path.Direction.CW)
            addRect(11.4f, 8.2f, 13f, 9.8f, Path.Direction.CW)
            addRect(14.6f, 8.2f, 16.2f, 9.8f, Path.Direction.CW)
            addRect(17.8f, 8.2f, 19.4f, 9.8f, Path.Direction.CW)
            // 中行键帽（错位半格，真实键盘观感）
            addRect(6.6f, 11.2f, 8.2f, 12.8f, Path.Direction.CW)
            addRect(9.8f, 11.2f, 11.4f, 12.8f, Path.Direction.CW)
            addRect(13f, 11.2f, 14.6f, 12.8f, Path.Direction.CW)
            addRect(16.2f, 11.2f, 17.8f, 12.8f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** 收起：键盘 + 下方收起箭头（Android IME 标准符号） */
    private fun hide(): Spec {
        val stroke = Path().apply {
            addRoundRect(RectF(3.5f, 3.5f, 20.5f, 13.5f), 1.8f, 1.8f, Path.Direction.CW)
            // 空格条
            moveTo(9f, 10.9f)
            lineTo(15f, 10.9f)
            // 下收 V 形箭头
            moveTo(8.5f, 16.8f)
            lineTo(12f, 20.2f)
            lineTo(15.5f, 16.8f)
        }
        val fill = Path().apply {
            // 键帽
            addRect(6.2f, 5.8f, 7.8f, 7.4f, Path.Direction.CW)
            addRect(9.4f, 5.8f, 11f, 7.4f, Path.Direction.CW)
            addRect(12.6f, 5.8f, 14.2f, 7.4f, Path.Direction.CW)
            addRect(15.8f, 5.8f, 17.4f, 7.4f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** 设置：三根调节滑杆（Material tune 风格，旋钮为填充圆点） */
    private fun settings(): Spec {
        val stroke = Path().apply {
            moveTo(4f, 6.5f)
            lineTo(20f, 6.5f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17.5f)
            lineTo(20f, 17.5f)
        }
        val fill = Path().apply {
            addCircle(15f, 6.5f, 2.4f, Path.Direction.CW)
            addCircle(8f, 12f, 2.4f, Path.Direction.CW)
            addCircle(13f, 17.5f, 2.4f, Path.Direction.CW)
        }
        return Spec(stroke, fill)
    }

    /** Logo：极简笔画「字」字标（字由品牌识别核心，点 + 宝盖 + 简化子），
     *  去装饰纯线条，与其余图标同描边规格 */
    private fun logo(): Spec {
        val stroke = Path().apply {
            // 点：宝盖上方短竖（圆帽收尾成点）
            moveTo(12f, 3.2f)
            lineTo(12f, 4.8f)
            // 宝盖头：横梁 + 两端短垂
            moveTo(4.8f, 9.6f)
            lineTo(4.8f, 7.2f)
            lineTo(19.2f, 7.2f)
            lineTo(19.2f, 9.6f)
            // 子 · 横撇：横梁折向左下收至竖钩顶端
            moveTo(8.6f, 12f)
            lineTo(15.4f, 12f)
            quadTo(14.2f, 14.4f, 12f, 15.6f)
            // 子 · 竖钩：中竖下行，末端左勾
            moveTo(12f, 15.6f)
            lineTo(12f, 20.4f)
            quadTo(12f, 21.4f, 10.2f, 21f)
            // 子 · 横：长横穿竖
            moveTo(5.4f, 17.6f)
            lineTo(18.6f, 17.6f)
        }
        return Spec(stroke)
    }
}
