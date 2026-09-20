# 结婚系统 设计规格文档

## 文档元信息

- 用途: 结婚系统实现阶段的唯一架构与机制参考。所有规则以本文档为准, 不得凭记忆改写。
- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。API 名称以此版本为准。
- 部署环境(硬约束): 公服人均初始血量 80、装 TACZ 枪械(TTK 以秒计)、死亡不掉落、PvP+PvE。**结婚系统不提供任何战斗力**(本系统全部为社交/便利/外观功能), 故战斗向滥用面集中在"传送"与"共享背包"两处, 已分别设闸。
- 状态图例: DECIDED 已定稿 / PENDING 待选或待标定数值 / TODO 实现期补全。
- 前置依赖: 复用职业 spec 已规划的两项框架(`EnumMap<JobId,JobProgress>` 玩家数据重构所在的 entry Capability、公共 menu 脚手架 `com.miningdim.menu`)。共享背包/誓言墙类 GUI 走公共 menu 脚手架。
- 反小号说明: 本服有"白名单 + 审核群"准入机制, 已在社交层挡住凭空开小号(除非长期潜伏混入审核群, 属运营问题)。故机制层反小号做轻量纵深防御即可, 不做偏执设计。

---

## 一、系统定位与设计目标 (DECIDED)

1. 两玩家可结婚: 买"订婚戒指" -> 求婚并被接受 -> 双方在场办典礼 -> 典礼后订婚戒指变"结婚戒指"(场地预约见第三章, DEFERRED)。
2. 结婚后解锁: 共享背包(1-5 级, 婚龄解锁)、传送到伴侣(1-5 级)、及选定的情侣功能。
3. 配套离婚系统。
4. 全系统零战斗力增益(社交/便利/外观), 与 80 血枪服的硬核战斗解耦。
5. 反滥用四闸(第七章): 小号联姻、共享背包 dupe、传送+情报集火、结离再婚刷取。

---

## 二、核心流程总览 (DECIDED)

```
买订婚戒指(信用点) -> propose + accept 确立意向 -> 双方在场办典礼(各付一半结婚成本)
  -> 订婚戒指 NBT 改写为结婚戒指 + MarriageRegistry 登记 MarriageState
  -> 解锁: 共享背包 / 传送 / 情侣功能(按婚龄阶梯)
  -> (离婚) 冷却 + 清算 + escrow 公示期 -> 解除关系、回收夫妻态
```

---

## 二之二、交互入口与玩家命令表 (DECIDED)

玩家侧入口共三套, 三套读写同一份服务端权威状态:

**(1) `/marriage` 命令树**(真源 `marriage/MarriageCommands.java`)——买戒指/求婚/典礼/离婚的主入口:

| 命令 | 参数 | 作用 | 前置与成本 |
| --- | --- | --- | --- |
| `/marriage buyring` | 无 | 买一枚订婚戒指 | 扣 config `engagementCost` |
| `/marriage propose <target>` | `EntityArgument.player()` | 向对方登记订婚意向 | 双方均未婚 |
| `/marriage accept <proposer>` | `EntityArgument.player()` | 接受对方的求婚 | 对方须先 propose 你 |
| `/marriage reject <proposer>` | `EntityArgument.player()` | 拒绝指向自己的求婚 | 存在该 incoming 意向 |
| `/marriage withdraw` | 无 | 撤回自己发出的 outgoing 意向 | 存在自己发出的意向 |
| `/marriage wed <partner>` | `EntityArgument.player()` | 双方在场办典礼 | 意向已被接受 + 双方各持订婚戒指 + 各付一半 `weddingCost`(事务性) |
| `/marriage divorce` | 无 | 提交离婚, 进 escrow 公示期 | 扣 config `divorceCost`; 已在公示期中则幂等回执, 不二次扣费 |
| `/marriage divorce cancel` | 无 | 发起方在公示期内撤回 | 全额退款 |
| `/marriage divorce confirm` | 无 | 配偶提前确认, 使离婚立即生效 | 到期不确认也会自动生效 |

`divorce` / `cancel` / `confirm` 是**父子层级**(`divorce` 节点自身可执行, `cancel`/`confirm` 挂在它下面), 不是三条并列子命令。

**(2) WebUI 婚姻面板**(`marriage/MarriageWebUiActions.java` + `webui/src/pages/MarriagePage.tsx`)——7 条 action: `marriage.state` / `marriage.buyRing` / `marriage.propose` / `marriage.respond` / `marriage.wed` / `marriage.divorce` / `marriage.sharedInv`。

