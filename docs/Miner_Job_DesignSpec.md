# 矿工 职业 Mod — 设计规格文档

## 文档元信息

- 用途: 矿工职业实现阶段的唯一架构与机制参考。所有规则以本文档为准, 不得凭记忆改写。
- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。
- 部署环境(硬约束): 公服初始血量 80、TACZ 枪械、死亡不掉落、PvP+PvE。战斗向一律守 %最大血量/不破 attrition; 本职业**全部技能不提供战斗力**(效率/生存向)。
- 强依赖: 本职业深度绑定 `docs/MiningDimension_Mod_DesignSpec.md`(单维度 + region 副本 + 三难度 + danger 压力 + 陷阱 + 经济反滥用), 是矿洞维度的"玩家进度层"。
- 前置: 复用职业框架两项前置(entry 玩家 Capability 的 `EnumMap<JobId,JobProgress>`、公共 menu/网络脚手架)。
- 状态图例: DECIDED 已定稿 / PENDING 待标定 / TODO 实现期补全。

---

## 一、职业定位与设计目标 (DECIDED)

1. 定位: **更高效、更快速地挖矿**(效率/速度向, 非风险/收益、非战斗)。
2. 等级 1-10: 技能**解锁后逐级变强**(成长式)+ 等级解锁**更高难度矿洞入场**。
3. 经验: 谁挖谁得(矿洞实例内挖矿), 复用全职业曲线(总 61,900)+ 每日衰减, 约一个月。
4. 反通胀北极星: 技能只让玩家"更快/更安全/更省事地挖到当天上限", **绝不抬高每日产矿上限**; 更高收益靠"高难度矿表 + 隐藏软上限计产出物", 产出仍封顶。
5. 数据并入 entry capability `EnumMap<JobId,JobProgress>`, 不新挂 capability。

---

## 二、成长模型 (DECIDED)

- **解锁后逐级变强**: 技能在解锁级后, 每升一级数值就涨(挖速 %、连锁块数、探矿半径、时运掉率…), 不是"每级开一个新技能"。每一级升级都有可见提升。
- **主动技能用长 CD 防膨胀**: 探测/连锁/隧道/脱险/降压 这些主动是"关键时刻按一下", 满级 CD 仍可观, 升级只是略快 + 威力/范围更大, 绝不变成"满屏连发"。
- **三类节流**: 主动(长 CD / 慢充能)、被动(纯成长, 自带数值封顶)、经济(隐藏软上限封顶产出)。
- 开关: 主动技能与"自动入包/熔炼"用 `KeyMapping` + C2S 翻 per-player 开关; CD/充能存服务端 per-player。

---

## 三、技能 · 探测类 (DECIDED, 开关/脉冲 + 长 CD, 服务端权威)

| 技能 | 解锁 | 解锁级 → L10 成长 | 离散里程碑 | 防滥用 |
| --- | --- | --- | --- | --- |
| 矿物探测 | L3 | 半径 6 → 16 格;CD **300s → 180s** | 可探矿种: L3 铁/煤 → L6 +钻 → L8 +金/残骸 | 单矿种 + 脉冲 ~8s 熄灭 + 有限半径 + 长 CD 防 X 光; 服务端权威 `OreSystem.cachedPlacement`+`OrePlacement.oreAt`, 只下发球内确有该矿的坐标 |
| 陷阱探测 | L5 | 半径随级扩大;CD **240s → 150s** | L5 仅非致死 → L8 含致死(TNT/岩浆袋) | `TrapSystem.staticPlacement(id).trapAt(x,y,z)` + `TrapType.lethal()`; 矿/陷阱**拆两个技能**, 禁一次激活同给 |

---

## 四、技能 · 速挖类 (DECIDED, 开关 + 慢充能/长 CD)

