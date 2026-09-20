# 矿物定价台账（Ore Pricing Ledger）

> 状态标记：DECIDED = 已拍板（落在代码）；DRAFT = 本文提议待你确认；PENDING = 待标定。
> 平台：Forge 1.20.1 / modid `miningdim`。前置真源：
> [服务器经济系统设计文档.md](服务器经济系统设计文档.md)（×10 锚价 / 软上限 / 跨职业收入）、
> [Miner_Job_DesignSpec.md](Miner_Job_DesignSpec.md)（挖速/连锁/隧道/时运/难度门控）。
> 代码真源：`economy/ShopPriceTable.java`（锚价）、`economy/EconomyConstants.java`（软上限/衰减）、
> `economy/AbuseGuard.buyPrice`（衰减曲线）、`job/miner/MinerConstants.java`（产能曲线）。

---

## 一、当前已定价（DECIDED，代码现状）

只有 3 个"系统龙头矿"有价 + 每日软上限。衰减口径：`price(n) = base × max(0.01, 0.97^max(0, n-cap))`（地板比例的单一真源是 `EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO`，第十一章决策①已由 0.25 砍到 0.01）。

| 矿 | base（信用点/个） | 每日软上限 cap | 满 cap 日上限 = base×cap | 来源 |
|---|--:|--:|--:|---|
| 下界残骸 | 4,500 | 8 | 36,000 | ShopPriceTable + EconomyConstants |
| 钻石 | 500 | 64 | 32,000 | 同上 |
| 金 | 120 | 256 | 30,720 | 同上 |

衰减：超过 cap 后每多 1 个 ×0.97，跌到 base 的 **1% 地板**。例：钻石第 65 个 = 485，超 cap 约 152 块（`ln(0.01)/ln(0.97)`）后触地板，深 over 夹到 **5**。

**已编码的核心结论**：金（中级，30,720）已经"极限勉强跟上"钻石（高级，32,000）——你的设计直觉在代码里成立。

---

## 二、缺口（本次要补）

`HighValueOre` 枚举只有 DIAMOND/GOLD/NETHERITE_SCRAP。**铜、铁、煤、红石、青金、绿宝石全部无价、无 cap**
（`ShopPriceTable` 注释把它们划为"低价矿，属各自子系统物价职责，不落货币层"，但那个子系统还不存在）。

这正是"铜+时运能否比得过高级"的唯一口子：**没有 cap 的矿，收入不被任何东西封顶**。

---

## 三、两类矿（定价哲学）

| 类 | 矿 | 主销路 | 定价机制 | 收入封顶来源 |
|---|---|---|---|---|
| **系统龙头矿** | 钻石/金/残骸/绿宝石 | 卖系统（settleOreSale） | base × 软上限衰减 | 每日软上限（**当前是软膝盖非硬顶，见下注**） |
| **材料矿** | 铜/铁（**煤本轮排除**） | P2P 卖给职业 | 市场浮动 + 系统低保底 | 下游需求 + 低 cap 的保底 |

- **铜** 的真实价值是 P2P 卖给军火商（造发射药，经济文档锚 ~28 占位，`服务器经济系统设计文档.md:372`）；系统只给一个**低保底价**，防止"没军火商在线时铜一文不值"，但保底 < P2P，逼玩家走供应链。
- **铁** 同理（卖工程师造护甲板）。
- 铜/铁的系统保底**必须带 cap**，否则连锁+隧道+时运的高吞吐能把"卖系统铜"刷穿。
- **煤本轮排除**：军火商配方不吃煤（`Munitions_Job_DesignSpec.md` 第五章配方表），下游买家悬空，不进系统收购（见 5.2 / 8.6）。
- **「硬封顶」现状**：逐矿衰减的地板已按第十一章决策①降到 1%（`AbuseGuard.buyPrice` 取 `Math.max(EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO, decayed)`），过 cap 后仍停在地板线性继续涨，故 base×cap 本身依旧不是硬顶。但收入封顶职责已按决策②整体上移：逐矿曲线降级为「优先挖哪种矿」的相对吸引力，总量由 `grantDaily` 的全服统一衰减主闸（0.6 / 60000 档 / 1% 地板）锁住，见第十一章。

---

## 四、核心铁律：收入上限由衰减主闸锁定（反通胀北极星）

`Miner_Job_DesignSpec` 第六章 + 方案 B。注意本章第 2/3 条已按 2026-07-12「连锁全放开」裁决与第十一章决策②重写：
封顶锚点从「逐矿软上限 + 高价矿物理排除连锁」整体移到了「全服统一 `credit_faucet` 衰减主闸」。

1. **时运/连锁/隧道只让你"更快/更省块撞上限"，绝不抬上限**。时运额外掉落**计入**当日产出物计数（方案 B），所以撞限更快≠收入更多。注：时运的施加范围仍只在连锁/隧道路径（`MinerFortune` 类注释自述作用域是 `MinerSystem.onChainProduce` 与 `MinerActions` 的 tunnelProduce），单块原版挖矿的掉落由 vanilla 直接物化不经本路径；但该路径现在**不再按矿种筛选**，详见第八章 8.5。
2. **吞吐不再受矿种白名单/黑名单限制**。旧口径的 `CHAIN_WHITELIST` / `CHAIN_HARD_EXCLUDE` 双表已随用户 2026-07-12「矿洞维度内连锁全放开」裁决从 `MinerConstants` 删除（该文件已无这两个常量，全库只剩 `ChainMiningEngine` 的一行注释记着它们被替代）。现行唯一判定是 `ChainMiningEngine.chainable`：镐类可采 + 手持镐档位足够采出产物 + 方块可破坏（硬度 ≥ 0）+ 该位无 BlockEntity，**四条全满足即可连锁，不看矿种**。钻石/金/残骸矿全部满足，可被连锁/隧道连带，连带产出还经 `MinerSystem.replayEconomyOreCount` → `EconomyService.recordMinedOreDrops` → `settleOreSale` 逐颗真实发钱。
3. 所以**收入封顶已完全依赖衰减主闸，而不是矿种的物理排除**。逐矿的 `base × 每日软上限` 不是硬顶（过 cap 后 1% 地板留线性尾巴），它现在只是「优先挖哪种矿」的相对权重；真正锁总量的是第十一章决策②的全服统一 `credit_faucet` 衰减主闸（0.6 / 60000 档 / 1% 地板，几何主项前 10 档 ≈ 14.9 万为正常落点）。要让"铜+时运 < 高级"，靠的不再是 `铜_base × 铜_cap < 钻石天花板` 这条不等式，而是两种矿的收入都被同一条主闸曲线压在同一天花板之下。

