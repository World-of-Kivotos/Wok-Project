# 铸甲师护甲系统设计与实装规格

> 状态：第一版已实装，数值仍可调。
> 更新日期：2026-07-14。
> 适用平台：Minecraft 1.20.1、Forge 47.x、Java 17、TaCZ 1.1.8。
> 界面与创造页签显示名称为“铸甲师”；/job info 等直接显示稳定职业 ID 的命令、JobId.ENGINEER、engineer 注册名和配置文件名暂时保留，以兼容旧存档与既有模块。

## 一、当前结论

本期只实现“插板护甲”种类，已经完成 54 件独立胸甲物品、54 张物品图标、I 至 VI 六个等级、轻中重三种类型、九种材料耐久、TaCZ 弹道结算、普通物理伤害结算和服务端配置。

四个身份字段必须始终按以下顺序显示：

    等级：V
    种类：插板护甲
    类型：重型
    材质：钛

字段含义固定如下：

| 字段 | 当前含义 | 是否决定防护数值 |
|---|---|---|
| 等级 | I、II、III、IV、V、VI，全护甲种类共用的强度级别 | 是 |
| 种类 | 护甲采用的防护机制；本期为插板护甲，未来可为电浆护盾等 | 是，不同种类使用不同公式 |
| 类型 | 某一种类内部的构型；插板护甲为轻型、中型、重型 | 是 |
| 材质 | 主防护结构材料；模块化载体取默认前后主板，固定式护甲取内置材料 | 否，只决定最大耐久 |

“类型”不是跨所有护甲种类共用的死枚举。未来电浆护盾可以定义轻型护盾、重型护盾，且不必沿用插板护甲的轻中重规则。

材料是耐久维度。同等级、同类型的两件插板护甲即使材质不同，R、Q、G、T 和机动修正也完全相同，只是能承受的长期磨损不同。玩家给出的“V 级、插板护甲、重型、超高分子聚乙烯”是合法的排版示例，但当前 54 件原型中没有这一具体组合；当前映射不为凑示例而改动。

“插板护甲”是 Wok 的玩法种类名，不代表 PACA、UNTAR 等所有原型在《逃离塔科夫》或现实中都具有可拆卸插板。

## 二、插板护甲的等级与类型

### 2.1 类型定位

| 类型 | 防护定位 | 移速修正 | 玩法 |
|---|---|---:|---|
| 轻型 | 同级最低 | +3% | 游击、侦察、快速换位 |
| 中型 | 同级居中 | 0% | 通用基准 |
| 重型 | 同级最高 | -4% | 阵地、突破、正面交火 |

机动修正只由类型决定，与等级和材质无关。第一版只修改基础移动速度；冲刺会继承同一移动属性倍率，但不另加冲刺、跳跃、换弹或瞄准专属修正。

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

| 属性 | 名称 | 作用对象 |
|---|---|---|
| R | 弹道肉伤防护率 | TaCZ 普通弹道段 |
| Q | 穿甲缓冲率 | TaCZ 穿甲段 |
| G | 通用物理防护率 | 白名单内的非 TaCZ 物理伤害 |
| T | 单次承压值 | 一次通用物理伤害中最多有多少伤害可获得 G 防护 |

Q 是第一版原型数值，目的在于拉开 IV 至 VI 的成长，同时保留穿甲弹反制。后续实弹测试可以调数值，调参规范应保持 Q 小于同格 R；当前配置只分别强制 R/Q 位于 [0,1)，尚未做 Q<R 的运行时交叉校验。

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

### 3.4 与原版护甲的关系

功能正常的插板胸甲会把玩家的原版护甲值和护甲韧性整体归零，再由 R/Q/G/T 独立结算。这样不会出现“插板减一次、钻石甲或其他模组护甲再减一次”的叠甲。

这一规则也适用于被插板排除的伤害：排除表示插板不减伤，同时不会退回原版护甲值或韧性。它仍可能受到抗性、职业减伤、保护附魔等其他独立层影响。该行为是第一版的明确规则，不是遗漏。

插板耗尽、卸下或更换后，原版护甲值、韧性和机动属性会恢复。正常事件链会在同一次伤害流程或装备变化时清理；若第三方模组在后续阶段取消伤害，异常流程最迟由当 tick 末的同步兜底清理，避免同 tick 换装或击碎后长期留下错误属性。

## 四、默认数值

所有数值都位于服务端 miningdim-engineer.toml 的 plateArmor 配置段，可在后续测试中微调。

### 4.1 R：弹道肉伤防护率

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 45% | 50% | 55% |
| II | 60% | 65% | 70% |
| III | 75% | 80% | 85% |
| IV | 85% | 88% | 90% |
| V | 90% | 92% | 94% |
| VI | 94% | 96% | 98% |

### 4.2 Q：穿甲缓冲率

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 0% | 0% | 0% |
| II | 2% | 5% | 8% |
| III | 8% | 10% | 15% |
| IV | 15% | 20% | 25% |
| V | 25% | 35% | 45% |
| VI | 45% | 50% | 55% |

