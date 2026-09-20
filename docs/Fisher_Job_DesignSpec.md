# 渔夫 职业 Mod — 设计规格文档

## 文档元信息

- 用途: 渔夫模块（规划中的第 9 职业）的唯一主设计入口。本文只登记代码里已经存在的事实与已裁定的待决项，不预写尚未实现的玩法。
- 目标平台: Minecraft 1.20.1 + Forge 47.3.0 + Java 17。
- 模块边界: 并入 WOK 本体，沿用 `modId=miningdim`、Java 包根 `com.miningdim` 与单 JAR 交付，不另建强制安装的附属 MOD。入口 `com.miningdim.job.fisher.FishingSystem`，源码归属 `job/fisher`，模块注册表 id 为 `wok-job-fisher`（`docs/modules/module-registry.json`）。
- 专题子文档: [渔业图鉴框架](Fishing_Journal_Framework.md)（目录数据格式、界面、存档与网络协议）、[矿石鱼与鱼羹](Ore_Fish_And_Soup.md)（钓获/出售/料理的数值与历次验证记录）。两者是本文的专题展开；口径冲突时以本文与代码为准。
- 模块依赖（注册表登记）: `wok-core`、`wok-store`、`wok-economy`、`wok-job-chef`；可选集成 `farmersdelight`、`tide`。
- 状态图例: DECIDED 已落地 / PENDING 待拍板 / DEFERRED 已确认未实现。

---

## 一、职业定位与经济角色 (DECIDED)

1. 定位: **矿洞水域的原料产出职业**。玩法闭环是"在矿洞维度钓鱼 -> 拿到矿石鱼 -> 卖信用点，或交给厨师做成鱼羹再回到矿洞挖矿"。延续全项目职业设计哲学（FF14 生产职业思路）：主打经济产出，战斗增益为零。
2. 战斗力红线: 五种鱼羹给的全部是**挖矿效率与续航**（夜视、挖速、工具耐久、饥饿消耗），没有一条改伤害、护甲、血量或移速，且全部以维度 `miningdim:mining` 为生效门槛（`OreSoupEffects.activeInMining`）。矿洞外只计时不生效。
3. 经济角色: 卖鱼是 faucet（对外注入信用点），鱼是 P2P 原料（供厨师或自制鱼羹）。faucet 并入全服统一衰减主闸，不另开计数键，详见第十章。
4. 与厨师的关系: 鱼羹走既有厨师品质/料理效果流程（`ChefQualityNbt`），厨师只能延长鱼羹时长，不能提升鱼羹强度（第四章）。
5. 与矿工的关系: 钻石/暗金鱼羹的挖速加成进 `MinerSystem.onBreakSpeed` 统一结算，不另开一条挖速路径。
6. 产出量级（取自经济总表 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 渔夫行，与第三章权重/售价自洽）: 每次成功钓获期望 28.4 CP；不带附魔约 5,700 CP/小时，饵钓（Lure）III 约 25,600 CP/小时。缩短咬钩间隔的是饵钓，不是海之眷顾——海之眷顾只改战利品类别权重，而矿石鱼是在钓上来之后无条件替换渔获的，对渔夫收益严格零影响。

---

## 二、等级曲线与 XP 来源 (DEFERRED — 尚未落地)

**事实**: 渔夫目前没有职业身份、等级、经验与技能。

- `JobId` 枚举当前 8 个成员（MINER / FARMER / ENGINEER / TAROT / CHEF / AGENT / MUNITIONS / BREWER），**没有 FISHER**；`JobCommands` 全文没有渔夫分支，`/job` 系列命令查不到渔夫。
- `job/fisher` 整包没有任何经验入账调用：钓获（`OreFishCatchHandler`）、出售（`OreFishSellService`）、图鉴收录（`FishingJournalService`）三条路径只改物品、信用点与收藏集合，一处都不碰 `JobProgress`。图鉴收录刻意不冒充亲手钓获，也不发经验。
- 模块也不记录独立的"亲手钓获"统计：`FishingJournalSavedData` 只存"该 UUID 收录过哪些物品 ID"的集合，没有计数、没有来源区分。

