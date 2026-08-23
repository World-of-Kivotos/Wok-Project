# WOK 全模块库存基线

本清单来自 `src/main/java` 的实际 package 扫描。Java 文件数与 GameTest 数是 2026-08-23 的整理基线；后续由模块边界校验保护代码所有权，数量变化本身不构成错误。

## 1. 全量模块

| 模块键 | 模块 | Java 文件 | GameTest | 主要入口 | 直接目标依赖 |
| --- | --- | ---: | ---: | --- | --- |
| `wok-app` | WOK-综合装配模块 | 1 | 0 | `MiningDim` | 全部业务模块 |
| `wok-core` | WOK-核心模块 | 60 | 1 | `ConfigSystem`、`NetworkSystem`、`EntrySystem`、`ErrorSystem` | 无；现有反向引用见债务表 |
| `wok-experience` | WOK-全服经验模块 | 7 | 0 | `ExperienceModule` | 核心 |
| `wok-job-core` | WOK-职业框架模块 | 14 | 15 | `JobFrameworkSystem` | 核心、全服经验、经济 |
| `wok-mining` | WOK-矿区副本模块 | 63 | 6 | `WorldgenSystem`、`InstanceSystem`、`EntranceSystem` 等 | 核心 |
| `wok-economy` | WOK-经济模块 | 15 | 29 | `EconomySystem` | 核心 |
| `wok-combat-core` | WOK-战斗框架模块 | 4 | 5 | `CombatSystem` | 核心 |
| `wok-webui` | WOK-WebUI 模块 | 11 | 5 | `WebUiServerSubsystem`、`WebUiClientSubsystem` | 核心；MCEF 可选 |
| `wok-market` | WOK-市场模块 | 20 | 15 | `MarketSubsystem` | 核心、经济、WebUI |
| `wok-job-miner` | WOK-矿工模块 | 19 | 25 | `MinerSystem` | 核心、职业框架、矿区、经济、战斗框架 |
| `wok-job-farmer` | WOK-农夫模块 | 23 | 23 | `FarmerModule` | 核心、全服经验、职业框架、经济；Farmer's Delight 可选 |
| `wok-job-armorer` | WOK-铸甲师模块 | 121 | 67 | `EngineerSystem` | 核心、职业框架、精英怪；TaCZ 可选 |
| `wok-job-chef` | WOK-厨师模块 | 38 | 11 | `ChefModule` | 核心、全服经验、职业框架、经济、战斗框架 |
| `wok-job-brewer` | WOK-酿酒师模块 | 39 | 45 | `BrewerSystem` | 核心、职业框架、战斗框架 |
| `wok-job-tarot` | WOK-塔罗师模块 | 52 | 45 | `TarotSystem` | 核心、职业框架、矿区、经济、精英怪 |
| `wok-job-munitions` | WOK-军火商模块 | 52 | 65 | `MunitionsSystem` | 核心、职业框架、经济；TaCZ 可选 |
| `wok-job-agent` | WOK-特勤干员模块 | 36 | 54 | `AgentSystem` | 核心、职业框架、经济、精英怪；Champions 可选 |
| `wok-champion` | WOK-精英怪模块 | 118 | 320 | `ChampionSystem` | 核心、经济；Champions 可选 |
| `wok-marriage` | WOK-婚姻社交模块 | 19 | 9 | `MarriageSystem` | 核心、经济 |
| `wok-case-opening` | WOK-开箱模块 | 24 | 13 | `CaseOpeningSystem` | 核心、经济、WebUI；TaCZ 可选 |
| `wok-stacking` | WOK-实体堆叠模块 | 9 | 16 | `StackingSystem` | 核心 |

GameTest 位于主源码集是本仓库既有约定，因此 Java 文件数包含测试类。`wok-champion` 的测试数量较高，是精英词条和红线组合测试形成的结果。

## 2. 产品边界

- 根工程只登记 WOK 本体模块。
- `WOK-本体护甲` 由 `wok-job-armorer` 管理，但不改变现有仓库身份、modId、注册 ID 或存档数据。
- `standalone/wok-infantry-armor` 是 `WOK步战附属-独立护甲` 源码位置，不属于任何 WOK 本体模块。
- `standalone/wok-cardgame` 是 `WOK-卡牌游戏独立MOD`，正式 modId 为 `wok_cardgame`，同样不进入本体模块登记。
- `WOK步战核心`、部位血量和创伤治疗均不进入本库存。

## 3. 当前保护状态

- `wok-job-brewer` 存在活动工作区改动，整理过程只登记所有权，不移动或重写其源码和资源。
- `wok-job-armorer` 资源量最大，并承担 `WOK-本体护甲` 兼容责任，物理迁移排在后段。
- `wok-champion` 文件和测试最多，必须先拆清纯逻辑、Champions 兼容层、客户端表现和 GameTest。
- `wok-market` 已从经济核心独立登记，避免基础货币模块对 WebUI 和 SQLite 挂单实现形成反向依赖。
- `wok-job-farmer` 已采用独立 `FarmerModule` 装配入口；职业框架通过策略注册表读取农夫经验曲线，不再反向引用农夫实现。
- `wok-experience` 已成为全服经验总入口；八个现有职业轨道继续读取原 Capability/NBT，旧发放接口也会自动进入统一路由。

## 4. 验证入口

- `verifyModuleRegistry`：验证模块 ID、目标依赖图、例外登记和循环依赖。
- `verifyModuleBoundaries`：按最长 Java package 前缀判定所有权，拒绝新出现的未声明跨模块引用。
- `check`：自动依赖上述两项验证。
- 功能迁移仍需运行 `compileJava`、`processResources` 和相应 GameTest batch。
