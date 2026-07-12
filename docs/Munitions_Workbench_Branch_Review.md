# 军械工作台分支审查报告 (codex/munitions-workbench)

- 审查日期: 2026-07-06
- 审查范围: 分支相对 main (73a22a4) 的全部改动, 共 3 个提交
  - c0c3446 功能主体 (16 个 Java 文件 + 资源)
  - 3039232 merge origin/main
  - ff1aac4 搜索面板 + 品级标题 (仅 MunitionsBenchScreen)
- 审查方法: 6 维度并行审查 (WebUI 回退 / 服务端权威与 dupe / 方块放置破坏 / 客户端 Screen / 经济产出 / 资源与测试), 每个发现由独立验证代理对抗复核 (以反驳为职责, 对照 Forge 1.20.1 反编译源码逐环节验证); 另在隔离 worktree 实跑全量 GameTest 与 main 基线对照。
- 结论: **不可合并**。1 项 Critical, 11 项 Major, 6 项 Minor; 2 个 required GameTest 失败。

---

## 零. 测试实证 (硬性阻断)

main 基线: **425 个 required GameTest 全绿** (本机实跑)。

分支: **2 个 required 失败**:

```
benchsettlepreserveswindowwhenfeefails failed!
  after balance restored, the retained elapsed window is settled in one shot to 80 rounds, got 0
benchsettlechargesfeeandgrantsxp failed!
  online owner catch-up produces exactly 80 rounds (2 batches of 40), got 0
```

失败根因见 Major-1。提交前未运行 GameTest。

---

## 一. Critical

### C-1. 双格方块爆炸/凋灵双掉落 dupe

- 位置: `MunitionsBenchBlock.updateShape` (143-152 行) + `data/miningdim/loot_tables/blocks/munitions_bench*.json` (全部 6 份)
- 机制 (逐环节对照 Forge 1.20.1-47.x 反编译源码验证):
  1. `updateShape` 在搭档半块消失时返回 AIR;
  2. vanilla `Level.markAndNotifyBlock` 传播 shape update 前执行 `flags & -34`, 无条件剥掉 `UPDATE_SUPPRESS_DROPS(32)`;
  3. 级联走 `Block.updateOrDestroy -> level.destroyBlock(pos, true)`, **带掉落**销毁搭档半块;
  4. 该路径 LootParams 无 `EXPLOSION_RADIUS`, loot table 的 `survives_explosion` 条件缺参时无条件放行;
  5. 6 份 loot table 均无 vanilla 床那样的 `block_state_property part=head` 条件。
- 失败场景: 爆炸/凋灵破坏一台 -> 两个半块可各产一次掉落, 一台最多掉 2 个方块物品。TNC/TACZ 爆炸物环境下可稳定复现, 构成刷方块 dupe。
- 修复方向: loot table 加 `part=main` 条件 (对齐 vanilla bed loot); `playerWillDestroy` 清搭档半块时叠加 `UPDATE_SUPPRESS_DROPS`。

同根因 Minor: 创造模式拆台会凭空掉 1 个方块 (`playerWillDestroy` 138 行无 `player.isCreative()` 分流, 级联 destroyBlock 恒带掉落; vanilla 床有创造分流)。

---

## 二. Major

### M-1. settleManualCraft 恒返 true, 被动产线/离线补产整段死代码; 2 个 GameTest 必红

- 位置: `MunitionsBenchBlockEntity.settleManualCraft` (448-475 行) / `settleForOwner` (365 行)
- `settleManualCraft` 四条返回路径 (450/458/466/474) **全部 return true**, 无任何 false 路径; 而 `settleForOwner` 首行即 `if (settleManualCraft(owner)) return;`。368-446 行的被动结算体 (elapsed 追算 -> `MunitionsProduction.settle` -> 扣料 -> 入缓冲 -> 工费 -> 经验) 从 `serverTick` 与 GUI 打开帧两个入口均不可达, 成死代码。
- 产出模型实际被静默改成 "手动点按钮开工制作", 但:
  - 类 javadoc (五章 "玩家回来/区块加载时按流逝时间一次性补产") 仍描述旧模型, 文档与实现矛盾;
  - 分支自己把 4 个 settle 用例精心移植到新四件套料体系 (新增 stockParts/assertPartCounts), 却留着 2 个红测试提交 —— 说明并非有意废弃旧路径;
  - `MunitionsProduction.settle` 纯函数在生产环境同样不可达, 其配套纯函数测试绿着但测的是死代码。
