# 电力线材系统 Mod — 美术资源需求清单

## 一、文档说明

- 用途: 线材(导体/线缆)子系统的美术需求与资产状态清单。列出分期成品所需的方块贴图、物品图标和共享基底, 供美术与实现逐项对账。所有机制/分级依据以 `docs/Power_Cable_DesignSpec.md` 为唯一真源, 本文档只做美术资产映射与状态记录, 不改机制。
- 关联真源: 导体阶梯与材料 id 见设计文档第四章 12 级导体表及 `ConductorMaterial` 枚举; 绝缘 5 档见 `InsulationGrade` 枚举; 分期路线见设计文档第十三章。
- 贴图规格通用约定:
  - 方块贴图: 16x16 PNG, 放置于 `src/main/resources/assets/miningdim/textures/block/`。
  - 物品图标: 16x16 PNG(扁平 2D sprite), 放置于 `src/main/resources/assets/miningdim/textures/item/`。
  - 命名: 一律小写下划线, 与注册 id / blockstate / model / lang 键完全一致(如 `iron_energy_cable`), 大小写或拼写不符将导致材质丢失(紫黑格)。
  - 多面机器方块: 按 `top` / `side` / `front` 拆分独立贴图文件, 工作态另出 `front_on`。
- 线缆模型方案(DECIDED): 采用细管本体加六向连接口的多部件模型, 按相邻线缆/端点状态组合连接方向。整方块模型只保留为迁移前的旧占位, 不再作为待确认方案。
- 线缆贴图方案(DECIDED): 细管本体、连接口和物品图标使用灰度基底, 导体/绝缘色由材料数据 tint 注入; 不为 12 级导体重复绘制同形状的整套彩色 PNG。
- 旧占位事实: 当前 `models/block/iron_energy_cable.json` 与 `copper_energy_cable.json` 仍是 `cube_all` 路径, 仅铁、铜方块已在 `PowerRegistry` 注册。仓库中已有的 58 个旧式 PNG 只能记为“文件已生成但待新模型/数据接线复核”, 不能据此宣称新模型或新材料已完成。
- 状态/严重度记法: 纯文本 Critical / Major / Minor 与 [x] / [ ]; 优先级用 P1 / P2 / P3; 不使用任何 emoji 或颜色符号。
- 资产状态记法: `[x] 已生成` 表示目标 PNG 文件在当前分支存在; `[~] 已生成但待复核` 表示文件存在但仍需按细管模型、灰度基底或数据 tint 重绑; `[ ] 未生成` 表示当前没有目标文件。文件存在不等于代码已注册, 也不等于分期验收完成。
- 资源类型缩写: BLOCK=方块贴图, ITEM=物品图标, TOOL=工具图标(物品图标的一种)。

当前实现与资产边界:

- 代码已注册: `iron_energy_cable`(T1)、`copper_energy_cable`(T3)。
- 代码未注册: `aluminum_energy_cable`(T2) 及其余 `ConductorMaterial` 条目; 旧 PNG 即使存在也不能写成“已接入”。
- 本清单旧版的 58 项仅覆盖一批历史同名 PNG, 不覆盖灰度矿脉覆盖层、原矿/锭/线材共享基底+tint、燃料芯、低温控制器等新增独立资产。因此“合计 58”不再是覆盖总量或完成标准。

---

## 二、P1 现在就需要(T1-T3 + 橡胶/PVC/PE 基础绝缘)

P1 的目标范围固定为 T1 铁、T2 铝、T3 铜, 并把橡胶、PVC、PE 前移为基础绝缘资产。当前代码只注册 T1 和 T3; T2 是 P1 目标但尚未注册。细管六向模型已经拍板, 以下条目不再保留整方块/细管待确认项。

