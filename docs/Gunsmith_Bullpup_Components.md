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

新增 BULLPUP 平台和 RECEIVER 枚举后，装配台固定为 `Size=12`。本次实现同步提供两条明确迁移路径：`Size=11` 保留图纸与原有部件槽 `0-9`，将旧输出槽 `10` 移到新输出槽 `11`，新的 RECEIVER 槽 `10` 保持为空；`Size=8` 保留旧步枪图纸与六个部件槽 `0-6`，将旧输出槽 `7` 移到新输出槽 `11`。其他尺寸视为损坏或未知格式并直接报错，不使用静默默认值。
