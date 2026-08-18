# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# bundle_static_library(<target> <bundled_name>)
#
# 递归收集 <target>（及其全部静态库依赖）的归档文件，用 ar 的 MRI 脚本
# 合并成单个静态库 <build>/lib<bundled_name>.a。
#
# 该实现是社区广泛使用的方案（cristianadam / static bundling gist），
# 适配 Android NDK 的 llvm-ar（支持 `-M` 读取 MRI 脚本）。
# 仅支持 Unix 构建主机（macOS / Linux），依赖 /bin/sh 完成输入重定向。

function(bundle_static_library tgt_name bundled_tgt_name)
  list(APPEND static_libs ${tgt_name})

  # 递归收集依赖中的静态库目标
  function(_recursively_collect_dependencies input_target)
    set(_input_link_libraries LINK_LIBRARIES)
    get_target_property(_input_type ${input_target} TYPE)
    if(${_input_type} STREQUAL "INTERFACE_LIBRARY")
      set(_input_link_libraries INTERFACE_LINK_LIBRARIES)
    endif()
    get_target_property(public_dependencies ${input_target} ${_input_link_libraries})
    # 补充收集 INTERFACE-only 依赖：部分第三方静态库（如 witogram 内嵌的
    # sentencepiece-static）以 target_link_libraries(... INTERFACE ...) 声明
    # 依赖（absl 别名目标链），仅存于 INTERFACE_LINK_LIBRARIES，不读则
    # 合并产物缺符号。
    get_target_property(_iface_dependencies ${input_target} INTERFACE_LINK_LIBRARIES)
    if(_iface_dependencies)
      list(APPEND public_dependencies ${_iface_dependencies})
    endif()
    foreach(dependency ${public_dependencies})
      if(TARGET ${dependency})
        get_target_property(alias ${dependency} ALIASED_TARGET)
        if(TARGET ${alias})
          set(dependency ${alias})
        endif()
        get_target_property(_type ${dependency} TYPE)
        if(${_type} STREQUAL "STATIC_LIBRARY")
          list(APPEND static_libs ${dependency})
        endif()

        get_property(library_already_added
          GLOBAL PROPERTY _${tgt_name}_static_bundle_${dependency})
        if(NOT library_already_added)
          set_property(GLOBAL PROPERTY _${tgt_name}_static_bundle_${dependency} ON)
          _recursively_collect_dependencies(${dependency})
        endif()
      endif()
    endforeach()
    set(static_libs ${static_libs} PARENT_SCOPE)
  endfunction()

  _recursively_collect_dependencies(${tgt_name})
  list(REMOVE_DUPLICATES static_libs)

  set(bundled_tgt_full_name
    ${CMAKE_BINARY_DIR}/${CMAKE_STATIC_LIBRARY_PREFIX}${bundled_tgt_name}${CMAKE_STATIC_LIBRARY_SUFFIX})

  # 生成 MRI 脚本
  set(_mri_in ${CMAKE_BINARY_DIR}/${bundled_tgt_name}.ar.in)
  file(WRITE ${_mri_in} "CREATE ${bundled_tgt_full_name}\n")
  foreach(tgt IN LISTS static_libs)
    file(APPEND ${_mri_in} "ADDLIB $<TARGET_FILE:${tgt}>\n")
  endforeach()
  file(APPEND ${_mri_in} "SAVE\n")
  file(APPEND ${_mri_in} "END\n")

  # 用 file(GENERATE) 展开 $<TARGET_FILE:...> 生成器表达式
  set(_mri ${CMAKE_BINARY_DIR}/${bundled_tgt_name}.ar)
  file(GENERATE OUTPUT ${_mri} INPUT ${_mri_in})

  set(ar_tool ${CMAKE_AR})
  if(CMAKE_INTERPROCEDURAL_OPTIMIZATION)
    set(ar_tool ${CMAKE_CXX_COMPILER_AR})
  endif()

  add_custom_command(
    OUTPUT ${bundled_tgt_full_name}
    COMMAND /bin/sh -c "${ar_tool} -M < ${_mri}"
    DEPENDS ${tgt_name} ${_mri}
    COMMENT "Bundling ${CMAKE_STATIC_LIBRARY_PREFIX}${bundled_tgt_name}${CMAKE_STATIC_LIBRARY_SUFFIX}"
    VERBATIM)

  add_custom_target(bundling_target ALL DEPENDS ${bundled_tgt_full_name})
  add_dependencies(bundling_target ${tgt_name})

  add_library(${bundled_tgt_name} STATIC IMPORTED GLOBAL)
  set_target_properties(${bundled_tgt_name}
    PROPERTIES
      IMPORTED_LOCATION ${bundled_tgt_full_name}
      INTERFACE_INCLUDE_DIRECTORIES
        $<TARGET_PROPERTY:${tgt_name},INTERFACE_INCLUDE_DIRECTORIES>)
  add_dependencies(${bundled_tgt_name} bundling_target)
endfunction()
