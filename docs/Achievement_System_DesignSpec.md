# 成就系统 设计规格文档

## 文档元信息

- 用途：成就系统（`wok-achievement`）实现阶段的架构与机制参考。与代码不符时以代码为准并回写本文档。
- 目标平台：Minecraft 1.20.1 + Forge 47.x + Java 17。API 名称以此版本为准。
- 状态图例：DECIDED 已定稿 / PENDING 待拍板或待标定数值 / TODO 实现期补全。
- 姊妹文档：[Title_System_DesignSpec](Title_System_DesignSpec.md)。成就模块依赖称号模块，称号模块不依赖成就模块。
- 部署规模（2026-09-26 口径）：在线峰值约 50 人、上限 100 人以内，历史玩家不足一千；将来可能改为多子服架构。
- 本文档关闭 [WebUI_Frontend_Wiring_Checklist](WebUI_Frontend_Wiring_Checklist.md) 中的 J12 决策项里"成就做不做"这一半：**做，基于原版进度框架**。排行榜那一半仍然开放。

---

## 一、系统定位 (DECIDED)

1. **成就就是原版进度（Advancement）。** 展示用原版 L 键进度界面，解锁提示用原版右上角 Toast，全服公告用原版 `announce_to_chat`。**G 面板不另做成就浏览界面。**
2. 成就分七个档位，另有可叠加在任意档位上的"隐藏"标记（第四章）。
3. 解锁成就获得**成就点**，部分成就附带**称号**。奖励需要**手动领取**（第七章）。
4. 成就点只能在 **G 面板的"成就点商店"** 里兑换装饰物、特殊小道具和称号（第八章）。
5. 成就点**不属于经济系统**：不可交易，不能兑换成信用点或青辉石，也不进入 `IEconomyService` 的钱包。

**用词约束**：凡是指兑换商店的地方，一律写"成就点商店"，不单独写"商店"。仓库里已经有 `/shop` 路由和市场，单写"商店"会有歧义。

---

## 二、模块边界 (DECIDED)

| 项 | 值 |
|---|---|
| 模块 ID | `wok-achievement` |
| 分类 | `gameplay` |
| Java 包 | `com.miningdim.achievement` |
| 依赖（P1） | `wok-core`、`wok-store`、`wok-webui`、`wok-title`、`wok-mining`、`wok-champion`、`wok-economy`（只用 `isAfkFrozen` 过滤挂机击杀） |
| 依赖（P2 追加） | `wok-experience`、`wok-job-core`，以及第 9.4 节中提供监听接口的各模块 |
| 可选集成 | `tacz`（枪械击杀，仅在 `ModList.isLoaded("tacz")` 时注册） |
| 资源 | `data/miningdim/advancements`、`data/miningdim/achievement_meta`、`data/miningdim/achievement_point_shop`、lang 中以 `achievement.` 开头的键 |

**依赖方向硬约束**：成就模块可以依赖玩法模块，但玩法模块**不得**反过来依赖成就模块，否则形成循环依赖，会被 `verifyModuleBoundaries` 拦下。
P2 需要职业、任务、市场等模块的信号时，由这些模块在**自己的包里**提供一个多播监听接口（参照 `MiningServices.registerInstanceResetListener`），成就模块注册进去。
职业经验这类来自基础模块的信号，把监听接口加在 `wok-experience` 里。

`wok-app` 的 `dependencies` 需加入 `wok-achievement`。`MiningDim.registerSubsystems()` 中，`AchievementSystem` 须排在 `TitleSystem`、`QuestSystem` 之后，`WebUiClientSubsystem` 之前。

---

## 三、包结构 (DECIDED)

```
com.miningdim.achievement
├─ AchievementSystem              Subsystem 入口
├─ tier/   AchievementTier         七档枚举：框体、公告、默认点数、色板（色值引用 title.TierPalette）
├─ meta/   AchievementMeta, AchievementMetaLoader     元数据加载与一致性校验
├─ trigger/ *Trigger               自定义 SimpleCriterionTrigger
│           AchievementStats       自定义统计项
│           *Hooks                 原版事件 → 统计项 / 触发器
├─ reward/ AchievementRewardService, AchievementRewardRepository   待领取奖励与成就点账本
├─ shop/   AchievementPointShop, PointShopCatalog, PointShopRepository   成就点商店
├─ web/    AchievementWebUiActions
├─ command/ AchievementCommands    /machievement
└─ datagen/ AchievementAdvancementProvider   生成进度 JSON 与元数据
```

---

## 四、七个档位 (DECIDED)

| 档位 | 原版框体 | 全服公告 | 默认成就点 | 颜色 |
|---|---|---|---|---|
| 铜 bronze | task | 否 | 10 | `#C8834A` |
| 银 silver | task | 否 | 25 | `#D0D7DE` |
| 金 gold | goal | **是** | 50 | `#FFCC33` |
| 白金 platinum | goal | 是 | 100 | 渐变 `#FFFFFF → #FFF1C9 → #FFD66B`，粗体 |
| 钻石 diamond | challenge | 是 | 200 | 渐变 `#6FF2FF → #7FB0FF → #D59CFF`，粗体 |
| 大师 master | challenge | 是 | 400 | 渐变 `#A55CFF → #E05CFF → #FF5CB8`，粗体 |
| 传说 legend | challenge | 是 | 800 | 渐变 `#FF3D3D → #FF8A1F → #FFD23F`，粗体 |

- **隐藏**：原版 `hidden: true`，可以叠加在任意档位上。隐藏成就**一律开启全服公告**，不论档位。
- 色板与称号共用同一个真源 `com.miningdim.title.TierPalette`，渐变做法（逐字上色）见称号文档 3.1 节。
- 进度标题的 Component 带档位颜色，所以 L 键界面、Toast 和聊天公告里都能看到档位颜色。
- 各档默认点数（PENDING）：数值待开服前统一标定。单条成就可以在元数据里覆盖。

### 4.1 档位是唯一的真源

原版只有三种框体，所以"档位"是我们自己的概念。为了保证原版进度 JSON 里的框体、公告开关、标题颜色和档位始终一致：

1. **用 datagen 生成。** `AchievementAdvancementProvider`（Forge `ForgeAdvancementProvider`）从 Java 里的成就声明**同时生成**两样东西：
   - `data/miningdim/advancements/**.json`，其中的框体、公告开关、标题颜色都由档位推导得出，不手写
   - `data/miningdim/achievement_meta/**.json`，内容是档位、点数覆盖、附带称号

   生成结果按仓库惯例提交到 `src/generated/resources/`；修改后必须重新运行 `gradlew runData`。
2. **运行时再做一次一致性校验。** 元数据加载后，拿它和服务端实际加载的进度逐条对比，检查三点：
   - 框体和公告开关是否与档位一致
   - 是否有 `miningdim:` 命名空间下的进度缺少元数据
   - 元数据引用的称号 id 是否存在

   不一致的在日志里报错。缺少元数据的进度一律按"0 点、无称号"处理，**宁可少发，不可错发**。

实现口径（P1 地基）：

- 生成器 `datagen.AchievementAdvancementProvider` 是一个普通的 `DataProvider`，没有直接继承 `ForgeAdvancementProvider`：后者的回调只收 `Advancement` 对象、自己序列化，挂不上 `forge:mod_loaded` 加载条件，也生成不了元数据。进度 JSON 仍由原版 `Advancement.Builder` 序列化，带条件的在顶层加 `conditions` 数组（Forge 读取条件进度的格式）。声明表是 `datagen.AchievementDeclarations`。生成的进度一律 `sends_telemetry_event: false`。
- 渐变档（白金起）的标题在生成时从模组自带的 `zh_cn` 语言表取文字、按 `TierPalette.gradient` 逐字上色；语言表缺键时生成直接失败。单色档保留 translate 并整段套档位主色。
- 页签根也有元数据，写成 `{ "root": true }`（0 点、无称号），校验要求它没有父进度、不公告、不发 Toast、不隐藏。有档位的成就另外核对隐藏标记与元数据一致、发 Toast、挂在父进度下。
- "`miningdim:` 命名空间下的进度"不含 `recipes/` 下的配方进度（电力模块的 datagen 产出）。有元数据而服务端没有对应进度（加载条件不满足，如没装 TaCZ）不算错误，只在日志里列出。
- 校验要查称号定义，而称号门面在 ServerStarting 才注入，所以查询快照的重建与校验放在 ServerStarted 与每次 `/reload` 完成后；查询快照经 `AchievementServices.catalog()` 取得。

---

## 五、进度页签 (DECIDED)

每个分类在 L 键界面里是一个独立页签，每页有一个根进度：

| 页签 | 根进度 id | 内容 |
|---|---|---|
| WOK · 矿区 | `miningdim:mining/root` | 矿区副本的进入、撤离、挖掘 |
| WOK · 战斗 | `miningdim:combat/root` | 精英怪、枪械 |
| WOK · 职业 | `miningdim:profession/root` | 九个职业、钓鱼图鉴、塔罗（P2） |
| WOK · 经济 | `miningdim:economy/root` | 市场、开箱（P2） |
| WOK · 社交 | `miningdim:social/root` | 任务、婚姻（P2） |
| WOK · 成就 | `miningdim:meta/root` | 以成就数量为条件的成就 |