> **架构不变量(接线正确性的唯一判据)**: 面板与命令**必须共用同一份** `MarriageProposals` 与 `MarriageBackpackSessions` 实例(由 `MarriageSystem` 构造注入下发)。这条只能用**实例同一性**断言证伪——各自 `new` 一份时 action 照样注册成功、用例照样全绿(两侧都读同一份错表, 自洽), 而真服后果是"命令行求的婚在面板上永远看不见""离婚时关不掉对方正开着的共享背包窗口"(即第四章要堵死的并发 dupe 窗口)。

**(3) 戒指物品交互**(`PlayerInteractEvent.RightClickItem`, 仅主手)——只覆盖两项: 蹲下右键 = 远程开共享背包(第四章); 不潜行右键 = 起传送蓄力(第五章)。买戒指/求婚/接受/拒绝/撤回/办典礼/离婚三段**一律不能**由戒指交互发起, 只能走命令或面板。

---

## 三、戒指与典礼 (DECIDED)

- 戒指为自定义 `Item`。订婚/结婚态、身份防伪全靠 ItemStack NBT 盖章(仿塔罗 ownerUUID): `spouseUUID` / `marriageId` / `weddingDay` / `officiantId`(可选证婚人)。`appendHoverText` 显示双方身份, 防倒卖戒指给小号白嫖婚姻福利。
- 场地预约: **DEFERRED(未实现)**。当前典礼不绑定任何场地, 也没有登记场地坐标/时段的 `SavedData`; 典礼前置只有"意向已被接受 + 双方在场"。订婚意向表 `MarriageProposals` 是**瞬态不持久化**的内存表(服务端重启或任一方登出即作废, 重新 propose 即可), 刻意不给瞬态意向做 SavedData。
- 典礼成本(反小号闸之一): **双方各付一半信用点**, 走 `IEconomyService.tryCharge(player, Currency.CREDIT, amount)` 扣 Capability 余额。**严禁用 `economy.AbuseGuard.chargeItem`**——那扣的是物理物品(默认钻石), 语义完全不同(职业框架 spec 第三章已把"复用 chargeItem 花信用点"的说法点名为语义错位)。扣费须事务性: 先扣发起方那一半, 伴侣余额不足时把已扣的那一半 `grant` 退回、整单失败, 不留半成品。总价 config 键 `weddingCost`(默认 20000), 奇数总价由发起方多付 1(ceil), 伴侣付 floor; 数值本身仍待标定(第十二章条目 1), 建议偏高以提高小号联姻成本。
- 典礼成功: 服务端把双方戒指 NBT 从订婚改写为结婚, 在 `MarriageRegistry` 登记 `MarriageState`, 双方 Capability 写 `marriageId` 指针。

---

## 四、共享背包 (DECIDED)

- 交互: 蹲下 + 右键结婚戒指远程同开(`PlayerInteractEvent.RightClickItem` + `player.isShiftKeyDown()`), 双方可同时打开。
- 等级 1-5, 按**婚龄**阶梯解锁(每级 +容量/格数), 阶梯天数进 config。
- **内容白名单(黑名单)**: 禁止放入
  - 信用点/青辉石 —— 是 Capability 余额、非物品, 物理上进不来(自动排除);
  - 高级矿物(钻石/绿宝石/下界合金/远古残骸及其矿石方块);
  - 可上架皮肤(TACz 皮肤凭证);
  - 绑定装备(任何带 ownerUUID/绑定的装备, 杜绝互借神装)。
  - 允许: 黑名单未命中即放行(消耗品、普通材料、食物、任务道具、情书/纪念物)。
  - **判定形态**: 黑名单是硬编码静态谓词 `SharedBackpackWhitelist`(服务端权威, menu 的 `canPlaceItem` 与取放路径调用), **既不走 ItemTag 也不走 config, 服主不可调** —— 三类判据: 高级矿物的固定 `Item`/`Block` 集合(`BLOCKED_ITEMS`/`BLOCKED_BLOCKS`)、命名空间属 TACZ 系(tacz/cgm 等)且 id 含 `skin` 的皮肤凭证(以 id 子串识别, 不硬 import TACZ)、带 `OwnerUUID`/`SpouseUUID`/`MarriageId` 盖章 NBT 的绑定装备(按 NBT 键识别, 新增绑定物自动覆盖)。
  - **容器下钻**: 只判顶层物品的话, 把钻石/绑定装备整包塞进潜影盒即可绕过全部黑名单。故对容器内容物递归判一层(`MAX_CONTAINER_DEPTH = 1`: 容器本体判一次 + 内容物判一次; 原版禁止潜影盒套潜影盒, 一层即覆盖真实可达嵌套), 下钻额度用尽后视为放行。该逻辑与 `market.MarketTradeWhitelist#judgeContents` 是两份独立实现, 尚未收口成共用工具。