---

## 五、提议定价表（DRAFT，经调研接地 + 红队修正，待你最终拍板）

> 本表数值已逐项核对代码/JSON/经济文档真源（见第八章「调研接地」与第九章「红队压测结论」）。
> 标注「无结算通道」= 该矿不在 `HighValueOre` 枚举（`EconomyConstants` 的 `HighValueOre` 仅 DIAMOND/GOLD/NETHERITE_SCRAP），
> `settleOreSale` 物理上结算不了，进系统收购须先补枚举 + 软上限常量 + `buyPrice/classify` 分支三处同步（红队 Major）。
> 原「BLOCKED-BY-WORLDGEN」标注已作废：残骸与绿宝石的 `PlacedFeature` 都已落仓并挂进 Hard biome，见第八章 8.1。

### 5.1 系统龙头矿（卖系统，`settleOreSale` 走衰减）

| 矿 | tier | base | 每日 cap | base×cap | 区域可得（真源） | 状态 |
|---|---|--:|--:|--:|---|---|
| 下界残骸 | 高 | 4,500 | 8 | 36,000 | **Hard 已接线**（`mining_hard.json` feature 列含 `miningdim:ore_ancient_debris`） | DECIDED（base/cap 已编码，产出已接线且已进结算通道） |
| 钻石 | 高 | 500 | 64 | 32,000 | Hard（`mining_hard.json` ore_diamond×3） | DECIDED |
| 金 | 中 | 120 | 256 | 30,720 | Medium 主（`mining_medium.json`）/ Hard（`mining_hard.json`） | DECIDED |
| 绿宝石 | 中 | 180 | 100 | 18,000 | Hard 已接线（`mining_hard.json` 含 `miningdim:ore_emerald`），但**无结算通道** | DRAFT |
| 红石 | — | — | — | — | Medium/Hard 有 feature，但**倾向不进系统收购**（见下） | DRAFT-不收购 |
| 青金 | — | — | — | — | Medium/Hard 有 feature，但**倾向不进系统收购**（见下） | DRAFT-不收购 |

数值依据：
- 残骸 4,500×8 / 钻石 500×64 / 金 120×256 直接照抄代码 DECIDED 真值（`ShopPriceTable` 的 `ORE_BASE_*` + `EconomyConstants` 的 `DAILY_SOFTCAP_*`），不动。金（30,720）≈钻石（32,000）的「极限勉强跟上」在代码里已成立（第一章），本轮不改。注意这个「≈」只在把 base×cap 当独立封顶看时成立，总收入口径见第十一章决策②。
- **绿宝石 180×100=18,000**（较原 DRAFT 200×96=19,200 微调）：定位「中级偏下、稀有补充」——低于金 30,720/钻 32,000（不抢龙头风头），高于材料矿。原 DRAFT 的 cap 96 是照搬 `OreType` 绿宝石 Hard maxCount，但那是 A 套死代码（worldgen 零调用，见第八章 8.1），不能当产出锚；此处 cap 100 是货币层每日软上限的独立量级。**不**用经济文档 8.1 的绿宝石收购锚 250 直接 ×cap：250×100=25,000 逼近金，破坏「稀有补充非主力」定位。
- 红石/青金：经济文档 8.1 虽给红石 20、青金 30 收购价，但二者主用途是工业/附魔/村民交易。进系统 faucet 等于多开两个无下游需求的印钞口（与「删材料矿煤」同理，见 5.2）。倾向只做村民交易/工业，不落货币层（PENDING，见第十章 F）。

### 5.2 材料矿（P2P 主销给职业 + 系统低保底；进系统收购须三处接线）

| 矿 | 系统保底 base | 每日 cap | 保底 base×cap | P2P 锚（卖职业） | 区域可得（真源） | 状态 |
|---|--:|--:|--:|---|---|---|
| 铁 | 30 | 350 | 10,500 | 工程师护甲板（经济文档 8.1 铁锭收购 **60**，`:335`） | Easy/Medium/Hard 全有 iron feature | DRAFT-须接线 |
| 铜 | 15 | 400 | 6,000 | 军火商发射药（经济文档 P2P 锚 **28** 占位，`:372`） | **仅 Easy/Medium**（`mining_easy.json:42`/`mining_medium.json:40`）；**Hard 无 copper feature** | DRAFT-须接线 |
| 煤 | — | — | — | 军火商配方**不吃煤**（`Munitions_Job_DesignSpec.md` 第五章配方表只吃铜+火药），下游买家悬空 | 仅 Easy/Medium 有 coal feature | DRAFT-不收购（见红队 Major） |

数值依据：
- **铁 30×350=10,500**：P2P 锚直接用经济文档 8.1 铁锭收购价 60（`:335`，是 ×10 锚真值非占位）。工程师是 sink 职业（满修 ~3.6 万 CP 是花钱，`服务器经济系统设计文档.md:361`），且只低级板吃铁，稳态需求远低于升级期，故铁 P2P 风险低于铜。
- **铜 15×400=6,000**：base 15 刻意 < P2P 锚 28（`:372`），逼玩家走军火商供应链而非卖系统（第三章定位）。cap 400 ≈ 6 名矿工供 1 个 L10 军火商日需 2,400 铜的人均分摊（`Munitions_Job_DesignSpec.md:99` L10 ~24,000 发 ÷ 70 发/批 × 7 铜/批 ≈ 2,400）。单矿 ceiling 6,000 ≪ 金 30,720 ≈ 钻 32,000，满足铁律。**修正**：原 DRAFT 把铜算进 Hard 区天花板，但真源 `mining_hard.json` 无 copper feature——Hard 挖不到铜，第六章 Hard 区合计已据此修正。
- **煤：本轮建议不进系统收购**（与红石/青金同列）。原 DRAFT 给煤 6×700=4,200 兜底，但军火商配方只吃铜+火药不吃煤（工费是扣信用点销毁，`Munitions_Job_DesignSpec.md:51`），三份文档无煤的量化下游买家锚。进系统等于又一个无下游需求的印钞口，省一处接线。煤留燃料/工业/村民交易（PENDING，见第十章 F）。
- **接线警告（红队 Critical/Major）**：铜/铁进系统收购**必须三处原子同步**——(1) `HighValueOre` 枚举加 COPPER/IRON；(2) 配 `DAILY_SOFTCAP_COPPER/IRON` 常量并在 `AbuseGuard.dailySoftCap` switch 加分支；(3) `buyPrice/classify` 走同一 0.97/0.01。漏任一处：`dailySoftCap` 对铜铁抛 `IllegalArgumentException` 或退化为无衰减刷穿。**禁止只落价不接 cap**。

