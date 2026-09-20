# 铸甲师护甲系统设计与实装规格

> 状态：插板护甲与电浆护盾两个护甲种类均已实装，数值仍可调。
> 更新日期：2026-09-20。
> 适用平台：Minecraft 1.20.1、Forge 47.x、Java 17、TaCZ 1.1.8。
> 文档分工：本文件负责铸甲师的护甲子系统，即护甲种类、身份字段、R/Q/G/T、材料修正与耐久、54 件插板映射、电浆护盾与枪匠联动；同一职业的纳米维修套件、生产台、职业等级与纳米特效见 docs/MillenniumEngineer_Mod_DesignSpec.md。两份文档描述的是同一个职业的两个部分，不是两个职业：它们共用 JobId.ENGINEER、共用 com.miningdim.job.engineer.EngineerSystem（name() 返回 ArmorerSystem）、共用 miningdim-engineer.toml 与同一个铸甲师创造页签。
> 界面、创造页签与 /job 系列命令的显示名称统一为“铸甲师”（翻译键 job.miningdim.engineer）；/job info 与 /job set 的职业参数同时接受 engineer、armorer 与“铸甲师”三种写法（JobId.byId）。JobId.ENGINEER、engineer 注册 ID、NBT 键与配置文件名 miningdim-engineer.toml 保留不动，以兼容旧存档与既有模块。

## 一、当前结论

本子系统已实装“插板护甲”与“电浆护盾”两个护甲种类。

插板护甲已经完成 54 件独立胸甲物品、54 张 48×48 物品图标、54 张自定义穿戴贴图与 49 个自定义穿戴模型（同款不同配色共享几何）、I 至 VI 六个等级、轻中重三种类型、七种材料特性、TaCZ 弹道结算、普通物理伤害结算、FE 电力层（见 3.6）和服务端配置。

电浆护盾已经完成 18 件正式物品（nano、standard、quantum 三个系列各 I 至 VI 六级，见 PlasmaShieldVariant 与 PlasmaShieldSeries）、3 个不进创造页签的旧 ID 兼容物品（PlasmaShieldType：nano、light、heavy_ion）、能量池与过热重启状态机、散热与回能、移速修正、独立网络频道与客户端 HUD，详见第十一章。

两个种类都占用胸甲槽（PlateArmorItem 与 PlasmaShieldItem 的 equippedBy 都读 EquipmentSlot.CHEST），因此互斥，不存在同时穿一件插板与一面护盾的叠加情形。

插板护甲的四个身份字段必须始终按以下顺序显示：

    等级：V
    种类：插板护甲
    类型：重型
    材质：钛

字段含义固定如下：

| 字段 | 当前含义 | 是否决定防护数值 |
|---|---|---|
| 等级 | I、II、III、IV、V、VI，全护甲种类共用的强度级别 | 是 |
| 种类 | 护甲采用的防护机制；当前为插板护甲与电浆护盾两种 | 是，不同种类使用不同公式 |
| 类型 | 某一种类内部的构型；插板护甲为轻型、中型、重型 | 是 |
| 材质 | 主防护结构材料；模块化载体取默认前后主板，固定式护甲取内置材料 | 是，在等级与类型基础值上修正防护、抗压、耐久和机动 |

“类型”不是跨所有护甲种类共用的死枚举。已实装的电浆护盾就没有沿用插板的轻中重，而是用 nano、standard、quantum 三个系列（PlasmaShieldSeries）承担同级内部构型的角色，各系列的能量、发热、散热与移速取舍互不相同。

等级是总体强度主轴，类型决定同级轻中重基准，材质只在该基准上形成偏科。同等级、同类型的不同材料会得到不同的缓冲、抗穿、防护率、抗压、耐久和机动；材料不会改写等级，也不能让低一级护甲在综合评价上反超高一级。单项允许跨级，例如低一级装甲钢可能比高一级轻质材料更抗穿。玩家给出的“V 级、插板护甲、重型、超高分子聚乙烯”是合法的排版示例，但当前 54 件原型中没有这一具体组合；当前映射不为凑示例而改动。

“插板护甲”是 Wok 的玩法种类名，不代表 PACA、UNTAR 等所有原型在《逃离塔科夫》或现实中都具有可拆卸插板。

### 1.1 核心战斗定位

插板护甲的核心定位固定为：对普通子弹、近战、投射物和爆炸等直接伤害提供良好且稳定的抗性，但不是覆盖所有伤害机制的万能防具。玩家穿着符合当前阶段的插板后，应能显著提高正面交火容错；面对持续伤害、技能伤害和高穿甲弹时，仍必须依靠走位、掩体、打断、治疗与弹药识别。

- 直接伤害是插板的优势领域。符合分类的普通弹道段由缓冲承担，普通物理直接伤害由防护率和抗压承担；
- DoT（持续伤害）是明确短板。燃烧、中毒、流血、腐蚀等持续结算不因插板等级提高而获得常规减伤；若一次攻击同时包含直接命中和后续 DoT，插板只保护其中符合分类的直接命中部分；
- 技能伤害是明确短板。带预兆、可躲、可打断或要求团队处理的技能不能因为高等级防护率和抗压而被压成普通小伤害；插板只应提供有限容错，不能替代技能机制；
- 穿甲弹是明确反制。穿甲比例越高，进入较弱抗穿段的伤害越多，高穿甲弹必须比低穿甲弹更能缩短高等级插板提供的生存时间；
- 任何后续调参都必须保留以上强弱关系，不得通过同时抬高抗穿、防护率和抗压，把插板改成对枪弹、DoT 与技能都同样有效的全能护甲。

## 二、插板护甲的等级与类型

### 2.1 类型定位

| 类型 | 防护定位 | 移速修正 | 玩法 |
|---|---|---:|---|
| 轻型 | 同级最低 | +10% | 游击、侦察、快速换位 |
| 中型 | 同级居中 | 0% | 通用基准 |
| 重型 | 同级最高 | -12% | 阵地、突破、正面交火 |

最终机动修正等于类型修正减去材料惩罚。当前实装物品中，轻甲约为 +8.5% 至 +10%，中甲约为 -3% 至 0%，重甲约为 -15% 至 -12.5%。第一版只修改基础移动速度；冲刺会继承同一移动属性倍率，但不另加冲刺、跳跃、换弹或瞄准专属修正。该差异按 Wok 的战斗节奏主动放大，塔科夫只用于原型与材料参考，不约束移速幅度。

### 2.2 现有物品分布