科技/电力类成就（发电机、线缆等）**暂不做**（2026-09-26 拍板），不开"科技"页签。以后要做时再决定放进哪个页签。

- 根进度用原版 `minecraft:tick` 条件，玩家首次登录时自动获得。根进度**不公告、不发 Toast、0 点**。
- 页签背景（TODO）：P1 先借用原版方块贴图（矿区用深板岩、战斗用黑石等）。以后新做的背景图放在 `assets/miningdim/textures/gui/advancements/backgrounds/`，届时要登记进模块注册表的 `resourcePaths`。
- 进度图标必须是物品。P1 使用原版物品和本 MOD 已有的物品；P3 新做的装饰物做好后，同时拿来当图标。
- 文案 key 一律带模块前缀：`achievement.miningdim.<分类>.<名称>.title` 和 `.desc`。

---

## 六、触发条件 (DECIDED)

### 6.1 自定义统计项

通过 `DeferredRegister<ResourceLocation>(Registries.CUSTOM_STAT)` 注册，数值随原版统计文件按玩家保存，在原版"统计信息"界面里也能看到。

| 统计项 | 递增时机 | 期 |
|---|---|---|
| `mining_entries` | 从其他维度进入矿区。只用于统计界面展示，**没有成就读取它**（进入免费、离开是瞬间的，可以无限刷） | P1 |
| `mining_extractions` | 一次**有效撤离**（定义见 6.3），每人每天（UTC）最多计 5 次 | P1 |
| `mining_extractions_hard` | 一次难度为困难的有效撤离，每人每天最多计 2 次（和上一项分开计） | P1 |
| `mining_blocks_mined` | 一次**计数挖掘**（定义见 6.3） | P1 |
| `mining_traps_sprung` | 玩家自己挖到伪装矿石陷阱：`BreakEvent` 以 `@LOWEST`、`receiveCanceled=true` 收到已取消的事件，`TrapDisguise.isDisguiseOre(state)` 为真，且该位置已经变成空气 | P1 |
| `mining_hard_active_ticks` | 每次有效的困难撤离结算时增加：本次行程中相邻两次计数挖掘的间隔（单次最多计 3600 tick）之和，且总量不超过"本次计数挖掘数 × 600 tick"。死亡或无效的行程不加。显示格式为时间 | P1 |
| `champion_kills` | 精英怪死亡时，给每个在线的**有效贡献者**（`ContributionPool.isQualified`）加 1。精英怪必须满足 6.4 的击杀过滤；处于挂机冻结（`isAfkFrozen`）的贡献者不加 | P1 |
| `gun_kills` | TaCZ 的 `EntityKillByGunEvent`（服务端）：攻击者是未挂机的 `ServerPlayer`，被击杀者在矿区、满足 6.4 的击杀过滤 | P1（需 TaCZ） |
| `gun_headshot_kills` | 同上，且 `isHeadShot()` 为真 | P1（需 TaCZ） |
| `farmer_harvests` | `wok-experience` 发放经验时，来源为 `miningdim:farmer/harvest` 或 `miningdim:farmer/pick`，且玩家不是 FakePlayer | P2 |
| `credits_earned` | `IEconomyService#grantDaily` 发放 CREDIT 且账本事务提交后，加上衰减后的实际金额（所有 faucet key）。`grant()`、`grantBundle()`（市场收入、管理员发放、退款、婚姻）和青辉石都**不算** | P2 |
| `quests_completed` | `QuestService#claim` 返回 CLAIMED，每人每天最多计 6 份 | P2 |
| `quest_daily_clears` | 领取每日任务后当天所有每日任务都已领取，每个 `dailyStamp` 最多计 1 次 | P2 |

统计项 id 都在 `miningdim:` 命名空间下。计数类成就不自己存计数，一律用 `stat_at_least` 读取统计项。
每日上限用的"当天已计次数"存在本模块的 SQLite 里（`achievement_daily_counter`，结构参照现有的 `daily_counters` 表）。

### 6.2 自定义触发器

继承 `SimpleCriterionTrigger`，通过 `CriteriaTriggers.register` 注册，id 都在 `miningdim:` 命名空间下。触发器类**一律注册**，不因可选模组缺失而跳过；依赖 TaCZ 的成就改在进度 JSON 上加 `forge:mod_loaded` 加载条件。

| 触发器 | 条件字段 | 期 |
|---|---|---|
| `enter_mining` | `difficulty`（可选：easy / medium / hard） | P1 |
| `mining_extraction` | `difficulty`（可选）；`max_health_ratio`（可选，0~1，撤离时 当前血量 / 最大血量）；`threat_hit_within_ticks`（可选，和前者一起用）。有效撤离的基本条件始终生效 | P1 |
| `mine_ore` | `ore`（必填，16 种之一：coal / copper / iron / gold / redstone / lapis / emerald / diamond / ancient_debris / bauxite / borax / tin / silver / nickel / chromium / tungsten；石头版和深板岩版视为同一种）；`difficulty`（可选） | P1 |
| `stat_at_least` | `stat`（统计项 id）、`value` | P1 |
| `champion_kill` | `min_star`（1~10）；`affix`（可选，词条名，死亡时持有）；`min_share`（可选，0~1，自己的记录伤害 / 总记录伤害）；`solo`（可选，定义见 6.4）；`max_fight_ticks`（可选，死亡时间 - 首次命中时间） | P1 |
| `gun_kill` | `min_horizontal_distance`（可选，击杀时 dx、dz 的水平距离）；`headshot`（可选） | P1（需 TaCZ） |
| `achievement_count` | `count`，计数规则见 6.5 | P1 |
| `married` | 无（预留 `min_days`，以后做"婚龄"成就用） | P1 |
| `open_shared_backpack` | 无 | P1 |
| `job_level` | `level`（1~10，必填）；`job`（可选，按 `JobId.byId` 解析，兼容旧别名 armorer）；`jobs_count`（可选，1~8，默认 1） | P2 |
| `fish_catch` | `item`（可选，物品 id 或 #标签）；`size_class`（可选：small / standard / large / trophy）；`max_length`（可选） | P2 |
| `fishing_journal` | `percent`（1~100）；`source`（caught / collected，默认 caught，即只算亲手钓到的）；`min_catalog`（图鉴条目数不少于这个值时才判定百分比） | P2 |
| `chef_dish` | `min_quality`（可选：low / medium / high / extraordinary / radiant）；`target`（可选） | P2 |
| `brew_complete` | `wine_type`（可选，九种酒之一）；`min_quality`（可选：low / mid / high / superb / brilliant） | P2 |
| `tarot_play` | `min_quality`（可选：r / sr / ssr / ur / shiny）；`card_id`（可选，0~21，预留）。取代原先的 `tarot_purchase` | P2 |
| `agent_seal` | `category`（可选：passive / mechanic）；`affixes`（可选）；`min_star`（可选） | P2 |
| `nano_plate_produced` | `min_tier`（可选） | P2 |
| `munitions_batch` | 无 | P2 |
| `market_trade` | `role`（any / buyer / seller）；`count`（可选，合格交易数）；`volume`（可选，卖方计数成交额）；`partners`（可选，不同的交易对手数）；`partner_min`（可选，一个对手至少贡献多少计数成交额才算）。规则见 6.6 | P2 |
| `case_open` | `min_rarity`（可选，按 `CaseRarity` 顺序：blue / purple / pink / red / gold）；`max_credit_after`（可选，只看新开箱） | P2 |
| `quest_complete` | `source`（可选：daily / weekly / special / hidden）；`quest_id`（可选）；`chain`（可选）；`chain_finished`（可选） | P2 |
| `spouse_teleport` | `min_distance`（可选，同维度水平距离） | P2 |

实现口径：触发器实例集中在 `trigger.AchievementTriggers`，在 FMLCommonSetup 里登记进原版触发器表。`married` 与 `open_shared_backpack` 没有条件字段，直接复用原版 `PlayerTrigger` 的形态。`champion_kill` 的 `min_star` 缺省为 1，`solo` 只在写 `true` 时生效。条件写错（未知难度、矿种、词条或统计项，数值越界）时整条进度加载失败，不退化成"不限"。统计项的递增一律经 `AchievementStats.award`，它随后重新核对 `stat_at_least`。

另外直接使用原版触发器（纯数据，不需要代码）：
- `minecraft:tick`：根进度
- `minecraft:inventory_changed`：获得订婚戒指
- `minecraft:consume_item`：在矿区喝矿石鱼羹
- `minecraft:player_generates_container_loot`：矿区地牢宝箱
- `minecraft:entity_killed_player`：被"命定之死"处决，需要新增伤害类型标签 `data/miningdim/tags/damage_type/is_champion_execution.json`

### 6.3 有效撤离与计数挖掘 (DECIDED，阈值 2026-09-26 服主确认)

矿区进入免费、`/mining leave` 是瞬间的，所以"进入次数"和"撤离次数"如果不加限制，可以在几分钟内刷完。因此只有**有效撤离**才计数：

