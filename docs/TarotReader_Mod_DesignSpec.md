# 塔罗师 职业 Mod — 设计规格文档

## 文档元信息

- 用途: 塔罗师职业实现阶段的唯一架构、数值与机制参考。所有常量以本文档为准, 不得凭记忆改写。
- 目标平台: Minecraft 1.20.1 + Forge 47.x + Java 17。API 名称以此版本为准, 不得套用其他版本语法。
- 部署环境 (硬约束, 决定一切战斗向数值): 公服人均初始最大血量 80 (原版 20 的 4 倍); 装 TACZ 枪械 mod, DPS 极高、全自动每秒多发、狙/霰弹单发可破 80 且常穿甲, TTK 以秒计; 死亡不掉落; PvP+PvE。一切战斗向数值按 80 血建模。原版"虚弱"只削近战、对枪无效, 故本职业战斗削弱统一改用自定义效果"易伤"(见第四章)。
- 状态: 塔罗师已全量实现并合入 main。第十一章两项框架级前置均已建成并被本职业消费 —— `EnumMap<JobId,JobProgress>` 重构见 `com.miningdim.job.JobData` 与 `IMiningPlayerData#jobProgress`, 公共 menu 脚手架见 `com.miningdim.menu`(`AbstractMiningMenu` / `ModMenus`); `TarotSystem` 已挂进 `MiningDim` 子系统列表, `/tarot` 命令树、三种卡包、合成四结果与闪耀自选 GUI 均已可用。
- 数值状态: 22 张大阿卡纳的正位/逆位/闪耀效果已逐张评审定稿, 并落成 `src/main/resources/data/miningdim/tarot/cards/` 下 22 份 datapack JSON(格式见附录 A); 原第十三章标 PENDING 的系统级总闸门已按推荐值逐条落进 `TarotConfig`(运行期文件 `miningdim-tarot.toml`), 要调整改 toml 或改 `TarotConfig` 默认值即可, 无需重新拍板。
- 状态图例: DECIDED 已定稿。原图例中的 PENDING(待最终确认)与 TODO(实现期补全)两档已随全量落地撤销; 正文凡与代码冲突, 一律以代码为准。

---

## 一、职业定位与设计目标 (DECIDED)

1. 定位: 仿 FF14 生产/玩法职业, 玩家可同时持有全部职业。塔罗师以"抽卡 + 合成 + 打牌触发效果"为核心循环。
2. 品质: 塔罗牌分 5 品质 — 低级(R) / 中级(SR) / 高级(SSR) / 超凡(UR) / 闪耀。卡牌效果数值按四档 (a/b/c/d) = R/SR/SSR/UR 缩放; "闪耀"是该牌专属签名大招(不分正逆位、带长 CD)。
3. 等级: 塔罗师 1-10 级, 高等级才能使用高品质牌(门控卡在"用牌"而非"持有", 见第九章)。
4. 牌面: 大阿卡纳 22 张(0-XXI), 每张有正位/逆位两套效果。
5. 平衡总纲: 高品质严格更优(收益升序、自身惩罚降序); 抗性封顶 III; 最大生命增减有界; 无永久续航(用牌 CD + 强增益不可续期)。
6. 反代练: 经验"谁打谁得"、卡牌 ownerUUID 绑定、卡包用游戏内资源购买且每日限购(禁真钱 P2W)。

三道闸门(与农夫/工程师同构): 每日有效经验软上限 × 分级经验需求 × 等级门控可用品质。

---

## 二、核心机制总览 (DECIDED)

| 机制 | 一句话 | 章节 |
| --- | --- | --- |
| 卡牌物品 | 单一 Item + NBT(cardId / quality / orientation), 非 220 个独立 Item | 三 |
| 易伤效果 | 自定义 MobEffect, 提高受到的伤害, 替代枪服无用的"虚弱" | 四 |
| 大阿卡纳 | 22 张, 正位/逆位各 4 档 + 闪耀签名 | 六 |
| 卡包 | 普通/高级/闪耀 三种, 服务端 RNG, 闪耀含自选 GUI | 七 |
| 合成 | 2 张 → 1 张, 四结果(破碎/大破碎/逆转/成功)+ 碎片保底 | 八 |
| 等级经验 | 打牌结算谁打谁得, 复用 61900 曲线 + 每日衰减 | 九 |
| 经济 | 复用 AbuseGuard 物品扣费, 禁真钱, 每日限购 | 十 |

---

## 三、卡牌物品模型 (DECIDED)

单一 `TarotCardItem extends Item` + NBT 三键, **不做 220 个独立 Item**(22 牌×5 品质×正逆=220 会爆注册表/lang/模型, 且无法表达"逆转翻面"):

- `cardId` (0-21, 对应 enum `TarotArcana`, 承载该牌正/逆位双效果定义)
- `quality` (enum `TarotQuality`: R/SR/SSR/UR/闪耀)
- `orientation` (boolean 正/逆位)
- `ownerUUID` (绑定, 见第十章)

`stacksTo(1)`(不同 NBT 本就不堆叠, 单张杜绝歧义)。客户端按 cardId 给基础贴图 + 品质边框 overlay(`ItemProperties.register` model predicate, 在 `FMLClientSetupEvent`)。`appendHoverText` 读 NBT 显示牌名/品质/正逆位/效果。

---

## 四、自定义效果: 易伤 Vulnerability (DECIDED)

枪服里原版"虚弱"只削近战攻击、对枪无效, 故引入自定义负面效果"易伤"承载一切战斗削弱(对敌或逆位自惩)。

| 等级 | 受到伤害提高 |
| --- | --- |
| 易伤 I | +20% |
| 易伤 II | +35% |
| 易伤 III | +50% |
| 易伤 IV | +70% |
| 易伤 V | +100% |

- 类别 HARMFUL: 显红、属负面, 可被"去负面/净化"类(教皇/节制/星星)清除。
- 作用于全部来源入伤(枪/近战/坠落)。多源只取最高等级(不叠加)。**总易伤封顶 +100%**, 防一击放大链。
- 全部 % 进 config。
- 实现(Forge 1.20.1, 无需 mixin): 自定义 `MobEffect` 子类 → `DeferredRegister<MobEffect>` 注册到 `ForgeRegistries.MOB_EFFECTS`(图标 28x28 png + lang); 伤害放大挂 `LivingHurtEvent`(服务端): 受击者带易伤则 `event.setAmount(amount*(1+易伤%))`, 该步在护甲/抗性减伤后、黄心吸收前, 顺序合理。
- **建议注册在共享 `ModEffects`(非塔罗包内)**, 供未来其他职业/武器复用(多职业框架)。
- 实现期校验: 确认 TACZ 子弹伤害走标准 `LivingEntity.hurt` 管线(`LivingHurtEvent` 会触发), 开工跑一发子弹断言。

