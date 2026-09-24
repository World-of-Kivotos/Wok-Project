# WOK 模块依赖债务

目标依赖图保持无环。现有代码中无法在本次仓库整理提交内安全消除的反向引用，统一登记在 `module-registry.json` 的 `boundaryExceptions` 中。边界校验只允许这些已知组合，新增组合会直接失败。

`module-registry.json` 是债务的唯一真源，本表是它的渲染结果：

- 每条 `boundaryException` 必须带一个 `D###` 编号，`verifyModuleRegistry` 校验编号存在、格式合法且不重复。
- `verifyModuleBoundaries` 会比对本文件中出现的全部 `D###` 与登记表中的编号集合（活跃 + 已清偿），两边对不上就判失败。因此不会再出现「文档里有编号、登记表里查无此条」的幽灵债务。
- 每条例外的 `evidence` 字段列出触发它的全部源文件，校验器要求该清单与实际扫描结果完全一致：删掉最后一处引用后例外会被判为失效，新增一处引用而不更新 evidence 同样失败。想知道某个编号具体卡在哪几个文件上，直接查登记表的 `evidence`。

## P0：核心反向依赖

| 编号 | 当前引用 | 原因 | 退出方案 |
| --- | --- | --- | --- |
| D001 | 核心 -> 职业框架 | 入场玩家数据、入场网关和入场 WebUI 动作直接读写职业进度 | 把职业同步包和职业进度存取收敛为职业模块适配器 |
| D003 | 核心 -> 精英怪 | 主网络集中注册精英体型同步包 | 由精英怪模块注册固定 discriminator |
| D004 | 核心 -> WebUI | C2S 请求包与入场侧管理动作直接调用 WebUI Dispatcher | 核心只调用已绑定的 WebUI 请求处理契约 |
| D005 | 核心 -> 矿工 | `EntryGateway` 与入场 WebUI 动作调用 `MinerLevelGate` 做难度门槛 | 将难度门槛策略移入共享职业契约 |
| D006 | 核心 -> 塔罗师 | 易伤全局处理器直接读取 `TarotCombatState` | 由塔罗师绑定通用易伤免疫查询器 |
| D007 | 核心 -> 婚姻 | 中央 `ModItems` 构造婚姻戒指 | 婚姻模块自持物品 DeferredRegister，保持原 ID |
| D008 | 核心 -> 矿区 | 中央注册与入场包直接引用矿区方块、区块票和重置状态 | 注册项归还矿区；玩家数据与入场编排拆开 |
| D017 | 核心 -> 经济 | 入场收费网关与入场 WebUI 动作直接调用经济服务 | 入场扣费与余额查询走核心自持的端口 |
| D036 | 核心 -> 渔夫 | 核心拥有的 `ItemStackMiningDurabilityMixin`、`PlayerFoodExhaustionMixin`、`PlayerOreSoupStateMixin` 三个羹效果 Mixin，以及 `VanillaOreFishMixin` 与可选的 `mixin/compat/TideOreFishMixin` 直接调用渔夫实现 | 将羹状态和钓鱼兼容接缝收敛为核心接口或模块绑定。鱼种品质的名字颜色接缝已按此方向落地（`mixin/ItemRarityOverrideMixin` 只调用核心自有的 `core/ItemRarityOverrides`，渔夫注册解析器），不属于本条 `evidence` |

## P1：玩法与基础模块横向依赖

| 编号 | 当前引用 | 原因 | 退出方案 |
| --- | --- | --- | --- |
| D010 | 塔罗师 -> 矿工 | 隐士闪耀直接复用矿工扫描、包和高亮 | 矿区 API 提供通用矿物高亮能力，矿工负责实现绑定 |
| D011 | 酿酒师 -> 塔罗师 | 金酒最大生命帽直接读取 `TarotRuntime` | 战斗框架提供全局最大生命贡献查询 |
| D012 | 矿区 -> 精英怪 | 压力刷怪直接调用 `ChampionSpawnSeam` | seam 归矿区所有，精英怪模块反向绑定晋升实现 |
| D013 | WebUI -> 开箱 | 通用 WebUI 桥直接控制 `CaseSounds` | WebUI 提供通用客户端动作注册表，开箱自行注册动作 |
| D022 | WebUI -> 铸甲师 | 物品详情序列化读取铸甲师自有元数据 | 由各模块注册自己的物品详情贡献者 |
| D023 | WebUI -> 塔罗师 | 物品详情序列化读取塔罗师自有元数据 | 由各模块注册自己的物品详情贡献者 |
| D024 | WebUI -> 酿酒师 | 物品详情序列化读取酿酒师自有元数据 | 由各模块注册自己的物品详情贡献者 |
| D025 | WebUI -> 军火商 | 物品详情序列化读取军火商自有元数据 | 由各模块注册自己的物品详情贡献者 |
| D028 | 矿区 -> 电力 | `ore.OreType` 直接按 `power.mineral.PowerMineral` 拼矿表 | 电力模块把自己的矿物注册进矿区自持的矿物注册表 |
| D029 | WebUI -> 任务 | hub 状态直接读取 `QuestServices` 汇报未完成任务 | 任务模块向 WebUI 注册 hub 状态贡献者 |
| D034 | 矿工 -> 渔夫 | `MinerSystem` 直接读取 `OreSoupEffects` 应用矿石鱼羹挖掘速度加成 | 发布共享挖掘速度贡献查询，矿工不再依赖渔夫实现 |
| D035 | 厨师 -> 渔夫 | `ChefHungerHandler` 直接读取 `OreSoupEffects` 判断矿石鱼羹生效状态 | 发布共享食物效果状态查询，厨师不再依赖渔夫实现 |

