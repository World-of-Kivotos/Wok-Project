# 游戏内 Web UI 前端接线清单与实现草案

> ## 视觉部分的修订说明（2026-08-13）
>
> 本文的**接线内容全部仍然有效**——要接哪些 action、缺口在哪、按什么顺序接，与视觉风格正交，
> 一条都没变。但涉及**视觉与组件命名**的部分已经过时，读到时按下表换算：
>
> | 本文写的 | 现在的真源 |
> | --- | --- |
> | 第二章 L0/L1/L2 的 `Pixel*` 组件表 | `webui/src/components/kit/README.md`（签名表）。旧实现封存在 `webui/_pixel-archive/` |
> | 第三章"美术资产缺口"里的 9-slice 边框、16×16 图标、点阵中文字体 | 均已不需要。功能图标走 lucide-react，字体走系统栈 |
> | 第六章"批 1 · 像素单点验证" | 已作废。该批要验的 `devicePixelRatio` × GUI Scale 像素对齐问题只在像素风下存在 |
> | 决策 J1 的兜底"一律像素占位块" | 改为中性棋盘格占位块（`ItemIcon` 第三层回退），语义不变 |
>
> 视觉风格已整体改为**圆角中性灰阶 + 单一可调强调色**（Coss UI + Tailwind v4），
> 像素风推迟（`PixelUI_DesignSystem_DesignSpec.md` 全文标 DEFERRED，含上一轮的实测教训）。
> 换风格不影响本文任何一条接线判定。

> 状态标记：DECIDED = 已拍板；DRAFT = 本文提议待确认；BLOCKED = 前端开工前必须先解决。
> 前置真源：
> [WebUI_Architecture_DesignSpec.md](WebUI_Architecture_DesignSpec.md)（数据地基：MCEF 宿主、cefQuery 桥、服务端权威）、
> [PixelUI_DesignSystem_DesignSpec.md](PixelUI_DesignSystem_DesignSpec.md)（视觉地基，**当前 DEFERRED**）。
> 本文边界：**只管"前端要接多少东西、缺口在哪、按什么顺序接"**。桥怎么通、画面怎么长，一律见前置真源。
> 盘点口径：2026-08-12 对 `src/main/java`（583 文件）+ `d:\Repo\WOK-ChestShop` + 31 份 docs 做十切片并行盘点 + 一轮完整性批判，共得 207 条玩家可见面。桥接层与 market 契约的结论为主控逐行读码复核，其余为切片盘点结论并附 Java 落点可自查。

---

## 零、一句话结论

**后端不是"差几个 action"，是差三层：**

| 层 | 规模 | 性质 |
|---|---|---|
| 已能直接用 | 13 个服务端 action + 1 个客户端本地 action | 全部集中在市场 + 钱包 + 背包；其中 `market.history` 形状在但恒返回空数组 |
| 只差 JSON 薄封装（服务端逻辑齐全） | 约 40 条（部分条目含多个子动作，实际 handler 数更高） | 纯体力活，是 Codex 的主战场 |
| 数据在私有字段/无聚合/无索引，要写真业务代码 | 约 34 处 | 必须人工设计，不能外包 |
| 文档有设计、代码零实现的整块系统 | 10 块 | 前端只能先不做或留占位 |

换算下来：**当前已就绪的后端只覆盖前端总需求的约 15%**，且这 15% 全在跳蚤市场一个模块里。

**两条最致命的**（不解决前端连跑都跑不起来，与业务面板无关）：

1. `S2CWebUiEvent` 全库**零业务调用方** —— 推送面是空管道，架构文档 5.1 第 6 条要的市场实时联动完全没有发送端。
2. **物品图标的取图路径未定** —— 旧前端只会 fetch 第三方原版贴图镜像站 `assets.mcasset.cloud`，那上面只有 Minecraft 原版资源。本 mod 自己的 246 张 item + 57 张 block 贴图虽在本仓库内（同 monorepo，构建期 copy 即可），**第三方 mod（TACZ 枪械、Champions、farmersdelight 等）的贴图既不在本仓库也不在该镜像站**，而市场分类树枚举的是 `ForgeRegistries.ITEMS` 全量。详见 A13/J1。

---

## 一、UI 信息架构草稿（DRAFT）

统一入口走平板 hub（见 `unified-ui-entry-plan` 的既定方向）：一个物品/键位打开平板，全部功能面板经平板进入，不给每个功能接 ad-hoc 独立入口。

```
平板 hub
├─ 首页 · 个人档案        [聚合] 8 职业进度 / 双货币 / 今日收益额度 / 婚姻 / 所在矿洞
├─ 跳蚤市场              [旗舰] 浏览 · 分类 · 挂单 · 我的挂单 · 成交历史 · 收件箱
├─ 系统商店              [跨仓] ChestShop 目录 · 比价
├─ 职业                  8 个并列子页（非单选器，全职业被动恒生效）
│   ├─ 矿工   探测触发 / 连锁充能 / 被动数值 / 当日矿物软上限
│   ├─ 农夫   卖菜 / 收购曲线预览 / 耕地五档
│   ├─ 厨师   品质上限 / 效果数值表 / 调味台花费
│   ├─ 酿酒师 9 酒永久层数 / 月光词条 / 配方表 / 酒窖陈酿
│   ├─ 塔罗   卡组 / CD / 卡包 / 合成 / 碎片兑换
│   ├─ 特勤   战术扫描 / 封印 / 悬赏板 / 五支线数值
│   ├─ 军火商 军火台 / 冲压机 / 装配台 / 图纸百科
│   └─ 工程师 纳米生产台 / 档位表 / 护甲特效
├─ 矿洞                  三难度总览 / 进入 · 离开 / 等级门 / 重置倒计时 / danger
├─ 精英怪图鉴            35 词条表 / 10 星级表 / 难度分布 / 参团贡献
├─ 婚姻                  状态 / 求婚收发 / 典礼 / 离婚 / 共享背包 / 传送
├─ 设置                  UI 偏好（缩放/免打扰/布局）
└─ 管理后台（OP）        经济调账 / 基准价 curate / 职业调级 / 副本重置 / 服务器状态
```