---

## 五、平衡硬规则 (DECIDED, 全表强制)

1. 抗性提升(Resistance)封顶 III(=60%减伤)。严禁 V/VI(=100%完全免疫/无敌)。
2. 最大生命增减有界: 增有上限(教皇 +40/+60/闪耀+120)、减有下限(maxHealth 不低于 20, 部分牌不低于 40)。
3. 高品质严格更优: 收益项随品质升序, 自身惩罚项(自伤/失明/缓慢/反胃/漂浮/减最大生命/易伤/死亡概率)随品质降序。
4. 无永久续航: 用牌全局 GCD + 每卡 CD; 强增益(抗性/隐身/伤害吸收/无敌窗/复活)同类不可续期(已存在则拒绝刷新)。
5. 战斗削弱用"易伤"不用"虚弱"; 削弱可被净化、有封顶。

---

## 六、大阿卡纳全表 0-XXI (DECIDED)

> (a/b/c/d) = 品质 R低/SR中/SSR高/UR超凡 四档; 闪耀 = 闪耀品质专属签名大招(带 CD)。基础卡每卡 CD 见第九章, 闪耀 CD 见本表。

| 牌 | 正位 (R/SR/SSR/UR) | 逆位 (R/SR/SSR/UR) | 闪耀 · CD |
| --- | --- | --- | --- |
| **0 愚者** | 每 30 秒瞬间治疗 ×3, 每次 (40/50/60/70) | 先自伤 (70/60/50/30), 后每 15 秒治疗 100 ×3 | 15 秒免疫全部伤害 + 回满血 · 10min |
| **I 魔术师** | (3/4/5/6) 秒 速度10 | (3/4/5/6) 秒 速度15 + 自身失明 (5/4/3/2) 秒 | 短按前后/长按前 瞬移 20 格 · 45s |
| **II 女祭司** | 预知 (12/16/20/25) 秒：高亮并显示 (24/32/40/48) 格内敌人生命；首次受击减伤 (20/25/30/35)% | 禁忌视野 (8/10/12/15) 秒：高亮并显示 (20/28/36/44) 格内敌人生命，施加易伤 (I/I/II/III)；自身黑暗 (4/4/3/2) 秒 | 清空自身全部塔罗 CD(不含闪耀级), 自身 CD 一天 · 24h |
| **III 女皇** | (30/45/60/75) 秒 生命恢复 (I/I/II/III) | (20/30/40/50) 秒 恢复III, 期间 −最大生命 (50/45/40/30) 〔下限20〕 | 50 秒恢复III + 360 秒 +50 最大生命 · 20min |
| **IV 皇帝** | (30/45/60/75) 秒 抗性 (I/I/II/II) | 自伤 (70/60/50/40) + 30 秒 抗性III | 30 秒抗性III + 周围 50 格敌缓慢III 60 秒 · 15min |
| **V 教皇** | 清自身全部负面, 每个 +(5/7/9/10) 最大生命〔封顶+40〕120 秒 | 清负面, 每个 +(5/5/10/15)〔封顶+60〕240 秒 + 前 (120/90/60/45) 秒漂浮 | 清全部负面, 每个 +20 最大生命〔封顶+120〕120 秒 · 9min |
| **VI 恋人** | 半径 (3/5/7/10) 格友方: 生命恢复 (I/I/II/III) (10/15/20/25) 秒 + 伤害吸收 (15/20/30/40) (10/15/20/25) 秒 | 半径内随机 1 敌 (150/100/60/40) 伤害(无敌人则自己双倍)+ 自身抗性III (15/17/20/25) 秒〔高档伤害更低=更安全〕 | 绑定 1 玩家(需同意)15 秒共享生死, 一方死另一方 3 秒后死, >50 格解绑 · 15min |
| **VII 战车** | (15/20/25/30) 秒 速度 (I/II/III/IV) + 冲锋后 (3/4/5/6) 秒 抗性III | 失控冲锋 (12/14/16/20) 格，沿途敌人受 (25/32/40/50) 伤害并撞飞；撞墙自身真伤 (20/16/12/8) | 冲锋 20 格击飞 + 10 秒免疫移动负面 + 速度III · 5min |
| **VIII 力量** | (30/40/50/60) 秒 力量 (V/VI/VII/VIII) + 伤害吸收 (15/20/25/30) | 野性过载 (20/25/30/35) 秒：力量 (III/IV/V/VI) + 吸血 (25/35/45/55)% + 免疫击退，生命≤50%时力量+1且吸血再+15%；代价易伤 (II/II/I/I) | 15 秒狮心: 力量V + 大量吸收 + 免疫击退与易伤 + 30% 吸血 · 8min |
| **IX 隐士** | (30/45/60/120) 秒隐身 + (10/15/20/25) 秒 力量 (I/II/III/IV) | (60/80/120/145) 秒隐身 + 自身易伤 (III/II/II/I) (120/100/80/60) 秒 | 15 秒遁世: 完全隐身 + 不可索敌 + 提灯高亮玩家/矿物, 期间不可攻击 · 7min |
| **X 命运之轮** | 随机 1 项增益 (15/18/22/26) 秒〔池: 力量II/速度II/抗性II/恢复II/跳跃III+夜视/吸收(20/25/30/40)〕, 抽强档概率 (30/45/60/75)%, 无坏结果 | 50% 瞬治 (45/55/65/80) / 50% 自伤 (45/40/32/24), 之后 (8/10/12/15) 秒 急迫II+速度II | 刷新自身全部增益至最高强度并延至 60 秒, 强制再抽必得 力量+抗性II 60 秒 · 12min |
| **XI 正义** | (10/13/16/20) 秒反伤 (30/40/50/60)%〔单次封顶40〕+ 自身抗性II | 准星锁定命中后, 自身与该敌当前血各设为均值〔单次最多±30〕+ 双方发光 (6/8/10/12) 秒 + 敌缓慢II (4/5/6/8) 秒 | 15 秒反伤 80%〔单次封顶80〕+ 免疫击退; 结束对 15 格内伤过你的敌各回击其累计伤害 40%〔封顶60〕· 10min |
| **XII 倒吊人** | (12/14/16/20) 秒 漂浮+缓慢I(代价) + 力量 (II/II/III/III) + 恢复 (I/II/II/III) + 吸收 (20/25/30/40) | 以命相赌: 有 (20%/12%/6%/2%) 概率当场死亡; 成功则牺牲当前最大生命 15(90 秒归还, 下限20)+ (25/30/35/40) 秒 力量V + 吸收 (40/50/60/70) + 免疫击退 + 35% 吸血 | 18 秒延迟记账(致命伤冻结不死)+ 力量V + 恢复III + 免疫击退; 结束结算挂起伤害 50%, 存活则 +40 血 · 11min |
| **XIII 死神** | 斩杀准星目标(当前血 < 16/20/24/28 处决), 否则 (40/55/70/90) 穿刺 + 凋零II (4/5/6/8) 秒 | 自伤 (30/24/18/12) 开契约, 60 秒内拦截 1 次致死并复活回 (30/40/50/65) + 抗性II/速度II (5/6/8/10) 秒 | 12 格内处决 <30% 血敌(每杀回 20 + 叠力量至V), 无目标则全体 50 穿刺〔回血/叠层仅对玩家/精英〕· 12min |
| **XIV 节制** | (20/26/32/40) 秒 恢复 (II/II/III/III), 每 5 秒净化 1 负面(至多 2/3/4/5 个) | 净化全部负面 → (6/8/10/12) 秒 恢复IV(代价: 挖掘疲劳II+反胃)+ (18/22/26/30) 秒均衡链接(仅友方每 2 秒高血→低血转 6) | 10 格内全体玩家 18 秒恢复III + 净化全部 + 所受伤害 10% 全场分摊; 自身 20 秒免疫缓慢 · 10min |
| **XV 恶魔** | (18/22/26/30) 秒 力量 (III/IV/V/VI) + (20/25/30/40)% 吸血, 代价: 全程凋零I + 结束易伤II (8/7/6/5) 秒 | 半径 (5/6/7/8) 格敌缓慢III (6/8/10/12) 秒 + 失明 (3/4/5/6) 秒; 自身 (20/24/28/32) 秒 力量IV+50%吸血+免疫击退, 每 5 秒自损 8 | 22 秒 力量VI+60%吸血+免疫击退与易伤, 拖 10 格敌至身前缓慢III, 自身 −30 最大生命〔下限20, 结束归还〕· 9min |
| **XVI 高塔** | 准星落雷, 半径 (3/4/5/6) 格敌 (40/55/70/90) 伤害 + 强击退; 命中则自身 (4/5/6/8) 秒抗性II | 自伤 (40/32/24/14) 引雷暴, 半径 (4/5/6/8) 格敌 (60/80/100/130) 伤害 + 失明 (2/3/4/5) 秒 + 拉向爆心 | 准星雷柱, 半径 10 格敌 150 伤害 + 强击退 + 5 秒失明 + 震碎其黄心与增益药水 · 6min |
| **XVII 星星** | 瞬治 (30/40/50/65) + (20/25/30/40) 秒恢复 (I/II/II/III) + 净化 (1/2/3/全部) 个负面 | 半径 (4/6/8/10) 格友方各瞬治 (40/50/60/75)+恢复III+净化 (1/2/3/全部); 代价: 自身 (20/16/12/8) 秒"力竭"(易伤 +25% + 缓慢II + 无法被治疗) | 半径 12 格友方各 +40 黄心 + 45 秒恢复III + 25 秒夜视/发光, 濒死(<25%)队友额外 +50 血 · 8min |
| **XVIII 月亮** | 半径 (3/4/5/6) 格敌 失明+缓慢 (I/II/II/III)+反胃 (2/3/4/5) 秒, 自身 (6/8/10/12) 秒隐身 | 自身 (10/14/18/24) 秒隐身+速度II, 半径 (4/6/8/10) 格敌 失明+缓慢III+易伤 (I/II/II/III) (6/8/10/14) 秒, 代价: 隐身期 −最大生命 (40/30/20/10) 〔下限40〕 | 半径 12 格敌 8 秒 失明+缓慢III+易伤III + 5 秒反胃 + 随机错位瞬移 3-6 格, 自身全程隐身+不可索敌 · 7min |
| **XIX 太阳** | (20/25/30/40) 秒 速度II+跳跃II+急迫II+力量 (I/I/II/II) + 清自身移动/视觉负面; 每秒灼半径 (2/3/4/5) 格敌 (4/5/6/8) | (20/25/30/40) 秒 力量 (III/III/IV/IV)+速度III+吸收 (25/30/35/45), 每秒灼半径 (3/4/5/6) 格敌 (6/8/10/13), 代价: 整段累计自伤封顶 (36/28/20/12) | 15 秒 力量IV+速度III+45 黄心+免疫缓慢/失明/反胃/易伤/凋零, 半径 8 格每秒灼敌 15 + 每秒为友回 12 + 净化 1 负面 · 8min |
| **XX 审判** | 瞬治 (35/45/55/70) + (8/10/12/15) 秒抗性II + 免疫凋零/中毒 + 净化负面 | 半径 (4/6/8/10) 格敌 (30/40/50/65) + 其已损血 (15/20/25/30)% 追加 + 发光 (4/5/6/8) 秒, 自身自伤 (35/28/20/12) | 满血急救 15 格内残血(<25%)存活队友 + 各 25 黄心; 半径 15 格敌 80 + 已损血 30% 追加 + 6 秒发光 + 3 秒缓慢III · 10min |
| **XXI 世界** | (25/30/40/50) 秒 抗性II+力量 (II/II/III/III)+速度II+恢复II + 瞬治 (30/40/50/60) + 吸收 (20/25/30/40) | (25/30/40/50) 秒 抗性III+力量 (III/III/IV/IV)+速度III+恢复III+吸收 (30/35/40/50) + 周围 (4/6/8/10) 格敌缓慢II, 代价: −最大生命 (40/35/30/20)〔下限40〕+ 结束 (10/8/6/4) 秒"重负"(易伤 +30% + 缓慢II) | 20 秒 抗性III+力量IV+速度III+急迫III+跳跃II+恢复III+50 黄心+夜视+免疫缓慢/失明/易伤/反胃, 每 5 秒补 25 黄心(不免疫直接伤害)· 9min |

