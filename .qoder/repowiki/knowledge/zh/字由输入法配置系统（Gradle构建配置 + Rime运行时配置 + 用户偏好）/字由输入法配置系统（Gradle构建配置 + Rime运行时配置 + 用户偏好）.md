---
kind: configuration_system
name: 字由输入法配置系统（Gradle构建配置 + Rime运行时配置 + 用户偏好）
category: configuration_system
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - gradle.properties
    - settings.gradle.kts
    - app/build.gradle.kts
    - core-logic/build.gradle.kts
    - app/src/main/java/com/ziyou/ime/config/AssetDeployer.kt
    - app/src/main/java/com/ziyou/ime/config/RimeConfigManager.kt
    - app/src/main/java/com/ziyou/ime/config/DisplayModeManager.kt
    - app/src/main/assets/rime/default.yaml
    - keystore.properties.template
---

本工程的配置系统分为三个层次：构建期配置、Rime引擎运行时配置、以及应用层用户偏好配置，分别由 Gradle、AssetDeployer/RimeConfigManager 和 SharedPreferences 管理。

**1. 构建期配置（Gradle）**
- 根级 `settings.gradle.kts` 通过 `dependencyResolutionManagement` 集中声明仓库（google/mavenCentral/gradlePluginPortal），并启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库，确保依赖来源统一可控。
- `gradle/libs.versions.toml` 使用 Version Catalog 统一管理所有第三方库版本（AGP 9.3.0、Kotlin 2.2.10、Compose BOM 2026.02.01 等），子模块通过 `alias(libs.plugins.* )` 引用插件，避免版本漂移。
- `gradle.properties` 固定 JVM 参数（`org.gradle.jvmargs=-Xmx2048m`）、开启 Configuration Cache、指定 Android Studio JBR 路径，保证构建环境一致。
- `app/build.gradle.kts` 中通过 `Properties().load("keystore.properties")` 读取签名信息，该文件被 `.gitignore` 忽略，仅保留模板 `keystore.properties.template`；ABI 列表支持通过 `-Pziyou.abis=...` 命令行覆盖，默认仅 `arm64-v8a`。
- CMake 外部构建通过 `externalNativeBuild` 指向 `src/main/jni/librime_jni/CMakeLists.txt`，并通过 `-DWITH_PREDICT=ON` 启用 librime-predict 模块，需与 `librime-prebuilt` 侧编译开关保持一致。

**2. Rime 运行时配置（assets → 内部存储部署 + JNI 读写）**
- `AssetDeployer` 负责在应用启动时将 `assets/rime/` 下的 YAML 配置文件（default.yaml、各 schema/*.yaml、opencc 词表等）递归复制到 `context.filesDir/rime/`，并将 `predict.db` 复制到 `rime_user/predict.db`。部署版本通过 `SharedPreferences("ziyou_deploy", KEY_DEPLOYED_VERSION)` 记录，当 `versionCode` 变化时触发重新部署，确保 schema 变更生效。
- `RimeConfigManager` 封装 JNI 层的 Rime 配置读写，提供便捷 API：`getDefaultInt/String`、`getSchemaInt/String`、`getConfig*`、`getUserConfigString` 以及 `withConfig/withSchema/withUserConfig` 高阶函数，自动管理 peer 生命周期（open/close），调用方无需手动释放资源。
- Rime 配置文件以 YAML 形式存放在 `app/src/main/assets/rime/`，包括 `default.yaml`、`luna_pinyin.schema.yaml`、`cangjie5.schema.yaml`、`t9.schema.yaml`、`symbols.yaml` 及对应 dict 文件。

**3. 用户偏好配置（SharedPreferences）**
- `DisplayModeManager` 管理悬浮键盘相关偏好，使用独立 `SharedPreferences("ziyou_display_mode")` 持久化：悬浮模式开关、横屏自动悬浮、面板位置（横竖屏各一份坐标）、缩放因子等，仅存储几何与开关信息，不触碰输入内容。
- 所有偏好均通过 `Context.MODE_PRIVATE` 访问，符合项目隐私基线。

**设计约定与约束**
- 配置分层清晰：构建配置（Gradle）→ 运行时配置（YAML + JNI）→ 用户偏好（SharedPreferences），职责互不越界。
- 资源部署采用版本号比对策略，仅在 `versionCode` 变化时重放 assets，避免重复 IO。
- JNI 配置操作统一通过 `RimeConfigManager` 暴露，禁止直接调用底层 JNI 方法，保证资源安全释放。
- 敏感配置（keystore）不入库，通过模板文件引导开发者本地生成，CI 构建在无 keystore 时产出未签名 APK 用于校验编译。