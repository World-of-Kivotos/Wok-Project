# WOK-农夫模块

模块键：`wok-job-farmer`

当前入口：`com.miningdim.job.farmer.FarmerModule`

当前状态：已启用独立模块装配入口，并通过职业框架策略注册契约清偿反向依赖；Java 包、资源 ID 和注册项保持兼容。
粮仓子包 `job/farmer/granary` 已落地六块：储粮囤额度与归属记账、账台 / 气窗与房间结构判定、停表结算、
验收分档与启用裁决（含登记项与启用状态存档）、账台门面（放置开号、右键看结构、潜行右键验收、部件变动后重排启用）、
损坏判定与宽限期（基础要求/漏风/进水/冲突才判整座损坏，宽限 = 出事起 7 天与出事后首次上线起 3 天取较晚、上限 28 天，每位囤主各算各的）；
方块变动后的复查节流、冷源清单扩展、合建成员与待领粮单、界面与命令按 `docs/Spoilage_Granary_DesignSpec.md` 后续分片推进。

## 1. 功能边界

本模块负责：

- 农夫等级对应的五档耕地解锁与放置数量上限，以及以维度加坐标为键记录放置归属的耕地索引。
- WOK 农夫小麦、种子、作物成长和产量。
- 成熟作物收获经验与每日职业经验衰减接入。
- 农夫小麦动态收购、每日累计和信用点发放。
- Farmer's Delight 作物种植、成熟收获和番茄右键采摘软联动。
- `/farmer crops` 与 `/farmer sell <amount>` 命令。
- `/farmer admin legacy <target>` 与 `/farmer admin recount <target>`（`requires(hasPermission(OP_LEVEL))`）：F025 归属迁移的对账与清零入口，读写 `FarmerSavedData#legacyOverflow` / `#clearLegacyOverflow`，是运营侧的补救通道。
- `job.farmer.state` 与 `job.farmer.sell` 两条 WebUI action（注册进 `WebUiServerDispatcher`）。
- **粮仓（`granary` 子包）**：储粮囤方块（27 格、谁放下就归谁、不开放物品接口、推不动）、
  按农夫等级的全服额度（L1 3 个、每级 +1、L10 共 13 个，管理员可在 `miningdim-farmer.toml` 的
  `[granary]` 段整表调整）、按坐标的归属账本与派生计数、放置裁决（`EventPriority.LOWEST` + 假玩家拒绝）
  与拆除归还；账台（粮仓的大脑、每人最多 2 座）与气窗方块，以及**房间结构判定扫描器**
  （从账台周围的空气洪泛出封闭小屋，数室内格数 / 储粮囤 / 账台 / 气窗 / 冷源，并给出可支撑囤数）；
  **停表结算**（连续存满 24 小时才起算、按「存放小时 − 1」记、每件封顶 21 天，账目按格记
  原始入仓时刻 E 与本次已计停表 C，中途结算只更新 C 不动 E）；
  **验收分档与启用裁决**（粮棚 L1 / 冷库 L5 / 官仓 L7 三档并档，档位只管能存什么与功能、不给容量，
  按**验收人的农夫等级**在点击那一刻判定且只升不降；验收清单逐条报实际数字；每个囤的启用状态分
  启用 / 待启用 / 停用三态，按入册先后截出启用集合，待启用的不占格数要求，原来启用、部件被拆撑不住的
  记「停用」走宽限期；粮仓登记项与编号水位落盘）；
  **账台门面**（放下账台就开号并落一条未验收的登记项，方块实体上的编号只是镜像、账本才是权威；
  右键账台看状态与逐条清单，潜行右键验收，服务器在点击那一刻读验收人的等级并记下「谁替谁验收」；
  每次验算把屋里的囤连到这座粮仓并按部件重排启用名单，拆掉账台就解散粮仓、把囤的连线清掉）；
  **损坏与宽限**（整座损坏只在基础要求不满足、漏风、进水或账台冲突时判；宽限取「出事起 7 天」与
  「该囤主出事后首次上线起 3 天」较晚的那个、上限 28 天，每位囤主各算各的；宽限内照常停表、
  过了宽限结算到宽限截止那一刻再照常腐烂；部件撑不住被停用的囤同样走自己的宽限；上线时提醒
  房主与受影响的囤主，并记下首次上线锚点）。
  粮仓的其余部分（方块变动后的复查节流、冷源清单扩展、合建与待领粮单、界面与命令）
  在同一份设计文档的后续分片里。

本模块不负责：

