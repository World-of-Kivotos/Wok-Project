# 文档索引

WOK 主 mod（`modid=miningdim`）的全部设计文档入口。截至 2026-09-26，`docs/` 下现役 52 份、归档 12 份。

本索引是 2026-09-20 全量文档审查的产物。审查前 `docs/` 没有任何索引，78 份文档里 34 份没有被任何其它
文档引用过，冻结的审查快照与现役规格平铺在同一层目录——这些是这次建索引要解决的问题。

## 怎么用

**先看文档类型，再看内容。** 类型决定了你该不该照着它做事：

| 类型 | 含义 | 命名 |
|---|---|---|
| 现役规格 | 该照着做的。与代码不符时，以代码为准并回写本文档 | `*_DesignSpec.md` |
| 专题说明 | 某个子系统的展开，从属于某份主规格 | 描述性名字 |
| 资产清单 | 美术/资源的交付进度表 | `*_AssetChecklist.md` |
| 手册 | 面向玩家与服主，不是开发文档 | `*_Guide.md` |
| 推迟中 | 已写好但明确未实现，抬头标 DEFERRED。**不是现状** | 抬头自带标记 |
| 归档件 | 历史记录，**永不回写**，不要当依据 | 在 [`archive/`](archive/README.md) 下 |

**状态标记**：`DECIDED` 已落地 / `PENDING` 待拍板 / `DEFERRED` 已确认未实现 / `SUPERSEDED` 已被取代。

**新增文档的规矩**：放进下面某个分类并在本索引登记；新增任何经济 faucet 或 sink，
必须先过 [经济收支总表](Economy_BalanceSheet_DesignSpec.md)；新增资源路径要登记进
[模块注册表](modules/module-registry.json)，否则 `gradlew verifyModuleRegistry` 会红。

---

## 一、职业规格

九个职业，每个一份主规格。

| 职业 | 主规格 | 代码包 | 模块键 |
|---|---|---|---|
| 矿工 | [Miner_Job_DesignSpec](Miner_Job_DesignSpec.md) | `job/miner` | `wok-job-miner` |
| 农夫 | [FarmingXP_Mod_DesignSpec](FarmingXP_Mod_DesignSpec.md) | `job/farmer` | `wok-job-farmer` |
| 厨师 | [Chef_Job_Mod_DesignSpec](Chef_Job_Mod_DesignSpec.md) | `job/chef` | `wok-job-chef` |
| 酿酒师 | [Brewer_Job_DesignSpec](Brewer_Job_DesignSpec.md) | `job/brewer` | `wok-job-brewer` |
| 塔罗师 | [TarotReader_Mod_DesignSpec](TarotReader_Mod_DesignSpec.md) | `job/tarot` | `wok-job-tarot` |
| 军火商 | [Munitions_Job_DesignSpec](Munitions_Job_DesignSpec.md) | `job/munitions` | `wok-job-munitions` |
| 特勤干员 | [SpecialAgent_Job_DesignSpec](SpecialAgent_Job_DesignSpec.md) | `job/agent` | `wok-job-agent` |
| 千年工程师 | [MillenniumEngineer_Mod_DesignSpec](MillenniumEngineer_Mod_DesignSpec.md) | `job/engineer` | `wok-job-armorer` |
| 渔夫 | [Fisher_Job_DesignSpec](Fisher_Job_DesignSpec.md) | `job/fisher` | `wok-job-fisher` |

注意三处命名不一致（**待拍板，尚未统一**）：模块注册表把 `com.miningdim.job.engineer` 登记成
`wok-job-armorer`（中文名「铸甲师」），而文档有「千年工程师」与「铸甲师」两份。
三者指的是同一个职业，拆分见各自文档抬头。

职业共用的框架与曲线：[JobFramework_Shared_Foundation_DesignSpec](JobFramework_Shared_Foundation_DesignSpec.md)。
农夫那份的持久化/平台/经济三节已被它取代，等级曲线与耕地数值仍以农夫文档为准（该文档抬头有说明）。

### 职业子系统专题

| 文档 | 从属于 |
|---|---|
| [Armorer_Armor_System_DesignSpec](Armorer_Armor_System_DesignSpec.md) — 护甲、纳米/电浆护盾 | 千年工程师 |
| [Fishing_Journal_Framework](Fishing_Journal_Framework.md) — 渔业图鉴的数据格式与网络协议 | 渔夫 |
| [Ore_Fish_And_Soup](Ore_Fish_And_Soup.md) — 矿石鱼与鱼羹的数值与验证记录 | 渔夫 |

