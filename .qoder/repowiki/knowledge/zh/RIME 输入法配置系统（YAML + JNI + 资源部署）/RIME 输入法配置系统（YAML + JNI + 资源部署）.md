---
kind: configuration_system
name: RIME 输入法配置系统（YAML + JNI + 资源部署）
category: configuration_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/ziyou/ime/config/RimeConfigManager.kt
    - app/src/main/java/com/ziyou/ime/config/AssetDeployer.kt
    - app/src/main/java/com/ziyou/ime/config/ThemeManager.kt
    - app/src/main/java/com/ziyou/ime/core/RimeConfig.kt
    - app/src/main/assets/rime/default.yaml
    - app/src/main/assets/rime/luna_pinyin.schema.yaml
    - app/src/main/java/com/ziyou/ime/daemon/RimeSession.kt
---

该工程采用 RIME 输入法引擎的配置体系，通过 YAML 配置文件、JNI 桥接与 Android 资源部署三层协作实现输入法的运行时配置管理。

**系统与架构**
- 配置格式：完全基于 RIME 的 YAML 配置体系，包括 `default.yaml`（全局设置）、`*.schema.yaml`（输入方案定义）、`*.dict.yaml`（词库定义）等标准文件。
- 配置加载：通过 Kotlin 层 `RimeConfigManager` 封装 JNI 接口 `RimeConfig`，调用 C++ 层的 `config.cc` 实现对 RIME 配置文件的读写操作。
- 资源部署：`AssetDeployer` 负责在应用首次安装或版本升级时，将 `assets/rime/` 目录下的所有配置文件递归复制到应用内部存储的共享数据目录，并单独部署 `predict.db` 联想词库到用户目录。
- 目录结构：共享配置目录位于 `context.filesDir/rime`，用户数据目录位于 `context/filesDir/rime_user`，由 `RimeSession` 在初始化时创建。

**核心组件**
- `RimeConfigManager`：提供便捷 API 读取默认配置、方案配置和用户配置，支持整型、字符串、列表路径读取及布尔值写入，使用 `withConfig`/`withSchema`/`withUserConfig` 高阶函数自动管理资源生命周期。
- `RimeConfig`：声明 JNI 外部方法，对应 C++ 层的 `openRimeConfig`、`openRimeUserConfig`、`openRimeSchema`、`closeRimeConfig` 等函数。
- `AssetDeployer`：基于 SharedPreferences 记录已部署的应用版本号，比较当前版本码决定是否重新部署，避免重复 IO 操作。
- `ThemeManager`：独立的主题管理系统，通过 SharedPreferences 持久化主题选择，结合等级系统控制主题解锁。

**配置分层策略**
- 系统配置（default.yaml）：定义全局开关、标点映射、快捷键绑定、识别器模式等基础行为。
- 方案配置（luna_pinyin.schema.yaml 等）：定义输入方案的处理器链、分段器、翻译器、过滤器等引擎管线。
- 用户配置：通过 `openRimeUserConfig` 访问用户级配置，支持个性化设置而不影响系统默认配置。
- 词库配置：通过 `*.dict.yaml` 定义词表来源，支持扩展词库的动态注入。

**启动流程中的配置处理**
1. `RimeSession.initialize` 中首先调用 `AssetDeployer.deployIfNeeded` 部署资源文件
2. 随后执行 `DictManager.regenerateMainDict` 重新注入已启用的扩展词库引用
3. 确保共享目录和用户目录存在后，以 `sharedDir` 和 `userDir` 作为参数启动 RIME 引擎
4. 支持 `fullCheck` 模式用于新方案编译后的完整维护

**约束与约定**
- 配置文件必须遵循 RIME 的 YAML schema 规范，包含 `config_version` 字段
- 配置项路径使用斜杠分隔（如 `menu/page_size`、`schema_list/@0`）
- 所有 JNI 配置对象使用后必须显式关闭，通过 `finally` 块保证资源释放
- 资源部署版本控制基于应用 `versionCode`，首次安装或版本变更触发完整部署
- 主题切换需满足等级解锁条件，通过 `LevelEngine.isThemeUnlocked` 验证权限