- 职业等级、经验曲线和每日翻日框架；归 `wok-job-core`。
- 钱包、信用点总闸和经济审计；归 `wok-economy`。
- Farmer's Delight 本身的注册或内容维护。
- 酿酒师配方中的小麦加工，即使配方引用农夫产物，也归 `wok-job-brewer`。

## 2. 当前所有权

### Java 源码

- `src/main/java/com/miningdim/job/farmer/`，已有内部子包 `block`、`item`、`granary`（含 `granary/block`）。
- 整包由登记表的 `javaPackagePrefixes: ["com.miningdim.job.farmer"]` 按最长前缀独占，`gradlew verifyModuleBoundaries` 逐文件核对归属。
- 文件数与 GameTest 数不在本文钉死（每补一个用例就会过期），当期基线见 [`../INVENTORY.md`](../INVENTORY.md)。

### 专属资源

- `assets/miningdim/blockstates/farmer_*`
- `assets/miningdim/models/block/farmer_*`
- `assets/miningdim/models/item/farmer_*`
- `assets/miningdim/textures/block/farmer_*`
- `data/miningdim/recipes/farmer/`
- `data/miningdim/loot_tables/blocks/farmer_*`
- `data/miningdim/loot_modifiers/farmer_crop_yield.json`
- `data/miningdim/tags/blocks/farmer_farmland.json`
- 粮仓：`assets/miningdim/blockstates/granary_*`、`models/block/granary_*`、`models/item/granary_*`、
  `textures/block/granary_*`、`data/miningdim/loot_tables/blocks/granary_*`、`data/miningdim/recipes/farmer/` 下的粮仓配方。
- `assets/miningdim/lang/en_us.json` 与 `zh_cn.json` 中的农夫键与粮仓键。

登记表口径：`resourcePaths` 只登记 `data/miningdim/recipes/farmer` 这一条目录，其余条目全部靠 `resourceNamePrefixes` 的 `farmer_` 与 `granary_` 按文件名前缀认领；
两个语言文件本身登记在 `sharedResources`，不按文件归属：只有键名里带 `farmer` 段的条目归本模块（`block.`、`item.`、`itemGroup.`、`job.`、`message.`、`tooltip.` 各命名空间下都有），提交只能动这些行。
`verifyModuleBoundaries` 会核对 `src/main/resources` 下每个文件都有归属，新增资源若既不匹配 `farmer_` 也不在共享清单里会直接构建失败。

`data/miningdim/recipes/brewer/dried_wheat.json` 与酿酒台配方虽然引用农夫产物，但所有权属于 WOK-酿酒师模块，不能因引用关系迁入农夫模块。

## 3. 冻结的兼容身份

第一阶段不得修改：

- 技术命名空间：`miningdim`。
- 方块：`farmer_crop`、`farmer_farmland_low`、`farmer_farmland_medium`、`farmer_farmland_high`、`farmer_farmland_premium`、`farmer_farmland_supreme`。
- 物品：`farmer_seed`、`farmer_wheat` 及五档同名耕地物品。
- 创造标签页：`farmer`。
- Loot Modifier Serializer：`farmer_crop_yield`。
- 方块标签：`miningdim:farmer_farmland`。
- SavedData 名：`miningdim_farmer`。
- 命令根：`/farmer`。
- 粮仓（`granary` 子包）：方块 `granary_bin`、`granary_center`、`granary_vent`，方块实体类型
  `granary_bin`、`granary_center`，创造页 `farmer_granary`、SavedData 名 `miningdim_farmer_granary`、
  配置文件 `miningdim-farmer.toml` 的 `[granary]` 段。
- 房间判定的技术上限（改了会让已有粮仓的判定结果变化，属运营口径）：室内 4,096 格、以账台为中心
  水平 ±24 / 垂直 ±12、每囤 9 格、每扇气窗撑 4 个囤、每个冷源撑 2 个囤。
- 储粮囤的格账键：`EntryHours`（每格原始入仓时刻）、`CreditedHours`（每格本次已计停表）、
  `Stopping`（是否正在停表）；停表口径 24 小时起算、每件 504 小时封顶。
- 粮仓存档键（`GranaryConstants`，迁移与存档体检按同一套键读旧档）：`Version`、`Bins`、`Centers`、
  `Granaries`、`BinGranary`、`BinStates`、`NextGid`、`NextSeq`，以及条目内的 `Dim`、`Pos`、`Owner`、`Seq`、
  Gid、State、At、Hour、Id、Tier、AcceptedBy、AcceptedAt、Status、DamagedAt；GraceLogins 是按「粮仓 + 囤主」记的宽限锚点，BinStates 条目里的 At 是停用时刻。
  账台条目里的 `Gid` 是「这座账台开的是哪座粮仓」：账本是权威（拆账台、解散粮仓、日后的损坏复查都读它），
  方块实体上的 `Gid` 只是给界面看的镜像。