不是 18 个等级类型格都必须有现成原型。本期 54 件护甲占用 13 个格：

| 等级 | 轻型 | 中型 | 重型 | 合计 |
|---|---:|---:|---:|---:|
| I | 2 | 0 | 0 | 2 |
| II | 1 | 0 | 0 | 1 |
| III | 2 | 6 | 0 | 8 |
| IV | 6 | 9 | 3 | 18 |
| V | 5 | 6 | 9 | 20 |
| VI | 1 | 2 | 2 | 5 |
| 合计 | 17 | 23 | 14 | 54 |

钻石套是平衡锚点而不是底层实现：无附魔满耐久钻石套在 Wok 中顶多相当于 III 级中型插板护甲。插板启用后不再调用原版护甲公式叠算。

## 三、伤害系统

### 3.1 四个属性

物品提示统一使用中文名称，玩家不需要理解公式字母。R、Q、G、T 只作为配置项与公式中的内部符号保留。
缓冲、抗穿、防护率按百分比显示；抗压按伤害点显示，不是百分比、耐久或额外生命值。

| 内部符号 | 玩家名称 | 作用对象 |
|---|---|---|
| R | 缓冲 | TaCZ 普通弹道段的防护率 |
| Q | 抗穿 | TaCZ 穿甲段的防护率 |
| G | 防护率 | 白名单内非 TaCZ 物理伤害的防护率 |
| T | 抗压 | 一次通用物理伤害中最多有多少伤害可获得防护率减免 |

Q 是第一版原型数值，目的在于拉开 IV 至 VI 的成长，同时保留穿甲弹反制。后续实弹测试可以调数值，调参规范应保持 Q 小于同格 R；当前配置只分别强制 R/Q 位于 [0,1)，尚未做 Q<R 的运行时交叉校验。

四张等级类型矩阵给出基础值 R0、Q0、G0、T0，材料通过漏伤倍率 kR、kQ、kG 与抗压倍率 kT 得到最终值：

    R = max(0, 1 - (1 - R0) × kR)
    Q = Q0 为 0 时保持 0，否则 max(0, 1 - (1 - Q0) × kQ)
    G = max(0, 1 - (1 - G0) × kG)
    T = T0 × kT

漏伤倍率小于 1 代表材料更好，大于 1 代表更差。例如 kR=0.88 表示把原本会漏过 R 的伤害再降低 12%，不是把 R 直接增加 12 个百分点。这样 45% 基础缓冲变为 51.6%，94% 基础缓冲只变为 94.72%，高等级不会因为材料修正频繁撞到 99% 上限。基础值为 0 时保持 0，材料不能凭空创造该项防护；差材料最多把该项降到 0，不会反向放大来伤。

### 3.2 TaCZ 弹道

TaCZ 已把一颗子弹拆成普通段与穿甲段，插板不重新读取枪械 NBT，也不重算枪匠系数：

    普通段结算后 = 普通段结算前 × (1 - R)
    穿甲段结算后 = 穿甲段结算前 × (1 - Q)

若用 H 表示枪匠、距离、爆头等进攻计算完成后的整弹伤害，用 p 表示 TaCZ 穿甲比例，则概念总式为：

    D = H × (1 - p) × (1 - R) + H × p × (1 - Q)

精确分类如下：

| TaCZ 伤害类型 | 插板规则 |
|---|---|
| tacz:bullet | R |
| tacz:bullet_void | R |
| tacz:bullet_ignore_armor | Q |
| tacz:bullet_void_ignore_armor | Q |
| 其他未知 tacz 命名空间伤害 | 保守排除，不误入 G/T |

进入两个 LivingHurt 伤害段的值，以及随后 Post/Kill 事件中用于磨损的 base 值，都已经包含爆头倍率，因此插板不会再乘一次。Pre.getBaseAmount 在 TaCZ 内部仍是乘爆头前的值，但本实现的 Pre 只捕获护甲引用，不用它计算伤害或磨损。第一版没有身体部位覆盖判定：只要胸甲槽穿着可用插板，符合分类的爆头伤害也会进入 R 或 Q。

### 3.3 非 TaCZ 通用物理伤害

白名单内的普通近战、玩家近战、投射物、爆炸和冠军技能 AOE 使用：

    D = X - min(X, T) × G

其中 X 是进入插板前的本次伤害。X 不超过 T 时，整击都获得 G 防护；X 超过 T 时，超过部分完全通过。这让高等级护甲能稳定承受常规攻击，又不会把后期精英怪的超高单次伤害压成固定小数。

当前进入 G/T 的来源：

- 原版生物近战与无仇恨近战；
- 玩家近战；
- 原版 projectile 标签；
- 原版 explosion 标签；
- Wok champion_skill_aoe。

当前明确排除：

- bypasses_armor；
- 火焰；
- 魔法、间接魔法；
- 凋零、凋零头、龙息；
- 荆棘反伤；
- 未列入白名单的环境与第三方伤害；
- 未识别的 TaCZ 新伤害类型。

### 3.4 DoT、技能伤害与穿甲弹边界

三类短板的结算边界如下：

| 威胁 | 插板应有表现 | 主要应对 |
|---|---|---|
| DoT | 不进入 R/Q/G/T，保留绝大部分持续威胁 | 治疗、净化、脱离伤害源 |
| 技能伤害 | 只提供有限容错；高伤技能应通过抗压溢出、部分绕过或独立技能规则保持威胁 | 走位、打断、掩体、团队机制 |
| 穿甲弹 | 穿甲段只使用明显弱于缓冲的抗穿；穿甲比例越高，插板收益越低 | 更换战术、减少暴露、优先处理高穿甲火力 |

当前 DoT、反伤和处决绕过插板，穿甲弹按较弱的 Q 结算，已经符合上述方向。当前 `champion_skill_aoe` 仍统一进入 G/T；由于 V、VI 级现有防护率和抗压较高，部分精英技能可能被减到接近普通攻击的水平。这是待修正的实现与数值缺口，不代表最终设计允许插板擅长抵抗技能伤害。

后续验收必须同时满足：同阶段插板能明显延长玩家承受普通直接伤害的时间；高穿甲弹仍能显著缩短该时间；DoT 不随插板等级同步失去威胁；精英怪大招不能只靠站立承伤解决。若四项无法同时成立，应优先调整技能伤害入口和抗压覆盖范围，而不是继续抬高全部防护属性。

### 3.5 与原版护甲的关系

