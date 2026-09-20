# TaskSpec: 矿洞进入收费

状态: **数值待实测** (机制可先做) | 分支: `feat/mining-entry-fee`

---

## 零、执行者须知 (硬约束, 违反即返工)

1. **严禁 Emoji**。代码、注释、提交信息、文档、日志、测试用例一律零 Emoji。
2. **严禁 TODO 与空壳代码**。
3. **严禁越界修改**。只改本规格点名的文件。
4. **异常必须痛**。不许掩盖空值, 不许 try-catch 生吞。
5. **提交信息用中文 + Conventional Commits**, 严禁任何 AI 署名。
6. **写入不等于成功**, 见第七节验证门。
7. 查找文件用 `rg --files` 或 ripgrep, **严禁在 Bash 里用 `find`**。
8. **严禁臆造收费数值。**本任务的默认值必须是 0 (即保持当前免费), 理由见第一节。谁把一个非零数字写进默认值, 谁就是在拿没有依据的数编经济。

```
JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer
```

---

## 一、为什么这个任务要拆成两段

用户决策原文: "进去你根据收益比简单定价, 三个难度都高"。

"根据收益比定价"要求先知道**一趟矿洞平均产出多少信用点**。这个数**当前不存在**:

- 全库 grep 确认: `oresurvey` 这个测量工具**在代码与文档里都不存在**, 从来没被实现过。
- 现有的 `AbuseGuard` 只按"每人每日各矿种累计"记账, 没有"每趟行程"的口径, 也不分难度。
- `QuestMiningVisits` 记了行程起止, 但只为任务判据服务, 不记产出。

没有这个数就定价, 定出来的是拍脑袋数字, 而入场费是**硬门槛**——定高了新人进不去, 定低了等于没收。

所以本任务分两段:

- **第一段 (本规格全文)**: 收费机制 + 产出测量埋点。收费默认值 **0** (行为与现在逐字一致, 上线零风险)。
- **第二段 (另起任务, 不在本规格内)**: 拿测量数据定三档价, 改配置默认值。

第一段做完就可以上线跑数据, 第二段等数据够了再做。**不要试图在第一段里把价定了。**

---

## 二、现状核实 (已核实, 不要重新调研)

### 2.1 进入全流程

`src/main/java/com/miningdim/entry/EntryGateway.java` 的类注释写了完整 12 步:

```
1 gateCheck 难度门控 -> 2 snapshotFallback 写 Capability -> 3 allocate 实例 ->
4 awaitReady (生成中等待) -> 5 force-load spawn 周边区块 -> 6 awaitChunksLoaded ->
7 resolveSpawn -> 8 主线程 teleportTo -> 9 onPlayerEnter -> 10 写 currentInstanceId ->
11 initDanger / spawnFreeze -> 12 active=true
```

步骤 1 同步执行, 步骤 4-8 由 `tick()` 跨若干 tick 推进。

**步骤 3 与步骤 8 之间有五条失败回滚路径**, 全在 `advance()` 里:

1. 等待期间玩家断线 -> `rollback`
2. 实例被 GC / 重置销毁 -> `notifyAndRollback`
3. 实例进入 RESETTING -> `notifyAndRollback`
4. 生成超时 (200 tick) -> `notifyAndRollback`
5. 区块加载超时 (200 tick) -> `notifyAndRollback`

这五条决定了收费点的选择, 见第三节。

### 2.2 门控现状

`gateCheck` 当前只有矿工等级一道闸, 判据是 `MinerLevelGate.canEnter(minerLevel, difficulty)`。

**警告**: `src/main/java/com/miningdim/entry/GateResult.java` 的类注释写着"Easy 无 / Medium L10 / Hard L25" —— 这是**过期文档口径**, 真实门槛是 `MinerLevelGate` 的 Easy L1 / Medium L4 / Hard L8。`MiningWebUiActions` 的类注释已经点名了这处漂移。不要照抄那段注释里的数字。

### 2.3 门控在两处各写了一遍

- `EntryGateway.gateCheck` (权威路径)
- `MiningWebUiActions.ENTER` (面板的同步预拒, 让玩家不用等就知道进不去)

后者的注释明写"两条同步拒绝判据的顺序与 requestEnter 内部逐字一致, 否则同一次请求在这里与在权威路径里会给出不同的原因"。**加余额闸时两处都要加, 且顺序一致。**

---

## 三、机制设计

### 3.1 检查在前, 扣费在后 (这是本任务的核心决策)

- **余额检查**放进 `gateCheck` (步骤 1)。余额不足立刻拒, 玩家不用等十秒才被告知没钱。
- **实际扣费**放进 `completeEnter` (步骤 7-12), 就在 `teleportTo` **之前**。

**为什么不在步骤 1 就扣**: 第 2.1 节列的五条回滚路径, 每一条都要配一条退款。少写一条, 玩家就会在服务器卡顿导致区块超时的时候白丢一笔钱, 而且是静默的——他只会看到"进入失败", 不会注意到余额少了。把扣费挪到最后一步, **退款路径直接不存在**, 少写的代码就是少出的 bug。

