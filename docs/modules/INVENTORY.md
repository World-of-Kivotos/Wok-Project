# WOK 全模块库存基线

本清单的口径与构建校验器完全一致：Java 文件数按 `module-registry.json` 的 `javaPackages`（精确包）与 `javaPackagePrefixes`（最长前缀）判定归属，与 `verifyModuleBoundaries` 用的是同一套规则；GameTest 数是 `@GameTest` 注解的出现次数（不含 `@GameTestHolder`、`@GameTestGenerator`）。计数是 2026-08-30 的实测基线，后续由模块边界校验保护代码所有权，数量变化本身不构成错误。

`wok-app` 只拥有根包 `com.miningdim` 里的入口类，因此它的 Java 文件数就是 1。此前该模块用 `com.miningdim` 做包前缀，任何未登记的顶层包都会被兜底吞进来，导致文档口径与校验器口径长期对不上；现在未登记的顶层包会直接被判为"没有模块所有者"。

## 1. 全量模块

| 模块键 | 模块 | Java 文件 | GameTest | 主要入口 | 直接目标依赖 |
| --- | --- | ---: | ---: | --- | --- |
| `wok-app` | WOK-综合装配模块 | 1 | 0 | `MiningDim` | 全部业务模块 |
| `wok-core` | WOK-核心模块 | 73 | 40 | `ConfigSystem`、`NetworkSystem`、`LoginGateSubsystem`（`core.auth` 登录门）、`EntrySystem`、`ErrorSystem` | 无；现有反向引用见债务表 |
| `wok-store` | WOK-持久化存储模块 | 10 | 13 | `MiningStoreSubsystem` | 核心；SQLite 可选 |
| `wok-experience` | WOK-全服经验模块 | 8 | 8 | `ExperienceModule` | 核心 |
| `wok-job-core` | WOK-职业框架模块 | 19 | 26 | `JobFrameworkSystem` | 核心、全服经验、经济、WebUI |
| `wok-mining` | WOK-矿区副本模块 | 65 | 56 | `WorldgenSystem`、`InstanceSystem`、`EntranceSystem` 等 | 核心 |
| `wok-economy` | WOK-经济模块 | 23 | 46 | `EconomySystem` | 核心、存储、WebUI；SQLite 可选 |
| `wok-combat-core` | WOK-战斗框架模块 | 4 | 5 | `CombatSystem` | 核心 |
| `wok-webui` | WOK-WebUI 模块 | 27 | 35 | `WebUiServerSubsystem`、`WebUiClientSubsystem` | 核心；MCEF 可选 |
| `wok-title` | WOK-称号模块 ‡ | 35 | 38 | `TitleSystem` | 核心、存储、WebUI；SQLite 可选 |
| `wok-market` | WOK-市场模块 | 25 | 62 | `MarketSubsystem` | 核心、存储、经济、WebUI、职业框架、塔罗师；SQLite 可选 |
| `wok-power` | WOK-电力模块 | 133 | 100 | `PowerSystem` | 核心；Flux Networks、Jade、JEI 可选 |
| `wok-quest` | WOK-任务模块 | 35 | 49 | `QuestSystem` | 核心、经济、WebUI、附魔；TaCZ 可选 |
| `wok-enchant` | WOK-附魔模块 | 7 | 9 | `EnchantmentSystem` | 核心、经济 |
| `wok-job-miner` | WOK-矿工模块 | 28 | 47 | `MinerSystem` | 核心、职业框架、矿区、经济、战斗框架、WebUI |
| `wok-job-farmer` | WOK-农夫模块 | 25 | 51 | `FarmerModule` | 核心、全服经验、职业框架、经济、WebUI；Farmer's Delight 可选 |
| `wok-job-armorer` | WOK-铸甲师模块 | 124 | 82 | `EngineerSystem` | 核心、职业框架、精英怪、WebUI；TaCZ 可选 |
| `wok-job-chef` | WOK-厨师模块 | 35 | 34 | `ChefModule` | 核心、全服经验、职业框架、战斗框架、经济、WebUI；Farmer's Delight、Flavor Immersed Daily 可选 |
| `wok-job-fisher` | WOK-渔夫模块 † | 39 | 37 | `FishingSystem` | 核心、存储、经济、厨师；Farmer's Delight、Tide 可选 |
| `wok-job-brewer` | WOK-酿酒师模块 | 41 | 59 | `BrewerSystem` | 核心、职业框架、战斗框架、农夫、WebUI |
| `wok-job-tarot` | WOK-塔罗师模块 | 56 | 67 | `TarotSystem` | 核心、职业框架、矿区、经济、战斗框架、精英怪、WebUI |
| `wok-job-munitions` | WOK-军火商模块 | 55 | 103 | `MunitionsSystem` | 核心、职业框架、经济、电力、WebUI；TaCZ 可选 |
| `wok-job-agent` | WOK-特勤干员模块 | 30 | 79 | `AgentSystem` | 核心、职业框架、精英怪、经济、WebUI；Champions 可选 |
| `wok-champion` | WOK-精英怪模块 | 124 | 336 | `ChampionSystem` | 核心、经济、WebUI；Champions 可选 |
| `wok-marriage` | WOK-婚姻社交模块 | 21 | 35 | `MarriageSystem` | 核心、经济、WebUI |
| `wok-case-opening` | WOK-开箱模块 | 25 | 24 | `CaseOpeningSystem` | 核心、存储、经济、WebUI；TaCZ、SQLite 可选 |
| `wok-stacking` | WOK-实体堆叠模块 | 11 | 33 | `StackingSystem` | 核心、精英怪 |
| `wok-achievement` | WOK-成就模块 § | 84 | 81 | `AchievementSystem` | 核心、存储、称号、矿区、精英怪、经济、全服经验、职业框架、铸甲师、厨师、酿酒师、塔罗师、军火商、特勤干员、市场、开箱、任务、婚姻、WebUI；TaCZ、SQLite 可选 |
| `wok-district` | WOK-自管区模块 ¶ | 184 | 312 | `DistrictSystem` | 核心、存储、经济、WebUI；SQLite、Flan、机械动力可选 |

