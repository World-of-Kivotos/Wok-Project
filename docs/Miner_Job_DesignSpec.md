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
| 矿物探测 | L3 | 半径 6 → 16 格;CD **300s → 180s** | 可探矿种: L3 铁/煤/铝土 → L6 +钻/硼砂/锡/银 → L8 +金/残骸/镍/铬/钨(能源矿随电力子系统接入探测, 分档依据同价值量级; 见 `OreScanService.allowedOres`) | 单矿种 + 脉冲 ~8s 熄灭 + 有限半径 + 长 CD 防 X 光; 服务端权威**扫真实世界**: 在 `ServerPlayer.serverLevel()` 上对探测球内逐 BlockPos 读方块态, 经 `OreType.fromBlock` 还原矿种(`OreScanService.scanWorldDetailed`), 只下发球内确有该矿的坐标。旧 `OreSystem.cachedPlacement`/`OrePlacement.oreAt` 路线已废弃(维度改 `minecraft:noise` 生成 + 原版 ore feature 后无生产调用方填表, 该缓存恒空 → 探矿永远空返) |
| 陷阱探测 | L5 | 半径随级扩大;CD **240s → 150s** | L5 仅非致死 → L8 含致死(TNT/岩浆袋) | `TrapSystem.staticPlacement(id).trapAt(x,y,z)` + `TrapType.lethal()`; 矿/陷阱**拆两个技能**, 禁一次激活同给 |

---

## 四、技能 · 速挖类 (DECIDED, 开关 + 慢充能/长 CD)

| 技能 | 解锁 | 解锁级 → L10 成长 | 防滥用 |
| --- | --- | --- | --- |
| 连锁挖矿 | L2 | 充能池 16 → 48 块;**整池回满 ~5 分 → ~3.5 分**(用一阵→歇一阵) | 2026-07-12 用户裁决**矿洞维度内连锁全放开**: 旧"枚举硬白名单 + 高价矿硬排除"双表已废除, 钻石/金/远古残骸/绿宝石用正确档位镐即可连带, 时运照算, **不存在"遇高价矿停在边界"**。连锁边界改由单一谓词 `ChainMiningEngine.chainable` 的四条判据决定: `MINEABLE_WITH_PICKAXE` 标签 + `tool.isCorrectToolForDrops` 档位足够 + `getDestroySpeed >= 0` 可破坏 + 无 BlockEntity; 扩散语义不变(仍只在"与起始块同种方块"上扩散, 即清矿脉)。反通胀由**连锁每块逐块走经济计数**(回放 `recordMinedOreDrops`)与统一 faucet 封顶单独承担, 绝不用 `destroyBlock` 绕过计数 |
| 隧道挖(满级) | L9 | 3×3 横截面沿朝向掘进 `TUNNEL_DEPTH` 格;CD **30s → 20s** | 复用同一 `chainable` 谓词(白名单已废), 仅对谓词放行的方块 |

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
| 矿脉抗性(减陷阱伤) | L5 | 被动 | 仅"**陷阱类环境伤来源**"减伤 **-10% → -35%**; 陷阱专属 DamageSource 未落地, 当前为降级实现: 按原版环境伤类型集合识别(`FALLING_BLOCK`/`FALLING_STALACTITE`/`FALLING_ANVIL`/`LAVA`/`IN_FIRE`/`ON_FIRE`/`HOT_FLOOR`/非玩家 `EXPLOSION`, 见 `MinerSurvival.isTrapSource`) | 对近战/远程/枪伤与玩家点燃的 TNT(`PLAYER_EXPLOSION`)**零作用**。**已知副作用**: 集合含非玩家 `EXPLOSION`, 故苦力怕/床/重生锚爆炸在矿洞 region 内同样被减伤(`MinerGameTests` 已断言并锁定此行为), 待 trap 子系统暴露专属源后收紧。第七章原给的"陷阱触发时给 0.5s 无敌反应窗"降级方案**已放弃未落地**(`MinerConstants.VEIN_RESIST_FALLBACK_IFRAME_TICKS` 是零引用遗留常量) |
| 脱险归途(撤离/归位) | L7 | 主动·开关+长 CD | CD **8 分 → 5 分**, 读条 ~3s, **受伤/移动即打断** | 长 CD + 可打断 = 不能当 PvP 逃跑后门(仿结婚传送); 复用 `EntryGateway.resolveSpawn`/回退态 |
| 声东击西(降压窗口) | L9 | 主动·长 CD | 短时压制后方刷怪 ~数秒;CD **5 分 → 3.5 分** | 只压刷怪节奏几秒, 长 CD, 不免疫压力系统; 复用 `spawnFreeze` 机制 |