**刻意不进 MCEF 的**（保留原生渲染，搬进来只增延迟无收益）：BOSS 血条、命定之死倒计时、反击单元锁定窗、致盲、实体堆叠头顶计数、danger 屏幕压暗、共享背包/各类工作台的**物品取放**（走 vanilla Container 协议）。

---

## 二、组件库草稿（DRAFT）

### L0 地基（3 个，不外包，是全部组件的地基）

| 组件 | 关键实现点 |
|---|---|
| PixelFrame | 唯一 9-slice 容器原语。`border-image-slice: N fill` + `border-image-repeat: round`；variant = window / panel / inset 三档层级各一份资产；`border-width` 必须取 slice 的整数倍 |
| PixelIcon | `mask-image` + `background-color: currentColor` 单色蒙版，`image-rendering: pixelated` |
| ItemIcon | 回退链 item -> block -> 模型 parent 链解析 -> **像素占位块**（旧实现回退到 lucide 矢量图标，与规格互斥，必须改） |

### L1 控件（15 个，全部基于 PixelFrame，适合外包）

PixelButton / PixelInput（点击唤起 MC EditBox 叠加）/ PixelSlot / PixelSlotGrid / PixelTabs / PixelTable / PixelScrollArea（原生滚动条是圆角矢量，必须自绘）/ PixelProgress / PixelBadge / PixelTooltip / PixelModal / PixelToast / PixelSelect / PixelStepper / PixelCurrency

### L2 状态件（4 个）

PixelLoading / PixelEmpty / PixelError / PixelConfirmDanger

### 资产缺口（美术侧，当前 0 张）

全库 320 个贴图逐目录核对完毕：**零一张按 9-slice 设计的可拉伸边框、面板底图或按钮图**。现有 GUI 贴图（15 个）全是绑定固定槽位 `blit()` 的一次性整屏底图（vanilla 惯例 176x166，或自定义 3 倍超采样 1080x720），不可拉伸复用。

| 资产 | 数量 | 规格 |
|---|---|---|
| 9-slice 边框 | 3 张（window/panel/inset） | 16x16 或 24x24，1x 密度，灰度带 alpha，严禁美术端预放大 |
| 功能图标 | 约 20 个 | 16x16 单色蒙版 |
| 点阵中文字体 | 1 套 | Fusion Pixel / Zpix，授权与覆盖待核（规格第九章 PENDING） |

**可直接复用的现成资产**：塔罗 22 张 16x16 物品图标 + 5 张稀有度边框、塔罗 22 张高清源图（461x817，做卡面大图）、枪匠部件 190 张 64x64、佳酿 9 张 64x64、纳米板 6 张 16x16、易伤效果图标 1 张 28x28。

---

## 三、接线总表

图例：`READY` = 已有 action 可直接用；`WRAP` = 服务端逻辑齐全只差 JSON 薄封装；`BACKEND` = 数据在私有字段/无聚合/无索引，要写真业务代码；`NONE` = 文档有设计代码零实现。

### A 组 · 地基（不属于任何面板，缺一个前端就跑不起来）

