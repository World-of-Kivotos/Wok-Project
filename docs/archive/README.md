# 文档归档区

这里放**已经完成使命或已被推翻的文档**。它们是历史记录，不是实现依据。

建立归档区的理由：2026-09-20 的全量文档审查发现，冻结的审查快照与现役设计规格平铺在同一层目录里，
文件名与位置都不带类型信号，结果是下游文档、甚至源码 javadoc 都把几个月前的冻结结论当成现状引用，
据此做门禁决策。归档区就是那个缺失的类型信号。

## 规矩

1. 归档件**一律不再回写**。发现它内容过时不必修正——过时是归档件的正常状态。
2. 每份归档件的 H1 标题下面必须有统一的失效抬头，写明：冻结日期、冻结基线 commit、归档日期、
   **现行真源指向哪里**，以及从它里面**搬出去的未闭合项**。
3. 归档一份文档之前，必须先把它里面**仍未闭合**的条目搬进对应的现役文档。
   归档不等于结案——把未闭合的风险随快照一起冻掉，是这次审查发现的原始病因之一。
4. 现役文档引用归档件时，要写清「这是 X 年 X 月的冻结结论」，不要当现状引用。

## 目录

### `delivered/` — 已交付，任务完成

| 文档 | 冻结日期 | 现行真源 |
|---|---|---|
| [经济数据统一入 SQLite 实施计划](delivered/Economy_SQLite_Migration_Plan.md) | 2026-08-12 | 代码 `com.miningdim.store` / `com.miningdim.persistence` |
| [军械工作台分支审查报告](delivered/Munitions_Workbench_Branch_Review.md) | 2026-07-12 | [军火商设计规格](../Munitions_Job_DesignSpec.md) |
| [TaskSpec 索引 (2026-08-17 批)](delivered/TaskSpec_INDEX.md) | 2026-08-17 | 下面四份各自的实现 |
| [TaskSpec: 矿洞三难度死亡规则](delivered/TaskSpec_Mining_DeathRules.md) | 2026-08-17 | `com.miningdim.rules.MiningDeathRules` |
| [TaskSpec: 矿洞进入收费](delivered/TaskSpec_Mining_EntryFee.md) | 2026-08-17 | `MiningServerConfig.ENTRY_FEE_*` + `entry.MiningWebUiActions` |
| [TaskSpec: 矿洞维度禁止设置重生点](delivered/TaskSpec_Mining_NoRespawnPoint.md) | 2026-08-17 | `com.miningdim.rules.RulesSystem` |
| [TaskSpec: 任务系统接入 WebUI 平板面板](delivered/TaskSpec_Quest_WebUI_Panel.md) | 2026-08-17 | `quest.QuestWebUiActions` + `webui/src/pages/QuestsPage.tsx` |

### `reviews/` — 审查快照

| 文档 | 冻结基线 | 规模 | 现行真源 |
|---|---|---|---|
| [全库审计报告 (2026-08)](reviews/Full_Repo_Audit_2026-08.md) | `49d5283` | 112 条发现 | 无单一接替者，逐条以代码为准 |
| [经济系统完整度审计报告](reviews/Economy_Completeness_Audit.md) | 2026-08-05 | 8 Critical | [经济收支总表](../Economy_BalanceSheet_DesignSpec.md) |
| [经济体系跨账号洗额度设计评审](reviews/Economy_Laundering_Review.md) | 2026-06-20 | V1-V7 漏洞表 | [经济收支总表](../Economy_BalanceSheet_DesignSpec.md) 的「未闭合风险」一节 |
| [全量文档审查报告 (2026-09)](reviews/Docs_Audit_2026-09.md) | `bd0c4588` | 380 条发现 | 各文档自身（本批已整改） |

## 从归档件搬进现役文档的未闭合项

这张表是归档规矩第 3 条的落实记录。搬过去之后，**现役文档才是跟踪这些项的地方**，
归档件里的对应段落只作追溯用。

| 未闭合项 | 来源 | 现在跟踪在哪 |
|---|---|---|
| faucet 计数键仍是单账号维度（跨账号洗额度的根因） | 完整度审计、洗钱评审 | [经济收支总表](../Economy_BalanceSheet_DesignSpec.md) 未闭合风险 |
| `/fishing sell` 无职业身份门（V7 红线已触发） | 洗钱评审 | 同上 + [渔夫设计规格](../Fisher_Job_DesignSpec.md) |
| 市场交付写玩家背包仍在数据库事务之外 | SQLite 计划第六节、完整度审计缺口[12] | [经济收支总表](../Economy_BalanceSheet_DesignSpec.md) 未闭合风险 |
| 补扣款失败的业务策略未定（记欠账 / 收回资产 / 永久隔离） | SQLite 计划第六节 | 同上 |
| 旧库自动导入从未在真实存档上演练 | SQLite 计划第六节 | 同上 |
| 数据库运维口径未写进部署文档 | SQLite 计划第六节 | 同上 |
| `/economy grant` 与 `/mchampion summon` 两条 OP 命令零文档 | 完整度审计 | [文档索引](../README.md) 的已知缺口 |
| F017 / F078 特勤悬赏系统整条未实现 | 全库审计 | [特勤干员设计规格](../SpecialAgent_Job_DesignSpec.md) |
| F068 婚姻「预约场地」未实现 | 全库审计 | [婚姻系统设计规格](../Marriage_System_DesignSpec.md) |
| F047 护盾全伤害免疫窗定性未结案 | 全库审计 | [铸甲师护甲系统设计规格](../Armorer_Armor_System_DesignSpec.md) |
