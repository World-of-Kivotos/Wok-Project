# 经济数据统一入 SQLite 实施计划

> **实施状态 (2026-08-12): 六个阶段全部完成, 815 个 GameTest 全绿。**
> 提交序列: `abe73fa` 地基 / `84acb5f` 合库与旧库导入 / `94f36b7` 钱包与账本入库 /
> `70ae7d2` faucet 合并事务 / `4ff78dc` 市场单事务化 / `30bd213` 开箱单事务化 /
> `b09fcaf` 恢复链路四缺陷 / `f7dbb73` 守恒律崩溃测试。
> 本文保留原始计划正文供追溯; 各阶段的实际落点见每节开头的状态行, 尚未闭合的项见第六节。

本文是可执行的交接文档。写它的前提是: 接手者不掌握此前会话的任何上下文, 必须仅凭本文就能继续施工
且不重复已完成的验证。凡是已经反编译或实测确认过的事实, 本文直接给出结论并标注证据来源, 不要再查一遍。

---

## 零 施工环境 (先照抄, 不要重新摸索)

工作副本是一个 git worktree, 不是主仓库:

```
worktree:   C:\Users\Xiaoxiao\AppData\Local\Temp\claude\<session>\scratchpad\integ
主仓库:     D:\Repo\Wok-Project
分支:       feat/job-systems-integration   (本地, 未推送)
基线:       origin/main = b1c54a7
```

构建工具链 (照抄, 本机默认 Java 21 + Gradle 9 构建不了本工程):

```bash
export JAVA_HOME="C:/Users/Xiaoxiao/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8"
cd <worktree>
./gradlew.bat --no-daemon compileJava          # 快速编译
./gradlew.bat --no-daemon runGameTestServer    # 全量 GameTest
```

新建 worktree 时必须先补依赖, 否则编译失败:

```powershell
cmd /c mklink /J "<worktree>\libs" "D:\Repo\Wok-Project\libs"
# PR#8 带来的独立子模组另需一份 tacz jar:
New-Item -ItemType Directory "<worktree>\standalone\kivotos-armorer\libs"
Copy-Item "D:\Repo\Wok-Project\libs\tacz-1.20.1-1.1.8-hotfix.jar" "<worktree>\standalone\kivotos-armorer\libs\"
```

### 施工必须避开的六个坑 (全部已踩过)

1. **跑 GameTest 前先 `rm -rf run`**。上一轮残留会让 `world/serverconfig/*.toml` 被占用, 报出与代码无关的假失败。
2. **严禁 `./gradlew.bat ... | tail -N`**。测试统计在日志中段, 被 tail 截掉后会误判成"没有测试执行"。必须整份落盘再 grep。
3. **Bash 工具里不要用 PowerShell here-string** (`-m @'...'@`)。Bash 不认, `@` 会进 commit message。多行提交用 `git commit -F - <<'EOF'`。
4. **GameTest 注解写错 namespace 会静默不执行**, 表现为假绿。新用例必须照抄同文件既有形式:
   `@GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)`
5. **依赖缺席即假绿**: `FarmerGameTests` 里 Farmer's Delight 相关用例在该 mod 未加载时直接 `helper.succeed()`。
   dev 环境不装 FD, 所以那批用例根本不执行。新写的验证必须用原版方块 (原版小麦等作物同样登记在
   `FarmerHarvests.PRODUCE_BY_CROP` 中), 才能真正跑到。
6. **TACZ 在 dev GameTest 不加载** (`build.gradle` 里是 `compileOnly`)。任何直接 `new` TACZ 事件或调用其
   接口的测试都会抛 `NoClassDefFoundError`, 相关行为只能上测试服人工验证。

### 项目纪律 (违反即返工)

- 提交信息用简体中文 + Conventional Commits; **严禁任何 AI 署名** (`Co-Authored-By` 等)。
- 全库**零 Emoji**, 包括代码、注释、提交信息、文档、测试用例。注意 `U+2726` 这类装饰星标也算; 但 `U+2192`
  箭头与 `U+00A9` 版权号属排版符号, 不算违规。
