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
| 玩法 | WOK-市场模块 | `wok-market` | `market` |
| 玩法 | WOK-电力模块 | `wok-power` | `power` |
| 玩法 | WOK-任务模块 | `wok-quest` | `quest` |
| 玩法 | WOK-附魔模块 | `wok-enchant` | `enchant` |
| 职业 | WOK-矿工模块 | `wok-job-miner` | `job/miner` |
| 职业 | WOK-农夫模块 | `wok-job-farmer` | `job/farmer` |
| 职业 | WOK-铸甲师模块 | `wok-job-armorer` | `job/engineer` |
| 职业 | WOK-厨师模块 | `wok-job-chef` | `job/chef` |
| 职业 | WOK-渔夫模块 | `wok-job-fisher` | `job/fisher` |
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
- `mixin` 归 `wok-core` 而不单列模块：当前只有 `MoveSpeedCheckMixin` 一个类，混入的是原版 `ServerGamePacketListenerImpl` 且读的是核心配置门面，属于核心装配的基础设施而非独立业务。
- 模块数、活跃例外数和共享资源文件数不在本文重复钉死，`gradlew verifyModuleRegistry` 每次运行都会打印当期实数。

## 整理规则

- 一个模块对应一个功能分支和一组可独立审查的提交。
- 模块只能依赖登记表中声明的模块；现有反向引用必须出现在 `boundaryExceptions`，不得新增未登记依赖。
- 每条 `boundaryException` 必须带 `D###` 编号和触发它的全部源文件清单 `evidence`；[`DEPENDENCY_DEBT.md`](DEPENDENCY_DEBT.md) 是登记表的渲染结果，两边编号集合对不上或 `evidence` 与实际扫描结果不符都会直接构建失败。
- 资源必须落进某个模块的 `resourcePaths`/`resourceNamePrefixes`，或显式登记进 `sharedResources`；语言文件、`sounds.json` 这类单一物理文件按 key 或条目分属，提交只能动本模块的那些行。
- 跨模块功能优先通过公开接口、服务门面或软联动实现。
- 全服任何模块发放经验都必须经过 `wok-experience`；经验目标使用轨道 ID，发放行为使用稳定来源 ID。
- 每个模块必须登记源码、资源、配置、注册 ID、存档数据、外部依赖和测试入口。
- 任何物理搬迁前必须先冻结注册 ID、资源 ID、Capability ID、SavedData 名和网络包编号。
- 生成图片、预览文件、临时脚本输出和发布 JAR 不与业务源码混在同一提交。
- `WOK-本体护甲` 归 WOK-铸甲师模块管理，但保持现有兼容身份。
- `WOK步战附属-独立护甲` 及其他 WOK步战附属 MOD 不属于本登记表。

## 样板与基线

[WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板。其余模块已进入全量登记表和库存基线；后续每个模块在实际迁移分支中补充自己的详细注册 ID 清单。