| 技能 | 解锁 | 解锁级 → L10 成长 | 防滥用 |
| --- | --- | --- | --- |
| 连锁挖矿 | L2 | 充能池 16 → 48 块;**整池回满 ~5 分 → ~3.5 分**(用一阵→歇一阵) | **代码级硬白名单**(石/深板岩/凝灰岩/花岗岩/煤/铁/铜), 硬排除 `DIAMOND_ORE/DEEPSLATE_DIAMOND_ORE/GOLD_ORE/DEEPSLATE_GOLD_ORE/NETHER_GOLD_ORE/ANCIENT_DEBRIS` + 绿宝石; 连锁遇高价矿停在边界。**连锁每块仍逐块走经济计数**(回放 `AbuseGuard.recordMinedOre`), 绝不用 `destroyBlock` 绕过计数 |
| 隧道挖(满级) | L9 | 3×3 隧道一段, 仅普通方块;CD **30s → 20s** | 同硬白名单, 仅对普通方块 |

慢充能是连锁的主节流: 清完一段矿脉充能见底, 等几分钟回满, 天然防"连续清场把矿洞抽干"的膨胀。

---

## 五、技能 · 被动类 (DECIDED, 纯成长, 无 CD)

| 技能 | 解锁 | 解锁级 → L10 成长 |
| --- | --- | --- |
| 挖矿提速 | L1 | 矿洞内挖速 **+15% → +110%**(每级约 +10%, 封顶 110%, 不超急迫III等效) |
| 省耐久 | L1 | 不耗耐久概率 **5% → 30%**(封顶 30%) |
| 抗疲劳 | L4 | 免疫挖掘疲劳(里程碑, 与挖速同走 `PlayerEvent.BreakSpeed` 统一结算防互相覆盖) |
| 便利(偏好开关) | L2/L6/L8 | 自动入包(满则落地);自动熔炼(铁/铜→锭 1:1 不增量, 可关, L6 基础/L8 加金) |

`PlayerEvent.BreakSpeed`: 判维度 `MINING_LEVEL` + `regionAt!=null` 后按矿工等级 `event.setNewSpeed(*mult)`, 与挖掘疲劳/急迫天然叠乘。

---

## 六、技能 · 获取更多矿 (DECIDED, 时运 B + 隐藏软上限)

- 矿脉时运: 挖矿额外掉落(时运式), 额外掉落期望 **+8% → +50%**(逐级涨, 解锁 L4)。
- **方案 B**: 每日上限的计数从"挖了几块矿"改为"**产出的矿物个数**"(含时运额外掉落)。时运**对一切矿生效**, 但单日总产出仍被同一上限封顶——时运 = 用更少的块达到上限(效率), 不抬上限。
- **隐藏软上限**: **去掉"已达软上限"提示**(删 `EconomySystem` 的 `MiningErrors.notify(ECONOMY_SOFTCAP)`)。玩家不知道有上限, 表现为**收购价随当日产量悄悄递减**(现成 `收购价递减` base 0.97 静默生效)。玩家只感觉"今天挖太多卖不上价", 无撞墙、无红字。
- 透明度取舍(知悉): 隐藏 = 玩家可能不懂为何价跌, 但这正是"无形递减"设计目标, 避免"撞墙"挫败。

---

## 七、技能 · 生存类 (DECIDED, 支撑高难度, 守"不漂战斗力"红线)

| 技能 | 解锁 | 类型 | 解锁级 → L10 成长 | 守红线 |
| --- | --- | --- | --- | --- |
| 耐压(减 danger 累积) | L4 | 被动 | danger **时间项**累积速率 0.85x → **0.6x 封底**(不再低、不钳 0、不动难度基础压力 zoneTerm) | 只减"停留时间"带来的刷怪压力, 不减伤、不加战力 |
| 矿脉抗性(减陷阱伤) | L5 | 被动 | 仅"**陷阱专属来源**"(落石/陷阱岩浆/陷阱 TNT)减伤 **-10% → -35%** | 对怪/枪/玩家 TNT **零作用**(陷阱触发打专属 DamageSource 区分); 做不到精确区分则降级为"陷阱触发时给 0.5s 无敌反应窗" |
| 脱险归途(撤离/归位) | L7 | 主动·开关+长 CD | CD **8 分 → 5 分**, 读条 ~3s, **受伤/移动即打断** | 长 CD + 可打断 = 不能当 PvP 逃跑后门(仿结婚传送); 复用 `EntryGateway.resolveSpawn`/回退态 |
| 声东击西(降压窗口) | L9 | 主动·长 CD | 短时压制后方刷怪 ~数秒;CD **5 分 → 3.5 分** | 只压刷怪节奏几秒, 长 CD, 不免疫压力系统; 复用 `spawnFreeze` 机制 |