- 原子提交, 一个闭环一次提交。
- 严禁在 Bash 里用 `find` (本机 Git 自带 find.exe 有句柄泄露)。用 Glob/Grep 或 PowerShell `Get-ChildItem`。
- 异常必须痛: 严禁 `orElse(0L)` / `?? 0` 之类在业务层掩盖空值, 严禁在业务函数内 try/catch 生吞。
- 严禁不可达的防御分支与永远为真的判断。

---

## 一 为什么要做这件事

同一笔不可分割的经济不变量此前跨越三个存储且没有共同事务:

| 数据 | 存储 | 落盘时机 |
|---|---|---|
| 钱包余额、双币操作账本、每日计数 | Minecraft SavedData (`miningdim_economy.dat`) | **最长 5 分钟后** |
| 跳蚤市场挂单/流水/待结款 | SQLite `miningdim_market.db` | 提交即落盘 |
| 开箱记录与皮肤归属 | SQLite `miningdim_cases.db` | 提交即落盘 |

**5 分钟这个数字是实测的**: `MinecraftServer.tickServer` 中 `if (this.tickCount % 6000 == 0)` 才触发
`saveEverything`, 6000 tick = 300 秒; `SavedData.setDirty()` 只置一个 boolean, 无异步无回调。Forge
1.20.1-47.3.0 **没有**把该间隔做成可配置项。

由此产生的不对称是**单向**的: SQLite 恒领先, SavedData 恒滞后。所以崩溃后只会出现"资产在、钱回滚了"
(玩家白嫖), 反方向是罕见分支。且该窗口套在**所有**经济写入上 —— 挖矿收入、市场成交、每日计数全部会
一起回滚, 而 SQLite 侧的皮肤、挂单、流水全部幸存。开箱只是这个系统性不对称最容易被主动利用的出口。

当前实现同时违反已定稿规格 `docs/服务器经济系统设计文档.md:244`:「扣钥匙 + 扣箱子 + 发皮肤 必须是单个
原子事务(要么全成、要么全回滚), 且幂等」。现状满足幂等, 不满足原子。

### 已否决的两条路 (不要再提)

- **每次改钱强制落盘**: `SavedData.save(File)` 内部是 `NbtIo.writeCompressed` → `new FileOutputStream`
  就地截断重写, 无临时文件、无 rename、无备份, 且 `catch (IOException)` 后仍清脏标记。把这个高危写动作
  从 5 分钟一次提到每笔交易一次, 很可能净负收益 —— 拿"崩溃丢 5 分钟"换"更大概率把整个钱包文件写坏"。
- **把资产迁回 SavedData**: 失去唯一约束、索引、事务查询与审计能力; 数据量增长后放大整个 NBT 的序列化
  成本; 且市场仍在 SQLite, 跨存储问题原样存在。

### 为什么现在做代价最低

`origin/main` 上**不存在** `caseopening` 包, 也**不存在** `bundleOperations` 与 `EconomyWalletData` 的
双币操作记录 (已用 `git grep` 在 origin/main 上确认)。即开箱系统与双币幂等机制都是本分支新增、从未上线,
真服存档里不可能有对应数据。上线后再迁移余额, 要处理在线切换、余额核对、回滚和历史操作兼容。

**唯一有存量数据风险的是跳蚤市场**: market 已在 `origin/main` 上 (20 个文件), 库文件 `miningdim_market.db`,
真服存档可能已有挂单/流水/待结款。

---

## 二 已完成的部分 (不要重做)

本分支相对 `origin/main` 共 51 个提交。与本计划直接相关的是最后一个; 其余是第一批与第二批的安全修复。

### 已落地的地基: `abe73fa feat(store): 建立统一 SQLite 连接与 schema 迁移设施`

新增 `src/main/java/com/miningdim/store/` 三个类:

