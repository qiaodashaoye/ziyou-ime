# 云朵奶油（com.ziyou.cloudcream）

参考「奶油雾面 / 云朵拟态」风格设计的字由输入法皮肤包，`specVersion = 1`。

## 视觉映射

| 参考图元素 | 皮肤字段 | 取值 |
|-----------|---------|------|
| 淡紫灰雾面底板 | `colors.light.keyboardBackground` + `background.image` | `#E3E5F1` + 渐变柔光背景图 |
| 纯白圆角键面 | `keyBackground` / `dimens.keyCornerRadiusDp` | `#FFFFFF` / `14dp` |
| 键间大留白 | `dimens.keyGapDp` / `keyboardPaddingDp` | `6dp` / `6dp` |
| 键面下方柔和投影 | `effects.keyShadow` | `dy = 2dp`，色 `#1A272B45` |
| 深近黑加粗字形 | `keyTextColor` / `typography.keyTextBold` | `#26262B` / `true` |
| 淡紫功能键（左下换挡键） | `funcKeyBackground` | `#D9DCEF` |
| 樱粉强调键（右侧删除/回车） | `candidateHighlightColor` | `#E8879C` |
| 候选/工具条浅底 | `candidateBackground` | `#F3F4FA` |

`darkMode: light` —— 本皮肤为浅色单变体，深色系统下仍保持浅色雾面观感。

## 目录结构

```
com.ziyou.cloudcream/
├── skin.json          皮肤规格（唯一必需文件）
├── preview.png        皮肤管理页预览图（720×480）
├── images/bg.png      键盘背景图（1080×560，scaleMode=fitXY）
├── build_assets.py    背景图/预览图生成脚本（开发用，不入包）
└── pack.sh            打包脚本（开发用，不入包）
```

## 构建与导入

```bash
# 1. （可选）改色后重新生成背景图与预览图
python3 build_assets.py

# 2. 打包为 .zyskin（产物落在 skins-dev/com.ziyou.cloudcream.zyskin）
./pack.sh
```

导入路径：**字由输入法设置页 → 外观 → 键盘皮肤 → 导入皮肤包 → 选择 `.zyskin`**。
导入后在皮肤网格中点击即应用；点「自定义当前皮肤」可在此皮肤上继续微调
（圆角 / 键距 / 字号 / 配色 / 透明度 / 背景图），自定义为独立覆盖层，不改动本皮肤包。

## 注意

- 皮肤包只允许 `json / png / jpg / jpeg / webp / ttf / otf`，`build_assets.py`
  与 `pack.sh` 不会被打进包（含其他扩展名会被安装器整包拒绝）。
- 修改 `skin.json` 配色后请同步 `build_assets.py` 顶部的配色常量，
  以保证预览图与实际键盘一致。
