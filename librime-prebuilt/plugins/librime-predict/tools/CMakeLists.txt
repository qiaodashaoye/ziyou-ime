set(EXECUTABLE_OUTPUT_PATH ${PROJECT_BINARY_DIR}/bin)

include_directories(../src)

add_executable(build_predict
  build_predict.cc
  $<TARGET_OBJECTS:rime-predict-objs>)
target_link_libraries(build_predict
  ${rime_library}
  ${rime_dict_library})

# 字由定制：召回率 A/B 查询工具（宿主验收用，见 docs/联想功能优化调研与方案.md §7.7）
add_executable(query_predict
  query_predict.cc
  $<TARGET_OBJECTS:rime-predict-objs>)
target_link_libraries(query_predict
  ${rime_library}
  ${rime_dict_library})