- **`MiningDb`** — 统一库文件 `miningdim.db` 与连接管理。提供 `open(MinecraftServer)` /
  `openInMemory()` / `openAt(Path)` / `close(Connection)`。
  PRAGMA: `journal_mode=WAL`、**`synchronous=NORMAL`**、`foreign_keys=ON`、`busy_timeout=5000`。
  必须 `Class.forName("org.sqlite.JDBC")` 显式注册驱动 —— FML 模块化类加载下 JDBC4 的 ServiceLoader
  自动注册只在 JVM boot 层早期跑一次, 那时 game 层的 sqlite jar 尚未加载。
- **`SchemaMigrator`** — 基于 `PRAGMA user_version` 的迁移器。`migrate(conn, List<List<String>>)`,
  第 i 个迁移仅在 `user_version < i` 时执行, 全部迁移在单事务内完成, 失败整体回滚; 库版本高于代码
  支持版本时抛异常拒绝启动。另有 `userVersion(conn)` 与 `tableExists(conn, name)`。
- **`MiningStoreException`** — 非受检异常, 存储层内部不吞。

`MiningStoreGameTests` 四个用例 (batch = `store`) 全绿: 迁移按序应用且重复为 no-op、失败迁移整体回滚、
高版本库被拒、**已提交行在关闭连接重开后仍在**。最后一个是全项目首个真正验证"数据确实落进文件"的
测试 —— 此前所有 SQLite 测试都用 `jdbc:sqlite::memory:`, 而 `MarketDb` 自己的注释就写明
「`:memory:` 的 journal_mode 实际为 memory」, 即 **WAL 从未被任何测试覆盖过**。

### 关于 `synchronous=NORMAL` 的取舍 (已决, 不要改回 FULL)

WAL 模式下 NORMAL 仍保证**进程崩溃**后已提交事务不丢, 只有操作系统崩溃/掉电才可能丢最近几笔。本项目
要防的故障模型正是服务端进程崩溃。若用默认 FULL, 挖矿连锁一 tick 内数十次 faucet 入账 = 数十次 fsync,
直接卡死主线程。该档位由人类主控确认接受。

**建议**: 真服首次部署后跑一次 `PRAGMA synchronous;` 复核实际生效档位。

### 测试基线

当前 **798 个 GameTest 全绿**。此数字随阶段推进会变化, 每阶段结束都必须回到全绿。

---

## 三 待迁移数据的精确结构

来自 `src/main/java/com/miningdim/economy/EconomyWalletData.java` (一个 `SavedData` 子类), 四个 map:

| 字段 | 类型 | 含义 |
|---|---|---|
| `wallets` | `Map<UUID, PlayerWallet>` | 玩家 → 钱包。无记录视为余额 0 |
| `bundleOperations` | `Map<UUID, BundleChargeOperation>` | operationId → 双币幂等操作 |
| `dailyCharges` | `Map<String, DailyCharge>` | `playerId + "|" + dailyKey` → 当日已扣量 |
| `dailyFaucets` | `Map<String, DailyCharge>` | 同上, faucet 侧, 额外含小数余量 |

`PlayerWallet`: 两个 `long` 字段 `credit`、`azure`。方法 `balance/tryDebit/tryDebitBundle/credit/creditBundle`。
入账溢出必须用 `Math.addExact` 检出并抛 `EconomyException.Reason.BALANCE_OVERFLOW`, 不静默回绕。

`BundleChargeOperation` (record): `operationId`、**`domain`** (`EconomyOperationDomain` 枚举, 目前仅
`CASE_OPENING`)、`playerId`、`creditAmount`、`azureAmount`、`status` (`EconomyOperationStatus`:
`NONE/CHARGED/COMPLETED/REFUNDED`, 其中 NONE 不得持久化)。
注意: **无时间戳字段**, 因此当前既无法做 TTL 回收也无法审计定位。迁移建表时应补 `created_at`。

