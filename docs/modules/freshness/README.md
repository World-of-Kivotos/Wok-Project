# WOK-腐烂底座模块

模块键：`wok-freshness`

当前入口：`com.miningdim.freshness.FreshnessModule`

当前状态：五个切片已落地——① 腐烂时钟 + 物品保鲜章 + 类别与豁免判定；② 腐烂物终态 + 无需 mixin 的观察点；
③ 加工继承策略 + 三个原版 mixin 观察点（合成格、熔炉三件套、漏斗）与两份 mixin 配置；
④ 同种食物合并（核心 + 拾取入包 / 点击叠放 / 配方书追加三个入口）；
⑤ 开服标签自检 + 按小时统计环（保留 30 天）+ `/freshness` 命令。
类别标签工程的第三方条目、Shift 快移的合并入口、开服迁移、
FD 与 BnC/refurbished 兼容 mixin、物品栏保鲜条与粮仓联动按设计文档后续切片推进。

设计真源：[`../../Spoilage_Granary_DesignSpec.md`](../../Spoilage_Granary_DesignSpec.md)（14 项已按推荐拍板，
2026-10-15；P0 环境核实记录在该文末）。

## 1. 功能边界

本模块负责：

- **腐烂时钟**：UTC 挂钟裁决、停服/停机按整小时补偿、运行中挂钟跳变按「差值 − 1」补偿、5 分钟心跳与累计偏移。
- **物品保鲜章**：`MiningFresh` 物品数据的唯一读写入口（批次时刻、已停表小时、迁移标记、村民标记），
  以及「除新鲜度外数据相同」的合并比较器。
- **类别与豁免判定**：六个类别标签、豁免标签、模块登记的按物品数据豁免规则，以及「能吃但没标类别归熟食」的兜底。
- **腐烂终态**：`freshness_rot` 物品（64 一组、不能吃、无市场锚价），1 换 1 替换，碗和瓶按件退回。
- **结算**：`FreshnessSettle` 是全模块唯一的「盖章 / 换腐烂物」实现，所有观察点都调它。
- **观察点（无需 mixin 的那批）**：全局掉落修改器、掉落物进世界、玩家背包每 2 秒错峰扫描、登录、
  白名单容器开与关、右键方块 / 右键物品（含开吃）/ 与实体交互之前。
- **加工继承策略**：产物按原料里最差的剩余比例继承（取最差不取平均）、加热工序 +25%、封顶满保质期、
  向下取整且最少 1 小时、停表额度取原料里最大的（堵「冻满额度 → 做熟 → 再冻满」）。
- **mixin 观察点（原版目标，本模块自带必需配置）**：合成格（工作台 3×3 与背包 2×2 共用的静态方法）、
  熔炉三件套（原料过期就停工并换成腐烂物、出品按加热加成继承）、漏斗（每次搬运前结算自己那几格，不做合并）。
- **同种食物合并**：三条门槛（只差新鲜度、落在 1/8 保质期且至少 2 小时的窗口内、停表时间相同），
  剩余按件数加权平均（精确到分钟）且件数 × 剩余的总和不增加；入口为拾取入包（`Inventory` 两个注入点）、
  点击叠放（`ItemStackedOnOtherEvent`）与配方书追加（取最老的那批）。
- **开服标签自检**：逐条核对八项（腐肉/蜘蛛眼/金苹果/蜂蜜瓶必须豁免，小麦/农夫小麦必须是谷物，
  生牛肉必须是生鲜，面包必须是熟食），有一条不对就**自动退回试运行**并提醒在线 OP。
- **统计环与命令**：按小时记录「盖章 / 本该腐烂 / 真换掉」三列，保留 30 天；
  `/freshness stats|clock|dump|audit|stamp|strip`（OP）分别查计数、查时钟、导出「物品 → 类别」清单、
  巡查半径内**已加载**容器里还剩多少无章/已腐烂的易腐品（只读、不加载区块，给压缩抽屉与第三方
  银行/邮箱这类观察点覆盖不到的地方用）、
  给手上的东西盖章 / 去章（奖励模板与商店货架配置前必须先去掉章）。
