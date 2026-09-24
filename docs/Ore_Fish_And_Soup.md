# 渔夫模块初步制作：图鉴、矿石鱼与鱼羹

本功能属于 WOK 本体，沿用 `modId=miningdim` 与单 JAR。入口为 `FishingSystem`，不另建强制安装的附属 MOD。矿石鱼的鱼种品质与厨师料理品质章相互独立；暗金鱼是矿石鱼里的最高档（传说）。模块级的唯一主设计入口是 [渔夫职业设计规格](Fisher_Job_DesignSpec.md)，本文是其矿石鱼与鱼羹专题；全鱼种的六档鱼种品质见其第四章，每次钓获的体型与个人钓获记录见其第五章，本文不重复。

本阶段定位为渔夫模块的初步制作，先打通图鉴持有收录、矿洞钓获、原鱼出售及厨师鱼羹联动。尚未新增渔夫职业身份、等级、经验或技能，这些属于后续职业成长阶段；独立的亲手钓获统计已随体型功能加入（个人钓获记录），但同样不发经验。以下概率、售价与料理数值均为首测方案，仍需游戏内体验和经济平衡验证。

## 获取与出售

仅在 `miningdim:mining` 的水域成功钓获时参与矿石鱼抽取。原版接入 `FishingHook.retrieve` 的实际战利品生成，Tide 1.6.5 通过成功收竿生成掉落前的可选 Mixin 接入。命中后用一条矿石鱼替换该次原渔获，不额外叠加；后续 Forge 事件取消仍阻止掉落。失败、岩浆和其他维度不产生矿石鱼。不提供拆解矿石或烧炼配方。

以下为可调整的首测值，配置文件 `world/serverconfig/miningdim-fishing.toml`。概率以每次成功钓获为分母，总权重不得超过 10000。

| 鱼种 | 物品 ID（miningdim） | 配置键 | 鱼种品质 | 概率 | 单条基础信用点 |
| --- | --- | --- | --- | --- | --- |
| 铁鱼 | iron_ore_fish | iron | 普通 | 20% | 20 |
| 金鱼 | gold_ore_fish | gold | 优良 | 8% | 80 |
| 钻石鱼 | diamond_ore_fish | diamond | 稀有 | 2% | 400 |
| 绿宝石鱼 | emerald_ore_fish | emerald | 史诗 | 1% | 600 |
| 暗金鱼 | dark_gold_ore_fish | dark_gold | 传说 | 0.2% | 2000 |

首列是游戏内简中显示名，取自 `assets/miningdim/lang/zh_cn.json`，五条都没有“矿”字；英文显示名反而带 Ore（`Iron Ore Fish` 等，见 `en_us.json`）。简中统称仍是“矿石鱼”，对应分类键 `ore_fish`。

鱼种品质取自 `OreFishType.quality()`，与品质标签 `data/miningdim/tags/items/fish_quality/` 里的归档一致，决定物品名在游戏内的显示颜色。五种鱼各占一档（普通到传说），品质、权重与售价三个序列同向：绿宝石鱼比钻石鱼更稀有（权重 100 对 200）、更贵（600 对 400），品质也高一档（史诗对稀有）。鱼羹不进品质标签，`OreFishingItems` 注册时让鱼羹的名字颜色跟随原料鱼的品质。传说档是运行期扩展的稀有度（金色），渲染接缝与分档模型见 [渔夫职业设计规格](Fisher_Job_DesignSpec.md) 第四章。

配置分节与键名由 `OreFishingConfig` 定义，服主改 toml 认的是上表第三列的配置键，**不是**第二列的物品 ID：

- `[catch_weights]`：`iron` / `gold` / `diamond` / `emerald` / `dark_gold` 五个万分权重键，单键取值范围 0–10000。五者之和超过 10000 时，`ModConfigEvent.Loading` 与 `ModConfigEvent.Reloading` 直接抛 `IllegalArgumentException`，配置拒绝加载。
- `[sell_prices]`：同名五个单条基础收购价键，单键取值范围 1–1000000。

