# 游戏内 Web UI 架构（In-Game Web UI / Flea Market Frontend）

> 状态标记：DECIDED = 已拍板（落方向/将落代码）；DRAFT = 本文提议待确认；PENDING = 待实现期验证。
> 平台：Forge 1.20.1 / modid `miningdim` / Java 17 / parchment 映射。
> 前置真源：
> [服务器经济系统设计文档.md](服务器经济系统设计文档.md)（货币层/软上限）、
> [Ore_Pricing_Ledger_DesignSpec.md](Ore_Pricing_Ledger_DesignSpec.md)（铜/铁 P2P 只能在跳蚤市场消耗 = 本 UI 的旗舰需求）、
> [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md)（收支）。
> 外部基座：MCEF（CinemaMod Chromium 嵌入框架）`2.1.6-1.20.1`、用户旧作 Miracle-Bridge（评估见第三章）。
> 代码真源（将落地）：`client/webui/*`（客户端宿主+桥）、`market/*`（服务端跳蚤市场+SQLite）。

---

## 一、定位

游戏内玩家 UI 套件的技术地基。旗舰是**跳蚤市场**（玩家间 P2P 交易，铜/铁等材料矿的唯一消耗出口，见定价台账），后续复用同一地基扩展：系统商店、开箱、特勤面板、职业总菜单/HUD。

渲染走 **MCEF（把 Chromium 浏览器内嵌进 MC 客户端，渲染远端 React 应用）**，不走原生 MC Screen 手搓控件。理由：跳蚤市场是数据密集型交互（订单簿/搜索/排序/图表），HTML/CSS/React 写起来比原生控件轻一个数量级；且与已定的 Astro Wiki（见 [[wiki-architecture-decision]]）**共一套 web 技术栈/设计系统/真源数据**，一份前端代码同时喂 Wiki 与游戏内 UI。

---

## 二、决策定稿（DECIDED，本会话拍板）

1. **渲染基座 = MCEF 2.1.6-1.20.1**。已核实是 Forge 1.20.1 线最新稳定版（CinemaMod，2024-10-20），不升级。MCEF 作为**客户端必需前置 mod**（玩家装），我方 `compileOnly` 取 API + `ModList.isLoaded("mcef")` 守卫 + `Dist.CLIENT` 隔离。

2. **前端按美术资源走，不打包进 jar = 只走远端 `devServerUrl` 模式**。React 构建产物托管在远端站点，MCEF 直接加载远端 URL。更新 UI = 重部署网站，mod 一行不动（用户要的"后期更新简单"由此成立）。
   - 推论：旧作的 `EmbeddedWebServer`（JDK HttpServer，只为 serve jar 内 `/assets/.../web` 而存在）**整个丢弃**，连带消除其 localhost 端口 + `CORS:*` 攻击面。jar 内只剩 bridge 地基 + 一个指向远端的 config 值。

3. **服务端权威重写**：旧作的入站数据面（JS→Java）全是客户端本地回假数据、服务端往返链路是断的死代码（见第三章）。跳蚤市场必须新写一条干净的"客户端只发意图 → 服务端 SQLite 事务校验 → 回执"链路。服务端是交易状态的唯一写入方。

4. **跳蚤市场存储 = 单文件 SQLite**（`org.xerial:sqlite-jdbc`，经 Forge jarJar 内置）。单写者模型契合单 MC 服务端，WAL 模式，零运维。已评估对 ~80 玩家 + 30 天交易留存绰绰有余（数十 MB 量级）。

5. **中文输入 = 原生 EditBox 叠加注入**。MCEF 单 char API 无 CEF IME 桥，搜索/输价的中文走 MC 原生 `EditBox` 接收、提交后经客户端桥把字符串送进页面（不在浏览器里直接打中文）。完整 CEF IME 桥列后期可选项。

6. **零 mixin**。旧作 `miraclebridge.mixins.json` 的 mixins 数组为空——纯 Forge 事件 + MCEF API，无 TACZ 那种 SRG mixin 崩服风险（对比 [[external-mod-deps-gap]]）。我方并入同样零 mixin。

---

## 三、Miracle-Bridge 评估结论（好骨架，坏管线）

三组并行精读旧作全部 40 文件后定性。源码参考目录：`D:\Repo\_ref-miracle-bridge`（工程外，不入库）。

