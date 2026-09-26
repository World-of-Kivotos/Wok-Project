# WOK 模块登记

本目录是 WOK 综合玩法 MOD 的模块所有权入口。模块划分以业务边界为准，第一阶段不改变现有 `modId=miningdim`、资源命名空间、注册 ID、存档键或单 JAR 交付方式。

机器可读登记表见 [`module-registry.json`](module-registry.json)，总体迁移方案见 [`../WOK_Repository_Module_Plan.md`](../WOK_Repository_Module_Plan.md)。全量现状见 [`INVENTORY.md`](INVENTORY.md)，资源归属见 [`RESOURCE_OWNERSHIP.md`](RESOURCE_OWNERSHIP.md)，依赖债务见 [`DEPENDENCY_DEBT.md`](DEPENDENCY_DEBT.md)。

## 模块总表

「登记的 `currentPaths`」一列逐字取自 [`module-registry.json`](module-registry.json) 的同名字段，相对 `src/main/java/com/miningdim/`。
不写成散文式的「某某根包」是为了让本表能与登记表逐字对照：`verifyModuleBoundaries` 校验的正是这些路径在磁盘上真实存在，
换一种说法就等于把一份无人校验的口径塞进文档。各模块的入口类、Java 文件数和 GameTest 数见 [`INVENTORY.md`](INVENTORY.md)。

| 类型 | 模块 | 模块键 | 登记的 `currentPaths` |
| --- | --- | --- | --- |
| 基础 | WOK-核心模块 | `wok-core` | `core`、`config`、`network`、`registry`、`menu`、`effect`、`entry`、`error`、`mixin`、`testutil` |
| 基础 | WOK-持久化存储模块 | `wok-store` | `store` |
| 基础 | WOK-全服经验模块 | `wok-experience` | `progression` |
| 基础 | WOK-职业框架模块 | `wok-job-core` | `job` |
| 基础 | WOK-矿区副本模块 | `wok-mining` | `worldgen`、`instance`、`chunk`、`reset`、`spawn`、`ore`、`trap`、`pressure`、`rules`、`entrance`、`persistence`、`command` |
| 基础 | WOK-经济模块 | `wok-economy` | `economy` |
| 基础 | WOK-战斗框架模块 | `wok-combat-core` | `combat` |
| 基础 | WOK-WebUI 模块 | `wok-webui` | `webui`、`client/webui` |
| 基础 | WOK-称号模块 | `wok-title` | `title`、`client/title` |
| 玩法 | WOK-市场模块 | `wok-market` | `market` |
| 玩法 | WOK-电力模块 | `wok-power` | `power` |
| 玩法 | WOK-任务模块 | `wok-quest` | `quest` |
| 玩法 | WOK-附魔模块 | `wok-enchant` | `enchant` |
| 职业 | WOK-矿工模块 | `wok-job-miner` | `job/miner` |
| 职业 | WOK-农夫模块 | `wok-job-farmer` | `job/farmer` |
| 职业 | WOK-铸甲师模块 | `wok-job-armorer` | `job/engineer` |
| 职业 | WOK-厨师模块 | `wok-job-chef` | `job/chef` |
| 职业 | WOK-渔夫模块（无职业身份与等级） | `wok-job-fisher` | `job/fisher` |
| 职业 | WOK-酿酒师模块 | `wok-job-brewer` | `job/brewer` |
| 职业 | WOK-塔罗师模块 | `wok-job-tarot` | `job/tarot` |
| 职业 | WOK-军火商模块 | `wok-job-munitions` | `job/munitions` |
| 职业 | WOK-特勤干员模块 | `wok-job-agent` | `job/agent` |
| 玩法 | WOK-精英怪模块 | `wok-champion` | `champion` |
| 玩法 | WOK-婚姻社交模块 | `wok-marriage` | `marriage` |
| 玩法 | WOK-开箱模块 | `wok-case-opening` | `caseopening` |
| 支撑 | WOK-实体堆叠模块 | `wok-stacking` | `stacking` |
| 装配 | WOK-综合装配模块 | `wok-app` | `MiningDim.java` |

读表须知：

