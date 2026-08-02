---
kind: frontend_style
name: Android 输入法前端样式体系：View + Compose 混合与皮肤系统
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/res/values/themes.xml
    - app/src/main/res/values/colors.xml
    - gradle/libs.versions.toml
    - app/src/main/java/com/ziyou/ime/skin/SkinTheme.kt
    - app/src/main/java/com/ziyou/ime/skin/SkinManager.kt
    - app/src/main/java/com/ziyou/ime/skin/SkinPackLoader.kt
    - app/src/main/java/com/ziyou/ime/skin/SkinSpecCodec.kt
    - app/src/main/java/com/ziyou/ime/ime/BaseKeyboardView.kt
    - app/src/main/java/com/ziyou/ime/ui/DictManagerActivity.kt
    - skins-dev/com.ziyou.cloudcream/skin.json
---

本工程的 UI 样式采用「传统 Android View + Jetpack Compose」混合架构，并通过可插拔的皮肤（Skin）系统实现主题化与视觉一致性。

### 1. 样式技术栈
- **应用层设置页面**：使用 Jetpack Compose + Material3，通过 `androidx-compose-bom` 统一管理版本，Activity 内以 `ComposeView` 嵌入 `MaterialTheme` 包裹的 Composable 树。
- **输入法核心视图**：基于自定义 Android View（`BaseKeyboardView`、`AiPanelView`、`CandidateToolbarView` 等），配色与尺寸全部从运行时 `SkinTheme` 读取，无硬编码颜色/尺寸。
- **资源定义**：基础色值集中在 `app/src/main/res/values/colors.xml`，全局主题在 `themes.xml` 中继承 `Theme.AppCompat.DayNight.NoActionBar`，提供 primary/accent 等 AppCompat 色彩。

### 2. 皮肤系统架构
- **皮肤规范**：每个皮肤包由 `skin.json` 描述，包含 `meta`（id/name/version/darkMode）、`colors.light`（键盘背景、按键背景/文字、候选区、边框等）、`dimens`（圆角、间距、缩放）、`typography`（字号/粗细）、`effects`（按键风格、阴影、透明度）、`background`（背景图及缩放模式）等字段。
- **运行时主题对象**：`SkinTheme.kt` 将 skin.json 解析为强类型主题对象，供所有 IME 视图消费。
- **加载与管理**：`SkinManager` 负责当前皮肤切换；`SkinPackLoader` / `SkinRepository` / `SkinAssetCache` / `SkinSpecCodec` 完成皮肤包的发现、校验、缓存与编解码；`SkinBackgroundDrawable` 渲染背景图。
- **视图集成**：IME 各面板（`BaseKeyboardView`、`AiPanelView`、`ClipboardPanelView`、`DoodleCanvasView`、`FloatingPanelContainer`、`PinyinSideBarView`、`KeyboardPickerPanelView`、`CandidateToolbarView`）均持有 `SkinTheme` 引用，并通过 `applySkin()` 方法响应主题变更。

### 3. 组织约定与约束
- **颜色来源单一**：除 `colors.xml` 中的 AppCompat 基础色外，输入法所有可见颜色均来自 `SkinTheme`，避免 View 层硬编码。
- **DayNight 支持**：AppCompat 主题继承 `NoActionBar` 并启用 DayNight，但当前皮肤 spec 仅定义 `light` 配色，暗色模式尚未在 skin.json 中扩展。
- **Compose 与 View 共存**：设置类 Activity 使用 Compose 构建界面，标题栏复用自定义 `TitleBarView` 以保持与旧版一致；输入法面板仍为原生 View 以保证性能。
- **皮肤包结构**：开发期皮肤位于 `skins-dev/<package>/`，包含 `skin.json`、`images/` 资源与打包脚本（`build_assets.py`、`pack.sh`），最终产物以 `.zyskin` 后缀分发。

### 4. 关键文件
- `app/src/main/res/values/themes.xml` — AppCompat 主题定义
- `app/src/main/res/values/colors.xml` — 基础色值表
- `gradle/libs.versions.toml` — Compose BOM 与依赖版本集中管理
- `app/src/main/java/com/ziyou/ime/skin/SkinTheme.kt` — 运行时主题数据类
- `app/src/main/java/com/ziyou/ime/skin/SkinManager.kt` — 皮肤生命周期管理
- `app/src/main/java/com/ziyou/ime/skin/SkinPackLoader.kt` — 皮肤包加载器
- `app/src/main/java/com/ziyou/ime/skin/SkinSpecCodec.kt` — skin.json 编解码
- `app/src/main/java/com/ziyou/ime/ime/BaseKeyboardView.kt` — 键盘基类，统一 applySkin()
- `app/src/main/java/com/ziyou/ime/ui/DictManagerActivity.kt` — Compose 示例（MaterialTheme 使用）
- `skins-dev/com.ziyou.cloudcream/skin.json` — 皮肤规范实例