# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 字由输入法 —— librime 预编译库 superbuild
#
# 该 CMake 工程从源码交叉编译 librime 及其全部依赖（boost / glog /
# yaml-cpp / leveldb / marisa-trie / opencc），并将它们合并成一个
# 单独的静态库 librime.a，供 ziyou-ime 的 JNI 层直接链接。
#
# 本工程不直接被 Android Gradle 调用，而是由 build.sh 针对每个 ABI
# 单独配置 / 构建。构建产物（librime.a + 头文件）最终安装到
# ziyou-ime/libs/ 目录下。
#
# 注意：本工程位于 superbuild/ 子目录中（而非顶层 CMakeLists.txt），
# 以防止 Android Studio 自动检测并尝试配置此工程。

cmake_minimum_required(VERSION 3.18.0)

project(librime_prebuilt VERSION 1.0.0)

# ---------------------------------------------------------------------------
# 入参（由 build.sh 通过 -D 传入）
# ---------------------------------------------------------------------------
# LIBRIME_SOURCE_DIR  librime 源码根目录（含 deps/ 子模块）
# PREBUILT_INSTALL_DIR 合并静态库 + 头文件的安装目录（即 ziyou-ime/libs）
# WITH_LUA / WITH_OCTAGRAM / WITH_PREDICT  可选插件开关（默认 OFF）
if(NOT DEFINED LIBRIME_SOURCE_DIR)
  message(FATAL_ERROR "LIBRIME_SOURCE_DIR is not set. Run via build.sh.")
endif()
if(NOT EXISTS "${LIBRIME_SOURCE_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "librime source not found at ${LIBRIME_SOURCE_DIR}. "
    "Did you run `git submodule update --init --recursive` / build.sh fetch step?")
endif()

option(WITH_LUA "Bundle librime-lua module" OFF)
option(WITH_OCTAGRAM "Bundle librime-octagram module" OFF)
option(WITH_PREDICT "Bundle librime-predict module" OFF)
option(WITH_WITOGRAM "Bundle librime-witogram module (octagram 演进替代，含 KenLM)" OFF)

# for reproducible build（与 Trime 保持一致）
add_link_options("LINKER:--hash-style=both,--build-id=none")

