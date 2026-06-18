# 职业框架 · 共享地基 设计规格文档

## 文档元信息

- 用途: **全部职业(矿工/农夫/工程师/塔罗师/厨师)与结婚系统共享的地基的唯一真源**。各职业 spec 反复引用的"前置/共享地基"在此定义,职业 spec 的"前置"小节应**引用本文档而非各自复述**。本文档由完整性审计补建(此前这套地基被切碎散落在 5 份职业 spec 的"架构/前置"节,且 `JobId` 成员各 spec 互相矛盾)。
- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。
- 部署环境(硬约束): 公服初始血量 80、TACZ 枪械、死亡不掉落、PvP+PvE;一切战斗向数值用 %最大血量、抗性≤III、**不破枪战 attrition**(此红线对"全职业合并后的总效果"生效,见第八章)。
- 状态图例: DECIDED 已定稿 / PENDING 待拍板(已给推荐) / TODO 实现期补全。
- 编码前阻塞: 本文档第二、三章(JobId/JobProgress capability 收敛迁移)是**所有职业的硬前置**,必须先于任何职业实现落地。

---

## 一、为何需要这份文档 (DECIDED)

5 份职业 spec 都写"复用职业框架两/三项前置",但没有一份定义这些前置本身:`EnumMap<JobId,JobProgress>` 重构规格只在塔罗 11.1.1;menu 脚手架 API 清单只在工程师 10.5;`JobId` 成员各 spec 不一致(塔罗/工程师写 4 个不含 CHEF,矿工/厨师/结婚不列举);没有任何一处定义 `JobProgress` 字段全集、序列化键、三套并存存储的收敛、谁先建谁负责。本文档把这些收编为单一规格。

---

## 二、JobId 与 JobProgress (DECIDED)

### 2.1 JobId 权威枚举
`enum JobId { MINER, FARMER, ENGINEER, TAROT, CHEF }`(5 个)。**结婚不是 JobId**——它是系统,数据走 `MarriageRegistry`(见结婚 spec),不进 `JobProgress`。各 spec 此前互相矛盾的成员清单一律以此为准。

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
代码现存三套玩家级存储,必须收敛(否则复活既裁的"双 capability 重复 attach → 双重传送/双重引用计数"隐患):

| 存储 | 现状 | 裁决 |
| --- | --- | --- |
| `entry.MiningPlayerData`(capability) | 既裁唯一权威 | **保留为唯一权威**,扩 `EnumMap<JobId,JobProgress>` |
| `persistence.PlayerMiningData`(+Provider/Events) | 等价的第二套 capability+attach+Clone,仅在 InstanceSystem 接线层被裁撤,类仍在 | **删除整包**(留着就会被误 attach;`MiningDim.java` 注释已警告,但仅在代码注释、未进任何文档) |
| `economy.PlayerAbuseState` / `pressure` 内存态 | UUID 内存态,无持久化(注释:"Capability 子系统就绪后从持久层 load") | **并入 entry capability 持久化**(或明确保留内存态的理由) |

迁移步骤(独立原子提交,遵循第 0 步法则):(1) entry 扩 `EnumMap`;(2) 删 `persistence` 死包 + 多维 grep 确认无引用;(3) `economy/pressure` 内存态迁入 capability;(4) 扩 `IMiningPlayerData` 后多维 grep 补全所有实现/mock。

