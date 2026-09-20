# AK 平台枪匠组件说明

## 六部件

AK 平台与 AR 同属首发平台，平台标识为 `ak`，本地化显示为 `AK`，平台 index 为 `1`。使用六类枪匠部件：CORE、BARREL、BOLT、HANDGUARD、GRIP、STOCK，顺序即 `GunsmithPressPart` 的枚举顺序。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

AK 与 AR 永久沿用这六槽，不新增 RECEIVER；赤雪-A 枪机复用 BOLT。

## CMD 与叶模型

基础型号的 CMD 沿用公式：

`platformIndex * 100 + partOrdinal * 10 + qualityIndex + 1`

品质 index 按 `common=0`、`improved=1`、`milspec=2`、`precision=3`、`legendary=4` 计，部件 ordinal 为 CORE=`0`、BARREL=`1`、BOLT=`2`、HANDGUARD=`3`、GRIP=`4`、STOCK=`5`。因此本平台使用以下 CMD：

| 部件 | CMD |
| --- | --- |
| CORE（导气） | 101-105 |
| BARREL（枪管） | 111-115 |
| BOLT（枪机） | 121-125 |
| HANDGUARD（护木） | 131-135 |
| GRIP（握把） | 141-145 |
| STOCK（枪托） | 151-155 |

每个叶模型使用 `minecraft:item/generated`，并将 `layer0` 指向对应的 `miningdim:item/gunsmith_part_ak_{part}_{quality}` 纹理，即资源路径 `textures/item/gunsmith_part_ak_{part}_{quality}.png`。

资源侧仍留有 `gunsmith_part_ak_receiver_*` 五张贴图及其 CMD `161-165` 的 override，与 AR 侧的 `61-65` 同属机匣槽早期遗留。AK 平台不支持 RECEIVER，现行代码不可能再产出该 CMD；该区间只作历史保留，不得重新分配给新部件。

## 属性映射

| 部件 | 属性映射 |
| --- | --- |
| BOLT（枪机） | 伤害，具体为最终伤害倍率 |
| BARREL（枪管） | 爆头，具体为爆头倍率 |
| CORE（导气） | 射程，具体为有效射程 |
| STOCK（枪托） | 后坐力，具体为后坐力控制 |
| HANDGUARD（护木） | 散布，具体为散射控制 |
| GRIP（握把） | 操控，具体为开镜时间与瞄准散布 |

六项属性的来源槽位由 `GunsmithStat` 统一决定，本平台六项全部由实际组件提供，没有固定为 `1.0` 的属性。

## 特殊组件

AK 当前已注册两个非基础型号，均出自红冬。稀有度取自 `GunsmithPartVariant` 的固定映射，与[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)第 3.4 节一致：

| 型号 | 稳定 `variant` | 稀有度 | 制造势力 | 槽位 | CMD | 五档数值 |
| --- | --- | --- | --- | --- | --- | --- |
| 红冬高压导气 | `red_east_high_pressure_gas` | 特种级（`SPECIAL`） | 红冬 | CORE | 10101-10105 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第四节 |
| 赤雪-A枪机 | `red_winter_chixue_a_bolt` | 特种级（`SPECIAL`） | 红冬 | BOLT | 10701-10705 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第十一节 |

两者的取舍方向相反又互补：红冬高压导气以射速、有效射程、散布与后坐为代价换逐档递增的伤害；赤雪-A 枪机以穿甲下降和后坐上升为代价换全档恒定的伤害提升。五档倍率由服务端 datapack `data/miningdim/gunsmith/components/<variant>.json` 管理并可热更新；平台、槽位、稀有度、势力与 CMD 属于代码与资源维度，不受 datapack 影响。

AK 平台没有强制射击模式类组件，成品的射击模式列表一律按普通装配路径逐项保留源枪数据。

## 图纸绑定

AK 平台已登记三张装配图纸，每张要求上述六类组件全集，图纸图标使用 `gunsmith_blueprint_ak`，`iconModelData` 为 `2`：

| 图纸 | gunId | 名称键 |
| --- | --- | --- |
| AK47 | `tacz:ak47` | `tacz.gun.ak47.name` |
| RPK | `tacz:rpk` | `tacz.gun.rpk.name` |
| TYPE 81 | `tacz:type_81` | `tacz.gun.type_81.name` |

RPK 在 `GunsmithBlueprint` 中注册在 AK 平台下，要求的仍是 AK 的六件套；它与至今未绑定任何图纸的 MACHINE_GUN 平台无关，不得据此把 RPK 改挂机枪平台。

图纸目录由 GameTest `blueprintCatalogUsesPlatformSpecificPartSets` 覆盖：断言本平台恰有三张图纸、每张恰需六类组件、且 `assembledGunId` 等于图纸自身的 `gunId`。新增或改动 AK 图纸必须同步该断言的计数。