---

## 二、系统规格

| 系统 | 文档 | 代码包 |
|---|---|---|
| 矿区副本 | [MiningDimension_Mod_DesignSpec](MiningDimension_Mod_DesignSpec.md)（全库最大，24 章） | `instance` `entry` `chunk` `pressure` `trap` `worldgen` `rules` `reset` |
| 经济 · 顶层总表 | [Economy_BalanceSheet_DesignSpec](Economy_BalanceSheet_DesignSpec.md) — **新增任何 faucet/sink 必须先过这里** | `economy` |
| 经济 · 全局设计 | [服务器经济系统设计文档](服务器经济系统设计文档.md) | `economy` `market` |
| 经济 · 矿价台账 | [Ore_Pricing_Ledger_DesignSpec](Ore_Pricing_Ledger_DesignSpec.md) | `economy` `ore` |
| 精英怪 · 词条系统 | [ChampionStarAffix_System_DesignSpec](ChampionStarAffix_System_DesignSpec.md) | `champion` |
| 电力 · 线缆 | [Power_Cable_DesignSpec](Power_Cable_DesignSpec.md) | `power/cable` |
| 电力 · 发电机 | [Power_Generator_DesignSpec](Power_Generator_DesignSpec.md) | `power` |
| 电力 · 经济再平衡 | [Power_Economy_Rebalance_DesignSpec](Power_Economy_Rebalance_DesignSpec.md) | `power` `economy` |
| WebUI · 架构 | [WebUI_Architecture_DesignSpec](WebUI_Architecture_DesignSpec.md) | `webui` `menu` `network` |
| WebUI · 前端接线清单 | [WebUI_Frontend_Wiring_Checklist](WebUI_Frontend_Wiring_Checklist.md) | `webui/src` |
| WebUI · 接线范围裁定 | [WebUI_Wiring_Execution_Scope](WebUI_Wiring_Execution_Scope.md) | 同上 |
| 婚姻社交 | [Marriage_System_DesignSpec](Marriage_System_DesignSpec.md) | `marriage` |
| 成就 · 原版进度 + 成就点商店 | [Achievement_System_DesignSpec](Achievement_System_DesignSpec.md)（2026-09-26 起草，未实现） | `achievement` |
| 称号 | [Title_System_DesignSpec](Title_System_DesignSpec.md)（2026-09-26 起草，未实现） | `title` `client/title` |
| 实体堆叠 | [Minecraft实体堆叠_需求规格说明书](Minecraft实体堆叠_需求规格说明书.md) | `stacking` |
| 全局脑图 | [design_mindmap](design_mindmap.md) — 各系统的一页纸概览，细节以各自主规格为准 | 全部 |

**任务系统（`quest` 包）至今没有主设计规格**，只有已归档的
[TaskSpec_Quest_WebUI_Panel](archive/delivered/TaskSpec_Quest_WebUI_Panel.md) 覆盖了 WebUI 面板接线这一面。
见下面的「已知缺口」。

---

## 三、枪匠组件专题

军火商的枪械组件子系统，15 份，全部从属于 [Munitions_Job_DesignSpec](Munitions_Job_DesignSpec.md)。

**规则与流程**

- [Gunsmith_Component_Creation_Rules](Gunsmith_Component_Creation_Rules.md) — 新增组件的制作规范
- [Gunsmith_Component_Naming_Plan](Gunsmith_Component_Naming_Plan.md) — 命名方案
- [Gunsmith_Component_Hot_Reload_Rules](Gunsmith_Component_Hot_Reload_Rules.md) — datapack 热重载规则与数值表
- [Gunsmith_Component_Balance_Roadmap](Gunsmith_Component_Balance_Roadmap.md) — 平衡路线图
- [Gunsmith_Blueprint_Fire_Mode_Policy](Gunsmith_Blueprint_Fire_Mode_Policy.md) — 图纸与射击模式策略

**九个平台的组件清单**

[AR](Gunsmith_AR_Components.md) · [AK](Gunsmith_AK_Components.md) ·
[手枪](Gunsmith_Handgun_Components.md) · [冲锋枪](Gunsmith_SMG_Components.md) ·
[霰弹枪](Gunsmith_Shotgun_Components.md) · [精确射手](Gunsmith_Marksman_Components.md) ·
[狙击枪](Gunsmith_Sniper_Components.md) · [机枪](Gunsmith_Machine_Gun_Components.md) ·
[无托](Gunsmith_Bullpup_Components.md)

