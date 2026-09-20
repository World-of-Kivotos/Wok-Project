# 职业框架 · 共享地基 设计规格文档

## 文档元信息

- 用途: **全部职业(矿工/农夫/铸甲师/塔罗师/厨师/特勤干员/军火商/酿酒师,见 2.1 权威枚举)与结婚系统共享的地基的唯一真源**。各职业 spec 反复引用的"前置/共享地基"在此定义,职业 spec 的"前置"小节应**引用本文档而非各自复述**。本文档由完整性审计补建(此前这套地基被切碎散落在 5 份职业 spec 的"架构/前置"节,且 `JobId` 成员各 spec 互相矛盾)。
- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。
- 部署环境(硬约束): 公服初始血量 80、TACZ 枪械、死亡不掉落、PvP+PvE;一切战斗向数值用 %最大血量、抗性≤III、**不破枪战 attrition**(此红线对"全职业合并后的总效果"生效,见第八章)。
- 状态图例: DECIDED 已定稿 / PENDING 待拍板(已给推荐) / TODO 实现期补全。
- 当前状态: 第二/三/四/五/六/九章均已落地(EnumMap 收敛见 `job/JobData.java`,货币门面见 `economy/IEconomyService.java`,经验曲线与每日衰减见 `job/JobXpCurve.java`,共享效果见 `effect/ModJobEffects.java`,menu 脚手架见 `com.miningdim.menu`,`/job` 命令见 `job/JobCommands.java`)。本文档已由"编码前阻塞"转为**职业地基的现状真源与回归依据**;仍未闭合的只有第 2.3 节迁移步骤 (3)(`economy`/`pressure` 内存态迁入 capability)与第十一章"现有文档待补丁登记"表中的各项缺口。

---

## 一、为何需要这份文档 (DECIDED)

5 份职业 spec 都写"复用职业框架两/三项前置",但没有一份定义这些前置本身:`EnumMap<JobId,JobProgress>` 重构规格只在塔罗 11.1.1;menu 脚手架 API 清单只在工程师 10.5;`JobId` 成员各 spec 不一致(塔罗/工程师写 4 个不含 CHEF,矿工/厨师/结婚不列举);没有任何一处定义 `JobProgress` 字段全集、序列化键、三套并存存储的收敛、谁先建谁负责。本文档把这些收编为单一规格。

---

## 二、JobId 与 JobProgress (DECIDED)

### 2.1 JobId 权威枚举
`enum JobId { MINER, FARMER, ENGINEER, TAROT, CHEF, AGENT, MUNITIONS, BREWER }`(8 个,真值见 `job/JobId.java`)。成员顺序即 `JobSyncS2C` 按 `values()` 读写的同序契约,**新增职业只能尾部追加**(AGENT/MUNITIONS 依 mindmap 与特勤/军火两份 spec 收编为 7 个,BREWER 据同序契约追加为第 8 个)。`ENGINEER` 的玩家可见名已改为"铸甲师",但稳定 id 保留 `engineer`,以免旧存档进度与旧命令参数失效。**结婚不是 JobId**——它是系统,数据走 `MarriageRegistry`(见结婚 spec),不进 `JobProgress`。各 spec 此前互相矛盾的成员清单一律以此为准。

**不占 JobId 的 job 子包**: `com.miningdim.job.fisher`(模块 `wok-job-fisher`"渔夫",见 `docs/modules/module-registry.json` 与 `docs/Ore_Fish_And_Soup.md`)位于 `job` 包下并有独立模块身份,但**不进 JobId、不持 `JobProgress`、不进 `/job` 命令**(该包对 `JobId` 零引用)——它是围绕钓鱼的内容模块而非等级职业。此形态合法但须显式登记:新增此类"挂在 `job` 包下却不入 EnumMap"的模块,必须在本节补一行并写明不入 EnumMap 的理由,避免与 JobId 成员混淆。