# 父目录（librime-prebuilt/）包含 cmake 辅助模块和 plugins
set(PREBUILT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")

# 本工程的 cmake 辅助模块（Find* shim / Boost / Bundle）需要排在最前，
# 以覆盖 librime 自带的 Find*.cmake（后者面向系统安装的库）。
set(CMAKE_MODULE_PATH "${PREBUILT_ROOT}/cmake" ${CMAKE_MODULE_PATH})

include(BundleStaticLibrary)

# ---------------------------------------------------------------------------
# 全局构建选项
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(BUILD_SHARED_LIBS "" OFF)
option(BUILD_TESTING "" OFF)

# ---------------------------------------------------------------------------
# 依赖 1：Boost（在线下载指定版本，解压到构建目录）
# ---------------------------------------------------------------------------
include(Boost)

# ---------------------------------------------------------------------------
# 依赖 2：glog
# ---------------------------------------------------------------------------
option(WITH_GFLAGS "Use gflags" OFF)
option(WITH_GTEST "Use googletest" OFF)
option(WITH_UNWIND "Enable libunwind support" OFF)
add_subdirectory("${LIBRIME_SOURCE_DIR}/deps/glog" glog EXCLUDE_FROM_ALL)
target_compile_options(glog_internal PRIVATE
  "-ffile-prefix-map=${LIBRIME_SOURCE_DIR}=.")

# glog 的 INTERFACE include 包含 glog_BINARY_DIR（其内生成 config.h），
# 会沿链接关系传播到 witogram 内嵌的 sentencepiece：后者以裸名
# #include "config.h" 解析宏（PACKAGE_STRING/VERSION），被 glog 的
# config.h 截胡后在 libc++/NDK 下编译失败（桌面构建因 include 顺序
# 差异未暴露）。glog 自身编译只依赖 PRIVATE 路径，此处将对外接口
# 收窄为 src/ 头文件目录，消除裸名头文件污染。
if(WITH_WITOGRAM)
  set_target_properties(glog glog_internal PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES
    "$<BUILD_INTERFACE:${LIBRIME_SOURCE_DIR}/deps/glog/src>")
endif()

# ---------------------------------------------------------------------------
# 依赖 3：yaml-cpp
# ---------------------------------------------------------------------------
option(YAML_CPP_BUILD_TOOLS "" OFF)
option(YAML_CPP_BUILD_TESTS "" OFF)
option(YAML_CPP_INSTALL "" OFF)
add_subdirectory("${LIBRIME_SOURCE_DIR}/deps/yaml-cpp" yaml-cpp EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# 依赖 4：leveldb（不启用 snappy 压缩，避免额外子模块）
# ---------------------------------------------------------------------------
option(LEVELDB_BUILD_TESTS "" OFF)
option(LEVELDB_BUILD_BENCHMARKS "" OFF)
option(LEVELDB_INSTALL "" OFF)
add_subdirectory("${LIBRIME_SOURCE_DIR}/deps/leveldb" leveldb EXCLUDE_FROM_ALL)

# ---------------------------------------------------------------------------
# 依赖 5：marisa-trie
# ---------------------------------------------------------------------------
add_subdirectory("${LIBRIME_SOURCE_DIR}/deps/marisa-trie" marisa-trie EXCLUDE_FROM_ALL)
target_compile_options(marisa PRIVATE
  "-ffile-prefix-map=${LIBRIME_SOURCE_DIR}=.")
set(LIBMARISA marisa)

# ---------------------------------------------------------------------------
# 依赖 6：OpenCC
# ---------------------------------------------------------------------------
option(USE_SYSTEM_MARISA "" ON)
add_subdirectory("${LIBRIME_SOURCE_DIR}/deps/opencc" OpenCC EXCLUDE_FROM_ALL)
# OpenCC 不会把头文件放到 binary dir，手动收集到 <build>/include/opencc
set(OPENCC_SOURCE_DIR "${LIBRIME_SOURCE_DIR}/deps/opencc")
include(OpenccWorkarounds)

# X11 keysym 头文件（librime 自带一份）
include_directories("${LIBRIME_SOURCE_DIR}/include")

# ---------------------------------------------------------------------------
# 可选插件：软链接到 librime/plugins 下，随 rime-static 一并编译
# ---------------------------------------------------------------------------
set(RIME_PLUGINS "")
if(WITH_LUA)
  list(APPEND RIME_PLUGINS librime-lua)
endif()
if(WITH_OCTAGRAM)
  list(APPEND RIME_PLUGINS librime-octagram)
endif()
if(WITH_PREDICT)
  list(APPEND RIME_PLUGINS librime-predict)
endif()
if(WITH_WITOGRAM)
  list(APPEND RIME_PLUGINS librime-witogram)
endif()

foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${LIBRIME_SOURCE_DIR}/plugins/${plugin}")
    if(EXISTS "${PREBUILT_ROOT}/plugins/${plugin}")
      file(CREATE_LINK "${PREBUILT_ROOT}/plugins/${plugin}"
           "${LIBRIME_SOURCE_DIR}/plugins/${plugin}" COPY_ON_ERROR SYMBOLIC)
    else()
      message(FATAL_ERROR
        "Plugin ${plugin} enabled but not found under "
        "${PREBUILT_ROOT}/plugins/${plugin}. See README.")
    endif()
  endif()
endforeach()

# librime-lua 依赖 Lua 5.4 源码（其 thirdparty 分支）：未 pkg-config 命中系统
# lua 时回退编译 thirdparty/lua5.4（见 librime-lua/CMakeLists.txt）。
# 该分支不入 ziyou-ime 版本库，configure 阶段自动浅克隆，已存在则跳过。
if(WITH_LUA)
  set(_lua_thirdparty "${PREBUILT_ROOT}/plugins/librime-lua/thirdparty")
  if(NOT EXISTS "${_lua_thirdparty}/lua5.4")
    message(STATUS "Fetching Lua 5.4 sources (librime-lua thirdparty branch)...")
    execute_process(
      COMMAND git clone --depth=1 -b thirdparty
              https://github.com/hchunhui/librime-lua.git "${_lua_thirdparty}"
      RESULT_VARIABLE _lua_clone_rc)
    if(NOT _lua_clone_rc EQUAL 0)
      message(FATAL_ERROR
        "Failed to fetch Lua sources: run manually —\n"
        "  git clone --depth=1 -b thirdparty https://github.com/hchunhui/librime-lua.git "
        "${_lua_thirdparty}")
    endif()
  endif()
endif()

# ---------------------------------------------------------------------------
# librime 本体（静态库 rime-static）
# ---------------------------------------------------------------------------
# 移动端优化：witogram 内嵌 KenLM 默认 KENLM_MAX_ORDER=16（按 16-gram 预留
# 状态数组）；ziyou 目标模型（zh-moqi / wanxiang-lts）最高 8-gram，降至 8
# 可缩小模型查询窗口与运行时内存占用。witogram 侧以非 FORCE 的 CACHE 变量
# 声明该选项，此处预设值优先生效；另 kenlm 仅将该宏挂在 kenlm 目标上，
# kenlm_util / witogram objs 等直接包含 kenlm 头文件的目标会漏定义，
# 故同时以目录级 compile definition 全量覆盖。
if(WITH_WITOGRAM)
  set(KENLM_MAX_ORDER 8 CACHE STRING "Maximum supported ngram order (mobile-tuned)")
  add_compile_definitions(KENLM_MAX_ORDER=${KENLM_MAX_ORDER})

  # NDK 适配：sentencepiece 顶层 configure_file 生成的 config.h 位于
  # sentencepiece 二进制根目录，已在 src/CMakeLists 的 include 路径上；
  # 前述 glog 接口收窄后不再被截胡，无需额外补丁。
  # boost 侧补 thread 头文件（kenlm multi_progress，见 cmake/Boost.cmake）。
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
# 插件自带的宿主工具（如 librime-predict 的 build_predict）在交叉编译中无意义，关闭
set(BUILD_TOOLS OFF CACHE BOOL "disable plugin host tools for cross build" FORCE)
add_subdirectory("${LIBRIME_SOURCE_DIR}" librime)
target_compile_options(rime-static PRIVATE
  "-ffile-prefix-map=${LIBRIME_SOURCE_DIR}=."
  "-Wno-error=deprecated-declarations")

# 插件 OBJECT 目标补链 Boost 使用要求：librime 核心经 target_link_libraries(Boost::*)
# 获得 signals2 → mpl 等传递头文件路径，而插件 objs 目标未链接这些目标，
# 在模块化 Boost（无单一 include 目录）下会找不到传递依赖头文件，需在此补齐
list(TRANSFORM BOOST_INCLUDE_LIBRARIES PREPEND Boost:: OUTPUT_VARIABLE _boost_targets)
foreach(_plugin_objs rime-lua-objs rime-octagram-objs rime-predict-objs rime-witogram-objs)
  if(TARGET ${_plugin_objs})
    target_link_libraries(${_plugin_objs} ${_boost_targets})
    target_compile_options(${_plugin_objs} PRIVATE
      "-ffile-prefix-map=${LIBRIME_SOURCE_DIR}=.")
  endif()
endforeach()

# ---------------------------------------------------------------------------
# 合并所有静态库为单个 librime.a
# ---------------------------------------------------------------------------
# bundle_static_library 会递归收集 rime-static 的全部静态库依赖
# （boost regex / glog / yaml-cpp / leveldb / marisa / opencc 等），
# 通过 llvm-ar 的 MRI 脚本合并成 <build>/librime.a。
bundle_static_library(rime-static rime)

# 安装合并库 + 公共头文件到 PREBUILT_INSTALL_DIR
if(DEFINED PREBUILT_INSTALL_DIR AND DEFINED ANDROID_ABI)
  set(_merged "${CMAKE_BINARY_DIR}/${CMAKE_STATIC_LIBRARY_PREFIX}rime${CMAKE_STATIC_LIBRARY_SUFFIX}")
  install(FILES "${_merged}"
    DESTINATION "${PREBUILT_INSTALL_DIR}/${ANDROID_ABI}")
  # 公共头文件（rime_api.h 等，排除 *_impl.h / levers API）
  file(GLOB _rime_public_headers "${LIBRIME_SOURCE_DIR}/src/*.h")
  list(FILTER _rime_public_headers EXCLUDE REGEX ".*_impl\\.h$")
  install(FILES ${_rime_public_headers}
    DESTINATION "${PREBUILT_INSTALL_DIR}/include")
  # 安装 librime/include/ 下的额外头文件（utf8.h / darts.h 等）
  install(DIRECTORY "${LIBRIME_SOURCE_DIR}/include/"
    DESTINATION "${PREBUILT_INSTALL_DIR}/include"
    FILES_MATCHING PATTERN "*.h"
                   PATTERN "X11" EXCLUDE)
endif()
