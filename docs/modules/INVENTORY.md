# WOK 全模块库存基线

本清单的口径与构建校验器完全一致：Java 文件数按 `module-registry.json` 的 `javaPackages`（精确包）与 `javaPackagePrefixes`（最长前缀）判定归属，与 `verifyModuleBoundaries` 用的是同一套规则；GameTest 数是 `@GameTest` 注解的出现次数（不含 `@GameTestHolder`、`@GameTestGenerator`）。计数是 2026-08-30 的实测基线，后续由模块边界校验保护代码所有权，数量变化本身不构成错误。

`wok-app` 只拥有根包 `com.miningdim` 里的入口类，因此它的 Java 文件数就是 1。此前该模块用 `com.miningdim` 做包前缀，任何未登记的顶层包都会被兜底吞进来，导致文档口径与校验器口径长期对不上；现在未登记的顶层包会直接被判为"没有模块所有者"。

## 1. 全量模块

| 模块键 | 模块 | Java 文件 | GameTest | 主要入口 | 直接目标依赖 |
| --- | --- | ---: | ---: | --- | --- |
| `wok-app` | WOK-综合装配模块 | 1 | 0 | `MiningDim` | 全部业务模块 |
| `wok-core` | WOK-核心模块 | 73 | 40 | `ConfigSystem`、`NetworkSystem`、`EntrySystem`、`ErrorSystem` | 无；现有反向引用见债务表 |
| `wok-store` | WOK-持久化存储模块 | 10 | 13 | `MiningStoreSubsystem` | 核心；SQLite 可选 |
| `wok-experience` | WOK-全服经验模块 | 8 | 8 | `ExperienceModule` | 核心 |
| `wok-job-core` | WOK-职业框架模块 | 19 | 26 | `JobFrameworkSystem` | 核心、全服经验、经济、WebUI |
| `wok-mining` | WOK-矿区副本模块 | 65 | 56 | `WorldgenSystem`、`InstanceSystem`、`EntranceSystem` 等 | 核心 |
| `wok-economy` | WOK-经济模块 | 23 | 46 | `EconomySystem` | 核心、存储、WebUI；SQLite 可选 |
| `wok-combat-core` | WOK-战斗框架模块 | 4 | 5 | `CombatSystem` | 核心 |
| `wok-webui` | WOK-WebUI 模块 | 27 | 35 | `WebUiServerSubsystem`、`WebUiClientSubsystem` | 核心；MCEF 可选 |
| `wok-title` | WOK-称号模块 ‡ | 21 | 11 | `TitleSystem` | 核心、存储；SQLite 可选 |
| `wok-market` | WOK-市场模块 | 25 | 62 | `MarketSubsystem` | 核心、存储、经济、WebUI、职业框架、塔罗师；SQLite 可选 |
| `wok-power` | WOK-电力模块 | 133 | 100 | `PowerSystem` | 核心；Flux Networks、Jade、JEI 可选 |
| `wok-quest` | WOK-任务模块 | 35 | 49 | `QuestSystem` | 核心、经济、WebUI、附魔；TaCZ 可选 |
| `wok-enchant` | WOK-附魔模块 | 7 | 9 | `EnchantmentSystem` | 核心、经济 |
| `wok-job-miner` | WOK-矿工模块 | 28 | 47 | `MinerSystem` | 核心、职业框架、矿区、经济、战斗框架、WebUI |
| `wok-job-farmer` | WOK-农夫模块 | 25 | 51 | `FarmerModule` | 核心、全服经验、职业框架、经济、WebUI；Farmer's Delight 可选 |
| `wok-job-armorer` | WOK-铸甲师模块 | 124 | 82 | `EngineerSystem` | 核心、职业框架、精英怪、WebUI；TaCZ 可选 |
| `wok-job-chef` | WOK-厨师模块 | 35 | 34 | `ChefModule` | 核心、全服经验、职业框架、战斗框架、经济、WebUI；Farmer's Delight、Flavor Immersed Daily 可选 |
| `wok-job-fisher` | WOK-渔夫模块 † | 23 | 15 | `FishingSystem` | 核心、存储、经济、厨师；Farmer's Delight、Tide 可选 |
| `wok-job-brewer` | WOK-酿酒师模块 | 41 | 59 | `BrewerSystem` | 核心、职业框架、战斗框架、农夫、WebUI |
| `wok-job-tarot` | WOK-塔罗师模块 | 56 | 67 | `TarotSystem` | 核心、职业框架、矿区、经济、战斗框架、精英怪、WebUI |
| `wok-job-munitions` | WOK-军火商模块 | 55 | 103 | `MunitionsSystem` | 核心、职业框架、经济、电力、WebUI；TaCZ 可选 |
| `wok-job-agent` | WOK-特勤干员模块 | 30 | 79 | `AgentSystem` | 核心、职业框架、精英怪、经济、WebUI；Champions 可选 |
| `wok-champion` | WOK-精英怪模块 | 124 | 336 | `ChampionSystem` | 核心、经济、WebUI；Champions 可选 |
| `wok-marriage` | WOK-婚姻社交模块 | 21 | 35 | `MarriageSystem` | 核心、经济、WebUI |
| `wok-case-opening` | WOK-开箱模块 | 25 | 24 | `CaseOpeningSystem` | 核心、存储、经济、WebUI；TaCZ、SQLite 可选 |
| `wok-stacking` | WOK-实体堆叠模块 | 11 | 33 | `StackingSystem` | 核心、精英怪 |
| `wok-achievement` | WOK-成就模块 § | 49 | 38 | `AchievementSystem` | 核心、存储、称号、矿区、精英怪、经济；TaCZ、SQLite 可选 |