---

## 六、各难度区"满肝"日收入上限测算（DRAFT，按真源 worldgen 可得矿 + 干净 base×cap）

> 红队 Major 指出原 DRAFT 的区域合计与其列出的 base×cap 不自洽（红石/青金删后未扣净的幽灵残留）。
> 本表用**干净 base×cap 求和**，且**只计真源 worldgen 实际有 feature 且已进结算通道的矿**——
> 残骸的 worldgen 与结算通道现已双双接线，故重新计入 Hard；绿宝石虽已有 feature 但仍无结算通道（不在 `HighValueOre` 枚举），不计；煤/红石/青金本轮不进系统收购，不计。

按 L10 满级矿工、各软上限全撞、各区**真源可得且已接线**矿求和（**未经主闸衰减的毛值上界**，不是到手收入；实际到手另受第十一章主闸与时间/吞吐限约束）：

| 区 | 真源可得且计价的矿 | 理论 base×cap 合计（毛值上界） | 占 Hard 比 | 现实大头 realisticDaily（毛值） |
|---|---|--:|--:|--:|
| Easy | 铁10,500 + 铜6,000 | **16,500** | ~15% | ~6,000–10,000 |
| Medium | 铁10,500 + 铜6,000 + 金30,720 | **47,220** | ~43% | ~25,000–35,000 |
| Hard | 铁10,500 + 金30,720 + 钻32,000 + 残骸36,000 | **109,220** | 100% | ~55,000–70,000 |

各区算式与真源依据：
- **Easy 16,500** = 铁10,500（`mining_easy.json` iron×3）+ 铜6,000（同文件 copper）。Easy 无金/钻/残骸 feature。占 Hard ~15%，**低于已采纳方向「Easy 占 Hard 20–25%」下沿**——残骸计入 Hard 后这个缺口比原先更大，若要回到区间须微调（见第十章 C，如铁 cap 350→420 或铜 base 15→20）。
- **Medium 47,220** = Easy 两矿 + 金30,720（`mining_medium.json`）。Medium 有 copper/iron/gold feature，无钻/残骸。金是 Medium 跳变主力。
- **Hard 109,220** = 铁10,500 + 金30,720（`mining_hard.json`）+ 钻32,000（同文件 ore_diamond / _large / _buried）+ 残骸36,000（同文件 `miningdim:ore_ancient_debris`）。**Hard 真源无 copper、无 coal feature**，故铜不计入 Hard。残骸从「纸面」翻成活项：worldgen 已接线，且 `AbuseGuard` 把 `ancient_debris` 方块分类到 `HighValueOre.NETHERITE_SCRAP`、`ShopPriceTable` 给出 base 4,500、`EconomyConstants` 给出 cap 8，整条结算通道通。
- 注意残骸的 cap 只有 8 个/日：它把**理论天花板**从 73,220 抬到 109,220（+49%），但对「现实大头」的影响小得多（满撞也只有 36,000 毛值，且 Hard 区残骸 feature 的 `count` 是 1，实际挖到 8 个/日并不轻松），故 realisticDaily 一列暂不上调，待真服跑量观测。

> 单矿对比：铜(6,000) ≪ 金(30,720) ≈ 钻(32,000) < 残骸(36,000)。
> **口径说明**：以上「理论合计」是把各高价矿当**独立封顶且互不挤占**算出的**毛值上界**。这一口径已不是到手收入：
> `settleOreSale` 现在两层串联——先用 `AbuseGuard.buyPrice` 取逐矿 steering 毛值，再经 `grantDaily` 并入全服统一
> `credit_faucet` 衰减主闸（第十一章决策②）。三矿叠加会被同一条主闸曲线压向单一天花板（几何主项前 10 档 ≈ 14.9 万），
> 「各矿独立封顶可叠加、中级金叠绿宝石反超高级单钻」那条旧警告**已随代码闭合**，不再是现状描述。

---

## 七、结论：铜+时运能不能比得过高级？（按真源衰减曲线复核）

简短版：**单看"卖系统"路径，不能**（base×cap 的乘积，铜 6,000 ≪ 钻 32,000）；
**曾经有两个口子让这个结论在实践中不成立**，其中地板那个已由第十一章决策①闭合，铜 P2P 那个已由决策③裁定但实现与裁定不符（见第十一章③「落地现状」）。

1. **"卖系统铜有 cap 兜住"的前提是衰减地板足够低——代码地板现在是 0.01（1%），不再是 0.25**。`AbuseGuard.buyPrice` 是 `price(n)=base×max(0.01, 0.97^(n-cap))`，地板比例转引 `EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO`（GameTest 锚点 `EconomyGameTests.priceFloorRatioIsOnePercent`）。过 cap 约 152 块后触 1% 地板，此后**永远停在地板**，故 base×cap 仍是「软膝盖」不是「硬天花板」——但尾巴比 0.25 时代细了 25 倍。
   - 地板单价：钻石 500×0.01 = **5 CP/个**；金 = 1.2；残骸 = 45；铜（若 base 15）= 0.15。注意 `settleOreSale` 对毛值取 `Math.floor`，故金实发 1 CP、铜实发 0 CP。
   - **按 1% 地板重算**红队那条实算：钻石 8h 现实约 1,280 个 → 前 64 个满价 32,000 + 随后约 152 个从 500 衰减到 5 共约 16,500 + 剩余约 1,066 个按地板 5 共约 5,300 ≈ **5.4 万 CP 毛值**（0.25 地板时代是约 19 万）。它落在主闸第一档内，到手仍约 5.4 万。原文「是 ceiling 的约 6 倍、单矿即反超军火商 17.5 万」**在现行代码里不成立**，「矿工理论 < 军火商」的跨职业序不再被地板尾巴推翻。
   - 原「二选一：(a) 地板砍到 0 或 ~0.02；(b) 每矿加当日绝对块数硬顶」的待裁定论调**已由第十一章决策①作废**：用户裁定保留 1% 地板 + 反矿透/反挂机巡查兜底，收入封顶职责上移给统一衰减主闸（决策②），不再要求逐矿 base×cap 变成硬顶。

