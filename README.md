# MC Bedrock 命令 LSP（Java 模块）

目标：给 haisa-editor（或其通用 LSP 客户端）提供《我的世界基岩版》命令补全/提示，
Java 实现、独立目录，后续可直接对接 editor 的 LSP。

规划
- `data/commands.json` — 命令索引（双版本：旧数据值时代 / 新组件时代）
- `data/items.json`   — 全物品索引（方块 + 非方块物品）：英文 id / 中文名 / 特殊值 data / 图标 / 分类

生成方法：把 `AI_PROMPT.md` 全文交给 AI，产出上述两个数据文件。
格式与分类约束请看 `AI_PROMPT.md`。

## 构建 / 运行

```bash
./gradlew test          # 单元测试（引擎）
./gradlew installDist   # 打包可 spawn 的 LSP 进程 → build/install/mc-command-lsp/bin/mc-command-lsp
tools/smoke_lsp.sh      # 冒烟：initialize + didOpen + completion
```

## 对接 LSP 客户端（haisa-editor / pylsp 那套 Stdio）

- languageId：`mcfunction`（客户端 didOpen 时带上即可）
- transport：**stdio**，启动命令 = `build/install/mc-command-lsp/bin/mc-command-lsp data/commands.json data/items.json`（路径给绝对路径）
- 同步：full（`textDocumentSync.change=1`）；补全触发字符 `/ 空格 [ =`
- 版本切换：连上后发 `workspace/didChangeConfiguration`
  `{"settings":{"mcVersion":"1.16.220"}}`（`1.21.60` 等）；不设则用 commands.json 的 `defaultVersion`
- 示例：打开 `.mcfunction`，输 `/give @p wool 1 ` → 应返回羊毛 16 个数据值候选

## 里程碑进度

- [x] M1 数据加载（commands/items）
- [x] M2 命令补全引擎（纯 JVM，版本参数/物品/数据值候选）
- [x] M3 LSP Server（stdio，completion + full sync + mcVersion 切换）
- [ ] M4 接 editor 真机联调（haisa Stdio 指向本 server；分类/图标随补全展示）
- [ ] M5 命令覆盖扩充 / 多套签名 / position 等多 token 参数完善
