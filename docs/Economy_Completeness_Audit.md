# 经济系统完整度审计报告

审计日期: 2026-08-05
审计范围: 主 mod (Wok-Project) 全部经济相关代码与设计文档
审计方式: 12 个并行 agent 分七维取证 + 三视角对抗式复核 + 完整性批判补查 (627 次工具调用)
基线 commit: 8039ae5 (feat/generator-shell)

---

## 一、总评: 48%

**结论: 不能上线跑。**

这套经济系统的组件质量与闭环成立性严重背离。账本内核 (70)、市场引擎 (68)、测试断言强度 (70) 都是货真价实的生产级代码，但把它们串成一个能在真服跑三个月不出事故的经济体，缺的恰恰是承重的那几根。按上线安全加权得 48；若只问"收支闭环是否成立"这一个问题，真实分不超过 30。

三条主干同时断裂:

1. **CREDIT sink 全线失效**。设计中最大的日常 sink (塔罗卡包) 因量纲错配恒失败；第二大 sink (跳蚤 20% 手续费) 在游戏内无任何入口；最大额 sink (系统买枪弹) 零代码，且 datapack 还主动 ban 掉了全部 tacz 原版配方。
2. **AZURE 是纯单向货币**。faucet 每人每日稳定注入 30 点，唯一 sink 载体 (闪耀卡包) 在生存模式无配方无战利品表，回收率恒为 0。
3. **faucet 侧同时在漏**。最大龙头 `settleOreSale` 只发钱不收货，同一次挖矿产出可再进市场变现第二遍；经济层零 placed-block 守卫，唯一防线是另一个子系统的可改 config 默认值。

最致命的是这一切都不可观测: 全库无 M0 统计、无 faucet/sink 逐笔流水、无异常告警。`EconomyConstants` 自己写明 1% 地板的线性尾巴"靠巡查兜底"，而巡查在代码层等于零。

此外埋着一颗即时地雷: 正式服只要装上 champions mod，`AgentRewardHandler` 就会以 HIGHEST 抢先 discard 贡献账本并因 capability 契约失配永不发钱，全服精英怪奖励静默归零且日志无异常。

当前之所以还没爆，是因为跳蚤市场在游戏内根本打不开。**一旦接上任何玩家可达的交易通道 (包括 WOK-ChestShop)，上述缺陷会在同一天同时兑现。**

---

## 二、分层完整度

| 层 | 完整度 | 状态 |
|---|---|---|
| 核心账本层 | 70% | 基本可用 |
| 市场与存储层 | 68% | 基本可用 |
| 测试覆盖 | 70% | 基本可用 |
| Faucet 侧 | 60% | 半成品 |
| 文档对账 | 49% | 半成品 |
| 定价与风控 | 42% | 半成品 |
| 玩家入口与 UI | 35% | 骨架 |
| Sink 与闭环 | 25% | 骨架 |

### 核心账本层 (70%, 基本可用)

`EconomyWalletData` / `PlayerWallet` / `EconomyService` 是全仓最扎实的一块: long 整数记账、`Math.addExact` 防溢出、先校验后扣杜绝双花、SavedData 跨重启、25 个 GameTest 断言具体金额。

缺口在 `PlayerAbuseState.java:206/229` 的 save/load 零调用方 (反滥用态只活在 `EconomySystem.java:63` 的内存 Map)、`AbuseGuard.java:81/397/440` 三组闸门全是死代码、以及 `tryChargeDaily` 唯一生产调用方传参量纲错误。

### 市场与存储层 (68%, 基本可用)

`MarketEngine` 挂单-成交-撤单-离线待结主干完整，SQLite (WAL + xerial) 四表四索引、单写者并发模型正确、13 个 GameTest 断言精确金额与状态。

缺事务边界 (`MarketEngine.java:194-215` markSold 与 insertTxn 之间无事务，`:131-135` shrink 先于 insertListing)、无 schema 版本迁移 (`MarketDaoSqlite.java:46-105` 只有 CREATE IF NOT EXISTS)、`market.history` 是 `MarketActions.java:185` 的空数组桩、`MarketActions.java:63-67` 分页零钳制。

### Faucet 侧 (60%, 半成品)

统一衰减主闸 (`AbuseGuard.java:295-335` 分段积分 + `EconomyWalletData.java:201-221` 跨笔 carry) 是真 production，四路信用点 faucet 确实共用同一 `credit_faucet` 键。