**一旦落地会继承什么（事实陈述，非承诺）**:

- 经验轨道 id 由枚举派生：`JobExperienceTracks.track(job)` 返回 `miningdim:job/<id>`，`legacySource(job)` 返回 `miningdim:legacy/job/<id>`，追加枚举成员即自动得到轨道，无需另立注册。
- 等级曲线是全职业共享的单一拷贝 `JobXpCurve`：L1 到 L10 累计 61,900 有效经验（逐级 3,300 / 3,800 / 4,500 / 5,300 / 6,300 / 7,400 / 8,800 / 10,300 / 12,200），满级后超额经验仍累计但不再升级；每日有效经验软上限衰减按当日已累计有效经验分段 [0,2000) x1.0 / [2000,2800) x0.4 / [2800,3400) x0.2 / [3400,3800) x0.08 / [3800,+inf) x0.02，UTC 翻日重置。渔夫无需也不得另立一张曲线表。
- 追加成员必须**尾部追加**：`JobSyncS2C` 按 `values()` 顺序读写，插在中间会破坏同序契约（理由见 `JobId` 类注释，酿酒师 BREWER 即按此规则追加为第 8 个）。

**直接后果**: 卖鱼的反洗钱身份门没有等级可依，见第十章。

---

## 三、矿石鱼: 稀有度、权重与卖价 (DECIDED)

### 3.1 钓获条件与接入点

仅在维度 `miningdim:mining` 的**水**方块上成功钓获时参与抽取；失败、岩浆与其它维度一律不产矿石鱼。命中后用**一条**矿石鱼替换该次原渔获（`drops.clear()` 后放入一条），不额外叠加；后续 Forge 事件取消仍能阻止掉落。不提供把矿石鱼拆成矿物或烧炼的配方。

两条接入路径共用同一张权重表（`OreFishCatchHandler.typeForRoll`，roll 取 `[0, 10000)`）：

| 路径 | Mixin | 所在配置 | 注入点 |
| --- | --- | --- | --- |
| 原版钓竿 | `mixin/VanillaOreFishMixin` | `miningdim.mixins.json`（`required=true`） | `@Redirect` 掉 `FishingHook.retrieve` 里的 `LootTable.getRandomItems`，在真实战利品生成之后替换 |
| Tide 1.6.5 | `mixin/compat/TideOreFishMixin` | `miningdim.compat.mixins.json`（`required=false`） | `@Inject` 到 `TideFishingHook.retrieve` 第一次读 `hookedItems` 之前，即小游戏判定成功、掉落生成之前 |

两者都在服务端判维度与 `FluidTags.WATER` 之后才动手。

### 3.2 鱼种表

| 鱼种（简中物品名） | 物品 ID（miningdim） | 配置键 | Rarity | 万分权重 | 概率 | 单条基础信用点 |
| --- | --- | --- | --- | --- | --- | --- |
| 铁鱼 | `iron_ore_fish` | `iron` | COMMON | 2000 | 20% | 20 |
| 金鱼 | `gold_ore_fish` | `gold` | UNCOMMON | 800 | 8% | 80 |
| 钻石鱼 | `diamond_ore_fish` | `diamond` | RARE | 200 | 2% | 400 |
| 绿宝石鱼 | `emerald_ore_fish` | `emerald` | RARE | 100 | 1% | 600 |
| 暗金鱼 | `dark_gold_ore_fish` | `dark_gold` | EPIC | 20 | 0.2% | 2000 |

