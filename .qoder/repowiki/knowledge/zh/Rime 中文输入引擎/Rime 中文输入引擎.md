---
kind: external_dependency
name: Rime 中文输入引擎
slug: rime-engine
category: external_dependency
category_hints:
    - vendor_identity
scope:
    - '**'
---

项目集成 Rime 输入法引擎作为中文输入的核心引擎，通过 RimeConfigManager 管理配置。支持候选词页面大小等配置项，但当前只有 setConfigBool 方法可用，menu/page_size 等字段暂无法运行时写入。引擎配置文件采用 YAML 格式，默认配置在 default.yaml 中定义。