### 4.3 G：通用物理防护率

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 35% | 40% | 45% |
| II | 45% | 50% | 55% |
| III | 60% | 68% | 70% |
| IV | 70% | 76% | 78% |
| V | 78% | 84% | 86% |
| VI | 86% | 88% | 90% |

### 4.4 T：单次承压值

| 等级 | 轻型 | 中型 | 重型 |
|---|---:|---:|---:|
| I | 16 | 20 | 24 |
| II | 24 | 32 | 38 |
| III | 38 | 48 | 58 |
| IV | 58 | 72 | 84 |
| V | 84 | 96 | 112 |
| VI | 112 | 128 | 154 |

### 4.5 受到 20 点伤害的中型护甲样本

下表只展示插板层输出，不含抗性、附魔、职业被动和吸收生命。“纯普通段”是 p=0 的肉伤端点，“纯穿甲段”是 p=1 的穿甲端点，并不是同一颗混合弹同时造成两列伤害；混合列展示 p=20% 时，16 点普通段加 4 点穿甲段的整弹输出。

| 等级 | TaCZ 纯普通段 | TaCZ 纯穿甲段 | TaCZ 混合段 p=20% | 通用物理 |
|---|---:|---:|---:|---:|
| I 中型 | 10 | 20 | 12 | 12 |
| II 中型 | 7 | 19 | 9.4 | 10 |
| III 中型 | 4 | 18 | 6.8 | 6.4 |
| IV 中型 | 2.4 | 16 | 5.12 | 4.8 |
| V 中型 | 1.6 | 13 | 3.88 | 3.2 |
| VI 中型 | 0.8 | 10 | 2.64 | 2.4 |

V 级重型受到 20 点伤害时：

- TaCZ 普通段：20 × (1 - 94%) = 1.2；
- TaCZ 穿甲段：20 × (1 - 45%) = 11；
- 通用物理：20 - min(20,112) × 86% = 2.8。

原版无附魔钻石套为 20 护甲、8 韧性。按原版公式承受一次 20 点护甲敏感伤害时约剩 8 点；当前 III 级中型插板对同值通用物理剩 6.4 点，对 TaCZ 普通段剩 4 点，对穿甲段剩 18 点。因此“钻石甲顶多 III 级中甲”成立，同时穿甲弹仍能明显克制插板。

## 五、材质与耐久

### 5.1 材质只决定最大耐久

| 材质 | 默认最大耐久 | 件数 |
|---|---:|---:|
| 芳纶 | 860 | 1 |
| 超高分子聚乙烯 | 640 | 13 |
| 钛/芳纶 | 630 | 1 |
| 复合材料 | 610 | 8 |
| 钛 | 580 | 5 |
| 铝 | 550 | 4 |
| 陶瓷/芳纶 | 540 | 1 |
| 装甲钢 | 510 | 10 |
| 陶瓷 | 480 | 11 |
| 合计 | — | 54 |

Wok 借用《逃离塔科夫》的主防护材料关系来区分耐久，但没有照搬其穿透概率、材料破坏系数、维修损耗和覆盖区域算法。这里的数值是 Wok 面向 80 HP 与高 DPS 环境的第一版耐久标尺。

对于有异材质侧板的模块化载体，提示只显示默认前后主板材质。例如 CPC MOD.1 的主板按超高分子聚乙烯记录，陶瓷侧板不另开第二行；Osprey Protection、IOTV 与 THOR Integrated 也按其默认主板记录。

### 5.2 战斗磨损

统一磨损公式：

    磨损 = max(1, floor(进入插板前的来伤 / 4))

规则：

- 材料不再额外乘脆弱度，耐久差异只来自最大耐久；
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
| III | 中型 | 6B5-16 Zh-86 Uley 防弹胸挂 | 钛/芳纶 | miningdim:plate_armor_6b5_16 |
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
| IV | 重型 | 6B5-15 Zh-86 Uley 防弹胸挂（丛林迷彩） | 陶瓷/芳纶 | miningdim:plate_armor_6b5_15_flora |
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

这些页面用于原型等级、默认插板和材料归类；Wok 的 R/Q/G/T、耐久、轻中重类型和移速修正是独立平衡，不声称复刻塔科夫公式。

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
- 穿着功能正常的新插板时，其他护甲槽上的旧“纳米多重护盾”全免窗口被禁用，避免两套防护原理叠加；
- 其他槽位上既有的纳米生命恢复、重塑或图腾类行为暂未统一移除；它们属于后续跨模块平衡项。

## 八、物品、资源与获取状态