† `wok-job-fisher` 这一行是 2026-09-24 在 `feat/fish-quality-size` 分支上按同一口径单独重测的（新增 `quality`、`size` 两个子包；2026-09-06 首次补测时为 23 / 15），其余 25 行仍是第一段声明的 2026-08-30 基线。同一分支给 `wok-core` 新增了 `core/ItemRarityOverrides` 与 `mixin/ItemRarityOverrideMixin` 两个文件，该行未重测。另外它在登记表里的 `category` 虽然是 `job`，但这只是业务归类：`job/JobId.java` 的枚举至今只有八个常量、没有 `FISHER`，因此渔夫没有职业等级、没有经验轨道（对照 [`experience/README.md`](experience/README.md) 的八轨清单），也没有职业身份门；它对 `wok-store` 的依赖同样只出现在 GameTest 里，图鉴本身走原版 `FishingJournalSavedData`、个人钓获记录走玩家 `PlayerPersisted` NBT，都不落 SQLite。

‡ `wok-title` 这一行是 2026-09-26 称号系统 P1 落地时新增的实测值。它登记的依赖只有核心与存储：设计文档里预留的 WebUI 依赖要等 P2 的"我的称号"页签注册 `title.*` 动作时才真正产生引用，届时再补进登记表。2026-09-26 合并 G 面板 P2 后补测为 35 个文件、38 条 GameTest：P1 之后的赞助专属称号 12 个文件、22 条，G 面板的 `TitleWebUiActions` 与 `TitleWebUiGameTests` 2 个文件、5 条 (batch `title_webui`)；WebUI 依赖已随 `title.*` 动作补进登记表。