功能正常的插板胸甲会把玩家的原版护甲值和护甲韧性整体归零，再由 R/Q/G/T 独立结算。这样不会出现“插板减一次、钻石甲或其他模组护甲再减一次”的叠甲。

这一规则也适用于被插板排除的伤害：排除表示插板不减伤，同时不会退回原版护甲值或韧性。它仍可能受到抗性、职业减伤、保护附魔等其他独立层影响。该行为是第一版的明确规则，不是遗漏。

插板耗尽、卸下或更换后，原版护甲值、韧性和机动属性会恢复。正常事件链会在同一次伤害流程或装备变化时清理；若第三方模组在后续阶段取消伤害，异常流程最迟由当 tick 末的同步兜底清理，避免同 tick 换装或击碎后长期留下错误属性。

### 3.6 插板的 FE 电力层

插板护甲除了耐久以外还有一层独立的 FE 电力约束，配置在 plateArmor 的 power 段（PlateArmorConfig）。它是生存向硬约束，规划获取链与配服时不能漏掉。

- 出厂满电：电量存在物品 NBT 键 PlateArmorEnergy 上，随护甲一起进箱子、掉落与交易；NBT 里没有该键时按满电解析，新造出来的护甲立刻可用（PlateArmorPowerCell）；
- 按吸收量计费：每实际吸收一点伤害扣 fePerAbsorbedDamage 的电，默认 5,000 FE；单件电池容量 energyCapacity 默认 2,000,000 FE，即满电约可吸收 400 点伤害。挂着不打架不掉电；
- 余额不足按比例回退：本次可支付的部分照常减伤，支付不起的部分原样通过，不会出现“最后一点残电挡满整发”的免费防护（PlateArmorDamageHandler）；
- 零电即退化：电量归零后插板完全退化成普通护甲，既不减伤也不磨损，不静默假装工作；
- 只进不出：电量走标准 IEnergyStorage capability 暴露，extractEnergy 恒返回 0、canExtract 为 false，外部只能充电不能抽电。护甲穿在身上无法接线缆，无线充电（如后续接入的 Flux Networks）是它合理的补给方式；
- fePerAbsorbedDamage 设为 0 即整层关闭，此时插板退回纯耐久模型。

这一层与第九章的“最终属性不写入物品 NBT”不冲突：不写 NBT 说的是等级、类型与材料衍生的 R/Q/G/T 等最终属性，电量状态本身确实写在 ItemStack NBT 上。

## 四、默认数值

下列四张表是等级与类型的基础矩阵，尚未叠加材料修正。所有数值都位于服务端 miningdim-engineer.toml 的 plateArmor 配置段，可在后续测试中微调。

### 4.1 缓冲（内部符号 R）

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 45% | 50% | 55% |
| II | 60% | 65% | 70% |
| III | 75% | 80% | 85% |
| IV | 85% | 88% | 90% |
| V | 90% | 92% | 94% |
| VI | 94% | 96% | 98% |

### 4.2 抗穿（内部符号 Q）

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 0% | 0% | 0% |
| II | 2% | 5% | 8% |
| III | 8% | 10% | 15% |
| IV | 15% | 20% | 25% |
| V | 25% | 35% | 45% |
| VI | 45% | 50% | 55% |

### 4.3 防护率（内部符号 G）

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 35% | 40% | 45% |
| II | 45% | 50% | 55% |
| III | 60% | 68% | 70% |
| IV | 70% | 76% | 78% |
| V | 78% | 84% | 86% |
| VI | 86% | 88% | 90% |

### 4.4 抗压（内部符号 T）

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 16 | 20 | 24 |
| II | 24 | 32 | 38 |
| III | 38 | 48 | 58 |
| IV | 58 | 72 | 84 |
| V | 84 | 96 | 112 |
| VI | 112 | 128 | 154 |

### 4.5 受到 20 点伤害的基础矩阵样本

下表用于核对六级中型基础格，未叠加具体材料。它只展示插板层输出，不含抗性、附魔、职业被动和吸收生命。“纯普通段”是 p=0 的肉伤端点，“纯穿甲段”是 p=1 的穿甲端点，并不是同一颗混合弹同时造成两列伤害；混合列展示 p=20% 时，16 点普通段加 4 点穿甲段的整弹输出。

| 等级 | TaCZ 纯普通段 | TaCZ 纯穿甲段 | TaCZ 混合段 p=20% | 通用物理 |
|---|---:|---:|---:|---:|
| I 中型 | 10 | 20 | 12 | 12 |
| II 中型 | 7 | 19 | 9.4 | 10 |
| III 中型 | 4 | 18 | 6.8 | 6.4 |
| IV 中型 | 2.4 | 16 | 5.12 | 4.8 |
| V 中型 | 1.6 | 13 | 3.88 | 3.2 |
| VI 中型 | 0.8 | 10 | 2.64 | 2.4 |

V 级重型基础格受到 20 点伤害时：

- TaCZ 普通段：20 × (1 - 94%) = 1.2；
- TaCZ 穿甲段：20 × (1 - 45%) = 11；
- 通用物理：20 - min(20,112) × 86% = 2.8。

实际物品会继续叠加材料。以两件同为 V 级轻型的护甲为例：

| 材料样本 | 缓冲 | 抗穿 | 防护率 | 抗压 | 机动 | 耐久 |
|---|---:|---:|---:|---:|---:|---:|
| TacTec，超高分子聚乙烯 | 91.2% | 19% | 79.32% | 90.72 | +10% | 850 |
| Gladiator-S 轻型，陶瓷 | 90.6% | 34% | 78% | 96.6 | +9.5% | 420 |

两者等级和类型相同，但超高分子聚乙烯更耐用、更轻且偏缓冲，陶瓷则明显偏抗穿和抗压。

原版无附魔钻石套为 20 护甲、8 韧性。按原版公式承受一次 20 点护甲敏感伤害时约剩 8 点；实际 III 级装甲钢中甲 6B23-1 对同值通用物理剩 5.632 点，对 TaCZ 普通段剩 3.52 点，对穿甲段剩 15.84 点。因此“钻石甲顶多 III 级中甲”成立，同时穿甲弹仍能明显克制插板。

## 五、材质、防护与耐久

### 5.1 七种材料默认值

材料属性只从防震或吸能、耐久、形变与失效方式出发；机动惩罚另由材料的质量与所需厚度决定。这里的“优秀”等级是七种材料之间的相对定位，不是现实防弹认证。钛/芳纶与陶瓷/芳纶不再作为独立材料，统一并入有多层异材质结构含义的“复合材料”。

