#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「云朵奶油」皮肤包资源生成脚本。

产出：
- images/bg.png  键盘整体背景（淡紫灰渐变 + 奶油/樱粉柔光斑，对应参考图的雾面质感）
- preview.png    皮肤管理页预览图（迷你键盘示意，与 skin.json 配色一致）

依赖 Pillow。重新调色后执行 `python3 build_assets.py` 即可覆盖生成，
随后用 pack.sh 重新打包 .zyskin。
"""

import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# ===== 与 skin.json 保持一致的配色（改色时两处同步）=====
BG_TOP = (231, 232, 244)          # #E7E8F4 面板上缘
BG_BOTTOM = (222, 224, 238)       # #DEE0EE 面板下缘
KEY_COLOR = (255, 255, 255)       # #FFFFFF 键面
KEY_TEXT = (38, 38, 43)           # #26262B 键面文字
FUNC_KEY_COLOR = (217, 220, 239)  # #D9DCEF 功能键
ACCENT = (232, 135, 156)          # #E8879C 强调色（樱粉）
CANDIDATE_BG = (243, 244, 250)    # #F3F4FA 候选区
SHADOW = (39, 43, 69)             # #272B45 阴影基色

# 柔光斑：(相对x, 相对y, 相对半径, RGB, 透明度)
GLOW_BLOBS = [
    (0.16, 0.30, 0.26, (255, 246, 232), 70),   # 奶油暖光（左上）
    (0.52, 0.18, 0.30, (255, 252, 245), 60),   # 中部提亮
    (0.86, 0.42, 0.28, (255, 232, 238), 66),   # 樱粉柔光（右侧）
    (0.30, 0.82, 0.30, (226, 230, 250), 72),   # 淡紫（左下）
    (0.72, 0.88, 0.26, (240, 236, 250), 60),   # 淡紫（右下）
]


def vertical_gradient(size, top, bottom):
    """自上而下的线性渐变底图。"""
    width, height = size
    image = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        ratio = y / max(height - 1, 1)
        color = tuple(
            int(top[i] + (bottom[i] - top[i]) * ratio) for i in range(3)
        )
        draw.line([(0, y), (width, y)], fill=color)
    return image


def add_glow(image, blobs, blur_ratio=0.12):
    """叠加高斯模糊柔光斑，营造参考图的雾面质感。"""
    width, height = image.size
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    for rel_x, rel_y, rel_r, rgb, alpha in blobs:
        cx, cy = rel_x * width, rel_y * height
        r = rel_r * min(width, height) * 1.6
        draw.ellipse(
            [cx - r, cy - r, cx + r, cy + r],
            fill=(rgb[0], rgb[1], rgb[2], alpha),
        )
    overlay = overlay.filter(
        ImageFilter.GaussianBlur(radius=min(width, height) * blur_ratio)
    )
    return Image.alpha_composite(image.convert("RGBA"), overlay)


def build_background(width=1080, height=560):
    """键盘背景图：渐变 + 柔光斑（scaleMode=fitXY，按键盘区域拉伸铺满）。"""
    image = vertical_gradient((width, height), BG_TOP, BG_BOTTOM)
    image = add_glow(image, GLOW_BLOBS)
    out = os.path.join(BASE_DIR, "images", "bg.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    image.convert("RGB").save(out, "PNG", optimize=True)
    return out


def load_font(size, bold=False):
    """
    优先选带中文字形的字体（预览图含“空格/中英”等汉字），缺失时逐级回退。
    元组为 (字体路径, 常规字重 index, 粗体 index)；
    Hiragino Sans GB 的 W3=0 / W6=2 分别对应常规与粗体。
    """
    candidates = [
        ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0, 2),
        ("/System/Library/Fonts/STHeiti Medium.ttc", 1, 1),
        ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", 0, 0),
    ]
    for path, regular_index, bold_index in candidates:
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(
                    path, size, index=bold_index if bold else regular_index)
            except OSError:
                continue
    return ImageFont.load_default()


def rounded_key(draw, box, radius, fill, shadow_offset=0):
    """带下投影的圆角键面（阴影先绘于下方，再绘键面）。"""
    left, top, right, bottom = box
    if shadow_offset:
        draw.rounded_rectangle(
            [left, top + shadow_offset, right, bottom + shadow_offset],
            radius=radius,
            fill=(SHADOW[0], SHADOW[1], SHADOW[2], 26),
        )
    draw.rounded_rectangle(box, radius=radius, fill=fill)


def build_preview(width=720, height=480):
    """预览图：候选栏 + 三行字母 + 底栏，视觉与真实键盘一致。"""
    base = build_preview_canvas(width, height)
    out = os.path.join(BASE_DIR, "preview.png")
    base.convert("RGB").save(out, "PNG", optimize=True)
    return out


def build_preview_canvas(width, height):
    background = vertical_gradient((width, height), BG_TOP, BG_BOTTOM)
    background = add_glow(background, GLOW_BLOBS, blur_ratio=0.14)
    canvas = background.copy()
    draw = ImageDraw.Draw(canvas, "RGBA")

    pad = int(width * 0.035)
    radius = int(width * 0.026)

    # 候选栏（圆角胶囊条，与参考图顶部工具条同构）
    cand_h = int(height * 0.15)
    draw.rounded_rectangle(
        [pad, pad, width - pad, pad + cand_h],
        radius=cand_h // 2,
        fill=(CANDIDATE_BG[0], CANDIDATE_BG[1], CANDIDATE_BG[2], 235),
    )
    cand_font = load_font(int(cand_h * 0.42), bold=True)
    text_y = pad + cand_h // 2
    x = pad + int(width * 0.035)
    for index, word in enumerate(["字由", "输入法", "云朵", "奶油"]):
        color = ACCENT if index == 0 else KEY_TEXT
        draw.text((x, text_y), word, font=cand_font, fill=color, anchor="lm")
        x += int(draw.textlength(word, font=cand_font)) + int(width * 0.045)

    # 三行字母键 + 一行底栏
    rows = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM", None]
    rows_top = pad + cand_h + int(height * 0.045)
    gap = int(width * 0.011)
    row_h = int((height - rows_top - pad - gap * 3) / 4)
    key_font = load_font(int(row_h * 0.46), bold=True)
    shadow_dy = max(int(height * 0.006), 2)

    for row_index, letters in enumerate(rows):
        top = rows_top + row_index * (row_h + gap)
        if letters is None:
            # 底栏：功能键 + 空格 + 强调键（对应参考图底部一行）
            draw_bottom_row(draw, width, pad, top, row_h, gap, radius,
                            key_font, shadow_dy)
            continue
        # 键宽统一按最长行（10 键）计算，短行居中——与真实全键盘同构
        max_count = max(len(row) for row in rows if row)
        unit = (width - pad * 2 - gap * (max_count - 1)) / max_count
        count = len(letters)
        row_width = unit * count + gap * (count - 1)
        indent = (width - pad * 2 - row_width) / 2
        for col, char in enumerate(letters):
            left = pad + indent + col * (unit + gap)
            box = [left, top, left + unit, top + row_h]
            rounded_key(draw, box, radius, KEY_COLOR, shadow_dy)
            draw.text(
                ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2),
                char, font=key_font, fill=KEY_TEXT, anchor="mm",
            )
    return canvas


def draw_bottom_row(draw, width, pad, top, row_h, gap, radius, font, shadow_dy):
    """底栏：符号/数字/逗号 + 空格 + 句号/中英 + 回车（强调色）。"""
    total = width - pad * 2
    # 相对宽度：与九宫格/全键盘底栏比例接近
    weights = [1.3, 1.2, 0.9, 3.2, 0.9, 1.5, 1.5]
    labels = ["符", "123", "，", "空格", "。", "中/英", "回车"]
    func_flags = [True, False, False, False, False, False, "accent"]
    unit = (total - gap * (len(weights) - 1)) / sum(weights)
    # 底栏标签多为双字，字号略小于字母键避免超出键面
    small_font = load_font(int(row_h * 0.34), bold=True)
    x = pad
    for label, weight, flag in zip(labels, weights, func_flags):
        w = unit * weight
        box = [x, top, x + w, top + row_h]
        if flag == "accent":
            fill = ACCENT
            text_color = (255, 255, 255)
        elif flag:
            fill = FUNC_KEY_COLOR
            text_color = KEY_TEXT
        else:
            fill = KEY_COLOR
            text_color = KEY_TEXT
        rounded_key(draw, box, radius, fill, shadow_dy)
        label_font = font if len(label) <= 1 else small_font
        draw.text(
            ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2),
            label, font=label_font, fill=text_color, anchor="mm",
        )
        x += w + gap


if __name__ == "__main__":
    print("背景图:", build_background())
    print("预览图:", build_preview())
