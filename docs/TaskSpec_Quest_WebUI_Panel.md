# TaskSpec: 任务系统接入 WebUI 平板面板

状态: 待实施 | 分支: `feat/quest-webui-panel` | 预估: 服务端 1 个新类 + 3 处改动, 前端 1 个新页面 + 4 处改动

---

## 零、执行者须知 (硬约束, 违反即返工)

1. **严禁 Emoji**。代码、注释、提交信息、文档、日志、测试用例一律零 Emoji。严重度用纯文本 (Critical/Major/Minor), 状态用 `[x]` / `[ ]`, 层级用数字或 `===` / `---`。
2. **严禁 TODO 与空壳代码**。本规格已给全部判据; 缺信息就在 PR 里明写缺什么, 不许留 `// TODO` 或空方法体。
3. **严禁越界修改**。只改本规格点名的文件。旁边代码有问题就在 PR 描述里口头报告, 不要顺手改导入顺序 / 格式 / 命名。
4. **异常必须痛**。业务层不许 `?? 0` / `|| '未知'` 掩盖空值, 不许 try-catch 生吞。WebUI 的唯一 Gateway 边界是 `WebUiServerDispatcher.dispatchAndRespond`, action handler 内一律让异常自然冒泡。
5. **提交信息用中文 + Conventional Commits**, 严禁任何 AI 署名 (`Co-Authored-By` 等)。
6. **写入不等于成功**。见第七节验证门, 全绿之前不许报告完成。
7. 查找文件用 `rg --files` 或 ripgrep, **严禁在 Bash 里用 `find`** (本机 Git Bash 的 `find.exe` 有句柄泄露, 单进程可囤三百万句柄不退出)。

工具链:
```
JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
后端: gradlew.bat compileJava / gradlew.bat runGameTestServer
前端: cd webui && pnpm build && pnpm lint && pnpm lint:css
```

---

## 一、背景与目标

任务系统 (`com.miningdim.quest`) 服务端已完整落地: 四类来源 (DAILY/WEEKLY/SPECIAL/HIDDEN)、任务板、领奖、上交、重摇、信用点与物品奖励全通。玩家入口目前**只有 `/quest` 命令**。

用户决策: **直接接 WebUI 面板**。

本任务把任务板接进平板 hub, 成为第 11 个面板。

### 必须先读的一条历史

`src/main/java/com/miningdim/webui/server/HubWebUiActions.java:37` 的 `PANEL_IDS` **显式剔除了 `quests`**, 注释写着:

> quests 剔除 (前端根本没有这条路由, 任务系统零实现, 发一个点不进去的入口只会制造工单)

`webui/src/lib/types.ts:468` 与 `webui/src/lib/bridge.mock.ts:1802` 有同样措辞的注释。这三处注释在本任务完成后**全部过期, 必须一并改掉**——留着会让下一个人以为任务系统仍然零实现。

---

## 二、服务端: 新增 `QuestWebUiActions`

新文件 `src/main/java/com/miningdim/quest/QuestWebUiActions.java`。

### 2.1 照抄哪个范式

**逐字对照 `src/main/java/com/miningdim/entry/MiningWebUiActions.java`**, 它是本仓库 WebUiAction 的样板。必须复制的三条做法:

1. **`Gson` 用 `new GsonBuilder().serializeNulls().create()`**。任务回执里"这个槽没有替换任务""这条任务没有奖励物品"是真值不是缺数据; 默认 Gson 会把 null 成员整键丢掉, 前端拿到 `undefined` 就分不清"服务端说没有"和"服务端漏发了"。
2. **业务判据不另写一份**, 一律调 `QuestServices.service()` 门面的现成方法。`QuestService` 的 javadoc 明写它把"翻转周期 / 标脏存档 / 发奖顺序"三件必须成套发生的事收在一处, 绕过它必然漏其中一件。
3. **静态 `registerAll()`**, 由子系统 `register()` 调一次。

### 2.2 注册点

改 `src/main/java/com/miningdim/quest/QuestSystem.java` 的 `register(IEventBus, IEventBus)`, 在 `forgeBus.register(new QuestEventHooks())` 之后加一行 `QuestWebUiActions.registerAll();`。

