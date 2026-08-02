---
kind: external_dependency
name: Android 框架与系统 API
slug: android-framework
category: external_dependency
category_hints:
    - vendor_identity
scope:
    - '**'
---

项目基于 Android 平台开发，使用 Android Framework 提供的输入法服务（InputMethodService）、SharedPreferences 数据持久化、Material Design 组件库。核心依赖包括：InputMethodManager 用于输入法状态检测与切换、Settings.Secure.DEFAULT_INPUT_METHOD 读取当前激活输入法、View 系统用于纯代码布局绘制。所有 UI 组件均为自定义 View，不依赖 XML 布局文件。