但活跃 faucet 实为 4 条而非 6 条: 连锁回放因 `MinerConstants.CHAIN_HARD_EXCLUDE` 恒 no-op，特勤三笔全是 deadcode。最大龙头 `EconomyService.java:80-96` 全程无 `player.getInventory()` 操作，与 `FarmerWheatSellService.java:76` 的先扣后发口径不一致。

### Sink 与闭环 (25%, 骨架)

生产扣款调用点 11 处、真 sink 场景 8 个，但逐一过"逻辑正确 + 载体生存可得 + 有游戏内入口"三重检验后，只剩厨师调味台 (5 CP/份) 与婚姻三费 (低频一次性) 真正可跑。

军火工费逻辑正确但 `munitions_bench` 在 `src/main/resources/data/miningdim/recipes` 下无配方；塔罗三种包同样无配方且信用点包量纲坏；跳蚤手续费无 UI 入口；`AbuseGuard.java:81` 的重置钻石成本零调用方；系统买枪弹零代码。AZURE 回收率恒为 0。

### 定价与风控 (42%, 半成品)

三条曲线 (逐矿 0.97 steering / 主闸 0.6 分档 / 小麦 buyback) 数学形式清晰且拆分不变，有 GameTest 钉死系数。

但风控口径全是单账号: `EconomyWalletData.java:140/174/206/248` 的计数键恒为 playerId 拼接，全库零 IP/HWID/转移追踪；市场偏离费只对 `DefaultBaseValues.java:28-32` 的 4 个预设物品生效，其余走 `MarketFee.java:42-45` 的 20% 平率，对敲成本与诚实交易同价。`EconomyConstants` / `MarketConstants` / `FarmerConstants` 三个类 100% 硬编码，上线后无法热调。

### 玩家入口与 UI (35%, 骨架)

命令树 (`/mining` `/job` `/marriage` `/farmer` `/tarot` `/mchampion`) 与八个职业台 GUI 都真实接线，挖矿被动 faucet 无需 UI。

但经济核心 UI 全断: `WebBrowser.java:67` 的 `loadURL` 零外部调用方，唯一入口 `/miningdim-webui-dev` 只加载 `WebUiClient.java:95` 的内联 echo 开发页，12 个 market/admin/player action 在游戏内不可达；`AgentScanMenu.Provider` 零实例化点；`MiningNetwork.java:142` 的 `sendWebUiEvent` 零发送方 (余额只能轮询)；余额唯一入口是 `JobCommands.java:96-109` 自称临时调试的 `/job wallet`；无任何 OP 调账命令。

### 测试覆盖 (70%, 基本可用)

全库 63 个文件、766 处 `@GameTest`，经济与市场核心 44 个，抽查断言全部落到具体金额差值、SOLD/CANCELLED 状态串、库存件数、流水行数、副作用计数，无 is-not-null 独撑。

盲区是层次性的: `src/test` 下零文件 (纯逻辑类无法毫秒级验证)；`*GameTests.java` 中 `CommandSourceStack` 零命中 (7 个命令根、约 25 个子命令的 `.requires` 权限门全无验证)；测试直调 `MarketActions.BASE_VALUE.handle` 等静态字段，绕过 `MarketSubsystem.java:42-46` 的 `registerAll`，删掉注册接线全部市场测试仍绿。

### 文档对账 (49%, 半成品)

四份文档 49 项可验证承诺落地约 24 项。已落地的是货币内核与反通胀主闸；未落地集中在开箱系统整章、TACZ 皮肤归属 (自标 Blocking)、交易信用等级 I-V、动态基准价滚动均价、7 天 trade hold、流水异常检测、可观测性指标。

同时有 6 处文档已被代码超越却未回写 (PostgreSQL 实为 SQLite、手续费改为上单即收、台账记载的 `settleOreSale` 无调用方与 worldgen 缺 feature 均已修)，另有互相矛盾处 (重置成本走 `chargeItem` 还是 `tryCharge`，两份文档打架)。

---

## 三、关键缺口清单

按严重度排序。全部条目均经对抗式复核保留，被推翻的 20 项已剔除。

### Critical

**[1] settleOreSale 名为收购却从不扣物品，同一次挖矿产出可双重变现**