`DailyCharge`: `long amount`、`long dayStamp`、`double creditCarry`。
`creditCarry` 是 faucet 衰减主闸的小数余量 ("小额不被逐笔取整吞光"), 扣费侧不用该字段。
每日键构造: `playerId + "|" + dailyKey`。翻日时钟是 UTC epochDay, 与 `AbuseGuard.currentPlayerDayStamp`
同口径, 两者必须一致。

影响面: `IEconomyService` 有 10 个公开方法, `EconomyServices.economyService()` 的调用方分布在 **24 个文件**
(矿工/农夫/酿酒师/塔罗/特勤/厨师/婚姻/军械/市场/开箱/命令等)。

---

## 四 各阶段实施结果

每阶段独立可验证, 结束时必须: 编译通过 + 全量 GameTest 全绿 + 一次原子提交。

### 阶段 2 — 合库并导入旧库数据

**状态: 已完成 (`84acb5f`)。** 实际落点与计划一致, 另加一条计划外的防线: 旧库文件存在、无导入标记、
而统一库该组表已有业务行时直接拒绝启动 —— 无法判定是否会重复导入, 只能交人核对。

**目标**: `miningdim_market.db` 与 `miningdim_cases.db` 的表并入 `miningdim.db`, 旧库数据自动导入。

1. 把 market 与 caseopening 的建表 DDL 改写成 `SchemaMigrator` 的迁移列表 (版本 1 = 全部现有表)。
   现有表: market 侧 `listings` / `transactions` / `pending_payout` / `base_values`;
   caseopening 侧 `case_openings` / `skin_assets` 及三个索引。
2. 两处 DAO 改为接受外部传入的 `Connection` (现在各自持有连接), 由统一入口创建并注入。
3. **旧库导入 (人类已拍板采用此方案)**: 服务端启动时若检测到世界存档目录下存在
   `miningdim_market.db`, 则在单事务内把四张表的数据读出写入新库, 完成后在新库写一个导入标记
   (建议 `meta` 表存 `imported_market_at`), 保证只导一次。
   **旧库文件保留不删**, 作为回滚保险。
   `miningdim_cases.db` 同理处理, 但它从未上线, 正常情况下不存在。
4. 导入必须校验行数一致, 不一致则抛异常拒绝启动 —— 宁可不启动, 不可带着丢失的挂单开服。

**验证**: 新增用例构造一个含数据的旧格式 market 库文件, 启动导入后断言每张表行数与关键列值精确一致;
再跑一次导入, 断言标记生效、不产生重复行。

### 阶段 3 — 钱包与账本迁入 SQLite

**状态: 已完成 (`94f36b7`)。** 与计划的一处**刻意偏离**: 计划要求扣款用条件 UPDATE 在 SQL 内校验余额,
实现改为把钱包读出来交 `PlayerWallet` 算再写回。理由是货币不变量 (正数校验、余额充足、`Math.addExact`
溢出检出) 不该在 Java 与 SQL 两处各存一份, 改一处漏一处的代价是真钱; 读-算-写在单线程单连接单写者且
读写同事务的前提下没有竞态。该前提一旦变化 (多线程写账本), `SqliteEconomyLedger` 必须重做, 类注释已写明。

**目标**: `EconomyWalletData` 的四个 map 全部落 SQLite, SavedData 不再持有可写的余额副本。

建议表结构 (最终以实现为准):

```sql
CREATE TABLE wallets (
    player_id TEXT PRIMARY KEY,
    credit    INTEGER NOT NULL DEFAULT 0,
    azure     INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE bundle_operations (
    operation_id TEXT PRIMARY KEY,
    domain       TEXT NOT NULL,
    player_id    TEXT NOT NULL,
    credit_amount INTEGER NOT NULL,
    azure_amount  INTEGER NOT NULL,
    status       TEXT NOT NULL,
    created_at   INTEGER NOT NULL
);
CREATE INDEX idx_bundle_ops_player ON bundle_operations(player_id, domain);
CREATE TABLE daily_counters (
    player_id    TEXT NOT NULL,
    counter_key  TEXT NOT NULL,
    kind         TEXT NOT NULL,          -- CHARGE 或 FAUCET
    amount       INTEGER NOT NULL,
    day_stamp    INTEGER NOT NULL,
    credit_carry REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (player_id, counter_key, kind)
);
```