| 材质 | 缓冲漏伤倍率 | 抗穿漏伤倍率 | 防护漏伤倍率 | 抗压倍率 | 最大耐久 | 材料机动惩罚 | 件数 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 芳纶 | 0.94 | 1.18 | 1.00 | 0.90 | 900 | 0% | 1 |
| 超高分子聚乙烯 | 0.88 | 1.08 | 0.94 | 1.08 | 850 | 0% | 13 |
| 复合材料 | 0.94 | 0.94 | 0.94 | 1.08 | 610 | -1% | 10 |
| 钛 | 0.90 | 0.98 | 0.94 | 1.08 | 700 | -0.5% | 5 |
| 铝 | 0.98 | 1.00 | 0.98 | 1.00 | 580 | -1.5% | 4 |
| 装甲钢 | 0.88 | 0.88 | 0.88 | 1.15 | 760 | -3% | 10 |
| 陶瓷 | 0.94 | 0.88 | 1.00 | 1.15 | 420 | -0.5% | 11 |
| 合计 | — | — | — | — | — | — | 54 |

倍率小于 1 代表漏伤更少，只有抗压倍率是大于 1 更好。定性定位如下：

| 材质 | 缓冲 | 抗穿 | 防护率 | 抗压 | 耐久 | 主要代价 |
|---|---|---|---|---|---|---|
| 芳纶 | 优秀 | 极差 | 普通 | 差 | 极其优秀 | 依赖纤维拉伸吸能，硬质穿透与形变承压弱 |
| 超高分子聚乙烯 | 极其优秀 | 差 | 优秀 | 优秀 | 极其优秀 | 抗穿偏弱，但质量最低且长期耐用 |
| 复合材料 | 优秀 | 优秀 | 优秀 | 优秀 | 良好 | 多层结构均衡，质量与维修成本居中 |
| 钛 | 极其优秀 | 中等偏好 | 优秀 | 优秀 | 良好 | 形变韧性好，质量略高于轻质板材 |
| 铝 | 良好 | 普通 | 良好 | 普通 | 良好 | 需要更大厚度，机动代价较明显 |
| 装甲钢 | 极其优秀 | 极其优秀 | 极其优秀 | 极其优秀 | 优秀 | 最重，材料机动惩罚最大 |
| 陶瓷 | 优秀 | 极其优秀 | 普通 | 极其优秀 | 差 | 首击承压强，但脆性开裂导致长期耐久最低 |

Wok 借用《逃离塔科夫》的主防护材料关系来做 54 件物品归类，但没有照搬其穿透概率、材料破坏系数、维修损耗和覆盖区域算法。具体倍率与耐久是 Wok 面向 80 HP、高 DPS 与六级成长环境的独立平衡。

对于有异材质侧板的模块化载体，提示只显示默认前后主板材质。例如 CPC MOD.1 的主板按超高分子聚乙烯记录，陶瓷侧板不另开第二行；Osprey Protection、IOTV 与 THOR Integrated 也按其默认主板记录。

### 5.2 战斗磨损

统一磨损公式：

    磨损 = max(1, floor(进入插板前的来伤 / 4))

规则：

- 材料不会额外改变单次磨损公式，脆性与长期寿命统一体现在最大耐久；
- 通用物理每次有效命中磨损一次；
- TaCZ 一颗子弹可能触发普通段与穿甲段两个伤害事件，但只按整颗子弹磨损一次；
- TaCZ 磨损使用已经包含爆头倍率的实际整弹伤害，不会平方爆头倍率；
- 致死子弹也会结算掉落护甲的耐久，不因死亡清空装备槽而漏扣；
- 本击先获得防护，再结算本击磨损；达到上限后物品击碎并从胸甲槽移除；
- 若管理员热改配置，把最大耐久降到旧物品当前损耗以下，该物品立即视为耗尽，不再提供防护，提示中的剩余耐久最低为 0。

### 5.3 强酸与直接腐蚀

冠军“强酸”的附加腐蚀会直接损耗耐久，这一部分不套上述战斗磨损公式。若腐蚀后护甲仍可用，该次冠军近战本体仍会作为通用物理伤害，再按来伤产生一次正常战斗磨损，因此两种损耗会叠加。当前事件顺序中，强酸先腐蚀；若它直接把插板腐蚀至耗尽，该击不会再获得插板防护，也不会再由已耗尽插板承担本体磨损。这是当前兼容行为，若以后要改成“本击先挡、随后腐蚀”，必须单独评审冠军模块的事件顺序。

## 六、54 件护甲权威映射

等级和类型参考《逃离塔科夫》的默认防护等级、默认主板与护甲外形，再按 Wok 的轻中重构型归类。塔科夫本体允许替换插板的装备，在 Wok 第一版中被锁定为一个稳定的基准等级和主材质，避免同一物品运行时漂移。