- 修复方向: 二选一 —— 要么恢复被动结算 (settleManualCraft 空闲时返回 false), 要么正式废弃旧模型 (删死代码 + 删/改旧测试 + 改 javadoc + 过经济评审, 见 M-4)。

### M-2. 手动制造非原子: 取消/工费失败时已扣材料湮灭不退

- 位置: `tryStartCraft` (299-302 行, 开工帧即 consume 四件套) / `cancelCraft` (315-323 行) / `finishActiveCraft` (485-488 行)
- 两条中断路径均只 `clearActiveCraft()` 不退料: 玩家取消 -> 白丢一批料; 完成帧 `tryChargeWorkFee` 失败 -> 料与产出双没收, 且无保留重试窗口。
- 直接违反 main 侧 munitions-01 修复确立的契约 ("扣不动则料不扣、缓冲不增"; main 版 GameTest `benchSettleForfeitsBatchWhenBalanceShort` 明确断言此行为)。同文件内: 死掉的被动路径 (421-427 行) 遵守契约, 活着的手动路径违反契约, 两套结算纪律自相矛盾。
- 连续模式放大: `finishActiveCraft` 返回 void 不报失败, `settleManualCraft` 470-472 行无条件 `if (continuousCrafting) tryStartCraft(owner)` —— 余额不足时**逐批循环烧光整箱囤料**。
- 修复方向: 材料扣减移到完成帧与工费同帧结算 (先查后扣), 或取消/失败时退料; 连续模式在工费失败时停机。

### M-3. 制造发起者与结算对象错位: 跨账号绕等级门 + 路人 griefing

- 位置: `tryStartCraft` (283/306 行) / `finishActiveCraft` (477-497 行) / `cancelCraft` (315 行)
- 台子 `locked` 默认 false 且放置不自动上锁, `canAccess` 未锁恒放行; 菜单按钮 210/211/212 以**点击者**身份直通开工/取消/切模式, 均无 isOwner 校验。
- 错位链: 产量按点击者等级 (`craftingOwnerLevel`, 含提炼翻倍档判定), 但工费扣**台主**、经验入**台主**, 完成帧不复核台主等级是否解锁该口径 (391 行的复核在死代码里)。
- 失败场景:
  1. 高等级枪手在低等级台主的台上开工提炼档 (70 发/批), 台主等级根本没解锁 -> 绕过口径/产能等级门控;
  2. 任意路人对制作中的台点取消 -> 台主材料湮灭 (与 M-2 叠加构成零成本 griefing)。
- 修复方向: 开工/取消收敛到 owner (或至少产量按台主等级), 完成帧复核归属。

### M-4. 四种弹药零件无任何生存获取途径; 配方变更未过经济总表

- 位置: `ModMunitionsItems` (42 行起, primer/casing/bullet_head/propellant) / `MunitionsConfig` (铜/火药 cost 被删, 四零件 cost 下界 1)
- 配方从原版 7 铜 + 16 火药/批 (生存可采集) 换成四个 mod 专属零件, 但全库核查: 无合成配方 JSON (data 下 brewer/chef/farmer 均有 recipes 目录, munitions 没有)、无 loot、无市场挂牌、无事件发放、工作台内部无转化 —— **生存模式产线全灭**, 功能不完整。
- 同时新增 4 个可自由转移的中间物品, 按 `Economy_BalanceSheet_DesignSpec` 纪律, 任何新 faucet/可交易物必须先过全服收支总表评审; 结合已知的 "物品自由转移 + 收购按卖家计" 洗钱模式 (见 `Economy_Laundering_Review.md`), 零件流通面需要显式评估。
- 修复方向: 补零件获取链 (合成/加工/市场), 并将零件流通与工费 sink 变化并入经济总表评审。