1. 进入矿区时开始一段"行程"，记下进入时的难度，计时用主世界的 gameTime。
2. 行程中**没有死亡**。用 `LivingDeathEvent` 以 `@LOWEST`、`receiveCanceled=false` 标记，这样被职业技能、纳米反应堆等以 `NORMAL` 优先级取消掉的"死亡"不会作废行程。
3. 停留时间不少于 `extractionMinDwellTicks`（默认 6000 tick，即 5 分钟，和任务系统的 `QuestConfig.extractionMinDwellTicks` 一致）。
4. 行程中的计数挖掘不少于 `minTripBlocks`（默认 32）。
5. 以任何方式离开矿区都算（`/mining leave`、面板离开、逃生技能、自动重置撤离）。行程中下线则作废；在矿区里重新上线会从头计时。

**计数挖掘**：一次满足以下全部条件的方块破坏：
- `BreakEvent` 以 `@LOWEST` 收到、没有被取消
- 破坏者是真实玩家，不是 FakePlayer
- 位置在矿区的有效区域内（`regionAt != null`）
- 方块硬度大于 0，且不在放置白名单 `IMiningConfig.placeWhitelist()` 里（防止放了再挖）
- 该位置不是流体生成的方块（在矿区监听 `BlockEvent.FluidPlaceBlockEvent`，把位置记进一个有上限的集合，被挖掉时消费掉）
- 连锁挖掘、隧道技能这类走 `destroyBlock` 的破坏不计

以上阈值写在本模块自己的配置文件 `miningdim-achievement.toml` 里，不读任务模块的配置。

实现口径（P1 钩子）：

- 行程状态机是 `trigger.MiningTrips`，事件接线是 `trigger.MiningTripHooks`。进入矿区时按玩家所在区域（`regionAt`）记难度；落在缓冲带等区域以外时不开行程，也不加 `mining_entries`。在矿区里上线会重开行程，并补触发一次 `enter_mining`，但不加 `mining_entries`。补触发是因为本模块上线前就留在矿区里的玩家从没走过维度切换，不补的话 `mining/first_entry` 下面的子成就会先于它到手；`first_entry` 是一次性条件，已获得时原版直接忽略这次触发。死后在矿区以外重生，直接丢弃这一趟（重生不发维度切换事件）。
- "被怪物或陷阱打过"在 `LivingHurtEvent` 上以 `@HIGHEST`、`receiveCanceled=true` 记录。怪物指带 `MobInstanceTag` 的怪，含它射出的弹射物；陷阱指非玩家造成的 `explosion`、`lava`、`falling_block` 伤害，与陷阱系统实际使用的伤害类型一致。撤离时的血量比例取维度切换那一刻的 当前血量 / 最大血量。
- 每日上限按"全部有效撤离"和"困难有效撤离"两个计数键分别计：一次困难撤离两个键各占一次，全部撤离的上限满了不影响困难撤离计数。`mining_hard_active_ticks` 不受每日上限约束，每次有效的困难撤离都会结算。相邻计数挖掘的间隔以主世界 gameTime 计，时钟倒退的那一段记 0。
- 每日计数写库失败时只记错误日志，不加两项撤离统计（宁可少发），`mining_extraction` 触发器照常触发。维度切换发生在传送流程末尾，异常不能冒出去打断传送。
- 流体生成位置表上限 4096 个，超出后丢掉最早记下的位置。同一位置被挖一次就消费掉，不论那次破坏是否计数。
- 陷阱计数：`BreakEvent` 以 `@LOWEST`、`receiveCanceled=true` 收到已取消的事件，同时满足三个条件：被挖的方块是伪装矿石、事件已取消、该位置已成空气。陷阱系统在 `@HIGHEST` 取消玩家的破坏，并自行把方块清掉。FakePlayer 不计。

### 6.4 击杀过滤 (DECIDED)

击杀类统计和触发器都先经过同一组过滤：
- 发生在矿区维度内
- 被击杀者带有 `MobInstanceTag`（由矿区实例生成）。这一条同时排除了 `/mchampion` 召唤的、刷怪笼刷出的怪
- 精英类还要求 `isChampion()` 为真、`isSummonedByAffix()` 为假
- **世界 BOSS 例外**（2026-09-26）：由 `/mchampion worldboss` 召唤的世界 BOSS（`WorldBoss.isWorldBoss`）在**任意维度**都计入精英击杀类统计和触发器，不要求 `MobInstanceTag`；其余判据（`isChampion`、非词条召唤物、在线的有效贡献者、挂机规则）照旧。普通 `/mchampion summon` 召唤的精英仍被排除。枪械击杀不适用这条例外，`gun_kills` 的口径仍是矿区怪物
- 只统计在线的有效贡献者，读取 `ContributionTracker` 时只用 `peek()`，**不能用 `drain()`**，否则会抢走精英奖励处理器的数据
- 挂机过滤只作用于计数类统计，不作用于一次性的成就

`solo`（独自击杀）的定义：伤害账本里只有这一名玩家、他的记录伤害不少于精英的有效血量（1.0 倍），且这只精英攻击过的玩家只有他一人。

实现口径（P1 钩子）：

- 精英击杀钩子 `trigger.ChampionKillHooks` 挂在 `LivingDeathEvent` 的 `@HIGH`，不收已取消的事件。账本的所有者是精英模块的贡献池主结算 `ChampionRewardHandler`，它在默认优先级 `drain`；特勤奖励在 `@HIGHEST` 只 `peek`。`@HIGH` 正好晚于可能取消死亡的最高优先级处理，又早于清账。GameTest 在事件总线上验证：结算后账本已空，统计与成就却已经发出。
- 有效贡献者按 `ContributionPool.isQualified` 判定，团队人均伤害取 `teamAverageEffectiveDamage`；在线判定用的是贡献池同一套"是否在玩家列表里"。精英的有效血量缺失（≤ 0）时整只跳过。
- "攻击过的玩家"由钩子自己在 `LivingHurtEvent` 上记录，同样是 `@HIGHEST`、`receiveCanceled=true`，攻击方须是计入精英击杀的精英（带实例标记的矿区精英，或任意维度的世界 BOSS）。精英死亡时摘掉这条记录；没死就消失的精英靠上限（1024 只）按最久未更新淘汰。精英一次没出手就被打死，也算独自击杀。
- 精英击杀的被击杀者判据是 `KillFilter.countsForChampionKills`：先查实例标记（含一次维度比较），再查世界 BOSS 标记（一次 capability 读取）。因为世界 BOSS 可能死在任何维度，精英击杀的两个钩子不再先按矿区维度早退；枪械击杀仍只用 `KillFilter.isInstanceMob`。
- 输出占比的分母含全部贡献记录，离线者和不合格者也算在内。`fightTicks` 是死亡时刻减去账本里最早的首伤 tick，两者都取精英所在维度的 gameTime。
- 枪械击杀的判定在 `trigger.GunKillHooks`，不引用 TaCZ。TaCZ 的 `EntityKillByGunEvent` 由 `trigger.TaczGunKillHooks` 翻译过来，只在 `ModList.isLoaded("tacz")` 时注册。射手是 FakePlayer 时不计。
- 挂机判据读 `EconomyServices.economyService().isAfkFrozen`，经济门面尚未注入时按未冻结处理，与任务模块一致。

### 6.5 成就数量的计数规则 (DECIDED)

- 计入：已获得的 `miningdim:` 进度，**不计**各页签的 `*/root` 和 `meta/*` 本身，**计入**隐藏成就。
- 候选列表在数据包重载时重建并缓存，不去扫描全部进度（全部进度里包括大量配方进度）。
- 加载条件不满足的进度（比如没装 TaCZ）直接不存在，不影响计数。

实现口径（P1 钩子）：`trigger.PlayerProgressHooks` 监听 `AdvancementEvent.AdvancementEarnEvent`，只在获得的进度属于候选列表时，用查询快照重新数一遍并触发 `achievement_count`。玩家登录时再补查一次，同时补查 `stat_at_least`（数据包新增阈值成就后，已达标的玩家上线即可获得）。

### 6.6 市场成就的防刷规则 (DECIDED)

服务器是**离线模式**（非正版验证），开小号几乎没有成本，所以市场类成就按以下规则只计"真实"成交：
1. **合格交易**：单笔成交额不低于 1,000 信用点。
2. **按买家的系统收入封顶**：一位买家贡献给某个卖家的计数成交额，不超过 min(累计成交额, 1,000,000, 这位买家当时的 `credits_earned`)。小号没有正经收入，对刷的成交额基本记为 0。
3. **同 IP 不计**：交易时买卖双方当前的连接 IP 相同，这笔交易不计入成就。只在交易瞬间比较，**不存储 IP**。
4. **配偶之间不计**：买卖双方是夫妻时不计入。
5. 按买家分别记账，存在新表 `achievement_market_partner(seller_uuid, buyer_uuid, trades, counted_volume)`，在 `MarketEngine#buy` 事务提交后写入。
6. 上线初期所有人的 `credits_earned` 都从 0 开始，会让计数成交额偏低。处理方式见第十四章待定项。

---

## 七、成就点与奖励领取 (DECIDED)

### 7.1 流程

