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

## 特殊组件

SNIPER 平台目前有一个非基础型号，按[枪匠组件命名计划](Gunsmith_Component_Naming_Plan.md)第 3.4、3.5 节的体例登记如下：

| 项 | 值 |
| --- | --- |
| 型号名 | 圣三一精密刻度狙击枪管 |
| 稳定 `variant` | `trinity_precision_graduated_sniper_barrel` |
| 组件型号稀有度 | 尖端级（`ADVANCED`） |
| 制造势力 | 圣三一 |
| 占用槽位 | BARREL（枪管），且只在 SNIPER 平台生效 |
| CMD | `10601-10605`（基数 `10600` + 品质 index + 1） |

该型号的五档特殊效果恒定，不随品质成长：爆头倍率 `×2.00`、弹丸速度 `×1.50`、散布 `×0.67`、开镜速度 `×0.70`，其余字段一律 `×1.00`。五档数值以[枪匠特殊组件热更新规则](Gunsmith_Component_Hot_Reload_Rules.md)第十节为唯一权威来源，可由服务端 datapack 热更新；平台、槽位、稀有度与势力不受 datapack 影响。枪管本身的品质浮动系数仍按基础规则继续影响爆头倍率。

本型号不附带最大耐久代价，这一点与同名的 AR 平台“圣三一精密刻度枪管”不同，后者另有 `×0.70` 的最大耐久惩罚。

## 装配、迁移与图纸绑定

首批图纸绑定 `tacz:kar98`、`lavender:smle_iii` 和 `tacz:m700`；三把枪均已核对为手动枪机，并同步使用 WOK 主服枪械数据表中的基础穿甲、爆头与三段伤害数据。

成枪数据版本升级到 v7。v6 的四槽栓动式步枪成品会自动补入普通品质、基础型号、系数 `1.0` 的兼容撞针，因此旧成品的固定操控与其余组件属性保持不变。装配台旧 `Size=13` 存档会保留原槽位，并把旧输出槽 `12` 迁移到新输出槽；新撞针槽保持为空。装配台当前槽位数与全部历史迁移路径的权威来源是 `GunsmithAssemblyBenchBlockEntity` 的 `SLOT_COUNT` 与各 `LEGACY_*_SLOT_COUNT` 常量，完整迁移表见[无托式步枪枪匠组件说明](Gunsmith_Bullpup_Components.md)“装配台迁移”一节。

五张组件纹理由参考图整体重画为高分辨率透明源图，再统一归一化并输出为 64×64 五档品质图标。该“整体重画后低清”流程只用于枪械组件，不用于图纸图标。生成入口为 `tools/generate_sniper_gunsmith_assets.ps1`，脚本通过固定 SHA-256 校验锁定五张重画源图。