要点:

- 扣款必须用**条件 UPDATE 在 SQL 内校验余额充足** (`UPDATE wallets SET credit=credit-? WHERE
  player_id=? AND credit>=?`), 用受影响行数判断成败, 不要"先 SELECT 再 UPDATE" (那是竞态写法, 即使
  单线程也会让逻辑依赖调用顺序)。
- 保留 `Math.addExact` 溢出检查语义。
- **一次性引导迁移**: 首次启动若 SavedData 里存在旧数据且 SQLite 侧 wallets 表为空, 则整体搬迁并打标记。
  搬迁前后必须逐玩家断言余额、货币总量、操作条数精确一致, 不一致拒绝启动。
- 数据库异常时经济写入必须**失败关闭**, 严禁回退到 SavedData —— 留一个可写副本等于让不一致重新出现。
- `IEconomyService` 的门面签名尽量不变, 只换实现, 以免波及 24 个调用方。

### 阶段 4 — 高频 faucet 批量提交

**状态: 已完成 (`70ae7d2`)。** 计划把它定位成性能优化, 实施中发现它首先是**正确性缺陷**: `grantDaily`
的三步 (推进当日原始累计 / 推进小数余量 / 落账) 此前各自提交, 落账失败会留下"衰减档位推进了、钱没发"
的玩家。另: 计划里"数十次 fsync 卡死主线程"的说法在 `synchronous=NORMAL` 下并不成立 (NORMAL 下 WAL
提交不 fsync), 真实收益是**减少 WAL 写放大** —— 逐笔提交会把同一页反复追加进 WAL。

**目标**: 挖矿连锁不因逐笔事务卡死主线程。

`EconomyService.recordMinedOreDrops` 对每个产出物循环调 `settleOreSale`, 每次内部走
`grantDaily` → `recordFaucetGrant` + `creditFaucetWithCarry` + `credit` 三次写。连锁挖矿一 tick 内可达
数十次。做法: 一个 tick 内的经济写入合并进单个事务, 在 tick 末统一提交。

**验证**: 构造一次性大量产出的用例, 断言余额与每日计数结果与逐笔提交完全一致 (批量不得改变业务结果)。

### 阶段 5 — 开箱与市场单事务化

**状态: 已完成 (`4ff78dc` + `30bd213` + `b09fcaf`)。** 五个同源缺陷全部修掉。市场交付物品刻意留在事务
之外 (背包是无法并入事务的第三个存储), 这一缺口见第六节第 4 项。

**目标**: 让"扣钱 + 发资产"落在同一个 SQLite 事务内。

开箱事务边界:

```
BEGIN
  插入或校验 bundle_operations (带 domain 与金额指纹)
  条件扣减 wallets (SQL 内校验余额充足)
  写 case_openings 与 skin_assets
  bundle_operations 推进为 COMPLETED
COMMIT
```

崩溃在提交前两边都回滚, 提交后两边都存在, 白嫖窗口从结构上消失。

市场同理: 买家扣款、listing 状态、卖家入账或 pending_payout 必须同事务。

**必须一并修掉的既有缺陷** (调研中发现, 与本迁移同源):

1. **`MarketEngine.settlePendingOnLogin` 先删后发**: `drainPendingPayout` 在事务里 SELECT + DELETE +
   COMMIT **物理删除**行, 之后才 `grant`。崩溃落在两步之间 = 卖家离线收入**永久消失且记录全无**。
   这比开箱白嫖更不可挽回。改为"标记已发放"而非物理删除。