**为什么不在 `teleportTo` 之后扣**: 那就成了先给货后收钱, 扣费失败时人已经在里面了。

### 3.2 检查与扣费之间会漂 (必须实现, 不许假设不会发生)

步骤 1 通过, 到步骤 7 之间隔了若干 tick, 玩家可能在这期间把钱花在别处 (市场、商店、开箱都能在同一时间窗里完成)。

所以 `completeEnter` 里的扣费**必须判返回值**:

```java
if (!EconomyServices.economyService().tryCharge(player, Currency.CREDIT, fee)) {
    // 中止入场, 走既有的 notifyAndRollback 路径
}
```

`tryCharge` 返回 `boolean`, 余额不足返 false 且不扣。这条分支要走 `notifyAndRollback` 撤掉已申请的 force ticket, 并给玩家一条明确提示 (不是"未知错误")。

**严禁**把它写成 `economyService().grant(...)` 的反向操作或忽略返回值。

### 3.3 免费时不碰货币层

`fee <= 0` 时**直接跳过**, 一次货币层调用都不发。理由与 `QuestRewards.chargeRefresh` 里那段注释同源: `tryCharge` 对 `amount<=0` 会抛 `ILLEGAL_AMOUNT`, 让一个合法的运营配置 (免费) 炸掉是错的。

这一条同时保证了默认值 0 时行为与现状逐字一致。

### 3.4 收费是 sink 不是转移

扣掉的信用点直接销毁, 不进任何人的口袋。这与任务重摇费同性质 (`QuestConfig` 里那条注释: "this is a sink, not a transfer")。`tryCharge` 本身就是纯扣减, 不需要额外做什么, 但要在注释里写明这是有意的。

---

## 四、实施

### 4.1 配置

三处联动 (照抄 `autoResetHours` 那一组的现成范式):

1. `src/main/java/com/miningdim/config/MiningServerConfig.java`: 加 `ENTRY_FEE_EASY / ENTRY_FEE_MEDIUM / ENTRY_FEE_HARD`, 类型 `ForgeConfigSpec.LongValue`, `defineInRange("entryFeeEasy", 0L, 0L, Long.MAX_VALUE)` (三档默认全 0)。
   注释里写明: 0 = 免费; 数值待产出实测后标定, 见 `docs/TaskSpec_Mining_EntryFee.md`。
2. `src/main/java/com/miningdim/core/IMiningConfig.java`: 加 `long entryFee(Difficulty difficulty);`
3. `src/main/java/com/miningdim/config/ModConfig.java`: 实现之, 照抄 `autoResetHours(Difficulty)` 的分派写法 (**该文件的门面约定是每个 getter 内实时 `.get()` 不缓存**, 见 `IMiningConfig` 类注释)。

### 4.2 `GateResult` 加一档

```java
INSUFFICIENT_FUNDS("message.miningdim.gate.insufficient_funds"),
```

顺手**不要**去改那段过期的类注释——那属于越界。只在 PR 描述里报告"GateResult 类注释里的等级数字是过期的 (真实值在 MinerLevelGate), 建议另开分支修"。

### 4.3 `EntryGateway`

- `gateCheck`: 等级闸之后加余额闸。顺序固定为**先等级后余额**——等级是永久门槛, 余额是临时状态, 先告诉玩家"你等级还不够"比"你钱不够"更有用。
- `completeEnter`: 在 `resolveSpawn` 之后、写 capability 之前扣费。失败则中止 (见 3.2)。
  **注意 `completeEnter` 当前返回 void 且调用方 `advance()` 直接 `return true`**, 加了失败分支后要让调用方能区分"成功进入"和"扣费失败中止", 两者都终结任务但提示不同。

### 4.4 `MiningWebUiActions.ENTER`

同步预拒里加余额闸, 顺序与 `gateCheck` 一致 (等级 -> 余额 -> 已在实例内)。回执沿用既有的 `rejected(result, reasonCode, reasonKey)` 形状, `reasonCode` 用 `GateResult.INSUFFICIENT_FUNDS.name()`。

同时 `mining.overview` 的每行加 `entryFee: <long>` 字段, 让面板能在卡片上显示价钱。前端改 `webui/src/lib/types.ts` 的 `MiningInstanceRow` 与 `webui/src/pages/MiningPage.tsx`。

### 4.5 lang 键

`message.miningdim.gate.insufficient_funds`, 加到 `src/main/resources/assets/miningdim/lang/` 下**实际存在**的语言文件 (先 `rg --files` 确认)。文案要带上差额, 用 `Component.translatable(key, args)` 传参。

---

## 五、产出测量埋点 (第二段定价的唯一依据, 本任务必须交付)

目标: 回答"EASY / MEDIUM / HARD 各自一趟平均产出多少信用点、耗时多久"。

### 5.1 口径

一趟 = 一次进入到一次离开 (含主动退出、死亡后离开、掉线)。

