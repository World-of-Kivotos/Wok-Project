# 电力线材系统 Mod — 美术资源需求清单

## 一、文档说明

- 用途: 线材(导体/线缆)子系统的美术需求总清单。列出为让本系统各分期成品拥有正式外观所需绘制的全部方块贴图与物品图标, 供美术照单绘制。所有机制/分级依据以 `docs/Power_Cable_DesignSpec.md` 为唯一真源, 本文档只做美术资产映射, 不改机制。
- 关联真源: 导体阶梯与材料 id 见设计文档第四章 12 级导体表及 `ConductorMaterial` 枚举; 绝缘 5 档见 `InsulationGrade` 枚举; 分期路线见设计文档第十三章。
- 贴图规格通用约定:
  - 方块贴图: 16x16 PNG, 放置于 `src/main/resources/assets/miningdim/textures/block/`。
  - 物品图标: 16x16 PNG(扁平 2D sprite), 放置于 `src/main/resources/assets/miningdim/textures/item/`。
  - 命名: 一律小写下划线, 与注册 id / blockstate / model / lang 键完全一致(如 `iron_energy_cable`), 大小写或拼写不符将导致材质丢失(紫黑格)。
  - 多面机器方块: 按 `top` / `side` / `front` 拆分独立贴图文件, 工作态另出 `front_on`。
- 当前占位策略(P1):
  - 线缆方块模型暂用 `minecraft:block/cube_all` 父模型, 贴图直接指向原版方块贴图: `iron_energy_cable` 指 `minecraft:block/iron_block`, `copper_energy_cable` 指 `minecraft:block/copper_block`(见 `models/block/iron_energy_cable.json` 与 `copper_energy_cable.json`)。
  - 物品图标当前继承方块 `cube_all` 模型, 即以整方块 3D 渲染充当图标, 尚无独立扁平 sprite。
  - 现状是"整方块占位"; 线缆真实外观应为细连接管状(类似管道 mod 的细杆 + 端口连接件), 与整方块观感差距大, 属首要替换项。
- 状态/严重度记法: 纯文本 Critical / Major / Minor 与 [x] / [ ]; 优先级用 P1 / P1.5 / P2 / P3; 不使用任何 emoji 或颜色符号。
- 资源类型缩写: BLOCK=方块贴图, ITEM=物品图标, TOOL=工具图标(物品图标的一种)。

---

## 二、P1 现在就需要(先画这一批即可让本期成品有正式外观)

本期(P1)代码实际注册的线缆方块仅铁、铜两种(见 `PowerRegistry.P1_MATERIALS`, 原版金属可直接 raw 搓)。以下 4 项是当下唯一"缺正式贴图"的资产。

| 资源类型 | 资源路径 / 文件名 | 用途 | 当前占位 | 说明 |
|---|---|---|---|---|
| BLOCK | textures/block/iron_energy_cable.png | 铁能量线缆(T1)放置态外观 | `minecraft:block/iron_block`(整方块) | 线缆真实应为细连接管状, 现用整方块占位; 铁线为最差档, 建议暗灰铁质 + 灰黑绝缘皮观感 |
| ITEM | textures/item/iron_energy_cable.png | 铁能量线缆物品栏 / 手持图标 | 继承方块 cube_all 3D 渲染(即铁块外观) | 若线缆改细管模型需独立扁平图标; 若维持整方块占位则物品图标自动取方块渲染, 可暂不单出 |
| BLOCK | textures/block/copper_energy_cable.png | 铜能量线缆(T3)放置态外观 | `minecraft:block/copper_block`(整方块) | 同上, 现整方块占位; 铜线为前中期主力, 建议橙铜芯 + 浅色 PE 绝缘皮观感 |
| ITEM | textures/item/copper_energy_cable.png | 铜能量线缆物品栏 / 手持图标 | 继承方块 cube_all 3D 渲染(即铜块外观) | 同铁线物品图标说明 |

注: 若决定线缆改用细管模型(推荐), 方块贴图可能需拆成"线芯段"与"端口连接件"两张(接管道式 blockstate 多部件模型); 该模型方案一旦确定, 本表贴图数量会相应调整。请在开画前与开发确认最终采用整方块还是细管模型。

---

## 三、后续分期资源清单

以下按设计文档第十三章分期(P1.5 / P2 / P3)列出后续所有需绘制资产。机器方块与线缆方块的 resource id 均为建议值(尚未注册), 落码时以此为准可零改名对齐。标 (可选) 者为分期内非必需项。

