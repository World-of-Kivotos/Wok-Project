# WebUI 全量接线执行范围

> 定位：**执行合同**，不是盘点。回答"这一轮做哪些、不做哪些、拆几个分支、怎么算验收通过"。
> 盘点真源是 [WebUI_Frontend_Wiring_Checklist.md](WebUI_Frontend_Wiring_Checklist.md)（每条 action 的 Java 落点与缺口性质）。
> 数据地基真源是 [WebUI_Architecture_DesignSpec.md](WebUI_Architecture_DesignSpec.md)（MCEF 宿主、cefQuery 桥、服务端权威）。
>
> 范围于 2026-08-13 拍板，三项裁定见第一章。本文的档位承自接线清单第三章（2026-08-12 十切片并行盘点），
> 本轮另行逐条复核了横切五项与 case 域，复核结论直接写进第二章，与清单冲突处以本文为准。

---

## 一、范围裁定（DECIDED，2026-08-13）

| # | 议题 | 裁定 | 影响 |
|---|---|---|---|
| S1 | 接线深度 | **50 条 planned action 一次到底** | 含 11 条需写真业务代码的 BACKEND（新 DAO 查询 / 新反查索引 / 私有字段暴露），不留 mock 尾巴 |
| S2 | 中文输入（A14） | **本轮解掉** | `WebUiScreen` 叠隐藏 EditBox 接 GLFW IME 组字事件，市场搜索 / admin 过滤 / 按名找人三处交互按"可输入"设计 |
| S3 | 服务端推送（A11） | **补三个生产发送方** | 市场成交、求婚收到、精英怪击杀结算。同时建立受控事件名常量表 |

**"一次到底"指范围覆盖，不指交付批次。** 交付仍严格遵守「一个模块一个分支 / 一个 PR 只承载一个模块」，
拆 12 个分支（第五章）。50 条塞进一个 PR 会让审查与合并互相拖累，是明确的反面教材。

---

## 二、开工前的边界快照（本轮逐条复核，非承自清单）

### 2.1 已通的真契约：19 条

| 组 | action |
|---|---|
| system | `system.echo`、`system.handshake` |
| player | `player.inventory`、`player.wallet` |
| market | `market.list`、`.place`、`.buy`、`.cancel`、`.mine`、`.history`、`.baseValue`、`.categories` |
| admin | `admin.setBaseValue`、`admin.listItems` |
| case | `case.state`、`case.open`、`case.apply` |
| 客户端本地 | `client.i18n`、`client.playCaseSound` |

### 2.2 清单的过期项（复核发现）

接线清单第四章把**开箱列为"全库零实现"**，该判定已过期：`CaseWebUiActions` 已注册
`case.state` / `case.open` / `case.apply` 三条，是盘点（08-12）之后落地的。开箱不在本轮接线范围内。

### 2.3 两条横切阻塞仍然成立（逐个验过，未信清单）

| 项 | 复核方式 | 结论 |
|---|---|---|
| A11 服务端推送 | 全库搜 `sendWebUiEvent` | 仍**只有 `MiningNetwork:142` 定义处一处匹配**，零业务调用方。推送面至今是空管道 |
| A14 中文输入 | 读 `WebUiScreen:24-26,181-189` | 仍是 step2 接口位。`charTyped` 只直接转发 BMP 字符，组字态（preedit）中文未做 |

### 2.4 前端接线成本已被设计成接近零

这是本轮工作量分布的关键事实，决定了人力该往哪投：

- `mock/handlers.ts` 的 `callMock(action, payload)` 与 `lib/bridge.ts` 的 `call(action, payload)` **同签名**；
- `isPlannedAction()` 按 `PLANNED_ACTIONS` 集合判定走内存世界还是转调真桥；
- **接通一条的最后一步只是从 `PLANNED_ACTIONS` 与 `PlannedContractMap` 删掉它**，`callMock` 的调用点与面板代码全程不动。

因此**全部成本在 Java 侧 + 契约形状对齐**，前端只承担三件事（第六章 6.1）。

---

## 三、总范围

| 类别 | 数量 | 性质 |
|---|---|---|
| WRAP（服务端逻辑齐全，只差 JSON 薄封装） | 36 | 纯体力活，可外包 |
| WRAP + BACKEND 混合 | 1 | `job.tarot.state`：等级/品质门可薄封装，CD 只读 peek 要新增 |
| BACKEND（新索引 / 新聚合 / 私有字段暴露） | 11 | 必须人工设计，不外包 |
| 需新规格后才能实现 | 2 | `job.agent.scan`（触发入口未定）、`shop.buy`（隔空下单无规格） |
| **planned action 合计** | **50** | |
| 横切工程 | 3 项 | 中文 IME、推送三发送方、错误码体系 |