2. **铜 P2P 卖军火商无任何每人每日 cap，是比"卖系统铜"更大的口子**。一名矿工独供一个 L10 军火商全部 2,400 铜/日（`Munitions_Job_DesignSpec.md:99` 反推），按 P2P 锚 28（`服务器经济系统设计文档.md:372`）= **67,200 CP/日 = 钻石单矿 32,000 的 2.1 倍**。军火商弹药需求只封「总量/价格」不封「单人吞吐」，市场自限挡不住单点垄断。须配一道「按矿工本人计的每日铜产出/出售软上限」（不分卖系统还是卖军火商，复用 0.97/0.01，cap≈850–900 铜/人/日使 base×P2P 落在 ~3 万同档）。这与 5.2 的系统保底 cap 400 是两套口径（第十章 Major-A，已由第十一章③裁定，但现有实现与裁定不符，见该节「落地现状」）。
   - 注：经济文档只锚到铜 P2P=28（8.6 占位），原 DRAFT 写的「28–45」中的 45 在三份文档里查无来源，本轮按 28 算，不引用 45。

3. **中级金（30,720）≈ 高级钻石（32,000）的「极限勉强跟上」在代码里成立**（第一章），本轮不动。注意这个对比只在「逐矿 base×cap 毛值」这一口径下有意义：`settleOreSale` 已并入统一 `credit_faucet` 主闸，各矿不再独立封顶、不能叠加印钞（第十一章决策②），故「Medium 玩家金叠绿宝石在叠加层反超 Hard 单钻」这条旧风险随之消失；逐矿 base/cap 现在只决定优先挖哪种矿与首档肥度。

---

## 八、调研接地（worldgen 配矿实况 / 跨职业锚 / 吞吐撞限 / 材料矿 P2P 需求）

### 8.1 worldgen 配矿实况（真源 = vanilla noise + biome JSON feature 列）

真源维度走 vanilla `minecraft:noise` 生成器（`dimension/mining.json`），三个 biome JSON 的 feature 列直接决定哪些矿挖得到。逐 JSON 核对：

| biome | 真源 ore feature（`features[6]` 段） | 含高价矿？ |
|---|---|---|
| `mining_easy.json` | coal_upper/lower, iron_upper/middle/small, copper | 无 |
| `mining_medium.json` | coal_lower, iron_middle/small, copper_large, **gold**, redstone, lapis | 仅金 |
| `mining_hard.json` | iron_middle, **gold/gold_lower**, redstone_lower, lapis_buried, **diamond/diamond_large/diamond_buried**, **`miningdim:ore_ancient_debris`**, **`miningdim:ore_emerald`** | 金 + 钻 + 残骸 + 绿宝石 |

**关键事实（影响定价）**：
- **Hard biome 已接线残骸与绿宝石 feature**：`mining_hard.json` 的 feature 列含 `miningdim:ore_ancient_debris` 与 `miningdim:ore_emerald`，对应的 `configured_feature` / `placed_feature` JSON 也都已落仓（残骸 `count` 1、绿宝石 `count` 4），由 GameTest `MinerGameTests.ancientDebrisAndEmeraldFeaturesWiredIntoHard` 固化。原「三 biome 全无绿宝石/残骸 feature、定价空转」的结论**已作废**。
  - 残骸**已进结算通道**，是活 faucet 不是纸面：`AbuseGuard` 的分类表把 `EconomyConstants.ORE_ANCIENT_DEBRIS` 映到 `HighValueOre.NETHERITE_SCRAP`，`ShopPriceTable.ORE_BASE_NETHERITE_SCRAP` = 4,500，`EconomyConstants.DAILY_SOFTCAP_NETHERITE_SCRAP` = 8。第六章 Hard 合计已据此补回 36,000。
  - 绿宝石**仍无结算通道**：`HighValueOre` 枚举只有 DIAMOND/GOLD/NETHERITE_SCRAP，`AbuseGuard` 的分类表也没有绿宝石矿。挖得到但卖不掉，5.1 的 18,000 仍是纸面。
- **Easy/Medium 无绿宝石、无残骸 feature**；**Hard 无 copper、无 coal feature**（只 iron/gold/redstone/lapis/diamond + 上述两条自定义 feature + 能源七矿）。第六章 Hard 合计已据此剔除铜。
- 另一套 `ore/OreType`（绿宝石 cap 12/36/96、残骸 cap 0/6/20 等难度梯度）+ `OreSystem.placementFor` 是**死代码**：`GenerationScheduler`/`worldgen` 包零调用 `placementFor`/`OreType`/`OreSystem` 的铺矿路径，生成管线只产体素不铺自定义矿。故 `OreType` 的 maxCount 不能当任何产出/定价锚。
- 探矿技能已与这套死代码解耦：`OreScanService` 现在吃 `ServerLevel` 并用 `level.getBlockState(...)` 扫真实世界（`scanWorld` / `scanWorldDetailed` / `collectWithinSphere`，类注释自述「改扫真实世界缘由（经济文档第十章 H）」），回归锚是 `MinerGameTests.oreScanReadsRealWorldBlocks`。`OreSystem.placementFor`/`cachedPlacement` 仍是无生产调用方的死代码，但已不再影响探矿技能。

**定价前置**：残骸的 worldgen 前提已满足，剩下的口径问题是它与统一主闸的关系（见 8.2，已闭合）。**绿宝石定价的前提是先补 EMERALD 结算通道三处接线**——`HighValueOre` 枚举 + `DAILY_SOFTCAP_EMERALD` 常量（含 `AbuseGuard.dailySoftCap` 分支）+ `buyPrice/classify` 分支，缺一则要么抛 `IllegalArgumentException` 要么无衰减刷穿。

