# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# OpenCC 不会把头文件安装到 binary dir，这里手动拷贝到
# <build>/include/opencc，供 librime 的 gear 模块 include。
# OPENCC_SOURCE_DIR 由上层 CMakeLists 设置为 OpenCC 源码根目录。

file(GLOB LIBOPENCC_HEADERS
  "${OPENCC_SOURCE_DIR}/src/*.hpp"
  "${CMAKE_BINARY_DIR}/OpenCC/src/opencc_config.h")
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")
