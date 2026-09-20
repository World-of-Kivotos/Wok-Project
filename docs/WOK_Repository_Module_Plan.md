# WOK 综合玩法 MOD 仓库模块化整理方案

## 1. 整理目标

本仓库定位为 WOK 服务器的综合玩法 MOD。整理目标不是立刻拆成多个互不兼容的 JAR，而是先把现有单体仓库收敛为边界清楚、可独立开发、可独立测试、可按模块提交的“模块化单体”。

第一阶段继续保留现有 Forge 入口、`modId`、资源命名空间、注册名、网络通道、存档键和单 JAR 交付方式。待模块间只通过稳定接口通信后，再决定哪些模块适合独立成可选 JAR。

## 2. 永久命名与范围边界

- `WOK` 是主项目/本体产品线，本方案只整理 WOK 本体综合玩法。
- `WOK步战` 是附加子模式，不等同于 WOK 本体，不纳入本次根仓库模块迁移。
- 当前服务于 WOK 本体的护甲实现统一称为 `WOK-本体护甲`。它归属 WOK-铸甲师模块的业务边界，但必须保留现有仓库身份、`modId`、注册名和存档兼容性。
- `WOK步战附属-独立护甲`、`WOK步战附属-部位血量`、`WOK步战附属-创伤治疗`、`WOK步战核心` 均属于另一产品线，不得迁入 WOK 本体模块，也不得将其新联动回灌到 `WOK-本体护甲`。
- `WOK-卡牌游戏独立MOD` 是 WOK 计划中的独立安装产品，正式 modId 为 `wok_cardgame`，代码不在本仓库内（`module-registry.json` 的 `excludedProducts` 声明排除它）；不得接入 WOK 本体的 `MiningDim`、网络包或资源命名空间。

## 3. 推荐的一级模块

### 3.1 基础模块

| 模块名称 | 模块键建议 | 当前主要代码 | 职责 |
| --- | --- | --- | --- |
| WOK-核心模块 | `wok-core` | `core`、`config`、`network`、`registry`、`menu`、`effect`、`entry`、`error`、`mixin`、`testutil` | 生命周期、配置、公共网络、注册辅助、玩家公共数据、错误边界、公共接口与服务装配 |
| WOK-持久化存储模块 | `wok-store` | `store` | 单一世界级 SQLite 库、schema 版本迁移、事务边界与旧库导入；只依赖核心，谁要落盘就依赖它 |
| WOK-全服经验模块 | `wok-experience` | `progression` | 全服统一经验轨道、来源 ID、发放路由和持久化适配契约；不直接持有具体玩法数据 |
| WOK-职业框架模块 | `wok-job-core` | `job` 根目录中的框架类 | `JobId`、职业轨道处理器、等级曲线、每日衰减、职业命令、职业同步和旧存档适配 |
| WOK-矿区副本模块 | `wok-mining` | `worldgen`、`instance`、`chunk`、`reset`、`spawn`、`ore`、`trap`、`pressure`、`rules`、`entrance`、`persistence`、`command` | 矿区维度、实例分配、生成、矿物、陷阱、压力刷怪、重置、入离场 |
| WOK-经济模块 | `wok-economy` | `economy` | 双货币、收支闸门、审计与反通胀接口，不持有具体交易 UI |
| WOK-战斗框架模块 | `wok-combat-core` | `combat` | 玩家受击结算与各职业共享的命名减伤源注册，不持有具体职业或精英词条 |
| WOK-WebUI 模块 | `wok-webui` | `webui`、`client/webui` | 服务端动作派发、客户端 MCEF 外壳、页面路由；不得持有经济或职业业务规则 |

### 3.2 职业玩法模块