- 证据: `EconomyService.java:80-96` 全程只有 buyPrice 计算与 grantDaily 入账，无任何 `player.getInventory()` 操作；`EconomySystem.java:191-200` 只把 Block 传下去，掉落物照常由原版物化进背包。对照 `FarmerWheatSellService.java:76` 的 chargeWheat 先扣后发。
- 影响: 卡上线。玩家挖一块钻石白拿约 500 CP 且钻石留在背包，可再进 P2P 通道卖第二次，实际 faucet 强度是账面两倍以上；与跨账号转移叠加后一次产出可撑起三条现金流。

**[2] 经济层零玩家放置方块守卫，唯一防线是另一子系统的可改 config 白名单**

- 证据: `EconomySystem.java:136-200` 只判 isCanceled / ServerPlayer / isMiningDimension / regionAt，无 placed-block 追踪，economy 包 grep `EntityPlaceEvent|playerPlaced` 零命中；唯一拦截在 `RulesSystem.java:44-72` 加 `MiningServerConfig.java:237-240`，默认白名单只含 `minecraft:scaffolding`。
- 影响: 卡上线。运营方往 placeWhitelist 加一项掉落自身的高价矿 (如 ancient_debris)，即开启零成本吃满单日上限的印钞循环 (约 160 次放置-破坏实发约 11.7 万，继续刷可吃满 14.9 万几何主项)。反滥用闸门不应依赖另一子系统的 config 默认值。

**[3] 塔罗信用点卡包量纲错配，设计中最大的日常 CREDIT sink 恒失败**

- 证据: `TarotPackItem.java:79-89` 把 `dailyPackCap()` (= `TarotConfig.DAILY_PACK_LIMIT`，单位包数，默认 20) 传给 `tryChargeDaily` 的 dailyCap；`EconomyWalletData.java:143` 的判定式是 `spentToday + amount > dailyCap` 且 amount 是信用点金额 (200/1200)。`0+200>20` 恒真，`:65-69` 随即 cannot_afford 并 fail。
- 影响: 卡塔罗职业整条核心循环 (抽卡永不可用)，同时抹掉文档估算占日常真 sink 约 67% 的那一块。
- 修法二选一: 另建包数计数键，或把 cap 改成 PRICE 乘 LIMIT。

**[4] AZURE 是纯 faucet-only 货币，唯一 sink 载体在生存模式不可获得**

- 证据: faucet 侧 `EconomyConstants.java:106` 每人每日硬截断 30 点持续注入，`ChampionRewardHandler.java:150-156` 与 `AgentRewardHandler.java:117` 双路发放；sink 侧全库仅 `TarotPackItem.java:131` 一处，而 `tarot_pack_shiny` 在 `src/main/resources` 下无 recipes 无 loot_tables (grep `tarot_pack` 仅命中 lang/models/tags)，唯一来源是创造模式物品栏。
- 影响: 卡青辉石整条经济线。生存服上 AZURE 回收率恒为 0，随运营时间单调堆积，且它是闪耀卡包 (约 390 万 CP 等值毕业线) 的唯一 PvE 来源，通胀无出口。

**[5] M0 货币总量、faucet/sink 逐笔流水、异常告警三件套全库零实现**

- 证据: `EconomyWalletData.java:43` 的 wallets 为 private 且唯一遍历点是 `:265` 的 save()，无任何聚合 getter；economy 包全部 LOGGER 调用仅 `EconomySystem.java:69/82/89/234` 四条生命周期日志；唯一资金流水是 market 的 transactions 表且 `MarketEngine.java:207-208` 的 fee 列恒写字面量 0；`EconomyConstants.java:63-68` 自承 1% 地板的线性尾巴靠反矿透/反挂机巡查兜底。
- 影响: 卡一切数值校准与事故定位。无法回答全服现有多少信用点、昨日净印多少、哪条 faucet 贡献最大；出现刷钱 bug 时无任何数据可还原资金流向，所谓巡查兜底在实现层等于无兜底。

**[6] 装上 champions mod 即静默清零全服精英怪奖励 (capability 契约失配加抢先 discard)**

