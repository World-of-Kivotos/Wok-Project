# 精确射手平台枪匠组件说明

## 五部件

MARKSMAN 平台显示为“精确射手”（`Marksman Rifle`），复用五类现有枪匠部件：HANDGUARD、CORE、STOCK、BOLT、BARREL。其中 CORE 作为导气部件使用。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

## CMD 与叶模型

MARKSMAN 的平台 index 为 `4`。CMD 公式为：

`platformIndex * 100 + partOrdinal * 10 + qualityIndex + 1`

品质 index 按 `common=0`、`improved=1`、`milspec=2`、`precision=3`、`legendary=4` 计。现有部件 ordinal 为 CORE=`0`、BARREL=`1`、BOLT=`2`、HANDGUARD=`3`、GRIP=`4`、STOCK=`5`。因此本平台使用以下 CMD：

| 部件 | CMD |
| --- | --- |
| CORE（导气） | 401-405 |
| BARREL（枪管） | 411-415 |
| BOLT（枪机） | 421-425 |
| HANDGUARD（护木） | 431-435 |
| STOCK（枪托） | 451-455 |

每个叶模型使用 `minecraft:item/generated`，并将 `layer0` 指向对应的 `miningdim:item/gunsmith_part_marksman_{part}_{quality}` 纹理，即资源路径 `textures/item/gunsmith_part_marksman_{part}_{quality}.png`。

## 属性映射

| 部件 | 属性映射 |
| --- | --- |
| BOLT（枪机） | 伤害，具体为最终伤害倍率 |
| BARREL（枪管） | 爆头，具体为爆头倍率 |
| CORE（导气） | 射程，具体为有效射程 |
| STOCK（枪托） | 后坐力，具体为后坐力控制 |
| HANDGUARD（护木） | 散布，具体为散射控制 |

操控系数固定为 `1.0`，不由 MARKSMAN 这五类部件改变。

## 装配与后续绑定

本平台复用现有部件，因此不涉及槽位迁移。当前不虚构图纸或枪型；待指定实际 TaCZ 枪械后，再绑定该枪械原有的射击模式与侧视图。