### M-5. 旧注册名 munitions_bench 降为 tier1, 存量在役台无迁移被砍产能

- 位置: `ModMunitionsBlocks` (23-28 行)
- main 语义: 单一方块, 口径上限只由职业等级门控 (javadoc 明言)。分支把同一注册名注册为 tier1 (`maxEffectiveLevel=2`), 另加 5 个新档位方块。
- 区块按注册名持久化, 全库无 MissingMappingsEvent/datafixer/remap: 服务器升级后, 玩家已放置的台子被静默封顶 L2 —— `effectiveOwnerLevel` 夹断后过不了口径门, `settleForOwner`/`tryStartCraft` 直接清空已选口径, RIFLE 等在役配置全部失效。在役玩家资产无声贬值。
- 修复方向: 旧注册名保持最高兼容档 (或 datafixer 迁移到对应新档), 新档位用新注册名。

### M-6. 越界重写 WebUiClientSubsystem, 覆盖 main 修复并删光防御注释

- 位置: `client/webui/WebUiClientSubsystem.java` (整文件重写)
- munitions 功能分支无理由触碰 webui。分支从 d99e19e 之前分出并自行重写此文件, merge 3039232 时以分支版覆盖 main 版, 实际效果:
  1. main 上 d99e19e/5a17046 两个提交记录的实测崩溃反例注释 (unsafeRunWhenOn+双箭头防 CONSTRUCT 崩、safeRunWhenOn 触 SafeReferent 校验) 被删光;
  2. 同模块直接调用改成 `Class.forName + Method.invoke` 字符串反射, 丢编译期检查 (WebUiClient 方法改名时编译不报错, 运行期才 warn), 零收益。
- 功能等价性经验证大体成立 (dedicated server / 缺 MCEF / 正常客户端三环境行为一致), 问题在于越界、丢失知识沉淀与可维护性回退。
- 修复方向: revert 此文件回 main 版 (d99e19e); 若反射守卫确有价值, 单独分支提案。

### M-7. quickMoveStack 可绕过 mayPlace 把弹药合并进输出槽, 随后被覆盖销毁

- 位置: `MunitionsBenchMenu` (输出槽 index 4) + 基类 `AbstractMiningMenu.quickMoveStack` (玩家区 -> 容器区目标区间 [0,5) 含输出槽)
- vanilla `moveItemStackTo` 的**合并分支**只判 `isSameItemSameTags`, 不调 `mayPlace` (mayPlace 仅在空槽分支生效); Forge `SlotItemHandler` 无参 `getMaxStackSize()` 也不走 `isItemValid`。玩家 Shift 同种弹药 -> 合并进输出槽 -> `refreshOutputStack` 按缓冲重物化时直接覆盖 -> **玩家的弹药凭空销毁**。
- 修复方向: quickMoveStack 目标区间排除输出槽 (改为 [0,4)), 或输出槽改用带 isItemValid 模拟的自定义合并。

### M-8. 多人同开一台: OutputSlot 取弹快照只升不降, 交错取弹超额扣缓冲

- 位置: `MunitionsBenchMenu.OutputSlot.getItem` 快照刷新条件 (138-141 行) + `onTake` (152 行) + `MunitionsBenchBlockEntity.onOutputTaken` (646-656 行)
- 快照只在 "空/换类/数量增大" 时刷新; vanilla 每 tick `broadcastChanges` 会虚调用 getItem, 把第二名观看者的快照固定在历史最大值。玩家 A 取走部分后, 玩家 B 再取: `takenCount = 旧快照 - 残留` 严重高估 (验证场景: 算出 48, 实取 16), `onOutputTaken` 对传入量无实存钳制 -> 超额扣 `bufferedRounds` 并清 `bufferedCaliber`, 缓冲弹凭空蒸发。
- 修复方向: 取弹量以 `onTake` 传入栈 + 槽内实存差值在 BE 侧钳制结算, 不依赖 Screen 侧快照。

