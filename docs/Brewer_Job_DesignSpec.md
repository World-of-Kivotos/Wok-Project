# 酿酒师职业 设计规范 (Brewer Job DesignSpec)

状态: 设计锁定, 分阶段实现中。分支 `feat/brewer-profession`。

## 一、定位

至少七天周期的制造职业, 产出为酒。延续职业设计哲学 (FF14 生产职业思路): 主打经济产出与玩法深度,
战斗增益受控、严防战力叠叠乐。等级/经验走共享职业框架 capability (`JobId.BREWER`)。

完整生产闭环:
1. 酿酒台投料 -> 定时酿造 -> 产出"基酒"(年份 0), 品质按酿酒师等级 roll;
2. 基酒放入酒窖箱陈酿, 年份随时间长, 须不断填干小麦保鲜 (燃料门控);
3. 喝酒按强度 `S = 年份 × 品质系数` 获增益; 闪耀档触发各酒的"永久 (一条命)"特殊增益。

七天周期 = 酿造 (前半段, 分钟级) + 陈酿 (后半段, 天级长线), 主要时长在陈酿。

## 二、品质 (五档, 与其它职业一致)

品质决定"品质系数", 曲线由策划定:

| 档 | id | 系数 | 色 |
|---|---|---|---|
| 低级 | low | 1.0 | 灰 |
| 中级 | mid | 1.5 | 白 |
| 高级 | high | 2.0 | 青 |
| 超凡 | superb | 3.0 | 紫 |
| 闪耀 | brilliant | 5.0 | 金 |

品质在酿酒台酿造时按酿酒师等级加权 roll (高等级才可能出超凡/闪耀)。落地: `WineQuality`。

## 三、年份 (酒窖箱陈酿)

- 表示: 酒类型即物品身份 (九种酒各一 item), 品质 + 年份存 ItemStack NBT (`WineNbt`, 沿用厨师
  `ChefQualityNbt` 范式); 不为 45 个 (类型×品质) 各注册一个物品。
- 时钟: 与潮汐 (Tide, Lightning-64/Tide 1.20.1) mod 同源 —— 二者都读原版 `level` 时钟, 不引入自定义
  时钟、零跨 mod 依赖 (Tide 不在本工程 classpath, 不可硬依赖)。
  - 基础年份累积按 `level.getGameTime()` 的 tick 差 (单调递增; 不受 `/time set`、睡觉跳夜影响 —— 那两者
    改的是 `getDayTime` 而非 `getGameTime`; 只在维度加载 + 服务器运行时推进, 无离线白嫖陈酿)。默认
    `TICKS_PER_VINTAGE_YEAR = 24000` (1 年份 = 一个游戏日)。
  - 满月加成 (潮汐关联): 满月 (`level.getMoonPhase()==0`) 期间陈酿额外 +25% 年份增量, 呼应 Tide 满月夜
    出最稀有鱼的设计。纯原版 API 读取, 零耦合。
- 取出: 中途从酒窖箱取出, 年份冻结暂停 (停在当前值, 放回继续累积), 不清零。
- 燃料门控: 干小麦由小麦烘制 (要求量大, 是长线职业的小麦 sink, 联动农夫经济)。酒窖箱每瓶酒每陈酿 1
  年份消耗 `DRIED_WHEAT_PER_BOTTLE_YEAR = 16` 干小麦; 燃料不足则陈酿暂停 (只结算燃料覆盖到的年份, 未覆盖
  的 gameTime 跨度作废) —— 这是"必须不断填充"的压力, 也封死燃料外的免费陈酿。

强度公式统一入口: `WineNbt.strength(stack) = 年份 × 品质系数`。落地: `VintageClock` (纯静态换算, 便于
GameTest 确定性验证) + `BrewerConstants` (单一来源可调常量)。

## 四、酒类型 (九种) 与喝酒效果

普通效果按强度 S 缩放 (S=0 的新酒几乎无效, 逼你陈酿); 闪耀档触发"永久 (一条命)"特殊增益:

| 类型 | 普通效果 | 闪耀 (永久·一条命) | 原料 |
|---|---|---|---|
| 白兰地 brandy | 急迫 (挖矿酒) | 永久急迫 | 小麦(大)·苹果 |
| 伏特加 vodka | 抗性提升 | 永久 20% 减伤 | 小麦(超大) |
| 金酒 gin | 金心吸收 | 永久生命上限 (带帽) | 小麦·糖 |
| 朗姆酒 rum | 速度 | 永久移速 | 甘蔗·小麦 |
| 龙舌兰 tequila | 力量 | 永久力量 | 萝卜·小麦 |
| 茅台 maotai | 给经验值 | 职业经验加成 | 小麦·稻米 |
| 威士忌 whiskey | 瞬间恢复 | 周期性瞬间恢复 | 小麦 |
| 香槟 champagne | 生命恢复 | 常驻生命恢复 | 小麦 |
| 月光 moonshine | 赌博 (随机好/坏) | 赌博 (永久随机好/坏) | 烈酒·小麦 |

月光为赌博性质: 加权随机表, 好结果 (强随机增益) vs 坏结果 (中毒/反胃/虚弱); S 越高好结果概率与强度越高;
闪耀月光把结果变永久 (高赌注)。落地: `WineType` + 后续 `BrewEffectEngine` / `BrewMoonshineTable`。

## 五、闪耀永久增益 (一条命)

策划定: 可叠加, 但有上限, 死亡丢失。

- 上限 `MAX_PERMANENT_BUFFS = 3`: 同时在身的永久增益封顶 3 个, 满则 FIFO 替换最旧。
- 死亡丢失: `LivingDeathEvent` 清空该玩家全部永久增益 + 摘除属性修饰/效果。
- 跨下线保留: 永久增益持久化 (`BrewBuffStore extends SavedData`), `PlayerLoggedInEvent` 重挂; 仅死亡清。
- 单项数值帽: 金酒永久生命上限有硬帽 (`GIN_MAX_HEALTH_CAP`, 经 `MaxHealthModifierManager.capUp` 执行);
  伏特加永久减伤 `VODKA_DAMAGE_REDUCTION = 0.20`。

落地: `BrewBuffStore` + `BrewPermanentBuffs` (后续阶段)。

## 六、实现分期 (原子提交)

1. 地基 (本提交): `WineQuality` / `WineType` / `BrewerConstants` / `VintageClock` / `WineNbt` /
   `BrewerSystem` 占位登记 + `JobId.BREWER` 接入 + 地基 GameTest。编译 + GameTest 全绿。
2. 酒物品 + 喝酒普通效果 + lang。
3. 酿酒台 (方块/BE/menu/screen + 按等级 roll 品质 + 酿造经验) + 酿造配方 + 干小麦配方。
4. 酒窖箱 (方块/BE/menu/screen + 年份陈酿 + 燃料门控 + 满月加成 + 取出冻结)。
5. 闪耀永久增益 (Store/封顶/死亡清/登录重挂) + 九种闪耀特殊。
6. 月光赌博表 + 收尾打磨。

每期: 编译 (JDK17) + `runGameTestServer` 全绿 (验 `run/logs/latest.log` 的 `All N required tests passed`)
后再提交。