- 54 件护甲都是独立注册物品，全部位于铸甲师创造模式页签；
- 每件护甲都有由 F:\CHATGPT\护甲图\像素重绘_192版 原图确定性降采样得到的独立 64×64 RGBA 透明像素图标；
- 像素化规则固定为 输出(x,y)=原图(3x+1,3y+1)，即每个 3×3 区块取中心像素；不平滑、不抖动、不限色、不裁切，保持原始轮廓和二值透明；
- 每件护甲保留独立的扁平物品显示 JSON，用于让 PNG 在物品栏、手持和掉落实体上正常显示；它不是自定义 3D 模型，不能删除；
- 穿在人身上的显示按类型明确复用原版皮革、铁、下界合金人形胸甲层；不制作自定义穿戴贴图或 3D 模型；
- 第一版尚未加入生存配方、掉落、商店或生产台获取，测试阶段使用创造模式页签或 /give；
- 示例：/give @s miningdim:plate_armor_iotv_gen4_assault。

## 九、配置与数据边界

服务端配置使用既有 miningdim-engineer.toml，不创建第二套铸甲师配置，以免旧服迁移时出现两个权威来源。plateArmor 下包含：

- ballisticProtectionR：18 个 R；
- armorPiercingBufferQ：18 个 Q；
- generalProtectionG：18 个 G；
- pressureCapacityT：18 个 T；
- movement：轻中重机动修正；
- materialDurability：九种材料最大耐久。

四张矩阵固定按 I轻、I中、I重、II轻……VI重排列，必须恰好 18 个有限数。R/Q/G 的每个值范围为大于等于 0 且小于 1，T 大于等于 0。等级、类型、材料都绑定在注册物品定义上，不接受玩家 NBT 伪造。

## 十、验证状态

第一版自动验证结果：

- compileJava 通过；
- runGameTestServer：706/706 必需测试通过；
- 54 个枚举、注册物品、扁平物品显示 JSON、图标、中英文名称一一对应；
- 54 件等级、类型和材料映射全表断言；
- 同等级同类型不同材质时，R/Q/G/T 与机动相同、最大耐久不同；
- R/Q/G/T 数学边界、20 点样本和承压溢出通过；
- 四种 TaCZ 伤害类型精确分类，未知 TaCZ 类型保守排除；
- 同 tick 换装、击碎、配置下调耗尽、属性恢复通过；
- 内部账本模拟覆盖 TaCZ 一弹一次磨损、致死后装备槽清空和 Post/Kill 防双扣；
- TaCZ 1.1.8-hotfix 字节码复核确认爆头 getter 口径与原版死亡掉落的 ItemStack 引用顺序；
- 新插板与旧纳米全免护盾隔离、纳米维修清效果通过。

开发 GameTest 不加载 compileOnly 的 TaCZ。自动测试对 TaCZ 只做字符串伤害分类、纯账本调用、手工清空胸甲槽模拟死亡和二次 settle 模拟 Post/Kill；它没有注册真实 TaCZ 事件，也没有用真实枪械射击玩家。发布前仍需在固定测试客户端手测：

1. 普通弹、穿甲弹与混合穿甲比例；
2. 身体命中、爆头、致死一枪；
3. 全自动连射和同 tick 多发命中；
4. 护甲击碎、死亡掉落、纳米维修；
5. 冠军强酸与高伤害精英攻击；
6. 轻中重移速与卸甲恢复；
7. 客户端提示、64×64 图标、原版穿戴显示与物品显示 JSON 均无缺失。

## 十一、后续护甲种类

这些种类只记录机制方向，本期不实现，也不复用插板的 R/Q/G/T：

| 种类 | 未来类型示例 | 核心机制 | 主要反制 |
|---|---|---|---|
| 电浆护盾 | 轻型护盾、重型护盾 | 充能能量池；护盾存在时由能量池承受来伤，不做百分比减伤；破盾后延迟充能 | 高单发肉伤、爆发与持续压制 |
| 纳米陶瓷板护甲 | 待定 | 完全抵挡有限次数伤害；次数耗尽后完全无防护 | 高射速、多段伤害 |
| 弹力护甲 | 待定 | 把部分即时伤害延迟为数秒内的创伤池 | 持续火力、治疗压制 |

未来新增种类时，必须分别定义自己的类型集合、资源、击碎或恢复状态、伤害入口、耐久规则和反制手段。材料默认仍只承担耐久差异；若要让材料影响其他属性，必须另行拍板并修改本条权威规则。

## 十二、后续工作

1. 在固定 TaCZ 客户端完成实弹手测并按结果微调 Q、G、T；
2. 增加生存获取、配方、掉落或铸甲师生产链；
3. 决定是否增加身体覆盖区域和爆头覆盖规则；
4. 单独评审强酸的“先腐蚀还是先防护”顺序；
5. 单独评审其他槽位旧纳米生命恢复与图腾是否允许和插板并存；
6. 电浆护盾、纳米陶瓷板和弹力护甲各自另开功能分支。

本文件是护甲种类、身份字段、R/Q/G/T、材质耐久、54 件映射和枪匠联动的当前权威文档。既有纳米生产、职业等级、维修经济和工作台设计继续参考 MillenniumEngineer_Mod_DesignSpec.md。
