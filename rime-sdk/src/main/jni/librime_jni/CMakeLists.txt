# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.22.1)
project("rime_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 可选模块开关
option(WITH_LUA "Enable Lua module" OFF)
option(WITH_OCTAGRAM "Enable Octagram module" OFF)
option(WITH_PREDICT "Enable Predict module" OFF)
option(WITH_WITOGRAM "Enable Witogram grammar module" OFF)
option(WITH_OPENCC "Enable OpenCC module" OFF)

# librime 头文件和静态库路径
# 使用绝对路径指向 ziyou-ime/libs/ 目录
get_filename_component(ZIYOU_IME_ROOT "${CMAKE_SOURCE_DIR}/../../../../../" ABSOLUTE)
set(RIME_INCLUDE_DIR "${ZIYOU_IME_ROOT}/libs/include")
set(RIME_LIB_DIR "${ZIYOU_IME_ROOT}/libs/${ANDROID_ABI}")

message(STATUS "RIME_INCLUDE_DIR = ${RIME_INCLUDE_DIR}")
message(STATUS "RIME_LIB_DIR = ${RIME_LIB_DIR}")

# 包含目录
include_directories(
    ${RIME_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# JNI 源文件
set(RIME_JNI_SOURCES
    rime_jni.cc
    config.cc
)

# 添加条件编译定义
if(WITH_LUA)
    add_definitions(-DWITH_LUA)
endif()
if(WITH_OCTAGRAM)
    add_definitions(-DWITH_OCTAGRAM)
endif()
if(WITH_PREDICT)
    add_definitions(-DWITH_PREDICT)
endif()
if(WITH_WITOGRAM)
    add_definitions(-DWITH_WITOGRAM)
endif()
if(WITH_OPENCC)
    add_definitions(-DWITH_OPENCC)
endif()

# 构建共享库
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})

# 链接器优化：死代码消除 + 16KB 页面对齐（Android 15+ 要求）
target_link_options(rime_jni PRIVATE
    "LINKER:--gc-sections"
    "LINKER:--exclude-libs,ALL"
    "LINKER:-z,max-page-size=16384"
)

# 链接librime静态库
set(RIME_LIB_FILE "${RIME_LIB_DIR}/librime.a")
if(EXISTS "${RIME_LIB_FILE}")
    message(STATUS "Found librime: ${RIME_LIB_FILE}")
    target_link_libraries(rime_jni "${RIME_LIB_FILE}")
else()
    message(FATAL_ERROR
        "librime.a not found at: ${RIME_LIB_FILE}\n"
        "Please build librime for ${ANDROID_ABI} and place it in libs/${ANDROID_ABI}/librime.a\n"
        "See librime-prebuilt/README.md for build instructions.")
endif()

# 链接系统库（z：witogram 内嵌 kenlm 的 gzip 解压路径依赖 zlib，
# NDK sysroot 提供静态 libz.a）
target_link_libraries(rime_jni
    log
    z
)