- `wok-job-core` 登记 `job`，但所有权按最长前缀判定，因此 `job/miner`、`job/farmer` 等子包归各自的职业模块，职业框架只拥有 `job` 根包下的框架类本身。
- `wok-app` 是唯一用精确包（`javaPackages`）而非前缀登记的模块，它只拥有根包里的装配入口；这样未登记的新顶层包会被判为无主而失败，不会被兜底吞进装配模块。
- `wok-job-fisher` 的 `category=job` 只是业务归类：`job/JobId.java` 的枚举至今仍是八个常量、没有 `FISHER`，所以渔夫没有职业等级、没有经验轨道（对照 [`experience/README.md`](experience/README.md) 的八轨清单），也没有职业身份门，当前只有图鉴、矿石鱼和鱼羹三条内容线。
- `mixin` 归 `wok-core` 而不单列模块：`com.miningdim.mixin` 下现有五个类，另有 `required=false` 的 `com.miningdim.mixin.compat.TideOreFishMixin`。其中 `MoveSpeedCheckMixin` 混入原版 `ServerGamePacketListenerImpl`、只读核心配置门面，属于核心装配的基础设施；`ItemStackMiningDurabilityMixin`（混 `ItemStack`）、`PlayerFoodExhaustionMixin` 与 `PlayerOreSoupStateMixin`（混 `Player`）、`VanillaOreFishMixin`（混 `FishingHook`）以及 compat 里的 Tide 版都直接调用渔夫实现，这五处正是 D036 这条运行期债务的全部触发点。收敛 D036 前不再新增同向 mixin。
- 模块数、活跃例外数和共享资源文件数不在本文重复钉死，`gradlew verifyModuleRegistry` 每次运行都会打印当期实数。

## 整理规则

- 一个模块对应一个功能分支和一组可独立审查的提交。
- 模块只能依赖登记表中声明的模块；现有反向引用必须出现在 `boundaryExceptions`，不得新增未登记依赖。
- 每条 `boundaryException` 必须带 `D###` 编号和触发它的全部源文件清单 `evidence`；[`DEPENDENCY_DEBT.md`](DEPENDENCY_DEBT.md) 是登记表的渲染结果，两边编号集合对不上或 `evidence` 与实际扫描结果不符都会直接构建失败。
- 资源必须落进某个模块的 `resourcePaths`/`resourceNamePrefixes`，或显式登记进 `sharedResources`；语言文件、`sounds.json` 这类单一物理文件按 key 或条目分属，提交只能动本模块的那些行。
- 跨模块功能优先通过公开接口、服务门面或软联动实现。
- 全服任何模块发放经验都必须经过 `wok-experience`；经验目标使用轨道 ID，发放行为使用稳定来源 ID。
- 每个模块必须登记源码、资源、配置、注册 ID、存档数据、外部依赖和测试入口。设计文档不在这份清单里：登记表没有文档字段，`verifyModuleRegistry` 与 `verifyModuleBoundaries` 都不校验文档归属，所以文档只能按下面「文档所有权与子 README」一节的约定人工归口——资源少登记一条就构建失败，文档一份不登记也照样绿。
- 任何物理搬迁前必须先冻结注册 ID、资源 ID、Capability ID、SavedData 名和网络包编号。
- 生成图片、预览文件、临时脚本输出和发布 JAR 不与业务源码混在同一提交。
- `WOK-本体护甲` 归 WOK-铸甲师模块管理，但保持现有兼容身份。
- `WOK步战附属-独立护甲` 及其他 WOK步战附属 MOD 不属于本登记表。

## 样板与基线

[WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板，[WOK-厨师模块](chef/README.md) 是第二份落地的职业模块详细文档。其余模块已进入全量登记表和库存基线。

## 文档所有权与子 README

`docs/modules/` 下目前只有三份子 README，这是有意为之而非漏登：模块的机制与数值写在 `docs/` 下各自的设计规格里，子 README 承载的是另一套内容——注册 ID 清单、冻结的兼容身份、资源前缀口径和该模块的验证入口。没开始迁移就写，只会写成设计规格的二手抄本，还会立刻随代码漂移。

| 子 README | 模块 | 为什么先有它 |
| --- | --- | --- |
| [`experience/README.md`](experience/README.md) | `wok-experience` | 全服经验是所有模块都要遵守的公共契约，接入规则必须有单一出处 |
| [`farmer/README.md`](farmer/README.md) | `wok-job-farmer` | 首个完成模块化装配的职业，迁移提交清单与验证清单以它为模板 |
| [`chef/README.md`](chef/README.md) | `wok-job-chef` | 第二份落地的职业文档，示范「配置分组 + 注册清单 + 资源所有权」这一体例 |

判定规则，满足任一条就必须补子 README，其余模块按迁移排期补：

1. 模块已经换成独立的 `*Module` 装配入口。当期只有 `ExperienceModule`、`FarmerModule`、`ChefModule` 三个，其余模块仍是 `*System`/`*Subsystem`（入口类见 [`INVENTORY.md`](INVENTORY.md)）——换入口意味着它已经在走物理迁移，冻结身份和验证清单必须有单一出处。
2. 模块注册了服务端配置文件，而该文件在 `docs/` 下没有可对照的文档（见下一节）。
3. 模块拥有独占资源前缀，而这些前缀没有写进任何一份设计规格。

按第 2 条，当期唯一欠账的是 `wok-quest`：`QuestConfig` 的 19 个键里只有重摇单价两个被收支总表顺带提到，任务槽位、faucet 档位、各来源奖励基数和扫描周期都无处可查。

## 配置文件登记表

代码共注册 14 份配置文件（注册点即 `ModLoadingContext.registerConfig` 的调用处）。除 `miningdim-client.toml` 是 CLIENT 类型外，其余都是 SERVER 类型，落在存档的 `serverconfig` 目录下。服主拿到一份 toml 要先能查到它归哪个模块、参数写在哪，所以这张表按文件名而不是按模块排。

| 配置文件 | 归属模块 | 注册点 | 可对照的文档 |
| --- | --- | --- | --- |
| `miningdim-server.toml` | `wok-core` | `config/ConfigSystem` | [`../MiningDimension_Mod_DesignSpec.md`](../MiningDimension_Mod_DesignSpec.md) |
| `miningdim-client.toml`（CLIENT） | `wok-core` | `config/ConfigSystem` | [`../MiningDimension_Mod_DesignSpec.md`](../MiningDimension_Mod_DesignSpec.md) |
| `miningdim-power.toml` | `wok-power` | `power/PowerSystem` | [`../Power_Generator_DesignSpec.md`](../Power_Generator_DesignSpec.md) |
| `miningdim-quest.toml` | `wok-quest` | `quest/QuestSystem` | 无；仅 [`../Economy_BalanceSheet_DesignSpec.md`](../Economy_BalanceSheet_DesignSpec.md) 提到 `dailyCost`/`weeklyCost` 两个键 |
| `miningdim-money-mending.toml` | `wok-enchant` | `enchant/EnchantmentSystem` | [`../Economy_BalanceSheet_DesignSpec.md`](../Economy_BalanceSheet_DesignSpec.md) 的 sink 脚注（`ironUnitValue` 未覆盖） |
| `miningdim-stacking.toml` | `wok-stacking` | `stacking/StackingSystem` | [`../Minecraft实体堆叠_需求规格说明书.md`](../Minecraft实体堆叠_需求规格说明书.md) 第一节默认参数表（键名按文档口径书写，与 toml 实际键名拼写不同） |
| `miningdim-case-opening.toml` | `wok-case-opening` | `caseopening/CaseOpeningSystem` | [`../服务器经济系统设计文档.md`](../服务器经济系统设计文档.md) 第四章 |
| `miningdim-engineer.toml` | `wok-job-armorer` | `job/engineer/EngineerSystem` | [`../Armorer_Armor_System_DesignSpec.md`](../Armorer_Armor_System_DesignSpec.md) |
| `miningdim-chef.toml` | `wok-job-chef` | `job/chef/ChefSystem` | [`chef/README.md`](chef/README.md) |
| `miningdim-fishing.toml` | `wok-job-fisher` | `job/fisher/FishingSystem` | [`../Ore_Fish_And_Soup.md`](../Ore_Fish_And_Soup.md) |
| `miningdim-brewer.toml` | `wok-job-brewer` | `job/brewer/BrewerSystem` | [`../Brewer_Job_DesignSpec.md`](../Brewer_Job_DesignSpec.md) |
| `miningdim-tarot.toml` | `wok-job-tarot` | `job/tarot/TarotSystem` | [`../TarotReader_Mod_DesignSpec.md`](../TarotReader_Mod_DesignSpec.md) 第七、八章（gacha 与 craft 两段出率） |
| `miningdim-munitions.toml` | `wok-job-munitions` | `job/munitions/MunitionsSystem` | [`../Munitions_Job_DesignSpec.md`](../Munitions_Job_DesignSpec.md) |
| `miningdim-title.toml` | `wok-title` | `title/TitleSystem` | [`../Title_System_DesignSpec.md`](../Title_System_DesignSpec.md) 13.3、13.4（赞助专属称号的校验阈值与修改冷却） |

这张表也是本文「文档所有权与子 README」第 2 条的判定依据：新增一份配置文件而不在这里登记，等同于交付了一组服主调不明白的旋钮。