### 2.2 JobProgress 字段全集
```
class JobProgress {
  int level;        // 1-10
  long xp;          // 累计经验(向当前级)
  long dailyXp;     // 当日已结算"有效经验"(衰减后)
  long dayStamp;    // 翻日戳(统一 UTC, 见第四章)
  // 职业特有附加字段(同一对象内, 按 JobId 取用):
  //  ENGINEER: nanoReactorCdEndTick
  //  TAROT:    pityCounter / 各卡 CD(或走物品 NBT)
  //  MINER:    各技能 CD/充能、当前激活的开关位
  //  CHEF:     (无额外, 经验/品质走物品 NBT)
  //  FARMER:   (无额外)
}
```
玩家数据 = `entry.MiningPlayerData` 持 `EnumMap<JobId,JobProgress>` 一处 `serializeNBT/deserializeNBT/copyFrom`(遍历 Map),`IMiningPlayerData` 扩 `JobProgress jobProgress(JobId)` 一个方法取代"每职业一组 getter/setter"。新职业 = Map 多一个 key,零结构改动。`deserializeNBT` 对旧存档缺键给默认(level=1,xp=0)。

### 2.3 三套并存数据存储的收敛裁决(Critical)
代码曾并存三套玩家级存储,必须收敛(否则复活既裁的"双 capability 重复 attach → 双重传送/双重引用计数"隐患):

| 存储 | 现状 | 裁决 |
| --- | --- | --- |
| `entry.MiningPlayerData`(capability) | 既裁唯一权威 | **保留为唯一权威**,扩 `EnumMap<JobId,JobProgress>` |
| `persistence.PlayerMiningData`(+Provider/Events) | **已删除(收敛完成)**,类与 Provider/Events 均不复存在 | **裁决已执行完毕,本行转为历史记录**。现 `com.miningdim.persistence` 包内仅剩 `MiningSavedData`(矿区实例注册表 + 全局计数器持久层,挂矿山维度 `DimensionDataStorage`,承载实例 Map/`nextInstanceId`/`globalSeed`/`resetGeneration`/region 位图),与玩家 capability 无关,**严禁再按"删整包"执行**。另:`pressure.PlayerMiningData` 是同名的压力子系统内存态纯数据(非 capability),`economy.PlayerAbuseState` 类注释里提到的"Capability/PlayerMiningData"只是历史设计引用,两者都不是本行裁撤的旧 capability,勿误伤 |
| `economy.PlayerAbuseState` / `pressure` 内存态 | UUID 内存态,无持久化(注释:"Capability 子系统就绪后从持久层 load") | **并入 entry capability 持久化**(或明确保留内存态的理由) |

迁移步骤(独立原子提交,遵循第 0 步法则)与当前状态:

- [x] (1) entry 扩 `EnumMap` —— 已落地,`job/JobData.java` 持 `EnumMap<JobId,JobProgress>`,作为 `entry.MiningPlayerData` 的内部委派。
- [x] (2) 删 `persistence` 死包 + 多维 grep 确认无引用 —— 已完成。**该步骤严禁再次执行**:`persistence` 包内现仅存的 `MiningSavedData` 是矿区实例持久化的现役代码,不是待删死代码。
- [ ] (3) `economy/pressure` 内存态迁入 capability —— **未完成**:`economy.PlayerAbuseState` 仍由 `EconomySystem` 以 `Map<UUID,PlayerAbuseState>` 在内存维护、登入重建(其 NBT 读写已备好但无持久层接管),`pressure` 运行态同样不跨重启持久化。
- [x] (4) 扩 `IMiningPlayerData` 后多维 grep 补全所有实现/mock —— 已落地,见 `entry/IMiningPlayerData.java` 的 `JobProgress jobProgress(JobId job)`。