### M-9. 制造进度走 ContainerData 同步被截成 int16, 多档等级进度条恒空

- 位置: `MunitionsBenchBlockEntity.dataAccess` (147-157 行) 经 vanilla `addDataSlots` 同步
- `ClientboundContainerSetDataPacket` 的 value 读写均为 short (Forge 未 patch)。默认配置 L1 所需 ticks = 40 x 1440 = 57600 > 32767, 符号回绕为负 -> 客户端进度条恒空、剩余时间恒 0。
- 修复方向: 进度/所需 tick 拆高低位两个 data slot, 或换自定义 S2C 包。

### M-10. 新 UI 硬编码中文字面量, i18n 回退

- 位置: `MunitionsBenchScreen` (329/442/454/569-577 行等) / `MunitionsCaliber.Category` (55-59 行 "手枪弹/步枪弹/霰弹/狙击弹/爆破弹")
- main 侧该 Screen 全部 3 处用户可见文本走 `Component.translatable` (en_us+zh_cn 双语齐备); 分支重写后全部换成 `Component.literal` 中文, 原 3 个 lang key 成孤儿, en_us 客户端恒显中文。
- 修复方向: 全部回 translatable + 补双语 lang。

### M-11. 分支新增核心逻辑零测试覆盖, 且测试被改为绕开品级钳制

- 位置: `MunitionsGameTests`
- 品级钳制 (`effectiveLevelFor`)、手动制作全链 (tryStartCraft/finishActiveCraft/cancelCraft/连续模式)、双格放置破坏、六档注册 —— 全库测试零引用, 删掉钳制逻辑现有套件仍全绿 (违反 "删核心逻辑测试必须挂" 标准)。
- 尤其: 测试 helper `newBench` 被特意从基础台改成 `MUNITIONS_BENCH_HIGH` (max 6) —— 若沿用基础台, L5 替身被压到 L2, 旧测试全炸。这是**绕开**新逻辑保测试绿, 而非覆盖新逻辑。3 参 `newBench` 重载无人调用, 是死帮手。
- 修复方向: 按 M-1 定稿的模型补齐: 钳制边界 (等级>上限被夹)、非原子路径 (取消/扣费失败料的去向)、归属错位、双格破坏掉落数。

---

## 三. Minor

1. 创造模式拆台凭空掉方块 (见 C-1 附注)。
2. `getStateForPlacement` (104-114 行) 只查 `canBeReplaced`, 缺 `WorldBorder.isWithinBounds` 校验 (蓝本 BedBlock 有双重校验)。
3. 搜索面板/滚动条与输出槽几何重叠: SEARCH_PANEL (205,45,116x87) 面板体至 x321, 输出槽 (316,84) 物品矩形 316..332 重叠 5-8px, 滚动条画在槽位图标上。
4. 自绘按钮不过滤鼠标键位: `mouseClicked` 中制作/取消/连续/页签/口径按钮均不判 `button==0`, 右键/中键也触发真实服务端动作; 同文件 `handleSearchClick` (741 行) 却正确过滤, 自相矛盾。
5. 死代码: `drawSmoothTextCentered` (1024 行) 与 `trimSmoothText` (1079 行) 出生即无调用点, 约 21 行。
6. `registerBench` 的 unlockLevel 参数 (1/3/5/7/9/10) 全库无强制点: 放置只校验台数上限, 不校验玩家等级与方块档位, 属声明性死参数。

---

## 四. 对抗验证驳回项 (不计入, 供参考)

1. "搜索过滤每帧重复分配" —— 空查询时 `matchesSearch` 短路零分配, 分配仅在输入非空查询的瞬态窗口, 量级被高估 2-3 倍。
2. "invokeWebUiClient 吞 LinkageError 掩盖故障" —— JCEF 原生层故障经 MCEF 异步回调上报, 路由注册在 invoke 返回后执行, 声称的故障场景不经过该 catch。
3. "破坏工作台吞内容物" —— main 侧同样无 onRemove/掉落逻辑且连 loot table 都没有, 非本分支引入 (分支补 6 份 loot table 反而是改善); 缓冲上限 main/分支一致。

