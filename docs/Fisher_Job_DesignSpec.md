# 渔夫 职业 Mod — 设计规格文档

## 文档元信息

- 用途: 渔夫模块（规划中的第 9 职业）的唯一主设计入口。本文只登记代码里已经存在的事实与已裁定的待决项，不预写尚未实现的玩法。
- 目标平台: Minecraft 1.20.1 + Forge 47.3.0 + Java 17。
- 模块边界: 并入 WOK 本体，沿用 `modId=miningdim`、Java 包根 `com.miningdim` 与单 JAR 交付，不另建强制安装的附属 MOD。入口 `com.miningdim.job.fisher.FishingSystem`，源码归属 `job/fisher`（子包 `journal` / `ore` / `soup` / `quality` / `size`），模块注册表 id 为 `wok-job-fisher`（`docs/modules/module-registry.json`）。
- 专题子文档: [渔业图鉴框架](Fishing_Journal_Framework.md)（目录数据格式、界面、存档与网络协议）、[矿石鱼与鱼羹](Ore_Fish_And_Soup.md)（钓获/出售/料理的数值与历次验证记录）。两者是本文的专题展开；口径冲突时以本文与代码为准。鱼种品质（第四章）与体型/个人钓获记录（第五章）不另立专题，只写在本文。
- 模块依赖（注册表登记）: `wok-core`、`wok-store`、`wok-economy`、`wok-job-chef`；可选集成 `farmersdelight`、`tide`。
- 状态图例: DECIDED 已落地 / PENDING 待拍板 / DEFERRED 已确认未实现。

---

## 一、职业定位与经济角色 (DECIDED)

1. 定位: **矿洞水域的原料产出职业**。玩法闭环是"在矿洞维度钓鱼 -> 拿到矿石鱼 -> 卖信用点，或交给厨师做成鱼羹再回到矿洞挖矿"。延续全项目职业设计哲学（FF14 生产职业思路）：主打经济产出，战斗增益为零。
2. 战斗力红线: 五种鱼羹给的全部是**挖矿效率与续航**（夜视、挖速、工具耐久、饥饿消耗），没有一条改伤害、护甲、血量或移速，且全部以维度 `miningdim:mining` 为生效门槛（`OreSoupEffects.activeInMining`）。矿洞外只计时不生效。
3. 经济角色: 卖鱼是 faucet（对外注入信用点），鱼是 P2P 原料（供厨师或自制鱼羹）。faucet 并入全服统一衰减主闸，不另开计数键，详见第十二章。
4. 与厨师的关系: 鱼羹走既有厨师品质/料理效果流程（`ChefQualityNbt`），厨师只能延长鱼羹时长，不能提升鱼羹强度（第六章）。
5. 与矿工的关系: 钻石/暗金鱼羹的挖速加成进 `MinerSystem.onBreakSpeed` 统一结算，不另开一条挖速路径。
6. 产出量级（取自经济总表 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 渔夫行，与第三章权重/售价自洽）: 每次成功钓获期望 28.4 CP；不带附魔约 5,700 CP/小时，饵钓（Lure）III 约 25,600 CP/小时。缩短咬钩间隔的是饵钓，不是海之眷顾——海之眷顾只改战利品类别权重，而矿石鱼是在钓上来之后无条件替换渔获的，对渔夫收益严格零影响。
7. 收藏维度: 鱼种品质（每个鱼种固定一档，决定物品名颜色，第四章）与体型（每次成功钓获随机掷出，第五章）只用于展示、收藏与个人记录，不改售价、不改鱼羹效果。体型是否参与定价待拍板（5.8）。

---

## 二、等级曲线与 XP 来源 (DEFERRED — 尚未落地)

**事实**: 渔夫目前没有职业身份、等级、经验与技能。

- `JobId` 枚举当前 8 个成员（MINER / FARMER / ENGINEER / TAROT / CHEF / AGENT / MUNITIONS / BREWER），**没有 FISHER**；`JobCommands` 全文没有渔夫分支，`/job` 系列命令查不到渔夫。
- `job/fisher` 整包没有任何经验入账调用：钓获替换（`OreFishCatchHandler`）、体型结算（`FishCatchService`）、出售（`OreFishSellService`）、图鉴收录（`FishingJournalService`）四条路径只改物品、玩家 NBT、信用点与收藏集合，一处都不碰 `JobProgress`。图鉴收录刻意不冒充亲手钓获，也不发经验。
- 模块**已经**记录"亲手钓获"统计，但与经验无关：`FishingRecords` 按玩家、按鱼种存钓获次数与最大个体（第五章 5.6），只来自成功钓获路径。`FishingJournalSavedData` 仍只存"该 UUID 收录过哪些物品 ID"的集合，没有计数、没有来源区分。

**一旦落地会继承什么（事实陈述，非承诺）**:

- 经验轨道 id 由枚举派生：`JobExperienceTracks.track(job)` 返回 `miningdim:job/<id>`，`legacySource(job)` 返回 `miningdim:legacy/job/<id>`，追加枚举成员即自动得到轨道，无需另立注册。
- 等级曲线是全职业共享的单一拷贝 `JobXpCurve`：L1 到 L10 累计 61,900 有效经验（逐级 3,300 / 3,800 / 4,500 / 5,300 / 6,300 / 7,400 / 8,800 / 10,300 / 12,200），满级后超额经验仍累计但不再升级；每日有效经验软上限衰减按当日已累计有效经验分段 [0,2000) x1.0 / [2000,2800) x0.4 / [2800,3400) x0.2 / [3400,3800) x0.08 / [3800,+inf) x0.02，UTC 翻日重置。渔夫无需也不得另立一张曲线表。
- 追加成员必须**尾部追加**：`JobSyncS2C` 按 `values()` 顺序读写，插在中间会破坏同序契约（理由见 `JobId` 类注释，酿酒师 BREWER 即按此规则追加为第 8 个）。

**直接后果**: 卖鱼的反洗钱身份门没有等级可依，见第十二章。

---

## 三、矿石鱼: 鱼种品质、权重与卖价 (DECIDED)

### 3.1 钓获条件与接入点

仅在维度 `miningdim:mining` 的**水**方块上成功钓获时参与抽取；失败、岩浆与其它维度一律不产矿石鱼。命中后用**一条**矿石鱼替换该次原渔获，不额外叠加（原版路径 `OreFishCatchHandler.replaceDrops` 是 `drops.clear()` 后放入一条；Tide 路径把 `hookedItems` 整个换成只含这一条的新可变列表，理由见第十章）；后续 Forge 事件取消仍能阻止掉落。不提供把矿石鱼拆成矿物或烧炼的配方。

两条接入路径共用同一张权重表（`OreFishCatchHandler.typeForRoll`，roll 取 `[0, 10000)`）：

| 路径 | Mixin | 所在配置 | 注入点 |
| --- | --- | --- | --- |
| 原版钓竿 | `mixin/VanillaOreFishMixin` | `miningdim.mixins.json`（`required=true`） | `@Redirect` 掉 `FishingHook.retrieve` 里的 `LootTable.getRandomItems`，在真实战利品生成之后替换 |
| Tide 1.6.5 | `mixin/compat/TideOreFishMixin` | `miningdim.compat.mixins.json`（`required=false`） | `@Inject` 到 `TideFishingHook.retrieve` 第一次读 `hookedItems` 之前，即小游戏判定成功、掉落生成之前 |

两者都在服务端判维度与 `FluidTags.WATER` 之后才替换。替换发生在体型结算之前，所以换上来的矿石鱼同样会被测量（第五章 5.5）。

### 3.2 鱼种表