† `wok-job-fisher` 这一行是 2026-09-06 单独补测的，其余 25 行仍是第一段声明的 2026-08-30 基线。另外它在登记表里的 `category` 虽然是 `job`，但这只是业务归类：`job/JobId.java` 的枚举至今只有八个常量、没有 `FISHER`，因此渔夫没有职业等级、没有经验轨道（对照 [`experience/README.md`](experience/README.md) 的八轨清单），也没有职业身份门；它对 `wok-store` 的依赖同样只出现在 GameTest 里，图鉴本身走原版 `FishingJournalSavedData` 而不落 SQLite。

‡ `wok-title` 这一行是 2026-09-26 称号系统 P1 落地时新增的实测值。它登记的依赖只有核心与存储：设计文档里预留的 WebUI 依赖要等 P2 的"我的称号"页签注册 `title.*` 动作时才真正产生引用，届时再补进登记表。

§ `wok-achievement` 这一行是 2026-09-26 成就系统 P1 三部分合并后的实测值：地基 (模块骨架、档位、统计项与触发器、元数据与一致性校验、datagen、存储)、事件钩子 (矿区行程与计数挖掘、陷阱、精英与枪械击杀、婚姻、成就数量)，以及奖励与命令 (待领取奖励、与称号同一事务的原子领取、成就点账本与管理员调整、`/machievement`)。GameTest 按类分布：地基 12、触发器 4、事件钩子 14、奖励与命令 8。同理只登记真实引用到的模块：
- 经济依赖随事件钩子补进，只读挂机过滤 `isAfkFrozen`。
- 奖励与命令只用到已登记的称号 (`grantInTransaction`、`notifyGranted`)、存储与核心，没有新增依赖。
- 设计文档第二章列出的 WebUI 依赖 (成就点商店页)，要等商店页真正引用时再补。
- 婚姻相关的两条成就读核心模块的婚姻指针、按菜单注册名识别共享背包，不依赖婚姻模块。
- TaCZ 出现在两处：进度 JSON 上的 `forge:mod_loaded` 加载条件；只在 TaCZ 已加载时才注册的边界类 `TaczGunKillHooks`。

GameTest 位于主源码集是本仓库既有约定，因此 Java 文件数包含测试类。`wok-champion` 的测试数量较高，是精英词条和红线组合测试形成的结果。

## 2. 产品边界

- 根工程只登记 WOK 本体模块。
- `WOK-本体护甲` 由 `wok-job-armorer` 管理，但不改变现有仓库身份、modId、注册 ID 或存档数据。
- `standalone/kivotos-armorer` 是本仓库内唯一的独立子工程，已跟踪入库，modId 为 `kivotos_armorer`、包根为 `com.kivotos.armorer`；按其自带 README，它是从本项目独立出去的纯护甲 MOD（六档插板护甲与电浆护盾），不属于任何 WOK 本体模块。它与 `WOK-本体护甲` 的产品线归属尚未由仓库所有者裁定，登记前不要据此改动本体资源。
- `WOK步战附属-独立护甲` 与 `WOK-卡牌游戏独立MOD`（modId `wok_cardgame`）在本仓库内没有源码目录，只在 `module-registry.json` 的 `excludedProducts` 中声明排除，不进入本体模块登记。
- `WOK步战核心`、部位血量和创伤治疗均不进入本库存。

## 3. 当前保护状态

- `wok-job-armorer` 资源量最大，并承担 `WOK-本体护甲` 兼容责任，物理迁移排在后段。
- `wok-champion` 文件和测试最多，必须先拆清纯逻辑、Champions 兼容层、客户端表现和 GameTest。
- `wok-market` 已从经济核心独立登记，避免基础货币模块对 WebUI 和 SQLite 挂单实现形成反向依赖。
- `wok-job-farmer` 已采用独立 `FarmerModule` 装配入口；职业框架通过策略注册表读取农夫经验曲线，不再反向引用农夫实现。
- `wok-experience` 已成为全服经验总入口；八个现有职业轨道继续读取原 Capability/NBT，旧发放接口也会自动进入统一路由。
- `wok-store` 是经济、市场、开箱共用的单一世界级 SQLite 库；它只依赖核心，谁要落盘就依赖它，不允许各模块自己开库。
- `wok-power` 是全库最大的非职业模块，且是唯一被矿区反向引用的玩法模块（D028）；矿物注册表契约落地前不要再新增矿区对电力的直接引用。
- `wok-quest` 的奖励一律走共享 credit faucet 键，悬赏将来作为第五来源并入，不另起一套任务板。

## 4. 验证入口

- `verifyModuleRegistry`：验证模块 ID、目标依赖图、例外登记、债务编号和循环依赖，不读磁盘。
- `verifyModuleBoundaries`：登记表与磁盘的一致性总闸——`currentPaths`/`resourcePaths` 必须真实存在；按 Java package 所有权拒绝新出现的未声明跨模块引用；每条例外的 `evidence` 必须与实际触发文件完全一致；`src/main/resources` 下每个文件都要落到某个模块或共享文件纪律上；`DEPENDENCY_DEBT.md` 的编号集合必须与登记表一致。
- `check`：自动依赖上述两项验证。
- 两个任务都写成功戳记到 `build/module-registry-verified.txt` 与 `build/module-boundaries-verified.txt`，因此无改动时会 UP-TO-DATE 跳过；两者均通过 `--configuration-cache` 校验。
- 功能迁移仍需运行 `compileJava`、`processResources` 和相应 GameTest batch。