### 3.1 抢救（改造并入，质量过关）

| 模块 | 文件 | 价值 |
|---|---|---|
| MCEF 宿主 | `MiracleBrowser` | 浏览器封装 + 把 Chromium 贴图画进 GUI，用 1.20.1 正确的 RenderSystem 即时模式（`setShader`/`Tesselator`/`BufferUploader`），无旧 API 误用 |
| GUI 宿主 | `BrowserScreen` | Screen 宿主 + DPI/坐标换算三件套：按帧缓冲分辨率 `resize`、按 GUI scale 渲染、`toActual*` 映射鼠标回 CEF 像素。**跳蚤市场 GUI 的宿主基类** |
| 输入 | `InputHandler` | GLFW→CEF 按键/修饰键映射助手（需修右键中键映射反、接入被旧代码晾着的死分支） |
| 前端契约 | `bridge.js` | `window.MiracleBridge.call/callServer/on(...)` 的 RPC 客户端壳，改品牌后留 |
| 出站推送 | `S2CPushEventPacket` + `pushEvent` + JS `on` | 干净的服务端→客户端单向事件通知，适合交易状态广播 |
| 关联骨架 | `BridgeAPI` 的 requestId/`CompletableFuture` 映射、`C2SBridgeActionPacket`+`S2CBridgeResponsePacket` 的 requestId 回执范式 | 保结构，业务全重写 |
| 工具 | `GsonHelper` / `ThreadScheduler`（裁剪）/ `MessageHelper`（改前缀） | 直接用 |

### 3.2 丢弃

`entity/`+`ysm/`（YSM/LLM 实体 AI）、`BrowserOverlay`（Blue Archive 透明 HUD `#/hud/sidebar`）、`KeyBindings`（BA 全局键位）、`BridgeMessageQueue`（死轮询，Java 侧无驱动）、`EmbeddedWebServer`（远端模式不需要）、`config/` 的 Watcher/Validator/Reloader/ModConfigs/ServerConfig（过度工程半成品，与 ForgeConfigSpec 重复）、`C2SSendEventPacket`（零校验灌服务端总线）、`S2CFullSyncPacket` 的 `executeJs` 分支、全部假数据默认 handler、`BridgeEventBus` 手写 `escapeJsString` 拼 JS。

### 3.3 旧作的致命问题（重写要解决的，即用户说的"挺多问题"）

- **Critical 服务端权威断裂**：活着的入站路径（`bridge://` scheme + 内置 HTTP）全部只在客户端本地 `handleRequest` 回**进程内伪造数据**（`getPlayerInfo` 返回写死 health=20）。带服务端校验的 `requestFromServer → C2S → S2C` 链路设计在、无任何生产调用方 = 孤儿死代码。
- **Critical 远程执行面**：`S2CFullSyncPacket.executeJs` 允许服务端在客户端浏览器跑任意 JS；手写 JS 转义只覆 5 种字符（`</script>`/U+2028/U+2029 可破注入）= 展示玩家输入时的 XSS 面。
- **Major 三套并行传输 + 三个重叠 S2C 包 + 三条事件下行**，大量死代码与重复关联 id 体系（同步无 id / UUID / `req_` 字符串，互不通）。
- **Major 中文 IME 不可用**、渲染线程 vs 主线程贴图竞态（`browser` 非 volatile）、右键中键映射反、满屏吞异常、Dist 隔离不一致（部分 S2C 包服务端误收会 `NoClassDefFoundError`）、`NetworkRegistry.newSimpleChannel` 已废弃。
- **清理项**：`mods.toml` / build.gradle 带 ⚠️/✅/✓ emoji，并入时一律不抄（本工程硬禁 emoji）。

---

## 四、目标架构分层