全表数值进 config(建议牌效表走 datapack JSON, 每张牌一份, 全局旋钮走 ForgeConfigSpec, 见第十一章)。

---

## 七、卡包获取 gacha (已实现, 数值见 `TarotConfig` 的 gacha/economy 段)

卡牌只能开卡包获得, 三种卡包(均为物品, 右键 `use` 开启, 服务端 RNG 权威, `ItemHandlerHelper.giveItemToPlayer` 给物):

| 卡包 | 产出 |
| --- | --- |
| 普通卡包 | **信用点购买**。1 张低级(R), 随机 cardId + 随机正逆位 |
| 高级卡包 | **信用点购买**。3 张牌, 每张独立概率为 SR/SSR; 并有概率附带"派生卡包" |
| 闪耀卡包 | **不用信用点**: 稀有掉落 + 青辉石抽取(价格很高)。开出后**自选一张 SSR(高级)牌**(不含 UR/闪耀) |

已落地的约束(原 PENDING 推荐值, 现以 `TarotConfig` 默认值为准):
- 闪耀卡包: 来源为稀有掉落 + 青辉石抽取(高价), 不走信用点; 开出自选一张 SSR 牌(不含 UR/闪耀)。最强内容天然 PvE/稀有门控, 信用点 farm 不出来。
- 高级卡包的"派生卡包"期望个数 E < 1(几何收敛), 中级包不可派生更高级包, 防套娃无限繁殖; 派生总数并入每日上限兜底。实现期写 TDD: 配置概率跑 1e6 次断言派生包期望有限。
- 保底(pity): 连续 N 包未出某品质则保底必出; 重复牌转"塔罗碎片", 攒够换指定牌(给非洲玩家确定性毕业线)。N 与碎片转化率进 config。
- `use` 双端坑: RNG 抽卡仅服务端(`if(!level.isClientSide)`), 客户端只回 success 触发手臂动画。
- 所有概率进 ForgeConfigSpec, 硬编码即缺陷(C6)。

