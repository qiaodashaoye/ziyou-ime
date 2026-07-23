# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 下载并解压指定版本的 Boost（cmake 打包版），提供 header + 编译型库
# （regex 等）。移植自 Trime 的 app/src/main/jni/cmake/Boost.cmake。

set(BOOST_VERSION 1.90.0)

# 父目录（librime-prebuilt/）是 Boost 下载和解压的位置
set(PREBUILT_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")

if(NOT EXISTS "${PREBUILT_ROOT}/boost-${BOOST_VERSION}.tar.xz")
  # 优先使用 GitHub 镜像（中国大陆网络更友好），直连作为兜底
  set(_boost_urls
    "https://ghfast.top/https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "https://gh-proxy.com/https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
  )
  set(_boost_download_ok FALSE)
  foreach(_url IN LISTS _boost_urls)
    message(STATUS "Downloading Boost ${BOOST_VERSION} from: ${_url}")
    file(
      DOWNLOAD "${_url}"
      "${PREBUILT_ROOT}/boost-${BOOST_VERSION}.tar.xz"
      EXPECTED_HASH
        SHA256=aca59f889f0f32028ad88ba6764582b63c916ce5f77b31289ad19421a96c555f
      SHOW_PROGRESS
      STATUS _dl_status)
    list(GET _dl_status 0 _dl_code)
    if(_dl_code EQUAL 0)
      set(_boost_download_ok TRUE)
      break()
    else()
      message(WARNING "Download failed (code=${_dl_code}): ${_url}")
      file(REMOVE "${PREBUILT_ROOT}/boost-${BOOST_VERSION}.tar.xz")
    endif()
  endforeach()
  if(NOT _boost_download_ok)
    message(FATAL_ERROR "Failed to download Boost ${BOOST_VERSION} from all mirrors.")
  endif()

  message(STATUS "Remove older version Boost")
  file(REMOVE_RECURSE "${PREBUILT_ROOT}/boost")
endif()

if(NOT EXISTS "${PREBUILT_ROOT}/boost")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ......")
  file(ARCHIVE_EXTRACT INPUT "${PREBUILT_ROOT}/boost-${BOOST_VERSION}.tar.xz" DESTINATION
       "${PREBUILT_ROOT}")
  file(RENAME "${PREBUILT_ROOT}/boost-${BOOST_VERSION}" "${PREBUILT_ROOT}/boost")
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    preprocessor
    range
    regex
    scope_exit
    signals2
    unordered
    utility
    uuid
    vmd)

add_subdirectory("${PREBUILT_ROOT}/boost" boost EXCLUDE_FROM_ALL)
