# 全库审计报告 (2026-08)

审计范围: `src/main/java` 全部 794 个文件 / 13.6 万行 (含 GameTest 3.9 万行) + `webui/src` 3.2 万行 + `docs/` 39 份设计文档。
基线提交: `49d5283` (WebUI 全量接线合并后)。
方法: 12 组独立审计 (9 个代码域 + 3 个横切专项) 并行扫描, 对全部 Critical/Major 逐条做对抗式证伪复核。

四个维度: **compat** 兼容性 / **perf** 性能 / **bug** 缺陷 / **gap** 功能缺口。

---

## 一、结论

原始 findings **112** 条。对其中 66 条 Critical/Major 做了对抗复核: **62 条维持, 4 条被推翻 (误报), 20 条降级为 Minor**。

复核后的实际盘面:

| 档位 | 条数 | 说明 |
|---|---|---|
| Critical | **6** | 崩服 / 资产损毁 / 经济被击穿 / 核心系统完全无作用 |
| Major | **36** | 玩家可感知的错误行为、显著性能退化、升级出错、安全面扩大 |
| Minor | 66 | 20 条自 Major 降级 + 46 条原报 Minor (未逐条复核) |
| 误报 | 4 | 见第五章, 如实计入 |

按维度: bug 56 / perf 25 / gap 22 / compat 9。

**全部 112 条的逐条明细 (证据 / 影响 / 建议 / 复核结论) 见文末附录 A**, 编号 `F001`-`F112` 稳定可引用。

**最重要的判断**: 问题不是均匀分布的, 它们聚成了两个簇, 且这两个簇各自都足以让公服无法运营。

---

## 二、两个必须优先处理的簇

### 簇 A — 实体堆叠系统上线即崩 (3 Critical + 2 Major)

`com.miningdim.stacking` 的合并候选判定只有一道类型闸: `if (!(entity instanceof LivingEntity)) return false`
(`StackMerge.java:44`)。之后的四道过滤 (自定义名 / 驯服 / Boss / 黑名单) 一个都拦不住下列对象:

| 被误合并的对象 | 后果 |
|---|---|
| **ServerPlayer** | 同区块内水平 5 格 / 垂直 3 格的两名玩家, 最迟 5 秒后其中一人被 `discard()`。连接还在但实体已不在世界里 —— 不可见、不被 tick、不触发登出或死亡事件的幽灵号。**出生点、商店、市场、组队打架都是常态触发条件, 不需要任何特殊操作。** |
| **村民 / 盔甲架 / 铁傀儡** | 交易大厅贴身站位的村民被压成一个 "村民 x64", 其余 63 只连同职业、交易表、声望永久消失; 装备好的盔甲架连同护甲武器一起 `discard`。不可逆, 无日志, 无提示。 |
| **本工程精英怪** | 精英被合并或顶着 xN 名牌被一次击杀掉 N 份普通战利品; 玩家已累积的贡献与奖励作废。 |

外加两条放大器:
- `discard()` 不走 `LivingEntity.die`, 因此不发 `LivingDeathEvent` —— 矿洞实例的 `liveMobs` 集合里留下永不销账的幽灵 UUID, 满 30 后该难度区域**永久停止刷怪**。
- 堆叠 N 只共用一份血量, 却按 N 份结算掉落与经验: 击杀成本除以 N, 产出乘以 N (默认上限 64 倍)。这是一个直通经济系统的倍增器。

`StackingConfig` 的黑名单默认是空表 (`defineList("blacklist", List.of(), ...)`), 所以以上全部处于放行状态。

**主控已逐环亲自复核这条 (不只采信审计员)**:
- `StackingSystem.java:103` 的候选来源是 `level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)`
  —— 收全部 LivingEntity, **玩家在内**; 第 107 行唯一的过滤就是 `canStack`。
- `canStack` 逐道闸对玩家的判定: `instanceof LivingEntity` 通过; `hasCustomName()` 对玩家默认 false 不排除;
  `isBoss` 的判据是 `!canChangeDimensions()` 而站立玩家为 true, 故不排除; 黑名单默认空表。**放行。**
- **没有任何总开关**: `StackingConfig` 里不存在 `enabled` 项, `onServerTick` 无条件每 100 tick 跑一次,
  子系统在主类 `registerSubsystems()` 里无条件装配。

**处置建议**: 在 `StackMerge` 的候选判定里显式排除 `Player`、`ArmorStand`、`Villager`/`AbstractVillager`、
`IronGolem` 等非怪物 LivingEntity, 以及带本工程 champion capability 的实体; 把"仅限敌对怪物"改成
白名单式判据而不是黑名单式。掉落与经验必须按"实际结算的血量份数"计, 不能按 StackSize 直乘。

**紧急止血**: 由于不存在配置开关, 修完之前唯一的止血手段是**注释掉主类里
`subsystems.add(new com.miningdim.stacking.StackingSystem())` 那一行**。
顺带说明: "缺一个总开关"本身也是缺陷 —— 这种量级的实体系统必须能在不发版的情况下关停。

### 簇 B — 矿洞是一次性资源, 三块常驻区域挖空即报废 (1 Critical + 3 Major)

`ResetJob` 全文 148 行, `tick()` 只有 UNLOAD -> REGEN -> SETTLE -> DONE 四态, 而 `doUnload()` 只做两件事:
释放区块票 + 调离线生成器。全库 grep 不到任何删区块 / 清实体 / 重放地形的调用。

于是三条重置入口 (`/mining reset` 命令、`admin.mining.reset` 面板、`AutoResetScheduler` 定时刷新) 全部只是
"广播倒计时 -> 把玩家传走 -> 把 genState 翻一圈"。默认配置 Hard 每 2 小时、Medium 4 小时、Easy 6 小时,
于是公服每 2 小时把 Hard 区所有人踢出去一次, 玩家回来发现地形、矿脉、自己挖的坑、放的箱子、
地上的掉落物和残留怪物**一模一样**。

R1 模型下全服只有三块常驻区域, 这意味着**矿石是一次性资源**: 上线几天后三块区域被挖空,
矿工职业 (经济主 faucet) 产出归零, 且没有任何恢复手段。

同簇的另外三条:
- 实例活怪计数只增不减 (陷阱击杀与自然消失都不销账), 满 30 后该难度永久停刷。
- 三块固定实例的区块强加载票永不释放: TTL 释放分支对固定实例是死路, 玩家走后数百区块常驻内存。
- 已判废的离线体素管线仍在每次开服与每次重置全量重跑: 单实例 2500 万体素、约 96MB 的 int 队列缓冲, **结果无人读取**。

---

## 三、其余 Critical (3 条)

### C3 · 30 天回收把开箱资产凭据一起删了

`EconomySystem.java:110` 在 ServerStarted 内无条件调 `pruneTerminalOperations(now - 30天)`,
而 `SqliteEconomyLedger` 的 SQL 是 `DELETE FROM bundle_operations WHERE status IN (...) AND created_at < ?`,
**没有 domain 过滤** —— 开箱的幂等行与经济的终态行共用这张表。

后果 (公服连续运行超 30 天后必然发生): 每次重启删掉 30 天前的开箱幂等行, 玩家下次登录时 `recoverFor`
为每条历史 COMMITTED 开箱**重新扣一次 50000 信用点 + 10 青辉石**, 扣完写新行, 30 天后再删再扣,
形成周期性抽血。余额不足时抛出的异常从 `open()` 里 try 之外的 `recoverFor` 冒泡, 玩家从此再也开不了箱。
期间已购皮肤在面板上消失, 握在手里的枪每秒被强制还原成默认外观。

### C6 · 军火台产出被 byte 截断销毁

`MunitionsBenchBlockEntity.java:668` 把 `bufferedRounds` 原样喂给 TACZ 的 `AmmoItemBuilder`,
代码注释断言"TACZ 会内部钳制"。复核者用 javap 反汇编 `libs/tacz-1.20.1-1.1.8-hotfix.jar` 确认:
`setCount` 只有 `Math.max(count, 1)`, **没有上限钳制**。

于是挂机回来输出槽里坐着 count=480 的弹栈, 而 `ItemStack.save` 用 `putByte("Count", (byte) count)` 存盘:
480 存成 -32, 重载后该槽变空, 几百发弹凭空消失。GameTest 完全测不到 (dev 无 TACZ, `materialize` 恒返 EMPTY),
只会在正式服炸。

### C4 · 见簇 A (幽灵 UUID 堵死刷怪), 复核时由 Major 升级为 Critical

---

## 四、功能缺口 (gap) — 22 条

这一栏回答"还有些功能没做"。**几条职业主线是整条不可达的**:

### 4.1 特勤干员: 三条主玩法全部不可达 + 职业本身升不了级

- 探测源读的是**已废弃的第三方 capability** (`AgentChampionData.java:51`), 于是封印恒返 `NO_TARGET`、
  战术扫描对任何精英恒返 null 快照 (且空表照烧 CD)、对精英伤害加成恒不生效。
- 连锁: `markActiveAgent` 的唯一置位点在封印成功处, 封印不可达 -> 入职标志永不置位 ->
  加强奖励与伤害加成的资格门永不打开。
- **职业升级死锁**: 唯一的经验入账点被"入职标志"门锁死, 而入职标志只能在 L3 时置位 —— 互为前置。
- **悬赏系统整条未实现**: `BountyDefinition` / `BountyProgress` 两个类零生产调用点, 发奖出口零调用点,
  玩家侧无任何接取入口。设计文档第四章/7.2/8.1/10.5/十一章全部落空, 其中十一章写明
  "青辉石唯一来源 = 周常悬赏" —— 即**青辉石当前实际产量恒为 0**。
- 原生扫描面板三件套 (MenuType + Screen + 两个网络包) 全部注册但零入口, 占着 discriminator 的死代码。

结论: 特勤职业目前对玩家而言只剩"扫描看词条"这一个动作, 而扫描本身也读不到东西。

### 4.2 矿洞: 静态陷阱与出生点池整套死代码

- `StaticTrapGenerator` (238 行) + `TrapParams` 的难度因子/致死密度/间距数值表零生产调用方:
  三块矿洞里没有任何静态陷阱, **矿工花技能点解锁的 L5/L8 陷阱探测永远显示"无陷阱"**,
  且每次激活还要空跑一个 radius³ 的三重循环。
- 出生点候选池与 60 tick 占位 TTL 整套死代码: 同一难度的所有玩家**恒定落在同一格** ——
  实体互相挤压, 一颗苦力怕能覆盖所有刚进场的人。

### 4.3 经济链路上的空环

- **酿酒师宣称的"联动农夫经济"在代码里不存在**: `dried_wheat` 配方与九条酿造配方吃的全是
  `minecraft:wheat` 等原版物品, 没有一条引用 `FarmerItems.FARMER_WHEAT`。于是酿酒师这条本该最大的
  小麦 sink 可由零上限、零等级门、可全自动化的原版小麦农场满足, 完全绕开 mod 耕地的放置硬封顶与档位门。
  反过来农夫的 `farmer_wheat` 至今只有 `/farmer sell` 一个出口 —— 两个职业之间没有任何真实供给耦合。
- **枪匠冲压+装配整条链零职业等级门、零经济 sink**: 新号拿到材料就能直冲传奇零件装满配枪,
  且整条造枪链一分钱 sink 都没有, 是把矿物转成高价值战斗成品的净产出通道。

### 4.4 其它成规模的缺口

- 婚姻: 离婚缺 escrow 公示期且共享背包**无条件归发起方** —— 文档点名要防的"离婚资产抢劫"成了默认行为;
  "预约场地"未实现; `MarriageState` 四个持久字段全库零写入方 (存档里躺着自洽但完全错误的数据)。
- 实体堆叠: 规格里两条 MUST (拆分语义、拴绳语义) 完全没实现, 玩家无法从堆叠里取出单只动物。
- 铸甲师: 多重护盾充能格数与心肺反应器共享 CD **没有任何 HUD 或 S2C 状态包** ——
  在 80 血 + 高 DPS 环境里, 决定"要不要继续打"的两个资源玩家完全看不见。
- WebUI: 启动握手自检 `handshake()` 实现完整但**零调用点**, 契约漂移没有任何可观测信号。
- `data/miningdim/affix_setting` 下 35 个 JSON 声明的 champions 类型在其注册表里全不存在, 恒为死数据,
  却每次 reload 被解析并随握手包全量发给每个客户端。
- `instance.regionSizeChunks` / `bufferChunks` 两个带 `worldRestart` 语义的配置项零消费方 —— 对运维撒谎。

---

## 五、误报与降级 (如实计入)

对抗复核推翻了 4 条, 其中一条特别值得记:

> **"插板/等离子盾/纳米盾三套减伤绕开单点结算, 逃出 85% 全局帽"** — 推翻。
> 这是写进 `docs/Armorer_Armor_System_DesignSpec.md:164` 的**明确设计规则**而非遗漏, 代码里也有对应的
> 显式意图注释。审计员按"所有减伤都该收在单点"的通则下了判断, 但没读设计文档。

另三条: 前端分类树性能问题的**服务端侧描述**不成立; 塔罗的表清理入口实际存在 (走登出事件);
特勤召唤物经济闸缺失属实但后果被上游门拦死, 修好 capability 也不会印钞。

另有 20 条自 Critical/Major 降级为 Minor, 主因多是"上游有门先拦住了"或"后果没有审计员说的那么重"。

**这 24 条 (4 + 20) 的存在本身是复核有效的证据** —— 未经证伪的审计清单里, 这个比例的噪音会直接变成
无效工时。

---

## 六、建议的处置顺序

| 批次 | 内容 | 理由 |
|---|---|---|
| **P0 · 立刻** | 摘掉 stacking 子系统装配 (无配置开关可关), 再修 `StackMerge` 候选判定 (簇 A) | 上线即崩服 + 不可逆资产损毁 |
| **P0 · 立刻** | 开箱幂等行不再被 30 天回收误删 (C3) | 公服跑满 30 天必然发生, 且会重复扣款 |
| **P1 · 本周** | 矿洞重置真正作用于世界 (簇 B) | 决定矿工职业乃至整个经济主 faucet 能否持续 |
| **P1 · 本周** | 军火台输出栈钳到单栈上限 (C6) | 静默销毁玩家产出 |
| **P2** | 特勤 capability 探测源 + 职业升级死锁 (4.1) | 一整个职业当前不可玩 |
| **P2** | 农夫耕地计数归属、耕地档位门分叉、酒窖燃料债 | 经济与职业进度被绕开 |
| **P3** | 性能簇: 精英怪 16 个 handler 的同 tick AABB 扫描、区块票泄漏、体素管线空转 | 满编公服的稳定性 |
| **P4** | 其余功能缺口按产品优先级排期 | 见第四章 |

---

## 七、方法与局限

- 每条 finding 都要求给出真实的 `文件:行号` 与实际读到的代码事实; 报不出具体后果的不予收录。
- Critical/Major 全部经过独立的对抗式复核 (复核者的立场是证伪, 且被要求给出推翻依据的具体位置)。
  复核过程中有 2 条被**升级**严重度。
- **未复核的 46 条原报 Minor** 存在噪音, 使用前请自行确认。
- 审计为纯静态读码 + 反编译核对 (对 TACZ jar 用了 javap)。**未在真实服务器上运行验证** ——
  凡是标注"上线即发生"的结论, 建议在测试服 (`shinoyuki@192.168.10.139`) 复现一次再动手。
- 本轮编译期检查另有一条独立结论: 全库**零 unchecked 警告**; 37 条 deprecation 中 35 条是
  `BlockBehaviour` 那组 Mojang 按设计标注的方法 (覆写它们是正确用法), 真正值得改的只有
  `FarmerWheatSellService.java:141` 的 `Item.getMaxStackSize()` (应改用 `ItemStack` 级)。

---

## 附录 A · 112 条 findings 全量明细

编号 `F001`-`F112` 稳定, 按**复核后严重度**排序, 同档内按审计域聚集。日后引用请直接用编号。

每条的「复核」栏: Critical/Major 全部经过独立的对抗式证伪; 原报 Minor 未逐条复核 (标注为"未复核")。

「严重度」栏写成 `原报 -> 复核后`; 两者相同时只写一个。


### A.1 Critical (6 条)


#### F001 · 军火台输出槽会造出 count 远超单栈上限的 TACZ 弹栈, 存档时 Count 被截成 byte 直接销毁玩家产出

- **维度**: 缺陷 | **严重度**: Critical | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:668`
- **证据**: refreshOutputStack 把权威缓冲发数原样喂给物化层: `ItemStack ammo = MunitionsAmmoFactory.materialize(bufferedCaliber, bufferedRounds);` 紧跟的注释断言 "若超 TACZ stack_size, TACZ 内部钳到上限"。用 libs/tacz-1.20.1-1.1.8-hotfix.jar 反汇编核实该断言不成立: AmmoItemBuilder.setCount 只做 `Math.max(1, count)`, build() 只做 `new ItemStack(ModItems.AMMO.get(), count)`, 全程无 getMaxStackSize 钳制 (AmmoItem.getMaxStackSize 只是被 vanilla 查询, builder 不查)。而 bufferCap 来自 MunitionsConfig.BUFFER_L1=500 ... BUFFER_L10=4000 (MunitionsConfig.java:169-178), 单次 settle 就能一次性把几百发填进缓冲并 refreshOutputStack。
- **影响**: 主人挂机回来后输出槽里坐着一个 count=480 之类的弹栈。ItemStackHandler.serializeNBT -> ItemStack.save 用 putByte("Count",(byte)count) 存盘: 480 存成 -32 -> 重载后该槽变空, 玩家几百发弹凭空消失; 300 会存成 44 (缓冲计数 300 与槽内 44 长期不一致)。鼠标整取还会把这个超限栈放到光标上, 关界面掉落成同样会被截断的 ItemEntity。GameTest 完全测不到 (dev 无 TACZ, materialize 恒返 EMPTY; MunitionsGameTests.java:604 的注释里作者也是按"会钳到 64"假设写的), 只会在正式服炸。
- **建议**: 物化前按 min(bufferedRounds, ammoStack.getMaxStackSize()) 分栈, 输出槽只放一栈, 余量留在 bufferedRounds 由后续 refresh 补; 同时给输出槽写入加一道 count<=getMaxStackSize 的断言, 别再依赖第三方 builder 替自己钳。
- **复核**: 维持 — 逐环核实全部成立, 无任何上游门拦住。(1) 代码事实: MunitionsBenchBlockEntity.java:663-671 refreshOutputStack 原样把 bufferedRounds 喂给 materialize, 紧跟的 669 行注释断言 TACZ 会内部钳制。(2) 我用 ~/.gradle/jdks 的 JDK17 javap 反汇编 libs/tacz-1.20.1-1.1.8-hotfix.jar 的 com.tacz.guns.api.item.builder.AmmoItemBuilder: setCount 字节码只有 Math.max(count,1) 后 putfield; build 只有 new ItemStack(ModItems.AMMO.get(), count) + setAmmoId, 全程无 getMaxStackSize 调用 —— 该注释的断言不成立, 审计员没编造。(3) 上限确实会被突破: 我解包 assets/tacz/custom/tacz_default_gun/data/tacz/index/ammo/12g.json stack_size=36, 308.json stack_size=48; 而 MunitionsConfig.java:169-178 bufferL1=500 ... bufferL10=4000, MunitionsProduction.settle 的三项夹取里只有 bufferRemaining 这一道 (MunitionsProduction.java:97-121), 不含栈上限。(4) 下游无补救: MunitionsAmmoFactory.materialize 只判 count<=0/未加载; ItemStackHandler.setStackInSlot 不钳; MunitionsBenchMenu.java:66 的 OutputSlot 也只覆写 mayPlace/remove/onTake, 无 count 钳制; saveAdditional (BE:760) 走 inventory.serializeNBT -> ItemStack.save 的 putByte("Count") 截断。(5) 生产/dev 行为差异反而佐证审计员: dev 无 TACZ, materialize 恒 EMPTY, GameTest 天然测不到, 只会在装了 TACZ 的正式服炸。补充一条比审计员说得更狠的后果: refreshOutputStack 只在 settleForOwner 产出成功帧 (BE:449)、finishActiveCraft (BE:519) 与 onOutputTaken (BE:694) 被调, load() 路径不调 —— 缓冲打满 (produced()=false) 时重载后输出槽是截断出来的空栈且不会被重建, 玩家几千发弹会被永久卡死在 bufferedRounds 里取不出来。Critical 成立。


#### F002 · 实体堆叠合并 discard 实体不触发 LivingDeathEvent, 矿洞实例 liveMobs 被幽灵 UUID 占满后该实例永久停止刷怪

- **维度**: 缺陷 | **严重度**: Major -> Critical | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/stacking/StackMerge.java:152`
- **证据**: StackMerge.mergeGroup 在 StackMerge.java:152 对被吸收的实体调 `other.discard()` —— discard 不触发 LivingDeathEvent。压力子系统这一侧: MobPressureSystem.java:266 `instance.liveMobs().add(mob.getUUID())` 登记, 而全工程 liveMobs 的唯一移除点是 MobPressureSystem.java:373 的 onMobDeath(LivingDeathEvent) 分支(grep liveMobs 只有 InstanceState.java:45/133、MobPressureSystem.java:177/266/373、DynamicTrapEngine.java:131/133/144/157)。MobPressureSystem.java:177-181 用 `instance.liveMobs().size() >= MAX_MOBS_PER_INSTANCE(30)` 直接跳过整波刷怪。StackingSystem.java:83 的周期扫描遍历 `server.getAllLevels()`, 不排除矿洞维度; 压力系统刷出的僵尸/骷髅/苦力怕都是普通 LivingEntity, StackMerge.canStack(StackMerge.java:42) 对它们全部放行。
- **影响**: 矿洞实例内一波刷出的怪互相靠近(水平 5 格/垂直 3 格内)就会被合并, 被并者 discard 而不死亡, 其 UUID 永久留在 InstanceState.liveMobs。玩久了 liveCount 单调爬到 30 硬上限, 该实例从此再也不刷怪: 玩家会看到 danger 压力条照涨、HUD 报高危, 但一只怪都不来, 整个压力/危险度玩法在长时间局内静默失效。同一路径下 mobInstanceIndex 也只在实例重置时才清。(注: 原版自然 despawn 同样只 discard 不发 LivingDeathEvent, 是同一个根因, 堆叠只是把它变成高频常态。)
- **建议**: liveMobs 的回收不能只认 LivingDeathEvent。要么改听 EntityLeaveLevelEvent / 在 tick 里按 UUID 反查实体是否还在世界来惰性清理, 要么让 StackMerge 在 discard 前给一个可订阅的合并钩子, 由压力子系统按既有 seam 范式接线把被并者从 liveMobs 摘掉。顺带把 mobInstanceIndex 的回收对齐同一路径。
- **复核**: 维持 — 成立, 且实际比审计员描述的更严重, 故升级为 Critical。核实点: StackMerge.java:152 对被吸收者 other.discard() (discard 走 remove(RemovalReason)、不进 LivingEntity.die, 不发 LivingDeathEvent); 登记点 MobPressureSystem.java:266 instance.liveMobs().add; 全库 liveMobs 的引用只有 InstanceState.java:45/133、MobPressureSystem.java:177/266/373、DynamicTrapEngine.java:133/144/157, 唯一移除是 MobPressureSystem.java:367-373 的 onMobDeath, 且它先要 mobInstanceIndex.remove(id) 命中才会去动 liveMobs; 封顶点 MobPressureSystem.java:53 MAX_MOBS_PER_INSTANCE=30 + line 177-182 直接 return 跳过整波; 危险度 HUD 在 line 152 sendDanger 于封顶判定之前下发, 所以"压力条照涨、一只怪不来"属实。堆叠确实会作用到矿洞: StackingSystem 在 MiningDim.java:153 无条件装配, StackingSystem.java:83 遍历 server.getAllLevels() 不排除矿洞维度, canStack 对压力系统刷的僵尸/骷髅/苦力怕全放行。升级理由 (三条审计员没挖到的加重事实): (a) MobPressureSystem.java:395 的 onInstanceReset 全库零调用方, 而且它本来也只清 mobInstanceIndex 不清 liveMobs, 所谓"实例重置时才清"其实根本不会发生; (b) 实例是常驻的 —— InstanceManager.java:149-206 的 ensureFixedInstances/createFixedInstance/isFixedInstance 表明三难度是固定共享常驻实例, 不 GC 不回收, 所以幽灵 UUID 一直累积到重启 (InstanceState.java:164 注明 liveMobs 不持久化, 只有重启能清); (c) DynamicTrapEngine.java:157 把身后苦力怕加进 liveMobs 却从不写 mobInstanceIndex, 于是 MobPressureSystem.java:369 的 instanceId==null 会直接 return —— 这类怪就算被玩家正常打死也永远不释放槽位, 属于百分之百必然发生的泄漏 (不依赖堆叠, 也不依赖原版 despawn)。综合: 该难度的固定实例对全服所有玩家静默停止刷怪直到重启, 矿洞的压力/危险度核心玩法整体失效且无日志报警。


#### F003 · 区域重置全链路对世界零作用: 不删区块、不清实体, 三块常驻区域的矿产永不再生

- **维度**: 功能缺口 | **严重度**: Critical | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/reset/ResetJob.java:111`
- **证据**: doUnload() 全文只有两件事: ChunkServices.ticketService().releaseAll(instance.instanceId()) 释放强加载票, 然后 MiningServices.offlineGenerator().generate(...) 重算体素; 注释写 '13.4 阶段一: 释放 region 强加载 ticket, region 区块自然卸载'。core/IResetService.java:12 的契约却写 '文件级删除 region 区块 (绝不逐块 setBlock) -> 按 mode 决定种子 -> 重跑离线生成'。对整个 reset 包 grep `delete|Files\.|\.mca|setBlock` 零命中, 全库 grep `ChunkDataEvent|RegionFile|deleteChunk` 也零命中 —— 没有任何代码删除或重建 region 区块。而重算出来的体素只写进 GenerationScheduler.voxelCache, 唯一读者 MiningChunkGenerator 并未被维度使用 (data/miningdim/dimension/mining.json 的 generator.type = "minecraft:noise")。ResetJob 随后 finish() 只把 genState 翻回 READY。
- **影响**: 三条重置入口 (/mining reset 命令、admin.mining.reset 面板、AutoResetScheduler 定时刷新) 全部只是 '广播倒计时 -> 把玩家传走 -> genState 翻一圈'。配置默认 autoResetHoursHard=2 / Medium=4 / Easy=6 且 warnSeconds=60 (MiningServerConfig:226-233), 于是公服每 2 小时把 Hard 区所有人踢出去一次, 玩家回来发现地形、矿脉、自己挖的坑、放的箱子、地上的掉落物和残留怪物一模一样。R1 模型下全服只有三块常驻区域, 这意味着矿石是一次性资源: 上线几天后三块区域被挖空, 矿工职业 (经济主 faucet) 产出归零, 且没有任何恢复手段。同时 evacuate 只处理 playerSet 里的玩家, 实体清理这一步在代码里根本不存在。
- **建议**: 重置必须真正作废 region 内的区块数据: 要么在玩家撤离且 ticket 释放后按 region 走服务端的区块删除/重生成路径 (先确认区块已卸载再动文件, 再让下次加载重新走 worldgen), 要么退一步做 '按 region 边界重放 worldgen 并批量写回'。无论哪种, 都要补上 region 内实体 (掉落物/怪物/玩家放置的 BlockEntity) 的定向清除, 并在 ResetJob 里把这一步做成可分帧、可失败回滚的阶段, 而不是让 UNLOAD 阶段名不副实。
- **复核**: 维持 — 逐行读完 ResetJob 全文 (148 行): tick() 只有 UNLOAD->REGEN->SETTLE->DONE 四态, doUnload() (:111-132) 确实只做两件事 —— ChunkServices.ticketService().releaseAll(instanceId) 与 MiningServices.offlineGenerator().generate(...); SETTLE (:93-103) 只数 2 个 tick, finish() (:135-139) 只把 genState 翻 READY。全库 grep `deleteChunk|RegionFile|ChunkDataEvent|\.mca|Files\.delete` 只命中 testutil/TempStoreDb.java:44, 确无任何区块删除/重建代码, 与 core/IResetService.java:12 写死的契约 (文件级删除 region 区块 -> 按 mode 决定种子 -> 重跑离线生成) 直接矛盾。重算出的体素只进 GenerationScheduler.voxelCache (:128), 唯一读者 voxelsOf 供 MiningChunkGenerator, 而 data/miningdim/dimension/mining.json 的 generator.type 实为 minecraft:noise, 该 ChunkGenerator 根本不被维度使用 -> 产物无消费方。三条入口全部真实存在: entry/MiningCommands.java:140-142、MiningAdminWebUiActions.java:99-106、AutoResetScheduler.java:167-192; MiningServerConfig:226-233 默认 Hard 2h/Medium 4h/Easy 6h + 60s 预警, 即公服每 2 小时把 Hard 区在场玩家真传走 (ResetSystem.evacuate:150-170 是真传送) 然后世界一格不变。补充证据 (比审计员说的更彻底): 重置链路对其他子系统的清理钩子同样全是死代码 —— MobPressureSystem.onInstanceReset(:395) 与 TrapSystem.invalidate(:133) 全库零调用点, 故实例内怪物/陷阱节流态在重置后也一并残留。维持 Critical。


#### F004 · 实体堆叠把玩家也当作合并候选, 同区块 5 格内的两名玩家会被合并, 其中一人被 discard 出世界

- **维度**: 缺陷 | **严重度**: Critical | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackMerge.java:44`
- **证据**: canStack 的唯一类型闸是 `if (!(entity instanceof LivingEntity)) return false;`, 之后只排除 命名/驯服/Boss/blacklist。ServerPlayer 就是 LivingEntity, 没有 CustomName, 不是 TamableAnimal, 而 isBoss() 判据是 `!entity.canChangeDimensions()` —— 已核对 1.20.1 反编译源码 (forge-1.20.1-47.4.20 sources): Entity.canChangeDimensions() = `!isPassenger() && !isVehicle()`, LivingEntity 再叠 `&& !isSleeping()`, 故一个站着不骑乘的玩家该值恒为 true -> isBoss()=false -> canStack(player)=true。取候选侧 StackingSystem.java:103 用 `level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)`, 而 ServerLevel.addPlayer 走 `entityManager.addNewEntityWithoutEvent(player)`, getEntities() 返回的正是同一个 entityManager.getEntityGetter(), 玩家必然在列表里。StackMatchKey.of(player) = (EntityType.PLAYER, baby=false, variant=""), 两名玩家等键。
- **影响**: 两名玩家站在同一区块内且水平距离 <=5 格 / 垂直 <=3 格 (出生点、商店、市场、组队打架时的常态), 最迟 5 秒 (scanIntervalTicks=100) 后 mergeGroup 就会给其中一个写 StackSize=2 并 setCustomName("玩家 x2"), 另一个执行 StackMerge.java:152 的 other.discard()。ServerLevel.EntityCallbacks.onTrackingEnd 会把该 ServerPlayer 从 ServerLevel.players 移除并停止区块追踪, 玩家连接还在但实体已不在世界里 —— 变成不可见、不被 tick 的幽灵号, 且不触发任何登出/死亡事件。这是上线即崩服级别的故障, 不需要任何特殊操作就能复现。
- **建议**: canStack 必须改成显式类型准入而非黑名单排除: 至少先无条件排除 Player (以及 ServerPlayer 的一切子类) 与所有非 Mob 的 LivingEntity; 更稳的做法是只允许 config 里显式列出的 EntityType 参与堆叠 (allowlist), 让"新实体默认不堆"成为默认安全态。同时给 StackingGameTests 补一条"两名玩家永不合并"的删逻辑必挂用例。
- **复核**: 维持 — 亲自逐层验证, 五个环节全部成立, 无任何前置门拦住: 1) 代码事实: StackMerge.java:44 唯一类型闸确实是 `if (!(entity instanceof LivingEntity)) return false;`; 之后只有 :50 命名 (hasCustomName)、:55 驯服 (TamableAnimal/AbstractHorse)、:58 Boss、:61 blacklist 四道。ServerPlayer 一条都不命中。 2) isBoss 判据核对反编译源码 (forge-1.20.1-47.4.20_mapped_official sources): Entity.java:2546 `canChangeDimensions(){ return !isPassenger() && !isVehicle(); }`, LivingEntity.java:3178 叠 `&& !isSleeping()`。Player/ServerPlayer 均未 override (已 grep 两文件, 零命中), 故站立玩家 canChangeDimensions=true -> StackMerge.java:79 isBoss=false。 3) 候选侧玩家必在列表内: ServerLevel.java:842-851 addPlayer 走 `this.entityManager.addNewEntityWithoutEvent(p_8854_)`; ServerLevel.java:1426-1428 `getEntities(){ return this.entityManager.getEntityGetter(); }`, 与 StackingSystem.java:103-104 的 `level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)` 是同一个 getter (这也正是原版 @e 选择器能选中玩家的同一条路径)。 4) 等键成立: StackMatchKey.of (StackMatchKey.java:41-44) 对 Player 取 (EntityType.PLAYER, baby=false 因 Player 非 Mob, variant="" 因非 Sheep/Creeper/AbstractHorse), 两名玩家完全等键。 5) 后果链: StackMerge.java:148 给 anchor 写 StackSize, :152 `other.discard()`; Player.java:1280 `remove(RemovalReason)` 是真实存在的实现; ServerLevel.java:1530-1534 EntityCallbacks.onTrackingEnd 明确 `if (p_143375_ instanceof ServerPlayer serverplayer) ServerLevel.this.players.remove(serverplayer)` —— 玩家被踢出世界实体表但连接仍在, 幽灵号成立。 无开关可豁免: StackingConfig 全表 (StackingConfig.java:46-116) 根本没有子系统总开关, MiningDim.java:153 无条件 `subsystems.add(new StackingSystem())`, StackingSystem.java:66-87 的 ServerTickEvent 也无任何维度/权限门。scanIntervalTicks 默认 100 (StackingConfig.java:81), 即最迟 5 秒触发。 补充升级要点 (比审计员描述更严重): 幸存玩家会被 StackData.setStackSize 写入随存档落盘的 persistentData (StackData.java:47), 并被 applyLabel (StackMerge.java:188) 打上 setCustomName; 此后该玩家死亡会命中 StackDeath.java:67 的 canStack + :70 stackSize>1, 走 handleInstantAll 按玩家 loot table 与 getExperienceReward 再补 N-1 份结算。判 Critical 无异议, 属上线即崩服级。


#### F005 · 村民/盔甲架/铁傀儡等一切非怪物 LivingEntity 同样无差别合并, 被并方连同其交易、装备、物品被永久销毁

- **维度**: 缺陷 | **严重度**: Critical | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackMerge.java:152`
- **证据**: canStack 只排 命名/驯服/Boss/blacklist, 而 StackingConfig.java:115 的 EXCLUSIONS_BLACKLIST 默认值是 `List.of()` 空表。Villager / IronGolem / WanderingTrader / ArmorStand 都继承 LivingEntity, 都不 hasCustomName、不 TamableAnimal, canChangeDimensions() 恒 true (已核对 1.20.1 源码, Villager.java 与 ArmorStand.java 均未 override 该方法)。StackMatchKey.variantSignature (StackMatchKey.java:50-66) 只区分羊毛色/苦力怕充能/马 Variant, 对村民职业与等级、盔甲架穿戴物、傀儡血量一律返回空串 -> 等键。合并时 StackMerge.java:152 直接 other.discard(), 而 discard() 走 remove(RemovalReason.DISCARDED), 不触发 LivingDeathEvent, 不掉落任何物品。
- **影响**: 公服上交易大厅的村民彼此贴身站位, 5 秒一轮扫描后会被压成一个 "村民 x64" 实体, 其余 63 只村民连同各自的职业、交易表、声望全部凭空消失且无法找回; 装备好的盔甲架 (常用于展示柜/仓库门面) 只要有玩家路过所在区块触发 chunksWithMovement, 就会被合并并把身上的护甲和武器一起 discard 掉。这是不可逆的玩家资产损毁, 且没有任何日志或提示。
- **建议**: 同上, 走显式 EntityType 准入白名单; 在准入白名单落地前, 至少把 Villager/WanderingTrader/ArmorStand/IronGolem/SnowGolem 硬编码排除。另外 discard 前应先判定被并实体是否携带不可重建状态 (Mob.getArmorSlots/getHandSlots 非空、AbstractHorse 的鞍与箱、Villager 的 offers), 携带即拒绝合并而不是静默销毁。
- **复核**: 维持 — 事实全部核对成立, 且比审计员描述的触发条件更宽: 1) 黑名单确为空: StackingConfig.java:114-115 `EXCLUSIONS_BLACKLIST = ... .defineList("blacklist", List.of(), o -> o instanceof String)`, StackMerge.java:83-86 空表直接 return false 放行。 2) 类型确实全部落网: 反编译源码核对 ArmorStand.java:46 `public class ArmorStand extends LivingEntity`, Villager.java:92 `public class Villager extends AbstractVillager`; 对两文件 grep `canChangeDimensions` 零命中, 即均不 override, 恒 true -> isBoss=false。二者不 hasCustomName、非 TamableAnimal, canStack 全放行。 3) 等键成立: StackMatchKey.variantSignature (StackMatchKey.java:50-65) 只写了 Sheep/Creeper/AbstractHorse 三支, 其余一律 `return ""`; 村民职业/等级/交易表、盔甲架穿戴物、傀儡血量均不进键。两个不同职业村民等键。 4) 销毁不可逆: StackMerge.java:152 `other.discard()`, 该路径不触 LivingDeathEvent, 无 dropAllDeathLoot, 盔甲架身上的护甲武器随实体一并消失。 推翻不成立, 反而要补两条加重情节: - 审计员说需要 "玩家路过所在区块触发 chunksWithMovement" 才会合并静止实体, 实际门槛更低: StackingSystem.java:48 的 lastSeenPos 是进程级瞬态 Map, StackingSystem.java:139-142 ServerStopping 清空, 而 :133-135 hasMoved 对无记录返回 true。故每次服务端重启后的第一轮扫描, 所有已加载区块一律进 chunksWithMovement, 全部村民/盔甲架当场被压堆 —— 不需要任何玩家路过。 - 该扫描覆盖 server.getAllLevels() 全维度 (StackingSystem.java:83-85), 主世界交易大厅与展示柜同样在内。 判 Critical 成立: 不可逆玩家资产损毁 + 零日志零提示。


#### F006 · 30 天回收 bundle_operations 会摧毁开箱资产的归属凭据: 老皮肤集体失效, 玩家每 30 天被重复扣一次 50000 信用点 + 10 青辉石

- **维度**: 缺陷 | **严重度**: Critical | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/economy/EconomySystem.java:110`
- **证据**: EconomySystem.java:64 定义 TERMINAL_OPERATION_RETENTION_MILLIS = 30 天, :110 每次 ServerStarted 调 ledger.pruneTerminalOperations(now - 30天); SqliteEconomyLedger.java:193-194 执行 DELETE FROM bundle_operations WHERE status IN ('COMPLETED','REFUNDED') AND created_at < ?。而开箱侧把这张表当成永久归属凭据: CaseOpeningService.java:469-471 isEconomySettled(asset) 判的是 economy.state(ownerId, sourceOpeningId) == COMPLETED, 行被删后 SqliteEconomyLedger.findOperation 返 null -> operationStatus 返 NONE。后果三处连锁: (1) CaseOpeningService.java:96-98 ownedAssets 用 isEconomySettled 过滤, 老资产从 case.state 回执里整批消失; (2) CaseTaczBridge.java:61-71 enforce 的 authorized 含 settledOwnership.test(asset), 判 false 即 resetToDefault 把枪上的皮肤 NBT 抹掉; (3) CaseDaoSqlite.java:142-144 recoverableOpenings 的状态集合含 'COMMITTED', 登录恢复 CaseOpeningService.java:159-172 -> resume :247-263 在 COMMITTED 分支【无条件】economy.charge(player, openingId, row.creditCost(), row.azureCost()), 幂等行已被删故这是一次真实扣款 (CaseOpeningConfig.java:26-27 默认 50000 CREDIT + 10 AZURE)。EconomyGameTests.java:749-755 只在账本内部断言 '终态记录回收后应查不到', 无任何测试覆盖开箱侧后果。
- **影响**: 公服连续运行超过 30 天后必然发生: 每次重启把 30 天前的开箱幂等行删光, 玩家下次登录时 recoverFor 会为每一条历史 COMMITTED 开箱重新扣 50000 信用点 + 10 青辉石 (N 箱扣 N 次), 扣完写新行, 30 天后再被删再扣一轮, 形成周期性抽血。余额不足时 resume 抛 IllegalStateException, 该异常从 open() 的 recoverFor(:117, 在 try 之外) 冒泡, 玩家从此再也开不了新箱, apply(:197-202) 也永远失败。同时期间玩家已购皮肤在面板上消失、握在手里的枪每秒被强制还原成默认外观。
- **建议**: 两个模块对 bundle_operations 的生命周期假设互斥, 必须选一侧收口: 要么开箱侧不再拿幂等记录当归属凭据 (在 skin_assets 上落一个自己的 settled 列, 由提交事务同事务写入, isEconomySettled 改读它), 要么回收侧按域豁免 (CASE_OPENING 域的 COMPLETED 行永不回收) 并在 EconomyLedger.pruneTerminalOperations 的契约里写明哪些域禁止回收。另外 recoverFor 不应把 COMMITTED 且账本已 COMPLETED 的行反复送进 resume, 应在进入前短路。
- **复核**: 维持 — 整条链逐环读码验证, 全部属实, 没有任何前置门挡住。(一) 回收侧: EconomySystem.java:64 TERMINAL_OPERATION_RETENTION_MILLIS = 30 天, :110-111 在 ServerStarted 内无条件调 ledger.pruneTerminalOperations(now-30天); SqliteEconomyLedger.java:192-200 的 SQL 就是 DELETE FROM bundle_operations WHERE status IN ('COMPLETED','REFUNDED') AND created_at < ?, 无 domain 过滤 (bundle_operations 建表见 MiningSchema.java:119-126, 有 domain 列却未参与回收条件)。(二) 归属凭据侧: CaseOpeningService.java:469-471 isEconomySettled 唯一判据就是 economy.state(ownerId, sourceOpeningId)==COMPLETED; SqliteEconomyLedger.java:141-149 operationStatus -> findOperation(:391-410) 行不在即返 null -> NONE。skin_assets 建表 (MiningSchema.java:88-98) 确实没有任何自有 settled 列, 无第二真源可回退。(三) 三处后果全部复现: ownedAssets (CaseOpeningService.java:94-99) 用 isEconomySettled 过滤; CaseTaczBridge.java:61-65 authorized 含 settledOwnership.test(asset), false 即 :69 resetToDefault 抹 NBT, 且 CaseTaczEventHooks.java:29-41 在 GunFire/GunShoot 上 setCanceled(true), 即老皮肤玩家那一枪先被取消再被还原成默认外观; 最关键的 resume 重复扣款成立 —— CaseDaoSqlite.java:139-144 recoverableOpenings 的状态集合确实含 COMMITTED, CaseOpeningService.java:167-169 的 alreadySettled 依赖 economy.state (被删后为 NONE), 进 resume :247-263 的 COMMITTED 分支【无条件】economy.charge; 而 EconomyCaseOperations.java:41-46 的 charge 走 tryChargeBundle, SqliteEconomyLedger.java:121-137 在 findOperation 返 null 时【真的走 wallet.tryDebitBundle 扣钱并插新行】。成本确认为 CaseOpeningConfig.java:26-28 的 50000 CREDIT + 10 AZURE。(四) 时序也对上: EconomySystem.onServerStarted 是默认优先级、CaseOpeningSystem.onServerStarted 显式 EventPriority.LOW (CaseOpeningSystem.java:75-76), 故先删后对账; 而 reconcileCommitted (CaseOpeningService.java:420-438) 对 state==NONE 明确返 0 不处置, 把补扣款留给登录, 正好落进重复扣款分支。新扣款写入的 created_at 是当下时间, 30 天后再被删再扣, 周期性抽血成立。仅两处对审计员描述做修正 (不影响定级): 余额不足时 resume 抛出的异常在登录路径被 CaseOpeningSystem.java:98-102 catch 并记 ERROR, 登录不会中断, 但 recoveryAuditedPlayers 未置位, open()(:117) 与 apply()(:197-202) 从此长期失败, 结论一致; EconomyGameTests.java:733-759 确如所述只断言账本内部语义, 开箱侧零覆盖。


### A.2 Major (36 条)


#### F007 · 页面授权用整串 URL 精确匹配, 但 webui.url 配置只校验 scheme 前缀且不做归一化, 换一个非规范地址即全部 action 被拒

- **维度**: 缺陷 | **严重度**: Major | **审计域**: WebUI 服务端与网络基建 (webui / network / client / menu / registry)
- **位置**: `src/main/java/com/miningdim/client/webui/WebUiBridge.java:111`
- **证据**: WebUiBridge.onQuery 第 110-113 行: `if (allowed == null || cefBrowser == null || frame == null || !frame.isMain() || !allowed.equals(cefBrowser.getURL())) { callback.failure(-3, "WebUI query rejected: untrusted page or subframe"); return true; }` —— allowed 是 WebUiClient.openWebUi 第 102 行原样传入的 `MiningClientConfig.WEBUI_URL.get()` 字面量。而 MiningClientConfig.java:48 的定义是 `.define("url", "http://localhost:5173/", MiningClientConfig::isHttpUrl)`, 其校验器 (第 59-64 行) 全部内容只有 `s.startsWith("http://") || s.startsWith("https://")`。两侧之间没有任何归一化: 配置里存的是运维手打的字面量, 比较的是 Chromium 归一化后的实时文档 URL。webui/src/router.ts 第 6-16 行已经识别出这条精确匹配对 fragment 的脆弱性并因此禁止前端写 location, 但只处理了 fragment 一种成因。
- **影响**: 只要运维配置的地址与 CEF 归一化后的文档 URL 有一个字节的差异, 整个 WebUI 的服务端 action 100% 被拒。至少三条常见成因: (1) 填 host-only 地址 `https://ui.example.com` (无尾斜杠), Chromium 归一化成 `https://ui.example.com/`; (2) 站点做 301/302 (例如 /webui -> /webui/ 或 http->https); (3) HSTS 把 http 升级成 https。症状是页面能加载、能翻页, 但所有数据请求都以 -3 失败, 而 -3 在契约里 (webui/src/lib/bridge.ts:273) 的含义写死为"页面被塞进 iframe 或改过 location", 排障会被带向完全错误的方向。当前默认值 http://localhost:5173/ 恰好是规范形式, 所以这条隐患要等切生产地址那天才炸。
- **建议**: 授权比较改为对双方做同一套归一化后再比 (去 fragment、补空路径的尾斜杠), 或者干脆改成"scheme+host+port+path 前缀匹配、忽略 fragment 与 query"; 同时把配置校验器从 startsWith 提升为真正解析一次 URI 并回写规范化结果, 让不规范写法在配置加载期就被纠正而不是在运行期变成静默拒绝。router.ts 顶部那段偏离说明也应随之更新。
- **复核**: 维持 — 逐条读码全部对上, 且没有任何上游门挡住。(1) WebUiBridge.java:109-113 确实是整串精确匹配: `String allowed = allowedPageUrl; if (allowed == null || ... || !allowed.equals(cefBrowser.getURL())) { callback.failure(-3, ...); return true; }`。(2) 被比较的 allowed 一路是运维手打字面量, 中间零加工: WebUiClient.java:102 `openScreen(MiningClientConfig.WEBUI_URL.get(), "WOK", false)` -> WebUiClient.java:133 `BRIDGE.setAllowedPage(pageUrl)` -> WebUiBridge.java:89-97 的 setAllowedPage 只做 startsWith("data:text/html"/"http://"/"https://") 三选一的前缀判断后 `this.allowedPageUrl = pageUrl`, 全程没有 URI 解析或归一化。(3) 配置端同样只有前缀校验: MiningClientConfig.java:47-48 `.define("url", "http://localhost:5173/", MiningClientConfig::isHttpUrl)`, 校验器 59-63 行整个方法体就是 `s.startsWith("http://") || s.startsWith("https://")`。我对全库 grep `webui.url|WEBUI_URL` 只得到这两处代码 + 三处注释/文档, 确认不存在别处的归一化收口。(4) 相反, 这三处注释恰恰证明团队已被这条精确匹配咬过三次却每次只补一个成因: webui/src/router.ts:6-21 为 fragment 禁止前端写 location; webui/src/components/kit/README.md:22 复述同一约束; webui/vite.config.ts:242 因为它禁掉 dev server 端口顺延。审计员说的三条成因中 host-only 地址是必然踩中的 (Chromium GURL 对 http(s) 这类分层 scheme 会把空路径补成 "/"), 301/HSTS 升级则会让 getURL 返回跳转后的最终地址。症状是页面能加载能翻页但所有数据请求以 -3 失败, 而 webui/src/lib/bridge.ts:273 把 -3 的语义写死为"页面被塞进 iframe 或改过 location", 排障必被带偏。默认值恰好是规范形式, 故只在切生产地址那天炸 —— 属于"一定会到来的部署期硬失败", 维持 Major, 不降级。


#### F008 · WebUI 派发路径全程无速率限制, 单个客户端可无限刷服务端主线程 action 并刷爆日志

- **维度**: 性能 | **严重度**: Major | **审计域**: WebUI 服务端与网络基建 (webui / network / client / menu / registry)
- **位置**: `src/main/java/com/miningdim/webui/server/WebUiServerDispatcher.java:103`
- **证据**: C2SWebUiRequest.handle (C2SWebUiRequest.java:43) 对每个到达的包无条件 `ctx.enqueueWork(...)` 切主线程, 随后 dispatchAndRespond 内除了 markRequestProcessed 的 requestId 去重外没有任何节流。去重按 requestId 键 (第 107 行), 攻击者只要每次换一个新 id 就完全绕开。对 webui/network/client 三个包做 `rg -ni "ratelimit|throttle"` 只命中 WebUiErrorCodes.java:100 的 RATE_LIMITED 常量, 其抛出点注释写明只在 CaseOpeningService.enforceNewOpenRateLimit (即只覆盖 case.open 一条)。而 webui/src/hooks/use-live-updates.ts:36-38 自己写明: economy.today 打 1 次 SQLite 且跑在服务器主线程, player.profile 每次打 3 次 SQLite。另外第 124-128 行对任何非 WebUiBusinessException 一律 `LOGGER.warn(..., e)` 打整条堆栈, 而未知 action (第 113-114 行 IllegalArgumentException) 与坏 payload 都走这条。
- **影响**: 满编公服上任意一个装了改版客户端的玩家 (或前端一个失控的 useEffect 循环) 每秒发数千个 market.list / economy.today / player.profile, 全部串行落在服务端主线程并逐个打 SQLite, TPS 直接被拖垮, 且服务器任务队列无界增长; 同一手法换成未知 action 名还能让每个包都往日志写一条完整 WARN 堆栈, 短时间内把日志盘写满。两种后果都不需要任何权限, 普通玩家即可触发。
- **建议**: 在 Gateway 入口加一层按 sender UUID 的令牌桶 (与 requestId 窗口同一张表, 复用登出清理), 超限直接回一个稳定 errorCode 的业务拒绝而不是执行 handler; 并把"未知 action"这类攻击者可无限触发的失败从 WARN+堆栈降级为 DEBUG 或带采样的 WARN, 与 WebUiBusinessException 已经享有的"不打堆栈"待遇对齐。
- **复核**: 维持 — 事实全部核实, 且我另外找到一处比审计员描述更狠的放大器。(1) 入口无门: C2SWebUiRequest.java:41-51 对每个到达包无条件 `ctx.enqueueWork(...)`, 仅判 sender != null; MiningNetwork.java:71-73 注册该包时也只声明 PLAY_TO_SERVER, 无任何前置校验。(2) 派发器内唯一的门是 dispatchAndRespond 第 107 行的 `markRequestProcessed(sender.getUUID(), requestId)`, 其实现 185-198 行只是按 requestId 的 256 条滑动窗口去重, 换 id 即完全绕开 —— 它是防重放, 不是限流。(3) 全库确认限流只有一处且与本路径无关: 对 webui 包 grep 限流关键字只命中 WebUiErrorCodes.java:99-100 的 RATE_LIMITED, 注释明写抛出点是 CaseOpeningService.enforceNewOpenRateLimit (只覆盖 case.open)。WebUiServerSubsystem.java:40-42 挂的只有登出清理, 没有任何节流。(4) handler 确实吃 SQLite: EconomyWebUiActions.java:138-141 economy.today 走一次 SELECT, 且全程在主线程 (enqueueWork 已切回)。(5) 日志放大比审计员写的更严重: WebUiServerDispatcher.java:124-128 的 `LOGGER.warn("Web UI action '{}' failed for player {} (requestId={})", action, ..., e)` 把 action 原文与整条堆栈一起写盘, 而 C2SWebUiRequest.java:30-31 的 `buf.readUtf()` 用的是默认上限 FriendlyByteBuf.MAX_STRING_LENGTH(32767), 即 action 名是攻击者可控的 32KB 标量 —— 每个垃圾包能写进日志约 32KB 文本 + 一条完整栈, 同一份 32KB 还会再经第 128 行 errorJson(e.getMessage()) 回执一遍。不升 Critical 的唯一理由: 触发需要改版客户端自造包, 且"C2S 无限流"在本仓是系统性缺口而非 WebUI 独有 (SelectZoneC2S.java:46-68 同样零限流)。但 WebUI 是唯一挂着上百个打 SQLite 的 handler 的入口, 维持 Major。


#### F009 · 军火台台数计数按"破坏者"而非"台主"回收, 可无限刷放置额度绕过 L10 六台上限

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/MunitionsSystem.java:127`
- **证据**: onBenchBroken 只判 `event.getState().getBlock() instanceof MunitionsBenchBlock`, 然后 `MunitionsSavedData.get(overworld).decrement(player.getUUID())` —— 既不查 BE 的 ownerUUID, 也没有 isMain 过滤。放置侧 onBenchPlace (line 98-123) 却是按放置者 UUID increment。MunitionsSavedData.decrement (SavedData.java:60) 只钳到 0, 不校验来源。
- **影响**: 两个方向都坏。(一) 刷额度: B 放一台, A 挖掉, A 的计数 -1 (B 的不变), 循环即可让 A 的 benchCount 归零而自己那 6 台照旧存在 -> A 可以摞出 12/18/N 台被动产线, 军火商 6.1 的台数曲线彻底作废, 一个账号把铜+火药按小时无上限转成高价弹药。(二) 额度泄漏: 台被 TNT/苦力怕/活塞/级联 updateShape 毁掉时根本没有 BreakEvent, 台主计数永远不减, 被炸过基地的玩家从此再也放不下新台。
- **建议**: decrement 的对象应取 BE 的 ownerUUID (破坏前从 BlockEntity 读), 并补 isMain 过滤; 非玩家破坏路径 (onRemove/爆炸) 也要走同一个回收点, 否则计数只增不减。
- **复核**: 维持 — 核心成立, 但审计员的 isMain 那半条要更正。事实核对: MunitionsSystem.java:127-139 onBenchBroken 确实只判 event.getState().getBlock() instanceof MunitionsBenchBlock, 随后 MunitionsSavedData.get(overworld).decrement(player.getUUID()), 全程不读 BE 的 ownerUUID; 放置侧 98-123 却是按放置者 increment; MunitionsSavedData.java:60-69 decrement 只 Math.max(0,..) 不校验来源。可达性也确认: MunitionsBenchBlock.playerWillDestroy (135-151) 与 BreakEvent 路径均无归属保护, 任何人都能挖别人的台 (归属只在 setPlacedBy:128-131 写 BE, 只用于 GUI 权限)。故 A 挖 B 的台 -> A 计数 -1 而 B 不变, A 可摞出超额产线, 且被挖的 B 计数永不回收 = 永久失去配额, 一次操作双向获利, 这在 8 职业里唯一的被动弹药 faucet 上是真实的印钞面。额度泄漏那半条也成立: 我核了 ModMunitionsBlocks.java:56-63, 台走 BlockBehaviour.Properties.copy(Blocks.SMITHING_TABLE) (爆炸抗性 2.5), 苦力怕/TNT 能炸掉, 而爆炸不触发 BreakEvent, 计数只增不减。更正: 补 isMain 过滤没有必要 —— 主/副半块同属一个 Block 实例, 玩家只会直接破坏其中一块并触发一次 BreakEvent, 另一半由 updateShape (MunitionsBenchBlock.java:154-162) 返回 AIR 且不发事件, 不存在双扣。另注: FarmerSystem.java:302/322 是同一写法, 属全库同源隐患而非本模块新增。维持 Major。


#### F010 · 选口径没收敛到台主: 任何路人都能改别人军火台的口径并把离线补产的时间窗清零

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:248`
- **证据**: trySelectCaliber 的门是 `if (!canAccess(player)) return false;` —— canAccess (line 233) 在未上锁时对所有人恒 true (默认 locked=false)。同一个类里 tryStartCraft (line 288)/cancelCraft (line 322)/toggleContinuousCrafting (line 332) 都已按审查 M-3 收敛成 `isOwner(player)`。更糟的是它随后用的是点击者的等级 `MunitionsLevels.munitionsLevel(player)` 去过等级门, 并把结果写进台主的缓存 (line 265-266 `this.ownerLevelCache = level; this.refineUnlockedForOwnerCache = ...`), 最后 line 267-270 无条件 `settleInitialized = true; lastSettleTick = this.level.getGameTime();`。对照兄弟实现 ProductionTableBlockEntity.trySelectTier (engineer/block/ProductionTableBlockEntity.java:205) 用的是 `canAccess && canTakeOutput`, canTakeOutput 要求 owner 或 OP。
- **影响**: 任何人走到别人未上锁的军火台前点一下口径, 就把 lastSettleTick 推到当前, 台主离线期间攒下的全部流逝时间 (五章离线一次性补产的唯一依据) 被抹掉, 产出归零 —— 反复点即持续性 grief。同时高等级路人可以选一个台主造不出的口径 (下次结算被判无权后 selectedCaliber 直接置 null), 等于远程停掉别人的产线; ownerLevelCache 被污染后 GUI/WebUI 显示的 bufferCap 也跟着错。
- **建议**: 把 trySelectCaliber 的门改成与 tryStartCraft 同源的 isOwner, 等级一律取台主等级而非点击者; 缓存字段只允许在"台主在场帧"刷新。
- **复核**: 维持 — 逐字核实成立, 且整条调用链无前置门拦截。MunitionsBenchBlockEntity.java:248-273 trySelectCaliber 的门确实只有 canAccess(player); canAccess (233-238) 在 locked=false 时对所有人恒 true, 而 locked 默认 false (字段 99 行无初始化)。入口可达: MunitionsBenchBlock.use:190-196 未上锁即 NetworkHooks.openScreen 给任何 ServerPlayer, MunitionsBenchMenu.java:89-91 的 clickMenuButton 把 [0, caliber count) 直接路由到 trySelectCaliber。危害两条我都验到源码: 其一 267-270 无条件 settleInitialized=true; lastSettleTick=now, 而 settleForOwner:388 的 elapsed = now - lastSettleTick 是五章离线补产的唯一依据, 主人离线期间被路人点一下即清零 (可反复点, 持续 grief); 其二 255 行用的是点击者等级 MunitionsLevels.munitionsLevel(player), 高等级路人可选台主未解锁的口径, 下次 settleForOwner:397-403 判无权后 selectedCaliber=null 直接停产。同类对照也属实: 同类 tryStartCraft:288 / cancelCraft:322 / toggleContinuousCrafting:332 已按 M-3 收敛成 isOwner, 兄弟实现 ProductionTableBlockEntity.java:204-206 用 canAccess && canTakeOutput(=owner 或 OP)。唯一可打的折扣是台主可 shift+空手上锁 (MunitionsBenchBlock:177-188), 但默认未锁, 且同类方法已全部收敛, 这就是漏改一处。维持 Major。


#### F011 · 枪械 tooltip 走的是会抛异常的 from(), 平衡表一改或版本回退就会在悬浮时崩客户端

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/MunitionsSystem.java:143`
- **证据**: onItemTooltip 里 `GunsmithGunStats stats = GunsmithGunStats.from(event.getItemStack());`。from() 构造时会跑 validateCurrentStats (GunsmithGunStats.java:349-375), 它用 `Double.compare(encoded, expected) != 0` 把 NBT 里缓存的 stats 与按当前品质/变体表重算的值做精确相等比对, 不等即 throw IllegalArgumentException; version(root) (line 249) 对 `version > CURRENT_VERSION` 也直接抛。同一个类 line 71-88 就为此专门提供了不抛的 tryFrom, 注释写明"只读展示 ... 点开详情不该报错"; GunsmithBlueprintItem.java:70-79 也已经为渲染钩子建立了同一条纪律 ("抛异常会直接崩客户端 —— 无外层 Controller 兜底")。tooltip 这条路偏偏没用。
- **影响**: 任何一次品质系数/变体加成调整, 或把 mod 回退到更低的 CURRENT_VERSION, 都会让玩家背包里的存量枪集体变成"读不出来"。ItemTooltipEvent 是在客户端渲染 tooltip 时触发的, 异常没有兜底层 -> 玩家鼠标划过背包里那把枪即崩客户端, 且每次重进背包都复现, 等于该玩家的存档被那把枪锁死。
- **建议**: tooltip 分支改用 tryFrom, 读不出来时渲染一条降级提示 (与 gunsmith_blueprint.invalid 同范式); 硬校验保留在装配/伤害结算路径。
- **复核**: 维持 — 事实全对。MunitionsSystem.java:143 确实是 GunsmithGunStats.from(event.getItemStack()), 且该 handler 经 74 行 forgeBus.register(this) 无条件挂载 (不受 TACZ 是否加载影响, 与 78 行的 GunsmithTaczStatsHandler 不同)。抛点核实: GunsmithGunStats.java:55-68 from -> 构造器 42-43 -> validateCurrentStats:349-375, validateCurrentStat:370-375 用 Double.compare(encoded, expected)!=0 精确比对即 throw; version():240-253 对 version>CURRENT_VERSION 直接 throw; readParts:294-298 还会对 coefficient 落在 quality.min/maxCoefficient 之外 throw —— 也就是说光调 GunsmithPartQuality 的系数区间 (纯平衡动作, docs/Gunsmith_Component_Balance_Roadmap.md 正是为此存在) 就会让存量枪集体读不出。降级出口确实就在同类 81-88 的 tryFrom, 其 javadoc (71-79) 明写只读展示不该报错, WebUiItemDetailJson.java:82 已经用了它; GunsmithBlueprintItem.java:69-79 也已就渲染钩子立过同一条纪律 (注释原文: 抛异常会直接崩客户端 —— 无外层 Controller 兜底)。ItemTooltipEvent 在客户端渲染线程触发, 异常无兜底层, 崩溃成立。唯一减轻项: MunitionsConfig.java:196 gunsmithEnabled 默认 false, 正式服现在没有存量枪, 属潜伏而非在线故障 —— 但 handler 已挂、修法只有一行、同仓已有两处同款纪律, 不足以降级。维持 Major。


#### F012 · 写操作成功后的镜像刷新失败会把已经生效的写操作报成失败, 并丢弃回执 —— 开箱的 openingId 幂等保护因此被绕开

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/mock/handlers.ts:210`
- **证据**: delegateReal 把只读的镜像刷新串在写操作的成功路径上: ```ts const result = await callErased(action, payload) if (MIRROR_AFTER_WALLET_INVENTORY.has(action)) { await refreshWalletAndInventory() } else if (MIRROR_AFTER_CASE.has(action)) { await refreshCaseTotals() } return result ``` refreshWalletAndInventory 是 `Promise.all([call('player.wallet'), call('player.inventory'), call('market.mine')])` (handlers.ts:130-134), 任一条 reject 整条就 reject, 于是**已经成功的写操作**变成 callMock 的 rejection, `result` 被丢掉。下游各页面的 catch 都把它当业务失败展示: CasePage.runOpen 的 catch 走 describeFailure(error), 而这时的 error 是 case.state 的错误 —— 它没有 business 信封, 于是 `retrySameOpeningId: false` (CasePage.tsx:139-152), 紧接着执行 `setRetryOpeningId(null)` (CasePage.tsx:794-796)。
- **影响**: case.open 已扣费并已发放皮肤, 界面却报失败且把 openingId 清空; 玩家再点一次开箱会用**新的 openingId** 发起请求, 服务端按新单再扣一次费 —— 这正是 openingId 幂等机制要防的事故, 被这条链整条绕开。同类路径还有: MarriagePage.handleBuyRing (MarriagePage.tsx:263) 钱已扣戒指已发却报错, 重试再买一枚; TarotPanel.handleBuyPack (TarotPanel.tsx:229) 重试再买一份卡包; SellPage.handleSubmit (SellPage.tsx:208) 挂单已成立且上架费已收却报错, 重试再交一次上架费; BrowsePage 的 BuyDialog (BrowsePage.tsx:422) 已买入却显示"购买未生效", 玩家再买一次。
- **建议**: 镜像刷新不能参与写操作的成败判定。把 refreshWalletAndInventory / refreshCaseTotals 改成 fire-and-forget (catch 后只写控制台与一条独立的"余额显示可能过期"提示), 让 delegateReal 无论刷新成败都返回原始 result。若确实需要让调用方知道刷新失败, 应通过一个与写操作回执分离的通道 (如 store 里的 mirrorError) 表达。
- **复核**: 维持 — 逐环节读码确认。mock/handlers.ts:207-215 的 delegateReal 确实把只读镜像刷新 await 在写操作成功路径上, 刷新 reject 即整条 callMock reject, result 被丢弃; refreshWalletAndInventory (:129-141) 是 Promise.all([player.wallet, player.inventory, market.mine]), 任一条挂整条挂。  下游确认: CasePage.tsx:768-799, case.open 成功后若 refreshCaseTotals (case.state) 抛错, 走 catch -> describeFailure (:139-153) -> case.state 的错误没有 business 信封故 retrySameOpeningId 恒 false -> :794-796 setRetryOpeningId(null); 下次点击 :750 用 retryOpeningId ?? createOpeningId() 生成**新** openingId, 服务端按新单再扣一次费, 正好绕开 openingId 幂等 (CasePage.tsx:660-663 的注释写明该字段就是防重复扣费的全部实现)。同链的其余四处也逐一核实属实: BrowsePage.tsx:418-435 (market.buy 已成交却显示失败, 重试即再买一次)、SellPage.tsx:206-215 (market.place 已成立且上架费已收)、MarriagePage 买戒指、TarotPanel 买卡包。  补一条审计员没查、但让这条从"偶发"变成"对重度卖家确定性发生"的触发源: refreshWalletAndInventory 里的 market.mine (MarketActions.java:173-185) 回的是该玩家全部 ACTIVE 挂单且**无任何条数上限** (MarketEngine.place 只对铜铁 6 个标的有每日量 cap, 全库无活跃挂单条数上限), 单行 listingJson 200+ 字符, 挂到约 150 条即恒定撞 WebUiServerDispatcher.java:148 的 32767 收口 -> RESPONSE_TOO_LARGE。此后该玩家的每一次 market.place / market.buy / market.cancel / job.farmer.sell / job.tarot.buyPack / marriage.* / admin.economy.set 都会"成功了却报失败"。经济是重灾区且这条直通重复扣费, 维持 Major。


#### F013 · 管理后台在生产构建里读的是 mock 种子身份 (测试员_Mock / isOp:true), 默认调账目标是一个不存在的玩家, 非 OP 还会看到绿色 OP 徽标

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/pages/admin/AdminPage.tsx:1093`
- **证据**: mock/seed.ts:254 硬编码 `player: { name: PLAYER_NAME, isOp: true }`, PLAYER_NAME = '测试员_Mock' (seed.ts:27)。全库只有 TabletShell.tsx:325 的 OP 视图开关会写 draft.player.isOp, 而那个开关被 `isMockActive()` 包住 (TabletShell.tsx:320), 生产构建里恒不渲染; 没有任何地方把真实 player.isOp / player.profile.playerName 写回 world.player。AdminPage 却直接读它: `const [target, setTarget] = useState(world.player.name)` (:1093)、`.filter((name) => name !== world.player.name)` (:1117)、`{ value: world.player.name, label: \`${world.player.name} (我自己)\` }` (:1119)、`if (target === world.player.name)` (:1135)、`<Tag tone={world.player.isOp ? 'success' : 'danger'}>` (:1153) 与 `{world.player.isOp ? null : <Surface tone="danger">…}` (:1160)。已在构建产物 dist/assets/index-BzmGJXAo.js 中确认字符串 '测试员_Mock' 存在, 即种子确实随生产包发布。
- **影响**: OP 在游戏里打开管理后台: 经济调账/职业调级的目标玩家默认填的是不存在的 '测试员_Mock', 点"查询余额"必被服务端拒绝; 玩家选择器第一项是伪造的"测试员_Mock (我自己)", 而真正的自己混在下面的 roster 普通项里 (player.roster 服务端不剔除自己, PlayerWebUiActions.java:89-99); 职业调级面板对该默认目标显示的"当前等级"取自 seedJobProgress 的假数据 (:1136-1137)。更糟的是 isOp 恒为 true: 一个非 OP 玩家进到 /admin 会看到绿色 "OP" 徽标且**没有**那条"所有提交都会被服务端拒绝"的警告横幅, 只会收到一连串看不懂的权限异常 (服务端权限本身仍在, 不构成越权, 但界面在说谎)。
- **建议**: 把 world.player 这个 mock 身份从生产路径上摘掉: AdminPage 的 target 初值、"(我自己)"标注、roster 去重、currentLevel 分支一律改读真契约 (player.profile 的 playerName / player.isOp 的 isOp / job.progress); 顶部那条身份横幅改吃 opState 的回执。世界里的 player 字段应只在 isMockActive() 下参与渲染, 或干脆随 walletOverlay 一并清理。
- **复核**: 维持 — 主体属实。mock/seed.ts:27 PLAYER_NAME='测试员_Mock', :249 player:{name:PLAYER_NAME,isOp:true}; mock/store.ts:102 `let world = createInitialWorld()` 是模块级无条件执行, 不带任何 isMockActive 门, 种子确实随生产包发布。全库写 draft.player 的只有 TabletShell.tsx:325 那个被 :320 isMockActive() 包住的 OP 视图开关, 生产构建恒不渲染, 没有第二个写入方。AdminPage.tsx:1093/1117/1119/1135 读的确实是 world.player.name (mock 种子名), :1153-1154/:1160 读的是 world.player.isOp (恒 true)。  更硬的一条佐证是服务端契约本身点名了正确来源: PlayerWebUiActions.java:85-86 明写"也不剔除调用者自己 —— 剔了就得在服务端定义'自己'这个语义 ... 前端拿 player.profile 里的名字自行过滤即可", 而 AdminPage 拿的是 mock 种子名, 于是这条过滤 (:1117) 在真服上过滤不掉任何人, "(我自己)"那一项挂的是个不存在的账号。  还发现审计员漏报的一处同源污染 (严重度同档, 并入本条): AdminPage.tsx:1124-1128 的**职业下拉选项表**也来自 world.jobs.progress, 即 seed.ts:136-146 的 JOB_ROWS 硬编码 8 条。今天恰好与真服 8 职业对得上, 但第 9 个职业 (空军) 上线后 OP 在管理后台里根本选不到它去调级, 而这不会报任何错。:1134-1142 的 currentLevel 对默认目标读的同样是这份种子假等级。  被推翻的部分: "非 OP 玩家进到 /admin 会看到绿色 OP 徽标且缺警告横幅"不成立 —— TabletShell.tsx:204-205 的导航项过滤用的是真契约 player.isOp 的回执 (opState), 非 OP 侧栏里根本没有管理后台入口; router.ts:6-16 明写运行期绝不写 location, 玩家没有地址栏也改不了 hash, 该路由对非 OP 实际不可达。故本条的真实危害面只在 OP 侧: 默认目标不可用 + "我自己"标错人 + 职业表与等级取自 mock 种子, 属于"管理台在说谎", 仍判 Major。


#### F014 · 一次写操作被放大成十余次服务端往返, 且 player.profile (每次 3 次 SQLite) 被重复触发两轮

- **维度**: 性能 | **严重度**: Major | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/pages/jobs/panels/FarmerPanel.tsx:103`
- **证据**: job.farmer.sell 已经在 MIRROR_AFTER_WALLET_INVENTORY 表里 (handlers.ts:113-114), delegateReal 会自动调 refreshWalletAndInventory() 打三条 (player.wallet / player.inventory / market.mine); FarmerPanel.handleSell 在 callMock 返回后又显式调了一次 `loadInventory()` (FarmerPanel.tsx:103 → :58 refreshWalletAndInventory), 于是同样三条再打一遍。每次 refreshWalletAndInventory 结尾都 mutateWorld (handlers.ts:135), revision 各 +1; TabletShell 的 revision effect (TabletShell.tsx:193-201) 每次都会 reloadProfile + reloadServer + reloadIsOp 三条, 而 lib/types.ts 与 HomePage.tsx:73-74 都注明 player.profile "每次都要打 3 次 SQLite 且跑在服务器主线程"。加上最后的 query.reload() 拉 job.farmer.state, 单次"出售"点击共 14 条服务端往返, 其中 player.profile 出现两次 = 6 次 SQLite。
- **影响**: 满编公服上, 卖菜/挂单/买入/买卡包/买戒指/开箱这些高频动作每次都在服务器主线程上放大成 7-14 条查询; 农夫卖菜是最坏的一档 (双份镜像刷新)。此外镜像刷新是 await 在按钮的成功路径上的, 玩家点"出售"后要等 4 条往返才看到回执; 而 TabletShell 的 useMockAction 每次 reload 都会先把状态置回 loading, 于是顶栏余额在每次写操作后都会消失一个往返再回来。
- **建议**: FarmerPanel 删掉 handleSell 里那次重复的 loadInventory (delegateReal 已经刷过); 把镜像刷新改成不 await (见 handlers 那条 finding), 让按钮回执不必等它; 给 revision 触发的重查加合并/节流, 或者干脆只在真正变化的那块镜像上做细粒度订阅, 不要让一次 mutateWorld 就把外壳的三条聚合请求全部重发。
- **复核**: 维持 — 逐条核实且计数吻合。mock/handlers.ts:113-114 job.farmer.sell 确在 MIRROR_AFTER_WALLET_INVENTORY 表内, delegateReal (:209-210) 已经打过一轮 player.wallet/player.inventory/market.mine; FarmerPanel.tsx:100-104 在 callMock 返回后又显式调 loadInventory() (:57-65 直接就是 refreshWalletAndInventory), 同样三条再打一遍 —— 这是一处纯粹的重复, 删一行即可。  两轮镜像刷新各 mutateWorld 一次 (handlers.ts:135), 且因为第二轮不是 await 在同一微任务内, 是两次独立的 revision bump; TabletShell.tsx:192-201 的 revision effect 每次都 reloadProfile+reloadServer+reloadIsOp, 故 3x2=6。合计: 1 (sell) + 3 + 3 + 1 (job.farmer.state) + 6 = 14, 与审计员的数字一致。  player.profile 的成本也确认: PlayerWebUiActions.java:239-271 依次 creditBalance / heartstoneBalance / todayFaucetGross 三次落库并遍历 8 个职业, 全程在服务器主线程; HomePage.tsx:73-74 自己就写着"profile 每次都要打 3 次 SQLite 且跑在服务器主线程, 严禁给它挂定时轮询 (现有的 world.revision 触发式重载已经是上限)" —— 这次重复恰恰把它顶过了自设的上限, 单次卖菜 6 次 SQLite。  顶栏余额闪空一个往返也属实: useMockWorld.ts:61-63 的 effect 每次 reload 先 setState 回 loading, TabletShell.tsx:281-290 在非 ready 时不渲染 Currency。维持 Major。


#### F015 · 军火台 4→5 槽迁移静默销毁老存档里的两格料, 且非 4 非 5 的槽数直接清空整台库存

- **维度**: 兼容性 | **严重度**: Major | **审计域**: 横切专项: 兼容性 (存档/NBT 迁移、config 迁移、跨 mod API、双端 classload、数据包格式)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:806`
- **证据**: load() 第 781-783 行先读 invTag 的 "Size" 再 inventory.deserializeNBT(invTag), 然后调 migrateInventoryShape(savedSlots)。Forge 1.20.1 的 ItemStackHandler.deserializeNBT 第 188 行是 setSize(nbt.getInt("Size")), setSize 第 35-38 行为 stacks = NonNullList.withSize(size, EMPTY) —— 即按存档尺寸重建并丢弃全部内容。migrateInventoryShape 第 807-821 行的 savedSlots==4 分支只 copy 了 legacy slot 2 与 slot 3 两格, 随后 810-813 行 setSize(SLOT_COUNT) + 全槽置 EMPTY, 最后只回填 SLOT_PROPELLANT 与 SLOT_OUTPUT; legacy slot 0 与 slot 1 的内容无任何去向。本文件仍留着旧布局的类 javadoc 第 45 行 '料槽 铜 (slot 0) / 火药 (slot 1) / 发射药 (slot 2) + 输出缓冲展示槽 (slot 3)', 与现行常量 68-73 行 (0=底火 1=弹壳 2=弹头 3=发射药 4=输出) 并列, 直接坐实 legacy 0/1 是玩家投入的真实材料。第 822-824 行的兜底分支对任何其它 savedSlots 直接 setSize(SLOT_COUNT), 同样是整台清空。全仓无任何用例覆盖该路径 (grep 'putInt("Size"' / '"Size", 4' 在 src/main/java 下零命中, MunitionsGameTests 也只有第 791 行一处与迁移无关的 legacy 方块引用)。对比同模块 GunsmithAssemblyBenchBlockEntity 第 344-416 行: 三条 legacy 尺寸各有独立 migrate 方法, 逐槽 copy 且未知尺寸抛 IllegalStateException 而非静默吞。
- **影响**: 老存档里在 4 槽时代往军火台投过料的玩家, 服务端升级后重进世界, 槽 0 的铜与槽 1 的火药凭空消失, 无日志无提示 (LOGGER 在该分支未打点)。公服死亡不掉落、材料靠产线累积, 这类静默吞料玩家会当成刷物 bug 报障且无法自证。兜底分支更严重: 若真服存在过任何其它槽数版本, 该台库存被整体抹掉。
- **建议**: 照搬同模块 GunsmithAssemblyBenchBlockEntity 的范式: legacy 0/1 两格内容要么按语义映射到新布局的对应槽 (若旧铜/火药对应新底火/弹壳), 要么在迁移时收集成掉落物交还世界, 二者都要 LOGGER 打点; 未知 savedSlots 一律抛异常拒绝静默清空, 而不是 setSize 抹平。补一条以 4 槽 NBT 夹具跑 load() 并断言四格材料去向的 GameTest (删掉迁移分支该测试必须挂)。顺带清理第 45/60 行已与常量矛盾的类 javadoc。
- **复核**: 维持 — 逐行读码核实, 核心结论成立。(1) 事实核对: load() 第 779-784 行确为先取 invTag.getInt("Size") 存入 savedSlots, 再 inventory.deserializeNBT(invTag), 最后 migrateInventoryShape(savedSlots); migrateInventoryShape 在第 806-826 行, savedSlots==4 分支只 copy 了 inventory.getStackInSlot(2) 与 (3) 两格 (第 808-809 行), 随后 setSize(SLOT_COUNT) + 第 811-813 行全槽置 EMPTY, 只回填 SLOT_PROPELLANT 与 SLOT_OUTPUT (且 propellant 还要过 is(PROPELLANT) 判定, 不匹配也一并丢), legacy slot 0/1 无任何去向 —— 全文件 grep legacy/popResource/dropContents 只命中第 808-818 这 6 处, 确认无第二条回收路径。(2) legacy 槽语义确认: 不止审计员提到的本文件第 45 行类 javadoc '料槽 铜 (slot 0) / 火药 (slot 1) / 发射药 (slot 2) + 输出缓冲展示槽 (slot 3)', 另有 menu/MunitionsBenchMenu.java 第 25 行同样写着 '料槽 铜/火药/发射药 + 输出缓冲槽', 两处独立残留互证 4 槽时代 slot 0/1 是玩家投入的铜与火药; 现行常量在第 68-73 行是 0=底火 1=弹壳 2=弹头 3=发射药 4=输出, ModMunitionsItems 第 55-64 行也只有 PRIMER/CASING/BULLET_HEAD/PROPELLANT 四件套, 无 COPPER/GUNPOWDER 承接槽, 即老料确实无处可去。MunitionsConfig 第 182-183 行 '7 铜 + 16 火药 -> 40 发' 说明这两样是真材料而非装饰。(3) 无覆盖确认: MunitionsGameTests 第 845/883/927 行的三处 load() 全是 saveWithoutMetadata() 当前 5 槽 NBT 的往返, 无任何 4 槽夹具; 反观 GunsmithAssemblyBusinessGameTests 第 238-369 行对三条 legacy 尺寸各有逐槽断言 + 未知尺寸抛异常的测试, 对照 GunsmithAssemblyBenchBlockEntity 第 344-377 行 loadInventory 逐条 migrate + LOGGER.info + 未知尺寸 IllegalStateException, 同模块范式差距坐实。(4) 两点对审计员表述的修正: 其一, 'deserializeNBT ... setSize 即丢弃全部内容' 的说法孤立看是错的 —— Forge ItemStackHandler.deserializeNBT 在 setSize 之后会按 Items 列表回填, 所以进 migrateInventoryShape 时 4 个槽是有料的, 否则整个迁移分支都无意义; 好在这不影响结论, 反而是丢料成立的前提。其二, '非 4 非 5 的槽数整台清空' (第 822-824 行 setSize 确实是 NonNullList.withSize 重建即全清) 属真代码事实, 但无证据表明历史上存在过 4/5 以外的槽数版本, 该子结论是推测性的, 不能与 slot 0/1 丢料同等看待。(5) 严重度: 维持 Major。真服已双端部署过 4 槽版本, 老档一旦重进世界即静默销毁玩家投入的铜/火药 (单台上限约 2 组, 折合上百发弹的料), 无日志无提示无补偿, 玩家会当刷物 bug 报障且无法自证; 但损失有界且非经济印钞向, 不到 Critical。


#### F016 · 特勤干员职业升级死锁: 唯一经验入账点被"入职标志"门锁死, 而入职标志只能在 L3 时置位, 新号永远停在 L1

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:133`
- **证据**: AGENT 职业全工程只有一个经验入账点: AgentRewardHandler.grantAgentKillXp -> AgentLevels.grantRawXp (AgentLevels.java:39 是 JobId.AGENT 唯一的 grantXp 调用, 全库 grep JobId.AGENT 无第二处)。该入账点在 AgentRewardHandler.java:133 被 `if (!AgentBountySavedData.get(...).isActiveAgent(player.getUUID())) continue;` 拦住。而 isActiveAgent 的唯一置位点是 AgentSealHandler.java:104 的 markActiveAgent(全库 grep markActiveAgent 只有此一处生产调用), 它位于 AgentSealHandler.java:81 `SealPlan.plan(level, star, category)` 三门通过之后; SealPlan.java:61-67 要求 PASSIVE 类别解锁, 门槛 AgentSkillTable.java:34 `SEAL_UNLOCK_LEVEL = 3`。AgentBountySavedData.java 类注释自己写明"框架 level 对【任何】玩家恒返 1 级默认"。AgentWebUiActions.java:270-273 还刻意注明扫描路径不置位该标志。设计文档 SpecialAgent_Job_DesignSpec 8.1 列了四条 XP 来源(首次扫描发现 星级×8 / 击杀精英 / 日常悬赏 400-1500 / 周常悬赏 2500-6000), 代码只实现了"击杀精英"一条。
- **影响**: 真服(Champions 已加载)上, 任何新玩家玩特勤: 等级恒为 1 -> 封印被 CATEGORY_LOCKED 拒 -> 永不置位 activeAgent -> 击杀精英拿不到 AGENT 经验 -> 永远升不到 L3。整条职业不可进阶: 第四章的封印/加强奖励(×1.0→×3.0)/对精英伤害加成(+5%→+15%)/悬赏权限全部不可达, 面板却把这些数值原样显示给玩家。dev/GameTest 因 Champions 是 compileOnly 走"未绑定"分支, 测不到这条闭环。目前唯一破局手段是 OP 用 admin.job.setLevel 手动把玩家拉到 L3。
- **建议**: 打破环: 把"做过特勤工作"的置位点前移到 L1 就能做到的动作上 —— 最自然的是把 job.agent.scan 成功扫到至少一个盖章精英时置位(比"站在精英旁边点按钮"多一道"确实找到了精英"的门, 不至于泄漏给全服), 或者补上文档 8.1 的"首次扫描发现精英 星级×8"这条 XP 来源并让它不受 activeAgent 门约束(它本身就是入职动作)。无论选哪条, 都要在 GameTest 里加一条断言: 一个 L1 新号走完"扫描->击杀"后 AGENT 经验必须 > 0, 否则这个环会再次被封上。
- **复核**: 维持 — 逐环复核全部成立, 环是闭合的。(1) 入账点唯一性: 全库 grep JobId.AGENT 只有三处生产代码 —— AgentLevels.java:30 (读等级)、AgentLevels.java:40 (grantXp)、AgentDamageBonusHandler.java:55 与 AgentRewardHandler.java:153 (读等级); grantRawXp 的唯一生产调用是 AgentRewardHandler.java:188 (grantAgentKillXp)。(2) 门确实在: AgentRewardHandler.java:133 `if (!AgentBountySavedData.get(player.server.overworld()).isActiveAgent(player.getUUID())) continue;` 把 (B) 循环里加强奖励与经验两笔一起挡住。(3) 置位点唯一: 全库 markActiveAgent 只有 AgentSealHandler.java:104 一处生产调用, 位于 SealPlan.plan 三门 (line 81) 通过之后; AgentSkillTable.java:34 SEAL_UNLOCK_LEVEL=3, AgentSkillTable.java:223-224 isPassiveSealUnlocked 要求 clampLevel(level)>=3, SealPlan.java:61-67 未解锁即 CATEGORY_LOCKED。(4) 扫描路径确认不置位: AgentWebUiActions.java:270-273 有明确注释说明刻意不置位。(5) 其它出路只有 OP: JobAdminWebUiActions.java:52/70/95 的 admin.job.setLevel 走 WebUiPermissions.requireOp。结论: 新号 L1 -> 封印被拒 -> 永不置 activeAgent -> 击杀精英拿不到 AGENT 经验 -> 永远到不了 L3, 死锁真实存在。两点需要修正审计员的 impact 表述, 但不影响成立: 其一, 精英系统已于 2026-07-07 自研脱离 Champions (ChampionSystem.java:42-44 无条件注册), 而特勤集成层仍被 AgentSystem.java:58-65 的 ModList.isLoaded("champions") 守卫, 且 AgentChampionData.java:51 读的是第三方 top.theillusivec4.champions 的 ChampionCapability, 与自研 ChampionPromoter.java:101 写的 MiningChampions capability 不是同一份 —— 也就是说无论装不装 champions, 这条击杀发经验链在上游就已经断了 (该 capability 契约失配已记在 docs/Economy_Completeness_Audit.md:127-128, 不重复计)。其二, 因此本条的真实价值是: 即便把上游 capability 失配修好, 这个 activeAgent<->L3 的环仍会把职业锁死, 是一条独立且必须单独修的缺陷。维持 Major (整条职业不可进阶, 但不涉及印钞/崩服)。


#### F017 · 特勤悬赏系统整条未实现: 定义/进度两个类零生产调用点, 发奖出口零调用点, 玩家侧无任何接取入口

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/job/agent/BountyDefinition.java:15`
- **证据**: BountyDefinition / BountyProgress 两个类全工程只在 AgentGameTests.java:489-586 被 new 过, 无任何生产调用点。周常青辉石发奖出口 AgentRewardHandler.java:202 `public static long grantWeeklyBountyAzure` 全工程零调用点(grep 只命中定义与一处注释)。AgentBountySavedData.java:26 类注释自陈"具体每玩家多槽悬赏实例的持久化序列化属 b 阶段悬赏面板接线(留 deferred)", AgentRewardHandler.java:47 同样自陈"具体悬赏实例推进留 deferred"。67 条已注册的 WebUiAction 里没有任何 bounty 相关 action(grep 'bounty' 在 webui/src/lib/actions.ts 与 mock/planned.ts 零命中)。但 AgentWebUiActions.java:165-174 仍向面板回报 dailySlots / weeklySlots / maxBountyStar / worldBossUnlocked / weeklyAzureCap 五个悬赏字段。
- **影响**: 设计文档第四章(日 1→5 槽 / 周 0→3 槽 / L8 世界 BOSS 悬赏)、7.2、8.1(悬赏是两条主力 XP 来源)、10.5、十一章(青辉石唯一来源=周常悬赏)全部落空。玩家在特勤面板上看到"日常悬赏 3 槽 / 周常 2 槽 / 本周青辉石 0/50", 但没有任何地方能查看或接取一条悬赏, 也永远不会有青辉石从这条路产出。配合上一条发现, 特勤职业目前对玩家而言只剩"扫描看词条 + 封印(升不到级所以封不了)"。
- **建议**: 要么补齐最小闭环(悬赏模板库 + 每玩家多槽实例持久化进 AgentBountySavedData + 日/周重置走已有的 AgentClock + 一条 job.agent.bounty.* action 供面板接取/交付 + 在 AgentRewardHandler 的合格击杀处推进 BountyProgress + 完成时调 grantWeeklyBountyAzure), 要么在补齐之前把 job.agent.state 里那五个悬赏字段撤掉/标注为未开放, 别让面板展示一套玩家够不着的规则。
- **复核**: 维持 — 取证全部核实。BountyDefinition / BountyProgress 的构造点只出现在 AgentGameTests.java:489/491/514/516/531/537/552/555/571/586, 无任何生产调用; grantWeeklyBountyAzure 定义在 AgentRewardHandler.java:202, 全库只被 EconomyGameTests.java:411 的注释与自身 javadoc 提及, 零调用方; AgentWebUiActions.java:88-90 只注册了 job.agent.state / job.agent.scan / job.agent.seal 三条 action, 无任何 bounty action; 全库 -l 扫 bounty 只命中 13 个文件, 其中生产代码全是 AgentSkillTable 的权限查表与 AgentBountySavedData 的青辉石周计数器, 没有任何指令入口 (MarriageCommands 式的 /agent bounty 不存在)。设计文档要求的日/周悬赏槽、L8 世界 BOSS 悬赏、青辉石唯一来源全部落空, 且它同时是设计文档 8.1 四条 XP 来源里的两条 —— 与上一条死锁叠加, 特勤职业目前对玩家只剩"扫描看词条"。一处需要给审计员打折: 面板并非把它当可接取的悬赏列表展示 —— webui/src/lib/types.ts:950-954 与 AgentPanel.tsx:40-41/444 都明确写成"悬赏权限一览", 并有 AgentPanel.tsx:473-475 的说明文字。但那句说明文字"接单与进度只能在游戏里进行"是错的 (游戏内同样没有接单口), 属于对玩家的误导, 修的时候要一并改掉。维持 Major。


#### F018 · 实体堆叠未把本工程精英怪排除在合并之外, 精英会被并掉或顶着 xN 名牌被一次击杀掉 N 份普通战利品

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/stacking/StackMerge.java:42`
- **证据**: StackMerge.canStack(StackMerge.java:42-65) 的排除项只有四条: 命名(hasCustomName)、驯服、Boss(判据是 StackMerge.java:79 `!entity.canChangeDimensions()`, 只覆盖末影龙/凋灵)、config 黑名单(按 EntityType id)。StackMatchKey.of(StackMatchKey.java:41)的等价键只含 type + baby + 变体(羊色/苦力怕充能/马花色), 不含精英/星级维度。精英怪是 MobPressureSystem.java:270 经 ChampionSpawnSeam.promote 就地升格的普通 Mob(僵尸/骷髅等), canChangeDimensions 为 true, 本工程用自建 BossBar(ChampionBossBarHandler)显示星级而非 vanilla CustomName, 故不被任何一条排除项挡住。合并后 StackMerge.applyLabel(StackMerge.java:179)会 setCustomName("Zombie x2")。击杀时 StackDeath.handleInstantAll(StackDeath.java:86-91)按 N-1 个虚拟个体补跑普通 loot table 并补 (N-1)× 经验。ContributionTracker 的 LEDGER(ContributionTracker.java:28)是静态表, 只在 drain(死亡)时移除。
- **影响**: 两只同种精英(或一只精英与一只普通怪)进到 5 格内就会被并成一只: 被并的一方连同其盖章星级/词条数据直接消失, 玩家打了一半的精英凭空不见; 若它已被打过, 其 ContributionTracker 账本条目永远不 drain, 在长期运行的服上是静态表泄漏。幸存者顶着 "Zombie x2" 名牌, 死亡时按普通怪 loot table 多掉 N-1 份战利品与经验, 而精英奖励池仍按一只结算。config 的 exclusions.blacklist 按 EntityType id 生效, 想排精英就得连普通僵尸一起排, 无法只排精英。
- **建议**: 在 canStack 里加一条"本工程盖章精英不参与堆叠"的排除, 且必须走 champions-free 接缝(仿 ChampionSpawnSeam 的 bind/isBound 范式)注入判据, 不能让 stacking 包硬 import Champions。同时给 StackMatchKey 留一个"特殊身份"维度, 防止将来别的带 capability 身份的怪重蹈覆辙。
- **复核**: 维持 — 逐条核实成立。canStack (StackMerge.java:42-64) 的四条排除项确认只有 hasCustomName / isTamed / isBoss / blacklist; isBoss 判据 StackMerge.java:78-80 是 !canChangeDimensions(), 只覆盖末影龙凋灵。精英不带 CustomName 这一关键前提我单独验过: 在 src/main/java/com/miningdim/champion 全目录 grep setCustomName / setCustomNameVisible / setPersistenceRequired 零命中, 盖章只写 capability (ChampionPromoter.java:100-107 applyChampion -> MiningChampions.get(mob) -> champ.promote), 星级靠自建 BOSS 血条显示 —— 所以精英 hasCustomName=false, 四条排除项全部不挡。StackMatchKey.java:41-66 的等价键确认只有 type + baby + 羊色/充能/马花色, 没有任何身份维度, 精英僵尸与普通僵尸同键。合并方向不受控: StackMerge.java:127-158 的 mergeGroup 以列表先出现者为锚, 普通怪完全可能把精英 discard 掉 (line 152), 精英的星级/词条/血池随 capability 一起消失。事后放大也属实: applyLabel (line 179-190) 打 "Zombie x2", StackDeath.java:86-91 的 INSTANT_ALL (StackingConfig.java:89-90 是默认值) 按 N-1 个虚拟个体补跑普通 loot table, StackDeath.java:210-224 再补 (N-1) 份经验 —— 而精英奖励池仍按一只结算。ContributionTracker.java:28 LEDGER 是静态表, 只在 drain (死亡, line 74) 或服务器停止时清, 被 discard 的精英若已被打过, 其账本条目在本次会话内不再回收, 泄漏属实 (量级小, 但确实存在)。config 侧也确认无解: EXCLUSIONS_BLACKLIST (StackingConfig.java:114-115) 按 EntityType id 生效, 想排精英僵尸就得连普通僵尸一起排。维持 Major。


#### F019 · 离婚缺 escrow 公示期, 且共享背包全部内容无条件归发起方, 文档点名要防的"离婚资产抢劫"反而成了默认行为

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/marriage/MarriageDivorce.java:131`
- **证据**: MarriageDivorce.settleSharedBackpack(MarriageDivorce.java:131-149)逐槽取出全部非空物品直接 `initiator.getInventory().add(stack)`, 给不下就落地在发起方脚边, 方法注释自陈"本期简化: 全部退回发起方; 谁放入谁取回的逐物流水分割是候选功能"。divorce(MarriageDivorce.java:59-125)整条是同步即时生效: 扣费 -> 清算 -> 关窗 -> registry.dissolve -> 清双方指针, 无任何延迟生效/可撤销窗口。入口 MarriageCommands.java:52-53 `.then(Commands.literal("divorce").executes(this::divorce))` 挂在 /marriage 根上, 根节点没有任何 .requires 权限门, 也没有二次确认。MarriageRegistry.java:105 的注释还写着"离婚冷却/清算/escrow 由阶段 5 接入"。设计文档第六章闸 2 明确要求"离婚成本 + escrow 公示期: 延迟生效、期间可撤销", 闸 3 要求"共享背包按谁放入谁取回流水分割", 第七章闸 4 直接点名"离婚资产抢劫"是要防的风险。
- **影响**: 任一方只要付得起 divorceCost, 一条 /marriage divorce 就能把共享背包里对方放进去的全部物资卷走, 对方连一个可申诉/可撤销的窗口都没有(离线配偶更是完全无感, 登录后才发现关系没了、东西也没了)。这正是文档写明要防的场景, 现在是默认路径。
- **建议**: 两件事分开做: (1) 清算口径 —— 共享背包至少记录每个槽位的放入者 UUID, 按放入者退回, 做不到逐物流水就退而求其次按"谁放入谁取回"的槽级归属; (2) escrow —— 离婚改成两阶段(提交 -> 公示期 -> 生效), 公示期内双方均可撤销, 期间冻结共享背包取放。在补齐之前, 至少给 divorce 加一道二次确认, 并在确认文案里写清共享背包会被谁取走。
- **复核**: 维持 — 全部核实成立, 且设计文档原文比审计员引的更明确。代码侧: settleSharedBackpack (MarriageDivorce.java:131-149) 逐槽 inv.set(slot, EMPTY) 后 initiator.getInventory().add(stack), 加不下就 initiator.drop —— 全部归发起方, 无任何放入者记账 (MarriageState.sharedInv 是裸 NonNullList<ItemStack>, 没有槽位归属字段); divorce (line 59-125) 是同步一条龙: tryCharge -> settleSharedBackpack -> forceCloseAll -> registry.dissolve -> 清指针 -> recordDivorce, 没有任何延迟生效/可撤销窗口; 入口无门无确认: MarriageCommands.java:39-54 的 /marriage 根节点没有 .requires, divorce 子节点直接 .executes(this::divorce); WebUI 侧 MarriageWebUiActions.java:102/436 的 marriage.divorce 同样一次调用即结算完毕。文档侧: docs/Marriage_System_DesignSpec.md:89 闸 2 原文"离婚成本 + escrow 公示期: 延迟生效、期间可撤销(仿取款 escrow)", :90 闸 3 原文"清算: 共享背包按'谁放入谁取回'流水分割", :102 风险表把"离婚资产抢劫"写进要防的风险, :207 交付项也列了 escrow —— 三处都没落地, 代码注释 (line 92/128) 自陈"本期简化"。公服上共享背包 54 格可以装枪械/弹药/建材等未被黑名单挡住的高价值物, 一方付得起 divorceCost 即可单方面卷走且对方 (尤其离线方) 无任何撤销窗口, 属于文档点名要防、现在却是默认路径的玩家伤害面。维持 Major。


#### F020 · 16 个精英怪 handler 各自独立做"每玩家 96³ AABB 全实体扫描", 且全部对齐在同一 tick 触发, 形成每秒一次的主线程尖峰

- **维度**: 性能 | **严重度**: Major | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionParticleHandler.java:57`
- **证据**: champion/integration 下 16 个 handler 用同一份复制粘贴的扫描范式: `for (ServerLevel level : server.getAllLevels()) { for (ServerPlayer player : level.players()) { AABB box = player.getBoundingBox().inflate(VIEW_RANGE=48.0D); for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {...} } }`。实测清单与节流 (均为 `server.getTickCount() % N == 0`): ChampionParticleHandler:47/57 (N=5)、ChampionBossBarHandler:64/76 (N=10)、ChampionBlinkHandler:108/154、ChampionTacticalBlinkHandler:95/106、ChampionElectroChargeHandler:130/194、ChampionThunderHandler:121/168、ChampionLittleBoyHandler:125/178、ChampionCaesarSwapHandler:126/190、ChampionBladeWaltzHandler:127/282、ChampionSizeHandler:251/268、ChampionPhaseWalkHandler:122/203、ChampionSummonHandler:120/131、ChampionCounterUnitHandler:102/113、ChampionDeathMarkHandler:101/112、ChampionSelfEffectHandler:118/129、ChampionVisualDisruptionHandler:87/98 —— 后 14 个的 N 全部 = 20 (ChampionThunderPlan/ChampionBlinkPlan/ChampionCaesarSwapPlan/ChampionBladeWaltzPlan/ChampionElectroChargePlan/ChampionPhaseWalkPlan/ChampionTacticalBlinkPlan/ChampionVisualDisruptionValues 的 SCAN_INTERVAL_TICKS 均为 20L, ChampionSelfBuffValues.HEAL_TICK_INTERVAL=20L, 其余硬编码 20)。每个 handler 内部的 `Set<UUID> processed` 只在本 handler 内去重, 跨 handler 不共享: 同一只实体在同一 tick 内被 16 次 `MiningChampions.get(entity)` (MiningChampions.java:68 → entity.getCapability().resolve()) 重复解析。
- **影响**: 48 格 inflate 得到 96×96×96 的盒, 每次查询要遍历约 6×6×6≈216 个 EntitySection 并新建结果 ArrayList。满编公服 40 人在线时, 每逢 tickCount%20==0 的那一 tick, 16 个 handler 同时发起 16×40=640 次该量级查询 (约 13.8 万次 section 访问 + 640 次 List 分配 + 对每只邻近生物 16 次 capability 解析), 全部落在服务端主线程同一 tick 内。表现为规律性的每秒一次 tick 时间尖峰 (肉眼即卡顿/回弹), 且成本随在线人数线性增长, 与场上是否真有精英怪无关 —— 一只精英怪都没有时这 640 次扫描照跑。
- **建议**: 方向: 把"扫近玩家实体"抽成单一的共享扫描器 —— 每 20 tick 扫一次, 产出一份 (冠军实体, MiningChampionData) 的快照列表, 16 个 handler 改为消费该快照而不是各自扫。次选/叠加: 给各 handler 的取模加互不相同的相位偏移 (`(tickCount + k) % 20 == 0`) 把尖峰摊平到 20 个 tick 上; 以及在扫描器内一次性缓存 capability 解析结果, 杜绝同一实体同 tick 被解析 16 次。
- **复核**: 维持 — 逐条核实全部命中, 三次证伪尝试全部失败。(1) 扫描范式属实: ChampionParticleHandler.java:50-64 即 for(getAllLevels) -> for(level.players()) -> inflate(VIEW_RANGE) -> getEntitiesOfClass(LivingEntity.class, box, isAlive); grep VIEW_RANGE 得 16 个文件全部 `= 48.0D` (Blink:76 / BossBar:54 / BladeWaltz:85 / CounterUnit:72 / CaesarSwap:85 / DeathMark:77 / ElectroCharge:81 / LittleBoy:82 / Particle:37 / PhaseWalk:83 / SelfEffect:78 / Size:76 / TacticalBlink:67 / Summon:77 / Thunder:89 / VisualDisruption:62), 无一例外。(2) 同拍属实: 8 个 Plan/Values 的 SCAN_INTERVAL_TICKS 实测全为 20L (ChampionThunderPlan:36 / BlinkPlan:32 / CaesarSwapPlan:32 / BladeWaltzPlan:45 / ElectroChargePlan:31 / PhaseWalkPlan:39 / TacticalBlinkPlan:32 / VisualDisruptionValues:30), SelfBuffValues:32 HEAL_TICK_INTERVAL=20L, CounterUnit:69 与 Summon:74 与 Size:73 硬编码 20; Particle 的 5 与 BossBar 的 51 处 10 都整除 20, 故 tickCount%20==0 那一 tick 16 个全部起跳, 全部写作 `% N == 0` 无任何相位偏移。(3) 找门失败: ChampionSystem.java:76-103 逐行 forgeBus.register 全部无条件, 且该类 javadoc 第 44 行明写 "故本入口【无条件】注册 (不再 ModList.isLoaded(\"champions\") 守卫)" —— 这条不属于 TACZ/Champions 那类 compileOnly 只在 dev 走空分支的路径, 生产照跑; handler 内除 `players.isEmpty()` 外无维度门、无配置开关、无"场上是否存在冠军"的前置判定 (ChampionSizeHandler:248-256 与 ChampionSelfEffectHandler:113-137 的 stateByChampion 早退只挡每 tick 的预兆推进, 挡不住 1s 扫描)。(4) 成本模型审计员反而低估了: 从 Forge userdev 源码 EntitySectionStorage.forEachAccessibleNonEmptySection (1.20.1-47.4.20 sources, 第 44-60 行) 看, 它是按 section-x 逐条切片, 每片用 sectionIds.subSet 遍历该 x 切片上【全世界已加载的所有非空 entity section】再按 y/z 过滤, 并非只碰盒内那 216 个 —— 故在主世界大量区块已加载时单次查询成本远高于"216 次 section 访问"。(5) 无收口: MiningChampions.java:68-73 的 get 就是 entity.getCapability().resolve(), 各 handler 各自的 `Set<UUID> processed` 只在本 handler 内去重, 跨 handler 无共享缓存, 同一实体同 tick 被解析 16 次属实。严重度维持 Major 不升 Critical: 结论来自代码结构推演, 没有实测 tick 耗时数据, 且"40 人在线"是审计员的假设值, 不做无证据的升级。


#### F021 · 实例生成完成后把整个 256 区块的 region 全部 force-load 且直到最后一人离开才释放, 使 loadRadiusChunks 滑动窗口形同虚设

- **维度**: 性能 | **严重度**: Major | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/instance/GenerationScheduler.java:166`
- **证据**: onGenerationComplete (:130) 调 enqueueRegionChunkLoads (:136-147), 把 region 覆盖的全部区块入队; tickChunkLoads (:154-171) 每 tick 消费 4 个, 逐个 `ForgeChunkManager.forceChunk(level, MODID, owner, cx, cz, true, false)` 加票, **加完后没有任何路径把它们撤掉**。撤票只有 GenerationScheduler.release (:179-197), 调用点仅 InstanceManager.java:456 (最后一名玩家离开) 与 :517 (destroyInstance)。region 尺寸: MiningConstants.REGION_SIZE_X=256 / REGION_SIZE_Z=256 (core/MiningConstants.java:53,56) → 16×16 = 256 个区块/实例。与之并存的 ChunkTicketManager 滑动窗口半径 loadRadiusChunks 默认仅 4 (config/MiningServerConfig.java:290-291), 即 9×9=81 区块/玩家。实例并发上限 GLOBAL_CAP 默认 32 (MiningServerConfig.java:127-128)。另注: 两者用的是同一个 owner 键 (ChunkTicketManager.java:53 与 GenerationScheduler.java:167 都取 regionBox 原点 BlockPos), 票在 Forge 侧互相覆盖。
- **影响**: 只要一个实例里有人, 该实例整整 256 个区块被强加载到 FULL 并常驻 (非 ticking 但仍占堆、仍参与自动保存序列化), 而玩家实际只在其中约 81 个区块的窗口内。满编 32 个实例同时有人时最多 8192 个常驻 FULL 区块 —— 堆占用与每次 autosave 的落盘量都按实例数而非玩家数放大, 是把"分帧限速加载"省下的瞬时开销换成了长期的常驻成本。ChunkSystem/ChunkTicketManager 那套滑动窗口逻辑在有人期间完全不起作用 (窗口是整个 region 的子集)。此外无人进入的实例 (生成完但玩家中途退出) 要等 emptyInstanceTtlSeconds=300s (MiningServerConfig.java:292-293) 的 GC 才释放。
- **建议**: 方向: 区分"物化用的临时票"与"驻留用的常驻票"。tickChunkLoads 加的票是为了触发 MiningChunkGenerator 落方块, 落完即应撤 (加 add=true → 等区块达 FULL → 立刻 add=false), 之后区块驻留权完全交回 ChunkTicketManager 的滑动窗口。顺带把两个模块的 owner 键区分开 (例如 region 原点 vs 原点上移一格), 否则一方的 add=false 会把另一方的票一并抹掉, 两个模块各自的 forced 记账都会与 Forge 实际状态失同步。
- **复核**: 维持 — 核心机制成立, 但影响规模被审计员算错了 (方向相反的两处修正)。成立部分: GenerationScheduler.java:130 onGenerationComplete 调 enqueueRegionChunkLoads, :136-147 把 region 全部区块入队, :154-171 tickChunkLoads 每 tick 消费 4 个逐个 forceChunk(..., add=true, ticking=false), 加完确无撤票路径; 全库 grep forceChunk 只在 ChunkTicketManager 与本文件出现, 本文件的 add=false 仅在 release(:179-197), 调用点确只有 InstanceManager.java:456 (最后一人离开) 与 :517 (destroyInstance)。REGION_SIZE_X/Z=256 (MiningConstants:53,56) -> 256 区块/实例; loadRadiusChunks 默认 4 (MiningServerConfig:291); emptyInstanceTtlSeconds 默认 300 (:293) 均属实。修正一 (规模被高估): globalCap=32 与 8192 区块在当前模型下不可达 —— InstanceManager.allocateOnMainThread:244-254 明确只把入场路由到 fixedInstanceFor(difficulty), 类注释 :238-242 写着"旧的动态分配/共享复用/容量背压机制保留但本模式下不触发", ensureFixedInstances:163-183 只建三难度三个固定实例, 故实际上限是 3×256=768 区块。修正二 (被审计员漏掉、且更糟): 三个固定实例在服务端启动期就被 createFixedInstance:197 scheduler.submit 提交生成 (重启后 rebuild:137-139 把 READY 也重置 PENDING 再提交), enqueueRegionChunkLoads 没有任何"有人才加载"的门 —— 即零玩家在线时 768 个区块也会从开服起被 force-load; 而只要没人真正进过, playerSet 从未非空, onPlayerLeave 的 release 永不触发, gcScan 又在 :485 显式跳过固定实例, 这批票就一直挂着。修正三 ("滑动窗口完全不起作用"言过其实): 生成票是 ticking=false, 而 ChunkTicketManager 的窗口还负责 ticking=true (ensureTicking:135 / applyDesired:243), 被架空的只有"限制加载范围"这一半, "限制 tick 范围"那一半仍在生效。owner 键冲突属实且我读了 Forge 源码坐实: ForgeChunkManager (1.20.1-47.4.20 sources) :110-140 用 TicketOwner(modId, owner) 作键, TicketTracker.add/remove :541-563 是无引用计数的 LongSet, 且 getTickets(ticking) :533 按 ticking 分成两张表 —— 故 GenerationScheduler(ticking=false) 与 ChunkTicketManager 的非 ticking 票确实落在同一张表同一 key 上, 一方 add=false 会抹掉另一方的票。附带发现一个比本 finding 更严重、且审计员给的"分开 owner 键"方案修不掉的缺陷 (在 ChunkTicketManager.java 不在本文件): 该类所有撤票调用 (:170 releaseAll / :200 demoteToLoadOnly / :226 applyDesired) 一律传 ticking=false, 而它自己在 :135/:243 加过 ticking=true 的票 —— 按 Forge 上述实现, ticking=false 的 remove 只动非 ticking 表, 那些 BLOCK_TICKING 票永远撤不掉, 且经 ForcedChunksSavedData 持久化 (ForgeChunkManager:222-229 启动 reinstate) 跨重启累积, 玩家走过的每个 tick 圈区块都会变成永久强制 ticking 区块。本条按其自身描述维持 Major。


#### F022 · 登出即清空全部用牌冷却 (含闪耀分钟级 CD), 重连即可绕过冷却闸门

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotSystem.java:272`
- **证据**: TarotSystem.cleanup(player) 在 PlayerLoggedOutEvent 里调 cooldown.clear(player.getUUID()); TarotCooldownManager.clear (TarotCooldownManager.java:109-113) 同时 remove 掉 gcdEnd / cardEnd / shinyCardEnd 三张表, 类注释自述 '内存态不跨会话; 重连重新计时'。闪耀级 CD 来自牌表 (如 10_wheel_of_fortune.json 的 shiny.cooldownTicks=14400 = 12 分钟), TarotPlayHandler:92-101 明确闪耀走 data.shinyCooldownTicks()。
- **影响**: 玩家打完一张闪耀牌 (无敌窗 / 复活契约 / 处决 AoE 这类强牌) 后断线重连, 12 分钟的签名 CD 立即归零, 可连续甩闪耀; 普通战斗牌的 45s CD 同理。公服断线重连约十几秒, 收益远大于代价, 是稳定可复现的平衡闸门绕过。
- **建议**: CD 表不应随登出清空: 要么按 UUID 保留在内存表里 (只在 ServerStopping 清), 要么落 SavedData; 若坚持内存态, 至少闪耀级表 shinyCardEnd 不随登出清除。注意时钟用的是 server.getTickCount(), 跨重启会重置, 落库需一并换成挂钟或存相对剩余量。
- **复核**: 维持 — 读码核实全部成立。TarotSystem.java:219-223 onLogout -> cleanup(player), cleanup 第 272 行 cooldown.clear(player.getUUID()); TarotCooldownManager.java:109-113 clear 同时 remove gcdEnd/cardEnd/shinyCardEnd 三张表, 类注释 (18-19 行) 自认'内存态不跨会话; 重连重新计时'。闪耀 CD 数值属实: data/miningdim/tarot/cards/10_wheel_of_fortune.json:128 shiny.cooldownTicks=14400 (12 分钟), TarotPlayHandler.java:92-94 闪耀确实走 data.shinyCooldownTicks()。我找不到任何拦截: 全工程只有 TarotPackSavedData 持久化 pity, 没有任何冷却落库; 重连后 owner 门 (TarotPlayHandler.java:44) 与等级门 (50 行) 照样通过, 背包里的牌不丢, 于是同一张闪耀牌可立即再打。公服断线重连成本远低于 12 分钟, 是可稳定复现的平衡闸门绕过, 维持 Major。


#### F023 · 命运之轮闪耀把玩家身上所有增益硬拔到 amplifier 4, 并把长增益砍成 60 秒

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotEffectEngine.java:542`
- **证据**: refreshBeneficial: `int maxAmp = effect == MobEffects.DAMAGE_RESISTANCE ? RESISTANCE_MAX_AMPLIFIER : Math.max(current.getAmplifier(), 4); caster.removeEffect(effect); caster.addEffect(new MobEffectInstance(effect, durationTicks, maxAmp));` —— 对每一条 BENEFICIAL 效果执行。唯一数据来源是 10_wheel_of_fortune.json:131-133 的 shiny op {kind: self_refresh_beneficial, durationTicks: 1200}, 该 op 在 TarotEffectOp 里根本没有 amplifier 字段, 4 是引擎硬编码。
- **影响**: 1) 战力叠叠乐: 玩家先随便喝一圈廉价药水 (力量 I / 速度 I / 生命提升 I / 吸收 I / 再生 I), 一张牌就全部变成 V 级并持续 60s —— 力量 V 白送 +12 近战伤害, 生命提升 IV 在 80 血基准上 +40 最大生命 (占基础血 50%), 与 FF14 生产职 '战斗只给少量加成' 的哲学直接冲突; 2) 抗性虽被钳到 III, 但这里是 remove+add, 绕开了 applySelfPotion:230 的 '强增益同类不可续期' 红线, 等于给抗性 III 开了一条续期通道; 3) 反向伤害: 8 分钟的抗火/夜视被这张牌砍成 60 秒, 文案却写 '强化并刷新'。
- **建议**: 放大幅度必须来自 datapack (给该 op 加 amplifierBoost/上限字段并逐品质配), 且不得高于该效果本身的平衡上限; 时长应取 max(现有剩余, durationTicks) 而不是覆盖; 抗性一类不可续期的强增益应在此处一并跳过而不是只钳幅度。
- **复核**: 维持 — 核心成立。TarotEffectEngine.java:536-548 refreshBeneficial 对每条 BENEFICIAL 效果执行 maxAmp = (抗性 ? RESISTANCE_MAX_AMPLIFIER(=2, 58 行) : Math.max(current.getAmplifier(), 4)) 后 removeEffect + addEffect(durationTicks); 唯一数据源 10_wheel_of_fortune.json:130-133 的 self_refresh_beneficial 只有 durationTicks=1200, 确无 amplifier 字段, 4 是引擎硬编码。战力叠叠乐属实且最大的一项不是审计员点名的力量: 再生 V (amp 4) 按原版 50>>4=3 tick 回 1 血 = 约 6.7 HP/秒 × 60 秒 ≈ 400 HP, 在 80 血基准的高 DPS 环境里是极强的持续续航, 而前置只需一瓶廉价再生药水。抗性一条确属续期通道: applySelfPotion (230-232 行) 的 isNonRefreshable 拒绝续期在此被 removeEffect+addEffect 绕开, 且把玩家原有的抗性 I 直接抬到封顶的 III (原版 60% 减伤)。两处需修正审计员的数值: 生命提升 amp 4 原版是 +20 最大生命 (4.0×(amp+1)), 不是 +40; 且 zh_cn.json:765 文案写的是'强化并刷新全部现有增益至 60 秒', 已明示是'至 N 秒'的覆盖语义, '文案不符'这条子指控站不住。放大幅度不由 datapack 决定这一点足以维持 Major。


#### F024 · 封印/扫描/伤害加成三条特勤主玩法因探测源读的是已废弃的第三方 capability, 全链路不可达

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/integration/AgentChampionData.java:51`
- **证据**: championOf 走 `ChampionCapability.getCapability(entity)` (第三方 champions mod 的 capability), isOurChampion 再读 `champion.getServer().getData("miningdim_champion")` 里的 star 键 (line 66-68)。但精英系统已自研脱离 Champions: MiningChampions 类注释写明 '取代 Champions 的 ChampionCapability', ChampionPromoter.applyChampion (ChampionPromoter.java:100-107) 只写自研的 MiningChampionData capability, 全工程再无任何 IChampion.getServer().setData 的写入点。于是 AgentSealHandler.requestSeal:64、AgentScanProbe.buildSnapshot:45、AgentDamageBonusHandler:46 三处的 isOurChampion 判据对本工程精英恒为 false。
- **影响**: 装了 champions mod 时: 封印申请恒返 NO_TARGET (面板与 C2S 两条路都封不了任何东西), 战术扫描对任何精英都返 null 快照 (job.agent.scan 恒返空表, 且因空表也照烧 CD), 对精英伤害加成恒不生效; 不装 champions 时集成层根本不装配, 同样为零。连锁后果是 markActiveAgent 的唯一置位点 (AgentSealHandler.java:104) 永不触发, 于是加强奖励与伤害加成的入职门 isActiveAgent 恒 false —— 整个特勤职业目前在玩家侧只剩一个只读面板。经济审计 [6] 已记录同一根因的奖励清零后果, 但封印/扫描/伤害加成这三条玩法侧后果不在那份文档内。
- **建议**: AgentChampionData 整体改读自研 MiningChampions/MiningChampionData (星级/有效血/词条都在那里), 并连带处理: 词条身份不再是 champions 的 IAffix 而是本工程 AffixDef, AgentAffixClassifier / AgentSealExecutor 的 setAffixes 真改链路要一并改写到自研词条表; 改完必须补一条 'ChampionPromoter 盖章的精英能被特勤扫到并封印' 的 GameTest, 否则同类失配还会再犯。
- **复核**: 维持 — 逐条核实全部成立。AgentChampionData.java:47-56 championOf 走 Champions 的 ChampionCapability, 65-68 isOurChampion 读 champion.getServer().getData("miningdim_champion") 的 star 键; 而升格侧 ChampionPromoter.applyChampion (ChampionPromoter.java:100-124) 只写自研 MiningChampionData capability, 全工程 grep `setData(` 只命中 reset/AutoResetData 两处无关行, 即 IChampion 侧的 miningdim_champion 子标签从来没有写入方, 判据对本工程精英恒 false。三处消费点确认: AgentSealHandler.java:64 (封印恒 NO_TARGET)、AgentScanProbe.java:45 (快照恒 null, 且 AgentWebUiActions.java:251-253 对 null 只是 continue, 空表照样已经消耗了脉冲 CD)、AgentDamageBonusHandler.java:46 (加成恒不生效)。连锁后果也属实: markActiveAgent 全工程唯一置位点就是 AgentSealHandler.java:104 (AgentWebUiActions.java:270-273 明确写了扫描刻意不置位), 故 isActiveAgent 恒 false, AgentRewardHandler.java:133 与 AgentDamageBonusHandler.java:51 的特勤专属福利门永不开。另补一条同源事实: 即使装了 champions, AgentSystem.java:92-96 在 ServerStopping 调 AgentSealSeam.unbind() 后没有任何重新 bind 的入口 (assemble 只在 mod 构造期跑一次), 同 JVM 二次进档后接缝也恒 NOT_BOUND。这是本轮最有价值的一条, 且是第 7、9 两条的共同根因, 维持 Major。


#### F025 · 农夫耕地放置计数按"破坏者"回收且无归属记录, 可用小号绕过放置硬上限, 非玩家破坏则永久吃掉额度

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/farmer/FarmerSystem.java:322`
- **证据**: onFarmlandBroken 唯一动作是 `FarmerSavedData.get(overworld).decrement(player.getUUID())` —— 扣的是【破坏者】的计数。FarmerSavedData 里只有 `Map<UUID,Integer> placedCounts` (FarmerSavedData.java:55), 全库没有任何 pos->放置者 的归属记录, decrement (FarmerSavedData.java:83-92) 还把结果 clamp 到 0。放置侧 onFarmlandPlace (FarmerSystem.java:302) 则是 `data.increment(player.getUUID())`。
- **影响**: (1) 上限绕过: 小号 Y 放 9 块低级耕地, 主号 X 去破坏这 9 块 -> X 计数 -9 而 X 自己在世界里的耕地一块没少, X 随即可再放 9 块。用一批一次性小号反复供给"可破坏的耕地", X 在世界里维持的 mod 耕地数可远超 FARMLAND_CAP_PER_LEVEL (L1 仅 9 块) 的反扩建硬封顶, 直接放大 mod 小麦与农夫经验的单人吞吐。(2) 反向不可自救: 玩家的耕地被苦力怕/TNT/活塞/指令等非玩家路径摧毁时不产生带玩家的 BreakEvent, 计数永不回收, 该玩家世界里 0 块耕地却显示已满额, 此后永久无法再放, 且没有任何补救入口。
- **建议**: 放置计数必须与"放置者"绑定而非"破坏者": 在耕地方块上记归属 (BlockEntity 或独立的 pos->owner SavedData), 破坏时按记录的 owner 回收; 同时给非玩家破坏路径 (爆炸/活塞/流体/setBlock) 补一条统一的回收钩子, 否则计数只会单向漂高。
- **复核**: 维持 — 逐条读码核实, 描述与代码完全一致, 且没有任何上游门挡得住。事实链: FarmerSystem.java:310-323 onFarmlandBroken 只判 `event.getPlayer() instanceof ServerPlayer` 与 `getState().getBlock() instanceof FarmerFarmlandBlock`, 唯一动作是 `FarmerSavedData.get(overworld).decrement(player.getUUID())` —— 扣的确实是破坏者; FarmerSavedData.java:55 只有 `Map<UUID,Integer> placedCounts`, 全文 192 行读完, 无 pos->owner 结构、无重算入口 (Grep placedCount/increment/decrement 全库只有 FarmerSystem:283/302/322 三个调用点, 另一处是同范式的军械 MunitionsSystem, 不在本区)。放置侧 FarmerSystem.java:281-303 用 `data.placedCount(player.getUUID())` 喂 FarmlandPlacementGuard.checkPlacement (FarmlandPlacementGuard.java:49-63, 只比数不看归属)。绕过链成立且 decrement 的 clamp-to-0 挡不住: X 先把自己计数顶到 cap(L1=9, FarmerConstants.java:30-41), 再去破坏小号 Y 放的 9 块 -> X 计数 9->0 而 X 世界里的 9 块一块没少, X 立刻可再放 9 块, 循环无上限。耕地可破坏可回收 (loot_tables/blocks/farmer_farmland_supreme.json 掉落自身), 低级耕地配方仅 dirt+farmer_seed (recipes/farmer/farmer_farmland_low.json), 小号供地成本近零。反向不可自救也成立: FarmerBlocks.java:57 耕地 `Properties.copy(Blocks.DIRT)` (爆炸抗性 0.5, loot table 还挂 survives_explosion), 苦力怕/TNT 炸掉不触发带玩家的 BreakEvent, 额度永久蒸发且无补救指令。FARMLAND_CAP_PER_LEVEL 是农夫唯一的反扩建硬封顶 (FarmlandPlacementGuard 类注释自陈), 被一个小号打穿即等于 mod 小麦与农夫经验的单人吞吐无上限, 只剩收购曲线 25% 地板兜着 (FarmerConstants.java:69), 仍是净印钞。维持 Major。


#### F026 · mod 作物掉落与 FD 番茄右键采摘缺"耕地档位解锁"门, 与 loot modifier 的判据分叉

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/farmer/FarmerSystem.java:227`
- **证据**: onCropPicked 直接 `new ItemStack(requiredItem("farmersdelight","tomato"), nativeCount * tier.yieldPerHarvest())` 并在 :237 按同一 tier 发经验, 全程没有 tier.isUnlockedAt 判定; onCropHarvested 的原生作物分支 (:193 `Block.popResource(..., new ItemStack(FARMER_WHEAT, yield))`) 同样没有。而 FarmerHarvestLootModifier.java:57 对兼容作物明确加了 `if (!tier.isUnlockedAt(JobServices.jobService().level(player, JobId.FARMER))) return generatedLoot;`, 注释写着"否则 1 级玩家在他人闪耀耕地上收割即可拿满 6 倍, 与按职业等级递进的经济设计相悖"。GameTest 层面两条判据直接打架: FarmerGameTests.java:70 farmersDelightTomatoRightClickUsesSupremeYield 用未设等级的 mock 玩家断言 SUPREME 6 倍必须生效, FarmerGameTests.java:882 vanillaCropYieldRequiresTierUnlock 则断言等级不足必须零增产。
- **影响**: 任意 1 级玩家 (含专门用来收割的小号) 站到他人 SUPREME 耕地上右键采番茄, 就能拿到 6 倍产出与 6 倍收获经验; 原生 mod 小麦的破坏掉落路径同样不看等级。等级门只挡住了兼容作物的破坏掉落这一条路, 另外两条产出路径全开, "高档耕地必须自己练到 L9" 的递进被绕开, 高档耕地变成可对外开放的公共增产设施。
- **建议**: 把 FarmerHarvestLootModifier 里那道 tier.isUnlockedAt 判据提成共享裁决, 让 onCropHarvested 的原生掉落与 onCropPicked 的右键采摘复用同一份; 顺带确认经验侧要不要同门 (若不同门, 需在 spec 里写明原因), 并修正与之矛盾的 GameTest 断言。
- **复核**: 维持 — 三条产出路径逐条读完, 分叉属实。(1) FarmerHarvestLootModifier.java:57 确有 `if (!tier.isUnlockedAt(JobServices.jobService().level(player, JobId.FARMER))) return generatedLoot;`, 注释写明"否则 1 级玩家在他人闪耀耕地上收割即可拿满 6 倍"; 但该 modifier 只作用于兼容作物 (doApply 首行 FarmerHarvests.isSupportedMatureCrop 短路)。(2) 原生 mod 作物根本不走 loot modifier: FarmerSystem.java:192-194 由事件层单一权威 `Block.popResource(level, pos, new ItemStack(FARMER_WHEAT, yield))` 直接发, yield 来自 FarmerCropBlock.tierBelow, 全程无 isUnlockedAt —— 也就是说职业主产出 farmer_wheat 这条路 100% 无门。(3) FarmerSystem.java:219-227 onCropPicked 取 tier 后直接 `nativeCount * tier.yieldPerHarvest()`, 同样无门, 且 :237 按同 tier 发经验。GameTest 互斥也核实: FarmerGameTests.java:70-104 farmersDelightTomatoRightClickUsesSupremeYield 用 MockGameTestPlayers 造的玩家 (JobProgress.java:22 默认 level = JobXpCurve.MIN_LEVEL = 1) 断言 SUPREME 必须掉 6/12; FarmerGameTests.java:882-892 vanillaCropYieldRequiresTierUnlock 把等级设成 unlockLevel-1 断言必须零增产。两条断言对同一档耕地给出相反的合法结果, 说明缺的是共享裁决而非漏测。可达性无阻: 破坏与右键都不需要归属或职业身份。经济量级确实受"耕地总量 × 生长周期"封顶 (多人代收不放大总产量), 故不到 Critical; 但它把"高档耕地=L9 自己练"的递进变成可对外开放的公共设施, 且经验侧 (12 vs 4 原始经验/株) 让借用他人闪耀地的新号升级速度直接 3 倍, 与 loot modifier 里已明写的设计意图正面冲突。维持 Major。


#### F027 · 酒窖箱燃料债无上限累加且持久化, 一次区块卸载/离线就永久废掉酒窖

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/brewer/cellar/CellarSettle.java:88`
- **证据**: 每步先对全部未变质瓶累加应耗 `debt += fuelPerYear * stepYears` (:84), 再只扣付得起的部分 `int burn = Math.min((int) Math.floor(debt), fuelLeft)` (:88), 差额原样留在 debt 里返回, 由 WineCellarBlockEntity.java:63/169/181 以 K_FUEL_DEBT 持久化。燃料槽是 ItemStackHandler 的默认 64 上限 (WineCellarBlockEntity.java:37, dried_wheat 是 `new Item(new Item.Properties())`), 且 BE 刻意不暴露 IItemHandler (类注释:漏斗/机器无法注入)。GameTest sharedFuelPoolAgesAllWhileFuelPresent (WineCellarGameTests.java:127) 明确断言"12 fuel debt carried", 但没有任何用例覆盖债超过槽容量的情形。
- **影响**: 12 瓶 vintage 10 的窖按 BrewerConstants 的 16 + 5×V² 曲线每现实天要 6192 颗干小麦, 而燃料槽最多装 64 颗 (约 15 分钟量) 又不能用漏斗补。玩家下线 8 小时后重新加载, 一次补齐结算产生约 2064 的债、只扣得掉 64, 剩约 2000 永久写进 BE 的 NBT。此后玩家每次塞满 64 颗都会在一个 5 秒步内被旧债吃光, 随即掉进 SPOILAGE_DECAY_YEARS_PER_DAY=200 的衰退分支, vintage 10 的酒 72 分钟归零变质 —— 酒窖只要离线或区块卸载过一次就再也养不出高年份酒。而闪耀永久层要求 vintage>=10 (VINTAGE_LAYER_T1), 整条酿酒师主线因此不可达。
- **建议**: 燃料不足时不应把差额记成永远还不掉的债: 要么把本步应耗钳到当步实际可支付的上限, 要么在燃料见底切进衰退分支时把 debt 清零 (断粮惩罚已由 vintage 倒扣承担, 不该再叠一笔不可偿还的欠款)。同时复核燃料槽 64 的容量与设计吞吐 (文档自称满 12 瓶 v25 约 3.8 万/天) 之间的量级差, 需要提高 slot limit 或开放受控的燃料输入通道, 否则数值本身就是不可完成的。
- **复核**: 维持 — 代码事实全部核实为真, 但它给出的后果与修复方向是错的, 需要按下述更正后再动手。核实为真的部分: CellarSettle.java:84 `debt += fuelPerYear * stepYears` 对全部未变质瓶无条件累加, :88 `int burn = Math.min((int) Math.floor(debt), fuelLeft)` 只扣付得起的, 差额原样返回; WineCellarBlockEntity.java:64/124/170/181 以 K_FUEL_DEBT 持久化且无任何上限; 燃料槽是 ItemStackHandler 默认 64 且 dried_wheat 为 `new Item(new Item.Properties())` (BrewerItems.java:31-32), WineCellarMenu.java:77-82 未改 slot limit, 整个 BE 通读 183 行无 getCapability (漏斗无法注入); BrewerConstants.java:39/43/47 的 16 + 5V² 与 200 年/天衰退也与它算的 6192/天、72 分钟归零一致。被推翻的部分是它的因果与影响: 陈酿门在 CellarSettle.java:73 是 `boolean hasFuel = fuelLeft > 0;`, 只看槽内当步是否有粮, 与 debt 无关 —— 债从不阻断增龄, 补料期间酒照常长, 债也随每次补料 64 递减, 因此"永久废掉/闪耀主线不可达"不成立。真实行为反而是反向的漏洞: 区块卸载/离线期间 BE 不 tick, 重新加载时按 WineCellarBlockEntity.java:92-97 一次性补齐, 若 elapsed <= 1 天则 CellarSettle 只跑一个步 —— 槽里哪怕只有 1 颗干小麦, 12 瓶也照样吃满整段年份, 只烧掉 fuelAvailable 那么多 (这正是 WineCellarGameTests.java:115-127 断言的形态: 应耗 32、只有 20, 两瓶仍增龄到 1.0, 债 12 结转)。于是"每天上线插 1 颗干小麦再离开"就能把设计的 6192-38000/天燃料 sink 打成个位数, 而留在自己基地反被债秒吞燃料并掉进 200 年/天衰退, 在线被惩罚、离线被奖励。据此: 它建议的"燃料不足时钳到可支付上限 / 断粮时清零 debt"必须否决 —— 那等于把上述旁路彻底合法化。真正要修的是"补齐结算按可支付燃料截断实际增龄时长"(以及 64 槽 vs 设计吞吐的量级差, 这一点它的第二段建议是对的)。因缺陷真实存在且是酿酒师主 sink 的旁路, 维持 Major。


#### F028 · 九种闪耀永久层可同时叠满, 无职业门也无跨类型总帽, 构成战力叠叠乐

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/brewer/BrewEffectEngine.java:142`
- **证据**: applyOnDrink 只判 `if (quality != null && quality.isBrilliant())` 就调 applyBrilliantLayer 固化永久层, 全程不查 JobId.BREWER 的等级或入职状态。BrewBuffStore 按 (玩家, WineType) 各存 0..5 层 (BrewBuffStore.java:41), 九个类型互不相干、可同时满层。满层收益见 BrewerConstants.java:62-83: 金酒 +50% 最大生命、伏特加 +25% 全伤减伤、龙舌兰 +15 近战、朗姆 +30% 移速、白兰地 急迫 III、威士忌 每 30 秒回 25% 最大血、香槟 每秒回 5% 最大血 (CHAMPAGNE_HEAL_PCT_PER_LAYER=0.01 × 5 层, 周期 20 tick), 外加月光满层固化 5 条属性词条 (护甲/韧性/击退抗性/近战等, MoonshinePerk)。全套里只有最大生命有跨职业帽 (GLOBAL_BONUS_MAX_HEALTH_CAP_PCT, GinMaxHealthManager.clampToGlobalCap), 减伤只进 PlayerDamageReduction 的连乘与 PLAYER_MIN_KEEP 保底, 其余几项完全无帽。
- **影响**: 酒是可自由交易的普通物品, 任何非酿酒师玩家买齐九种闪耀高年份酒各喝两瓶, 就一次性拿到 +50% 最大血 + 25% 全伤减伤 (还要与凝脂/矿脉抗性在 PlayerDamageReduction 里继续连乘) + 每秒 4 血 (80 基础血 ×5%) 的持续自愈 + 护甲/韧性/击退抗性词条。在 80 初始血 + TACZ 高 DPS + 死亡不掉落的环境里, 每秒 4 血的无条件自愈意味着常规交火的净伤害被大幅抵消, 而唯一的清零条件"死亡"在这种防御强度下极难触发, 形成正反馈。与"八职业主打经济产出、战斗只给少量加成"的设计哲学正面冲突, 且因为可买断而不构成任何职业身份感。
- **建议**: 给永久层加跨类型约束而不是只在单类型上封 5 层: 例如限制同时生效的酒类数、把战斗向层 (伏特加/龙舌兰/威士忌/香槟/月光) 与续航向层分开各设总预算、或把周期回血改成脱战才生效。另外考虑把固化条件收紧为"酿酒师本人喝自己酿的酒"(WineNbt 已存 brewer UUID), 以掐断买断式变强。
- **复核**: 维持 — 核实成立, 且运行期确实接线 (BrewerSystem.java:58 forgeBus.register(permanentBuffHandlers), :61 PlayerDamageReduction.register(new VodkaNumbness()); MiningDim.java:143 装载子系统)。无职业门属实: BrewEffectEngine.java:125-145 applyOnDrink 只判 `quality != null && quality.isBrilliant()` 就调 applyBrilliantLayer, 全链 (BrewBuffStore.addLayersForVintage / BrewPermanentBuffs / BrewPermanentBuffHandlers.onPlayerTick) 无一处读 JobId.BREWER; WineItem.java:56-61 finishUsingItem 也无门, 酒是 stacksTo(16) 的普通可交易物品。无跨类型总帽属实: BrewBuffStore.java:41 按 (玩家, WineType) 各存 0..5, 九类互不相干; BrewPermanentBuffs.java 里朗姆 MOVEMENT_SPEED +30%、龙舌兰 ATTACK_DAMAGE +15、白兰地急迫 III、:152-166 tickPeriodicHeal 香槟每 20 tick 回 1%×层 最大血 (满层 5%/秒) 全部无帽, MoonshinePerk 满层再固化 5 条属性词条。两处审计员已自陈的收口我复核过, 确实存在但不足以证伪: 最大生命有 GLOBAL_BONUS_MAX_HEALTH_CAP_PCT=1.0 的跨职业帽 (BrewerConstants.java:64), 减伤有 CombatConstants.java:16 的 PLAYER_MAX_REDUCTION=0.85 全局帽 —— 伏特加满层 0.25 远在帽内, 只是与凝脂/矿脉抗性连乘时不会溢出。另需给主控补两条它没提的权衡: (a) 死亡即清全部层 (BrewPermanentBuffHandlers.java:29-41 一条命语义), 死亡不掉落服上玩家死得频繁, 是真实的自然衰减; (b) 闪耀只有 L10 酿酒师能 roll 出且归一后仅约 5% / 满月 9.5% (BrewQualityRoller.java:38-41, lv<10 恒 0), 不是随手可买。但配合第 3 条那个"离线补齐几乎不烧燃料"的旁路, 高年份 (v25 = +3 层/瓶) 的时间与燃料成本被绕开, 买断式叠满的可达性比设计预期高得多。核心指控——买酒即得永久战力、与 FF14 生产职业哲学冲突——成立。维持 Major, 不升 Critical (无数据损坏/无越权, 且有死亡清零这一自然上限)。


#### F029 · 酿酒师宣称的"联动农夫经济"在代码里不存在: 燃料与配方吃的都是原版小麦

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/resources/data/miningdim/recipes/brewer/dried_wheat.json:5`
- **证据**: dried_wheat 的配方是 `minecraft:smelting` 且 ingredient 为 `minecraft:wheat`; BrewRecipes.java:65-83 的九条酿造配方全部用 `Items.WHEAT` / `Items.SUGAR` / `Items.APPLE` / `Items.SUGAR_CANE` / `Items.CARROT` / `Items.WHEAT_SEEDS` 等原版物品, 没有一条引用 FarmerItems.FARMER_WHEAT。全库对 FARMER_WHEAT 的消费方只有 FarmerWheatSellService 与 market/DefaultBaseValues。
- **影响**: BrewerItems.java:30 与 BrewRecipes.java:18-20 的注释都写着大宗小麦消耗联动农夫经济, 实际需求可由零上限、零等级门、可用村民/水流完全自动化的原版小麦农场满足, 完全绕开 mod 耕地的放置硬封顶 (9-64 块) 与档位解锁门 —— 酿酒师这条本该最大的小麦 sink 对农夫的供给约束毫无作用。反过来农夫的 miningdim:farmer_wheat 至今只有 /farmer sell 一个出口, 两个职业之间没有任何真实的供给耦合, 经济总表里"农夫供给酿酒师"这条链是空的。
- **建议**: 确定方向后二选一: 要么把 dried_wheat 与酿造配方改吃 miningdim:farmer_wheat (让耕地上限真正成为酿酒吞吐的上游闸), 要么把注释与设计文档里的"联动农夫经济"删掉并重新评估酿酒师的 sink 归属。两者都要先过 Economy_BalanceSheet 那层核对。
- **复核**: 维持 — 逐项核实全中。dried_wheat.json 是 `minecraft:smelting` 且 ingredient 为 `minecraft:wheat`; BrewRecipes.java:65-83 九条配方全部 Items.WHEAT/SUGAR/APPLE/SUGAR_CANE/CARROT/WHEAT_SEEDS, 无一条引用 FarmerItems.FARMER_WHEAT; 连酿酒台自身配方 recipes/brewer/brewing_station.json 也是 minecraft:wheat。宣称与实现的分叉也属实: BrewRecipes.java:18-19 写"双重消耗联动农夫经济", BrewerItems.java:30 写"要求量大, 联动农夫小麦经济"。全库 Grep FARMER_WHEAT (含资源/webui) 后, 消费方只有 FarmerWheatSellService.java:122/128/138、FarmerWebUiActions 的同一卖出口和 market/DefaultBaseValues.java:32 的基价表, 没有任何配方或方块消耗它。后果成立: 原版小麦零上限、零等级门、可村民全自动, 所以 mod 耕地的 9-64 块硬封顶与档位解锁门对酿酒吞吐毫无约束, 设计里"农夫供给酿酒师"这条链在代码层是空的; 反过来 farmer_wheat 只剩 /farmer sell 一个带衰减的出口。已核对 docs/Economy_Completeness_Audit.md, 全文对 brewer/干小麦/小麦供给链无条目 (仅第 69/212 行提到小麦 buyback 曲线与 FarmerConstants 入 config), 故不属于"已记录不必重报"。这不是可以直接闷头改的 bug —— 改吃 farmer_wheat 会同时改动农夫产量需求与酿酒成本两端, 须先过 Economy_BalanceSheet; 但"注释断言了一条不存在的经济耦合"本身就会误导后续维护。维持 Major。


#### F030 · 实例活怪计数只增不减: 陷阱苦力怕与自然消失的怪永不销账, 满 30 后该难度区域永久停刷

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/pressure/MobPressureSystem.java:368`
- **证据**: spawnMob 落地时同时写两处: instance.liveMobs().add(mob.getUUID()) 与 mobInstanceIndex.put(...) (:266-267)。销账只有一条路径 onMobDeath: `Long instanceId = mobInstanceIndex.remove(id); if (instanceId == null) { return; }` 再去 liveMobs().remove。两个漏洞: (1) DynamicTrapEngine.java:157 的身后苦力怕直接 `instance.liveMobs().add(mobId)`, 从不进 mobInstanceIndex, 所以它死了以后 onMobDeath 在第一行就 return, 计数永远挂着; (2) spawnMob 用 ForgeEventFactory.onFinalizeSpawn(..., MobSpawnType.SPAWNER, ...) 且不调 setPersistenceRequired, 怪物离玩家超距离会自然 despawn / 随区块卸载消失, 这两条路都不发 LivingDeathEvent, 同样不销账。另外 onInstanceReset(long) (:395) 本可清 spawnStates/mobInstanceIndex, 但全库 grep 零调用点。
- **影响**: MAX_MOBS_PER_INSTANCE=30 (:53) 是按 UUID 集合大小判的硬闸 (spawnWave :177-182 一超就 return)。R1 下一个难度=一整块共享区域, 玩家在 256x256 里跑动会持续把怪甩出加载/despawn 距离, 泄漏累积很快; 一旦攒够 30 个僵尸 UUID, 该难度区域对全服所有玩家永久停止刷怪 (直到重启), 整个压力系统与 danger 曲线失去输出。mobInstanceIndex 同步泄漏内存条目。
- **建议**: 销账不能只挂 LivingDeathEvent: 需要同时覆盖实体移除的全部路径 (可用 EntityLeaveLevelEvent / 定期按 UUID 校验实体是否仍存活来对账), 并把动态陷阱生成的怪也登记进同一张归属索引; 另外把 onInstanceReset 真正接进重置/释放流程。更稳的做法是不维护 UUID 集合, 改为按需扫描 region 内带实例标记 PersistentData 的活体计数。
- **复核**: 维持 — 代码事实全部核实: spawnMob 落地时双写 instance.liveMobs().add + mobInstanceIndex.put (:266-267); 唯一销账路径 onMobDeath (:363-374) 第一步 `mobInstanceIndex.remove(id)` 为 null 即 return; spawnWave (:177-182) 用 liveMobs().size() >= MAX_MOBS_PER_INSTANCE(30, :53) 硬闸跳过整波; DynamicTrapEngine.java:157 的身后苦力怕确实只 `instance.liveMobs().add(mobId)` 从不进 mobInstanceIndex; onInstanceReset(:395) 全库 grep 零调用点。我另解出原版 1.20.1 源码把泄漏面坐实并扩大: (1) Creeper.java:248-257 explodeCreeper() 走 `this.discard()` 而非死亡流程, 根本不触发 LivingDeathEvent —— 于是两条路的苦力怕 (陷阱引擎的 + SpawnTier HIGH/EXTREME 表里的 CREEPER) 自爆后计数一律挂死; (2) Mob.java:1131-1142 finalizeSpawn 不置 persistenceRequired, Mob.java:759-792 checkDespawn 对非持久怪超 despawnDistance(128) 直接 discard(), 同样无死亡事件, 而单个 region 是 256x256, 玩家跑动极易把怪甩出 128 格。liveMobs 不持久化 (InstanceState.java:164 注释), 故确实是 '直到重启'。后果是该难度区域压力系统输出归零 (降低难度而非伤害玩家), 维持 Major。


#### F031 · 三块固定实例的区块强加载票永不释放: TTL 释放分支对固定实例是死路, 玩家走后数百区块常驻内存

- **维度**: 性能 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/chunk/ChunkSystem.java:105`
- **证据**: tickInstance 的空置分支: `long emptySince = inst.lastEmptyTick(); if (emptySince < 0L) { ticketManager.demoteToLoadOnly(...); return; }` —— 只有 emptySince>=0 才会走到下面的 `if (now - emptySince >= ttlTicks) ticketManager.releaseAll(...)`。而 InstanceManager.onPlayerLeave (:450-457) 对固定实例明确不打时间戳: 注释 'R1: 固定实例常驻不 GC, 不打 lastEmptyTick (保持 -1 使 gcScan 永不命中)', 只在 !isFixedInstance 时 setLastEmptyTick。R1 下存在的实例恰好只有这三个固定实例, 所以 releaseAll 分支永远不会被执行。
- **影响**: 玩家离开矿洞后, 其离开位置周围 (2*loadRadiusChunks+1)^2 = 9x9 = 81 个区块 (perf.loadRadiusChunks 默认 4) 以 load-only 状态被永久强加载, 三块区域叠加、且多个玩家在不同位置离开时取并集, 常驻区块数只增不减。满编公服长期运行下这是持续增长的常驻内存与存档写入压力, 而 19.1 设计的空置 TTL 卸载机制事实上从未生效过一次。
- **建议**: 固定实例同样需要一个 '空置起始时刻' 用于 ticket TTL —— 把 '不参与 GC 销毁' 与 '不释放区块票' 两件事解耦: 要么给固定实例单独记录 emptySinceTick 供 ChunkSystem 使用, 要么在 ChunkSystem 里对 lastEmptyTick<0 的空置实例用自己的计时器兜底。
- **复核**: 维持 — 核心论断成立: ChunkSystem.tickInstance(:104-115) 确为 `emptySince<0 -> demoteToLoadOnly + return`, 只有 >=0 才可能走到 releaseAll; InstanceManager.onPlayerLeave(:450-458) 对固定实例明确不打 lastEmptyTick, 而 onPlayerEnter(:438) 会把它写回 -1, R1 下存活实例恰只有三个固定实例 (isFixedInstance :204-206), 故 releaseAll 分支确实永不执行。细节上我要修正并加重: 我从 Forge 47.3.22 源码 ForgeChunkManager.java:110-140 读到 remove 走 `tickets.remove(ticketOwner, chunk, ticking)` 且 type 取 `ticking ? BLOCK_TICKING : BLOCK` —— ticking 标志决定操作哪一张票表。而本仓所有撤票调用 (ChunkTicketManager releaseAll :170、demoteToLoadOnly :200、applyDesired :226/:241) 一律传 ticking=false, 因此以 ticking=true 加过的票 (ensureTicking :135、refreshWindow 内圈 tickRadius=2 的 5x5) 在任何路径下都撤不掉, 且 Forge 的 ForcedChunksSavedData 会落盘并在开服 reinstate, 泄漏跨重启累积。另一处与审计员描述不符但不影响结论: onPlayerLeave 里 scheduler.release(inst) (InstanceManager:456 -> GenerationScheduler:179-197) 用同一 owner BlockPos 把整个 region 的非 ticking 票扫掉了, 所以泄漏主体不是 '离开点周围 81 个 load-only 区块', 而是历史上进过 tick 圈的那批 ticking 票 (更重: ticking 区块要跑方块随机刻与实体 tick)。维持 Major。


#### F032 · 已判废的离线体素管线仍在每次开服与每次重置全量重跑: 单实例 2500 万体素, 单次约 96MB 的 int 队列缓冲, 结果无人读取

- **维度**: 性能 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/instance/InstanceManager.java:138`
- **证据**: rebuildFromStorage 对每个存档里的实例执行 `default -> { inst.setGenState(GenState.PENDING); scheduler.submit(inst); }`, 即每次开服都把三块固定实例重新提交离线生成; ResetJob:119 也会再触发一次。管线规模: RegionBox.ofDefault 用 REGION_SIZE 256 x REGION_HEIGHT 384 x 256 = 25,165,824 体素 (MiningConstants:53-59); ConnectivityFixer.java:62 `int[] queueBuf = new int[total]` 就是约 96MB, 循环体内还对每个非主分量 `new BitSet(total)` (每个约 3.1MB); NoiseCarver.java:82 每趟 `grid.copy()` 再全量三重循环, 共 3 趟。而产物只写进 GenerationScheduler.voxelCache, 唯一读者是 MiningChunkGenerator (经 MiningVoxelLookup), 该 ChunkGenerator 在 dimension/mining.json 里根本没被引用 (generator.type = minecraft:noise)。同一事实已被 job/miner/OreScanService.java:22-24 的注释确认过一次 ('维度已改用 minecraft:noise 生成 + 原版 ore feature, 无任何生产调用方填表')。
- **影响**: 每次服务端启动都会在后台线程 (maxGenWorkers 默认 2) 跑三份 2500 万格的洪泛/雕刻计算, 峰值堆占用上百 MB 且产生大量短命大对象 (每个孤岛一个 3MB BitSet), 换来的数据没有任何消费方。副作用是开服后固定实例先被置回 PENDING, 此间玩家的 allocate future 一直挂起 (EntryGateway 要等 future 兑现后才登记 PendingEnter), 表现为点了进入之后长时间毫无反馈。GenerationScheduler.enqueueRegionChunkLoads 随后还会把三块区域共 768 个区块逐帧强加载并在无人进出时长期保持。
- **建议**: 既然维度已经改走 vanilla noise + datapack ore feature, 应当把离线体素管线整条摘掉 (或至少在 submit 处按开关短路), 同时移除随之空转的 region 全量区块预加载; 若要保留代码作为未来路线, 也必须让它默认不在启动路径上执行。
- **复核**: 维持 — 全部核实且实际开销比审计员说的还大。InstanceManager.rebuildFromStorage 的 default 分支 (:136-142) 对每个存档实例 setGenState(PENDING)+scheduler.submit, 三块固定实例每次开服必重跑; ResetJob:119 再触发一次。规模: RegionBox.ofDefault (:21-24) 取 MiningConstants 的 256x384x256 = 25,165,824 体素 (:53-59); ConnectivityFixer.fix 里 `int[] queueBuf = new int[total]` (:62) 约 96MiB, 且 :58 的首次 floodFillInto 传 null 会再 `new int[total]` 又一份 96MiB (审计员只数了一份), 每个非主分量另 `new BitSet(total)` 约 3.1MB; NoiseCarver WIDEN_PASSES=3 (:49), 每趟 grid.copy() (:82) + 全盒三重循环。产物无消费方已双向坐实: dimension/mining.json 用 minecraft:noise, MiningChunkGenerator 未被引用; 同一事实 job/miner/OreScanService.java:22-25 的注释也已白纸黑字记过一次。挂起表现属实: attachAllocationFuture (:257-266) 在非 enterable 时把 future 塞进 pendingAllocations 干等, EntryGateway.onAllocateComplete (:134-164) 兑现后才登记 PendingEnter, 且此前无任何超时, 玩家点进入后确实零反馈。enqueueRegionChunkLoads (:136-147) 三块共 768 区块的强加载也确实在无人进出时长期保持 (ChunkSystem 只查自己那张票表, hasTickets 恒 false 不会去收)。维持 Major。


#### F033 · 静态陷阱表零生产调用方: 三块矿洞里没有任何静态陷阱, 矿工 L5/L8 陷阱探测技能恒空返

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/trap/TrapSystem.java:129`
- **证据**: staticPlacement(long) 在缓存未命中时返回 `new StaticTrapPlacement(state.regionBox(), java.util.Map.of(), java.util.List.of())` —— 一张空表。唯一能填缓存的入口 staticPlacementFor(...) (:104) 全库 grep 零调用点 (只有它自身定义与 javadoc 提到), 因为设计里由 GenerationScheduler 在离线阶段预热, 而离线阶段的产物链路已判废。下游 job/miner/TrapScanService.java:47 正是拿 `TrapSystem.get().staticPlacement(...)` 再逐格 placement.trapAt(...) (:66), 必然恒空。对照组: OreScanService.java:22-24 记录了矿物侧同一个坑并已改为扫真实世界, 陷阱侧没跟上。
- **影响**: 9.5 章整套静态陷阱 (StaticTrapGenerator 238 行 + TrapParams 的难度因子/致死密度/间距数值表) 是死代码, 玩家在 Medium/Hard 区遇不到任何静态陷阱; 矿工花技能点解锁的 L5/L8 陷阱探测永远显示 '无陷阱', 且每次激活还要空跑一个 radius^3 的三重循环。DynamicTrapEngine 里 '身后刷怪避开致死陷阱区' 的判据 (:213 statics.inLethalTrapRadius) 也恒为 false, 形同虚设。
- **建议**: 二选一: 要么按矿物侧的既有修法, 把陷阱落到 datapack feature / 真实方块上并让扫描读真实世界; 要么在重置/生成流程里真正调用 staticPlacementFor 预热并让某处把表落成方块。在没落地之前, 矿工技能面板不应展示这两条技能, 否则等于卖空气。
- **复核**: 维持 — 读 TrapSystem 全文核实: staticPlacement(long) (:119-130) 缓存未命中时返回 `new StaticTrapPlacement(regionBox, Map.of(), List.of())` 空表; 唯一填缓存入口 staticPlacementFor (:104-113) 全库 grep 只命中自身定义与两处 javadoc, 零调用点 (设计里由 GenerationScheduler 离线预热, 而该链路已判废见第 4 条)。下游确实恒空: job/miner/TrapScanService.java:47 取 staticPlacement 后 :66 逐格 trapAt, 必然返回空列表, 而 MinerActions.java:73-87 的 tryTrapScan 已完整接线 (等级门 + CD + 发 MinerHighlightS2C + 发聊天提示 hits.size()), 即技能面板真的卖了一个恒为 0 命中的技能。DynamicTrapEngine.java:213 的 `!statics.inLethalTrapRadius(...)` 亦恒 true (判据形同虚设)。对照组属实: OreScanService.java:22-25 记录了矿物侧同一个坑并已改扫真实世界, 陷阱侧没跟上。维持 Major。


#### F034 · 出生点池与占位 TTL 整套死代码: 同一难度的所有玩家恒定落在同一格

- **维度**: 功能缺口 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/entry/EntryGateway.java:309`
- **证据**: resolveSpawn 自己写了一套扫描: 以 region 几何中心为心做由内向外的环形搜索, 每列自顶向下取第一个 `MiningServices.spawnService().isSafe(level, cursor, inst)` 命中的格并直接 return (:309-316)。而 SpawnSystem 里为此准备的 findSpawn / buildPool / SpawnPool.claim 占用 TTL (OCCUPY_TTL_TICKS=60) / buildFallbackPlatform / onInstanceReleased 全库 grep 零调用点, 只有 isSafe 被用到。因为扫描顺序完全确定且地形静态, 同一难度返回的落点是一个固定坐标。
- **影响**: R1 是三块共享常驻区域, 于是全服所有进入 Easy 的玩家都落在同一格, Medium/Hard 同理: 实体互相挤压、一颗苦力怕/一次岩浆喷发能覆盖所有刚进场的人, 也无法分散人流。设计文档 11.3/11.4 为此写的候选池与 60 tick 占用 TTL 防叠人机制事实上从未运行过。另外这套扫描在最坏情况 (中心附近整段实心) 要在单 tick 内做约 49x49 列 x 约 190 层的方块查询, 且扫描半径 24 格会越出入场前只强加载的 3x3 区块窗口, 触发主线程同步加载/生成邻近区块。
- **建议**: 把入场落点接回 SpawnSystem (即使体素视图已废, 也可以在真实世界上建池并复用 claim 的占用 TTL), 至少要做到多候选点 + 占位, 避免所有人同格; 同时把扫描半径与入场前强加载的区块窗口对齐, 别让扫描踩到未加载区块。
- **复核**: 维持 — EntryGateway.resolveSpawn (:287-321) 确为自写的中心向外环形扫描, 每列自 yTop 向下取首个 spawnService().isSafe 命中即 return; SpawnSystem.isSafe (:110-135) 只判头顶净空/脚下 isFaceSturdy/邻域无岩浆, 不含任何占用或随机项, 加上地形静态 -> 同一难度返回恒定同一格, 结论成立。死代码属实: findSpawn(:78)/buildPool(:185)/SpawnPool.claim+reserve(:88/:96/:172, OCCUPY_TTL_TICKS=60 :49)/buildFallbackPlatform(:148)/onInstanceReleased(:258) 全库 grep 零外部调用点, ISpawnService 只有 isSafe 被 EntryGateway:311 用到; EntryGateway 自己另写了一份私有 buildFallbackPlatform(:324-339)。扫描越界那半条只部分成立: SPAWN_FORCE_RADIUS_CHUNKS=1(:54) 对 SPAWN_SCAN_HORIZONTAL_RADIUS=24(:59) 确实不够, 但开服后 GenerationScheduler 把整块 region 的 256 区块都强加载着, 只有在某玩家离开触发 scheduler.release 卸掉整区之后才会踩到主线程同步加载; 另审计员写的 '约 190 层' 实际是 yTop=318/yBottom=-63 约 381 层, 数字偏小。主结论 (R1 共享区全服同格 + 11.3/11.4 池与占位 TTL 从未运行) 成立, 维持 Major。


#### F035 · Hard 区 danger 出场即顶格: zone 权重乘满已等于 DANGER_MAX, 时间/矿富集项完全失效

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/pressure/Danger.java:29`
- **证据**: ZONE_HARD = 1.00f (:29), compose() 计算 `raw = wZone*zoneTerm + wTime*timeTerm + wOre*oreTerm` 后 `clamp(raw, 0, dangerMax)` (:113-116)。配置默认 danger.weightZoneDifficulty=1.0 且 danger.max=1.0 (MiningServerConfig:175-178), 所以 Hard 区 raw 至少 1.0, 恒被钳到上限; MEDIUM 是 0.55, 叠 wTime(0.5)*timeTerm 最高 1.05 也很快顶格。SpawnTier.EXTREME 的下界是 0.80 (SpawnTier:39)。
- **影响**: Hard 区玩家一出 200 tick 出生冻结就是 EXTREME 档: 每 120 tick 刷 3-4 只 (含苦力怕/洞穴蜘蛛/女巫), 且 danger 恒 >= DANGER_THRESH_LAVA(0.70) 与 COLLAPSE(0.55), 岩浆喷发与坍塌常驻, 压力系统设计的 '越待越危险' 成长曲线在 Hard 区完全不存在, 矿工耐压等职业系数 (timeAccrueFactor 只缩放 tWin) 也一并失效。反过来 Easy 区上限是 0.2+0.5=0.70, 待够久同样会踩到岩浆喷发阈值, 而 TrapParams:20 明确规定 Easy 难度因子为 0 (新手区不应有致死陷阱), 两处口径互相矛盾。
- **建议**: 要么把 zoneTerm 的权重/取值压到给时间项与矿富集项留出余量 (例如 Hard zone 取 0.6-0.7), 要么把 danger.max 与各项权重重新配平, 使三档在 '刚进场' 与 '久留' 之间有可区分的区间; 同时给动态陷阱的 danger 阈值加难度门 (Easy 区不触发致死类)。
- **复核**: 维持 — 数值链全部对上: Danger.ZONE_HARD=1.00f(:29), compose(:106-117) 的 raw = wZone*zone + wTime*time + wOre*ore 后 clamp 到 dangerMax; MiningServerConfig:175-180 默认 danger.max=1.0 / weightZoneDifficulty=1.0 / weightTimeSpent=0.5, ModConfig:111-123 是直读无缩放。故 Hard 恒 raw>=1.0 被钳到 1.0, SpawnTier.EXTREME 下界 0.80(:39) -> 出了 200 tick 冻结 (MobPressureSystem:56/126 + Danger:149-151 钳 0.15) 即恒 EXTREME (120 tick 刷 3-4 只含 creeper/cave_spider/witch), 且恒 >= TrapParams 的 LAVA 0.70 与 COLLAPSE 0.55, 时间项与 DangerJobFactor 的耐压系数 (只缩放 tWin) 在 Hard 完全失效, 成长曲线不存在。Medium 0.55 起步、约 1 分钟后也顶格。Easy 上限那半条我实算复核后同样成立 (略微 marginal): timeTerm 是 (float)(1-exp(-tWin/1200)), tWin 大到约 2 万 tick 时因 float 精度精确等于 1.0f, danger 恰为 0.7f >= DANGER_THRESH_LAVA=0.70f; 更早在 tWin≈1200-1500 就已越过 0.50/0.55 触发身后苦力怕与坍塌, 与 TrapParams:20 '新手区无致死陷阱' 的口径确实互相矛盾 (动态陷阱侧无难度门)。维持 Major。


#### F036 · 动态坍塌把承重方块无差别替换成砂砾: 会静默吞掉玩家头顶的矿石, 并成为零成本砂砾产出

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/trap/DynamicTrapEngine.java:305`
- **证据**: dropColumn: `BlockState falling = Blocks.GRAVEL.defaultBlockState(); level.setBlock(source, Blocks.AIR..., UPDATE_CLIENTS); FallingBlockEntity fb = FallingBlockEntity.fall(level, source, falling);` —— 源方块的真实 BlockState 被读出来 (:300) 却只用于判空, 落下的恒是砂砾。选点 findCeilingBlock (:274-290) 只要求 '非空气且无流体', 不排除矿石、玩家放置的方块或方块实体。
- **影响**: danger >= 0.55 时每玩家每 200 tick 触发一次, 每次 1-3 列: 玩家头顶 2-6 格内的钻石矿/深层矿会被直接删掉并变成一块掉下来的砂砾, 属于玩家可感知的资源丢失 (且死亡不掉落的公服里玩家会认为是 mod 偷东西); 反向看这也是一个不受任何闸门约束的砂砾/燧石产出源。若头顶是玩家放置的箱子等方块实体, 同样被静默清除。
- **建议**: 坍塌应当保留原方块 (用读到的 BlockState 生成 FallingBlockEntity), 或者只在可坍塌的基材 (石头/深板岩/砂砾类) 上触发, 显式排除矿石、容器与玩家放置物; 落点方块类型不应硬编码成砂砾。
- **复核**: 维持 — 主缺陷成立: dropColumn(:296-310) 把源方块的真实 BlockState 读进 bs(:300) 后只用于判空, 落下的恒是 Blocks.GRAVEL(:305), 源格 setBlock 成 AIR 再 FallingBlockEntity.fall; 选点 findCeilingBlock(:274-290) 只要求 '非空气且无流体', 不排除任何矿石。可达性核实: danger>=0.55 (TrapParams:117) 在 Hard 恒成立 (见第 7 条), 每玩家 COLLAPSE_PER_PLAYER_COOLDOWN_TICKS=200 一次、每次 COLLAPSE_MIN/MAX_COLUMNS=1-3 列 (:108-109), 取玩家脚上 2-6 格内的第一块实心 —— 命中矿石即静默删除并变成一块掉下来的砂砾, 在死亡不掉落的公服上属玩家可感知的资源丢失, 且矿洞是矿工职业主 faucet 的作业面。但审计员的一半推论要推翻: '玩家放置的箱子等方块实体被静默清除' 不成立 —— rules/RulesSystem.java:44-72 对矿山维度全量拦截非白名单放置, MiningServerConfig:237-240 的 rules.placeWhitelist 默认只含 minecraft:scaffolding, 玩家在矿洞里根本放不下箱子等容器。砂砾/燧石产出源在经济尺度上近乎无价值, 该论点也可忽略。缺陷核心 (静默吞矿 + 落点方块类型硬编码) 成立, 维持 Major。


#### F037 · 精英怪 (自研 champion capability) 不在堆叠排除项内, 战斗中可被合并 discard, 玩家已累积的贡献与奖励全部作废

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackMerge.java:42`
- **证据**: MiningChampions.java:57-65 给每个 Mob 挂 MiningChampionData capability, 由 star>=1 判定是否冠军 (MiningChampions.java:76); champion 包内 grep 不到任何 setCustomName / getPersistentData 的盖章。StackMerge.canStack 与 StackMatchKey.of 都不查 MiningChampions.isChampion, 也不读 star, 故 5星冠军僵尸与普通僵尸的 StackMatchKey 完全相等, 落在同一分组内按 mergeGroup 的先后序贪心合并; 谁当 anchor 取决于 level.getEntities 的遍历序, 即随机。
- **影响**: 冠军怪与普通同种怪同处 5 格内 (刷怪塔、矿洞遭遇战的常态) 时: 若冠军不是 anchor, 它被 discard —— BOSS 在玩家打到一半时凭空消失, 且 discard 不触发 LivingDeathEvent, ChampionRewardHandler 永远不结算, 玩家按伤害累积的贡献池奖励全部作废 (BloodPoolRegistry 里以 UUID 为键的条目也随之泄漏, 永不清理); 若冠军是 anchor, 它会吸收 N 只普通怪变成 "僵尸 xN", applyLabel 覆写其显示名, 且死亡时按 StackDeath 再补 N-1 份该冠军的原版掉落与经验。
- **建议**: canStack 增加一条硬排除: MiningChampions.isChampion(entity) 为真即不参与堆叠 (与 Boss 排除同级)。若日后确实要允许同星级冠军堆叠, 星级与词条集合必须进 StackMatchKey 的 variant 维度, 否则不同词条的冠军会被当成同类。
- **复核**: 维持 — 核对成立, 且 dev/生产行为一致 (不属于 compileOnly 只能真服验的那类): 1) 冠军是自研 capability 不是第三方 mod: ChampionSystem.java:42-45 明确 "故本入口【无条件】注册 (不再 ModList.isLoaded(\"champions\") 守卫)", :64-66 挂 cap, :73 bind promoter, :76-103 全部 handler 无条件 register。所以生产环境冠军必然存在, 不存在 "dev 不加载所以路径不同" 的豁免。 2) 无盖章可被现有排除项误打误撞挡住: 全库 grep `setCustomName`, champion 包零命中 (唯一命中是 StackMerge 自己与 stacking/agent 测试), BOSS 血条走的是 ChampionBossBarHandler.java:108 的独立 `new ServerBossEvent(...)`, 不改实体 CustomName。故 StackMerge.java:50 的命名排除挡不住冠军。ChampionPromoter 只改 Attributes.MAX_HEALTH 修饰 (applyBaseHealth) 与挂 cap, 无任何 canStack 会读的字段。 3) canStack (StackMerge.java:42-65) 与 StackMatchKey.of 均不调 MiningChampions.isChampion (MiningChampions.java:76-78 star>=1 判定), 5★ 僵尸与普通僵尸完全等键, mergeGroup (StackMerge.java:125-164) 按 group 列表先后序贪心, anchor 取决于 getEntities 遍历序。 4) 账本泄漏属实: BloodPoolRegistry.remove 与 ContributionTracker 的清理只挂在 LivingDeathEvent 上 (ChampionBloodPoolHandler.java:315-318, ChampionRewardHandler.java:97/101/106), discard 路径不触发该事件, 血池条目与贡献账本双双残留, 只有 ChampionSystem.java:126-137 的 ServerStopping 才 reset。 严重度维持 Major: 后果是 BOSS 战中途消失 + 奖励作废 + 静态表泄漏, 恶劣但不构成 #1/#2 那种世界级/资产级不可逆故障。根因与 #1/#2 同源 (黑名单而非白名单)。


#### F038 · 堆叠 N 只共用一份血量却按 N 份结算掉落与经验, 击杀成本被除以 N 而产出被乘以 N (默认上限 64 倍)

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackDeath.java:86`
- **证据**: handleInstantAll 对 N-1 个虚拟个体逐个跑 rollStackedLoot (StackDeath.java:88) 并 awardStackedExperience(level, entity, N-1) (StackDeath.java:90); DeathMode 默认 INSTANT_ALL (StackingConfig.java:90), DROPS_MULTIPLY_XP 默认 true (StackingConfig.java:94), MERGE_MAX_STACK_SIZE 默认 64 (StackingConfig.java:83)。而合并侧 StackMerge.mergeGroup 只做 StackData.setStackSize(anchor, anchorSize + moved) —— 全程没有任何对 anchor 最大生命/护甲/伤害的等比放大, 幸存者的血量仍是单只原版血量。
- **影响**: 一座刷怪塔里 64 只僵尸被压成一个 20 血的实体, 玩家打死它一次即得 64 次独立 loot roll + 64 份经验; 同理牛/鸡/羊的养殖场, 屠宰成本降到 1/64 而肉与皮革产出不变。这等于把全服所有怪物掉落与经验 faucet 的上限直接乘 64, 而这些产物可经跳蚤市场变现。该模块自己的 FR-3.4 写明"严禁因堆叠出现免冷却或无限产出", 击杀成本这一侧恰恰没有按 N 结算。
- **建议**: 两条路二选一并写进经济总表: 要么合并时把 maxHealth 按 N 放大 (击杀成本随之 N 倍, 掉落 N 倍才成立), 要么保留单份血量但把 deathMode 默认改成 ONE_PER_KILL 且 multiplyXp 默认 false。在与 Economy_BalanceSheet 对账通过前, 建议默认关闭 drops.* 与 passive.* 全部倍增开关。
- **复核**: 维持 — 代码事实逐行核对无误, 但需要修正它的定性口径: 1) 事实成立: StackDeath.java:86-91 handleInstantAll 对 N-1 个虚拟个体跑 rollStackedLoot (:88, PER_INDIVIDUAL 下 :145-148 逐个独立 roll) 并 awardStackedExperience(level, entity, N-1) (:90); 默认值 StackingConfig.java:90 deathMode=INSTANT_ALL、:94 multiplyXp=true、:83 maxStackSize=64。合并侧 StackMerge.java:148 确实只写 StackData, 全文无任何 MAX_HEALTH/护甲/伤害的等比放大, 幸存者血量就是单只原版血量。 2) 需修正的定性: 这不是实现偏离规格, 而是【规格本身】要求的。docs/Minecraft实体堆叠_需求规格说明书.md:43 FR-2.1 (MUST) "击杀堆叠数为 N 的实体, 其掉落与经验等价于击杀 N 个独立同种实体", :47 FR-2.5 instant_all, 参数表 :19/:21 默认值也照抄。审计员引 FR-3.4 (确在 :55 "严禁因堆叠出现免冷却或无限产出") 来指认矛盾是合理的, 但 FR-3.4 写在 FR-3 被动产出章节下, 严格说约束的是剪毛/挤奶/产蛋冷却 (StackPassive.java:26-39 已按此实现), 并非 FR-2 击杀链。所以本条的实质是【经济评审缺口】而非代码 bug。 3) 之所以仍然成立且够 Major: 该模块已被 MiningDim.java:148-153 无条件装配进主类, 三个 faucet 倍增开关全部默认 true, 而 StackingConfig.java:15-16 自己的注释就写着 "被动产出 xN + 经验 xN 是 faucet 倍增器, 与反洗钱定价强耦合 (见 Economy_BalanceSheet), 服主平衡评审前可一键关" —— 即作者自己承认要过总表评审, 却以全开默认上线。我 grep 了 docs/Economy_Completeness_Audit.md 与 Economy_Laundering_Review.md, 二者均无实体堆叠条目, 说明这一层对账确实没做过, 不属于 "已在册不必重报"。 4) 补一条审计员漏掉的、比掉落倍增更强的放大机制: 原版自然生成受每玩家实体计数上限约束, 把 64 只压成 1 个实体等于清空 63 个刷怪配额, 刷怪塔的【生成速率】本身也被抬高, 与掉落倍增叠乘。 维持 Major, 不升 Critical: 后果是经济曲线偏移而非数据损毁, 且 deathMode/multiplyXp/passive.* 均可配置关闭。
- **判定 (2026-08, 分支 fix/stacking-animal-whitelist)**: 按主控决策 D2 关闭, 不作为缺陷修。理由: 合并候选已收口为白名单 minecraft:pig / chicken / sheep / cow 四种低价值动物 (见 F004/F005/F018/F037 的修复), 刷怪塔与精英怪链路彻底退出堆叠, 掉落倍增的经济面只剩生猪肉/牛肉/皮革/羽毛/羊毛, 且这些产物不接入 credit faucet; 规格 FR-2.1 (MUST) 要求 "击杀堆叠数 N 的实体等价于击杀 N 个独立同种实体", 保留 "猪 x64 就掉 64 份" 正是该 MUST。StackDeath 的掉落份数逻辑本分支不作任何改动。


#### F039 · 血池注册表只在死亡事件回收, 自然 despawn 的 6★+ 冠军条目永久驻留, 且每 tick 全表复制 + 逐条查实体

- **维度**: 性能 | **严重度**: Major | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionBloodPoolHandler.java:350`
- **证据**: onServerTick 每个服务端 tick 都执行 `Map<UUID, BloodPool> pools = BloodPoolRegistry.snapshot();` (snapshot 内部 `new LinkedHashMap<>(POOLS)` 全表复制), 再对每条 `mining.getEntity(entry.getKey())` 查一次实体。而 BloodPoolRegistry.remove 的生产调用点只有两处: 本文件 149 (拦死分支) 与 317 (onLivingDeath)。全库 grep 无 EntityLeaveLevelEvent / checkDespawn / 区块卸载 相关的池回收钩子。ChampionPromoter.applyBaseHealth (158) 只 install 从不注册回收; MobPressureSystem.spawnMob (247/263) 用 MobSpawnType.SPAWNER 落地且从未调 setPersistenceRequired (全库 setPersistenceRequired 只在 DynamicTrapEngine:149 出现一次), 故冠军会走原版 Mob.checkDespawn 的 discard 路径消失, 不发 LivingDeathEvent。
- **影响**: ChampionSpawnPolicy HARD 档升格率 0.15 且星级在 [5,10] 均匀掷, 即 HARD 实例里约 12.5% 的刷怪会建血池。玩家离开矿洞后这些怪自然 despawn, 每只留下一条永不回收的 POOLS 条目。长期运行的公服上, 主线程每 tick (20 次/秒) 都要复制整张越来越大的表并做同样多次 UUID→实体查表, 内存与 tick 开销随在线时长单调增长, 重启才归零。
- **建议**: 给血池加一条与死亡事件并列的回收通道: 订阅实体移除/离开世界事件, 按 RemovalReason 区分 —— DISCARDED/KILLED 回收, UNLOADED_TO_CHUNK 保留 (怪还在, 只是卸载)。同时 onServerTick 不必每 tick 全表复制, 在册为空时已早退, 非空时可直接遍历原表 (主线程串行, 遍历中无 install/remove)。
- **复核**: 维持 — 逐条核实, 全部属实, 且比审计员说的更有力。(1) 回收口径: 全库 grep BloodPoolRegistry.remove, 生产调用点确实只有 ChampionBloodPoolHandler:149 (拦死分支) 与 :317 (onLivingDeath), 其余全在 GameTests。服务端侧无任何 EntityLeaveLevelEvent 订阅 (grep 只命中两个 client 包: ChampionSizeRenderClient:146 与 PlasmaShieldClientLifecycle:38, 都是客户端渲染态清理, 与血池无关)。(2) despawn 可达性: 全库 setPersistenceRequired 只有 DynamicTrapEngine:149 一处 (给陷阱苦力怕)。MobPressureSystem.spawnMob (247-263) 用 MobSpawnType.SPAWNER 落地后直接 addFreshEntityWithPassengers, 没有任何持久化标记, 冠军会走原版 Mob.checkDespawn 的 discard 路径。(3) 决定性反证材料: 本仓自己的同类状态表【全部】都建了 TTL 清扫兜底, 且注释逐字点名这个泄漏向量 —— ChampionSelfEffectHandler:93、ChampionSizeHandler:63、ChampionSummonHandler:64、ChampionBladeWaltzHandler:72、ChampionBlinkHandler:61、ChampionCaesarSwapHandler:65、ChampionElectroChargeHandler:65、ChampionSelfRepairHandler:59、ChampionTacticalBlinkHandler:53、ChampionThunderHandler:67、ChampionVisualDisruptionHandler:47 全写着『冠军未设 persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏』, 并配 STATE_SWEEP_INTERVAL_TICKS=1200 的 sweepStaleStates。血池是这批 per-冠军状态里【唯一】没有清扫通道的一个, 属于明确的一致性缺口而非设计取舍。(4) 第二条泄漏路径审计员没提: MobPressureSystem:393 注释明写『实体本身的移除由重置流程的 region 清块完成』, 即每次实例自动重置 (AutoResetScheduler 按 reset.autoResetHours 到点触发) 会成批清掉在场冠军且不发死亡事件, 整批血池全泄漏。(5) 每 tick 开销属实: ChampionBloodPoolHandler:350 先无条件 BloodPoolRegistry.snapshot() (BloodPoolRegistry:84 内部 new LinkedHashMap<>(POOLS) 全表复制) 才在 351 判 isEmpty, 空表也白复制一次; 359-364 对每条做一次 mining.getEntity。(6) 量级: ChampionSpawnPolicy:37/45-46 确为 HARD 0.15 + 星级 [5,10] 均匀掷, 6★+ 占 5/6 -> 约 12.5% 的刷怪建池 (StarRank:132-134 usesCustomBloodPool 判 star>=6; ChampionPromoter:144 另加 effectiveHp>1024 也建池, 故实际比例更高)。SpawnTier EXTREME 间隔 120 tick 单波 3-4, 泄漏随在线时长单调增长, 重启才归零。判 Major: 无功能性错误 (getEntity 查不到就跳过), 但是无界泄漏 + 主线程每 tick O(N) 复制, 且违反本仓已确立的清扫惯例。


#### F040 · 血池纯内存不持久化也不在载入时重建, 服务端重启后存活的 6★+ 冠军永久退回 vanilla 血权威

- **维度**: 兼容性 | **严重度**: Major | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/bloodpool/BloodPoolRegistry.java:27`
- **证据**: POOLS 是 `private static final ConcurrentHashMap<UUID, BloodPool>` 纯内存表, ChampionSystem.onServerStopping (127) 调 reset() 清空。install 的唯一生产调用点是 ChampionPromoter.applyBaseHealth:158, 只在 spawn 盖章那一刻执行; 全库无 EntityJoinLevelEvent / load 路径重建池。另一侧 MiningChampionData 的 star/effectiveHp/affixes 是随实体 NBT 持久的 (serializeNBT:131-147), 血量属性修饰也是 addPermanentModifier (ChampionPromoter:152) 随 NBT 存盘。mods.toml 的 dependencies 段只声明了 forge/minecraft/mcef/farmersdelight, 没有 attributefix。
- **影响**: 重启后仍存于矿洞区块里的 6★+ 冠军, BloodPoolRegistry.get 返回 null, ChampionBloodPoolHandler:122-127 落入"1-5★ 无池"分支, 战斗权威静默从影子血池切回 vanilla 血 —— 这正是 spec 6.2 #2 明令禁止的口径。在没装 AttributeFix 的服务器上 generic.max_health 被原版钳在 1024, 一只 27000 有效血的 8★ 重启后实际只剩 ≤1024 血 (26 倍削弱), 而 ChampionRewardHandler:112 读的奖励门槛分母仍是 NBT 里的 27000, 于是"秒杀 BOSS 还照发全额奖励"。ChampionSelfEffectHandler/ChampionSelfRepairHandler 的回血、ChampionAttackHandler 的嗜血低血判定也全部一并退化到 vanilla 口径。
- **建议**: 把 currentHp 一并写进 MiningChampionData 的 NBT (maxHp 可由 effectiveHp 复原), 并在实体进入世界 / capability 载入完成后按 star 与 effectiveHp 重建血池; 或者反过来彻底承认 vanilla 属性为存档态、血池只作运行期缓存, 但那样必须显式声明 AttributeFix 为必需依赖并在 mods.toml 里锁死, 二选一, 不能像现在这样两套口径谁也不兜底。
- **复核**: 维持 — 结构性事实全部属实, 但『26 倍削弱 + 秒杀 BOSS 照发全额奖励』这个后果有前提条件, 审计员没说清, 我补上。核实成立的部分: BloodPoolRegistry:27 POOLS 是纯内存 ConcurrentHashMap, ChampionSystem:127 在 ServerStoppingEvent 里 reset() 清空; install 的生产调用点全库只有 ChampionPromoter:158 一处, 位于 applyBaseHealth 内, 只在 spawn 盖章那一刻执行; 全库无 EntityJoinLevelEvent / capability load 路径重建池。另一侧 MiningChampionProvider 实现 ICapabilitySerializable, MiningChampionData:131-147/153-178 把 star/effectiveHp/affixes 随实体 NBT 存盘; ChampionPromoter:152 用 addPermanentModifier 写血量修饰也随 NBT 持久。mods.toml 我通读了, dependencies 段只有 forge/minecraft/mcef/farmersdelight 四条, 确实没有 attributefix。重启后 BloodPoolRegistry.get 返 null, ChampionBloodPoolHandler:122-127 落入 1-5★ 分支, 战斗权威静默从影子血池切回 vanilla, 违反 spec 6.2 #2。触发路径可达: AutoResetScheduler 是按 reset.autoResetHours 到点触发的, 不在服务端启动时清场, 故存盘时活着的冠军会随实体区块存盘并在玩家返回矿洞时重新加载。必须修正的部分: 后果分两种环境。(a) 服务器装了 AttributeFix (ChampionPromoter:137-141、BloodPool:134-138、ChampionBloodPoolHandler:323-326 三处注释都明写测试服装了它, max_health 上限抬到 1e6): 此时 getMaxHealth() = effectiveHp 真值, BloodPool:143-155 的镜像写回的就是池的真实 currentHp, 重启后冠军拿着 27000 的真血继续打, 减伤词条也照常生效 (ChampionBloodPoolHandler:126 的 null-pool 分支仍写回净伤), 各消费点也都有 vanilla 回退 (ChampionSelfEffectHandler:370-386、ChampionAttackHandler:461-471), 数值上基本无感, 只是权威口径变了。(b) 服务器【没装】AttributeFix: 原版 RangedAttribute 把 max_health 钳在 1024, 镜像退化成 fraction×1024, 重启后 27000 有效血的 8★ 实际只剩 ≤1024 血, 而 ChampionRewardHandler:112 读的奖励门槛分母仍是 NBT 里的 27000、:118 的固定池仍是 star×600, 审计员说的『秒杀 BOSS 照发全额奖励 + 6 青辉石』成立。判 Major 而非降级: 整套 6★+ 血池设计静默依赖一个 mods.toml 里【没有任何声明也没有任何运行期检测】的第三方 mod, 一旦运维漏装或该 mod 未跟上版本, 精英怪主线直接崩成可刷的经济漏洞, 而且没有任何日志会告警。审计员给的二选一 (currentHp 入 NBT + 载入重建, 或承认 vanilla 属性为存档态并把 AttributeFix 锁进 mods.toml) 是对的, 现状是两套口径互不兜底。


#### F041 · market.categories 回执必然超过 32767 字符下行上限, 分类树在真服永远加载不出来

- **维度**: 缺陷 | **严重度**: Major | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/market/MarketActions.java:496`
- **证据**: MarketActions.java:496 CATEGORIES = (sender, payload) -> GSON.toJson(MarketCategoryTree.build()), 无分页无裁剪; MarketCategoryTree.java:115-128 对 ForgeRegistries.ITEMS.getKeys() 全量枚举, 每个物品产出一条 {"id":"i_<ns>_<path>","label":"<descriptionId>","itemId":"<ns>:<path>"} 叶子 (典型 90-110 字符)。WebUiServerDispatcher.java:147-156 respond 的硬闸: resultJson.length() > FriendlyByteBuf.MAX_STRING_LENGTH (32767) 时整条回执被换成 RESPONSE_TOO_LARGE 失败回执。仅原版 1.20.1 就有约 1300 个物品 -> 回执约 12 万字符, 加 miningdim 自有物品与 TACZ 只会更大; 即使按最短可能的叶子 40 字符算, 819 个物品就撞线。MarketBridgeGameTests.java:231-234 只断言树结构与叶子有序, 不断言体积。
- **影响**: 跳蚤市场面板左栏分类筛选在任何真实服务器上都拿不到数据, 前端只会收到 success=false + RESPONSE_TOO_LARGE。这是玩家侧唯一的品类导航入口, 等于市场只能靠模糊搜索用。开发期若用精简 mod 集也未必复现, 上真服必现。
- **建议**: 回执必须与注册表规模解耦: 分类树只下发分支骨架 (6 个顶层 + 3 个子类), 叶子改成按分类分页拉取 (复用 market.list 已有的 page/pageSize 钳制口径), 或改为只在 market.list 里按 categoryId 服务端过滤而不再下发全量叶子。顺带给这条 action 补一个体积断言, 否则改回来也没人发现。
- **复核**: 维持 — 事实与算术都核实过, 成立。MarketActions.java:76 注册 market.categories -> :496 CATEGORIES = GSON.toJson(MarketCategoryTree.build()), 无分页无裁剪; MarketCategoryTree.java:115-128 对 ForgeRegistries.ITEMS.getKeys() 全量枚举, 每个物品固定产出 {id,label,itemId} 三字段叶子。体积闸口确认存在且是硬闸: WebUiServerDispatcher.java:147-156 respond 内 resultJson.length() > FriendlyByteBuf.MAX_STRING_LENGTH 时整条换成 RESPONSE_TOO_LARGE 失败回执, 且 dispatchAndRespond(:118) 的成功路径也走同一个 respond。上限值实测: 从 forge-1.20.1-47.3.22 sources jar 取 net/minecraft/network/FriendlyByteBuf.java:86, MAX_STRING_LENGTH = Short.MAX_VALUE = 32767。规模实测: 同一 sources jar 的 net/minecraft/world/item/Items.java 中 'public static final Item' 共 1255 处, 即仅原版物品就 ~1255 个。按最短形态估 (如 minecraft:air 叶子 79 字符) 上限也只能装约 410 个, 原版单独就已 3 倍溢出; 按典型 iron_ingot 叶子约 100 字符算约 12.5 万字符, 近 4 倍上限。故这不是'负载大了才炸', 是任何真服必炸。消费方存在: webui/src/pages/market/BrowsePage.tsx:581 useMockAction('market.categories'), 且 webui/src/lib/actions.ts:73 把它登记为真域 action。MarketBridgeGameTests.java 只经 resolve 直调纯逻辑、绕开 respond, 故测试恒绿而生产恒挂, 与审计员描述一致。


#### F042 · 托管物品的 mod 被卸载后, 挂单仍可被买走: 买家钱照扣、货是空气

- **维度**: 兼容性 | **严重度**: Major | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/market/MarketEngine.java:196`
- **证据**: MarketEngine.java:196 ItemStack escrow = deserializeStack(row.itemNbt()) —— 1.20.1 的 ItemStack.of 对未知注册 id 会兜成 EMPTY (内部 catch RuntimeException / item 解析成 AIR), 不抛。随后 :198-201 delivered.setCount(buyCount) 后调 canInsert, 而 canInsert 自己在 :476-478 就写着 if (stack.isEmpty()) return true —— 空栈一律判为放得下。于是事务照常执行: :209 扣买家 total、:215/:220 markSold 或 reduceListing、:225 insertTxn、:229-235 给卖家全额入账。最后 :242-246 buyer.getInventory().add(空栈) 返 false -> buyer.drop(空栈) 同样是 no-op, 什么都没交付, 也没有任何告警。撤单路径 :270-285 同理, 会把挂单标 CANCELLED 后静默丢掉托管物。
- **影响**: 任何一次 mod 移除/物品 id 变更 (TACZ、自家 gunsmith_part 等), 存量挂单就变成收钱不发货的陷阱: 买家付出真实信用点、卖家收到全额、流水记录一切正常, 只有物品凭空消失, 事后无从判定是谁的锅。市场列表里这类挂单还照常显示 (MarketActions.java:199-200 对未知 id 回退显示 itemId), 玩家看不出异常。
- **建议**: 托管反序列化必须把空栈当作硬错误而不是正常值: buy/cancel 在 deserializeStack 之后立即判 isEmpty 并拒绝该笔操作 (给出稳定 errorCode), 同时把这类挂单标成不可交易状态交人工处置; canInsert 的空栈短路只对调用方保证的非空栈成立, 不该被托管路径借用。
- **复核**: 维持 — 本条我按'空栈会不会抛异常提前拦下'去证伪, 结果反而把链路坐实了。我从 forge-1.20.1-47.3.22 的 sources jar 取出 net/minecraft/world/item/ItemStack.java 逐段核对: :164-181 的 ItemStack(CompoundTag) 用 BuiltInRegistries.ITEM.get(ResourceLocation) 取 item, 未知 id 在 defaulted 注册表下得 minecraft:air 而不抛; :192-194 isEmpty() 因 delegate.get()==Items.AIR 返 true; :473-485 copy() 对空栈直接 return EMPTY 单例; 关键的 :1063-1065 setCount 在 1.20.1 是裸赋值 this.count = pCount, 既不校验也不抛 —— 也就是说 MarketEngine.java:197-198 的 escrow.copy().setCount(buyCount) 不但不会异常中断, 还顺手把全局 ItemStack.EMPTY 单例的 count 字段改成了 buyCount (isEmpty() 因 this==EMPTY 短路故未暴露, 但这是被污染的共享单例, 属额外隐患)。随后 MarketEngine.java:199 canInsert 在 :476-478 对空栈 return true, :207-237 的事务照常扣买家 total(:209)、markSold/reduceListing(:215-221)、insertTxn(:225)、给卖家全额(:229-235); 交付端我同样核了原版: Inventory.java:277-279 add 对空栈返 false, Player.java:683-685 drop 对空栈返 null, 两处都是静默 no-op。撤单路径 MarketEngine.java:270-285 更彻底 —— 不经 setCount, canInsert 放行, markCancelled 落库后托管物凭空消失。审计员引用的展示回退也属实 (MarketActions.java:199-200), 玩家侧看不出异常。定级维持 Major 而非 Critical: 触发需要一次外部注册表变更 (卸 mod / 改物品 id), 不是玩家可随时自发的印钞路径; 但一旦发生就是无声吞钱吞物且流水记录完全正常, 事后无从取证, 在'经济是重灾区'的前提下不可降级。


### A.3 Minor (66 条)


#### F043 · 关闭界面即把页面撤销授权, 但 SPA 在后台继续轮询, 每次轮询被判为"未授权页面"并清空页面数据

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: WebUI 服务端与网络基建 (webui / network / client / menu / registry)
- **位置**: `src/main/java/com/miningdim/client/webui/WebUiBridge.java:232`
- **证据**: WebUiBridge.onScreenClosed 第 231-236 行第一句就是 `allowedPageUrl = null;`。而 WebUiClient.openScreen 第 147-150 行对正式前端传 forceReload=false, 目标 URL 未变时**不重载**, 浏览器与整个 React 应用在界面关闭后原样存活 (WebBrowser.close 全库零外部调用点, 只有它自己第 200 行的内部调用)。前端的轮询不看界面开关: hooks/use-live-updates.ts:61-70 的 usePolling 只要组件挂载就挂 setInterval, MiningPage.tsx:181 以 3 秒轮询 mining.myStatus, MarriagePage.tsx:210/227 以 10 秒轮询 marriage.state。这些轮询进 onQuery 时 allowed == null, 第 112 行直接 `callback.failure(-3, ...)`。接收方 mock/useMockWorld.ts:61-76 的 useMockAction 在每次 reload 时先 `setState({status:'loading', data:null})`, 失败再 `setState({status:'error', data:null})` —— 上一次的好数据被丢弃。
- **影响**: 玩家在矿洞页或婚姻页按 ESC 关掉平板后, 后台每 3 秒 (婚姻 10 秒) 打一次注定失败的请求, 并把该页已有数据清成 null; 再次按 G 打开时, 看到的是错误态而不是上次的内容, 要等下一次轮询成功 (最长 3-10 秒) 才恢复。这是每个玩家每次开关平板都会撞上的路径。更糟的是这条失败用的错误码是 -3, 而 webui/src/lib/bridge.ts:273 把 -3 的含义写死为"页面被塞进了 iframe 或改过 location", 玩家反馈与运维排障会被引向一个根本不存在的安全问题。
- **建议**: 把"界面是否显示"和"页面是否可信"两件事拆开: 授权应该跟随宿主加载了哪个页面 (setAllowedPage 登记的那个 URL), 而不是跟随 Screen 的开关; 关屏时改为通知页面进入隐藏态 (或对浏览器调 hidden/暂停) 让前端主动停轮询, 而不是让请求继续发出去再被拒。若确实要保留"关屏即拒"这条防线, 至少要给它一个与 -3 区分开的失败码, 否则两种完全不同的原因共用一个诊断结论。
- **复核**: 维持 — 机制链条逐环读码属实, 但后果被高估了一档, 故降为 Minor。属实部分: WebUiBridge.java:231-236 onScreenClosed 第一句就是 `allowedPageUrl = null`; WebUiScreen.java:206-217 的 cleanup 只做 setFocus(false)+input.reset()+WebUiClient.onScreenClosed(), 注释明写"不在此 close 浏览器"; 我对 client 包 grep `.close()` 只得到 WebBrowser.java:200 这一处自调, 确认全库无外部关浏览器的调用点, SPA 原样存活; WebUiClient.java:147-150 对正式前端 forceReload=false 且 URL 未变时跳过 loadURL, 所以 React 树也不会重挂。轮询不看界面开关: use-live-updates.ts:61-71 的 usePolling 只要组件挂载就 setInterval, MiningPage.tsx:181 (3s) 与 MarriagePage.tsx:206 (10s) 直接挂上去。数据确实被清: mock/useMockWorld.ts:63 每次尝试先 `setState({status:'loading', data:null})`, 71-76 失败再 `setState({status:'error', data:null})`, 旧数据丢弃; MiningPage.tsx:328-331 于是渲染 ErrorBlock, 文案经 errorText.ts:156-161 对 business===null 直接回 error.message, 即宿主那句英文 "WebUI query rejected: untrusted page or subframe"。我还额外确认 JS 在关屏后确实继续跑: mcef.refmap.json 里 CefRenderUpdateMixin 注入的是 GameRenderer.m_109093_ (render), 与当前是哪个 Screen 无关, CEF 消息循环每帧照泵。降级理由: 这些失败在 WebUiBridge.java:110-113 就地被拒, 根本走不到第 148 行的 sendToServer, 服务端零负载, 不存在性能面后果; 且重开界面时 WebUiClient.java:133 会先 setAllowedPage 重新授权, 下一次轮询 (3s/10s) 即自愈, ErrorBlock 本身还带 onRetry 手动重试。即"高频但短暂且自愈的本地 UX 退化 + 一个会误导排障的错误码复用", 够不上 Major。审计员建议里真正值钱的是给"关屏即拒"单独一个失败码, 别与 -3 (bridge.ts:273 已写死为 iframe/改 location) 混用。


#### F044 · 关屏时在途请求被整批丢弃而不是逐个回失败, 已在服务端落账的动作对玩家表现为 35 秒后的一句失败

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: WebUI 服务端与网络基建 (webui / network / client / menu / registry)
- **位置**: `src/main/java/com/miningdim/client/webui/WebUiBridge.java:233`
- **证据**: onScreenClosed 第 233-234 行 `pending.clear(); requestIdByCefQueryId.clear();` —— 只是丢弃 PendingQuery, 没有对其中任何一个 CefQueryCallback 调 success/failure。随后到期的 expireRequest (第 238-243 行) 走 removePending 拿到 null, 直接什么都不做。webui/src/bridge/query.ts:12-21 的注释已逐字记录了这条路径并为此加了 35 秒看门狗, 明写"宿主侧的正解是关屏时逐个 failure, 但那属于 Java 客户端模块"。
- **影响**: 玩家点了 market.buy / case.open 这类会真扣钱的按钮后立刻按 ESC, 服务端照常完成扣款并发回执, 但客户端桥已经把 callback 丢了, 回执被 onResponse 静默 drop; 前端要等 35 秒才由看门狗抛出一句"宿主既未回成功也未回失败"。玩家看到的是一次失败, 实际钱已经扣了、货已经到了 —— 最可能的反应是再买一次。
- **建议**: onScreenClosed 改为遍历 pending 逐个 `callback.failure(<专用码>, ...)` 后再 clear, 让"界面被关掉了"变成一个立即、明确、可与真实业务失败区分的失败, 前端的 35 秒看门狗随之退回真正的兜底位置。
- **复核**: 未复核 (原报 Minor)


#### F045 · duplicate_request 拒绝走通用 errorJson, 没有 errorCode, 玩家会直接看到英文机器串

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: WebUI 服务端与网络基建 (webui / network / client / menu / registry)
- **位置**: `src/main/java/com/miningdim/webui/server/WebUiServerDispatcher.java:108`
- **证据**: dispatchAndRespond 第 107-110 行判重命中后 `respond(sender, requestId, false, errorJson("duplicate_request"))`, 而 errorJson (第 209-213 行) 只写一个 `{"error":...}` 键。WebUiErrorCodes 全表 (含专为 Gateway 收口而设的 RESPONSE_TOO_LARGE, 第 112 行) 里没有 duplicate_request。前端 lib/bridge.ts:324-327 对没有 errorCode 的回执明确按"通用异常路径"处理并原样把 message 交给文案层。
- **影响**: 防重放这条红线一旦真的触发 (客户端与服务端 requestId 视角失步、或将来出现第二个桥实例), 玩家界面上出现的是没有中文文案的 `duplicate_request` 英文串, 前端的错误码字典无从翻译, 也无法据此做"请勿重复提交"的专门提示。同一份 Gateway 里 RESPONSE_TOO_LARGE 已经按业务码规格下发, 两条同类拒绝形状不一致。
- **建议**: 把 duplicate_request 收编进 WebUiErrorCodes 并改用 WebUiBusinessException 抛出/下发, 与 RESPONSE_TOO_LARGE 同规格 (稳定码 + retrySameOpeningId), 前端加一条对应文案。
- **复核**: 未复核 (原报 Minor)


#### F046 · 纳米多重护盾不排除 bypasses_invulnerability, /kill 与虚空伤害会被免疫窗吃掉

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/engineer/effect/NanoShieldHandler.java:39`
- **证据**: onLivingHurt 只判 `event.getEntity() instanceof ServerPlayer` 与是否穿了插板/等离子盾, 随后遍历护甲槽命中免疫窗即 `event.setAmount(0.0f)`, 全程不看 DamageSource。对照同包的 PlasmaShieldHandler.java:58-59, 它显式排除了 `DamageTypeTags.BYPASSES_INVULNERABILITY` 与自定义 BYPASSES_PLASMA_SHIELD 标签。
- **影响**: /kill 用的 GENERIC_KILL 属 bypasses_invulnerability, 但它照样走 LivingEntity.actuallyHurt -> LivingHurtEvent; 被置 0 后 actuallyHurt 直接 return, 玩家血量不动 —— 管理员对一个带 armed 纳米护盾甲的玩家执行 /kill 会静默失败, 封禁/清理流程被挡。虚空伤害同理: 玩家掉出世界底部时每次伤害各吃掉一次 2 秒免疫窗, 行为诡异且日志无痕。
- **建议**: 照抄 PlasmaShieldHandler 的排除条件, 至少排掉 BYPASSES_INVULNERABILITY, 并考虑给纳米盾也建一个 bypasses 标签供运营挂载。
- **复核**: 维持 — 代码事实成立但后果被审计员放大了。核实: NanoShieldHandler.java:29-52 全程不读 event.getSource(), 只判 ServerPlayer 与是否穿插板/等离子盾, 命中即 setAmount(0.0f); 同包 PlasmaShieldHandler.java:55-61 确实显式排除了 DamageTypeTags.BYPASSES_INVULNERABILITY 与自建 BYPASSES_PLASMA_SHIELD (39-41 行定义), 兄弟不一致属实。机制上 LivingEntity.actuallyHurt 在 ForgeHooks.onLivingHurt 返回 <=0 时直接 return, /kill 的 genericKill 会被吞掉, 这一环也对。但推翻其影响描述的两点: (1) 每吞一次都要烧一格充能 (NanoEffects.java:143-156 tryReactiveShield 无条件 charges-1), 上限是 4 件 x EngineerConfig.java:220 maxCharges=5 = 20 次, 管理员连发 /kill 二十余次必然生效, 不构成封禁/清理流程被挡, 只是运营侧的恼人退化; (2) 整个 handler 在穿着插板或等离子盾胸甲时 (NanoShieldHandler.java:35) 完全不生效, 适用面比描述窄。虚空伤害同理 —— 充能耗尽后照常死亡。是真实的一致性缺陷、修法一行 (照抄 PlasmaShieldHandler 的排除条件), 但够不上 Major, 降 Minor。


#### F047 · 纳米护盾充能按件独立且无跨件安全阀, 一整套甲 = 20 次共 40 秒全免疫 (兄弟特效都有阀门, 唯独它没有)

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/engineer/effect/NanoEffects.java:143`
- **证据**: tryReactiveShield 只看单件的 NanoNbt.shieldCharges/shieldRegenTick, 消耗该件一次充能开 SHIELD_IMMUNITY_TICKS 的窗; NanoShieldHandler.java:46-51 对 `player.getInventory().armor` 四件逐件尝试, 命中一件即 return。充能上限 SHIELD_MAX_CHARGES 默认 5、再生间隔 1200 tick、免疫窗 40 tick (EngineerConfig.java:218-220), 且 advanceShieldTimers (NanoEffects.java:103) 也是逐件推进再生。同类特效都有跨件收敛: 机能修复有 DECAY_VALVE {1.0,0.5,0.25,0.125} (NanoEffects.java:24), 图腾有人级共享 CD (NanoReactorHandler.java:59); 护盾两者皆无。
- **影响**: 四件套 = 4×5 = 20 次"受击即 2 秒全伤害免疫", 战斗外还按每件 60 秒各回一枚 (全套 4 枚/分钟)。在 80 血 + TACZ 高 DPS 的枪战里, 这相当于一名生产职业玩家白得 40 秒硬免疫外加持续再生, 正是职业设计哲学要防的战力叠叠乐; 而且它是免疫而非减伤, 连上一条的 85% 帽都不经过。
- **建议**: 把充能与免疫窗收敛到人级 (像图腾那样共享), 或给多件叠穿套一条与机能修复同形的递减/共享阀; 免疫窗触发也应有人级 CD, 而不是四件各跑一套。
- **复核**: 维持 — 代码事实逐条属实, 但它是规格拍板的设计而非实现失误, 故降级保留。核实: NanoEffects.java:143-156 tryReactiveShield 只读单件 NBT 充能; NanoShieldHandler.java:39-51 对 player.getInventory().armor 四件逐件尝试; advanceShieldTimers:103-130 也逐件推进再生; EngineerConfig.java:218-220 immunityTicks=40 / regenIntervalTicks=1200 / maxCharges=5, 四件套 = 20 次 x 2 秒 + 4 枚/分钟再生, 算术无误。兄弟阀门对照也属实: NanoEffects.java:24 DECAY_VALVE {1.0,0.5,0.25,0.125}, NanoReactorHandler.java:58-61 人级共享 CD。推翻其唯一性指控的证据: docs/MillenniumEngineer_Mod_DesignSpec.md:146 明写 按件独立充能, 149 行更给了分类原则 —— 作用于护甲自身的效果 (重塑、护盾) 按件生效安全; 作用于玩家本体的效果 (机能修复、图腾) 按件会线性放大成无敌/多命, 故机能修复加递减、图腾共享 CD。也就是说没有阀门是规格显式裁决的, 代码 100% 忠实实现, 不是漏做。不过审计员的实质担忧仍站得住: 全伤害免疫窗按 149 行自己的判据分明是作用于玩家本体, 规格把它归到护甲自身这一类存在内部矛盾, 在 80 血 + TACZ 高 DPS + 生产职业哲学的前提下 20 次硬免疫值得回炉。定性为设计复议项而非代码缺陷, 降 Minor。


#### F048 · 枪匠冲压+装配整条链零职业等级门、零经济 sink, 与弹药线的等级曲线和工费 sink 完全脱节

- **维度**: 功能缺口 | **严重度**: Major -> Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/GunsmithPressBlockEntity.java:164`
- **证据**: 对 job/munitions/gunsmith/** 与 block/Gunsmith* 全量 grep `MunitionsLevels|JobServices|JobId.MUNITIONS|EconomyServices|tryCharge`: 零命中。具体表现: trySelectQuality(int index) 只判 `isPressing()` 就 `selectedQuality = GunsmithPartQuality.byIndex(index)`, 任何 1 级玩家都能直接选 LEGENDARY (系数 1.36-1.50, GunsmithPartQuality.java:10); tryStartPreview (line 186) 只校验材料与输出槽; GunsmithAssemblyBenchBlockEntity.tryStartAssembly (line 135-193) 只校验 GUNSMITH_ENABLED/图纸/零件。对照弹药线: MunitionsBenchBlockEntity 每批都过 isCaliberUnlocked 等级门 (line 294) 并经 tryChargeWorkFee 扣 1.5 CP/发 (line 504, 九章"弹药链唯一信用点 sink")。
- **影响**: 枪匠链一旦开启 (GUNSMITH_ENABLED), 军火商的 1-10 级曲线对造枪毫无意义 —— 新号只要拿到材料就能直接冲传奇零件装出满配枪; 同时整条造枪链一分钱 sink 都没有, 是纯粹把矿物转成高价值战斗成品的净产出通道, 与经济总表里"工费销毁是唯一 sink"的口径冲突。MunitionsConfig.java:194 的启用前置清单里只列了材料校验/归属门控/原子结算/破坏掉落/测试, 没写等级门与 sink, 意味着这两条大概率会被漏在开关翻开的那天。
- **建议**: 给冲压品质与图纸装配各补一道 MunitionsLevels 等级门 (品质档位对齐口径解锁曲线), 并在冲压/装配完成帧接 EconomyServices.tryCharge 走与弹药同源的工费 sink; 把这两条加进 GUNSMITH_ENABLED 的启用前置清单。
- **复核**: 维持 — 缺口客观存在但当前不可达, 且启用清单并非全无提及, 故降级。事实核对: 对 job/munitions/gunsmith/** 全量 grep MunitionsLevels|JobServices|JobId|EconomyServices|tryCharge|Currency 零命中, 对 block/Gunsmith*BlockEntity.java 同样只命中一处 GUNSMITH_ENABLED; GunsmithPressBlockEntity.java:164-171 trySelectQuality(int) 确实只判 isPressing 就直接落 selectedQuality; tryStartPreview:186-208 只校验 supports/输出槽/材料; GunsmithAssemblyBenchBlockEntity.java:135-193 除 143 行的 GUNSMITH_ENABLED 外只校验图纸与零件。对照组也对: MunitionsBenchBlockEntity.java:294 有 isCaliberUnlocked 等级门, :504 与 :558-563 走 tryChargeWorkFee -> EconomyServices.tryCharge 的 CREDIT sink。但两处减轻: (1) 生产不可达 —— MunitionsConfig.java:196 gunsmithEnabled 默认 false, GunsmithPressBlock.java:71 在 use 里直接拒绝开界面 (菜单是 BE 那些 trySelect* 的唯一入口), 装配侧 143 行同样拒绝; 我另核了 MunitionsWebUiActions 对冲压/装配只有 pressJson/assemblyJson 只读投影, 没有写动作可绕过方块门。(2) 审计员对启用清单的描述不准: MunitionsConfig.java:194-196 的 comment 原文是 keep false until material items, survival chain, gating and damage coefficients pass review, 其中 gating 已覆盖门控一项; 真正没被任何清单写到的只有经济 sink 这一条 (GunsmithPressBlock.java:70 的清单为 材料校验/归属门控/加伤评审)。结论: 属默认关闭的 WIP 功能开关翻开前必须补齐的待办 (尤其是 sink 未进任何清单), 不是在役缺陷, 降 Minor。


#### F049 · 被动挂机产线的 active 判据恒为 false, 离线补产期间方块永远不亮也不响

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:594`
- **证据**: `private boolean canAccumulateProduction() { return craftingActive && craftingCaliber != null; }`。它只在 settleForOwner 里被用 (line 414 `boolean active = canAccumulateProduction();`, line 420, line 450 `setMachineActive(canAccumulateProduction());`), 而 settleForOwner 的第一句 `if (settleManualCraft(owner)) return;` 保证走到这里时 craftingActive 必为 false (settleManualCraft line 463 对 craftingActive 为真的情形一律 return true 接管)。
- **影响**: 被动产线正常出弹的整个过程中, setMachineActive 恒收到 false, MunitionsBenchBlock.ACTIVE 永不置位 -> 无火花/烟/火焰粒子 (Block.animateTick line 202 直接 return), playWeldSoundIfActive 也永不发声。玩家看到台子出弹却一直是熄火状态, 会误判"台子坏了"。
- **建议**: 被动路径的 active 应由"本次 settle 是否真的产出/是否具备继续产的条件 (料足 + 缓冲未满 + 已选口径)"决定, 而不是复用只描述手动开工的 craftingActive。
- **复核**: 未复核 (原报 Minor)


#### F050 · 生产台经验待结算位清在快照副本上, "结算即清防塞回刷"的承诺实际不成立

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/engineer/block/ProductionTableBlockEntity.java:350`
- **证据**: onOutputTaken 末尾 `NanoNbt.clearProductionXpPending(boardSnapshot);`, 而 boardSnapshot 由 ProductionTableMenu.OutputSlot.onTake (menu/ProductionTableMenu.java:154) 传入 `this.takeSnapshot` —— 该字段在 getItem() 里由 `this.takeSnapshot = current.copy()` (line 143) 生成, 是一份副本。
- **影响**: 玩家实际拿到手的那块护甲板 NBT 里 productionXpPending 仍为 true, 该方法注释与类注释宣称的"结算即清 (无论是否匹配, 防塞回刷)"没有生效。目前之所以没被利用, 只是因为输出槽 mayPlace=false、capability 也只暴露输入槽 [0,1), 全库没有任何能把板重新塞回输出槽的入口 —— 这是运气而非设计, 以后任何一个新增的板容器 (比如仓储/自动化接口) 都会立刻把它变成刷经验通道。
- **建议**: 清 pending 应作用在真正离槽的那个 ItemStack 上 (在 remove/onTake 里对返回的实栈操作), 而不是计量用的快照副本。
- **复核**: 未复核 (原报 Minor)


#### F051 · 反漏斗包装用的 RangedWrapper 是可读可写的, 漏斗能把料槽里的材料反抽走

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/block/MunitionsBenchBlockEntity.java:148`
- **证据**: `LazyOptional.of(() -> new RangedWrapper(inventory, SLOT_PRIMER, INPUT_SLOT_END))`, getCapability (line 710-715) 对任何 side 都返回它。类注释 line 60 与 line 147 两处都写作"只写包装"。RangedWrapper 同时代理 insertItem 与 extractItem, 并不只写。engineer 的 ProductionTableBlockEntity.java:108 是同一写法同一措辞。
- **影响**: 玩家在军火台/生产台下方放漏斗 (常见的"接产物"直觉布局) 时, 漏斗抽不到输出槽 (这部分设计生效了), 却会把底火/弹壳/弹头/发射药源源不断抽走, 产线静默停摆且玩家找不到原因。设计文档里"必须人手取"的前提只挡住了输出, 没挡住输入被反抽。
- **建议**: 换成只实现 insertItem 的只写包装 (extractItem 恒返 EMPTY), 或按 side 区分; 顺手把两处类注释里"只写"的描述与实现对齐。
- **复核**: 未复核 (原报 Minor)


#### F052 · isBlueprint 这个布尔谓词会抛异常, 裸 NBT 图纸进槽会在容器点击/面板刷新路径上炸

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/gunsmith/GunsmithAssemblyRecipe.java:18`
- **证据**: isBlueprint 在确认是 gunsmith_blueprint 物品后调 `GunsmithBlueprintItem.requireBlueprint(stack)` 并只在其不抛时返 true; requireBlueprint (GunsmithBlueprintItem.java:45-67) 对无 NBT / 无 GunId / 未知 gunId 一律 throw IllegalArgumentException。该谓词被用在 GunsmithAssemblyBenchBlockEntity 的 inventory.isItemValid (block/GunsmithAssemblyBenchBlockEntity.java:76,81) —— 即服务端 SlotItemHandler.mayPlace 路径 —— 以及 MunitionsWebUiActions.assemblyJson (MunitionsWebUiActions.java:265)。
- **影响**: 一件 /give 出来的裸 gunsmith_blueprint (创造栏发的都带 NBT, 所以现实里只有 op 或 NBT 编辑能造) 一旦被拖进图纸槽, 异常从容器点击处理里冒出来; 同一件东西留在装配台里还会让附近所有玩家的平板 job.munitions.state 刷新失败。同类的渲染路径 (getName/appendHoverText) 早已按审查 GS-2 加了 tryBlueprint 兜底, 这条判定路径没有。
- **建议**: isBlueprint 作为谓词就该返回 false 而不是抛 (内部用 tryBlueprint), 把"必须是合法图纸"的硬校验留在 blueprint()/assemble() 这些真正要用图纸内容的地方。
- **复核**: 未复核 (原报 Minor)


#### F053 · 平板军火商页每次刷新在主线程扫 81 个区块的全部 BlockEntity

- **维度**: 性能 | **严重度**: Minor | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/munitions/MunitionsWebUiActions.java:389`
- **证据**: NearbyStations.around 以玩家所在区块为心做 `for dx in [-4,4] for dz in [-4,4]` 共 81 次 getChunkNow, 对每个已加载区块 `chunk.getBlockEntities().values()` 全量遍历并逐个 instanceof (line 395-396, 403-423)。类注释自己说明"面板刷新是玩家每隔几秒就会触发一次的读操作"。
- **影响**: 科技/仓储基地里单区块 BE 上千是常态, 81 区块合计可达数万个 BE, 每个玩家每几秒在服务端主线程跑一遍全量 instanceof 链。满编公服上是可累积的主线程抖动, 且开销与"玩家基地建得多复杂"正相关 —— 越是重度玩家越卡。目前没有任何按玩家的刷新节流。
- **建议**: 给两台无归属机台的 16 格半径先做区块级预筛 (只有 3x3 区块可能落在 16 格内, 现在却按 9x9 扫), 或对结果做短期缓存/按玩家节流; 长远解法是补 BE 归属字段 + 玩家->台位注册表, 把就近扫描整条去掉。
- **复核**: 未复核 (原报 Minor)


#### F054 · 首页进出矿洞后"当前所在矿洞"卡片永久停在旧状态 —— 代码注释依赖的 revision 触发链在 mining.* 上根本不成立

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/pages/HomePage.tsx:1039`
- **证据**: HomePage.tsx:1038-1041 的注释写着"进入/离开矿洞。两条都是写操作, 成功后由 mutateWorld 冒出的 revision 变化触发上面那条重查, 因此这里不再手动 reload", enterMining/leaveMining (:1046-1087) 据此只 pushToast 不调 reload。但 mock/handlers.ts 的两张镜像表 MIRROR_AFTER_WALLET_INVENTORY (:110-125) 与 MIRROR_AFTER_CASE (:126) **都不含 mining.enter / mining.leave**, 因此 delegateReal 不会调 refreshWalletAndInventory/refreshCaseTotals, 也就不会 mutateWorld, revision 不变, HomePage.tsx:1030-1036 的重查 effect 不触发。全库 mutateWorld 的调用点只有 handlers.ts:135/146、TabletShell.tsx:325 (isMockActive 包住) 与 bridge.mock.ts:4652 (dev-only) —— 生产路径上没有一处会因 mining.* 而 bump revision。首页也没有挂 usePolling (对比 MiningPage.tsx:181 挂了 mining.myStatus 的 3 秒轮询)。
- **影响**: 玩家在首页点"进入矿洞", 服务端受理并在若干 tick 后完成传送, 但首页"当前所在矿洞"面板会一直显示"不在任何矿洞"直到玩家离开首页再回来; 点"离开矿洞"后 toast 说"已离开矿洞", 面板却仍然显示在矿洞里, 且"进入"按钮保持可点 —— 再点一次只会拿到 ALLREADY_INSIDE 的红色回执。玩家会以为入场失败而反复点击。
- **建议**: 两条路二选一: enterMining/leaveMining 成功后显式调 reloadMiningStatus()/reloadMiningOverview() (与 MiningPage.handleEnter 的做法一致), 或把首页的矿洞卡片也纳入 POLL_INTERVAL_MS.miningStatus 轮询。同时把那段与事实不符的注释改掉, 它会诱导后来人再犯。
- **复核**: 维持 — 机制属实。HomePage.tsx:1038-1045 的注释确实声称 mining.enter/leave 成功后由 mutateWorld 的 revision 变化触发 :1030-1036 的重查, 而 :1046-1087 据此只 pushToast 不 reload; 但 mock/handlers.ts:110-126 的两张镜像表都不含 mining.enter / mining.leave, delegateReal (:209-213) 因此一次 mutateWorld 都不发。全库 mutateWorld 调用点经 grep 只有 handlers.ts:135/146、TabletShell.tsx:325 (isMockActive 包住) 与 bridge.mock.ts:4652 (dev), 生产路径上确实没有一处因 mining.* 而 bump revision; HomePage 也确实没挂 usePolling (对比 MiningPage.tsx:181 挂了 3 秒 mining.myStatus 轮询)。卡片按 MiningSection (HomePage.tsx:542) 的 myStatus.inside 分支渲染, 陈旧即一直停在旧分支, "进入矿洞"按钮 (:566) 保持可点。注释与事实不符这一点也确认。  降级理由 (两条实际缓和): 一是 HomePage.tsx:1156 有一枚 reloadAll 的"全部重载"按钮, 玩家点一下卡片就回正, 状态不是不可恢复; 二是入场那条 toast (:1051) 本身就写了"入场请求已受理 ... 到矿洞面板可以看到是否真进去了", 已经把"这里看不到终局"讲明白了。真正误导的只剩离开那条 (:1076-1079 说"已离开矿洞"而卡片仍显示在矿洞里)。重复点"进入"的代价只是拿到 ALREADY_INSIDE 的红色回执, 无资金/数据后果。故降 Minor, 但注释必须改 —— 它会诱导后来人照抄这条不存在的触发链。


#### F055 · 原版物品图标运行期依赖公网第三方镜像站 CDN, 无超时无本地回退, 与同仓库"禁用外链字体 CDN"的决策自相矛盾

- **维度**: 兼容性 | **严重度**: Major -> Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/components/ItemIcon.tsx:20`
- **证据**: `const VANILLA_ASSET_ROOT = 'https://assets.mcasset.cloud/1.20.1/assets/minecraft/'` (ItemIcon.tsx:20)。取图链对每个原版 itemId 依次执行 `probeImage(textures/item/X.png)` → `probeImage(textures/block/X.png)` → `resolveByModelChain` (最多 4 跳 `fetch(models/X.json)`, 每跳命中后再 probe 一次), 见 :188-232。probeImage 用裸 `new Image()` 且**无超时** (:100-111), fetchVanillaModel 用裸 fetch 且**无 AbortSignal** (:122-142)。结果缓存 textureUrlCache 是模块级 Map (:49), 页面一重载就清空。同一仓库的 styles/index.css:79-83 明确写着"字体栈只用系统字体, 刻意不引 Web Font。渲染目标是 MCEF 内嵌 Chromium, 页面由局域网内的服务端 serve; 外链字体 CDN 在国内网络与离线服上都会先卡住整屏文字再回退, 不值这个风险"。
- **影响**: 国内网络或纯离线局域网服上, 所有原版物品图标 (市场挂单行、背包格、分类树、开箱、流水、管理后台) 都会先各自挂起若干秒的探测请求再退化成灰色棋盘格占位块; 玩家看到的是"图标全没了", 与 A13/J1 记录的"第三方 mod 贴图缺口"症状完全一样, 无法区分。叠加上一条 finding (分类树数千个 ItemIcon 同时挂载), 单次打开市场页就会向一个不受本项目控制的公网域名排起数千个无超时请求, 占满 MCEF 的连接池并拖垮同进程的游戏客户端。docs/WebUI_Frontend_Wiring_Checklist.md:129 把这条记作 A13/J1"待定", 但代码已经把这一分支上线了。
- **建议**: 要么按 J1 拍板走服务端端点从 jar 抽贴图 (与本 mod 贴图同一条 /mc/ 管线), 要么至少给 probeImage/fetchVanillaModel 加超时 + 全局失败熔断 (连续 N 次失败后直接落占位块, 不再发请求), 并把 textureUrlCache 落进 localStorage 以免每次页面加载重跑整条链。在 J1 定案前, 至少不要在游戏内的默认首屏路径上批量触发它。
- **复核**: 维持 — 代码事实全部属实: ItemIcon.tsx:20 VANILLA_ASSET_ROOT 指向 https://assets.mcasset.cloud/1.20.1/...; probeImage (:100-111) 裸 new Image() 无超时; fetchVanillaModel (:122-142) 裸 fetch 无 AbortSignal; resolveTexture (:222-231) 对每个原版 id 先探 item 再探 block 再走最多 4 跳模型链; textureUrlCache (:49) 是模块级 Map 不落 localStorage。styles/index.css:79-83 的"刻意不引 Web Font"理由也逐字属实。  但降级到 Minor, 三条理由: 1. 这不是新引入的未记录分支, 而是在册的既有决策。docs/WebUI_Frontend_Wiring_Checklist.md:129 (A13) 明写"(1) 原版物品: 旧前端已实现四级回退链打第三方镜像站 assets.mcasset.cloud, 可用但引入外部服务依赖", 而 J1 (:334) 待定的范围被明确限定为"**本条只关乎第三方 mod**" —— 原版走镜像站是已拍板可用、待优化的形态, 不是偷偷上线的分支。 2. 与字体 CDN 的类比不成立: 外链字体会阻塞整屏文字渲染 (FOIT), 而图标失败是非阻塞的, 降级路径明确 (ItemIcon.tsx:382-391 落中性棋盘格占位块, 且 :297 的注释解释了刻意不用原版品红以免把"待接线"读成"报错")。 3. 放大论证被推翻: "分类树数千个 ItemIcon 同时挂载"这个前提不成立 —— market.categories 的回执恒超 WebUiServerDispatcher.java:148 的 32767 下行上限而被换成失败回执, 分类树在真服上根本渲染不出任何叶子 (详见第 1 条 finding 的复核)。同时 lib/i18n.ts:20-26 记录的"MCEF 浏览器常驻、同 URL 不 loadURL"反而使模块级 textureUrlCache 跨"关平板再打开"存活, "每次页面加载重跑整条链"也不成立。 剩下真正值得修的是无超时 + 无失败熔断这两点 (离线局域网服上 DNS/TCP 挂起会让占位块延后若干秒才出现), 按 Minor 排期即可。


#### F056 · 启动握手自检 handshake() 实现完整但零调用点, 契约漂移没有任何可观测信号

- **维度**: 功能缺口 | **严重度**: Major -> Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/lib/bridge.ts:481`
- **证据**: lib/bridge.ts:464-505 完整实现了 HandshakeReport 与 `export async function handshake()`, 做 SERVER_ACTIONS 与服务端 registeredActions 的双向 diff 并给出 compatible 判定。lib/actions.ts 的文件头 (:2-9) 写着"供 bridge 握手自检比对 (架构文档 10.6): 页面启动时调一次 system.handshake"。但全库 grep `handshake(` 只有定义处与注释, App.tsx / main.tsx / TabletShell.tsx 都没有调用点; SERVER_ACTIONS 这张 64 项手工表也因此从未被运行期核对过 (本次审计手工比对 Java 侧 `register("...")` 字面量, 当前 64 项一条不差, 但这只是此刻的巧合)。
- **影响**: MCEF 的浏览器是常驻的且刻意跳过同 URL 的 loadURL 以保住 SPA 状态 (lib/i18n.ts:20-26 记录), 页面又由远端服务端 serve —— mod 升级后前端很容易停在旧构建上。此时前端会去调服务端已经改名/删除的 action, 表现是各功能面板逐个弹出看不懂的 Java 异常原文, 运维与玩家都无从判断这是"版本不匹配"还是"某个子系统坏了"。架构文档 10.6 要解的正是这个场景, 代码写好了却没接上。
- **建议**: 在 App 挂载或 TabletShell 首屏调一次 handshake(), missingOnServer 非空时在外壳顶栏挂一条常驻警告 (列出缺失的 action 名与 modVersion); 不必整页拦截, 但必须让契约漂移在界面上可见。
- **复核**: 维持 — 事实属实。lib/bridge.ts:464-505 完整实现了 HandshakeReport 与 handshake(), 含运行期形状校验与双向 diff; lib/actions.ts:2 的文件头写着"供 bridge 握手自检比对 (架构文档 10.6): 页面启动时调一次 system.handshake"。对整个 webui 目录 (不只 src) grep "handshake" 后, 除定义处、契约表 (bridge.ts:160)、类型注释 (types.ts:104-122)、mock 分支 (bridge.mock.ts:4721) 与 actions.ts 的注释外, 零调用点; App.tsx 与 main.tsx 已逐行读完, 都没有调用。  降级到 Minor: 这是一条诊断链未接, 不是功能缺陷 —— 没有任何玩家可见功能因为它不被调用而坏掉, 当前 SERVER_ACTIONS 与服务端注册表也没有漂移。它的价值只在"漂移发生时把症状从一串 Java 异常原文变成一句版本不匹配", 属于可观测性欠账。真出现漂移时, 各面板本来也会各自弹出可见的失败回执 (不是静默), 故不构成"故障不可发现"。建议按 Minor 排期在 TabletShell 首屏补一次调用 + 顶栏常驻警告即可。


#### F057 · 镜像里的 wallet / myListings / caseOwnedTotal 三个字段全库零读取方, 却每次刷新都要为它们各打一条服务端请求 (开箱因此每次打两遍 case.state)

- **维度**: 性能 | **严重度**: Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/mock/handlers.ts:144`
- **证据**: 全库对 mirror 字段的读取只有: HomePage.tsx:1125-1128 读 refreshedAt, FarmerPanel.tsx:88/139、BrowsePage.tsx:606/642、SellPage.tsx:368 读 inventory。`mirror.wallet`、`mirror.myListings`、`mirror.caseOwnedTotal` 在 handlers.ts:136/138/147/148 之外**没有任何读取点**。而 refreshWalletAndInventory 仍然三条并发 (handlers.ts:130-134), 其中 market.mine 在服务端是一次 listingsBySeller 数据库查询 (MarketActions.java:173-185); refreshCaseTotals (handlers.ts:144-151) 整条只为写这两个无人读的字段 —— 且 CasePage.runOpen 在 callMock('case.open') 之后自己还会 `stateQuery.reload()` (CasePage.tsx:790), 于是每次开箱都发两遍 case.state。
- **影响**: 每次 market.place/buy/cancel、farmer.sell、tarot.buyPack、marriage.buyRing/wed/divorce、admin.economy.set 都白打 player.wallet + market.mine 两条 (后者是 DB 查询); 每次 case.open/case.apply 白打一条 case.state 并与页面自己的 reload 重复。平板打开时 primeRealDomainMirror 的四条里也有三条无消费方。这些都在服务器主线程上, 属于纯浪费的常态负载。
- **建议**: 把 refreshWalletAndInventory 收窄成只拉 player.inventory, refreshCaseTotals 整条删除 (由 CasePage 自己的 stateQuery.reload 负责), 同时删掉 store.ts 里三个无人读的镜像字段 —— 与已在册的 walletOverlay 清理一并做。
- **复核**: 未复核 (原报 Minor)


#### F058 · 婚姻页的求婚候选按 mock 种子玩家名去重, 生产环境下玩家自己会出现在候选下拉里

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/pages/MarriagePage.tsx:408`
- **证据**: `const proposeCandidates = [...new Set([...proposerNames, ...rosterNames])].filter((name) => name !== '' && name !== world.player.name)` (MarriagePage.tsx:407-409)。world.player.name 恒为 seed.ts:27 的 '测试员_Mock', 与真实玩家名无关 (同 AdminPage 那条 finding 的根因)。而 player.roster 服务端刻意不剔除调用者自己 (PlayerWebUiActions.java:89-99 遍历全部在线玩家, AdminPage.tsx:1112 的注释也明确说"自己也在名册里")。
- **影响**: 玩家打开婚姻页的求婚下拉时会在候选里看到自己的名字, 选中并点求婚会拿到一句服务端拒绝; 反过来, 如果某个真实玩家恰好叫 '测试员_Mock' (测试服上完全可能), 那个人会被静默从所有人的候选里剔除, 谁都没法向他求婚且没有任何提示。
- **建议**: 改用真契约里的自己 (player.profile 的 playerName, 或 marriage.state 回执里已有的自身标识) 做这次过滤, 不要读 mock 世界的 player.name。
- **复核**: 未复核 (原报 Minor)


#### F059 · mirror 的初值注释声明"全 null", 但 myListings 出厂就带三条署名为 mock 玩家的假挂单

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/mock/seed.ts:254`
- **证据**: store.ts:47-50 的 MockRealDomainMirror 契约写着"null = 本会话还没拉过, 面板据此显示骨架而不是显示 0", seed.ts:249-250 的行内注释也写着"全 null: 本会话还没拉过真域数据"。但紧接着的 :254 是 `myListings: seedMyListings(epoch)` —— seedMyListings (seed.ts:201-238) 返回三条 sellerName 为 '测试员_Mock' 的挂单 (88000 / 12500x2 / 34800 信用点)。这段代码随生产构建发布 (dist 产物中确认存在 '测试员_Mock')。
- **影响**: 当前 mirror.myListings 零读取方, 所以还没显形; 但契约已经破了 —— 任何后续接入这个字段的页面 (例如把"我的挂单"改成读镜像以省一次往返) 都会在真服上先画出三条根本不存在的挂单, 而作者会照着字段注释认定"null 才是未拉取", 排查方向直接被带偏。
- **建议**: 把 myListings 的初值改回 null (与其余三项一致), 假挂单如果还有设计预览价值就搬进 lib/bridge.mock 的 market.mine 假后端 —— 那才是真契约的唯一假数据源。
- **复核**: 未复核 (原报 Minor)


#### F060 · components/ui 下 36 个组件零引用, 但仍被 Tailwind 扫描进产物, 导致 dist CSS 达 222KB

- **维度**: 性能 | **严重度**: Minor | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/components/ui/drawer.tsx:1`
- **证据**: components/ui 共 60 个 .tsx / 7543 行, 逐个统计导入方后有 36 个零引用 (accordion / autocomplete / avatar / breadcrumb / calendar / checkbox-group / collapsible / combobox / command / context-menu / drawer / field / fieldset / form / frame / group / input-group / kbd / label / menu / meter / otp-field / pagination / popover / preview-card / radio-group / scroll-area / sheet / sidebar / slider / switch / textarea / toast / toggle / toggle-group / toolbar); hooks/use-media-query.ts 也只被其中的 sidebar 用到。Tailwind v4 按源码文本扫描生成工具类, 已在构建产物中验证: `grep -c 'drawer-swipe-progress' dist/assets/index-DpUQ7fla.css` 返回 1, 即只存在于零引用的 drawer.tsx 里的自定义属性确实进了最终 CSS。产物 CSS 221956 字节。
- **影响**: MCEF 每次加载页面都要解析 222KB CSS, 其中相当一部分规则没有任何元素会命中; 同时这 5000+ 行零引用组件构成持续的维护噪音 (升级 Base UI 时要跟着改, 却从来没人验过它们能不能跑)。
- **建议**: 删掉确实不打算用的那批组件文件 (Coss/shadcn 的组件是 copy-in 模式, 删除不影响其余); 若要保留作"随时可取"的备件, 就移出 src 或在 Tailwind 的 source 配置里排除该目录, 别让它们进产物。
- **复核**: 未复核 (原报 Minor)


#### F061 · mods.toml 对 tacz / champions 零依赖声明, 集成层却直连 TACZ 非 API 内部包, 升级前置只会在运行期炸而非加载期拒绝

- **维度**: 兼容性 | **严重度**: Major -> Minor | **审计域**: 横切专项: 兼容性 (存档/NBT 迁移、config 迁移、跨 mod API、双端 classload、数据包格式)
- **位置**: `src/main/resources/META-INF/mods.toml:39`
- **证据**: mods.toml 只声明了四个依赖: forge [47,)、minecraft [1.20.1,1.20.2)、mcef [2.1.6,)、farmersdelight [1.20.1-1.2.0,), 全文无 tacz 与 champions 条目 (grep modId 只有第 10/22/29/40/48 行五处)。而 build.gradle 第 90-91 行以 compileOnly 编译 tacz-1.20.1-1.1.8-hotfix.jar 与 champions-forge-1.20.1-2.1.10.2.jar。代码侧 import 已越过 api 包直连内部实现: GunsmithTaczBridge 第 6-8 行 import com.tacz.guns.resource.pojo.data.gun.{BulletData,ExtraDamage,GunData}, GunsmithTaczStatsHandler 第 10-15 行 import com.tacz.guns.resource.modifier.{AttachmentCacheProperty,AttachmentPropertyManager} 与 com.tacz.guns.resource.pojo.data.attachment.Modifier —— 这些是 resource 内部包, 不是 com.tacz.guns.api。运行期唯一的门是存在性判断: MunitionsSystem 第 78 行、EngineerSystem 第 76 行、CaseOpeningSystem 第 39 行、AgentSystem 第 58 行全部只调 ModList.get().isLoaded(...), 无任何版本比对。
- **影响**: 运维把 TACZ 或 Champions 升一个小版本 (这两个前置都在持续更新), isLoaded 依旧为 true, 于是 GunsmithTaczStatsHandler.register / PlateArmorTaczIntegrationBootstrap.assemble 照常装配, 内部包一旦改签名就在 mod setup 或首次开枪事件上抛 NoClassDefFoundError / NoSuchMethodError。专用服表现为启动崩或战斗中崩, 且因为没有版本区间, FML 不会在加载期给出'需要 tacz x.y.z'的可读拒绝, 运维只能对着堆栈猜。同一文件里 mcef 与 farmersdelight 都写了 versionRange, 说明该纪律存在, 只是这两个漏了。
- **建议**: 给 mods.toml 补 tacz 与 champions 两条 mandatory=false 的 dependencies 块, versionRange 按当前编译所用的 1.1.8 / 2.1.10.2 给出带上界的区间 (与 minecraft 那条 [1.20.1,1.20.2) 同思路), ordering=AFTER。同时把直连 com.tacz.guns.resource.* 内部包的引用收敛到一处薄壳里, 使将来只需改一个文件; 若上游确无等价 api 入口, 至少在该薄壳的类 javadoc 里标注锁定版本与升级须复验的清单。
- **复核**: 维持 — 事实全部核实为真, 但影响被高估, 降为 Minor。(1) 事实核对: mods.toml 全文 53 行, dependencies 块只有四条 —— forge (第 21-26 行)、minecraft (第 28-34 行)、mcef (第 39-44 行)、farmersdelight (第 47-52 行), 确无 tacz 与 champions。build.gradle 第 90-91 行确以 compileOnly files 引 libs/tacz-1.20.1-1.1.8-hotfix.jar 与 libs/champions-forge-1.20.1-2.1.10.2.jar (PowerShell 列 libs/ 三个 jar 均在盘)。内部包直连比审计员报的还广: 除 GunsmithTaczBridge 第 6-8 行、GunsmithTaczStatsHandler 第 10-15 行的 com.tacz.guns.resource.* 外, 还有 client/GunsmithTaczClientData.java 第 5-6 行的 com.tacz.guns.client.resource.*, 以及 Champions 侧 AgentSealExecutor.java 第 6 行 top.theillusivec4.champions.common.util.ChampionBuilder 与 AgentChampionData.java 第 6 行 top.theillusivec4.champions.common.capability.ChampionCapability —— 两个 mod 都越过了 api 包。全库 grep ArtifactVersion/VersionRange/getModContainerById 只在 WebUiServerSubsystem 第 102 行读本 mod 自己的版本, 集成层守卫 (CaseOpeningSystem:39、AgentSystem:58、EngineerSystem:76、MunitionsAmmoFactory:32) 确实全是裸 isLoaded, 无任何版本比对。(2) 影响重估: 按当前锁定的 1.1.8 / 2.1.10.2 jar, 线上无任何现存错误行为, 该条是纯加固建议而非活体缺陷; 触发条件是运维主动升级前置。(3) 审计员的论据有一处站不住: 它拿 mcef 与 farmersdelight '都写了 versionRange' 论证纪律已存在, 但第 42 行是 [2.1.6,)、第 50 行是 [1.20.1-1.2.0,), 两条都是无上界的下限区间, 换成同样写法根本挡不住它描述的 NoClassDefFoundError 场景 —— 真正能挡的是带上界的区间, 那是一次会把所有 TACZ 更新 (含兼容更新) 一律硬拒在启动期的策略变更, 属维护者的取舍而非既定纪律的遗漏。(4) 且带上界的结果是 '服务端拒绝启动' 而非 '服务端不崩', 收益主要在报错可读性/可诊断性, 而非避免停服; 唯一无争议的免费收益是 ordering=AFTER (GunsmithTaczStatsHandler.register 在 MunitionsSystem 第 75-80 行的 FMLCommonSetupEvent.enqueueWork 里读 TACZ 静态注册表 AttachmentPropertyManager.getModifiers() 并 requireNonNull, 缺 ordering 声明时 FML 无从保证相对加载序)。综上: 结论成立, 但既无当前失效也无游戏内后果, 仅为可诊断性与加载序元数据缺口, Major 过重, 降 Minor。


#### F062 · data/miningdim/affix_setting 下 35 个 JSON 声明的 champions: 类型在 Champions 注册表里全不存在, 恒为死数据

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 兼容性 (存档/NBT 迁移、config 迁移、跨 mod API、双端 classload、数据包格式)
- **位置**: `src/main/resources/data/miningdim/affix_setting/uhmwpe_armor.json:2`
- **证据**: 本仓 data/miningdim/affix_setting/ 有 35 个文件, 每个 type 都写成 champions:<本工程自研词条名> (如本文件第 2 行 "champions:uhmwpe_armor", 另有 blade_waltz / caesar_swap / counter_unit / tactical_blink 等)。而 libs/champions-forge-1.20.1-2.1.10.2.jar 里 data/champions/affix_setting/ 只有 16 个: adaptable, arctic, dampening, desecrating, enkindling, hasty, infested, knocking, lively, magnetic, molten, paralyzing, plagued, reflective, shielding, wounding —— 与这 35 个名字零交集。javap 反编译 top/theillusivec4/champions/api/AffixDataLoader.class 的 listResources 可见它以 ResourceManager.listResources("affix_setting", ...) 扫全部 namespace, 因此我方这 35 个确实被读入并按 AffixSetting.CODEC 解析成功 (codec 的 type 字段只是 ResourceLocation, 不校注册表); 但 SPacketSyncAffixSetting.lambda$handelSettingMainThread$1 是 Champions.API.getAffix(setting.type()) 后 Optional.ifPresent(...), 解析不到即无声跳过。另一侧 data/champions/affix_setting/ 下我方 16 个 enable:false 的覆盖文件格式与上游逐字段一致 (仅 enable 由 true 改 false), 那条禁用原版词条的链路是对的。champion 包本身已无任何 top.theillusivec4 import (grep '^import top.theillusivec4' 在 src/main/java/com/miningdim/champion/ 下零命中), 即自研星级词条系统不消费这 35 个文件。
- **影响**: 这 35 个文件对游戏行为零影响, 却每次数据包 reload 都被 Champions 解析一遍, 并随 SPacketSyncAffixSetting 的 unboundedMap 全量编码发给每个登录客户端, 白占握手包体。更实际的危害是误导: 后来者看到 tier.min/max 与 category 会以为星级门槛由这些 JSON 驱动, 改了却毫无效果, 排查成本远大于文件本身价值。
- **建议**: 确认自研词条系统不再需要 Champions 侧登记后, 整目录删除; 若保留是为了将来把 35 个词条真正注册进 Champions 的 AffixRegistry, 则必须先有注册代码 (IAffix 实现 + 注册事件), 并在目录里加 README 说明 type 命名空间为何借用 champions:。无论哪条路, 现状的'有文件无消费方'都应终结。
- **复核**: 未复核 (原报 Minor)


#### F063 · instance.regionSizeChunks / bufferChunks 两个 worldRestart 配置项无任何生产消费方, 改了不生效

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 兼容性 (存档/NBT 迁移、config 迁移、跨 mod API、双端 classload、数据包格式)
- **位置**: `src/main/java/com/miningdim/config/ModConfig.java:47`
- **证据**: MiningServerConfig 第 137-142 行以 .worldRestart().defineInRange("regionSizeChunks", 16, 4, 64) 与 defineInRange("bufferChunks", 1, 1, 8) 定义, 注释写着 'changing invalidates the existing grid'; ModConfig 第 47/52 行把它们暴露成 IMiningConfig.regionSizeChunks()/bufferChunks()。全仓 grep 这两个方法名, 除接口声明 (IMiningConfig 第 32/35 行)、本实现、以及 PressureGameTests 第 215-216 行两个 throw unused() 的桩之外, 零调用点。真正决定网格几何的是编译期常量: RegionGrid 第 35-36 行的无参构造直接取 MiningConstants.REGION_STRIDE_X/Z 与 REGION_ORIGIN_X/Z, 而 MiningConstants 第 53/56 行 REGION_SIZE_X=REGION_SIZE_Z=256、第 71 行 REGION_GAP=32、第 74-75 行 STRIDE=SIZE+GAP=288, 与 config 无任何联系。
- **影响**: 运维按注释把 regionSizeChunks 从 16 改成 32 并重启世界 (worldRestart 标记正是这么暗示的), 期望实例区域变大, 实际几何纹丝不动且无任何警告; 反过来若有人担心'改了会让现有网格失效'而不敢动, 那份担心也是假的。两个带范围校验、带 worldRestart 语义、带注释的配置项对运维撒谎。
- **建议**: 二选一: 要么让 RegionGrid 构造期从 IMiningConfig 读这两个值派生 strideX/Z (并在启动时把实际用值与 MiningSavedData 里已落盘的占用位图做一致性校验, 不一致就拒绝启动而不是错位复用槽位), 要么删掉这两个 config 键与 IMiningConfig 上的两个方法, 在 MiningConstants 处注明几何为编译期固定。保留现状是最差选项。
- **复核**: 未复核 (原报 Minor)


#### F064 · 婚姻共享背包黑名单不做容器下钻, 潜影盒整包即可绕过高级矿物/绑定装备闸(同仓 market 侧已做下钻)

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/marriage/SharedBackpackWhitelist.java:59`
- **证据**: SharedBackpackWhitelist.isAllowed(SharedBackpackWhitelist.java:59-70)只判顶层 stack: isBoundEquipment 读 stack.getTag() 的 OwnerUUID/SpouseUUID/MarriageId, isHighValueOre 比对 BLOCKED_ITEMS/BLOCKED_BLOCKS, isSkinCredential 比对注册名 —— 三条全部不看容器内容物。它是唯一执行点(MarriageBackpackContainer.java:113 canPlaceItem 直接转调)。对照同仓的 MarketTradeWhitelist.java:63/113 的 MAX_CONTAINER_DEPTH + judgeContents, 那边明确对 BlockEntityTag.Items 递归判一层, 类注释还专门写明"只看顶层的话 27 张 UR 塔罗塞进一个潜影盒整包就能过关"。
- **影响**: Marriage_System_DesignSpec 第四章的共享背包黑名单(高级矿物/皮肤凭证/绑定装备)与第七章闸位"共享背包 = 定向转移通道"被一个潜影盒直接绕开: 把钻石/绿宝石/下界合金锭或塔罗牌、绑定装备装进潜影盒放进共享背包, 就能在两个账号之间自由转移。结合经济侧"跨账号洗额度"的既有风险面, 这是一条现成的定向洗矿/互借神装通道。
- **建议**: 把 MarketTradeWhitelist 已经写好的容器下钻思路复用到 SharedBackpackWhitelist(同样只需一层深度, 同样对脏 NBT 走"当作没装东西"而不是抛)。两处规则最好共用一个下钻工具, 避免以后一边改一边忘。
- **复核**: 维持 — 代码事实完全属实, 但影响被审计员放大了一个数量级, 故降为 Minor。事实侧: SharedBackpackWhitelist.java:59-70 的 isAllowed 只判顶层 —— isBoundEquipment (line 73-79) 读 stack.getTag() 的三个键, isHighValueOre (line 82-88) 比对 BLOCKED_ITEMS/BLOCKED_BLOCKS, isSkinCredential (line 91-100) 比对注册名, 三条都不看内容物; 唯一执行点确实是 MarriageBackpackContainer.java:111-114 canPlaceItem 直转调; 对照 MarketTradeWhitelist.java:63 MAX_CONTAINER_DEPTH=1 与 line 113-131 judgeContents 对 BlockEntityTag.Items 递归判一层, 两边规则不对称, 这条修是应该修的。降级依据 (三条实测): (1) 全库 grep ItemTossEvent / PlayerDropsEvent / EntityItemPickupEvent 零命中 —— 本服对玩家丢物/捡物没有任何限制, 面对面给钻石本来就是零成本, 共享背包不是唯一定向转移通道; (2) MarketTradeWhitelist.java:81-102 的 judge 只对 TarotCardItem 生效, 其余一律 ALLOWED —— 钻石/下界合金/TACZ 皮肤凭证在 P2P 市场上本来就能自由挂单转手, 黑名单挡的那几类物品在别处根本没有对应闸位; (3) 绑定装备转过去也用不了: TarotPlayHandler.java:43-47 使用前校验 ownerUUID, TarotCraftService.java:73 与 TarotCraftMenu.java:174 合成前同样校验 crafter 与两张输入牌的 owner 一致 —— 拿到别人的牌既打不出也合不了。故真实增量风险只剩"配偶离线也能异步交接"这点便利, 不构成新的洗矿/互借神装通道。判 Minor (一致性缺陷, 应复用 market 那套下钻工具收口)。


#### F065 · 特勤战术扫描的原生面板三件套(menu/Screen/S2C/C2S)全部注册但零入口, 决策 J9 未落, 是占着网络 discriminator 的死代码

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/job/agent/panel/AgentScanMenu.java:41`
- **证据**: AgentScanMenu.Provider(AgentScanMenu.java:41)全工程从未被 new 过(grep 'AgentScanMenu.Provider' / 'new AgentScanMenu' 只命中类自身与注释), 全库 NetworkHooks.openScreen 的 20 处调用点里没有一处开这个 menu。MiningNetwork.java:120 的 sendAgentScan 零生产调用点。AgentScanSyncS2C 与 AgentSealRequestC2S 已在 MiningNetwork.java:82-93 占用两个 channel discriminator, 客户端 AgentScanScreen 也已在 AgentSystem.onClientSetup 注册。AgentWebUiActions.java:37 的类注释自陈"在此之前 AgentSealSeam.buildScanSnapshot 与 AgentScanMenu.Provider 全工程零调用点", docs/WebUI_Wiring_Execution_Scope.md 第八章决策 J9 仍挂着"特勤扫描的触发入口"未拍板。
- **影响**: 扫描/封印功能现在只经平板 WebUI 的 job.agent.scan / job.agent.seal 可达; 原生 Container 面板这一整条(MenuType 注册 + Screen 注册 + 两个网络包)是玩家永远打不开的死代码, 且两个 discriminator 与一个 MenuType 会一直占着位。将来若有人误以为这条路是活的而去修改它, 改完也无从验证。
- **建议**: 先把 J9 拍板: 如果统一入口确定走平板 hub, 就删掉原生 menu/Screen/两个包(顺带回收 discriminator, 但要注意 discriminator 是按注册顺序自增的, 删中间的会改变后面包的 id, 属于协议破坏, 要么删末尾要么保留占位); 如果要保留原生入口(键位/道具), 就把 openScreen 与 sendAgentScan 接上, 并让它和 WebUI 路径共用同一份 CD/快照账本, 不要各记一份。
- **复核**: 未复核 (原报 Minor)


#### F066 · 实体堆叠 FR-5(MUST)的拆分与拴绳语义完全没有实现, 玩家无法从堆叠里取出单只动物

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/stacking/StackingSystem.java:59`
- **证据**: StackingSystem.register(StackingSystem.java:59-63)只挂了三个 handler: StackDeath(FR-2)、StackPassive(FR-3)、StackBreed(FR-4)。整个 stacking 包对 'split' / 'lead' / 'LEASH' / 'SHEARS' 之外的分离交互 grep 零命中, 没有任何分离工具、分离交互或拴绳语义代码。StackData.incr(StackData.java:57)虽然支持负 delta, 但它的唯一负向调用方是 StackDeath.handleOnePerKill(StackDeath.java:108)。需求规格第五章 FR-5.1(MUST)要求"提供从堆叠中分离单个个体的手段, 分离后原堆叠数减 1", FR-5.2(MUST)要求"拴绳语义须固化: 默认作用于整堆或先拆出 1 个, 二选一写入配置"; StackingConfig.java 里也没有对应的配置键。
- **影响**: 默认 death_mode=INSTANT_ALL(StackingConfig.java:90)下, 玩家想要一只羊/牛去别处, 只有两条路: 把整堆(最多 64 只)赶过去, 或者一刀把整堆全杀掉。拴绳作用于堆叠实体时行为未定义(实际是牵走整堆而只算一根绳)。这对牧场类玩法是直接可感的功能缺失, 也是规格里两条 MUST 的缺口。
- **建议**: 补一个明确的分离交互(例如潜行右键空手从堆叠里剥 1 只并 spawn 出去, 复用 StackData.incr(-1) + StackMerge.applyLabel), 并在 StackingConfig 里补 FR-5.2 要求的拴绳语义开关(整堆 or 先拆 1)。剥离出的新实体要立刻带上 StackData=1, 否则下一轮扫描会马上被吸回去。
- **复核**: 未复核 (原报 Minor)


#### F067 · 工程师纳米多重护盾充能格数与末影心肺反应器共享 CD 无任何 HUD 与 S2C 状态包, 玩家看不见自己的救命资源

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/job/engineer/effect/NanoEffects.java:143`
- **证据**: 护盾充能与免疫窗全部只存在服务端读写的 ItemStack NBT 上(NanoEffects.java:103 advanceShieldTimers / :133 shieldWindowActive / :143 tryReactiveShield), 反应器 CD 存玩家 capability(NanoReactor.java:18 cooldownReady / :23 nextCdEndTick)。全库 RegisterGuiOverlaysEvent / IGuiOverlay 的注册只有等离子护盾那一套(PlasmaShieldClientEvents.java:19、PlasmaShieldHudOverlay.java:20、PlasmaShieldHitOverlay.java:15), 纳米特效侧没有任何 overlay, 也没有对应的 S2C 状态包(engineer 包下只有 shield/network 的 PlasmaShieldSyncS2C / PlasmaShieldHitS2C)。MillenniumEngineer_Mod_DesignSpec 6.4 第二批与十三章第三批第 11 项都明确要求"自定义 HUD 叠层(护盾剩余层数 / 图腾 CD 倒计时 / 生效特效图标)+ S2C 状态包"。
- **影响**: 在 80 血 + TACZ 高 DPS 的战斗环境里, 多重护盾最多 5 格、每 60 秒回 1 格, 心肺反应器是 30 分钟人级共享 CD 的一次免死 —— 这两个都是决定"要不要继续打"的资源, 而玩家完全看不到剩余量, 只能靠数秒。结果是要么当没有(白白浪费), 要么当还有(送死)。
- **建议**: 复用 PlasmaShieldSyncS2C 的现成范式: 服务端在充能/CD 变化时下发一个轻量状态包, 客户端一个 IGuiOverlay 画格数与 CD。注意别每 tick 发, 只在数值跨越整格/CD 就绪时发。
- **复核**: 未复核 (原报 Minor)


#### F068 · 婚礼"预约场地"(SavedData 登记坐标+时段, 典礼期间占用)未实现, 典礼退化为任意地点一条命令

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 横切专项: 功能缺口 (设计文档 vs 实现)
- **位置**: `src/main/java/com/miningdim/marriage/MarriageCommands.java:49`
- **证据**: MarriageCommands 的 wed 分支(MarriageCommands.java:49-51 注册, :106-134 实现)只校验"婚约意向已被接受", 随后直接调 MarriageEngine.wed; MarriageEngine.wed(MarriageEngine.java:96-175)的校验链是 自己不能跟自己结 -> 双方未婚 -> 再婚冷却 -> 双方各持订婚戒指 -> 事务扣费, 没有任何场地/位置判定。整个 marriage 包 grep 'venue' / '预约' / 场地相关标识零命中, 19 个文件里没有场地 SavedData。Marriage_System_DesignSpec 第三章(DECIDED)明确写"预约场地: SavedData 登记场地坐标 + 时段, 典礼期间占用", 第二章流程图也把"预约场地"排在"双方到场办典礼"之前。
- **影响**: 文档设计的"买戒指 -> 预约场地 -> 到场办典礼"三步仪式感缩成两步, 玩家在任何地方(包括矿洞里)输一条命令就能结婚, 典礼这件本来要制造全服叙事的事失去了公共场所这一半; 同时也少了一道"必须到主城指定地点"的轻量反小号成本。
- **建议**: 若这条仍要做, 按文档建一个场地 SavedData(坐标 + 时段 + 占用标记), 让 wed 校验"双方都在已预约且当前生效的场地范围内"; 若决定不做, 把文档第三章那句改成 SUPERSEDED 并说明理由, 别让规格与代码长期对不上。
- **复核**: 未复核 (原报 Minor)


#### F069 · 塔罗"不可被索敌"期间每 tick 做一次 128³ 的 Mob AABB 扫描, 持续 160 tick 无任何降频

- **维度**: 性能 | **严重度**: Major -> Minor | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotCombatState.java:538`
- **证据**: TarotSystem.onServerTick (TarotSystem.java:180-190) 每 tick 无条件调 `TarotCombatState.tick(server)`; TarotCombatState.tick (:508-526) 末尾调 clearMobTargets (:528-544), 其中对每个持 UNTARGETABLE 限制的在线玩家执行 `AABB area = player.getBoundingBox().inflate(64.0D); for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, area, candidate -> candidate.getTarget() == player)) { mob.setTarget(null); }`。窗口时长来自卡面数据: src/main/resources/data/miningdim/tarot/cards/18_moon.json:339-340 的 self_untargetable durationTicks = 160 (8 秒); 隐士闪耀 (09_hermit.json) 经 TarotCombatState.java:238 走同一条 UNTARGETABLE 路径。
- **影响**: 64 格 inflate 得到 128×128×128 的盒 —— 体积是精英怪那套 96³ 扫描的约 2.4 倍, 约 512 个 EntitySection/次, 而且是每 tick 一次 (20 次/秒), 不是每秒一次。单个玩家开一张月亮闪耀就在 8 秒内产生约 10 万次 section 访问, 全在主线程; 一个塔罗队伍同时开就是数倍。而该功能的实际语义 (阻止怪索敌) 对 0.5 秒级的延迟完全不敏感, 原版怪的 follow range 也远小于 64 格, 这个精度和半径都是纯浪费。
- **建议**: 方向两条同时收: 一是给 clearMobTargets 单独加降频 (例如与 danger 同拍每 10-20 tick 一次), 不要跟着 TarotCombatState.tick 的每 tick 节奏走; 二是把 64.0D 收到怪物实际 follow range 量级 (32-40 格), 128³ 降到 80³ 就是一半以上的开销。TarotCombatState.tick 内其余 6 个 removeIf 全表扫描同样是每 tick 跑, 一并考虑整体降频。
- **复核**: 维持 — 代码事实全部属实, 但影响量级被夸大了一个数量级, 降级 Minor。属实部分: TarotSystem.java:180-190 onServerTick 无条件调 TarotCombatState.tick(server); TarotCombatState.java:508-526 的 tick 末尾 :518 调 clearMobTargets; :528-544 确实是 player.getBoundingBox().inflate(64.0D) + getEntitiesOfClass(Mob.class, area, candidate -> candidate.getTarget() == player) + setTarget(null), 无任何降频。窗口来源也属实, 且审计员还低估了: 18_moon.json:339-340 self_untargetable durationTicks=160 (8s), 而隐士路径 (TarotEffectEngine.java:584 -> TarotCombatState.openHermitRestrictions:236-239 -> Restriction.UNTARGETABLE) 取 09_hermit.json:133-134 self_hermit_shiny durationTicks=300, 即 15 秒而非 8 秒。降级依据: :529-533 的循环是遍历 RESTRICTIONS 表, 没有 UNTARGETABLE 或已过期即 continue —— 没有玩家处于该状态时 RESTRICTIONS 为空, 整个 clearMobTargets 零成本, 不存在"常态每 tick 扫"。真实峰值是每个持该状态的玩家 1 次查询/tick = 20 次/秒, 而 finding 1 是 640 次/秒 (40 人时), 即便按 128³/96³≈2.4 倍体积折算仍只有其 1/13; 更关键的是它逐 tick 均摊而非集中在同一 tick 起跳, 单 tick 只多一次查询, 打不出可感知的尖峰 (finding 1 那种 16 路同拍才会)。故这是一条真实但低危的浪费 (64 格半径确实远超原版索敌 followRange 量级, 每 tick 精度对"清索敌"语义也确无必要), 定 Minor。审计员附带提的"其余 6 个 removeIf 每 tick 全表扫" (:510-517) 不成立为独立问题: 那几张表都是 per-player 战斗态, 平时为空或个位数条目。


#### F070 · pending_payout 表没有 seller_uuid 索引, 每次查/取待结货款都是全表扫描, 且该表只增不减地累积离线卖家的行

- **维度**: 性能 | **严重度**: Minor | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/store/MiningSchema.java:56`
- **证据**: V1 迁移建了 pending_payout 表 (:56-61), 但同一批 CREATE INDEX 只覆盖 listings 与 transactions (:67-70: idx_listings_status_item / idx_listings_seller / idx_txn_buyer / idx_txn_seller), 没有 pending_payout 的任何索引。而访问该表的三条语句全部按 seller_uuid 过滤: MarketDaoSqlite.java:311 `SELECT amount FROM pending_payout WHERE seller_uuid = ?`、:312 `DELETE FROM pending_payout WHERE seller_uuid = ?`、:336 peekPendingPayout 同一 SELECT。调用点 MarketEngine.java:303 (drain, 上线结算) / :371 / :379 (peek, 收件箱面板)。行只在该卖家本人 drain 时被删除。
- **影响**: 卖家离线期间成交就往表里插一行, 只有该卖家回来结算才删。长期运营下退坑玩家的行永久驻留, 表单调增长。每次有人开收件箱面板或上线结算, 都在服务端主线程上对整张表做一次线性扫描。单次代价随表长增长, 是那种"半年后才开始有感觉"的慢性退化。
- **建议**: 方向: 追加一条新的 schema 迁移 (V3, 遵循 MiningSchema.java:13-14 的"已发布迁移只能追加不能改"铁律) 建 `pending_payout(seller_uuid)` 索引。另可考虑给长期未结算的行加一条运维侧清理/归档路径, 但那属于业务裁决不是纯性能。
- **复核**: 未复核 (原报 Minor)


#### F071 · 两处诊断 INFO 日志挂在每秒级/每扫描周期的战斗热路径上, 输出量随战斗规模持续增长

- **维度**: 性能 | **严重度**: Minor | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionDotTickHandler.java:102`
- **证据**: ChampionDotTickHandler.onServerTick 每秒 flush 时对每个身上有 DoT 且本秒有伤害的玩家打一行 INFO: `LOGGER.info("dot-tick {} total={} hp={}/{} sources={}", ...)` (注释自称"诊断 (真服首验)"), 且同行内还有 3 次 String.format + summarizeSources。ChampionBossBarHandler 同样在每 10 tick 的扫描里对血条的创建/摘除各打一行 INFO (:97 `LOGGER.info("bossbar-remove {}", ...)`、:111 `LOGGER.info("bossbar-create {} tier{} title={}", ..., view.name.getString())`), 注释写明"低频不门控"。
- **影响**: dot-tick: 一场 20 人被精英怪 DoT 的团战就是 20 行/秒持续输出。bossbar: 血条在册与否取决于"是否落在任一玩家 48 格内", 玩家在 48 格边界来回走动会让同一只精英怪每 0.5 秒创建/摘除一次, 即 2 行/秒/怪, 且每次 create 都要 getString() 解析翻译组件。两者叠加在主线程上产生持续的日志写入与字符串构造, 并让 latest.log 被战斗噪音淹没, 真出问题时反而难定位。
- **建议**: 方向: 这两处是刻意保留的真服取证日志, 不建议直接删 (日志清理权归你)。可行的折中是降级到 debug 并由 logger 配置控制开关, 或复用本仓已有的 ChampionDiagnostics.shouldTrace 门控范式 (ChampionAttackHandler.java:542 已是这个写法) —— 只对被显式标记追踪的目标打印。
- **复核**: 未复核 (原报 Minor)


#### F072 · MarriageBackpackSessions 的会话表只增不清, 唯一的回收方法 pruneIdle 零调用点

- **维度**: 性能 | **严重度**: Minor | **审计域**: 横切专项: 性能 (perf) — 全库扫描
- **位置**: `src/main/java/com/miningdim/marriage/MarriageBackpackSessions.java:109`
- **证据**: `private final Map<Long, Session> sessions = new HashMap<>();` (:35) 由 containerFor 的 computeIfAbsent (:44) 只增不减地填充; onClosed (:58-63) 只把玩家从 openers 里摘掉, 明确保留 Session 与其 MarriageBackpackContainer 实例 ("集合空但容器实例保留, 供下次开复用同一权威容器")。移除条目的路径只有 onMarriageDissolved (:93, 离婚) 与 reset (:98, 停服)。pruneIdle (:109-116) 的 javadoc 自己写着"当前不主动调, 保留为诊断/GC 接缝", 全库确无调用点。
- **影响**: 每一对曾经打开过共享背包的夫妻都在表里留下一个 Session (含一个 MarriageBackpackContainer 实例 + 一个 HashSet), 直到离婚或停服才释放。单条占用很小, 但增长完全由"历史累计婚姻数"决定而非当前在线数, 且没有任何回收时机 —— 属于长期运行的单向内存增长。同时 pruneIdle 是一个已实现但无人调用的死方法。
- **建议**: 方向: 要么在 onClosed 发现 openers 为空时直接移除 Session (复用容器实例带来的收益极小, 因为内容本就唯一存在于 MarriageState.sharedInv, 重建容器不丢数据), 要么把 pruneIdle 接到一个低频的周期 tick 上并删掉"保留为接缝"的说法。两者择一, 不要留着一个自称是 GC 接缝却从未接线的方法。
- **复核**: 未复核 (原报 Minor)


#### F073 · 背包里每张塔罗牌每 tick 在服务端主线程反序列化一次 tooltip JSON

- **维度**: 性能 | **严重度**: Major -> Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotCardItem.java:209`
- **证据**: inventoryTick: `if (!level.isClientSide && (!hasCurrentEffectTooltip(stack.getTag()) || cachedEffectTooltip(stack.getTag()).isEmpty()))`。对一张 NBT 正常的牌, 第一个条件为 false, 于是每 tick 都会求值第二个条件, 而 cachedEffectTooltip (line 253-270) 会遍历 EffectTooltip ListTag 并对每一行调 Component.Serializer.fromJson (Gson 反序列化) 后再 List.copyOf, 只为判断结果是否为空。
- **影响**: 满编公服上, 每个持牌玩家的每张牌每 tick 触发若干次 Gson 反序列化 + 组件对象分配 (一张牌的效果说明通常 3-8 行)。100 人各带 5 张牌即约每秒数万次 JSON 解析与临时对象分配, 全部落在服务端主线程与 GC 上, 属纯粹无收益开销 (结果被丢弃)。
- **建议**: 把 '是否需要补写' 的判据改成只读 NBT 结构 (版本号 + 列表存在且 size>0), 不要为此解析 JSON; 或把补写迁到只在 stack 首次进入背包/版本号不匹配时触发一次。
- **复核**: 维持 — 代码事实成立: TarotCardItem.java:209-210 条件为 `!hasCurrentEffectTooltip(tag) || cachedEffectTooltip(tag).isEmpty()`, 对 NBT 健全的牌第一个子句恒 false, 于是每 tick 都会执行 cachedEffectTooltip (253-270 行), 该方法逐行 Component.Serializer.fromJson (Gson) 后 List.copyOf, 结果只用来判空即丢, 确属纯浪费, 且 Inventory.tick 对 36 格主背包每格每 tick 都调 inventoryTick。但严重度按真实量级下调: 单张牌的效果说明行数由 datapack ops 数决定 (10_wheel_of_fortune.json 每个品质 2-3 个 op, 即 2-3 行, 非审计员假设的 3-8 行), 且塔罗只是八职业之一, 现实持牌人数远低于'满编 100 人各带 5 张'。按 20 名持牌者 × 8 张 × 3 行 = 每 tick 约 480 次小 JSON 解析 (约 1-2ms/秒量级), 是应修的浪费但不构成 tick 预算威胁; 无正确性/经济影响, 判 Minor。


#### F074 · 卡牌与冷却在演出开始时就扣掉, 但效果 64 tick 后才结算, 期间死亡/换维度/登出则整张牌白扣

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotPlayHandler.java:87`
- **证据**: tryPlay 先 cooldown.tryUse 占 CD (line 65), 再 castManager.begin(player, TarotCastTiming.EFFECT_RESOLVE_TICKS=64, ...) 排结算 (line 71), 然后立刻 stack.shrink(1) (line 87)。TarotCastManager.tick (TarotCastManager.java:82-85) 在到点时若 `player == null || player.isRemoved() || !player.isAlive() || !player.level().dimension().equals(cast.originDimension)` 就 iterator.remove() 后 continue —— 直接丢弃, 不回补卡牌也不退 CD; TarotSystem.cleanup/onDeath/onChangeDimension 还会主动 castManager.cancel。
- **影响**: 演出窗口有 3.2 秒。玩家在残血时打出无敌窗/复活契约这类保命牌, 只要在这 3.2 秒内被打死 (公服 TACZ 高 DPS 下极常见), 牌已消失、CD 已进入, 效果一次都没生效; 进出矿洞维度的传送同样会吞牌。牌是青辉石/信用点买包开出来的付费产物, 属可感知的资源损失。
- **建议**: 把消耗与占 CD 移到 resolution 回调里 (结算成功才扣牌扣 CD), 或在 castManager 丢弃 pending cast 时回补一张牌并清该卡 CD; 顺带评估 3.2 秒演出是否该对战斗类牌缩短。
- **复核**: 维持 — 路径核实无误: TarotPlayHandler.java:65 先 cooldown.tryUse 占 CD, 71 行 castManager.begin(EFFECT_RESOLVE_TICKS=64, 见 TarotCastTiming.java:13), 87 行立刻 stack.shrink(1); TarotCastManager.java:80-85 到点时若玩家已死/已移除/换了维度就 iterator.remove() 后 continue, 全程没有任何回补卡牌或退 CD 的分支; TarotSystem.java:234/247/270 的 castManager.cancel 同样只是丢弃。3.2 秒窗口与'死亡不掉落环境下玩家不预期丢物品'的落差也确实存在。下调为 Minor 的理由: 这是 TarotPlayHandler.java:70 注释明写的既定设计 ('先提交卡牌和冷却并播放演出'), 后果是玩家自身单件消耗品的损失, 方向对玩家不利、不可被利用, 无经济/权限/安全外溢, 也不影响服务端一致性; 属体验缺陷而非系统性缺陷。


#### F075 · 裸牌 (缺身份 NBT 的塔罗牌) 未防, 会让开包/合成/用牌三条路径直接抛异常中断

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/pack/PackGachaService.java:152`
- **证据**: isCard: `!stack.isEmpty() && stack.getItem() instanceof TarotCardItem && TarotCardItem.cardId(stack) == cardId`, 而 TarotCardItem.cardId (TarotCardItem.java:96-102) 对缺 CardId 键的栈 throw IllegalStateException。同类未设防调用点还有 TarotCraftMenu.tryCraft:159 的 TarotCardItem.quality(a) 与 TarotPlayHandler:37-39。工程里已经有现成的非抛探针 TarotCardItem.hasReadableCardIdentity (line 85-94), 市场白名单 (MarketTradeWhitelist.java:83) 与 WebUI 面板 (TarotWebUiActions.java:282) 都先过这道探针, 唯独这三处没有。裸牌是真实可得物: `/give <player> miningdim:tarot_card` 产出的栈无任何 NBT (创造标签页里的样例牌反而是齐全的, TarotCreativeTab.java:43-44)。
- **影响**: 只要玩家背包里有一张 op 用 /give 发出的裸牌, 他此后每次右键开卡包都会在 playerOwnsCard 里抛异常, 开包流程被服务端异常终止 (钱已付的卡包既开不出牌也可能不消耗), 日志刷 fatal 且玩家侧毫无反馈; 把裸牌放进合成台两槽点合成、或直接右键裸牌, 同样是异常终止而不是友好拒绝。
- **建议**: 三处调用点统一先过 hasReadableCardIdentity: 开包判重时把不可读的牌视为 '不是同 id 的牌' 跳过, 合成/用牌把不可读牌走已有的友好拒绝分支 (与 owner 不符同一条提示线)。
- **复核**: 维持 — 三处调用点属实: PackGachaService.java:150-153 isCard 直接调 TarotCardItem.cardId, 而 cardId (TarotCardItem.java:96-102) 对缺 CardId 键的栈 throw IllegalStateException; TarotCraftMenu.java:159 的 TarotCardItem.quality(a) 在 156 行的 instanceof 之后、未过任何可读性探针; TarotPlayHandler.java:37-39 同样直接取三键。现成探针 hasReadableCardIdentity (TarotCardItem.java:85-94) 确实只被市场白名单与 TarotWebUiActions.java:282 用上。但可达性远比审计员说的窄, 故下调 Minor: 全工程所有产牌路径 (PackGachaService.java:159-160 / TarotCraftService.java:83,89 / TarotShardExchange.java:44 / TarotCreativeTab.java:43-44) 一律走 TarotCardItem.create, 而 create (59-74 行) 强制写全四键并对 null owner 抛异常, 创造标签页样例牌 NBT 是齐的; data 目录下也没有任何产出 tarot_card 的战利品表 (只有 tags/items/tarot_bound.json 引用了它)。裸牌只能由 op 权限的 /give 产出, 普通玩家无从获得, 也进不了市场 (MarketW2GameTests.java:222 即针对裸牌的拒绝用例)。即 op 误发一张才会触发, 属健壮性缺陷。


#### F076 · C2S 封印包缺少 WebUI 路径明确要求的 '快照门 + 解密门', 客户端可直接对任意实体任意词条发起封印

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/network/AgentSealRequestC2S.java:62`
- **证据**: handle 里只做 sender 非空与 level.getEntity(targetNetworkId) instanceof LivingEntity 两步, 随后直接 AgentSealSeam.requestSealResult(sender, living, msg.affixId)。同一业务的 WebUI 路径 (AgentWebUiActions.java:296-324) 明确加了两道门并在注释里写明理由: '没扫过就能封等于把探测支线整条跳过'、'集成层的 requestSeal 只查词条可封性, 不查解密态 —— 少了这道门, 客户端直接送一个加密词条的注册名就能封'。该包已在共享通道注册 (MiningNetwork.java:88-93), 是活的服务端入口, 且无任何频率限制。
- **影响**: 任何改包客户端 (以及未来接线的原生面板) 都能跳过战术扫描脉冲的长 CD 与分级解密曲线, 直接对视野内任意实体、任意词条注册名申请封印 —— L1 干员即可尝试封他本来看不到的词条, 探测支线整条被绕过。目前因下述 capability 契约失配, 封印恒返 NO_TARGET, 所以这条通路是 '已开但暂时打不穿'; 一旦按经济审计建议修好探测源, 它立刻变成真实的门槛绕过。
- **建议**: 把快照门与解密门下沉到共用的服务端裁决层 (让 WebUI 与 C2S 走同一个入口), 而不是只在 WebUI 层写一份; 同时给该包加最小频率限制。
- **复核**: 维持 — 两条路径的门确实不对等: AgentSealRequestC2S.java:50-63 只做 sender 非空 + getEntity 是 LivingEntity 两步即转调 AgentSealSeam.requestSealResult; 而 AgentWebUiActions.java:306-324 明确加了快照门 (必须来自本人仍有效的 ScanPulse) 与解密门 (entry.decrypted), 注释 291-294 行写明了理由。该包确实是活入口 (MiningNetwork.java:89-92 注册, AgentScanScreen.java:67 原生面板在用), 也确无频率限制。下调为 Minor 的理由: 一是它今天完全打不穿 —— requestSeal 第一道就是 AgentChampionData.isOurChampion (AgentSealHandler.java:64), 而该判据对本工程精英恒 false (见第 10 条), 恒返 NO_TARGET; 二是即便修好探测源, 被绕开的只是'先扫描'与'已解密'两条前置, 干员等级门/星级门/类别门 (SealPlan) 与槽位、互斥、类别 CD (SealRegistry.applySeal, AgentSealHandler.java:81-92) 仍在服务端全数生效, 不会导致越权封高星或无限封印。属必须与第 10 条同一补丁一并收口的权威面缺口。


#### F077 · 封印到期恢复只在矿洞维度查实体, 查不到就丢快照 —— 精英词条被永久剥夺

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/integration/AgentSealHandler.java:166`
- **证据**: onServerTick 取 `ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL)` (line 153) 后用 `mining.getEntity(championId)` 复原实体 (line 166); 复原不到 (实体在别的维度 / 所在区块已卸载 / 实体未加载) 就走 line 175 的 AgentSealExecutor.discard(championId), 而 discard 只是把 ORIGINAL_AFFIXES 里的原词条快照删掉 (AgentSealExecutor.java:92-96), 恢复用的唯一数据源就此丢失。词条移除本身是写进实体持久数据的 (sealAffix 走 setAffixes + ChampionBuilder.resetAndUpdate)。
- **影响**: 封印窗口只有 8-12 秒, 而精英完全可能在窗口内走出加载范围或玩家离场导致区块卸载。此时到期 tick 找不到实体, 快照被丢弃, 该精英的那条词条就永久消失, 但它的初始星级 NBT 不变 —— 奖励池按初始星级算, 于是产出 '同样星级同样奖励、危险词条却被永久摘掉' 的怪, 玩家可主动利用 (封掉核心词条后拉远脱战再回来收割)。
- **建议**: 恢复源不应依赖 '当时能不能找到实体': 把原词条快照写进精英自身的持久数据 (随实体存盘), 由实体重新加载时自行恢复; 遍历也不应写死矿洞维度, 应按封印时记录的维度键查。
- **复核**: 维持 — 代码事实核实无误: AgentSealHandler.java:153 只取 server.getLevel(MiningConstants.MINING_LEVEL), 166 行只在该维度 getEntity(championId), 复原不到即走 175 行 AgentSealExecutor.discard, 而 discard 只删 ORIGINAL_AFFIXES 快照 (AgentSealExecutor.java:92-96), 恢复源就此丢失; 封印时也确实没有记录目标所在维度键。但严重度下调: 这条路径今天一次也执行不到 —— 149 行 `AgentSealExecutor.snapshotCount() == 0` 直接早退, 而快照唯一写入点是封印成功, 封印又恒被 64 行的 isOurChampion 拦死 (见第 10 条), 即封印从未成功过, 恢复 tick 永远空转。属必须与第 10 条同一补丁一并处理的潜伏缺陷 (且届时 AgentSealExecutor 的 setAffixes 链本身也要改写到自研词条表), 现阶段无玩家可感后果。


#### F078 · 悬赏支线 (10.5) 只有数据类没有任何生产调用点, 周青辉石门控出口零调用

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/BountyDefinition.java:15`
- **证据**: 全库 grep 显示 BountyDefinition / BountyProgress 的构造只出现在 AgentGameTests.java:489-532; AgentRewardHandler.grantWeeklyBountyAzure (AgentRewardHandler.java:202) 是 tryGrantWeeklyAzure 的唯一调用者, 而它自己没有任何生产调用者。AgentBountySavedData 类注释 (line 24-26) 也自承 '每玩家多槽悬赏实例的持久化序列化属 b 阶段', save/load 里确实只有 weeklyAzure 与 activeAgents 两块, 没有悬赏进度。与此同时 job.agent.state 回执 (AgentWebUiActions.java:163-175) 已经在给前端下发 dailySlots/weeklySlots/maxBountyStar/weeklyAzureCap 这一整套悬赏字段。
- **影响**: 面板会展示一整套 '日常 X 槽 / 周常 Y 槽 / 可接 N★ / 本周青辉石 0/50' 的悬赏信息, 但玩家没有任何接取、推进、领奖的入口, 也没有任何代码会写这些计数 —— 属于玩家侧看得见摸不着的半成品; 青辉石作为唯一的周常产出通路目前实际产量恒为 0。
- **建议**: 要么补齐悬赏实例的接取/推进/发奖与持久化 (BountyProgress 已有 rolloverIfStale 等纯逻辑可直接复用), 要么在面板回执里把悬赏段标成未开放, 不要下发一组永远不会变的数字。
- **复核**: 未复核 (原报 Minor)


#### F079 · 开包重复判定只看当前背包, 把牌放进箱子即可规避 '重复转碎片'

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/pack/PackGachaService.java:127`
- **证据**: grantOrRefund 的判据是 `playerOwnsCard(player, cardId) || grantedThisPack.contains(cardId)`, 而 playerOwnsCard (line 136-148) 只遍历 player.getInventory().items 与 offhand。
- **影响**: 玩家开包前把已有牌全部存进箱子/末影箱, 就永远不会触发重复转碎片, 每次都拿真牌 (真牌可当合成材料, 价值远高于 1 碎片); 老实把牌带在身上的玩家反而被扣成碎片。结果是碎片这条 '反非酋毕业线' 通路对懂行玩家形同虚设, 而碎片兑换 (SHARD_EXCHANGE_COST=40) 也就基本无人可达, 同一张牌可被无限量重复获取。
- **建议**: 重复判定应基于持久化的 '该玩家已收集过哪些 cardId' 集合 (SavedData / capability), 而不是即时背包扫描; 顺带让 TarotWebUiActions 的 inInventory 口径跟着改, 保持面板与开包同源。
- **复核**: 未复核 (原报 Minor)


#### F080 · 隐士闪耀的不可选中窗口期间, 每 tick 对持窗玩家做 128 格立方体的 Mob 全扫

- **维度**: 性能 | **严重度**: Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotCombatState.java:528`
- **证据**: clearMobTargets 由 tick() 每 tick 调用, 对每个持 UNTARGETABLE 限制的玩家执行 `player.getBoundingBox().inflate(64.0D)` 后 `player.serverLevel().getEntitiesOfClass(Mob.class, area, candidate -> candidate.getTarget() == player)` 并逐只 setTarget(null)。
- **影响**: 窗口期内每 tick 一次 128x128x128 的实体检索 (每秒 20 次), 在怪物密集的矿洞维度是可观的主线程开销; 多名玩家同时持窗时线性叠加。窗口时长虽有限, 但代价与 '清掉仇恨' 这件事的实际需要严重不匹配。
- **建议**: 降频 (如每 10-20 tick 一次) 并缩小半径到实际仇恨范围 (原版怪追踪距离普遍 <= 32 格); 或改为在 LivingSetAttackTargetEvent 上拦截, 从源头拒绝把持窗玩家设为目标。
- **复核**: 未复核 (原报 Minor)


#### F081 · 扫描快照 S2C 对未解密词条照发真实 affixId, 分级解密在原生面板路径被击穿

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/network/AgentScanSyncS2C.java:38`
- **证据**: encode 无条件 `buf.writeUtf(e.affixId())`, 只有 displayKey 在构建时被置空 (AgentScanSnapshotBuilder.java:72-73 对未解密条目写 "" 但 affixId 照填真值)。同一份快照走 WebUI 时是脱敏的 —— AgentWebUiActions.entryJson (line 504-514) 对未解密条目把 affixId/displayKey/category 三项全打成 JSON null, 并在注释里写明 '客户端自律在浏览器里不成立'。
- **影响**: 原生面板路径下, 任何抓包或改包客户端都能拿到未解密词条的真实注册名, L1 干员即可看到本该 L5 才解密的机制类词条身份, 探测支线的十级解密曲线在这条路上白做。当前 MiningNetwork.sendAgentScan 零发送方, 所以尚未真正泄漏, 属已注册但未接线的隐患。
- **建议**: 脱敏应做在快照构建层 (未解密条目的 affixId 本就不该带进要下行的对象), 让两条下行路径共享同一份已脱敏数据, 而不是各自在序列化处补一遍。
- **复核**: 未复核 (原报 Minor)


#### F082 · 高等级战术扫描一次脉冲要收集并排序 448 格立方体内的全部生物

- **维度**: 性能 | **严重度**: Minor | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/AgentWebUiActions.java:231`
- **证据**: SCAN 先 `level.getEntitiesOfClass(LivingEntity.class, sender.getBoundingBox().inflate(radius), ...)` 再对整份结果按距离排序, 之后才应用 MAX_SCAN_TARGETS=8 的截断。radius 来自 effectiveScanRadiusBlocks (line 438-445): L9 = 448 格, L10 取 max(448, 视距区块×16)。
- **影响**: L9/L10 干员每次脉冲 (CD 30-60s) 会在主线程构造一个约 896³ 的 AABB 查询, 把范围内所有生物 (含刷怪塔/农场里的成群实体) 收进 List 再整体排序, 而最终只用前 8 个。人数多、怪多时单次脉冲的停顿可观, 且完全可以在收集阶段就剪枝。
- **建议**: 边遍历边维护一个容量 8 的最近目标堆, 不做全量收集+全量排序; 或先按较小的分层半径找够 8 个即停 (球形判据保持不变)。
- **复核**: 未复核 (原报 Minor)


#### F083 · 披甲护盾窗口被低档窗口覆盖后 absorption 记账对不上, 会留下永不回收的黄心

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/chef/ChefWindowEffectState.java:103`
- **证据**: stampShield 用 `if (shield > player.getAbsorptionAmount()) player.setAbsorptionAmount(shield)` 做"刷新不叠", 但随后无条件写 `w.shieldGranted = shield` (:103) —— 记的是本次算出的护盾值, 而不是本次实际抬高的量。过期回收 (:201-204) 与 reclaimOnline (:157-162) 一律按 shieldGranted 做减法。
- **影响**: 先吃闪耀菜 (80 血 × 8% = 6.4 黄心, 窗口记 6.4), 在窗口未过期时再吃一份高级菜 (3.2 < 6.4, absorption 不变但窗口被整条替换成 granted=3.2 且计时重置), 窗口过期只退 3.2, 剩下的 3.2 黄心没有任何窗口记录, 直到死亡为止永不回收 —— 等于白拿一层不过期的护盾。反向也错: 先吃金苹果拿 4 点 absorption 再吃高级菜 (3.2), 因为走的是 setAbsorptionAmount 覆盖而非累加, 金苹果那份会被直接抹掉。
- **建议**: shieldGranted 应记"本次实际抬高的差值"(max(0, 新值 - 施加前的 absorption)), 施加也应基于差值而不是整体覆盖, 这样过期回收才与外来 absorption 来源互不侵犯。
- **复核**: 未复核 (原报 Minor)


#### F084 · 酿酒永久特殊的清理会无条件删掉玩家身上任何来源的急迫

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/brewer/BrewPermanentBuffs.java:67`
- **证据**: clearAttributesAndEffects 里 `player.removeEffect(MobEffects.DIG_SPEED);` 无任何来源判定; applyBrandyHaste 在 layers<=0 分支 (:113) 也照样删。该方法在登录重挂 (BrewPermanentBuffHandlers.java:52 remountAll -> clearAttributesAndEffects) 与死亡 (BrewPermanentBuffHandlers.java:40 buffs.clearAll) 两条路径都会跑。
- **影响**: 厨师"提神"给的 DIG_SPEED (ChefConsumeHandler.applyRefresh, 闪耀档 600 秒)、原版急迫药水、信标急迫, 只要玩家触发一次酿酒重挂 (每次登录只要有任意永久层或月光词条) 或死亡, 都会被连带清空。白兰地 0 层但其它类型有层的玩家, 每次登录都会被无理由清一次急迫。
- **建议**: 白兰地的永久急迫要能被辨认后再删 —— 例如只在当前实例确为本 mod 施加的 30 天长时 + 对应 amplifier 时才移除, 而不是按效果类型一刀切 removeEffect。
- **复核**: 未复核 (原报 Minor)


#### F085 · 一次探矿最多把整个探测球重新扫五遍

- **维度**: 性能 | **严重度**: Minor | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/miner/OreScanService.java:139`
- **证据**: scanWorldDetailed 对 preferenceOrder() 的 5 个矿种逐个调 collectWithinSphere, 每个矿种都把整个半径球从头遍历一遍 (:155-180 的三重循环 + level.isLoaded + level.getBlockState), 命中即 return, 不命中就继续下一个矿种。半径由 MinerSkills.oreScanRadius 给, L10 为 16。
- **影响**: L8+ 玩家满半径时单次探矿在服务端主线程最多做 5 × 约 18700 = 9 万余次方块读取; 球内一无所获 (已挖空区域是常态) 恰好是最坏情况, 必然跑满五遍。CD 虽为 180-300 秒, 但满编公服上多名矿工错峰触发会周期性造成单 tick 尖峰, 且 job.miner.scan 面板路径与键位路径共用同一实现, 触发面翻倍。
- **建议**: 改成一趟遍历同时统计五个矿种的命中 (每矿种一个结果桶 + 各自的 64 条硬顶), 遍历完再按 preferenceOrder 挑第一个非空桶, 把 5 次全球遍历压成 1 次, 单矿种/半径/硬顶等对外语义完全不变。
- **复核**: 未复核 (原报 Minor)


#### F086 · 酿酒师全部平衡数值零 ForgeConfigSpec, 上线后无法热调

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 生产四职业 (矿工 job/miner · 农夫 job/farmer · 厨师 job/chef · 酿酒师 job/brewer)
- **位置**: `src/main/java/com/miningdim/job/brewer/BrewerConstants.java:10`
- **证据**: 整个 job/brewer 包 grep ForgeConfigSpec 零命中; BrewerConstants 从 MILLIS_PER_VINTAGE_YEAR、MAX_LAYERS_PER_TYPE、九类每层收益, 到 DRIED_WHEAT_PER_BOTTLE_YEAR/FUEL_QUAD_COEF/SPOILAGE_DECAY_YEARS_PER_DAY、软上限 knee 与 diminish, 全是 public static final 编译期常量。对照 ChefConfig.java:21-258 已把厨师全部数值放进自持的 SERVER SPEC 并在 ChefSystem.java:40 registerConfig, 业务层一律实时 .get()。
- **影响**: finding 4 的九种永久层收益与 finding 3 的燃料系数都是需要频繁试调的平衡参数, 但要动任何一个都必须改代码、重编译、重启服务端, 无法像厨师那样改 toml 即时生效。同一套职业框架下两个职业的运营能力不对等, 平衡迭代成本被人为抬高。
- **建议**: 照 ChefConfig 的范式给 brewer 建一份自持的 SERVER SPEC (miningdim-brewer.toml), 由 BrewerSystem.register 注册, BrewerConstants 保留结构语义、数值改走 *.get() 实时读取; 优先把层数收益、燃料曲线、衰退速率、软上限四组挪进去。
- **复核**: 未复核 (原报 Minor)


#### F087 · SelectZoneC2S 仍注册在信道上: 绕过矿工等级门直接 allocate, 且从不传送玩家

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/network/SelectZoneC2S.java:55`
- **证据**: handle 在 enqueueWork 里直接 `MiningServices.instanceManager().allocate(sender, msg.difficulty)` 并回 sendInstanceStatus, 全程没有 EntryGateway.gateCheck (MinerLevelGate) 那一步, 也没有任何传送。该包在 MiningNetwork.java:52-53 被 registerMessage 注册进 PLAY_TO_SERVER 信道; 全库 grep 找不到任何发送方 (只有 MiningWebUiActions:39-40 与两处 GameTest 注释把它列为 '存量只 allocate 不传送且不过 gateCheck' 的坏路径)。
- **影响**: 任何自制客户端都能对任意难度发这个包: 虽然不会真的传送 (故不是完整的门槛绕过), 但可拿到该实例的状态回执; 若此时实例仍在生成中, allocate 会往 InstanceManager.pendingAllocations 的列表里无上限追加 future, 反复发包即可堆积。功能上它是一条永远走不完的半成品入场路径。
- **建议**: 直接注销这个包 (WebUI 与入口方块已覆盖入场需求); 若要保留, 必须先过 gateCheck 再委派 EntryGateway.requestEnter, 并对同一玩家的挂起请求去重。
- **复核**: 未复核 (原报 Minor)


#### F088 · region 几何常量按 384 高工作, 维度实际只有 192 高: 任何按 REGION_FULL_* 取世界 Y 的代码都会落到世界外

- **维度**: 兼容性 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/core/MiningConstants.java:59`
- **证据**: REGION_HEIGHT = 384 且注释写 '等于维度 height', 派生出 REGION_MAX_Y_EXCLUSIVE=320 / REGION_FULL_MAX_WORLD_Y=319 (:65,:107); 但 data/miningdim/dimension_type/mining.json 是 height=192, min_y=-64 (可建高度 -64..127), noise_settings/mining.json 的 noise 段同样是 min_y=-64/height=192。RegionBox.ofDefault 因此产出 sizeY=384 的体素盒, 一半体素恒在世界之外。现存的一处实际误用在 command/MiningCommands.java:257 `double cy = (REGION_FULL_MIN_WORLD_Y + REGION_FULL_MAX_WORLD_Y) / 2.0;` = 127.5, 落在 surface_rule 顶部 5 格基岩里 (noise_settings 的 bedrock_roof 规则)。
- **影响**: 当前活代码里 EntryGateway 用 Math.min(..., level.getMaxBuildHeight()-2) 钳住了, command 包又未接入主类, 所以暂无线上后果; 但常量与维度 JSON 已经脱钩, 任何新写的 '按 region 全高取世界 Y' 的代码都会直接落到世界外或基岩层, 上面那条 /mining tp 就是现成的样例 (一旦有人把 command 包接回去, 管理员会被传进基岩)。体素/几何层还按两倍高度分配内存。
- **建议**: 让 REGION_HEIGHT / REGION_MIN_Y 与 dimension_type 的 height/min_y 成为单一真源 (启动期做一次自检并在不一致时报错), 并顺手修掉 command 包里那个中点 Y。
- **复核**: 未复核 (原报 Minor)


#### F089 · NEW_SEED 重置代数只存在进程内存里, 重启后从 0 重来会复用同一批种子

- **维度**: 兼容性 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/reset/ResetSystem.java:59`
- **证据**: `private final Map<Long, Integer> resetGenerations = new ConcurrentHashMap<>();` 是纯内存, onServerStopping:88 直接 clear; nextResetGeneration (:204-209) 用它 merge 自增, ResetJob:118 据此 `SeedUtil.deriveSeed(globalSeed, instanceId, resetGeneration)`。而 persistence/MiningSavedData.java:110-119 已经提供了持久化的 resetGeneration 计数器与 incrementResetGeneration(), 全库 grep 零调用点。
- **影响**: 每次服务端重启后, 该实例的重置代数回到 0, 于是重启后第一次 NEW_SEED 重置派生出的 seed 与上一次重启后第一次完全相同。在离线生成链路复活 (或换成别的按 seed 出图的实现) 之后, 表现就是 '换图换出一张玩过的图'; 当前因为重置本身不重建世界, 这个缺陷被上一条 Critical 掩盖着。
- **建议**: 改用 MiningSavedData 里已有的持久计数器 (或给每个实例存一份代数), 让重置代数随存档落盘。
- **复核**: 未复核 (原报 Minor)


#### F090 · GenerationScheduler 自建的工作线程池从未被使用, 每次开服白起线程

- **维度**: 性能 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/instance/GenerationScheduler.java:71`
- **证据**: 构造函数 `this.genPool = Executors.newFixedThreadPool(pool, namedDaemonFactory());` 并打日志 'GenerationScheduler started with N worker thread(s)'; 但 submit (:92-100) 是 `MiningServices.offlineGenerator().generate(...)`, 而 OfflineCaveGenerator 内部自己另有一个懒建的 maxGenWorkers 线程池 (OfflineCaveGenerator.java:61-76)。genPool 除了 shutdown() (:201) 之外没有任何引用。
- **影响**: 每次开服多创建 maxGenWorkers (默认 2) 个永不接活的线程, 日志还会打印一条误导性的 '已启动 N 个生成线程', 排查生成性能问题时会看错池子。
- **建议**: 删掉 GenerationScheduler.genPool (连同其日志), 线程池只保留 OfflineCaveGenerator 那一个; 若最终按上面的建议摘掉整条离线链路, 两个池子一起删。
- **复核**: 未复核 (原报 Minor)


#### F091 · MiningServices 的静态门面在停服时从不清空, 换存档后可能残留上一个存档的 InstanceManager

- **维度**: 兼容性 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/core/MiningServices.java:80`
- **证据**: reset() 方法注释写 '服务端停止时清空, 防止跨存档/跨重启的脏引用 (供 ServerStoppingEvent 调用; 可选)', 但全库 grep `MiningServices.reset()` 零调用点。instance/InstanceSystem.java:92-97 的 onServerStopping 只把自己的 manager 字段置 null, 静态门面仍指向旧实例; 而 onServerStarted (:54-59) 在矿山维度缺失时是直接 return 不注册。
- **影响**: 单机/局域网连续开两个存档时, 若第二个存档因数据包问题没有加载矿山维度, MiningServices.instanceManager() 仍会返回上一个存档的 InstanceManager (绑着已关闭的 ServerLevel 与 SavedData), 后续 regionAt/allocate 读到的是跨存档脏数据。专用服进程一次只开一个存档, 影响有限。
- **建议**: 在某个统一的 ServerStoppingEvent 里调用 MiningServices.reset(), 或至少把 instanceManager 这类随存档生命周期的门面在停服时置空。
- **复核**: 未复核 (原报 Minor)


#### F092 · 实例/重置/区块票/陷阱/出生 五条核心链路零 GameTest 覆盖, 上面多条缺陷属于 '删掉实现测试也不会挂'

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 矿洞世界 (entry / instance / worldgen / reset / spawn / trap / ore / chunk / entrance / pressure)
- **位置**: `src/main/java/com/miningdim/reset/ResetJob.java:75`
- **证据**: 本范围内带 @GameTest 的文件只有 entry/MiningWebUiGameTests、entry/MiningAdminWebUiGameTests、entry/MiningCapabilitiesGameTests、entry/UiPrefsGameTests 与 pressure/PressureGameTests (danger 数学与 DangerSource 注入); instance / reset / chunk / trap / spawn / ore / worldgen / entrance 八个包一个测试都没有。ResetJob 的四阶段状态机 (UNLOAD/REGEN/SETTLE/DONE)、InstanceManager 的启动重建与 GC、ChunkTicketManager 的窗口差量、liveMobs 销账全部无断言。
- **影响**: 本次报告里的重置无作用、活怪计数泄漏、区块票永不释放三条, 都属于 '把实现整段删掉现有测试仍全绿' 的类型, 回归防线缺失使这些缺陷可以长期潜伏。
- **建议**: 优先给三处补可证伪的测试: 重置后 region 内某个人工改动的方块必须消失; 一只被 discard 的怪必须让 liveMobs 归零; 固定实例在空置超 TTL 后 hasTickets 必须为 false。
- **复核**: 未复核 (原报 Minor)


#### F093 · 共享背包的高价矿黑名单可被潜影盒等容器物完整绕过, 且钻石/下界合金成品装备本就不在名单内

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/marriage/SharedBackpackWhitelist.java:37`
- **证据**: BLOCKED_ITEMS 只列 DIAMOND / EMERALD / NETHERITE_INGOT / NETHERITE_SCRAP / ANCIENT_DEBRIS / NETHERITE_BLOCK / DIAMOND_BLOCK / EMERALD_BLOCK, BLOCKED_BLOCKS 只列四种矿石方块 (第 48-53 行)。isAllowed (第 59-70 行) 只做 绑定NBT / 皮肤凭证 / 上述枚举 三项判定, 对 SHULKER_BOX 及其 BlockEntityTag 内的物品零检查, 对 DIAMOND_SWORD / NETHERITE_CHESTPLATE 等成品、附魔书、TACZ 枪械本体也一律放行。
- **影响**: 类注释声称这是 spec 第四章"杜绝定向洗矿"的服务端权威闸, 但玩家把 1728 颗钻石塞进一个潜影盒即可整箱放进共享背包完成定向转移, 闸门等于不存在; 同时下界合金整套装备可以自由互借 —— 在死亡不掉落 + 装备价值极高的公服环境下, 这正是该名单要挡的"互借神装"。安全面比设计意图大了一整个数量级。
- **建议**: 黑名单判定必须递归进容器物的 BlockEntityTag/Items 列表 (潜影盒、可能的 bundle), 并把判据从"枚举原料"改成"按物品 tag 或统一价值表判定" (与 AbuseGuard 的高价矿分类共用同一张表, 避免两处漂移); 成品高价装备是否放行需要单独裁决, 但当前是默认放行且无人知情。
- **复核**: 维持 — 代码事实成立但影响面被严重高估, 从 Major 降为 Minor, 且后半条主张站不住: 1) 前半条 (容器绕过) 属实: BLOCKED_ITEMS (SharedBackpackWhitelist.java:37-45) 八项、BLOCKED_BLOCKS (:48-53) 五项均为扁平枚举; isAllowed (:59-70) 只做绑定NBT/皮肤凭证/高价矿三判, isHighValueOre (:82-88) 只看 stack.getItem() 与 BlockItem.getBlock(), 对 SHULKER_BOX 的 BlockEntityTag/Items 零递归。MarriageBackpackContainer.java:111-114 canPlaceItem 直接转调它, 是唯一闸门, 潜影盒确实整箱放行。 2) 但实际危害极低, 因为该通道不提供任何 vanilla 之外的转移能力: MarriageBackpackMenu.java:179-193 的 spouseProximityValidity 要求配偶【在线 + 同维度 + 距离 <= MARRIAGE_BACKPACK_OPEN_RANGE】, 该值 MiningServerConfig.java:284-286 默认 64 格。也就是说共享背包只在两人同时在线且贴近时可用 —— 在这个前提下, 丢地上/摆个箱子就能完成同样的定向转移, 且全库 grep `ItemTossEvent` / `EntityItemPickupEvent` 零命中, 本 mod 根本没有限制玩家丢物/互拾。项目背景本身把 "物品可自由转移" 列为威胁模型的既定前提, 因此这道闸从设计上就挡不住洗矿, 潜影盒的洞并没有让攻击面 "大了一个数量级", 只是让一道本就象征性的闸更象征。 3) 后半条 (成品装备放行) 是对代码意图的误读: SharedBackpackWhitelist.java:21-22 类注释明确把 "互借神装" 的口径限定为【带身份盖章 NBT (OwnerUUID/SpouseUUID/MarriageId) 的绑定装备】, 并写明 "以 NBT 键识别, 不枚举具体物品类"。普通下界合金套本就不在该规则射程内, 这是显式设计取舍而非疏漏, 不能算缺陷。 结论: 保留为 Minor —— 递归进 BlockEntityTag 成本极低且能让这道闸名副其实, 值得修; 但它既不是新增攻击面也不改变全服经济态势, 不够 Major。


#### F094 · 堆叠周期扫描每 5 秒对全部维度拉一份全量 LivingEntity 列表, 并给每个实体强制分配 persistentData

- **维度**: 性能 | **严重度**: Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackingSystem.java:103`
- **证据**: scanLevel 用 `level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true)` 一次性物化本维度全部已加载 LivingEntity 到 ArrayList, 且 onServerTick 对 `server.getAllLevels()` 逐个调用; 随后对每个实体调 canStack -> StackData.hasStackData -> `entity.getPersistentData()`, 而 Forge 的 getPersistentData() 在 tag 为 null 时会 new 一个 CompoundTag 挂上去。lastSeenPos 是 `Map<Integer, Long>` (StackingSystem.java:48), 每轮对每个实体做一次装箱 put, 收尾再用一个新建 HashSet<Integer> 做 retainAll (第 82、86 行)。
- **影响**: 满编公服 + 多维度 + 刷怪塔/养殖场的实体量级下, 每 5 秒一次的全量物化 + 逐实体装箱是可测量的主线程尖峰; 更持久的代价是每个曾被扫到的实体都被永久挂上一个空 CompoundTag (含其 HashMap), 万级实体量约多占数 MB 常驻堆, 且这份开销对完全不可能堆叠的实体 (玩家、掉落物承载者、村民) 也照付。
- **建议**: 先按 EntityType 准入白名单过滤 (与 Critical 项的修复同一件事), 只对白名单类型走 getEntities(EntityType, ...) 的按类型索引查询; hasStackData 改成先判类型再读 persistentData, 避免对非候选实体触发 tag 分配; lastSeenPos 换成 Int2LongMap 之类的原生映射或直接改为按区块记录以消除装箱。
- **复核**: 未复核 (原报 Minor)


#### F095 · 剪毛产出按 N 倍结算但"已剪冷却"只按 1 只结算, 一次吃草让整堆 N 只同时长毛

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/stacking/StackPassive.java:87`
- **证据**: computeShearDrops 对 N 只逐只 roll 求和 (StackPassive.java:100-121), 但恢复侧只有一句 `sheep.setSheared(true);` (第 87 行), 之后靠原版 Sheep 的 EatBlockGoal 吃一次草即 setSheared(false)。也就是说产出乘了 N, 而重新长毛所需的"吃草次数"仍是 1。
- **影响**: 同一块围栏草地上, 堆叠 64 只羊的羊毛吞吐远高于 64 只散养羊 —— 散养时 64 只各要啃一次草, 草地会被啃秃形成天然节流; 堆叠后一次吃草回满 64 只, 草皮消耗降到 1/64。这与该文件自己 FR-3.4 声明的"严禁因堆叠出现免冷却"直接冲突, 是一条未计入经济总表的羊毛 faucet 放大。
- **建议**: 给堆叠羊单独记一个"待恢复只数"计数 (可复用 persistentData), 每次原版 ate() 只把恢复计数减 1, 减到 0 才允许再次整堆剪毛; 或者简单起见, 剪毛后按 N 折算一段 tick 冷却期, 期间 readyForShearing 恒 false。
- **复核**: 未复核 (原报 Minor)


#### F096 · 女祭司预知减伤绕开 PlayerDamageReduction 单点结算, 使 PLAYER_MAX_REDUCTION 全局帽在该次受击上失效

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotCombatHandlers.java:108`
- **证据**: CombatConstants.java:16-19 定义等效总减伤上限 0.85 (PLAYER_MIN_KEEP=0.15), PlayerDamageReduction.java:102-103 在 EventPriority.LOWEST 用 `keep = max(∏(1-r), PLAYER_MIN_KEEP)` 统一钳制并 setAmount。但 TarotCombatHandlers.onLivingHurtVictim 以默认优先级 (早于 LOWEST) 直接执行 `event.setAmount(amount * (1 - reduction))`, 该 reduction 既不是 ReductionSource 也不进 keepFactor。
- **影响**: 预知窗生效的那一次受击, 实际等效减伤 = 1 - (1-预知率) x keep, 例如预知 50% 叠上已钳到底的 0.15 保留系数, 玩家只吃 7.5% 伤害, 即 92.5% 减伤, 超过声明的 85% 硬帽。PlayerDamageReduction 类注释把"施加等效总减伤上限"作为改成单点结算的核心理由, 现在这条不变量在职业侧被绕开了一条口子, 后续再有职业照抄这个写法帽子就彻底失效。
- **建议**: 把预知减伤改成注册进 PlayerDamageReduction 的一个具名 ReductionSource (rate 内部消费窗口), 让它与凝脂/矿脉抗性/烈酒钝感走同一次连乘与同一个帽; 真·免疫窗 (愚者/纳米护盾 setAmount(0)) 属另一语义可保留, 但应在 CombatConstants 注释里明确"帽只约束按比例减伤, 不约束全免疫窗"。
- **复核**: 未复核 (原报 Minor)


#### F097 · MarriageState 的 sharedInvLevel / teleportLevel / divorceCount / lastWeddingTick 四个字段落盘但全库零写入方

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/marriage/MarriageState.java:129`
- **证据**: 全库 grep setSharedInvLevel / setTeleportLevel / setDivorceCount / setLastWeddingTick / setMarriedSinceTick 只有 MarriageState 自身的声明, 没有任何调用点。这四个字段在构造时被固定为 1 / 1 / 0 / marriedSinceTick, 却照常写进 save() (第 205-208 行) 与 load() (第 236-239 行)。实际生效的等级一律由 MarriageTuning 按婚龄现算 (MarriageBackpackMenu.java:144、MarriageWebUiActions.java:170), 离婚次数由 MarriageHistory 单独持有。
- **影响**: 存档里长期躺着四个恒为初值的假字段。任何后续读它们的代码 (管理面板、迁移脚本、客服工具) 都会拿到"所有夫妻背包等级都是 1、离婚次数都是 0"这种自洽但完全错误的数据, 而字段名与注释都在暗示它们是权威值 —— MarriageWebUiActions.java:139 的注释已经不得不特意声明"那个持久字段全库无写入方, 不作展示依据"。
- **建议**: 要么删掉这四个 setter 与对应持久化键 (现算派生是唯一真源), 要么在离婚/典礼路径上真正写入它们并让 MarriageTuning 只负责阈值换算。二选一, 不要留半套。
- **复核**: 未复核 (原报 Minor)


#### F098 · /marriage 命令树没有拒绝/撤回子命令, 且已接受的婚约意向永不过期、不随登出清理

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 社交与杂项 (marriage / stacking / combat / effect / core / config / rules)
- **位置**: `src/main/java/com/miningdim/marriage/MarriageProposals.java:36`
- **证据**: MarriageProposals 是纯内存 LinkedHashMap, 唯一的移除入口是 clear(proposer) (第 90-92 行), 调用点只有典礼成功后的 MarriageCommands.java:127-128 与 MarriageWebUiActions.java:403-404, 以及面板 RESPOND 里 accept=false 的分支 (MarriageWebUiActions.java:310)。MarriageCommands.register (第 40-53 行) 只注册 buyring/propose/accept/wed/divorce 五条, 没有 reject/cancel; MarriageSystem 的 PlayerLoggedOutEvent (第 199-210 行) 也不清理该表。
- **影响**: 玩家用 /marriage accept 接受后就再没有命令行退路 —— 只能去 WebUI 面板才能撤销。而 MarriageEngine.wed 只校验"存在已接受意向", 不校验双方是否同处一地、也不要求当场再确认, 于是求婚方可以在几小时后的任意时刻远程 /marriage wed 兑现, 从对方钱包扣走一半典礼费 (默认 10000 CREDIT) 并消耗其订婚戒指。此外该表随玩家退出常驻内存, 直到进程重启才清空。
- **建议**: 补一条 /marriage reject <proposer> 与命令层的撤回路径 (复用 proposals.clear); 给 Proposal 加一个 gameTime 时间戳与过期窗 (例如数分钟), 典礼前复核未过期; PlayerLoggedOutEvent 里顺手清掉登出者的 outgoing 意向。
- **复核**: 未复核 (原报 Minor)


#### F099 · 青辉石按合格人头复制发放而非瓜分, 与信用点池的反人头复制口径相反

- **维度**: 缺陷 | **严重度**: Major -> Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionRewardHandler.java:153`
- **证据**: 结算循环里信用点走 `ContributionPool.distribute` 的加权瓜分 (payout 的 value 是按有效伤害占比切分的份额), 但青辉石是 `EconomyServices.economyService().grantAzureDaily(player, azureAmount, AZURE_DAILY_FAUCET_CAP)` —— azureAmount = ChampionReward.azureDrop(star) 是与人数无关的常量 (8★ = 6)。合格门槛见 ContributionPool:60-62: 个人有效伤害 ≥ BOSS 总有效血 0.5% 或 ≥ 团队人均 15%, 取一即合格。8★ 基础有效血 27000 (StarRank:29), 经 ChampionHpConversion 的 HP_FLOOR=0.35 下限后最低约 9450, 0.5% 门槛即约 47 点伤害。EconomyConstants.AZURE_DAILY_FAUCET_CAP = 30, 是每账号每日上限。
- **影响**: 一次 8★ 击杀设计产出 6 青辉石; 带 9 个小号各对 BOSS 补约 47 点伤害 (远低于任何有意义的贡献), 同一次击杀实际产出 60。每个账号有独立的 30/日上限, 于是全服青辉石供给被"小号数量"线性放大。叠加本项目既有的物品自由转移, 产出可无损归集到一人 —— 正是跨账号洗额度的标准形态。信用点侧已用加权瓜分堵死了这条路, 青辉石侧没堵。
- **建议**: 让青辉石与信用点走同一个分配语义: 要么整只怪固定产出 N 颗按合格者权重取整瓜分 (余数处理与 ContributionPool 一致), 要么保留人头发放但把单次掉落量按合格人数递减。判定前需先确认设计意图 (spec 第十一章对青辉石是"每人掉落"还是"整池瓜分"没写死), 这是需要人拍板的一步。
- **复核**: 维持 — 代码事实成立, 但审计员给的危害机制被推翻, 故从 Major 降到 Minor。成立的部分: ChampionRewardHandler:119 信用点确实走 ContributionPool.distribute (ContributionPool:129-141 按有效伤害加权瓜分, 类注释明写『严禁按人头复制』), 而 :153-156 青辉石对 payout 里每个合格者各发一份与人数无关的常量 ChampionReward.azureDrop(star) (ChampionReward:56-62, 8★ = 2+(8-6)*2 = 6)。门槛口径也核对无误: ContributionPool:60-62 双门槛取一, StarRank:29 8★ 基础有效血 27000, ChampionHpConversion:27 HP_FLOOR=0.35, 故 0.5% 门槛在 47-135 点伤害之间, 在 80 血 + TACZ 高 DPS 环境下形同虚设。本类自己 :86 的注释写着『整池不发, 防按人头复制』, 青辉石分支恰恰在做人头复制, 属自相矛盾。被推翻的部分 (核心): 审计员的 impact 建立在『物品自由转移 -> 无损归集到一人 -> 跨账号洗额度』上, 但青辉石根本转不动。Currency.java:21 AZURE(false), :11-13 类注释明写『硬绑定玩家不可转移不可交易, 从根上堵死 RMT 通道』; MarketEngine.java:88-91 从挂单源头拒绝非 CREDIT 计价, MarketConstants:69 同; MarketTradeWhitelist.java:31 明写青辉石是纯账本余额没有对应注册物品。我进一步查了青辉石的两个出口, 全是账号绑定的死胡同: (a) 塔罗闪耀卡包 (TarotPackService:64/69 用 AZURE) 开出的牌经 PackGachaService:159-160 -> TarotCardItem.create 强制盖 ownerUUID (TarotCardItem:59-71 owner 为 null 直接抛), 用牌与合成都过归属门 (TarotCraftService:73、TarotCraftMenu:174), 卖给别人打不出效果; (b) 开箱 (CaseOpeningConfig.AZURE_COST) 产出是 DB 里的 SkinAssetRow, CaseOpeningService:193 按 player.getUUID() 查归属, :214-217 enforceGunStack 一旦持枪者不是资产主人就剥皮肤。所以小号刷到的青辉石只能烂在小号手里, 不存在归集。另一个降级理由: 全服青辉石供给的天花板由 EconomyConstants:106 的每账号每日硬上限 30 决定, 与单次掉落是人头发还是瓜分无关 —— 瓜分只是让人撞顶更慢, 上限本身在两种设计下都是 30 × 账号数。残留的真实问题只剩『打 47 点伤害的蹭枪者与打 26000 的主力拿一样多青辉石』这一条公平性/口径不一致, 值得拍板但不是印钞口。


#### F100 · 16 个 handler 各自独立做全维度×全玩家×48 格 AABB 实体扫描, 且 14 个同余同相位挤在同一 tick

- **维度**: 性能 | **严重度**: Major -> Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionParticleHandler.java:50`
- **证据**: 本文件 50-64 是这套范式的样板: `for (ServerLevel level : server.getAllLevels())` → `for (ServerPlayer player : players)` → `player.getBoundingBox().inflate(48.0)` → `level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)`, 之后才用 capability 过滤出冠军。同一形状在 16 个 handler 里各写一份 (BossBar:77, SelfEffect:130, VisualDisruption:99, CounterUnit:114, Summon:132, DeathMark:113, Blink:155, TacticalBlink:107, ElectroCharge:195, Thunder:169, LittleBoy:179, CaesarSwap:191, BladeWaltz:283, Size:269, PhaseWalk:204)。门控一律 `server.getTickCount() % SCAN_INTERVAL_TICKS == 0`, 而常量实测: 14 个 = 20 (BladeWaltz/Blink/CaesarSwap/ElectroCharge/PhaseWalk/TacticalBlink/Thunder/VisualDisruption/CounterUnit/Summon/Size 直写 20, DeathMark = ChampionDeathMarkMath.TICKS_PER_SECOND=20, LittleBoy = ChampionLittleBoyPlan.TICKS_PER_SECOND=20, SelfEffect = ChampionSelfBuffValues.HEAL_TICK_INTERVAL=20), BossBar=10, Particle=5。没有任何一个 handler 有"全服无该词条冠军就整体早退"的守卫。
- **影响**: 每名玩家每秒承受约 20 次 96×96×96 的实体区间查询 (14×1 + 10 的 2 次 + 5 的 4 次); 满编 40 人即每秒约 800 次。因 20 的倍数必同时是 10 和 5 的倍数, 每秒有一个 tick 上全部 16 个 handler 同时开扫 (约 640 次查询挤在一帧), 表现为稳定的每秒一次 tick 尖峰。扫描还覆盖 getAllLevels 的全部维度, 包括根本不会有冠军的主世界。
- **建议**: 把"附近冠军集合"抽成每 tick 至多算一次的共享快照 (一次扫描 + capability 过滤, 各 handler 订阅结果), 或至少让各 handler 从 MiningChampions 侧维护的在册冠军索引反查而非从玩家 AABB 正扫; 顺带给各 handler 的 % 门控错开相位 (如 `(tickCount + 固定偏移) % 20`), 并按维度白名单跳过无冠军的世界。
- **复核**: 维持 — 所有事实逐条核对无误, 但因无功能性影响且开销量级达不到 Major, 降级。核实结果: (1) 扫描范式确为 16 份独立实现, grep 命中的 getEntitiesOfClass + player.getBoundingBox().inflate(VIEW_RANGE) 组合精确落在 Particle:57-58、BossBar:76-77、SelfEffect:129-130、VisualDisruption:98-99、CounterUnit:113-114、Summon:131-132、DeathMark:112-113、Blink:154-155、TacticalBlink:106-107、ElectroCharge:194-195、Thunder:168-169、LittleBoy:178-179、CaesarSwap:190-191、BladeWaltz:282-283、Size:268-269、PhaseWalk:203-204, 一个不多一个不少。(2) VIEW_RANGE 逐个核实全部 = 48.0D (16 处 private static final double VIEW_RANGE = 48.0D), 即 96×96×96 盒子。(3) 相位对齐属实: 14 个 SCAN_INTERVAL_TICKS 实测全为 20 —— CounterUnit/Size/Summon 直写 20, DeathMark = ChampionDeathMarkMath:40 TICKS_PER_SECOND=20, SelfEffect = ChampionSelfBuffValues:32 HEAL_TICK_INTERVAL=20, LittleBoy = ChampionLittleBoyPlan:28 TICKS_PER_SECOND=20, 其余六个取各自 Plan 的 SCAN_INTERVAL_TICKS 也都是 20L (BladeWaltzPlan:45 / BlinkPlan:32 / CaesarSwapPlan:32 / ElectroChargePlan:31 / PhaseWalkPlan:39 / TacticalBlinkPlan:32 / ThunderPlan:36 / VisualDisruptionValues:30); BossBarHandler:51 = 10, ParticleHandler:34 = 5。20 的倍数必同时是 10 和 5 的倍数, 故每秒确有一 tick 上 16 个 handler 同时开扫。(4) 无全局早退守卫属实: 我逐个读了 BossBar:59-90、Particle:42-66、SelfEffect:112-137、Size:240-277, 唯一的过滤是 level.players().isEmpty() 就 continue, 没有任何『全服无冠军/本维度无冠军就整体跳过』的判断, 主世界照扫不误。(5) 降级理由: 纯重复劳动, 零功能性影响; 每玩家每秒 20 次区间查询、峰值 tick 16 次, 40 人满编也就是每秒一次数毫秒级的尖峰, 且 1.20.1 的 EntitySectionStorage 只遍非空 section。这是架构洁癖级的性能欠债 (16 份同形状扫描该收口成一份共享快照), 应该修, 但够不上 Major。审计员给的 suggestion (共享快照 + 相位错开 + 维度白名单) 方向正确。


#### F101 · 6★+ 拦死分支预记的"最后一击"与 RewardHandler 的常规记账重复, 致命击被计入贡献两次

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionBloodPoolHandler.java:141`
- **证据**: 拦死分支注释 (135-140) 的前提是"kill() 内部走 hurt(...MAX_VALUE) 会同步重入 LivingHurtEvent 紧接 LivingDeathEvent", 故在 kill 前补记一笔 `ContributionTracker.record(victim, killer, incoming, nowTick)`。但对照 1.20.1 原文: LivingEntity.kill() (260-262) = `this.hurt(this.damageSources().genericKill(), Float.MAX_VALUE)`, 而 LivingEntity.hurt (1058) 在 1064 行 `else if (this.isDeadOrDying()) return false;` —— 本文件 150 行刚执行过 `victim.setHealth(0.0F)`, isDeadOrDying() 即 getHealth()<=0 (1054-1056), 所以 151 行的 kill() 是纯 no-op, 不会重入。真正的 die() 由外层 vanilla hurt 的 1175-1182 行在本次 LivingHurtEvent 派发【全部结束之后】才触发。此时 ChampionRewardHandler.onChampionHurt (LOWEST + receiveCanceled=true, ChampionSystem 注册序 77 在血池 handler 76 之后, 同优先级按注册序 FIFO) 已经用同一个 event.getAmount() 记过一次同样的账 —— 血池分支只 setCanceled 没改 amount, 两笔金额完全相同。
- **影响**: 每一只 6★+ 冠军的击杀者, 其贡献权重都被多算一次致命击。固定信用点池按权重瓜分, 末击者份额系统性偏高、其余参战者被稀释; 边缘情形下一个自身伤害刚好差一点的末击者会被这次重复计数推过 0.5% 盖章门槛, 从蹭枪变成合格瓜分者。
- **建议**: 预记那段可以整体删掉 (前提已不成立), 让 RewardHandler 的常规记账独占这笔账; 若要保留防御性预记, 就得在 RewardHandler 侧对同一 tick 同一 (冠军, 玩家) 做幂等去重。顺带把 150-151 两行的顺序与注释修正 —— 现在的 setHealth(0)+kill() 之所以还能死, 靠的是 vanilla hurt 尾部的 isDeadOrDying 兜底, 不是注释描述的路径。
- **复核**: 未复核 (原报 Minor)


#### F102 · AOE 免疫缓冲在本次伤害已被自己掐 0 时仍无条件续窗, 免疫期可被链式延长

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionElectroChargeHandler.java:301`
- **证据**: `player.hurt(source, dmg); AoeImmunityBuffer.grant(player);` —— grant 不看 hurt 是否真的落地。AoeImmunityBuffer.grant (73-76) 直接 `LEDGER.grant(uuid, nowTick + 40)` 覆盖到期 tick; 而 onLivingHurt (106-128) 在 HIGHEST 对缓冲中玩家的一切【冠军直接伤害】setAmount(0)+setCanceled, 豁免表 (186-190) 只放行 magic/champion_execution/champion_thorns, 冠军近战不在豁免内。ChampionThunderHandler:294 与 ChampionLittleBoyHandler:312 是同样写法。本文件 277-278 的注释已经意识到"若玩家已在上一发缓冲窗内则首发即被掐 0, grant 只续窗", 但把它称作幂等 —— 实际是把到期点推到新的 now+40。
- **影响**: 玩家只要持续待在 AOE 落点圈里, 每 ≤2s 一发的 AOE 即便零伤也会把免疫窗刷到 now+2s, 窗内该玩家对全部冠军近战与技能伤害免疫。多只带电磁/天雷的冠军同场时, "站在爆点里不动"反而成为最优解, 与这些技能"锁定落点可躲"的设计意图完全相反。
- **建议**: grant 改为只在本次伤害真正结算生效时才开窗 (看 hurt 返回值, 或先查 isBuffered 再决定是否续), 让被掐 0 的那一发不产生续窗效果; 窗口语义上应是"挨了一发真伤害后的保护", 而不是"被 AOE 覆盖就续期"。
- **复核**: 未复核 (原报 Minor)


#### F103 · ContributionTracker.drain 的"按首伤 tick 排序保确定性"注释与代码不符, 实际是哈希序

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/reward/ContributionTracker.java:80`
- **证据**: 注释写"保持稳定迭代序: LinkedHashMap 重建 (ConcurrentHashMap 无序; 用首伤 tick 排序保确定性)", 但代码只有 `Map<UUID, Accum> ordered = new LinkedHashMap<>(perPlayer);` 一行, 没有任何按 firstHitTick 的排序; perPlayer 是 ConcurrentHashMap, 拷进 LinkedHashMap 只是把哈希桶顺序固化下来。下游 ContributionPool.distribute:129-140 把 round 余数全部塞给"最后一名合格者"(`share = fixedPoolRaw - distributed`)。
- **影响**: 余数归属由 UUID 哈希顺序决定, 同一场战斗换个玩家 UUID 结果就不同, distribute 声称的可复现性不成立。另外当合格人数多且哈希序末位的权重极小时, `fixedPoolRaw - distributed` 可能算出负数, 被 ChampionRewardHandler:144 的 `raw > 0L` 跳过, 于是实发总额略超固定池 —— 违反 distribute 自述的"保证 Σ应得 = fixedPoolRaw"不变量 (偏差为个位数信用点)。
- **建议**: 要么把注释承诺的排序真正补上 (按 firstHitTick 再按 UUID 兜底排), 要么把注释改成实话; 同时余数应归给权重最大者而非序列末位, 并对末位份额取 max(0, ...) 防负。
- **复核**: 未复核 (原报 Minor)


#### F104 · 凯撒换位位移玩家前未查落地保护窗, 波0 约定的"位移类效果自查"在这条路径缺失

- **维度**: 缺陷 | **严重度**: Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/integration/ChampionCaesarSwapHandler.java:304`
- **证据**: executeSwapOrAbandon 只做了双向 KnockbackSafetyGuard.evaluateLanding (281-284), 通过后直接 `target.teleportTo(level, champPos.x, champPos.y, champPos.z, playerYaw, playerPitch)`, 换位后才补 PlayerLandingProtection.grant (309)。而 PlayerLandingProtection 类注释 (29-32) 明写: 不经 LivingKnockBackEvent 的位移拦不住, 故约定"位移类效果动手前先查 isProtected"。全库 grep isProtected 的生产调用点只有 ChampionAttackHandler:399 一处 (混沌击飞), 换位这条路径没有。
- **影响**: 刚被混沌击飞或上一次换位挪到安全落点、仍处 2s 抗位移窗内的玩家, 会被凯撒换位立刻再强制传送一次。落点虽经守卫判 SAFE 不至于送进岩浆, 但红线 6"落点后 2s 内不再被二次位移"的保护在这条路径上形同虚设, 玩家表现为连续两次被拽走、完全失去落点后的操作窗。
- **建议**: 在 executeSwapOrAbandon 的落点裁决之前加一道与 ChampionAttackHandler:399 同款的 isProtected 早退 (受保护则本次放弃换位, CD 处理与现有 ABANDON 分支一致); 更彻底的做法是把这个自查收进一个统一的"玩家位移准入"入口, 避免今后每加一个位移词条就漏一次。
- **复核**: 未复核 (原报 Minor)


#### F105 · 6★+ 受击→拦死→死亡→奖励发放这条主链没有任何实体级测试, 奖励与血池测试全是纯函数单测

- **维度**: 功能缺口 | **严重度**: Minor | **审计域**: 精英怪 (35 词条 + 10 星级 + 自定义血池) — src/main/java/com/miningdim/champion
- **位置**: `src/main/java/com/miningdim/champion/ChampionRewardBloodPoolGameTests.java:1`
- **证据**: 该文件 20 个 test 方法全部直接构造 DamageContribution / BloodPool / BloodPoolRegistry 调纯函数断言 (bloodPoolContinuousHitsAccumulate:48, stampThresholdTwoOnlyQualifies:209, weightedRoundingRemainderAbsorbedByLast:320, registryInstallSameUuidOverwrites:428 …), 无一处 spawn 真实体、触发 LivingHurtEvent、断言 LivingDeathEvent 是否 fire 或检查 ContributionTracker 的最终账面。对比 ChampionAttackGameTests:338-356 —— DoT 致死那条链就用 `boolean[] deathFired` 挂真 LivingDeathEvent 监听器做了端到端断言, 说明这套手法在本仓是现成的, 只是没用在血池拦死上。
- **影响**: 本报告第 5 条 (致命击双记 + victim.kill() 恒为 no-op) 正是因为缺这条端到端回归而长期未被发现: 单测只验"wouldDieFrom 的数学", 验不到"拦死之后账本里到底是几笔"。今后任何对受击优先级、拦死顺序、handler 注册序的改动都没有回归网兜底。
- **建议**: 补一条 GameTest: spawn 一只挂了 6★ capability + 血池的怪, 用真玩家/假攻击者打出致死伤害, 断言 (a) LivingDeathEvent 确实 fire, (b) 该冠军的 ContributionTracker 账本在 drain 后为空, (c) 单一攻击者场景下记入的总伤害等于实际打出的总伤害 (删掉预记那段则该断言必挂)。
- **复核**: 未复核 (原报 Minor)


#### F106 · 开枪路径每颗子弹打 2 次 enforce 共 4 条主线程 SQLite 点查, 且每次都重新 prepare 语句

- **维度**: 性能 | **严重度**: Major -> Minor | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/caseopening/CaseTaczEventHooks.java:30`
- **证据**: CaseTaczEventHooks.java:29-41 用 HIGHEST 优先级同时订阅 GunFireEvent 与 GunShootEvent, 两者都调 stripUnauthorized -> CaseOpeningService.java:214-217 enforceGunStack -> CaseTaczBridge.java:47-72 enforce。enforce 在枪的 display 命中 isCaseDisplay 后走两次同步 SQLite: :59 dao.findOwnedAsset (CaseDaoSqlite.java:202-213 SELECT * FROM skin_assets ...) 与 :65 settledOwnership.test(asset) -> CaseOpeningService.java:469 -> SqliteEconomyLedger.java:391-410 SELECT ... FROM bundle_operations。DAO 层每次调用都 conn.prepareStatement 新建语句 (全仓无语句缓存), 即每颗子弹 4 次 SQL 编译 + 执行。CaseOpeningSystem.java:105-114 另有每 20 tick 一次的 enforceMainHand 走同一条链。全部跑在服务端主线程 (单连接单写者)。
- **影响**: 公服战斗环境正是 TACZ 全自动高射速 (10-15 发/秒/人)。一个用开箱皮肤的玩家扫射即 40-60 次主线程 SQLite 点查/秒; 团战 20 人同时开火可达每秒近千次, 直接吃 tick 预算并在交火瞬间抖动。而开箱皮肤是旗舰付费外观, 用它的恰好是最活跃的战斗玩家。
- **建议**: 归属校验不该落在每发子弹上: 把 (assetId -> 是否本人已结清) 的判定结果缓存进内存 (以 assetId + ownerUuid 为键, 开箱提交/退款/隔离时失效), 开枪路径只读缓存; 或把归属判定收敛到低频事件 (GunDrawEvent + 换手 + 降频 tick), 射击事件不再查库。DAO 侧同时应复用 PreparedStatement, 避免每次点查都重新编译 SQL。
- **复核**: 维持 — 代码事实全部属实, 但量级不到 Major, 降级。属实部分: CaseTaczEventHooks.java:24-41 确实以 HIGHEST 同时订阅 GunDraw/GunFire/GunShoot 三个事件, 后两个每次射击都走 stripUnauthorized -> CaseOpeningService.java:214-217 -> CaseTaczBridge.java:47-72; enforce 在命中 isCaseDisplay 后确实做两次同步查询 (:59 dao.findOwnedAsset -> CaseDaoSqlite.java:202-213; :65 settledOwnership.test -> CaseOpeningService.java:469 -> SqliteEconomyLedger.java:391-410); 两个 DAO 都是每次 conn.prepareStatement 新建语句, 全仓确无语句缓存; CaseOpeningSystem.java:105-114 另有每 20 tick 一次的同链调用。降级依据 (审计员没查的两点): (1) 两条查询都是主键/唯一列上的单行点查 —— MiningSchema.java:89 skin_assets.asset_id 是 PRIMARY KEY, :120 bundle_operations.operation_id 是 PRIMARY KEY, 不存在全表扫; (2) MiningDb.java:76-84 的 PRAGMA 是 journal_mode=WAL + synchronous=NORMAL, 纯读路径不触发任何 fsync, 命中的是页缓存。按 xerial 驱动一次 prepare+step+close 数十微秒估算, 20 人同时全自动的极端场景约 1000 次点查/秒, 折合每秒个位数毫秒主线程时间 (每 tick 亚毫秒), 达不到'吃掉 tick 预算'。另外这条路径还有一道天然收窄: CaseTaczBridge.java:49-56 对非枪、非 miningdim:case_*_display 的栈直接 return, 零 SQL, 故只有正持开箱皮肤枪的玩家付这个成本。结论: 真实的可优化点 (归属判定不该挂在每发子弹上, PreparedStatement 该复用), 但按本仓公服口径只够 Minor。


#### F107 · 登录恢复遍历该玩家全部历史开箱行, 每行一个独立事务, 随开箱次数无上界增长

- **维度**: 性能 | **严重度**: Major -> Minor | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/caseopening/CaseOpeningService.java:159`
- **证据**: CaseDaoSqlite.java:142-144 recoverableOpenings 的 WHERE status IN ('RESERVED','DEBITED','COMMITTED','REFUNDED') 含 COMMITTED, 而 COMMITTED 是开箱成功后的永久终态, 从不迁出。CaseOpeningService.java:159-172 recoverFor 对每一行都调 resume(:239-263), 每行至少 requireOpening(1 次 SELECT) + validateIdentity + requireAsset(1 次 SELECT) + economy.inTransaction 内的 charge(1 次 SELECT) 与 complete(1 次 SELECT + 1 次 UPDATE), 即每行一次 BEGIN/COMMIT 加 4-5 条语句, 全部主线程。触发点 CaseOpeningSystem.java:87-103 的 PlayerLoggedInEvent, 且 open() :117 每次开新箱前也会走一遍 (首次未审计时)。CaseOpeningService.java:375-393 reconcileAtStartup 同理对 allRecoverableOpenings (全服所有历史行) 每行一次 economy.state 点查。
- **影响**: 重度玩家的登录耗时随其历史开箱总数线性增长且永不回落: 开过 300 箱的玩家每次登录要跑约 1500 条 SQL 加 300 次事务提交, 全在主线程, 表现为登录瞬间全服卡顿; 服务端启动期的全量对账同样随全服累计开箱数线性增长。case_openings 表也永无归档, 只增不减。
- **建议**: recoverFor 只该捞真正未结清的行: COMMITTED 且账本已 COMPLETED 的行应由 SQL 直接排除 (给 case_openings 加终态列或建 settled 索引), 而不是捞回 Java 层逐行开事务重放。同理 reconcileAtStartup 应改成一次联表查询定位不一致行, 而非逐行点查账本。
- **复核**: 维持 — 结构性事实成立但影响被显著夸大, 降级。属实部分: CaseDaoSqlite.java:139-144 recoverableOpenings 的 WHERE 状态集合确实含 COMMITTED 这一永久终态 (markSold 之外无任何迁出路径), CaseOpeningService.java:159-172 对每行都进 resume(:239-263), COMMITTED 分支每行一次 economy.inTransaction; 每行代价确为 requireOpening + requireAsset + state(:168) + charge 内 findOperation + complete 内 findOperation 共约 5 条语句; reconcileAtStartup(:375-393) 同样对 allRecoverableOpenings 每行一次 economy.state; case_openings 表确无归档机制。降级依据: (1) 在账本行仍在的正常态下, 这 5 条全是主键点查且【零写入】—— charge 在 SqliteEconomyLedger.java:122-128 命中 existing 即返回不写, complete 在 :161-165 见 COMPLETED 直接返回不 UPDATE; StoreTx.java:23-41 的空事务在 WAL + synchronous=NORMAL (MiningDb.java:78-82) 下不产生 WAL 帧也不 fsync, 所以'每行一次事务提交'并不等于每行一次落盘。300 箱玩家约 1500 条点查, 量级是几十毫秒的一次性登录抖动, 不构成'全服卡顿'。(2) 触发面比描述窄: recoverFor 由 recoveryAuditedPlayers(:154-156) 守着, 每进程每玩家只全量跑一次, open() 的 :117 调用第二次起即刻短路。真正致命的不是这条 perf, 而是同一段代码在账本行被回收后变成重复扣款 (即第 1 条 Critical); 把 COMMITTED 留在恢复集合里的代价主要体现在那里, 单看性能只值 Minor。


#### F108 · case.state 对玩家每一件已拥有皮肤各打一次账本点查, 60 条上限只截回执不截查询

- **维度**: 性能 | **严重度**: Minor | **审计域**: 经济闭环 (账本/市场/开箱/存储): com.miningdim.economy / market / caseopening / store / persistence
- **位置**: `src/main/java/com/miningdim/caseopening/CaseOpeningService.java:96`
- **证据**: CaseOpeningService.java:96-98 ownedAssets = dao.ownedAssets(uuid).stream().filter(this::isEconomySettled).toList(), 而 isEconomySettled(:469) 每次都是一条 SELECT FROM bundle_operations。CaseWebUiActions.java:37-41 的 case.state 先取全量 owned 再 :138-141 ownedSlice 只对【回执】截到 OWNED_RESPONSE_LIMIT=60, 过滤阶段的点查次数仍等于该玩家资产总数。这条 action 无冷却, 面板每次刷新都会打。
- **影响**: 资产多的玩家每开一次开箱面板就是几十到几百次主线程点查; 与上面的登录恢复叠加, 同一批数据在一次会话里被反复点查。当前量级不致命, 但随开箱系统运营时间单调恶化。
- **建议**: 改成一次联表查询 (skin_assets JOIN bundle_operations ON source_opening_id=operation_id AND status='COMPLETED') 直接在 SQL 侧过滤并分页, 不要把过滤留在 Java 流里逐行点查。
- **复核**: 未复核 (原报 Minor)


### A.4 复核推翻 (误报, 4 条)


#### F109 · 插板/等离子盾/纳米盾三套减伤全部绕开 PlayerDamageReduction 单点结算, 逃出 85% 全局减伤帽

- **维度**: 缺陷 | **严重度**: Major -> 推翻 | **审计域**: 军械与铸甲 (job/munitions 含枪匠链 + job/engineer)
- **位置**: `src/main/java/com/miningdim/job/engineer/armor/PlateArmorDamageHandler.java:47`
- **证据**: PlateArmorDamageHandler 在 EventPriority.LOW 直接 `event.setAmount((float) output)`; PlasmaShieldHandler.onLivingHurt (shield/PlasmaShieldHandler.java:73) 与 NanoShieldHandler.onLivingHurt (effect/NanoShieldHandler.java:41) 同样各自 setAmount。而全库唯一的减伤总闸 PlayerDamageReduction (combat/PlayerDamageReduction.java:78-104) 跑在 LOWEST, 用 keep = max(∏(1-rᵢ), CombatConstants.PLAYER_MIN_KEEP) 施加 PLAYER_MAX_REDUCTION=0.85 的全局帽 (CombatConstants.java:16)。grep `PlayerDamageReduction.register` 全库只有 BrewerSystem:61 / MinerSystem:73 / ChefSystem:62 三处, 工程师三套护具一处都没登记。插板 R 矩阵最高档 0.98 (armor/PlateArmorConfig.java:18 的 VI-heavy)。
- **影响**: VI 重插板先把弹道段打到 2%, 随后 LOWEST 的职业减伤再对这 2% 乘一个被钳到 0.15 的 keep, 合计等效减伤 99.7%, 远超设计写死的 85% 上限。在 80 血 + TACZ 高 DPS 的公服上, 这等于一件胸甲让 PvP 变成不可击杀; 而全局帽存在的意义 ("无法施加等效总减伤上限"正是该类注释给出的建立理由) 被完全架空, 且这条叠加不可审计 —— sourceCount() 看不到它。
- **建议**: 把三套护具的减伤率折成 ReductionSource 注册进 PlayerDamageReduction 统一连乘+钳帽; 若插板确需独立于职业帽 (Tarkov 语义), 也必须显式定义"护具帽 × 职业帽"的合成上限并写进 CombatConstants, 不能靠优先级顺序默认逃逸。
- **复核**: **推翻 (误报)** — 代码事实对, 但定性被三条独立证据推翻。(一) 这是写进设计文档的明确规则而非遗漏: docs/Armorer_Armor_System_DesignSpec.md:164 原文 —— 排除表示插板不减伤, 同时不会退回原版护甲值或韧性。它仍可能受到抗性、职业减伤、保护附魔等其他独立层影响。该行为是第一版的明确规则, 不是遗漏。3.5 节 (162 行) 同时说明插板会把原版护甲/韧性归零来防叠甲, 说明分层是设计而非逃逸。(二) 代码里有对应的显式意图注释: PlateArmorDamageHandler.java:12 —— LOW 保证先接收冠军 HIGH 重写与易伤 NORMAL 放大, 再把结果交给 LOWEST 职业减伤; 优先级是刻意选的, 不是默认逃逸。R=0.98 也不是失控值, 与 Armorer 规格 4.1 表 (docs/...:181, VI 重型 98%) 逐格吻合。(三) 标题的三套叠加前提事实错误: PlateArmorDamageHandler.java:32 与 PlasmaShieldHandler.java:63 都取 EquipmentSlot.CHEST, 插板与等离子盾天然互斥; NanoShieldHandler.java:35 更是显式 —— 穿了插板或等离子盾就 return, 纳米盾一律停用。三者同一时刻最多生效一套, 不存在三层连乘。另外 PlayerDamageReduction 的类注释 (combat/PlayerDamageReduction.java:14) 把该注册表的语义限定为各职业的命名减伤源 (凝脂/矿脉抗性/烈酒钝感), 装备层从来不在其管辖内 —— 按审计员的逻辑, 原版护甲与保护附魔同样在逃逸, 显然不是该帽的设计目标。残留的合理内核只有一条: 护甲层 x 职业层的合成上限确实没在 CombatConstants 里写死, 那是数值调优议题 (Armorer 规格 3.4 节 158 行已列出验收口径), 不是代码缺陷。


#### F110 · 跳蚤市场左栏分类树默认展开全部顶层桶, 把整个物品注册表 (数千条) 一次性铺进 DOM 并为每条发起 CDN 贴图探测

- **维度**: 性能 | **严重度**: Major -> 推翻 | **审计域**: 前端 React/TypeScript (webui/src)
- **位置**: `webui/src/pages/market/BrowsePage.tsx:286`
- **证据**: CategoryTree 的默认展开集是全部顶层节点: `const expandedIds = expandedOverride === null ? new Set(nodes.map((node) => node.id)) : expandedOverride` (BrowsePage.tsx:285-286)。服务端 MarketCategoryTree.build() 遍历 `ForgeRegistries.ITEMS.getKeys()` 全量注册表, 每件物品产出一个叶子, 且叶子直接挂在所属桶的 children 下 (renderNode 把 leaves 追加进 children); bucketOf 只对 _ingot/_ore/gem/gun/ammo/armor/food 这几组关键词做特判, 其余全部落进 `other` 桶。而 weapons/ammo/gear/food/other 五个桶都没有子分支, 叶子就是它们的直接 children —— 默认展开即全部渲染。每个叶子渲染一个 `<ItemIcon itemId={node.itemId} .../>` (BrowsePage.tsx:265), 容器只有 `max-h-96 overflow-y-auto` (BrowsePage.tsx:842), 无任何虚拟化。同时 CategoryTree 调 `useItemNames(collectLeafLabels(nodes))` (BrowsePage.tsx:279), 而 useItemNames 在**每次渲染**都执行 `JSON.stringify(descriptionIds)` 当 effect 签名 (lib/i18n.ts:157)。
- **影响**: 玩家在游戏内点开"跳蚤市场"时: (1) market.categories 的回执包含数千条叶子 (原版 1.20.1 约 1.3k 件, 加 TACZ/Champions/farmersdelight/本 mod 246 件后是数千件), 该 JSON 由服务端主线程全量遍历注册表现场构造, 每次进入浏览页与每次点"刷新"(handleRefresh 调 categories.reload()) 各构造一次; (2) 前端一次性挂载数千个 button + 数千个 ItemIcon, 每个 ItemIcon 又对公网 CDN 发起 1-2 次 new Image() 探测 (见另一条 finding), Chrome 单域名 6 并发 → 排成数千深的队列; (3) 一次 client.i18n 携带数千个键跨 cefQuery 桥; (4) 每次 BrowsePage 重渲染 (改筛选/弹回执条/翻页) 都重新 JSON.stringify 这个数千元素的数组。MCEF 与 MC 渲染循环共享 CPU/GPU, 表现是打开市场页整个游戏客户端卡住数秒到数十秒。
- **建议**: 三处一起改: 默认只展开"空"(或只展开被选中项所在的那条路径), 不要默认展开全部顶层; 叶子列表加虚拟化或按分页/搜索裁剪后再渲染; useItemNames / ItemIcon 只对**当前可见**的叶子发起解析。更根上的做法是让 market.categories 支持按桶懒加载 (branch 只回子节点数, 展开时再拉该桶的叶子), 避免服务端每次全量遍历 ForgeRegistries.ITEMS。
- **复核**: **推翻 (误报)** — 前端侧的三条代码事实都属实: BrowsePage.tsx:285-286 默认展开集确为全部顶层节点; :842 容器只有 max-h-96 overflow-y-auto 无虚拟化; :279 对全树叶子调 useItemNames; lib/i18n.ts:157 每次渲染都 JSON.stringify。服务端 MarketCategoryTree.java:115-128 也确实遍历 ForgeRegistries.ITEMS.getKeys() 全量, weapons/ammo/gear/food/other 五桶的叶子直挂顶层 (renderNode :155-158)。  但描述的后果在生产里一次都不会发生, 被服务端的体积收口挡死: WebUiServerDispatcher.respond (WebUiServerDispatcher.java:147-156) 在下行前判 resultJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH (32767), 超限即整条换成 RESPONSE_TOO_LARGE 失败回执。而一片叶子的 JSON 形如 {"id":"i_minecraft_diamond","label":"item.minecraft.diamond","itemId":"minecraft:diamond"} 约 100 字符, 原版 1.20.1 单是物品注册表就 1300+ 条 (再加 TACZ/farmersdelight/本 mod 246 件), 回执必在 13 万字符量级 —— 整整超上限 4 倍以上, 且与玩家数据无关, 是**恒定**超限。所以 market.categories 在真服上永远返回失败, BrowsePage.tsx:832-837 走的是 ErrorBlock 分支, categories.data 永远拿不到, 数千 button / 数千 ItemIcon / 数千键 i18n 这三条放大链一条都触发不了。dev 下走 bridge.mock.ts:4796 的小种子树, 同样触发不了。  顺带报一条审计员没看到、比原 finding 更严重的真缺陷 (服务端范围, 不在本次前端复核的记分内, 但必须落账): market.categories 是本仓唯一没有自带上限的列表类 action —— 同仓其他列表 action 全部自设预算 (PlayerWebUiActions.java:55-59 名册 200 条上限并注明"不指望派发器的体积收口兜底"; ChampionWebUiGameTests.java:315-328 与 MunitionsWebUiGameTests.java:390 都专门断言不逼近 32767), 唯独 MarketActions.java:496 的 CATEGORIES 是裸 GSON.toJson(MarketCategoryTree.build())。现有 GameTest (MarketBridgeGameTests.java:234-270) 直接调 MarketCategoryTree.build() 断言树结构, 绕过了 respond() 的体积门, 因此是一条测不出这个缺陷的假绿。真实症状是玩家打开跳蚤市场后左栏恒显示"分类树读取失败: server response exceeded the downstream size limit"。


#### F111 · ServerStopping 只清了 castManager, 调度队列/冷却/战斗窗口/同意窗全部跨存档残留

- **维度**: 兼容性 | **严重度**: Major -> 推翻 | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/tarot/TarotSystem.java:260`
- **证据**: onServerStopping 只调 castManager.clear() 与 TarotRuntime.reset() (后者也只是把静态引用置 null 并再 clear 一次 castManager, TarotRuntime.java:48-60)。ScheduledEffectManager.tasks、TarotCooldownManager 的三张表、TarotCombatState 的 WINDOWS/CONTRACTS/BONDS/IMMUNITIES/RESTRICTIONS 等 9 个静态 Map、TarotConsentRegistry.CONSENTS 都没有清理入口被调用; 而 TarotSystem 实例由 MiningDim.registerSubsystems (MiningDim.java:122) 在 mod 构造期 new 一次, 整个 JVM 只有一份。所有时钟都用 MinecraftServer.getTickCount() (每次开服从 0 起)。
- **影响**: 单人/局域网宿主退出世界再进另一个存档 (同一 JVM), 旧世界遗留的窗口 endTick 远大于新世界的 tickCount, 于是无敌窗/免疫窗/复活契约/恋人绑定会在新存档里长时间生效 (旧 endTick=100200 即新世界前约 83 分钟一直无敌), 冷却表同理会把新存档的牌锁死; 更糟的是 ScheduledEffectManager 里遗留的恋人连死任务 (TarotCombatHandlers.java:245 的 p -> p.setHealth(0.0F)) 与周期真伤任务会在新存档到点执行, 表现为无来由暴毙。同包的 AgentWebUiActions.activePulse (AgentWebUiActions.java:361-371) 专门写了时钟回退自愈判据, 说明该风险已被识别, 塔罗侧只是漏做。
- **建议**: onServerStopping 里补齐: scheduler 增加 clear()、cooldown 全表清、TarotCombatState 加一个静态 clearAll()/reset()、TarotConsentRegistry.CONSENTS 清空; 或统一给这些运行期表加 '时钟回退即丢弃' 的判据 (与 AgentWebUiActions 同范式)。
- **复核**: **推翻 (误报)** — 推翻依据: 审计员说这些表'都没有清理入口被调用'与源码不符。TarotSystem.java:268-275 cleanup 里逐条清了 scheduler.cancelFor / cooldown.clear / TarotCombatState.clearAll / TarotConsentRegistry.clear, 而 cleanup 由 onLogout (219-223) 触发; 服务器停止时 PlayerList.removeAll 会对每个在线玩家触发 PlayerLoggedOutEvent, 因此关服/退出世界过程中所有在线玩家的这些表都会被清空。且 TarotCombatState 的 9 张 Map 全部以【玩家】UUID 为键 (137-253 行的 openXxx 一律接 ServerPlayer), clearAll (480-490) 一次覆盖全部 9 张, ReflectAccum 里的攻击者 UUID 也嵌在玩家键条目内; 不存在以怪物 UUID 为键、无人清理的条目。所以'旧存档 endTick 在新存档长期生效/恋人连死任务在新档暴毙'的后果链不成立。 反而这几行有一个方向相反的真缺陷 (读码时发现, 请另立条目): TarotRuntime.reset() (TarotRuntime.java:48-60) 把 8 个单例引用全置 null, 而 TarotRuntime.init 全工程唯一调用点是 TarotSystem.java:63 的 register (mod 构造期, 每 JVM 一次, 无任何 ServerStarting 重装配)。故同一 JVM 内退出第一个世界后, 再进任何存档时 TarotPlayHandler.java:32 (cardLoader().isLoaded()) 与 TarotWebUiActions.java:125 都会撞 TarotRuntime.require 抛 IllegalStateException, 塔罗整体不可用。同型问题也在特勤侧: AgentSystem.java:95 onServerStopping 调 AgentSealSeam.unbind() 后同样没有重新 bind 的入口。


#### F112 · 特勤接管精英死亡结算时漏掉 '召唤物不发奖' 经济闸, 修好探测源即变成印钞口

- **维度**: 缺陷 | **严重度**: Major -> 推翻 | **审计域**: 特勤与塔罗 (src/main/java/com/miningdim/job/agent + job/tarot)
- **位置**: `src/main/java/com/miningdim/job/agent/integration/AgentRewardHandler.java:60`
- **证据**: AgentRewardHandler.onChampionDeath 挂 EventPriority.HIGHEST 抢先 drain 贡献账本并自行完成整池瓜分 (line 86-120), 判据只有 isOurChampion + star>=1 + effectiveHp>0; 它接管的原结算 ChampionRewardHandler.onChampionDeath 里有一条它没有复制的闸: `if (champ.isSummonedByAffix()) { ContributionTracker.discard(championId); return; }` (ChampionRewardHandler.java:100-103, 注释标注 'spec 红线 8-a 经济闸: 整池不发')。而召唤出来的精英确实是盖章冠军 (ChampionSummonHandler.java:294 对召唤物 markSummonedByAffix, 本身仍走 applyChampion 盖章)。
- **影响**: 目前该 handler 因读第三方 champions capability 而恒在 isOurChampion 处早退, 所以这条漏闸尚未生效; 但经济审计给出的修复方向正是 '让 AgentChampionData 读自研 MiningChampions capability'—— 一旦照做, 特勤 handler 就成为全服精英死亡的权威结算点, 支援召唤刷出来的精英每只都会照发固定信用点池 (+6★ 起的青辉石)。SUMMON_SUPPORT 是可按 CD 反复召唤的技能词条, 等于开了一个可无限重复的战斗 faucet, 直接击穿红线 8-a。
- **建议**: 修 capability 契约的同一个补丁里必须同步补上召唤物闸 (以及后续 ChampionRewardHandler 新增的任何前置判据); 更稳的做法是让特勤侧不要另抄一份瓜分流程, 改为在原结算之后挂一个 '特勤加成' 钩子, 从根上消除两份实现分叉。
- **复核**: **推翻 (误报)** — 缺闸这个代码事实成立 (AgentRewardHandler.java:60-88 判据只有 hasLedger + isOurChampion + star>=1 + effectiveHp>0, 确无 ChampionRewardHandler.java:100-103 那条 isSummonedByAffix 整池不发), 但它声称的印钞后果被上游门彻底拦死, 即便照经济审计修好 capability 也不会发生: (1) AgentRewardHandler.java:63 第一句就是 !ContributionTracker.hasLedger 早退, 而全工程只有两处写账本 —— ChampionRewardHandler.java:80 (第 71-73 行已对 isSummonedByAffix 拒记) 与 ChampionBloodPoolHandler.java:144 (位于 pool != null 的致死分支内); (2) 召唤物星级被 ChampionSummonPlan.summonStar 钳在 [1,4] (ChampionSummonPlan.java:33-36,116-125, ChampionSummonGameTests.java:102-110 有断言), 4★ 基础有效血 540 (StarRank.java:25), 4★ 最高品质仅 UNCOMMON 故巨大化最多 ×1.5 = 810, 恒不破 ChampionPromoter.java:144 的 1024 阈值, 也不满足 usesCustomBloodPool (6★+), 因此召唤物永远不装影子血池, 第二条写账本路径对它同样不可达。结论: 召唤物身上永远没有贡献账本, AgentRewardHandler 连第 63 行都过不去, 既不会发信用点也谈不上'+6★ 起的青辉石'(召唤物根本到不了 6★)。这条缺闸只剩'两份瓜分实现分叉'的防御性一致性价值, 建议随第 10 条重构时补, 但不构成经济漏洞。