## P2：资源锚点

| 编号 | 当前引用 | 原因 | 退出方案 |
| --- | --- | --- | --- |
| D015 | 开箱 -> 综合装配 | TaCZ 导出以 `MiningDim.class` 作为同 JAR 资源锚 | 改用开箱 Bootstrap 自身类作为锚点 |
| D016 | 军火商 -> 综合装配 | 枪匠 TaCZ 导出以 `MiningDim.class` 作为资源锚 | 改用枪匠 Bootstrap 自身类作为锚点 |

## P3：仅测试夹具

以下条目的 `scope` 均为 `test-only`：耦合只出现在 GameTest 里，不影响运行期装配。

| 编号 | 当前引用 | 原因 | 退出方案 |
| --- | --- | --- | --- |
| D014 | 精英怪 -> 铸甲师 | 精英基础 GameTest 复用铸甲师的固定随机数工具 | 工具迁入共享 `testutil` |
| D018 | 矿区 -> 矿工 | 陷阱 GameTest 直接验证矿工归属 | 共享陷阱-矿工夹具下沉到 `testutil` |
| D019 | 市场 -> 铸甲师 | 玩家 WebUI GameTest 引用铸甲师夹具 | 跨职业玩家夹具迁入共享 `testutil` |
| D020 | 市场 -> 酿酒师 | 玩家 WebUI GameTest 引用酿酒师夹具 | 跨职业玩家夹具迁入共享 `testutil` |
| D021 | 市场 -> 军火商 | 市场桥与玩家 WebUI GameTest 引用军火商夹具 | 在军火商实现之外发布市场物品详情夹具 |
| D026 | WebUI -> 市场 | 响应体积 GameTest 引用市场载荷夹具 | 通用响应夹具迁回 WebUI 模块 |
| D027 | 核心 -> 存储 | 共享 GameTest 工具 `TempStoreDb` 直接建 `MiningDb` 并套用 `MiningSchema` | 临时库工具迁入存储模块自己的测试支持包 |
| D030 | 任务 -> 农夫 | 任务物品奖励 GameTest 直接拿农夫物品当奖励载荷 | 跨模块物品奖励夹具迁入共享 `testutil` |
| D031 | 电力 -> 矿区 | 电力矿物 GameTest 断言电力矿物进入了矿区矿表 | D028 清偿后改为断言矿物注册表契约 |
| D032 | 电力 -> 矿工 | 电力矿物 GameTest 断言矿工扫描能看到电力矿物 | 改为断言矿区自持的扫描能力而非矿工实现 |
| D033 | 全服经验 -> 职业框架 | 经验 GameTest 经 `JobServices` 驱动职业轨道以覆盖完整路由 | 经验模块自己提供轨道夹具，测试不再触碰职业框架 |

## 已清偿

| 编号 | 原引用 | 清偿结果 |
| --- | --- | --- |
| D002 | 核心 -> 特勤干员 | 复核为幻影条目：对全部 Java 文件做全量扫描并双向 `git grep`，`com.miningdim.network`、`core`、`entry` 对 `com.miningdim.job.agent` 零引用。编号作废，不再复用。 |
| D009 | 职业框架 -> 农夫 | 新增 `JobXpPolicy`/`JobXpPolicies.register` 契约，由 `FarmerModule` 主动注册农夫曲线；职业框架已不再 import 农夫实现。 |

## 清债纪律

- 一个债务编号对应一个独立分支和提交，不与玩法数值修改混合。
- 删除代码引用时必须同步删除对应 `boundaryExceptions` 与本表条目；边界任务会把失效例外判为错误，编号集合对不上也会判错。
- 新增一处同向耦合时必须把文件补进该例外的 `evidence`；靠既有例外顺带放行新耦合会被校验器拦下。
- 不允许通过复制实现、反射吞异常或扩大核心模块依赖来"消除"校验错误。
