---
kind: dependency_management
name: Gradle 多模块依赖管理与 CMake 原生库预构建
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - app/build.gradle.kts
    - core-logic/build.gradle.kts
    - librime-prebuilt/build.sh
    - librime-prebuilt/cmake/FindBoost.cmake
    - librime-prebuilt/cmake/FindGlog.cmake
    - librime-prebuilt/cmake/FindLevelDb.cmake
    - librime-prebuilt/cmake/FindMarisa.cmake
    - librime-prebuilt/cmake/FindOpencc.cmake
    - librime-prebuilt/cmake/FindYamlCpp.cmake
---

本仓库采用 Gradle Version Catalog + Android Gradle Plugin (AGP) 管理 Android/Kotlin 依赖，并通过独立的 librime-prebuilt 子工程以 CMake 交叉编译 RIME 输入法引擎为静态库，形成声明式 Kotlin 依赖与预编译原生库的双轨依赖管理体系。

### 1. 系统与方法
- Gradle Version Catalog：所有第三方库版本集中在 gradle/libs.versions.toml 中统一管理，模块通过 alias(libs.plugins.xxx) 和 libs.xxx 引用，避免硬编码版本号。
- 集中式仓库配置：settings.gradle.kts 的 dependencyResolutionManagement 启用 RepositoriesMode.FAIL_ON_PROJECT_REPOS，强制所有模块统一使用根级声明的 google()、mavenCentral() 仓库，禁止子模块自行添加仓库。
- 插件版本集中化：AGP、Kotlin Compose 等插件版本也通过 Version Catalog 的 [plugins] 段管理，由 org.gradle.toolchains.foojay-resolver-convention 自动解析 JDK 工具链。
- CMake 预构建脚本：librime-prebuilt/build.sh 负责从源码克隆/初始化 librime 及其 git 子模块依赖（glog、yaml-cpp、leveldb、marisa-trie、opencc），按 ABI 交叉编译并合并为 libs/<abi>/librime.a 静态库，供 app 模块 JNI 直接链接。

### 2. 关键文件与包
- gradle/libs.versions.toml：全局版本目录（AGP 9.3.0、Kotlin 2.2.10、Compose BOM 2026.02.01、AndroidX 各组件）
- settings.gradle.kts：仓库源与依赖解析策略（FAIL_ON_PROJECT_REPOS）
- app/build.gradle.kts：应用模块依赖声明（AndroidX、Compose、Coroutines、JUnit、Espresso）
- core-logic/build.gradle.kts：纯逻辑库模块，仅依赖 JUnit，无 Android 框架依赖
- librime-prebuilt/build.sh：原生库构建脚本，控制 NDK/CMake 探测、ABI 循环、可选插件开关（WITH_LUA/WITH_OCTAGRAM/WITH_PREDICT）
- librime-prebuilt/cmake/Find*.cmake：自定义 CMake 查找脚本，定位 Boost、Glog、LevelDB、Marisa、Opencc、YamlCpp
- librime-prebuilt/librime/ 与 librime-prebuilt/plugins/：通过 git submodule 管理的上游源码及插件

### 3. 架构与约定
- 模块依赖方向单向：:app → :core-logic，编译器强制边界，core-logic 不反向依赖 app。
- Compose 依赖通过 BOM 统一：所有 Compose 相关库引用 platform(libs.androidx.compose.bom)，确保组件间版本兼容。
- JNI 与原生库耦合通过 CMakeLists.txt 显式声明：app/src/main/jni/librime_jni/CMakeLists.txt 指定 CMake 版本 3.22.1，并通过 -DWITH_PREDICT=ON 与 build.sh 的编译开关保持一致，否则出现 undefined symbol 链接失败。
- NDK 与 ABI 固定：ndk.abiFilters += listOf("arm64-v8a") 限定仅构建 arm64-v8a，与 build.sh 默认 ABIS 中的对应项对齐。
- ProGuard/R8 保留 JNI 符号：release 构建开启 minify 但关闭 optimization，并通过 proguard-rules.pro 的 -keep com.ziyou.ime.core.** 保证反射调用不被混淆破坏。

### 4. 约定与约束
- 禁止子模块自定义仓库：FAIL_ON_PROJECT_REPOS 强制所有依赖必须通过根级 settings.gradle.kts 声明的仓库获取。
- 版本集中管理：所有库版本必须在 libs.versions.toml 中定义，模块内不得直接写版本号字符串。
- 原生库构建需手动执行：librime-prebuilt/build.sh 需在 macOS/Linux 主机上运行，依赖环境变量 ANDROID_NDK_HOME/ANDROID_SDK_ROOT/ANDROID_HOME 或 local.properties 中的 sdk.dir 自动探测 NDK 与 CMake。
- 插件开关一致性约束：app/build.gradle.kts 的 WITH_PREDICT=ON 必须与 build.sh 的 WITH_PREDICT 变量一致，否则链接失败。
- JVM 参数与配置缓存：gradle.properties 设置 org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 与 org.gradle.configuration-cache=true 提升构建性能。