---

## 五. 流程与规范

1. 提交信息 `Add munitions workbench feature` 违反中文 Conventional Commits 纪律。
2. 提交前未跑 GameTest (2 个 required 红着进分支)。
3. munitions 分支夹带 webui 重写, 违反原子级修改纪律 (见 M-6)。

## 六. 合并前置清单

- [ ] C-1: loot table 加 part=main 条件 + 破坏路径抑制搭档掉落
- [ ] M-1: 结算模型二选一定稿, 删死路径, 2 个 GameTest 回绿
- [ ] M-2: 制造改原子 (完成帧扣料或失败退料), 连续模式失败停机
- [ ] M-3: 开工/取消归属校验
- [ ] M-4: 零件获取链落地 + 经济总表评审
- [ ] M-5: 旧注册名存量兼容方案
- [ ] M-6: WebUiClientSubsystem 回退 main 版
- [ ] M-7/M-8: 输出槽 Shift 合并与并发取弹修复
- [ ] M-9: 进度同步 int16 修复
- [ ] M-10: i18n 回 translatable
- [ ] M-11: 新逻辑测试补齐
- [ ] 分支上 UI 贴图/双格模型等资产保留

分支的 UI 资产 (贴图/双格模型/自绘字体图集) 与四件套配方的经济方向本身有价值, 建议按上述清单返工后再提合并。

---
---

# 增量审查 (2026-07-11): a2552da 枪匠冲压子系统

- 审查对象: 提交 a2552da "feat: 同步枪匠冲压与职业资源更新" —— 全新 gunsmith 子系统 (~2178 行 Java / 23 文件 / 331 文件总计): 冲压台方块+BE+Menu+Screen, AR 部件 x 5 品质档, M4 组装模板, TACZ 属性挂接; 另触碰 farmer 耕地方块与测试。
- 审查方法: 6 维度并行审查 (冲压台权威 / TACZ 集成 / 经济链路 / Screen / 数据模型注册 / 越界与测试), 每发现独立对抗验证; 全量 GameTest 实跑。
- 结论: 冲压子系统属 **WIP 半成品** (冲压台无合成配方, 输入材料物品未注册, 生存不可达), 但代码已 live 且带 1 项 Critical + 5 项 Major, 上一轮 12 项 finding **全部未修**。整分支维持不可合并。

## 零. 测试实证

- 首跑 3 个 required 失败; 甄别后 `ac1_twentyadultsheepmergetoone` 为 run/ 目录残留配置中毒**假红** (main 侧 6374974 修的正是此问题, 分支缺该提交; 清 run/world+config 后复绿)。
- 真实状态: 上一轮的 2 个 bench settle 红**依旧未修**; a2552da 自身零新增测试回归 —— 但也是因为 2045 行新子系统**零 GameTest 覆盖**。

## 一. Critical

### C-2. 冲压输入槽零物品身份校验 + 品质玩家直选: 垃圾物品压出 LEGENDARY 加伤部件