```
远端托管 React 应用（与 Astro Wiki 共栈/共设计系统/共真源数据）
        | HTTPS 加载静态资源（devServerUrl）
        | JS<->Java：CEF message router（cefQuery）  ← 与页面来源无关，见 5.2
========|==================== 客户端进程 ====================
[client.webui 子系统]  Dist.CLIENT + ModList.isLoaded("mcef") 守卫
  - MCEF 宿主：MiracleBrowser（贴图）/ WebUiScreen（BrowserScreen 改）/ InputHandler
  - 客户端桥：BridgeRouter —— 收 cefQuery 请求，本地动作直接回，权威动作转 C2S
  - EditBox 叠加（中文输入）
        | C2S 请求（requestId）/ S2C 回执（requestId）/ S2C 事件推送
        | 接入现有 MiningNetwork 通道（避免多通道），ChannelBuilder 范式
========|==================== 服务端进程 ====================
[market 子系统]  com.miningdim.core.Subsystem.register(modBus, forgeBus)
  - MarketService（撮合/托管/手续费/留存），注入 MiningServices 定位器
  - SQLite（jarJar sqlite-jdbc，WAL）：listings / escrow / transactions
  - 复用 EconomyService（信用点/青辉石转账）、entry.MiningPlayerData（玩家权威数据）
```

子系统拆分铁律：**客户端 webui 全 `Dist.CLIENT` + `ModList` 守卫，服务端 GameTest 不加载它**——现有 243/243 GameTest 不受影响（见 [[jobs-implementation-state]]）。服务端 market 子系统的纯逻辑（撮合/托管/SQLite 事务）可 dev GameTest 全覆盖。

---

## 五、服务端权威数据面（重写核心）

### 5.1 请求-回执流（带 requestId 关联）

1. JS：`MiracleBridge.callServer('market.placeOrder', {itemRef, count, unitPrice, currency})`。
2. 客户端 BridgeRouter：判定为权威动作 → 生成 `requestId`(UUID) → 发 `C2SWebUiRequest(requestId, action, payloadJson)` → 登记 pending `CompletableFuture`，带超时。
3. 服务端 MarketService：**发送者身份 = `ctx.getSender()`（不可伪造卖家）** → 参数校验 → SQLite 单事务（BEGIN/COMMIT）执行状态转移 → 产出结果 JSON。
4. 服务端 → `S2CWebUiResponse(requestId, success, resultJson)`。
5. 客户端：按 `requestId` 取出 future → resolve 对应 JS Promise（经 router 回填，不用字符串拼 `executeJavaScript`）。
6. 实时联动（他人挂单/成交）：服务端 → `S2CWebUiEvent(eventName, dataJson)` → JS `MiracleBridge.on('market:update', ...)`。

### 5.2 JS→Java 传输（DECIDED，已 javap 核实）

**CEF message router（`window.cefQuery`）= 唯一入站通道。** MCEF 2.1.6 jar 已确认捆绑 JCEF 的 `org.cef.browser.CefMessageRouter` + `org.cef.callback.CefQueryCallback` + `org.cef.handler.CefMessageRouterHandlerAdapter`。
- 注册：`MCEF.getClient().getHandle().addMessageRouter(CefMessageRouter.create(config, handler))`（`getHandle()` 返回 `org.cef.CefClient`）。默认 JS 函数名 `cefQuery`，可经 `CefMessageRouterConfig` 改品牌。
- JS 侧：`window.cefQuery({request: JSON.stringify({action,requestId,payload}), onSuccess, onFailure})`。
- 处理：`handler.onQuery(browser, frame, queryId, request, persistent, CefQueryCallback callback)`。**回调可异步持有**——这正是优雅解掉旧 scheme handler 同步/异步矛盾的关键：本地动作当场 `callback.success(json)`；权威动作则持住 callback → 发 C2S → S2C 回来再 `callback.success(json)`，服务端往返天然支持，无需阻塞 CEF 线程。
- 与页面来源无关（router 注入所有 frame），远端 https 页面照常可调，彻底取代旧作的 scheme + HTTP + 死轮询三套传输。
- 旧作的 `bridge://` scheme 与 `EmbeddedWebServer` 一并弃用。

### 5.3 服务端权威红线

- 客户端只发**意图**，绝不写权威状态。卖家身份取 `ctx.getSender()`，不信前端传的 uuid。
- 资金/库存校验、扣款托管、成交转移全在服务端单事务内；前端拿到的余额/库存仅供显示。
- 无 `executeJs` 原语。服务端→客户端只推**结构化数据**，由页面安全解析（React 默认转义文本，物品名/留言不经字符串拼 JS）。
- 每个 C2S 请求的 `requestId` 服务端去重，防重复提交/重放。

---

