#!/usr/bin/env bash
#
# 字由输入法 —— 正式发布 APK 一键构建脚本
#
# 用法:
#   ./scripts/build-release.sh [选项]
#
# 选项:
#   --abis <list>       逗号分隔的 ABI 列表（默认 arm64-v8a）
#   --rebuild-native    先重建 librime 预编译库（调用 librime-prebuilt/build.sh，
#                       复用其 NDK/CMake 自动探测逻辑，强制 WITH_PREDICT=ON
#                       以与 app 侧 -DWITH_PREDICT=ON 保持一致）
#   --skip-tests        跳过单元测试（仅限本地快速验证，正式发布禁用）
#
# 产物:
#   dist/ziyou-ime-v<versionName>-<abis>-release.apk 及 .sha256
#
# 前置条件:
#   1. 根目录存在 keystore.properties（参考 keystore.properties.template）
#   2. 每个目标 ABI 已有 libs/<abi>/librime.a（或使用 --rebuild-native）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
ABIS="arm64-v8a"
REBUILD_NATIVE=0
SKIP_TESTS=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --abis) ABIS="$2"; shift 2 ;;
    --rebuild-native) REBUILD_NATIVE=1; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    *) echo "未知选项: $1" >&2; exit 1 ;;
  esac
done

IFS=',' read -r -a ABI_ARR <<< "${ABIS}"

# ---------------------------------------------------------------------------
# JDK：gradlew 启动需要 JAVA_HOME；未设置时回退到 Android Studio JBR
# （与 gradle.properties 的 org.gradle.java.installations.paths 保持一致）
# ---------------------------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -x "${JBR}/bin/java" ]; then
    export JAVA_HOME="${JBR}"
    echo ">> 未设置 JAVA_HOME，使用 Android Studio JBR: ${JAVA_HOME}"
  else
    echo "错误: 未设置 JAVA_HOME 且未找到 Android Studio JBR。" >&2
    exit 1
  fi
fi

# ---------------------------------------------------------------------------
# 0. 前置检查：签名配置
# ---------------------------------------------------------------------------
if [ ! -f "${ROOT_DIR}/keystore.properties" ]; then
  echo "错误: 缺少 keystore.properties（发布签名配置）。" >&2
  echo "请复制 keystore.properties.template 并填写实际密钥库信息。" >&2
  exit 1
fi
STORE_FILE="$(grep -E '^\s*storeFile=' keystore.properties | sed 's/^[^=]*=//' | tr -d '\r')"
# 相对路径按仓库根解析（与 build.gradle.kts 的 rootProject.file 一致）
case "${STORE_FILE}" in
  /*) : ;;
  *) STORE_FILE="${ROOT_DIR}/${STORE_FILE}" ;;
esac
if [ ! -f "${STORE_FILE}" ]; then
  echo "错误: keystore.properties 指向的密钥库不存在: ${STORE_FILE}" >&2
  exit 1
fi
echo ">> 签名密钥库: ${STORE_FILE}"

# ---------------------------------------------------------------------------
# 1. Native 预编译库（可选重建；NDK/CMake 探测复用 librime-prebuilt/build.sh）
# ---------------------------------------------------------------------------
if [ "${REBUILD_NATIVE}" -eq 1 ]; then
  echo ">> 重建 librime 预编译库: ${ABI_ARR[*]}（WITH_PREDICT=ON）"
  ( cd "${ROOT_DIR}/librime-prebuilt" && WITH_PREDICT=ON ./build.sh "${ABI_ARR[@]}" )
fi

for ABI in "${ABI_ARR[@]}"; do
  if [ ! -f "${ROOT_DIR}/libs/${ABI}/librime.a" ]; then
    echo "错误: 缺少 libs/${ABI}/librime.a。" >&2
    echo "请先执行: cd librime-prebuilt && WITH_PREDICT=ON ./build.sh ${ABI}" >&2
    echo "或本脚本追加 --rebuild-native。" >&2
    exit 1
  fi
done
echo ">> 预编译库检查通过: ${ABI_ARR[*]}"

# ---------------------------------------------------------------------------
# 2. 单元测试门禁（38 用例基线）
# ---------------------------------------------------------------------------
if [ "${SKIP_TESTS}" -eq 0 ]; then
  echo ">> 运行全量单元测试 ..."
  ./gradlew :core-logic:testDebugUnitTest :app:testDebugUnitTest
else
  echo ">> [警告] 已跳过单元测试（--skip-tests），正式发布请勿使用。"
fi

# ---------------------------------------------------------------------------
# 3. 构建签名 Release APK
# ---------------------------------------------------------------------------
echo ">> 构建 Release APK（ABI: ${ABIS}）..."
./gradlew :app:assembleRelease "-Pziyou.abis=${ABIS}"

APK_SRC="${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk"
if [ ! -f "${APK_SRC}" ]; then
  # 未签名说明 signingConfig 未生效，视为失败
  echo "错误: 未找到已签名产物 app-release.apk（仅有 unsigned？请检查 keystore.properties）。" >&2
  ls -1 "${ROOT_DIR}/app/build/outputs/apk/release/" >&2 || true
  exit 1
fi

# ---------------------------------------------------------------------------
# 4. 归档产物 + 校验
# ---------------------------------------------------------------------------
VERSION_NAME="$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | head -n1 | sed 's/.*"\(.*\)".*/\1/')"
DIST_DIR="${ROOT_DIR}/dist"
mkdir -p "${DIST_DIR}"
ABIS_TAG="${ABIS//,/+}"
APK_OUT="${DIST_DIR}/ziyou-ime-v${VERSION_NAME}-${ABIS_TAG}-release.apk"
cp "${APK_SRC}" "${APK_OUT}"
shasum -a 256 "${APK_OUT}" | tee "${APK_OUT}.sha256"

echo ""
echo ">> 发布构建完成:"
ls -lh "${APK_OUT}"