```
原版授予进度 → AdvancementEarnEvent（只处理 miningdim: 命名空间且点数 > 0 或附带称号的）
  → 写入一条"待领取奖励"（按 玩家 + 进度 id 去重）
  → 给玩家本人发一条聊天提示，末尾带可点击的 [领取]
玩家领取（两个入口，调用同一个服务方法）：
  · 点击聊天里的 [领取] → /machievement claim <进度id>
  · G 面板成就点商店页顶部的"待领取"栏 → 单条领取 / 一键全部领取
  → 在同一事务内：标记已领取 + 成就点入账 + 通过 grantInTransaction 发放称号
```

实现口径（P1 奖励与领取）：

- 业务入口是 `reward.AchievementRewardService`：`recordEarned`（由 `reward.AchievementRewardSystem` 挂在 `AdvancementEarnEvent` 上调用）、`claim` / `claimAll`、管理员用的 `addPoints` / `removePoints`。命令和以后的 G 面板都调它，不直接碰仓库。奖励与命令的监听全部由 `AchievementRewardSystem.register` 挂载。
- 获得成就：按查询快照的 `isRewarding` 判断，页签根、缺元数据的进度、0 点且无称号的进度什么都不做。待领取奖励记下获得那一刻快照里的档位、点数和称号。只有新写入时才给玩家本人发提示，撤销后再授予被主键挡下，既不重复生成，也不再提示。写库失败只记错误日志（写明玩家、进度、点数和称号，供人工补发），异常不冒进原版的授予流程。
- 领取：单条领取和"全部领取"都只开 `MiningStore` 上的**一个**事务，逐条执行：标记已领取 → 点数大于 0 时入账（余额和累计获得）并写一条 `claim` 流水，ref 为进度 id（0 点的奖励不写流水）→ 附带的称号经 `grantInTransaction` 发放，来源 `ACHIEVEMENT`，source_ref 为进度 id。玩家原本就拥有该称号（`ALREADY_OWNED`）不算失败。
- **任何一步失败，整个事务回滚，这次一条都不发**，结果里指出是哪一条。失败包括：称号发不出去（称号门面未注入、定义已删除、id 不可发放）、写库出错、这条奖励已被领取。所以"全部领取"时只要有一条的称号发不出去，其余奖励也都留在待领取；玩家仍可单条领取别的，出问题的那一条要等管理员修好称号定义再领（称号定义随 `/reload` 重新加载，待领取记录里存的称号 id 不变，修好后即可领取）。全部领取因此失败时，反馈用单独的文案 `claim.title_unavailable_all`，写明其余奖励不受影响、可以在 `/machievement pending` 里逐条领取。
- 事务提交后，只给这次新得到的称号调用 `notifyGranted`，再给出领取结果（本次入账点数、当前余额）。
- 领取（`claim` / `claimAll`）和管理员调整（`addPoints` / `removePoints`）都不能在调用方已开着的事务里调用。入口先查共享连接，已处在事务中就直接抛 `IllegalStateException`，与称号模块写穿操作的 `requireNoOpenTransaction` 口径相同。原因是 `StoreTx` 并入外层事务时既不提交也不回滚：领取自己的回滚会被外层吞掉，排在前面的标记、入账与称号随外层提交，结果却报"已全部撤销"；提示也会先于真正落盘发出。
- 领取结果 `reward.ClaimResult.Status`：`CLAIMED`、`NOT_FOUND`（没有这条奖励记录）、`ALREADY_CLAIMED`（对应 WebUI 错误码 `REWARD_ALREADY_CLAIMED`）、`NOTHING_PENDING`（全部领取时没有待领取的）、`TITLE_UNAVAILABLE`、`STORE_FAILED`。
- 提示与命令反馈的文案键在 `achievement.miningdim.reward.*`、`achievement.miningdim.claim.*`、`achievement.miningdim.command.*` 下，与进度的标题、说明键分开。

### 7.2 存储

在 `MiningSchema` 末尾追加迁移：

```sql
CREATE TABLE achievement_reward (
  player_uuid    TEXT NOT NULL,
  advancement_id TEXT NOT NULL,
  tier           TEXT NOT NULL,
  points         INTEGER NOT NULL,
  title_id       TEXT,
  earned_at      INTEGER NOT NULL,
  claimed_at     INTEGER,          -- NULL = 待领取
  PRIMARY KEY (player_uuid, advancement_id)
);
CREATE TABLE achievement_points (
  player_uuid    TEXT PRIMARY KEY,
  balance        INTEGER NOT NULL,
  lifetime       INTEGER NOT NULL  -- 累计获得，只增不减
);
CREATE TABLE achievement_point_ledger (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid    TEXT NOT NULL,
  delta          INTEGER NOT NULL,
  reason         TEXT NOT NULL,    -- claim / shop_buy / admin
  ref            TEXT,             -- 进度 id 或商品 id
  at             INTEGER NOT NULL
);
```

- 以上三张表与 6.1 的 `achievement_daily_counter(player_uuid, counter_key, day, count)`（主键 玩家 + 键 + UTC 纪元日）一起追加为 `MiningSchema` 的 **V7**（V5、V6 是称号模块的）。每日计数经 `trigger.DailyCounterRepository`，判断上限与加一在一条 upsert 里完成；开服时删掉今天以前的计数行。
- 数据库访问经过 `AchievementRewardRepository`、`PointShopRepository` 两个接口，当前实现为 SQLite，为多子服预留。
- `achievement_reward` 的主键就是防重键：管理员用 `/advancement revoke` 撤销后再次授予，**不会重复发奖**。撤销进度也**不收回**已领取的奖励，流水里保留原记录。
- 待领取的奖励只记录"当时的点数和称号"。之后调整数值，不影响已经产生的记录。
- 进度本身由原版保存在 `world/advancements/<uuid>.json`，本模块不重复存。

---

## 八、成就点商店（G 面板）(DECIDED)

### 8.1 页面

- 路由 `/achievement-shop`，侧边栏和首页磁贴的名称都是"成就点商店"，面板 id `achievementShop`。
- 顶部：成就点余额、累计获得，以及"待领取"栏（有待领取奖励时显示，提供单条领取和一键全部领取）。
- 页签一 **成就点商店**：商品卡片网格，内容包括物品图标、名称、价格、限购信息和兑换按钮。
- 页签二 **我的称号**：由称号模块提供数据，见称号文档第七章。

### 8.2 接口

| action | 类型 | 说明 |
|---|---|---|
| `achievement.pointShop` | 只读，可加入 batch | 余额、累计获得、待领取列表、商品列表（含本人剩余限购） |
| `achievement.pointShopBuy` | 写 | `{ goodsId }` |
| `achievement.claimRewards` | 写 | `{ advancementIds: string[] \| "all" }` |

- 写操作完成后，前端调用 `invalidateAll()` 刷新数据。
- 错误码在 `WebUiErrorCodes` 中新增 `POINTS_INSUFFICIENT`、`GOODS_LIMIT_REACHED`、`GOODS_UNKNOWN`、`INVENTORY_FULL`、`REWARD_ALREADY_CLAIMED`，并在 `webui/src/lib/errorText.ts` 中映射成玩家可读的提示。

### 8.3 商品定义

路径：`data/miningdim/achievement_point_shop/<id>.json`，支持热重载。

```json
{
  "type": "item",
  "item": { "id": "miningdim:deco_trophy_gold", "count": 1 },
  "price": 300,
  "limit_per_player": 1,
  "sort": 100,
  "requires_advancement": "miningdim:mining/deep_regular"
}
```

| 字段 | 说明 |
|---|---|
| `type` | `item` 或 `title` |
| `item` / `title` | 物品（id、数量、可选 NBT）或称号 id |
| `price` | 成就点价格 |
| `limit_per_player` | 每人限购数量，缺省不限。称号自动视为 1 |
| `requires_advancement` | 可选：需要先获得某个成就才能兑换 |
| `sort` | 排序 |

### 8.4 兑换规则

- 在同一个 `MiningStore` 事务里完成：检查余额和限购 → 扣点 → 写流水 → 发称号（`grantInTransaction`）。
- 物品类商品**在扣点前**先检查背包空间，没有空间就返回 `INVENTORY_FULL`，不扣点。事务提交后在主线程把物品放进背包。因为服务端单线程，检查和发放之间不会有其他操作插进来。
- **经济隔离（DECIDED，硬约束）**：成就点商店的物品必须是装饰物或纯外观/趣味小道具，不得是可以出售换信用点的物品。兑换出的物品**必须**打上 `OwnerUUID` 绑定 NBT，**一律不能上架市场**。婚姻共享背包的黑名单已经按 `OwnerUUID` 拦截，会自动覆盖这些物品。市场侧要在 `market.MarketTradeWhitelist` 中确认已拦截，没有就补上，并加一条 GameTest 锁住（TODO）。
- 按 [文档索引](README.md) 的规矩，要在 [经济收支总表](Economy_BalanceSheet_DesignSpec.md) 登记一条说明："成就点是经济外的独立点数，不是 faucet"（TODO）。

### 8.5 前端接线清单

按现有"新增页面"流程：