**单型号专题**

- [Gunsmith_Gehenna_High_Speed_Gas](Gunsmith_Gehenna_High_Speed_Gas.md) — 格赫娜高速导气组件

---

## 四、资产清单与来源登记

- [Power_Cable_AssetChecklist](Power_Cable_AssetChecklist.md) — 12 档线缆与七矿的资产交付表
- [Power_Preheat_Generator_AssetChecklist](Power_Preheat_Generator_AssetChecklist.md) — 预热发电机资产表
- [CASE_ASSET_PROVENANCE](CASE_ASSET_PROVENANCE.md) — 开箱资产来源登记（**法务用，新增随 JAR 分发的第三方派生资产必须登记**）

第三方依赖的许可证登记在仓库根的 [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)。

---

## 五、手册（面向玩家与服主）

- [Champion_Effects_Guide](Champion_Effects_Guide.md) — 精英怪 35 个词条的效果说明。
  全仓唯一一份非开发受众的文档；数值一律以代码为准，与
  [ChampionStarAffix_System_DesignSpec](ChampionStarAffix_System_DesignSpec.md) 互为表里。

---

## 六、推迟中（DEFERRED，不是现状）

这三份写好了但明确未实现。**不要把它们当成已落地的描述**，也不要因为「没实现」就删——
它们是恢复时的起点，其中的实测教训比重写一遍更值钱。

- [PixelUI_DesignSystem_DesignSpec](PixelUI_DesignSystem_DesignSpec.md) — 像素风 UI 设计系统。
  2026-08-13 起推迟（**未作废**），游戏内 UI 已改中性灰阶圆角风；
  组件实现封存在 [`webui/_pixel-archive/`](../webui/_pixel-archive/README.md)
- [WebUI_ServerPush_DesignSpec](WebUI_ServerPush_DesignSpec.md) — 服务端推送 W12
- [WebUI_ChineseIME_DesignSpec](WebUI_ChineseIME_DesignSpec.md) — 游戏内中文输入法

---

## 七、模块治理

WOK 全仓的模块划分、资源归属与依赖债，入口见 [`modules/README.md`](modules/README.md)。

- [modules/module-registry.json](modules/module-registry.json) — **26 个模块的登记表，`gradlew verifyModuleRegistry` 的校验输入**
- [modules/INVENTORY.md](modules/INVENTORY.md) — 模块清单
- [modules/RESOURCE_OWNERSHIP.md](modules/RESOURCE_OWNERSHIP.md) — 资源路径归属
- [modules/DEPENDENCY_DEBT.md](modules/DEPENDENCY_DEBT.md) — 跨模块反向引用的债务登记
- [WOK_Repository_Module_Plan](WOK_Repository_Module_Plan.md) — 拆仓总方案
- 模块子说明：[chef](modules/chef/README.md) · [experience](modules/experience/README.md) · [farmer](modules/farmer/README.md)

分支与合并纪律见仓库根的 [`分支协作.md`](../分支协作.md)。

---

## 八、归档区

已完成使命或已被推翻的文档在 [`archive/`](archive/README.md)：7 份已交付的实施规格 +
5 份冻结的审查快照。**归档件一律不再回写，不要当现状依据。**

归档一份文档之前必须先把它里面仍未闭合的条目搬进现役文档——
搬运记录见 [`archive/README.md`](archive/README.md) 末尾那张表。

---

## 九、已知缺口

2026-09-20 审查确认、尚未补上的：

| 缺口 | 影响 |
|---|---|
| 任务系统（`quest` 包）无主设计规格 | 一个已上线且带独立 faucet 的系统，只有归档的面板接线单 |
| `/economy grant`、`/mchampion summon` 两条 OP 命令零文档 | 前者直接发信用点与青辉石且绕过全部 faucet 计数，运维无章可循 |
| 数据库运维口径未进部署文档 | 存档与 `miningdim.db` 须同生共死、备份须含 `-wal`/`-shm`、真服须复核 `PRAGMA synchronous` |
| 26 个模块只有 3 个有子 README | 规则见 [modules/README.md](modules/README.md) |
| 工程师 / 铸甲师 三方命名未统一 | 见第一节末尾 |

未闭合的**经济与玩法**风险另在
[Economy_BalanceSheet_DesignSpec](Economy_BalanceSheet_DesignSpec.md) 的「未闭合风险」一节与
[`archive/README.md`](archive/README.md) 的搬运表里跟踪。
