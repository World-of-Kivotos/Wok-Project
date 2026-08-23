# WOK 模块登记

本目录是 WOK 综合玩法 MOD 的模块所有权入口。模块划分以业务边界为准，第一阶段不改变现有 `modId=miningdim`、资源命名空间、注册 ID、存档键或单 JAR 交付方式。

机器可读登记表见 [`module-registry.json`](module-registry.json)，总体迁移方案见 [`../WOK_Repository_Module_Plan.md`](../WOK_Repository_Module_Plan.md)。全量现状见 [`INVENTORY.md`](INVENTORY.md)，资源归属见 [`RESOURCE_OWNERSHIP.md`](RESOURCE_OWNERSHIP.md)，依赖债务见 [`DEPENDENCY_DEBT.md`](DEPENDENCY_DEBT.md)。

## 模块总表

| 类型 | 模块 | 模块键 | 当前代码入口 |
| --- | --- | --- | --- |
| 基础 | WOK-核心模块 | `wok-core` | `com.miningdim.core` 及公共基础包 |
| 基础 | WOK-全服经验模块 | `wok-experience` | `progression.ExperienceModule` |
| 基础 | WOK-职业框架模块 | `wok-job-core` | `com.miningdim.job` 根包 |
| 基础 | WOK-矿区副本模块 | `wok-mining` | `worldgen`、`instance`、`chunk`、`reset`、`spawn`、`ore`、`trap`、`pressure`、`rules`、`entrance`、`persistence`、`command` |
| 基础 | WOK-经济模块 | `wok-economy` | `economy` |
| 基础 | WOK-战斗框架模块 | `wok-combat-core` | `combat.CombatSystem` |
| 基础 | WOK-WebUI 模块 | `wok-webui` | `webui`、`client.webui` |
| 玩法 | WOK-市场模块 | `wok-market` | `market.MarketSubsystem` |
| 职业 | WOK-矿工模块 | `wok-job-miner` | `job.miner.MinerSystem` |
| 职业 | WOK-农夫模块 | `wok-job-farmer` | `job.farmer.FarmerModule` |
| 职业 | WOK-铸甲师模块 | `wok-job-armorer` | `job.engineer.EngineerSystem` |
| 职业 | WOK-厨师模块 | `wok-job-chef` | `job.chef.ChefSystem` |
| 职业 | WOK-酿酒师模块 | `wok-job-brewer` | `job.brewer.BrewerSystem` |
| 职业 | WOK-塔罗师模块 | `wok-job-tarot` | `job.tarot.TarotSystem` |
| 职业 | WOK-军火商模块 | `wok-job-munitions` | `job.munitions.MunitionsSystem` |
| 职业 | WOK-特勤干员模块 | `wok-job-agent` | `job.agent.AgentSystem` |
| 玩法 | WOK-精英怪模块 | `wok-champion` | `champion.ChampionSystem` |
| 玩法 | WOK-婚姻社交模块 | `wok-marriage` | `marriage.MarriageSystem` |
| 玩法 | WOK-开箱模块 | `wok-case-opening` | `caseopening.CaseOpeningSystem` |
| 支撑 | WOK-实体堆叠模块 | `wok-stacking` | `stacking.StackingSystem` |
| 装配 | WOK-综合装配模块 | `wok-app` | `MiningDim` |

## 整理规则

- 一个模块对应一个功能分支和一组可独立审查的提交。
- 模块只能依赖登记表中声明的模块；现有反向引用必须出现在 `boundaryExceptions`，不得新增未登记依赖。
- 跨模块功能优先通过公开接口、服务门面或软联动实现。
- 全服任何模块发放经验都必须经过 `wok-experience`；经验目标使用轨道 ID，发放行为使用稳定来源 ID。
- 每个模块必须登记源码、资源、配置、注册 ID、存档数据、外部依赖和测试入口。
- 任何物理搬迁前必须先冻结注册 ID、资源 ID、Capability ID、SavedData 名和网络包编号。
- 生成图片、预览文件、临时脚本输出和发布 JAR 不与业务源码混在同一提交。
- `WOK-本体护甲` 归 WOK-铸甲师模块管理，但保持现有兼容身份。
- `WOK步战附属-独立护甲` 及其他 WOK步战附属 MOD 不属于本登记表。

## 样板与基线

[WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板。其余模块已进入全量登记表和库存基线；后续每个模块在实际迁移分支中补充自己的详细注册 ID 清单。