### 8.2 结算通道实况（settleOreSale 现状，红队 Critical-1 已闭合）

- `settleOreSale` 现在是**两层串联**：先用 `AbuseGuard.buyPrice` 取逐矿 steering 毛值（per-ore 0.97 衰减至 1% 地板，引导撞主闸前优先卖高价矿），毛值再经 `grantDaily(player, gross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER)` 并入全服统一衰减主闸，与农夫卖菜共享同一每人每日天花板。方法注释自证：「此前直接 `ledger.credit` 绕过主闸 = 卖矿不受全服日衰减约束的印钞口（红队 Critical），现并入主闸闭合」。
- **生产调用方有两条**，不是「无调用方」：
  1. `EconomySystem.recordAndSettleBreak`（由 `EconomySystem.onBlockBreak` 事件触发）——单块挖矿卖矿；`recordMinedOre` 先把本块计入当日计数并返回含本块的累计，直接用作 `settleOreSale` 的 `countSoFar`。
  2. `EconomyService.recordMinedOreDrops`（由 `MinerSystem.replayEconomyOreCount` 经 `MinerActions` 的连锁/隧道产出路径触发）——连锁/隧道连带产出逐颗结算，整批合并进一个 `ledger.inTransaction`，与单块口径完全一致（一颗产出物发一次）。
- 故「当前生产路径下卖矿一分钱都没真正入账」「`settleOreSale` 只有单测引用」这两条旧表述**已作废**；结算时机也随之定死为 on-break 自动结算（第十章 G 随之关闭）。
- 对比：农夫卖菜 `FarmerWheatSellService` 同样走 `grantDaily`，键与档值都转引 `EconomyConstants` 的同一对常量（`FarmerConstants.WHEAT_SELL_FAUCET_KEY` / `DAILY_CREDIT_FAUCET_CAP`）。两条路径口径已收敛，红队 Critical-1 的根已拔。
- 回填依据就在本文档第十一章决策②（用户 2026-06-18 定稿「统一收入主闸」）——第十一章一直是正确口径，只是此前没回填进第六、八章的早期表述。

### 8.3 跨职业锚（经济文档 8.4「各职业日产/收益锚」）

| 职业 | 定位 | 满级日产/收益锚 |
|---|---|---|
| 矿工 | 产出（封顶） | ~11–19 万 CP（撞每日软上限） |
| 农夫 | 产出 | ~3 万株小麦/日（单价 config，未定标，见 8.4） |
| 军火商 | 产出（半被动） | ~17.5 万 CP/日（满产满销；现实 ~13–16 万，`Munitions:102`） |
| 普通全职业玩家(5h) | — | ~16 万 CP/日 |
| 千年工程师 | sink | 满修 ~3.6 万 CP（花钱，非收入） |

矿工锚定位（FF14 生产职序「主动单产职 < 半被动满产 < 全职业玩家」）：
- **理论天花板（毛值）**：Hard 真源可达四矿（铁+金+钻+残骸）base×cap 合计 109,220（第六章），仍低于经济文档矿工锚上沿 19 万；差额一部分来自绿宝石（有 feature 但无结算通道，不计），一部分来自 1% 地板尾巴（第七章 1）。口径仍待与经济文档 8.4 对齐。
- **现实大头**：撞穿钻/金两高价矿即落点，约 5.5–7 万毛值（第六章 Hard realisticDaily），显著低于军火商半被动 17.5 万与普通玩家 16 万，守住「主动单产职不反超半被动」。残骸现已是活 faucet，但每日 cap 只有 8 个（满撞 36,000 毛值），主要抬的是理论天花板而不是现实大头，故本行暂不上调，待真服跑量观测。
- **前提**：以上序现在靠的是「所有卖矿收入都经同一条 `credit_faucet` 衰减主闸」（第十一章决策②，已落码，见 8.2），而不是原先那两条「base×cap 是硬顶」「高价矿不叠加印钞」的假设——后者第一条至今不成立、第二条已不再需要。

### 8.4 农夫横向序（共用 credit_faucet，必须连带定标）

- `WHEAT_BASE_PRICE=1`（`FarmerConstants`，注释自承「PENDING 经济文档 8.6 校准」）仍是占位。
- `FarmerConstants.DAILY_CREDIT_FAUCET_CAP` **已不是 2160 占位**：它直接转引 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER`（= 60000，CP 量纲），与矿工 `settleOreSale` 共用同一常量；常量注释自证「此前农夫私有占位 2160L（株量纲误塞 CP 档）已废，改为全服唯一真源」。株量纲的 2,160 现在只剩 `WHEAT_DAILY_SOFTCAP` 一处，喂 `FarmerWheatBuyback` 的收购曲线 softCap，与 CP 档已解耦。红队 #9 要求的「economy 侧定唯一全局常量 + 农夫 cap 改引用」已落地，只是常量名是 `..._TIER` 而非建议的 `..._CAP`。
- 农夫满级 ~3 万株（经济文档 8.4）在单价定标前无法换算 CP。矿工卖矿与农夫卖菜**共用** `credit_faucet` 键（两侧都转引 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`），二者不能单调一个：统一档值一改即同时改变矿工主力与农夫的到手曲线，须联合标定。
- 红队警告：若小麦单价拍到 ×10 锚的 10 CP/株，3 万株 = 30 万 CP，反超所有人——是更隐蔽的印钞口。建议据满级 3 万株反算使农夫满级日 CP 落矿工现实同档或略低（更被动安全），量级约 2–3 CP/株（待统一 cap 定后联合标定）。

### 8.5 吞吐撞限时间与方案 B 时运口径