- **防 dupe(关键)**:
  - 服务端唯一权威 `Container` 实例; 内容唯一权威落 `MarriageState` 的 `NonNullList<ItemStack>`(NBT 编解码, 仿 `InstanceState` 的 ListTag)。
  - 所有远程开背包/取放经 `server.execute` 回主线程**串行**(对齐 `InstanceState` 注释: 并发集合不替代主线程串行写)。
  - 客户端 Menu 仅视图, 每次变更回服务端校验后再广播给另一个开着同一背包的客户端, **严禁两端各自对账**。
  - 打开期间任一方登出/踢线/掉线 -> 立即强制结算并关闭双方界面(仿 `AbuseGuard` 扣费竞态的"不可双花不可白扣"纪律)。
  - 大宗物品移动写审计日志。

---

## 五、传送到伴侣 (DECIDED)

发起方 A **右键(不潜行, 仅主手)**结婚戒指 -> 服务端校验(双方在线、同维度、不在 CD、双方当前静止) -> 进入蓄力 T 秒。蓄力是服务端状态机按 tick 推进(交互回调起、`ServerTickEvent` 推进), **与玩家是否按住按键无关**, 松手不取消; 只有下表三类事件会取消:

| 时点 | 发起方 A | 伴侣 B |
| --- | --- | --- |
| 蓄力开始 | 看到蓄力进度条 | 收到提示: actionbar「伴侣 [A] 正在传送到你身边… 保持静止接受, 移动/潜行取消」+ 提示音(可选屏幕倒计时) |
| 蓄力中 | 持续校验 | 持续校验 |
| 任一方移动 / 潜行 / 受到伤害 | 「传送已取消(原因)」 | 「传送已取消」 |
| 蓄力满 | 传送到 B 身边 + 音效/粒子, 进 CD | 「[A] 已传送到你身边」+ 音效/粒子 |

- **双方不动**: 蓄力期间发起方与伴侣双方都须静止, 任一方移动即取消。**伴侣保持静止 = 知情同意**(收到提示后, 走两步或潜行即拒绝, 不弹确认框)。
- **可打断**: 蓄力期间任一方受到伤害即取消(等于天然战斗锁: 挨枪传不掉)。
- **CD**: 成功后进冷却。
- 等级 1-5(婚龄): 只缩短蓄力 T / CD, **绝不取消"双方不动 + 可打断"**。
- 跨维度传送: **不支持**。发起时双方不在同一维度直接拒绝(`StartResult.DIFFERENT_DIMENSION`); 蓄力中任一方换维度立即取消(原因码 `dimension`); 伴侣处在矿洞维度时蓄力直接拒绝(`StartResult.SPOUSE_IN_MINING_DIM`), 提示走 `/mining enter`。理由: 直接把玩家传进矿洞维度实例会绕过 `EntryGateway` 的实例引用计数/重入闸/落点安全, 比单纯重入更严重。故本系统全程只在同维度内传送, 不调 `changeDimension`。
- 可实现性: B 提示用服务端 `serverPlayerB.displayClientMessage(component, actionBar=true)` + `level.playSound`, 基础版无需自定义网络包; 实时倒计时条可走现有 `MiningNetwork` S2C(仿 `TeleportResultS2C`)。传送用 `ServerPlayer.teleportTo`(同维度传到伴侣身边)。
- T / CD / 各级数值已接入 config(`teleportChargeSeconds` / `teleportCooldownSeconds`, 按等级 1..5 各一张列表, 见第九章键位对照表); 具体数值仍待标定(第十二章条目 1)。

---

## 六、离婚 (DECIDED)