护栏(强制): **耐压 + 声东击西 同时满级也不能让矿工实质免疫压力系统**(danger 累积封底 0.6x + 降压窗口短+长 CD, 联合调参); 减伤**只认陷阱专属来源**, 否则即战斗减伤天赋, 直接砍。

---

## 八、难度门控 (DECIDED)

- 改 `EntryGateway.gateCheck`(现用 `player.experienceLevel`, MEDIUM=10/HARD=25)为读**矿工等级**: **L4 开 Medium、L8 开 Hard**, L1-3 仅 Easy。
- 更好矿表是高难度给的合法收益, 但产出仍受**隐藏软上限**封顶。这是"等级解锁更高难度"的落地。

---

## 九、等级与经验 (DECIDED)

- **谁挖谁得**: 挂 `BlockEvent.BreakEvent`, 落在矿洞实例 region 内(`regionAt!=null`)才算, 经验记给挖矿者 UUID。
- **曲线**: 复用全职业总 61,900(L1→2 3,300 … L9→10 12,200)+ 每日有效经验软上限衰减(0-2000 ×1.0 … 3800+ ×0.02)。
- **天数**: 同框架——休闲 ~30 / 正常 ~22 / 肝满 ~16 天(软底 ~15.5)。挖矿提速只缩短"打满每日上限的时间", 不抬天花板。
- **反挂机**: 沿用现有 AFK 冻结(`AbuseGuard.evaluateAfk`), AFK 态不计经验/不计产矿。

---

## 十、反通胀三道硬约束 (DECIDED)

1. **连锁/隧道/时运不得绕过计数**: 经济计数挂在"被产出的矿物"上(方案 B), 连锁连带破坏的每块产出**必须回放 `AbuseGuard.recordMinedOre`**, 严禁 `destroyBlock` 静默绕过。连锁/隧道用**代码级硬白名单**, 物理排除高价矿。
2. **探矿脉冲式防 X 光**: 单矿种 + 有限半径 + ~8s 熄灭 + 长 CD; 服务端只下发球内确有该矿的坐标, 客户端无从透视全图。
3. **守隐藏每日上限**: 时运/速度只让人**更快/更省块达到上限**, 不抬上限; 上限隐形(收购价递减), 计"产出物个数"。

---

## 十一、架构落地与要同步改的现有代码 (DECIDED)

- `MinerSystem implements Subsystem`, `MiningDim.registerSubsystems()` 追加一行; 跨子系统经 core 门面 + MiningServices, 不硬 import。
- 等级并入 entry capability `EnumMap<JobId,JobProgress>`(框架前置)。
- 开关用 `KeyMapping` + C2S(复用 `MiningNetwork`/`SelectZoneC2S` 范式); 探矿/陷阱高亮**新建一个 S2C 包**(现仅 4 个)+ 客户端 `RenderLevelStageEvent` 画轮廓。
- **要同步改的现有代码(实现期清单)**:
  - `EconomySystem.onBlockBreak`: 计数口径"块 → 产出物个数"(方案 B); **删 `MiningErrors.notify(ECONOMY_SOFTCAP)`**(隐藏上限)。
  - `EntryGateway.gateCheck`: 难度门控由 `experienceLevel` 改读矿工等级(L4 Medium/L8 Hard)。
  - `Danger.evaluate` / `MobPressureSystem.tickPlayer`: 加 job 系数入参, 矿工等级缩放**时间项 tWin 累积/衰减**(不动 zoneTerm、不钳 0)。
  - `TrapSystem`: 暴露"查询玩家附近静态/动态陷阱"只读访问器; 陷阱触发打**专属 DamageSource**(供矿脉抗性精确区分)。
  - 经济文档同步: 计数口径与隐藏上限要写进 `docs/服务器经济系统设计文档.md` / MiningDimension 经济章。
- 全数值进 config(`MiningServerConfig`), 硬编码即缺陷。

---