| 资源类型 | 资源路径 / 文件名 | 用途 | 状态 | 说明 |
|---|---|---|---|---|
| BLOCK | textures/block/energy_cable_base.png | 六向细管线缆共用灰度本体 | [ ] 未生成 | T1-T3 及后续导体共用, 由材料数据 tint 导体芯与绝缘层; 不是每级一张彩色整方块贴图 |
| ITEM | textures/item/energy_cable_base.png | 六向细管线缆共用扁平灰度图标 | [ ] 未生成 | 物品图标独立于方块模型, 各级沿用同一基底并注入 tint |
| BLOCK / MODEL | `iron_energy_cable`(T1) | 已注册铁线缆的六向细管放置态 | [~] 旧 PNG 已存在, 新模型未接线 | `PowerRegistry` 已注册; 需将旧 `cube_all` 模型改为六向连接模型 |
| BLOCK / MODEL | `aluminum_energy_cable`(T2) | P1 铝线缆放置态 | [~] 旧 PNG 已存在, 代码未注册 | P1 目标条目, 铝土矿与导体注册落地后再接入 |
| BLOCK / MODEL | `copper_energy_cable`(T3) | 已注册铜线缆的六向细管放置态 | [~] 旧 PNG 已存在, 新模型未接线 | `PowerRegistry` 已注册; 铜芯 + PE tint 由数据驱动 |
| ITEM | item/rubber_tapping_knife.png | 割胶刀 | [x] 已生成 | 文件存在; 独立物品注册与配方仍需代码侧对账 |
| ITEM | item/latex.png | 生胶乳 | [x] 已生成 | 文件存在; 不代表物品已注册 |
| ITEM | item/rubber.png | 天然橡胶 | [x] 已生成 | P1 基础绝缘原料 |
| ITEM | item/insulation_pvc.png | PVC 绝缘料(70°C 档) | [x] 已生成 | P1 基础绝缘档 |
| ITEM | item/insulation_pe.png | PE 绝缘料(80°C 档) | [x] 已生成 | P1 基础绝缘档 |
| BLOCK | block/rubber_tree_sapling.png; rubber_log.png; rubber_log_top.png; rubber_log_tapped.png; rubber_planks.png; rubber_leaves.png | 橡胶树方块贴图 | [x] 已生成 | P1 全量内容; 已割胶原木用于持久化冷却状态, 木板是正式木材链的一部分 |

---

## 三、七矿共享资产方案(新增覆盖范围)

铝土、硼砂、银、锡、镍、铬、钨七种实际矿物采用同一套数据驱动资产管线: 矿脉使用一张灰度覆盖层叠加石质/深板岩基底, 原矿、锭、导线/线材使用共享灰度基底, 颜色与材质差异由矿物数据 tint 注入。共享基底不改变七种矿物各自拥有独立注册 id、掉落物与 worldgen 数据的事实。

| 资源族 | 实际矿物 / 建议 id | 分期 | 数据驱动资产 | 状态 |
|---|---|---|---|---|
| 铝土 | `bauxite_ore` -> `raw_aluminum` -> `aluminum_ingot` -> T2 线材 tint | P1 | 复用灰度矿脉覆盖层、原矿/锭/线材基底 | [ ] 共享基底与新矿资源尚未生成/接入 |
| 硼砂 | `borax_ore` -> `borax` | P2 前置 | 矿脉覆盖层 + 原矿/矿物基底; 不生成导体线材贴图 | [ ] 未生成 |
| 银 | `silver_ore` -> `raw_silver` -> `silver_ingot` -> T7/T9 线材 tint | P2 | 复用灰度矿脉覆盖层、原矿/锭/线材基底 | [ ] 未生成 |
| 锡 | `tin_ore` -> `raw_tin` -> `tin_ingot` -> T4 线材 tint | P2 | 复用灰度矿脉覆盖层、原矿/锭/线材基底 | [ ] 未生成 |
| 镍 | `nickel_ore` -> `raw_nickel` -> `nickel_ingot` -> 镍铬保险丝 | P3 | 复用灰度矿脉覆盖层、原矿/锭基底 | [ ] 未生成 |
| 铬 | `chromium_ore` -> `raw_chromium` -> `chromium_ingot` -> 镍铬保险丝 | P3 | 复用灰度矿脉覆盖层、原矿/锭基底 | [ ] 未生成 |
| 钨 | `tungsten_ore` -> `raw_tungsten` -> `tungsten_ingot` -> 耐热线 tint | P3 | 复用灰度矿脉覆盖层、原矿/锭/线材基底 | [ ] 未生成 |

共享基底文件登记:

| 资源类型 | 建议路径 / 文件名 | 用途 | 状态 |
|---|---|---|---|
| BLOCK | textures/block/ore_vein_overlay.png | 七个资源族共用的灰度矿脉覆盖层, 叠加石质/深板岩基底 | [ ] 未生成 |
| ITEM | textures/item/raw_ore_base.png | 原矿共用灰度形状 | [ ] 未生成 |
| ITEM | textures/item/ingot_base.png | 锭共用灰度形状 | [ ] 未生成 |
| ITEM | textures/item/wire_base.png | 导线与耐热线共用灰度形状 | [ ] 未生成 |