注意 `QuestSystem` 无需持有任何实例 (与 `MiningWebUiActions` 需要 `EntrySystem` 不同): 任务的服务门面是进程级静态 `QuestServices`, action 里直接取即可。**不要照抄 `MiningWebUiActions` 那个 `volatile EntrySystem` 字段**, 那是它自己的处境, 任务这边引入等于凭空多一个可为 null 的状态。

### 2.3 四条 action 的完整契约

所有 action 在进入业务前先过这道闸:

```java
if (!QuestServices.active()) {
    throw new WebUiBusinessException(WebUiErrorCodes.QUEST_DISABLED,
            "任务系统当前未启用", false);
}
```

`QUEST_DISABLED` 是**新增错误码**, 加到 `src/main/java/com/miningdim/webui/server/WebUiErrorCodes.java`。该文件的类注释写明"码值即对外契约, 一旦下发不许改名", 照此写 javadoc。

#### (1) `quest.board` — 入参 `{}`

回执:

```jsonc
{
  "dailyRefreshCost": 500,          // long, QuestRewards.refreshCost(DAILY)
  "weeklyRefreshCost": 2500,        // long
  "creditBalance": 123456,          // long, 玩家当前信用点; 前端据此灰掉付不起的重摇按钮
  "daily":   [ <QuestRow>, ... ],   // 顺序即槽位序, 下标 0 起
  "weekly":  [ <QuestRow>, ... ],
  "special": [ <QuestRow>, ... ],
  "chains":  [ <ChainRow>, ... ]
}
```

`QuestRow`:

```jsonc
{
  "questId": "daily.mine.iron",     // QuestDefinition.id()
  "title": "...",                   // QuestDefinition.title()
  "objective": "...",               // QuestDefinition.objective().describe()
  "difficulty": 1,                  // int, QuestDefinition.difficulty()
  "count": 3,                       // int, 当前进度
  "requiredCount": 8,               // int
  "complete": false,                // QuestProgress.isComplete()
  "claimed": false,                 // QuestProgress.claimed()
  "turnIn": false,                  // objective instanceof TurnInItemObjective
  "creditReward": 2000              // long, QuestRewards.creditFor(definition)
}
```

`turnIn` 这一项决定前端画不画"上交"按钮, 必须由服务端判定——前端靠 `objective` 描述文本猜是典型的契约漂移源。

`ChainRow`:

```jsonc
{
  "chainId": "...",                 // QuestChain.id()
  "title": "...",
  "finished": false,                // QuestChainState.finished()
  "stageIndex": 0,                  // int, 0 起
  "stageCount": 4,                  // int
  "current": <QuestRow> | null      // finished 时为 null, 必须显式发 null
}
```

取数一律经 `QuestServices.service().boardOf(sender)` (它内部会先翻转周期)。**不要自己调 `QuestSavedData`**。

`creditBalance` 取 `EconomyServices.economyService().creditBalance(sender)`。

#### (2) `quest.claim` — 入参 `{"questId": "<string>"}`

```java
String questId = WebUiPayloads.requiredString(payload, "questId");
QuestService.ClaimResult result = QuestServices.service().claim(sender, questId);
```

回执:

```jsonc
{
  "outcome": "CLAIMED",             // ClaimOutcome.name(), 四值: CLAIMED/NOT_FOUND/NOT_COMPLETE/ALREADY_CLAIMED
  "questId": "daily.mine.iron",
  "title": "..." | null,            // NOT_FOUND 时 definition 为 null, 显式发 null
  "credit": 2000,                   // long, 非 CLAIMED 时为 0
  "items": [ <ItemRow>, ... ]       // 非 CLAIMED 时空数组
}
```

**四种 outcome 全部走 success=true 回执, 不抛 `WebUiBusinessException`。** 理由: 它们是业务结果不是调用失败, 前端要按 outcome 分别渲染 (领取成功的金额动画 vs "还没做完"的提示)。抛异常会让前端只能拿到一句错误文案, 且拿不到 `title`。这一条与 `/quest claim` 命令的行为一致 (命令里 `NOT_FOUND` 走 `sendFailure` 只是聊天框的表达方式差异)。