- 服务端把 `MarriageState` 移出 `MarriageRegistry`(转历史表), 清两侧 Capability 的 `marriageId` 指针(`NO_MARRIAGE`), 回收戒指 NBT 与所有夫妻态(称号/HUD/buff/距离共鸣)。
- **三道闸**:
  1. 再婚冷却: `divorceCount` 递增 + `lastWeddingTick`(仿 `AbuseGuard` untilTick), 离婚后 N 天禁再婚, 冷却随离婚次数递增。
  2. 离婚成本 + escrow 公示期: 延迟生效、期间可撤销(仿取款 escrow)。
  3. 清算: 共享背包按"谁放入谁取回"流水分割; 一次性福利以"**双方 UUID 对 + 里程碑**"为去重键(换 marriageId 也不重发同一里程碑); 全程写审计日志。
- 数值(冷却天数/成本/公示期)已接入 config(`remarryCooldownDays` / `divorceCost` / `divorceEscrowHours`, 见第九章键位对照表); 具体数值仍待标定(第十二章条目 1), 其中公示期时长代码里仍明确标着待定。

---

## 七、反滥用四闸 (DECIDED)

| 闸 | 风险 | 防控 |
| --- | --- | --- |
| 小号联姻 | 跟自己小号结婚白嫖共享背包/传送等通道 | 社交层(白名单+审核群)主防; 机制层轻量纵深: 结婚成本(双方各付一半信用点)+ 离婚冷却 + 共享背包白名单 + 高级功能按婚龄阶梯解锁 |
| 共享背包 dupe | 双人远程并发同开/掉线竞态 = 物品复制(皮肤一个 dupe 市场归零) | 服务端唯一权威 Container + 主线程串行 + 客户端只视图 + 掉线强制结算关闭(见第四章) |
| 传送 + 情报集火 | 传送当逃跑/集火, HUD/死亡点/SOS 当雷达 | 传送套"双方不动 + 受伤/移动/潜行打断 + CD"(见第五章); 若选用情报类功能(HUD/SOS/死亡点), 只给粗维度不给精确坐标/凶手身份, 死亡点严禁作为传送目标 |
| 结离再婚刷取 | 反复结离刷一次性福利 + 离婚资产抢劫 | 再婚冷却递增 + 一次性福利"双方 UUID 对+里程碑"去重 + 离婚清算规则化 + escrow(见第六章) |

---

## 八、已选情侣功能 (DECIDED)

- **婚戒距离共鸣(手持戒指亮光)**: 手持结婚戒指时戒指发微光 + 偶发心形粒子; 伴侣越近越亮、越远越暗; 伴侣离线/不同维度/超出范围则熄灭成普通戒指。
  - 实现: 纯客户端渲染(用已加载的伴侣实体算距离), 基础版零额外网络包、零服务端逻辑、零滥用面。若后续选用"伴侣 HUD", 其 S2C 坐标包可让戒指做到"超视距感应方向"。

---

## 九、数据架构 (DECIDED)

婚姻是"双人关系"(两 UUID 绑定), 不能只靠 per-player Capability。仿现有 `InstanceState` 模式:

- `MarriageRegistry extends SavedData`(挂主世界 `DimensionDataStorage`): 持 `Map<marriageId, MarriageState>`, 启动重建, 保存序列化。
- `MarriageState`(数据载体): `marriageId` / `partnerA`,`partnerB` UUID(无序对, 谁先 propose 谁是 A, 业务上对等) / `marriedSinceTick` / 共享背包 `NonNullList<ItemStack>` + 按槽归属 `slotDepositors`(离婚"谁放入谁取回"清算用) / `claimedMilestones` 里程碑领取记录 / 离婚公示期三元组 `pendingDivorceInitiator`、`pendingDivorceFiledTick`、`pendingDivorceCost`。以下两类值**刻意不在本类存储**, 勿按旧版字段清单重新引入:
  - **`sharedInvLevel` / `teleportLevel` 不入库**: 二者是婚龄的纯函数, 由 `MarriageTuning.backpackLevel` / `teleportLevel` 按 `marriedSinceTick` 现算, 不落盘也不缓存。本文档早期版本确实列过这两个持久字段, 实现后证实它们全库零写入方——即"存档里躺着、自洽但错误的数据", 已删除。把等级重新存盘会让 config 阶梯调整对老存档失效, 属已修复缺陷, **严禁回潮**。
  - **`divorceCount` 不在本类**: 它落 `MarriageHistory`, 按**玩家**而非按关系持有——再婚冷却必须在关系解除之后继续生效, 存进 `MarriageState` 会随关系一起消失。原 `lastWeddingTick` 同理零写入方, 已删除。