1. `webui/src/lib/types.ts`：加入类型定义，并在 `HubPanelId` 中加入 `achievementShop`
2. `lib/actions.ts`（`SERVER_ACTIONS`，按字母序）、`lib/bridge.ts`（`WebUiContractMap`）、`lib/batch.ts`（只加 `achievement.pointShop`）、`lib/bridge.mock.ts`（mock 分支）
3. `router.ts`：`ROUTE_ACHIEVEMENT_SHOP = '/achievement-shop'`，同时加进 `ROUTE_PATTERNS`、`ROUTE_TRANSITION_RANK`，并在 `ROUTE_TITLES` 中设为"成就点商店"
4. `App.tsx` 的 `ROUTE_ELEMENTS`；`TabletShell.tsx` 的 `SHELL_NAV_ENTRIES`（图标用 lucide 的 `AwardIcon`）和 `NAV_PREFETCH`
5. `lib/panels.ts` 的 `HUB_PANEL_META`；Java 侧 `HubWebUiActions.PANEL_IDS`
6. 页面文件 `pages/AchievementShopPage.tsx`，写法参照 `QuestsPage`：只用 `@/components/kit` 里的组件，读数据用 `useMockAction`，写操作用 `callMock` 后调用 `invalidateAll`，操作结果用 `FeedbackAlert` 展示
7. 档位颜色在前端定义为 CSS 变量 `--tier-*`，浅色主题使用加深版
8. 验收：`pnpm build`、`pnpm check:contract`

---

## 九、首批成就 (DECIDED，2026-09-26)

70 条成就，外加 6 个页签根进度。
- **档位分布**：铜 24、银 18、金 12、白金 7、钻石 5、大师 3、传说 1
- **成就点**：全部取档位默认值，合计 4,990 点
- **分期**：P1 30 条，P2 40 条（其中两条 meta 成就随 P2 开放）
- **隐藏成就**：8 条

这批成就由 13 个代理对照代码调研、对抗式评审、统一平衡后得出，阈值都有代码里的节奏数据支撑。单条成就的推导依据归档在实现期的 PR 描述里，不在本文展开。

表格中 `stat≥N` 表示 `stat_at_least{stat=miningdim:<统计项>, value=N}`；"父"一列是进度树里的父节点。

### 9.1 矿区（14 条）

| id | 名称 | 条件（玩家可见描述的依据） | 档位 | 父 | 触发器 | 期 | 图标 |
|---|---|---|---|---|---|---|---|
| `mining/first_entry` | 初入矿区 | 第一次进入矿区 | 铜 | root | `enter_mining{}` | P1 | `miningdim:entrance_easy` |
| `mining/first_extraction` | 平安归来 | 一次有效撤离（停留满 5 分钟、挖 32 块以上、途中未阵亡） | 铜 | first_entry | `mining_extraction{}` | P1 | `minecraft:lantern` |
| `mining/blocks_1k` | 千镐之始 | 计数挖掘 1,000 块 | 铜 | first_entry | `mining_blocks_mined` ≥1,000 | P1 | `minecraft:stone_pickaxe` |
| `mining/trap_sprung` | 这矿不对劲 | 挖到伪装成矿石的陷阱 | 铜 · 隐藏 | first_entry | `mining_traps_sprung` ≥1 | P1 | `miningdim:fake_ore` |
| `mining/deep_regular` | 深井常客 | 有效撤离 20 次（每天最多计 5 次） | 银 | first_extraction | `mining_extractions` ≥20 | P1 | `minecraft:iron_pickaxe` |
| `mining/medium_extraction` | 进阶勘探 | 中等难度有效撤离 1 次 | 银 | first_extraction | `mining_extraction{difficulty=medium}` | P1 | `miningdim:entrance_medium` |
| `mining/narrow_escape` | 死里逃生 | 撤离时血量不超过 10%，且最近 600 tick 内被怪物或陷阱打过 | 银 · 隐藏 | first_extraction | `mining_extraction{max_health_ratio=0.10, threat_hit_within_ticks=600}` | P1 | `minecraft:golden_apple` |
| `mining/dungeon_chest` | 地下遗迹 | 在矿区打开地牢宝箱 | 银 | first_entry | 原版 `player_generates_container_loot`（`chests/simple_dungeon` + 维度 `miningdim:mining`） | P1 | `minecraft:chest` |
| `mining/hard_extraction` | 满载而归 | 困难难度有效撤离 1 次 | 金 | medium_extraction | `mining_extraction{difficulty=hard}` | P1 | `miningdim:entrance_hard` |
| `mining/blocks_10k` | 矿工之魂 | 计数挖掘 10,000 块 | 金 | blocks_1k | `mining_blocks_mined` ≥10,000 | P1 | `minecraft:diamond_pickaxe` |
| `mining/ore_codex` | 矿脉图鉴 | 在矿区亲手挖到全部 16 种矿石 | 金 | hard_extraction | 16 个 `mine_ore{ore=…}`，全部满足 | P1 | `miningdim:raw_tungsten` |
| `mining/hard_veteran` | 深层老手 | 困难难度有效撤离 25 次（每天最多计 2 次） | 白金 | hard_extraction | `mining_extractions_hard` ≥25 | P1 | `minecraft:deepslate_diamond_ore` |
| `mining/blocks_100k` | 地脉行者 | 计数挖掘 100,000 块 | 钻石 | blocks_10k | `mining_blocks_mined` ≥100,000 | P1 | `minecraft:netherite_pickaxe` |
| `mining/hard_active_100h` | 深渊守望者 | 困难矿区累计有效作业 100 小时 | 大师 | hard_veteran | `mining_hard_active_ticks` ≥7,200,000 | P1 | `minecraft:ancient_debris` |

### 9.2 战斗（11 条）

| id | 名称 | 条件 | 档位 | 父 | 触发器 | 期 | 图标 |
|---|---|---|---|---|---|---|---|
| `combat/first_champion` | 精英猎手 | 作为有效贡献者击倒一只精英怪 | 铜 | root | `champion_kill{min_star=1}` | P1 | `minecraft:iron_sword` |
| `combat/gun_100` | 枪械入门 | 在矿区用枪击杀 100 只矿区怪物 | 铜 | root | `gun_kills` ≥100 | P1（TaCZ） | `miningdim:bullet_head` |
| `combat/executed` | 在劫难逃 | 被"命定之死"词条处决 | 铜 · 隐藏 | first_champion | 原版 `entity_killed_player`（伤害标签 `is_champion_execution`） | P1 | `minecraft:wither_skeleton_skull` |
| `combat/headshot_100` | 精准射手 | 用枪爆头击杀 100 只矿区怪物 | 银 | gun_100 | `gun_headshot_kills` ≥100 | P1（TaCZ） | `minecraft:target` |
| `combat/giant_slayer` | 巨人杀手 | 击倒带"巨大化"词条的精英怪 | 银 | first_champion | `champion_kill{affix=GIGANTISM}` | P1 | `minecraft:zombie_head` |
| `combat/champion_100` | 百战之身 | 作为有效贡献者击倒 100 只精英怪 | 金 | first_champion | `champion_kills` ≥100 | P1 | `minecraft:diamond_sword` |
| `combat/star_6` | 首领讨伐 | 击倒 6 星及以上精英怪 | 金 | first_champion | `champion_kill{min_star=6}` | P1 | `minecraft:golden_sword` |
| `combat/long_shot` | 百米狙杀 | 在水平距离 100 格以外用枪爆头击杀敌对生物 | 金 · 隐藏 | headshot_100 | `gun_kill{min_horizontal_distance=100, headshot=true}` | P1（TaCZ） | `minecraft:spyglass` |
| `combat/star_7` | 星辰陨落 | 击倒 7 星及以上精英怪 | 白金 | star_6 | `champion_kill{min_star=7}` | P1 | `minecraft:netherite_sword` |
| `combat/star_10` | 十星弑神 | 参与击倒 10 星**世界 BOSS**，个人输出不少于全队的 5% | 钻石（PENDING，按事件频率重新标定） | star_7 | `champion_kill{min_star=10, min_share=0.05}` | P1，**已开放**（世界 BOSS 由 `/mchampion worldboss` 召唤） | `minecraft:dragon_head` |
| `combat/solo_star_9` | 一人成军 | 15 分钟内**独自**击倒一只 9 星精英怪 | 传说 | star_7 | `champion_kill{min_star=9, solo=true, max_fight_ticks=18000}` | P1 | `minecraft:end_crystal` |

**10 星改为事件触发的影响（2026-09-26 拍板）**：
- 10 星精英只由世界 BOSS 产生（精英文档里的设计是约 10 人挑战），困难矿区自然刷出的上限改为 9 星。`wok-champion` 已实现（2026-09-26）：世界 BOSS 暂由管理员指令 `/mchampion worldboss` 召唤，出现和被玩家击倒时都会全服公告，详见精英文档第十章。
- "十星弑神"改成"参与讨伐世界 BOSS 并贡献不少于 5% 输出"，已随指令召唤的世界 BOSS 开放：击杀过滤在任意维度接受世界 BOSS（6.4）。档位暂定钻石，等世界 BOSS 的出现频率定下来后再重新标定（第十四章第 8 项）。
- 原先的"一人成军"要求独自击倒 10 星，世界 BOSS 本来就是按约 10 人设计的，一个人基本打不了，所以改为独自击倒 9 星，也就是自然刷出的最高星级。id 相应从 `solo_star_10` 改为 `solo_star_9`。

### 9.3 职业（21 条）

