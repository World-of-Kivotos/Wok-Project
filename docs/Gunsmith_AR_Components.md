# AR 平台枪匠组件说明

## 六部件

AR 平台是枪匠的首发平台，平台标识为 `ar`，本地化显示为 `AR`，平台 index 为 `0`。使用六类枪匠部件：CORE、BARREL、BOLT、HANDGUARD、GRIP、STOCK，顺序即 `GunsmithPressPart` 的枚举顺序。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

AR 与 AK 永久沿用这六槽，不新增 RECEIVER；三连发枪机与 MK-AX-A 枪机都复用 BOLT。

## CMD 与叶模型

基础型号的 CMD 沿用公式：

`platformIndex * 100 + partOrdinal * 10 + qualityIndex + 1`

品质 index 按 `common=0`、`improved=1`、`milspec=2`、`precision=3`、`legendary=4` 计，部件 ordinal 为 CORE=`0`、BARREL=`1`、BOLT=`2`、HANDGUARD=`3`、GRIP=`4`、STOCK=`5`。因此本平台使用以下 CMD：

| 部件 | CMD |
| --- | --- |
| CORE（导气） | 1-5 |
| BARREL（枪管） | 11-15 |
| BOLT（枪机） | 21-25 |
| HANDGUARD（护木） | 31-35 |
| GRIP（握把） | 41-45 |
| STOCK（枪托） | 51-55 |

每个叶模型使用 `minecraft:item/generated`，并将 `layer0` 指向对应的 `miningdim:item/gunsmith_part_ar_{part}_{quality}` 纹理，即资源路径 `textures/item/gunsmith_part_ar_{part}_{quality}.png`。

资源侧仍留有 `gunsmith_part_ar_receiver_*` 五张贴图及其 CMD `61-65` 的 override，是早期误建 AR 机匣槽时期的遗留（该误建由 `GunsmithPartVariant.byId` 的 `mk_ax_a_receiver` 别名迁回枪机槽）。AR 平台不支持 RECEIVER，现行代码不可能再产出该 CMD；该区间只作历史保留，不得重新分配给新部件。

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

AR 是特殊组件最集中的平台，当前已注册四个非基础型号。稀有度取自 `GunsmithPartVariant` 的固定映射，与[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)第 3.4 节一致：