护栏(强制): **耐压 + 声东击西 同时满级也不能让矿工实质免疫压力系统**(danger 累积封底 0.6x + 降压窗口短+长 CD, 联合调参); 减伤**只认陷阱类环境伤类型集合**(见上表该行), 排除一切战斗向来源(近战/远程/枪械/弹射物/玩家 TNT), 否则即战斗减伤天赋, 直接砍。

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

1. **连锁/隧道/时运不得绕过计数**: 经济计数挂在"被产出的矿物"上(方案 B), 连锁连带破坏的每块产出**必须回放 `recordMinedOreDrops`**, 严禁 `destroyBlock` 静默绕过。连锁/隧道**不再用硬白名单物理排除高价矿**(2026-07-12 裁决已废除双表, 见第四章), 高价矿的收益完全靠逐块计数并入统一 faucet 封顶来压住。
2. **探矿脉冲式防 X 光**: 单矿种 + 有限半径 + ~8s 熄灭 + 长 CD; 服务端只下发球内确有该矿的坐标, 客户端无从透视全图。
3. **守隐藏每日上限**: 时运/速度只让人**更快/更省块达到上限**, 不抬上限; 上限隐形(收购价递减), 计"产出物个数"。

---

## 十一、架构落地与要同步改的现有代码 (DECIDED)

- `MinerSystem implements Subsystem`, `MiningDim.registerSubsystems()` 追加一行; 跨子系统经 core 门面 + MiningServices, 不硬 import。
- 等级并入 entry capability `EnumMap<JobId,JobProgress>`(框架前置)。
- 开关用 `KeyMapping` + C2S; 网络侧**自建独立 SimpleChannel `MinerNetwork`**(`miningdim:miner`), **不复用** `MiningNetwork`/`SelectZoneC2S`——复用须改中央 `MiningNetwork.register` 追加 discriminator(集成阶段统一接线职责), 另开 channel 则 discriminator 独立自增, 不破坏既有次序。频道下挂 6 个包:
  - C2S: `MinerToggleC2S`(开关翻转 / 主动技能触发)、`MinerChainHoldC2S`(连锁按住续期心跳)、`MinerChainPreviewC2S`(连锁预览请求)。
  - S2C: `MinerHighlightS2C`(探矿/陷阱高亮坐标下发)、`MinerStatusS2C`(状态 HUD 瞬态同步)、`MinerChainPreviewS2C`(连锁预览高亮)。
  - 客户端一律 `RenderLevelStageEvent` 画轮廓。
- 连锁的**按住激活 + 预览高亮**交互(数值见 `MinerConstants`): 客户端按住期间每 `CHAIN_HOLD_HEARTBEAT_TICKS`(20 tick)发一次 hold=true 心跳续期, 服务端把 `heldUntilTick` 续到 now + `CHAIN_HOLD_GRACE_TICKS`(30 tick), 松开包立即置失效; 预览请求按 `CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS`(10 tick)兜底节流, 预览高亮存活 `CHAIN_PREVIEW_EXPIRE_TICKS`(15 tick)后天然自清(无需显式清空包); 状态 HUD 按 `HUD_STATUS_PUSH_INTERVAL_TICKS`(10 tick)节流推送。
- **要同步改的现有代码(实现期清单)**:
  - `EconomySystem.onBlockBreak`: 计数口径"块 → 产出物个数"(方案 B); **删 `MiningErrors.notify(ECONOMY_SOFTCAP)`**(隐藏上限)。
  - `EntryGateway.gateCheck`: 难度门控由 `experienceLevel` 改读矿工等级(L4 Medium/L8 Hard)。
  - `Danger.evaluate` / `MobPressureSystem.tickPlayer`: 加 job 系数入参, 矿工等级缩放**时间项 tWin 累积/衰减**(不动 zoneTerm、不钳 0)。
  - `TrapSystem`: 暴露"查询玩家附近静态/动态陷阱"只读访问器; 陷阱触发打**专属 DamageSource**(供矿脉抗性精确区分)。
  - 经济文档同步: 计数口径与隐藏上限要写进 `docs/服务器经济系统设计文档.md` / MiningDimension 经济章。