## 十二、可实现性结论 (Forge 1.20.1, 已核验真实 API)

| 模块 | 可实现性 | 关键 API(已核验) |
| --- | --- | --- |
| 挖速/抗疲劳 | 可实现 | `PlayerEvent.BreakSpeed`(维度+region 守卫) |
| 连锁/隧道 | 可实现但有坑 | `BlockEvent.BreakEvent` + BFS + `level.destroyBlock` + 逐块回放 `recordMinedOre` + 硬白名单 + 防重入 |
| 时运(方案 B) | 可实现 | `Block.getDrops`(含 fortune)计产出物个数; 改经济计数口径 |
| 自动入包/熔炼 | 可实现 | `BlockEvent.BreakEvent` + `RecipeType.SMELTING`(1:1) |
| 矿物探测 | 可实现 | `OreSystem.cachedPlacement`/`OrePlacement.oreAt` + 新 S2C + `RenderLevelStageEvent` |
| 陷阱探测 | 可实现 | `TrapSystem.staticPlacement(id).trapAt` + `TrapType.lethal()` |
| 减 danger | 可实现 | `Danger.evaluate` 加 job 系数(`TWIN_ACCRUE_PER_EVAL` 现为 private 常量, 须接线) |
| 难度门控 | 可实现 | `EntryGateway.gateCheck` 改读矿工等级 |
| 脱险归途 | 可实现 | `EntryGateway.resolveSpawn` 提取复用 + 读条可打断 + 长 CD |
| 矿脉抗性 | 可实现但有坑 | 陷阱专属 DamageSource(`LivingHurtEvent` 判来源); 区分不了则降级为反应窗 |

前置事实(已核验): 矿工等级/`EnumMap<JobId,JobProgress>` 代码里**尚不存在**(仅设计文档), 多个技能(减 danger/门控)的前提是先落地 job 等级。

---

## 十三、待确认实现项 (PENDING)

1. config 数值标定: 各技能成长曲线/CD/充能/时运掉率/danger 系数封底。
2. 隐藏软上限的透明度取舍最终确认(是否给极弱的间接暗示, 还是全无形)。
3. 矿商门路(收购价优待): 依赖外部经济插件读矿工等级, 本 mod 单独闭不了环——**列为可选羁绊**, 默认不做。
4. 矿脉抗性能否精确标记"陷阱专属来源", 否则降级为反应窗。
5. 时运方案 B 改经济计数口径对其它玩法(非矿工卖矿)的影响复核。

---

## 十四、实现期工作分解 (确认 PENDING 后展开)

前置(与职业共享): entry `EnumMap<JobId,JobProgress>`(落地矿工等级)。

矿工本体:
1. `MinerSystem` 子系统 + 等级读写 + 谁挖谁得经验结算(BreakEvent)+ 每日衰减入账。
2. 被动类: 挖速/省耐久/抗疲劳(BreakSpeed)+ 自动入包/熔炼(BreakEvent+SMELTING)。
3. 速挖类: 连锁(BFS+硬白名单+逐块回放计数+充能)+ 隧道挖。
4. 探测类: 矿物探测/陷阱探测(服务端查询 + 新 S2C + 客户端渲染 + 长 CD)。
5. 获取更多: 时运 B 计数口径改造 + 隐藏软上限(删提示)+ 经济文档同步。
6. 生存类: 耐压(Danger.evaluate 接 job 系数)+ 矿脉抗性(陷阱专属 DamageSource)+ 脱险归途(resolveSpawn 复用 + 读条)+ 声东击西(spawnFreeze)。
7. 难度门控: EntryGateway.gateCheck 改读矿工等级(L4/L8)。
8. 开关/CD: KeyMapping + C2S + 服务端 per-player CD/充能 + HUD。

测试断言示例: 连锁挖普通矿逐块计入产出计数(无绕过)、连锁遇钻石矿停在边界; 时运多爆的钻石仍受当日隐藏上限封顶(收购价递减, 无提示); 矿工 L4 可进 Medium、L8 可进 Hard、L3 进 Hard 被拒; 减 danger 满级 tWin 累积不低于 0.6x、zoneTerm 不变; 矿脉抗性对苦力怕/枪伤零减免。