| 型号 | 稳定 `variant` | 稀有度 | 制造势力 | 槽位 | CMD | 五档数值 |
| --- | --- | --- | --- | --- | --- | --- |
| 格赫娜高速导气 | `gehenna_gas` | 特种级（`SPECIAL`） | 格赫娜 | CORE | 10001-10005 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第五节 |
| MK-AX-A枪机 | `mk_ax_a_bolt` | 原型级（`PROTOTYPE`） | 蔚蓝重工 | BOLT | 10201-10205 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第六节 |
| 圣三一精密刻度枪管 | `trinity_precision_graduated_barrel` | 尖端级（`ADVANCED`） | 圣三一 | BARREL | 10301-10305 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第八节 |
| AR三连发枪机 | `ar_three_round_burst_bolt` | 改装级（`MODIFIED`） | 无，不绑定势力 | BOLT | 10401-10405 | [热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第九节 |

特殊组件的五档倍率由服务端 datapack `data/miningdim/gunsmith/components/<variant>.json` 管理并可热更新；平台、槽位、稀有度、势力与 CMD 属于代码与资源维度，不受 datapack 影响。

两条只属于 AR 的额外规则：

- 圣三一精密刻度枪管在特殊倍率之外，还把成品枪的最大耐久乘以 `0.70`；同名的狙击版本没有这项代价。
- AR三连发枪机会把装配输出改指向 `miningdim:{templateId}_gunsmith_burst` 替代枪数据，并强制成品射击模式为纯 `burst`。完整分支规则见[枪匠图纸射击模式策略](Gunsmith_Blueprint_Fire_Mode_Policy.md)“三连发枪机例外”一节。连发节奏取替代枪数据的 `burst_data`，不读射速缓存；与格赫娜高速导气同装时射速加成作废，成品 tooltip 的射速一格标红注明"连发模式下无效"。

## TaCZ 配件标签

TaCZ 按成品枪自己的 gunId 查 `allow_attachments` 标签，标签为空时一律不许装配件。三连发替代枪与旧 M4 模板成品的 gunId 都在 `miningdim` 命名空间，源枪的标签覆盖不到，因此枪匠枪包在 `custom/miningdim_gunsmith/data/miningdim/tacz_tags/attachments/allow_attachments/` 下为每个 id 各备一份，内容照抄 TaCZ 1.1.8 默认枪包里对应源枪的标签：

| 成品 gunId | 照抄的源枪标签 |
| --- | --- |
| `miningdim:m4a1_gunsmith` | `tacz:m4a1` |
| `miningdim:m4a1_gunsmith_burst` | `tacz:m4a1` |
| `miningdim:m16a1_gunsmith_burst` | `tacz:m16a1` |
| `miningdim:m16a4_gunsmith_burst` | `tacz:m16a4` |
| `miningdim:hk416d_gunsmith_burst` | `tacz:hk416d` |

服务器若另用枪包改过源枪的配件标签，须同步改这五份。GameTest `everyGunsmithGunIdCarriesItsSourceGunAttachmentTags` 核对枪包 `index/guns` 里登记的每个 id 都有标签且与源枪一致，新增替代枪 id 时必须同步。

## 图纸绑定

AR 平台已登记四张装配图纸，每张要求上述六类组件全集，图纸图标使用 `gunsmith_blueprint_ar`，`iconModelData` 为 `1`：

| 图纸 | gunId | 名称键 | 三连发替代枪数据 |
| --- | --- | --- | --- |
| M4A1 | `tacz:m4a1` | `tacz.gun.m4a1.name` | `miningdim:m4a1_gunsmith_burst` |
| M16A1 | `tacz:m16a1` | `tacz.gun.m16a1.name` | `miningdim:m16a1_gunsmith_burst` |
| M16A4 | `tacz:m16a4` | `tacz.gun.m16a4.name` | `miningdim:m16a4_gunsmith_burst` |
| HK416D | `tacz:hk416d` | `tacz.gun.hk416d.name` | `miningdim:hk416d_gunsmith_burst` |

未装三连发枪机时，成品保留各源枪原有的完整、有序射击模式列表；已核对 HK416D 与 M16A1 为 `auto, semi`，M16A4 为 `burst, semi`。

早期发布的 M4 装配模板是独立物品，其成品固定指向 `miningdim:m4a1_gunsmith`，与 M4A1 图纸共用平台与部件要求，作为存量兼容路径保留。

图纸目录由 GameTest `blueprintCatalogUsesPlatformSpecificPartSets` 覆盖：断言本平台恰有四张图纸、每张恰需六类组件、且 `assembledGunId` 等于图纸自身的 `gunId`。新增或改动 AR 图纸必须同步该断言的计数。

## 基础数据

四张 AR 图纸在 WOK 主服枪械数据表中都有行，基础穿甲、爆头倍率与分段伤害取表格生效值（`GunsmithWeaponBaseProfile`）；装了三连发枪机的成品按所属图纸取同一行。M4A1（35 格内 8、60 格内 7、之后 6）与 HK416D（25 格内 8，其余同 M4A1）2026-09 未调整，M4A1 是精英星表的血量标定锚点。

M16A1 与 M16A4 原先在表中没有行，走枪包数据；2026-09 补行，取值写在枪行覆盖列。穿甲取 5.56 弹药默认值 0.30，射程照抄枪包：

| 图纸 | 首段 | 第二段 | 之后 | 穿甲 | 爆头 | 补行前（枪包） |
| --- | --- | --- | --- | --- | --- | --- |
| M16A1 | 45 格内 9.5 | 70 格内 8 | 5.5 | 0.30 | 1.50 | 8.5 / 7 / 5，穿甲 0.25，爆头 1.50 |
| M16A4 | 45 格内 10.5 | 70 格内 8.5 | 8 | 0.30 | 1.50 | 8 / 6.5 / 6，穿甲 0.25，爆头 1.35 |

两行在数据表中暂放 DU 页，归属学园待定，放在哪页不影响代码。
