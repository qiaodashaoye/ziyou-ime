# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 字由输入法 —— librime 预编译库 superbuild
#
# 该 CMake 工程从源码交叉编译 librime 及其全部依赖（boost / glog /
# yaml-cpp / leveldb / marisa-trie / opencc），并将它们合并成一个
# 单独的静态库 librime.a，供 simplerime 的 JNI 层直接链接。
#
# 本工程不直接被 Android Gradle 调用，而是由 build.sh 针对每个 ABI
# 单独配置 / 构建。构建产物（librime.a + 头文件）最终安装到
# simplerime/libs/ 目录下。

cmake_minimum_required(VERSION 3.18.0)

project(librime_prebuilt VERSION 1.0.0)

# ---------------------------------------------------------------------------
# 入参（由 build.sh 通过 -D 传入）
# ---------------------------------------------------------------------------
# LIBRIME_SOURCE_DIR  librime 源码根目录（含 deps/ 子模块）
# PREBUILT_INSTALL_DIR 合并静态库 + 头文件的安装目录（即 simplerime/libs）
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

# for reproducible build（与 Trime 保持一致）
add_link_options("LINKER:--hash-style=both,--build-id=none")

# 本工程的 cmake 辅助模块（Find* shim / Boost / Bundle）需要排在最前，
# 以覆盖 librime 自带的 Find*.cmake（后者面向系统安装的库）。
set(CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake" ${CMAKE_MODULE_PATH})

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

foreach(plugin ${RIME_PLUGINS})
  if(NOT EXISTS "${LIBRIME_SOURCE_DIR}/plugins/${plugin}")
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/plugins/${plugin}")
      file(CREATE_LINK "${CMAKE_CURRENT_SOURCE_DIR}/plugins/${plugin}"
           "${LIBRIME_SOURCE_DIR}/plugins/${plugin}" COPY_ON_ERROR SYMBOLIC)
    else()
      message(FATAL_ERROR
        "Plugin ${plugin} enabled but not found under "
        "${CMAKE_CURRENT_SOURCE_DIR}/plugins/${plugin}. See README.")
    endif()
  endif()
endforeach()

# ---------------------------------------------------------------------------
# librime 本体（静态库 rime-static）
# ---------------------------------------------------------------------------
option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory("${LIBRIME_SOURCE_DIR}" librime)
target_compile_options(rime-static PRIVATE
  "-ffile-prefix-map=${LIBRIME_SOURCE_DIR}=."
  "-Wno-error=deprecated-declarations")

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
endif()