- 证据: `AgentRewardHandler.java:59-71` 挂 `EventPriority.HIGHEST`，championOf 失败即 `ContributionTracker.discard` 并 return；判定源 `AgentChampionData.java:47-56` 读第三方 `ChampionCapability`，而自研 `ChampionPromoter.java:100-107` 只写 `MiningChampions` capability；受害者 `ChampionRewardHandler.java:91-93` 在 `hasLedger=false` 时直接 return；注册门 `AgentSystem.java:55-62` 仅 `ModList.isLoaded(champions)`。
- 影响: 卡战斗 faucet 整条。正式服一旦装 champions，所有精英怪的信用点与青辉石奖励全部消失，且两个 handler 都走正常早退路径、日志无任何异常，排查极难。不装则特勤加强奖励与周悬赏恒不发。

**[7] 跳蚤市场服务端 production 但游戏内完全不可达，唯一 P2P 通道与第二大 sink 双双为零**

- 证据: `WebBrowser.java:67-70` 的 `loadURL` 经反向 grep 仅有自身定义与 `WebUiClient.java:117` 一条注释，零外部调用方；唯一打开路径 `WebUiClientSubsystem.java:61` 到 `WebUiClient.java:95` 的 `create(devPageDataUri())` 是内联 echo 开发页；grep `Commands.literal` 无 market 根、无 KeyMapping。
- 影响: 卡整条 P2P 交易与 20% 手续费 sink。`MarketEngine` (429 行) 加 DAO (485 行) 加 12 个 action 目前只有 GameTest 能跑到，真服上等同未上线；`unified-ui-entry-plan` 的平板 hub 是其上游依赖。

**[8] 系统买枪弹 sink 零实现，而 datapack 已 ban 掉全部 tacz 原版配方，两头皆空**

- 证据: `MunitionsConfig.java:100-132,224-259` 定义 9 组 `SHOP_PRICE_*` / `SELL_PRICE_*`，但 `MunitionsCaliber.java:143/148` 的 `shopPrice()` / `sellPrice()` 全库零调用方，无商店方块/命令/UI；`src/main/resources/data/miningdim/recipe_filters/munitions_disable_tacz.json` 的 blacklist 为 `^tacz:.*$`；`src/main/resources/data/miningdim/recipes` 全量 25 个文件中无 `munitions_bench` / `gunsmith_press` / `gunsmith_assembly_bench`。
- 影响: 卡文档指定的最大额 sink (高端枪 60-100 万 CP 吸收老玩家储蓄)。当前枪械只能经 P2P 换手、钱不离开经济；承载工费 sink 的三个方块本身也只能创造模式获得。

### Major

**[9] faucet 计数键单账号维度加 /farmer sell 零职业门，跨账号洗额度通道敞开**

- 证据: `EconomyWalletData.java:140/174/206/248` 计数键恒为 playerId 字符串拼接，全库 grep `getIpAddress|hwid|altAccount` 零命中；`FarmerSystem.java:69-76` 命令树无 `.requires`；`FarmerWheatSellService.java:56-97` 全函数无 JobServices/JobId 引用；`data/miningdim/recipes/farmer/farmer_seed.json` 为 1 个原版 wheat_seeds 无序合成，L1 即可放 9 块低级耕地。
- 影响: 卡"每人每日"这一约束单位的有效性。群体上限恒等于全员额度之和，新号可零门槛自产自卖闭环。注: memory 记载的"农夫走等级门 level>=2"指的是耕地档位解锁，卖出口至今无门。

**[10] 偏离手续费只对 4 个预设物品生效，对敲洗钱成本恒为 20% 平税**

- 证据: `MarketFee.java:42-45` `v0.isEmpty` 直接退平率；`DefaultBaseValues.java:28-32` 仅 diamond / gold_ingot / netherite_scrap / farmer_wheat 四项；`BaseValueResolver.java:12` 第三层成交中位数自承后续 commit。
- 影响: 卡反洗钱内核。`MarketConstants.java:32-38` 注释里"对敲洗钱净亏到付不起"的断言对注册表中除这 4 项外的所有物品都不成立，选无锚物品即可把转移成本压到与诚实交易同价的固定 20%。

**[11] PlayerAbuseState 零持久化，服务器重启即重置逐矿 steering 计数**

- 证据: `PlayerAbuseState.java:206/229` 的 save/load 经反向 grep 零调用方；`EconomySystem.java:63` 是纯内存 ConcurrentHashMap，`:107-108` 注释自陈当前阶段无持久层新建，`:112-115` onLogout 为空体；对照 `EconomyWalletData.java:262-320` 主闸计数随 SavedData 落盘。
- 影响: 卡逐矿 steering 这层实际封顶。重启后钻石单价回到满价 500 (再送约 32000 毛收入窗口)，金与残骸同理；AFK 冻结态与死亡再入冷却一并清零。两层反滥用态持久化纪律不对称，知道重启表的人独享。