---

## 四、逐条档位与分支归属

图例：`WRAP` = 薄封装；`BACKEND` = 要写真业务代码；`SPEC` = 需先出规格。
"清单行"指 [接线清单](WebUI_Frontend_Wiring_Checklist.md) 第三章的条目编号。

### 分支 W1 · 地基（7 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `system.serverStatus` | WRAP | A4 | `MinecraftServer` 公开 API 取在线数 / TPS / MSPT |
| `player.isOp` | WRAP | A6 | 仅决定是否渲染管理页签；服务端逐动作校验**不因此放松** |
| `player.itemDetail` | WRAP | A8 | 按 slot 解析 NBT：`GunsmithGunStats.from` / `NanoNbt` / 塔罗品质 |
| `player.profile` | BACKEND | A5 | 首屏聚合。`IMiningPlayerData` 指针分散无聚合读取点，不做则首屏串行 6+ 次往返 |
| `player.prefs.get` | BACKEND | A9 | **推翻清单第七章"我直接定的"第 2 条**（原定用 localStorage）。S1 选了一次到底，故落 capability 字段 |
| `player.prefs.set` | BACKEND | A9 | 同上 |
| `hub.panels` | BACKEND | A17 | 面板注册表 + 按职业等级 / OP / 婚姻门控。前端需要"我能进哪些面板"的数据源 |

### 分支 W2 · 市场增量（5 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `market.feePreview` | WRAP | B9 | `MarketFee:35-46` 纯函数已就绪且被 place 内部调用，只差单独暴露 |
| `market.p2pCap` | WRAP | B10 | `MarketConstants:51` cap=512/日 与 DAO 聚合均已就绪，包成"已用/剩余"查询 |
| `market.transactions` | BACKEND | B6 | **接线时填充已存在的 `market.history`，不新建 action**。`MarketDao` 缺 `transactionsByPlayer(UUID,offset,limit)`，表与双索引均已就绪。回执**必须带 total**（`market.list` 缺 total 是已知缺陷 B1，别再犯） |
| `market.pendingPayout` | BACKEND | B11 | `drainPendingPayout` 是登录时"取即删"的破坏性方法，须新增只读 peek（DAO 签名固定，新增而非改签名） |
| `market.tradable` | BACKEND | B12 | 依赖决策 J8（可交易白名单）。不定则前端无法灰掉不可挂物品，玩家会先托管再报错 |

### 分支 W3 · 职业一（7 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `job.progress` | WRAP | C1 | 8 职业 level/xp/dailyXp/dailyRemaining/nextLevelXp 一次拿回 |
| `job.miner.state` | WRAP | C5 | 被动数值纯函数 + `MinerChargeState` 充能/CD/开关 |
| `job.miner.scan` | WRAP | C6 | **必须保留同等防 X 光限制**：单矿种一次 + 有限半径 + 脉冲熄灭 |
| `job.farmer.state` | WRAP | C8 | 今日已售 / 耕地五档均已持久化 |
| `job.farmer.sell` | WRAP | C8 | 复用 `/farmer sell` 的先扣后发 + 收购曲线 + faucet 主闸 |
| `job.chef.state` | WRAP | C9 | 数值走 `ForgeConfigSpec` 运营可调，**必须实时读而非抄静态副本** |
| `job.brewer.state` | WRAP | C11 | 9 酒永久层数 + 月光 8 选 5 词条 + 配方表 |

### 分支 W4 · 职业二（8 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `job.agent.state` | WRAP | — | 清单无直接对应条目，档位待实现前复核 |
| `job.agent.seal` | WRAP | C17 | SealOutcome 九态服务端裁决齐全，按 targetNetworkId + affixId 直转调 |
| `job.munitions.state` | WRAP | C19 | 军火台/冲压机/装配台均是 ContainerData 驱动，按 blockPos + 按钮 id 薄封装 |
| `job.blueprints` | WRAP | C20 | `GunsmithBlueprint` 枚举（9 款枪 + requiredParts），玩家最常查的静态表 |
| `job.engineer.state` | WRAP | C21 | 档位表 / 护甲特效可封装；QTE 游标见决策 J5 |
| `job.tarot.state` | WRAP+BACKEND | C14 | 等级/品质门/碎片兑换可薄封装；**战斗窗口聚合快照与 CD 只读 peek 都没有**（`tryUse` 是校验并占用的写方法），须新增只读方法 |
| `job.tarot.buyPack` | BACKEND | C15 | 卡包是信用点主力 sink，`spentToday` 是私有计数无 getter，玩家看不到"今天还能买几包" |
| `job.agent.scan` | SPEC | C16 | **本组最大缺口**：menu/S2C/C2S 三件套齐全，但 `AgentSealSeam.buildScanSnapshot` 与 `AgentScanMenu.Provider` 全工程零调用点。须先定决策 J9（触发入口） |