- 权重与售价的默认值来自 `OreFishingConfig`，Rarity 来自 `OreFishType`。剩余 6,880/10000（68.8%）保留原渔获。
- 简中显示名（`lang/zh_cn.json`）五条都没有"矿"字；英文显示名反而带 Ore（`Iron Ore Fish` 等）。简中的品类统称仍是"矿石鱼"，对应分类键 `ore_fish`（`fishing.miningdim.category.ore_fish`）。本文表格首列用游戏内真名，正文里的"矿石鱼"一律指品类。
- 鱼与对应鱼羹共用同一个 Rarity（`OreFishingItems` 两处都写 `.rarity(type.rarity())`），Rarity 直接决定物品名在游戏内的显示颜色。绿宝石鱼比钻石鱼更稀有（权重 100 对 200）也更贵（600 对 400）却同为 RARE：Rarity 只有四档显示色，档次差由权重与售价承担，"鱼种档次"序列与 Rarity 序列不是线性同构。这是当前实现的取舍，不是笔误。
- 暗金鱼是当前最高鱼种档次（`OreFishItem` 对 `DARK_GOLD` 额外挂 `tooltip.miningdim.ore_fish.highest`）。鱼种档次与厨师给成品菜盖的加工品质是两套，互不换算。

### 3.3 物品标签

`data/miningdim/tags/items/ore_fish.json` 把五种矿石鱼聚合为 `miningdim:ore_fish`，已登记进模块注册表的 `resourcePaths`。**当前全库无消费方**：Java 侧没有对应的 `TagKey` 常量，十条鱼羹配方引用矿石鱼时用的也都是具体物品 ID。保留它是给下游配方/任务预留聚合点；若确认长期无人消费，应连同注册表登记一并删除，不留无主资源。

---

## 四、鱼羹: 效果、时长与携带规则 (DECIDED)

### 4.1 物品与配方

- 鱼羹物品 ID = 鱼 ID 加 `_soup`（`OreFishType.soupId()`），共五种，简中名为铁鳞鱼羹 / 金鳞鱼羹 / 钻鳞鱼羹 / 翠玉鱼羹 / 暗金鱼羹。
- 食物属性（`OreFishingItems`）：饱食 8、饱和系数 0.6（即饱和 9.6）、`alwaysEat`（吃饱时也能喝）、最多堆叠 16、`craftRemainder` 为碗；生存模式喝完由 `OreFishSoupItem.finishUsingItem` 显式返还一只碗（背包满则掉在脚边），创造模式不返还。
- 基础配方（无序合成）：对应矿石鱼、红/棕蘑菇任一、胡萝卜/马铃薯/甜菜根任一、碗，各一份，产出一碗。
- 装 Farmer's Delight 时额外提供烹饪锅配方（`farmersdelight:cooking`，`forge:mod_loaded` 条件门控），蘑菇与蔬菜改用 `forge:mushrooms`、`forge:vegetables` 标签，容器为碗，经验 1.0、烹饪 200 tick，仍只出一碗。

### 4.2 矿洞内收益

| 鱼羹 | 矿洞内收益 | 代码来源 |
| --- | --- | --- |
| 铁鳞鱼羹 | 饥饿消耗降低 25%（250 per mille） | `OreSoupEffects.exhaustionReductionPerMille` |
| 金鳞鱼羹 | 夜视 | `OreSoupEffects.refreshNightVision` |
| 钻鳞鱼羹 | 挖掘速度 +15 个百分点 | `OreSoupEffects.miningSpeedBonusPercent` |
| 翠玉鱼羹 | 挖方块时工具耐久磨损降低 20%（200 per mille） | `OreSoupEffects.durabilityReductionPerMille` |
| 暗金鱼羹 | 夜视、挖速 +20 个百分点、磨损降低 25%、饥饿消耗降低 25% | 同上四处 |

结算细节：

