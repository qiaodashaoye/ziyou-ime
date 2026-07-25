---
kind: external_dependency
name: Android 输入法服务框架
slug: android-ime-framework
category: external_dependency
category_hints:
    - framework_behavior
    - client_constraint
scope:
    - '**'
source_files:
    - app/src/main/AndroidManifest.xml
    - app/src/main/res/xml/input_method.xml
---

### Android 输入法服务（InputMethodService）
- **角色**：字由作为系统输入法服务运行，继承自 `InputMethodService`
- **权限要求**：需要 `BIND_INPUT_METHOD` 权限，无需额外的悬浮窗权限（悬浮键盘基于 IME 窗口机制）
- **游戏兼容**：IME 窗口天然有权显示在游戏之上，通过 `TOUCHABLE_INSETS_REGION` 实现触摸穿透
- **生命周期**：需正确处理 `onCreate`、`onStartInputView`、`onDestroy` 等生命周期回调
- **兼容性约束**：不同厂商 ROM 对 touchableRegion 行为存在差异，需多机型真机测试