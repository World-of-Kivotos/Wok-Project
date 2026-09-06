# 渔夫模块初步制作：图鉴、矿石鱼与鱼羹

本功能属于 WOK 本体，沿用 `modId=miningdim` 与单 JAR。入口为 `FishingSystem`，不另建强制安装的附属 MOD。矿石鱼种类的档次与厨师料理品质章相互独立；暗金矿鱼是当前最高鱼种档次。

本阶段定位为渔夫模块的初步制作，先打通图鉴持有收录、矿洞钓获、原鱼出售及厨师鱼羹联动。尚未新增渔夫职业身份、等级、经验或技能，也未记录独立的亲手钓获统计；这些属于后续职业成长阶段。以下概率、售价与料理数值均为首测方案，仍需游戏内体验和经济平衡验证。

## 获取与出售

仅在 `miningdim:mining` 的水域成功钓获时参与矿石鱼抽取。原版接入 `FishingHook.retrieve` 的实际战利品生成，Tide 1.6.5 通过成功收竿生成掉落前的可选 Mixin 接入。命中后用一条矿石鱼替换该次原渔获，不额外叠加；后续 Forge 事件取消仍阻止掉落。失败、岩浆和其他维度不产生矿石鱼。不提供拆解矿石或烧炼配方。

以下为可调整的首测值，配置文件 `world/serverconfig/miningdim-fishing.toml`。概率以每次成功钓获为分母，总权重不得超过 10000。

| 鱼种 | 物品 ID（miningdim） | 概率 | 单条基础信用点 |
| --- | --- | --- | --- |
| 铁矿鱼 | iron_ore_fish | 20% | 20 |
| 金矿鱼 | gold_ore_fish | 8% | 80 |
| 钻石矿鱼 | diamond_ore_fish | 2% | 400 |
| 绿宝石矿鱼 | emerald_ore_fish | 1% | 600 |
| 暗金矿鱼 | dark_gold_ore_fish | 0.2% | 2000 |

剩余 68.8% 保留原渔获。普通玩家执行 `/fishing sell` 出售主手整组矿石鱼，实发走既有全局每日信用点衰减；实发为零时保留鱼，经济系统不可用时明确失败。鱼羹不参加该收购。

持有即加入图鉴，交易取得也算，卖出后保留收录。图鉴含 4 种原版鱼与 5 种矿石鱼；加载 Tide 1.6.5 时再加入其 66 种鱼，总计 75 种。亲手钓获经验与新渔夫职业轨道不在本轮加入。

## 料理契约

每种鱼羹 ID 为鱼 ID 加 `_soup`。基础配方：对应矿石鱼、红/棕蘑菇、胡萝卜/马铃薯/甜菜根、碗，各一份，产出一碗。安装 Farmer's Delight 1.3.2 时另有烹饪锅配方，蘑菇和蔬菜兼容其 Forge 食材标签，仍只出一碗。

每碗基础饱食 8、饱和 9.6，最多堆叠 16，可在吃饱时饮用；生存食用返还一只碗。料理可进入已有厨师品质与料理效果流程。

| 鱼羹 | 矿洞内收益 |
| --- | --- |
| 铁鳞鱼羹 | 饥饿消耗降低 25% |
| 金鳞鱼羹 | 夜视 |
| 钻鳞鱼羹 | 挖掘速度 +15 个百分点，与矿工倍率相加 |
| 翠玉鱼羹 | 采掘工具经过耐久附魔后的磨损再降低 20% |
| 暗金鱼羹 | 夜视、挖速 +20 个百分点、采掘磨损降低 25%、饥饿消耗降低 25% |

基础时长 300 秒。只有菜上实际盖章的厨师 `AMPLIFY` 延长汤的时长，最多 1500 秒，不提升上述强度；`SPOILED` 不给予汤增益。任意维度可以食用并开始计时，矿洞外不享受汤收益但时间继续流逝。重复同种刷新，改喝另一种替换，时长不累加。死亡清除汤状态，正常退出再进入保留未过期状态。

铁/暗金耐饥与厨师 `ENDURANCE` 取较强者，避免双重结算。汤夜视尽量仅移除自身授予的实例，保留外部药水或厨师夜照。采掘耐久减免发生在 `ItemStack.hurt` 的耐久附魔裁决后、破坏判定前。

## 资源与验证

图标保存在 `assets/miningdim/textures/item/fishing/`；选图和内置 image_gen 提示词记录见 `tools/assets/fishing/v1/README.md`。首版保留原生成分辨率，十张均核验真实透明背景。

当前环境无 Context7，第三方接口依据锁定 Minecraft 1.20.1 / Forge 47.3.0、Tide 1.6.5 和 Farmer's Delight 1.3.2 的本地映射 JAR、源码及字节码核验。不得将其它 Tide 版本视为已验证兼容。

测试使用根工程 Wrapper 与 JDK 17，分别验证未安装 Tide 和已安装 Tide/Cloth Config 两种服务端组合。只有日志明确出现 `All N required tests passed` 才可宣布通过。客户端物品显示、图鉴操作和完整钓鱼小游戏另需实机验收。

2026-09-06 验证记录：

- JDK 17 / Gradle Wrapper 编译通过。
- 无 Tide：`build/ore-fish-gametest-no-tide-verified.log` 明确记录 `All 1457 required tests passed`。
- Tide 1.6.5 + Cloth Config 11.1.136：使用保留旧存档后的新测试世界，`build/ore-fish-gametest-tide-final.log` 明确记录 `All 1457 required tests passed`，目录加载 75 项。测试直接调用成功态的真实 Tide `retrieve` 并核对掉落实体，未模拟客户端小游戏界面。
- `jarJar`、`reobfJarJar` 通过，产物 `build/libs/miningdim-1.20.1-1.0.33-all.jar`。JAR 内核对 modId、十张图标及原版 Mixin refmap。
- `verifyModuleRegistry` 通过。`verifyModuleBoundaries` 中此次渔业新增内容通过，整体仍被 main 既有 12 张未登记材料贴图阻挡（如 `aluminum_ingot.png`、`raw_tungsten.png`）；未跨模块改动材料登记，也未合入 main。
- 源码保存在功能分支 `codex/ore-fish-soup`，没有覆盖根目录已有未提交工作，也没有部署到玩家客户端或服务端。