- **连锁/隧道在矿洞维度已全放开**（用户 2026-07-12 裁决）：`CHAIN_WHITELIST` / `CHAIN_HARD_EXCLUDE` 双表已从 `MinerConstants` 删除，唯一判定是 `ChainMiningEngine.chainable`（镐可采 + 工具档位够 + 可破坏 + 无 BlockEntity，不看矿种）。钻石/金/残骸矿全部满足，用正确档位的镐即可连带。
- 矿脉时运（`MinerConstants` 的矿脉时运期望表，+8% L4 → +50% L10）的**施加范围仍只在连锁/隧道路径**（`MinerFortune` 类注释自述作用域是 `MinerSystem.onChainProduce` 与 `MinerActions` 的 tunnelProduce；单块原版挖矿的掉落由 vanilla 直接物化，不经本类）。但既然该路径已不再按矿种筛选，**钻石/金/残骸被连锁或隧道连带时同样吃得到矿脉时运的额外掉落**。故原文「时运只对连锁白名单的铜/铁/煤生效」已作废，定价依据也不能再假定「时运对钻石不生效」。
- 额外掉落经 `MinerSystem.replayEconomyOreCount` 计入方案 B 当日矿物计数，并由 `EconomyService.recordMinedOreDrops` 逐颗走 `settleOreSale` / `grantDaily` 入统一衰减主闸——即**时运仍然只让你更快撞向主闸落点，不抬天花板**（方案 B 的本意），只是「更快」现在对高价矿也适用。
- 含义：高价矿的撞限速度现在会被吞吐（连锁 + 隧道 + 时运）加速，不再由「手挖 + 找矿 + 走位」单独主导。反通胀北极星的成立点因此完全落在衰减主闸上（第十一章决策②），而不是「高价矿物理挖不快」这条已经不存在的前提。

### 8.6 材料矿 P2P 需求（军火商配方真值）

- 军火商步枪弹配方（`Munitions_Job_DesignSpec.md` 第五章配方表）：直造 7 铜 + 16 火药 → 40 发；L6 提炼 7 铜 + 16 火药 → 70 发。L10 ~24,000 发/日（同文档产能表）。
- 铜日需 ≈ 24,000 ÷ 70 × 7 ≈ **2,400 铜/日**（单个 L10 军火商）。这是 5.2 铜 cap 400（6 名矿工人均分摊）与 P2P 单点垄断风险（第七章 2）的数据来源。
- 煤：军火商配方**只吃铜+火药不吃煤**，工费是扣信用点销毁，煤的下游真实买家在三份文档无量化锚——故 5.2 煤本轮不进系统收购。

---

## 九、红队压测结论（逐条漏洞 + 如何调价堵住 / 为何是误报）

三组红队（梯度反转猎手 / 对抗性吞吐 / 跨职业失衡）verdict 一致：**有可利用漏洞，需重调结构而非微调数值**。逐条：

| # | 严重度 | 漏洞 | 本轮处置 |
|---|---|---|---|
| 1 | Critical | `settleOreSale` 绕过统一 credit_faucet，高价矿各自独立封顶且可叠加（金30,720+绿宝石18,000=48,720 在 Medium 反超 Hard 单钻 32,000）。背离经济文档 8.5 DECIDED。 | **已闭合（第十一章决策② 落码）**：`settleOreSale` 先取 `buyPrice` 毛值再经 `grantDaily` 并入统一 `credit_faucet` 主闸（见 8.2），叠加反超消失。第六章合计已改标为「未经主闸衰减的毛值上界」。 |
| 2 | Critical | 0.25 衰减地板使 base×cap 是「软膝盖」非硬顶，过 cap 后地板尾巴线性印钞（钻石 8h ~19 万 > 军火商 17.5 万）。北极星「收入天花板=base×cap，与吞吐无关」在代码里数学上不成立。 | **已按第十一章决策① 裁定**：地板由 0.25 砍到 0.01。base×cap 仍非硬顶，但尾巴细 25 倍，同口径实算由 ~19 万降到 ~5.4 万毛值（第七章 1），不再反超军火商。封顶职责上移给衰减主闸（决策②），深档靠反矿透/反挂机巡查兜底。 |
| 3 | Critical | 铜 P2P 卖军火商无每人每日 cap、无手续费/流水，单点垄断 2,400 铜/日 = 67,200 CP（按锚 28）= 钻石单矿 2.1 倍。 | **已由第十一章③ 裁定**（按矿工本人计的每日铜产出/出售软上限 ≈850–900，复用 0.97/0.01）。但现有实现是市场侧 `MarketConstants.COPPER_IRON_DAILY_P2P_CAP=512` 的硬拒单，与裁定四点不符，须二次确认，见第十一章③「落地现状」。 |
| 4 | Major | 区域合计算术与 base×cap 不自洽（红石/青金删后幽灵残留 5,800）。原 Easy 35,100 实应 16,500，Easy/Hard 比 16% 非 22%。 | **已用干净 base×cap 重算第六章**：Easy 16,500 / Medium 47,220 / Hard 109,220（残骸接线后补回 36,000）。Easy 占 Hard ~15%，低于 20–25% 下沿，须微调（第十章 C）。 |
| 5 | Major | 绿宝石 18,000 / 残骸 36,000 产出前提在真源 worldgen 不成立（无 feature + A 套死代码 + 绿宝石不在 HighValueOre 枚举无结算通道）。 | **残骸这半已不成立**：`mining_hard.json` 已含 `ore_ancient_debris`，且已进结算通道，第六章 Hard 合计已补回 36,000。**绿宝石只剩「无结算通道」半句仍成立**（feature 已有，但不在 `HighValueOre` 枚举），进系统须补 EMERALD 三处接线。 |
| 6 | Major | 铜/铁/煤给了 base×cap 却无 cap 强制路径（不在 HighValueOre 枚举，`dailySoftCap` 抛异常），无 cap 的铜按 base15 高吞吐 4h=10.8 万 > 钻石。 | **5.2 标注「进系统收购须三处原子接线，禁止只落价」**。煤本轮不进系统（无下游买家）。 |
| 7 | Major | 煤下游买家悬空（军火商配方不吃煤），定价 4,200 是无效印钞口。 | **本轮删煤系统收购**，与红石/青金同列只做燃料/工业/村民交易（5.2 + 8.6）。 |
| 8 | Major | AFK 冻结只防「零输入挂机」（`AbuseGuard.evaluateAfk` 要 noBreak AND noMove），对「有挖掘信号的自动化」零防御；一次有效 BreakEvent 即解冻。 | **非定价层职责**（属反作弊/输入熵检测）。经济层兜底已改为依赖衰减主闸（第十一章决策②）而非「硬顶后入账 0」：深档 1% 地板仍留线性尾巴，故自动化的实操封顶靠反矿透/反挂机巡查。台账标注「AFK 只防零输入，深档尾巴靠巡查兜底」。 |
| 9 | Minor | 统一 credit_faucet 无 canonical 数值，唯一传入值 2,160 是农夫私塞占位（=小麦株 softcap，量纲是「株」非「CP」），对矿工主力荒谬偏低。 | **DONE**：economy 侧已定唯一真源常量（名为 `GLOBAL_DAILY_CREDIT_FAUCET_TIER` = 60000 CP，非建议的 `..._CAP`），`FarmerConstants.DAILY_CREDIT_FAUCET_CAP` 已改为直接转引它，`WHEAT_DAILY_SOFTCAP=2160` 留作株量纲收购曲线参数（见 8.4）。`WHEAT_BASE_PRICE=1` 仍 PENDING。 |
| 10 | 误报/非定价 → **待评估** | 「方案 B 时运计入 cap 对钻石生效」表述与代码有落差——时运实际只作用连锁/隧道（当时的白名单是铜/铁/煤），高价矿手挖单块不吃时运。 | **原判「非漏洞/反而让钻石 cap 更稳」的依据已消失**：连锁白名单于 2026-07-12 废除后，钻石/金/残骸被连锁或隧道连带时同样进 `MinerFortune.withFortuneExtras`（见 8.5）。高价矿的时运封顶现完全依赖衰减主闸，不再靠「手挖不吃时运」这条旧前提，是否仍安全须与 #1/#2 的现行口径一并复核。 |