### 分支 W5 · 经济（3 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `economy.status` | WRAP | D2 | `isAfkFrozen` 接口方法已就绪，包个布尔 |
| `economy.priceTable` | WRAP | D5 | `ShopPriceTable:36-42` 锚价静态常量。挖矿是最大 faucet 且价格随当日产量递减，玩家却无处查 |
| `economy.today` | BACKEND | D6 | `dailyFaucets` 是 **private Map 且无任何 public 读取方法**。须按 faucetKey 遍历的只读接口，**且必须与收支总表口径一致**（见 `economy-balance-sheet-reconciliation`） |

### 分支 W6 · 婚姻（7 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `marriage.state` | WRAP | E1 | 状态/配偶/婚龄/再婚冷却/离婚次数/共享背包格数/里程碑，聚合成一个 action |
| `marriage.buyRing` | WRAP | E2 | 走现成 Engine 方法 |
| `marriage.propose` | WRAP | E2 | 同上 |
| `marriage.wed` | WRAP | E2 | 失败原因六态，需映射前端文案 |
| `marriage.divorce` | WRAP | E2 | 失败原因四态，需映射前端文案 |
| `marriage.sharedInv` | WRAP | E5 | 仿 `PlayerWebUiActions.INVENTORY` 逐槽转 JSON；白名单已在容器层强制，前端只读展示 |
| `marriage.respond` | BACKEND | E3 | `MarriageProposals` 只有 byProposer 单向表，**无反查索引**，须新增反查 |

### 分支 W7 · 矿洞（4 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `mining.overview` | WRAP | F1 | **R1 模型：全服仅 3 个常驻共享固定实例（每难度 1 个），不是私有副本** —— UI 设计的前提认知偏差点 |
| `mining.myStatus` | WRAP | F2 | `regionAt(x,z)` + 维度校验，纯读 |
| `mining.enter` | WRAP | F3 | **必须复用 `EntryGateway.requestEnter` 权威路径**。现存三条不一致路径中，`/mining enter` 与 SelectZoneC2S 都跳过 gateCheck 且从不实际传送 |
| `mining.leave` | WRAP | F4 | 委派 `EntrySystem.leaveToFallback` |

### 分支 W8 · 精英怪图鉴（2 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `champion.codex` | WRAP | G1 | 35 词条（池/成本/最低星/互斥族/5 档数值）+ 10 星级主数据表，纯静态 dump |
| `champion.inspect` | WRAP | G2 | 按实体查星级/词条/品质配色/血量（6 星及以上走自定义血池） |

### 分支 W9 · 系统商店（3 条，跨仓）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `shop.detail` | WRAP | H2 | `recordAt(BlockPos)` 已就绪，handler 须写在主仓跨 jar 调用 |
| `shop.catalog` | BACKEND | H1 | `AdminShopRegistry` 是 private Map 按 BlockPos 存且**逐维度隔离**，须新增 public `entries()` 并遍历全部 ServerLevel |
| `shop.buy` | SPEC | H3 | `ShopTransaction.buy/sell` 逻辑完整，但**只被玩家物理点击真实告示牌的路径调用**（内嵌 reach/tamper/冷却校验），隔空下单需新规格 |

> **跨仓依赖**：ChestShop 靠 `fg.deobf` 引 `libs/` 下 miningdim 编译期快照 jar（由 `tools/prepare-miningdim-dep.ps1` 手动同步）。
> **主仓改 webui API 后须先重建该 jar 再回灌**，否则 W9 编译不过。

### 分支 W10 · 管理后台（4 条）

