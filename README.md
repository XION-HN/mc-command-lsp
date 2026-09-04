# MC Bedrock 命令 LSP（Java 模块）

目标：给 haisa-editor（或其通用 LSP 客户端）提供《我的世界基岩版》命令补全/提示，
Java 实现、独立目录，后续可直接对接 editor 的 LSP。

规划
- `data/commands.json` — 命令索引（双版本：旧数据值时代 / 新组件时代）
- `data/items.json`   — 全物品索引（方块 + 非方块物品）：英文 id / 中文名 / 特殊值 data / 图标 / 分类

生成方法：把 `AI_PROMPT.md` 全文交给 AI，产出上述两个数据文件。
格式与分类约束请看 `AI_PROMPT.md`。