## 六、跳蚤市场后端（v1 已落地 2026-06-19，DRAFT 数值待标定）

> 落地状态：`com.miningdim.market`（17 类）+ 6 个 `market.*` action + 252/252 GameTest 全绿（6 项真连 SQLite）。托管物品折叠进 listings 行的 `item_nbt` BLOB（v1 不单列 escrow 表）。手续费已定稿为**挂单时收取**的 `FEE_RATE=0.20`（平价基础费率）+ 偏离费二次系数 `DEVIATION_K=0.04`（高税重摩擦市场，强反通胀/反洗钱；真源 `market.MarketConstants`/`market.MarketFee`，非早期 0.05 成交额比例）；铜铁日 P2P cap `COPPER_IRON_DAILY_P2P_CAP=512` 仍为 DRAFT 待标定。崩溃原子性**已于 2026-08-12 闭合**：钱包与市场表并入统一库 `miningdim.db`，买家扣款 / 挂单状态 / 流水 / 卖家收款收进单个事务（`IEconomyService.inTransaction`）。此前该窗口被描述为「极小」并不准确 —— 钱当时在 SavedData，最长 5 分钟才落一次盘（`MinecraftServer.tickServer` 每 6000 tick 触发 `saveEverything`），而 SQLite 提交即落盘。仍未闭合的是**物品交付**：背包是无法并入事务的第三个存储，交付放在提交之后，最坏情况是「钱货两清但物品没进包」，需邮箱式领取才能真正闭合。30 天流水清理、偏离/反洗钱价格校验 deferred。

### 6.1 表结构草案

- `listings`：id / seller_uuid / item_ref(注册名+序列化 NBT 快照) / count / unit_price / currency / created_at / status(ACTIVE/SOLD/CANCELLED/EXPIRED) / expire_at。
- `escrow`：挂单瞬间把卖品从卖家库存移入服务端托管（NBT 快照 + 引用），成交/撤单/过期再出托管。**托管 = 不能"挂着卖还揣兜里"的关键**。
- `transactions`：id / listing_id / buyer_uuid / seller_uuid / item 快照 / count / unit_price / total / fee / created_at。**留存 30 天后定期清理**（审计 + 反洗钱观察窗）。

### 6.2 撮合/成交不变量

- 挂单：校验卖家持有 → 移入 escrow → 写 ACTIVE listing。失败自然冒泡，不吞。
- 买入：校验买家资金（EconomyService）→ 单事务内 listing ACTIVE→SOLD + 扣买家 + 卖家收款（在线即时入账／离线落 `pending_payout`）→ COMMIT → 提交后再交付物品。任一步失败整事务回滚。**交付刻意在事务之外**：玩家背包是第三个存储，放在提交前意味着回滚即白送；放在提交后，剩下的缺口是「钱货两清但物品没进包」，那要靠邮箱式领取而非事务来闭合。
- 手续费**在挂单时一次性收取并蒸发**（sink，非成交额比例）：`fee = round(max(V0,VR)*count*(FEE_RATE + DEVIATION_K*ln(VR/V0)^2))`（V0=可信基准价、VR=挂单价；诚实按基准价挂单退化为 0.20 平价费，偏离越大二次惩罚越重，兼作反洗钱）。买入端为纯转移、不再额外收费。真源 `market.MarketConstants`/`market.MarketFee`。
- 铜/铁 P2P 单人 cap：定价台账第三章遗留的"铜 P2P 单人 cap"在此层落地（按买家/卖家日成交量门控）。

### 6.3 与货币层接线

复用 `EconomyService`（信用点 CREDIT / 青辉石 AZURE）。跳蚤市场是**纯转移**（买家→卖家），不是 faucet，**不并入 credit_faucet 衰减主闸**（那是 PVE 印钞口的事）；但手续费是 sink，反向有助控通胀。

---

## 七、中文输入方案（DECIDED 方向，DRAFT 实现）

MC 原生 `EditBox` 浮在 WebUiScreen 上接收键盘（含中文 IME，因为走 MC 自己的文本输入路径），玩家敲完按确认 → 客户端桥把 committed 字符串经 router 回填进页面对应输入框（页面暴露一个 `setFieldValue(fieldId, text)` 入口）。规避 MCEF 单 char/无 IME 的坑。搜索框、输价、留言均走此路。

