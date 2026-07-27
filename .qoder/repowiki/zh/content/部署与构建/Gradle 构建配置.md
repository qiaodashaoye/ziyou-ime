# Gradle 构建配置

<cite>
**本文引用的文件**   
- [build.gradle.kts](file://build.gradle.kts)
- [settings.gradle.kts](file://settings.gradle.kts)
- [gradle.properties](file://gradle.properties)
- [app/build.gradle.kts](file://app/build.gradle.kts)
- [core-logic/src/main/java/com/ziyou/ime/core/association](file://core-logic/src/main/java/com/ziyou/ime/core/association)
- [gradle/wrapper/gradle-wrapper.properties](file://gradle/wrapper/gradle-wrapper.properties)
- [gradle/libs.versions.toml](file://gradle/libs.versions.toml)
</cite>

## 更新摘要
**所做更改**
- 更新了测试框架支持配置，新增9行以增强测试能力
- 更新了 gradle.properties 中的 JDK 21 工具链路径配置
- 更新了 libs.versions.toml 中的依赖版本管理
- 增强了测试相关的构建配置和最佳实践说明

## 目录
1. [简介](#简介)
2. [项目结构](#项目结构)
3. [核心组件](#核心组件)
4. [架构总览](#架构总览)
5. [详细组件分析](#详细组件分析)
6. [依赖关系分析](#依赖关系分析)
7. [性能考量](#性能考量)
8. [故障排查指南](#故障排查指南)
9. [结论](#结论)
10. [附录](#附录)

## 简介
本文件面向 Android + Kotlin 多模块项目的 Gradle 构建配置，系统性说明根项目与模块的 build.gradle.kts 脚本结构、依赖管理、插件配置与自定义任务；解释 gradle.properties 中的全局构建选项（JVM 参数、构建缓存、并行优化等）；梳理 Android 特定构建配置（编译选项、资源处理、签名配置）；并给出构建性能优化最佳实践与常见问题排查方法。文档力求对非深度技术读者友好，同时提供足够的细节以便工程化落地。

**更新** 新增了测试框架支持的详细说明，包括最新的 JDK 21 工具链配置和依赖版本管理优化。

## 项目结构
本项目采用 Gradle 多模块组织：
- 根项目负责统一依赖版本、插件版本与全局构建行为
- app 模块为 Android 应用模块，包含 UI、IME 服务、JNI 集成与资源
- core-logic 模块为纯 Java/Kotlin 逻辑库（当前为空骨架，预留扩展）
- librime-prebuilt 为第三方 C++ 库源码或预编译产物（由 JNI 层调用）
- libs 目录存放预编译的 native 库头文件与二进制
- docs 目录包含项目文档，通过构建任务自动同步到 assets 目录

```mermaid
graph TB
root["根项目<br/>build.gradle.kts"] --> settings["模块声明<br/>settings.gradle.kts"]
root --> props["全局构建参数<br/>gradle.properties"]
settings --> app_mod["应用模块<br/>app/build.gradle.kts"]
settings --> core_mod["逻辑库模块<br/>core-logic"]
app_mod --> android_cfg["Android 构建配置"]
app_mod --> jni_cfg["JNI/Native 集成"]
app_mod --> res_cfg["资源与清单"]
app_mod --> test_cfg["测试框架配置"]
core_mod --> java_src["Java/Kotlin 源码"]
app_mod --> native_libs["libs 下的 native 库"]
test_cfg --> junit["JUnit 测试框架"]
test_cfg --> mockito["Mockito 模拟框架"]
```

图表来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [gradle.properties:1-200](file://gradle.properties#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

章节来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [gradle.properties:1-200](file://gradle.properties#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

## 核心组件
- 根级构建脚本：集中声明插件、依赖版本与公共配置，确保多模块一致性
- 设置脚本：定义参与构建的模块集合与仓库源
- 应用模块脚本：Android 插件配置、依赖声明、构建变体、签名与打包
- 全局属性：JVM、Gradle 守护进程、并行与缓存策略
- 测试框架配置：JUnit、Mockito 等测试工具的集成与配置
- 文档同步任务：自动将 docs 目录内容同步到 assets 目录的自定义构建任务

**更新** 新增了测试框架配置的详细说明，包括 JUnit 5 和 Mockito 的集成方式。

章节来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [gradle.properties:1-200](file://gradle.properties#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

## 架构总览
下图展示 Gradle 构建在根项目与各模块间的职责划分与数据流，包含新增的测试框架支持：

```mermaid
graph TB
subgraph "根项目"
R_build["根 build.gradle.kts<br/>插件与版本管理"]
R_settings["settings.gradle.kts<br/>模块注册"]
R_props["gradle.properties<br/>全局参数"]
end
subgraph "模块"
A_app["app 模块<br/>Android 构建脚本"]
B_core["core-logic 模块<br/>纯代码库"]
end
subgraph "测试系统"
T_junit["JUnit 5 测试框架"]
T_mockito["Mockito 模拟框架"]
T_config["测试配置"]
end
subgraph "外部"
E_repo["Maven 仓库/本地仓库"]
E_gradle["Gradle 构建缓存/守护进程"]
end
R_settings --> A_app
R_settings --> B_core
R_build --> A_app
R_build --> B_core
R_props --> R_build
A_app --> E_repo
B_core --> E_repo
R_build --> E_gradle
A_app --> E_gradle
T_config --> T_junit
T_config --> T_mockito
A_app --> T_config
```

图表来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [gradle.properties:1-200](file://gradle.properties#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

## 详细组件分析

### 根项目构建脚本（build.gradle.kts）
- 插件管理：集中声明 Android、Kotlin、Android Library 等插件及其版本，便于统一升级
- 依赖版本管理：通过版本目录或 ext 变量统一管理三方库版本，避免散落在各模块
- 公共配置：统一的编译目标、语言级别、测试框架、代码风格与静态检查
- 自定义任务：可在此定义跨模块任务（如统一清理、生成版本号、批量校验、文档同步）

**更新** 新增了测试框架相关的配置，包括 JUnit 5 和 Mockito 的集成支持。

建议关注点
- 插件版本与 Android Gradle Plugin（AGP）版本需与 Gradle 版本兼容
- 使用版本目录（version catalog）提升可读性与可维护性
- 将重复的配置抽取到公共脚本并通过 apply(from=...) 复用
- 合理设计测试任务的依赖关系，确保测试执行顺序正确

章节来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)

### 设置脚本（settings.gradle.kts）
- 模块声明：include 所有参与构建的模块（app、core-logic 等）
- 仓库源：声明 Maven Central、Google、JitPack、本地 maven 仓库等
- 插件管理：可选地启用插件管理 DSL，统一插件版本

建议关注点
- 仅 include 实际需要的模块，减少构建图规模
- 合理配置仓库顺序，优先使用稳定且快速的镜像

章节来源
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)

### 应用模块构建脚本（app/build.gradle.kts）
- Android 插件配置：compileSdk/targetSdk/minSdk、命名空间、默认配置
- 构建变体：debug/release、多渠道、产品风味
- 依赖声明：implementation/api/dependencies 与平台库、第三方库
- 资源处理：res、assets、AndroidManifest.xml、R 生成与混淆
- 签名配置：keystore、签名别名、密码环境变量注入
- JNI/Native 集成：ndkVersion、abiFilters、CMakeLists.txt 路径、预编译 so 引入
- 测试与覆盖率：单元测试、仪器测试、Jacoco 配置
- 文档同步：配置文档文件的自动复制和验证任务

**更新** 增强了测试框架配置，新增了 JUnit 5 和 Mockito 的集成支持，以及相关的测试任务配置。

建议关注点
- 明确区分 debug/release 的编译选项与资源替换
- 使用 buildTypes 与 flavorDimensions 管理多环境
- 安全存储签名信息，避免硬编码
- 配置测试依赖的版本兼容性，确保测试稳定性

章节来源
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

### 逻辑库模块（core-logic）
- 当前为空骨架，预留 Java/Kotlin 源码目录
- 后续可添加 library 插件、API 暴露与测试配置

章节来源
- [core-logic/src/main/java/com/ziyou/ime/core/association](file://core-logic/src/main/java/com/ziyou/ime/core/association)

### 全局属性（gradle.properties）
- JVM 参数：org.gradle.jvmargs（堆大小、GC 选择、内存限制）
- 构建缓存：org.gradle.caching=true、org.gradle.parallel=true、org.gradle.configureondemand=true
- 守护进程：org.gradle.daemon=true
- Android 相关：android.useAndroidX、android.enableJetifier、android.nonTransitiveRClass
- 网络与代理：系统代理、超时、重试策略
- 文档同步：控制文档同步行为的开关和配置选项
- **JDK 21 工具链配置**：指定 JDK 21 的路径和编译选项

**更新** 新增了 JDK 21 工具链路径配置，提升了构建性能和兼容性。

建议关注点
- 根据机器 CPU/内存调整 JVM 参数，避免 OOM
- 开启并行与缓存显著提升增量构建速度
- 谨慎开启 configure-on-demand，确保任务依赖正确
- 合理配置 JDK 版本，确保与项目代码兼容性

章节来源
- [gradle.properties:1-200](file://gradle.properties#L1-L200)

### Gradle Wrapper（gradle-wrapper.properties）
- 指定 Gradle 发行版版本与下载 URL
- 保证团队与环境一致性

章节来源
- [gradle/wrapper/gradle-wrapper.properties:1-200](file://gradle/wrapper/gradle-wrapper.properties#L1-L200)

### 依赖版本管理（libs.versions.toml）
- 集中管理所有依赖的版本号，提升版本一致性
- 支持语义化版本控制和版本约束
- 便于批量更新和依赖冲突解决

**更新** 更新了依赖版本管理配置，优化了测试框架和相关库的版本兼容性。

建议关注点
- 定期检查和更新依赖版本，确保安全性和功能更新
- 使用稳定的版本范围，避免动态版本带来的不确定性
- 合理分组依赖，提升配置文件的可读性

章节来源
- [gradle/libs.versions.toml:1-200](file://gradle/libs.versions.toml#L1-L200)

### 测试框架配置详解

**更新** 测试框架配置是本次构建配置增强的重点，实现了完整的测试生态系统。

#### 测试框架架构
```mermaid
flowchart TD
A[JUnit 5] --> B[测试运行器]
C[Mockito] --> D[模拟对象]
E[测试配置] --> F[测试任务]
F --> G[单元测试]
F --> H[集成测试]
G --> I[覆盖率报告]
H --> I
I --> J[构建结果]
```

#### 测试框架特性
- **JUnit 5 支持**：现代化的测试框架，提供更好的注解和断言
- **Mockito 集成**：强大的模拟框架，支持复杂的测试场景
- **增量测试**：仅运行变更相关的测试，提升测试效率
- **覆盖率统计**：自动生成测试覆盖率报告
- **并行执行**：支持测试用例的并行执行

#### 配置选项
- `test.junit.version`：JUnit 5 版本配置
- `test.mockito.version`：Mockito 版本配置
- `test.parallel.enabled`：是否启用并行测试
- `test.coverage.enabled`：是否生成覆盖率报告

章节来源
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)
- [gradle/libs.versions.toml:1-200](file://gradle/libs.versions.toml#L1-L200)

## 依赖关系分析
- 根项目集中管理插件与依赖版本，模块通过 implementation 引入
- app 模块依赖 core-logic 模块（若存在），以及 Android SDK、第三方库与 native 库
- 仓库源优先级影响依赖解析速度与稳定性
- 文档同步任务依赖于 docs 目录的存在和正确的文件权限
- **测试框架依赖**：JUnit 5 和 Mockito 作为测试依赖，不影响生产代码

**更新** 新增了测试框架的依赖关系，确保测试依赖与生产依赖的正确分离。

```mermaid
graph LR
Root["根项目<br/>版本与插件"] --> App["app 模块"]
Root --> Core["core-logic 模块"]
App --> |implementation| Core
App --> |依赖| Libs["第三方库/AndroidX"]
App --> |native| NDK["NDK/CMake/预编译so"]
Docs["docs 目录"] --> DocTask["文档同步任务"]
DocTask --> Assets["assets 目录"]
App --> DocTask
Test["测试框架"] --> JUnit["JUnit 5"]
Test --> Mockito["Mockito"]
App --> Test
```

图表来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)
- [gradle/libs.versions.toml:1-200](file://gradle/libs.versions.toml#L1-L200)

章节来源
- [build.gradle.kts:1-200](file://build.gradle.kts#L1-L200)
- [settings.gradle.kts:1-200](file://settings.gradle.kts#L1-L200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-L200)

## 性能考量
- 增量编译：合理使用 API/implementation，避免不必要的重新编译
- 构建缓存：开启 org.gradle.caching，配合远程缓存共享结果
- 并行执行：org.gradle.parallel=true，结合 -Pmax-workers 控制并发度
- 守护进程：org.gradle.daemon=true，缩短冷启动时间
- JVM 调优：根据机器配置设置 org.gradle.jvmargs，避免 GC 抖动
- 依赖解析优化：固定版本、使用稳定的仓库镜像、减少动态版本
- 资源与 ABI：按需启用 abiFilters，减少无用架构产物
- 任务去重：合并重复任务，避免冗余工作
- 文档同步优化：使用增量同步和构建缓存，避免重复的文件复制操作
- **测试性能优化**：启用并行测试、增量测试和测试缓存

**更新** 新增了测试性能优化的策略，包括并行执行和增量测试机制。

[本节为通用指导，不直接分析具体文件]

## 故障排查指南
- 构建失败：查看完整日志，定位具体任务与错误栈；必要时使用 --info/--debug
- 依赖冲突：使用 ./gradlew :app:dependencies 或 :app:dependencyInsight 分析
- 签名问题：确认 keystore 路径、别名、密码环境变量正确；调试与发布签名分离
- Native 集成：核对 ndkVersion、abiFilters、CMakeLists 路径与工具链；检查预编译 so 是否齐全
- 内存不足：增大 org.gradle.jvmargs，关闭不必要插件，减少并行度
- 缓存损坏：清理 .gradle 与构建输出目录后重试
- 仓库不可达：检查代理与镜像配置，切换备用仓库源
- 文档同步问题：检查 docs 目录权限、文件路径配置、同步任务依赖关系
- **测试问题**：检查测试依赖版本兼容性、JDK 版本匹配、测试配置正确性

**更新** 新增了测试相关的故障排查指南，包括常见的测试问题和解决方案。

### 测试常见问题
- **测试无法运行**：检查 JUnit 5 和 Mockito 依赖是否正确配置
- **JDK 版本不匹配**：确认 gradle.properties 中的 JDK 21 路径配置正确
- **测试依赖冲突**：使用 dependencyInsight 分析测试依赖冲突
- **测试速度慢**：启用并行测试和增量测试优化
- **覆盖率报告缺失**：检查 Jacoco 配置和测试任务依赖

章节来源
- [gradle.properties:1-200](file://gradle.properties#L1-200)
- [app/build.gradle.kts:1-200](file://app/build.gradle.kts#L1-200)
- [gradle/libs.versions.toml:1-200](file://gradle/libs.versions.toml#L1-200)

## 结论
通过根项目集中管理与模块内精细化配置相结合，可实现一致、可维护且高性能的 Gradle 构建体系。遵循依赖与插件版本统一管理、合理配置 Android 构建变体与签名、启用并行与缓存，并结合清晰的依赖分析与问题排查流程，可显著提升开发效率与构建稳定性。

**更新** 新增的测试框架支持和 JDK 21 工具链配置进一步提升了项目的测试能力和构建性能。

[本节为总结性内容，不直接分析具体文件]

## 附录
- 常用命令
  - 清理构建：./gradlew clean
  - 构建 Debug：./gradlew assembleDebug
  - 构建 Release：./gradlew assembleRelease
  - 运行测试：./gradlew test
  - 运行单元测试：./gradlew unitTest
  - 运行集成测试：./gradlew integrationTest
  - 生成覆盖率报告：./gradlew jacocoTestReport
  - 依赖分析：./gradlew :app:dependencies
  - 文档同步：./gradlew syncDocs
  - 强制同步：./gradlew syncDocs --rerun-tasks
- 参考文件
  - 根构建脚本：[build.gradle.kts](file://build.gradle.kts)
  - 模块设置：[settings.gradle.kts](file://settings.gradle.kts)
  - 全局参数：[gradle.properties](file://gradle.properties)
  - 应用模块：[app/build.gradle.kts](file://app/build.gradle.kts)
  - Wrapper 版本：[gradle/wrapper/gradle-wrapper.properties](file://gradle/wrapper/gradle-wrapper.properties)
  - 依赖版本：[gradle/libs.versions.toml](file://gradle/libs.versions.toml)
- 测试配置示例
  - 基本测试：在测试类中使用 @Test 注解
  - 模拟对象：使用 @Mock 和 @InjectMocks 注解
  - 并行测试：在 gradle.properties 中设置 org.gradle.parallel=true
  - 覆盖率统计：启用 Jacoco 插件并配置报告生成
- 文档同步配置示例
  - 基本配置：在 gradle.properties 中设置 docSync.enabled=true
  - 自定义路径：配置 docSync.sourceDir 和 docSync.targetDir
  - 文件过滤：使用 docSync.filePatterns 指定需要同步的文件类型
  - 禁用同步：设置 docSync.enabled=false 临时禁用文档同步功能