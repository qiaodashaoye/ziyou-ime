#!/usr/bin/env bash
# 「云朵奶油」皮肤包打包脚本：把当前目录打成 .zyskin（zip）。
#
# 包内结构（字由输入法皮肤规范 specVersion=1）：
#   skin.json / preview.png / images/bg.png
# 开发脚本（build_assets.py / pack.sh）不入包——皮肤包只允许
# json / 位图 / 字体，含其他扩展名会被 SkinPackLoader 整包拒绝。
set -euo pipefail

cd "$(dirname "$0")"
SKIN_ID="$(basename "$PWD")"
OUT="../${SKIN_ID}.zyskin"

rm -f "$OUT"
zip -q -X -r "$OUT" skin.json preview.png images
echo "已生成: $(cd .. && pwd)/${SKIN_ID}.zyskin"
unzip -l "$OUT"