| id | 名称 | 条件 | 档位 | 父 | 触发器 | 期 | 图标 |
|---|---|---|---|---|---|---|---|
| `profession/level_2` | 就业上岗 | 任意职业达到 2 级 | 铜 | root | `job_level{level=2}` | P2 | `minecraft:experience_bottle` |
| `profession/level_4` | 崭露头角 | 任意职业达到 4 级 | 银 | level_2 | `job_level{level=4}` | P2 | `minecraft:book` |
| `profession/level_7` | 业内骨干 | 任意职业达到 7 级 | 金 | level_4 | `job_level{level=7}` | P2 | `minecraft:enchanted_book` |
| `profession/max_level` | 行业专家 | 任意职业满级（10 级） | 白金 | level_7 | `job_level{level=10}` | P2 | `minecraft:enchanting_table` |
| `profession/all_max` | 全职精通 | 全部 8 个职业满级 | 大师 | max_level | `job_level{level=10, jobs_count=8}` | P2 | `minecraft:beacon` |
| `profession/farmer_first_harvest` | 第一茬 | 在职业耕地上收获第一株成熟作物 | 铜 | root | `farmer_harvests` ≥1 | P2 | `miningdim:farmer_seed` |
| `profession/farmer_harvest_500` | 丰收在望 | 收获 500 次 | 银 | farmer_first_harvest | `farmer_harvests` ≥500 | P2 | `miningdim:farmer_wheat` |
| `profession/farmer_harvest_5000` | 麦浪滚滚 | 收获 5,000 次 | 金 | farmer_harvest_500 | `farmer_harvests` ≥5,000 | P2 | `minecraft:hay_block` |
| `profession/ore_soup_in_mine` | 井下热汤 | 在矿区里喝下任意一种矿石鱼羹 | 铜 | root | 原版 `consume_item`（5 种鱼羹 + 维度） | P1 | `miningdim:iron_ore_fish_soup` |
| `profession/fishing_trophy` | 奖杯个体 | 亲手钓到奖杯级体型的鱼 | 铜 | root | `fish_catch{size_class=trophy}` | P2 | `minecraft:fishing_rod` |
| `profession/journal_50` | 图鉴收藏家 | 亲手钓到图鉴中至少一半的鱼种 | 银 | fishing_trophy | `fishing_journal{percent=50, source=caught, min_catalog=75}` | P2 | `miningdim:fishing_journal` |
| `profession/journal_100` | 图鉴大全 | 亲手钓到图鉴中的全部鱼种 | 钻石 | journal_50 | `fishing_journal{percent=100, source=caught, min_catalog=75}` | P2 | `miningdim:dark_gold_ore_fish` |
| `profession/chef_first_dish` | 初次掌勺 | 在调味台完成第一道料理 | 铜 | root | `chef_dish{}` | P2 | `miningdim:seasoning_table_low` |
| `profession/chef_radiant` | 闪耀出锅 | 做出一道闪耀品质的料理 | 银 | chef_first_dish | `chef_dish{min_quality=radiant}` | P2 | `miningdim:seasoning_table_radiant` |
| `profession/brewer_first_brew` | 头道基酒 | 在酿酒台酿出第一瓶酒 | 铜 | root | `brew_complete{}` | P2 | `miningdim:brewing_station` |
| `profession/brewer_nine_wines` | 九酝齐备 | 九种酒都亲手酿过 | 银 | brewer_first_brew | 9 个 `brew_complete{wine_type=…}`，全部满足 | P2 | `miningdim:wine_cellar` |
| `profession/brewer_brilliant` | 闪耀佳酿 | 酿出一瓶闪耀品质的酒 | 白金 | brewer_nine_wines | `brew_complete{min_quality=brilliant}` | P2 | `miningdim:wine_maotai` |
| `profession/first_tarot` | 命运之轮 | 第一次打出塔罗牌 | 铜 | root | `tarot_play{}` | P2 | `miningdim:tarot_pack_common` |
| `profession/agent_first_seal` | 初次封印 | 特勤干员第一次封印精英怪的词条 | 银 | root | `agent_seal{}` | P2 | `minecraft:chain` |
| `profession/first_nano_plate` | 纳米工艺 | 造出第一份纳米维修套件 | 铜 | root | `nano_plate_produced{}` | P2 | `miningdim:nano_plate_low` |
| `profession/first_ammo` | 第一批弹药 | 在军火台完成第一批弹药生产 | 铜 | root | `munitions_batch{}` | P2 | `miningdim:munitions_bench` |

- 职业满级是 **10 级**，共 **8 个**职业 id：工程师和铸甲师是同一个职业，`JobId` 里只有一个。
- Tide 模组**常驻**（2026-09-26 确认），装上后图鉴共 75 种，所以 `min_catalog=75`。其中 `tide:midas_fish` 需要鱼竿幸运 7 以上，要在实现期确认生存模式下能否达到；达不到就把它排除出"图鉴大全"的统计范围（TODO）。

### 9.4 经济（11 条，全部 P2）

| id | 名称 | 条件 | 档位 | 父 | 触发器 | 图标 |
|---|---|---|---|---|---|---|
| `economy/first_paycheck` | 第一份薪水 | 通过系统途径累计获得 10,000 信用点（市场收入不算） | 铜 | root | `credits_earned` ≥10,000 | `minecraft:gold_nugget` |
| `economy/income_300k` | 小有积蓄 | 累计 300,000 | 银 | first_paycheck | `credits_earned` ≥300,000 | `minecraft:gold_ingot` |
| `economy/income_1500k` | 殷实之家 | 累计 1,500,000 | 金 | income_300k | `credits_earned` ≥1,500,000 | `minecraft:gold_block` |
| `economy/income_5m` | 富甲一方 | 累计 5,000,000 | 白金 | income_1500k | `credits_earned` ≥5,000,000 | `minecraft:netherite_ingot` |
| `economy/first_trade` | 开张大吉 | 在市场完成第一笔合格交易（成交额不低于 1,000，买入卖出均可） | 铜 | root | `market_trade{role=any, count=1}` | `minecraft:barrel` |
| `economy/market_100k` | 摆摊老手 | 在市场累计卖出 100,000（计数成交额） | 银 | first_trade | `market_trade{role=seller, volume=100000}` | `minecraft:emerald_block` |
| `economy/market_1m` | 生意兴隆 | 累计卖出 1,000,000，且至少 3 位买家各贡献 10,000 以上 | 金 | market_100k | `market_trade{role=seller, volume=1000000, partners=3, partner_min=10000}` | `minecraft:diamond_block` |
| `economy/tycoon` | 商业大亨 | 累计卖出 20,000,000，且至少 20 位买家各贡献 10,000 以上 | 大师 | market_1m | `market_trade{role=seller, volume=20000000, partners=20, partner_min=10000}` | `minecraft:netherite_block` |
| `economy/first_case` | 创始之箱 | 第一次开启创始武器箱 | 银 | root | `case_open{}` | `minecraft:ender_chest` |
| `economy/lucky_case` | 欧皇降临 | 从创始武器箱开出金色品质皮肤 | 钻石 · 隐藏 | first_case | `case_open{min_rarity=gold}` | `minecraft:enchanted_golden_apple` |
| `economy/all_in` | 倾家荡产 | 开箱后信用点余额不足 1,000 | 铜 · 隐藏 | first_case | `case_open{max_credit_after=999}` | `minecraft:bowl` |

- 市场成就的计数规则见 6.6。
- 开箱类成就依赖 TaCZ（加 `forge:mod_loaded` 加载条件）。开箱概率属于服务器配置，档位按默认概率标定。
- "富甲一方"**不附带称号**：所有 faucet 共用同一个 key，挂机卖矿石鱼的收入也会计入 `credits_earned`，没法在不改 `IEconomyService` 签名的前提下把它过滤掉。

### 9.5 社交（10 条）

| id | 名称 | 条件 | 档位 | 父 | 触发器 | 期 | 图标 |
|---|---|---|---|---|---|---|---|
| `social/quest_first` | 初次委托 | 第一次领取任务奖励 | 铜 | root | `quests_completed` ≥1 | P2 | `minecraft:writable_book` |
| `social/special_quest` | 路过的委托 | 完成一个在村庄随机接到的特殊任务 | 铜 | quest_first | `quest_complete{source=special}` | P2 | `minecraft:map` |
| `social/quest_10` | 勤勉 | 领取 10 份任务奖励（每天最多计 6 份） | 银 | quest_first | `quests_completed` ≥10 | P2 | `minecraft:paper` |
| `social/quest_200` | 任务狂人 | 领取 200 份（每天最多计 6 份） | 白金 | quest_10 | `quests_completed` ≥200 | P2 | `minecraft:bookshelf` |
| `social/daily_clear_60` | 全勤 | 在 60 个不同的日子里领完当天全部每日任务 | 钻石 | quest_10 | `quest_daily_clears` ≥60 | P2 | `minecraft:clock` |
| `social/marksman_chain` | 神射手 | 完成隐藏任务线"神射手"的全部四个阶段 | 金 · 隐藏 | quest_first | `quest_complete{chain=marksman, chain_finished=true}` | P2（TaCZ） | `minecraft:spectral_arrow` |
| `social/engagement_ring` | 心意已决 | 获得一枚订婚戒指 | 铜 | root | 原版 `inventory_changed` | P1 | `miningdim:engagement_ring` |
| `social/married` | 执子之手 | 与伴侣完成婚礼 | 银 | engagement_ring | `married{}` | P1 | `miningdim:wedding_ring` |
| `social/shared_backpack` | 两人的口袋 | 第一次打开和伴侣的共享背包 | 铜 | married | `open_shared_backpack{}` | P1 | `minecraft:pink_shulker_box` |
| `social/long_distance` | 千里赴约 | 从 1,000 格以外传送到伴侣身边（同一维度） | 铜 · 隐藏 | married | `spouse_teleport{min_distance=1000}` | P2 | `minecraft:ender_pearl` |