上述资源族的文件存在性、矿物 worldgen 注册、物品注册和 tint 数据接线分别验收; 任何一项未落地都不能把整条资源链标为完成。

---

## 四、后续分期资源清单

以下按 P2 / P3 列出 P1 之外的资产。原 P1.5 内容作为 P2 的前置设施与高纯材料资产, 不再另设独立交付门槛; 机器方块与线缆方块的 resource id 仍需以实际注册为准。表内历史 PNG 仅表示文件曾生成, 不表示对应方块、物品或配方已接入。

### P2 前置 — 提纯机 + 无氧铜档(OFC/OFE) + 空分氩气罐

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| BLOCK | block/metallurgic_purifier_top.png | 提纯机·顶面(P2) | 冶金灌注机顶部, 金属机壳 + 注料口 / 排气格栅 |
| BLOCK | block/metallurgic_purifier_side.png | 提纯机·侧面(P2) | 工业机壳侧板, 螺栓边框 + 管线细节 |
| BLOCK | block/metallurgic_purifier_front.png | 提纯机·正面待机(P2) | 正面熔炼腔口, 熄灭态深色 |
| BLOCK | block/metallurgic_purifier_front_on.png | 提纯机·正面工作态(P2) | 熔炼腔亮起橙红, 表示灌注进行中 |
| BLOCK | block/air_separation_unit_top.png | 空分装置·顶面(P2) | 深冷分馏塔顶, 冷凝盘管 / 阀件 |
| BLOCK | block/air_separation_unit_side.png | 空分装置·侧面(P2) | 高塔侧壁, 蓝白低温管路 + 结霜质感 |
| BLOCK | block/air_separation_unit_front.png | 空分装置·正面待机(P2) | 正面控制面板 / 出气口, 熄灭态 |
| BLOCK | block/air_separation_unit_front_on.png | 空分装置·正面工作态(P2) | 面板指示灯亮起, 表示制氩或液氮模式运行 |
| ITEM | item/deoxidized_copper_ingot.png | 脱氧铜锭(P2) | 粗铜锭经硼砂助熔后, 色泽较粗铜略净的红铜锭 |
| ITEM | item/phosphorus_deoxidized_copper_ingot.png | 磷脱氧铜锭(P2, 提纯机/空分装置部件) | 略带暗红/紫调的铜锭, 暗示残磷; 不进入 OFC 主链或线缆阶梯 |
| ITEM | item/ofc_copper_ingot.png | 无氧铜锭 OFC(P2) | 洁净亮红铜锭, 镜面高光, 无氧化斑 |
| ITEM | item/ofe_copper_ingot.png | 无氧高导铜锭 OFE(P2) | 最亮最纯的粉红铜锭, 边缘冷白高光, 顶级质感 |
| ITEM | item/argon_canister.png | 氩气罐(P2, OFE 顶级灌注料) | 加压钢瓶, 惰性气标识 / 冷白瓶身 + 阀头 |
| ITEM | item/copper_wire.png | 导线·中间物(P2 起, 拉丝退火产物) | 一小卷细铜导线; 各金属级沿共享基底注入 tint |
| BLOCK | block/ofc_copper_energy_cable.png | 无氧铜线缆(T5)放置态 | 亮红铜芯 + EPR 绝缘皮, 沿细管模型与数据 tint 接入 |
| ITEM | item/ofc_copper_energy_cable.png | 无氧铜线缆物品图标 | 沿共享线材基底注入 tint |
| BLOCK | block/ofe_copper_energy_cable.png | 无氧高导铜线缆(T6)放置态 | 顶级铜芯 + XLPE 交联绝缘皮, 沿细管模型与数据 tint 接入 |
| ITEM | item/ofe_copper_energy_cable.png | 无氧高导铜线缆物品图标 | 沿共享线材基底注入 tint |