- **总开关**：关闭 / 试运行 / 开启三档（安全阀）。
- 后续切片：营火 mixin；Shift 快移的合并入口；放置食物的**取份继承**（第三方盛宴那一路）；
- **给消费点用的查询**：`FreshnessCheck.isRottenNow` / `remainingMinutes` —— 把「**不腐烂的东西永远不算烂**」
  固定在一处（`shelfLife == 0` 的骨头、火药、下界之星直接拼 `isRotten` 会被算成「剩余 0 分钟」而整批拒收）。
  已接线：卖麦（先结算背包再计数、扣麦按剩余从少到多）与任务上交（已腐烂的不收；腐肉在豁免标签里，照旧能交）。
  酿酒台与市场随后。
- **村民货**：村民与流浪商人卖出的易腐食物在报价生成时打 `MiningFresh.v`（报价那一刻**不盖章**——
  报价是开服登记的，那时盖章玩家买到手就是烂的），老村民进世界时补一次；**到手那一刻**由结算那一条路
  把剩余压到 6 小时（幂等：只在剩余多于 6 小时时压，压过之后只会继续变少）。村民收作物不拦。
- **放置食物记录**：摆成方块的食物（蛋糕、派这类）按**区块附加数据**记一笔「位置 → 方块 id + 批次时刻 +
  已停表小时」，方块被拆掉（掉落生成）时按记录继承、继承完就忘；位置上的方块换过就不算（旧账不盖给后来者）。
  跟着区块存盘、跟着区块消失。记录只在服务端内存里维护，卸载时丢内存那一条。
  消费点拦截（卖麦/酿酒/任务/市场）；机器输出合并取最老；放置食物记录的**取份继承**（第三方盛宴那一路，
  随兼容分片接）；
  开服迁移；FD / BnC / refurbished 兼容 mixin（走可选配置）；类别标签工程的第三方条目；
  物品栏保鲜条与 WebUI 物品详情贡献者；粮仓在 `wok-job-farmer`。

本模块不负责：

- 粮仓的建筑、验收、启用、停表结算与共享——归 `wok-job-farmer` 的 `job/farmer/granary`。
- 职业等级与经验曲线——归 `wok-job-core`；储粮囤额度读的是农夫等级，只经职业门面读取。
- 经济口径（收购额度、挂单费）——归 `wok-economy` 与 `wok-market`；腐烂是**物品** sink，不进信用点真 sink。
- 酒与奖杯鱼这类「自带数据就豁免」的判定——归各自模块，它们向本模块登记豁免规则。

## 2. 当前所有权

### Java 源码

- `src/main/java/com/miningdim/freshness/`（40 个文件，含 `item/`、`mixin/` 两个子包与 8 个测试类）。

### 专属资源

- `data/miningdim/tags/items/freshness/{fresh,produce,cooked,root,grain,preserved}.json`
- `data/miningdim/tags/items/freshness_exempt.json`
- `data/miningdim/loot_modifiers/freshness_stamp.json`（并登记在共享索引 `data/forge/loot_modifiers/global_loot_modifiers.json`）
- `assets/miningdim/models/item/freshness_rot.json`、`assets/miningdim/textures/item/freshness_rot.png`
- `miningdim.freshness.mixins.json`（必需：原版目标）与 `miningdim.freshness.compat.mixins.json`（可选：第三方目标，
  当前为空，等 FD / BnC / refurbished 那批填）。两份都要同时登记在 `build.gradle` 的 `mixin{}` 块、jar 清单的
  `MixinConfigs` 与本模块的 `resourcePaths` —— 只改一处就是「开发能跑、正式服什么都没加载」。
- 语言键：`item.miningdim.freshness_rot`、`tooltip.miningdim.freshness.category.*`（共享语言文件按 key 前缀分属）

### 运行期产物

- `/freshness dump` 把「物品 → 类别」清单写到 `<存档>/miningdim/freshness-dump.txt`（不入库，供人工核对标签）。

### 配置文件

- `miningdim-freshness.toml`（SERVER，注册点 `freshness/FreshnessModule`）：`mode` 三档总开关、
  `[shelfLife]` 六类保质期、`[granary]` 停表上限与门槛、`[consume]` 村民货与市场上架门槛、`[merge]` 合并窗口。

## 3. 冻结的兼容身份

后续切片不得修改（改了会废掉旧存档或让同一批货叠不上）：