| 鱼种（简中物品名） | 物品 ID（miningdim） | 配置键 | 鱼种品质 | 万分权重 | 概率 | 单条基础信用点 |
| --- | --- | --- | --- | --- | --- | --- |
| 铁鱼 | `iron_ore_fish` | `iron` | 普通 | 2000 | 20% | 20 |
| 金鱼 | `gold_ore_fish` | `gold` | 优良 | 800 | 8% | 80 |
| 钻石鱼 | `diamond_ore_fish` | `diamond` | 稀有 | 200 | 2% | 400 |
| 绿宝石鱼 | `emerald_ore_fish` | `emerald` | 史诗 | 100 | 1% | 600 |
| 暗金鱼 | `dark_gold_ore_fish` | `dark_gold` | 传说 | 20 | 0.2% | 2000 |

- 权重与售价的默认值来自 `OreFishingConfig`，鱼种品质来自 `OreFishType.quality()`，并与品质标签里的归档一致（`FishQualityGameTests.documentedTiersDriveRarity` 核对二者不分叉）。剩余 6,880/10000（68.8%）保留原渔获。
- 简中显示名（`lang/zh_cn.json`）五条都没有"矿"字；英文显示名反而带 Ore（`Iron Ore Fish` 等）。简中的品类统称仍是"矿石鱼"，对应分类键 `ore_fish`（`fishing.miningdim.category.ore_fish`）。本文表格首列用游戏内真名，正文里的"矿石鱼"一律指品类。
- 名字颜色: 五种鱼各占一档（普通到传说），品质、权重与售价三个序列现在同向——绿宝石鱼比钻石鱼更稀有、更贵，品质也高一档。鱼的颜色由品质标签经核心接缝统一给出（第四章 4.5），`OreFishingItems` 注册时给鱼写的 `.rarity(type.quality().rarity())` 只是标签绑定前的默认值；鱼羹注册时写同一个值，但鱼羹不进品质标签，名字颜色就只来自这里，即跟随原料鱼的品质。
- 暗金鱼是矿石鱼里的最高档（传说；`OreFishItem` 对 `DARK_GOLD` 额外挂 `tooltip.miningdim.ore_fish.highest`"矿石鱼最高档。"）。全鱼种的最高档是神话，目前只有三种 Tide 鱼（第四章 4.4）。鱼种品质与厨师给成品菜盖的加工品质是两套，互不换算。

### 3.3 物品标签

`data/miningdim/tags/items/ore_fish.json` 把五种矿石鱼聚合为 `miningdim:ore_fish`，已登记进模块注册表的 `resourcePaths`。**当前全库无消费方**：Java 侧没有对应的 `TagKey` 常量，十条鱼羹配方引用矿石鱼时用的也都是具体物品 ID。保留它是给下游配方/任务预留聚合点；若确认长期无人消费，应连同注册表登记一并删除，不留无主资源。鱼种品质用的是另外六个标签，见第四章 4.2。

---

## 四、鱼种品质 (DECIDED)

### 4.1 六档定义

每个鱼种一个**固定**档位，档位决定物品名颜色（`Rarity`）。它与厨师给成品菜盖的加工品质是两套、互不换算，与每次钓获随机的体型（第五章）也无关。枚举 `job/fisher/quality/FishQuality`：

| 档位 | 枚举 | 名字颜色 | Rarity | 标签 |
| --- | --- | --- | --- | --- |
| 普通 | `COMMON` | 白 | 原版 `Rarity.COMMON` | `#miningdim:fish_quality/common` |
| 优良 | `FINE` | 黄 | 原版 `Rarity.UNCOMMON` | `#miningdim:fish_quality/fine` |
| 稀有 | `RARE` | 青（aqua） | 原版 `Rarity.RARE` | `#miningdim:fish_quality/rare` |
| 史诗 | `EPIC` | 淡紫 | 原版 `Rarity.EPIC` | `#miningdim:fish_quality/epic` |
| 传说 | `LEGENDARY` | 金 | 运行期扩展 `Rarity.create("MININGDIM_LEGENDARY", GOLD)` | `#miningdim:fish_quality/legendary` |
| 神话 | `MYTHIC` | 红 | 运行期扩展 `Rarity.create("MININGDIM_MYTHIC", RED)` | `#miningdim:fish_quality/mythic` |

- 两档扩展稀有度的名字带 `MININGDIM_` 前缀：Forge 的 `Rarity.create` 按名字忽略大小写去重，裸用 `LEGENDARY` 可能静默拿到别的 mod 的同名实例和颜色。
- 创建时机: `FishingSystem.register` 的第一步调 `FishQuality.bootstrap()` 触发枚举类初始化（含两次 `Rarity.create`），mod 构造期两个物理端都会走到，早于任何物品注册与稀有度查询；两端不同步创建的话，服务端拼出的聊天栏物品名颜色会与客户端分叉。
- 档名翻译键 `fishing.miningdim.quality.<id>`（普通 / 优良 / 稀有 / 史诗 / 传说 / 神话）。

### 4.2 归档数据: 六个物品标签

- 路径 `data/miningdim/tags/items/fish_quality/<id>.json`，已登记进模块注册表 `resourcePaths`。`FishQuality.of` 按神话到普通的顺序查标签，同一物品落在多个档位标签里时**取最高档**；出厂的六个标签互不相交（GameTest 断言）。
- 原版与 miningdim 条目写成必需条目；Tide 条目一律写成 `{"id": ..., "required": false}`，没装 Tide 时标签照常加载，只是少了这些条目。
- 原版会把物品标签同步给客户端，所以品质不需要自建网络同步。服主可用数据包改档：往更高档标签里追加即可升档（最高档胜出）；降档须用 `"replace": true` 覆写原档标签。
- 鱼羹不在任何品质标签里（颜色规则见 3.2）。不在标签里的物品 `FishQuality.of` 返回 null，渲染接缝不认领，照走原版逻辑。

### 4.3 分档模型

档位 = **几率分 + 条件分**，封顶 5（神话）。模型文件 `tools/fishing/fish_quality_model.json`。

几率分按 `p_bite` 计：`p_bite` 是在该鱼**最佳栖息地**每次咬钩钓到它的概率（幸运 0、无磁力饵、开阔水域或岩浆）。阈值 `probability_bands = [0.12, 0.05, 0.015, 0.007, 0.0015]`：

| p_bite | 几率分 |
| --- | ---: |
| >= 12% | 0 |
| [5%, 12%) | 1 |
| [1.5%, 5%) | 2 |
| [0.7%, 1.5%) | 3 |
| [0.15%, 0.7%) | 4 |
| < 0.15% | 5 |

条件分为该鱼所列条件的分值之和：

| 条件键 | 分值 | 含义 |
| --- | ---: | --- |
| `depths` | 1 | 主世界 Y<0 深层水域 |
| `lava` | 1 | 岩浆垂钓（需防火钓竿） |
| `nether` | 1 | 下界维度 |
| `the_end` | 2 | 末地维度 |
| `biome` | 1 | 指定生物群系 |
| `rare_biome` | 2 | 稀有或危险生物群系（樱花树林、蘑菇岛、恶地、深暗之域） |
| `structure` | 2 | 鱼钩位于指定结构内（海底神殿） |
| `moon_phase_2of8` | 1 | 月相 0 或 4（八天中两天） |
| `moon_phase_1of8` | 2 | 满月（八天中一天） |
| `night` | 1 | 夜晚 |
| `luck_7` | 3 | 钓竿幸运不低于 7 |

`p_bite` 的来源（逐条写在模型文件的 `note` 与 `description` 里）：

- 原版四种取原版 `gameplay/fishing` 战利品表（鱼类池 85% 乘各自权重）；热带鱼取装 Tide 时 saltwater_warm 表的 21.3%（纯原版仅 1.7%，WOK 服务器装有 Tide，取前者）。
- 矿石鱼取 `OreFishingConfig` 默认权重折成的每次成功钓获概率（矿洞维度水域）。改了 toml 权重不会自动改档，档位以标签为准。
- Tide 66 种由 Tide 1.6.5 的战利品表与 `TideFishingHook` 字节码推导，两路独立计算，结果一致。