**[12] 市场资金一致性无事务边界: 离线待结先删后发、成交三步无事务、挂单 shrink 先于落库**

- 证据: `MarketEngine.java:276-289` 先 `dao.drainPendingPayout` (已 commit 删行) 再 grant，且 `MarketSubsystem.java:71-81` 只守卫 `MarketServices.isRegistered()` 不守卫经济门面 (`EconomySystem.java:77-84` 在矿山维度缺失时明确不注入门面并继续启动)；`MarketEngine.java:194-215` markSold 到 insertTxn 到 `inventory.add` 三步无事务；`:131-135` `stack.shrink` 先于 insertListing；`MarketDao.java:15-17` 刻意不暴露 Connection。
- 影响: 卡市场上线。离线卖家登录时若经济门面未就绪或 grant 溢出，待结款已删除即永久消失；insertTxn 抛异常则买家钱货双失；insertListing 抛异常则物品与手续费同时蒸发，全部无补偿路径无日志留痕。

---

## 四、已经做扎实的部分

1. **统一信用点衰减主闸是真生产级**: `AbuseGuard.java:295-335` 对累计毛收入指针做确定性分段积分，区间可加性保证拆批与整笔折算一致 (杜绝拆单多刷)，配合 `EconomyWalletData.java:201-221` 的持久化 carry 杜绝深档小额被逐笔 floor 吞光；四路信用点 faucet 确实全部传同一 `credit_faucet` 键与 60000 档，无任何 faucet 自开私有上限。

2. **账本内核的不变量守得住**: `PlayerWallet` 用 long 整数记账无浮点，`tryDebit` 先校验后扣杜绝双花，`credit` 用 `Math.addExact` 把溢出转成 `BALANCE_OVERFLOW` 领域异常自然冒泡而非静默回绕；`IEconomyService` 刻意不暴露任何 P2P transfer 方法，且 `EconomyGameTests.java:442-446` 用反射断言把这条不变量钉死。

3. **GameTest 断言强度达标且可作为回归网**: 全库 766 处 `@GameTest`，市场侧断言到 sink 蒸发额、双方精确余额差、SOLD 状态串、流水行数、二次买入必抛；经济侧断言到档系数、14.9 万落点、拆批一致性、溢出后余额不回绕。多处显式写明"删除被测逻辑则本断言必挂"，无弱校验独撑的用例。

4. **市场引擎的业务主干设计正确**: 托管走 item_nbt BLOB、部分购买拆分、条件 UPDATE 带 seller 加 ACTIVE 双守卫、撤单先验背包空间再改状态 (失败不标状态防物品消失)、离线待结用显式事务 drain、SQLite 走 WAL 且显式 `Class.forName` 规避 FML 模块化类加载、build.gradle 双声明驱动。

5. **WebUI 服务端权威纪律到位**: `C2SWebUiRequest.java:41-51` 经 `enqueueWork` 切主线程并一律取 `ctx.getSender()` 不信包内 uuid；`WebUiServerDispatcher.java:90-152` 是唯一 Gateway try-catch 边界；admin 动作经 `PlayerList.isOp` 门控且有双向 GameTest。

6. **全库零 mixin**: `src/main/resources` 无任何 mixins.json，`src/main/java` 无 `org.spongepowered.asm` 引用，不存在改写原版掉落/破坏/背包路径的字节码注入。所有关于挖矿结算与取消事件的结论不会被绕过，这条攻击面可正式关闭。

7. **反洗钱的两条硬边界已经立住**: 货币层完全不提供 P2P 接口，玩家间转移只能经收摩擦的市场通道；`Currency.AZURE` 从挂单源头被拒 (`MarketEngine.java:82-85`)，婚姻共享背包另有高价值物黑名单 (`SharedBackpackWhitelist.java:12-45`)。

---

## 五、修复优先级

1. **先修 settleOreSale 不扣物品** (`EconomyService.java:80-96` 加库存扣减，口径对齐 `FarmerWheatSellService.chargeWheat` 的先扣后发)，并给 `EconomyGameTests` 补一条"挖矿入账后背包矿石必须减少"的契约断言。

   理由: 这是最大 faucet 上的口径缺陷，也是接任何交易通道 (含 ChestShop) 前必须闭合的前提。现在没爆只因市场不可达；一旦有可达的变现出口，双重变现立刻成为玩家日常主流程，届时所有衰减曲线的标定值全部作废。