- 入册序号是全服单调递增的（`NextSeq` 水位）：同一座粮仓里可能有多人的囤，启用顺序要在同一条序列上分先后，
  不能用「某人第几个囤」。
- 验收口径（改了会让已有粮仓的档位判定变化）：三档门槛 L1/L5/L7；室内基础格数 18/36/72 且每个入册的囤 9 格；
  气窗每 4 个囤 1 扇、至少 1 扇；冷源自冷库起每 2 个囤 1 个、至少 2 个；单座粮仓最多启用 128 个囤；
  档位在验收那一刻锁定、之后升级降级都不追溯。

## 4. 依赖登记

### 必需依赖

| 模块 | 使用内容 |
| --- | --- |
| `wok-core` | `Subsystem`、`MiningConstants.MODID`、Forge 公共装配约定 |
| `wok-experience` | `IExperienceService`、经验轨道、来源登记和统一发放路由 |
| `wok-job-core` | `JobId.FARMER`、农夫轨道的旧存档适配、等级曲线和每日衰减 |
| `wok-economy` | `EconomyServices`、信用点发放、每日 faucet 总闸与反滥用状态 |
| `wok-webui` | `WebUiServerDispatcher`：注册 `job.farmer.state` 与 `job.farmer.sell` 两条动作 |
| `wok-freshness` | 粮仓停表要把额度写进物品的 `MiningFresh` 章（`FreshnessData`、`FreshnessConstants`）；方向只有农夫 → 腐烂底座，反过来不认农夫模块（农夫小麦按注册 ID 取） |

### 可选外部联动

| 外部 MOD | 技术 ID | 规则 |
| --- | --- | --- |
| Farmer's Delight | `farmersdelight` | 未安装时 WOK 原生农夫作物必须正常工作；安装时才启用其作物和声音路径 |

## 5. 当前结构问题

- `FarmerModule` 已接管注册项、经验策略和事件处理器装配；`FarmerSystem` 仍同时承担命令、收获、右键采摘、放置限制和骨粉拦截，后续应继续拆分。
- `FarmerConstants` 直接转引经济实现包中的常量；后续应依赖稳定的经济契约常量或配置接口。
- `FarmerGameTests` 直接构造多项经济实现类，属于集成测试依赖，不应误认为农夫运行时代码必须依赖经济实现细节。
- Farmer's Delight 的兼容判定分散在 `FarmerSystem`、`FarmerHarvests` 和 `FarmerFarmlandBlock`，后续应收敛到 `compat/farmersdelight` 边界。
- 语言文件为全仓共享文件，搬迁时不能直接按文件归属，需要按 key 前缀校验。

## 6. 目标内部结构

在不修改任何注册 ID 的前提下，后续建议收敛为：

```text
job/farmer/
  FarmerModule.java  唯一公开装配入口
  FarmerExperience.java  农夫经验轨道和来源适配
  FarmerSystem.java  运行时事件与命令处理器
  domain/        档位、成长、产量、收购数学
  block/         方块行为
  item/          物品与创造标签页
  economy/       小麦出售适配器
  persistence/   FarmerSavedData
  compat/        Farmer's Delight 软联动
  event/         收获、放置、骨粉等事件处理
  command/       /farmer 命令
```

包迁移应分小提交进行；每次迁移只改 Java 包和 import，不同时调整数值或玩法。

## 7. 验证清单

本模块的 GameTest 分六个持有者，分属不同 batch：

| 持有者 | batch | 职责 |
| --- | --- | --- |
| `FarmerGameTests` | `farmer` | 职业核心逻辑、数值表与事件裁决 |
| `FarmerWebUiGameTests` | `webui_w3` | 两条 WebUI action 的契约名、成功路径与失败回滚；与其他模块的 W3 动作共批 |
| `granary/GranaryGameTests` | `granary_bins` | 储粮囤额度表、归属记账与放置裁决 |
| `granary/GranaryStructureGameTests` | `granary_structure` | 账台 / 气窗 / 冷源与房间结构判定 |
| `granary/GranarySettleGameTests` | `granary_settle` | 停表算术（四组钉死的数字与中途结算不重置） |
| `granary/GranaryAcceptanceGameTests` | `granary_acceptance` | 验收分档门槛、清单实际数字、启用先后与启用状态存档 |
| granary/GranaryInspectionGameTests | `granary_inspect` | 账台开号与解散、右键报告与潜行右键验收、结构不成立不收囤、升档的等级门、部件被拆转停用 |
| `granary/GranaryDamageGameTests` | `granary_damage` | 宽限算术与边界、损坏判据只看基础要求、宽限内照常停表、修好/过期的两条路、上线锚点、停用的宽限 |

