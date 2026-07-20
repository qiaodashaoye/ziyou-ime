# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# OpenCC shim：OpenccWorkarounds.cmake 已把头文件收集到 <build>/include，
# 库目标名为 libopencc（OpenCC 的静态库目标）。

set(Opencc_FOUND TRUE)
set(Opencc_LIBRARY libopencc)
set(Opencc_INCLUDE_PATH "${CMAKE_BINARY_DIR}/include")
