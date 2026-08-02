---
kind: build_system
name: Gradle 多模块构建与 Native 预编译流水线
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - gradle.properties
    - app/build.gradle.kts
    - core-logic/build.gradle.kts
    - app/src/main/jni/librime_jni/CMakeLists.txt
    - librime-prebuilt/build.sh
    - librime-prebuilt/Makefile
    - scripts/build-release.sh
---

## 构建系统与工具链

项目采用 **Gradle Kotlin DSL** 组织 Android 多模块工程，核心版本通过 `gradle/libs.versions.toml` 统一管理（AGP 9.3.0、Kotlin 2.2.10、Compose BOM 2026.02.01）。顶层 `build.gradle.kts` 仅声明插件别名，所有子模块通过 `alias(libs.plugins.*)` 引用，避免版本漂移。

仓库包含两个 Gradle 模块：
- `:app` — Android Application，IME 主应用，含 JNI/CMake 原生层
- `:core-logic` — Android Library，纯 Kotlin 逻辑库（无 Android UI/JNI），被 :app 单向依赖

依赖解析通过 `settings.gradle.kts` 的 `dependencyResolutionManagement` 集中配置，启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行声明仓库，确保依赖来源可控。使用 `org.gradle.toolchains.foojay-resolver-convention` 自动管理 JDK 工具链。

## Native 构建流程

JNI 层通过 CMake 构建，`app/src/main/jni/librime_jni/CMakeLists.txt` 链接预编译静态库 `libs/<abi>/librime.a`。librime 本身由独立脚本 `librime-prebuilt/build.sh` 交叉编译，支持参数化 ABI 列表（默认 arm64-v8a/armeabi-v7a/x86_64）、可选插件开关（WITH_LUA/WITH_OCTAGRAM/WITH_PREDICT）和 NDK/CMake 自动探测。

发布构建通过 `scripts/build-release.sh` 统一编排：
1. 校验 keystore.properties 签名配置
2. 可选重建 native 库（--rebuild-native）
3. 运行全量单元测试并校验用例数不低于基线（scripts/unit-test-baseline.txt）
4. 生成签名 Release APK 并输出到 dist/
5. 生成 SHA256 校验文件

## 构建约定与约束

- **ABI 过滤**：通过 `-Pziyou.abis=arm64-v8a,armeabi-v7a` 覆盖默认 ABI 列表，每个 ABI 必须预先产出对应 librime.a
- **测试门禁**：发布脚本强制要求单元测试全绿且用例数不低于基线，禁止通过 @Ignore 跳过既有用例使套件变绿
- **签名安全**：keystore.properties 不入库，提供 template；未找到时产出不签名 APK（CI 校验用）
- **代码混淆**：Release 开启 R8 minify 但关闭 optimization，避免破坏 JNI 符号与反射调用
- **文档同步**：构建时自动将 docs/技能插件开发指南.md 拷贝至 assets/docs/skill_dev_guide.md，作为单一来源
- **JVM 环境**：强制使用 Android Studio JBR（/Applications/Android Studio.app/Contents/jbr/Contents/Home），禁用自动检测防止 IDE 内置 JRE 被误用
- **CMake 版本锁定**：JNI 层固定使用 3.22.1，与 Android SDK 组件版本对齐
- **链接优化**：启用 --gc-sections、--exclude-libs,ALL 和 16KB 页面对齐（Android 15+ 要求）