| 模块名称 | 模块键建议 | 当前主要代码 | 子功能 |
| --- | --- | --- | --- |
| WOK-矿工模块 | `wok-job-miner` | `job/miner` | 挖矿经验、速挖、连锁挖矿、矿物探测、矿脉生存能力 |
| WOK-农夫模块 | `wok-job-farmer` | `job/farmer` | 分档耕地、职业作物、产量、收购闸门、农夫乐事软联动 |
| WOK-铸甲师模块 | `wok-job-armorer` | `job/engineer` | 纳米护甲板、生产台、校准、修复、护盾、`WOK-本体护甲` |
| WOK-厨师模块 | `wok-job-chef` | `job/chef` | 调味台、火候玩法、菜肴品质、职业增益 |
| WOK-渔夫模块 | `wok-job-fisher` | `job/fisher` | 钓鱼图鉴、矿石鱼、矿石鱼羹效果、原版与 Tide 钓鱼接缝 |
| WOK-酿酒师模块 | `wok-job-brewer` | `job/brewer` | 酿酒台、酒窖、年份、品质、饮用效果、永久增益 |
| WOK-塔罗师模块 | `wok-job-tarot` | `job/tarot`、`data/miningdim/tarot` | 塔罗牌、卡包、品质、合成、牌效、易伤仲裁接入 |
| WOK-军火商模块 | `wok-job-munitions` | `job/munitions` | 弹药产线、推进剂、军械台、枪械配件冲压、枪匠组件、TaCZ 软联动 |
| WOK-特勤干员模块 | `wok-job-agent` | `job/agent` | 精英探测、封印、悬赏、奖励增幅、Champions 软联动 |

军火商与枪匠当前共享生产台、材料、图纸和 TaCZ 接入，第一阶段不拆成两个物理模块；先在 `wok-job-munitions` 内划分 `production` 与 `gunsmith` 两个内部子域。若后续枪匠拥有独立等级、独立经济循环和独立注册入口，再升级为 `WOK-枪匠模块`。

铸甲师源码当前仍使用 `engineer` 包名。第一阶段只统一业务称呼为“WOK-铸甲师模块”，不立即批量重命名 Java 包，以免把业务整理和大范围兼容变更混进同一提交。

渔夫列在职业模块下只是业务归类：`JobId` 枚举至今仍是八个常量、没有 `FISHER`，所以它没有职业等级、没有经验轨道、也没有职业身份门，当前只有图鉴、矿石鱼和鱼羹三条内容线。要不要补上职业身份是独立议题，本方案只按现状登记它的边界。

### 3.3 独立玩法与支撑模块

| 模块名称 | 模块键建议 | 当前主要代码 | 职责 |
| --- | --- | --- | --- |
| WOK-精英怪模块 | `wok-champion` | `champion` | 精英词条、血池、贡献池、Champions 软联动 |
| WOK-市场模块 | `wok-market` | `market` | 跳蚤市场、SQLite 托管挂单、成交结算与 WebUI 动作 |
| WOK-电力模块 | `wok-power` | `power` | 多燃料发电、储电、12 级线缆与导体材料、提纯/空分/低温机器、电力矿物与橡胶链、Flux Networks 软联动 |
| WOK-任务模块 | `wok-quest` | `quest` | 四类来源（DAILY/WEEKLY/SPECIAL/HIDDEN）共用的任务池、任务板、任务链、目标判据与信用点/物品发奖 |
| WOK-附魔模块 | `wok-enchant` | `enchant` | 金钱修补附魔与按耐久点扣费的修复定价 |
| WOK-婚姻社交模块 | `wok-marriage` | `marriage` | 求婚、典礼、关系数据、共享功能、社交便利 |
| WOK-开箱模块 | `wok-case-opening` | `caseopening` | 箱池、钥匙、抽取、资产归属、Saga、TaCZ 外观授权 |
| WOK-实体堆叠模块 | `wok-stacking` | `stacking` | 实体合并、堆叠持久化、性能治理及产出倍率约束 |
| WOK-综合装配模块 | `wok-app` | `MiningDim.java` | 唯一 Forge 入口，只负责按顺序装配全部模块，不持有业务逻辑 |

`WOK-开箱模块` 使用经济和 WebUI 的公开接口，但不属于经济核心。这样可在不影响基础货币与市场的情况下停用或维护开箱玩法。

## 4. 推荐依赖方向

依赖只允许从上层玩法指向下层契约，不允许反向依赖具体实现。

```text
WOK 综合入口
  -> 各职业 / 精英怪 / 婚姻 / 开箱 / 实体堆叠
      -> WOK-全服经验 / WOK-职业框架 / WOK-战斗框架 / WOK-经济 API / WOK-WebUI API / WOK-矿区 API
          -> WOK-核心模块
```

额外约束：