2. **建 faucet/sink 逐笔流水表加 M0 聚合统计加 OP 查询命令** (至少: 全服 CREDIT/AZURE 总量、按 faucetKey 与 sink 场景的当日汇总、单人单日实发跨档 WARN 日志)。

   理由: `EconomyConstants.java:63-68` 把 1% 地板线性尾巴的封顶责任明确转嫁给巡查，而巡查在代码层为零。没有这层，后续每一个修复的效果都不可验证，ChestShop 上线后新增的资金流向更是完全盲飞。这应当作为所有调参与新通道的准入门槛。

3. **把 CREDIT sink 主动脉接回来**: 修 `TarotPackItem` 量纲 (另建包数计数键或把 cap 改成 PRICE 乘 LIMIT)，并给 `tarot_pack_common` / `advanced` / `shiny` 与 `munitions_bench` / `gunsmith_press` / `gunsmith_assembly_bench` 六个 sink 载体补生存配方或战利品途径。

   理由: 逻辑正确但载体不可得，与逻辑坏一样等于 sink 为零。当前生存服真正跑得起来的 CREDIT sink 只剩调味台 5 CP/份，结构上保证单向净流入。AZURE 侧更急，闪耀卡包是它唯一的出口。

4. **修 champions capability 契约失配**: 让 `AgentChampionData` 读自研 `MiningChampions` capability (或给 `AgentRewardHandler` 加"判定失败不 discard"的兜底)，并加一条"装载第三方 champions 时精英奖励仍正常发放"的 GameTest。

   理由: 这是唯一一颗装个 mod 就当场引爆、且日志完全无异常的地雷。战斗 faucet 整条静默归零，玩家只会以为打精英不给钱，排查成本极高。修复代价小但收益是消除一整类事故。

5. **给 /farmer sell 加职业/入职门** (克隆 `AgentBountySavedData` 的 SavedData 布尔标志范式，勿用 `IJobService.level`，它对任何玩家恒返 1)，并把 faucet 计数键加第二维度或改为全服供给口径。

   理由: 计数键单账号是所有洗额度问题的总根，卖菜零门则是最容易被利用的具体落点 (新号 1 个原版麦种即可闭环)。ChestShop 会把"把货集中给一个号变现"从理论变成一键操作，必须先补。

6. **把 PlayerAbuseState 接进持久层** (与 `EconomyWalletData` 同一 SavedData 或玩家 capability)，并接线 `AbuseGuard.checkAndChargeReset` / `checkReentryGate` 或明确删除，同时修正 `MarriageTeleport.java:28` 的错误注释。

   理由: 两层反滥用态持久化纪律不对称可被重启利用；三组死闸门要么接上要么删掉，留着会让后续接线者误以为重置已收费、死亡冷却已生效。注释与实现不符是比缺功能更危险的技术债。

7. **市场层补事务边界与 schema 版本**: 给 DAO 加 begin/commit 供业务层驱动 (markSold 与 insertTxn 同事务)、把 `drainPendingPayout` 改为先发后删或加经济门面就绪守卫、加 `user_version` 与迁移路径、把 `market.list` 分页钳到与 `admin.listItems` 一致的 1 到 200。

   理由: 这些是市场 UI 上线当天就会暴露的资金一致性与可用性问题。schema 版本尤其要在有真实数据前加，一旦老存档跑起来再想加列就只能手工改库或丢数据。

8. **把经济核心常量接进 ForgeConfig**: `EconomyConstants` (衰减底数/档大小/地板/软上限/AZURE cap)、`MarketConstants` (费率/K/铜铁 cap)、`ShopPriceTable` (锚价)、`FarmerConstants` (小麦单价与地板)；同时清理 `MiningServerConfig` 里 `ore.baseWeight` 与 difficulty 三乘子与 `ore.useDatapackDistribution` 这几组死键，并决定原版 biome decoration 这条矿供给旁路是否要关。

   理由: 上线后观测到通胀却只能改代码重编译不可接受。补查发现矿供给实为双链路 (`OreGenerator` 体素铺矿加三个 biome JSON 里的原版矿脉特征)，而 ore 段配置多数是死键，运营方以为改配置能收紧印钞、实际只有 `globalDensity` 一个旋钮真接线，这个认知错误必须在标定前纠正。