---

## 八、合成系统 (已实现, 概率见 `TarotConfig` 的 craft 段)

2 张同品质牌 → 1 张高一档牌, 合成链 R→SR→SSR→UR→闪耀。**闪耀品质牌仅由 UR→闪耀 合成产出, 且需塔罗师 L10**(这是闪耀品质牌的唯一来源)。点合成走 `AbstractContainerMenu.clickMenuButton`, 服务端裁决四结果:

| 结果 | 效果 |
| --- | --- |
| 成功合成 | 产出升一档牌 |
| 逆转 | 算合成成功, 产出升档牌但翻转正逆位(改正逆的唯一途径) |
| 破碎 | 消耗 1 张输入、不出货, 返还 1 塔罗碎片 |
| 大破碎 | 消耗 2 张输入、不出货, 返还 2 塔罗碎片 |

四结果概率(原 PENDING 推荐表, 已按原值落进 `TarotConfig` 的 craft 段; 大破碎率 = 1 − 成功 − 逆转 − 破碎, 派生不单列以保证四率和恒为 1):

| 档 | 成功 | 逆转 | 破碎 | 大破碎 |
| --- | --- | --- | --- | --- |
| R→SR | 50% | 12% | 28% | 10% |
| SR→SSR | 40% | 12% | 36% | 12% |
| SSR→UR | 28% | 12% | 45% | 15% |
| UR→闪耀(需 L10) | 15% | — | 55% | 30% |

- 闪耀为签名牌、不分正逆位, 故 UR→闪耀 无"逆转"结果(成功即出闪耀)。其成功率最低(15%), 是全合成链最难一档, 匹配最强牌定位。
- 升一档期望投入张数 ≈ 2 / P(成功+逆转); 碎片返还把"大破碎双毁"从破产随机游走改为有底进度条(攒够碎片换同档牌, 防低档玩家永久卡死)。
- 逆转产出物品质同样升级(翻面≠原地踏步)。
- 四结果服务端裁决, 客户端无权预知(防作弊)。
- 对应 config 键(`miningdim-tarot.toml` 的 `[craft]` 段): `rToSrSuccess`/`rToSrReverse`/`rToSrShatter`、`srToSsrSuccess`/`srToSsrReverse`/`srToSsrShatter`、`ssrToUrSuccess`/`ssrToUrReverse`/`ssrToUrShatter`、`urToShinySuccess`/`urToShinyShatter`; 碎片保底走 `[gacha]` 段的 `shardExchangeCost`(40)与 `duplicateShardRefund`(1)。

---

## 九、等级与经验 (已实现, 数值见 `TarotConfig` 的 experience/cooldown 段; 复用既验证曲线)

### 9.1 经验来源(谁打谁得)
- **打牌结算**: 打出一张牌结算一次原始经验(按品质, 正逆位可不同), 经验只给打牌者本人 UUID(买来的/别人的牌不转移经验)。
- 合成成功额外给小额经验。
- 单牌原始经验示例(进 config): R 8 / SR 16 / SSR 32 / UR 60 / 闪耀 120。
- 严禁靠开包/持有给经验(防代练)。

### 9.2 等级曲线(复用农夫/工程师已交叉验证, 总 61,900)
L1→2 3300 / 2→3 3800 / 3→4 4500 / 4→5 5300 / 5→6 6300 / 6→7 7400 / 7→8 8800 / 8→9 10300 / 9→10 12200。

### 9.3 每日有效经验软上限(复用工程师"奖励肝度"衰减表)
0-2000 ×1.00 / 2000-2800 ×0.40 / 2800-3400 ×0.20 / 3400-3800 ×0.08 / 3800+ ×0.02(软顶约 4000/天)。满级: 休闲约 30 天, 肝满约 16 天(已实算)。

### 9.4 等级 × 可用品质门控(用牌时校验)
L1 用 R / L3 用 SR / L5 用 SSR / L8 用 UR / L10 用 闪耀。门控卡在"用牌"服务端帧(不足则不应用效果+不消耗+提示), 不卡持有(可攒高品质牌等升级)。

### 9.5 用牌冷却(总闸门)
- 全局 GCD 1.5 秒(防一帧连甩)。
- 每卡 CD(进 config): 功能牌 ~10 秒、增益牌 ~20-30 秒、强战斗牌(皇帝/战车/恋人逆位/高塔/死神等)~30-60 秒; 闪耀按第六章表(分钟级)。
- 强增益(抗性/隐身/伤害吸收/无敌窗/复活)同类不可续期: 已存在同类则拒绝刷新, 防永久叠 buff。

---

## 十、经济、货币与反代练 (已实现, 价格与每日上限见 `TarotConfig` 的 economy 段; 例外见本节末)

