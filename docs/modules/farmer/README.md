# WOK-农夫模块

模块键：`wok-job-farmer`

当前入口：`com.miningdim.job.farmer.FarmerModule`

当前状态：已启用独立模块装配入口，并通过职业框架策略注册契约清偿反向依赖；Java 包、资源 ID 和注册项保持兼容。

## 1. 功能边界

本模块负责：

- 农夫等级对应的五档耕地解锁与放置数量上限，以及以维度加坐标为键记录放置归属的耕地索引。
- WOK 农夫小麦、种子、作物成长和产量。
- 成熟作物收获经验与每日职业经验衰减接入。
- 农夫小麦动态收购、每日累计和信用点发放。
- Farmer's Delight 作物种植、成熟收获和番茄右键采摘软联动。
- `/farmer crops` 与 `/farmer sell <amount>` 命令。
- `job.farmer.state` 与 `job.farmer.sell` 两条 WebUI action（注册进 `WebUiServerDispatcher`）。

本模块不负责：

- 职业等级、经验曲线和每日翻日框架；归 `wok-job-core`。
- 钱包、信用点总闸和经济审计；归 `wok-economy`。
- Farmer's Delight 本身的注册或内容维护。
- 酿酒师配方中的小麦加工，即使配方引用农夫产物，也归 `wok-job-brewer`。

## 2. 当前所有权

### Java 源码

- `src/main/java/com/miningdim/job/farmer/`，已有内部子包 `block`、`item`。
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
- `assets/miningdim/lang/en_us.json` 与 `zh_cn.json` 中的农夫键。

登记表口径：`resourcePaths` 只登记 `data/miningdim/recipes/farmer` 这一条目录，其余条目全部靠 `resourceNamePrefixes` 的 `farmer_` 按文件名前缀认领；
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

## 4. 依赖登记

### 必需依赖

| 模块 | 使用内容 |
| --- | --- |
| `wok-core` | `Subsystem`、`MiningConstants.MODID`、Forge 公共装配约定 |
| `wok-experience` | `IExperienceService`、经验轨道、来源登记和统一发放路由 |
| `wok-job-core` | `JobId.FARMER`、农夫轨道的旧存档适配、等级曲线和每日衰减 |
| `wok-economy` | `EconomyServices`、信用点发放、每日 faucet 总闸与反滥用状态 |
| `wok-webui` | `WebUiServerDispatcher`：注册 `job.farmer.state` 与 `job.farmer.sell` 两条动作 |

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

本模块的 GameTest 分两个持有者，分属不同 batch：

| 持有者 | batch | 职责 |
| --- | --- | --- |
| `FarmerGameTests` | `farmer` | 职业核心逻辑、数值表与事件裁决 |
| `FarmerWebUiGameTests` | `webui_w3` | 两条 WebUI action 的契约名、成功路径与失败回滚；与其他模块的 W3 动作共批 |

只看 `farmer` batch 会漏掉整条 WebUI 面板链路，两个 batch 都要绿。

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

每个农夫模块整理提交至少执行：

1. `gradlew.bat compileJava`
2. `gradlew.bat processResources`
3. `gradlew.bat runGameTestServer`，确认 `farmer` 与 `webui_w3` 两个 batch 全部通过。判定以日志里的 `All N required tests passed` 为准：服务端数据包加载崩溃时 gradle 仍可能返回 0，只看退出码是已知假阳性。
4. `gradlew.bat verifyModuleBoundaries`，确认 Java 包与资源所有权没有漂移
5. 对冻结注册 ID 和 `miningdim_farmer` 做文本回归搜索
6. 验证未安装 Farmer's Delight 时仍能启动和使用原生农夫内容