2. **开箱恢复只由登录驱动**: `caseopening` 包内没有 `ServerStartedEvent` 订阅, 玩家崩溃后不再上线则
   其 RESERVED/DEBITED 行永久悬挂。补启动期全量对账, 且**必须挂 `ServerStartedEvent` 而非
   `ServerStartingEvent`** —— 经济门面要到 `EconomySystem.onServerStarted` 才注入, 而 case 的 DAO 在
   `ServerStartingEvent` 就开了, 顺序反了。
3. **恢复失败不阻断新开箱**: `CaseOpeningService.open()` 从不查询 `recoveryAuditedPlayers`, 该字段名字
   像闸门实为备忘录。挂着未结清资产的玩家可无限开新箱, 每箱都在扩大不一致面。
4. **毒化行永久化**: `reconcileRefunded` 遇到 SQL REFUNDED + 经济 COMPLETED 时抛异常, 该行永不自愈,
   导致该玩家**每次登录都抛**, 且 `enforceMainHand` 被跳过。应落隔离状态 + 告警, 而不是靠抛异常表达
   "已隔离"。注意现有测试 `completedRefundConflictIsIsolatedWhileLaterRowsRecover` 把抛出行为固化为
   预期, 修复时需一并改该用例。
5. **`bundleOperations` 从不清理**, 终态记录永久累积。补 `created_at` 后可加 TTL 回收 (仅清终态,
   `CHARGED` 永不清)。

### 阶段 6 — 崩溃注入测试

**状态: 已完成 (`f7dbb73`), 但形态与计划不同。** 计划里的 `CrashHarness` 是为"钱在 SavedData"设计的:
用 `save(new CompoundTag())` 拍快照、崩溃时从快照重建。钱迁进 SQLite 后这套脚手架失去意义 —— 崩溃语义
变成"事务回滚"与"关闭连接重开", 两者都能直接构造, 不需要伪造。因此实施为: 真实文件库 + 真的关闭重开
+ 真实失败路径 (余额溢出、注入的事务末步失败), 外加计划要求的守恒律断言。计划里列的待覆盖场景 (离线
玩家被启动期对账捞到 / 毒化行不破坏后续登录 / 恢复未完成时新开箱被拒 / 市场待结款崩溃后不丢钱) 均已
各自成用例。

现有 `CaseOpeningGameTests` 的"持久化/崩溃恢复"用例**全部用内存 SQLite + 游离的 `new EconomyWalletData()`**
(从未挂进 `DimensionDataStorage`, 故 `setDirty` 无消费者, `save`/`load` 一次都没跑过), 崩溃是靠"不做某
一步"伪造的, 没有验证"真的写进去的东西真的丢了"。

建议建 `CrashHarness` 测试工具类:

- `snapshotMoney()` — `ledger.save(new CompoundTag())`, 语义等价于"发生了一次自动保存"。
- `crash()` — 钱从**快照**重建 (丢弃快照后的全部内存变更, 这正是硬崩溃的真实语义); SQLite 侧
  `close()` 后用**同一文件路径**重开 (唯一能证明 WAL 提交真的落文件的手段)。

断言必须是**守恒律**而非分项相等, 这样删掉恢复逻辑测试必挂:

```
初始CREDIT == 当前CREDIT + 已结清资产数 x creditCost
初始AZURE  == 当前AZURE  + 已结清资产数 x azureCost
```

对每个写入边界各跑一遍崩溃。另需覆盖: 从不登录的玩家能否被启动期对账捞到、余额被花光时的恢复、
毒化行不破坏后续登录、恢复未完成时新开箱被拒、市场 pending_payout 崩溃后不丢钱。

---

## 五 每阶段收尾检查单