`ItemRow`:

```jsonc
{
  "itemId": "minecraft:iron_ingot",           // ForgeRegistries.ITEMS.getKey(stack.getItem()).toString()
  "descriptionId": "item.minecraft.iron_ingot", // stack.getDescriptionId()
  "count": 6,
  "enchantments": [ {"id": "minecraft:mending", "level": 1} ]  // 仅附魔书/带附魔物品有此键, 否则整键缺席
}
```

再调一次 `WebUiItemJson.appendVariant(itemRow, stack)` 追加 `customModelData` / `nameParts` (见该类 javadoc)。

**关于 `enchantments` 这一项的必要性**: 附魔书的 `descriptionId` 恒为 `item.minecraft.enchanted_book`, 所有书长得一模一样。任务奖励池 (`QuestItemRewards`) 里附魔书是最有价值的一档 (含经验修补与金钱修补), 不发附魔信息等于让玩家中了大奖却看到一句"附魔书"。

实现前**先读 `net.minecraft.world.item.enchantment.EnchantmentHelper` 的真实签名再落笔**, 不要凭记忆写 `getEnchantments`。专用服务端不加载 lang 文件, 故只发注册名 (`ResourceLocation` 字符串), 中文由客户端 `client.i18n` 解附魔的 `descriptionId`。

#### (3) `quest.turnIn` — 入参 `{"questId": "<string>"}`

```java
QuestService.TurnInResult result = QuestServices.service().turnIn(sender, questId);
```

回执 `{"outcome": <TurnInOutcome.name()>, "questId", "title" | null, "count": <int>}`。五种 outcome 同样全走 success=true。

#### (4) `quest.refresh` — 入参 `{"source": "daily"|"weekly", "slot": <int, 0 起>}`

```jsonc
{
  "outcome": "REFRESHED",           // RefreshOutcome.name(): REFRESHED / NOT_ENOUGH_CREDIT
  "cost": 500,                      // long
  "replacement": <QuestRow> | null  // NOT_ENOUGH_CREDIT 时 null
}
```

**槽位序号是 0 起, 不做 +1 转换。**`QuestCommands` 里那个 `playerFacingSlot - 1` 是**聊天命令专属**的人机适配 (它的类注释写明了"槽位序号对玩家是 1 起而对内部是 0 起"), WebUI 走数组下标, 天然 0 起, 照抄 +1 会让面板上点第一个槽重摇掉第二个。

两道前置校验, 顺序固定:

1. `source` 解析。只接受 `daily` / `weekly` (大小写不敏感), 其余值抛 `WebUiPayloads.illegalValue("source", raw, "...")`。**不接受 `special` / `hidden`**——`QuestRewards.refreshCost` 对它们会抛 `UnsupportedOperationException`, 那会走通用兜底变成一句没有 errorCode 的英文原文。范式照抄 `MiningWebUiActions.parseDifficulty`。
2. `slot` 越界。取 `board.daily().size()` / `board.weekly().size()` 判断, 越界抛 `WebUiErrorCodes.SLOT_OUT_OF_RANGE` (**已有的码, 不要新造**)。这道闸必须在调 `service().refresh()` **之前**——`QuestService.refresh` 是先扣费后重摇的, 越界下标会在扣完费之后才炸, 玩家白丢 500 信用点。

---

## 三、服务端: 放开 hub 面板

改 `src/main/java/com/miningdim/webui/server/HubWebUiActions.java`:

1. `PANEL_IDS` 加 `"quests"`。**位置**: 放在 `"mining"` 与 `"codex"` 之间。理由: 该列表顺序即前端磁贴顺序, 任务是日常高频入口, 排在图鉴这类查阅型面板之前。
2. 改掉类注释里"quests 剔除 (前端根本没有这条路由, 任务系统零实现...)"那段, 换成本次接线后的事实。
3. 加一道锁: 任务系统被配置关掉时 (`!QuestServices.active()`) 该面板 `enabled=false` + `lockCode = HubLockCodes.QUEST_DISABLED`。