| action | 档 | 清单行 | 实现要点 |
|---|---|---|---|
| `admin.economy.balance` | WRAP | I2 | 抄 `/economy set` 内部的 ledgerOf + balance 范式 |
| `admin.economy.set` | WRAP | I2 | 同上。**无流水表，面板做出来也查不到历史调账**（D7 不在本轮范围） |
| `admin.job.setLevel` | WRAP | I3 | 权限校验 / setLevel / 改级后 syncTo 全就绪 |
| `admin.mining.reset` | WRAP | I4 | 活跃版 `/mining reset` **无二次确认**，面板按钮必须自行加确认弹窗 |

### 分支 W11 · 中文输入 IME（横切，客户端）

裁定 S2。`WebUiScreen` 叠一个不可见原版 `EditBox` 捕获 GLFW IME 组字事件（preedit / commit），
已上屏字符经 `WebBrowser.sendKeyTyped` 注入 CEF。接口位已在 `WebUiScreen:24-26,181-189` 留好。

**只能真客户端验**（见 `test-server-access`：固定测试服 shinoyuki@192.168.10.139）。验收必须覆盖：
拼音组字中途的候选窗、组字态回退键、中英切换、粘贴、以及**焦点在 CEF 内 input 与在 MC 界面之间切换**。

### 分支 W12 · 服务端推送（横切）

裁定 S3。补三个生产发送方，同时建立受控事件名常量表（架构文档 5.1 第 6 条：不允许各子系统自由拼字符串）。

| 事件 | 触发点 | 为什么必须推送而非轮询 |
|---|---|---|
| 市场成交 | 挂单被买走 | 玩家不在市场面板时发生，轮询要么错过要么延迟到无意义 |
| 求婚收到 | `propose` 成功 | 同上，且对方当前只收聊天栏消息（E4） |
| 精英怪击杀结算 | 分赃入账 | 现在只打 LOGGER + grantDaily，**没有任何 S2C 告诉玩家分到了多少**（G4） |

**红线（承自 `lib/bridge.ts:264-269`）**：任何功能都不得依赖本通道到达才能工作。进度类数据一律轮询。
事件只做"提示有变化"，前端收到后重新拉取权威数据，**不得把事件 payload 当权威值直接展示**。

---

## 五、分支与 PR 切分

12 个分支，每个独立 PR，每个 PR 只承载一个模块。

| 分支 | 内容 | 条数 | 依赖 |
|---|---|---|---|
| `feat/webui-wire-foundation` | W1 地基 + 错误码体系 | 7 | 无（其余全部依赖它） |
| `feat/webui-wire-market` | W2 市场增量 | 5 | W1 |
| `feat/webui-wire-jobs-core` | W3 职业一 | 7 | W1 |
| `feat/webui-wire-jobs-ext` | W4 职业二 | 8 | W1、J9 决策 |
| `feat/webui-wire-economy` | W5 经济 | 3 | W1 |
| `feat/webui-wire-marriage` | W6 婚姻 | 7 | W1 |
| `feat/webui-wire-mining` | W7 矿洞 | 4 | W1 |
| `feat/webui-wire-champion` | W8 图鉴 | 2 | W1 |
| `feat/webui-wire-shop` | W9 系统商店 | 3 | W1、跨仓 jar 回灌 |
| `feat/webui-wire-admin` | W10 管理后台 | 4 | W1 |
| `feat/webui-ime` | W11 中文 IME | — | 无（客户端独立，可并行） |
| `feat/webui-push-events` | W12 推送三发送方 | — | W1（事件名常量表） |

**W1 是全局前置**：错误码体系（A10）与 `player.profile` 的聚合读取点会被其余各支引用，先合并再铺开，
否则十个分支各自发明一套错误返回形状。W11 与其余全部无耦合，可全程并行。

---

## 六、三件必须同步做的事

### 6.1 前端侧（每接通一条都要做，共三步）

1. 按 **Java 实现**重写 payload/result 形状，类型从 `mock/planned.ts` 搬进 `lib/types.ts`；
2. 从 `PLANNED_ACTIONS` 与 `PlannedContractMap` 删掉该条（编译期双向核对锁会立刻抓出遗漏）；
3. `planned.ts` 里凡是直给中文 `displayName` 的，改走翻译键 + `client.i18n`——**该字段本身就是"尚未接线"的标记**。

不允许反过来让 Java 迁就 `planned.ts`。那 50 个形状是前端为了画界面发明的，一行 Java 都不对应。

### 6.2 错误码体系（A10，随 W1 落地）

现在回给前端的是 Java 异常原文（中英混杂，部分是内部描述），不可直接展示给玩家。
`WebUiBusinessException` + `errorCode` 的机制已存在且前端 `bridge.ts:163-179` 已能解析，
**缺的是让各 action 真的抛它而不是抛通用异常**。W1 须约定 `{errorCode, params}` 结构并由前端本地化。

