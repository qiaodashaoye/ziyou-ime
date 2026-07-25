---
kind: frontend_style
name: Android 输入法 UI 主题与视觉风格体系
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/config/ThemeManager.kt
    - app/src/main/java/com/ziyou/ime/ime/BaseKeyboardView.kt
    - app/src/main/java/com/ziyou/ime/ime/NineGridKeyboardView.kt
    - app/src/main/java/com/ziyou/ime/ime/SimpleKeyboardView.kt
    - app/src/main/java/com/ziyou/ime/ui/SettingsActivity.kt
    - app/src/main/java/com/ziyou/ime/ui/DictManagerActivity.kt
    - app/src/main/res/values/themes.xml
    - app/src/main/res/values/colors.xml
---

该仓库的 Android 输入法前端样式采用「传统 View + Canvas 自定义绘制」与「少量 Jetpack Compose 页面」混合的方式，围绕 ThemeManager 统一管理三套预设键盘主题（Light/Dark/Material），并通过 SharedPreferences 持久化用户选择。具体要点如下：

1. 样式系统与主题架构
- 主题由 com.ziyou.ime.config.ThemeManager 集中管理，定义 KeyboardTheme 数据模型，包含键盘背景、按键背景/文字、候选栏颜色、高亮色、边框等完整调色板。
- 提供三个内置主题：Light（浅色）、Dark（深色）、Material（蓝色调），通过 LevelEngine.isThemeUnlocked 与等级系统联动，未达等级不可应用。
- 所有键盘视图（BaseKeyboardView 及其子类）在初始化时从 ThemeManager 读取当前主题，并在 applyTheme / invalidate 时重建 Paint，保证主题切换即时生效。
- 应用级 AppCompat 主题 Theme.Ziyou 继承自 Theme.AppCompat.DayNight.NoActionBar，仅设置 colorPrimary/colorPrimaryDark/colorAccent，实际键盘配色不走 Material Components 主题，而是由 ThemeManager 控制。

2. 核心文件与位置
- 主题管理与数据模型：app/src/main/java/com/ziyou/ime/config/ThemeManager.kt
- 键盘绘制基类（Canvas 自定义绘制）：app/src/main/java/com/ziyou/ime/ime/BaseKeyboardView.kt
- 九宫格/全键盘等具体键盘实现：app/src/main/java/com/ziyou/ime/ime/NineGridKeyboardView.kt、SimpleKeyboardView.kt 等
- 候选栏、侧边栏、悬浮面板等辅助视图：SimpleCandidatesView.kt、PinyinSideBarView.kt、FloatingPanelContainer.kt、PreeditOverlayView.kt
- 设置页（纯代码构建 LinearLayout + TextView/Switch/AlertDialog）：app/src/main/java/com/ziyou/ime/ui/SettingsActivity.kt
- 词库管理页（使用 Jetpack Compose + MaterialTheme）：app/src/main/java/com/ziyou/ime/ui/DictManagerActivity.kt
- 资源文件：app/src/main/res/values/colors.xml（App 主色调）、themes.xml（AppCompat 主题声明）

3. 架构与约定
- 键盘渲染统一走 BaseKeyboardView 抽象类：子类只需提供 rows 布局定义与 handleKeyUp 逻辑，绘制、触摸、长按重复、阴影圆角、主题着色均由基类完成。
- 颜色不直接硬编码在业务代码中，而是通过 KeyboardTheme 字段访问；Paint 对象按主题重建，避免跨主题污染。
- 设置页全部用代码动态创建 View（ScrollView → LinearLayout → TextView/Switch/Divider），不使用 XML 布局，保持轻量。
- 少数页面（如 DictManagerActivity）已迁移到 Jetpack Compose，使用 MaterialTheme.colorScheme.* 获取颜色，与旧式 View 主题并存。

4. 约束与规范
- 键盘主题必须通过 ThemeManager 获取和切换，禁止在 View 内直接写死颜色值。
- 新增键盘类型需继承 BaseKeyboardView，并实现 rows 与 handleKeyUp，复用基类的 Canvas 绘制与主题能力。
- AppCompat 主题仅用于 Activity 外壳，输入法键盘本身的颜色完全由 ThemeManager 控制，不依赖系统 DayNight 自动切换。
- 资源 colors.xml 仅保留 App 级 primary/accent 等基础色，键盘配色集中在 ThemeManager 的 KeyboardTheme 中。