9. **补两类测试盲区**: 给纯逻辑类 (`MarketFee` / 衰减曲线 / `PlayerWallet` / `MarketCategoryTree`) 建 `src/test/java` 下的 JUnit 穷举与边界用例；给命令层与 action 注册接线补 GameTest (至少断言 dispatcher 能 resolve 到 12 个 market/admin/player action，以及 `/job set` 的 level 2 门与 `/farmer sell` 新加的职业门真实生效)。

   理由: 当前删掉 `MarketActions.registerAll()` 全部市场测试仍绿，删掉任一 `.requires` 也不会有测试变红，这两处正好是"删掉被测核心逻辑测试必须挂掉"判据的反例。ChestShop 会新增一批命令与权限门，先把这条纪律补上再写新代码。

---

## 六、对 WOK-ChestShop 的接入结论

WOK-ChestShop (`World-of-Kivotos/WOK-ChestShop`) 当前为空仓 (仅 AGPL-3.0 LICENSE + Initial commit)。基于本次审计，接入分两阶段。

### 阶段一: 只做系统商店形态 (当前状态下唯一安全的切入点)

形态: 管理员/NPC 箱子按锚价买断玩家物品，或向玩家出售枪弹，**不开玩家对玩家的箱子挂牌**。

- 买断价直接复用 `ShopPriceTable` 与 `MunitionsConfig` 的 `SHOP_PRICE_*`；`MunitionsCaliber.shopPrice()` / `sellPrice()` 已定义好却零调用方，正好是现成接口。
- **收购侧一律经 `IEconomyService.grantDaily` 并入同一 `credit_faucet` 键**，不得用 `grant`，否则就是又开一个不受主闸约束的印钞口。
- 售卖侧扣款走 `IEconomyService.tryCharge`。

为何安全: 它补的恰好是文档列明却零代码的最大额 sink (系统买枪弹)，且不引入任何新的 P2P 转移通道，因此不会放大跨账号洗额度、不会绕开市场手续费、不依赖尚不存在的流水审计。

### 阶段二: 开 P2P 箱子挂牌的四条前置硬门槛

1. 复用 `MarketFee` 同一条偏离费曲线并把 fee 落流水，**不得自建费率**。
2. 先修 `MarketEngine.java:120-124` 的 "fee 为 0 撞 `PlayerWallet.requirePositive` 抛异常" 问题 (低价商品开店即崩)。
3. 先把 `BaseValueResolver` 第三层成交中位数补上，否则无锚物品恒 20% 平税，箱子商店会成为比跳蚤更方便的对敲工具。
4. 流水与 M0 已就绪 (即上文修复优先级第 2 条)。

理由: 箱子商店天然可达 (右键即用)，会成为全服第一条真正跑起来的 P2P 通道，也就是第一条真正会被用来洗钱的通道。在偏离费只覆盖 4 个物品、无任何流水的当下开 P2P，等于把现有反洗钱设计里唯一的摩擦点作废。

### 跨仓工程约束

跨仓调用需要主 mod 把 `IEconomyService` 提升为对外 API 并在 `mods.toml` 声明依赖。当前两个第三方 mod (TACZ / Champions) 都靠运行期 `ModList` 探测接线，缺口 [6] 那颗静默地雷正是这种松散接线的产物，不要重复。

---

## 附: 审计方法与可信度

- 七个取证维度: 核心账本层 / 市场与存储层 / Faucet 全普查 / Sink 与收支闭环 / 设计文档对账 / 反洗钱风控 / 玩家可达性与测试覆盖。
- 三个对抗式复核视角: 空壳猎手 (推翻"已实现"的乐观判断)、漏判反查 (核实"缺失"是否其实已在别处实现)、经济学健全性 (核对收支闭环数值依据)。三轮共推翻或修正 20 项初判结论，本报告已采纳修正后的版本。
- 一个完整性批判者补查了 config 死键、resources 数据、客户端余额同步、初始资金与重置、离线收付款、外部 mod 交互点、并发竞态、mixin 存在性八个易漏角落。
- 所有结论均带 `file:line` 证据。本报告中的行号对应基线 commit 8039ae5，后续代码变动后需重新核对。