- 物品数据根键：`MiningFresh`；子键 `h`（批次时刻，IntTag）、`f`（已停表小时，IntTag）、`m`（迁移货）、`v`（村民货）。
- SavedData 名：`miningdim_freshness_clock`（主世界）。
- 物品 tag：`miningdim:freshness/{fresh,produce,cooked,root,grain,preserved}`、`miningdim:freshness_exempt`。
- mixin 配置名：`miningdim.freshness.mixins.json`（必需）、`miningdim.freshness.compat.mixins.json`（可选）；
  mixin 包：`com.miningdim.freshness.mixin`、`com.miningdim.freshness.mixin.compat`。
- 配置文件：`miningdim-freshness.toml`；计划中的命令根 `/freshness`。
- 腐烂物物品 ID：`freshness_rot`。

## 4. 依赖登记

### 必需依赖

| 模块 | 使用内容 |
| --- | --- |
| `wok-core` | `Subsystem`、`MiningConstants.MODID`、Forge 公共装配约定 |
| `wok-webui` | 设计文档预留：物品详情贡献者接口（`fresh.hours` / `fresh.rotten` / `fresh.untimed`），本切片尚未引用 |

### 可选外部联动

| 外部 MOD | 技术 ID | 规则 |
| --- | --- | --- |
| Farmer's Delight | `farmersdelight` | 未安装时本模块照常工作；加工继承的 FD mixin 走本模块的可选 mixin 配置，对准整合包实际装的 1.2.9 |

## 5. 当前结构问题

- 类别判定依赖物品 tag，第三方条目必须写进标签；「能吃但没标类别」的兜底会把漏标的食物按 48 小时处理，
  所以开服自检（后续切片）必须逐条核对设计文档点名的那些物品。
- `FreshnessCategories.shelfLifeHours` 直接读配置：配置未加载时取值会抛异常，调用方必须在服务端起好之后再用。
- 时钟的「现在」由挂钟现算，测试要用 `FreshnessClock.setOffsetHoursForTest` 注入并在 `finally` 复位；
  观察点用的则是主世界时钟 `FreshnessClockData.nowMinute(level)`，写测试夹具时必须按它现算，
  否则批次会被当成未来时间被 clamp 回去。
- **机器输出合并取最老还没做**：熔炉与合成格的产物只做继承盖章，原版「同物品同数据才并堆」在带章之后
  不会把不同批次并起来；这条与「同种食物合并」的核心算术已经就位（`FreshMerge.preferOlderBatch`），
  差的是把熔炉/厨锅的输出格也接上它。
- **Shift 快移还没接**：规格要求的 `AbstractContainerMenu.moveItemStackTo` 合并（整堆搬完就返回 true、
  没搬完用 ThreadLocal 在 RETURN 处合并 moved 标志）还没写；目前 Shift 快移仍按原版「完全相同才叠」，
  只差新鲜度的两堆不会被并起来。粮仓「一键存入」的合并入口随粮仓那一批。
- **合成格只锁了策略**：`CraftingMenuMixin` 的两个注入点由启动即编译进 jar 保证（`defaultRequire: 1`，
  注入不上直接崩服），但走模拟玩家的合成夹具还没写，列入同种合并的收尾。
- `FreshnessHooks` 用一个 `ThreadLocal` 在熔炉的 HEAD 与 RETURN 之间传原料快照（`burn` 成功后会先把原料扣掉，
  RETURN 时单件原料已经空了）。只在服务端主线程使用，用完即清。

## 6. 目标内部结构

```text
freshness/
  FreshnessModule.java      唯一公开装配入口
  FreshnessClock.java       纯逻辑时钟（可测）
  FreshnessClockData.java   主世界 SavedData（心跳 + 偏移）
  FreshnessData.java        物品章的唯一 writer
  FreshnessSettle.java      结算（盖章 / 换腐烂物），全模块唯一
  FreshnessProcessing.java  加工继承的纯策略（取最差、加热加成、封顶、下限、停表取大）
  FreshMerge.java           同种合并的纯核心（三条门槛 + 加权平均 + 配方书取最老）
  FreshnessHooks.java       mixin 适配层（薄）
  FreshnessMergeHooks.java  合并适配层（入包 / 点击叠放 / 配方书）
  FreshnessCategories.java  类别判定
  FreshnessExemptions.java  模块登记的豁免规则
  FreshnessCategory.java    六类枚举
  FreshnessConfig.java      服务端配置
  FreshnessObservation.java 无需 mixin 的观察点
  FreshnessStats.java       进程级计数（每小时排空）
  FreshnessStatsRing.java   按小时的统计环（保留 30 天，纯数据）
  FreshnessSelfCheck.java   开服标签自检（不通过就退回试运行）
  FreshnessCommands.java    /freshness stats|clock|dump|stamp|strip
  FreshnessLootModifier(s)  全局掉落修改器
  item/                     freshness_rot
  mixin/                    合成格 / 熔炉三件套 / 漏斗 / 背包入包 / 配方书（原版目标）
  mixin/compat/             计划：FD、BnC、refurbished（可选配置）
  client/                   计划：物品栏保鲜条、剩余数字、快捷栏提醒
```

