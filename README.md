# 字由输入法

字由输入法是一个基于 [Rime 输入法引擎](https://github.com/rime/librime) 的简洁 Android 输入法应用。它以最小化的代码实现了完整的 Rime 输入法功能，适合作为学习 Rime Android 集成的参考项目。

## 与 Trime 的区别

| 特性 | 字由输入法 | Trime |
|------|-----------|-------|
| 定位 | 轻量参考实现 | 全功能输入法 |
| 代码规模 | ~3000行 Kotlin + ~700行 C++ | 数万行代码 |
| JNI函数数 | 18个精简接口 | 完整API覆盖 |
| 键盘实现 | 纯Canvas绘制QWERTY | YAML可配置多键盘 |
| 主题系统 | 3个内置主题 | YAML完全自定义 |
| 配置方式 | 代码内置 | YAML Schema驱动 |
| UI框架 | 传统View + Compose(设置页) | 传统View体系 |
| 插件系统 | CMake条件编译 | 运行时模块加载 |
| 最低版本 | Android 7.0 (API 24) | Android 6.0 (API 23) |

## 特性

- **极简设计**：精简的代码结构，易于理解和二次开发
- **完整功能**：支持拼音、仓颉等 Rime 原生输入方案
- **Canvas键盘**：纯代码绘制的 QWERTY 键盘，无 XML 布局依赖
- **候选词横条**：支持滑动翻页、点击选词
- **主题切换**：内置 Light / Dark / Material 三套主题
- **中英切换**：支持 ascii_mode 实时切换
- **配置管理**：通过 JNI 直接读写 Rime 配置文件
- **用户词典同步**：支持 Rime 原生的用户数据同步
- **单线程安全**：所有 Rime 调用通过专属 Dispatcher 顺序执行
- **RAII资源管理**：JNI 层使用 C++ RAII 自动管理会话和引用

## 环境要求

- **Android Studio** Iguana (2023.2.1) 或更高版本
- **JDK** 17+
- **Android SDK** compileSdk 35, minSdk 24
- **NDK** r26+ (推荐 r26c)
- **CMake** 3.22.1+
- **Kotlin** 1.9.22+

## 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd simplerime
```

### 2. 准备 librime 预编译库

本项目自带 `librime-prebuilt/` 模块，可**独立**从源码交叉编译 librime 并生成所需的预编译静态库（无需依赖 Trime）：

```bash
cd librime-prebuilt
make librime          # 编译全部 ABI，产物自动安装到 ../libs/
cd ..
```

编译完成后 `libs/` 目录结构如下：

```
libs/
├── include/
│   └── rime_api.h          # librime 头文件
├── arm64-v8a/
│   └── librime.a           # arm64 静态库（已合并全部依赖）
├── armeabi-v7a/
│   └── librime.a           # armv7 静态库
└── x86_64/
    └── librime.a           # x86_64 静态库
```

> 详细步骤、可选插件、版本兼容性说明见 [`librime-prebuilt/README.md`](librime-prebuilt/README.md)，以及下方 [构建说明](#构建说明) 章节。

### 3. 编译运行

```bash
# 使用 Gradle 编译 debug 版本
./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 启用输入法

1. 打开系统 **设置 → 语言和输入法 → 管理键盘**
2. 开启 **字由输入法**
3. 切换到字由输入法
4. 开始输入

## 构建说明

### 编译 librime 预编译库

字由输入法需要 librime 的 Android 预编译静态库。推荐使用项目自带的 `librime-prebuilt/` 模块本地构建：

#### 方式一（推荐）：使用自带的 librime-prebuilt 模块

```bash
cd librime-prebuilt
# 首次会自动 clone librime 源码（或先按模块 README 以子模块方式固定版本）
make librime
# 产物自动安装到 ../libs/<abi>/librime.a 与 ../libs/include/
```

该模块把 librime 及其全部依赖（boost、glog、yaml-cpp、leveldb、marisa、opencc）
从源码交叉编译并**合并为单个 `librime.a`**，完全独立，不再依赖 Trime 项目。
完整说明见 [`librime-prebuilt/README.md`](librime-prebuilt/README.md)。

#### 方式二：使用现成的预编译库

若已有来自其它来源的 `librime.a` 与 `rime_api.h`，按上文 `libs/` 的目录结构放入即可，
App 侧无需任何改动。

#### 方式三：手动交叉编译

```bash
# 使用 Android NDK 交叉编译 librime
# 需要先编译 librime 的所有依赖（boost, leveldb, marisa, opencc, yaml-cpp等）
# 具体步骤参考 librime 官方文档，或直接参考 librime-prebuilt/ 模块的 CMake 实现
```

### CMake 构建配置

JNI 层的 CMake 配置位于 `app/src/main/jni/librime_jni/CMakeLists.txt`：

```cmake
# 可选模块开关（编译时通过 -D 参数启用）
option(WITH_LUA "Enable Lua module" OFF)
option(WITH_OCTAGRAM "Enable Octagram module" OFF)
option(WITH_PREDICT "Enable Predict module" OFF)
option(WITH_OPENCC "Enable OpenCC module" OFF)
```

在 `app/build.gradle.kts` 中启用可选模块：

```kotlin
android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments("-DWITH_LUA=ON", "-DWITH_OPENCC=ON")
            }
        }
    }
}
```

### 编译 Release 版本

```bash
# Release版本已配置R8/ProGuard混淆
./gradlew :app:assembleRelease
```

## 配置说明

### 配置文件目录

| 目录 | 用途 | 运行时路径 |
|------|------|-----------|
| `assets/rime/` | 初始配置文件（打包到APK） | — |
| `{filesDir}/rime/` | 共享数据目录（shared_data_dir） | 部署后的配置 |
| `{filesDir}/rime_user/` | 用户数据目录（user_data_dir） | 用户词典等 |

### 内置输入方案

项目默认包含以下 Rime 方案（来自 librime/data/minimal/）：

- **朗月拼音** (`luna_pinyin`) - 全拼输入方案
- **仓颉五代** (`cangjie5`) - 仓颉输入方案
- `default.yaml` - 全局默认配置
- `essay.txt` - 八股文词频
- `symbols.yaml` - 符号表

### 添加新的输入方案

1. 将方案文件（`.schema.yaml`）和词典文件（`.dict.yaml`）放入 `app/src/main/assets/rime/` 目录
2. 编辑 `assets/rime/default.yaml`，在 `schema_list` 中添加新方案：

```yaml
schema_list:
  - schema: luna_pinyin
  - schema: cangjie5
  - schema: your_new_schema   # 添加新方案
```

3. 重新编译安装应用，或在设置页面触发重新部署

### 自定义配置

用户可通过以下方式自定义 Rime 配置：

- 修改 `default.yaml` 调整全局选项（如候选词数量 `menu/page_size`）
- 编辑方案 `.schema.yaml` 自定义输入行为
- 通过 `RimeConfigManager` API 在运行时读写配置

## 项目结构

```
simplerime/
├── app/src/main/
│   ├── assets/rime/               # Rime配置文件（7个，来自librime/data/minimal/）
│   ├── java/com/ziyou/ime/
│   │   ├── ZiyouApplication.kt        # Application入口，全局初始化
│   │   ├── core/                       # 核心层：Rime引擎交互
│   │   │   ├── RimeNative.kt          # JNI native方法声明（18个外部函数）
│   │   │   ├── RimeApi.kt             # 引擎API接口（suspend函数）
│   │   │   ├── RimeDispatcher.kt      # 单线程协程调度器
│   │   │   ├── SimpleRimeImpl.kt      # RimeApi实现（代理到Dispatcher）
│   │   │   ├── ProtoTypes.kt          # 数据传输对象（6个data class）
│   │   │   ├── RimeMessage.kt         # 消息类型 + SharedFlow广播
│   │   │   └── RimeConfig.kt          # 配置文件JNI接口
│   │   ├── daemon/                     # 会话管理层
│   │   │   └── RimeSession.kt         # 引擎生命周期单例
│   │   ├── ime/                        # 输入法服务层
│   │   │   ├── SimpleRimeInputMethodService.kt  # IME服务主类
│   │   │   ├── SimpleKeyboardView.kt  # QWERTY键盘（Canvas绘制）
│   │   │   ├── SimpleCandidatesView.kt # 候选词横条（滑动翻页）
│   │   │   └── KeyCode.kt            # Android KeyCode → X11 keysym映射
│   │   ├── config/                     # 配置管理层
│   │   │   ├── RimeConfigManager.kt   # 配置高阶API封装
│   │   │   ├── ThemeManager.kt        # 主题管理（3套预设）
│   │   │   └── AssetDeployer.kt       # assets资源部署器
│   │   └── ui/                         # UI层
│   │       └── SettingsActivity.kt    # 设置页面
│   ├── jni/librime_jni/               # C++/JNI层
│   │   ├── rime_jni.cc               # 核心JNI实现（Rime单例 + 18个导出函数）
│   │   ├── config.cc                  # 配置操作JNI实现
│   │   ├── session.h                  # RAII会话管理器
│   │   ├── jni-utils.h               # JNI工具类（CString/JRef/JString）
│   │   ├── objconv.h                  # C++ → Java对象转换
│   │   ├── helper-types.h            # C++ Proto类型定义
│   │   └── CMakeLists.txt            # Native构建配置
│   └── res/                           # Android资源
├── libs/                              # 预编译librime（按ABI分目录，由librime-prebuilt生成）
├── librime-prebuilt/                  # librime预编译库模块（从源码交叉编译+合并为librime.a）
│   ├── CMakeLists.txt                 # superbuild：编译deps+librime并合并
│   ├── build.sh                       # 逐ABI交叉编译+安装到libs/
│   ├── Makefile                       # `make librime`入口
│   └── cmake/                         # 依赖Find shim + 静态库合并helper
├── build.gradle.kts                   # 项目级构建配置
├── settings.gradle.kts                # Gradle设置
└── gradle.properties                  # Gradle属性
```

## 支持的 ABI

| ABI | 说明 |
|-----|------|
| `arm64-v8a` | 64位ARM（主流设备） |
| `armeabi-v7a` | 32位ARM（兼容旧设备） |
| `x86_64` | x86 64位（模拟器） |

## 贡献指南

欢迎提交 Pull Request 和 Issue！

### 开发流程

1. Fork 本项目
2. 创建功能分支：`git checkout -b feature/your-feature`
3. 提交更改：`git commit -m "feat: add your feature"`
4. 推送分支：`git push origin feature/your-feature`
5. 提交 Pull Request

### 代码规范

- Kotlin 代码遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- C++ 代码遵循项目 `.clang-format` 配置（C++17标准）
- 所有 Rime 调用必须通过 `RimeDispatcher` 调度
- JNI 层使用 RAII 管理资源，避免内存泄漏
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范

### 运行检查

```bash
# 编译检查
./gradlew :app:assembleDebug

# 代码格式化（JNI层）
clang-format -i app/src/main/jni/librime_jni/*.cc app/src/main/jni/librime_jni/*.h
```

## 许可证

本项目基于 [GPL-3.0-or-later](LICENSE) 许可证开源。

```
SPDX-License-Identifier: GPL-3.0-or-later
```