只看 `farmer` batch 会漏掉整条 WebUI 面板链路与全部粮仓规则，八个 batch 都要绿。

`farmer` batch 覆盖范围包括：

- 模块 ID、农夫经验策略注册与职业框架路由。
- 全服经验轨道 `miningdim:job/farmer` 及 `miningdim:farmer/harvest`、`miningdim:farmer/pick` 来源。
- Farmer's Delight 番茄及悬挂番茄采摘。
- Farmer's Delight 全作物种植和成熟产量。
- 五档耕地放置上限与等级解锁。
- Forge 作物土壤兼容。
- 收获经验、每日上限、经验硬顶边界和档位成长速率（多阶段抽样均值）。
- 小麦动态收购曲线及连续性。
- 每日卖出数据翻日、清理和 NBT 往返。
- 卖出扣物、信用点发放、经济离线、精通等级门与共享 faucet 上限。
- 收获、骨粉、放置三个事件处理器的直调裁决。
- 掉落量修正的资格门：无实体来源、假玩家和未解锁档位一律不加成，原版作物与 mod 作物走同一道门。
- 耕地放置归属索引：认领与回收计在放置者而非破坏者头上，无玩家破坏也要还配额，旧计数迁移为溢出额且不跨认领/释放重复计数。
- 五档耕地活塞不可推（`PushReaction.BLOCK`），封死推走耕地绕过放置上限的路径。

`granary_*` 六个 batch 覆盖范围包括：

- 额度与归属：L1 3 个、每级 +1、L10 共 13 个；放置事件在 `EventPriority.LOWEST` 上裁决，假玩家一律拒绝；
  认领与归还在坐标上记账、拆除才归还、同一坐标换主人必须被拒。
- 结构判定：封闭、漏风（树叶 / 脚手架 / 蜘蛛网 / 细雪）、进水、超过技术上限、超出 ±24 / ±12、
  两座账台冲突、账台卡在两间屋之间；嵌在墙里或有一面朝外的囤不算数。
- 停表：存 21 小时取出记 0、存 25 小时记 24、存 168 小时记 167、存 600 小时封顶 504；中途结算只更新 C 不动 E。
- 验收与启用：档位门槛 L1/L5/L7 与「只升不降、到顶无可验收档位」；清单报实际数字（格数 / 气窗 / 冷源 / 账台数 / 不算数的囤）；
  待启用的囤不占格数要求；按入册先后启用，部件被拆时原来启用的转「停用」、从来没启用的保持「待启用」，补齐后转回启用；
  登记项状态机（未验收 → 完好 → 损坏 → 修好）与登记项 / 连线 / 启用状态的存档往返；悬空索引读档时丢弃、编号水位按已用编号抬高。
- 账台门面：放下账台开号并落未验收登记项（方块实体上的编号是账本那份的镜像）、拆掉账台解散粮仓并清掉囤的连线；
  右键只报告不改状态、潜行右键才验收；结构不成立（漏风等）时既不收囤也不给验收；升档要点击者本人的农夫等级
  且验收人不必是房主；部件被拆时原来启用的囤转「停用」，装回去又转回启用。
- 损坏与宽限：宽限取「出事起 7 天」与「出事后首次上线起 3 天」较晚的那个、上限 28 天，出事时刻只记第一次；
  漏风等结构问题与室内格数掉到基础要求以下判整座损坏，少一扇气窗只判「停用最后入册的那几个」；
  宽限内照常停表（入仓锚点不动）、过了宽限结算到宽限截止那一刻再照常腐烂；过了宽限才修好的重新存满 24 小时；
  未验收的粮仓不判损坏；上线只记一次宽限锚点。

每个农夫模块整理提交至少执行：

1. `gradlew.bat compileJava`
2. `gradlew.bat processResources`
3. `gradlew.bat runGameTestServer`，确认 `farmer`、`webui_w3` 与 `granary_bins` / `granary_structure` / `granary_settle` / `granary_acceptance` / `granary_inspect` / `granary_damage` 八个 batch 全部通过。判定以日志里的 `All N required tests passed` 为准：服务端数据包加载崩溃时 gradle 仍可能返回 0，只看退出码是已知假阳性。
4. `gradlew.bat verifyModuleBoundaries`，确认 Java 包与资源所有权没有漂移
5. 对冻结注册 ID 和 `miningdim_farmer` 做文本回归搜索
6. 验证未安装 Farmer's Delight 时仍能启动和使用原生农夫内容