P2 前置补充说明:
- 磷灌注料复用原版骨粉 / 骨头, 无需新图标; 硼砂 worldgen 接入当前真实生效的 datapack, 不依赖已退役的离线体素路径, 视觉基底和 tint 按本清单第三节登记。
- 铝锭等基础金属锭同样归矿物 / 冶炼系统产出, 本清单不含; 本清单只列线材系统自有的加工中间锭。
- `air_separation_unit` 已固定为独立机器, 氩气和液氮为菜单切换的独立工序; 四张机器贴图全部属于交付范围。

### P2 — 绝缘分档 + 填满 T4/T7/T8/T9

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| ITEM | item/insulation_epr.png | EPR 三元乙丙绝缘料(P2, 105°C 档) | 深灰哑光合成橡胶料 |
| ITEM | item/insulation_xlpe.png | XLPE 交联聚乙烯绝缘料(P2, 120°C/短路 250°C) | 半透高密料卷, 略带光泽 |
| ITEM | item/insulation_silicone.png | 硅橡胶绝缘料(P2, 180°C 顶级耐温) | 红棕 / 橙红硅橡胶料, 高端质感 |
| ITEM | item/gold_4n_ingot.png | 4N 金锭(P2, 提纯至 99.99%) | 极亮镜面金锭, 冷白高光, 区别于原版金锭 |
| ITEM | item/silver_ingot.png | 银锭(P2, T9 银芯 + T7 镀银用) | 亮银白金属锭, 冷调高光 |
| BLOCK | block/tinned_copper_energy_cable.png | 镀锡铜线缆(T4)放置态 | 铜芯外镀银灰锡层, 抗蚀侧档观感 |
| ITEM | item/tinned_copper_energy_cable.png | 镀锡铜线缆物品图标 | 同上 |
| BLOCK | block/silver_plated_copper_energy_cable.png | 镀银铜线缆(T7)放置态 | 铜芯外镀亮银层 + XLPE 皮, 高频高端 |
| ITEM | item/silver_plated_copper_energy_cable.png | 镀银铜线缆物品图标 | 同上 |
| BLOCK | block/gold_energy_cable.png | 金线缆(T8)放置态 | 金黄芯 + XLPE 皮, 强调耐热稳定 |
| ITEM | item/gold_energy_cable.png | 金线缆物品图标 | 同上 |
| BLOCK | block/silver_energy_cable.png | 银线缆(T9)放置态 | 亮银芯 + 硅橡胶皮, 顶级常规导体 |
| ITEM | item/silver_energy_cable.png | 银线缆物品图标 | 同上 |

P2 补充说明: PVC/PE 基础档已前移 P1; EPR/XLPE/硅橡胶为 P2 额外合成步产物(设计文档第七章), 图标观感建议按"天然褐 -> 合成灰 -> 高端红棕"梯度区分耐温档。锡矿与银矿由本清单第三节的灰度矿脉覆盖层与数据 tint 方案覆盖。本轮不增加铅矿或铅护套。

### 跨分期缺失的独立组件资产

以下文件当前不存在, 不能以旧 58 项中的任意线缆、机器或锭图标代替。燃料芯属于发电机的独立输入物; 低温控制器是 NbTi 超导线缆的独立机器方块, 同时登记四张方块面贴图与物品图标。

| 资源类型 | 建议路径 / 文件名 | 用途 | 分期 | 状态 |
|---|---|---|---|---|
| ITEM | item/industrial_fuel_core.png | 工业发电机燃料芯 | P1 | [ ] 未生成 |
| ITEM | item/modern_fuel_core.png | 现代发电机燃料芯 | P2 | [ ] 未生成 |
| ITEM | item/future_fuel_core.png | 未来发电机燃料芯 | P3 | [ ] 未生成 |
| ITEM | item/liquid_nitrogen_canister.png | 低温控制器每 24,000 tick 消耗的液氮罐 | P3 | [ ] 未生成 |
| BLOCK | block/low_temperature_controller_top.png | NbTi 低温控制器顶面 | P3 | [ ] 未生成 |
| BLOCK | block/low_temperature_controller_side.png | NbTi 低温控制器侧面 | P3 | [ ] 未生成 |
| BLOCK | block/low_temperature_controller_front.png | NbTi 低温控制器待机正面 | P3 | [ ] 未生成 |
| BLOCK | block/low_temperature_controller_front_on.png | NbTi 低温控制器工作正面 | P3 | [ ] 未生成 |
| ITEM | item/low_temperature_controller.png | NbTi 低温控制器物品图标 | P3 | [ ] 未生成 |

