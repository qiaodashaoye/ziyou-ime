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
option(WITH_OPENCC "Enable OpenCC module" OFF)

# librime 头文件路径（根据实际预编译路径调整）
set(RIME_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/../../../../libs/include")
set(RIME_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../../libs/${ANDROID_ABI}")

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
if(WITH_OPENCC)
    add_definitions(-DWITH_OPENCC)
endif()

# 构建共享库
add_library(rime_jni SHARED ${RIME_JNI_SOURCES})

# 链接librime静态库（如果存在）
find_library(RIME_LIB rime PATHS ${RIME_LIB_DIR} NO_DEFAULT_PATH)
if(RIME_LIB)
    target_link_libraries(rime_jni ${RIME_LIB})
else()
    # 如果没有预编译库，尝试从系统路径查找
    message(WARNING "librime not found at ${RIME_LIB_DIR}, using stub mode")
endif()

# 链接系统库
target_link_libraries(rime_jni
    log
)