## 7. 验证清单

当前五个测试类共 42 条 GameTest：

- `FreshnessGameTests`（batch `freshness_foundation`，12 条）
  - 时钟：首次观察、正常前进、停服按整小时补偿、运行中跳变补「差值 − 1」、挂钟回跳只增不减、测试偏移入口钳负值。
  - 物品章：整点盖章、`f = 0` 不写、数值是 IntTag、已盖章不变、剩余 = 批次 + 停表 + 保质期 − 现在、
    剩余 0 即腐烂、未盖章按整保质期算、钳制未来批次与超额停表、合并比较器只忽略 `h`/`f`、村民货到手 6 小时。
  - 类别与豁免：六个类别标签命中、腐肉与种子豁免、非食物不腐烂、能吃未标类别兜底熟食、模块登记豁免优先且不污染进程级登记表。
- `FreshnessSettleGameTests`（batch `freshness_settle`，5 条）
  - 结算：未盖章的当场盖章、不腐烂的一概不碰；试运行档只盖章不换、开启档 1 换 1；碗装食物按件退碗；
    容器级结算就地替换并顺手盖上其余；腐烂物的注册身份（不能吃、64 一组）与掉落修改器序列化器在位。
- `FreshnessProcessingGameTests`（batch `freshness_processing`，8 条）
  - 策略：取最差比例（并反证不是平均值）、加热 +25% 与封顶、向下取整与最少 1 小时、停表额度取原料最大、
    过期原料判定（未盖章不算过期、不腐烂的不参与）。
  - 适配层：漏斗钩子换掉过期食物；熔炉钩子拦下过期原料并把出品按加热加成盖章；
    漏斗在自己 tick 里真的执行了结算（这条是 mixin 注入生效的端到端证据）。
- `FreshnessMergeGameTests`（batch `freshness_merge`，10 条）
  - 加权平均与守恒：合并后剩余是两边按件数加权的结果，且「件数 × 剩余」的总和不增加；
    停表额度不变、批次按「加权平均有效批次 − 停表 × 60」反算。
  - 三条门槛：差正好一个窗口还算能合并、多一分钟就不合并、其它数据不同不合并、停表时间不同不合并、
    迁移货不和新货合并、未盖章与不腐烂的东西不参与；窗口随保质期缩放（2 / 4.5 / 6 / 21 小时）。
  - 两个原版注入用的口径：入包追加前对齐章（整堆塞得下才插手，件数仍由原版搬）、
    配方书追加取最老的那批；容器里只认能合并的那一格。
- `FreshnessStatsGameTests`（batch `freshness_stats`，7 条）
  - 计数器：看一眼不清零、排空拿到同一份、排空后归零；结算分别计入盖章 / 本该腐烂 / 真换掉三列。
  - 统计环：按窗口汇总、最新小时与占用格数、转一圈覆盖最老一格、存读一轮不变、旧存档缺键从空环开始、
    列长不对直接报错。
  - 自检：出厂标签必须让八项全过；换成假判定器时，腐肉/蜘蛛眼/金苹果不再豁免、生牛肉不腐烂都要报出来。
  - 命令：`/freshness`、`stats`、`clock`、`dump` 四个入口在真实命令派发器上跑通（dump 会真的落盘）。

每个切片至少执行：

1. `gradlew.bat compileJava`
2. `gradlew.bat verifyModuleRegistry verifyModuleBoundaries`
3. `gradlew.bat runGameTestServer`，确认日志出现 `All N required tests passed`（不信 Gradle 退出码）
4. 改动物品数据键、SavedData 名或 tag 名时，对第 3 节的冻结身份做文本回归