- WOK-核心模块不得 import 任一具体职业。
- 全服经验模块不得 import `JobId` 或具体玩法；职业、赛季、账户等进度域通过轨道处理器主动注册。
- 任一玩法发放经验必须携带稳定的轨道 ID 和来源 ID，并通过 `IExperienceService` 进入统一路由。
- 具体职业之间不得直接 import 对方实现类。
- WOK-特勤干员只能依赖精英怪模块的公开契约，Champions 必须保持可选依赖。
- WOK-矿工只能通过矿区公开接口读取矿物或难度信息，不能直接操作实例实现。
- WOK-军火商只能通过 TaCZ 兼容层接入外部 API，核心生产逻辑不得依赖 TaCZ 类才能加载。
- WOK-开箱与 WOK-市场只通过经济事务接口扣款、发放和审计。
- WOK-WebUI 只负责传输与展示，不直接结算货币、经验、物品或婚姻状态。

## 5. 当前仓库问题清单

### 5.1 代码与功能边界

- 根入口一次装配近四十个 `Subsystem`（当期以 `MiningDim.registerSubsystems()` 的实数为准），已经具备逻辑模块雏形，但所有功能仍共享一个源码集和资源命名空间。
- `job/engineer` 同时承载生产、护盾、护甲、特效和客户端模型，是当前最大的职业模块之一。
- `job/munitions` 同时承载军火生产和枪匠组件，需要先划清内部边界。
- `job/agent` 与 `champion`、`economy` 的耦合较强，应优先改为公开契约依赖。
- 多个顶层包仍直接 import 其他顶层实现包，尚未完全达到 README 所述的“只依赖 core 契约”。

### 5.2 Git 与产物边界

- `dist` 当前有大量生成图片和交付文件被 Git 跟踪，源码、设计源文件、预览图和构建产物没有清晰分层。
- `.gitignore` 尚未覆盖 `artifacts`、`outputs`、`tmp` 三个约定的本地生成物目录，往这三处扔产物会被 `git status` 全量列出，有误提交风险。
- `standalone` 下当前只有 `standalone/kivotos-armorer`（modId `kivotos_armorer`，已跟踪入库）；它归哪条产品线尚未裁定，裁定前不作为 WOK 本体模块参与本次整理。

## 6. 推荐仓库结构

第一阶段保持单 JAR，先建立清晰的源码和资料所有权：

```text
src/main/java/com/miningdim/
  MiningDim.java         WOK-综合装配模块（唯一 Forge 入口）
  core/                 WOK-核心模块
  store/                WOK-持久化存储模块
  progression/          WOK-全服经验模块
  combat/               WOK-战斗框架模块
  mining/               WOK-矿区副本模块的目标聚合域
  economy/              WOK-经济模块
  webui/                WOK-WebUI 模块
  market/               WOK-市场模块
  power/                WOK-电力模块
  quest/                WOK-任务模块
  enchant/              WOK-附魔模块
  job/
    core/               WOK-职业框架模块的目标聚合域
    miner/
    farmer/
    engineer/           暂保留包名，业务名为 WOK-铸甲师模块
    chef/
    fisher/
    brewer/
    tarot/
    munitions/
    agent/
  champion/
  marriage/
  caseopening/
  stacking/

docs/modules/
  README.md             全部模块的总登记表、文档归属规则与配置文件登记表
  module-registry.json  机器可读模块、依赖与例外登记
  INVENTORY.md          全仓代码与测试库存
  DEPENDENCY_DEBT.md    历史反向依赖清偿表
  RESOURCE_OWNERSHIP.md 数据与资源所有权
  <module-short-name>/  单个模块的详细文档；职业模块直接用 chef/、farmer/ 这类短名，不加 jobs/ 一层
                        当前已落地 experience/、farmer/、chef/ 三份，其余随迁移分支逐个补
                        哪些模块必须补子 README，判定规则写在 docs/modules/README.md

tools/assets/<module>/   可复现的资源生成脚本
artifacts/               本地生成物，默认不入 Git
```

这里的 `mining` 和 `job/core` 是目标聚合域，不应在第一批提交中一次性搬完。先建立接口和所有权，再做小批量迁移。

## 7. 分阶段整理顺序

### 阶段 0：冻结身份并建立清单

