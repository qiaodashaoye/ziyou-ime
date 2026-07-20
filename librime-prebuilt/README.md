# librime 预编译库模块（librime-prebuilt）

本模块负责为**字由输入法（simplerime）**从源码交叉编译 [librime](https://github.com/rime/librime) 及其全部依赖，并合并成**单个** Android 静态库 `librime.a`（按 ABI 分目录），供 app 的 JNI 层直接链接。

它用于**取代**此前从 Trime 项目获取预编译库的做法 —— 在 Trime 被删除后，simplerime 可以完全独立地生成所需的 librime 预编译库。

---

## 1. 背景：与 Trime 的做法对比

Trime 并没有真正产出「预编译库文件」，而是把 librime 及其依赖作为 git 子模块，在 **构建 App 时** 通过 CMake（`app/src/main/jni/CMakeLists.txt`）从源码整体编译进 App：

```
Trime app 构建
  └─ CMake
       ├─ add_subdirectory(boost / glog / yaml-cpp / snappy / leveldb / marisa / OpenCC)
       ├─ add_subdirectory(librime)         → 生成 rime-static 目标
       └─ target_link_libraries(rime_jni rime-static ...)   # 同一 CMake 树内直接链接
```

其关键点是：`rime-static` 与各依赖处于**同一个 CMake 构建树**，CMake 自动解析传递依赖，无需真正落地成 `.a` 文件。

simplerime 的定位不同：它的 JNI 层（`app/src/main/jni/librime_jni/CMakeLists.txt`）期望链接一个**已存在的**静态库：

```cmake
find_library(RIME_LIB rime PATHS ${RIME_LIB_DIR} NO_DEFAULT_PATH)  # libs/<abi>/librime.a
```

因此本模块的职责就是：把 Trime 那套「从源码编译」的机制**独立出来**，并在最后**把 librime + 所有依赖合并成一个 `librime.a`**，产出到 `simplerime/libs/`。

本模块的 CMake 依赖配置（`cmake/*.cmake` 中的 Find shim、`Boost.cmake`、`OpenccWorkarounds.cmake`）均**移植自 Trime 的成熟实现**，行为一致。

---

## 2. 模块结构

```
librime-prebuilt/
├── CMakeLists.txt              # superbuild：编译 deps + librime，并合并为 librime.a
├── build.sh                    # 逐 ABI 交叉编译 + 安装到 ../libs 的驱动脚本
├── Makefile                    # `make librime` 等入口（对齐旧 Trime 工作流）
├── cmake/
│   ├── Boost.cmake             # 下载并解压指定版本 Boost
│   ├── FindBoost.cmake         # 把 Boost:: 目标暴露给 librime 的 find_package
│   ├── FindGlog.cmake          # glog 目标 shim
│   ├── FindLevelDb.cmake       # leveldb 目标 shim
│   ├── FindMarisa.cmake        # marisa 目标 shim
│   ├── FindYamlCpp.cmake       # yaml-cpp 目标 shim
│   ├── FindOpencc.cmake        # opencc 目标 shim
│   ├── OpenccWorkarounds.cmake # 收集 OpenCC 头文件到 build/include
│   └── BundleStaticLibrary.cmake # 递归合并所有静态库为单个 librime.a
├── plugins/                    # （可选）放置 librime-lua / octagram / predict 插件源码
└── librime/                    # librime 源码（子模块或脚本自动 clone；已被 .gitignore）
```

**产出目录**（位于 simplerime 根，正是 app 的 JNI 层期望的布局）：

```
simplerime/libs/
├── include/
│   └── rime_api.h              # 及其它 librime 公共头文件
├── arm64-v8a/
│   └── librime.a               # 合并后的静态库（含全部依赖）
├── armeabi-v7a/
│   └── librime.a
└── x86_64/
    └── librime.a
```

---

## 3. 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| 构建主机 | macOS / Linux | 依赖 `/bin/sh` 与 `ar` 的 MRI 脚本，**不支持 Windows** |
| Android NDK | r26+（推荐 r26c） | 需含 `build/cmake/android.toolchain.cmake` 与 `llvm-ar` |
| CMake | 3.18+ | NDK 自带或系统安装均可 |
| Git | 任意较新版本 | 拉取 librime 及其依赖子模块 |
| 网络 | 需要 | 首次会下载 Boost 与克隆 librime 源码 |

---

## 4. 使用步骤

### 步骤 1：获取 librime 源码

有两种方式，任选其一。

**方式 A（推荐）—— 作为 git 子模块固定版本**

在 simplerime 仓库根目录执行：

```bash
git submodule add https://github.com/rime/librime.git librime-prebuilt/librime
cd librime-prebuilt/librime
git submodule update --init --recursive   # 拉取 glog / leveldb / yaml-cpp / marisa / opencc
# 可选：固定到已验证可用的 commit，保证可复现
# git checkout <commit>
cd ../..
```

**方式 B —— 交给 build.sh 自动 clone**

无需手动操作，直接进入步骤 2。`build.sh` 检测到 `librime-prebuilt/librime` 不存在时，会自动执行
`git clone --recursive`（版本由环境变量 `LIBRIME_VERSION` 指定，默认 `master`）。

> **版本兼容性**：simplerime 的 JNI 层仅使用 `rime_api.h` 暴露的 C 接口（`RimeTraits` / `RimeContext` / `process_key` 等），这些 API 长期稳定，librime **1.8.0 及以上**均兼容。为保证可复现，建议用方式 A 固定到一个具体 commit（可参考 Trime 曾使用的 librime commit 作为已验证基线）。

### 步骤 2：配置 NDK 路径

脚本会按以下顺序自动探测 NDK，任选一种即可：

1. 环境变量 `ANDROID_NDK_HOME`（或 `ANDROID_NDK`）；
2. `simplerime/local.properties` 中的 `sdk.dir`，再取 `sdk.dir/ndk/<最高版本>`；
3. 环境变量 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 下的 `ndk/`。

如需手动指定：

```bash
export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/26.3.11579264
```

### 步骤 3：编译

```bash
cd librime-prebuilt

# 编译全部 ABI（arm64-v8a / armeabi-v7a / x86_64）
make librime
#   等价于 ./build.sh

# 或只编译指定 ABI（加快调试）
./build.sh arm64-v8a
```

首次编译会下载 Boost（约几十 MB）并完整编译 librime 与依赖，耗时较长（十几分钟到半小时不等，取决于机器与 ABI 数量）。之后的增量编译会快很多。

### 步骤 4：验证产物

```bash
ls -lh ../libs/arm64-v8a/librime.a      # 应为数十 MB 的合并静态库
ls ../libs/include/                     # 应包含 rime_api.h 等头文件
```

### 步骤 5：编译并运行 App

回到 simplerime 根目录，正常编译即可（app 的 JNI 层会自动从 `libs/<abi>/` 找到 `librime.a`）：

```bash
cd ..
./gradlew :app:assembleDebug
```

---

## 5. 可选插件（Lua / Octagram / Predict）

simplerime 的 JNI 层通过 CMake 开关 `WITH_LUA` / `WITH_OCTAGRAM` / `WITH_PREDICT` 决定是否声明模块依赖。若要启用，需要**同时**：

1. **在本模块中把对应插件编译进 `librime.a`**。将插件源码放到 `plugins/` 下，例如：

   ```bash
   git clone https://github.com/hchunhui/librime-lua        librime-prebuilt/plugins/librime-lua
   git clone https://github.com/lotem/librime-octagram      librime-prebuilt/plugins/librime-octagram
   git clone https://github.com/rime/librime-predict         librime-prebuilt/plugins/librime-predict
   # librime-lua 还需其第三方依赖（lua 源码），参考该仓库 thirdparty 分支
   ```

   然后带开关重新构建（合并库会包含插件的 `rime_require_module_*` 符号）：

   ```bash
   make lua                 # 等价于 WITH_LUA=ON ./build.sh
   # 或组合：WITH_LUA=ON WITH_OCTAGRAM=ON ./build.sh
   ```

2. **在 app 的构建中打开同名开关**（见 `simplerime/app/build.gradle.kts`）：

   ```kotlin
   externalNativeBuild {
       cmake {
           arguments("-DWITH_LUA=ON")
       }
   }
   ```

> 两侧开关必须一致：App 侧的开关决定 JNI 是否调用 `rime_require_module_lua()`，本模块侧的开关决定这些符号是否真的被编进 `librime.a`。二者不一致会导致链接错误或模块未生效。

---

## 6. 依赖管理与版本说明

| 依赖 | 来源 | 版本控制方式 |
|------|------|-------------|
| librime | `librime-prebuilt/librime`（子模块/克隆） | 由 `git checkout` 固定 commit，或 `LIBRIME_VERSION` |
| Boost | `cmake/Boost.cmake` 在线下载 | 常量 `BOOST_VERSION`（当前 1.89.0，含 SHA256 校验） |
| glog / yaml-cpp / leveldb / marisa-trie / opencc | librime 的 `deps/` 子模块 | 随 librime commit 一并固定 |

- **leveldb** 默认**不启用 snappy 压缩**，以避免额外子模块；对输入法词典场景无影响。
- **Boost** 仅编译 librime 需要的组件（regex 等），其余为 header-only，不进入合并库。
- 升级 librime 时，只需在子模块目录 `git fetch && git checkout <new>` 后重新 `make librime`。

---

## 7. 工作原理简述

1. `build.sh` 针对每个 ABI 调用 CMake，使用 NDK 的 `android.toolchain.cmake` 交叉编译。
2. 顶层 `CMakeLists.txt` 依次 `add_subdirectory` 各依赖（生成 `glog` / `yaml-cpp` / `leveldb` / `marisa` / `libopencc` / `Boost::*` 目标），再 `add_subdirectory(librime)` 生成 `rime-static`。
   - librime 的 `find_package(Glog/YamlCpp/...)` 会命中本模块 `cmake/` 下的 Find shim，直接指向上述目标（与 Trime 完全一致）。
3. `bundle_static_library(rime-static rime)` 递归收集 `rime-static` 的**全部静态库依赖**，用 `llvm-ar` 的 MRI 脚本合并成单个 `librime.a`。
4. `cmake --install` 把合并库拷到 `libs/<abi>/`，并把 librime 公共头文件拷到 `libs/include/`。

---

## 8. 常见问题

**Q：链接 App 时报缺少 `__cxa_*` / STL 符号？**
本模块用 `c++_static` 编译。请确认 app 的 native 构建使用同一 STL（NDK 默认即 `c++_static`）。若仍缺符号，可在 `app/src/main/jni/librime_jni/CMakeLists.txt` 的 `target_link_libraries(rime_jni ...)` 中追加 `android`、`atomic`（armeabi-v7a 可能需要）。

**Q：`ar: unknown option -M` 或合并失败？**
请确保使用 **NDK 自带的 `llvm-ar`**（脚本通过 `CMAKE_AR` 自动选择）。系统的 BSD `ar`（macOS 默认）不支持 MRI 脚本，但 CMake 在 Android 工具链下会指向 `llvm-ar`，正常情况下无需手动干预。

**Q：Windows 能构建吗？**
本模块的合并步骤依赖 `/bin/sh` 与 `llvm-ar -M`，仅支持 macOS / Linux 主机。Windows 用户建议使用 WSL2。

**Q：想改回「引用现成 .a」而不自己编？**
把任意来源的 `librime.a` 与 `rime_api.h` 按第 2 节的产出目录结构放入 `simplerime/libs/` 即可，App 侧无需改动。

---

## 许可证

SPDX-License-Identifier: GPL-3.0-or-later