| # | action | 状态 | Java 落点 | 说明 |
|---|---|---|---|---|
| A1 | `system.echo` | READY | `WebUiServerSubsystem` | 联调回声 |
| A2 | `client.i18n` | READY | `WebUiBridge:106` | 客户端本地解翻译键，**仅覆盖客户端已加载的 lang** |
| A3 | `system.handshake` | BACKEND | `WebUiServerDispatcher:33`（ACTIONS 表无自省接口） | 回 `{serverVersion, actions:[]}`，前端启动比对。路线 A 下浏览器缓存旧页面调已删 action 会静默失败，架构文档 10.6 立案未做 |
| A4 | `system.serverStatus` | WRAP | `MinecraftServer` 公开 API | 在线人数/TPS/公告，hub 首页与管理后台都要 |
| A5 | `player.profile` | BACKEND | `IMiningPlayerData:54,70,85,104`（指针分散，无聚合读取点） | 首屏聚合。不做的话 MCEF 首屏要串行 6+ 次往返 |
| A6 | `player.isOp` | WRAP | `MarketAdminActions:137 requireOp`（仅动作内校验无查询） | 决定是否渲染管理页签；服务端逐动作校验不放松 |
| A7 | `player.inventory` | READY | `PlayerWebUiActions:47` | 只回主背包 36 槽，改名物品附 displayName |
| A8 | `player.itemDetail` | WRAP | `GunsmithGunStats.from` / `NanoNbt` / 塔罗 NBT | 按 slot 解析 NBT。现有 inventory 只回 slot/itemId/count，枪械属性、纳米特效、塔罗牌品质全拿不到 |
| A9 | `player.prefs` | BACKEND | `MiningPlayerData`（capability 内无 UI 偏好字段） | MCEF 是远端页面，localStorage 随 Chromium 缓存清理即丢，换机器不跟随 |
| A10 | 错误码中文化 | BACKEND | `WebUiServerDispatcher:107-113` | 现在回给前端的是 Java 异常原文（中英混杂，部分是内部描述），不可直接展示给玩家。需约定 `errorCode` + 参数由前端本地化 |
| A11 | 服务端主动推送 | BACKEND | `MiningNetwork:142 sendWebUiEvent`（**全库零业务调用方**） | 事件面是空管道。无推送则一切靠轮询 |
| A12 | 物品中文名 | WRAP | `MarketActions:60`/`PlayerWebUiActions:47` 只回 itemId | **不是来源问题**：`client.i18n` 走客户端 `I18n.get()`，玩家客户端已加载全部模组 lang，中文名本就能解。缺的是 `market.list`/`player.inventory` 响应里没有 `descriptionId`（翻译键），前端从 itemId 推不出（物品是 `item.ns.x`、方块是 `block.ns.x`）。`admin.listItems` 已在回该字段，照抄即可 |
| A13 | 物品图标取图路径 | 分三段，见 J1 | `PixelUI 规格:181` 写的 `/v1/item-icon` 零代码 | (1) 原版物品：旧前端已实现四级回退链打第三方镜像站 `assets.mcasset.cloud`，可用但引入外部服务依赖；(2) **本 mod 自己的 246 item + 57 block 贴图：就在本仓库 `src/main/resources/assets/miningdim/textures/` 下，前端同 monorepo，构建期 copy 即可，非阻塞**；(3) 第三方 mod（TACZ/Champions/farmersdelight）贴图：既不在本仓库也不在镜像站，是唯一真缺口 |
| A14 | 中文输入 EditBox 叠加 | BLOCKED | `WebUiScreen:24-26,178`（自标 step2 接口位） | 受影响：市场搜索框、admin 物品过滤框、按玩家名找人（求婚/调账）。纯数字框不受影响，可先放行数字面板 |
| A15 | 前端加载入口 | BLOCKED | `WebUiClient:95` 硬编码 `data:` URI 开发页 | **无 devServerUrl config、无 openUrl(route)**，前端页面目前根本没有被加载的途径。0 号阻塞项 |
| A16 | 玩家名/UUID 解析与在线状态 | BACKEND | 全库零 `GameProfileCache` 用法 | 求婚选人、OP 调账选目标、看配偶在线、成交历史显示对手名都要。`sellerName` 是挂单瞬间快照，会随改名过期 |
| A17 | 平板 hub 面板目录 | NONE | `WebUiClientSubsystem:49-52`（唯一入口是调试命令） | 无平板物品、无键位、无面板注册表。前端需要一个"我能进哪些面板"的数据源（受职业等级/OP/婚姻门控） |

### B 组 · 跳蚤市场（唯一已真实接线的模块）

| # | action | 状态 | 说明 |
|---|---|---|---|
| B1 | `market.list` | READY(有缺陷) | **不返回 total**，前端无法算总页数（`admin.listItems` 已有 total 可照抄）。分页参数无上限钳制 |
| B2 | `market.place` | READY | 回执含 listingId + listFee；手续费上单即收（蒸发 sink），撤单不退 |
| B3 | `market.buy` | READY | count 缺省 0 = 买整单；fee 回执恒 0；卖家离线落 pending_payout |
| B4 | `market.cancel` | READY | 手续费不退；背包满时拒绝且不标 CANCELLED |
| B5 | `market.mine` | READY | 服务端权威取 sender 自己的 ACTIVE |
| B6 | `market.history` | BACKEND | **恒回空数组**。transactions 表 + 双索引 + insertTxn 全就绪，唯独 `MarketDao` 契约缺 `transactionsByPlayer(UUID,offset,limit)` |
| B7 | `market.baseValue` | READY | 分层回 source：override / preset（仅钻/金锭/残骸/小麦 4 项）/ none |
| B8 | `market.categories` | READY(契约已变更，前端未跟进) | **F041 修复 (fix/market-payload) 起，回执形状不再是含叶子的树** —— 只出六个固定顶层 + ores 三个固定子分类，每个分支节点带 `leafCount`，`children` 恒存在但**不再保证非空**（原因：全量叶子超 `FriendlyByteBuf.MAX_STRING_LENGTH=32767` 字符硬闸，真服必炸，见 F041）。旧契约 `webui/src/lib/types.ts` 的 `CategoryBranchNode` 注释「恒有 children 且非空」与此矛盾，须作废；旧契约也无 `leafCount` 字段。叶子改由新 action `market.categoryItems` 按需分页取，见 B8b。前端未接入前，左栏分类展开后是空目录，等同 F041 玩家侧症状未消失，只是从报错变成空目录——**必须与前端配套改动同批次上线，不能单独放行** |
| B8b | `market.categoryItems`（新增，本清单原未登记） | READY(前端零接入) | `{categoryId,page?,pageSize?} -> {categoryId,items:[{id,label,itemId}],page,pageSize,total}`。`categoryId` 必须是 `market.categories` 骨架里出现过的顶层/子分类 id，未知 id 拒绝。分页口径同 `market.list`（`page`/`pageSize` 缺省 0/20，钳制上限 100）。前端要接入需三件事：(1) `webui/src/lib/actions.ts` 的 `SERVER_ACTIONS` 登记本 action，`types.ts` 补返回类型；(2) `CategoryTree`/`BrowsePage.tsx` 改成点开分支节点时懒加载调用本 action 取叶子，而不是假设骨架自带叶子；(3) 同步改掉 `bridge.mock.ts` 里 `market.categories` 的 mock 实现（当前仍回旧的含叶子小树），否则 dev 假桥与真桥形状分叉 |
| B9 | `market.feePreview` | WRAP | `MarketFee:35-46` 纯函数已就绪且被 place 内部调用，只差单独暴露 —— 否则玩家提交后才知道扣了多少费 |
| B10 | `market.p2pCap` | WRAP | `MarketConstants:51` cap=512/日 + DAO 聚合方法均已就绪且在 place 内部做拒挂判定，只差包成"已用/剩余"查询 |
| B11 | `market.pendingPayout` | BACKEND | `drainPendingPayout` 是登录时"取即删"的破坏性方法，无只读 peek。DAO 签名固定须新增而非改签名 |
| B12 | `market.tradable` | BACKEND | 塔罗禁交易、青辉石绑定、婚戒/绑定装备等约束在设计里存在，挂单路径未做标的过滤，前端也拿不到判定 —— 玩家会先托管再报错 |
| B13 | 收件箱（离线成交交付） | NONE | 架构文档 257 行仍是 DRAFT，无落点 |
| B14 | 挂单过期 | NONE | 规格 6.1 写了 `expire_at`，`ListingRow` record **无该列**，整体未落地 |