`QUEST_DISABLED` 加到 `HubLockCodes`。**注意它与第 2.3 节的 `WebUiErrorCodes.QUEST_DISABLED` 是两个不同命名空间里的同名码**, 这不是重复——`HubLockCodes` 的类注释明写两张表严禁合并, 因为"面板为什么进不去"与"这次调用为什么失败"是两种语义, 前端也是两张本地化字典。两处各自写自己的 javadoc。

`HubWebUiActions.PANELS` 目前的写法是 `boolean enabled = !PANEL_ADMIN.equals(panelId) || op;`, 加第二道锁后要改成按 panelId 分派的形式。保持"每条锁各自独立判定"的结构, 不要写成一串三元表达式。

---

## 四、前端

### 4.1 契约类型 `webui/src/lib/types.ts`

- `HubPanelId` 联合类型加 `'quests'`, 并改掉 468 行那段"quests 剔除"的注释。
- 新增 `QuestRow` / `QuestChainRow` / `QuestBoardResult` / `QuestClaimResult` / `QuestTurnInResult` / `QuestRefreshResult` / `QuestItemRow` 接口, 字段与第二节的 JSON 逐字对齐。
- outcome 一律写成**字面量联合类型** (`'CLAIMED' | 'NOT_FOUND' | 'NOT_COMPLETE' | 'ALREADY_CLAIMED'`), 不要写 `string`——那样前端 switch 漏分支时 tsc 不报。

### 4.2 路由 `webui/src/router.ts`

加 `export const ROUTE_QUESTS = '/quests'`, 加进 `ROUTE_PATTERNS` (静态模式, 排在 `ROUTE_JOB_DETAIL` 之前——该文件注释写明静态模式一律排在含 `:参数` 的动态模式之前), 加进 `ROUTE_TITLES` (`'任务板'`)。

`ROUTE_TITLES` 是 `Record<RoutePattern, string>`, 漏配 tsc 直接报缺键, 不会静默。

### 4.3 面板元数据 `webui/src/lib/panels.ts`

`HUB_PANEL_META` 加 `quests: { label: '任务', route: ROUTE_QUESTS, iconItemId: 'minecraft:writable_book' }`。

`PANEL_LOCK_TEXT` 加 `QUEST_DISABLED: '任务系统当前未启用'`。

### 4.4 页面 `webui/src/pages/QuestsPage.tsx` (新建)

参照 `webui/src/pages/MiningPage.tsx` 的结构 (取数 + 渲染 + 动作回执处理)。组件一律用 `webui/src/components/kit/` 下的既有件, **不要新造按钮/卡片样式**——kit 契约层的存在意义就是换皮时业务页零改动 (见 `webui/src/components/kit/README.md`)。

页面结构:

- 顶部: 信用点余额 + 两个重摇单价
- 每日区: N 个槽位卡片, 每张显示 title / objective / 进度条 (`count`/`requiredCount`) / 奖励金额 / 难度; 右侧按状态给按钮
  - 未完成且是上交类 -> "上交"
  - 已完成未领 -> "领取"
  - 已领 -> 灰态
  - 每张卡片右上角一个"重摇"图标按钮, 余额 < `dailyRefreshCost` 时禁用
- 每周区: 同上, 用 `weeklyRefreshCost`
- 特殊区: 同上但**无重摇按钮** (特殊任务不可重摇, 服务端会拒)
- 任务线区: 按 `stageIndex/stageCount` 显示阶段, `finished` 时显示已完成

动作后一律重新拉 `quest.board` 刷新整块, 不做本地乐观更新——任务进度可能被同一次动作以外的事件推进 (挖矿、击杀), 局部改本地状态会与服务端漂移。

错误文案走 `webui/src/lib/errorText.ts` 的 `callErrorText`, 新错误码 `QUEST_DISABLED` 加进 `ERROR_CODE_TEXT`。

### 4.5 分派 `webui/src/App.tsx`

`import { QuestsPage }` + `ROUTE_QUESTS` 分支。

### 4.6 mock `webui/src/lib/bridge.mock.ts`