- 数值最终目标是进 config(`MiningServerConfig`); **当前阶段**因中央配置门面 `IMiningConfig` 为阶段0 定稿、不暴露 `miner.*` 键(理由见 `MinerConstants` 类注释), 全部数值集中在 `MinerConstants` 这一个常量类, 由 job.miner 包内各处(`MinerSkills`/`MinerLevelGate`/`MinerActions`/`MinerChargeState`/`MinerFortune`/`ChainMiningEngine`/`MinerSystem`/`OreScanService`/`TrapScanService` 等)直接读取, 逐级值由 `MinerSkills.lerpUnlockToMax` 单点解析。集成阶段把这些初值搬进 `MiningServerConfig` 后再统一改读配置门面。散落在 `MinerConstants` 之外的裸值仍视为缺陷。

---

## 十二、可实现性结论 (Forge 1.20.1, 已核验真实 API)

| 模块 | 可实现性 | 关键 API(已核验) |
| --- | --- | --- |
| 挖速/抗疲劳 | 可实现 | `PlayerEvent.BreakSpeed`(维度+region 守卫) |
| 连锁/隧道 | 可实现但有坑 | `BlockEvent.BreakEvent` + BFS + `level.destroyBlock` + 逐块回放 `recordMinedOreDrops` + `ChainMiningEngine.chainable` 单一谓词(白名单已废)+ 防重入 |
| 时运(方案 B) | 可实现 | `Block.getDrops`(含 fortune)计产出物个数; 改经济计数口径 |
| 自动入包/熔炼 | 可实现 | `BlockEvent.BreakEvent` + `RecipeType.SMELTING`(1:1) |
| 矿物探测 | 可实现 | `ServerPlayer.serverLevel()` 逐 BlockPos 读真实方块态 + `OreType.fromBlock` 还原矿种(`OreScanService`)+ `MinerHighlightS2C` + `RenderLevelStageEvent`;旧 `OreSystem.cachedPlacement`/`OrePlacement.oreAt` 已废弃(恒空) |
| 陷阱探测 | 可实现 | `TrapSystem.staticPlacement(id).trapAt` + `TrapType.lethal()` |
| 减 danger | 可实现 | `Danger.evaluate` 加 job 系数(`TWIN_ACCRUE_PER_EVAL` 现为 private 常量, 须接线) |
| 难度门控 | 可实现 | `EntryGateway.gateCheck` 改读矿工等级 |
| 脱险归途 | 可实现 | `EntryGateway.resolveSpawn` 提取复用 + 读条可打断 + 长 CD |
| 矿脉抗性 | 可实现但有坑 | 陷阱专属 DamageSource 未落地, 实走 `MinerSurvival.isTrapSource` 的环境伤类型集合近似识别 + 百分比减伤(非反应窗) |

前置已落地(截至 bd0c4588): `JobData` 持 `EnumMap<JobId,JobProgress>` 并挂在 entry capability(`MiningPlayerData.jobData`)下序列化; `EntryGateway.gateCheck` 已委派 `MinerLevelGate` 读矿工等级实现难度门控(不再读 `player.experienceLevel`); `MinerSystem` 已把 `MinerSkills.dangerTimeFactor` 绑入压力子系统的 job 系数, `Danger.evaluate` 的 `timeAccrueFactor` 入参已生效。本章余下条目的"前提未满足"表述一并作废。