### P3 — 石墨烯 + 超导终局(T10-T12) + 镍铬保险丝 + 钨耐热线

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| BLOCK | block/graphene_energy_cable.png | 石墨烯线缆(T10)放置态 | 深黑石墨芯 + 六角晶格暗纹 + 硅橡胶皮, 高科技感 |
| ITEM | item/graphene_energy_cable.png | 石墨烯线缆物品图标 | 同上 |
| BLOCK | block/nbti_superconductor_energy_cable.png | NbTi 超导线缆(T11, 需冷却)放置态 | 冷蓝低温超导线, 表面结霜 / 冷雾质感 |
| ITEM | item/nbti_superconductor_energy_cable.png | NbTi 超导线缆物品图标 | 同上 |
| BLOCK | block/ybco_superconductor_energy_cable.png | YBCO 超导线缆(T12, 终极)放置态 | 高温超导涂层导体, 青黑陶瓷涂层 + 发光边线, 终局观感 |
| ITEM | item/ybco_superconductor_energy_cable.png | YBCO 超导线缆物品图标 | 同上 |
| ITEM | item/nichrome_fuse.png | 镍铬保险丝(P3, 发电机保险槽耗材) | 陶瓷底座 + 镍铬螺旋熔丝, 只作为物品, 不制作线路方块 |
| BLOCK | block/tungsten_heat_resistant_wire.png | 钨耐热线(P3, 低容量耐高温, 发电机旁短接)放置态 | 深灰钨芯短接线, 耐高温工业观感 |
| ITEM | item/tungsten_heat_resistant_wire.png | 钨耐热线物品图标 | 同上 |

P3 补充说明: 镍矿、铬矿、钨矿的 worldgen 走当前生效的 datapack, 视觉覆盖层和原矿/锭基底按本清单第三节登记; 镍铬保险丝只作为发电机保险槽耗材, 不另做方块贴图。

---

## 五、优先级建议

- P1 的最小交付是 T1-T3 的六向细管模型接线、共享线缆灰度基底、材料 tint, 以及橡胶/PVC/PE 基础绝缘资产。当前只有铁、铜代码注册; 铝仍是 P1 目标而非已接入成品。
- P2 从 T4 开始, 覆盖 T4-T9、EPR/XLPE/硅橡胶、七矿中的 P2 内容和提纯机/空分前置资产。原 P1.5 只作为 P2 前置资产分组, 不再单独计为完成阶段。
- P3 覆盖 T10-T12、镍铬保险丝、钨耐热线、低温控制器和未来燃料芯。缺少独立组件图标时, 不得用线缆或机器贴图代替。
- 细管六向连接模型已定稿; 物品图标与方块本体均按灰度基底 + 数据 tint 处理, 不再等待“整方块还是细管”确认。
- 机器工作态 `front_on`、`rubber_log_tapped` 与 `rubber_planks` 均属于本轮正式交付, 不能作为可选项后补。
- 七矿矿脉覆盖层、原矿/锭/线材共享基底和 tint 数据属于本次清单新增覆盖范围, 不再排除在外。

---

## 六、资源条目统计与验收边界

| 范围 | 当前事实 | 验收口径 |
|---|---|---|
| 历史 58 项 PNG | 文件已在当前分支存在, 但多为旧式逐级彩色贴图 | 统一按 `[~]` 处理, 直到细管模型与数据 tint 接线复核完成 |
| P1 目标 | T1-T3 + 橡胶/PVC/PE | 以共享基底、tint、模型连接和代码注册分别核对, 不以文件数量代替 |
| P2 目标 | T4-T9 + 七矿 P2 内容 + 提纯/空分前置 | 资源文件、worldgen/物品注册和配方接线均完成后才可标记 `[x]` |
| P3 目标 | T10-T12 + 支援组件 | 独立组件资产(含低温控制器、燃料芯)必须逐项存在 |
| 新增独立资产 | 灰度矿脉覆盖层、原矿/锭/线材基底、三档燃料芯、液氮罐、低温控制器 | 当前均按 `[ ] 未生成` 登记, 不从历史 58 项冲抵 |

统计口径: 旧 58 项只代表历史 PNG 文件数, 不是当前完整需求量。共享灰度基底、tint 数据、六向模型部件和独立组件均单独登记; 未生成或未接线条目不得写成已完成。
