# 任务：生成《我的世界基岩版命令 LSP》数据索引（命令 + 全物品）

> 用途：给一个 Java 编写的基岩版命令 LSP 做数据源，驱动命令补全 / 参数提示 /
> 物品/方块与数据值补全，并支持在「旧版（数字数据值）」与「新版（组件/states）」之间切换。
> 本提示词是给 AI 的完整指令，产出两个数据文件。请严格遵守「事实约束」，不得编造。

---

## 0. 你要产出的文件（UTF-8，无 BOM，标准 JSON，不带注释）

1. `commands.json` —— 命令索引（含双版本参数语法）
2. `items.json` —— **全物品索引**（含方块物品与非方块物品，含 Wiki 分类与图标）

如果单次无法覆盖全集，优先保证**正确性 + 常用子集**，并在文件里写明：
`"_notice": "AI 生成子集，未覆盖 xx，见 data_meta"`。

---

## 1. commands.json —— 精确格式

顶层：

```jsonc
{
  "$schemaVersion": 1,
  "game": "minecraft-bedrock",
  "defaultVersion": "1.21.60",
  "versionAliases": { "old": "1.16.220", "new": "1.21.60" },
  "commands": { }
}
```

`commands` 的每个键 = 命令**标准英文名**（小写，无 `/`），值为：

```jsonc
{
  "name": "give",
  "category": "item",
  "description_cn": "给予玩家物品",
  "description_en": "Give items to a player",
  "url": "https://minecraft.wiki/w/Commands/give",
  "params": {
    "1.16.220": [
      { "name": "玩家", "type": "player", "required": true },
      { "name": "物品", "type": "item", "required": true },
      { "name": "数量", "type": "int", "required": false },
      { "name": "数据值", "type": "int", "required": false, "description_cn": "旧版数字数据值" }
    ],
    "1.21.60": [
      { "name": "玩家", "type": "player", "required": true },
      { "name": "物品", "type": "item", "required": true },
      { "name": "数量", "type": "int", "required": false },
      { "name": "组件", "type": "json", "required": false, "description_cn": "物品组件，如 {\"minecraft:food\":{}}" }
    ]
  }
}
```

规则：
- `type` 枚举：`player|entity|block|item|int|float|string|selector|position|rotation|nbt|json|rawtext|color|time|command|enchant|gamemode|gamerule|difficulty|effect|text`
- 可省略参数 → `"required": false`；可重复 → `"repeat": true`；需枚举补全 → `"values": [...]`
- 版本差异：某版本没这条命令就不写该版本键；未列出的命令继承 `defaultVersion`
- **give 专用约定**：`数量(amount)` 与 `数据值(data)` 在两个版本都必须标 `"required": false`（可选填）；
  数据值参数建议加 `"suggestFrom": "special_values"` —— 表示当物品来自 items.json 且带 `special_values` 时，
  LSP 用其数据值作为候选提示（无 special_values 的物品不弹数字候选，仅在补全框给范围说明，如 0-32767）。
  数据值描述里注明 Bedrock 新版仍保留 `data:int`（默认 0），仅新增 `components`。
- 至少覆盖：give/execute/setblock/summon/gamemode/gamerule/tp/fill/effect/scoreboard/locate
- 不确认的不写

---

## 2. items.json —— 精确格式（全物品，含方块）

顶层：

```jsonc
{
  "$schemaVersion": 1,
  "game": "minecraft-bedrock",
  "categorySet": { "...": "分类字典，见下方 category 说明" },
  "items": []
}
```

每个元素代表一个**物品**（方块物品、工具、食物、材料等都在同一张表里）。
`id` = Bedrock 标准短 id（默认命名空间 `minecraft:`）。

### 2.1 方块物品（带数据值/变体/states）示例

```jsonc
{
  "id": "planks",
  "namespace": "minecraft",
  "category_cn": "建筑方块",
  "category_en": "Building Blocks",
  "wikiPage": "Oak_Planks",
  "wikiUrl": "https://minecraft.wiki/w/Oak_Planks",
  "name_cn": "橡木木板",
  "name_en": "Oak Planks",
  "icon": "oak_planks.png",
  "isBlock": true,
  "isItem": true,
  "special_values": [
    { "value": 0, "name_cn": "橡木木板", "name_en": "Oak Planks",   "icon": "oak_planks.png" },
    { "value": 1, "name_cn": "云杉木板", "name_en": "Spruce Planks", "icon": "spruce_planks.png" },
    { "value": 2, "name_cn": "白桦木板", "name_en": "Birch Planks",  "icon": "birch_planks.png" },
    { "value": 3, "name_cn": "丛林木板", "name_en": "Jungle Planks", "icon": "jungle_planks.png" },
    { "value": 4, "name_cn": "金合欢木板","name_en": "Acacia Planks","icon": "acacia_planks.png" },
    { "value": 5, "name_cn": "深色橡木木板","name_en":"Dark Oak Planks","icon":"dark_oak_planks.png" }
  ],
  "states": { "wood_type": ["oak","spruce","birch","jungle","acacia","dark_oak"] },
  "stackable": true
}
```