---

## 八、安全红线（集中）

1. 服务端权威：客户端只发意图，服务端唯一写入方（第五章）。
2. 身份不可伪造：`ctx.getSender()` 定卖家/买家。
3. 无远程 JS 执行：删 `executeJs`，服务端只推结构化数据。
4. 输入即不可信：玩家物品名/留言等渲染为文本（React 转义），严禁经字符串拼 `executeJavaScript`。
5. 无 localhost 端口：远端模式不起 `EmbeddedWebServer`，消除本机 HTTP 攻击面。
6. requestId 去重防重放；交易事务原子，失败回滚不留半成品。

---

## 九、与现有架构接线

- 子系统范式：客户端 `com.miningdim.client.webui` + 服务端 `com.miningdim.market`，各 `Subsystem.register(modBus, forgeBus)`、各自管 DeferredRegister、门面注入 `MiningServices`、在 `MiningDim.registerSubsystems()` 接线。
- 网络：接入现有 `MiningNetwork.CHANNEL`（新增 `C2SWebUiRequest`/`S2CWebUiResponse`/`S2CWebUiEvent` 三包），**照搬现有 `register()` 的 `registerMessage(nextId(), ...)` 追加范式**（与既有职业包 `JobSyncS2C` 同序追加，不另起通道——一致性优先；现有工程刻意用 `NetworkRegistry.newSimpleChannel` 并注释为"1.20.1 正确 API"，故第三章关于迁 `ChannelBuilder` 的建议在本工程不采纳）。发包带现有 `canReceive` 守卫。
- 构建：`compileOnly files("libs/mcef-forge-2.1.6-1.20.1.jar")`（同 TACZ/Champions 范式，见 [[external-mod-deps-gap]]）。SQLite 驱动 `org.xerial:sqlite-jdbc` 的 **三连坑**（嵌任何 JDBC/纯 java 库到 Forge 1.20.1 dev 运行期必踩）：(1) `jarJar` 内嵌进产物 jar 的 `META-INF/jarjar`（生产，需 `jarJar.enable()` 先调）；(2) dev run classpath 必须用 FG6 的 `minecraftLibrary` 配置——`runtimeOnly`/`implementation` 都进不了 `build/classpath/run*_minecraftClasspath.txt`，否则 GameTest 抛 `No suitable driver found`；(3) 还需显式 `Class.forName("org.sqlite.JDBC")`——FML 模块层 ServiceLoader 在 boot 层早跑一次、game 层 jar 没赶上，不显式注册即便在 classpath 也找不到驱动。gradle 必须 JDK17（改 build.gradle 才暴露，见 [[build-toolchain]]）。
- 前端共栈：React 应用与 Astro Wiki 共设计系统/组件/真源 JSON，托管在同一基础设施（见 [[wiki-architecture-decision]]）。

---

## 十、落地顺序

1. **bridge 地基**：抢救 MCEF 宿主三件套（MiracleBrowser/WebUiScreen/InputHandler）+ 客户端 BridgeRouter（cefQuery）+ MiningNetwork 三包 + 一个 "hello world" UI，跑通**一次真服务端往返**（证明权威链路通、requestId 关联对、Dist 隔离不崩 GameTest）。
2. **跳蚤市场**（旗舰）：market 子系统 + SQLite + 撮合/托管/手续费/留存 + 对应 React 页面（订单簿/搜索/挂单/买入）+ EditBox 中文输入。
3. **复用扩展**：系统商店、开箱、特勤面板、职业总菜单/HUD——同宿主+桥，各加一个服务端子系统。

---

## 十一、遗留验证项（PENDING，真服/实现期验）

- ~~MCEF 2.1.6 是否暴露 CEF message router（`cefQuery`）API~~：已 javap 核实暴露，见第 5.2（RESOLVED 2026-06-19）。
- MCEF dev 环境首启下 Chromium native（`MCEF.scheduleForInit`）行为：纯客户端，服务端 GameTest 不触；客户端 runClient 真测。
- 远端 https 页面 + `bridge://`/cefQuery 的 CORS/混合内容实际表现：真客户端验。
- 手续费率、铜/铁 P2P 单人 cap 具体数值：标定（DRAFT）。
- 离线成交交付（邮箱/暂存）落点：设计（DRAFT）。
