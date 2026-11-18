# WOK-腐烂底座模块

模块键：`wok-freshness`。配置入口：`com.miningdim.freshness.FreshnessModule`。

当前切片引入物品数据、类别判定、分钟精度时钟及其存档和统计数据结构。只注册服务端配置，`mode` 默认 `OFF`；时钟事件、观察点和玩家操作入口尚未注册，此时不会自动给物品盖章或替换物品。后续切片需要完成各入口、数据与回归验证后再启用。

设计背景见 [`../../Spoilage_Granary_DesignSpec.md`](../../Spoilage_Granary_DesignSpec.md)。该版本的设计文档仍标为 PENDING，不能据此宣称其 14 项已拍板或 P0 核实记录已合入。

## 当前代码边界

- `FreshnessClock` 用分钟维护挂钟观测和不回退的逻辑时间。开服时按最后观测分钟与当前分钟的差，补偿满整小时的停服时间；运行中跨多小时的跳变计入补偿，挂钟回拨不倒退逻辑时间。
- `FreshnessClockData` 保存分钟锚点、小时偏移、心跳和统计环。代码提供每五分钟心跳入口，但本切片尚未把它接到服务器事件。
- `FreshnessData` 读写 `MiningFresh` 物品数据；`FreshnessCategories`、`FreshnessExemptions` 和 `FreshnessCheck` 提供类别、豁免与是否腐烂的查询。
- `FreshnessStats` 和 `FreshnessStatsRing` 保存盖章、试运行过期及实际替换的统计；当前未接入运行时结算。
- `FreshnessMigrationHooks` 提供启动、区块与实体迁移的事件处理实现，`FreshnessPlacedFood` 提供放置食物记录存读；本切片不注册这些事件。
- `FreshnessCommands` 和类别自检的实现已加入，但尚未注册命令或启动自检。
- `FreshnessNested` 提供嵌套物品数据的读取、腐烂判断和盖章计算；市场等消费入口尚未接入。
- `FreshnessAudit.scan` 只遍历已加载区块中的容器并统计无章及已过期物品，当前尚未注册命令。
- `FreshnessMigration` 与 `FreshnessMigrationData` 提供旧物品迁移计算、NBT/容器处理和迁移状态存档；启动及区块钩子尚未接入。
- 加工继承和同种合并的纯策略及合并适配方法已加入；终态替换、观察点、自动合并入口、迁移钩子、客户端显示和粮仓尚未接入运行时。

## 数据与配置

物品数据根键为 `MiningFresh`；`h` 是按整点落章的批次分钟序号，`f` 是累计停表小时，`m` 和 `v` 分别标记迁移货和村民货。数值写为 IntTag；`f` 为零时省略。保质期来自服务端配置，不写入物品章。

主世界时钟 SavedData 名为 `miningdim_freshness_clock`。当前写入版本 2：`Version`、`LastSeenHour`、`LastSeenMinute`、`LastNowMinute`、`OffsetHours`、`HeartbeatHour`、`HeartbeatMinute` 和 `Stats`。读取版本 1 时，小时精度的 `LastSeenHour`、`OffsetHours`、`HeartbeatHour` 转换为分钟锚点；版本 2 的分钟字段必需。仅版本 1 的旧存档缺少整个 `Stats` 时从空环开始；版本 2 缺失或损坏的 `Stats` 必须报错。

`miningdim-freshness.toml` 注册为 SERVER 配置，包含 `mode`、六类保质期、粮仓停表参数、村民与市场参数、合并窗口。`OFF` 为当前默认值；`DRY_RUN` 与 `ON` 的完整业务行为依赖后续入口接入。已有服务器配置可能覆盖默认值，升级时须检查实际配置。

专属资源为 `data/miningdim/tags/items/freshness/` 下六类标签与 `freshness_exempt.json`。粮仓业务归 `wok-job-farmer`；职业等级和经验归统一职业系统。

## 验证

`FreshnessGameTests` 当前声明 16 条 GameTest（batch `freshness_foundation`），覆盖：

- 时钟首次观测、正常推进、运行中跳变、停服补偿、跨小时不足一小时重启、版本 1 存档迁移、回拨不倒退及测试偏移入口。
- 整点盖章、剩余时间计算、到期判定、未来章与停表上限、忽略指定字段的物品比较、村民货剩余时间。
- 非易腐品查询、类别标签及模块登记的豁免规则。

`FreshnessProcessingGameTests` 的 5 条策略测试与 `FreshnessMergeGameTests` 的 10 条合并测试直接调用纯逻辑；原版注入与观察点用例随运行入口加入。`FreshnessAuditGameTests` 的 3 条测试覆盖容器统计、无问题内容和不加载区块；命令权限测试随命令入口加入。`FreshnessMigrationGameTests` 的 9 条测试覆盖迁移计算、容器、存档和区块标记等直接方法；事件接线测试随入口加入。`FreshnessPlacedFoodGameTests` 覆盖区块记录存读。`FreshnessNestedGameTests` 的 4 条测试覆盖嵌套读取、深度边界、畸形数据与盖章回写。

每层执行 `gradlew.bat compileJava verifyModuleBoundaries` 和 `gradlew.bat runGameTestServer`；GameTest 以日志中 `All N required tests passed` 为成功判据。