- `social/married` 在 P1 靠读取玩家 Capability 里的婚姻指针实现：登录时检查，另外在自动保存时、打开共享背包时各补查一次。P2 加了婚礼监听接口后改成当场触发。
- 实现口径（P1 钩子，`trigger.PlayerProgressHooks`）：
  - 婚姻指针读的是核心模块的 `IMiningPlayerData`，`marriageId` 不为 `NO_MARRIAGE` 或配偶 UUID 非空即算已婚。
  - "自动保存"取主世界的 `LevelEvent.Save`，所以 `/save-all` 也会补查一次；其他维度的存档事件忽略。
  - 共享背包按菜单的注册名 `miningdim:marriage_backpack` 识别（`PlayerContainerEvent.Open`）。婚姻模块只在核实了有效婚姻之后才打开这个菜单，所以先补查 `married` 再触发 `open_shared_backpack`：刚办完婚礼、登录与存档的补查都还没轮到的玩家（`/save-off` 时要等到下次登录），不会在父成就"执子之手"之前拿到"两人的口袋"。
  - 这两条都不引用婚姻模块。

### 9.6 成就（3 条）

| id | 名称 | 条件 | 档位 | 父 | 触发器 | 期 | 图标 |
|---|---|---|---|---|---|---|---|
| `meta/count_10` | 初露锋芒 | 获得 10 个成就 | 银 | root | `achievement_count{count=10}` | P1 | `minecraft:amethyst_shard` |
| `meta/count_25` | 收藏家 | 获得 25 个成就 | 金 | count_10 | `achievement_count{count=25}` | **随 P2 开放** | `minecraft:diamond` |
| `meta/count_40` | 功勋卓著 | 获得 40 个成就 | 白金 | count_25 | `achievement_count{count=40}` | **随 P2 开放** | `minecraft:nether_star` |

P1 只有 30 条成就（扣掉 meta 本身，计数池里只有 29 条），"获得 25 个 / 40 个"在 P2 上线前几乎无法达成，所以这两条随 P2 一起开放（2026-09-26 拍板）。
"完成整个页签"这类成就不做：目标会随每次更新变化。

### 9.7 页签根进度

| 根进度 | 名称 | 图标 | 背景（P1 借用原版贴图） |
|---|---|---|---|
| `mining/root` | WOK · 矿区 | `minecraft:deepslate_iron_ore` | `minecraft:textures/block/deepslate.png` |
| `combat/root` | WOK · 战斗 | `minecraft:shield` | `minecraft:textures/block/blackstone.png` |
| `profession/root` | WOK · 职业 | `minecraft:crafting_table` | `minecraft:textures/block/spruce_planks.png` |
| `economy/root` | WOK · 经济 | `minecraft:emerald` | `minecraft:textures/block/bricks.png` |
| `social/root` | WOK · 社交 | `minecraft:bell` | `minecraft:textures/block/cherry_planks.png` |
| `meta/root` | WOK · 成就 | `minecraft:knowledge_book` | `minecraft:textures/block/quartz_block_side.png` |

### 9.8 附带的称号（14 个，文案已认可）

| 称号 id | 文字 | 稀有度 | 来源成就 |
|---|---|---|---|
| `miningdim:mining/ore_codex` | 矿物学者 | 金 | 矿脉图鉴 |
| `miningdim:mining/blocks_100k` | 地脉行者 | 钻石 | 地脉行者 |
| `miningdim:mining/hard_active_100h` | 深渊守望者 | 大师 | 深渊守望者 |
| `miningdim:combat/long_shot` | 鹰眼 | 金 | 百米狙杀 |
| `miningdim:combat/star_10` | 弑神者 | 钻石 | 十星弑神 |
| `miningdim:combat/solo_star_9` | 一人成军 | 传说 | 一人成军 |
| `miningdim:profession/max_level` | 行业专家 | 白金 | 行业专家 |
| `miningdim:profession/journal_100` | 钓遍基沃托斯 | 钻石 | 图鉴大全 |
| `miningdim:profession/all_max` | 万事通 | 大师 | 全职精通 |
| `miningdim:economy/tycoon` | 商业大亨 | 大师 | 商业大亨 |
| `miningdim:economy/lucky_case` | 欧皇 | 钻石 | 欧皇降临 |
| `miningdim:economy/all_in` | 大人的卡 | 铜 | 倾家荡产 |
| `miningdim:social/daily_clear_60` | 全勤老师 | 钻石 | 全勤 |
| `miningdim:meta/count_40` | 功勋卓著 | 白金 | 功勋卓著 |

称号定义文件放在 `data/miningdim/titles/`，文案 key 用 `title.miningdim.<分类>.<名称>`。

### 9.9 上线时的追溯发放 (DECIDED)

- **查得到的历史进度，登录时静默补发**：
  - 职业等级：读取 `wok-job-core`
  - 婚姻状态：读取 Capability 中的婚姻指针
  - 开箱记录：通过开箱模块新增的只读接口 `settledOpenings(owner)` 查询
  - 钓鱼记录：`FishingRecords` 中亲手钓到的鱼种
  - "神射手"任务线：通过只读接口 `chainFinished(UUID, chainId)` 查询
- **静默**的意思是：补发时不做全服公告，也不弹 Toast，但照常生成待领取奖励。实现方式是补发期间设置一个线程局部标志，在原版公告处检查它，具体在实现期确定（TODO）。
- **从零开始计**：挖方块、撤离、击杀、收获、`credits_earned`、任务领取次数。这些计数没有可靠的历史数据。
- `/job set` 或管理员直接改等级也会触发职业成就，不做区分。

### 9.10 P2 需要的监听接口

全部参照 `MiningServices.registerInstanceResetListener` 的写法：在状态提交之后或方法末尾触发，异常由成就侧的监听器自己捕获并记录日志，不能影响调用方。

| 模块 | 接口 | 触发位置 | 用途 |
|---|---|---|---|
| `wok-experience` | `ExperienceServices.registerAwardListener` | `ExperienceRouter#award` 末尾，携带发放前后的等级 | 职业等级、农夫收获 |
| `wok-economy` | `EconomyServices.registerFaucetListener` | `EconomyService#grantDaily` 账本事务提交后（嵌套在批量事务里时，需要一个提交后队列） | `credits_earned` |
| `wok-market` | `MarketServices.registerTradeListener` | `MarketEngine#buy` 的事务提交后 | 市场成就 |
| `wok-case-opening` | `CaseServices.registerOpeningListener` 和只读接口 `settledOpenings` | `markEconomySettled` 所在事务提交后，每个开箱 id 只触发一次（正常流程和两条恢复流程都要覆盖） | 开箱成就、追溯 |
| `wok-quest` | `QuestServices.registerClaimListener` 和只读接口 `chainFinished` | `QuestService#claim` 最后一条语句。监听器列表不能在 `QuestServices.reset()` 时被清空 | 任务成就 |
| `wok-marriage` | `MarriageEvents.registerWeddingListener` / `registerTeleportListener` | 婚礼成功返回前；传送完成时 | 婚姻、千里赴约 |
| `wok-job-fisher` | `FishingEvents.addCatchListener` 和亲手钓到鱼种的只读接口 | `FishCatchService#onSuccessfulCatch` 中记录之后 | 钓鱼成就 |
| `wok-job-chef` | `ChefEvents.addDishListener` | `SeasoningTableBlockEntity#finishCooking` 发放经验之后 | 厨师成就 |
| `wok-job-brewer` | `BrewerEvents.addBrewListener` | `BrewingStationBlockEntity#grantBrewXp` 的在线操作者分支 | 酿酒成就 |
| `wok-job-tarot` | `TarotEvents` 出牌监听 | `TarotPlayHandler#tryPlay` 结算之后 | 塔罗成就 |
| `wok-job-agent` | `AgentEvents` 封印监听 | `AgentSealHandler#requestSeal` 成功分支 | 特勤成就 |
| `wok-job-armorer` | `EngineerEvents` 生产监听 | `ProductionTableBlockEntity#finishProduction`，产出大于 0 时 | 工程师成就 |
| `wok-job-munitions` | `MunitionsEvents` 批次监听 | `MunitionsBenchBlockEntity` 两个经验结算点 | 军火成就 |

### 9.11 第二批候选（已评审，暂缓）

