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

剩余 68.8% 保留原渔获。普通玩家执行 `/fishing sell` 出售主手整组矿石鱼，实发走既有全局每日信用点衰减。
结算顺序与卖菜一致：**先扣鱼再入账**。`grantDaily` 的第一步就是把毛收入写进当日 faucet 计数器，
所以「实发为零就保留鱼」会给计数器记下一笔从未成交的销售、并把衰减档位白推一格；现在深档实发为零也照常扣鱼，
与卖菜「收购曲线到底仍算卖出」同口径，命令回执单独提示实发为零。入账抛异常时把鱼原样退回再重抛。
经济系统不可用时不扣不发、明确失败。鱼羹不参加该收购。

待决（未闭合）：`/fishing sell` 目前**没有身份门**。卖菜有 `SELL_MIN_MASTERY_LEVEL` 这道反洗钱门，
本阶段渔夫职业身份尚未落地故无等级可依，矿石鱼又可自由堆叠转移 —— 跨账号把鱼集中给一人出售即可摊薄每日衰减。
这与既有的跨账号洗额度结构性问题同源，须与经济总表一并定夺，不在本模块单独决策。

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

基础时长 300 秒。只有菜上实际盖章的厨师 `AMPLIFY` 延长汤的时长，最多 1500 秒，不提升上述强度；`SPOILED` 不给予汤增益。任意维度可以食用并开始计时，矿洞外不享受汤收益但时间继续流逝。重复同种刷新，改喝另一种替换，时长不累加。死亡清除汤状态；正常退出再进入、以及从末地主出口回主世界这类非死亡的玩家实体重建，都保留未过期状态与原到期时刻（Forge 的 `restoreFrom` 只搬 `PlayerPersisted` 子标签，故由 `PlayerEvent.Clone` 显式搬运，`isWasDeath` 时不搬）。

铁/暗金耐饥与厨师 `ENDURANCE` 取较强者，避免双重结算。汤夜视尽量仅移除自身授予的实例，保留外部药水或厨师夜照；状态刚变为「矿洞内有汤」的那一 tick 立即补发夜视，之后按 80 tick 错峰续期。采掘耐久减免发生在 `ItemStack.hurt` 的耐久附魔裁决后、破坏判定前，并按实际写回的 damage 重算损坏判定——否则减免在工具最后一点耐久上会把工具赔掉。减免只覆盖挖方块这一条路径，攻击与其它耐久消耗不受影响。

## 资源与验证

图标保存在 `assets/miningdim/textures/item/fishing/`，统一 64x64（正方形、2 的幂、保住 4 级 mipmap，
且恰好等于 GUI 缩放 4 档下一个物品格的真实像素数）；由 `tools/fishing/build_ore_fish_icons.py`
从 `tools/assets/fishing/v1/source/` 的原画派生，原画不进 JAR，十张合计 76 KB。
选图和内置 image_gen 提示词记录见 `tools/assets/fishing/v1/README.md`。十张均核验真实透明背景。
`FishingAssetGameTests` 立三条契约，判据全部取原版算法本身而非脚本参数：帧尺寸整除规则、mipmap 可整除次数、
以及 `ItemModelGenerator` 沿 Alpha 轮廓烘出的 element 数上限（当前十张最大 134，十张合计 949，红线 200；
本模块最初提交的原画分辨率下这一项是单张 3473、十张 17729）。

当前环境无 Context7，第三方接口依据锁定 Minecraft 1.20.1 / Forge 47.3.0、Tide 1.6.5 和 Farmer's Delight 1.3.2 的本地映射 JAR、源码及字节码核验。不得将其它 Tide 版本视为已验证兼容。

`TideOreFishMixin` 按 Tide 1.6.5 的 `retrieve` 签名与私有字段写死，故不放主 mixin 配置，单独进 `miningdim.compat.mixins.json`（`required=false`）。Mixin 0.8.5 的 `MixinProcessor.handleMixinError` 按配置的 `isRequired()` 决定抛 `MixinApplyError` 还是只记 WARN —— 玩家装上别的 Tide 版本时，非必需配置只会停用这一个 mixin（矿洞钓鱼退回原版路径），不会让整个服务端启动崩掉。

测试使用根工程 Wrapper 与 JDK 17，分别验证未安装 Tide 和已安装 Tide/Cloth Config 两种服务端组合。只有日志明确出现 `All N required tests passed` 才可宣布通过。客户端物品显示、图鉴操作和完整钓鱼小游戏另需实机验收。

2026-09-06 验证记录：

- JDK 17 / Gradle Wrapper 编译通过。
- 无 Tide：`build/ore-fish-gametest-no-tide-verified.log` 明确记录 `All 1457 required tests passed`。
- Tide 1.6.5 + Cloth Config 11.1.136：使用保留旧存档后的新测试世界，`build/ore-fish-gametest-tide-final.log` 明确记录 `All 1457 required tests passed`，目录加载 75 项。测试直接调用成功态的真实 Tide `retrieve` 并核对掉落实体，未模拟客户端小游戏界面。
- `jarJar`、`reobfJarJar` 通过，产物 `build/libs/miningdim-1.20.1-1.0.33-all.jar`。JAR 内核对 modId、十张图标及原版 Mixin refmap。
- `verifyModuleRegistry` 通过。`verifyModuleBoundaries` 中此次渔业新增内容通过，整体仍被 main 既有 12 张未登记材料贴图阻挡（如 `aluminum_ingot.png`、`raw_tungsten.png`）；未跨模块改动材料登记，也未合入 main。
- 源码保存在功能分支 `codex/ore-fish-soup`，没有覆盖根目录已有未提交工作，也没有部署到玩家客户端或服务端。