**旧前端 `types.ts` 与 Java 实际返回的 6 处错配（新建时必须按 Java 为准）**：`ListResp.total` 后端不返回、`Listing.expireAt` 后端无此列、`Listing.sellerCredit` 不存在、`ListSort='time'` 与后端默认值 `'created_at'` **都不在 DAO 白名单**（白名单只认 `newest`/`price_asc`/`price_desc`，两者均静默落回 newest）、`market.history` 恒空。

### C 组 · 职业（8 个，全部零接线）

| # | action | 状态 | 说明 |
|---|---|---|---|
| C1 | `job.progress` | WRAP | 8 职业 level/xp/dailyXp/dailyRemaining/nextLevelXp 一次拿回。`IJobService.progress` 已给全字段，只经 `/job list` 聊天文本暴露 |
| C2 | 职业列表口径 | READY | **代码 8 个**（JobId 含 AGENT/MUNITIONS/BREWER），设计文档 2.1 节仍写 5 个，前端按代码走 |
| C3 | 无转职/无解锁 | READY | DECIDED 设计：全职业被动恒生效，不存在"当前激活职业"。前端做 8 条并列进度，**不做单选器** |
| C4 | 经验实时性 | BACKEND | `syncTo()` 全库只有 2 个调用点（登录、OP `/job set`），`grantXp` 主路径不触发同步。**现有 JobSyncS2C 镜像在一局内会持续陈旧，不能作实时数据源** —— 前端只能轮询，或后端补节流同步 |
| C5 | `job.miner.state` | WRAP | 被动数值纯函数 + `MinerChargeState` 充能/CD/开关已可直接读 |
| C6 | `job.miner.scan` | WRAP | 服务端等级门/CD/半径裁决齐全，webui 版把命中坐标 JSON 化即可，**必须保留同等防 X 光限制**（单矿种一次 + 有限半径 + 脉冲熄灭） |
| C7 | 矿工当日矿物软上限进度 | BACKEND | `PlayerAbuseState` 有 public getter 但只能经 `EconomySystem.playerState(UUID)` 拿，`IEconomyService` 接口无对应方法；且该态 save/load 零调用方，重启即清零 |
| C8 | `job.farmer.sell` + `.state` | WRAP | `/farmer sell` 服务端先扣后发 + 收购曲线 + faucet 主闸全就绪；今日已售/耕地五档均已持久化可查 |
| C9 | `job.chef.state` | WRAP | 品质上限纯函数 + `ChefConfig` 效果表。**数值走 ForgeConfigSpec 运营可调，前端必须实时读而非抄静态副本** |
| C10 | 厨师做菜小游戏 | NONE(webui) | 火候条 + 调味台走原生 Container GUI，搬进 MCEF 要整套重做交互层 |
| C11 | `job.brewer.state` | WRAP | 9 酒永久层数 + 月光 8 选 5 词条 + 配方表全已持久化。配方表是最容易接的一条 |
| C12 | 酒窖陈酿/酿酒台进度 | BACKEND | 状态挂方块位置而非玩家，`progress/pendingType` 无公开 getter，远程查看需额外设计索引 |
| C13 | 酿酒师卖酒 | NONE | 与矿工/农夫不同，**酿酒师没有 NPC 收购 faucet**，变现只能走 market 卖给玩家 |
| C14 | `job.tarot.*` | WRAP + BACKEND | 等级/品质门/碎片兑换/同意窗可薄封装；**战斗窗口聚合快照与 CD 只读 peek 都没有**（`tryUse` 是校验并占用的写方法），需新增只读方法 |
| C15 | 塔罗卡包购买与限购 | BACKEND | 卡包是信用点主力 sink，`spentToday` 是私有计数无 getter，玩家看不到"今天还能买几包" |
| C16 | `job.agent.scan` | BLOCKED | menu/S2C/C2S 三件套齐全，但 `AgentSealSeam.buildScanSnapshot` 与 `AgentScanMenu.Provider` **全工程零调用点** —— 探测脉冲无任何触发入口。本切片最大缺口 |
| C17 | `job.agent.seal` | WRAP | SealOutcome 九态服务端裁决齐全，按 targetNetworkId+affixId 直转调 |
| C18 | 特勤悬赏板 | BACKEND | 周期/目标类型骨架就绪，但模板库与持久化序列化未实现（`AgentBountySavedData` 类注释自标遗留待办），暂无可读数据源 |
| C19 | `job.munitions.*` | WRAP | 军火台/冲压机/装配台均是 ContainerData 驱动的成熟 menu，数据权威完整，按 blockPos + 按钮 id 薄封装 |
| C20 | 枪械图纸百科 | WRAP | `GunsmithBlueprint` 枚举（9 款枪 + requiredParts），玩家最常查的静态表 |
| C21 | `job.engineer.*` | WRAP + 待决策 | 档位表/护甲特效可封装；**纳米校准 QTE 游标是每 tick 变化的时序权威值**，搬进 MCEF 需先决策网络延迟对判定手感的影响 |
| C22 | 电力/线缆 | NONE(本分支) | `com.miningdim.power` 包在当前 checkout 完全不存在，代码在未合并分支 `feat/generator-shell`；且 12 级里仅 IRON/COPPER 两级真实注册，其余数值 PENDING，现在展示是假数据 |