- `HUB_PANEL_IDS` 加 `'quests'`, 改掉 1802 行那段注释。
- 四条 `quest.*` 的 mock handler, 返回形状与真服务端逐字一致的种子数据。种子里必须包含: 一条未完成的普通任务、一条未完成的上交类任务 (`turnIn: true`)、一条已完成未领、一条已领、一条任务线。**否则页面的四种按钮状态里有三种在 mock 下画不出来, 等于没法自测。**

注意 `webui` 生产构建会剥离 mock (见既有实现), 不要把 mock 逻辑写进非 mock 文件。

---

## 五、明确的非目标 (做了算越界)

- **不做悬赏 (BOUNTY)**。用户决策: 悬赏作为任务线 DLC 后续以第五来源并入, 本任务不碰。
- **不改任何任务判据 / 奖励数值 / 内容池**。`QuestPool` / `QuestConfig` / `QuestRewards` 一行不动。
- **不做游戏内 HUD、不做 S2C 主动推送**。面板靠玩家打开时拉取, 不做轮询也不做推送。
- **不动 `/quest` 命令**。命令与面板并存, 命令是运维与无 MCEF 环境的退路。
- **不改 `WebUiServerDispatcher` / 限流 / 防重放**。四条新 action 天然继承既有闸。

---

## 六、测试要求

新建 `src/main/java/com/miningdim/quest/QuestWebUiGameTests.java`, 范式照抄 `src/main/java/com/miningdim/entry/MiningWebUiGameTests.java`。

**质量判据 (硬性): 删掉被测的核心实现, 该断言必须挂。**严禁 `assertTrue(x != null)` 这类永远通过的弱校验。

必须覆盖的六条:

1. `quest.board` 回执里 daily 行数等于 `QuestConfig.DAILY_SLOTS`, 且每行 `questId` / `requiredCount` / `creditReward` 与 `QuestPool` 里同 id 的定义逐字一致。
2. `quest.board` 的 `turnIn` 标志: 上交类任务为 true, 挖矿类为 false。(删掉 `instanceof TurnInItemObjective` 判定即挂)
3. `quest.claim` 对未完成任务回 `outcome=NOT_COMPLETE` 且**余额一分未变**。
4. `quest.claim` 对已完成任务回 `outcome=CLAIMED`, `credit` 等于 `QuestRewards.creditFor`, 且钱包增量与回执一致。
5. `quest.refresh` 槽位越界时**先拒后扣**: 断言余额与越界前逐分相等。(把越界校验挪到 `service().refresh()` 之后即挂——这是本任务最容易写错的一处)
6. `quest.refresh` 传 `source=special` 被拒且回 `INVALID_REQUEST` + `params.field=source`, 不是通用兜底的英文原文。

另外在 `WebUiHubStatusGameTests` 或等价位置补一条: `hub.panels` 的回执含 `quests` 一行。

---

## 七、验证门 (全绿之前不许报告完成)

```
# 后端
set JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer

# 前端
cd webui
pnpm build          # 内含 tsc --noEmit
pnpm lint
pnpm lint:css
```

**判绿不能只看 gradle 退出码。**`runGameTestServer` 在服务端数据包加载崩溃时仍可能报 `BUILD SUCCESSFUL`。必须在日志里核对这两行:

```
N tests are now running!
All N required tests passed :)
```

当前基线 **1165 绿**。本任务完成后应为 1165 + 新增用例数, 且**一条都不许比基线少**。

### 变异验证 (必做, 结论写进 PR 描述)

至少对第 6 节的第 3 条与第 5 条各做一次: 故意破坏实现 -> 确认对应断言真的挂 -> 还原 -> 复跑全绿。PR 描述里贴出变异时的失败信息原文。

---

## 八、交付

- 分支 `feat/quest-webui-panel`, 从最新 `main` 切出。
- 原子提交: 建议拆成"服务端 action + 注册"、"hub 面板放开"、"前端接线"三个提交。
- PR 描述必须含: 变异验证结论、测试绿数 (基线 1165 -> 新值)、以及第五节非目标里**有没有不小心碰到**的自查。