---

## 十三、待确认实现项 (部分已裁决)

1. config 数值标定: **DECIDED**——各技能成长曲线/CD/充能/时运掉率/danger 系数封底已在 `MinerConstants` 定稿(集成期再迁 `MiningServerConfig`, 见第十一章), 逐级值统一由 `MinerSkills.lerpUnlockToMax` 解析。
2. 隐藏软上限的透明度取舍: **DECIDED**——取**全无形**, 不给任何间接暗示。原 `ECONOMY_SOFTCAP` 文案常量与对应 lang 词条已随此设计删除(`MiningMessages` 仅留一条说明性注释), 与第六章原文一致。
3. 矿商门路(收购价优待): **PENDING**——依赖外部经济插件读矿工等级, 本 mod 单独闭不了环, **列为可选羁绊**, 默认不做。
4. 矿脉抗性的"陷阱专属来源"标记: **DECIDED(走第三条路, 既非精确标记也非反应窗)**——trap 子系统未暴露专属 `DamageSource`, `MinerSurvival.isTrapSource` 按原版环境伤类型集合(落石/钟乳石/铁砧/岩浆/着火/炽热地面/非玩家爆炸)识别, 经 `MinerSystem` 接入减伤链走正常百分比减伤(封顶 35%)。已接受的副作用: 苦力怕等非玩家爆炸连带被减伤(见第七章)。`MinerConstants.VEIN_RESIST_FALLBACK_IFRAME_TICKS`(反应窗方案)零引用, 应连带清理或补注释标废弃。**后续可选再拍板**: 是否为苦力怕爆炸单独排除, 以真正做到"对爆炸零作用"。
5. 时运方案 B 改经济计数口径对其它玩法(非矿工卖矿)的影响复核: **PENDING**。

---

## 十四、实现期工作分解

前置(与职业共享): entry `EnumMap<JobId,JobProgress>`(矿工等级)——**已落地**, 见第十二章末尾。

矿工本体:
1. `MinerSystem` 子系统 + 等级读写 + 谁挖谁得经验结算(BreakEvent)+ 每日衰减入账。
2. 被动类: 挖速/省耐久/抗疲劳(BreakSpeed)+ 自动入包/熔炼(BreakEvent+SMELTING)。
3. 速挖类: 连锁(BFS+`chainable` 谓词+逐块回放计数+充能)+ 隧道挖。
4. 探测类: 矿物探测/陷阱探测(服务端查询 + `MinerHighlightS2C` + 客户端渲染 + 长 CD)。
5. 获取更多: 时运 B 计数口径改造 + 隐藏软上限(删提示)+ 经济文档同步。
6. 生存类: 耐压(Danger.evaluate 接 job 系数)+ 矿脉抗性(环境伤类型集合近似识别, 专属 DamageSource 待 trap 子系统)+ 脱险归途(resolveSpawn 复用 + 读条)+ 声东击西(spawnFreeze)。
7. 难度门控: `EntryGateway.gateCheck` 改读矿工等级(L4/L8)——**已落地**(委派 `MinerLevelGate.canEnter`)。
8. 开关/CD: KeyMapping + C2S + 服务端 per-player CD/充能 + HUD。

测试断言示例: 连锁挖普通矿逐块计入产出计数(无绕过); 下界合金镐可连带深层钻石(高价矿硬排除已废), 木镐对钻石因档位不足无掉落故不连锁; 时运多爆的钻石仍受当日隐藏上限封顶(收购价递减, 无提示); 矿工 L4 可进 Medium、L8 可进 Hard、L3 进 Hard 被拒; 减 danger 满级 tWin 累积不低于 0.6x、zoneTerm 不变; 矿脉抗性对近战/远程/枪伤与玩家 TNT 零减免, 但对苦力怕等非玩家爆炸有减免(与 `MinerSurvival.isTrapSource` 集合一致)。