### D 组 · 经济

| # | action | 状态 | 说明 |
|---|---|---|---|
| D1 | `player.wallet` | READY | 双货币余额 |
| D2 | `economy.status` | WRAP(部分) | `isAfkFrozen` 接口方法已就绪，包个布尔即可 |
| D3 | 信用点 faucet 当日衰减进度 | BACKEND | `dailyFaucets` 是 **private Map 且无任何 public 读取方法**，玩家最想看的"还差多少撞 0.6 衰减下一档"连最基础查询都要新写 |
| D4 | 青辉石每日硬上限剩余 | BACKEND | 同上私有 map 问题。硬截断 30/日，玩家只能打完发现 0 才知道撞顶 |
| D5 | `economy.priceTable` | WRAP | `ShopPriceTable:36-42` 锚价是静态常量。挖矿是最大 faucet 且价格随当日产量递减，玩家却无处查 |
| D6 | 今日全口径收益汇总 | BACKEND | 要把挖矿/卖菜/精英分赃/悬赏各 faucetKey 当日进度并排显示，需按 faucetKey 遍历私有 map 的只读接口，且必须与收支总表口径一致 |
| D7 | 货币层流水 | BACKEND | 与 market 的 transactions 表不同，**faucet 发放/sink 扣费/OP 调账完全零流水记录**，只有 4 条生命周期日志 |
| D8 | 全服 M0 总量 | BACKEND | `wallets` 私有 Map，唯一遍历点是 `save()`，无任何聚合 getter |

### E 组 · 婚姻（命令层齐全，零 webui）

| # | action | 状态 | 说明 |
|---|---|---|---|
| E1 | `marriage.state` | WRAP | 状态/配偶/婚龄/再婚冷却/离婚次数/共享背包等级格数/里程碑 —— 全部查询方法已就绪，聚合成一个 action |
| E2 | `marriage.buyring` / `propose` / `accept` / `wed` / `divorce` | WRAP x5 | 全部走现成 Engine 方法，失败原因枚举（wed 六态、divorce 四态）需映射前端文案 |
| E3 | 谁向我求婚 | BACKEND | `MarriageProposals` 只有 byProposer 单向表，**无反查索引**，需新增反查或 O(n) 扫描 |
| E4 | 求婚实时通知 | BACKEND | 对方只收聊天栏消息，无 S2C 事件推送（撞 A11） |
| E5 | `marriage.sharedInv` | WRAP | 仿 `PlayerWebUiActions.INVENTORY` 逐槽转 JSON；白名单已在容器层强制，前端只读展示无需重复校验 |
| E6 | 打开共享背包窗口 | BACKEND | 现仅"蹲下右键戒指"触发（private 方法），需提为可调用入口；**取放本身仍走 vanilla menu，不建议改造成 WebUI 拖拽** |
| E7 | 传送蓄力进度 / 冷却 | BACKEND x2 | 进度只有每秒 actionbar 文本无结构化暴露；`cooldownUntil` 是私有 Map 无 getter |

### F 组 · 矿洞

| # | action | 状态 | 说明 |
|---|---|---|---|
| F1 | `mining.overview` | WRAP | **R1 模型：全服仅 3 个常驻共享固定实例（每难度 1 个），不是私有副本** —— 这是 UI 设计的前提认知偏差点 |
| F2 | `mining.myStatus` | WRAP | `regionAt(x,z)` + 维度校验，纯读操作 |
| F3 | `mining.enter` | WRAP(有陷阱) | **进入有三条不一致路径**：只有 entrance 方块交互走完整 gateCheck + teleport；`/mining enter` 命令与 SelectZoneC2S 包都跳过 gateCheck 且**从不实际传送玩家**。新增 action 必须复用 `EntryGateway.requestEnter` 权威路径 |
| F4 | `mining.leave` | WRAP | 委派 `EntrySystem.leaveToFallback` |
| F5 | `mining.gate` | WRAP | `MinerLevelGate.minLevelFor`。注意 `GateResult` 头注释里 MEDIUM=10/HARD=25 是**过期文档口径**，代码权威是 L4/L8 |
| F6 | danger 实时值 | WRAP | 已按周期推送原生 S2C 包，但走的不是 webui 通道，需桥接同一份数据 |
| F7 | 自动重置倒计时 | BACKEND | 倒计时只活在 `AutoResetScheduler` 私有内存字段，仅经聊天广播；退而求其次可用 `lastReset + autoResetHours` 推算 |
| F8 | 新手保护倒计时 | BACKEND | `spawnFreezeUntil` 在运行态但无任何 S2C 告知客户端 |
| F9 | 矿脉分布 / 静态陷阱 | NONE(死代码) | `OreSystem.placementFor` 与 `TrapSystem.staticPlacementFor` **全库零生产调用方**，该维度已改用原版 noise + ore feature 生成。**不能拿它当 UI 数据源** |
| F10 | 实例人数上限 | 已失效 | `shareCap`/`globalCap` 在 R1 模型下已不再被 allocate 路径校验，是旧动态分配模型遗留 |