| 等级 | 类型 | 护甲名称 | 材质 | 物品 ID |
|---|---|---|---|---|
| I | 轻型 | Tac-Kek JayPC 插板胸挂（橄榄绿） | 超高分子聚乙烯 | miningdim:plate_armor_jaypc_olive |
| I | 轻型 | Tac-Kek JayPC 插板胸挂（黑色） | 超高分子聚乙烯 | miningdim:plate_armor_jaypc_black |
| II | 轻型 | PACA 防弹背心 | 芳纶 | miningdim:plate_armor_paca |
| III | 轻型 | Eagle Allied Industries MBSS 插板胸挂 | 超高分子聚乙烯 | miningdim:plate_armor_mbss |
| III | 轻型 | WARTECH TV-115 插板胸挂 | 超高分子聚乙烯 | miningdim:plate_armor_tv115 |
| III | 中型 | 6B23-1 防弹衣（数码丛林迷彩） | 装甲钢 | miningdim:plate_armor_6b23_1_digital_flora |
| III | 中型 | 6B5-16 Zh-86 Uley 防弹胸挂 | 复合材料 | miningdim:plate_armor_6b5_16 |
| III | 中型 | BNTI Kirasa-N（胸甲-N）防弹衣（绿色） | 复合材料 | miningdim:plate_armor_kirasa_n_green |
| III | 中型 | MF-UNTAR 防弹背心 | 铝 | miningdim:plate_armor_mf_untar |
| III | 中型 | NPP KlASS Kora-Kulon 防弹衣 | 装甲钢 | miningdim:plate_armor_kora_kulon |
| III | 中型 | NPP KlASS Kora-Kulon 防弹衣（数码迷彩） | 装甲钢 | miningdim:plate_armor_kora_kulon_digital |
| IV | 轻型 | Eagle Industries MMAC 插板胸挂（丛林绿） | 超高分子聚乙烯 | miningdim:plate_armor_mmac_ranger_green |
| IV | 轻型 | ECLiPSE RBAV-AF 插板胸挂（丛林绿） | 钛 | miningdim:plate_armor_rbav_af_ranger_green |
| IV | 轻型 | FirstSpear Strandhogg 插板胸挂（丛林绿） | 铝 | miningdim:plate_armor_strandhogg_ranger_green |
| IV | 轻型 | FirstSpear Strandhogg 插板胸挂（黑系复合迷彩） | 铝 | miningdim:plate_armor_strandhogg_black_multicam |
| IV | 轻型 | HighCom Trooper TFO 防弹背心（复合迷彩） | 超高分子聚乙烯 | miningdim:plate_armor_trooper_tfo_multicam |
| IV | 轻型 | Shellback Tactical Banshee 插板胸挂（A-Tacs AU 迷彩） | 超高分子聚乙烯 | miningdim:plate_armor_banshee_atacs_au |
| IV | 中型 | 6B13 突击甲（丛林迷彩） | 装甲钢 | miningdim:plate_armor_6b13_flora |
| IV | 中型 | 6B3TM-01M 防弹胸挂（卡其色） | 钛 | miningdim:plate_armor_6b3tm_01m_khaki |
| IV | 中型 | ANA Tactical M1 防弹胸挂（橄榄绿） | 装甲钢 | miningdim:plate_armor_ana_m1_olive |
| IV | 中型 | Ars Arma A18 Skanda 插板胸挂（复合迷彩） | 复合材料 | miningdim:plate_armor_a18_skanda_multicam |
| IV | 中型 | Crye Precision AVS 插板胸挂（丛林绿） | 复合材料 | miningdim:plate_armor_avs_ranger_green |
| IV | 中型 | Crye Precision AVS 插板胸挂（复合迷彩） | 复合材料 | miningdim:plate_armor_avs_multicam |
| IV | 中型 | NFM THOR 隐蔽型强化防弹背心 | 复合材料 | miningdim:plate_armor_thor_concealable |
| IV | 中型 | Stich Profi V2 插板胸挂（黑色） | 装甲钢 | miningdim:plate_armor_stich_profi_v2_black |
| IV | 中型 | Wartech TV-110 插板胸挂（灰褐色） | 装甲钢 | miningdim:plate_armor_tv110_coyote |
| IV | 重型 | 6B23-2 防弹衣（山地丛林迷彩） | 装甲钢 | miningdim:plate_armor_6b23_2_mountain_flora |
| IV | 重型 | 6B5-15 Zh-86 Uley 防弹胸挂（丛林迷彩） | 复合材料 | miningdim:plate_armor_6b5_15_flora |
| IV | 重型 | CQC 鱼鹰 MK4A 防弹胸挂（突击型，多地形迷彩） | 铝 | miningdim:plate_armor_osprey_mk4a_assault |
| V | 轻型 | 5.11 Tactical TacTec 插板胸挂（丛林绿） | 超高分子聚乙烯 | miningdim:plate_armor_tactec_ranger_green |
| V | 轻型 | Ars Arma CPC MOD.1 插板胸挂（A-TACS FG 迷彩） | 超高分子聚乙烯 | miningdim:plate_armor_cpc_mod1_atacs_fg |
| V | 轻型 | Ferro Concepts FCPC V5 插板胸挂 | 超高分子聚乙烯 | miningdim:plate_armor_fcpc_v5 |
| V | 轻型 | FORT Gladiator-S（格斗-S）轻型插板胸挂（复合迷彩） | 陶瓷 | miningdim:plate_armor_gladiator_s_light_multicam |
| V | 轻型 | Hexatac HPC 插板背心（黑系复合迷彩） | 超高分子聚乙烯 | miningdim:plate_armor_hexatac_hpc_black_multicam |
| V | 中型 | 6B45 防弹胸挂（通用型） | 陶瓷 | miningdim:plate_armor_6b45_general |
| V | 中型 | 6B45 防弹胸挂（医疗型） | 陶瓷 | miningdim:plate_armor_6b45_medic |
| V | 中型 | BNTI Gzhel-K（彩瓷-K）防弹衣 | 陶瓷 | miningdim:plate_armor_gzhel_k |
| V | 中型 | FORT Gladiator-S（格斗-S）插板胸挂（灰色） | 陶瓷 | miningdim:plate_armor_gladiator_s_gray |
| V | 中型 | FORT Gladiator-S（格斗-S）轻型插板胸挂（维京） | 陶瓷 | miningdim:plate_armor_gladiator_s_viking |
| V | 中型 | Tasmanian Tiger MKIII 插板胸挂（狼棕色） | 复合材料 | miningdim:plate_armor_tt_mkiii_coyote |
| V | 重型 | CQC 鱼鹰 MK4A 防弹胸挂（防护型，多地形迷彩） | 复合材料 | miningdim:plate_armor_osprey_mk4a_protection |
| V | 重型 | FORT Defender-2 防弹衣（格赫娜斑点迷彩） | 陶瓷 | miningdim:plate_armor_defender_2_spot_camo |
| V | 重型 | FORT Defender-2 防弹衣 | 陶瓷 | miningdim:plate_armor_defender_2 |
| V | 重型 | FORT Gladiator-S（格斗-S）插板胸挂（无惧死亡） | 陶瓷 | miningdim:plate_armor_gladiator_s_deathless |
| V | 重型 | FORT Redut-M（堡垒-M）防弹衣 | 陶瓷 | miningdim:plate_armor_redut_m |
| V | 重型 | IOTV Gen4 防弹衣（高机动型，复合迷彩） | 钛 | miningdim:plate_armor_iotv_gen4_high_mobility |
| V | 重型 | IOTV Gen4 防弹衣（全面防护型，复合迷彩） | 钛 | miningdim:plate_armor_iotv_gen4_full_protection |
| V | 重型 | IOTV Gen4 防弹衣（突击型，复合迷彩） | 钛 | miningdim:plate_armor_iotv_gen4_assault |
| V | 重型 | NPP KlASS Korund-VM（刚玉-VM）防弹衣（黑色） | 装甲钢 | miningdim:plate_armor_korund_vm_black |
| VI | 轻型 | 5.11 Hexgrid 插板背心 | 超高分子聚乙烯 | miningdim:plate_armor_hexgrid |
| VI | 中型 | LBT 6094A Slick 插板背心 | 装甲钢 | miningdim:plate_armor_slick |
| VI | 中型 | Stich Profi Stich Defense mod.2 防弹插板胸挂 | 超高分子聚乙烯 | miningdim:plate_armor_stich_defense_mod2 |
| VI | 重型 | 6B43 屏障-Sh 防弹衣（数码丛林迷彩） | 陶瓷 | miningdim:plate_armor_6b43_zabralo_sh |
| VI | 重型 | NFM THOR 一体式防弹护甲 | 复合材料 | miningdim:plate_armor_thor_integrated |