### 2.2 普通物品（非方块，无数据值）示例

```jsonc
{
  "id": "diamond",
  "category_cn": "杂项",
  "category_en": "Miscellaneous",
  "wikiUrl": "https://minecraft.wiki/w/Diamond",
  "name_cn": "钻石",
  "name_en": "Diamond",
  "icon": "diamond.png",
  "isBlock": false,
  "isItem": true,
  "special_values": [],
  "states": {},
  "stackable": true
}
```

### 2.3 带“颜色变体”用数据值区分的物品示例（羊毛，0-15）

```jsonc
{
  "id": "wool",
  "category_cn": "彩色方块",
  "category_en": "Colored Blocks",
  "name_cn": "羊毛",
  "name_en": "Wool",
  "icon": "white_wool.png",
  "isBlock": true,
  "isItem": true,
  "special_values": [
    { "value": 0, "name_cn": "白色羊毛", "name_en": "White Wool", "icon": "white_wool.png" },
    { "value": 1, "name_cn": "橙色羊毛", "name_en": "Orange Wool", "icon": "orange_wool.png" },
    { "value": 15, "name_cn": "黑色羊毛", "name_en": "Black Wool", "icon": "black_wool.png" }
  ],
  "states": { "color": ["white","orange","magenta","light_blue","yellow","lime","pink","gray",
                          "light_gray","cyan","purple","blue","brown","green","red","black"] },
  "stackable": true
}
```

### 2.4 category 使用说明（直接用 Minecraft Wiki 的常用分类）

请用下面的**固定分类词表**给每个物品归类（可加 `category_note` 补充，如“火药 - 酿造/杂项兼有”）：

| category_en | category_cn | 涵盖 |
|---|---|---|
| Building Blocks | 建筑方块 | 木板/石砖/玻璃等结构材料 |
| Colored Blocks | 彩色方块 | 羊毛/带釉陶瓦/染色玻璃等 |
| Natural Blocks | 自然方块 | 泥土/矿石/原木/草/花等 |
| Functional Blocks | 功能方块 | 工作台/熔炉/箱子/唱片机等 |
| Redstone | 红石元件 | 红石粉/活塞/红石中继器等 |
| Mining | 挖掘 | 镐/斧/锹/锄等 |
| Combat | 战斗 | 剑/弓/盔甲/盾牌等 |
| Food & Drinks | 食物与饮品 | 食物/药水/附魔金苹果等 |
| Brewing | 酿造 | 药水材料/酿造台相关 |
| Tools | 工具 | 桶/剪刀/钓鱼竿/指南针等 |
| Transportation | 交通 | 矿车/船/栓绳等 |
| Materials | 原料 | 铁锭/钻石/红石粉(材料)等 |
| Miscellaneous | 杂项 | 以上都不归类的 |

分类以「minecraft.wiki 英文页的分类/用途」与中文常用译名为准；同一物品有多个用途时选最主流的分类，并在 `category_note` 说明。

### 2.5 图标（可从 Wiki 下载）

- 每个物品给一个 `icon` 文件名；**同时生成一份 `icons/` 清单文件**（`icon` 文件名 → 对应 wiki 图片下载 URL，如 `https://minecraft.wiki/images/...`），让后续脚本批量下载。
- 若你（AI）无法确定该物品的 wiki 图片地址，`icon` 与 `wikiUrl` 都可以留 `""`，但**不要编造地址**。
- 下载规则提示：Wiki 物品图多为 Inventory 类 16×16 png；方块变体图用各自页面主图即可。

约束汇总：
1. 英文 `id`、`data` 值、名称、分类必须真实，来自 minecraft.wiki / bedrock 数据，禁止臆造。
2. 同 id 不同数据值变体 → `special_values`；block-state 变体 → `states`；都不是 → 两个都给 `[]`/`{}`。
3. 只列**可被指令识别/给予的物品与方块**（食物/工具/材料都要，别把非物品的纯方块漏掉）。
4. 总量大允许分批；未覆盖的写在顶层 `_notice`。

---

## 3. 完成定义（AI 自查）

- [ ] commands.json / items.json 都能通过 `python -m json.tool` 解析。
- [ ] commands 覆盖 give/execute/setblock/summon/gamemode/gamerule/tp/fill/effect/scoreboard/locate 等，新旧两个版本各有 params。
- [ ] items 覆盖：常见方块（木板/羊毛/矿石/石砖…）、常用工具/武器/食物/材料（镐/剑/面包/钻石…），含正确数据值。
- [ ] 每个条目都带 `category_en/category_cn`（用 2.4 词表），图标文件名/URL 不为空的地方可被后续脚本下载。
- [ ] 文件内注明来源与生成日期（data_meta / _notice）。