### G 组 · 精英怪

| # | action | 状态 | 说明 |
|---|---|---|---|
| G1 | `champion.codex` | WRAP | 35 词条（池/成本/最低星/互斥族/5 档数值）+ 10 星级主数据表 + 难度分布升格概率，纯静态 dump |
| G2 | `champion.inspect` | WRAP | 按实体查星级/词条/品质配色/血量（6 星及以上走自定义血池） |
| G3 | 参团贡献实时进度 | BACKEND | 账本只在死亡时 drain 一次性瓜分，**战斗中玩家完全不知道自己是否已达 0.5% boss 血或 15% 队均门槛** |
| G4 | 击杀奖励结算 | BACKEND | 只打 LOGGER + grantDaily 入账，**没有任何 S2C 告诉玩家分到了多少**（撞 A11） |
| G5 | 燃烧/寒霜 DoT 层数 | BACKEND | 只扣血 + 粒子，不挂 MobEffectInstance，玩家在原生效果栏看不到叠了几层/合计 %maxHP/s |
| G6 | 玩家减伤汇总 | BACKEND | `ReductionSource.rate()` 依赖 DamageSource 入参，只在受击瞬间现算，**无脱离受击场景的快照查询** |
| G7 | BOSS 血条 / 命定之死 / 反击单元 / 致盲 | READY(不搬) | 全是 vanilla 原生机制，零客户端代码，MCEF 重实现只增延迟无收益 |

### H 组 · 系统商店（WOK-ChestShop 跨仓）

| # | action | 状态 | 说明 |
|---|---|---|---|
| H1 | `shop.catalog` | BACKEND | `AdminShopRegistry` 是 **private Map 按 BlockPos 存且逐维度隔离**，需新增 public `entries()` 并遍历全部 ServerLevel |
| H2 | `shop.detail` | WRAP(跨 jar) | `recordAt(BlockPos)` 已就绪，但 handler 须写在主仓跨 jar 调用 |
| H3 | 远程买卖 | NONE | `ShopTransaction.buy/sell` 逻辑完整且已挂主仓 IEconomyService，但**只被玩家物理点击真实告示牌的路径调用**（内嵌 reach/tamper/冷却校验），隔空下单需新规格 |
| H4 | 跨店比价 | BACKEND | 仅 BlockPos -> ShopRecord 正向索引，无反向索引、无缓存机制 |
| H5 | 商店流水 | NONE | 每笔买卖只发一条聊天提示，不落任何流水 |

> 跨仓依赖：ChestShop 靠 `fg.deobf` 引 `libs/` 下 miningdim 编译期快照 jar（由 `tools/prepare-miningdim-dep.ps1` 手动同步）。**主仓改 webui API 需先重建该 jar 再回灌**。

### I 组 · 管理后台（OP）

| # | action | 状态 | 说明 |
|---|---|---|---|
| I1 | `admin.setBaseValue` / `admin.listItems` | READY x2 | 已接线 |
| I2 | `admin.economy.balance` / `.set` | WRAP x2 | `/economy set` 内部已读 before 余额，抄同一 ledgerOf+balance 范式。**无流水表，面板做出来也查不到历史调账** |
| I3 | `admin.job.setLevel` | WRAP | 权限校验/setLevel/改级后 syncTo 全就绪 |
| I4 | `admin.mining.reset` | WRAP | 活跃版 `/mining reset` **无二次确认**（有二次确认的那套在 `com.miningdim.command` 死代码里），面板按钮必须自行加确认弹窗 |
| I5 | `admin.champion.summon` | WRAP(低优先级) | 需 EntityType 下拉 + 35 词条多选 + 星级输入，复杂度高，建议暂缓 |

---

## 四、完全没有后端的 10 块系统（前端不做或留占位）

| 系统 | 文档依据 | 性质 |
|---|---|---|
| 开箱（买箱 + 买钥匙 + 掉率公示） | 经济文档第四章 180-249 行 | **信用点第一 sink + 青辉石唯一 sink**，全库零实现。掉率公示是硬需求 |
| 皮肤资产库存与归属 | 经济文档 0.2 与三章 | 文档明写"绝不以物品形式存在"，7 天 trade hold 是 UI 状态；当前 market 只交易 ItemStack 快照 |
| 日常/周常/挑战任务 | 经济文档 63 行（faucet 一览第 1 条） | 四大信用点 faucet 之首，全库唯一近似物是特勤职业专属悬赏 |
| 组队（4 人 party） | `IMiningConfig.maxPartySize=4` + `SelectZoneC2S.partyJoin` | 两个都是活字段却**无任何组队实体/成员表/邀请流程**，partyJoin 解码即丢 |
| 排行榜 | 社交服强需求 | 全库无任何跨玩家遍历/排序查询，数据都在 per-player capability 或私有 Map |
| 成就/里程碑 | 仅婚姻一处有一次性里程碑 | 无通用定义/进度/领奖框架 |
| 物品百科（在哪买/在哪造/基准价/可否交易） | 跨 market + 收购价 + ChestShop + 枪匠配方 | 无任何一处能按 itemId 反查全部获取与出手渠道 |
| 空军（钓鱼，第 9 职业） | docs 31 份中**无 spec**，仅存在于会话记忆 | JobId 仍是 8 个，前端按 8 个做但预留扩展位 |
| 平板 hub 本身 | PixelUI 规格第一章 | 无平板物品、无键位、无面板注册表 |
| 电力/线缆 | `Power_Cable_DesignSpec.md` | 代码在未合并分支，且 12 级中仅 2 级真实注册 |