### 6.3 mock 侧的已知偏差要随接线消失

`handlers.ts:18-24` 记录了三条 mock 阶段独有的偏差，接线时逐条核销：
背包不扣物品（真服先扣后发）、planned 域收支只记 `walletOverlay` 叠加层、手续费率与掉率不复刻服务端规则。

---

## 七、验收口径

每个分支的 PR 必须同时满足：

| 项 | 口径 |
|---|---|
| Java 侧 | `runGameTestServer` 全绿（当前基线 817），新增 action 必须有 GameTest 断言**具体业务结果**（金额、状态码、副作用），删掉被测逻辑测试必须挂 |
| 前端侧 | `tsc --noEmit` / `eslint` / `stylelint` / `vite build` 全绿 |
| 契约一致 | `system.handshake` 自检 `missingOnServer` 为空 |
| 核销完整 | `PLANNED_ACTIONS` 相应变短，编译期双向核对锁通过 |
| 真服验证 | 涉及 mod 运行期行为的（IME、推送、agent scan、跨仓 shop）**必须在测试服实跑**，不接受只跑单测 |
| 纪律 | diff 内零 Emoji；提交信息中文 Conventional Commits；无 AI 署名 |

**"写入不等于成功"**：任何一条 action 在未经上述验证前，不得报告接线完成。

---

## 八、仍需拍板才能动的决策

| # | 决策 | 卡住 | 现状 |
|---|---|---|---|
| J9 | **特勤扫描的触发入口** | `job.agent.scan`（W4） | 三件套齐全但零调用点。选项：专用道具 / 键位 / 平板内按钮 / 手持特定物品右键 |
| J8 | **可交易标的白名单** | `market.tradable`（W2） | 塔罗禁交易、青辉石绑定、婚戒绑定在设计里存在但挂单路径无过滤。注：`miningdim:azurite` 不是注册物品（纯账本货币），绑定规则永远匹配不到真实物品 |
| — | **`shop.buy` 隔空下单规格** | `shop.buy`（W9） | 现有 buy/sell 内嵌 reach/tamper/冷却校验，隔空下单要重新定义这三者 |
| J5 | **QTE 类交互进不进 MCEF** | `job.engineer.state` 的完整度（W4） | 建议**不进**：游标是每 tick 变化的服务端时序权威值，网络延迟直接影响判定手感。平板内只做数值预览与配方查询 |
| J6 | **挂单过期做不做** | 市场页要不要留倒计时 UI | `ListingRow` 无 `expire_at` 列。不做则挂单永久有效，托管物品长期占用 |
| J7 | **离线成交交付落点** | 收件箱面板（不在本轮 50 条内） | `drainPendingPayout` 只覆盖货款不覆盖退回物品 |

前三条**直接卡住本轮的 W2/W4/W9**，须在对应分支开工前拍板。后三条不卡本轮。

---

## 九、明确不在本轮范围

| 项 | 理由 |
|---|---|
| 开箱系统 | 已落地（`case.*` 三条真契约），不在 50 条内 |
| 货币层流水（D7） | faucet 发放 / sink 扣费 / OP 调账零流水记录，是独立的经济基建工程，且须先过收支总表口径 |
| 全服 M0 总量（D8）、排行榜 | 全库无跨玩家遍历查询，是纯新建 |
| 收件箱（B13）、挂单过期（B14） | 依赖决策 J6/J7 |
| 组队 party、日常任务、皮肤库存、物品百科、成就 | 接线清单第四章的零后端系统，前端不做或留占位 |
| 电力 / 线缆（C22） | 代码在未合并分支 `feat/generator-shell`，且 12 级中仅 IRON/COPPER 两级真实注册，现在展示是假数据 |
| 空军（第 9 职业） | 无 spec。职业列表按数据驱动写，加第 9 个不用改前端 |
| 厨师火候 / 纳米校准 QTE | 决策 J5 建议不进 MCEF，保留原生 Container GUI |
| 物品取放拖拽 | 一律走 vanilla Container 协议，搬进 MCEF 只增延迟无收益 |
| 第三方 mod 图标（J1） | 本 mod 自己的 303 张贴图已同 monorepo 构建期 copy；第三方（TACZ/Champions/farmersdelight）落中性占位块。倾向方案 (a) 新增 `client.itemIcon` 客户端本地 action，与 `client.i18n` 同构 |
