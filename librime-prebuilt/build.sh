#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 字由输入法 —— librime 预编译库构建脚本
#
# 用法:
#   ./build.sh [abi ...]
#
# 环境变量:
#   ANDROID_NDK_HOME / ANDROID_NDK  Android NDK 根目录（未设置时自动探测）
#   ANDROID_PLATFORM               目标 API level（默认 24，与 minSdk 对齐）
#   LIBRIME_SOURCE_DIR             librime 源码目录（默认 ./librime，子模块）
#   LIBRIME_VERSION                当 LIBRIME_SOURCE_DIR 不存在时用于 clone 的 tag/commit/branch
#   WITH_LUA / WITH_OCTAGRAM / WITH_PREDICT  设为 ON 以编译对应可选插件
#
# 产物:
#   ../libs/<abi>/librime.a       合并后的静态库
#   ../libs/include/rime_api.h    公共头文件
#
# 仅支持在 macOS / Linux 主机上构建。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ZIYOU_IME_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ---------------------------------------------------------------------------
# 参数与默认值
# ---------------------------------------------------------------------------
DEFAULT_ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
if [ "$#" -gt 0 ]; then
  ABIS=("$@")
else
  ABIS=("${DEFAULT_ABIS[@]}")
fi

ANDROID_PLATFORM="${ANDROID_PLATFORM:-24}"
LIBRIME_SOURCE_DIR="${LIBRIME_SOURCE_DIR:-${SCRIPT_DIR}/librime}"
LIBRIME_VERSION="${LIBRIME_VERSION:-master}"
WITH_LUA="${WITH_LUA:-OFF}"
WITH_OCTAGRAM="${WITH_OCTAGRAM:-OFF}"
WITH_PREDICT="${WITH_PREDICT:-OFF}"

BUILD_ROOT="${SCRIPT_DIR}/build"
INSTALL_DIR="${ZIYOU_IME_DIR}/libs"