---

## 五、落地顺序（DRAFT）

> **本章已被替代（2026-08-13）**：批次划分改以 [WebUI_Wiring_Execution_Scope.md](WebUI_Wiring_Execution_Scope.md) 为准
> —— 该文按模块拆 12 个分支，而非本章的线性四批（批 1 像素单点验证已作废，见文首修订说明）。
> 本章保留作历史记录，其中批 0 的完成状态仍然有效。
> 第三章接线总表与第七章决策项**不受影响，仍是真源**。

严格分批，前一批不通过不进下一批 —— 与 PixelUI 规格第十一章"第 1 步未通过则不进入第 2 步"同纪律。

**批 0 · 解阻塞（Java 侧，不外包）** —— 2026-08-12 已完成，见分支 `feat/webui-host-entry`，699/699 GameTest 全绿
1. [x] A15 前端加载入口：新增客户端配置 `webui.url`（校验必须 http/https 绝对地址）；`WebUiClient` 拆 `openWebUi`（正式前端）/ `openDevScreen`（内置调试页），按 J4 共用单例 `WebBrowser`，仅当目标 URL 变化才 `loadURL` 导航。
2. [x] J3 键位入口：新增 `WebUiKeyMappings`，默认 G。保留 `/miningdim-webui-dev` 作调试后路。
3. [x] A3 契约握手：`WebUiServerDispatcher.registeredActions()` 自省 + `system.handshake` 回 `{modVersion, actions[]}`。
4. [x] A12 补字段：`market.list` / `market.mine` / `player.inventory` 响应补 `descriptionId`；顺带把 list 与 mine 逐字重复的挂单 JSON 构造收敛为单点。
5. [x] `.gitignore` 补 `webui/node_modules/` 与 `webui/dist/`。
6. [ ] ~~事件名常量表~~ —— **本批不做，移入批 3**。建一个当前无任何发送方的常量类属空壳代码（YAGNI），受控常量的约束在第一个真实发送方落地时一并建立才有意义。

**批 1 · 像素单点验证**（PixelUI 规格第十一章第 1 步）
出 1 张 9-slice 资产 + `PixelFrame`，用同一份资产同时渲染行内小按钮与全屏平板，真客户端验 `devicePixelRatio` x GUI Scale 叠加是否破坏像素对齐。**此步不通过，后面全部作废。**

**批 2 · 市场闭环**（唯一后端就绪的模块，验证全链路）
组件库 L0/L1 + 市场四视图，按 J2 全用轮询。同步补 B1 total / B6 history / B9 feePreview / B10 p2pCap / A14 中文输入，并在收尾前定 J1。

**批 3 · 职业总览 + 个人档案**
A5 聚合 action + C1 job.progress。按 J2 在此批补 `S2CWebUiEvent` 的首个生产调用方（成交/求婚/击杀结算三类）。

**批 4 · 各职业子页 + 婚姻 + 矿洞**
约 30 个薄封装 action 铺开，是 Codex 主战场。

---

## 六、Codex 外包任务包（切片，每片自足）

外包原则：**只包"服务端逻辑已齐全、只差 JSON 薄封装"的 WRAP 类**。BACKEND 类涉及新索引/新聚合/私有字段暴露，必须人工设计后再交付实现。

| 包 | 内容 | 规模 | 自足性依据 |
|---|---|---|---|
| CX-1 | 组件库 L1 的 15 个控件 + L2 的 4 个状态件 | 19 文件 | 依赖只有 L0 三件（我先写完并冻结 API） |
| CX-2 | C 组职业薄封装：`job.progress` / miner / farmer / chef / brewer 共 8 个 action | 8 handler | 全部照抄 `PlayerWebUiActions` 范式，纯函数与 SavedData 查询均已就绪 |
| CX-3 | E 组婚姻薄封装：state + 5 个动作 + sharedInv 共 7 个 action | 7 handler | Engine 方法齐全，失败枚举需映射文案（文案我给） |
| CX-4 | F 组矿洞薄封装：overview / myStatus / enter / leave / gate 共 5 个 action | 5 handler | 必须复用 `EntryGateway.requestEnter`，此约束写死在任务书里 |
| CX-5 | G 组图鉴静态表 dump：35 词条 + 10 星级 + 难度分布 | 2 handler | 纯静态枚举转 JSON，零玩家态依赖，最适合外包 |
| CX-6 | I 组管理后台薄封装：economy.balance/set + job.setLevel + mining.reset | 4 handler | 照抄 `MarketAdminActions.requireOp` 门控范式 |
| CX-7 | C19/C20 军械三台 + 图纸百科 | 5 handler | ContainerData 字段齐全，按 blockPos + 按钮 id 转发 |

**不外包**（26 处 BACKEND + 全部 BLOCKED）：经济私有 map 暴露、market DAO 新查询、婚姻求婚反查索引、特勤扫描触发入口、塔罗只读 peek、事件推送生产方、错误码体系、聚合 profile、图标端点、中文名词典。

---

## 七、遗留决策项（需拍板才能继续）

### 甲类 · 曾卡批 0（2026-08-12 已全部拍板，DECIDED）