- 玩家 Capability(并入 entry 的 `MiningPlayerData`, 不新挂 capability): `marriageId` 指针(`NO_MARRIAGE` 哨兵)+ `spouseUUID`。`PlayerEvent.Clone` 复制以跨死亡/换维度保留。
- `MarriageSystem implements Subsystem`, `MiningDim.registerSubsystems()` 追加一行; 跨子系统经 core 门面 + MiningServices, 不硬 import 他系统实现类。
- 所有规则数值进 `MiningServerConfig` ForgeConfigSpec(C6)。

### 9.1 config 键位对照表

落盘文件 `<world>/serverconfig/miningdim-server.toml` 的 `[marriage]` 段(真源 `config/MiningServerConfig.java`)。`MarriageTuning` **实时读取、严禁缓存**, 故改 config 即时生效:

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `engagementCost` | int(信用点) | 5000 | `/marriage buyring` 买订婚戒指的花费 |
| `weddingCost` | int(信用点) | 20000 | 典礼总价, 双方各付一半(奇数时发起方多付 1) |
| `backpackUnlockDays` | int 列表 | [0, 3, 7, 14, 30] | 第 i 项 = 解锁共享背包第 i+1 级所需婚龄天数; 1 级 0 天 |
| `backpackSlots` | int 列表 | [9, 18, 27, 45, 54] | 1..5 级各自暴露的格数; 容器恒 54 格, 等级只控可见子集(升级不丢物) |
| `backpackOpenRangeBlocks` | int(格) | 64 | 共享背包保持打开时伴侣的最大距离; 超距或跨维度自动关闭 |
| `teleportChargeSeconds` | int 列表 | [8, 7, 6, 5, 4] | 1..5 级各自的蓄力 T 秒 |
| `teleportCooldownSeconds` | int 列表 | [300, 240, 180, 120, 60] | 1..5 级各自的成功后 CD 秒 |
| `divorceCost` | int(信用点) | 10000 | 提交离婚的花费, 由发起方付 |
| `divorceEscrowHours` | int(小时) | 24 | 离婚公示期; 0 = 立即生效。**代码内仍标注数值待定** |
| `remarryCooldownDays` | int(天) | 7 | 再婚冷却基数; 实际冷却 = 基数 x (1 + `divorceCount`), 随离婚次数递增 |

- **传送等级与共享背包等级本期共用同一组婚龄阈值**(`backpackUnlockDays`), 即婚龄是唯一解锁尺度、两功能同步成长; 若日后要独立阶梯, 在 `MarriageTuning.teleportLevel` 分叉读独立 config。
- **婚龄口径**: `TICKS_PER_DAY = 20 * 86400`(服务器运行 tick, 非游戏日), `TICKS_PER_HOUR = 20 * 3600`; 婚龄、再婚冷却、离婚公示期同挂一条 overworld `getGameTime()` 轴。
- 上表登记的是**键与机制已落地**; 具体数值的最终标定仍是第十二章条目 1 的 PENDING。

---

## 十、可实现性结论 (Forge 1.20.1, DECIDED)

| 模块 | 可实现性 | 关键 API | 工作量 |
| --- | --- | --- | --- |
| 戒指物品 + NBT 盖章 + 典礼 | 可实现 | Item + NBT(spouseUUID/marriageId) + appendHoverText; 典礼经 `/marriage wed`(场地预约 DEFERRED, 无 SavedData) | 中 |
| 共享背包 | 可实现但最高危 | 公共 menu 脚手架 + 服务端权威 Container + 主线程串行 + 硬编码黑名单谓词(含容器下钻) | 大 |
| 传送(含提示) | 可实现 | 右键起蓄力(服务端 tick 状态机)+ ServerPlayer.teleportTo(仅同维度)+ displayClientMessage/playSound + 可选 S2C | 中 |
| 离婚 + 清算 + escrow | 可实现 | MarriageRegistry 移除 + 清算流水 + 审计 | 中 |
| 婚戒距离共鸣 | 可实现 | 纯客户端粒子/微光按伴侣实体距离 | 小 |
| 数据架构(Registry+指针) | 可实现 | SavedData + entry Capability 指针, 仿 InstanceState | 中 |

实现期红线: 共享背包的并发串行与掉线结算(dupe 防线); 传送的"双方不动+受伤打断"战斗锁; 离婚清算与一次性福利去重键(防刷取)。