- 位置: `GunsmithPressBlockEntity.hasRequiredMaterials/consumeRequiredMaterials` (229-260 行) / `isItemValid` (58-61 行) / `GunsmithPressMenu` 输入槽 (50-55 行, 裸 SlotItemHandler)
- 三个输入槽 (GUN_PARTS/ALLOY/POLYMER) 只按 `getCount()` 判定与扣料, **完全不校验物品身份**; isItemValid 只拦输出槽; 名义材料 alloy/polymer/gun_parts 物品根本未注册。品质由玩家按钮直选 (`trySelectQuality` 无任何等级钳制, 可直选 LEGENDARY)。
- 产物链路已 live: 部件 NBT 系数经 `GunsmithTaczStatsHandler.onGunHurt` 第 57 行 `setBaseAmount(base * damage系数)` 真实乘算枪伤 (LEGENDARY 1.36~1.50), 部件 stacksTo(64) 可自由转移。
- 失败场景: 三槽塞足量鹅卵石 -> 选 AR + LEGENDARY -> 6 分钟产出合法顶级加伤部件, 集齐组装 1.5x 伤害 M4。无本印钞 + 战力叠叠乐, 双违 Economy_BalanceSheet 与职业哲学。
- 可达性说明 (不改变定级): 冲压台本体无生存配方, 当前经创造/OP/loot 自掉落传播; 但 loot table 已存在, 方块一旦流入生存世界漏洞即全开。两名独立验证者一驳一确认, 采信确认方: WIP 状态不豁免已落库可达的经济+战力漏洞。
- 修复方向: 输入槽绑定注册材料物品 (isItemValid + mayPlace 双闸) + 品质档按台主职业等级钳制。

## 二. Major

### G-1. TACZ 三轴乘算无全局帽, 违反 "战斗仅少量加成" 职业哲学

- 位置: `GunsmithTaczStatsHandler.onGunHurt` (57/59 行) / `onAttachmentProperty` (40 行)
- 伤害 x damage系数、爆头倍率 x headshot系数、射速 multiplyInteger(RPM x recoil系数) 三条乘子独立作用, LEGENDARY 单轴最高 1.50; 与 TACZ 原生配件加成、其他职业进攻加成之间**无单点结算总帽** (对照跨职业减伤已有的乘法非线性单点结算+全局帽, 进攻侧完全裸奔)。系数经 `M4AssemblyTemplateItem.stampGunData` 烙进枪 NBT 永久生效。
- 修复方向: 进攻加成并入全局单点结算帽; 系数区间按职业哲学重标定 (少量加成, 非 +50%)。

### G-2. 冲压结算非原子: 开工帧扣料, 产物延后 600~7200 tick, 无取消无退料 (上轮 M-2 同款)

- 位置: `GunsmithPressBlockEntity.tryStartPreview` (153 行 consumeRequiredMaterials) / `startPressRun` (181-192) / `finishPressRun` (194-208)
- 开工帧扣光三槽材料, 产物最长 6 分钟后才落; 窗口内无 cancel 按钮 (Menu 无此 id, BE 无此方法)、无退料回滚; 台子中途被破坏 -> 已扣料 + 未来产物双蒸发 (与 G-3 破坏无掉落叠加)。同款非原子模式第二次出现 (上轮 M-2 判 major 未修, 新代码重演), 违反 main 侧 munitions-01 "扣不动则料不扣" 契约。
- 修复方向: 材料改完成帧与产物同帧结算 (先查后扣); 补 cancel 路径。

### G-3. 冲压台零归属/零职业/零等级门控, 相对同胞军火台范式是子系统内回归

- 位置: `GunsmithPressBlock.use` (63-73 行) / `GunsmithPressMenu.clickMenuButton` (76-95) / BE load/save (无 owner 字段)
- 同一提交所在子系统的军火台有 setOwner + canAccess + effectiveOwnerLevel 三重范式, 冲压台连 owner 字段都不存: 任意玩家可开任意人放的台、直选 LEGENDARY 开工。与 C-2 叠加即零门槛量产 +50% 伤害部件产线。
- 修复方向: 克隆军火台的 owner/锁/等级门范式。

### G-4. 冲压台破坏无掉落回收: 4 个真实物品槽内容物静默删除

- 位置: `GunsmithPressBlock` (全类 133 行无 onRemove/playerWillDestroy) / `loot_tables/blocks/gunsmith_press.json` (只掉方块本体)
- BE 用真实 ItemStackHandler 装玩家放入的材料与产出部件, 破坏时不 dropContents, 全库也无兜底 (MunitionsSystem.onBenchBroken 的 instanceof 只匹配军火台)。破坏 = 槽内全部物品删除。注意: 上一轮旧军火台同款问题被驳回的理由是 "main 既有非分支引入", 冲压台是本提交**全新方块**, 该理由不适用, 为新引入的物品损失点。
- 修复方向: 覆盖 onRemove 非创造 dropContents 四槽。