- **挖速**: 在矿洞 region 内与矿工等级倍率**相加**后统一结算（`MinerSystem.onBreakSpeed`：`mult = MinerSkills.digSpeedMultiplier(level) + soupBonus/100`）；在矿洞维度内但不在 region 内时，按 `newSpeed * (1 + soupBonus/100)` 单独乘一次。客户端挖速预测靠 `PlayerOreSoupStateMixin` 同步的整型汤态（0 表示无汤，否则为 `OreFishType.ordinal()+1`）。
- **饥饿**: 走 `PlayerFoodExhaustionMixin` 重定向 `Player.causeFoodExhaustion`，疲劳乘 `(1000 - reduction)/1000`。矿洞内只要有任意一种鱼羹在身，厨师 `ENDURANCE` 就改走这条路径结算（`ChefHungerHandler` 的早退条件是 `OreSoupEffects.activeInMining`，`SATIATION` 在身时例外），鱼羹自带的 25% 与厨师耐饥取较强者，避免双重结算。副作用是：喝本身零耐饥的金/钻/翠玉鱼羹，同样会把厨师耐饥从"每 40 tick 按比例回补饱和"换成疲劳缩减口径，效果没丢但数值口径不同。
- **夜视**: 每次授予 400 tick（20 秒），状态刚变成"矿洞内有汤"的那一 tick 立即补发，此后按 `(gameTime + playerId) % 80 == 0` 错峰续期。汤把自己授予的到期时刻记在 `nightVisionUntil`，只有当前夜视实例的剩余时长与该记录相差不超过 3 tick 时才会移除，外部药水与厨师夜照因此不会被误删。下线时先撤掉汤夜视（离线期间药水时长暂停而世界时钟继续），重登后按剩余汤时间重新授予。
- **耐久**: 减免发生在 `ItemStack.hurt` 的耐久附魔裁决之后、破坏判定之前（`ItemStackMiningDurabilityMixin` + `OreSoupEffects.withMiningDurabilityAttempt` / `reduceMiningDamageAfterUnbreaking`），并按实际写回的 damage 重算损坏判定，否则减免会在工具最后一点耐久上把工具赔掉。只认"这次扣的正好是挖方块那一点"（`damageAfterUnbreaking == stack.getDamageValue() + 1`），攻击与其它耐久消耗不受影响。

### 4.3 时长、AMPLIFY 上限与替换规则

- 基础时长 `BASE_DURATION_TICKS = 300 * 20`，即 300 秒。
- 只有菜上实际盖章的厨师 `AMPLIFY` 能延长时长，取该菜上 AMPLIFY 的**最大** magnitude 作百分比乘数（无 AMPLIFY 时按 100），封顶 `MAX_DURATION_TICKS = 1500 * 20`，即 1500 秒（25 分钟）。AMPLIFY 只加时长，**不提升上述任何强度**。
- 按厨师品质默认档（`ChefConfig` 的 low/medium/high/extraordinary/radiant = 120/150/200/300/500）换算：360 / 450 / 600 / 900 / 1500 秒，闪耀档恰好顶到上限。
- `SPOILED`（失败品）不给予任何汤增益：`applyConsumedSoup` 直接早退，**且不清除已在身的汤**——喝失败品不会把好汤顶掉。
- 任意维度都能食用并开始计时；矿洞外不享受收益但时间继续流逝。重复同种刷新，改喝另一种替换，时长不累加。

### 4.4 跨死亡与跨重生的携带规则

- 汤态存在玩家 `getPersistentData()` 根节点的 `MiningOreFishSoup` 复合标签里，字段 `type`（`OreFishType.id()`）、`expiresAt`（世界 gameTime 绝对时刻）、`nightVisionUntil`。
- **死亡清除**：`PlayerEvent.Clone` 在 `isWasDeath()` 为真时什么都不搬，汤态随旧实体丢弃。
- **非死亡的实体重建保留**：正常退出再进入、以及从末地主出口回主世界这类走 `PlayerList.respawn` 的路径，都保留未过期状态与**原到期时刻**。这一步必须显式搬运，因为 Forge 的 `restoreFrom` 只复制 `PlayerPersisted` 子标签，挂在根节点的汤态会被丢掉。
- 搬运时刻意**不搬** `nightVisionUntil`：重建出来的玩家身上一个效果都没有，搬过去只会留下一个指向旧时刻的归属记录，万一玩家自己喝的夜视剩余时长撞上这个值，就会把别人的药水当成汤夜视删掉。下一 tick 的 `refreshNightVision` 会重新授予并重新记归属。
- 过期判定用世界 gameTime，因此离线时间照样计入消耗。