### 2.4 死亡/换维度 Clone 与登出纪律(DECIDED)
复用 MiningDimension 12.5/14.6 已规格化的 `PlayerEvent.Clone`(reviveCaps/invalidateCaps 1.20.1 强制写法)与登录恢复。**新增**:所有 `JobProgress` 字段在 Clone 时全量复制;**临时属性修饰符/CD/效果**(工程师 nanoReactor、塔罗强增益、厨师窗口效果、矿工充能)在死亡/登出/**换维度**时按各自 spec 清理(本 mod 反复进出矿洞维度=最高频泄漏路径,统一在第五章 ModEffects 纪律兜底)。

---

## 三、货币接口契约 (DECIDED 接口 / 实现归经济文档)

经济文档 0.3 裁定"信用点/青辉石余额 → Capability";但代码 `AbuseGuard.chargeItem` 扣的是**物理物品**(默认钻石),**不是余额**。塔罗(十)、结婚(三)写"复用 `chargeItem` 花信用点"是**语义错位**。本章定义职业侧依赖的门面,余额模型实现归经济文档(见第九章待补丁)。

```
interface IEconomyService {            // 注入 MiningServices, 职业子系统按接口取用
  long creditBalance(ServerPlayer player);
  long heartstoneBalance(ServerPlayer player);    // 青辉石
  boolean tryCharge(ServerPlayer player, Currency currency, long amount);   // 事务安全, 不足返 false
  void grant(ServerPlayer player, Currency currency, long amount);
  boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount, String dailyKey, long dailyCap); // 含每日限购计数器
}
```
参数一律是 `ServerPlayer` 而非 `Player`(服务端权威,客户端侧无余额真值)。实现另含 `settleOreSale` / `recordMinedOreDrops` / `grantDaily` / `grantAzureDaily` / `isAfkFrozen` 五个经济侧结算方法,不属职业地基门面的最小集,详见经济文档与 `economy/IEconomyService.java`。

塔罗买卡包、结婚典礼成本、矿山重置成本一律走 `IEconomyService.tryCharge(信用点)`,**不再说"复用 chargeItem"**(那是扣物品)。余额字段并入 entry capability,序列化/Clone 纳入第二章。

---

## 四、统一经验与每日衰减框架 (DECIDED)

总 61,900 曲线 + 每日有效经验软上限衰减表(0-2000 ×1.0 / 2000-2800 ×0.4 / 2800-3400 ×0.2 / 3400-3800 ×0.08 / 3800+ ×0.02)是**唯一数据源**(现被工程师/塔罗/厨师/矿工逐字复制 4-5 份 → spec 漂移温床)。已实现为共享的 `job/JobXpCurve.java`(曲线表 + 衰减分段 + `DAILY_SOFTCAP` 单一权威)配合 `JobProgress.grantXp`,各职业 spec 经验章改为引用本表。

- **翻日口径统一为 UTC**(`AbuseGuard.currentPlayerDayStamp` 已是 UTC;废弃 `gameTime/24000` 口径)。信用点每日 faucet 上限与职业经验软上限**共用同一 UTC 翻日时钟**。
- **跨职业日预算(DECIDED = per-job 独立衰减,已落地)**: 每职业各持一份 `dailyXp`/`dayStamp` 游标(`JobData` 的 `EnumMap<JobId,JobProgress>` 每个 key 一份 `JobProgress` 实例),`JobXpCurve` 的分段折算只吃该职业当日的有效经验标量、不聚合其它职业,**不存在任何全职业总额约束**。理由:挖矿/做菜/打牌/种田**竞争同一份真实在线时间**(一次只能干一件),per-job 天然被真实时间封顶,无需再设全局上限;且各职业有独立反通胀闸。若运营发现总产出过高,再追加一个"全职业总有效经验/日"的硬顶——那是存档结构改动,须单独评估成本,不能当调参处理。

---

## 五、共享 ModEffects / 自定义效果框架 (DECIDED)

- 共享 `ModEffects`(`DeferredRegister<MobEffect>`,放 `com.miningdim.effect` 共享包,**非任何单职业包**),登记跨职业自定义效果:**易伤**(塔罗/厨师/未来武器)、厨师窗口效果(余韵/披甲/凝脂/稳膛/耐饥…)等。
- **LivingHurtEvent 全局乘伤顺序与封顶仲裁(关键)**: 易伤"多源取最高、总封顶 +100%"需要**单一全局仲裁点**——所有职业的易伤来源汇总后由一个 handler 统一乘伤,塔罗/厨师**不得各自挂 LivingHurtEvent 各乘一次**(否则叠乘破封顶或互相覆盖)。乘伤在护甲/抗性减伤后、黄心吸收前。
- **临时属性修饰符纪律**: 一切临时 `AttributeModifier`(最大生命/抗击退等)用**固定 UUID + transient**,并在登出/死亡/Clone/**换维度**统一 `removeModifier` 兜底(本框架提供统一清理 hook,各职业登记自己的 UUID)。严禁 `addPermanentModifier` 或不配对清理(泄漏红线)。维护一张**固定 UUID 登记表**防撞。
- **周期效果调度器**: 共享 `ScheduledEffectManager`(服务端 tick,`server.getTickCount()` 全局时钟),供厨师窗口效果/塔罗延时效果/矿工充能用;登出/死亡清该玩家全部 pending。

---

## 六、公共 menu 脚手架 (DECIDED)

工程师(生产台/校准)、塔罗(开包自选/合成)、厨师(调味台/小游戏)、结婚(共享背包/誓言墙)全部依赖它。共享脚手架已建成于 `com.miningdim.menu`(`ModMenus` / `AbstractMiningMenu` / `AbstractMiningScreen` / `MenuValidity`),含方块 menu 与远程 menu(`remoteMenuType`,extraData 不带 `BlockPos`)两种工厂:结婚共享背包(`MarriageRegistration`)与塔罗闪耀开包自选(`TarotRegistry`)已复用 `remoteMenuType`,工程师生产台、厨师调味台、酿酒台/酒窖、军械台与冲压台、塔罗合成、电力机械等均已落地各自的 `AbstractMiningMenu`/`AbstractMiningScreen` 子类。`MiningNetwork.openGui` 仍抛 `UnsupportedOperationException`,那是网络子系统的历史残留、早已不是开窗入口(其方法注释已写明真实开窗走 `NetworkHooks.openScreen`),应择机清理或删除以免误导。下列清单(源自工程师 10.5)即该脚手架的**共享规格**:

- `com.miningdim.menu` 包: `DeferredRegister<MenuType<?>>` + `IForgeMenuType.create((id,inv,buf)->…)`(传 `BlockPos`) + `AbstractMiningMenu` 基类(**正确 `quickMoveStack`/`stillValid`**,防 Shift 吞物/死循环) + `ContainerData` 同步约定 + 客户端 `MenuScreens.register`(`FMLClientSetupEvent.enqueueWork`)。全 1.20.1 写法(严禁 1.20.4+ custom payload / 1.20.5+ MapCodec)。
- **非方块 menu 场景**(工程师 10.5 仅覆盖方块 menu): 结婚共享背包是**戒指远程开**(非方块)且是"最高危 dupe"模块——脚手架须提供"无 BlockPos、以 `MarriageId`/虚拟 owner 为 stillValid 依据"的工厂变体。
- 归属: 公共脚手架"谁先实现谁建",建在 `com.miningdim.menu`;不必进 MiningServices(各职业 register 内用)。

---

## 七、职业网络包与 HUD (DECIDED)

工程师(护盾层/图腾CD/特效图标 HUD + S2C)、矿工(探矿/陷阱高亮 S2C + RenderLevelStageEvent)、塔罗(易伤图标/粒子)、结婚(伴侣状态 HUD + 蓄力条 S2C)都要新 S2C 包与 HUD。现 `MiningNetwork` 只有 4 个本体包,discriminator id 集中自增。

- **统一 CHANNEL 注册纪律**: 新职业 S2C 包**全部塞进同一 `MiningNetwork.CHANNEL`**,discriminator id 两端一致、全局唯一、集中登记(防多职业并行加包握手错位)。维护一张**全 mod S2C/C2S 包登记表**(同时补进 MiningDimension 网络章)。
- **多职业 HUD 布局仲裁**: 一个玩家同时是 5 个职业,HUD 叠层不得互相打架。定义统一 HUD 分区(如左下=矿工探测/右下=战斗向CD/图标行=效果)+ `RenderGuiOverlayEvent` 单一注册入口 + 客户端状态镜像(仿现有 `ClientDangerState`)。

---

## 八、多职业并发模型(FF14 式)(DECIDED)

"玩家同时持有全部职业"的语义此前无定义,在此定:

1. **全部职业被动恒生效**(无"当前激活职业"概念)——矿工挖速被动 + 厨师吃菜 buff + 塔罗用牌 + 工程师特效可同时在身。被动按 `BreakSpeed`/事件无条件结算(不判"当前职业")。
2. **跨职业同类效果合并仲裁(关键平衡红线)**: 多个职业给同类效果时**取最高不叠乘**,且"不破 attrition / 抗性≤III / %最大血量"红线对**全职业合并后的总效果**生效(不是各职业各算)。例:矿工减 danger + 未来职业减 danger 不叠加;任何职业组合后的减伤/护盾/回血总和受统一上限。
3. "切换"仅指 UI 上查看不同职业进度(`/job`),不是激活态切换。

---

## 九、/job 命令 (DECIDED)

- 新建一棵 `/job` 根(**不挂 `/mining` 下**,避免 Brigadier 双根冲突——`MiningDim.java` 已对 `/mining` 双根有过裁决),由职业框架子系统统一 `register`(`RegisterCommandsEvent`)。
- 子命令(实际注册见 `job/JobCommands.java`): `/job list`(各职业等级/经验/当日剩余衰减额度)、`/job info <job>`、`/job wallet`(查信用点与青辉石余额);OP(permission level 2): `/job set <player> <job> <level>`。`/job top <job>`(可选排行)**至今未实现**,属可选项不是缺口。权限沿用 `MiningPermissions` 的 OP 等级口径(`JobCommands` 内以 `OP_LEVEL = 2` 常量落地,与 `entry` 命令一致)。

---

## 十、工程约定(横切,DECIDED 方向)

实现期会卡、此前无文档的横切关注,统一在此定方向(细则实现期补):

- **config 组织**: 全局旋钮走 `MiningServerConfig` ForgeConfigSpec **分职业分段**(push/pop);**大数值表(塔罗 80 组牌效、厨师效果、矿工技能曲线)走 datapack JSON**(仿 `ORE_USE_DATAPACK`),避免单 config 爆千行。
- **本地化**: lang key 命名空间 `<职业>.<类别>.<名>`;zh_cn/en_us 双语同步纪律;动态文案(品质前缀/牌名)用 Component 拼装。
- **客户端资源管线**: 品质边框走**共享 ItemProperties predicate**(塔罗/厨师/工程师复用);贴图/图标命名规范;客户端事件接入点统一(FMLClientSetupEvent)。
- **mods.toml 软依赖**: FD(`farmersdelight`)/FID(`flavor_immersed_daily`)/TACZ 一律 `mandatory=false` + `ModList.isLoaded` 守卫,缺失降级不崩。
- **per-player tick 性能**: 统一 per-player tick 分发器(一个 ServerTickEvent 遍历在线玩家,各职业注册回调),降频(每 N tick)+ 短路(不在矿洞/非该职业活跃则跳过),设主线程预算。避免每职业各挂一个全量 tick。
- **存档/版本**: capability NBT 加 schema 版本号,字段增删走迁移;跨 mod 版本升级核验。
- **服务端权威输入校验**: 多职业新增大量 C2S(开关/小游戏命中/选档/用牌/合成),统一"客户端只发请求、服务端校验时序/CD/资源/owner"清单 + 限流。

---

## 十一、现有文档待补丁登记 (TODO — 把剩余缺口落到对应文档)

| 文档 | 要补 | 严重度 |
| --- | --- | --- |
| 服务器经济系统设计文档 | "货币 capability 数据模型 + 扣费 API"专章(余额字段/序列化/Clone/每日限购计数器/`tryCharge`实现,落地本文档第三章接口);方案 B"产出物计数口径 + 非高价矿是否纳入 cap";全服**玩家间转移通道清单 + 反 RMT 一致性**(跳蚤/结婚共享背包/未来交易,统一定哪些落审计、哪些禁高价值物);全职业 faucet/sink 登记表 | Critical/Major |
| FarmingXP_Mod_DesignSpec | 顶部加 **superseded 注记**:持久化/ModLoader/版本/经济以本框架文档为准(覆盖其第十一章 PENDING),capability 并入 `EnumMap`(作废其独立 capability 方案),衰减表对齐统一 2000 系 | Major(本轮已加注记) |
| MiningDimension_Mod_DesignSpec | 登记矿工要求的**本体改动**:`Danger.evaluate` 加 job 系数入参(第十章)、`EntryGateway.gateCheck` 难度门控源改矿工等级(14.4)、`TrapSystem` 陷阱伤打专属 DamageSource、经济计数口径改"产出物"、persistence 死包**已删除**(12.5;该包现仅剩现役的 `MiningSavedData`,勿再按"删整包"执行,见本文档 2.3);消除"双权威" | Critical/Major |
| Chef_Job_Mod_DesignSpec | 第八/九章补一张 **FID 34 个状态效果逐个"战斗向/可增香"判定表**(从仓库根 `flavor_immersed_daily-1.1.0.3-forge-1.20.1.jar` 核 effect id 全集),给"增香黑名单"据可依 | Major |
| Miner_Job_DesignSpec | 按真实 `Danger.evaluate` 签名/pressure 包结构校正 hook 描述;与上面 MiningDimension 本体改动交叉引用 | Minor |
| 各职业 spec "前置"节 | 改为引用本文档,删除各自复述的 EnumMap/menu/ModEffects 细节 | Minor |

---

## 十二、实现顺序 (历史记录 — 五步已全部走完)

- [x] 1. **本框架第二章**(JobId/JobProgress + 三套存储收敛迁移)——所有职业硬前置,先做。已完成:`job/JobData.java` 的 EnumMap 委派 + `entry/IMiningPlayerData.jobProgress`;唯一残留是 2.3 迁移步骤 (3) 的 `economy`/`pressure` 内存态。
- [x] 2. 第三章货币接口 + 经济文档余额模型补丁(塔罗/结婚/矿山扣费都等它)。接口侧已完成:`economy/IEconomyService.java`;经济文档的余额模型专章仍挂在第十一章缺口表。
- [x] 3. 第五章共享 ModEffects + 第六章公共 menu 脚手架(谁先用谁建,后者复用)。已完成:`effect/ModJobEffects.java`(含易伤单一仲裁 `VulnerabilityHurtHandler`)与 `com.miningdim.menu`。
- [x] 4. 第四章 LevelingService(落为 `job/JobXpCurve.java`)+ 第七章网络/HUD 框架 + 第九章 `/job`(`job/JobCommands.java`)。已完成。
- [x] 5. 各职业本体(按各自 spec)。已完成:`com.miningdim.job` 下 8 个 JobId 职业各有子包(另有不占 JobId 的 `fisher` 内容模块,见 2.1)。

测试断言示例: `JobId` 全 8 成员一致且顺序不变(守 `JobSyncS2C` 按 `values()` 读写的同序契约);扩 `IMiningPlayerData` 后全实现/mock 编译通过;`persistence` 包内除现役 `MiningSavedData` 外无玩家 capability 残留;多职业易伤来源叠加后总值 ≤+100%(单一仲裁);临时最大生命修饰符登出再登入恢复基线(无泄漏);`/job` 与 `/mining` 无 Brigadier 双根冲突。