- 货币: 全服基础货币 = **信用点**(余额存 Capability, 见服务器经济文档)。普通/高级卡包售价 = 信用点(价格进 config); 闪耀卡包不走信用点(稀有掉落 + 青辉石抽取)。扣费复用 `economy.AbuseGuard` 的事务安全模式(防双花)。**信用点/青辉石永不出售真钱**(规避真钱 P2W/抽奖合规; 信用点可买战斗内容=游戏内成长, 非真钱 P2W)。
- 每日购包上限并入 economy 既有 UTC 翻日体系, 与产矿软上限对齐, 防日产矿无限换战斗牌。
- 卡牌绑定: 开包时写 `ownerUUID`; 用牌校验 `ownerUUID == 使用者`(倒卖来的牌打不出效果), 从根上掐死二级市场与工作室刷包(仿工程师 producerUUID)。
- 卡牌/卡包的"禁入漏斗、禁交易"一条须拆开看, 当前实现只兑现了其中一半:
  - **禁入漏斗尚未实现**: `TarotConfig` 里没有任何绑定/禁交易开关, `com.miningdim.job.tarot` 整包也没有一处漏斗防护代码。为它准备的物品标签 `data/miningdim/tags/items/tarot_bound.json`(列 `tarot_card` 与普通/高级/闪耀三种卡包)确实存在, 但代码里没有任何一处读取它, 当前是死资源。全仓唯一的反漏斗实现在军火商模块(`MunitionsBenchBlockEntity` 的输入口 insert-only capability), 不在塔罗包内。
  - **禁交易只做到市场一侧, 且不是 config 开关**: `com.miningdim.market.MarketTradeWhitelist` 以硬编码规则在挂单路径拒绝 SR/SSR/UR/闪耀四档塔罗牌(并对容器内容物下钻一层, 防潜影盒整包过关), 但**放行最低档 R** 自由挂单; 该规则也不覆盖三种卡包(只认 `TarotCardItem`), 更管不到丢弃拾取、直接给予等非市场转移。`ownerUUID` 绑定管的是"买到手能不能用", 不阻止物品本身易手。
  - 待拍板(不在本文档单方权限内): 是否补齐 `job.tarot` 的反漏斗保护(仿军火商 insert-only, 或让漏斗交互读 `tarot_bound` 标签), 以及是否把市场品质门槛迁进 `TarotConfig` 做成可配置开关、R 档要不要一并纳入禁交易。

---

## 十一、架构落地 (DECIDED)

### 11.1 两项框架级前置(塔罗师开工前必做, 各自独立原子提交)
1. **玩家职业数据改 `EnumMap<JobId, JobProgress>`**: 现 `entry.MiningPlayerData` 是扁平字段, 塔罗师是第 4 个职业, 再平铺必成上帝对象。引入 `JobId{MINER,FARMER,ENGINEER,TAROT}` + `JobProgress{level,xp,dailyXp,dayStamp}`, 一处遍历 serialize/copyFrom(本前置已完成; `JobId` 此后扩到 8 个成员 MINER/FARMER/ENGINEER/TAROT/CHEF/AGENT/MUNITIONS/BREWER, 以 `com.miningdim.job.JobId` 源码为准 —— 渔夫当前未并入该枚举, 不计入); `IMiningPlayerData` 扩 `jobProgress(JobId)`。回退态/danger/instanceId 非职业字段不动。旧存档缺键给默认(level=1)。严禁新挂 capability(避免既裁的"双 capability 重复 attach"隐患)。
2. **公共 menu 脚手架**(`com.miningdim.menu`): 公共 `MenuType` DeferredRegister + 带正确 `quickMoveStack`/`stillValid` 的 `AbstractMiningMenu` 基类 + `IForgeMenuType.create` 传 BlockPos 工厂 + 客户端 `MenuScreens` 统一注册(`FMLClientSetupEvent.enqueueWork`)。开包自选 GUI、合成 GUI 与工程师生产台 GUI 共用一套, 全 1.20.1 写法(严禁 1.20.4+ custom payload / 1.20.5+ MapCodec)。

### 11.2 塔罗师本体
- `TarotSystem implements Subsystem`, `MiningDim.registerSubsystems()` 追加一行。等级走 entry capability; 牌品质/正逆位/ownerUUID 走 ItemStack NBT; 不污染 `MiningServices`(职业进度经 entry capability 取)。
- 易伤效果注册在共享 `ModEffects`(供多职业复用)。
- 牌效数值表走 datapack JSON(每张牌一份, 仿 `ORE_USE_DATAPACK` 先例; 字段结构见**附录 A**)。datapack 缺字段报错冒泡, 不静默给默认(C9)。
- 全局旋钮分两类落点, 不是笼统的一处:
  - 卡包出率(`[gacha]`)、合成四结果概率(`[craft]`)、每日购包上限与卡包价格(`[economy]`)、用牌 CD 与 GCD(`[cooldown]`)、打牌经验(`[experience]`): 走本职业自持的 `TarotConfig.SPEC`, 由 `TarotSystem.register` 内 `registerConfig` 注册为独立文件 `miningdim-tarot.toml`(范式同 `ChefConfig`)。按仓库"谁的旋钮谁带 spec 段"惯例**不进中央 `MiningServerConfig`**(该类 grep `tarot` 零命中), 理由见 `TarotConfig` 类注释。
  - 等级门控与易伤 % 两项**不是配置项**: 用牌等级门控硬编码在 `TarotQuality` 枚举的 `requiredLevel`(R=1/SR=3/SSR=5/UR=8/闪耀=10), 易伤各级放大比硬编码在共享 `VulnerabilityEffect` 的 `LEVEL_PCT`(0.20/0.35/0.50/0.70/1.00, 总封顶 `MAX_VULNERABILITY_PCT=1.00`)。二者都不经任何 ForgeConfigSpec, 服主改不了 toml, 只能改源码 —— 第四章"全部 % 进 config"是设计原意, 与当前实现分叉。

---

## 十二、可实现性结论 (Forge 1.20.1, DECIDED)

全部机制可实现, 无不可实现项。

| 模块 | 可实现性 | 关键 API | 工作量 |
| --- | --- | --- | --- |
| 卡牌物品(单 Item+NBT) | 可实现 | Item + NBT + appendHoverText + ItemProperties | 中 |
| 卡包开包/概率 | 可实现 | use() 服务端 RNG + giveItemToPlayer | 中 |
| 闪耀自选 / 合成 GUI | 可实现但有坑 | 公共 menu 脚手架(quickMoveStack) | 大 |
| 易伤自定义效果 | 可实现 | MobEffect + LivingHurtEvent 乘伤 | 小 |
| 药水/瞬治/瞬伤/AoE | 可实现 | addEffect / heal / hurt / getEntitiesOfClass | 小-中 |
| 最大生命增减 | 可实现但有坑 | 固定 UUID transient AttributeModifier + 到期/登出/死亡/换维度全清(防泄漏) | 中 |
| 抗性/反伤/斩杀/复活 | 可实现 | LivingHurtEvent/LivingDeathEvent + 拦截致死 | 中 |
| 周期/延迟效果 | 可实现但有坑 | ServerTickEvent 全局时钟 + 内存态 ScheduledEffectManager, 登出/死亡清队列 | 中 |
| 等级/经验/CD 数据 | 可实现 | 并入 EnumMap<JobId,JobProgress> | 小 |