---

## 五、渔业图鉴 (DECIDED)

完整规格见 [渔业图鉴框架](Fishing_Journal_Framework.md)，此处只列本文需要的口径：

- 图鉴物品 `miningdim:fishing_journal`，一本书加一个墨囊无序合成，只堆叠 1 本；右键打开，或执行 `/fishing journal`。
- 目录由服务端数据包唯一决定（`data/<namespace>/fishing/journal/*.json`，`FishingJournalCatalog`）。内置三份：`vanilla.json` 4 条、`ore_fish.json` 5 条、`tide_1_6_5.json` 66 条。缺少 Tide 时加载 9 条；装 Tide 1.6.5 时 75 条；Tide 版本不等于 1.6.5 则跳过该兼容文件并记 WARN。
- 分类共 11 个：WOK 自建的 `ore_fish`，加上沿用 Tide 的 freshwater / saltwater / underground / depths / biome / structure / lava / nether / end / legendary。
- 收录规则: 持有即收录，交易取得也算，卖出/食用/存箱后保留记录；鱼桶、鱼实体与背包外的箱子不算。收藏按 UUID 存在主世界 SavedData `miningdim_fishing_journal`，跨死亡、重生、维度切换与重启保留。
- 图鉴**不发经验、不记亲手钓获**，它只是查阅与收藏进度。
- 硬上限: 目录最多 512 条，分类键 1–32 位小写字母数字下划线，文本翻译键非空且不超过 128 字符。物品不存在、鱼种重复或字段非法时拒绝发布这次目录，保留此前完整目录。
- 网络: 通道 `miningdim:fishing_journal`，协议版本 1，唯一消息是服务端到客户端的目录/收藏快照，客户端不上传收藏状态。

---

## 六、玩家命令 (DECIDED)

模块注册的命令只有 `/fishing` 一支（`FishingSystem.onCommands`），两个子命令：

| 命令 | 作用 | 权限门 |
| --- | --- | --- |
| `/fishing sell` | 出售主手**整组**矿石鱼，按第三章售价乘数量算毛收入，实发过每日衰减主闸 | 无 `.requires`，全员可用，无 OP 门也无职业门 |
| `/fishing journal` | 先扫一遍背包补收录，再下发快照并打开图鉴界面 | 无 `.requires`，全员可用 |

`/fishing sell` 的四种结果（判定优先级见 `OreFishSellService.sellMainHand`，回执由 `executeSell` 发出）：

1. 主手不是矿石鱼（鱼羹也不算，`typeFor` 只匹配 `OreFishingItems.FISH`）-> `sendFailure`，不扣不发；
2. 经济系统未注册 -> `sendFailure`，不扣不发，明确失败；
3. 实发为零 -> `sendSuccess` 单列一条"卖出 N 条但当日收益已衰减到零"，**鱼照扣**；
4. 正常成交 -> `sendSuccess` 报条数与信用点。

两条 `sendSuccess` 回执都传 `broadcastToAdmins = false`，与 `/farmer sell` 同口径：卖鱼是普通玩家的日常动作，不该刷 OP 聊天与服务端日志。

---

## 七、配置项 (DECIDED)

服务端配置一份：`world/serverconfig/miningdim-fishing.toml`，由 `FishingSystem.register` 以 `ModConfig.Type.SERVER` 注册，spec 来自 `OreFishingConfig`。

| 分节 | 键 | 类型 | 默认值 | 取值范围 |
| --- | --- | --- | --- | --- |
| `[catch_weights]` | `iron` | int | 2000 | 0–10000 |
| `[catch_weights]` | `gold` | int | 800 | 0–10000 |
| `[catch_weights]` | `diamond` | int | 200 | 0–10000 |
| `[catch_weights]` | `emerald` | int | 100 | 0–10000 |
| `[catch_weights]` | `dark_gold` | int | 20 | 0–10000 |
| `[sell_prices]` | `iron` | long | 20 | 1–1000000 |
| `[sell_prices]` | `gold` | long | 80 | 1–1000000 |
| `[sell_prices]` | `diamond` | long | 400 | 1–1000000 |
| `[sell_prices]` | `emerald` | long | 600 | 1–1000000 |
| `[sell_prices]` | `dark_gold` | long | 2000 | 1–1000000 |