剩余 68.8% 保留原渔获。普通玩家执行 `/fishing sell` 出售主手整组矿石鱼，并连带背包主栏里同鱼种的其它非奖杯栈（体型档会把同一鱼种拆成几组互不堆叠的栈）；`/fishing sell all` 出售背包主栏里全部非奖杯矿石鱼。奖杯个体只在拿在主手执行 `/fishing sell` 时出售，副手不参与。收购价为基础价乘条数，与体型无关；一条命令合并成一笔入账，实发走既有全局每日信用点衰减。完整口径见 [Fisher_Job_DesignSpec.md](Fisher_Job_DesignSpec.md) 的玩家命令章。
结算顺序与卖菜一致：**先扣鱼再入账**。`grantDaily` 的第一步就是把毛收入写进当日 faucet 计数器，
所以「实发为零就保留鱼」会给计数器记下一笔从未成交的销售、并把衰减档位白推一格；现在深档实发为零也照常扣鱼，
与卖菜「收购曲线到底仍算卖出」同口径，命令回执单独提示实发为零。入账抛异常时把扣下的各栈连同体型标签原样退回再重抛。
经济系统不可用时不扣不发、明确失败。鱼羹不参加该收购。

待决（未闭合）：`/fishing sell`（含 `/fishing sell all`）目前**没有身份门**。卖菜有 `SELL_MIN_MASTERY_LEVEL` 这道反洗钱门，
本阶段渔夫职业身份尚未落地故无等级可依，矿石鱼又可自由堆叠转移 —— 跨账号把鱼集中给一人出售即可摊薄每日衰减。
这与既有的跨账号洗额度结构性问题同源，须与经济总表一并定夺，不在本模块单独决策。
该条已于 2026-09-19 登记为 PENDING 并裁决按现状合入，权威记录与后续处理路线（方向 A 职业门 / 方向 B 全服供给定价）
见 [Economy_BalanceSheet_DesignSpec.md](Economy_BalanceSheet_DesignSpec.md) 第六节第 6 条，模块侧同步登记在
[Fisher_Job_DesignSpec.md](Fisher_Job_DesignSpec.md) 的反洗钱章。

持有即加入图鉴，交易取得也算，卖出后保留收录。图鉴含 4 种原版鱼与 5 种矿石鱼；加载 Tide 1.6.5 时再加入其 66 种鱼，总计 75 种。亲手钓获经验与新渔夫职业轨道不在本轮加入。

创造模式下五种矿石鱼与五种鱼羹在原版「食物与饮品」分类，渔业图鉴在「工具与实用物品」分类（`FishingSystem.onCreativeTab`）。

模块还交付了物品标签 `miningdim:ore_fish`（`data/miningdim/tags/items/ore_fish.json`），把五种矿石鱼聚合在一起，为下游配方与任务预留。当前全库没有消费方：Java 侧没有对应的 `TagKey` 常量，十条鱼羹配方引用矿石鱼时用的也都是具体物品 ID，没有一处读这个标签。

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

基础时长 300 秒。只有菜上实际盖章的厨师 `AMPLIFY` 延长汤的时长，最多 1500 秒，不提升上述强度；`SPOILED` 不给予汤增益。任意维度可以食用并开始计时，矿洞外不享受汤收益但时间继续流逝。重复同种刷新，改喝另一种替换，时长不累加。死亡清除汤状态；正常退出再进入、以及从末地主出口回主世界这类非死亡的玩家实体重建，都保留未过期状态与原到期时刻（Forge 的 `restoreFrom` 只搬 `PlayerPersisted` 子标签，故由 `PlayerEvent.Clone` 显式搬运，`isWasDeath` 时不搬）。

矿洞内任意一种鱼羹生效时，厨师 `ENDURANCE` 一律改走 `Player.causeFoodExhaustion` 的疲劳缩减结算，不再走它自己那条周期回补饱和的路径（`ChefHungerHandler` 的早退条件是 `OreSoupEffects.activeInMining`，对五种鱼羹一视同仁，只有 `SATIATION` 在身时例外）；其中铁/暗金自带的 25% 与厨师耐饥在 `OreSoupEffects.exhaustionReductionPerMille` 里取较强者，避免双重结算。也就是说喝本身零耐饥的金/钻/翠玉鱼羹同样会换掉厨师耐饥的结算机制：效果没丢，但数值口径从「每 40 tick 按比例回补饱和」变成「疲劳乘 (1000 - reduction)/1000」。汤夜视尽量仅移除自身授予的实例，保留外部药水或厨师夜照；状态刚变为「矿洞内有汤」的那一 tick 立即补发夜视，之后按 80 tick 错峰续期。采掘耐久减免发生在 `ItemStack.hurt` 的耐久附魔裁决后、破坏判定前，并按实际写回的 damage 重算损坏判定——否则减免在工具最后一点耐久上会把工具赔掉。减免只覆盖挖方块这一条路径，攻击与其它耐久消耗不受影响。

## 资源与验证

