# SPDX-FileCopyrightText: 2015 - 2025 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# 字由输入法 —— librime 预编译库 Makefile
#
# 该 Makefile 提供 `make librime` 等构建入口，构建产物
# 直接安装到 ziyou-ime/libs/ 目录，供 app 的 JNI 层链接。

.PHONY: all librime clean distclean lua octagram predict witogram

# 默认目标：编译全部 ABI 的 librime.a
all: librime

# 从源码交叉编译 librime 及依赖，合并为单个 librime.a
librime:
	./build.sh

# 带可选插件的构建示例
lua:
	WITH_LUA=ON ./build.sh

octagram:
	WITH_OCTAGRAM=ON ./build.sh

predict:
	WITH_PREDICT=ON ./build.sh

witogram:
	WITH_WITOGRAM=ON ./build.sh

# 只清理各 ABI 的中间构建目录（保留已安装到 libs/ 的产物与下载的 boost）
clean:
	rm -rf build

# 彻底清理：中间构建 + 已安装产物 + 克隆的 librime 源码（若为脚本 clone）
distclean: clean
	rm -rf ../libs/arm64-v8a ../libs/armeabi-v7a ../libs/x86_64 ../libs/include
