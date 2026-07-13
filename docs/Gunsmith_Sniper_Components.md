# 狙击步枪平台枪匠组件说明

## 四类复用部件

SNIPER 平台显示为“狙击步枪”（`Sniper Rifle`），复用四类现有枪匠部件。装配台 UI 顺序为：RECEIVER（机匣）、STOCK（枪托）、BARREL（枪管）、HANDGUARD（护木）。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

## CMD 与叶模型

SNIPER 的平台 index 为 `5`。CMD 公式为：

`platformIndex * 100 + partOrdinal * 10 + qualityIndex + 1`

品质 index 按 `common=0`、`improved=1`、`milspec=2`、`precision=3`、`legendary=4` 计。现有部件 ordinal 为 BARREL=`1`、HANDGUARD=`3`、STOCK=`5`、RECEIVER=`9`，因此本平台使用以下 CMD：

| 部件 | CMD |
| --- | --- |
| BARREL（枪管） | 511-515 |
| HANDGUARD（护木） | 531-535 |
| STOCK（枪托） | 551-555 |
| RECEIVER（机匣） | 591-595 |

每个叶模型使用 `minecraft:item/generated`，并将 `layer0` 指向对应的 `miningdim:item/gunsmith_part_sniper_{part}_{quality}` 纹理，即资源路径 `textures/item/gunsmith_part_sniper_{part}_{quality}.png`。

## 属性映射

| 部件 | 属性映射 |
| --- | --- |
| RECEIVER（机匣） | 伤害，具体为最终伤害倍率 |
| STOCK（枪托） | 后坐力，具体为后坐力控制 |
| BARREL（枪管） | 爆头，具体为爆头倍率 |
| HANDGUARD（护木） | 散布，具体为散射控制 |

射程固定为 `1.0`，操控固定为 `1.0`，不由这四类部件改变。

## 装配与后续绑定

本平台复用现有部件，因此不涉及槽位迁移。当前不虚构图纸或枪型；待指定实际 TaCZ 枪械后，再绑定该枪械原有的射击模式与侧视图。