§ `wok-achievement` 这一行是 2026-09-26 成就系统 P1 三部分合并后的实测值：地基 (模块骨架、档位、统计项与触发器、元数据与一致性校验、datagen、存储)、事件钩子 (矿区行程与计数挖掘、陷阱、精英与枪械击杀、婚姻、成就数量)，以及奖励与命令 (待领取奖励、与称号同一事务的原子领取、成就点账本与管理员调整、`/machievement`)。GameTest 按类分布：地基 12、触发器 4、事件钩子 14、奖励与命令 8。同理只登记真实引用到的模块：
- 经济依赖随事件钩子补进，只读挂机过滤 `isAfkFrozen`。
- 奖励与命令只用到已登记的称号 (`grantInTransaction`、`notifyGranted`)、存储与核心，没有新增依赖。
- 设计文档第二章列出的 WebUI 依赖 (成就点商店页)，要等商店页真正引用时再补。G 面板部分 (2026-09-26) 已补上：成就点商店页注册了 `achievement.*` 动作。
- 婚姻相关的两条成就读核心模块的婚姻指针、按菜单注册名识别共享背包，不依赖婚姻模块。
- TaCZ 出现在两处：进度 JSON 上的 `forge:mod_loaded` 加载条件；只在 TaCZ 已加载时才注册的边界类 `TaczGunKillHooks`。
- 世界 BOSS 部分 (2026-09-26) 在本模块新增 1 个测试类 `trigger.WorldBossKillGameTests` (3 条 GameTest)，`combat/star_10` 随之开放，没有新增依赖。
- P2 职业部分 (2026-09-26) 新增 11 个类与 13 条 GameTest (batch `achievement_p2_jobs`)，依赖补进全服经验、职业框架和六个提供监听接口的职业模块；各职业模块与经验模块自己包里的监听接口各多一个类，它们都不引用成就模块。
- 2026-09-26 P2 经济与社交部分之后的实测值：新增 13 个类与 1 个测试类 (14 条 GameTest, batch `achievement_p2_social`)。市场、开箱、任务、婚姻四个依赖来自成就侧注册进这些模块监听接口的监听器与只读接口 (成就文档 9.10)，方向是成就依赖它们，它们不引用成就模块。静默追溯的开关 `core.AdvancementSilence` 与混入原版 `PlayerAdvancements` 的 `PlayerAdvancementsSilenceMixin` 归核心模块。
- G 面板部分 (2026-09-26) 新增 9 个类 (`shop` 包 7 个、`web` 包 2 个) 与 11 条 GameTest (batch `achievement_webui`)，依赖补进 WebUI。成就点商店的兑换次数取自成就点流水，没有新表。
- 合并后的复核修正 (2026-09-26) 没有新增文件，在已有测试类里加了 2 条 GameTest：`WorldBossKillGameTests` 锁住 `/kill`、虚空结束的世界 BOSS 不计击杀 (3 条变 4 条)，`JobAchievementGameTests` 锁住登录追溯补判职业等级 (13 条变 14 条)。
- 表中 84 个文件、81 条 GameTest 是 P1、世界 BOSS、P2 职业、P2 经济社交与 G 面板合并并经复核修正后的实测值 (49 + 1 + 11 + 14 + 9 个文件，38 + 4 + 14 + 14 + 11 条 GameTest)。