实现期红线(战斗向正确性): 抗性绝不超 III; 最大生命修饰符用 transient + 固定 UUID + 在登出/死亡/Clone/**换维度**统一 removeModifier(本 mod 反复进出矿洞维度=最高频泄漏路径, 时钟用 `server.getTickCount()` 全局); "对自己造成伤害"用绕过护甲/抗性/吸收的 source 或直接 setHealth(否则被自己的抗性/黄心吞掉)。

---

## 十三、已落地的系统级闸门 (原 PENDING, 现以 `TarotConfig` 默认值为准)

原"进 Code 前最终拍板"的九条已全部落地, 逐条对应的 `miningdim-tarot.toml` 键如下; 要调数值改 toml 即可, 不必重开评审:
1. 用牌冷却(9.5): 全局 GCD 1.5s + 每卡 CD 分档 + 强增益不可续期。(`[cooldown]` `gcdTicks=30`、`utilityCdTicks=200`/`buffCdTicks=500`/`combatCdTicks=900`; 每张牌归哪档由其 datapack 的 `cooldownCategory` 决定)
2. 货币(十): 全服基础货币 = 信用点; 普通/高级卡包用信用点, 闪耀卡包用稀有掉落+青辉石抽取。每日限购。(`[economy]` `commonPackCredit=200`/`advancedPackCredit=1200`/`shinyPackAzure=64`/`dailyPackLimit=20`)
3. 经验来源(9.1): 打牌结算、谁打谁得 + 复用 61900 曲线与每日衰减。(`[experience]` `rawXpR/rawXpSr/rawXpSsr/rawXpUr/rawXpShiny = 8/16/32/60/120`, 合成成功 `rawXpCraftSuccess=10`)
4. 正逆位获取: 开包随机定(50/50)+ NBT 持久、用时不可改; 逆转合成是改正逆唯一途径。(50/50 是 `PackGachaService` 里的 `rng.nextBoolean()`, 硬编码无旋钮)
5. 卡牌绑定(十): ownerUUID 盖章已落地; 但"禁交易/漏斗"只兑现一半 —— 禁交易仅做到市场侧硬编码品质门槛(放行 R 档、不管卡包), 禁漏斗零实现, 详见第十章末。
6. 合成四结果概率与碎片保底(八): 按推荐表, 大破碎保留但配碎片返还防卡死。(`[craft]` 四档十一键 + `[gacha]` `shardExchangeCost=40`/`duplicateShardRefund=1`/`pitySsrPacks=10`)
7. 闪耀(品质)牌获取: **UR→闪耀 合成、需 L10**(已定, 见第八章)。闪耀卡包(掉落+青辉石)只出自选 SSR, 不产闪耀品质牌。(`craft.urToShinySuccess=0.15`)
8. 闪耀卡包自选范围 = 任意 SSR 牌(已定, 落在 `ShinyPackSelectMenu`); 是否进一步限非战斗弱牌可按线上数据再调。
9. 审判闪耀"急救残血存活队友"已避开未实现的倒地系统; 若将来加倒地待救窗再扩展。

---

## 十四、实现分解 (已完成, 留作实现索引)

前置框架(与工程师共享, 谁先做谁建):
1. `EnumMap<JobId,JobProgress>` 重构(独立提交)。
2. 公共 menu 脚手架。
3. 共享 `ModEffects` + 易伤效果(MobEffect + LivingHurtEvent)。

塔罗师本体:
4. `TarotSystem` 子系统 + `TarotArcana`/`TarotQuality` 枚举 + `TarotCardItem`(NBT) + 22 牌效果 datapack JSON。
5. 三种卡包物品 + 开包 RNG + 概率 config + 保底/碎片。
6. 合成台 + 合成 GUI + 四结果裁决 + 逆转翻面 + 碎片。
7. 闪耀自选 GUI。
8. 用牌交互(use + 服务端应用效果 + 消耗 + GCD/每卡 CD)+ 等级门控 + ownerUUID 校验。
9. 周期效果调度器(ScheduledEffectManager)+ 属性修饰符清理(登出/死亡/换维度)。
10. 信用点扣费(经 AbuseGuard 事务模式)+ 每日限购 + 经验结算 + 每日衰减入账。
11. 客户端: 牌面贴图/品质边框 + 易伤图标 + 效果粒子。

测试断言示例: 易伤III 使受伤 ×1.5; 抗性全表无 >III; 倒吊人逆位 R 档 20% 概率死亡、UR 2%; 满血敌被正义逆位均值化单次最多降 30; 倒卖给他人的牌打不出效果(ownerUUID 不匹配); 减最大生命后登出再登入 maxHealth 恢复基线(无泄漏)。

---

## 附录 A、牌效 datapack JSON 格式 (真源: `job/tarot/card/`)

第十一章说"牌效数值表走 datapack JSON", 这里给出那批 JSON 的字段契约, 免得改一张牌还要反推代码。
本附录内容全部从 `TarotCardData` / `TarotEffectOp` / `TarotEffectKind` / `TarotCardLoader` 的源码与 javadoc 汇总而来,
代码改了以代码为准。

### A.1 文件位置与加载

- 路径: `data/<命名空间>/tarot/cards/<NN>_<id>.json`, 本体在 `src/main/resources/data/miningdim/tarot/cards/`,
  `NN` 是两位卡序 (00-21), `id` 是 `TarotArcana` 的小写名 (如 `00_fool.json`、`21_world.json`), 共 22 份。
- 加载器 `TarotCardLoader` 是 `SimpleJsonResourceReloadListener`(目录 `tarot/cards`), 挂在 `AddReloadListenerEvent`,
  随服务端数据包重载生效。
- 报错行为 (C9, 不静默给默认): 22 张缺任意一张 -> `IllegalStateException`; JSON 结构错/必填字段缺失 ->
  `GsonHelper` 抛 `JsonSyntaxException`; `kind` 或 `cooldownCategory` 写了未知值 -> `IllegalArgumentException`。
  全部在数据包重载边界冒泡, 重载失败后取牌效同样抛出, 不会静默退回默认效果。

### A.2 顶层结构

```json
{
  "cooldownCategory": "buff",
  "upright":  { "tiers": [ [ /* R 档 op 列表 */ ], [ /* SR */ ], [ /* SSR */ ], [ /* UR */ ] ] },
  "reversed": { "tiers": [ [ /* R */ ], [ /* SR */ ], [ /* SSR */ ], [ /* UR */ ] ] },
  "shiny":    { "cooldownTicks": 12000, "ops": [ /* 签名大招 op 列表 */ ] }
}
```

- `cooldownCategory`: `utility` / `buff` / `combat` 三选一, 决定该牌走 `[cooldown]` 段的哪个键
  (`utilityCdTicks` / `buffCdTicks` / `combatCdTicks`, 见第九章 9.5)。
- `upright` / `reversed` 的 `tiers` **必须恰好 4 项**, 顺序即 R/SR/SSR/UR(第六章表的 a/b/c/d 四档); 多一项少一项直接抛错。
- `shiny` 不分正逆位、不走四档: 一组 `ops` + 自己的 `cooldownTicks`(第六章表里的分钟级 CD, 单位 tick)。
- 每个 tier / `ops` 是一个 **op 对象数组**, 按数组顺序依次执行; 空数组合法(该档无效果)。

### A.3 op 对象

每个 op 必填 `kind`(下表第一列), 其余字段随 kind 而定。字段语义随 kind 复用同一组载体
(`amount` / `radius` / `durationTicks` / `periodTicks` / `count` / `percent` / `chance` / `threshold` /
`capUp` / `floorDown` / `amplifier` / `effect`), 所以**只能照下表按 kind 填**, 不能按字段名想当然。
下表"必填"列缺任一项即解析期抛错; "可选"列缺失不是缺陷, 有各自的缺省语义
(如 `IMMUNITY` 缺 `effects` 表示该免疫窗只免易伤、不免任何 MobEffect)。

| kind | 作用 | 必填字段 | 可选字段 |
| --- | --- | --- | --- |
| `self_potion` | 给使用者本人加一个原版 MobEffect (effect=注册名, amplifier, durationTicks) | `effect`、`amplifier`、`durationTicks` | — |
| `aoe_enemy_potion` | 半径 radius 格内的敌对生物各加一个 MobEffect (effect/amplifier/durationTicks) | `effect`、`amplifier`、`durationTicks`、`radius` | — |
| `aoe_ally_potion` | 半径 radius 格内的友方玩家各加一个 MobEffect (恋人正位/节制闪耀) | `effect`、`amplifier`、`durationTicks`、`radius` | — |
| `self_heal_over_time` | 周期瞬治使用者: 每 periodTicks 治疗 amount, 共 count 次 (愚者正位/逆位) | `amount`、`periodTicks`、`count` | — |
| `self_periodic_absorption` | 周期给使用者补黄心: 每 periodTicks 把吸收补至至少 amount, 共 count 次 | `amount`、`periodTicks`、`count` | — |
| `self_heal` | 立即瞬治使用者 amount 点 (星星正位等) | `amount` | — |
| `self_true_damage` | 对使用者造成 amount 真实伤害, 绕过护甲/抗性/吸收 (逆位自惩; spec 实现红线) | `amount` | — |
| `self_absorption` | 给使用者加黄心 (吸收) amount 点 (恋人/世界等) | `amount` | — |
| `self_full_heal` | 把使用者治疗至当前最大生命 (愚者闪耀 "回满血"; 不写死定值, 随最大生命浮动) | 无 | — |
| `self_max_health` | 增/减使用者最大生命 amount (正=增, 负=减), durationTicks 后归还 | `amount`、`durationTicks` | `capUp`、`floorDown` |
| `self_cleanse` | 清除使用者全部负面效果 (教皇/节制/星星) | 无 | — |
| `self_cleanse_max_health` | 教皇专用净化结算: 清负面并按清除个数增最大生命, 总量不超过 capUp, durationTicks 后归还 | `amountPerEffect`、`durationTicks`、`capUp` | — |
| `self_blink` | 魔术师闪耀: 沿水平视线瞬移 amount 格; 潜行使用时向后, 普通使用时向前 | `distance` | — |
| `self_dash` | 战车: 沿视线冲锋 distance 格并把沿途敌人按 force 击退 | `distance`、`radius`、`force` | — |
| `self_premonition_scan` | 女祭司: 预知扫描敌人、显示生命；正位额外提供一次首击减伤，逆位改为向目标施加易伤 | `radius`、`durationTicks`、`firstHitReduction`、`vulnerabilityAmplifier` | — |
| `self_uncontrolled_dash` | 战车逆位: 强制向前失控冲锋，沿途伤敌并击退；撞墙时承受真实自伤 | `distance`、`radius`、`force`、`damage`、`collisionSelfDamage` | — |
| `self_wild_overdrive` | 力量逆位: 野性过载，获得动态力量、吸血与击退免疫，低生命时收益进一步提高 | `durationTicks`、`strengthAmplifier`、`lifesteal`、`lowHealthThreshold`、`lowHealthBonusLifesteal`、`lowHealthStrengthBonus` | — |
| `self_random_buff` | 命运之轮正位: 从定稿增益池随机一项, chance 决定强档 | `durationTicks`、`strongChance`、`absorptionLow`、`absorptionHigh` | — |
| `self_fortune_gamble` | 命运之轮逆位: chance 概率治疗, 否则真实自伤 | `healChance`、`heal`、`selfDamage` | — |
| `self_refresh_beneficial` | 命运之轮闪耀: 全部现有正面效果升至允许的最高级并延长 | `durationTicks`、`maxAmplifier` | — |
| `self_healing_block` | 星星逆位力竭: durationTicks 内无法受到治疗 | `durationTicks` | — |
| `self_delayed_potion` | 延迟施加自身药水效果 (恶魔/世界的结束代价) | `effect`、`amplifier`、`durationTicks`、`delayTicks` | — |
| `self_periodic_true_damage` | 周期真实自伤 (恶魔逆位每 5 秒自损) | `amount`、`periodTicks`、`durationTicks` | — |
| `self_cleanse_limited` | 只净化 count 个负面效果 | `count` | — |
| `self_periodic_cleanse` | 每 periodTicks 净化 1 个负面, 至多 count 次 | `count`、`periodTicks` | — |
| `self_cleanse_effects` | 只清除 effects 指定的负面效果 | 无 | `effects` |
| `self_hermit_shiny` | 隐士闪耀: 不可攻击、不可被生物索敌, 并执行玩家/矿物提灯高亮 | `durationTicks`、`radius` | — |
| `self_untargetable` | 月亮闪耀: 保持可攻击, 但生物不会索敌 | `durationTicks` | — |
| `clear_normal_tarot_cooldowns` | 女祭司闪耀: 清空使用者全部非闪耀塔罗牌 CD, 保留 GCD 与所有闪耀级 CD | 无 | — |
| `self_death_gamble` | 以命相赌 (倒吊人逆位): chance 概率当场死亡 | `chance`、`amount`、`durationTicks`、`floorDown` | — |
| `self_death_contract` | 复活契约 (死神逆位): durationTicks 内拦截 1 次致死并复活回 amount 血 (一次性) | `amount`、`durationTicks` | — |
| `self_knockback_immunity` | 免疫击退窗 (倒吊人逆位/力量闪耀等): durationTicks 内 LivingKnockBackEvent 强度归零 | `durationTicks` | — |
| `self_lifesteal` | 吸血窗 (倒吊人逆位/恶魔): durationTicks 内对敌造成伤害的 percent 回血给使用者 | `percent`、`durationTicks` | — |
| `self_reflect` | 反伤窗 (正义正位): durationTicks 内受伤把 percent 回击攻击者, 单次封顶 capUp | `percent`、`capUp`、`durationTicks` | — |
| `self_reflect_accum` | 累计反击窗 (正义闪耀结算尾): durationTicks 内逐攻击者累计其对使用者造成的伤害 | `percent`、`capUp`、`radius`、`durationTicks` | — |
| `self_delayed_ledger` | 延迟记账冻死窗 (倒吊人闪耀): 窗口内致命伤冻结不死, 结束时结算挂起伤害的 percent, 存活再回 amount | `percent`、`amount`、`durationTicks` | — |
| `self_invulnerable` | 无敌窗 (愚者闪耀): durationTicks 内对使用者的伤害归零 (真免疫, 非抗性减伤) | `durationTicks` | — |
| `shiny_bind_share_life` | 绑定共享生死 (恋人闪耀): 与已 `/tarot consent` 的玩家双向绑定, 一方死另一方 count ticks 后同死, 超 radius 解绑 | `durationTicks`、`radius`、`count` | — |
| `aoe_execute_below_pct` | 处决斩杀 AoE (死神闪耀): radius 格内当前血占比 < percent 的敌处决 (setHealth 0) | `percent`、`radius`、`amount`、`threshold`、`amplifier` | — |
| `enemy_target_damage` | 准星单体 (死神正位): 准星目标当前血 < threshold 处决, 否则 amount 穿刺 | `amount`、`radius`、`threshold` | — |
| `enemy_target_average_health` | 均值化 (正义逆位): 准星目标与使用者当前血各设为均值, 单次最多 ±capUp; reach=radius | `radius`、`capUp` | — |
| `enemy_target_potion` | 给准星敌人施加药水效果 (正义/死神的单体后效) | `effect`、`amplifier`、`durationTicks`、`reach` | — |
| `target_tower_strike` | 高塔: 在准星落点生成伤害区域、位移敌人, 可附带失明/清黄心与增益 | `amount`、`radius`、`force`、`mode` | `casterResistanceTicks`、`blindnessTicks`、`clearBuffs` |
| `aoe_enemy_random_damage` | 半径 radius 格内随机 1 敌受 amount 伤害, 无敌则使用者自身受双倍真伤 (恋人逆位) | `amount`、`radius` | — |
| `aoe_enemy_damage` | 半径 radius 格内的敌对生物各受 amount 伤害 (高塔/审判逆位/死神闪耀) | `amount`、`radius` | — |
| `aoe_ally_heal` | 半径 radius 格内的友方玩家各瞬治 amount (恋人逆位/星星逆位) | `amount`、`radius` | — |
| `aoe_ally_absorption` | 半径 radius 格内的友方玩家各加黄心 amount (星星闪耀/世界闪耀) | `amount`、`radius` | — |
| `aoe_enemy_pull` | 半径内敌人拖至使用者身前 | `distance`、`radius` | — |
| `aoe_enemy_random_teleport` | 半径内敌人随机错位瞬移 | `radius`、`minDistance`、`maxDistance` | — |
| `aoe_enemy_missing_health_damage` | 固定伤害 + 目标已损生命百分比追加伤害 | `amount`、`percent`、`radius` | — |
| `aoe_ally_cleanse_limited` | 半径内友方各净化 count 个负面 (count<0 表示全部) | `radius`、`count` | — |
| `aoe_ally_periodic_cleanse` | 周期为半径内友方每人净化 1 个负面 | `radius`、`periodTicks`、`durationTicks` | `amount` |
| `aoe_ally_balance_health` | 周期把友方高血者的 amount 生命转移给低血者 | `radius`、`periodTicks`、`durationTicks` | `amount` |
| `aoe_ally_damage_share` | 把半径内玩家组成 durationTicks 的伤害分摊组 | `radius`、`percent`、`durationTicks` | — |
| `aoe_ally_emergency_heal` | 把低于 percent 最大生命的存活队友直接回满并给予 amount 黄心 | `radius`、`thresholdPercent`、`absorption` | — |
| `aoe_ally_low_health_heal` | 低于 percent 最大生命的友方额外治疗 amount | `radius`、`thresholdPercent`、`amount` | — |
| `aoe_enemy_damage_over_time` | 周期 AoE 敌方持续伤害 (太阳系列"每秒灼敌"): 每 periodTicks 对半径内敌各 amount 伤害, 持续 durationTicks | `amount`、`radius`、`periodTicks`、`durationTicks` | — |
| `aoe_ally_heal_over_time` | 周期 AoE 友方持续治疗 (太阳闪耀"每秒为友回血"): 每 periodTicks 对半径内友方各瞬治 amount, 持续 durationTicks | `amount`、`radius`、`periodTicks`、`durationTicks` | — |
| `immunity` | 免疫窗 (太阳/世界/力量/恶魔闪耀): 窗口内拒绝施加 `effects` 列出的 MobEffect, `vulnerability=true` 时并免易伤放大 | `durationTicks`、`vulnerability` | `effects` |

约定与坑:

- `SELF_HEAL_OVER_TIME` / `SELF_PERIODIC_ABSORPTION` 的周期字段写作 `periodTicks`, 次数写 `count`(总时长 = 周期 × 次数);
  而 `AOE_*_OVER_TIME` 一类写 `periodTicks` + `durationTicks`, 次数由引擎按 `durationTicks / periodTicks` 算, 两者别混。
- `SELF_MAX_HEALTH` 的 `capUp`(增向封顶) 与 `floorDown`(减向下限) 按存在性读, 解析期不判朝向: 增向牌写 `capUp`, 减向牌写 `floorDown`。
- 单体准星类的射程复用 `radius` 载体, 但 `ENEMY_TARGET_POTION` 的 JSON 字段名是 `reach`。
- `AOE_ENEMY_DAMAGE_OVER_TIME` / `AOE_ALLY_HEAL_OVER_TIME` 的 `amount` 是第六章表里的扁平每跳值, 引擎施加时还会按目标
  最大生命的占比上限再 clamp 一道(防扁平值在低血杂兵上离谱), 故实际到手可能低于 JSON 写的数。
- `IMMUNITY` 的 `vulnerability` 是布尔, 控制是否在易伤单点仲裁里跳过放大; `effects` 是 MobEffect 注册名字符串数组。

---

## 附录 B、概念稿登记

美术概念稿存放在 `docs/assets/tarot/`, 与各自的消费方对应关系如下(避免出现无人认领的孤图):

| 文件 | 用途 | 消费方 |
| --- | --- | --- |
| `tarot_card_back_concept_v1.png` | 卡背视觉概念稿 | `tools/build_tarot_card_assets.py` |
| `tarot_craft_table_concept.png` | 合成台方块外观概念稿 | `src/main/resources/assets/miningdim/models/block/tarot_craft_table.json` 的 `credit` 字段 |
| `tarot_craft_ui_concept.png` | 合成界面概念稿 | **当前全仓无任何代码、脚本或文档引用**; 对应的界面版本待确认, 确认作废前不要当作现行界面依据 |