图标保存在 `assets/miningdim/textures/item/fishing/`，统一 64x64（正方形、2 的幂、保住 4 级 mipmap，
且恰好等于 GUI 缩放 4 档下一个物品格的真实像素数）；由 `tools/fishing/build_ore_fish_icons.py`
从 `tools/assets/fishing/v1/source/` 的原画派生，原画不进 JAR，十张合计 76 KB。
选图和已留档的内置 image_gen 提示词见 `tools/assets/fishing/v1/README.md`（铁鱼原画的提示词未留档，该 README 已注明，不要按「十张提示词全在此处」去找）。十张均核验真实透明背景。
`FishingAssetGameTests` 立三个用例，判据全部取原版算法本身而非脚本参数：

1. `everyFishingIconSurvivesVanillaAtlasStitching`：帧尺寸整除规则、mipmap 可整除次数（至少 4 级）、
   边长不超过 `MAX_ICON_EDGE=256`、必须带 Alpha 通道且整张至少有一个全透明像素。
2. `everyFishingIconStaysCheapForItemModelGeneration`：`ItemModelGenerator` 沿 Alpha 轮廓烘出的 element 数上限
   （当前十张最大 134，十张合计 949，红线 200；本模块最初提交的原画分辨率下这一项是单张 3473、十张 17729）。
3. `everyFishingItemModelBindsItsOwnIcon`：物品模型必须继承 `minecraft:item/generated`，且 `layer0` 指回同名图标
   `miningdim:item/fishing/<icon>`。

当前环境无 Context7，第三方接口依据锁定 Minecraft 1.20.1 / Forge 47.3.0、Tide 1.6.5 和 Farmer's Delight 1.3.2 的本地映射 JAR、源码及字节码核验。不得将其它 Tide 版本视为已验证兼容。

`TideOreFishMixin` 按 Tide 1.6.5 的 `retrieve` 签名与私有字段写死，故不放主 mixin 配置，单独进 `miningdim.compat.mixins.json`（`required=false`）。Mixin 0.8.5 的 `MixinProcessor.handleMixinError` 按配置的 `isRequired()` 决定抛 `MixinApplyError` 还是只记 WARN —— 玩家装上别的 Tide 版本时，非必需配置只会停用这一个 mixin（Tide 自己的钓鱼流程照常工作，只是 Tide 钓竿的渔获既不替换出矿石鱼、也不做体型结算，因为 Tide 不发 `ItemFishedEvent`；原版钓竿路径不受影响），不会让整个服务端启动崩掉。

测试使用根工程 Wrapper 与 JDK 17，分别验证未安装 Tide 和已安装 Tide/Cloth Config 两种服务端组合。只有日志明确出现 `All N required tests passed` 才可宣布通过。客户端物品显示、图鉴操作和完整钓鱼小游戏另需实机验收。

2026-09-06 验证记录：

- JDK 17 / Gradle Wrapper 编译通过。
- 无 Tide：`build/ore-fish-gametest-no-tide-verified.log` 明确记录 `All 1457 required tests passed`。
- Tide 1.6.5 + Cloth Config 11.1.136：使用保留旧存档后的新测试世界，`build/ore-fish-gametest-tide-final.log` 明确记录 `All 1457 required tests passed`，目录加载 75 项。测试直接调用成功态的真实 Tide `retrieve` 并核对掉落实体，未模拟客户端小游戏界面。
- `jarJar`、`reobfJarJar` 通过，产物 `build/libs/miningdim-1.20.1-1.0.33-all.jar`。JAR 内核对 modId、十张图标及原版 Mixin refmap。
- `verifyModuleRegistry` 通过。`verifyModuleBoundaries` 中此次渔业新增内容通过，当时整体被 main 既有 12 张未登记材料贴图阻挡（如 `aluminum_ingot.png`、`raw_tungsten.png`）；本模块未跨模块改动材料登记。
- 源码当时保存在功能分支 `codex/ore-fish-soup`，没有覆盖根目录已有未提交工作。

合入后状态（截至本文档所在基线 main `bd0c4588`）：

- 上述 12 张材料贴图已由电力模块登记进 `docs/modules/module-registry.json` 与 `docs/modules/RESOURCE_OWNERSHIP.md`（`aluminum_ingot`、`raw_tungsten` 等均在电力模块的 `resourceNamePrefixes` 里），该阻塞已解除，`verifyModuleBoundaries` 不再被它卡住。
- 渔夫模块源码已合入 main，不再停留在 `codex/ore-fish-soup`：基线 main 上已含完整的 `job/fisher`、`mixin/VanillaOreFishMixin` 与 `mixin/compat/TideOreFishMixin`，并在 `docs/modules/module-registry.json` 登记为独立模块 `wok-job-fisher`。
- 客户端物品显示、图鉴操作与完整钓鱼小游戏仍未实机验收，也未部署到玩家客户端或服务端。
