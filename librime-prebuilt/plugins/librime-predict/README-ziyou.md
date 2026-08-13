# librime-predict 收编说明（字由输入法 vendor 版）

> 本目录自 2026-08-13 起由 git 子模块改为**主仓直接纳管**（方案 B，
> 决策记录见 `docs/联想功能优化调研与方案.md` §7.7 与提交历史）。
> 原因：子模块语义下主仓只记录上游 SHA 指针，字由定制补丁无法随主仓
> 提交/分发，新克隆会丢失全部定制。

## 上游基线

- 项目：https://github.com/rime/librime-predict
- 基线提交：`920bd41` "make tools optional (#28)"（收编时 HEAD）
- License：上游 LICENSE 保留，商用/再分发遵守其条款

## 字由定制清单（相对上游的改动，同步上游时须重放）

| 文件 | 定制内容 |
|---|---|
| `src/predict_engine.cc` | `Predict()` 最长后缀回退：整串 miss 时按 UTF-8 字逐步截前缀重试（最短保留 2 字），配套 `NextUtf8CharOffset` 辅助函数；重编预编译库后生效 |
| `tools/query_predict.cc` | 新增召回率 A/B 查询工具（宿主验收用，`--fallback` 模拟运行期回退） |
| `tools/CMakeLists.txt` | 注册 `query_predict` 可执行目标 |

构建侧配套定制位于主仓 `librime-prebuilt/superbuild/CMakeLists.txt`
（非本目录）：插件 objs 目标补链 Boost 使用要求、交叉编译强制
`BUILD_TOOLS=OFF`（宿主工具不进 Android 产物）。

## 上游同步规程（手工，上游极不活跃）

1. `git clone` 上游新版，与本目录 diff；
2. 逐文件合入上游改动（注意保留上表定制）；
3. 更新本文件「基线提交」；
4. 宿主 `make` + 交叉编译 `WITH_PREDICT=ON ./build.sh arm64-v8a` 双验证。

## 构建集成要点

- Android 交叉编译：superbuild `WITH_PREDICT=ON` 时经
  `librime/plugins/librime-predict` 符号链接引到本目录（该符号链接当前为
  绝对路径，换机器需重建为相对路径 `../../plugins/librime-predict`）；
- 宿主工具链（build_predict / query_predict）：`librime-prebuilt/librime`
  下 `make deps && make`，插件 `BUILD_TOOLS` 默认 ON。