每趟记四个量:
- `difficulty`
- `dwellTicks` (停留 tick 数)
- `oreDrops` (高价矿产出物个数, 与 `AbuseGuard.recordMinedOreDrops` 的 producedCount 同口径)
- `creditGross` (本趟经 `settleOreSale` 产生的**毛额**, 即衰减主闸打折之前的数)

**为什么记毛额不记到手额**: 到手额受该玩家当天已经赚了多少影响, 同一趟矿在不同玩家身上会得出不同的数, 无法用来标定"这趟矿值多少钱"。毛额才是产出本身。

### 5.2 落点

新文件 `src/main/java/com/miningdim/entry/MiningYieldProbe.java`。进程内 `Map<UUID, 累计器>`, 进入时开始、离开时结算并打一行结构化 INFO 日志:

```
[miningdim] yield-probe difficulty=hard dwellTicks=12043 oreDrops=87 creditGross=4210
```

日志一行一趟, 便于事后 `rg` 出来统计。**不要引入新的持久层**——这是标定期的临时仪表, 不是长期功能。

### 5.3 挂在哪

- 进入: `EntryGateway.completeEnter` 末尾。
- 离开: `EntrySystem.leaveCurrentInstance` (那是 12.6 的统一离开汇聚点, 主动退出/死亡/掉线三条路都经过它)。挂这里才不会漏。
- 产出累加: 需要 `EconomySystem` 在 `settleOreSale` 路径上回调一次。**这是唯一一处跨子系统改动**, 做法是在 `MiningYieldProbe` 上开一个静态 `record(ServerPlayer, int drops, long gross)`, 由经济侧调用; 经济侧只依赖这个静态方法, 不 import entry 包的其它东西。

如果这条跨子系统接线在实施时发现比预期重, **停下来在 PR 里报告**, 不要为了绕开它去改经济子系统的结构。

### 5.4 保留现场

这套埋点**不许在第二段定价完成后顺手删掉**。日志清理权归主控。

---

## 六、测试要求

**质量判据: 删掉被测实现, 断言必须挂。**

必须覆盖的五条:

1. **免费时不碰货币层**: `entryFee = 0` 时进入成功且余额一分未变。(把 3.3 节的短路删掉, 会因 `ILLEGAL_AMOUNT` 抛异常而挂)
2. **余额不足被拒**: 余额 < fee 时 `gateCheck` 返 `INSUFFICIENT_FUNDS`, 且**一分钱没扣**。
3. **收费点在最后**: 这是本任务最重要的一条。构造一次"步骤 1 通过但中途失败"的入场 (最容易造的是让实例进入 RESETTING, 走 `advance()` 的第 3 条回滚路径), 断言**余额与入场前逐分相等**。
   把扣费挪回 `requestEnter` 时这条立刻挂——它锁死的正是第 3.1 节那个决策。
4. **成功进入后确实扣了**: 完整走通一次入场, 断言余额恰好减少 fee。
5. **检查与扣费之间余额被花光**: 通过后、`completeEnter` 之前把玩家余额清零, 断言入场被中止且没有把人传进去。(3.2 节那条分支的唯一防线)

矿洞入场链路当前 GameTest 覆盖为零 (全库审计 F092 明确指出"实例/重置/区块票/陷阱/出生五条链路零覆盖"), 所以这几条大概率要新搭测试脚手架。**这是本任务的正常工作量, 不是意外**, 不许因为"不好测"就降级成只测 `gateCheck` 的纯函数部分——那样第 3 / 5 条根本测不到, 而它们正是最容易写错的两条。

---

## 七、验证门 (全绿之前不许报告完成)

```
set JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8
gradlew.bat compileJava
gradlew.bat runGameTestServer

cd webui && pnpm build && pnpm lint && pnpm lint:css
```

**判绿不能只看 gradle 退出码**, 必须核对:

```
N tests are now running!
All N required tests passed :)
```

基线 **1165 绿**, 完成后应为 1165 + 新增用例数, 一条都不许少。

变异验证必做: 把扣费从 `completeEnter` 挪回 `requestEnter` -> 第 3 条必须挂 -> 还原 -> 复跑全绿。失败信息原文贴进 PR。

---

## 八、明确的非目标

- **不定价**。三档默认值必须是 0。
- **不做退款机制**。3.1 节的设计就是为了让它不必存在; 谁加了退款路径, 说明扣费点放错了。
- **不改矿工等级门槛**, 不改 `MinerLevelGate`。
- **不改 `GateResult` 的过期类注释** (口头报告即可)。
- **不做"包月/次卡/入场券"**。`GateResult.NO_TICKET` 这个预留码不要顺手实现。
- **不改重置 / 自动刷新周期**。

---

## 九、交付

分支 `feat/mining-entry-fee`, 从最新 `main` 切出。建议三个原子提交: "配置与门控 (默认免费)"、"扣费点与失败分支"、"产出测量埋点"。

PR 描述必须含:
- 三档默认值确认为 0 的自查
- 变异验证的失败信息原文
- 5.3 节那条跨子系统接线实际是怎么做的
- 第二段 (定价) 需要主控做的事: 让埋点在真机跑够样本, 然后拿 `yield-probe` 日志定价