| # | 决策 | 结论 | 落地含义 |
|---|---|---|---|
| J2 | **实时性策略**（C4/A11） | **DECIDED = 混合**：成交/求婚/击杀结算走 `S2CWebUiEvent` 推送，进度条类走前端轮询 | 批 2 市场闭环可全用轮询先跑通；`S2CWebUiEvent` 的首个生产调用方随批 3 补，不必现在动 `grantXp` 主路径（规避 C4 同步时机缺口的改造风险）。事件名须集中登记为受控常量（架构文档 5.1 第 6 条），不允许各子系统自由拼字符串 |
| J3 | **平板 hub 入场载体**（A17） | **DECIDED = 键位绑定** | 注册一个 `KeyMapping`（客户端侧，`RegisterKeyMappingsEvent`），按下即 `setScreen(WebUiScreen)`。不做平板物品，因而无掉落/死亡/复制处理面；日后要加沉浸感道具再叠，不影响本决策 |
| J4 | **前端加载 URL 形态**（A15） | **DECIDED = 单 URL + 前端 hash 路由** | Java 侧只持有一个 `devServerUrl`，`WebBrowser` 只 `create()` 一次并全程复用（与 10.5 预加载策略一致）。切面板不经 Java，由前端路由自理；Java 侧若需定向打开某面板，走 `client.navigate` 事件带 route 参数，而非 `loadURL` 重载页面 |
| J1 | **第三方 mod 的物品图标怎么取**（A13） | **推迟到批 2 收尾前**（已不卡批 0） | 候选：(a) 新增客户端本地 action `client.itemIcon`，与 `client.i18n` 同构，从客户端 `ResourceManager` 读 PNG 转 base64；(b) 切分发路线 B 出 `/v1/item-icon`；(c) 构建期从 `libs/` 抽图；(d) 一律像素占位块。倾向 (a)：客户端本就加载全部 mod 资源，取图与取中文名是同一件事的两面，且顺带甩掉对第三方镜像站的依赖、自动覆盖未来新 mod。**本条只关乎第三方 mod** —— 本 mod 自己的 303 张贴图同 monorepo 构建期 copy 即可 |

> J1 的降级依据：本 mod 自己的贴图同仓 copy、原版贴图走既有回退链，二者合计已覆盖批 2 市场闭环的绝大多数标的，第三方 mod 物品可先落像素占位块。
> (a) 方案需一次实现期验证：`Minecraft.getInstance().getResourceManager().getResource(...)` 在 1.20.1 返回 `Optional<Resource>`，读 PNG 字节本身直接；难点在 itemId -> 贴图路径解析（要走模型 parent 链，等价于把 `mcAssets.ts` 的逻辑搬到 Java 客户端侧）。标 PENDING。

### 乙类 · 卡住批 2 之后，晚定会返工

| # | 决策 | 说明 |
|---|---|---|
| J5 | **QTE 类交互进不进 MCEF**（C10 厨师火候、C21 纳米校准） | 游标是每 tick 变化的服务端时序权威值，网络延迟直接影响判定手感。建议：**不进**，保留原生 Container GUI，平板里只做数值预览与配方查询 |
| J6 | **挂单过期做不做**（B14） | 规格 6.1 写了 `expire_at`，`ListingRow` 无该列。做 = 加列 + 定时清理 + 前端倒计时；不做 = 市场页去掉过期相关 UI，挂单永久有效。影响托管物品长期占用 |
| J7 | **离线成交交付落点**（B13） | 架构文档 257 行仍 DRAFT。收件箱面板 / 登录自动补发 / 邮箱物品。当前 `drainPendingPayout` 是登录取即删，只覆盖货款不覆盖退回物品 |
| J8 | **可交易标的白名单**（B12） | 塔罗禁交易、青辉石绑定、婚戒绑定在设计里存在但挂单路径无过滤。不定则前端无法灰掉不可挂物品，玩家会先托管再报错 |
| J9 | **特勤扫描的触发入口**（C16） | 三件套齐全但零调用点。选项：专用道具 / 键位 / 平板内按钮 / 手持特定物品右键。这条不定，特勤职业整块无法接 |

### 丙类 · 产品范围（决定前端要不要留导航位）

| # | 决策 | 说明 |
|---|---|---|
| J10 | **开箱与任务系统的优先级** | 二者分别是"信用点第一 sink"与"faucet 之首"，都零实现。经济完整度审计已判定闭环不成立；UI 建起来后这两块的缺位会立刻显形 |
| J11 | **组队 party 做不做** | `maxPartySize=4` 与 `SelectZoneC2S.partyJoin` 都是活字段但解码即丢。已有讨论未落代码 |
| J12 | **排行榜/成就做不做** | 社交服强需求且天然属 Web UI，但全库无跨玩家遍历查询，是纯新建 |

### 我直接定的（列出来给你反对的机会，不反对即执行）

1. **错误码体系**：约定 `{errorCode, params}` 结构化返回，前端本地化。现在直接抛 Java 异常原文给玩家不可接受。
2. **UI 偏好持久化**（A9）：先不做 capability 字段，用 Chromium localStorage；等真出现跨机器诉求再补。
3. **管理后台**：进同一个平板，靠 `player.isOp` 决定是否渲染页签，不另开入口。
4. **职业页不做单选器**：8 条并列，因为全职业被动恒生效是已定设计。
5. **电力系统**（C22）：前端不留位。代码在未合并分支且 12 级里仅 2 级真实注册，现在展示是假数据。
6. **空军（第 9 职业）**：不做，但职业列表按数据驱动写，加第 9 个不用改前端。
7. **`--px` 取值**：批 1 单点验证时在真客户端标定，不提前定。