- 键名是 `OreFishType.id()`（`iron` / `gold` / `diamond` / `emerald` / `dark_gold`），**不是**物品 ID `iron_ore_fish` 那一套。照物品 ID 去 toml 里找会找不到。
- 五个权重之和超过 10000 时，`ModConfigEvent.Loading` 与 `ModConfigEvent.Reloading` 都会走 `OreFishingConfig.validate()` 抛 `IllegalArgumentException`，配置直接拒绝加载——这是故意的硬失败，不是可忽略的告警。
- **覆盖缺口（Minor）**: 鱼羹侧的全部数值目前都是 `OreSoupEffects` 里的硬编码常量，没有一项进 toml——基础/最长时长（300 秒 / 1500 秒）、夜视授予时长与续期周期（400 tick / 80 tick）、饥饿与耐久减免的 per mille 值（250 / 200 / 250）、挖速百分点（15 / 20）。与本仓"全数值进 config、硬编码即缺陷"的既定纪律不一致，运营期若要调平衡必须改代码重编译。是否迁入 toml 待拍板。

---

## 八、与前置 mod Tide 的关系 (DECIDED)

Tide 是**可选**集成，不是强依赖：不装 Tide 模块照常工作，装了也不改它的 JAR、不复制它的代码/贴图/描述正文，只引用其已有的翻译键与物品模型。

版本口径与降级路径分两条，互相独立：

1. **图鉴兼容目录**（数据层）: `tide_1_6_5.json` 带 `required_mod: "tide"` 与 `required_version: "1.6.5"`。`FishingJournalCatalog.isAvailable` 的判定是——没装 Tide 则整份文件跳过；装了但版本字符串不等于 `1.6.5`，记一条 WARN（`Skipping journal compatibility data for tide 1.6.5: installed X`）后整份跳过，目录回落到 9 条。`required_version` 必须伴随 `required_mod`，只写版本会直接抛错。
2. **钓获接入**（字节码层）: `TideOreFishMixin` 按 Tide 1.6.5 的 `retrieve` 方法签名与 `hookedItems` / `fluid` 两个私有字段写死，所以**不放主 mixin 配置**，单独进 `miningdim.compat.mixins.json` 并置 `required: false`。Mixin 0.8.5 的 `MixinProcessor.handleMixinError` 按配置的 `isRequired()` 决定抛 `MixinApplyError` 还是只记 WARN：玩家装上别的 Tide 版本时，非必需配置只停用这一个 mixin，不会让整个服务端启动崩掉。`@Pseudo` 只免疫"目标类不存在"，签名对不上照样要靠 `required=false` 兜底。
   该 mixin 停用后，Tide 自己的钓鱼流程照常工作，只是不再替换出矿石鱼；原版 `FishingHook` 路径的替换（`VanillaOreFishMixin`，在主 mixin 配置里，`required=true`）不受影响。

其它口径：

- 兼容用例 `TideOreFishCompatibilityGameTests` 自带 `ModList.get().isLoaded("tide")` 判断，没装 Tide 时直接 `succeed()` 跳过，不会在纯净环境里变红。
- 版本纪律: 第三方接口依据锁定的 Minecraft 1.20.1 / Forge 47.3.0、Tide 1.6.5 与 Farmer's Delight 1.3.2 的本地映射 JAR、源码及字节码核验。**不得把其它 Tide 版本视为已验证兼容。**

---

## 九、资产管线 (DECIDED)