### P1.5 — 提纯机 + 无氧铜档(OFC/OFE) + 空分氩气罐

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| BLOCK | block/metallurgic_purifier_top.png | 提纯机·顶面(P1.5) | 冶金灌注机顶部, 金属机壳 + 注料口 / 排气格栅 |
| BLOCK | block/metallurgic_purifier_side.png | 提纯机·侧面(P1.5) | 工业机壳侧板, 螺栓边框 + 管线细节 |
| BLOCK | block/metallurgic_purifier_front.png | 提纯机·正面待机(P1.5) | 正面熔炼腔口, 熄灭态深色 |
| BLOCK | block/metallurgic_purifier_front_on.png | 提纯机·正面工作态(P1.5, 可选) | 熔炼腔亮起橙红, 表示灌注进行中 |
| BLOCK | block/air_separation_unit_top.png | 空分装置·顶面(P1.5) | 深冷分馏塔顶, 冷凝盘管 / 阀件 |
| BLOCK | block/air_separation_unit_side.png | 空分装置·侧面(P1.5) | 高塔侧壁, 蓝白低温管路 + 结霜质感 |
| BLOCK | block/air_separation_unit_front.png | 空分装置·正面待机(P1.5) | 正面控制面板 / 出气口, 熄灭态 |
| BLOCK | block/air_separation_unit_front_on.png | 空分装置·正面工作态(P1.5, 可选) | 面板指示灯亮起, 表示制氩运行 |
| ITEM | item/deoxidized_copper_ingot.png | 脱氧铜锭(P1.5) | 粗铜锭经硼砂助熔后, 色泽较粗铜略净的红铜锭 |
| ITEM | item/phosphorus_deoxidized_copper_ingot.png | 磷脱氧铜锭(P1.5, 便宜打折档) | 略带暗红/紫调的铜锭, 暗示残磷 |
| ITEM | item/ofc_copper_ingot.png | 无氧铜锭 OFC(P1.5) | 洁净亮红铜锭, 镜面高光, 无氧化斑 |
| ITEM | item/ofe_copper_ingot.png | 无氧高导铜锭 OFE(P1.5, 顶级铜) | 最亮最纯的粉红铜锭, 边缘冷白高光, 顶级质感 |
| ITEM | item/argon_canister.png | 氩气罐(P1.5, OFE 顶级灌注料) | 加压钢瓶, 惰性气标识 / 冷白瓶身 + 阀头 |
| ITEM | item/copper_wire.png | 导线·中间物(P1.5 起, 拉丝退火产物) | 一小卷细铜导线; 各金属级可复用此图标改色或按级另出 |
| BLOCK | block/aluminum_energy_cable.png | 铝能量线缆(T2)放置态 | 银灰铝芯 + PVC 绝缘皮; 设计属 P1, 因待铝土矿(新矿)故随 P1.5 补 |
| ITEM | item/aluminum_energy_cable.png | 铝能量线缆物品图标 | 同上, 银灰细管卷 |
| BLOCK | block/ofc_copper_energy_cable.png | 无氧铜线缆(T5)放置态 | 亮红铜芯 + EPR 绝缘皮, 较普通铜更净 |
| ITEM | item/ofc_copper_energy_cable.png | 无氧铜线缆物品图标 | 同上 |
| BLOCK | block/ofe_copper_energy_cable.png | 无氧高导铜线缆(T6)放置态 | 顶级铜芯 + XLPE 交联绝缘皮, 观感更高端 |
| ITEM | item/ofe_copper_energy_cable.png | 无氧高导铜线缆物品图标 | 同上 |

P1.5 补充说明:
- 磷灌注料复用原版骨粉 / 骨头, 无需新图标; 硼砂矿及硼砂物品归 `OreSystem` / 矿物 worldgen 框架(设计文档第九章), 不在本线材美术清单内。
- 铝锭等基础金属锭同样归矿物 / 冶炼系统产出, 本清单不含; 本清单只列线材系统自有的加工中间锭。
- `air_separation_unit` 若最终并入提纯机的一个高耗能配方(设计文档第六章待定), 则其 4 张机器贴图作废, 仅保留氩气罐图标。

### P2 — 橡胶树 + 绝缘分档 + 填满 T4/T7/T8/T9 + 铅护套

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| BLOCK | block/rubber_tree_sapling.png | 橡胶树苗(P2, cross 模型, item 继承) | 丛林系小树苗, 阔叶 + 细茎 |
| BLOCK | block/rubber_log.png | 橡胶树原木·侧面(P2) | 灰褐树皮, 可叠加割痕纹理走向 |
| BLOCK | block/rubber_log_top.png | 橡胶树原木·顶面年轮(P2) | 浅色木芯年轮圈 |
| BLOCK | block/rubber_log_tapped.png | 橡胶树已割胶态树皮(P2, 可选) | 树皮上斜向割痕 + 集胶点, 冷却态偏干 |
| BLOCK | block/rubber_leaves.png | 橡胶树叶(P2) | 浓绿阔叶簇, 半透边缘 |
| BLOCK | block/rubber_planks.png | 橡胶木板(P2, 可选副产) | 砍树副产的浅褐木板纹 |
| TOOL | item/rubber_tapping_knife.png | 割胶刀(P2, 右键树干出胶) | 短柄弯刃割胶刀, 木柄 + 金属斜刃 |
| ITEM | item/latex.png | 生胶乳(P2, 割胶原料) | 一小桶 / 一坨乳白色黏稠胶乳 |
| ITEM | item/rubber.png | 橡胶(P2, 天然胶=基础绝缘 PVC/PE 级) | 深褐色压干橡胶块 / 胶片 |
| ITEM | item/insulation_pvc.png | PVC 绝缘料(P2, 70°C 档) | 灰色 PVC 绝缘皮料卷, 最基础 |
| ITEM | item/insulation_pe.png | PE 绝缘料(P2, 80°C 档) | 米白 / 半透 PE 料卷 |
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
| ITEM | item/lead_sheath.png | 铅护套(P2+, 电缆铠装抗爆层, 发电机旁) | 暗灰铅质护套 / 铠装环片 |

