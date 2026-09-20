# 霰弹枪平台组件规格

## 一、平台登记

霰弹枪平台标识为 `SHOTGUN`，数据 ID 为 `shotgun`，本地化显示为“霰弹枪”或 `Shotgun`。平台追加在现有平台之后，固定使用索引 `7`，不得改变 AR、AK、手枪、无托式步枪、精确射手、狙击步枪与机枪的既有索引。

## 二、四类组件

霰弹枪平台按以下顺序使用四类现有枪匠槽位：

1. `STOCK`：枪托。
2. `BARREL`：枪管。
3. `BOLT`：枪机。
4. `HANDGUARD`：护木。

本平台不使用 `CORE`、`GRIP` 或 `RECEIVER`，不新增机匣槽。四类组件沿用品质、材料成本、机械冲压和装配台的通用流程。

## 三、基础属性来源

- 枪机系数影响伤害。
- 枪管系数影响爆头倍率。
- 枪托系数影响后坐控制。
- 护木系数影响散布控制。
- 当前平台未提供导气或握把槽，射程与操控系数固定为 `1.0`。

## 四、资源与模型数据

基础组件使用五档品质：`common`、`improved`、`milspec`、`precision`、`legendary`。基础型号的 CustomModelData 为：

| 组件 | 普通 | 改良 | 军规 | 精密 | 传奇 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 枪管 | 711 | 712 | 713 | 714 | 715 |
| 枪机 | 721 | 722 | 723 | 724 | 725 |
| 护木 | 731 | 732 | 733 | 734 | 735 |
| 枪托 | 751 | 752 | 753 | 754 | 755 |

## 五、图纸绑定

霰弹枪图纸已随基础组件同一版本落地，共四张，全部在 `GunsmithBlueprint` 中定义：

| 图纸 | gunId | 名称键 |
| --- | --- | --- |
| M870 | `tacz:m870` | `tacz.gun.m870.name` |
| M1887 LONG | `ccrp:m1887_long` | `ccrp.gun.m1887_long.name` |
| KSG | `hare:ksg` | `hare.gun.ksg.name` |
| M1014 | `tacz:m1014` | `tacz.gun.m1014.name` |

每张图纸要求第二节的四类组件全集，图纸图标使用 `gunsmith_blueprint_shotgun`，`iconModelData` 为 `6`。成品保留各源枪原有的完整、有序射击模式列表，不得增删或重排。

图纸目录由 GameTest `blueprintCatalogUsesPlatformSpecificPartSets`（断言本平台恰有四张图纸、每张恰需四类组件）与 `firstWaveBlueprintsMatchGunPackIdsAndNameKeys`（逐张断言 gunId 与名称键）覆盖，新增或改动图纸必须同步这两处断言。