### G-5. 2045 行新子系统零 GameTest 覆盖 (上轮 M-11 模式重演)

- gunsmith 全包无一测试。最该覆盖的断点: 品质系数 roll 边界 (min/max 收敛)、冲压结算原子性 (中断/破坏路径)、材料身份校验 (C-2 回归测试)、M4 组装消耗与 NBT 往返、输出槽并发取件。

## 三. Minor

1. Screen 全部用户可见文本硬编码中文 literal (111/112/113/238-240/255/259-261/283/319/351/359-360/369 行), 375 行却混用 translatable, 同文件自相矛盾 —— 上轮 M-10 同款, 全 gunsmith UI 面积更大。
2. 全部平衡系数 (materialMultiplier 1~10 / requiredTicks 600~7200 / 系数区间) 硬编码在 `GunsmithPartQuality` 枚举, 不进 MunitionsConfig, 真服调平衡必须发版。
3. `GunsmithPressPart` 枚举缺 RECEIVER: 本提交新增的 AR/AK x 5 品质共 10 模型 + 10 贴图 receiver 资产成死资源, M4 实际 6 部件成枪。
4. `en_us.json` 缺 `itemGroup.miningdim_gunsmith` 键, 英文客户端创造页签显示裸 key (zh_cn 有)。

## 四. 对抗驳回项 (不计入, 供参考)

1. "TACZ 改装台重建栈丢旁挂 NBT" —— TACZ 1.1.8 字节码级验证: 改装台不重建栈, 旁挂键 `MiningDimGunsmith` 不被触碰, 场景不可达。
2. "产物无 sink 构成洗钱 faucet" —— 冲压是物料换物料转换器, 零货币产出, 不是 faucet (真问题在 C-2 的输入侧零校验)。
3. "M4 按背包首槽取件忽略品质" —— 系数如实烙进枪, 价值转移非湮灭。
4. "CustomModelData 按 ordinal 未来插档错位" —— 当前公式与静态覆盖表自洽, 纯假设场景。
5. "quickMoveStack 含输出槽绕 mayPlace" —— 守卫链挡住, 与上轮军火台 M-7 不同, 冲压台此路不可达。
6. "farmer 改动越界夹带" —— FarmerFarmlandBlock 系本分支自建文件 (258867e), a2552da 属分支内自我演进, 非夹带他人代码; 但混功能+跨职业资源于一个无 scope 提交仍违反原子提交纪律。

## 五. 上轮 12 项回归核对

a2552da 未触碰军火台/WebUI 代码, 上轮 C-1 与 M-1~M-11 **全部未修**: 2 个 GameTest 依旧红 (M-1 实证), 四件套 primer/casing/bullet_head/propellant 配方依旧零命中 (M-4, 冲压台产的是枪械部件不是弹药四件套, 两条获取链都断)。

## 六. 合并前置清单 (增补)

原 12 项全部保留, 新增:

- [ ] C-2: 冲压输入槽材料身份校验 + 品质等级钳制
- [ ] G-1: 进攻加成并入全局单点结算帽, 系数重标定
- [ ] G-2: 冲压改原子结算 + cancel 路径
- [ ] G-3: 冲压台 owner/职业/等级门控
- [ ] G-4: 破坏 dropContents
- [ ] G-5: gunsmith GameTest 补齐
- [ ] 注册 alloy/polymer 等输入材料物品 + 冲压台/材料生存获取链 (WIP 补完)
- [ ] receiver 资产接回枚举或移除死资源
- [ ] 增量 minor: config 化平衡系数 / i18n / itemGroup 键

---
---

# 补充审查 (2026-07-11): 对 main 既有实测代码的侵入面清单

审查准则: main 为实测基准, 分支对 main 既有文件的任何修改 (--diff-filter=MD, 共 31 文件) 均逐一过堂。munitions bench 全系列 + WebUiClientSubsystem 已在前两轮覆盖, 此处只列此前未细审的侵入点。