- 进 JAR 的只有 `assets/miningdim/textures/item/fishing/` 下十张 64x64 PNG（五鱼 + 五羹）。
- 原画留在 `tools/assets/fishing/v1/source/`，**不进 JAR**；最终 PNG 由 `tools/fishing/build_ore_fish_icons.py` 从原画确定性派生：按 Alpha 包围盒裁切 -> 等比缩放到长边 64 -> 居中贴到 64x64 全透明画布 -> 清掉 alpha 低于 `ALPHA_FLOOR = 8` 的缩放碎屑。改美术改 `source/` 再重跑脚本，不要直接往资源目录塞原画。
- 定 64x64 的理由（写在脚本 docstring 与 `FishingAssetGameTests` 类注释里）：原版 GUI 缩放最多 4 档、一个物品格恰好 64 真实像素，再高只是白占图集；更关键的是 `item/generated` 会沿 Alpha 轮廓烘侧面几何，同一条暗金鱼 1254x1254 原画要 3473 个 element、256x256 要 372 个、64x64 只要 134 个，不清缩放碎屑会涨到 220。
- 非正方形或非 2 的幂的原画直接当贴图会踩两条原版硬规则：帧尺寸整除规则不满足则该物品退化成缺失贴图；边长被 2 整除的次数不够会把整张物品图集的 mipmap 从 4 级拉到 1 级，受害的是全服远景贴图。
- 提示词留档在 `tools/assets/fishing/v1/README.md`，但**不完整**：铁鱼原画的生成提示词未留档（该 README 已注明），不要按"十张提示词全在此处"去找。
- Tide 兼容目录由 `tools/fishing/generate_tide_journal_catalog.ps1` 生成：接受 `-SourcePath` 与 `-JarPath`，固定校验两者 SHA256，并验证 Tide modId、1.6.5 版本、鱼种唯一性、物品模型与英文翻译键；只读输入、输出 JSON，不联网下载也不部署 JAR。

---

## 十、反洗钱口径与待决项

### 10.1 已落地口径 (DECIDED)

