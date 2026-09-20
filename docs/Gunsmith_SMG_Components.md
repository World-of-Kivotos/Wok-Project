# 冲锋枪枪匠基础组件

冲锋枪作为独立枪匠平台追加在现有平台之后，固定平台索引为 `8`。基础组件、五档品质资源与该平台的装配图纸均已落地，本文是 SMG 平台图纸与组件的唯一规格来源。

## 组件顺序与属性来源

枪匠冲压界面按以下顺序显示：

1. 枪管 `BARREL`：爆头倍率
2. 枪托 `STOCK`：后坐控制
3. 机匣 `RECEIVER`：伤害倍率
4. 护木 `HANDGUARD`：散布控制
5. 握把 `GRIP`：操控倍率

冲锋枪不使用 `CORE`，因此射程倍率固定为 `1.0`。这里的 `RECEIVER` 属于独立 SMG 平台，不改变 AR/AK 永久六槽结构。

## CustomModelData

| 组件 | 五档编号 |
| --- | --- |
| 枪管 | `811`–`815` |
| 护木 | `831`–`835` |
| 握把 | `841`–`845` |
| 枪托 | `851`–`855` |
| 机匣 | `891`–`895` |

资源名称统一使用 `gunsmith_part_smg_<part>_<quality>`。五档品质依次为 `common`、`improved`、`milspec`、`precision`、`legendary`。

## 装配图纸

SMG 平台已登记五张装配图纸，全部在 `GunsmithBlueprint` 中定义：

| 图纸 | gunId | 名称键 |
| --- | --- | --- |
| UZI | `tacz:uzi` | `tacz.gun.uzi.name` |
| UMP45 | `tacz:ump45` | `tacz.gun.ump45.name` |
| HK MP5A5 | `tacz:hk_mp5a5` | `tacz.gun.hk_mp5a5.name` |
| STERLING | `wyyc1991:stl` | `wyyc.stl.name` |
| MPX | `ccrp:mpx` | `ccrp.gun.mpx.name` |

每张图纸要求上表五类组件全集，图纸图标使用 `gunsmith_blueprint_smg`，`iconModelData` 为 `5`。成品沿用各自来源枪包原有的射击模式列表与顺序，不做增删或重排。

上述图纸目录由 GameTest `blueprintCatalogUsesPlatformSpecificPartSets`（断言本平台恰有五张图纸）与 `firstWaveBlueprintsMatchGunPackIdsAndNameKeys`（逐张断言 gunId 与名称键）覆盖，新增或改动图纸必须同步这两处断言。
