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
