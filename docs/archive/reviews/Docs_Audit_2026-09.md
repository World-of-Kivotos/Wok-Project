# Wok-Project 全量文档审查报告

> ## 归档件 — 历史记录, 不是实现依据
>
> - **冻结日期**: 2026-09-20
> - **冻结基线**: `bd0c4588`
> - **现行真源**: 各文档自身。本报告的 380 条发现已在同一批次整改中落地,
>   整改结果就是 `docs/` 下各文档的当前内容。
>
> 本文是**整改前**的问题清单, 保留供追溯"为什么某段文字被改成现在这样"。
> 其中记录的"文档原文"绝大多数已不复存在。不要拿本文去核对现状。
>
> **本报告自身的已知局限**: 对抗复核只剔除了 5/359 条(存活率 98.6%), 偏高;
> 主控抽验了 6 条头部 Critical 全部成立, 但 Major 一档未逐条复验, 其中含噪声。
> 整改阶段由执行者逐条复验, 实际跳过 48 条(误报或越界), 跳过理由见各簇报告。

---
审查基线: main `bd0c4588`, 2026-09-20。覆盖仓库内全部 78 份 Markdown 文档。
方法: 17 个域并行审查, 每条发现逐条对照源码/资源取证, 再经独立复核剔除误报;
另有两名完整性批评者补充跨域与结构类发现。初审 359 条, 复核存活 354 条, 补充 26 条, 最终 380 条。

严重度定义: Critical = 照文档做事会直接出错或做出错误决策; Major = 明显误导但有旁证可纠; Minor = 小瑕疵。

## 分布

| 主题簇 | Critical | Major | Minor | 合计 |
|---|---:|---:|---:|---:|
| 矿区主文档与 TaskSpec | 17 | 18 | 0 | 35 |
| 经济 | 15 | 40 | 5 | 60 |
| 矿工与农夫 | 3 | 19 | 2 | 24 |
| 精英怪与特勤干员 | 6 | 17 | 2 | 25 |
| 军火商与枪匠 | 7 | 27 | 3 | 37 |
| 工程师与铸甲师 | 5 | 15 | 0 | 20 |
| 厨师 酿酒师 塔罗师 | 1 | 19 | 4 | 24 |
| 渔夫 | 0 | 8 | 5 | 13 |
| 电力 | 1 | 8 | 5 | 14 |
| WebUI | 2 | 24 | 4 | 30 |
| 模块治理 | 1 | 11 | 10 | 22 |
| 仓库根治理文档 | 4 | 16 | 1 | 21 |
| 婚姻 堆叠 职业框架 其它 | 7 | 19 | 4 | 30 |
| 其它 | 4 | 19 | 2 | 25 |
| **合计** | **73** | **260** | **47** | **380** |

| 类别 | 条数 |
|---|---:|
| A-文档与代码不符 | 135 |
| C-实现状态标错 | 70 |
| D-覆盖缺口 | 58 |
| E-状态过期 | 48 |
| B-文档互相打架 | 35 |
| F-结构问题 | 34 |

---

## 全部发现

## 矿区主文档与 TaskSpec

### `docs/MiningDimension_Mod_DesignSpec.md`

**[Critical] [A-文档与代码不符] 第六章"单 region 内垂直堆叠三难度子盒(384 高)"整套架构已被代码 R1/F088 修订取代, 不只是内存估算数字算错**

- 位置: 第 161, 486-497(4.2) 行
- 文档原文: L161 `| 内存:单实例 bitset | 约 256x384x256 bit ≈ 3.0 MiB | `BitSet` 扁平一维 | 仅生成期常驻,生成完落盘后可释放 |`, 4.2 末同样按 2.52e7 体素算 3.0 MiB
- 代码实际: region 高度是 192 不是 384, 真实体素数 256*192*256 = 12,582,912, bitset 约 1.5 MiB, 文档的估算与由它派生的 7.2.2 内存预算、19.3 峰值 12MB 门槛全部偏高一倍。(该条已随高度 192 的主因产生, 单列出来是因为 19.3/19.5 的压测门槛直接引用这些数。)
- 取证: src/main/java/com/miningdim/core/MiningConstants.java:58 `public static final int REGION_HEIGHT = 192;`
- 建议改法: 不能只是把 2.3/4.2/7.2.1/7.2.2/19.3/19.5 里的体素数与内存数从 384 换成 192 重算了事。必须先重写第六章 6.2/6.3/6.5(MiningDimension_Mod_DesignSpec.md 735-820 行一带): 废止"单 region 内沿 Y 垂直切三难度子盒"的 DECIDED 描述, 改为代码 R1/F088 的实际模型——三个各 256x192x256、XZ 平面并排、各自独享一个难度的固定常驻 region(对应 MiningConstants.java 的 EASY_CELL_X/MEDIUM_CELL_X/HARD_CELL_X 与 RegionGrid.fixedRegionFor), 另加 InstanceManager.allocate 走同一 192 高模型的动态私有/共享实例; 同步删除或重写十六章 `layer.easyMinY/mediumMinY/hardMinY/enforceOrdering`(2415-2420 行)这套已在代码里被删除的 worldY 子盒配置项, 并检查 8.3、22 章风险表里引用旧子盒模型的措辞。上述架构性改写落地后, 再按 192 * 256 * 256 = 12,582,912 体素、约 1.5 MiB 重算 2.3/4.2/7.2.1/7.2.2 的体素数与内存表, 并相应下调 19.3/19.5 的内存峰值门槛。

**[Critical] [C-实现状态标错] 第十二章动态实例分配模型在 R1 下全部不触发**

- 位置: 第 1694-1760(12.1-12.3), 1734 行
- 文档原文: 12.2 给出 allocatePrivate/allocateShared 两套算法与 ownerKey 复用表, 12.3 定义 globalCap/queueEnabled/queueTtlTicks 背压, 12.6 定义 emptyTtlTicks 空实例 GC 与 region free 复用, 全部标 DECIDED
- 代码实际: R1 固定区域模型下开服即预建恰好三个固定难度实例(每难度一个, shared=true, 常驻不 GC), allocate 只做路由, 不再动态新建/复用/背压; 旧的 findReusable*/createInstance/backpressureOrQueue/pollQueue/空实例 GC 代码虽保留但本模式下不触发。照文档理解实例治理会得出完全错误的运维结论(比如以为超 globalCap 会排队)。
- 取证: src/main/java/com/miningdim/instance/InstanceManager.java:45-50 `R1 固定区域模型: 开服时 (rebuildFromStorage 末) 预建恰好三个固定难度实例 (每难度一个, shared=true, 常驻不 GC)。allocate(player, difficulty) 路由到对应固定实例 (不再动态新建/复用/背压) ... 旧的动态分配/共享复用/容量背压机制 (findReusable* / createInstance / backpressureOrQueue / pollQueue / 空实例 GC) 代码保留但本模式下不触发。`
- 建议改法: 在第十二章(docs/MiningDimension_Mod_DesignSpec.md 第 1694 行"## 十二、实例生命周期、并发与持久化")开头加一段 R1 现状说明块,明确当前版本(对应 InstanceManager.java 类注释所述"R1 固定区域模型")的真实行为:开服时预建恰好三个固定难度实例(每难度一个,shared=true,常驻不 GC),allocate(player, difficulty) 只路由到 fixedInstanceFor(difficulty),不调用 12.2 的 allocatePrivate/allocateShared、不调用 createInstance、不触发 12.3 的 backpressureOrQueue/pollQueue、也不进入 12.6 的空实例 GC(lastEmptyTick 恒为 -1)。据此把 12.2(实例分配语义)、12.3(容量上限与背压)、12.6(引用计数与空实例 GC)三节标题的 "(DECIDED)" 改为 "(DECIDED,代码保留但 R1 模式下不生效)",并在小节正文首句补一句"以下算法为历史/未来动态分配路径的设计,当前 R1 固定区域模型不经过此路径"。同时新增一小节(建议编号 12.2a 或并入 12.1)描述固定实例的真实生命周期:预建时机(rebuildFromStorage 末)、shared=true 且常驻不 GC 的语义、通过持久化的 fixedInstanceId 认领(而非编译期几何比对,因 D3 滑动重置会整体挪动 region 坐标)、以及"只能被 ResetService 重置"而非玩家离场触发销毁的事实,让读者能对照 InstanceManager.java 的 fixedInstances/fixedInstanceFor/attachAllocationFuture 实际实现理解当前分配路径。

**[Critical] [A-文档与代码不符] SavedData.Factory 是 1.20.2+ API, 1.20.1 不存在**

- 位置: 第 1835, 1855 行
- 文档原文: L1835 `new SavedData.Factory<>(MiningSavedData::new, MiningSavedData::load),`; L1855 ``get` 必须用 `SavedData.Factory`(1.20.1 签名);任何字段修改后必须 `setDirty()`」
- 代码实际: 1.20.1 的 DimensionDataStorage.computeIfAbsent 是三参签名 (Function<CompoundTag,T> load, Supplier<T> create, String name), 根本没有 SavedData.Factory。照文档写会直接编译失败, 而文档还特意声明这是"1.20.1 签名", 属于会把人带沟里的硬错。
- 取证: src/main/java/com/miningdim/persistence/MiningSavedData.java:125-132 `1.20.1 的 computeIfAbsent 签名为 (Function<CompoundTag,T> load, Supplier<T> create, String name) —— SavedData.Factory ...` 与 `return miningLevel.getDataStorage().computeIfAbsent(MiningSavedData::load, MiningSavedData::new, DATA_NAME);`;src/main/java/com/miningdim/trap/TrapRegistry.java:30 `1.20.1 computeIfAbsent 三参签名 (load, create, name); SavedData.Factory 是 1.20.2+ 不可用。`
- 建议改法: 把 docs/MiningDimension_Mod_DesignSpec.md 12.5 节 L1832-1836 的代码块改为:     public static MiningSavedData get(ServerLevel miningLevel) {         return miningLevel.getDataStorage().computeIfAbsent(             MiningSavedData::load, MiningSavedData::new, DATA_NAME);     } 与 src/main/java/com/miningdim/persistence/MiningSavedData.java:130 保持一致;并将 L1855 改为「`get` 必须用 1.20.1 的三参 `computeIfAbsent(load, create, name)`;`SavedData.Factory` 是 1.20.2+ 才引入,本目标版本严禁套用;任何字段修改后必须 `setDirty()`,否则不落盘(评审常见漏点,标 Major)。」

**[Critical] [D-覆盖缺口] 重置流程文档(13.4 及全文档多处)仍描述已下线的"文件级删除+限速重生成"两阶段模型, 与代码现行的 slideRegion 滑动四阶段状态机完全不符, 且伪码引用的 MiningChunkGenerator 类已被删除**

- 位置: 第 2037-2069(13.4) 行
- 文档原文: L2037-2045 13.4 阶段一"文件级删除"+阶段二"用同一 instanceSeed 限速重生成", 伪码 `generateChunkFromBitset(cpos) // MiningChunkGenerator 查 bitset 填块`
- 代码实际: 实际重置是 D3 滑动: slideRegion 把实例整块滑到一块从未生成过的新世界坐标(由 SavedData 的世界 X 游标单向推进保证不复用), 旧坐标区块留在磁盘上不再访问, 不做任何删除也不做限速重生成。相关硬约束(相邻两代 region 间隔必须 > 最大视距 512 格、游标上限 25,000,000、约 888 天耗尽、X 越过 2^23 后 float 精度劣化)文档完全没有。
- 取证: src/main/java/com/miningdim/reset/ResetJob.java:24-27 `REGEN : 调 IInstanceManager.slideRegion 把实例整块滑到一块从未生成过的新坐标 (写回新 regionBox/seed 后直接置 READY —— 离线生成已下线, 维度走 minecraft:noise 按需生成)`;src/main/java/com/miningdim/core/MiningConstants.java:79-87 `SLIDE_SEPARATION_BLOCKS = 1024;` 与 `MAX_REGION_WORLD_X = 25_000_000;`
- 建议改法: 重写 13.4: 删掉"区块文件级删除"+"限速重生成"两阶段及已不存在的 `generateChunkFromBitset`/`MiningChunkGenerator` 伪码, 换成 ResetJob 实际的 UNLOAD(撤离玩家+释放全部 ChunkTicket+按 mode 定 targetSeed) -> REGEN(调用 IInstanceManager.slideRegion 把 region 整体滑到 MiningSavedData 游标分配的新坐标, 写回 regionBox/seed 后直接置 READY, 维度按 minecraft:noise 按需生成, 不存在限速重生成概念, 只逐 tick 轮询 genState 直到 isEnterable/FAILED/超时) -> SETTLE(清 liveMobs, 停留 MIN_SETTLE_TICKS) -> DONE 四阶段状态机; 同步删除或标注废弃 reset.maxChunksPerTick / reset.maxMillisPerTick / reset.deleteUseFileLevel 三个已失效配置项。补充四条硬约束到正文与第二十二章风险登记: (a) MiningConstants.SLIDE_SEPARATION_BLOCKS=1024, 相邻两代 region 间隔须大于原版最大视距 512 格; (b) MiningConstants.MAX_REGION_WORLD_X=25,000,000, MiningSavedData.allocateRegionOriginX 游标只增不减、越界直接抛 IllegalStateException 且绝不回绕复用旧坐标, 默认三难度重置节奏下约 888 天耗尽; (c) 世界 X 越过 2^23 后原版实体位置/渲染 float 精度开始劣化; (d) 旧坐标 region 的地形区块由 com.miningdim.reset.RetiredRegionGc 异步节流回收(每 100 tick 最多清 16 个区块, 逐区块从 .mca 摘除而非整文件删除, 因为多块 region 共享同一 32x32 区块的 .mca), 但只覆盖 world/region/, 不含 world/entities/ 与 world/poi/, 是延迟回收而非"不回收"。同时刷新第 22.3 节 R7("reset 前...才删区块")与第二十三章总体定位表("重置(region 区块重生成)")这两处仍沿用旧模型措辞的地方, 使全文档对重置机制的描述与代码一致。

**[Critical] [A-文档与代码不符] 13.4 整节(阶段二"限速重生成"叙述、ResetJob.tick 伪码与五个 reset.* 配置键)描述的重置机制已被 slideRegion 架构整体取代,配置键与算法在代码中均不存在**

- 位置: 第 2042, 2050, 2060-2069 行
- 文档原文: L2060-2069 重置参数表列出 `reset.maxChunksPerTick`(8)、`reset.maxMillisPerTick`(10)、`reset.countdownSeconds`(30)、`reset.retryDelayTicks`(600)、`reset.deleteUseFileLevel`(true)
- 代码实际: MiningServerConfig 的 reset 段只有 cooldownSeconds / requireEmpty / kickOnForceReset / confirmationWindowSeconds / autoResetHoursEasy / autoResetHoursMedium / autoResetHoursHard / autoResetWarnSeconds 八项, 文档那五个键一个都没有(限速重生成随离线管线一起下线, 倒计时走 autoResetWarnSeconds 且默认 60 秒而非 30)。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:227-244 reset 段只定义 `cooldownSeconds`/`requireEmpty`/`kickOnForceReset`/`confirmationWindowSeconds`/`autoResetHoursEasy`/`autoResetHoursMedium`/`autoResetHoursHard`/`autoResetWarnSeconds`;src/main/java/com/miningdim/reset/ResetJob.java:33-36 `限速契约: 离线生成管线已下线 (F021/F032) ... 本任务自身不做任何加载限速`
- 建议改法: 不能只削表:1) 删除 L2061-2069 的"重置参数"表,换成 MiningServerConfig.java:227-244 的真实 reset 段八键(cooldownSeconds/requireEmpty/kickOnForceReset/confirmationWindowSeconds/autoResetHoursEasy/autoResetHoursMedium/autoResetHoursHard/autoResetWarnSeconds,默认 60);2) 删除 L2038-2059 阶段二"限速重生成"整段文字与 ResetJob.tick() 伪码(pendingChunks/generateChunkFromBitset 等在代码中不存在);3) 改写为 ResetJob.java 类头注释(22-41 行)记载的真实状态机:UNLOAD(撤离玩家、释放 ticket、派生新 seed)→REGEN(IInstanceManager.slideRegion 把 region 滑到未生成坐标,直接置 READY,区块靠 ChunkTicketManager 按需 minecraft:noise 生成)→SETTLE(清影子态、广播 fireInstanceReset)→DONE,并显式加一句"离线生成管线已下线(F021/F032)",避免读者仍以为重置走文件删除+限速区块生成这条旧路线;4) L2060 附近"倒计时"口径统一改引用 reset.autoResetWarnSeconds(默认 60s),不要再写 30s 的 countdownSeconds。

**[Critical] [A-文档与代码不符] 14.4 难度门控数值口径整段过期, 且仍标 PENDING**

- 位置: 第 2160-2166 行
- 文档原文: ### 14.4 难度解锁门控(PENDING 待校验,给建议方案) ... | 等级门槛 | Easy 无门槛;Medium 需经验等级 >= 10;Hard >= 25 | 读 `player.experienceLevel` |
- 代码实际: 门控早已改成矿工职业等级 Easy L1 / Medium L4 / Hard L8, 经 JobServices 门面读取, 不再读 player.experienceLevel; 且这一档不是 PENDING 而是 DECIDED 并已落地。同仓较新的 TaskSpec_Mining_EntryFee.md:76 已经点名这处漂移 ("这是**过期文档口径**, 真实门槛是 `MinerLevelGate` 的 Easy L1 / Medium L4 / Hard L8"), 主文档却没跟。
- 取证: src/main/java/com/miningdim/entry/EntryGateway.java:312-320 "14.4 难度门控: 按矿工职业等级门槛 (Easy L1 / Medium L4 / Hard L8, 见 {@link MinerLevelGate})。集成阶段裁决 (Miner_Job_DesignSpec 第八章): 门槛口径从原版经验等级 (experienceLevel) 改为矿工职业等级" + `int minerLevel = JobServices.jobService().level(player, JobId.MINER); if (!MinerLevelGate.canEnter(minerLevel, difficulty))`; src/main/java/com/miningdim/job/miner/MinerLevelGate.java:17 "/** 进入指定难度所需的最低矿工等级 (Easy=1, Medium=4, Hard=8)。 */"
- 建议改法: 1) 将 MiningDimension_Mod_DesignSpec.md 第2160行标题的"(PENDING 待校验,给建议方案)"改为"(DECIDED,已落地)"；2) 第2166行整行替换为"| 等级门槛 | Easy L1;Medium 需矿工职业等级 >= 4;Hard >= 8 | 经 `JobServices.jobService().level(player, JobId.MINER)` 读矿工等级，阈值真源 `MinerLevelGate`/`MinerConstants` |"；3) 第2170行"所有阈值标 PENDING 待平衡校验"改为指向 MinerLevelGate/MinerConstants 为唯一真源，不再称 PENDING；4) 顺带修正 entry/GateResult.java 第5行同样过期的类注释(仍写 Easy 无/Medium L10/Hard L25)，改为与 MinerLevelGate 一致的 Easy L1/Medium L4/Hard L8 表述；可参照 docs/TaskSpec_Mining_EntryFee.md 第76行已有的措辞。

**[Critical] [A-文档与代码不符] 难度门槛文档写经验等级10/25, 代码是矿工等级4/8**

- 位置: 第 2160-2166 行
- 文档原文: ### 14.4 难度解锁门控(PENDING 待校验,给建议方案) ... | 等级门槛 | Easy 无门槛;Medium 需经验等级 >= 10;Hard >= 25 | 读 `player.experienceLevel` |
- 代码实际: 代码早已改成读矿工职业等级, 且阈值是 MEDIUM=4 / HARD=8, 与原版经验等级无关。该章仍标 PENDING, 但门控已落地并被 EntryGateway.requestEnter 强制执行。同仓 docs/Miner_Job_DesignSpec.md:90 写的才是真值(L4 开 Medium、L8 开 Hard), 两份文档同时存在, 谁先读到 MiningDimension 主设计文档谁就拿到错的口径。
- 取证: src/main/java/com/miningdim/job/miner/MinerConstants.java:166-167 `public static final int MEDIUM_MIN_MINER_LEVEL = 4;` / `public static final int HARD_MIN_MINER_LEVEL = 8;`; src/main/java/com/miningdim/job/miner/MinerLevelGate.java:18-24 `case MEDIUM -> MinerConstants.MEDIUM_MIN_MINER_LEVEL; case HARD -> MinerConstants.HARD_MIN_MINER_LEVEL;`; src/main/java/com/miningdim/entry/EntryGateway.java:52 注释 `// ---- 14.4 等级门槛: 改为委派矿工职业等级 (MinerLevelGate), 不再用原版经验等级常量 (见 gateCheck)。 ----`
- 建议改法: 将 docs/MiningDimension_Mod_DesignSpec.md 第2160行标题的"(PENDING 待校验,给建议方案)"去掉(该决策已在 Miner_Job_DesignSpec.md 第八章 DECIDED 并已在代码落地);第2166行表格行改为: `| 等级门槛 | Easy 无门槛;Medium 需矿工职业等级 >= 4;Hard >= 8 | 委派 EntryGateway.gateCheck -> JobServices.jobService().level(player, JobId.MINER) -> MinerLevelGate.canEnter, 不读 player.experienceLevel |`,并在表格后加一句"数值真源 MinerConstants.MEDIUM_MIN_MINER_LEVEL(=4)/HARD_MIN_MINER_LEVEL(=8),另见 Miner_Job_DesignSpec.md 第八章"。同时第2170行"所有阈值标 PENDING 待平衡校验"一句需删去或改为仅限"前置成就/入场券"两项未落地机制,避免继续掩盖已实现的等级门槛。

**[Critical] [A-文档与代码不符] instance.bufferChunks 文档默认值写 1,代码实际默认值为 2,且该差值曾在本仓库真实引发 region 重复新建与旧地形重叠故障**

- 位置: 第 2409 行
- 文档原文: L2409 `| `instance.bufferChunks` | int | 1 | 1..8 | region 间实心缓冲带宽度(区块),>=1(D1) |`
- 代码实际: spec 默认值是 2, 且 MiningConstants.BUFFER_CHUNKS=2 与之硬绑定(注释要求两者必须相等, 并由 REGION_GAP=BUFFER_CHUNKS*16 派生出既有存档依赖的 stride 288)。按文档的 1 去校准会把网格几何算错。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:150-152 `BUFFER_CHUNKS = b.comment("Solid buffer band width between regions, in chunks (>=1); must equal MiningConstants.BUFFER_CHUNKS").worldRestart().defineInRange("bufferChunks", 2, 1, 8);`;src/main/java/com/miningdim/core/MiningConstants.java:67 `public static final int BUFFER_CHUNKS = 2;`
- 建议改法: 16.2.1 表 instance.bufferChunks 一行的默认值由 1 改为 2,并在说明列补充:"必须与 MiningConstants.BUFFER_CHUNKS 相等,否则 region 网格 stride(SIZE+GAP)与既有存档几何失配"。同时应引用 src/main/java/com/miningdim/instance/InstanceManager.java:252-258 的注释,说明这不是理论风险:本仓库曾因 bufferChunks 默认值从 1 改到 2 后新旧几何不一致,导致老实例被误判"未认领"而重复新建、与已挖空地形直接重叠(对应分支复核 finding #1/#5/#8),代码为此专门写了 claimFixedByLegacyGeometry 兜底。鉴于该差值已导致过真实数据完整性故障,建议将本条严重度由 Major 上调为 Critical。

**[Critical] [C-实现状态标错] 17 章命令树大半没接进游戏(command 包未注册)**

- 位置: 第 2620-2662(17.2/17.4/17.5) 行
- 文档原文: L2625 `| `/mining status` | 无 | 0 | 玩家 | 显示自身 instanceId、难度、danger、region 信息 |` 及同表的 list/tp/kick/reset confirm; L2650-2655 17.4 二次确认双闸; 17.5 PermissionAPI 对接
- 代码实际: 线上唯一注册的命令树是 entry.MiningCommands, 只有 enter / leave / info / reset(含 all、reseed)。status/list/tp/kick/confirm/party 与 MiningPermissions 权限节点都在 command 包里, 而 command.CommandSystem 被主类刻意排除(避免 Brigadier 双根冲突), 即整包是死代码。按 17 章去服上敲 /mining status 会直接报未知命令。
- 取证: src/main/java/com/miningdim/MiningDim.java:36-38 `/mining 命令树以 entry.MiningCommands 为唯一权威 (匹配设计文档 14.1 DECIDED: enter/leave/reset/info, 且 enter 走 EntryGateway 真实传送)。command 包的 CommandSystem 是并行期产出的另一套 /mining (其 enter 仅 allocate 不传送, 与 14.2 不符), 不接入主类以避免 Brigadier 双根冲突。`;src/main/java/com/miningdim/entry/MiningCommands.java:44-70 只注册 enter/leave/info/reset
- 建议改法: 17.2 表按 entry.MiningCommands 真实树重写: enter <difficulty> [reseed] / leave / info [instanceId] / reset <instanceId> [reseed] / reset all, 且 reset 权限等级须由文档现写的 4 订正为代码实际硬编码的 2(entry/MiningCommands.java:33 OP_LEVEL=2); status/list/tp/kick/party/reset...confirm 各行标 REJECTED 或注明"仅存在于未接入主类的 command 包(死代码)"; 17.4 二次确认+冷却双闸与 17.5 PermissionAPI 节点对接(节点常量已在 command/MiningPermissions.java 预留但从未注册, 判定恒走 OP 回退)同样标注为未接线/未生效; 并在第十七章章首加一条说明: command.CommandSystem 未被 MiningDim.registerSubsystems() 装配, 整包对运行期零效果, 仅 entry 包的四条命令(含 reseed 而非 party)是唯一真实生效的 /mining 树。

**[Critical] [A-文档与代码不符] 17.2/17.4/17.5 描述的 reset OP level 4 + 二次确认 + 冷却 + PermissionAPI 机制均不存在于线上命令树,线上 entry.MiningCommands 实际是 OP level 2 且立即执行无任何确认/冷却(文档照抄的是从未接入主类的废弃并行实现 com.miningdim.command.*)**

- 位置: 第 2631, 2654 行
- 文档原文: L2631 `| `/mining reset <instanceId> confirm` | + `confirm` | 4 | 破坏性 | ...`; L2654 `| 权限门槛 | OP level 4(或 PermissionAPI 节点 `miningdim.command.reset`,见 17.5) |`
- 代码实际: 线上权威命令树里 reset 的门槛是 OP level 2, 比文档低两级。按文档去配权限组会把本该拦住的人放进来。
- 取证: src/main/java/com/miningdim/entry/MiningCommands.java:33 `private static final int OP_LEVEL = 2;`, 同文件 63 行 `.requires(src -> src.hasPermission(OP_LEVEL))`
- 建议改法: 不能只把文档 17.2 表(2631 行等)和 17.4(2654 行)的 "4" 改成 "2" 了事,需要按线上真实行为整体重写: 1) 明确当前生产命令树是 src/main/java/com/miningdim/entry/MiningCommands.java(由 MiningDim.java:109 唯一接入的 EntrySystem 注册),reset/reset all 权限为 OP level 2(entry/MiningCommands.java:33,63),且不存在二次确认字面量节点、不读取 resetConfirmationWindowSeconds、不接入 PermissionAPI,把 17.2 表、17.4 表、17.5 描述都同步改为如实反映这一行为,或明确标注"确认窗口/冷却/PermissionAPI 为未落地设计,当前不生效"。 2) 在文档里加一条批注说明 com.miningdim.command 包(MiningCommands/MiningPermissions/ResetConfirmations,含 LEVEL_RESET=4 与 confirm 字面量)是并行期产出但被 MiningDim.java 显式判定"不接入主类以避免 Brigadier 双根冲突"的废弃实现,防止后续验收或新人对照它误判为已上线行为。 3) 如果二次确认+冷却+OP4 仍是业务期望的最终形态,应作为一个明确的工程任务,把 command 包的确认/冷却逻辑迁移进 entry.MiningCommands 并接入配置读取,而不是继续放着两套互不相通的实现让文档与代码各说各话。 4) 审计日志(17.4 "每次 reset 记录执行者...")也应一并核实:entry/MiningCommands.java 的 reset()/resetAll() 现状里没有看到执行者身份写入日志(仅 LOGGER.info 記了 instance 数),需要单独核实并按同样口径同步文档或代码,但这已超出本条待核范围,建议另开一条 F 类/A 类发现单独提交。

**[Critical] [E-状态过期] 总结清单及第五/七/八/十二章通篇仍描述已被 F021/F032 判废删除的离线三阶段生成架构,全文档零处提及现行 minecraft:noise 方案**

- 位置: 第 3056-3081(24.1/24.2) 行
- 文档原文: L3060 `[x] 随机生成矿洞结构(离线三阶段算法 Skeleton/NoiseCarving/ConnectivityFix,D2)`; L3061 `[x] 矿道完全连通(连通性作最后一道闸,BFS 6-邻接,100% 可达断言,D4)`; L3073 `[x] 离线预生成代替运行时全局算法(D2 ...)`
- 代码实际: 离线三阶段管线与 ConnectivityFix 连通性闸门已随 F021/F032 整体删除, 地形改由原版 minecraft:noise 生成, 不再有任何"100% 可达"断言链路。这份清单是全文最容易被当结论引用的一页, 现在给出的是三个月前的状态。
- 取证: src/main/java/com/miningdim/worldgen/WorldgenSystem.java:19-22 `自定义 ChunkGenerator 与离线体素生成管线 (OfflineCaveGenerator/GenerationScheduler) 已下线 (F021/F032)`;src/main/java/com/miningdim/instance/InstanceManager.java:42-44 `实例登记即置 GenState.READY, 不再有独立的生成调度阶段`
- 建议改法: 不能只改 24.1/24.2 的三个勾选框。需要: (a) 在文档顶部或第七章开头加一条醒目的架构变更声明,注明"离线三阶段生成(Skeleton/NoiseCarving/ConnectivityFix)、OfflineCaveGenerator、GenerationScheduler 已于 F021/F032 整体下线,维度改为原版 minecraft:noise 按需生成,不再有全局 100% 可达 BFS 断言"; (b) 第七章标题"矿洞生成系统(离线预生成模型)"与其下全部小节标注为历史/已废弃(HISTORICAL/SUPERSEDED),不要直接删除以保留设计演进记录,但禁止让当前读者误当现行规格; (c) 第八章 OG-3、第十二章 11.2/生成时机等依赖 ConnectivityFix 的表述同步加废弃标注,并说明矿物铺设改由数据包 placed_feature 承接(据 OreSystem.java:60-61); (d) 24.1 三条与 24.2 中 D2/D4 相关条目由 [x] 改为 [ ],批注"已由 R1 固定三区域 + D3 滑动重置(vanilla-noise)取代,详见 InstanceManager.java 类注释"; (e) 24.2 补充新增决策项: R1 固定三区域模型、R2 难度=区域、D3 滑动重置(世界 X 游标单向推进)、R6 定时自动重置,并给出 InstanceManager.java 对应行号作为代码侧锚点。

**[Critical] [A-文档与代码不符] 维度高度全文按 384 编写, 实际 JSON/常量/运行期自检均为 192(不止 4.3/4.4, 另涉 2.3/4.2/6.1/6.2/6.5/7.2/16.2/19.3 共 10 余处)**

- 位置: 第 521, 547-548, 577 行
- 文档原文: L521 `| `logical_height` | 384 | 传送/区块逻辑高度 |`; L547-548 `"logical_height": 384,` / `"height": 384,`; L577 generator settings `"height": 384`
- 代码实际: 维度类型 JSON 与噪声设置 JSON 的 height/logical_height 都是 192, Java 常量 REGION_HEIGHT 也是 192, 且 InstanceManager 启动期会拿 ServerLevel.getHeight() 自检, 不一致直接抛。全文凡以 384 推导的 Y 区间/体素数/内存量(2.3、4.2、4.3、6.2、6.5、7.2)全部失效。
- 取证: src/main/resources/data/miningdim/dimension_type/mining.json:12-13 `"logical_height": 192,` / `"height": 192,`;src/main/resources/data/miningdim/worldgen/noise_settings/mining.json:14-15 `"min_y": -64,` / `"height": 192,`;src/main/java/com/miningdim/core/MiningConstants.java:58 `public static final int REGION_HEIGHT = 192;`(注释: 必须等于 dimension_type/mining.json 的 height)
- 建议改法: 以 src/main/resources/data/miningdim/dimension_type/mining.json 与 worldgen/noise_settings/mining.json 的 height=192(与 MiningConstants.REGION_HEIGHT=192 一致)为唯一真源, 逐处改正全部以 384 推导的内容(建议按行号收尾式核对, 不要只改发现里点名的三行): 1. 4.2 表: L493 REGION_HEIGHT 384→192。 2. 4.3 表: L520-522 height/logical_height/local_y 范围 384→192, 0..383→0..191; L509 体素估算 256*256*384≈2.52e7/3.0MiB → 256*256*192≈1.26e7/约1.5MiB。 3. 4.4 两份 JSON 样例: L547-548 logical_height/height 384→192; L577 settings.height 384→192; 并在 4.3 注明 height 唯一真源是 dimension_type/mining.json 与 noise_settings/mining.json 两处同值(与 MiningConstants.REGION_HEIGHT 三方一致, 由 InstanceManager 启动自检兜底)。 4. 2.3 表: L155、L161 的 256x384x256 改为 256x192x256, 3.0MiB 内存估算同步减半。 5. 6.1/6.2(L743/747/765): "-64..319 共 384 格"改为"-64..127 共 192 格", 三区垂直堆叠总预算随之改写(各区格数/隔层格数需重新分配, 不能只改总数)。 6. 6.5(L815-822): Easy/Medium/Hard 子盒 local_y 表与隔层 local_y 需按新的 0..191 区间整体重算(不是简单减半, 需保证子盒边界+隔层边距仍在 0..191 内且不重叠), 这是本次遗漏中风险最高的一处, 必须优先处理。 7. 7.2(L871/873/874/890/891/900): W*H*D 由 256*384*256=25,165,824 改为 256*192*256=12,582,912; bitset 由 25,165,824 bit≈3.0MiB 改为 12,582,912 bit≈1.5MiB; 连通分量临时缓冲(int[25.17M]≈96MiB / short[]≈48MiB)按新体素数同比减半。 8. 16.2(L2420)与 19.3(L2839)遗漏项: 分别把"min_y=-64,height=384"与"典型 256x384x256 region≈25.2M voxel≈3.0MiB bitset"改为对应的 192 版本。 9. 全部改完后, 用 rg "384" 对本文档做一次收尾复核, 确认无遗留(尤其警惕未出现字面 384、但由其派生的坐标/体积表格, 如本次发现的 6.5 local_y 表)。

**[Critical] [C-实现状态标错] 自定义 MiningChunkGenerator 类与 CHUNK_GENERATOR 注册已判废(F021/F032),文档仍以 DECIDED 描述其为已注册实现(4.4/5.1/5.3/7.8 节,7.8 节实际位于 L1118-1172,非 L1276-1307)**

- 位置: 第 566-583, 692, 1276-1307(7.8 全节) 行
- 文档原文: L568 `"type": "miningdim:mining_chunk_generator",`; L583 `generator.type` = `miningdim:mining_chunk_generator` 必须与第五章 RegisterEvent 向 BuiltInRegistries.CHUNK_GENERATOR 注册 Codec 时用的 ResourceLocation 完全一致; 7.8 整节以 DECIDED 规定 MiningChunkGenerator 的每个覆写点
- 代码实际: dimension/mining.json 的 generator.type 是 minecraft:noise + settings: miningdim:mining(自定义 noise_settings), 仓库里根本没有 MiningChunkGenerator 这个类, CHUNK_GENERATOR 注册表也没有任何 miningdim 条目; 只有 BiomeSource 的 Codec 还在注册。
- 取证: src/main/resources/data/miningdim/dimension/mining.json:4-5 `"type": "minecraft:noise",` / `"settings": "miningdim:mining",`;src/main/java/com/miningdim/worldgen/WorldgenSystem.java:19-22 `自定义 ChunkGenerator 与离线体素生成管线 (OfflineCaveGenerator/GenerationScheduler) 已下线 (F021/F032): dimension/mining.json 的 generator.type 现为 minecraft:noise, 按需生成`
- 建议改法: 1) 4.4 文件(2) 示例(MiningDimension_Mod_DesignSpec.md:565-580)的 generator 块改成真实内容: `"type": "minecraft:noise", "settings": "miningdim:mining", "biome_source": {"type": "miningdim:mining_biome_source"}`(与 src/main/resources/data/miningdim/dimension/mining.json 一致),并删除 :583 关于 generator.type 必须匹配 CHUNK_GENERATOR Codec 注册的说明。 2) 5.1 表与 5.3 节(:681-705,含 RegisterEvent 示例 :688-699)删除 CHUNK_GENERATOR 注册分支,只保留 BIOME_SOURCE 注册(与 src/main/java/com/miningdim/registry/ModRegistration.java:37-40 实际只注册 Registries.BIOME_SOURCE 一致)。 3) 7.8 节的真实位置是 :1118-1172("### 7.8 ChunkGenerator 查表接入" 及其 7.8.1-7.8.4 子节),应整节标 REJECTED 并注明 F021/F032 判废理由,不要再用 DECIDED 描述 MiningChunkGenerator 的方法覆写表。 4) 新增一节说明当前真实生成方案: minecraft:noise + 自定义 noise_settings(src/main/resources/data/miningdim/worldgen/noise_settings/mining.json,含 noise_router/final_density=1/surface_rule 等字段)+ 自定义 BiomeSource,并引用 worldgen/WorldgenSystem.java:16-18 的判废注释作为权威出处。 5) 顺带清理文档中其余约 20 处仍以现在时描述 MiningChunkGenerator 的段落(如 :157、193、209、216、228、231、290、293-294、424、634、705、725、764、830、1226、1323、2053、3000 等),统一改为历史/已判废措辞,避免读者反复撞见已不存在的类名;同时注意 README.md:93/110 与 docs/Full_Repo_Audit_2026-08.md 中也存在对同一 MiningChunkGenerator 现状不一致的表述,可一并核对是否需要同步更正(此为衍生的 B 类线索,供人工决定是否单独立项)。

**[Critical] [A-文档与代码不符] 第六章(6.1-6.5)按 worldY 垂直分层的难度模型已被 R2 推翻为"难度=区域"沿 X 轴并排模型;且文档声称的 384 格维度高度与真实数据包 192 格不符,Easy 区 worldY 192..311 完全落在维度范围之外**

- 位置: 第 733-824(第六章 6.1-6.5), 757 行
- 文档原文: L757 `| Easy | 256..375 | 192..311 | 石头 | 下隔层 246..255 实心 | 顶部 376..383 留实心顶板(对应 has_ceiling) |`; 6.2 全表规定三难度子盒在同一 region 内沿 Y 垂直切分, 6.4 BiomeSource 按 local_y 区间返回 biome
- 代码实际: 实际模型是 R2「难度=区域」: 三块固定 region 在 XZ 平面并排(每块 256x192x256), 一整块就是一个难度, 旧的难度子盒 Y 常量已从代码删除; BiomeSource 按 region 归属返回 biome, y 不参与。而且在 height=192(worldY -64..127)下, 文档给的 Easy worldY 192..311 根本不在维度范围内。
- 取证: src/main/java/com/miningdim/core/MiningConstants.java:14-16 `难度模型 (R1/R2): 三个固定、独立、共享、常驻的区域在 XZ 平面并排, 每块 256x192x256, 一整块就是一个难度 (Easy/Medium/Hard)。难度由"玩家在哪一块 region"决定, 不再按 worldY 分带; 旧的难度子盒 Y 常量已删除。`;src/main/java/com/miningdim/worldgen/MiningBiomeSource.java:93-95 `按所在 region 难度返回 biome (R2: 难度=区域, 整列同一 biome)。... y 不参与 (整列同 biome)。`
- 建议改法: 重写第六章:6.1(旧矛盾消解叙事)、6.2(三区垂直子盒表,含 L757 Easy worldY 192..311)、6.3(隔层竖井)、6.5(随机游走 Y 区间表)整体标 REJECTED(R2 推翻),说明理由引用 MiningConstants.java:15-16/107-108、Difficulty.java:12-14、RegionLayout.java:62-65。改写为:"三块固定 region 沿 X 轴并排(Z 列固定为 FIXED_REGION_CELL_Z=0),单元 X 列 EASY=0/MEDIUM=1/HARD=2,stride=REGION_SIZE_X+REGION_GAP=288,REGION_GAP=BUFFER_CHUNKS*16=32,区域间与网格外由 mining_wall biome 的 surface_rule 填纯基岩封死(D1)"。6.4 的 `getNoiseBiome` 伪代码改为按 `RegionLayout.current().difficultyAt(blockX, blockZ)` 查表返回 easy/medium/hard/wall,并去掉 y 参与判断的描述(实现中 y 参数直接丢弃,见 MiningBiomeSource.java:72-73)。同时必须同步修正文档反复出现的"384 格 Y 预算"/"height: 384"(L493、547-549、743、2420 等处),改为与 src/main/resources/data/miningdim/dimension_type/mining.json 一致的 `height=192、min_y=-64、worldY 范围 -64..127`,否则修完难度模型后仍会残留一个与真实维度高度不符的历史数字,继续误导后续按此文档校验 worldY 边界的人。

**[Critical] [B-文档互相打架] 第六章(Y 垂直分层子盒)与第廿三/廿四章(水平分区表述)互相矛盾,且二者均已被代码现行的"三个独立 region 并排、一区一难度"模型(R1/R2)架空,全文档无一处记载现行模型**

- 位置: 第 757 与 3048, 3064 行
- 文档原文: L757 6.2 表把三难度沿 local_y 垂直堆叠(Easy 256..375 / Medium 128..245 / Hard 8..117); 而 L3048 `| 分层 | "因 Y 轴限制用区域分层" | DECIDED: region 内水平分三难度区,不依赖 Y 轴 |`, L3064 `[x] 多难度分层矿区(region 内水平分 Easy/Medium/Hard 三区,不依赖 Y 轴)`
- 代码实际: 同一份文档对「难度怎么分」给出两个互斥结论。代码取的是第三种: 不是 region 内水平分区, 而是三个独立 region 各承担一个难度。
- 取证: src/main/java/com/miningdim/core/MiningConstants.java:94-97 `// ---- 三固定难度区域的 XZ 槽位 (R1: 三块并排、常驻、共享, 一整块就是一个难度) ----` 与 `public static final int EASY_CELL_X = 0;` / `MEDIUM_CELL_X = 1;` / `HARD_CELL_X = 2;`
- 建议改法: 整体标记/删除 6.1-6.4(含 6.3 竖井设计与 6.4 按 local_y 判定的 BiomeSource 示例代码)为已废弃历史方案;新增一节以 MiningConstants.java:15,58,92-103 与 RegionGrid.java:21-23,151-153 为准描述现行模型(三个独立 region 沿 X 轴网格单元并排,EASY_CELL_X=0/MEDIUM_CELL_X=1/HARD_CELL_X=2,Z 恒为 FIXED_REGION_CELL_Z,单个 region 高度 REGION_HEIGHT=192 而非文档所称 384,一整块 region 只属一个难度,不做 Y 或 XZ 内部再分区);同步改写 L3048 与 L3064 的"region 内水平分三区,不依赖 Y 轴"为"三个 region 沿 X 轴并排,一 region 一难度"。

**[Critical] [D-覆盖缺口] 6.2-6.5 节仍在描述已被代码废弃的"单 region 按 worldY 分难度子盒"旧模型,与当前"三 region 并排、按整块 region 定难度"(R1/R2)实现完全不符;mining_wall 群系与 surface_rule 基岩封边链路(D1 隔离的真实落地机制)全文未提,possibleBiomes 应为四个而非三个,PENDING 状态早已 DECIDED**

- 位置: 第 795 行
- 文档原文: L795 `region 之外(缓冲带/实心墙)统一返回 `hardBiomeHolder` 或专设 `bufferBiomeHolder`(不影响玩法,因缓冲带全实心,PENDING)。`
- 代码实际: 已经落地成一个专门的第四群系 miningdim:mining_wall, 并且不是"不影响玩法"的摆设——它是 D1 实心隔离的唯一实现手段: surface_rule 据 biome 把这些列填成纯基岩, 把三个难度盒子封死, carver 碰基岩即停。possibleBiomes 也必须含它(四个)。文档既没登记这个 biome, 也没写 surface_rule 这条链路。
- 取证: src/main/java/com/miningdim/core/MiningConstants.java:38-45 `难度区域之外的"基岩墙"群系键 (data/miningdim/worldgen/biome/mining_wall.json) ... surface_rule 据 biome 把这些列填成纯基岩, 天然封死三难度, 且 carver 碰基岩即停 (D1 实心隔离的纯 datapack 实现, 不依赖自定义 ChunkGenerator)。`;src/main/java/com/miningdim/worldgen/MiningBiomeSource.java:70-73 `return Stream.of(easy, medium, hard, wall);`
- 建议改法: 不能只改 6.4 一处措辞,需整体重写 6.2/6.3/6.4/6.5 以对齐 MiningConstants.java 与 MiningBiomeSource.java 现状:(a) 6.2 起把"单 region 内 worldY 三分难度子盒"的描述替换为 R1/R2 模型——三个固定 region 在 XZ 网格并排(EASY_CELL_X=0/MEDIUM_CELL_X=1/HARD_CELL_X=2,Z 列恒为0),整块 region 高度(REGION_FULL_MIN/MAX_*)属同一难度,不再有难度子盒;(b) 6.3 竖井/连通描述若仍建立在旧子盒切分上需同步核实是否已被 RegionLayout/ConnectivityFix 的新实现取代;(c) 6.4 把 `getNoiseBiome` 示例代码换成实际的按 `RegionLayout.current().difficultyAt(blockX, blockZ)` 查表(y 不参与),`bufferBiomeHolder(PENDING)`改为已 DECIDED 的专设第四群系 `miningdim:mining_wall`(data/miningdim/worldgen/biome/mining_wall.json),`possibleBiomes()`说明改为四元集合(easy/medium/hard/wall);并新增段落说明 `noise_settings/mining.json` 的 `surface_rule.sequence` 中 bedrock_floor/bedrock_roof 垂直渐变基岩规则 + `biome_is: ["miningdim:mining_wall"]` 判断填基岩,是 D1 实心隔离在 datapack 层的实际实现;(d) 6.5 的随机游走 Y 区间约束若仍按旧子盒 local_y 表述,也需要重新核实是否仍然适用于现行整块 region 高度模型,或标注为历史/已废弃并给出现行等效约束。

**[Critical] [C-实现状态标错] 第七章离线体素三阶段管线整章(实际行 826-1223, 非 826-1307)已下线但仍标 DECIDED**

- 位置: 第 826-1307(第七章全章), 860 行
- 文档原文: L826 `## 七、矿洞生成系统(离线预生成模型)`; L860 `全局算法的复杂度被前移到一个不受 MC 调度约束的纯计算阶段。这是本 mod 能同时满足"全连通矿洞"与"MC 区块生成契约"的唯一可行架构,故 DECIDED。`
- 代码实际: Skeleton -> NoiseCarving -> ConnectivityFix 整条离线管线连同 OfflineCaveGenerator / GenerationScheduler 已被删除(F021/F032), 实例登记即置 GenState.READY, 不再有生成调度阶段; 地形改由原版 minecraft:noise 按需生成。VoxelOccupancy/OreGenerator/StaticTrapPlacement 等接口仍在, 但其 javadoc 引用的消费方 MiningChunkGenerator 已不存在。
- 取证: src/main/java/com/miningdim/instance/InstanceManager.java:42-44 `维度走 minecraft:noise 按需生成 (F021/F032: 离线体素管线已下线), 实例登记即置 GenState.READY, 不再有独立的生成调度阶段。`;src/main/java/com/miningdim/ore/OreSystem.java:62-64 `原注释描述的"由 GenerationScheduler 在工作线程预热、MiningChunkGenerator 主线程读缓存"这条链已不存在: 维度改用 minecraft:noise 生成后, 那两个类连同整条离线体素管线已判废删除`
- 建议改法: 在第七章(docs/MiningDimension_Mod_DesignSpec.md:826-1223)开头加一个显眼的状态块: `状态: 已作废 (F021/F032, 2026-08-17 提交 8150710a 起改用 minecraft:noise 按需生成)`, 正文整体降级为历史归档, 不再作为实现参考; 另起一章描述现行方案(dimension/mining.json 的 generator.type=minecraft:noise、worldgen/noise_settings/mining.json 的基岩 surface_rule、worldgen/biome/mining_wall.json 分层、worldgen/placed_feature 下的 ore_*.json 铺矿与 trap_collapsing_tunnel/trap_fake_ore/trap_lava_pocket/trap_tnt_vein.json 布陷阱); 同步修正引用离线管线的 2.2(C3/C4, 行 137-138)、2.3(性能阈值表引用 OfflineCaveGenerator.generate 与 MiningChunkGenerator 单区块填充, 行 158 一带)、3.4(生成主流程调用时序, 行 417-420 完整点名 OfflineCaveGenerator/Skeleton/NoiseCarving/ConnectivityFix/MiningChunkGenerator)、22.1(SP2 Spike 与"编码前阻塞项", 行 2984 一带)、以及 2.1 目标对照表中"对应章节"列指向第七章离线三阶段/ConnectivityFix 的 G2/G3 行, 全部同步标注为历史或改指向新章节。

**[Major] [B-文档互相打架] 配置文件名 mining-config.toml(仅 L1734 孤立一处)与全篇统一的 miningdim-server.toml 打架**

- 位置: 第 1734 行
- 文档原文: L1734 `私有与共享走不同算法,由 `mining-config.toml` 的 `instance.sharedByDefault`(默认 false)与玩家是否组队共同决定。`
- 代码实际: 同文档 16.1(L2386/L2390)与代码都是 `serverconfig/miningdim-server.toml`, 仓库里不存在 mining-config.toml。同样的旧名还出现在 2390-2391 与 2566 附近的路径引用里(机械扫描已标 L1734/L2390-2391)。
- 取证: src/main/java/com/miningdim/config/ConfigSystem.java:30-31 `ctx.registerConfig(net.minecraftforge.fml.config.ModConfig.Type.SERVER, MiningServerConfig.SPEC, "miningdim-server.toml");`
- 建议改法: 将 docs/MiningDimension_Mod_DesignSpec.md:1734 的 `mining-config.toml` 改为 `serverconfig/miningdim-server.toml`,与 16.1(L2390)、16.3(L2544)及代码 ConfigSystem.java:30-31 的注册文件名保持一致。经全仓库 `rg -i "mining-config"` 复核(4300 files / 21MB 搜索,仅 1 处命中),mining-config.toml 这个错误名字只出现在 L1734 这一行,L2390-2391 与 L2566 附近实际已经是正确名字(miningdim-server.toml / miningdim-client.toml / mining_ore 数据包路径),不需要一并修改,原建议中"全文 grep 一遍统一"的措辞应改为"仅需修正 L1734 这一处孤立笔误",避免误导后续处理时对 2390-2391/2566 做不必要的改动。

**[Major] [B-文档互相打架] 14.1 与 17.2 的 /mining 命令树冲突未同步"阶段2集成裁决": 17.2 描述的其实是已被弃用、未接入主类的 command.CommandSystem 实现**

- 位置: 第 2096-2116(14.1) 与 2620-2631(17.2) 行
- 文档原文: L2109-2115 14.1 命令树为 `enter <difficulty> [reseed] / leave / reset <instanceId> [reseed] / reset all / info [instanceId]`; L2620-2631 17.2 表为 `enter <difficulty> [party] / leave / status / list [page] / tp / kick / reset <id> [confirm] / reset all`
- 代码实际: 同一份文档给了两棵互斥的命令树: 14.1 有 info 无 status/list, 17.2 有 status/list 无 info; reseed 与 party 也各出现一处。实际生效的是 14.1 那棵(entry.MiningCommands), 但 14.1 里也没有难度门控之外的管理命令。
- 取证: src/main/java/com/miningdim/entry/MiningCommands.java:44-70 实际注册 `enter <difficulty> [reseed]` / `leave` / `info [instanceId]` / `reset [all|<instanceId> [reseed]]`, 无 status/list/tp/kick/party
- 建议改法: 不要把 17.2 简单裁剪成与 14.1 一致再单列一张"规划中"表 —— command 包那套不是"规划中"(尚未写), 而是"已写好但被明确弃用"(已写且已被 MiningDim.java:36-38 与 README.md:95-102 记录为不接入主类)。建议: 1. 在设计文档第十七章开头加一条状态说明, 引用 MiningDim.java 类注释与 README.md "已知架构裁决" 第2条, 把 17.1-17.4 的标题状态由 (DECIDED) 改为 (SUPERSEDED/已废弃, 对应代码 command.CommandSystem 未接入主类), 避免读者把它当现行规范。 2. 以 14.1(entry.MiningCommands, docs/MiningDimension_Mod_DesignSpec.md:2106-2115)为唯一权威表, 17.2 的权限等级列同步改为与 entry.MiningCommands 实际的 OP_LEVEL=2(entry/MiningCommands.java 第33行 `OP_LEVEL = 2` 及第62-63行应用于 reset)一致, 不再保留 level 4 的表述。 3. 若仍想保留 status/list/tp/kick/reset-confirm 的规格价值, 应按 README.md:102 给出的方向重写为"待迁移方案": 明确这些能力现存于 command.MiningCommands.java(具体到该文件第90/94/100/106/115行), 迁移目标是并入 entry.MiningCommands 而非另注册一个 "mining" 根, 且要点名 command 包里 enter 的差异(其 enter 仅 allocate 不传送, 与 14.2 防虚空链路不符, 见 README.md:100), 迁移前不能直接照抄现有实现。 4. 建议同批核查 17.3/17.4 中所有以现在时描述"已接入"行为的段落(如 17.3 表格第 2637-2645 行), 逐条标注它们对应的是 entry 包还是 command 包的实现, 避免同样的"两套实现描述成一套现状"的问题在别的小节重演。

**[Major] [A-文档与代码不符] 15.2 节协议版本代码示例 PROTOCOL_VERSION 仍写死为 "1", 与 MiningNetwork.java 现值 "2" 不符, 而该常量被文档自身列为"不得凭记忆改写"的权威取值且直接参与握手拒绝判定**

- 位置: 第 2263 行
- 文档原文: L2263 `private static final String PROTOCOL_VERSION = "1";`
- 代码实际: 信道协议版本已升到 "2"。该常量直接参与握手拒绝判定, 文档里的旧值会误导排查"客户端连不上"类问题。
- 取证: src/main/java/com/miningdim/network/MiningNetwork.java:34 `private static final String PROTOCOL_VERSION = "2";`
- 建议改法: 把 15.2 代码块中 `private static final String PROTOCOL_VERSION = "1";` 改为 `"2"`, 并在其后补一句协议版本变更规则, 例如: "协议字段(尤其 packet discriminator 集合)增删必须同步 bump PROTOCOL_VERSION, 现值以源码 MiningNetwork.PROTOCOL_VERSION 为准, 本文档不重复维护具体数值"。同时建议把 15.2/15.3 的 register() 代码快照与 packet 表标记为"示例, 非实时同步", 或直接改为链接源码行范围, 因为经核实该代码块相比当前 register()(见 MiningNetwork.java L61-92)已整体漂移: SelectZoneC2S 已被 F087 整包删除但文档仍列为已注册包, OpenMiningGuiS2C 从未真正注册, 且 JobSyncS2C/C2SWebUiRequest/S2CWebUiResponse/S2CWebUiEvent/ChampionSizeS2C 五个现网包均未收录, 一次性连带修正可避免后续再次出现同类"常量/包表过期"问题。

**[Major] [C-实现状态标错] SelectZoneC2S 包已被删除(F087), 第15章仍将其列为正式协议(含15.2注册代码/15.3总表/15.4.1完整职责表)**

- 位置: 第 2275-2276, 2303, 2313-2318 行
- 文档原文: L2275-2276 `CHANNEL.registerMessage(nextId(), SelectZoneC2S.class, SelectZoneC2S::encode, ...)`; L2303 SelectZoneC2S 在 15.3 Packet 总表首行; 15.4.1 给出其完整 encode/decode/handler 职责
- 代码实际: network 包里没有 SelectZoneC2S 类, MiningNetwork 的注册序列已不含它; 代码明确记录 discriminator 0 上原注册的 SelectZoneC2S 因"零发送方、绕过矿工等级门直接 allocate、且从不传送玩家"被移除(F087)。按 15.3 去实现客户端会写一个服务端不认的包。
- 取证: src/main/java/com/miningdim/network/MiningNetwork.java:56 `F087: 原 discriminator 0 曾注册 SelectZoneC2S (零发送方、绕过矿工等级门直接 allocate、且从不传送玩家的 ...`;目录 src/main/java/com/miningdim/network/ 下无 SelectZoneC2S.java
- 建议改法: 15.2 节代码块删除 SelectZoneC2S 的 registerMessage 调用(docs/MiningDimension_Mod_DesignSpec.md:2275-2277), 并补一句指向 MiningNetwork.java:56-57 的 F087 说明; 15.3 总表(2303行)删除 SelectZoneC2S 数据行, 补齐当前实际在册但表中缺失的 JobSyncS2C、C2SWebUiRequest、S2CWebUiResponse、S2CWebUiEvent 四包(均可在 MiningNetwork.java 的 register() 方法中逐一核对字段), 顺带核实 ChampionSizeS2C 是否也需要补入; 15.4.1 整段(2313行起)标注为 REJECTED(F087), 说明原因(零发送方、绕过矿工等级门直接 allocate、从不传送玩家)与替代路径(WebUI 请求包 MiningWebUiActions 与入口方块/命令)。

**[Major] [C-实现状态标错] 矿物分布 datapack JSON(16.6)从未落地代码, mining_ore 路径与 ReloadableResourceManager listener 在仓库中均不存在**

- 位置: 第 2392, 2566-2582(16.6) 行
- 文档原文: L2392 `| 数据包 JSON | `data/miningdim/mining_ore/*.json` | 矿物分布表(权重/配额/Y 适用) | `/reload` 重载(见 16.6) |`; L2566 `改为数据包资源 `data/miningdim/mining_ore/<difficulty>.json`,由自定义 `ReloadableResourceManager` listener 解析,支持 `/reload` 热更`, 并给出 hard.json 样例
- 代码实际: src/main/resources/data/miningdim/ 下没有 mining_ore 目录, 也没有对应的 ReloadableResourceManager listener; 配置键 ore.useDatapackDistribution 虽存在且默认 true, 但没有任何代码消费它(只有 IMiningConfig/ModConfig 的透传 getter 与几个测试里 throw unused() 的桩)。矿物分布的真实来源是 OreType 里的 Java 数值表, 外加 data/miningdim/worldgen/placed_feature/ore_*.json。
- 取证: `ls src/main/resources/data/miningdim/mining_ore` 返回 No such file or directory;`rg -n "useDatapackOreDistribution|ORE_USE_DATAPACK"` 仅命中 src/main/java/com/miningdim/config/ModConfig.java:71-72 与 src/main/java/com/miningdim/config/MiningServerConfig.java:162 的定义, 无任何业务读取点
- 建议改法: 1) 16.6 整节标题改为"矿物分布走数据包 JSON(DECIDED 设计, 未实现)"或直接加 TODO 标记, 正文补一句"当前未落地, 见下方代码事实"; 2) 16.1 表格中 `data/miningdim/mining_ore/*.json` 一行改为"规划中, 当前矿物权重/密度/配额硬编码在 `ore/OreType.java`(枚举字段 baseWeight/multipliers/densityPerK/maxCount), 铺矿算法见 `ore/OreGenerator.java`; 能源类矿物(BAUXITE/BORAX/SILVER/TIN/NICKEL/CHROMIUM/TUNGSTEN 等)改走 `data/miningdim/worldgen/placed_feature/*.json` 装饰特征"; 3) 16.2.3 的 `ore.useDatapackDistribution` 一行末尾补注"当前无任何业务代码读取此键(仅 `ModConfig`/`IMiningConfig` 透传 getter, 以及 `PressureGameTests`/`DangerCurveGameTests` 两处显式 `throw unused()` 的测试桩), 修改该配置值对实际生成结果无影响"; 4) 若日后要真正实现, 可参照仓库已有的 `TarotCardLoader`/`GunsmithComponentRuleLoader`(均 `extends SimpleJsonResourceReloadListener`)模式新建对应 loader, 而不是文档里杜撰的"ReloadableResourceManager listener"(1.20.1 无此可扩展类型)。

**[Major] [D-覆盖缺口] 16.2 配置总表与 14.2 入场流程伪代码均未登记已合入 main 的 entry.* 入场费/文案配置**

- 位置: 第 2400-2502(16.2 全表) 行
- 文档原文: 16.2 SERVER 配置总表分九小节(instance/layer/ore/difficulty/trap/danger/mob/spawn/reset/perf), 无任何 entry 段
- 代码实际: spec 里有 entry 段: labelEasy/labelMedium/labelHard 三条入口告示文案 + entryFeeEasy/entryFeeMedium/entryFeeHard 三档入场费(long, 默认 0)。入场费是 TaskSpec_Mining_EntryFee.md 明确要求的硬门槛机制, 默认 0 只是待实测, 不是不存在。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:253-262 `b.push("entry");` 与 `ENTRY_FEE_EASY = b.defineInRange("entryFeeEasy", 0L, 0L, Long.MAX_VALUE);` 等六项;docs/TaskSpec_Mining_EntryFee.md 第一节"第一段 (本规格全文): 收费机制 + 产出测量埋点。收费默认值 0"
- 建议改法: 1) 在 16.2 新增 "16.2.11 入场(Entry)" 小节, 完整列出 MiningServerConfig.java:253-262 的六个键: `entry.labelEasy`/`labelMedium`/`labelHard`(String, 入口浮空文案)与 `entry.entryFeeEasy`/`entryFeeMedium`/`entryFeeHard`(long, 范围 [0, Long.MAX_VALUE], 默认 0, 单位 CREDIT), 并交叉引用 docs/TaskSpec_Mining_EntryFee.md。 2) 修正落点不是 14.4 的 gateCheck, 而是 14.2 的步骤伪代码: 在步骤 1 "gateCheck" 的说明里补一句"含 entryFee 余额是否充足的判定(GateResult.INSUFFICIENT_FUNDS, 见 EntryGateway.java:107-119), 不足则中止并提示差额"; 再在步骤 7(resolveSpawn)与步骤 8(teleportTo)之间插入新的 "7.5 若 entryFee>0, 调用 EconomyServices.tryCharge(player, CREDIT, entryFee) 扣款, 失败则回滚 pendingEnter 并提示 INSUFFICIENT_FUNDS"(对应 EntryGateway.java:259 注释自称的"14.2 步骤 7-12: resolveSpawn 后扣费"与第 266-268 行的实际 tryCharge 调用)。

**[Major] [C-实现状态标错] 16.2.2/16.4/16.7 的 layer.* 分层 Y 边界配置在代码里已整段删除,文档仍标注为已实现配置项(16.4/16.7 引用行号需更正为 2551/2593)**

- 位置: 第 2413-2418, 2504-2506(16.4), 2531(16.7) 行
- 文档原文: L2415 `| `layer.easyMinY` / `layer.easyMaxY` | int | 192 / 311 | -64..320 | Easy 难度子盒 worldY 区间(与第六章 6.2 子盒一致) |` 及 mediumMinY/hardMinY/enforceOrdering 四项, 16.4 把 layer.* 标为 worldRestart, 16.7 把"分层有序"列为启动校验项
- 代码实际: MiningServerConfig 的 spec 里没有 layer 段, 配置门面的分层 Y 实现也已删除(R2 难度由所在 region 决定)。按文档去改 serverconfig 里的 layer.easyMinY 不会有任何效果——那个键根本不会被写进 toml。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:155 `// R2: layer.* 子盒 Y 配置已删除 —— 难度由所在 region 决定, 不再按 worldY 分带。`;src/main/java/com/miningdim/config/ModConfig.java:59 `// ---- R2: 分层 Y 边界实现已删除 (难度由所在 region 决定) ----`
- 建议改法: 删除 docs/MiningDimension_Mod_DesignSpec.md 第 2411-2420 行的"16.2.2 分层 Y 边界(Layer)"整节(含表头与注释段);同步删除 16.4 worldRestart 表第 2551 行"`layer.*` 子盒边界"一行,以及 16.7 配置校验表第 2593 行"分层有序"一行;并在第六章 6.2 或第十六章章首补一句说明:R2 后难度改由 RegionLayout 按 region 的 X/Z 位置决定(参见 worldgen/MiningBiomeSource.java:84 的 RegionLayout.current().difficultyAt),不再存在任何 worldY 分层配置项,避免运维人员按文档去改 serverconfig.toml 里实际不存在的 layer.easyMinY 等键。

**[Major] [D-覆盖缺口] 每难度定时自动重置(R6)未进配置表与重置章**

- 位置: 第 2490-2496(16.2.9), 1866-1874(13.1) 行
- 文档原文: 16.2.9 重置配置表只有 cooldownSeconds/requireEmpty/kickOnForceReset/confirmationWindowSeconds 四项; 13.1 只泛泛写"定时重置 | 配置周期(如每 N 小时)", 未给任何键名与默认值
- 代码实际: 代码里有完整的 R6 每难度定时自动重置: reset.autoResetHoursEasy=6 / Medium=4 / Hard=2 / autoResetWarnSeconds=60, 由 AutoResetScheduler 每 20 tick 巡检、按难度独立计时、预警倒计时后撤离并以 NEW_SEED 重置, lastReset 持久化于 AutoResetData。这是运维最需要知道的一组键, 文档完全没有。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:236-244 `.defineInRange("autoResetHoursEasy", 6, 0, 168);` / `autoResetHoursMedium", 4` / `autoResetHoursHard", 2` / `autoResetWarnSeconds", 60`;src/main/java/com/miningdim/reset/AutoResetScheduler.java:21-24 `R6 每难度定时自动重置调度器。开服后由 ResetSystem 每秒 (20 tick) 检查一次 ...`
- 建议改法: 在 16.2.9 表格追加四行:`reset.autoResetHoursEasy`(int,默认 6,范围 0..168)、`reset.autoResetHoursMedium`(默认 4)、`reset.autoResetHoursHard`(默认 2)、`reset.autoResetWarnSeconds`(默认 60,范围 0..600),并注明"0 = 关闭该难度自动重置"(对应 MiningServerConfig.java:237/239/241/243 的 comment 语义)。在 13.1"定时重置"一行补充:按难度独立计时(AutoResetScheduler 每难度维护独立 Phase/剩余秒数)、到期先按 autoResetWarnSeconds 倒计时广播撤离、随后以 `IResetService.ResetMode.NEW_SEED` 触发重置(AutoResetScheduler.java:186-189)、lastReset 持久化于 AutoResetData 供重启后续算。

**[Major] [B-文档互相打架] GenState 枚举取值在 3.3/7.9.3/7.9.4 与 12.1 之间不一致:12.1"核心数据结构与状态机"表遗漏 READY_FALLBACK 与 RECYCLED,且 12.5 持久化方案明确挂靠该表**

- 位置: 第 270 与 1712 行
- 文档原文: L270 `// genState: PENDING, GENERATING, READY, READY_FALLBACK, RESETTING, FAILED, RECYCLED(全文统一枚举)`; L1712 `| genState | enum(PENDING/GENERATING/READY/RESETTING/FAILED) | 离线生成与重置状态 | 是 |`
- 代码实际: 两处同标"全文统一"却差两个值。代码取的是 3.3 的七值超集, 并在枚举 javadoc 里专门记下这处文档打架。
- 取证: src/main/java/com/miningdim/core/GenState.java:5-8 `12.1 表给出的持久化最小集为 PENDING/GENERATING/READY/RESETTING/FAILED; 任务契约额外要求 READY_FALLBACK (生成降级为可用但非理想) 与 RECYCLED (已回收待清), 故本枚举取并集。`
- 建议改法: 把 docs/MiningDimension_Mod_DesignSpec.md:1716 的 genState 一行由 `enum(PENDING/GENERATING/READY/RESETTING/FAILED)` 补成七值 `enum(PENDING/GENERATING/READY/READY_FALLBACK/RESETTING/FAILED/RECYCLED)`,并在该行或表格脚注补充: (a) 与 3.3(第 270 行)、7.9.3/7.9.4(第 1205/1216/1218 行)取值口径统一, 不再各写一份; (b) isEnterable() 语义 = READY|READY_FALLBACK(对应代码 GenState.java:41-43); (c) 明确 RECYCLED 是否需要落盘还是仅作为内存态终态(当前 12.5:1830 "对应 12.1 表" 的措辞暗示七值都要序列化,应在此处显式确认,避免实现者按旧 5 值表裁剪持久化白名单)。

**[Major] [C-实现状态标错] 18 章 abuse.* 配置分类从未接入 ForgeConfigSpec**

- 位置: 第 2777-2795(18.7) 行
- 文档原文: L2777-2779 `### 18.7 闸门配置汇总(ForgeConfigSpec)` / `所有键归于 `abuse` 配置分类(SERVER 配置,见配置章)。判定全部服务端权威。`, 表中列 abuse.reset.cooldownTicks / costItem / costAmount / dailyLimitPerInstance 等
- 代码实际: MiningServerConfig 没有 abuse 段, 这些数值全部是 EconomyConstants 里的 Java 硬编码常量, 代码自己注明"把 abuse.* 接入 ForgeConfigSpec 并扩展配置门面之前, 本闸门子系统自带这些初值作为唯一来源 ... 留待接线"。服主按 18.7 去 toml 里找 abuse 段会一无所获, 且这与 2.2 硬约束 C6"所有平衡数值可配置"直接冲突。
- 取证: src/main/java/com/miningdim/economy/EconomyConstants.java:15-17 `把 abuse.* 接入 ForgeConfigSpec 并扩展配置门面之前, 本闸门子系统自带这些初值作为唯一来源, 与 18.7 表逐项对齐。一旦 ConfigSystem 暴露 abuse.* getter, 这些常量应改为读配置 (留待接线)`;同文件 33/36/39/42 行 `RESET_COOLDOWN_TICKS = 6000;` / `RESET_COST_ITEM = ...diamond` / `RESET_COST_AMOUNT = 2;` / `RESET_DAILY_LIMIT_PER_INSTANCE = 8;`
- 建议改法: 18.7 表头改为「当前实现:硬编码于 economy/EconomyConstants.java,abuse.* 配置段待接线(违反 2.2 C6,已登记)」;表格逐行新增一列"代码常量名",分别对应 RESET_COOLDOWN_TICKS/RESET_COST_ITEM/RESET_COST_AMOUNT/RESET_DAILY_LIMIT_PER_INSTANCE/RESET_DAY_MODE/DAILY_SOFTCAP_DIAMOND/DAILY_SOFTCAP_NETHERITE_SCRAP/ECONOMY_DECAY_BASE/AFK_NO_BREAK_TICKS/AFK_NO_MOVE_BLOCKS/REENTRY_COOLDOWN_TICKS/REENTRY_RETAIN_RATIO/DEATH_REENTRY_COOLDOWN_TICKS/DEATH_DANGER_MODE;并在 2.2 节 C6 行下加一条例外脚注(指向 EconomyConstants.java 的接线计划),或在第二十二章风险登记表新增一条"abuse.* 配置未接线,违反 C6"的风险项,避免服主按文档去 toml 里翻找不存在的 abuse 段。

**[Major] [A-文档与代码不符] 19.1 的 load.* 配置键不存在(真实键在 perf.* 段,与 16.2.10 表打架),且 tickRadius 并非独立配置而是运行时派生值**

- 位置: 第 2810-2815(19.1) 行
- 文档原文: L2810 `按 `load.activeRadius`(建议 8)申请滑动 ticket 集合 | 半径内 `load.tickRadius`(建议 4)区块 ticking=true`; 同表"卸载释放"行用 `load.emptyTtlTicks`(建议 6000=5min)
- 代码实际: spec 里没有 load 段, 对应的真实键是 perf.loadRadiusChunks(默认 4, 范围 2..16)与 perf.emptyInstanceTtlSeconds(默认 300 秒), 文档自己的 16.2.10 表也是这么写的——即 19.1 与 16.2.10 互相打架, 且两者都与"activeRadius 8 / tickRadius 4"两级半径的说法对不上(代码只有一个半径)。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:307-315 `b.push("perf");` 与 `.defineInRange("loadRadiusChunks", 4, 2, 16);` / `.defineInRange("emptyInstanceTtlSeconds", 300, 0, 86400);`
- 建议改法: 1) 19.1 表格三处 `load.activeRadius` / `load.tickRadius` / `load.emptyTtlTicks` 统一改写为实现中的真实键:加载半径为 `perf.loadRadiusChunks`(默认 4,范围 2..16,MiningServerConfig.java:308-309),空置 TTL 为 `perf.emptyInstanceTtlSeconds`(默认 300 秒,MiningServerConfig.java:310-311、310);并把 19.1 的"6000 tick"与 16.2.10 的"300 秒"统一成同一单位表述,避免读者需要自行换算才能确认两处一致。 2) 不要把 `tickRadius` 写成与 `activeRadius` 平级、可独立配置的键。ChunkTicketManager.refreshWindow(ChunkTicketManager.java:99-102)里 tickRadius 是运行时派生值:`tickRadius = Math.max(1, activeRadius / 2)`,并无独立配置项。应改写为"tickRadius 由 perf.loadRadiusChunks 派生(取其一半,向下取整,至少为 1),当前版本没有独立配置该值的入口";若确实想让 tickRadius 独立可调,才应在文档中标注 TODO/PENDING 并注明这是尚未实现的增强项。 3) 校正 19.1 给出的建议默认值:文档写 activeRadius 建议 8(对应派生 tickRadius=4),但代码当前默认 loadRadiusChunks=4(对应派生 tickRadius=2)。应二选一:要么把文档默认值改成与代码一致的 4,要么明确标注"建议值 8 是未来调参目标,当前实际默认仍是 4",避免施工方/测试方误以为默认已是 8。

**[Major] [A-文档与代码不符] REGION_STRIDE_X 写 544, 算式与代码都是 288**

- 位置: 第 491 行
- 文档原文: L490-491 `| `REGION_GAP` | 32 | 相邻 region 之间缓冲带格数(2 区块) | >=16,实心填充 |` / `| `REGION_STRIDE_X` | 544 | XZ 网格步长 = SIZE + GAP | 派生量 |`
- 代码实际: SIZE(256) + GAP(32) = 288, 文档自己的算式就推不出 544; 代码是 288, 且注释点明既有存档的 stride 288 依赖 BUFFER_CHUNKS 不变。按 544 去算 region 原点会把每块 region 的世界坐标算错约一倍。
- 取证: src/main/java/com/miningdim/core/MiningConstants.java:73 `public static final int REGION_STRIDE_X = REGION_SIZE_X + REGION_GAP; // 288`;同文件 70 行 `public static final int REGION_GAP = BUFFER_CHUNKS * 16;`(注释: 既有存档的 stride 288 依赖此值不变)
- 建议改法: docs/MiningDimension_Mod_DesignSpec.md 第491行表格把 REGION_STRIDE_X/Z 的取值列由 544 改为 288(与488/490行 REGION_SIZE_X=256、REGION_GAP=32 的算式 SIZE+GAP 保持一致),并在备注列注明该值已被既有存档几何(MiningConstants.java:70 注释所述)冻结,变更 SIZE 或 GAP 会使旧存档 region 网格错位废图。

**[Major] [A-文档与代码不符] monster_spawn_light_level 写成 uniform 对象, 实际是整数 7**

- 位置: 第 552 行
- 文档原文: L552 `"monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },`
- 代码实际: 线上 dimension_type/mining.json 用的是整数简写 7。文档的对象形式虽然 1.20.1 也支持, 但与实际文件不一致, 照抄会覆盖掉当前"恒为 7"的刷怪光照语义(uniform 0..7 是每次取随机值)。
- 取证: src/main/resources/data/miningdim/dimension_type/mining.json:17 `"monster_spawn_light_level": 7,`
- 建议改法: 将 4.4 文件(1) 的 JSON 样例中第552行对应字段由 `"monster_spawn_light_level": { "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7 },` 改为与线上文件一致的 `"monster_spawn_light_level": 7,`,并在紧随其后的字段决策说明(第557行)中补充一句区分:整数简写代表"每次判定均使用恒定阈值 7",uniform 对象代表"每次判定从 0..7 中随机取阈值",本设计采用前者(恒定阈值),避免读者按对象形式理解刷怪光照语义。

**[Major] [F-结构问题] 状态词汇各文档自定义且互相冲突, 501 处标记零机械校验**

- 位置: 第 7 行
- 文档原文: L7「- 状态图例: DECIDED 已定稿 / PENDING 待校验 / REJECTED 已否决(附理由) / TODO 实现期补全。」
- 代码实际: 全库只有 9 份文档定义了「状态图例」, 且 6 套词汇互不兼容, 同一个 PENDING 在不同文档里分别是「待校验」「待标定」「待你拍板」「待拍板(已给推荐)」「待选或待标定数值」「待最终确认(已给推荐默认值)」; Power_Cable 则改用 IMPLEMENTATION_REQUIRED。另有 35/57 份文档连一行状态都没有(含 MiningDimension 自身的顶层状态、Munitions_Job、Marriage、SpecialAgent、WebUI_Architecture 等主规格)。全库累计 DECIDED 338 处、PENDING 163 处、REJECTED 19 处、TODO 18 处、DEFERRED 8 处, 没有任何机械校验, 也没有一处记录「谁在什么时候把 PENDING 翻成 DECIDED」。
- 取证: 逐份对照: docs/Miner_Job_DesignSpec.md:10「- 状态图例: DECIDED 已定稿 / PENDING 待标定 / TODO 实现期补全。」、docs/Power_Cable_DesignSpec.md:11「- 状态图例: DECIDED 已定稿 / IMPLEMENTATION_REQUIRED 已定机制尚待落码。」、docs/FarmingXP_Mod_DesignSpec.md:9「PENDING 待你拍板」。计数由 `grep -ro <关键词> docs/*.md | wc -l` 得出。同仓已有可借鉴的机械校验范式: build.gradle:423 verifyModuleBoundaries 对 DEPENDENCY_DEBT.md 的 D### 编号集合与 module-registry.json 逐条对账, 编号对不上直接构建失败。
- 建议改法: 在 docs/README.md 里定义唯一的全库状态词汇表(建议 DECIDED / PENDING / IMPLEMENTED / DEFERRED / SUPERSEDED / ARCHIVED 六值), 删掉各文档自定义的「状态图例」行改为引用。每份文档头部统一一个元信息块, 必填 状态 / 最后核对日期 / 核对基线 commit / 归属模块键。仿照 D### 债务编号的做法, 给 PENDING 条目编号并集中登记, 由构建期脚本核对文档内标记与登记表一致。

**[Major] [F-结构问题] 矿区规格 3091 行 24 章塞成单文件, 整章腐烂无人察觉**

- 位置: 第 826 行
- 文档原文: L826「## 七、矿洞生成系统(离线预生成模型)」(全文 3091 行, 24 个一级章, 第七章自 L826 延伸至 L1223 共 398 行)
- 代码实际: 单文件覆盖 24 个互不相干的主题: 平台约束、世界结构、注册架构、难度分区、生成管线、矿物数值、陷阱、刷怪、出生、实例生命周期、重置、传送、网络协议、配置、命令、反滥用、性能、错误处理、测试、路线图。体量使得整章级别的作废都无人发现: 第六章(L733-825)、第七章(L826-1223)、第十二章(L1694-1953)合计约 750 行(占全文 24%)所描述的架构在代码里已整体不存在, 却仍挂着 DECIDED。粒度过粗还直接造成引用困难 —— 全仓只有 3 个文件引用它, 且都只能引文件名、指不到章。
- 取证: `grep -rl 'class MiningChunkGenerator' src/` 零命中; src/main/java/com/miningdim/worldgen/ 目录下只有 MiningBiomeSource.java、UndergroundMiscFeatureGameTests.java、WorldgenSystem.java 三个文件 —— 第七章整章围绕的离线三阶段管线与 MiningChunkGenerator 类在代码中已无任何对应物。对照 docs/modules/README.md:11「| 基础 | WOK-矿区副本模块 | `wok-mining` | `worldgen`、`instance`、`chunk`、`reset`、`spawn`、`ore`、`trap`、`pressure`、`rules`、`entrance`、`persistence`、`command` |」: 代码侧同一模块已经拆成 12 个包, 文档侧仍是一个文件。
- 建议改法: 按代码侧已有的 12 个包切分成 docs/spec/mining/ 子目录: world-structure.md(第二/四章)、worldgen.md(第六/七/八章)、instance-lifecycle.md(第十二章)、reset.md(第十三章)、entry-teleport.md(第十四章)、protocol.md(第十五章)、config.md(第十六章)、commands.md(第十七章)、abuse-gate.md(第十八章)、ops.md(第十九至廿一章), 由 docs/spec/mining/README.md 收口并标注各章状态。拆分时把已判废的第六/七/十二章整体移入 docs/archive/ 并加 SUPERSEDED 抬头, 不要留在现役文件里继续被当 DECIDED 读。

### `docs/TaskSpec_Mining_DeathRules.md`

**[Major] [C-实现状态标错] 状态仍写"有两条待拍板", 实现已按默认取值全量落地且已合入main**

- 位置: 第 3 行
- 文档原文: 状态: **有两条待拍板** (见第一节), 机制部分可先实施 | 分支: `feat/mining-difficulty-death-rules`
- 代码实际: MiningDeathRules.java 已按 D1 默认 (宽读, 覆盖原版全部保留路径) 与 D2 默认 (只掉物品不动经验) 实现完毕: LivingDropsEvent + EventPriority.HIGH、先判维度再判 region、消失诅咒销毁、清空背包防"既掉又留"、keepInventory=false 的启动 WARN 自检, 全部到位; 第六节要求的五条 GameTest 一条不缺。第四节 4.3 的两处明示 (HARD 额外红色聊天警告 + overviewRow 的 dropsOnDeath 字段) 也都已接线。
- 取证: src/main/java/com/miningdim/rules/MiningDeathRules.java:33-61 `@SubscribeEvent(priority = EventPriority.HIGH) ... if (instance == null || instance.difficulty() != Difficulty.HARD) return; ... ItemStack removed = inventory.removeItemNoUpdate(slot); if (EnchantmentHelper.hasVanishingCurse(removed)) continue; event.getDrops().add(new ItemEntity(...))`; 同文件:66-68 `if (!event.getServer().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) { LOGGER.warn("[miningdim] keepInventory=false: EASY/MEDIUM death item retention is not active"); }`; src/main/java/com/miningdim/entry/EntryGateway.java:295-297 `if (inst.difficulty() == Difficulty.HARD) { player.sendSystemMessage(Component.translatable("message.miningdim.enter.hard_death_drops").withStyle(ChatFormatting.RED)); }`; src/main/java/com/miningdim/entry/MiningWebUiActions.java:124 `row.addProperty("dropsOnDeath", difficulty == Difficulty.HARD);`; src/main/java/com/miningdim/rules/MiningDeathRulesGameTests.java:36/48/62/86/112 五条用例
- 建议改法: 将 docs/TaskSpec_Mining_DeathRules.md 第3行改为: "状态: **已交付** (D1 按默认(B)宽读实施, D2 按默认"只掉物品不动经验"实施; 实现见 `rules/MiningDeathRules`, 测试见 `rules/MiningDeathRulesGameTests` 五条用例) | 分支 `feat/mining-difficulty-death-rules` 已合入 main(经 cc8c295a)"。第一节标题"待拍板"下的 D1/D2 两条改为陈述已按默认值实施的既成事实(保留能力边界表与理由说明供后续查阅, 但去掉"待确认"语气); 第五节三条真机验收项(gamerule实测值/第三方soulbound互操作/既掉又留复制bug排查)若确未在真机验证, 保留原样并加注"真机待验", 不要一并标记为已完成。

### `docs/TaskSpec_Mining_EntryFee.md`

**[Major] [C-实现状态标错] TaskSpec_Mining_EntryFee.md 状态行与"这个数当前不存在"表述过期: 第一段机制+埋点已交付并有GameTest覆盖, 文档仍暗示埋点未实现**

- 位置: 第 3 (旁证 31-34) 行
- 文档原文: 状态: **数值待实测** (机制可先做) | 分支: `feat/mining-entry-fee`  ... "根据收益比定价"要求先知道**一趟矿洞平均产出多少信用点**。这个数**当前不存在**
- 代码实际: 第一段全部落地: 三档配置键默认 0、IMiningConfig.entryFee/ModConfig 实现、GateResult.INSUFFICIENT_FUNDS、gateCheck 先等级后余额、completeEnter 内 fee>0 才 tryCharge 且失败走 notifyInsufficientFundsAndRollback、MiningWebUiActions 的 entryFee 字段, 以及第五节要求的 MiningYieldProbe (进入 start / 离开 finish / 经济侧 record 三点接线齐全, 日志格式与 5.2 给的逐字一致)。所以"这个数当前不存在"已不成立——埋点已在跑, 缺的只是样本。
- 取证: src/main/java/com/miningdim/entry/MiningYieldProbe.java:59-60 `LOGGER.info("[miningdim] yield-probe difficulty={} dwellTicks={} oreDrops={} creditGross={}", ...)`; src/main/java/com/miningdim/entry/EntryGateway.java:300 `MiningYieldProbe.start(player, pe.difficulty);`; 同文件:266-268 `if (pe.entryFee > 0L && !EconomyServices.economyService().tryCharge(player, Currency.CREDIT, pe.entryFee)) { notifyInsufficientFundsAndRollback(pe, player, pe.entryFee); }`; 同文件:321-327 `if (entryFee <= 0L) { return GateResult.PASS; } return ...creditBalance(player) >= entryFee ? GateResult.PASS : GateResult.INSUFFICIENT_FUNDS;`; src/main/java/com/miningdim/config/MiningServerConfig.java:260-262 三档默认 0
- 建议改法: 1) 第3行改为: "状态: **第一段(机制+埋点)已交付, 三档默认仍为0**; 第二段(定价)未开始, 需先让 `yield-probe` 日志在真机跑够样本 | 分支: `feat/mining-entry-fee`"。 2) 第30-34行: 把"这个数**当前不存在**"改为"这个数**当前尚无样本**", 并在第32行"oresurvey 这个测量工具...从来没被实现过"后面补一句"(注: 本任务第一段已交付的标定期仪表 `entry/MiningYieldProbe` 承担了这个角色, 详见第五节; 此处保留原文是为了说明动笔时的现状, 不代表当前状态)", 避免读者误以为埋点仍待建。 3) 顺带核对: docs/TaskSpec_INDEX.md 第10行"矿洞进入收费 ... 数值待实测; 机制与测量埋点可先做, 默认值必须是 0"与本文档同源同批过期, 建议一并更新为与第3行一致的措辞, 否则两处仍会互相印证出"埋点未做"的错误印象(该处不在原待核发现范围内, 仅作关联提示)。

### `docs/TaskSpec_Mining_NoRespawnPoint.md`

**[Major] [C-实现状态标错] 状态仍写"待实施", 实现与三条 GameTest 已于 2026-08-17 合入 main(提交 504288cd)**

- 位置: 第 3 行
- 文档原文: 状态: 待实施 | 分支: `fix/mining-no-respawn-point` | 预估: 1 个方法 + 1 条 lang 键 + 2 条 GameTest
- 代码实际: RulesSystem.onSetSpawn 已按第四节给的代码逐字落地 (含"判据是重生点指向的维度"与"清除永不被拦"两条注释), lang 键中英两份都有, 第五节要求的三条 GameTest 一条不缺 (拦得住 / 不误伤 / 清除不被拦)。注意第 3 行预估的是"2 条 GameTest", 第五节正文要求 3 条, 实际写了 3 条。
- 取证: src/main/java/com/miningdim/rules/RulesSystem.java:61-67 `if (!event.getSpawnLevel().equals(MiningConstants.MINING_LEVEL)) { return; } event.setCanceled(true); ... Component.translatable("message.miningdim.rules.spawn_denied")`; src/main/java/com/miningdim/rules/RulesSpawnGameTests.java:24/36/47 三个 `@GameTest` 方法 miningDimensionSpawnIsCanceled / overworldSpawnIsNotCanceled / clearingSpawnIsNotCanceled; src/main/resources/assets/miningdim/lang/zh_cn.json:111 "message.miningdim.rules.spawn_denied": "矿洞里不能设置重生点 —— 这块地会被整块刷新。"
- 建议改法: 把 docs/TaskSpec_Mining_NoRespawnPoint.md 第 3 行改为: "状态: **已交付**(实现在 `rules/RulesSystem.onSetSpawn`, 测试在 `rules/RulesSpawnGameTests` 三条, 已随提交 504288cd 合入 main) | 分支 `fix/mining-no-respawn-point`(内容已合并, 分支可清理)"。同时把第 3 行"预估: ...2 条 GameTest"与第五节正文"三条: 拦得住/不误伤/清除不被拦"对齐(改成 3 条, 或既然已交付直接删除"预估"字样), 消除文档内部的自相矛盾。

---

## 经济

### `docs/Economy_BalanceSheet_DesignSpec.md`

**[Critical] [D-覆盖缺口] 任务 faucet 未登记且已另开独立印钞口**

- 位置: 第 126 行
- 文档原文: L126「1. **系统印钞（faucet，正常落点 ~14.9 万/人/日，几何主项封顶…）** → 矿工/农夫/渔夫/特勤/精英怪（全部并入同一 `credit_faucet` 主闸，不另开印钞口）。」（全文含第二/三/四/五章零处提到「任务」）
- 代码实际: 任务系统是一条已上线的信用点 faucet，总表全文一次都没登记。它刻意不并入 credit_faucet 主闸，而是走独立键 quest_faucet + 档位默认 1,000,000 CP，正常游玩够不到，衰减系数恒为 1（实发 == 名义值）。默认配置下光日常保底就是 10,000 CP/日不衰减（dailyBase 2000 × 三条易 + 2000×2 一条难），再加每周 weeklyBase 6000×难度，相当于在 ~10 万主闸之外再挂约 10% 的无衰减注入。第三章三档净储蓄的「系统收入」列、第四章「通胀平衡」算式都少算了这一整条龙头，第五章「不另开印钞口」这句在代码里直接是错的。
- 取证: src/main/java/com/miningdim/quest/QuestRewards.java:80-82 `return EconomyServices.economyService().grantDaily(player, raw, EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY, QuestConfig.FAUCET_TIER.get());`；src/main/java/com/miningdim/economy/EconomyConstants.java:93 「任务奖励专用信用点 faucet 计数键 —— <b>刻意不并入 {@link #GLOBAL_DAILY_CREDIT_FAUCET_KEY} 主闸</b> (用户决策)」；src/main/java/com/miningdim/quest/QuestConfig.java:92 `.defineInRange("faucetTier", 1_000_000L, 1L, Long.MAX_VALUE);`；:95-97 「The default is back-solved from a 10,000 CREDIT daily floor: three easy slots at difficulty 1 plus one hard slot at difficulty 2 = 10,000」
- 建议改法: 第二章职业表补一行:「任务（全职业） | 日常/周常/特殊/隐藏奖励（faucet，独立键 `quest_faucet`，默认档位 1,000,000 故不衰减） | — | 全员保底收入源」;第五章第1条改为「矿工/农夫/渔夫/特勤/精英怪并入同一 `credit_faucet` 主闸；任务走 `quest_faucet` 独立键且默认档位 1,000,000 故不衰减，判据见 `EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY` 与 `QuestConfig.FAUCET_TIER`」;第三章三档净储蓄表「系统收入」列各加约 1 万/日任务保底（休闲 6万→7万、正常 10万→11万、肝帝 14万→15万），净储蓄随之重算；第四章通胀平衡算式的"系统每日印钞"一项连带补入这条恒定不衰减的任务注入并重算风险评估。

**[Critical] [A-文档与代码不符] 经济总表漏登任务 faucet, 且与代码"不另开印钞口"直接相反**

- 位置: 第 126 (旁证 15/21/74) 行
- 文档原文: 1. **系统印钞（faucet，正常落点 ~14.9 万/人/日，几何主项封顶；深档 1% 线性尾巴靠巡查兜底）** → 矿工/农夫/渔夫/特勤/精英怪（全部并入同一 `credit_faucet` 主闸，不另开印钞口）。
- 代码实际: 任务系统是第六条信用点 faucet, 且刻意不并入 credit_faucet 主闸: 走独立计数键 quest_faucet, 配套档位默认 1,000,000 (远高于一天任务总额, 等于恒不衰减)。日常保底 10,000 CP/人/日 (4 槽: 3 条 difficulty=1 x 2000 + 1 条 difficulty=2 x 2000) 再加周常 6000 x difficulty, 全额不打折入账。全文 grep "任务|quest" 在 Economy_BalanceSheet_DesignSpec.md 里零命中, 第 15/21 行的"系统收入(faucet)"画像表与第 74 行的"通胀平衡"算式都没算这笔。
- 取证: src/main/java/com/miningdim/economy/EconomyConstants.java:93 "任务奖励专用信用点 faucet 计数键 —— <b>刻意不并入 {@link #GLOBAL_DAILY_CREDIT_FAUCET_KEY} 主闸</b> (用户决策)。"; 同文件:106 `public static final String QUEST_DAILY_CREDIT_FAUCET_KEY = "quest_faucet";`; src/main/java/com/miningdim/quest/QuestConfig.java:92 `.defineInRange("faucetTier", 1_000_000L, 1L, Long.MAX_VALUE);`; 同文件:95-97 "The default is back-solved from a 10,000 CREDIT daily floor: three easy slots at ..." / `.defineInRange("dailyBase", 2_000L, 0L, Long.MAX_VALUE);`; src/main/java/com/miningdim/quest/QuestRewards.java:81-83 `grantDaily(player, raw, EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY, QuestConfig.FAUCET_TIER.get())`
- 建议改法: 把第 126 行改成: "1. **系统印钞（faucet）** → 矿工/农夫/渔夫/特勤/精英怪并入同一 `credit_faucet` 主闸（正常落点 ~14.9 万/人/日）；**任务奖励例外，走独立 `quest_faucet` 键且默认不衰减（日常保底 10,000 CP/人/日 + 周常 6,000×难度），封顶手段是槽位数而非衰减系数，判据见 `EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY` 的 javadoc**。" 同时在第 27 行附近的职业收入表补一行任务系统来源, 并把第 74 行"通胀平衡"的人均日印钞数字上修 1 万以上重算。

**[Critical] [D-覆盖缺口] 任务系统是独立于主闸且默认不衰减的信用点 faucet,收支总表完全未登记(并与本文档"不另开印钞口"的自述直接矛盾)**

- 位置: 第 25-38, 71 行
- 文档原文: 第二章"各职业收支定位"表只列 矿工/农夫/渔夫/军火商/千年工程师/厨师/塔罗师/特勤/精英怪 九行; 第四章 L71 `| 销毁（真 sink） | 离开经济 | 工费 1.5/发 · 卡包/开箱 · 跳蚤手续费 · 重置(钻) · 系统买枪弹 |`。全文 grep "任务" 与 "quest" 命中 0 次。
- 代码实际: quest 包(35 个 Java 文件 / 49 个 GameTest)有四类任务来源, 发奖走一个**独立于主闸的** 每日 faucet 计数键 quest_faucet, 即任务信用点不撞 GLOBAL_DAILY_CREDIT_FAUCET_KEY 主闸。这是一条既有产能又刻意绕开主闸的 faucet, 而顶层收支总表对它零登记, 任何据此表做通胀估算的决策都会系统性低估每日印钞量。
- 取证: src/main/java/com/miningdim/quest/QuestRewards.java:80-82 `return EconomyServices.economyService().grantDaily(player, raw, EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY, QuestConfig.FAUCET_TIER.get());`; src/main/java/com/miningdim/economy/EconomyConstants.java:106 `public static final String QUEST_DAILY_CREDIT_FAUCET_KEY = "quest_faucet";`; docs/modules/INVENTORY.md:22 `| wok-quest | WOK-任务模块 | 35 | 49 | QuestSystem | 核心、经济、WebUI、附魔；TaCZ 可选 |`
- 建议改法: 在第二章"各职业收支定位"表后补一行非职业 faucet: `| 任务(每日/周常/特殊/隐藏) | 完成任务发信用点(faucet,独立计数键 quest_faucet,默认 FAUCET_TIER=1,000,000 永不撞衰减) | — | 活跃度 faucet,日常任务下限即 10,000/人/日(QuestConfig.DAILY_REWARD_BASE 回推) |`;在第四章"通胀闸"算式里把任务 faucet 单列并入"系统每日印钞"总量,不能只算 credit_faucet 主闸落点;同时修正第五章第122行"矿工/农夫/渔夫/特勤/精英怪(全部并入同一 credit_faucet 主闸,不另开印钞口)"这句表述,加注"任务系统的 quest_faucet 是刻意开的第二条独立印钞口,理由见 EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY 与 QuestRewards 类注释",避免读者据此句误判全服只有一条印钞通道。

### `docs/Economy_Completeness_Audit.md`

**[Critical] [C-实现状态标错] 缺口[3]塔罗卡包量纲错配已按建议修法闭合**

- 位置: 第 109-113, 192 行
- 文档原文: **[3] 塔罗信用点卡包量纲错配，设计中最大的日常 CREDIT sink 恒失败** — 证据: `TarotPackItem.java:79-89` 把 `dailyPackCap()`…传给 `tryChargeDaily` 的 dailyCap…`0+200>20` 恒真…修法二选一: 另建包数计数键，或把 cap 改成 PRICE 乘 LIMIT。
- 代码实际: 已按「另建包数计数键」这一修法落地: 包数上限由 TarotPackSavedData 独立计数, 钱走不带 dailyCap 的 tryCharge。全库 tryChargeDaily 已无任何生产调用方 (仅剩 economy 包内的接口/实现定义与测试), 因此 L47 所称「tryChargeDaily 唯一生产调用方传参量纲错误」也一并失效。
- 取证: src/main/java/com/miningdim/job/tarot/pack/TarotPackService.java:37-48 「if (!testMode && !data.canAcquire(player.getUUID(), count, cap, today)) { return new PurchaseResult(PurchaseStatus.DAILY_LIMIT, …); } long unitPrice = price(kind); long totalPrice = Math.multiplyExact(unitPrice, (long) count); if (!testMode && totalPrice > 0L && !EconomyServices.economyService().tryCharge(player, currency(kind), totalPrice))」; grep tryChargeDaily 在 src/main/java 的非测试命中只有 EconomyLedger.java:72 / EconomyService.java:108,112 / IEconomyService.java:119 / SqliteEconomyLedger.java:205 五处定义
- 建议改法: 把 docs/Economy_Completeness_Audit.md 第 109-113 行的 [3] 标注为已闭合, 并注明落地点 TarotPackService.java:37-48(采用"另建包数计数键"方案: TarotPackSavedData.canAcquire/recordAcquired 独立记包数, 扣费改走不带 dailyCap 的 tryCharge); 删除第 47 行"以及 tryChargeDaily 唯一生产调用方传参量纲错误"半句, 改为"tryChargeDaily 现已无生产调用方, 属可删的死接口"; 第 192 行"修复优先级"第 3 条只保留"给 tarot_pack_common/advanced/shiny 与 munitions_bench/gunsmith_press/gunsmith_assembly_bench 六个 sink 载体补生存配方或战利品途径"这一半, 删去已不成立的"修 TarotPackItem 量纲"半句; 同时应联动核查文档"总评 48%"与"日常真 sink 约 67%"等衍生结论是否需要因此项闭合而重新计算。

**[Critical] [C-实现状态标错] 缺口[6]champions奖励静默归零已修复但仍标为未修**

- 位置: 第 125-128, 196-198 行
- 文档原文: **[6] 装上 champions mod 即静默清零全服精英怪奖励 (capability 契约失配加抢先 discard)** — 证据: `AgentRewardHandler.java:59-71` 挂 `EventPriority.HIGHEST`，championOf 失败即 `ContributionTracker.discard` 并 return；判定源 `AgentChampionData.java:47-56` 读第三方 `ChampionCapability`…注册门 `AgentSystem.java:55-62` 仅 `ModList.isLoaded(champions)`。
- 代码实际: AgentChampionData.java 已从仓库整条删除 (rg --files 在 src/main/java/com/miningdim/job/agent/ 下无此文件), 探测源改读自研 MiningChampions, handler 无条件生效且明令禁止 drain/discard。该文所列的三处证据行全部失效, 修复优先级第 4 条给出的修法也已按第一方案落地。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:57-62 「探测源已改自研 {@link MiningChampions#get}, 不再触任何 top.theillusivec4.champions.*, 由 {@link AgentIntegrationBootstrap} 挂 forgeBus。【醒目约束】本 handler 无条件生效 (探测源已自研, 不依赖 Champions 加载与否), 且永远不得调用 {@link ContributionTracker#drain} 或 {@code discard}」; 同文件 L72-75 「MiningChampionData champ = MiningChampions.get(victim).orElse(null); if (champ == null || !champ.isChampion()) { return; }」; 同文件 L3-4 import com.miningdim.champion.MiningChampionData / MiningChampions (无任何 top.theillusivec4 引用)
- 建议改法: 把[6]整条移入新增的「已闭合」章节, 正文改为: 「[6] (已于 2026-08-16 提交 c5cd8bc9 随特勤集成层脱离 Champions 改造闭合) 探测源已改 `MiningChampions`, `AgentChampionData.java` 已整体删除, `AgentSystem.java:14-17` 的类注释自陈旧版 ModList.isLoaded 守卫是缺陷源且已改无条件装配(见同文件 L33-41), `AgentRewardHandler.java:57-62` 明令禁止调用 `ContributionTracker#drain`/`discard`、实际只在 L103 调用 `peek`, 判定源见 L74-84。」同时把「五、修复优先级」第4条整条删除或标注「已闭合」, 并在L24「即时地雷」段落前加删除线或「已闭合」标注, 避免读者据此重复投入修复或误判上线风险。

**[Critical] [C-实现状态标错] 缺口[7]"跳蚤市场游戏内不可达"及总评L18/L26同一前提已被2026-08-15合并的WebUI接线(commit 49d5283)推翻,应改列为已开放的现行风险**

- 位置: 第 130-133, 18, 26, 77 行
- 文档原文: **[7] 跳蚤市场服务端 production 但游戏内完全不可达，唯一 P2P 通道与第二大 sink 双双为零** — 证据: `WebBrowser.java:67-70` 的 `loadURL`…零外部调用方；唯一打开路径…是内联 echo 开发页；grep `Commands.literal` 无 market 根、无 KeyMapping。
- 代码实际: 已存在默认 G 键的 KeyMapping 与加载正式远端前端的 openWebUi() 路径, loadURL 在 WebUiClient 内有真实调用方。L18「第二大 sink (跳蚤 20% 手续费) 在游戏内无任何入口」与 L26「当前之所以还没爆，是因为跳蚤市场在游戏内根本打不开」这两条总评级断言随之失效, 而整份报告的风险模型正是建立在它们之上。
- 取证: src/main/java/com/miningdim/client/webui/WebUiKeyMappings.java:55-61 「/** 打开平板 hub。默认 G (原版未占用), 玩家可在控制设置改绑。 */ public static final KeyMapping OPEN_WEB_UI = new KeyMapping(… GLFW.GLFW_KEY_G, CATEGORY);」; src/main/java/com/miningdim/client/webui/WebUiClient.java:103-105 「public static void openWebUi() { openScreen(MiningClientConfig.webUiUrl(), "WOK", false); }」; 同文件 L154 「b.loadURL(pageUrl);」
- 建议改法: 按待核发现的建议改写,并补充两点使风险方向不被误读为"已解决/降级": 1. 改写[7](第130-133行):标题改为"[7] 跳蚤市场入口已接通(2026-08-15后),但手续费sink与撮合链路是否已在此入口下实际生效未经验证"。证据换成 `WebUiKeyMappings.java:56-61`(默认G键 `OPEN_WEB_UI`)、`WebUiKeyMappings.java:84`(tick中调用 `WebUiClient.openWebUi()`)、`WebUiClient.java:101-102`(`openWebUi()` 加载 `MiningClientConfig.webUiUrl()` 配置化正式前端,而非内联echo页)、`WebUiClient.java:154`(`b.loadURL(pageUrl)` 真实调用点)、`MarketActions.java:73-86` 经 `MarketSubsystem.java:45-51` 把12个 market.* action 注册进 `WebUiServerDispatcher`。 2. 改写第18行:"第二大 sink (跳蚤 20% 手续费) 在游戏内无任何入口" 改为 "第二大 sink (跳蚤 20% 手续费) 的游戏内入口已于2026-08-15随WebUI接线(commit 49d5283)开放,但手续费扣缴是否在该入口下真实生效尚需另行核实"。 3. 改写第26行:不能只删除"还没爆"的表述,要把风险时态从未来改成现在。建议改为:"跳蚤市场在游戏内的接口缺口已于 2026-08-15(commit 49d5283)之后闭合(默认G键 -> 正式前端 -> market.* 12个action)。这意味着上述[1]~[6]所列的faucet/sink缺陷理论上已具备被现网触发的通道,风险应按'现行'而非'一旦接上'处理,须立即核实真服是否已出现相应异常流水。" 4. 建议在文档顶部(标题下方的审计元信息处)补一行"已知过期声明":本报告基线为 8039ae5(2026-07-15),晚于本次审计撰写(2026-08-05)一个月的 PR#28(commit 49d5283,2026-08-15 合并)已完成WebUI全量接线,[7]及依赖其结论的L18/L26需以此为界重新评估,避免读者按旧结论误判优先级。 5. 追加一条新的核实型 finding(建议列为Major,待专项复核):"P2P市场入口开放后,[1] settleOreSale双重变现、[3]塔罗信用点卡包量纲错配等 faucet/sink 缺陷是否已在正式服(参考用户侧记忆:WebUI已于2026-08-19在 home.shinoyuki.cn:8443/ui/ 真实上线)被实际触发,需要拉取真服资金流水/日志比对,而非仅代码可达性判断。"

**[Critical] [A-文档与代码不符] "全库零 mixin, 攻击面可正式关闭"与现状相反(mixin 基础设施于审计基线之后引入)**

- 位置: 第 176 行
- 文档原文: 6. **全库零 mixin**: `src/main/resources` 无任何 mixins.json，`src/main/java` 无 `org.spongepowered.asm` 引用，不存在改写原版掉落/破坏/背包路径的字节码注入。所有关于挖矿结算与取消事件的结论不会被绕过，这条攻击面可正式关闭。
- 代码实际: 仓库现有两份 mixins.json 与 6 个 mixin 类, build.gradle 已引入 mixin 插件。其中 VanillaOreFishMixin 用 @Redirect 直接替换原版钓鱼战利品表的返回值 —— 正是「改写原版掉落路径的字节码注入」, 而钓上来的矿石鱼又是一条 CREDIT faucet 的原料。审计把这条攻击面「正式关闭」的结论已经不成立。
- 取证: src/main/resources/miningdim.mixins.json 与 src/main/resources/miningdim.compat.mixins.json 均存在; src/main/java/com/miningdim/mixin/VanillaOreFishMixin.java:16-22 「/** Replaces only the successful vanilla fishing loot list before Forge can cancel its later drop event. */ @Mixin(FishingHook.class) … @Redirect(method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I", at = @At(value = "INVOKE", target = "…LootTable;getRandomItems…"))」; 同目录另有 PlayerOreSoupStateMixin / ItemStackMiningDurabilityMixin / PlayerFoodExhaustionMixin / MoveSpeedCheckMixin / compat/TideOreFishMixin; build.gradle:6 「id 'org.spongepowered.mixin' version '0.7.+'」
- 建议改法: 将 docs/Economy_Completeness_Audit.md 第176行第6条整条重写为: "本仓已自带 mixin(`src/main/resources/miningdim.mixins.json` 必需 + `miningdim.compat.mixins.json` 可选, 共6个类: MoveSpeedCheckMixin/VanillaOreFishMixin/PlayerFoodExhaustionMixin/PlayerOreSoupStateMixin/ItemStackMiningDurabilityMixin/compat.TideOreFishMixin, build.gradle 已接入 org.spongepowered.mixin 插件)。与经济结算直接相关的是 VanillaOreFishMixin(用 @Redirect 替换原版钓鱼战利品表, 产出的矿石鱼经 OreFishSellService 计入 credit_faucet)与 ItemStackMiningDurabilityMixin(改写 ItemStack.mineBlock 路径); 这两处均晚于本审计基线 commit 8039ae5 引入(2026-08-19/2026-09-06), 任何关于挖矿/钓鱼掉落链路'不可被字节码绕过'的结论需重新对这6个注入点取证, 不应视为已关闭的攻击面。"

**[Critical] [F-结构问题] 审计报告与设计规格同目录, 代码已把冻结报告当现行真源**

- 位置: 第 3 行
- 文档原文: L3「审计日期: 2026-08-05」(全文是一次性审计报告, 无失效声明、无「本文不是实现依据」的类型标注)
- 代码实际: docs/ 把「该照着做的规格」和「历史记录」混在同一层目录, 文件名与位置都不带类型信号。后果已经外溢到代码: 任务系统三个 Java 类把这份 2026-08-05 冻结的审计报告当成长期设计依据写进 javadoc, 而该报告的多条核心结论(跳蚤市场不可达、champions 奖励静默归零、AZURE 回收率恒为 0 等)早已被后续提交推翻。
- 取证: src/main/java/com/miningdim/quest/QuestConfig.java:8「<b>全部经济数值都在这里, 一条都不写死在内容池里。</b> 原因见 docs/Economy_Completeness_Audit.md: 信用点的…」; src/main/java/com/miningdim/quest/QuestDefinition.java:7「把数值从内容里剥离有两个理由: 一是经济尚未完成全局净流入核对 (见 docs/Economy_Completeness_Audit.md,…」; src/main/java/com/miningdim/quest/QuestRewards.java:12「只有一处可查、一处可改 —— 经济尚未做过全局净流入核对 (docs/Economy_Completeness_Audit.md), 将来要么改档位…」。同类型混放还有 src/main/java/com/miningdim/config/MiningServerConfig.java:259「+ "See docs/TaskSpec_Mining_EntryFee.md.");」—— 把一份已交付的任务单当成配置键的常驻说明出处。
- 建议改法: 按类型分目录: docs/spec/(现役设计规格) / docs/task/(任务单) / docs/report/(审计与分支审查报告, 一律带冻结基线 commit 与「非实现依据」抬头) / docs/asset/(资产清单) / docs/manual/(玩家与服主手册) / docs/archive/(归档)。报告类一律禁止被代码注释引用, 代码需要长期理由时把结论正文搬进对应的现役规格再引。上述四个 Java 引用点随迁移一并改指。

**[Critical] [E-状态过期] 报告无失效标注, 下游仍据其阻断新faucet**

- 位置: 第 3-6, 10-26 行
- 文档原文: 审计日期: 2026-08-05 / 基线 commit: 8039ae5 …「一、总评: 48% **结论: 不能上线跑。** …三条主干同时断裂: 1. **CREDIT sink 全线失效**…2. **AZURE 是纯单向货币**…」
- 代码实际: 八条 Critical 中至少四条 ([3][4][6][7]) 已在代码侧闭合, 但全文没有任何复核日期、失效标记或「已闭合」清单, 头部仍以「不能上线跑 / 48%」作为现行结论。下游文档仍照此做门禁决策, 属于会直接导致错误决策的过期状态。
- 取证: 闭合证据见本轮其余四条发现 (AgentRewardHandler.java:57-75 / TarotPackService.java:37-48 / TarotSystem.java:120-124 / WebUiKeyMappings.java:55-61); 下游援引: docs/SpecialAgent_Job_DesignSpec.md:231 「注意 `Economy_Completeness_Audit.md` 判定 CREDIT sink 全线失效，在 sink 修好前新增 faucet 会直接抬升净印钞。」
- 建议改法: 在文首 L6 之后加一段"复核记录"表, 至少列出: [3] TarotPackService.java(2026-08-16 前后重构)已闭合 —— 包数日限与信用点扣费已拆分为独立字段, 量纲错配不复存在; [4] 已闭合 —— `/tarot pack buy shiny`(TarotSystem.java:120-124)已提供生存模式 AZURE 消耗入口; [6] 已闭合 —— commit c5cd8bc9(2026-08-16)"特勤集成层脱离已废弃 Champions capability, 改读自研 MiningChampions", AgentRewardHandler 现用 peek 不再 drain/discard; [7] 已闭合 —— commit f107aa26(2026-08-12T10:52:50+08:00, 晚于本文档定稿 20 分钟)落地 WebUiKeyMappings 默认 G 键入口, 加载正式前端而非开发 echo 页; [1][2][5][8] 仍成立(需按各自最新代码复核一遍再定档)。同时把 L10 总评改为"48% 为 2026-08-05 基线评分, 截至 <复核日> 已闭合 [3][4][6][7], 现分见二、复核记录表, 未复核项不代表仍然有效"。并在 SpecialAgent_Job_DesignSpec.md:231 处补一句"该判定中 CREDIT sink(塔罗卡包分支)与 AZURE 侧已于 2026-08-16 前后闭合, 供给锚点拍板前请核对 Economy_Completeness_Audit.md 的复核记录表而非仅看头部总评"。

### `docs/Economy_Laundering_Review.md`

**[Critical] [C-实现状态标错] /fishing sell 是新的"印钞级"洗钱口子而非 V7 红线触发: 文档"唯一卖菜口子, 硬顶2160/天"的结论(第11/39行)已过期, 鱼类共享矿工同款主闸可达约14.9万/天/号**

- 位置: 第 75, 60 行
- 文档原文: L75「**T0-3 刷怪/无限输入物 NPC 收购红线 + 回归测试。** 当前刷怪掉物零 NPC 铸币口(正确), 但这是"恰好没接", 脆弱。…并文档化设计禁令: 任何可被刷怪塔/自动农场无限产出的物品, **严禁挂带正地板的 NPC 收购价**(走 AZURE 或无地板趋零)。」; L60 漏洞表 V7 真实严重度「预防性 Major(当前=0)」
- 代码实际: 矿石鱼卖出口已上线: 手持整摞鱼一次卖出, 单价是配置里的固定正值 (20/80/400/600/2000), 全程无逐条递减曲线, 只受全服 credit_faucet 主闸约束 —— 而主闸带 1% 正地板, 正是 V7 描述的「击穿 1% 尾巴」形态。钓鱼在原版可挂机自动化, 该红线已从「预防性」变成「已触发」。
- 取证: src/main/java/com/miningdim/job/fisher/ore/OreFishSellService.java:53-60 「int count = stack.getCount(); long gross = Math.multiplyExact(OreFishingConfig.sellPrice(type), count); stack.shrink(count); … granted = EconomyServices.economyService().grantDaily(player, gross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);」; src/main/java/com/miningdim/job/fisher/ore/OreFishingConfig.java:25 「long[] prices = {20L, 80L, 400L, 600L, 2000L};」
- 建议改法: 不要按 T0-3(刷怪掉物)的框架去改——那类物品现状确实仍是零通道, 未被打破。应改文档第一/三节的量级结论并新增清单项: 把"唯一能凭接收物品铸新钱的口子只有卖菜, 硬顶2160/天"(第11、39行)更正为"卖菜与卖鱼两条", 并在漏洞清单里新增一条(建议编号 V10): "/fishing sell 定额收购无本地衰减曲线 + 无职业门, 与矿工共享同一衰减主闸, 单号可凭受赠鱼铸新钱至约14.9万/天(与满课矿工同量级)", 严重度 Critical。修复建议比照已有 Tier 0 手法: (a) 参照 T0-1, 给 FishingSystem.java:81 的 sell 分支加职业等级门; (b) 参照 T0-2 与 FarmerWheatBuyback 的本地递减曲线, 给 OreFishingConfig 的定额单价补一条随当日累计销售量递减、最终趋零的本地曲线(而不是仅依赖矿工同款的1%地板), 或按 T0-3 禁令的精神让深档单价直接趋零/改走 AZURE 计价; (c) 补一条契约 GameTest, 断言"单号每日卖鱼铸出的净 CREDIT 存在与卖菜同量级(千级)的硬顶, 而非撞到矿工级的十万级渐近线"。

### `docs/Ore_Pricing_Ledger_DesignSpec.md`

**[Critical] [C-实现状态标错] settleOreSale 绕过主闸的描述已过期**

- 位置: 第 121-124, 167-168 行
- 文档原文: L167-168「`settleOreSale`（`EconomyService.java:80-89`）直接 `ledger.credit` 入账，**不经** `grantDaily`…全库 grep 证 `settleOreSale` **无生产调用方**…即当前生产路径下卖矿一分钱都没真正入账」；L122「这正是当前代码 `settleOreSale`（`EconomyService.java:80-89`）的真实行为（直接 `ledger.credit`，不走统一 faucet）」
- 代码实际: settleOreSale 现在是两层串联：先用 buyPrice 取逐矿 steering 毛值，再经 grantDaily(credit_faucet, 60000 档) 并入全服统一衰减主闸，且函数在 EconomyService.java:116-135（非 :80-89）。生产调用方有两条：EconomySystem.onBlockBreak 的单块卖矿、以及 EconomyService.recordMinedOreDrops 里连锁/隧道产出的逐颗结算。红队 Critical-1「高价矿独立封顶且可叠加」已在代码里闭合，第六章的「独立封顶可叠加」口径警告不再是现状描述。
- 取证: src/main/java/com/miningdim/economy/EconomyService.java:130-131 `long effective = grantDaily(player, gross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);`（:117-119 注释「此前直接 ledger.credit 绕过主闸 = 卖矿不受全服日衰减约束的印钞口 (红队 Critical), 现并入主闸闭合」）；src/main/java/com/miningdim/economy/EconomySystem.java:228 `EconomyServices.economyService().settleOreSale(`
- 建议改法: 将第八章 8.2（docs/Ore_Pricing_Ledger_DesignSpec.md:165-169）整节改写为：「settleOreSale 已并入 grantDaily 统一主闸（EconomyService.java:116-135，关键行 :130-131），生产调用方有二：EconomySystem.recordAndSettleBreak（:223-230，由 onBlockBreak 事件 :200 触发单块卖矿）与 EconomyService.recordMinedOreDrops（:138-166，由 MinerSystem.replayEconomyOreCount :248 经 MinerActions.java:193 触发连锁/隧道逐颗结算）」；同时第六章 L121-124 的口径警告改为「已按并入主闸口径，各矿 base×cap 降级为相对权重，不再可叠加」，并将第六章合计表标注为「未经主闸衰减的毛值上界」。此外可直接引用文档自身第十一章 :255-259（用户 2026-06-18 决策定稿「② 统一收入主闸」）作为回填依据，避免重复造轮子——第十一章已经是正确口径，只是没有回填进第六、八章的早期表述。

**[Critical] [A-文档与代码不符] 衰减地板仍写 0.25，代码已是 0.01**

- 位置: 第 14, 22, 48, 133-136 行
- 文档原文: L14「衰减口径：`price(n) = base × max(0.25, 0.97^max(0, n-cap))`」；L22「跌到 base 的 25% 地板。例：钻石第 65 个 = 485，深 over 夹到 125。」；L134「钻石地板 = 500×0.25 = **125 CP/个**；金 = 30；残骸 = 1,125」
- 代码实际: 地板常量已按本文第十一章决策①由 0.25 砍到 0.01(1%)，但第一/三/七章仍按 0.25 叙述并据此算出 125/30/1125 三个地板价。实际地板价是 500×0.01=5 / 120×0.01=1.2 / 4500×0.01=45，深档尾巴比文中细 25 倍，第七章第 1 条据 0.25 推出的「钻石 8h 约 19 万 CP、是 ceiling 的 6 倍、单矿即反超军火商 17.5 万」在代码里不成立。同一份文档 L279-281 自己写的是 1%，三章互相打架。
- 取证: src/main/java/com/miningdim/economy/EconomyConstants.java:68 `public static final double ECONOMY_PRICE_FLOOR_RATIO = 0.01D;`（L63 注释「收购价/faucet 两层统一地板比例 (第十一章决策 1: 0.25 -> 0.01, 即 1%)」）；src/main/java/com/miningdim/economy/AbuseGuard.java:237 `double ratio = Math.max(EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO, decayed);`
- 建议改法: L14 公式改为 `price(n) = base × max(0.01, 0.97^max(0, n-cap))`；L22 改为「跌到 base 的 1% 地板。例：钻石第 65 个 = 485，深 over 夹到 5」；L48 与 L133-136 中的 0.25 全部改为 0.01，并把地板价重算为 钻石 500×0.01=5 / 金 120×0.01=1.2 / 残骸 4,500×0.01=45（可引用 EconomyGameTests.java:1271-1292 的 `priceFloorRatioIsOnePercent` 测试断言值核对）；第七章第 1 条基于 0.25 得出的「约 19 万 CP / 约 6 倍 / 单矿反超军火商 17.5 万」及"二选一(a)地板砍到0或~0.02"的未决论调，需按 1% 地板重新推算，或整段标注为「0.25 地板时代的历史结论，已由第十一章决策①(2026 前后)作废，现地板见 EconomyConstants.java:68」，避免读者误以为该 Critical 问题仍待裁定。

**[Critical] [A-文档与代码不符] 高价矿排除连锁的铁律已被代码推翻**

- 位置: 第 57, 194 行
- 文档原文: L57「2. **高价矿物理排除连锁/隧道**（`CHAIN_HARD_EXCLUDE` `MinerConstants.java:170-175`）：钻石/金/残骸/绿宝石只能手挖单块。铜/铁/煤才进白名单（`:159-164`…）」；L194「高价矿全在 `CHAIN_HARD_EXCLUDE`（`MinerConstants.java:170-175`…），物理排除连锁/隧道，只能手挖单块。」
- 代码实际: CHAIN_WHITELIST / CHAIN_HARD_EXCLUDE 双表已在 2026-07-12「矿洞维度内连锁全放开」裁决后从 MinerConstants 删除，全库只剩 ChainMiningEngine 的一行注释提到这两个旧名。现行唯一判定 chainable() 只看「镐可采 + 工具档位够 + 可破坏 + 无 BlockEntity」，钻石/金/残骸矿全部满足，可被连锁连带；连带产出还经 EconomyService.recordMinedOreDrops 逐颗走 settleOreSale 真发钱。第四章铁律第 2 条以及由它支撑的「高价矿撞 cap 速度不被吞吐加速」在代码里已不成立。
- 取证: src/main/java/com/miningdim/job/miner/ChainMiningEngine.java:273 「单一连锁判定 (替代旧 CHAIN_WHITELIST / CHAIN_HARD_EXCLUDE 双表, 用户 2026-07-12 裁决"矿洞维度内连锁全放开")」；:285-289 `public static boolean chainable(...) { return state.is(BlockTags.MINEABLE_WITH_PICKAXE) && tool.isCorrectToolForDrops(state) && state.getDestroySpeed(level, pos) >= 0.0F && level.getBlockEntity(pos) == null; }`（无任何矿种排除）；src/main/java/com/miningdim/economy/EconomyService.java:148-150 「2026-07-12 连锁全放开后高价矿可被连带 (ChainMiningEngine 废除枚举白名单 / 高价矿硬排除双表), 本路径已实际发钱」
- 建议改法: 删掉 L57/L194 对 `CHAIN_HARD_EXCLUDE` / `CHAIN_WHITELIST` 的引用与行号(该区间已不存在, `MinerConstants.java` 全文件仅 168 行), 改述为"连锁/隧道在矿洞维度已全放开(2026-07-12 裁决, 判定唯一谓词见 `ChainMiningEngine.chainable`, :285-289), 高价矿(钻/金/残骸/绿宝石)用正确档位镐即可连带, 连带产出经 `MinerSystem.replayEconomyOreCount` -> `EconomyService.recordMinedOreDrops` -> `settleOreSale`(`EconomyService.java:148-163`)逐颗真实入主闸"; 第四章"核心铁律"第 2 条整条重写为"吞吐不再受矿种白名单/黑名单限制, 收入封顶完全依赖衰减主闸(而非矿种物理排除)", 并同步检查第七章、8.5 节等依赖此铁律的推导段落是否需要连带更正措辞。

**[Critical] [C-实现状态标错] 绿宝石/残骸 BLOCKED-BY-WORLDGEN 已不成立(残骸worldgen已接线且已是活结算faucet；绿宝石仅"无结算通道"半句仍成立)**

- 位置: 第 65-66, 73, 76, 105, 118, 158, 163, 216, 240 行
- 文档原文: L158「**三 biome 全无绿宝石 feature、全无残骸 feature**。绿宝石/残骸在真源 worldgen 产出为 0，给它们定价（绿宝石 18,000 / 残骸 36,000）当前**空转**。」；L73 残骸行「**真源任一 biome 均无残骸 feature**（BLOCKED-BY-WORLDGEN）」；L118「残骸虽是最高单矿 36,000 但 worldgen 拿不到，不计」
- 代码实际: mining_hard.json 的 feature 列已包含 miningdim:ore_ancient_debris 与 miningdim:ore_emerald，对应的 configured_feature / placed_feature JSON 也都已落仓。残骸还完整接着结算通道（HighValueOre.NETHERITE_SCRAP 枚举 + ShopPriceTable 4500 + AbuseGuard 把 ancient_debris 方块分类到 NETHERITE_SCRAP），即 Hard 区残骸 36,000/日 已是活 faucet 而非纸面，第六章 Hard 合计 73,220 与 8.3 的「矿工现实 5.5-7 万 < 军火商 17.5 万」跨职业序都需重算。绿宝石仍不在 HighValueOre 枚举，「无结算通道」这一半仍成立。
- 取证: src/main/resources/data/miningdim/worldgen/biome/mining_hard.json:56-57 `"miningdim:ore_ancient_debris", "miningdim:ore_emerald",`；src/main/java/com/miningdim/economy/AbuseGuard.java:61 `m.put(EconomyConstants.ORE_ANCIENT_DEBRIS, HighValueOre.NETHERITE_SCRAP);`；src/main/java/com/miningdim/economy/ShopPriceTable.java:28 `public static final double ORE_BASE_NETHERITE_SCRAP = 4_500.0D;`
- 建议改法: 在原建议基础上补一条, 其余原样保留: 1) 5.1(:73)残骸行"区域可得"列由「真源任一biome均无残骸feature（BLOCKED-BY-WORLDGEN）」改为「Hard已接线（mining_hard.json:56, GameTest MinerGameTests.ancientDebrisAndEmeraldFeaturesWiredIntoHard 覆盖）」, 状态列保留 DECIDED 但去掉"产出待接线"字样(产出已接线, 只是尚未走统一 credit_faucet 主闸, 该口径问题另属第九章Critical-1, 与worldgen无关)。 2) 5.1(:76)绿宝石行只保留「无结算通道（不在HighValueOre枚举, EconomyConstants.java:199-203）」, 删除「真源三biome均无绿宝石feature」(mining_hard.json:57已有ore_emerald)。 3) 8.1(:158)"关键事实"首条整条重写为: "Hard biome 已接线残骸(mining_hard.json:56)与绿宝石(:57) feature, 由GameTest固化; 残骸已进结算通道(AbuseGuard.java:61→NETHERITE_SCRAP, ShopPriceTable.java:28 base 4,500, EconomyConstants.java:53 cap 8), 是活faucet；绿宝石仍不在HighValueOre枚举, 无结算通道, 进系统须补EMERALD三处接线(枚举+软上限常量+buyPrice/classify分支)。" 4) 8.1(:163)"定价前置"删除"要么补自定义emerald/debris PlacedFeature"一句(已存在), 改为"绿宝石定价前提是先补EMERALD结算通道三处接线, 残骸worldgen前提已满足"。 5) 8.2(:165-169)同步更正: 删除"settleOreSale无生产调用方""EconomySystem.onBlockBreak只recordMinedOre计数、不发钱"的表述, 改为如实描述 EconomySystem.recordAndSettleBreak 已在生产路径调用 settleOreSale(经 abuseGuard.classify 分流), 但仍应保留其原有的"未经grantDaily统一主闸、绕开credit_faucet软上限"这一真实存在的Critical-1问题描述, 只是调用方从"无"改为"有, 但绕主闸"。 6) 第六章(:112-118) Hard合计由73,220改为109,220(+残骸36,000), 表格与算式同步更新; 8.3(:171-184)"矿工现实5.5-7万"与"跨职业序"需按新增的活跃残骸faucet重新估算(残骸cap仅8个/日, 对"现实大头"影响远小于对"理论天花板"的影响, 需分别说明, 不能笼统套用+36,000)。 7) 第九章(:216)第5行 Major 改为: "绿宝石18,000产出前提(无结算通道)成立, 残骸36,000产出前提已不成立(worldgen已接线且已进结算通道, 只是未经统一主闸)"; 处置列相应去掉"标BLOCKED-BY-WORLDGEN…不计入第六章可达合计"中关于残骸的部分。 8) 第十章(:240) Major-D 改为: "worldgen接线(残骸/绿宝石)已DONE(mining_hard.json:56-57 + GameTest覆盖); 仅剩绿宝石EMERALD枚举三处接线(HighValueOre枚举 + DAILY_SOFTCAP_EMERALD + buyPrice/classify分支)待落, 残骸待解决的是是否并入统一credit_faucet主闸(见Critical-1), 不再是worldgen问题"。

### `docs/服务器经济系统设计文档.md`

**[Critical] [A-文档与代码不符] 1.1 要求任务币设递减, 代码刻意不设**

- 位置: 第 70 行
- 文档原文: L70「任务币与卖矿/卖菜币同样须设每日上限/递减(复用 economy 既有 UTC 翻日 + 软上限框架),不可只对刷怪设闸而留任务/卖菜为无递减印钞口。」
- 代码实际: 卖矿/卖菜确实已并进 credit_faucet 主闸，但任务奖励被刻意排除在外：走独立键 quest_faucet + 档位默认 1,000,000 CP（远高于一天任务能拿到的总额），衰减系数恒为 1，实发 == 名义值。代码把这个例外写成了有完整判据的设计决策（任务供给由槽位数硬封，不是靠肝），与本行「必须设递减」的硬性要求方向相反。两边必须有一边改，否则任何人照 1.1 去审都会把任务系统判成违纪。
- 取证: src/main/java/com/miningdim/economy/EconomyConstants.java:93-98 「任务奖励专用信用点 faucet 计数键 —— <b>刻意不并入 {@link #GLOBAL_DAILY_CREDIT_FAUCET_KEY} 主闸</b> (用户决策)。…任务奖励的供给<b>由槽位数硬性封死</b>: 每人每天 {@code dailySlots} 条日常 + 每周 {@code weeklySlots} 条周常」；src/main/java/com/miningdim/quest/QuestConfig.java:86-91 「Quest payouts deliberately do NOT join the global credit_faucet soft cap… The default is far above what a day of quests can ever total, so payouts are never decayed.」
- 建议改法: 在 docs/服务器经济系统设计文档.md 第 70 行所在的 1.1 防沉迷段落末尾补一条例外并注明判据: 「任务币例外——其供给由每日/每周槽位数硬封(领取幂等落 QuestProgress.claimed), 故走独立 quest_faucet 键 + 高档位默认不衰减, 判据见 EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY; 任何产能不受槽位约束的新 faucet 仍须并回 credit_faucet 主闸, 不得援引本例。」

### `docs/Economy_BalanceSheet_DesignSpec.md`

**[Major] [E-状态过期] TACZ/Champions 待接入的星标已过期**

- 位置: 第 39, 140 行
- 文档原文: L39「\* = 待 TACZ + Champions 依赖接入」；L140「4. **军火商/特勤/精英怪收入待依赖**：本表 \* 项数值在 TACZ+Champions 接入并跑通后才生效。」
- 代码实际: 精英怪已完全自研脱离 Champions：冠军判定走本工程自建的 MiningChampions capability（1-10★ 全星级发奖），奖励结算 ChampionRewardHandler 已上线，信用点经 grantDaily 并入 credit_faucet 主闸、青辉石经 grantAzureDaily 并入每人每日硬上限；特勤的加强奖励与周常悬赏青辉石出口也都已接线（AgentRewardHandler）；军火商整条生产/工费链在 MunitionsConfig 里已有全套默认值。三个星标项已不再是「待依赖」状态。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:56-57 「冠军判定经自研 {@link MiningChampions} capability: 1-10★ 全星级冠军均发奖 (非仅 6★+ 血池冠军)」；:161-168 `EconomyServices.economyService().grantDaily(player, raw, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, …)`；src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:166-168 与 :225-226 `grantAzureDaily(player, grantable, EconomyConstants.AZURE_DAILY_FAUCET_CAP)`
- 建议改法: L39 图例由「\* = 待 TACZ + Champions 依赖接入」改为「\* = 依赖代码已接线（精英怪已自研 MiningChampions 替代 Champions API，Champions 现为 mandatory=false 可选依赖；军火商仍真实依赖 TACZ 且已完整接线），数值待真服跑量观测校准」；第六章第 4 条由「军火商/特勤/精英怪收入待依赖：本表 \* 项数值在 TACZ+Champions 接入并跑通后才生效」改写为「军火商/特勤/精英怪三条 faucet 均已落码接线（精英怪经自研 MiningChampions capability 发奖、特勤经 AgentRewardHandler 发放加强奖励与悬赏青辉石、军火商经 MunitionsConfig 全套默认值产销，三者均已并入 credit_faucet/azure 主闸），不再依赖"接入"本身，剩余风险是真服跑量后的数值校准，与本节其余 PENDING 项合并管理」。

**[Major] [A-文档与代码不符] 开箱日支出估算比单次开箱价还低**

- 位置: 第 47-51, 74 行
- 文档原文: L47 表头「| 画像 | 系统收入 | 弹药 | 修甲 | 卡包/开箱/手续费 | 刚性合计 | 净储蓄/日 |」，L49「| 休闲 | 6 万 | 2,250 | 2,000 | 3,000 | ~7,000 |」…L51「| 肝帝 | 14 万 | 18,000 | 10,000 | 25,000 | ~53,000 |」；L74「卡包 ~8k」
- 代码实际: 一次开箱在代码里销毁 50,000 信用点 + 10 青辉石。表里最肝的一档「卡包/开箱/手续费」合计才 25,000/日，连半次开箱都买不起，即这张表隐含「没有人开箱」。而第四章 L71 把开箱列为真 sink、L75 又说靠大额 sink 吸走储蓄，同一份文档两处互相拆台；按这张表做运营决策会严重低估信用点的真实销毁量。
- 取证: src/main/java/com/miningdim/caseopening/CaseOpeningConfig.java:25-28 `CREDIT_COST = builder.comment("CREDIT destroyed by one founders-case opening").defineInRange("creditCost", 50_000L, 1L, Long.MAX_VALUE); AZURE_COST = builder.comment("Bound AZURE destroyed by one founders-case opening").defineInRange("azureCost", 10L, 1L, Long.MAX_VALUE);`
- 建议改法: 把「卡包/开箱/手续费」一列拆成三列各自给量级：卡包（塔罗普通 200 CP / 高级 1200 CP，日上限 20 包，见 TarotConfig.java:120-127 的 commonPackCredit/advancedPackCredit/dailyPackLimit）；开箱（CaseOpeningConfig.java:25-28 的 creditCost=50,000 CP + azureCost=10 青辉石/次，实际频率受 EconomyConstants.java:122 每日青辉石硬上限 30 约束，即最多 3 次/日）；跳蚤手续费（MarketConstants.java:30 的 FEE_RATE=0.20 即挂单价 20% 起，偏离越大费率越高）。三档净储蓄与 L74 的日常真 sink 合计连带重算，并在表头/细分口径中明确注明「开箱属大额一次性支出，不计入日常刚性」或反之，避免表头与自身细分数据互相矛盾。

**[Major] [C-实现状态标错] 系统买枪弹 sink 在代码里无购买路径**

- 位置: 第 53, 71 行
- 文档原文: L71「| 销毁（真 sink） | 离开经济 | 工费 1.5/发 · 卡包/开箱 · 跳蚤手续费 · 重置(钻) · **系统买枪弹** |」；L53「弹药按「买军火商价 ≈ 系统 75%」算（步枪军火商 15/发）」
- 代码实际: 8.2/8.3 的商店价在代码里只是一组 config 值（MunitionsConfig.SHOP_PRICE_* 九档，数值与经济文档 8.2 完全对得上）加一个 MunitionsCaliber.shopPrice()/sellPrice() 访问器，全库除 MunitionsConfig 的声明与 MunitionsCaliber 的构造/访问器外没有任何调用方，也没有任何 tryCharge 走弹药或枪械购买。即「系统买枪弹」这条真 sink 和第三章三档「弹药」列 2,250/8,000/18,000 的刚性支出目前都无代码支撑，唯一可走的是军火商 P2P 卖弹（经跳蚤市场）。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:374-400 `SHOP_PRICE_PISTOL = b.defineInRange("pistolShopPrice", 10, 1, 1000000); … SHOP_PRICE_ANTI_MATERIEL = b.defineInRange("antiMaterielShopPrice", 200, 1, 1000000);`；src/main/java/com/miningdim/job/munitions/MunitionsCaliber.java:143 `public int shopPrice() {` 与 :148 `public int sellPrice() {` —— 对 `shopPrice(` / `sellPrice(` / `SHOP_PRICE_` 全库检索，除本文件的构造入参与这两个访问器外无任何调用点
- 建议改法: 第四章「钱去哪了」表(docs/Economy_BalanceSheet_DesignSpec.md:71)把「系统买枪弹」改为「系统买枪弹(未实现)」,或直接移入第六章 PENDING 清单;第三章弹药列(:53 附近)补一句「当前唯一可走的是军火商 P2P 卖弹(经跳蚤市场),系统弹药/枪械商店尚未接线——MunitionsConfig.SHOP_PRICE_*/MunitionsCaliber.shopPrice()·sellPrice() 全库无调用方」,避免读者据此高估日常真实销毁量、低估轻通胀风险。

**[Major] [A-文档与代码不符] 塔罗毕业 390 万被当成信用点 sink**

- 位置: 第 61, 75 行
- 文档原文: L59 表头「| 目标 | 价（信用点，8.3） | 天数 |」下的 L61「| 塔罗毕业（5 闪耀 ~390 万，8.4） | ~390 万 | ~50 天（闪耀卡走青辉石/PvE） |」；L75「缓冲 = 主闸正常落点 ~14.9 万/日…+ 塔罗的天价（390 万–1700 万）+ 持续上新内容」
- 代码实际: 闪耀卡包在代码里只收青辉石（PRICE_SHINY_PACK_AZURE 默认 64 AZURE/包），TarotPackService.currency() 对 SHINY 硬编码返回 Currency.AZURE，信用点根本买不到（config 注释直写 spec 7: never CREDIT）。所以「390 万–1700 万」既不是信用点支出，也不能当作吸走信用点储蓄的大额 sink，第四章「靠大额 sink 吸走」的通胀结论少了它自己点名的主要支柱。信用点侧真正的塔罗 sink 只有普通包 200 / 高级包 1200，且每日最多 20 包，封顶 24,000 CP/日。
- 取证: src/main/java/com/miningdim/job/tarot/pack/TarotPackService.java:68-70 `public static Currency currency(PackKind kind) { return kind == PackKind.SHINY ? Currency.AZURE : Currency.CREDIT; }`；src/main/java/com/miningdim/job/tarot/TarotConfig.java:124-125 `PRICE_SHINY_PACK_AZURE = b.comment("Shiny pack price in AZURE (spec 7: never CREDIT)").defineInRange("shinyPackAzure", 64, 0, 10000000);`；:121/123 commonPackCredit 200 / advancedPackCredit 1200；:126-127 dailyPackLimit 20
- 建议改法: 第三章把「塔罗毕业」行从「价（信用点，8.3）」表里拆出去，单列一张青辉石线的表（5 闪耀 = 5×64 = 320 青辉石，受 EconomyConstants.AZURE_DAILY_FAUCET_CAP=30/日 约束，且 AZURE 绑定不可交易/不可用信用点购买）；同时把表头引用行号从原声称的 L59 订正为实际的 L57（该行是表头，L59 是"基础套"行）。第四章 L75 把「塔罗的天价（390 万–1700 万）」改标为青辉石 sink，不再与"缓冲每人每天 ~8 万信用点盈余"混为一谈；并重新论证信用点侧真正的大额 sink（当前实际只剩高端枪 60-100 万 P2P 换手非销毁、及普通/高级卡包 200/1200 CP 共享 20 包/日上限、封顶 24,000 CP/日，且据 Economy_Completeness_Audit.md Critical[3] 该卡包 sink 目前因量纲错配代码层面恒失败，需一并核实修复状态）。

**[Major] [D-覆盖缺口] sink 表漏登五类已上线信用点 sink（金钱修补/婚姻三费/厨师调味台/枪匠冲压装配维修/任务重摇）, 另有入场费机制已实现但默认值为 0 尚未生效**

- 位置: 第 69-75 行
- 文档原文: L71「| 销毁（真 sink） | 离开经济 | 工费 1.5/发 · 卡包/开箱 · 跳蚤手续费 · 重置(钻) · 系统买枪弹 |」；L74「日常真 sink（工费 ~600 + 卡包 ~8k + 手续费 ~3k ≈ 1.2 万/人/日）**远小于** faucet → 每人每天 ~8 万在累积」
- 代码实际: 代码里另有至少六条走 IEconomyService.tryCharge 直接销毁信用点的 sink，总表一条都没登：矿洞入场费（EntryGateway.completeEnter，注释明写「费用是纯 sink，不转入任何玩家账户」）、金钱修补附魔（MoneyMendingHandler 按耐久点持续扣）、婚姻三费（买订婚戒指 / 典礼双方各半 / 离婚）、厨师调味台每次做菜、枪匠冲压·装配·维修工费（默认 200×品质×稀有度倍率 / 5000 / 1500）、任务重摇费（日常 500 / 周常 2500）。装配一把枪 5000 CP、维修 1500 CP 的量级远超表里唯一登记的「工费 ~600/日」，「1.2 万/人/日真 sink」与由它推出的「每人每天 ~8 万在累积」都失真。
- 取证: src/main/java/com/miningdim/entry/EntryGateway.java:266-267 `if (pe.entryFee > 0L && !EconomyServices.economyService().tryCharge(player, Currency.CREDIT, pe.entryFee))`；src/main/java/com/miningdim/enchant/MoneyMendingHandler.java:78 `if (!EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost))`；src/main/java/com/miningdim/marriage/MarriageEngine.java:75/139/142；src/main/java/com/miningdim/job/chef/SeasoningTableBlockEntity.java:467；src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:339 `.defineInRange("pressWorkFeeCredits", 200, 0, 1000000);`、:341 `ASSEMBLY_WORK_FEE_CREDITS = b.defineInRange("assemblyWorkFeeCredits", 5000, 0, 100000000);`、:348 `.defineInRange("repairWorkFeeCredits", 1500, 0, 100000000);`；src/main/java/com/miningdim/quest/QuestConfig.java:79/81 dailyCost 500 / weeklyCost 2500
- 建议改法: 在第四章「销毁（真 sink）」补齐五类默认非零的真实 sink 并各标量级: 金钱修补附魔按耐久点持续扣费(MoneyMendingHandler.java:78, 默认开启 MoneyMendingConfig.java:38)、婚姻三费(订婚 5000/典礼 20000 对半/离婚 10000, 证据分别为 MarriageEngine.java:75、MarriageEngine.java:139-142、MarriageDivorce.java:123, 配置见 MiningServerConfig.java:267/269/295)、厨师调味台每次 5 CP(SeasoningTableBlockEntity.java:467, ChefConfig.java:444)、枪匠冲压/装配/维修工费 200(×品质倍率)/5000/1500(MunitionsConfig.java:339/341/348, 其中装配维修已见于 Munitions_Job_DesignSpec.md:182, 仅未汇总进本表)、任务日重摇 500/周重摇 2500(QuestConfig.java:79/81)。入场费单独一行注明: 代码已实现(EntryGateway.java:266-267)但 entryFeeEasy/Medium/Hard 三档默认值均为 0(MiningServerConfig.java:260-262, 注释"0 = free; values await yield measurement before tuning"), 当前不产生实际销毁, 待调优后再计入总表。重算 L74 时不要把装配/维修/婚姻这类角色向或低频一次性支出直接按“每人每天”与工费~600/卡包~8k/手续费~3k 相加平均, 应先在文中区分"average 玩家日常经常性 sink"与"特定职业/低频事件 sink", 再据此判断「每人每天 ~8 万在累积」的方向性结论是否仍然成立。

**[Major] [D-覆盖缺口] 金钱修补是常驻信用点真 sink, sink 清单里没有它**

- 位置: 第 71 行
- 文档原文: | 销毁（真 sink） | 离开经济 | 工费 1.5/发 · 卡包/开箱 · 跳蚤手续费 · 重置(钻) · 系统买枪弹 |
- 代码实际: enchant 模块的"金钱修补"(money_mending)是一条持续运行的信用点真 sink: 装备在身时按每秒固定耐久点扣信用点, 单价 = 物品材料总价 / 最大耐久 × 2.0 倍率, 由独立配置 miningdim-money-mending.toml 控制。它既不在 L71 的真 sink 清单里, 也不在 L47 三档玩家净储蓄表的任何一列(该表的"修甲"按 L33 指的是千年工程师 P2P 收费, 属再分配不是 sink)。据此表估算日均 sink 会漏掉一整条随在线时长线性增长的支出。
- 取证: src/main/java/com/miningdim/enchant/MoneyMendingHandler.java:78 `if (!EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost)) {`; src/main/java/com/miningdim/enchant/MoneyMendingConfig.java:38-52 `PRICE_MULTIPLIER = builder.comment("Repair cost = (item material value / max durability) * this multiplier...").defineInRange("priceMultiplier", 2.0D, 1.01D, 100.0D);` 与 `REPAIR_POINTS_PER_SECOND = ... defineInRange("repairPointsPerSecond", 10, 1, 1_000);`; src/main/resources/assets/miningdim/lang/zh_cn.json:2 `"enchantment.miningdim.money_mending": "金钱修补"`
- 建议改法: 在 L71 真 sink 一行的例子里加入"金钱修补(按秒扣费, 单价=材料总价/最大耐久×priceMultiplier, 见 MoneyMendingConfig)"; 在 L47 的三档净储蓄表新增一列或并入"修甲"列并加脚注区分"P2P 修甲(千年工程师, 再分配)"与"金钱修补(附魔, 真 sink)"; 第四章通胀平衡算式("日常真 sink ~1.2 万/人/日")里补上其日均量级估算(取决于装备磨损速率与 REPAIR_POINTS_PER_SECOND=10 的默认上限)，否则该行的日常真 sink 汇总会系统性偏低。

**[Major] [A-文档与代码不符] 三档燃料芯 FE 总量与代码差 6-12 倍**

- 位置: 第 88-91 行
- 文档原文: L89-91「| 工业燃料芯 | 2,304,000 FE / 芯 | 燃料资源总量，不折算信用点 | | 现代燃料芯 | 20,736,000 FE / 芯 | … | 未来燃料芯 | 73,728,000 FE / 芯 | …」
- 代码实际: 代码里燃料芯耐久是每 20 tick 扣 1 点，发电机每 tick 产 peakFePerTick，故单芯 FE 总量 = peak × 耐久 × 20：工业 192×3600×20 = 13,824,000 FE；现代 1152×7200×20 = 165,888,000 FE；未来 3072×14400×20 = 884,736,000 FE，分别是文档值的 6 / 8 / 12 倍。文档那三个数对应的是 12,000 / 18,000 / 24,000 tick 的旧燃烧时长，与现行 3600 / 7200 / 14400 的耐久值无法对上，且三档倍率还各不相同（不是整体缩放）。
- 取证: src/main/java/com/miningdim/power/generator/GeneratorSpec.java:10-12 `LOW("low", VoltageClass.LOW, 192, 3_600, …), MEDIUM("medium", VoltageClass.MEDIUM, 1_152, 7_200, …), HIGH("high", VoltageClass.HIGH, 3_072, 14_400, …)`（构造参数顺序为 peakFePerTick, coreDurability）；src/main/java/com/miningdim/power/generator/GeneratorBlockEntity.java:305-312 `int accepted = Math.min(room, runtime.peakFePerTick()); … reactionTickRemainder++; if (reactionTickRemainder == 20) { … fuel.hurt(1, serverLevel.random, null)`；src/main/java/com/miningdim/power/PowerGeneratorConfig.java:69-70 / 91-92 / 113-114 `fuelCoreDurability` 默认 3_600 / 7_200 / 14_400
- 建议改法: 将 docs/Economy_BalanceSheet_DesignSpec.md 第89-91行三档 FE 总量分别改为:工业燃料芯 13,824,000 FE/芯、现代燃料芯 165,888,000 FE/芯、未来燃料芯 884,736,000 FE/芯;并在表格下补一行推导说明,例如「单芯 FE 总量 = peakFePerTick × fuelCoreDurability × 20 tick/耐久点(GeneratorSpec.java 定义 peakFePerTick/coreDurability,GeneratorBlockEntity.java 每 20 tick 扣 1 点耐久,PowerGeneratorConfig.java 中 fuelCoreDurability 默认 3,600/7,200/14,400)」,避免后续改配置(peakFePerTick 或 fuelCoreDurability)后文档再次与代码脱节。

### `docs/Economy_Completeness_Audit.md`

**[Major] [C-实现状态标错] [4] 文档称 AZURE 回收率恒为0/唯一来源是创造模式, 已被命令购买与精英怪掉落两条生存路径推翻, 结论过期**

- 位置: 第 115-118 行
- 文档原文: **[4] AZURE 是纯 faucet-only 货币，唯一 sink 载体在生存模式不可获得** — sink 侧全库仅 `TarotPackItem.java:131` 一处，而 `tarot_pack_shiny` 在 `src/main/resources` 下无 recipes 无 loot_tables…唯一来源是创造模式物品栏。影响: …生存服上 AZURE 回收率恒为 0。
- 代码实际: 闪耀卡包现有两条生存可达途径: (1) `/tarot pack buy shiny [count]` 命令直接用 AZURE 扣款; (2) 自研精英怪按星级概率掉落。虽然资源包里确实仍无 recipes/loot_tables (这一半事实成立), 但「唯一来源是创造模式物品栏」与「回收率恒为 0」的结论已被命令购买路径推翻。
- 取证: src/main/java/com/miningdim/job/tarot/TarotSystem.java:120-124 「.then(Commands.literal("pack").then(Commands.literal("buy").then(packPurchaseNode("common", PackKind.COMMON)).then(packPurchaseNode("advanced", PackKind.ADVANCED)).then(packPurchaseNode("shiny", PackKind.SHINY))))」; src/main/java/com/miningdim/job/tarot/pack/TarotPackService.java:64,69 「case SHINY -> TarotConfig.PRICE_SHINY_PACK_AZURE.get();」「return kind == PackKind.SHINY ? Currency.AZURE : Currency.CREDIT;」; src/main/java/com/miningdim/job/tarot/pack/TarotPackDropHandler.java:13-33 「Rare PvE source for shiny packs: qualifying self-hosted champions can award one to their killer.」
- 建议改法: 把 Economy_Completeness_Audit.md 第115-118行的 [4] 由 Critical 降为 Major, 正文改写为: "AZURE 已有两条生存可达的消耗/获取路径 —— (1) `/tarot pack buy shiny [count]` 命令面向全部玩家(TarotSystem.java:120-124 命令树无权限门), 经 TarotPackService.buy() 对 PackKind.SHINY 按 Currency.AZURE 走 EconomyService.tryCharge() 真实扣账本(TarotPackService.java:45/64/69, EconomyService.java:72-74), 默认单价 64 AZURE/包、日封顶20包(TarotConfig.java:124-127), 生产环境 TEST_MODE 默认 false 不会跳过扣款; (2) 自研精英怪按星级概率掉落闪耀卡包(TarotPackDropHandler.java:13-35, 已在 TarotSystem.java:84 挂载到 forgeBus, 非死代码)。故'唯一来源是创造模式物品栏'与'回收率恒为0'均不成立。剩余真实缺口收窄为: tarot_pack_shiny 在 src/main/resources 与 src/generated/resources 下仍无 recipes/loot_tables(命令购买与精英怪掉落之外没有工作台合成或世界掉落路线), 以及该 sink 的吞吐量(默认64 AZURE/包 x 日封顶20包=1280 AZURE/天/人)相对 faucet(每人每日30点, EconomyConstants.java:106)是否足以长期出清尚未核算, 建议补一次收支比对而非归零处理。" 同步修正第19行"唯一 sink 载体 (闪耀卡包) 在生存模式无配方无战利品表，回收率恒为 0"与第65行末句"AZURE 回收率恒为 0"两处同结论的表述, 改为"闪耀卡包无配方无战利品表, 但可经命令购买(耗 AZURE)与精英怪掉落获取, AZURE 回收率非零"。

**[Major] [E-状态过期] 审计[7]"跳蚤市场游戏内完全不可达"已被后续提交(f107aa26, 2026-08-12)推翻，审计文档至今未加任何修复标注，与 WebUI_Frontend_Wiring_Checklist.md 正面打架**

- 位置: 第 131-133 行
- 文档原文: **[7] 跳蚤市场服务端 production 但游戏内完全不可达，唯一 P2P 通道与第二大 sink 双双为零** - 证据: ... 唯一打开路径 `WebUiClientSubsystem.java:61` 到 `WebUiClient.java:95` 的 `create(devPageDataUri())` 是内联 echo 开发页；grep `Commands.literal` 无 market 根、无 KeyMapping。
- 代码实际: 该结论已被后续实现推翻: 客户端注册了默认 G 键的 KeyMapping(key.miningdim.ui.open "打开平板"), 按键走 WebUiClient.openWebUi() 加载 MiningClientConfig.webUiUrl() 的正式 SPA, 前端有 market 路由(webui/src/pages/market/BrowsePage.tsx)。同仓 docs/WebUI_Frontend_Wiring_Checklist.md:288 已把它勾成 `[x] J3 键位入口：新增 WebUiKeyMappings，默认 G`, 两份文档正面打架。本审计报告的总评"48% 不能上线跑"至今没有任何修复批注, 被当作现状引用会做出错误的优先级决策。
- 取证: src/main/java/com/miningdim/client/webui/WebUiKeyMappings.java:56-61 `public static final KeyMapping OPEN_WEB_UI = new KeyMapping("key." + MiningConstants.MODID + ".ui.open", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);` 与 :49 `event.register(OPEN_WEB_UI);`; src/main/java/com/miningdim/client/webui/WebUiClient.java:101-103 `public static void openWebUi() { openScreen(MiningClientConfig.webUiUrl(), "WOK", false); }`; src/main/resources/assets/miningdim/lang/zh_cn.json:205 `"key.miningdim.ui.open": "打开平板"`
- 建议改法: 在 docs/Economy_Completeness_Audit.md 顶部"基线 commit"行下方加一行复核状态块，例如：`> 复核状态(截至 main bd0c4588): [7] 已闭合 —— WebUiKeyMappings.java:49/56-61(默认 G 键) + WebUiClient.java:101-103 的 openWebUi() 已接入正式 SPA(webui/src/App.tsx:59 路由 market 页)，且 webui/src/lib/bridge.ts 的 call() 在桥已注入时直接落真服 action，并非展示假数据；见 docs/WebUI_Frontend_Wiring_Checklist.md:286-288。本报告其余条目未逐条复核，总评 48% 为 2026-08-05 快照，不代表当前状态。` 并在第130-133行[7]条目正文末尾追加一行 `**已修复(2026-08-12, f107aa26)**：入口与真服联通已闭合，证据见 WebUiKeyMappings.java:49/56-61、WebUiClient.java:101-103、WebUI_Frontend_Wiring_Checklist.md:288。`

**[Major] [C-实现状态标错] [9] faucet 计数键仍为单账号维度(未变); /farmer sell 精通等级门已于 73bf644f 补齐,文档"卖出口至今无门"的表述已过期且写反**

- 位置: 第 142-145 行
- 文档原文: **[9] faucet 计数键单账号维度加 /farmer sell 零职业门，跨账号洗额度通道敞开** — 证据: …`FarmerSystem.java:69-76` 命令树无 `.requires`；`FarmerWheatSellService.java:56-97` 全函数无 JobServices/JobId 引用…注: memory 记载的"农夫走等级门 level>=2"指的是耕地档位解锁，卖出口至今无门。
- 代码实际: 卖出口已有门: FarmerWheatSellService 现在 import JobServices/JobId, 并在扣料之前做 level>=2 判定, 拒绝时返回 masteryDenied() 零副作用。审计特地加的那条「注: …卖出口至今无门」正好写反了。计数键单账号这半条仍成立。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerWheatSellService.java:4-5 「import com.miningdim.job.JobId; import com.miningdim.job.JobServices;」; 同文件 L81-83 「if (JobServices.jobService().level(player, JobId.FARMER) < FarmerConstants.SELL_MIN_MASTERY_LEVEL) { return SellResult.masteryDenied(); }」; 同文件 L47 「{@code belowMastery} 为 true: 卖家农夫精通等级未达 {@link FarmerConstants#SELL_MIN_MASTERY_LEVEL} (反洗钱身份门), 未扣未发。」
- 建议改法: 拆分 docs/Economy_Completeness_Audit.md 142-145 行为两条: (a) 保留"faucet 计数键单账号维度"为 Major, 证据改引 EconomyWalletData.java 中 `counter.playerId() + "|" + counter.counterKey()` 组合键(持久化写入约170-176行、解析约244-250行), 结论不变——群体上限仍恒等于全员额度之和; (b) "/farmer sell 零职业门"部分标注为已闭合(非本次审计范围内问题), 引用 FarmerWheatSellService.java:81-83 的 `JobServices.jobService().level(player, JobId.FARMER) < FarmerConstants.SELL_MIN_MASTERY_LEVEL` 判定与 FarmerConstants.java:97 的 `SELL_MIN_MASTERY_LEVEL = 2`, 并注明该门由提交 73bf644f(2026-08-17)引入, 晚于本审计文档定稿(2026-08-12), 属于文档未随代码更新的过期状态, 而非最初審查失误。删除 145 行末尾"卖出口至今无门"这句已被证伪的注,避免误导后续读者认为反洗钱身份门仍未接入。

**[Major] [C-实现状态标错] 缺口[12]市场事务边界三条中两条已修, 仅余交付背包非原子**

- 位置: 第 157-160, 53 行
- 文档原文: **[12] 市场资金一致性无事务边界: 离线待结先删后发、成交三步无事务、挂单 shrink 先于落库** — 证据: `MarketEngine.java:276-289` 先 `dao.drainPendingPayout` (已 commit 删行) 再 grant…`MarketEngine.java:194-215` markSold 到 insertTxn 到 `inventory.add` 三步无事务；`:131-135` `stack.shrink` 先于 insertListing…
- 代码实际: 三条中两条已闭合: 买入的 markSold+insertTxn+卖家结算现在包在 economy.inTransaction 内; settlePendingOnLogin 的 drain 与 grant 也在同一事务里 (代码注释直接点名「此前 drainPendingPayout 自己提交了 SELECT + DELETE, 之后才 grant」)。挂单顺序已反转成先 insertListing 后 shrink 并附了详细理由。仅剩「交付物品写背包仍在事务外」这一条。L53 的「无 schema 版本迁移 (只有 CREATE IF NOT EXISTS)」也已被 MiningSchema + SchemaMigrator 的 user_version 机制取代。
- 取证: src/main/java/com/miningdim/market/MarketEngine.java:217 「economy.inTransaction(() -> {」(内含 L226 markSold、L235 insertTxn、L241 economy.grant); 同文件 L315-321 「取删与入账必须同事务。此前 drainPendingPayout 自己提交了 SELECT + DELETE, 之后才 grant —— …economy.inTransaction(() -> { List<long[]> pending = dao.drainPendingPayout(seller.getUUID());」; 同文件 L149-152 「扣库存必须排在 insertListing 之后: 挂单期物品的唯一所在就是 listings 那一行, 先扣再落库的话…」与 L158-160 「long listingId = dao.insertListing(…); stack.shrink(count);」; src/main/java/com/miningdim/store/MiningSchema.java:215-219 「static final List<List<String>> MIGRATIONS = List.of(V1, V2, V3, V4); … SchemaMigrator.migrate(conn, MIGRATIONS);」
- 建议改法: [12] 缩写为"市场交付物品与事务之间仍非原子 (背包是第三个存储, MarketEngine.java:249-252 明确写明交付放在事务提交之后)"这一条, 其余两条(成交三步无事务、挂单 shrink 先于落库)标注已闭合并给出证据锚点 MarketEngine.java:217-247(买入四件事同一 inTransaction 闭包)与 MarketEngine.java:160-162(insertListing 先于 shrink); 离线待结的"先删后发"半条同样已闭合, 证据 MarketEngine.java:321-332(drain 与 grant 同一 inTransaction)及 MarketSubsystem.java:78-82(登录结算同时守卫 MarketServices 与 EconomyServices 两个门面就绪)。L53 删除"缺事务边界""shrink 先于 insertListing""无 schema 版本迁移"三段过期表述, 改为指向 MiningSchema.java:215-220(MIGRATIONS 列表 + apply 方法)与 SchemaMigrator.java:32-60(基于 PRAGMA user_version 的整体事务式迁移), 并注明该迁移已接入生产路径(MiningStore.java:32、MarketDb.java:31、SqliteEconomyLedger.java:54、CaseDb.java:27), 而非仅测试用例。

**[Major] [A-文档与代码不符] docs/Economy_Completeness_Audit.md 第200行对 IJobService.level 的描述与实现相反,且与已落地的反洗钱门槛矛盾**

- 位置: 第 200-202 行
- 文档原文: 5. **给 /farmer sell 加职业/入职门** (克隆 `AgentBountySavedData` 的 SavedData 布尔标志范式，勿用 `IJobService.level`，它对任何玩家恒返 1)，并把 faucet 计数键加第二维度或改为全服供给口径。
- 代码实际: level() 并非恒返 1: 它委派到玩家 capability 里的 JobProgress.level(), 该字段以 MIN_LEVEL 为初值并随经验推进。「恒返 1」只在玩家 0 经验时成立。实际落地也正是用了这条被劝阻的路径 (level>=2), 并在常量注释里写明「level>=2 即确实练过农夫、升过至少一级」。照本文的劝阻去改反而是倒退。
- 取证: src/main/java/com/miningdim/job/JobServiceImpl.java:26-28 「public int level(Player player, JobId job) { return require(player, job).jobProgress(job).level(); }」; src/main/java/com/miningdim/job/JobProgress.java:21,32-34 「private int level = JobXpCurve.MIN_LEVEL;」「public int level() { return level; }」; src/main/java/com/miningdim/job/farmer/FarmerConstants.java:91-93 「故用精通等级门做身份代理: level>=2 即 "确实练过农夫、升过至少一级", 排除从没种过地、纯靠 /give 或跨账号交易/复制拿到小麦就直接套现的白板小号 (L1 默认态)。」
- 建议改法: 把第200行括号内容从「克隆 `AgentBountySavedData` 的 SavedData 布尔标志范式，勿用 `IJobService.level`，它对任何玩家恒返 1」改为:「`IJobService.level` 委派 `JobProgress.level()`,随经验真实推进,恒为1只是零经验默认态(L1);该门槛已落地为 `FarmerWheatSellService.java` 中 `JobServices.jobService().level(player, JobId.FARMER) < FarmerConstants.SELL_MIN_MASTERY_LEVEL`(即 level>=2)的精通等级门,并有 FarmerGameTests/FarmerWebUiGameTests 覆盖,无需按 AgentBountySavedData 范式另建布尔标志」。同时该条修复优先级建议本身也应标注为"已完成"而非待办,避免误导后续读者重复劳动或误删已生效的反洗钱门。

**[Major] [E-状态过期] market.history空桩/分页零钳制/无OP调账均已实现**

- 位置: 第 53, 77 行
- 文档原文: L53「`market.history` 是 `MarketActions.java:185` 的空数组桩、`MarketActions.java:63-67` 分页零钳制。」; L77「…`AgentScanMenu.Provider` 零实例化点；…无任何 OP 调账命令。」
- 代码实际: market.history 已接通真实 DAO 查询, 分页已有钳制 (且注释特地说明比 history 更要紧), OP 调账有 `/economy grant` (权限 2) 与 `admin.economy.set` / `admin.economy.balance` 两条 WebUI 动作, AgentScanMenu 那条原生面板路径已整条删除 (类不存在, 谈不上零实例化点)。
- 取证: src/main/java/com/miningdim/market/MarketActions.java:255 「List<TxnRow> rows = engine.transactionsByPlayer(self, offset, pageSize);」与 L99-101 「分页钳制, 与 market.history 同一口径 —— 这里比 history 更要紧」; src/main/java/com/miningdim/economy/EconomyCommands.java:29-35 「dispatcher.register(Commands.literal("economy").requires(source -> source.hasPermission(OP_LEVEL)).then(Commands.literal("grant")…」; src/main/java/com/miningdim/economy/EconomyAdminWebUiActions.java:56-57 「WebUiServerDispatcher.register("admin.economy.balance", BALANCE); WebUiServerDispatcher.register("admin.economy.set", SET);」; src/main/java/com/miningdim/job/agent/AgentWebUiActions.java:37 「触发入口 (决策 J9): {@code AgentScanMenu} 那条原生面板路径已在本 PR 整条删除」
- 建议改法: L53: 删除「`market.history` 是 `MarketActions.java:185` 的空数组桩、`MarketActions.java:63-67` 分页零钳制」这两句(该行号现属 MINE action;history 已接 `engine.transactionsByPlayer`/`transactionsCountByPlayer` 真查询,分页在 MarketActions.java:249-250 有 `Math.max(0,...)` 与 `clamp(...,1,MAX_PAGE_SIZE)` 双重钳制)。  L77: 删除「`AgentScanMenu.Provider` 零实例化点」与「无任何 OP 调账命令」两处;改为「`AgentScanMenu` 原生面板路径已整条删除(AgentWebUiActions.java:37 决策 J9,统一走平板 hub WebUI 入口);OP 调账已有 `/economy grant`(EconomyCommands.java:28-35,权限等级 2,EconomySystem.java:89 已接线)与 WebUI 侧 `admin.economy.balance`/`admin.economy.set`(EconomyAdminWebUiActions.java:56-57,EconomySystem.java:79 已接线)——仍缺的是 `MiningNetwork.sendWebUiEvent`(MiningNetwork.java:123)零发送方,余额变动仍只能靠前端轮询感知」。

**[Major] [D-覆盖缺口] 审计漏掉卖鱼这一条共用 credit_faucet 主闸的 faucet(任务奖励系走独立的 quest_faucet 键, 并非同类缺口, 应从本条剔除)**

- 位置: 第 55-59, 93-161 行
- 文档原文: ### Faucet 侧 (60%, 半成品)…统一衰减主闸…四路信用点 faucet 确实共用同一 `credit_faucet` 键。(第三章「关键缺口清单」全文亦无钓鱼/任务两条)
- 代码实际: 代码里还有两条走同一 credit_faucet 主闸的 faucet 完全没有进审计的普查表: (1) `/fishing sell` 卖矿石鱼, 定额单价 20/80/400/600/2000 无逐条递减; (2) 任务系统发奖。前者还正好踩中 Economy_Laundering_Review 自己划的 NPC 收购红线。Economy_BalanceSheet 纪律要求任何新 faucet 先过总表, 而完整度审计是事实上的 faucet 清单, 漏登就等于总表也漏。
- 取证: src/main/java/com/miningdim/job/fisher/ore/OreFishSellService.java:54-60 「long gross = Math.multiplyExact(OreFishingConfig.sellPrice(type), count); stack.shrink(count); … grantDaily(player, gross, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER)」; src/main/java/com/miningdim/job/fisher/ore/OreFishingConfig.java:25 「long[] prices = {20L, 80L, 400L, 600L, 2000L};」; src/main/java/com/miningdim/quest/QuestRewards.java:80 「return EconomyServices.economyService().grantDaily(player, raw,」
- 建议改法: 仅就卖鱼一处补文档: 在「Faucet 侧」一节(Economy_Completeness_Audit.md 第55-59行附近)补一行"卖鱼(`OreFishSellService.java:54-60`)——共用 `credit_faucet` 主闸(`EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`/`_TIER`),定额单价 `OreFishingConfig.java:25` {20,80,400,600,2000} 无逐条递减曲线,与矿工/农夫的衰减口径不一致",并在第三章「关键缺口清单」新增一条 Major:"定额 NPC 收购价挂在可反复自动化产出的钓鱼产物上,缺少像矿工 0.97 / 农夫 buyback 那样的逐条递减曲线,仅靠共享主闸兜底,与 `Economy_Laundering_Review.md` T0-3(刷怪/无限输入物 NPC 收购红线)的风险模式相同"。不要把任务奖励塞进同一条:任务奖励(`QuestRewards.java:73-83`)用的是独立键 `EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY`("quest_faucet"),`EconomyConstants.java:93-105` 已用整段 javadoc 说明这是"刻意不并入主闸的唯一有判据例外"(供给由每日/每周任务槽位数硬封,非产能无限)。若认为审计仍应提及这条 faucet,应作为独立的 Minor/覆盖缺口单独记录("完整度审计通篇未提任务奖励这一 CREDIT 来源"),且需注明其风险显著低于卖鱼(有槽位硬顶、有独立计数器、非产能无限),不应与卖鱼共享同一条 Major 发现或同一句"与主闸/红线冲突"的结论。

**[Major] [A-文档与代码不符] "活跃faucet仅4条"的两条理由均不成立(CHAIN_HARD_EXCLUDE已删除且连锁已发钱, 特勤handler已自研化无条件生效)**

- 位置: 第 59 行
- 文档原文: 但活跃 faucet 实为 4 条而非 6 条: 连锁回放因 `MinerConstants.CHAIN_HARD_EXCLUDE` 恒 no-op，特勤三笔全是 deadcode。
- 代码实际: 两条理由都不成立: CHAIN_HARD_EXCLUDE 双表已于 2026-07-12 被用户裁决废除, 连锁回放路径已实际发钱; 特勤奖励 handler 现在无条件生效 (探测源自研, 不依赖 Champions 加载)。按当前代码, 经 grantDaily 入同一 credit_faucet 键的生产调用点有 6 处。
- 取证: src/main/java/com/miningdim/job/miner/ChainMiningEngine.java:273 「单一连锁判定 (替代旧 CHAIN_WHITELIST / CHAIN_HARD_EXCLUDE 双表, 用户 2026-07-12 裁决"矿洞维度内连锁全放开")」; src/main/java/com/miningdim/economy/EconomyService.java:148-150 「2026-07-12 连锁全放开后高价矿可被连带 (ChainMiningEngine 废除枚举白名单 / 高价矿硬排除双表), 本路径已实际发钱」; grantDaily 生产调用点: ChampionRewardHandler.java:161 / AgentRewardHandler.java:166 / FarmerWheatSellService.java:117 / OreFishSellService.java:58 / QuestRewards.java:80 / EconomyService.java:130 (settleOreSale 内部)
- 建议改法: 将 docs/Economy_Completeness_Audit.md 第59行改为: 「活跃 CREDIT faucet 现为 6 条: 卖矿 (`EconomyService.settleOreSale`, :130)、连锁/隧道回放 (`recordMinedOreDrops` 内部复用同一 settleOreSale 出口, 2026-07-12 用户裁决全放开后已实际发钱)、卖菜 (`FarmerWheatSellService.java:117`)、精英怪贡献池 (`ChampionRewardHandler.java:161`)、特勤加强奖励 (`AgentRewardHandler.java:166`, 集成层已自研化 F024, 无条件生效不依赖 Champions 加载)、卖鱼 (`OreFishSellService.java:58`) 与任务奖励 (`QuestRewards.java:80`)。」并删除"CHAIN_HARD_EXCLUDE 恒 no-op"与"特勤三笔全是 deadcode"这两条已失效的理由; 同时建议顺带核查 docs/Ore_Pricing_Ledger_DesignSpec.md 第57/194/195行(仍引用已被物理删除的 `CHAIN_HARD_EXCLUDE`/`CHAIN_WHITELIST`/`MinerConstants.java:170-175`), 该文档同样需要回写, 但这是独立的另一条发现, 不在本条修复范围内。

**[Major] [D-覆盖缺口] OP 命令 /economy grant(绕过全部 faucet 计数、与文档自身反洗钱纪律冲突)与 /mchampion summon 全库无文档，且 L75 遗漏 /quest /fishing /economy /wokcase 四条已接线命令树**

- 位置: 第 75 行
- 文档原文: 命令树 (`/mining` `/job` `/marriage` `/farmer` `/tarot` `/mchampion`) 与八个职业台 GUI 都真实接线，挖矿被动 faucet 无需 UI。
- 代码实际: 这是全仓唯一一处提到 /mchampion 的地方, 且只是列名字, 没有任何参数与用法; /economy grant 则全库文档零命中。两条都是 OP(level 2) 命令且直接影响线上经济与刷怪: /economy grant 直接向玩家发放信用点与青辉石(绕过全部 faucet 计数), /mchampion summon 可指定实体、星级与词条直接召唤精英怪。运维/GM 无文档可依, 也无处记录"发放必须走审计"这类纪律。另外该句遗漏了同样已接线的 /quest、/fishing、/economy、/wokcase 四棵命令树。
- 取证: src/main/java/com/miningdim/economy/EconomyCommands.java:29-35 `dispatcher.register(Commands.literal("economy").requires(source -> source.hasPermission(OP_LEVEL)).then(Commands.literal("grant").then(Commands.argument("target", EntityArgument.player()).then(Commands.argument("credit", LongArgumentType.longArg(1L)).then(Commands.argument("azure", LongArgumentType.longArg(1L))...`; src/main/java/com/miningdim/champion/ChampionCommands.java:49-58 `Commands.literal("mchampion").requires(src -> src.hasPermission(OP_LEVEL)).then(Commands.literal("summon").then(Commands.argument("entity", ResourceLocationArgument.id()).then(Commands.argument("star", IntegerArgumentType.integer(StarRank.MIN_STAR, StarRank.MAX_STAR))...`
- 建议改法: 新建 docs/Admin_Commands_Reference.md(或在 docs/modules/README.md 下加一节"运维命令总表")，比照 MiningDimension_Mod_DesignSpec.md 第十七章 /mining 命令的写法(权限等级、参数、审计要求逐条列表)，至少登记： 1) `/economy grant <target> <credit> <azure>`(EconomyCommands.java:29-35，OP level 2；credit/azure 下界均为 1，无法只发一种货币)——必须明确标注：该命令经 EconomyService.grantBundle 直接调用 ledger.creditBundle(EconomyService.java:103-105)，不经过 grantDaily/recordFaucetGrant，完全绕开 Economy_Completeness_Audit.md:231 要求的 credit_faucet 主闸；需在文档里给出等价于该文档 231 行的使用纪律(仅限一次性调账/补偿，禁止用于常规发放，且要求操作留痕审计)。 2) `/mchampion summon <entity> <star> [affixes]`(ChampionCommands.java:49-58，OP level 2；entity 为任意 EntityType 资源位置，star 受 StarRank.MIN_STAR~MAX_STAR 约束，affixes 可显式指定越过互斥/预算校验)——需补充调试/GM 用途边界与预期影响(高星+高强度词条组合对玩家战斗压力的量级)。 3) 补齐 L75 遗漏的四棵已接线命令树：`/quest`(QuestCommands.java:28，QuestSystem.java:57 接线)、`/fishing`(FishingSystem.java:80)、`/economy`(EconomyCommands.java:29，本身即被遗漏)、`/wokcase`(WebUiClientSubsystem.java:52-53)。 4) 顺带登记同样只在代码注释里有说明、文档零覆盖的 `/farmer admin legacy <target>`(只读查询迁移遗留占用，FarmerSystem.java:158-170)与 `/farmer admin recount <target>`(清零迁移遗留占用，FarmerSystem.java:174-189，OP level 见 FarmerSystem.java 类注释)，以及 `/job set`(JobCommands.java:48)、`/mining reset all`(command/MiningCommands.java:114-116 或 entry/MiningCommands.java:62-64，注意仓库里这两个同名 mining 命令注册文件是否重复接线需另行口头核实，不在本条修复范围内)。

### `docs/Economy_Laundering_Review.md`

**[Major] [C-实现状态标错] 开放问题1(AZURE per-UUID cap)代码里已有答案**

- 位置: 第 117 行
- 文档原文: 1. 6 星精英怪的 AZURE 奖励是否有 per-UUID 日/周 cap?(V6: 多 alt 各戳 boss 达伤害门槛各领满 AZURE 的 alt-shimmer 向量; AZURE 不可转移故不可市场洗, 但堆个人 AZURE 购买力。)
- 代码实际: 有。精英怪青辉石一律经 grantAzureDaily 并入每人每日硬上限 30 点; 特勤侧另有 ISO 周戳软上限。这条「待确认」在代码侧是确定答案, 继续挂在开放问题里会让读者以为还没查过。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:170-171 「EconomyServices.economyService().grantAzureDaily(player, azureShare, EconomyConstants.AZURE_DAILY_FAUCET_CAP);」; src/main/java/com/miningdim/economy/EconomyConstants.java:122 「public static final long AZURE_DAILY_FAUCET_CAP = 30L;」; src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:205 「按 ISO 周戳门控本周已产量, 撞顶则只发剩余额度 (软上限语义); 周门控放行的 grantable 再经 {@code grantAzureDaily}」
- 建议改法: 把开放问题 1 由疑问句改写为结论: "已确认: 精英怪青辉石经 `grantAzureDaily` 并入每人每日硬上限 `AZURE_DAILY_FAUCET_CAP=30`(`ChampionRewardHandler.java:170`, `EconomyConstants.java:122`), 特勤悬赏侧另有 ISO 周轴软上限且与精英怪掉落共享同一日 cap(`AgentRewardHandler.java:205`)。该硬 cap 由提交 6a531ce7/aa155a84(2026-06-27)引入, 晚于本文档成文(2026-06-20), 故此前为开放项、现已有定论。V6 的 alt-shimmer 向量仍成立, 但约束为单 alt 每日封顶 30, 多 alt 汇总不受限(身份层问题, 见第七节)。"

**[Major] [A-文档与代码不符] 称faucet计数器是NBT持久, 实已迁入SQLite**

- 位置: 第 118 行
- 文档原文: 2. `PlayerAbuseState` 当前仅内存无持久层(取证标注), 但 faucet 计数器是 NBT 持久 —— 确认重连不重置当日额度(取证倾向"不重置", 待真服验)。
- 代码实际: faucet 计数器已于 2026-08-12 的 SQLite 迁移搬进统一库的 daily_counters 表, 不再是 NBT/SavedData。前半句 PlayerAbuseState 仅内存无持久层仍然成立 (EconomySystem 登入时新建)。两者持久化纪律不对称这一结论没变, 但载体描述错了, 会让后续查证者去翻错的存储。
- 取证: src/main/java/com/miningdim/store/MiningSchema.java:130-137 「"CREATE TABLE daily_counters (" + "player_id TEXT NOT NULL, " + "counter_key TEXT NOT NULL, " + "kind TEXT NOT NULL, " + "amount INTEGER NOT NULL, " + "day_stamp INTEGER NOT NULL, " + "credit_carry REAL NOT NULL DEFAULT 0, " + "PRIMARY KEY (player_id, counter_key, kind))"」; src/main/java/com/miningdim/economy/EconomySystem.java:139-140 「// 登入建态。Capability 子系统就绪后此处应从持久层 load(PlayerAbuseState.load); 当前阶段无持久层, 新建。 playerStates.computeIfAbsent(player.getUUID(), k -> new PlayerAbuseState());」
- 建议改法: 把 docs/Economy_Laundering_Review.md 第118行「但 faucet 计数器是 NBT 持久 —— 确认重连不重置当日额度(取证倾向"不重置", 待真服验)」改为「faucet 计数器已于 SQLite 迁移(f63a1b3b)后进入统一库的 `daily_counters` 表(`src/main/java/com/miningdim/store/MiningSchema.java:130-137`, 建表与写入见 `EconomyLedgerBootstrap.java:170`、`SqliteEconomyLedger.java:471`), 提交即落盘, 重连不重置当日额度已可由该表直接证实, 无需真服验」; 前半句「`PlayerAbuseState` 当前仅内存无持久层」保留, 并补证据行 `EconomySystem.java:139-140`(全类未见 `PlayerAbuseState.load/save` 调用)。

**[Major] [B-文档互相打架] 市场手续费写5%且描述"经市场费回流", 实际是20%且卖菜环节本身根本不经过市场收费(代码与另两份文档均为20%)**

- 位置: 第 35, 39, 42 行
- 文档原文: L35「已铸 CREDIT 在账号间转移只受 5% 手续费约束」; L39「经 5% 市场费回流后净 ~2052/mule/天」; L42「买家先被扣 CREDIT, 卖家收 proceeds, 5% 蒸发为 sink」
- 代码实际: 费率定稿为 0.20 且收费时机改成挂单时向卖家一次性收取 (买入不再二次收费, 卖家实收全额)。同仓另两份文档都已写 20%: Economy_Completeness_Audit.md:18「第二大 sink (跳蚤 20% 手续费)」、WebUI_Architecture_DesignSpec.md:125 更明确写「非早期 0.05 成交额比例」。本文的 5% 是三方中唯一的旧值, 且被用来算「净 ~2052/mule/天」这个承重量级。
- 取证: src/main/java/com/miningdim/market/MarketConstants.java:30 「public static final double FEE_RATE = 0.20D;」, 同文件 L21 「挂单手续费的【平价基础费率】(定稿: 0.20 = 20%)」, L27-28 「收费时机 (用户决策"上单即收"): 本费在 {@link MarketEngine#place} 向卖家一次性收取, 蒸发为 sink…买入 ({@link MarketEngine#buy}) 不再二次收费, 卖家实收全额 total。」; src/main/java/com/miningdim/market/MarketEngine.java:195 「手续费已在挂单时 (place) 向卖家收过 (上单即收 sink); 买入不再二次收费, 卖家实收全额 total, 流水 fee 记 0。」
- 建议改法: 三处费率数字 5% 全改 20%(与 MarketConstants.FEE_RATE=0.20D 对齐), 且 L39 需连同机制一起改写, 不能只替换数字: 现文「即单账号卖菜一天最多铸 2160 信用点(硬顶, 非软顶), 经 5% 市场费回流后净 ~2052/mule/天」应改为「即单账号卖菜一天最多铸 2160 信用点(硬顶, 非软顶); /farmer sell 经 FarmerWheatSellService 直接调用 EconomyService#grantDaily 入账, 不经过市场模块, 卖菜本身零手续费, 2160 即到手净额。若该笔 CREDIT 之后要再经跳蚤市场 P2P 转移/汇集给他人(即真正的跨账号洗钱步骤), 卖家在挂单(MarketEngine#place)时须先承担 20%(非 5%) 上单费, 撤单不退」。L35「只受 5% 手续费约束」改「只受 20% 挂单手续费约束(上单即收, 非按成交额)」; L42「5% 蒸发为 sink」改「20% 蒸发为 sink」; 同时顺带修正 L81 同源的「5% 手续费」为「20% 手续费」。

**[Major] [C-实现状态标错] T0-1职业门与L33"零职业校验"均已过期, 实际已按精通等级门(level>=2)落地在service层**

- 位置: 第 72-73, 55 行
- 文档原文: L72「**T0-1 给 `/farmer sell` 加职业门。**…改 1-3 行(命令注册 `.requires` 或服务层 `JobServices.level(player, FARMER) >= N` 前置)。」; L73「所以只能做"等级门"(level>=阈值), 阈值要 >1 才有过滤意义」; L55 漏洞表 V2 状态「确认」
- 代码实际: 已按 T0-1 给出的第二方案 (服务层 JobServices.level 前置) 落地, 阈值取 2, 且刻意加在 service 层而非命令层以防 GUI/网络包绕过。漏洞表 V2 的状态「确认」与 Tier 0 的「建议上线前做」都已过期。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerWheatSellService.java:78-83 「// 精通等级身份门 (反洗钱, FarmerConstants.SELL_MIN_MASTERY_LEVEL): 未达门槛的白板小号不得套现 /give 或跨账号交易/复制来的小麦。加在 service 层 (非仅命令层) …if (JobServices.jobService().level(player, JobId.FARMER) < FarmerConstants.SELL_MIN_MASTERY_LEVEL) { return SellResult.masteryDenied(); }」; src/main/java/com/miningdim/job/farmer/FarmerConstants.java:97 「public static final int SELL_MIN_MASTERY_LEVEL = 2;」
- 建议改法: 在 L72 T0-1 前加「[x] 已完成」, 注明落地点与阈值: FarmerWheatSellService.java:81(判定) + FarmerConstants.java:97(SELL_MIN_MASTERY_LEVEL=2), 并注明覆盖命令(FarmerSystem.java:129)与WebUI(FarmerWebUiActions.java:114)两个入口、有 GameTest 覆盖(FarmerWebUiGameTests.java:369起)。保留 L73"诚实局限"段落但改述基调, 从"待办中的顾虑"改为"已知设计局限说明"(等级门收窄但不根除真练农夫的 real-identity Sybil, 与 service 注释"收窄但不根除"一致), 不再作为未完成项呈现。同步修正 L33: 把"`FarmerWheatSellService.sell` 对任意持 mod 小麦者发币、零职业校验"改为"现按精通等级门 level>=2 校验(校验下沉在 service 层而非命令 `.requires`, 是为防 GUI/网络包绕过的有意设计)", 命令注册本身确实仍无 `.requires`(FarmerSystem.java:81-85), 这半句可保留。L55 V2 行"状态"列改为"已修(等级门 level>=2)", 并把该行"名称"列同步由"`/farmer sell` 无职业门 + 源物品无门槛"改为"`/farmer sell` 曾无职业门, 现已按精通等级门 level>=2(源物品 farmer_seed 仍无门槛, 该半部分风险仍在)"。

**[Major] [C-实现状态标错] T0-4称market.history是空数组桩, 实已接通**

- 位置: 第 76 行
- 文档原文: **T0-4 接通 `market.history`。** 现在返回空数组桩(`MarketActions.java:168-175`, DAO 缺 `transactionsByPlayer`), 是已知 TODO。`transactions` 表其实每笔都写了。补这个查询…
- 代码实际: DAO 已有 transactionsByPlayer 与配套的计数方法, action 层已真实分页查询并返回流水行。该 TODO 已闭合, 它同时是 T1-4「净流入人工巡查」的前置依赖, 状态需要一并更新。
- 取证: src/main/java/com/miningdim/market/store/MarketDao.java:97 「List<TxnRow> transactionsByPlayer(UUID player, int offset, int limit);」, L99 「/** 该玩家参与的成交流水总条数 (与 {@link #transactionsByPlayer} 同一 WHERE), 供前端算总页数。 */」; src/main/java/com/miningdim/market/store/MarketDaoSqlite.java:218 「public List<TxnRow> transactionsByPlayer(UUID player, int offset, int limit) {」; src/main/java/com/miningdim/market/MarketActions.java:255 「List<TxnRow> rows = engine.transactionsByPlayer(self, offset, pageSize);」
- 建议改法: T0-4 前加「[x] 已完成」, 并将引用行号更新为当前真实位置: DAO 接口 MarketDao.java:97(transactionsByPlayer)与:99(transactionsCountByPlayer), 实现 MarketDaoSqlite.java:217-254, 接线 MarketActions.java:245-270(HISTORY handler); 原文引用的 MarketActions.java:168-175 已随后续重构挪作 market.cancel handler, 应一并订正避免误导查证者。同时把 T1-4「market.history 接通后」的前置条件改为「已具备(fb6c74b0 起), 可直接做管理员周报」。

### `docs/Economy_SQLite_Migration_Plan.md`

**[Major] [A-文档与代码不符] 施工环境写死不存在的Xiaoxiao用户目录, 与分支协作.md的占位符写法互相打架**

- 位置: 第 19, 28 行
- 文档原文: L19「worktree:   C:\Users\Xiaoxiao\AppData\Local\Temp\claude\<session>\scratchpad\integ」; L28「export JAVA_HOME="C:/Users/Xiaoxiao/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8"」(上方 L25 明写「构建工具链 (照抄, 本机默认 Java 21 + Gradle 9 构建不了本工程)」)
- 代码实际: 本机不存在 C:\Users\Xiaoxiao 目录, 接手者照抄 L28 会直接拿到无效 JAVA_HOME 而编译失败 —— 恰好是这一节明令「照抄, 不要重新摸索」要避免的情形。同仓的 分支协作.md 写的是占位符形式 C:/Users/<你>/, 两份文档对同一件事给出不同写法。
- 取证: build.gradle:27-28 「toolchain { languageVersion = JavaLanguageVersion.of(17)」(确认必须 JDK17); 分支协作.md:63-64 「# JDK17 随 gradle 拉到 ~/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8 / export JAVA_HOME="C:/Users/<你>/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8"」; 实测 ls "C:/Users/Xiaoxiao" 返回 No such file or directory
- 建议改法: docs/Economy_SQLite_Migration_Plan.md 第19行 worktree 路径中的 C:\Users\Xiaoxiao 改为占位符形式(如 C:\Users\<你>, 或注明"以下为历史施工快照, 用户名/session 需替换为当前账户与本次会话值"); 第28行 export JAVA_HOME 同样改为与 分支协作.md 第63-64行一致的占位符写法, 例如 export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8" 或 "C:/Users/<你>/.gradle/jdks/eclipse_adoptium-17-amd64-windows/jdk-17.0.18+8"; 并在第14行标题或紧邻处补一句"JDK17 工具链的规范写法以 分支协作.md 为唯一真源, 本节仅为当时那次施工的路径快照, 用户名/session id 不可直接照抄"。

**[Major] [F-结构问题] docs 无归档区, 13 份已完成或已作废文档混在现役目录**

- 位置: 第 3 行
- 文档原文: L3「> **实施状态 (2026-08-12): 六个阶段全部完成, 815 个 GameTest 全绿。**」
- 代码实际: 至少 13 份文档已经完成使命或已被推翻, 却与 44 份现役规格同级平铺, 目录里没有任何信号区分。清单与各自自述: 本文(六阶段全完成)、docs/Munitions_Workbench_Branch_Review.md:9「结论: **不可合并**」(该分支的修复早已闭环合入, 结论作废)、docs/PixelUI_DesignSystem_DesignSpec.md:3「全文状态：DEFERRED（2026-08-13 起）」、docs/WebUI_ChineseIME_DesignSpec.md:3「状态: **DEFERRED (已推迟, 未实现)**」、docs/WebUI_ServerPush_DesignSpec.md:3「状态: **DEFERRED (已推迟, 未实现)**」、docs/Full_Repo_Audit_2026-08.md(基线 49d5283 的冻结快照)、docs/Economy_Completeness_Audit.md(2026-08-05 冻结)、docs/Economy_Laundering_Review.md、以及 TaskSpec_INDEX 加 4 份 TaskSpec(整批已于 2026-08-17 交付合入)。
- 取证: 仓库对代码侧已经建立了归档惯例并落到磁盘: webui/_pixel-archive/ 整棵目录封存像素 UI 实现(webui/_pixel-archive/README.md 记录恢复方法), 而同一次决策产生的规格文档 docs/PixelUI_DesignSystem_DesignSpec.md 却仍留在现役 docs/ 根。docs/modules/README.md:57 的必登记清单里也没有任何关于文档生命周期的规则。
- 建议改法: 建 docs/archive/ 并把上述 13 份迁入, 迁入时在文件头加统一抬头: 冻结日期 + 冻结基线 commit + 「本文是历史记录, 不是实现依据」+ 指向接替它的现役文档。已完成的迁移计划与已交付的 TaskSpec 归 docs/archive/delivered/, 被推翻或推迟的归 docs/archive/superseded/。docs/README.md 的文档地图对归档区只列索引不展开。

**[Major] [D-覆盖缺口] 第六节遗留项漏登V4的pending_payout无清理策略**

- 位置: 第 345-373 行
- 文档原文: ## 六 尚未解决 / 需人类决策的遗留项 (全节共 8 条, 无一条涉及 pending_payout 的保留期/清理策略)
- 代码实际: 迁移在计划定稿后又推进了 V3/V4 两版, 其中 V4 的 javadoc 自己明确留了一条需要主控拍板的业务数值 (待领款行的保留期), 并写明本次刻意不臆造这个数字、留给后续变更。这正是第六节该收的那类遗留项, 但第六节没有, 于是这条待拍板事项在文档层面无人认领。
- 取证: src/main/java/com/miningdim/store/MiningSchema.java:205-209 「只加索引、不加清理: 行只在该卖家本人 drain 时被删除, 退坑/长期离线卖家的待领款行会永久驻留、表单调增长。这本该配一条清理或归档策略, 但保留期是需要主控拍板的业务数值 (例如"离线满多久视为弃置"), 本次迁移刻意不臆造这个数字, 只解决索引缺失导致的扫描代价, 清理逻辑留给后续单独的、附带具体保留期拍板结果的变更。」; 同文件 L211-212 「private static final List<String> V4 = List.of("CREATE INDEX idx_pending_payout_seller ON pending_payout(seller_uuid)");」
- 建议改法: 第六节新增第9条:"pending_payout 保留期未定(需人类决策): V4 只补了 seller_uuid 索引, 未加清理/归档。退坑或长期离线卖家的待领款行永久驻留, 表单调增长。需拍板"离线满多久视为弃置"后另开一次变更实现, 见 MiningSchema.java:206-212。"同时在第四节或文档头部状态行补记V3(economy_settled 幂等锚补列, MiningSchema.java:140)与V4两版迁移的落点, 使迁移版本号(现已到V4)与文档记录的阶段提交序列对齐, 避免读者误以为该计划已随V1/V2/阶段5全部收尾。

**[Major] [E-状态过期] 第六节第7条不稳定用例的根因已定位并修复**

- 位置: 第 365-368 行
- 文档原文: 7. **一条疑似不稳定的既有用例 (待查, 与本迁移无关)**: 施工期间 `munitions` 批次的 `inprogressAssemblySurvivesSaveLoadRoundTripAndStillDelivers` 出现过一次失败 ("complete recipe must start assembly"), 同一份代码立即复跑通过…没有把它当作已排除 —— 它可能是既有的时序敏感用例, 值得单独查一次…
- 代码实际: 根因已在 main 的 bd0c4588 定位并修复: Forge 配置文件的 autosave + FileWatcher 在 GameTest 里自写自重载, 导致任何读配置的用例随机变红, 未改动的 main 同样复现。已新增 GameTestConfigWatchGuard 在 GameTestServer 启动时摘掉全部配置监视器, 并配了按磁盘逐份核对的用例。该遗留项应闭合, 否则下次偶发时仍会被当成未知问题重查一遍。
- 取证: src/main/java/com/miningdim/core/GameTestConfigWatchGuard.java:46 「public final class GameTestConfigWatchGuard {」与 L85 「watcher.removeWatch(path);」; 分支协作.md:78 「**GameTest 里禁止让配置热重载跑起来**:…于是**任何**正在读配置的用例都可能随机变红(实测 `*StateReadsConfigLive`、`networkOverheatsUnderSustainedLoadThenRecovers`、两条军火台用例都中过招,且未改动的 main 同样复现)。`GameTestConfigWatchGuard` 已在 `GameTestServer` 启动时摘掉本 mod 全部配置文件的监视器」; 用例实名为 GunsmithAssemblyBusinessGameTests.java:123 「public static void inProgressAssemblySurvivesSaveLoadRoundTripAndStillDelivers」(文中大小写写错)
- 建议改法: 把第 7 条改成删除线加结论: 「~~疑似不稳定的既有用例~~ **已闭合 (bd0c4588)**: 根因是 Forge 配置 autosave + FileWatcher 在 GameTest 里自写自重载, 由 `GameTestConfigWatchGuard` 修复, 详见 `分支协作.md:78`。」顺手把用例名改成正确大小写 `inProgressAssembly…SurvivesSaveLoadRoundTripAndStillDelivers`(该方法实际声明在 GunsmithAssemblyBusinessGameTests.java 第152行)。

### `docs/Ore_Pricing_Ledger_DesignSpec.md`

**[Major] [C-实现状态标错] 探矿技能失效条目已修复未销号**

- 位置: 第 161, 247 行
- 文档原文: L161「矿工探矿技能 `OreScanService.scan`（`OreScanService.java:68`）查 `OreSystem.cachedPlacement`，而该缓存只在 `placementFor` 被调用时填充——既然无生产调用方，探矿技能当前事实失效」；L247「**H. 探矿技能修复**（第八章 8.1）：若走 vanilla worldgen，`OreScanService` 须改为扫 `ServerLevel` 实际 `BlockState`…否则矿工 L6 探钻 / L8 探金残骸里程碑空转」
- 代码实际: OreScanService 已按 Minor-H 的建议改造完毕：scan/scanWorld/scanWorldDetailed/collectWithinSphere 全部吃 ServerLevel 并用 level.getBlockState(cursor) 扫真实世界，类注释直接点名「改扫真实世界缘由（经济文档第十章 H）」，MinerGameTests 还加了「删世界扫描回到死体素表必挂」的回归锚。第十章 Minor-H 已闭合却仍挂在待办清单里。
- 取证: src/main/java/com/miningdim/job/miner/OreScanService.java:24-26 「改扫真实世界缘由 (经济文档第十章 H): 旧实现读 {@link com.miningdim.ore.OreSystem#cachedPlacement} 死体素表…改读真实世界后, L3/L6/L8 探矿…」；:190 `Block block = level.getBlockState(cursor).getBlock();`
- 建议改法: 8.1 末条(docs/Ore_Pricing_Ledger_DesignSpec.md:161)改为:「探矿技能已改扫真实世界 BlockState(`OreScanService.scanWorld`/`collectWithinSphere`,见 `OreScanService.java:24-27,190`),与 `OreSystem` 死代码解耦;`OreSystem.placementFor`/`cachedPlacement` 仍无生产调用方(仍是死代码),但不再影响探矿技能」;第十章 Minor-H 条目(:247)整条删除或改标 DONE 并注明修复落点(`OreScanService.scanWorld` + `MinerGameTests.oreScanReadsRealWorldBlocks` 回归锚)。

**[Major] [C-实现状态标错] 农夫 faucet 档值 2160 的描述已过期**

- 位置: 第 188-189, 220 行
- 文档原文: L188「`WHEAT_BASE_PRICE=1`（`FarmerConstants.java:60`，自承 PENDING）+ `DAILY_CREDIT_FAUCET_CAP=2160`（`:85`，自承 PENDING「接通后须与矿工传同一值」）均为占位。」；L220「统一 credit_faucet 无 canonical 数值，唯一传入值 2,160 是农夫私塞占位（=小麦株 softcap，量纲是「株」非「CP」），对矿工主力荒谬偏低」
- 代码实际: FarmerConstants.DAILY_CREDIT_FAUCET_CAP 已改为直接转引 EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER = 60000L（CP 量纲），2160 只剩 WHEAT_DAILY_SOFTCAP（株量纲，喂收购曲线）一处。红队 #9 建议的「economy 侧定唯一全局常量 + 农夫 cap 改引用」已经落地，只是常量名是 ..._TIER 而非 ..._CAP。WHEAT_BASE_PRICE=1 仍是占位（在 :69 而非 :60），这一半仍成立。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerConstants.java:122-123 `public static final long DAILY_CREDIT_FAUCET_CAP = com.miningdim.economy.EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;`；:116-117 「此前农夫私有占位 2160L (株量纲误塞 CP 档) 已废, 改为全服唯一真源」；src/main/java/com/miningdim/economy/EconomyConstants.java:86 `public static final long GLOBAL_DAILY_CREDIT_FAUCET_TIER = 60000L;`
- 建议改法: L188 改为:「`WHEAT_BASE_PRICE=1`（`FarmerConstants.java:69`，仍 PENDING，注释「PENDING 经济文档 8.6 校准」）；`DAILY_CREDIT_FAUCET_CAP`（`FarmerConstants.java:122-123`）已改为直接转引 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER`（`EconomyConstants.java:86`，=60000，CP 量纲，矿工 settleOreSale 与农夫共用同一常量），株量纲的 2160 只剩 `WHEAT_DAILY_SOFTCAP`（`:72`）一处，喂收购曲线 softCap，与 CP cap 已解耦。」第九章表格 #9 行"漏洞"列保留(WHEAT_BASE_PRICE 半仍占位)，"本轮处置"列标 DONE 并注明真源常量名为 `GLOBAL_DAILY_CREDIT_FAUCET_TIER`（而非其建议命名的 `GLOBAL_DAILY_CREDIT_FAUCET_CAP`）。第 189 行"若 settleOreSale 并入统一 faucet（Critical-1）"的假设性措辞也应一并核实并按已落地状态改写(不在本条待核范围内, 建议另开一条 E 类过期状态标记一并处理)。

**[Major] [A-文档与代码不符] 时运只对铜铁煤生效的结论已失效**

- 位置: 第 195, 221 行
- 文档原文: L195「故时运只对连锁白名单的铜/铁/煤（`CHAIN_WHITELIST` `:159-164`）生效。定价依据不引用「时运对钻石生效」。」；L221 第九章 #10 处置「**非漏洞**（反而让钻石 cap 更稳）」
- 代码实际: 时运的施加范围确实仍只在连锁/隧道路径，但连锁白名单已废除，钻石/金/残骸被连锁时同样进 MinerFortune.withFortuneExtras，拿得到 +8%(L4)~+50%(L10) 的额外掉落，且额外掉落经方案 B 计入当日矿物计数并逐颗发钱。第九章 #10 判为「误报、反而让钻石 cap 更稳」的依据随之消失。
- 取证: src/main/java/com/miningdim/job/miner/MinerFortune.java:15-16 「施加范围 (本类作用域): 连锁 ({@link MinerSystem#onChainProduce}) / 隧道 ({@link MinerActions} tunnelProduce) 的连带破坏」；src/main/java/com/miningdim/job/miner/ChainMiningEngine.java:285-289 chainable() 对钻石/金/残骸矿全部返回 true
- 建议改法: 8.5 第二条改为：「时运仍只作用于连锁/隧道路径（ChainMiningEngine.chainable，单一镐可采+档位足+可破坏+无BlockEntity判定），但该路径的矿种白名单/高价矿硬排除已于2026-07-12(commit 82e96be3)废除——钻石/金/残骸被连锁或隧道连带时同样进入 MinerFortune.withFortuneExtras，可吃到 +8%(L4)~+50%(L10) 的额外掉落；额外掉落经 MinerSystem.replayEconomyOreCount 计入方案B当日矿物计数，并由 EconomyService.recordMinedOreDrops 逐颗走 settleOreSale/grantDaily 入统一衰减主闸（EconomyService.java:138-163 注释自证）。」第九章#10 verdict 由「误报/非定价·非漏洞」改回「待评估」，说明高价矿时运封顶现完全依赖衰减主闸(Critical-2)而非"手挖不吃时运"的旧前提，二者叠加是否仍安全需连带 Critical-1/2 一并裁定。

**[Major] [A-文档与代码不符] 铜 P2P 单人 cap 数值与机制同代码不符**

- 位置: 第 285, 237 行
- 文档原文: L285「按矿工本人计的每日铜产出/出售软上限 **≈ 850–900 铜/人/日**（不分卖系统还是卖军火商，复用 0.97/0.01 衰减），并入跳蚤手续费 + 流水审计防对敲。封「源头」（铜是挖出来的），不封 P2P 流通。」
- 代码实际: 代码里唯一落地的铜上限是市场侧的 COPPER_IRON_DAILY_P2P_CAP = 512，与决策③四处不同：(1) 512 不是 850-900；(2) 是铜与铁共用的同一个额度，不是铜专属；(3) 是超限直接抛异常拒绝挂单的硬上限，不是 0.97/0.01 衰减；(4) 口径是「今日该卖家这些 item 的 ACTIVE 挂单 + 已 SOLD 成交」，恰好封在决策③明说不封的 P2P 流通侧而非源头。按本节去实现会做出第二套与现有闸互相打架的规则。
- 取证: src/main/java/com/miningdim/market/MarketConstants.java:51 `public static final int COPPER_IRON_DAILY_P2P_CAP = 512;`；:47-49 「铜/铁每卖家每日 P2P 量上限 (DRAFT, 待用户依定价台账标定)…口径 = 今日该卖家这些 item 的 (当前 ACTIVE 挂单 count 之和 + 今日已 SOLD 成交 count 之和)」；src/main/java/com/miningdim/market/MarketEngine.java:127-131 超限 `throw new IllegalStateException("今日铜/铁 P2P 挂单量已达上限 …")`
- 建议改法: 第十一章③ 补一段「落地现状」: 现有实现是市场侧 `MarketConstants.COPPER_IRON_DAILY_P2P_CAP=512`(铜铁合计共用同一常量与同一 `COPPER_IRON_ITEM_IDS` 集合、超限在 `MarketEngine.place()` 直接抛 `IllegalStateException` 拒挂的硬上限、口径统计的是今日 ACTIVE 挂单+SOLD 成交即 P2P 流通侧), 与本节「850-900 铜/人/日 + 0.97/0.01 衰减 + 封源头不封 P2P 流通」四点均不一致。须二选一并同步更新 `docs/WebUI_Architecture_DesignSpec.md:125`、`docs/WebUI_Frontend_Wiring_Checklist.md:149`、`docs/WebUI_Wiring_Execution_Scope.md:97`、`docs/Economy_Laundering_Review.md:82` 这四处已把 512 当现实记录的文档: 要么把本节决策改写为对齐 512 硬上限机制(不做衰减、铜铁合并、封流通侧), 要么保留 850-900+衰减+封源头 的决策并把 `MarketConstants` 的 DRAFT 值与机制按此重新实现; 同时在第十章 L237 Major-A 条目上补一句「已由第十一章③ 裁定, 但裁定内容与现有 `MarketConstants` 实现冲突, 需二次确认」, 避免读者以为该项已经和代码对齐。

**[Major] [F-结构问题] 跨文档与文档-代码引用全用文件名加行号, 752 处已批量腐烂**

- 位置: 第 57 行
- 文档原文: L57「2. **高价矿物理排除连锁/隧道**（`CHAIN_HARD_EXCLUDE` `MinerConstants.java:170-175`）：钻石/金/残骸/绿宝石只能手挖单块。铜/铁/煤才进白名单（`:159-164`…」
- 代码实际: 全库文档用「文件名:行号」做定位的引用共 752 处(指向 .java)加 22 处(指向 .md), 没有任何一处用可稳定解析的锚点(章节标题、类全限定名、常量名)。行号随任何一次无关编辑漂移, 且没有校验。机械可检出的最低限度腐烂已有 43 处: 2 个被引用的项目类已删除, 5 处行号越过文件末尾; 真正的多数情形(行号漂移但仍在文件内)连机械都查不出来。
- 取证: src/main/java/com/miningdim/job/miner/MinerConstants.java 实际只有 168 行(wc -l), 文档引的 170-175 直接越界; 且 `grep -rn CHAIN_HARD_EXCLUDE src/` 只在 src/main/java/com/miningdim/job/miner/ChainMiningEngine.java:273 命中一条注释「单一连锁判定 (替代旧 CHAIN_WHITELIST / CHAIN_HARD_EXCLUDE 双表, 用户 2026-07-12 裁决"矿洞维度内连锁全放开")」—— 常量本身已删除, 代码把废止记录写在了自己身上, 文档还在按行号指过去。另两例: docs/Economy_Completeness_Audit.md:154 引 `EconomyWalletData.java:262`(该文件实际 214 行); docs/Economy_Completeness_Audit.md:127 引 `AgentChampionData.java:47-56`(src/ 下该类已不存在)。
- 建议改法: 把引用口径改成「文件路径 + 类名/方法名/常量名」, 禁止在文档正文钉死行号; 文档间引用改用 Markdown 章节锚点。加一条构建期检查(可挂在 verifyModuleRegistry 上): 扫描 docs/*.md 里所有 `X.java` 与 `X.java#符号` 引用, 断言文件存在且符号可在文件中检索到, 命中失败即构建失败。存量 752 处按文档逐份清理, 优先 Ore_Pricing_Ledger(35 处)、Economy_Completeness_Audit(29 处)、Champion_Effects_Guide(15 处)。

### `docs/Power_Economy_Rebalance_DesignSpec.md`

**[Major] [F-结构问题] 经济主题 6 份文档无主从层级, 已形成自述的循环引用**

- 位置: 第 306 行
- 文档原文: L306「**阻塞项（需先补一张表才能推进）**：仓库里没有覆盖原版长尾物料的信用点定价表。…本文与 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 之间因此形成循环引用：两边都指望对方提供物料定价。**需要先决定这张表由谁承载、并落成代码真源，燃料芯配方重标才有依据。**」
- 代码实际: 经济主题横跨 6 份文档(服务器经济系统设计文档.md、Economy_BalanceSheet_DesignSpec.md、Ore_Pricing_Ledger_DesignSpec.md、Power_Economy_Rebalance_DesignSpec.md、Economy_Completeness_Audit.md、Economy_Laundering_Review.md), 彼此用「前置真源」互指, 但没有任何一份被指定为顶层。结果是文档自己写下了死锁: 两份文档互相等对方提供同一张定价表, 且这条阻塞项至今没有仲裁出口 —— 文档体系里不存在「谁来裁定真源归属」的位置。同一批文档还有三份自称唯一真源(Power_Economy_Rebalance:3「本文是重标定后的唯一真源」、Power_Generator:5「唯一真源」、JobFramework:5「唯一真源」), 谁覆盖谁没有规则。
- 取证: 代码侧对应的确实是空缺: 文档 L306 点名的 `ShopPriceTable` 只落三项、`DefaultBaseValues` 只有四项, 而《服务器经济系统设计文档》8.1 的十项 CP 数值从未同步进代码 —— 也就是说这个循环引用导致的后果(没有代码真源)是实际存在的, 不是纸面问题。docs/Economy_BalanceSheet_DesignSpec.md:4-6 的前置真源块反向指回 Ore_Pricing_Ledger 与 服务器经济系统设计文档, 三者构成闭环。
- 建议改法: 在 docs/README.md 里为每个主题簇明确唯一顶层文档与从属关系(经济簇建议以 服务器经济系统设计文档.md 为顶层, Economy_BalanceSheet 为收支总账, Ore_Pricing_Ledger / Power_Economy_Rebalance 为子域), 并规定「自称唯一真源」只有顶层文档可写。物料定价表按 L306 的要求指定承载方(建议 Economy_BalanceSheet)并落成代码单一真源, 落码后回填 L306 销号。

**[Major] [A-文档与代码不符] "铁线可喂 2 台地热机"超出铁缆额定，且举例全部越过 75% 安全线**

- 位置: 第 316 行
- 文档原文: L316：“低压三级（铁 256 / 铝 768 / 铜 1,280）在前期两台发电机进场后获得真实需求：铁线可喂 5 台煤炭机或 2 台地热机，铝线对应中型基地（16 台煤炭 / 5 台地热）。”
- 代码实际: 地热机峰值 144 FE/t，2 台即 288 FE/t，直接超过铁缆额定 256 FE/t；即便不超额，线缆热学的安全持续线是额定的 75%（铁 192、铝 576），文中四个举例（铁 5 煤炭 240、铁 2 地热 288、铝 16 煤炭 768、铝 5 地热 720）全部高于对应安全线，按文档配线会持续升温降效而非“真实需求”。
- 取证: src/main/java/com/miningdim/power/cable/ConductorMaterial.java:23 `IRON("iron", 256, 0.35, ...)`、:24 `ALUMINUM("aluminum", 768, ...)`；src/main/java/com/miningdim/power/generator/PreheatGeneratorSpec.java:19 `GEOTHERMAL("geothermal", FuelSource.LAVA_SOURCE_BELOW, 144, ...)`；src/main/java/com/miningdim/power/grid/CableThermics.java:21 `public static final double SAFE_LINE = 0.75;` 与 :51-56 `double overload = loadRatio - SAFE_LINE; if (overload > 0.0) { next = currentTempC + HEAT_RATE_C * (overload / (1.0 - SAFE_LINE)); }`。
- 建议改法: 把 docs/Power_Economy_Rebalance_DesignSpec.md:316 的举例按 CableThermics.SAFE_LINE=0.75 重算并改写为："铁线（安全持续 192 FE/t）可喂 4 台煤炭机或 1 台地热机，铝线（安全持续 576 FE/t）对应 12 台煤炭或 4 台地热"，并补一句"容量按额定的 75% 安全线而非额定值本身配线，超过安全线即持续升温降效（见 CableThermics.advanceTemperature）；铁线两台地热机（288 FE/t）还会直接超过铁线 256 FE/t 的额定上限，实际会被 EnergyNetworkManager 的 effCap 节流，发电机产出无法全额送达"。

**[Major] [E-状态过期] 待定项2"护甲吸伤比例与耐久模型"早已实装并有独立权威文档，且与同文档L357自相矛盾**

- 位置: 第 381 行
- 文档原文: L381：“2. 护甲吸伤比例与耐久模型（与战斗环境标定联评）。”；L277 亦写“具体吸伤比例……一并标定，本文不定。”
- 代码实际: 护甲吸伤模型（弹道防护 R、破甲缓冲 Q、通用防护 G、单次承压 T）与逐材料耐久、四类泄漏乘子、移动惩罚均已作为服务端配置项落地并带默认值；同文 L357 自己也把“护甲耗电”标为已落地。
- 取证: src/main/java/com/miningdim/job/engineer/armor/PlateArmorConfig.java:60-66 定义 `ballisticProtectionR` / `armorPiercingBufferQ` / `generalProtectionG` / `pressureCapacityT`，:94-109 逐 `PlateArmorConstructionMaterial` 定义 `durability` / `ballisticLeak` / `armorPiercingLeak` / `generalLeak` / `pressureCapacity` / `movementPenalty`；:78 `defineInRange("fePerAbsorbedDamage", 5_000, 0, 10_000_000)` 与本文 4.2 的“每吸收 1 点伤害扣 5,000 FE”一致。
- 建议改法: 将 docs/Power_Economy_Rebalance_DesignSpec.md:381 第2条改写为"护甲吸伤比例（R/Q/G/T）与逐材料耐久/泄漏模型已落地于 PlateArmorConfig（详见权威文档 Armorer_Armor_System_DesignSpec.md），剩余待办仅为与 80 血高 DPS 环境的最终数值复核标定"；同步修订 L277 的"本文不定"为"具体数值已在 PlateArmorConfig 落地默认值，是否需按 80 血高 DPS 环境复核见 Armorer_Armor_System_DesignSpec.md"，避免与本文档自身 L357"护甲耗电 已落地"矛盾。

**[Major] [E-状态过期] 待定项 5"三级储电的方块形态与 GUI"早已落地并写在同文 3.4 节**

- 位置: 第 384 行
- 文档原文: L384：“5. 三级储电的方块形态（单方块或多方块）与 GUI。”（列在“待定，需在实现前定案”下）
- 代码实际: 同文 L217 小节标题即写“### 3.4 松散聚合多方块（已实现）”，L352-354 状态表也写“三级储电 | 已落地 | `com.miningdim.power.storage.PowerCell*`”。代码侧三档储电方块、方块实体、菜单、专属界面底图与界面坐标全部落地。
- 取证: src/main/java/com/miningdim/power/PowerRegistry.java:69-74 注册 industrial/modern/future_power_cell，:182-184 注册 `POWER_CELL_MENU`；src/main/java/com/miningdim/power/storage/PowerCellGroup.java:45 `public static final int MAX_MEMBERS = 64;`（对应 3.4 表的“成员上限 64”）；src/main/java/com/miningdim/power/client/PowerCellScreen.java:20-25 `METER_WIDTH = 178 / MAIN_METER_Y = 36 / RECEIVED_METER_Y = 84 / EXTRACTED_METER_Y = 104`，与 docs/Power_Preheat_Generator_AssetChecklist.md:99-101 的 3.2 坐标表逐格一致。
- 建议改法: 删除 L384 第 5 条，或改写为"三级储电形态已定案为松散聚合多方块（见 3.4）与 `power_cell.png` 单张共用界面（见 Power_Preheat_Generator_AssetChecklist 第三章），本条关闭。"

**[Major] [E-状态过期] 待定项 6"金导体取 3,200 还是 2,560"与同文七之二"已落地"自相矛盾**

- 位置: 第 385（关联 320-325、352） 行
- 文档原文: L385：“6. 金导体取 3,200 还是保持 2,560 加 UI 提示。”（列在“待定，需在实现前定案”下）；L323 表格仍写“| 金 | **2,560** | HIGH | 低于低一级的 OFE 铜，且低于未来发电机峰值 3,072 |”
- 代码实际: 金导体早已按推荐方案上调到 3,200 并落码，同文 L352 自己也写“| 金导体 2,560 → 3,200 | 已落地 | `ConductorMaterial` |”。线缆设计文档 L79 亦已记为“吞吐 2026-08-19 由 2560 上调至 3200 以消除倒挂”。
- 取证: src/main/java/com/miningdim/power/cable/ConductorMaterial.java:30 `GOLD(                "gold",                   3200, 0.50, InsulationGrade.XLPE,     VoltageClass.HIGH,    ThermalMode.STANDARD, 221, false),`；docs/Power_Cable_DesignSpec.md:79 “吞吐 2026-08-19 由 2560 上调至 3200 以消除倒挂”。
- 建议改法: 删除 L385 第 6 条待定项（若为清单末条，删除后清单以第5条结尾，无需重排编号）；把 L323 表格中的金行改为"| 金 | 3,200（原 2,560） | HIGH | 已于 2026-08-19 上调，倒挂已消除 |"；并在第六章（约 L329 推荐方案句后）或第八章清单末尾补一句交叉引用，如"本条已按推荐方案（3,200）落地，见七之二实现状态表 `ConductorMaterial`"，避免后续读者仅读到第六/第八章就误以为该项仍待拍板。

### `docs/服务器经济系统设计文档.md`

**[Major] [A-文档与代码不符] 跳蚤手续费四项口径全部与代码不符**

- 位置: 第 119-123, 154-156 行
- 文档原文: L121「由卖家承担,成交时销毁(确认是 sink,不入任何账户)。」；L123「正常价格带内收常规低费率(随交易信用等级递减),偏离基准价越远费率成倍增加。」；L154「正常带:基准价的某区间内(建议起始 70%–150%)只收常规低费率」；L156「可设硬性挂单价格区间(如基准价的 30%–300%),超出直接拒绝挂单」
- 代码实际: 实现与这四条全都不同：(1) 手续费是「上单即收、撤单/未售不退」，在 MarketEngine.place 收，不是成交时收；(2) 平价基础费率 20%，且被明文定稿为「高税重摩擦市场」，不是「常规低费率」；(3) 没有 70%-150% 正常带，偏离费是连续的二次对数式 fee = max(V0,VR)×count×(0.20 + 0.04×ln(VR/V0)²)，平价即最低点；(4) 没有 30%-300% 硬拒单区间，靠「费大到付不起 -> tryCharge 失败 -> 挂单被拒」自限。第二章的交易信用等级 I-V 费率减免全库无任何实现。照三章去做会做出与已定稿实现完全不同的第二套费率。
- 取证: src/main/java/com/miningdim/market/MarketConstants.java:24-30 「诚实按基准价挂单也付 20% 上单费且不退 —— 刻意的高摩擦, 强反通胀 (sink 蒸发) 与反洗钱…public static final double FEE_RATE = 0.20D;」；:27-28 「收费时机 (用户决策"上单即收"): 本费在 {@link MarketEngine#place} 向卖家一次性收取…撤单/未售【不退】」；src/main/java/com/miningdim/market/MarketFee.java:55 「偏离费 = max(V0,VR) * count * (FEE_RATE + DEVIATION_K * ln(VR/V0)^2)。对称 (两端同罚), 不封顶。」；:15 「不封顶 (用户决策): 极端偏离的费可达 long 饱和, 卖家付不起即挂单失败 (自限, 无需硬天花板)」
- 建议改法: 3.1/3.3 整体改写为实现口径: 上单即收不退(MarketEngine.place, 撤单 cancel 不退费)、平价基础费率 20%(MarketConstants.FEE_RATE)、偏离费连续公式 fee = max(V0,VR)×count×(FEE_RATE + DEVIATION_K×ln(VR/V0)^2)(DEVIATION_K=0.04, 平价即费率最低点, 无正常带平台)、无硬性挂单价格区间, 靠偏离费趋近 Long.MAX_VALUE 导致 tryCharge 失败自限(MarketFee.deviationFee 注释); 第二章"交易信用等级 I-V 费率减免"应明确标注未实现(全仓无 TradeCredit/CreditLevel 等任何实现, 且与 docs/Economy_Completeness_Audit.md:87 已列的"未落地"项一致), 避免与 20% 平率描述打架。

**[Major] [A-文档与代码不符] 动态基准价机制与实现方向相反**

- 位置: 第 127, 129-144 行
- 文档原文: L127「基准价不由人工逐一拍定,而由系统按近期成交滚动均价动态计算。人工定价在数百上千 SKU 规模下不可维护、必然滞后、且是腐败点」；L131-137 的四阶段生命周期状态机；L143-144「时间窗口:滚动 N 天(建议起始 7 天)」「异常值剔除:计算前掐头去尾(建议剔除最高、最低各 5%)」
- 代码实际: 实现里的 V0 解析顺序恰好把文档点名为「腐败点」的人工定价放在第一优先：admin 后台手写覆盖（base_values 表，OP 逐个 curate，注释自述「操纵不动的强锚」）> 代码内置预设 DefaultBaseValues > 空则退平率费。基于市场成交中位数的第 3 层在代码注释里还标着「(后续 commit)」，resolve() 只查前两层。即滚动均价、7 天窗口、掐头去尾 5%、四阶段生命周期状态机全部未实现，且设计取向与文档相反。
- 取证: src/main/java/com/miningdim/market/BaseValueResolver.java:8-13 「基准价值 V0 分层解析器 (偏离费 {@link MarketFee} 的锚来源, 用户设计的分层)。优先级: 1. admin 后台手写覆盖 ({@link MarketDao#getBaseValue}, base_values 表) —— 操纵不动的强锚, OP 逐个 curate; 2. 代码内置预设 ({@link DefaultBaseValues}) …; 3. (后续 commit) 市场成交中位数 (带钳制) —— 长尾自税兜底; 4. 空 -> 调用方…退平率费」；:34-40 `resolve()` 只查 dao.getBaseValue 与 DefaultBaseValues 两层
- 建议改法: 3.2 拆成两段描述:「当前实现 = admin 覆盖(强锚,反操纵,见 BaseValueResolver.resolve() 第 1 步)> 内置预设 DefaultBaseValues(第 2 步)> 无锚退平率费(MarketFee 兜底)」,以及「滚动成交均价(7 天窗口 + 掐头去尾 5% + 四阶段生命周期状态机)= 规划中的第 3 层,代码注释标注为"(后续 commit)",尚未实现」;并将 L127 对人工定价"不可维护、必然滞后、且是腐败点"的全盘否定改写为"人工定价目前只用于 admin curate 的少量强锚商品(反操纵设计),长尾 SKU 暂无成交中位数兜底,该能力待后续 commit 落地"。

**[Major] [B-文档互相打架] 8.1/8.5 仍写 ×1.75 cap 与 0.25 地板**

- 位置: 第 339, 370 行
- 文档原文: L339「高价矿每日软上限(`EconomyConstants`,建议 ×1.75 适配 5h):钻石 ~112/日、金 ~448/日、残骸 ~14/日;超限买价按 `0.97^(n-cap)` 衰减至 25% 地板。」；L370「（复用 UTC 翻日 + 0.97/0.25 框架）」
- 代码实际: 同一份文档 L368 的决策定稿已经写明「地板由 0.25 砍至 **1%**，取代 8.1 的 ×1.75 cap 与 18.3 的 0.25 地板」，Ore_Pricing 第十一章也把「×1.75 旋钮」判为作废；代码里 cap 仍是 64/256/8（从未 ×1.75），地板常量是 0.01。8.1 与 8.5 自己的正文却把作废值和旧地板原样留着，同一份文档三处互相打架，照 8.1 去实现会写出错误的 cap 与地板。
- 取证: src/main/java/com/miningdim/economy/EconomyConstants.java:50 `public static final int DAILY_SOFTCAP_DIAMOND = 64;`、:53 `DAILY_SOFTCAP_NETHERITE_SCRAP = 8;`、:56 `DAILY_SOFTCAP_GOLD = 256;`；:68 `public static final double ECONOMY_PRICE_FLOOR_RATIO = 0.01D;`
- 建议改法: L339 删掉「建议 ×1.75 适配 5h」整句与 112/448/14 三个数,改成「高价矿每日软上限(`EconomyConstants` 现值):钻石 64/日、金 256/日、残骸 8/日;超限买价按 `0.97^(n-cap)` 衰减至 **1% 地板**(`ECONOMY_PRICE_FLOOR_RATIO=0.01`)」;L370 的「0.97/0.25 框架」改成「0.97/0.01 框架」。

**[Major] [E-状态过期] 0.3 第 35 行"四个战斗职业"清单口径过期且失真, 与 8.4 职业表及代码实际战斗集成(厨师凝脂/酿酒师烈酒钝感与金酒/特勤伤害加成)均对不上**

- 位置: 第 35 行
- 文档原文: L35「本服不是纯外观服,而是"外观 + 战斗职业"混合服(矿工/农夫/千年工程师/塔罗师四个职业有实打实战斗内容)。」
- 代码实际: JobId 枚举已有 8 个职业（MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER），另有未登记为 JobId 的渔夫模块。0.3 自称「优先级高于下文」，按它去判断「哪些职业与经济系统相关」会直接漏掉厨师/特勤干员/军火商/酿酒师四条，而其中特勤与军火商在 8.4 自己的职业锚表里是有行的，同一文档前后不一致。
- 取证: src/main/java/com/miningdim/job/JobId.java:20-32 `MINER("miner"), FARMER("farmer"), ENGINEER("engineer"), TAROT("tarot"), CHEF("chef"), AGENT("agent"), MUNITIONS("munitions"), BREWER("brewer");`
- 建议改法: 不要简单把括号内容改成"矿工/农夫/千年工程师/塔罗师/厨师/特勤干员/军火商/酿酒师八个职业(JobId 枚举)"——这会把"未接入统一减伤/加成系统的农夫、军火商"和"已接入的厨师/酿酒师/特勤干员"一并笼统称为"有实打实战斗内容", 同样失真。建议改为: 按 src/main/java/com/miningdim/combat/PlayerDamageReduction.java 的 ReductionSource 实现清单与各职业伤害加成/减免 handler(矿工矿脉抗性、厨师 ChefGreaseReduction、酿酒师 VodkaNumbness/GinMaxHealthManager、塔罗 MaxHealthModifierManager 与 TarotCombatHandlers、特勤 AgentDamageBonusHandler、工程师 PlateArmorDamageHandler/PlasmaShieldHandler)逐一核实后, 如实列出当前真正接入战斗结算的职业集合, 农夫是否仍算"战斗职业"需单独复核(目前代码侧找不到任何战斗相关文件); 同时把 0.3 第 1 条与 8.4 表格的职业口径对齐(至少把 8.4 已单列的特勤干员/军火商纳入 0.3 的裁定范围), 避免同一文档内两节对"哪些职业受本节裁决约束"给出不同答案。

**[Major] [A-文档与代码不符] 钱包余额仍写落 Capability/SavedData**

- 位置: 第 44, 48 行
- 文档原文: L44「信用点/青辉石余额 + 职业/世界/实例数据 -> Capability/SavedData(单服,与矿山 spec 2.4 一致)。」；L48「系统购买(卡包/箱子/重置)= 直接扣 Capability 余额;玩家间转移 = 经 DB 记录的交易通道。」
- 代码实际: 余额早已迁进统一 SQLite。旧的 EconomyWalletData（矿山维度 SavedData）类注释自称「合库之前的全服双货币账本…现在只读」，唯一职责是把老存档搬一次家；现行账本是 SqliteEconomyLedger，所有扣费（卡包/开箱/重置/手续费）都走 IEconomyService.tryCharge -> ledger.tryDebit，没有任何一条路径去读写 Capability 余额。同节 L45-47 已经写了「已把钱包、双币幂等账本与每日计数一并收编」，但 L44/L48 没跟着改，按 L48 去实现会去找一个不存在的 Capability。
- 取证: src/main/java/com/miningdim/economy/EconomyWalletData.java:17-21 「合库之前的全服双货币账本 (矿山维度 SavedData, 数据文件名 …), 现在只读。账本已迁往统一 SQLite ({@link SqliteEconomyLedger}), 本类唯一的职责是把旧存档里的余额…读出来交给 {@link EconomyLedgerBootstrap} 搬迁一次。」；src/main/java/com/miningdim/economy/EconomyService.java:72-74 `public boolean tryCharge(ServerPlayer player, Currency currency, long amount) { return ledger.tryDebit(player.getUUID(), currency, amount); }`
- 建议改法: L44 把「信用点/青辉石余额」从 Capability/SavedData 一栏挪到下面的 SQLite 一栏,只留「职业/世界/实例数据 -> Capability/SavedData」(该后半句本身准确, 对应 entry/MiningCapabilities.java 的 IMiningPlayerData);L48 改成「系统购买(卡包/箱子/重置)= 经 IEconomyService.tryCharge/tryChargeBundle 扣统一 SQLite 账本余额(开箱等多资产操作走 tryChargeBundle 保证与发货同事务);玩家间转移 = 经同库交易通道并落流水」。

### `docs/Economy_BalanceSheet_DesignSpec.md`

**[Minor] [D-覆盖缺口] 职业收支表漏登酿酒师(JobId 第 8 个正式职业未列入,且未注明其当前无信用点经济接线)**

- 位置: 第 27-38 行
- 文档原文: L27「| 职业 | 收入(类型) | 吃进/支出 | 经济定位 |」下 9 行依次是矿工/农夫/渔夫/军火商/千年工程师/厨师/塔罗师/特勤/精英怪，无酿酒师
- 代码实际: 酿酒师是 JobId 枚举里的正式职业（BREWER，第 8 个），酿酒台/酒窖箱/永久增益整条链已落地，但顶层收支总表里既无它的收入也无它的支出定位；反过来表里列了「渔夫」而渔夫并不在 JobId 枚举里（这一点 † 注已诚实说明）。诚实局限：src/main/java/com/miningdim/job/brewer/ 下目前确实没有任何 EconomyServices 调用，即酿酒师还没有自己的 faucet/sink，故定 Minor 而非 Major。
- 取证: src/main/java/com/miningdim/job/JobId.java:31-32 `/** 酿酒师 (Brewer): 至少七天周期的制造职业, 酿酒台酿基酒 + 酒窖箱陈酿年份 + 喝酒增益。尾部追加 (S2C 同序契约)。 */ BREWER("brewer");`
- 建议改法: 在第二章表格(docs/Economy_BalanceSheet_DesignSpec.md 第 27-38 行)补一行,措辞不要编造未经代码证实的经济类型,例如: 「酿酒师 | (当前无) | (当前无,仅耗实物原料) | 长周期制造职·尚未接入信用点经济」 并在同侧追加脚注,如:「‡ = 酿酒师(JobId.BREWER, 见 JobId.java:31-32)酿酒台/酒窖/永久增益链已完整落地, 但 job/brewer/ 目录下无任何 EconomyServices 调用, market/DefaultBaseValues.java 也无酒类定价锚点, 即当前无 faucet 亦无 sink; 是否/如何接入信用点经济待设计」。 不要写"卖酒（P2P）"或"收粮/收桶"这类具体机制,因为代码里找不到对应的 P2P 出售通道或按信用点购粮/购桶的逻辑,写具体机制会造成新的 A 类(文档与代码对不上)问题。

### `docs/Economy_Completeness_Audit.md`

**[Minor] [E-状态过期] GameTest规模统计已大幅过期**

- 位置: 第 81, 170 行
- 文档原文: L81「全库 63 个文件、766 处 `@GameTest`，经济与市场核心 44 个」; L170「全库 766 处 `@GameTest`」
- 代码实际: 现为 140 个文件、1616 处 @GameTest, 最近一次全量运行的质量门日志是「All 1474 required tests passed」。文末 L256 的免责只覆盖「行号」, 不覆盖这类被当作覆盖率论据的聚合统计。
- 取证: grep -rn "@GameTest" --include=*.java src/main/java 命中 1616 行、140 个文件; 提交 bd0c4588 的提交信息「连续跑五轮 runGameTestServer 全绿(All 1474 required tests passed), 零失败」
- 建议改法: 两处统计更新为「140 个文件、1616 处 `@GameTest`(其中 1474 处为方法级用例)；最近一次质量门 All 1474 required tests passed (bd0c4588)」, 并把 L256 的免责句从「行号对应基线 commit...需重新核对」扩展为「行号与文中全部聚合统计(文件数/处数等)均对应基线 commit...需重新核对」。

### `docs/Economy_Laundering_Review.md`

**[Minor] [A-文档与代码不符] 卖菜收购曲线地板写0.25, 代码已统一到1%**

- 位置: 第 39 行
- 文档原文: `WHEAT_BASE_PRICE=1`, 收购价 `= floor(1 * max(0.25, 0.97^over))`; 超过 2160 株软上限后第一株即 `floor(0.97)=0`。
- 代码实际: 地板比例已从农夫私有的 0.25 收敛为转引经济层单一真源 0.01, 代码注释直接把 0.25 记成「未随经济侧迁移的漂移」。base=1 时结论 (2160 硬顶) 不变, 但公式里的 0.25 会让读者按错的曲线重算其它 base 下的上限。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerConstants.java:83-86 「小麦收购价地板比例: 直接转引 {@link com.miningdim.economy.EconomyConstants#ECONOMY_PRICE_FLOOR_RATIO} (第十一章决策 1 已定 0.01 = 1%) 做单一真源…此前本类私有 0.25 未随经济侧 0.25 -> 0.01 迁移而漂移」; src/main/java/com/miningdim/job/farmer/FarmerGameTests.java:571-572 「helper.assertTrue(deepPrice == 10L, "floor ratio aligned to 1%: floor(1000 * 0.01) = 10 (was 250 under the pre-migration 0.25 drift)");」
- 建议改法: 公式改为 `= floor(1 * max(0.01, 0.97^over))`, 并补一句"地板比例转引 `EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO`(第十一章决策1, 当前0.01=1%), 与矿物/主闸同一真源, 此前农夫私有0.25已随经济侧0.25->0.01迁移收敛"。同时可顺带注明: 因 base=1 时本条2160硬顶/2160信用点的结论不受此drift影响, 仅公式字面量对非1的base场景会算错, 避免读者误以为结论本身也要重算。

**[Minor] [A-文档与代码不符] 称默认每人全7职业L1, 实际JobId已扩至8个成员(尾部追加BREWER)**

- 位置: 第 73 行
- 文档原文: 注意: `IJobService` 当前**只有 level 概念, 无"是不是农夫"的成员(membership)概念**(默认每人全 7 职业 L1)。
- 代码实际: JobId 枚举已有 8 个成员 (尾部追加 BREWER 酿酒师)。「无 membership 概念、默认全职业 L1」这一结论仍成立, 只是数目过期。
- 取证: src/main/java/com/miningdim/job/JobId.java:22-32 「MINER("miner"), FARMER("farmer"), ENGINEER("engineer"), TAROT("tarot"), CHEF("chef"), AGENT("agent"), MUNITIONS("munitions"), /** 酿酒师 (Brewer)…尾部追加 (S2C 同序契约)。 */ BREWER("brewer");」
- 建议改法: 将 docs/Economy_Laundering_Review.md:73 括号内「默认每人全 7 职业 L1」改为「默认每人全 8 个 `JobId` 成员 L1 (含尾部追加的 BREWER 酿酒师, 见 JobId.java:22-32)」; 前半句「`IJobService` 当前只有 level 概念, 无 membership 概念」经核实仍成立(IJobService.java:13-19), 无需改动。

### `docs/Power_Economy_Rebalance_DesignSpec.md`

**[Minor] [F-结构问题] 两处会话记忆条目键名混入正文Markdown链接：277行是锚文本名不副实（链接目标本身正确有效），375行是无定义引用式死链（不会渲染为可点击链接）**

- 位置: 第 277、375 行
- 文档原文: L277：“具体吸伤比例与 [server-combat-environment](Economy_BalanceSheet_DesignSpec.md) 的 80 血高 DPS 环境一并标定”；L375：“物流 mod 选型（XNet / AE2）继续挂起，见 [power-system-decision]。”
- 代码实际: `server-combat-environment` 与 `power-system-decision` 都是会话记忆条目的键名，不是仓库文档。L277 的链接文字与目标文件不同名，读者会以为存在一份同名文档；L375 是未定义的 Markdown 引用式链接，仓库内既无该文件也无对应的链接定义行，渲染后是死引用。
- 取证: 仓库 docs/ 目录下不存在 power-system-decision 或 server-combat-environment 文件（`ls docs/ | grep -i power-system` 无输出，docs/ 下 Power 前缀仅 Power_Cable_AssetChecklist.md、Power_Cable_DesignSpec.md、Power_Economy_Rebalance_DesignSpec.md、Power_Generator_DesignSpec.md、Power_Preheat_Generator_AssetChecklist.md 五份）；同文件内也无 `[power-system-decision]:` 链接定义行（grep 仅命中 L375 本身）。本条为纯文档结构问题，代码侧无对应实体可取证。
- 建议改法: L277：链接目标 Economy_BalanceSheet_DesignSpec.md 本身正确、无需更换，只需把锚文本从会话记忆键名 `server-combat-environment` 换成反映目标文档内容的文字，例如：「具体吸伤比例与 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 中 80 血高 DPS 的战斗环境假设一并标定」。 L375：`[power-system-decision]` 缺少对应的引用定义行，会被渲染为纯文本方括号、语义上等于失去了跳转能力，应去掉方括号改写为纯文本，例如「见电力系统选型决策记录（暂只存在于协作会话记忆，仓库内尚无对应文档）」；若该决策后续要落成仓库文档，再补一条指向真实文件的 `[标题](路径)` 链接。

---

## 矿工与农夫

### `docs/FarmingXP_Mod_DesignSpec.md`

**[Critical] [B-文档互相打架] 表C 衰减表与共享地基 2000 系表互相打架, 代码站表C**

- 位置: 第 3, 75-89 行
- 文档原文: L3 SUPERSEDED 注记: "本文档的等级曲线、**每日衰减表**、耕地数值仍有效,作为农夫职业的数值参考。"; L75-85 表C: 阈值 T=1500, 四档 1500/1800/2000/2150, 末档 x0.005
- 代码实际: docs/JobFramework_Shared_Foundation_DesignSpec.md:76 宣称 2000 系表(0-2000 x1.0 / 2000-2800 x0.4 / 2800-3400 x0.2 / 3400-3800 x0.08 / 3800+ x0.02)是"**唯一数据源**", 同文件 :147 更明确要求 FarmingXP "衰减表对齐统一 2000 系"。两处与 FarmingXP L3 的"每日衰减表仍有效"正面冲突。代码实际站 FarmingXP 表C: 农夫注册了自己的 XP 策略, 走 1500/1800/2000/2150 + 0.005。更危险的是代码注释站在相反一侧, 声称农夫表C已作废。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerXpCurve.java:87-90 `private static final long[] BOUNDS = {1_500L, 1_800L, 2_000L, 2_150L}; private static final double[] MULTIPLIERS = {1.0D, 0.30D, 0.10D, 0.03D}; private static final double DRIP_MULTIPLIER = 0.005D; public static final long DAILY_SOFTCAP = 2_150L;`; src/main/java/com/miningdim/job/farmer/FarmerModule.java:30 `JobXpPolicies.register(JobId.FARMER, FarmerXpCurve.POLICY);` (JobXpPolicies.java:68 只有未注册的职业才落回 JobXpCurve 默认); 反向证据: src/main/java/com/miningdim/job/farmer/FarmerConstants.java:7-11 "等级经验曲线 (表A) 与每日有效经验软上限衰减 (表C) 一律以 JobXpCurve 为唯一真源 (2000 系衰减...)。这消解了 FarmingXP 表C (T=1500 四档) 与共享地基 2000 系表的 Critical 冲突 (取共享地基为准)" —— 与它自己所在模块的注册行相反
- 建议改法: 以运行代码为准裁决: 农夫的每日衰减就是表C(1500 系), 已由 FarmerModule.java:30 注册、FarmerGameTests.java:93-135 断言并随质量门验证生效。 1) 在 FarmingXP_Mod_DesignSpec.md L3 SUPERSEDED 注记后追加一句: "衰减表冲突已裁决: 农夫经 FarmerXpCurve 注册独立 XP 策略(job/JobXpPolicies.java 的按职业覆盖路由), 表C(T=1500) 为农夫的实际生效表; 共享地基 2000 系表(job/JobXpCurve.java)只是未注册独立策略职业的默认值。" 2) 修正 JobFramework_Shared_Foundation_DesignSpec.md:76 的"唯一数据源"措辞为"默认数据源(职业可经 job/JobXpPolicies.java 的 register 接口登记独立策略覆盖, 农夫已如此做)", 并删掉 :147 表格中"衰减表对齐统一 2000 系"这条已被现有实现推翻的动作项; 同章顺带补一段说明 JobXpPolicies 覆盖机制的存在(register/policy 路由, 见 JobXpPolicies.java:38-69), 否则"唯一数据源"这个措辞今后还会被人重复误读。 3) 顺带报告代码侧自相矛盾, 需一并改口: FarmerConstants.java:7-11 与 FarmerWheatBuyback.java:6-7 的注释都声称农夫经验侧衰减已统一到 JobXpCurve 的 2000 系, 与同模块 FarmerModule.java:30 的实际注册行为相反; 若后人按这两处注释误删 FarmerXpCurve.java 或撤销该注册, 会直接废掉 FarmerGameTests.java 里已验证通过的表C 断言, 并使 FarmingXP_Mod_DesignSpec.md 第六节的 30 天毕业推演(基于 1500 系数值)整体失真。 (引用修正: FarmerXpCurve.java 的 BOUNDS/MULTIPLIERS/DRIP_MULTIPLIER/DAILY_SOFTCAP 实际位于该文件 7-10 行, 而非原发现所写的 87-90 行, 该文件总长仅 53 行。)

### `docs/Miner_Job_DesignSpec.md`

**[Critical] [A-文档与代码不符] 连锁/隧道硬白名单与高价矿排除已被代码废除**

- 位置: 第 46, 47, 106, 170 行
- 文档原文: L46: "**代码级硬白名单**(石/深板岩/凝灰岩/花岗岩/煤/铁/铜), 硬排除 `DIAMOND_ORE/DEEPSLATE_DIAMOND_ORE/GOLD_ORE/DEEPSLATE_GOLD_ORE/NETHER_GOLD_ORE/ANCIENT_DEBRIS` + 绿宝石; 连锁遇高价矿停在边界"; L106: "连锁/隧道用**代码级硬白名单**, 物理排除高价矿"; L170 测试断言: "连锁遇钻石矿停在边界"
- 代码实际: 连锁判定早已改成单一谓词, 白名单与高价矿排除两张表被显式删除。现在凡镐类可采、镐档位够、可破坏、无 BlockEntity 的方块都能连锁, 钻石/金/远古残骸/绿宝石全部可以连带, 只靠经济 faucet 封顶收益, 不存在"停在边界"这回事。
- 取证: src/main/java/com/miningdim/job/miner/ChainMiningEngine.java:27-31 "连锁判定 (单一谓词 chainable, 用户 2026-07-12 裁决\"矿洞维度内连锁全放开\", 废除旧枚举白名单 + 高价矿硬排除双表): ... 高价矿 (钻/金/残骸/绿宝石) 用正确档位镐即可连带, 时运照算"; 同文件 :287-292 `return state.is(BlockTags.MINEABLE_WITH_PICKAXE) && tool.isCorrectToolForDrops(state) && state.getDestroySpeed(level, pos) >= 0.0F && level.getBlockEntity(pos) == null;` (谓词中无任何方块白/黑名单)
- 建议改法: 改写第四章表格"防滥用"列(L46、L47)与第十章第1条(L106): 把"代码级硬白名单 + 高价矿硬排除"替换为"2026-07-12 用户裁决: 矿洞维度内连锁全放开, 高价矿可连带; 连锁边界改由 chainable 谓词的四条判据决定(MINEABLE_WITH_PICKAXE 标签 + tool.isCorrectToolForDrops 档位 + getDestroySpeed>=0 可破坏 + 无 BlockEntity); 反通胀改由逐块回放 recordMinedOreDrops 的统一 faucet 封顶单独承担"。同步删掉 L170 的"连锁遇钻石矿停在边界"断言, 换成与 MinerGameTests.java:245 同源的"木镐连钻石因档位不足无掉落, 故不连锁"类断言。

**[Critical] [A-文档与代码不符] 矿脉抗性实际会减苦力怕爆炸伤, 文档红线写零作用**

- 位置: 第 80, 84, 170 行
- 文档原文: L80: "仅\"**陷阱专属来源**\"(落石/陷阱岩浆/陷阱 TNT)减伤 **-10% → -35%** | 对怪/枪/玩家 TNT **零作用**"; L84 护栏: "减伤**只认陷阱专属来源**, 否则即战斗减伤天赋, 直接砍"; L170 测试断言: "矿脉抗性对苦力怕/枪伤零减免"
- 代码实际: 陷阱专属 DamageSource 从未落地, 代码降级成按原版环境伤类型集合识别, 集合里含 DamageTypes.EXPLOSION。苦力怕/床/重生锚的爆炸都落在 EXPLOSION 上, 于是矿洞 region 内苦力怕炸伤被真实减免最多 35% —— 正是文档护栏要砍掉的"战斗减伤天赋"。代码注释自己写明了这一点。
- 取证: src/main/java/com/miningdim/job/miner/MinerSurvival.java:75-76 "// 仅非玩家爆炸 (苦力怕/床/陷阱 TNT); PLAYER_EXPLOSION 排除以守 PvP attrition 红线。" + `|| source.is(DamageTypes.EXPLOSION);`; 接线处 src/main/java/com/miningdim/job/miner/MinerSystem.java:84-88 `if (!inMiningRegion(victim) || !MinerSurvival.isTrapSource(source)) { return 0.0D; } return MinerSkills.trapDamageReduction(minerLevel(victim));`
- 建议改法: 第七章表格 L80 该行改为: "仅陷阱类环境伤来源减伤 -10% → -35%; 当前降级实现按原版环境伤类型集合识别(FALLING_BLOCK/FALLING_STALACTITE/FALLING_ANVIL/LAVA/IN_FIRE/ON_FIRE/HOT_FLOOR/非玩家 EXPLOSION), **已知副作用: 苦力怕/床/重生锚爆炸同样被减伤(MinerGameTests.veinResistTrapOnly 已断言并锁定此行为)**, 排除的仅 PLAYER_EXPLOSION(玩家点燃 TNT)与一切近战/远程/枪伤(MOB_ATTACK/PLAYER_ATTACK/弹射物), 待 trap 子系统暴露专属 DamageSource 后收紧"。L84 护栏措辞同步改为"减伤只认陷阱专属环境伤类型集合(见上), 排除一切战斗向来源"。L170 测试断言描述改成"矿脉抗性对近战/远程/枪伤/玩家 TNT 零减免, 但对苦力怕等非玩家爆炸有减免"，与 MinerGameTests.java:185-213 的真实断言对齐。 另外, 不要新开第十三章条目, 而是直接**收口现有第13章第4条**("矿脉抗性能否精确标记'陷阱专属来源', 否则降级为反应窗")——该条目目前仍标 PENDING, 但代码/测试已经把结论定死为"第三方案: 环境类型集合匹配, 接受苦力怕误伤连带减免", 既不是精确标记也不是文档写的 0.5s 无敌反应窗降级预案(MinerConstants.VEIN_RESIST_FALLBACK_IFRAME_TICKS 全仓零引用, 从未接线)。应把第4条状态改成 DECIDED 并写明最终选择的方案与已知副作用, 而不是保留"待确认"字样, 同时可在条目下追加一句"是否要为苦力怕爆炸单独排除(即真正做到零作用)"作为后续可选的再拍板项。

### `docs/FarmingXP_Mod_DesignSpec.md`

**[Major] [A-文档与代码不符] 原版与 Farmer's Delight 作物也在 mod 耕地上结算, 文档只认 mod 作物**

- 位置: 第 17, 18, 29 行
- 文档原文: L17: "mod 作物只能长在 mod 耕地上"; L18: "仅\"在 mod 耕地上收获成熟 mod 作物\"时结算。"; L29: "经验结算事件: 仅认\"作物方块从成熟态被破坏并掉落\"这一事件"
- 代码实际: 实现远不止 mod 作物: 原版小麦/胡萝卜/马铃薯/甜菜, 以及 Farmer's Delight 的卷心菜/洋葱/番茄(含挂绳番茄)/稻穗, 只要种在 mod 耕地上, 都按档位倍率放大掉落并按同一公式发经验。番茄还多出一条"右键采摘"经验源(PICK_SOURCE), 与"仅认成熟态破坏"这条直接冲突。整个兼容作物体系和第二条经验源在文档里完全不存在。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerHarvests.java:22-32 `PRODUCE_BY_CROP` 含 minecraft:wheat / carrots / potatoes / beetroots 与 farmersdelight:cabbages / onions / tomatoes / tomatoes_on_rope / rice_panicles; src/main/java/com/miningdim/job/farmer/FarmerSystem.java:222-224 `boolean nativeCrop = state.getBlock() instanceof FarmerCropBlock crop && crop.isMaxAge(state); boolean compatibleCrop = FarmerHarvests.isSupportedMatureCrop(state); if (!nativeCrop && !compatibleCrop) { return; }`; 右键采摘经验源 src/main/java/com/miningdim/job/farmer/FarmerSystem.java:292-293 `FarmerExperience.awardPick(player, (long) FarmerConstants.SINGLE_CROP_XP * yield);` 与 src/main/java/com/miningdim/job/farmer/FarmerExperience.java:16-17 `static final ResourceLocation PICK_SOURCE = new ResourceLocation(MiningConstants.MODID, "farmer/pick");`
- 建议改法: 在"二、机制总览"新增"兼容作物"小节: 列出 mod 作物 + 原版四种(小麦/胡萝卜/马铃薯/甜菜, 见 FarmerHarvests.java:23-26) + Farmer's Delight 五种(卷心菜/洋葱/番茄/挂绳番茄/稻穗, 见 FarmerHarvests.java:27-31), 明确判据是"作物正下方(FD 纵向作物经 tierFor 最多向下探 4 格)是 mod 耕地"(FarmerHarvests.java:65-79), 未解锁该档位的玩家退化为基准产量 1(与 loot modifier 同一裁决 F026)。同时把 L29 的经验结算事件由单一条改为两条: (a) 作物成熟态被破坏并掉落(FarmerSystem.java:213-247, HARVEST_SOURCE); (b) Farmer's Delight 番茄右键采摘(FarmerSystem.java:257-296, PICK_SOURCE, FarmerExperience.java:16-17), 两条共用"单作物经验 x 档位产量"公式与同一每日软上限衰减。

**[Major] [C-实现状态标错] 第八节标题仍标"PENDING — 必须现在决策", 但方案4(动态收购衰减曲线)已完整实现并接入 /farmer sell 与 WebUI**

- 位置: 第 177-186 行
- 文档原文: L177: "## 八、经济侧隐患与方案 (PENDING — 必须现在决策)"; L186: "倾向: 方案 4。... 若选 4,收获事件结算时同时算\"经验衰减档\"与\"经济衰减档\",两条曲线独立持久化。"
- 代码实际: 方案4 已经是完整实现: 收购价递减曲线、两条曲线独立持久化、逐株求和跨边界连续、并入全服信用点 faucet 主闸, 一条不缺, 还有 /farmer sell 命令与 WebUI 动作做触发点。把它继续标成"必须现在决策"的 PENDING, 会让人以为经济侧还是空白。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerWheatBuyback.java:9-11 "曲线形态与矿物收购同构 (economy.AbuseGuard.buyPrice ...): price(n) = basePrice * max(floorRatio, decayBase^max(0, n - softCap))" 与 :32-35 `int over = Math.max(0, countSoFar - FarmerConstants.WHEAT_DAILY_SOFTCAP); double decayed = Math.pow(FarmerConstants.WHEAT_DECAY_BASE, over); double ratio = Math.max(FarmerConstants.WHEAT_PRICE_FLOOR_RATIO, decayed);`; 两条曲线分工见 src/main/java/com/miningdim/job/farmer/FarmerWheatSellService.java:35-39 "两条曲线分工 (第八节明示独立): 收购曲线 (FarmerWheatBuyback, 按当日卖出株数) 是边际单价递减; 全服每日信用点 faucet 软上限 (grantDaily ...) 是货币注入天花板"
- 建议改法: 将 docs/FarmingXP_Mod_DesignSpec.md L177 标题改为 "## 八、经济侧隐患与方案 (DECIDED — 方案4 已实现)", 保留四个方向的比较作为决策背景; 在 L186"两条曲线独立持久化"之后补一段落地说明, 指明: 收购衰减曲线实现于 FarmerWheatBuyback(wheatBuyPrice/totalBuyPrice, 逐株求和, 跨 WHEAT_DAILY_SOFTCAP 边界连续); 结算与先扣后发闭环实现于 FarmerWheatSellService.sell(含 SELL_MIN_MASTERY_LEVEL 身份门与 grantDaily 失败回滚 refundWheat); 触发点为 /farmer sell <amount> 命令(FarmerSystem.java)与 WebUI 动作 job.farmer.sell(FarmerWebUiActions.java); 经济衰减(按当日卖出株数, 落于 FarmerSavedData.wheatSoldToday)与经验衰减(按 JobXpCurve 软上限, 落于 JobProgress/dailyXp)两条曲线各自独立持久化互不影响, 并与全服信用点 faucet 主闸(grantDaily + WHEAT_SELL_FAUCET_KEY/DAILY_CREDIT_FAUCET_CAP)构成两层独立闸门。

**[Major] [D-覆盖缺口] 收购曲线全部参数与 faucet 并档规则文档没有**

- 位置: 第 177-186 行
- 文档原文: 第八节方案4 通篇只有定性描述 "NPC 收购单价随当日/全服小麦供给衰减,与经验软上限同构", 没有任何 basePrice / softCap / 衰减底数 / 地板比例 / faucet 键与档值
- 代码实际: 代码里这五个参数全部定死并且都有平衡含义: 基础单价 1 信用点/株, 每日收购软上限 2160 株(= 单块超凡地 24h 满产), 递减底数 0.97, 价格地板 1%(转引经济全局常量), 卖菜与矿工卖矿共用同一个 (playerId, faucetKey) 每日计数器并入同一 60000 档衰减主闸。其中"卖菜与卖矿撞同一主闸"是跨职业的强耦合决策, 文档完全没有, 按文档单独给农夫调价会直接算错全服货币注入量。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerConstants.java:69-75 `public static final long WHEAT_BASE_PRICE = 1L;` / `public static final int WHEAT_DAILY_SOFTCAP = 2160;` / `public static final double WHEAT_DECAY_BASE = 0.97D;`; :83-84 `public static final double WHEAT_PRICE_FLOOR_RATIO = com.miningdim.economy.EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO;`(EconomyConstants.java:68 = 0.01D); :110-123 `WHEAT_SELL_FAUCET_KEY = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY` / `DAILY_CREDIT_FAUCET_CAP = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER` 注释 "与矿工卖矿 (settleOreSale 内部同键) 共用同一 (playerId, faucetKey) 每日累计计数器, 各 faucet 并入同一衰减主闸天花板而非各算独立日上限"
- 建议改法: 第八节追加一张参数表: basePrice=1 CP/株(标注引自 FarmerConstants.WHEAT_BASE_PRICE,注明经济文档 8.6 仍待校准) / softCap=2160 株/日(= 单块超凡地24h满产) / decayBase=0.97 / floorRatio=1%(转引 EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO)。并单开一段写明量纲与并档规则: WHEAT_DAILY_SOFTCAP 是株量纲(喂收购曲线 FarmerWheatBuyback), DAILY_CREDIT_FAUCET_CAP 是 CP 量纲(60000 档, 喂 grantDaily), 两者不可混用; 卖菜与矿工卖矿(settleOreSale)共用全服同一 (playerId, credit_faucet) 每日累计计数器与衰减主闸, 调农夫单价前必须回 Economy_BalanceSheet_DesignSpec.md 过全局净流入。同时应同步更新本文档第 231 行 "第八节经济方案四选一(倾向方案4)" 的 PENDING 标记——该决策已在代码侧落地并有 GameTest 覆盖, 继续标 PENDING 会误导后来者以为方案未定, 存在重复实现或另定数值与线上行为冲突的风险。

**[Major] [D-覆盖缺口] 卖菜精通等级门(level>=2)文档完全没有**

- 位置: 第 18, 177-186, 236-246 行
- 文档原文: L18: "独立经验池: ... 仅\"在 mod 耕地上收获成熟 mod 作物\"时结算。"; 第八节 L184 方案4 只写 "NPC 收购单价随当日/全服小麦供给衰减"; L236-246 实现期工作分解第 5 条只写 "(若选经济方案 4)经济衰减档计算与持久化" —— 全文无任何售卖等级门
- 代码实际: 代码在 service 层加了一道反洗钱身份门: 农夫精通等级 < 2 的玩家一株都卖不掉, 不扣物品不发币直接返回 masteryDenied。这是堵跨账号洗额度漏洞的关键闸门, 文档零记载; 按文档做平衡推演或写玩家说明都会漏掉"L1 不能卖菜"这个硬事实。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerConstants.java:97 `public static final int SELL_MIN_MASTERY_LEVEL = 2;` (:86-95 注释: "出售 mod 小麦所需的最低农夫精通等级 (反洗钱身份门, 堵 economy-laundering-vulnerability 的农夫实例) ... level>=2 即 '确实练过农夫、升过至少一级', 排除从没种过地、纯靠 /give 或跨账号交易/复制拿到小麦就直接套现的白板小号 (L1 默认态)"); 命令层反馈 src/main/java/com/miningdim/job/farmer/FarmerSystem.java:134-137 `if (result.belowMastery()) { ctx.getSource().sendFailure(Component.literal("You must reach farmer mastery level " + FarmerConstants.SELL_MIN_MASTERY_LEVEL + " to sell wheat; nothing was sold."));`
- 建议改法: 在第八节方案4 下新增一条: "售卖身份门(已实现): 卖出 mod 小麦要求农夫精通等级 >= `FarmerConstants.SELL_MIN_MASTERY_LEVEL`(当前 2), 未达门槛不扣物品不发币(`SellResult.masteryDenied()`)。判据在 `FarmerWheatSellService.sell` 的 service 层(非命令层), 保证未来 GUI/网络包直调也过同一门; 命令层 `FarmerSystem` 仅转述该结果做玩家提示。诚实局限: 玩家真升到 L2 后仍可倒卖交易来的小麦, 非结构级杜绝。"并在"实现期工作分解"追加第 7 条"售卖等级门"。可顺带在文末补一句交叉引用: 该常量同时被 docs/Economy_BalanceSheet_DesignSpec.md 与 docs/Ore_Fish_And_Soup.md 引用为渔夫职业的对比参照, 便于读者知晓其余职业尚未跟进同类身份门。

**[Major] [E-状态过期] 第十节自动收割 PENDING 不可直接改写为"已由 FakePlayer 统一剥夺倍率"——排除仅覆盖 loot modifier 路径,本 mod 原生作物 farmer_crop 的 onCropHarvested 路径未排除 FakePlayer**

- 位置: 第 199-203 行
- 文档原文: L202: "自动收割是否允许由你定;无论是否允许,每日软上限已封顶单日经验,自动农场只省手不增经验。建议至少对 mod 作物禁用观察者自动机以保留手动手感。PENDING。"
- 代码实际: 已有明确裁决并落码: 自动机不被物理禁止, 但通过 FakePlayer 伪装成玩家驱动的收割拿不到任何档位倍率, 掉落退化为基准值。代码注释把理由写得很清楚("自动化模组正是借它伪装成玩家来驱动收割, 放行即等于把增产直接送给机器农场")。这条 PENDING 已无待办。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerHarvestLootModifier.java:43-49 "FakePlayer 必须单独排除: 它是 ServerPlayer 的子类, 且 getType() 同样返回 minecraft:player, 因此 instanceof 与 json 里的 entity_properties 条件都拦不住它。自动化模组正是借它伪装成玩家来驱动收割, 放行即等于把增产直接送给机器农场。" + `if (!(harvester instanceof ServerPlayer player) || harvester instanceof FakePlayer) { return generatedLoot; }`
- 建议改法: 不要把 L202 简单替换成"自动机收到的是基准产量 1……手动收割的价值由此保住"这类一刀切表述。应先修代码再改文档,或如实拆分现状: 1) 代码侧: 在 FarmerSystem.java:218 的判断上补齐与 FarmerHarvestLootModifier.java:47 同款的排除,例如改为 `if (!(event.getPlayer() instanceof ServerPlayer player) || player instanceof net.minecraftforge.common.util.FakePlayer) { return; }`,并补一条对应 GameTest(用 FakePlayer 破坏成熟 farmer_crop,断言产量退化为基准值且不结算经验,仿照 FarmerGameTests.java:1237-1249 但走原生分支)。 2) 文档侧: 代码补齐后,再把 L202 改为 DECIDED,并如实区分两条产出路径,例如:"自动收割不物理禁止,但经 FakePlayer 判据剥夺档位倍率——无论是受 farmer 耕地加成的原版作物/Farmer's Delight 作物(FarmerHarvestLootModifier)还是本 mod 自己的原生作物 farmer_crop(FarmerSystem.onCropHarvested),自动机拿到的都是基准产量 1,不享 2/3/4/5/6 倍放大,手动收割的价值由此保住。" 在代码补齐之前,L202 应保留 PENDING,或至少注明"loot modifier 路径已排除 FakePlayer,原生作物 onCropHarvested 路径尚未排除,存在自动化套利口子待修"。

**[Major] [E-状态过期] 第十一节 A/D 两组 PENDING 早已全部拍板**

- 位置: 第 207-232 行
- 文档原文: L209: "按架构先行原则,以下三类共需先决策,否则无法开始编码"; L211-214 "### A. 技术栈与版本 (PENDING) - ModLoader: Forge / NeoForge / Fabric。- MC 版本号。"; L227-232 "### D. 数值层遗留决策 (PENDING)" 下四条(表B 主/替代、末档 x0.005 vs x0、第八节四选一、自动机是否禁用)
- 代码实际: A 早已由本文档自己的 SUPERSEDED 注记(L3 "平台锁 1.20.1 / Forge")推翻, 与代码一致; D 四条在代码里逐条有答案: 表B 主方案(FarmerTier 五档 10/8/6/5/4 分 + 产量 2/3/4/5/6, 单作物经验固定 2)、末档取 x0.005 不硬归零、第八节取方案4、自动机经 FakePlayer 排除。整节"否则无法开始编码"的阻塞措辞与"编码早已完成"的现实完全脱节。
- 取证: 表B 主方案: src/main/java/com/miningdim/job/farmer/FarmerTier.java:16-29 `LOW("low", 1, 10, 2), MEDIUM("medium", 3, 8, 3), HIGH("high", 5, 6, 4), PREMIUM("premium", 7, 5, 5), SUPREME("supreme", 9, 4, 6);` + FarmerConstants.java:24 `SINGLE_CROP_XP = 2`; 末档取 0.005: src/main/java/com/miningdim/job/farmer/FarmerXpCurve.java:89 `private static final double DRIP_MULTIPLIER = 0.005D;`; 自动机: src/main/java/com/miningdim/job/farmer/FarmerHarvestLootModifier.java:47 `if (!(harvester instanceof ServerPlayer player) || harvester instanceof FakePlayer) { return generatedLoot; }`
- 建议改法: A 段(L211-214)直接删除(已由 L3 SUPERSEDED 注记覆盖)或改写为"A. 技术栈与版本 (DECIDED): Forge 1.20.1 / Java 17"。D 段(L227-232)五条(不是四条,补上遗漏的"表B档位间距是否再调")逐条改 DECIDED 并写明代码落点: 表B主方案与档位间距均取默认/不调(落点 FarmerTier.java:16-29 五档常量已固化)、表C末档取 x0.005 非硬归零(落点 FarmerXpCurve.java 第9行 DRIP_MULTIPLIER,注意不是第89行)、第八节经济取方案4(落点 FarmerWheatBuyback.java 收购曲线)、自动机不整体禁用但 FakePlayer 被排除不享档位倍率(落点 FarmerHarvestLootModifier.java:47)。L209 抬头"否则无法开始编码"改为"以下决策的最终落点(编码已完成)"。

**[Major] [A-文档与代码不符] 每日清零时区文档建议 04:00, 实现是 UTC 自然日**

- 位置: 第 223-225 行
- 文档原文: L223-225: "### C. 上限触发反馈与时区 (PENDING) - 触顶后是\"经验显示为 0 灰字提示\"还是\"actionbar 提示进入衰减档\"。- 每日清零时区与具体时刻(建议 04:00)。"
- 代码实际: 时区与时刻已定死: 全链路一律 UTC 自然日翻日(epochDay), 不是建议的 04:00, 而且这是农夫卖菜计数、职业经验软上限、经济信用点 faucet 三处必须一致的口径, 改一处就要同步三处。照文档按 04:00 实现会把三处口径打散。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerClock.java:45-52 "UTC 翻日时钟 (JobFramework_Shared_Foundation_DesignSpec 第四章)。农夫卖菜每日收购计数 (经济衰减曲线) 必须与职业经验每日软上限共用同一 UTC 翻日口径 (epochDay = Instant.now().atZone(UTC).toLocalDate().toEpochDay())。... 三处口径必须一致 (UTC epochDay), 任一改动须同步" 与 :60-62 `public static long currentUtcDayStamp() { return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(); }`; 框架侧同口径见 src/main/java/com/miningdim/job/JobXpCurve.java:144 "UTC 翻日重置"
- 建议改法: C 段拆成两条: "时区与时刻(DECIDED): UTC 自然日 epochDay 翻日, 由 FarmerClock.currentUtcDayStamp 提供; 与 JobServiceImpl.currentUtcDayStamp(职业经验翻日)、economy.AbuseGuard.currentPlayerDayStamp(信用点 faucet 翻日)三处同口径, 改一处须同步三处; 同一表达式还被 agent/munitions/tarot 等职业时钟复用, 属跨模块既定架构决策。原'建议 04:00'作废。" + "触顶反馈(仍 PENDING): 经验显示为 0 灰字提示 / actionbar 提示进入衰减档 二选一未定。"

**[Major] [D-覆盖缺口] 农夫 F025 迁移对账命令 /farmer admin legacy|recount 全仓文档零记录(非 FarmingXP_Mod_DesignSpec.md 一份文档的问题, crops/sell 已在 docs/modules/farmer/README.md 登记)**

- 位置: 第 236-247 行
- 文档原文: L236-246 "实现期工作分解"六条里只有方块注册、收获结算、衰减与清零、等级上限校验、经济衰减档、反作弊钩子, 无任何命令行入口; 全文也没出现 /farmer
- 代码实际: 农夫包自注册了一整条 /farmer 命令根: crops(查五档耕地表)、sell <amount>(卖菜唯一运行期触发点)、admin legacy|recount <target>(OP 2, F025 迁移口径的对账与重算入口)。其中 admin 两条是运营侧必须知道的对账工具, 文档一条没写。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerSystem.java:82-96 `Commands.literal("farmer").then(Commands.literal("crops").executes(this::cropTableCommand)).then(Commands.literal("sell").then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(this::sellCommand))).then(Commands.literal("admin").requires(src -> src.hasPermission(OP_LEVEL)).then(Commands.literal("legacy")...).then(Commands.literal("recount")...))`; 注册说明见同文件 :44-50 "/farmer admin legacy|recount <target> (OP 2), F025 迁移口径修正的唯一对账/重算入口"
- 建议改法: 不要在已被 SUPERSEDED 的 docs/FarmingXP_Mod_DesignSpec.md 里补命令章节; 应在 docs/modules/farmer/README.md 第 18 行"`/farmer crops` 与 `/farmer sell <amount>` 命令"之后, 补一条"`/farmer admin legacy <target>`(OP>=2, 只读查询目标玩家的迁移遗留占用/总占用/当前等级封顶)与 `/farmer admin recount <target>`(OP>=2, 清零该玩家的迁移遗留占用, 是 F025 迁移口径修正后唯一的对账/清零入口, 见 FarmerSystem.java:157-192、FarmerSavedData#legacyOverflow/#clearLegacyOverflow)"。同时建议在 docs/Full_Repo_Audit_2026-08.md 的 F025 条目下补一句复核后记, 说明该缺陷已有 legacy/recount 对账命令作为运营侧补救手段, 避免读者以为该 Major 缺陷仍完全无解。

### `docs/Miner_Job_DesignSpec.md`

**[Major] [D-覆盖缺口] 矿工网络架构文档失真:声称复用 MiningNetwork 实为独立频道 MinerNetwork,且按住连锁交互机制完全未记载**

- 位置: 第 116 行
- 文档原文: L116: "开关用 `KeyMapping` + C2S(复用 `MiningNetwork`/`SelectZoneC2S` 范式); 探矿/陷阱高亮**新建一个 S2C 包**(现仅 4 个)+ 客户端 `RenderLevelStageEvent` 画轮廓。"
- 代码实际: 实现没有复用 MiningNetwork, 而是另起一条矿工专用频道 MinerNetwork, 下挂 6 个包(3 个 C2S + 3 个 S2C), 不是文档说的"新建一个 S2C 包"。连锁还多出一整套文档完全没有的"按住激活 + 预览高亮"交互: 客户端按住期间每 20 tick 发一次 hold 心跳续期, 服务端 30 tick 宽限过期, 预览高亮 15 tick 自清。
- 取证: src/main/java/com/miningdim/job/miner/network/ 目录下: MinerChainHoldC2S.java / MinerChainPreviewC2S.java / MinerChainPreviewS2C.java / MinerHighlightS2C.java / MinerNetwork.java / MinerStatusS2C.java / MinerToggleC2S.java; 注册点 src/main/java/com/miningdim/job/miner/MinerSystem.java:90-91 `modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(MinerNetwork::register));`; 按住连锁常量 src/main/java/com/miningdim/job/miner/MinerConstants.java:68-80 `CHAIN_HOLD_GRACE_TICKS = 30; CHAIN_HOLD_HEARTBEAT_TICKS = 20; CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS = 10; CHAIN_PREVIEW_EXPIRE_TICKS = 15;`
- 建议改法: docs/Miner_Job_DesignSpec.md 第 116 行改为: "矿工自建独立 SimpleChannel `MinerNetwork`(miningdim:miner, discriminator 独立自增, 不复用 `MiningNetwork`/`SelectZoneC2S`); C2S = `MinerToggleC2S`(开关/主动技能)/`MinerChainHoldC2S`(按住续期心跳)/`MinerChainPreviewC2S`(预览请求), S2C = `MinerHighlightS2C`(探矿/陷阱高亮)/`MinerStatusS2C`(状态 HUD)/`MinerChainPreviewS2C`(连锁预览高亮); 客户端 `RenderLevelStageEvent` 画轮廓"。并在第十一章或新增小节补充"按住激活"交互机制: 客户端按住期间每 `CHAIN_HOLD_HEARTBEAT_TICKS`(20 tick)发一次 hold=true 心跳续期, 服务端按 `CHAIN_HOLD_GRACE_TICKS`(30 tick)给宽限过期; 连锁预览请求节流 `CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS`(10 tick), 预览高亮存活 `CHAIN_PREVIEW_EXPIRE_TICKS`(15 tick)后自清; 状态 HUD 按 `HUD_STATUS_PUSH_INTERVAL_TICKS`(10 tick)节流推送。同时应在文档中说明"另开 channel 而非复用 MiningNetwork"的架构决策理由(避免中央 register 追加 discriminator 的集成耦合), 以免与既有"复用"表述冲突误导读者。

**[Major] [A-文档与代码不符] "全数值进 config, 硬编码即缺陷"与实现现状相反(未反映阶段性延期决策)**

- 位置: 第 123 行
- 文档原文: L123: "全数值进 config(`MiningServerConfig`), 硬编码即缺陷。"
- 代码实际: 矿工全部数值刻意没有进 MiningServerConfig, 而是集中在 MinerConstants 这个常量类里, 代码开头用整段注释说明了为何这样做并把"迁入 MiningServerConfig"列为集成阶段待办。照文档字面判定, 现有实现整体"即缺陷", 会误导后来者把一个已被明确记录的阶段性决策当成 bug 去改。
- 取证: src/main/java/com/miningdim/job/miner/MinerConstants.java:6-10 "为何落在本子系统而非中央 com.miningdim.config.MiningServerConfig: ... 中央配置门面 com.miningdim.core.IMiningConfig 为阶段0 定稿, 不暴露 miner.* 键; 在 ConfigSystem 把 miner.* 接入 ForgeConfigSpec 之前, 本类是矿工各能力生效所必需的真实数值 (非占位)。集成阶段把这些初值搬进 MiningServerConfig 后, MinerSkills 改读配置门面"
- 建议改法: L123 改为: "数值最终目标是进 config(`MiningServerConfig`); 当前阶段因 `IMiningConfig` 门面未暴露 `miner.*` 键(阶段0 已定稿, 详见 `MinerConstants` 类注释), 全部数值集中在 `MinerConstants` 单一常量类, 由 job.miner 包内多处(`MinerSkills`/`MinerLevelGate`/`MinerActions`/`ChainMiningEngine`/`MinerSystem` 等)直接读取; 集成阶段把这些初值搬进 MiningServerConfig 后再统一改读配置门面。散落在 MinerConstants 之外的裸值仍视为缺陷。"

**[Major] [E-状态过期] 第十二章"矿工等级尚不存在"的前置事实已完全过期**

- 位置: 第 142 行
- 文档原文: L142: "前置事实(已核验): 矿工等级/`EnumMap<JobId,JobProgress>` 代码里**尚不存在**(仅设计文档), 多个技能(减 danger/门控)的前提是先落地 job 等级。"
- 代码实际: EnumMap<JobId,JobProgress> 早已落地并挂在 entry capability 下; 减 danger 与难度门控这两个被点名"前提未满足"的技能也都已接线生效。按这行做规划会误判整个矿工职业还没有地基。
- 取证: src/main/java/com/miningdim/job/JobData.java:24 `private final Map<JobId, JobProgress> progress = new EnumMap<>(JobId.class);`; 难度门控已接: src/main/java/com/miningdim/entry/EntryGateway.java:316-320 `private GateResult gateCheck(ServerPlayer player, Difficulty difficulty, long entryFee) { int minerLevel = JobServices.jobService().level(player, JobId.MINER); if (!MinerLevelGate.canEnter(minerLevel, difficulty)) { return GateResult.LEVEL_TOO_LOW; }`; 减 danger 已接: src/main/java/com/miningdim/job/miner/MinerSystem.java:106 `player -> (float) MinerSkills.dangerTimeFactor(minerLevel(player)));` 对应 src/main/java/com/miningdim/pressure/Danger.java:167,172 `float timeAccrueFactor` / `int accrue = Math.round(TWIN_ACCRUE_PER_EVAL * timeAccrueFactor);`
- 建议改法: 整行删除或改写为: "前置已落地(截至 bd0c4588): JobData 持 EnumMap<JobId,JobProgress> 并挂在 entry capability(MiningPlayerData.jobData)下序列化; EntryGateway.gateCheck 已委派 MinerLevelGate 读矿工等级实现难度门控; MinerSystem.onServerStarted 已将 MinerSkills.dangerTimeFactor 绑入 DangerJobFactor, Danger.evaluate 的 timeAccrueFactor 入参已生效。"同时应联动核查第十三章(PENDING)与第十四章(工作分解)中依赖同一过期前提的条目(如158行"前置(与职业共享): entry EnumMap<JobId,JobProgress>(落地矿工等级)"、167行"难度门控: EntryGateway.gateCheck 改读矿工等级(L4/L8)"), 避免读者按 PENDING/待分解误判为未完成。

**[Major] [C-实现状态标错] 第十三章 PENDING 清单里三项已在代码拍板落地**

- 位置: 第 146-152 行
- 文档原文: L146 "## 十三、待确认实现项 (PENDING)" 下 L148: "config 数值标定: 各技能成长曲线/CD/充能/时运掉率/danger 系数封底。"; L149: "隐藏软上限的透明度取舍最终确认"; L151: "矿脉抗性能否精确标记\"陷阱专属来源\", 否则降级为反应窗。"
- 代码实际: 三项都已有确定答案: (1) 全部成长曲线/CD/充能/时运/danger 封底已在 MinerConstants 定稿并被 MinerSkills 逐项插值使用; (2) 隐藏软上限已按"全无形"执行, ECONOMY_SOFTCAP 文案常量与 lang 词条随设计删除; (3) 陷阱专属来源做不到, 但既没有精确标记也没走"反应窗"降级, 而是走了第三条路(环境伤类型集合 + 正常减伤)。
- 取证: (1) src/main/java/com/miningdim/job/miner/MinerConstants.java:82-88 `CHAIN_POOL_AT_UNLOCK = 16; CHAIN_POOL_AT_MAX = 48; CHAIN_REFILL_FULL_TICKS_AT_UNLOCK = 5 * 60 * TICKS_PER_SECOND;` 等全表; (2) src/main/java/com/miningdim/error/MiningMessages.java:51 "// 仅在收购价 (settleOreSale) 无形递减体现; 原 ECONOMY_SOFTCAP 文案常量与 lang 词条随此设计删除。"(全库 grep ECONOMY_SOFTCAP 仅此一条注释); (3) src/main/java/com/miningdim/job/miner/MinerSurvival.java:64-77 `isTrapSource` 按 DamageTypes 集合判定, 而 MinerConstants.java:145 `VEIN_RESIST_FALLBACK_IFRAME_TICKS = 10` 全库无任何引用(反应窗方案未实现)
- 建议改法: 把第 1/2/4 三项从 PENDING 移出, 改标 DECIDED 并写明落点: "1 DECIDED: 数值定稿于 MinerConstants(集成期再迁 MiningServerConfig), 逐级值由 MinerSkills.lerpUnlockToMax 统一解析; 2 DECIDED: 取全无形, MiningErrors/MiningMessages 的 ECONOMY_SOFTCAP 常量及其 lang 词条已删(仅剩说明性注释 MiningMessages.java:50-51), 与第六章原文一致; 4 DECIDED(降级为第三条路径而非反应窗): trap 子系统未暴露专属 DamageSource, MinerSurvival.isTrapSource 按环境伤类型集合(落石/钟乳石/铁砧/岩浆/着火/炽热地面/非玩家爆炸)识别, 经 MinerSystem 接入 PlayerDamageReduction 走正常百分比减伤(封顶 35%), MinerConstants.VEIN_RESIST_FALLBACK_IFRAME_TICKS 是未被任何代码引用的遗留常量, 应连带清理或补注释说明其为废弃占位"。第 3 项(矿商门路)与第 5 项(时运口径对非矿工影响复核)未见任何代码实现或落地结论, 应保留 PENDING。

**[Major] [D-覆盖缺口] 探矿里程碑漏掉七种能源矿, 文档只列原版三档**

- 位置: 第 37 行
- 文档原文: L37 离散里程碑列: "可探矿种: L3 铁/煤 → L6 +钻 → L8 +金/残骸"
- 代码实际: 代码的可探矿种集合是 L3 铁/煤/铝土, L6 +钻/硼砂/锡/银, L8 +金/残骸/镍/铬/钨 —— 电力子系统那套 PowerMineral 矿(铝土/硼砂/锡/银/镍/铬/钨)全部在里面, 文档一个都没提。按文档给玩家写说明或做数值评估会直接漏掉七种矿的探测收益。
- 取证: src/main/java/com/miningdim/job/miner/OreScanService.java:47-61 `set.add(OreType.IRON); set.add(OreType.COAL); set.add(OreType.BAUXITE); if (level >= MinerConstants.ORE_SCAN_DIAMOND_LEVEL) { set.add(OreType.DIAMOND); set.add(OreType.BORAX); set.add(OreType.TIN); set.add(OreType.SILVER); } if (level >= MinerConstants.ORE_SCAN_GOLD_DEBRIS_LEVEL) { set.add(OreType.GOLD); set.add(OreType.ANCIENT_DEBRIS); set.add(OreType.NICKEL); set.add(OreType.CHROMIUM); set.add(OreType.TUNGSTEN); }`; 矿种定义见 src/main/java/com/miningdim/ore/OreType.java:48-54 (BAUXITE/BORAX/SILVER/TIN/NICKEL/CHROMIUM/TUNGSTEN 均走 PowerMineral)
- 建议改法: L37 离散里程碑改为"L3 铁/煤/铝土 → L6 +钻/硼砂/锡/银 → L8 +金/残骸/镍/铬/钨", 并加一句"能源矿(铝土/硼砂/锡/银/镍/铬/钨)随电力子系统接入探测, 分档依据同价值量级"。

**[Major] [A-文档与代码不符] 矿物探测数据源已从 cachedPlacement 改扫真实世界**

- 位置: 第 37, 135 行
- 文档原文: L37: "服务端权威 `OreSystem.cachedPlacement`+`OrePlacement.oreAt`, 只下发球内确有该矿的坐标"; L135 可实现性表: "矿物探测 | 可实现 | `OreSystem.cachedPlacement`/`OrePlacement.oreAt` + 新 S2C + `RenderLevelStageEvent`"
- 代码实际: cachedPlacement 路线已被判定为死代码并废弃: 维度改 minecraft:noise 生成 + 原版 ore feature 后没有任何生产调用方填表, 该缓存恒空, 探矿技能事实失效。现实现改为在 ServerPlayer.serverLevel() 上逐 BlockPos 读真实方块态, 经 OreType.fromBlock 还原矿种。
- 取证: src/main/java/com/miningdim/job/miner/OreScanService.java:20-27 "经 MiningServices.instanceManager() 取玩家所在实例 ... 再在 ServerPlayer.serverLevel() 上对探测球内逐 BlockPos 读真实方块态, 经 OreType.fromBlock 把命中的矿石方块还原为矿种" / "改扫真实世界缘由 (经济文档第十章 H): 旧实现读 com.miningdim.ore.OreSystem.cachedPlacement 死体素表 ... cachedPlacement 恒空 -> scan 永远空返, 探矿技能事实失效"
- 建议改法: 将 Miner_Job_DesignSpec.md 第 37 行与第 135 行两处的"服务端权威 OreSystem.cachedPlacement/OrePlacement.oreAt"统一改为"服务端权威 ServerPlayer.serverLevel() 逐 BlockPos 读真实方块态 + OreType.fromBlock 还原矿种(见 OreScanService.scanWorldDetailed); 旧 cachedPlacement/OrePlacement.oreAt 因维度改用 minecraft:noise 生成、离线体素铺矿管线已判废删除而恒空, 已废弃"。"只下发球内确有该矿的坐标"一句仍成立应保留。同时建议在 docs/Ore_Pricing_Ledger_DesignSpec.md 第 161 行"探矿技能当前事实失效"处补一句说明该问题已在 OreScanService 改实现后修复, 避免读者以为探矿仍失效。

**[Major] [A-文档与代码不符] 矿脉抗性降级路径文档写"0.5s无敌反应窗"，实现实为"环境伤类型近似识别+百分比减伤"，二者效果不同且遗留死常量**

- 位置: 第 80 行
- 文档原文: L80 守红线列: "做不到精确区分则降级为\"陷阱触发时给 0.5s 无敌反应窗\""
- 代码实际: 精确区分确实做不到(trap 子系统没有暴露专属 DamageSource), 但实现没有走文档给的反应窗降级, 而是按原版环境伤类型集合识别后照常做百分比减伤。文档给的反应窗常量被建出来后从未被任何代码引用, 成了死常量。
- 取证: src/main/java/com/miningdim/job/miner/MinerSurvival.java:15-19 "陷阱专属来源判定 (第七章): 理想路径是 trap 子系统暴露 TrapDamageSources 专属 DamageSource ... 当前 trap 子系统未提供该专属源 ... 第七章给出的降级路径: 专属源缺失时按这些环境伤类型集合识别陷阱伤"; src/main/java/com/miningdim/job/miner/MinerConstants.java:144-145 "/** 无法精确区分陷阱专属来源时的降级反应窗时长 (~0.5s)。 */ public static final int VEIN_RESIST_FALLBACK_IFRAME_TICKS = 10;" —— 全库 grep 该标识符只有这一处声明, 零引用
- 建议改法: 将 docs/Miner_Job_DesignSpec.md 第80行守红线列中"做不到精确区分则降级为\"陷阱触发时给 0.5s 无敌反应窗\""改写为"做不到精确区分则降级为: 按原版环境伤类型集合(FALLING_BLOCK/FALLING_STALACTITE/FALLING_ANVIL/LAVA/IN_FIRE/ON_FIRE/HOT_FLOOR/非玩家EXPLOSION)识别陷阱伤, 仍按矿脉抗性百分比(-10%→-35%)折减伤害; 0.5s无敌反应窗方案已放弃未落地"；并在同一改动中对 src/main/java/com/miningdim/job/miner/MinerConstants.java:144-145 的 VEIN_RESIST_FALLBACK_IFRAME_TICKS 做处理：要么删除该死常量，要么在其 Javadoc 上补一行"已废弃, 未被任何代码引用, 保留仅供历史追溯"以免后续开发者误用。

### `docs/design_mindmap.md`

**[Major] [B-文档互相打架] 思维导图"死亡不掉落"与已落地的 HARD 强制掉落冲突**

- 位置: 第 14 行
- 文档原文: - 进入与回退:EntryGateway,死亡不掉落回退进入前坐标
- 代码实际: HARD 难度矿区已经是死亡掉落全部物品: MiningDeathRules 在 LivingDropsEvent 里把整个 Inventory 移进掉落集合并清空槽位, 进入 HARD 时还会额外推一条红色警告"本区死亡掉落全部物品"。只有 EASY/MEDIUM 仍沿用全局 keepInventory 的不掉落。较新的 TaskSpec_Mining_DeathRules.md:68-72 的难度表已明确写 HARD=是, 思维导图这一行仍停在旧口径。
- 取证: src/main/java/com/miningdim/rules/MiningDeathRules.java:44-46 `if (instance == null || instance.difficulty() != Difficulty.HARD) { return; }`; 同文件:48-61 遍历 `inventory.removeItemNoUpdate(slot)` 后 `event.getDrops().add(new ItemEntity(...))`; src/main/resources/assets/miningdim/lang/zh_cn.json:88 "message.miningdim.enter.hard_death_drops": "警告：本区死亡掉落全部物品。"
- 建议改法: docs/design_mindmap.md 第 14 行改为: "- 进入与回退:EntryGateway;复活点恒为进入前坐标;死亡物品规则按难度分档 —— EASY/MEDIUM 不掉落, HARD 强制掉落全部背包物品(`rules/MiningDeathRules`, 进入时红字明示)"。第 235 行"平衡基准:80 血 / TACZ / 死亡不掉落"同理改为注明该基准仅覆盖 EASY/MEDIUM,HARD 另计(引用 docs/TaskSpec_Mining_DeathRules.md 第 68-72 行难度表作为权威口径)。

**[Major] [D-覆盖缺口] 脑图缺渔夫、电力、任务三个已落地的一级模块**

- 位置: 第 194-227 行
- 文档原文: L194 "## 系统"下只有三节: L196 "### 精英怪星级词条系统"、L211 "### 经济系统"、L220 "### 结婚系统"; L16 "## 职业"下六节, 无渔夫
- 代码实际: 仓库里三个规模不小的一级模块在脑图上完全不存在: 渔夫/钓鱼(job/fisher, 含图鉴 journal、矿物鱼 ore、鱼汤 soup 三个子包)、电力系统(power, 含 cable/generator/grid/machine/mineral/rubber/storage/endgame 等十余个子包)、任务系统(quest, 含日常/周常/特殊/隐藏四类任务板与奖励发放)。三者都已在 MiningDim 里作为 Subsystem 注册, 不是草稿。脑图自称"结构总览", 漏掉三个一级模块就失去了总览价值。
- 取证: src/main/java/com/miningdim/MiningDim.java:113 `subsystems.add(new com.miningdim.power.PowerSystem());`、:155 `subsystems.add(new com.miningdim.job.fisher.FishingSystem());`、:185 `subsystems.add(new com.miningdim.quest.QuestSystem());`; 规模佐证: src/main/java/com/miningdim/quest/ 下 QuestBoard/QuestChain/QuestPool/QuestRewards/QuestService 等 28 个文件, src/main/java/com/miningdim/power/ 下 cable/generator/grid/machine/mineral/rubber/storage/endgame 等 13 个子包, src/main/java/com/miningdim/job/fisher/ 下 journal/ore/soup 三个子包
- 建议改法: "## 职业"下补 "### 渔夫(钓鱼 · 经济向)"节点(捕获即结算 + 鱼类图鉴 journal + 矿物鱼 ore + 鱼汤 soup 效果, 接 Tide, 对应 job/fisher 三子包与 MiningDim.java:155 的 FishingSystem 注册); "## 系统"下补 "### 电力系统"(储能走 Flux / 自研多级线缆 cable / 多燃料发电 generator / grid 输配 / machine 用电设备 / mineral 电力矿物 / rubber 橡胶产线 / storage 储能 / endgame 后期内容, 对应 power/ 十余子包与 MiningDim.java:113 的 PowerSystem 注册)与 "### 任务系统"(日常/周常/特殊/隐藏四类共用单一任务板 QuestBoard、QuestPool/QuestChain 派发、QuestRewards 发奖走共享 credit_faucet 键, 悬赏将作第五来源 BOUNTY 并入, 对应 quest/ 28个文件与 MiningDim.java:185 的 QuestSystem 注册)两节。补充说明: 核对时发现"## 职业"下实际现有7个二级节点(矿工/农夫/千年工程师/塔罗师/厨师/特勤干员/军火商), 而非原发现引文摘要中的"六节", 修复时一并注意计数, 不影响本条缺口本身。

**[Major] [D-覆盖缺口] 任务系统无主设计文档, 设计总览"系统"章也没有它**

- 位置: 第 194-228 行
- 文档原文: ## 系统  ### 精英怪星级词条系统(深改 Champions Unofficial) ... ### 经济系统 ... ### 结婚系统
- 代码实际: quest 包是 35 个 Java 文件、5242 行的完整子系统 (四类来源 DAILY/WEEKLY/SPECIAL/HIDDEN、任务板、任务线、领奖、上交、重摇、独立 faucet、TaCZ 可选钩子), 已登记进 module-registry 并有 QuestSystem 公开入口; 但 docs/ 下没有 Quest_System_DesignSpec.md (精英怪/经济/结婚/婚姻/电力/各职业都各有一份), design_mindmap.md 全文"任务"零命中, 唯一的文档是 TaskSpec_Quest_WebUI_Panel.md, 而它只覆盖 WebUI 面板接线, 不写任务判据、内容池、周期翻转、奖励曲线与数值。
- 取证: src/main/java/com/miningdim/quest/ 共 35 个文件 5242 行 (QuestPool/QuestChain/QuestClock/QuestRewards/QuestItemRewards/QuestConfig/QuestSavedData/QuestService 等); docs/modules/module-registry.json 的 wok-quest 条目 `"publicEntry": "com.miningdim.quest.QuestSystem", "scope": "daily, weekly, special and hidden quest board sharing the credit faucet key"`; docs/modules/INVENTORY.md:22 "| `wok-quest` | WOK-任务模块 | 35 | 49 | `QuestSystem` | 核心、经济、WebUI、附魔；TaCZ 可选 |"; 而 `grep -c 任务 docs/design_mindmap.md` = 0
- 建议改法: 两步: (1) 在 design_mindmap.md 的 "## 系统" 下补 "### 任务系统(四来源任务板)" 一节, 至少列出 DAILY/WEEKLY/SPECIAL/HIDDEN 四来源、槽位数(日常 4 含 1 硬槽 dailySlots=4/dailyHardSlots=1、周常 1 即 weeklySlots=1)、重摇 sink(dailyCost=500/weeklyCost=2500, QuestConfig.java:79/81)、独立 quest_faucet 计数器且"槽位封顶而非衰减封顶"的判据(QuestConfig.java:86-89)、悬赏将作为第五来源 BOUNTY 并入(INVENTORY.md:58); (2) 新建 docs/Quest_System_DesignSpec.md 作主设计文档, 数值真源指向 QuestConfig, 内容池真源指向 QuestPool, 奖励曲线指向 QuestRewards/QuestItemRewards, 并把 TaskSpec_Quest_WebUI_Panel.md 降级为它的"WebUI 面板接线附录"。(原修复建议已核实其引用数值全部准确, 原样采纳, 未作调整)

**[Major] [A-文档与代码不符] 共享地基 JobId 列 7 个, 代码是 8 个且以本图为权威**

- 位置: 第 230 行
- 文档原文: L230: "- 数据架构:单 capability 持 EnumMap&lt;JobId,JobProgress&gt;(不每职业新挂);JobId = 矿工/农夫/工程师/塔罗/厨师/特勤干员/军火商"
- 代码实际: JobId 枚举实际有 8 个成员, 尾部多一个 BREWER(酿酒师)。要命的是 JobId 的类注释白纸黑字把本图这一行当成"成员清单依据", 还说框架 spec 的 5 个说法"滞后于 mindmap"。现在轮到 mindmap 自己滞后, 而代码仍指着它当权威, 后人按这行去核对会得出"代码多了一个职业"的错误结论。另外图里其它节点也完全没有酿酒师这个职业。
- 取证: src/main/java/com/miningdim/job/JobId.java:9-13 "成员清单依据: design_mindmap.md \"共享地基\" 节明确列 7 个职业 (矿工/农夫/工程师/塔罗/厨师/特勤干员/军火商); ... 酿酒师 (BREWER) 即据此前向兼容性在原 7 职业尾部追加为第 8 个 (完整实现, 非占位; 尾部追加以守 network.JobSyncS2C 按 values() 顺序读写的同序契约)"; 枚举体 :22-32 `MINER, FARMER, ENGINEER, TAROT, CHEF, AGENT, MUNITIONS, BREWER`; 酿酒师实现见 src/main/java/com/miningdim/job/brewer/(BrewerConstants/BrewEffectEngine/BrewBuffStore 等 20 个文件), 注册于 src/main/java/com/miningdim/MiningDim.java:154 `subsystems.add(new com.miningdim.job.brewer.BrewerSystem());`
- 建议改法: L230 的职业清单补成 8 个: "JobId = 矿工/农夫/铸甲师(engineer)/塔罗/厨师/特勤干员/军火商/酿酒师(尾部追加, 守 JobSyncS2C 同序契约)"。同时在"职业"节下补一个"### 酿酒师"节点(酿酒台酿基酒 + 酒窖箱陈酿年份 + 永久层数增益 + 喝酒特殊效果), 否则脑图与 JobId 会继续互相指认对方是权威。

**[Minor] [D-覆盖缺口] 农夫节点两行带过, 缺五档耕地与整条卖菜经济链**

- 位置: 第 40-42 行
- 文档原文: L40-42: "### 农夫(原有)" / "- 种菜攒经验(成熟破坏结算)" / "- 每日软上限多档衰减,约一个月毕业"
- 代码实际: 农夫是已完整落地的职业, 有五档耕地(解锁级 L1/L3/L5/L7/L9, 成长间隔 10/8/6/5/4 分, 产量 2/3/4/5/6)、按等级硬封顶的耕地放置上限(9→64 块)、原版与 Farmer's Delight 兼容作物、以及带精通等级门与动态收购价的整条卖菜经济链。脑图给矿工写了 20 行、给塔罗写了 90 行, 农夫只有两行, 结构总览在这一支上是断的。
- 取证: src/main/java/com/miningdim/job/farmer/FarmerTier.java:16-29 `LOW("low", 1, 10, 2), MEDIUM("medium", 3, 8, 3), HIGH("high", 5, 6, 4), PREMIUM("premium", 7, 5, 5), SUPREME("supreme", 9, 4, 6);`; src/main/java/com/miningdim/job/farmer/FarmerConstants.java:38-49 `FARMLAND_CAP_PER_LEVEL = {9, 12, 16, 20, 25, 30, 36, 42, 48, 64}`; :97 `SELL_MIN_MASTERY_LEVEL = 2`; :69-75 `WHEAT_BASE_PRICE = 1L / WHEAT_DAILY_SOFTCAP = 2160 / WHEAT_DECAY_BASE = 0.97D`
- 建议改法: 把 docs/design_mindmap.md 第40-42行的 `### 农夫(原有)` 展开为与矿工/塔罗同粒度的子树, 至少包含: 五档耕地(低/中/高/极品/超凡, 解锁L1/3/5/7/9, 成长间隔10/8/6/5/4分, 产量2/3/4/5/6, 单作物经验固定2, 详见 FarmerTier.java:16-29); 耕地放置上限按级硬封顶 9→12→16→20→25→30→36→42→48→64(反扩建, FarmerConstants.java:38-48 + FarmlandPlacementGuard.java:37-38); 兼容作物(原版四种 + Farmer's Delight 番茄/挂绳番茄/卷心菜/洋葱/大米, 须种在mod耕地上, FarmerGameTests.java多处GameTest佐证); 经验(成熟破坏结算 + FD番茄右键采摘同样按超凡档结算, 复用JobXpCurve 2000系衰减曲线, 表C 1500系已被裁决废弃改用共享地基); 卖菜经济链(精通L2门 SELL_MIN_MASTERY_LEVEL=2 反洗钱身份门 + 动态收购价 basePrice=1/株、softCap=2160株/日、decayBase=0.97 FarmerConstants.java:69-75 + FarmerWheatBuyback.java:32-33 + 并入全服CP faucet主闸 WHEAT_SELL_FAUCET_KEY/DAILY_CREDIT_FAUCET_CAP 复用经济侧唯一真源, 与矿工卖矿共享同一每日衰减天花板)。

**[Minor] [E-状态过期] 职业名"千年工程师"已改名铸甲师,脑图未更新**

- 位置: 第 44 行
- 文档原文: L44: "### 千年工程师"
- 代码实际: 该职业的玩家可见名已改为"铸甲师", engineer 只作为旧存档与旧命令的兼容 ID 保留; 模块注册表里这个模块的登记名也是 wok-job-armorer / "铸甲师"。脑图仍用旧名, 按它去模块注册表或命令里找"千年工程师"会找不到。
- 取证: src/main/java/com/miningdim/job/JobId.java:60-62 "// \"铸甲师\"是玩家可见的新名称；保留 engineer 作为旧存档和旧命令兼容 ID。" + `if ("armorer".equalsIgnoreCase(id) || "铸甲师".equals(id)) { return ENGINEER; }`
- 建议改法: docs/design_mindmap.md 第44行 "### 千年工程师" 改为 "### 铸甲师(原千年工程师 · 稳定 id engineer)"; 同时第230行"共享地基"节的职业清单 "工程师" 一并改为 "铸甲师(engineer)", 避免同一文件内两处旧名不同步。

---

## 精英怪与特勤干员

### `docs/ChampionStarAffix_System_DesignSpec.md`

**[Critical] [E-状态过期] 7.4 仍写"电磁/天雷/小男孩/凯撒/利刃 仍哑", 批4 已于 2026-07-08 收官(35/35 全词条有 handler)**

- 位置: 第 163 行
- 文档原文: 电磁蓄力/天雷/小男孩/凯撒/利刃 仍哑（批4 待 KnockbackSafetyGuard）。
- 代码实际: 批4 三波已全部收官: KnockbackSafetyGuard 落地后这五条技能各自有独立 handler 并入白名单, 全库 35/35 词条均有运行期 handler。该状态句是本文档对"哪些技能还没做"的唯一权威标注, 过期后会让人重复实现。
- 取证: src/main/java/com/miningdim/champion/AffixRoller.java:62-65 "波2+波3 收官 (Stage2 批4 终态): 技能池周期 AOE/换位/连段 5 (ELECTRO_CHARGE/THUNDER/LITTLE_BOY ...; CAESAR_SWAP 双向守卫换位; BLADE_WALTZ 连段 60% 帽均分) + 机动池 PHASE_WALK ... 至此 35/35 全部词条有运行期 handler"; integration 目录存在 ChampionElectroChargeHandler.java / ChampionThunderHandler.java / ChampionLittleBoyHandler.java / ChampionCaesarSwapHandler.java / ChampionBladeWaltzHandler.java
- 建议改法: 将 docs/ChampionStarAffix_System_DesignSpec.md:163 句尾"电磁蓄力/天雷/小男孩/凯撒/利刃 仍哑（批4 待 KnockbackSafetyGuard）。"替换为如下内容(与 AffixRoller.java:57-64 注释及提交 b5b63272/33d49e4e/7a02d068 对齐): "批4 分三波收官（波0 2026-07-07 KnockbackSafetyGuard/落地保护/AOE免疫缓冲等共享基建 → 波1 2026-07-07 分跳/混沌真击飞/闪光/战术传送 → 波2+波3 2026-07-08 电磁蓄力/天雷/小男孩（均走 champion_skill_aoe 判决伤害 + 2s 免疫缓冲）/凯撒实验型转换器（双向守卫换位）/利刃华尔兹（连段 60% 帽均分）+ 灵体移动收官）：至此 35/35 全部词条均有运行期 handler, roll 白名单 = 全集。"同时建议核对文档同章节是否还有其它引用"批4 待完成"字样的残留表述一并回填(本次未在 docs 目录发现第二处引用, 但后续如新增章节交叉引用需同步)。

**[Critical] [C-实现状态标错] 规格仍以 Champions Unofficial 为实现底座, 代码已自研脱离**

- 位置: 第 5, 196-197, 231-309(九·附整章) 行
- 文档原文: > 深度改造对象：Champions Unofficial（`champions-forge-1.20.1-2.1.x`，开源，config + IAffix API + KubeJS 可扩展）(L5); - 1-10★ = 10 条自定义 rank（`champions-ranks.toml` 支持无限 rank）。- 自定义词条走 `IAffixBase/IAffixCombatHandler/IAffixLifecycle/IAffixSyncable`（或 KubeJS 注册）(L196-197)
- 代码实际: 代码已把冠军数据换成自研 Forge capability MiningChampionData/MiningChampions, 全库零 top.theillusivec4.champions.* 运行期依赖(32 处命中全是"不触 Champions"的注释), 入口也不再 ModList.isLoaded("champions") 守卫。按本章去接 IAffix API / champions-ranks.toml / KubeJS 会直接走错路。
- 取证: src/main/java/com/miningdim/champion/MiningChampions.java:19-22 "自研冠军 Capability 的注册、挂载与取用中心 (取代 Champions 的 {@code ChampionCapability})...供全部效果 handler...读星级 + 词条→品质, 不再触任何 Champions 类。"; src/main/java/com/miningdim/champion/ChampionSystem.java:45 "零 top.theillusivec4.champions.* 依赖。故本入口【无条件】注册 (不再 ModList.isLoaded(\"champions\") 守卫)"; build.gradle:143 champions jar 仅 compileOnly
- 建议改法: L5"深度改造对象"改为："自研 Forge capability(MiningChampionData/MiningChampions)承载星级与词条；Champions Unofficial(`libs/champions-forge-1.20.1-2.1.10.2.jar`)仅 compileOnly、mods.toml 声明为可选伴生依赖，不再是词条系统的实现底座"。 9.1(:196-197)两条改写为："10 星表由 StarRank 枚举承载数据(四池预算/上限/有效HP), 不再依赖 `champions-ranks.toml` 驱动词条数量/权重"；"词条由 AffixDef 枚举 + integration 层各 handler(ChampionAttackHandler/ChampionDotTickHandler 等)实现, 不走 `IAffixBase/IAffixCombatHandler/IAffixLifecycle/IAffixSyncable` 或 KubeJS 注册"。 `九·附`整章(:231-326，含 9A.1-9A.7)整体标注为"历史可行性论证(2026-07-07 自研脱离前), 已被自研方案取代"；并单独订正 9A.7(:317 冠军头顶名牌一条：Champions 原生 rank 颜色已被 StarRank.barColorRgb() 自研配色取代；:322 "经 IAffixSyncable 同步客户端"改为"经自研 ChampionWebUiActions/champion.codex 与既有 MiningNetwork.CHANNEL(如 ChampionSizeS2C 同范式)下发客户端")。 另建议在改写时保留一条脚注说明：Champions Unofficial 仍可作为可选伴生 mod 部署(mods.toml `[[dependencies.miningdim]] modId="champions" mandatory=false`)，deploy/champions-ranks.toml 是历史遗留兼容文件，不驱动本系统星级/词条逻辑，避免读者误以为"完全不能再装 Champions"。

### `docs/Champion_Effects_Guide.md`

**[Critical] [C-实现状态标错] 4.1 哑词条清单 16 条已全部实现, 清单整节作废**

- 位置: 第 391-417 行
- 文档原文: ### 4.1 哑词条清单(16 条)\n来自 `AffixRoller.java:53-64` 明确列出的未接线词条(35 条总词条减去 19 条白名单)... 技能池(全部 10 条主动技能, 均未接线): 电磁蓄力/天雷/小男孩/命定之死/视觉干扰/自我修复单元/反击单元/凯撒实验型转换器/利刃华尔兹/支援
- 代码实际: 这 16 条各自都有独立生产 handler: 混沌重击真 push 击飞、双倍/四倍按跳拆分、闪光/战术传送/灵体移动各自周期瞬移、技能池 10 条各有 Handler 并在 ChampionSystem 注册。玩家按本节理解会误判这些词条无害。
- 取证: src/main/java/com/miningdim/champion/AffixRoller.java:62-64 "波2+波3 收官 (Stage2 批4 终态): 技能池周期 AOE/换位/连段 5 (ELECTRO_CHARGE/THUNDER/LITTLE_BOY 走 champion_skill_aoe 判决伤害 + 2s 免疫缓冲; CAESAR_SWAP 双向守卫换位; BLADE_WALTZ 连段 60% 帽均分) + 机动池 PHASE_WALK"; src/main/java/com/miningdim/champion/integration/ChampionAttackHandler.java:170 "applyChaosKnockback(equipped, pair, nowTick, attacker, victim);"; integration 目录下 ChampionElectroChargeHandler/ChampionThunderHandler/ChampionLittleBoyHandler/ChampionDeathMarkHandler/ChampionVisualDisruptionHandler/ChampionSelfRepairHandler/ChampionCounterUnitHandler/ChampionCaesarSwapHandler/ChampionBladeWaltzHandler/ChampionSummonHandler 十个文件均存在
- 建议改法: 删除 4.1 整节(或改写为"历史遗留: 批3 时代曾有 16 条哑词条, 批4(2026-07-08, 7a02d068)已全部接线"), 并把这 16 条按"一效果一节"补进第三章, 体例与已有 19 条一致(标注品质数值表+反制建议)。第四章标题"尚未生效/未来内容"若保留, 内容只应留真正未接线项——目前 AffixRoller.IMPLEMENTED_AFFIXES 已覆盖 AffixDef 全部 35 项, 即无剩余未接线词条, 第四章可能需要整体撤下或改为"当前无未接线词条"的占位说明。顺带核实并修正 4.2 节关于 KnockbackSafetyGuard "没有实现"的表述(该类与配套 GameTest 均已存在于 champion/integration 包下)。

**[Critical] [F-结构问题] 第四章(4.1 哑词条清单/4.2 KnockbackSafetyGuard未实现)整体已过期: AffixRoller.java 自身 javadoc 证明 35/35 词条已全部接线可刷出, 且 KnockbackSafetyGuard 是已实现类, 不止是 53-64/69-97 行号引用错位**

- 位置: 第 393, 432 行
- 文档原文: 来自 `AffixRoller.java:53-64` 明确列出的未接线词条(L393); 已实现白名单(19 条)与哑词条清单(16 条): `AffixRoller.java:69-97`(白名单成员), `AffixRoller.java:53-64`(排除说明)(L432)
- 代码实际: AffixRoller.java 53-64 行现在是批4 收官的 javadoc 叙述(波1/波2/波3 落地说明), 并不存在"排除说明"; 白名单成员现为 70-119 行而非 69-97。文件内根本没有独立的哑词条排除清单(白名单已是全集), 按行号去查会查到相反结论。
- 取证: src/main/java/com/miningdim/champion/AffixRoller.java:57-64 为"波1 增补 (Stage2 批4; KnockbackSafetyGuard 已落地)...波2+波3 收官 (Stage2 批4 终态)...至此 35/35 全部词条有运行期 handler, 白名单 = 全集"; 同文件 70 行 "public static final Set<AffixDef> IMPLEMENTED_AFFIXES = Collections.unmodifiableSet(EnumSet.of(" 至 119 行 "AffixDef.PHASE_WALK));"
- 建议改法: 不能只把行号从 69-97 改成 70-119 了事, 需要连内容一起重写: 1) 重新核实当前 IMPLEMENTED_AFFIXES(AffixRoller.java:70-119, 共 35 条)与 AffixDef.java 总词条数完全相等这一事实, 把文档第三章"已实现词条大全(19 条)"标题及 3.18/3.19 之后缺失的 16 节(BLINK/TACTICAL_BLINK/PHASE_WALK/DOUBLE_STRIKE/QUADRUPLE_STRIKE/CHAOS_STRIKE/ELECTRO_CHARGE/THUNDER/LITTLE_BOY/DEATH_MARK/VISUAL_DISRUPTION/SELF_REPAIR/COUNTER_UNIT/CAESAR_SWAP/BLADE_WALTZ/SUMMON_SUPPORT)按各自 handler 补齐, 统计口径改成"35 条"; 2) 删除或彻底重写第四章 4.1 节, 不再声称这 16 条"刷怪也抽不到"; 3) 重写 4.2 节, 如实说明 KnockbackSafetyGuard 已在 integration/KnockbackSafetyGuard.java 落地并被 Blink/TacticalBlink/CaesarSwap/PhaseWalk/BladeWaltz 五个 handler 消费, 有 KnockbackSafetyGuardGameTests 覆盖, 不再是"代码注释里的概念"; 4) 第五章证据来源表如仍需引用白名单声明, 改成 `AffixRoller.java:70-119`(当前 = 全集 35 条), 删除"AffixRoller.java:53-64(排除说明)"这一不存在的引用。建议将此项作为 Critical 立即处理, 而不是随手改两个数字。

**[Critical] [C-实现状态标错] 称落点安全守卫"只是注释概念、无实体类", 实为已落地类**

- 位置: 第 419-421, 444 行
- 文档原文: 有一个规划中的"落点安全守卫(KnockbackSafetyGuard)"...它目前只是代码注释里的概念, **没有实现**...受此影响而不产生真实位移的有: 混沌重击的击飞、凯撒转换器的换位、利刃华尔兹的突袭(L421); 落点安全守卫(未实现): 仅见于 `AffixDef.java`, `integration/ChampionAttackHandler.java` 的注释引用, 无实体类(L444)
- 代码实际: KnockbackSafetyGuard 已是独立生产类(批4 波0 提交 b5b6327), 配套 SafeLandingRules/PlayerLandingProtection 也已落地, 混沌击飞/凯撒换位/利刃突袭全部产生真实位移并过守卫裁决。
- 取证: src/main/java/com/miningdim/champion/integration/KnockbackSafetyGuard.java:32 "public final class KnockbackSafetyGuard {"; 同文件 14-16 行 "位移安全守卫的【世界适配层】(ChampionStarAffix spec 9.3 / 红线 6)。所有产生位移/击退/击飞/换位的词条效果在服务端权威结算前, 必须经本守卫预测【末端落点】"; 同目录另有 PlayerLandingProtection.java, 同包上级有 SafeLandingRules.java; git log b5b63272 "批4 波0 位移安全共享基建 (KnockbackSafetyGuard/落地保护/AOE免疫缓冲/体型白名单)"
- 建议改法: 删除第四章 4.2 整节("落点安全守卫未实现的连带影响"), 改在第二章新增一节"落点安全守卫", 说明 KnockbackSafetyGuard/SafeLandingRules/PlayerLandingProtection 三者分工(纯逻辑落点判定/世界适配裁决/落地后抗二次位移)及其如何保证混沌重击击飞、凯撒转换器换位、利刃华尔兹突袭不把玩家送进岩浆或虚空。第五章 L444 证据行改为: `integration/KnockbackSafetyGuard.java`(世界适配裁决)+ `SafeLandingRules.java`(纯逻辑判定)+ `integration/PlayerLandingProtection.java`(落地保护), 引用点补 `integration/ChampionAttackHandler.java:416-442`(混沌重击真 push)、`integration/ChampionCaesarSwapHandler.java:270-272`(双向裁决换位)、`integration/ChampionBladeWaltzHandler.java:218-219`(突袭选点)。同时应一并核实并更新紧邻的 4.1"哑词条清单(16 条)"(第 391-417 行): 经 AffixRoller.java 的 IMPLEMENTED_AFFIXES 白名单核对, 该清单所列 16 条(含本条涉及的 CHAOS_STRIKE/CAESAR_SWAP/BLADE_WALTZ)均已被纳入白名单(注释原文"至此 35/35 全部词条有运行期 handler, 白名单 = 全集"), 第四章标题"尚未生效/未来内容"本身已名不副实, 建议整章重写或删除并把已实现内容并入第三章"已实现词条大全"。

**[Critical] [C-实现状态标错] Champion_Effects_Guide.md 第56/58/134行称白名单仅19条已实现词条, 代码 AffixRoller.IMPLEMENTED_AFFIXES(现70-119行)自2026-07-08批4收官起已是35/35全集; 连带第四章"16条哑词条清单"与"KnockbackSafetyGuard未实现"的说法(L387-421)均已过期失实**

- 位置: 第 56, 58, 132 行
- 文档原文: 代码里定义了 35 条词条, 但**当前只有 19 条真正接好了运行效果**, 另外 16 条是"哑词条"...系统用一份白名单(`AffixRoller.java:69-97` 的 `IMPLEMENTED_AFFIXES`)保证: **刷怪只会掷出白名单里的 19 条**(L56); 下面 19 条是当前游戏里真能刷出并生效的全部词条(L132)
- 代码实际: 批4 收官后 IMPLEMENTED_AFFIXES 已登记 35 个 AffixDef 常量(COMPOSITE_ARMOR 一直到 PHASE_WALK), 白名单等于全集, 玩家实际能刷到全部 35 条。手册第三章只展开 19 条, 玩家会在游戏里遇到 16 条手册完全没讲的词条(含 10 个主动技能)。
- 取证: src/main/java/com/miningdim/champion/AffixRoller.java:64 "至此 35/35 全部词条有运行期 handler, 白名单 = 全集 (本集合仍保留白名单语义: 未来新增词条默认不可 roll, 实现后显式登记)"; 同文件 70-119 行 EnumSet.of(...) 逐条登记到 AffixDef.PHASE_WALK; git log 7a02d068 "feat(champion): 批4 波2+波3 收官 35/35 全词条"
- 建议改法: 1) L56 改为: "代码定义 35 条词条, 批4 收官(2026-07-08, commit 7a02d068)后 35 条已全部接好运行效果, 白名单 IMPLEMENTED_AFFIXES(`AffixRoller.java:70-119`)= 全集"; 删去"另外 16 条是哑词条"整句。 2) L58 与 L134 的"19 条"均改为"35 条"; 章节三标题(L132 `## 三、已实现词条大全(19 条, 一效果一节)`)同步改为"35 条"，并为其补齐当前缺失的 16 节: 传送家族 3 条(BLINK/TACTICAL_BLINK/PHASE_WALK)、战斗池分跳击飞 3 条(DOUBLE_STRIKE/QUADRUPLE_STRIKE/CHAOS_STRIKE)、技能池 10 条主动技能(ELECTRO_CHARGE/THUNDER/LITTLE_BOY/DEATH_MARK/VISUAL_DISRUPTION/SELF_REPAIR/COUNTER_UNIT/CAESAR_SWAP/BLADE_WALTZ/SUMMON_SUPPORT), 各自机制口径可直接参照 `AffixRoller.java` 类注释里"波1 增补""波2+波3 收官"两段的 handler 清单与对应 integration 类。 3) 第四章(L387-417)"尚未生效/未来内容"下的"4.1 哑词条清单(16 条)"整节应删除或明确标注为历史存档(如"批4 之前的旧状态, 现已全部实现, 参见第三章"), 不能继续留在"当前未实现"位置误导读者; 4.2 节关于"落点安全守卫(KnockbackSafetyGuard)...没有实现"的整段描述应删除或改写, 因为 `KnockbackSafetyGuard.java` 已实现并被 `ChampionAttackHandler.applyStrikeSplit`/`applyChaosKnockback` 实际调用, 混沌重击等击飞类效果现在会真实推动玩家。 4) 第五章证据来源(L429-431)中 `AffixRoller.java:69-97` 的引用应更新为当前实际行号 `AffixRoller.java:70-119`。 5) 文档开头 L5 的核对基线"截至 2026-07-07 的 ... 源码"应更新为不早于 2026-07-08(批4 波2+波3 收官提交 7a02d068)之后的日期, 并重新走一遍全文数值核对。

### `docs/ChampionStarAffix_System_DesignSpec.md`

**[Major] [A-文档与代码不符] 9.2 称子弹走 EntityHurtByGunEvent 为同一结算点, 代码只有 LivingHurtEvent**

- 位置: 第 201 行
- 文档原文: 6★+ 血池的 `LivingHurtEvent` 与子弹的 `EntityHurtByGunEvent` 为同一受击结算点
- 代码实际: 精英怪侧只监听 LivingHurtEvent 一个事件, 子弹与近战走同一入口、靠伤害类型 id 分桶; EntityHurtByGunEvent 在全库仅被工程师板甲耐久 handler 使用, 与精英怪结算无关。按本句去加 TACZ 事件监听会造成双重结算。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionBloodPoolHandler.java:87 "public void onLivingHurt(LivingHurtEvent event) {"; 同文件 46 行 "(超高分子/重型子弹抗 + 复合同源适应 ramp + 偏斜 EV + 缩小化体型折算) 在本 {@link LivingHurtEvent} 单点收集 rates"; src/main/java/com/miningdim/job/engineer/armor/integration/PlateArmorTaczDurabilityHandler.java:3 为全库唯一 EntityHurtByGunEvent 引用
- 建议改法: 将 docs/ChampionStarAffix_System_DesignSpec.md 第 201 行开头改为: "6★+ 血池与子弹共用同一个 `LivingHurtEvent` 受击结算点(子弹按伤害类型 id `tacz:bullet*` 前缀分桶识别, 见 `ChampionDamageReduction.isBulletDamage`/`ChampionBloodPoolHandler.categorize`), 不额外监听 TACZ 的 `EntityHurtByGunEvent`(该事件全库唯一用于工程师板甲耐久判定, 与精英怪减伤链无关), 以免对子弹伤害重复结算"; 其后 keep 公式与 TDD 描述保持不变。

**[Major] [A-文档与代码不符] 9.6 与十四·5 称抗枪减伤走 tacz:bullet_resistance 属性 AttributeModifier + TACZ 事件监听, 代码实为伤害类型字符串判定并直接把折算率并入净减伤连乘, 无任何 Attribute/AttributeModifier/事件监听**

- 位置: 第 222, 390 行
- 文档原文: - 抗枪减伤走 `tacz:bullet_resistance` 属性（0-1，syncable，挂全实体），瞬态 AttributeModifier，封顶 <0.5 并入净减伤钳制。(L222); 5. TACZ 接入：bullet_resistance 修饰器 + 公共事件监听。(L390)
- 代码实际: 全库没有任何给冠军挂 tacz:bullet_resistance AttributeModifier 的代码, 该字符串只出现在 4 处注释里。实际做法是在 LivingHurtEvent 单点按伤害类型 id 判定是否子弹, 再把超高分子/重型护甲的抗性率作为 rᵢ 并入 keep 连乘, 完全在我方逻辑内完成。
- 取证: src/main/java/com/miningdim/champion/ChampionDamageReduction.java:210 "重型护甲: 近战/爆炸单次净伤 < T 归 0 (整次免疫); 子弹不享此免疫 (子弹走 bullet_resistance 比例减)"(全库 bullet_resistance 4 处命中全为注释, 无属性 API 调用); src/main/java/com/miningdim/champion/integration/ChampionBloodPoolHandler.java:190 "boolean bullet = isBulletDamage(source);" 与 265-271 按 ResourceLocation 判 tacz:bullet*
- 建议改法: 1) 第222行(9.6 首条)改为: "抗枪减伤不挂任何 TACZ 属性修饰: 在受击结算点(LivingHurtEvent/EntityHurtByGunEvent)按伤害类型 ResourceLocation 的 namespace/path 判定是否子弹伤害(`ChampionDamageReduction.isBulletDamage`, namespace=tacz 且 path 以 bullet 起), 命中后把超高分子/重型护甲对应档位的抗性率(由 `AffixDef` 档位数值折算)直接作为 rᵢ 传入 `ChampionRedlines.clampNetKeepFactor` 参与 keep 连乘, 不注册/操作实体 Attribute, 也不产生 AttributeModifier。" 2) 第390行(十四·5)改为: "TACZ 接入: 按伤害类型 id(namespace=tacz, path 以 bullet 起)在受击结算点判定子弹伤害 + 抗性档位折算率并入净减伤连乘(`ChampionDamageReduction`/`ChampionBloodPoolHandler`/`ChampionRedlines`, 已实现); 不涉及属性修饰器或额外的 TACZ 公共事件监听(EntityHurtByGunEvent/AttachmentPropertyEvent 仅用于工程师护甲耐久与枪匠属性两个不相关模块, 与本条无关)。"

**[Major] [B-文档互相打架] 9A 两处（L245、L256）仍写净减伤 49%，与同文档红线1(L45)/9.2(L201) 已于2026-07-07 抬帽至 75% 及代码常量 ChampionRedlines.NET_DAMAGE_REDUCTION_CAP=0.75D 自相矛盾**

- 位置: 第 245, 256 行
- 文档原文: **禁止用 Champions 逐词条 `onHurt` 实现减伤**：...多源相乘必穿透净减伤 49%（红队已证）(L245); | 3 | 被动减伤（复合/超高分子/刚毅/偏斜） | 须自建 | 不能各词条独立 onHurt；走 9.2 单点聚合 + 净减伤 49% clamp |(L256)
- 代码实际: 同文档红线 1(L45)与 9.2(L201)都已于 2026-07-07 抬到 75%, 代码 ChampionRedlines.NET_DAMAGE_REDUCTION_CAP=0.75D / MIN_KEEP_FACTOR=0.25。9A 的两处 49% 是抬帽前的遗留数字, 同一份文档给出两个互相矛盾的封顶值。
- 取证: src/main/java/com/miningdim/champion/ChampionRedlines.java:19-26 "净减伤上限 (spec 红线 1; 2026-07-07 用户定向 49%→75%): 单点求 keep = ∏(1-rᵢ) 后统一 keep = max(keep, 1-0.75) ... public static final double NET_DAMAGE_REDUCTION_CAP = 0.75D; ... public static final double MIN_KEEP_FACTOR = 1.0D - NET_DAMAGE_REDUCTION_CAP;"
- 建议改法: 将 L245"多源相乘必穿透净减伤 49%（红队已证）"与 L256"净减伤 49% clamp"两处的"49%"均改为"75%"，并建议改写措辞为"必穿透红线1(第9.2节)规定的净减伤硬封顶"这类不重复具体数字的引用方式，避免未来红线1数值再调整时又需要逐处同步而遗漏。

**[Major] [A-文档与代码不符] 十章刷怪星级区间与代码及玩家手册均不符**

- 位置: 第 333-334 行
- 文档原文: | 矿洞 中（L4-7） | 3-5★ 保底 |\n| 矿洞 困难（L8+） | 5-8★，深层保底高星 |
- 代码实际: 代码 MEDIUM 为 3-6★、HARD 为 5-10★; Champion_Effects_Guide 2.1(L82-84)写的也是中等 3★-6★、困难 5★-10★。本表的 3-5/5-8 既与代码不符, 也与玩家手册打架, 会让人以为困难矿洞刷不出 9-10★ 世界 BOSS。
- 取证: src/main/java/com/miningdim/champion/ChampionSpawnPolicy.java:41-46 "public static final int EASY_MIN_STAR = 1; public static final int EASY_MAX_STAR = 3; public static final int MEDIUM_MIN_STAR = 3; public static final int MEDIUM_MAX_STAR = 6; public static final int HARD_MIN_STAR = 5; public static final int HARD_MAX_STAR = 10;"
- 建议改法: 把第333-334行两行改为"| 矿洞 中（L4-7） | 3-6★ |"与"| 矿洞 困难（L8+） | 5-10★，深层保底高星 |"，并补一句升格概率 易 6% / 中 10% / 困难 15%(`ChampionSpawnPolicy`)，与 Champion_Effects_Guide.md 第82-84行、ChampionSpawnPolicy.java 第41-46行三方对齐。

**[Major] [E-状态过期] 十一章称刷怪信用点 faucet"当前代码未接线", 且衰减参数张冠李戴、青辉石"单设"失实**

- 位置: 第 346 行
- 文档原文: 刷怪信用点并入 `EconomyConstants economy.daily.*` 软上限 + AbuseGuard mob-kill faucet（复用 `decayBase=0.97/floorRatio=0.25` 与 UTC 翻日，当前代码未接线属缺口）；青辉石单设日产软上限。
- 代码实际: 三处都不对: (1) 已接线 —— ChampionRewardHandler 逐合格玩家走 grantDaily 并入 credit_faucet 主闸; (2) 参数张冠李戴 —— 0.97 是逐矿收购价 steering 的 ECONOMY_DECAY_BASE, 衰减主闸是 FAUCET_DECAY_BASE=0.6、地板是 ECONOMY_PRICE_FLOOR_RATIO=0.01(1%), 代码注释明确写"底数与主闸 0.6 语义不同、不可共用"; (3) 青辉石日上限与特勤悬赏共享 azure_faucet 键, 非"单设"。照此文档再接一次线会造成重复入账。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:161-163 "EconomyServices.economyService().grantDaily(player, raw, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);"; src/main/java/com/miningdim/economy/EconomyConstants.java:58-61 "economy.decayBase: 逐矿收购价 steering 递减底数 (per-ore, 默认 0.97) ... 故底数与主闸 0.6 语义不同、不可共用", :68 ECONOMY_PRICE_FLOOR_RATIO = 0.01D, :79 FAUCET_DECAY_BASE = 0.6D
- 建议改法: 将 docs/ChampionStarAffix_System_DesignSpec.md 第 346 行改为: "刷怪信用点已接线: 逐合格玩家经 `ChampionRewardHandler` 调用 `grantDaily` 并入 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`(credit_faucet) 主闸, 与矿工卖矿/农夫卖菜共享同一衰减主闸(单档 60,000 信用点毛收入, 跨档系数 `FAUCET_DECAY_BASE=0.6`, 地板 `ECONOMY_PRICE_FLOOR_RATIO=0.01`(1%)); 青辉石经 `grantAzureDaily` 并入 `AZURE_DAILY_FAUCET_KEY`(azure_faucet), 与特勤周常悬赏(`AgentRewardHandler.grantWeeklyBountyAzure`)共享同一每人每日硬上限(`AZURE_DAILY_FAUCET_CAP=30`), 非单独设置。" 删去"decayBase=0.97/floorRatio=0.25"与"当前代码未接线属缺口"表述; 0.97 是 `ECONOMY_DECAY_BASE`(逐矿收购价 steering 底数), 与主闸语义不同不可混用。

### `docs/Champion_Effects_Guide.md`

**[Major] [A-文档与代码不符] 反震写成仅近战触发, 代码对任何玩家来源伤害(含枪械)都反**

- 位置: 第 229, 231, 237 行
- 文档原文: 近战打中它时, 它按**你(攻击者)的最大生命**的一定比例真伤反弹给你(L231); 用远程(子弹)攻击不触发这条近战反震。(L237)
- 代码实际: applyThorns 的唯一来源判据是"伤害源实体是不是 ServerPlayer", 没有任何近战/远程区分 —— 玩家用 TACZ 枪械打中同样触发反伤。手册据此给出的"用远程绕开反震"反制建议是错的, 会直接误导玩家。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionSelfEffectHandler.java:224-226 "if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) { return; // 非玩家来源 (环境/召唤物): 不反伤。 }"(其后仅有内 CD 与 maxHp 判定, 无近战判据)
- 建议改法: docs/Champion_Effects_Guide.md 3.8 节: 1) L231 "近战打中它时, 它按..." 改为 "任何由玩家造成的伤害(近战、弓箭、TACZ 枪械等一视同仁)打中它时, 它按..."。 2) 删除 L237 末句"用远程(子弹)攻击不触发这条近战反震"。 3) L237 的反制建议改写为: "无法靠切换远程武器规避反震(判据只看伤害来源是不是玩家, 见 ChampionSelfEffectHandler.applyThorns 的 instanceof ServerPlayer 判定, 无近战/远程分支); 唯一限频手段是 3 秒内 CD 与 RetaliationAggregator 的每秒/每 5 秒反伤帽, 高频轻击或多人集火时应留意帽值封顶。"

**[Major] [C-实现状态标错] 巨大化称体型/碰撞渲染"属于后续内容", 代码已落地**

- 位置: 第 243, 252 行
- 文档原文: 生存池 / 基础点数 12 / 最低 3★ / 状态: 已实现(血量分量)(L243); 体型放大的碰撞/渲染形态属于后续内容, 当前已生效的是血量分量。(L252)
- 代码实际: 批4 波2/波3 已把体型分量做完: ChampionSizeHandler 服务端改 EntityDimensions/AABB, ChampionSizeScale 提供缩放数学, ChampionSizeS2C 同步到客户端, ChampionSizeRenderClient 做渲染缩放, 另有 SizeAffixEligibility 白名单校验。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionSizeHandler.java:22-28 import net.minecraft.world.entity.EntityDimensions / net.minecraft.world.phys.AABB 等; 同包存在 src/main/java/com/miningdim/champion/ChampionSizeScale.java、src/main/java/com/miningdim/champion/client/ChampionSizeRenderClient.java、src/main/java/com/miningdim/champion/network/ChampionSizeS2C.java、src/main/java/com/miningdim/champion/SizeAffixEligibility.java; git log 7a02d068 提交标题含"体型渲染"
- 建议改法: L243 状态改为"已实现(血量 + 体型/碰撞/渲染)"; L252 改写为: 体型放大同时改服务端碰撞箱(EntityEvent.Size 缩放 EntityDimensions/AABB, 含生成期"放不下则降档"与运行时卡墙 blink 守卫)与客户端渲染缩放(经 ChampionSizeS2C 同步给 ChampionSizeRenderClient 做 PoseStack 缩放与客户端碰撞箱同步), 并补充说明该效果只对 SizeAffixEligibility 白名单内的 13 种规则碰撞箱人形/亚人形实体生效, 异形碰撞箱实体不会 roll 到体型词条。

**[Major] [F-结构问题] 面向玩家与服主的手册混入开发文档目录且零入口**

- 位置: 第 3 行
- 文档原文: L3「面向玩家与服主的精英怪(Champion)玩法说明。讲清楚"目前游戏里真正做出来的精英怪效果"是什么、怎么触发、各品质有多强、以及玩家可以怎么反制。」
- 代码实际: 这是全仓唯一一份面向非开发受众的文档(446 行玩家手册), 却和 56 份开发规格平铺在同一个 docs/ 目录, 文件名不带受众标识, 而且零入链 —— 玩家和服主没有任何路径能找到它。同一主题的开发侧规格 docs/ChampionStarAffix_System_DesignSpec.md 就在隔壁, 两份文档受众、更新节奏、正确性标准完全不同, 目录里却毫无区分。
- 取证: 对应代码是活跃模块: src/main/java/com/miningdim/champion/ 下有 AffixDef.java、AffixPool.java、AffixRoller.java 等(docs/modules/README.md:36「| 玩法 | WOK-精英怪模块 | `wok-champion` | `champion` |」)。全仓受控文件子串反查该文件名零命中(与本轮 19 份孤儿同一脚本)。
- 建议改法: 新建 docs/manual/ 存放面向玩家与服主的文档, 把本文迁入并在 docs/README.md 的文档地图里单列「玩家与服主手册」一节。手册头部统一标注受众与「数值以代码为准、本文按版本同步」的口径, 并与 docs/ChampionStarAffix_System_DesignSpec.md 双向互链, 避免两份文档在同一批数值上各自漂移。

### `docs/SpecialAgent_Job_DesignSpec.md`

**[Major] [A-文档与代码不符] 加强奖励声称并入 economy.daily.* 逐矿软上限与子虚乌有的 AbuseGuard mob-kill faucet，实际接的是 credit_faucet 全局信用点衰减主闸**

- 位置: 第 100 行
- 文档原文: **受经济每日软上限约束**（防印钞），并入 `EconomyConstants economy.daily.*` + AbuseGuard mob-kill faucet。
- 代码实际: 代码走的是 grantDaily(player, raw, GLOBAL_DAILY_CREDIT_FAUCET_KEY="credit_faucet", GLOBAL_DAILY_CREDIT_FAUCET_TIER=60000) 的全局信用点衰减主闸, 与矿工卖矿/农夫卖菜共享同一天花板。EconomyConstants 里的 economy.daily.* 是逐矿种日上限(钻石 64/下界残骸 8/金 256), 与信用点主闸无关; AbuseGuard 里也没有任何 mob-kill faucet。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:166-168 "EconomyServices.economyService().grantDaily(player, bonusRaw, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);"; src/main/java/com/miningdim/economy/EconomyConstants.java:49 "economy.daily.diamond: 钻石每玩家每日软上限 (默认 64)", :86 "GLOBAL_DAILY_CREDIT_FAUCET_TIER = 60000L", :91 "GLOBAL_DAILY_CREDIT_FAUCET_KEY = \"credit_faucet\""
- 建议改法: 将 docs/SpecialAgent_Job_DesignSpec.md:100 改为："受经济每日软上限约束（防印钞），并入 `EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY`(credit_faucet)/`GLOBAL_DAILY_CREDIT_FAUCET_TIER`(60,000 CP 一档)信用点衰减主闸，与矿工卖矿/农夫卖菜共享同一每人每日天花板"，并删除对 `economy.daily.*`(实为钻石/下界残骸/金逐矿软上限，与本机制无关)与 AbuseGuard 内并不存在的 mob-kill faucet 的错误引用。

**[Major] [A-文档与代码不符] 青辉石写"单设日产软上限"，代码是周产软上限50 + 与精英掉落共享的日硬上限30**

- 位置: 第 105, 195 行
- 文档原文: - **青辉石仅来自周常悬赏**（≥L4），单设日产软上限。(L105); - **青辉石**唯一来源：周常悬赏（≥L4），单设日产软上限。(L195)
- 代码实际: 代码的周常悬赏青辉石出口是双轴门控: 先过 AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP=50 的每玩家每【周】软上限, 放行量再过 EconomyConstants.AZURE_DAILY_FAUCET_CAP=30 的每日硬上限, 而该日上限是与精英怪 6★+ 掉落**共享**的 azure_faucet 键, 并非"单设"。本文档十三章第 5 条也自称"青辉石周产软上限", 与七/十一章的"日产软上限"措辞自相矛盾。
- 取证: src/main/java/com/miningdim/job/agent/AgentBountySavedData.java:52 "public static final long WEEKLY_AZURE_SOFT_CAP = 50L;"; src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:205-207 "周门控放行的 grantable 再经 {@code grantAzureDaily} 并入【与精英怪掉落共享的】每人每日青辉石产出硬上限 (azure_faucet 键, 日+周双轴)"; src/main/java/com/miningdim/economy/EconomyConstants.java:122 "public static final long AZURE_DAILY_FAUCET_CAP = 30L;"
- 建议改法: 将 L105/L195 的"单设日产软上限"统一改为："青辉石产出经双轴门控：每玩家每周软上限50（`AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP`，撞顶只发剩余额度）+ 与精英怪6★+掉落共享的每人每日硬上限30（`EconomyConstants.AZURE_DAILY_FAUCET_CAP`，经同一 azure_faucet 键的 `grantAzureDaily` 硬截断，非衰减）"，并删除"单设"二字；同时核对十三章第5条(L245)"青辉石周产软上限"的措辞，使三处（七章/十一章/十三章）与十二章(L229"每人每日硬上限30")对齐，避免同一文档内对同一上限一处称"软"一处称"硬"、一处称"日"一处称"周"的自相矛盾。

**[Major] [C-实现状态标错] 8.1 表"首次扫描发现精英"XP 行零实现且未在 12.0/13 章登记为缺口**

- 位置: 第 118 行
- 文档原文: | 首次扫描发现精英 | 星级 × 8（1★=8 … 10★=80） | 每只仅首次，奖励"找"，防重复扫刷 |
- 代码实际: 全库唯一的特勤经验生产入口是击杀结算里的 AgentLevels.grantRawXp(AgentRewardHandler:200), 按 AgentKillXp 的"星级 × 60 × 贡献占比"计算; 没有任何按首次扫描发经验的代码, 也没有"已扫描过的精英"去重集合。本文档对悬赏缺口(12.0)有专门的 DEFERRED 标注, 唯独这条缺口未登记, 读者会以为它已生效。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:200 "AgentLevels.grantRawXp(player, xpRaw);"(全库唯一特勤经验入账点); src/main/java/com/miningdim/job/agent/AgentKillXp.java:34 "public static final long XP_BASE_PER_STAR = 60L;"; 全库搜索 scanXp/SCAN_XP/首次扫描 零命中
- 建议改法: 在 docs/SpecialAgent_Job_DesignSpec.md:118 该行后追加括注,例如"【未实现,DEFERRED】首次扫描发现的 XP 尚无代码,与悬赏 XP 同属待接线(参见12.0行文风格);当前唯一生效的 XP 来源是击杀精英(AgentKillXp: 星级×60×贡献占比,经 AgentRewardHandler.grantAgentKillXp -> AgentLevels.grantRawXp 入账)",并在第十三章"实现拆分"第2条或新增一条中登记该缺口,与12.0节的悬赏系统缺口并列声明,避免读者误以为该 XP 来源已随扫描面板一并落地。

**[Major] [A-文档与代码不符] 10.2/13-3 封印机制写成 IChampion.getData 存 sealed 集合 + IAffixSyncable 重同步,代码实为直接 removeAffix 摘词条增量还原,常驻修饰按 modifier name 而非固定 UUID 摘除**

- 位置: 第 175, 243 行
- 文档原文: 封印 = 在 `IChampion.getData/setData` 写"sealed 集合"；我方所有词条钩子（onServerUpdate/onAttack/onHurt）生效前先判 sealed(L175); 3. 封印子系统：sealed 集合 + 词条钩子判定 + 常驻修饰 teardown/重加 + IAffixSyncable 重同步(L243)
- 代码实际: 代码封印 = 直接 MiningChampionData.removeAffix 把被封的那几条从自研 capability 摘掉 + 到期增量合并回去, 没有 sealed 集合、没有逐钩子判 sealed、没有 IAffixSyncable。且刻意用增量而非整份快照(防 LITTLE_BOY 一次性词条被还原成可重复触发)。常驻修饰 teardown 的对象也不是 bullet_resistance, 而是 SPRINT/OVERDRIVE/SELF_REPAIR 三条的 MOVEMENT_SPEED modifier, 且靠 modifier name 字符串匹配而非固定 UUID。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentSealExecutor.java:21-23 "真封印执行 ... {@link MiningChampionData#removeAffix} 临时移除少量词条 + 到期增量恢复), 已自研脱离 Champions —— 直接操作自研 {@link MiningChampionData} capability, 不 import 任何 top.theillusivec4.champions.*"; 同文件 63-66 "STEADY_STATE_MOVEMENT_MODIFIER_NAMES = Map.of(AffixDef.SPRINT, \"champion_sprint\", AffixDef.OVERDRIVE, \"champion_overdrive\", AffixDef.SELF_REPAIR, \"champion_self_repair_root\")"
- 建议改法: 把 10.2 改写为:"封印 = 服务端权威调 MiningChampionData.removeAffix 把被封词条从自研 capability 直接摘除(不写 sealed 标记,不逐钩子判定,词条钩子因数据已被摘空而自然不生效);到期由 AgentSealExecutor 按增量快照(仅本次封印摘除的那几条)合并回当前词条表,刻意不做整份词条集覆盖,以防窗口内被 LITTLE_BOY 等一次性词条自摘后被误还原成可重复触发。对挂了常驻 MOVEMENT_SPEED AttributeModifier 的 SPRINT/OVERDRIVE/SELF_REPAIR 三条词条,因其固定 UUID 是 champion.integration 包私有常量、job.agent 侧无法跨包引用,改按 modifier 的公开 name 字符串('champion_sprint'/'champion_overdrive'/'champion_self_repair_root')匹配摘除;超高分子的 tacz:bullet_resistance 并非常驻 AttributeModifier,而是受击时按比例计算的伤害减免率,不涉及本机制,不应作为示例。"同时把十三章第 3 条"封印子系统:sealed 集合 + 词条钩子判定 + 常驻修饰 teardown/重加 + IAffixSyncable 重同步"中的"sealed 集合 + 词条钩子判定"改为"removeAffix 摘除 + 增量快照恢复",并删除"IAffixSyncable 重同步"(全仓无此接口,面板/名牌刷新走既有扫描推送/网络同步通道,非独立的 IAffixSyncable)。

**[Major] [A-文档与代码不符] 10.3 称枪伤走 TACZ EntityHurtByGunEvent,精英侧根本没接该事件**

- 位置: 第 180 行
- 文档原文: （枪伤走 TACZ `EntityHurtByGunEvent` attacker、DoT 走伤害源 owner）
- 代码实际: 全库唯一使用 EntityHurtByGunEvent 的是工程师板甲耐久 handler; 精英怪侧贡献记账与减伤都只挂 LivingHurtEvent, 子弹靠伤害类型 id(tacz:bullet*)识别, 不监听 TACZ 事件。
- 取证: src/main/java/com/miningdim/job/engineer/armor/integration/PlateArmorTaczDurabilityHandler.java:3 "import com.tacz.guns.api.event.common.EntityHurtByGunEvent;"(全库唯一命中); src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:69-70 "@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true) public void onChampionHurt(LivingHurtEvent event)"; src/main/java/com/miningdim/champion/integration/ChampionBloodPoolHandler.java:265-271 isBulletDamage 按 ResourceLocation 的 namespace/path 判 tacz:bullet*
- 建议改法: 把 SpecialAgent_Job_DesignSpec.md 第180行括号内改为:"（枪伤与近战、DoT 统一走 LivingHurtEvent 的单一受击拦截点,直接攻击者用 event.getSource().getEntity() instanceof ServerPlayer 判定;子弹伤害另按伤害类型 id `tacz:bullet*` 识别以计减伤,全程未监听 TACZ `EntityHurtByGunEvent`,也未见按伤害源 owner 归因的代码）"。

**[Major] [A-文档与代码不符] 10.4 面板载体写原生 Screen/menu, 代码已整条删除改走 WebUI(AgentWebUiActions.java:37-39 自证)**

- 位置: 第 185, 219 行
- 文档原文: 自定义 Screen / 容器式 menu（走 JobFramework 公共 menu 脚手架，支持非方块菜单）；点击词条 → 服务端封印封包(L185); | 载体 | 悬赏板走 JobFramework 公共 menu 脚手架 | 10.4 / 10.5 |(L219)
- 代码实际: AgentScanMenu 那条原生面板路径已被整条删除(统一 UI 入口走平板 hub), 扫描与封印的唯一入口是 job.agent.scan / job.agent.seal / job.agent.state 三条 WebUiAction; com.miningdim.menu 包里只剩 AbstractMiningMenu/AbstractMiningScreen/MenuValidity/ModMenus, 无任何 Agent 相关项。
- 取证: src/main/java/com/miningdim/job/agent/AgentWebUiActions.java:38-40 "触发入口 (决策 J9): {@code AgentScanMenu} 那条原生面板路径已在本 PR 整条删除 (统一 UI 入口走平板 hub, 不为特勤单开 ad-hoc 原生入口) —— 扫描与封印的唯一入口是本类的三条 WebUI action"; 同文件 11-14 行 import com.miningdim.webui.server.WebUiServerDispatcher 等
- 建议改法: 10.4 改写为: "面板走 WebUI(MCEF 远端 React 渲染): 服务端注册 job.agent.state / job.agent.scan / job.agent.seal 三条 WebUiAction(AgentWebUiActions.java:89-91), 服务端权威做分级解密与封印九态裁决, 客户端只渲染只读态; 原 AgentScanMenu/AgentScanScreen 原生 Screen/menu 路径已于 2026-08-16(commit 9bf2f618, 决策 J9)整条删除, 统一入口收敛到平板 hub, 不再走 JobFramework menu 脚手架。" 12.0 表格"载体"一行同步改为"悬赏板载体待定, 方向应对齐 WebUI action + 平板 hub(不再沿用已废弃的原生 menu 脚手架), 具体交互形态待拍板", 并在文档中补一条指向 J9/F065 的交叉引用, 避免其余读者继续把 10.4 当作当前实现来源。

**[Major] [B-文档互相打架] 入池门槛只写 0.5% 单门，与代码、精英怪规格及本文档第 196 行自身表述的双门槛互相打架**

- 位置: 第 38 行
- 文档原文: 3. **入池门槛（防蹭枪）**：个人贡献需 ≥ 该精英总有效血的 **0.5%** 才进分配，一发枪蹭不进。
- 代码实际: 代码是"≥ BOSS 总有效血 0.5%"或"≥ 团队人均有效伤害 15%"取一即合格。ChampionStarAffix 十一章(L344)与 Champion_Effects_Guide 2.6(L118)都写了双门槛, 唯独本条只写单门; 在参战人数少、总伤害低的场景下第二道门会放进未达 0.5% 的玩家, "一发枪蹭不进"的结论并不总成立。
- 取证: src/main/java/com/miningdim/champion/reward/ContributionPool.java:31-35 "public static final double STAMP_THRESHOLD_BOSS_HP_RATIO = 0.005D; ... public static final double STAMP_THRESHOLD_TEAM_AVG_RATIO = 0.15D;"; 同文件 60-61 "boolean meetsBossThreshold = dmg >= bossTotalEffectiveHp * STAMP_THRESHOLD_BOSS_HP_RATIO; boolean meetsTeamThreshold = dmg >= teamAverageEffectiveDamage * STAMP_THRESHOLD_TEAM_AVG_RATIO;"
- 建议改法: 将 docs/SpecialAgent_Job_DesignSpec.md 第 38 行改为："3. **入池门槛（防蹭枪，盖章双门槛，取一即合格）**：个人贡献 ≥ 该精英总有效血的 **0.5%** **或** ≥ 团队人均有效伤害的 **15%**，满足其一即进分配（与 ChampionStarAffix_System_DesignSpec.md 第十一章、Champion_Effects_Guide.md 2.6 节、ContributionPool.java 的 STAMP_THRESHOLD_BOSS_HP_RATIO/STAMP_THRESHOLD_TEAM_AVG_RATIO 同口径）。正规多人团队每人份额通常远超两道门槛，单发蹭伤害两道都进不去；但人少、团队总伤害低的场景需注意团队人均门槛可能被拉低。"同时建议核查并修正本文档第 196 行与第 38 行的表述一致性（第 196 行已正确写"盖章双门槛"，只是第 38 行没跟上）。

**[Major] [A-文档与代码不符] 贡献账本口径文档笼统写"减伤后实际伤害"，未区分星级：1-5★(无血池)确为净减伤后入伤，但6★+(有血池)冠军因 ChampionBloodPoolHandler 只 cancel 不 setAmount，实际记的是净减伤前的名义入伤**

- 位置: 第 42, 180 行
- 文档原文: 伤害账本挂在精英体系的**单一受击拦截点**（见 ChampionStarAffix 9.2 净减伤单点）：每次受击按攻击者累计"减伤后实际伤害"，死亡时读账本结算。(L42); - 账本挂 ChampionStarAffix 9.2 的**单一受击拦截点**：`playerUUID → 累计减伤后伤害`(L180)
- 代码实际: 代码用 event.getAmount()(净减伤前的名义入伤)记账, 并在注释里明确声明"贡献是输出统计, 不是扣血量", 与血池扣血的净减伤刻意分离。按文档理解会把"打高减伤怪贡献占比被吃掉"当成预期行为, 实际不是。
- 取证: src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:64-67 "读最终伤害 (LOWEST), 排除已被血池取消的伤害? —— 血池 handler 取消 vanilla 伤害但 event.getAmount() 仍是净伤前的最终入伤名义值; 贡献按\"有效伤害\"口径用 event.getAmount() (玩家实际打出的有效输出), 与血池扣血的净减伤分离 (贡献是输出统计, 不是扣血量)"; 同文件 84 行 "double effectiveDamage = event.getAmount();"
- 建议改法: 把 L42、L180 的"每次受击按攻击者累计'减伤后实际伤害'"/"累计减伤后伤害"改为按星级分支说明：1-5★（无自定义血池）冠军走 ChampionBloodPoolHandler:124-129 的 `event.setAmount(netDamage)`，账本记的确是经 9.2 净减伤单点算出的净伤；6★+（有血池）冠军因 ChampionBloodPoolHandler:130-160 只 `pool.applyDamage(netDamage)` + `event.setCanceled(true)` 不回写 event，ChampionRewardHandler:85 读到的 `event.getAmount()` 是净减伤前的名义入伤（仅经默认优先级易伤放大器处理，未经冠军自身减伤，玩家实际有效输出口径，与血池扣血口径刻意分离，防止高减伤怪压低贡献占比，见 ChampionRewardHandler.java:66-68 注释）。两条分支应在文档中分别点明，不能用一句话覆盖全部星级。

**[Major] [D-覆盖缺口] 代码存在"入职标志"(activeAgent)福利门, 文档全文未提, 使第四章总表 L1/L2 行的加强奖励与伤害加成实际不可达**

- 位置: 第 50-61, 99-100, 108 行
- 文档原文: | 1 | 1 词条+星级 · 64格 | — | ×1.0 | 日1 · 接≤1★ | +5% |(L52 第四章总表); - 仅对**精英/冠军怪** +5% → +15%（线性，随级 +1%）(L108)
- 代码实际: 代码给加强奖励和对精英伤害放大加了一道 AgentBountySavedData.isActiveAgent 入职标志门: 只有"做过特勤活计"的玩家才享。而全库唯一置位点是封印申请成功(AgentSealHandler), 封印又要 L3 才解锁 —— 因此 L1/L2 干员实际上永远拿不到第四章表里写的 ×1.0 加强奖励和 +5%/+6% 伤害加成。设计文档对这道门零描述。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentDamageBonusHandler.java:52 "if (!AgentBountySavedData.get(attacker.server.overworld()).isActiveAgent(attacker.getUUID())) {"; src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:136 "if (AgentBountySavedData.get(player.server.overworld()).isActiveAgent(player.getUUID())) { grantAgentKillBonus(player, star);"; src/main/java/com/miningdim/job/agent/integration/AgentSealHandler.java:108 "AgentBountySavedData.get(agent.server.overworld()).markActiveAgent(agent.getUUID());"(全库唯一 markActiveAgent 生产调用点)
- 建议改法: 在 docs/SpecialAgent_Job_DesignSpec.md 第七章新增一节"7.0 入职标志(activeAgent)门", 说明: (1) 加强奖励(7.1)与对精英伤害放大(7.3)这两笔福利仅对 SavedData 标记为 isActiveAgent 的玩家生效, 防止用框架默认 1 级泄漏给全服(见 AgentDamageBonusHandler.java:27-29 注释); (2) 当前生产代码唯一置位入口是封印申请成功(AgentSealHandler.java:105-108), 而封印(被动类)硬性要求 AgentSkillTable.SEAL_UNLOCK_LEVEL=3(AgentSkillTable.java:34), 故 L1/L2 干员结构性拿不到第四章总表(L52-53)标注的 ×1.0/×1.25 加强奖励与 +5%/+6% 伤害加成, 需在总表旁加脚注明确这一前置条件, 或把入口如 AgentSealHandler.java:107 注释所列的待接线项(扫描探测脉冲/接悬赏/悬赏击杀记账)之一提前到 L1 即可触发, 消除入职前的空窗; (3) 说明经验(8.1 XP)刻意不受此门约束(AgentRewardHandler.java:120-125, F016 死锁修复), 避免与加强奖励/伤害加成的门控混淆。

### `docs/ChampionStarAffix_System_DesignSpec.md`

**[Minor] [B-文档互相打架] 9A.7 把特勤词条品质解锁写成 L7+, 规格与代码都是 L8**

- 位置: 第 320 行
- 文档原文: - 特勤干员探测词条列表（L7+）：逐条 `词条名 [品质]`，按品质着色；
- 代码实际: SpecialAgent 第四章总表(L59)把"全品质表+Glowing高亮"放在 L8, 代码 AgentScanField.QUALITY_TABLE 的解锁等级也是 8。L7 只解锁技能机制(SKILL_MECHANICS)。
- 取证: src/main/java/com/miningdim/job/agent/AgentScanField.java:43-44 "/** 全品质表 (每词条品质色标全解; L8 起: 第四章 \"全品质表\")。 */ QUALITY_TABLE(8),"; 同文件 40-41 "SKILL_MECHANICS(7)"
- 建议改法: 把 9A.7 第320行的"（L7+）"改为"（L8+，见 SpecialAgent_Job_DesignSpec.md 第四章总表第59行与 AgentScanField.QUALITY_TABLE(8)）"。

### `docs/SpecialAgent_Job_DesignSpec.md`

**[Minor] [A-文档与代码不符] 7.3 称伤害加成"线性随级+1%"，与代码及本文档第四章总表的 L9→L10 实际跳 2% 矛盾**

- 位置: 第 108 行
- 文档原文: - 仅对**精英/冠军怪** +5% → +15%（线性，随级 +1%）。
- 代码实际: 代码 DAMAGE_BONUS_PERCENT 为 5/6/7/8/9/10/11/12/13/15, L9→L10 跳 2 个百分点(注释明说"末级多跳 1%"); 按"线性随级 +1%"从 L1=5 推 L10 只能得 14, 与第四章总表 L10=+15% 也对不上。
- 取证: src/main/java/com/miningdim/job/agent/AgentSkillTable.java:176-186 "private static final int[] DAMAGE_BONUS_PERCENT = { 5, 6, 7, 8, 9, 10, 11, 12, 13, 15 // L10 (末级多跳 1%: +13% -> +15%) };"
- 建议改法: 将 docs/SpecialAgent_Job_DesignSpec.md:108 的"+5% → +15%（线性，随级 +1%）"改为"+5% → +15%（L1-L9 每级 +1%，L10 多跳 1 个百分点到 +15%）"，使其与同文档第四章总表（50-61 行）及 src/main/java/com/miningdim/job/agent/AgentSkillTable.java:177-187 的 DAMAGE_BONUS_PERCENT 数组保持一致。

---

## 军火商与枪匠

### `docs/Gunsmith_Component_Creation_Rules.md`

**[Critical] [B-文档互相打架] 制作规范的红冬"已确认示例"数值与JSON、GameTest断言、热更新文档四方打架**

- 位置: 第 92-104 行
- 文档原文: ### 3.3 已确认示例\n红冬高压导气是 AK 平台导气槽的势力特制组件，以伤害提升交换射速与垂直后坐表现：\n| 品质 | 伤害乘区 | 射速乘区 | 垂直后坐乘区 |\n| 普通 | `1.20` | `0.95` | `1.30` | … | 传奇 | `2.00` | `0.75` | `2.50` |
- 代码实际: 实际 JSON 里红冬高压导气的 fire_rate 五档恒为 0.75、vertical_recoil 五档恒为 3.00 (另有 recoil 全向 2.00)。文档写的 0.95→0.75 递减射速与 1.30→2.50 递增垂直后坐两列全是错的; 只有伤害列 1.20-2.00 对得上。同仓 docs/Gunsmith_Component_Hot_Reload_Rules.md:55-59 的同一张表写的是正确值, 两份文档直接互斥。
- 取证: src/main/resources/data/miningdim/gunsmith/components/red_east_high_pressure_gas.json:16-22 `"fire_rate": {"common":0.75,"improved":0.75,"milspec":0.75,"precision":0.75,"legendary":0.75}`; 同文件 61-67 `"vertical_recoil": {五档全 3.0}`; 同文件 54-60 `"recoil": {五档全 2.0}`
- 建议改法: 把 Gunsmith_Component_Creation_Rules.md 第92-104行的3.3节示例表整体替换为 Gunsmith_Component_Hot_Reload_Rules.md 第53-59行"四、红冬高压导气当前表"的权威数值(伤害1.20/1.40/1.60/1.80/2.00, 射速全×0.75, 有效射程全×0.60, 散布1.20/1.35/1.50/1.65/1.80, 全向后坐全×2.00, 额外垂直后坐全×3.00, 可引用 GunsmithStatGameTests.java:314-332 作为测试锚点), 或直接删除3.3节表格改为一句指向 Hot_Reload_Rules.md 第四节的链接, 避免同一组数值分散在两份文档各存一份互相漂移。

### `docs/Gunsmith_Gehenna_High_Speed_Gas.md`

**[Critical] [A-文档与代码不符] 格赫娜射速写成浮动系数连续公式, 实际按品质查表**

- 位置: 第 24-34 行
- 文档原文: 该组件的半自动与全自动循环射速最高提高 `25%`。实际加成由单件浮动系数决定：\n```text\n射速乘区 = 1 + (浮动系数 - 0.96) / (1.50 - 0.96) * 0.25\n```\n端点必须严格满足：\n- `0.96` 对应 `+0%`。\n- `1.50` 对应 `+25%`。
- 代码实际: 射速倍率完全由 datapack 的按品质表决定 (1.05/1.10/1.15/1.20/1.25), 单件浮动系数根本没有进入射速计算链。全库 grep `0.96` / `0.54` 只命中 GunsmithPartQuality 的品质区间定义, 没有任何一处实现这条连续公式。因此普通品质最低值就是 +5% 而不是 +0%。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:120-122 `public double fireRateMultiplier(GunsmithPartQuality quality) { return GunsmithComponentRules.get(this).fireRate(quality); }`; src/main/resources/data/miningdim/gunsmith/components/gehenna_gas.json:16-22 `"fire_rate": {"common":1.05,"improved":1.1,"milspec":1.15,"precision":1.2,"legendary":1.25}`; GunsmithStatGameTests.java:364 `double[] fireRate = {1.05D, 1.10D, 1.15D, 1.20D, 1.25D};`
- 建议改法: 3.1 节删除连续公式与 0.96/1.50 端点约束, 改为"射速按品质档位查 datapack 表: 普通 ×1.05 / 改良 ×1.10 / 军规 ×1.15 / 精密 ×1.20 / 传奇 ×1.25, 单件浮动系数不参与射速"; 第六节验收项"五个品质端点和中间值同时符合射速与上跳后坐力公式"同步改为按表逐档断言(射速部分), 并注明浮动系数(GunsmithStat 系数系统)与射速(VariantStat.FIRE_RATE)是两套互不相通的独立计算路径。

**[Critical] [A-文档与代码不符] 格赫娜后坐写成pitch最高+300%且yaw免疫, 实际是全向固定×2.00**

- 位置: 第 41-49 行
- 文档原文: - 上跳后坐力（pitch）与同一件组件的浮动系数同步变化：\n```text\n上跳后坐力乘区 = 1 + (浮动系数 - 0.96) / (1.50 - 0.96) * 3.00\n```\n- `0.96` 对应 `+0%`，`1.50` 对应 `+300%`。\n- 水平后坐力（yaw）不受该组件额外惩罚。
- 代码实际: JSON 里 recoil 是"全向"字段, 五档恒为 2.00, vertical_recoil 恒为 1.00。也就是 pitch 与 yaw 同时被乘 2.0, 既没有 +300% 上限, 也没有"yaw 不受惩罚", 更不随单件浮动系数变化。GameTest 断言写得很明确: "gehenna all-axis recoil must be fixed across qualities"。
- 取证: src/main/resources/data/miningdim/gunsmith/components/gehenna_gas.json:54-67 `"recoil": {common..legendary 全 2.0}, "vertical_recoil": {全 1.0}`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithStatGameTests.java:378-381 `assertClose(helper, variant.recoilMultiplier(quality), 2.0D, "gehenna all-axis recoil must be fixed across qualities")` 与 `verticalRecoilMultiplier(quality), 1.0D`
- 建议改法: 3.2 节改写为: 该组件五档(普通/改良/军规/精密/传奇)恒定施加全向后坐 ×2.00, pitch 与 yaw 同时生效且倍率相同; 不额外施加垂直专属倍率, 也不随单件浮动系数连续插值(与3.3节散布惩罚一样是离散定值, 而非3.1节射速那种浮动系数公式)。删除"上跳后坐力乘区=1+(浮动系数-0.96)/(1.50-0.96)*3.00"公式、"0.96对应+0%, 1.50对应+300%"端点说明与"水平后坐力(yaw)不受该组件额外惩罚"一句。第四节制造界面描述中"该件成品按实际浮动系数计算出的上跳后坐力百分比, 最高+300%"一句同步改为"固定+100%(全向)"式表述。第六节验收项"pitch 随该件实际浮动系数受到最高+300%惩罚, yaw不受额外惩罚"改为"五个品质端点的全向后坐力乘区恒为2.00(pitch与yaw相同), 不随单件浮动系数变化"。第五节"数据与兼容"提到的旧版本+40%/+100%上跳曲线兼容迁移描述本身不受影响, 可保留, 但需在其后补一句说明当前版本的目标曲线已改为固定值而非公式, 避免读者把历史迁移曲线误当成当前公式的一部分。

**[Critical] [A-文档与代码不符] 格赫娜散布写成固定+30%, 实际是随品质递增的1.03-1.15**

- 位置: 第 53-61 行
- 文档原文: - 该组件固定令实际弹道散布增加 `30%`，不随品质或单件浮动系数变化：\n```text\n格赫娜散布乘区 = 1.30\n最终散布乘区 = (1 / 护木散布系数) * 1.30\n```\n- 普通至传奇五档均显示 `散布 +30.0%`
- 代码实际: 组件规则 JSON 里 gehenna_gas 的 spread 是五档递增表 1.03/1.06/1.09/1.12/1.15, 没有任何档位是 1.30。GameTest 已把这张表钉死, 所以文档里的 1.30 不是"未实现", 而是被明确推翻的旧值。
- 取证: src/main/resources/data/miningdim/gunsmith/components/gehenna_gas.json:47-53 `"spread": { "common": 1.03, "improved": 1.06, "milspec": 1.09, "precision": 1.12, "legendary": 1.15 }`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithStatGameTests.java:365 `double[] spread = {1.03D, 1.06D, 1.09D, 1.12D, 1.15D};`; 另见 docs/Gunsmith_Component_Hot_Reload_Rules.md:67-71 的格赫娜表同样是 1.03-1.15
- 建议改法: 把 Gunsmith_Gehenna_High_Speed_Gas.md 第53-61行整段改为与 gehenna_gas.json 一致的五档表: 普通 ×1.03 / 改良 ×1.06 / 军规 ×1.09 / 精密 ×1.12 / 传奇 ×1.15; 删除"格赫娜散布乘区 = 1.30"公式、"最终散布乘区 = (1/护木散布系数) * 1.30"公式, 以及"不随品质或单件浮动系数变化""普通至传奇五档均显示 散布 +30.0%"的表述; 第六节验收项(第93行"始终使用固定 +30% 散布惩罚"、第95行"实际散布乘区固定额外乘以 1.30")同步改为"按品质取 1.03-1.15"; 并核对第85行(制造界面文案"固定 散布 +30.0%")与第100行(数据兼容小节提到的"固定 +30% 散布惩罚")两处同类表述一并修正, 确保与 docs/Gunsmith_Component_Hot_Reload_Rules.md 第67-71行的表格保持一致。

**[Critical] [A-文档与代码不符] 称格赫娜导气槽射程固定贡献1.0, 代码已改成核心系数×1.0**

- 位置: 第 65 行
- 文档原文: 格赫娜高速导气不继承基础导气的有效射程加成。装配属性计算中，该槽位对有效射程固定贡献 `1.0`，避免在已批准的射速、后坐力和散布变化之外再增加额外收益。
- 代码实际: JSON 用的是 operation=multiply、五档 values 全 1.0, 而 GunsmithGunStats.range() 先取 CORE 槽的品质浮动系数再乘组件倍率, 所以装了格赫娜导气的 AR 射程仍按该件导气自身的浮动系数放大 (传奇件可达约 1.43 倍), 不是固定 1.0。代码注释里直接写明这是对主线旧行为的有意反转。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithGunStats.java:23-24 注释 `(例: 主线把格赫娜导气核心的 range 强制写成 1.0, 本版改成写核心系数再乘组件系数)`; 同文件 191-200 `double result = coefficient(GunsmithStat.RANGE); for (PartSummary part : parts) { result = part.variant().applyRangeMultiplier(result, part.quality()); }`; GunsmithComponentRule.java:86-92 `rangeOperation == REPLACE ? configured : base * configured`; gehenna_gas.json:23-32 `"effective_range": {"operation": "multiply", "values": {全 1.0}}`
- 建议改法: 3.4 节改为: "该组件不额外提供射程倍率(规则文件 gehenna_gas.json 的 effective_range.operation=multiply、五档 values 全为 1.0), 该槽位最终对射程的贡献仍等于该件导气自身按品质浮动的系数(如传奇档区间 [1.36,1.50], 中点约 1.43), 并非固定不变"。第六节验收项"特殊导气的射程贡献为 1.0"相应改为"特殊导气自身不额外放大或缩小射程(其射程倍率恒为 1.0), 但槽位最终射程仍随该件品质浮动系数变化"。若产品意图确实是把装了格赫娜导气后的最终射程锁死为与品质无关的固定值, 则应把 gehenna_gas.json 的 effective_range.operation 从 "multiply" 改为 "replace" 并把 values 定为期望的固定倍率, 而不是仅仅修改文档描述; 且需同步更新 GunsmithStatGameTests.java 中 legendaryGehennaM4Gun 相关的期望值与第716-719行注释所述的"1.0 与 1.43 对不上属于合法存量数据"这一前提。

### `docs/Munitions_Job_DesignSpec.md`

**[Critical] [C-实现状态标错] 枪匠冲压/装配/耐久/维修全套默认被 config 关闭, 文档只字未提**

- 位置: 第 35-51, 179-186 行
- 文档原文: L179「**枪械耐久**:仅作用于 WOK 枪匠装配成品，在 `MiningDimGunsmith` 内保存独立耐久 NBT」; L180「**枪械维修**:枪械装配台兼作维修入口……全部数值落在 `miningdim-munitions.toml`」; L181「**等级门** `[gunsmith] repairUnlockLevel`（默认 4）」。十章整章标题为「十、架构与实现（DECIDED）」。
- 代码实际: 整条 gunsmith 链(冲压台右键/装配台右键/M4 组枪/TACZ 加伤与耐久事件/创造页签)都被 `[recipe] gunsmithEnabled` 单一开关门控, 默认值是 false。真服开箱状态下右键装配台只会收到「message.miningdim.gunsmith.disabled」提示, 十章 8/9 描述的耐久扣减与维修结算、3A 描述的冲压产线一条都不会发生, repairUnlockLevel/repairWorkFeeCredits 等参数全部空转。3A 只标了「(WIP)」, 十章 8/9 连 WIP 都没标。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:260-262 `GUNSMITH_ENABLED = b.comment("Enable the gunsmith press subsystem (WIP chapter 3A; keep false until material items, survival chain, gating, damage coefficients and economy sink pass review)").define("gunsmithEnabled", false);`; src/main/java/com/miningdim/job/munitions/block/GunsmithAssemblyBenchBlock.java:213-215 `if (!MunitionsConfig.GUNSMITH_ENABLED.get()) { player.displayClientMessage(Component.translatable("message.miningdim.gunsmith.disabled"), true); return InteractionResult.CONSUME; }`; src/main/java/com/miningdim/job/munitions/block/GunsmithPressBlock.java:70-71 `// 功能门 (审查 C-2/G-1~G-4): 3A 章 WIP 子系统默认关闭…` / `if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithTaczDurabilityHandler.java:31 同门; src/main/java/com/miningdim/job/munitions/ModMunitionsTab.java:51 创造页签同门
- 建议改法: 在 L35 「### 3A. 枪械配件冲压补充（WIP）」标题后补一行硬状态标注:「> 运行状态：整条枪匠链由 `miningdim-munitions.toml` 的 `[recipe] gunsmithEnabled` 统一门控（对应代码 MunitionsConfig.GUNSMITH_ENABLED, 见 GunsmithPressBlock/GunsmithAssemblyBenchBlock 的 use() 拦截、GunsmithTaczDurabilityHandler、GunsmithTaczStatsHandler 与 ModMunitionsTab 创造页签), 当前默认 false; 开启前冲压/装配/耐久/维修/加伤与创造页签全部不生效, 玩家交互只会收到 message.miningdim.gunsmith.disabled 提示。」同时把十章第 8、9 条的编号前缀从隐含的 DECIDED 改成「(已落码, 默认关闭, 见 3A 运行状态)」, 并顺带修正 L196(PENDING 第 5 条)中"十章 8/9 落地的耐久与维修 sink"这一措辞, 补上同一句默认关闭提示, 避免同文档内前后矛盾。

**[Critical] [D-覆盖缺口] 军火台电力闸门不止 Munitions_Job_DesignSpec.md 未记录,更与三份 DECIDED 文档(Power_Cable/Power_Generator/Economy_BalanceSheet)"军火商不电气化/不联动"的拍板结论正面矛盾且从未回改**

- 位置: 第 79-88, 91-108, 170-178, 200-208 行
- 文档原文: L79-88 五章「被动生产模型（DECIDED）」全文只列了料槽、缓冲满停产、离线追算、总产能=台数×速率、性能五条, 无一字提电; L196 十一 PENDING 5 里才顺带一句「制弹台目前只吃电(`[recipe] fePerRifleEquivalentRound` / `benchEnergyCapacity`)」。
- 代码实际: 电力是与料/缓冲/时间并列的第四道产线闸: `MunitionsProduction.settle` 明写「门 4: 电力能撑的最大批数」, 手动开工路径也单独设闸。默认每发步枪当量 100,000 FE, 直造一批 40 发 = 400 万 FE, 台子内部缓冲上限 3200 万 FE。没接电的军火台一发都出不来, 而六章的产能/收益表完全按无电力约束推算。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsProduction.java:166-177 `// 门 4: 电力能撑的最大批数。电不足不是停产而是减产 …` + `long maxBatchesByPower = (long) availableFe / feCostPerBatch;` + 四门取最小; 同文件 :100-106 `feCostPerBatch` = 基础批发数 × `FE_PER_RIFLE_EQUIVALENT_ROUND`; src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:440-443 `// 手动路径完全绕开 MunitionsProduction.settle(), 若不在这里单独设闸, 它就是电力限制的逃逸口。` + `if (!energy.hasAtLeast(MunitionsProduction.feCostPerBatch(level0))) return false;`; src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:278/283 `fePerRifleEquivalentRound` 默认 100_000、`benchEnergyCapacity` 默认 32_000_000
- 建议改法: 按四份文档同步刷新, 而不是只补 Munitions_Job_DesignSpec.md: 1) docs/Munitions_Job_DesignSpec.md 五章补一条"电力"闸门说明(内部 FE 缓冲 `benchEnergyCapacity` 默认3200万, 电网只push不pull; 每批耗电=该等级基础批发数×`fePerRifleEquivalentRound`默认10万FE/步枪当量发; 电不足减产不停产; 手动开工与被动结算各自设闸), 六章产能/收益表下注明"电力充足前提", 十/十二章的架构与实现拆分条目里补上电力闸门与手动路径独立设闸这一步骤。 2) docs/Power_Cable_DesignSpec.md 第24行"电气化边界: 不改造军火商或现有全仓机器为FE机器"已被 commit b28aa774(2026-08-19,晚于该文档定稿的 da0e8467 仅一天)推翻, 必须删除或改写该条(如"军火商为已批准的例外, 见 Munitions_Job_DesignSpec.md 五章"), 不能继续挂着已经作废的边界声明。 3) docs/Power_Generator_DesignSpec.md 第25行与第268行"发电机不与军火商...联动/未授权扩展"同样需要人工核实: 若发电机目前确实不能直接给军火台供电(军火台电力另有来源或独立缓冲), 需要在文档里说清"联动"具体指什么、边界卡在哪一层, 避免和已实现的军火台FE消耗产生表述矛盾; 若发电机与军火台已经或计划直接联网, 该条必须删除并走文档内声明的"先改本文并完成经济与战斗边界审定"流程。 4) docs/Economy_BalanceSheet_DesignSpec.md 第83行"军火商及全仓其他既有机器不电气化,不增FE接口或FE成本"与代码矛盾, 必须把军火台的FE消耗(默认上限3200万FE缓冲、每步枪当量发10万FE)补记入四A能源系统技术账, 作为一个真实的电力sink, 否则总表统计的faucet/sink核对不完整。 以上四处修订应一次性同步, 避免再次出现"代码先改、一份文档补记、另外三份DECIDED文档不回改"的连锁遗漏。

### `docs/Gunsmith_Blueprint_Fire_Mode_Policy.md`

**[Major] [A-文档与代码不符] 射击模式策略漏掉三连发枪机分支，硬规则被该分支直接违反**

- 位置: 第 22 行
- 文档原文: - 成品缺少任一源模式、增加源枪没有的模式、列表为空，或顺序发生变化，均为数据错误，必须拒绝。
- 代码实际: AR三连发枪机走的是另一条分支 GunsmithFireModePolicy.forceThreeRoundBurst: 装配时 assembledGunId 被换成 miningdim:<template>_gunsmith_burst, 那份枪数据的 fire_mode 只有 ["burst"], 源枪的 auto/semi 被成建制丢掉——恰恰是本条"必须拒绝"的情形, 而且该方法强制要求成品列表必须正好等于 [burst] 才放行。整份文档只描述了 preserveAndSelectFirst 一条路径, 对 forceThreeRoundBurst、替代枪数据与 burstCount/continuousBurst 校验只字未提。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithFireModePolicy.java:19-25 `public static <T> T forceThreeRoundBurst(...) { ... if (!assembledFireModes.equals(List.of(burstMode))) { throw new IllegalArgumentException("Three-round-burst firearm must expose only burst mode"); }`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithGunFactory.java:39-51 `boolean forceThreeRoundBurst = blueprint.platform() == GunsmithPlatform.AR && gunId.equals(burstGunId(blueprint)); ... return new ResourceLocation(MiningConstants.MODID, blueprint.templateId() + "_gunsmith_burst");`; src/main/resources/assets/miningdim/custom/miningdim_gunsmith/data/miningdim/data/guns/m4a1_gunsmith_burst_data.json:47-48 `"fire_mode": ["burst"], "burst_data": {"continuous_shoot": false, "count": 3, "bpm": 900, "min_interval": 0.3}`
- 建议改法: 在“未来图纸硬规则”一节新增“三连发枪机例外”小节，写明：当装配用枪机 variant().forcesBurstFireMode() 为真时（GunsmithAssemblyRecipe.assembledGunId(stack, parts) 第61-68行），assembledGunId 改指向 GunsmithGunFactory.burstGunId(blueprint) 对应的 `<template>_gunsmith_burst` 替代枪数据，装配走 GunsmithFireModePolicy.forceThreeRoundBurst 而非 preserveAndSelectFirst；该分支下成品模式列表必须恰好为 [burst]（不要求与源枪逐项相等）、burstCount 必须为 3、continuous_shoot 必须为 false，否则拒绝装配。同时把第22行的硬规则改为“普通装配路径（preserveAndSelectFirst）下……”，明确其不适用于 forceThreeRoundBurst 分支；并在“验证清单”补充三条断言：assembledFireModes 恰为 [burst] 时通过、burstCount != 3 时抛异常、continuousBurst 为 true 时抛异常。

### `docs/Gunsmith_Bullpup_Components.md`

**[Major] [E-状态过期] 无托文档称装配台固定Size=12且其他尺寸直接报错, 现为14且遗漏Size=13迁移路径**

- 位置: 第 38 行
- 文档原文: 新增 BULLPUP 平台和 RECEIVER 枚举后，装配台固定为 `Size=12`。本次实现同步提供两条明确迁移路径：`Size=11` 保留图纸与原有部件槽 `0-9`，将旧输出槽 `10` 移到新输出槽 `11`，新的 RECEIVER 槽 `10` 保持为空；`Size=8` 保留旧步枪图纸与六个部件槽 `0-6`，将旧输出槽 `7` 移到新输出槽 `11`。其他尺寸视为损坏或未知格式并直接报错，不使用静默默认值。
- 代码实际: 槽位数由 GunsmithPressPart 的枚举长度推导, 后续加入 BIPOD 与 FIRING_PIN 后 SLOT_COUNT 已是 1+12+1=14。loadInventory 现在接受五种尺寸: 14(当前)、13(pre-firing-pin)、12(pre-bipod)、11(pre-receiver)、8(rifle-only), 全部走迁移而不是报错。文档里的"固定 Size=12"和"其他尺寸直接报错"两句都已失效, 且与 docs/Gunsmith_Sniper_Components.md:41"装配台旧 Size=13 存档会保留原槽位"互相打架。
- 取证: src/main/java/com/miningdim/job/munitions/block/GunsmithAssemblyBenchBlockEntity.java:44-47 `SLOT_PART_BASE = 1; SLOT_OUTPUT = SLOT_PART_BASE + GunsmithPressPart.values().length; SLOT_COUNT = SLOT_OUTPUT + 1;`（GunsmithPressPart.java:4-15 共 12 项）; 同文件 482-505 依次判定 LEGACY_PRE_FIRING_PIN_SLOT_COUNT(13) / LEGACY_PRE_BIPOD_SLOT_COUNT(12) / LEGACY_PRE_RECEIVER_SLOT_COUNT(11) / LEGACY_RIFLE_SLOT_COUNT(8) 并分别迁移
- 建议改法: 将 docs/Gunsmith_Bullpup_Components.md 第39行"装配台迁移"一节改写为: "装配台槽位数随 GunsmithPressPart 枚举增长(参见 GunsmithAssemblyBenchBlockEntity.SLOT_COUNT), 当前为 Size=14(图纸槽0 + 12个部件槽1-12 + 输出槽13)。历史存档 Size=13(前撞针)/12(前双脚架, 即本文档最初记录的'固定值')/11(前机匣, 无托平台引入RECEIVER前)/8(仅步枪六槽)均按各自路径自动迁移到当前布局, 只有不在这五个值之内的尺寸才视为损坏并报错, 不使用静默默认值。本平台引入 RECEIVER 时 Size=12 曾是当时的固定值, 现已是历史迁移路径之一。" 不要引用 Gunsmith_Component_Hot_Reload_Rules.md 第十二节(该节讲的是成枪数据版本v1-v7的NBT兼容, 与此处装配台槽位迁移无关); 同时建议在 docs/Gunsmith_Sniper_Components.md:41 和本文档处各自保留迁移路径描述, 但都改为引用同一权威来源(GunsmithAssemblyBenchBlockEntity.java 中的 SLOT_COUNT/LEGACY_*_SLOT_COUNT 常量), 避免每次新增部件枚举后再次各平台文档各说各话。

### `docs/Gunsmith_Component_Balance_Roadmap.md`

**[Major] [D-覆盖缺口] 路线图基础组件平台枚举漏掉霰弹枪与冲锋枪**

- 位置: 第 27 行
- 文档原文: 当前已存在的导气、枪管、枪机、枪托、护木、握把，以及各平台对应的手枪、无托、精确射手和狙击部件，均属于基础组件。机枪平台的基础组件规格另见[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)
- 代码实际: GunsmithPlatform 现有 9 个平台, 除文中列出的 AR/AK/手枪/无托/精确射手/狙击/机枪外还有 SHOTGUN(index 7) 与 SMG(index 8), 各自有完整的基础组件与贴图 (霰弹枪 4 槽 20 张, 冲锋枪 5 槽 25 张)。本节是"基础组件定位"的权威枚举, 漏两个平台会让后续按此文做平衡评审时直接漏掉两条产线。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPlatform.java:57-67 `SHOTGUN("shotgun", "gunsmith.platform.shotgun", List.of(STOCK, BARREL, BOLT, HANDGUARD)), SMG("smg", "gunsmith.platform.smg", List.of(BARREL, STOCK, RECEIVER, HANDGUARD, GRIP))`; 资源侧 textures/item 下 gunsmith_part_shotgun_* 20 张、gunsmith_part_smg_* 25 张
- 建议改法: 将 docs/Gunsmith_Component_Balance_Roadmap.md 第 27 行的枚举句从"当前已存在的导气、枪管、枪机、枪托、护木、握把，以及各平台对应的手枪、无托、精确射手和狙击部件，均属于基础组件。机枪平台的基础组件规格另见[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)"改为"当前已存在的导气、枪管、枪机、枪托、护木、握把，以及各平台对应的手枪、无托、精确射手、狙击和霰弹枪、冲锋枪部件，均属于基础组件。机枪平台的基础组件规格另见[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)，霰弹枪与冲锋枪平台的基础组件规格另见[霰弹枪平台组件规格](Gunsmith_Shotgun_Components.md)、[冲锋枪枪匠基础组件](Gunsmith_SMG_Components.md)"。保留原文"狙击"用词不变，不要将其改写为"栓动式步枪"；不要把已单独链接处理的"机枪"重新塞回行内枚举，避免与既有句式结构重复。

**[Major] [E-状态过期] 路线图状态行与"当前非目标"第一条仍称只有格赫娜一件特殊组件落地，与代码已注册的7个特殊variant（含改射速与强制三连发）不符**

- 位置: 第 3 行
- 文档原文: > 状态：基础组件与稀有组件的分层原则已确认；首个特殊射速组件"格赫娜高速导气"已按独立规格进入实现。除该独立规格外，本文出现的其他具体部件、百分比和惩罚组合仍为说明性示例，不代表数值已经定案。\n（L136）- 不因格赫娜高速导气的落地自动注册其他稀有组件、极致强化组件或射速组件。
- 代码实际: BASE 之外已注册 7 个特殊 variant: 格赫娜高速导气、红冬高压导气、MK-AX-A枪机、圣三一精密刻度 AR 枪管、AR三连发枪机、圣三一精密刻度狙击枪管、赤雪-A枪机。其中红冬高压导气 (fire_rate 0.75) 与 MK-AX-A (1.05) 都是改射速的组件, AR三连发枪机还直接改写了射击模式。第六节"当前非目标"列的四条里至少前两条已经被现实突破。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:8-26 依次登记 GEHENNA_GAS / RED_EAST_HIGH_PRESSURE_GAS / MK_AX_A_BOLT / TRINITY_PRECISION_GRADUATED_BARREL / AR_THREE_ROUND_BURST_BOLT / TRINITY_PRECISION_GRADUATED_SNIPER_BARREL / RED_WINTER_CHIXUE_A_BOLT; src/main/resources/data/miningdim/gunsmith/components/ 下对应 7 个规则 JSON; red_east_high_pressure_gas.json:16-22 `"fire_rate": {五档全 0.75}`
- 建议改法: 1) 第3行状态行改为："状态：基础组件与稀有组件的分层原则已确认；截至当前已落地7个特殊组件（格赫娜高速导气、红冬高压导气、MK-AX-A枪机、圣三一精密刻度AR枪管、AR三连发枪机、圣三一精密刻度狙击枪管、红冬赤雪-A枪机），具体规则与数值见[枪匠特殊组件热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第四至十一节；本文出现的其他具体部件、百分比和惩罚组合仍为说明性示例，不代表数值已经定案。" 2) 第六节"当前非目标"第136行（"不因格赫娜高速导气的落地自动注册其他稀有组件、极致强化组件或射速组件"）应删除或改写为"新增稀有组件、极致强化组件或射速组件仍须逐件独立规格与评审，不得由已落地组件的先例直接类推免审"。 3) 第137行（"不把 `+30%`、`+60%`、其他未单独批准的特殊导气或示例惩罚视为已经确定的数值与内容"）经核实并未被现实突破（7个已落地variant均未采用这两个占位数值），保留原文，不需修改。

**[Major] [A-文档与代码不符] 路线图 2.2 节(第43行)绝对化措辞未给已上线的"强制射击模式"类组件(AR三连发枪机)开例外，易误判其违规或误判第六节 L139 承诺被打破（L139 本身实际未被突破）**

- 位置: 第 43 行
- 文档原文: 基础组件和普通图纸还必须保留源枪的完整、有序射击模式列表。射速变化与射击模式变化是两个不同概念：未来即使某个稀有组件调整 RPM，也不得因此增加、删除、替换或重排 `auto`、`semi`、`burst` 等模式。
- 代码实际: 已注册的改装级组件 AR三连发枪机 (ar_three_round_burst_bolt) 正是靠删除源枪的 auto/semi、只保留 burst 来实现的, forcesBurstFireMode() 是枚举上的一等方法。L139 第六节"不改变现有图纸保存的射击模式列表及顺序"同样已被突破。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:18-20 `AR_THREE_ROUND_BURST_BOLT("ar_three_round_burst_bolt", ..., GunsmithPartRarity.MODIFIED, null)`; 同文件 78-80 `public boolean forcesBurstFireMode() { return this == AR_THREE_ROUND_BURST_BOLT; }`; src/main/resources/assets/miningdim/custom/miningdim_gunsmith/data/miningdim/data/guns/m16a1_gunsmith_burst_data.json:28 `"fire_mode": ["burst"]`
- 建议改法: 在 docs/Gunsmith_Component_Balance_Roadmap.md 第43行所在段落末尾（2.2 节）追加一段例外说明，措辞建议：  "唯一已批准的例外是 AR三连发枪机（改装级，参见[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)第3.4节、其五档数值表见[枪匠新组件热重载规则](Gunsmith_Component_Hot_Reload_Rules.md)第九节）。该组件不调整 RPM（五档 RPM 倍率恒为 ×1.00），而是通过 `GunsmithGunFactory.burstGunId()` 路由到一支独立注册、预先固定为纯 `burst` 的成品枪数据（如 `miningdim:m16a1_gunsmith_burst`），并由 `GunsmithFireModePolicy.forceThreeRoundBurst()` 强制校验三连发不变量；图纸自身保存的 `gunId`（如 `tacz:m16a1`）与 TaCZ 源枪数据本身不受任何改写，第六节关于'不改变现有图纸保存的射击模式列表及顺序、不改写 TaCZ 源枪数据'的承诺仍然成立。除该已批准例外外，禁止任何组件对已注册枪支的射击模式列表做增删替换或重排。"  不需要修改第139行本身（其描述与现状一致），可在该条末尾追加一句交叉引用（例如"（AR三连发枪机例外见2.2节末尾说明）"），避免读者把 L139 误读为已被打破。不要引用 Gunsmith_Blueprint_Fire_Mode_Policy.md 作为该例外的出处——该文件通读全文未提及三连发强制机制。

### `docs/Gunsmith_Component_Creation_Rules.md`

**[Major] [B-文档互相打架] 制作规范的势力清单漏掉蔚蓝重工, 与命名计划11条不一致(第6条措辞也不统一)**

- 位置: 第 137-148 行
- 文档原文: 枪匠组件当前允许使用以下制造势力或集团标识：\n1. 红冬。 2. 山海经。 3. 千禧年。 4. 格赫娜万魔殿。 5. 格赫娜。 6. 格赫娜风纪委员会。 7. 百鬼夜行。 8. 阿拜多斯。 9. 凯撒 PMC。 10. 圣三一。
- 代码实际: 命名计划 L71-85 的同一份清单有 11 条, 第 11 条正是"蔚蓝重工（六角 B 字＋铁砧工业徽记，已定稿）"。而蔚蓝重工恰恰是代码里真实存在的四个势力之一 (MK-AX-A枪机的制造方), 有 160x160 LOGO 资源。制作规范把"当前允许使用"的清单写成 10 条, 等于把唯一一个已落地且不在传统三家里的势力排除在外。第 6 条两份文档还写成"格赫娜风纪委员"与"格赫娜风纪委员会"两个名字。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithFaction.java:5-8 `RED_WINTER("red_winter"), GEHENNA("gehenna"), BLUE_HEAVY_INDUSTRIES("blue_heavy_industries"), TRINITY("trinity")`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:13-14 `MK_AX_A_BOLT("mk_ax_a_bolt", ..., GunsmithPartRarity.PROTOTYPE, GunsmithFaction.BLUE_HEAVY_INDUSTRIES)`; src/main/resources/assets/miningdim/textures/gui/gunsmith/factions/blue_heavy_industries.png (160x160)
- 建议改法: 在 Gunsmith_Component_Creation_Rules.md 5.1 节(第137-148行)补第11条"蔚蓝重工（六角 B 字＋铁砧工业徽记）", 并将第144行"格赫娜风纪委员会"与 Gunsmith_Component_Naming_Plan.md 第80行"格赫娜风纪委员"统一为同一措辞(以命名计划为准, 或两处都改"格赫娜风纪委员会"); 更稳妥的做法是删除 Creation Rules 里这份重复清单, 改为一行"当前势力清单以《枪匠组件命名计划》(Gunsmith_Component_Naming_Plan.md)第74-85行表格为准", 避免后续新增势力(如蔚蓝重工)时再次出现两处清单不同步。

### `docs/Gunsmith_Component_Naming_Plan.md`

**[Major] [D-覆盖缺口] 命名计划的槽位类别清单与属性映射漏掉套筒/扳机/击锤/机匣/撞针五类**

- 位置: 第 20 行
- 文档原文: | 槽位类别 | 枪械上的装配位置；当前基础组件包括导气、枪管、枪机、枪托、护木、握把，机枪平台另有脚架。 | `导气`、`脚架` | 组件类型或平台 |\n（L35）现有基础槽位均使用"基础"：基础导气、基础枪管、基础枪机、基础枪托、基础护木、基础握把；机枪平台的脚架显示为"基础脚架"。
- 代码实际: GunsmithPressPart 现有 12 类槽位, 除文档列出的 7 类外还有 SLIDE(基础套筒)、TRIGGER(基础扳机)、HAMMER(基础击锤)、RECEIVER(基础机匣)、FIRING_PIN(基础撞针), 全部已有 gunsmith.part.* 与 gunsmith.slot.* 本地化并在手枪/无托/狙击/SMG 平台上实际使用。命名计划作为显示规则的权威文档用"均使用"的口吻给出了一份不完整的清单, 3.3 节 L51 的属性说明映射同样只覆盖 AR/AK 六槽。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPressPart.java:10-15 `SLIDE("slide", "gunsmith.part.slide", ...), TRIGGER(...), HAMMER(...), RECEIVER("receiver", "gunsmith.part.receiver", ...), BIPOD(...), FIRING_PIN("firing_pin", "gunsmith.part.firing_pin", ...)`; src/main/resources/assets/miningdim/lang/zh_cn.json:656-659 `"gunsmith.part.slide": "基础套筒", "gunsmith.part.trigger": "基础扳机", "gunsmith.part.hammer": "基础击锤", "gunsmith.part.receiver": "基础机匣"`; 同文件 1224 `"gunsmith.part.firing_pin": "基础撞针"`
- 建议改法: 把 L20 与 L35 的槽位类别清单补全为 12 类：导气(CORE)、枪管(BARREL)、枪机(BOLT)、护木(HANDGUARD)、握把(GRIP)、枪托(STOCK)、套筒(SLIDE)、扳机(TRIGGER)、击锤(HAMMER)、机匣(RECEIVER)、脚架(BIPOD)、撞针(FIRING_PIN)；3.3 节 L51 的属性映射需按平台补充：手枪(伤害用击锤/后坐用套筒/散布用扳机)、无托与狙击/SMG(伤害用机匣)、机枪(操控用脚架)、狙击(瞄准稳定用撞针)，或改为链接各平台组件规格表，避免读者误以为只有 AR/AK 六槽存在属性映射。

### `docs/Gunsmith_Gehenna_High_Speed_Gas.md`

**[Major] [C-实现状态标错] 称格赫娜暂不增加解锁门槛, 实际已有特种级等级门与2.5倍工费**

- 位置: 第 69 行
- 文档原文: 本轮试制沿用 `CORE` 槽位的三种基础材料成本，再按品质材料倍率和制作时间计算；暂不额外增加特殊材料或解锁门槛。
- 代码实际: 格赫娜是 SPECIAL 稀有度, 冲压时既要过 rarityUnlockSpecial=6 的军火商等级门, 工费又要再乘 pressRarityFeeMultiplierSpecial=2.5, 两者都在服务端冲压结算里硬判。docs/Gunsmith_Component_Naming_Plan.md:130-131 已把这两条记为"已落地", 只有本规格还停在"暂不增加"。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:354 `PRESS_RARITY_FEE_MULTIPLIER_SPECIAL = b.defineInRange("pressRarityFeeMultiplierSpecial", 2.5D, 1.0D, 20.0D);`; 同文件 360 `RARITY_UNLOCK_SPECIAL = b.defineInRange("rarityUnlockSpecial", 6, 1, 10);`; src/main/java/com/miningdim/job/munitions/block/GunsmithPressBlockEntity.java:232-239 `int rarityUnlockLevel = MunitionsConfig.rarityUnlockLevel(rarity); if (level < rarityUnlockLevel) { ... } long fee = MunitionsConfig.pressWorkFeeCredits(selectedQuality, rarity);`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:8-9 `GunsmithPartRarity.SPECIAL`
- 建议改法: 第四节改为"材料仍沿用 CORE 槽位三种基础材料与品质材料倍率; 因属特种级稀有度, 冲压另受 rarityUnlockSpecial（默认 L6）等级门与 pressRarityFeeMultiplierSpecial（默认 2.5）工费倍率约束, 具体见[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)第 5.1 节"。

**[Major] [A-文档与代码不符] 格赫娜稳定variant ID写成gehenna_high_speed_gas, 实际是gehenna_gas**

- 位置: 第 7 行
- 文档原文: - 稳定变体 ID：`gehenna_high_speed_gas`
- 代码实际: 枚举里的稳定 id 是 `gehenna_gas`, `gehenna_high_speed_gas` 只是 byId 里保留的存量物品迁移别名。更关键的是: datapack 规则文件名必须精确等于稳定 id, 运维照本文档把覆盖文件命名成 gehenna_high_speed_gas.json 会被加载器忽略并只留一条 ERROR, 平衡改动静默不生效。同仓 Hot_Reload_Rules:45 已经点名这三个别名不是合法文件名。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:8 `GEHENNA_GAS("gehenna_gas", "gunsmith.variant.gehenna_gas", 10000, ...)`; 同文件 165-167 `if ("gehenna_high_speed_gas".equals(id)) { return GEHENNA_GAS; }`; GunsmithComponentRuleLoader.java:28-34 注释 `刻意不复用 GunsmithPartVariant.byId: 那里保留了 basic / gehenna_high_speed_gas / mk_ax_a_receiver 三个存量物品迁移用的历史别名`; 内置规则文件实际名为 src/main/resources/data/miningdim/gunsmith/components/gehenna_gas.json
- 建议改法: 第一节改为"稳定变体 ID：`gehenna_gas`（`gehenna_high_speed_gas` 仅为存量物品迁移别名, 不可用作 datapack 文件名）"; 第五节 L88 的"非 AR、非导气槽使用 `gehenna_high_speed_gas` 必须明确拒绝"同步换成 `gehenna_gas`。

**[Major] [E-状态过期] 格赫娜规格仍写成枪数据版本v5, 当前已是v7**

- 位置: 第 84 行
- 文档原文: - 新成枪数据版本为 v5，每个已装组件显式保存 `variant`，属性区保存射速乘区、最终上跳后坐力乘区、基础散布系数和最终散布乘区。
- 代码实际: 成枪数据当前版本是 7。v5/v6 已经变成需要迁移的旧版 (v5 五槽精准射手补握把、v6 四槽栓动式步枪补撞针)。按本文档把新成枪写成 v5 会直接被 GunsmithGunStats 的版本判定路由到 legacy 迁移分支。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithGunStats.java:33 `public static final int CURRENT_VERSION = 7;`; 同文件 91-95 `if (version == CURRENT_VERSION) { validateCurrentStats(); } else { validateLegacyStats(); }`; docs/Gunsmith_Component_Hot_Reload_Rules.md:153 `枪匠成枪数据版本 v7 只在 NBT 中缓存由品质浮动系数推导出的基础属性`
- 建议改法: 第五节把"新成枪数据版本为 v5"改为 v7, 并把"v3/v4 格赫娜成枪兼容迁移"一段改成指向 Gunsmith_Component_Hot_Reload_Rules.md 第十二节(存量枪兼容)所述的现行迁移链(v4 MK-AX-A 机匣回枪机、v5 补握把、v6 补撞针), 不再在本文重复维护一份独立的版本号叙述, 避免两份文档各写一套版本号导致后续再次漂移。

### `docs/Gunsmith_Machine_Gun_Components.md`

**[Major] [C-实现状态标错] 机枪状态行称仅文档与本地化已登记, 实际部件已可冲压获取为真实物品**

- 位置: 第 3 行
- 文档原文: > 状态：文档规格与本地化已登记；不代表已有机枪图纸、成品枪、配方或可获取物品。
- 代码实际: 机枪平台早已不是"只有文档与本地化": 枚举登记了 5 个槽位, 25 张 64x64 贴图与 25 个叶子模型全部就位并进了 gunsmith_part.json 的 CMD override, 冲压机可以选中 MACHINE_GUN 平台并冲压 BIPOD 等部件, GameTest 覆盖了平台索引、槽位集合与非法组合拒绝。真正仍不存在的只有机枪图纸与成品枪这一条。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPlatform.java:51-56 `MACHINE_GUN("machine_gun", "gunsmith.platform.machine_gun", List.of(HANDGUARD, BOLT, BARREL, STOCK, BIPOD))`; src/main/java/com/miningdim/job/munitions/block/GunsmithPressGameTests.java:268 `helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MACHINE_GUN.index()), ...)` 与 286 `press.trySelectPart(compactRow(GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.BIPOD))`; 资源侧 src/main/resources/assets/miningdim/models/item/ 下 gunsmith_part_machine_gun_* 共 25 个叶子模型, textures/item 下同名贴图 25 张
- 建议改法: 将 docs/Gunsmith_Machine_Gun_Components.md 第 3 行状态语句改为: "状态：平台枚举(index=6)、五类组件槽位顺序、CMD 范围、贴图与模型均已落地并接入冲压机产线, 冲压完成后可获得对应部件物品; 仍未创建机枪图纸与成品枪, 也未提供整枪装配/合成路径。" 第六节"图纸与 TaCZ 绑定边界"关于"不新增或虚构机枪图纸、成品枪、配方"的表述本身仍成立可保留, 但其中"或可获取物品"一句与本次修正后的第 3 行状态描述同样存在事实冲突(部件本身已是可获取物品), 建议一并核实是否需要同步措辞, 避免同一文档内出现新的自相矛盾。

### `docs/Gunsmith_SMG_Components.md`

**[Major] [E-状态过期] 冲锋枪文档称不创建装配图纸,实际已有5张SMG图纸且已被GameTest覆盖**

- 位置: 第 3 行
- 文档原文: 冲锋枪作为独立枪匠平台追加在现有平台之后，固定平台索引为 `8`。本轮只接入基础组件与五档品质资源，不创建或改动装配图纸。
- 代码实际: GunsmithBlueprint 枚举里已登记 5 张 SMG 图纸: UZI、UMP45、HK_MP5A5、STERLING(wyyc1991:stl)、MPX(ccrp:mpx), 并有对应的 gunsmith_blueprint_smg 图标与 iconModelData=5。平台索引 8 那部分是对的, 只有"不创建装配图纸"一句已过期, 且本文件是该平台唯一的规格文档, 读者无从得知图纸已存在。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithBlueprint.java:29-33 `UZI("uzi", GunsmithPlatform.SMG), UMP45("ump45", GunsmithPlatform.SMG), HK_MP5A5("hk_mp5a5", GunsmithPlatform.SMG), STERLING("wyyc1991", "stl", "wyyc.stl.name", GunsmithPlatform.SMG), MPX("ccrp", "mpx", "ccrp.gun.mpx.name", GunsmithPlatform.SMG)`; 同文件 102 `case SMG -> 5;`; src/main/resources/assets/miningdim/textures/item/gunsmith_blueprint_smg.png
- 建议改法: 将 docs/Gunsmith_SMG_Components.md 第3行的"本轮只接入基础组件与五档品质资源，不创建或改动装配图纸。"改为:"本轮同时登记了5张SMG平台装配图纸:tacz:uzi、tacz:ump45、tacz:hk_mp5a5、wyyc1991:stl、ccrp:mpx(见 GunsmithBlueprint.java:29-33),各图纸沿用其来源枪包原有的射击模式列表,不做改动。"并补充说明该文档现为SMG平台图纸的唯一规格来源,与 GunsmithAssemblyBusinessGameTests 中的图纸目录测试(blueprintCatalogUsesPlatformSpecificPartSets、firstWaveBlueprintsMatchGunPackIdsAndNameKeys)保持一致。

### `docs/Gunsmith_Shotgun_Components.md`

**[Major] [E-状态过期] 霰弹枪文档称本阶段不创建图纸与成品枪, 实际已有4张(且与文档同一提交一并落地)**

- 位置: 第 37 行
- 文档原文: 本规格只登记霰弹枪平台与基础组件，不在此阶段创建霰弹枪图纸或成品枪。
- 代码实际: GunsmithBlueprint 已登记 4 张霰弹枪图纸: M870、M1887_LONG(ccrp:m1887_long)、KSG(hare:ksg)、M1014, 并有 gunsmith_blueprint_shotgun 图标与 iconModelData=6。本文件是该平台唯一规格文档, 末句会让读者得出与代码相反的结论。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithBlueprint.java:25-28 `M870("m870", GunsmithPlatform.SHOTGUN), M1887_LONG("ccrp", "m1887_long", "ccrp.gun.m1887_long.name", GunsmithPlatform.SHOTGUN), KSG("hare", "ksg", "hare.gun.ksg.name", GunsmithPlatform.SHOTGUN), M1014("m1014", GunsmithPlatform.SHOTGUN)`; 同文件 101 `case SHOTGUN -> 6;`; src/main/resources/assets/miningdim/textures/item/gunsmith_blueprint_shotgun.png
- 建议改法: 将 docs/Gunsmith_Shotgun_Components.md 第37行末句改写为: "本规格登记霰弹枪平台与基础组件; 图纸已随同一版本落地, 当前绑定 tacz:m870、ccrp:m1887_long、hare:ksg、tacz:m1014 四张, 成品保留各源枪原有射击模式列表", 或仿照 docs/Gunsmith_Sniper_Components.md 的写法新增一节"图纸绑定"登记这4张图纸及其 gunId/命名空间, 避免读者(含后续维护者)误判该平台仍缺图纸而重复实现。

### `docs/Gunsmith_Sniper_Components.md`

**[Major] [D-覆盖缺口] 九个枪械平台只有七份组件文档, AR 与 AK 整体缺失**

- 位置: 第 1(以本文件为对照锚点; 缺失项为 docs/Gunsmith_AR_Components.md 与 docs/Gunsmith_AK_Components.md) 行
- 文档原文: docs/ 下平台组件文档共七份: Gunsmith_Bullpup_Components.md / Handgun / Machine_Gun / Marksman / SMG / Shotgun / Sniper_Components.md。每份都给出该平台的部件清单、平台 index、CMD 区间表与属性映射表(本文件 L9-23 即为该体例)。AR 与 AK 无对应文档。
- 代码实际: 代码注册九个平台。AR(index 0)与 AK(index 1)不仅是首发平台, 还承载七个特殊组件型号中的五个(格赫娜高速导气、红冬高压导气、MK-AX-A 枪机、圣三一精密刻度 AR 枪管、AR 三连发枪机, 另加 AK 的赤雪-A 枪机)。二者的部件清单、CMD 区间(AR 为 001-055、AK 为 101-155)与属性映射在全仓文档中没有任何一处给出。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPlatform.java:12-18 「AR("ar", "gunsmith.platform.ar", EnumSet.of(CORE, BARREL, BOLT, HANDGUARD, GRIP, STOCK))」, :19-25 「AK("ak", "gunsmith.platform.ak", EnumSet.of(...))」, 共九个枚举常量至 :67 SMG。特殊组件归属见 GunsmithPartVariant.java:61-73, 其中 :61 GEHENNA_GAS->AR/CORE, :62-63 RED_EAST_HIGH_PRESSURE_GAS->AK/CORE, :64-65 MK_AX_A_BOLT->AR/BOLT, :66-67 TRINITY_PRECISION_GRADUATED_BARREL->AR/BARREL, :68-69 AR_THREE_ROUND_BURST_BOLT->AR/BOLT, :72-73 RED_WINTER_CHIXUE_A_BOLT->AK/BOLT。文档侧机械取证: grep 全部 docs/*.md 的 CMD 区间只命中 211-215(Handgun:27)、401-455(Marksman:15-22)、511-595(Sniper:15-21)等, 无任何 0xx/1xx 段。
- 建议改法: 新建 docs/Gunsmith_AR_Components.md 与 docs/Gunsmith_AK_Components.md, 沿用 Marksman/Sniper 的体例登记六部件(CORE/BARREL/BOLT/HANDGUARD/GRIP/STOCK)、平台 index 0/1、CMD 区间(AR 001-055、AK 101-155, 公式 platformIndex*100+partOrdinal*10+qualityIndex+1)与属性映射(BOLT 伤害/BARREL 爆头/CORE 射程/STOCK 后坐/HANDGUARD 散布/GRIP 操控, 见 GunsmithStat.java:28-44), 并逐项登记该平台已实现的特殊组件型号与稀有度; 同时在 Munitions_Job_DesignSpec.md 第 3A 节补上这两条链接。

**[Major] [D-覆盖缺口] 狙击平台专属特殊组件圣三一狙击枪管全文零记载**

- 位置: 第 全文 43 行(属性映射表在 27-33) 行
- 文档原文: L5「SNIPER 平台显示为"栓动式步枪"...使用五类枪匠部件」; L27-33 属性映射表仅列 RECEIVER/STOCK/BARREL/HANDGUARD/FIRING_PIN 五行, 其中「| BARREL（枪管） | 爆头，具体为爆头倍率 |」; 全文 43 行无一处出现「圣三一」「variant」或「稀有度」。
- 代码实际: 代码已注册 TRINITY_PRECISION_GRADUATED_SNIPER_BARREL, 稀有度 ADVANCED(尖端级), 且 supports() 明确限定只装在 SNIPER 平台的 BARREL 槽。它是 SNIPER 平台唯一的非基础组件型号, 但 SNIPER 平台的权威组件文档对它零记载。这同时违反命名计划自己定的硬规则。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPartVariant.java:21-23 「TRINITY_PRECISION_GRADUATED_SNIPER_BARREL("trinity_precision_graduated_sniper_barrel", "gunsmith.variant.trinity_precision_graduated_sniper_barrel", 10600, GunsmithPartRarity.ADVANCED, GunsmithFaction.TRINITY)」; 同文件 :70-71 「case TRINITY_PRECISION_GRADUATED_SNIPER_BARREL -> platform == GunsmithPlatform.SNIPER && part == GunsmithPressPart.BARREL;」。规则侧: docs/Gunsmith_Component_Naming_Plan.md:98 「枪匠设计文档、组件规格、平衡表和实现清单只要列出具体组件型号，就必须逐项记录组件型号稀有度」, :99 「已实现型号的文档值必须与 GunsmithPartVariant 的固定稀有度映射及本文件第 3.4 节同步」。交叉佐证: 全库 grep「圣三一」只命中 Gunsmith_Component_Creation_Rules/Hot_Reload_Rules/Naming_Plan/Munitions_Job_DesignSpec 四份, 不含本文档。
- 建议改法: 在 docs/Gunsmith_Sniper_Components.md 的「属性映射」与「装配、迁移与图纸绑定」之间补一节「特殊组件」, 按命名计划 3.4/3.5 体例登记: 型号「圣三一精密刻度狙击枪管」、variant `trinity_precision_graduated_sniper_barrel`、稀有度「尖端级(ADVANCED)」、势力「圣三一」、占用槽位 BARREL、CMD 基数 10600(=10601-10605)、以及对最大耐久的 0.70 倍代价(GunsmithPartVariant.java:84)。

### `docs/Munitions_Job_DesignSpec.md`

**[Major] [D-覆盖缺口] 军火商WebUI远程面板(两条action)在设计文档中完全不存在**

- 位置: 第 170-187, 200-208 行
- 文档原文: 十章「架构与实现」共 9 条、十二章「实现拆分」共 8 条, 全文无「WebUI」「面板」「远程」任何字样(整份文档 grep `WebUI|webui|面板|平板` 零命中)。
- 代码实际: 军火商已落地一套 WebUI 远程面板服务端能力: `job.munitions.state` 返回军火台/冲压机/装配台三台机器的只读镜像(含口径、缓冲、进度、产出、benchesPlaced/benchCap), `job.blueprints` 返回图纸静态全表。它带有明确的只读纪律(绝不触发结算, 免得开面板变成产能加速器)和按区块半径就近取台位的妥协设计, 这些约束一旦被后人改写就会直接破坏产线经济, 但文档里没有任何登记。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsWebUiActions.java:35 `军火商面板的两条 WebUiAction: job.munitions.state (三台机器的远程只读镜像) 与 job.blueprints (图纸静态表)。`; 同文件 :38-41 `两条都不写任何状态。特别是 job.munitions.state <b>绝不</b>调 MunitionsBenchBlockEntity#onAccess 或 settleForOwner —— 那条路径会扣工费、扣料、发经验。`; 同文件 :44-46 按 `SEARCH_CHUNK_RADIUS` 区块半径就近取台位; 配套测试 src/main/java/com/miningdim/job/munitions/MunitionsWebUiGameTests.java
- 建议改法: 在十章新增第10条"WebUI 面板: 两条只读 action job.munitions.state / job.blueprints(MunitionsWebUiActions, 经 MunitionsSystem.register 调 registerAll 注册进 WebUiServerDispatcher)。铁律: 面板刷新绝不触发 onAccess/settleForOwner, 否则开面板即产能加速器; 台位按发送者所在维度、以其所在区块为心的 SEARCH_CHUNK_RADIUS(4)区块半径内已加载区块就近扫描, pos=null 语义是"半径内没扫到"而非"没造过", 全局台数以 benchesPlaced/benchCap 为准(SavedData 按 UUID 计, 跨维度权威)。"

**[Major] [A-文档与代码不符] 十章「产弹」步骤引用的 onServerUpdate 方法不存在, 且把「访问时才物化」的 compileOnly 隔离设计误写成「生产时直接 build()」**

- 位置: 第 173 行
- 文档原文: L173「2. **产弹**:`onServerUpdate`/时间戳追算消耗料 → `AmmoItemBuilder.create().setId(口径).setCount(N).build()` 入缓冲;缓冲满停产。」
- 代码实际: 军火台的服务端驱动入口叫 `serverTick()`, 全 `job/munitions` 包里没有任何 `onServerUpdate`(全库仅 `champion/ChampionSystem.java` 的一句注释里出现过这个词)。按文档去搜 `onServerUpdate` 找不到产弹入口。另外缓冲里存的是 int 计数, `AmmoItemBuilder` 物化发生在 `refreshOutputStack`(主人在线访问帧), 不是「入缓冲」那一步。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:478 `public void serverTick() {`; `grep -rn "onServerUpdate" src/main/java/com/miningdim/job/munitions/` 零命中; src/main/java/com/miningdim/job/munitions/MunitionsAmmoFactory.java:96 `return AmmoItemBuilder.create().setId(ammoId).setCount(count).build();`(物化点在工厂, 由 refreshOutputStack 调用)
- 建议改法: L173 改为:「2. **产弹**:`MunitionsBenchBlockEntity.serverTick`/时间戳追算消耗料 → 缓冲只记发数与口径(`bufferedRounds`/`bufferedCaliber`, int 权威);取弹/开界面等主人在线访问帧由 `MunitionsAmmoFactory.materialize` 经 `AmmoItemBuilder.create().setId(口径).setCount(N).build()` 物化成真弹(tick 路径不触 TACZ, 服务 compileOnly 构建与 dev GameTest 隔离);缓冲满停产。」并建议在该句旁补一句指向 MunitionsBenchBlockEntity.java 71-73 行注明的 compileOnly 铁律, 避免读者据此在 tick 路径里直接调用 AmmoItemBuilder。

**[Major] [D-覆盖缺口] 手动/连续制作双模式与台主专属操作权文档零覆盖**

- 位置: 第 19-26, 79-88 行
- 文档原文: L23「**被动产弹**:制造台随时间自动消耗料、产弹,存进缓冲区」; L81「类农夫的"塞料→定时产→回收"模型」——全文只描述被动挂机一种产出方式, 也没有任何关于台子归属/上锁/谁能按按钮的规定。
- 代码实际: 实现是被动挂机与手动开工**双模式共存、天然互斥**: 界面上有「开工 / 取消 / 连续」三个按钮(菜单按钮 id 210/211/212), 开工期由手动路径接管、空闲时才放行被动结算；并且这三个动作全部只对台主(`isOwner`)开放, 产量按台主等级算、工费扣台主、经验入台主。这套交互是审查 M-1/M-3 定稿的结果, 设计文档一个字都没跟上。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:419-422 `// 归属收敛 (审查 M-3): 开工限台主 … 访客 (含未锁台) 只能看不能开。` + `if (level == null || !isOwner(player) || craftingActive || selectedCaliber == null) return false;`; 同文件 :625-632 `/** 双模式互斥 (审查 M-1): 返回 true 表示本帧由手动制作接管 (开工中), false 表示空闲 —— 调用方 settleForOwner 据此放行被动挂机结算 */`; src/main/java/com/miningdim/job/munitions/menu/MunitionsBenchMenu.java:39-42 `BUTTON_TOGGLE_LOCK = 200; BUTTON_START_CRAFT = 210; BUTTON_CANCEL_CRAFT = 211; BUTTON_TOGGLE_CONTINUOUS = 212;`; src/main/java/com/miningdim/job/munitions/client/MunitionsBenchScreen.java:608/626/632 三个按钮已接线
- 建议改法: 在五章"被动生产模型"补一条:"**双模式**:空闲时走被动挂机结算(离线追算), 台主点『开工』后转入手动单批制作, 可开『连续』自动续批;两模式互斥, 开工期不再走被动结算。开工/取消/连续/上锁四个操作一律限台主, 产量-工费-经验三者同源于台主等级。"并建议在该条旁注明此决策的既定来源(参见 docs/Munitions_Workbench_Branch_Review.md 审查 M-1/M-2/M-3), 避免后续再次出现"代码定稿、设计文档未回填"的漂移。

**[Major] [A-文档与代码不符] 称GUI复用TACZ制枪台贴图,实际四张贴图全是自研miningdim资源**

- 位置: 第 33, 172 行
- 文档原文: L33「**GUI 复用 TACZ 制枪台贴图**(我方 Screen 引用 `tacz:textures/...`,不拷贝其 PNG → 不触再分发协议;TACZ 客户端硬依赖,资源恒在)。……GUI 仍引用 TACZ 贴图。」; L172「GUI 走 JobFramework 公共 menu 脚手架 + 引用 TACZ 制枪台 GUI 贴图」
- 代码实际: `MunitionsBenchScreen` 引用的四张贴图全部是本模组自有资源(`miningdim:textures/gui/container/munitions_bench.png` / `munitions_ui_font.png` / `munitions_titles.png` / `munitions_ammo_profiles.png`), 对应 PNG 文件都在仓库里。整个 `job/munitions/client` 包里没有任何 `tacz:` 贴图引用。文档据此推出的「不触再分发协议」「TACZ 资源恒在」两条论据现在都已失效。
- 取证: src/main/java/com/miningdim/job/munitions/client/MunitionsBenchScreen.java:24-31 `private static final ResourceLocation BG = new ResourceLocation(MiningConstants.MODID, "textures/gui/container/munitions_bench.png");` 以及 UI_FONT / TITLES / AMMO_PROFILES 三个同样以 `MiningConstants.MODID` 为命名空间的常量; 资源实体 src/main/resources/assets/miningdim/textures/gui/container/munitions_bench.png 等四份均存在; `grep -rn "tacz:textures" src/main/java/com/miningdim/job/munitions/client/` 零命中
- 建议改法: L33 第三条改为:"**GUI 为自研贴图**(`miningdim:textures/gui/container/munitions_bench.png`/`munitions_ui_font.png`/`munitions_titles.png`/`munitions_ammo_profiles.png` 共四张),世界模型为自研 GeckoLib 骨骼模型,两者均不再引用 TACZ 资源;对 TACZ 的依赖只剩运行期弹药物化 API(`AmmoItemBuilder`)。"并删去原文"不拷贝其 PNG → 不触再分发协议;TACZ 客户端硬依赖,资源恒在"这一段已失效的论据。 L172 删去"+ 引用 TACZ 制枪台 GUI 贴图",改为:"GUI 走 JobFramework 公共 menu 脚手架(自研贴图);世界模型为自研 GeckoLib 骨骼模型, 经 `MunitionsBenchRenderer`(GeoBlockRenderer)渲染。" 顺带修正:L202"军火台 block + BE + 公共 menu GUI(引用 TACZ 贴图)"同步改为"军火台 block + BE + 公共 menu GUI(自研贴图)",避免同一处过期表述在文档内留下第三份副本。

**[Major] [F-结构问题] 同一模块 15 份枪匠文档平铺 docs 根, 无子目录无聚合索引**

- 位置: 第 35 行
- 文档原文: L35「### 3A. 枪械配件冲压补充（WIP）」—— 该节是枪匠体系在主规格里的唯一入口
- 代码实际: wok-job-munitions 一个模块散着 15 份文档: docs/Gunsmith_*.md 共 13 份, 加 Munitions_Job_DesignSpec.md 与 Munitions_Workbench_Branch_Review.md, 全部平铺在 docs/ 根。这 13 份 Gunsmith 文档内部还混了至少五种子类型(平台组件规格 6 份、制作规范 1 份、命名计划 1 份、平衡路线图 1 份、热更新运维规则 1 份、单组件规格 1 份、图纸策略 1 份), 文件名前缀看不出类型差异, 也没有任何一份承担索引职责。docs/ 根按主题前缀聚类的结果是 Gunsmith 13 / WebUI 5 / TaskSpec 5 / Power 5 / Economy 4, 五个簇占了 32 份, 全无层级。
- 取证: `ls docs/Gunsmith_*.md | wc -l` = 13; docs/modules/module-registry.json 里这些文档对应的是单一模块 `wok-job-munitions`(docs/modules/README.md:35「| 职业 | WOK-军火商模块 | `wok-job-munitions` | `job/munitions` |」), 代码侧 src/main/java/com/miningdim/job/munitions/ 是一个包。仓库在 docs/modules/ 已经有按模块建子目录的现成惯例(chef/experience/farmer), 枪匠这 15 份没有沿用。
- 建议改法: 建 docs/spec/gunsmith/ 收纳 13 份 Gunsmith 文档, 加一份 docs/spec/gunsmith/README.md 作为枪匠体系索引, 按「体系规则(命名/制作规范/平衡路线/热更新) / 平台组件(7 个平台) / 单组件规格 / 图纸策略」四组登记并标注各自状态。Munitions_Job_DesignSpec.md:35 的 3A 节改为只链该索引一处, 不再逐份手抄(现在只链了 4 份、漏 9 份)。Power(5 份)与 WebUI(5 份)两簇按同样方式处理。

**[Major] [F-结构问题] 3A作为枪匠体系入口只链了4份子文档, 漏登记9份已存在的枪匠文档**

- 位置: 第 37, 40, 45, 56 行
- 文档原文: 3A 节全部外链只有四处: L37「[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)」「[枪匠组件平衡与后续扩展路线](Gunsmith_Component_Balance_Roadmap.md)」、L40「[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)」、L56「[枪匠图纸射击模式策略](Gunsmith_Blueprint_Fire_Mode_Policy.md)」。
- 代码实际: docs/ 下另有 9 份枪匠文档无人登记: Gunsmith_Bullpup_Components.md(无托式步枪五件套)、Gunsmith_Handgun_Components.md(手枪五件套)、Gunsmith_Marksman_Components.md(精准射手六部件)、Gunsmith_SMG_Components.md(冲锋枪, 自称平台索引 8)、Gunsmith_Shotgun_Components.md、Gunsmith_Sniper_Components.md(栓动五类部件)、Gunsmith_Gehenna_High_Speed_Gas.md(首个特殊组件)、Gunsmith_Component_Creation_Rules.md(新组件制作规范)、Gunsmith_Component_Hot_Reload_Rules.md(特殊组件热更新规则)。它们对应的平台与组件在代码里都已实装, 但从核心设计文档出发找不到任何入口——唯独机枪这个**还没有图纸**的平台被链了。
- 取证: 平台实装侧: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPlatform.java:26-60 `PISTOL/BULLPUP/MARKSMAN/SNIPER/MACHINE_GUN/SHOTGUN` 及 SMG 均已定义部件集; 特殊组件实装侧: src/main/resources/data/miningdim/gunsmith/components/ 下 gehenna_gas.json、trinity_precision_graduated_sniper_barrel.json、mk_ax_a_bolt.json 等 7 份规则数据包已落库; 被漏登记的文档文件确实存在于 docs/(Gunsmith_Bullpup_Components.md 首行 `# 无托式步枪枪匠组件说明` 等)
- 建议改法: 把 L40 的单条机枪链接扩成一张平台文档索引表(平台 → 组件规格文档: AR/AK 见本节正文, PISTOL→Gunsmith_Handgun_Components.md, BULLPUP→Gunsmith_Bullpup_Components.md, MARKSMAN→Gunsmith_Marksman_Components.md, SNIPER→Gunsmith_Sniper_Components.md, MACHINE_GUN→Gunsmith_Machine_Gun_Components.md, SHOTGUN→Gunsmith_Shotgun_Components.md, SMG→Gunsmith_SMG_Components.md), 并在 3A 末尾补一行「通用规范：[枪匠新组件制作规范](Gunsmith_Component_Creation_Rules.md)、[枪匠特殊组件热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)；特殊组件实例：[格赫娜高速导气组件规格](Gunsmith_Gehenna_High_Speed_Gas.md)」。

**[Major] [B-文档互相打架] 3A(L40)称冲压机"当前平台先列AR、AK"，但冲压台代码(GunsmithPressBlockEntity)已实现全部9平台选择与部件校验，docs/下另有6份平台专属组件文档(Bullpup/Handgun/Marksman/SMG/Shotgun/Sniper)未被链接，且同文档L179/L183、GunsmithBlueprint(21图纸/7平台)均已列出更多平台**

- 位置: 第 40 行
- 文档原文: L40「当前平台先列 AR、AK；机枪平台的组件规格见[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)。制作 AR 与 AK 均使用六类组件……」——而同一份文档 L179 写「初始耐久和每次维修永久损失按 AR、AK、霰弹枪、栓动步枪、精准射手、冲锋枪、手枪等平台分开配置」, L183 更列出「AR / AK / 精准射手 / 机枪 / 霰弹枪……手枪……无托式步枪 / 冲锋枪……栓动式步枪」九类平台的替换件映射。
- 代码实际: `GunsmithPlatform` 已有 9 个平台(AR/AK/PISTOL/BULLPUP/MARKSMAN/SNIPER/MACHINE_GUN/SHOTGUN/SMG), 各自带自己的部件集(手枪五件套含 SLIDE/TRIGGER/HAMMER, 栓动含 FIRING_PIN, 机枪含 BIPOD); `GunsmithBlueprint` 已有 21 张图纸, 覆盖 AR/MARKSMAN/AK/PISTOL/SHOTGUN/SMG/SNIPER 七个平台。L40 的「先列 AR、AK」已被自己文档的十章和代码双向推翻。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithPlatform.java:11-60 `AR(...)`、`AK(...)`、`PISTOL("pistol", ..., EnumSet.of(BARREL, SLIDE, GRIP, TRIGGER, HAMMER))`、`BULLPUP(...)`、`MARKSMAN(...)`、`SNIPER(..., List.of(RECEIVER, STOCK, BARREL, HANDGUARD, FIRING_PIN))`、`MACHINE_GUN(..., List.of(HANDGUARD, BOLT, BARREL, STOCK, BIPOD))`、`SHOTGUN(...)` 及 SMG; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithBlueprint.java:16-36 共 21 个枚举常量 `M4A1/M16A1/M16A4/HK416D/SPR15HB/AK47/RPK/TYPE_81/M1911/M870/M1887_LONG/KSG/M1014/UZI/UMP45/HK_MP5A5/STERLING/MPX/KAR98K/SMLE_III/M700`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithGunDurability.java:146-151 `repairPart` 按九平台分流
- 建议改法: 将 L40 改为类似:"机械冲压机已支持全部9个平台的选择与部件校验(见 GunsmithPlatform):AR、AK、手枪、无托式步枪、精准射手、栓动式步枪、机枪、霰弹枪、冲锋枪；各平台部件集与组件规格见对应平台文档——[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)、[无托式步枪组件说明](Gunsmith_Bullpup_Components.md)、[手枪组件说明](Gunsmith_Handgun_Components.md)、[精准射手组件说明](Gunsmith_Marksman_Components.md)、[栓动式步枪组件说明](Gunsmith_Sniper_Components.md)、[霰弹枪组件说明](Gunsmith_Shotgun_Components.md)、[冲锋枪组件说明](Gunsmith_SMG_Components.md)。已出图纸(GunsmithBlueprint)覆盖其中 AR/AK/手枪/精准射手/栓动式步枪/霰弹枪/冲锋枪七个平台，无托式步枪与机枪平台仍只有组件规格、尚无图纸或成品枪。"，同时清理该行与十章(L179/L183)之间的表述落差，避免读者误以为冲压机只支持AR/AK两个平台。

**[Major] [E-状态过期] 3A第50行仍把`M4装配模板`试作验证描述为装配主线, 实际主线已是21张图纸(GunsmithBlueprint)体系, M4模板降级为legacy兼容分支**

- 位置: 第 50 行
- 文档原文: L50「试作验证：`M4装配模板` 消耗 AR 六件套并生成 TACZ 默认枪包 `tacz:m4a1`；零件品质与浮动系数写入枪械 NBT……」
- 代码实际: 装配体系早已从单一 M4 模板演进为 `GunsmithBlueprint` 图纸体系(21 张图纸、7 个平台、支持第三方枪包命名空间 ccrp/hare/wyyc1991/lavender)。`m4_assembly_template` 物品仍注册, 但在 `GunsmithAssemblyRecipe` 里只剩一条 legacy 兼容分支, 硬映射回 `GunsmithBlueprint.M4A1`。文档把一个存量兼容项写成当前主线, 会让读者以为装配只支持 M4。
- 取证: src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithAssemblyRecipe.java:24-26 `if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) { return true; }` 与 :35-37 `if (stack.is(...M4_ASSEMBLY_TEMPLATE.get())) { return GunsmithBlueprint.M4A1; }`; src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithBlueprint.java:16-36 共 21 张图纸; src/main/java/com/miningdim/job/munitions/block/GunsmithAssemblyBusinessGameTests.java:667 `ItemStack legacy = new ItemStack(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get());` (测试自身以 legacy 命名)
- 建议改法: 将 docs/Munitions_Job_DesignSpec.md 第 50 行改写为:「装配走 `枪匠图纸`(`GunsmithBlueprint`,当前 21 张,覆盖 AR/AK/精准射手/手枪/霰弹枪/冲锋枪/栓动七个平台,其中部分图纸绑定 ccrp/hare/wyyc1991/lavender 等第三方枪包命名空间);旧 `M4装配模板` 物品保留为存量兼容项,等价于 M4A1 图纸(见 GunsmithAssemblyRecipe.isBlueprint/blueprint 中的兼容分支)。零件品质与浮动系数写入枪械 NBT,并通过 TACZ 属性缓存事件影响枪机伤害、枪管爆头倍率、导气射程、枪托后坐力、护木散布、握把开镜/瞄准散布以及特殊枪机规则——该机制对全部图纸通用,不限于 M4。」同时建议在该段末尾补一句指向 Gunsmith_Blueprint_Fire_Mode_Policy.md 中的 legacy M4 规则章节,避免与 3B 节脱节。

**[Major] [A-文档与代码不符] 发射药提炼被描述成L6军火商用火药+铜炼制的独立工序,实际是任何等级/任何玩家用8火药即可合成、且L1-L5同样需要投入的普通中间品**

- 位置: 第 65, 72, 174, 205 行
- 文档原文: L65「**发射药**:**高级军火商(L6+)用 火药 + 铜 提炼而成**(中间品),提炼后造弹**翻倍**。」; L72 表格行「| 提炼(L6+) | 7 铜 + 16 火药 → 提炼成发射药 → **70 发** |」; L174「3. **提炼(L6+)**:火药+铜 → 发射药(中间物品);发射药 → 弹(高产)。」; L205「4. 提炼子系统(L6+):火药+铜→发射药→弹。」
- 代码实际: `miningdim:propellant` 是普通的无序工作台配方, 输入 8 个 `minecraft:gunpowder`, **不含铜**, 也**没有任何职业或等级门**——1 级号甚至非军火商都能合成。L6 解锁的不是「提炼」这道工序, 而只是把同一批四件套料的产出基数从 40 发提到 70 发(`REFINED_ROUNDS_PER_BATCH`), 投入料完全不变。文档描述的「提炼子系统」在代码里根本不存在独立工序。
- 取证: src/main/resources/data/miningdim/recipes/munitions/propellant.json:2-32 `"type": "minecraft:crafting_shapeless"` + 8 个 `{"item": "minecraft:gunpowder"}` → `{"item": "miningdim:propellant", "count": 1}` (全文无 copper_ingot, 无 conditions); src/main/java/com/miningdim/job/munitions/MunitionsProduction.java:108-114 `int baseRounds = MunitionsLevels.isRefineUnlocked(level) ? MunitionsConfig.REFINED_ROUNDS_PER_BATCH.get() : MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get();` (等级只切基数, 不换配方); src/main/java/com/miningdim/job/munitions/MunitionsLevels.java:117-119 `isRefineUnlocked(level) { return level >= MunitionsConfig.REFINE_UNLOCK_LEVEL.get(); }`
- 建议改法: L65 改为:「**发射药**:任何玩家用 8 火药在工作台合成的中间品(`miningdim:propellant`,配方无铜、无等级门),L1-L10 产弹均需投入该中间品(与铜制的底火/弹壳/弹头一起构成四件套);L6+ 的『提炼』体现为**同一批四件套料的产出基数翻倍**(40→70 发),不是另一道以铜为原料的独立提炼工序。」 L72 表格两行统一改写为「配方」列「1 底火(2铜)+1 弹壳(3铜)+1 弹头(2铜)+2 发射药(=16 火药)」,「解锁」列区分「L1」产出 40 发、「L6」产出 70 发,去掉"提炼(L6+)"这一暗示配方结构随等级变化的行标题。 L174 改写为:「3. **产出加成(L6+)**:四件套(底火+弹壳+弹头+发射药)投入不随等级变化;L6+ 仅将同一批料的产出基数从 40 发提升到 70 发。」 L205 改写为:「4. 产出倍增(L6+):四件套配方全等级通用,L6+ 只切换产出基数(40→70),不存在独立的提炼子系统。」

**[Major] [A-文档与代码不符] 五章料槽写成铜/火药/发射药,实际是四件套(底火/弹壳/弹头/发射药)五槽**

- 位置: 第 83 行
- 文档原文: L83「**军火台**(自建方块 + BlockEntity):料槽(铜/火药/发射药)+ 进度 + 输出缓冲区。」
- 代码实际: 军火台是 5 个容器槽: 底火(0)/弹壳(1)/弹头(2)/发射药(3)/输出缓冲(4), 铜锭与火药**不能**直接塞进台子, 必须先在工作台合成成四件套零件。文档描述的「铜/火药/发射药」正是已被迁移掉的旧 4 槽布局, 代码里还留着专门的迁移分支把它转换成新布局。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:87-93 `/** 槽位: 0=底火, 1=弹壳, 2=弹头, 3=发射药, 4=输出缓冲展示 (四件套见 MunitionsConfig recipe 组)。 */` + `SLOT_PRIMER = 0; SLOT_CASING = 1; SLOT_BULLET_HEAD = 2; SLOT_PROPELLANT = 3; SLOT_OUTPUT = 4;` `SLOT_COUNT = 5`; 同文件 :96-97 `/** 旧档 4 槽布局 (F015 迁移用): legacy 0/1 无对应料槽, legacy 2=发射药, legacy 3=输出。 */ private static final int LEGACY_FOUR_SLOT_COUNT = 4;`; src/main/java/com/miningdim/job/munitions/menu/MunitionsBenchMenu.java:37 `CONTAINER_SLOTS = 5` 与 :58-66 四个料槽 + 一个 OutputSlot
- 建议改法: 将 docs/Munitions_Job_DesignSpec.md:83 改为:「- **军火台**(自建方块 + BlockEntity):四件套料槽(底火/弹壳/弹头/发射药)+ 输出缓冲槽 + 进度。铜锭与火药不直接入台,须先在工作台按配方(recipes/munitions/primer.json、casing.json、bullet_head.json、propellant.json)合成四件套零件后再塞入军火台。」

**[Major] [D-覆盖缺口] 六档军火台方块与档位等级钳制机制文档零覆盖**

- 位置: 第 91-108, 170-178 行
- 文档原文: L95-106 的 6.1 产能曲线表只有「制造台数 / 每台速率 / 缓冲/台 / 可造口径」四列, 全文把产能只归因于职业等级; L108「制造台向系统购买(信用点 sink + 进阶目标),拥有数受等级上限约束。」十章也只在 L172 顺带提「六档各一套 geo」的美术事实。
- 代码实际: 代码里军火台是**六个独立注册方块**, 每个方块带 `unlockLevel` 与 `maxEffectiveLevel` 两个参数, 后者会把台主的有效军火商等级**夹断**——例如 L10 台主在 `munitions_bench_medium` 上只按 L4 结算(速率 95、缓冲 1000、口径门到霰弹为止、提炼不解锁)。这条钳制直接决定产能与可造口径, 文档读者完全看不到它的存在, 也看不出旧注册名 `munitions_bench` 是唯一 (1,10) 全档台。
- 取证: src/main/java/com/miningdim/job/munitions/ModMunitionsBlocks.java:27-32 `MUNITIONS_BENCH = registerBench("munitions_bench", 1, 10);` / `MUNITIONS_BENCH_MEDIUM = registerBench("munitions_bench_medium", 3, 4);` / `..._HIGH = registerBench(..., 5, 6);` / `..._SUPERIOR = registerBench(..., 7, 8);` / `..._TRANSCENDENT = registerBench(..., 9, 9);` / `..._RADIANT = registerBench(..., 10, 10);`; src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlock.java:91-93 `public int effectiveLevelFor(int playerLevel) { return Math.min(clampLevel(playerLevel), maxEffectiveLevel); }`; src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:758-764 `effectiveOwnerLevel` 每次结算/选口径/开工都过这道钳制
- 建议改法: 在 6.1 产能曲线表(docs/Munitions_Job_DesignSpec.md L95-106)后新增「6.1b 军火台档位」小节,列出六个注册名 munitions_bench(1,10)/munitions_bench_medium(3,4)/munitions_bench_high(5,6)/munitions_bench_superior(7,8)/munitions_bench_transcendent(9,9)/munitions_bench_radiant(10,10) 对应的 (unlockLevel, maxEffectiveLevel),并在紧邻 6.1 表格处明确一句:"单台有效等级 = min(职业等级, 台子 maxEffectiveLevel),速率/缓冲/可造口径/提炼解锁均按有效等级而非职业等级本身结算";同时注明旧注册名 munitions_bench 出于存量兼容保留全档(1,10)能力。十章(L170-178)第1条也应补一句台子档位与美术资产(六档各一套 geo)的对应关系,避免读者把 L33 的"六档"美术描述误当作纯外观差异。

### `docs/Munitions_Workbench_Branch_Review.md`

**[Major] [E-状态过期] 报告头部结论仍是"不可合并"、清单全未勾选, 与自身末尾的闭环记录打架**

- 位置: 第 9, 159-172, 183, 251-263 行
- 文档原文: L9「- 结论: **不可合并**。1 项 Critical, 11 项 Major, 6 项 Minor; 2 个 required GameTest 失败。」; L183「整分支维持不可合并。」; L161-172 十二项合并前置清单**全部是 `- [ ]` 未勾选**; 而同一文件 L301「# 修复记录 (2026-07-12): 全部立案项闭环, 分支达合并态」、L327「merge origin/main 后 **652 required GameTest 全绿**, 零回归。分支达合并态。」
- 代码实际: 分支早已合入 main, 清单里的修复在代码里逐条可验: M-5 旧注册名恢复 (1,10)、M-7 quickMoveStack 目标区间止步输出槽、M-1 settleManualCraft 空闲返 false、M-4 四件套合成表落地。文档既无归档横幅, 头部结论又与末尾自相矛盾, 一个只读头部的人会得出「军火台分支不可合并」的完全错误结论。另注: L255-263 的增量清单里 G-3(冲压台归属)、G-4(破坏 dropContents)、alloy/polymer 物品注册三项**至今确实未修**(靠 gunsmithEnabled=false 挂起), 所以不能一律勾上。
- 取证: M-5 已修: src/main/java/com/miningdim/job/munitions/ModMunitionsBlocks.java:25-27 `// 存量兼容 (审查 M-5): 旧注册名 "munitions_bench" … 旧名保持全档能力 (1,10)` + `registerBench("munitions_bench", 1, 10)`; M-7 已修: src/main/java/com/miningdim/job/munitions/menu/MunitionsBenchMenu.java:140 `this.moveItemStackTo(stackInSlot, 0, MunitionsBenchBlockEntity.SLOT_OUTPUT, false)`; M-1 已修: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:634-636 `if (!craftingActive || craftingCaliber == null) { return false; }`; M-4 已修: src/main/resources/data/miningdim/recipes/munitions/{primer,casing,bullet_head,propellant}.json 四份齐全; 反之 G-3/G-4 仍未修: `grep -n "owner" GunsmithPressBlockEntity.java` 零命中, `grep -n "onRemove|dropContents|playerWillDestroy" GunsmithPressBlock.java` 零命中
- 建议改法: 在 L1 标题下加归档横幅:「> 状态: 已归档历史记录。本分支已于 2026-07-12 完成全部立案项处置并合入 main(详见文末『修复记录』), 头部『不可合并』结论仅代表 2026-07-06 当时的快照, 不代表现状。」并把 L161-172 十二项改为 `- [x]`; L255-263 的增量清单按实际状态逐条标注——C-2/G-1/G-2/G-5 及 receiver、itemGroup 键标 `- [x]`(已独立核实: GunsmithPressPart.java:13 有 RECEIVER、en_us.json:775 有 itemGroup.miningdim_gunsmith 键、GunsmithPressGameTests.java 等三个测试文件共 3781 行已落地), G-3、G-4、alloy/polymer 注册三项保持 `- [ ]` 并加注「随 gunsmithEnabled=false 挂起, 代码层未落地(GunsmithPressBlockEntity.java 无 owner 字段, GunsmithPressBlock.java 无 onRemove/dropContents)」。

### `docs/Gunsmith_Component_Creation_Rules.md`

**[Minor] [A-文档与代码不符] 格赫娜"已确认简介"文案与实际发布的语言键不一致**

- 位置: 第 125-129 行
- 文档原文: 格赫娜高速导气的已确认简介为：\n```text\n经过格赫娜学院加压调教过的高速导气箍，使其射速变得更快，当然，后坐力也有所增高。\n```
- 代码实际: 实际发布的简介是"经过格赫娜学院加压调教过的高速导气箍，使其射速变得更快，代价是散布和后坐力增高。", 后半句不同且多了"散布"这一项代价。本文件 L131 自己要求"文案、数据与实战结果必须一致", 却把一份未同步的文案标成"已确认"。另外 zh_cn.json 里还遗留一条旧键 gunsmith.variant.gehenna_high_speed_gas.description, 内容是第三份文案（与 Gehenna 规格第二节相同）, 三处并存。
- 取证: src/main/resources/assets/miningdim/lang/zh_cn.json:1252 `"tooltip.miningdim.gunsmith_part.description.variant.gehenna_gas": "经过格赫娜学院加压调教过的高速导气箍，使其射速变得更快，代价是散布和后坐力增高。"`; 同文件 675 遗留旧键 `"gunsmith.variant.gehenna_high_speed_gas.description": "由格赫娜万魔殿研发的新型高速导气组件。…"`
- 建议改法: 把 docs/Gunsmith_Component_Creation_Rules.md 第128行的代码块替换成语言文件里当前实际渲染的文案"经过格赫娜学院加压调教过的高速导气箍，使其射速变得更快，代价是散布和后坐力增高。"(对应 zh_cn.json:1252 的 tooltip.miningdim.gunsmith_part.description.variant.gehenna_gas 键);同时在 docs/Gunsmith_Gehenna_High_Speed_Gas.md 第二节(第18行)注明该长文案已被替换为该 tooltip 键的简版文案,或直接统一指向这一个键,避免文档与 zh_cn.json:675 的遗留死键(gunsmith.variant.gehenna_high_speed_gas.description)三处文案并存造成后续维护者误改。

### `docs/Gunsmith_Component_Hot_Reload_Rules.md`

**[Minor] [F-结构问题] 枪匠13份文档中7份无任何入链, 含运维唯一权威的热更新规则**

- 位置: 第 1 行
- 文档原文: # 枪匠特殊组件热更新规则（文件首行标题, 全文无"关联文档"引用块, 也无任何其他 md 指向它）
- 代码实际: 全仓 md 里搜文件名, Gunsmith_Component_Hot_Reload_Rules、Gunsmith_Handgun_Components、Gunsmith_SMG_Components、Gunsmith_Shotgun_Components、Gunsmith_Marksman_Components、Gunsmith_Sniper_Components、Gunsmith_Bullpup_Components 七份命中数为 0, 即没有任何文档链接到它们。而 Gunsmith_Machine_Gun_Components 被 Munitions_Job_DesignSpec:40 与 Naming_Plan:52 引到, Creation_Rules/Naming_Plan/Balance_Roadmap/Gehenna/Fire_Mode_Policy 也都有入链。热更新规则是运维改 datapack 平衡的唯一权威文件, 却从任何入口都走不到。docs/ 下也没有总索引 (TaskSpec_INDEX.md 只收 TaskSpec_*)。
- 取证: 代码侧取证方式说明: 这是文档树结构问题, 代码侧无对应实体, 故定为 Minor。可复现命令 `rg -n "Gunsmith_Component_Hot_Reload_Rules" --glob '*.md' .` 返回空; 对照 docs/Munitions_Job_DesignSpec.md:40 `机枪平台的组件规格见[机枪平台组件规格](Gunsmith_Machine_Gun_Components.md)` 是唯一被引到的平台规格。相关实体侧旁证: 该文档描述的加载器确实存在于 src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithComponentRuleLoader.java:25 `DIRECTORY = "gunsmith/components"`, 说明它不是废弃文档。
- 建议改法: 在 docs/Munitions_Job_DesignSpec.md 第 3A 节的关联文档块里按平台补齐 7 条链接(手枪/无托/精准射手/栓动/机枪/霰弹枪/冲锋枪组件规格), 并把[枪匠特殊组件热更新规则]加进 Gunsmith_Component_Creation_Rules.md 与 Gunsmith_Component_Naming_Plan.md 的关联文档引用块; 或新建 docs/Gunsmith_INDEX.md 统一登记这13份, 由 docs/TaskSpec_INDEX.md 之外单独维护。

### `docs/Munitions_Job_DesignSpec.md`

**[Minor] [C-实现状态标错] PENDING 3 "速率需换算成每批tick" 的换算已实现并有默认值**

- 位置: 第 194 行
- 文档原文: L194「3. **生成耗时绝对值**(速率表是"发/时"口径,需换算成每批 tick)。」——列在「十一、PENDING（待定）」下。
- 代码实际: 换算已落地并可用: config 提供 `[timing] ticksPerRateHour`(默认 72000, 即一个真实小时), `MunitionsProduction.ticksPerRound` 按 `ticksPerRateHour / ratePerTable` 向上取整给出每发 tick。真正还待定的只剩这个锚值要不要按真服观测重新校准, 而不是「需换算」这件事本身。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:366-368 `b.comment("11.3 PENDING: rate (rounds/hour) -> tick conversion. ticksPerRoundForTable = ticksPerRateHour / ratePerTable.");` + `TICKS_PER_RATE_HOUR = b.comment("Real ticks mapped to one rate-hour (default 72000 = one real hour; not MC day). Calibrated on live server.").defineInRange("ticksPerRateHour", 72000, 20, 172800000);`; src/main/java/com/miningdim/job/munitions/MunitionsProduction.java:55-61 `public static int ticksPerRound(int level) { int rate = MunitionsLevels.ratePerTable(level); int hourTicks = MunitionsConfig.TICKS_PER_RATE_HOUR.get(); int perRound = (hourTicks + rate - 1) / rate; return Math.max(1, perRound); }`
- 建议改法: L194 改为「3. **速率锚值校准**:换算机制已落地(`[timing] ticksPerRateHour`, 默认 72000 tick = 1 真实小时; 每发 tick = ticksPerRateHour / 每台速率, 见 MunitionsProduction.ticksPerRound 与其 GameTest 数值锚点), 待定的只是该锚值是否按真服观测调整。」

---

## 工程师与铸甲师

### `docs/Armorer_Armor_System_DesignSpec.md`

**[Critical] [A-文档与代码不符] 文档称不做自定义穿戴贴图与 3D 模型, 实际 54 张贴图 49 个模型**

- 位置: 第 396, 464 行
- 文档原文: L396 "穿在人身上的显示按类型明确复用原版皮革、铁、下界合金人形胸甲层；不制作自定义穿戴贴图或 3D 模型"; L464 验收项 7 "客户端提示、64×64 图标、原版穿戴显示与物品显示 JSON 均无缺失"
- 代码实际: 每件插板都有自己的穿戴贴图, 并且有逐件手写的自定义 HumanoidModel 几何体。PlateArmorEquipmentMaterial 复用原版 leather/iron/netherite 只剩下"装备音效 + 材质名占位"的作用, 真正渲染走的是 miningdim 自己的贴图与模型层。
- 取证: src/main/java/com/miningdim/job/engineer/armor/item/PlateArmorItem.java:29-30 "MODEL_TEXTURE_PREFIX = \"miningdim:textures/models/armor/plate_armor_\"" 与 :57-83 getArmorTexture 对全部 54 个 variant 返回 MODEL_TEXTURE_PREFIX + variant.id() + "_layer_1.png"; src/main/resources/assets/miningdim/textures/models/armor/ 下实有 54 个 plate_armor_*_layer_1.png; src/main/java/com/miningdim/job/engineer/armor/client/ 下 49 个 *ArmorModel.java, 例 PacaArmorModel.java:17 "public final class PacaArmorModel extends HumanoidModel<LivingEntity>" 与 :43-60 createBody() 手写 addBox 几何
- 建议改法: 把 L396 整条替换为: "穿在人身上的显示由铸甲师自有资源承担: 每件护甲一张 miningdim:textures/models/armor/plate_armor_<id>_layer_1.png, 并配一个继承 HumanoidModel 的自定义几何模型(armor/client/*ArmorModel.java, 经 PlateArmorModelDefinition 映射、PlateArmorClientRegistration 在客户端注册, 128×128 贴图尺寸); PlateArmorEquipmentMaterial 仅继续复用原版皮革/铁/下界合金的装备音效与材质名占位, 不再提供穿戴贴图层。" L464 验收项 7 中"原版穿戴显示"一并改为"自定义穿戴模型显示", 与实际的 PlateArmorClient.getHumanoidArmorModel 行为对齐。不需要改动第十二章"后续工作"——该章节内容(L478-486)未提及自定义穿戴贴图, 没有需要清理的过期语义。

**[Critical] [D-覆盖缺口] 插板护甲的 FE 电力需求全文零记载**

- 位置: 第 402-409 行
- 文档原文: L402 "服务端配置使用既有 miningdim-engineer.toml，不创建第二套铸甲师配置…plateArmor 下包含：" 随后只列 ballisticProtectionR / armorPiercingBufferQ / generalProtectionG / pressureCapacityT / movement / materialProfiles 六项; L411 "最终属性按当前服务端配置即时解析，不写入物品 NBT"
- 代码实际: plateArmor 下还有一个 power 段, 且它是生存向硬约束: 插板每吸收 1 点伤害要扣 5000 FE, 单件电池 2,000,000 FE (约可吸收 400 点伤害), 电量耗尽后插板"退化成普通护甲: 不减伤、不磨损"; 电量以 IEnergyStorage capability 形式挂在物品上, 并把 PlateArmorEnergy 写进 ItemStack NBT (与 L411 的"不写入物品 NBT"表述也相左, 那句只对 R/Q/G/T 成立)。按现有文档配服或规划获取链, 会完全漏掉"护甲需要充电"这条。
- 取证: src/main/java/com/miningdim/job/engineer/armor/PlateArmorConfig.java:69-79 "builder.push(\"power\"); energyCapacity = …defineInRange(\"energyCapacity\", 2_000_000, 0, 2_000_000_000); fePerAbsorbedDamage = …defineInRange(\"fePerAbsorbedDamage\", 5_000, 0, 10_000_000)"; PlateArmorDamageHandler.java:41-44 "// 没电的插板退化成普通护甲: 不减伤、不磨损、也不静默假装工作。 if (fePerPoint > 0 && PlateArmorPowerCell.storedEnergy(armorStack) <= 0) { return; }" 与 :56-63 按实际吸收量扣电、余额不足时按比例回退减伤; PlateArmorPowerCell.java:22 "K_ENERGY = \"PlateArmorEnergy\"" 与 :35-41 NBT 无记录时视为出厂满电, :69-74 暴露 ForgeCapabilities.ENERGY
- 建议改法: 在第九章"配置与数据边界"的 plateArmor 配置清单中补一条: "power：energyCapacity（单件随身电池容量，默认 2,000,000 FE，范围 0-2,000,000,000）、fePerAbsorbedDamage（每吸收一点伤害扣的电，默认 5,000 FE，范围 0-10,000,000；设为 0 关闭电力需求）"；并在第三章（核心战斗定位）或第五章新增一小节说明电力规则: 出厂满电（NBT 无记录时视为满电）、只进不出（extractEnergy 恒返回 0，需通过标准 IEnergyStorage capability 外部充电，如后续接入的 Flux 无线充电）、零电时插板完全退化为普通护甲（不减伤、不磨损）、电量按实际吸收伤害量计费、余额不足时按可支付比例回退减伤而非整段免费。同时应在第十章"验证状态"补充电力相关的已测行为（配置下调耗尽等）说明，并在 L411 附近澄清"不写入物品 NBT"仅指等级/类型/材料衍生的 R/Q/G/T 等最终属性，不适用于电量状态（PlateArmorEnergy 确实写入 ItemStack NBT），避免字面矛盾。

**[Critical] [D-覆盖缺口] Armorer_Armor_System_DesignSpec.md 把已完整实装的电浆护盾系统(18 变体, 130 项配置, 独立伤害结算)全程描述为"未来"功能, 第九章配置清单亦漏登其 plasmaShield 段**

- 位置: 第 402-409 行
- 文档原文: L402 "服务端配置使用既有 miningdim-engineer.toml，不创建第二套铸甲师配置，以免旧服迁移时出现两个权威来源。plateArmor 下包含：" 后跟六条子键
- 代码实际: 同一个 miningdim-engineer.toml 里, plateArmor 之外还有一个体量更大的 plasmaShield 段: 四个全局键 (maxHeat / restartHeat / stateTickInterval / heatCoolDelayTicks) 加 balanceV4 下 18 个变体各 7 个键 (capacity / maxTotalEnergy / heatPerDamage / coolingPerSecond / rechargePerSecond / rechargeDelayTicks / movementModifier), 合计 130 个配置项。服主按第九章清单核对配置文件会发现大量"多出来的"未知段。
- 取证: src/main/java/com/miningdim/job/engineer/EngineerConfig.java:244-245 "PLATE_ARMOR = PlateArmorConfig.define(b); PLASMA_SHIELD = PlasmaShieldConfig.define(b);"; PlasmaShieldConfig.java:18-27 "builder.push(\"plasmaShield\") … maxHeat=100.0 / restartHeat=30.0 / stateTickInterval=5 / heatCoolDelayTicks=20"; :29-37 "builder.push(\"balanceV4\") … for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) defineVariant(...)"; :50-65 七个变体级键
- 建议改法: 1) 第九章(L402-409): 标题由"plateArmor 下包含"改为"miningdim-engineer.toml 下包含两个顶层段: plateArmor 与 plasmaShield", 补齐 plasmaShield 全局键 maxHeat/restartHeat/stateTickInterval/heatCoolDelayTicks 与 balanceV4.<variant> 下 capacity/maxTotalEnergy/heatPerDamage/coolingPerSecond/rechargePerSecond/rechargeDelayTicks/movementModifier 七项 × 18 个变体(NANO/STANDARD/QUANTUM × I~VI), 并注明 balanceV4 是刻意与旧路径隔离的新路径(参照 materialProfiles 的处理说明)。 2) 第一章"当前结论"(L10、L24、L28)与第八章末(L486): 删除或改写"本期只实现插板护甲""未来可为电浆护盾""另开功能分支"等表述, 改为如实说明电浆护盾(18 件, NANO/STANDARD/QUANTUM 三系列 × I~VI 六级)已随本子系统一并注册、可在铸甲师创造页签直接获取、拥有独立的伤害吸收/过热/回能/移速与 HUD 同步逻辑(PlasmaShieldHandler)。若电浆护盾的战斗数值/身份字段设计确由另一份文档承接, 应在此明确标注"实装状态: 已上线; 设计细节详见《XXX》", 而不是让读者以为它尚不存在。

**[Critical] [C-实现状态标错] 电浆护盾标为本期不实现, 实际已全量实装 18 个变体**

- 位置: 第 468-476, 486, 10 行
- 文档原文: L468 "这些种类只记录机制方向，本期不实现，也不复用插板的 R/Q/G/T"; L472 表行 "电浆护盾 | 轻型护盾、重型护盾 | 充能能量池…"; L486 "7. 电浆护盾、纳米陶瓷板和弹力护甲各自另开功能分支"; L10 "本期只实现“插板护甲”种类"
- 代码实际: 电浆护盾已是与插板并列的第二套完整护甲种类并随铸甲师子系统上线: 3 个系列 (nano/standard/quantum) × 6 个等级 = 18 个正式注册物品, 外加 3 个旧 ID 兼容物品, 自带独立配置段 balanceV4、热量/过热/重启状态机、专用网络频道、HUD 叠层与命中特效、穿戴模型与音效。文档给出的未来类型集合"轻型护盾、重型护盾"也与实际的三系列六级结构完全不同。
- 取证: src/main/java/com/miningdim/job/engineer/shield/PlasmaShieldVariant.java:8-27 枚举 NANO_I..QUANTUM_VI 共 18 项; PlasmaShieldSeries.java:8-10 "NANO(\"nano\"), STANDARD(\"standard\"), QUANTUM(\"quantum\")"; ModEngineerItems.java:127-134 对 PlasmaShieldVariant 与 PlasmaShieldType 各自 ITEMS.register; EngineerSystem.java:137 日志 "armorer subsystem registered (54 plate armors + 18 plasma shields + 3 legacy shield aliases + 6 repair plates + 6 tables + effects + QTE)"; PlasmaShieldConfig.java:18 builder.push("plasmaShield") 与 :110-132 十八个变体默认值; shield/client/ 下 PlasmaShieldHudOverlay.java、PlasmaShieldHitOverlay.java 等 10 个类; shield/network/ 下 PlasmaShieldSyncS2C/PlasmaShieldHitS2C
- 建议改法: 把第十一章表(docs/Armorer_Armor_System_DesignSpec.md:470-474)中的"电浆护盾"整行移出"后续护甲种类", 另开一节或新建 PlasmaShield_DesignSpec.md 记录已实装事实: 三系列 nano/standard/quantum(shield/PlasmaShieldSeries.java:8-10) x I-VI 六级(shield/PlasmaShieldVariant.java:8-27, 共 18 个正式变体) + 3 个 legacy 兼容 ID(shield/PlasmaShieldType.java:8-10: nano/light/heavy_ion); 配置段列出 PlasmaShieldConfig.java 中 capacity/maxTotalEnergy/heatPerDamage/coolingPerSecond/rechargePerSecond/rechargeDelayTicks/movementModifier 七项每变体独立值, 以及全局 maxHeat=100.0/restartHeat=30.0(PlasmaShieldConfig.java:20-24)的过热/重启阈值; 同时说明其独立网络协议(shield/network/PlasmaShieldNetwork.java、PlasmaShieldSyncS2C.java、PlasmaShieldHitS2C.java)与客户端 HUD/命中特效/穿戴模型(shield/client/ 下 10 个类)。同步删除第十二章第 7 条(L486)里的"电浆护盾"，只保留"纳米陶瓷板和弹力护甲各自另开功能分支"；第一章结论 L10 改为"本期实现插板护甲与电浆护盾两个种类"，并在其后补一句两者互斥关系(同槽位, PlateArmorDamageHandler 与 PlasmaShieldHandler 均取 EquipmentSlot.CHEST)以免读者误以为二者可叠穿。

### `standalone/kivotos-armorer/README.md`

**[Critical] [A-文档与代码不符] standalone/kivotos-armorer/README.md 构建步骤缺 TaCZ jar 前置放置说明, 按文档唯一给出的 `./gradlew.bat build` 必现编译失败**

- 位置: 第 19, 22-27 行
- 文档原文: L19: "- TaCZ 1.1.8 hotfix（可选运行依赖；仅编译 API）"; L22-27: "## 构建\n\n```powershell\n./gradlew.bat build\n```\n\n成品位于 `build/libs/kivotos_armorer-1.20.1-1.0.0.jar`。"
- 代码实际: build.gradle 以 `compileOnly files('libs/tacz-1.20.1-1.1.8-hotfix.jar')` 引入 TaCZ, 而 standalone/kivotos-armorer/libs 目录在仓库里根本不存在(jar 属第三方不入库); 同时 PlateArmorTaczDurabilityHandler.java 硬 import 了 com.tacz.guns.api.event.common.EntityHurtByGunEvent / EntityKillByGunEvent。缺 jar 时这两个 import 无法解析, 直接 `./gradlew.bat build` 会编译失败。README 把 TaCZ 说成"可选", 且构建段落只有一条命令, 没有任何"先手工放 jar 到 libs/"的步骤。产物名 kivotos_armorer-1.20.1-1.0.0.jar 本身是对的。
- 取证: standalone/kivotos-armorer/build.gradle:45: "compileOnly files('libs/tacz-1.20.1-1.1.8-hotfix.jar')"; standalone/kivotos-armorer/src/main/java/com/kivotos/armorer/armor/integration/PlateArmorTaczDurabilityHandler.java:3-4: "import com.tacz.guns.api.event.common.EntityHurtByGunEvent;\nimport com.tacz.guns.api.event.common.EntityKillByGunEvent;"; `ls standalone/kivotos-armorer/libs` 返回 "No such file or directory"; 产物名依据 build.gradle:9-13 "version = mod_version" / "archivesName = \"${mod_id}-${mc_version}\"" 与 gradle.properties "mod_id=kivotos_armorer" "mod_version=1.0.0" "mc_version=1.20.1"
- 建议改法: 1) 第19行由"TaCZ 1.1.8 hotfix（可选运行依赖；仅编译 API）"改为"TaCZ 1.1.8 hotfix（运行期可选降级，但编译期必需：armor/integration 包直接 import com.tacz.guns.api，缺 jar 将导致编译失败）"。 2) 第22-27行"## 构建"一节改为两步: "1. 从官方渠道获取 `tacz-1.20.1-1.1.8-hotfix.jar`（第三方 jar，不随本仓库分发），放入 `standalone/kivotos-armorer/libs/`（目录需自行创建）。2. 执行 `./gradlew.bat build`。"其后保留"成品位于 `build/libs/kivotos_armorer-1.20.1-1.0.0.jar`。"不变(该行本身准确)。

### `docs/Armorer_Armor_System_DesignSpec.md`

**[Major] [E-状态过期] 验证状态与当前结论两章均未跟上插板护甲已接入 FE 电力层、电浆护盾新增测试等后续变更, 708/708 与 2026-07-15 已是过期快照**

- 位置: 第 3-4, 442 行
- 文档原文: L3-4 "> 状态：第一版已实装，数值仍可调。 > 更新日期：2026-07-15。"; L442 "- runGameTestServer：708/708 必需测试通过；"
- 代码实际: 708 是 2026-07-15 那一版的数字, 与当前仓库差了一个数量级以上。此后插板加了 FE 电力层、换了穿戴贴图与自定义模型、图标改尺寸、电浆护盾整套上线, 第十章"验证状态"这一节记录的既不是当前测试规模, 也不覆盖上述新增面。该节以"验证状态"为标题, 读者会当成当前门槛。
- 取证: 全库 grep "@GameTest" 计数 1616 (其中 src/main/java/com/miningdim/job/engineer 下 86); 期间新增的测试类含 PlasmaShieldGameTests.java (1235 行) 与 EngineerWebUiGameTests.java (439 行), 两者在 L439-454 的验证清单里均无对应条目
- 建议改法: 1) L442 的具体数字改判据而非快照: "runGameTestServer：日志出现 All N required tests passed(N 随全库增长, 本文档不固化具体数字)"。 2) L3-4 更新日期刷新为本次修订实际日期, 并在 L437"十、验证状态"标题下方补一句: "以下为 2026-07-15 第一版的验证清单, 之后新增的 FE 电力层(PlateArmorPowerCell)、自定义穿戴模型与电浆护盾(PlasmaShield*)另见对应章节/文档", 同时给这三者各补一条验收条目, 而不是只在电浆护盾未来表格行里一笔带过。 3) 更重要的是 L8-10"一、当前结论"章节要同步补一句插板护甲已接入 FE 电力(IEnergyStorage, 电量耗尽影响防护结算, 见 src/main/java/com/miningdim/job/engineer/armor/PlateArmorPowerCell.java), 否则读者从"当前结论"看到的仍是"本期只有 TaCZ 弹道结算和普通物理伤害结算", 会漏掉一个已经上线的核心机制。 4) 不要用"差了一个数量级以上"这种夸大表述, 如实写作"从 708 增长到当前全库 1616, engineer 包内已有 86 条", 避免引入新的不准确表述。

**[Major] [D-覆盖缺口] 7.2节漏记等离子盾同样禁用旧纳米护盾全免窗,且两个数据包可配的绕盾标签(bypasses_nano_shield/bypasses_plasma_shield)完全未记录**

- 位置: 第 385-386 行
- 文档原文: L385 "穿着功能正常的新插板时，其他护甲槽上的旧“纳米多重护盾”全免窗口被禁用，避免两套防护原理叠加；"
- 代码实际: 代码的禁用条件是"穿了插板 **或** 等离子盾", 文档只写了插板。另外纳米护盾还新增了两条豁免: BYPASSES_INVULNERABILITY 标签 (保证 /kill 与虚空伤害不被免疫窗吃掉) 与自定义标签 miningdim:bypasses_nano_shield; 等离子盾侧有对称的 miningdim:bypasses_plasma_shield。这两个 damage_type 标签是数据包可配的平衡开关, 两份文档都没有记录。
- 取证: src/main/java/com/miningdim/job/engineer/effect/NanoShieldHandler.java:49-52 "// 新插板是一套独立护甲原理；穿戴时禁用其他槽位遗留的纳米全免窗… if (PlateArmorItem.equippedBy(player) != null || PlasmaShieldItem.equippedBy(player) != null) { return; }" 与 :34-36 "public static final TagKey<DamageType> BYPASSES_NANO_SHIELD = TagKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(MiningConstants.MODID, \"bypasses_nano_shield\"));"; :45-46 "|| event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) || event.getSource().is(BYPASSES_NANO_SHIELD)"; 资源侧 src/main/resources/data/miningdim/tags/damage_type/bypasses_nano_shield.json 与 bypasses_plasma_shield.json
- 建议改法: 将 docs/Armorer_Armor_System_DesignSpec.md 第385行改为:"穿着功能正常的新插板或电浆护盾时，其他护甲槽上的旧"纳米多重护盾"全免窗口被禁用，避免多套防护原理叠加；";并在7.2节末尾(第386行后)新增一条:"纳米护盾与电浆护盾各自受一个数据包可配的伤害类型标签约束(miningdim:bypasses_nano_shield / miningdim:bypasses_plasma_shield，定义于 src/main/resources/data/miningdim/tags/damage_type/)，命中标签的伤害不会被免疫窗吸收；原版 minecraft:bypasses_invulnerability(含 /kill 与虚空伤害)恒不被两套免疫窗吸收。"

**[Major] [A-文档与代码不符] 图标规则写 64×64 安全区 56×56，实际全部 48×48**

- 位置: 第 393-394, 464 行
- 文档原文: L393 "图标生成规则固定为：保留原图 alpha≥128 的实体主体并二值化，只裁去透明外边距；保持宽高比，以最近邻中心采样缩放进 56×56 安全区，再居中放入 64×64 RGBA 画布"; L394 "四边至少保留 4 像素透明边距"; L464 验收项 7 "64×64 图标"
- 代码实际: 仓库里 54 张插板物品图标全部是 48×48, 不是 64×64。历史上确实是 64×64 (提交 e6991862 与 3a3aebdf 时仍为 64×64), 在提交 ebaef4f2 "feat(armorer): THOR 一体式护甲纹理重制与构造材料扩展" 被整体改成 48×48, 文档未同步。48 不是 2 的幂, 按本仓库既有教训 (非 POT 物品贴图会拉低整张图集的 mipmap 质量), 这条对不上会让后续重新出图的人按错尺寸交付。另外没有任何 GameTest 断言图标像素尺寸, 所以不会被质量门拦住。
- 取证: src/main/resources/assets/miningdim/textures/item/plate_armor_*.png 共 54 个文件, 读 PNG IHDR 得到的尺寸统计为 {(48, 48): 54}; git 历史核对: "git show e6991862:…/plate_armor_paca.png" 为 (64,64), "git show ebaef4f2:…/plate_armor_paca.png" 为 (48,48); 全库 grep 未找到任何针对插板图标尺寸的断言 (PlateArmorGameTests.java 中无 icon/png/width/height 相关断言)
- 建议改法: 先确认哪一边是权威。若 48×48 是有意决策，把 docs/Armorer_Armor_System_DesignSpec.md L393 改为"……以最近邻中心采样缩放进 40×40 安全区，再居中放入 48×48 RGBA 画布"并同步 L394 的边距描述与 L464 的"48×48 图标"；若 64×64 才是规格，则回退 ebaef4f2 对 textures/item 下 54 张插板图标的尺寸改动。无论取哪边，都建议在 PlateArmorGameTests.java 中补一条 GameTest 断言 54 张图标的 IHDR 像素尺寸，否则这条规则没有任何机械兜底，未来再次改动尺寸仍不会被质量门拦住。

**[Major] [A-文档与代码不符] 称 /job info 直接显示稳定职业 ID，实际显示"铸甲师"（且命令参数暗藏 armorer/铸甲师 别名未记）**

- 位置: 第 6 行
- 文档原文: L6 "/job info 等直接显示稳定职业 ID 的命令、JobId.ENGINEER、engineer 注册名和配置文件名暂时保留，以兼容旧存档与既有模块。"
- 代码实际: /job list 与 /job info 走的是 JobId.displayName(), 解出来的是翻译键 job.miningdim.engineer, 中文值就是"铸甲师", 没有任何命令会把裸 id "engineer" 显示给玩家。另外命令参数侧还多接受了 armorer 与中文"铸甲师"两个别名, 文档也未记。真正保留 engineer 的只有 NBT 键、注册 ID、lang key 命名空间与配置文件名。
- 取证: src/main/java/com/miningdim/job/JobCommands.java:96 "job.displayName(), shown.level(), shown.xp(job)," (info 行) 与 :73 (list 行) 同样用 job.displayName(); JobId.java:48-50 "public Component displayName() { return Component.translatable(\"job.miningdim.\" + id); }"; lang/zh_cn.json:856 "\"job.miningdim.engineer\": \"铸甲师\""; JobId.java:61 "if (\"armorer\".equalsIgnoreCase(id) || \"铸甲师\".equals(id)) { return ENGINEER; }"
- 建议改法: 把 L6 改为: "界面、创造页签与 /job 系列命令的显示名称统一为"铸甲师"(翻译键 job.miningdim.engineer); /job info 与 /job set 的职业参数同时接受 engineer、armorer 与"铸甲师"三种写法。JobId.ENGINEER、engineer 注册 ID、NBT 键与配置文件名 miningdim-engineer.toml 保留不动, 以兼容旧存档与既有模块。"

### `docs/MillenniumEngineer_Mod_DesignSpec.md`

**[Major] [B-文档互相打架] 职业名三方打架: 千年工程师 / 铸甲师 / wok-job-armorer**

- 位置: 第 1, 5 行
- 文档原文: L1 "# 千年工程师 职业 Mod — 设计规格文档"; L5 "用途: 千年工程师职业实现阶段的唯一架构、数值与机制参考"; 对照 docs/Armorer_Armor_System_DesignSpec.md:6 "界面与创造页签显示名称为“铸甲师”"; docs/modules/module-registry.json 模块 id 为 wok-job-armorer, docs/modules/README.md:29 "职业 | WOK-铸甲师模块 | wok-job-armorer | job/engineer"
- 代码实际: 代码侧已经完全倒向"铸甲师": 玩家可见名、创造页签名、子系统 name() 都是铸甲师/Armorer, engineer 只作为旧存档与旧命令的兼容 id 保留。两份文档描述的是同一个职业 (同一个 JobId.ENGINEER、同一个 EngineerSystem、同一个 miningdim-engineer.toml、同一个创造页签), 不是两个东西; "千年工程师"已是全库唯一还在用的过期名称 (docs/design_mindmap.md:44 同源过期)。
- 取证: src/main/resources/assets/miningdim/lang/zh_cn.json:856 "\"job.miningdim.engineer\": \"铸甲师\"" 与 :265 "\"itemGroup.miningdim_engineer\": \"铸甲师\""; src/main/java/com/miningdim/job/JobId.java:60-62 "// “铸甲师”是玩家可见的新名称；保留 engineer 作为旧存档和旧命令兼容 ID。 if (\"armorer\".equalsIgnoreCase(id) || \"铸甲师\".equals(id)) { return ENGINEER; }"; EngineerSystem.java:149 "return \"ArmorerSystem\";" 与 :137 日志 "armorer subsystem registered"
- 建议改法: 统一成"铸甲师", 千年工程师只作为历史别名在元信息里保留一行。具体: 把本文件标题改为 "# 铸甲师 职业 Mod — 纳米生产与维修规格文档", L5 改为"铸甲师职业(旧称千年工程师)纳米生产/维修/等级部分的架构与数值参考; 护甲种类、R/Q/G/T 与 54 件映射见 docs/Armorer_Armor_System_DesignSpec.md", 并把正文出现的"工程师"统一为"铸甲师"(注册名 engineer、JobId.ENGINEER、包名 job.engineer、配置文件名保持不动并在元信息注明)。docs/design_mindmap.md:44 的"### 千年工程师"一并改名。

**[Major] [E-状态过期] 全文仍标为进入编码前的阻塞态, 实际三批工作已落码上线**

- 位置: 第 10, 312, 322 行
- 文档原文: L10 "阻塞项: 进入编码前须先解决第十二章列出的全部 PENDING。"; L312 "## 十二、待确认实现项 — 进入 Code 前须拍板 (PENDING)"; L322 "## 十三、实现期工作分解 (确认上述 PENDING 后展开)"
- 代码实际: 第十三章三批共 11 项里只剩第三批第 11 项 (纳米特效自定义 HUD 叠层 + S2C 状态包) 未做, 其余全部落码并已接进 MiningDim 主线: 子系统骨架、六档板与六档生产台、投矿选档三道门、上锁与 producer 盖章、两个经验结算点、修复曲线与四个特效、纳米校准 QTE 与客户端 Screen、全部 config spec 都在。第十二章那 5 条 PENDING 也已各有实现取舍 (且第 1、2、4 条文档自己已在括号里写出了当前实现), 把整份文档继续标成"进入 Code 前阻塞"会误导后来者以为这职业还没开工。
- 取证: src/main/java/com/miningdim/MiningDim.java:130 "subsystems.add(new com.miningdim.job.engineer.EngineerSystem());"; EngineerSystem.java:96-135 六个 DeferredRegister + registerConfig + 七个事件 handler + MenuScreens.register 全部就位; NanoCalibration.java:104-146 serverTick/onClick 即第三批第 10 项 QTE; client/ProductionTableScreen.java 存在; 唯一未做项有旁证: docs/Full_Repo_Audit_2026-08.md:893 "纳米特效侧没有任何 overlay, 也没有对应的 S2C 状态包"
- 建议改法: 元信息 L10 改为 "状态: 第十三章三批工作已全部落码并接进 MiningDim.registerSubsystems(), 唯一未做项是第三批第 11 项 (纳米特效 HUD 叠层 + S2C 状态包)。"; 第十二章标题改为 "## 十二、实现期取舍记录 (含未结案项)", 逐条标明 DECIDED/PENDING(其中第4条"Unbreakable NBT 护甲的修复处理"应据 NanoRepair.java:24 的既有取舍标为 DECIDED, 并把该取舍原文补写进第十二章正文, 而非仅留章节号引用); 第十三章标题改为 "## 十三、实现期工作分解 (已完成, 留档)" 并在每项前加 [x] / [ ] (仅第三批第11项标 [ ])。

**[Major] [A-文档与代码不符] 5.2 建议不拦铁砧, 代码已拦且已实装, 与本文 12.1 "当前实现" 描述自相矛盾**

- 位置: 第 124 行
- 文档原文: L124 "铁砧旁路: … **建议不拦** (省一层 `AnvilUpdateEvent`), 让铁砧修这类甲保留特效。PENDING 你拍板。"
- 代码实际: 代码确实加了 AnvilUpdateEvent 这一层, 并且在铁砧输出会回耐久时直接取消合成 (改名与附魔书附魔不回耐久时放行)。同一份文档 L314 第十二章第 1 条已经把结论写成"当前实现：仅阻止铁砧恢复耐久，改名/附魔不回耐久时放行", 与 L124 的"建议不拦"正面打架 —— 只读 5.2 的人会以为带纳米特效的甲能进铁砧修耐久。
- 取证: src/main/java/com/miningdim/job/engineer/effect/NanoAnvilGuard.java:55-69 "@SubscribeEvent public void onAnvilUpdate(AnvilUpdateEvent event) { if (NanoNbt.effects(event.getLeft()).isEmpty()) { return; } … if (output.getDamageValue() < event.getLeft().getDamageValue()) { event.setCanceled(true); } }"; EngineerSystem.java:119 "forgeBus.register(new NanoAnvilGuard());"
- 建议改法: 将 docs/MillenniumEngineer_Mod_DesignSpec.md L124 的 "铁砧旁路: 带特效护甲多为 "原版修不了才用纳米修" 的那类, 铁砧本就修不了, 旁路在实战里多不成立; 仅 "本可修的甲特意纳米修来拿特效" 才碰得到。**建议不拦** (省一层 `AnvilUpdateEvent`), 让铁砧修这类甲保留特效。PENDING 你拍板。" 整句替换为: "**已拦并已实装** (NanoAnvilGuard 订阅 AnvilUpdateEvent, 见 EngineerSystem 注册): 左侧带任意纳米特效且铁砧输出会回耐久 (output.getDamageValue() < event.getLeft().getDamageValue()) 时取消输出, 挡住经验修补/同物合并修/材料修越过特效耐久门槛的路径; 纯改名与附魔书附魔因不改变耐久值 (output 耐久 == left 耐久) 照常放行, 不影响普通装备与该甲的正常改名/附魔。与十二章第1条结论一致, 本条不再 PENDING。" 同时建议顺带核对十二章标题仍写 "待确认实现项...PENDING" 但第1条已注明"当前实现"的措辞是否也需要挪出 PENDING 列表或加注"已定稿", 避免同类"整节标 PENDING、单条已落地"的状态过期再次出现(不在本条评分范围内, 仅顺带提示)。

**[Major] [A-文档与代码不符] 纳米重塑失效阈值表主值写 25%,代码默认值已是 0.40(40%)**

- 位置: 第 144 行
- 文档原文: L144 "| 纳米重塑 | 缓慢回护甲自身耐久 | 损失 > 25% 耐久 (阈值可配, 建议可放宽到 40%, 否则枪火下立即触发) | 按件 (耐久逐件, 不汇聚), 安全 |"
- 代码实际: 实现直接采纳了括号里的建议值, 配置默认就是 0.40, 表格主值 25% 从来没有生效过。docs/design_mindmap.md:50 "纳米重塑:缓回耐久,损失 >25% 失" 是同源过期拷贝。这条本身不致命, 但 25% 与 40% 在 TACZ 高 DPS 环境下对应的是"基本不生效"与"能生效"两种体验, 拿 25% 去算平衡会得出错误结论。
- 取证: src/main/java/com/miningdim/job/engineer/EngineerConfig.java:204-207 "b.push(\"reshape\"); … RESHAPE_FAIL_DAMAGE_PCT = b.defineInRange(\"failDamagePct\", 0.40, 0.0, 1.0);" 与 :89 注释 "重塑失效的耐久损失阈值 (0.40 = 损失超 40% 即停; 规格建议放宽到 40 防枪火立即触发)"; effect/NanoEffects.java:70-71 "double lost = 1.0 - durabilityFraction(armor); return lost <= EngineerConfig.RESHAPE_FAIL_DAMAGE_PCT.get();"
- 建议改法: 把 docs/MillenniumEngineer_Mod_DesignSpec.md:144 的"失效条件"列改为: "损失 > 40% 耐久 (config engineer.reshape.failDamagePct, 默认 0.40; 早期草案 25% 在枪火高 DPS 下会立即触发, 已弃用)"; 同步把 docs/design_mindmap.md:50 的 "损失 >25% 失" 改为 "损失 >40% 失", 避免同源过期拷贝继续误导后续读者做平衡测算。

**[Major] [D-覆盖缺口] 7.4 单档原始经验表缺闪耀档, 代码被迫暂用超凡值**

- 位置: 第 219 行
- 文档原文: L219 "单档原始经验 (示例, 进 config): 低级 15 / 中级 30 / 高级 60 / 极品 110 / 超凡 200; 用自己板修甲额外 +50%。"
- 代码实际: 六个档位里唯独闪耀 (RADIANT) 没有给原始经验值, 但代码必须给它一个数 —— 闪耀是 L10 毕业档、成本最高 (2 下界合金锭概率产出), 经验按理不应低于超凡。实现只能先抄超凡的 200 并在 config 注释里自挂 PENDING 等待标定。这会让"用闪耀板修甲的收益不如成本"这条平衡问题一直悬着。
- 取证: src/main/java/com/miningdim/job/engineer/EngineerConfig.java:176-179 "// 7.4 单档原始经验只给到 \"超凡 200\", 未给闪耀值 (PENDING 12.7 单板经验标定)。闪耀是 L10 毕业档、成本最高 … spec 缺值, 此处暂沿用超凡 200 待标定 (config 可调)。 RAW_XP_RADIANT = b.comment(\"PENDING 12.7: spec 7.4 leaves radiant raw xp undefined; provisionally equals transcendent (200) until calibrated.\").defineInRange(\"rawRadiant\", 200, 0, 100000);"; NanoTier.java:163 "case RADIANT -> EngineerConfig.RAW_XP_RADIANT.get();"
- 建议改法: 把 docs/MillenniumEngineer_Mod_DesignSpec.md:219 的经验表由五档补全为六档并定稿闪耀值, 例如"低级 15 / 中级 30 / 高级 60 / 极品 110 / 超凡 200 / 闪耀 320(不低于超凡)"; 若暂不定稿, 至少写成"闪耀: 待标定, 实现期暂等于超凡 200 (config 键 xp.rawRadiant, 见 EngineerConfig.java:178-179 PENDING 12.7)", 使文档与代码注释同口径。同时建议在第十二章第 5 条(docs/MillenniumEngineer_Mod_DesignSpec.md:318 "单板经验")里补一句具体指向"闪耀档原始经验尚无 spec 值", 而不是仅用笼统的"继续标定"带过, 避免只看第十二章看不出闪耀是唯一缺数值的一档。

**[Major] [A-文档与代码不符] 9.1 声称锁定后"信任名单"成员可开 GUI, 但全仓访问控制只有主人/OP 两级, 无任何信任名单实现**

- 位置: 第 245 行
- 文档原文: L245 "锁定时: 非主人 (OP / 信任名单除外) 拒绝开 GUI；输出槽无论是否上锁都只允许主人或 OP 取物。"
- 代码实际: 访问控制只有两条: 是主人, 或者 permission level >= 2 (OP)。没有任何信任名单/白名单的数据结构、NBT 键或命令入口。写在文档里会让人以为可以给队友开权限。
- 取证: src/main/java/com/miningdim/job/engineer/block/ProductionTableBlockEntity.java:185-193 "public boolean canAccess(ServerPlayer player) { … return isOwner(player) || player.hasPermissions(2); }" 与紧随其后的 canTakeOutput 同样是 "isOwner(player) || player.hasPermissions(2)"; :170 "return ownerUUID != null && ownerUUID.equals(player.getUUID());"; 全类只持 ownerUUID 一个 UUID 字段 (:75), 无名单集合
- 建议改法: 把 docs/MillenniumEngineer_Mod_DesignSpec.md:245 的"(OP / 信任名单除外)"改为"(仅 OP, 即 hasPermissions(2) 除外; 当前无信任名单机制)", 与 ProductionTableBlockEntity.java:185-194 的 canAccess/canTakeOutput 实现对齐; 若信任名单确是产品想要的后续功能, 不要留在第九章"已定稿"里, 应把它作为一条新的 PENDING 项挪到第十二章(紧邻现有 L312-318 的五条), 并说明其与现有单一 ownerUUID 字段(10.2 已裁定"不新挂 capability")、10.4 多人共用主人离线策略等相关设计的取舍关系。

**[Major] [A-文档与代码不符] 10.1 包名错误且状态已过期: `com.miningdim.engineer` 不存在, 铸甲师(工程师)子系统实为 `com.miningdim.job.engineer`, 且早已建成并接入 MiningDim.registerSubsystems()(非"新建")**

- 位置: 第 269 行
- 文档原文: L269 "新建 `com.miningdim.engineer` 子系统, 提供一个 `EngineerSystem implements Subsystem`, 在 `register` 内完成自己的 DeferredRegister (Block/Item/BlockEntity/MenuType) + 事件订阅 + 服务注册。"
- 代码实际: 实际包名是 com.miningdim.job.engineer (收在 job 一级包下, 与其余八个职业同构), com.miningdim.engineer 这个包不存在。docs/modules/module-registry.json 里 wok-job-armorer 的 javaPackagePrefixes 也写的是 com.miningdim.job.engineer。按文档去 grep 包名会一无所获。
- 取证: src/main/java/com/miningdim/job/engineer/EngineerSystem.java:52 "package com.miningdim.job.engineer;"; src/main/java/com/miningdim/MiningDim.java:130 "subsystems.add(new com.miningdim.job.engineer.EngineerSystem());"; docs/modules/module-registry.json 中 wok-job-armorer 的 "javaPackagePrefixes": ["com.miningdim.job.engineer"]
- 建议改法: 将 docs/MillenniumEngineer_Mod_DesignSpec.md:269 的 `com.miningdim.engineer` 改为 `com.miningdim.job.engineer`, 并把"新建...子系统"改为"已建成子系统(见 job/engineer 包下 12 个类, 含 EngineerSystem.java)"; L270 的"在 MiningDim.registerSubsystems() 追加一行 new EngineerSystem()"应改为"已在 MiningDim.registerSubsystems() 第19位接线完成(MiningDim.java:130: `subsystems.add(new com.miningdim.job.engineer.EngineerSystem());`)"。建议同时口头提请复核本文档第十二章 PENDING 与第十三章"实现期工作分解"是否也已被后续实现推翻或完成, 因为10.1整段的"未来时"语气与现状(已完整实现并进入生产注册序列)明显不符, 不排除全篇状态图例都需要一次系统性复核(此为另一条潜在E类发现, 超出本条范围, 仅口头提示)。

**[Major] [A-文档与代码不符] 10.2 的三个等级经验字段并不存在, 数据在共享 JobProgress**

- 位置: 第 274, 300, 327 行
- 文档原文: L274 "工程师等级数据 (`engineerLevel` / `engineerXp` / `dailyEngineerXp` + 翻日戳 / `nanoReactorCdEndTick`) 作为字段并入 `entry.MiningPlayerData` (`IMiningPlayerData` 接口扩方法)"; L300 表行 "等级/经验/CD 数据 | 可实现 | 并入 entry.MiningPlayerData (NBT) | 小"; L327 第一批第 1 项同样点名这三个字段
- 代码实际: 落地采用的是该节自己在 L276 预告的演进形态: MiningPlayerData 里放一个 EnumMap<JobId, JobProgress>, 等级与经验由共享职业框架统一裁决, 铸甲师侧只剩一个薄封装。engineerLevel / engineerXp / dailyEngineerXp 这三个字段名全库不存在, 只有 nanoReactorCdEndTick 活了下来, 而且落在 ENGINEER 的 JobProgress 上而不是 MiningPlayerData 的直属字段。按 L274/L327 去 IMiningPlayerData 上找扩展方法会扑空。
- 取证: 全库 grep "engineerLevel|engineerXp|dailyEngineerXp" 只命中方法名 EngineerLevels.engineerLevel(Player), 无任何同名字段; src/main/java/com/miningdim/job/engineer/EngineerLevels.java:40-42 "return JobServices.jobService().level(player, JobId.ENGINEER);" 与 :50-52 "return JobServices.jobService().grantXp(player, JobId.ENGINEER, rawXp);"; src/main/java/com/miningdim/job/JobProgress.java:30 "private long nanoReactorCdEndTick = 0L;"; src/main/java/com/miningdim/entry/MiningPlayerData.java:42 "/** 全职业进度 (第 2.3 节并入): EnumMap<JobId,JobProgress> 持有者, 按需懒建默认。 */"
- 建议改法: 把 10.2 第一条改为已落地形态: "铸甲师等级/经验由共享职业框架承载 —— entry.MiningPlayerData 内部持一个 JobData(封装 EnumMap<JobId, JobProgress>), 等级曲线与每日衰减由 JobXpCurve/JobProgress.grantXp 统一裁决, 铸甲师侧只经 EngineerLevels(job.engineer 包)薄封装读写(JobId.ENGINEER, 经 JobServices.jobService().level/grantXp); 职业私有字段只有 nanoReactorCdEndTick, 声明并存储在 JobProgress(job/JobProgress.java:30)上, 而非 MiningPlayerData 的直属字段, IMiningPlayerData 接口上也只有通用的 jobProgress(JobId) 方法(entry/IMiningPlayerData.java:138), 无 engineerLevel/engineerXp/dailyEngineerXp 专属扩展方法。" L300 表格"关键 API"一列同步改为"经 JobData/JobProgress(EnumMap<JobId,JobProgress>)"; L327 第一批第 1 项同步删除三个字段名, 改为"确认 JobProgress/JobData 已覆盖工程师所需字段, 无需再扩 IMiningPlayerData"; L276 的"应朝...演进"改为"已按此落地(见 JobFramework_Shared_Foundation_DesignSpec 第 2.2/2.3 节)"。

**[Major] [A-文档与代码不符] 10.3 说数值进 MiningServerConfig, 实际是独立 miningdim-engineer.toml**

- 位置: 第 281, 330 行
- 文档原文: L281 "全部平衡数值 (…) 进 `MiningServerConfig` 的 `ForgeConfigSpec`"; L330 第一批第 4 项 "`MiningServerConfig` 加全部数值 spec (第七、八章曲线 + 第五、六章示例值 + 第三章绑档)"
- 代码实际: 铸甲师全部数值在自己的 EngineerConfig.SPEC 里, 注册为独立 SERVER 配置文件 miningdim-engineer.toml, 中央 config.MiningServerConfig 一个铸甲师键都没有。这同时与 docs/Armorer_Armor_System_DesignSpec.md:170 与 :402 的"服务端 miningdim-engineer.toml 的 plateArmor 配置段""不创建第二套铸甲师配置"正面冲突: 按工程师文档去 MiningServerConfig 里找 R/Q/G/T 或修复曲线会一无所获。另外等级曲线与每日衰减档也不在 EngineerConfig 里, 而是 JobXpCurve 的硬编码常量 (框架自述为刻意推迟 config 化), 与 L281 的"等级曲线 / 每日衰减档…进 ForgeConfigSpec"也不符。
- 取证: src/main/java/com/miningdim/job/engineer/EngineerConfig.java:13-15 "本任务铁律: 不修改中央 config.MiningServerConfig (那是别的子系统拥有的文件)。故工程师自带一份独立 SERVER spec (文件 miningdim-engineer.toml)"; EngineerSystem.java:104-105 "ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, EngineerConfig.SPEC, \"miningdim-engineer.toml\");"; JobXpCurve.java:20-22 "ch10 config 化推迟决定: … 本表数值已定稿, 故 ch10 的 config 化推迟 —— 本类常量为定稿硬值的唯一拷贝" 与 :42-53 CUMULATIVE_XP 硬编码断点
- 建议改法: 把 L281 改为 "全部平衡数值 (单板经验 / 矿石绑档 / 各档生成耗时 / 修复曲线 / 特效阈值与系数 / 图腾 CD 与复活百分比 / 护盾免疫窗与次数 / 机能修复递减系数 / 闪耀概率) 进铸甲师自有的 `EngineerConfig` ForgeConfigSpec (服务端文件 miningdim-engineer.toml), 不进中央 MiningServerConfig; 等级曲线与每日衰减档不在此, 由共享职业框架的 `JobXpCurve` 常量承载 (框架第十章的 config 化已显式推迟, 见 JobXpCurve.java:20-22)。" L330 同步改为 "4. `EngineerConfig` (miningdim-engineer.toml) 加全部数值 spec (第七、八章曲线 + 第五、六章示例值 + 第三章绑档); 等级曲线/每日衰减档沿用 JobXpCurve 硬编码常量, 不在此项范围内。"

**[Major] [F-结构问题] 第十二章由7条删至5条(删除旧1/2两条、非合并)未重编号, 代码四处12.x交叉引用(12.1/12.4/12.6/12.7)全部失效, 其中12.1对应的决策已在5.2定案却仍被代码标记PENDING**

- 位置: 第 312-318 行
- 文档原文: L312-318 第十二章当前只有 5 条: 1 铁砧、2 闪耀保底、3 主人离线、4 Unbreakable、5 config 真值
- 代码实际: 第十二章原本是 7 条 (提交 625e88ba 版本: 1 禁用经验修补范围、2 QTE 取舍、3 铁砧、4 闪耀、5 主人离线、6 Unbreakable、7 config 真值), 后来被合并压缩到 5 条但没有重编号回填。结果代码里四处 PENDING 交叉引用全部指错位置: 12.4 在代码里指闪耀 (现为第 2 条)、12.6 指 Unbreakable (现为第 4 条)、12.7 指单板经验标定 (现为第 5 条)。按代码注释去文档里查 12.6 会读到"Unbreakable"以外的内容甚至越界。
- 取证: git show 625e88ba:docs/MillenniumEngineer_Mod_DesignSpec.md 第 308-316 行为 7 条列表; src/main/java/com/miningdim/job/engineer/NanoRepair.java:24 "Unbreakable NBT 物品 (PENDING 12.6): 无可修耐久, 直接拒绝修复"; EngineerConfig.java:178 "PENDING 12.7: spec 7.4 leaves radiant raw xp undefined"; EngineerConfig.java:238 "3.2 radiant plate probabilistic output (PENDING 12.4: tuneable initial values)."; effect/NanoAnvilGuard.java:20 "PENDING 12.1 范围裁决"
- 建议改法: 分两部分处理, 一次做完: (1) 12.4/12.6/12.7 三处(EngineerConfig.java:238、EngineerConfig.java:176+178与EngineerWebUiGameTests.java:66、NanoRepair.java:24+58): 给第十二章现行5条加稳定编号并标历史别名, 如第2条"(旧编号12.4) 闪耀是否增加N次失败必出的保底...", 第4条"(旧编号12.6) Unbreakable NBT护甲的修复处理...", 第5条"(旧编号12.7) 实现期继续标定的config真值..."; 或反过来把代码里的12.4/12.6/12.7统一改成新编号2/4/5, 二者选一。 (2) 12.1 三处(NanoAnvilGuard.java:18/20/24-25)不适用上述重编号方案: 其对应决策(经验修补范围仅纳米特效甲禁用)已在5.2节定稿为正文、且不在现行十二章5条列表中, 应直接删除"PENDING 12.1"标签, 把引用改成指向已定稿的5.2(例如"实现按 5.2 定案: 仅纳米特效甲禁用"), 不要把它并入10.4的重编号名单, 否则会把一个已拍板事项继续误标为悬而未决。

**[Major] [F-结构问题] 工程师文档自称唯一参考却不引用铸甲师护甲文档**

- 位置: 第 5, 全文 行
- 文档原文: L5 "用途: 千年工程师职业实现阶段的唯一架构、数值与机制参考。所有常量以本文档为准, 不得凭记忆改写。"; 对照 docs/Armorer_Armor_System_DesignSpec.md:488 "本文件是护甲种类、身份字段、R/Q/G/T、材料修正与耐久、54 件映射和枪匠联动的当前权威文档。既有纳米生产、职业等级、维修经济和工作台设计继续参考 MillenniumEngineer_Mod_DesignSpec.md。"
- 代码实际: 两份文档合起来才覆盖这一个职业, 但引用是单向的: 铸甲师文档指回工程师文档, 工程师文档全文没有任何一处提到 Armorer_Armor_System_DesignSpec.md, 也不提 54 件插板护甲与电浆护盾 (仅 L46 顺带提了一句"电浆护盾"与"新插板", 不解释也不给出处)。同一个 EngineerSystem 注册的三大块内容 (纳米板/生产台、插板护甲、电浆护盾) 里有两块在本文件中不可达, 而 L5 又宣称自己是"唯一…参考", 读者按它办事必然漏掉护甲主体。
- 取证: grep "插板|Armorer|铸甲" docs/MillenniumEngineer_Mod_DesignSpec.md 只命中 1 行 (L46); src/main/java/com/miningdim/job/engineer/EngineerSystem.java:137 一条日志即同时登记三块 "54 plate armors + 18 plasma shields + 3 legacy shield aliases + 6 repair plates + 6 tables + effects + QTE", 证明三块同属一个子系统
- 建议改法: L5 改为: "用途: 铸甲师职业中"纳米生产台 + 纳米维修套件 + 等级经验 + 纳米特效"这一部分的架构、数值与机制参考。护甲种类本身(插板护甲 54 件、电浆护盾 18 件、R/Q/G/T 与材料修正)见 docs/Armorer_Armor_System_DesignSpec.md, 两份文档互为补集, 各自对自己的段落负责, 代码同属 com.miningdim.job.engineer.EngineerSystem(name()="ArmorerSystem")一个子系统。" 并在第二章"核心机制总览"表后加一行跳转指引, 指向 Armorer_Armor_System_DesignSpec.md 覆盖的插板护甲/电浆护盾部分, 同时可在 docs/modules/module-registry.json 的 wok-job-armorer 条目里补一个指向两份设计文档的字段, 避免仅凭包路径反推关系。

---

## 厨师 酿酒师 塔罗师

### `docs/TarotReader_Mod_DesignSpec.md`

**[Critical] [F-结构问题] 19 份文档零入链, 含 3 个职业的唯一主规格**

- 位置: 第 1 行
- 文档原文: L1「# 塔罗师 职业 Mod — 设计规格文档」(该文件是塔罗师职业的唯一设计文档)
- 代码实际: 用 git ls-files 全量文件做子串反查, docs/ 顶层 57 份中有 19 份在全仓任何受版本控制文件里零出现: Brewer_Job_DesignSpec / CASE_ASSET_PROVENANCE / Champion_Effects_Guide / Chef_Job_Mod_DesignSpec / Economy_SQLite_Migration_Plan / Fishing_Journal_Framework / Gunsmith_Bullpup_Components / Gunsmith_Component_Hot_Reload_Rules / Gunsmith_Handgun_Components / Gunsmith_Marksman_Components / Gunsmith_SMG_Components / Gunsmith_Shotgun_Components / Gunsmith_Sniper_Components / Munitions_Workbench_Branch_Review / Power_Cable_AssetChecklist / Power_Preheat_Generator_AssetChecklist / TarotReader_Mod_DesignSpec / TaskSpec_INDEX / WebUI_ServerPush_DesignSpec。其中塔罗师、厨师、酿酒师三个已上线职业的唯一主规格全在列, 枪匠唯一的运维权威 Gunsmith_Component_Hot_Reload_Rules.md 也在列。
- 取证: 对应代码均为活跃模块: src/main/java/com/miningdim/job/tarot/、job/chef/、job/brewer/ 均存在且已注册; docs/modules/README.md:33「| 职业 | WOK-塔罗师模块 | `wok-job-tarot` | `job/tarot` |」、L30「| 职业 | WOK-厨师模块 | `wok-job-chef` | `job/chef` |」在模块总表里都有行, 但表只有四列(类型/模块/模块键/登记的 currentPaths), 没有一列指向设计文档, 所以从代码侧也走不回文档。另: 全库 Java 源码里以 `docs/xxx.md` 形式引用的文档只有 7 份(grep -rhoE 'docs/[A-Za-z0-9_]+\.md' src/ | sort -u), 代码注释这条旁路同样覆盖不到这 19 份。
- 建议改法: 在 docs/README.md 的文档地图里逐份登记这 19 份; 同时给 docs/modules/README.md 的模块总表加第五列「设计文档」, 让 26 个模块每行都能点到自己的规格。枪匠 7 份平台组件文档与热更新规则统一由 docs/gunsmith/README.md(见另一条发现)收口。

### `docs/Brewer_Job_DesignSpec.md`

**[Major] [A-文档与代码不符] 金酒/伏特加的帽位常量名与执行入口在代码中都不存在**

- 位置: 第 109-110 行
- 文档原文: - 单项数值帽: 金酒永久生命上限有硬帽 (`GIN_MAX_HEALTH_CAP`, 经 `MaxHealthModifierManager.capUp` 执行);   伏特加永久减伤 `VODKA_DAMAGE_REDUCTION = 0.20` (与当前实现 0.05x5=0.25 不一致, 见五之补)。
- 代码实际: `GIN_MAX_HEALTH_CAP` 与 `VODKA_DAMAGE_REDUCTION` 两个符号在 src/main/java 下都搜不到。金酒的帽是 `BrewerConfig.GLOBAL_BONUS_MAX_HEALTH_CAP_PCT`(默认 1.0, 占 base 比例), 由 `GinMaxHealthManager.clampToGlobalCap` 执行, 不是 `MaxHealthModifierManager.capUp`(后者是塔罗的类, 且 capUp 是 apply(...) 的一个形参, 不是可调用入口)。伏特加是 `BrewerConfig.VODKA_REDUCTION_PER_LAYER`(默认 0.05, 每层)。本文档第 124 行自己写的就是正确的 `GinMaxHealthManager.clampToGlobalCap`, 与 L109 打架。
- 取证: src/main/java/com/miningdim/job/brewer/GinMaxHealthManager.java:92 `public static double clampToGlobalCap(double desired, double otherBonus, double base)`; src/main/java/com/miningdim/job/brewer/BrewerConfig.java:90-94 `GLOBAL_BONUS_MAX_HEALTH_CAP_PCT = b.defineInRange("globalBonusMaxHealthCapPct", 1.0D, 0.0D, 10.0D);` / `VODKA_REDUCTION_PER_LAYER = b.defineInRange("vodkaReductionPerLayer", 0.05D, 0.0D, 0.2D);`; src/main/java/com/miningdim/job/tarot/MaxHealthModifierManager.java:46 `public void apply(LivingEntity entity, UUID source, double delta, double capUp, double floorDown)`(capUp 是形参)
- 建议改法: 改为: "金酒永久生命上限有硬帽 (`BrewerConfig.GLOBAL_BONUS_MAX_HEALTH_CAP_PCT`, 经 `GinMaxHealthManager.clampToGlobalCap` 执行, 与塔罗共享同一跨职业帽); 伏特加永久减伤 `BrewerConfig.VODKA_REDUCTION_PER_LAYER = 0.05`/层, 满 5 层 0.25(设计原稿 0.20 与之分叉, 见五之补)。"

**[Major] [E-状态过期] 状态仍写"分阶段实现中"并指向早已合并的功能分支**

- 位置: 第 3 行
- 文档原文: 状态: 设计锁定, 分阶段实现中。分支 `feat/brewer-profession`。
- 代码实际: 第六节列的六期(地基 / 酒物品与喝酒效果 / 酿酒台 / 酒窖箱 / 闪耀永久增益 / 月光赌博表)全部已落码并合入 main: 当前审查用的 D:/Repo/_wt-docs 就是 main 的工作树, job/brewer 下 WineQuality、WineType、BrewerConstants、VintageClock、WineNbt、WineItem、station/BrewingStationBlockEntity、cellar/WineCellarBlockEntity、CellarSettle、BrewBuffStore、BrewPermanentBuffs、MoonshinePerk 悉数存在, 模块注册表也已登记 wok-job-brewer。
- 取证: docs/modules/module-registry.json:536-538 `"id": "wok-job-brewer", "name": "WOK-酿酒师模块", "category": "job"`; src/main/java/com/miningdim/job/brewer/ 下含 cellar/CellarSettle.java、station/BrewingStationBlockEntity.java、MoonshinePerk.java(六期产物齐全, 均在 main 工作树内)
- 建议改法: 把 docs/Brewer_Job_DesignSpec.md 第 3 行改为「状态: 设计锁定, 第六节六期已全部实现并合入 main。」，删除对 `feat/brewer-profession` 分支的引用（该分支已于 main 中，代码不再以分支为准）。

**[Major] [A-文档与代码不符] 干小麦耗量公式写成线性乘式, 实现是二次加式**

- 位置: 第 58 行
- 文档原文: - **耗量随年份递增**: 一瓶酒每陈酿 1 年份的耗量 = `DRIED_WHEAT_PER_BOTTLE_YEAR` 基础 × (1 + 年份/比例)。
- 代码实际: 实现是 `DRIED_WHEAT_PER_BOTTLE_YEAR + FUEL_QUAD_COEF × 年份²`(加式二次), 不是"基础 × (1 + 年份/比例)"(乘式线性), 而且文档里的"比例"在全文和配置里都没有对应旋钮。默认 16 与 5.0 下, 年份 10 时单瓶单年应耗 = 16 + 5×100 = 516(基础的 32 倍); 按文档的线性乘式无论"比例"取何值都无法得到这个量级。`DRIED_WHEAT_PER_BOTTLE_YEAR` 这个符号也已从 BrewerConstants 搬到 BrewerConfig(键名 driedWheatPerBottleYear)。
- 取证: src/main/java/com/miningdim/job/brewer/cellar/CellarSettle.java:108-109 `demandPerYear += BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR.get() + BrewerConfig.FUEL_QUAD_COEF.get() * b.vintage() * b.vintage();`; src/main/java/com/miningdim/job/brewer/BrewerConfig.java:71-74 `DRIED_WHEAT_PER_BOTTLE_YEAR = b.defineInRange("driedWheatPerBottleYear", 16, 0, 100000);` / `FUEL_QUAD_COEF = b.defineInRange("quadCoef", 5.0D, 0.0D, 1000.0D);`
- 建议改法: 将 docs/Brewer_Job_DesignSpec.md:58 改为: "一瓶酒每陈酿 1 年份的耗量 = `BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR`(默认 16, 年份 0 时的基础量) + `BrewerConfig.FUEL_QUAD_COEF`(默认 5.0) × 年份²(超线性二次递增: 嫩酒便宜、高年份指数爆炸, 这是软上限的经济一面)"; 并删除或替换原文中不存在对应配置项的"比例"措辞。

**[Major] [A-文档与代码不符] 月光闪耀写"永久随机好/坏", 实现只发良性词条且须满5层**

- 位置: 第 95 行
- 文档原文: | 月光 moonshine | 赌博 (随机好/坏) | 赌博 (永久随机好/坏) | 烈酒·小麦 |
- 代码实际: 闪耀月光的永久档没有任何坏结果: 代码从 8 条全良性词条(击退抗性/护甲/护甲韧性/幸运等)里确定性抽 5 条不重复施加, 且只有在该酒类层数攒满 `MAX_LAYERS_PER_TYPE=5` 时才首次固化, 未满层什么也不给。"随机好/坏"只存在于非闪耀档的临时效果(MOONSHINE_GOOD_POOL / MOONSHINE_BAD_POOL)。第 97-98 行"闪耀月光把结果变永久 (高赌注)"同样与实现相反 —— 永久档反而是最没有赌注的一档。
- 取证: src/main/java/com/miningdim/job/brewer/MoonshinePerk.java:20-25 `全部词条都是【可干净重挂/移除】的良性增益 … 月光以"赌博"立身, 但永久档良性 (设计: 永久随机良性, 临时档才有翻车惩罚)。`; src/main/java/com/miningdim/job/brewer/BrewEffectEngine.java:172-179 `case MOONSHINE -> { if (newLayers >= BrewerConstants.MAX_LAYERS_PER_TYPE && store.moonshinePerks(player.getUUID()).isEmpty()) { MoonshinePerk[] picked = MoonshinePerk.rollDistinct(…); … } }`
- 建议改法: 把 docs/Brewer_Job_DesignSpec.md 第95行表格第三列由 `赌博 (永久随机好/坏)` 改为 `永久随机良性词条 ×5 (满5层首次固化)`;并把第97-98行 `闪耀月光把结果变永久 (高赌注)。` 改为 `闪耀档不参与赌博: 临时档才有好/坏两面 (加权随机, 强度越高好结果概率越高); 闪耀档攒满 MAX_LAYERS_PER_TYPE=5 层后一次性用玩家 UUID 派生的确定性种子从 8 条良性词条池不重复抽 5 条 (击退抗性/护甲/护甲韧性/幸运/移速/攻击击退/攻击力/永久夜视), 永久生效、死亡清零, 未满层不固化任何东西。` 真源: src/main/java/com/miningdim/job/brewer/MoonshinePerk.java 与 BrewEffectEngine.java。

### `docs/Chef_Job_Mod_DesignSpec.md`

**[Major] [A-文档与代码不符] 6.1 膳香写绝对血量 20/40/60/80/200, 实现是最大血量千分比(且低/中档实为0, 与十二章4已改口的百分比结算矛盾)**

- 位置: 第 100 行
- 文档原文: | 膳香(额外回血) | 20 | 40 | 60 | 80 | 200 | 战斗向, 高品质解锁; 建议改 %最大血量(闪耀=满血); 进食可打断兜底 |
- 代码实际: 实现按最大生命值千分比结算, 且只为高/超凡/闪耀三档定义数值(低/中恒为 0, 因为战斗向受 combatUnlocked 门控): 高 75‰、超凡 100‰、闪耀 1000‰。在公服 80 血基线下等于 6 / 8 / 80 点, 与文档表里的 60 / 80 / 200 相差一个量级(高档差 10 倍)。同文档第十二章第 4 条已改口"膳香按最大生命值百分比结算", 但 6.1 表未同步。
- 取证: src/main/java/com/miningdim/job/chef/ChefConfig.java:238-241 `b.comment("6.1 combat heal as PER-MILLE of max HP (spec ch.12.4 mandates %maxHP; radiant=100% full heal). high/extra/radiant only"); HEAL_HIGH = b.defineInRange("high", 75, 0, 1000); HEAL_EXTRAORDINARY = b.defineInRange("extraordinary", 100, 0, 1000); HEAL_RADIANT = b.defineInRange("radiant", 1000, 0, 1000);`; src/main/java/com/miningdim/job/chef/ChefConfig.java:515-522 `healPerMille` 对 LOW/MEDIUM 返回 `default -> 0`
- 建议改法: 将 docs/Chef_Job_Mod_DesignSpec.md:100 该行改为 `| 膳香(额外回血, %最大血量) | — | — | 7.5% | 10% | 100%(满血) | 战斗向, 仅高/超凡/闪耀解锁(低/中恒为0); 进食可打断兜底 |`,并删除"建议改 %最大血量"一句已落地的历史建议措辞,使其与第十二章第4条"膳香按最大生命值百分比结算"保持一致。

**[Major] [B-文档互相打架] 第九章说数值进 MiningServerConfig, 实际是独立的 miningdim-chef.toml**

- 位置: 第 166 行
- 文档原文: 所有数值(品质倍率/效果数值/经验/调料映射/小游戏参数/增香黑名单)进 `MiningServerConfig` ForgeConfigSpec(或牌效式 datapack JSON), 硬编码即缺陷(C6)。
- 代码实际: 厨师数值全在独立的 `ChefConfig.SPEC`, 由 ChefSystem 注册成单独的 SERVER 级配置文件 `miningdim-chef.toml`, 与中央 MiningServerConfig 无关。同文档第十二章第 2 条已改口"由厨师专属 config 提供默认值", docs/modules/chef/README.md L16 也写明是 `miningdim-chef.toml`, 三处口径只有第九章是旧的。
- 取证: src/main/java/com/miningdim/job/chef/ChefSystem.java:41 `ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ChefConfig.SPEC, "miningdim-chef.toml");`
- 建议改法: 把 docs/Chef_Job_Mod_DesignSpec.md:166 的 `MiningServerConfig` 改为"厨师专属 `ChefConfig.SPEC`(注册为 SERVER 级配置文件 `miningdim-chef.toml`, 与中央 MiningServerConfig 无关)", 与第十二章第 2 条(238-241 行)及 docs/modules/chef/README.md:16 的口径统一。

**[Major] [A-文档与代码不符] 第十一章镇火/稳膛的中级档数值在实现中永不可达，且与同表披甲/凝脂的"—/—"体例不一致**

- 位置: 第 209-211 行
- 文档原文: | 镇火(灭火+火抗) | —/灭火/+8s/+12s/+18s | 推荐 | | 稳膛(抗击退+清缓慢) | —/50%/70%/85%/100% | 谨慎(必须 LivingKnockBackEvent) |
- 代码实际: 镇火(FIRE_QUELL)与稳膛(STABLE_AIM)在代码里都是 isCombat()=true, 而效果池只在 `quality.combatUnlocked()` 为真时才放入战斗向效果, ChefQuality 里只有 HIGH/EXTRAORDINARY/RADIANT 的 combatUnlocked=true。因此中级(MEDIUM)档永远掷不出这两个效果, 文档给出的"中级: 灭火"和"中级: 50% 抗击退"是死数值。ChefConfig 的 fireQuell 也只定义了 high/extra/radiant 三档。回甘(第 101 行)的低/中档 1/2 同理不可达, ChefEffectMagnitude 对 LOW/MEDIUM 直接返回 0。
- 取证: src/main/java/com/miningdim/job/chef/ChefQuality.java:20-24 `MEDIUM("medium", 1, 1, false, false, …)` 第五个参数即 combatUnlocked=false; src/main/java/com/miningdim/job/chef/SeasoningEffectRoller.java:96-102 `if (quality.combatUnlocked() && chefLevel >= ChefConfig.combatUnlockLevel()) { … pool.add(ChefEffectType.FIRE_QUELL); }` 与 L106-108 注释 `MEDIUM 不 combatUnlocked, 故稳膛实际最低从 HIGH 起 (与膳香/披甲一致), MEDIUM 列数值留作上限提示`; src/main/java/com/miningdim/job/chef/ChefEffectMagnitude.java:27-32 `case PURIFY -> switch (q) { case HIGH -> 3; case EXTRAORDINARY -> 4; case RADIANT -> 99; default -> 0; }`
- 建议改法: 将 docs/Chef_Job_Mod_DesignSpec.md 第209行"镇火"一行的低/中两列改为 `—/—/+8s/+12s/+18s`；第211行"稳膛"一行改为 `—/—/70%/85%/100%`(高/超凡/闪耀数值不变，与 ChefConfig.java:271-273 的 STABLE_AIM_HIGH/EXTRAORDINARY/RADIANT=700/850/1000 一致)；同理核查并修正第101行"回甘"的低/中两列(1/2)为 `—`(ChefEffectMagnitude.java:27-32 对 LOW/MEDIUM 恒返回0)。并在"战斗辅助(高品质门控)"小节标题下补充一句: "战斗向效果受 ChefQuality.combatUnlocked 门控(仅 高/超凡/闪耀 为 true)，低/中级效果池中不含任何战斗向条目，故本节中列出的低/中级数值均不可达"，使其与披甲、凝脂两行已采用的"—/—"体例保持一致。

**[Major] [D-覆盖缺口] 调味台每道菜扣信用点的经济 sink, 主设计文档完全没写**

- 位置: 第 26-33 行
- 文档原文: | 机制 | 一句话 | 章节 | | --- | --- | --- | | 品质 NBT | 给任意食物盖 `chefQuality`, 显示"超凡面包", 吃时给加成 | 三 | | 调味台 | 5 档方块 + 投成品菜 + 调料 + 烹饪小游戏(火候+调味) | 四 | …(全表无任何经济/成本项)
- 代码实际: 实现里每做一道菜要向厨师扣信用点(默认 5, 配置键 `economy.tableUseCostCredit`), 余额不足直接拒绝开工。这是一条真实的经济 sink, 主设计文档的机制总览、第四章调味台、第九章架构落地、第十三章工作分解全都没有提到, 只有 docs/modules/chef/README.md L18 一笔带过"信用点做菜成本"。
- 取证: src/main/java/com/miningdim/job/chef/ChefConfig.java:443-444 `b.comment("credit cost charged to the chef per dish seasoned (sink; 0 disables charging)"); TABLE_USE_COST_CREDIT = b.defineInRange("tableUseCostCredit", 5, 0, 1000000);`; src/main/java/com/miningdim/job/chef/SeasoningTableBlockEntity.java:460 `@param cost 做菜信用点成本 ({@link ChefConfig#TABLE_USE_COST_CREDIT}); <= 0 视为免费直接放行`
- 建议改法: 在 docs/Chef_Job_Mod_DesignSpec.md 第二章"核心机制总览"表格(26-33行)补一行, 例如 `| 经济 | 每道菜结算前扣信用点(config: economy.tableUseCostCredit, 默认 5, 0 关闭), 余额不足直接拒绝做菜并保留材料 | 四/九 |`；在第四章"调味台"(48-61行)结算流程处补一句"结算前经 EconomyServices 扣信用点, 经济子系统未注入或 cost<=0 时放行不扣, 余额不足则拒绝(材料保留)"；在第九章"架构落地"(160-168行)补一条"信用点扣费经 EconomyServices 定位器接入, 不做 per-job 静态 bind"；第十三章工作分解(250-263行)厨师本体清单里补一条经济sink接入项。同时建议修正代码注释里失效的"Chef_Job_DesignSpec 7.2"引用, 改指文档更新后的实际章节号。

**[Major] [A-文档与代码不符] QTE 加成文档写成"每次正确 QTE 固定 +10 个百分点",代码实际按命中率(命中数/时机点总数)乘固定 500 千分比上限折算**

- 位置: 第 56 行
- 文档原文: 目标基础表现率为低/中/高/超凡/闪耀 `100%/70%/45%/25%/10%`；完美控火最多增加 50 个百分点，每次正确 QTE 在表现率中增加 10 个百分点，表现率先封顶 100%。
- 代码实际: 实现不按命中次数发固定加成, 而是按命中率 hits/totalCues 乘一个固定的 500 千分比上限(即完美命中恒得 50 个百分点, 与时机点总数无关)。这是为了防止低档台多派时机点反而更容易出高品质的台档倒挂。只有在"完美操作"这一个点上两套公式恰好都被 1000‰ 封顶而巧合一致, 非完美操作时结果差异极大(例: 闪耀目标、6 个时机点命中 2 次、控火 0 —— 按文档是 10%+20pp=30%, 按代码是 100‰+(2/6)×500‰=267‰=26.7%)。
- 取证: src/main/java/com/miningdim/job/chef/ChefQualityResolver.java:30-34 `// QTE 加成按命中率而非命中数计: cue 数会随台档不足/等级不足上浮, 若按命中数发固定加成, 多派的 cue 就成了净收益` / `+ (int) Math.round((double) hits / totalCues * ChefConfig.targetQtePerfectBonusPerMille());`; src/main/java/com/miningdim/job/chef/ChefConfig.java:404-405 `TARGET_QTE_PERFECT_BONUS_PER_MILLE = b.defineInRange("targetQtePerfectBonusPerMille", 500, 0, 1000);`
- 建议改法: 将 docs/Chef_Job_Mod_DesignSpec.md 第56行"完美控火最多增加 50 个百分点，每次正确 QTE 在表现率中增加 10 个百分点，表现率先封顶 100%。"改为:"完美控火最多增加 50 个百分点，QTE 按命中率(命中数/时机点总数)折算最多增加 50 个百分点(多派的时机点只抬难度、不抬期望加成，否则低档台会倒挂)，表现率先封顶 100%。"使其与 docs/modules/chef/README.md 第20行"命中率(命中数/时机点总数) x targetQtePerfectBonusPerMille"的口径统一,并顺带消除两份文档之间的B类矛盾。

**[Major] [A-文档与代码不符] 结算算法漏掉下探衰减系数, 按文档算出的下探概率全错**

- 位置: 第 56 行
- 文档原文: 服务端在开始时锁定厨师等级、台档与 QTE 参数，结算时只生成一个 `0-999` 随机数，从所选目标向低档依次对照每档实时阈值；首个命中的档位成为产出，全部高档阈值均未命中则回落到低品质。
- 代码实际: 实现并不是直接对照"每档实时阈值"：每比所选目标低一档，就把该档阈值再乘一次 `targetDowngradeDecayPerMille`(默认 900‰)。这条衰减是整套目标选择机制成立的前提(代码注释称没有它时中/高/超凡三个目标会退化成永远不该点的死选项)，主文档全篇未提，只有 docs/modules/chef/README.md L22 写了。按主文档口径算出的下探分布最多会偏离真实值 0.9^4≈0.66 倍。
- 取证: src/main/java/com/miningdim/job/chef/ChefQualityResolver.java:68-79 `int decayPerMille = ChefConfig.targetDowngradeDecayPerMille();` … `for (int step = target.tier() - candidate.tier(); step > 0; step--) { effective = (int) Math.round(effective * decayPerMille / 1000.0D); }`; src/main/java/com/miningdim/job/chef/ChefConfig.java:410 `TARGET_DOWNGRADE_DECAY_PER_MILLE = b.defineInRange("targetDowngradeDecayPerMille", 900, 0, 999);`
- 建议改法: 在 L56 该句后补一句: "下探时每比所选目标低一档，该档阈值再乘一次下探衰减系数(默认 900‰)，这是「瞄更高」的代价；不收这份代价时任一档的到手概率与所选目标无关，中/高/超凡三个目标会退化成死选项。"(与 docs/modules/chef/README.md:22 的表述保持一致; 若要在主文档中补充最大偏离倍数示例, 应写 RADIANT 目标下探到 MEDIUM 最多衰减 3 次、约 0.9^3≈0.73 倍, 而非 0.9^4)

### `docs/TarotReader_Mod_DesignSpec.md`

**[Major] [C-实现状态标错] 塔罗师全量上线, 文档仍标"进入编码前须确认 PENDING"**

- 位置: 第 10, 230, 245 行
- 文档原文: L10 `- 阻塞项: 进入编码前须完成第十一章两项框架级前置(多职业 capability 重构 + 公共 menu 脚手架), 并确认第十三章 PENDING。`; L230 `## 十三、待确认实现项 — 进 Code 前最终拍板 (PENDING)`; L245 `## 十四、实现期工作分解 (确认 PENDING 后展开)`
- 代码实际: 塔罗师是已上线职业: JobId 有 TAROT 常量, job/tarot 包 56 个 Java 文件 / 67 个 GameTest, 22 张牌的 datapack JSON 全部落地, /tarot 命令树、卡包、合成、闪耀自选 GUI 全部可用, 十三章列的九条 PENDING 数值(GCD 1.5s=30tick、合成四结果概率、每日限购、碎片兑换)已全部以默认值写进 TarotConfig。文档仍以"尚未开工"的姿态呈现, 读者会误判这是待排期需求。
- 取证: src/main/java/com/miningdim/job/JobId.java:25 `TAROT("tarot"),`; docs/modules/INVENTORY.md 中 `| wok-job-tarot | WOK-塔罗师模块 | 56 | 67 | TarotSystem | ...`; src/main/java/com/miningdim/job/tarot/TarotConfig.java:97-105 `b.push("cooldown"); ... defineInRange("gcdTicks", 30, 1, 200);` 与 :150-163 `b.push("craft"); CRAFT_R_SUCCESS = ... defineInRange("rToSrSuccess", 0.50D, ...)`; ls src/main/resources/data/miningdim/tarot/cards 共 22 个 json
- 建议改法: 删掉 L10 的阻塞项一句(第十一章两项框架级前置——EnumMap&lt;JobId,JobProgress&gt; 重构见 job/JobData.java、公共 menu 脚手架见 com.miningdim.menu.ModMenus——均已建成并被 TarotRegistry 消费, 不再是前置阻塞); 把十三章标题由 "## 十三、待确认实现项 — 进 Code 前最终拍板 (PENDING)" 改为 "## 十三、已落地的系统级闸门(原 PENDING, 现以 TarotConfig 默认值为准)", 并在每条数值后标注对应 config 键(如 第1条 GCD 标注 TarotConfig.GCD_TICKS=30、第6条碎片保底标注 TarotConfig.SHARD_EXCHANGE_COST=40、第八章合成概率标注 CRAFT_R_SUCCESS 等); 十四章标题由 "## 十四、实现期工作分解 (确认 PENDING 后展开)" 改为 "## 十四、实现分解(已完成, 留作实现索引)"。

**[Major] [C-实现状态标错] "卡牌禁入漏斗、禁交易"缺 config 开关且漏斗防护零实现，配套标签是死资源；文档称"禁交易"但实际是市场模块内硬编码的品质门槛（非配置项，只挡非R档、不挡R档、不读tarot_bound标签）**

- 位置: 第 193 行
- 文档原文: - 卡牌/卡包默认禁入漏斗、禁交易(config 开关, 默认绑定)。
- 代码实际: 同章其它反代练项(ownerUUID 盖章与用牌校验、每日限购、信用点扣费)都已落地, 唯独这一条完全没有实现: TarotConfig 里没有任何绑定/禁交易开关, com.miningdim.job.tarot 整包搜不到一处漏斗相关代码, 全仓库唯一的漏斗防护在军火商模块。为它准备的物品标签 `data/miningdim/tags/items/tarot_bound.json` 确实存在并列了 4 个 ID, 但代码里没有任何一处读取它 —— 是彻底的死资源。按本条做安全判断(以为卡牌不可被漏斗搬运/不可交易)会得出错误结论。
- 取证: src/main/resources/data/miningdim/tags/items/tarot_bound.json 列有 `miningdim:tarot_card`、`miningdim:tarot_pack_common/advanced/shiny`, 但仓库内 grep `tarot_bound` 零命中于 src/; src/main/java/com/miningdim/job/munitions/MunitionsGameTests.java:1641 `inputCapabilityIsInsertOnlyAgainstHoppers` 是全仓库唯一的 hopper 防护实现(不在塔罗包内); src/main/java/com/miningdim/job/tarot/TarotPlayHandler.java:51 `// 闸门 1: ownerUUID 校验 (spec 第十章)。`(只有 owner 校验这一道)
- 建议改法: 把第193行改写为区分两件事的准确描述, 例如: "- 卡牌/卡包已定义绑定标签 `#miningdim:tarot_bound`(tarot_card + 三种卡包), 但【禁入漏斗尚未实现】: TarotConfig 无任何相关 config 项, `job.tarot` 包与全仓库 mixin 均无一处 hopper 防护代码, 该标签当前是无人读取的死资源。【禁交易仅部分实现】: 市场模块 `MarketTradeWhitelist`(硬编码规则, 非本行所述的 config 开关)在挂单路径拦截 SR/SSR/UR/闪耀四档, 但放行最低档 R 自由挂单出售, 且该规则不拦截丢弃拾取/直接给予等非市场转移方式, 也未使用 `tarot_bound` 标签。" 后续实现建议(供拍板, 非本审计越权决策): 若产品意图仍是"全品质默认绑定+可配置开关", 需要 1) 为 `job.tarot` 补齐反漏斗保护(仿 munitions/engineer 的 InsertOnlyRangedWrapper 模式, 或让 hopper 交互代码读取 `tarot_bound` 标签); 2) 把市场品质门槛从硬编码迁移到 `TarotConfig` 做成可配置开关, 并决定 R 档是否也要一并纳入禁交易范围。

**[Major] [A-文档与代码不符] 第十一章说全局旋钮走 MiningServerConfig, 实现刻意不进中央 config**

- 位置: 第 206 行
- 文档原文: - 牌效数值表走 datapack JSON(每张牌一份, 仿 `ORE_USE_DATAPACK` 先例), 全局旋钮(卡包出率/合成四结果概率/等级门控/每日上限/用牌 CD/易伤%)走 `MiningServerConfig`。datapack 缺字段报错冒泡, 不静默给默认(C9)。
- 代码实际: 实现刻意没有走 MiningServerConfig: 塔罗自带独立 SERVER 级 ForgeConfigSpec `TarotConfig.SPEC`, 由 TarotSystem 注册成 `miningdim-tarot.toml`, 而且 TarotConfig 的类注释专门写了一段"为何不塞进 com.miningdim.config.MiningServerConfig"来反驳本条。牌效数值表走 datapack 这半句是对的。
- 取证: src/main/java/com/miningdim/job/tarot/TarotConfig.java:6-12 `独立 SERVER 级 ForgeConfigSpec, 由 {@link TarotSystem} 在 register 内 registerConfig("miningdim-tarot.toml")。为何不塞进 com.miningdim.config.MiningServerConfig: … 工程惯例是 "谁的旋钮谁带 spec 段"。塔罗师照此自带本类, 不污染中央 config`; src/main/java/com/miningdim/job/tarot/TarotSystem.java:76 `net.minecraftforge.fml.ModLoadingContext.get().registerConfig(`
- 建议改法: 将 docs/TarotReader_Mod_DesignSpec.md 第206行"走 `MiningServerConfig`"改为"走塔罗自带的 `TarotConfig.SPEC`(独立 SERVER 级 ForgeConfigSpec, 由 TarotSystem.register 内 registerConfig 注册为文件 miningdim-tarot.toml; 按仓库既有"谁的旋钮谁带 spec 段"惯例, 不进中央 MiningServerConfig, 参见 TarotConfig.java 类注释)", 其余描述(datapack 缺字段报错冒泡)保持不变。

**[Major] [A-文档与代码不符] 塔罗全局旋钮文档写成走 MiningServerConfig, 实为独立 toml; 且文档中"等级门控/易伤%"并非配置项而是硬编码常量**

- 位置: 第 206 行
- 文档原文: - 牌效数值表走 datapack JSON(每张牌一份, 仿 `ORE_USE_DATAPACK` 先例), 全局旋钮(卡包出率/合成四结果概率/等级门控/每日上限/用牌 CD/易伤%)走 `MiningServerConfig`。
- 代码实际: 塔罗所有全局旋钮实际落在独立的 miningdim-tarot.toml(TarotConfig 自持一份 SERVER 级 SPEC), MiningServerConfig 里 grep "tarot" 命中 0 行。文档点名的六类旋钮(卡包出率 gacha.*、合成四结果概率 craft.*、每日上限 economy.dailyPackLimit、用牌 CD cooldown.*)全部在 miningdim-tarot.toml。服主按文档去 miningdim-server.toml 找这些键会一个都找不到。
- 取证: src/main/java/com/miningdim/job/tarot/TarotSystem.java:76-78 `net.minecraftforge.fml.ModLoadingContext.get().registerConfig(..., TarotConfig.SPEC, "miningdim-tarot.toml");`; src/main/java/com/miningdim/job/tarot/TarotConfig.java:130-146 `b.push("gacha"); ... defineInRange("advancedSsrChance", 0.20D, ...)`; :119-127 `b.push("economy"); ... defineInRange("dailyPackLimit", 20, 0, 100000);`; src/main/java/com/miningdim/config/MiningServerConfig.java 全文 grep -i tarot 零命中
- 建议改法: 将 docs/TarotReader_Mod_DesignSpec.md 第206行后半句拆开改写, 不要把六项笼统并列为"走 MiningServerConfig": (1) 卡包出率(gacha.*)/合成四结果概率(craft.*)/每日上限(economy.dailyPackLimit)/用牌CD(cooldown.*) 四项: 改为"走本职业自持的独立配置 `TarotConfig`, 注册为独立文件 `miningdim-tarot.toml`(范式同 ChefConfig), 不进中央 `MiningServerConfig`"(证据: TarotSystem.java:76-78 registerConfig 调用; TarotConfig.java 第97-164行 cooldown/economy/gacha/craft 四段)。 (2) 等级门控与易伤%两项: 从"全局旋钮"描述中移除或单独说明——用牌等级门控实为硬编码在 `TarotQuality` 枚举(TarotQuality.java 第6、13-21、26行, `requiredLevel` 构造时写死); 易伤放大百分比实为硬编码在共享 `VulnerabilityEffect.LEVEL_PCT` 数组(VulnerabilityEffect.java 第22行), 二者均不经任何 ForgeConfigSpec, 服主无法通过任何 toml 文件调整, 需改源码。

**[Major] [D-覆盖缺口] docs/ 下无塔罗牌效 datapack JSON 格式文档(字段语义已在 Java javadoc 中写明, 但与仓库内两个同类 datapack 子系统的既有文档惯例不一致)**

- 位置: 第 206 行
- 文档原文: - 牌效数值表走 datapack JSON(每张牌一份, 仿 `ORE_USE_DATAPACK` 先例)... datapack 缺字段报错冒泡, 不静默给默认(C9)。
- 代码实际: 文档只说"走 datapack JSON", 但全仓没有一份说明这些 JSON 的字段结构。data/miningdim/tarot/cards/ 下 22 份文件由 TarotCardLoader 读取, 结构相当复杂(按品质分段、每段一组 op 列表、含 shiny.cooldownTicks、durationTicks、自定义效果 op 名等), 且按文档口径缺字段直接抛错。服主或后续开发要新增/调一张牌, 只能反推代码。对照同类的 gunsmith/components 是有 docs/Gunsmith_Component_Creation_Rules.md + Gunsmith_Component_Hot_Reload_Rules.md 两份格式文档的, 塔罗这边完全空白。
- 取证: src/main/java/com/miningdim/job/tarot/card/TarotCardLoader.java(SimpleJsonResourceReloadListener 读 data/<ns>/tarot/cards); ls src/main/resources/data/miningdim/tarot/cards 共 22 个文件(00_fool.json ... ); docs/Full_Repo_Audit_2026-08.md:442 引用 `data/miningdim/tarot/cards/10_wheel_of_fortune.json:128 shiny.cooldownTicks=14400` 与 `18_moon.json:339-340 的 self_untargetable durationTicks = 160`, 说明字段层级深且无处可查
- 建议改法: 新建 docs/Tarot_Card_Datapack_Format.md, 体例参照 docs/Gunsmith_Component_Creation_Rules.md 与 docs/Fishing_Journal_Framework.md(后者第 29 行起的 ```json 字段示例可直接借鉴排版方式)。内容可直接汇总已有的 Java javadoc, 无需重新调研: 顶层结构照抄 TarotCardData.java:13-27 的类注释(cooldownCategory/upright.tiers[4]/reversed.tiers[4]/shiny.cooldownTicks+ops); 各 kind 必填字段与语义照抄 TarotEffectKind.java 各枚举项注释 + TarotEffectOp.java 的 fromJson switch 分支(约 50 个 kind, 每个列出用到的字段名与含义); 报错行为注明"字段缺失即 GsonHelper 抛 JsonSyntaxException 冒泡, 不给默认值(C9)"。最后在 docs/TarotReader_Mod_DesignSpec.md:206 加一条指向该文档的链接。

**[Major] [E-状态过期] 全职业已上线, 文档仍标 PENDING 并写着"进入编码前须…"**

- 位置: 第 8-10 行
- 文档原文: - 状态图例: DECIDED 已定稿 / PENDING 待最终确认(已给推荐默认值) / TODO 实现期补全。 - 数值状态: 22 张大阿卡纳的正位/逆位/闪耀效果已逐张评审定稿(DECIDED); 6 项系统级总闸门已给推荐默认值, 待最终确认(第十三章)。 - 阻塞项: 进入编码前须完成第十一章两项框架级前置(多职业 capability 重构 + 公共 menu 脚手架), 并确认第十三章 PENDING。
- 代码实际: 两项"阻塞"前置早已完成(com.miningdim.menu 公共脚手架存在, JobId 已是 9 个职业的枚举), 第七/八/九/十/十三章标 PENDING 的推荐默认值也已逐条落进 TarotConfig 并注册成 miningdim-tarot.toml: GCD 30 tick、每卡 CD 200/500/900、单牌经验 8/16/32/60/120、合成四结果 0.50/0.12/0.28 等、UR→闪耀 0.15、每日购包 20、碎片兑换 40 —— 与文档推荐值逐一对上。22 张牌的 datapack JSON 也已全部存在且闪耀 CD 与第六章全表完全一致。读者按现状会误以为塔罗师尚未开工。
- 取证: src/main/java/com/miningdim/job/tarot/TarotConfig.java:98-99 `GCD_TICKS = b.comment("Global cooldown between any two card plays, in ticks (spec 9.5: 1.5s = 30)").defineInRange("gcdTicks", 30, 1, 200);` 及 L151-163 的 `rToSrSuccess 0.50 / srToSsrSuccess 0.40 / ssrToUrSuccess 0.28 / urToShinySuccess 0.15`; src/main/resources/data/miningdim/tarot/cards/ 下 00_fool.json…21_world.json 共 22 份; src/main/java/com/miningdim/menu/AbstractMiningMenu.java 与 src/main/java/com/miningdim/job/JobId.java:20-32(MINER…BREWER 共 9 项)
- 建议改法: 将文档元信息(第 8-10 行)改为: "状态: 第十一章两项框架级前置(EnumMap&lt;JobId,JobProgress&gt; 重构见 entry.IMiningPlayerData#jobProgress、公共 menu 脚手架见 com.miningdim.menu.ModMenus)与塔罗师本体均已实现并合入 main(TarotSystem 已挂入 MiningDim 子系统列表); 第十三章 PENDING 推荐值已按原值落进 TarotConfig(运行期文件 miningdim-tarot.toml), 如需调整改 toml 或改 TarotConfig 默认值即可, 无需重新拍板。" 同时删除第 10 行"阻塞项"表述, 并把第七/八/九/十/十三章标题的 "(PENDING — 已给推荐默认值)" 改为 "(已实现, 数值见 TarotConfig, 如需调整可再评审)"。

### `docs/modules/chef/README.md`

**[Major] [B-文档互相打架] README 声称拥有十个效果图标, 资源所有权表把它们判给 WOK-核心**

- 位置: 第 48 行
- 文档原文: 本模块拥有 `seasoning_table_*` blockstate/model、`geo/block/seasoning_table.geo.json`、`animations/block/seasoning_table.animation.json`、五档静态方块纹理与五档 `seasoning_table_*_geo.png` 动态模型图集、调味台 GUI、十个窗口效果图标、`recipes/chef/`…
- 代码实际: docs/modules/RESOURCE_OWNERSHIP.md 明确把这十个图标判给 WOK-核心: "菜肴效果图标 `textures/mob_effect/chef_*.png` 物理归 WOK-核心: 它们与 `ModJobEffects` 这个跨职业共享效果注册表同处一个目录, 按目录整体登记"。module-registry.json 也把整个 `assets/miningdim/textures/mob_effect` 目录登记在 wok-core 的 resourcePaths 下, wok-job-chef 的 resourcePaths 里一条 assets 路径都没有。十个 chef_*.png 确实物理存在于该共享目录。
- 取证: docs/modules/RESOURCE_OWNERSHIP.md:26 `菜肴效果图标 textures/mob_effect/chef_*.png 物理归 WOK-核心`; docs/modules/module-registry.json:42-44 wok-core 的 `"resourcePaths": [ "assets/miningdim/textures/mob_effect" ]`; src/main/resources/assets/miningdim/textures/mob_effect/ 下实有 chef_aftertaste_regen.png、chef_endurance.png 等 10 个文件与 vulnerability.png 同目录
- 建议改法: 把 chef/README.md 第48行的"十个窗口效果图标"改为"十个窗口效果图标的行为语义与 lang 键(PNG 文件按目录整体归 WOK-核心, 见 RESOURCE_OWNERSHIP.md 与 module-registry.json 的 wok-core.resourcePaths)"; 或者反过来在 RESOURCE_OWNERSHIP.md 与 module-registry.json 里按 `chef_` 文件名前缀把这十个图标划归 wok-job-chef 的 resourceNamePrefixes。二选一即可, 但必须让 README.md 与 RESOURCE_OWNERSHIP.md/module-registry.json 三处口径一致, 且改动后需重跑 verifyModuleBoundaries 确认不因目录/前缀双重匹配冲突而失败。

**[Major] [B-文档互相打架] 厨师 README 与 RESOURCE_OWNERSHIP/module-registry 对同一批十个效果图标给出互斥的物理归属结论**

- 位置: 第 48 行
- 文档原文: 本模块拥有 `seasoning_table_*` blockstate/model ... 调味台 GUI、十个窗口效果图标、`recipes/chef/` ...
- 代码实际: docs/modules/RESOURCE_OWNERSHIP.md:26 对同一批文件的结论相反: 「菜肴效果图标 `textures/mob_effect/chef_*.png` 物理归 WOK-核心: 它们与 `ModJobEffects` 这个跨职业共享效果注册表同处一个目录, 按目录整体登记」。登记表也把整个 assets/miningdim/textures/mob_effect 判给 wok-core, 厨师的 resourcePaths 里没有这一项。两份文档对十个 chef_*.png 给出互斥归属, 提交时按哪份都可能越界。
- 取证: docs/modules/module-registry.json wok-core 条目 `"resourcePaths": ["assets/miningdim/textures/mob_effect"]`, wok-job-chef 条目的 resourcePaths 为 recipes/chef 与四个 tags 文件, 不含 mob_effect; `ls src/main/resources/assets/miningdim/textures/mob_effect/` 返回 10 个 chef_*.png 加 vulnerability.png 共 11 个文件; src/main/java/com/miningdim/effect/ModJobEffects.java:34-52 在核心 effect 包里注册这十个效果。
- 建议改法: 把 docs/modules/chef/README.md:48 中的「十个窗口效果图标」改为「十个窗口效果图标的业务内容与注册键(图标文件物理归 WOK-核心的 `textures/mob_effect/` 目录及 `module-registry.json` 中 wok-core 的 resourcePaths, 见 RESOURCE_OWNERSHIP.md 第 26 行; 本模块仅拥有其效果行为定义与触发逻辑)」, 使该行与 RESOURCE_OWNERSHIP.md/module-registry.json 保持单一归属结论, 避免按字面误将这十个 PNG 当作 chef 分支可自由改动的专属资产。

**[Major] [F-结构问题] 模块 README 与设计规格双轨并行且零互链, 子目录只覆盖 3/26 模块**

- 位置: 第 50 行
- 文档原文: L50「运行期 PNG 和 GeckoLib JSON 由 `tools/generate_chef_assets.py` 与 `tools/generate_chef_gecko_assets.py` 可复现生成；挑选后的模型说明预览保存在 `docs/assets/chef/`…」(全文未出现一次 Chef_Job_Mod_DesignSpec)
- 代码实际: 仓库同时存在两套文档组织法: docs/ 根的 *_DesignSpec.md, 和 docs/modules/<module>/README.md。后者只建了 chef、experience、farmer 三个子目录(覆盖 26 个模块中的 3 个), 且两套体系零互链 —— 三份模块 README 都不引用对应的 DesignSpec, 三份 DesignSpec 也都不引用对应的模块 README(grep 双向零命中)。读者无法判断同一模块的两份文档谁管什么、哪份更新。
- 取证: `ls docs/modules/` 只有 chef、experience、farmer 三个子目录, 对照 docs/modules/module-registry.json 的 26 个模块。docs/modules/README.md:64「[WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板。」—— 连这份索引自己都漏了已存在的 chef/README.md。`grep -n 'modules/' docs/Chef_Job_Mod_DesignSpec.md` 零命中。
- 建议改法: 二选一并写进 docs/README.md: 要么把 docs/modules/<module>/README.md 明确降格为「模块交付清单(注册 ID / 资源 / 测试入口)」并强制头部回链设计规格, 要么把设计规格整体迁进 docs/modules/<module>/ 与交付清单合署。无论选哪条, 都补齐 docs/modules/README.md:64 对 chef/README.md 的登记, 并在模块总表加「设计文档」列与本条 suggestedFix 里的 designDocs 字段对齐。

### `docs/Brewer_Job_DesignSpec.md`

**[Minor] [F-结构问题] 文档内部交叉引用行号错误(第136行称"本文档第96行遗留", 实际内容在第110行, 且第96行本身是空行而非表格内容)**

- 位置: 第 136 行
- 文档原文: - 本文档第 96 行遗留的 `VODKA_DAMAGE_REDUCTION=0.20` 与代码现状 `0.05×5=0.25` 的分叉, 以哪个为准。
- 代码实际: `VODKA_DAMAGE_REDUCTION = 0.20` 出现在本文档第 110 行(第五节"单项数值帽"), 第 96 行是酒类型表里的月光行 `| 月光 moonshine | 赌博 (随机好/坏) | …`。按这条指引去第 96 行会找不到任何相关内容。代码侧无法取证(这是纯文档内交叉引用错误), 故定为 Minor。
- 取证: docs/Brewer_Job_DesignSpec.md:110 `伏特加永久减伤 \`VODKA_DAMAGE_REDUCTION = 0.20\` (与当前实现 0.05x5=0.25 不一致, 见五之补)。`; docs/Brewer_Job_DesignSpec.md:96 `| 白兰地 brandy | 急迫 (挖矿酒) | 永久急迫 | 小麦(大)·苹果 |` 附近即酒类型表, 与伏特加帽值无关
- 建议改法: 把第136行的"本文档第 96 行遗留的"改为"第五节『单项数值帽』一条遗留的"(用章节名定位以避免行号随后续编辑再次漂移), 或至少更正为"第 110 行"。另外, 复核证据本身也需更正: 不要将第96行描述为"白兰地行"或"月光行"表格内容——用 cat -n 核对后, 白兰地行实际在第87行、月光行实际在第95行, 第96行是表格结束后的一个空行, 按136行的指引跳转过去只会看到空白, 而非无关表格内容。

**[Minor] [A-文档与代码不符] 月光落地类名 BrewMoonshineTable 在代码中不存在**

- 位置: 第 98 行
- 文档原文: 落地: `WineType` + 后续 `BrewEffectEngine` / `BrewMoonshineTable`。
- 代码实际: 全仓库没有 `BrewMoonshineTable` 这个类(唯一命中就是本文档这一行)。月光的临时好/坏池在 `BrewEffectEngine.MOONSHINE_GOOD_POOL` / `MOONSHINE_BAD_POOL` + `moonshine(strength, rng)`, 永久良性词条池在独立的 `MoonshinePerk` 枚举。
- 取证: src/main/java/com/miningdim/job/brewer/BrewEffectEngine.java:28-34 `public static final MobEffect[] MOONSHINE_GOOD_POOL = {` / `public static final MobEffect[] MOONSHINE_BAD_POOL = {`; src/main/java/com/miningdim/job/brewer/MoonshinePerk.java:27 `public enum MoonshinePerk {`(仓库内 grep `BrewMoonshineTable` 只命中 docs/Brewer_Job_DesignSpec.md:98)
- 建议改法: 把 docs/Brewer_Job_DesignSpec.md:98 中的 `BrewMoonshineTable` 改为 `BrewEffectEngine.MOONSHINE_GOOD_POOL/MOONSHINE_BAD_POOL`(临时好/坏效果池)与 `MoonshinePerk`(闪耀永久良性词条池), 与代码实际类名对齐。

### `docs/TarotReader_Mod_DesignSpec.md`

**[Minor] [E-状态过期] JobId 枚举仍写成四个职业, 实际已扩展到八个(MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER; fisher 职业尚未并入 JobId, 原发现称"九个"有误)**

- 位置: 第 200 行
- 文档原文: 引入 `JobId{MINER,FARMER,ENGINEER,TAROT}` + `JobProgress{level,xp,dailyXp,dayStamp}`, 一处遍历 serialize/copyFrom
- 代码实际: JobId 现有 9 个成员(MINER、FARMER、ENGINEER、TAROT、CHEF、AGENT、MUNITIONS、BREWER 等), 该重构早已完成并被后续五个职业复用。这一行作为"塔罗师是第 4 个职业"的历史前置描述可保留, 但直接照抄枚举内容会误导新读者以为只有四个职业。
- 取证: src/main/java/com/miningdim/job/JobId.java:20-32 `public enum JobId {` / `MINER("miner"), FARMER("farmer"), ENGINEER("engineer"), TAROT("tarot"), CHEF("chef"), … AGENT("agent"), … MUNITIONS("munitions"), … BREWER("brewer");`
- 建议改法: 在 docs/TarotReader_Mod_DesignSpec.md:200 该句后补一句括注, 内容改为: "(本前置已完成; JobId 现已扩展到 8 个成员 MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER, 以 com.miningdim.job.JobId 源码为准; fisher 职业当前未并入该枚举, 不计入)"。不要照搬"9 个"这一错误计数。

### `docs/modules/chef/README.md`

**[Minor] [F-结构问题] docs/assets 六张图零 Markdown 引用, 其中一张全库零引用**

- 位置: 第 1(对照 docs/assets/ 目录整体) 行
- 文档原文: docs/assets/ 下存有 chef/(chef_asset_style_source.png、chef_gecko_workstation_preview.png、chef_runtime_asset_preview.png)与 tarot/(tarot_card_back_concept_v1.png、tarot_craft_table_concept.png、tarot_craft_ui_concept.png)共六张图。厨师模块 README(docs/modules/chef/README.md)与塔罗规格(docs/TarotReader_Mod_DesignSpec.md)均未嵌入或提及其中任何一张。
- 代码实际: 六张图没有任何 .md 文件引用, 即 docs/ 下存在一个不被任何文档消费的资产目录。其中五张至少被生成脚本或资源 JSON 当作来源锚点引用, 唯独 tarot_craft_ui_concept.png 在全仓(排除 .git)零引用, 既无文档也无脚本, 无法判断它对应哪一版界面稿、是否仍有效。
- 取证: 机械取证: 对六个文件名逐个做全仓 grep(排除 .git)——chef_asset_style_source -> ./tools/generate_chef_assets.py; chef_gecko_workstation_preview -> ./tools/generate_chef_gecko_assets.py; chef_runtime_asset_preview -> ./tools/generate_chef_assets.py; tarot_card_back_concept_v1 -> ./tools/build_tarot_card_assets.py; tarot_craft_table_concept -> ./src/main/resources/assets/miningdim/models/block/tarot_craft_table.json:2 「"credit": "Blue Archive-inspired celestial synthesis table, based on docs/assets/tarot/tarot_craft_table_concept.png"」; tarot_craft_ui_concept -> 零命中。限定 --include='*.md' 的同一轮 grep 六项全部零命中。
- 建议改法: 在 docs/modules/chef/README.md 的资产一节与 docs/TarotReader_Mod_DesignSpec.md 的美术相关章节分别嵌入或链接对应概念图, 并注明每张图的用途与对应生成脚本(如 chef_asset_style_source.png 对应 tools/generate_chef_assets.py); 对 tarot_craft_ui_concept.png 单独确认它是否仍是有效界面稿——若已作废则删除, 若有效则在塔罗规格中登记它对应的界面版本。

---

## 渔夫

### `docs/Fishing_Journal_Framework.md`

**[Major] [A-文档与代码不符] 分类清单遗漏 WOK 自建的 ore_fish（矿石鱼）分类，且深渊/熔岩两处译名与 lang key 不符**

- 位置: 第 25 行
- 文档原文: 分类沿用淡水、海水、地下、深层、群系、结构、岩浆、下界、末地、传说鱼。分类是查阅组织方式，不是 WOK 自定义稀有度或钓获概率。
- 代码实际: 实际存在 11 个分类：除文中列的 10 个（对应 freshwater/saltwater/underground/depths/biome/structure/lava/nether/end/legendary）之外，还有 WOK 自建的 `ore_fish`（简中「矿石鱼」），五种矿石鱼全部落在这个分类下。这恰恰是唯一一个不是「沿用」Tide 的分类，却被这份声称完整枚举的清单漏掉了。另外译名也对不上：`depths` 的简中是「深渊」不是「深层」，`lava` 是「熔岩」不是「岩浆」，`biome` 是「生物群系」，`legendary` 是「传说」。
- 取证: src/main/resources/assets/miningdim/lang/zh_cn.json:3 `"fishing.miningdim.category.ore_fish": "矿石鱼"`，:5 `"fishing.miningdim.category.depths": "深渊"`，:8 `"fishing.miningdim.category.lava": "熔岩"`，:4 `"fishing.miningdim.category.biome": "生物群系"`，:9 `"fishing.miningdim.category.legendary": "传说"`；src/main/resources/data/miningdim/fishing/journal/ore_fish.json 五条 entry 全部 `"category": "ore_fish"`。
- 建议改法: 将 docs/Fishing_Journal_Framework.md:25 改为：「分类共 11 个：矿石鱼（WOK 自建，五种矿石鱼专属）、淡水、海水、地下、深渊、生物群系、结构、熔岩、下界、末地、传说；后十个沿用 Tide 的组织方式。分类是查阅组织方式，不是 WOK 自定义稀有度或钓获概率。」译名按 src/main/resources/assets/miningdim/lang/zh_cn.json 第 3-13 行实际值对齐：depths→深渊（非深层）、lava→熔岩（非岩浆）、biome→生物群系（可保留“群系”简写但避免与其它译名混淆）。

**[Major] [D-覆盖缺口] 渔夫是唯一没有职业主设计文档的职业，只有两份特性说明**

- 位置: 第 5 行
- 文档原文: 本模块并入 WOK 本体，保持 `modId=miningdim`、Java 包根 `com.miningdim` 和单 JAR 交付。入口为 `com.miningdim.job.fisher.FishingSystem`，源码归属 `job/fisher`。
- 代码实际: 核实成立。docs/ 下八个职业各有主设计文档（miner=Miner_Job_DesignSpec.md、farmer=FarmingXP_Mod_DesignSpec.md、engineer=MillenniumEngineer_Mod_DesignSpec.md、tarot=TarotReader_Mod_DesignSpec.md、chef=Chef_Job_Mod_DesignSpec.md、agent=SpecialAgent_Job_DesignSpec.md、munitions=Munitions_Job_DesignSpec.md、brewer=Brewer_Job_DesignSpec.md），唯独 job/fisher 没有对应的 Fisher_Job_DesignSpec.md，只有 Fishing_Journal_Framework.md 与 Ore_Fish_And_Soup.md 两份特性说明。后果是模块级事实没有归口：本片另外查到的「配置键」「创造模式分类」「物品稀有度」「ore_fish 标签」四条缺口都落在无人认领的空白里。需要澄清的是等级曲线/XP 来源/JobId 并不是文档漏写——JobId 枚举确实没有 FISHER，两份文档也如实写了「尚未新增渔夫职业身份、等级、经验或技能」，那是功能未做而非文档缺失。
- 取证: src/main/java/com/miningdim/job/fisher/ 共 24 个 Java 文件（FishingSystem、journal/ 8 个、ore/ 7 个、soup/ 5 个、client/ 2 个、FishingAssetGameTests）；docs/modules/module-registry.json:501-533 已把它登记为独立模块 `"id": "wok-job-fisher"` / `"publicEntry": "com.miningdim.job.fisher.FishingSystem"`；docs 目录列表中不存在 Fisher_Job_DesignSpec.md 或等价文件；src/main/java/com/miningdim/job/JobId.java:22-32 枚举只有 MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER，无 FISHER，印证「职业身份未落地」这部分文档没有说谎。
- 建议改法: 新建 docs/Fisher_Job_DesignSpec.md 作为渔夫模块唯一主设计文档入口, 至少收编: 模块边界与入口(com.miningdim.job.fisher.FishingSystem)、五种矿石鱼的钓获概率/售价与出售链路(现有 world/serverconfig/miningdim-fishing.toml 配置键全表)、鱼羹效果与时长机制、图鉴玩家入口与收藏规则、创造模式分类与物品稀有度分级、ore_fish 标签用途、与 Tide/Farmer's Delight 的可选依赖关系、以及"职业身份/等级/经验尚未实现"的明确 DEFERRED 标注; Fishing_Journal_Framework.md 与 Ore_Fish_And_Soup.md 降级为该文档引用的子章节或专题说明, 不必删除但需在新文档中建立唯一入口索引。补充说明: 待核发现原文中 job/fisher 代码文件按子目录的拆分计数(ore 7个/soup 5个)与实测的 ore 8个/soup 4个略有出入, 总数 24 无误, 修复时以实测为准即可, 不影响本条问题成立与修复方案。

**[Major] [E-状态过期] 验证记录里的目录条数4/70早已作废,与同文档L23及Ore_Fish_And_Soup.md的9/75口径自相矛盾**

- 位置: 第 75-76 行
- 文档原文: - 未安装 Tide：加载 4 条目录，完整 GameTest 日志确认 `All 782 required tests passed`。 - 安装 Tide 1.6.5 与同实例 Cloth Config 11.1.136：加载 70 条目录，完整 GameTest 日志确认 `All 782 required tests passed`。
- 代码实际: 内置目录现在是 vanilla.json 4 条 + ore_fish.json 5 条 + tide_1_6_5.json 66 条。无 Tide 时加载 9 条，装 Tide 1.6.5 时加载 75 条。该验证块停在 2026-09-05 的图鉴框架单独存在时期（提交 d6361506），2026-09-06 的 77230f5e 加入五种矿石鱼后没有回改，而同一份文档 L23 已经写成「缺少 Tide 时加载 9 种原版与 WOK 鱼」，自相矛盾；docs/Ore_Fish_And_Soup.md:31/71 也写 75。
- 取证: src/main/resources/data/miningdim/fishing/journal/vanilla.json（entries 4 条）；src/main/resources/data/miningdim/fishing/journal/ore_fish.json:1-38（entries 5 条，逐条 "category": "ore_fish"）；src/main/resources/data/miningdim/fishing/journal/tide_1_6_5.json（entries 66 条，"required_mod": "tide" / "required_version": "1.6.5"）。机械计数：4 / 5 / 66。对照 docs/Ore_Fish_And_Soup.md:71「目录加载 75 项」。
- 建议改法: 把 Fishing_Journal_Framework.md 第75-76行改为:"- 未安装 Tide：加载 9 条目录（4 原版 + 5 矿石鱼），完整 GameTest 日志确认 `All 1457 required tests passed`。""- 安装 Tide 1.6.5 与同实例 Cloth Config 11.1.136：加载 75 条目录，完整 GameTest 日志确认 `All 1457 required tests passed`。"(测试数与目录数直接复用 docs/Ore_Fish_And_Soup.md 第71行2026-09-06记录的口径,不要沿用第782条这个仅在矿石鱼加入前成立的历史值)。同时应在"2026-09-05 验证结果"标题旁补一句说明,标明该区块是矿石鱼功能加入前的历史快照,当前有效数据以 Ore_Fish_And_Soup.md 的2026-09-06记录为准,避免读者把过期数字当成现行结论。

### `docs/Ore_Fish_And_Soup.md`

**[Major] [D-覆盖缺口] 只给了 toml 路径，配置分节与键名一个字没写，照表格改会改不到**

- 位置: 第 11 行
- 文档原文: 以下为可调整的首测值，配置文件 `world/serverconfig/miningdim-fishing.toml`。概率以每次成功钓获为分母，总权重不得超过 10000。
- 代码实际: 配置里有两个分节 `[catch_weights]` 与 `[sell_prices]`，键名是 OreFishType.id()，即 iron / gold / diamond / emerald / dark_gold，**不是**表格第二列的 iron_ore_fish 这套物品 ID；权重取值范围 [0,10000]、售价取值范围 [1,1000000]；总权重超 10000 会在 ModConfigEvent.Loading/Reloading 直接抛 IllegalArgumentException。文档只给了路径和一张「物品 ID」列的表，服主照着找 iron_ore_fish 这个键会找不到，凭表格猜键名改配置必然改错。
- 取证: src/main/java/com/miningdim/job/fisher/ore/OreFishingConfig.java:16-20 `builder.comment("每次矿洞水域成功钓获的万分权重…").push("catch_weights"); int[] weights = {2000, 800, 200, 100, 20}; … CATCH_WEIGHTS.put(type, builder.defineInRange(type.id(), weights[type.ordinal()], 0, 10000));`；同文件 :23-27 `push("sell_prices")` + `builder.defineInRange(type.id(), prices[type.ordinal()], 1L, 1000000L)`；键名来源 src/main/java/com/miningdim/job/fisher/ore/OreFishType.java:7-11 `IRON("iron", …) GOLD("gold", …)` 与 :21-23 `public String id()`；注册名 src/main/java/com/miningdim/job/fisher/FishingSystem.java:45 `registerConfig(ModConfig.Type.SERVER, OreFishingConfig.SPEC, "miningdim-fishing.toml")`。
- 建议改法: 在 L11 后补一段配置样例，明确写出：`[catch_weights]` 下 iron/gold/diamond/emerald/dark_gold 五个万分权重键（范围 0-10000，五者之和 >10000 会在 ModConfigEvent.Loading/Reloading 时直接抛 IllegalArgumentException 拒绝加载），`[sell_prices]` 下同名五个售价键（范围 1-1000000）；并把表格第二列标题从「物品 ID（miningdim）」改为「物品 ID / 配置键」两列或另加一列注明配置键（iron/gold/diamond/emerald/dark_gold），避免服主把 iron_ore_fish 这个物品 ID 误当成配置键去改。

**[Major] [A-文档与代码不符] 简中鱼名实际是「铁鱼/金鱼」，文档表格写的「铁矿鱼/金矿鱼」并不存在**

- 位置: 第 13-19 行
- 文档原文: | 鱼种 | 物品 ID（miningdim） | 概率 | 单条基础信用点 | | 铁矿鱼 | iron_ore_fish | 20% | 20 | | 金矿鱼 | gold_ore_fish | 8% | 80 | | 钻石矿鱼 | diamond_ore_fish | 2% | 400 | | 绿宝石矿鱼 | emerald_ore_fish | 1% | 600 | | 暗金矿鱼 | dark_gold_ore_fish | 0.2% | 2000 |
- 代码实际: zh_cn 里这五个物品的显示名是「铁鱼 / 金鱼 / 钻石鱼 / 绿宝石鱼 / 暗金鱼」，全部丢了「矿」字；en_us 反而是 Iron Ore Fish / Gold Ore Fish… 与文档一致。也就是说文档表格、en_us、同模块的 tools/assets/fishing/v1/README.md 三者对不齐：README L15 跟 zh_cn 一样写「铁鱼、金鱼、绿宝石鱼」。「金鱼」在简中是另一种真实鱼类，玩家在游戏里看到的名字与全部设计文档都对不上。概率与售价列本身与代码一致（权重 2000/800/200/100/20，售价 20/80/400/600/2000），问题只在名称列。
- 取证: src/main/resources/assets/miningdim/lang/zh_cn.json:29-33 `"item.miningdim.iron_ore_fish": "铁鱼"` / `"item.miningdim.gold_ore_fish": "金鱼"` / `"item.miningdim.diamond_ore_fish": "钻石鱼"` / `"item.miningdim.emerald_ore_fish": "绿宝石鱼"` / `"item.miningdim.dark_gold_ore_fish": "暗金鱼"`；对照 src/main/resources/assets/miningdim/lang/en_us.json:29-33 `"item.miningdim.iron_ore_fish": "Iron Ore Fish"`；tools/assets/fishing/v1/README.md:15「铁鱼、金鱼、绿宝石鱼采用已选 v1，钻石鱼采用真实透明的 v3」。
- 建议改法: 先拍板一个真源: 若以设计文档为准, 把 src/main/resources/assets/miningdim/lang/zh_cn.json 第29-33行的 iron_ore_fish/gold_ore_fish/diamond_ore_fish/emerald_ore_fish/dark_gold_ore_fish 五条显示名改成"铁矿鱼/金矿鱼/钻石矿鱼/绿宝石矿鱼/暗金矿鱼", 并同步改 tools/assets/fishing/v1/README.md 第15行的措辞; 若以游戏内既有名为准, 把 docs/Ore_Fish_And_Soup.md 第3行与第13-19行表格首列一并改成"铁鱼/金鱼/钻石鱼/绿宝石鱼/暗金鱼"。二选一落地, 不要让文档表格、zh_cn.json、tools README 三处继续各写各的。

**[Major] [F-结构问题] 卖鱼无身份门的 PENDING 裁决已在经济总表落地,但模块文档未回链定位,读者会误判该问题仍未拍板**

- 位置: 第 27-29 行
- 文档原文: 待决（未闭合）：`/fishing sell` 目前**没有身份门**。…这与既有的跨账号洗额度结构性问题同源，须与经济总表一并定夺，不在本模块单独决策。
- 代码实际: 核查结论：这条 PENDING 确实已经登记，落在 docs/Economy_BalanceSheet_DesignSpec.md:142，并且带了明确日期与裁决（2026-09-19 按现状合入、挂 PENDING、待渔夫职业等级落地后补门，或与跨账号洗额度一并按方向 A/B 处理）。所以不构成漏登记。问题只在链接是单向的：经济总表 L41 回链了 Ore_Fish_And_Soup.md，但本文这段既无日期也无指向裁决记录的链接，只读本文的人会以为这事还悬在空中、没人拍过板。命令侧无 `.requires` 门这一事实与文档描述完全一致，属实。
- 取证: docs/Economy_BalanceSheet_DesignSpec.md:142 `6. **渔夫卖鱼无身份门（未闭合）**：\`/fishing sell\` 目前对所有玩家开放。…决策（2026-09-19）：按现状合入，此条挂 PENDING，待渔夫职业等级落地后补门…`；代码侧印证无门：src/main/java/com/miningdim/job/fisher/FishingSystem.java:80-81 `event.getDispatcher().register(Commands.literal("fishing").then(Commands.literal("sell").executes(OreFishSellService::executeSell))` 未挂任何 `.requires(...)`。
- 建议改法: 在 docs/Ore_Fish_And_Soup.md 第29行末尾补一句回指,并同步核对措辞与经济总表一致,例如:「该条已于 2026-09-19 登记为 PENDING 并裁决按现状合入,权威记录与后续处理路线(方向A职业门/方向B全服供给定价)见 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 第六节第6条。」同时建议反向核对 Economy_BalanceSheet_DesignSpec.md:41 的角标链接,使其明确指向第142条而非泛指渔夫模块,避免两处链接各自表述、互不对齐。

**[Major] [A-文档与代码不符] 厨师耐饥互斥写成「铁/暗金」，代码里五种鱼羹都会改写厨师耐饥的结算机制**

- 位置: 第 49 行
- 文档原文: 铁/暗金耐饥与厨师 `ENDURANCE` 取较强者，避免双重结算。
- 代码实际: ChefHungerHandler 的早退条件是 `OreSoupEffects.activeInMining(player)`，对五种鱼羹一视同仁。喝金/钻/翠玉鱼羹（本身零耐饥）在矿洞里同样会让厨师 ENDURANCE 的「周期回补饱和」整条路径被跳过，改由 OreSoupEffects.reduceExhaustion 走 max(0, chefReduction) 的疲劳缩减来结算。厨师效果没丢，但结算机制被换掉、数值口径也不同（每 40 tick 回补 1.0×比例饱和 vs 疲劳乘 (1000-reduction)/1000）。按文档只会预期铁/暗金有这种互斥。
- 取证: src/main/java/com/miningdim/job/chef/ChefHungerHandler.java:41-44 `// 矿洞羹生效时，耐饥改由 Player.causeFoodExhaustion 的真实疲劳缩减统一结算；这里不再额外回补饱和。` / `if (OreSoupEffects.activeInMining(player) && !satiation) { return; }`；src/main/java/com/miningdim/job/fisher/soup/OreSoupEffects.java:128-130 `activeInMining` 只判维度与是否有任意汤态；同文件 :147-155 `int soupReduction = type == OreFishType.IRON || type == OreFishType.DARK_GOLD ? 250 : 0; … return Math.max(soupReduction, chefReduction);`。
- 建议改法: 把 docs/Ore_Fish_And_Soup.md 第 49 行改为：「矿洞内任意一种鱼羹生效时，厨师 `ENDURANCE` 一律改走 `Player.causeFoodExhaustion` 的疲劳缩减结算（不再周期回补饱和）；其中铁/暗金自带的 25% 与厨师耐饥取较强者，避免双重结算。」

**[Major] [E-状态过期] 验证记录仍称未合入 main 且被 12 张未登记贴图阻挡，两条都已作废**

- 位置: 第 73-74 行
- 文档原文: `verifyModuleBoundaries` 中此次渔业新增内容通过，整体仍被 main 既有 12 张未登记材料贴图阻挡（如 `aluminum_ingot.png`、`raw_tungsten.png`）；未跨模块改动材料登记，也未合入 main。 - 源码保存在功能分支 `codex/ore-fish-soup`，没有覆盖根目录已有未提交工作，也没有部署到玩家客户端或服务端。
- 代码实际: 渔夫模块已经合入 main：77230f5e(2026-09-06)、a1e4dfba/da7edd8f/16b5160c/eddc3a6a(2026-09-19) 全部是当前 main(bd0c4588) 的祖先。文中列为阻挡项的材料贴图也已经登记到模块注册表与资源归属表，不再阻挡 verifyModuleBoundaries。按这段文字判断「渔夫还在分支上没合」会直接做出错误决策。
- 取证: git merge-base --is-ancestor eddc3a6a HEAD 返回真，git branch --contains 77230f5e 含 main；docs/modules/module-registry.json:300 `"aluminum_ingot",`、:311 `"raw_tungsten",` 已列入电力模块 resourceNamePrefixes；docs/modules/RESOURCE_OWNERSHIP.md:21 同样登记了 `aluminum_ingot`、`raw_tungsten`。
- 建议改法: 将 docs/Ore_Fish_And_Soup.md 第73-74行改写为反映合入后的真实状态,例如: "`verifyModuleRegistry`、`verifyModuleBoundaries` 均已通过；此前阻挡的 12 张材料贴图（如 `aluminum_ingot.png`、`raw_tungsten.png`）已由电力模块登记进 `docs/modules/module-registry.json`（2026-09-08，提交 a43349424）与 `docs/modules/RESOURCE_OWNERSHIP.md`，该阻塞已解除；渔夫模块已于 2026-09-19 通过 PR#64（合并提交 97975e3e）合入 main，历经 77230f5e（2026-09-06 初步制作）与 a1e4dfba/da7edd8f/16b5160c/eddc3a6a（2026-09-19 四轮自审修复）。" - 源码已在 main 分支，不再是"保存在功能分支 codex/ore-fish-soup"；但"没有部署到玩家客户端或服务端"这一事实仍然有效，应保留。

### `docs/Fishing_Journal_Framework.md`

**[Minor] [D-覆盖缺口] 只写了图鉴的创造分类，五鱼五羹进「食物与饮品」没写**

- 位置: 第 13 行
- 文档原文: 创造模式在原版“工具与实用物品”分类查找“渔业图鉴”。
- 代码实际: 同一个 BuildCreativeModeTabContentsEvent 监听里还把五种矿石鱼与五种鱼羹全部塞进了原版 FOOD_AND_DRINKS（食物与饮品）分类。两份渔夫文档都没写这十个物品在创造模式里去哪找，只写了图鉴那一条。
- 取证: src/main/java/com/miningdim/job/fisher/FishingSystem.java:63-71 `if (event.getTabKey().equals(CreativeModeTabs.TOOLS_AND_UTILITIES)) { event.accept(JOURNAL); }` 紧跟 `if (event.getTabKey().equals(CreativeModeTabs.FOOD_AND_DRINKS)) { OreFishingItems.FISH.values().forEach(event::accept); OreFishingItems.SOUPS.values().forEach(event::accept); }`。
- 建议改法: 在 Fishing_Journal_Framework.md L13 后或 Ore_Fish_And_Soup.md「获取与出售」节补一句：「创造模式下五种矿石鱼与五种鱼羹在原版「食物与饮品」分类，渔业图鉴在「工具与实用物品」分类。」

### `docs/Ore_Fish_And_Soup.md`

**[Minor] [D-覆盖缺口] 「鱼种档次」只有口头描述，实际的 Rarity 分级没有任何文档**

- 位置: 第 3 行
- 文档原文: 矿石鱼种类的档次与厨师料理品质章相互独立；暗金矿鱼是当前最高鱼种档次。
- 代码实际: 代码里「档次」是具体落到原版 Rarity 上的：IRON=COMMON、GOLD=UNCOMMON、DIAMOND=RARE、EMERALD=RARE、DARK_GOLD=EPIC，并且鱼与对应鱼羹共用同一个 Rarity（直接决定物品名在游戏里的显示颜色）。两份文档都没写这张映射表。值得一并拍板的是：绿宝石鱼比钻石鱼更稀有（权重 100 vs 200）也更贵（600 vs 400），却与钻石鱼同为 RARE，文档里的「档次」序列与代码里的 Rarity 序列并不同构。
- 取证: src/main/java/com/miningdim/job/fisher/ore/OreFishType.java:7-11 `IRON("iron", Rarity.COMMON), GOLD("gold", Rarity.UNCOMMON), DIAMOND("diamond", Rarity.RARE), EMERALD("emerald", Rarity.RARE), DARK_GOLD("dark_gold", Rarity.EPIC);`；src/main/java/com/miningdim/job/fisher/ore/OreFishingItems.java:26-30 鱼与羹都 `.rarity(type.rarity())`。
- 建议改法: 在 docs/Ore_Fish_And_Soup.md 第13-19行的表格里加一列「Rarity」，按 OreFishType.java:7-11 填 COMMON/UNCOMMON/RARE/RARE/EPIC，并注明鱼与鱼羹(OreFishingItems.java:27,29)共用同一 Rarity、直接决定游戏内物品名显示颜色；同时在文中明确标注绿宝石矿鱼(权重100/600信用点)比钻石矿鱼(权重200/400信用点)更稀有更贵却仍同为 RARE 这一设计取舍是否刻意，若非刻意则把 EMERALD 提至独立档级并同步改表，若是刻意则加一句说明避免读者误以为档次序列与 Rarity 序列线性同构。

**[Minor] [D-覆盖缺口] ore_fish 物品标签随模块交付但零文档且全库零引用**

- 位置: 第 31 行
- 文档原文: 持有即加入图鉴，交易取得也算，卖出后保留收录。图鉴含 4 种原版鱼与 5 种矿石鱼；加载 Tide 1.6.5 时再加入其 66 种鱼，总计 75 种。
- 代码实际: 模块还交付了一个物品标签 `miningdim:ore_fish`，把五种矿石鱼收在一起，并且已被登记进模块注册表的 resourcePaths。但两份渔夫文档都没有提到它，而且全库检索不到任何引用：没有 Java 侧 TagKey/ItemTags 常量、没有配方用 `"tag": "miningdim:ore_fish"`、也没有别的标签包含它。它现在是一份既没有文档解释用途、运行期也没人读的资源。
- 取证: src/main/resources/data/miningdim/tags/items/ore_fish.json:1-10 `{"replace": false, "values": ["miningdim:iron_ore_fish", … "miningdim:dark_gold_ore_fish"]}`；docs/modules/module-registry.json:511-512 resourcePaths 含 `"data/miningdim/tags/items/ore_fish.json"`；全库 grep `#miningdim:ore_fish` 与 Java 侧 TagKey/ItemTags 引用均为零命中（src/main/java 中 ore_fish 只出现在 GameTest 的 batch 名与 OreFishType.fishId() 拼接里）。
- 建议改法: 在渔夫主设计文档（或 Ore_Fish_And_Soup.md「获取与出售」节，紧邻第31行图鉴段落）补一句该标签的定位，例如「`miningdim:ore_fish` 为下游配方/任务预留的矿石鱼聚合标签，当前无消费方」；或者确认无人要用就连同 module-registry.json 第512行的登记一并删除，不要留一份无主资源。

**[Minor] [A-文档与代码不符] 「三条契约」与三个实际用例对不上，模型绑定与 256 上限两条没写**

- 位置: 第 58 行
- 文档原文: `FishingAssetGameTests` 立三条契约，判据全部取原版算法本身而非脚本参数：帧尺寸整除规则、mipmap 可整除次数、以及 `ItemModelGenerator` 沿 Alpha 轮廓烘出的 element 数上限（当前十张最大 134，十张合计 949，红线 200；…）
- 代码实际: 数值全部核对无误（机械复算十张 PNG：最大 134、合计 949、全部 64x64）。但契约与用例的对应关系错了：文中列的「帧尺寸整除 + mipmap 次数」是同一个用例 everyFishingIconSurvivesVanillaAtlasStitching 里的两条断言，element 上限是第二个用例；第三个用例 everyFishingItemModelBindsItsOwnIcon（断言物品模型继承 item/generated 且 layer0 指回同名图标）文档完全没提。同一个用例里还有两条没写进文档的硬约束：边长不得超过 MAX_ICON_EDGE=256、必须带 Alpha 通道且至少有一个全透明像素。
- 取证: src/main/java/com/miningdim/job/fisher/FishingAssetGameTests.java:76-77 `public static void everyFishingIconSurvivesVanillaAtlasStitching` 内含规则 1（帧尺寸整除）与规则 2（mipmap halvings）两条断言，:98-99 还有 `helper.assertTrue(width <= MAX_ICON_EDGE && height <= MAX_ICON_EDGE, …)`；:110-111 `everyFishingIconStaysCheapForItemModelGeneration`；:179-180 `public static void everyFishingItemModelBindsItsOwnIcon` 断言 `"minecraft:item/generated".equals(model.get("parent")…)` 与 layer0 指向 `miningdim:item/fishing/<icon>`；:52 `private static final int MAX_ICON_EDGE = 256;`。
- 建议改法: 将 docs/Ore_Fish_And_Soup.md 第 57-58 行改为分用例枚举，如：「`FishingAssetGameTests` 立三个用例，判据全部取原版算法本身而非脚本参数：(1) `everyFishingIconSurvivesVanillaAtlasStitching`——帧尺寸整除规则、mipmap 可整除次数、边长不超过 256、必须带 Alpha 通道且至少一个全透明像素；(2) `everyFishingIconStaysCheapForItemModelGeneration`——`ItemModelGenerator` 沿 Alpha 轮廓烘出的 element 数上限（当前十张最大 134，十张合计 949，红线 200；本模块最初提交的原画分辨率下这一项是单张 3473、十张 17729）；(3) `everyFishingItemModelBindsItsOwnIcon`——物品模型继承 `item/generated` 且 layer0 指回同名图标 `miningdim:item/fishing/<icon>`。」

### `tools/assets/fishing/v1/README.md`

**[Minor] [D-覆盖缺口] 声称留档了选图与提示词，铁矿鱼的提示词一份都没有**

- 位置: 第 15 行
- 文档原文: 铁鱼、金鱼、绿宝石鱼采用已选 v1，钻石鱼采用真实透明的 v3；暗金鱼源样选用户确认的虹彩 v5，保存为 `dark_gold_selected_v5.png`。
- 代码实际: 目录里确实留了 gold_prompt_v1.txt、emerald_prompt_v1.txt、diamond_prompt_v1/v2/v3.txt 和五份 dark_gold_*_prompt_v1..v5.txt，README 正文另存了最终暗金鱼与五种鱼羹的提示词。唯独铁矿鱼（被明确写成「采用已选 v1」的那一条）既没有 iron_prompt_v1.txt，README 正文里也没有对应段落——README 里的「基础铁羹提示词」是鱼羹不是鱼。docs/Ore_Fish_And_Soup.md:56 还把这份 README 当成「选图和内置 image_gen 提示词记录」的唯一去处，实际做不到。
- 取证: tools/assets/fishing/v1/ 目录实际内容：README.md、dark_gold_iridescent_prompt_v5.txt、dark_gold_mystic_prompt_v4.txt、dark_gold_prompt_v1.txt、dark_gold_purple_prompt_v2.txt、dark_gold_purple_prompt_v3.txt、dark_gold_selected_v5.png、diamond_prompt_v1.txt、diamond_prompt_v2.txt、diamond_prompt_v3.txt、emerald_prompt_v1.txt、gold_prompt_v1.txt、source/ —— 无任何 iron 前缀的提示词文件；tools/assets/fishing/v1/README.md:19 的「基础铁羹提示词」文本为 `iron ore fish soup … a single small chunky wooden bowl`，是鱼羹。
- 建议改法: 补一份 tools/assets/fishing/v1/iron_prompt_v1.txt（或在 README 里补一节"铁矿鱼提示词"）；若原始提示词确已找不回，就在 README 第15行附近明确写出"铁矿鱼 v1 的原始生成提示词未留档"，避免 docs/Ore_Fish_And_Soup.md:56 那句"选图和内置 image_gen 提示词记录见……"被读作十张图标提示词已全部归档。

---

## 电力

### `docs/Power_Cable_DesignSpec.md`

**[Critical] [C-实现状态标错] 线缆文档仍写"仅注册铁铜、禁止预注册 T4-T12",代码已全注册 13 种且姊妹文档已自认过时**

- 位置: 第 231（同源问题见 31、42） 行
- 文档原文: L231：“当前仅注册铁、铜；P1 注册 T1-T3，后续按分期开放，禁止预注册未落地的 T4-T12。” L42：“方块注册按分期逐级开放，当前仅铁、铜。” L31：“当前实际注册的线缆只有铁、铜两种”
- 代码实际: PowerRegistry 已把 12 档 ConductorMaterial 全部注册为方块+BlockItem+导线中间物，另加阶梯外的钨耐热线，共 13 种摆放态线缆，全部贴图、multipart 模型、配方、loot 均已落地。
- 取证: src/main/java/com/miningdim/power/PowerRegistry.java:117-129 `public static final List<ConductorMaterial> REGISTERED_MATERIALS = List.of(IRON, ALUMINUM, COPPER, TINNED_COPPER, OFC_COPPER, OFE_COPPER, SILVER_PLATED_COPPER, GOLD, SILVER, GRAPHENE, NBTI_SUPERCONDUCTOR, YBCO_SUPERCONDUCTOR);`；同文件 135-141 `TUNGSTEN_HEAT_RESISTANT_WIRE = BLOCKS.register(SpecialCableMaterial.TUNGSTEN.blockId(), ...)`；src/main/java/com/miningdim/power/cable/EnergyCableGameTests.java:92-93 `ConductorMaterial.values().length == 12`。反证文档同批已更新的说法：docs/Power_Cable_AssetChecklist.md:14 “旧 `cube_all` 与“仅铁、铜已注册”的描述已经失效”。
- 建议改法: L31 "当前实际注册的线缆只有铁、铜两种"改为"当前实际注册的线缆为 12 档 `ConductorMaterial` 全量加钨耐热线,共 13 种摆放态"; L42 "方块注册按分期逐级开放,当前仅铁、铜。"删去"当前仅铁、铜"这半句,或改为"方块已按 `ConductorMaterial` 表全量注册,不再分期开放注册动作,分期含义改指配方/贴图/UI 曝光节奏"; L231 "当前仅注册铁、铜;P1 注册 T1-T3,后续按分期开放,禁止预注册未落地的 T4-T12。"整句替换为"T1-T12 与钨耐热线均已全量注册;新增材料仍须先进 `ConductorMaterial`/`SpecialCableMaterial` 表再注册,禁止绕开该表硬编码方块"。三处改完后建议在文末加一条日期化的勘误脚注,并与 docs/Power_Cable_AssetChecklist.md 第 9、16 行的现状描述对齐,避免同仓两份文档继续互相矛盾。

### `docs/Power_Cable_AssetChecklist.md`

**[Major] [C-实现状态标错] docs/Power_Cable_AssetChecklist.md 第175-183行"跨分期缺失的独立组件资产"9项全标`[ ] 未生成`,实际9张PNG均已存在并完整接入物品/方块模型**

- 位置: 第 175-183 行
- 文档原文: L175-183 表格九行状态列一律 `[ ] 未生成`：item/industrial_fuel_core.png、item/modern_fuel_core.png、item/future_fuel_core.png、item/liquid_nitrogen_canister.png、block/low_temperature_controller_top.png、_side.png、_front.png、_front_on.png、item/low_temperature_controller.png
- 代码实际: 九个文件全部存在于仓库，且都已被模型引用：三档燃料芯与液氮罐、低温控制器物品用 item/generated 绑同名 layer0，低温控制器四张方块面贴图由 orientable 模型引用。
- 取证: src/main/resources/assets/miningdim/textures/item/ 下 industrial_fuel_core.png(272B)、modern_fuel_core.png(265B)、future_fuel_core.png(275B)、liquid_nitrogen_canister.png(295B)、low_temperature_controller.png(352B) 均存在；src/main/resources/assets/miningdim/textures/block/ 下 low_temperature_controller_top/side/front/front_on.png 四张齐全。接线证据：src/generated/resources/assets/miningdim/models/item/industrial_fuel_core.json:1-4 `"layer0": "miningdim:item/industrial_fuel_core"`；src/generated/resources/assets/miningdim/models/block/low_temperature_controller.json:1 `{"parent":"minecraft:block/orientable","textures":{"front":"miningdim:block/low_temperature_controller_front","side":"miningdim:block/low_temperature_controller_side","top":"miningdim:block/low_temperature_controller_top"}}`。
- 建议改法: 将第175-183行九行表格的状态列由 `[ ] 未生成` 统一改为 `[x] 已生成`(与文档第16行自定义的状态记法一致, 因九项不仅文件存在, 且已通过 PowerRegistry/PowerMachineRegistry 注册、经 PowerGeneratorItemModelProvider/PowerMachineItemModelProvider/PowerEndgameBlockStateProvider 接入生成模型, 并有配套 GameTests, 已满足"[x] 已生成"及以上的闭环标准); 第169行小节标题"### 跨分期缺失的独立组件资产"改为"### 独立组件资产(燃料芯 / 液氮罐 / 低温控制器)"以去除"缺失"这一过期表述; 第171行"以下独立组件不能以本轮完成的13张摆放态线缆、其他机器或锭图标代替"一句予以保留, 作为该组资产与线缆摆放态资产不得互相冲抵计数的独立说明, 不受本次状态更正影响。

**[Major] [D-覆盖缺口] 三档燃料芯发电机(工业/现代/未来)的 29 张方块贴图与 36 个部件模型(每档 12 个)既无资产清单逐项登记, 也无自动化资产校验测试覆盖**

- 位置: 第 全文（对照 docs/Power_Preheat_Generator_AssetChecklist.md:5-6） 行
- 文档原文: 两份电力资产清单的覆盖声明分别是：Power_Preheat_Generator_AssetChecklist L5“记录煤炭发电机、地热发电机与三级储电的 20 张方块贴图和 2 张界面底图”；Power_Cable_AssetChecklist L5“线材(导体/线缆)子系统的美术需求与资产状态清单”。Power_Generator_DesignSpec 全文无资产章节。
- 代码实际: 工业/现代/未来三档燃料芯发电机各自有一整套方块贴图目录（共 29 张 PNG）与 12 部件多方块模型，全部不在任何资产清单的逐项验收范围内；它们只在 docs/modules/RESOURCE_OWNERSHIP.md 里以目录粒度登记归属，没有逐张的生成脚本、尺寸与状态口径。
- 取证: src/main/resources/assets/miningdim/textures/block/generator/industrial/ 共 11 张（base_steel.png、control_panel.png、dark_steel.png、edge_steel.png、exhaust_soot.png、frame_black.png、indicator_red.png、panel_steel.png、rivet_steel.png、vent_dark.png、warning_stripe.png），modern/ 与 future/ 各 9 张；模型目录 src/main/resources/assets/miningdim/models/block/generator/{industrial,modern,future}/；注册见 src/main/java/com/miningdim/power/PowerRegistry.java:52-57。归属登记（非逐项验收）见 docs/modules/RESOURCE_OWNERSHIP.md:21 “`models/block/generator/`、`textures/block/generator/`”。
- 建议改法: 保留原建议: 在 Power_Preheat_Generator_AssetChecklist.md 增设"后期三档燃料芯发电机(29 张方块贴图 + 36 个部件模型)"一节, 或新建 docs/Power_Generator_AssetChecklist.md, 按现有两份清单的口径逐项登记文件名、尺寸、生成脚本真源与自动验收范围, 并把 Power_Preheat_Generator_AssetChecklist.md 标题"(前期发电机 + 三级储电)"相应调整为反映其实际范围。此外应参照 PreheatGeneratorGameTests.blockAssetsExistAndMatchModels 与 PowerCableAssetGameTests 的先例, 为 industrial_generator/modern_generator/future_energy_generator 补一个同类的贴图/模型存在性与引用校验 GameTest, 因为当前 GeneratorGameTests.java 只覆盖多方块放置与运行时逻辑, 完全没有资产层校验, 光补文档不补测试仍会让贴图/模型的静默损坏无法被质量门(runGameTestServer)拦截。

### `docs/Power_Cable_DesignSpec.md`

**[Major] [C-实现状态标错] 第八章仍标 IMPLEMENTATION_REQUIRED,25 条线缆配方早已生成**

- 位置: 第 167（配套 181） 行
- 文档原文: L167 标题：“## 八、加工链与配方 (DECIDED 结构 / IMPLEMENTATION_REQUIRED 具体配方)”；L181：“具体配方在实现期从 `ConductorMaterial` 表生成。”（L11 图例：IMPLEMENTATION_REQUIRED = 已定机制尚待落码）
- 代码实际: 配方生成器已落码且产物已入库：12 张导线、12 张线缆、钨耐热线共 25 条合成配方全部存在于 src/generated/resources，产量与 L179 的固定公式逐条吻合（3 材料→6 导线、3 导线+3 绝缘→6 线缆、8 导线+1 锡/银锭→8 镀层导线）。
- 取证: src/main/java/com/miningdim/power/data/PowerCableRecipeProvider.java:30 `itemId(PowerRegistry.CABLE_ITEMS.get(material).get()), 6,`、:76-80 `itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), 8, "miningdim:copper_wire", "8", "miningdim:tin_ingot", "1"`；src/generated/resources/data/miningdim/recipes/iron_wire.json 全文 `{"type":"minecraft:crafting_shapeless",...,"result":{"count":6,"item":"miningdim:iron_wire"}}`；同目录另有 iron_energy_cable.json 等 24 条同族配方。
- 建议改法: 标题改为“## 八、加工链与配方 (DECIDED)”,L181 改为“具体配方由 `PowerCableRecipeProvider` 从 `ConductorMaterial` 表生成,产物见 src/generated/resources/data/miningdim/recipes/。”

**[Major] [A-文档与代码不符] "EnergyCableGameTests 现有 3 个用例"实为 18 个, 且覆盖面被严重窄化**

- 位置: 第 31 行
- 文档原文: L31：“`EnergyCableGameTests` 现有 3 个用例，覆盖并网/拆网、receive-only 共享缓冲和过载升温后冷却回升。本文不保留会过时的全项目 GameTest 总数。”
- 代码实际: 该测试类现有 18 个 @GameTest 用例，覆盖面远不止三项：十二档契约、P3 剖面与钨特殊线、NbTi 64 段冷却边界、距离电阻与温度、六向连接形状、onLoad 修复、拆网按容量比例守恒、合网守恒、末根弃电审计、合网超额只计一次、超额期停拉、过压拒抽、receive-only 共享缓冲、过载升温后冷却等。
- 取证: src/main/java/com/miningdim/power/cable/EnergyCableGameTests.java 内 `@GameTest(` 出现 18 次；方法名示例：:48 `registeredMaterialsPreserveAllTwelveTiersAndExistingIds`、:117 `nbtiCoolingHasSixtyFourSegmentBoundaryAndSecondControllerRestoresIt`、:171 `p3DistanceResistanceAndTemperatureContractsAreExact`、:754 `overvoltageTripsInternalSourceButThirdPartyLowWorks`。
- 建议改法: 按本文自己"不保留会过时的计数"的原则,删除具体数字与封闭式覆盖列举,把 docs/Power_Cable_DesignSpec.md:31 的"`EnergyCableGameTests` 现有 3 个用例，覆盖并网/拆网、receive-only 共享缓冲和过载升温后冷却回升。本文不保留会过时的全项目 GameTest 总数。"改为类似:"`EnergyCableGameTests` 覆盖并网/拆网与容量比例守恒、合网守恒与超额只计一次、末根弃电审计、receive-only 共享缓冲、过载升温与冷却回升、过压拒抽、P3 距离电阻与温度契约、NbTi 64 段冷却边界、十二档材料注册契约等;具体用例数量与清单以该类源码为准,本文不登记会过时的数字。"

**[Major] [D-覆盖缺口] "电力系统分三层、线缆=储能/传输层"的定位表述已过期：储电已独立成第三层(power.storage 包)，且第二章对结算引擎"单轮拉/单轮推"的描述也未反映当前"储电专属两轮防churn"的实现**

- 位置: 第 8 行
- 文档原文: L8：“系统定位: 电力系统分三层 —— 发电(自研, 另立文档)、储能/传输(线缆=本文档)、用电(逐台另行立项)。”（配合 L33 “瞬态缓冲 stored”、L73 注释“线缆不是电池”）
- 代码实际: 储能已从线缆层独立出来成为专门的一层：三档储电方块、松散聚合分组、饱和截断与掉落带电全部落地，线缆只剩一次额定吞吐的瞬态缓冲。线缆文档全文零处提及储电层，读者按 L8 会以为线缆仍承担储能职责。
- 取证: src/main/java/com/miningdim/power/storage/PowerCellSpec.java:18-20 `INDUSTRIAL("industrial", 13_824_000, 768), MODERN("modern", 165_888_000, 4_608), FUTURE("future", 884_736_000, 12_288);`；src/main/java/com/miningdim/power/cable/ConductorMaterial.java:72-75 `/** 瞬态导体缓冲容量 = 一次额定吞吐 (线缆不是电池...) */ public int transientBufferCap() { return ratedCapacityFe; }`；docs/Power_Economy_Rebalance_DesignSpec.md:13 “发电（持续，tick 级） -> 储电（缓冲，小时级） -> 消费（脉冲，批量结算）”。
- 建议改法: 1) 第 8 行改为: "系统定位: 电力系统分四层 —— 发电(自研, 另立文档)、传输(线缆=本文档, 只有一次额定吞吐的瞬态缓冲, 不持久存电)、储电(`com.miningdim.power.storage.PowerCell*`, 独立包, 见 docs/Power_Economy_Rebalance_DesignSpec.md 第三章/七之二实现状态)、用电(逐台另行立项)。"并在文档元信息处补一条到 Power_Economy_Rebalance_DesignSpec.md 的双向链接, 消除当前"新文档单向引用旧文档、旧文档对新文档零感知"的断链。 2) 第 34 行结算描述需同步更新, 补充"储电"这一双向端点(canExtract 且 canReceive)的存在及其分轮规则, 与 EnergyNetworkManager.java:43-45 的注释对齐, 例如追加: "储电类端点(既可拉又可推)不与纯生产端/纯消费端同轮结算, 拉、推各自额外单开一轮且排在后面, 防止同一 settlement 内来回 churn(取出又充回); 完整落地状态见 docs/Power_Economy_Rebalance_DesignSpec.md 七之二。" 避免读者仅凭本章误以为结算仍是单轮拉单轮推。

**[Major] [A-文档与代码不符] T11/T12 绝缘列写"特殊"，实为具体的 SILICONE(180°C)，且该值决定两档线缆的真实合成配方原料(与网络降效计算无关)**

- 位置: 第 82-83 行
- 文档原文: L82：“| 11 | 超导 NbTi | 低温超导, 铜基 | 16384 | 1.00 | EXTREME | — | 特殊 | 合成·低温控制器 | P3；控制器覆盖时超导 |” L83 同列亦为“特殊”
- 代码实际: 两档在代码里都绑定了具体的 InsulationGrade.SILICONE（持续耐温 180°C），不是未定义的“特殊”档。该值会经混级网木桶取最小值参与 insulationMaxTempC 计算（例如 NbTi 与钨耐热线 300°C 混接时，全网降效起始点被 NbTi 的 180 压低），按文档的“特殊”字样无法推出这一行为。
- 取证: src/main/java/com/miningdim/power/cable/ConductorMaterial.java:33-34 `NBTI_SUPERCONDUCTOR( "nbti_superconductor",   16384, 1.00, InsulationGrade.SILICONE, VoltageClass.EXTREME, ThermalMode.NBTI, 168, false), YBCO_SUPERCONDUCTOR( "ybco_superconductor",   32768, 1.00, InsulationGrade.SILICONE, ...)`；src/main/java/com/miningdim/power/cable/InsulationGrade.java:91 `SILICONE("silicone", 180);`。
- 建议改法: 将文档第82-83行"绝缘(耐温)"列的"特殊"改为"硅橡胶(180°C)"，与 T9/T10 表述一致。在第四章 T11/T12 说明处补一句：该绝缘档只决定建造配方原料——T11/T12 线缆合成均需 3 个 miningdim:insulation_silicone（见 src/generated/resources/data/miningdim/recipes/nbti_superconductor_energy_cable.json、ybco_superconductor_energy_cable.json，来源逻辑在 PowerCableRecipeProvider.java 第109-116行的 insulationId()）；不要采用原发现建议的"绝缘档仍参与混级网木桶降效起始点取值"这一说法，因为 EnergyNetwork.recomputeProfile()（EnergyNetwork.java 第146-160行）只把 thermalMode()==STANDARD 的导体纳入 insulationMaxTempC/degradeFloor 木桶，NBTI/YBCO 的 thermalMode 分别为 NBTI/YBCO（ConductorMaterial.java 第33-34行、CableProfile.java 第38-43行），不会进入该木桶；T11 的降容改由控制器覆盖段数机制单独判定（EnergyNetwork.java 第176-186行），T12 按文档"热效率恒定"不受影响。

**[Major] [B-文档互相打架] 线缆文档三处声明"不接入 Flux Networks"，与经济重标定文档及仓库已落地的 Flux 接入冲突**

- 位置: 第 8、18、240 行
- 文档原文: L8：“本文档只覆盖"有线线材"这一层, 不接入 Flux Networks 无线传输。” L18：“本系统不实现或依赖 Flux Networks 无线传输。” L240：“不接入 Flux Networks；本线缆系统的范围止于有线 FE 传输。”
- 代码实际: docs/Power_Economy_Rebalance_DesignSpec.md:129 已拍板“路线已定：引入 Flux Networks 作为终局无线储能，接受实体线缆在终局退役”，且仓库内已落地 Flux 配方覆盖与互操作契约测试（后者就放在线缆网络自己的 grid 包里）。两份文档对同一子系统给出相反结论，且线缆文档没有任何指向重标定文档的交叉引用。
- 取证: src/main/resources/data/fluxnetworks/recipes/fluxcore.json（带 `forge:mod_loaded` 条件，把 flux_core 覆盖成 4 石墨烯片+4 YBCO 带+1 下界之星）；src/main/java/com/miningdim/power/grid/FluxInteropGameTests.java:12-23 类注释“与 Flux Networks (通量网络) 的 Forge Energy 协议级互操作……本仓库对 Flux 只有一份配方覆盖, 零代码集成”；docs/Power_Economy_Rebalance_DesignSpec.md:129 与 137 “该互操作边界已由 `FluxInteropGameTests` 用与 Flux 逐位一致的 capability 形状钉住”。
- 建议改法: 把 L8/L18/L240 的绝对否定改成范围声明，例如 L240 改为"本线缆系统自身不实现无线传输机制；与 Flux Networks 的互操作边界(FluxInteropGameTests/FluxOverrideGameTests)与终局定位见 docs/Power_Economy_Rebalance_DesignSpec.md 第 3.3 节，本文不重复定义。"并在文档元信息处补一条指向 Power_Economy_Rebalance_DesignSpec.md 的交叉引用；同时在第二章"传输网络引擎"处补一句提及 com.miningdim.power.grid 包内已有 FluxInteropGameTests 这层与 Flux capability 形状的兼容性契约，避免未来重构该包时在不知情的情况下破坏该契约。

### `docs/Power_Generator_DesignSpec.md`

**[Major] [B-文档互相打架] 发电机文档自称唯一真源且禁止第四档，实际已有煤炭/地热两台且被另一文档声明改写**

- 位置: 第 5、19、264 行
- 文档原文: L5：“用途：发电子系统实现、测试、存档兼容与平衡标定的唯一真源。实现不得以口头约定、临时常量或其他文档覆盖本文。” L19：“发电机只有 LOW、MEDIUM、HIGH 三个规格档”。L264（第十章未授权扩展）：“第四种发电机规格、超出第二章的峰值输出或缓冲容量。”
- 代码实际: 代码已注册五台发电机：三档燃料芯机加煤炭机（48 FE/t）与地热机（144 FE/t），后两台由 PreheatGeneratorSpec 独立建模。docs/Power_Economy_Rebalance_DesignSpec.md:4 明确“本文**修订** Power_Generator_DesignSpec.md 第二章数值表与第五章保护条款……凡与旧文冲突之处以本文为准”，L42-48 给出五台总表。而发电机文档全文零处提及煤炭机、地热机或该重标定文档（grep 无命中）。
- 取证: src/main/java/com/miningdim/power/generator/PreheatGeneratorSpec.java:18-19 `COAL("coal", FuelSource.BURNABLE_ITEM, 48, 300.0D, 1_200, 0.15D, 4), GEOTHERMAL("geothermal", FuelSource.LAVA_SOURCE_BELOW, 144, 600.0D, 2_400, 0.20D, 1);`；src/main/java/com/miningdim/power/PowerRegistry.java:59-62 `registerPreheatGenerator("coal_generator", PreheatGeneratorSpec.COAL)` 与 `registerPreheatGenerator("geothermal_generator", PreheatGeneratorSpec.GEOTHERMAL)`。
- 建议改法: L5：将"发电子系统实现、测试、存档兼容与平衡标定的唯一真源。实现不得以口头约定、临时常量或其他文档覆盖本文"改为"燃料芯发电机（工业/现代/未来）实现、测试、存档兼容与平衡标定的唯一真源；前期预热式发电机（煤炭/地热）见 docs/Power_Economy_Rebalance_DesignSpec.md 第二章。实现不得以口头约定或临时常量覆盖本文；本文明确交叉引用的重标定文档对本文数值表/保护条款的修订除外"。 L19：在"发电机只有 LOW、MEDIUM、HIGH 三个规格档"前加限定词，改为"燃料芯发电机（GeneratorSpec）只有 LOW、MEDIUM、HIGH 三个规格档"。 L264：将"第四种发电机规格"改为"GeneratorSpec 第四种规格档"，避免被解读为禁止一切新增发电机类型（预热式发电机已通过独立的 PreheatGeneratorSpec/PreheatGeneratorBlock 实现，未违反本条字面约束，但需要文字上排除歧义）。 补充：第二章现有数值表（燃料芯耐久 3,600/7,200/14,400 等）本身已与重标定文档及代码同步，无需改动数值，只需在章节末尾加一条交叉引用（指向 Power_Economy_Rebalance_DesignSpec.md）说明预热式发电机的存在与其对第五章保护条款的补充语义（前期两台不熔毁）。

### `docs/Power_Cable_AssetChecklist.md`

**[Minor] [F-结构问题] 关联真源指向"设计文档第十三章",分期路线实际在第十二章**

- 位置: 第 6 行
- 文档原文: L6：“关联真源: 导体阶梯与材料 id 见设计文档第四章 12 级导体表及 `ConductorMaterial` 枚举; 绝缘 5 档见 `InsulationGrade` 枚举; 分期路线见设计文档第十三章。”
- 代码实际: Power_Cable_DesignSpec 的第十二章才是“分期落地路线”，第十三章是“最终集成模块：JEI 与 Jade”。照指引翻到第十三章拿不到 P1/P1.5/P2/P3 路线表。
- 取证: docs/Power_Cable_DesignSpec.md:244 “## 十二、分期落地路线 (DECIDED)”；同文件:258 “## 十三、最终集成模块：JEI 与 Jade (DECIDED, 可选, compileOnly)”。代码侧佐证第四章/InsulationGrade 两条引用无误：src/main/java/com/miningdim/power/cable/InsulationGrade.java:87-91 `PVC("pvc", 70) ... SILICONE("silicone", 180)`。
- 建议改法: 把 docs/Power_Cable_AssetChecklist.md:6 中"分期路线见设计文档第十三章"改为"分期路线见设计文档第十二章"(该章标题为"## 十二、分期落地路线 (DECIDED)",第十三章实为"最终集成模块：JEI 与 Jade")。

**[Minor] [C-实现状态标错] 割胶刀与生胶乳的说明列仍写"仍需对账/不代表已注册", 但两者均已注册且各自都有配方**

- 位置: 第 81-82 行
- 文档原文: L81：“| ITEM | item/rubber_tapping_knife.png | 割胶刀 | [x] 已生成 | 文件存在; 独立物品注册与配方仍需代码侧对账 |” L82：“| ITEM | item/latex.png | 生胶乳 | [x] 已生成 | 文件存在; 不代表物品已注册 |”
- 代码实际: 两者均已在 PowerRubberRegistry 注册（割胶刀带 128 耐久），割胶刀有合成配方，生胶乳有熔炼成橡胶的配方，割胶与 24,000 tick 冷却也已落码。
- 取证: src/main/java/com/miningdim/power/rubber/PowerRubberRegistry.java:42 `public static final RegistryObject<Item> LATEX = ITEMS.register("latex", () -> new Item(new Item.Properties()));`、:54-55 `RUBBER_TAPPING_KNIFE = ITEMS.register("rubber_tapping_knife", () -> new Item(new Item.Properties().durability(128)));`；配方 src/generated/resources/data/miningdim/recipes/rubber_tapping_knife.json 与 rubber_from_latex_smelting.json；src/main/java/com/miningdim/power/rubber/RubberLogBlockEntity.java:14 `public static final long TAP_COOLDOWN_TICKS = 24_000L;`。
- 建议改法: 将 Power_Cable_AssetChecklist.md L81 说明列由"文件存在; 独立物品注册与配方仍需代码侧对账"改为"文件存在; 物品已在 PowerRubberRegistry 注册(耐久128)并有合成配方(见 PowerRubberRecipeProvider.tappingKnife/rubber_tapping_knife.json)"; L82 由"文件存在; 不代表物品已注册"改为"文件存在; 物品已注册, 并有熔炼为橡胶的配方(rubber_from_latex_smelting.json)"。

**[Minor] [F-结构问题] 七矿表 7 处 worldgen 勾选被 Markdown 解析成链接**

- 位置: 第 96-102 行
- 文档原文: L96：“原矿/锭 [x] · 导线图标 [x] · 矿石方块 [x] · worldgen [x](easy/medium/hard)”；L97-102 同形共 7 处（medium/hard、hard 等）
- 代码实际: `[x](easy/medium/hard)` 是合法的 Markdown 行内链接语法，渲染后勾选框消失、只剩一个指向相对路径 easy/medium/hard 的死链，表格“worldgen 已交付且覆盖哪些难度”的信息在渲染视图里丢失。七矿的难度覆盖本身与数据一致，只是写法被吃掉。
- 取证: 数据侧佐证这些勾选内容属实，因此更值得修写法：src/generated/resources/data/miningdim/worldgen/placed_feature/ 下 ore_bauxite_{easy,medium,hard}.json、ore_borax_{medium,hard}.json、ore_silver_{medium,hard}.json、ore_tin_{medium,hard}.json、ore_{nickel,chromium,tungsten}_hard.json 齐全，且 count 与 docs/Power_Cable_DesignSpec.md:193-201 表格逐格一致（如 ore_bauxite_easy.json `{"type":"minecraft:count","count":10}`）。
- 建议改法: 把 docs/Power_Cable_AssetChecklist.md 第96-102行中 `worldgen [x](easy/medium/hard)` 等7处的圆括号改成非链接写法, 例如 `worldgen [x] easy/medium/hard`(在 `]` 与 `(` 之间加空格或去掉括号)或 `worldgen [x]（easy/medium/hard）`(改用全角括号), 7行统一处理, 避免被 CommonMark/GFM 行内链接语法 `[text](dest)` 吞掉可见文字并生成指向不存在路径的死链。

**[Minor] [F-结构问题] 勾选框写成 [x](easy/medium/hard) 被解析成 Markdown 链接**

- 位置: 第 96-102 行
- 文档原文: | 铝土 | ... | 原矿/锭 [x] · 导线图标 [x] · 矿石方块 [x] · worldgen [x](easy/medium/hard) |(L96); 同形写法见 L97 `worldgen [x](medium/hard)`、L98、L99、L100、L101、L102 共 7 处
- 代码实际: `[x](...)` 是 Markdown 的内联链接语法, 渲染后这 7 处不会显示成"勾选 + 难度范围", 而会变成一个链接文字 x、指向相对路径 easy/medium/hard 的死链, 原本想表达的"在哪几个难度生成"这条信息在渲染视图里直接丢失。同文件其他位置的 `[x] 已生成, ...`(L106-L110)因为后面跟的是空格不是左括号, 渲染正常, 说明是这 7 处的写法问题而非全文体例。
- 取证: 代码侧可佐证这确实是"难度范围"而非链接: worldgen 难度维度在代码里是 Difficulty 三档, src/main/java/com/miningdim/entry/MiningCommands.java:51-53 用 `java.util.Arrays.stream(Difficulty.values()).map(Difficulty::configName)` 做补全, configName 即 easy/medium/hard, 仓库内不存在名为 easy/medium/hard 的文件或目录可供链接指向。
- 建议改法: 把这7处(docs/Power_Cable_AssetChecklist.md 第96-102行, `worldgen [x](easy/medium/hard)` 及其5个同类变体 `[x](medium/hard)`/`[x](hard)`)改成不会被解析成内联链接的写法, 例如在 `]` 与 `(` 之间加一个空格 `worldgen [x] (easy/medium/hard)`, 或直接去掉括号写成 `worldgen [x] easy/medium/hard`。

**[Minor] [B-文档互相打架] 硼砂分期两份文档不一致：设计文档写 P1.5，资产清单写 P2 前置**

- 位置: 第 97（对照 Power_Cable_DesignSpec.md:196、本文件 119） 行
- 文档原文: 资产清单 L97：“| 硼砂 | `borax_ore` -> `borax` | P2 前置 | ...”；同文件 L119：“原 P1.5 内容作为 P2 的前置设施与高纯材料资产, 不再另设独立交付门槛。”
- 代码实际: 设计文档第九章七矿总表仍把硼砂的“配方开放”标为 P1.5，第十二章分期路线也保留 P1.5 作为独立阶段（含提纯机与空分装置的独立验收门槛），与资产清单“不再另设独立交付门槛”的口径相反。两边对同一材料给出不同的分期标签。
- 取证: docs/Power_Cable_DesignSpec.md:196 “| 硼砂 | 提纯灌注料(铜锭→脱氧铜→OFC) | — | 4 | 3 | 5 | P1.5 |”；同文件:249 “| P1.5 | 提纯机；铜锭 + 硼砂 → 脱氧铜 → 再次硼砂 → OFC → 氩气 → OFE；独立空分装置 | GameTest 覆盖完整提纯链 |”。代码侧硼砂矿与提纯链均已落地，分期标签已无实际约束力：src/main/java/com/miningdim/power/machine/PurifyingProfile.java:8-12 五档工序全在，src/generated/resources/data/miningdim/worldgen/placed_feature/ore_borax_{medium,hard}.json 已生成。
- 建议改法: 二选一统一口径：要么把 docs/Power_Cable_DesignSpec.md:196 的"P1.5"与:249 的 P1.5 行合并进 P2(与资产清单一致), 要么把 docs/Power_Cable_AssetChecklist.md:97 改回"P1.5"并删去:119/:206 的"不再另设独立交付门槛/不再单独计为完成阶段"。鉴于提纯链(PurifyingProfile 五档工序、PowerMachineGameTests 全覆盖)与硼砂 worldgen(ore_borax_medium/hard)均已交付, 建议统一为"P2 前置(原 P1.5)", 并在 AssetChecklist.md 第 7 行的范围声明处补一句提醒: 分期标签如与 DesignSpec 冲突以 DesignSpec 最新版为准, 避免同类措辞歧义再次出现。

---

## WebUI

### `docs/WebUI_Frontend_Wiring_Checklist.md`

**[Critical] [C-实现状态标错] 清单第 282 行宣称第三章接线总表"仍是真源"，但代码侧核实后 A/C/D/E/F/G 组绝大多数 WRAP/BACKEND/NONE/BLOCKED 条目均已注册为可用 action（含 A15/A17/A3/A5/A9 等），仅极少数如 A16 仍真实未解决**

- 位置: 第 282 (配合 119 / 131 / 133) 行
- 文档原文: L282: "第三章接线总表与第七章决策项**不受影响，仍是真源**。"；L131: "| A15 | 前端加载入口 | BLOCKED | `WebUiClient:95` 硬编码 `data:` URI 开发页 | **无 devServerUrl config、无 openUrl(route)**，前端页面目前根本没有被加载的途径。0 号阻塞项 |"；L133: "| A17 | 平板 hub 面板目录 | NONE | `WebUiClientSubsystem:49-52`（唯一入口是调试命令） | 无平板物品、无键位、无面板注册表 ..."
- 代码实际: A15 早已解阻塞: 客户端配置 webui.url 已存在, WebUiClient 已拆出 openWebUi(); A17 的 hub.panels action 与 G 键位均已落地。第三章表里标 BLOCKED/NONE/BACKEND 的条目绝大多数已注册成真 action (前端 SERVER_ACTIONS 现有 68 条)。同文第五章批 0 清单 L287-L289 已把 A15/A3/A12 勾成 [x], 与第三章自相矛盾。
- 取证: src/main/java/com/miningdim/client/webui/WebUiClient.java:101 "public static void openWebUi() {"；src/main/java/com/miningdim/config/MiningClientConfig.java:116 "webui.url 的唯一读取口径。配置里存的是运维手打的字面量"；src/main/java/com/miningdim/webui/server/HubWebUiActions.java:50 "WebUiServerDispatcher.register(\"hub.panels\", PANELS);"；webui/src/lib/actions.ts:40-109 SERVER_ACTIONS 共 68 条
- 建议改法: 把 L282 从"不受影响，仍是真源"改为"本表为 2026-08-12 盘点快照，此后 W1-W10 等接线批次已大面积推翻其中的 WRAP/BACKEND/NONE/BLOCKED 判定，仅供查缺口成因，当前状态以 webui/src/lib/actions.ts 的 SERVER_ACTIONS 与各模块 *WebUiActions.java 的 registerAll 为准"，并在第三章表头加同样的过期横幅。禁止不加验证地"批量回写"——必须逐行核对：凡该 action 已出现在 SERVER_ACTIONS 且能在对应 *WebUiActions.java 找到 register(...) 调用（如本次验证过的 A15/A17/A3/A5/A9 以及 C/D/E/F/G 组的 job.*/marriage.*/mining.*/champion.*/quest.*/admin.* 等）才可改 READY；对仍能在代码注释里找到"清单 A16 的后端缺口"这类自述未解决的条目（如 A16，因全库零 GameProfileCache 用法）必须保留原状态，不得一并改动。

### `docs/WebUI_Wiring_Execution_Scope.md`

**[Critical] [B-文档互相打架] W11 仍按已被撤销的 GLFW preedit 前提写实现方案(范围裁定表 S2 行与分支 W11 正文两处均未同步)**

- 位置: 第 185-186 行
- 文档原文: L185-186: "裁定 S2。`WebUiScreen` 叠一个不可见原版 `EditBox` 捕获 GLFW IME 组字事件（preedit / commit），已上屏字符经 `WebBrowser.sendKeyTyped` 注入 CEF。接口位已在 `WebUiScreen:24-26,181-189` 留好。"
- 代码实际: docs/WebUI_ChineseIME_DesignSpec.md 第 2.1/2.4 节已用 javap 实测撤销该前提: LWJGL 3.3.1 的 GLFW 绑定零 preedit/composition API, 叠 EditBox 也拿不到组字事件; 该文 L84 明确要求"W11 动工的第一个提交必须是删掉这两处错误注释"。Scope 是执行合同, 照它动工会直接进死胡同。
- 取证: docs/WebUI_ChineseIME_DesignSpec.md:81 "**这句话的前提是错的** (见 2.1: GLFW 不投递 preedit 事件, 叠 EditBox 也拿不到)。"；src/main/java/com/miningdim/client/webui/WebUiScreen.java:302-304 "// step2 中文 IME 接口位: 完整组字 (preedit) 需在此 Screen 叠加一个隐藏 EditBox, 接 GLFW IME 组字/上屏事件, 再把 commit 的字符序列经 sendKeyTyped 注入。" (错误注释至今仍在码里)
- 建议改法: docs/WebUI_Wiring_Execution_Scope.md 需两处同步改, 缺一处都会误导只读某一章的人: 1) 第 17 行(第一章"范围裁定"表 S2 行): 把"`WebUiScreen` 叠隐藏 EditBox 接 GLFW IME 组字事件，市场搜索 / admin 过滤 / 按名找人三处交互按"可输入"设计"改为"技术路线待定(见 WebUI_ChineseIME_DesignSpec.md);原定 EditBox 接 GLFW preedit 方案已证实不可行, W11 状态改为 DEFERRED"。 2) 第 183-189 行(第四章"分支 W11"正文): 整段替换为指针,内容至少包含: "W11 已改由 docs/WebUI_ChineseIME_DesignSpec.md 承载, 状态 DEFERRED；原文'叠 EditBox 捕获 preedit'的前提已被该文 2.1/2.4 节用 javap 实测撤销(LWJGL 3.3.1 的 GLFW 绑定零 preedit/composition API), 不得照此动工；实际方案待该文第三章真机实验协议(E1-E8)跑完后依结果三选一(路线 A/B/C)"。 3) 顺带核实: src/main/java/com/miningdim/client/webui/WebUiScreen.java 第 27-29 行(类头注释)与第 300-304 行(charTyped 内注释)仍保留同一条已撤销的错误说法, 按 ChineseIME 设计文档 2.4 节"第一个提交必须是删掉这两处错误注释"的处置要求, 属于 W11 真正动工时的必做前置步骤, 应在本次文档修复的验收清单里一并注明(不属于本轮文档改动本身, 但应作为遗留项挂在 Scope 文档 W11 段落末尾, 避免再次被遗忘)。

### `docs/TaskSpec_Quest_WebUI_Panel.md`

**[Major] [C-实现状态标错] 状态仍写"待实施", 服务端四条 action 与前端整页均已上线**

- 位置: 第 3 行
- 文档原文: 状态: 待实施 | 分支: `feat/quest-webui-panel` | 预估: 服务端 1 个新类 + 3 处改动, 前端 1 个新页面 + 4 处改动
- 代码实际: QuestWebUiActions 四条 action 全注册、HubWebUiActions 的 PANEL_IDS 已含 quests 并接了 QUEST_DISABLED 锁、WebUiErrorCodes 与 HubLockCodes 双码已加、前端 ROUTE_QUESTS/HUB_PANEL_META/QuestsPage.tsx/App.tsx 分支全部就位, 第六节要求的测试在 QuestWebUiGameTests.java (590 行) 与 WebUiHubStatusGameTests 里都有。
- 取证: src/main/java/com/miningdim/quest/QuestWebUiActions.java:37-40 注册 quest.board/claim/turnIn/refresh; src/main/java/com/miningdim/webui/server/HubWebUiActions.java:38-40 `List.of("home", "market", "shop", "jobs", "mining", "quests", "codex", ...)`; 同文件:70-72 `if (PANEL_QUESTS.equals(panelId) && !QuestServices.active()) { enabled = false; lockCode = HubLockCodes.QUEST_DISABLED; }`; webui/src/lib/panels.ts:46 `quests: { label: '任务', route: ROUTE_QUESTS, iconItemId: 'minecraft:writable_book' }`; webui/src/App.tsx:68 `[ROUTE_QUESTS]: () => <QuestsPage />`
- 建议改法: 第3行改为: "状态: **已交付** (服务端 `quest/QuestWebUiActions` 四条 action + `HubWebUiActions` 放开 quests; 前端 `pages/QuestsPage.tsx` + 路由/面板元数据; 测试 `quest/QuestWebUiGameTests`) | 分支 `feat/quest-webui-panel` 已合入 main"; 同时可一并核实第34-40行"必须先读的一条历史"一节是否仍需保留(该节描述的"剔除 quests"旧注释已在代码中清除, 该节作为历史背景说明可保留, 但需在其后补充一句"本任务已完成, 上述剔除注释已随代码更新移除", 避免读者误以为该历史状态仍是当前状态)。

**[Major] [A-文档与代码不符] "必须先读的一条历史"整段失效, 引的三处行号与注释原文全部不符**

- 位置: 第 34-40 行
- 文档原文: `src/main/java/com/miningdim/webui/server/HubWebUiActions.java:37` 的 `PANEL_IDS` **显式剔除了 `quests`**, 注释写着: > quests 剔除 (前端根本没有这条路由, 任务系统零实现, 发一个点不进去的入口只会制造工单) ... `webui/src/lib/types.ts:468` 与 `webui/src/lib/bridge.mock.ts:1802` 有同样措辞的注释。这三处注释在本任务完成后**全部过期, 必须一并改掉**
- 代码实际: 三处注释早已按本规格要求改掉, 引用的行号也全部漂了: HubWebUiActions 的 PANEL_IDS 在 38-40 行且已含 quests, 说明注释在 33-36 行; types.ts 的 HubPanelId 注释在 508 行 (不是 468); bridge.mock.ts 的注释在 1997 行 (不是 1802)。按文档去"找到这段剔除注释并改掉"的人一个都找不到。
- 取证: src/main/java/com/miningdim/webui/server/HubWebUiActions.java:33-34 "取值逐条对齐前端 {@code router.ts} 的路由常量与 {@code TabletShell} 的一级导航 id。任务面板已经接入 {@code /quests}"; 同文件:38-40 `List.of("home", "market", "shop", "jobs", "mining", "quests", "codex", "marriage", "case", "settings", "admin")`; webui/src/lib/types.ts:508 "quests 已接入真实任务板路由; 精英怪图鉴的稳定 id 是 codex"; webui/src/lib/bridge.mock.ts:1997 "面板域与顺序 = 服务端 HubWebUiActions 的 11 项硬编码表 (任务叫 quests, 精英怪图鉴叫 codex)。"
- 建议改法: 把 34-40 行的小节标题由"### 必须先读的一条历史"改为"### 历史背景 (已处理, 存档)", 正文改成: "接入前 `HubWebUiActions.PANEL_IDS` 曾显式剔除 quests, 前端 `types.ts` 的 `HubPanelId` 与 `bridge.mock.ts` 的 `HUB_PANEL_IDS` 各有一份同措辞注释; 本任务已把三处一并改成接入后的事实 (见 `HubWebUiActions.java:33-40`、`types.ts:508`、`bridge.mock.ts:1997`)。" 并删掉"必须一并改掉"的祈使句。

**[Major] [A-文档与代码不符] QuestRow 契约块漏了实际下发的 itemReward 字段**

- 位置: 第 93-106 行
- 文档原文: `QuestRow`: ```jsonc {   "questId": "daily.mine.iron", ... "creditReward": 2000              // long, QuestRewards.creditFor(definition) } ```
- 代码实际: 服务端 progressRow 在 creditReward 之后还多发一个 itemReward 对象 (tier / materialStacks / bookChance 三个键), 前端 QuestRow 接口也已按此声明。规格里的 QuestRow 契约块少了这一项, 按它对照回执或重写 mock 的人会把 itemReward 当成服务端多发的野字段。
- 取证: src/main/java/com/miningdim/quest/QuestWebUiActions.java:148-149 `row.addProperty("creditReward", QuestRewards.creditFor(definition)); row.add("itemReward", itemRewardRow(definition.source()));`; 同文件:160-166 `reward.addProperty("tier", QuestItemRewards.tier(source).name()); reward.addProperty("materialStacks", QuestItemRewards.GUARANTEED_MATERIAL_STACKS); reward.addProperty("bookChance", QuestItemRewards.bookChance(source));`; webui/src/lib/types.ts:2815 `itemReward: QuestItemReward` 与 2824-2831 的 `QuestItemReward` 接口 (tier: 'IRON' | 'DIAMOND' / materialStacks / bookChance)
- 建议改法: 在 docs/TaskSpec_Quest_WebUI_Panel.md 第104行 `"creditReward": 2000, // long, QuestRewards.creditFor(definition)` 之后、第105行 `}` 之前补一行:\n  "itemReward": { "tier": "IRON", "materialStacks": 1, "bookChance": 0.15 }  // QuestItemRewards.tier/bookChance, 档位+概率, 不发具体掉落表(materialStacks 恒为 QuestItemRewards.GUARANTEED_MATERIAL_STACKS=1)\n并在第108行"turnIn 这一项..."那段后补一句: itemReward 只发档位与概率, 因为物品是领奖那一刻按权重掷的(见 QuestWebUiActions.itemRewardRow 方法上第153-158行的 javadoc 说明, 与 webui/src/lib/types.ts 第2824-2831行 QuestItemReward 接口的注释一致)。

### `docs/WebUI_Architecture_DesignSpec.md`

**[Major] [E-状态过期] 第六章市场落地状态段落的类数/action数/GameTest数三项均已过期(action数被腰斩为6实为13),且该句2026-08-12仍在编辑却未同步刷新**

- 位置: 第 125 行
- 文档原文: L125: "落地状态：`com.miningdim.market`（17 类）+ 6 个 `market.*` action + 252/252 GameTest 全绿（6 项真连 SQLite）。"
- 代码实际: com.miningdim.market 包下现有 18 个 java 文件 (另含 store 子包), MarketActions 注册的 market.* action 共 13 条 (list/place/buy/cancel/mine/history/baseValue/categories/categoryItems/feePreview/p2pCap/pendingPayout/tradable)。252/252 是 2026-06-19 的历史快照。
- 取证: src/main/java/com/miningdim/market/MarketActions.java 内 "WebUiServerDispatcher.register(\"market" 出现 13 次; ls src/main/java/com/miningdim/market/*.java 共 18 个文件
- 建议改法: 把 docs/WebUI_Architecture_DesignSpec.md:125 中易漂移的三个硬编码计数替换为指向真源的活引用，并注明续订历史，例如："落地状态（2026-06-19 v1 首次落地，2026-08-12 等多次续订；以下计数不再写死，以代码为真源）：`com.miningdim.market` 包（当前 18 个源文件，另含 store/ 子包 7 个）+ `market.*` action（当前 13 条：list/place/buy/cancel/mine/history/baseValue/categories/categoryItems/feePreview/p2pCap/pendingPayout/tradable，真源见 `MarketActions.registerAll`）+ GameTest 全绿（真源见 `market` 包下全部 `@GameTest` 方法，条数随迭代持续增长不再固定引用）。" 同时建议顺带核对第 90 行 "现有 243/243 GameTest" 与本处 "252/252" 两个数字互相矛盾的问题，一并刷新或统一说明口径。

**[Major] [E-状态过期] 分发方式 A/B 重选门槛(像素 UI 首步验证 + MCEF 无手感)已双重过期:验证批次已作废,且代码已有详实 MCEF 离屏性能实测数据**

- 位置: 第 259 行
- 文档原文: L259: "- 分发方式是否由 A 切换至 B（服务端内嵌静态 serve）：待像素 UI 首步验证跑通、UI 迭代频率明朗后重选（见 10.3；切换成本为一个 config 值 + 一个静态 serve 类）。"；同因 L211 "选 A 的当前理由：像素 UI 首步验证尚未跑通"
- 代码实际: 像素风已于 2026-08-13 被整体推翻, PixelUI 规格全文标 DEFERRED, 其中"批 1 像素单点验证"已明确作废, 因此"待像素 UI 首步验证跑通"这个触发条件永远不会到来, 该遗留项等同于无限期挂起。
- 取证: docs/PixelUI_DesignSystem_DesignSpec.md:3 "## 全文状态：DEFERRED（2026-08-13 起）"；docs/WebUI_Frontend_Wiring_Checklist.md:12 "| 第六章\"批 1 · 像素单点验证\" | 已作废。该批要验的 `devicePixelRatio` × GUI Scale 像素对齐问题只在像素风下存在 |"
- 建议改法: 同时改两处并各给出依据: 1) docs/WebUI_Architecture_DesignSpec.md:211 与 :259 中的"像素 UI 首步验证尚未跑通/待像素 UI 首步验证跑通"应删除或改写——依据 docs/WebUI_Frontend_Wiring_Checklist.md:12 与 docs/PixelUI_DesignSystem_DesignSpec.md:3,该验证批次(PixelUI 第十一章"批 1·像素单点验证")已随像素风 DEFERRED 一并作废,不会再以原定形式发生。 2) 同两处"对 MCEF 实际表现...均无手感"的现状陈述应一并更新——依据 src/main/java/com/miningdim/client/webui/WebUiScreen.java:104-111(及 src/main/java/com/miningdim/config/MiningClientConfig.java:71-76 的同组注释),代码已落有关于 CEF 离屏渲染 30fps 出帧上限、CPU 栅格合成随像素数线性增长、4K 单帧约 60ms 等具体实测认知,"无手感"表述已不成立。 3) 建议改写为:"选 A 的当前理由：UI 迭代频率仍不明朗，值不值得为 B 承担'更新 UI 须重启 MC 服务端、玩家掉线'的代价，现在没有判断依据（MCEF 离屏渲染性能特征已有实测数据，见 WebUiScreen/MiningClientConfig 相关注释，不再是决策缺口）。" 第 259 行同步改为"待 UI 迭代频率明朗后重选"，去掉"像素 UI 首步验证"前置。 4) 10.3 表格备注可补一句说明当前 A 仍维持的真实理由是迭代频率未定,而非缺乏 MCEF 性能认知,避免读者误判还需要一次像素风验证才能重新评估分发路线。

**[Major] [A-文档与代码不符] 架构称前端不打包进 jar，实际 jar 内已带整页 HTML**

- 位置: 第 26-27 行
- 文档原文: L26: "2. **前端按美术资源走，不打包进 jar = 只走远端 `devServerUrl` 模式**。"；L27: "jar 内只剩 bridge 地基 + 一个指向远端的 config 值。"
- 代码实际: 仓库内已存在 src/main/resources/assets/miningdim/web/case-opening.html (56209 字节), 由客户端以 data: URI 内联加载, 等于第十章表格里已被"否决"的路线 C 在开箱面板上事实成立。
- 取证: src/main/java/com/miningdim/client/webui/WebUiClient.java:38 "private static final String CASE_PAGE_RESOURCE = \"/assets/miningdim/web/case-opening.html\";"；同文件 :106-109 "public static void openCaseScreen() { ... page = resourceDataUri(CASE_PAGE_RESOURCE);"；资源文件实存 56209 字节
- 建议改法: 在第二章第 2 条后补一条例外说明，并在 10.3 路线表里把开箱页标为"已走路线 C 的唯一例外，理由与边界：开箱动画为一次性展示、无需持久 SPA 状态、更新频率低于旗舰跳蚤市场"；同时把第十二章遗留项里"分发方式是否由 A 切换至 B"一条改为"A 为主 + 开箱页走 C（已实现，见 WebUiClient.openCaseScreen）"，避免与代码现状脱节。

**[Major] [D-覆盖缺口] 架构分层漏掉服务端 webui 网关整个包**

- 位置: 第 84, 165 行
- 文档原文: L84: "[market 子系统]  com.miningdim.core.Subsystem.register(modBus, forgeBus)" (服务端进程内只画了 market 一层)；L165: "子系统范式：客户端 `com.miningdim.client.webui` + 服务端 `com.miningdim.market`，各 `Subsystem.register(modBus, forgeBus)`"
- 代码实际: 服务端真正的 WebUI 网关是 com.miningdim.webui.server 包 (WebUiServerSubsystem / WebUiServerDispatcher / WebUiRateLimiter / WebUiPermissions / WebUiErrorCodes / WebUiBusinessException / WebUiBatchAction / HubWebUiActions), 全部 68 条 action 都经它派发与限流, 架构文档只字未提; 其中 WebUiRateLimiter 属安全机制, 第八章"安全红线"里也没有对应条目。
- 取证: src/main/java/com/miningdim/MiningDim.java:170 "subsystems.add(new com.miningdim.webui.server.WebUiServerSubsystem());"；src/main/java/com/miningdim/webui/server/WebUiServerDispatcher.java:225 "public static List<String> registeredActions() {"；src/main/java/com/miningdim/webui/server/WebUiRateLimiter.java:8 "每玩家令牌桶限流器 (F008)。"
- 建议改法: 在第四章(70-93行)分层图的"服务端进程"一侧,于 `[market 子系统]` 方框之前补一层独立方框,例如: "[webui.server 网关]  com.miningdim.core.Subsystem.register(modBus, forgeBus) —— WebUiServerDispatcher 派发表(69 条 action)/ WebUiRateLimiter 每玩家令牌桶限流(F008)/ WebUiPermissions 权限门 / WebUiErrorCodes 错误码 / WebUiBatchAction 批量通道",并注明 market 等业务子系统通过 WebUiServerDispatcher.register 挂载到该网关(对应 MiningDim.java 第 170/180 行注册顺序依赖)。第九章165行子系统范式列表补上 `com.miningdim.webui.server`(与 client.webui、market 并列)。第八章"安全红线"(152-159行)补第7条"每玩家令牌桶限流(WebUiRateLimiter, F008)"。

**[Major] [D-覆盖缺口] system.batch 批量通道与其白名单安全边界零文档**

- 位置: 第 96-103 (第 5.1 节全节) 行
- 文档原文: L96-103 第 5.1 节"请求-回执流（带 requestId 关联）"只描述单条 action 的一来一回, 全文无任何批量通道描述。
- 代码实际: 服务端已有 system.batch: 一次往返跑完多条只读 action, 整批只占一个 requestId, 因此批内 handler 拿不到派发器的"同 requestId 只执行一次"防重放保护 —— 往 BATCHABLE 白名单里加写 action 等于开资金漏洞。这是与第 5.3 节"requestId 去重防重放"直接相关的例外, 却没有任何文档记载。
- 取证: src/main/java/com/miningdim/webui/server/WebUiBatchAction.java:20 "\"system.batch\" —— 一次往返跑完多条<b>只读</b> action。"；同文件 :27-30 "<b>白名单是安全边界, 不是性能清单。</b> 整批只占一个 requestId, 于是批内 handler 拿不到派发器那道\"同 requestId 只执行一次\"的防重放保护 ... 加错的代价是资金漏洞"；webui/src/lib/actions.ts:106 "'system.batch',"
- 建议改法: 第五章新增 5.4 节"只读批量通道（system.batch）", 至少写清三点: (1) 批内所有子 action 共享外层这一个 requestId, 派发器的"同 requestId 只执行一次"防重放(WebUiServerDispatcher.markRequestProcessed)只挡得住整批重放, 挡不住批内单条; (2) 因此 BATCHABLE 白名单(WebUiBatchAction.BATCHABLE)只准收纯只读 handler, 判据是"该 handler 不得改变任何玩家可见的持久状态"(幂等惰性初始化如 quest.board 当日任务板除外); (3) 白名单是安全边界不是性能清单, 加错一条写 action 等于二次扣款/二次发货的资金漏洞。同时把第八章"安全红线"第 6 条"requestId 去重防重放"改成带例外指针的措辞(例如补一句"system.batch 批内子 action 除外, 见 5.4"), 避免该条被读成对所有 action 无条件成立的全称断言。

**[Major] [A-文档与代码不符] 数据面示例仍用 MiracleBridge 与不存在的 action 名**

- 位置: 第 98, 103 行
- 文档原文: L98: "1. JS：`MiracleBridge.callServer('market.placeOrder', {itemRef, count, unitPrice, currency})`。"；L103: "6. 实时联动（他人挂单/成交）：服务端 → `S2CWebUiEvent(eventName, dataJson)` → JS `MiracleBridge.on('market:update', ...)`。"
- 代码实际: 实际全局入口是 window.miningdimQuery / window.miningdimOnEvent, 无 MiracleBridge; 挂单 action 名是 market.place 不是 market.placeOrder; 事件名风格也已在 WebUI_ServerPush_DesignSpec.md 第三章定为 `域.事件` (market.sold), 与 `market:update` 冒号写法冲突。
- 取证: src/main/java/com/miningdim/client/webui/WebUiClient.java:36 "private static final String QUERY_FUNCTION = \"miningdimQuery\";"；src/main/java/com/miningdim/client/webui/WebUiBridge.java:440-441 "if (typeof window.miningdimOnEvent === 'function') { window.miningdimOnEvent(..."；src/main/java/com/miningdim/market/MarketActions.java 中注册名为 "market.place"; docs/WebUI_ServerPush_DesignSpec.md:49 "命名一律 `域.事件` 小驼峰 ... (`market.sold` 而非 `MARKET_SOLD` / `marketSold`)"
- 建议改法: L98 改为："1. JS：`window.miningdimQuery({request: JSON.stringify({action: 'market.place', requestId, payload}), onSuccess, onFailure})`。"；L103 改为："6. 实时联动（他人挂单/成交）：服务端 → `S2CWebUiEvent(eventName, dataJson)` → JS 经 `window.miningdimOnEvent` 分发给页面预置的事件总线，前端订阅入口见 `webui/src/lib/bridge.ts` 的 `on('market.sold', ...)`（事件名遵循 `docs/WebUI_ServerPush_DesignSpec.md` 第 49 行"域.事件"小驼峰规范，而非 `market:update`）。"

### `docs/WebUI_ChineseIME_DesignSpec.md`

**[Major] [A-文档与代码不符] IME 文档引用的 WebUiScreen 行号与 GitHub 锚点全部失效, 且指向内容对不上**

- 位置: 第 61, 77, 101 行
- 文档原文: L61: "[`WebUiScreen.charTyped`](../src/main/java/com/miningdim/client/webui/WebUiScreen.java#L181-L190) 现状:"；L77: "`WebUiScreen` 类头注释第 24-26 行与 `charTyped` 内第 183-185 行, 均写着:"；L101: "高危: `keyPressed` 第 158 行 ESC 无条件 `onClose()`"
- 代码实际: WebUiScreen.java 现为 342 行: 类头那条错误注释在 27-29 行; charTyped 在 300-309 行, 内部错误注释在 302-304 行; keyPressed 的 ESC 无条件 onClose 在 270-272 行。L61 的 GitHub 风格锚点 #L181-L190 指向的是 toPixelX/toPixelY 坐标换算, 完全不是 charTyped。
- 取证: src/main/java/com/miningdim/client/webui/WebUiScreen.java:27-29 "中文 IME (step2 接口位): 完整 IME 需叠加一个不可见原版 EditBox 捕获 GLFW IME 组字事件 (preedit/commit),"；同文件 :300 "public boolean charTyped(char codePoint, int modifiers) {"；:271-272 "if (keyCode == GLFW.GLFW_KEY_ESCAPE) { onClose();"
- 建议改法: 将 docs/WebUI_ChineseIME_DesignSpec.md 三处行号引用逐一改正: 第61行锚点 #L181-L190 改为 #L300-L309(当前 charTyped 完整方法体); 第77行"类头注释第 24-26 行与 charTyped 内第 183-185 行"改为"类头注释第 27-29 行与 charTyped 内第 302-304 行"; 第101行"keyPressed 第 158 行"改为"keyPressed 第 271-272 行"。鉴于该文件仍在活跃迭代(近期已有 5 次以上后续提交导致行号漂移), 建议比照本文档第四章的写法, 只用方法名/关键字定位(不写死行号或 GitHub 行锚), 从根上避免下次再漂; 若确需保留行号, 应在同一提交里对该文档做一次"落地时校准", 并在文档状态栏注明校准所对应的代码提交哈希。

### `docs/WebUI_Frontend_Wiring_Checklist.md`

**[Major] [B-文档互相打架] handshake 回执字段名文档内部自相矛盾(serverVersion vs modVersion)，且被文档重申为"真源"的一侧与代码不符**

- 位置: 第 119 行
- 文档原文: L119: "回 `{serverVersion, actions:[]}`，前端启动比对。"；同文 L289: "3. [x] A3 契约握手：`WebUiServerDispatcher.registeredActions()` 自省 + `system.handshake` 回 `{modVersion, actions[]}`。"
- 代码实际: 服务端实际回 modVersion, 前端 HandshakeReport 也按 modVersion 解析。清单第三章的 serverVersion 是唯一错的那处。
- 取证: src/main/java/com/miningdim/webui/server/WebUiServerSubsystem.java:105 "result.addProperty(\"modVersion\", ModList.get().getModContainerById(MiningConstants.MODID)"；webui/src/lib/bridge.ts:527 "modVersion: string"
- 建议改法: 将 docs/WebUI_Frontend_Wiring_Checklist.md:119 的 `{serverVersion, actions:[]}` 改为 `{modVersion, actions:[]}`，与同文件 289 行、src/main/java/com/miningdim/webui/server/WebUiServerSubsystem.java:96,105 及 webui/src/lib/bridge.ts:527,553-561 保持一致；顺带核实第三章 A3 行的状态标记(当前仍写 BACKEND、注明"ACTIONS 表无自省接口")是否也已过期，因为 WebUiServerDispatcher.java:225 的 registeredActions() 与 289 行的批 0 完成记录显示自省接口已交付。

**[Major] [A-文档与代码不符] 本 mod 自有贴图数 246/57 与磁盘实数差 2.2 至 3.8 倍**

- 位置: 第 129 行
- 文档原文: 「(2) **本 mod 自己的 246 item + 57 block 贴图：就在本仓库 `src/main/resources/assets/miningdim/textures/` 下，前端同 monorepo，构建期 copy 即可，非阻塞**」
- 代码实际: 该路径下 item 目录实有 553 张 PNG(顶层 461 张), block 目录实有 217 张 PNG(顶层 188 张)。文档给的 246/57 分别低估 2.2 倍与 3.8 倍。该数字被 A13 行直接用作「构建期 copy 即可、非阻塞」的论据, 照此估算构建期资产拷贝体量与打包尺寸会显著失真。
- 取证: 文件系统机械取证(工作树 D:/Repo/_wt-docs, 基线 bd0c4588): `rg --files -g '*.png' src/main/resources/assets/miningdim/textures/item/ | wc -l` = 553; `rg --files -g '*.png' src/main/resources/assets/miningdim/textures/block/ | wc -l` = 217; 顶层直接计数 `ls .../item/*.png | wc -l` = 461, `ls .../block/*.png | wc -l` = 188。textures/ 下另有 entity/gui/mob_effect/models 四个子目录未计入。
- 建议改法: 把 L129 的「246 item + 57 block 贴图」改为「553 张 item + 217 张 block 贴图(含子目录; 顶层 461 + 188)」, 并补一句说明 textures/ 下另有 entity/gui/mob_effect/models 四类未计入; 同时把该数字改成引用一条可复跑的计数命令, 避免下次再钉死过期实数。

**[Major] [C-实现状态标错] 婚姻求婚反查标为未实现，实际已落地且刻意不建索引**

- 位置: 第 203 行
- 文档原文: L203: "| E3 | 谁向我求婚 | BACKEND | `MarriageProposals` 只有 byProposer 单向表，**无反查索引**，需新增反查或 O(n) 扫描 |"；docs/WebUI_Wiring_Execution_Scope.md:145 同义: "`MarriageProposals` 只有 byProposer 单向表，**无反查索引**，须新增反查"
- 代码实际: MarriageProposals 已提供 public 反查方法 proposersFor(UUID), 且类注释明确说明是刻意选择 O(n) 扫描而不是建第二张索引表; marriage.respond action 也已注册。WebUI_ServerPush_DesignSpec.md:90 把它称作"W6 接线新增的 `marriage.respond` 反查索引", 措辞同样与实现相反。
- 取证: src/main/java/com/miningdim/marriage/MarriageProposals.java:83 "public List<UUID> proposersFor(UUID target) {"；同文件 :74 "<b>刻意不建第二张 target -&gt; proposer 的索引表</b>: 那张表必须在三处同步失效"；webui/src/lib/actions.ts:86 "'marriage.respond',"
- 建议改法: 1) WebUI_Frontend_Wiring_Checklist.md:203 E3 一行状态由 BACKEND 改为 READY，说明改为："`MarriageProposals.proposersFor(UUID)` 已提供反查，实现刻意用 O(n) 扫描而非新建第二张 target->proposer 索引表(理由见该类 javadoc :74-79)；`marriage.state` 已将结果并入 `incomingProposals` 字段，`marriage.respond` 已注册上线"。 2) WebUI_Wiring_Execution_Scope.md:145 同步改为 READY/已完成，措辞去掉"须新增反查"。 3) WebUI_ServerPush_DesignSpec.md:90 的"W6 接线新增的 `marriage.respond` 反查索引"改为"W6 接线已上线的 `marriage.respond`(反查方法 `MarriageProposals.proposersFor`)"，避免"索引"一词暗示存在第二张表。

**[Major] [A-文档与代码不符] F3 行称 /mining enter 跳过 gateCheck 且从不传送, 与活跃代码相反**

- 位置: 第 215 行
- 文档原文: | F3 | `mining.enter` | WRAP(有陷阱) | **进入有三条不一致路径**：只有 entrance 方块交互走完整 gateCheck + teleport；`/mining enter` 命令与 SelectZoneC2S 包都跳过 gateCheck 且**从不实际传送玩家**。新增 action 必须复用 `EntryGateway.requestEnter` 权威路径 |
- 代码实际: 活跃的 /mining 命令树是 entry.MiningCommands, 它的 enter 直接委派 EntryGateway.requestEnter, 该方法第一步就是 gateCheck(含入场费), 通过后走完整传送链, 与 entrance 方块同一条路径。真正"只 allocate 不传送"的是并行开发期遗留、**从未接入主类**的 command.MiningCommands(代码里明确写了不接线的理由)。另一条被点名的 SelectZoneC2S 更是已随 F087 删除, 全仓已无该类, 只剩两处注释追述。照这行文字去排查会指向不存在的缺陷。
- 取证: src/main/java/com/miningdim/entry/MiningCommands.java:87 `entrySystem.gateway().requestEnter(player, difficulty, reseed);`; src/main/java/com/miningdim/entry/EntryGateway.java:107-109 `public void requestEnter(ServerPlayer player, Difficulty difficulty, boolean reseed) { long entryFee = MiningServices.config().entryFee(difficulty); GateResult gate = gateCheck(player, difficulty, entryFee);`; src/main/java/com/miningdim/MiningDim.java:36-38 `/mining 命令树以 entry.MiningCommands 为唯一权威 ... command 包的 CommandSystem 是并行期产出的另一套 /mining (其 enter 仅 allocate 不传送, 与 14.2 不符), 不接入主类`; src/main/java/com/miningdim/network/MiningNetwork.java:56 `F087: 原 discriminator 0 曾注册 SelectZoneC2S (零发送方、绕过矿工等级门直接 allocate、且从不传送玩家的...`
- 建议改法: 把 F3 说明改为: "进入的唯一权威路径是 `EntryGateway.requestEnter`(gateCheck + 入场费 + 传送), `/mining enter`(entry.MiningCommands) 与 entrance 方块都走它。仅并行期遗留的 `com.miningdim.command.MiningCommands` 仍是 allocate-only, 但它未接入主类不会被触发; SelectZoneC2S 已随 F087 删除。新增 action 直接复用 requestEnter。"

**[Major] [B-文档互相打架] UI 偏好用 localStorage 的决定已被推翻但清单仍标真源**

- 位置: 第 361 (配合 282) 行
- 文档原文: L361: "2. **UI 偏好持久化**（A9）：先不做 capability 字段，用 Chromium localStorage；等真出现跨机器诉求再补。"；L282: "第三章接线总表与第七章决策项**不受影响，仍是真源**。"
- 代码实际: docs/WebUI_Wiring_Execution_Scope.md:88 已明写"**推翻清单第七章'我直接定的'第 2 条**（原定用 localStorage）。S1 选了一次到底，故落 capability 字段", 且代码已按 capability 落地 (MiningPlayerData.uiPrefs + player.prefs.get/set 两条 action)。清单未回写, 还自称真源。
- 取证: src/main/java/com/miningdim/entry/MiningPlayerData.java:46 "private UiPrefs uiPrefs = UiPrefs.DEFAULT;"；src/main/java/com/miningdim/entry/IMiningPlayerData.java:101-104 "当前界面偏好 (永不返回 null; 从未设置过为 {@link UiPrefs#DEFAULT})。player.prefs.get 直接下发本值。 ... UiPrefs uiPrefs();"；src/main/java/com/miningdim/market/PlayerWebUiActions.java:71-72 "register(\"player.prefs.get\", PREFS_GET); ... register(\"player.prefs.set\", PREFS_SET);"
- 建议改法: 将 docs/WebUI_Frontend_Wiring_Checklist.md L361 改为:"~~先不做 capability 字段，用 Chromium localStorage~~ —— 已于 2026-08-13 被 WebUI_Wiring_Execution_Scope.md 第一章 S1 推翻, 实际落 capability 字段 `MiningPlayerData.uiPrefs`(见 player.prefs.get/set)"；同时修正 L282,将"第三章接线总表与第七章决策项不受影响，仍是真源"改为仅涵盖未被 Execution_Scope.md 推翻的条目,或直接加一句"其中第 2 条(UI 偏好持久化)已被 WebUI_Wiring_Execution_Scope.md 推翻,以该文为准"，避免与顶部已有的 2026-08-13 视觉修订说明并列的这条遗漏继续误导读者。

### `docs/WebUI_ServerPush_DesignSpec.md`

**[Major] [A-文档与代码不符] WebUI 服务端推送设计规格文档全链路表与红线引用的行号全部对不上代码, 且错误行号已复制扩散到另外两份下游文档**

- 位置: 第 16-17, 22, 24 行
- 文档原文: L16: "| 包注册 | `MiningNetwork.java:77-78` | 已注册 |"；L17: "| 服务端发送门面 | `MiningNetwork.sendWebUiEvent(ServerPlayer, S2CWebUiEvent)` (`:142`) | 已实现, **零生产调用方** |"；L22: "| 前端订阅 | `webui/src/lib/bridge.ts:360` `on(eventName, handler)` | 已实现 |"；L24: "前端 `bridge.ts:349-354` 把 handler 的 data 定成 `unknown`"
- 代码实际: S2CWebUiEvent 的 registerMessage 在 MiningNetwork.java:84-86 (77-78 是上面那段注释); sendWebUiEvent 定义在 :123 不是 :142; bridge.ts 的 on() 在 :509 不是 :360; "data 是 unknown"那段注释在 bridge.ts:503-504 不是 349-354 (349-354 是错误 JSON 解析分支)。同样的 `MiningNetwork:142` 也出现在 WebUI_Wiring_Execution_Scope.md:47 与 WebUI_Frontend_Wiring_Checklist.md:127, `lib/bridge.ts:264-269` 出现在 Scope:201 (实际红线注释在 bridge.ts:506-507)。
- 取证: src/main/java/com/miningdim/network/MiningNetwork.java:84 "CHANNEL.registerMessage(nextId(), S2CWebUiEvent.class,"；同文件 :123 "public static void sendWebUiEvent(ServerPlayer player, S2CWebUiEvent msg) {"；webui/src/lib/bridge.ts:509 "export function on(eventName: string, handler: WebUiEventHandler): () => void {"；webui/src/lib/bridge.ts:503 "* data 是 unknown 而非具体类型: 服务端 sendWebUiEvent 至今零业务调用方"
- 建议改法: 逐条改正 WebUI_ServerPush_DesignSpec.md: L16 `MiningNetwork.java:77-78` 改为 `:84-86`; L17 `:142` 改为 `:123`(并去掉指向 `sendDanger` 方法的错误落点); L22 `bridge.ts:360` 改为 `:509`; L24 `bridge.ts:349-354` 改为 `:503-504`。随后同步修正两处下游同源错误: WebUI_Wiring_Execution_Scope.md:47 与 WebUI_Frontend_Wiring_Checklist.md:127 的 `MiningNetwork:142` 改为 `:123`; Scope.md:201 的 `lib/bridge.ts:264-269` 改为 `:506-507`。鉴于本文档 L66 已自陈"本文档写于接线批次之前, 行号会漂", 更根本的修复是仿照该文档自己在其他地方的做法, 统一改成只写类名+方法名(如 `MiningNetwork.sendWebUiEvent` / `bridge.ts on()`)不写具体行号, 从源头避免三份文档因行号漂移而反复失真。

### `docs/WebUI_Wiring_Execution_Scope.md`

**[Major] [A-文档与代码不符] 执行范围表复述了同一条过期的"三条不一致路径"**

- 位置: 第 153 行
- 文档原文: | `mining.enter` | WRAP | F3 | **必须复用 `EntryGateway.requestEnter` 权威路径**。现存三条不一致路径中，`/mining enter` 与 SelectZoneC2S 都跳过 gateCheck 且从不传送 |
- 代码实际: 与 WebUI_Frontend_Wiring_Checklist.md:215 同源的过期结论。/mining enter 走的就是 requestEnter(含 gateCheck 与传送), SelectZoneC2S 类已不存在。两份文档同时错, 修一份不修另一份仍会误导。
- 取证: src/main/java/com/miningdim/entry/MiningCommands.java:87 `entrySystem.gateway().requestEnter(player, difficulty, reseed);`; src/main/java/com/miningdim/entry/EntryGateway.java:107-109 gateCheck 在 requestEnter 内第一步; src/main/java/com/miningdim/network/ 目录下已无 SelectZoneC2S.java(仅 MiningNetwork.java:56 与 MiningWebUiActions.java:41 的追述注释)
- 建议改法: 与 F3 行同步改写：只保留"必须复用 EntryGateway.requestEnter 权威路径"这一句结论，删去关于 /mining enter 与 SelectZoneC2S 仍会跳过 gateCheck、从不传送的现在时事实陈述；如需保留历史脉络，改为过去时表述——"历史上曾有的两条旁路：command.MiningCommands（com.miningdim.command 包，未接入主类 MiningDim 的 subsystems 列表，RegisterCommandsEvent 监听器不会注册，属死代码）与 SelectZoneC2S（已整包删除，见 MiningNetwork.java 的 F087 注释）"。同时应在 WebUI_Frontend_Wiring_Checklist.md:215 处做同步修订，避免两份文档各自维护同一条已过期结论、修一份漏一份继续互相印证误导读者。

**[Major] [E-状态过期] 验收口径写的 GameTest 基线 817 已过期（实际约 1414-1474，而非待核发现所称的 1616）**

- 位置: 第 259 行
- 文档原文: L259: "| Java 侧 | `runGameTestServer` 全绿（当前基线 817），新增 action 必须有 GameTest 断言**具体业务结果**"
- 代码实际: 当前主分支源码里 @GameTest 注解共 1616 处, 约为文档基线的两倍。按 817 去判"全绿"会把大批用例缺席当成正常。
- 取证: 对 src/main/java 全量统计 @GameTest 注解出现次数 = 1616 (grep -rc "@GameTest" --include=*.java src/main/java 求和)。注: 该计数是注解数, 与 runGameTestServer 日志行 "All N required tests passed" 的 N 可能有出入 (模板化用例), 但两者相差近一倍, 足以判定 817 已过期。
- 建议改法: 把 docs/WebUI_Wiring_Execution_Scope.md:259 的"（当前基线 817）"改为"（基线以合入前在 main 上实跑一次 `runGameTestServer` 的日志行 `All N required tests passed` 为准，不写死数字）"。这与仓库内已有共识一致：docs/modules/farmer/README.md:34 已明确"文件数与 GameTest 数不在本文钉死（每补一个用例就会过期），当期基线见 INVENTORY.md"，且 docs/modules/INVENTORY.md:3 已给出可复用的精确统计口径（`@GameTest` 注解出现次数，不含 `@GameTestHolder`、`@GameTestGenerator`）。若仍想保留一个可读的参考数字，应引用 INVENTORY.md 当期实测值（如"参见 docs/modules/INVENTORY.md 当期基线"），而不是在本文里另外硬编码一个会持续漂移的数字。

**[Major] [E-状态过期] 第八章 J9 行确已过期(战术扫描已用平板按钮落地); J8 行仅塔罗子项落地, 青辉石子项本就无法命中, 婚戒子项仍待市场侧授权, 不应整条标记为已拍板**

- 位置: 第 274-275 行
- 文档原文: L274: "| J9 | **特勤扫描的触发入口** | `job.agent.scan`（W4） | 三件套齐全但零调用点。选项：专用道具 / 键位 / 平板内按钮 / 手持特定物品右键 |"；L275: "| J8 | **可交易标的白名单** | `market.tradable`（W2） | 塔罗禁交易、青辉石绑定、婚戒绑定在设计里存在但挂单路径无过滤 |"；L281: "前三条**直接卡住本轮的 W2/W4/W9**，须在对应分支开工前拍板。"
- 代码实际: J9 已按"平板内按钮"落地: job.agent.scan 已注册且 AgentWebUiActions 类注释写明"扫描与封印的唯一入口是本类的三条 WebUI action"。J8 也已落地: MarketTradeWhitelist 是挂单拒绝与 market.tradable 只读预判的共用真源。两条都不再卡。
- 取证: src/main/java/com/miningdim/job/agent/AgentWebUiActions.java:90 "WebUiServerDispatcher.register(\"job.agent.scan\", SCAN);"；同文件 :38 "扫描与封印的唯一入口是本类的三条 WebUI action"；src/main/java/com/miningdim/market/MarketActions.java:86 "WebUiServerDispatcher.register(\"market.tradable\", TRADABLE);"；src/main/java/com/miningdim/market/MarketTradeWhitelist.java:15 "唯一真源纪律: {@link MarketEngine#place} 的挂单拒绝与 {@code market.tradable} 的只读预判**共用本类的"
- 建议改法: L274(J9)行改为: "已拍板落地 = 平板内按钮。job.agent.scan 已注册(AgentWebUiActions.java:90)且已被 webui/src/pages/jobs/panels/AgentPanel.tsx 的『战术扫描』按钮实际调用(:203/:278); 原生 AgentScanMenu 面板路径已删除, 决策依据见 AgentWebUiActions.java:37-39 类注释。" L275(J8)行改为: "塔罗禁交易子项已落地(MarketTradeWhitelist.judge, 被 MarketEngine#place 与 market.tradable 只读预判共用, 唯一真源见 MarketTradeWhitelist.java:15-17); 青辉石绑定子项因 miningdim:azurite 无对应注册物品, 规则永远无法命中真实物品, 判定为设计上的『刻意不写分支, 非遗漏』(MarketTradeWhitelist.java:30-31); 婚戒绑定子项仍未纳入市场侧规则, 代码注释(:32)原话『市场侧未获授权, 不擅自加』, 即该子决策仍待用户就『市场侧是否要接管婚戒转移限制』拍板, 不应视为已解决。" L281 改为: "J9 与 J8 的塔罗子项已落地, 不再卡本轮; J8 的婚戒子项与 `shop.buy`(W9)仍卡, 须在对应分支开工前拍板。"

### `webui/README.md`

**[Major] [D-覆盖缺口] webui/README.md:76 客户端本地 action 清单漏记 5 条，其中 client.closePanel/client.textFocus 涉及界面能否自关与输入焦点让位**

- 位置: 第 76 行
- 文档原文: L76: "- 客户端本地 action：`client.i18n`（翻译键 -> 显示名），不走服务端往返"；docs/WebUI_Wiring_Execution_Scope.md:37 亦只列 "`client.i18n`、`client.playCaseSound`" 两条
- 代码实际: WebUiBridge.handleClientLocal 现已处理 6 条: client.i18n / client.playCaseSound / client.closePanel / client.textFocus / client.display.get / client.display.set。其中 client.closePanel 与 client.textFocus 分别关系到"页面关不掉自己"和 ESC/开关键让位打字, 属对接线人有实际影响的契约。
- 取证: src/main/java/com/miningdim/client/webui/WebUiBridge.java:224 "if (\"client.playCaseSound\".equals(action)) {"、:228 "if (\"client.closePanel\".equals(action)) {"、:233 "if (\"client.textFocus\".equals(action)) {"、:237 "if (\"client.display.get\".equals(action)) {"、:241 "if (\"client.display.set\".equals(action)) {"；webui/src/lib/actions.ts:117-124 CLIENT_LOCAL_ACTIONS 六条
- 建议改法: 把 webui/README.md L76 改为："客户端本地 action（`WebUiBridge.handleClientLocal` 就地处理，不走服务端往返；真源见 `webui/src/lib/actions.ts` 的 `CLIENT_LOCAL_ACTIONS`）：`client.i18n`（翻译键 -> 显示名）/ `client.playCaseSound` / `client.closePanel`（页面请求关闭平板 UI）/ `client.textFocus`（上报当前焦点是否可编辑，用于 ESC/开关键让位）/ `client.display.get` / `client.display.set`"。同时建议顺带核对 docs/WebUI_Wiring_Execution_Scope.md 第 36 行同一处的两条清单，改成引用 actions.ts 而非在文档里手抄列表，避免后续新增 client-local action 时再次漏更两份文档。

**[Major] [A-文档与代码不符] 仍写 50 条未接线假 action, 实际只剩 2 条**

- 位置: 第 83-84 行
- 文档原文: L83-84: "2. **`callMock` 的 planned 分流在生产构建下必须硬失败。** 见 `src/mock/handlers.ts`。缺了这道门，50 条尚未接线的假 action 会在真客户端里由内存世界作答。"
- 代码实际: PLANNED_ACTIONS 现在只剩 shop.catalog / shop.detail 两条, 其余 48 条已接线并从 planned 表移除。webui/src/components/kit/README.md:25-26 有一字不差的同一句话, 同样过期。
- 取证: webui/src/mock/planned.ts:102 "export const PLANNED_ACTIONS = ['shop.catalog', 'shop.detail'] as const"
- 建议改法: 两处(webui/README.md:83-84 与 webui/src/components/kit/README.md:25-26)都把"50 条"改成"尚未接线的 planned action(当前只剩 `shop.catalog` / `shop.detail` 两条, 以 `src/mock/planned.ts` 的 PLANNED_ACTIONS 为准)", 去掉写死的数字, 避免下次核销又忘改。

### `webui/_pixel-archive/public/ui/README.md`

**[Major] [B-文档互相打架] 归档文档称 publicDir 指向 mod 贴图, 与代码不符——该描述从文件诞生的同一次提交起就已过时, 并非封存期之后才漂移**

- 位置: 第 79-83 行
- 文档原文: L79-83: "## 五、publicDir 冲突的处置（已闭环）\n`webui/vite.config.ts` 把 `publicDir` 指向了 `../src/main/resources/assets/miningdim/textures`（为的是让 mod 物品贴图直接映射为静态资源，避免复制副本造成双源漂移）。Vite 只支持一个 publicDir，因此**本目录不是站点静态根**"
- 代码实际: vite.config.ts 从未 (或已不再) 设置 publicDir, 改用自写插件 modTexturesPlugin 把 mod 贴图挂到 /mc/ 前缀下, 并在注释里明确否定 publicDir 方案。webui/README.md:88-91 亦写"刻意不用 `publicDir` 指过去"。归档文档整节的前提与结论都不再成立。
- 取证: webui/vite.config.ts:145 "为什么不用 publicDir 直接指过去: publicDir 全局唯一, 指向 mod 目录会让 webui/public/ 变成一个"; 同文件 :151 "name: 'miningdim-mod-textures',"; :234 "plugins: [react(), tailwindcss(), modTexturesPlugin()],"; webui/README.md:88-90 "`vite.config.ts` 的 `modTexturesPlugin` 把 mod 的 `item/` 与 `block/` 贴图挂到 `/mc/` 下 ... 刻意不用 `publicDir` 指过去"
- 建议改法: 把第五节标题由"五、publicDir 冲突的处置（已闭环）"改为"五、publicDir 方案的放弃记录（早期设计, 未进入最终代码）"；正文第一段换成："本节所述的 `publicDir` 指向 mod 贴图目录的方案, 是本文件所在同一次提交（86bddb56, 2026-08-12）完成前就已被放弃的中间设计——该提交的'主控复验后的修正'已改为自写的 `modTexturesPlugin` 把 `item/`、`block/` 贴图挂到 `/mc/` 下, `vite.config.ts` 从未在最终代码里设置过 `publicDir`。此节保留仅作设计过程记录, 现行机制以 `webui/README.md`'mod 贴图挂载'一节与 `webui/vite.config.ts` 的 `modTexturesPlugin` 为准, 重启像素风时按现行机制重新评估 `public/ui/` 的接入方式。" 第二段（PixelFrame.tsx 用 ESM import 引用三张 PNG 的做法）保持不变，因为该做法与 publicDir 是否存在无关，本身仍然是当前可行方案，不需要跟着改。

### `webui/_pixel-archive/public/ui/icons/README.md`

**[Major] [F-结构问题] 归档资产文档仍指向搬走的路径与已删除的守卫脚本**

- 位置: 第 7, 26, 56, 62 行
- 文档原文: L7: "消费侧组件：`webui/src/components/pixel/PixelIcon.tsx`。"；L26: "写在 `webui/tools/gen-icons.mjs` 的 `ICONS` 表里"；L62: "构建期尺寸守卫已覆盖本目录：`scripts/verify-pixel-guards.mjs` 递归扫描 `public`"
- 代码实际: 三处路径全已随像素风封存搬到 webui/_pixel-archive/ 下 (PixelIcon.tsx / tools/gen-icons.mjs), 而 verify-pixel-guards.mjs 已被删除, webui/scripts/ 下只剩 check-frontend-contract.mjs。同一目录的 public/ui/README.md:90-91 也有同样的失效引用, 且与 _pixel-archive/README.md:36 自述"前者已随本次换皮删除"直接打架。
- 取证: webui/scripts/ 目录实际只含 check-frontend-contract.mjs, 无 verify-pixel-guards.mjs；实际文件位于 webui/_pixel-archive/src/components/pixel/PixelIcon.tsx 与 webui/_pixel-archive/tools/gen-icons.mjs；webui/_pixel-archive/README.md:35-36 "3. 恢复构建期守卫：`scripts/verify-pixel-guards.mjs` 与 `assertPixelGrid.ts`（前者已随本次换皮删除，从 git 历史取回）"
- 建议改法: 把 webui/_pixel-archive/public/ui/icons/README.md 第 7、26 行以及 webui/_pixel-archive/public/ui/README.md 第 90 行内的路径统一加 `_pixel-archive/` 前缀(`webui/_pixel-archive/src/components/pixel/PixelIcon.tsx`、`webui/_pixel-archive/tools/gen-icons.mjs`); 把两处提到 `scripts/verify-pixel-guards.mjs` 为"已覆盖/现存"的现在时表述(icons/README.md:56、:62 与 public/ui/README.md:90-91), 改为"该守卫已随 2026-08-13 换皮删除(见 webui/_pixel-archive/README.md 第 3 步, 需从 git 历史 `git log --diff-filter=D -- webui/scripts/verify-pixel-guards.mjs` 取回), 重启像素风前本节描述不成立"。

### `webui/src/components/kit/README.md`

**[Major] [A-文档与代码不符] kit 契约层 README 仍写 50 条未接线假 action, 实为 2 条**

- 位置: 第 25-26 行
- 文档原文: 「2. **`callMock` 的 planned 分流必须在生产构建下硬失败**。见 `src/mock/handlers.ts`。缺了这道门, / 50 条尚未接线的假 action 会在真客户端里由内存世界作答。」
- 代码实际: PLANNED_ACTIONS 已核销至只剩 2 条(shop.catalog / shop.detail), 且这两条的服务端在 WOK-ChestShop 跨仓、本轮明确不做。该门的实际风险面是 2 条而非 50 条。webui/README.md:83-84 的同一处 50 条错误已被上一轮审查登记, 但同一个数字在本文件(零发现文档)里原样存在, 属于同源过期数字的二次扩散。
- 取证: webui/src/mock/planned.ts:102 「export const PLANNED_ACTIONS = ['shop.catalog', 'shop.detail'] as const」; webui/src/mock/handlers.ts:9 「 *   2. **planned.ts 里剩下那 2 个** (shop.catalog / shop.detail): 后端还不存在 (在 WOK-ChestShop 跨仓,」; webui/src/mock/planned.ts 头部注释「本轮核销后只剩 H 组两条 (shop.catalog / shop.detail)」。
- 建议改法: 把 webui/src/components/kit/README.md:26 的「50 条尚未接线的假 action」改为「planned.ts 里剩下的 2 条假 action(shop.catalog / shop.detail, 后端在 WOK-ChestShop 跨仓)」; 与 webui/README.md:83-84 的同源修正一并提交, 避免只改一处继续分叉。

### `docs/PixelUI_DesignSystem_DesignSpec.md`

**[Minor] [A-文档与代码不符] DEFERRED 教训引用 VISUAL_REVIEW 1.1 节, 87% 实出自 1.2 节**

- 位置: 第 22 行
- 文档原文: 「>    而它在实际页面里 87% 的容器仍然是灰的（见封存的 `VISUAL_REVIEW.md` 1.1 节机械统计）——」
- 代码实际: 87% 这个数字出自 VISUAL_REVIEW.md 的 1.2 节(灰度上色链路是否真的接通), 原文口径是「87% 的框体没有用[染色机制]」。被引用的 1.1 节(语义色 token 是否真的被各面板使用)给出的机械统计是 17.4% / 14.2% / 68%, 没有 87% 这个数。按文档指引翻到 1.1 节的读者查不到该数字。
- 取证: webui/_pixel-archive/VISUAL_REVIEW.md:66 「也就是说, 为了"一份灰度资产 x N 个颜色变量 = 整套 UI"这条压缩原则专门建的染色机制, 87% 的」(:67 续「框体没有用它。」), 该行位于 :50 起的「### 1.2 灰度上色链路是否真的接通」节内。1.1 节(:21 「### 1.1 语义色 token 是否真的被各面板使用」)的表格在 :26-29 给出「全部页面 70/333/17.4%」与「扣除三个验证/预览页 43/260/**14.2%**」, :41-44 给出 PixelBadge 68% 等, 均无 87%。全文 grep '87' 只命中 :66 与 :137。
- 建议改法: 把 L22 的「见封存的 `VISUAL_REVIEW.md` 1.1 节机械统计」改为「见封存的 `VISUAL_REVIEW.md` 1.2 节机械统计」; 若本意是同时引用容器着色占比, 可改为「1.1 与 1.2 两节机械统计」并把 1.1 的 14.2% 语义色占比一并写出。

**[Minor] [B-文档互相打架] 像素图标白名单 26 个的错误数字另有两处未登记**

- 位置: 第 26(同源: webui/src/components/kit/README.md:16) 行
- 文档原文: docs/PixelUI_DesignSystem_DesignSpec.md:26 「>    功能图标只剩一张 26 个名字的手工白名单，而平板 hub 的十个一级入口里有五个」; webui/src/components/kit/README.md:16 「只会得到一张永远补不全的名字白名单 —— 上一版的 `PixelIcon` 就是这么卡住的 (26 个名字, 首页/矿洞/」。
- 代码实际: 封存目录实有 24 张图标 PNG, 归档目录自己的 README 也写 24。上一轮审查只登记了 webui/_pixel-archive/README.md:21 的 26, 但同一个错误数字还在另外两份文档里, 其中 PixelUI 规格是该结论的原始出处、kit README 是当前在用的契约层文档, 两者都未被修正。
- 取证: 资产实数: `ls webui/_pixel-archive/public/ui/icons/*.png | wc -l` = 24。文档自证: webui/_pixel-archive/public/ui/icons/README.md:3 「本目录的 24 张 PNG 是 **PixelUI 规格第八章第 2 层**的功能图标」。全库 grep 「26 个名字|26 张」命中三处: docs/PixelUI_DesignSystem_DesignSpec.md:26、webui/src/components/kit/README.md:16、webui/_pixel-archive/README.md:21。
- 建议改法: 把三处的 26 统一改为 24, 与 webui/_pixel-archive/public/ui/icons/README.md:3 的 24 对齐; 修 _pixel-archive/README.md:21 时一并改掉另两处, 否则只修归档一处会让原始出处继续散布错误数字。

### `docs/WebUI_Architecture_DesignSpec.md`

**[Minor] [F-结构问题] 会话记忆键以 [[key]] 语法混入正文, 渲染为字面量死引用**

- 位置: 第 18, 36, 90, 167, 168, 231(同类: docs/PixelUI_DesignSystem_DesignSpec.md:52, 275) 行
- 文档原文: docs/WebUI_Architecture_DesignSpec.md:18 「与已定的 Astro Wiki（见 [[wiki-architecture-decision]]）」; :36 「（对比 [[external-mod-deps-gap]]）」; :90 「（见 [[jobs-implementation-state]]）」; :167 「（同 TACZ/Champions 范式，见 [[external-mod-deps-gap]]）...（改 build.gradle 才暴露，见 [[build-toolchain]]）」; :168 「（见 [[wiki-architecture-decision]]）」; :231 「（见 [[pixel-ui-design-decision]]）」; docs/PixelUI_DesignSystem_DesignSpec.md:52 「（见 [[unified-ui-entry-plan]]」; :275 「测试落点见 [[test-server-access]]」。
- 代码实际: 这些是 Claude 会话记忆条目的键名, 不是仓库内文件。GitHub Flavored Markdown 不支持 [[wiki-link]] 语法, 八处全部原样渲染成字面方括号文本, 点不开也查不到; 仓库内亦无同名 .md 文件。上一轮只登记了 docs/Power_Economy_Rebalance_DesignSpec.md:277/375 的两处(那两处是 [text](target) 与引用式语法, 与本条的 [[key]] 语法及文件均不同)。
- 取证: 机械取证: `grep -rn '\[\[[a-z0-9-]*\]\]' --include='*.md' .` 在工作树内共命中 9 行, 其中 docs/MiningDimension_Mod_DesignSpec.md:48 的 [[mods]] 是 mods.toml 代码块内的 TOML 表头(正常), 其余 8 行即上列两文件的 6 处 + 2 处。仓库内不存在 wiki-architecture-decision.md / external-mod-deps-gap.md / build-toolchain.md / jobs-implementation-state.md / pixel-ui-design-decision.md / unified-ui-entry-plan.md / test-server-access.md 任何一个文件(rg --files -g '*.md' 全量清单可证)。
- 建议改法: 把八处 [[key]] 全部改写为对读者可用的引用: 能指向仓库内文档的改成真链接(如 [[pixel-ui-design-decision]] 改为 [PixelUI_DesignSystem_DesignSpec.md](PixelUI_DesignSystem_DesignSpec.md)); 纯属私有会话记忆、仓库内无对应文件的(build-toolchain / external-mod-deps-gap / test-server-access 等), 把该条结论本身的要点一句话写进正文并删掉括号引用, 不要留下读者打不开的键名。

### `webui/_pixel-archive/README.md`

**[Minor] [A-文档与代码不符] 像素图标数量三处都写 26，实际 24**

- 位置: 第 21 行
- 文档原文: L21: "| `public/ui/` | 三张 9-slice 边框 PNG + 26 张 16×16 图标 PNG |"；同一数字也出现在 webui/src/components/kit/README.md:16-17 "上一版的 `PixelIcon` 就是这么卡住的 (26 个名字, ...)" 与 docs/PixelUI_DesignSystem_DesignSpec.md:26-27 "功能图标只剩一张 26 个名字的手工白名单"
- 代码实际: PIXEL_ICON_NAMES 实际只有 24 个名字, public/ui/icons/ 下也只有 24 张 PNG; 同目录的 icons/README.md:3 自己写的是"本目录的 24 张 PNG", 两份归档文档互相打架。
- 取证: webui/_pixel-archive/src/components/pixel/PixelIcon.tsx:49-83 的 PIXEL_ICON_NAMES 共 24 项 (close/menu/settings/search/refresh/check/cross/plus/minus/arrow-up/arrow-down/arrow-left/arrow-right/sort/filter/warning/info/lock/star/heart/coin-credit/coin-azure/bag/clock)；webui/_pixel-archive/public/ui/icons/*.png 计数 = 24；webui/_pixel-archive/public/ui/icons/README.md:3 "本目录的 24 张 PNG 是 **PixelUI 规格第八章第 2 层**的功能图标"
- 建议改法: 三处"26"（webui/_pixel-archive/README.md:21、webui/src/components/kit/README.md:16-17、docs/PixelUI_DesignSystem_DesignSpec.md:26）统一改成"24"，并以 `PixelIcon.tsx` 的 `PIXEL_ICON_NAMES` 为唯一真源在括号里注明；同目录 `public/ui/icons/README.md:3` 已写对（24），修复时可直接引用它作为交叉校验依据。

---

## 模块治理

### `docs/WOK_Repository_Module_Plan.md`

**[Critical] [B-文档互相打架] 总方案(含第7章迁移顺序)仍按21个模块写, 落后登记表5个模块, 且执行清单直接漏排全库最大非职业模块**

- 位置: 第 33-42, 50-57, 131 行
- 文档原文: docs/modules/\n  README.md             21 个模块的总登记表
- 代码实际: module-registry.json 与 docs/modules/README.md 的总表都是 26 个模块。方案第 3 章三张表合计只列出 21 个, 缺 wok-store、wok-power、wok-quest、wok-enchant 四个基础/玩法模块与 wok-job-fisher 这个职业模块; 第 6 章的源码树同样没有 store/、power/、quest/、enchant/、job/fisher/。按本方案排迁移顺序会整体漏掉这 5 个模块, 其中 wok-power 是全库最大的非职业模块。
- 取证: docs/modules/module-registry.json 顶层 modules 数组实测 26 项, 含 `"id": "wok-store"`、`"id": "wok-power"`、`"id": "wok-quest"`、`"id": "wok-enchant"`、`"id": "wok-job-fisher"`; src/main/java/com/miningdim/ 下真实存在 store/、power/、quest/、enchant/、job/fisher/ 五个目录; src/main/java/com/miningdim/MiningDim.java:79 `subsystems.add(new com.miningdim.store.MiningStoreSubsystem());`、:113 `new com.miningdim.power.PowerSystem()`、:155 `new com.miningdim.job.fisher.FishingSystem()`、:185 `new com.miningdim.quest.QuestSystem()`、:188 `new com.miningdim.enchant.EnchantmentSystem()`。
- 建议改法: 除原建议(3.1节补 wok-store, 3.2节补 wok-job-fisher, 3.3节补 wok-power/wok-quest/wok-enchant, 第6章源码树同步补 store/、power/、quest/、enchant/、job/fisher/ 五个目录, 第131行"21 个模块的总登记表"改为"全部模块的总登记表(当期数量以 module-registry.json 或 gradlew verifyModuleRegistry 为准)")外, 必须同步修正第178-193行"阶段3：按低风险模块逐个迁移"的10步推荐顺序, 把 wok-store、wok-power、wok-quest、wok-enchant、wok-job-fisher 五个模块按依赖关系插入具体迁移批次(wok-power体量最大, 建议单独成批并置于依赖它的职业模块之前), 避免文档使用者按现有清单排期时整体漏掉这5个模块的迁移工作。

### `docs/modules/DEPENDENCY_DEBT.md`

**[Major] [A-文档与代码不符] D034/D035/D036 被归进仅测试夹具一节, 实为运行期耦合**

- 位置: 第 48, 63-65 行
- 文档原文: 以下条目的 `scope` 均为 `test-only`：耦合只出现在 GameTest 里，不影响运行期装配。
- 代码实际: 该节标题下的 D034、D035、D036 三条在 module-registry.json 里根本没有 `scope` 字段 (只有 D014/D018/D019/D020/D021/D026/D027/D030/D031/D032/D033 带 scope=test-only), 且三条的 evidence 全是运行期生产代码: MinerSystem.java、ChefHungerHandler.java 与五个 mixin, 没有一个是 GameTest 类。照该断言判断会把三条运行期反向依赖当成不影响装配的测试债务而降级处理。
- 取证: docs/modules/module-registry.json:1148 起 D034 条目无 scope 字段, evidence=["src/main/java/com/miningdim/job/miner/MinerSystem.java"]; D035 evidence=["src/main/java/com/miningdim/job/chef/ChefHungerHandler.java"]; docs/modules/module-registry.json:1168 起 D036 evidence 为 mixin/ItemStackMiningDurabilityMixin.java 等 5 个非测试文件; 对照 D014 条目显式写有 "scope": "test-only"。
- 建议改法: 把 D034、D035、D036 三行从「P3：仅测试夹具」表格中移出,归入 P1(玩法与基础模块横向依赖)或新开一节(如「P1b：跨职业运行期接缝」);P3 节标题与第48行的 test-only 前言保持原样不动;移动后逐条复核 P3 表格剩余条目(D014/D018/D019/D020/D021/D026/D027/D030/D031/D032/D033)在 module-registry.json 中确都带 `"scope": "test-only"` 且 evidence 全部指向 GameTest 类,避免同类问题再次混入。

### `docs/modules/INVENTORY.md`

**[Major] [D-覆盖缺口] 任务系统只有一份 WebUI 面板接线 TaskSpec, 缺任务系统本身的设计文档**

- 位置: 第 22 行
- 文档原文: | `wok-quest` | WOK-任务模块 | 35 | 49 | `QuestSystem` | 核心、经济、WebUI、附魔；TaCZ 可选 |
- 代码实际: 任务系统是已上线的玩家核心玩法(四类来源 DAILY/WEEKLY/SPECIAL/HIDDEN、任务板、任务链、领奖、上交、重摇、信用点与物品奖励), 但 docs/ 下与之相关的文件只有 TaskSpec_Quest_WebUI_Panel.md 一份, 而它按自述只是"接入 WebUI 平板面板"的实施规格, 并明确声明不碰任何任务判据/奖励数值/内容池。也就是说: 任务池内容、判据、刷新周期、奖励曲线、/quest 命令树(list/claim/turnin/refresh daily|weekly)全部无文档。这同时是上面那条"任务 faucet 未进收支总表"的根因。
- 取证: docs/TaskSpec_Quest_WebUI_Panel.md:28 `任务系统 (com.miningdim.quest) 服务端已完整落地: 四类来源 (DAILY/WEEKLY/SPECIAL/HIDDEN)、任务板、领奖、上交、重摇、信用点与物品奖励全通。玩家入口目前**只有 /quest 命令**。`; 同文件 :262 `- **不改任何任务判据 / 奖励数值 / 内容池**。QuestPool / QuestConfig / QuestRewards 一行不动。`; src/main/java/com/miningdim/quest/QuestCommands.java:28-44 注册 `"quest"` / `"list"` / `"claim"` / `"turnin"` / `"refresh"` / `"daily"` / `"weekly"`; ls docs | grep -i quest 只有 TaskSpec_Quest_WebUI_Panel.md
- 建议改法: 新建 docs/Quest_System_DesignSpec.md, 至少覆盖: (1) 四类来源(QuestSource: DAILY/WEEKLY/SPECIAL/HIDDEN)各自的刷新周期(UTC翻日/ISO翻周/事件触发/多阶段任务线)与槽位数(对应 QuestConfig 里的槽位常量); (2) QuestPool 内容池的接取判据与 QuestObjective 目标类型; (3) QuestRewards 的信用点曲线, 以及为何走独立 faucet 键 EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY 而非并入全局软上限(把 QuestRewards.java:15-24 / QuestConfig.java:9-24 注释里已写好的论证迁移进设计文档, 而不是只留在代码注释里); (4) QuestChain 任务链的多阶段状态机; (5) /quest 全部子命令(list/claim/turnin/refresh daily|weekly)与权限、以及 miningdim-quest.toml 的键表。同时在 docs/Economy_BalanceSheet_DesignSpec.md 里补一节引用该新文档并说明 quest_faucet 这条例外(目前该文件全文零处提及任务/quest), 并在 docs/TaskSpec_INDEX.md 或 docs/modules/README.md 里加一条到新设计文档的链接, 避免今后只能从 TaskSpec_Quest_WebUI_Panel.md 或源码注释里反推设计。

**[Major] [A-文档与代码不符] 步战独立护甲路径 standalone/wok-infantry-armor 不存在**

- 位置: 第 44 行
- 文档原文: - `standalone/wok-infantry-armor` 是 `WOK步战附属-独立护甲` 源码位置，不属于任何 WOK 本体模块。
- 代码实际: 仓库里 standalone/ 下只有 standalone/kivotos-armorer 一个子工程, modId 是 kivotos_armorer 不是 wok_infantry_armor, 且它是已跟踪入库的 (不是文档所说的未跟踪内容)。`git log --all -- standalone/wok-infantry-armor` 无任何提交, 该路径从未在本仓库存在过。同一错误路径在 docs/WOK_Repository_Module_Plan.md:97 与 docs/modules/RESOURCE_OWNERSHIP.md:64 各出现一次, 后者还把 modId 写成 wok_infantry_armor。
- 取证: standalone/kivotos-armorer/gradle.properties:6 `mod_id=kivotos_armorer`; standalone/kivotos-armorer/README.md:1-3 `# Kivotos Armorer` / 「从 World of Kivotos 主项目独立出来的纯护甲 Forge MOD」; `git ls-files standalone` 仅返回 standalone/kivotos-armorer/** (含 build.gradle、src/main/java/com/kivotos/armorer/ArmorerMod.java 等); `git log --oneline --all -- standalone/wok-infantry-armor` 输出为空。
- 建议改法: 三处统一改为 `standalone/kivotos-armorer`(modId `kivotos_armorer`,包根 `com.kivotos.armorer`),并先由仓库所有者确认它到底对应《WOK_Repository_Module_Plan.md》第14行所列的“WOK步战附属-独立护甲”产品线,还是从铸甲师(job/engineer)拆出的“WOK-本体护甲”独立版——kivotos-armorer 的 README 自述“从 World of Kivotos 主项目独立出来的纯护甲 MOD”且源码含 armor/ 与 shield/ 包,更像后者,两种归属结论会导致后续整理方向完全不同。同时把 WOK_Repository_Module_Plan.md:97 中“standalone 在根目录形成大量未跟踪内容”一句改为准确描述“standalone/kivotos-armorer 已跟踪入库”,并同步核对 docs/modules/README.md:61 等提到“WOK步战附属-独立护甲”但未写路径的位置是否也需要补充/更正为正确路径。

**[Major] [A-文档与代码不符] 卡牌 MOD 的 standalone/docs/outputs 三处路径均不存在**

- 位置: 第 45 行
- 文档原文: - `standalone/wok-cardgame` 是 `WOK-卡牌游戏独立MOD`，正式 modId 为 `wok_cardgame`，同样不进入本体模块登记。
- 代码实际: standalone/wok-cardgame 在工作树与 git 全历史中都不存在; RESOURCE_OWNERSHIP:68 另外声称「根目录的 `docs/cardgame` 与 `outputs/cardgame` 是历史遗留位置」, 这两个目录同样既不在工作树也不在历史中。整条卡牌 MOD 隔离叙述目前没有任何仓库内落点, 读者无法据此定位或迁移。
- 取证: `git log --oneline --all -- standalone/wok-cardgame` 输出为空; `git log --oneline --all -- docs/cardgame outputs/cardgame` 输出为空; `ls standalone/` 仅有 kivotos-armorer; docs/WOK_Repository_Module_Plan.md:15 与 docs/modules/RESOURCE_OWNERSHIP.md:67-68 复述同一批路径。
- 建议改法: 确认 `WOK-卡牌游戏独立MOD`(modId `wok_cardgame`)当前真实代码归属后,统一订正三处文档: docs/WOK_Repository_Module_Plan.md:15、docs/modules/INVENTORY.md:45、docs/modules/RESOURCE_OWNERSHIP.md:66-68(正文在第68行)。若该 MOD 代码确实不在本仓库,应把这几段改写为"`WOK-卡牌游戏独立MOD` 不在本仓库内,modId 为 `wok_cardgame`,仅通过 docs/modules/module-registry.json 的 excludedProducts 声明排除",并删除"源码目录为 standalone/wok-cardgame"以及"docs/cardgame"/"outputs/cardgame"历史遗留位置这类给出具体、可核实却查无实据的路径表述,避免读者按文档路径查找或迁移一个从未存在过的目录。

**[Major] [E-状态过期] 酿酒师未提交改动的保护状态早已作废**

- 位置: 第 50 行
- 文档原文: - `wok-job-brewer` 存在活动工作区改动，整理过程只登记所有权，不移动或重写其源码和资源。
- 代码实际: job/brewer 的 41 个 Java 文件全部已入库, 最近一次改动是 2026-08-19 的提交, 当前工作树干净, 不存在任何未提交的酿酒师改动。同一条过期约束还出现在 docs/modules/DEPENDENCY_DEBT.md:80(以此挂起 D011)与 docs/WOK_Repository_Module_Plan.md:98-99、191(以此把酿酒师排到迁移末位并禁止触碰), 三处合起来在无依据地冻结一个模块的整理与清债。
- 取证: `git ls-files src/main/java/com/miningdim/job/brewer | wc -l` = 41; `git log -1 --date=short -- src/main/java/com/miningdim/job/brewer` = `5641074d 2026-08-19 fix(job): 已移除的玩家实体不再驱动职业结算, 修死亡即崩服`; `git status --porcelain` 在本工作树无输出。
- 建议改法: 删除 docs/modules/INVENTORY.md:50 这条保护状态(或改写为反映当前无保留项的中性表述); 将 docs/modules/DEPENDENCY_DEBT.md:80 的「D011 涉及当前酿酒师活动改动，必须等该工作收口后再处理」按正常清债纪律排期或直接删除该行, 让 D011 回到与其余未清偿项相同的处理队列; 同步订正 docs/WOK_Repository_Module_Plan.md:98-99(现状问题描述)、:191(阶段 3 迁移顺序第 10 条)、:223(第一批明确不做清单)五处引用, 去掉"未提交/活动改动"的前提, 让酿酒师模块按常规排期正常纳入迁移与债务清偿计划; 若日后需要保留这段历史决策的来龙去脉, 建议改写为带日期的历史脚注(如"2026-08-23 曾因未提交改动暂缓, 已于 2026-08-19 前的提交中收口"), 而不是保留成读起来仍是"现状"的现在时态描述。

### `docs/modules/README.md`

**[Major] [D-覆盖缺口] 五份 miningdim-*.toml (quest/stacking/money-mending/case-opening/tarot) 服务端配置文件整份无任何文档**

- 位置: 第 13-40 行
- 文档原文: 模块总表逐行登记了 wok-quest / wok-stacking / wok-enchant / wok-case-opening / wok-job-tarot 等模块及其 `currentPaths`, 但该表(及全部 docs/)对这些模块各自的服务端配置文件只字未提。
- 代码实际: 代码共注册 13 个 SERVER/CLIENT 配置文件。按文件名在 docs/ 全量 grep 后, 以下五份整份零命中, 即服主拿到 world/serverconfig 下的 toml 完全没有文档可对照: miningdim-quest.toml(QuestConfig, 含任务槽位与 faucet 档位)、miningdim-stacking.toml(StackingConfig, 19 个键)、miningdim-money-mending.toml(MoneyMendingConfig, 直接决定一条 sink 的单价)、miningdim-case-opening.toml(CaseOpeningConfig)、miningdim-tarot.toml(TarotConfig, 含全部卡包出率与合成概率)。其余八份至少有一份文档提到过。
- 取证: 注册点逐条: src/main/java/com/miningdim/quest/QuestSystem.java:29 `registerConfig(ModConfig.Type.SERVER, QuestConfig.SPEC, "miningdim-quest.toml")`; src/main/java/com/miningdim/stacking/StackingSystem.java:63-65 `StackingConfig.SPEC, "miningdim-stacking.toml"`; src/main/java/com/miningdim/enchant/EnchantmentSystem.java:22-23 `MoneyMendingConfig.SPEC, "miningdim-money-mending.toml"`; src/main/java/com/miningdim/caseopening/CaseOpeningSystem.java:34-35 `CaseOpeningConfig.SPEC, "miningdim-case-opening.toml"`; src/main/java/com/miningdim/job/tarot/TarotSystem.java:76-78 `TarotConfig.SPEC, "miningdim-tarot.toml"`。docs/ 下对这五个文件名的 grep 结果均为 0 命中(对照: miningdim-brewer.toml 命中 2、miningdim-munitions.toml 命中 2、miningdim-server.toml 命中 1)。
- 建议改法: 在 docs/modules/README.md 模块总表后新增一张"配置文件登记表", 把13份 toml 逐条对到模块与说明文档(可考虑在 module-registry.json 的模块条目中补一个 configPaths 字段, 使其与 resourcePaths 同级受 verifyModuleRegistry 校验)。对 miningdim-quest.toml/miningdim-stacking.toml/miningdim-money-mending.toml/miningdim-case-opening.toml/miningdim-tarot.toml 这五份零文档的, 至少在各自模块目录(docs/modules/quest/README.md、docs/modules/stacking/README.md、docs/modules/enchant/README.md、docs/modules/case-opening/README.md、docs/modules/tarot/README.md)补一份键表, 体例照 docs/modules/chef/README.md 提到 miningdim-chef.toml 的写法, 重点覆盖 QuestConfig 的任务槽位与faucet档位、StackingConfig 的19个键、MoneyMendingConfig 的 priceMultiplier 定价倍率、CaseOpeningConfig 的开箱概率、TarotConfig 的卡包出率与合成概率。

**[Major] [A-文档与代码不符] mixin 包被写成只有一个类, 实际有 5 个加 compat 子包**

- 位置: 第 46 行
- 文档原文: `mixin` 归 `wok-core` 而不单列模块：当前只有 `MoveSpeedCheckMixin` 一个类，混入的是原版 `ServerGamePacketListenerImpl` 且读的是核心配置门面，属于核心装配的基础设施而非独立业务。
- 代码实际: com.miningdim.mixin 下已有 5 个 mixin 类, 外加 com.miningdim.mixin.compat 子包中的 TideOreFishMixin。其中 4 个 (ItemStackMiningDurabilityMixin / PlayerFoodExhaustionMixin / PlayerOreSoupStateMixin / VanillaOreFishMixin) 混入的不是 ServerGamePacketListenerImpl, 读的也不是核心配置门面, 而是直接调用渔夫模块实现, 正是 D036 这条运行期债务的全部触发点。
- 取证: src/main/resources/miningdim.mixins.json:7-13 `"mixins": ["MoveSpeedCheckMixin", "VanillaOreFishMixin", "PlayerFoodExhaustionMixin", "PlayerOreSoupStateMixin", "ItemStackMiningDurabilityMixin"]`; src/main/resources/miningdim.compat.mixins.json:4-8 `"package": "com.miningdim.mixin.compat" ... "mixins": ["TideOreFishMixin"]`; docs/modules/module-registry.json:1168 起 D036 的 evidence 正是这五个 mixin 文件; 目录实测 src/main/java/com/miningdim/mixin/ 含 5 个 .java 与 compat/ 子目录。
- 建议改法: 把该条改写为: `mixin` 归 `wok-core` 而不单列模块: 当前 `com.miningdim.mixin` 下有 MoveSpeedCheckMixin (混原版 ServerGamePacketListenerImpl, 读核心配置门面) 与 ItemStackMiningDurabilityMixin、PlayerFoodExhaustionMixin、PlayerOreSoupStateMixin、VanillaOreFishMixin 四个羹/钓鱼接缝, 另有 required=false 的 `com.miningdim.mixin.compat.TideOreFishMixin`; 后五个对渔夫模块的调用已按 D036 登记为边界例外, 收敛该债务前不再新增同向 mixin。

**[Major] [F-结构问题] 模块登记表登记 7 类资产却不含文档, 文档无所有权无校验**

- 位置: 第 54, 57 行
- 文档原文: L57「- 每个模块必须登记源码、资源、配置、注册 ID、存档数据、外部依赖和测试入口。」; L54「- 资源必须落进某个模块的 `resourcePaths`/`resourceNamePrefixes`，或显式登记进 `sharedResources`…」
- 代码实际: 治理规则枚举了每个模块必须登记的 7 类资产, 唯独没有「文档」。因此 57 份设计文档没有任何归属模块, 新增一份文档不需要登记、删掉一个模块也不会有人发现它的文档还留着。这与同仓对资源的强制归属形成鲜明反差: 资源少登记一条就构建失败, 文档一份都不登记也照常绿。
- 取证: docs/modules/module-registry.json 的 modules 数组共 26 个对象, 每个对象字段固定为 id / name / category / javaPackagePrefixes / currentPaths / resourcePaths / resourceNamePrefixes / dependencies / optionalIntegrations 共 9 项, 无任何文档字段(python json 解析实测)。build.gradle:253 `tasks.register('verifyModuleRegistry')`、build.gradle:423 `tasks.register('verifyModuleBoundaries')`、build.gradle:629-631 `tasks.named('check').configure { dependsOn ... }` —— 校验机制已存在且挂进 check, 只是没有一条规则覆盖 docs/。
- 建议改法: 给 module-registry.json 每个模块加 `designDocs` 数组(相对仓库根的文档路径), 并在 L57 的必登记清单里补上「设计文档」。扩展 verifyModuleRegistry: (1) 断言每条 designDocs 路径在磁盘存在; (2) 断言 docs/ 下每份 .md 恰好被一个模块认领或显式登记进新增的 sharedDocs(与现有 sharedResources 同构)。这样孤儿文档与死链文档在 `gradlew check` 阶段即暴露。

### `docs/modules/RESOURCE_OWNERSHIP.md`

**[Major] [B-文档互相打架] RESOURCE_OWNERSHIP.md 表格(第19行)声称 WOK-WebUI 拥有 assets/miningdim/web/,但登记表里该模块 resourcePaths/resourceNamePrefixes 均为空,唯一现存文件已登记给 WOK-开箱**

- 位置: 第 19 行
- 文档原文: | WOK-WebUI | `assets/miningdim/web/` 通用页面宿主与 WebUI 公共资源 |
- 代码实际: 本文第 5 行明写「所有权的唯一真源是 `module-registry.json`」、第 13 行又说「下表是登记表的可读渲染，字段以登记表为准」, 但登记表里 wok-webui 的 resourcePaths 与 resourceNamePrefixes 都是空数组; assets/miningdim/web/ 目录下当前唯一的文件 case-opening.html 明确登记给 wok-case-opening。照本行去新增 web 资源会被 verifyModuleBoundaries 判为无主而构建失败。
- 取证: docs/modules/module-registry.json:197-210 wok-webui 条目 `"resourcePaths": []`、`"resourceNamePrefixes": []`; 同文件 wok-case-opening 的 resourcePaths 含 `assets/miningdim/web/case-opening.html`; `ls src/main/resources/assets/miningdim/web/` 只返回 case-opening.html。
- 建议改法: 将 docs/modules/RESOURCE_OWNERSHIP.md 第19行改为:"| WOK-WebUI | 当前无独占资源文件;`assets/miningdim/web/` 下仅有开箱页(归 WOK-开箱),通用页面宿主落地时须先把目录/文件写进登记表 wok-webui 的 `resourcePaths` |",使其与 module-registry.json:208-209(wok-webui 的 resourcePaths、resourceNamePrefixes 均为空数组)和同文件714-718(该目录下唯一文件 case-opening.html 归属 wok-case-opening)保持一致。同时与本文第49行(而非原建议误引的第44行)"`assets/miningdim/web/index.html` 若后续落地为物理共享页面……"的前瞻性表述对齐,避免"已落地"和"若后续落地"两处对同一目录的时态矛盾。

**[Major] [D-覆盖缺口] 资源所有权校验漏掉整棵 src/generated/resources**

- 位置: 第 5 行
- 文档原文: `verifyModuleBoundaries` 会遍历 `src/main/resources` 下的每一个文件 ... 因此资源所有权不再是纯文档承诺——把贴图改名或新增一个无前缀的文件，构建会立刻红。
- 代码实际: src/generated/resources 也被挂进 main 资源源集, 会一起打进 JAR, 但校验器的资源根写死 src/main/resources, 完全不遍历它。按登记表现有规则回算, 该树 328 个已跟踪文件里有 80 个既不匹配任何 resourcePaths/resourceNamePrefixes 也不在 sharedResources 中 (如 bauxite_ore、chromium_ore、nickel_ore 等七矿方块状态与模型), 即近四分之一的随 JAR 交付资源处于无主且构建不会变红的状态。
- 取证: build.gradle:92 `sourceSets.main.resources { srcDir 'src/generated/resources' }`; build.gradle:432 `def resourceRootDir = layout.projectDirectory.dir('src/main/resources').asFile`; build.gradle:434 `def resourceFiles = fileTree('src/main/resources')`; 实测 `git ls-files src/generated/resources` 共 328 个文件, 按登记表规则重放有 80 个无主, 首条为 src/generated/resources/assets/miningdim/blockstates/bauxite_ore.json。
- 建议改法: 两条二选一并在文中写明：要么把该段改成「校验只覆盖 src/main/resources；src/generated/resources 由 datagen 产出，暂不纳入所有权校验，新增生成资源须人工归口」，要么先补齐 wok-power 缺失的矿石前缀（chromium_ore/nickel_ore/silver_ore/tin_ore/tungsten_ore 及其 deepslate_ 变体等，实测共 80 个文件无主），再把 verifyModuleBoundaries 的资源根从单一 src/main/resources 扩展为同时遍历 src/main/resources 与 src/generated/resources 两棵树后保留原断言。

**[Major] [A-文档与代码不符] 声称 artifacts/outputs/tmp 已被 Git 忽略，.gitignore 无此三条**

- 位置: 第 58 行
- 文档原文: - `artifacts/`、`outputs/`、`tmp/` 放本地生成物并由 Git 忽略。
- 代码实际: .gitignore 全文 60 行, 忽略的是 build/、.gradle/、out/、bin/、run/、runs/、run-data/、src/generated/*、IDE 配置、webui/node_modules 等, 没有 artifacts/、outputs/、tmp/ 任何一条。按本文的说法往这三个目录扔生成物, 会被 git status 全量列出并有被误提交的风险 —— 这正是 Plan 第 167 行「补齐 .gitignore」这一步尚未完成的表现。
- 取证: 仓库根 .gitignore 第 1-60 行全文无 artifacts、outputs、tmp 三个词 (`grep -n "standalone\|outputs\|tmp" .gitignore` 无输出); 其中 .gitignore:1-5 为 `# Gradle / 构建产物` 下的 build/、.gradle/、out/、bin/, .gitignore:52-60 为裸 javac 产物与外部 jar。
- 建议改法: 二选一：(a) 在根 .gitignore 中补上 `artifacts/`、`outputs/`、`tmp/` 三行，使其与文档承诺一致；或 (b) 将 RESOURCE_OWNERSHIP.md:58 改为「`artifacts/`、`outputs/`、`tmp/` 约定用于本地生成物；`.gitignore` 尚未覆盖它们，提交前需人工用 `git status`/`git check-ignore` 核实不要误带入版本控制」，如实反映当前状态。

### `docs/WOK_Repository_Module_Plan.md`

**[Minor] [E-状态过期] 推荐的 docs/modules 目录树与已落地布局不一致**

- 位置: 第 130-147 行
- 文档原文: docs/modules/\n  README.md             21 个模块的总登记表\n  ...\n  core/\n  experience/\n  combat/\n  mining/\n  economy/\n  webui/\n  market/\n  jobs/<job-name>/\n  champion/\n  marriage/\n  case-opening/\n  stacking/
- 代码实际: docs/modules/ 实际只有 chef/、experience/、farmer/ 三个子目录, 且两个职业文档直接放在 docs/modules/ 下而不是方案建议的 `jobs/<job-name>/` 一层。docs/modules/README.md 第 65 行已确立的实际政策是「其余模块在实际迁移分支中补充自己的详细注册 ID 清单」, 与这里预先列出十一个目录的写法不一致; 照方案建的新模块文档会落到与 chef/farmer 不同的层级。
- 取证: `ls docs/modules/` 返回 DEPENDENCY_DEBT.md、INVENTORY.md、README.md、RESOURCE_OWNERSHIP.md、module-registry.json 与 chef、experience、farmer 三个目录, 无 jobs/ 层; docs/modules/README.md:65 的索引也用 `experience/README.md`、`farmer/README.md` 这种一级路径。
- 建议改法: 把 docs/WOK_Repository_Module_Plan.md 第 130-147 行的目录树改成与现状一致的形态: docs/modules/<module-short-name>/README.md(职业模块直接用 chef/、farmer/ 这类短名, 不加 jobs/ 层), 并注明子目录随迁移分支逐个补齐, 不预先列出全部模块清单。

**[Minor] [B-文档互相打架] 方案中 wok-core 的代码清单漏掉 mixin 包**

- 位置: 第 23 行
- 文档原文: | WOK-核心模块 | `wok-core` | `core`、`config`、`network`、`registry`、`menu`、`effect`、`entry`、`error`、`testutil` | 生命周期、配置、公共网络、注册辅助、玩家公共数据、错误边界、公共接口与服务装配 |
- 代码实际: 登记表与 docs/modules/README.md 第 15 行给 wok-core 的 currentPaths 都是十项, 含 `mixin`; 方案这一行只写了九项, 少 mixin。README 第 46 行还专门解释了 mixin 为何归核心, 唯独方案表里查不到这个包, 照方案排核心模块范围会把 mixin 漏在无主状态。
- 取证: docs/modules/module-registry.json:16 起 wok-core 的 `javaPackagePrefixes` 含 `"com.miningdim.mixin"`、`currentPaths` 含 `"mixin"`; src/main/java/com/miningdim/mixin/ 目录真实存在, 内含 5 个 mixin 与 compat 子包。
- 建议改法: docs/WOK_Repository_Module_Plan.md 第 23 行第三列的 wok-core 清单补上 `mixin`, 使其与 docs/modules/module-registry.json 的 currentPaths 及 docs/modules/README.md 第 15 行保持十项一致(可在附近视情况顺带核对该文档其余章节是否有需要同步的类似遗漏, 但不在本条修复范围内一并展开)。

**[Minor] [A-文档与代码不符] 入口装配的 Subsystem 数量写成约三十个, 实为 38**

- 位置: 第 88 行
- 文档原文: - 根入口一次装配约三十个 `Subsystem`，已经具备逻辑模块雏形，但所有功能仍共享一个源码集和资源命名空间。
- 代码实际: MiningDim.registerSubsystems() 里实际有 38 条 subsystems.add, 且全部有效(无注释掉的行)。与「约三十个」差 8 个, 正好是方案第 3 章漏登的 store/power/quest/enchant/fisher 等新模块带来的增量。
- 取证: `grep -c "subsystems.add" src/main/java/com/miningdim/MiningDim.java` = 38; src/main/java/com/miningdim/MiningDim.java:76 `private void registerSubsystems() {`, 末条为 :194 `subsystems.add(new com.miningdim.client.webui.WebUiClientSubsystem());`。
- 建议改法: 改为「根入口一次装配近四十个 `Subsystem`(当期以 `MiningDim.registerSubsystems()` 为准)」, 不钉死一个会随模块增长漂移的具体数字。

### `docs/modules/INVENTORY.md`

**[Minor] [B-文档互相打架] 渔夫模块依赖列漏掉 wok-store**

- 位置: 第 28 行
- 文档原文: | `wok-job-fisher` | WOK-渔夫模块（2026-09-06） | 23 | 15 | `FishingSystem` | 核心、经济、厨师；Farmer's Delight、Tide 可选 |
- 代码实际: 登记表给 wok-job-fisher 声明的 dependencies 是 wok-core、wok-store、wok-economy、wok-job-chef 四项, 库存表只写了三项, 漏掉存储。全表其余 25 行的依赖列与登记表逐项一致, 只有这一行对不上; 而 INVENTORY 第 57 行又强调「`wok-store` 是经济、市场、开箱共用的单一世界级 SQLite 库」, 漏记渔夫会让人误以为它不落盘。
- 取证: docs/modules/module-registry.json:522-527 wok-job-fisher 的 `"dependencies": ["wok-core", "wok-store", "wok-economy", "wok-job-chef"]`。
- 建议改法: 将 docs/modules/INVENTORY.md 第28行依赖列由"核心、经济、厨师；Farmer's Delight、Tide 可选"改为"核心、存储、经济、厨师；Farmer's Delight、Tide 可选"; 同时把第57行"`wok-store` 是经济、市场、开箱共用的单一世界级 SQLite 库"中的共用方清单补上渔夫, 改为"是经济、市场、开箱、渔夫共用的单一世界级 SQLite 库", 与 module-registry.json:522-527 中 wok-job-fisher 的 dependencies 数组保持一致。

**[Minor] [B-文档互相打架] 库存基线自称单一日期, 渔夫行却另带一个更晚的日期**

- 位置: 第 3, 28 行
- 文档原文: L3 「计数是 2026-08-30 的实测基线，后续由模块边界校验保护代码所有权，数量变化本身不构成错误。」; L28 「| `wok-job-fisher` | WOK-渔夫模块（2026-09-06） | 23 | 15 | `FishingSystem` | 核心、经济、厨师；Farmer's Delight、Tide 可选 |」
- 代码实际: 全表 26 行中只有渔夫这一行在模块名里内嵌了日期, 且该日期(2026-09-06)晚于表头声明的统一基线日(2026-08-30)。读者无法判断: 是整张表已在 09-06 重测而表头未更新, 还是仅渔夫一行是后补且与其余 25 行不同源。L3 的「数量变化本身不构成错误」免责声明是按单一基线日写的, 混入第二个日期后该声明的适用范围变得不确定。
- 取证: docs/modules/INVENTORY.md:3 与 :28 原文如上。机械取证: `grep -n '（2026-\|(2026-' docs/modules/INVENTORY.md` 全文仅命中 :28 一行, 证明其余 25 行均无行内日期, 渔夫行是唯一例外。对照 docs/modules/README.md:39-40 的同一批模块行, 该表无任何行内日期。
- 建议改法: 二选一并明确写出: 若整表已在 2026-09-06 重测, 把 L3 的基线日改为 2026-09-06 并删掉 L28 模块名里的「（2026-09-06）」; 若只有渔夫行是后补, 则保留 L3 的 2026-08-30 并在表格下方加一行脚注「`wok-job-fisher` 于 2026-09-06 单独补测, 其余 25 行为 2026-08-30 基线」, 不要把日期塞进模块显示名。

**[Minor] [D-覆盖缺口] 开箱机制/概率/定价/保底已有权威设计章节，仅 /wokcase 命令入口与 miningdim-case-opening.toml 键名未被任何文档登记**

- 位置: 第 35 行
- 文档原文: | `wok-case-opening` | WOK-开箱模块 | 25 | 24 | `CaseOpeningSystem` | 核心、存储、经济、WebUI；TaCZ、SQLite 可选 |
- 代码实际: 开箱是完整的付费玩法(信用点/青辉石消耗、CS2 式抽取、TACZ 皮肤归属、独立 SQLite 表、专属客户端页面与命令), 但 docs/ 下没有任何设计规格: 全库 grep "caseopening" 只命中 Economy_SQLite_Migration_Plan(纯迁移视角)与 module-registry.json; 唯一带 Case 字样的 docs/CASE_ASSET_PROVENANCE.md 只讲资源授权与替换, 不含机制、概率、定价、保底。玩家入口命令 /wokcase 全库文档零命中, 配置文件 miningdim-case-opening.toml 也零命中。Economy_BalanceSheet 把"卡包/开箱"当作已知 sink 引用, 却无处可查它的真实出率与定价。
- 取证: src/main/java/com/miningdim/caseopening/CaseOpeningSystem.java:34-35 `ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CaseOpeningConfig.SPEC, "miningdim-case-opening.toml");`; src/main/java/com/miningdim/client/webui/WebUiClientSubsystem.java:53 `Commands.literal("wokcase").executes(this::runCaseCommand)`; src/main/java/com/miningdim/client/webui/WebUiClient.java:105-115 `openCaseScreen()`; 包内另有 CaseRoller.java / CaseWeights.java / CaseRarity.java / CaseTaczBridge.java / CaseEconomyOperations.java 等 19 个类 + store 子包
- 建议改法: 不需要新建整份设计规格文档(docs/服务器经济系统设计文档.md 第四章已完整覆盖箱子/钥匙货币口径、CS2 式掉率表、原子事务与防作弊、无保底声明、轮换下架，且数值已与 CaseOpeningConfig.java / CaseWeights.java 的默认值核对一致)。只需做两处补登记：1) 在服务器经济系统设计文档.md 第四章或新增 4.6 小节里补一段"玩家入口"，写明客户端命令 /wokcase(WebUiClientSubsystem.java:53-54)对应 openCaseScreen()，说明它如何接入平板 hub 路由；2) 仿照 Armorer/Brewer/Munitions/Power 等模块设计文档的既有先例，在同一章或专门的配置附录里列出 miningdim-case-opening.toml 的字段清单(enabled/creditCost/azureCost/weights.blue|purple|pink|red|gold/openCooldownTicks/ownershipEnforceIntervalTicks，对照 CaseOpeningConfig.java:23-38)。顺带可在 docs/CASE_ASSET_PROVENANCE.md 顶部加一行指回服务器经济系统设计文档.md 第四章，避免"Case 字样文档只讲资源授权"造成的误导。

### `docs/modules/README.md`

**[Minor] [B-文档互相打架] 渔夫被列为"职业"类型, 但代码无 FISHER 职业身份**

- 位置: 第 31 行
- 文档原文: | 职业 | WOK-渔夫模块 | `wok-job-fisher` | `job/fisher` |
- 代码实际: JobId 枚举只有八个常量(MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER), 没有 FISHER, 因此渔夫没有职业等级、没有经验轨道、没有职业门控。同仓两处文档也这么写: docs/modules/experience/README.md:22 `当前八个职业全部注册为全服经验轨道` 后面只列八条轨道; docs/Economy_BalanceSheet_DesignSpec.md 的脚注 `† = 渔夫模块当前只有图鉴/矿石鱼/鱼羹, 无职业身份与等级`。模块总表把它与八个真职业并排列在"职业"类型下, 会让人按九职业做规划。
- 取证: src/main/java/com/miningdim/job/JobId.java:22-32 `MINER("miner"), FARMER("farmer"), ENGINEER("engineer"), TAROT("tarot"), CHEF("chef"), AGENT("agent"), MUNITIONS("munitions"), BREWER("brewer");` 共八项无 FISHER; docs/modules/module-registry.json 中 wok-job-fisher 的 `"category": "job"`; docs/modules/experience/README.md:22 `当前八个职业全部注册为全服经验轨道：`
- 建议改法: 在 docs/modules/README.md:31 该行的模块名后加限定, 例如「WOK-渔夫模块(暂无职业身份/等级, 仅图鉴+矿石鱼+鱼羹)」; 或在「读表须知」小节追加一条: "wok-job-fisher 的 category=job 是业务归类, 代码侧 JobId 枚举(job/JobId.java)尚未登记 FISHER 常量, 无等级、无经验轨道(对照 experience/README.md 的八轨清单)、无卖鱼身份门(对照 Economy_BalanceSheet_DesignSpec.md 脚注与第142条)"。同时建议在 docs/modules/INVENTORY.md:28 的渔夫行同步补一句引用该脚注,避免只查登记入口两份文档(README/INVENTORY)时看不到这一差异。

**[Minor] [F-结构问题] chef 子 README 无任何入链, 成为孤儿文档**

- 位置: 第 65 行
- 文档原文: [WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板。其余模块已进入全量登记表和库存基线；后续每个模块在实际迁移分支中补充自己的详细注册 ID 清单。
- 代码实际: docs/modules/ 下实际有三个子 README(chef、experience、farmer), 但索引只登记了后两个。全仓 Markdown 与 JSON 中对 `chef/README.md` 或 `modules/chef` 零引用, 这份 50 行、含完整注册清单与配置分组的厨师模块文档因此无法从模块入口被发现。注: 其余 23 个模块没有子 README 是有意为之(本行已说明「后续每个模块在实际迁移分支中补充」), 不算缺口; 缺口只在 chef 这一份已存在却没登记。
- 取证: `ls docs/modules/` 返回 chef、experience、farmer 三个子目录; `grep -rn "modules/chef\|chef/README" --include=*.md --include=*.json .` 无任何命中; docs/modules/chef/README.md:1-5 为 `# WOK-厨师模块` / 模块键 `wok-job-chef` / 公开入口 `com.miningdim.job.chef.ChefModule`。
- 建议改法: 在 docs/modules/README.md 第65行「样板与基线」段补上 chef 的链接,例如改写为:「[WOK-全服经验模块](experience/README.md) 是全服基础契约，[WOK-农夫模块](farmer/README.md) 是首个采用该契约的职业模块详细模板，[WOK-厨师模块](chef/README.md) 是第二份落地的职业模块详细文档。其余模块已进入全量登记表和库存基线；后续每个模块在实际迁移分支中补充自己的详细注册 ID 清单。」或另起「已落地的模块详细文档」小节，把 experience、farmer、chef 三份并列登记，后续每补一份加一行，避免同类遗漏再次发生。

### `docs/modules/RESOURCE_OWNERSHIP.md`

**[Minor] [F-结构问题] docs/assets 惯例无校验, tarot 目录 6MB 概念图零引用**

- 位置: 第 57 行
- 文档原文: L57「- `docs/assets/<module>/` 放设计稿和经过挑选的说明预览。」
- 代码实际: 惯例只写在纸面, 无任何校验。实际 docs/assets/ 下两个模块目录: chef 被 docs/modules/chef/README.md:50 引用, tarot 的三张概念图(tarot_card_back_concept_v1.png 2.4MB、tarot_craft_table_concept.png 1.8MB、tarot_craft_ui_concept.png 1.6MB, 合计约 6MB)在全仓任何受控文件里零引用 —— 塔罗师的唯一规格 docs/TarotReader_Mod_DesignSpec.md 一次都没提到它们, 而该规格本身也是零入链孤儿。
- 取证: `grep -rn 'docs/assets' --include='*.md' .` 全仓只有两处命中: docs/modules/chef/README.md:50 与 docs/modules/RESOURCE_OWNERSHIP.md:57 本身, 无一处指向 tarot。对照 build.gradle:423 verifyModuleBoundaries 对 resourcePaths 的逐文件所有权扫描 —— src/ 下的资源少一条归属就构建失败, docs/assets/ 完全不在扫描范围内。
- 建议改法: 要么在 docs/TarotReader_Mod_DesignSpec.md 补上对这三张概念图的引用与用途说明(是定稿参考还是废弃草案), 要么随规格一起迁到归属模块目录下。同时把 docs/assets/<module>/ 纳入 verifyModuleRegistry 的校验: 断言每个子目录名对应一个存在的模块键, 且目录下每个文件至少被该模块的某份文档引用一次。

**[Minor] [A-文档与代码不符] dist 历史跟踪文件数写成 156, 实际 205**

- 位置: 第 59 行
- 文档原文: - `dist/` 的 156 个历史跟踪文件需逐个判断是源素材、文档预览还是发布产物；在完成审查前不批量删除。
- 代码实际: 当前 main(bd0c4588) 上 dist/ 下被 Git 跟踪的文件是 205 个, 比文档多 49 个。按 156 这个数去做逐个审查会漏掉近四分之一的待判定文件。
- 取证: `git ls-files dist | wc -l` 在 D:/Repo/_wt-docs (main bd0c4588) 上输出 205。
- 建议改法: 把「156 个」改为「两百余个(以 `git ls-files dist | wc -l` 当期实测为准)」, 避免钉死一个会随提交漂移的计数; 或在订正为 205 的同时注明统计所依据的提交号(如当前 bd0c4588)。

---

## 仓库根治理文档

### `README.md`

**[Critical] [A-文档与代码不符] core 契约层清单列出已删除的 IOfflineGenerator**

- 位置: 第 66-67, 72, 81 行
- 文档原文: L66-67 架构图: "MiningServices (服务定位器) · Subsystem · IInstanceManager · IMiningConfig / IMiningNetwork · IOfflineGenerator · IResetService · ISpawnService"; L72: "(IMiningConfig)(IMiningNetwork)(IOfflineGenerator)(IInstanceManager)"; L81 表格: "| 3 | `worldgen.WorldgenSystem` | 离线洞穴生成 + 注册两个 Codec | `IOfflineGenerator` |"
- 代码实际: com.miningdim.core 包下不存在 IOfflineGenerator.java(实测目录只有 BaseMaterial/Difficulty/GameTestConfigWatchGuard/GenState/IInstanceManager/IInstanceResetListener/IMiningConfig/IMiningNetwork/IResetService/ISpawnService/InstanceLimitException/InstanceState/MaterialPalette/MiningConstants/MiningServices/MobInstanceTag/ModDependencyDeclarationGameTests/RegionBox/RegionLayout/SeedUtil/Subsystem/VoxelOccupancy)。全库对 IOfflineGenerator 只剩一处注释提及, 且正是说它已被摘掉。离线洞穴生成管线整体下线, WorldgenSystem 现在只注册一个 BiomeSource Codec。
- 取证: src/main/java/com/miningdim/worldgen/WorldgenSystem.java:16-18 — "自定义 ChunkGenerator 与离线体素生成管线 (OfflineCaveGenerator/GenerationScheduler) 已下线 (F021/F032): dimension/mining.json 的 generator.type 现为 minecraft:noise, 按需生成, 本子系统不再持有生成器实例、不再注入 core.IOfflineGenerator、不再需要在停服时关闭生成线程池。" 另 WorldgenSystem.java:11-12 — "订阅 RegisterEvent (modBus), 把 MiningBiomeSource.CODEC 注册到 Registries.BIOME_SOURCE"(只有这一个 Codec)。
- 建议改法: L66-67 的 core 契约行删掉 IOfflineGenerator, 保留 MiningServices/Subsystem/IInstanceManager/IMiningConfig/IMiningNetwork/IResetService/ISpawnService/IInstanceResetListener; L72 的注入行改为 "(IMiningConfig)(IMiningNetwork)(—)(IInstanceManager)"; L81 行改为 "| 3 | `worldgen.WorldgenSystem` | 注册 MiningBiomeSource 的 BiomeSource Codec(维度 generator 走 minecraft:noise) | —(无 core 门面) |"。

**[Critical] [F-结构问题] 57 份 docs 文档无任何索引, 全局入口只点名 3 份**

- 位置: 第 7, 122 行
- 文档原文: L7「仓库模块总表见 [`docs/modules/README.md`]…完整整理方案见 [`docs/WOK_Repository_Module_Plan.md`]」; L122「矿区规格见 `docs/MiningDimension_Mod_DesignSpec.md`；WOK 全仓模块、资源和依赖治理见 `docs/modules/`。」
- 代码实际: docs/ 顶层平铺 57 份 .md, 目录内既无 README.md 也无 INDEX.md。全仓唯一的全局入口 README.md 只在散文里点名 3 份文档, 其中 MiningDimension 那条还是反引号纯文本、不是可点链接。新人按 README 进来, 剩下 54 份文档没有任何可发现路径; 文档量还在快速膨胀: git log --diff-filter=A 统计 2026-06 新增 19 份、07 新增 18 份、08 新增 28 份。
- 取证: `git ls-files docs/*.md | wc -l` = 57, `ls docs/ | grep -iE '^(readme|index)'` 零命中。docs/modules/README.md:5「机器可读登记表见 [`module-registry.json`]…全量现状见 [`INVENTORY.md`]，资源归属见 [`RESOURCE_OWNERSHIP.md`]，依赖债务见 [`DEPENDENCY_DEBT.md`]」—— 这是全仓唯一索引, 但它只管 docs/modules/ 同目录 4 份治理文件和 26 个代码模块, 零设计文档链接。对照 build.gradle:253 `tasks.register('verifyModuleRegistry')`: 仓库对代码模块已经建立了「登记表 + 机械校验」范式, 文档侧完全空白。
- 建议改法: 新建 docs/README.md 作为文档地图, 按「现役设计规格 / 任务单 / 审查报告 / 资产清单 / 玩家手册 / 归档件」六类分节, 每行给出 文档 + 一句话用途 + 归属模块键(与 module-registry.json 的 id 对齐) + 状态。README.md:7 与 L122 改为统一指向 docs/README.md, 并把 L122 的反引号文本改成 Markdown 链接。

**[Critical] [A-文档与代码不符] MiningVoxelLookup 静态 seam 段落引用零存在的类**

- 位置: 第 93 行
- 文档原文: "worldgen 的 `MiningChunkGenerator` 经 `worldgen.MiningVoxelLookup` 静态 seam 取冻结体素：集成层（`instance.InstanceSystem.onServerStarted`）把离线调度器的 `voxelsOf` 接进该 seam。"
- 代码实际: 全库 grep `MiningVoxelLookup` 在 src/ 下零命中, 该类不存在; `MiningChunkGenerator` 同样没有对应源文件, 只在 core/ore 包的几处陈旧注释里被提及。worldgen 包实际只有 MiningBiomeSource.java、UndergroundMiscFeatureGameTests.java、WorldgenSystem.java 三个文件。照此段落去找接线点会一无所获。
- 取证: src/main/java/com/miningdim/worldgen/ 目录实际内容仅 MiningBiomeSource.java / UndergroundMiscFeatureGameTests.java / WorldgenSystem.java; `grep -rn "MiningVoxelLookup" src/` 输出为空。旁证 src/main/java/com/miningdim/ore/OreSystem.java:60 — "原注释描述的\"由 GenerationScheduler 在工作线程预热、MiningChunkGenerator 主线程读缓存\"这条链已不存在"。
- 建议改法: 删掉 README.md:93 末尾"worldgen 的 `MiningChunkGenerator` 经 `worldgen.MiningVoxelLookup` 静态 seam 取冻结体素：集成层（`instance.InstanceSystem.onServerStarted`）把离线调度器的 `voxelsOf` 接进该 seam。"整句。若要保留 worldgen 说明，改写为："维度 generator 走 `minecraft:noise` 按需生成（见 `dimension/mining.json`），worldgen 子系统只负责把 `MiningBiomeSource.CODEC` 注册进 `Registries.BIOME_SOURCE`（经 `registry.ModRegistration` 单一真源，见 `ModRegistration.java:39`），自定义 ChunkGenerator 与离线体素生成管线已按 F021/F032 下线，不再有离线体素 seam（见 `worldgen/WorldgenSystem.java` 类注释与 `ore/OreSystem.java:60-62`）。"

### `THIRD-PARTY-NOTICES.md`

**[Critical] [D-覆盖缺口] 漏登记 GeckoLib —— 硬前置(mandatory=true, side=BOTH)的运行期第三方依赖**

- 位置: 第 43-47 行
- 文档原文: 第二节表格只列三项: "| TACZ（永恒枪械工坊：零） | 1.20.1-1.1.8-hotfix | `GPL3 / CC BY-NC-ND 4.0` | | Champions（冠军／强敌再续） | forge-1.20.1-2.1.10.2 | `lgpl-3.0` | | MCEF | forge-2.1.6-1.20.1 | `LGPL` |"; 第六节 L92 又自定纪律: "新增任何第三方依赖时，必须同步更新本文件"。
- 代码实际: GeckoLib 4.8.2 是 build.gradle 里唯一的 implementation 级第三方库, 并在 mods.toml 里被声明为 mandatory=true、side=BOTH 的硬前置 —— 比表中三项(全为 mandatory=false 或纯 compileOnly)的依赖强度更高, 却在本文件里一个字都没有。按本文件自己第六节的判定顺序应归入第二节(不内嵌、不由我方分发), 漏登记直接构成法务清单缺口。
- 取证: build.gradle:132-134 — "// 厨师双格料理工位与六档军火台均使用 GeckoLib 4 的方块实体骨骼渲染。// 正式包不内嵌该库，由 mods.toml 声明为 BOTH 硬前置并钉死 1.20.1 的 4.8.2 编译 API。 implementation fg.deobf(\"software.bernie.geckolib:geckolib-forge-${mc_version}:${geckolib_version}\")"; gradle.properties:23 — "geckolib_version=4.8.2"; src/main/resources/META-INF/mods.toml:37-42 — "[[dependencies.miningdim]] modId = \"geckolib\" mandatory = true versionRange = \"[4.8.2,4.9)\" ordering = \"AFTER\" side = \"BOTH\""。
- 建议改法: 在第二节补登 GeckoLib,并同步修正该节开头的限定措辞,避免制造新的表述不一致: 1) 先将第37-41行"以下 mod 在 `build.gradle` 中以 `compileOnly files(\"libs/...\")` 引入,    仅用于编译期获取 API 签名"改为涵盖"compileOnly 本地 jar"与"implementation 远程 Maven    依赖"两种引入方式的共同描述(判据统一为:是否随产物 jar 分发,而非具体 Gradle 配置名)。 2) 在表格首行补一行:    "| GeckoLib(geckolib-forge-1.20.1) | 4.8.2 | 许可字段按第六节纪律从该 jar 的    `META-INF/mods.toml` 或官方许可页读取后填入,不得凭记忆填 |"    落笔前先取证该 jar 实际的许可字段(GeckoLib 官方仓库/其 mods.toml license 字段),    不得沿用本发现中的猜测。 3) 在表下加注: "GeckoLib 以 `implementation fg.deobf(...)` 从 Cloudsmith Maven 仓库引入    (build.gradle:118-124、132-134),不经 jarJar/shade 进入产物 jar(见 build.gradle 内    jarJar 声明仅覆盖 sqlite-jdbc);同时在 `mods.toml`(第37-42行)中被声明为    `mandatory = true`、`side = \"BOTH\"` 的硬前置,玩家必须自行安装,强度高于本节其余    `mandatory=false` 或纯编译期依赖项。"

### `README.md`

**[Major] [C-实现状态标错] PENDING 称 resetGeneration 用进程内 Map 跟踪未落盘**

- 位置: 第 107 行
- 文档原文: "**`InstanceState` 持久化字段缺口**：`reset` 的 `resetGeneration`（NEW_SEED 派生第三维）当前进程内 `Map<Long,Integer>` 跟踪，未随实例落盘"
- 代码实际: resetGeneration 已作为全局计数器随 MiningSavedData 落盘(NBT 键 resetGeneration, save/load 双向都写全)。reset 包内不存在任何 Map<Long,Integer>。真正的缺口只是"没有 per-instance 粒度", 与"进程内 Map 跟踪、完全未落盘"是两回事, 照此描述去排查会找错地方。
- 取证: src/main/java/com/miningdim/persistence/MiningSavedData.java:35 — "private static final String K_RESET_GEN = \"resetGeneration\";"; :322 — "tag.putInt(K_RESET_GEN, resetGeneration);"; :358 — "data.resetGeneration = tag.getInt(K_RESET_GEN);"; :57-58 — "注: 12.5 实例字段表未把 per-instance resetGeneration 列入持久列, 故重置代数以全局计数器形式"。`grep -rn "Map<Long, *Integer>" src/main/java/com/miningdim/reset/` 输出为空。
- 建议改法: L107 前半句改为:"`resetGeneration` 已由 `persistence.MiningSavedData` 以**全局计数器**形式落盘（NBT 键 `resetGeneration`），缺口是 12.5 实例字段表未收 per-instance 粒度，NEW_SEED 派生第三维目前全实例共用同一代数"。

**[Major] [C-实现状态标错] danger 接线 (a) 已落地却仍列为未填的接线缺口**

- 位置: 第 109 行
- 文档原文: "**跨子系统 danger 接线**：… (a) `trap` 的 danger 门控需压力子系统经 `TrapSystem.setDangerSource(...)` 注入读取；… 二者均为已声明的接线缺口（非空壳），需在对应子系统稳定公开注入入口后补一行适配器。"
- 代码实际: (a) 已经补完: PressureSystem.register 末尾就在调 TrapSystem.get().setDangerSource(...), 并有 GameTest 用变异验证锁死(删掉注入该用例必挂)。(b) oreTerm 传 0 仍属实。把已完成的 (a) 和未完成的 (b) 并列成"二者均为缺口"会误导后来者去做已经做完的事。
- 取证: src/main/java/com/miningdim/pressure/PressureSystem.java:36 — "TrapSystem.get().setDangerSource((player, instanceId) -> {"; 同文件 :33-35 注释 — "注入 danger 读取适配器: 复活动态陷阱 (C3)。陷阱引擎据此 danger 门控岩浆/坍塌/身后苦力怕, 不再恒读 stub 的 0f。"; src/main/java/com/miningdim/pressure/PressureGameTests.java:126 — "删 PressureSystem.register 末尾的 setDangerSource 注入 -> 引擎保留 0f stub -> injectedDangerOf 恒 0 -> 本测试必挂。"(b) 侧仍成立: src/main/java/com/miningdim/pressure/MobPressureSystem.java:158 — "矿物富集度: 矿物子系统尚无 core 门面暴露富集度, 暂以 0 计入 oreTerm。"
- 建议改法: 将 README.md:109 该条改写为区分两个子项的现状,例如:"**跨子系统 danger 接线**：`trap` 的 danger 门控已由 `PressureSystem.register` 经 `TrapSystem.setDangerSource(...)` 注入完成(`PressureGameTests.registerInjectsDangerSourceRevivingDynamicTraps` 变异验证锁死:删注入该用例必挂)。**剩余缺口**：`pressure` 的 `oreTerm` 仍传 0(见 `MobPressureSystem.java:158-160`),待矿物子系统提供"局部富矿度"读取门面后补一行适配器。"

**[Major] [C-实现状态标错] 退役 region 磁盘物理回收已部分落地（RetiredRegionGc 已接入 reset 主循环并有 GameTest 锁定），且 PENDING 项中"reset 区块重建依赖 MiningChunkGenerator 按新 bitset 重填"的描述已随 D3 滑动重置方案彻底作废**

- 位置: 第 110 行
- 文档原文: "**物理区块删除**：`instance.InstanceManager.destroyInstance` 当前仅释放强加载 + 逻辑回收，物理区块文件删除待 `reset` 流程接入；`reset` 的区块重建依赖 `MiningChunkGenerator` 在下次区块加载时按新 bitset 重填。"
- 代码实际: reset 子系统已落地 RetiredRegionGc: 逐区块 ChunkStorage.write(pos, null) 把退役 region 从 .mca 扇区表里摘掉, 并有 RetiredRegionGcGameTests 实测锁死这条路径, 不是待办。后半句更是整条作废: 重置现在走 slideRegion 把实例整块滑到从未生成过的新坐标, 维度按 minecraft:noise 按需生成, 既没有 MiningChunkGenerator 也没有"按新 bitset 重填"这回事。
- 取证: src/main/java/com/miningdim/reset/RetiredRegionGc.java:10-11 — "退役 region 的磁盘回收 (滑动重置的已知代价, 本类是它的收尾)。"; 同文件 :21-22 — "只能逐区块清: ChunkStorage.write(pos, null) 会走到 RegionFile.clear(pos), 把该区块从 .mca 的扇区表里摘掉 (这条路径由 RetiredRegionGcGameTests 实测锁死, 不靠推断)。"; src/main/java/com/miningdim/reset/ResetJob.java:25-27 — "REGEN : 调 IInstanceManager.slideRegion 把实例整块滑到一块从未生成过的新坐标 (写回新 regionBox/seed 后直接置 READY —— 离线生成已下线, 维度走 minecraft:noise 按需生成)"。
- 建议改法: L110 改为："**退役 region 磁盘回收（部分完成）**：滑动重置遗弃的旧坐标区块由 `reset.RetiredRegionGc` 逐区块 `ChunkStorage.write(pos, null)` 回收（每 100 tick 一批，`RetiredRegionGcGameTests` 锁死）。**剩余缺口**：只清地形区块 `world/region/`，实体区块 `world/entities/` 未清（存储句柄在 `ServerLevel.entityManager` 私有字段上）；`instance.InstanceManager.destroyInstance` 走的仍是纯逻辑回收路径，未接入该 GC。"（此修复文本已核对与代码逐字吻合，原样采纳。）

**[Major] [C-实现状态标错] GUI PENDING 称"本期无 MenuProvider"，实际已有 16 个方块实体/内部类实现该接口且全部绕开 network.MiningNetwork.openGui**

- 位置: 第 112 行
- 文档原文: "**GUI**：`network.MiningNetwork.openGui` 因本期无 `MenuProvider` 按 C9 抛 `UnsupportedOperationException` 明确暴露缺失能力，待 GUI 子系统提供菜单后接入。"
- 代码实际: openGui 确实还在抛, 但"本期无 MenuProvider"已不成立: 至少 20 个方块实体/菜单类实现了 MenuProvider(brewer 酒窖与酿造台、chef 调味台、engineer 生产台、munitions 三台、tarot 制卡台与闪耀包、marriage 共享背包、power 的发电机/预热机/空分/提纯/储能/低温控制器等), 且 menu/ModMenus.java 已是共享 MenuType 注册中心。真实现状是各职业各自走 NetworkHooks.openScreen, 没人经 core 的 openGui 门面, 所以这条门面成了永久死路而非"待接入"。
- 取证: src/main/java/com/miningdim/menu/ModMenus.java:15-16 — "menu 类型注册中心 (JobFramework_Shared_Foundation_DesignSpec 第六章)。持共享 DeferredRegister<MenuType<?>>; 各职业在自己的 register 内经本类的工厂助手登记自己的 MenuType。"; 实现 MenuProvider 的文件含 job/brewer/cellar/WineCellarBlockEntity.java、job/chef/SeasoningTableBlockEntity.java、job/engineer/block/ProductionTableBlockEntity.java、job/munitions/block/MunitionsBenchBlockEntity.java、marriage/MarriageBackpackMenu.java、power/generator/GeneratorBlockEntity.java 等 20 个; 对照 src/main/java/com/miningdim/network/MiningNetwork.java:189-190 仍写着 "本期 (网络子系统) 不含菜单实现"。
- 建议改法: README.md:112 改为: "**`IMiningNetwork.openGui` 门面悬空**：`menu.ModMenus`(menu/ModMenus.java:15-16)已是跨职业共享的 MenuType 注册中心，酒窖/酿造台/调味台/生产台/军械三台(装配台、冲压机、军械台)/塔罗制卡台与闪耀卡包/婚姻共享背包/发电机/预热机/空分/提纯/储能/低温控制器等 16 个方块实体或菜单内部类已实现 `MenuProvider` 并各自直接调用 `NetworkHooks.openScreen`；全仓对 `network.MiningNetwork.openGui` 的调用点为 0，该方法仍按 C9 抛 `UnsupportedOperationException`(network/MiningNetwork.java:188-193)。待决：删掉这条 core 门面，或改造成统一转发入口收编现有 `NetworkHooks.openScreen` 调用点。"同时建议一并更新 network/MiningNetwork.java:189-190 处"本期 (网络子系统) 不含菜单实现"这句已过期的注释。

**[Major] [A-文档与代码不符] 平台约束仍称注册自定义 ChunkGenerator 的 Codec**

- 位置: 第 19 行
- 文档原文: "自定义 ChunkGenerator/BiomeSource 注册的是 `Codec`（非 MapCodec；MapCodec 化是 1.20.5+）。"
- 代码实际: 维度 JSON 的 generator.type 已是原版 minecraft:noise, 只有 biome_source 是自定义的 miningdim:mining_biome_source。本仓库不再有任何自定义 ChunkGenerator, 这条平台约束只对 BiomeSource 成立。
- 取证: src/main/resources/data/miningdim/dimension/mining.json:2-8 — "\"generator\": { \"type\": \"minecraft:noise\", \"settings\": \"miningdim:mining\", \"biome_source\": { \"type\": \"miningdim:mining_biome_source\" } }"; src/main/java/com/miningdim/worldgen/WorldgenSystem.java:16-17 — "自定义 ChunkGenerator 与离线体素生成管线 ... 已下线 (F021/F032): dimension/mining.json 的 generator.type 现为 minecraft:noise"。
- 建议改法: 将 README.md 第19行改为："自定义 BiomeSource 注册的是 `Codec`（非 MapCodec；MapCodec 化是 1.20.5+）；维度 generator 用原版 `minecraft:noise` + `miningdim:mining` noise settings，本仓库无自定义 ChunkGenerator（已下线，见 WorldgenSystem.java 注释 F021/F032）。"

**[Major] [E-状态过期] 构建步骤 1 让新人执行 `gradle wrapper` 重新生成 gradle-wrapper.jar，但该 jar 早已入库且此举会覆盖 properties 中的版本 pin 说明**

- 位置: 第 25-31 行
- 文档原文: "1. `gradle-wrapper.jar` 是二进制文件，无法由文本生成。首次克隆后，用本机已装的任意 Gradle 仅执行这一步生成 wrapper（之后一律用 wrapper）：\n\n   ```\n   gradle wrapper --gradle-version 8.1.1\n   ```\n\n   该命令补齐 `gradle/wrapper/gradle-wrapper.jar`，使 `gradle-wrapper.properties` 中 pin 的 8.1.1 生效。"
- 代码实际: gradle/wrapper/gradle-wrapper.jar 自初始提交起就在版本控制里(48966 字节), 克隆后直接可用, 这一步完全多余。更糟的是照做会有副作用: README L23 刚说本机装的可能是 Gradle 9, 用 Gradle 9 跑 gradle wrapper 会重写 gradle-wrapper.properties, 抹掉文件里"严禁升到 8.4+/9.x"的 pin 注释与 networkTimeout/validateDistributionUrl 设置。
- 取证: `git ls-files gradle/` 输出 "gradle/wrapper/gradle-wrapper.jar" 与 "gradle/wrapper/gradle-wrapper.properties"; `git log --oneline -- gradle/wrapper/gradle-wrapper.jar` 只有一条 "7d170feb chore: 初始化 Forge 1.20.1 矿山维度 mod 工程"。gradle/wrapper/gradle-wrapper.properties:3-5 — "# 工程强制 pin Gradle 8.1.1 (本机 Gradle 9 / Java 21 不可直接构建本工程). # ForgeGradle 6.x 与 1.20.1 工具链在 8.1.1 上验证稳定; 严禁升到 8.4+/9.x."
- 建议改法: 删除 README.md 第 25-31 行的步骤 1，将现步骤 2（第 33-45 行）提为步骤 1，并在"构建步骤"小节开头（第 23 行后）补充一句："`gradle/wrapper/` 已随仓库入库（gradle-wrapper.jar + 锁定 8.1.1 的 gradle-wrapper.properties），克隆后可直接使用 `gradlew`/`gradlew.bat`，**不要**运行 `gradle wrapper` 重新生成——该命令会用本机已装 Gradle（可能是本节提到的 Gradle 9）重写 gradle-wrapper.properties，抹掉文件内 `# 工程强制 pin Gradle 8.1.1...` 等注释以及 networkTimeout/validateDistributionUrl 配置。"

**[Major] [A-文档与代码不符] 构建产物文件名版本号过期，且指向不含内嵌 JDBC 驱动的 jar**

- 位置: 第 45 行
- 文档原文: "构建产物：`build/libs/miningdim-1.20.1-1.0.0.jar`（已 reobf）。"
- 代码实际: 两处错。一是版本号: archivesName 取 mod_id-mc_version、version 取 mod_version=1.0.33, 实际产物名是 miningdim-1.20.1-1.0.33.jar, 文档钉死的 1.0.0 已落后 33 个小版本。二是产物选错: 跳蚤市场的 sqlite-jdbc 驱动是经 jarJar 内嵌的, 普通 jar 里没有, 正式环境要的是 jarJar 产出并 reobf 过的 -all.jar; 照文档把普通 jar 丢进正式服, SQLite 会抛 \"No suitable driver found\"。
- 取证: gradle.properties:11 — "mod_version=1.0.33"; build.gradle:10 — "version = \"${mod_version}\""; build.gradle:13-14 — "base { archivesName = \"${mod_id}-${mc_version}\" }"; build.gradle:209-212 — "jarJar 生成的 -all.jar 同样必须重混淆后才能放进正式 Forge 客户端。… 把生产映射固定为打包终结任务, 避免部署脚本或人工只运行 jarJar 时产出不可加载的 JAR。"; build.gradle:173 — "jarJar(...): 把驱动内嵌进最终产物 jar 的 META-INF/jarjar, 正式服由 FML JarJar 机制加载 (生产路径)"。
- 建议改法: README.md 第 45 行改为："构建产物：`build/libs/miningdim-1.20.1-<mod_version>.jar`（版本号取 `gradle.properties` 的 `mod_version`，已 reobf）。**正式部署用的是 jarJar 产出的 `miningdim-1.20.1-<mod_version>-all.jar`**——只有它内嵌了 `META-INF/jarjar/` 下的 sqlite-jdbc 驱动，普通 jar 在正式服会因缺驱动导致跳蚤市场存储不可用（`No suitable driver found`）。" 可参照 docs/Ore_Fish_And_Soup.md:72 的实际产物记录（miningdim-1.20.1-1.0.33-all.jar）核对措辞一致性。

**[Major] [D-覆盖缺口] 常用任务表漏 gradlew runData：328 个入库生成资源的重跑步骤，在唯一全局入口文档中完全找不到（其他专项文档已各自把它当作强制步骤）**

- 位置: 第 51-58 行
- 文档原文: 常用任务表只有五行: "| `gradlew build` | … | `gradlew runClient` | … | `gradlew runServer` | … | `gradlew runGameTestServer` | … | `gradlew verifyModuleRegistry` | … | `gradlew verifyModuleBoundaries` | …"，无 datagen 任务。
- 代码实际: build.gradle 配了独立的 data run（输出目录 src/generated/resources），且该目录下 328 个文件是入库交付物、被 sourceSets 纳入主资源。改 datagen provider 后必须跑 runData 重新生成并提交，README 的"常用任务"表完全没提这条，新人无从知道该跑什么。
- 取证: build.gradle:70-75 — "data {\n            workingDirectory project.file('run-data')\n            args '--mod', mod_id, '--all',\n                    '--output', file('src/generated/resources/'),\n                    '--existing', file('src/main/resources/')\n        }"; build.gradle:97 — "sourceSets.main.resources { srcDir 'src/generated/resources' }"; `git ls-files src/generated | wc -l` = 328。
- 建议改法: 在 README.md 第 51-58 行的「常用任务」表中补一行： | `gradlew runData` | 运行 Forge DataGen，重新生成 `src/generated/resources/`（该目录 328 个文件已入库并被 `sourceSets.main.resources` 纳入主资源，见 build.gradle:70-75、92；改动 datagen provider 或相关模型/贴图后必须重新运行并把生成结果一并提交——`docs/Power_Cable_AssetChecklist.md:57,62` 等模块专项文档已将其列为必需步骤，但全局入口文档此前未提及） |

**[Major] [D-覆盖缺口] 首段功能清单漏掉电力/任务/附魔三个已落地的玩法模块（渔夫职业已被"职业体系"统称覆盖，不应计入）**

- 位置: 第 7 行
- 文档原文: "当前功能包括全服经验、矿区副本、职业体系、经济市场、精英战斗、开箱、社交及服务器支撑功能。"
- 代码实际: 模块登记表 26 个模块里, wok-power(电力)、wok-quest(任务)、wok-enchant(附魔)三个玩法模块与 wok-job-fisher(渔夫)职业均已登记且已在主类装配, 均未出现在 README 这句对外的功能概述里。读者(尤其新人与外部)会据此低估仓库范围。
- 取证: src/main/java/com/miningdim/MiningDim.java:113 — "subsystems.add(new com.miningdim.power.PowerSystem());"; :155 — "subsystems.add(new com.miningdim.job.fisher.FishingSystem());"; :185 — "subsystems.add(new com.miningdim.quest.QuestSystem());"; :188 — "subsystems.add(new com.miningdim.enchant.EnchantmentSystem());"。docs/modules/README.md 模块总表同时列有 "| 玩法 | WOK-电力模块 | `wok-power` | `power` |"、"| 玩法 | WOK-任务模块 | `wok-quest` | `quest` |"、"| 玩法 | WOK-附魔模块 | `wok-enchant` | `enchant` |"、"| 职业 | WOK-渔夫模块 | `wok-job-fisher` | `job/fisher` |"。
- 建议改法: L7 首句改为："当前功能包括全服经验、矿区副本、职业体系、经济市场、电力、任务、附魔、精英战斗、开箱、社交及服务器支撑功能。"（不建议按原提案把职业逐一列成"矿工/农夫/铸甲师/厨师/渔夫/酿酒师/塔罗师/军火商/特勤干员"，因为这会改变"职业体系"原有的统称粒度，超出本条发现实际证实的缺口范围；只需在"玩法"层级补上电力、任务、附魔三个词即可与经济市场/精英战斗/开箱/社交保持同一粒度。）

**[Major] [F-结构问题] 全仓无文档索引, 58 份文档中 22 份零入链**

- 位置: 第 7, 75, 93, 122, 134(对照 docs/ 下无 README.md) 行
- 文档原文: README.md 是全仓唯一全局入口, 但只链出五份文档: L7 「仓库模块总表见 [`docs/modules/README.md`]...完整整理方案见 [`docs/WOK_Repository_Module_Plan.md`]」, L93 「[`docs/modules/DEPENDENCY_DEBT.md`]」, L122 「矿区规格见 `docs/MiningDimension_Mod_DesignSpec.md`；WOK 全仓模块、资源和依赖治理见 `docs/`」, L134 「[THIRD-PARTY-NOTICES.md]」。docs/ 目录下不存在 README.md 或任何总索引。
- 代码实际: 全仓 58 份 .md 中有 22 份不被任何其它 .md 文件按文件名提及, 只能靠目录列表发现。其中包括: 分支协作.md(CLAUDE.md 指定的合并纪律唯一权威, 全仓零引用)、docs/TaskSpec_INDEX.md(它自己是索引, 却没有任何上游指向它)、三份职业主规格(Brewer/Chef/TarotReader_Mod_DesignSpec.md)、docs/Economy_SQLite_Migration_Plan.md、docs/WebUI_ServerPush_DesignSpec.md、docs/WebUI_ChineseIME_DesignSpec.md、docs/Champion_Effects_Guide.md、docs/CASE_ASSET_PROVENANCE.md, 以及七份枪匠文档。另有 13 份仅有单一入链。
- 取证: 文件系统取证: `ls docs/README.md` 返回 「No such file or directory」。README.md 全文 grep '\.md' 只有上述 5 行命中。`grep -rn 分支协作 --include='*.md' .` 只命中 ./分支协作.md:1 自身标题行「# 分支协作规范」, 零外部引用; 而 CLAUDE.md 规定「操作流程以仓库内 `分支协作.md` 为准」。docs/design_mindmap.md 全文 grep '\.md' 仅命中 :4 的 markmap 渲染命令, 不承担索引职责。入链图用脚本对全部 58 份 .md 两两做 basename 包含判定得出 22 份零入链。
- 建议改法: 新建 docs/README.md 作为文档总索引, 按域(经济/矿区/职业/枪匠/电力/WebUI/治理/TaskSpec)分节列出全部 docs/*.md 并一句话说明各自定位与状态; 在根 README.md L122 把「WOK 全仓模块、资源和依赖治理见 `docs/`」改为指向该索引, 并显式补上 [分支协作.md](分支协作.md) 与 [docs/TaskSpec_INDEX.md](docs/TaskSpec_INDEX.md) 两条链接。

**[Major] [A-文档与代码不符] 子系统装配表漏登记 rules.RulesSystem 一行，致 error/entry 两行序号与 MiningDim 自身编号注释错位（非全表错位，前 11 行核实无误）**

- 位置: 第 75-91 行
- 文档原文: L75 "List 顺序仍是门面注入顺序，见 `MiningDim` 类注释的硬约束。"; L79 "| 1 | `config.ConfigSystem` | ForgeConfigSpec (SERVER+CLIENT) + 16.7 校验 | `IMiningConfig` |"; L89-91 "| 11 | `economy.EconomySystem` ... | 12 | `error.ErrorSystem` ... | 13 | `entry.EntrySystem` ..."
- 代码实际: registerSubsystems() 的实际第一条是 store.MiningStoreSubsystem, config.ConfigSystem 排第 2; economy 与 error 之间还插了 rules.RulesSystem。表里从 1 到 13 每一行的序号都与真实注入位置对不上, 而 L75 又明确宣称这列序号就是注入顺序。
- 取证: src/main/java/com/miningdim/MiningDim.java:79 — "subsystems.add(new com.miningdim.store.MiningStoreSubsystem());"; :81 — "subsystems.add(new com.miningdim.config.ConfigSystem());"; :103 — "subsystems.add(new com.miningdim.rules.RulesSystem());"(位于 :101 economy.EconomySystem 与 :105 error.ErrorSystem 之间)。
- 建议改法: 不要删除整列或重写 L75 措辞(前 11 行经核实与 MiningDim.java 自身编号注释及实际注入顺序完全一致, 该断言仍成立)。只需在 README.md:89(economy 行)之后插入一行 "12 | `rules.RulesSystem` | 放置规则：矿山维度内放置白名单（R7，事件型，无对外门面） | —（事件型）", 对应 MiningDim.java:102-103 的 "// 12. 放置规则..." + rules.RulesSystem 注入; 并将现第 90 行 error.ErrorSystem 的序号由 12 改为 13(对应 MiningDim.java:104 "// 13. 边界兜底..."), 现第 91 行 entry.EntrySystem 的序号由 13 改为 14(对应 MiningDim.java:106 "// 14. 入场子系统...")。这样表格 1-14 行即与 MiningDim.java 自带的内联编号注释完全对齐, 无需触碰 L75 或其余行。

**[Major] [E-状态过期] 架构裁决引用已删除的 PlayerMiningCapability/PlayerMiningEvents**

- 位置: 第 99 行
- 文档原文: "`persistence` 包另有一套等价玩家 Capability（`PlayerMiningCapability`/`PlayerMiningEvents`），若同时挂载会重复 attach 能力并重复触发 `onPlayerLeave`/登录恢复（双重传送、双重引用计数）。故 `InstanceSystem` 只保留实例后端，不再注册该套玩家 Capability。"
- 代码实际: persistence 包现在只剩 MiningSavedData.java 一个文件, PlayerMiningCapability 与 PlayerMiningEvents 两个类在全仓 src/ 与 docs/ 下均零命中, 那套重复实现早已删除。裁决描述的"另有一套等价实现留在仓库"这一前提不再成立。
- 取证: src/main/java/com/miningdim/persistence/ 目录实际内容只有 MiningSavedData.java; `grep -rn "PlayerMiningCapability\|PlayerMiningEvents" src/ docs/` 输出为空。
- 建议改法: 把 README.md 第99行改写成完成态记录，例如："**玩家 Capability 与进入/离开/登录恢复**：以 `entry` 子系统为唯一权威（`EntrySystem` + `MiningCapabilities`，实现了 14.2 完整防虚空进入链路与 14.6 登录恢复，`reset` 子系统亦依赖其能力）。`persistence` 包曾有一套等价实现（`PlayerMiningCapability`/`PlayerMiningEvents`），因同时挂载会重复 attach 能力并重复触发 `onPlayerLeave`/登录恢复（双重传送、双重引用计数），已随 762b3f40 整体删除；该包现仅保留 `MiningSavedData`（实例注册表持久层），继续作为 InstanceManager 的后端。"

### `THIRD-PARTY-NOTICES.md`

**[Major] [D-覆盖缺口] 第二节漏掉 JEI / Jade / Farmer's Delight / flavor_immersed_daily 四项**

- 位置: 第 37-47, 92-94 行
- 文档原文: L37-41 第二节标题与引子: "## 二、编译期 API 依赖（不随产物分发）\n以下 mod 在 `build.gradle` 中以 `compileOnly files(\"libs/...\")` 引入，仅用于编译期获取 API 签名。"; L92 "新增任何第三方依赖时，必须同步更新本文件"。
- 代码实际: 第二节把口径窄化成"libs/ 下的本地 jar", 于是四个同样参与编译期或 dev 运行期、并且在 mods.toml 里正式声明为可选前置的第三方 mod 全部落在清单之外: JEI(common-api + forge-api)、Jade、Farmer's Delight、flavor_immersed_daily。按第六节自定的判定顺序(不进产物 jar 即归第二节), 它们都应登记。
- 取证: build.gradle:150-152 — "compileOnly fg.deobf(\"mezz.jei:jei-1.20.1-common-api:15.20.0.135\") / compileOnly fg.deobf(\"mezz.jei:jei-1.20.1-forge-api:15.20.0.135\") / compileOnly fg.deobf(\"maven.modrinth:jade:11.13.2+forge\")"; build.gradle:159-169 — "def farmersDelightDevJar = file(\"libs/FarmersDelight-1.20.1-1.3.2.jar\") … runtimeOnly fg.deobf(\"local:FarmersDelight-1.20.1:1.3.2\")" 与 "runtimeOnly fg.deobf(\"local:flavor_immersed_daily:1.1.0.3-forge-1.20.1\")"; 四者在 src/main/resources/META-INF/mods.toml:55-60、95-100、107-112、115-120 均有 `[[dependencies.miningdim]]` 声明。
- 建议改法: 把第二节引子（THIRD-PARTY-NOTICES.md:39-41）改为"以下 mod 在 `build.gradle` 中以 `compileOnly`（本地 `libs/` jar 或远程 Maven 坐标）或仅 dev `runtimeOnly` 引入"，并在表格（第45-47行后）补四行：JEI 15.20.0.135（common-api/forge-api，build.gradle:150-151，mods.toml:95-100）、Jade 11.13.2+forge（build.gradle:152，mods.toml:107-112）、Farmer's Delight 1.20.1-1.3.2（build.gradle:159-161，mods.toml:55-60）、flavor_immersed_daily 1.1.0.3-forge-1.20.1（build.gradle:166-168，mods.toml:115-120）；许可字段按第六节纪律逐个从各自发行 jar 的 `META-INF/mods.toml` 或官方页面读取，不得凭记忆填写。

### `分支协作.md`

**[Major] [E-状态过期] "当前活跃分支"三条全部已合入 main**

- 位置: 第 19-23 行
- 文档原文: "当前活跃分支（随时更新）：\n- `main` — 稳定线\n- `feat/market-admin-curate` — 跳蚤市场 V0 基准价 admin curate 后台（base_values 表 + OP 动作 + React 面板）\n- `feat/coherent-noise-carving` — 矿业维度 worldgen 连贯噪声雕刻\n- `docs/job-and-economy-specs` — 职业与经济设计 spec"
- 代码实际: 三条功能/文档分支全部已经合进 main（`git branch --merged main` 三者都在列），零条仍在开发；而仓库实际有 118 个本地分支、数十个未合入分支（feat/webui-*、feat/quest-*、feat/power-* 等）。新人照此表会去 checkout 早已合并的分支继续开发，或误以为 WebUI/电力/任务等在建工作不存在。
- 取证: 在 D:/Repo/Wok-Project 执行 `git branch --merged main` 的输出同时包含 "feat/market-admin-curate"、"feat/coherent-noise-carving"、"docs/job-and-economy-specs" 三行，即三者相对 main 已无独有提交；`git branch | wc -l` = 118。另外 feat/coherent-noise-carving 描述的"连贯噪声雕刻"与 worldgen 现状一致（维度已改 `minecraft:noise`，见 src/main/resources/data/miningdim/dimension/mining.json:3-4），也印证该分支工作已落地。
- 建议改法: 要么删掉这个注定过期的列表,改为一句"活跃分支以 `git branch -a` 与 GitHub 分支页为准,本文不维护快照";要么把三条换成当前真正未合入的分支(如 `feat/webui-frame-stats-hud`、`feat/webui-shared-texture-host`、`feat/economy-admin-set`、`feat/economy-set-command`,以及一批 `fix/*` 批次修复分支)并在条目旁标注最后更新日期。考虑到本条自 2026-06-20(见 `git blame -L 19,23 分支协作.md`)起从未更新过,建议直接删列表。

**[Major] [B-文档互相打架] 把 src/generated/ 整个列为不入库,与 .gitignore 的反排除规则及实际入库的328个生成资源文件相反**

- 位置: 第 84 行
- 文档原文: "`build/` `.gradle/` `run/` `runs/` `src/generated/`、IDE 配置（`.idea/`/`.vscode/`）、`.claude/`（Claude Code 本地工具配置）、`/libs/`…"（第五节标题为"不入库的东西（见 `.gitignore`）"）
- 代码实际: .gitignore 先忽略 src/generated/* 再用四条 ! 规则把 src/generated/resources/assets 与 data 整个反排除回来, 实际有 328 个生成资源文件在版本控制里。照本文的说法, 有人会把 datagen 产物当成脏文件从暂存区剔掉甚至删除, 直接丢失入库的 blockstates/models/recipes。本文第五节又自称"见 .gitignore", 两者当面打架。
- 取证: .gitignore:11-17 — "src/generated/*\n!src/generated/resources/\nsrc/generated/resources/*\n!src/generated/resources/assets/\n!src/generated/resources/data/\n!src/generated/resources/assets/**\n!src/generated/resources/data/**"; `git ls-files src/generated | wc -l` = 328, 样例 "src/generated/resources/assets/miningdim/blockstates/air_separation_unit.json"。另 build.gradle:97 — "sourceSets.main.resources { srcDir 'src/generated/resources' }"。
- 建议改法: 将分支协作.md 第84行的 `src/generated/` 改为"`src/generated/` 下除 `resources/assets/**` 与 `resources/data/**` 之外的部分(这两处 datagen 产物**是入库的**,见 `.gitignore` 第11-17行的 `!` 反排除规则;改动 datagen 相关代码后必须连同重新生成的 assets/data 结果一起提交,git ls-files 现存328个此类文件)"。

**[Major] [A-文档与代码不符] 新人自备 libs jar 的清单漏了 MCEF, 照做编不过**

- 位置: 第 86 行
- 文档原文: "外部 mod 依赖（TACZ/Champions）走 `compileOnly` 本地 jar，新人需自备 `libs/` 下对应 deobf jar 才能编译。"
- 代码实际: libs/ 实际需要三个 jar: tacz、champions、mcef。MCEF 同样是 `compileOnly files("libs/...")`，WebUI 客户端代码编译依赖它；新人只按文档准备 TACZ 与 Champions 两个 jar，`gradlew compileJava` 会直接失败在 MCEF 相关符号上。
- 取证: build.gradle:145-146 — "// MCEF 客户端前置 (浏览器/JCEF 外壳): compileOnly 仅取 API, 零 mixin/refmap, 不像 TACZ 会在 dev 崩。 compileOnly files(\"libs/mcef-forge-2.1.6-1.20.1.jar\")"; D:/Repo/Wok-Project/libs/ 实有 champions-forge-1.20.1-2.1.10.2.jar、mcef-forge-2.1.6-1.20.1.jar、tacz-1.20.1-1.1.8-hotfix.jar 三个文件。旁证 THIRD-PARTY-NOTICES.md:45-47 的第二节表也是三项, 与分支协作.md 的两项不一致。
- 建议改法: L86 改为: "外部 mod 依赖（TACZ / Champions / MCEF）走 `compileOnly` 本地 jar，新人需自备 `libs/` 下三个 deobf jar 才能编译：`tacz-1.20.1-1.1.8-hotfix.jar`、`champions-forge-1.20.1-2.1.10.2.jar`、`mcef-forge-2.1.6-1.20.1.jar`（版本以 `build.gradle` dependencies 段与 `THIRD-PARTY-NOTICES.md` 第二节为准）。"同时第 84 行 `.gitignore` 一节的括注"(TACZ/Champions 第三方 deobf 编译依赖 jar...)"一并补上 MCEF,避免同一遗漏在文档内出现两处。

**[Minor] [F-结构问题] 唯一的合并纪律文档在仓库根且零入链, 它指向的 docs 又无索引**

- 位置: 第 3 行
- 文档原文: L3「本仓库 `World-of-Kivotos/Wok-Project`(Minecraft Forge 1.20.1 mod, modid `miningdim`)的分支模型、提交规范与合并前硬门。新人入手先读本文 + 各子系统真源 spec(见 `docs/`)。」
- 代码实际: 这是全仓唯一记载分支模型、提交规范与合并前硬门(编译通过 + GameTest 全绿)的文档, 自称是新人入手第一份, 却放在仓库根、用中文文件名、且零入链 —— README.md 全文 0 次提及它(grep -c 分支协作 README.md = 0)。它把新人接着导向 `docs/`, 而 docs/ 恰恰没有索引, 导流链条在第二跳就断了。
- 取证: 仓库根只有三份 Markdown: README.md、THIRD-PARTY-NOTICES.md(README.md:134 有链接)、分支协作.md(零链接), 前两份英文名并被引用, 第三份中文名且无人引用。`git ls-files` 确认 CLAUDE.md 不在版本控制内, 因此不存在任何其他文件替它做导流。
- 建议改法: 在 README.md 的「构建步骤」之前加一节「先读这三份」, 明确列出 分支协作.md、docs/README.md(新建)、docs/modules/README.md 三条链接; 分支协作.md 改名为 CONTRIBUTING.md 或迁入 docs/ 并按命名规范改英文名, L3 的 `docs/` 改为指向新建的 docs/README.md。

---

## 婚姻 堆叠 职业框架 其它

### `dist/munitions-test/README_MUNITIONS_TEST.md`

**[Critical] [A-文档与代码不符] 测试路径完全没有供电步骤，军火台没电零产出**

- 位置: 第 19-36 行
- 文档原文: L21-28 快速测试命令只给了 `/give @p miningdim:munitions_bench`、四件套材料与 `/job set @p munitions 10`; L30-36 测试路径: "1. 放置军火台。2. 右键打开军火台。3. 依次放入底火、弹壳、弹头、发射药。4. 选择已解锁口径。5. 等待产线累积，输出缓冲会生成 TACZ 弹药。"
- 代码实际: 军火台是耗电机器: 内部有 FE 缓冲, 产线结算前先检查 `energy.hasAtLeast(feCostPerBatch(level))`, 不足直接不产; 每批电费 = 基础批发数 × FE_PER_RIFLE_EQUIVALENT_ROUND, L10(已解锁提炼)即 70 × 100,000 = 7,000,000 FE。README 的快速测试命令不含任何发电/供电手段, 照做会出现"料给满了、口径选了、等了半天一发不出"的现象, 测试者会直接把它当 bug 报回来。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:196-198: "/** 军械台的内部 FE 缓冲。电网只 push 进来, 产线结算时从这里扣。 */\n    private final MachineEnergyStorage energy = new MachineEnergyStorage(MunitionsConfig.BENCH_ENERGY_CAPACITY::get, this::setChanged);"; 同文件 L441 "if (!energy.hasAtLeast(MunitionsProduction.feCostPerBatch(level0))) {"; L676 "if (!energy.hasAtLeast(feCost)) {"; src/main/java/com/miningdim/job/munitions/MunitionsProduction.java:99-105 "public static int feCostPerBatch(int level) { int baseRounds = MunitionsLevels.isRefineUnlocked(level) ? REFINED_ROUNDS_PER_BATCH.get() : DIRECT_ROUNDS_PER_BATCH.get(); return Math.max(1, Math.multiplyExact(baseRounds, MunitionsConfig.FE_PER_RIFLE_EQUIVALENT_ROUND.get())); }"; MunitionsConfig.java:278 "fePerRifleEquivalentRound" 默认 100_000
- 建议改法: 在"建议前置"补一条"必需: 供电手段(军火台按 FE 计费; `/job set @p munitions 10` 对应 L10 已解锁提炼, 每批消耗 REFINED_ROUNDS_PER_BATCH(默认70) × FE_PER_RIFLE_EQUIVALENT_ROUND(默认100,000) = 7,000,000 FE, 见 MunitionsProduction.feCostPerBatch; 军火台内部 FE 缓冲上限 BENCH_ENERGY_CAPACITY 默认 32,000,000, 满电约可连续产出 4 批后需再充; 无电时开工帧与完工帧的 energy.hasAtLeast 闸门均会拒绝结算, 零产出)"；快速测试命令里补给一台发电机(或等效充能方式)与线缆并接通到军火台；测试路径在第 1 步"放置军火台"之后插入"1.5 用电缆连接发电机为军火台供电, 确认军火台 FE 缓冲开始上涨(可用 Jade/类似探针或等待观察产出验证)"，再进行后续放料与选口径步骤。

### `docs/CASE_ASSET_PROVENANCE.md`

**[Critical] [B-文档互相打架] 随 JAR 分发的 17 份 display JSON 是 TACZ 配置的派生拷贝，两份法务文档都没登记**

- 位置: 第 3-9 行
- 文档原文: L3-5: "本模块随 JAR 分发的 17 张枪械皮肤纹理和 8 个界面音效，均由 `tools/generate_case_assets.py` 确定性生成。它们没有复制、采样或提取 Counter-Strike 2、TaCZ 或其他游戏的图片与音频字节。" L7-9: "枪械 display JSON 会读取本地 `libs/tacz-1.20.1-1.1.8-hotfix.jar` 的显示配置，保留模型、动画、状态机和声音的资源引用，仅把枪身纹理指向本模组生成的原创 PNG。"
- 代码实际: 生成脚本是把 TACZ jar 内 `assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/<gun>_display.json` 整个 JSON 对象读出来, 只替换 texture 字段、删掉 lod, 其余(transform 缩放数值、muzzle_flash、hud/slot/animation/state_machine 引用)原样写进 src/main/resources 并随 JAR 分发。这不是"保留资源引用", 而是 TACZ 配置数据的逐字派生拷贝, 17 份共约 29KB。而 THIRD-PARTY-NOTICES.md 把 TACZ 归入"二、编译期 API 依赖(不随产物分发)", 并在第五节把"将 TACZ 代码 fork 或复制进本仓库"列为会与 GPL-3.0 产生实质冲突的情形之一 —— 两份文档对同一事实的描述直接打架, 且它第六节的判定顺序明确把"内嵌资源"算作进入产物 jar。
- 取证: tools/generate_case_assets.py:241-244: "def _read_base_display(archive: zipfile.ZipFile, gun_id: str) -> dict:\n    entry = f\"assets/tacz/custom/tacz_default_gun/assets/tacz/display/guns/{gun_id}_display.json\"\n    ...\n    return json.loads(_strip_json_comments(text))"; 同文件 L263-268: "display = _read_base_display(archive, gun_id)\n            display[\"texture\"] = f\"miningdim:gun/uv/case_{skin_id}\"\n            display.pop(\"lod\", None)\n            display_path.write_text(json.dumps(display, ensure_ascii=False, indent=2) + \"\\n\", ...)"; 产物 src/main/resources/assets/miningdim/custom/miningdim_cases/assets/miningdim/display/guns/case_arctic_grid_display.json:1-27 含 "model": "tacz:gun/m4a1_geo" 与整段 "transform": {"scale": {"thirdperson": [0.6,0.6,0.6], ...}}; THIRD-PARTY-NOTICES.md:39-41 "以下 mod 在 `build.gradle` 中以 `compileOnly files(\"libs/...\")` 引入，仅用于编译期获取 API 签名。其 jar **不入库**...**不内嵌**进我方产物"; 同文件 L82 "2. 将 TACZ 代码 fork 或复制进本仓库;"; 同文件 L92-94 "判定顺序为：先确认该依赖是否进入产物 jar（`jarJar` / shade / 内嵌资源 = 是）"
- 建议改法: CASE_ASSET_PROVENANCE.md 第一段改为："本模块随 JAR 分发的开箱资产分三类：(1) 17 张枪械皮肤 PNG 与 8 个界面音效 OGG，由 tools/generate_case_assets.py 确定性生成，未复制/采样/提取 CS2、TaCZ 或其他游戏的图片与音频字节；(2) 17 份 `display/guns/case_*_display.json` 与 1 份 gunpack.meta.json，由 TaCZ jar 内对应枪械的 display JSON 派生而来（仅替换 texture 字段、删除 lod，其余 transform/muzzle_flash/动画与状态机引用原样保留），随 JAR 分发；路径见 src/main/resources/assets/miningdim/custom/miningdim_cases/。" 并同步修订 THIRD-PARTY-NOTICES.md：把第(2)类明确登记到"一、随官方构建产物分发"一节（或在第二节的"不内嵌"表述旁加注例外），并在第五节 GPL 风险提示中补一条已发生事实与评估结论；同时核查第 45 行 TACZ 自声明的 "CC BY-NC-ND 4.0" 资产许可是否覆盖 display json——若覆盖，需在第五节之外单独评估"演绎并再分发"是否违反 ND 条款，这是比 GPL 组合作品更直接的风险点，不能只在 GPL 一条线上给结论。

### `docs/JobFramework_Shared_Foundation_DesignSpec.md`

**[Critical] [A-文档与代码不符] JobId 权威枚举写 5 个成员, 代码是 8 个且代码已明说文档滞后**

- 位置: 第 22 行
- 文档原文: L22: "`enum JobId { MINER, FARMER, ENGINEER, TAROT, CHEF }`(5 个)。**结婚不是 JobId**——它是系统...各 spec 此前互相矛盾的成员清单一律以此为准。" (章节标 DECIDED, 文档自称 JobId 的唯一真源)
- 代码实际: 代码 JobId 有 8 个成员: MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER, 且枚举类注释直接写着"框架 spec 第 2.1 节文字 (5 个) 滞后于 mindmap 与两份新 spec, 本枚举以 7 成员为准"再加 BREWER 共 8。本文档自称是 JobId 的唯一真源, 真源却比实现少 3 个成员, 且成员顺序是 JobSyncS2C 按 values() 读写的同序契约, 误按 5 成员实现会直接打断网络同步。
- 取证: src/main/java/com/miningdim/job/JobId.java:20-32: "public enum JobId {\n    MINER(\"miner\"), FARMER(\"farmer\"), ENGINEER(\"engineer\"), TAROT(\"tarot\"), CHEF(\"chef\"),\n    AGENT(\"agent\"), MUNITIONS(\"munitions\"), BREWER(\"brewer\");"; 同文件 L11-13: "框架 spec 第 2.1 节文字 (5 个) 滞后于 mindmap 与两份新 spec, 本枚举以 7 成员为准 ... 酿酒师 (BREWER) 即据此前向兼容性在原 7 职业尾部追加为第 8 个 ... 尾部追加以守 JobSyncS2C 按 values() 顺序读写的同序契约"
- 建议改法: docs/JobFramework_Shared_Foundation_DesignSpec.md:22 改为: "`enum JobId { MINER, FARMER, ENGINEER, TAROT, CHEF, AGENT, MUNITIONS, BREWER }`(8 个, 顺序即 JobSyncS2C 按 values() 读写的同序契约, **新增职业只能尾部追加**)。ENGINEER 的玩家可见名已改为"铸甲师",但稳定 id 保留 engineer 以免旧存档进度丢失。结婚不是 JobId;它是系统,数据走 MarriageRegistry(见结婚 spec),不进 JobProgress。各 spec 此前互相矛盾的成员清单一律以此为准。"

**[Critical] [A-文档与代码不符] 裁决"删除 persistence 整包"已危险过期,该包现在只剩矿区实例持久层 MiningSavedData**

- 位置: 第 47, 50 行
- 文档原文: L47: "| `persistence.PlayerMiningData`(+Provider/Events) | 等价的第二套 capability+attach+Clone,仅在 InstanceSystem 接线层被裁撤,类仍在 | **删除整包**(留着就会被误 attach...) |"; L50: "迁移步骤...(2) 删 `persistence` 死包 + 多维 grep 确认无引用"
- 代码实际: 文档点名要删的 persistence.PlayerMiningData 及其 Provider/Events 早已不存在。现在 com.miningdim.persistence 包里只剩 MiningSavedData.java —— 那是矿区实例注册表与全局计数器的持久层(挂矿山维度 DimensionDataStorage, 承载实例 Map/nextInstanceId/globalSeed/resetGeneration/region 位图), 与玩家 capability 无关。照文档"删整包"执行会直接删掉矿区实例持久化。另: 全库仍有一个同名类 pressure.PlayerMiningData, 但那是压力子系统的内存态纯数据, 不是 capability, 容易被这条过期裁决误伤。
- 取证: src/main/java/com/miningdim/persistence/ 目录只含 MiningSavedData.java; MiningSavedData.java:16-20: "实例注册表与全局计数器的持久层 (设计文档 12.5 第一层)。挂在矿山维度的 DimensionDataStorage ... 承载: 实例注册表 Map<Long,InstanceState>、持久自增主键 nextInstanceId、确定性种子源 globalSeed、累计重置代数 resetGeneration、region 占用网格位图。"; 全库 grep "PlayerMiningData" 命中仅在 com/miningdim/pressure/ (Danger.java:77 "private final Map<UUID, PlayerMiningData> byPlayer = new ConcurrentHashMap<>();", PlayerMiningData.java:17) 与 economy/PlayerAbuseState.java:12 的历史注释
- 建议改法: JobFramework_Shared_Foundation_DesignSpec.md 2.3 表格第二行整行改为已完成记录:"| `persistence.PlayerMiningData`(+Provider/Events) | **已删除(收敛完成)** | 现 `com.miningdim.persistence` 仅存 `MiningSavedData`(矿区实例注册表+全局计数器持久层,挂矿山维度 DimensionDataStorage,与玩家 capability 无关),**不得再删**。注意 `pressure.PlayerMiningData` 是同名的压力子系统内存态纯数据(非 capability),`economy/PlayerAbuseState.java:12` 提到的"Capability/PlayerMiningData"仅为历史设计引用注释,均勿与本行已裁撤的旧 capability 类混淆误伤。|";第50行迁移步骤第(2)步"删 `persistence` 死包 + 多维 grep 确认无引用"改标为已完成(DONE),并补一句"该步骤严禁再次执行:persistence 包内现仅存的 MiningSavedData 是矿区实例持久化的现役代码,并非待删死代码"。

### `docs/Marriage_System_DesignSpec.md`

**[Critical] [A-文档与代码不符] MarriageState 字段清单列出三个已被代码明确移除的错误字段(sharedInvLevel/teleportLevel/divorceCount),文档指导重新引入已修复的数据模型缺陷**

- 位置: 第 118 行
- 文档原文: L118: "- `MarriageState`(数据载体): `marriageId` / `partnerA`,`partnerB` UUID / `marriedSinceTick` / `sharedInvLevel` / `teleportLevel` / 共享背包 `NonNullList<ItemStack>` / `divorceCount` / 里程碑领取记录。"
- 代码实际: MarriageState 实际字段: marriageId / partnerA / partnerB / marriedSinceTick / sharedInv(NonNullList) / slotDepositors(按槽归属, 文档没写) / claimedMilestones / pendingDivorceInitiator / pendingDivorceFiledTick / pendingDivorceCost。文档列的 sharedInvLevel 与 teleportLevel 根本不存储 —— 两者由婚龄实时派生(MarriageTuning.backpackLevel/teleportLevel); divorceCount 也不在 MarriageState, 在 MarriageHistory。按文档设计会做出"等级存盘"这种与实现相反的模型。
- 取证: src/main/java/com/miningdim/marriage/MarriageState.java:33-76 字段全集: "private final long marriageId;" "private final UUID partnerA;" "private final UUID partnerB;" "private final long marriedSinceTick;" "private final NonNullList<ItemStack> sharedInv;" "private final UUID[] slotDepositors = new UUID[SHARED_INV_SIZE];" "private final Set<String> claimedMilestones = new HashSet<>();" "private UUID pendingDivorceInitiator;" "private long pendingDivorceFiledTick;" "private long pendingDivorceCost;"; src/main/java/com/miningdim/marriage/MarriageTuning.java:42-54 "public static int backpackLevel(long marriedSinceTick, long nowTick)" / "public static int teleportLevel(...) { return backpackLevel(...); }"; MarriageTuning.java:83-87 "remarryCooldownTicks(int divorceCount)" 的 divorceCount 由 MarriageHistory 提供
- 建议改法: 将 docs/Marriage_System_DesignSpec.md 第 118 行改为: "- `MarriageState`(数据载体): `marriageId` / `partnerA`,`partnerB` UUID / `marriedSinceTick` / 共享背包 `NonNullList<ItemStack>` + 按槽归属 `slotDepositors`(离婚清算用) / `claimedMilestones` 里程碑领取记录 / 离婚公示期三元组(`pendingDivorceInitiator`/`pendingDivorceFiledTick`/`pendingDivorceCost`)。**共享背包等级与传送等级不入库**,由 `MarriageTuning.backpackLevel`/`teleportLevel` 按婚龄(`marriedSinceTick`)实时派生; `divorceCount` 落 `MarriageHistory`(按玩家而非按关系持有,用于跨婚姻关系的再婚冷却计算),而非本类。" 并建议在该行末尾补一句提示性说明,引用 MarriageState.java 类注释中"原字段已删除"的历史背景,防止后续读者再次把这两类派生值当作应持久化字段实现。

**[Critical] [A-文档与代码不符] 典礼扣费写成 AbuseGuard.chargeItem 扣物品, 实际走 IEconomyService.tryCharge 扣信用点余额**

- 位置: 第 39 行
- 文档原文: L39: "- 典礼成本(反小号闸之一): **双方各付一半信用点**(走 `economy.AbuseGuard.chargeItem` 同款扣费销毁, 防单方刷)。"
- 代码实际: 实现走的是 IEconomyService.tryCharge(player, Currency.CREDIT, amount) 余额扣费, 不是 AbuseGuard.chargeItem。而 AbuseGuard.chargeItem 扣的是物理物品(默认钻石), 语义完全不同 —— 同仓 JobFramework 文档第三章已经把这条点名为"语义错位"并要求各 spec 改口, 但本文档未改。照本文档实现会扣错货币种类。
- 取证: src/main/java/com/miningdim/marriage/MarriageEngine.java:139-142: "if (halfA > 0 && !eco.tryCharge(a, Currency.CREDIT, halfA)) {" ... "if (halfB > 0 && !eco.tryCharge(b, Currency.CREDIT, halfB)) {"; 同包 MarriageEngine.java:75 买戒指亦是 "eco.tryCharge(player, Currency.CREDIT, cost)"; 对照 docs/JobFramework_Shared_Foundation_DesignSpec.md:59 "塔罗(十)、结婚(三)写\"复用 `chargeItem` 花信用点\"是**语义错位**"
- 建议改法: L39 改为: "典礼成本(反小号闸之一): **双方各付一半信用点**, 走 `IEconomyService.tryCharge(player, Currency.CREDIT, amount)` 事务性扣余额(任一方余额不足则将已扣一方通过 `grant` 退回, 整单回滚, 双方净额不变); 严禁用 `AbuseGuard.chargeItem`, 那是扣物理物品(默认钻石)。成本数值 config 键 `MARRIAGE_WEDDING_COST`(配置文件字段名 weddingCost, 默认 20000, 双方各付一半, 奇数余 1 由发起方多付)。"

### `docs/Minecraft实体堆叠_需求规格说明书.md`

**[Critical] [A-文档与代码不符] 堆叠适用范围: 文档说全实体类型, 实现只有四种农场动物白名单**

- 位置: 第 34, 95 行
- 文档原文: L34: "FR-1.1 (MUST) 合并条件须同时满足:同 entity type、同年龄段(成年/幼年)、同变体维度(羊毛颜色、苦力怕充能态、马花色等)、处于 merge.radius 范围内。" L95: "| AC-4 | 击杀 \"Zombie x100\",统计概率掉落 | 稀有掉落频次符合 100 次独立 roll 的期望(统计容差内),证明非 base×N |"
- 代码实际: 代码是硬编码白名单准入: 只有猪/鸡/羊/牛四种可堆叠, 其余一切 LivingEntity(含僵尸、苦力怕、马、狼)一律不合并。文档举例的羊毛颜色变体成立, 但苦力怕充能态、马花色两个例子和 AC-4 的僵尸场景在代码里根本不可能发生; 按文档执行 AC-4 会恒 FAIL, 且会被误判成"倍增逻辑坏了"。
- 取证: src/main/java/com/miningdim/stacking/StackMerge.java:59-60: "private static final Set<EntityType<?>> STACKABLE_TYPES =\n            Set.of(EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.COW);" 同文件 L26-27 注释: "候选准入 ({@link #canStack}): 白名单式 —— 只有 {@link #STACKABLE_TYPES} 四种 (决策 D1) 才可能参与堆叠, 其余 LivingEntity (含玩家/村民/盔甲架/僵尸骷髅/自研精英怪) 一律不合并"
- 建议改法: 在第一章前新增一节"适用范围(决策 D1)":明确堆叠候选准入为白名单式,当前仅 StackMerge.STACKABLE_TYPES 内的 PIG/CHICKEN/SHEEP/COW 四种低价值农场动物,其余一切 LivingEntity(含玩家/村民/盔甲架/僵尸骷髅/苦力怕/马/狼/自研精英怪)一律不参与合并;白名单硬编码在代码中,扩展须改代码走 code review,exclusions.blacklist 只是白名单内的二次过滤而非准入判据。FR-1.1 删除"苦力怕充能态、马花色"两个当前不可达的变体举例(StackMatchKey 中虽预留了 Creeper.isPowered()/AbstractHorse Variant 的变体签名代码,但因白名单判定在 canStack 中前置短路,这两段逻辑目前永远不会被触达,属未激活的预留扩展点,不应作为验收依据)。AC-4 的"Zombie x100"改为"Cow x100"或"Chicken x100"(与 AC-3 保持同类型口径)。AC-10 中"驯服狼"一例的断言说明改为"狼不在白名单内,在 canStack 判定顺序中先于 exclusions.tamed 检查即被挡",避免让人误以为该用例验证了驯服排除逻辑。

### `dist/munitions-test/README_MUNITIONS_TEST.md`

**[Major] [A-文档与代码不符] README_MUNITIONS_TEST.md 第38行(非第37行)"四件套每批各消耗1个"表述有误,发射药实际每批消耗2个**

- 位置: 第 37 行
- 文档原文: L37: "当前规则: 四件套每批各消耗 1 个。产量仍按文档等级走，L1-L5 每批步枪弹 40 发，L6+ 每批步枪弹 70 发；高阶口径按缩产系数减少发数。"
- 代码实际: 底火/弹壳/弹头每批各消耗 1 个, 但发射药每批消耗 2 个(配方对齐设计文档四章"7 铜 + 16 火药 -> 40 发": 底火 2 铜 + 弹壳 3 铜 + 弹头 2 铜 = 7 铜各 1 个, 发射药 8 火药 × 2 = 16 火药)。测试者按"各 1 个"备料会发现发射药先耗尽, 误判成配方 bug。同句后半段的 40/70 与缩产系数描述正确。
- 取证: src/main/java/com/miningdim/job/munitions/MunitionsConfig.java:252-259: "RECIPE_PRIMER_COST = b.comment(\"Primers consumed per production batch (1 primer = 2 copper)\").defineInRange(\"primerCost\", 1, 1, 64);\n        RECIPE_CASING_COST = ... .defineInRange(\"casingCost\", 1, 1, 64);\n        RECIPE_BULLET_HEAD_COST = ... .defineInRange(\"bulletHeadCost\", 1, 1, 64);\n        RECIPE_PROPELLANT_COST = b.comment(\"Propellant consumed per production batch (1 propellant = 8 gunpowder)\").defineInRange(\"propellantCost\", 2, 1, 64);"; 同文件 L248-249 注释 "合成表 底火=2铜/弹壳=3铜/弹头=2铜 (每批各 1, 共 7 铜) + 发射药=8火药 (每批 2, 共 16 火药)"
- 建议改法: 将 dist/munitions-test/README_MUNITIONS_TEST.md 第38行(不是第37行,该文件第37行为空行)前半句由"当前规则: 四件套每批各消耗 1 个。"改为"当前规则: 每批消耗 底火1 + 弹壳1 + 弹头1 + 发射药2(对齐设计文档四章 docs/Munitions_Job_DesignSpec.md:71 "7 铜 + 16 火药 -> 40 发"及 MunitionsConfig.java:248-259 的默认配置)。"后半句"产量仍按文档等级走,L1-L5 每批步枪弹 40 发,L6+ 每批步枪弹 70 发;高阶口径按缩产系数减少发数。"保持不变,该部分描述准确。

### `docs/JobFramework_Shared_Foundation_DesignSpec.md`

**[Major] [D-覆盖缺口] com.miningdim.job.fisher 是已注册模块却不占 JobId, 框架文档(及其继任的 design_mindmap.md)完全未覆盖这种形态**

- 位置: 第 22 行
- 文档原文: L22 (2.1 JobId 权威枚举) 与 L15 "本文档把这些收编为单一规格" —— 文档只区分"是 JobId 的职业"与"不是 JobId 的系统(结婚)", 没有第三类。
- 代码实际: com.miningdim.job.fisher 存在且在模块注册表里登记为独立模块 wok-job-fisher(名"渔夫模块"), 位置在 job 包下, 但 JobId 枚举没有 FISHER 成员, fisher 包内对 JobId 零引用 —— 即它是一个"挂在 job 包下、有模块身份、却不走 JobProgress 存档与 /job 命令"的第三类形态。框架文档作为职业地基的唯一真源没有定义这种形态, 后人新增类似模块时无规可循。
- 取证: src/main/java/com/miningdim/job/fisher/ 含 FishingSystem.java、FishingAssetGameTests.java 及 journal/ore/soup 三个子包; 对该目录 grep "JobId" 零命中, 对 JobId.java grep "FISHER" 零命中; docs/modules/module-registry.json 模块项: id="wok-job-fisher" name="WOK-渔夫模块" javaPackagePrefixes=["com.miningdim.job.fisher"]
- 建议改法: 在 docs/JobFramework_Shared_Foundation_DesignSpec.md 2.1 节末尾新增一段: "**不占 JobId 的 job 子包**: `com.miningdim.job.fisher`(模块 wok-job-fisher\"渔夫\", 见 docs/modules/module-registry.json 与 docs/Ore_Fish_And_Soup.md)位于 job 包下并有独立模块身份, 但不进 JobId、不持 JobProgress、不进 /job 命令——它是围绕钓鱼的内容模块而非等级职业(Ore_Fish_And_Soup.md 现状是'尚未新增渔夫职业身份', 但框架文档需要说明的是这类模块*即便未来仍不做职业化*时应遵循的规则)。新增此类模块须在本节登记, 并说明其不入 EnumMap 的理由, 避免与 JobId 成员混淆。" 由于 docs/design_mindmap.md 已被 JobId.java 注释指定为 JobId 成员清单的新真源、但同样零覆盖此形态, 建议该段落同步落一份到 design_mindmap.md 对应节, 而不是只改框架文档单侧。

**[Major] [C-实现状态标错] 跨职业日预算标 PENDING 待拍板,per-job 独立衰减已实现且代码注释已引用该方案为既定口径**

- 位置: 第 79 行
- 文档原文: L79: "- **跨职业日预算(PENDING,推荐 per-job)**: FF14 式同时持有全部职业,一天能否在 5 个职业各刷满?...**推荐 per-job 独立衰减**...需你拍板。"
- 代码实际: per-job 独立衰减已按推荐方案落地并成为唯一实现: 衰减游标 dailyXp 存在每职业各自的 JobProgress 里(JobData 的 EnumMap 每个 key 一份), JobXpCurve 的分段折算按传入的 currentDailyXp 计算, 不存在任何跨职业总额约束。文档挂 PENDING 会让读者以为还能改方案, 实际改就要动存档结构。
- 取证: src/main/java/com/miningdim/job/JobXpCurve.java:58-70: "// ---- 每日有效经验软上限衰减分段 (框架 spec 第四章 2000 系) ----\n    // 区间 [0,2000) x1.0 / [2000,2800) x0.4 / [2800,3400) x0.2 / [3400,3800) x0.08 / [3800,+inf) x0.02\n    private static final long DECAY_T1 = 2_000L; ..."; src/main/java/com/miningdim/job/JobData.java:24 "private final Map<JobId, JobProgress> progress = new EnumMap<>(JobId.class);" —— 每职业各持一份 dailyXp; JobXpCurve.java:74-77 "DAILY_SOFTCAP ... /job list 的\"当日剩余衰减额度\"以此为分母"
- 建议改法: 将 docs/JobFramework_Shared_Foundation_DesignSpec.md:79 由 "- **跨职业日预算(PENDING,推荐 per-job)**: ... 需你拍板。" 改为: "- **跨职业日预算(DECIDED = per-job 独立衰减)**: 已落地。每职业各持一份 dailyXp/dayStamp 游标(JobData 的 EnumMap<JobId,JobProgress> 每职业一份实例字段,见 JobProgress.java:26-27),分段衰减表 [0,2000)x1.0 / [2000,2800)x0.4 / [2800,3400)x0.2 / [3400,3800)x0.08 / [3800,+inf)x0.02(JobXpCurve.java:57-77)按该职业当日有效经验独立计算(JobXpCurve.applyDailyDecayExact 只吃单职业标量,不聚合其它职业),无任何全局总额上限。理由: 各职业竞争同一份真实在线时间,per-job 天然被封顶(JobProgress.java:85-86 注释已把此方案当既定口径引用)。若运营发现总产出过高,再追加"全职业总有效经验/日"硬顶,需另评估存档结构改动成本。"

**[Major] [E-状态过期] "编码前阻塞"与五步实现顺序已全部走完, 仍写成硬前置**

- 位置: 第 9, 155-160 行
- 文档原文: L9: "编码前阻塞: 本文档第二、三章(JobId/JobProgress capability 收敛迁移)是**所有职业的硬前置**,必须先于任何职业实现落地。"; L157: "1. **本框架第二章**(JobId/JobProgress + 三套存储收敛迁移)——所有职业硬前置,先做。"
- 代码实际: 第二章的 EnumMap 收敛、第三章的 IEconomyService、第四章的 LevelingService(落为 JobXpCurve)、第五/六章的共享 ModEffects 与 menu 脚手架、第九章的 /job 命令全部已落地; job 包下已有 9 个职业子包(agent/brewer/chef/engineer/farmer/fisher/miner/munitions/tarot)。"编码前阻塞"仍挂在文档元信息里, 会让读者以为整个职业线还没开工。
- 取证: src/main/java/com/miningdim/job/JobData.java:23-24: "public final class JobData {\n    private final Map<JobId, JobProgress> progress = new EnumMap<>(JobId.class);" 与 L17-19 "框架 spec 第 2.3 节已落地: 本数据作为 entry.MiningPlayerData 的内部委派 (单一权威玩家 capability 持本 EnumMap), 不再另挂第二套 job 玩家 capability"; src/main/java/com/miningdim/entry/IMiningPlayerData.java:138 "JobProgress jobProgress(JobId job);"; src/main/java/com/miningdim/job/ 下 9 个职业子包; src/main/java/com/miningdim/economy/IEconomyService.java:50-119 五个门面方法俱在
- 建议改法: L9 改为: "状态: 第二/三/四/五/六/九章均已落地(EnumMap 收敛见 job/JobData.java、IEconomyService 门面见 economy/IEconomyService.java、JobXpCurve/JobCommands/menu 脚手架/ModJobEffects 均已在库), 本文档转为地基的现状真源与回归依据; 仅第十一章"现有文档待补丁登记"表中列出的缺口(经济文档余额模型专章、FarmingXP superseded 注记等)仍未闭合。" 第十二章"实现顺序"整章改为"实现顺序(历史, 已全部完成)", 五条前逐一加 [x], 并在每条后补一句"已完成"及可选的代码定位(如第 1 条补 job/JobData.java、第 2 条补 economy/IEconomyService.java、第 4 条补 job/JobXpCurve.java 与 job/JobCommands.java), 只有第十一章表格里的真实缺口保留 [ ]。

**[Major] [E-状态过期] "代码现 0 个 Menu/Screen"已过期, menu 脚手架早已建成并被多方复用**

- 位置: 第 94 行
- 文档原文: L94: "工程师(生产台/校准)、塔罗(开包自选/合成)、厨师(调味台/小游戏)、结婚(共享背包/誓言墙)全部依赖它。代码现 0 个 Menu/Screen(`MiningNetwork.openGui` 直接 throw)。把工程师 10.5 的清单提升为**共享规格**"
- 代码实际: com.miningdim.menu 已建成 4 个类(ModMenus/AbstractMiningMenu/AbstractMiningScreen/MenuValidity), 含本章第三点要求的"无 BlockPos 的远程 menu 工厂变体" remoteMenuType, 并已被结婚共享背包与特勤扫描面板实际复用。括号里的 openGui 仍 throw 属实, 但它早已不是唯一入口(实际走 NetworkHooks.openScreen), 整句"0 个 Menu/Screen"是错的。
- 取证: src/main/java/com/miningdim/menu/ 含 ModMenus.java、AbstractMiningMenu.java、AbstractMiningScreen.java、MenuValidity.java; ModMenus.java:67 "public static <T extends AbstractMiningMenu> MenuType<T> remoteMenuType(RemoteMenuFactory<T> factory)" 与 L21 "remoteMenuType: 非方块 menu (戒指远程开共享背包) —— extraData 无 BlockPos"; src/main/java/com/miningdim/marriage/MarriageRegistration.java:21-23 "ModMenus.MENUS.register(\"marriage_backpack\", () -> ModMenus.remoteMenuType(MarriageBackpackMenu::new));"; src/main/java/com/miningdim/network/MiningNetwork.java:188-192 openGui 仍 throw
- 建议改法: L94 第二句改为:"共享脚手架已建成于 `com.miningdim.menu`(ModMenus / AbstractMiningMenu / AbstractMiningScreen / MenuValidity),含方块 menu 与远程 menu(`remoteMenuType`,无 BlockPos)两种工厂;结婚共享背包(MarriageRegistration.java:22-23)与塔罗开包自选(TarotRegistry.java:84-85)均已复用 remoteMenuType,工程师/酿酒师/厨师/军械等已落地各自的 AbstractMiningMenu/Screen 子类。`MiningNetwork.openGui` 仍是 throw 的历史残留(其注释已说明真实开窗走 NetworkHooks.openScreen/MenuScreens),该方法应择机清理或删除以消除误导。"

### `docs/Marriage_System_DesignSpec.md`

**[Major] [D-覆盖缺口] 文档全篇无命令章节与 WebUI 面板, 但两者都已是主要入口**

- 位置: 第 198-209 行
- 文档原文: L198-209 第十三章"实现期工作分解"共 7 项, 从 MarriageRegistry 到候选功能, 无任何一项提及命令树或 WebUI 面板; 全文亦无 /marriage 字样。
- 代码实际: 实际交互入口有两套, 文档一套都没写: (1) /marriage 命令树 9 个子命令(buyring/propose/accept/reject/withdraw/wed/divorce/divorce cancel/divorce confirm), 是买戒指、求婚、办典礼、离婚的唯一入口; (2) MarriageWebUiActions 781 行 + MarriageWebUiGameTests 1013 行的婚姻面板数据通道。文档描述的入口只有"蹲下右键戒指"与"长按戒指"两个物品交互, 覆盖不到实际功能的一半。
- 取证: src/main/java/com/miningdim/marriage/MarriageCommands.java:40-62: "LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(\"marriage\")\n                .then(Commands.literal(\"buyring\")...\n                .then(Commands.literal(\"propose\")...\n                .then(Commands.literal(\"accept\")...\n                .then(Commands.literal(\"reject\")...\n                .then(Commands.literal(\"withdraw\")..."; src/main/java/com/miningdim/marriage/MarriageWebUiActions.java (781 行) 与 MarriageWebUiGameTests.java (1013 行); MarriageSystem.java:62-67 注释 "面板拿到的就是本子系统自己那张表 ... A 用 /marriage propose 求的婚 B 在面板上永远看不见"
- 建议改法: 新增"第十四章 交互入口": 列出 /marriage 命令树 9 个子命令(buyring/propose/accept/reject/withdraw/wed/divorce/divorce cancel/divorce confirm)及各自校验与失败回执; 说明 MarriageWebUiActions 的 7 条 marriage.* action(state/buyRing/propose/respond/wed/divorce/sharedInv)与命令共享同一份 MarriageProposals/MarriageBackpackSessions 实例(MarriageSystem.java 由构造/静态注入下发, 实例同一性是接线正确性的唯一判据, 走错路径的后果——命令行求的婚在面板上看不见、离婚强制关窗关不到真实会话——已在 MarriageSystem.java 接线校验缝注释与配套 GameTest 中有明确说明, 应原样引用作为架构不变量); 并注明戒指物品交互仅覆盖"蹲下右键开共享背包"与"右键(不潜行)起传送蓄力"两项, 其余全部动作(买戒指/求婚/接受/拒绝/撤回/办典礼/离婚三段)均只能经命令或面板发起。

**[Major] [D-覆盖缺口] 婚姻系统九条玩家命令在设计文档里零登记**

- 位置: 第 24-33 行
- 文档原文: 第二章"核心流程总览 (DECIDED)"通篇用"服务端把双方戒指 NBT 从订婚改写为结婚"这类内部动作描述流程(L40), 全文 grep "命令" / "指令" / "buyring" / "propose" 命中 0 次, 没有任何一处写玩家用什么指令触发订婚/求婚/接受/离婚。
- 代码实际: MarriageCommands 注册了完整的 /marriage 命令树共九条子命令: buyring / propose / accept / reject / withdraw / wed / divorce / cancel / confirm, 这是婚姻系统当前唯一的玩家触发入口(MarriagePage.tsx 只做展示)。玩家可见命令没有任何文档, 运营与客服无从查证; 反倒是配置文件的注释里写了命令名。
- 取证: src/main/java/com/miningdim/marriage/MarriageCommands.java:46-67 依次 `Commands.literal("marriage")` / `"buyring"` / `"propose"` / `"accept"` / `"reject"` / `"withdraw"` / `"wed"` / `"divorce"` / `"cancel"` / `"confirm"`; src/main/java/com/miningdim/config/MiningServerConfig.java:267 `MARRIAGE_ENGAGEMENT_COST = b.comment("Credit cost to buy an engagement ring (/marriage buyring)")`
- 建议改法: 在 Marriage_System_DesignSpec.md 第二章后新增"二之二、玩家命令表"一节,依据 MarriageCommands.java:46-69 的实际注册结构逐条列出: `/marriage buyring`(扣 MARRIAGE_ENGAGEMENT_COST 买订婚戒)、`/marriage propose <player>`、`/marriage accept <proposer>`、`/marriage reject <proposer>`、`/marriage withdraw`、`/marriage wed <partner>`、`/marriage divorce`(顶层可执行,提交离婚)及其嵌套子命令 `/marriage divorce cancel`(公示期内撤回)与 `/marriage divorce confirm`(配偶提前确认生效);注明 divorce/cancel/confirm 是父子层级关系而非九条并列子命令,并标注各自的参数类型(EntityArgument.player)、成本来源(config 键名)与前置状态依赖,真源指向 MarriageCommands.java:46-69。

**[Major] [C-实现状态标错] "预约场地 SavedData"标 DECIDED 但零实现, 实际流程是 propose/accept/wed**

- 位置: 第 27, 38 行
- 文档原文: L27 核心流程图: "买订婚戒指(信用点) -> 预约场地 -> 双方到场办典礼(各付一半结婚成本)"; L38: "- 预约场地: `SavedData` 登记场地坐标 + 时段, 典礼期间占用。" (整个第三章标 DECIDED)
- 代码实际: marriage 包全 19 个类无任何场地/预约/venue 相关代码, 也没有登记场地坐标的 SavedData。真实流程是三段命令: /marriage propose <player> 登记意向 -> /marriage accept <player> 接受 -> /marriage wed <player> 双方在场办典礼, 意向表 MarriageProposals 是不持久化的瞬态表。按文档实现会去找一个不存在的模块。
- 取证: src/main/java/com/miningdim/marriage/MarriageCommands.java:22-27: " *  - /marriage buyring          买一枚订婚戒指 (tryCharge engagementCost)\n *  - /marriage propose <player>  向对方表达订婚意向 (登记意向)\n *  - /marriage accept <player>   接受对方的求婚 (对方须先 propose 你)\n ...\n *  - /marriage wed <player>      双方在场办典礼"; src/main/java/com/miningdim/marriage/MarriageRegistration.java:12-13 明确只登记共享背包 MenuType, 无场地对象; 对 marriage 包全文 grep "venue|场地|预约" 零命中
- 建议改法: L27 流程图改为 "买订婚戒指(信用点) -> /marriage propose + accept 确立意向 -> 双方在场 /marriage wed 办典礼(各付一半结婚成本)"。L38 整条改为 "场地预约: DEFERRED(未实现)。当前典礼不绑定场地, 以“双方在场 + 意向已接受”为唯一前置; 意向表 MarriageProposals 为瞬态不持久化, 登出即作废。"

**[Major] [E-状态过期] 婚姻系统 8 个 MARRIAGE_* config 键机制均已落地读取, 但 escrow 公示期具体数值代码自身仍标 PENDING, 第十二章第 1 条与 L39/L80/L91 状态标记整体过期(需保留一条例外, 不可全删)**

- 位置: 第 39, 80, 91, 188-194 行
- 文档原文: L80: "- T / CD / 各级数值进 config(PENDING 标定)。"; L91: "- 数值(冷却天数/成本/公示期)进 config(PENDING)。"; L190: "1. 各项 config 数值标定: 结婚成本/离婚成本/再婚冷却天数/escrow 公示期/共享背包各级容量与解锁婚龄/传送 T 与 CD 与各级。"
- 代码实际: 这些数值全部已在 MiningServerConfig 落地并被 MarriageTuning 实时读取: MARRIAGE_BACKPACK_UNLOCK_DAYS / MARRIAGE_BACKPACK_SLOTS / MARRIAGE_TELEPORT_CHARGE_SECONDS / MARRIAGE_TELEPORT_COOLDOWN_SECONDS / MARRIAGE_REMARRY_COOLDOWN_DAYS / MARRIAGE_DIVORCE_ESCROW_HOURS / MARRIAGE_WEDDING_COST。婚龄口径也已定(TICKS_PER_DAY = 20*86400 服务器运行 tick)。第十二章第 1 条挂着 PENDING 会让人以为还要重新标定。
- 取证: src/main/java/com/miningdim/marriage/MarriageTuning.java:44 "MiningServerConfig.MARRIAGE_BACKPACK_UNLOCK_DAYS.get()"; L58 "MiningServerConfig.MARRIAGE_BACKPACK_SLOTS.get()"; L63 "MARRIAGE_TELEPORT_CHARGE_SECONDS"; L69 "MARRIAGE_TELEPORT_COOLDOWN_SECONDS"; L84 "MARRIAGE_REMARRY_COOLDOWN_DAYS"; L94 "MARRIAGE_DIVORCE_ESCROW_HOURS"; src/main/java/com/miningdim/marriage/MarriageCommands.java:162 "long totalCost = MiningServerConfig.MARRIAGE_WEDDING_COST.get();"; MarriageTuning.java:28-32 "TICKS_PER_DAY = 20L * 86400L" / "TICKS_PER_HOUR = 20L * 3600L"
- 建议改法: L39/L80/L91 的 "(PENDING 标定)" 改为 "(已接入 config, 见第九章后新增的键位对照表)"; 第十二章第 1 条不应整条删除, 应改写为两部分: (a) 明确"结婚成本/离婚成本/再婚冷却天数/共享背包容量与解锁婚龄(两张列表)/传送蓄力与冷却(两张列表)"共 8 个 MARRIAGE_* 键的机制与默认值已落地(非 7 个: MARRIAGE_WEDDING_COST/MARRIAGE_DIVORCE_COST/MARRIAGE_REMARRY_COOLDOWN_DAYS/MARRIAGE_DIVORCE_ESCROW_HOURS/MARRIAGE_BACKPACK_UNLOCK_DAYS/MARRIAGE_BACKPACK_SLOTS/MARRIAGE_TELEPORT_CHARGE_SECONDS/MARRIAGE_TELEPORT_COOLDOWN_SECONDS), 移出 PENDING 清单; (b) 单独保留一条"escrow 公示期(divorceEscrowHours, 默认 24 小时)具体数值仍未终定, 见 MiningServerConfig.java:296-298 注释 exact value PENDING"作为唯一存续的待标定项。新增的 config 键对照表须列出全部 8 个键各自默认值(20000/10000/7 天/24 小时/[0,3,7,14,30]/[9,18,27,45,54]/[8,7,6,5,4]秒/[300,240,180,120,60]秒), 并注明婚龄口径 TICKS_PER_DAY = 20*86400(MarriageTuning.java:29)。

**[Major] [A-文档与代码不符] 共享背包黑名单写"走 tag + config, 服主可调", 实现是硬编码静态谓词不可调**

- 位置: 第 53 行
- 文档原文: L53: "- 允许: 消耗品、普通材料、食物、任务道具、情书/纪念物。黑名单走 tag + config, 服主可调。"
- 代码实际: 实现是纯静态谓词 SharedBackpackWhitelist: 高级矿物是硬编码的 Set<Item>(8 个物品) + BLOCKED_BLOCKS, 皮肤凭证按 item id 子串识别, 绑定装备按 NBT 键(OwnerUUID/SpouseUUID/MarriageId)识别。没有任何 ItemTag, 也没有任何 config 键, 服主完全不可调。另外实现还多做了一层文档未写的容器下钻(潜影盒内容物递归判定), 不下钻就能整包绕过全部黑名单。
- 取证: src/main/java/com/miningdim/marriage/SharedBackpackWhitelist.java:34-35 "public final class SharedBackpackWhitelist {" 与 L16 "纯静态谓词, 服务端权威"; L51-60: "private static final Set<Item> BLOCKED_ITEMS = Set.of(\n            Items.DIAMOND,\n            Items.EMERALD,\n            Items.NETHERITE_INGOT, ...)"; L46-48 "private static final String NBT_OWNER_UUID = \"OwnerUUID\";"; L39-41 "MAX_CONTAINER_DEPTH ... 容器本体判一次 + 内容物判一次"
- 建议改法: docs/Marriage_System_DesignSpec.md:53 改为: "- 允许: 黑名单未命中即放行。黑名单为硬编码静态谓词(SharedBackpackWhitelist), 不走 ItemTag 也不走 config, 服主不可调 —— 三类判据: 高级矿物固定 Item/Block 集合(BLOCKED_ITEMS/BLOCKED_BLOCKS, SharedBackpackWhitelist.java:52-68)、item id 命名空间属 tacz/cgm/timeless 且路径含 \"skin\" 的皮肤凭证(:147-155)、带 OwnerUUID/SpouseUUID/MarriageId 盖章 NBT 的绑定装备(:128-134)。另对潜影盒等容器内容物递归下钻一层判定(MAX_CONTAINER_DEPTH=1, :44、:110-126), 该下钻逻辑当前文档全篇未提及, 需一并补充说明其边界(下钻额度用尽后视为放行)。"

**[Major] [A-文档与代码不符] 传送触发文档写"长按结婚戒指"(暗示松开可取消), 实际是单次右键(不潜行)落子, 蓄力由服务端逐 tick 状态机推进, 与是否持续按住按键无关**

- 位置: 第 65 行
- 文档原文: L65: "发起方 A 长按结婚戒指 -> 服务端校验(双方在线、同维度可达、不在 CD、双方当前静止) -> 进入蓄力 T 秒:"
- 代码实际: 实现是一次 PlayerInteractEvent.RightClickItem(主手, 不潜行)即起蓄力, 没有任何长按/useOn 计时逻辑; 潜行右键分流去开共享背包。蓄力过程本身是服务端状态机按 tick 推进, 与玩家是否按住鼠标无关。
- 取证: src/main/java/com/miningdim/marriage/MarriageSystem.java:124-130: "if (player.isShiftKeyDown()) {\n            openSharedBackpack(player, overworld);\n        } else {\n            startTeleport(player, overworld);\n        }"; 同文件 L161 注释 "/** 不潜行右键: 起传送蓄力 (spec 第五章)。"
- 建议改法: docs/Marriage_System_DesignSpec.md:65 改为"发起方 A **右键(不潜行)**结婚戒指 -> 服务端校验(双方在线、同维度、不在 CD、双方当前静止) -> 进入蓄力 T 秒(服务端状态机按 tick 推进, 松开鼠标不影响; 仅位移/潜行/受伤三项会取消, 见下表):", 避免"长按"暗示的"松开即取消"语义。同时把 src/main/java/com/miningdim/marriage/MarriageTeleport.java:17 类注释里同样的"发起方长按结婚戒指开始蓄力"一并改为"右键(不潜行)结婚戒指起蓄力", 消除代码注释与代码实现(MarriageTeleport.java:90-133 的 tryStart/tick)之间的自相矛盾, 防止未来维护者据此注释误加"按住使用"相关的 getUseDuration/useOn 逻辑造成双状态机冲突。

**[Major] [A-文档与代码不符] 跨维度传送: 文档说过 reentry gate 放行, 实现是直接拒绝(全库无 changeDimension 调用)**

- 位置: 第 78 行
- 文档原文: L78: "- 跨维度传送(进矿洞维度): 须先过 `economy.AbuseGuard.checkReentryGate`。"; L79 又写 "传送用 `ServerPlayer.teleportTo` / `changeDimension`(仿矿洞回退态写法)"
- 代码实际: 实现根本不做跨维度传送: 起蓄力时若双方不在同一维度直接返回 StartResult.DIFFERENT_DIMENSION 拒绝, 蓄力中维度变化也立即 cancel; 伴侣在矿洞维度时蓄力直接拒绝并提示走 /mining enter。代码只调 teleportTo, 从不调 changeDimension, 因此 AbuseGuard.checkReentryGate 在本路径上压根不会被调用。按文档去接 checkReentryGate 是无用功, 且会误以为跨维度传送是支持的。
- 取证: src/main/java/com/miningdim/marriage/MarriageTeleport.java:25-28: "跨维度约束: 本系统拒绝把玩家直接传进矿洞维度实例 (会绕过 EntryGateway 的实例引用计数/重入闸/落点安全, 比单纯重入更严重)。故伴侣在矿洞维度 ... 时蓄力直接拒绝, 提示走 /mining enter (该流程内含 AbuseGuard.checkReentryGate); 同维度世界内传送照常。"; 同文件 L67 "DIFFERENT_DIMENSION," L119 "return StartResult.DIFFERENT_DIMENSION;" L165 "cancel(initiator, partner, \"dimension\");" L241 "initiator.teleportTo(partner.serverLevel(),"
- 建议改法: 将 docs/Marriage_System_DesignSpec.md L78-79 改为: "- 跨维度传送: **不支持**。发起时若双方不在同一维度直接拒绝(`StartResult.DIFFERENT_DIMENSION`); 蓄力中任一方换维度立即取消(原因码 `dimension`); 伴侣处于矿洞维度时蓄力直接拒绝(`StartResult.SPOUSE_IN_MINING_DIM`), 提示走 `/mining enter`。理由: 直接把玩家传进矿洞维度实例会绕过 EntryGateway 的实例引用计数/重入闸/落点安全, 比单纯重入更严重(见 `MarriageTeleport` 类注释)。" - 可实现性一句删除 "changeDimension", 只保留 "传送用 `ServerPlayer.teleportTo`(同维度传送到伴侣身边)"。 另建议口头向作者报告(不在本条范围内新开发现): 全库 `checkReentryGate` 目前 0 处实际调用(仅 economy/AbuseGuard.java:442 定义), /mining enter 流程是否真的接了这道闸需要单独核实, 避免文档链式引用一个同样悬空的方法。

### `docs/Minecraft实体堆叠_需求规格说明书.md`

**[Major] [E-状态过期] "候选 mod 覆盖度核对表"选型决策已尘埃落定逾一月(自研 wok-stacking 早已上线并通过完整审计), 文档仍以待决策口吻呈现**

- 位置: 第 103-120 行
- 文档原文: ## 六、候选 mod 覆盖度核对表 ... 核对结论:两者的"击杀掉落 × N"基本覆盖... 两项,两个 mod 的公开描述均未承诺,须在测试服实测;若不达标,即为自建 mod 的核心理由。
- 代码实际: 自建实现早已落地并成为登记模块 wok-stacking(11 个 Java 文件 / 33 个 GameTest, 入口 StackingSystem), 选型决策已经做完, 第六节整节讨论的"选 frikinjay 还是 DevDr0ggy"不再有任何决策价值, 表内十余处"待实测"也不会有人去测。留着会让读者误以为选型仍未定。
- 取证: docs/modules/INVENTORY.md:36 `| wok-stacking | WOK-实体堆叠模块 | 11 | 33 | StackingSystem | 核心、精英怪 |`; src/main/java/com/miningdim/stacking/StackingSystem.java:63-65 注册独立配置 `StackingConfig.SPEC, "miningdim-stacking.toml"`; src/main/java/com/miningdim/stacking/StackMerge.java / StackDeath.java / StackPassive.java / StackSplit.java 均为自研实现
- 建议改法: 在 docs/Minecraft实体堆叠_需求规格说明书.md 第103行"## 六、候选 mod 覆盖度核对表"标题后立即加一行归档提示,例如: `> 已作废(自建实现已落地并上线, 见 wok-stacking 模块 / StackingSystem, 决策沿革见 docs/Full_Repo_Audit_2026-08.md 主控决策 D1/D2): 本节仅作选型过程存档, 不再更新, 表内"待实测"项均无需再验证。` 或将整节移入附录/单独的《选型决策记录》文档, 避免与仍在生效的第一至五章(默认参数表/FR/NFR, 已与 StackingConfig.java 逐键核对一致)混淆。

**[Major] [D-覆盖缺口] 参数表漏了总开关 enabled 与 interaction 两个键**

- 位置: 第 11-28 行
- 文档原文: L11-12 表头: "| 配置键 | 类型 | 默认值 | 说明 |" —— 表内只有 merge.* / drops.* / passive.* / exclusions.* 四组共 17 行, 无顶层 enabled, 无 interaction 段。
- 代码实际: 代码另有三个已落地并被消费的配置键, 文档一个都没登记: 顶层 enabled (子系统总开关, 关掉即停止一切新合并, 是运维一键止血旋钮)、interaction.leashMode (FR-5.2 拴绳语义, 默认 SPLIT_ONE)、interaction.splitGraceTicks (FR-5.1 拆出个体的免合并保护期, 默认 600 tick)。服主看文档不会知道有止血开关。
- 取证: src/main/java/com/miningdim/stacking/StackingConfig.java:90-91: "ENABLED = b.comment(\"Master kill switch for the entity stacking subsystem. false stops all NEW merges ...\")\n                .define(\"enabled\", true);"; L140-144: "b.push(\"interaction\"); LEASH_MODE = ... .defineEnum(\"leashMode\", LeashMode.SPLIT_ONE); SPLIT_GRACE_TICKS = ... .defineInRange(\"splitGraceTicks\", 600, 0, 72000);"
- 建议改法: 在 docs/Minecraft实体堆叠_需求规格说明书.md 第 11 行表头下方(默认参数表最前)加一行: "| enabled | bool | true | 子系统总开关; false 停止一切新合并, 已成堆叠仍正常结算掉落/被动/拆分 |"; 并在第 28 行后(表尾)追加两行: "| interaction.leashMode | enum | SPLIT_ONE | SPLIT_ONE(拴绳先拆 1 个再拴) / WHOLE_STACK(拴整堆, 原版行为) |"、"| interaction.splitGraceTicks | int(tick) | 600 | 刚拆出的个体在此期间不被重新吸收, 供玩家牵走 |"。对应代码依据: src/main/java/com/miningdim/stacking/StackingConfig.java 第 90-91、140-144 行。

**[Major] [A-文档与代码不符] 堆叠规格默认参数表的配置键名与真实 toml 键全对不上,且缺三个真实存在的键**

- 位置: 第 11-30 行
- 文档原文: ## 一、默认参数表(量化基线) | 配置键 | 类型 | 默认值 | 说明 | ... | merge.radius.horizontal | int(格) | 5 | 水平合并半径 | ... | merge.scan_interval | int(tick) | 100 | ... | drops.death_mode | enum | instant_all | ...
- 代码实际: 真实配置落在 miningdim-stacking.toml, 键名全是 camelCase 且分组层级只有一层: [merge] radiusHorizontal / radiusVertical / trigger / scanIntervalTicks / maxStackSize / requireMoved, [drops] deathMode / lootRollMode / multiplyXp。文档写的 merge.radius.horizontal 之类在 toml 里一个都不存在。另外文档表缺了代码实际存在的三个键: 顶层 enabled 总开关、interaction.leashMode、interaction.splitGraceTicks。服主按这张表去改配置文件会全部改不动(nightconfig 对未知键静默忽略)。
- 取证: src/main/java/com/miningdim/stacking/StackingConfig.java:95-101 `b.push("merge"); MERGE_RADIUS_HORIZONTAL = b.comment(...).defineInRange("radiusHorizontal", 5, 0, 64); MERGE_RADIUS_VERTICAL = ... defineInRange("radiusVertical", 3, 0, 64);`; 同文件 :86-87 `ENABLED = b.comment("Master kill switch...").define("enabled", true);`; :139-146 `b.push("interaction"); LEASH_MODE = ... defineEnum("leashMode", LeashMode.SPLIT_ONE); SPLIT_GRACE_TICKS = ... defineInRange("splitGraceTicks", 600, 0, 72000);`; 注册点 src/main/java/com/miningdim/stacking/StackingSystem.java:63-65 `registerConfig(..., StackingConfig.SPEC, "miningdim-stacking.toml")`
- 建议改法: 把 docs/Minecraft实体堆叠_需求规格说明书.md 第9-29行参数表的"配置键"一列整列替换为真实 TOML 路径(表头注明落盘文件 miningdim-stacking.toml,依据 StackingSystem.java:63-65 的 registerConfig 调用),并补全代码里存在而表中缺失的三项:enabled(顶层总开关,依据 StackingConfig.java:86-87)、merge.radiusHorizontal、merge.radiusVertical、merge.trigger(ON_MOVE|INTERVAL)、merge.scanIntervalTicks、merge.maxStackSize、merge.requireMoved(以上均依据 StackingConfig.java:92-101)、drops.deathMode(INSTANT_ALL|ONE_PER_KILL)、drops.lootRollMode(PER_INDIVIDUAL|MULTIPLY_BASE)、drops.multiplyXp、passive.shearEnabled、passive.milkEnabled、passive.eggEnabled、exclusions.named、exclusions.tamed、exclusions.boss、exclusions.blacklist、interaction.leashMode、interaction.splitGraceTicks(interaction.* 两项依据 StackingConfig.java:139-146,文档此前完全未登记)。

**[Major] [A-文档与代码不符] 默认参数表 16 个配置键中有 11 个键名格式与实际 toml 键名不符(应为单层 camelCase, 非文档写的多级下划线路径); 另 5 个(trigger/named/tamed/boss/blacklist)本身已与代码一致**

- 位置: 第 13-28 行
- 文档原文: L13-18: "| merge.radius.horizontal | int(格) | 5 | 水平合并半径 |" "| merge.radius.vertical | int(格) | 3 |" "| merge.scan_interval | int(tick) | 100 |" "| merge.max_stack_size | int | 64 |" "| merge.require_moved | bool | true |"; L19-24: "drops.death_mode" "drops.loot_roll_mode" "drops.multiply_xp" "passive.shear.enabled" "passive.milk.enabled" "passive.egg.enabled"
- 代码实际: 实际 ForgeConfigSpec 键名一律是 camelCase 且层级只有一层 section: merge.radiusHorizontal / merge.radiusVertical / merge.scanIntervalTicks / merge.maxStackSize / merge.requireMoved / drops.deathMode / drops.lootRollMode / drops.multiplyXp / passive.shearEnabled / passive.milkEnabled / passive.eggEnabled / exclusions.named 等。默认值全部对得上, 只有键名对不上。服主按文档去改 miningdim-stacking.toml 会写出一堆被忽略的无效键, 且不会报错。
- 取证: src/main/java/com/miningdim/stacking/StackingConfig.java:96-107: "MERGE_RADIUS_HORIZONTAL = b.comment(\"Horizontal merge radius in blocks (FR-1.1)\")\n                .defineInRange(\"radiusHorizontal\", 5, 0, 64);" ... "MERGE_SCAN_INTERVAL = ... .defineInRange(\"scanIntervalTicks\", 100, 1, 6000);" "MERGE_MAX_STACK_SIZE = ... .defineInRange(\"maxStackSize\", 64, 1, 100000);"; L121-126: "PASSIVE_SHEAR_ENABLED = ... .define(\"shearEnabled\", true);"; 配置文件名见 StackingSystem.java:63-65 "registerConfig(..., StackingConfig.SPEC, \"miningdim-stacking.toml\")"
- 建议改法: 仅改动确实错误的 11 行"配置键"列(其余 5 行 merge.trigger / exclusions.named / exclusions.tamed / exclusions.boss / exclusions.blacklist 保持不变, 因为已经和代码一致): merge.radius.horizontal -> merge.radiusHorizontal merge.radius.vertical -> merge.radiusVertical merge.scan_interval -> merge.scanIntervalTicks merge.max_stack_size -> merge.maxStackSize merge.require_moved -> merge.requireMoved drops.death_mode -> drops.deathMode drops.loot_roll_mode -> drops.lootRollMode drops.multiply_xp -> drops.multiplyXp passive.shear.enabled -> passive.shearEnabled passive.milk.enabled -> passive.milkEnabled passive.egg.enabled -> passive.eggEnabled 同时把默认值列里的枚举默认值大小写一并改正: on_move -> ON_MOVE, instant_all -> INSTANT_ALL, per_individual -> PER_INDIVIDUAL(依据 StackingConfig.java 中枚举常量名与 defineEnum 默认值)。 表头上方补一句"配置文件: <world>/serverconfig/miningdim-stacking.toml"(依据 StackingSystem.java L63-65 的 registerConfig 调用)。 不建议把顶层 enabled 开关或 interaction.leashMode / interaction.splitGraceTicks 塞进本条修复, 那是本文档"覆盖缺口"类的另一条独立问题, 不属于本条"键名不符"的范围。

**[Major] [E-状态过期] 第六章候选 mod 核对表与"自建理由"结论已被自建实现作废**

- 位置: 第 5, 103-120 行
- 文档原文: L5: "文档用途:(1) 评估候选 mod 的验收清单;(2) 自建 Forge mod 的实现规格,两者通用"; L120: "核对结论:两者的\"击杀掉落 × N\"基本覆盖(Mob & Item Stacker 描述更明确),但 FR-3 被动产出倍增 与 FR-2.2 概率掉落正确性 两项,两个 mod 的公开描述均未承诺,须在测试服实测;若不达标,即为自建 mod 的核心理由。"
- 代码实际: 自建实现早已落地并带完整 GameTest: com.miningdim.stacking 共 11 个类 3254 行, 覆盖合并/主动掉落/被动产出/繁殖/拆分/持久化全部 FR, 另有 StackingGameTests(918 行) 与 StackingInteractionGameTests(492 行)。第六章整张"Mob Stacker Ind. / Mob & Item Stacker"覆盖度核对表与"待实测"标记、以及"若不达标即为自建理由"的结论, 全部是已经走完的决策过程, 留在正文会让读者以为还在选型阶段。
- 取证: src/main/java/com/miningdim/stacking/ 目录: StackBreed.java(103) StackData.java(95) StackDeath.java(238) StackMatchKey.java(109) StackMerge.java(311) StackPassive.java(476) StackSplit.java(139) StackingConfig.java(160) StackingSystem.java(213) 及两份 GameTest; StackingSystem.java:25 "实体堆叠子系统入口 (需求规格阶段 1; implements core.Subsystem; 模块化铁律 3 自注册)"
- 建议改法: L5 用途改为"自建 Forge 实现 (com.miningdim.stacking) 的验收规格",删掉"评估候选 mod"一句。第六章整章降级为附录并加 HISTORICAL 注记:"选型阶段产物,结论=自建;实现见 com.miningdim.stacking(StackingSystem 已注册于 MiningDim.java:165),本表不再维护"。第五章 AC 表上方补一句"对应自动化用例:StackingGameTests / StackingInteractionGameTests"。

**[Major] [C-实现状态标错] FR-5.2 拴绳语义文档仍写成"二选一待定案",实际已在 F066 修复中固化为 config interaction.leashMode(默认 SPLIT_ONE),文档未记录默认值与已完成的双分支实现**

- 位置: 第 67 行
- 文档原文: L67: "- FR-5.2 (MUST) 拴绳语义须固化:默认作用于整堆或先拆出 1 个,二选一写入配置。"
- 代码实际: 已经选完并落地: config 键 interaction.leashMode 默认 SPLIT_ONE (对堆叠用拴绳=拆出 1 个体单独拴住), WHOLE_STACK 是另一可选分支且有完整语义实现。文档没有记录这个默认值决策, 读者无从知道线上行为是哪一种。
- 取证: src/main/java/com/miningdim/stacking/StackingConfig.java:141-142: ".defineEnum(\"leashMode\", LeashMode.SPLIT_ONE)"; src/main/java/com/miningdim/stacking/StackSplit.java:21-22: "FR-5.2 (拴绳语义二选一, config interaction.leashMode): SPLIT_ONE 时对堆叠用拴绳 = 拆出 1 个体并单独拴住它; WHOLE_STACK 时拴绳直接作用于整堆实体本身 (原版行为)"
- 建议改法: 将 docs/Minecraft实体堆叠_需求规格说明书.md 第67行由"FR-5.2 (MUST) 拴绳语义须固化:默认作用于整堆或先拆出 1 个,二选一写入配置。"改为:"FR-5.2 (MUST) 拴绳语义已固化为 config interaction.leashMode(StackingConfig.java):默认 SPLIT_ONE——对堆叠用拴绳会先拆出 1 个个体并单独拴住它;可选 WHOLE_STACK——拴绳直接作用于整堆实体本身(原版行为)。两种模式下被拴住的堆叠均因 canMerge 的 isLeashed 闸停止吸收其它个体(见 StackSplit.java、StackMerge.java)。"并建议同步核对文档中其余仍用"须固化/二选一/待定"等悬决口吻描述的条目(如有),确认是否已被后续修复(参照 Full_Repo_Audit_2026-08.md 的 F066 等记录)收敛但未回写文档。

**[Major] [A-文档与代码不符] 堆叠规格按"同种实体皆可堆"写，代码只白名单四种农场动物**

- 位置: 第 7, 95 行
- 文档原文: L7 `- 核心机制:范围内同种同状态实体合并为单实体...`; L95 `| AC-4 | 击杀 "Zombie x100",统计概率掉落 | 稀有掉落频次符合 100 次独立 roll 的期望(统计容差内),证明非 base×N |`
- 代码实际: 代码是白名单准入: 只有猪/鸡/羊/牛四种才可能参与堆叠, 其余全部 LivingEntity(含僵尸骷髅、玩家、村民、自研精英怪)一律不合并, 且白名单是硬编码而非 config 键(决策 D1, 刻意不给服主扩展旋钮)。因此 AC-4 这条验收用例在当前实现下永远构造不出"Zombie x100", 照它写自动化测试必然失败; 规格全文也没有任何一处提到白名单, 读者会以为怪物农场可以堆叠。
- 取证: src/main/java/com/miningdim/stacking/StackMerge.java:59-60 `private static final Set<EntityType<?>> STACKABLE_TYPES = Set.of(EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.COW);`; 同文件 :26-27 类注释 `候选准入 ({@link #canStack}): 白名单式 —— 只有 {@link #STACKABLE_TYPES} 四种 (决策 D1) 才可能参与堆叠, 其余 LivingEntity (含玩家/村民/盔甲架/僵尸骷髅/自研精英怪) 一律不合并`; :54-56 `硬编码而非 config 键: D1 是产品决策而非运维旋钮`
- 建议改法: 在 L7 核心机制句后补一句硬约束："合并候选为白名单制，当前仅猪/鸡/羊/牛四种（决策 D1，硬编码于 StackMerge.STACKABLE_TYPES，非配置键）；怪物、村民、驯服宠物、精英怪一律不参与"；并把 AC-4 的被测对象从 Zombie x100 换成 Cow x100 或 Chicken x100，否则该用例在当前实现下无法通过（canStack 对非白名单类型直接短路返回 false）。

### `docs/CASE_ASSET_PROVENANCE.md`

**[Minor] [F-结构问题] docs 根并存四套命名风格, 类型后缀无约束力**

- 位置: 第 1 行
- 文档原文: L1「# 开箱资产来源与再分发边界」(文件名 CASE_ASSET_PROVENANCE.md 采用全大写下划线)
- 代码实际: docs/ 顶层 57 份文件并存四套命名风格: 主流的 PascalCase_主题_类型后缀(*_DesignSpec / TaskSpec_* / *_AssetChecklist)、全大写下划线(CASE_ASSET_PROVENANCE.md)、全小写下划线(design_mindmap.md)、纯中文或中英混排(服务器经济系统设计文档.md、Minecraft实体堆叠_需求规格说明书.md, 加仓库根的 分支协作.md)。类型后缀本身也没有约束力: 22 份带 _DesignSpec, 但 Champion_Effects_Guide(玩家手册)、Gunsmith_Component_Hot_Reload_Rules(运维规则)、Ore_Fish_And_Soup、Fishing_Journal_Framework、Economy_SQLite_Migration_Plan 等 24 份落在任何既有后缀之外, 从文件名读不出它是规格、报告、手册还是计划。
- 取证: 文件名与仓库其他标识体系不接轨: docs/modules/module-registry.json 的模块 id 全部是 ASCII 小写连字符(wok-job-munitions、wok-case-opening), 中文名文档无法与任何模块键做前缀对齐, 这正是本轮另一条发现(登记表加 designDocs 字段)落地时的直接障碍。
- 建议改法: 在 docs/README.md 固化命名规范: `<主题>_<类型>.md`, 类型取 DesignSpec / TaskSpec / Audit / Review / AssetChecklist / Manual / Plan 七个受控值之一, 主题用 ASCII PascalCase 与 module-registry.json 的模块键可对齐。存量重命名按目录迁移(docs/spec、docs/manual、docs/archive 等)一并做完, 避免二次改名; 三份中文名文档改为英文名 + 中文标题(正文标题仍用中文)。

**[Minor] [F-结构问题] 开箱资产来源文档(CASE_ASSET_PROVENANCE.md)未被仓库任何文件引用,与 THIRD-PARTY-NOTICES 变更纪律未互链(注:孤儿文档在本仓库 docs/ 下并非此文件独有,如 Power_Cable_AssetChecklist.md、Fishing_Journal_Framework.md、Gunsmith_Bullpup_Components.md 同样零引用;但此文档涉及法务合规内容,遗漏互链的实际风险更高)**

- 位置: 第 1-30 行
- 文档原文: 全文 30 行独立成篇, 无任何上游链接; THIRD-PARTY-NOTICES.md:90-94 "## 六、变更纪律 新增任何第三方依赖时，必须同步更新本文件" 只提本文件, 未指向 CASE_ASSET_PROVENANCE.md。
- 代码实际: 对全仓 *.md 搜索 "CASE_ASSET_PROVENANCE" 零命中 —— 该文档既不被 README.md 引用, 也不被 THIRD-PARTY-NOTICES.md、LICENSE、任何 docs/ 下文档引用, 是完全的孤儿。对比之下, 同目录其它规格文档至少被一份文档引用(如 JobFramework 被 4 份职业 spec 引用)。法务性质的文档挂成孤儿, 下次改开箱资产时没人会想起来同步它。
- 取证: 对 D:/Repo/_wt-docs 全库 `grep -rln "CASE_ASSET_PROVENANCE" --include=*.md` 返回空; 对照 `grep -rln "JobFramework_Shared_Foundation_DesignSpec" --include=*.md` 返回 docs/ChampionStarAffix_System_DesignSpec.md、docs/FarmingXP_Mod_DesignSpec.md、docs/Munitions_Job_DesignSpec.md、docs/SpecialAgent_Job_DesignSpec.md 四份
- 建议改法: 在 THIRD-PARTY-NOTICES.md 第六节"变更纪律"末尾补一句,明确开箱模块(com.miningdim.caseopening)的资产来源登记见 docs/CASE_ASSET_PROVENANCE.md,并要求改动开箱资产时同步更新两份文件;同时在 CASE_ASSET_PROVENANCE.md 顶部加一行回链 THIRD-PARTY-NOTICES.md。此修复只解决这一份文档的孤儿问题,建议另行口头报告"docs/ 目录下孤儿文档非个例(至少还有 Power_Cable_AssetChecklist.md、Fishing_Journal_Framework.md、Gunsmith_Bullpup_Components.md)"这一更大范围的结构性问题,由人工决定是否值得建立统一的 docs/README.md 索引来系统性解决,不在本条修复范围内一并处理。

### `docs/JobFramework_Shared_Foundation_DesignSpec.md`

**[Minor] [A-文档与代码不符] IEconomyService 契约签名用 Player, 实现是 ServerPlayer; /job 子命令清单漏 wallet**

- 位置: 第 62-68, 124 行
- 文档原文: L62-68: "interface IEconomyService { long creditBalance(Player p); long heartstoneBalance(Player p); boolean tryCharge(Player p, Currency c, long amount); void grant(Player p, Currency c, long amount); boolean tryChargeDaily(Player p, Currency c, long amount, String dailyKey, long dailyCap); }"; L124: "子命令: `/job list`...、`/job info <job>`、`/job top <job>`(可选排行);OP: `/job set <player> <job> <level>`。"
- 代码实际: 实际接口五个方法参数一律是 ServerPlayer(服务端权威, 不是 Player), 另有 5 个文档未列的方法: settleOreSale / recordMinedOreDrops / grantDaily / grantAzureDaily / isAfkFrozen。/job 命令实际注册 list / info / wallet / set 四个子命令 —— 文档标"可选"的 top 未实现(可接受), 但已实现的 wallet 未登记。
- 取证: src/main/java/com/miningdim/economy/IEconomyService.java:50-204: "long creditBalance(ServerPlayer player);" "long heartstoneBalance(ServerPlayer player);" "boolean tryCharge(ServerPlayer player, Currency currency, long amount);" "void grant(ServerPlayer player, Currency currency, long amount);" "boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount, String dailyKey, long dailyCap);" "long settleOreSale(...)" "int recordMinedOreDrops(...)" "long grantDaily(...)" "long grantAzureDaily(...)" "boolean isAfkFrozen(...)"; src/main/java/com/miningdim/job/JobCommands.java:40-48 "Commands.literal(\"job\").then(Commands.literal(\"list\")).then(Commands.literal(\"info\")).then(Commands.literal(\"wallet\")).then(Commands.literal(\"set\"))"
- 建议改法: L62-68 代码块把 5 处 `Player p` 改为 `ServerPlayer player`, 并在块后加一句"实现另含 settleOreSale / recordMinedOreDrops / grantDaily / grantAzureDaily / isAfkFrozen 五个经济侧结算方法, 详见 economy 文档"。L124 子命令清单补 "`/job wallet`(查信用点与青辉石余额)", 并把 `/job top` 标为未实现。

### `docs/Marriage_System_DesignSpec.md`

**[Minor] [C-实现状态标错] 婚姻十二章条目3(共享背包黑名单)已在代码落地却仍标 PENDING,十三章标题"确认 PENDING 后展开"与已交付的实现现状不符;条目1数值 PENDING 标记本身不过期**

- 位置: 第 188-196 行
- 文档原文: ## 十二、待确认实现项 (PENDING) / 1. 各项 config 数值标定: 结婚成本/离婚成本/再婚冷却天数/escrow 公示期/共享背包各级容量与解锁婚龄/传送 T 与 CD 与各级。
- 代码实际: 这些数值早已作为 ForgeConfigSpec 键落在 MiningServerConfig 的 marriage 段, 并由 MarriageTuning 按婚龄派生等级与容量, 业务代码(menu/teleport/divorce)只问 MarriageTuning 要派生结果。第十三章标题"(确认 PENDING 后展开)"同样过期 —— marriage 包 20 个类 + WebUI 面板 923 行均已交付。
- 取证: src/main/java/com/miningdim/config/MiningServerConfig.java:265-274 `b.push("marriage"); MARRIAGE_ENGAGEMENT_COST = b.comment("Credit cost to buy an engagement ring (/marriage buyring)") ... .defineList("backpackUnlockDays", ...)`; src/main/java/com/miningdim/marriage/MarriageTuning.java:8-10 类注释 `结婚系统阶段 2 数值派生 (结婚系统 spec 第四/五/六章; 实时读 {@link MiningServerConfig}, 严禁缓存) ... 所有阶梯阈值与各级数值都来自 config 列表`
- 建议改法: 不要按原建议删除或改写十二章条目1——"config 数值标定"这个 PENDING 至今仍是代码自己认领的状态(MiningServerConfig.java:95/266/297 三处注释仍写着"第十二章 PENDING 数值标定"/"ch.12 PENDING tuning"/"exact value PENDING",RingItem.java:25、MarriageCommands.java:163、MarriageWebUiActions.java:409 三处证婚人相关代码也仍以"spec 第十二章 PENDING"为由把 officiant 恒传 null),按文档第8行的状态图例定义("PENDING 待选或待标定数值"),config 键已存在与数值仍待标定并不矛盾,条目1维持原状即可。  需要改的只有两处、且都是 Minor 级的状态标记收尾: 1) 十二章条目3(第192行)"共享背包黑名单的精确 tag 集合(高级矿物/皮肤凭证/绑定装备的判定)"——这项已经在 src/main/java/com/miningdim/marriage/SharedBackpackWhitelist.java:52-68 落地为具体的 BLOCKED_ITEMS/BLOCKED_BLOCKS 常量集合 + NBT 键判定 + TACZ 皮肤 id 子串判定,应从"待确认"清单移除,或改写为"已实现(见 SharedBackpackWhitelist),如需迁移到原版 Tag 体系可再议"。 2) 十三章标题(第198行)"(确认 PENDING 后展开)"应去掉这个前提性表述,改为类似"(核心机制已实现,见 marriage 包; 十二章条目2/候选功能、条目4/动画库、条目5/证婚人仍待拍板后再扩展)",避免读者误以为整个婚姻系统尚未开始实现——marriage 包已有 20 个类且 webui/src/pages/MarriagePage.tsx(923 行)已交付,最近改动分别在 2026-08-16 与 2026-08-19,晚于本文档 2026-06-18 的唯一一次提交。

---

## 其它

### `docs/Full_Repo_Audit_2026-08.md`

**[Critical] [E-状态过期] 全文无冻结快照声明,112 条里只有 F038 标了修复判定**

- 位置: 第 1-5, 13-26, 212 行
- 文档原文: L1 `# 全库审计报告 (2026-08)`;L4 `基线提交: 49d5283 (WebUI 全量接线合并后)`;L13 `原始 findings **112** 条。对其中 66 条 Critical/Major 做了对抗复核: **62 条维持, 4 条被推翻 (误报), 20 条降级为 Minor**`;L212 `编号 F001-F112 稳定, 按复核后严重度排序 ... 日后引用请直接用编号`
- 代码实际: 文档正文与每条 finding 的「证据/影响/复核」全部用现在时陈述,通篇没有任何「本文是 49d5283 时点的冻结快照,后续修复不回写本文」的声明,也没有逐条的修复状态栏。全 112 条中唯一带修复判定的是 F038(L603 `- **判定 (2026-08, 分支 fix/stacking-animal-whitelist)**: 按主控决策 D2 关闭, 不作为缺陷修`)。实测 85 个不重复「位置」路径中已有 8 个文件被删除、六条 Critical 全部闭合、大量 Major/Minor 已修,但文档无一处反映。同时它已被其它文档当成现状清单引用:docs/MillenniumEngineer_Mod_DesignSpec.md:151 `**待主控拍板 (docs/Full_Repo_Audit_2026-08.md F047, 复核定性为设计复议项、已降 Minor 但未结案)**`,docs/TaskSpec_Mining_EntryFee.md:216 `矿洞入场链路当前 GameTest 覆盖为零 (全库审计 F092 明确指出...)`——而 F092 点名的五个包如今都已有 GameTest。
- 取证: git 工作树 HEAD=bd0c4588。批量存活性比对:文档 85 个位置路径中 src/main/java/com/miningdim/instance/GenerationScheduler.java、job/agent/integration/AgentChampionData.java、job/agent/network/AgentScanSyncS2C.java、job/agent/network/AgentSealRequestC2S.java、job/agent/panel/AgentScanMenu.java、network/SelectZoneC2S.java、src/main/resources/data/miningdim/affix_setting/uhmwpe_armor.json、webui/src/components/ui/drawer.tsx 共 8 个已不存在。F092 反证:src/main/java/com/miningdim/instance/RegionSlideGameTests.java、reset/(2 个 GameTests)、chunk/(1 个)、trap/TrapGameTests.java、spawn/SpawnDistributionGameTests.java 均已存在。删除提交示例:9e8ca867 `fix(miningdim): 出生点池改回真实世界预扫描, 消除同格叠压并注销死信道包 (F034/F087)`、8150710a `refactor(miningdim): 下线已判废的离线体素生成管线`、c5cd8bc9 `fix(job-agent): 特勤集成层脱离已废弃 Champions capability, 改读自研 MiningChampions`。
- 建议改法: 在 L1 标题下方、L3 审计范围之前插入一段状态声明(全文唯一需要新增的结构),例如:「> **状态: 冻结的历史快照(2026-08-15,基线 49d5283)。本文不再随代码更新。** 其中六条 Critical 与绝大多数 Major 已在 2026-08 至 2026-09 的修复轮中闭合(见附录 A 各条的「修复状态」栏)。引用本文的 F 编号时,必须先核对该条的修复状态,不得把本文当作现存问题清单。」并在附录 A 的每条 finding 的「复核」栏之后补一行 `- **修复状态**: 已闭合 (提交/分支 xxx) / 仍存在 / 已作废`,以 F038 现有的「判定」行为格式范本。

**[Critical] [E-状态过期] P0 处置建议「摘掉 stacking 子系统装配」已作废且照做会误停功能**

- 位置: 第 185, 63-65 行
- 文档原文: L185 `| **P0 · 立刻** | 摘掉 stacking 子系统装配 (无配置开关可关), 再修 StackMerge 候选判定 (簇 A) | 上线即崩服 + 不可逆资产损毁 |`;L63-65 `**紧急止血**: 由于不存在配置开关, 修完之前唯一的止血手段是**注释掉主类里 subsystems.add(new com.miningdim.stacking.StackingSystem()) 那一行**。顺带说明: "缺一个总开关"本身也是缺陷`
- 代码实际: StackingConfig 已补上热重载的子系统总开关 ENABLED,合并候选也已改成硬编码白名单,原「上线即崩服」的前提不复存在。照 L63-65 去注释掉装配行,等于用一次发版把一个已修好的子系统整体下线,而正确动作只是把 toml 里的 enabled 改成 false。
- 取证: src/main/java/com/miningdim/stacking/StackingConfig.java:57 `public static final ForgeConfigSpec.BooleanValue ENABLED;`;同文件 L90-91 `ENABLED = b.comment("Master kill switch for the entity stacking subsystem. false stops all NEW merges ... Hot-reloadable.").define("enabled", true);`;同文件 L21 注释 `顶层 {@link #ENABLED} 是子系统总开关 (F094 修复配套的运维旋钮...)`。装配行仍在 src/main/java/com/miningdim/MiningDim.java:165 `subsystems.add(new com.miningdim.stacking.StackingSystem());`,但已不再是唯一关停手段。
- 建议改法: 把 L185 该行改为: `| ~~P0 · 立刻~~ **已闭合** | 摘掉 stacking 子系统装配 → 已由 StackingConfig.ENABLED (config/miningdim-stacking.toml 顶层键 enabled, 由 StackingSystem.onServerTick 消费短路, 热重载) 取代; StackMerge 候选判定已从黑名单式改为硬编码白名单 (STACKABLE_TYPES: 猪/鸡/羊/牛) | — |`。 把 L63-65 整段"紧急止血"替换为一行说明: "(已闭合) 总开关缺失与候选判定两项均已修复: 关停改走 config/miningdim-stacking.toml 里的 `enabled=false` (StackingConfig.ENABLED, 热重载), 不要再注释 MiningDim.java:165 的装配行, 否则会把已收口到白名单的子系统整体下线。" (相比待核发现原文的修复建议, 此处订正了配置文件名与键路径: 实际是独立文件 `miningdim-stacking.toml` 的顶层键 `enabled`, 不是 `miningdim-server.toml` 下的 `stacking.enabled`。)

**[Critical] [C-实现状态标错] 簇 A「实体堆叠上线即崩」整章描述与现行代码相反**

- 位置: 第 34-65 行
- 文档原文: L34 `### 簇 A — 实体堆叠系统上线即崩 (3 Critical + 2 Major)`;L36-37 `com.miningdim.stacking 的合并候选判定只有一道类型闸: if (!(entity instanceof LivingEntity)) return false (StackMerge.java:44)。之后的四道过滤 (自定义名 / 驯服 / Boss / 黑名单) 一个都拦不住下列对象:`;L49 `StackingConfig 的黑名单默认是空表 (defineList("blacklist", List.of(), ...)), 所以以上全部处于放行状态。`
- 代码实际: canStack 的第一道实质闸已经是硬编码的四种低价值农场动物白名单(决策 D1),ServerPlayer / 村民 / 盔甲架 / 铁傀儡 / 本工程精英怪全部在类型判定处就被挡住,「黑名单默认空表所以全部放行」的结论已不成立。F004/F005/F018/F037 四条 Critical/Major 的根因已一并闭合。
- 取证: src/main/java/com/miningdim/stacking/StackMerge.java:59-60 `private static final Set<EntityType<?>> STACKABLE_TYPES = Set.of(EntityType.PIG, EntityType.CHICKEN, EntityType.SHEEP, EntityType.COW);`;同文件 L90-96 `public static boolean canStack(Entity entity) { if (!(entity instanceof LivingEntity)) { return false; } if (!isStackableType(entity)) { return false; }`;同文件白名单注释 `合并候选白名单 (主控决策 D1)。硬编码而非 config 键: D1 是产品决策而非运维旋钮, 且 F004 的根因正是 "默认放行一切 LivingEntity + 靠配置收口"`;并有纵深防御 `3) 自研精英怪 (MiningChampions#isChampion) -> false: 纵深防御 (F018/F037)`。
- 建议改法: 在 L34 标题后补一行 `> **已闭合 (决策 D1, 分支 fix/stacking-animal-whitelist)**: 合并候选已收口为硬编码白名单 minecraft:pig/chicken/sheep/cow (StackMerge.java:59-60), 玩家/村民/盔甲架/铁傀儡/精英怪在类型判定处即被排除 (StackMerge.java:90-103); 本节以下内容保留为历史记录。` 并把 F004(:252 起)、F005(:262 起)、F018(:395 起)、F037(:585 起) 四条各自补上同一句"修复状态"行, 写法与文档现有 F038 判定行(:603, 由提交 168d04d0 引入)保持一致, 使全文档内部状态标注不再自相矛盾。

**[Critical] [C-实现状态标错] 簇 B「矿洞是一次性资源,重置对世界零作用」已闭合**

- 位置: 第 67-84 行
- 文档原文: L67 `### 簇 B — 矿洞是一次性资源, 三块常驻区域挖空即报废 (1 Critical + 3 Major)`;L69 `ResetJob 全文 148 行, tick() 只有 UNLOAD -> REGEN -> SETTLE -> DONE 四态, 而 doUnload() 只做两件事: 释放区块票 + 调离线生成器。全库 grep 不到任何删区块 / 清实体 / 重放地形的调用。`;L77-78 `这意味着**矿石是一次性资源**: 上线几天后三块区域被挖空, 矿工职业 (经济主 faucet) 产出归零, 且没有任何恢复手段。`
- 代码实际: ResetJob 已重写为 223 行,REGEN 阶段调 IInstanceManager.slideRegion 把整个实例滑到一块从未生成过的新坐标(等价于换图),矿石不再是一次性资源;已判废的离线体素管线已整条下线(GenerationScheduler.java 已删),同簇的 F021/F030/F031/F032 也一并闭合。
- 取证: src/main/java/com/miningdim/reset/ResetJob.java 共 223 行;L25 `REGEN  : 调 IInstanceManager.slideRegion 把实例整块滑到一块从未生成过的新坐标 (写回新 regionBox/seed`;L39 `限速契约: 离线生成管线已下线 (F021/F032), slideRegion 写回新 regionBox/seed 后直接置 READY;`;L145 `slidTo = MiningServices.instanceManager().slideRegion(instance.instanceId(), targetSeed);`。F030 闭合证据:src/main/java/com/miningdim/core/MobInstanceTag.java:9 `这是"这只怪算不算某个实例的刷怪额度"的<b>唯一判据</b>` 与 src/main/java/com/miningdim/pressure/MobCountAccountingGameTests.java:72-73 `"discard 不发死亡事件, 但离场事件必须把它销账 —— 实测仍留在 liveMobs 里 (F030 复现)"`。F031 闭合证据:src/main/java/com/miningdim/chunk/ChunkSystem.java:126-127 `TTL 起算点用本类自维护的 ticketEmptySinceTick, 不用 InstanceState.lastEmptyTick`。
- 建议改法: 在 L67 标题后补 `> **已闭合**: 重置改为 slideRegion 换坐标 (ResetJob.java:145), 离线体素管线整条下线 (提交 8150710a), liveMobs 改由实例标记 + 离场事件销账 (MobInstanceTag.java), 固定实例 ticket TTL 改走 ChunkSystem 自维护时刻 (ChunkSystem.java:126)。本节保留为历史记录。` 建议同时核对并更新第一章"结论"里的 Critical/Major 计数表与"两个必须优先处理的簇"表述, 使其与附录 A 中 F003/F030/F031/F032 的复核结论保持一致, 避免读者仅看摘要就误判当前仍有两个可致公服停摆的簇。

**[Major] [C-实现状态标错] C6/F001「军火台产出被 byte 截断销毁」已于 commit 61cf7ca7(2026-08-15)修复, 文档处置表与附录均未标注已闭合**

- 位置: 第 100-108, 188 行
- 文档原文: L100 `### C6 · 军火台产出被 byte 截断销毁`;L102-104 `MunitionsBenchBlockEntity.java:668 把 bufferedRounds 原样喂给 TACZ 的 AmmoItemBuilder, 代码注释断言"TACZ 会内部钳制"。复核者用 javap 反汇编 ... 确认: setCount 只有 Math.max(count, 1), **没有上限钳制**。`;L188 `| **P1 · 本周** | 军火台输出栈钳到单栈上限 (C6) | 静默销毁玩家产出 |`
- 代码实际: refreshOutputStack 已在物化后显式取 ammo.getMaxStackSize(),再对其做一道 NBT 安全上限钳制,最后按 min(bufferedRounds, itemMax) 出栈;并补上了「老档输出槽被截断成空时按缓冲重建」的分支,不再依赖第三方 builder 钳制。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:871 `int itemMax = ammo.getMaxStackSize();`;L872-876 `if (itemMax > PERSISTENCE_SAFE_MAX_COUNT) { LOGGER.warn(...); itemMax = PERSISTENCE_SAFE_MAX_COUNT; }`;L878 `int stackCount = Math.min(bufferedRounds, itemMax);`;L928-929 `于是老档里被 byte 截断成空的输出槽永不重建, 玩家几千发弹卡死在 bufferedRounds 里取不出。 if (bufferedRounds > 0 && inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {`。
- 建议改法: 在 L100 标题下方与 L188 处置表该行分别补一句收尾标注(参照本文档 L603 F038 已有的"判定 (日期, 分支)"惯例, 保持格式统一), 并给附录 A 的 F001(L222-229 段末)追加同款收尾段, 三处一次性同步, 不要只改标题不改处置表/附录:  > **判定 (2026-08-15, commit 61cf7ca7 fix(munitions): 军火台输出槽按单栈上限分栈, 修 4->5 槽迁移吞料)**: 已闭合。refreshOutputStack 物化后改为 `itemMax = ammo.getMaxStackSize()`, 超过 NBT 安全上限 `PERSISTENCE_SAFE_MAX_COUNT=127`(MunitionsBenchBlockEntity.java:853, 871-876)即钳到 127, 再取 `stackCount = Math.min(bufferedRounds, itemMax)`(:878)物化展示栈, 原先"TACZ 会内部钳制"的错误注释已随本提交删除; 另在 onAccess 补了老档兜底分支(:927-930): `bufferedRounds>0` 但输出槽因历史截断读出为空时会重新调用 `refreshOutputStack()` 补建, 覆盖 F001 复核段(L229)提到的"缓冲打满后重载即永久卡死"场景。权威计数 bufferedRounds 本身以 `putInt`/`getInt` 持久化(:997/:1053), 不受 byte 截断影响。  同时建议在文档头部或第六章说明一句维护约定: 处置表/附录条目一旦有后续提交修复, 须补"判定"收尾, 避免后续条目重复出现同样的过期口径。

**[Major] [C-实现状态标错] 4.1「特勤三条主玩法不可达 + 升级死锁」已部分闭合(F024 capability 探测源、F016 升级死锁均已修复), 仅悬赏系统(F017/F078)仍未实现——原文档 4.1 节与 L189 处置表状态已过期**

- 位置: 第 118-130, 189 行
- 文档原文: L118 `### 4.1 特勤干员: 三条主玩法全部不可达 + 职业本身升不了级`;L120-121 `- 探测源读的是**已废弃的第三方 capability** (AgentChampionData.java:51), 于是封印恒返 NO_TARGET、战术扫描对任何精英恒返 null 快照 (且空表照烧 CD)、对精英伤害加成恒不生效。`;L124 `- **职业升级死锁**: 唯一的经验入账点被"入职标志"门锁死, 而入职标志只能在 L3 时置位 —— 互为前置。`;L189 `| **P2** | 特勤 capability 探测源 + 职业升级死锁 (4.1) | 一整个职业当前不可玩 |`
- 代码实际: AgentChampionData.java 已整体删除,探测源全部改读自研 MiningChampions capability(F024 闭合);经验入账已从 isActiveAgent 门拆出、对全体合格击杀者无条件发放,死锁被显式打破(F016 闭合)。本节四条里只剩「悬赏系统整条未实现」(F017/F078)仍成立。
- 取证: src/main/java/com/miningdim/job/agent/integration/AgentChampionData.java 已不存在(删除提交 c5cd8bc9 `fix(job-agent): 特勤集成层脱离已废弃 Champions capability, 改读自研 MiningChampions`);src/main/java/com/miningdim/job/agent/integration/AgentScanProbe.java:6 与 AgentSealHandler.java:5 均为 `import com.miningdim.champion.MiningChampions;`;AgentDamageBonusHandler.java:46 `MiningChampionData champ = MiningChampions.get(victim).orElse(null);`。死锁闭合:AgentRewardHandler.java:120-125 `F016 服务端一半修法 (双重死锁): ... 拆开两道口: 经验对全体合格击杀者无条件照发 ...`,L134-136 `grantAgentKillXp(player, star, entry.getValue(), fixedPoolRaw); xpGranted++; if (AgentBountySavedData.get(...).isActiveAgent(...)) { grantAgentKillBonus(player, star);`。仍开:grantWeeklyBountyAzure 在 src/main/java 内除自身定义外零调用方。
- 建议改法: 在 docs/Full_Repo_Audit_2026-08.md L118 标题后插入说明: "> **部分闭合(2026-09 复核)**: F024 探测源已改读自研 MiningChampions(top-level import com.miningdim.champion.MiningChampions; AgentChampionData.java 已整体删除, 提交 c5cd8bc9), 封印/扫描/伤害加成三条链路均已可走通(AgentSealHandler.java:54-56/108, AgentScanProbe.java:43-44); F016 升级死锁已打破, 经验入账已从 isActiveAgent 门拆出、对全体合格击杀者无条件发放(AgentRewardHandler.java:120-137), 仅信用点加成与伤害加成两笔真福利仍需入职标志。**仍未闭合**: 悬赏系统整条未实现(F017/F078), BountyDefinition/BountyProgress 零生产调用点, grantWeeklyBountyAzure(AgentRewardHandler.java:214)全库零调用方, 状态见 docs/SpecialAgent_Job_DesignSpec.md:204(该节已自行标注 DEFERRED/PENDING)。" 同时将 L124「职业升级死锁」与 L120-121「探测源读的是已废弃的第三方 capability」两条从"现状描述"改为"历史成因(已修复)"措辞, 避免读者按当前文字误判职业仍整体不可玩。第六章 L189 处置表该行改为: "| **已闭合** | ~~特勤 capability 探测源 + 职业升级死锁 (4.1)~~ 仅剩悬赏系统(F017/F078)未实现 | 见 4.1 节更新说明 |", 或迁移到"已处置"栏目而非仍列在 P2 待办栏, 防止排期表按过期状态重复分配工时。

**[Major] [B-文档互相打架] 「青辉石实际产量恒为 0」与本文 F099 及代码直接矛盾**

- 位置: 第 127 行
- 文档原文: L127 `"青辉石唯一来源 = 周常悬赏" —— 即**青辉石当前实际产量恒为 0**。`
- 代码实际: 6★+ 精英怪击杀是一条一直存在的青辉石产出通道,与悬赏无关。本文档自己的 F099(L1209-1216)整条就是在讨论这条通道的分配口径(`青辉石按合格人头复制发放而非瓜分`),两处结论互相打架:F099 若成立,青辉石产量就不可能恒为 0。代码侧该通道在基线时即已存在,现在还按 F099 的建议改成了按权重瓜分。
- 取证: src/main/java/com/miningdim/champion/reward/ChampionReward.java:56-62 `public static long azureDrop(int star) { requireStar(star); if (star < AZURE_MIN_STAR) { return 0L; } return AZURE_BASE_AT_MIN_STAR + (long)(star - AZURE_MIN_STAR) * AZURE_PER_STAR_ABOVE_MIN; }`;src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:147-151 `boolean dropsAzure = ChampionReward.dropsAzure(star); long azurePoolRaw = dropsAzure ? ChampionReward.azureDrop(star) : 0L; Map<UUID, Long> azurePayout = azurePoolRaw > 0L ? ContributionPool.distribute(...)`;同文件 L169-171 `if (azureShare > 0L) { EconomyServices.economyService().grantAzureDaily(player, azureShare, EconomyConstants.AZURE_DAILY_FAUCET_CAP);`。本文档内部对照:L1213 `#### F099 · 青辉石按合格人头复制发放而非瓜分` 的证据栏写着 `青辉石是 EconomyServices.economyService().grantAzureDaily(player, azureAmount, AZURE_DAILY_FAUCET_CAP)`。
- 建议改法: 把 L127 改为: "青辉石唯一来源 = 周常悬赏" —— 该表述与实现不符: 6★+ 精英怪击杀是另一条实际存在的青辉石产出通道 (ChampionReward.azureDrop / ChampionRewardHandler.grantAzureDaily, 现按 F099 修复后的权重瓜分逻辑发放, 见本报告 F099)。悬赏这一条产量为 0, 但青辉石总产量不为 0; 设计文档十一章的"唯一来源"口径需要与精英怪掉落对齐, 或改口为"悬赏这一条设计来源尚未实现, 当前青辉石唯一实际生效来源是精英怪击杀"。

**[Major] [C-实现状态标错] 4.2「静态陷阱与出生点池整套死代码」已闭合**

- 位置: 第 132-138 行
- 文档原文: L132 `### 4.2 矿洞: 静态陷阱与出生点池整套死代码`;L134-136 `- StaticTrapGenerator (238 行) + TrapParams 的难度因子/致死密度/间距数值表零生产调用方: 三块矿洞里没有任何静态陷阱, **矿工花技能点解锁的 L5/L8 陷阱探测永远显示"无陷阱"**`;L137-138 `- 出生点候选池与 60 tick 占位 TTL 整套死代码: 同一难度的所有玩家**恒定落在同一格**`
- 代码实际: StaticTrapGenerator.java 已整体删除,静态陷阱改走 worldgen 真实方块路线(trap 包现有 TrapDebugPlacement / TrapDisguise / TrapRegistry / block 子包);出生点改为真实世界预扫描并配了分布用例。F033/F034 均已闭合。
- 取证: src/main/java/com/miningdim/trap/StaticTrapGenerator.java 已不存在;现 trap 包内容为 DangerSource.java / DynamicTrapEngine.java / StaticTrapKind.java / StaticTrapPlacement.java / StaticTrapTrigger.java / TrapDebugPlacement.java / TrapDisguise.java / TrapDisguiseConverter.java / TrapGameTests.java / TrapParams.java / TrapRegistry.java / TrapSystem.java / TrapType.java / block。出生点:src/main/java/com/miningdim/spawn/SpawnDistributionGameTests.java 已存在;删除提交 9e8ca867 `fix(miningdim): 出生点池改回真实世界预扫描, 消除同格叠压并注销死信道包 (F034/F087)`。
- 建议改法: 在 L132 标题后补: "> **已闭合**: 静态陷阱改走 worldgen 真实方块方案C (StaticTrapGenerator.java 已删; 现由 trap/block/TrapOreBlock.java 经 worldgen/configured_feature/trap_*.json 真实布点, TrapDisguiseConverter 于区块加载时换皮并登记 TrapRegistry, job/miner/TrapScanService 改查 TrapRegistry 使 L5/L8 陷阱探测恢复真实命中); 出生点改真实世界预扫描 (entry/EntryGateway.java:262 completeEnter 现直接调用 MiningServices.spawnService().findSpawn, 由 spawn/SpawnPool+SpawnSystem 提供候选池与占用TTL, 消除同格叠压), 并补齐 spawn/SpawnDistributionGameTests.java 五组回归 (提交 9e8ca867)。" 同步在 F033/F034 详细条目 (docs/Full_Repo_Audit_2026-08.md 第545/555行) 追加"已闭合"标注, 避免读者仍按旧证据行号 (TrapSystem.java:129 / EntryGateway.java:309) 去核对已不存在的代码路径。可选补充一句: TrapParams 中难度因子/致死密度/间距四个具体方法目前仍无生产调用方 (设计文档标注为阶段2 PENDING 初值), 与本次已闭合的两个症状是独立遗留项, 不影响本条闭合结论。

**[Major] [C-实现状态标错] 4.3「酿酒师不联动农夫经济」已闭合,干小麦改吃 farmer_wheat**

- 位置: 第 142-145 行
- 文档原文: L142-145 `- **酿酒师宣称的"联动农夫经济"在代码里不存在**: dried_wheat 配方与九条酿造配方吃的全是 minecraft:wheat 等原版物品, 没有一条引用 FarmerItems.FARMER_WHEAT。... 反过来农夫的 farmer_wheat 至今只有 /farmer sell 一个出口 —— 两个职业之间没有任何真实供给耦合。`
- 代码实际: dried_wheat 配方的 ingredient 已改成 miningdim:farmer_wheat,酿造站也已消费 FarmerItems.FARMER_WHEAT,供给耦合已落地(F029 闭合)。
- 取证: src/main/resources/data/miningdim/recipes/brewer/dried_wheat.json:4-5 `"ingredient": { "item": "miningdim:farmer_wheat" }`;src/main/java/com/miningdim/job/brewer/station/BrewingStationGameTests.java:94/100/105/110/116 一系列 `new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16/32/24/24/16)` 直接喂酿造站槽位。
- 建议改法: 在 L142 该条目前补 "(已闭合) " 前缀,并把正文改为:"dried_wheat 配方与酿造站投料已改吃 miningdim:farmer_wheat (recipes/brewer/dried_wheat.json:5, BrewRecipes.java:77-96), 农夫耕地上限现已成为酿酒吞吐的上游闸; BrewingStationGameTests.java 已有回归测试锁死原版小麦不再命中任何配方(F029 commit 8a0f9bd4); 数值配平须以 Economy_BalanceSheet 为准。" 同步在附录 A 第 505-508 行的 F029 词条末尾追加"已于 8a0f9bd4(2026-08-16)修复关闭"，避免"复核: 维持"与代码现状继续冲突。

**[Major] [C-实现状态标错] 4.3「枪匠冲压+装配整条链零职业等级门、零经济 sink」已闭合(F048 已修, 文档正文与附录 A 均未同步)**

- 位置: 第 146-147 行
- 文档原文: L146-147 `- **枪匠冲压+装配整条链零职业等级门、零经济 sink**: 新号拿到材料就能直冲传奇零件装满配枪, 且整条造枪链一分钱 sink 都没有, 是把矿物转成高价值战斗成品的净产出通道。`
- 代码实际: 冲压侧已接 MunitionsLevels 的品质解锁等级门,并在开工路径接了 EconomyServices.tryCharge 的 CREDIT 工费 sink,两条缺口都已补(F048 闭合)。
- 取证: src/main/java/com/miningdim/job/munitions/block/GunsmithPressBlockEntity.java:181-184 `int level = MunitionsLevels.munitionsLevel(player); if (!MunitionsLevels.isPartQualityUnlocked(level, quality)) { ... MunitionsLevels.partQualityUnlockLevel(quality)), true);`;同文件 L223-226 同一道门在另一入口;L240 `if (!tryChargeWorkFee(player, fee)) {`;L357-361 `private boolean tryChargeWorkFee(ServerPlayer player, long cost) { ... return EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost);`。
- 建议改法: 把 docs/Full_Repo_Audit_2026-08.md 第146-147行改为: `- ~~枪匠冲压+装配整条链零职业等级门、零经济 sink~~ **(已闭合)**: 冲压侧已接品质等级门(GunsmithPressBlockEntity.java:181-184 trySelectQuality 与 :223-226 tryStartPreview 二次校验)与 CREDIT 工费 sink(:240 调用 / :357-361 tryChargeWorkFee -> EconomyServices.tryCharge); 装配侧同样已接等级门(GunsmithAssemblyBenchBlockEntity.java:256-259 isAssemblyUnlocked)与 CREDIT 工费 sink(:299 调用 / :316-320 tryChargeWorkFee)。` 同时更新附录 A 第699-706行 F048 词条: 其"复核"部分引用的 `trySelectQuality(int)` 单参签名已不存在(现为 `trySelectQuality(int index, ServerPlayer player)` 且内含等级门), 该词条应从"缺口客观存在但当前不可达"改判为"已闭合", 并保留/注明 MunitionsConfig.GUNSMITH_ENABLED 默认仍为 false(MunitionsConfig.java:262)这一前提, 避免读者误以为功能已面向玩家全面开放。

**[Major] [C-实现状态标错] 4.4 婚姻条目里 escrow 与 MarriageState 四字段两项已闭合, 仅"预约场地"仍开, 三者状态混写误导读者**

- 位置: 第 151-152 行
- 文档原文: L151-152 `- 婚姻: 离婚缺 escrow 公示期且共享背包**无条件归发起方** —— 文档点名要防的"离婚资产抢劫"成了默认行为; "预约场地"未实现; MarriageState 四个持久字段全库零写入方 (存档里躺着自洽但完全错误的数据)。`
- 代码实际: 离婚已改成「提交 -> 公示期 -> 生效」三段式并带撤回/提前确认与公示期冻结(F019 闭合);MarriageState 的四个零写入方字段与其 setter 已删除(F097 闭合)。同条里只有「预约场地未实现」(F068)仍成立,读者无法区分。
- 取证: src/main/java/com/miningdim/marriage/MarriageDivorce.java:25-28 `三段式流程 (提交 -> 公示期 -> 生效), 对应设计文档"仿取款 escrow" ... MarriageState#beginPendingDivorce 开公示期 -> 强关双方共享背包窗口 (公示期冻结从这一刻开始)`;src/main/java/com/miningdim/marriage/MarriageCommands.java:28-30 `/marriage divorce 提交离婚 (扣 divorceCost, 进公示期...) / divorce cancel 发起方在公示期内撤回, 全额退款 / divorce confirm 配偶提前确认`;setSharedInvLevel / setTeleportLevel / setDivorceCount / setLastWeddingTick 在 src/main/java/com/miningdim/marriage/ 下已零命中。仍开:marriage 包内 venue/场地 相关标识仍零命中。
- 建议改法: 把 docs/Full_Repo_Audit_2026-08.md:151-152 拆成三条独立陈述并分别标注现状(而非全部用现在时描述为"缺失"): - 婚姻 · 已闭合(F019): 离婚已改为"提交(file)-> 公示期(beginPendingDivorce)-> 生效"三段式, 支持发起方在公示期内 `/marriage divorce cancel` 全额撤回、配偶 `/marriage divorce confirm` 提前确认(MarriageDivorce.java:26-30/95/128/165/218, MarriageCommands.java:63-68); 共享背包清算按 `depositorOf(slot)` 放入者归属分配, 无归属槽按槽号奇偶平分, 已非"无条件归发起方"(MarriageDivorce.java:317-334)。 - 婚姻 · 已闭合(F097): `MarriageState` 的 `sharedInvLevel`/`teleportLevel`/`divorceCount`/`lastWeddingTick` 四个零写入方字段与对应 setter 已整体删除, 等级现算, 离婚次数改由 `MarriageHistory` 持有(MarriageState.java:20-26)。 - 婚姻 · 仍存在(F068): "预约场地"未实现, marriage 包内 grep `venue`/`场地` 零命中, 与 `Marriage_System_DesignSpec.md:38` 的设计要求(SavedData 登记场地坐标+时段)不符。 同时建议核对并更新第405-408行 F019"复核"小节的结论文本, 其证据引用(settleSharedBackpack 全部归发起方)已与当前代码不符, 属同一批需要刷新的过期状态标记, 但该条目本身不在本次待核范围, 仅口头提示由文档维护者定夺。

**[Major] [C-实现状态标错] 4.4 摘要(L153)与 F066(L879-886)仍称"实体堆叠拆分/拴绳两条 MUST 完全没实现", 但同日晚些的 f1659a59+06f70ecb 提交已落地 StackSplit 并补齐 GameTest �covered, 文档未同步标注闭合**

- 位置: 第 153, 879-886 行
- 文档原文: L153 `- 实体堆叠: 规格里两条 MUST (拆分语义、拴绳语义) 完全没实现, 玩家无法从堆叠里取出单只动物。`;L883 `整个 stacking 包对 'split' / 'lead' / 'LEASH' / 'SHEARS' 之外的分离交互 grep 零命中, 没有任何分离工具、分离交互或拴绳语义代码。`
- 代码实际: stacking 包已新增 StackSplit.java 与 StackingInteractionGameTests.java,拆分已实现并被 StackMerge 的合并期闸显式引用(拆分保护期 NoMergeUntil、被拴住即不参与合并)。
- 取证: src/main/java/com/miningdim/stacking/ 现含 StackSplit.java 与 StackingInteractionGameTests.java;src/main/java/com/miningdim/stacking/StackMerge.java 类注释 `canMerge 额外叠加两条【仅合并期】瞬时态闸 (被拴住 / 拆分保护期未到)` 与 `{@link StackSplit#splitOne} 会给拆出的单个体写 StackSize=1 (拆分保护期用的合法数据)`;canStack 被 `{@link StackDeath}/{@link StackPassive}/{@link StackBreed}/{@link StackSplit} 四个结算 handler 共用`。
- 建议改法: 将 L153 改为: `- ~~实体堆叠: 规格两条 MUST 未实现~~ **(已闭合, f1659a59)**: 拆分见 StackSplit.splitOne(空手潜行右键), 拴绳二选一语义见 StackingConfig.LEASH_MODE(SPLIT_ONE/WHOLE_STACK)并落入 StackMerge.canMerge 的仅合并期闸(isLeashed/NoMergeUntil), 覆盖用例见 StackingInteractionGameTests.java 的 f066SplitOnePeelsSingleIndividual/f066SplitGraceBlocksImmediateReabsorption/fr52LeashModeSplitOneVsWholeStack。` 同步在 F066(L879-886)标题前加 `[已闭合]`, 并在"复核"行(886行)补一条: "复核: 已闭合 —— f1659a59(2026-08-15 18:43,晚于本报告落笔)新增 StackSplit.java 并接入 StackingSystem.register(72行)与 StackMerge.canMerge/canStack, 配套 StackingConfig.LEASH_MODE/SPLIT_GRACE_TICKS 配置键与 StackingInteractionGameTests 四个 GameTest, FR-5.1/FR-5.2 均已实现, 原判据(register 只挂三个 handler/配置无对应键)已随之失效, 不再计入未闭合缺口统计。" 同时建议在文档顶部的统计口径(如有"未闭合 Minor 计数")里相应扣减本条, 避免遗留计数与正文脱节。

**[Major] [C-实现状态标错] 4.4「handshake() 零调用点」已闭合,首屏已接线(附录 A F056 需同步)**

- 位置: 第 156 行
- 文档原文: L156 `- WebUI: 启动握手自检 handshake() 实现完整但**零调用点**, 契约漂移没有任何可观测信号。`
- 代码实际: handshake() 已在外壳首屏被调用,契约漂移已有可观测入口(F056 闭合)。
- 取证: webui/src/components/shell/TabletShell.tsx:255 `handshake()`;定义处仍在 webui/src/lib/bridge.ts:543 `export async function handshake(): Promise<HandshakeReport> {`。全库对 `handshake(` 的命中已从「仅定义处」变为「定义处 + TabletShell 调用点」。
- 建议改法: 把 docs/Full_Repo_Audit_2026-08.md:156 改为: `- ~~WebUI: 启动握手自检 handshake() 零调用点~~ **(已闭合, 见 commit 8a6f622e)**: 已在外壳首屏接线并显形 (webui/src/components/shell/TabletShell.tsx:255 调用, :452-460 渲染"契约漂移"/"契约自检失败"顶栏警告; 定义处 webui/src/lib/bridge.ts:543)。` 同时把附录 A 中 F056 条目(docs/Full_Repo_Audit_2026-08.md:779-786)的"复核: 维持——事实属实"一行追加闭合注记，避免正文与附录出现新的互相矛盾(B 类问题)。

**[Major] [C-实现状态标错] 4.4 与 F062 的 affix_setting 35 个死 JSON 已整目录删除**

- 位置: 第 157-158, 839-846 行
- 文档原文: L157-158 `- data/miningdim/affix_setting 下 35 个 JSON 声明的 champions 类型在其注册表里全不存在, 恒为死数据, 却每次 reload 被解析并随握手包全量发给每个客户端。`;L842 `- **位置**: src/main/resources/data/miningdim/affix_setting/uhmwpe_armor.json:2`
- 代码实际: src/main/resources/data/miningdim/ 下已不存在 affix_setting 目录,35 个文件整体删除,F062 已按其建议闭合。
- 取证: `ls src/main/resources/data/miningdim/` 实际内容为 damage_type / dimension / dimension_type / fishing / gunsmith / loot_modifiers / loot_tables / recipe_filters / recipes / structures / tags / tarot / worldgen —— 无 affix_setting;文档 L842 指向的 uhmwpe_armor.json 路径已失效。
- 建议改法: 把 L157-158 改为 `- ~~data/miningdim/affix_setting 下 35 个 JSON ...~~ **(已闭合)**: 整目录已删除, 见提交 18723a9c (2026-08-16, "fix(core): 清理 miningdim 死数据 affix_setting 并补全 mods.toml 兼容声明 (F061/F062)")。` 并在 F062 条目(约 L845-846)下补一行 "**复核**" 同格式的闭合批注: `- **复核**: 已闭合 — data/miningdim/affix_setting 整目录(含 L842 指向的 uhmwpe_armor.json)已随提交 18723a9c 删除, 文中路径已失效; 沿用本文档给 F038 追加"按决策关闭"批注的既有写法即可。

**[Major] [C-实现状态标错] 4.4 与 F063 的 regionSizeChunks/bufferChunks「零消费方」已闭合**

- 位置: 第 159, 849-856 行
- 文档原文: L159 `- instance.regionSizeChunks / bufferChunks 两个带 worldRestart 语义的配置项零消费方 —— 对运维撒谎。`;L853 `真正决定网格几何的是编译期常量: RegionGrid 第 35-36 行的无参构造直接取 MiningConstants.REGION_STRIDE_X/Z`
- 代码实际: RegionGrid 的无参构造已改成从 IMiningConfig 读这两个键派生 sizeX/sizeZ/gap,MiningConstants 退为默认值镜像,配置项已有真实消费方。
- 取证: src/main/java/com/miningdim/instance/RegionGrid.java:25-26 `网格步长现在由 config 的 instance.regionSizeChunks / instance.bufferChunks 派生 (worldRestart, 见 IMiningConfig), MiningConstants 里的编译期常量只作默认值镜像, 不再是运行期几何的唯一来源 (F063)。`;同文件 L46 `this(MiningServices.config().regionSizeChunks(), MiningServices.config().bufferChunks(),`;L51-54 `public RegionGrid(int regionSizeChunks, int bufferChunks, int originX, int originZ) { this.sizeX = regionSizeChunks * 16; this.sizeZ = regionSizeChunks * 16; int gap = bufferChunks * 16;`。
- 建议改法: 把 L159 改为 `- ~~instance.regionSizeChunks / bufferChunks 零消费方~~ **(已闭合)**: RegionGrid 已改由 config 派生几何 (RegionGrid.java:46/51-54, ModConfig.java:47-53, InstanceManager.java:95 生产路径确认走该构造, RegionSlideGameTests.java:58-77 有专门回归测试)。` 并在 F063 条目(第 849-856 行)下补一行同样的修复状态说明,同时可在证据里补充：InstanceManager.java:252-256 保留 MiningConstants.REGION_STRIDE_X/Z 仅用于兼容"F063 落地前"存档的一次性迁移匹配(claimFixedByLegacyGeometry),不代表 config 值未被消费,避免后续读者误判为矛盾。

**[Major] [A-文档与代码不符] F007(联动 F043)页面授权已于 2026-08-16 由 WebUiPageUrl 归一化 + screenOpen 拆分闭合, 审计报告(定稿于 2026-08-15)未回填, 状态标记已过期**

- 位置: 第 285-292 行
- 文档原文: L289 `WebUiBridge.onQuery 第 110-113 行: if (allowed == null || cefBrowser == null || frame == null || !frame.isMain() || !allowed.equals(cefBrowser.getURL())) { ... } —— allowed 是 WebUiClient.openWebUi 第 102 行原样传入的 MiningClientConfig.WEBUI_URL.get() 字面量`
- 代码实际: 登记值与 CEF 实时回读值都改走 WebUiPageUrl 的归一化/匹配,不再是整串 equals;并补了失败日志打印双方实际值。
- 取证: src/main/java/com/miningdim/client/webui/WebUiBridge.java:121 `登记值经 {@link WebUiPageUrl#normalize} 归一化后存入; onQuery 侧对 CEF 实时回读的文档 URL 也过`;L132 `this.allowedPageUrl = WebUiPageUrl.normalize(pageUrl);`;L151-152 `if (allowed == null || cefBrowser == null || frame == null || !frame.isMain() || !WebUiPageUrl.matchesNormalized(allowed, cefBrowser.getURL())) {`;L159 `LOGGER.warn("WebUI 页面授权失败: 配置/登记页面为 {}, 浏览器实际停在 {}", allowed, actual);`。
- 建议改法: 在 F007 条目(docs/Full_Repo_Audit_2026-08.md 第288-292行"复核"之后)按本文档既有体例追加一行: "**判定 (2026-08-16, 提交 59215eb2/ec6ca4e9)**: 已闭合, 不再需要修复。授权比对改为登记侧与 CEF 实时回读侧统一走 WebUiPageUrl.normalize/matchesNormalized 归一化(WebUiBridge.java:132 登记时归一化, :151-152 比对时归一化), 覆盖尾斜杠/大小写/默认端口/百分号编码/fragment 等字面差异, 并在拒绝时打印双方实际 URL 供排障(:159); 归一化规则与边界情形由 WebUiPageUrlGameTests.java 三个 GameTest 方法锁定。"  同时在 F043 条目(第649-662行"复核"之后)追加: "**判定 (2026-08-16, 提交 59215eb2/ec6ca4e9)**: 已闭合。授权(allowedPageUrl)与界面开关(screenOpen)已拆成两个独立状态, onScreenClosed 不再清空 allowedPageUrl(WebUiBridge.java:389-390), 关屏后的请求改由顶部的 F043 关屏门以独立失败码 -4 就地短路(:191), 在途请求逐个回 -5(F044 同批解决), 不再复用 -3 的'页面不可信'语义。"  （如仓库有专门跟踪"已闭合审计项"的清单, 建议同批把这两条从"未决 Major"计数里移出, 避免后续统计仍把它们算进开放缺陷总数。）

**[Major] [A-文档与代码不符] F009/F010/F025 三条归属口径缺陷均已闭合**

- 位置: 第 305-312, 315-322, 465-472 行
- 文档原文: L309 `onBenchBroken 只判 event.getState().getBlock() instanceof MunitionsBenchBlock, 然后 MunitionsSavedData.get(overworld).decrement(player.getUUID()) —— 既不查 BE 的 ownerUUID`;L319 `trySelectCaliber 的门是 if (!canAccess(player)) return false;`;L469 `onFarmlandBroken 唯一动作是 FarmerSavedData.get(overworld).decrement(player.getUUID()) —— 扣的是【破坏者】的计数。FarmerSavedData 里只有 Map<UUID,Integer> placedCounts ... 全库没有任何 pos->放置者 的归属记录`
- 代码实际: 三条全部闭合:军火台计数回收改按方块实体记录的 owner 扣减并补了非玩家破坏路径;trySelectCaliber 的门已收敛成 isOwner;农夫侧已建 pos->owner 归属表。
- 取证: src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlock.java:246 `MunitionsSavedData.get(serverLevel.getServer().overworld()).decrement(owner);`(MunitionsSystem 内已无 onBenchBroken);src/main/java/com/miningdim/job/munitions/MunitionsGameTests.java:1713 `覆盖爆炸/指令这类没有 PlayerInteractEvent/BreakEvent 的破坏路径。`;src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:382-383 `// ownerLevelCache; 选口径须收敛到台主。canAccess 仍保留给开 GUI/取物用, 这里不能借用。 if (!isOwner(player)) {`;src/main/java/com/miningdim/job/farmer/FarmerSavedData.java:129 `public UUID ownerOf(ResourceLocation dimension, BlockPos pos) {`;src/main/java/com/miningdim/job/farmer/FarmerGameTests.java:1464-1467 `"台主的耕地被他人破坏后, 台主的配额必须真回收" / "破坏者破坏他人耕地不得动自己的配额"`。
- 建议改法: 分别在 F009(第309-312行)/F010(第319-322行)/F025(第469-472行)复核段落后补一行 `- **修复状态**: 已闭合`, 并附对应代码证据: F009 附 MunitionsBenchBlock.java:241-248(onRemove 内 :246 按 BE owner decrement)与 MunitionsGameTests.java:1710-1713(benchRemovalRecoversCountForOwnerNotBreaker); F010 附 MunitionsBenchBlockEntity.java:379-385(trySelectCaliber 已收敛 isOwner); F025 附 FarmerSavedData.java:71/129(farmlandOwners/ownerOf)与 FarmerFarmlandBlock.java:58-69(onRemove -> releaseFarmland)、FarmerGameTests.java:1456-1471。同时把第190行处置顺序表 P2 行 `农夫耕地计数归属、耕地档位门分叉、酒窖燃料债` 标注为已闭合, 分别附 F025(如上)、F026(FarmerSystem.java:271-277 与 FarmerTier.java:77-78 的 yieldFor 统一裁决)、F027(docs/Brewer_Job_DesignSpec.md:65 的"F027 二段修复落地"记录 + CellarSettle.java:32-33 不变式 + BrewerConstants.java:70 FUEL_SLOT_CAPACITY=6192)三条独立证据。

**[Major] [A-文档与代码不符] F013 · 管理后台读 mock 种子身份已闭合,AdminPage.tsx 现全文读 player.profile 真契约(0 处 world.player)**

- 位置: 第 345-352 行
- 文档原文: L349 `AdminPage 却直接读它: const [target, setTarget] = useState(world.player.name) (:1093)、.filter((name) => name !== world.player.name) (:1117)、... <Tag tone={world.player.isOp ? 'success' : 'danger'}> (:1153)`
- 代码实际: webui/src/pages/admin/AdminPage.tsx 全文对 `world.player` 已零命中,mock 种子身份已从生产路径摘掉。
- 取证: 在 webui/src/pages/admin/AdminPage.tsx 内检索 `world.player` 零结果(文档 L349 列举的 :1093/:1117/:1119/:1135/:1153/:1160 六处全部不存在)。
- 建议改法: 在 F013 条目(docs/Full_Repo_Audit_2026-08.md 第345-352行)下补充一行"修复状态"说明:`- **修复状态**: 已闭合(提交 8a6f622e "fix(webui): 前端契约对齐真数据源, 核销 F012/F013/F014/F055-F060") —— AdminPage.tsx 已改为 useMockAction('player.profile', {}) 读取 selfName/isOp/jobs(:1101-1105),全文对 world.player 零命中(原文列举的 :1093/:1117/:1119/:1135/:1153/:1160 六处均已不存在);仓库已固化回归检查 webui/scripts/check-frontend-contract.mjs 的 'F013-F058-mock-identity-leak' 断言(pages/** 下 world.player/world.jobs 必须为 0)防止复发。` 无需改变原发现文字本身(作为历史记录保留),只需在其后追加闭合状态,避免读者把这条 Major 误当作当前仍存在的缺陷。

**[Major] [A-文档与代码不符] F041 market.categories 描述已与现行契约不符**

- 位置: 第 626-633 行
- 文档原文: L630 `MarketActions.java:496 CATEGORIES = (sender, payload) -> GSON.toJson(MarketCategoryTree.build()), 无分页无裁剪; MarketCategoryTree.java:115-128 对 ForgeRegistries.ITEMS.getKeys() 全量枚举`;L631 `**影响**: 跳蚤市场面板左栏分类筛选在任何真实服务器上都拿不到数据`
- 代码实际: CATEGORIES 已改为只回骨架 buildSkeleton(),叶子拆到新 action market.categoryItems 分页取,32767 硬闸不再被撞。该契约变更已被 docs/WebUI_Frontend_Wiring_Checklist.md:146 记录为「F041 修复 (fix/market-payload)」,但本报告 F041 条目本身仍是旧描述。
- 取证: src/main/java/com/miningdim/market/MarketActions.java:506 `static final WebUiAction CATEGORIES = (sender, payload) -> GSON.toJson(MarketCategoryTree.buildSkeleton());`;同文件 L40-43 `market.categories 与 market.categoryItems 的分工: 前者只回分类骨架 (六个固定顶层 + ores 三个固定子分类, 各带 ...)`;L513 `某分类节点 (含其后代) 的叶子分页 (与 market.categories 配套...)`。跨文档旁证:docs/WebUI_Frontend_Wiring_Checklist.md:146 `**F041 修复 (fix/market-payload) 起, 回执形状不再是含叶子的树**`。
- 建议改法: 在 F041 条目(docs/Full_Repo_Audit_2026-08.md:626 起)的"复核"栏(L633)后补一行: "**修复状态**: 已闭合(分支 fix/market-payload, 提交 f3aa32ee/9e6f1a52, 经 95522c94 merge 进 main) —— CATEGORIES 改回骨架 MarketCategoryTree.buildSkeleton()(MarketActions.java:506), 叶子改由新 action market.categoryItems 分页取(MarketActions.java:509-514); 前端接入状态见 docs/WebUI_Frontend_Wiring_Checklist.md:146 的 B8/B8b(前端仍未跟进, 玩家侧症状从报错变为空目录)。" 同理在 F110(docs/Full_Repo_Audit_2026-08.md 约 L1322 段落)的复核文字里, 对其仍引用的"MarketActions.java:496 裸 GSON.toJson(MarketCategoryTree.build())"补一句"该行已随 F041 修复(f3aa32ee)失效, 现为 buildSkeleton() 骨架调用, 但 F110 复核给出的服务端体积裸奔判断本身在修复前成立, 结论不受影响, 仅证据行号需更新为 MarketActions.java:506"。

**[Major] [C-实现状态标错] C3「30 天回收删开箱归属凭据」已通过 schema V3 闭合, 主体段(L89-98)与附录 F006(L276-279)均需回补闭合状态**

- 位置: 第 89-98, 186 行
- 文档原文: L89 `### C3 · 30 天回收把开箱资产凭据一起删了`;L91-93 `EconomySystem.java:110 在 ServerStarted 内无条件调 pruneTerminalOperations(now - 30天), 而 SqliteEconomyLedger 的 SQL 是 DELETE FROM bundle_operations WHERE status IN (...) AND created_at < ? , **没有 domain 过滤** —— 开箱的幂等行与经济的终态行共用这张表。`;L186 `| **P0 · 立刻** | 开箱幂等行不再被 30 天回收误删 (C3) | 公服跑满 30 天必然发生, 且会重复扣款 |`
- 代码实际: 回收 SQL 本身确实没变(仍无 domain 过滤),但开箱侧的归属/结算锚已通过 schema V3 迁出到 case_openings.economy_settled 列,不再依赖可回收的 bundle_operations 行;并补了一次性回填以覆盖直升存档,重复扣款链已断。
- 取证: src/main/java/com/miningdim/store/MiningSchema.java:140 `版本 3: 给 {@code case_openings} 加 {@code economy_settled} 列, 把开箱结算的幂等锚从可回收的`;同文件 L184 `"UPDATE case_openings SET economy_settled=1 WHERE status='COMMITTED' AND NOT EXISTS "`;src/main/java/com/miningdim/caseopening/CaseOpeningService.java:274 `结算锚在开箱库自身 (economy_settled), 账本行是否已被 EconomySystem 定期回收与本次判定`;src/main/java/com/miningdim/economy/EconomySystem.java:106-109 `存档若停在 user_version=1 直升 3 ... 必须补跑一次同一套判据把它们追平, 否则这批玩家会在保留期后被真实重复扣款`;同文件 L110 `MiningSchema.backfillCaseEconomySettled(MiningStore.connection());`。
- 建议改法: 在 L89 标题后补充闭合说明: `> **已闭合 (schema V3, 提交 2b9b5f84)**: 开箱结算锚已迁到 case_openings.economy_settled(MiningSchema.java:140-185), 并由 EconomySystem.java:106-109 的 backfillCaseEconomySettled 覆盖 user_version=1 直升 3 与旧库导入两类迟到数据; CaseOpeningService.resume()(:274-276)与 isEconomySettled()(:534-536)已改读该列, 不再依赖可回收的 bundle_operations 行。bundle_operations 的 30 天回收(SqliteEconomyLedger.java:193-194)本身保持不变、仍无 domain 过滤, 但已不再影响开箱归属判定。`  同时: 1) 把 L186 处置顺序表里"开箱幂等行不再被 30 天回收误删 (C3)"那一行标注为已闭合(或移出 P0, 移到已完成清单), 避免继续占用 P0 优先级误导排期。 2) 同步更新附录 A 的 F006 条目(现 L276-279): 其"证据"与"复核"两段仍在描述修复前的旧实现(`CaseOpeningService.java:469-471 isEconomySettled(asset) 判的是 economy.state(ownerId, sourceOpeningId) == COMPLETED`), 与当前代码(CaseOpeningService.java:534-536 只读 dao.isOpeningSettled)不符, 需要一并加闭合注记, 否则读附录细节的人仍会被引导去看一个已经不存在的实现路径。

### `docs/TaskSpec_INDEX.md`

**[Major] [A-文档与代码不符] 五份 TaskSpec 系列文档(INDEX/DeathRules/EntryFee/NoRespawnPoint/Quest, 共9处)钉死的 JAVA_HOME 路径在本机不存在; Economy_SQLite_Migration_Plan.md 的同类路径因文档性质不同需单独处理**

- 位置: 第 35 (同源 9 处: DeathRules 19/215, EntryFee 19/223, NoRespawnPoint 18/163, Quest 19/292) 行
- 文档原文: - `JAVA_HOME=C:\Users\Xiaoxiao\.gradle\jdks\eclipse_adoptium-17-amd64-windows\jdk-17.0.18+8` (本机默认 Java 21 会在配置阶段报 `Unsupported class file major version 65`)。
- 代码实际: 本机用户目录是 C:\Users\Shinoyuki, `C:\Users\Xiaoxiao\` 整个目录不存在; `C:\Users\Shinoyuki\.gradle\jdks\` 下也只有 CACHEDIR.TAG 与 latest, 没有 eclipse_adoptium-17-amd64-windows。照抄这行 set JAVA_HOME 只会得到一个指向空路径的环境变量, 随后 Gradle 仍按本机默认 Java 21 走, 撞的正是文档自己警告的 major version 65。
- 取证: 无法给出代码侧行号 —— 这是环境路径而非代码断言, 取证方式是文件系统: `ls C:/Users/Xiaoxiao/` 返回 "No such file or directory"; `Get-ChildItem C:\Users\Shinoyuki\.gradle\jdks -Force` 只返回 CACHEDIR.TAG 与 latest。旁证: 仓库 gradle.properties 不含任何 java installations 路径配置, 所以 JAVA_HOME 确实是唯一入口。
- 建议改法: 分两类处理, 不要用同一种改法:  1) docs/TaskSpec_INDEX.md:35、TaskSpec_Mining_DeathRules.md:19,215、TaskSpec_Mining_EntryFee.md:19,223、TaskSpec_Mining_NoRespawnPoint.md:18,163、TaskSpec_Quest_WebUI_Panel.md:19,292 共 9 处 —— 这些是尚待执行/可直接开工的活文档, 直接把硬编码路径替换为仓库自己在 README.md:33-43 已给出且经核实可用的通用写法, 例如: "JAVA_HOME 指向本机任意 JDK 17(Temurin 17 即可); 本机默认 Java 21 会在配置阶段报 `Unsupported class file major version 65`。找不到已注册 JDK 时改用 `gradlew.bat build -Porg.gradle.java.installations.paths=<JDK17 目录>`(见 README.md 构建步骤第 2 条)。"  2) docs/Economy_SQLite_Migration_Plan.md:19,28 —— 该文档第 3、7 行已明确声明"六个阶段全部完成"且"保留原始计划正文供追溯", 是历史交接记录而非待执行入口, 不建议直接改写原文本身(会破坏其作为历史快照的可追溯性)。建议在"零 施工环境"小节末尾追加一条不改动原文的说明性脚注, 例如: "(注: 以上 worktree 路径与 JAVA_HOME 为 2026-08-12 写作时的环境快照, 当前请改用 README.md 构建步骤第 2 条的通用写法, 不要照抄本节路径)", 与仓库其余"已核实事实不要重新调研"的历史条目保持同一处理方式。

**[Major] [C-实现状态标错] 索引表把四份规格全标成未开工("无, 可直接开"/"数值待实测"), 实际四者均已在2026-08-17当天合入main且沉淀逾一月未更新**

- 位置: 第 7-10 行
- 文档原文: | 1 | [任务系统接入 WebUI 平板面板](TaskSpec_Quest_WebUI_Panel.md) | `feat/quest-webui-panel` | 无, 可直接开 | | 2 | [矿洞维度禁止设置重生点](TaskSpec_Mining_NoRespawnPoint.md) | `fix/mining-no-respawn-point` | 无, 可直接开 | | 3 | [矿洞三难度死亡规则](TaskSpec_Mining_DeathRules.md) | ... **两条待主控拍板** ... | 4 | [矿洞进入收费](TaskSpec_Mining_EntryFee.md) | ... 数值待实测; **机制与测量埋点可先做, 默认值必须是 0**
- 代码实际: 四份规格点名的产物全部存在于 main (bd0c4588): QuestWebUiActions.java + QuestWebUiGameTests.java、RulesSystem.onSetSpawn + RulesSpawnGameTests.java、MiningDeathRules.java + MiningDeathRulesGameTests.java、MiningServerConfig 的三个 ENTRY_FEE_* + MiningYieldProbe.java + MiningEntryFeeGameTests.java。照这张表读文档的人会以为有四条活跃待办, 从而重复开分支。
- 取证: src/main/java/com/miningdim/quest/QuestWebUiActions.java:36-41 `public static void registerAll() { WebUiServerDispatcher.register("quest.board", BOARD); ... }`; src/main/java/com/miningdim/rules/RulesSystem.java:57-68 `@SubscribeEvent public void onSetSpawn(PlayerSetSpawnEvent event)`; src/main/java/com/miningdim/rules/MiningDeathRules.java:33-34 `@SubscribeEvent(priority = EventPriority.HIGH) public void onLivingDrops(LivingDropsEvent event)`; src/main/java/com/miningdim/config/MiningServerConfig.java:260-262 `ENTRY_FEE_EASY = b.defineInRange("entryFeeEasy", 0L, 0L, Long.MAX_VALUE);` 等三行; src/main/java/com/miningdim/entry/MiningYieldProbe.java:59 `LOGGER.info("[miningdim] yield-probe difficulty={} dwellTicks={} oreDrops={} creditGross={}", ...)`
- 建议改法: 把 docs/TaskSpec_INDEX.md 第5行表头"阻塞状态"改为"交付状态", 第7-10行分别改写: 1/2/3行改为"已交付并合入 main (commit 33159a79/504288cd/cc8c295a)"; 第4行改为"第一段(扣费机制+MiningYieldProbe测量埋点, 三档默认值均为0)已交付并合入 main (commit fb598013); 第二段(依 yield-probe 样本回填非零定价)尚未开始, EntrySystem.leaveCurrentInstance 目前直接丢弃 MiningYieldProbe.finish 的返回样本, 全仓无消费方"。并在文档顶部第1行标题下加一行"2026-09 复核: 四份规格均已实施合入 main, 本索引转为归档/回溯用, 不再是待办清单", 避免后续执行者据此误开重复分支。

**[Major] [E-状态过期] 四条 TaskSpec 全部已落地并合入 main, TaskSpec_INDEX.md 仍标"可直接开/待拍板/待实测"且基线钉死在过期的 1165**

- 位置: 第 7-11, 33 行
- 文档原文: L7-L11 `| 1 | [任务系统接入 WebUI 平板面板] | feat/quest-webui-panel | 无, 可直接开 |` / `| 2 | [矿洞维度禁止设置重生点] | fix/mining-no-respawn-point | 无, 可直接开 |` / `| 3 | [矿洞三难度死亡规则] | feat/mining-difficulty-death-rules | **两条待主控拍板** |` / `| 4 | [矿洞进入收费] | feat/mining-entry-fee | 数值待实测 |`; L33 `- **GameTest 基线 1165 绿** (2026-08-17, main 5852c73)`
- 代码实际: 四条全部已合入 main 并有配套测试: 任务面板有 QuestWebUiActions.java + QuestWebUiGameTests.java + webui/src/pages/QuestsPage.tsx; 禁重生点有 RulesSystem.onSetSpawn + RulesSpawnGameTests.java; 死亡规则有 MiningDeathRules.java + MiningDeathRulesGameTests.java; 进入收费有 ENTRY_FEE_EASY/MEDIUM/HARD 三个配置键 + MiningEntryFeeGameTests.java。索引还在教人"去开这四个分支", 且 1165 这个基线早已不是当前值(docs/modules/INVENTORY.md 的基线日期已是 2026-08-30), 照它判绿会误判。
- 取证: src/main/java/com/miningdim/rules/RulesSystem.java:58 `public void onSetSpawn(PlayerSetSpawnEvent event) {` 与 :43 `rules subsystem registered (placement, respawn and death rules enforced)`; src/main/java/com/miningdim/rules/MiningDeathRules.java(文件存在); src/main/java/com/miningdim/config/MiningServerConfig.java:260-262 `ENTRY_FEE_EASY = b.defineInRange("entryFeeEasy", 0L, 0L, Long.MAX_VALUE);` 等三行; src/main/java/com/miningdim/quest/QuestWebUiActions.java 与 webui/src/pages/QuestsPage.tsx 均存在
- 建议改法: 1) 将 TaskSpec_INDEX.md 表格第5列"阻塞状态"整列改为"完成状态", 四行(L7-L10)全部标 DONE, 并各自补一句落地文件出处: 第1条注 QuestWebUiActions.java + QuestWebUiGameTests.java + webui/src/pages/QuestsPage.tsx; 第2条注 RulesSystem.onSetSpawn + RulesSpawnGameTests.java; 第3条注 RulesSystem.register 内 forgeBus.register(new MiningDeathRules()) + MiningDeathRules.java + MiningDeathRulesGameTests.java; 第4条注 MiningServerConfig.ENTRY_FEE_EASY/MEDIUM/HARD + MiningEntryFeeGameTests.java。 2) 删除或明确标注失效 L14-L27 的"执行顺序/冲突点"小节(四条均已合入, 文件冲突讨论已无意义), 若保留仅作历史存档需在标题加"(历史, 已全部合入 main, 仅供归档参考)"字样, 避免被当作当前可执行指引。 3) 将 L33 "GameTest 基线 1165 绿 (2026-08-17, main `5852c73`)" 改为不钉死历史数值, 例如: "判绿口径以最近一次 `runGameTestServer` 日志行 `All N required tests passed` 为准, 历史基线数字不再作为比对锚点"; 同时可指向 docs/modules/INVENTORY.md 的最新基线日期作为交叉参照。 4) 文件头 L1 "# TaskSpec 索引 (2026-08-17 批)" 建议加注"(四条均已于 main 落地, 详见完成状态列)", 防止读者只看标题日期误判为待办。

### `docs/Full_Repo_Audit_2026-08.md`

**[Minor] [F-结构问题] 第三章标题条数与 C3/C6/C4 编号体系自相矛盾**

- 位置: 第 87, 110 行
- 文档原文: L87 `## 三、其余 Critical (3 条)`;L110 `### C4 · 见簇 A (幽灵 UUID 堵死刷怪), 复核时由 Major 升级为 Critical`;对照 L212 `编号 F001-F112 稳定, 按**复核后严重度**排序 ... 日后引用请直接用编号。`
- 代码实际: 标题写「其余 Critical (3 条)」,但三条里的 C4 正文自己写着「见簇 A」——它已被计入簇 A 的 3 Critical(L34 `簇 A — 实体堆叠系统上线即崩 (3 Critical + 2 Major)`),真正「其余」只有 C3 与 C6 两条。同时 C3/C6/C4 这套编号在全文其它位置没有任何到 F001-F112 的映射(C3=F006、C6=F001、C4=F002 需读者自行比对标题推断),与 L212 宣称的「编号稳定可引用」冲突。
- 取证: 代码侧无法为编号体系取证(纯文档内部一致性问题,故定为 Minor):C4 对应的 F002 位于 src/main/java/com/miningdim/stacking/StackMerge.java,与簇 A 的 F004/F005 同属 stacking 包同一根因,确实是同一簇;而 C3(F006)在 com/miningdim/economy、C6(F001)在 com/miningdim/job/munitions,与 stacking 无关。
- 建议改法: 把 L87 改为 `## 三、簇外 Critical (2 条: C3 = F006, C6 = F001)`;把 L110 整行改为 `> 另一条 Critical (F002 · 幽灵 UUID 堵死刷怪, 复核时由 Major 升级) 已计入簇 A, 此处不重复计数。`;并在 L89、L100 两个小节标题后各加一个 F 编号括注(`### C3 (= F006) · ...`、`### C6 (= F001) · ...`),使第三章编号与附录 A 可互相跳转,同时避免"其余 Critical (3 条)"与总账(簇A 3 + 簇B 1 + 其余 2 = 6)对不上。

### `docs/TaskSpec_INDEX.md`

**[Minor] [F-结构问题] 批次快照命名成 INDEX, 自身零入链且基线数钉死**

- 位置: 第 33 行
- 文档原文: L1「# TaskSpec 索引 (2026-08-17 批)」; L33「- **GameTest 基线 1165 绿** (2026-08-17, main `5852c73`)。每条规格完成后应为 1165 + 新增用例数, **一条都不许比基线少**。」
- 代码实际: 文件名叫 INDEX, 但内容是 2026-08-17 一个交付批次的快照: 钉死了当时的基线数、当时的分支名、当时的阻塞状态。四份 TaskSpec 早已全部交付合入, 基线也早已远离 1165。文件自身零入链, 所以既不是索引也不是活文档; 同时它占用了 INDEX 这个名字, 会让人误以为 docs/ 已经有索引体系。
- 取证: 仓库现行质量门判据见 分支协作.md(唯一记载合并前硬门的文档), 基线数不再以某次批次快照为准; 全仓受控文件子串反查 TaskSpec_INDEX.md 零命中。docs/ 下不存在任何其他以 INDEX 命名的文件, 这份是唯一一个, 却只覆盖 57 份中的 4 份。
- 建议改法: 随 4 份 TaskSpec 一起迁入 docs/archive/delivered/, 并改名为 TaskSpec_Batch_2026-08-17.md 以释放 INDEX 这个名字; 头部补交付闭环记录(合入 commit 与最终 GameTest 数)。INDEX 语义留给新建的 docs/README.md。

---