原型资料入口：

- [《逃离塔科夫》官方 Wiki：Ballistics](https://escapefromtarkov.fandom.com/wiki/Ballistics)
- [《逃离塔科夫》官方 Wiki：Armor plates](https://escapefromtarkov.fandom.com/wiki/Armor_plates)
- [《逃离塔科夫》官方 Wiki：Armor vests](https://escapefromtarkov.fandom.com/wiki/Armor_vests)
- [《逃离塔科夫》官方 Wiki：Chest rigs](https://escapefromtarkov.fandom.com/wiki/Chest_rigs)

这些页面只用于原型等级、默认插板和材料归类；Wok 的 R/Q/G/T、耐久、轻中重类型和移速修正均为独立平衡，不复刻塔科夫公式，尤其不以塔科夫的机动惩罚幅度作为上限。

## 七、枪匠与既有纳米系统联动

### 7.1 枪匠

枪匠系统先完成源枪基础伤害、部件乘区、距离与爆头等进攻侧计算，护甲只处理最终进入对应伤害段的数值。插板不会读取组装枪 NBT，也不会重新计算枪械属性，因此：

- 枪匠提高伤害会直接抬高进入护甲前的 H；
- TaCZ 穿甲比例决定伤害落入 R 段还是 Q 段；
- 高肉伤、低穿甲弹主要被 R 克制；
- 高穿甲弹把更多伤害送入较弱的 Q，仍是插板的明确反制手段；
- 枪匠射击模式、射速与散布不被护甲代码改写。

### 7.2 纳米维修与旧特效

- 现有纳米护甲板可以维修新插板，沿用铸甲师的维修经济；
- 新插板被纳米板维修时会清除旧纳米效果，且不会重新掷出纳米护盾、图腾等特效；
- 穿着功能正常的新插板或电浆护盾时，其他护甲槽上的旧“纳米多重护盾”全免窗口被禁用，避免多套防护原理叠加（NanoShieldHandler 同时检查 PlateArmorItem 与 PlasmaShieldItem）；
- 纳米护盾与电浆护盾各自受一个数据包可配的伤害类型标签约束：miningdim:bypasses_nano_shield 与 miningdim:bypasses_plasma_shield，定义于 src/main/resources/data/miningdim/tags/damage_type/，默认都包含 #minecraft:bypasses_invulnerability 与饥饿、溺水、方块内窒息、实体挤压四类。命中标签的伤害不会被对应的免疫窗或护盾吸收；两个 handler 另外硬性放行 #minecraft:bypasses_invulnerability（含 /kill 与虚空伤害），避免管理员的清理流程静默失败。服主可在数据包里增删这两个标签作为平衡开关；
- 其他槽位上既有的纳米生命恢复、重塑或图腾类行为暂未统一移除；它们属于后续跨模块平衡项。

## 八、物品、资源与获取状态

- 54 件护甲都是独立注册物品，全部位于铸甲师创造模式页签；
- 资源权威来源是 F:\CHATGPT\护甲图 顶层的 54 张 WebP 原图；像素重绘_统一版、像素重绘_192版及分类目录都只是历史中间稿，不再作为游戏图标来源；
- 原图与游戏物品保持 54:54 唯一映射；其中 plate_armor_6b23_2_mountain_flora 唯一对应 5c0e57ba86f7747fa141986d-512.webp，其余项目按护甲名称对应；
- 图标生成规则固定为：保留原图 alpha≥128 的实体主体并二值化，只裁去透明外边距；保持宽高比，以最近邻中心采样缩放进 44×44 安全区，再居中放入 48×48 RGBA 画布；
- 生成过程不平滑、不抖动、不限色、不拉伸、不重绘主体，四边至少保留 2 像素透明边距，透明像素 RGBA 全部归零（当前 54 张图标实测都是 48×48、alpha 只有 0 与 255 两个值、主体包围盒不超出 2 至 46）；
- 本文档早期记录的 64×64 画布与 56×56 安全区已不再与仓库资源一致，现按实测的 48×48 重写；重新出图时以 48×48 为准，不要按旧稿交付 64×64。48 不是 2 的幂，非 2 幂贴图会拉低所在图集的 mipmap 质量，若日后要换回 2 的幂尺寸需整批重出并同步本节；
- 当前没有任何 GameTest 断言图标的像素尺寸（PlateArmorGameTests 里没有读 PNG 头的用例），这条规则没有机械兜底，尺寸再被改动也不会被质量门拦住；
- 每件护甲保留独立的扁平物品显示 JSON，用于让 PNG 在物品栏、手持和掉落实体上正常显示；它不是自定义 3D 模型，不能删除；
- 穿在人身上的显示由铸甲师自有资源承担：每件护甲在 miningdim:textures/models/armor/ 下有一张与物品 ID 同名的 plate_armor_*_layer_1.png（54 张，均为 128×128），并配一个继承 HumanoidModel 的自定义几何模型（armor/client/ 下 49 个 *ArmorModel.java，同款不同配色共享几何，经 PlateArmorModelDefinition 映射、PlateArmorClientRegistration 在客户端注册、PlateArmorClient.getHumanoidArmorModel 取用）。PlateArmorEquipmentMaterial 继续复用原版皮革、铁、下界合金，但只为了装备音效与材质名占位，不再提供穿戴贴图层；
- 第一版尚未加入生存配方、掉落、商店或生产台获取，测试阶段使用创造模式页签或 /give；
- 示例：/give @s miningdim:plate_armor_iotv_gen4_assault。

## 九、配置与数据边界

服务端配置使用既有 miningdim-engineer.toml，不创建第二套铸甲师配置，以免旧服迁移时出现两个权威来源。护甲子系统在该文件下占两个顶层段：plateArmor 与 plasmaShield（EngineerConfig 里依次 define）。

plateArmor 下包含：

- ballisticProtectionR：18 个缓冲（R）；
- armorPiercingBufferQ：18 个抗穿（Q）；
- generalProtectionG：18 个防护率（G）；
- pressureCapacityT：18 个抗压（T）；
- power：energyCapacity（单件随身电池容量，默认 2,000,000 FE，范围 0 至 2,000,000,000）与 fePerAbsorbedDamage（每吸收一点伤害扣的电，默认 5,000 FE，范围 0 至 10,000,000，设为 0 即关闭电力需求），见 3.6；
- movement：轻中重机动修正；
- materialProfiles：七种材料各自的最大耐久、三项漏伤倍率、抗压倍率和机动惩罚。

plasmaShield 下包含四个全局键与一个 balanceV4 子段，合计 130 个配置项：

- maxHeat：热量达到该值立即停机，默认 100.0；
- restartHeat：过热后必须冷却到该值才重启，默认 30.0；
- stateTickInterval：散热与回能的结算间隔，默认 5 tick，即每秒四次 HUD 更新；
- heatCoolDelayTicks：未过热护盾被命中后的散热延迟，默认 20 tick；过热护盾立即应急散热；
- balanceV4 下按 18 个变体各开一个子段（nano_i 至 quantum_vi），每个变体七个键：capacity、maxTotalEnergy、heatPerDamage、coolingPerSecond、rechargePerSecond、rechargeDelayTicks、movementModifier。

balanceV4 与 materialProfiles 同理，是刻意与旧平衡路径隔离的新路径，确保旧服留下的 V3 容量值不会静默覆盖新默认值。服主按本节核对配置文件时，plasmaShield 段不是“多出来的未知段”。

四张矩阵固定按 I轻、I中、I重、II轻……VI重排列，必须恰好 18 个有限数。R/Q/G 的每个值范围为大于等于 0 且小于 1，T 大于等于 0；材料漏伤与抗压倍率范围为 0.75 至 1.25，材料机动惩罚范围为 0 至 4%。等级、类型、材料都绑定在注册物品定义上，不接受玩家 NBT 伪造，最终属性按当前服务端配置即时解析，不写入物品 NBT。

旧 materialDurability 段连同 titanium_aramid 与 ceramic_aramid 都不再参与结算。七材料改版故意使用新的 materialProfiles 路径，确保旧服原先仍在合法范围内的耐久不会静默覆盖新平衡值。旧服若改过材料耐久，应由管理员人工决定是否迁移到新路径，尤其不能让三个互相冲突的 combined、titanium_aramid、ceramic_aramid 数值由代码代为猜测。

### 9.1 默认值跨级平衡门

综合分只用于自动测试默认值，不显示给玩家，也不参与实际伤害结算：

    保护分 P = 100 × [0.35R + 0.25Q + 0.20G + 0.20G × min(T / 100, 1)]
    耐久分 U = 100 × min(耐久 / 900, 1)
    机动分 M = 把 -15% 至 +10% 线性映射到 0 至 100
    综合分 S = 0.85P + 0.08U + 0.07M

保护占 85%，耐久占 8%，机动占 7%。测试必须断言“本级最高综合分小于下一级最低综合分”，但不限制单项跨级。该门只约束代码提供的默认值；服主主动修改 TOML 后可以打破默认平衡，代码不会暗中改回管理员配置。

| 等级 | 件数 | 最低综合分 | 最高综合分 | 与前一级安全间隔 |
|---|---:|---:|---:|---:|
| I | 2 | 37.662 | 37.662 | — |
| II | 1 | 42.866 | 42.866 | +5.204 |
| III | 8 | 52.249 | 58.004 | +9.383 |
| IV | 18 | 59.263 | 68.064 | +1.259 |
| V | 20 | 70.841 | 75.711 | +2.777 |
| VI | 5 | 77.059 | 81.128 | +1.348 |

最窄边界是 III 级最高的 6B23-1 装甲钢中甲 58.004 与 IV 级最低的 Strandhogg 铝制轻甲 59.263，仍保留 1.259 分。IV 级装甲钢重甲的抗穿为 34%，确实高于 V 级超高分子聚乙烯轻甲的 19%，但前者的综合分仍低于后者，符合“单项可跨、整体不跨”的规则。

## 十、验证状态

下列清单是插板护甲第一版（2026-07-15）的自动验证结果。之后新增的 FE 电力层、自定义穿戴贴图与模型、48×48 图标重制和整套电浆护盾另见本节末尾的补充条目。

- compileJava 通过；
- runGameTestServer：日志出现 All N required tests passed 即视为通过。N 随全库增长，本文档不固化具体数字；判定以该日志行为准，不看 gradle 退出码。写作本次修订时全库共 1,616 条 GameTest，其中 job/engineer 包内 86 条；
- 54 个枚举、注册物品、扁平物品显示 JSON、图标、中英文名称一一对应；
- 54 件等级、类型和材料映射全表断言；
- 七种材料倍率、耐久、机动惩罚与 54 件映射逐项断言；
- 同等级同类型不同材质会得到不同最终属性，基础抗穿为 0 时材料不能凭空生成抗穿；
- 六级综合分区间与相邻级差逐级断言，允许单项跨级但禁止整体反超；
- 54 件护甲两两比较，不允许低级护甲在缓冲、抗穿、防护率、抗压、耐久、机动六项上同时支配高级护甲；
- R/Q/G/T 数学边界、20 点样本和承压溢出通过；
- 四种 TaCZ 伤害类型精确分类，未知 TaCZ 类型保守排除；
- 同 tick 换装、击碎、配置下调耗尽、属性恢复通过；
- 内部账本模拟覆盖 TaCZ 一弹一次磨损、致死后装备槽清空和 Post/Kill 防双扣；
- TaCZ 1.1.8-hotfix 字节码复核确认爆头 getter 口径与原版死亡掉落的 ItemStack 引用顺序；
- 新插板与旧纳米全免护盾隔离、纳米维修清效果通过。

第一版之后补充的自动验证：

- FE 电力层：PlateArmorGameTests 覆盖“按实际吸收量扣电”“电量耗尽后不再减伤、余额不足按比例回退”“能量 capability 只进不出，抽干后仍可充电”三条（plateArmorBillsPowerPerAbsorbedDamage、drainedPlateArmorStopsAbsorbingAndPartialPowerScalesBack、plateArmorEnergyIsReceiveOnly）；
- 电浆护盾：PlasmaShieldGameTests 共 31 条，覆盖能量吸收、过热与重启阈值、散热与回能延迟、移速修正与状态同步；
- 自定义穿戴贴图与模型、48×48 图标尺寸目前没有任何 GameTest 断言，属于已知的机械兜底缺口，改动资源时不会被质量门拦住。

开发 GameTest 不加载 compileOnly 的 TaCZ。自动测试对 TaCZ 只做字符串伤害分类、纯账本调用、手工清空胸甲槽模拟死亡和二次 settle 模拟 Post/Kill；它没有注册真实 TaCZ 事件，也没有用真实枪械射击玩家。发布前仍需在固定测试客户端手测：

1. 普通弹、穿甲弹与混合穿甲比例；
2. 身体命中、爆头、致死一枪；
3. 全自动连射和同 tick 多发命中；
4. 护甲击碎、死亡掉落、纳米维修；
5. 冠军强酸与高伤害精英攻击；
6. 轻中重移速与卸甲恢复；
7. 客户端提示、48×48 图标、自定义穿戴模型显示与物品显示 JSON 均无缺失；
8. 插板电量的充电、耗尽退化与耗尽后重新充电恢复防护；
9. 电浆护盾的破盾、过热停机、冷却重启与 HUD 同步。

## 十一、电浆护盾与后续护甲种类

### 11.1 电浆护盾（已实装）

电浆护盾是与插板并列的第二套完整护甲种类，同样占胸甲槽、同样随铸甲师子系统注册、同样在铸甲师创造页签里直接可取，但不复用插板的 R/Q/G/T，也不套用插板的七种材料倍率。

身份结构：三个系列（PlasmaShieldSeries：nano、standard、quantum）乘六个等级（PlasmaShieldTier：I 至 VI）共 18 个正式注册物品，ID 形如 miningdim:plasma_shield_nano_i 到 miningdim:plasma_shield_quantum_vi。另有 3 个旧 ID 兼容物品（PlasmaShieldType：plasma_shield_nano、plasma_shield_light、plasma_shield_heavy_ion），分别解析到 NANO_I、STANDARD_I、QUANTUM_I，只为旧存档与旧命令保留，不进创造页签。

结算模型由 PlasmaShieldState 承担纯状态转移，PlasmaShieldHandler 订阅 LivingHurtEvent 接入：

- 一比一吸收，不做百分比减伤：吸收一点伤害就扣一点护盾能量，没有折算系数；
- 双层电量：capacity 是当前活动护盾层的上限，maxTotalEnergy 是含该层在内的电池总量；回能只能把总量里的余量搬进护盾层，搬不出总量本身，所以电池是有限的；
- 热量与过热：每吸收一点伤害升 heatPerDamage 的热，热量撞到 maxHeat 立即停机；停机后必须冷却到 restartHeat 才重启，重启前一点伤害都不吸收；
- 单次命中的吸收量取来伤、剩余护盾、剩余总电与当前热量余量四者的最小值，吸不下的部分原样通过，不静默抹掉；
- 冷却与回能各有独立延迟：被命中后过 heatCoolDelayTicks 才开始散热、过 rechargeDelayTicks 才开始回能；过热状态走应急散热，不等延迟；
- 三个系列是明确的取舍分工：nano 电池最小但发热低、散热与回能最快，适合短促交火；standard 电池居中，每点伤害的发热最高（默认 2.20 到 1.20，约为同级 nano 的四到五倍），扛久了容易过热；quantum 电池最大、延迟最长、散热与回能最慢，还要付负移速修正（movementModifier 从 I 级的 -12% 递减到 VI 级的 -9%；nano 与 standard 两系列为 0）；
- 状态（护盾、总电、热量、过热标记、两个延迟计时）逐件存在 ItemStack NBT 的 MiningDimPlasmaShield 子标签下，带版本号；旧版本缺 totalEnergy 时按迁移规则补齐，配置缩水时钳回合法区间。

客户端与网络：自有频道 PlasmaShieldNetwork，PlasmaShieldSyncS2C 推状态快照，PlasmaShieldHitS2C 推命中反馈；shield/client 下有 HUD 叠层、命中叠层与渲染器、穿戴模型与客户端注册、命中特效与生命周期共 10 个类。命中、过热与应急排气各有音效，由 PlasmaShieldSoundCadence 限流，避免高射速下音效糊成一片。

配置见第九章 plasmaShield 段，自动验证见第十章补充条目，与旧纳米多重护盾的互斥关系见 7.2。

### 11.2 后续护甲种类

以下种类只记录机制方向，尚未实现，也不复用插板的 R/Q/G/T：

| 种类 | 未来类型示例 | 核心机制 | 主要反制 |
|---|---|---|---|
| 纳米陶瓷板护甲 | 待定 | 完全抵挡有限次数伤害；次数耗尽后完全无防护 | 高射速、多段伤害 |
| 弹力护甲 | 待定 | 把部分即时伤害延迟为数秒内的创伤池 | 持续火力、治疗压制 |

未来新增种类时，必须分别定义自己的类型集合、资源、击碎或恢复状态、伤害入口、耐久规则和反制手段。插板的七种材料倍率不会自动套到纳米陶瓷板或弹力护甲；每一种护甲应单独决定材料是否参与及参与哪些属性。

## 十二、后续工作

1. 按核心定位重评 `champion_skill_aoe`：技能伤害只能获得有限插板容错，不能与常规直接伤害同等享受高 G/T；
2. 在固定 TaCZ 客户端完成普通弹、穿甲弹与混合弹实弹手测，确保高穿甲弹始终是高等级插板的有效反制；
3. 增加生存获取、配方、掉落或铸甲师生产链；
4. 决定是否增加身体覆盖区域和爆头覆盖规则；
5. 单独评审强酸的“先腐蚀还是先防护”顺序；
6. 单独评审其他槽位旧纳米生命恢复与图腾是否允许和插板并存；
7. 纳米陶瓷板和弹力护甲各自另开功能分支（电浆护盾已于本子系统内交付，见 11.1，不再属于后续工作）；
8. 给自定义穿戴贴图、穿戴模型与 48×48 图标补机械断言，填上第十章末尾记录的质量门缺口。

本文件是护甲种类、身份字段、R/Q/G/T、材料修正与耐久、54 件插板映射、电浆护盾和枪匠联动的当前权威文档。同一职业的纳米生产、职业等级、维修经济和工作台设计继续参考 docs/MillenniumEngineer_Mod_DesignSpec.md。