1. `./gradlew.bat --no-daemon compileJava` 通过。
2. `rm -rf run` 后 `runGameTestServer` 全绿, 且**测试总数不低于上一阶段**。
3. 新增用例做一次**变异验证**: 临时移除被测逻辑, 确认相关用例确实失败, 再 `git checkout` 恢复。
   本工程此前两次变异验证都精确命中 (农夫守卫移除后恰好 2 条失败; 开箱短路恢复后恰好 1 条失败),
   这是判断"测试不是永远通过的弱校验"的唯一可靠手段。
4. 一次原子提交, 中文 Conventional Commits, 无 AI 署名, 零 Emoji。

---

## 六 尚未解决 / 需人类决策的遗留项

1. ~~同域内的残留敞口~~ **已随阶段 5 消失**: 钱与资产同库同事务后, 不可能再出现"账本有记录、资产库无
   对应行"的状态, 那条窄路径的前置条件不复存在。
2. **补扣款失败的业务策略 (未定, 需产品决策)**: 玩家崩溃后把回滚回来的钱花光, 恢复时扣不到款。
   三选一: 记欠账 / 收回资产 / 永久隔离。当前实现的行为是: 该资产被 `ownedAssets` 过滤掉 (拿不到手),
   开箱闸门也会挡住他开新箱, 但不会自动收回也不会记欠账 —— 停在"可用性冻结"这个中间态等人拍板。
3. **运维口径 (未写进部署文档)**: 数据库文件与世界存档必须同生共死, 禁止单独回滚其一。合库后风险面已
   收敛为单一文件 `miningdim.db`, 但备份必须覆盖它的 WAL 附属文件 (`-wal` / `-shm`)。
   另: 真服首次部署后应跑一次 `PRAGMA synchronous;` 复核实际生效档位是不是 NORMAL。
4. **市场交付与背包之间仍非原子 (已知缺口)**: 迁移解决的是"钱与数据库资产"; 市场交货写的是玩家背包 NBT,
   那是第三个无法并入事务的存储。当前顺序是先提交事务再交付, 最坏情况为"钱货两清但物品没进包"。
   要真正闭合需持久 outbox 或邮箱式领取, 不要误以为合库后市场就完全原子了。
5. ~~`docs/WebUI_Architecture_DesignSpec.md` 的两处描述与实现不符~~ **已回写** (:124 的"极小窗口"、
   :135 的单事务描述), 同批回写的还有 `docs/design_mindmap.md`、`docs/FarmingXP_Mod_DesignSpec.md`
   与 `docs/服务器经济系统设计文档.md` 里残留的 PostgreSQL 表述 —— 本项目从未引入 PG。
6. **真服验证 (未做)**: 以上全部结论来自 GameTest。旧库自动导入这条路径在真实存档上从未跑过 ——
   dev 环境不存在 `miningdim_market.db`。上线前应在测试服 (见 test-server-access) 用一份真实存档演练一次:
   确认导入行数一致、旧文件保留、二次启动跳过。

## 七 与本计划相关的既有事实索引 (避免重复求证)

| 事实 | 证据 |
|---|---|
| 自动保存间隔 6000 tick = 5 分钟, Forge 未做成可配置 | `MinecraftServer.tickServer` 反编译 |
| `SavedData.setDirty()` 只置 boolean, 无异步 | `SavedData.java` 反编译 |
| `NbtIo.writeCompressed` 就地截断重写, 无备份 | `NbtIo.java` 反编译 |
| `SavedData.save` 吞 IOException 后仍清脏标记 | `SavedData.java` 反编译 |
| `ServerStoppingEvent` 早于最终落盘 | `MinecraftServer` 停服路径反编译 |
| origin/main 无 caseopening 包与 bundleOperations | `git grep`/`git ls-tree` on origin/main |
| market 已在 origin/main (20 文件), 库名 `miningdim_market.db` | 同上 |
| 市场不用双币幂等机制, 靠自增 `long listingId` | 全库 grep 四个 bundle 方法调用方 |
| `:memory:` 库的 journal_mode 实为 memory, WAL 未被测试覆盖 | `MarketDb` 源码注释 + 新增重开测试 |