### 4.4 全鱼种档位表

`FishQualityGameTests` 的期望档位逐字转抄本表（不从标签或 `OreFishType` 反取）；改档必须同时改模型、重跑生成器（4.6）并改本表。

| 档位 | 物品 ID | p_bite | 几率分 | 达成条件 | 条件分 | 合计 |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| 普通 | `minecraft:cod` | 51% | 0 | — | 0 | 0 |
| 普通 | `minecraft:salmon` | 21.25% | 0 | — | 0 | 0 |
| 普通 | `minecraft:tropical_fish` | 21.31% | 0 | — | 0 | 0 |
| 普通 | `miningdim:iron_ore_fish` | 20% | 0 | — | 0 | 0 |
| 普通 | `tide:anglerfish` | 12.084% | 0 | — | 0 | 0 |
| 普通 | `tide:bass` | 15.093% | 0 | — | 0 | 0 |
| 普通 | `tide:bluegill` | 14.161% | 0 | — | 0 | 0 |
| 普通 | `tide:cave_crawler` | 15.537% | 0 | — | 0 | 0 |
| 普通 | `tide:cave_eel` | 15.537% | 0 | — | 0 | 0 |
| 普通 | `tide:glowfish` | 12.084% | 0 | — | 0 | 0 |
| 普通 | `tide:guppy` | 18.25% | 0 | — | 0 | 0 |
| 普通 | `tide:mackerel` | 16.603% | 0 | — | 0 | 0 |
| 普通 | `tide:mint_carp` | 13.688% | 0 | — | 0 | 0 |
| 普通 | `tide:ocean_perch` | 21.607% | 0 | — | 0 | 0 |
| 普通 | `tide:trout` | 17.701% | 0 | — | 0 | 0 |
| 普通 | `tide:tuna` | 13.701% | 0 | — | 0 | 0 |
| 普通 | `tide:yellow_perch` | 15.046% | 0 | — | 0 | 0 |
| 优良 | `minecraft:pufferfish` | 11.05% | 1 | — | 0 | 1 |
| 优良 | `miningdim:gold_ore_fish` | 8% | 1 | — | 0 | 1 |
| 优良 | `tide:abyss_angler` | 12.084% | 0 | `depths` | 1 | 1 |
| 优良 | `tide:angelfish` | 9.134% | 1 | — | 0 | 1 |
| 优良 | `tide:crystal_shrimp` | 5.179% | 1 | — | 0 | 1 |
| 优良 | `tide:deep_grouper` | 15.537% | 0 | `depths` | 1 | 1 |
| 优良 | `tide:ember_koi` | 20.398% | 0 | `lava` | 1 | 1 |
| 优良 | `tide:inferno_guppy` | 15.298% | 0 | `lava` | 1 | 1 |
| 优良 | `tide:iron_tetra` | 8.632% | 1 | — | 0 | 1 |
| 优良 | `tide:luminescent_jellyfish` | 12.084% | 0 | `depths` | 1 | 1 |
| 优良 | `tide:obsidian_pike` | 17.848% | 0 | `lava` | 1 | 1 |
| 优良 | `tide:pike` | 8.851% | 1 | — | 0 | 1 |
| 优良 | `tide:shadow_snapper` | 15.537% | 0 | `depths` | 1 | 1 |
| 稀有 | `miningdim:diamond_ore_fish` | 2% | 2 | — | 0 | 2 |
| 稀有 | `tide:ashen_perch` | 29.35% | 0 | `nether` + `lava` | 2 | 2 |
| 稀有 | `tide:barracuda` | 4.567% | 2 | — | 0 | 2 |
| 稀有 | `tide:bedrock_tetra` | 5.179% | 1 | `depths` | 1 | 2 |
| 稀有 | `tide:catfish` | 4.425% | 2 | — | 0 | 2 |
| 稀有 | `tide:chorus_cod` | 13.17% | 0 | `the_end` | 2 | 2 |
| 稀有 | `tide:clayfish` | 3.186% | 2 | — | 0 | 2 |
| 稀有 | `tide:crystalline_carp` | 6.905% | 1 | `depths` | 1 | 2 |
| 稀有 | `tide:enderfin` | 17.874% | 0 | `the_end` | 2 | 2 |
| 稀有 | `tide:endergazer` | 15.052% | 0 | `the_end` | 2 | 2 |
| 稀有 | `tide:endstone_perch` | 18.815% | 0 | `the_end` | 2 | 2 |
| 稀有 | `tide:gilded_minnow` | 1.726% | 2 | — | 0 | 2 |
| 稀有 | `tide:lapis_lanternfish` | 8.632% | 1 | `depths` | 1 | 2 |
| 稀有 | `tide:magma_mackerel` | 33.543% | 0 | `nether` + `lava` | 2 | 2 |
| 稀有 | `tide:purpur_pike` | 15.052% | 0 | `the_end` | 2 | 2 |
| 稀有 | `tide:sailfish` | 4.567% | 2 | — | 0 | 2 |
| 稀有 | `tide:volcano_tuna` | 10.199% | 1 | `lava` | 1 | 2 |
| 史诗 | `miningdim:emerald_ore_fish` | 1% | 3 | — | 0 | 3 |
| 史诗 | `tide:aquathorn` | 7.519% | 1 | `structure` | 2 | 3 |
| 史诗 | `tide:birch_trout` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:crimson_fangjaw` | 8.386% | 1 | `nether` + `lava` | 2 | 3 |
| 史诗 | `tide:dripstone_darter` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:fluttergill` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:frostbite_flounder` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:leafback` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:oakfish` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:pine_perch` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:prarie_pike` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:sandskipper` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:slimefin_snapper` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:soulscaler` | 8.386% | 1 | `nether` + `lava` | 2 | 3 |
| 史诗 | `tide:stonefish` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:sunspike_goby` | 4.186% | 2 | `biome` | 1 | 3 |
| 史诗 | `tide:warped_guppy` | 8.386% | 1 | `nether` + `lava` | 2 | 3 |
| 传说 | `miningdim:dark_gold_ore_fish` | 0.2% | 4 | — | 0 | 4 |
| 传说 | `tide:blazing_swordfish` | 1.677% | 2 | `nether` + `lava` | 2 | 4 |
| 传说 | `tide:blossom_bass` | 4.186% | 2 | `rare_biome` | 2 | 4 |
| 传说 | `tide:echofin_snapper` | 4.186% | 2 | `rare_biome` | 2 | 4 |
| 传说 | `tide:elytrout` | 3.763% | 2 | `the_end` | 2 | 4 |
| 传说 | `tide:mirage_catfish` | 4.186% | 2 | `rare_biome` | 2 | 4 |
| 传说 | `tide:sporestalker` | 4.186% | 2 | `rare_biome` | 2 | 4 |
| 传说 | `tide:witherfin` | 4.193% | 2 | `nether` + `lava` | 2 | 4 |
| 神话 | `tide:midas_fish` | 3.76% | 2 | `luck_7` | 3 | 5 |
| 神话 | `tide:shooting_starfish` | 4.184% | 2 | `moon_phase_1of8` + `night` | 3 | 5 |
| 神话 | `tide:voidseeker` | 4.186% | 2 | `the_end` + `moon_phase_2of8` | 3 | 5 |

- 档位人数: 装 Tide 1.6.5 时普通 17 / 优良 13 / 稀有 17 / 史诗 17 / 传说 8 / 神话 3，共 75 条；不装 Tide 时只剩原版 4 + 矿石鱼 5，即 4 / 2 / 1 / 1 / 1 / 0，共 9 条。没有鱼的原始合计超过 5，封顶当前未生效。
- 表中取值的附注（摘自模型 `note`）: `aquathorn` 须鱼钩位于海底神殿结构件内；四个 `rare_biome` 分别是 `blossom_bass` 樱花树林、`echofin_snapper` 深暗之域、`mirage_catfish` 恶地、`sporestalker` 蘑菇岛；`midas_fish` 取钓竿幸运 >= 7 时的每咬钩概率（幸运 0 时为 0）；`shooting_starfish` 取满月、夜晚、深海群系同时满足时的概率；`voidseeker` 取月相 0/4 时末地水域的概率。

### 4.5 渲染接缝

- 核心自有接缝: `core/ItemRarityOverrides`（解析器列表，mod 构造期注册，先注册者优先，返回 null 表示不认领）加 `mixin/ItemRarityOverrideMixin`（`Item.getRarity(ItemStack)` 的 HEAD 注入、可取消，登记在 `miningdim.mixins.json`）。渔夫在 `FishingSystem.register` 里注册 `FishQuality::rarityOverride`，已归档的鱼返回档位稀有度，其余物品不认领。
- 选这个注入点的理由（写在 mixin 类注释里）: 1.20.1 没有任何子类覆写 `Item.getRarity(ItemStack)`，`ItemStack.getRarity()` 只是转调，tooltip 首行、快捷栏物品名与聊天栏 [物品] 名全部经过这里。
- 在 HEAD 直接返回还顺带绕开两件事: 原版附魔升档（附魔过的鳕鱼仍是普通档，不会被升成 RARE）；以及原版升档用的 `Item$1` switch 表——那张表按类初始化时的 `Rarity.values().length` 定长，运行期扩展出来的稀有度进去会越界。未被认领的物品原样走原版逻辑，附魔木棍照样升 RARE。
- 解析器会在客户端渲染线程与服务端线程（聊天栏物品名、死亡消息）上被调用，必须只读、无副作用、足够便宜。
- 模块边界: mixin 只 import `core.ItemRarityOverrides`，不引用任何渔夫类，所以没有新增核心到渔夫的运行期引用，D036 的 `evidence` 不变；"核心自有接口 + 业务模块注册绑定"正是 D036 退出方案要求的形态（`docs/modules/DEPENDENCY_DEBT.md`）。
- 厨师盖过章的鱼: `ChefQualityNbt` 用 `setHoverName` 写入带厨师品质颜色的自定义名，显式颜色优先于稀有度颜色，名字保持厨师品质色；品质档仍在 tooltip 的"鱼种品质"行里显示。
- tooltip: `FishingTooltipHandler`（公共代码注册到 Forge 总线，事件两端都有）在物品名之后紧接着插入"鱼种品质：X"（X 按档位着色）；带体型标签时再插"体型：X"，奖杯再加"奖杯个体：长度 · 重量"与"钓获者：名字"。原有 tooltip 行顺延，非鱼物品不改动。

### 4.6 生成器

- `tools/fishing/build_fish_quality_tags.ps1` 读 `fish_quality_model.json`，按 4.3 的公式写出六个标签；`-Check` 只比对不写，任一文件与模型推导不一致即退出码 1。输出 UTF-8 无 BOM、LF 换行，同一模型重跑逐字节稳定。
- 生成器自带校验: `tiers` 必须比 `probability_bands` 多一项、阈值严格递减、条件分非负、物品 ID 格式合法且不重复、`p_bite` 落在 (0, 1]、未知条件键直接报错。
- 模型与生成器都在 `tools/`，不进 JAR；运行期只读标签。

---

## 五、体型与个人钓获记录 (DECIDED)

源码在 `job/fisher/size`: `FishSizeProfile`、`FishSizeCatalog`、`FishSizeRoller`、`FishSizeClass`、`FishSizeNbt`、`FishMeasurement`、`FishRecord`、`FishingRecords`、`FishCatchService`、`FishSizeFormat`。

### 5.1 体型档案

- 数据包路径 `data/<namespace>/fishing/sizes/*.json`，由 `FishSizeCatalog`（`SimpleJsonResourceReloadListener`）加载，与图鉴目录一起在 `FishingSystem.onReload` 注册、在 `onServerStopping` 清空。只在服务端使用：物品上只留档位，客户端显示不需要档案。
- 条目字段 `{item, min_cm, common_cm, max_cm, a, b}`：`min_cm` 是能钓到的最小个体，`common_cm` 是分布中位数，`max_cm` 是奖杯上限（取真实最大体长的八九成）。体重按渔业生物学的长重关系 W(g) = a * L(cm)^b 推算。
- 校验（`FishSizeProfile` 构造器）: 0 < min < common < max <= 5000 cm；0 < a <= 1；2.5 <= b <= 3.5；max 处体重不超过 2,000,000 g（2 吨，只拦明显写错的数量级，档案里最重的 `tide:blazing_swordfish` 约 0.57 吨）。
- 与图鉴目录同一套纪律: 可选 MOD 门控走共用的 `FishingDataGate`（第十章）；物品不存在、同一物品重复或数值非法时整次拒绝发布，保留此前完整档案；最多 512 条。没有档案的物品（垃圾、宝藏等）永远不做体型结算。
- 内置三份: `vanilla.json` 4 条、`ore_fish.json` 5 条、`tide_1_6_5.json` 66 条（带 `required_mod: "tide"` 与 `required_version: "1.6.5"`）。缺少 Tide 时 9 条，装 Tide 1.6.5 时 75 条，与图鉴目录一一对应（`FishSizeGameTests.everyJournalFishHasASizeProfile`）。档案是手写数据，没有生成脚本。

原版与矿石鱼的档案（中位体重、上限体重按条件因子 1 计算）：

| 物品 ID | min_cm | common_cm | max_cm | a | b | 中位体重 | 上限体重 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `minecraft:cod` | 30 | 60 | 130 | 0.0077 | 3.07 | 约 2,215 g | 约 23,785 g |
| `minecraft:salmon` | 40 | 75 | 120 | 0.0107 | 3.0 | 约 4,514 g | 约 18,490 g |
| `minecraft:pufferfish` | 10 | 25 | 45 | 0.03 | 2.95 | 约 399 g | 约 2,260 g |
| `minecraft:tropical_fish` | 5 | 12 | 30 | 0.02 | 3.0 | 约 35 g | 约 540 g |
| `miningdim:iron_ore_fish` | 25 | 40 | 70 | 0.00924 | 3.07 | 约 766 g | 约 4,267 g |
| `miningdim:gold_ore_fish` | 25 | 40 | 70 | 0.01232 | 3.07 | 约 1,021 g | 约 5,689 g |
| `miningdim:diamond_ore_fish` | 20 | 35 | 60 | 0.00847 | 3.07 | 约 466 g | 约 2,437 g |
| `miningdim:emerald_ore_fish` | 20 | 35 | 60 | 0.00847 | 3.07 | 约 466 g | 约 2,437 g |
| `miningdim:dark_gold_ore_fish` | 35 | 60 | 100 | 0.0154 | 3.07 | 约 4,430 g | 约 21,258 g |

鳕鱼这一行被 `FishSizeGameTests` 逐字转抄为期望值。Tide 66 条见 `tide_1_6_5.json`，不在本文重复。

### 5.2 掷骰（`FishSizeRoller`）

- 每次测量取两个独立标准正态数：`z` 定体长与体型档，`z2` 定肥瘦（条件因子）。
- `z` 截断到 [-3, 3]（`Z_LIMIT = 3.0`）。体长围绕中位数做对数正态展开，两侧各用自己的对数跨度：
  - z >= 0: L = common * (max / common)^(z / 3)
  - z < 0: L = common * (common / min)^(z / 3)
- 于是 z = 0 落在 `common_cm`，z = +3 恰好是 `max_cm`，z = -3 恰好是 `min_cm`，结果永远不出档案区间。截断只影响两端约 0.27% 的样本；三条体型档阈值都在 +-3 以内，出档概率不受影响。
- 体重 = a * L^b 乘条件因子 exp(0.08 * z2)（`WEIGHT_SIGMA = 0.08`），条件因子钳在 [0.8, 1.2]（`WEIGHT_SPREAD = 0.20`），避免同一体长出现离谱的重量。
- 存储取整: 体长按毫米取整并钳在 [round(min*10), round(max*10)]，体重按毫克取整且至少 1 mg；体重用未取整的体长计算。

### 5.3 体型档（`FishSizeClass`）

档位只看本次钓获的 `z`，与鱼种无关，所以**每个鱼种的出档概率完全相同**：

| 档 | 判据 | 概率 | 名字颜色 | 物品 NBT |
| --- | --- | ---: | --- | --- |
| 小型 `SMALL` | z < -0.8416（Phi^-1(0.20)） | 20% | 灰 | `{v, cls}` |
| 标准 `STANDARD` | 其余 | 65% | 白 | 不写 |
| 大型 `LARGE` | z >= 1.0364（Phi^-1(0.85)） | 14% | 绿 | `{v, cls}` |
| 奖杯 `TROPHY` | z >= 2.3263（Phi^-1(0.99)） | 1% | 金 | `{v, cls, mm, mg, by, byName, t}` |

档名翻译键 `fishing.miningdim.size.<id>`（小型 / 标准 / 大型 / 奖杯）。

### 5.4 物品 NBT（`FishSizeNbt`）

- 复合标签 `MiningFish` 挂在物品 NBT 根下，与厨师的 `MiningChef` 并存（两边都用 `getOrCreateTag().put`，不整体替换）。字段: `v`（格式版本，byte，当前 1）、`cls`（档 id）；奖杯另写 `mm`（体长毫米，int）、`mg`（体重毫克，long）、`by`（钓获者 UUID）、`byName`（钓获者名）、`t`（钓获时的世界 gameTime）。
- 堆叠语义是这套混合存储的目的: 标准档一个字节都不写，与没测量过的鱼（生物掉落、箱子战利品）照常堆叠；小/大只写档位，同档不同尺寸、不同钓获者的鱼可以堆叠；只有奖杯带精确尺寸与钓获者，刻意独一份。精确尺寸另记在个人钓获记录里（5.6）。
- 尺寸**绝不写进自定义物品名**：自定义名会让配方书自动填充跳过这组鱼，还会被厨师盖章整个覆盖。
- 读取宽容: 档位读不出、或奖杯的长度/重量非正时按"没有标签"处理，不抛异常（tooltip 在客户端渲染线程上调用，不能被坏数据打断）。

### 5.5 结算入口: 只测成功钓获

`FishCatchService.onSuccessfulCatch` 逐栈处理：空栈与已有体型标签的栈跳过（不重复结算），没有档案的物品跳过；其余掷骰、打标签、记入个人记录、发回报。只有两条"确定会落地"的钓获路径会调用它：

- 原版钓竿: `FishingSystem.onItemFished` 订阅 `ItemFishedEvent`，`EventPriority.LOWEST` 且不接收已取消事件，别的 MOD 取消掉的那一竿不留记录。事件里的列表是副本，但元素与实际生成掉落物的 ItemStack 是同一批引用，原地打标签即作用于落地的掉落物。`VanillaOreFishMixin` 的替换发生在事件之前，所以矿石鱼也会被测量。不限维度，原版钓竿在任何地方钓上有档案的鱼都会测量。
- Tide 钓竿: `TideOreFishMixin` 同一个注入点，先做矿洞水域的矿石鱼替换，再对最终渔获调 `FishCatchService`；两步共用一个注入器，顺序固定。Tide 从不发 `ItemFishedEvent`，这里是 Tide 渔获唯一的测量点。测量本身不限维度与流体。

生物掉落、箱子战利品、交易、`/give` 与鱼桶得到的鱼一律不测量，按标准档处理，因此刷怪或鱼桶农场产不出奖杯。

### 5.6 个人钓获记录（`FishingRecords`）

- 按玩家、按物品 ID 存 `{n: 次数, mm: 最大个体体长, mg: 该个体体重}`，路径 `getPersistentData()` -> `PlayerPersisted`（`Player.PERSISTED_NBT_TAG`）-> `MiningFishingRecords`。
- Forge 在玩家实体重建（死亡重生、从末地回主世界）时只复制 `PlayerPersisted` 子标签，所以记录跨死亡保留（对照 6.4：鱼羹状态挂在根节点，要靠 `PlayerEvent.Clone` 显式搬运）。记录随玩家存档文件走，不进全服共享的图鉴 SavedData，玩家再多也不会让单个全局文件膨胀。
- 每次结算按这一栈的条数加次数，次数饱和在 `Integer.MAX_VALUE`。只有**严格更长**才刷新最大个体，首次钓获也算刷新；条目数以体型档案数为上限（最多 512）。
- 读取宽容: 键解析不出物品 ID、字段非正的单条记录跳过，不抛异常，一条坏数据不会让整份记录不可读。
- 图鉴快照带上本人记录（只保留当前目录里的鱼种），在详情页显示（第七章）。

### 5.7 钓获回报

- 每条被测量的渔获都在动作栏显示 `message.miningdim.fishing.catch`："钓获 X：长度 · 重量（档）"，鱼名按其稀有度着色、档名按档位着色；刷新个人最大个体时追加金色"个人新纪录！"。
- 奖杯另发一条聊天栏系统消息 `message.miningdim.fishing.catch.trophy`："奖杯级个体！X：长度 · 重量"（金色）。
- 显示格式（`FishSizeFormat`，数字按 `Locale.ROOT`，单位不走翻译）: 长度保留一位小数的 cm；体重 10 g 以下保留一位小数、1 kg 以下取整克、以上按千克两位小数。

### 5.8 PENDING: 体型不影响售价

本步体型只做展示与记录：`/fishing sell` 的收购价仍是鱼种基础价乘条数，与档位、尺寸无关（第八章）。体型或奖杯是否溢价、溢价是否走同一 faucet 主闸，随下一步经济改动一并拍板，届时须先过 [经济收支总表](Economy_BalanceSheet_DesignSpec.md)。

---

## 六、鱼羹: 效果、时长与携带规则 (DECIDED)

### 6.1 物品与配方

- 鱼羹物品 ID = 鱼 ID 加 `_soup`（`OreFishType.soupId()`），共五种，简中名为铁鳞鱼羹 / 金鳞鱼羹 / 钻鳞鱼羹 / 翠玉鱼羹 / 暗金鱼羹。
- 食物属性（`OreFishingItems`）：饱食 8、饱和系数 0.6（即饱和 9.6）、`alwaysEat`（吃饱时也能喝）、最多堆叠 16、`craftRemainder` 为碗；生存模式喝完由 `OreFishSoupItem.finishUsingItem` 显式返还一只碗（背包满则掉在脚边），创造模式不返还。
- 基础配方（无序合成）：对应矿石鱼、红/棕蘑菇任一、胡萝卜/马铃薯/甜菜根任一、碗，各一份，产出一碗。
- 装 Farmer's Delight 时额外提供烹饪锅配方（`farmersdelight:cooking`，`forge:mod_loaded` 条件门控），蘑菇与蔬菜改用 `forge:mushrooms`、`forge:vegetables` 标签，容器为碗，经验 1.0、烹饪 200 tick，仍只出一碗。

### 6.2 矿洞内收益

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

### 6.3 时长、AMPLIFY 上限与替换规则

- 基础时长 `BASE_DURATION_TICKS = 300 * 20`，即 300 秒。
- 只有菜上实际盖章的厨师 `AMPLIFY` 能延长时长，取该菜上 AMPLIFY 的**最大** magnitude 作百分比乘数（无 AMPLIFY 时按 100），封顶 `MAX_DURATION_TICKS = 1500 * 20`，即 1500 秒（25 分钟）。AMPLIFY 只加时长，**不提升上述任何强度**。
- 按厨师品质默认档（`ChefConfig` 的 low/medium/high/extraordinary/radiant = 120/150/200/300/500）换算：360 / 450 / 600 / 900 / 1500 秒，闪耀档恰好顶到上限。
- `SPOILED`（失败品）不给予任何汤增益：`applyConsumedSoup` 直接早退，**且不清除已在身的汤**——喝失败品不会把好汤顶掉。
- 任意维度都能食用并开始计时；矿洞外不享受收益但时间继续流逝。重复同种刷新，改喝另一种替换，时长不累加。

### 6.4 跨死亡与跨重生的携带规则

- 汤态存在玩家 `getPersistentData()` 根节点的 `MiningOreFishSoup` 复合标签里，字段 `type`（`OreFishType.id()`）、`expiresAt`（世界 gameTime 绝对时刻）、`nightVisionUntil`。
- **死亡清除**：`PlayerEvent.Clone` 在 `isWasDeath()` 为真时什么都不搬，汤态随旧实体丢弃。
- **非死亡的实体重建保留**：正常退出再进入、以及从末地主出口回主世界这类走 `PlayerList.respawn` 的路径，都保留未过期状态与**原到期时刻**。这一步必须显式搬运，因为 Forge 的 `restoreFrom` 只复制 `PlayerPersisted` 子标签，挂在根节点的汤态会被丢掉。
- 搬运时刻意**不搬** `nightVisionUntil`：重建出来的玩家身上一个效果都没有，搬过去只会留下一个指向旧时刻的归属记录，万一玩家自己喝的夜视剩余时长撞上这个值，就会把别人的药水当成汤夜视删掉。下一 tick 的 `refreshNightVision` 会重新授予并重新记归属。
- 过期判定用世界 gameTime，因此离线时间照样计入消耗。

---

## 七、渔业图鉴 (DECIDED)

完整规格见 [渔业图鉴框架](Fishing_Journal_Framework.md)，此处只列本文需要的口径：

- 图鉴物品 `miningdim:fishing_journal`，一本书加一个墨囊无序合成，只堆叠 1 本；右键打开，或执行 `/fishing journal`。
- 目录由服务端数据包唯一决定（`data/<namespace>/fishing/journal/*.json`，`FishingJournalCatalog`）。内置三份：`vanilla.json` 4 条、`ore_fish.json` 5 条、`tide_1_6_5.json` 66 条。缺少 Tide 时加载 9 条；装 Tide 1.6.5 时 75 条；Tide 版本不等于 1.6.5 则跳过该兼容文件并记 WARN（门控由 `FishingDataGate` 统一判定，见第十章）。
- 分类共 11 个：WOK 自建的 `ore_fish`，加上沿用 Tide 的 freshwater / saltwater / underground / depths / biome / structure / lava / nether / end / legendary。
- 收录规则: 持有即收录，交易取得也算，卖出/食用/存箱后保留记录；鱼桶、鱼实体与背包外的箱子不算。收藏按 UUID 存在主世界 SavedData `miningdim_fishing_journal`，跨死亡、重生、维度切换与重启保留。
- 收录**不发经验、不冒充亲手钓获**，它只是查阅与收藏进度。亲手钓获另有来源：详情页显示第五章的个人钓获记录（亲手钓获 N 次、最大个体"长度 · 重量"，没有时显示"尚无钓获记录"）。两者互不影响——交易来的鱼会收录，但不产生钓获记录。
- 品质显示: 列表里已收录条目的名字按鱼种品质着色（未收录仍用原来的暗淡色 `MUTED`），详情页标题按品质着色，并新增"鱼种品质"与"钓获记录"两节（`FishingJournalScreen`）。
- 硬上限: 目录最多 512 条，分类键 1–32 位小写字母数字下划线，文本翻译键非空且不超过 128 字符。物品不存在、鱼种重复或字段非法时拒绝发布这次目录，保留此前完整目录。
- 网络: 通道 `miningdim:fishing_journal`，协议版本 2，唯一消息是服务端到客户端的目录/收藏/钓获记录快照，客户端不上传收藏状态。协议 2 在收藏集合之后追加本人钓获记录；记录必须指向本次目录里的条目，否则整包拒绝。两端版本号必须相等，旧客户端连新服务端会在握手时被拒。

---

## 八、玩家命令 (DECIDED)

模块注册的命令只有 `/fishing` 一支（`FishingSystem.onCommands`），三个子命令：

| 命令 | 作用 | 权限门 |
| --- | --- | --- |
| `/fishing sell` | 主手必须是矿石鱼；卖主手整组，连带背包主栏里同鱼种的其它非奖杯栈。按第三章售价乘总条数算毛收入，实发过每日衰减主闸 | 无 `.requires`，全员可用，无 OP 门也无职业门 |
| `/fishing sell all` | 不看主手，卖背包主栏里全部非奖杯矿石鱼，其余同上 | 无 `.requires`，全员可用 |
| `/fishing journal` | 先扫一遍背包补收录，再下发快照并打开图鉴界面 | 无 `.requires`，全员可用 |

出售口径（`OreFishSellService.sell(player, Scope)`，`sellMainHand` / `sellAll` 是两个 Scope 的入口）：

- 为什么不再只卖主手那一栈: 体型档把同一鱼种拆成小/标准/大几组互不堆叠的栈（5.4），只卖主手会让玩家一栈一栈地喊命令。
- 扫描范围是背包主栏 36 格（含快捷栏，`Inventory.items`），副手与盔甲栏不参与。
- 奖杯个体是独一份的收藏品，批量扫描一律跳过；只有把奖杯拿在主手上执行 `/fishing sell` 才会卖掉，那是明确意图。`/fishing sell all` 永远不卖奖杯。
- 收购价仍是鱼种基础价乘条数，与体型档、尺寸无关（5.8）。一条命令卖出的全部栈合并成一笔 `grantDaily`，不按栈拆笔。
- 扣鱼之前先对选中的每一栈补一次图鉴收录。

四种结果（判定优先级见 `OreFishSellService.sell`，回执由 `execute` 发出）：

1. 无鱼可卖（`SellResult.nothingToSell`）-> `sendFailure`，不扣不发。`/fishing sell` 是主手不是矿石鱼（鱼羹也不算，`typeFor` 只匹配 `OreFishingItems.FISH`），回执 `message.miningdim.fishing.sell.no_fish`；`/fishing sell all` 是背包主栏里没有非奖杯矿石鱼，回执 `message.miningdim.fishing.sell.no_fish_inventory`（提示奖杯需拿在主手单独出售）；
2. 经济系统未注册 -> `sendFailure`，不扣不发，明确失败；
3. 实发为零 -> `sendSuccess` 单列一条"卖出 N 条但当日收益已衰减到零"，**鱼照扣**；
4. 正常成交 -> `sendSuccess` 报条数与信用点。

两条 `sendSuccess` 回执都传 `broadcastToAdmins = false`，与 `/farmer sell` 同口径：卖鱼是普通玩家的日常动作，不该刷 OP 聊天与服务端日志。

---

## 九、配置项 (DECIDED)

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
- 鱼种品质与体型档案不在 toml 里，是数据包数据：品质走六个物品标签（4.2），体型走 `data/<namespace>/fishing/sizes/*.json`（5.1），服主改档或调档案用数据包覆写，`/reload` 生效。
- **覆盖缺口（Minor）**: 鱼羹侧的全部数值目前都是 `OreSoupEffects` 里的硬编码常量，没有一项进 toml——基础/最长时长（300 秒 / 1500 秒）、夜视授予时长与续期周期（400 tick / 80 tick）、饥饿与耐久减免的 per mille 值（250 / 200 / 250）、挖速百分点（15 / 20）。与本仓"全数值进 config、硬编码即缺陷"的既定纪律不一致，运营期若要调平衡必须改代码重编译。是否迁入 toml 待拍板。
- **覆盖缺口（Minor）**: 体型侧同理，下列全是代码常量、不进 toml 也不进数据包——体型档分位阈值 `FishSizeClass.SMALL_BELOW_Z` / `LARGE_FROM_Z` / `TROPHY_FROM_Z`（即 20% / 65% / 14% / 1% 的出档概率），掷骰常量 `FishSizeRoller.Z_LIMIT` / `WEIGHT_SIGMA` / `WEIGHT_SPREAD`（3.0 / 0.08 / 0.20），档案校验上限（`FishSizeProfile.MAX_LENGTH_CM` 5000、`MAX_WEIGHT_G` 2,000,000）。要调奖杯出率必须改代码重编译。是否迁入 toml 待拍板。

---

## 十、与前置 mod Tide 的关系 (DECIDED)

Tide 是**可选**集成，不是强依赖：不装 Tide 模块照常工作，装了也不改它的 JAR、不复制它的代码/贴图/描述正文，只引用其已有的翻译键与物品模型。

版本口径与降级路径分两条，互相独立：

1. **兼容数据**（数据层）: 图鉴目录与体型档案各有一份 `tide_1_6_5.json`，都带 `required_mod: "tide"` 与 `required_version: "1.6.5"`，由两者共用的 `FishingDataGate.isAvailable` 判定——没装 Tide 则整份文件跳过；装了但版本字符串不等于 `1.6.5`，记一条 WARN（`Skipping fishing compatibility data for tide 1.6.5: installed X`）后整份跳过，目录与档案都回落到 9 条。`required_version` 必须伴随 `required_mod`，只写版本会直接抛错。品质标签里的 Tide 条目是 `required: false`，不受这道门影响，没装 Tide 时自然缺席。
2. **钓获接入**（字节码层）: `TideOreFishMixin` 按 Tide 1.6.5 的 `retrieve` 方法签名与 `hookedItems` / `fluid` 两个私有字段写死，所以**不放主 mixin 配置**，单独进 `miningdim.compat.mixins.json` 并置 `required: false`。Mixin 0.8.5 的 `MixinProcessor.handleMixinError` 按配置的 `isRequired()` 决定抛 `MixinApplyError` 还是只记 WARN：玩家装上别的 Tide 版本时，非必需配置只停用这一个 mixin，不会让整个服务端启动崩掉。`@Pseudo` 只免疫"目标类不存在"，签名对不上照样要靠 `required=false` 兜底。
   该注入点按固定顺序做两件事：先在矿洞水域做矿石鱼替换，再对最终渔获做体型结算（第五章 5.5）。Tide 不发 `ItemFishedEvent`，所以该 mixin 停用后，Tide 自己的钓鱼流程照常工作，只是既不替换出矿石鱼、也不测量体型；原版 `FishingHook` 路径的替换（`VanillaOreFishMixin`，在主 mixin 配置里，`required=true`）与测量（`ItemFishedEvent`）不受影响。

其它口径：

- 不可变列表修复: Tide 的空表兜底交回的是不可变的 `List.of(...)`，旧实现原地 `hookedItems.clear()` 会抛 `UnsupportedOperationException`，把整竿渔获吞掉。现在替换时把 `hookedItems` 整个赋成新的 `ArrayList`，回归用例 `immutableFallbackCatchIsReplacedAndMeasured`。
- 兼容用例 `TideOreFishCompatibilityGameTests` 的两条用例都自带 `ModList.get().isLoaded("tide")` 判断，没装 Tide 时直接 `succeed()` 跳过，不会在纯净环境里变红。
- 版本纪律: 第三方接口依据锁定的 Minecraft 1.20.1 / Forge 47.3.0、Tide 1.6.5 与 Farmer's Delight 1.3.2 的本地映射 JAR、源码及字节码核验。**不得把其它 Tide 版本视为已验证兼容。**

---

## 十一、资产管线 (DECIDED)

- 进 JAR 的只有 `assets/miningdim/textures/item/fishing/` 下十张 64x64 PNG（五鱼 + 五羹）。
- 原画留在 `tools/assets/fishing/v1/source/`，**不进 JAR**；最终 PNG 由 `tools/fishing/build_ore_fish_icons.py` 从原画确定性派生：按 Alpha 包围盒裁切 -> 等比缩放到长边 64 -> 居中贴到 64x64 全透明画布 -> 清掉 alpha 低于 `ALPHA_FLOOR = 8` 的缩放碎屑。改美术改 `source/` 再重跑脚本，不要直接往资源目录塞原画。
- 定 64x64 的理由（写在脚本 docstring 与 `FishingAssetGameTests` 类注释里）：原版 GUI 缩放最多 4 档、一个物品格恰好 64 真实像素，再高只是白占图集；更关键的是 `item/generated` 会沿 Alpha 轮廓烘侧面几何，同一条暗金鱼 1254x1254 原画要 3473 个 element、256x256 要 372 个、64x64 只要 134 个，不清缩放碎屑会涨到 220。
- 非正方形或非 2 的幂的原画直接当贴图会踩两条原版硬规则：帧尺寸整除规则不满足则该物品退化成缺失贴图；边长被 2 整除的次数不够会把整张物品图集的 mipmap 从 4 级拉到 1 级，受害的是全服远景贴图。
- 提示词留档在 `tools/assets/fishing/v1/README.md`，但**不完整**：铁鱼原画的生成提示词未留档（该 README 已注明），不要按"十张提示词全在此处"去找。
- Tide 兼容目录由 `tools/fishing/generate_tide_journal_catalog.ps1` 生成：接受 `-SourcePath` 与 `-JarPath`，固定校验两者 SHA256，并验证 Tide modId、1.6.5 版本、鱼种唯一性、物品模型与英文翻译键；只读输入、输出 JSON，不联网下载也不部署 JAR。
- 鱼种品质标签由 `tools/fishing/build_fish_quality_tags.ps1` 从 `tools/fishing/fish_quality_model.json` 生成，见 4.6。体型档案是手写数据，没有生成脚本。

---

## 十二、反洗钱口径与待决项

### 12.1 已落地口径 (DECIDED)

- **并入主闸**: `/fishing sell` 与 `/fishing sell all` 每条命令调一次 `grantDaily`，传 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`（`credit_faucet`）与 `GLOBAL_DAILY_CREDIT_FAUCET_TIER`（60000），与矿工卖矿、农夫卖菜共用同一个 (playerId, key) 每日累计计数器和同一条衰减曲线。渔夫**没有**也不得另开独立 faucet 键。
- **先扣鱼再入账**: `grantDaily` 的第一步就是把毛收入写进当日 faucet 计数器。旧写法"先调 `grantDaily`、实发为零就保留鱼"会给计数器记下一笔从未成交的销售、把衰减档位白推一格。现在先把选中的各栈全部 `shrink` 到零再入账，计数器里每一笔都对应一次真实成交；实发为零照样扣鱼，与卖菜"收购曲线到底仍算卖出"同口径。
- **入账失败原样退回**: `grantDaily` 抛 `RuntimeException` 时，把扣下的各栈按扣之前的原样副本（连同体型标签）逐栈塞回背包（放不下就掉在脚边），然后**重抛**原异常——不是吞异常，是不让半步副作用留在玩家头上。
- **经济不可用时明确失败**: `EconomyServices.isRegistered()` 为假时不扣不发，直接回执失败。
- **鱼羹不参与收购**: `typeFor` 只匹配矿石鱼本体，拿鱼羹喊 `/fishing sell` 走"主手没有可出售的矿石鱼"分支，`/fishing sell all` 的扫描也跳过鱼羹。

### 12.2 PENDING: 卖鱼没有职业身份门

**状态**: PENDING（2026-09-19 已裁定按现状合入，门待补）。经济总表同步登记在 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 第六节第 6 条，两处结论一致。

**事实**: `/fishing sell` 与 `/fishing sell all` 的命令节点都没有挂任何 `.requires(...)`，对全体玩家开放。农夫卖菜有 `SELL_MIN_MASTERY_LEVEL` 这道反洗钱身份门（拦白板小号套现 `/give` 或跨账号转来的货），渔夫因为第二章的 DEFERRED 没有等级可依，这道门无从落地。

**风险**: 衰减主闸按卖家账号计，而矿石鱼是普通可堆叠物品、可自由转移。把多个账号的鱼集中给一人出售会摊薄每日衰减；反过来，把一人的鱼分散到多个小号出售可以绕开深档系数，小号数量直接放大全服总注入。这与既有的跨账号洗额度结构性问题同源，不是渔夫独有。

**待裁定选项（二选一或组合，均未拍板）**:

- 方向 A（职业门）: 先落地 `JobId.FISHER` 与等级，再给 `/fishing sell` 补一道与农夫同构的等级门。代价是必须先做完第二章。
- 方向 B（全服供给定价）: 把定价从"按卖家账号的每日衰减"改成全服供给曲线，跨账号转移因此不再产生套利空间。代价是影响面覆盖全部 faucet，须与经济总表一并改。

**关联的第二条 PENDING（在经济总表第六节第 7 条登记，本模块只做转述）**: 钓鱼可用自动化钓鱼机无人值守产出，而 AFK 冻结闸门（`AbuseGuard.evaluateAfk` -> `PlayerAbuseState.afkFrozen`）目前只作用于高价矿当日计数，不覆盖卖鱼这条 faucet。是否把 AFK 冻结推广到全部 faucet 待定。

---

## 十三、测试覆盖 (DECIDED)

模块共 37 条 GameTest 用例，分七个持有类：

| 类 | 用例数 | 覆盖 |
| --- | --- | --- |
| `FishingAssetGameTests` | 3 | 图集拼图规则/mipmap/边长上限/Alpha 通道；`ItemModelGenerator` element 红线 200；物品模型继承 `item/generated` 且 layer0 指回同名图标 |
| `OreFishGameTests` | 6 | 权重边界与余量保留、替换清空原渔获、真实 `FishingHook.retrieve` 的维度/流体/事件取消三重守卫、出售主手整组精确入账且拒收鱼羹（含深档实发归零照常扣鱼）、`/fishing sell` 连带同鱼种小/标准/大三档而不碰奖杯/其它鱼种/副手且 `sell all` 卖光非奖杯、只剩奖杯时批量出售无鱼可卖、奖杯拿在主手才卖；入账失败重抛并把带体型标签的栈原样退回 |
| `OreSoupGameTests` | 8 | 返还碗与 AMPLIFY 延时、铁羹只减疲劳不加挖速、失败品不顶掉在身汤、矿洞外换羹只保留倒计时且进矿洞后立即生效与到期双端清态、暗金同时改挖速与疲劳、翠玉在耐久附魔之后再减、非死亡 Clone 保留而死亡清除、减免不把工具在最后一点耐久上赔掉 |
| `FishingJournalGameTests` | 5 | 目录与合成入口、持有收录及幂等、存档往返与玩家隔离、网络往返（含钓获记录）且指向目录外物品的记录整包拒绝、失败热重载保留旧目录 |
| `TideOreFishCompatibilityGameTests` | 2 | 真实 Tide `retrieve` 成功态只替换成功那一次并在同一注入点完成体型结算；不可变兜底列表换成新列表后替换并结算（未装 Tide 时均自跳过） |
| `FishQualityGameTests` | 6 | 本文 4.4 的档位驱动物品稀有度（原版 4 + 矿石鱼 5 全量，装 Tide 时加每档代表条目；`OreFishType` 品质与标签不分叉；鱼羹不进标签但颜色跟随原料鱼；非鱼物品不被认领）、出厂标签互不相交且各档人数与本文一致、扩展稀有度名带前缀且颜色正确并作用于服务端聊天物品名、附魔不改鱼的品质稀有度而未归档物品保留原版升档、tooltip 在名字后依次插入品质/体型/尺寸/钓获者且原有行顺延、品质/体型/回报/界面文本中英文齐全 |
| `FishSizeGameTests` | 7 | 掷骰覆盖档案区间（z = +-3 恰为 min/max、z = 0 为中位、体重等于 a*L^b、肥瘦差钳在 +-20%、档位边界）、固定种子 20 万样本的出档比例与 20/65/14/1 偏差小于 0.5 个百分点、图鉴每条鱼都有档案且条数 9/75 且鳕鱼档案与本文一致、非法档案整次拒绝保留旧档案、只有非标准档写标签且同档可堆叠而奖杯独一份、坏标签按无标签处理、结算计次与个人最大且挂在 `PlayerPersisted` 下且不重复结算、真实原版 `retrieve` 落地才测量而被取消的钓获不留记录 |

验收判据只认 `runGameTestServer` 日志里明确出现的 `All N required tests passed`，不能只看 Gradle 退出码。最近一次完整记录（2026-09-24，分支 `feat/fish-quality-size`）：无 Tide 时 `All 1490 required tests passed`，目录与体型档案各加载 9 条；装 Tide 1.6.5 + Cloth Config 11.1.136（dev 映射 JAR，全新测试世界）时同样 `All 1490 required tests passed`，目录与体型档案各加载 75 条，两条 Tide 兼容用例真实调用 `TideFishingHook.retrieve`。本分支之前的历史记录（2026-09-06，1457 条全过）见 [矿石鱼与鱼羹](Ore_Fish_And_Soup.md) 的验证章。

**仍未实机验收**: 客户端物品显示、图鉴界面渲染与中文排版、完整钓鱼小游戏、Tide 实例下的重连表现；以及本轮新增的客户端表现——品质名字颜色（含传说/神话两档扩展稀有度）、tooltip 新增行、图鉴品质着色与"鱼种品质""钓获记录"两节、动作栏钓获回报。服务端 GameTest 从不拼图集也不开界面，tooltip 用例只断言插入的行，这几项必须真机跑。

---

## 十四、已知缺口清单

| 编号 | 级别 | 缺口 | 章节 |
| --- | --- | --- | --- |
| 1 | Major | 职业身份/等级/经验全未落地，`JobId` 无 FISHER | 第二章 |
| 2 | Major | `/fishing sell`（含 `sell all`）无身份门（PENDING，已裁定按现状合入） | 12.2 |
| 3 | Major | 卖鱼 faucet 不受 AFK 冻结覆盖，可挂机产出（PENDING） | 12.2 |
| 4 | Minor | 鱼羹全部数值硬编码，未进 toml | 第九章 |
| 5 | Minor | `miningdim:ore_fish` 标签零消费方 | 3.3 |
| 6 | Minor | 简中鱼名与英文名不同构（简中无"矿"字），三处文档曾各写各的，现已统一按 `zh_cn.json` 为真源 | 3.2 |
| 7 | Minor | 铁鱼原画提示词未留档 | 第十一章 |
| 8 | Minor | 客户端显示、图鉴界面与钓鱼小游戏未实机验收 | 第十三章 |
| 9 | Minor | 体型与奖杯不影响卖价，是否溢价待与经济改动一并拍板（PENDING） | 5.8 |
| 10 | Minor | 体型档出率、掷骰常量与档案校验上限是代码常量，未进 toml | 第九章 |
| 11 | Minor | 品质名字颜色、tooltip 新增行、图鉴品质着色与钓获记录区、动作栏钓获回报未在真实客户端验收 | 第十三章 |