---

## 十一、候选功能菜单 (PENDING — 待你勾选)

下列为头脑风暴产出、尚未选入的功能。推荐度已评估, 关键注意已附。勾选后补入正文。

战斗/协作向:

| 功能 | 推荐度 | 一句话 | 关键注意 |
| --- | --- | --- | --- |
| 战地拉起 | 推荐 | 伴侣致命一击变濒死倒地, 你长按拉起回 30-50%+短免疫窗 | 人级共享 CD、倒地仍可被打死、不回满 |
| 婚姻称号 | 推荐 | Tab/聊天/头顶挂「xx的丈夫/妻子」或情侣二字昵称 | 零战力、双方确认昵称、离婚收回 |
| 守护标记 | 谨慎偏推荐 | 主动把自己变伴侣人肉盾, 替挡有总额度挡满即停 | 与生命链接互斥、近身、真实掉血 |
| 共享出生点/家 | 推荐 | 婚后共享家、可设重生点 | 回家传送须套与传送伴侣相同的战斗锁 |
| 并肩光环 | 谨慎 | 同框给走位增益(免击退/微移速, 抗性≤I) | 不给输出、不叠抗性、拉开即消 |
| 双人合击 | 谨慎 | 双方蓄力放一次强易伤+短控(婚龄毕业级) | 分钟级共享 CD、易伤+100%封顶、控制 1-2s 且 PvP 减半 |
| 生命链接 | 不推荐(裸版) | 限时按%转移伤害给伴侣分摊 | 伤害循环硬 bug 风险, 做也要互斥+≤35%+长 CD |

社交/便利向:

| 功能 | 推荐度 | 一句话 | 关键注意 |
| --- | --- | --- | --- |
| 纪念日事件 | 推荐 | 婚龄周年 PvE buff + 纪念礼盒 | buff 只 PvE、红包进每日上限、spouseUUID 戳 |
| 戒指刻字 | 推荐 | 戒指 NBT 刻字+婚期+主婚人 | 限长+敏感词+双方确认、不可上架 |
| 誓言墙 + 婚龄榜 | 推荐 | 主城只读 GUI, 公开誓言+最长婚龄排行 | 真实婚龄榜本身是反小号叙事激励 |
| 伴侣状态 HUD | 谨慎 | 看伴侣在线/%血/战斗告警, 分级 | 高级别别给精确坐标/实例 id |
| 协作生产 | 谨慎偏推荐 | 伴侣在场时工程师校准容差↑/塔罗合成破碎↓ | 微调、双方非 AFK、产出进每日软上限 |
| 情侣周常 | 谨慎偏推荐 | 双人绑定任务奖青辉石+经验 | 须真协作(非各挖N次)、防双开刷 |
| 死亡点定位/SOS/共享路点 | 谨慎 | 找回阵地/呼叫/导航 | 只给粗区域、死亡点不可作传送目标 |
| 婚礼宾客红包 | 谨慎拆分 | 典礼围观给小额奖励 | 每账号每场每日封顶+盖戳, 或只给临时观礼称号 |
| 联名信用点钱包 | 不推荐(裸版) | 夫妻共同钱包 | 信用点免费转移=洗钱/归集后门, 做也只能严格 escrow+审计 |
| 夫妻店专属价 | 不推荐(裸版) | 配偶专属价/折扣 | 专属价=定向转移洗钱, 只保留常规手续费小折扣 |

情侣/外观向:

| 功能 | 推荐度 | 一句话 | 关键注意 |
| --- | --- | --- | --- |
| 亲密度系统 | 推荐(地基) | 共享"爱情值", 主动在一起涨, 解锁外观/称号/动作 | 只算双方非 AFK 同框时间(反小号/反挂机) |
| 蜜月期 | 推荐 | 婚后头几天亲密度加速+专属婚戒光辉 | 纯仪式感、零战力 |
| 情侣皮肤套装 | 推荐 | 成对外观, 接开箱/皮肤经济的非战力用途 | 走青辉石抽/开箱, 不加战力 |
| 婚戒升级外观 | 推荐 | 等级越高婚戒模型越华丽 | ItemProperties model predicate, 极简 |
| 情侣互动动作 | 谨慎 | 拥抱/牵手/亲吻/共舞(粒子+音效) | 原版做不了自定义姿势, 朴素版=粒子+音效; 真动作需动画库 |
| 共同宠物 | 谨慎 | 双 ownerUUID 共养宠物 | 纯陪伴, 不做战斗优势 |
| 情侣私聊 + 情书 | 谨慎 | 二人爱心私聊频道, 沉淀情书 | ServerChatEvent + 开关, 低风险 |
| 情侣回忆录/相册 | 推荐 | 只读 GUI 自动记大事(婚日/守护次数/周年) | 关系长期投入的情感留存 |
| 公开求婚 + 婚礼烟花 | 推荐 | 公开求婚(下跪+烟花+广播)+ 典礼烟花 | 制造全服叙事 |
| 守护战绩 | 推荐 | 拉起次数/并肩时长 情侣统计 | 给 romance 可炫耀数字 |

