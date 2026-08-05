#!/usr/bin/env bash
# 下载 sherpa-onnx 预编译 Android AAR 到 app/libs/（不入 git，与 libs/<abi>/librime.a 同一惯例）。
#
# 用途：实时语音输入功能（见 docs/实时语音输入可行性方案.md）。
# 出处：https://github.com/k2-fsa/sherpa-onnx/releases（Apache-2.0）
#       1.13.x 起 AAR 首发于 GitHub Release（HuggingFace 仓库同步滞后），
#       因此默认走 GitHub，失败再回退 hf-mirror 国内镜像；
#       可用环境变量 SHERPA_ONNX_BASE 覆盖下载源前缀（须以 /<version>/ 目录组织兼容）。
#
# 完整性：下载后校验 sha256 与结构（classes.jar），任一失败即删除产物，
#        绝不让坏文件残留被下次运行当作"已存在"跳过。
set -euo pipefail

VERSION="1.13.3"
# sha256 锚定值取自上游 GitHub Release v1.13.3（2026-08 下载实测）；升级版本时必须同步更新
EXPECT_SHA256="243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEST="$SCRIPT_DIR/../app/libs/sherpa-onnx-${VERSION}.aar"
FILE_NAME="sherpa-onnx-${VERSION}.aar"

# 候选下载源（按序尝试）：GitHub Release 官方 → hf-mirror 国内镜像
SOURCES=(
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/${FILE_NAME}"
    "https://hf-mirror.com/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/${FILE_NAME}"
)
# 环境变量覆盖：只给一个自定义源
if [ -n "${SHERPA_ONNX_BASE:-}" ]; then
    SOURCES=("$SHERPA_ONNX_BASE/$FILE_NAME")
fi

verify() {
    local actual listing
    actual=$(shasum -a 256 "$DEST" | awk '{print $1}')
    if [ "$actual" != "$EXPECT_SHA256" ]; then
        echo "错误：sha256 不匹配（期望 $EXPECT_SHA256，实际 $actual）" >&2
        return 1
    fi
    # 先取完整列表再匹配，避免 grep -q 提前退出把 SIGPIPE 传回 unzip（pipefail 误报）
    listing=$(unzip -l "$DEST" 2>/dev/null || true)
    case "$listing" in
        *classes.jar*) : ;;
        *)
            echo "错误：AAR 结构不完整（缺少 classes.jar）" >&2
            return 1
            ;;
    esac
}

# 已存在：校验通过直接用；校验不过则删除重下
if [ -f "$DEST" ]; then
    if verify; then
        echo "已存在且校验通过，跳过下载: $DEST"
        exit 0
    fi
    rm -f "$DEST"
fi

mkdir -p "$(dirname "$DEST")"
downloaded=0
for url in "${SOURCES[@]}"; do
    echo "尝试下载: $url"
    # --http1.1：部分网络环境 HTTP/2 下载 GitHub Release 大文件会中断
    if curl -L --http1.1 --retry 2 --fail --progress-bar -o "$DEST" "$url"; then
        downloaded=1
        break
    fi
    rm -f "$DEST"
done

if [ "$downloaded" -ne 1 ]; then
    echo "错误：全部下载源均失败（可用 SHERPA_ONNX_BASE 指定其他下载源）" >&2
    exit 1
fi

if ! verify; then
    rm -f "$DEST"
    exit 1
fi

echo "完成（sha256 已校验）: $DEST"
