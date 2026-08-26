# 栓动式步枪平台枪匠组件说明

## 五类部件

SNIPER 平台显示为“栓动式步枪”（`Bolt-Action Rifle`），使用五类枪匠部件。装配台 UI 顺序为：RECEIVER（机匣）、STOCK（枪托）、BARREL（枪管）、HANDGUARD（护木）、FIRING_PIN（撞针）。品质沿用 `common`、`improved`、`milspec`、`precision`、`legendary` 五档。

## CMD 与叶模型

SNIPER 的平台 index 为 `5`。已发布部件继续使用公式：

`platformIndex * 100 + partOrdinal * 10 + qualityIndex + 1`

品质 index 按 `common=0`、`improved=1`、`milspec=2`、`precision=3`、`legendary=4` 计。BARREL、HANDGUARD、STOCK、RECEIVER 继续使用原编号。FIRING_PIN 在已发布枚举末尾新增，若直接套公式会与 MACHINE_GUN/BARREL 的 `611-615` 冲突，因此单独使用保留区间 `10501-10505`。

| 部件 | CMD |
| --- | --- |
| BARREL（枪管） | 511-515 |
| HANDGUARD（护木） | 531-535 |
| STOCK（枪托） | 551-555 |
| RECEIVER（机匣） | 591-595 |
| FIRING_PIN（撞针） | 10501-10505 |

每个叶模型使用 `minecraft:item/generated`，并将 `layer0` 指向对应的 `miningdim:item/gunsmith_part_sniper_{part}_{quality}` 纹理，即资源路径 `textures/item/gunsmith_part_sniper_{part}_{quality}.png`。

## 属性映射

| 部件 | 属性映射 |
| --- | --- |
| RECEIVER（机匣） | 伤害，具体为最终伤害倍率 |
| STOCK（枪托） | 后坐力，具体为后坐力控制 |
| BARREL（枪管） | 爆头，具体为爆头倍率 |
| HANDGUARD（护木） | 散布，具体为散射控制 |
| FIRING_PIN（撞针） | 操控，具体为操控与击发稳定 |

射程固定为 `1.0`；操控由撞针系数提供。

## 装配、迁移与图纸绑定

首批图纸绑定 `tacz:kar98`、`lavender:smle_iii` 和 `tacz:m700`；三把枪均已核对为手动枪机，并同步使用 WOK 主服枪械数据表中的基础穿甲、爆头与三段伤害数据。

成枪数据版本升级到 v7。v6 的四槽栓动式步枪成品会自动补入普通品质、基础型号、系数 `1.0` 的兼容撞针，因此旧成品的固定操控与其余组件属性保持不变。装配台旧 `Size=13` 存档会保留原槽位，并把旧输出槽 `12` 迁移到新输出槽；新撞针槽保持为空。

五张组件纹理由参考图整体重画为高分辨率透明源图，再统一归一化并输出为 64×64 五档品质图标。该“整体重画后低清”流程只用于枪械组件，不用于图纸图标。生成入口为 `tools/generate_sniper_gunsmith_assets.ps1`，脚本通过固定 SHA-256 校验锁定五张重画源图。