---

## 十二、待确认实现项 (PENDING)

1. 各项 config 数值标定: 结婚成本/离婚成本/再婚冷却天数/escrow 公示期/共享背包各级容量与解锁婚龄/传送 T 与 CD 与各级。(**键与机制均已落地**, 见 9.1 键位对照表并已带默认值; 此处 PENDING 的是**数值的最终标定**——`MiningServerConfig` 的 marriage 段注释亦仍标着待定, 公示期时长尤其明确写了 exact value PENDING。)
2. 候选功能菜单(第十一章)勾选。
3. ~~共享背包黑名单的精确 tag 集合(高级矿物/皮肤凭证/绑定装备的判定)。~~ **已实现, 移出待确认**: 落为 `SharedBackpackWhitelist` 的硬编码 `BLOCKED_ITEMS`/`BLOCKED_BLOCKS` 常量集合 + 盖章 NBT 键 + TACZ 皮肤 id 子串三类判据, 外加容器下钻一层(见第四章), **未走原版 Tag**。若日后要迁到 Tag 体系以便服主可调, 另开议题。
4. 是否引入玩家动画库(决定"情侣互动动作"能否做真姿势)。
5. 证婚人(NPC/管理员)机制是否启用(典礼盖 officiantId)。

---

## 十三、实现期工作分解 (核心机制已交付)

前置(与职业共享): `EnumMap<JobId,JobProgress>` 所在 entry Capability 扩 marriage 指针; 公共 menu 脚手架。二者均已就绪。

结婚系统本体(现状: 核心已落地于 `com.miningdim.marriage` 包与 `webui/src/pages/MarriagePage.tsx`; 仍待拍板的只有第十二章条目 1/2/4/5):
1. [x] `MarriageRegistry`(SavedData)+ `MarriageState` + Capability 指针 + Clone 复制。
2. [x] `MarriageSystem` 子系统 + 戒指 Item(NBT 盖章)+ 典礼流程 + 信用点扣费。场地预约 SavedData **DEFERRED, 未实现**(见第三章)。
3. [x] 共享背包(公共 menu + 服务端权威 Container + 主线程串行 + 黑名单谓词 + 掉线结算)。**第四章要求的"大宗物品移动写审计日志"尚未落地**(离婚清算侧已有审计日志, 背包取放侧还没有)。
4. [x] 传送(右键起蓄力 + 双方不动校验 + 伴侣提示 + 受伤/移动/潜行打断 + CD)。跨维度按"直接拒绝"落地, **不是**原计划的过 reentry gate(见第五章)。
5. [x] 离婚(移除 Registry + 再婚冷却 + 清算流水 + escrow + 审计)。
6. [ ] 婚戒距离共鸣(客户端粒子/微光)——**未实现**, `marriage/client` 目前只有共享背包的 Screen/Client 两个类。
7. [ ] (按勾选)候选功能逐项 —— 第十一章尚未勾选, 未展开。
8. [x] `/marriage` 命令树(见二之二), 是买戒指/求婚/接受/拒绝/撤回/典礼/离婚三段的主入口。
9. [x] WebUI 婚姻面板(`MarriageWebUiActions` 的 7 条 `marriage.*` action + `MarriagePage.tsx`), 与命令**共用同一份**瞬态表(实例同一性, 见二之二)。

测试断言示例: 共享背包并发同 slot 取放只出一份(无 dupe); 高级矿物/绑定装备放入被拒; 传送蓄力中受伤即取消; 伴侣移动/潜行即取消并双方收到提示; 离婚后 N 天内再婚被拒; 同一对 UUID 离婚再婚不重发同一里程碑礼盒。
