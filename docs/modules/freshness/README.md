# WOK-腐烂底座模块

模块键：`wok-freshness`

当前入口：`com.miningdim.freshness.FreshnessModule`

当前状态：第一切片已落地（腐烂时钟 + 物品保鲜章 + 类别与豁免判定）；观察点、腐烂物终态、类别标签自检、
同种合并、开服迁移、WebUI 贡献者与粮仓联动按设计文档后续切片推进。

设计真源：[`../../Spoilage_Granary_DesignSpec.md`](../../Spoilage_Granary_DesignSpec.md)（14 项已按推荐拍板，
2026-10-15；P0 环境核实记录在该文末）。

## 1. 功能边界

本模块负责：

- **腐烂时钟**：UTC 挂钟裁决、停服/停机按整小时补偿、运行中挂钟跳变按「差值 − 1」补偿、5 分钟心跳与累计偏移。
- **物品保鲜章**：`MiningFresh` 物品数据的唯一读写入口（批次时刻、已停表小时、迁移标记、村民标记），
  以及「除新鲜度外数据相同」的合并比较器。
- **类别与豁免判定**：六个类别标签、豁免标签、模块登记的按物品数据豁免规则，以及「能吃但没标类别归熟食」的兜底。
- **总开关**：关闭 / 试运行 / 开启三档（安全阀）。
- 后续切片：全局掉落与拾取盖章、白名单容器与漏斗、加工继承、进食与消费点拦截、腐烂物终态、类别自检与统计、
  同种合并、开服迁移、WebUI 物品详情贡献者。

本模块不负责：

- 粮仓的建筑、验收、启用、停表结算与共享——归 `wok-job-farmer` 的 `job/farmer/granary`。
- 职业等级与经验曲线——归 `wok-job-core`；储粮囤额度读的是农夫等级，只经职业门面读取。
- 经济口径（收购额度、挂单费）——归 `wok-economy` 与 `wok-market`；腐烂是**物品** sink，不进信用点真 sink。
- 酒与奖杯鱼这类「自带数据就豁免」的判定——归各自模块，它们向本模块登记豁免规则。

## 2. 当前所有权

### Java 源码

- `src/main/java/com/miningdim/freshness/`（10 个文件，含测试类）。

### 专属资源

- `data/miningdim/tags/items/freshness/{fresh,produce,cooked,root,grain,preserved}.json`
- `data/miningdim/tags/items/freshness_exempt.json`

### 配置文件

- `miningdim-freshness.toml`（SERVER，注册点 `freshness/FreshnessModule`）：`mode` 三档总开关、
  `[shelfLife]` 六类保质期、`[granary]` 停表上限与门槛、`[consume]` 村民货与市场上架门槛、`[merge]` 合并窗口。

## 3. 冻结的兼容身份

后续切片不得修改（改了会废掉旧存档或让同一批货叠不上）：

- 物品数据根键：`MiningFresh`；子键 `h`（批次时刻，IntTag）、`f`（已停表小时，IntTag）、`m`（迁移货）、`v`（村民货）。
- SavedData 名：`miningdim_freshness_clock`（主世界）。
- 物品 tag：`miningdim:freshness/{fresh,produce,cooked,root,grain,preserved}`、`miningdim:freshness_exempt`。
- 配置文件：`miningdim-freshness.toml`；计划中的命令根 `/freshness`。
- 腐烂物物品 ID：`freshness_rot`（终态切片落地时冻结）。

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
- 时钟的「现在」由挂钟现算，测试要用 `FreshnessClock.setOffsetHoursForTest` 注入并在 `finally` 复位。

## 6. 目标内部结构

```text
freshness/
  FreshnessModule.java      唯一公开装配入口
  FreshnessClock.java       纯逻辑时钟（可测）
  FreshnessClockData.java   主世界 SavedData（心跳 + 偏移）
  FreshnessData.java        物品章的唯一 writer
  FreshnessCategories.java  类别判定
  FreshnessExemptions.java  模块登记的豁免规则
  FreshnessCategory.java    六类枚举
  FreshnessConfig.java      服务端配置
  command/                  计划：/freshness dump|stats|audit|stamp|strip
  observe/                  计划：掉落、玩家扫描、白名单容器、漏斗、加工出品、进食
  client/                   计划：物品栏保鲜条、剩余数字、快捷栏提醒
```

## 7. 验证清单

当前 `FreshnessGameTests` 有 12 条 GameTest（batch `freshness_foundation`），覆盖：

- 时钟：首次观察、正常前进、停服按整小时补偿、运行中跳变补「差值 − 1」、挂钟回跳只增不减、测试偏移入口钳负值。
- 物品章：整点盖章、`f = 0` 不写、数值是 IntTag、已盖章不变、剩余 = 批次 + 停表 + 保质期 − 现在、
  剩余 0 即腐烂、未盖章按整保质期算、钳制未来批次与超额停表、合并比较器只忽略 `h`/`f`、村民货到手 6 小时。
- 类别与豁免：六个类别标签命中、腐肉与种子豁免、非食物不腐烂、能吃未标类别兜底熟食、模块登记豁免优先且不污染进程级登记表。

每个切片至少执行：

1. `gradlew.bat compileJava`
2. `gradlew.bat verifyModuleRegistry verifyModuleBoundaries`
3. `gradlew.bat runGameTestServer`，确认日志出现 `All N required tests passed`（不信 Gradle 退出码）
4. 改动物品数据键、SavedData 名或 tag 名时，对第 3 节的冻结身份做文本回归
