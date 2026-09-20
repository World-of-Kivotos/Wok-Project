# 无托式步枪枪匠组件说明

## 五件套

BULLPUP 平台显示为“无托式步枪”（`Bullpup Rifle`），使用五件套：CORE、BARREL、HANDGUARD、GRIP、RECEIVER。CORE 复用现有基础导气组件，不新增独立的 BULLPUP 部件名称。RECEIVER 在结构上等于枪托与枪机的组合，但它是一个单独的装配槽位。

## 品质与 CMD

五档品质按 `common`、`improved`、`milspec`、`precision`、`legendary` 排列：

| 部件 | CMD |
| --- | --- |
| CORE | 301-305 |
| BARREL | 311-315 |
| HANDGUARD | 331-335 |
| GRIP | 341-345 |
| RECEIVER | 391-395 |

每个叶子模型使用 `minecraft:item/generated`，并将 `layer0` 指向同名的 `miningdim:item/gunsmith_part_bullpup_{part}_{quality}` 纹理。

## 属性映射

| 部件 | 属性作用 |
| --- | --- |
| CORE | 影响有效射程，显示为基础导气 |
| BARREL | 影响爆头倍率 |
| HANDGUARD | 影响散射控制 |
| GRIP | 影响操控与开镜稳定 |
| RECEIVER | 影响最终伤害；结构上包含枪托与枪机职责，但不提供后坐力控制 |

BULLPUP 的后坐力系数固定为 `1.0`，不受任何组件加成。RECEIVER 不减少后坐力，其他 BULLPUP 组件也不改变该系数。

## 机械冲压机与成本

五类组件沿用机械冲压机的现有平台、品质和 CMD 输出流程。RECEIVER 的材料成本为 `6/6/5`，分别按该组件定义的三类成本材料计。BULLPUP 当前不新增图纸；等待用户指定 AUG、QBZ-95 等具体枪型后再增加对应图纸，严禁回退或 fallback 到其他枪型。

## 装配台迁移

装配台槽位数不是固定常量，而是随 `GunsmithPressPart` 枚举增长，权威来源是 `GunsmithAssemblyBenchBlockEntity` 中的 `SLOT_COUNT` 与各 `LEGACY_*_SLOT_COUNT` 常量。当前为 `Size=14`：图纸槽 `0`、十二个部件槽 `1-12`、输出槽 `13`。

引入 BULLPUP 平台和 RECEIVER 枚举时，当时的固定值是 `Size=12`；该值现已降为历史迁移路径之一。装配台当前接受并自动迁移以下五种存档尺寸：

| 存档 Size | 对应历史阶段 | 迁移方式 |
| --- | --- | --- |
| 14 | 当前布局 | 直接读取，不迁移 |
| 13 | 撞针槽加入前 | 保留原槽位，旧输出槽 `12` 移到新输出槽，新撞针槽留空 |
| 12 | 脚架槽加入前（本平台引入 RECEIVER 时的固定值） | 保留原槽位，旧输出槽 `11` 移到新输出槽，新增槽留空 |
| 11 | RECEIVER 槽加入前 | 保留图纸与原有部件槽 `0-9`，旧输出槽 `10` 移到新输出槽，新 RECEIVER 槽留空 |
| 8 | 仅步枪六槽时期 | 保留旧步枪图纸与部件槽 `0-6`，旧输出槽 `7` 移到新输出槽 |

只有不在上述五个值之内的尺寸才视为损坏或未知格式并直接报错，不使用静默默认值。后续再新增部件枚举时，只在上述常量处追加一条迁移路径，各平台文档不再各自重复登记具体数字。