以下点子评审通过，但为了维持档位金字塔暂缓，留作第二批的素材：
- **矿区**：困难矿区长时间作业、困难矿区无伤撤离、远古残骸 128 块、清空整波压力怪
- **战斗**：枪械 500 杀、濒死反杀、8 星单挑、闪电战、词条图鉴、拆除"小男孩"
- **职业**：矿石鱼全收集、五种鱼羹、塔罗系列（高塔、愚者之旅、首张 UR、闪卡）、特勤扫描和拆弹、纳米反应堆救命、闪耀料理 50 份
- **经济**：开箱 10 次、皮肤收集、青辉石 500、市场回头客
- **社交**：配偶互传、活跃 7 天 / 30 天、婚龄 30 天 / 100 天

其中两个点子同名都叫"拆弹专家"，以后启用时要改掉一个。

发电机、线缆等科技类成就按拍板暂不做。

---

## 十、性能约束 (DECIDED)

1. **高频事件先过滤再处理。** 在方块破坏、生物死亡事件里，先判断维度和实体类型；不在矿区维度、不是精英怪的直接返回，然后才递增统计项或调用触发器。
2. **不做 tick 轮询。** 所有条件都由事件驱动；状态类条件（等级、图鉴完成度）在状态变化的那一刻检查。
3. **触发器依靠原版的监听机制。** 原版只为玩家尚未完成的条件注册监听器，完成后自动移除。触发器的 `trigger()` 里只做轻量的条件判断，不访问数据库。
4. **只在低频时刻访问数据库**：解锁、领取、兑换、打开成就点商店页面。
5. **网络**：解锁提示用原版 Toast，本模块不额外发包。G 面板的数据只在打开页面时拉取，走现有的 batch 合并请求。
6. 按当前规模（在线约 50 人），数据库读写在主线程同步完成即可。

---

## 十一、管理员命令 (DECIDED)

命令根为 `/machievement`。普通玩家可以用 `claim`，以及不带玩家参数的 `pending`、`points`（只看自己的）；其余需要权限等级 2。命令根本身不设权限，由各子命令分别判断（与 `/mtitle` 相同）。以后可能会统一成 `/wok` 指令集，届时迁移到那里；现阶段先用独立的命令根。

| 命令 | 权限 | 作用 |
|---|---|---|
| `/machievement claim <进度id\|all>` | 玩家 | 领取自己的奖励（聊天里的 [领取] 按钮会调用这条）。进度 id 的补全候选是自己的待领取奖励 |
| `/machievement pending` | 玩家 | 查看自己的待领取奖励，每条带 [领取]，表头带 [全部领取] |
| `/machievement points` | 玩家 | 查看自己的成就点余额和累计获得 |
| `/machievement pending <玩家>` | 2 | 查看该玩家的待领取奖励 |
| `/machievement points <玩家> [add\|remove <数量>]` | 2 | 查看或调整成就点，流水 reason 记为 `admin` |
| `/machievement check` | 2 | 重新执行第 4.1 节的一致性校验并输出结果 |

授予或撤销进度本身用原版的 `/advancement` 命令，本模块不重复实现。

实现口径（P1）：

- 命令类是 `command.AchievementCommands`，由 `reward.AchievementRewardSystem` 在 `RegisterCommandsEvent` 里注册。
- 管理员子命令的玩家参数按 GameProfile 解析，用户缓存里查得到的离线玩家同样可以查看和调整。
- `points add` 同时增加余额和累计获得（仓库只有这一种入账）。`points remove` 最多扣到 0，不会出现负余额，累计获得不变；实际扣了多少，流水就记多少，余额已经是 0 时不写流水。两者的流水 ref 都记执行者名字，每次调整另写管理日志 `miningdim/achievement/admin`。
- `check` 拿当前的元数据快照和服务端实际加载的进度重新校验，逐条列出问题；没有问题时返回 1，有问题时返回 0。"有元数据、没有对应进度"的单独列一行，不算问题（加载条件不满足时属于正常情况，见 4.1 节实现口径）。

---

## 十二、测试 (TODO)

GameTest 放在 `com.miningdim.achievement`，使用 `testutil.TempStoreDb` 提供的临时库：

- 获得进度后生成待领取奖励；撤销后再次授予不会重复生成
- 领取时点数、流水、称号在同一事务里；事务回滚后没有残留；重复领取返回 `REWARD_ALREADY_CLAIMED`
- 成就点商店：余额不足、达到限购、背包已满时都不扣点；称号类商品发放成功
- 一致性校验：框体和档位不一致、缺少元数据、称号 id 不存在时都能报出来
- 统计项与 `stat_at_least` 触发器的阈值判定
- 非矿区维度破坏方块不递增统计项
- WebUI 契约：`achievement.pointShop`、`achievement.pointShopBuy`、`achievement.claimRewards`（仿照 `QuestWebUiGameTests`）

事件钩子的 GameTest 在 `trigger.AchievementHookGameTests`（batch `achievement_hooks`），覆盖以下内容：

- 有效撤离：停留不足、挖掘不足、途中阵亡都不算；被更高优先级取消的死亡不作废行程。进出矿区用真实的跨维度传送。
- 行程生命周期：下线作废、在矿区里上线重开（补触发 `enter_mining`，不加 `mining_entries`）、死后重生丢弃。
- 每日上限：两个计数键各自独立计。
- 困难作业时长：单段间隔封顶与总量封顶。
- 死里逃生的威胁判据。
- 计数挖掘的全部排除条件。
- 陷阱：经事件总线由陷阱系统先取消，本模块后接收。
- 精英击杀：经事件总线验证本模块读账本早于主结算清账；另测击杀过滤与挂机。
- 独自击杀、输出占比与战斗时长的计算。
- 枪械击杀的过滤。
- 成就数量只随计数成就变化。
- 婚姻指针在登录与存档时触发。
- 共享背包菜单；打开时先补 `married`，父成就不落在子成就之后。

奖励与领取的 GameTest 在 `reward.AchievementRewardGameTests`（batch `achievement_rewards`），覆盖以下内容：

- 获得成就写一条快照奖励并提示；撤销后再授予不重复生成；页签根、别的命名空间、缺元数据、0 点无称号的进度不产生奖励。
- 单条领取与全部领取：标记、入账、流水、称号一并提交，提交后只为新得到的称号提示。
- 任何一步失败整次回滚，包括事务里已经发出（`GRANTED`）的称号：库里与在线持有集合都没有它，也不发提示。全部领取失败的命令反馈写明其余奖励仍可逐条领取。
- 领取与管理员调整在调用方已开着的事务里一律拒绝，事务外照常可用。
- 重复领取、不存在的奖励、没有待领取。
- 真实命令分发器下的权限、补全、反馈与落库。

---

## 十三、分期

| 期 | 内容 |
|---|---|
| P1 | 模块骨架、档位、datagen、元数据校验、P1 统计项（第 6.1 节）、P1 触发器、有效撤离与计数挖掘、击杀过滤、六个页签、第九章的 30 条 P1 成就、待领取奖励与成就点账本、`/machievement`、GameTest |
| P2 | 第 9.10 节的监听接口、P2 统计项与触发器、第九章的 40 条 P2 成就（含两条 meta 成就）、上线追溯（9.9）、市场防刷表（6.6）；G 面板成就点商店页（含"我的称号"页签） |
| P3 | 新做装饰物和道具的物品与贴图、页签背景图、扩充商品；视情况用"拥有某成就"作为困难难度门槛（`entry.GateResult` 已为成就预留了原因码，对应矿区文档中的 TODO） |

实施顺序：先做称号系统 P1（成就模块依赖它），再做本模块 P1。

## 十四、待定项

| # | 事项 | 状态 |
|---|---|---|
| 1 | 各档默认成就点数、首批成就的阈值 | DECIDED（2026-09-26，第九章） |
| 2 | 哪些成就附带称号，以及对应的称号文案 | DECIDED（2026-09-26，9.8 节） |
| 3 | 成就点商店首批商品与价格（依赖 P3 美术） | PENDING |
| 4 | 页签背景图 | TODO（P3） |
| 5 | 多子服架构下原版进度文件如何共享（成就点和称号已通过 Repository 接口预留） | PENDING |
| 6 | 排行榜（J12 的另一半） | PENDING |
| 7 | 有效撤离的阈值：停留 6000 tick、至少 32 块、每日上限 5 次和 2 次（困难）（6.3 节） | DECIDED（2026-09-26） |
| 8 | "十星弑神"的档位（取决于世界 BOSS 事件的频率） | PENDING |
| 8b | "一人成军"改为独自击倒 9 星，传说档 | DECIDED（2026-09-26） |
| 9 | 世界 BOSS 事件机制，以及困难矿区自然刷出上限改为 9 星，属于 `wok-champion` 的改动 | DONE（2026-09-26）：自然上限 9 星；世界 BOSS 暂由 `/mchampion worldboss` 指令召唤，出现与被击倒都全服公告。定时或自动刷新的事件机制待定 |
| 10 | `tide:midas_fish` 在生存模式下能否获得（决定是否计入图鉴大全） | TODO（实现期核实） |
| 11 | 上线初期所有人的 `credits_earned` 都是 0，会压低市场计数成交额：是用账本历史预填，还是上线满 N 天后才启用买家封顶 | PENDING |
| 12 | 离线模式下玩家 UUID 由用户名生成，改名等于换号，成就、成就点、称号都会丢失，和服务器上其他数据一样。需要运维方面的迁移方案 | PENDING（运维） |