### 2.4 死亡/换维度 Clone 与登出纪律(DECIDED)
复用 MiningDimension 12.5/14.6 已规格化的 `PlayerEvent.Clone`(reviveCaps/invalidateCaps 1.20.1 强制写法)与登录恢复。**新增**:所有 `JobProgress` 字段在 Clone 时全量复制;**临时属性修饰符/CD/效果**(工程师 nanoReactor、塔罗强增益、厨师窗口效果、矿工充能)在死亡/登出/**换维度**时按各自 spec 清理(本 mod 反复进出矿洞维度=最高频泄漏路径,统一在第五章 ModEffects 纪律兜底)。

---

## 三、货币接口契约 (DECIDED 接口 / 实现归经济文档)

经济文档 0.3 裁定"信用点/青辉石余额 → Capability";但代码 `AbuseGuard.chargeItem` 扣的是**物理物品**(默认钻石),**不是余额**。塔罗(十)、结婚(三)写"复用 `chargeItem` 花信用点"是**语义错位**。本章定义职业侧依赖的门面,余额模型实现归经济文档(见第九章待补丁)。

```
interface IEconomyService {            // 注入 MiningServices, 职业子系统按接口取用
  long creditBalance(Player p);
  long heartstoneBalance(Player p);    // 青辉石
  boolean tryCharge(Player p, Currency c, long amount);   // 事务安全, 不足返 false
  void grant(Player p, Currency c, long amount);
  boolean tryChargeDaily(Player p, Currency c, long amount, String dailyKey, long dailyCap); // 含每日限购计数器
}
```
塔罗买卡包、结婚典礼成本、矿山重置成本一律走 `IEconomyService.tryCharge(信用点)`,**不再说"复用 chargeItem"**(那是扣物品)。余额字段并入 entry capability,序列化/Clone 纳入第二章。

---

## 四、统一经验与每日衰减框架 (DECIDED + 1 PENDING)

总 61,900 曲线 + 每日有效经验软上限衰减表(0-2000 ×1.0 / 2000-2800 ×0.4 / 2800-3400 ×0.2 / 3400-3800 ×0.08 / 3800+ ×0.02)是**唯一数据源**(现被工程师/塔罗/厨师/矿工逐字复制 4-5 份 → spec 漂移温床)。实现为共享 `LevelingService`(或 `JobProgress` 方法),各职业 spec 经验章改为引用本表。

- **翻日口径统一为 UTC**(`AbuseGuard.currentPlayerDayStamp` 已是 UTC;废弃 `gameTime/24000` 口径)。信用点每日 faucet 上限与职业经验软上限**共用同一 UTC 翻日时钟**。
- **跨职业日预算(PENDING,推荐 per-job)**: FF14 式同时持有全部职业,一天能否在 5 个职业各刷满?**推荐 per-job 独立衰减**——理由:挖矿/做菜/打牌/种田**竞争同一份真实在线时间**(一次只能干一件),per-job 天然被真实时间封顶,无需再设全局上限;且各职业有独立反通胀闸。若运营发现总产出过高,再上一个"全职业总有效经验/日"的硬顶。需你拍板。

---

## 五、共享 ModEffects / 自定义效果框架 (DECIDED)

- 共享 `ModEffects`(`DeferredRegister<MobEffect>`,放 `com.miningdim.effect` 共享包,**非任何单职业包**),登记跨职业自定义效果:**易伤**(塔罗/厨师/未来武器)、厨师窗口效果(余韵/披甲/凝脂/稳膛/耐饥…)等。
- **LivingHurtEvent 全局乘伤顺序与封顶仲裁(关键)**: 易伤"多源取最高、总封顶 +100%"需要**单一全局仲裁点**——所有职业的易伤来源汇总后由一个 handler 统一乘伤,塔罗/厨师**不得各自挂 LivingHurtEvent 各乘一次**(否则叠乘破封顶或互相覆盖)。乘伤在护甲/抗性减伤后、黄心吸收前。
- **临时属性修饰符纪律**: 一切临时 `AttributeModifier`(最大生命/抗击退等)用**固定 UUID + transient**,并在登出/死亡/Clone/**换维度**统一 `removeModifier` 兜底(本框架提供统一清理 hook,各职业登记自己的 UUID)。严禁 `addPermanentModifier` 或不配对清理(泄漏红线)。维护一张**固定 UUID 登记表**防撞。
- **周期效果调度器**: 共享 `ScheduledEffectManager`(服务端 tick,`server.getTickCount()` 全局时钟),供厨师窗口效果/塔罗延时效果/矿工充能用;登出/死亡清该玩家全部 pending。

---

## 六、公共 menu 脚手架 (DECIDED)

工程师(生产台/校准)、塔罗(开包自选/合成)、厨师(调味台/小游戏)、结婚(共享背包/誓言墙)全部依赖它。代码现 0 个 Menu/Screen(`MiningNetwork.openGui` 直接 throw)。把工程师 10.5 的清单提升为**共享规格**:

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
- 子命令: `/job list`(各职业等级/经验/当日剩余衰减额度)、`/job info <job>`、`/job top <job>`(可选排行);OP: `/job set <player> <job> <level>`。权限沿用 `MiningPermissions`。

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
| MiningDimension_Mod_DesignSpec | 登记矿工要求的**本体改动**:`Danger.evaluate` 加 job 系数入参(第十章)、`EntryGateway.gateCheck` 难度门控源改矿工等级(14.4)、`TrapSystem` 陷阱伤打专属 DamageSource、经济计数口径改"产出物"、persistence 死包待删(12.5);消除"双权威" | Critical/Major |
| Chef_Job_Mod_DesignSpec | 第八/九章补一张 **FID 34 个状态效果逐个"战斗向/可增香"判定表**(从仓库根 `flavor_immersed_daily-1.1.0.3-forge-1.20.1.jar` 核 effect id 全集),给"增香黑名单"据可依 | Major |
| Miner_Job_DesignSpec | 按真实 `Danger.evaluate` 签名/pressure 包结构校正 hook 描述;与上面 MiningDimension 本体改动交叉引用 | Minor |
| 各职业 spec "前置"节 | 改为引用本文档,删除各自复述的 EnumMap/menu/ModEffects 细节 | Minor |

---

## 十二、实现顺序 (DECIDED)

1. **本框架第二章**(JobId/JobProgress + 三套存储收敛迁移)——所有职业硬前置,先做。
2. 第三章货币接口 + 经济文档余额模型补丁(塔罗/结婚/矿山扣费都等它)。
3. 第五章共享 ModEffects + 第六章公共 menu 脚手架(谁先用谁建,后者复用)。
4. 第四章 LevelingService + 第七章网络/HUD 框架 + 第九章 /job。
5. 各职业本体(按各自 spec)。

测试断言示例: `JobId` 全 5 成员一致;扩 `IMiningPlayerData` 后全实现/mock 编译通过;删 `persistence` 包后无悬空引用;多职业易伤来源叠加后总值 ≤+100%(单一仲裁);临时最大生命修饰符登出再登入恢复基线(无泄漏);`/job` 与 `/mining` 无 Brigadier 双根冲突。