---

## 十、跨职业锚 + PENDING（收敛后仍待人工拍板）

> 本章数值结论见第五、六、八章；此处只列收敛后**仍须人工定**的项，按优先级排。

### Critical（三条均已由第十一章裁定并落码，留作决策留痕）

1. **统一 faucet 口径裁定**（红队 Critical-1/9，经济文档 8.5 DECIDED 铁律）→ **已裁定并落码**：`settleOreSale` 已并入 `grantDaily` 同口径 `credit_faucet` 统一每人每日衰减主闸（第十一章决策②，代码见 8.2）；唯一真源常量落为 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER` = 60000 CP/档，农夫 cap 已改引用它。
2. **0.25 地板裁定**（红队 Critical-2）→ **已裁定并落码**：地板由 0.25 砍到 0.01（第十一章决策①，`EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO`）。未采纳「砍到 0」与「每矿加当日绝对块数硬顶」，保留 1% 线性尾巴 + 反矿透/反挂机巡查兜底。
3. **×1.75 软上限旋钮**（经济文档 8.1 曾建议高价矿 cap ×1.75 适配 5h：钻 112/金 448/残骸 14，代码 `EconomyConstants` 始终是 64/256/8）→ **已作废**（第十一章「架构后果」）：总收入由衰减主闸定，不再靠 cap×1.75，高价矿 base/cap 沦为相对权重。经济文档 8.1 的那句建议已同步删除。

### Major（数值/接线，裁定后即可落）

- **A. 铜 P2P 单人软上限**（红队 Critical-3）：已由第十一章③ 裁定（按矿工本人计的每日铜产出/出售软上限 ≈850–900，复用 0.97/0.01，封源头不封 P2P 流通）。**但裁定内容与现有 `MarketConstants` 实现冲突，需二次确认**——现有实现是市场侧 512 的硬拒单，口径、机制、量级、封堵位置四点都不一致，详见第十一章③「落地现状」。
- **B. 铜/铁系统收购接线**（红队 Major-6）：确认进系统后，必须 `HighValueOre` 枚举 + `DAILY_SOFTCAP_COPPER/IRON` 常量 + `buyPrice/classify` 分支三处原子同步（漏一处抛异常/无衰减刷穿）。本台账 5.2 数值（铁 30×350、铜 15×400）待此接线落地后生效。
- **C. Easy 区偏穷修正**（红队 Major-4）：Easy/Hard = 约 15%（残骸补回 Hard 后比值进一步下探），低于已采纳「20–25%」下沿。回到区间二选一：铜 base 15→20（Easy +2,000）或铁 cap 350→420（Easy +2,100）。须确认哪个更符合「低级不废但明显穷」；也可连带考虑残骸计入后是否要重定 Easy/Hard 的目标区间。
- **D. worldgen 接线**（红队 Major-5，第八章 8.1）→ **已 DONE**：残骸与绿宝石的 `PlacedFeature` 已落仓并挂进 `mining_hard.json`，由 GameTest `MinerGameTests.ancientDebrisAndEmeraldFeaturesWiredIntoHard` 覆盖。剩余的不是 worldgen 问题：**绿宝石仍待补 EMERALD 结算通道三处接线**（`HighValueOre` 枚举 + `DAILY_SOFTCAP_EMERALD` + `buyPrice/classify` 分支）；残骸已全通，无遗留。

### Minor（体验/方向，可后置）

- **E. 农夫小麦单价定标**（第八章 8.4）：据满级 ~3 万株反算单价，使农夫满级日 CP 落矿工现实同档或略低；与矿工共用 `credit_faucet`（统一档值已定为 60000），单价本身仍 PENDING（建议 ~2–3 CP/株，严禁套 ×10 锚的 10 CP/株）。
- **F. 红石/青金去留**（5.1）：确认只做村民交易/工业（本轮倾向），还是进系统收购。
- **G. settleOreSale 结算时机**（第八章 8.2）→ **已 DONE**：定为 on-break 自动结算，生产路径是 `EconomySystem.recordAndSettleBreak`（单块）与 `EconomyService.recordMinedOreDrops`（连锁/隧道逐颗），无需商店 UI。
- **H. 探矿技能修复**（第八章 8.1）→ **已 DONE**：`OreScanService` 已改为吃 `ServerLevel` 并读真实 `BlockState`（`scanWorld` / `scanWorldDetailed` / `collectWithinSphere`），与 `OreSystem` 死代码解耦；回归锚是 `MinerGameTests.oreScanReadsRealWorldBlocks`（删世界扫描退回死体素表则该用例必挂）。

---

## 十一、决策定稿（用户拍板 2026-06-18）

本节裁定第十章 Critical-1/2/3 与 Major-A，覆盖其「待定」表述。

### ② 统一收入主闸 = 衰减曲线（取代硬上限；红队 Critical-1 DECIDED）

`settleOreSale` 卖矿与农夫卖菜并入**同一条** `grantDaily` credit_faucet 衰减曲线（高价矿不再独立封顶、不再可叠加印钞）。该曲线**不是硬上限**：衰减主闸 0.6 / 60000 档，几何主项前 10 档 ≈ **14.9 万**（sum 60000×0.6^k, k=0..9 = 149093），正常游玩落点 ~10 万（正常）/ ~14.9 万（硬肝），基本撞顶。

但保留了 1% 地板（见①）：因 0.6^10≈0.006<0.01，自第 10 档（累计毛收入 ≥60 万）起系数被 1% 地板钳住恒定，此后每多 60000 毛收入恒发 **+600**，**线性、不收敛、无数学硬顶**（faucet(0, 1e9)≈1014 万）。该深档线性尾巴**只有 xray/自动化才挖得到**，实操封顶靠**反矿透/反挂机巡查**（用户决策：保留 1% 地板 + 巡查兜底）。

- 几何主项首档到手 **6 万**，衰减率 **0.6**（每等量努力档到手 = 上一档 ×0.6），前 10 档之和 ≈ **14.9 万**——这是正常游玩的有效落点，不是数学渐近线/硬顶。
- 画像落点：休闲(1 档) ~6 万 / 正常(~2.2 档) ~10 万 / 肝帝(~6 档) ~14 万 / 硬肝(撞几何主项) ~14.9 万。

| 等量努力档 | 该档到手 | 累计到手 |
|---|--:|--:|
| 1 | 6.00 万 | 6.00 万（休闲） |
| 2 | 3.60 万 | 9.60 万 |
| ~2.2 | — | 10.0 万（正常） |
| 3 | 2.16 万 | 11.76 万 |
| 4 | 1.30 万 | 13.06 万 |
| 5 | 0.78 万 | 13.84 万 |
| ~6 | — | 14.0 万（肝帝） |
| 10（撞几何主项） | 0.07 万 | 14.9 万（前 10 档 = 149093） |
| ≥10（深档 1% 线性尾巴） | 恒 +600/档 | 线性增长（xray/自动化才到，靠巡查封） |

「等量努力档」= 等量毛收入（按基础价值算）；系统按当日累计毛收入施衰减发净到手。
含义：**14.9 万 = 正常游玩「系统印钞」的有效落点**（几何主项封顶），不是数学硬顶；深档 1% 线性尾巴只有 xray/自动化挖得到，实操封顶靠反矿透/反挂机巡查。逐矿 0.97 是引导（relative attractiveness）非封顶。P2P 跳蚤收入不在内（第三/四章），靠手续费 sink + 需求自限管。

### ① 衰减地板 = 1%（红队 Critical-2 DECIDED）

`AbuseGuard.buyPrice` 与 `faucetCreditAfterDecay` 的地板从 **0.25 砍到 0.01**：过最大衰减后单价 = 基础价 ×1%（钻石 500→5 或 600→6，见待确认）。1% 尾巴细到无人肝（深档每 60000 毛只换 600 CP），但**它不收敛、无数学硬顶**——自第 10 档（累计毛收入 ≥60 万）起恒发 +600/档线性增长。正常游玩落点撞几何主项 ≈14.9 万即基本封顶；深档 1% 线性尾巴只有 xray/自动化才挖得到，实操封顶靠**反矿透/反挂机巡查**（用户决策：保留 1% 地板 + 巡查兜底）。**主闸与逐矿两层地板都用 1%。**

### ③ 铜 P2P 单人产出 cap（红队 Major-A DECIDED）

按矿工本人计的每日铜产出/出售软上限 **≈ 850–900 铜/人/日**（不分卖系统还是卖军火商，复用 0.97/0.01 衰减），并入跳蚤手续费 + 流水审计防对敲。封「源头」（铜是挖出来的），不封 P2P 流通。

**落地现状（与本裁定冲突，须二次确认）**：代码里唯一落地的铜上限是市场侧的 `MarketConstants.COPPER_IRON_DAILY_P2P_CAP = 512`（常量注释自承 DRAFT「待用户依定价台账标定」），与本节裁定四点不一致：

1. 量级是 512，不是 850–900；
2. 是**铜与铁共用**同一个额度、同一个 `COPPER_IRON_ITEM_IDS` 集合（覆盖原矿/粗矿/锭三态），不是铜专属；
3. 是**超限直接抛 `IllegalStateException` 拒绝挂单**的硬上限（`MarketEngine.place`），不是 0.97/0.01 衰减；
4. 口径是「今日该卖家这些 item 的 ACTIVE 挂单 count + 今日已 SOLD 成交 count」（`MarketDao.soldOrListedCountToday`），恰好封在本节明说**不封**的 P2P 流通侧，而不是源头（挖掘）。

按本节原文去实现会做出与现有闸互相打架的第二套规则。须二选一并同步更新已经把 512 当现实记录的其它文档（`docs/WebUI_Architecture_DesignSpec.md`、`docs/WebUI_Frontend_Wiring_Checklist.md`、`docs/WebUI_Wiring_Execution_Scope.md`、`docs/archive/reviews/Economy_Laundering_Review.md`）：要么把本节裁定改写为对齐 512 硬上限机制（不做衰减、铜铁合并、封流通侧），要么保留「850–900 + 衰减 + 封源头」并把 `MarketConstants` 的 DRAFT 值与机制按此重新实现。

### 架构后果（简化）

- 逐矿 `buyPrice` / per-ore cap **降级**为「只决定优先挖哪种矿（相对吸引力）+ 首档肥度」，不再背控通胀（主闸已锁总量）。
- 第十章 **Critical-3「×1.75 旋钮」作废**：总收入由衰减曲线定，不再靠 cap×1.75；高价矿 base/cap 沦为相对权重。

### 仍待确认（Minor）

- 钻石基础价 **500 vs 600**：有了主闸后只影响相对吸引力 + 首档肥度，不影响 15 万总顶。待用户一句。
- 第十章 Major-C（Easy 偏穷）/ D（worldgen 接线）/ Minor E–H 不受本裁定影响，仍按原表。
