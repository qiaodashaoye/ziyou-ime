---
kind: build_system
name: Gradle + CMake 多模块构建与 RIME 原生库预编译体系
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - app/build.gradle.kts
    - app/src/main/jni/librime_jni/CMakeLists.txt
    - librime-prebuilt/Makefile
    - librime-prebuilt/build.sh
    - librime-prebuilt/superbuild/CMakeLists.txt
    - gradle.properties
---

## 构建系统与工具链

该项目采用 **Gradle 多模块聚合**（Android Application + Android Library）作为上层构建系统，通过 **CMake 交叉编译**集成 C/C++ 原生层（JNI + librime），形成「Gradle → CMake → NDK」的三层构建流水线。

- **Gradle 版本管理**：使用 `gradle/wrapper/` 中的 Gradle Wrapper，插件版本通过 `libs.versions.toml`（由 `alias(libs.plugins.*)` 引用）集中声明。
- **NDK/CMake 交叉编译**：app 模块通过 `externalNativeBuild.cmake` 指定 CMakeLists.txt，调用 Android SDK 内置 cmake 3.22.1 进行 JNI 库构建。
- **librime 预编译**：独立于 Gradle 的 shell 脚本 + CMake superbuild 从源码交叉编译 librime 及其全部依赖（boost、glog、yaml-cpp、leveldb、marisa-trie、opencc），合并为单静态库 `librime.a` 输出到 `libs/<abi>/`。

## 关键文件与职责

| 文件 | 作用 |
|------|------|
| `settings.gradle.kts` | 模块注册（:app, :core-logic）、仓库源配置、插件版本管理 |
| `build.gradle.kts` | 顶层插件声明（android.application / android.library / kotlin.compose） |
| `app/build.gradle.kts` | Android 应用构建配置：compileSdk 35、minSdk 24、ABI 过滤 arm64-v8a、JNI 可调试关闭、R8 混淆开启但优化关闭 |
| `app/src/main/jni/librime_jni/CMakeLists.txt` | JNI 共享库构建：链接 `libs/<abi>/librime.a`，定义 WITH_* 宏开关 |
| `librime-prebuilt/Makefile` | 提供 `make librime` 入口，转发至 build.sh |
| `librime-prebuilt/build.sh` | 核心构建脚本：探测 NDK/CMake、克隆/初始化 librime 子模块、逐 ABI 调用 superbuild 构建并安装产物 |
| `librime-prebuilt/superbuild/CMakeLists.txt` | Superbuild 工程：add_subdirectory 引入所有依赖，bundle_static_library 合并为单一 librime.a |
| `gradle.properties` | JVM 参数、配置缓存启用等全局 Gradle 设置 |

## 架构与构建流程

```
开发者执行 gradle assembleRelease
    ↓
Gradle 触发 app 模块构建
    ↓
CMake 构建 app/src/main/jni/librime_jni/
    ↓
链接 libs/arm64-v8a/librime.a（需预先构建）
    ↓
生成 librime_jni.so → 打包进 APK
```

**librime 预构建流程**（独立于 Gradle）：
```bash
cd librime-prebuilt/
./build.sh [arm64-v8a armeabi-v7a x86_64]  # 默认构建全部 ABI
# 或 make predict  # 带 WITH_PREDICT=ON
```

构建产物结构：
- `libs/<abi>/librime.a` — 各 ABI 的合并静态库
- `libs/include/rime_api.h` 等 — 公共头文件
- `app/build/` — Gradle 生成的中间产物

## 约定与约束

1. **ABI 一致性要求**：app 模块仅编译 `arm64-v8a`（`ndk.abiFilters`），但 build.sh 默认构建三个 ABI。JNI 层通过 `${ANDROID_ABI}` 变量定位对应 `librime.a`。

2. **可选模块开关必须对齐**：`app/build.gradle.kts` 中 `WITH_PREDICT=ON` 必须与 `librime-prebuilt` 侧构建时传入的 `-DWITH_PREDICT=ON` 一致，否则出现 undefined symbol 链接错误。

3. **版本同步策略**：`versionCode` 变更会触发 AssetDeployer 重新部署 schema/predict.db，因此版本号与资源部署逻辑耦合。

4. **混淆与反射兼容**：release 构建开启 R8 压缩/混淆但关闭优化（`optimization.enable = false`），配合 `proguard-rules.pro` 中 `-keep com.ziyou.ime.core.**` 以支持 JNI 反射调用。

5. **构建环境要求**：仅在 macOS/Linux 主机上运行 build.sh；NDK 可通过 `ANDROID_NDK_HOME`、`ANDROID_NDK`、`local.properties` 中的 `sdk.dir` 自动探测。

6. **可重复构建**：superbuild 使用 `LINKER:--hash-style=both,--build-id=none` 确保链接器选项一致，配合 `-ffile-prefix-map` 消除路径差异。

7. **依赖隔离**：`dependencyResolutionManagement.repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 禁止子模块自定义仓库源，统一通过根级 google/mavenCentral 拉取依赖。

## 依赖关系图

```
app (Android Application)
├── core-logic (Android Library) — 纯 Kotlin 逻辑层
└── librime_jni (JNI Shared Lib)
    └── librime.a (Static Lib from prebuilt)
        ├── boost (header-only + compiled)
        ├── glog
        ├── yaml-cpp
        ├── leveldb
        ├── marisa-trie
        └── opencc
```

该构建体系将 Android 应用构建与 C++ 引擎构建解耦：Gradle 负责 Java/Kotlin 层和 JNI 桥接，shell+CMake 负责原生引擎的跨平台交叉编译，通过静态库文件作为两者之间的契约接口。