- **并入主闸**: `/fishing sell` 调 `grantDaily`，传 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`（`credit_faucet`）与 `GLOBAL_DAILY_CREDIT_FAUCET_TIER`（60000），与矿工卖矿、农夫卖菜共用同一个 (playerId, key) 每日累计计数器和同一条衰减曲线。渔夫**没有**也不得另开独立 faucet 键。
- **先扣鱼再入账**: `grantDaily` 的第一步就是把毛收入写进当日 faucet 计数器。旧写法"先调 `grantDaily`、实发为零就保留鱼"会给计数器记下一笔从未成交的销售、把衰减档位白推一格。现在先 `stack.shrink(count)` 再入账，计数器里每一笔都对应一次真实成交；实发为零照样扣鱼，与卖菜"收购曲线到底仍算卖出"同口径。
- **入账失败原样退回**: `grantDaily` 抛 `RuntimeException` 时把鱼按最大堆叠分批塞回背包（放不下就掉在脚边），然后**重抛**原异常——不是吞异常，是不让半步副作用留在玩家头上。
- **经济不可用时明确失败**: `EconomyServices.isRegistered()` 为假时不扣不发，直接回执失败。
- **鱼羹不参与收购**: `typeFor` 只匹配矿石鱼本体，拿鱼羹喊 `/fishing sell` 走"主手没有可出售的矿石鱼"分支。

### 10.2 PENDING: 卖鱼没有职业身份门

**状态**: PENDING（2026-09-19 已裁定按现状合入，门待补）。经济总表同步登记在 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 第六节第 6 条，两处结论一致。

**事实**: `/fishing sell` 的命令节点没有挂任何 `.requires(...)`，对全体玩家开放。农夫卖菜有 `SELL_MIN_MASTERY_LEVEL` 这道反洗钱身份门（拦白板小号套现 `/give` 或跨账号转来的货），渔夫因为第二章的 DEFERRED 没有等级可依，这道门无从落地。

**风险**: 衰减主闸按卖家账号计，而矿石鱼是普通可堆叠物品、可自由转移。把多个账号的鱼集中给一人出售会摊薄每日衰减；反过来，把一人的鱼分散到多个小号出售可以绕开深档系数，小号数量直接放大全服总注入。这与既有的跨账号洗额度结构性问题同源，不是渔夫独有。

**待裁定选项（二选一或组合，均未拍板）**:

- 方向 A（职业门）: 先落地 `JobId.FISHER` 与等级，再给 `/fishing sell` 补一道与农夫同构的等级门。代价是必须先做完第二章。
- 方向 B（全服供给定价）: 把定价从"按卖家账号的每日衰减"改成全服供给曲线，跨账号转移因此不再产生套利空间。代价是影响面覆盖全部 faucet，须与经济总表一并改。

**关联的第二条 PENDING（在经济总表第六节第 7 条登记，本模块只做转述）**: 钓鱼可用自动化钓鱼机无人值守产出，而 AFK 冻结闸门（`AbuseGuard.evaluateAfk` -> `PlayerAbuseState.afkFrozen`）目前只作用于高价矿当日计数，不覆盖卖鱼这条 faucet。是否把 AFK 冻结推广到全部 faucet 待定。

---

## 十一、测试覆盖 (DECIDED)

模块共 21 条 GameTest 用例，分五个持有类：

| 类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `FishingAssetGameTests` | 3 | 图集拼图规则/mipmap/边长上限/Alpha 通道；`ItemModelGenerator` element 红线 200；物品模型继承 `item/generated` 且 layer0 指回同名图标 |
| `OreFishGameTests` | 4 | 权重边界与余量保留、替换清空原渔获、真实 `FishingHook.retrieve` 的维度/流体/事件取消三重守卫、出售整组精确入账且拒收鱼羹 |
| `OreSoupGameTests` | 8 | 返还碗与 AMPLIFY 延时、铁羹只减疲劳不加挖速、失败品不顶掉在身汤、矿洞外换羹只保留倒计时且进矿洞后立即生效与到期双端清态、暗金同时改挖速与疲劳、翠玉在耐久附魔之后再减、非死亡 Clone 保留而死亡清除、减免不把工具在最后一点耐久上赔掉 |
| `FishingJournalGameTests` | 5 | 目录与合成入口、持有收录及幂等、存档往返与玩家隔离、网络往返、失败热重载保留旧目录 |
| `TideOreFishCompatibilityGameTests` | 1 | 真实 Tide `retrieve` 成功态只替换成功那一次（未装 Tide 时自跳过） |

验收判据只认 `runGameTestServer` 日志里明确出现的 `All N required tests passed`，不能只看 Gradle 退出码。最近一次完整记录（2026-09-06，1457 条全过）见 [矿石鱼与鱼羹](Ore_Fish_And_Soup.md) 的验证章。

**仍未实机验收**: 客户端物品显示、图鉴界面渲染与中文排版、完整钓鱼小游戏、Tide 实例下的重连表现——服务端 GameTest 从不拼图集也不开界面，这几项必须真机跑。

---

## 十二、已知缺口清单

| 编号 | 级别 | 缺口 | 章节 |
| --- | --- | --- | --- |
| 1 | Major | 职业身份/等级/经验全未落地，`JobId` 无 FISHER | 第二章 |
| 2 | Major | `/fishing sell` 无身份门（PENDING，已裁定按现状合入） | 10.2 |
| 3 | Major | 卖鱼 faucet 不受 AFK 冻结覆盖，可挂机产出（PENDING） | 10.2 |
| 4 | Minor | 鱼羹全部数值硬编码，未进 toml | 第七章 |
| 5 | Minor | `miningdim:ore_fish` 标签零消费方 | 3.3 |
| 6 | Minor | 简中鱼名与英文名不同构（简中无"矿"字），三处文档曾各写各的，现已统一按 `zh_cn.json` 为真源 | 3.2 |
| 7 | Minor | 铁鱼原画提示词未留档 | 第九章 |
| 8 | Minor | 客户端显示、图鉴界面与钓鱼小游戏未实机验收 | 第十一章 |
