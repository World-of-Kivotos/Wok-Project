# WebUI 服务端推送设计规格 — W12

状态: **DEFERRED (已推迟, 未实现)**
所属: WebUI 全量接线 W12 横切分支
前置文档: `WebUI_Architecture_DesignSpec.md` 第 5.1 节、`WebUI_Wiring_Execution_Scope.md` 第四章 W12

---

## 一、现状: 通道整条建好, 生产侧零调用方

推送链路的每一环都已存在且已接线, 唯独没有任何业务代码去发。全链路核对如下:

| 环节 | 落点 | 状态 |
|---|---|---|
| S2C 包定义 | `network/S2CWebUiEvent.java` (`record(String eventName, String dataJson)`) | 已实现 |
| 包注册 | `MiningNetwork.java:77-78` | 已注册 |
| 服务端发送门面 | `MiningNetwork.sendWebUiEvent(ServerPlayer, S2CWebUiEvent)` (`:142`) | 已实现, **零生产调用方** |
| 断连守卫 | 同上, 内部 `canReceive(player)` 短路 | 已实现 |
| 客户端接收 | `client/webui/WebUiClientReceiver.onEvent` | 已实现 |
| 桥未就绪处置 | 同上, 静默 `LOGGER.debug` 后丢弃 | 已实现 (刻意丢弃, 见第五章) |
| JS 派发 | 页面预置 `window.miningdimOnEvent(name, dataJsonString)` | 已实现 |
| 前端订阅 | `webui/src/lib/bridge.ts:360` `on(eventName, handler)` | 已实现 |

前端 `bridge.ts:349-354` 把 handler 的 data 定成 `unknown` 而非具体类型, 理由原文写着
"服务端 sendWebUiEvent 至今零业务调用方, 现在给事件定字段名就是凭空发明契约"。
**W12 的全部内容就是把这句话变成过去式**: 定事件名、定载荷、补三个发送方。

---

## 二、为什么推迟

推迟的代价是实的 (第六章), 但推迟的理由更硬: 三个发送方各自挂在三个不同子系统的业务路径上
(市场成交 / 婚姻求婚 / 精英怪结算), 而其中两个的宿主 action 本轮才刚接线完。

先把 `marriage.*` 与 `champion.*` 的只读契约跑通、确认聚合形状稳定, 再回头挂推送,
比一边定契约一边定事件载荷要省一轮返工。故排在接线批次之后。

---

## 三、事件名常量表 (架构文档 5.1 第 6 条)

**硬约束: 不允许各子系统自由拼字符串。** 现在零调用方, 是建这张表的最佳时机——一旦有三处各自
`sendWebUiEvent(player, new S2CWebUiEvent("marketSold", ...))` 散着写, 前后端就会因大小写/连字符/单复数对不上而静默失联,
且这种失联**没有任何编译期或运行期报错**, 只表现为"推送偶尔不到"。

规格:

- 新建 `webui/server/WebUiEventNames.java`, 全 `public static final String`, 私有构造。
- 命名一律 `域.事件` 小驼峰, 与 action 名同风格 (`market.sold` 而非 `MARKET_SOLD` / `marketSold`)。
- 前端在 `webui/src/lib/` 建对应的 `as const` 常量对象, 并在 `bridge.ts` 加一道与
  `AssertContractCoverage` 同构的编译期覆盖锁。
- **两张表仍是手工同步**——与现有 action 表同病。缓解手段是让 `system.handshake` 的自检把事件名一并回报,
  前端启动时比对, 缺一个就在控制台报出来 (与 `missingOnServer` 同机制)。

初版三个名字:

| 常量 | 值 |
|---|---|
| `MARKET_SOLD` | `market.sold` |
| `MARRIAGE_PROPOSAL_RECEIVED` | `marriage.proposalReceived` |
| `CHAMPION_REWARD_SETTLED` | `champion.rewardSettled` |

---

## 四、三个发送方

以下落点均已核对过真实代码位置, 但**动工时须重新读码复核**(本文档写于接线批次之前, 行号会漂)。

### 4.1 市场成交 — `market.sold`

落点: `market/MarketEngine.java` 的 `buy(...)` (约 `:176`) 内卖家结算分支 (约 `:227`)。

该处已区分两条路径: 卖家在线即时 `grant`, 离线落 `pending_payout` 待登录结算 (约 `:294-311`)。
**推送只挂在线分支**——离线路径的玩家连接都不存在, `canReceive` 必然短路, 挂上去是死代码。

离线卖家怎么知道自己卖掉了? 登录结算那条路 (`drainPendingPayout`) 本来就会把钱打进去,
面板打开时 `market.mine` / `market.pendingPayout` 会显示。这是"轮询兜底"原则的正例, 不需要为它补推送。

为什么必须推送: 挂单被买走这件事发生在**卖家不在市场面板上**的时候。轮询要么错过 (面板没开),
要么延迟到毫无意义 (开着别的页)。

### 4.2 求婚收到 — `marriage.proposalReceived`

落点: `marriage/MarriageProposals.java` 的 `propose(UUID proposer, UUID target)` (`:32`) 的调用方。