# ---------------------------------------------------------------------------
# 探测 NDK
# ---------------------------------------------------------------------------
find_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "${ANDROID_NDK_HOME}" ]; then
    echo "${ANDROID_NDK_HOME}"; return
  fi
  if [ -n "${ANDROID_NDK:-}" ] && [ -d "${ANDROID_NDK}" ]; then
    echo "${ANDROID_NDK}"; return
  fi
  # 从 ziyou-ime/local.properties 读取 sdk.dir，再找 ndk/<version>
  local sdk_dir=""
  if [ -f "${ZIYOU_IME_DIR}/local.properties" ]; then
    sdk_dir="$(grep -E '^\s*sdk\.dir=' "${ZIYOU_IME_DIR}/local.properties" | sed 's/^[^=]*=//' | tr -d '\r')"
    # local.properties 会转义冒号（macOS 路径无冒号，通常无碍）
    sdk_dir="${sdk_dir//\\:/:}"
  fi
  if [ -z "${sdk_dir}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
  fi
  if [ -z "${sdk_dir}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    sdk_dir="${ANDROID_HOME}"
  fi
  if [ -n "${sdk_dir}" ] && [ -d "${sdk_dir}/ndk" ]; then
    # 取版本号最大的一个
    local latest
    latest="$(ls -1 "${sdk_dir}/ndk" 2>/dev/null | sort -V | tail -n 1)"
    if [ -n "${latest}" ]; then
      echo "${sdk_dir}/ndk/${latest}"; return
    fi
  fi
  echo ""
}

NDK_DIR="$(find_ndk)"
if [ -z "${NDK_DIR}" ] || [ ! -f "${NDK_DIR}/build/cmake/android.toolchain.cmake" ]; then
  echo "错误: 未找到 Android NDK。" >&2
  echo "请设置 ANDROID_NDK_HOME 指向 NDK 根目录，或在 ziyou-ime/local.properties 配置 sdk.dir。" >&2
  exit 1
fi
TOOLCHAIN="${NDK_DIR}/build/cmake/android.toolchain.cmake"
echo ">> 使用 NDK: ${NDK_DIR}"

# ---------------------------------------------------------------------------
# 探测 CMake（优先使用 Android SDK 自带的 cmake）
# ---------------------------------------------------------------------------
find_cmake() {
  # 检查 cmake 是否在 PATH 中
  if command -v cmake &>/dev/null; then
    echo "cmake"; return
  fi
  # 从 Android SDK 查找 cmake
  local sdk_dir=""
  if [ -f "${ZIYOU_IME_DIR}/local.properties" ]; then
    sdk_dir="$(grep -E '^\s*sdk\.dir=' "${ZIYOU_IME_DIR}/local.properties" | sed 's/^[^=]*=//' | tr -d '\r')"
    sdk_dir="${sdk_dir//\\:/:}"
  fi
  if [ -z "${sdk_dir}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
  fi
  if [ -z "${sdk_dir}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    sdk_dir="${ANDROID_HOME}"
  fi
  if [ -n "${sdk_dir}" ] && [ -d "${sdk_dir}/cmake" ]; then
    local cmake_bin
    cmake_bin="$(ls -1d "${sdk_dir}/cmake/"*/bin/cmake 2>/dev/null | sort -V | tail -n 1)"
    if [ -n "${cmake_bin}" ] && [ -x "${cmake_bin}" ]; then
      echo "${cmake_bin}"; return
    fi
  fi
  echo ""; 
}

CMAKE_BIN="$(find_cmake)"
if [ -z "${CMAKE_BIN}" ]; then
  echo "错误: 未找到 cmake。" >&2
  echo "请安装 cmake 或确保 Android SDK cmake 组件已安装。" >&2
  exit 1
fi
echo ">> 使用 CMake: ${CMAKE_BIN}"

# ---------------------------------------------------------------------------
# 准备 librime 源码
# ---------------------------------------------------------------------------
prepare_source() {
  if [ -f "${LIBRIME_SOURCE_DIR}/CMakeLists.txt" ]; then
    # 已存在（子模块或手动放置），确保 deps 已初始化
    if [ -d "${LIBRIME_SOURCE_DIR}/.git" ] && [ ! -f "${LIBRIME_SOURCE_DIR}/deps/glog/CMakeLists.txt" ]; then
      echo ">> 初始化 librime 依赖子模块 ..."
      git -C "${LIBRIME_SOURCE_DIR}" submodule update --init --recursive
    fi
  else
    echo ">> 克隆 librime (${LIBRIME_VERSION}) 到 ${LIBRIME_SOURCE_DIR} ..."
    git clone --recursive --branch "${LIBRIME_VERSION}" \
      https://github.com/rime/librime.git "${LIBRIME_SOURCE_DIR}" \
      || git clone --recursive "https://github.com/rime/librime.git" "${LIBRIME_SOURCE_DIR}"
  fi

  # 校验关键依赖存在
  for dep in glog yaml-cpp leveldb marisa-trie opencc; do
    if [ ! -f "${LIBRIME_SOURCE_DIR}/deps/${dep}/CMakeLists.txt" ]; then
      echo "错误: 依赖 ${dep} 缺失 (${LIBRIME_SOURCE_DIR}/deps/${dep})。" >&2
      echo "请在 librime 源码目录执行: git submodule update --init --recursive" >&2
      exit 1
    fi
  done
}

prepare_source

# ---------------------------------------------------------------------------
# 逐 ABI 构建
# ---------------------------------------------------------------------------
JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)"

for ABI in "${ABIS[@]}"; do
  echo ""
  echo "==================================================================="
  echo ">> 构建 ABI: ${ABI}"
  echo "==================================================================="
  BUILD_DIR="${BUILD_ROOT}/${ABI}"
  mkdir -p "${BUILD_DIR}"

  # 指向 superbuild/ 子目录（已重命名以防止 Android Studio 自动检测）
  "${CMAKE_BIN}" -S "${SCRIPT_DIR}/superbuild" -B "${BUILD_DIR}" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM="android-${ANDROID_PLATFORM}" \
    -DANDROID_STL="c++_static" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="${BUILD_DIR}/install" \
    -DLIBRIME_SOURCE_DIR="${LIBRIME_SOURCE_DIR}" \
    -DPREBUILT_INSTALL_DIR="${INSTALL_DIR}" \
    -DWITH_LUA="${WITH_LUA}" \
    -DWITH_OCTAGRAM="${WITH_OCTAGRAM}" \
    -DWITH_PREDICT="${WITH_PREDICT}"

  # 触发合并静态库（bundling_target 为 ALL 目标）
  "${CMAKE_BIN}" --build "${BUILD_DIR}" --parallel "${JOBS}"

  # 安装合并库 + 头文件到 ../libs
  "${CMAKE_BIN}" --install "${BUILD_DIR}"

  echo ">> ${ABI} 完成: ${INSTALL_DIR}/${ABI}/librime.a"
done

echo ""
echo ">> 全部完成。产物位于: ${INSTALL_DIR}"
ls -R "${INSTALL_DIR}" 2>/dev/null || true