1. 锁定现有 `modId`、注册名、资源 ID、Capability ID、SavedData 名、网络包编号和配置文件名。
2. 建立“当前路径 -> 归属模块 -> 允许依赖 -> 负责人/分支”的模块登记表。
3. 将 WOK 本体、WOK-本体护甲和 WOK步战附属目录明确标记为不同产品边界。

### 阶段 1：先整理 Git，不移动业务代码

1. 开工前确认工作区干净，没有跨模块的未提交改动残留。
2. 每个模块使用独立分支和独立提交，不再在开箱分支混入酿酒师或其他职业改动。
3. 区分设计源文件、可复现生成脚本、发布成品和临时预览；把 `artifacts`、`outputs`、`tmp` 补进 `.gitignore`，再单独审查已跟踪的 `dist` 文件。
4. 不直接删除历史素材；需保留的源素材迁入对应模块资料目录，纯产物改为可再生成。

### 阶段 2：收敛公共契约

1. 从 `core` 中明确 WOK-核心 API。
2. 以全服经验路由统一所有经验发放，职业框架只注册兼容轨道处理器。
3. 将职业框架公共类聚合为 `job/core` 边界。
4. 为战斗、经济、矿区、精英怪和 WebUI 建立最小公开接口。
5. 消除跨模块对具体实现类的 import，再考虑物理 Gradle 子工程。

### 阶段 3：按低风险模块逐个迁移

推荐顺序：

1. WOK-农夫模块：文件较少、边界相对清晰，适合作为模块模板。
2. WOK-矿工模块：规模较小，但需先补矿区查询接口。
3. WOK-持久化存储模块：只有十个文件，但经济、市场、开箱都压在它的 schema 与事务边界上，必须先于这三者定型。
4. WOK-厨师模块。
5. WOK-渔夫模块：紧跟厨师，两者共用矿石鱼羹状态（D035）；同批还要处理矿工对鱼羹的直读（D034）与核心五个 Mixin 的反向调用（D036）。
6. WOK-附魔模块：只有一条金钱修补链，依赖面最小，可与上述任一批并行。
7. WOK-塔罗师模块。
8. WOK-婚姻社交模块与 WOK-实体堆叠模块。
9. WOK-市场模块与 WOK-WebUI 模块：先固化事务与动作接口。
10. WOK-任务模块：紧随市场与 WebUI，奖励一律走共享 credit faucet 键，不另起发奖出口。
11. WOK-电力模块：全库最大的非职业模块，单独成批；必须排在军火商之前，并先清偿矿区对电力的反向引用（D028）。
12. WOK-军火商模块。
13. WOK-精英怪模块与 WOK-特勤干员模块。
14. WOK-铸甲师模块：资源和模型最多，并涉及 `WOK-本体护甲` 兼容，排在末段。
15. WOK-酿酒师模块：依赖农夫产物与塔罗师的最大生命帽（D011），等这两条接缝清偿后再整理。

每次迁移只处理一个模块，并完成编译、GameTest、资源完整性和旧存档/旧物品 ID 兼容检查。

### 阶段 4：决定是否拆成多 JAR

只有同时满足以下条件才拆物理 JAR：

- 模块没有直接引用其他模块实现类。
- 注册项、数据文件和客户端资源归属已经可枚举。
- 模块可在依赖缺失时明确拒绝加载或安全降级。
- 旧世界、玩家 NBT、物品和方块注册 ID 有迁移或原样保留方案。
- 单模块测试和整包集成测试均通过。

在此之前，继续采用一个仓库、一个 Forge MOD、一个 JAR、内部强模块化，兼容风险最低。

## 8. 第一批实际整理范围

第一批只做以下内容：

- 确认本模块清单与命名。
- 新增机器可读或易维护的模块登记表。
- 整理 `.gitignore` 和产物目录规则，但不擅自删除已跟踪文件。
- 以 WOK-农夫模块为样板，审计它对核心、经济、菜单和外部 MOD 的依赖。
- 输出农夫模块的迁移提交清单和测试清单。

第一批明确不做：

- 不改现有 `modId=miningdim`。
- 不改任何注册 ID、资源 namespace 或存档键。
- 不迁移或重命名 `WOK-本体护甲`。
- 不把 `WOK步战附属-独立护甲` 合入本体。
- 不在同一提交里同时整理两个职业模块。
