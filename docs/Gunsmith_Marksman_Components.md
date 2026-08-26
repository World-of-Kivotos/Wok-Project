# 精准射手步枪平台枪匠组件说明

## 六部件

MARKSMAN 平台显示为“精准射手步枪”（`Designated Marksman Rifle`），使用六类枪匠部件：HANDGUARD、CORE、STOCK、BOLT、BARREL、GRIP。其中 CORE 作为导气部件使用，BOLT 按用户口径显示为枪机，不新增 RECEIVER 槽。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

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
| GRIP（握把） | 441-445 |
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
| GRIP（握把） | 操控，具体为瞄准操控系数 |

## 装配与图纸绑定

首把图纸绑定 TaCZ 默认枪包的 `tacz:spr15hb`，保留原枪射击模式。旧版按 AR 平台装配的 SPR15HB 成品会兼容读取为本平台，并完整保留原握把；短暂使用五槽结构的 v5 精准射手成品会自动补入普通品质、系数 `1.0` 的兼容握把，保持旧操控值并继续可用。

六张组件纹理由参考图整体重画为高分辨率透明源图，再统一归一化并输出为 64×64 五档品质图标。该“整体重画后低清”流程只用于枪械组件，不用于图纸图标。