**注意不要挂在 `MarriageProposals.propose` 本身**: 它的入参是两个 `UUID`, 拿不到 `ServerPlayer`,
硬要拿就得在数据结构层反查玩家列表——把网络下发塞进一个纯数据结构里, 是架构污点。
正确落点是上层 `MarriageEngine` 中调用它的那个方法 (该处持有 `ServerPlayer`)。

W6 接线新增的 `marriage.respond` 反查索引与本事件是同一份数据的两个出口, 应一并复核口径。

为什么必须推送: 对方当前只收到一条聊天栏消息 (清单 E4), 面板上不刷新。

### 4.3 精英怪击杀结算 — `champion.rewardSettled`

落点: `champion/integration/ChampionRewardHandler.java` 的 `onChampionDeath` (`:88`),
逐玩家 `grantDaily` 那一处 (约 `:146`)。

现状核对无误: 该方法只在 `:122` 打了一行 `LOGGER.info`, 然后逐玩家 `grantDaily` 入账,
**没有任何 S2C 告诉玩家自己分到了多少** (清单 G4)。玩家的观感是"打完了, 钱好像多了点, 不知道多少"。

载荷需要点数据才有意义 (见 5.2), 但仍以"提示有变化"为主。

---

## 五、红线

### 5.1 任何功能都不得依赖本通道到达才能工作

承自 `lib/bridge.ts` 的既有纪律。三条具体推论:

1. **进度类数据一律轮询**。事件不承担"驱动状态机"的职责。
2. 玩家没打开面板时, `WebUiClientReceiver.onEvent` 会因 `currentBridge == null` **静默丢弃**。
   这不是 bug 是设计——但它意味着事件的到达率天然不是 100%, 任何按"必达"写的逻辑都会错。
3. 断连/重连期间的事件不补发, 不做离线队列。补发队列要解决排序、去重、过期三件事,
   收益却只是省掉一次玩家开面板时的拉取——不划算。

### 5.2 事件载荷不得当权威值展示

前端收到事件后**必须重新拉取权威 action**, 再用拉回来的值渲染。载荷里的数字只用于:

- 决定要不要弹提示 (以及提示里那句话怎么写);
- 决定重新拉哪一条 action (路由);
- 去重 (同一笔成交的重复事件)。

理由: 事件在网络上是单向的, 没有请求-回执的对账机制; 而 action 有。让两条路径的数据都能上屏,
等于给同一个数值开了两个真源, 迟早对不上, 且对不上时无从判断哪个是对的。

### 5.3 载荷体积

`S2CWebUiEvent.encode` 用 `buf.writeUtf`, 上限 32767 字符, 超限**在编码期抛异常**。
考虑到 5.2 已经把载荷限定成"提示 + 路由 + 去重", 正常载荷应在几百字节内。

但仍须显式设一道守卫: 与 `WebUiServerDispatcher.respond` 已有的回执体积收口同构——
超限时记 WARN 并降级成无载荷事件 (事件名照发), 而不是让业务路径因为一个提示包炸掉。
**成交结算不能因为提示发不出去而回滚。**

### 5.4 线程

`sendWebUiEvent` 必须在服务端主线程调用。三个落点均已在主线程 (事件总线 / 命令 / 交易结算), 无需切线程。
不得从 SQLite 工作线程或异步回调里直接发。

---

## 六、推迟的代价 (现在就生效的约束)

在 W12 落地之前, 前端**只有轮询**。这条代价会随时间线性增长:

> 越多页面按"轮询就够了"的假设写, 补推送时要重构的就越多。

具体约束, W6/W8 接线时即须遵守:

- 需要"变化时通知"的地方, 前端**留出订阅接口位**但暂时不接 (`bridge.on` 已就绪, 挂空实现即可),
  不要把轮询间隔写死进组件内部。
- 轮询间隔集中配置, 不要每个页面各写一个 `setInterval(5000)`——W12 落地后要按事件驱动调整间隔,
  散落的定时器会漏改。

---

## 七、验收口径

| 项 | 口径 |
|---|---|
| GameTest | 三个发送方各有 GameTest 断言"该业务路径确实调用了发送门面且事件名/载荷正确"; 删掉发送调用测试必须挂 |
| 体积守卫 | 超长载荷降级为无载荷事件, 有 GameTest 覆盖 (仿 `WebUiResponseSizeGameTests`) |
| 断连 | 对已断连玩家发送不抛异常 (`canReceive` 守卫), 有 GameTest 覆盖 |
| 覆盖锁 | 前后端事件名常量表编译期双向核对通过; `system.handshake` 自检回报事件名 |
| 真服 | **三条事件必须在测试服实跑观察到真实到达**, 不接受只跑 GameTest (客户端渲染链 GameTest 覆盖不到) |
| 纪律 | diff 内零 Emoji; 中文 Conventional Commits; 无 AI 署名 |

---

## 八、明确不在 W12 范围

| 项 | 理由 |
|---|---|
| 离线事件队列 / 断线补发 | 见 5.1 第 3 条, 收益不抵复杂度 |
| 事件持久化 / 通知中心面板 | 那是收件箱 (清单 B13), 依赖决策 J7, 独立工程 |
| 广播类事件 (全服公告) | 现有门面是 `PacketDistributor.PLAYER` 定向下发; 广播要另定限流与权限 |
| 事件驱动替代轮询 | 本轮只做"提示有变化", 轮询不下线。红线 5.1 第 1 条 |