¶ `wok-district` 这一行是 2026-09-29 自管区后端阶段 1 第一步 (存储与领域层) 落地时的实测值：84 个文件、120 条 GameTest，分布在 12 个测试类里 (batch `district_store`、`district_roles`、`district_residents`、`district_unbind`、`district_permissions`、`district_plot_layout`、`district_plot_market`、`district_plot_owner`、`district_plot_freeze`、`district_first_login`、`district_flan_sync`、`district_queries`)。
- 登记的依赖都是真实引用：存储 (`StoreTx`、`StoreMeta`)、经济 (买地扣款的 `IEconomyService` 与测试夹具的 `SqliteEconomyLedger`)、WebUI (拒绝码取自 `WebUiErrorCodes`)。
- 统一库的迁移随本模块推进到 V9；`wok-store` 的 `MiningStoreGameTests` 为此多了 1 条 V8 升级测试，存储模块那一行仍是基线值。
- 第二步 (2026-09-29, 平板动作与命令) 补测为 93 个文件、150 条 GameTest：新增 `web` 包 7 个 (26 条 `district.*` / `plot.*` / `admin.district.*` / `admin.plot.*` 动作、回执构造与裁剪、入参读取、体积预算、测试工具与 2 个测试类) 与 `command` 包 2 个 (`/district` 命令与它的测试类)，测试类增至 15 个 (新增 batch `district_webui`、`district_action_roles`、`district_commands`，共 30 条)。没有新增依赖：WebUI 依赖此前已登记，命令只用到原版与 Brigadier。设计见 [`../District_Backend_Design.md`](../District_Backend_Design.md)。
- 第二步之后的复核修正 (2026-09-29) 没有新增文件，在已有测试类里加了 2 条 GameTest，合计 152 条：`DistrictResidentGameTests` 用 TEMP 触发器锁住移出住户的整体原子性与"回滚时不推 Flan"，`DistrictWebUiGameTests` 锁住移出原因的 200 字上限。
- 第二轮复核修正 (2026-09-29) 同样没有新增文件，在已有测试类里加了 7 条 GameTest，合计 159 条：买地钉住地块范围 (`PLOT_CHANGED`)、记录型网关只在 GameTest 服务端上可用、按 UUID 认人、到期收回逐块隔离、已解绑区的范围挡住别的学院、重新绑定后的两条完整性路径；清单见设计文档 18.2。`wok-webui` 的 `WebUiErrorCodes` 为此多了 `PLOT_CHANGED` 一个码，WebUI 模块那一行仍是基线值。
- `wok-webui` 的 `WebUiPayloads` 为此多了两个通用读取器 (`requiredNullableString`、`requiredSafeLong`)，`WebUiBatchActionGameTests` 的写动作名单加入 20 条自管区写动作；WebUI 模块那一行仍是基线值。
- 阶段 2 (2026-09-29, 真 Flan 对接, 设计文档第二十章) 补测为 117 个文件、182 条 GameTest，测试类 19 个：新增 `flan/real` 包 8 个 (开服自检 `FlanCompat`、真网关 `FlanClaimGateway` 与它的入口、反射字段、权限表缓存、领地备份的 Flan 一侧与文件一侧 `ClaimBackupFiles`，加 1 个测试类，batch `district_flan_backups`；全模块只有这个包 import Flan，`verifyModuleBoundaries` 另有一条源码扫描看住)、`flan` 包 13 个 (权限全表、期望状态、按差异写的比较与写入操作、对账器与时间切片、网关选择、日志节流，加 2 个测试类，batch `district_flan_policy`、`district_reconcile`)，以及 `DistrictConfig` (`miningdim-district.toml`)、`DistrictFeature` 与测试类 `DistrictFeatureGameTests` (batch `district_feature`)。Flan 是新的可选集成 (`compileOnly`；A 步时开发运行时暂不加载，见设计文档 20.1 的探路结果 S3)。`wok-webui` 为此多了 `HubPanelGates` (受门控的 hub 面板) 与 `DISTRICT_DISABLED` 一个码，`wok-core` 的 `ModDependencyDeclarationGameTests` 多了 1 条 Flan 依赖声明的断言；这两个模块那一行仍是基线值。
- 阶段 2 的 B 步 (2026-09-30, 真 Flan 的 GameTest) 补测为 119 个文件、204 条 GameTest，测试类 20 个：`flan/real` 包新增测试工具 `FlanTestClaims` 与测试类 `FlanRealGameTests` (22 条，batch `district_flan_real`)，经真网关写真 Flan、再用 Flan 自己的判定入口核对。开发运行时从此加载服主批准的 Flan jar (`runtimeOnly fg.deobf`)，它内嵌的 lingua_bib 原样取出后作为根 mod 加载 (设计文档 20.1 的出路 ③)，不引入别的构件。
- 阶段 2 的复核修正 (2026-09-30) 补测为 120 个文件、215 条 GameTest：`FlanRealGameTests` 拆成签名里没有 Flan 类型的登记处与用例体 `FlanRealScenarios` (多 1 个文件；没有 Flan 的运行时也登记得了，`-PwithoutFlan` 可跑一轮没有 Flan 的)，真 Flan 用例 22 条变 28 条；另在已有测试类里加了 5 条 (降级不误报"领地找不到"、降级时平板只读、对账条目抛异常与数据库出错、父领地的高度与放行清单、备份残留临时文件)。没有新增依赖。
- 阶段 3 的 A 步 (2026-09-30, 机械动力禁令与地块边界守卫, 设计文档第二十二章) 补测为 161 个文件、261 条 GameTest，测试类 27 个：新增 `guard` 包 22 个 (空间索引、守卫门面与 Forge 事件、设置、认人、mixin 插件与开服核对、GameTest 的测试区域；`guard/create` 下是机械动力的分类、各注入点的判定、注入点常量表、反射、`/district machines` 的扫描；7 个测试类共 44 条，batch `district_zone_index`、`district_world_guards`、`district_world_guards_off`、`district_create_policy`、`district_create_events`、`district_create_offline`、`district_guard_status`、`district_lang`) 与 `mixin` 包 19 个 (`mixin/world` 5 个原版目标，required 配置；`mixin/create` 14 个 `@Pseudo` 的机械动力目标，可选配置)，`DistrictCommandGameTests` 多了 2 条。机械动力是新的可选集成：`mods.toml` 的可选依赖 (`[0,)`)，只经 `@Pseudo` mixin 与反射接触，**没有**编译期或运行期依赖，开发运行时不加载它；注入点靠 `-PcreateJar=<整合包里的 jar>` 的离线核对 (只读)。资源多了两份 mixin 配置与 GameTest 用的空结构 `district_guard.nbt`。`wok-core` 的 `ModDependencyDeclarationGameTests` 多了 1 条机械动力依赖声明的断言，`wok-champion` 的 `ChampionFoundationGameTests` 两条注册表用例改为先清空再断言 (不再依赖执行顺序)；这两个模块那一行仍是基线值。
- 阶段 3 的 B 步 (2026-09-30, 聊天通知, 设计文档 22.10–22.12、22.18) 补测为 168 个文件、280 条 GameTest，测试类 28 个：新增 `notice` 包 6 个 (通知种类、入队与投递、投递时机的接缝 `NoticeDeliveryGate` 与它的默认实现和持有者、测试类 `DistrictNoticeGameTests` 18 条，batch `district_notices`) 与 `core/NoticeRecord`，`DistrictLangGameTests` 多了 1 条。统一库的 V9 末尾追加了 `district_notice` 表 (V9 还没发布过)，`wok-store` 的 `MiningStoreGameTests` 两份表名单跟着加了这张表，存储模块那一行仍是基线值。没有新增依赖：登录门 (PR #72) 只经接缝对接，本分支不引用它。
- 阶段 3 收尾的复核修正 (2026-09-30, 设计文档 22.19) 补测为 174 个文件、290 条 GameTest，测试类仍是 28 个：新增 `guard/MixinHandlerScan` (数 mixin 的处理方法真的织进目标类几处) 与 `mixin/create` 下 5 个可选 mixin (传送带、对称之杖、机械臂、显示链接、土豆加农炮，C15–C19)；在已有测试类里加了 10 条 (机械动力的新判定、织入核对、下落方块、朋友通知防刷、离线模式下的默认 gate、缺表降级、生产的守卫接线，最后一条单独一个 batch `district_guard_install`)。没有新增依赖。
- 个人圈地限制 (2026-09-30, 关 P32, 设计文档 22.20–22.21) 补测为 184 个文件、309 条 GameTest，测试类 30 个：新增 `guard/PersonalClaimGuard` (判定、提示、放行计数) 与测试类 `PersonalClaimGuardGameTests` (4 条，batch `district_claim_policy`)，第三个 mixin 包 `mixin/flan` 的 2 个 `@Pseudo` mixin (Flan 的 `ClaimStorage.createClaim` / `resizeClaim`，可选配置 `miningdim.district.flan.mixins.json`)，`flan` 包的 `PersonalClaim` 与 `BufferClaimReport` (外围已有个人领地的清单与计数)，`flan/real` 的 `FlanHookTargets` (只有字符串)、`FlanClaimGuard`，以及真 Flan 的测试类 `FlanClaimGuardGameTests` 与用例体 `FlanClaimGuardScenarios` (8 条，batch `district_claim_guard`)；在已有测试类里加了 7 条 (`FlanRealGameTests` 2、`GuardStatusGameTests` 3、`DistrictZoneIndexGameTests` 1、`DistrictCommandGameTests` 1)。资源多了一份 mixin 配置。没有新增依赖：Flan 早已是可选集成。
- 个人圈地限制的复核修正 (2026-09-30, 金锄头的陈旧编辑, 设计文档 22.22) 补测为 184 个文件、311 条 GameTest，测试类仍是 30 个：没有新文件，`FlanClaimGuardGameTests` 8 条变 10 条 (陈旧编辑被拒的两条用例)。没有新增依赖。
- 冒烟测试回写 (2026-09-30, 设计文档 22.4 末尾、23.11) 补测为 184 个文件、312 条 GameTest，测试类仍是 30 个：没有新文件，`CreateGuardGameTests` 多了 1 条 (`ignored_void` 命名空间默认算机械动力)，`DistrictCommandGameTests` 两条 status 用例多核对命名空间一行。没有新增依赖。

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