P2 补充说明: 绝缘 5 档中 PVC/PE 由天然橡胶直接加工, EPR/XLPE/硅橡胶为额外合成步产物(设计文档第七章); 图标观感建议按"天然褐 -> 合成灰 -> 高端红棕"梯度区分耐温档。锡矿 / 银矿 / 铅矿的矿石方块贴图归矿物系统, 不在本清单。

### P3 — 石墨烯 + 超导终局(T10-T12) + 镍铬保险丝 + 钨耐热线

| 资源类型 | 建议 resource id / 文件名 | 用途(分期) | 视觉描述(一句) |
|---|---|---|---|
| BLOCK | block/graphene_energy_cable.png | 石墨烯线缆(T10)放置态 | 深黑石墨芯 + 六角晶格暗纹 + 硅橡胶皮, 高科技感 |
| ITEM | item/graphene_energy_cable.png | 石墨烯线缆物品图标 | 同上 |
| BLOCK | block/nbti_superconductor_energy_cable.png | NbTi 超导线缆(T11, 需冷却)放置态 | 冷蓝低温超导线, 表面结霜 / 冷雾质感 |
| ITEM | item/nbti_superconductor_energy_cable.png | NbTi 超导线缆物品图标 | 同上 |
| BLOCK | block/ybco_superconductor_energy_cable.png | YBCO 超导线缆(T12, 终极)放置态 | 高温超导涂层导体, 青黑陶瓷涂层 + 发光边线, 终局观感 |
| ITEM | item/ybco_superconductor_energy_cable.png | YBCO 超导线缆物品图标 | 同上 |
| ITEM | item/nichrome_fuse.png | 镍铬保险丝(P3, 过流熔断保护件) | 陶瓷底座 + 镍铬螺旋熔丝, 保护件观感(方块 vs 物品待实现期定) |
| BLOCK | block/tungsten_heat_resistant_wire.png | 钨耐热线(P3, 低容量耐高温, 发电机旁短接)放置态 | 深灰钨芯短接线, 耐高温工业观感 |
| ITEM | item/tungsten_heat_resistant_wire.png | 钨耐热线物品图标 | 同上 |

P3 补充说明: 镍矿 / 铬矿 / 钨矿的矿石方块贴图归矿物系统; 镍铬保险丝最终定为独立组件方块还是物品(内联熔断)由实现期决定, 若为方块则需另拆 top/side 面贴图。

---

## 四、优先级建议

- 当下(P1)只需先画 `iron_energy_cable` 与 `copper_energy_cable` 两种线缆的方块贴图各一张(共 2 张 BLOCK), 即可让本期唯一两种已注册成品脱离"原版铁块 / 铜块"占位, 拥有正式外观。这是投入产出比最高、最该先做的一批。
- 物品图标是否单独绘制取决于线缆模型方案: 若维持整方块占位, 物品图标自动取方块 3D 渲染, P1 可暂不单出; 若改为推荐的细连接管状模型, 则每种线缆需补一张扁平物品图标, 且方块贴图可能拆成线芯段 + 端口件两张。开画前请与开发敲定模型方案, 避免返工。
- P1.5 及以后的资产按分期推进再画: 每当一个门槛(提纯机 / 空分 / 新矿 / 橡胶树 / 高能合成)在代码侧落地, 再补对应那一档的机器、中间锭、线缆与绝缘料图标即可, 不必一次性全画。
- 机器方块(提纯机 / 空分)工作态 `front_on` 与橡胶树 `rubber_log_tapped`、`rubber_planks` 等标 (可选) 项可最后补, 缺失不影响功能, 只影响观感细节。
- 矿石方块本身、基础金属锭(铝 / 锡 / 铅 / 镍 / 铬 / 钨)、硼砂、磷源均归矿物 / 冶炼系统美术, 不在本线材清单内, 避免重复绘制。

---

## 五、资源条目统计

| 分期 | 条目数 | 其中可选 |
|---|---|---|
| P1(现在就需要) | 4 | 0(2 张物品图标视模型方案可暂免) |
| P1.5 | 20 | 2(两台机器工作态 front_on) |
| P2 | 25 | 2(已割胶树皮、橡胶木板) |
| P3 | 9 | 0 |
| 合计 | 58 | 4 |

统计口径: 每个多面机器方块的每张面贴图、每种线缆的方块贴图与物品图标均各计 1 条。若线缆最终采用细管多部件模型导致贴图拆分, 或机器并入配方而非独立方块, 总数会相应调整。
