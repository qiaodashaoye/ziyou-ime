---
kind: dependency_management
name: Gradle 版本目录与集中式依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - gradle.properties
    - app/build.gradle.kts
    - core-logic/build.gradle.kts
---

本仓库采用 Gradle 版本目录（Version Catalog）作为统一的依赖声明与版本管理中心，配合多模块结构实现依赖的集中化、可复用和严格管控。

**系统与工具**
- 构建系统：Gradle Kotlin DSL（`build.gradle.kts` / `settings.gradle.kts`）
- 版本管理：`gradle/libs.versions.toml` 统一声明所有第三方库版本与插件版本
- 插件解析：通过 `alias(libs.plugins.*)` 引用，避免在子模块中重复声明版本号
- JVM 工具链：通过 `org.gradle.toolchains.foojay-resolver-convention` 插件自动解析 JDK 21（Android Studio JBR），并在 `gradle.properties` 中固定安装路径，禁用自动下载

**核心文件与职责**
- `gradle/libs.versions.toml`：集中定义 `[versions]`、`[libraries]`、`[plugins]` 三段，涵盖 AGP 9.3.0、Kotlin 2.2.10、Compose BOM 2026.02.01、Coroutines 1.8.1、MockK 1.13.13 等全部依赖
- `settings.gradle.kts`：通过 `dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` 强制禁止子模块自行配置仓库，仅允许 `google()`、`mavenCentral()` 两个源；并通过 `pluginManagement` 限定插件来源为 Google、Maven Central、Gradle Plugin Portal
- `build.gradle.kts`（根）：仅声明 `apply false` 的插件别名，不引入任何依赖
- `app/build.gradle.kts` 与 `core-logic/build.gradle.kts`：通过 `implementation(libs.* )` 引用版本目录中的库，内部模块间通过 `project(":core-logic")` 单向依赖

**架构与约定**
- 依赖方向严格单向：`:app → :core-logic`，编译器强制边界，确保纯逻辑层不反向依赖应用层
- Compose 依赖通过 `platform(libs.androidx.compose.bom)` 引入 BOM，保证 UI 相关库版本一致
- 测试依赖按作用域分离：`testImplementation`（JVM 单测）、`androidTestImplementation`（仪器化测试）、`debugImplementation`（调试工具）
- JNI/原生依赖通过 CMake + 预编译静态库 `librime.a` 方式集成，不在 Gradle 依赖管理中声明

**约束与规则**
- 子模块不得自行添加仓库源（`FAIL_ON_PROJECT_REPOS` 强制）
- 所有第三方库版本必须集中在 `libs.versions.toml` 中声明，子模块仅引用别名
- Android 签名配置通过外部 `keystore.properties`（模板 `keystore.properties.template`）加载，不入库
- NDK ABI 列表可通过 `-Pziyou.abis=...` 参数覆盖，默认仅 `arm64-v8a`