## 一. 实质行为变更

### F-1. FarmerFarmlandBlock 两处覆写: farmer 耕地行为被枪匠提交夹带修改 (minor 偏上)

- `canSustainPlant` (47-51 行): main 版是裸 Block (默认拒绝 Forge IPlantable), 分支放宽为任意 `PlantType.CROP` 朝上可种。配套测试注释自曝动机 "such as most Farmer's Delight seeds" —— **服务器并未安装 Farmer's Delight**, 属预设兼容。当前 modpack 下实际影响: vanilla 种子从 "种不上" 变 "种得上"; farmer 生长加速在 FarmerCropBlock 自身, 外来作物不白嫖加速, 经济影响可控。
- `getShape` 15/16 高 (53-56 行) + 五档模型换 template_farmland 与自制贴图 (10 张已确认存在): 对齐 vanilla farmland 观感。副作用: 顶面支撑判定 (isFaceSturdy) 从满格变非满, **存量世界中已放在 mod 耕地上的火把/压力板等将在邻居更新时弹出掉落** —— 一次性迁移扰动, 上线前应公告或写迁移说明。
- 缓解: 配套新增 farmerFarmlandSustainsForgeCropPlants 测试 (CROP 准入/WATER/DESERT/侧面四断言, 质量合格), farmer 旧测试断言未动且全绿。
- 问题实质: 行为变更本身方向合理, 但夹带在 "枪匠冲压" 提交里且 commit message 无一字提及 farmer。

### F-2. MunitionsLevels.highestUnlockedCaliber 静默修正 (minor)

- 105 行加 `&& caliber.unlockLevel() > best.unlockLevel()`: 消除对枚举声明顺序的隐含依赖, 系配合 RIFLE_556 插入枚举的防御性修正。方向正确, 但静默改 main 实测函数且无专项断言 (若插枚举时不带这行, main 版逻辑会返回错误口径 —— 说明枚举插入本身就动了 main 函数的输入域)。

## 二. 合规扩展 (确认无风险)

1. `MunitionsSystem`: gunsmith 挂接范式正确 —— GunsmithTaczStatsHandler 走 FMLCommonSetupEvent + isTaczLoaded 守卫延迟注册, Screen 注册经 DistExecutor 隔离; registerExportPack 虽在 register() 早期无条件调用, 但内部 ClassNotFoundException 守卫齐全 (GameTest 实跑不崩佐证)。
2. `ModMunitionsTab`: munitions 页签补四件套展示 + 新 GUNSMITH_TAB, icon lambda 仅引自家类, classload 安全。
3. lang 双文件: `message.miningdim.marriage.divorce.insufficient` 的 -/+ 为行尾逗号变化 (原文件末行后追加新 key), **婚姻 key 未丢, 虚惊**; `munitions_bench` 值 "军火台 -> 低级军火台" 是 M-5 存量降档的表现层, 非新问题。
4. propellant/primer 物品贴图从 vanilla 借用换自制, 文件存在。
5. `docs/Munitions_Job_DesignSpec.md` 补 3A 冲压 WIP 章节: 洗清增量审查中 "无设计文档背书" 的疑虑, 同时坐实 TACZ 三轴加伤 (G-1) 是**设计意图而非手滑** —— G-1 由此升格为需要设计层拍板的决策项: 系数区间 (传奇 1.36-1.50) 与 "战斗仅少量加成" 职业哲学的冲突要么改文档要么改数值。
6. AGENT.md 删除与 main 4de6fc3 同步一致; FarmerGameTests 旧断言零改动。

## 三. 结论

对 main 既有代码的侵入共三类: 前两轮已立案的 (bench 全系列 + WebUI), 本轮新立案的 F-1/F-2, 其余为合规扩展。main 实测行为在 GameTest 可见范围内未被破坏 (farmer 全绿), 破坏面集中在测试覆盖不到的地方: 存量世界 (F-1 支撑弹出 / M-5 降档) 与运行期语义 (M-1 死代码)。
