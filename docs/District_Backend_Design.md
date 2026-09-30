# 自管区后端 设计规格文档（阶段 1–3，附阶段 4 核对清单）

## 文档元信息

- 用途：自管区模块（`wok-district`）阶段 1 后端的实现依据，覆盖存储、领域规则、权限判定、Flan 网关接缝、平板动作 D1–D26、OP 引导命令和 GameTest。阶段 2（真 Flan 对接）的设计在第二十章，阶段 3（机械动力禁令、地块边界守卫、聊天通知，以及收尾时补做的个人圈地限制 22.20，实现记录 22.21）的设计在第二十二章，阶段 4（测试服实机核对）的清单在第二十三章，2026-09-30 本地冒烟测试的结论在 23.11。与代码不符时以代码为准，并回写本文。
- 目标平台：Minecraft 1.20.1 + Forge 47.x + Java 17。持久化走统一库 `miningdim.db`（sqlite-jdbc 3.45.3）。
- 基线：分支 `feat/wok-district`，HEAD `457bac9a`，即 `origin/feat/achievement-p2`（`ebb1f418`）加上自管区界面预览的合并。
- 需求来源（阶段 1 设计时的需求全集，73de0240 接线之前）：
  - `webui/src/mock/planned.ts`：当时 D1–D26 的入参、回执形状与规则注释。
  - `webui/src/mock/district-handlers.ts`：当时的参考实现，检查顺序、拒绝文案、副作用和记录写法照它设计。
  - `webui/src/mock/district-seed.ts`：常量、权限目录（36 项）、固定规则、地块记录的固定缘由。
  - `webui/src/mock/district-geometry.ts`：划地块的范围校验。
  - [District_Flan_Integration_Notes](District_Flan_Integration_Notes.md)：Flan 1.11.16 的真实行为。它与契约冲突时以它为准。
- 接线之后的权威来源（73de0240 起）：
  - 回执与入参的形状以 `webui/src/lib/types.ts` 的自管区一节与 `DistrictJson` 为准；`planned.ts` 里已经没有这 26 条。
  - 规则、检查顺序、拒绝文案与记录写法以 `com.miningdim.district` 的 Java 为准。
  - `district-handlers.ts`、`district-seed.ts`、`district-geometry.ts` 只是开发构建（无宿主）的假后端；与 Java 不一致时改它们，不改 Java。
  - 下文沿用"参考实现"的地方，说的是设计当时的出处，不是现在的裁决依据。
- 状态图例：
  - DECIDED：已定。来源是服主拍板，或本文按仓库惯例定下。
  - PENDING：待服主确认，先按本文默认值实现。汇总在第十九章。
  - TODO：后续阶段，见第二十一章。阶段 2 已在第二十章定下，阶段 3 已在第二十二章定下。
- 编号口径：本文的 "D3" 之类沿用界面预览期 planned.ts 的行号（planned.ts 里现在已没有这些行）；本文的 D<n> 就是接线清单 K 组的 K<n>，顺序与 `DistrictActionNames` 的常量相同。Java 代码、注释和断言文案里一律写 action 名，不写裸编号，原因见 17.4。

---

## 一、范围与分期 (DECIDED)

### 1.1 服主已拍板、后端必须照做的规则

1. **三层身份**：
   - 管理员 = OP。管理员有全部权限，能进所有地块，亲手放置机械动力方块不受禁令约束（假玩家不算 OP）；OP 的机器同样改不了自管区里的方块（22.5、22.6）。
   - 区务长由 OP 任命，本身不是 OP。区务长能做的事：
     - 加、移住户。移出时必须选原因种类。选"违反区规"时，TA 在本区别人地块的朋友身份自动暂停；其余原因保留朋友身份。
     - 开关本区公共区域的住户列、外人列。区域规则只有 OP 能改。
     - 在区内划、调、删**空置**地块。
   - 区务长不能做的事：碰有户主的地块、定价、开放购买、增删自管区或改它的大小。
2. **住户 = 学院成员**：
   - 学院名单手工维护，"加住户"就是"入学院"。
   - 一人只属于一个学院，已解绑学院的名单也算。
3. **地块**是 Flan 子领地：
   - 一块地一个户主，一个住户最多一块地。
   - 户主自己管朋友（先按 8 人）和"朋友 / 其他住户 / 外人"三列显式开关，这三列从不继承。
   - OP 可以代改，每次都记为"管理员代改"。
4. **买地**：
   - 直接购买，先到先得。
   - 价格 = 面积 × 本区单价。单价只有 OP 能设。
   - 开放购买由 OP 按区开关，默认关。
   - 买家必须是本区住户、还没有地块、余额够。
   - 钱暂时直接销毁，付款去向还在定。
5. **户主被移出**：
   - 地块原地冻结 7 天，东西不动，除 OP 外谁都不能进。
   - 期满收回：地块变空置，朋友清空，三列回到默认，旧记录归档，只有管理员能看。
   - 期间 OP 可以解冻（前提是原户主已重新成为住户），也可以立即收回。
6. **删除自管区只解绑**：学院名单保留，记录归档，Flan 领地不动。
7. **开服六校**：阿拜多斯 `abydos`、千年 `millennium`、格赫娜 `gehenna`、圣三一 `trinity`、百鬼夜行 `hyakkiyako`、狂猎艺术学院 `wildhunt`（简称"狂猎"）。
8. **主城 DU 不设自管区**，由 OP 直接管理。

### 1.2 阶段 1 做什么

| 做 | 不做（后续阶段） |
|---|---|
| SQLite 持久化（V9 迁移） | 真实 Flan 调用与对账（阶段 2） |
| 契约全部规则的领域服务 | 机械动力禁令与外围 8 格拦截（阶段 3，已做：22.4–22.8；个人圈地限制 22.20） |
| D1–D26 服务端动作，身份从服务端状态实时判定 | 聊天通知（阶段 3，已做：22.10–22.12） |
| `FlanGateway` 接口 + 记录型假实现 + 默认的"未启用"实现 | 税收、钱仓（另案） |
| 每个住户、每块地的"领地权限生效状态"，按网关回报记录 | 前端的正式开放（导航仍只在开发构建显示） |
| 从没进过服的玩家：按离线 UUID 记入，状态 pending | 登录门：已在另一分支的派发器入口实现，本模块不重复做 |
| 买地：扣款与过户在同一个数据库事务 | |
| 冻结到期：服务端定时检查，时钟可注入 | |
| `/district` OP 引导命令 | |
| 规则、权限、持久化的 GameTest | |

### 1.3 实现顺序

1. 第一步提交：V9、仓储、领域服务、假网关，以及领域层 GameTest（第四至十三章）。
2. 第二步提交：`DistrictWebUiActions`、`/district` 命令，以及动作级 GameTest（第十四至十六章）。
3. 审查修复之后，前端接线单独做一个提交（第十七章）。

---

## 二、模块边界与注册 (DECIDED)

| 项 | 值 |
|---|---|
| 模块 ID | `wok-district` |
| 名称 | WOK-自管区模块 |
| 分类 | `gameplay` |
| Java 包 | `com.miningdim.district`（含子包） |
| 依赖 | `wok-core`、`wok-store`、`wok-economy`、`wok-webui` |
| 可选集成 | `sqlite-jdbc`、`flan`（阶段 2，20.1）、`create`（阶段 3，只经 `@Pseudo` mixin 与反射接触，22.1） |
| 对外入口 | `com.miningdim.district.DistrictServices` |
| 资源 | 阶段 1–2 无独立资源。语言键写进共享的 `zh_cn.json` / `en_us.json`，前缀 `district.miningdim.`。阶段 3 起有三份 mixin 配置（`miningdim.district.mixins.json`、`miningdim.district.create.mixins.json`，以及个人圈地限制的 `miningdim.district.flan.mixins.json`）与守卫 GameTest 用的空结构 `district_guard.nbt`（22.1、22.17、22.20） |
| 配置 | 阶段 1 不加配置文件，常量写在 `DistrictLimits`，第十九章列出哪些日后改成配置。阶段 2 加 `miningdim-district.toml`，只有功能总开关一项（20.9）；阶段 3 加 `[district.guards]` 与 `[district.createBan]` 两节（22.1），`[district.guards]` 收尾时加 `personalClaims`（22.20） |

**依赖方向硬约束**：本模块不得引用称号、成就、婚姻、市场或任何职业模块。

各依赖的用途：

- `wok-core`：`core.Subsystem`、`core.MiningConstants`、`testutil.*`。
- `wok-store`：`MiningStore`、`StoreTx`、`StoreMeta`、`MiningDb`、`MiningSchema`。
- `wok-economy`：`EconomyServices`、`IEconomyService`、`Currency`。测试里还用 `SqliteEconomyLedger`、`EconomyService`、`AbuseGuard`。
- `wok-webui`：`WebUiServerDispatcher`、`WebUiBusinessException`、`WebUiErrorCodes`、`WebUiPayloads`、`WebUiPermissions`。

这组依赖不会成环：economy 依赖 core、store、webui，webui 只依赖 core。以上由 `gradlew verifyModuleBoundaries` 校验。

`docs/modules/module-registry.json` 新增的条目如下。它必须和第一个 `com.miningdim.district` Java 文件放在同一个提交里，因为 `currentPaths` 要求目录在磁盘上真实存在。

```json
{
  "id": "wok-district",
  "name": "WOK-自管区模块",
  "category": "gameplay",
  "javaPackagePrefixes": ["com.miningdim.district"],
  "currentPaths": ["district"],
  "resourcePaths": [],
  "resourceNamePrefixes": [],
  "dependencies": ["wok-core", "wok-store", "wok-economy", "wok-webui"],
  "optionalIntegrations": ["sqlite-jdbc"],
  "publicEntry": "com.miningdim.district.DistrictServices",
  "scope": "academy self-governed districts: academy rosters (one academy per player), district bindings and archives, public-area permission tables, resident plots (warden-drawn vacant plots, first-come direct purchase with an atomic wallet debit, owner friends and three-column permissions, 7-day freeze and reclaim), district and plot logs, the FlanGateway seam with recording and disabled implementations, SQLite V9 tables, the district.* / plot.* / admin.district.* / admin.plot.* WebUI actions and the /district OP bootstrap commands"
}
```

同一提交里要改的其他地方：

1. `wok-app.dependencies` 追加 `"wok-district"`。原因：`MiningDim.java` 用全限定名引用子系统，这也算引用。
2. `MiningDim.registerSubsystems()`：在 `AchievementSystem`（26d）之后、`WebUiClientSubsystem`（27）之前加一行：

   ```java
   // 26e. 自管区 (学院领地): 学院名单 + 自管区绑定 + 地块 (统一库 MiningSchema V9) + Flan 网关接缝 + 26 条
   //      district.* / plot.* / admin.district.* / admin.plot.* WebUI 动作 + /district 命令。register 期向派发器
   //      登记这 26 条 action, 须排在 WebUiServerSubsystem 之后; 买地运行期经 EconomyServices 门面扣款, 须排在
   //      EconomySystem 之后 (docs/District_Backend_Design.md 第二章)。
   subsystems.add(new com.miningdim.district.DistrictSystem());
   ```

3. `docs/modules/README.md` 模块总表加一行：`| 玩法 | WOK-自管区模块 | wok-district | district |`。阶段 1 没有配置文件，配置文件表不用加行。
4. `docs/modules/INVENTORY.md`：加一行（Java 文件数、`@GameTest` 数、入口 `DistrictSystem`、依赖），带日期脚注，写法照称号模块、成就模块。
5. `MiningStoreGameTests.unifiedSchemaCreatesEveryTable` 的硬编码表名单加入 11 张 district 表（见 4.4）。
6. `docs/modules/RESOURCE_OWNERSHIP.md` 的所有权映射表加一行 WOK-自管区：无独占资源文件，共享语言文件里的 `district.miningdim.*` 键（复核时补上）。

---

## 三、包与类布局 (DECIDED)

```
com.miningdim.district
├── DistrictSystem              Subsystem: 注册动作/命令, 开服绑定/停服复位, 登录登出钩子, 定时收回
├── DistrictServices            定位器 (volatile DistrictContext; register/context/isRegistered/reset), 模块对外入口
├── DistrictLimits              全部常量 (7 天、朋友 8 人、边距 2、单价默认 5、回执预算……)
├── DistrictTestEnv             GameTest 夹具: 内存库 + 假网关 + 可拨时钟 + 门面替换与复原
├── core/                       纯领域模型, 不碰 Minecraft 类以外的服务
│   ├── Academy, AcademyCatalog         六校常量 (id / 简称 / 全称 / 顺序)
│   ├── DistrictRecord, MemberRecord, PlotRecord, FriendRecord, TombstoneRecord
│   ├── DistrictBounds, PlotArea        含两端的整数方块坐标, area() 用 long
│   ├── PlotStatus, ResidentSyncStatus, PlotSyncStatus, FriendSyncStatus
│   ├── RemoveReasonKind                inactive / violation / selfRequest / other
│   ├── DistrictLogAction, DistrictActorRole, PlotLogAction, PlotActorRole   与契约枚举逐值相同
│   ├── DistrictError                   拒绝码枚举 (wire 值取自 WebUiErrorCodes, 第十五章)
│   ├── DistrictRuleException           业务拒绝 (unchecked): code + 中文 message + params
│   ├── PermissionCatalog               36 项目录 (29 member + 7 region) 与固定规则, 文案逐字抄 district-seed.ts
│   ├── PlotGeometry                    D17/D18 的范围校验 (照 district-geometry.ts)
│   └── DistrictTexts                   固定文案: 冻结/收回/解冻/暂停缘由、恢复默认、formatCredit、sideText
├── access/
│   ├── Actor                           谁在操作: uuid (控制台为 null)、name、op
│   ├── GlobalRole, DistrictAccess, PlotRelation
│   ├── AccessResolver                  每次请求从库里现算身份, 不缓存
│   └── Abilities                       D2 abilities 的计算 (第七章表格)
├── store/
│   ├── DistrictRepository              仓储接口 (便于 GameTest 注入故障)
│   ├── SqliteDistrictRepository        唯一实现, 构造参数 Connection, 不关连接
│   └── DistrictStoreException          包 SQLException 的 unchecked 异常
├── service/
│   ├── DistrictContext                 一次绑定的全部协作者: repo / gateway / flanSync / clock / economy / players
│   ├── PlayerDirectory (+ ServerPlayerDirectory)   "进没进过服"、规范名、离线 UUID; 见过的玩家表
│   ├── FirstLoginActivation            pending 住户/朋友首次登录补写
│   ├── DistrictQueryService            D1 D2 D8 D11 D12 D26 的只读快照 (不写库)
│   ├── ResidentService                 addResident / removeResident / retrySync
│   ├── DistrictAdminService            setWarden / unbind / setPlotPricing / setPurchaseOpen / 建区与改范围 (命令)
│   ├── DistrictPermissionService       setPermission / resetPermissions
│   ├── PlotOwnerService                地块三列与朋友 (setPermission / reset / add / remove / restoreFriend)
│   ├── PlotLayoutService               create / resize / delete
│   ├── PlotMarketService               buy (扣款 + 过户 + 记录同一事务)
│   └── PlotFreezeService               freeze (供 removeResident 调)、unfreeze、reclaimNow、到期收回
├── flan/
│   ├── FlanGateway                     低层接口, 逐项对照对接说明 2.1 (第八章)
│   ├── ClaimHandle, PermValue, FlanResult, ClaimPermissionSnapshot
│   ├── DistrictFlanSync                领域级推送: 算期望状态 -> 调网关 -> 回写生效状态
│   ├── PlotDesiredState                一块地整块重写的期望 (owned / vacant / frozen)
│   ├── FlanGroupNames                  d_<districtId>_resident, p_<plotId>_owner/_friend/_resident
│   ├── RecordingFlanGateway            阶段 1 假网关 (只在 GameTest 服务端上可用): 内存领地模型 + 调用记录 + 故障注入 + 不变式断言
│   └── DisabledFlanGateway             生产默认: 一切写入都回"领地对接尚未启用"
├── web/
│   ├── DistrictWebUiActions            registerAll() 登记 26 条; 每条 handler 是 static final WebUiAction
│   ├── DistrictJson                    回执构造与按身份裁剪 (第 14.2 节的全部规则只在这一处)
│   ├── DistrictPayloads                入参读取: area / 必填范围 / 可空字符串 / 安全 long / 严格 boolean
│   ├── ResponseBudget                  按字符预算装列表, 超出即截断并置 truncated
│   └── DistrictWebTestSupport          平板 GameTest 的共用工具 (经派发器调用、断言拒绝码、在线 mock 玩家与 OP)
└── command/
    └── DistrictCommands                /district 命令树 (第十六章), 与平板共用同一批服务方法
```

GameTest 类放在各自被测代码的包里，类名以 `GameTests` 结尾，清单见第十八章。

阶段 2 新增的 `flan/real` 等见 20.1；阶段 3 新增的 `guard/`（含 `guard/create`）与两个 mixin 包 `mixin/world`、`mixin/create` 见 22.1，`notice/` 随聊天通知一起落地；个人圈地限制的 `guard/PersonalClaimGuard`、第三个 mixin 包 `mixin/flan`、`flan/BufferClaimReport`、`flan/PersonalClaim` 与 `flan/real` 里的 `FlanHookTargets`、`FlanClaimGuard` 见 22.20。

---

## 四、存储：MiningSchema V9 (DECIDED)

### 4.1 表一览

| 表 | 一行是什么 | 主键 / 关键约束 |
|---|---|---|
| `district_academy` | 一个学院 | `academy_id`，`sort_order` 唯一 |
| `district_member` | 一个学院成员（= 住户名单一行） | `id` 自增（入学顺序），`player_uuid` 唯一（**一人一学院**），`name_lower` 唯一 |
| `district` | 一个自管区（学院 ↔ 领地的一次绑定，含已解绑的归档） | `district_id`；部分唯一索引：每个学院最多一个未解绑的自管区 |
| `district_permission` | 公共区域开关表的一格 | (`district_id`, `permission_id`, `audience`) |
| `district_plot` | 一块现存地块 | `plot_id`；(`district_id`, `plot_no`) 唯一；部分唯一索引 `owner_uuid`（**一人一块地**） |
| `district_plot_permission` | 地块三列开关的一格 | (`plot_id`, `permission_id`, `audience`)，随地块级联删除 |
| `district_plot_friend` | 地块的一位朋友 | `id` 自增（存储顺序），(`plot_id`, `player_uuid`) 与 (`plot_id`, `name_lower`) 唯一，随地块级联删除 |
| `district_plot_tombstone` | 一块已删地块的墓碑 | `plot_id` |
| `district_log` | 本区操作记录一条 | `id` 自增，只追加 |
| `district_plot_log` | 地块记录一条（按任期分） | `id` 自增，只追加，删地块后仍保留 |
| `district_seen_player` | 一个进过服的玩家 | `player_uuid` |
| `district_notice` | 一条排着的聊天通知（阶段 3 追加在 V9 末尾，22.11） | `id` 自增（发生顺序），按 (`recipient_uuid`, `id`) 建索引，每人最多 30 条、保留 30 天 |

### 4.2 DDL

原样写进 `MiningSchema` 的 `private static final List<String> V9`，并把 `MIGRATIONS` 改成 `List.of(V1, ..., V8, V9)`。

写法遵守该文件的铁律：
- 不写 `IF NOT EXISTS`；
- 一条语句一个字符串；
- javadoc 写明每张表的键、索引，以及为什么这样设约束。

```sql
CREATE TABLE district_academy (
  academy_id TEXT PRIMARY KEY,
  short_name TEXT NOT NULL,
  full_name TEXT NOT NULL,
  sort_order INTEGER NOT NULL UNIQUE,
  created_at INTEGER NOT NULL)

CREATE TABLE district_member (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  player_uuid TEXT NOT NULL UNIQUE,
  player_name TEXT NOT NULL,
  name_lower TEXT NOT NULL UNIQUE,
  academy_id TEXT NOT NULL REFERENCES district_academy(academy_id),
  joined_at INTEGER NOT NULL,
  added_by_uuid TEXT,
  added_by_name TEXT NOT NULL,
  sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','pending','failed')),
  sync_error TEXT)
CREATE INDEX idx_district_member_academy ON district_member(academy_id, id)

CREATE TABLE district (
  district_id TEXT PRIMARY KEY,
  academy_id TEXT NOT NULL REFERENCES district_academy(academy_id),
  display_name TEXT NOT NULL,
  dimension TEXT NOT NULL,
  min_x INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  rules_json TEXT NOT NULL DEFAULT '[]',
  warden_uuid TEXT,
  warden_name TEXT,
  unit_price INTEGER NOT NULL,
  min_side INTEGER NOT NULL,
  max_side INTEGER NOT NULL,
  purchase_open INTEGER NOT NULL DEFAULT 0,
  next_plot_no INTEGER NOT NULL DEFAULT 1,
  flan_claim_id TEXT,
  needs_reconcile INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  created_by_name TEXT NOT NULL,
  unbound_at INTEGER,
  unbound_by_name TEXT,
  unbound_member_count INTEGER,
  unbound_plot_count INTEGER,
  CHECK (min_x <= max_x AND min_z <= max_z),
  CHECK (unit_price >= 1),
  CHECK (min_side >= 1 AND min_side <= max_side),
  CHECK (purchase_open IN (0,1)),
  CHECK (needs_reconcile IN (0,1)),
  CHECK (next_plot_no >= 1),
  CHECK ((warden_uuid IS NULL) = (warden_name IS NULL)),
  CHECK ((unbound_at IS NULL) = (unbound_by_name IS NULL)),
  CHECK (unbound_at IS NULL OR warden_uuid IS NULL))
CREATE UNIQUE INDEX ux_district_live_academy ON district(academy_id) WHERE unbound_at IS NULL

CREATE TABLE district_permission (
  district_id TEXT NOT NULL REFERENCES district(district_id),
  permission_id TEXT NOT NULL,
  audience TEXT NOT NULL CHECK (audience IN ('resident','outsider','district')),
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  PRIMARY KEY (district_id, permission_id, audience))

CREATE TABLE district_plot (
  plot_id TEXT PRIMARY KEY,
  district_id TEXT NOT NULL REFERENCES district(district_id),
  plot_no INTEGER NOT NULL,
  code TEXT NOT NULL,
  min_x INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  owner_uuid TEXT,
  owner_name TEXT,
  frozen_owner_uuid TEXT,
  frozen_owner_name TEXT,
  frozen_at INTEGER,
  tenure INTEGER NOT NULL DEFAULT 1,
  sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','failed')),
  sync_error TEXT,
  flan_claim_id TEXT,
  created_at INTEGER NOT NULL,
  UNIQUE (district_id, plot_no),
  CHECK (plot_no >= 1),
  CHECK (tenure >= 1),
  CHECK (min_x <= max_x AND min_z <= max_z),
  CHECK ((owner_uuid IS NULL) = (owner_name IS NULL)),
  CHECK ((frozen_owner_uuid IS NULL) = (frozen_owner_name IS NULL)),
  CHECK ((frozen_owner_uuid IS NULL) = (frozen_at IS NULL)),
  CHECK (owner_uuid IS NULL OR frozen_owner_uuid IS NULL))
CREATE UNIQUE INDEX ux_district_plot_owner ON district_plot(owner_uuid) WHERE owner_uuid IS NOT NULL
CREATE INDEX idx_district_plot_frozen_owner ON district_plot(frozen_owner_uuid) WHERE frozen_owner_uuid IS NOT NULL
CREATE INDEX idx_district_plot_frozen_at ON district_plot(frozen_at) WHERE frozen_at IS NOT NULL

CREATE TABLE district_plot_permission (
  plot_id TEXT NOT NULL REFERENCES district_plot(plot_id) ON DELETE CASCADE,
  permission_id TEXT NOT NULL,
  audience TEXT NOT NULL CHECK (audience IN ('friend','resident','outsider')),
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  PRIMARY KEY (plot_id, permission_id, audience))

CREATE TABLE district_plot_friend (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plot_id TEXT NOT NULL REFERENCES district_plot(plot_id) ON DELETE CASCADE,
  player_uuid TEXT NOT NULL,
  player_name TEXT NOT NULL,
  name_lower TEXT NOT NULL,
  added_at INTEGER NOT NULL,
  added_by_name TEXT NOT NULL,
  sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','pending')),
  suspended_at INTEGER,
  UNIQUE (plot_id, player_uuid),
  UNIQUE (plot_id, name_lower))
CREATE INDEX idx_district_plot_friend_player ON district_plot_friend(player_uuid)
CREATE INDEX idx_district_plot_friend_pending ON district_plot_friend(name_lower) WHERE sync_status = 'pending'

CREATE TABLE district_plot_tombstone (
  plot_id TEXT PRIMARY KEY,
  district_id TEXT NOT NULL,
  code TEXT NOT NULL,
  min_x INTEGER NOT NULL,
  min_z INTEGER NOT NULL,
  max_x INTEGER NOT NULL,
  max_z INTEGER NOT NULL,
  deleted_at INTEGER NOT NULL,
  deleted_by_uuid TEXT,
  deleted_by_name TEXT NOT NULL)
CREATE INDEX idx_district_plot_tombstone_district ON district_plot_tombstone(district_id, deleted_at)

CREATE TABLE district_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  district_id TEXT NOT NULL,
  at INTEGER NOT NULL,
  actor_uuid TEXT,
  actor_name TEXT NOT NULL,
  actor_role TEXT NOT NULL CHECK (actor_role IN ('admin','warden','resident','system')),
  action TEXT NOT NULL CHECK (action IN ('add','remove','appoint','revoke','resync','permission',
    'createPlot','resizePlot','deletePlot','buyPlot','freezePlot','unfreezePlot','vacatePlot',
    'suspendFriends','setPlotPricing','setPurchaseOpen')),
  target_name TEXT,
  reason TEXT,
  perm_id TEXT,
  perm_label TEXT,
  perm_audience TEXT CHECK (perm_audience IS NULL OR perm_audience IN ('resident','outsider','district')),
  perm_from INTEGER,
  perm_to INTEGER,
  from_min_x INTEGER, from_min_z INTEGER, from_max_x INTEGER, from_max_z INTEGER,
  to_min_x INTEGER, to_min_z INTEGER, to_max_x INTEGER, to_max_z INTEGER,
  CHECK ((action = 'permission') = (perm_id IS NOT NULL)))
CREATE INDEX idx_district_log_district ON district_log(district_id, at, id)

CREATE TABLE district_plot_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plot_id TEXT NOT NULL,
  district_id TEXT NOT NULL,
  tenure INTEGER NOT NULL,
  at INTEGER NOT NULL,
  actor_uuid TEXT,
  actor_name TEXT NOT NULL,
  actor_role TEXT NOT NULL CHECK (actor_role IN ('owner','admin','warden','system')),
  action TEXT NOT NULL CHECK (action IN ('create','resize','purchase','addFriend','removeFriend',
    'suspendFriend','restoreFriend','permission','freeze','unfreeze','vacate')),
  target_name TEXT,
  reason TEXT,
  perm_id TEXT,
  perm_label TEXT,
  perm_audience TEXT CHECK (perm_audience IS NULL OR perm_audience IN ('friend','resident','outsider')),
  perm_from INTEGER,
  perm_to INTEGER,
  from_min_x INTEGER, from_min_z INTEGER, from_max_x INTEGER, from_max_z INTEGER,
  to_min_x INTEGER, to_min_z INTEGER, to_max_x INTEGER, to_max_z INTEGER,
  on_behalf_of_owner INTEGER NOT NULL DEFAULT 0 CHECK (on_behalf_of_owner IN (0,1)),
  CHECK ((action = 'permission') = (perm_id IS NOT NULL)))
CREATE INDEX idx_district_plot_log_plot ON district_plot_log(plot_id, tenure, at, id)

CREATE TABLE district_seen_player (
  player_uuid TEXT PRIMARY KEY,
  player_name TEXT NOT NULL,
  name_lower TEXT NOT NULL,
  first_seen_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  source TEXT NOT NULL CHECK (source IN ('login','backfill')))
CREATE INDEX idx_district_seen_player_name ON district_seen_player(name_lower, last_seen_at)

CREATE TRIGGER trg_district_plot_insert_vacant
BEFORE INSERT ON district_plot
WHEN NEW.owner_uuid IS NOT NULL OR NEW.frozen_owner_uuid IS NOT NULL
BEGIN SELECT RAISE(ABORT, 'district_plot: a new plot must be vacant'); END

CREATE TRIGGER trg_district_plot_owner_is_member
BEFORE UPDATE OF owner_uuid ON district_plot
WHEN NEW.owner_uuid IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM district_member m JOIN district d ON d.academy_id = m.academy_id
  WHERE d.district_id = NEW.district_id AND m.player_uuid = NEW.owner_uuid)
BEGIN SELECT RAISE(ABORT, 'district_plot: owner must be a member of the district academy'); END

CREATE TRIGGER trg_district_warden_is_member
BEFORE UPDATE OF warden_uuid ON district
WHEN NEW.warden_uuid IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM district_member m
  WHERE m.player_uuid = NEW.warden_uuid AND m.academy_id = NEW.academy_id)
BEGIN SELECT RAISE(ABORT, 'district: warden must be a member of the academy'); END

CREATE TRIGGER trg_district_member_delete_guard
BEFORE DELETE ON district_member
WHEN EXISTS (SELECT 1 FROM district_plot p WHERE p.owner_uuid = OLD.player_uuid)
  OR EXISTS (SELECT 1 FROM district d WHERE d.warden_uuid = OLD.player_uuid)
BEGIN SELECT RAISE(ABORT, 'district_member: still owns a plot or is a warden'); END
```

几点约定：

- `CREATE TRIGGER ... BEGIN ...; END` 整段是**一个**字符串。sqlite-jdbc 的 `Statement.execute` 会把整个触发器当作一条语句准备，体内的分号不会把它拆开。
- 布尔值统一存 INTEGER 0/1，时间统一存 epoch 毫秒。
- `name_lower` 由 Java 用 `toLowerCase(Locale.ROOT)` 算好再写入，不用 SQLite 的 `lower()`：后者只处理 ASCII，两边口径会分叉。

### 4.3 约束怎么落

| 规则 | 落在哪 |
|---|---|
| 一人只属于一个学院（含已解绑学院） | `district_member.player_uuid UNIQUE`。另有 `name_lower UNIQUE`，防止大小写不同的同一个名字被加两次（第十章）。 |
| 每个学院最多一个在用的自管区 | 部分唯一索引 `ux_district_live_academy`。解绑后可以重新绑定，归档行保留。 |
| 一个住户最多一块地 | 部分唯一索引 `ux_district_plot_owner`。它对全部地块生效，包括已解绑自管区的地块，见 12.4。 |
| 户主必须是该区学院的成员 | 触发器 `trg_district_plot_owner_is_member`。 |
| 新地块必须空置 | 触发器 `trg_district_plot_insert_vacant`。 |
| 户主与冻结中的原户主互斥 | 表上 CHECK。另有三条 CHECK 保证"名 / UUID / 时间"要么同时有、要么同时无。 |
| 区务长必须是本学院成员 | 触发器 `trg_district_warden_is_member`。它只在 UPDATE 前触发，所以插入这一侧由仓储把关：`insertDistrict` 拒绝带区务长（UUID 或名字）的行（抛 IAE，不写库），新建的自管区一律没有区务长，任命只走 `setWarden`（UPDATE，触发器把关）。不为此改 V9 的迁移。 |
| 已解绑的自管区没有区务长 | CHECK `unbound_at IS NULL OR warden_uuid IS NULL`。解绑时一并清空区务长。 |
| 移出住户前必须先冻结 TA 的地块、先撤销区务长 | 触发器 `trg_district_member_delete_guard`。服务层先按契约报 `RESIDENT_IS_WARDEN`，触发器只是最后一道闸。 |
| 开关只有开或关，不存"不设置" | `enabled IN (0,1)` 且 NOT NULL。每一格都是一行，缺行视为代码 bug（启动期回填见 4.5）。 |
| 记录的动作、身份只取契约枚举值 | 两张记录表上的 CHECK。前端的标签表按这些值索引，出现枚举外的值会显示成空白。 |

**刻意不加外键的地方**：

- `district_log` 与 `district_plot_log` 是只追加的审计表，性质同 V7 的流水。地块删除后，它的记录必须留下来挪进墓碑，因此地块记录不对 `district_plot` 建外键。
- 墓碑表也不对 `district` 建外键。
- 一条记录属于哪一任户主，用 `tenure` 区分，不搬行（见第十三章）。

**只在服务层保证的规则**，由 GameTest 覆盖：

| 规则 | 数值或条件 |
|---|---|
| 朋友上限 | 8 人（含已暂停） |
| 地块几何 | 在区内、离边界不少于 2 格、边长在上下限内、不和其他地块重叠 |
| 原户主必须不是朋友 | 户主不能出现在自己地块的朋友名单里 |
| 单价与尺寸的业务上限 | 单价 ≤ 1,000,000，边长 ≤ 1024 |

业务上限写在代码里、不进 CHECK：数值还没拍板，写进 CHECK 的话，日后每改一次都得开一版迁移。

### 4.4 迁移编号与合并顺序

- `origin/main` 只有 V1–V4。V5–V8 属于称号与成就分支（本分支的基线）。执行 `git log --all -S "List<String> V9"` 查不到任何结果。
- 因此本模块必须在 `feat/achievement-p2` **之后**合入 main。世界首领分支另有一套 V5–V7，若它先合入，本模块和它都要重新编号，然后才能发布。
- `MiningStoreGameTests.unifiedSchemaCreatesEveryTable` 的表名单要加入以下 12 张表：
  - `district_academy`
  - `district_member`
  - `district`
  - `district_permission`
  - `district_plot`
  - `district_plot_permission`
  - `district_plot_friend`
  - `district_plot_tombstone`
  - `district_log`
  - `district_plot_log`
  - `district_seen_player`
  - `district_notice`（阶段 3 追加在 V9 末尾，22.11）
- 同一个测试断言 `user_version == 9`。

### 4.5 开服时的幂等初始化

以下几步由 `DistrictSystem` 执行，都在迁移之外，重复执行不会改动已有数据。

1. **六校**（`ServerStartingEvent`）：
   - `AcademyCatalog.LAUNCH` 按顺序 `INSERT OR IGNORE`，不覆盖已有行。
   - 学院改名或新增学院目前只能改代码，见 TODO 21.8。
   - 六校取值：

     | academy_id | short_name | full_name | sort_order |
     |---|---|---|---|
     | abydos | 阿拜多斯 | 阿拜多斯学院 | 1 |
     | millennium | 千年 | 千年学院 | 2 |
     | gehenna | 格赫娜 | 格赫娜学院 | 3 |
     | trinity | 圣三一 | 圣三一学院 | 4 |
     | hyakkiyako | 百鬼夜行 | 百鬼夜行学院 | 5 |
     | wildhunt | 狂猎 | 狂猎艺术学院 | 6 |

2. **权限格回填**（`ServerStartingEvent`）：
   - 对每个未解绑的自管区，把目录里的每一格按默认值 `INSERT OR IGNORE` 进 `district_permission`。
   - 对每块地，同样回填进 `district_plot_permission`。
   - 用途：目录日后新增一项时，老数据不缺格。
3. **见过的玩家回填**（`ServerStartedEvent`，只做一次）：
   - 以 `StoreMeta` 的键 `district.seenBackfill.v1` 作为只做一次的标记，细节见 10.3。

---

## 五、仓储层 (DECIDED)

`DistrictRepository` 是接口，`SqliteDistrictRepository` 是它唯一的生产实现。写法照 `SqliteTitleRepository`：

- 构造参数是一个 `Connection`，传入 null 时抛 IAE。仓储从不关闭这个连接。
- 每个方法用一个 `try (PreparedStatement ...)`。
- `SQLException` 一律包成 `DistrictStoreException`，并在异常信息里写明是哪个操作失败。
- 读取时，UUID 解析不出的畸形行记 WARN 后跳过，不让整次读取失败。
- 事务：
  - `inTransaction(Supplier<T>)` 直接调用 `StoreTx.call(connection, body)`。
  - `afterCommit(Runnable)` 直接调用 `StoreTx.afterCommit(connection, r)`。
  - `connection()` 只供测试夹具断言"和账本是同一条连接"。

方法按聚合分组（签名示意，参数省略）：

| 聚合 | 方法 |
|---|---|
| 学院 | `ensureAcademies(list)`、`academies()`、`academy(id)` |
| 自管区 | `insertDistrict`（拒绝带区务长的行，见 4.3）、`liveDistricts()`（按学院 `sort_order`）、`liveDistrict(id)`、`anyDistrict(id)`、`liveDistrictOfAcademy(academyId)`、`districtsOfAcademy(academyId)`、`archivedDistricts(limit)`（按 `unbound_at` 降序）、`setWarden`、`setPricing`、`setPurchaseOpen`、`setBounds`、`setRules`、`setDistrictClaimId`、`takeNextPlotNo(districtId)`（同一事务里先读后 +1）、`unbind`、`markNeedsReconcile` |
| 成员 | `insertMember`、`memberByUuid`、`memberByNameLower`、`membersOf(academyId)`（按 `id` 升序，即入学顺序）、`deleteMember`、`setMemberSync(uuid, status, error)`、`rekeyMember(id, newUuid, newName)`、`countMembers(academyId)`、`countMembersBySync(academyId)` |
| 公共区域开关 | `districtCells(districtId)`、`setDistrictCell(...)`、`insertDistrictDefaults(districtId)`、`backfillCells()`（开服回填，4.5） |
| 地块 | `insertPlot`、`plot(plotId)`、`plotsOf(districtId)`（按 `plot_no`）、`plotOwnedBy(uuid)`、`frozenPlotsOf(uuid)`、`assignOwner(plotId, uuid, name)`（条件 UPDATE，要求当前无户主且未冻结；返回是否恰好更新 1 行）、`freeze`、`unfreeze`、`vacate`（任期 +1，户主与冻结一并清空）、`releaseOwner`（只清户主，任期不变，12.4）、`rekeyPlotOwner`（首次登录换键）、`setPlotBounds`、`setPlotSync`、`setPlotClaimId`、`deletePlot`、`expiredFrozenPlots(cutoff, districtId)`（只列未解绑自管区的地块；districtId 为 null 时全服） |
| 地块开关 | `plotCells(plotId)`、`setPlotCell`、`resetPlotCells(plotId)`（删光后按目录默认值重插）、`insertPlotDefaults(plotId)` |
| 朋友 | `friendsOf(plotId)`（按 `id`）、`friendsInDistrict(districtId)`、`friendshipsOf(uuid, nameLower)`（UUID 相同，或小写名相同且还是待生效，10.7）、`pendingFriendsFor(uuid, nameLower)`（只看待生效行）、`insertFriend`、`deleteFriend`、`deleteFriends(plotId)`、`setFriendSuspended(friendId, at)`（暂停与恢复共用，null 为恢复）、`activateFriend(friendId, uuid, name)`（首次登录换键并改为已生效） |
| 墓碑 | `insertTombstone`、`tombstones(districtId, limit)`（按删除时间降序） |
| 记录 | `insertDistrictLog(row)` 返回 id；`districtLog(districtId, limit)`；`insertPlotLog(row)` 返回 id；`plotLog(plotId, minTenure, maxTenure, limit)` |
| 见过的玩家 | `upsertSeenOnLogin`、`touchLastSeen`、`seenByUuid`、`seenByNameLower`（按 `last_seen_at` 降序取第一条）、`insertSeenBackfill` |

记录一律按 `at DESC, id DESC` 排序。同一次动作写出的几行共用同一个 `at`，所以后插入的行排在前面，正好对应契约要求的"草稿顺序"。

---

## 六、领域服务与事务边界 (DECIDED)

### 6.1 DistrictContext 与定位器

`DistrictContext` 是一条不可变记录，一次绑定就定下这次运行的全部协作者：

| 字段 | 用途 |
|---|---|
| `repo` | 仓储 |
| `gateway` | Flan 网关 |
| `flanSync` | 领域级 Flan 推送 |
| `clock`（`LongSupplier`） | 统一取时间 |
| `economy`（`Supplier<IEconomyService>`） | 取经济门面，可能返回 null |
| `players`（`PlayerDirectory`） | 查"进没进过服"和规范名 |
| `logThrottle`（`LogThrottle`） | 每小时至多记一次的日志节流（阶段 2） |
| `zones`（`DistrictZoneIndex`） | 按坐标查区与地块的空间索引，库的投影；`of()` 建它、做第一次重建并挂到仓储的几何监听上（阶段 3，22.3） |
| `guards`（`GuardSettings`） | 守卫的配置，开服读一次（阶段 3，22.1） |
| `onlinePlayers`（`Function<UUID, ServerPlayer>`） | 按 UUID 找在线玩家：生产取服务器的玩家列表，`DistrictTestEnv` 取 GameTest 服务端的；5 参、6 参的 `of()` 按"谁都不在线"（阶段 3，22.11） |
| `notices`（`DistrictNotices`） | 聊天通知的入队、投递、只发在线的广播与保留期；`of()` 建它（阶段 3，22.10–22.12） |

各服务都从 context 取依赖。`DistrictServices` 是 volatile 定位器，接口如下：

| 方法 | 行为 |
|---|---|
| `register(ctx)` | 绑定。传入 null 抛 IAE |
| `context()` | 取当前绑定。没有绑定时抛 ISE |
| `isRegistered()` | 是否已绑定 |
| `reset()` | 解除绑定 |

绑定与解除的时机：

1. 生产环境在 `ServerStartingEvent` 绑定：
   - 连接取 `MiningStore.connection()`；
   - 时钟用 `System::currentTimeMillis`；
   - 经济门面取 `() -> EconomyServices.isRegistered() ? EconomyServices.economyService() : null`。
2. `ServerStoppingEvent` 时 `reset()`。
3. GameTest 用 `DistrictTestEnv` 换上测试用的 context，结束后复原（第十八章）。

**经济门面要在调用时再取**：它在 `ServerStartedEvent` 才绑定，比本模块绑定晚。另外，只有矿山维度存在时它才会绑定。取不到时，买地报 `ECONOMY_OFFLINE`，D11 的 `viewerBalance` 为 null。

### 6.2 服务与动作对应

| 服务 | 动作 |
|---|---|
| `DistrictQueryService` | D1 `district.state`、D2 `district.detail`、D8 `district.permissions`、D11 `district.plots`、D12 `plot.detail`、D26 `admin.district.archive` |
| `ResidentService` | D3 `district.addResident`、D4 `district.removeResident`、D5 `admin.district.retrySync` |
| `DistrictAdminService` | D6 `admin.district.setWarden`、D7 `admin.district.delete`、D21 `admin.district.setPlotPricing`、D22 `admin.district.setPurchaseOpen`；命令：建区、改范围、区规 |
| `DistrictPermissionService` | D9 `district.setPermission`、D10 `district.resetPermissions` |
| `PlotOwnerService` | D13 `plot.setPermission`、D14 `plot.resetPermissions`、D15 `plot.addFriend`、D16 `plot.removeFriend`、D25 `plot.restoreFriend` |
| `PlotLayoutService` | D17 `plot.create`、D18 `plot.resize`、D19 `plot.delete` |
| `PlotMarketService` | D20 `plot.buy` |
| `PlotFreezeService` | D23 `admin.plot.unfreeze`、D24 `admin.plot.reclaimNow`、到期收回；另供 D4 调用冻结 |

服务方法的参数统一是 `Actor` 加上已解析好的业务参数，返回领域快照记录，**不接触 JSON**。

- 平板动作和 `/district` 命令调同一批服务方法。
- 回执的构造和按身份裁剪只在 `web/DistrictJson` 做。

### 6.3 拒绝怎么表达

业务拒绝统一抛 `DistrictRuleException(DistrictError code, String message, Map<String,String> params)`，它是 unchecked 异常。理由：

- 在事务内抛出时，`StoreTx.call` 会整体回滚，写法同 `AchievementPointShop` 的 `PurchaseAborted`。
- 平板层把它一对一转成 `WebUiBusinessException(code.wire(), message, false, params)`。
- 命令层把它转成 `sendFailure(Component.literal(message))`。

几条约定：

- 拒绝文案就是玩家看到的中文句子，逐字取自 `district-handlers.ts`，码表见第十五章。
- 文案和 params 里回显的客户端输入（玩家名等）一律截断到 64 字符，与 `WebUiPayloads.illegalValue` 的上限相同。这些字段有的不做格式校验（如 D16 的名字），原样回显会让回执体积受客户端控制。
- params 的值全部是非 null 字符串。

数据库失败的处理：

- 仓储抛出的 `DistrictStoreException` 或 `MiningStoreException`，由平板层的 `guard(...)` 统一接住：记一条 ERROR（带 action 名与发送者），再抛 `STORE_FAILED`。
- 这不算"吞异常"：失败被转成了契约里的业务码，事务也已经回滚，写法同成就模块。
- 命令层同样接住，回复"数据库写入失败，本次操作没有生效"。

### 6.4 事务模板

每个写动作按固定的五步走：

```
1. sweep   PlotFreezeService.reclaimExpired(districtId)   —— 独立事务; 只处理本区已到期的冻结地块 (第十一章)
2. main    repo.inTransaction(() -> {
               重新读取需要的行 -> 按契约顺序逐条检查 (不满足即抛 DistrictRuleException)
               -> 写业务行 -> 写记录行 (同一 at, 草稿顺序)
               -> 受影响的地块/住户先写"临时失败"状态 (第九章)
               -> repo.afterCommit(() -> flanSync.xxx(...))     // 登记提交后推送
               -> return 快照
           })
3. push    提交后, afterCommit 队列按登记顺序执行: 调网关 -> 拿到每项成败
4. status  push 内部为每个结果各开一个小事务回写 sync_status / sync_error
5. build   服务方法返回; 平板层重新读库构造回执, 回执里的生效状态已是推送后的真值
```

- **Flan 永远在提交之后写**：
  - Flan 没有事务。如果先写 Flan、再提交数据库，一旦提交失败，Flan 里就多出权限，例如名单上没有的人进了居民组。
  - 反过来先提交、后写 Flan，最坏只是"数据库说该有、Flan 里还没有"，这种状态会以 `failed` 显示出来。
  - 撤权类写入（移出住户、冻结）会在同一个 tick 内紧接着提交执行，没有可被利用的时间窗。
- **服务的公开入口方法不许在调用方已开的事务里运行**：入口处调 `requireNoOpenTransaction()`（同 `TitleService`）。否则提交后推送会被推迟到外层提交，回执就只能看到临时状态。服务之间的内部组合不受这条限制，例如 D4 在自己的事务里调用冻结。
- **读动作一律不写库**，也不做到期收回（第十一章）。
- **不设"后台线程"**：动作、命令、tick、登录事件都跑在服务端主线程，和整个仓库的做法一致。先到先得就由这条单线程保证，条件 UPDATE 是第二道保险。
- **不入批量**：写动作一律不进 `WebUiBatchAction.BATCHABLE`，这是防重放的安全边界。读动作虽然不写库、理论上可以进，但阶段 1 不加（17.3）。

### 6.5 买地（D20）：扣款、过户、记录在同一个事务里

```java
BuyResult buy(Actor buyer, ServerPlayer wallet, String districtId, String plotId, PlotArea expectedBounds,
              long expectedPrice) {
    freeze.reclaimExpired(districtId);                                   // 独立事务
    IEconomyService economy = ctx.economy().get();                       // 可能为 null, 到第 11 步才用
    return repo.inTransaction(() -> {
        DistrictRecord d = requireLive(districtId);                      // 1 DISTRICT_NOT_FOUND
        PlotRecord p = requirePlot(d, plotId);                           // 2 PLOT_NOT_FOUND
        MemberRecord m = buyer.op() ? null : repo.memberByUuid(buyer.uuid());
        if (m == null || !m.academyId().equals(d.academyId())) throw NOT_RESIDENT;          // 3 (OP 恒拒)
        if (repo.plotOwnedBy(buyer.uuid()).isPresent()) throw ALREADY_OWNS_PLOT;            // 4 (按全库判, 见下)
        if (hasFrozenPlotIn(d, buyer)) throw HAS_FROZEN_PLOT;                               // 5
        if (!d.purchaseOpen()) throw PURCHASE_CLOSED;                                       // 6
        if (p.frozen()) throw PLOT_FROZEN;                                                  // 7
        if (p.owned()) throw PLOT_OCCUPIED;                                                 // 8
        if (!p.area().equals(expectedBounds)) throw PLOT_CHANGED;                           // 9 (见下)
        long price = Math.multiplyExact(p.area(), d.unitPrice());
        if (expectedPrice != price) throw PRICE_CHANGED;                                    // 10
        if (economy == null) throw ECONOMY_OFFLINE;                                         // 11
        long balance = economy.creditBalance(wallet);
        if (!economy.tryCharge(wallet, Currency.CREDIT, price)) throw INSUFFICIENT_FUNDS;  // 12 (未写任何行)
        if (!repo.assignOwner(p.plotId(), m.uuid(), m.name())) throw PLOT_OCCUPIED;         // 条件 UPDATE, 0 行 -> 连同扣款回滚
        repo.deleteFriends(p.plotId());                                   // 空置地块本来就没有, 防御性清空
        repo.resetPlotCells(p.plotId());                                  // 按目录默认值重插
        repo.setPlotSync(p.plotId(), FAILED, TEXT_SYNC_INTERRUPTED);      // 临时失败, 推送后改真值
        long logId = repo.insertDistrictLog(buyPlot(at, buyer, "resident", p.code(), formatCredit(price)));
        repo.insertPlotLog(purchase(at, buyer, "owner", target = m.name(), reason = formatCredit(price)));
        repo.afterCommit(() -> flanSync.writePlotState(p.plotId()));
        return new BuyResult(p.ref(), price, economy.creditBalance(wallet), logId);
    });
}
```

- **范围与价格一起钉住**（复核后补，2026-09-29）：只钉 `expectedPrice` 不够。买家确认期间，区务长可以把这块空置地块挪到别处，或改成同面积的另一个形状；价格不变，买家就会按同一个价格买到另一片地。所以入参另带 `expectedBounds`（确认框里那一行 `PlotSummary.bounds` 的四个坐标，界面原样送回），与现在的范围逐格比较，不同就报 `PLOT_CHANGED`，params 带现在的范围。它排在 `PRICE_CHANGED` 之前：改了形状的地块价格多半也变了，这时"范围变了"才是买家该先知道的事。`expectedBounds` 是机器字段，缺失、不是对象或坐标不是整数都当场报 `INVALID_REQUEST {field: expectedBounds}`（14.1）。没有另设"地块版本号"：库里没有这一列，加列就要动 V9；范围本身就是买家确认的那个东西。

- **"已有地块"按全库判**：一人一块地的部分唯一索引对全部地块生效（含已解绑自管区的地块，12.4）。第 4 步若只查本区，重新绑定后仍登记为旧区户主的人会一路走到 `assignOwner`、撞上唯一索引，报成 `STORE_FAILED`；按全库判才能给出契约里的 `ALREADY_OWNS_PLOT`。D11 的 `viewerBlock` 同一口径。
- **同一条连接**：生产环境里，`economy.tryCharge` 走 `SqliteEconomyLedger.tryDebit`，用的是 `MiningStore.connection()`，和本模块的仓储是同一条连接。因此它在我们的事务里只执行不提交，外层回滚时扣款一起撤销。GameTest 必须让两者也用同一条连接（`SqliteEconomyLedger.openInMemory()` + `ledger.connection()`），并用故障注入证明原子性（第十八章）。
- **防溢出与 JS 精度**：价格用 `Math.multiplyExact` 计算。按业务上限（边长 ≤ 1024、单价 ≤ 1,000,000），价格最大约 1.05×10¹²，远小于 2⁵³−1，前端的 number 不会失真。
- **钱去哪了**：暂时直接销毁（sink），没有出款方，也没有流水表。审计靠三处：本区记录 `buyPlot`、地块记录 `purchase`，以及审计日志 `miningdim/district` 的一行 INFO（买家、UUID、地块、价格、扣前扣后余额）。付款去向定下来之后另开一张表，见 TODO 21.4。
- **Flan 写失败不退款**：Flan 重写失败时，购买不回滚，也不自动退款（PENDING P6）。这块地会显示 `failed`，下一次改动这块地、或管理员执行 `/district resync` 时再整块重写。

### 6.6 移出住户（D4）的事务

main 事务里依次做这几件事：

1. 查出目标的成员行，以及 TA 在本区拥有的地块。
2. **在改动之前**统计 n：TA 在本区其他地块上"未暂停"的朋友身份有几块。
   - 冻结中的地块也算，TA 自己的地块不算。
3. 如果 TA 有地块，就冻结它：
   - 户主置空，写入 `frozen_owner_*` 和 `frozen_at = at`。
   - 朋友名单和三列开关都不动。
   - 冻结地块改为临时失败状态，等推送。
4. 如果原因是 `violation`，对上面那 n 块地的朋友行把 `suspended_at` 置为 `at`。
5. 删除成员行，即退出学院。触发器保证此时 TA 已不是户主、也不是区务长。
   - 边角情形（重新绑定，P2）：TA 若还登记为某个**已解绑**自管区里一块地的户主，删除前先清空那块地的户主（不写 Flan），并在那块地的当前任期写一行 `vacate`（缘由 `原户主已被移出学院名单，已解绑自管区里的这块地清空户主`，只有管理员能在归档里看到）。否则触发器会拦下删除，整个动作报成 `STORE_FAILED`。
6. 写本区记录，顺序：
   - `remove`：reason 为原因原文。
   - `freezePlot`（有冻结时）：reason 为 `原户主 {name} 被移出本区`。
   - `suspendFriends`（原因是 violation 且 n > 0）：reason 为 `本区 {n} 块地的朋友身份已暂停`。
7. 写地块记录。**移出原因绝不写进地块记录**，因为下一任户主能看到地块记录。
   - 冻结地块写一条 `freeze`：actorRole 为 admin 或 warden，target 为原户主，reason 用固定缘由。
   - 每块被暂停的地写一条 `suspendFriend`：reason 用固定缘由。这一行就是给户主的通知。
8. 登记提交后推送：
   - `removeResidentMembership`：从本区居民组和每块地的居民组里移出。
   - 冻结地块执行 `writePlotState`，重写成"全关"。
   - 原因是 violation 时，每块受影响、且未冻结的地块执行 `writePlotState`。

回执：

| 字段 | 取值 |
|---|---|
| `logEntry` | `remove` 那一条 |
| `frozenPlot` | 冻结的地块，没有则为 null |
| `reclaimAt` | `at + 7 天`，没有冻结则为 null |
| `suspendedFriendOfPlots` | 原因是 violation 时为 n，否则为 0 |
| `stillFriendOfPlots` | 原因是 violation 时为 0，否则为 n |

### 6.7 时间与时钟

- 一次动作只取一次 `at = clock.getAsLong()`。这次动作写的所有记录行，以及 `frozenAt`、`suspendedAt`、`deletedAt`、`unboundAt`，都用这同一个 `at`。
- 冻结到期时间：`reclaimAt = frozenAt + DistrictLimits.FREEZE_MS`，其中 `FREEZE_MS = 7 × 24 × 3_600_000`。
- GameTest 的做法：用 `AtomicLong clock = new AtomicLong(T0)`，把 `clock::get` 传进 context，再用 `clock.set(T0 + 7 * DAY)` 快进。
  - `T0 = 1_790_000_000_000L`，与称号模块的测试同值。

### 6.8 审计日志

`LoggerFactory.getLogger("miningdim/district")`，消息以 `[miningdim] ` 开头。以下情况各记一行 INFO，写明操作人、UUID、目标和关键数值：

- 管理员和区务长的每个写动作；
- 买地；
- 到期收回；
- 所有命令。

Flan 推送失败记 WARN，数据库失败记 ERROR。

---

## 七、权限判定 (DECIDED)

### 7.1 Actor

`Actor(UUID uuid, String name, boolean op)` 表示"谁在操作"。

| 来源 | uuid | name | op |
|---|---|---|---|
| 平板 | `sender.getUUID()` | `sender.getGameProfile().getName()` | `WebUiPermissions.isOp(sender)` |
| 命令：玩家执行 | 玩家的 UUID | 玩家名 | `true`（命令根已经要求 `hasPermission(2)`） |
| 命令：控制台 / RCON | `null` | `控制台` | `true` |
| 到期收回 | `null` | `服务器` | `false`（记录的 actorRole 固定为 `system`） |

平板请求的身份真实性由登录门保证。登录门在派发器入口（另一分支），本模块不重复做。

### 7.2 全局身份（D1 `viewer.role`）

每次请求都按下面的顺序从库里现算，不缓存：

1. OP → `admin`。即使 TA 同时在某个名单上或是区务长，也算 admin。
2. 是某个**未解绑**自管区的区务长（按 `warden_uuid` 匹配）→ `warden`。
3. 在某个**未解绑**自管区所属学院的名单上 → `resident`。
4. 其余 → `outsider`。已解绑学院的成员也算这一档：名单还在，但他们不是住户。

### 7.3 对某个区的身份（`DistrictAccess`）

几乎所有动作按这一层判定，而不是按全局身份。

| 取值 | 条件 |
|---|---|
| `ADMIN` | OP |
| `WARDEN` | 本区的区务长 |
| `RESIDENT` | 在本区学院的名单上 |
| `NONE` | 其余所有人。**别区的区务长、别区的住户都在这一档**，已解绑学院的成员也是 |

### 7.4 对某块地的关系（`PlotRelation`，D12–D16、D25）

按下面的顺序判定：

1. 这块地的现任户主 UUID 等于发送者 → `OWNER`。这一条**最先判**，所以 OP 管自己的地时按户主算，不记"代改"。
2. 否则，发送者是 OP → `ADMIN`。
3. 其余都是 `NONE`。这包括区务长和其他住户。

冻结地块和空置地块没有现任户主，因此只有 ADMIN 能打开。

### 7.5 abilities（D2）

由服务端算好下发，前端不再推一遍。

| 能力 | ADMIN | WARDEN | RESIDENT |
|---|---|---|---|
| build / interact | 真 | 真\* | 真\* |
| manageResidents、viewRoster、managePermissions、viewPlotStatus、managePlots | 真 | 真 | 假 |
| viewPlotList | 真 | 真 | 真 |
| editClaim、deleteDistrict、appointWarden、retrySync、manageRegionRules、inspectPlots、overridePlots、managePlotMarket、manageFrozenPlots | 真 | 假 | 假 |

\* 区务长和住户的 build、interact 只在**本人名单行**的 `sync_status == synced` 时为真。其他情况下本人不在 Flan 居民组里，游戏里按外人那一列算。管理员恒为真。

D2 对 `NONE` 直接拒绝，所以这张表里没有 NONE 一列。

### 7.6 与 Flan 口径的差异

`WebUiPermissions.isOp` 用的是 `PlayerList.isOp`，只看是否在 ops.json 里；Flan 的 OP 绕过则要求等级 ≥ 2。两边对 **1 级 OP** 的判定会不一致：面板认为 TA 是管理员，游戏里却不绕过。

阶段 1 照 WebUI 惯例用 `isOp`，并在守则里写明"员工不进 ops 名单，OP 一律 2 级以上"（PENDING P14）。

---

## 八、Flan 网关 (DECIDED；阶段 2 已实现，增改见 20.1、20.11)

> 本章是阶段 1 的口径。阶段 2 的真网关 `FlanClaimGateway`、按差异写与"先收后放"、对账、权限全表、Disabled 带原因等改动以第二十章为准，逐条的差异列在 20.11。

### 8.1 FlanGateway：低层接口

接口的每个方法对应 [对接说明](District_Flan_Integration_Notes.md) 2.1 节方法表里的一行，签名中不出现任何 Flan 的类。

```java
public interface FlanGateway {
    /** 本实现能否真正写领地 (Disabled 实现为 false; 回执不看它, 只看每次写入的 FlanResult)。 */
    boolean available();

    // ---- storage / findDistrict ----
    Optional<ClaimHandle> findDistrictClaim(String dimension, UUID claimId);
    Optional<ClaimHandle> districtClaimAt(String dimension, int x, int z);

    // ---- createDistrict / resizeDistrict ---- (deleteDistrict 刻意不提供: 解绑不删领地)
    FlanResult<ClaimHandle> createDistrictClaim(String dimension, DistrictBounds bounds, String name);
    FlanResult<Void> resizeDistrictClaim(ClaimHandle district, DistrictBounds bounds);

    // ---- createPlot / findPlot / resizePlot / deletePlot ----
    /** 契约: 返回时子领地已清理干净, 继承来的组和成员快照都已删掉 (对接说明 2.2)。 */
    FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name);
    Optional<ClaimHandle> findPlotClaim(ClaimHandle district, UUID plotClaimId);
    FlanResult<Void> resizePlotClaim(ClaimHandle plot, PlotArea area);
    FlanResult<Void> deletePlotClaim(ClaimHandle plot);

    // ---- createGroup / setGroupPerm / setDefaultPerm / deleteGroup ----
    FlanResult<Void> setGroupPermission(ClaimHandle claim, String group, String flanPermId, PermValue value);
    FlanResult<Void> setDefaultPermission(ClaimHandle claim, String flanPermId, PermValue value);
    FlanResult<Void> deleteGroup(ClaimHandle claim, String group);

    // ---- setMember / removeMember / readMembers ----
    /** group 为 null = 移出该领地的一切组。一个玩家在一块领地里只能在一个组。 */
    FlanResult<Void> setMember(ClaimHandle claim, UUID player, @Nullable String group);
    Map<UUID, String> readMembers(ClaimHandle claim);

    // ---- readPerms / listPerms ----
    ClaimPermissionSnapshot readPermissions(ClaimHandle claim);
    Set<String> knownPermissions();
    boolean isGlobalPermission(String flanPermId);

    // ---- name / flush ----
    FlanResult<Void> setClaimName(ClaimHandle claim, String name);
    void flush(String dimension);
}
```

接口里用到的几个类型：

| 类型 | 定义 |
|---|---|
| `ClaimHandle` | `(String dimension, UUID claimId, @Nullable UUID parentId)` |
| `PermValue` | `TRUE` / `FALSE` / `UNSET`。`UNSET` 对应 Flan 的 mode −1，只有"让地块里的全局类权限跟随自管区"时才用 |
| `FlanResult<T>` | `(boolean ok, @Nullable T value, @Nullable String error)`，其中 `error` 是管理员能看懂的一句中文，例如"区块未加载""领地对接尚未启用" |

### 8.2 DistrictFlanSync：领域级推送

各服务只调用这一层，由它根据数据库算出期望状态，再调用网关。

| 方法 | 做什么 | 调用方 |
|---|---|---|
| `ensureDistrictClaim(district)` / `initializeDistrict(districtId)` | 取本区的父领地；库里还没有领地 id 时新建一块、把 id 写回库并写满本区开关。库里有 id 但 Flan 里找不到时不擅自重建，置对账标记后报失败 | 建区之后、一切需要父领地的推送之前 |
| `resizeDistrictClaim(districtId)` | 把父领地改到库里的范围 | `/district bounds` |
| `syncResidentMembership(districtId, memberUuid)` | 调 `applyResidentMembership`：本区居民组 `d_<districtId>_resident` 加入该玩家；另外，在 TA 既不是户主、也不是有效朋友的每块地里，把 TA 加进 `p_<plotId>_resident`。冻结中的地块跳过，因为冻结期间它的居民组应该为空（8.3）。然后回写住户的生效状态 | D3（已知玩家）、首次登录、resync（D5 直接调 `applyResidentMembership`，自己回写） |
| `removeResidentMembership(districtId, memberUuid)` | 从本区组和每块地的居民组里移出 | D4 |
| `writeDistrictPermission(districtId, item, audience, value)` | 住户列写本区居民组的组权限；外人列写本区默认（全局）权限；全区列写全局权限，遇到 `flanInverted` 时写取反值 | D9、D10 |
| `writePlotState(plotId)` | 整块重写一块地（8.3）：子领地还没建或在 Flan 里找不到时先 `createPlotClaim` 并把 id 写回库，已有时先改到库里的范围；结果写回地块的 `sync_status` | 一切地块写入，含 D17、D18 |
| `deletePlotClaim(districtId, plotClaimId)` | 删除子领地（地块行已删，所以由调用方传入领地 id） | D19 |
| `resyncDistrict(districtId)` | 重推本区全部开关、全部 synced/failed 住户的成员资格，并整块重写全部地块 | `/district resync` 命令 |

结果怎么回写：

- **住户的生效状态**取本区组那一次写入的成败（A9）。
- 某块地的居民组写失败时，那块地标为 `failed`，住户本身的状态不受影响。
- 本区开关写失败没有契约字段可以展示：置 `district.needs_reconcile = 1`，同时记 WARN，等阶段 2 的对账来修（20.4）。
- 删除子领地失败同样处理：记 WARN，并置 `needs_reconcile`。

已解绑的自管区一律跳过：不写任何 Flan，不补写首次登录，也不做到期收回（D7）。

### 8.3 地块整块重写的期望状态

每次写入都是整块重写，不做增量。这样上一次失败的地块，在下一次任何改动时都会自然补齐。

| 状态 | 户主组 `p_<id>_owner` | 朋友组 `p_<id>_friend` | 居民组 `p_<id>_resident` | 地块默认（外人） |
|---|---|---|---|---|
| owned | 户主一人。组权限：全部**非管理类**权限为真 | 有效朋友：未暂停，且不是 pending。组权限取朋友列 | 本区名单里 `synced` 的人，去掉户主和有效朋友。组权限取住户列 | 取外人列 |
| vacant | 空 | 空 | 本区 `synced` 名单全员。组权限取住户列的默认值 | 取外人列的默认值 |
| frozen | 空 | 空 | 空 | 全部为假。库里存着的户主、朋友和三列设置不动 |

几条共同规则：

- **组的优先级**：一个人在一块地里只能进一个组，按"户主 > 朋友 > 居民"取最高的那个。
- **被暂停的朋友**：不进朋友组。TA 是本区住户时进居民组，否则按外人算。
- **管理类权限**（`edit_claim`、`edit_perms`、`edit_potions`）在所有组和默认权限里一律**显式写假**。
- **目录外的 Flan 权限**：按一张固定值表显式写入（阶段 2 由 Flan 的权限表生成全表，见 20.3）。阶段 1 的表（`FlanPermissions.OUTSIDE_CATALOG`）只有 `flan:can_stay` 与 `flan:drop` 两条：有户主和空置的地块对所有组与默认写真，冻结的写假。
- **全局类权限（区域规则）刻意不写在地块上**，一律写 `UNSET`，让它跟随自管区。
- **范围也在整块重写之内**：子领地已存在时，先把它的范围改到库里的值，再写组与成员。所以调范围（D18）的推送就是一次 `writePlotState`，上一次改范围失败的地块也会在下一次写入时补齐。
- **成员资格推送时的"当作已生效"**：加住户与首次登录时，名单行在事务里先写成临时失败；推送里本区居民组写成功之后，把 TA 放进各地块时按已生效算（`PlotDesiredState.compute` 的 `assumeSynced`），否则 TA 会因为自己那条临时状态被漏在地块居民组外。

### 8.4 组名与不变式

组名：

- 本区居民组：`d_<districtId>_resident`。
- 地块三组：`p_<plotId>_owner`、`p_<plotId>_friend`、`p_<plotId>_resident`。
- 每块地的组名都是这块地独有的，因为 Flan 建子领地时会带上父领地的组，同名组的设置并不各自独立。

必须保持的不变式：

1. 自管区父领地上只放本区居民组。因此 `createPlotClaim` 的契约是"返回时已清理干净"：删掉继承来的外层组，并对复制过来的成员逐个 `setMember(uuid, null)`。
2. 任何组、任何默认权限里，`edit_*` 都不许为真。
3. 地块的组权限只写本地块自己的组，绝不写继承来的组。
4. 不调用 `modifyFakePlayerUUID`。

### 8.5 阶段 1 的两个实现

**`RecordingFlanGateway`**：只给 GameTest 用，只在 GameTest 服务端（`GameTestServer`，即 `runGameTestServer`）上造得出来，别处构造直接抛 `IllegalStateException`（口径同登录门的 `forceVerdictForTest` 与 `GameTestConfigWatchGuard`：卡服务端类型，不卡 `forge.enabledGameTestNamespaces`）。

- 内存领地模型：自管区、子领地、组、成员、默认权限，全部放在 Map 里。建子领地时照搬 Flan 的浅拷贝语义：共用内层 Map，并复制成员快照。这样"忘了清理"的 bug 会在测试里暴露出来。
- 调用记录：`calls()` 返回 `List<FlanCall(op, claimId, args)>`，另有 `clear()`。
- 故障注入：
  - `failWhen(Predicate<FlanCall>, String error)`
  - `failNext(String op, String error)`
- **不变式断言**：出现下面任一情况，直接抛 `AssertionError`，让 GameTest 当场失败：
  - 给 `edit_*` 写真；
  - 往父领地写非 `d_` 组；
  - 往地块写 `d_` 组或继承来的组。
  - "同一个玩家在同一块领地里只能在一个组"由成员表（UUID → 组）的结构本身保证，不另设断言。
- 查询：`membersOf(claim)`、`groupsOf(claim)`、`groupPerm(claim, group, perm)`、`defaultPerm(claim, perm)`、`areaOf(claim)`、`exists(claim)`，供断言使用。

**`DisabledFlanGateway`**：生产环境默认使用。

- `available()` 为假。
- 查询返回空。
- 一切写入都返回 `FlanResult.fail("领地对接尚未启用（阶段 1）")`。

**绑定方式**：

- `DistrictSystem.selectGateway` 一律绑定 Disabled。JVM 系统属性 `-Dminingdim.district.flanGateway=recording` 只在 GameTest 服务端上换成记录型实现；专用服务器与单人存档里带了这个属性也忽略，记一条 WARN，仍绑 Disabled。
- 原因：阶段 1 装上测试服时，面板必须如实显示"领地权限没有生效"。如果假网关一律报成功，住户会以为自己能建造，这比显示失败更误导；它的不变式断言在生产里还会抛 `AssertionError`。本地开发服因此不能再用它演示界面流程，界面走开发构建（无宿主）的假数据即可。
- GameTest `DistrictFlanSyncGameTests.recordingGatewayIsGameTestServerOnly` 核对这道门（非 GameTest 服务端的那一侧用 null 代表）。

---

## 九、领地权限生效状态 (DECIDED)

| 对象 | 取值 | 由什么决定 |
|---|---|---|
| 住户（名单行） | `synced` / `pending` / `failed` | 最近一次成员资格写入的成败；从没进过服的人为 `pending`，不写 Flan |
| 地块 | `synced` / `failed` | 最近一次整块重写的成败 |
| 朋友 | `synced` / `pending` | 从没进过服的人为 `pending`；写入成败记在地块上，不记在朋友上（A9：契约类型里有 `failed`，但服务端不产生它） |

状态怎么变：

1. **临时失败**：
   - 适用于每个会触发推送的写事务。事务里先把受影响的住户或地块写成 `failed`，`sync_error` 写"服务器写入领地前中断，请管理员重试"（`DistrictTexts.SYNC_INTERRUPTED`）；提交后推送，再改成真值。
   - 服务器在两步之间崩溃时，留下的是如实的"失败"，而不是虚假的"已生效"。
   - 单线程下，没有其他请求能看到这个中间状态。
2. **pending → synced / failed**：只在首次登录时发生（第十章）。
3. **住户 failed → synced**：只经 D5 重试，或 `/district resync`。
4. **地块 failed → synced**：这块地的任何一次写入都会整块重写。另外也可以用 `/district resync`。

状态在界面上的体现：

- `syncIssues`（D1，只给管理员）：按名单行的 `sync_status` 计数 `{pending, failed}`。
- `syncError`：住户和地块的失败原文，**只给管理员**；区务长看到的恒为 null。
- abilities：区务长和住户的 build、interact 跟着本人名单行的状态走（7.5）。

---

## 十、从没进过服的玩家 (DECIDED；大小写一条 PENDING)

### 10.1 见过的玩家表

`district_seen_player` 是"有没有进过服"和 `lastSeenAt` 的唯一来源。

- 登录时（`PlayerLoggedInEvent`）：
  - 已有这一行：更新 `player_name`、`name_lower` 和 `last_seen_at`。
  - 没有：插入一行，`source = 'login'`。
- 登出时（`PlayerLoggedOutEvent`）：更新 `last_seen_at`。
- `lastSeenAt` 为 null，当且仅当表里没有这个人。这与 pending 同源。

### 10.2 按名字解析玩家（`PlayerDirectory.resolve(String typed)`）

D3、D15 用它判定"进没进过服"。`typed` 是去掉首尾空白后的输入。

1. **在线**：`PlayerList.getPlayerByName(typed)`（不分大小写）命中 → 已知玩家，取 `GameProfile` 的规范名和 UUID。
2. **见过**：`seenByNameLower(lower(typed))` 命中 → 已知玩家。有多行时取 `last_seen_at` 最新的那一行。
3. **旧存档兜底**：`playerdata/<离线UUID(typed)>.dat` 存在 → 已知玩家，规范名就是 `typed`。
   - 离线 UUID 是由名字的原样大小写算出来的，所以文件存在就说明大小写正确。
   - 命中时顺手补一行 `backfill`。
4. 以上都没有 → **从没进过服**：
   - UUID 取 `UUIDUtil.createOfflinePlayerUUID(typed)`，即 `nameUUIDFromBytes("OfflinePlayer:" + typed)`；
   - 名字按输入原样保存。

**绝不调用 `GameProfileCache.get(String)`**：缓存没命中时，它会在主线程上发 Mojang HTTP 请求；离线模式下还会编一个假档案写进 usercache，从而既判断不了"没进过服"，又污染缓存。`GameProfileCache.get(UUID)` 只查本地，可以用。

### 10.3 老玩家回填

本模块上线前就进过服的玩家，要在开服后回填进见过的玩家表，否则会被误判成"从没进过服"。

- 时机：`ServerStartedEvent`，只做一次，以 `StoreMeta` 键 `district.seenBackfill.v1` 作标记。
- 做法：
  1. 遍历 `server.getWorldPath(LevelResource.PLAYER_DATA_DIR)` 下的 `*.dat`，从文件名解析 UUID。
  2. 用 `server.getProfileCache()`（可能为 null）`.get(uuid)` 取名字，这一步只查本地。
  3. 取到名字就插入一行 `source='backfill'`，`first_seen_at` 和 `last_seen_at` 都取文件的修改时间。
- 取不到名字的玩家跳过。TA 下次登录时会被记上；在那之前，10.2 第 3 步的兜底也能认出 TA。

### 10.4 首次登录补写（`FirstLoginActivation`）

`PlayerLoggedInEvent` 在记完见过的玩家表之后执行下面的步骤。整段包在 try/catch 里，数据库失败只记 ERROR，不能打断登录（同 `TitleSystem`）。

1. **一个事务里重新对齐身份**：
   - 按 UUID 找名单行；找不到时，按 `name_lower` 找 `pending` 的名单行。
   - 找到的行 UUID 不同，就**换键**：把名单行的 UUID 和名字改成登录者的。如果这个人是区务长，把 `district.warden_uuid` 和 `warden_name` 一并改掉：先改名单行，再改自管区，保证触发器通过。
   - 名字相同的 `pending` 朋友行也照此换键，并把 `sync_status` 改为 `synced`。
  - 凡是换键的地方（名单行、朋友行），按旧 UUID 排着的聊天通知一并换到新 UUID（阶段 3，22.11）。
2. **提交后推送**：
   - 名单行原本是 `pending` → `syncResidentMembership`，然后回写 `synced` 或 `failed` 加原因。
   - 每块有朋友被激活的地 → `writePlotState`。
3. 只处理**未解绑**自管区。这一步**不写任何记录行**，因为契约没有对应的记录动作。

### 10.5 大小写与账号唯一 (PENDING P1)

离线 UUID 与名字的大小写有关，而全服比对名字一律不分大小写，名单还有 `name_lower UNIQUE` 约束。

- 例子：管理员把没进过服的 `pebble_fox` 加进名单，TA 首次以 `Pebble_Fox` 登录时，按小写名字匹配并换键。
- 这一步依赖一个前提：**AccessHub 保证"不分大小写的一个名字只对应一个账号"**，需要服主确认。
- 如果前提不成立，两个大小写不同的账号会争同一行。那时应改成"UUID 不同就不自动换键，只标记给管理员处理"。

### 10.6 名字的规范写法

- 记录里存的名字都是"规范名"：进过服的人用服务端知道的写法，没进过服的人用输入的原样写法。
- 回执一律回显库里存的规范名，不回显请求里的写法。
- 只有 D3 和 D15 做格式校验，规则是去掉首尾空白后匹配 `^[A-Za-z0-9_]{3,16}$`。

### 10.7 按 UUID 认人，名字只认待生效行（复核后补，2026-09-29）

离线 UUID 随名字的大小写变：`Owner_One` 与 `owner_one` 是两个账号。库里一行带着真 UUID（已知玩家、已生效）时，认人一律按 UUID；按名字（不分大小写）只认还只有名字的待生效行（从没进过服、按输入原样的离线 UUID 记入的名单行或朋友行）。否则名字相同的另一个账号会被当成原来那个人。

| 位置 | 按 UUID | 按名字，只在…… |
|---|---|---|
| D23 解冻、D11/D12 的 `canRestore`：原户主在不在本区名单上（`ServiceSupport.formerOwnerOnRoster`） | 冻结时记下的 `frozen_owner_uuid` | 名字相同的名单行是待生效行 |
| D3 回执的 `frozenPlot`：这个人原来那块冻结地块（`ResidentService.frozenPlotIn`） | 解析出的 UUID | 本次以待生效记入（没进过服） |
| D4 的朋友身份计数与暂停、D2 的 `friendOfPlotCount`（`FriendRecord.matches`） | 朋友行的 UUID | 朋友行是待生效行 |
| D1 的 `friendOf`（`friendshipsOf`） | 朋友行的 UUID | 朋友行是待生效行（SQL 里 `sync_status='pending'`） |
| D15 回执的 `isResident`（`PlotOwnerService.isResident`） | 朋友行的 UUID | 朋友行是待生效行 |
| 首次登录补写（10.4） | 名单行的 UUID | 名单行、朋友行是待生效行（本来就这样） |

刻意保留按名字（不分大小写）一律算数的地方，都是**拒绝**而不是认人：D3 查重（`name_lower UNIQUE`）、D15 查重（同一块地里 `name_lower UNIQUE`，改用 `FriendRecord.collidesWith`）、D15 的 `FRIEND_IS_OWNER`，以及管理者按名字操作名单与朋友（D4、D5、D6、D16、D25、`academy kick`）——那里的名字就是操作者的输入。GameTest：`PlotFreezeGameTests.formerOwnerIsMatchedByUuidAndNameOnlyForPendingRows`。

---

## 十一、冻结与到期收回 (DECIDED)

| 事件 | 地块变化 | 本区记录 | 地块记录 | Flan |
|---|---|---|---|---|
| 冻结（D4 连带触发） | 户主 → 冻结，朋友和三列不动 | `freezePlot` | `freeze`：缘由 `户主已移出本区，地块冻结 7 天` | 整块写成全关 |
| 解冻 D23 | 冻结 → 原户主 | `unfreezePlot`：`还给原户主 {name}` | `unfreeze`：`原户主回到本区，解除冻结` | 照库里存的设置整块重写 |
| 立即收回 D24 | 执行"收回例程" | `vacatePlot`：`管理员立即收回（原户主 {name}）` | `vacate`：`管理员立即收回冻结中的地块` | 重写成空置 |
| 到期收回 | 执行"收回例程" | `vacatePlot`：`冻结期满，自动收回`，操作人 `服务器` / `system` | `vacate`：`冻结期满，地块收回` | 重写成空置 |

**收回例程**（D24 与到期收回共用，一个事务）：

1. 任期 `tenure + 1`，此前的记录由此归档，只有管理员能看。
2. 清空户主和冻结信息，删掉全部朋友，三列回到地块默认值，状态改为临时失败。
3. 在**新任期**写 `vacate`，它成为新一任记录的第一行。
4. 写本区记录 `vacatePlot`。
5. 提交后执行 `writePlotState`。

**到期检查的三个入口**，都调用 `PlotFreezeService.reclaimExpired(...)`：

| 入口 | 时机 | 范围 |
|---|---|---|
| 定时 | `ServerTickEvent` 的 END 阶段，每 1200 tick（60 秒） | 全服 |
| 开服 | `ServerStartedEvent`，补上停服期间到期的 | 全服 |
| 写动作前 | 每个写动作第一步（6.4 的 sweep），先收本区已到期的 | 本区 |

写动作前先检查，保证写请求看不到已过期的冻结。例如到期后再调 D23，得到的是 `PLOT_NOT_FROZEN`。

三个入口的共同规则：

- **每块地一个事务**：一块地出错只记 ERROR，不影响其他地块。
- **跳过已解绑的自管区**。
- **收回时间**：记录的 `at` 取实际处理的时刻，不回填成 `frozenAt + 7 天`（A13）。否则记录会写成"发生在过去"，和自增 id 的顺序打架。

**读动作不做到期收回**。到期但还没来得及收回的地块，读回执照实给出 `status = frozen`，其中 `reclaimAt` 已经过去；界面已有"冻结期已满，等服务器收回"的写法。这样所有读动作都是纯读取。

---

## 十二、解绑与归档 (DECIDED；重新绑定 PENDING)

### 12.1 D7 解绑做什么

一个事务里完成：

1. 在自管区行写入：
   - `unbound_at = at`；
   - `unbound_by_name` = 操作人；
   - `unbound_member_count` = 名单人数；
   - `unbound_plot_count` = 地块数，包含所有状态的地块。
2. 清空区务长（有 CHECK 保证）。
3. 写本区记录：**不写**。契约没有"解绑"这个记录动作，前端也没有对应的标签；归档行本身已经带着解绑时间和操作人。

解绑之后：

- 这个区从 D1 消失，所有自管区动作都返回 `DISTRICT_NOT_FOUND`。
- 面板对这个区的一切 Flan 写入全部停止：成员同步、地块代办、首次登录补写、到期收回。
- Flan 领地原样保留，**不调用任何 Flan 方法**。
- 学院名单**保留**：
  - 仍然约束"一人一学院"，所以这些成员去别的学院会被拒（`RESIDENT_ELSEWHERE`，用"已解绑"那句文案）。
  - 这些成员的全局身份变成外人。

回执：`{districtId, keptMembers: unbound_member_count, keptPlots: unbound_plot_count}`。

### 12.2 数据保留（A7）

与参考实现不同，服务端**一行都不删**：地块、三列、朋友、地块记录、墓碑、公共区域开关、定价都保留。

D26 只按契约露出这些字段：`{districtId, displayName, academyName, academyFullName, unboundAt, unboundBy, memberCount, log}`。其中：

- `memberCount` 取解绑那一刻的人数。
- `log` 是这个区的本区记录，新的在前，受预算限制。

### 12.3 重新绑定 (PENDING P2)

同一个学院可以再次绑定。新的自管区用新的 `districtId`：

- 第一次绑定时，`districtId = academyId`。
- 之后第 n 次绑定为 `<academyId>-<n>`，例如 `abydos-2`。

新区的地块编号从 1 开始，`plotId` 为 `abydos-2-01`，`code` 仍是 `阿拜多斯-01`。旧区的地块、墓碑和记录都挂在旧的 `districtId` 下，不会冲突。

旧 Flan 子领地怎么处理（沿用还是删除），阶段 2 已定：用 `/district bind` 收编旧的父领地，其上的旧子领地全部删掉（20.6）。

**已解绑区的范围仍算"有人"**（复核后补，2026-09-29）：解绑刻意不删 Flan 领地，旧的父领地和地块子领地都还在那片地上，旧地块行也还在库里。所以建区（`/district create`）和改范围（`/district bounds`）的重叠检查除了其他**未解绑**的自管区，还要看**已解绑**的：

- 压到**别的学院**已解绑区的范围 → `OVERLAPS_DISTRICT`，detail 是那个归档区的 districtId。否则新区的父领地会和旧领地叠在一起，旧地块也会和新地块落在同一片地上。
- **同一个学院**把自己原来那片地重新绑回来（哪怕范围有出入）→ 放行。这正是 P2 的重新绑定；那片地上的旧领地是沿用还是删除，同样由阶段 2 定（20.6）。阶段 1 的假网关和真 Flan 一样不许顶层领地重叠，所以这种重新绑定的父领地多半建不起来，只会置对账标记；阶段 2 起，`/district create` 会先查到那里已有 Flan 领地，并提示改用 `/district bind`（20.6）。
- 实现：`DistrictAdminService.overlappingDistrict`；GameTest `DistrictUnbindGameTests.archivedLandBlocksOtherAcademiesButNotItsOwnRebind`。

### 12.4 已解绑学院的成员 (PENDING P12)

这些成员没法再加入别的学院，平板上也没有入口移出他们。阶段 1 提供命令 `/district academy kick <academyId> <name>`：

1. 只对**没有在用自管区**的学院生效。
2. 如果此人在已解绑的区里还登记为户主：
   - 先把那块地的户主清空。这不写 Flan，因为解绑后面板本来就不管 Flan。
   - 在那块地的**当前任期**写一行 `vacate`，actorRole 为 admin，reason 为 `管理员把原户主移出已解绑的学院`。这一行只有管理员能在墓碑或归档里看到。
3. 删除成员行。
4. 写审计 INFO。

这一步必须先做，因为 `ux_district_plot_owner` 对全部地块生效。

`friendOf`（D1）只列有户主的地块，`friendOfPlotCount`（D2）则把冻结中的地块也算进去。这是参考实现的刻意口径，照做。

---

## 十三、记录的可见性 (DECIDED)

| 记录 | 谁能看 | 在哪 | 上限 |
|---|---|---|---|
| 本区记录，含移出原因 | 管理员；本区区务长 | D2 `log` | 最新 100 条，同时受字符预算约束 |
| 已解绑区的本区记录 | 管理员 | D26 每区的 `log` | 每区最新 50 条，最多 20 个区 |
| 地块记录：本任期 | 户主；管理员 | D12 `log` | 与下一行合计 100 条 |
| 地块记录：历任（归档） | 仅管理员 | D12 `log`，排在本任期之后，各自新的在前 | 同上 |
| 已删地块的全部记录 | 仅管理员 | D11 `deletedPlots[].log` | 最新 20 个墓碑，每个 20 条 |
| 移出原因 | 只进本区记录，**永不**进地块记录 | — | — |
| 某人是哪几块地的朋友 | 只有本人（D1 `friendOf`）；区务长只拿到**块数** | D1，以及 D2 / D4 的计数 | `friendOf` 最多 50 条 |

- **任期**：
  - 地块记录行带 `tenure`。本任期 = `tenure == plot.tenure`，历任 = `tenure < plot.tenure`。
  - 收回例程只把任期 +1，不搬行、不改历史。
  - 墓碑 = 该 `plot_id` 的全部记录，不分任期。
- **`entryId`**：本区记录为 `d<id>`，地块记录为 `p<id>`。自增 id 在本任期、历任和墓碑里都唯一，React 把它当作 key。
- **排序**：一律 `at DESC, id DESC`。
- **上限在服务端生效**：超出上限时截断，并在回执里附加截断标记（14.3）。

---

## 十四、WebUI 动作 (DECIDED)

### 14.1 通用约定

1. **注册**：`DistrictWebUiActions.registerAll()` 在 `DistrictSystem.register` 里调用，登记 26 条。
   - 每条 handler 都是包级可见的 `static final WebUiAction`。
   - 服务从 `DistrictServices.context()` 在**调用时**取；取不到时抛 ISE，走派发器的通用兜底。
2. **序列化**：`new GsonBuilder().serializeNulls().create()`，只用 Gson 树 API。回执里每个字段都要写出来，可空的写 JSON `null`，**不许省略键**。
3. **管理员门**：`admin.*` 动作的**第一句**是 `WebUiPermissions.requireOp(sender, action)`，失败时抛 `PERMISSION_DENIED`，params 为 `{action}`，文案"需要 OP 权限"。所以 D5 和 D21–D24 的管理员检查排在查区之前。
4. **校验顺序**：
   - 机器字段（`districtId`、`plotId` 等必填字符串）缺失或类型不对 → `INVALID_REQUEST`，排在一切业务检查之前。
   - 其余字段在参考实现校验它的那一步再校验。例如 D9 的 `enabled` 排在第 6 步。
5. **入参读取**（`DistrictPayloads`，其中两个通用读取器加进 `WebUiPayloads`）。读取器分两档：机器字段在 handler 里当场报错；其余字段只读成可空的值交给服务层，由服务层在契约检查顺序里校验它的那一步报错（所以这些码排在身份门之后）。

   | 读取器 | 放在哪 | 规则 |
   |---|---|---|
   | `id(payload, field)`（即 `WebUiPayloads.requiredString`） | `DistrictPayloads` | 机器字段：`districtId`、`plotId`、`playerName`、`permissionId`。缺失或不是字符串当场报 `INVALID_REQUEST {field}` |
   | `requiredNullableString(payload, field)` | `WebUiPayloads` | 机器字段：键必须存在，值可以是 JSON null，用于 D6 的 `playerName`。**缺键是 `INVALID_REQUEST`，不等于撤销** |
   | `requiredSafeLong(payload, field)` | `WebUiPayloads` | 机器字段：必须是整数，且在 ±(2⁵³−1) 以内，用于 D20 的 `expectedPrice` |
   | `requiredArea(payload, field)` | `DistrictPayloads` | 机器字段：`{minX,minZ,maxX,maxZ}` 四个 32 位整数（多出的键不读），用于 D20 的 `expectedBounds`。缺失、不是对象或坐标不对当场报 `INVALID_REQUEST {field}` |
   | `strictBoolean(payload, field)` | `DistrictPayloads` | 只有 JSON 布尔才有值；缺省、null、字符串、数字一律读成 null，由服务层报 `INVALID_REQUEST {field}`（D9、D13 的 `enabled`，D22 的 `open`）。**绝不当成 false 或"不设置"** |
   | `optionalTrue(payload, field)` | `DistrictPayloads` | 只有 JSON `true` 算真，其余一律算假，用于 `allowNeverJoined` |
   | `optionalString(payload, field)` | `DistrictPayloads` | 缺键、null、不是字符串读成 null。枚举取值（`audience`、`reasonKind`、`scope`）由服务层按 `WebUiPayloads.illegalValue` 的写法报 `INVALID_REQUEST {field, value}`；D4 的 `reason` 由服务层报 `REASON_REQUIRED` 或超长的 `INVALID_REQUEST {field: reason}` |
   | `optionalSafeLong(payload, field)` | `DistrictPayloads` | 是安全范围内的整数才有值，否则 null。D21 的 `unitPrice`、`minSide`、`maxSide` 由服务层报 `INVALID_PRICE` / `INVALID_SIZE_LIMIT` |
   | `area(payload)` | `DistrictPayloads` | D17、D18 的 `area`：四个坐标都是 32 位整数时给出范围，否则给出第一个缺失或不是整数的字段名（`area` 本身不是对象时为 `area`），由服务层在身份门之后报 `INVALID_AREA {field}` |
6. **数据库失败**：统一由 `guard(action, ...)` 转成 `STORE_FAILED`（6.3）。
7. **批量**：不进批量（17.3）。
8. **读动作绝不写库**：D1、D2、D8、D11、D12、D26。

### 14.2 共享对象与裁剪

以下字段名和类型就是界面读取的形状，逐字对应 planned.ts。标 ★ 的是**本文新增的附加字段**：前端不认识时会忽略，接线时写进 `lib/types.ts`。

```
PlotRef          { plotId, code }
DistrictBounds   { dimension, minX, minZ, maxX, maxZ }                       // 含两端; area = (maxX-minX+1)*(maxZ-minZ+1), long
PlotArea         { minX, minZ, maxX, maxZ }
AreaChange       { from: PlotArea|null, to: PlotArea|null }

DistrictSummary  { districtId, displayName, academyName, academyFullName, wardenName|null,
                   residentCount, area, plotCount, vacantPlotCount, syncIssues|null }
Residency        { districtId, districtName, academyName, academyFullName, grantedAt, grantedBy,
                   isWarden, syncStatus, plot: PlotRef|null }
DistrictInfo     { districtId, displayName, academyName, academyFullName, wardenName|null, bounds, area,
                   residentCount, rules: string[], createdAt, plotCount, vacantPlotCount }
Resident         { playerName, joinedAt, addedBy, lastSeenAt|null, syncStatus, syncError|null,
                   isWarden, plot: PlotRef|null, friendOfPlotCount }
DistrictLogEntry { entryId, at, actorName, actorRole, action, targetName|null, reason|null,
                   permission: {permissionId,label,audience,from,to}|null, area: AreaChange|null }
PlotLogEntry     { ...同上但 actorRole ∈ owner/admin/warden/system, audience ∈ friend/resident/outsider...,
                   onBehalfOfOwner }
PermissionItem   { permissionId, label, detail|null, scope, current:{resident,outsider,district},
                   defaults:{...}, flanIds|null, flanInverted|null, outsiderRisk|null, districtRisk|null }
PermissionsResult{ districtId, editable:{member,region}, groups:[{groupId,label,scope,items}], fixedRules }
PlotSummary      { plotId, code, status, ownerName|null, frozen|null, bounds, area, price|null,
                   friendCount|null, syncStatus|null, syncError|null, openToResidents: string[],
                   residentColumnIsDefault }
FreezeInfo       { formerOwnerName, frozenAt, reclaimAt, canRestore|null }
PlotInfo         { plotId, code, districtId, status, ownerName|null, frozen|null, bounds, area,
                   syncStatus, syncError|null }
PlotFriend       { playerName, addedAt, addedBy, syncStatus, isResident, suspended, suspendedAt|null }
PlotPermItem     { permissionId, label, detail|null, current:{friend,resident,outsider},
                   defaults:{...}, flanIds|null, flanInverted|null, risk|null }
PlotFriendship   { districtId, plotId, code, ownerName, syncStatus, suspended }
Market           { unitPrice, open, viewerBlock|null, viewerBalance|null }
Rules            { edgeGap, minSide, maxSide }
```

**裁剪规则**：全部实现在 `DistrictJson`，按"对这个区的身份"（7.3）裁剪，不按全局身份。

公共区域开关（`PermissionItem`）：

| 字段 | 谁能看到真值 |
|---|---|
| 住户列（`current` 和 `defaults` 两处） | 管理员、本区区务长、本区住户。NONE 为 null |
| 外人列、全区列 | 所有人 |
| `flanIds`、`flanInverted` | 仅管理员 |
| `outsiderRisk` | 仅管理员和本区区务长，且只有高风险的 member 项才有值 |
| `districtRisk` | 仅管理员，且只有高风险的 region 项才有值 |

member 项的 `district` 列、region 项的 `resident` / `outsider` 列，结构上恒为 null。

名单、生效状态与记录：

| 字段 | 规则 |
|---|---|
| `Resident.syncError`、`PlotSummary.syncError`、`PlotInfo.syncError` | 仅管理员 |
| `PlotSummary.friendCount`、`PlotSummary.syncStatus`、`FreezeInfo.canRestore` | 管理员和本区区务长。住户为 null |
| `canRestore` 的算法 | 原户主在本区名单上，且在本区没有别的地块 |
| `DistrictSummary.syncIssues` | 仅管理员 |
| D2 的 `residents` 和 `log` | 仅管理员和本区区务长。住户为 null，必须和 `[]` 区分开 |
| D11 的 `deletedPlots` | 仅管理员，其余人为 null |

地块列表（`PlotSummary`）的派生字段：

- `ownerName`：空置和冻结中为 null。
- `price`：只有空置地块才有，等于 `area × unitPrice`，按 long 计算。
- `openToResidents`：
  - 冻结中为 `[]`；
  - 其余情况取住户列为真的 member 项的 label，按目录顺序排；空置地块用默认值。
- `residentColumnIsDefault`：未冻结，且住户列每一格都等于默认值。

地块详情（D12）的开关组（`PlotPermItem`）：

- 只含 member 组，组对象里不带 scope。
- 三列都是非 null 的布尔值。
- `risk` 取目录里的 `plotRisk`，所有人都能看到；`flanIds`、`flanInverted` 仅管理员。

排序：

| 列表 | 顺序 |
|---|---|
| 自管区 | 按学院的 `sort_order` |
| 住户 | 按入学顺序（`id`） |
| 地块 | 按编号 |
| 朋友 | 按存储顺序（`id`） |
| 开关组和开关项 | 按目录顺序 |
| `friendOf` | 先按自管区顺序，再按地块编号 |
| 墓碑、归档 | 按删除或解绑时间，新的在前 |

**固定规则**（D8 `fixedRules`）只有一条，所有人都会收到：

```
{ ruleId: 'create_ban', label: '机械动力', valueText: '机器禁用，装饰可放，OP 例外',
  detail: '自管区内和外围 8 格内不能放机械动力的机器（会转、会动或带功能的方块，轨道也算），外壳、支架、梯子、石材、玻璃这类纯装饰方块照常可放；机械动力的机器也改不了自管区里的方块。管理员（OP）亲手放置不受限。服务器直接拦，这条规则谁都改不了' }
```

**权限目录**（`PermissionCatalog`）一共 36 项。`label`、`detail`、`outsiderRisk`、`districtRisk`、`plotRisk` 从 `district-seed.ts` 第 407–593 行**逐字**抄。表中列名的含义：

- 公 R / 公 O：公共区域住户列、外人列的默认值。
- 地 F / R / O：地块朋友列、其他住户列、外人列的默认值。
- 风险：该项同时带 `outsiderRisk` 和 `plotRisk`。

| 组 | permissionId | flanIds | 公 R | 公 O | 地 F | 地 R | 地 O | 风险 |
|---|---|---|---|---|---|---|---|---|
| build 建造 | place | flan:place | T | F | T | F | F | 有 |
| | break | flan:break | T | F | T | F | F | 有 |
| | bucket | flan:bucket | T | F | T | F | F | 有 |
| | sign | flan:interact_sign | T | F | T | F | F | – |
| | trample | flan:trample | F | F | F | F | F | 有 |
| storage 箱子与机器 | container | flan:open_container | T | F | T | F | F | 有 |
| | use_block | flan:interact_block | T | F | T | F | F | – |
| | anvil | flan:anvil | T | F | T | F | F | – |
| | enchant | flan:enchantment | T | T | T | F | F | – |
| | enderchest | flan:enderchest | T | T | T | F | F | – |
| | beacon | flan:beacon | T | F | T | F | F | – |
| doors 门与红石 | door | flan:door | T | T | T | T | T | – |
| | trapdoor | flan:trapdoor | T | T | T | T | F | – |
| | fence_gate | flan:fence_gate | T | T | T | T | F | – |
| | button | flan:button_lever | T | T | T | T | T | – |
| | plate | flan:pressure_plate | T | T | T | T | T | – |
| | redstone | flan:redstone | T | F | T | F | F | – |
| living 生活 | bed | flan:bed | T | T | T | F | F | – |
| | trading | flan:trading | T | T | T | F | F | – |
| | itemframe | flan:itemframe_rotate | T | T | T | F | F | – |
| | armorstand | flan:armorstand | T | F | T | F | F | 有 |
| | break_entity | flan:break_non_living | T | F | T | F | F | 有 |
| animals 动物 | animal | flan:animal_interact | T | F | T | F | F | – |
| | hurt_animal | flan:hurt_animal | T | F | F | F | F | 有 |
| | hurt_named | flan:hurt_named | F | F | F | F | F | 有 |
| items 物品与移动 | pickup | flan:pickup | T | T | T | T | T | – |
| | ender_pearl | flan:ender_pearl | T | F | T | F | F | – |
| | ride | flan:boat, flan:minecart | T | F | T | F | F | – |
| | portal | flan:portal | T | T | T | F | F | – |

区域规则组 `region`（区域规则）只有全区一列，不进地块。`defaults` 为 `{resident: null, outsider: null, district: X}`，`plotDefaults` 为 null：

| permissionId | flanId | 全区默认 | flanInverted | 风险 |
|---|---|---|---|---|
| pvp | flan:hurt_player | F | 否 | 有 |
| explosions | flan:explosions | F | 否 | 有 |
| wither | flan:wither | F | 否 | 有 |
| fire_spread | flan:fire_spread | F | 否 | 有 |
| mob_spawn | flan:mob_spawn | T | **是** | – |
| enderman | flan:enderman | T | 否 | – |
| liquid_border | flan:water_border | F | 否 | 有 |

### 14.3 回执体积预算

派发器会把长度超过 32767 字符的回执替换成 `RESPONSE_TOO_LARGE`，而契约里的列表都没有分页，所以服务端必须自己控制体积。

- **做法**：由 `ResponseBudget` 按字符预算装列表，总预算 `RESPONSE_BUDGET_CHARS = 30_000`，给信封留出余量。
  1. 先放必需部分。
  2. 再按"新的在前"逐条加入列表项：每条先 `GSON.toJson(entry)` 量出长度，超出预算就停。
  3. 停下时把对应的 ★ 截断标记置为 true。
- **按动作的装入顺序**：

  | 动作 | 先装 | 后装 |
  |---|---|---|
  | D2 | 自管区信息、abilities | 住户（★ `residentsTruncated`），再记录（上限 100 条，★ `logTruncated`） |
  | D11 | 固定部分 | 地块（★ `plotsTruncated`），再墓碑（最多 20 个，每个的记录最多 20 条；★ `deletedPlotsTruncated`，每个墓碑另有 ★ `logTruncated`） |
  | D12 | 地块信息、朋友、开关组 | 记录（本任期在前，合计 100 条，★ `logTruncated`） |
  | D26 | — | 归档区（最多 20 个，★ `truncated`；每区记录最多 50 条，★ `logTruncated`） |
  | D1 | — | `friendOf`（最多 50 条，★ `friendOfTruncated`） |
  | D10 | 改完后的整张开关表 | 改动记录（★ `logEntriesTruncated`；★ `changedCount` 恒为实际改动的格数） |
  | D14 | 改完后的地块详情（记录先空着） | 改动记录（同上两个 ★ 字段），再详情里的地块记录（★ `detail.logTruncated`） |

  - **一旦有一条装不下，本次回执的预算即用尽**，之后的列表一条都不再加：否则后面列表里恰好更短的条目还能挤进去，出现"前面截断了、后面反倒是全的"。
  - **D10 / D14 另加 ★ `changedCount`**（第二步落地时补）：地块三列全部改成非默认再恢复，是 87 条改动记录（每条约 270 字符）加整块地的开关组，逼近 32767；改动记录因此也进预算。界面的"已把 N 处恢复成默认值"改读 `changedCount`，不再数 `logEntries` 的长度。

- **实测口径**（在参考目录上量得）：

  | 内容 | 约多少字符 |
  |---|---|
  | D8 管理员视角整份 | 11.7K |
  | D12 的开关组 | 7.7K |
  | 一条本区记录 | 190 |
  | 一条改权限记录 | 300 |
  | 一行住户 | 215 |
  | 一行地块 | 400 |

  推算下来，D2 在记录为空时大约能放 130 名住户，D11 大约能放 70 块地。**一个区超过约 70 块地时，D11 会被截断**，这需要一次契约变更（分页，或者把地块列表拆开），见 PENDING P7。
- **GameTest**：造一个"60 名住户、200 条本区记录、40 块地、30 个墓碑"的区，断言 D2、D11、D12、D26 都不超过 32767 字符，并且截断标记正确。第二步落地时另把 D10 / D14 的"全部改成非默认再恢复"一并量了（`DistrictWebUiGameTests.responsesStayUnderCapForALargeDistrict`）。

### 14.4 逐条动作

说明：

- "检查"一栏按先后顺序列出，报第一条不满足的；与参考实现一致。
- 所有写动作的第 0 步都是本区的 sweep（6.4），表里不再重复。
- 管理员门、区务长门、户主门报的都是 `PERMISSION_DENIED`，params 为 `{action, requires}`，文案见第十五章。

**D1 `district.state`**（读，所有人）

- 入参：`{}`。
- 回执：`{viewer:{playerName, role}, residency|null, districts: DistrictSummary[], friendOf: PlotFriendship[], ★friendOfTruncated}`。
  - `residency`：只要查看者在某个未解绑区的名单上就有，OP 也一样。其中的 `plot` 是 TA 现在拥有的地块，冻结中的旧地块不算。
  - `districts`：全部未解绑的区。
    - `residentCount` 计入 pending 和 failed 的名单行。
    - `plotCount` 包含冻结中的地块；`vacantPlotCount` 只数空置的。
  - `friendOf`：跨所有未解绑区，只列**有户主**的地块。

**D2 `district.detail`**（读）

- 入参：`{districtId}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`（身份为 NONE）：`你不是这个自管区的住户，只能看公开信息`
- 回执：`{district: DistrictInfo, abilities, residents|null, log|null, ★residentsTruncated, ★logTruncated}`。
- `Resident.friendOfPlotCount`：TA 在本区其他地块上"未暂停"的朋友身份块数。含冻结地块，不含 TA 自己的地块。

**D3 `district.addResident`**（写；管理员、本区区务长）

- 入参：`{districtId, playerName, allowNeverJoined}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`（不是管理员或本区区务长）
  3. `INVALID_PLAYER_NAME`（按去掉首尾空白后的名字）
  4. `ALREADY_RESIDENT`
  5. `RESIDENT_ELSEWHERE`（在别的**未解绑**学院）
  6. `RESIDENT_ELSEWHERE`（在已解绑学院，用"已解绑"那句文案）
  7. `PLAYER_NEVER_JOINED`（从没进过服，且 `allowNeverJoined` 不是 `true`）
- 写入：
  - 插入一行名单：存规范名和 UUID，`joinedAt = at`，`addedBy` = 操作人。
  - 状态：已知玩家先写临时失败，从没进过服的写 `pending`。
  - 本区记录 `add`。
  - **不会**顺带解冻 TA 以前冻结的地块。
- 推送：只对已知玩家做 `syncResidentMembership`。
- 回执：`{resident (调用者视角), logEntry, frozenPlot: TA 在本区冻结中的地块 | null}`。

**D4 `district.removeResident`**（写；管理员、本区区务长）

- 入参：`{districtId, playerName, reasonKind, reason}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`
  3. `INVALID_REQUEST {field: reasonKind}`（取值不在四种之内）
  4. `REASON_REQUIRED`（`reason.trim()` 为空）
  5. `INVALID_REQUEST {field: reason}`（`reason.trim()` 超过 `DistrictLimits.MAX_REMOVE_REASON_CHARS` = 200 个字符，按 UTF-16 码元计）
  6. `NOT_RESIDENT`
  7. `RESIDENT_IS_WARDEN`（管理员也不例外，要先撤销区务长；区务长不能移出自己）
- `reason` 原文去掉首尾空白后保存，不解析；连带后果**只看** `reasonKind`。
- 原因长度上限（第 5 步）：界面的补充说明限 60 字，拼上预设原因也不到 70 字，正常操作碰不到，所以按 15.1 第 2 条复用 `INVALID_REQUEST`。服务端必须自己限：原因原文进本区记录，随 D2、D26 下发；不限的话，一条上万字的记录会占满回执预算，把更早的记录全挤出去（区务长能借此让管理员在平板上看不到自己之前的操作），它自己的回执也会超过下行上限，变成"已经生效却报失败"。
- 写入、推送、回执见 6.6。

**D5 `admin.district.retrySync`**（写；管理员）

- 入参：`{districtId, playerName}`。
- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
  3. `NOT_RESIDENT`
  4. `SYNC_NOTHING_TO_RETRY`（状态不是 `failed`，pending 也拒）
- **流程特殊，不用 6.4 的模板**：
  1. 先调 `syncResidentMembership`。
  2. 成功时，在一个事务里改为 `synced`、清空 `syncError`、写本区记录 `resync`，回执 `{resident}`（管理员视角）。
  3. 失败时，用一个单独提交的小事务更新 `sync_error`，**不写** `resync` 记录，然后抛 `SYNC_RETRY_FAILED`，文案中带上 Flan 的失败原因（A6）。

**D6 `admin.district.setWarden`**（写；管理员）

- 入参：`{districtId, playerName: string|null}`。缺少 `playerName` 键时报 `INVALID_REQUEST`。
- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
- 撤销（`playerName` 显式为 null）：
  - 检查：`WARDEN_NOT_APPOINTED`（本来就没有区务长）。
  - 写入：清空区务长；记录 `revoke`（target = 前任）。
  - 回执：`{wardenName: null, logEntry}`。
- 任命：
  - 检查：
    1. `NOT_RESIDENT`（不在**本区**名单上；pending 的人可以任命）
    2. `ALREADY_WARDEN`
  - 写入：区务长设为名单上的规范名。
  - 记录：原本没有区务长时只写 `appoint`；换人时先写 `revoke`（前任）再写 `appoint`（新任）。
  - 回执：`{wardenName, logEntry: appoint 那一条}`。
- 不调用 Flan。

**D7 `admin.district.delete`**（写；管理员）

- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
- 其余见 12.1。

**D8 `district.permissions`**（读，所有人）

- 检查：`DISTRICT_NOT_FOUND`。
- 回执：`PermissionsResult`，按 14.2 裁剪。
  - `editable.member` = 管理员或本区区务长。
  - `editable.region` = 管理员。

**D9 `district.setPermission`**（写；管理员、本区区务长）

- 入参：`{districtId, permissionId, audience, enabled}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`（不是管理员或本区区务长）
  3. `PERMISSION_ITEM_UNKNOWN`
  4. `INVALID_REQUEST {field: audience}`（member 项只收 resident 或 outsider，region 项只收 district）
  5. `PERMISSION_DENIED`（region 项而调用者不是管理员）：`区域规则只有管理员可以改`
  6. `INVALID_REQUEST {field: enabled}`（不是 JSON 布尔）
- 值和现在一样时：回执 `{item, logEntry: null}`，不写库、不写记录。
- 否则：
  - 写这一格。
  - 写本区记录 `permission`：reason 为 null，target 为 null，`permission = {permissionId, label, audience, from, to}`。
  - 推送 `writeDistrictPermission`。
- 回执：`{item (调用者视角), logEntry}`。

**D10 `district.resetPermissions`**（写；管理员、本区区务长）

- 入参：`{districtId, scope}`，`scope` 为 `member` 或 `all`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`（不是管理员或本区区务长）
  3. `INVALID_REQUEST {field: scope}`
  4. `PERMISSION_DENIED`（`all` 而调用者不是管理员）：`区域规则只有管理员可以改，区务长只能恢复住户和外人的开关`
- 写入：按目录顺序，把范围内每个和默认值不同的格子改回默认。范围：
  - `member`：住户列、外人列；
  - `all`：再加全区列。
- 记录：每改一格写一条 `permission`，reason 为 `恢复默认`。一格都不用改时不写库，`logEntries` 为 `[]`。
- 回执：`{permissions: 改完后的 PermissionsResult, logEntries, ★changedCount, ★logEntriesTruncated}`（两个 ★ 字段见 14.3）。

**D11 `district.plots`**（读）

- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`（NONE）：`只有本区住户能看本区的地块和户主`
- 回执：`{districtId, bounds, myPlotId|null, residentDefaults: string[], rules, market, plots, deletedPlots|null, ★plotsTruncated, ★deletedPlotsTruncated}`。
  - `residentDefaults`：地块住户列默认为真的项的 label，按目录顺序。按当前目录是这 6 项：开关门、开关活板门、开关栅栏门、按按钮和拉杆、踩压力板、捡地上的东西。
  - `market.viewerBlock` 按以下顺序判定，都不满足时为 null：
    1. `NOT_RESIDENT`：OP（**恒是这一条，即使 TA 在名单上**），或不在本区名单上。
    2. `ALREADY_OWNS_PLOT`（按全库判，与买地同口径，见 6.5）
    3. `HAS_FROZEN_PLOT`
    4. `PURCHASE_CLOSED`
  - `viewerBalance`：只有 `viewerBlock` 为 null 且经济门面在线时才给出，否则为 null。

**D12 `plot.detail`**（读；户主、管理员）

- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PLOT_NOT_FOUND`
  3. `PERMISSION_DENIED`（关系为 NONE）。调用者是本区区务长时用专门的文案，其余人用通用文案。
- 回执：`{plot: PlotInfo, viewerRelation: 'owner'|'admin', editable, friendLimit: 8, friends, groups, log, ★logTruncated}`。
  - `editable`：状态为 owned 时为真，户主和管理员都一样；空置、冻结为假。
  - 户主只拿到本任期的记录；管理员另外拿到历任的归档，排在本任期之后。

**D13 `plot.setPermission`**（写；户主、管理员）

- 入参：`{districtId, plotId, permissionId, audience, enabled}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PLOT_NOT_FOUND`
  3. `PERMISSION_DENIED`（区务长也在这一档）
  4. `PLOT_FROZEN`
  5. `PLOT_VACANT`
  6. `INVALID_REQUEST {field: audience}`
  7. `INVALID_REQUEST {field: enabled}`
  8. `PERMISSION_ITEM_UNKNOWN`
  9. `REGION_RULE_NOT_IN_PLOT`
- 值没有变化时：回执 `{item, logEntry: null}`。
- 否则：
  - 写这一格。
  - 写地块记录 `permission`：`onBehalfOfOwner` = 关系是否为 ADMIN，actorRole 为 owner 或 admin。
  - 推送 `writePlotState`。
- 回执：`{item: PlotPermItem, logEntry}`。

**D14 `plot.resetPermissions`**（写）

- 检查：与 D13 前 5 步相同（D13 闸门）。
- 写入：顺序为先按目录项、每项内按"朋友、住户、外人"，每改回一格写一条 `permission`，reason 为 `恢复默认`。
- 一格都不用改时 `logEntries` 为 `[]`。
- 回执：`{detail: 改完后与 D12 同形的详情, logEntries, ★changedCount, ★logEntriesTruncated}`（见 14.3）。

**D15 `plot.addFriend`**（写）

- 入参：`{districtId, plotId, playerName, allowNeverJoined}`。
- 检查：
  1. D13 闸门
  2. `INVALID_PLAYER_NAME`
  3. `FRIEND_IS_OWNER`
  4. `ALREADY_FRIEND`（已暂停时用另一句文案）
  5. `FRIEND_LIMIT_REACHED`（已有 8 人，已暂停的也占名额）
  6. `PLAYER_NEVER_JOINED`
- 朋友可以是任何玩家：外人、本区住户、别区住户、OP 都行。
- 写入：追加一位朋友，已知玩家为 `synced`，从没进过服的为 `pending`；写地块记录 `addFriend`。
- 推送：`writePlotState`。
- 回执：`{friend, logEntry}`。

**D16 `plot.removeFriend`**（写）

- 检查：
  1. D13 闸门
  2. `FRIEND_NOT_FOUND`（不分大小写，不校验名字格式）
- 写入：删除这位朋友（已暂停的也能删）；写地块记录 `removeFriend`，target 为规范名。
- 推送：`writePlotState`。
- 回执：`{logEntry}`。

**D25 `plot.restoreFriend`**（写）

- 检查：
  1. D13 闸门
  2. `FRIEND_NOT_FOUND`
  3. `FRIEND_NOT_SUSPENDED`
- 写入：`suspended_at` 置为 null；写地块记录 `restoreFriend`。
- 推送：`writePlotState`。
- 回执：`{friend, logEntry}`。

**D17 `plot.create`**（写；管理员、本区区务长）

- 入参：`{districtId, area}`。
- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`：`只有本区区务长和管理员可以划地块`
  3. `INVALID_AREA`（字段缺失或不是整数）
  4. 范围校验，依次为：
     1. `INVALID_AREA`（min > max）
     2. `OUT_OF_DISTRICT`
     3. `TOO_CLOSE_TO_EDGE`（四边都至少留 2 格）
     4. `SIZE_OUT_OF_RANGE`
     5. `OVERLAPS_PLOT`（和所有地块比，包括冻结中的；**贴边可以，共用一个坐标就算重叠**）
- 写入：
  - 编号 `no = next_plot_no`，然后 `next_plot_no + 1`。编号永不复用。
  - `plotId = <districtId>-<两位编号>`，`code = <学院简称>-<两位编号>`。
  - 新地块为空置，三列写默认值，状态为临时失败。
  - 记录：
    - 本区 `createPlot`：reason 为 `{w} × {d}，{w*d} 格`，area 为 `{from: null, to: area}`；
    - 地块 `create`。
- 推送：`ensurePlotClaim`，然后 `writePlotState`。
- 回执：`{plot: PlotSummary, logEntry (本区那条)}`。

**D18 `plot.resize`**（写；区务长只能调空置地块，管理员可调空置或有户主的）

- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`
  3. `PLOT_NOT_FOUND`
  4. `PLOT_FROZEN`（管理员也不能调冻结中的地块）
  5. `PLOT_OCCUPIED`（有户主，且调用者不是管理员）
  6. `INVALID_AREA`（类型）
  7. `PLOT_AREA_UNCHANGED`
  8. 范围校验，同 D17；重叠检查排除自己，尺寸上下限用现值
- 记录：
  - 本区 `resizePlot`：有户主时 reason 为 `管理员代改，已通知户主 {owner}`，否则为 `{sideText(from)} → {sideText(to)}`。
  - 地块 `resize`：有户主时 `onBehalfOfOwner` 为真。
- 推送：`writePlotState`（整块重写先把子领地范围改到库里的值，再写组与成员，见 8.3）。
- 回执：`{plot, logEntry, ownerNotified: 是否有户主}`。

**D19 `plot.delete`**（写；管理员、本区区务长，都只能删空置地块）

- 检查：
  1. `DISTRICT_NOT_FOUND`
  2. `PERMISSION_DENIED`
  3. `PLOT_NOT_FOUND`
  4. `PLOT_FROZEN`
  5. `PLOT_OCCUPIED`
- 写入：
  - 写墓碑，然后删除地块行，三列和朋友随之级联删除。
  - 地块记录**不删**，墓碑按 `plot_id` 读到它们。
  - 本区记录 `deletePlot`：reason 为 `删掉后这片地回到公共区域`，area 为 `{from: area, to: null}`。
  - 不写地块记录。
- 推送：`deletePlot`。
- 回执：`{logEntry}`。

**D20 `plot.buy`**：见 6.5。

- 入参：`{districtId, plotId, expectedPrice, expectedBounds: PlotArea}`。`expectedBounds` 是确认框里那一行 `PlotSummary.bounds` 的四个坐标（复核后补）。
- 回执：`{plot: PlotRef, price, balanceAfter, logEntry}`。

**D21 `admin.district.setPlotPricing`**（写；管理员）

- 入参：`{districtId, unitPrice, minSide, maxSide}`。
- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
  3. `INVALID_PRICE`（不是 1 到 1,000,000 之间的整数）
  4. `INVALID_SIZE_LIMIT`（不是整数、`minSide < 1`、`minSide > maxSide`，或 `maxSide > 1024`）
- 三个值都和现在一样时：`logEntry` 为 null，不写库。
- 否则写本区记录 `setPlotPricing`。reason 由有变化的部分用 `；` 连接：
  - `单价 {old} → {new} 信用点/格`
  - `每边 {oldMin}~{oldMax} → {min}~{max} 格`
- 回执：`{rules, unitPrice, logEntry}`。

**D22 `admin.district.setPurchaseOpen`**（写；管理员）

- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
  3. `INVALID_REQUEST {field: open}`
- 值没有变化时：回执 `{open, logEntry: null}`。
- 否则写本区记录 `setPurchaseOpen`，reason 为 `开放购买` 或 `暂停购买`。
- 回执：`{open, logEntry}`。

**D23 `admin.plot.unfreeze`**（写；管理员）

- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
  3. `PLOT_NOT_FOUND`
  4. `PLOT_NOT_FROZEN`
  5. `FORMER_OWNER_NOT_RESIDENT`
  6. `ALREADY_OWNS_PLOT`
- 写入、记录、推送见第十一章。
- 回执：`{plot: PlotRef, ownerName, logEntry}`。

**D24 `admin.plot.reclaimNow`**（写；管理员）

- 检查：
  1. `requireOp`
  2. `DISTRICT_NOT_FOUND`
  3. `PLOT_NOT_FOUND`
  4. `PLOT_NOT_FROZEN`（有户主的地块不能直接收回）
- 执行收回例程（第十一章）。
- 回执：`{logEntry (本区那条)}`。

**D26 `admin.district.archive`**（读；管理员）

- 检查：`requireOp`。
- 回执：`{districts: [...], ★truncated}`。

---

## 十五、错误码 (DECIDED)

### 15.1 口径

码的取舍按下面五条：

1. **身份不够**一律复用 `PERMISSION_DENIED`，不新增 `NOT_PERMITTED`。
   - 依据是 `WebUiPermissions` 与 `WebUiErrorCodes.PERMISSION_DENIED` 的 javadoc：同一种拒绝只能有一种回执形状。
   - 管理员门走 `requireOp`，params 为 `{action}`。
   - 区务长门和户主门抛同一个码，params 为 `{action, requires}`，`requires` 取 `manager`、`member` 或 `owner`（区务长改区域规则、区务长用 `all` 恢复默认这两处只许管理员的拒绝取 `admin`），文案用契约里的中文句子。
   - 前端不按这个码分支。`PERMISSION_DENIED` 也不在 `errorText.ts` 里，所以界面直接显示服务端原文。
   - 同一提交里更新 `PERMISSION_DENIED` 的 javadoc：删掉"当前只有 OP 一档"，写明自管区的三档。
2. **机器能产生的形状错误**复用 `INVALID_REQUEST`，params 为 `{field[, value]}`。这类错误包括：
   - 开关值不是布尔：`enabled`、`open`；
   - 枚举取值不在范围内：`audience`、`reasonKind`、`scope`；
   - 机器字段类型不对：`expectedPrice`、`expectedBounds`、各类 id、`allowNeverJoined` 之外的必填字段；
   - 界面限了长度的自由文本超长：`reason`（D4，上限 200 个字符，界面限 60 字）。

   界面正常操作永远触发不了这类错误，所以 `errorText.ts` 的通用句"字段 X 不接受这个取值"就够用。
3. **人手输入能触发的业务规则**保留专用码。包括：玩家名、移出原因、坐标、单价和尺寸。
   - 这些码不在 `errorText.ts` 里，界面直接显示服务端原文，所以文案必须是给玩家看的中文句子。
4. **钱和存储**复用现有的码：
   - `INSUFFICIENT_FUNDS`：params 为 `{cost, currency:"CREDIT", balance}`，同婚戒。
   - `ECONOMY_OFFLINE`
   - `STORE_FAILED`
5. **码名进全局命名空间**。`WebUiErrorCodes` 是全仓共用的一张表，发布后码名就冻结。因此把太泛的契约码加上领域前缀。
   - 现在改名不花代价：前端只按 `PLAYER_NEVER_JOINED` 和 `viewerBlock` 的四个值分支，这几个名字不动。

### 15.2 改名清单与要改的前端文件

| 契约里的码 | 服务端的码 | 理由 |
|---|---|---|
| `NOT_PERMITTED` | `PERMISSION_DENIED`（复用） | 15.1 第 1 条 |
| `INVALID_VALUE` | `INVALID_REQUEST`（field `enabled` / `open`） | 15.1 第 2 条 |
| `INVALID_AUDIENCE` | `INVALID_REQUEST`（field `audience`） | 同上 |
| `INVALID_REASON_KIND` | `INVALID_REQUEST`（field `reasonKind`） | 同上 |
| （参考实现不校验 `scope`） | `INVALID_REQUEST`（field `scope`） | A11 |
| `NO_CHANGE` | `PLOT_AREA_UNCHANGED` | 全局表里太泛 |
| `NOT_FROZEN` | `PLOT_NOT_FROZEN` | 同上 |
| `IS_OWNER` | `FRIEND_IS_OWNER` | 同上 |
| `NOT_FRIEND` | `FRIEND_NOT_FOUND` | 同上 |
| `NOT_SUSPENDED` | `FRIEND_NOT_SUSPENDED` | 同上 |
| `UNKNOWN_PERMISSION` | `PERMISSION_ITEM_UNKNOWN` | 容易和 `UNKNOWN_ACTION`、`PERMISSION_DENIED` 混淆 |
| `NO_WARDEN` | `WARDEN_NOT_APPOINTED` | 全局表里太泛 |
| `IS_WARDEN` | `RESIDENT_IS_WARDEN` | 同上 |
| `NOTHING_TO_RETRY` | `SYNC_NOTHING_TO_RETRY` | 同上 |
| （新增） | `SYNC_RETRY_FAILED` | A6：D5 重试后仍然失败 |

要改的前端文件（都是注释或假数据，不涉及页面分支）：

- `webui/src/mock/district-handlers.ts`：`reject(...)` 调用改成发服务端码。它接线后仍然作为开发预览的假实现（第十七章），必须和真服同码。
- `webui/src/mock/planned.ts`：D 组注释里点到这些码的地方。这些类型接线时会挪进 `lib/types.ts`，挪的时候一并改。
- `webui/src/pages/district/PlotDetail.tsx` 第 70 行注释里的 `NOT_PERMITTED`。
- 不用改的地方：
  - `errorText.ts` 不改。
  - 页面分支不改：`ResidentManager.tsx:165`、`PlotDetail.tsx:310` 看的是 `PLAYER_NEVER_JOINED`；`DistrictCards.tsx:100`、`PlotList.tsx:640/647/896` 看的是 `viewerBlock` 的值。这些名字都没变。

### 15.3 码表

每个码都要在 `WebUiErrorCodes` 登记成常量，放在新段落 `// ---- 自管区 (District_Backend_Design 第十五章) ----` 下，javadoc 写明抛出点和 params。`DistrictError` 枚举的 wire 值直接引用这些常量。

`{name}` 一律是库里的规范名；对方不在库里时，用截断到 64 字符的输入。

| 码 | params | 抛出点 | 文案 |
|---|---|---|---|
| `DISTRICT_NOT_FOUND` | districtId | 带 districtId 的全部动作 | 没有找到这个自管区，它可能刚被管理员解除绑定了 |
| `PLOT_NOT_FOUND` | districtId, plotId | D12–D16, D18–D20, D23–D25 | 没有找到这块地，页面可能过期了，请刷新 |
| `PERMISSION_DENIED`（复用） | action, requires | 区务长门、户主门、住户门 | 见下面的门文案 |
| `INVALID_PLAYER_NAME` | playerName | D3, D15 | 玩家 ID 只能由 3-16 位英文字母、数字或下划线组成 |
| `ALREADY_RESIDENT` | playerName | D3 | {name} 已经是本区住户了 |
| `RESIDENT_ELSEWHERE` | playerName, academyId, unbound | D3 | 未解绑学院：{name} 已经是{academyFullName}的成员。一个人只能属于一个学院，要转过来得先让原学院的区务长把 TA 移出。已解绑学院：{name} 已经是{academyFullName}的成员（这个学院的自管区已解除绑定，成员名单还在）。一个人只能属于一个学院 |
| `PLAYER_NEVER_JOINED` | playerName | D3, D15（**界面按此码弹出"仍要添加"**） | 没有找到 {name} 的登录记录 |
| `REASON_REQUIRED` | — | D4 | 请填写移出原因，它会写进操作记录 |
| `NOT_RESIDENT` | playerName（D20 为 districtId） | D4, D5 | {name} 不是本区住户 |
| | | D6 | {name} 还不是本区住户，请先把 TA 加进来再任命 |
| | | D20 | 只有本区住户能买本区的地块 |
| `RESIDENT_IS_WARDEN` | playerName | D4 | {name} 是本区区务长，要先由管理员撤销区务长才能移出 |
| `SYNC_NOTHING_TO_RETRY` | playerName, syncStatus | D5 | {name} 当前没有同步失败，不需要重试 |
| `SYNC_RETRY_FAILED` | playerName | D5 | 重试没有成功：{Flan 失败原因}。{name} 仍是"同步失败" |
| `WARDEN_NOT_APPOINTED` | — | D6 | 本区现在没有区务长 |
| `ALREADY_WARDEN` | playerName | D6 | {name} 已经是本区区务长 |
| `PERMISSION_ITEM_UNKNOWN` | permissionId | D9, D13 | 没有这一项权限，页面可能过期了，请刷新 |
| `REGION_RULE_NOT_IN_PLOT` | permissionId | D13 | 区域规则全区统一，只有管理员能在自管区里改，地块里不能单独改 |
| `PLOT_FROZEN` | plotId, code | D13–D16, D25 | {code} 冻结中，朋友和权限要等解除冻结后才能改 |
| | | D18 | {code} 冻结中，不能改范围；要先解除冻结或收回 |
| | | D19 | {code} 冻结中，不能删；要先解除冻结或收回 |
| | | D20 | {code} 冻结中，不能买 |
| `PLOT_VACANT` | plotId, code | D13–D16, D25 | {code} 现在空置，没有户主，不能加朋友或改权限 |
| `FRIEND_IS_OWNER` | playerName | D15 | 户主本人不用加成朋友：自己的地块本来就什么都能做 |
| `ALREADY_FRIEND` | playerName, suspended | D15 | {name} 已经是这块地的朋友了 / {name} 已经在朋友名单上（已暂停），点 TA 旁边的"恢复"就行 |
| `FRIEND_LIMIT_REACHED` | limit | D15 | 朋友已满 8 人，先移除一位再加 |
| `FRIEND_NOT_FOUND` | playerName | D16, D25 | {name} 不是这块地的朋友 |
| `FRIEND_NOT_SUSPENDED` | playerName | D25 | {name} 的朋友身份没有暂停，不需要恢复 |
| `INVALID_AREA` | field（类型错时） | D17, D18 | 坐标必须是整数 / 坐标必须是整数，且两个角要分得开 |
| `OUT_OF_DISTRICT` | minX, maxX, minZ, maxZ | D17, D18 | 超出自管区范围（X {minX} ~ {maxX}，Z {minZ} ~ {maxZ}） |
| `TOO_CLOSE_TO_EDGE` | edgeGap | D17, D18 | 离自管区边界太近：四周至少要留 {edgeGap} 格公共区域 |
| `SIZE_OUT_OF_RANGE` | minSide, maxSide, width, depth | D17, D18 | 每边要在 {minSide} 到 {maxSide} 格之间（现在 {w} × {d}） |
| `OVERLAPS_PLOT` | plotId, code | D17, D18 | 和地块 {code} 重叠了 |
| `PLOT_OCCUPIED` | plotId, code, ownerName | D18 | {code} 是 {owner} 的家，区务长不能改有户主的地块范围 |
| | | D19 | {code} 是 {owner} 的家，有户主的地块不能删 |
| | | D20 | {code} 已经被 {owner} 买下了（先到先得） |
| `PLOT_AREA_UNCHANGED` | plotId | D18 | 范围和原来一样，没有改动 |
| `ALREADY_OWNS_PLOT` | plotId, code | D20 | 你已经有一块地了，一人最多一块 |
| | | D23 | {name} 回来后已经有了 {code}，一人最多一块 |
| `HAS_FROZEN_PLOT` | plotId, code | D20 | 你原来的地块还在冻结中，请先找管理员解除冻结或收回 |
| `PURCHASE_CLOSED` | districtId | D20 | 本区暂未开放购买 |
| `PLOT_CHANGED` | plotId, code, minX, minZ, maxX, maxZ（现在的范围） | D20（排在 `PRICE_CHANGED` 之前，复核后补） | {code} 的范围刚被调整过，现在是 {w} × {d}（X {minX} ~ {maxX}，Z {minZ} ~ {maxZ}），请看清新的范围和价格再确认 |
| `PRICE_CHANGED` | expectedPrice, price | D20 | 价格刚变了，现在是 {formatCredit(price)}，请按新价格重新确认 |
| `INSUFFICIENT_FUNDS`（复用） | cost, currency, balance | D20 | 余额不足：还差 {formatCredit(price−balance)} |
| `ECONOMY_OFFLINE`（复用） | — | D20 | 经济系统未就绪（`errorText.ts` 已有这个码的文案） |
| `INVALID_PRICE` | field | D21 | 每格单价要是正整数（上限 1,000,000） |
| `INVALID_SIZE_LIMIT` | field | D21 | 尺寸上下限要是正整数，且下限不能大于上限（上限 1024） |
| `PLOT_NOT_FROZEN` | plotId, code | D23 | {code} 没有冻结 |
| | | D24 | {code} 没有冻结；只有冻结中的地块能立即收回 |
| `FORMER_OWNER_NOT_RESIDENT` | playerName | D23 | 原户主 {name} 现在不是本区住户，先把 TA 加回本区才能解除冻结 |
| `STORE_FAILED`（复用） | — | 全部写动作 | 数据库读写失败，这次操作没有生效 |
| `DISTRICT_DISABLED` | — | 全部 26 条动作（取服务时的统一前置门，阶段 2 补，20.9） | 自管区功能没有开启 |

**门文案**（`PERMISSION_DENIED` 的 message）：

| 门 | 文案 |
|---|---|
| D3、D4 的管理门 | 只有本区区务长或管理员可以管理住户 |
| D2 | 你不是这个自管区的住户，只能看公开信息 |
| D9：不是管理者 | 只有本区区务长或管理员可以改本区公共区域的权限 |
| D9：区务长改区域规则 | 区域规则只有管理员可以改 |
| D10：不是管理者 | 只有本区区务长或管理员可以恢复本区的默认权限 |
| D10：区务长用 `all` | 区域规则只有管理员可以改，区务长只能恢复住户和外人的开关 |
| D11 | 只有本区住户能看本区的地块和户主 |
| D12–D16、D25：调用者是本区区务长 | 别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限 |
| D12 其余人 | 只有户主本人和管理员能看这块地的朋友和权限 |
| D13–D16、D25 其余人 | 只有户主本人和管理员能改这块地的朋友和权限 |
| D17–D19 | 只有本区区务长和管理员可以划地块 |
| `admin.*` | `requireOp` 的"需要 OP 权限" |

新增常量一共 39 个（第二步落地时 37 个，复核后加了 `PLOT_CHANGED`，阶段 2 加了 `DISTRICT_DISABLED`）。和其余自管区的码一样，`PLOT_CHANGED` 不进 `errorText.ts`，界面直接显示服务端原文（15.1 第 3 条、TODO 21.7）。

---

## 十六、OP 引导命令 `/district` (DECIDED)

- 根节点要求 `source.hasPermission(2)`，所以玩家和控制台都能用。
- 玩家名一律用 `StringArgumentType.word()` 读入，由 10.2 的规则解析。**不用 `GameProfileArgument`**：它会走会阻塞的名字查询，在 GameTest 服上还会遇到空指针。
- 坐标用 `ColumnPosArgument`，支持 `~`。维度默认取执行者所在的世界（控制台为主世界），也可以显式写。
- 反馈：成功时 `sendSuccess(..., true)` 广播给所有 OP；失败时 `sendFailure`。文案用语言键 `district.miningdim.command.*`。
- 门面没有绑定时回复 `district.miningdim.command.not_ready`，并返回 0。
- 每条会改动数据的命令写一行审计 INFO。

| 命令 | 作用 | 调用 |
|---|---|---|
| `/district academy list` | 列出学院：id、简称、全称、有没有在用的自管区、成员数 | 查询 |
| `/district academy members <academyId>` | 列出学院成员和生效状态，已解绑学院也能查 | 查询 |
| `/district academy kick <academyId> <name>` | 把人移出**已解绑**学院的名单（12.4） | `DistrictAdminService.kickFromUnboundAcademy` |
| `/district create <academyId> <from> <to> [<dimension>] [<unitPrice>]` | 为学院建一个自管区（12.3 的 districtId 规则），初始化开关表，单价默认 5，边长 8–48，购买关闭；网关执行 `createDistrictClaim`；绑定已有领地用 `/district bind`（20.6、20.8） | `DistrictAdminService.createDistrict` |
| `/district bounds <districtId> <from> <to>` | 改自管区范围，只改库。有地块会落到新范围外或离边界不足 2 格时，拒绝并列出这些地块（A15） | `setBounds` |
| `/district rules <districtId> add <text…>` / `remove <index>` / `clear` | 维护区规。中文只能在控制台输入 | `setRules` |
| `/district warden <districtId> set <name>` / `clear` | 任命或撤销区务长，与 D6 同一规则、同一记录 | `setWarden` |
| `/district residents <districtId> add <names…>` | 批量加住户。名字用空格或逗号分隔，逐人走 D3 的规则，逐人回报结果；从没进过服的人被拒 | `addResident` |
| `/district residents <districtId> addUnseen <names…>` | 同上，但允许从没进过服的人，以 pending 记入 | `addResident(allowNeverJoined=true)` |
| `/district list` | 列出全部自管区：id、学院、范围、区务长、人数、地块数、是否开放购买 | 查询 |
| `/district info <districtId>` | 查看一个区：区规、定价、名单概况、同步问题计数、最近 10 条记录 | 查询 |
| `/district plots <districtId>` | 列出全部地块（不受回执预算限制）：编号、状态、户主、范围、生效状态 | 查询 |
| `/district sweep` | 立即执行一次到期收回，回报收回了几块 | `reclaimExpired` |
| `/district resync <districtId>` | 重推本区全部 Flan 状态，回报成功和失败的数量 | `DistrictFlanSync.resyncDistrict` |

阶段 2、3 加的命令（`status`、`bind`、`claim … recreate / relink`、`bounds … sync`、`inspect`、`machines`、`personalclaims`）见 20.8。

几点说明：

- 解绑、定价、开放购买、冻结处置只在平板上做，那里有确认框。命令只负责引导和排障。
- 建区和改范围时，自管区不能和同一维度里其他**未解绑**的自管区重叠，也不能压到**别的学院已解绑**自管区的范围（同一个学院重新绑回自己原来的地放行，12.3），否则拒绝。
- 建区这一类命令专属的失败（学院不存在、学院已有自管区、范围重叠、范围非法）由服务返回结果对象，直接用语言键反馈，**不进** `WebUiErrorCodes`，因为它们从不走平板。
- 第二步落地时定下的细节（以 `DistrictCommands` 为准）：
  - 服务层的业务拒绝（`DistrictRuleException`）一律经语言键 `district.miningdim.command.rejected` 把中文原文转出去；批量加住户逐人回报用 `residents.rejected`（名字 + 原文），最后一行 `residents.summary` 汇总。
  - 区规上限：一个区最多 20 条，每条最多 200 字（`DistrictLimits.MAX_RULES` / `MAX_RULE_CHARS`）。区规随 D2 整张下发，不设上限就守不住回执体积。
  - 批量加住户一次最多 50 个名字（`MAX_BULK_NAMES`），名字用空格、半角或全角逗号分隔，重复的名字只算一次。
  - `bounds` 只改平面范围，不改维度（换维度等于重新建区）。
  - 返回值：查询类返回列出的条数（至少 1：一条都没有也是执行成功，例如没有任何自管区时的 `/district list`）；`residents add` 返回加入的人数；`sweep` 返回收回的块数（至少 1，具体块数也在反馈里）；`resync` 返回成功的项数（至少 1）；一切拒绝返回 0。

---

## 十七、前端：从 planned 挪到真契约 (DECIDED 步骤；PENDING 时机)

### 17.1 阶段 1 后端提交时，前端一行不动

服务端登记了 26 条 action，而前端的 `SERVER_ACTIONS` 里没有它们，握手时会把这些报成 `unknownToClient`。按现有规则，这**不算**不兼容。自管区页面在开发构建里继续走内存假世界，生产构建里仍然硬失败（`NOT_WIRED`）。

### 17.2 接线提交

接线单独做一个提交，按顺序：

1. **类型**：
   - 把 D 组类型从 `mock/planned.ts` 挪进 `webui/src/lib/types.ts`，去掉 `Planned` 前缀，例如 `PlannedDistrictResident` 改为 `DistrictResident`。
   - 注释改写成"字段在 Java 哪里产生"。
   - 加上 14.2 的 ★ 附加字段，按 15.2 改注释里的码名。
   - 页面里 `import type { Planned… } from '@/mock'` 改成从 `@/lib/types` 引入。
2. **planned 表**：从 `PLANNED_ACTIONS` 和 `PlannedContractMap` 里删掉 26 条。`AssertPlannedCoverage` 保证两边一起删。
3. **真契约**：
   - 26 条加进 `lib/bridge.ts` 的 `WebUiContractMap`，由 `AssertContractCoverage` 校验。
   - 同时按 `Collections.sort` 的字典序插进 `lib/actions.ts` 的 `SERVER_ACTIONS`。
   - 把该文件头的注册点清单从"二十五个"改成"二十六个"，并补一行 `district.* / plot.* / admin.district.* / admin.plot.*   DistrictWebUiActions`。
4. **开发构建的假数据照旧可用**：
   - `lib/bridge.mock.ts` 的 `resolveMock` 加 26 个 case，全部转给 `mock/district-handlers.ts` 新导出的 `resolveDistrictMock(action, payload)`。
   - `bridge.mock.ts` 已经在引用 `../mock/store`，再引用 district-handlers 不会产生新的层级依赖。
   - 这样开发构建（无宿主）仍然由内存世界回答，预览身份切换器（`PreviewRoleSwitcher`，受 `isMockActive()` 门控）和 `DistrictPage` 的"换身份即作废查询"都照常工作。
   - `district-handlers.ts`、`district-seed.ts`、`district-geometry.ts` 三个文件的头注释要改：从"接线时整份删除"改成"`bridge.mock` 里 `district.*` 的假实现，规则以 Java 为准"。
   - `reject()` 改成与 `bridge.mock` 的 `businessFailure` 同形，并按 15.2 发服务端码。
   - `resolveMock` 的 `default: never` 分支保证 26 条一条不漏。
5. **`mock/handlers.ts`**：
   - 删掉 `PLANNED_HANDLERS` 里的 `...DISTRICT_HANDLERS` 展开。删掉之后，`callMock` 对这些 action 自动走 `delegateReal` → `call()`，页面代码一行不改。
   - 把 `'plot.buy'` 加进 `BUMP_REVISION_AFTER`。买地只动钱包、不动背包，要叫醒顶栏的余额重查。
6. **导航入口先不放开**：
   - `TabletShell.tsx:109` 的 `previewOnly: true` 保留，导航入口仍然只在开发构建里显示（PENDING P15）。
   - `HubWebUiActions.PANEL_IDS` 也暂时不加 `district`。
7. **文档**：
   - 在 `docs/WebUI_Frontend_Wiring_Checklist.md` 第三章新增 **"K 组 · 自管区"**，K1–K26 与 D1–D26 一一对应，每行写明状态、Java 位置和备注。
   - 不能沿用 D 字母：已有"D 组 · 经济"。
   - 删掉 planned.ts 里 D 组的段头说明。

第二步 (2026-09-29) 已按上面七步做成独立的接线提交，落地时的几处细节：
- 类型搬进 `lib/types.ts` 末尾的自管区一节，注释里的编号一并改成 K1–K26；段头改写成"字段在 Java 哪里产生"（handler 常量与 `DistrictJson` 方法的对照），原 D 组段头的"已拍板前提"保留在那里。
- ★ 字段在类型里是必有键（服务端恒写出）：假实现一律回 false 或实际计数。两处恢复默认的提示改读 ★ `changedCount`（`PlotDetail.tsx`、`PermissionSettings.tsx`）。
- `mock/district-handlers.ts` 按 15.2 发服务端的码，拒绝带与真服同形的 params（身份门 `{action, requires}`、管理员门 `{action}` 与文案"需要 OP 权限"、形状错误 `{field[, value]}`）；另补了 D10 `scope` 的取值校验与"区务长买地记 resident"（P3），与真服对齐。
- 页面代码只动了类型的 import 来源与上面两处提示；导航入口的 `previewOnly` 保留（20.9）。

### 17.3 check:contract 与批量

- `pnpm -C webui check:contract` 的 11 条检查里，唯一和后端有关的是 `batchable-whitelist-parity`。自管区动作一条都不进 `BATCHABLE`，两侧名单都不变，这条自然通过。
- 服务端 `WebUiBatchActionGameTests.noWriteActionIsWhitelisted` 的 `writes` 名单，要加入 20 条自管区写动作。
- 读动作都是纯读取，将来想批量时，按 `WebUiBatchAction` 的四步流程再加。
- 类型覆盖由 `pnpm build`（`tsc --noEmit`）把关：`AssertPlannedCoverage`、`AssertContractCoverage`，以及 `resolveMock` 的穷尽检查。
- 接线提交必须让 `pnpm build`、`pnpm lint`、`pnpm check:contract` 全部通过。

### 17.4 发布顺序（重要）

握手检查会把 `missingOnServer` 非空判定为**整个平板不兼容**，不只是自管区页打不开。

因此，接线后的前端只能对着**已经装了 wok-district 的服务端**发布。凡是 `webui.url` 指向的前端版本会被没装这个模块的服务端加载的情况，接线提交都要等后端上线之后再发布。

编号口径：`D1`–`D26` 这几个编号和 Java 注释里已有的"决策 D2/D4/…"、"D5 跨死亡"会撞名；改用接线清单的 `K1`–`K26` 也不行，职业模块的注释里已有"K1"–"K5"（另一份契约的编号）。所以 Java 的注释和断言文案里一律写 action 名（`district.detail`、`admin.district.delete` 这样），不写任何编号。`DEPENDENCY_DEBT.md` 只扫描三位数的 `D###`，这里的编号不会触发它的校验。

---

## 十八、GameTest 计划 (DECIDED)

### 18.1 约定

- 按仓库惯例写：`@GameTestHolder(MiningConstants.MODID)` + `@PrefixGameTestTemplate(false)`，模板用 `empty`，每个类一个 snake_case 的 batch。
- 断言用 `helper.assertTrue(cond, "中文说明, 实得 " + actual)`，断言精确值，不写"没抛异常"这种断言。
- **测试夹具 `DistrictTestEnv`**（`AutoCloseable`）：
  1. 用 `SqliteEconomyLedger.openInMemory()` 建库，仓储也挂在 `ledger.connection()` 上，保证两者是同一条连接。
  2. 经济门面用 `new EconomyService(ledger, new AbuseGuard(), resolver)`，只经 context 的 supplier 注入，不动全局的 `EconomyServices`（`openWithoutEconomy()` 让 supplier 返回 null）。
  3. 网关用 `RecordingFlanGateway`（`openWith(gateway)` 可换成别的实现），时钟用 `AtomicLong`。
  4. `PlayerDirectory` 用 `ServerPlayerDirectory.seenTableOnly`：只查见过的玩家表，测试用 `env.seen(name)` 登记"进过服的名字"。
  5. 替换 `DistrictServices`，在 close 时复原门面并关闭库。
- **故障注入**：事务原子性用测试库上的 TEMP 触发器（`RAISE(ABORT)`）让某张表的写入失败，不另写一个抛异常的仓储包装类。
- 需要持久化的测试改用 `TempStoreDb.openUnified`。
- **玩家**：
  - 用 `MockGameTestPlayers.makeMockServerPlayerWithChannel(helper, new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name))` 创建，名字各不相同。
  - 在 `finally` 里调 `server.getPlayerList().remove(player)`。
- **OP**：
  - 用 `server.getPlayerList().op(profile)` 设置，在 `finally` 里 `deop`。
  - 先断言新建的 mock 玩家不是 OP。
- **平板测试**：
  - 走真实注册的 handler：`JsonParser.parseString(WebUiServerDispatcher.resolve(action).handle(sender, payload))`。
  - 拒绝用 `rejection(...)` 断言 `errorCode` 和 `params`。
  - **测试代码里不注册 action**：生产接线漏了，测试就应该失败。
- **运行**：
  1. 先删 `run/world`：复用旧世界时，番茄那条测试会失败，这是已知问题。
  2. 设置 `JAVA_HOME=D:\DevTools\jdk-17`。
  3. 执行 `gradlew compileJava verifyModuleBoundaries runGameTestServer`。

### 18.2 清单

| 类（batch） | 测试 |
|---|---|
| `DistrictSchemaGameTests`（`district_store`） | `v9CreatesEveryDistrictTableAtUserVersion9`、`oneAcademyPerPlayerByUuid`、`caseInsensitiveNameIsUniqueAcrossAcademies`、`onePlotPerOwnerAcrossDistricts`、`ownerAndFrozenOwnerAreExclusive`、`newPlotMustBeVacant`、`ownerMustBeMemberOfTheAcademy`、`wardenMustBeMemberOfTheAcademy`、`memberWithPlotOrWardenshipCannotBeDeleted`、`onlyOneLiveBindingPerAcademy`、`unboundDistrictHasNoWarden`、`fullStateSurvivesReopen`（落盘：区、名单、地块、朋友、两类记录、墓碑、归档、见过的玩家）；另在 `MiningStoreGameTests` 补表名单 |
| `DistrictRoleGameTests`（`district_roles`） | `opIsAdminEvenWhenOnRoster`、`wardenOfAnotherDistrictHasNoAccess`（D2/D11/D12 被拒，D8 按外人裁剪，D3/D4/D9/D17/D18 报 PERMISSION_DENIED）、`unboundAcademyMemberIsOutsiderWithoutResidency`、`abilitiesMatrixPerAccess`、`buildAndInteractFollowOwnSyncStatus`、`opOwningAPlotActsAsOwnerNotOverride` |
| `DistrictResidentGameTests`（`district_residents`） | `addResidentChecksInOrder`、`neverJoinedNeedsConfirmationThenPending`、`caseInsensitiveDuplicateIsAlreadyResident`、`residentElsewhereLiveAndUnboundMessages`、`canonicalNameAndUuidAreStored`、`knownPlayerSyncedOnlyAfterGatewaySuccess`、`gatewayFailureLeavesFailedWithError`、`reAddReturnsFrozenPlotWithoutUnfreezing`、`removeResidentChecksInOrder`、`wardenCannotBeRemovedEvenByAdminNorBySelf`、`removingOwnerFreezesPlotAndLogsInOrder`、`plotLogNeverContainsRemovalReason`、`violationSuspendsOnlyThisDistrictIncludingFrozenNotOwn`、`friendCountsForViolationAndOtherReasons`、`removalDropsPlayerFromAllPlotResidentGroups`、`retrySyncAdminCheckPrecedesDistrictLookup`、`retrySyncRejectsPending`、`retrySyncFailureKeepsFailedAndThrows`、`setWardenRulesAndReplaceWritesRevokeThenAppoint`、`missingWardenKeyIsInvalidRequestNotRevoke` |
| `DistrictUnbindGameTests`（`district_unbind`） | `unbindKeepsRosterPlotsAndArchivesLog`、`unboundDistrictIsNotFoundEverywhere`、`unboundRosterStillBlocksOtherAcademies`、`unbindMakesNoGatewayCalls`、`expirySkipsUnboundDistricts`、`archiveNewestFirstAndCapped`、`rebindCreatesNewDistrictIdAndRestartsNumbering`、`kickFromUnboundAcademyClearsArchivedOwnership` |
| `DistrictPermissionGameTests`（`district_permissions`） | `permissionsMaskingMatrix`（外人 / 住户 / 区务长 / 管理员 × 住户列、flanIds、两种风险、editable）、`setPermissionRejectsMissingNullAndStringValues`、`setPermissionChecksInOrder`、`unchangedValueReturnsNullLogAndWritesNothing`、`regionRuleByWardenDenied`、`resetWritesOneRowPerChangedCellWithRestoreReason`、`resetWithNothingToChangeReturnsEmpty`、`resetAllIsAdminOnly`、`unknownScopeIsInvalidRequest`、`mobSpawnIsWrittenInverted` |
| `PlotLayoutGameTests`（`district_plot_layout`） | `geometryCodesInOrder`、`touchingIsAllowedSharingACoordinateOverlaps`、`edgeGapOfExactlyTwoPasses`、`overlapChecksFrozenPlots`、`numbersAreNeverReusedAfterDelete`、`createWritesEveryCellExplicitlyWithPlotUniqueGroups`、`createdPlotHasNoInheritedGroups`（假网关的浅拷贝模型）、`wardenCannotResizeOwnedPlot`、`adminResizeOfOwnedPlotIsOnBehalfAndNotified`、`resizeWithSameAreaIsUnchanged`、`frozenPlotCannotBeResizedOrDeleted`、`deleteMovesWholeLogIntoTombstone`、`tombstonesAreAdminOnly` |
| `PlotMarketGameTests`（`district_plot_market`） | `buyChecksInOrder`、`opIsAlwaysNotResident`、`viewerBlockOrderMatchesBuyChecks`、`priceChangedWhenUnitPriceMoved`、`insufficientFundsWritesNothing`、`economyOfflineIsReported`、`successDebitsAssignsAndLogsBoth`、`debitAndAssignmentAreAtomic`（注入故障的仓储在记录写入时抛异常，断言余额和户主都没变）、`secondBuyerGetsOccupied`、`balanceAfterMatchesLedger`、`gatewayFailureKeepsPurchaseAndMarksPlotFailed` |
| `PlotOwnerGameTests`（`district_plot_owner`） | `detailRelationsOwnerAdminWardenMessage`、`editableFalseForVacantAndFrozen`、`ownerSeesCurrentTenureAdminSeesArchive`、`gateOrderFrozenBeforeVacant`、`adminChangesAreOnBehalfOfOwner`、`alreadyFriendWhenSuspendedUsesRestoreMessage`、`friendLimitCountsSuspended`、`ownerCannotBeFriend`、`restoreRequiresSuspended`、`removeFriendIsCaseInsensitive`、`suspendedFriendFallsBackToResidentGroup`、`pendingFriendIsNotInFriendGroup` |
| `PlotFreezeGameTests`（`district_plot_freeze`） | `unfreezeRestoresFriendsAndPermissionsExactly`、`unfreezeNeedsFormerOwnerOnRoster`、`unfreezeRejectsFormerOwnerWithAnotherPlot`、`reclaimNowArchivesLogAndStartsNewTenureWithVacate`、`expiryByInjectedClockUsesSystemActorAndFixedReasons`、`writeActionSweepsBeforeValidation`（到期后调 D23 → PLOT_NOT_FROZEN）、`readsNeverSweep`、`tickSweepRunsEvery1200Ticks`、`frozenPlotIsWrittenAllOff` |
| `DistrictFirstLoginGameTests`（`district_first_login`） | `loginRecordsSeenAndLogoutUpdatesLastSeen`、`pendingResidentBecomesSyncedOnFirstLogin`、`pendingResidentBecomesFailedWhenGatewayFails`、`pendingFriendBecomesSyncedAndPlotRewritten`、`caseVariantNameIsRekeyedIncludingWarden`、`firstLoginWritesNoLogRows`、`unboundDistrictsAreSkipped`、`backfillReadsPlayerDataOnce`（用临时目录测纯函数） |
| `DistrictWebUiGameTests`（`district_webui`） | `all26ActionsAreRegistered`、`nullableFieldsAreSerializedAsNull`、`noDistrictWriteIsBatchable`、`storeFailureMapsToStoreFailed`、`everyThrownCodeIsARegisteredConstant`（反射核对 `WebUiErrorCodes`）、`echoedNamesAreTruncated`、`responsesStayUnderCapForALargeDistrict`、`truncationFlagsAreSet` |
| `DistrictCommandGameTests`（`district_commands`） | `commandsRequirePermissionLevel2`（照 `CustomTitleGameTests` 的解析断言）、`createSeedsPermissionsAndDefaults`、`createRejectsOverlapAndSecondLiveBinding`、`bulkAddReportsPerName`、`addUnseenRecordsPending`、`wardenSetAndClearWriteLogs`、`boundsRejectsWhenPlotsFallOutside`、`sweepAndResyncReportCounts` |
| `DistrictFlanSyncGameTests`（`district_flan_sync`） | `ownedPlotGroupsFollowPriority`、`vacantPlotHasOnlyResidentGroup`、`regionPermissionsAreNeverWrittenOnPlots`、`adminPermissionsAreExplicitlyFalse`、`residentMembershipTouchesEveryPlot`、`recordingGatewayRejectsEditPermsTrue` |

预计约 120 个测试。

第一步 (存储与领域层) 的实际落地以代码为准，共 12 个类、120 条 GameTest，另有 `MiningStoreGameTests` 的 V8 升级测试。与上表的出入：
- 几条合并或改名：`alreadyFriendWhenSuspendedUsesRestoreMessage` / `friendLimitCountsSuspended` / `restoreRequiresSuspended` 合成 `friendLimitCountsSuspendedAndRestoreMessage`；`resizeWithSameAreaIsUnchanged` 并入 `vacantResizeByWardenAndUnchangedArea`；`tombstonesAreAdminOnly` 并入 `deleteMovesWholeLogIntoTombstone`；`unfreezeNeedsFormerOwnerOnRoster` 与 `unfreezeRejectsFormerOwnerWithAnotherPlot` 合成 `unfreezeChecksInOrder`；`balanceAfterMatchesLedger` 并入 `successDebitsAssignsAndLogsBoth`；`recordingGatewayRejectsEditPermsTrue` 扩成 `recordingGatewayRejectsInvariantBreaks`。
- 新增：`DistrictQueryGameTests`（`district_queries`，D1 / D2 / D11 快照的计数与派生字段）、`DistrictFlanSyncGameTests` 里的 `disabledGatewayReportsNotEnabled`、`plotFailureHealsOnNextWrite`、`resyncRewritesEverythingAndCounts`，以及 `playerDataFallbackRecognizesOldPlayers`、`permissionWriteFailureFlagsReconcile`、`alreadyOwnsAndFrozenPlotBlocks`、`wardenBuyingIsLoggedAsResident`。

第二步 (平板动作与命令) 的实际落地：3 个类、30 条 GameTest，模块合计 15 个类、150 条。
- `DistrictWebUiGameTests`（`district_webui`，14 条）：上表 8 条里 `noDistrictWriteIsBatchable` 改成 `noDistrictActionIsBatchable`（经真实的 `system.batch` 核 26 条全部逐条拒，读动作阶段 1 也不进批）；另有 `archiveStaysUnderCapAndFlagsTruncation`（D26 单独一条）、`missingWardenKeyIsInvalidRequestNotRevoke`、`switchValuesMustBeJsonBooleans`（原 `setPermissionRejectsMissingNullAndStringValues`，并覆盖 D13 / D22 与枚举取值）、`machineFieldsAreCheckedBeforeBusinessRules`、`permissionsMaskingMatrix`（外人 / 别区区务长 / 住户 / 本区区务长 / 管理员五档）、`frozenPlotShapesPerViewer`。
- `DistrictActionRoleGameTests`（`district_action_roles`，6 条）：按身份经派发器各走一遍放行与拒绝的路径和回执形状 —— `adminReachesEveryActionAndSeesAdminOnlyFields`、`wardenManagesResidentsPublicAreaAndVacantPlotsOnly`、`wardenOfAnotherDistrictIsAnOutsiderHere`、`residentWithPlotManagesOnlyTheirOwnPlot`、`residentWithoutPlotBuysAfterAdminOpensPurchase`、`outsiderSeesOnlyPublicInformation`。
- `DistrictCommandGameTests`（`district_commands`，10 条）：上表 8 条，另有 `rulesListInfoAndPlots` 与 `kickOnlyWorksOnUnboundAcademies`。
- 平板测试的共用工具在 `web/DistrictWebTestSupport`（经派发器调用、断言拒绝码、`Cast` 管在线 mock 玩家与 OP 的生命周期）。

第二步之后的复核修正（2026-09-29）加了 2 条，模块合计 152 条：
- `DistrictResidentGameTests.removalIsAtomicAndPushesNothingOnRollback`：6.6 的 D4 事务此前没有注入故障的测试（只有买地和加住户有）。TEMP 触发器让事务的最后一笔写入（受影响地块先写的临时失败，排在登记提交后推送之后）失败，断言名单行、冻结、朋友暂停、本区与地块记录全部回滚，假网关一次调用都没收到；去掉触发器后重试照常成功。
- `DistrictWebUiGameTests.removeReasonLengthIsCappedBeforeAnyWrite`：区务长经真实 handler 发 32000 字、6000 个 `<`、201 字的原因都报 `INVALID_REQUEST {field: reason}` 且一行不写；去掉首尾空白后恰好 200 字照常移出。

第二轮复核修正（2026-09-29）加了 7 条，模块合计 159 条，没有新增文件：
- `PlotMarketGameTests.plotChangedWhenWardenMovesOrReshapesVacantPlot`：买家确认期间区务长把空置地块挪走、改成同面积的另一个形状、再改大，三次都报 `PLOT_CHANGED`（排在 `PRICE_CHANGED` 之前），被拒时不扣钱、不过户、不写记录；按现在的范围与价格才买得成（6.5）。平板一侧另在 `DistrictWebUiGameTests.machineFieldsAreCheckedBeforeBusinessRules` 锁住 `expectedBounds` 的形状错误，在 `DistrictActionRoleGameTests.residentWithoutPlotBuysAfterAdminOpensPurchase` 走一遍"把列表那一行的 bounds 原样送回来"。
- `DistrictFlanSyncGameTests.recordingGatewayIsGameTestServerOnly`：记录型网关只在 GameTest 服务端上选得上（8.5）。
- `PlotFreezeGameTests.formerOwnerIsMatchedByUuidAndNameOnlyForPendingRows`：按 UUID 认人，名字只认待生效行（10.7）。
- `PlotFreezeGameTests.expiryFailureOfOnePlotDoesNotBlockOthers`：到期收回每块地一个事务，中间一块注入故障只回滚它自己（第十一章）。
- `DistrictUnbindGameTests.archivedLandBlocksOtherAcademiesButNotItsOwnRebind`：已解绑区的范围挡住别的学院建区与改范围，同一个学院重新绑回自己的地放行（12.3）。
- `DistrictUnbindGameTests.removalAfterRebindReleasesArchivedOwnership` 与 `kickAfterRebindWaitsUntilTheNewBindingIsGone`：重新绑定之后的两条完整性路径（6.6 第 5 步、12.4），在服务层各走一遍。
- 另在已有用例里补了断言：`DistrictSchemaGameTests.wardenMustBeMemberOfTheAcademy` 锁住"插入时带区务长一律拒绝"（4.3），`DistrictCommandGameTests.rulesListInfoAndPlots` 锁住一个区都没有时 `/district list` 返回 1（第十六章）。

阶段 2 的真 Flan 用例见 20.10；阶段 3 的守卫与通知用例见 22.14，实际落地的差异见 22.17。

---

## 十九、本文替服主定的默认值与待确认项 (PENDING)

先按下表的默认值实现，服主拍板后再回写本文。

| 编号 | 事项 | 默认 | 备注 |
|---|---|---|---|
| P1 | 大小写不同的名字是否一定是同一个账号 | 按小写名字匹配 pending 行并换键 | 依赖 AccessHub 保证"一名一号"（10.5） |
| P2 | 学院重新绑定 | 新 `districtId` 为 `<academyId>-<n>`，地块编号从 1 开始 | 旧的 Flan 子领地：阶段 2 已定，`/district bind` 收编旧的父领地时删掉（20.6） |
| P3 | 区务长自己买地时记录里的身份 | 一律记 `resident` | 参考实现会记成 `warden`；按契约"住户买地"的口径改 |
| P4 | 新区默认值与业务上限 | 单价 5，边长 8–48；单价 ≤ 1,000,000，边长 ≤ 1024 | 只在代码里，不进 CHECK |
| P5 | OP 上名单、当区务长 | 允许，不特殊处理 | OP 的全局身份恒为 admin，不能买地（只能经解冻拿到地） |
| P6 | Flan 写失败时是否退还地价 | 不退，地块显示 `failed`，之后自动补写 | — |
| P7 | 地块多于约 70 块的区 | D11 截断，附 ★ `plotsTruncated` | 真正要解决需要契约分页 |
| P8 | 朋友上限、离边界格数 | 8 人、2 格 | 契约本身也标着待拍板 |
| P9 | 买地的钱去哪 | 直接销毁 | 钱仓另案 |
| P10 | 阶段 1 生产环境用哪个网关 | Disabled（如实报"未启用"） | 记录型网关只在 GameTest 服务端上可用（8.5），系统属性在别处被忽略。阶段 2 已定：功能生效时用 `FlanClaimGateway`，降级时用 Disabled 并带原因（20.2） |
| P11 | D5 重试失败 | 新码 `SYNC_RETRY_FAILED` | 契约里没有这条 |
| P12 | 移出已解绑学院的成员 | 只有命令能做 | 平板上没有入口 |
| P13 | D22 的记录文案 | `开放购买` / `暂停购买` | 契约注释写的是"开放 / 关闭"，按参考实现 |
| P14 | 1 级 OP | 面板认、游戏里不绕过 | 守则写"员工不进 ops 名单，OP 一律 2 级以上" |
| P15 | 导航入口什么时候对玩家开放 | 阶段 2 真 Flan 接通、测试服实测之后 | 阶段 2 已定：由服务端开关 `enabled` 控制，经 `hub.panels` 下发；测试服按 20.12 实测后由服主打开（20.9） |
| P16 | 目录外 Flan 权限的取值 | 按 20.3 的跟随表与固定值 | 跟随关系不进界面文字；未知权限户主真、其余假 |
| P17 | 绑定已有领地时，其上的子领地、组和成员 | 全部删掉，先预览、再 `confirm` | 不沿用旧子领地（20.6） |
| P18 | 自动对账遇到外来子领地 | 只报告，`/district resync` 才删 | 本模块留下的孤儿（有墓碑）自动删（20.4） |
| P19 | 父领地与地块上的药水效果、假玩家白名单 | 一律清掉 | 面板里没有这两样东西，留着就是没人管的状态 |
| P20 | 显式存盘 | 只在建、绑、重建父领地之后 | 其余靠自动存档与开服对账（20.5） |
| P21 | 开关开着但 Flan 不可用（降级）时 | 导航隐藏；命令照常；平板只读：6 条读动作照常，20 条写动作回 `DISTRICT_DISABLED`（带降级原因） | 2026-09-30 复核改：原默认"服务照常，领地写入如实报失败"会让库照写、Flan 不跟着改（买地扣钱却没有保护，移出、删地块、冻结之后旧权限还留着）。也可以改成与关闭相同（20.2） |
| P22 | 开发环境怎么加载真 Flan（探路结果 S3，20.1） | 出路 ③（2026-09-30 B 步）：从批准的 Flan jar 里原样取出内嵌的 lingua_bib，与 Flan 一样经 `fg.deobf` 作为开发运行时的根 mod 加载；真 Flan 用例进默认门 | 批准的 Flan jar 一个字节不改，也不从别处引入构件，所以没有走要批准的 ①②。服主要否决的话，删掉 `build.gradle` 里两行 `runtimeOnly` 与 `extractFlanDevLibs` 即回到 S3：GameTest 服务端照常起来，真 Flan 的用例在门口失败，两处"有 Flan"的断言失败，其余照常（20.1、20.10）。`-PwithoutFlan` 可以不删代码就跑一轮没有 Flan 的 |
| P23 | 机械动力的装饰方块放不放 | 放行没有方块实体、也不会转的方块；另按两张名单微调（拒 4 个、放 9 个） | 服主可在 `[district.createBan]` 里增删。候选但默认不放：数码管、三种铃、三种桌布、工具箱（22.4） |
| P24 | 绑定前就在区内的机械动力方块 | 不拆、不停转；非 OP 不能右键操作（`denyUse = true`）；`/district machines` 列给 OP | 22.8 |
| P25 | OP 放的机器、OP 建的装置 | 与别人的一样改不了自管区里的方块，也不能在区内组装装置；OP 只在"亲手放置"上例外 | 照服主"方块自己动的机关不开例外"的口径（22.6） |
| P26 | 流体越界拦到哪 | 只拦同一个区里的地块边界；区的外沿交给 Flan 的"水和岩浆越界" | 严格口径会悄悄盖过那个开关（22.2） |
| P27 | 通知队列的迁移 | 改 V9（没有发布过），不开 V10 | 用阶段 1–2 构建开过的开发存档缺这张表：开服降级为只读，照日志手工补建两条语句后重启，**绝不删库**（统一库里是全部模块的数据；22.11、22.19） |
| P28 | 开放购买的通知 | 只发在线、没有地块的住户，不入队 | 离线补发时可能已经暂停购买（22.10） |
| P29 | 通知的保留 | 每人最多 30 条，保留 30 天 | 22.11 |
| P30 | 机械动力的构件 | 不加编译期依赖，也不进开发运行时；离线核对注入点 + 阶段 4 实测 | 要加的话坐标见 22.6、22.14，需下载、需服主批准 |
| P31 | 别的 mod 的假玩家 | 交给 Flan 的 `fake_player`（主人自己的机器在自家地块里能动） | 只有机械动力的假玩家一律拦（22.5） |
| P32 | 自管区内和外围 8 格内禁止个人圈地 | 已做（设计 22.20，实现记录 22.21）：挡 Flan 的 `createClaim`、`resizeClaim`（只管个人领地），以库为准，OP 例外（2 级且不是假玩家） | 原默认"仍未实现，打开 `enabled` 之前要么做、要么删掉界面上那半句"。界面上那半句从此属实，不用删（22.13） |
| P33 | "开放购买"广播的节流 | 同一个区 10 分钟内至多广播一次；操作人自己不收 | B 步加的（22.18）：管理员开了又关、关了又开不重复刷屏。常量 `DistrictLimits.PURCHASE_NOTICE_COOLDOWN_MS` |
| P34 | 违反区规移出时，被暂停朋友的地块正冻结着 | 不给那块地发 `friend_suspended` | 冻结地块没有现任户主，原户主要等解冻才能恢复朋友（22.18） |
| P35 | 登录门合入之前，不做正版验证的服务器上的通知 | 一条都不发，留在队列里（最多 30 天）；开服记 WARN | 复核改（22.19）：原默认是"上线即发"；不做正版验证的服务器上，身份要等 AccessHub 的 `/login` 确认之后才算数。正版验证的服务器（单人、局域网、正版服）照常上线即发。**正式服打开 `enabled` 之前登录门（PR #72）必须已合入**（23.10） |
| P36 | 机械臂、显示链接、对称之杖的口径 | 单向：只拦落到别的区域的那一部分；地块里自己的照常 | 它们搬物品、写字或替玩家干活，不是"机器拆放方块"；传送带、土豆加农炮仍按受保护口径（22.6、22.19） |
| P37 | 朋友通知防刷 | 同一收件人、同一块地、同一户主只留合并后的净变化；超出 30 条先删朋友通知；即时送达每人 60 秒至多一次 | 任何户主都能对任何人反复加、移朋友（22.19）。常量 `DistrictLimits.FRIEND_NOTICE_INTERVAL_MS` |
| P38 | 整合包里别的会直接改方块、不发事件的 mod 物品 | 本阶段不写 mixin；**打开 `enabled` 之前**由服主在整合包里处理（删掉配方或只给 OP），阶段 4 核对 | 具体清单不写进公开文档，由服主与主负责人私下核对（22.6、23.10） |
| P39 | 机械动力 mixin 注入点对不上时 | 不让服务端起不来：`defaultRequire = 0`，插件数处理方法真的织进去了几处，没织全的按"缺"报 | 复核改（22.7、22.19）：可选配置里 `require` 不满足抛的 `InjectionError` 是 Error，不看 `required`，原来会崩服 |
| P40 | 建区、绑定、改范围之前就压着外围的个人领地 | 不删、不冻结、不改，只报告（`/district personalclaims`、回显、`status`）；主人能缩小、能把远离区的一边往外挪，不能多占外围的一列 | 也可以改成冻结，或者拒绝建区、绑定（22.20） |
| P41 | 子领地、`/flan transferClaim`、Flan 的管理命令（`setAdminClaim`、`add … <维度> <玩家>`、`readGriefPrevention`） | 不拦 | 子领地出不了父领地；转让不增面积；管理命令要 Flan 的 `permissionLevel`（默认 2）。它低于 2 时开服记 WARN（22.20） |
| P42 | 个人圈地限制出错时 | 放行（fail open），每小时至多一条 ERROR，`status` 显示放行次数；漏过去的由清单兜底 | 同 22.3 的守卫口径；另有急停开关 `[district.guards] personalClaims`（22.20） |
| P43 | 金锄头的陈旧编辑（选中的领地与 Flan 当前的登记对不上） | F2 一律拒，OP 与管理员领地（含父领地）也拒；急停关着、功能关着时不拦（回到 Flan 原生的行为） | 复核补（22.22）：与禁圈区无关，所以不照 OP 例外。OP 碰上只要重新点一下角 |
| P44 | `denyBlocks`、`allowBlocks` 要不要补 `ignored_void` 下的同名项（整合包把默认名单里的 6 项登记在那里，22.4 末尾） | 不补，默认名单只写 `create:` | 2026-09-30 冒烟测试后加。`namespaces` 默认加上 `ignored_void` 是服主定的；名单跟不跟着补待服主定。服主可在 `[district.createBan]` 里直接加，不用新构建 |

---

## 二十、阶段 2：真 Flan 对接 (DECIDED；实机核对留给阶段 4)

本章取代原来的"TODO：阶段 2"清单，是阶段 2 的实现依据。

依据：
- [对接说明](District_Flan_Integration_Notes.md)。
- 2026-09-29 对服主批准的 `flan-1.20.1-1.11.16-forge.jar` 做的 `javap -p -c -l -s -constants` 复核（字节码加局部变量表）。该 jar 的 SHA1 为 `be83187dd6717029930e8ca7c1109f4bcd5cf870`，大小 894692 字节。复核纠正了对接说明的几处，列在 20.11 末尾，实现时一并回写那份文档。
- 设计基线：分支 `feat/wok-district`，HEAD `2acac7e4`，阶段 1 已全部完成。

阶段 2 的范围：

| 做 | 不做 |
|---|---|
| Flan 作编译期依赖 + 软依赖；开发运行时加载真 Flan，供 GameTest 使用（20.1） | 机械动力禁令、外围 8 格、聊天通知（阶段 3，第二十一章） |
| 真网关 `FlanClaimGateway`；开服自检，不通过就退回 Disabled（20.1、20.2） | 测试服实机核对（阶段 4，20.12） |
| 父领地与地块在 Flan 里的布局：四类受众、冻结、区域规则、权限全表（20.3） | 拦截 OP（服主已拍板：OP 能进所有地块） |
| 对账：开服跑一轮、之后定时跑，按差异写回（20.4） | 由程序改自管区范围（改走金锄头，20.6） |
| 写入前备份；存盘策略（20.5） | |
| 建区、绑定已有领地、重新绑定、同步范围（20.6） | |
| 命令（20.8）；生产开关与导航入口（20.9）；真 Flan 的 GameTest（20.10） | |

服主已拍板、本章照做的前提：

1. 管理员（OP）圈下一大片学院用地，就是一块 Flan **管理员领地**（owner 为 null），下文叫"父领地"。
2. 区务长在父领地里划地块。地块就是父领地下的 Flan **子领地**，一律由服务器代建。
3. 户主对"朋友 / 其他住户 / 外人"三列逐项显式开关。住户在公共区域的权限经本区居民组给；外人就是领地的默认（全局）权限。
4. 区域规则（PvP、爆炸、火、刷怪、末影人、水和岩浆越界……）只有 OP 能改，全区统一。
5. 冻结的地块，除 OP 外谁都不能做任何事：三类受众全部为假，库里存着的户主设置不动。
6. 区务长、户主都拿不到 Flan 的 `edit_claim`、`edit_perms`、`edit_potions`。
7. OP 能进所有地块（这是 Flan 写死的 OP 绕过），**不拦**。
8. 删除自管区只解绑，Flan 领地原样留着。

原 TODO 的去向：

| 原 TODO | 结论 | 见 |
|---|---|---|
| 20.1 `Flan1116Gateway` | 改名 `FlanClaimGateway`，版本门挪进开服自检 | 20.1、20.2 |
| 20.2 Flan 配置核对 | 不设配置前置条件。默认组在建领地时临时换空，其余靠显式写入和对账兜住 | 20.2 |
| 20.3 目录外权限表 | 由 Flan 的权限表生成全表，地块上每个非全局权限都显式写 | 20.3 |
| 20.4 对账 | 开服跑一轮，之后每 5 分钟一轮，按差异写回 | 20.4 |
| 20.5 落盘保护 | 写入前做逻辑备份，放在 `data/claims` 之外并轮换；不在每批改动后显式存盘 | 20.5 |
| 20.6 重新绑定、绑定已有领地 | `/district bind` 收编已有的管理员领地，删掉其上全部子领地；同一学院重新绑定也走它 | 20.6 |
| 20.7 冻结的"不能进出" | 冻结时 `can_stay` 写假。阶段 2 不监听 `PermissionCheckEvent`，事件双发的问题不再相关；实际弹出效果留给阶段 4 | 20.3、20.12 |
| 20.8 实机核对 | 挪到阶段 4 | 20.12 |
| 20.9 导航入口放开 | 服务端开关，经 `hub.panels` 报告；开发构建照旧 | 20.9 |

### 20.1 依赖接线与类布局

#### 编译期依赖与软依赖

`build.gradle`：

```groovy
// Flan 1.20.1-1.11.16 (Forge): 自管区的领地后端 (docs/District_Backend_Design.md 20.1)。
//   坐标用 Modrinth 版本 id (不可变), 不用版本号 (Modrinth 上版本号不保证唯一)。
//   Modrinth maven 不给 .sha1, Gradle 无从校验: verifyFlanArtifact 钉死服主批准的那个文件的 SHA1。
compileOnly fg.deobf('maven.modrinth:flan:Gh42Sknw')
runtimeOnly fg.deobf('maven.modrinth:flan:Gh42Sknw')   // 见下文探路: S1 无条件; S2 包进 withFlan 属性
```

逐项决定：

1. **坐标用 `maven.modrinth:flan:Gh42Sknw`。** 已只读核实：`flan:1.20.1-1.11.16-forge`、`flan:Gh42Sknw`、`Si383TIH:Gh42Sknw` 三种写法都会 307 到同一个 CDN 文件，也就是批准的那个 jar。选版本 id，是因为它不可变。`build.gradle` 已有 `https://api.modrinth.com/maven` 仓库，POM 不声明任何依赖。
2. **用 `compileOnly fg.deobf(...)`。** Flan 自己的类名、方法名没有混淆，但 jar 里引用的 Minecraft 成员是 SRG 名；deobf 之后，编译期签名与开发运行期一致。不用 `files(...)`：TACZ 的注释已记下 `fg.deobf(files)` 做不了 deobf。Flan 不进 jarJar、不打进产物，正式服由服主自己装。
3. **校验任务 `verifyFlanArtifact`。** 用一个独立的 `flanPinned` 配置（`transitive = false`）解析同一坐标的原始 jar，算 SHA1 与 `be83187d…f870` 比对，不符就让构建失败；`compileJava` 依赖这个任务。写法照 `verifyModuleRegistry`：配置期捕获局部变量，输出一个成功戳记。不开全局的 `verification-metadata.xml`，那要给全部依赖补校验和，面太大。
4. **不新增任何别的第三方依赖。** lingua_bib 由 Flan 自己用 JarJar 内嵌，正式服不用另装。开发运行时加载的 lingua_bib 就是从批准的 jar 里原样取出的那一个（下文的出路 ③），不是另下载的构件。
5. **`mods.toml` 加一条可选依赖：**

   ```toml
   # Flan: 自管区的领地后端 (wok-district)。mandatory=false: 没装 Flan 时服务器照常启动, 自管区降级 (20.2)。
   # 版本门在代码里 (FlanCompat 只认 1.20.1-1.11.16, 不符记 ERROR 并退回 Disabled 网关), 这里刻意放开成 [0,):
   # 写成精确区间的话, 服主换个 Flan 版本整个服务器就起不来, 而自管区只是一个模块。
   # side=BOTH 的理由同 champions (整合包单人的集成服务端 Dist 为 CLIENT); 只在开服后才碰 Flan, 不需要 ordering。
   [[dependencies.miningdim]]
   modId = "flan"
   mandatory = false
   versionRange = "[0,)"
   ordering = "NONE"
   side = "BOTH"
   ```

   Flan 的 Forge modId 是 `flan`，门控一律用 `ModList.get().isLoaded("flan")`。Flan 的 DisplayTest 是 IGNORESERVERVERSION，客户端不用装。
6. **登记与文档。**
   - `docs/modules/module-registry.json` 里自管区的 `optionalIntegrations` 改成 `["sqlite-jdbc", "flan"]`，scope 补上真网关。
   - `THIRD-PARTY-NOTICES.md` 加一行：只在编译期和开发运行期使用，不随产物分发；许可以 Modrinth 项目页为准，落地时查明写入。

#### GameTest 怎么拿到真 Flan：先探路，再定

默认方案：`runtimeOnly fg.deobf(...)` 无条件加进开发运行时，真 Flan 用例进默认门 `gradlew runGameTestServer`。

理由：
- 阶段 2 的风险全在 Flan 的真实语义上（浅拷贝、组的判定顺序、全局权限、存盘格式）。只在带某个属性时才跑的测试，等于不跑。
- 农夫乐事已经证明，带 SRG mixin 的正式 mod 经 `fg.deobf` 加上全局的 `mixin.env.remapRefMap`，可以在开发环境加载。Flan 的 `flan.mixins.json`（33 个 mixin）与 `flan.forge.mixins.json` 同理。

未经运行验证的风险：内嵌的 lingua_bib 不经 deobf，保持 SRG 名。它的 `ServerLangManager` 覆写 `m_5944_` / `m_5787_`，自己另有 7 个 mixin。FML 如果在开发环境把它从 Flan 里解出来加载，数据包加载时会出 AbstractMethodError、NoSuchMethodError 或 mixin 失败（`build.gradle` 里 TACZ 的注释记过同一类故障）。

所以实现的**第一步**是探路：加上 `runtimeOnly`，删掉 `run/world`，完整跑一遍 `gradlew compileJava verifyModuleBoundaries runGameTestServer`。看两样东西：日志里的 mod 列表（有没有 `flan`，有没有 `lingua_bib`），以及全部既有用例。然后按结果三选一：

| 结果 | 做法 |
|---|---|
| S1：服务器起得来，既有用例全过 | 保留无条件的 `runtimeOnly`。真 Flan 用例找不到 Flan 时直接失败，而不是跳过 |
| S2：起得来，但有既有用例因为 Flan 变了行为 | 先看是不是道具交互引起的，例如金锄头圈地、木棍查看。是的话，在 `runGameTestServer` 的 doFirst 钩子里（与 serverconfig 清理钩子同一处）把开发用 Flan 配置的 `claimingItem` / `inspectionItem` 指到用不到的物品，不引入新构件。仍不行就退到属性门：带 `-PwithFlan` 才加 `runtimeOnly`（先例是 `-PwithPowerCompat`）；没有 Flan 时真 Flan 用例跳过，合并前硬门另加一次带 `-PwithFlan` 的完整运行 |
| S3：lingua_bib 让服务器起不来 | 停下来，请服主批准以下之一，批准前不做。① 由构建从批准的 jar 派生一个只给开发用的 jar：去掉 `META-INF/jars/lingua_bib-*.jar` 与 jarjar 元数据里对应的那一项，派生前先校验输入的 SHA1，产物不分发。② 另加 lingua_bib 的可 deobf 构件。Flan 只在 `CommandHelp`、`gui/ServerScreenHelper` 和数据生成里用到 lingua_bib，领地代码不碰它，所以 ① 不影响被测行为。批准之前，阶段 2 的逻辑先用记录型网关测，真 Flan 用例暂缺，20.10 的清单不算完成 |

探路结果（2026-09-29，实现第一步）：**S3**。

- 加上 `runtimeOnly fg.deobf('maven.modrinth:flan:Gh42Sknw')`、删掉 `run/world` 后跑 `gradlew compileJava verifyModuleBoundaries runGameTestServer`：`verifyFlanArtifact` 通过（`flan-Gh42Sknw.jar`，SHA1 `be83187d…f870`，894692 字节），编译通过。
- 日志的 mod 列表里有 `flan 1.20.1-1.11.16`，FML 同时把内嵌的 `lingua_bib-1.20.1-1.0.6-forge.jar` 原样（SRG 名，不经 deobf）解出来加载了。
- CONSTRUCT 阶段 FATAL：`lingua_bib.mixins.json:PlayerListMixin` 的 `@Shadow field f_11195_ was not located in the target class net.minecraft.server.players.PlayerList`（refmap 反映射救不了：影子字段名直接写在 mixin 类里），`MixinApplyError` 让 GameTest 服务端起不来，一个用例都没跑。
- **注意：服务端起不来时 `runGameTestServer` 照样报 `BUILD SUCCESSFUL`**。判断 GameTest 是否全过一律看日志里的汇总行，不能只看 Gradle 的退出码。

A 步按上表的 S3 处理：`build.gradle` 只保留 `compileOnly` 与 `verifyFlanArtifact`，开发运行时不加载 Flan，20.10 的真 Flan 用例暂缺（P22）。

**出路 ③（2026-09-30，B 步）：原样取出内嵌的 lingua_bib，作为根 mod 加载。** 上表的 ①② 都要服主批准：① 改的是批准的 jar 本身，② 要另找一个构件。B 步找到了第三条路，两样都不碰：

- 起不来的根子是内嵌的 lingua_bib 没经 deobf：`PlayerListMixin` 的影子字段 `f_11195_`，`ServerLangManager` 覆写的 `m_5944_` / `m_5787_`（它是数据包重载监听器，开发环境里重载时会 AbstractMethodError）。而 `fg.deobf` 会按名字把 mixin 类里的 SRG 影子成员一起改名：已核对 deobf 之后的 Flan jar，`ServerPlayerGameModeMixin` 的 `f_9245_` 变成了 `player`，`AbstractBlockStateMixin` 的 `m_7160_` 变成了 `asState`。
- FML 的 JarJar 选择器（JarJarSelector 0.3.19）见到同 modId 的**根 mod** 时，就不再加载内嵌的那份。日志是 WARN："Attempted to select a dependency jar for JarJar which was passed in as source: lingua_bib. Using Mod File: …"。
- 所以 `build.gradle` 做三件事：
  1. 构建脚本里的 `extractFlanDevLibs`（一个闭包，不是任务）：挂在每个配置解析之前（`beforeResolve`，`flanPinned` 自己除外）。先核对 Flan jar 的 SHA1，再把其中的 `META-INF/jars/lingua_bib-1.20.1-1.0.6-forge.jar` 原样取到 `build/flan-dev-libs/`，取出后核对它的 SHA1（`844f0315513541c2f55f8def0295e8d88b767b44`，49089 字节）。任何一步不符就让构建失败；已取出且 SHA1 相符时什么都不做。
  2. 新的 flatDir 仓库 `flanDevLibs` 指向这个目录。
  3. `runtimeOnly fg.deobf('maven.modrinth:flan:Gh42Sknw')` 与 `runtimeOnly fg.deobf('local:lingua_bib:1.20.1-1.0.6-forge')`。
- 为什么挂在 `beforeResolve`：配置期解析 `flanPinned` 会用到 FG 的 deobf 仓库，FG 之后（afterEvaluate）再改它就报 "Cannot mutate content repository descriptor … after repository has been used"；只挂 `runtimeClasspath` 也不够，FG 的 deobf 仓库解析任何一个配置（实测 `compileClasspath`）都会连带去找全部 `fg.deobf` 构件的原始 jar。每次都查文件在不在，`clean` 与 `runGameTestServer` 同一次执行时也不会缺。
- 批准的 Flan jar 一个字节不改（运行的就是它 deobf 之后的样子，与 `compileOnly` 同一份），lingua_bib 也是它自己带的那一份；取出的 jar 只在 `build/` 里，不进产物、不分发。正式服照旧由 FML 从 Flan 里加载内嵌的 lingua_bib。

结果（删掉 `run/world` 后完整跑）：服务端正常起来，mod 列表里有 `flan 1.20.1-1.11.16` 与 deobf 过的 `lingua_bib 1.20.1-1.0.6`。既有的 1786 条用例里只有 2 条失败，都是 A 步写的"开发环境没有 Flan"的断言（`recordingGatewayIsGameTestServerOnly`、`selfCheckReportsEverySignatureMismatch`），已改为按 Flan 在位核对。**没有任何既有用例因为 Flan 改变行为，属于 S1**：保留无条件的 `runtimeOnly`，真 Flan 用例进默认门，Flan 不在位时直接失败而不是跳过（20.10）。

服主要否决出路 ③ 的话，删掉那两行 `runtimeOnly` 与 `extractFlanDevLibs` 即回到 S3（P22）。GameTest 服务端照常起来：`FlanRealGameTests` 的方法签名里没有 Flan 类型（用例体在 `FlanRealScenarios`，20.10），框架登记用例时对它调 `getDeclaredMethods()` 不会 `NoClassDefFoundError`；真 Flan 的用例在各自门口（`requireFlan`）失败，另有两处"开发运行时有 Flan"的断言（`recordingGatewayIsGameTestServerOnly`、`selfCheckReportsEverySignatureMismatch`）失败，其余照常通过。2026-09-30 复核之前 holder 的签名里带着 `Claim`，去掉那两行 `runtimeOnly` 会让整个 GameTest 服务端在跑任何用例之前崩掉，复核时拆开了。

`-PwithoutFlan`（先例 `-PwithPowerCompat`）不加载那两个 jar，并给 GameTest 服务端设系统属性 `miningdim.district.withoutFlan=true`：真 Flan 的用例按它算通过，两处"有 Flan"的断言改为核对没有 Flan 的生产路径（真实的开服选择选出 Disabled，原因"Flan 没有安装"；签名表每一项都报缺类）。这一轮核对的是正式服或单人存档没装 Flan 时的那条路，默认门之外偶尔跑一次（20.10）。

#### 类布局

```
com.miningdim.district
├── DistrictConfig              miningdim-district.toml：[district] enabled（默认 false，20.9）
├── DistrictFeature             功能状态 OFF / DEGRADED(原因) / LIVE；hub.panels 的门和 /district status 读它
└── flan/                       这一层不引用任何 Flan 类
    ├── FlanGateway（新增方法见下）、ClaimHandle、ClaimInfo（新）、PermValue、FlanResult、ClaimPermissionSnapshot
    ├── KnownPermission（新）   (id, global, defaultValue, requireExplicit)
    ├── FlanPermissionPolicy（新）由已知权限表生成的全表（20.3），纯函数
    ├── DistrictDesiredState（新）父领地的期望状态：组、成员、默认、全局
    ├── PlotDesiredState        加 policy 参数；地块上的全局权限一律 UNSET
    ├── DistrictFlanSync        推送：改成按差异写、先收后放（20.4）
    ├── DistrictReconciler（新） 对账：自动 / 显式 / 预演三种模式
    ├── ReconcileScheduler（新） 开服跑一轮，之后每 6000 tick 一轮，每 tick 限时
    ├── GatewaySelector（新）    开服选网关，写成纯函数以便测试
    ├── FlanGroupNames、FlanPermissions、RecordingFlanGateway、DisabledFlanGateway
    └── real/                   全模块唯一 import io.github.flemmli97.flan.* 的包
        ├── FlanCompat          开服自检：版本门 + 签名表 + 权限 id 探查。只用类名字符串和反射，没装 Flan 也能加载
        ├── FlanClaimGateway    真实现
        ├── FlanReflection      两个私有字段（permissions 删键、playersGroups 只读），自检通过后解析一次
        ├── FlanPermissionTable 读 PermissionManager，按 getAll() 返回对象的身份缓存（/reload，20.3）
        ├── FlanClaimBackups    逻辑备份与轮换（20.5）
        ├── FlanRealGameTests   真 Flan 用例的登记处：签名里没有 Flan 类型，先查 Flan 在不在位再调用例体
        └── FlanRealScenarios、FlanTestClaims   真 Flan 的用例体，以及测试专用的清理、改范围工具
```

几点说明：

- **类名不带版本号。** 版本门在 `FlanCompat` 里；将来核对过新版本，改那张表就行，不用复制一个新类。
- **只有 `flan/real/` 能 import Flan。** 在 `verifyModuleBoundaries` 里加一条源码扫描：`io.github.flemmli97.flan` 只许出现在 `district/flan/real/` 之下。
- **加载顺序。**
  1. `GatewaySelector` 先看 `ModList.get().isLoaded("flan")`；
  2. 再由 `FlanCompat` 按类名字符串自检；
  3. 两步都过了，才第一次碰 `FlanClaimGateway`。

  所以没装 Flan 的服务器从头到尾不会加载任何引用 Flan 类型的类。

#### FlanGateway 的增改

```java
// ---- 阶段 2 新增 ----
/** 范围、是否管理员领地、是否 2D 全高、名字、假玩家白名单与药水的条数。 */
Optional<ClaimInfo> inspectClaim(ClaimHandle claim);
/** 父领地下的全部子领地, 包括不是本模块建的。 */
List<ClaimHandle> listPlotClaims(ClaimHandle district);
/** 与这片范围 (X/Z) 相交的全部顶层领地, 管理员领地与玩家领地都算。 */
List<ClaimHandle> claimsIntersecting(String dimension, DistrictBounds bounds);
/** 清空假玩家白名单、药水与六张放行清单。 */
FlanResult<Void> clearExtras(ClaimHandle claim);
/** 不论节流, 立刻备份一个维度的管理员领地; 返回文件名。 */
FlanResult<String> backupNow(String dimension, String reason);
/** 当前已知的全部 Flan 权限 (/reload 之后重读)。 */
List<KnownPermission> permissionTable();
/** 权限表每重读一次加一。 */
long permissionTableVersion();
// ---- 2026-09-30 复核补 ----
/** available() 为假时给人看的原因 ("领地对接未启用：{原因}"); 降级与熔断时查询一律为空, 不能当成"领地被删了"。 */
String unavailableReason();
/** 这个维度现在有没有加载 (没加载时查询同样为空)。 */
boolean dimensionLoaded(String dimension);
/** 把 2D 父领地的底补到世界底 (Flan 的 extendDownwards); 已到底报成功, 3D 报失败 (20.3)。 */
FlanResult<Void> extendDistrictClaimToBottom(ClaimHandle district);
/** 多了 plotId: 返回时地块已清理并关上 (三个 p_<plotId>_… 组与默认全假, 全局没有键, 20.3)。 */
FlanResult<ClaimHandle> createPlotClaim(ClaimHandle district, PlotArea area, String name, String plotId);
// ---- 个人圈地限制 (22.20) ----
/** 与这片 X/Z 范围相交的顶层个人领地 (owner 不为 null)。降级、熔断、维度没加载时为空。 */
List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area);
```

`PersonalClaim` 定义为 `(ClaimHandle handle, UUID owner, PlotArea area, boolean flat, int minY, int maxY)`（22.20）。真网关与 `claimsIntersecting` 同法（复制 `getClaims()` 里全部非 null 键下的领地，逐块比 X/Z），为此签名表加了 `Claim.getOwner()` 与 `ClaimBox.maxY()`。

`ClaimInfo` 定义为 `(ClaimHandle handle, int minX, int minZ, int maxX, int maxZ, int minY, int worldMinY, boolean flat, boolean adminClaim, String name, int fakePlayers, int potions, int allowListEntries)`。`fullHeight()` = 2D 且底不高于世界底；`shallow()` = 2D 但底高于世界底（能补）；`hasExtras()` = 假玩家、药水、放行清单任一不为空。

已有方法的语义变化：

- `resizeDistrictClaim`：真网关不改领地，只核对范围是否一致（20.6）。
- `knownPermissions()` 保留，改为从 `permissionTable()` 派生。
- 所有写方法：值与现状相同就不写（不标脏），直接报成功。
- 每个会改动领地的方法，在第一次改动某个维度之前，先确认这个维度有一份不超过 10 分钟的备份。备份失败就不改，报失败（20.5）。

记录型、Disabled 两个实现同步补齐这些方法：
- 记录型在内存模型上实现，另加测试用的入口：造一块外来子领地、加假玩家、加药水。
- Disabled 的读方法返回空，写方法返回失败。

### 20.2 开服：开关、自检、网关选择与失败保护

#### 选择流程（ServerStarting）

1. `DistrictConfig.ENABLED` 为假：功能 OFF。不绑定 `DistrictServices`，不碰 Flan，记一条 INFO。
2. 否则建仓储，调 `GatewaySelector.select(...)`，按顺序判：
   1. JVM 系统属性要求 `recording`，且当前是 GameTest 服务端 → 记录型网关（阶段 1 的门不变）。
   2. `ModList.get().isLoaded("flan")` 为假 → Disabled，原因"Flan 没有安装"。
   3. `FlanCompat.check()` 不通过 → Disabled，原因取自检结论；另记一条 ERROR，列出全部问题。
   4. 都通过 → `FlanClaimGateway`。
3. 绑定 context。`DistrictFeature` 置为 LIVE（真网关）或 DEGRADED（附原因）。
4. LIVE 时，先对每个有在用自管区的维度做一次开服备份（20.5），然后才做别的事。

时机为什么够：
- 数据包在服务器构造前就已加载，`PermissionManager` 此时已填好。
- Forge 在 `loadLevel()` 之后才发 ServerStarting，而 Flan 在每个 `ServerLevel` 构造的末尾就已读完领地文件。
- 自检里的探针"主世界的 `ClaimStorage` 不为 null"会把这一点在实现时实测确认。

#### 三种状态

| 状态 | 条件 | 服务与钩子 | 网关 | 开服备份、对账 | 导航入口 | `/district` | 平板动作 |
|---|---|---|---|---|---|---|---|
| OFF 关闭 | `enabled = false` | 不绑定 | — | 不做 | 不显示 | 只有 `status` 可用，其余回"没有开启" | 回 `DISTRICT_DISABLED`（20.9） |
| DEGRADED 降级 | 开关开着，但 Flan 没装、版本不符、自检不过，或运行中熔断 | 绑定 | `DisabledFlanGateway`，带原因 | 不做 | 不显示 | 全部可用，领地类如实报失败 | 只读：6 条读动作照常，20 条写动作回 `DISTRICT_DISABLED`，文案带降级原因（P21，2026-09-30 复核改） |
| LIVE 生效 | 开关开着且自检通过 | 绑定 | `FlanClaimGateway` | 做 | 显示 | 全部可用 | 全部可用 |

DEGRADED 仍然绑定服务，理由：
- OP 要用 `/district status`、`info`、`plots` 排障；
- 数据库是真相，Flan 修好、重启之后，对账会补齐；
- 导航入口不显示，玩家不会撞上满屏的"同步失败"（20.9）。

但平板的写动作在 DEGRADED 时一律拒绝（2026-09-30 复核改，P21）：这时库照写、Flan 却不跟着改——买地扣了钱却没有保护，移出住户、删地块、冻结、换户主之后，旧的领地权限一直留到下一次 Flan 正常的重启。导航入口虽然隐藏，还开着的平板与直接输入的网址仍然到得了这些动作。`DistrictWebUiActions.ctxForWrite()` 在 `ctx()` 之后再查一次 `DistrictFeature`，DEGRADED 时抛 `DISTRICT_DISABLED`（不可同 id 重试、无 params），文案"自管区的领地对接暂停（{原因}），现在只能查看、不能修改"。OP 的 `/district` 命令照常，供排障。

降级、熔断或维度没有加载时，网关的查询一律返回空。推送与对账先问 `DistrictFlanSync.unreachable(dimension)`（`gateway.available()` 与 `dimensionLoaded()`），不通过就报出真正的原因（"领地对接未启用：…"或"维度 X 没有加载"），**不**走"父领地在 Flan 里找不到了"那一支：不置对账标记，不把错的原因写进住户与地块的生效状态，`/district inspect` 也不再提示去 recreate。

`DisabledFlanGateway` 的回复改成"领地对接未启用：{原因}"，原因取以下之一：
- "Flan 没有安装"
- "Flan 版本 {v} 未经核对"
- "Flan 自检未通过，见服务器日志"
- "Flan 调用出错，已停用到重启"

`DistrictTexts.FLAN_DISABLED` 相应改成带原因的格式函数。GameTest 服务端上配置每轮被清空，所以恒为 OFF，需要生产路径的用例自己翻状态（20.9）。

#### 自检清单（FlanCompat）

1. **版本。** Flan 模组容器的版本必须恰好是 `1.20.1-1.11.16`。别的版本一律不认，即使签名全对：行为上的差异，签名是查不出来的。
2. **签名。** 下表逐项按"全限定类名 + 参数类型 + 返回类型 + 是否 static"反射查找。Minecraft 的类在正式服上也用官方类名，所以同一张表在开发环境和正式服上通用；Minecraft 的成员名不在表里。类名前缀 `io.github.flemmli97.flan.` 从略。

   | 类 | 成员（参数 → 返回） |
   |---|---|
   | `claim.ClaimStorage` | static `get(ServerLevel)` → `ClaimStorage`；`createAdminClaim(BlockPos, BlockPos, ServerLevel, boolean)` → `Claim`；`getClaimsAt(int, int)` → `List`；`getFromUUID(UUID)` → `Claim`；`getClaims()` → `Map`；`save(MinecraftServer, ResourceKey)` → `void`；static final 字段 `ADMIN_CLAIMS`（`String`） |
   | `claim.Claim` | 构造器 `(BlockPos, BlockPos, UUID, ServerLevel)`；`tryCreateSubClaim(BlockPos, BlockPos, boolean)` → `Set`；`getAllSubclaims()` → `List`；`deleteSubClaim(Claim)` → `boolean`；`copySizes(Claim)` → `void`；`getClaimID()`、`getOwner()` → `UUID`；`parentClaim()` → `Claim`；`isSubclaim()`、`isAdminClaim()`、`isRemoved()`、`is3d()` → `boolean`；`getLevel()` → `ServerLevel`；`getDimensions()` → `ClaimBox`；`editPerms(ServerPlayer, String, ResourceLocation, int, boolean)` → `boolean`；`editGlobalPerms(ServerPlayer, ResourceLocation, int)` → `boolean`；`groups()` → `List`；`groupHasPerm(String, ResourceLocation)` → `int`；`permEnabled(ResourceLocation)` → `int`；`setPlayerGroup(UUID, String, boolean)` → `boolean`；`getAllowedFakePlayerUUID()` → `List`；`modifyFakePlayerUUID(UUID, boolean)` → `boolean`；`getPotions()` → `Map`；`removePotion(MobEffect)` → `void`；`setClaimName(String)` → `void`；`getClaimName()` → `String`；`setDirty(boolean)` → `void`；`isDirty()` → `boolean`；`toJson(JsonObject)` → `JsonObject`；`extendDownwards(BlockPos)` → `void`；私有字段 `permissions`、`playersGroups`（类型 `Map`）；public final 字段 `allowedItems`、`allowedUseBlocks`、`allowedPlaceBlocks`、`allowedBreakBlocks`、`allowedEntityAttack`、`allowedEntityUse`（类型 `AllowedRegistryList`） |
   | `claim.AllowedRegistryList` | `size()` → `int`；`removeAllowedItem(int)` → `void` |
   | `claim.ClaimBox` | `minX()`、`minY()`、`minZ()`、`maxX()`、`maxY()`、`maxZ()` → `int` |
   | `config.ConfigHandler` | static 字段 `CONFIG`（`Config`） |
   | `config.Config` | 字段 `defaultGroups`（`Map`，必须 public 且非 final） |
   | `api.permission.PermissionManager` | static 字段 `INSTANCE`；`getAll()` → `Collection`；`get(ResourceLocation)` → `ClaimPermission`；`isGlobalPermission(ResourceLocation)` → `boolean` |
   | `api.permission.ClaimPermission` | `getId()` → `ResourceLocation`；public final 字段 `defaultVal`、`global`、`requireExplicitSet`（`boolean`） |

   `Claim.getOwner()` 与 `ClaimBox.maxY()` 是个人圈地限制的清单加的（22.21 第 5 条），`Claim.getLevel()` 是金锄头陈旧编辑的检查加的（22.22）。

   只有 GameTest 用、生产代码不用的调用不进这张表，由测试自己保证：`ClaimStorage.deleteClaim`、`ClaimStorage.getForPermissionCheck`、私有 `ClaimStorage.addClaim`、`api.ClaimHandler.canInteract`。
3. **只读探查**，不改任何领地：
   - `ClaimStorage.ADMIN_CLAIMS` 的值（要反射读：它是编译期常量，会被内联）是 `"!AdminClaims"`。20.5 的恢复步骤靠这个文件名。
   - `ConfigHandler.CONFIG` 不为 null。
   - `PermissionManager.INSTANCE.getAll()` 非空，必需的 id 都在，且全局标志符合预期：
     - 目录的 30 个 Flan id、四个管理类权限、`can_stay`、`drop`、`flight` 都是非全局；
     - 全局的恰好是 `FlanPermissions.GLOBAL` 那 15 个。
   - 主世界的 `ClaimStorage.get(level)` 不为 null，说明 mixin 已经生效。
4. **不做会改领地的试探**，例如建一块临时领地再删掉：那会标脏，在正式存档里留下痕迹。Flan 的行为交给 GameTest 核对（20.10）。

任何一项不通过：记**一条** ERROR，每个问题一行；功能进入 DEGRADED，**一次写入都不做**。

自检表同时是 GameTest 的输入：测试传入一张故意写错一项的表，断言结果不通过、选出的是 Disabled、Flan 里什么都没变。

#### Flan 配置：不设前置条件（关原 TODO 20.2）

原计划是"开服检查 `defaultGroups: {}`，不满足就拒绝启用"。现改为不设任何配置前置条件，逐项理由如下：

- **`defaultGroups`**（默认有 Co-Owner，全部权限含 `edit_*`；另有 Visitor，11 项）：只在 `new Claim(...)` 的构造里套用。
  - 建父领地时，把这个 public 非 final 的字段临时换成一个空 Map，`finally` 里换回。换字段发生在服务器线程上；`/flan reload` 和 ServerAboutToStart 里的 `Config.load()` 也在服务器线程上，两者不会穿插。
  - 子领地构造时套上的默认组，`tryCreateSubClaim` 自己就会清掉。
  - 绑定已有领地和对账都会删掉一切不该有的组。
  - 反过来，要求服主改配置会波及玩家自己的领地。
- **`defaultClaimDepth` / `subClaimsInheritParentDepth`**：两个角的 Y 都取世界底，与这两项无关。
- **`minClaimsize`**：管理员领地不查。
- **`globalDefaultPerms` 的 ALLTRUE / ALLFALSE 锁**：管理员领地及其地块不看这些锁；构造时按它预填的值，会被我们的显式写入覆盖。
- **`preConfigVersion < 2` 时的 `lock_items` 升级器**：`lock_items` 在父领地上显式写，被改了由对账纠正。
- **`permissionLevel`**：只影响 `/flan bypass` 与 Flan 的管理命令，而 OP 本来就能进所有地块（服主已拍板）。个人圈地限制（22.20）起它多一层意思：低于 2 时 1 级 OP（P14，不例外）也能用 Flan 的管理命令（`/flan add … <维度> <玩家>`、`setAdminClaim`、bypass）绕过限制。仍不设前置条件，只提示：开服记 WARN，`/district status` 注明。

开服时把这几项的当前值记一条 INFO，便于阶段 4 对照；`permissionLevel` 低于 2 时另记一条 WARN（`FlanBridge`，22.20）。

#### 运行期的失败保护

1. **线程。** 每个网关方法先查 `server.isSameThread()`；不是服务器线程就报失败、不碰 Flan，ERROR 每小时至多一次。
2. **异常。** 每个方法都包住 RuntimeException 与 LinkageError，一律转成 `FlanResult.failure("Flan 调用出错（{异常类名}），详见服务器日志")`。原因：`StoreTx.afterCommit` 会吞掉 RuntimeException、只记一条 ERROR；异常要是漏出去，事务里先写的"临时失败"会原样留着，没人知道。同一个方法、同一类异常每小时只记一次带堆栈的 ERROR，其余降成一行 DEBUG（对账每一轮每块地都要读好几次，一个持续出错的读不能每 5 分钟刷一屏）。
3. **熔断。** 出现 LinkageError（NoSuchMethodError 之类，自检之后本不该有）时，网关熔断：此后所有写入直接失败，`DistrictFeature` 转为 DEGRADED（导航入口随之隐藏），记一次 ERROR。重启才恢复。
4. **写前预检。** 一批写入先查完再动手：权限 id 都在当前权限表里、句柄解析得到、地块范围在父领地内且不压兄弟地块。预检不过，整批不写。
5. **建地块是原子的。** `tryCreateSubClaim` 成功之后，清理或关上（20.3）的任何一步失败，立刻 `deleteSubClaim` 删掉这块半成品再报失败。绝不留下带着继承组、或者还开着的地块。建成之后库里没能记下它的 id 时同样删掉（20.3）。
   建父领地则相反：`createAdminClaim` 一返回领地就已进了 `ClaimStorage`，之后起名、删默认组出错只记 WARN、照样报成功（交给随后的整块写入与对账补齐）；报失败的话库里不记它的 id，下一次又去建、永远"重叠"。库里没能记下 id 的那一块由下一次建父领地时认回（20.6）。
6. **写入顺序"先收后放"：**
   1. 移出成员；
   2. 写假和 UNSET；
   3. 写真；
   4. 加入成员。

   中途失败时，地块多半停在更严的一侧。也有例外：外人列比某个组还宽的少见设置下，先移出成员会让 TA 暂时落到更宽的外人列。无论哪种情况，下一轮对账都会补齐。
7. **备份不成功就不写**（20.5）。
8. **权限表缺必需的 id**（`/reload` 之后数据包删掉了某个权限）：所有写入报"Flan 权限表里没有 {id}"，对账暂停，记一次 ERROR；之后的某次 `/reload` 把它补回来时自动恢复。

### 20.3 Flan 里的布局：父领地、地块、四类受众与权限全表

#### 父领地（自管区）

- 一块 2D 管理员领地（owner = null）。两个角的 Y 都取世界底，所以是全高；X/Z 取库里的范围（20.6）。名字是自管区显示名去掉 `%`：管理员领地的 `getClaimName()` 会做一次 `String.format`。
- **必须全高（2026-09-30 复核补）。** Flan 按顶层领地判定保护：`getForPermissionCheck(pos)` → `getClaimAt` → `ClaimBox.insideClaim` 查的是父领地的 `minY <= y <= maxY`，地块不在区块索引里，所以父领地的高度范围决定了地块在哪些高度受保护，冻结的地块也一样。本模块建的父领地两角都在世界底；`/flan` 圈的 2D 管理员领地只往下探 `defaultClaimDepth` 格（默认 10），底可能高于世界底。所以：
  - `bind`（和 `relink`）拒绝 3D 领地（`CLAIM_NOT_FULL_HEIGHT`：3D 领地补不了，请 OP 用 `/flan` 删掉后按 2D 重圈）；2D 但底偏高的照收，收编时用 Flan 自己的公开方法 `Claim.extendDownwards(BlockPos)` 把底补到世界底（Flan 放方块时也这么调：只降低 2D 领地的 `minY`、标脏、通知网页地图，不动 X/Z 与区块索引）。预览里写明领地的底。
  - 对账每一轮都看父领地的底：2D 但偏高就补（记一项改动），3D 只报告。
  - `ClaimInfo.fullHeight()` 比的是"不高于世界底"：`defaultClaimDepth = -1` 时 Flan 把 2D 领地的底折算成世界底再往下 10 格。
- **六张放行清单一律清空（2026-09-30 复核补）。** `Claim` 上的 public final 字段 `allowedItems`、`allowedUseBlocks`、`allowedPlaceBlocks`、`allowedBreakBlocks`、`allowedEntityAttack`、`allowedEntityUse`（`AllowedRegistryList`）由 Flan 在顶层领地上先于组与默认权限查看；地块里的判定落在父领地上，所以父领地上的放行清单会越过四类受众与冻结的设置。`inspectClaim` 把六张清单的条目数计进 `ClaimInfo.allowListEntries`，`hasExtras()` 带上它，`clearExtras` 从尾部逐条 `removeAllowedItem(int)`（公开、会标脏）。不用 `read(new JsonArray())`：它只清列表、不清内部的名字索引，之后 `addAllowedItem` 会把同名条目悄悄拒掉；从尾部删则不会让索引里其余条目的下标错位。收编与对账因此都会清掉它们。
- 只放一个组：本区居民组 `d_<districtId>_resident`。Flan 默认的 Co-Owner、Visitor，以及其他任何组都不许存在。
- 成员：本区名单里 `synced` 与 `failed` 的人，全在居民组。
  - `failed` 的人也在期望里，因为那表示"本该在、上次没写成"。
  - `pending` 的人不写：他们的 UUID 还可能在首次登录时换键。
- 居民组的组权限 = 公共区域的住户列（目录 29 项，对应 30 个 Flan id）+ 全表里的其余非全局权限（见下）。
- 默认权限（外人）= 公共区域的外人列 + 全表里的其余非全局权限。
- 全局权限（区域规则）只写在父领地上：
  - 目录的 7 项区域规则按全区列写，`mob_spawn` 取反；
  - 其余 8 个全局权限写 Flan 自己的 `defaultVal`（下表）。
- 假玩家白名单为空，药水为空。

#### 地块（子领地）

- 由服务器用 `tryCreateSubClaim` 代建，2D 全高，名字是地块编号，例如 `阿拜多斯-01`。
- 只放本地块自己的三个组：`p_<plotId>_owner`、`p_<plotId>_friend`、`p_<plotId>_resident`。`plotId` 是 `district_plot` 的主键，全服唯一，所以这三个组名也全服唯一。
- **为什么组名必须唯一、为什么地块绝不继承：** `tryCreateSubClaim` 会把父领地的组带到地块上，两边的内层设置并不独立（同一个 Map），要到存盘、重启之后才变成各自独立的副本。所以地块只用自己专有的组名、清理时删掉继承来的组，比较一律按值比。
- **清理（scrub）**：`createPlotClaim` 返回之前完成以下四步。
  1. 删掉地块 `permissions` 里的全部键（反射 `remove`）。此时里面只有继承来的 `d_…` 组，以及父领地上万一漂进来的别的组。
     - 不用"只移出成员、留下空组"的无反射方案：留下的键仍指着父领地的 Map，存盘时会把父领地的组权限原样写进地块，`/flan` 界面里也看得到，对账每一轮都得报它一遍。
  2. 移出复制过来的全部成员（`setPlayerGroup(uuid, null, true)`）。
  3. 删掉复制过来的药水（`removePotion`）。假玩家白名单 Flan 不复制，不用管。
  4. 设名字，标脏。

  父领地平时只有一个组，所以清理实际上就是删一个键、再移出全体住户。万一有别的键漂进来，对账会另外删掉。
- **关上（close，2026-09-30 复核补）**：清理之后、`createPlotClaim` 返回之前，把地块写成与冻结相同的"关闭"状态：本块的三个 `p_<plotId>_…` 组都建好、对全部非全局权限显式为假，地块默认同样全假，全局权限一律去掉键。清理完的子领地没有组，`globalPerm` 里只有构造器按出厂值预填的真值（`can_stay`、`pickup`、`enderchest`…），其余一律落到父领地的默认（公共区域的外人列）——在库里记下它的 id、按差异写好之前，这样的地块对外人是开着的。关上之后：
  - 之后的差异写入只"放"该放的格子；
  - 写库（`setPlotClaimId`）失败时 `applyPlotState` 删掉这块子领地、地块报 failed（"地块的领地没能记进数据库，已撤销，稍后自动重试"），下一次写入重建；删不掉的话它关着、带着本块的组名，对账按组名认领（`modulePlotId`）。
  - 清理或关上的任一步失败，照旧 `deleteSubClaim` 删掉半成品再报失败。
- 三个组的组权限、地块默认与成员，仍按 8.3 的表整块决定。阶段 2 有两处变化：
  - 非全局权限按全表**全部**显式写，不再只写目录项加 `can_stay`、`drop`；
  - 全局权限**全部**写 UNSET。阶段 1 只把 7 项区域规则写 UNSET；可新地块构造时会把 `lock_items`、`snow_golem` 预填为真，不写 UNSET 它们就不跟随父领地。

#### 四类受众怎么落到 Flan

| 受众 | 公共区域（父领地） | 某块地里 |
|---|---|---|
| 户主 | 居民组（户主也是住户） | `p_<id>_owner`：全部非管理类权限为真，全表里固定为假的除外 |
| 朋友 | 是住户就在居民组，否则按外人算 | `p_<id>_friend`：朋友列 |
| 其他住户 | 居民组：住户列 | `p_<id>_resident`：其他住户列。加住户、移住户时逐块地同步（8.2 的 `placeMemberInPlots`） |
| 外人 | 父领地的默认：外人列 | 地块的默认（`editGlobalPerms` 写在地块上）：外人列 |
| 全区 | 父领地的全局权限：区域规则 | 不写，UNSET，跟随父领地 |

下面几条性质由 Flan 的判定顺序（对接说明 2.3）保证，GameTest 逐条锁住（20.10）：

1. **父领地的组在地块里一律不读。** 例外只有 `edit_claim` 和 `edit_perms`：这两项在父领地上判，而居民组和默认里它们都显式为假。所以公共区域给住户开"破坏方块"，不会漏进任何人的地块。
2. 一个玩家在一块领地里只能在一个组，按"户主 > 朋友 > 居民"取最高的那个。
3. 不在地块任何组里的人，包括同步失败的住户，落到地块的默认，也就是外人列。
4. 全局权限没有组，也没有 OP 绕过。地块上是 -1，判定时落回父领地的值。

#### 冻结

- 三个组都保留，组权限一律写假：全表里的全部非全局权限，包括 `can_stay`、`drop`、`flight`。地块的默认同样全假。成员全部移出。全局权限照旧是 UNSET。
- 库里的户主、朋友与三列都不动（第十一章）；解冻时照库里的值整块重写。
- OP 仍能进出、操作（Flan 的 OP 绕过，服主已拍板不拦）。
- 本模块阶段 2 **不监听** `PermissionCheckEvent`，所以对接说明里"地块内事件发两次"的问题在阶段 2 不再相关，这关掉了原 TODO 20.7 的一半。另一半——`can_stay` 为假时，非 OP 是被挡在边界外还是被弹出去——留给阶段 4 实测（20.12）。

#### 区域规则

7 项目录区域规则（`PermissionCatalog` 的 region 组）写在父领地的全局权限上，取全区列的值：

- `pvp` → `flan:hurt_player`
- `explosions` → `flan:explosions`
- `wither` → `flan:wither`
- `fire_spread` → `flan:fire_spread`
- `mob_spawn` → `flan:mob_spawn`，**取反**：Flan 的 `mob_spawn` 为真表示"阻止自然生成"（`WorldEventsForge.preventMobSpawn` 只管 NATURAL 生成，判定为真就取消）
- `enderman` → `flan:enderman`
- `liquid_border` → `flan:water_border`

其余 8 个全局权限，父领地上写该权限的 `defaultVal`，也就是 Flan 新建领地时的出厂值。1.11.16 的实际取值如下：

| Flan 全局权限 | 父领地上的值 | 效果 |
|---|---|---|
| `flan:animal_spawn` | 假 | 语义同样是"阻止"（阻止怪物以外的自然生成，例如动物），假 = 不阻止 |
| `flan:fake_player` | 假 | 模组机器（假玩家）在区内不能操作。机械动力的假玩家另由 22.6 拦 |
| `flan:lightning` | 假 | 雷击不点火，不伤动物 |
| `flan:lock_items` | 真 | 死亡掉落锁给本人 |
| `flan:piston_border` | 假 | 活塞不能跨区的外边界推拉 |
| `flan:player_mob_spawn` | 假 | 玩家不能引出监守者、末影螨 |
| `flan:sculk` | 假 | 幽匿感测体不响应 |
| `flan:snow_golem` | 真 | 雪傀儡可以铺雪 |

未知的全局权限（数据包或别的模组新加的）同样写它的 `defaultVal`。地块上，所有全局权限一律 UNSET。

#### 权限全表：FlanPermissionPolicy（关原 TODO 20.3）

**生成。** 开服时、以及每次 `/reload` 之后，从 `PermissionManager.INSTANCE.getAll()` 读出全部已知权限，对每个权限按下面的规则定出每个位置的值。1.11.16 在开发环境里有 66 个；装了官方的机械动力是 67 个，多出的 `create_contraption` 带 `required_mod: create`。**整合包里实测也是 66 个**（2026-09-30 冒烟测试，`/district status` 的"66 known permission(s)"，见 23.11）：Flan 登记这一项时要拿 `create:cart_assembler` 当图标，整合包的机械动力把这个方块和物品登记在 `ignored_void` 下（22.4 末尾），`create:cart_assembler` 不存在，这一项就没有登记。Policy 按实际读到的列表生成，少这一项不影响别的。Policy 是纯函数，输入是已知权限列表和两张开关表，GameTest 可以直接测。

非全局权限在 1.11.16 里实际有 52 个（没装机械动力时 51 个；整合包里也是 51 个，缺的就是 `create_contraption`），分类如下：

| 类别 | 权限 | 父领地居民组 | 父领地默认 | 户主组 | 朋友组 / 居民组 / 地块默认 | 冻结 |
|---|---|---|---|---|---|---|
| 目录项（30 个 id） | 见 `PermissionCatalog` | 住户列 | 外人列 | 真 | 各自那一列 | 假 |
| 跟随目录项（10 个） | 见下表 | 所跟随项的住户列 | 所跟随项的外人列 | 真 | 所跟随项的那一列 | 假 |
| 固定为真（3 个） | `can_stay`、`drop`、`flight` | 真 | 真 | 真 | 真 | 假 |
| 管理类（4 个） | `edit_claim`、`edit_perms`、`edit_potions`、`claim_message` | 假 | 假 | 假 | 假 | 假 |
| 固定为假（5 个；整合包里 4 个，没有 `create_contraption`） | `teleport`、`raid`、`may_flight`、`no_hunger`、`create_contraption` | 假 | 假 | 假 | 假 | 假 |
| 表外的未知权限 | 数据包、别的模组新加的 | 假 | 假 | 真 | 假 | 假 |

跟随关系：

| Flan 权限 | 跟随 | 理由 |
|---|---|---|
| `flan:archeology`（用刷子刷可疑方块） | 破坏方块 `break` | 刷掉就是取走方块里的东西 |
| `flan:jukebox`（放取唱片） | 开箱子等容器 `container` | 能把唱片拿走 |
| `flan:lectern_take`（换讲台上的书） | `container` | 能把书拿走 |
| `flan:noteblock`（调音符盒） | 调红石元件 `redstone` | 改的是别人的装置 |
| `flan:target_block`（射中标靶方块） | 按按钮和拉杆 `button` | 同属"触发一下" |
| `flan:projectiles`（射出的东西触发方块，比如箭射中按钮） | `button` | 同上 |
| `flan:xp`（捡经验球） | 捡地上的东西 `pickup` | |
| `flan:frost_walker`（冰霜行者结冰） | 放置方块 `place` | 会在水面上生成方块 |
| `flan:endcrystal_place`（放末影水晶） | `place` | 炸不炸得坏东西，仍归区域规则"爆炸伤害"管 |
| `flan:chorus_fruit`（吃紫颂果） | 扔末影珍珠 `ender_pearl` | 两者都是短距离传送 |

几点说明：

- **跟随项不进 `PermissionCatalog` 的 `flanIds`。** `flanIds` 会随 `district.permissions` 下发给管理员看，改它就等于改契约数据；跟随关系只放在 Policy 里。界面上的说明文字不变，请服主过目（P16）。
- **`claim_message`**（改进出领地时的提示语）按管理类处理，因为它就是改领地设置。`FlanPermissions.ADMIN` 加上它，记录型网关"管理类不许写真"的断言随之覆盖到它。
- **`may_flight`、`no_hunger`** 是 Flan 里仅有的两个"要求显式设置"的权限（`require_explicit`）。写真就等于给飞行、免饥饿，所以一律为假。
- **表外的未知权限**按"这是别人的家"保守处理：户主组为真，其余为假。开服时和每次 `/reload` 之后，把未知的 id 列进一条 INFO 日志。
- **父领地默认权限的"假"与"缺省"等价。** Flan 存盘时，父领地的 `GlobalPerms` 只存值为真的 id，重启之后"假"就变成了"缺省"。对账把两者视为相同，否则每次开服都要把这些键重写一遍。地块的 `GlobalPerms` 真假都存，没有这个问题。
- `FlanPermissions.OUTSIDE_CATALOG`（阶段 1 只有 `can_stay`、`drop`）由这张表取代；`GLOBAL` 保留，作为自检的期望值。

#### /reload

- `PermissionManager.INSTANCE` 本身**从不替换**（这纠正了对接说明 2.1 里 listPerms 一行"replaced wholesale"的说法）。它的 `apply()` 换掉的是内部的 `permissions` 与 `sorted` 两个字段，而 `getAll()` 直接返回 `sorted`。所以 **`getAll()` 返回的对象换了身份，就说明发生过一次重载**。
- `FlanPermissionTable` 缓存上一次看到的对象身份和读出的列表。每轮对账开始时、每批写入做预检时，比一次身份（只是一次引用比较）。身份变了就重读、`permissionTableVersion` 加一、重建 Policy。另外监听 Forge 的 `OnDatapackSyncEvent`，玩家为 null 就是整服重载，主动作废缓存。
- 重读之后 id 集合变了：记一条 INFO 列出增减，并安排一轮完整对账，从下一 tick 开始。新出现的非全局权限要显式写进每一块地。
- 必需的 id 缺了：按 20.2 第 8 条处理。
- `/flan reload` 会执行 `Config.load()`，清空再重填 `defaultGroups` 这同一个 Map 实例。我们只在建领地时临时换一下这个字段、平时不持有它，所以不受影响。

### 20.4 对账（关原 TODO 20.4）

#### 何时跑

| 触发 | 范围 | 写回吗 | 删外来子领地吗 |
|---|---|---|---|
| 开服：ServerStarted，在开服备份、老玩家回填、到期收回之后 | 全部在用的自管区 | 写 | 否 |
| 定时：每 6000 tick（5 分钟）起一轮 | 全部在用的自管区 | 写 | 否 |
| 权限表重读后 id 集合变了 | 全部在用的自管区 | 写 | 否 |
| `/district resync <id>` | 一个区 | 写，先强制备份 | 是 |
| `/district inspect <id>` | 一个区 | 不写，只预演 | — |

- 只在 `DistrictFeature` 为 LIVE 时跑。
- `needs_reconcile` 不另起一轮：持续失败时立刻重试只会刷屏，而定时那一轮本来就覆盖全部在用的区。这个标记只用于展示。一个区整轮对完、且没有修不了的差异时，把它清掉（仓储补一个 `clearNeedsReconcile`）。

#### 一个区怎么对（DistrictReconciler）

每个区是一串"条目"：先是父领地，然后每块地一个。

**父领地条目：**

1. 找父领地（`findDistrictClaim`）。
   - 领地对接用不了（降级、熔断）或维度没有加载：报出这个原因，跳过本区，**不**置标记（20.2）。
   - 找不到：置标记、记 WARN（同一原因每小时至多一次），跳过本区其余条目。**不擅自重建**，与 8.2 的口径一致；OP 确认后用 `/district claim <id> recreate`，或者那片地上其实还有领地（恢复了不同时刻的备份）时用 `relink`（20.6）。
   - 库里还没有领地 id（DEGRADED 时建的区）：照常由 `ensureDistrictClaim` 新建；Flan 里已有一块没被任何自管区记着、X/Z 与名字都恰好相符的管理员领地时认回它（20.6）。
2. 修不了、只报告的差异：
   - 不再是管理员领地：有人用 `/flan setAdminClaim` 把它变成了玩家领地，那个玩家会在全区绕过一切。记 ERROR。
   - X/Z 与库里不同（被金锄头改过）：记 WARN，提示执行 `/district bounds <id> sync`。
   - 3D：记 WARN，提示用 `/flan` 删掉后按 2D 重圈、再 `relink`。
3. 修：
   - 2D 但底高于世界底：`extendDistrictClaimToBottom` 补到世界底（20.3），记一项改动；
   - 多出来的组删掉，连同它的成员；
   - 居民组的每一格、默认的每一格（非全局和全局都算）与期望比，只写不同的；
   - 清空假玩家白名单、药水与六张放行清单（20.3）。
4. 成员：期望是名单里 `synced` 与 `failed` 的人。多的移出，缺的或组不对的写上。写成功的名单行回写 `synced`，失败的回写 `failed` 加原因。
   - 所以对账也能把 `failed` 的住户修成 `synced`（第九章第 3 条因此多了一条途径）。
   - 服务器在提交与推送之间崩溃留下的"临时失败"，也在这里自愈。

**地块条目：** 先确定父领地下每个子领地的归属，再逐块整块对。

1. 把父领地下的全部子领地分成三类：
   - 库里某块地记着它的领地 id →"本区地块"；
   - 否则，带着 `p_<P>_…` 形式的组键 →"本模块建的"；
   - 其余 →"外来的"（OP 用金锄头在子领地模式下手划的，或别的来历）。
2. "本模块建的"，按 P 的情况分：
   - P 是本区一块现存的地块，而那块地记着的领地 id 在 Flan 里找不到 → **认领**：把库里的 id 改成这个子领地的，再整块对。写库失败、存盘前崩溃，都可能造成这种错位。
   - P 有墓碑（本区删过这块地）→ 删掉。这正是删地块时推送失败留下的孤儿：墓碑不存领地 id，也能这样认出来。
   - 其余情况（库里既没有这块地、也没有它的墓碑，多半是库回退到了旧备份）→ 只报告，`/district resync` 才删。
3. "外来的"：自动对账只报告，WARN 列出它的范围、组和成员数；`/district resync` 才删。理由是自动流程只删确定是自己留下的东西，而外来子领地里的权限只可能是 OP 自己设的——区务长和户主都没有 `edit_*`。
4. 本区每块地：与 8.3 的整块重写相同，只是按差异写。
   1. 读快照：范围、组、组权限、默认、成员、药水、假玩家、名字；
   2. 与期望比；
   3. 按"先收后放"的顺序，只写不同的。

   成功则地块回写 `synced`，失败则回写 `failed` 加原因。冻结的地块按冻结状态对。
5. 地块超出父领地（父领地被金锄头缩小之后）：只报告。自动缩小地块会改动户主买下的地，不做。

**从不碰没有绑定的领地：**
- 对账只从库里的在用区出发，经 `findDistrictClaim(库里记着的 id)` 拿到父领地，只看这块父领地的子领地，从不遍历别的顶层领地。
- 已解绑区的领地不看：解绑只解绑。
- 备份会读全部管理员领地，但只读。

#### 推送也改用"按差异写"

8.2 的推送（加住户、改三列、划地块……）与对账共用同一段逻辑：读快照、比较、只写不同的（`DistrictFlanSync.applyPlotState` / `applyDistrictState`）。8.3"整块重写"的语义不变：每次都看整块的期望状态，只是相同的格子不再重写。好处有两个：

- **幂等。** 同一个状态推两遍，第二遍 Flan 一次改动都没有，领地不被标脏，自动存档也不会重写文件。
- **对账便宜。** 没有漂移的地块只读不写。

#### 频率与性能上限

- 同一时间至多一轮。上一轮没跑完，下一轮等它结束再开始。
- 每 tick 限时 2 毫秒（用 `System.nanoTime` 计），且至少处理一个条目。游标记住做到了哪里，下一 tick 接着做。每个条目在被处理的那一刻才从库里读期望状态，所以两个 tick 之间有人改了地块，也不会拿旧数据去写。
- 估算：比较一块地的快照约 300 次 HashMap 查找。6 个区、每区 70 块地，一整轮只读在 20 毫秒量级，按 2 毫秒切片约 10 tick 做完。大面积漂移（比如有人清空了全部组）要写约 1 万次 `editPerms`，同样切到多个 tick 里。
- 一轮之中网关连续失败 20 次，就中止本轮，记一次 ERROR。Flan 状态整体坏掉时，这样不会刷屏。
- 一个条目抛了异常（本模块或 Flan 的 bug）：记成一次失败（计入连续失败），接着做下一个条目，这一轮照常结束；一轮之中只有第一个带堆栈记 ERROR，其余一行 DEBUG。数据库出错（`DistrictStoreException` / `MiningStoreException`：库锁着或坏了，每条还可能在 `busy_timeout` 上卡 5 秒）则整轮中止，`/district status` 注明"中止：数据库出错"，等下一次定时（2026-09-30 复核补）。
- 大区的开销（2026-09-30 复核补）：
  - `getAllSubclaims()` 每次都整张复制，逐块线性找地块会让"每次写一块地都扫一遍全区"。真网关按父领地缓存一张子领地 id 索引：命中时核对它没被删、仍挂在这块父领地对象下，不符或没命中就整张重建一次。
  - 加人、移人要触达每一块地：只读一次这个人的名单行与全区的朋友行，每块地只算"这个人该在哪个组"（`PlotDesiredState.memberGroup`，与整张期望状态里这个人的那一项相同），不再逐块读三列与全区名单。
  - 地块的生效状态只在状态或原因变了时才写库；墓碑只在遇到"带本模块组名、库里又没有"的子领地时才读。
  - `/district resync`、`recreate` 与 `relink` 仍在一个 tick 里同步跑完一个区：它们是 OP 显式执行、要当场给出成败计数的排障命令，按上面的开销是线性的。
- 日志：
  - 每次改回漂移记一条 WARN，列出这块领地改了什么（至多 10 项，其余只计数）。格式为 `[miningdim] district {id}: reverted Flan drift on {plot|district} ({n} change(s)): …`。这就是"有人用了 /flan"的痕迹。
  - 同一条修不了的差异，每小时至多 WARN 一次，其余记 DEBUG。"地块写失败"（同一块地、同一个原因）与"置对账标记"（同一个区、同一件事）同样每小时至多 WARN 一次：一块一直失败的地不能每 5 分钟 WARN 一遍。
  - `/district status` 显示上一轮的时间、耗时、切片数、改回项数与修不了的差异数。

不加 mixin。对接说明 2.4 提过可以在 `Claim#setDirty` 上挂一个改动提示，但 mixin 挂在第三方类上是硬耦合：Flan 一换签名就是启动失败，而且我们自己的写入也会触发它。5 分钟一轮的轮询已经够用。

### 20.5 备份与落盘（关原 TODO 20.5）

#### 备份

- **内容：逻辑备份。** 在服务器线程上复制 `ClaimStorage.getClaims().get(null)`（管理员领地的集合，先判 null），对每块领地调 `toJson(new JsonObject())`，装进一个 JsonArray。这与 Flan 存盘的 `!AdminClaims.json` 同构，地块嵌在各自父领地的 `SubClaims` 里。
  - 取内存里的现状，而不是复制磁盘上的文件：内存里的正好是"这一批改动之前"的状态，连还没存盘的改动也在内。
- **位置：`<世界>/miningdim/district-flan-backups/<维度>/`。** 维度 id 里的 `:` 与 `/` 换成 `_`，例如 `minecraft_overworld`。
  - 这个目录在世界根目录下，与各维度的 `data/claims` 平级，绝不在任何 `data/claims` 之内。
  - 原因：Flan 的 `read()` 会递归遍历 `data/claims`。放在里面的 `!AdminClaims.json` 会被当成真领地再读一遍；别的名字的 `.json` 会让 `UUID.fromString` 抛异常，世界直接打不开。
- **文件名：`<yyyyMMdd-HHmmss-SSS>-<原因>.AdminClaims.json.bak`。** 后缀刻意不是 `.json`，算双保险。原因取以下之一：`start`、`batch`、`create`、`bind`、`recreate`、`resync`、`orphan`。
- **写法：** 先写同目录下的 `.tmp` 并落盘（`FileChannel.force`），再原子改名：断电之后，带正式名字的备份不会是空的。写失败时删掉这个 `.tmp`。
- **何时备份：**
  - **开服**（`start`）：进入 LIVE 之后、做任何别的事之前，每个有在用自管区的维度一份。它代表"最近一次能正常载入的状态"。
  - **每批改动之前**（`batch`）：网关的每个改动方法，在第一次改动某个维度之前，先确认这个维度 10 分钟内有过备份，没有就先备一份。一批改动在同一 tick 内完成，所以这份备份一定早于本批的任何改动。
  - **强制**：建父领地、绑定、重建父领地、`/district resync`、对账删孤儿之前，不受 10 分钟节流限制（原因即文件名里的那几种）。
- **为什么节流：** 整个维度的管理员领地可能有几 MB（估算一块地约 10 KB，6 个区 × 70 块地约 4 MB），每改一格就序列化一遍不划算。自管区自己的状态随时能从数据库重建（恢复之后跑一轮对账即可）；备份真正要保的，是文件本身能载入，以及自管区以外的其他管理员领地。
- **轮换：** 按维度、按池各自只留最新的若干份：`start` 池 10 份，其余原因合计 30 份，更早的删掉。只删本目录下符合命名规则的文件，另删超过一分钟的残留 `*.AdminClaims.json.bak.tmp`。轮换只是尽力而为：某个旧文件删不掉（Windows 上被备份软件或杀毒软件占着）只记一条节流的 WARN，不让这一次备份失败——否则占着一个旧文件就会让建区、绑定、resync 等一切强制备份都被拒。
- **失败：** 写不出来（磁盘满、没有权限）时，本次改动不做，报"领地备份失败，本次没有写入领地"，受影响的住户、地块照常记 `failed`；ERROR 每小时至多一次。宁可不写，也不在没有退路的情况下改领地。失败之后一分钟内直接报失败、不再重试（`ClaimBackupFiles.coolingDown`）：否则每一次推送、每一个对账条目都会在服务器线程上把全部管理员领地再序列化一遍。

恢复步骤（写进运维说明，`/district status` 显示备份目录与最新文件）：

1. 停服。
2. 把选中的备份复制成 `<维度存储目录>/data/claims/!AdminClaims.json`，覆盖原文件。
3. 开服。开服时的对账会按数据库把各自管区重写到最新。

注意：备份之后别人新建或改过的其他管理员领地会丢，恢复前先看文件时间。

#### 落盘：不在每批改动后显式存盘

Flan 在 `ServerLevel.saveLevelData` 返回时存盘，覆盖自动存档、`/save-all` 和关服；`/save-off` 期间不写。

决定：**平时不调 `storage.save()`**。只有建、绑、重建父领地之后显式存一次；`level.noSave` 为真时这一次也跳过。

理由：

1. 自管区在 Flan 里的一切状态都能从数据库重建，而开服第一件事就是对账。存盘前崩溃丢掉的改动（成员、组权限、新地块、删地块），下次开服时自动补回。
2. 显式存盘会把这个维度里所有标脏的文件一起写，而 Flan 的写法不是原子的（先截断再写）。每批都存，等于成倍增加"写到一半崩溃、世界打不开"的窗口。
3. 唯一不能自愈的是父领地本身。库里记了领地 id、Flan 里却没有时，`ensureDistrictClaim` 按设计不擅自重建（那可能是 OP 有意删的）。所以建、绑、重建父领地之后要立刻存一次。
4. 尊重 `/save-off`：外部备份的窗口里不写盘。

这也回答了对接说明 2.5 第 12 条（"是否每批改动后都调 save"）：不调，理由如上。

### 20.6 建区、绑定已有领地、重新绑定与改范围（关原 TODO 20.6）

真相的分工：
- 自管区的 **X/Z 范围以 Flan 父领地为准**：由 OP 在游戏里用金锄头改，再用 `bounds sync` 读回库。
- 其余一切（组、成员、权限、地块）以数据库为准。

#### 新建：`/district create <academyId> <from> <to> [<dimension>] [<unitPrice>]`

与阶段 1 相同，另加两点：

- **LIVE 时，事务之前先查这片范围与已有的顶层领地是否相交**（`claimsIntersecting`）。相交则返回新结果 `FLAN_CLAIM_EXISTS`，detail 列出领地 id；如果是管理员领地，提示改用 `/district bind`。这一步挡住了 12.3 的旧问题：同一学院重新绑回自己的旧地时，父领地建不起来，只能置对账标记。
- **提交之后执行 `initializeDistrict`：**
  1. 强制备份；
  2. 建父领地（默认组临时换空）；
  3. 显式存盘；
  4. 写满父领地的状态（按差异写，新领地等于全部写一遍）。
- **建成了却没能记下 id（2026-09-30 复核补）：** `createAdminClaim` 返回之后领地已经进了 `ClaimStorage`；之后写库失败或崩溃，库里的 `flan_claim_id` 仍为空，而每一次 `ensureDistrictClaim` 都会再去建、永远"重叠"。所以库里没有 id 时，先找一块**没被任何自管区行（在用的与已解绑的）记着、X/Z 与库里的范围恰好相同、名字恰好是本区名字**的顶层管理员领地；恰好一块就认回它（写回 id、按库写满状态，记 WARN），不再新建。别的情形（库回退、备份恢复之后 id 对不上）交给 OP 的 `relink`。

#### 绑定已有的管理员领地：`/district bind <academyId> <pos> [confirm]`

- 维度取执行者所在的世界，控制台为主世界。要绑别的维度，用 `/execute in <维度> run district bind …`。
- 坐标用 `ColumnPosArgument`，取那一列上的顶层领地（`districtClaimAt`，只比 X/Z）。

**不带 `confirm`：** 只预览，不写任何东西。列出：
- 领地 id、名字、范围；
- 高度：全高，或者"底在 y，会补到世界底"（20.3）；
- 有几个子领地，其中几个像是本模块建的；
- 有哪些组、几个成员；
- 假玩家、药水与放行清单各有几项（都会清空）；
- 一行提示："确认后将删除其上全部 N 个子领地、全部组和成员"。

**带 `confirm`：**

1. 按顺序检查，任一不过即拒绝（新的命令结果，不进 `WebUiErrorCodes`）：
   1. 学院存在；
   2. 学院没有在用的自管区；
   3. 功能为 LIVE（否则 `FLAN_UNAVAILABLE`）；
   4. 那里有领地（`CLAIM_NOT_FOUND`）；
   5. 是管理员领地（`CLAIM_NOT_ADMIN`）；
   6. 是 2D 领地（`CLAIM_NOT_FULL_HEIGHT`，20.3；2026-09-30 复核补）；
   7. 没有被别的**在用**自管区绑着（`CLAIM_ALREADY_BOUND`）；
   8. 如果被已解绑的区绑过，只能是同一个学院（否则 `OVERLAPS_DISTRICT`）；
   9. 以这块领地的 X/Z 作为范围，通过与建区相同的重叠规则。
2. 一个事务：插入自管区行，`flan_claim_id` 直接写成这块领地的 id，范围取领地的 X/Z，其余同建区。
3. 提交之后收编（`DistrictFlanSync.adoptDistrictClaim`）：
   1. 强制备份；
   2. 删掉其上全部子领地；
   3. 删掉全部组与成员，清空假玩家、药水与六张放行清单；
   4. 设名字；
   5. 底不在世界底的补到世界底（`extendDistrictClaimToBottom`）；
   6. 显式存盘；
   7. 写满父领地的状态。

   任一步失败就置对账标记，下一轮对账接着做；每一步都是幂等的。

同一块领地不能同时被两个在用区绑定。这一条不靠给 `flan_claim_id` 加唯一索引（那要开 V10，与 4.4 的合并顺序约束冲突），而是在绑定的事务里查。服务器线程是单线程，事务里的检查就够了。

#### 重新绑定（关 P2 的遗留）

同一个学院把自己的旧地重新绑回来：
- 旧的父领地还在（解绑不动 Flan），所以 `/district create` 会被 `FLAN_CLAIM_EXISTS` 拦下，改用 `/district bind` 收编旧的父领地。
- 收编会删掉旧地块的全部子领地。新区从 1 号地块重新编号，旧地块在库里原样归档。旧地块记着的领地 id 从此指向不存在的子领地；归档区永不对账，所以无害。

沿用旧子领地（把旧户主的地"接回来"）不做，原因有三：
- 旧户主不一定还是住户；
- 旧编号与新区的编号冲突；
- 解绑本身已经宣告这些地块结束。

#### 父领地丢了：`/district claim <districtId> recreate`

库里记着的父领地在 Flan 里找不到时（有人执行了 `/flan adminDelete`，或恢复了更早的备份），对账只报告、不重建。OP 确认后用这条命令：
1. 在库里的范围上新建一块父领地（检查与步骤同新建）；
2. 把新的领地 id 写回库；
3. 跑一轮 `resync`，全部地块按库重建。

那片地上还有顶层领地时 recreate 被 `FLAN_CLAIM_EXISTS` 拒绝（文案提示 `relink`）。

#### 改认那片地上的领地：`/district claim <districtId> relink <pos> [confirm]`（2026-09-30 复核补）

恢复了不同时刻的领地备份或 `miningdim.db`、建父领地之后没能把 id 写进库：库里记着的 id 在 Flan 里找不到（或为空），那片地上其实还有一块管理员领地。这时 recreate 被"这片范围已有领地"拒绝，bind 又被"学院已有自管区"拒绝，原先只能站在领地里执行未写进文档的 `/flan adminDelete`。relink 让本区改认那一列上的顶层管理员领地：

1. 位置取本区所在的维度。要求本区在用、功能为 LIVE、本区现在的父领地确实找不到（找得到回 `CLAIM_PRESENT`）。
2. 检查同 bind：那里有领地、是管理员领地、是 2D、没被别的在用区绑着、被已解绑的区绑过时只能是同一个学院。
3. 范围取领地的 X/Z，按改范围的规则校验（不压别的区；每块地都在范围内、离边界不少于 2 格）。
4. 不带 `confirm` 只预览（同 bind 的预览，另列范围的变化）。带 `confirm`：一个事务里写回领地 id（与范围），提交后跑一轮 `resync`——先强制备份；按库重写父领地（组、成员、杂项，底补到世界底）；带着本区组名的子领地按组名认回（地块记着的 id 本来就在这块父领地下时原样留着），外来的删掉，缺的地块重建。

#### 改范围：金锄头 + `/district bounds <districtId> sync`

决定：改自管区的范围，走"OP 在游戏里用金锄头改领地，再执行 `bounds sync` 读回库"，不走反射改范围。

理由：

1. 反射路线要先 `deleteClaim` 把领地移出索引，再 `copySizes`，再反射调私有的 `addClaim` 放回去。中间任何一步失败，领地就既不在区块索引里、也不在存盘用的 `playerClaimMap` 里，下一次存盘它就从文件里消失了。这是一个静默的、灾难性的失败模式。
   - 全公开的替代路线 `toggleAdminClaim` 一样是删了再加，而且需要一个在线玩家（控制台用不了）。
2. Flan 自己的 `resizeClaim`（金锄头走的就是它）会查全部顶层领地冲突和别的圈地模组，我们复刻不全。
3. 改自管区范围是 OP 偶尔才做的事，多一步手工操作可以接受。
4. 我们的代码对父领地的几何只读，范围只有一个真相来源。

金锄头怎么用（写进运维说明）：
- 2 级 OP 手持金锄头，在普通圈地模式下，先点父领地的一个角，再点新的位置。OP 对管理员领地有 `EDITCLAIM` 绕过。
- Y 会被压到世界底，所以改完仍是全高。

`/district bounds <districtId> sync` 的步骤：

1. 要求功能为 LIVE，且父领地找得到。
2. 读 `getDimensions()` 的 X/Z。与库里相同就回"已经一致"。
3. 按 `setBounds` 的规则校验：
   - 不压别的在用区，也不压别的学院的已解绑区；
   - 每块地都在新范围内，且离边界不少于 2 格。
4. 通过：写库，记审计 INFO；下一轮对账会清掉范围漂移。

   不通过：列出落在范围外的地块（或压到的区），库不动，提示"请用金锄头把领地改回能容下这些地块的范围"。改好之前：
   - 对账每一轮都报告范围漂移；
   - 地块超出父领地的那一部分在 Flan 里没有保护，因为 Flan 只在父领地的范围内找子领地。

`/district bounds <districtId> <from> <to>`（阶段 1 的只改库）：
- 只在本区还没有父领地时可用，也就是 DEGRADED 时建的区；之后 `ensureDistrictClaim` 会按库里的范围把父领地建出来。
- 已有父领地时拒绝，新结果 `BOUNDS_FOLLOW_CLAIM`，提示改用金锄头加 `bounds sync`。
- 真网关的 `resizeDistrictClaim` 相应地只核对、不改：范围一致报成功；不一致报"领地范围和库里不一致，请用金锄头改好后执行 /district bounds sync"。

#### 解绑（不变）

D7 仍然一个 Flan 方法都不调。后果写明如下：
- 父领地、居民组和成员、全部地块子领地都原样留在 Flan 里；
- 已解绑学院的住户仍保有公共区域的居民权限，户主仍保有自己的地块。

要收回这些权限，OP 自己用 `/flan` 删，或者日后重新绑定时由 `bind` 收编。

### 20.7 每个网关操作对应的 Flan 调用

类名前缀 `io.github.flemmli97.flan.` 从略。"两角"一律指 `new BlockPos(minX, level.getMinBuildHeight(), minZ)` 与 `new BlockPos(maxX, level.getMinBuildHeight(), maxZ)`。

| 网关方法 | Flan 调用 | 方式 | 要点 |
|---|---|---|---|
| 取世界与存储 | `server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dim)))`，再 `claim.ClaimStorage.get(level)` | 直接 | 维度没加载就报失败"维度 … 没有加载" |
| `findDistrictClaim` | `storage.getFromUUID(id)`；要求非 null、`!isRemoved()`、`!isSubclaim()` | 直接 | 只查顶层领地。不用 `getAdminClaims()`：维度里没有管理员领地时它会空指针 |
| `districtClaimAt(dim, x, z)` | `storage.getClaimsAt(x >> 4, z >> 4)`，再按 `getDimensions()` 的 X/Z 闭区间过滤 | 直接 | 不用 `getClaimAt`：它连 Y 一起比 |
| `claimsIntersecting` | 复制 `storage.getClaims()` 的全部值（含 null 键下的管理员领地），逐块比 X/Z | 直接 | `getClaims()` 返回的是活的 Map，先复制再遍历 |
| `createDistrictClaim` | 暂存 `ConfigHandler.CONFIG.defaultGroups` 并换成空的 `HashMap`；`storage.createAdminClaim(两角, level, false)`；`finally` 里换回。然后 `setClaimName(去掉 % 的名字)`，并断言 `groups().isEmpty()` | 直接（临时换公共字段） | 返回 null 表示与某块顶层领地重叠。两角的 Y 相同，所以深度下探被压到世界底，结果是全高 2D |
| `resizeDistrictClaim` | 只读 `getDimensions()` 比对 | 直接 | 不改领地（20.6） |
| `createPlotClaim` | 先查：区域在父领地的 X/Z 内，且不压兄弟地块。`parent.tryCreateSubClaim(两角, false)`，返回空集即成功；新地块是 `getAllSubclaims()` 的最后一个，并核对范围相符。清理：逐个 `permissions.remove(k)`（反射），逐个 `setPlayerGroup(u, null, true)`，逐个 `removePotion(e)`；再 `setClaimName`、`setDirty(true)`。关上：对三个 `p_<plotId>_…` 组的每个非全局 id `editPerms(null, g, rl, 0, true)`，默认的每个非全局 id `editGlobalPerms(null, rl, 0)`、全局 id `editGlobalPerms(null, rl, -1)` | 直接 + 反射 | 清理或关上任一步失败 → `parent.deleteSubClaim(plot)`，再报失败 |
| `extendDistrictClaimToBottom` | `claim.extendDownwards(new BlockPos(minX, level.getMinBuildHeight(), minZ))` | 直接 | 3D 拒绝；已到底不写。Flan 放方块时也调它：只降低 2D 的 `minY`、标脏、通知网页地图 |
| `findPlotClaim` | 在父领地的子领地 id 索引里找（没命中或已失效就用 `parent.getAllSubclaims()` 重建），要求 `!isRemoved()` 且仍挂在这块父领地下 | 直接 | 子领地不在 `claimUUIDMap` 里；它的 id 只在兄弟之间唯一 |
| `listPlotClaims` | `parent.getAllSubclaims()` | 直接 | 返回的是一份不可变副本 |
| `resizePlotClaim` | 先查（同上）。范围相同就不动；否则 `plot.copySizes(new Claim(两角, null, level))` | 直接 | 子领地不在区块索引里，不用重排。临时 `Claim` 构造时会给自己套默认组，但它不挂到任何地方，无害 |
| `deletePlotClaim` | `parent.deleteSubClaim(plot)` | 直接 | |
| `setGroupPermission` | `claim.editPerms(null, group, rl, mode, true)` | 直接 | 网关先守卫：父领地只许 `d_` 组，地块只许 `p_` 组；管理类写真拒绝；全局权限拒绝；未知 id 拒绝。值相同（`groupHasPerm`）就不写 |
| `setDefaultPermission` | `claim.editGlobalPerms(null, rl, mode)` | 直接 | 管理员领地不查全局锁。值相同（`permEnabled`）就不写；父领地上"假"与"缺省"视为相同 |
| `deleteGroup` | 反射 `permissions.remove(group)`；该组成员逐个 `setPlayerGroup(u, null, true)`；然后 `setDirty(true)` | 反射 | 直接操作私有字段不会标脏，必须自己标 |
| `setMember` | `claim.setPlayerGroup(uuid, group, true)`，group 为 null 即移出 | 直接 | 管理员领地从不拒绝，Flan 也不校验组名，所以组名由网关守卫 |
| `readMembers` | 反射读 `playersGroups`，复制成 `LinkedHashMap` | 反射（只读） | 公开的替代路线是 `toJson`，但在父领地上它会把每块地一起序列化 |
| `readPermissions` | `groups()`；对每个组、每个已知 id 调 `groupHasPerm`；默认用 `permEnabled` | 直接 | 只列已设置的键。`groupHasPerm` / `permEnabled` 返回 -1 / 0 / 1，已核对字节码 |
| `inspectClaim` | `getDimensions()`、`is3d()`、`isAdminClaim()`、`getClaimName()`、`getAllowedFakePlayerUUID()`、`getPotions()`、六张放行清单的 `size()` | 直接 | |
| `clearExtras` | 对 `getAllowedFakePlayerUUID()` 的每一项调 `modifyFakePlayerUUID(UUID.fromString(s), true)`；对 `getPotions()` 键集合的副本逐个 `removePotion`；六张放行清单各自从尾部逐条 `removeAllowedItem(size() - 1)`；最后 `setDirty(true)` | 直接 | `modifyFakePlayerUUID` 不标脏，必须自己标。第二个参数为真表示移除（已核对字节码）。放行清单不用 `read(new JsonArray())`（20.3） |
| `permissionTable` / `knownPermissions` / `isGlobalPermission` | `PermissionManager.INSTANCE.getAll()`（按对象身份判断是否 `/reload` 过）；`ClaimPermission.getId()`、`.global`、`.defaultVal`、`.requireExplicitSet` | 直接 | |
| `setClaimName` | `claim.setClaimName(去掉 % 的名字)` | 直接 | 比较时用 `getClaimName()`：名字里没有 `%`，`String.format` 原样返回 |
| 备份 | 复制 `storage.getClaims().get(null)`，逐块 `toJson(new JsonObject())` 装进 `JsonArray` | 直接 | 20.5 |
| `flush` | `if (!level.noSave) storage.save(server, level.dimension())` | 直接 | 只在建、绑、重建父领地之后调（20.5） |

测试专用、生产代码不用的调用（不进自检表，只出现在 `FlanTestClaims` 里）：
- `storage.deleteClaim(c, true, ClaimMode.DEFAULT, level)`：清理测试领地。第二个参数为真，表示同时对领地调 `remove()`；已核对字节码。
- 模拟金锄头改范围：`deleteClaim(c, false, ClaimMode.DEFAULT, level)`，然后 `copySizes`，再反射调私有的 `addClaim`。

### 20.8 命令

根节点仍要求 `hasPermission(2)`。新增与改动：

| 命令 | 作用 | 调用 |
|---|---|---|
| `/district status` | 显示：功能状态（关闭 / 降级及原因 / 生效）、Flan 版本、自检问题、当前网关、权限表版本与已知权限数（附未知 id）、上一轮对账（时间、耗时、切片数、改回项数、修不了的差异数）、各维度的备份目录与最新文件。功能关着时也能用 | 查询 |
| `/district bind <academyId> <pos>` | 预览要收编的领地，不写 | 查询 |
| `/district bind <academyId> <pos> confirm` | 绑定并收编（20.6） | `DistrictAdminService.bindDistrict` |
| `/district claim <districtId> recreate` | 父领地丢了时，按库里的范围重建并 resync（20.6） | `DistrictAdminService.recreateDistrictClaim` |
| `/district claim <districtId> relink <pos> [confirm]` | 父领地找不到、那片地上却还有一块管理员领地时，改认它并 resync；不带 confirm 只预览（20.6，2026-09-30 复核补） | `DistrictAdminService.relinkDistrictClaim` |
| `/district bounds <districtId> sync` | 读父领地的范围，校验后写库（20.6） | `DistrictAdminService.syncBoundsFromClaim` |
| `/district bounds <districtId> <from> <to>` | 只在本区还没有父领地时可用（20.6） | `setBounds` |
| `/district inspect <districtId>` | 对账预演：列出每类差异的前 10 条并计数，不写 | `DistrictReconciler`（预演） |
| `/district resync <districtId>` | 立即对账并写回：先强制备份；外来子领地、没有墓碑的本模块子领地也删 | `DistrictReconciler`（显式） |
| `/district machines <districtId>` | 阶段 3：该区及外围 8 格内已加载区块里的机械动力"机器"（方块实体），按方块 id 计数、每种前 10 个坐标，并报没加载的区块数（22.8）。返回找到的个数（至少 1） | `CreateMachineScan` |
| `/district personalclaims <districtId>` | 阶段 3 收尾：与本区禁圈区（区 ± 8 格）相交的全部个人领地：主人、X/Z 范围、2D 或 3D（带 Y）、位置（压进区内 / 外围离区边几格 / 本区父领地）、领地 id 前 8 位；压进区内的在前，至多 20 行；末尾一行处理办法。只读，不删不改。领地对接没有生效时回 `flan_unavailable`。返回找到的块数（至少 1）（22.20） | `BufferClaimReport` |

- **个人圈地限制的改动**（22.20）：`create`、`bind … confirm`、`claim … relink … confirm`、`bounds … sync` 成功后，外围 8 格内（含区内）有个人领地时回显末尾多一行摘要（`personalclaims.summary`）；`bind` 与 `relink` 的预览多一行（`personalclaims.preview`），范围取 `CommandResult.previewBounds`；`status` 的守卫一行多一项"个人圈地限制 开/关"，另有"运行时出错 N 次（已放行）"、F1/F2 的情况（2/2 / 不完整：缺哪个、各自覆盖什么 / 未安装 Flan；版本未经核对；Flan 的 `permissionLevel` 低于 2 时注明），以及各在用区外围个人领地的计数（领地对接没有生效时"数不了"）。
- **阶段 3 的改动**（22.7、22.8）：`status` 末尾加守卫一节（索引装没装上、区与地块数、两个急停开关与 `denyUse`、索引过期、名单问题；mixin 情况：原版 6 条、机械动力 19 条（复核前 14 条）或"未安装"、版本不是 6.0.8 时注明未经核对），功能关着时也列 mixin 情况；`create`、`bind … confirm`、`bounds … sync` 成功后，已加载区块里有机械动力机器时回显末尾多一行摘要。
- **新增的命令结果**（`DistrictAdminService.CommandOutcome`）：`FLAN_UNAVAILABLE`、`FLAN_CLAIM_EXISTS`、`CLAIM_NOT_FOUND`、`CLAIM_NOT_ADMIN`、`CLAIM_NOT_FULL_HEIGHT`（3D 领地，复核补）、`CLAIM_ALREADY_BOUND`、`CLAIM_PRESENT`（执行 recreate / relink 时领地其实还在）、`BOUNDS_FOLLOW_CLAIM`、`PREVIEW`（bind / relink 还没确认）。每个都加对应的语言键 `district.miningdim.command.*`。`flan_claim_exists` 的文案另提示 relink。
- **返回值：** 口径同第十六章。`status`、`bind` 与 `relink` 的预览返回 1；`resync` 返回成功的项数（至少 1）；一切拒绝返回 0。
- `/district status` 的上一轮对账注明中止的原因：连续写失败，或数据库出错（复核补，20.4）。
- **功能 OFF 时：** 除 `status` 外一律回语言键 `district.miningdim.command.disabled`（"自管区功能没有开启：serverconfig/miningdim-district.toml 的 enabled 为 false"），返回 0。

### 20.9 生产开关与导航入口（关原 TODO 20.9、P15）

#### 开关

`DistrictConfig` 放在 `com.miningdim.district`，写法照 `TitleConfig`。在 `DistrictSystem.register` 里注册：`registerConfig(ModConfig.Type.SERVER, DistrictConfig.SPEC, "miningdim-district.toml")`。

```toml
[district]
	# 自管区总开关。false: 整个模块不启用 (不绑定服务、不碰 Flan、平板不显示入口)。
	# true: 装了 Flan 1.20.1-1.11.16 且自检通过时正式启用; 否则降级 (见日志与 /district status)。
	# 改完要重启服务器才生效。
	enabled = false
```

- **只在 ServerStarting 读一次，改了要重启。** 功能开关牵涉绑定服务、选网关、开服备份与对账，热切换的中间状态不值得处理。
- **文件名以 `miningdim-` 开头。** GameTest 的配置监视守卫和清理钩子都按这个前缀认文件。GameTest 每轮都清掉 serverconfig，所以在 GameTest 服务端上恒为 OFF；需要生产路径的用例自己翻 `DistrictFeature`（见下）。
- **文档：** `docs/modules/README.md` 的配置表加一行，由 15 份变成 16 份；第二章表格的"配置"一行改写。

#### 功能关着时

- `DistrictServices` 不绑定。登录登出钩子、定时收回、对账全都不跑（它们本来就先看 `isRegistered()`）。
- `/district`：见 20.8。
- 平板：26 条动作在 `DistrictWebUiActions.ctx()` 取不到 context 时，抛新码 `DISTRICT_DISABLED`（"自管区功能没有开启"，不可同 id 重试，无 params），不再落进派发器的通用兜底。
  - 这照 `QUEST_DISABLED`、`CASE_DISABLED` 的先例；
  - `everyThrownCodeIsARegisteredConstant` 会自动覆盖它；
  - 15.3 的码表加一行。
- 关着的这段时间里登录的玩家，不会进见过的玩家表。再打开时，10.2 第 3 步的 playerdata 兜底认得出他们，不用额外处理。

#### 导航入口：由 `hub.panels` 报告

决定：服务端经已有的 `hub.panels` 报告"自管区能不能进"，平板外壳按它决定显不显示导航入口。功能不是 LIVE 时，这一项**不下发**，而不是下发成锁着的。

**服务端（wok-webui）：**
- 新类 `HubPanelGates`（`com.miningdim.webui.server`），提供 `register(String panelId, BooleanSupplier visible)`。
- `HubWebUiActions.PANEL_IDS` 在 `case` 与 `settings` 之间加 `district`。属于"受门控"集合的面板（目前只有 `district`），只在有人登记了门、且门返回真时才下发；没有登记就不下发。
- `DistrictSystem.register` 登记 `HubPanelGates.register("district", DistrictFeature::live)`。
- wok-webui 不会因此依赖 wok-district：依赖方向是 district → webui，本来就有。不新增依赖债，D029（webui → quest）也不受影响。

**前端：**
- `TabletShell.tsx`：删掉 `previewOnly`，改成 `hubGated?: true`。外壳加一条 `useMockAction('hub.panels', EMPTY_PAYLOAD)`：与首页用同一个无入参的键，可批量，已在首页的预取表里。带 `hubGated` 的入口，只在 `hub.panels` 已就绪、列出了该 id 且 `enabled` 为真时显示；未就绪或请求失败一律不显示。这与 isOp 的口径相同："宁可晚一帧出现，也不能先画出来再收回去"。
- `lib/types.ts`：`HubPanelId` 加上 `'district'`；注释"恒 12 条"改成"12 条；自管区生效时 13 条，排在 case 之后"。
- `lib/panels.ts`：`HUB_PANEL_META` 加 district（路由 `ROUTE_DISTRICT`、名称"自管区"、图标与导航相同）。不加锁文案：它从不以锁的形式出现。
- `lib/bridge.mock.ts`：`HUB_PANEL_IDS` 与 `mockHubPanels` 加上 district（启用）。
  - 开发构建（无宿主）因此照旧显示入口，`PreviewRoleSwitcher` 和假后端都不变；
  - 开发构建接了真宿主时，由真服务端决定。
- 首页磁贴随 `hub.panels` 自动出现。

**为什么选 `hub.panels`，而不是握手或 `district.state`：**
1. 原 TODO 20.9 的本意就是"导航与面板开关两处一起改"。`hub.panels` 正是服务端对"这个面板现在能不能进"的权威回答，首页磁贴与导航自然一致。
2. 握手是契约自检；往里塞功能开关，会把两件事搅在一起，而且握手只在页面启动时调一次。
3. `district.state` 只有自管区页在用，外壳要为它多发一条重请求。
4. 选"不下发"而不是"下发成锁着"：功能没开的服务器上，玩家根本不该知道有这个页面。`enabled: false` 的语义是"看得见、进不去"。

**直接输网址进 `ROUTE_DISTRICT`：** OFF 时各动作回 `DISTRICT_DISABLED`；DEGRADED 时 6 条读动作照常，20 条写动作回 `DISTRICT_DISABLED`（文案带降级原因，P21，2026-09-30 复核改；原先是"照常工作，领地写入如实报失败"：库照写、Flan 不跟着改，买地扣钱却没有保护）。不另做路由拦截。

**测试：**
- `WebUiHubStatusGameTests` 在 GameTest（OFF）下仍断言 12 条、顺序不变，只改注释。
- 新增 `DistrictFeatureGameTests.hubPanelsListDistrictOnlyWhenLive`：用 `DistrictFeature.forceForTest(...)` 翻状态。这个方法只在 GameTest 服务端上可用，门同 `forceVerdictForTest`。
  - 翻成 LIVE：13 条，district 在第 11 位；
  - DEGRADED、OFF：12 条。
- 前端 `pnpm build`、`lint`、`check:contract` 全部通过。

#### 什么时候打开

服主在测试服按 20.12 逐条实测过之后，把正式服的 `enabled` 改成 `true` 并重启（P15）。代码里不再有"只在开发构建里显示"的开关。

### 20.10 GameTest 计划（真 Flan）

约定（在 18.1 的基础上）：

- **类与 batch。** 新 batch `district_flan_real`，登记处 `district/flan/real/FlanRealGameTests`，用例体在同包的 `FlanRealScenarios`（2026-09-30 复核拆开）。
  - 登记处的每个方法签名里只有 `GameTestHelper`：先 `requireFlan(helper)`（只用 `ModList`），再调 `FlanRealScenarios` 里的同名方法。GameTest 框架登记用例时对 holder 调 `getDeclaredMethods()`，要解析每个声明方法（连同 lambda 生成的合成方法）签名里的类型；签名里带 `Claim` 的话，没有 Flan 的运行时整个 GameTest 服务端都起不来。
  - S1 下 Flan 不在就失败；`-PwithoutFlan` 的专门一轮（系统属性 `miningdim.district.withoutFlan`）里按它算通过。
- **夹具 `DistrictTestEnv.openWithRealFlan(helper)`：**
  - 自检（`FlanCompat.check()`）不过，直接失败，并带上问题清单；
  - 网关用测试构造，注入时钟和本用例专用的备份目录；
  - `close()` 删掉本用例建过的全部顶层领地（`storage.deleteClaim(c, true, ClaimMode.DEFAULT, level)`），再复原门面。
- **坐标。**
  - 每条用例一个固定槽位：X、Z 从 2,000,000 起，每个槽位错开 5,000 格，远离 GameTest 结构（都在原点附近）和别的用例。
  - 不用 `abydos()` / `millennium()` 的默认范围：0..199 与 1000..1199 会压在原点附近的测试结构上。夹具新增 `districtAt(academyId, DistrictBounds)`。
  - 领地是全高 2D，建领地和判定都不加载区块。
- **玩家。**
  - 判定用 `new ServerPlayer(server, level, new GameProfile(uuid, name))` 构造，不放进世界，也不进玩家列表。
  - **不用** `makeMockServerPlayerInLevel` / `MockGameTestPlayers`：它们返回匿名子类，而 Flan 按 `getClass() != ServerPlayer.class` 把它当成假玩家，权限会被改写成 `flan:fake_player`。
  - 先断言 `!player.hasPermissions(2)`。
- **判定入口。** 玩家用公开 API `ClaimHandler.canInteract(player, pos, perm)`；全局权限用 `ClaimStorage.get(level).getForPermissionCheck(pos).canInteract(null, perm, pos, false)`。
- **同步。** 全部用例同步完成，不跨 tick。对账用"整轮同步跑完"的测试入口，不经时间切片。

用例清单：

| 用例 | 断言 |
|---|---|
| `selfCheckPassesOnTheApprovedFlan` | 版本串是 `1.20.1-1.11.16`；签名表全过；必需的 id 齐全；15 个全局权限恰好是 `FlanPermissions.GLOBAL`；开发环境（没装机械动力）已知权限 66 个 |
| `selfCheckMismatchFallsBackWithoutAnyWrite` | 传入一张写错一项的表（例如 `editPerms` 少一个 boolean）：自检不过，问题清单点名那一项；`GatewaySelector` 选出 Disabled。再经服务建区、加住户、划地块：Flan 里管理员领地的数量不变，每块已有领地的 `toJson` 前后一致；名单行和地块都是 `failed`，原因里带"自检未通过" |
| `districtClaimIsFullHeightAdminWithoutDefaultGroups` | 先断言 Flan 配置的 `defaultGroups` 非空（开发配置自带 Co-Owner、Visitor）。建区之后：是管理员领地；X/Z 相符；`minY == level.getMinBuildHeight()`；不是 3D；组只有 `d_<id>_resident`。配置的 `defaultGroups` 前后是同一个对象，内容不变 |
| `residentsGroupCarriesPublicPermissionsAndMembers` | 加两个住户，一个 synced、一个 pending：父领地的成员只有 synced 那位；居民组逐格等于住户列加全表；默认等于外人列；四个管理类权限在组和默认里都为假。移出住户之后：TA 不在父领地，也不在任何地块 |
| `plotCreationScrubsInheritedGroupsMembersAndPotions` | 父领地有居民组和成员，另用 `/flan` 同款调用 `addPotion` 加一种药水。划地块之后：地块的组恰好是三个 `p_` 组，没有 `d_`；成员只有期望的人；药水为空；地块任一组的内层 Map 与父领地的都不是同一个对象；改地块的组不影响父领地（前后 `groupHasPerm` 相同） |
| `groupNamesAreUniquePerPlot` | 两块地；改 A 的朋友列，B 的三个组逐格不变 |
| `newPlotWritesEveryNonGlobalPermissionAndUnsetsEveryGlobal` | 地块的三个组和默认，对全部 51 个非全局 id 都有显式值（`groupHasPerm` / `permEnabled` 不是 -1）；15 个全局 id 在地块上都是 -1，包括构造时预填为真的 `lock_items`、`snow_golem` |
| `audiencesResolveThroughRealFlan` | 一块有户主的地，三列设成能区分的值：朋友能放不能拆，其他住户只能开门，外人什么都不行。户主、朋友、其他住户、外人四个真 `ServerPlayer`，在地块内对 `place`、`break`、`door`、`open_container`，以及一个跟随项（`jukebox` 跟随 `container`）的判定逐个相符 |
| `publicBreakForResidentsDoesNotLeakIntoPlots` | 泄漏测试。公共区域住户列的"破坏方块"打开：住户在公共区域能拆；同一个住户在别人的地块里不能拆（TA 在那块地的居民组，居民列的破坏关着）；删掉 TA 在那块地的成员资格（模拟同步失败）仍不能拆，因为落到了外人列；外人两处都不能拆 |
| `regionRulesLiveOnTheParentAndPlotsFollow` | 全区列"爆炸伤害"关着：地块内 `explosions` 判定为假；打开之后地块内立刻为真，而地块的 `permEnabled(explosions)` 始终是 -1。`mob_spawn` 按取反写：目录"允许怪物自然生成"为真，对应 Flan 的 `mob_spawn` 为假。其余 8 个全局权限在父领地上等于各自的 `defaultVal` |
| `frozenPlotDeniesEveryoneAndKeepsSavedSettings` | 移出户主触发冻结：原户主、朋友、其他住户、外人对 `place`、`break`、`open_container`、`can_stay` 全部为假；地块成员为空；库里的三列和朋友前后逐格相同。解冻之后，判定回到冻结前 |
| `reconcileRevertsManualFlanEdits` | 同步好之后，用 `/flan` 命令底层的同一批公开调用（`editPerms`、`editGlobalPerms`、`setPlayerGroup`、`modifyFakePlayerUUID`、`addPotion`）做这些改动：给朋友组开"破坏"、把陌生人放进户主组、改父领地的一格默认、给父领地加一个 `Visitor` 组、加假玩家和药水。跑一轮对账：全部改回；报告的改回项数与种类精确相符；地块为 `synced`；`needs_reconcile` 被清掉。再跑一轮：零改动，领地不被标脏 |
| `reconcileHandlesForeignAndOrphanSubclaims` | 父领地下另建一块外来子领地（直接调 `tryCreateSubClaim`），再造一块本模块的孤儿（删地块时注入推送失败）。自动对账删掉孤儿（有墓碑），外来的只报告不删；`/district resync` 之后外来的也没了。另造一块错位的本区地块（库里的领地 id 改成一个不存在的）：它被认领，不新建 |
| `reconcileNeverTouchesUnboundClaims` | 附近另有一块无关的管理员领地、一块玩家领地、一个已解绑区的父领地。建区、划地、对账、resync 全跑一遍：这三块领地的 `toJson` 前后逐字相同 |
| `bindAdoptsAnExistingAdminClaim` | 先用 `createAdminClaim` 建一块带默认组的管理员领地，再加成员、两块子领地、假玩家和药水。`bind` 预览不改任何东西。`confirm` 之后：组只剩居民组；成员只剩住户；子领地为零；假玩家和药水为空；库里记着这块领地的 id。已被在用区绑着的领地、玩家领地分别被拒 |
| `boundsSyncReadsTheClaimAndRejectsPlotsOutside` | 用测试专用工具模拟金锄头改范围，`bounds sync` 写库。再缩到压住一块地：拒绝并列出该地块的编号，库不变，对账报告范围漂移 |
| `backupsAreWrittenOutsideDataClaimsAndRotated` | 一批改动之后，备份目录下出现一个 `.AdminClaims.json.bak`：路径不在任何维度的 `data/claims` 之内；内容是 JSON 数组，含本区父领地的 id 与嵌套的地块。10 分钟内再来一批，不新增文件（时钟可拨）；拨过 10 分钟就新增。`resync` 不受节流，照样新增。用测试调小池的上限，超出后只删最旧的，且只删符合命名规则的文件。备份目录不可写时：本批不写 Flan，地块记 `failed` |
| `pushesWriteOnlyDifferences` | 同一块地、库不变，连续两次 `writePlotState`：第二次之前先 `setDirty(false)`，之后 `isDirty()` 仍为假 |
| `gatewayTurnsFlanExceptionsIntoFailures` | 对一块已删除领地的句柄写入：返回失败，而不是抛异常。在非服务器线程上调用：返回失败 |

不需要真 Flan 的新增或改动（记录型网关或纯函数，放在原来的类里）：

- **`FlanPermissionPolicyGameTests`**（batch `district_flan_policy`）：给一张合成的已知权限表，里面含一个假想的非全局权限和一个假想的全局权限。断言每个位置的值都与 20.3 的表一致；`claim_message` 归管理类；冻结时全假；地块上的全局权限一律 UNSET。
- **`DistrictFlanSyncGameTests`：**
  - 新增 `writesAreOrderedRestrictiveFirst`：在"写第一个真值"处注入失败，停下来的地块没有任何一格比期望宽。
  - 新增 `permissionTableReloadRebuildsPolicy`：假权限表换了身份，版本号加一，并安排一轮对账。
  - `recordingGatewayIsGameTestServerOnly` 改为按 Flan 在不在位，断言选出真网关还是 Disabled。
  - `disabledGatewayReportsNotEnabled` 改为断言带原因的文案。
- **`DistrictFeatureGameTests`**（batch `district_feature`）：
  - `hubPanelsListDistrictOnlyWhenLive`；
  - `actionsReportDistrictDisabledWhenOff`：26 条动作全部回 `DISTRICT_DISABLED`；
  - `commandsReplyDisabledExceptStatus`。
- **`DistrictCommandGameTests`：** `createRejectsWhereAFlanClaimExists`、`boundsWithCoordinatesFollowTheClaimOnceBound`、`bindPreviewWritesNothing`（后者用记录型网关）。

真实的 `/reload` 不在 GameTest 里做：它是异步的，会重载全服数据包，打扰同一批里的其他用例。身份比较的逻辑用上面的合成表测；真实 `/reload` 留给阶段 4。

跑法同 18.1：
1. 先删 `run/world`，顺带删掉上一轮留下的 Flan 领地文件和备份目录；
2. 执行 `gradlew compileJava verifyModuleBoundaries runGameTestServer`（S1：真 Flan 用例就在这一次里，不另跑带属性的一次）；
3. 判断是否全过一律看日志里的 `All N required tests passed` 一行：服务端起不来时 Gradle 照样报成功（20.1 探路结果）。

另有一轮不进默认门、偶尔跑一次的：删 `run/world` 后 `gradlew runGameTestServer -PwithoutFlan`。开发运行时不加载 Flan，核对正式服或单人存档没装 Flan 时的那条路：整个服务端照常起来，非 Flan 的用例全过；真 Flan 的用例在门口算通过；`recordingGatewayIsGameTestServerOnly` 改为断言真实的开服选择选出 Disabled（原因"Flan 没有安装"），`selfCheckReportsEverySignatureMismatch` 改为断言签名表每一项都报缺类（20.1）。

2026-09-30 复核补的用例：

| 用例 | 断言 |
|---|---|
| `plotCreationScrubsInheritedGroupsMembersAndPotions`（加强） | 直接调网关的 `createPlotClaim`（差异写入之前的那一刻）：继承来的 `d_` 组、成员与药水都没了；三个 `p_` 组与默认对全部非全局 id 都是 0，全局 id 都是 -1；公共区域能拆的住户在里面拆不了，外人连"捡东西"也没有。把清理改成只起名（复核的变异 M1）时这条失败 |
| `unrecordedPlotClaimIsDeletedAgain` | TEMP 触发器让 `UPDATE district_plot SET flan_claim_id` 失败：子领地删掉，地块 failed（"没能记进数据库"）；去掉触发器后下一次写入重建、synced |
| `strayDistrictClaimIsRelinkedNotDuplicated` | TEMP 触发器让 `UPDATE district SET flan_claim_id` 失败：领地建成了、库里没有 id；下一轮对账认回这一块，不建第二块 |
| `relinkAdoptsTheLandsClaimAfterARestore` | 库里的父领地 id 换成一个不存在的（恢复了不同时刻的库）：recreate 被 `flan_claim_exists` 拒绝；relink 预览不写；确认后改认原来那块，地块的子领地原样认回，判定照旧，先强制备份；领地还在时 relink 被拒 |
| `bindExtendsAShallowClaimToTheWorldBottom` | 在 y=64 圈的 2D 管理员领地底在世界底之上、底下是野外；3D 的被拒（`claim_not_full_height`）；预览点明底偏高；收编补到世界底，地块的地下外人拆不了；底又被抬高后自动对账补回并记一项改动 |
| `allowListsAreClearedOnBindAndReconcile` | 父领地的 `allowedUseBlocks` 里有箱子：地块里的判定落在父领地上（`getForPermissionCheck`）；收编清空；清空之后还加得回去（名字索引没坏）；再加回去时自动对账再清一次并记一项改动 |
| `reconcileRepairsReachDiskEvenWithoutOtherEdits` | 父领地上一个没有成员的 `Visitor` 组和一个假玩家，存盘之后不脏；对账只做这两项改动，父领地必须被标脏，再存盘之后 `!AdminClaims.json` 里就没有它们（去掉 `deleteGroup` / `clearExtras` 的 `setDirty` 时——复核的变异 M2——这条失败） |
| `DistrictFlanSyncGameTests.degradedGatewayIsNotMistakenForAMissingClaim` | 降级网关、库里记着父领地 id：推送、预演、resync 都报"领地对接未启用：…"，不报找不到，不置对账标记 |
| `DistrictFeatureGameTests.degradedMakesTabletWritesReadOnly` | DEGRADED：20 条写动作回 `DISTRICT_DISABLED` 并带原因，库不写；读动作照常；状态复原后写动作照常 |
| `DistrictReconcileGameTests.schedulerSurvivesThrowingEntries` | 一个地块条目抛异常：记成失败，另一块照常改回，这一轮照常结束；TEMP 触发器让写地块生效状态失败：整轮中止，原因"数据库出错" |
| `DistrictReconcileGameTests.reconcileDeepensShallowParentsAndClearsAllowLists` | 记录型网关：底偏高与放行清单在预演里列出、不写；自动对账补底、清单清零；3D 只报告 |
| `ClaimBackupFilesGameTests.unwritableBackupFailsAndIsNotCountedAsRecent`（加强）、`rotationRemovesStaleTempFiles` | 失败之后一分钟内直接报失败、连目录都不去建，满一分钟、目录恢复后写成；轮换删掉超过一分钟的残留临时文件，新鲜的与名字不符的不动 |

实现状态（2026-09-30，B 步：出路 ③，S1）：

- **真 Flan 用例落地**：`FlanRealGameTests`（batch `district_flan_real`，`flan/real` 包），上表 19 条全部实现，另加 3 条，共 22 条：
  - `inspectPreviewsAndResyncRevertsThroughCommands`：`/district inspect` 只列差异、一个字都不写；`/district resync` 先强制备份再改回，返回成功的项数。
  - `recreateRebuildsAMissingDistrictClaim`：父领地被 `/flan adminDelete` 同款调用删掉之后，自动对账只置标记；`/district claim <id> recreate` 重建父领地、resync 重建地块，户主的判定回来。
  - `saveAndReloadNeedsNoRewrites`：把父领地按存盘格式 `toJson`、再用读盘的 `Claim.fromJson` 读回来换进存储（模拟存盘 + 重启），父领地上的"假"变成"缺省"、地块上的"假"原样保留，对账零改动、领地不被标脏，每个人的判定不变。这条锁住了 20.3"父领地默认权限的假与缺省等价"。
- **夹具**：`DistrictTestEnv.openWithRealFlan(helper, slot)`。
  - Flan 不在位或自检不过，用例直接失败并带上问题清单，不跳过。
  - 网关是真网关（`FlanBridge.create`），注入夹具的可拨时钟与本用例专用的备份目录（`<世界>/miningdim/district-flan-backups-gametest/slot-N`，开跑前清空）。
  - 开跑前与 close 时都删掉槽位里的全部顶层领地。
  - 坐标用 `slotBounds` / `slotArea` / `districtAt` / `plotAt` 按槽位原点加偏移。
  - 只有 GameTest 用的 Flan 调用（`deleteClaim`、`transferOwner`、私有 `addClaim`、`Claim.fromJson`、`ClaimHandler.canInteract`、`getForPermissionCheck`）集中在 `FlanTestClaims`，不进开服自检的签名表。
- **与上表的出入**：
  - `selfCheckMismatchFallsBackWithoutAnyWrite`：上表举的例子"`editPerms` 少一个 boolean"在 1.11.16 里恰好有这个重载，写错了也查得到；改为把 `tryCreateSubClaim` 的返回类型写成 `List`，问题清单只点名这一项。
  - `audiencesResolveThroughRealFlan`：另核对 2 级 OP 在别人的地块里什么都能做（服主已拍板不拦），1 级 OP 不绕过、按外人算（P14，原列在 20.12 的实机核对里）。
  - `pushesWriteOnlyDifferences`、`reconcileRevertsManualFlanEdits`：只看父领地的脏标记。Flan 只在顶层领地上记"脏"：子领地的 `setDirty` 转给父领地，子领地自己的 `isDirty()` 停在构造时的 `true`，不能拿来判断。推送一侧另把公共区域开关与住户成员资格各推一遍。
  - `gatewayTurnsFlanExceptionsIntoFailures`：另核对调用链上真抛出的异常（维度 id 不合法，`ResourceLocationException`）与写前预检（外来组、管理类写真、按组写全局权限、未知 id）。
  - `bindAdoptsAnExistingAdminClaim`：另核对这片范围已有管理员领地时建区被拒（`FLAN_CLAIM_EXISTS`，标出 `(admin)`）。
  - `backupsAreWrittenOutsideDataClaimsAndRotated`：两个池的上限调成 2 与 3；目录里另放两个不符合命名规则的文件，轮换后仍在。
- **生产代码的一处改动**：`FlanClaimGateway.inspectClaim` 读玩家领地（不是管理员领地）的名字时，读不到就当空串。玩家领地的 `getClaimName()` 要经玩家名缓存把主人的名字格式化进去（`ClaimUtils.fetchUsername`），没有玩家名缓存的服务端（GameTest 服务端）上直接空指针，整个 `inspectClaim` 就失败了，`bind` 因此把玩家领地报成 `CLAIM_NOT_FOUND` 而不是 `CLAIM_NOT_ADMIN`。本模块从不管玩家领地的名字；管理员领地与它的子领地不查缓存，照常读。
- A 步写的两条"开发环境没有 Flan"的断言改为按 Flan 在位核对：
  - `DistrictFlanSyncGameTests.recordingGatewayIsGameTestServerOnly`：真实的 `DistrictSystem.selectGateway` 走完自检，选出真网关、LIVE；"没装 Flan"一支由注入的 `flanLoaded = false` 覆盖。
  - `FlanPermissionPolicyGameTests.selfCheckReportsEverySignatureMismatch`：版本对但表里另加一个不存在的 Flan 类时，问题清单只点名那个类。
- 不需要真 Flan 的部分（A 步落地，照旧用记录型网关或纯函数测）：
  - `FlanPermissionPolicyGameTests`（batch `district_flan_policy`）：内置表与全表的每个位置、缺 id 与全局标志不符算问题，以及开服自检的签名核对器（用 JDK 的类核对六种情形；没有版本时不通过、缺类时问题清单点名那个类）。
  - `DistrictReconcileGameTests`（batch `district_reconcile`）：改回漂移且第二轮零写入、预演不写、外来 / 孤儿 / 错位子领地、从不碰没有绑定的领地、时间切片、连续失败中止、权限表重读、先收后放、推送只写不同的格子、新地块的清理与写满全表。
  - `ClaimBackupFilesGameTests`（batch `district_flan_backups`）：备份文件一侧（`ClaimBackupFiles` 不引用 Flan）——不在任何 `data/claims` 之内、后缀不是 `.json`、原子写、10 分钟节流、按池轮换且只删符合命名规则的文件、写不出来时如实报失败。
  - `DistrictFeatureGameTests`（batch `district_feature`）：`hub.panels` 只在 LIVE 时下发 district，OFF 时 26 条动作回 `DISTRICT_DISABLED`。
  - `DistrictCommandGameTests` 新增：功能关着时除 `status` 外回 disabled、`createRejectsWhereAFlanClaimExists`、`boundsWithCoordinatesFollowTheClaimOnceBound`、`boundsSyncReadsTheClaimAndRejectsPlotsOutside`、`bindPreviewWritesNothingAndConfirmAdopts`、`recreateRebuildsAMissingDistrictClaim`；`DistrictFlanSyncGameTests.recordingGatewayIsGameTestServerOnly` 改为核对 `GatewaySelector` 的每个分支（含自检不过时真网关根本没被造出来）。
  - `ModDependencyDeclarationGameTests.modsTomlDeclaresFlanAsAnOpenOptionalDependency`。
- 重新绑定改走 `bind` 之后，`DistrictUnbindGameTests`、`DistrictSchemaGameTests.onePlotPerOwnerAcrossDistricts` 与 `DistrictWebUiGameTests.archiveStaysUnderCapAndFlagsTruncation` 里"解绑后在旧地上再建"的夹具改用 `DistrictTestEnv.rebind`。

### 20.11 对前文的改动

- **第二章：** 可选集成改为 `sqlite-jdbc`、`flan`；配置加 `miningdim-district.toml`。
- **第三章：** 类布局按 20.1。
- **8.1：** 接口的增改见 20.1。
- **8.2：**
  - `resyncDistrict` 改由对账实现（显式模式）；
  - 对账也会把 `failed` 的住户修成 `synced`；
  - `needs_reconcile` 由对账清掉。
- **8.3：**
  - "目录外的 Flan 权限"改由 20.3 的全表生成；
  - 地块上的全局权限一律 UNSET；
  - 整块重写改为按差异写、先收后放。
- **8.4 不变式：**
  - 第 1 条推广为"父领地上只有本区居民组；地块上只有自己的三个 `p_` 组"；
  - 第 2 条的管理类加上 `claim_message`；
  - 第 4 条改为"绝不往白名单里**加**假玩家；对账会把白名单清空（`modifyFakePlayerUUID(uuid, true)` 之后自己标脏）"。
- **8.5：** Disabled 只在 DEGRADED 时绑定，回复带原因；选择顺序见 20.2。
- **第九章第 3 条：** 住户由 `failed` 变 `synced` 多一条途径：对账。
- **12.1：** 解绑不调 Flan 的后果，见 20.6。
- **12.3、P2：** 见 20.6。
- **第十六章：** 命令见 20.8。
- **第十八章：** GameTest 见 20.10。
- **第十九章：** P2、P10、P15 已定；新增 P16–P21。

对接说明要回写的几条（实现时一并改那份文档）：
- `PermissionManager.INSTANCE` 从不替换（2.1 的 listPerms 一行）。
- `deleteClaim` 的布尔参数表示"同时对领地调 `remove()`"（2.1 与 2.5 第 11 条）。
- `mob_spawn`、`animal_spawn` 为真都表示"阻止"。
- `GameTestHelper` 造的 mock 玩家会被 Flan 当成假玩家。
- 2.5 第 12 条已由 20.5 定。
- B 步真 Flan 用例核对到的（对接说明 2.6）：子领地的脏标记转给父领地；玩家领地的 `getClaimName()` 要查玩家名缓存；GameTest 服务端的 OP 等级恒为 0；开发运行时靠同 modId 的根 mod 顶掉内嵌的 lingua_bib；存盘再读回之后父领地的"假"变"缺省"、地块的"假"保留。

### 20.12 留给阶段 4 的实机核对

在测试服（装着整合包的全部模组）逐条跑，结果回写对接说明 2.5。执行顺序、准备工作与每条的通过标准汇总在第二十三章（23.4、23.9、23.10），本节保留原始条目：

1. 正式服 Flan 配置的实际值（开服的 INFO 已经记下）。
2. 建区、绑定之后，在 `/flan info` 里看到的范围是全高，组与成员如 20.3 所述。
3. 用金锄头改自管区范围，执行 `bounds sync`，存盘重启：地块都还在，判定不变。
4. 冻结地块 `can_stay` 为假时非 OP 的实际表现：是被挡在边界外，还是在里面被弹出去，传送进来又会怎样。
5. 2 级 OP 在冻结的地块和别人的地块里确实能进出、能操作；1 级 OP 不绕过（P14）。判定层面 B 步的真 Flan 用例已经核对过（`audiencesResolveThroughRealFlan`、`frozenPlotDeniesEveryoneAndKeepsSavedSettings`），这里只看实际进出的效果。
6. 首次登录的离线模式 UUID 成员得到正确的权限。
7. 整合包里的机器（假玩家）在地块里的实际表现，以及它与 `fake_player` 的关系。阶段 3 起机械动力的假玩家另由 22.6 拦，机械动力部分按 22.16 第 7、11 条核对；这里只看别的 mod 的假玩家。
8. 同一个区里相邻地块之间的活塞与水流：阶段 3 起由 22.9 拦，按 22.16 第 13 条核对。
9. 真实的 `/reload`：权限表被重读，对账把新增的权限写上；执行 `/flan reload` 之后建区，仍然没有默认组。
10. 备份恢复演练：按 20.5 的步骤恢复一次，确认世界能打开、对账能补齐。
11. 大区的对账耗时（`/district status` 显示上一轮的耗时与切片数）。
12. `/save-off` 期间建区：不显式存盘；`/save-on` 之后一切正常。

以上全部通过之后，服主打开 `enabled`（P15）。

## 二十一、TODO：阶段 3 及以后

1. （已挪到第二十二章：机械动力禁令，22.4–22.8。）
2. （已挪到 22.20：自管区内和外围 8 格内禁止个人圈地，关 P32。Flan 1.11.16 没有建领地的事件，改为 mixin 挡 `createClaim` / `resizeClaim`。）
3. （已挪到第二十二章：聊天通知，22.10–22.12。）
4. 付款去向与钱仓（P9），以及退地、转让。
5. 地块档位与朋友上限分档（P8）。
6. （已挪到第二十二章：地块边界守卫，22.9。）
7. 契约分页（P7），以及 `errorText.ts` 收录自管区的码。
8. 学院的增、改名命令或配置文件。配置文件要进 `docs/modules/README.md` 的配置表。
9. （已挪到 20.9：导航入口放开随阶段 2 实测完成一起做。）
10. 平板上编辑区规（契约写的是"配置入口本轮不做"）。

---

## 二十二、阶段 3：机械动力禁令、地块边界守卫与聊天通知 (DECIDED；实机核对留给阶段 4)

本章取代第二十一章的第 1、3、6 条，是阶段 3 的实现依据；第 2 条（个人圈地限制）在阶段 3 收尾时由 22.20 补做。

依据：
- 设计基线：分支 `feat/wok-district`，HEAD `7086201d`，阶段 1–2 已全部完成。
- 整合包里的机械动力：`E:\Aurora\.minecraft\versions\1.20.1-forge-47.4.16\mods\[建筑方块]create_consblocks.jar`。
  - 它就是 Create 6.0.8（`mods.toml` 里只有 `modId="create"`、`version="6.0.8"`），文件名只是整合包的分类标签。依赖 `ponder [0.8,)`、`flywheel [1.0,2.0)`（客户端），内嵌 Registrate 1.3.3、Flywheel 1.0.6-beta-266、MixinExtras 0.4.1、Ponder 1.0.91。
  - 它是本地重编译的，不是官方发布的文件：清单写着 2026-01-28 用 Java 21.0.6 构建、`Git-Hash: unknown`；SHA1 `ff2d7b3d941dfeff6d6fd729e6213a5fbb9cae96`，17,694,437 字节；Modrinth 查不到这个哈希（官方 `create-1.20.1-6.0.8.jar` 是 SHA1 `b13d912b…`、19,170,905 字节）。
  - jar 里没有别人加的领地或权限代码；唯一提到 `ftbchunks` 的是机械动力自己的火车地图兼容。
  - **本章所有注入点都是对这个 jar 做 `javap -p -c` 核对出来的**，没有拿官方构件核对。
  - 整合包里依赖机械动力的只有 `some-assembly-required-1.20.1-4.2.2`，只加配方，不登记任何移动检查。
- Flan 1.11.16 对机械动力只有 `CreateCompat.canMinecartPass`：`flan:create_contraption` 为假时拦矿车装置进领地。活塞、轴承、龙门、滑轮、火车、钻头、锯、机械手它都不管。（整合包里 `flan:create_contraption` 没有登记，见 20.3；矿车装置在整合包里也拿不到，23.7 第 8 条记 N/A。）
- 原版与 Forge：开发环境的 Forge 47.3.0（parchment 名），以及 47.3.0、47.4.22 两份源码 jar。正式服跑 47.4.16（缓存里没有），但下文涉及的每个目标类在 47.3.0 与 47.4.22 之间字节码相同（`SpongeBlock` 只差常量池编号），活塞、方块、流体相关的 Forge 事件源码两版相同。
- 整合包 97 个 jar（含内嵌 jar）与 Flan 的 mixin 扫描。
- 登录门分支 `origin/fix/webui-login-gate`（提交 `2af8adc2`，PR #72，本分支没有合入）的 `PlayerLoginGate`。
- 本阶段没有下载任何构件，也没有把机械动力加进构建。

阶段 3 的范围：

| 做 | 不做 |
|---|---|
| 机械动力禁令：区内与外围 8 格内禁放机器，装饰方块照常可放，OP 亲手放置例外（22.4、22.5） | 测试服实机核对（阶段 4，22.16） |
| 机械动力的机器改不了自管区里的方块：钻头、锯、机械手、收割机、犁、压路机、各类装置、蓝图炮、软管滑轮、开口管道；复核补上传送带、对称之杖、机械臂、显示链接、土豆加农炮（22.6、22.19） | 税收、钱仓（另案） |
| 注入失败时报出来，并写明还剩什么保护（22.7） | 自管区内和外围 8 格内禁止个人圈地（设计时不在阶段 3；收尾时补做，见 22.20） |
| 绑定前就在区内的机械动力方块：不拆、不停转，列给 OP 看，非 OP 不能右键操作（22.8） | 让机械动力在 GameTest 里真跑起来（P30） |
| 同一个区里的地块边界：活塞、流体、发射器与投掷器，另加下落的方块、海绵、岩浆点火（22.9） | 火焰蔓延、爆炸、生长、生物破坏跨地块；别的 mod 里不发事件的改方块途径（P38） |
| 按坐标查区与地块的空间索引（22.3） | |
| 聊天通知 15 类，离线的上线后补发；队列表改进 V9（22.10、22.11） | |
| 登录门的接缝：本分支上线即发，两个分支都合入后改为等"登录已确认"（22.12） | |
| 界面与文档文案（22.13）；GameTest（22.14） | |

服主已拍板、本章照做的前提：

1. 机械动力禁令：在每个在用自管区内，以及它外围 8 格内，玩家不能放机械动力的机器方块。OP（2 级）例外。
2. 区外的机械动力机器（钻头、锯、机械手、收割机，以及活塞、轴承、龙门、绳索滑轮、矿车这些装置）不能拆、放自管区里的方块；装置不能带着自管区里的方块组装、移动。
3. 上个赛季允许纯装饰的机械动力方块。
4. 同一个区里的地块之间：活塞不能跨地块边界推、拉、挤碎方块（地块↔公共区域、地块↔别的地块）；流体不能流过地块边界；发射器、投掷器不能往另一块地里放方块、倒流体、用物品。这些都是方块自己在动，不需要为 OP 开例外。
5. 通知用语言键发中文聊天消息；离线的上线后补发；登录确认之前不给看私人内容。

原 TODO 的去向：

| 原 TODO（第二十一章） | 结论 | 见 |
|---|---|---|
| 1. 机械动力禁令 | 按分类放行装饰方块；OP 判定加上"不是假玩家"；机器改不了区内方块 | 22.4–22.8 |
| 2. 区内和外围 8 格内禁止个人圈地 | 设计时不在阶段 3；收尾时补做（关 P32）。界面上的文字（`PublicDirectory.tsx:33`、`AdminPanels.tsx:581`）从此属实 | 22.20、P32 |
| 3. 聊天通知 | 15 类，其中包括"pending 玩家首次登录时提示已加入学院" | 22.10–22.12 |
| 6. 相邻地块之间的活塞与流体 | 活塞、流体、发射器与投掷器，另加下落的方块、海绵、岩浆点火 | 22.9 |

### 22.1 类布局、mixin 配置与开关

#### 类布局

```
com.miningdim.district
├── DistrictConfig             加 [district.guards] 与 [district.createBan] 两节 (见下)
├── guard/                     守卫的全部判定, 都是普通类; mixin 只转调这里
│   ├── DistrictZoneSnapshot    不可变的空间索引快照 (22.3)
│   ├── DistrictZoneIndex       每个 DistrictContext 一个: 从库重建快照, 提交后刷新
│   ├── GuardSettings           三个开关与禁令名单, 开服读一次
│   ├── GuardView               (settings, snapshot, createPolicy), 不可变; OFF 常量什么都不拦
│   ├── DistrictWorldGuards     静态门面: volatile GuardView; 地块边界的各项判定 (22.9)
│   ├── GuardActors             OP 例外与机械动力假玩家的认法 (22.5)
│   ├── GuardEvents             Forge 事件: 放置禁令、机械动力假玩家、denyUse、岩浆点火
│   ├── GuardMixinStatus        记录并核对哪些 mixin 真的应用上了 (22.7)
│   ├── MixinHandlerScan        数处理方法真的织进目标类几处 (22.7, 复核加的)
│   ├── DistrictMixinPlugin     三份 mixin 配置共用的插件 (不能放进 mixin 包; Flan 那份 22.20 加)
│   ├── PersonalClaimGuard      个人圈地限制的判定、提示与放行计数 (22.20, 不引用 Flan)
│   ├── GuardTestZones          GameTest 专用: 在测试结构周围临时装一份区域 (22.14)
│   └── create/
│       ├── CreateBlockPolicy   机械动力方块的分类 (22.4), 按 Block 身份缓存
│       ├── CreateGuards        各个机械动力注入点的判定 (22.6)
│       ├── CreateHookTargets   注入点常量: mixin 注解与离线核对共用同一张表
│       ├── CreateReflection    包内可见的枚举值与字段 (PaveResult.FAIL 等), 解析一次
│       └── CreateMachineScan   /district machines 的扫描 (22.8)
├── mixin/
│   ├── world/                  原版目标, required 配置, 5 个 mixin (22.9)
│   ├── create/                 机械动力目标, 可选配置, 19 个 mixin, 全部 @Pseudo (22.6)
│   └── flan/                   Flan 目标 (ClaimStorage.createClaim / resizeClaim), 可选配置, 2 个 mixin, @Pseudo (22.20)
└── notice/
    ├── DistrictNoticeKind      24 个 kind: wire 值、语言键、颜色 (22.10)
    ├── DistrictNotices         入队、投递、只发在线的广播 (ctx.notices())
    ├── NoticeDeliveryGate      投递时机的接缝 (22.12)
    ├── DefaultNoticeGate       本分支的默认实现: 验证身份的服务器上上线即发, 离线模式下不发 (22.19)
    └── NoticeDeliveryGates     当前生效的 gate, register 时装上
```

说明：
- mixin 包里只能放 mixin 类，普通代码绝不能引用它们（Mixin 会拒绝直接加载）。判定全在 `guard`，mixin 的方法体只有一行转调，外加服务端判断。
- 两个 mixin 包是兄弟包，互不嵌套：一份配置的 package 前缀不能盖住另一份配置的类。插件 `DistrictMixinPlugin` 放在 `guard`，不在任何 mixin 包里。
- `com.miningdim.district.mixin.*` 在 `com.miningdim.district` 前缀之下，归 wok-district。不能放进 wok-core 的 `com.miningdim.mixin`：那样就多出一条没声明的 wok-core → wok-district 引用（同债务 D036 的钓鱼 mixin）。
- 不引用任何机械动力的类：没有编译期依赖（22.6 末尾），目标类名全是字符串。

#### mixin 配置

两份新配置，都用现有的 `miningdim.refmap.json`：

| 配置 | required | package | 内容 | 注入失败时 |
|---|---|---|---|---|
| `miningdim.district.mixins.json` | true | `com.miningdim.district.mixin.world` | 活塞、流体、发射器与投掷器、下落的方块、海绵（22.9） | 启动失败，与现有的六个核心 mixin 一样 |
| `miningdim.district.create.mixins.json` | false | `com.miningdim.district.mixin.create` | 19 个机械动力注入点（22.6） | 目标类、方法对不上：只记 WARN、停用那一个 mixin；注入点对不上：什么都不织（`defaultRequire = 0`，不抛 `InjectionError`）。两种都由开服核对报"机械动力防护不完整"（22.7） |
| `miningdim.district.flan.mixins.json` | false | `com.miningdim.district.mixin.flan` | 个人圈地限制的 F1、F2（22.20） | 同上，`defaultRequire = 0`；开服核对报"个人圈地限制不完整"。只在装了 Flan 时应用 |

- 两份都是 `minVersion 0.8.5`、`JAVA_17`、`plugin = com.miningdim.district.guard.DistrictMixinPlugin`，只用 `mixins` 列表：单人存档的集成服务端也要生效，不分 client / server；处理方法里自己判断服务端。
- `injectors.defaultRequire`：原版的配置是 1，机械动力的配置是 **0**（复核改，22.7、22.19）。Mixin 0.8.5 里 `require` 不满足时 `InjectionInfo.postInject` 抛 `InjectionError`，它是 `Error`，`MixinProcessor` 不看配置的 `required`，直接包成 `MixinTransformerError`：可选配置也会让整个服务端起不来。所以机械动力的 mixin 一律不写 `require`（C11 要两处的写成 `expect = 2`，平时 Mixin 不核对它），"织进去没有"由插件自己数（下面的 `postApply`）。
- **原版配置为什么 required：**
  - 目标都是原版类，只有升级 Minecraft 或 Forge 时才会变。那时启动就失败，比守卫悄悄失效好查。`build.gradle` 的规矩也是"原版目标进必需配置，别的 mod 的目标进可选配置"。
  - 只用 HEAD / RETURN / TAIL 的 `@Inject`，外加海绵的一个 `@ModifyArg` 与下落方块在 `move` 之后的一个 `@Inject`（复核加的，22.9）；不用 `@Redirect`、`@Overwrite`。风险与现有的核心 mixin 相同。
  - 整合包扫描的结果：
    - `FlowingFluid.canSpreadTo` 另有 Flan 的 HEAD 注入；`canPassThrough` 有机械动力的 HEAD 注入（本章不碰它）。
    - `PistonStructureResolver` 没有任何别人的 mixin，整合包里也没有类引用它或 `PistonEvent`；`PistonBaseBlock` 只有 Flan 的 `isPushable` HEAD 注入。
    - `DispenserBlock.dispenseFrom` 有农夫乐事 1.2.9 的 `CuttingBoardDispenserMixin`（INVOKE `getDispenseMethod` 处，`CAPTURE_FAILHARD`）；机械动力对 `getDispenseMethod` 有一个 `@Invoker`。
    - `FallingBlockEntity.tick` 有 Architectury 的 INVOKE `Fallable.onLand` 注入（`CAPTURE_FAILHARD`），Bakeries 注入 `causeFallDamage`，机械动力有一个访问器。
  - 可取消注入只在已有局部变量之后添一个 CallbackInfo，不影响它们的局部捕获（下落方块在 `move` 之后那一处同理，见 22.9）。农夫乐事在开发运行时里（GameTest 服务端起得来就算核对过），Architectury 只能在测试服核对（22.16）。
- **机械动力配置为什么可选：** 机械动力换个版本、改个方法名，注入失败只会停用那一个 mixin（或那一处不织），服务器照常起来；缺了哪个由 22.7 报出来。
- **插件 `DistrictMixinPlugin`（两份配置共用）：**
  - `shouldApplyMixin`：机械动力包里的 mixin，只在 `FMLLoader.getLoadingModList().getModFileById("create") != null` 时应用（这时 `ModList` 还没建好）；原版包一律应用。
  - `postApply`：数目标类里对这个 mixin 各处理方法的调用（合并后改名为 `handler$…$原名` / `redirect$…$原名`），每个至少 `expect` 次（没写为 1）才写 `applied`，否则写 `partial: 处理方法 实际/应有`，一并写进系统属性 `miningdim.district.mixin.<mixin 简名>.<目标简名>`（`MixinHandlerScan`，复核加的）。插件不一定和游戏代码在同一个类加载器里，走系统属性最稳。
  - 插件只碰 `FMLLoader`、ASM 与系统属性，不加载任何 Minecraft 类。
- **机械动力 mixin 的写法**（照 `TideOreFishMixin`）：
  - `@Pseudo @Mixin(targets = CreateHookTargets.XXX_CLASS, remap = false)`。机械动力自己的类名、方法名没有混淆，选择器一律 `remap = false`。
  - `@At(INVOKE)` 指向 Minecraft 方法时，必须显式写 `remap = true`：否则沿用类上的 `remap = false`，正式服（SRG 名）就找不到。
  - 参数里的机械动力类型一律 `@Coerce Object`；返回机械动力枚举的方法用 `CallbackInfoReturnable<Object>`，值从 `CreateReflection` 取。
  - 没有 MixinExtras（Forge 47 的 userdev 只带 mixin 0.8.5；机械动力内嵌的那份开发环境里没有），`@WrapOperation`、`@WrapWithCondition`、`@Local` 都不能用。
  - 注解里的类名、方法描述符、INVOKE 目标全部引用 `CreateHookTargets` 的 `static final String` 常量；22.14 的离线核对读同一张表，两边不会各写一份。
  - 处理方法名以 `miningdim$` 开头；客户端一律直接放行。

#### 接线

- `build.gradle`：`mixin {}` 块加 `config "${mod_id}.district.mixins.json"` 与 `config "${mod_id}.district.create.mixins.json"`；jar 清单的 `MixinConfigs` 追加这两个。**两处都要加**：开发运行时读 `mixin {}`，正式服的 FML 只读清单，只改一处就是开发能跑、正式服什么都没加载。22.20 的 `miningdim.district.flan.mixins.json` 同样两处都加了。
- `docs/modules/module-registry.json`：wok-district 的 `resourcePaths` 从 `[]` 改成这两个 json（22.20 起再加 Flan 那份）；`optionalIntegrations` 加 `create`；scope 补上守卫与通知（22.20 起再补个人圈地限制）。
- `mods.toml` 加一条可选依赖，写法照 Flan（20.1）：

  ```toml
  # 机械动力 (Create): 自管区的机械动力禁令 (wok-district, 22.1)。只经 @Pseudo mixin 与反射接触, 没有编译期依赖。
  # versionRange 刻意放开成 [0,): 可选依赖装着的版本一旦不在区间内, FML 会拒绝启动整个服务器;
  # 版本核对在代码里 (22.7: 只认 6.0.8, 不符记 WARN, 照常尝试注入)。
  [[dependencies.miningdim]]
  modId = "create"
  mandatory = false
  versionRange = "[0,)"
  ordering = "NONE"
  side = "BOTH"
  ```

  `ModDependencyDeclarationGameTests` 加一条对应的断言。
- 不加 `verifyModuleBoundaries` 的扫描规则：没有编译期依赖，本来就 import 不了机械动力的类。
- `DistrictSystem.register` 里 `forgeBus.register(GuardEvents.class)`，并装上通知的 gate（22.12）。

#### 配置

`miningdim-district.toml` 加两节，与 `enabled` 一样只在 ServerStarting 读一次，改了要重启：

```toml
[district]
	enabled = false

	[district.guards]
		# 地块边界守卫: 活塞、流体、发射器与投掷器、下落的方块、海绵、岩浆点火 (22.9)。
		crossPlot = true
		# 机械动力的机器改不了自管区里的方块 (22.6: 19 个 mixin 与机械动力假玩家的事件)。
		# 玩家的放置禁令 (22.5) 与 denyUse (22.8) 不受这一项影响。
		createMachinery = true
		# 个人圈地限制 (22.20): 自管区内与外围 8 格内不能新圈、扩进个人 Flan 领地 (OP 例外)。
		# false 只是急停: F1、F2 一律放行; /district personalclaims 与各处的报告照常。
		personalClaims = true

	[district.createBan]
		# 算作机械动力的命名空间。以后装了机械动力的附属 mod, 把它的命名空间加进来。
		# 默认 create, 加上整合包的机械动力登记机器方块用的 ignored_void (22.4 末尾)。已有的文件不会跟着改, 要手工加。
		namespaces = ["create", "ignored_void"]
		# 没有方块实体、但有机械行为的方块, 按机器拦。
		denyBlocks = ["create:mechanical_plough", "create:piston_extension_pole", "create:controller_rail", "create:redstone_contact"]
		# 有方块实体、但只是装饰的方块, 放行。
		allowBlocks = ["create:copycat_step", "create:copycat_panel", "create:andesite_door", "create:brass_door", "create:copper_door", "create:train_door", "create:framed_glass_door", "create:placard", "create:clipboard"]
		# 绑定前就在区内的机械动力机器: 非 OP 能不能右键操作 (22.8)。潜行 + 扳手拆下、徒手拆不受影响。
		denyUse = true
```

- 三个 `guards` 开关是给服主的急停：mixin 在别的 mod 旁边出了意外时，不用等新构建就能关掉。玩家的放置禁令没有开关：它是"谁都改不了"的固定规则（14.2 的 fixedRules），而且只是一个 Forge 事件。
- 名单里写错的项（格式不对、命名空间不在 `namespaces` 里、方块不存在）记 WARN 并忽略，`/district status` 列出来。
- **默认值只在文件里还没有这一项时写入。** 已经生成过的 `miningdim-district.toml` 保留原来的 `namespaces`（例如 2026-09-30 之前生成的只有 `"create"`），改默认值不会改到它：要手工改成 `["create", "ignored_void"]` 再重启。`/district status` 的守卫一节有一行"算作机械动力的命名空间"，开服日志的 `district guards installed` 一行也列出来，改完以这两处核对（23.11）。
- 功能 OFF（`enabled = false`）时不读这两节，守卫全部闲着（22.3）。
- 读出来的结果是不可变的 `GuardSettings`。GameTest 每轮清掉 serverconfig，用例直接构造 `GuardSettings`。
- `docs/modules/README.md` 的配置表里 `miningdim-district.toml` 一行补上这两节。

### 22.2 口径：区域号、外围 8 格与四种判定

**区与地块都是全高的 X/Z 方柱。** `DistrictBounds`、`PlotArea` 都没有 Y（父领地与地块在 Flan 里是 2D 全高，20.3），所以守卫只看 X、Z，任何高度都一样；竖直方向的移动（流体下落、方块下落）不会越过边界。

**区域号（zone）。** 对一个维度里的一列 (x, z)：
- 0：不在任何**在用**自管区里。野外、主城 DU、已解绑的自管区都是 0。
- 否则是 `(区序号 << 16) | 地块序号`；地块序号 0 表示公共区域。空置、有户主、冻结的地块各是一个区域。
- 序号只在一份快照里有效，绝不存进库，也不跨快照比较。

**外围 8 格（禁放区）。** 每个在用自管区按库里的范围，向 X、Z 四个方向各外扩 `DistrictLimits.BUFFER_BLOCKS`（8）格：
- 方形外扩（切比雪夫距离），四角不削圆。范围两端都含：区从 `minX` 起，则 `x = minX − 8` 在禁放区内，`minX − 9` 不在。
- 全高：从世界最低到最高建筑高度都算。
- 只在自管区所在的那个维度。别的维度的同一坐标不受影响。
- 禁放区包含自管区本身。两个区的外围可以重叠，也可以压住别的学院区、主城或个人领地，一律照拦。
- 已解绑的自管区没有禁放区：Flan 领地虽然还在，禁令跟着绑定走。
- 以库为准：用金锄头改了父领地、还没 `bounds sync` 时，守卫按库里的旧范围算（20.6）。

**四种判定。** `a` 是动作的来源，`b` 是动作落到的格子：

| 名称 | 什么时候拦 | 用在 |
|---|---|---|
| 严格 | `zone(a) ≠ zone(b)` | 活塞：推动的每一格的起点与终点、被挤碎的格子、活塞头要进的格子，都要与活塞所在的格子同区域 |
| 单向 | `zone(b) ≠ 0` 且 `zone(b) ≠ zone(a)` | 发射器与投掷器、下落的方块、海绵、岩浆点火：只护落点 |
| 区内边界 | a、b 在同一个在用自管区里，且 `zone(a) ≠ zone(b)` | 流体：只拦同一个区里的地块边界 |
| 受保护 | `zone(b) ≠ 0` | 机械动力的机器：只看落点，不看机器在哪（22.6） |

- 流体为什么用"区内边界"（P26）：自管区的外沿在 Flan 里有区域规则"水和岩浆越界"（目录的 `liquid_border`，默认关，只有 OP 能改）。我们再按严格口径拦，就等于悄悄盖过了这个开关。地块离区边至少 2 格（`EDGE_GAP`），地块边界一定在区内，所以"区内边界"正好就是全部地块边界。
- 活塞为什么用严格：父领地上的 `piston_border` 不在目录里，按 20.3 取 Flan 的默认值假，LIVE 时外沿本来就拦；严格口径在 DEGRADED 时照样拦，行为一致。
- 发射器等为什么用单向：只护落点；从区里往野外发射，不损害任何受保护的东西。

### 22.3 空间索引：按坐标查区与地块

现有代码里没有能用在热路径上的查法：
- `DistrictServices` 只是 `DistrictContext` 的 volatile 持有者；`DistrictContext` 每次调用都新建服务和 `AccessResolver`；仓储是不带缓存的 SQLite，每次查询都走库。
- `FlanGateway.districtClaimAt` 只有 `flan.real` 能碰，DEGRADED 时不工作；Flan 的 `getForPermissionCheck` 只索引顶层领地，地块要线性扫、也没有地块 id；而且 Flan 可能与库漂移。

所以另建一个从库生成的只读快照。库是真相，快照只是它的投影，绝不从 Flan 读。

#### 快照

`DistrictZoneSnapshot`（不可变，有 `EMPTY` 常量）：
- 按维度分组的数组 `DimZones[]`，按 `level.dimension()` 的身份查找。`ResourceKey` 是驻留的，构建时用 `ResourceKey.create(Registries.DIMENSION, new ResourceLocation(bounds.dimension()))`。
- 每个 `DimZones` 有：
  - 该维度全部在用自管区的外包框，以及外扩 8 格之后的外包框，用来快速排除；
  - `DistrictBox[]`（目前最多 6 个）：区 id、序号、X/Z 范围，以及 `Long2ObjectOpenHashMap<PlotBox[]> plotsByChunk`（fastutil，Minecraft 自带），键是 `ChunkPos.asLong(x >> 4, z >> 4)`，含全部地块。
- `PlotBox`：地块 id、序号、X/Z 范围、显示用的标签（`阿拜多斯-01`）。对象预先建好，查询返回它们不需要分配。

查询（参数全是 `Level` 与整数坐标；不分配、不碰库、不抛异常）：

| 方法 | 返回 |
|---|---|
| `zoneAt(level, x, z)` | 区域号（22.2） |
| `inBanArea(level, x, z)` | 在某个在用自管区内，或它的外围 8 格内 |
| `touchesDistrict(level, minX, minZ, maxX, maxZ)` | 这个框碰不碰任何在用自管区（机械动力装置部件的就近判定，22.6） |
| `hit(level, x, z)` | `@Nullable Hit`：区 id、地块 id、标签；给提示文字和 `/district` 命令用 |

一次查询的开销：
1. 一次 volatile 读（门面的 `GuardView`）；
2. 维度身份比较，最多几次；这个维度没有区就放行；
3. 外包框 4 次整数比较；框外（服务器上绝大多数的格子）就此返回；
4. 框内：最多约 6 次区框比较、一次哈希查找，再比那个区块里的几块地。

活塞一次动作最多查约 26 格，流体每次横向扩散查 2 格，装置部件每 tick 查一个框，都在纳秒级。

#### 重建与失效

`DistrictZoneIndex`（每个 `DistrictContext` 一个）：
- 持有 `private volatile DistrictZoneSnapshot current`。
- `rebuild()`：`repo.liveDistricts()`，再逐区 `plotsOf(id)`，建出新快照后整体替换。
- 遇到 `DistrictStoreException` 时保留旧快照，按 `LogThrottle` 每小时至多记一次 ERROR，并置"索引过期"标记给 `/district status` 看。

什么时候重建：
1. `DistrictContext.of(...)` 建索引时建一次。
2. 改几何的六个仓储写方法，每次执行都登记一次 `afterCommit(onLayoutChanged)`：`insertDistrict`、`setBounds`、`unbind`、`insertPlot`、`setPlotBounds`、`deletePlot`。
   - 登记在仓储里而不是服务里：测试夹具直接调仓储写库，也照样触发。
   - 每次写都登记，不用"只登记一次"的标记：事务回滚会清空提交后队列（`StoreTx`），标记就卡住了。同一事务里重复登记可以在仓储里去重。
   - 仓储接口加 `void onLayoutChanged(Runnable listener)`（只有一个监听者）。
3. 定时收回的节拍（每 1200 tick）顺带重建一次，前提是 `!repo.inOpenTransaction()`。这是兜底：以后万一有新的几何写入忘了登记，最多一分钟就对上。代价是每分钟不到 10 条查询。
4. 户主、冻结、朋友、开关的变化不改几何，不重建。

重建发生在服务器主线程、提交之后：平板动作经 `enqueueWork` 转到主线程，命令也在主线程。所以热路径永远看不到没提交的行，也永远不碰库。

#### 接线

- `DistrictContext` 加两个组件：`DistrictZoneIndex zones()`（`ctx.layout()` 已经是 `PlotLayoutService`，所以不叫 layout）与 `GuardSettings guards()`。只有 `of()` 调规范构造器；`of()` 顺带做第一次重建，并把监听者挂到仓储上。
- `DistrictWorldGuards`（静态门面）持有 `static volatile GuardView view`，初值 `GuardView.OFF`（什么都不拦）。
  - `DistrictSystem.onServerStarting` 绑定 context 之后调 `DistrictWorldGuards.install(ctx)`。此后该 context 的索引每重建一次，就发布一个新的 `GuardView`。
  - `onServerStopping` 调 `DistrictWorldGuards.reset()`，回到 OFF。
  - 功能 OFF 时从不 install。DEGRADED 与 LIVE 都 install：守卫只依赖库，不依赖 Flan。
- **GameTest 里默认不装。** `DistrictTestEnv` 建的 context 有自己的索引，但不 install。原因：1.20.1 的 GameTest 结构从 (0, −60, 0) 起排，夹具的默认区 abydos (0,0)–(199,199)、millennium (1000,0)–(1199,199) 正好压在它们上面；守卫要是跟着当前 context 走，别的用例里的水、活塞、发射器就会被拦，变成随机失败。需要守卫的用例走 `GuardTestZones`（22.14）。
- 每个 mixin 与事件处理方法的外层都包 try/catch：任何异常一律放行（fail open），按 `LogThrottle` 记 ERROR。守卫出 bug 不能让世界 tick 崩掉。

### 22.4 机械动力：什么方块算"机器"

决定（P23）：**放行没有方块实体、也不会转的装饰方块；拦下带方块实体或会转的方块；再用两张名单微调。**

规则（`CreateBlockPolicy.isMachine(Block)`，按顺序）：
1. 注册名的命名空间不在 `namespaces` 里（默认 `create` 与 `ignored_void`，后者见本节末尾）→ 不是机器；
2. 在 `allowBlocks` 里 → 不是机器；
3. 在 `denyBlocks` 里 → 是机器；
4. `block instanceof EntityBlock` → 是机器。机械动力所有带方块实体的方块都经 `IBE` 实现了它，轨道 `TrackBlock` 也是；
5. 是 `com.simibubi.create.content.kinetics.base.IRotate` 的实例 → 是机器（按类名 `Class.forName`，机械动力不在时跳过）；
6. 其余 → 装饰，放行。

结果按 `Block` 身份缓存在 `GuardView` 里（`IdentityHashMap`）；配置只在开服读，缓存不用失效。

为什么这样分（2026-09-30 对整合包 jar 的 `AllBlocks` 普查，185 个方块）：
- **带方块实体的 134 个**：传动、动力源、加工、流体、装置、物流、火车、红石类，全是"机器"。
- **没有方块实体、但有机械行为的 4 个**，进 `denyBlocks`：`mechanical_plough`（装置上的犁，会拆方块、耕地）、`piston_extension_pole`（活塞杆）、`controller_rail`（控制铁轨）、`redstone_contact`（装置用的红石触点）。锁存器、火车的 `controls` 无害，放行。绳子、磁铁、活塞头、水车结构、矿车锚没有物品，放不出来。
- **没有方块实体、纯装饰的**：各种外壳、大梁、支架、梯子、脚手架、活板门、帆、座椅、石材与玻璃调色板、金属块与玫瑰石英块、纸板、经验块、锌矿。放行——这就是上赛季允许的"装饰方块"。
- **带方块实体、但只是装饰的 9 个**，进 `allowBlocks`：`copycat_step`、`copycat_panel`、五种推拉门（`andesite_door`、`brass_door`、`copper_door`、`train_door`、`framed_glass_door`）、`placard`、`clipboard`。
- 候选、但默认不放：`nixie_tube`、三种铃（`peculiar_bell`、`haunted_bell`、`desk_bell`）、三种桌布（`*_table_cloth`）、染色的工具箱（`*_toolbox`）。服主要放就加进 `allowBlocks`。
- 分类只用 `ForgeRegistries.BLOCKS.getKey`、`instanceof EntityBlock` 与一次按类名的 `Class.forName`，没装机械动力也能跑。GameTest 用原版方块加一份测试设置来核对（22.14）。

**整合包里的实际注册表与上面的普查不同（2026-09-30 冒烟测试，23.11）。** 上面的普查读的是 jar 里 `AllBlocks` 的字节码，按 `create:` 统计；开服日志（`debug.log` 里 Registrate 的 `Registered … to registry` 行）显示这个重打包实际是这样登记的：
- `create` 命名空间：397 个方块、410 个物品，基本是装饰：石材调色板、窗与玻璃、座椅、梯子、栏杆、脚手架、大梁、推拉门、仿制方块（`copycat_*`）、金属块等。机器只剩大梁包着的传动杆（`metal_girder_encased_shaft`），它要拿传动杆去做。
- `ignored_void` 命名空间：246 个方块、196 个物品，包括其余全部机器（传动、动力源、加工、流体、装置、物流、火车），以及一部分装饰（例如外壳、数码管、工具箱、告示板、剪贴板）。
- `ignored_items` 命名空间：93 个物品（例如扳手）。
- 生存模式的玩家拿不到机器方块。

服主 2026-09-30 定：`namespaces` 的默认值加上 `ignored_void`，`create` 保留。这样 `ignored_void` 下的机器方块在分类里照样算"机器"，非 OP 放不进区内与外围 8 格，OP 例外不变；`ignored_void` 下没有方块实体、也不会转的照样算装饰。`ignored_void` 不是 mod id，名单里写在它下面的方块不核对存在与否（`GuardSettings.parse` 的"mod 没装时不核对"）。GameTest 用合成的注册名核对（22.14 的 `ignoredVoidBlocksAreMachinesByDefault`）。

两张名单仍只按 `create:` 写。默认名单里有 6 项在这个整合包里登记在 `ignored_void` 下：`denyBlocks` 的 4 项全部，以及 `allowBlocks` 的 `placard`、`clipboard`。所以整合包上 `/district status` 会列出 6 条"方块 create:… 不存在"的名单问题。这是预期的提示，其余各项照常生效。要不要在名单里补 `ignored_void` 下的同名项，见 P44。

### 22.5 放置禁令

#### 认人（`GuardActors`）

- **OP 例外 = `hasPermissions(2) && !(player instanceof FakePlayer)`。**
  - 机械手的假玩家 `DeployerFakePlayer` 继承 `net.minecraftforge.common.util.FakePlayer`。它的档案名义上是 `9e2faded-cafe-4ec2-c314-dad129ae971d` / `"Deployer"`，但 `getUUID()` 与 `GameProfile.getId()` 返回的都是**主人的 UUID**，名字是主人最后一次的用户名。
  - `ServerPlayer.getPermissionLevel()` 经 `MinecraftServer.getProfilePermissions(profile)` 按 UUID 查 OP 名单，所以 **OP 放的机械手本身就能通过 `hasPermissions(2)`**。必须按类排除假玩家，不能按 UUID。
  - 1 级 OP 不例外（P14）。
- **机械动力的假玩家** = `player instanceof FakePlayer` 且类名以 `com.simibubi.create.` 开头：`DeployerFakePlayer`，以及犁的 `PloughBlock$PloughFakePlayer`（档案 `9e2faded-eeee-4ec2-c314-dad129ae971d` / `"Plough"`）。只按类名认，绝不按 UUID。
- **别的 mod 的假玩家不归本章管**，交给 Flan 在父领地与地块上固定为假的 `flan:fake_player`（20.3）。注意 Flan 的 `Claim.canInteract` 把类不是恰好 `ServerPlayer` 的都当假玩家，但 UUID 是领地主人或组成员的照样有本人的权限：住户自己的别家机器在自家地块里能动。这是 Flan 的口径，本阶段不改（P31）。

#### 拦在哪里

`BlockEvent.EntityPlaceEvent`（`EntityMultiPlaceEvent` 是它的子类，一起拦）。`GuardEvents` 以 HIGH 优先级监听、不接收已取消的事件，只在服务端判定：

| 放的人 | 放的东西 | 自管区内 | 外围 8 格内（区外） | 更远 |
|---|---|---|---|---|
| 真玩家、非 OP | 机器 | 拦 | 拦 | 放行 |
| 真玩家、非 OP | 装饰 | 放行（再交给 Flan 的"放置方块"） | 放行 | 放行 |
| OP（2 级、不是假玩家） | 任何 | 放行 | 放行 | 放行 |
| 机械动力的假玩家 | 任何 | 拦（22.6） | 机器拦、装饰放行 | 放行 |
| 别的假玩家 | 任何 | 交给 Flan | 放行 | 放行 |

- 多方块放置：主位置或 `getReplacedBlockSnapshots()` 里任何一格落在范围内、且放的是机器，就整次拦下。
- 拦下时 Forge 恢复方块快照、把物品数量还回去（`ForgeHooks.onPlaceItemIntoWorld`）。给玩家发一条动作栏提示（`displayClientMessage(…, true)`），同一名玩家 1 秒内至多一条：
  - `district.miningdim.guard.create_place_denied`："自管区里不能放机械动力的机器（装饰方块可以）"
  - `district.miningdim.guard.create_place_denied_buffer`："自管区外围 8 格内不能放机械动力的机器（装饰方块可以）"
- 覆盖的放置路径（对整合包 jar 核对过）：
  - 普通的右键放置（`ItemStack.useOn` → `onPlaceItemIntoWorld`）；
  - 机械动力的放置助手（连着放传动杆、齿轮、管道）：走 Ponder 内嵌的 catnip `PlacementOffset.placeInWorld` → `ModHooksHelper.playerPlaceSingleBlock`，它的 Forge 实现 `ForgeHooksHelper` 用 `BlockSnapshot` 发 `EntityPlaceEvent`，取消时恢复；
  - 对称之杖：逐格发 `onBlockPlace`。

#### 不发事件的玩家路径

- **轨道：** `TrackPlacement.placeTracks` / `paveTracks` 直接 `setBlock`，一次最多 32 格（`maxTrackPlacementLength`），站在外面就能把弯道铺进区里。用 22.6 的注入点 C14 拦，OP 例外（持轨道的是真玩家）。
- **传送带：** `BeltConnectorItem.createBelts` 沿途逐格 `destroyBlock` 挡路的方块，再直接放传送带，不发任何事件。复核改（22.19）：原来以为"两端都要有已经在那里的传动杆"就够了，其实蓝图炮打印传送带（`LaunchedItem$ForBelt.place`）也调它，起点在外围之外、长度来自蓝图里的数据，照样能伸进区里。用 22.6 的注入点 C15 拦：玩家连接时这一条碰到禁放区就拒（OP 例外，不扣物品），`createBelts` 本身按整条的框再拦一次（蓝图炮）。整合包的 jar 里调 `createBelts` 的只有这两处；链式传送带不经它，阶段 4 看一眼（23.6 第 9 条）。
- **蓝图的创造模式直接打印**（`SchematicPlacePacket`）：服务端先查 `isCreative()`，实际只有 OP 用得上，不拦。

### 22.6 机器：改不了自管区里的方块

决定（P25）：机器不管在哪（区外、外围、区内，也不管是谁放的），**都改不了在用自管区里的任何方块**（"受保护"口径，22.2）。OP 的例外只到"亲手放置"为止；方块自己动的机关不开例外。

`createMachinery = false` 时，本节的全部拦截（下面的事件与 19 个 mixin，以及 22.5 表里机械动力假玩家那一行）都放行；22.5 里真玩家的放置禁令与 22.8 的 `denyUse` 照旧。

#### ① Forge 事件（不依赖机械动力）

机械动力里只有机械手和犁经过 Forge，因为它们是假玩家。`GuardEvents` 对**机械动力的假玩家**，目标在自管区里的一律取消：

| 事件 | 目标位置 | 覆盖 |
|---|---|---|
| `BlockEvent.BreakEvent` | `getPos()` | 机械手挖方块（`DeployerHandler.tryHarvestBlock` → `onBlockBreakEvent`） |
| `EntityPlaceEvent`（含多方块） | 各格 | 机械手放方块；装置上的蓝图打印（`placeSchematicBlock` 之后 `onBlockPlace`） |
| `BlockEvent.BlockToolModificationEvent` | `getPos()` | 犁耕地（`PloughFakePlayer` 调钻石锄的 `useOn`）、机械手用斧去皮、用锹压路 |
| `PlayerInteractEvent.RightClickBlock` | `getPos()` | 取消，并显式把 `useBlock`、`useItem` 都置 DENY：机械动力读的是这两个值（Forge 47.3.0 起取消会把两者都置 DENY，这里再写一次不依赖这个细节） |
| `PlayerInteractEvent.LeftClickBlock` | `getPos()` | 机械动力检查 `isCanceled` |
| `FillBucketEvent` | `getTarget()` 命中的格子，以及它朝向的那一格 | 机械手装桶、倒桶 |
| `PlayerInteractEvent.EntityInteract`、`AttackEntityEvent` | 目标实体的 `blockPosition()` | 机械手对实体用物品、攻击 |

外围 8 格里的机械手只按 22.5 的表拦"放机器"。

#### ② mixin（`miningdim.district.create.mixins.json`，19 个）

其余路径都不发 Forge 事件。拆方块最终都走 `BlockHelper.destroyBlockAs`，它只在传入的玩家非空时才发 `BreakEvent`，而每个机器调用方传的都是 null。

下表的目标类都省略了前缀 `com.simibubi.create.`：

| # | 目标 | 方法 | 写法 | 效果 | 覆盖 |
|---|---|---|---|---|---|
| C1 | `foundation.utility.BlockHelper` | `destroyBlockAs(Level, BlockPos, Player, ItemStack, float, Consumer)V`（static） | HEAD 取消：玩家为 null 或是假玩家，且位置受保护 | 机器拆方块的总闸 | 固定与装置上的钻头、锯（含伐树逐根）、压路机的拆，收割机的拆 |
| C2 | `content.schematics.cannon.SchematicannonBlockEntity` | `shouldPlace(BlockPos, BlockState, BlockEntity, BlockState, BlockState, boolean)Z` | HEAD 返回 false：目标受保护，或目标在外围 8 格内且要放的是机器 | 这一格跳过，不耗火药和材料 | 蓝图炮（锚点在 256 格内） |
| C3 | `content.kinetics.base.BlockBreakingKineticBlockEntity` | `canBreak(BlockState, float)Z` | HEAD 返回 false：`@Shadow(remap = false)` 的 `breakingPos` 受保护 | 固定的钻头、锯不开始拆，也没有无尽的裂纹动画 | 钻头（没覆写）、锯（调 super） |
| C4 | `content.kinetics.base.BlockBreakingMovementBehaviour` | `canBreak(Level, BlockPos, BlockState)Z` | HEAD 返回 false：位置受保护 | 装置把它当墙，平移装置的碰撞检查随之停下 | 装置上的钻头、锯、压路机（它们调 super）；犁覆写了它且不调 super，由 C1、C5 管 |
| C5 | `content.contraptions.AbstractContraptionEntity` | `isActorActive(MovementContext, MovementBehaviour)Z` | HEAD 返回 false：服务端，且 `ctx.position` 外扩 2 格的框碰到任何在用自管区 | 这个部件这一 tick 的 `visitNewPosition` 与 `tick` 都跳过（只有推拉门、流体罐在停用时照样 tick） | 通用网：收割机、犁、机械手、装置上的发射器（目标 = 所在格 + 朝向）、钻头与锯伤实体、火车上的一切部件（车厢实体调 super） |
| C6 | `content.contraptions.actors.harvester.HarvesterMovementBehaviour` | `visitNewPosition(MovementContext, BlockPos)V` | HEAD 取消：`pos` 受保护 | — | 收割机。**必须有**：只有 C1 时它照样把作物重置为幼苗或设成空气，而且不掉落 |
| C7 | `content.contraptions.actors.roller.RollerMovementBehaviour` | `tryFill(MovementContext, BlockPos, BlockState)`，返回 `$PaveResult` | HEAD 返回 `PaveResult.FAIL`（包内可见的枚举，经 `CreateReflection` 取） | 不铺路 | 压路机向下最多 12 格（`rollerFillDepth`）、横向约 6 格的铺路，在火车上沿轨道 |
| C8 | `content.contraptions.ContraptionCollider` | `isCollidingWithWorld(Level, TranslatingContraption, BlockPos, Direction)Z`（static） | `@Redirect` 其中的 `Level.isLoaded(BlockPos)`（`m_46749_`，`remap = true`）：受保护的格子报"没加载" | 没加载的区块本来就算碰撞，平移装置停在边界外 | 机械活塞、绳索滑轮、电梯滑轮、龙门 |
| C9 | `content.contraptions.Contraption` | `addBlocksToWorld(Level, StructureTransform)V` | `@Redirect` 其中的 `customBlockPlacement(LevelAccessor, BlockPos, BlockState)Z`（`remap = false`）：落点受保护，或落点在外围 8 格内且是机器 → 播放 2001 事件、除非 `noDropWhenContraptionReplaceBlocks` 否则掉落、返回 true | 照抄机械动力自己"被挡住"那一支：装置的方块掉成物品，不砸原地的方块。否则它会先 `level.destroyBlock` 原地方块再放上去。装置里这几格的容器内容随之丢失，与机械动力原本的行为相同 | 拆装：轴承、火车、矿车这类没有地形碰撞的装置转进、开进区里之后 |
| C10 | `impl.contraption.BlockMovementChecksImpl` | `isMovementAllowed(BlockState, Level, BlockPos)Z`（static） | HEAD 返回 false：服务端且位置受保护 | 边缘方块不可移动 → 整个装置组装失败（`AssemblyException.unmovableBlock`，机械动力自己在方块上显示原因）；侧面的邻居只是不粘上 | 一切组装：活塞、轴承、滑轮、龙门、矿车组装器，底盘与强力胶够到区内方块时；活塞杆也走这一查 |
| C11 | `content.fluids.transfer.FluidManipulationBehaviour` | `search(Fluid, List, Set, BiConsumer, boolean)Fluid` | `@Redirect` 两处 `Level.getFluidState(BlockPos)`（`m_6425_`，`remap = true`，`expect = 2`：复核前写的是 `require = 2`，见 22.7）：受保护的格子返回空流体 | 洪水搜索绕开自管区 | 软管滑轮抽液（范围 `hosePulleyRange` = 128） |
| C12 | `content.fluids.transfer.FluidFillingBehaviour` | `getAtPos(Level, BlockPos, Fluid)`，返回 `$SpaceType` | HEAD 返回 `SpaceType.BLOCKING`（经反射） | — | 软管滑轮灌液 |
| C13 | `content.fluids.OpenEndedPipe` | `provideFluidToSpace(FluidStack, boolean)Z`、`removeFluidFromSpace(boolean)FluidStack`（都是私有） | HEAD：`getOutputPos()` 受保护时分别返回 false、空 | — | 开口管道放液、吸液 |
| C14 | `content.trains.track.TrackPlacement` | `tryConnect(Level, Player, BlockPos, BlockState, ItemStack, boolean, boolean)`，返回 `$PlacementInfo`；另挂私有静态的 `placeTracks(Level, PlacementInfo, BlockState, BlockState, BlockPos, BlockPos, boolean)` | 三处注入（实现时更正，见 22.17）：`tryConnect` 的 HEAD 清掉线程局部；`placeTracks` 的 HEAD 在 `simulate = true` 那一次把 `PlacementInfo` 记进线程局部；`tryConnect` 里唯一一次 INVOKE `Player.isCreative()`（`m_7500_`，`remap = true`，在算完两端、延伸段与曲线之后、开始扣物品与真正铺轨之前）处可取消地判定：服务端、玩家不是 OP 例外、要铺的范围碰到禁放区 → 把那个 `PlacementInfo` 的 `valid` 置假并以它为返回值提前结束，发 22.5 的动作栏提示 | 服务端不扣物品、不铺；`TrackBlockItem` 见 `valid` 为假回 FAIL | 轨道的弯道与长直道 |
| C15 | `content.kinetics.belt.item.BeltConnectorItem` | `useOn(UseOnContext)`（覆写 Minecraft 的方法，jar 里是 SRG 名 `m_6225_`，选择器直接写它、`remap = false`）；`createBelts(Level, BlockPos, BlockPos)V`（static） | 复核加的（22.19）。`useOn` 的 HEAD 清线程局部；`useOn` 里唯一一次 INVOKE `canConnect`（`remap = false`，在扣物品、铺传送带之前）处判定玩家这一条（物品 NBT `FirstPulley` 到点中的传动杆）：非 OP、框碰到禁放区 → 回 FAIL 并发 22.5 的动作栏提示；OP 放行并记下这一条。`createBelts` 的 HEAD：整条的框碰到禁放区就取消（OP 刚放行的同一条除外，只认一次） | 不拆、不铺；玩家不扣物品，物品上记着的第一根传动杆不动 | 玩家连接传送带；蓝图炮打印的传送带（`LaunchedItem$ForBelt.place` 先在起点放传动杆再调 `createBelts`，长度来自蓝图，起点在外围之外时 C2 看不到后面的格子。蓝图炮在发射之前已经扣了材料，这种情况下材料照扣、起点那根传动杆照放，只是不铺，可以接受） |
| C16 | `content.equipment.symmetryWand.SymmetryWandItem` | `remove(Level, ItemStack, Player, BlockPos)V`、`apply(Level, ItemStack, Player, BlockPos, BlockState)V`（都是 static） | 复核加的。`@Redirect` 两个方法里各唯一一次 `Map.keySet()`（镜像出来的全部位置，`remap = false`），处理方法再收目标方法的参数：去掉落到别的区域的位置（单向口径，以玩家这一下所在的格子为来源），OP 例外 | 这些位置既不拆（也不掉落）、也不放。不重定向 `setBlock`：那样掉落照样发生，等于复制物品 | 对称之杖：`remove` 在 `BreakEvent`（LOWEST）之后逐个 `setBlock` 成空气并在玩家那一格掉落，不发 `BreakEvent`；`apply` 的创造模式那一支直接 `setBlock` |
| C17 | `content.kinetics.mechanicalArm.ArmInteractionPoint` | `deserialize(CompoundTag, Level, BlockPos)`，返回 `ArmInteractionPoint`（static） | 复核加的。HEAD：交互点 = `anchor + tag.Pos`（与方法本身同口径）落到别的区域（单向口径）→ 返回 null（方法本来就会对不认识的类型返回 null，机械臂跳过它） | 那个交互点不存在；远处的点也不会因此同步加载区块 | 机械臂。服务端不查交互点的距离与归属：`mechanicalArmRange` 只在客户端查，`ArmPlacementPacket` 对任何已加载的机械臂照单全收，蓝图炮打印的机械臂带着交互点；存档读出来的也走这里 |
| C18 | `content.redstone.displayLink.DisplayLinkBlockEntity` | `updateGatheredData()V` | 复核加的。HEAD：目标（`getTargetPosition()`）或来源（`getSourcePosition()`，两个都是 `@Shadow` 的公开方法）落到别的区域（单向口径）→ 取消 | 这一次不读不写 | 显示链接：`tickSource`、失去红石信号、改配置的网络包都走它，再 `transferData` 写告示牌、讲台、显示板；目标偏移可以来自蓝图，服务端不查距离 |
| C19 | `api.equipment.potatoCannon.PotatoCannonProjectileType` | `onBlockHit(LevelAccessor, ItemStack, BlockHitResult)Z`（record 的方法） | 复核加的。HEAD：被打中的方块或它被打中那一面的邻格受保护 → 返回 false | 不放方块、不种作物；射弹照原版掉落或回收物品 | 土豆加农炮（手持的，或机械手拿着的）：`create:place_block_on_ground`（南瓜、西瓜）直接 `setBlock` 或生成下落的方块，`create:plant_crop`（土豆、胡萝卜等）直接 `setBlock`。挂在类型上，附属 mod 加的动作一并覆盖 |

补充：
- **C14 为什么不在 RETURN**（实现时对 jar 核对出来的更正）：`tryConnect` 在服务端自己就扣物品（非创造模式）并调 `paveTracks(…, false)` / `placeTracks(…, false)` 铺轨，最后才返回；RETURN 时轨道已经铺好了。`isCreative()` 在整个方法里只出现一次，恰好在扣物品之前。`PlacementInfo` 是局部变量，没有 MixinExtras 拿不到，所以借 `placeTracks(simulate = true)` 的参数交出来。
- **C14 的范围**：`pos1`、`pos2`、两端的直线延伸段（`end1Extent`、`end2Extent` 分别沿 `axis1`、`axis2`，有曲线时多一格，与 `placeTracks` 同口径），再加上曲线 `BezierConnection.getBounds()`（公开方法）。`PlacementInfo` 的这些字段包内可见，经 `CreateReflection` 读写：机械动力在 FML 里是自动模块，包全部开放，`setAccessible` 可用。客户端的预览同样调 `tryConnect`，我们在客户端不改，所以预览仍是绿的；`message` 不动（它会被当成 `create.` 下的翻译键显示），提示由我们自己发。实际提示效果阶段 4 核对。
- **C5 的位置**：`MovementContext.position` 是公开的 `Vec3` 字段，经缓存的反射字段读（`CreateReflection`），第一次调用时解析。取不到（为 null，或字段变了）时退回用装置实体自己的包围框外扩 2 格：宁可整台装置停下，记一次 WARN。
- **层次**：C1 是拆方块的总闸，C5 是装置部件的总网，C8–C10 管装置本身；C3、C4、C6、C7 在总闸、总网之外各自再挡一次，任何一个注入失败还有另一层（22.7）。
- **只看落点**：区内已有的、OP 放的机器同样受这些拦截（P25）。
- 不挂 `BlockHelper.placeSchematicBlock`：它的三个调用方里，蓝图炮由 C2 挡，装置上的打印由 C5 与机械手的事件挡，剩下的创造模式直接打印只有 OP 用得上（22.5）；挂上反而会拦住 OP。

#### 不拦、记为遗留的

| 路径 | 为什么不拦 |
|---|---|
| 对称之杖在自管区之外的镜像拆（别人的 Flan 领地） | C16 只管落到自管区别的区域的位置；自管区之外要拦得逐格补发 `BreakEvent`（本模块的挖掘统计等监听者也会跟着算），不在本章 |
| 机械臂在自管区之外越过作用半径（5 格） | C17 只按区域拦；服务端不查距离是机械动力自己的问题，只在自管区之外起作用 |
| 链式传送带 | 不经 `createBelts`（整合包的 jar 里只有玩家连接与蓝图炮两处调它）；阶段 4 看一眼（23.6 第 9 条） |
| 土豆加农炮打实体的动作（着火、药水等） | 不改方块；打实体交给 Flan 与原版规则 |
| 机械动力的附属 mod | 目前整合包只有 `some-assembly-required`（只加配方）；以后装了新附属，分类靠 `namespaces`，机器行为要另核 |

复核（22.19）时去掉的遗留：传送带（改由 C15 拦：蓝图炮打印的传送带起点可以在外围之外）、机械臂（C17：服务端不查距离与归属）、显示链接（C18：目标可以来自蓝图，不必右键目标方块）、土豆加农炮（C19：直接放方块、种作物，不经 Flan 的放置判定）。

#### 别的 mod 里同样不发事件、直接改方块的东西（复核补查，P38）

复核时查了整合包里别的会沿路直接改方块、不发玩家事件的物品。本阶段不写 mixin；具体清单与处理办法不写进公开文档，由服主在**打开 `enabled` 之前**在整合包里处理（删掉配方或只给 OP），阶段 4 核对（22.16 第 17 条、23.10）。要保留给玩家用的，再另做可选 mixin，让它们跳过受保护的位置。

#### 为什么不加编译期依赖（P30）

这些处理方法只用到 Minecraft、Forge 与 Java 的类型，机械动力的类型一律 `@Coerce Object` 或反射，所以不需要机械动力的构件。要编译期类型安全的话：`maven { url 'https://maven.createmod.net' }`，加 `compileOnly fg.deobf("com.simibubi.create:create-1.20.1:6.0.8-291:slim") { transitive = false }`。6.0.8 在那个仓库里有 288–291 四个构建，291 最新，没有一个与整合包的重编译 jar 字节相同；约 17 MB，要下载、要服主批准。本阶段不加。

### 22.7 注入失败时

机械动力的配置是可选的：目标类、方法、影子成员对不上时只记 WARN、停用那一个 mixin；注入点（INVOKE、`@Redirect`）对不上时什么都不织。为了不让它悄悄失效：

0. **不让它崩服**（复核改，22.19）。Mixin 0.8.5 对可选配置并不总是宽容：`require` 不满足时 `InjectionInfo.postInject` 抛 `InjectionError`（`MixinError extends Error`，不是 `InvalidMixinException`），`MixinTargetContext.applyInjections` 与 `MixinApplicatorStandard.apply` 只接 `InvalidMixinException` 与 `Exception`，`MixinProcessor.applyMixins` 把别的 `Throwable` 一律包成 `MixinTransformerError`，根本不看配置的 `required`（对 `mixin-0.8.5.jar` 的异常表核对过）。所以机械动力版本一变、或者别的附属先重定向了同一个调用，C8、C9、C11、C14 这类按 INVOKE 挂的注入点对不上时，原来的 `defaultRequire = 1` 会让服务端起不来（功能关着也一样：`shouldApplyMixin` 只看机械动力在不在，开服核对还会强制加载目标类）。改法：机械动力的配置 `defaultRequire = 0`、注解里不写 `require`（C11 的两处写成 `expect = 2`，Mixin 平时不核对它），"织进去没有"由插件自己数。原版的配置不变（`required = true`、`defaultRequire = 1`：原版目标只有升级 Minecraft、Forge 时才会变，那时启动就失败更好查）。
1. **记录。** `DistrictMixinPlugin.postApply` 数目标类里对各处理方法的调用，全都够数写 `applied`，否则写 `partial: …`（22.1）。只看"postApply 有没有被调用"不够：注入点对不上时它照样被调用。
2. **核对（ServerStarted）。** `GuardMixinStatus.check()`：
   - 先按目标表逐个 `Class.forName(目标, false, 本模块的类加载器)`：只加载、不初始化。目的是让还没被用到的目标类（例如 `ContraptionCollider`、`SchematicannonBlockEntity` 要到第一次有装置、第一次放蓝图炮才加载）现在就经过变换，mixin 才有机会应用。
   - 再数系统属性，只认值为 `applied` 的（处理方法全都织进去了，22.1 的 `postApply`；值为 `partial: …` 的按缺报，并把原值写进日志）。原版应有 6 条（5 个 mixin，发射器那个有两个目标）；装了机械动力时应有 19 条（复核前 14 条）。
   - 机械动力的版本（经 `ModList` 读）不是 `6.0.8` 时记 WARN"机械动力 {版本} 未经核对"，照常核对注入。
3. **报告。** 不完整时：
   - 开服记 ERROR，列出缺的 mixin 与各自覆盖什么（下表）；
   - `/district status` 显示"机械动力防护不完整：缺 C?、C?"。完整时显示"机械动力防护 19/19，版本 6.0.8"，没装时显示"未安装机械动力"；
   - OP 上线时收到一条红字 `district.miningdim.guard.create_incomplete_op`，随上线补发一起走 22.12 的 gate，不入队。
4. 功能 OFF 时照样核对并记 INFO（mixin 与功能开关无关），不记 ERROR、不提醒 OP。
5. **个人圈地限制（22.20）同一套。** 装了 Flan 时再按 `FlanHookTargets.HOOKS` 加载 `ClaimStorage`、数 F1、F2 的系统属性；版本不是 `1.20.1-1.11.16` 时记 WARN；不完整且功能开着记 ERROR；`status` 显示"个人圈地限制 2/2（Flan …）"、"个人圈地限制不完整：缺 F?"或"未安装 Flan"；OP 上线的红字 `district.miningdim.guard.claim_incomplete_op` 与 `create_incomplete_op` 同一个回调、同一个 gate。

各 mixin 失效时还剩什么：

| 失效的 | 仍然挡得住 | 挡不住 |
|---|---|---|
| 全部机械动力 mixin（例如机械动力改版） | 玩家的放置禁令；机械手与犁（事件）；LIVE 时 Flan 的 `create_contraption` 拦矿车装置进领地 | 外面的钻头装置、蓝图炮、软管滑轮、装置组装与拆装、轨道弯道。这时要当事故处理：OP 暂时禁止在自管区附近用机械动力，尽快出适配的构建 |
| C1 | C3（固定的钻头、锯）、C4（装置上的钻头、锯）、C5（部件总网） | 锯伐树时越界的原木（伐树逐根走 C1） |
| C3 或 C4 | C1（照样拆不了，只是一直显示拆的动画）；C8 照样让平移装置停在边界外 | — |
| C5 | C1（拆）、C6（收割机）、C7（压路机）、机械手的事件 | 装置上的发射器（没有 Forge 事件）、钻头与锯对区内实体的伤害 |
| C6 或 C7 | C5 | — |
| C8 | C4（带钻头、锯的装置把区内方块当墙）；拆装时的落点仍由 C9 挡 | 没带工具的活塞、滑轮、龙门平移进区内 |
| C9 | C10（带着区内方块组装不起来）、C8 | 轴承转进区内后拆装，砸掉区内方块 |
| C10 | C8、C9 | 底盘、强力胶把区内方块粘走 |
| C11、C12、C13 | — | 对应的流体路径 |
| C2 | — | 蓝图炮 |
| C14 | — | 轨道弯道铺进禁放区 |
| C15 | 玩家的放置禁令（传送带方块算机器，但玩家连接传送带不经放置事件，所以挡不住） | 传送带连进禁放区；蓝图炮打印的传送带沿途拆掉区内方块 |
| C16 | 对称之杖生存模式的镜像放置照样经放置事件（Flan、放置禁令） | 镜像拆别的区域的方块（不发 `BreakEvent`）；创造模式的镜像放置 |
| C17、C18 | — | 机械臂从别的区域取放物品；显示链接读写别的区域的字 |
| C19 | — | 土豆加农炮往区里放方块、种作物 |

### 22.8 绑定前就在区内的机械动力方块

决定（P24）：

1. **不拆。** 守卫全部按落点判：区内已有的钻头、机械手、管道、活塞既拆不了、放不了区内的方块，也不能带着区内方块组装（22.6）。
2. **不停转。** 传动、传送带、鼓风机、粉碎轮照常运转。要停转只能去改转速的传播，容易让客户端与服务端不同步，得不偿失；它们改不了区内方块，照常运转的只是加工。
3. **非 OP 不能右键操作**（`denyUse = true`）：真玩家、非 OP，在自管区内右键一个"机器"（22.4 的分类）→ 取消 `RightClickBlock`（`useBlock`、`useItem` 都 DENY），动作栏提示 `district.miningdim.guard.create_use_denied`"自管区里的机械动力机器只能拆除，不能操作"。
   - 例外：潜行 + 手持机械动力扳手（`create:wrench`）放行。这是拆下机器的正路：`onSneakWrenched` 拆下时发 `BreakEvent`，照样受 Flan 的"破坏方块"约束。
   - 徒手拆走左键与 `BreakEvent`，不受 `denyUse` 影响。
   - 不潜行的扳手右键（转向、调整）算操作，拦。
   - 机械动力的"数值框"（调转速、过滤槽）在 6.0 里有一部分走自己的网络包，不一定经过 `RightClickBlock`；阶段 4 核对（22.16）。核对之前，以"拦得住右键"为准。
   - 只在自管区内，不含外围 8 格：外围不是受保护的地。
4. **列给 OP。** 新命令 `/district machines <districtId>`（OP）：
   - 扫描该区及外围 8 格内**已加载**区块的方块实体，列出命名空间在 `namespaces` 里的，按方块 id 计数，每种给前 10 个坐标，并报告有多少区块没加载、没扫到。
   - 没有方块实体的拒绝名单方块（犁、活塞杆、控制铁轨、红石触点）不扫：逐格扫方块状态太贵。
   - `/district create`、`/district bind … confirm`、`/district bounds … sync` 成功之后，回显末尾追加一行："区内及外围 8 格的已加载区块里有 N 个机械动力方块，用 /district machines <id> 查看"（N 为 0 时不显示）。
   - 拆不拆由 OP 决定。OP 拆除不受任何限制。

### 22.9 地块边界守卫

`crossPlot = true` 时生效。除岩浆点火走 Forge 事件外，全部在 `miningdim.district.mixins.json`（required）里。OP 不例外：这些都是方块自己在动，与谁放的无关。

#### 活塞：`PistonStructureResolver.resolve()`

- 目标 `resolve()Z`（SRG `m_60422_`），`@Inject(at = @At("RETURN"), cancellable = true)`。影子字段都是 `private final`，用 `@Shadow @Final`：

  | 字段 | SRG |
  |---|---|
  | `level` | `f_60409_` |
  | `pistonPos` | `f_60410_` |
  | `extending` | `f_60411_` |
  | `pushDirection` | `f_60413_` |
  | `toPush` | `f_60414_` |
  | `toDestroy` | `f_60415_` |
  | `pistonDirection` | `f_60416_` |

- 规则：返回值为真、且在服务端时，令 Z = `zone(pistonPos)`。以下任一成立，就把返回值改成 false：
  - 伸出时，活塞头要进的格子 `pistonPos + pistonDirection` 不在 Z；
  - `toPush` 里任一 `p`：`p` 或 `p + pushDirection` 不在 Z；
  - `toDestroy` 里任一 `p` 不在 Z。
- 为什么挂在这里，而不是 Forge 的 `PistonEvent.Pre`：
  - `Pre` 在缩回时算不出要拉的方块：那时活塞头还在 `pos + facing`，而活塞头的推动反应是 BLOCK（`Blocks.<clinit>` 里确认过），`pos + 2·facing` 有可推的方块时 `resolve()` 一律返回 false。原版是在 `moveBlocks` 里先移走活塞头再 resolve 的。
  - 取消缩回的 `Pre` 会让活塞在没有红石信号时卡在伸出状态。
  - `resolve()` 被三处共用：`checkIfExtend`（`m_60167_`，伸出被拒就根本不排方块事件，不发包）；`moveBlocks`（`m_60181_`，缩回时这里返回 false 就是"缩回但不拉"，`triggerEvent` 在缩回时不看它的结果）；以及任何 Forge 监听者调的 `getStructureHelper().resolve()`。粘液块、蜂蜜块的侧枝，以及伸出时挤碎 `startPos` 上"破坏"反应的方块，都在这两张表里。
- Flan 的做法（`isPushable` 头部逐块比较顶层领地）分不出同一个区里的两块地；只作参考，不照抄。
- **粘性活塞缩回的客户端不同步。** 服务端仍会广播缩回事件，没有守卫的客户端会自己把方块拉过来，留下幽灵方块。修法：拒绝分支里如果是缩回，对 `toPush` 的每一格和各自的终点调 `level.sendBlockUpdated(p, s, s, Block.UPDATE_CLIENTS)`。这批更新在本 tick 末尾发出，晚于方块事件包，客户端会被纠正回来（Flan 也是这样重发的）。更彻底的做法（在 `checkIfExtend` 里把方块事件 id 1 改成 2，两边都不拉）本阶段不做，阶段 4 看了效果再定。
- 严格口径（22.2）：野外的活塞不能把方块推进区里，也不能把区里的方块拉出来；区里的活塞同样不能把方块推到区外。

#### 流体：`FlowingFluid.canSpreadTo`

- Forge 47 没有流体流动的事件。唯一沾边的 `BlockEvent.FluidPlaceBlockEvent` 不看取消；`CreateFluidSourceEvent` 只给邻近水源的位置、不给目标。
- `canSpreadTo`（protected，SRG `m_75977_`，参数 `(BlockGetter, BlockPos from, BlockState, Direction, BlockPos to, BlockState, FluidState, Fluid)`）是 `spreadTo` 之前唯一的闸：`spread`（`m_76010_`）对 DOWN、`spreadToSides`（`m_76014_`）对四个水平方向调它。`ForgeFlowingFluid`、水、岩浆都不覆写它。
- HEAD `@Inject(cancellable = true)`：方向是水平的、`level` 是服务端的 `Level`、按"区内边界"口径跨界 → `setReturnValue(false)`。竖直方向直接放行（区是全高方柱）。
- 与 Flan 在同一方法头部的注入共存，谁先谁后结果都一样（都只会拒绝）。
- 不另挂 `canPassThrough`（流向搜索用）：挂上以后水会顺着边界流，更好看；但它是私有方法、机械动力已在它头部注入，收益不值得多一个目标。现在的样子是水冲着边界流过去、停在边界上。
- 不拦、记为遗留：
  - `tick` → `getNewLiquid` 会读到边界另一侧的水源：邻居的水源能让你这边已有的流动水不干、甚至变成水源，但它从不在空格里造出流体。
  - 岩浆挨着邻居的水变成黑曜石或圆石：变的只是岩浆自己那一格，而且事件分不出两边的位置。

#### 发射器与投掷器：`dispenseFrom`

- 目标 `DispenserBlock.dispenseFrom(ServerLevel, BlockPos)V`（protected，SRG `m_5824_`，由 `tick` 调）。`DropperBlock` 覆写了它且不调 super，所以一个 `@Mixin({DispenserBlock.class, DropperBlock.class})` 对两者各注入一次。
- Forge 没有发射的事件：`FillBucketEvent`、`EntityPlaceEvent` 只在玩家路径上发；Forge 自己的 `DispenseFluidContainer` 直接对前方调 `FluidUtil.tryPlaceFluid` / `tryPickUpFluid`。
- 原版的每一种发射行为都作用在 `pos.relative(FACING)`：
  - 满桶（水、岩浆、细雪、鱼、美西螈、蝌蚪）倒出，空桶舀起；
  - 潜影盒放置；南瓜与凋灵头直接 `setBlock`（可能召出傀儡、凋灵）；
  - 打火石、骨粉、剪刀、蜜脾、荧石给重生锚充能、水瓶把泥土变泥巴、玻璃瓶取水；
  - TNT、船、矿车、刷怪蛋、盔甲、鞍、箱子在前方生成或穿戴实体；
  - 农夫乐事的砧板行为也作用在前方；
  - 投掷器往前方的容器里塞（`VanillaInventoryCodeHooks.dropperInsertHook`），没有容器就把物品丢在前方。
- HEAD `@Inject(cancellable = true)`：`FACING`（`f_52659_`）取自方块状态，按"单向"口径前方属于别的区域 → `level.levelEvent(1001, pos, 0)`（原版的"发射失败"咔哒声）并取消。物品不消耗。它排在农夫乐事的注入之前。
- 不拦、记为遗留：射弹（火焰弹、箭、药水）在本区射出、落进别的区域；侧向的漏斗把物品推进邻居的容器（只进不出，等于送东西，以后要拦可以挂 `HopperBlockEntity.ejectItems`）。

#### 下落的方块：`FallingBlockEntity.tick`

- 没有 Forge 事件；落地时 `tick`（`m_8119_`）里直接 `level.setBlock(blockpos, blockState, 3)`。`getStartPos()`（`m_31978_`）只是同步数据，不存盘，区块重新加载后是 `BlockPos.ZERO`，不能用。
- 只在服务端（复核改，22.19）：
  - `tick` 的 HEAD 只记起点：两个 `@Unique` 字段记下实体第一次被看到时所在的 X、Z。存坐标，不存区域号：区域号在索引重建后会变。起点随实体存盘（`addAdditionalSaveData` / `readAdditionalSaveData` 的 TAIL，键 `MiningdimDistrictFallStart`，`int[]{x, z}`），区块卸下再加载不会把起点换成当时的位置。
  - 判定挂在 `tick` 里 INVOKE `move(MoverType, Vec3)`（`m_6478_`）之后（`shift = AFTER`，可取消）：原版在同一个 tick 里先移动、再按移动之后的位置落地，在 HEAD 判定会漏掉"这一 tick 才越过边界并落地"的（TNT 大炮打到边界那一格、活塞把地上的沙子推过边界）。按"单向"口径，移动之后所在列属于别的区域时：`dropItem`（公开字段 `f_31943_`）为真且 `doEntityDrops` 开着，就按 `getBlockState().getBlock()` 掉一个物品；然后 `discard()` 并取消。
  - 这就是原版"放不下就掉成物品"那一支，碰不到 Bakeries 的注入点。移动之后的可取消注入多出一个 `CallbackInfo` 局部变量，它在原有 StackMap 帧处被 Mixin 的局部捕获分析丢掉（`Locals.getLocalsAt` 按原类的帧截断），不影响 Architectury 在 `Fallable.onLand` 处的 `CAPTURE_FAILHARD`；阶段 4 照样看开服日志（22.16 第 1 条）。
- 区是全高方柱，正常下落不会跨区域；只有被横着打出去的（TNT 大炮、活塞）才受影响。

#### 海绵：`SpongeBlock.removeWaterBreadthFirstSearch`

- 私有方法（SRG `m_56807_`），用 `BlockPos.breadthFirstTraversal(start, 6, 65, BiConsumer, Predicate)`（`m_276833_`）逐格吸水。
- 两处注入：
  - HEAD 把 `level` 放进一个 ThreadLocal（`@ModifyArg` 拿不到外层方法的参数），RETURN 清掉；
  - `@ModifyArg`（INVOKE `breadthFirstTraversal`，参数下标 4，处理方法收全部五个参数）把 Predicate 包成 `p -> 不越界(start, p) && 原来的.test(p)`，口径"单向"。海绵周围 7 格内没有别的区域时直接返回原 Predicate，不包。
- 效果：邻居的水池不会被你的海绵吸干。

#### 岩浆点火：Forge 事件

- `BlockEvent.FluidPlaceBlockEvent` 在 `LavaFluid.randomTick` 里发：岩浆在最远约 2 格外的 `pos` 点火，`liquidPos` 是岩浆。这是唯一会越过边界的流体放置。
- 这个事件虽然标着 `@Cancelable`，`ForgeEventFactory.fireFluidPlaceBlockEvent` 却不看取消，只返回 `getNewState()`。唯一的办法是 `setNewState(getOriginalState())`。
- 口径"单向"（`liquidPos` → `pos`）。另两处发这个事件的地方（岩浆流进水变成石头、Forge 的流体交互表）`pos == liquidPos`，天然同区域，不受影响。

#### 不拦的其他途径

| 途径 | 为什么不拦 |
|---|---|
| 火焰蔓延、爆炸、凋灵 | Flan 的全局权限：地块写 UNSET，跟随全区一个开关（默认关，只有 OP 能开，20.3）。OP 打开火焰蔓延时火会越过地块边界；要拦得在 `FireBlock.tick` 外面套 ThreadLocal 再挂 `getIgniteOdds` / `tryCatchFire`，不值得 |
| 树与菌的生长、藤蔓横向蔓延、紫水晶芽 | 生长的根在自家地块上，越界的只是枝叶；拦生长会让边界附近的树长不出来 |
| 草与菌丝的蔓延、幽匿催发体 | 只把方块变成草、菌丝或幽匿块，不拆不建 |
| 从下界一侧点燃的传送门 | 罕见；传送门方块本身受 Flan 的"放置"约束（LIVE 时） |
| 村民收割、别的生物破坏 | 交给 Flan 的区域规则（如末影人）与原版规则 |
| 别的 mod 里不发玩家事件、直接改方块的途径 | 见 P38（清单私下核对） |

### 22.10 聊天通知：目录

15 类、24 个 kind。语言键一律 `district.miningdim.notice.<kind>`：

| 类 | kind | 触发（服务） | 收件人 | 中文 | 参数 | 投递 |
|---|---|---|---|---|---|---|
| 加为住户 | `resident_added` | `ResidentService.addResident` | 被加的人；从没进过服的是按输入名字算的离线 UUID | 你已加入{0}，成为自管区「{1}」的住户。 | 学院全称、区名 | 入队 |
| | `resident_added_frozen` | 同上，TA 原来的地块还冻结着 | 同上 | 你原来的地块 {0} 还在冻结中，请联系管理员解冻。 | 地块标签 | 入队 |
| 移出住户 | `resident_removed.inactive` | `ResidentService.removeResident` | 被移出的人 | 你已被移出{0}（原因：长期不活跃）。 | 学院全称 | 入队 |
| | `resident_removed.violation` | 同上 | 同上 | 你因违反区规被移出{0}；你在本区别人地块上的朋友身份已暂停，户主可以恢复。 | 学院全称 | 入队 |
| | `resident_removed.self_request` | 同上 | 同上 | 你已按本人申请退出{0}。 | 学院全称 | 入队 |
| | `resident_removed.other` | 同上 | 同上 | 你已被移出{0}。 | 学院全称 | 入队 |
| 冻结 | `plot_frozen` | 同上，被移出的是户主 | 原户主 | 你的地块 {0} 已冻结 {1} 天，期满收回；期间重新加入学院后可以请管理员解冻。 | 地块标签、天数 | 入队 |
| 区务长 | `warden_appointed` | `DistrictAdminService.setWarden` | 新区务长 | 你被任命为自管区「{0}」的区务长。 | 区名 | 入队 |
| | `warden_revoked` | 同上（撤销，或换人时的旧区务长） | 旧区务长 | 你不再担任自管区「{0}」的区务长。 | 区名 | 入队 |
| 买地 | `plot_bought` | `PlotMarketService.buy` | 买家 | 你买下了地块 {0}，花费 {1}。 | 地块标签、`formatCredit` | 入队（买家通常在线，提交后立即送达） |
| 解冻 | `plot_unfrozen` | `PlotFreezeService.unfreeze` | 户主 | 你的地块 {0} 已解除冻结，朋友和开关设置都已恢复。 | 地块标签 | 入队 |
| 收回 | `plot_reclaimed.now` | `PlotFreezeService.reclaimNow` | 原户主 | 你原来的地块 {0} 已被管理员收回。 | 地块标签 | 入队 |
| | `plot_reclaimed.expired` | `PlotFreezeService.reclaimExpired` | 原户主 | 你原来的地块 {0} 冻结期满，已被收回。 | 地块标签 | 入队 |
| 管理员代改 | `plot_admin.permission` | `PlotOwnerService.setPermission`，关系为 ADMIN | 户主 | 管理员 {0} 代你修改了地块 {1} 的开关：{2}。 | 管理员名、地块标签、改动文字 | 入队 |
| | `plot_admin.reset` | `resetPermissions`，关系为 ADMIN | 户主 | 管理员 {0} 把你的地块 {1} 的开关恢复了默认（{2}，{3} 项有变化）。 | 管理员名、地块标签、恢复范围、项数 | 入队（N 条记录合成一条） |
| | `plot_admin.friend_added` | `addFriend`，关系为 ADMIN | 户主 | 管理员 {0} 代你把 {1} 加为地块 {2} 的朋友。 | 管理员名、朋友名、地块标签 | 入队 |
| | `plot_admin.friend_removed` | `removeFriend`，关系为 ADMIN | 户主 | 管理员 {0} 代你把 {1} 移出了地块 {2} 的朋友名单。 | 同上 | 入队 |
| | `plot_admin.friend_restored` | `restoreFriend`，关系为 ADMIN | 户主 | 管理员 {0} 代你恢复了 {1} 在地块 {2} 的朋友身份。 | 同上 | 入队 |
| | `plot_admin.resized` | `PlotLayoutService.resize`，有户主的地块 | 户主 | 管理员 {0} 调整了你的地块 {1} 的范围：{2} → {3}。 | 管理员名、地块标签、原边长、新边长 | 入队 |
| 朋友被暂停 | `friend_suspended` | `removeResident`，违反区规 | 受影响地块的户主，每块地一条 | 你的地块 {0} 的朋友 {1} 因违反区规被移出本区，朋友身份已自动暂停；你可以在平板上恢复。 | 地块标签、朋友名 | 入队 |
| 朋友 | `friend_added` | `addFriend` | 被加的朋友；待生效的按离线 UUID | 你成为了 {0} 的地块 {1} 的朋友。 | 户主名、地块标签 | 入队 |
| | `friend_removed` | `removeFriend` | 被移出的朋友 | 你已不再是 {0} 的地块 {1} 的朋友。 | 同上 | 入队 |
| | `friend_restored` | `restoreFriend` | 被恢复的朋友 | 你在 {0} 的地块 {1} 的朋友身份已恢复。 | 同上 | 入队 |
| 开放购买 | `purchase_opened` | `DistrictAdminService.setPurchaseOpen`（关 → 开） | 本区在线、没有地块、也不是冻结地块原户主的住户 | 自管区「{0}」开放购买地块了（{1}/格），可以在平板的「本区地块」里挑选。 | 区名、单价 | 只发在线，不入队（P28） |

另有 `district.miningdim.notice.header`：上线补发时的灰色头一行"你不在线期间有 {0} 条自管区消息："。

规则：
- **不给自己发**：收件人就是操作人时不发（例如 OP 把自己加进名单、任命自己）。例外是 `plot_bought`：服主点名要给买家。
- 管理员代改时，户主收 `plot_admin.*`；朋友那一侧照样收 `friend_*`，它与谁操作无关。户主自己操作时，只有朋友收。
- 参数全是事件发生那一刻格式化好的字符串（名字、地块标签、金额、开关文字），不存翻译键。
  - 开关文字由 `PermissionCatalog` 的 label 与列名（朋友 / 其他住户 / 外人）拼成，例如"外人·破坏方块 → 关"，与平板上的叫法一致。
  - 所以英文客户端看到的是英文句子、中文参数，可以接受：`DistrictTexts` 本来就只有中文。
- **移出原因的原文绝不进任何通知。** 给被移出者只发原因种类；给户主的朋友暂停通知只用固定句子，与地块记录的固定缘由同口径（第十三章）。
- 地块标签 = `{区名}-{两位编号}`，与 Flan 里的地块名相同，例如 `阿拜多斯-01`。
- 颜色在代码里加（`withStyle`），语言值里不写 `§`：
  - 对收件人有利或中性的用青色（AQUA）：加入、任命、买地、解冻、成为朋友、恢复朋友、开放购买；
  - 不利的用金色（GOLD）：移出、撤销、冻结、收回、管理员代改、不再是朋友、朋友被暂停；
  - 上线补发的头一行用灰色。
- 没有全局前缀（仓库惯例：只有广播带前缀，例如"【世界 BOSS】"）；上线补发的灰色头一行起归类作用。
- 持久通知一律走聊天栏（`sendSystemMessage`），不用动作栏。

这顺带完成第二十一章第 3 条：
- "pending 玩家首次登录时提示'你已加入某学院'"：从没进过服的人被加进名单时，`resident_added` 按离线 UUID 入队；TA 第一次上线、补写完成之后就收到。
- "户主的地块被暂停朋友、被代改范围、被冻结时，上线提醒 TA"：分别是 `friend_suspended`、`plot_admin.resized`、`plot_frozen`。
- `DistrictTexts.resizeOwnedReason` 写进记录的"管理员代改，已通知户主 {owner}"，从此名副其实。

**不发的：** 解绑、`/district academy kick`、收回时被清空的朋友、冻结地块的朋友。以后要加，只是多一个 kind。

### 22.11 通知队列：存储与投递

#### 存储：改 V9，不开 V10（P27）

决定：在 V9 的语句列表末尾追加一张表和一条索引。

理由：
- `MiningSchema` 的铁律是"**已发布**的迁移严禁修改，只能在末尾追加"。V9 没有发布过：
  - 它只在本地分支 `feat/wok-district` 上，没有推到 origin；所有本地与远端分支里没有别的 V9；`origin/main` 只到 V4。
  - 测试端装的 jar（`D:\WOK测试\…\mods\miningdim-1.20.1-1.0.33-all.jar`，09-28 18:14）里没有任何 `com/miningdim/district` 类。
  - GameTest 每轮删掉 `run/world`。
- 开 V10 会让 4.4 的合并顺序约束更重：世界首领分支另有一套 V5–V7，谁先合入另一方就要重新编号，多一个号码就多一处要改。本文此前三次避开动迁移（4.3 的区务长触发器、6.5 的地块版本号、20.6 的领地 id 唯一索引），因为那几处都有不动迁移的办法；这一次要的是一张新表，没有别的办法，而且只在末尾追加，不改任何已有语句。
- 代价：谁用阶段 1–2 的构建开过本地开发世界，那个库已经在 `user_version 9`、却没有这张表。复核改（22.19）：
  - 开服时 `DistrictSystem` 查一次 `sqlite_master`，缺表时：
    - 功能直接降级（DEGRADED，原因"数据库缺少 district_notice 表，按服务器日志补建后重启"，Disabled 网关，不碰 Flan）：平板只读，`/district status` 写明原因；
    - 通知一律停用（`DistrictNotices.disableStore`）：入队、投递、首次登录换键、清过期都什么都不做。原来的做法是让一切会发通知的写动作在事务里失败（`STORE_FAILED`），可是首次登录激活（`FirstLoginActivation` 在它的事务里换通知的键）与每分钟的到期收回也会跟着每次失败；
    - 记 ERROR，只给不丢数据的修法：停服、备份 `<世界>/miningdim.db`、用 sqlite3 对它执行下面两条语句、再开服。**绝不能删 `miningdim.db`**：它是统一库，钱包与账本、成就、称号、职业……全在里面（原来的日志让人"删掉 miningdim.db 后重开"，照做就把所有模块的数据清空了）。两条语句放在 `DistrictSystem.NOTICE_TABLE_REPAIR_SQL`，与 V9 末尾逐字相同，GameTest 核对补建出来的结构与迁移建的一样。
  - 不自动补建：迁移之外写 `CREATE TABLE IF NOT EXISTS` 违反该文件的惯例。

```sql
CREATE TABLE district_notice (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  recipient_uuid TEXT NOT NULL,
  kind TEXT NOT NULL,
  args_json TEXT NOT NULL DEFAULT '[]',
  district_id TEXT,
  plot_id TEXT,
  created_at INTEGER NOT NULL)
CREATE INDEX idx_district_notice_recipient ON district_notice(recipient_uuid, id)
```

- `kind` 不加 CHECK：以后加一种通知不用开迁移（业务取值不进 CHECK，同 4.3）。投递时遇到不认识的 kind（例如回退了构建）记 WARN，删掉那一行。
- `args_json` 是字符串数组，用仓储已有的 Gson 读写。
- `district_id`、`plot_id` 只为排障与日后去重，不建外键（同两张记录表的理由，4.3）。
- 按 `(recipient_uuid, id)` 建索引：投递时按收件人取，按插入顺序发。

仓储新增：`insertNotice`、`noticesFor(uuid)`（按 id 升序）、`deleteNotices(ids)`、`countNotices(uuid)`、`deleteOldestNotices(uuid, keep, 可先删的种类)`（复核加了第三个参数）、`pruneNoticesBefore(at)`、`rekeyNotices(oldUuid, newUuid)`。

#### 保留与上限（P29）

吸取 `pending_payout` 只增不减的教训（V4 的注释）：
- 每人最多 30 条：入队后超出就在同一事务里删掉。复核改（22.19，P37）：先删朋友那一侧的通知（`friend_added` / `friend_removed` / `friend_restored`，任何户主都能对任何人触发），最旧的先删；只剩要紧的（移出、冻结、收回、代改、任命……只有管理员或本人的动作才会产生）才删最旧的要紧的。原来按全部种类删最旧的，户主反复加、移朋友约 15 轮就能把别人队列里的冻结通知挤掉。
- 保留 30 天：`DistrictSystem.sweep` 里每小时至多一次 `DELETE … WHERE created_at < now − 30 天`。表很小，不为它另建 `created_at` 索引。
- 一个从没进过服的人 30 天都没上线，TA 的"你已加入"就过期了；名单上照样有 TA。

#### 入队

`DistrictNotices.enqueue(recipient, kind, args, districtId, plotId)`（`ctx.notices()`）：
- 只能在业务事务里调：`repo.inOpenTransaction()` 为假就抛 `IllegalStateException`（代码错误）。
- 插入一行（超出每人 30 条就在同一事务里删掉最旧的），把收件人放进"等提交后投递"的集合，再登记 `repo.afterCommit(this::flushAwaiting)`：第一次执行时整批取走、逐人 `deliverIfOnline`，同一事务里同一人只查一次队列（B 步实现，22.18 第 1 条）。
- 行随业务事务一起提交或回滚：回滚了的操作不留通知。
- 朋友那一侧的通知合并（复核加的，22.19，P37）：入队之前删掉同一个收件人、同一块地、同一个户主（参数的第一个）还没送达的朋友通知，按"收件人最后知道的状态 → 现在的状态"只留净变化：加了又移 = 什么都不发，移了又加 = 什么都不发（TA 本来就是朋友），暂停后恢复了又移 = 移出，加了又（被暂停再）恢复 = 加入。户主不同（地块被收回、换了户主）不合并。
- 朋友那一侧的通知即时送达有间隔（复核加的）：同一个收件人 60 秒内（`DistrictLimits.FRIEND_NOTICE_INTERVAL_MS`）只即时送一次，其余留在队列里（照样合并），由定时节拍（每 1200 tick，`DistrictNotices.flushHeld`）在间隔到了之后补发给在线的，或者下次上线补发。
- 参数个数必须等于这一种的 `arity`，不符抛 `IllegalArgumentException`（代码错误）；`enqueueUnlessSelf(actor, …)` 在收件人就是操作人、或收件人为 null 时什么都不做。

各服务里的调用点（都在主事务内，紧挨着对应的写入）：

| 服务方法 | 通知 |
|---|---|
| `ResidentService.addResident` | `resident_added`；TA 原来的地块还冻结着时再加 `resident_added_frozen` |
| `ResidentService.removeResident` | `resident_removed.<种类>`；被移出的是户主时加 `plot_frozen`；违反区规时对 `suspendedPlots` 的每块地给户主 `friend_suspended` |
| `DistrictAdminService.setWarden` | 撤销分支 `warden_revoked`（收件人取 `district.wardenUuid()`，要在清掉之前读）；换人时先旧后新 |
| `PlotMarketService.buy` | `plot_bought` |
| `PlotFreezeService.unfreeze` | `plot_unfrozen` |
| `PlotFreezeService.reclaimNow`、`reclaimExpired` | `plot_reclaimed.now` / `.expired`；收件人 `plot.frozenOwnerUuid()` 要在 `vacateInTransaction` 清掉它之前读 |
| `PlotOwnerService.setPermission`、`resetPermissions`、`addFriend`、`removeFriend`、`restoreFriend` | 关系为 ADMIN 时给户主 `plot_admin.*`；加、移、恢复朋友时给朋友 `friend_*` |
| `PlotLayoutService.resize`（有户主的地块，已有 `ownerNotified` 的那一支） | `plot_admin.resized` |
| `DistrictAdminService.setPurchaseOpen`（关 → 开） | 不入队；提交后调 `broadcastPurchaseOpened(districtId, 操作人)` 发 `purchase_opened`，同一个区 10 分钟内至多一次（22.18 第 2 条） |

- 通知在服务里显式发，不从记录推导：记录行只有 `target_name`，没有收件人的 UUID。
- 不挂在 `DistrictFlanSync` 上：对账和重试也会调它。
- 平板与 `/district` 命令走同一批服务方法，两边都会发。

#### 投递

`DistrictNotices.deliverPending(ServerPlayer player, boolean fromLogin)`：
1. `gate.canDeliverNow(player)` 为假就什么都不做（22.12）；
2. 读 `noticesFor(uuid)`；为空就返回；
3. 从登录路径来的，先发灰色头一行 `header`；
4. 逐条 `sendSystemMessage(Component.translatable(kind 的键, args…).withStyle(颜色))`；
5. 删掉这些行（一个小事务）。

- 至少送达一次：先发后删。删除失败时下次上线会重发，比丢了强。
- 提交后的即时投递（`deliverIfOnline`）：`ctx.onlinePlayers()` 找到在线玩家、gate 放行，就走同一个 `deliverPending`，不带头一行。
- 数据库出错一律接住、记 ERROR：通知失败绝不能让登录或业务动作失败（业务事务已经提交了）。
- `DistrictContext` 加组件 `Function<UUID, ServerPlayer> onlinePlayers`：生产由 `server.getPlayerList()::getPlayer` 建；`DistrictTestEnv` 用 GameTest 服务端的玩家列表（`MockGameTestPlayers` 造的玩家就在里面）。

#### 首次登录换 UUID

`FirstLoginActivation.onLogin` 的事务里，凡是换键的地方一并 `rekeyNotices(旧, 新)`：
- 住户行换键（`row.uuid()` → 真 UUID）时；
- 激活待生效朋友时（朋友行原来的 UUID → 真 UUID）。

这样按名字加进来、大小写不同的人第一次上线也能收到。已解绑学院的成员不换键（`FirstLoginActivation` 本来就跳过），TA 的通知按保留期过期。

### 22.12 登录门的接缝

登录门分支（PR #72，本分支没有合入）加了 `com.miningdim.core.auth.PlayerLoginGate`：
- `onLoginConfirmed(LoginConfirmedListener)`，监听者是 `(ServerPlayer player, boolean atJoin)`。
  - `atJoin = true`：进服那一刻就放行（单人、局域网、没装 AccessHub 的服务器）。
  - `atJoin = false`：进服时被拒，之后巡检（每 5 tick）发现 `/login` 生效；管理员重置后重新登录会再触发一次。
  - 返回的登记对象 `AutoCloseable`，生产代码不撤销；停服只清等待表、不清监听者，所以监听者在子系统 `register` 时登记一次。
- `allows(ServerPlayer)`：现在能不能给这名玩家看私人内容。
- 确认在 LOWEST 优先级的 `PlayerLoggedInEvent` 里做，所以自管区 NORMAL 优先级的登录钩子（首次登录补写、换 UUID）一定先跑。
- 婚姻模块的先例：登录事件里只做自愈，私人内容挪进 `deliverAfterLogin`，由 `onLoginConfirmed` 触发；即时投递之前先问 `allows`。

本分支不复制登录门的任何代码，只定义接缝：

```java
package com.miningdim.district.notice;

/** 什么时候能给玩家看自管区的私人通知 (设计文档 22.12)。 */
public interface NoticeDeliveryGate {

    /** 现在能不能投递 (提交后的即时投递先问它)。 */
    boolean canDeliverNow(ServerPlayer player);

    /** register 时调一次: 登记"这名玩家可以收通知了"的回调。 */
    void install(Consumer<ServerPlayer> deliverPending);

    /** DistrictSystem.onPlayerLoggedIn 在首次登录补写之后调。 */
    void onPlayerJoined(ServerPlayer player);
}
```

- 本分支的默认实现 `DefaultNoticeGate`：`install` 记下回调；`canDeliverNow` = "身份已验证"；`onPlayerJoined` 在身份已验证时直接调回调（上线即发），否则什么都不做。复核改（22.19，P35），原来 `canDeliverNow` 恒为真：
  - "身份已验证" = 服务器开着正版验证（`MinecraftServer.usesAuthentication()`：单人存档、局域网、正版服），或者是 GameTest 服务端（mock 玩家没有真实身份可言）。
  - 整合包的服务器不做正版验证，身份要等 AccessHub 的 `/login` 确认之后才算数；原来的默认（上线即发）不等这一步。现在这样的服务器上一条都不发，行留在队列里（最多 30 天），登录门合入、换 gate 之后补发。
  - 开服检查 `NoticeDeliveryGates.checkWiring`（功能打开时）：装着的还是默认 gate、服务器又不验证身份 → WARN"通知被扣着"；登录门的类 `com.miningdim.core.auth.PlayerLoginGate` 已经在、装着的却还是默认 gate → ERROR（合并提醒，除了 GameTest 之外运行时也报）。
  - OP 的"机械动力防护不完整"红字走同一个回调，所以离线模式下同样不发；开服 ERROR 与 `/district status` 照常。
- `NoticeDeliveryGates` 持有当前实现。`DistrictSystem.register` 里 `NoticeDeliveryGates.install(new DefaultNoticeGate(), DistrictSystem::onLoginConfirmed)`；`onLoginConfirmed` 先调 `DistrictNotices.deliverPendingOnLogin`，再发 22.7 给 OP 的红字（B 步实现，22.18 第 3 条）。
- 回调自己先查 `DistrictServices.isRegistered()`（功能 OFF 时什么都不做），数据库出错自己接住：不影响别的监听者，也不影响登录。
- `DistrictSystem.onPlayerLoggedIn`：在 `firstLogin().onLogin(...)` 之后调 `NoticeDeliveryGates.current().onPlayerJoined(player)`，两段各自 try/catch。
- 22.7 给 OP 的"机械动力防护不完整"随同一个回调发出。

**两个分支都合入之后要做的（写进合并提交）：**
1. 新类 `LoginGateNoticeGate`，放在 `com.miningdim.district.notice`。`core.auth` 属于 wok-core，wok-district 本来就依赖它，不加依赖：
   - `canDeliverNow` = `PlayerLoginGate.allows(player)`；
   - `install(cb)` = `PlayerLoginGate.onLoginConfirmed((p, atJoin) -> cb.accept(p))`，返回的登记对象丢掉；
   - `onPlayerJoined` 什么都不做。
2. `DistrictSystem.register` 里那一行改成装 `LoginGateNoticeGate`。
3. 重复触发无害：投递完的行已经删掉；管理员重置后再次确认，只会发新产生的。
4. 两边都在语言文件末尾加了键，合并冲突只是逗号。
5. `DistrictNoticeGameTests.loginGateSeamIsWiredWhenPresent`（22.14）在合并后的分支上会提醒：它用 `NoticeDeliveryGates.loginGatePresent()`（`Class.forName("com.miningdim.core.auth.PlayerLoginGate")`）探测，类在、装着的却仍是 `DefaultNoticeGate`，就失败；开服时 `checkWiring` 同样记 ERROR。
6. 照 `MarriageLoginGateGameTests.divorceNoticeAndClaimsWaitForLogin` 补一条用例：在强制的 `NOT_LOGGED_IN` 下派发登录事件，断言什么都没发；再用 `helper.succeedWhen` 等真实 tick 的巡检触发，断言补发了。
7. 正式服打开 `enabled` 之前，这份清单必须已经做完，并在测试服重跑 23.8 第 12 条（23.10 第 4 条，P35）。

### 22.13 界面与文档文案

fixedRules（`PermissionCatalog.FIXED_RULES`，仍是一条，`ruleId` 不变）：

| 字段 | 原来 | 改成 |
|---|---|---|
| `valueText` | 全区禁用，OP 例外 | 机器禁用，装饰可放，OP 例外 |
| `detail` | 自管区里不能用机械动力，外围 8 格内也不能放它的方块，管理员（OP）例外；服务器直接拦，这条规则谁都改不了 | 自管区内和外围 8 格内不能放机械动力的机器（会转、会动或带功能的方块，轨道也算），外壳、支架、梯子、石材、玻璃这类纯装饰方块照常可放；机械动力的机器也改不了自管区里的方块。管理员（OP）亲手放置不受限。服务器直接拦，这条规则谁都改不了 |

`detail` 里的 8 仍由 `DistrictLimits.BUFFER_BLOCKS` 拼出，`BUFFER_BLOCKS` 的注释从"阶段 1 只用于展示"改成"阶段 3 起用于机械动力禁令（22.2）"。

实现时一起改的地方：

| 文件 | 位置 | 改成 |
|---|---|---|
| `district/core/PermissionCatalog.java` | 47–52 | 上表 |
| `district/service/DistrictPermissionGameTests.java` | 50 | 断言的 detail 跟着改 |
| `webui/src/mock/district-seed.ts` | 264–273 | 开发构建的假后端，同上 |
| `webui/src/pages/district/PermissionMatrix.tsx` | 111–116 | note 改成"机器禁用、装饰方块可放；外围 8 格内同样"；管理员的 grantNote 改成"亲手放置例外" |
| 同上 | 251 | "……机械动力的机器在自管区内和外围 8 格内都不能放，装饰方块可以，管理员（OP）亲手放置例外。……" |
| `webui/src/pages/district/PublicDirectory.tsx` | 33 | "……外围 8 格内也不能放机械动力的机器（装饰方块可以）。" |
| `webui/src/pages/district/AdminPanels.tsx` | 581 | "……除管理员外也不能放机械动力的机器（装饰方块可以）。" |
| `webui/src/lib/types.ts` | 3572–3573、4090、4114–4116 | 注释：禁的是机器、装饰放行；OP 例外是 `hasPermissions(2)` 且不是假玩家；"阶段 3 落地（District_Backend_Design 第二十二章）" |
| 本文 1.1 第 1 条 | 第 32 行 | "……亲手放置机械动力方块不受禁令约束（假玩家不算 OP）；OP 的机器同样改不了自管区里的方块" |
| 本文 14.2 | fixedRules 示例 | 换成上表 |
| 本文 20.3 | `flan:fake_player` 一行的说明 | "机械动力另在阶段 3 禁"改成"机械动力的假玩家另由 22.6 拦" |
| 对接说明 | 第 19 行 | 判定写成 `hasPermissions(2)` 且不是 `FakePlayer`；装饰方块放行 |

`PublicDirectory.tsx:33` 与 `AdminPanels.tsx:581` 写着"自管区外围 8 格内不能个人圈地"。设计本章时它还是第二十一章第 2 条；收尾时由 22.20 做完（关 P32），这半句从此属实，前端不改。

语言文件（`zh_cn.json`、`en_us.json` 各加，放在现有自管区键的后面）：
- `district.miningdim.notice.*` 25 个（22.10 的 24 个 kind 加 `header`）；
- `district.miningdim.guard.*` 4 个：`create_place_denied`、`create_place_denied_buffer`、`create_use_denied`、`create_incomplete_op`；22.20 再加 4 个：`claim_create_denied`、`claim_resize_denied`、`claim_op_exempt`、`claim_incomplete_op`（占位符照现有的 guard 键写 `%s`，外围格数由 `DistrictLimits.BUFFER_BLOCKS` 传入）；
- `district.miningdim.command.*`：`/district machines` 的回显、`create` / `bind confirm` / `bounds sync` 末尾的摘要、`status` 新增的守卫一节；22.20 再加 `personalclaims.*`（清单、摘要、预览，13 个）与 `status.guard.claims_*`、`status.guard.buffer_claims*`（8 个），`status.guard.index` 多一个占位符（个人圈地限制的开关）。
- `docs/modules/RESOURCE_OWNERSHIP.md` 第 40 行 WOK-自管区的键列表加上 `notice.*`、`guard.*`。
- 补一条中英键成对的 GameTest：别的模块都有，自管区还没有（22.14）。

### 22.14 GameTest 计划

约定（在 18.1、20.10 的基础上）：
- **机械动力不进开发运行时**（P30）。让它真跑要加 5 个 `runtimeOnly`：
  - `fg.deobf` 的机械动力 slim（坐标见 22.6 末尾）；
  - `fg.deobf("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.91")`；
  - `fg.deobf("dev.engine-room.flywheel:flywheel-forge-1.20.1:1.0.6-beta-266")`；
  - `fg.deobf("com.tterrag.registrate:Registrate:MC1.20-1.3.3")`（`https://maven.tterrag.com/`）；
  - `"io.github.llamalad7:mixinextras-forge:0.4.1"`（Maven Central）。

  这些都要下载、要服主批准；启动更慢；装置用例依赖 tick 时序；而且跑的是官方构建，不是整合包的重编译版。所以机械动力部分分三层核对：
  1. **判定函数**：mixin 的方法体只有一行转调 `CreateGuards` / `DistrictWorldGuards`，这些普通方法只用 Minecraft 类型，GameTest 直接调；
  2. **离线核对注入点**：见下表 `district_create_offline`，对整合包的那个 jar 核对每个目标类、方法描述符与 INVOKE 目标确实存在；
  3. **实机**：阶段 4 按 22.16 逐条跑。
- 原版的 5 个 mixin 在开发运行时里真的应用（required），行为用例真跑。
- **守卫用例的区域：** `GuardTestZones`（只在 GameTest 服务端可用，门同 `forceVerdictForTest`）按 `helper.absolutePos(...)` 在每条用例自己的结构周围建区与地块，并入一份测试快照装进门面。
  - batch `district_world_guards` 的 `@BeforeBatch` 清空，`@AfterBatch` 卸下（原版的 `BeforeBatch` / `AfterBatch`）。
  - 同一个 batch 里的用例并行跑，区域各在各的结构上，互不干扰；batch 之间串行，别的 batch 永远看到 OFF。
  - 用 `DistrictTestEnv` 的旧用例一律不装守卫，行为不变。
- **OP：** 照对接说明 2.6，GameTest 服务端的 `getOperatorUserPermissionLevel()` 是 0，`op(profile)` 给的是 0 级。要 2 级就直接往 OP 名单里放 `new ServerOpListEntry(profile, 2, false)`，finally 里移除，先断言 `hasPermissions(2)`。
- **聊天：** mock 玩家用 `makeMockServerPlayerWithChannel`，从 `EmbeddedChannel` 的出站队列里挑 `ClientboundSystemChatPacket`，取 `TranslatableContents` 的键与参数（照 `CustomTitleGameTests.systemChatKeys`）；聊天栏看 `overlay == false`，动作栏提示看 `overlay == true`。

用例清单：

| 类（batch） | 用例 | 断言 |
|---|---|---|
| `DistrictZoneIndexGameTests`（`district_zone_index`，同步） | `snapshotMatchesRepositoryAndSkipsUnbound` | 两个区、各三块地（有户主、空置、冻结各一），另有一个已解绑区：区域号两两不同，区边与地块边两端都含；已解绑区的格子是 0 |
| | `bufferIsEightBlocksSquareSameDimension` | `minX − 8` 在禁放区、`minX − 9` 不在；角上 (minX − 8, minZ − 8) 在；下界的同一坐标不在 |
| | `layoutWritesRebuildOnlyAfterCommit` | 经服务划、改、删地块，改区范围、解绑：提交后快照跟着变；TEMP 触发器让划地块回滚：快照不变；事务进行中查到的仍是旧快照 |
| | `storeFailureKeepsThePreviousSnapshot` | 重建时仓储抛 `DistrictStoreException`：旧快照照用，状态报"索引过期" |
| | `sweepRebuildsAsASafetyNet` | 用测试专用 SQL 绕过仓储写方法直接改库：下一次定时节拍之后快照对上 |
| | `boxAndHitAgreeWithZoneAt` | 框判定与逐格判定一致；`hit` 给出正确的区 id、地块 id 与标签 |
| `WorldGuardGameTests`（`district_world_guards`，多 tick） | `pistonCannotPushAcrossPlotBoundary` | 活塞在地块 A，要推的方块在 A 的边上、终点在公共区域：通电后方块不动，活塞不伸出 |
| | `pistonCannotPushIntoTheDistrictFromOutside` | 区外的活塞往区里推：不动 |
| | `stickyPistonDoesNotPullAcrossBoundary` | 在同一区域里伸出，再把被粘住的那一格划进另一块地、断电：活塞缩回，方块留在原地（服务端状态） |
| | `pistonHeadMayNotEnterAnotherZone` | 活塞面对边界，前方是空气：不伸出 |
| | `pistonDoesNotCrushAcrossBoundary` | 推动路径的尽头在另一区域有火把（推动反应"破坏"）：火把还在，活塞不伸出 |
| | `pistonWithinOneZoneStillWorks` | 同一块地里照常推、拉 |
| | `waterStopsAtPlotBoundaryButSpreadsAndFallsInside` | 水源在地块 A、离边界 2 格：40 tick 后 A 里铺开、往下流，边界另一侧没有水 |
| | `lavaStopsAtPlotBoundary` | 同上换成岩浆（主世界 30 tick 一格，等 120 tick） |
| | `waterCrossesTheDistrictOuterEdge` | 水源在公共区域、贴着区边：流到区外（外沿不归本章，交给 Flan 的开关） |
| | `dispenserWillNotEmptyABucketIntoAnotherZone` | 发射器在 A、朝向公共区域、装着水桶，通电：前方仍是空气，水桶还在发射器里 |
| | `dropperWillNotInsertIntoAnotherZone` | 投掷器在 A，前方是另一块地里的箱子：箱子是空的，物品还在投掷器里 |
| | `dispenserFromDistrictIntoWildStillWorks` | 单向口径：从区里朝区外发射照常 |
| | `fallingBlockCrossingIntoAnotherZoneDrops` | 在 A 上空生成一个带横向速度的沙子实体：进了 B 的那一列之后消失，B 里没有沙子方块，掉出一个沙子物品 |
| | `spongeDoesNotDrainANeighboursWater` | 边界两侧都有水，海绵放在 A：A 的水没了，B 的水还在 |
| | `lavaFireEventRevertsAcrossBoundary` | 直接调 `ForgeEventFactory.fireFluidPlaceBlockEvent(level, pos, liquidPos, 火)`：`pos` 在另一区域时返回原状态，同一区域时返回火（随机刻没法测，所以直接发事件） |
| | `guardsIdleWithoutZones` | 同样的活塞、水、发射器摆在没有区域的地方：全部照常 |
| | `crossPlotSwitchOffDisablesAll` | `crossPlot = false` 的测试设置：上面的越界全部放行 |
| `CreateGuardGameTests`（`district_create_policy`，同步，不需要机械动力） | `classificationFollowsNamespaceBlockEntityAndLists` | 测试设置 `namespaces = ["minecraft"]`、拒绝 `minecraft:piston`、放行 `minecraft:chest`：熔炉（带方块实体）是机器，箱子不是，石头不是，活塞是；换一份设置就换一份缓存 |
| | `badListEntriesAreReportedAndIgnored` | 格式不对、命名空间不对、方块不存在：各记一条问题，其余照常 |
| | `kineticInterfaceIsOptional` | 没有机械动力时按类名查不到 `IRotate`，不抛异常，分类照常 |
| | `ignoredVoidBlocksAreMachinesByDefault`（2026-09-30 加，22.4 末尾） | 代码默认值的 `namespaces` 含 `create` 与 `ignored_void`。开发运行时没有 `ignored_void` 的方块，`CreateBlockPolicy` 另给注册名：高炉记作 `ignored_void:gt_voided_machine`、平滑石头记作 `ignored_void:gt_voided_casing`。前者是机器，后者是装饰；非 OP 放前者在区内、外围 8 格被拒，更远照常；2 级 OP 照常；非 OP 放后者照常 |
| | `placementDecisionMatrix` | 22.5 的表逐格：（真玩家非 OP / OP / 机械动力假玩家 / 别的假玩家）×（机器 / 装饰）×（区内 / 外围 / 更远） |
| | `opExemptionExcludesFakePlayers` | 一个 `FakePlayer` 子类，档案 UUID 放进 2 级 OP 名单：`hasPermissions(2)` 为真，`GuardActors.isExemptOp` 为假 |
| | `createGuardDecisions` | `CreateGuards` 的每个方法：受保护的格子拒；外围 8 格只拒机器；区外放行；部件就近判定外扩 2 格的边界值；轨道范围里 `getBounds` 之外的直线段也算 |
| | `machinerySwitchOffDisablesHooksButNotPlacement` | `createMachinery = false`：`CreateGuards` 全部放行，玩家的放置禁令照拦 |
| `CreateEventGameTests`（`district_create_events`，同步，测试区域） | `nonOpCannotPlaceAMachineInDistrictOrBuffer` | 测试分类里熔炉是机器。mock 真玩家对着方块 `useOn` 放熔炉：区内、`minX − 8` 处都被拒，方块恢复、物品数不变、收到动作栏提示；`minX − 9` 处与下界同一坐标照常 |
| | `decorativeBlocksStayPlaceable` | 同一位置放石头照常 |
| | `opPlacesMachinesAnywhere` | 2 级 OP：区内照常 |
| | `createFakePlayerCannotTouchDistrictBlocks` | 测试把一个 `FakePlayer` 子类登记成"机械动力的假玩家"（测试专用入口，只在 GameTest 服务端可用）。对区内格子发 `BreakEvent`、`EntityPlaceEvent`、`BlockToolModificationEvent`、`RightClickBlock`（`useBlock`、`useItem` 都是 DENY）、`LeftClickBlock`、`FillBucketEvent`、`EntityInteract`、`AttackEntityEvent`：全部取消；区外全部不取消；UUID 在 OP 名单里也照样取消 |
| | `otherFakePlayersAreLeftToFlan` | 没登记的 `FakePlayer` 子类：这些事件都不取消 |
| | `denyUseBlocksRightClickButNotWrenchSneakOrBreaking` | 测试分类里熔炉是机器：非 OP 右键区内的熔炉被拒；潜行 + 测试登记的"扳手"放行；`BreakEvent` 不受影响；OP 放行；`denyUse = false` 时放行 |
| | `guardsIdleWhenFeatureOff` | 门面是 OFF：以上全部放行 |
| `CreateMixinTargetGameTests`（`district_create_offline`） | `everyHookTargetExistsInTheServerJar` | 只在带 `-PcreateJar=<路径>` 时跑（`gameTestServer` 设系统属性 `miningdim.district.createJar`，先例 `-PwithoutFlan`），否则按通过算。用运行时自带的 ASM 读那个 jar：`CreateHookTargets` 里每一项的目标类存在；方法名与描述符存在（含静态、私有）；`@Shadow` 的字段存在且类型相符；INVOKE 目标（SRG 名 `m_46749_`、`m_6425_`，以及 `customBlockPlacement`）确实出现在该方法的指令里，次数与 `require` 相符；`PaveResult.FAIL`、`SpaceType.BLOCKING`、`PlacementInfo` 的字段、`MovementContext.position`、`BezierConnection.getBounds` 都在 |
| | `hookTableMatchesTheMixinConfig` | 不需要 jar：`miningdim.district.create.mixins.json` 的列表恰好 19 个（复核前 14 个），与 `CreateHookTargets` 一一对应；`injectors.defaultRequire` 是 0，注解里没有 `require`（22.19） |
| `GuardStatusGameTests`（`district_guard_status`） | `worldGuardMixinsAreApplied` | 强制加载目标类之后，原版的 6 条都记着 |
| | `statusReportsCreateAbsentInDev` | 开发运行时没有机械动力：状态为"未安装机械动力"，不记 ERROR |
| | `statusFlagsIncompleteCreateHooks` | 合成的"已应用"集合缺 C1、C8：状态列出这两项与各自覆盖什么 |
| `DistrictNoticeGameTests`（`district_notices`） | `noticeRowsCommitAndRollBackWithTheBusinessTransaction` | TEMP 触发器让加住户的记录写入失败：没有通知行，没有聊天；去掉触发器后照常 |
| | `onlineRecipientIsNotifiedRightAfterCommit` | 在线 mock 玩家被加为住户：收到 `resident_added`，参数是学院全称与区名；队列里没有 TA 的行 |
| | `offlineRecipientGetsQueuedNoticesOnNextLoginInOrder` | 离线时依次被加为住户、被任命、被加为朋友：上线收到灰色头一行和三条，按发生顺序；行已删 |
| | `pendingPlayerHearsAboutJoiningOnFirstLogin` | 名单加一个从没进过服的 `Foo`；以 `foo`（真 UUID）第一次登录：换键之后收到 `resident_added` |
| | `removalSendsKindNeverReasonText` | 四种原因各移出一次：键分别是 `.inactive` 等；任何通知的参数里都没有原因原文；违反区规时受影响地块的户主各收一条 `friend_suspended`；被移出的是户主时再收 `plot_frozen` |
| | `wardenAppointReplaceRevoke` | 任命 A；换成 B：A 收撤销、B 收任命，先 A 后 B；再撤销 B |
| | `adminOnBehalfNotifiesTheOwner` | 管理员改开关、恢复默认（N 项合成一条）、加 / 移 / 恢复朋友：户主各收一条 `plot_admin.*`，朋友收 `friend_*`；管理员改有户主地块的范围：户主收 `plot_admin.resized` |
| | `ownerActionsNotifyTheFriendButNotTheOwner` | 户主自己加、移、恢复朋友：只有朋友收到 |
| | `freezeUnfreezeReclaim` | 解冻、立即收回、拨时钟让它到期收回（操作人"服务器"）：原户主各收一条 |
| | `purchaseOpenedReachesOnlineResidentsWithoutPlotsOnly` | 在线住户三个（没有地、有地、冻结地块的原户主），离线住户一个：只有第一个收到；队列没有新行；已经开着再开一次不发 |
| | `selfActionsSendNothingExceptPurchase` | OP 把自己加进名单：不发；买地：买家照样收到 `plot_bought` |
| | `capAndRetentionDropTheOldest` | 同一人入队 35 条：只剩最新的 30 条；拨时钟 31 天后跑定时节拍：全清 |
| | `deliveryWaitsForTheGate` | 装一个测试 gate（`canDeliverNow` 为假）：提交后不发、行还在；gate 触发回调后发出 |
| | `loginGateSeamIsWiredWhenPresent` | `PlayerLoginGate` 类不在（本分支）：装着的是 `DefaultNoticeGate`，通过；类在、仍是默认实现：失败（合并提醒，22.12） |
| | `noticeFailuresNeverBreakLogin` | TEMP 触发器让删除通知失败：登录照常完成，首次登录补写照常，消息发出，行还在（下次重发），记 ERROR |
| | `unknownKindIsDroppedWithAWarning` | 手写一行 `kind = 'nope'`：上线时不发，行被删 |
| `DistrictLangGameTests`（`district_lang`） | `everyDistrictKeyExistsInBothLanguages`、`everyNoticeKindHasAKey` | 照 `AchievementFoundationGameTests` 的 `loadJsonResource` |
| 已有类的改动 | `DistrictSchemaGameTests.v9CreatesEveryDistrictTableAtUserVersion9`、`MiningStoreGameTests.unifiedSchemaCreatesEveryTable` | 表名单加 `district_notice`，共 12 张；`user_version` 仍是 9 |
| | `DistrictCommandGameTests.machinesListsCreateBlockEntitiesInLoadedChunks` | 测试分类：区内放两个熔炉、外围放一个：`/district machines` 计数 3，列出坐标；`bind confirm` 的回显带摘要 |
| | `DistrictCommandGameTests.statusShowsGuardState` | `status` 有索引、三个开关、mixin 情况与名单问题；2026-09-30 加"算作机械动力的命名空间"一行（测试设置为 `minecraft`，`statusShowsPersonalClaimGuard` 里默认设置为 `create, ignored_void`） |
| | `DistrictFirstLoginGameTests.caseVariantNameIsRekeyedIncludingWarden`（加强） | 通知行也跟着换键 |
| | `ModDependencyDeclarationGameTests` | `create` 的可选依赖是 `mandatory = false`、`[0,)` |

复核（22.19）又加了 10 条：C15–C19 的判定、织入核对、下落方块一个 tick 越界落地与起点存盘、朋友通知防刷三条、离线模式下默认 gate 不发、缺表降级、生产的 `install` 跟着索引走，见 22.19。

跑法同 20.10：
1. 删 `run/world`；
2. `gradlew compileJava verifyModuleBoundaries runGameTestServer`；
3. 看日志里的 `All N required tests passed` 一行。已知不稳定的用例照旧：复用的世界上农夫乐事的番茄，以及 `networkOverheatsUnderSustainedLoadThenRecovers` 的配置重载竞争。

另有一轮不进默认门、偶尔跑一次的：`gradlew runGameTestServer -PcreateJar="E:\Aurora\.minecraft\versions\1.20.1-forge-47.4.16\mods\[建筑方块]create_consblocks.jar"`。**换整合包里的机械动力 jar 之前必须跑这一轮**（23.10 第 5 条）。没带 `-PcreateJar` 时两条离线核对按通过算，但各记一条 WARN"SKIPPED"（复核加的），免得"全过"被当成核对过了。只读那个 jar，不复制、不进构建、不提交。

### 22.15 对前文的改动

- **1.1 第 1 条：** 见 22.13。
- **1.2：** "机械动力禁令与外围 8 格拦截""聊天通知"两行已由本章完成。
- **第二章：** 可选集成加 `create`；资源加两份 mixin 配置；配置加两节。
- **第三章：** 类布局加 `guard/`、`mixin/`、`notice/`（22.1）。
- **4.1、4.2、4.4：** 加 `district_notice`，共 12 张表（22.11）。
- **6.1：** `DistrictContext` 加 `zones()`、`guards()`、`onlinePlayers()` 与 `notices()`。
- **10.4：** 首次登录换键时一并换通知行。
- **14.2：** fixedRules 见 22.13。
- **第十六章、20.8：** 新命令 `/district machines <districtId>`；`status` 加守卫一节（索引、两个开关、mixin 情况、机械动力版本、名单问题）；`create`、`bind … confirm`、`bounds … sync` 的回显末尾带机械动力方块的摘要。
- **18.2、20.10：** GameTest 见 22.14。
- **第十九章：** 新增 P23–P32。
- **20.3：** `flan:fake_player` 一行的说明见 22.13。
- **20.12 第 7、8 条：** 由 22.16 细化（已回写 20.12）。
- **第二十一章：** 第 1、3、6 条挪到本章；第 2 条仍是 TODO。
- **对接说明第 19 行：** 见 22.13。
- **模块文档：** `docs/modules/README.md`（配置表）、`INVENTORY.md`（文件数、GameTest 数，带日期脚注）、`RESOURCE_OWNERSHIP.md`（键）、`module-registry.json`（22.1）。

A 步（守卫，22.17）已按上表改完：1.1、1.2 的机械动力一行、第二章、第三章、6.1 的 `zones`、`guards`、14.2、第十六章与 20.8（改在 20.8）、18.2 的指针、20.3、20.12 第 7、8 条、对接说明、模块文档里守卫的部分。其余几条（1.2 的聊天通知一行，4.1、4.2、4.4，6.1 的 `onlinePlayers`、`notices`，10.4，`RESOURCE_OWNERSHIP.md` 的 `notice.*`）随 B 步的通知一起改。

### 22.16 留给阶段 4 的实机核对

在测试服（装着整合包的全部模组）逐条跑，结果回写本章。执行顺序、准备工作与每条的通过标准汇总在第二十三章（23.3、23.4、23.5–23.8、23.10），本节保留原始条目：

1. **开服：** 没有 mixin 错误，特别是 Architectury 对 `FallingBlockEntity.tick` 的局部捕获。`/district status` 显示原版守卫 6/6、机械动力防护 19/19、版本 6.0.8。
2. **放置：**
   - 非 OP 在区内、`minX − 8`、`minX − 9` 各放一根传动杆；
   - 放置助手连着放、对称之杖；
   - 装饰方块（外壳、调色板、`copycat_step`）照常；
   - OP 放置照常；
   - 从外面把轨道弯道铺进外围 8 格：非 OP 被拒，看提示是否正常、客户端预览是什么样；OP 照常。
3. **固定的钻头、锯贴着区边：** 不拆，也没有无尽的裂纹；锯伐一棵跨界的树。
4. **龙门、机械活塞、绳索滑轮带着钻头往区里走：** 停在边界外；区块没加载时的表现。
5. **轴承带长臂转进区里再停下拆装：** 区内方块完好，掉出来的是装置自己的方块。底盘、强力胶够到区内方块时组装失败并显示原因。
6. **收割机、犁、压路机的装置沿着区边走（含火车上的压路机）：** 区内作物不被重置，不耕地，不铺路。
7. **机械手（固定的与装置上的）：** 住户自己的、OP 的，对自家地块里的方块拆、放、用、倒桶、攻击：全部被拒；装置上的蓝图打印进区里被拒。
8. **蓝图炮在 50 格外打印一座跨界的建筑：** 区内的格子跳过、不耗火药；外围 8 格里的机器方块跳过。
9. **软管滑轮抽一片跨界的湖：** 区内的水不动；灌液不进区；区内已有的开口管道不放、不吸。
10. **火车带钻头穿过区（OP 铺的轨道）：** 不拆。矿车装置进区与 Flan 的 `create_contraption`。（整合包里 N/A：Flan 没有登记 `create_contraption`，矿车装置也拿不到；见 20.3、23.7 第 8 条。）
11. **区内已有的机器：** `/district machines` 的清单；传动照转；非 OP 右键被拒；潜行 + 扳手拆下（受 Flan 的"破坏方块"约束）、徒手拆照常；数值框（调转速、过滤槽）的实际表现，拦不住就记下来再定。
12. **复核补上的注入点（22.19）：** 非 OP 从外面把传送带连进外围 8 格被拒、不扣物品，OP 照常；蓝图炮在外围之外打印一条伸进区里的传送带：整条不铺；对称之杖镜像拆、放到别的地块或区里：那一侧不动；机械臂从外面（包括经网络包、蓝图炮打印的）够区内的容器：那个交互点不起作用；显示链接改区内的告示牌：不改；土豆加农炮往区里打南瓜、种土豆：不放不种。链式传送带接到区内看一眼。
13. **地块边界：** 粘性活塞缩回被拒时客户端有没有幽灵方块、多久纠正；水沿着边界的样子；发射器的咔哒声；TNT 大炮把沙子打过边界；海绵。
14. **性能：** 一台大型采矿装置贴着区边跑时，用 spark 看 `isActorActive` 与 `canSpreadTo` 的开销。
15. **通知：** 真登录（AccessHub）下离线补发的时机；两个分支都合入之后，按 22.12 核对"登录确认之前不发"。
16. **急停：** 两个 `guards` 开关关掉、重启，行为回到没有守卫的样子；再打开，恢复。
17. **别的 mod 里直接改方块的物品（P38）：** 打开 `enabled` 之前，服主在整合包里处理好的那几样（删掉配方或只给 OP）核对非 OP 拿不到、用不了。清单不在公开文档里。要保留给玩家用，另做可选 mixin 再测。
18. **个人圈地限制（22.20，P32，收尾补的）：**
    - 开服：`/district status` 显示"个人圈地限制 2/2（Flan 1.20.1-1.11.16）"，日志没有 `miningdim.district.flan.mixins.json` 的 WARN；
    - 非 OP 用真金锄头：两下都点在外围里、一下在外面一下在外围里、3D 模式在外围半空：都被拒，聊天栏红字，红框标出禁圈区（看它在大区上画成什么样）；点在区里（父领地在）时金锄头根本走不到 `createClaim`，由 Flan 自己以"没有 `EDITCLAIM`"拦下，不经本节；两角都在 `minX − 9` 以外照常；下界的同一坐标照常；
    - 把外面的领地往区的方向拖角、朝着区 `/flan expand`：被拒、范围不变；往外拖、背着区照常；`/flan add`、`/flan add rect` 落进外围被拒；
    - 2 级 OP 在外围圈个人领地照常，收到金色提示；1 级 OP 被拒；OP 用金锄头改父领地（20.6）照常、没有提示；
    - 绑定一块外围里已经有个人领地的管理员领地：预览与回显都有块数，`/district personalclaims` 列出主人与范围，那块领地原样；它的主人能缩小、能把远端往外挪，不能往外围里扩；
    - 急停：`personalClaims = false` 重启后外围照常能圈，改回 `true` 重启恢复；
    - 金锄头的陈旧编辑（22.22）：按私下交接的核对步骤做；看红字"这次改范围没有生效……"与日志一行 INFO，存盘重启后领地范围、`/district personalclaims` 与领地格数都不变。

以上与 20.12 一起全部通过之后，服主打开 `enabled`（P15）。

### 22.17 实现记录：A 步（空间索引、机械动力禁令、地块边界守卫）

2026-09-30 落地，基于设计提交 `5128ae95`。范围是 22.1–22.9，以及 22.13、22.14 里守卫的部分；聊天通知（22.10–22.12）是 B 步。没有下载任何构件，也没有把机械动力加进构建（`compileOnly`、`runtimeOnly` 都没有）。

与设计不同、或设计没写细的地方：

1. **C14 改了挂法**（已回写 22.6 的表）：对 jar 核对发现，`tryConnect` 在服务端自己扣物品（非创造模式）并调 `paveTracks(…, false)`、`placeTracks(…, false)` 铺轨，最后才返回，RETURN 时轨道已经铺好。改成三处注入：`tryConnect` 的 HEAD 清线程局部；`placeTracks` 的 HEAD 在 `simulate = true` 那一次记下 `PlacementInfo`；`tryConnect` 里唯一一次 `Player.isCreative()`（扣物品之前）处判定，拦下时把 `valid` 置假并以它为返回值提前结束。`placeTracks` 在 `tryConnect` 里恰好调两次（先模拟、后真铺），离线核对按次数看住。
2. **离线核对多了一条** `CreateMixinTargetGameTests.handlerSignaturesMatchTheServerJar`（同样只在带 `-PcreateJar` 时跑）。开发运行时没有机械动力，可选配置里的 mixin 一个都不会应用；Mixin 对处理方法签名的核对要到正式服才发生，签名写错只会变成一条 WARN 并停用那个 mixin。所以按 Mixin 0.8.5 的规则先在这里核对 17 个处理方法（C13 两个、C14 三个）：
   - `@Inject` 的参数是目标方法的参数加 `CallbackInfo` / `CallbackInfoReturnable`；
   - `@Redirect` 的参数是接收者（非静态调用时）加被调方法的参数，返回类型相同；
   - 静态与否与目标方法相同；
   - 类型不同的地方只能是标了 `@Coerce` 的 `Object`（Mixin 0.8.5 的 `Injector.canCoerce` 对 `Object` 恒放行，`@Redirect` 的接收者也可以 coerce，已对 mixin jar 核对）；
   - 影子字段与影子方法在目标类里同名、同描述符。

   2026-09-30 对整合包的 jar（SHA1 `ff2d7b3d941dfeff6d6fd729e6213a5fbb9cae96`，只读，没有复制进构建）跑过一轮：14 个注入点、17 个处理方法全部对上。三处 `remap = true` 的 INVOKE 目标在 refmap 里都有 SRG 名（`m_46749_`、`m_6425_`、`m_7500_`）。这仍不等于 mixin 在正式服真的应用上了，那要在阶段 4 看 `/district status`（22.16 第 1 条）。
3. **`createMachinery = false` 时**，22.5 表里"机械动力的假玩家"那一行整行放行（交给 Flan），与 22.6 开头一致。C14（轨道）也是 14 个 mixin 之一，按 22.1 配置注释的字面口径同样放行：急停开关要能关掉全部 14 个注入点。真玩家手放单格轨道（`TrackBlock` 带方块实体，算机器）仍走 22.5 的放置禁令，不受这个开关影响；关着开关时漏掉的只有从外面铺进来的弯道与长直道。
4. **机械手对实体右键**另走 `PlayerInteractEvent.EntityInteractSpecific`（盔甲架这类），与 `EntityInteract` 一起拦。
5. **OP 上线的红字提醒**（22.7）暂时在登录事件里直接发、不入队；B 步随通知一起改走 22.12 的 gate，代码里留了 TODO。
6. **配置名单**的校验器只要求元素是字符串：写错的项由 `GuardSettings.parse` 报出来并忽略，不让 Forge 因为一项不合法就把整张名单重置成默认值。
7. **测试设施**（都只在 GameTest 服务端可用）：
   - `data/miningdim/structures/district_guard.nbt`：16 × 5 × 16 的空结构，登记在 `module-registry.json` 的 `resourcePaths` 与 `RESOURCE_OWNERSHIP.md`。
   - `GuardTestZones`：`put` 按结构里的相对坐标装区与地块，`putAbsolute` 把区放在远处、只做判定，`useSettings` 临时换设置，`suspend` 临时回到 OFF。门面的设置是全局的，所以地块边界的急停开关单独一个 batch（`district_world_guards_off`）。
   - `GuardActors.registerTestCreateFakePlayer`、`GuardEvents.registerTestWrench`（用骨头，不用木棍：Flan 的查看工具是木棍，右键方块时 Flan 自己会取消事件）、`GuardEvents.clearActionBarThrottle`。
   - `DistrictContext.of(…, GuardSettings)` 重载与 `DistrictTestEnv.openWithGuards`；`DistrictSystem.sweep` 改为 public，让用例直接触发一次节拍。
   - `/district machines` 的用例用"只有熔炉算机器"的分类：扫描是全高的，结构旁边一格宽的空隙和头顶上会留着别的 batch 的方块实体（实测遇到过刷怪笼、信标、命令方块），按一般的测试分类，计数会随世界而变。
8. **`DistrictLangGameTests`** 放在 `guard` 包；`everyNoticeKindHasAKey` 随 B 步。
9. **别的模块**：`ChampionFoundationGameTests` 的 `registriesCreateReuseAndClear`、`registriesResetClearsAll` 改成断言之前先清空进程级注册表。新增的 batch 改变了执行顺序之后，这两条会读到前序用例留下的实例（既有的顺序依赖，与本章的逻辑无关）。

跑法同 22.14：删 `run/world` 后 `gradlew compileJava verifyModuleBoundaries runGameTestServer`，日志 `All 1866 required tests passed`；带 `-PcreateJar=<整合包里的 jar>` 再跑一轮同样全过，日志里两条离线核对都报 0 个问题。

**续写复核（2026-09-30，接在 `e4307f6e` 之后）。** `e4307f6e` 是网络中断时原样存下的工作区，提交说明写着"未经编译与测试"；按当时留下的本地运行日志，那一版其实已经编译并跑过上面两轮，说明与实际不符。续写时按 22.1–22.9、22.13、22.14 逐条对照代码复核，没有发现要改的逻辑，只补了文档（20.12 第 7、8 条、本节第 3 条）。然后重新核对：
- 新世界上 `gradlew compileJava verifyModuleBoundaries runGameTestServer`：`All 1866 required tests passed`；
- 新世界上带 `-PcreateJar` 再跑一轮：同样全过，离线核对 14 个注入点、17 个处理方法都是 0 个问题；
- 产物：`gradlew jarJar` 出的 `-all.jar` 清单里 `MixinConfigs` 带上了两份自管区配置；refmap 里 5 个原版目标与三处 `remap = true` 的 INVOKE 都有 SRG 名；`PistonStructureResolverMixin` 的 7 个影子字段经注解处理器的 tsrg 重混淆成 `f_60409_` 等；机械动力 mixin 里调用的 Minecraft 方法（如 `Level.isLoaded` → `m_46749_`、`BlockEntity.getLevel` → `m_58904_`）已重混淆，机械动力自己的影子字段（`world`、`outputPos`、`breakingPos`）保持原名；
- webui：`tsc --noEmit`、`eslint`、`check:contract` 全部通过。

### 22.18 实现记录：B 步（聊天通知）

2026-09-30 落地，接在 A 步（`70126cfd`）之后。范围是 22.10–22.12，以及 22.13、22.14 里通知的部分。没有下载任何构件，也没有加依赖。

新增的类（`com.miningdim.district` 之下）：
- `core/NoticeRecord`：`district_notice` 的一行。
- `notice/DistrictNoticeKind`：24 个 kind 的 wire 值、参数个数、颜色、是否入队。
- `notice/DistrictNotices`：入队、投递、"开放购买"的在线广播、保留期（`ctx.notices()`）。
- `notice/NoticeDeliveryGate`、`DefaultNoticeGate`、`NoticeDeliveryGates`（含只在 GameTest 服务端可用的 `swapForTest`）。
- 测试类 `notice/DistrictNoticeGameTests`（batch `district_notices`，18 条）。

改动：
- `MiningSchema` 的 V9 末尾追加 `district_notice` 与它的索引（P27）；仓储加 7 个方法（22.11）。
- `DistrictContext` 加 `onlinePlayers`、`notices` 两个组件，`of()` 多一个 7 参重载；原来的 5 参、6 参按"谁都不在线"。生产取服务器的玩家列表，`DistrictTestEnv` 取 GameTest 服务端的玩家列表。
- 八个服务方法在主事务里入队（22.11 的表）；`FirstLoginActivation` 换键时一并 `rekeyNotices`。
- `DistrictSystem`：
  - `register` 装上 gate；
  - 登录钩子在首次登录补写之后交给 gate；
  - `onLoginConfirmed` 补发通知，并给 OP 发 22.7 的红字（22.17 第 5 条的 TODO 就此完成：红字不再在登录事件里直接发）；
  - 开服时查缺表，只记 ERROR；
  - 定时节拍里每小时至多一次清过期通知。
- `DistrictLimits` 加 4 个常量；`DistrictTexts` 加三列的叫法、开关改动文字与"恢复范围"。
- 语言文件各加 25 个 `district.miningdim.notice.*` 键，一律用带编号的占位符（`%1$s`）。

与设计不同、或设计没写细的地方：
1. **提交后的即时投递按人合并。** 设计写的是每次入队登记一次 `afterCommit(() -> deliverIfOnline(recipient))`。实现改成两步：
   - 每次入队把收件人放进一个"等投递"集合，并登记同一个整批投递的动作；
   - 第一个动作执行时取走整批，所以同一事务里同一人只查一次队列（例如移出户主时的 `resident_removed.*` 加 `plot_frozen`）。
   回滚会丢弃提交后队列。集合里留下的人只会在下一次提交后多查一次队列，不会多发。写法照 A 步的几何监听。
2. **"开放购买"的广播加了节流（P33）。**
   - 同一个区 10 分钟内至多广播一次：管理员开了又关、关了又开时不重复刷屏。
   - "不给自己发"同样适用于广播，操作人自己不收。
   - 方法叫 `broadcastPurchaseOpened(districtId, 操作人)`。收件人在提交之后按库现算，每人先问 gate。
3. **登录回调是 `DistrictSystem::onLoginConfirmed`，不是 `DistrictNotices::deliverPendingOnLogin`。** 它先补发通知，再发 OP 的红字，两段各自接住异常。合并登录门时，22.12 第 2 条要改的仍是 `register` 里那一行。
4. **冻结地块不收 `friend_suspended`（P34）。** 违反区规移出时，被暂停朋友身份的地块如果正冻结着，就没有现任户主；原户主要等解冻才能恢复朋友，所以不发。
5. **`resident_added_frozen` 用金色。** 它要 TA 去处理（找管理员解冻），按"不利或要处理"归类。
6. **坏行一律丢掉。** 投递时，这几种行都记 WARN 并删掉，不发：
   - 不认识的 kind；
   - `args_json` 读不出来；
   - 参数个数与这一种不符；
   - 本来就不该入队的 kind（`purchase_opened`）。
   头一行的条数只数真正发出的。
7. **入队时校验参数个数**，不符抛 `IllegalArgumentException`（代码错误）。`DistrictLangGameTests.everyNoticeKindHasAKey` 核对两种语言的占位符恰好是 `%1$s … %N$s`。
8. **`plot_admin.reset` 的"恢复范围"** 固定为"朋友、其他住户、外人三列"：恢复默认一律是三列全部。
9. **`NoticeDeliveryGates.swapForTest` 换回原来的 gate 时不重新 install。** 原来的 gate 登记过的外部监听者（合并之后是登录门的）不会被登记两次。
10. **前端没有改。** 下面这些文字从此都有聊天消息兑现，不用动：
    - `ResidentManager.tsx` 的"户主已收到通知"；
    - `PlotList.tsx` 与 `PlotDetail.tsx` 的"写进地块记录通知户主"；
    - `PermissionMatrix.tsx` 的"户主会收到通知"。

GameTest：
- 新增 19 条：
  - `DistrictNoticeGameTests` 18 条：22.14 列的 16 条，加 `purchaseOpenedIsThrottledPerDistrict`（节流）与 `deliveryInOneTransactionIsExactlyOnce`（同一事务里的两条各发一次，再登录不重发）；
  - `DistrictLangGameTests.everyNoticeKindHasAKey`。
- 加强 4 处：
  - `DistrictFirstLoginGameTests.caseVariantNameIsRekeyedIncludingWarden`：名单行换键时，通知跟着换，顺序不变；
  - `DistrictFirstLoginGameTests.pendingFriendBecomesSyncedAndPlotRewritten`：朋友行换键时，通知跟着换；
  - `DistrictSchemaGameTests.v9CreatesEveryDistrictTableAtUserVersion9`：12 张表加通知的索引，`user_version` 仍是 9；
  - `MiningStoreGameTests` 的两份表名单。
- 核对（2026-09-30）：
  - 删 `run/world` 后 `gradlew compileJava verifyModuleBoundaries runGameTestServer`：`All 1885 required tests passed`（A 步之后是 1866，加上本步的 19 条），第一次就全过；日志里唯一的通知 ERROR 是 `noticeFailuresNeverBreakLogin` 故意注入的删除失败；
  - webui：`tsc --noEmit`、`eslint`、`check:contract` 全部通过（前端没有改动）。

### 22.19 复核修正（阶段 3 收尾，2026-09-30）

对 `bf969606` 的三路复核（对抗性绕过、性能与运维、测试与惯例）提了 12 条，逐条对 jar、字节码与代码核对过，都成立；其中两条（mixin 注入点对不上会崩服）是同一件事。改法如下。没有下载任何构件，没有加依赖。

**机械动力：补上五个注入点（C15–C19，22.6 的表）。** 都是"不发事件、直接改方块或替人读写"的路径，原来列为遗留，或以为 Flan 管得住：

1. **传送带（C15，阻断级）**：蓝图炮打印传送带时 C2 只看起点；`LaunchedItem$ForBelt.place` 在起点放一根传动杆后调 `BeltConnectorItem.createBelts(起点, 起点 + 方向 × (长度 − 1))`，沿途 `destroyBlock` 不可替换的方块再放传送带；长度来自蓝图里的数据，所以起点在外围之外时照样能伸进区里。22.5 以为"两端都要有已有的传动杆"，这条路不需要。
2. **对称之杖（C16，阻断级）**：`SymmetryHandler` 在 `BreakEvent`（LOWEST）之后调 `SymmetryWandItem.remove`，对镜像到的位置直接 `setBlock`，不发 `BreakEvent`；镜像落到别的区域时，那里的方块也会被拆。
3. **机械臂（C17）**：`mechanicalArmRange` 只在客户端查；`ArmPlacementPacket` 对任何已加载的机械臂照单全收，不查距离与归属；蓝图炮打印的机械臂带着交互点。外面的机械臂能从区里的唱片机、堆肥桶、营火、已有的置物台与传送带上取物品，或往里塞。
4. **显示链接（C18）**：目标偏移随蓝图（`writeSafe`）走，`tickSource` 不查距离，不必右键目标方块。外面打印的显示链接能改区里告示牌、讲台的字。
5. **土豆加农炮（C19）**：`create:place_block_on_ground` 直接放南瓜、西瓜或生成下落方块，`create:plant_crop` 直接种，都不经 Flan 的放置判定（对 Flan 1.11.16 的字节码核对过）。

口径（P36）：传送带、土豆加农炮按"受保护"（与 C1–C13 同）；对称之杖、机械臂、显示链接按"单向"（它们搬物品、写字或替玩家干活，地块里自己的照常）。`createMachinery = false` 时五个都放行（与 C14 一样，急停要能关掉全部注入点）。离线核对（`-PcreateJar`）认得 `@Redirect` 在被调方法的参数之后再收目标方法参数的前缀（C16）。

**机械动力的 mixin 不再能让服务端起不来（P39，22.7）。** 可选配置的 `defaultRequire` 从 1 改成 0，C11 的 `require = 2` 改成 `expect = 2`；`DistrictMixinPlugin.postApply` 改为数处理方法真的织进去了几处（`MixinHandlerScan`），全够数才写 `applied`；`GuardMixinStatus.applied` 只认 `applied`，没织全的在开服 ERROR、`/district status` 与 OP 红字里照"缺"报，日志带上系统属性的原值。`hookTableMatchesTheMixinConfig` 看住 `defaultRequire = 0` 与注解里不再写 `require`。没带 `-PcreateJar` 时两条离线核对各记一条 WARN"SKIPPED"。`build.gradle` 里"可选配置失败只降级成 WARN"的注释补上了这个例外。兼容配置 `miningdim.compat.mixins.json`（`TideOreFishMixin`，不属于本模块）有同样的隐患，本节没有改，留给它的负责人。

**地块边界：下落的方块（22.9）。** 判定从 `tick` 的 HEAD 挪到 `move` 之后：原来在同一个 tick 里越过边界再落地的（TNT 大炮打到边界那一格、活塞把地上的沙子推过边界）会落成方块。起点随实体存盘，区块重新加载不再把起点换成当时的位置。

**聊天通知。**
1. **不做正版验证的服务器上不发（P35，22.12）**：`DefaultNoticeGate` 只在做正版验证的服务器上放行；整合包的服务器要等 `/login` 确认身份，原来的默认不等这一步。开服 `checkWiring`：不做正版验证时记 WARN；登录门的类在、却还装着默认 gate 记 ERROR（运行时也报，不只靠 GameTest）。第二十三章把"登录门已合入并重跑 23.8 第 12 条"列为打开 `enabled` 的硬性前提。
2. **朋友通知防刷（P37，22.11）**：同一收件人、同一块地、同一户主只留合并后的净变化（加了又移 = 没发生）；超出 30 条先删朋友通知，冻结、移出、收回这些要紧的挤不掉；即时送达每人 60 秒至多一次，其余由定时节拍补发。原来户主反复加、移朋友约 15 轮就能挤掉别人的冻结通知，对在线的人每秒能刷 30 行，对从没进过服的名字每个都留一份 30 条、30 天的队列。每次加、移朋友都重写一次 Flan 子领地，这一项的开销仍只由平板的限速（每秒 30 次）挡着，没有另加限制。
3. **缺表不再让人删库（P27，22.11）**：库在 9 却没有 `district_notice` 时，功能降级为只读（原因写明），通知停用（入队、投递、首次登录换键、清过期都不做），登录与定时节拍照常；ERROR 只给"备份、用 sqlite3 补建两条语句、重启"的修法，并写明绝不能删统一库。原来的日志让人删 `miningdim.db`，而且缺表时首次登录激活与每分钟的到期收回都会失败。

**别的 mod（P38，22.6）**：整合包里另有少数物品同样不发事件、直接改方块。本阶段不写 mixin，第二十三章把"服主在整合包里处理好（删掉配方或只给 OP）"列为打开 `enabled` 的硬性前提；清单不写进公开文档。

**测试（22.14 的补充，10 条）：**
- `CreateGuardGameTests.lateHookDecisions`：C15–C19 的判定（传送带的框；OP 只放行自己连的那一条、只认一次；非 OP 被拒；对称之杖按单向过滤、OP 例外；机械臂、显示链接的单向口径；土豆加农炮的受保护口径；急停开关）。
- `GuardStatusGameTests.handlerScanCountsWovenCalls`：在合成的类节点上核对织入核对（改名后的调用、`expect = 2`、别的类的调用不算）；`worldGuardMixinsAreApplied` 改为断言系统属性恰好是 `applied`，开发运行时里真实地把扫描跑一遍。
- `WorldGuardGameTests.fallingBlockLandingAcrossTheBoundaryInOneTickDrops`（地面上的沙子一个 tick 越过边界并落地：掉成物品）、`fallingBlockStartSurvivesSaveAndLoad`（起点存盘、读回）。
- `DistrictNoticeGameTests`：`friendAddRemoveFloodKeepsImportantNotices`（20 轮加、移之后冻结与移出通知还在）、`capEvictsFriendNoticesFirst`、`friendNoticesToAnOnlinePlayerAreSpacedAndNetted`、`defaultGateHoldsNoticesWithoutVerifiedIdentity`、`missingNoticeTableDegradesInsteadOfFailing`（降级、业务照常、补建语句建出的结构与迁移逐字相同）；`adminOnBehalfNotifiesTheOwner`、`ownerActionsNotifyTheFriendButNotTheOwner` 在朋友操作之间拨过 60 秒的间隔。
- `DistrictZoneIndexGameTests.productionInstallFollowsTheIndex`（batch `district_guard_install`，同步、单独一批）：生产的 `DistrictWorldGuards.install` 之后，开服后才建的区、划的地块、解绑，提交之后守卫都看得到。原来所有守卫用例都走合成快照，丢了 `install` 或 `onRebuilt` 的挂接也不会有用例失败。

**核对（2026-09-30）：**
- 删 `run/world` 后 `gradlew compileJava verifyModuleBoundaries runGameTestServer`：`All 1895 required tests passed`（1885 加上本节的 10 条）；
- 删 `run/world` 后带 `-PcreateJar=<整合包里机械动力 jar 的只读副本，SHA1 ff2d7b3d…>` 再跑：同样全过，离线核对 19 个注入点、25 个处理方法都是 0 个问题；
- `gradlew jarJar`：`-all.jar` 里有五个新 mixin 与 `MixinHandlerScan`；refmap 里下落方块的 `move` 指向 `m_6478_`，存读盘指向 `m_7380_` / `m_7378_`；新 mixin 里调用的 Minecraft 方法已重混淆（如 `UseOnContext.getLevel` → `m_43725_`），机械动力自己的影子方法（`getSourcePosition`、`getTargetPosition`）保持原名；
- webui：`tsc --noEmit`、`eslint`、`stylelint`、`check:contract` 通过（前端没有改动）。

### 22.20 个人圈地限制（关 P32，2026-09-30）

本节补做第二十一章第 2 条：自管区内和外围 8 格内，玩家不能新圈、也不能扩进个人 Flan 领地。设计本章时它不在阶段 3（P32），这里在阶段 3 收尾时补上。

依据：
- 对服主批准的 `flan-1.20.1-1.11.16-forge.jar`（SHA1 `be83187d…f870`，同 20.1）做的 `javap -p -c -l -s -constants`，外加 `CommandClaim` 的 `javap -v`（用引导方法表把 `/flan` 命令树里的 lambda 对到方法与权限节点）。只读，没有下载任何东西。
- 设计基线：分支 `feat/wok-district`，HEAD `f4715671`，阶段 1–3 已全部完成。

服主已拍板、本节照做的前提：
1. 自管区内与外围 8 格内，玩家不能圈个人 Flan 领地。
2. 区外个人圈地自由：Flan 原生，玩家自己给别人开权限。
3. 管理员 = OP。OP 例外的口径与机械动力禁令相同：`hasPermissions(2)` 且不是 `FakePlayer`（22.5 的 `GuardActors.isExemptOp`）。本节没有找到不照做的理由（见"认人与例外"）。
4. 整合包里别的会直接改方块的 mod 物品由服主以后在整合包里另行处理，不归本节（P38、23.10）。

#### 口径

- **禁圈区就是 22.2 的禁放区**：每个在用自管区按**库里**的范围，向 X、Z 四个方向各外扩 `DistrictLimits.BUFFER_BLOCKS`（8）格；方形、两端都含（区从 `minX` 起，`minX − 8` 在内、`minX − 9` 不在）；全高；只在该区所在的维度；已解绑的区没有。查询走同一份 `DistrictZoneSnapshot`（22.3），不读 Flan。
- **个人领地** = Flan 的顶层玩家领地（`owner != null`）。管理员领地（`owner == null`，含自管区父领地）与任何子领地都不算。
- **新圈**：候选领地的 X/Z 框碰到任何一个禁圈区就拒。3D 领地同样只看 X/Z：区是全高方柱，一块浮在 y = 200 的 3D 领地照样在区的那几列里。
- **改范围：不许新占禁圈区里的列。** 对同一维度里的每个在用自管区 d，令 B = d 的禁圈区，O = 改之前的框，N = 改之后的框：
  - N ∩ B 为空：与 d 无关；
  - O ∩ B 为空、N ∩ B 不为空：拒（从外面扩进来）；
  - 两者都不为空：N ∩ B 必须落在 O ∩ B 之内（四次整数比较），否则拒。

  这样外面的领地扩不进来；本来就压着外围的老领地（见"已有的个人领地"）可以缩小，也可以把远离区的那一边往外挪，但不能多占外围的一列。逐区判定与按并集判定等价：N 里有一列落在某个 B 里、却不在 O 里，当且仅当按并集判定也不过。
- **为什么不只靠 Flan 的"顶层领地不许重叠"**：Flan 只挡压到父领地上的个人领地，而且要父领地确实存在、范围与库一致。以库为准的判定补上四个缺口：
  1. 外围 8 格：Flan 根本不知道有这一圈；
  2. DEGRADED 时建的区还没有父领地：期间有人在那片地上圈了个人领地，之后 `ensureDistrictClaim` 按库去建父领地就永远"重叠"（20.6）；
  3. 父领地被 `/flan adminDelete` 删了、还没 `recreate`；
  4. 用金锄头缩了父领地、还没 `bounds sync`（守卫按库里的旧范围算，22.2）。

#### Flan 1.11.16 里建、改个人领地的全部路径

逐个对 jar 核对的调用关系（类名前缀 `io.github.flemmli97.flan.` 从略；标"不拦"的几条是 P41 的默认）：

| 路径 | 走到的 Flan 方法 | 拦不拦 | 说明 |
|---|---|---|---|
| 金锄头（`claimingItem`）普通模式、3D 模式：第一下记角，第二下圈 | `event.ItemInteractEvents.claimLandHandling` → `claim.ClaimStorage.createClaim(BlockPos, BlockPos, ServerPlayer)Z` | 拦（F1） | 3D 与否只看 `PlayerClaimData.getClaimMode().is3d`，同一个方法 |
| `/flan add <from> <to>`、`/flan add rect <x> <z>`、`/flan add all` | `commands.CommandClaim.addClaim` / `addClaimRect` / `addClaimAll`（算出边长再调 `addClaimRect`）→ `createClaim` | 拦（F1） | 权限节点 `flan.claim.create`，不是管理命令，人人可用 |
| 金锄头拖角：点自己领地的一个角，再点新位置 | `claimLandHandling` → `ClaimStorage.resizeClaim(Claim, BlockPos from, BlockPos to, ServerPlayer)Z` | 拦（F2） | 要求点中的那块领地上有 `EDITCLAIM` |
| `/flan expand <distance>` | `CommandClaim.expandClaim`（按玩家朝向取一个角、沿朝向外推）→ `resizeClaim` | 拦（F2） | 同上 |
| 金锄头拖角时编辑状态与登记不一致（22.22 补） | `claimLandHandling` → `resizeClaim` | 拦（F2，对谁都拒） | 见 22.22 |
| 金锄头子领地模式（含 3D 子领地） | `Claim.tryCreateSubClaim` / `Claim.resizeSubclaim` | 不拦 | 见下 |
| `/flan transferClaim <玩家>` | `ClaimStorage.transferOwner(Claim, ServerPlayer, UUID)` | 不拦 | 只换主人，面积不变。管理员领地没有主人，只有开着 bypass（管理命令）的人能转 |
| `/flan setAdminClaim <true\|false>` | `ClaimStorage.toggleAdminClaim` → `Claim.toggleAdminClaim` | 不拦 | 管理命令。在父领地上执行 `false` 会把父领地变成执行者的个人领地：对账已经把它当"修不了的差异"报 ERROR（`DistrictReconciler.reportDistrictGeometry`），下面的清单也会把它列成"本区父领地" |
| `/flan add <from> <to> <维度> <玩家>` | `CommandClaim.addClaimAs` → `createAdminClaim` 再 `transferOwner` | 不拦 | 管理命令：OP 替别人圈；圈出来的个人领地出现在清单里 |
| `/flan readGriefPrevention` | `ClaimStorage.readGriefPreventionData` | 不拦 | 管理命令，一次性导入 |
| 结构自动圈地（`autoClaimStructures`，默认关） | `event.WorldEvents.onStructureGen` → `createAdminClaim` | 不拦 | 圈的是管理员领地 |
| 在 2D 领地底下放方块 | `Claim.extendDownwards` | 不拦 | 只降低 Y，不动 X/Z |
| 开服读盘 | `ClaimStorage.read` | 不拦 | 不是玩家的动作；读进来的照样出现在清单里 |

- 金锄头点进一块已有的顶层领地（例如父领地）时，`claimLandHandling` 先查那块领地的 `EDITCLAIM`，没有就回 `flan.cantClaimHere` 并把那块领地描出来，根本走不到 `createClaim`。所以区里的新圈，金锄头由 Flan 自己挡；走到 F1 的只有 `/flan add` 这类按坐标圈的命令，以及上面四个缺口里父领地不在的时候。
- "管理命令"指 Flan 的 `PermissionNodeHandler.perm(source, node, true)`：没装 FTB Ranks 时要求 `hasPermission(permissionLevel)`，配置默认 2（`Config.<init>` 里的 `iconst_2`）。
- **子领地为什么不拦：**
  - `tryCreateSubClaim` 只查兄弟之间不相交，不查在不在父领地里。把子领地关在父领地里的是金锄头：两下都必须点在同一块顶层领地上（`claimLandHandling` 先 `getClaimAt(pos)`），两点张成的框出不了父领地。Flan 也只在父领地范围内找子领地，出了父领地的子领地什么都不保护。
  - 自管区父领地与地块上谁都没有 `EDITCLAIM`（20.3 第 6 条），非 OP 在区里划不了子领地。
  - 外围里的老领地划子领地，只是细分已有的面积。
- **没有可监听的事件：** Flan 1.11.16 在 Forge 上只发 `api.forge.PermissionCheckEvent` 与 `api.forge.ClaimBorderCrossEvent`（`forge.platform.ClaimEventsImpl`），没有建领地、改领地的事件。所以只能 mixin。

#### 拦在哪里

新的第三份 mixin 配置 `miningdim.district.flan.mixins.json`，两个 mixin：

| # | 目标 | 方法 | 写法 | 覆盖 |
|---|---|---|---|---|
| F1 | `claim.ClaimStorage` | `createClaim(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;)Z` | HEAD，可取消，`CallbackInfoReturnable<Boolean>`：判定为拒 → 发提示、画红框、`setReturnValue(false)` | 金锄头新圈（普通、3D）；`/flan add`、`add rect`、`add all` |
| F2 | 同上 | `resizeClaim(Lio/github/flemmli97/flan/claim/Claim;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;)Z` | 同上；`Claim` 参数写成 `@Coerce Object` | 金锄头拖角；`/flan expand` |

- **为什么挂在这两个方法的 HEAD：**
  - 参数里有全部上下文：动手的 `ServerPlayer`（OP 例外要的就是它）、两个角、被改的那块领地。
  - 只依赖方法签名：没有 INVOKE、没有局部变量、没有影子成员，Flan 改写方法体也不容易对不上。
  - 在 Flan 自己的任何检查之前拒绝，什么都没消耗：Flan 的圈地冷却（`updateLastClaim`）、领地格数都只在成功时才动；金锄头已点的角由 `claimLandHandling` 在调用之后照常清掉。
  - 代价两个：F2 要照抄 Flan 算新框的那几行（下面的"改范围的新框"）；红框要自己调 Flan 的显示接口，借不到 Flan 的冲突显示。
- **考虑过、不用的：**
  - `platform.integration.claiming.OtherClaimingModCheck.findConflicts(Claim, Set<DisplayBox>)`：Flan 给 FTB Chunks、MineColonies 留的"别的圈地模组"接缝，由私有的 `ClaimStorage.conflicts` 调；拒绝时 Flan 自己发 `flan.conflictOther` 并画红框，最原生。不用它：
    1. `INSTANCE` 是 `static final` 的平台实现，登记不了新的，照样得 mixin 进 `forge.platform.integration.claiming.OtherClaimingModCheckImpl`；
    2. 不知道动手的人：候选领地的主人 UUID 就是动手的人，可假玩家与主人同一个 UUID，按 UUID 认 OP 会放过 OP 的机械手（22.5 的教训）；
    3. `resizeClaim` 传进来的候选是 `new Claim(opposite, to, player.getUUID(), player.serverLevel())`，**永远不是管理员领地**，也不带原来那块领地：分不出"OP 用金锄头改父领地"（20.6 的运维流程）与"玩家改个人领地"，也做不了"不许新占外围的列"。
  - `ClaimStorage.conflicts` 的 RETURN：有原来那块领地（参数 `except`），仍然不知道动手的人，要在 F1、F2 的 HEAD 放进线程局部再在这里取出，一共三处注入，还得保证异常时清掉；`readGriefPreventionData` 也调它，会把 OP 的导入拒掉。
- **写法**（照机械动力的 mixin，22.1）：
  - `@Pseudo @Mixin(targets = FlanHookTargets.CLAIM_STORAGE, remap = false)`，`@Inject(method = …, at = @At("HEAD"), cancellable = true, remap = false)`。Flan 自己的类名、方法名没有混淆；描述符里的 Minecraft 类在正式服也是官方类名；HEAD 不需要 refmap。
  - 处理方法名以 `miningdim$` 开头，方法体只转调一行：F1 → `FlanClaimGuard.createDenied(pos1, pos2, player)`，F2 → `FlanClaimGuard.resizeDenied(this, claim, from, to, player)`（`this` 是被注入的 `ClaimStorage`，当 `Object` 传，22.22 加的），为真就 `setReturnValue(false)`。
  - 边界规则（20.1 的 `verifyModuleBoundaries`）按字面查：`io.github.flemmli97.flan` 这串字只许出现在 `district/flan/real/` 之下，字符串常量也算。所以：
    - 目标类名与描述符放在 `flan/real/FlanHookTargets`：只有字符串与记录，不引用 Flan 的类型，没装 Flan 也能加载（同 `FlanCompat`）。mixin 注解引用它的 `static final String` 常量，编译时内联；
    - mixin 放在新包 `com.miningdim.district.mixin.flan`（与 `world`、`create` 是兄弟包），`Claim` 参数一律 `@Coerce Object`，源码里没有那串字；
    - `flan/real/FlanClaimGuard` 把 `Object` 转成 `ClaimStorage`、`Claim`，挡金锄头的陈旧编辑（22.22），读 `getDimensions()` 与 `isAdminClaim()`，调 Flan 的显示接口；
    - 判定本身在 `guard/PersonalClaimGuard`，只用 Minecraft 类型与整数，不装 Flan 的 GameTest 也能直接调。
- **配置**：`required = false`、`minVersion 0.8.5`、`JAVA_17`、refmap 同前、`plugin = com.miningdim.district.guard.DistrictMixinPlugin`、`injectors.defaultRequire = 0`（22.7 第 0 条：可选配置 `require` 不满足也会崩服），只用 `mixins` 列表。
- **插件**：包前缀 `com.miningdim.district.mixin.flan.` 的 mixin 只在 `FMLLoader.getLoadingModList().getModFileById("flan") != null` 时应用；`postApply` 照旧数织进去了几处、写系统属性（22.1）。F1、F2 分成两个类，各对应一个系统属性（`miningdim.district.mixin.FlanCreateClaimMixin.ClaimStorage` 等），缺哪个报哪个。
- `build.gradle` 的 `mixin {}` 与 jar 清单的 `MixinConfigs` **两处都加**这份配置（22.1 接线的教训）；`module-registry.json` 的 `resourcePaths` 加上它。

#### 改范围的新框（F2）

照抄 `ClaimStorage.resizeClaim` 字节码偏移 0–121：
- 对角 `opposite` = (`dims.minX() == from.getX() ? dims.maxX() : dims.minX()`，Y 不管，`dims.minZ() == from.getZ() ? dims.maxZ() : dims.minZ()`)；
- 新框 = `opposite` 与 `to` 两点张成的 X/Z 框（`Claim` 构造器里的 `Math.min` / `Math.max`）。

`/flan expand` 也是先按朝向取一个角当 `from`、外推出 `to`，同一个公式。写成纯函数 `PersonalClaimGuard.resizedBox(oldMinX, oldMinZ, oldMaxX, oldMaxZ, fromX, fromZ, toX, toZ)`；真 Flan 用例拿 Flan 改完之后的 `getDimensions()` 核对它，公式与 Flan 不一致时那条用例失败。

#### 认人与例外

- **动手的人**是方法参数里的 `ServerPlayer`：F1 里就是将来的主人；F2 里不一定是主人（有 `EDITCLAIM` 的组员，或开着 bypass 的 OP）。例外按动手的人算，不按领地主人。
- **OP 例外 = `GuardActors.isExemptOp(player)`**：`hasPermissions(2)` 且不是 `FakePlayer`。1 级 OP 不例外（P14）；OP 的机械手拿金锄头也不例外。
  - OP 碰到禁圈区时放行，并发一条金色提示 `district.miningdim.guard.claim_op_exempt`："提示：这里在自管区「{0}」和它外围 {1} 格内。管理员不受个人圈地限制，这块领地会列在 /district personalclaims {2} 里。" 防的是 OP 本想用金锄头改父领地，却点在了父领地外面，顺手圈了一块自己的个人领地。
  - 没有找到不照做的理由：OP 的个人领地同样压不进父领地（Flan 的重叠检查），外围里的照样列进清单；OP 圈了再 `/flan transferClaim` 给别人，是 OP 自己的决定。
- **管理员领地不管**：F2 里被改的领地 `isAdminClaim()` 为真就直接放行。父领地的范围本来就由 OP 用金锄头改（20.6），而 Flan 在 `claimLandHandling` 里要求 `EDITCLAIM`，父领地上只有 OP 有；别的管理员领地（主城 DU 等）也归 OP 管。
- **金锄头的陈旧编辑不分人**（22.22）：要改的领地与 Flan 当前的登记对不上时，F2 一律拒，查在管理员领地与 OP 例外之前。
- 别的 mod 的假玩家：都不是 OP，在禁圈区里一律拒。

#### 拒绝时玩家看到什么

- **聊天栏一行红字**（`displayClientMessage(…, false)`：Flan 自己的圈地提示也在聊天栏，原因要读得到）：
  - `district.miningdim.guard.claim_create_denied`："自管区「{0}」和它外围 {1} 格内不能圈个人领地（红框是这片范围）。"
  - `district.miningdim.guard.claim_resize_denied`："不能把领地扩进自管区「{0}」和它外围 {1} 格（红框是这片范围）；缩小、往外挪照常。"

  {0} 是碰到的第一个区的显示名，{1} 由 `DistrictLimits.BUFFER_BLOCKS` 拼出。不限频：两下点击才触发一次。
- **陈旧编辑**（22.22）另是一行红字 `district.miningdim.guard.claim_stale_edit`："这次改范围没有生效：金锄头选中的领地已经变了。请回到那块领地，重新点一下它的角。"不画红框（不涉及禁圈区）；服务器日志记一行 INFO（谁、哪块、为什么）。
- **红框**：`PlayerClaimData.get(player).addDisplayClaim(new DisplayBox(禁圈区 minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ), EnumDisplayType.CONFLICT, player.blockPosition().getY())`。FTB Chunks 那条接缝画冲突区块用的是同样的 `DisplayBox` 与高度。显示是尽力而为：单独 try/catch，失败只记 DEBUG，不影响拒绝；假玩家不画。
- **已知的不便**：第一下（记角）点在外围里时不提示，第二下才拒。要在第一下就提示得再挂 `claimLandHandling`，不值得。

#### 开关、失败与降级

- **急停开关**：`[district.guards]` 加一项，与另两项一样只在开服读：

  ```toml
  		# 个人圈地限制 (22.20): 自管区内与外围 8 格内不能新圈、扩进个人 Flan 领地 (OP 例外)。
  		# false 只是急停: F1、F2 一律放行; /district personalclaims 与各处的报告照常。
  		personalClaims = true
  ```

  `GuardSettings` 加 `personalClaims` 与 `withPersonalClaims`。
- **门面**：判定读 `DistrictWorldGuards.view()`，与 22.3 相同。功能 OFF、GameTest 默认都是 OFF，什么都不拦；DEGRADED 与 LIVE 都装着（只依赖库）。DEGRADED 时正好补上"父领地还没建"那个缺口。
- **出错放行**（P42）：两个处理方法外层 try/catch（`RuntimeException | LinkageError`），一律放行，走 `DistrictWorldGuards.failOpen`（每小时至多一条 ERROR）。`PersonalClaimGuard` 另记放行次数，`/district status` 显示"运行时出错 N 次（已放行）"。漏过去的领地由下面的清单兜底。
- **织没织进去**（同 22.7）：
  - `GuardMixinStatus` 加 Flan 一节：Flan 在（`ModList.isLoaded("flan")`）时按 `FlanHookTargets.HOOKS` 只加载、不初始化目标类，再读系统属性，F1、F2 都是 `applied` 才算完整。版本不是 `1.20.1-1.11.16` 时记 WARN"Flan {版本} 未经核对"，照常核对（网关那边 `FlanCompat` 本来就会降级）。
  - 开服：不完整且功能开着记 ERROR，列出缺的与各自覆盖什么；功能关着只记 INFO。
  - `/district status` 守卫一节加一行："个人圈地限制 2/2（Flan 1.20.1-1.11.16）"，或"未安装 Flan（没有个人领地可拦）"，或"个人圈地限制不完整：缺 F1"；急停关着时注明"（急停：关）"。
  - OP 上线的红字，与 `create_incomplete_op` 同一个回调、同一个 gate（22.12）：`district.miningdim.guard.claim_incomplete_op`："【自管区】个人圈地限制没有生效：缺 {0}。自管区和外围 {1} 格内现在能圈个人领地，详情见 /district status 与服务器日志。"
  - 缺了还剩什么：

    | 缺 | 仍然挡得住 | 挡不住 |
    |---|---|---|
    | F1 | 父领地在、范围与库一致时，Flan 自己的重叠检查挡住压进区里的新领地 | 外围里新圈；上面缺口 2–4 时在区里新圈 |
    | F2 | 同上（改范围也查重叠） | 把领地扩进外围；金锄头的陈旧编辑（22.22）也不再被挡 |
    | 两个都缺，或急停关着 | 同上 | 外围的全部；清单是唯一的补救 |
- **Flan 的 `permissionLevel` 低于 2**：`FlanCompat` 开服记 Flan 配置那条 INFO（20.2）时另加判断，低于 2 就记 WARN，`status` 也注明：这时 1 级 OP（P14，不例外）也能用 `/flan add … <维度> <玩家>`、`setAdminClaim`、bypass 绕过本节。
- **没装 Flan**：插件不应用 F1、F2；`status` 显示"未安装 Flan"，不记 ERROR。

#### 已有的个人领地（P40）

`/district create`、`bind`、`relink`、`bounds … sync` 之前就压着外围（极少数情况下压进区里，即上面的缺口 2–4）的个人领地：

- **不删、不冻结、不改。** 照旧受 Flan 保护，主人照旧自己管权限。
- 主人能缩小、能把远离区的一边往外挪、能删、能转让；不能多占外围的一列（上面的"改范围"）。
- 外围里本来就不能放机械动力的机器（22.5），对这些领地同样适用，OP 解释时一并说明。
- 解绑自管区之后，它的禁圈区随之消失，这些领地与别处的一样。
- 为什么只报告：删领地毁的是玩家的东西、没有撤销；冻结要改 Flan 里别人的领地；拒绝建区、绑定会让 OP 为一块边角的个人领地停工。由 OP 与主人商量：请主人缩到外围之外或 `/flan delete`；非删不可时 OP 站在领地里 `/flan adminDelete`。

**在哪里看到**（都要 LIVE，因为要读 Flan；DEGRADED 时回"领地对接未启用：{原因}"）：

1. **新命令 `/district personalclaims <districtId>`**（OP）：
   - 列出与本区禁圈区（区 ± 8 格）相交的全部个人领地：主人（按见过的玩家表 10.1 取名字，取不到再查服务器的玩家名缓存，都没有就写 UUID 前 8 位）、X/Z 范围、2D 或 3D（3D 带 Y 范围）、位置（"压进区内"，或"外围，离区边 d 格"，d 为切比雪夫距离）、领地 id 前 8 位。领地 id 恰好是本区父领地时标"本区父领地（已不是管理员领地，见对账报告）"。
   - 排序：压进区内的在前，其余按离区边由近到远。至多 20 行，其余"……另有 N 块"。末尾一行上面那段处理办法。
   - 返回找到的块数（至少 1，同 `/district machines`）；一块都没有时回"没有"。
2. **回显摘要**：`create`、`bind … confirm`、`claim … relink … confirm`、`bounds … sync` 成功之后，回显末尾追加（N 为 0 不显示；与 22.8 的机械动力摘要同一处）："外围 {0} 格内（含区内）有 {1} 块个人领地，不会删除，也不能再扩进来；用 /district personalclaims {2} 查看。"
3. **预览**：`bind` 与 `relink` 的预览多一行："外围 {0} 格内有 {1} 块个人领地：绑定后原样保留，只是不能再扩进来。"
4. **`/district status`** 的守卫一节："外围个人领地：abydos 0、millennium 2"。

- **网关**：`FlanGateway` 加

  ```java
  /** 与这片 X/Z 范围相交的顶层个人领地 (owner 不为 null), 22.20。降级、熔断、维度没加载时为空。 */
  List<PersonalClaim> personalClaimsIntersecting(String dimension, PlotArea area);
  ```

  `PersonalClaim(ClaimHandle handle, UUID owner, PlotArea area, boolean flat, int minY, int maxY)`。`FlanClaimGateway` 与 `claimsIntersecting` 同法：复制 `getClaims()` 里全部非 null 键下的领地，逐块比 X/Z；记录型在内存模型上实现；Disabled 返回空。清单、摘要与计数由新类 `flan/BufferClaimReport`（不引用 Flan）生成，命令层只排版。
- **不进对账**：对账每 5 分钟一轮，老领地不会自己变多。新出现的只能来自 F1、F2 缺席或出错、急停、功能关着的时候，或者 OP；这些都由 `status` 的计数与上面的回显看得到。（这句前提要连同 22.22 对陈旧编辑的检查一起才成立。）

#### 类布局

```
com.miningdim.district
├── guard/
│   ├── PersonalClaimGuard      判定 (新圈、改范围、新框公式)、提示、放行计数; 不引用 Flan
│   ├── DistrictZoneSnapshot    加 banHit(level, 框) 与 newBanColumns(level, 旧框, 新框), 返回 BanHit (区 id、名字、禁圈区的框)
│   ├── GuardSettings           加 personalClaims
│   ├── GuardMixinStatus        加 Flan 一节 (F1、F2)
│   └── DistrictMixinPlugin     加 FLAN_MIXIN_PACKAGE 与 flanPresent()
├── mixin/flan/                 新包; 可选配置 miningdim.district.flan.mixins.json; 2 个 mixin, 都是 @Pseudo
│   ├── FlanCreateClaimMixin    F1
│   └── FlanResizeClaimMixin    F2
└── flan/
    ├── FlanGateway 与三个实现   加 personalClaimsIntersecting 与记录 PersonalClaim
    ├── BufferClaimReport       清单、摘要、计数 (不引用 Flan)
    └── real/
        ├── FlanHookTargets     目标类名、描述符与 Hook 表 (只有字符串)
        ├── FlanClaimGuard      Object → Claim, 读框与管理员标志, 画红框
        └── FlanClaimGuardGameTests、FlanClaimGuardScenarios   真 Flan 用例的登记处与用例体 (同 20.10 的拆法)
```

#### GameTest

Flan 在开发运行时里（S1，20.1），F1、F2 真的应用，行为用例直接走 Flan。

约定：
- **玩家**：`MockGameTestPlayers.makeMockServerPlayerWithChannel(helper)`。不能用 20.10 那种不进世界的 `new ServerPlayer(…)`：`createClaim` 一路要给玩家发聊天，没有连接会空指针。`createClaim` 不看"是不是假玩家"，匿名子类无妨。Flan 默认配置下新玩家有 500 格（`startingBlocks`），领地至少 100 格（`minClaimsize`），圈地冷却 0：用例的领地取 10×10 到 20×20，一块领地换一个玩家。
- **OP**：照 22.14，往 OP 名单放 `ServerOpListEntry(profile, 2, false)`，finally 里移除，先断言 `hasPermissions(2)`。
- **区域**：新 batch 的 `@BeforeBatch` / `@AfterBatch` 调 `GuardTestZones.begin` / `end`，用例用 `putAbsolute` 在自己的槽位装区（20.10 的槽位，取 30–39，远离结构与别的用例）；要父领地的用例另用 `DistrictTestEnv.openWithRealFlan(helper, slot)` 按同一范围建区。登记处的签名里没有 Flan 类型，用例体另放一个类（同 20.10）。
- **金锄头**：`claimLandHandling` 自带 10 tick 的点击冷却（`setClaimActionCooldown`），同步用例连调两次、第二次会被吞掉。所以先用公开的 `PlayerClaimData.setEditingCorner` / `setEditClaim` 摆好"第一下"的状态，再调一次 `ItemInteractEvents.claimLandHandling(player, pos)`：走的就是真金锄头第二下的那段代码。
- **命令**：`/flan add`、`/flan expand` 以 `player.createCommandSourceStack()` 经 `performPrefixedCommand` 执行；`add rect`、`expand` 看玩家的位置与朝向，用 `setPos`、`setYRot` 把 mock 玩家摆进槽位（只改坐标，不加载区块）。
- 全部同步，不跨 tick。

| 类（batch） | 用例 | 断言 |
|---|---|---|
| `PersonalClaimGuardGameTests`（`district_claim_policy`，同步，不需要 Flan） | `createDecisionMatrix` | 合成快照、直接调判定：框在区内、碰到 `minX − 8`、只碰到角 (minX − 8, minZ − 8)、止于 `minX − 9`、别的维度同一坐标、两个区的外围重叠（报第一个区）；拒的给出区名与禁圈区的框 |
| | `resizeMayNotAddBanColumns` | 外面的领地扩进外围：拒；老领地缩小：放；老领地把远离区的一边往外挪（外围那部分不变）：放；老领地沿外围加长：拒；压进区内的老领地只能缩；新占的是第二个区的外围：拒并报第二个区 |
| | `resizedBoxFollowsFlanFormula` | 四个角各拖一次；`/flan expand` 四个方向；宽度为 1 的退化情形 |
| | `actorsAndSwitches` | 2 级 OP 放行（碰到禁圈区时要发提示）；1 级 OP、UUID 在 2 级 OP 名单里的 `FakePlayer` 子类：拒；`personalClaims = false`：全放；门面 OFF：全放；管理员领地改范围：放 |
| `FlanClaimGuardGameTests`（`district_claim_guard`，真 Flan） | `createInBufferRejectedJustOutsideAccepted` | 槽位 30，有父领地。非 OP 对 `[minX − 20, minX − 8]` 调 `createClaim`：返回 false，`allClaimsFromPlayer(uuid)` 为空，聊天收到 `claim_create_denied`（区名、8），没有 `flan.claimCreateSuccess`；`[minX − 20, minX − 9]`：返回 true，主人是 TA；只碰到角的：拒 |
| | `insideWithoutParentClaimIsRejected` | 槽位 31，只装区域、不建父领地（模拟 DEGRADED 时建的区、父领地被删）：整块在区里的 `createClaim` 被拒，提示是本节的键，不是 `flan.conflictOther` |
| | `threeDClaimAndOtherDimension` | 槽位 32：`setEditMode(ClaimMode.DEFAULT_3D)` 后在外围 y 100–120 圈 3D 领地：拒；区只装在 `minecraft:the_nether` 时，主世界的同一坐标：放 |
| | `goldenHoeClicksAreGuarded` | 槽位 33：`setEditingCorner(c1)` 后 `claimLandHandling(c2)` 落进外围：没有新领地、收到提示、已点的角被清掉；外面一块领地，`setEditClaim` 加上它的角，`claimLandHandling` 点到外围：范围不变；点到远离区的一侧：范围照改 |
| | `resizeRules` | 槽位 34：外面的领地 `resizeClaim` 扩进外围：false、范围不变、收到 `claim_resize_denied`；用 `FlanTestClaims.playerClaim`（先圈管理员领地再 `transferOwner`，不经 F1）放一块压着外围的老领地：缩小、远端外挪放行，沿外围加长拒；每次放行之后 Flan 的 `getDimensions()` 等于 `resizedBox` 算出的框 |
| | `flanCommandsAreGuarded` | 槽位 35：以 mock 玩家执行 `/flan add <外围里的两角>`、站在外围里 `/flan add rect 10 10`：都没有新领地；朝着区 `/flan expand 10`：范围不变；背着区：照常扩 |
| | `opExemptionMatchesTheCreateBan` | 槽位 36：2 级 OP 在外围 `createClaim`：true，收到 `claim_op_exempt`；1 级 OP：false；UUID 在 2 级 OP 名单里的 `FakePlayer`：false |
| | `adminClaimsSubclaimsAndSwitch` | 槽位 37：非 OP 调 `resizeClaim` 把父领地外扩 4 格：true（管理员领地不管，权限由 Flan 在 `claimLandHandling` 查）；外围老领地里 `tryCreateSubClaim`：成功；`useSettings(personalClaims = false)`：外围 `createClaim` 成功；`suspend()`（门面 OFF）：同样成功 |
| | `inconsistentEditIsRefusedSlot40`、`inconsistentEditIsRefusedSlot41`（22.22 补） | 两条用例（槽位 40、41）：编辑状态与登记不一致时 F2 一律拒，领地范围与登记不变；对照组照改。 |
| `FlanRealGameTests`（`district_flan_real`，加 2 条） | `personalClaimsAreListedNeverDeleted` | 槽位 38：外围里一块 2D、一块 3D，另一块从 `minX − 9` 起（在外面）：`/district personalclaims` 返回 2，列出这两块（主人、范围、3D 的 Y、离区边格数），外面那块不在；命令、一轮自动对账、`resync` 前后三块的 `toJson` 逐字相同；`DistrictFeature` 翻成 DEGRADED：回"领地对接未启用" |
| | `bindAndBoundsSyncReportBufferClaims` | 槽位 39：管理员领地的外围有一块个人领地：`bind` 预览有那一行，`confirm` 回显摘要 1，那块不变；用测试工具把父领地外扩 5 格，另一块也落进外围：`bounds sync` 回显摘要 2 |
| `GuardStatusGameTests`（加 3 条） | `flanClaimMixinsAreApplied` | 开发运行时有 Flan：两个系统属性恰好是 `applied`；`-PwithoutFlan` 那一轮改为断言状态是"未安装 Flan"、不记 ERROR |
| | `statusFlagsMissingFlanHooks` | 合成的集合缺 F2：状态列出 F2 与它覆盖什么 |
| | `flanHookTableMatchesTheMixinConfig` | `miningdim.district.flan.mixins.json` 恰好 2 个 mixin，与 `FlanHookTargets.HOOKS` 一一对应；`required = false`、`defaultRequire = 0`，注解里没有 `require` |
| `DistrictZoneIndexGameTests`（加 1 条） | `banHitAndNewBanColumns` | 新查询与逐格判定一致：框的每个边界值；两个区时报第一个命中的 |
| `DistrictCommandGameTests`（加 1 条） | `statusShowsPersonalClaimGuard` | `status` 有个人圈地一行、急停开关与放行次数；外围个人领地计数：记录型网关里造一块个人领地时为 1，Disabled 网关时显示"领地对接未启用，数不了" |

`DistrictLangGameTests` 自动覆盖新键。共新增 19 条。跑法同 22.14；另跑一轮删掉 `run/world` 的 `-PwithoutFlan`（20.10）：F1、F2 不应用，服务端照常起来，真 Flan 的用例在门口算通过。

#### 对前文的改动（实现时回写）

- **1.2**：已加"个人圈地限制 22.20"。
- **第二章**：资源加第三份 mixin 配置；`[district.guards]` 加 `personalClaims`。
- **第三章、22.1**：类布局加上面的类；mixin 配置表加第三行；`build.gradle` 两处；`module-registry.json`。
- **第十六章、20.8**：新命令 `/district personalclaims <districtId>`；`create`、`bind … confirm`、`claim … relink … confirm`、`bounds … sync` 的回显摘要；`bind`、`relink` 的预览；`status` 守卫一节。
- **20.1**：`FlanGateway` 加 `personalClaimsIntersecting`。
- **20.2**：Flan 配置那条 INFO 加 `permissionLevel < 2` 的 WARN。
- **22.7**：状态与 OP 红字加 Flan 一节。
- **22.13**：语言键加 `district.miningdim.guard.claim_create_denied`、`claim_resize_denied`、`claim_op_exempt`、`claim_incomplete_op` 与命令的键（中英成对，占位符写法照现有的 guard 键）；`DistrictLimits.BUFFER_BLOCKS` 的注释去掉"个人圈地的限制仍是 TODO"。
- **第十九章**：P32 关闭；新增 P40–P42（已改）。
- **第二十一章第 2 条**：挪到本节（已改）。
- **对接说明**：记下 Flan 1.11.16 没有建、改领地的事件；`resizeClaim` 的候选领地恒为非管理员领地；`claimLandHandling` 有 10 tick 的点击冷却。
- **模块文档**：`docs/modules/README.md` 的配置表、`RESOURCE_OWNERSHIP.md`（配置文件与键）、`INVENTORY.md`。
- **前端不改**：`PublicDirectory.tsx:33`、`AdminPanels.tsx:581`、`PermissionMatrix.tsx:254`、`PermissionSettings.tsx:466` 写的"自管区内和外围 8 格内不能圈个人领地"从此属实。`FIXED_RULES` 仍只有机械动力一条：个人圈地在界面上已写明四处，再加一条 fixedRule 要动契约数据与假后端，不值得。

实机核对：22.16 第 18 条，执行清单在 23.4 第 11–15 条。

### 22.21 实现记录：个人圈地限制（2026-09-30）

基线：设计提交 `bff23479`（22.20）。本节记实现时与 22.20 不同或 22.20 没写死的地方；上面"对前文的改动"一节列的回写已全部做完（1.2、第二章、第三章、16、20.1、20.2、20.8、22.1、22.7、22.13、对接说明 2.7、模块文档）。

落地的类与资源同 22.20 的类布局：`guard/PersonalClaimGuard`；`mixin/flan/FlanCreateClaimMixin`（F1）、`FlanResizeClaimMixin`（F2）与 `miningdim.district.flan.mixins.json`；`flan/PersonalClaim`、`flan/BufferClaimReport`；`flan/real/FlanHookTargets`、`FlanClaimGuard`；`DistrictZoneSnapshot` 加 `BanHit`、`banHit`、`newBanColumns`；`GuardSettings` 加 `personalClaims`（记录的第三个分量；`parse` 的签名不变，默认开）；`GuardView.personalClaimsActive()`；`GuardMixinStatus` 加 Flan 一节；`DistrictMixinPlugin` 加 `FLAN_MIXIN_PACKAGE` 与 `flanPresent()`；`FlanGateway.personalClaimsIntersecting` 与三个实现；`/district personalclaims`、摘要、预览与 `status` 各行；`DistrictSystem` 的 OP 上线红字；`FlanBridge` 的 `permissionLevel` WARN；`build.gradle` 两处、`module-registry.json`。

与 22.20 的差异：

1. **`status` 的排版。** 急停开关不另起一行，作为守卫那一行的第 7 项"个人圈地限制 开/关"（与 23.3 第 5 条"三个开关显示关"一致）；"运行时出错 N 次（已放行）"单独一行，功能绑定时总是显示（0 次也显示）；mixin 一节显示"个人圈地限制 2/2（Flan 1.20.1-1.11.16）"、"个人圈地限制不完整：缺 F?"（下面逐项列覆盖什么）或"未安装 Flan（没有个人领地可拦）"。外围个人领地的计数在 `status` 末尾（读库的那一段里）："外围个人领地：abydos 0、millennium 2"，领地对接没有生效时"数不了（原因）"。
2. **`/district personalclaims` 在领地对接没有生效时**沿用现有的 `flan_unavailable`（"领地对接没有生效，这条命令要用到 Flan（原因）"），不另加键。
3. **预览那一行与摘要一样，N 为 0 时不显示。** 预览的范围经 `DistrictAdminService.CommandResult` 新加的分量 `previewBounds` 带给命令层（三参数的构造器保留）。
4. **`claim … relink … confirm` 只加个人领地的摘要**：机械动力的摘要按 22.8 本来就不在 relink 上，不顺手加。
5. **签名表**：真网关读 `Claim.getOwner()` 与 `ClaimBox.maxY()`，加进 `FlanCompat.expectedSignatures()`。守卫画红框用的 `PlayerClaimData.addDisplayClaim(DisplayBox, …)`、`EnumDisplayType.CONFLICT` 不进签名表：画框是尽力而为，单独接住异常，对不上只少一个红框，不该让整个功能降级。
6. **`FlanTestClaims.playerClaim`** 就是已有的 `rawPlayerClaim`（先圈管理员领地再 `transferOwner`，不经 F1）；另加 `rawPlayerClaimAt`（给定高度、可 3D）与 `playerClaims`（某人名下的顶层领地）。`FlanRealScenarios.Console` 改为包内可见，给 `FlanClaimGuardScenarios` 共用。
7. **`/flan add <from> <to>` 要求两角所在的区块已加载**（`BlockPosArgument.getLoadedBlockPos`，对接说明 2.7）：槽位在 2,000,000 之外，用例先 `level.getChunk` 把两角的区块载进来（GameTest 世界是超平坦，代价很小），并各加一条"外面照常圈得出来"的对照（`add`、`add rect`），拒绝不会是因为命令根本没跑到 `createClaim`。`/flan expand` 被拒时命令返回 0（Flan 把 `resizeClaim` 的结果当返回值）。
8. **"没有 `flan.claimCreateSuccess`"不按键断言**：Flan 的提示经 lingua_bib 自己排版，不一定是可翻译组件；改为断言领地存储里确实没有新领地，并断言收到的是本节的键。
9. **第一轮全套跑出的用例排布错误**（不是代码问题）：`resizeRules` 里外面那块领地与老领地往外挪之后的范围重叠，Flan 以冲突拒绝了本该放行的挪动；把外面那块挪到 Z 1100 起之后通过。

GameTest：新增 19 条，与 22.20 的表相同（`PersonalClaimGuardGameTests` 4、`FlanClaimGuardGameTests` 8、`FlanRealGameTests` +2、`GuardStatusGameTests` +3、`DistrictZoneIndexGameTests` +1、`DistrictCommandGameTests` +1）；另在已有用例里补了两处：`DistrictCommandGameTests` 的权限门与"功能关着"两张命令名单加上 `personalclaims`，`DistrictLangGameTests` 核对 `PersonalClaimGuard.keys()` 都在语言文件里。自管区模块合计 184 个文件、309 条 GameTest（测试类 30 个）。

跑法与结果（每轮都先删掉 `run/world`，判断一律看日志里的汇总行）：
- `compileJava verifyModuleBoundaries runGameTestServer`：All 1914 required tests passed；开服日志"personal claim limit: Flan hooks 2/2 (Flan 1.20.1-1.11.16)"。
- 带 `-PcreateJar=<整合包的 create_consblocks.jar>`：All 1914 required tests passed，机械动力离线核对 19 个注入点、25 个处理方法 0 问题。
- `-PwithoutFlan`：All 1914 required tests passed；F1、F2 不应用，开服记 INFO"Flan is not installed"，真 Flan 的用例在门口算通过，没有 `NoClassDefFoundError`（守卫、命令与 `FlanHookTargets` 都不碰 Flan 的类）。

### 22.22 复核修正：金锄头的陈旧编辑（2026-09-30）

对 `a653bc73` 的复核提了两条，都对 Flan 1.11.16 的字节码核对过，都成立；细节已私下交给主负责人。

**改法：**
- F2 把被注入的 `ClaimStorage`（mixin 的 `this`，当 `Object` 传）也交给 `FlanClaimGuard.resizeDenied(storage, claim, from, to, player)`。
- 在急停与门面那道门之后、管理员领地与 OP 例外之前：F2 先核对要改的领地仍是动手的人所在世界里登记着、未删除的那一块（`FlanClaimGuard.staleEdit`），不是就拒。对谁都一样（P43）：这与禁圈区无关，所以不照 OP 例外。
- 拒绝时：
  - 聊天栏一行红字 `district.miningdim.guard.claim_stale_edit`（中英成对，进 `PersonalClaimGuard.keys()`），不画红框；
  - 日志一行 INFO：谁、哪块、为什么；
  - 记日志、发提示出错都单独接住，不会把拒绝变成放行。判定本身出错仍按 P42 放行并计数。
- 禁圈区的判定与红框改按领地自己的世界（`claim.getLevel()`）取。
- 签名表加 `Claim.getLevel()` → `ServerLevel`（20.2）。`Claim.isRemoved()`、`ClaimStorage.getFromUUID(UUID)` 本来就在表里。
- 急停关着、功能关着时不查（回到 Flan 原生的行为），与 22.20 的门面口径一致。

**文档回写：**
- 22.20 的路径表加一行"金锄头拖角时编辑状态与登记不一致"；
- "认人与例外"加一条"陈旧编辑不分人"；
- "拒绝时玩家看到什么"加新键；
- "缺了还剩什么"的 F2 一行补上陈旧编辑；
- "不进对账"那句前提补上这一条；
- 20.2 的签名表补上 `getLevel()`，顺手补上 22.21 第 5 条加进代码、却没写进表里的 `getOwner()` 与 `ClaimBox.maxY()`；
- 第十九章加 P43；22.16 第 18 条与 23.4 第 16 条加实机核对；对接说明 2.7 记下这一条。

**测试（2 条，`FlanClaimGuardGameTests`，batch `district_claim_guard`）：**
- 两条用例（槽位 40、41；`inconsistentEditIsRefusedSlot40`、`inconsistentEditIsRefusedSlot41`）：编辑状态与登记不一致时 F2 一律拒，领地范围与登记不变；对照组照改。
- 反证：把陈旧编辑的检查临时关掉再跑，这两条恰好失败；恢复后通过。
- 没有新文件：自管区模块合计 184 个文件、311 条 GameTest（测试类 30 个）。

跑法与结果（每轮都先删掉 `run/world`，判断一律看日志里的汇总行）：
- `compileJava verifyModuleBoundaries runGameTestServer`：All 1916 required tests passed（1914 加上本节的 2 条）；开服日志"personal claim limit: Flan hooks 2/2 (Flan 1.20.1-1.11.16)"。
- `-PwithoutFlan`：All 1916 required tests passed；开服记"Flan is not installed"，没有 `NoClassDefFoundError`（新代码只在 `flan/real` 与 `PersonalClaimGuard` 里，后者不碰 Flan 的类）。
- 没有带 `-PcreateJar` 重跑：本节没有碰机械动力的注入点。

---

## 二十三、阶段 4：测试服实机核对清单

本章是阶段 4 的执行清单：在测试服存档的**副本**上，把 20.12、22.16 的条目，连同聊天通知、备份、开关，按顺序跑一遍。每一条写明做什么、看什么、怎样算过。

- 结果回写 20.12、22.16 与对接说明 2.5：逐条写"通过"，或"不通过 + 现象"。
- 全部通过之后，服主才打开正式服的 `enabled`（P15）。

### 23.1 前提与范围

- **服务端**：`E:\Aurora\.minecraft\versions\1.20.1-forge-47.4.16\` 那一套整合包（约 95 个 mod，Forge 47.4.16），加上两样：
  - 服主批准的 Flan `1.20.1-1.11.16`。整合包里原本没有 Flan，阶段 4 之前要把它装进测试服的 `mods/`。没有 Flan 时模块只会降级（DEGRADED），23.4 起的大部分条目都跑不了。
  - 本模块的 `-all.jar`。
- **机械动力**就是整合包里 `[建筑方块]create_consblocks.jar` 那个 6.0.8。注入点只对它核对过（22.6、22.14 的离线核对）。它把机器方块登记在 `ignored_void` 下，生存模式拿不到（22.4 末尾），所以 23.6、23.7 的大部分条目在这个整合包上记 N/A，见两节开头。
- **开服前提**：2026-09-30 的本地冒烟测试查出的几条（`flavor_immersed_daily`、spark、ToadLib、Flan 的版本）列在 23.11，副本开服之前逐条对上。
- **身份**：AccessHub 0.5.3，离线模式。
  - 登录门（PR #72）合入之前，离线模式的服务器上通知一条都不发，留在队列里（P35，开服日志有一条 WARN）。所以 23.8 要在合入了登录门的构建上跑。
  - 合入之后，按 22.12 的合并清单改装，再补跑 23.8 第 12 条。
- **不在本章**：税收与钱仓。个人圈地限制（P32）已由 22.20 做完，实测在 23.4 第 11–15 条。

### 23.2 准备

1. **副本。**
   - 停测试服，把整个服务端目录复制到另一个目录：世界目录、`serverconfig/`、`config/`、`mods/`。世界目录里的 `miningdim.db` 与各维度的 `data/claims/` 一定要在副本里。
   - 在副本上开服，原服不动。副本只在本机开，不对玩家开放。
2. **构建。**
   - 在 `feat/wok-district`（或合并了它的测试分支）上执行 `gradlew jarJar`，取 `build/libs/miningdim-1.20.1-<版本>-all.jar`。必须是 jarJar 重混淆之后的 `-all.jar`。
   - 用它替换副本 `mods/` 里原来的 miningdim jar，原 jar 另存一份。
3. **数据库。**
   - 这一版带 V9（自管区 12 张表）。迁移是单向的：副本的 `miningdim.db` 升级之后，不能再给旧 jar 用。副本本来就是一次性的；要复用，先备份 `miningdim.db`。
   - 用阶段 1–2 的开发构建开过的存档会缺 `district_notice` 表：开服日志有 ERROR，功能降级为只读。先备份 `miningdim.db`，再照日志用 sqlite3 执行那两条补建语句，重启（22.11、22.19）。**绝不能删 `miningdim.db`**：它是统一库，所有模块的数据都在里面。
4. **配置。**
   - 副本的 `serverconfig/miningdim-district.toml` 先保持 `enabled = false`（23.3 第 1 条要看关闭时的样子），之后改成 `true`。
   - `[district.guards]` 的三个开关保持默认的 `true`。
   - `[district.createBan]` 的 `namespaces` 要是 `["create", "ignored_void"]`。文件是旧构建生成的（只有 `"create"`）就手工加上（22.1）。
5. **账号。** 至少准备这些人，每人先在 AccessHub 里 `/register`、`/login`：
   - 一个 2 级 OP；
   - 一个 1 级 OP（`ops.json` 里 level 改成 1），核对 1 级不绕过（P14）；
   - 三个普通玩家 A、B、C。C 从没进过这个存档，用来核对待生效住户与首次登录。
6. **工具。** spark（23.10 看开销）、`/district status`、Flan 的 `/flan info` 与查看木棍、F3 看坐标。

### 23.3 开服、开关与急停

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | `enabled = false` 开服 | 日志有 "district feature is off"；`/district status` 显示"关闭"，守卫一节是"没有装上（功能关闭）"，mixin 情况照常列出；平板导航没有自管区入口；各维度 `data/claims` 的修改时间不变 |
| 2 | 看开服日志 | 没有任何 mixin 的报错或 WARN，特别是 Architectury 对 `FallingBlockEntity.tick` 的局部捕获（22.1）与农夫乐事对 `DispenserBlock.dispenseFrom` 的注入；没有 `district_notice` 缺表的 ERROR |
| 3 | 改成 `enabled = true`，重启 | `/district status`：功能"生效"，Flan 1.20.1-1.11.16、网关 `FlanClaimGateway`、自检没有问题；守卫"已装上"；原版守卫 mixin 6/6；"机械动力防护 19/19，版本 6.0.8"；"个人圈地限制 2/2（Flan 1.20.1-1.11.16）"与各区的外围个人领地块数（22.20）；"算作机械动力的命名空间：create, ignored_void"；名单问题只有 22.4 末尾说的那 6 条"方块 create:… 不存在"；Flan 权限 66 个（20.3）。有在用自管区的维度出现 `…-start.AdminClaims.json.bak` |
| 4 | 2 级 OP 登录 | 防护完整时不收红字（防护不完整时的红字只能在缺注入点的构建上看，这里不单独做） |
| 5 | 急停：`crossPlot = false`、`createMachinery = false`、`personalClaims = false`，重启 | `status` 三个开关显示"关"；23.5 的活塞、水、发射器越界照常发生；23.7 的钻头、装置照常改区内方块；23.4 第 11 条的外围圈地照常成功；**23.6 的玩家放置禁令与 `denyUse` 照拦**（22.6 开头）。改回 `true` 重启，全部恢复（急停期间圈下的个人领地留着，`/district personalclaims` 列得出来） |
| 6 | 降级（可选）：把 Flan jar 暂时移出副本的 `mods/`，开服 | `status` 显示"降级（Flan 没有安装）"；平板 6 条读动作照常，写动作回"自管区的领地对接暂停……"；守卫照常装上（只依赖库）。放回 Flan 重启恢复 |

### 23.4 建区、绑定与 Flan 保护（20.12；个人圈地限制 22.16 第 18 条）

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | 看开服 INFO 里 Flan 配置的实际值 | 记下来，回写对接说明 2.5（20.12 第 1 条） |
| 2 | `/district create abydos <from> <to>`；或在已有的管理员领地上 `/district bind abydos <pos>`，看过预览再加 `confirm` | `/flan info` 看到全高，组与成员如 20.3；绑定时其上的子领地、组、成员全部删掉（P17） |
| 3 | 平板上划 3 块地；开放购买，A 买一块；A 加 B 为朋友；设几格三列开关 | 每块地是父领地下的子领地，组名各不相同；A、B、C、外人的实际能力（放、拆、开门、开箱子）与平板上一致 |
| 4 | 用金锄头把父领地扩大一圈，`/district bounds abydos sync`，存盘重启 | 地块都在，判定不变；外围 8 格跟着新范围移（23.6 第 1 条再核一次） |
| 5 | 把 A 以"长期不上线"移出 | A 的地块冻结。记下 `can_stay` 为假时 A 的实际表现：被挡在外面、被弹出去，还是传送进来会怎样 |
| 6 | 2 级 OP、1 级 OP 分别进出冻结地块与别人的地块，并在里面操作 | 2 级能；1 级不绕过（P14） |
| 7 | C 从没进过服时被加为住户，然后 C 第一次登录 | 离线模式 UUID 的成员拿到正确的权限（20.12 第 6 条）；C 收到"你已加入……"（23.8 第 3 条） |
| 8 | 别的 mod 的假玩家（整合包里有的机器，不含机械动力）在地块里干活 | 按 20.3、P31 的口径，记下实际表现 |
| 9 | `/reload`；`/flan reload` 之后再建一个区 | 权限表被重读，对账把新增的权限写上；`/flan reload` 之后建的区仍然没有默认组 |
| 10 | `/save-off` 期间建区，再 `/save-on` | 期间不显式存盘；`/save-on` 之后一切正常（20.5） |
| 11 | 个人圈地（22.20）：非 OP 用金锄头，两下都点在外围里、一下在外面一下在外围里、3D 模式在外围半空各圈一次；站在区外 `/flan add` 两角都在区里；再在 `minX − 9` 以外、下界的同一坐标各圈一次 | 前四次都没圈成：聊天栏红字"自管区「…」和它外围 8 格内不能圈个人领地"，红框标出禁圈区（记下大区上红框的样子）；Flan 的圈地格数没少。后两次照常。（金锄头直接点进父领地里时 Flan 自己先以"没有 `EDITCLAIM`"拦下，走不到本节，不算不通过） |
| 12 | 非 OP 在外面有一块领地：用金锄头把靠区的角往区里拖、朝着区 `/flan expand 10`；再往外拖、背着区 expand；站在外围里 `/flan add rect 10 10`，`/flan add` 两角落在外围里 | 往区里的都被拒（红字"不能把领地扩进……"），范围不变；往外的照常；两条 `/flan add` 都没圈成 |
| 13 | 2 级 OP、1 级 OP 分别在外围用金锄头圈个人领地；2 级 OP 用金锄头把父领地外扩一圈（20.6 的流程） | 2 级照常并收到金色提示（会列在 `/district personalclaims` 里）；1 级被拒；改父领地照常、没有提示 |
| 14 | 绑定之前（那片地还没有禁圈区），让一个非 OP 在一块管理员领地外 8 格内圈好一块个人领地；然后 `bind` 预览、`confirm`；`/district personalclaims <id>` | 预览与回显都有块数；清单列出主人、范围、离区边格数；那块领地原样（`/flan info` 前后一致）。它的主人缩小、把远端往外挪照常，往外围里扩被拒 |
| 15 | 开服日志与 `status` | 没有 `miningdim.district.flan.mixins.json` 的 WARN 或 ERROR；"个人圈地限制 2/2（Flan 1.20.1-1.11.16）"；Flan 的 `permissionLevel` 为 2（低于 2 时有 WARN，照 22.20 先改回 2） |
| 16 | 金锄头的陈旧编辑（22.22）：按私下交接的核对步骤做 | 红字"这次改范围没有生效……"，日志有一行 `refused a stale claim edit`；存盘重启后那块领地范围不变，`/district personalclaims` 没有它；领地格数不变 |

### 23.5 地块边界守卫（22.9；22.16 第 13 条）

在同一个区里取两块相邻的地 P、Q（中间隔着公共区域或直接相邻），逐条做：

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | P 里的活塞往 Q 推方块；P 边上的活塞朝公共区域伸出；推动路径的尽头在 Q 里有一支火把 | 活塞不伸出，方块不动，火把还在 |
| 2 | 粘性活塞在 P 里粘着一格，把那一格划进 Q，断电 | 活塞缩回，方块留在原地；记下客户端有没有幽灵方块、多久被纠正（22.9 的已知取舍） |
| 3 | 野外的活塞往区里推；区里的活塞往野外推 | 都不动（严格口径） |
| 4 | P 里离边界 2 格放水源、岩浆源；公共区域贴着区边放水源 | P 里铺开、往下流，到边界停住，Q 里没有；贴着区边的水照样流到区外（外沿交给 Flan 的 `liquid_border`） |
| 5 | P 里的发射器朝 Q 倒水桶、放潜影盒、用打火石、发 TNT；投掷器朝 Q 里的箱子投 | 咔哒一声，物品不消耗，Q 里没有变化；朝区外发射照常 |
| 6 | 用 TNT 大炮或活塞把沙子、铁砧横着打过 P/Q 边界 | 进了 Q 的那一列就掉成物品，不落成方块 |
| 7 | P 里放海绵，边界两侧都有水 | Q 的水还在 |
| 8 | P 边上有岩浆（随机刻点火），Q 里有可燃物 | Q 里不着火；一时看不到就记"未观察到" |
| 9 | 在同一块地里把 1–7 再做一遍 | 全部照常 |

### 23.6 机械动力：放置禁令与已有的机器（22.5、22.8）

**在这个整合包上（22.4 末尾）**：非 OP 在生存里拿不到机器方块，本节大多记 N/A。
- 第 1 条改成由 OP 给一个非 OP 一根 `ignored_void:shaft` 再放，用来核对 `namespaces` 的新默认值。
- 第 3、4 条照做，第 4 条用 `ignored_void` 下的机器。
- 第 6–8 条只在 OP 先摆好机器时做。
- 第 2、5、9 条记 N/A。
- 第 7 条的"潜行 + 扳手"也记 N/A：整合包的扳手是 `ignored_items:wrench`，不是 22.8 认的 `create:wrench`。

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | 非 OP 在区内、`minX − 8`、`minX − 9` 各放一根传动杆；在另一个维度的同一坐标也放一根 | 前两处被拒：方块恢复、物品数不变、动作栏提示（区内与外围各一句）；后两处照常 |
| 2 | 非 OP 用放置助手连着放传动杆、齿轮；用对称之杖放 | 落进禁放区的每一格都被拒 |
| 3 | 非 OP 放装饰方块：外壳、大梁、石材与玻璃调色板、`copycat_step`、推拉门 | 照常 |
| 4 | 2 级 OP、1 级 OP 分别在区内放机械动力的机器 | 2 级照常；1 级按非 OP 拦 |
| 5 | 非 OP 从区外把轨道弯道、长直道铺进外围 8 格；OP 同样做 | 非 OP 被拒、不扣轨道，记下提示与客户端预览是什么样；OP 照常 |
| 6 | 绑定之前先在那片地上放几台机器（传动杆、鼓风机、钻头），再绑定；然后 `/district machines abydos` | 绑定的回显末尾有"区内及外围 8 格的已加载区块里有 N 个机械动力方块"；`machines` 列出计数、坐标与没加载的区块数 |
| 7 | 对已有的机器：非 OP 右键、不潜行拿扳手右键、潜行 + 扳手拆、徒手拆；OP 右键 | 右键与不潜行的扳手被拒（动作栏提示）；潜行 + 扳手拆下（受 Flan 的"破坏方块"约束）；徒手拆照常；OP 照常；传动照转 |
| 8 | 已有机器的数值框（调转速、过滤槽） | 记下实际表现；拦不住的回写 22.8 再定 |
| 9 | 复核补上的（C15–C18，22.19）：非 OP 从外面把传送带连进外围 8 格与区里；OP 同样做；对称之杖从地块 A 镜像拆、放到地块 B 与公共区域；机械臂从外面够区内的容器；显示链接从外面改区内的告示牌；链式传送带接到区内 | 非 OP 的传送带被拒（动作栏提示、不扣物品），OP 照常；镜像到别的区域的那一侧不拆不放（A 里的照常）；机械臂那个交互点不起作用；告示牌不变；链式传送带记下实际表现 |

### 23.7 机械动力：机器与装置改不了区内的方块（22.6；22.16 第 3–10、17 条）

每条都在区边外摆好，让机器朝区里干活。

**在这个整合包上（22.4 末尾）**：这些机器、装置、蓝图炮、软管滑轮、机械手、土豆加农炮都登记在 `ignored_void` 或 `ignored_items` 下，生存里拿不到。
- 第 1–7、10 条默认记 N/A。要核对注入点，就由 OP 在区外摆好再看：OP 放的机器同样改不了区内的方块（P25）。
- 第 8 条记 N/A（见表内）。
- 第 9 条与机械动力无关，照做。

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | 固定的钻头、锯贴着区边朝里转；锯伐一棵跨界的树 | 区内方块不被拆，也没有无尽的裂纹；区外那半棵树照常伐 |
| 2 | 龙门、机械活塞、绳索滑轮带着钻头往区里走；区块没加载时再走一次 | 停在边界外；记下区块没加载时的表现 |
| 3 | 轴承带长臂转进区里再停下拆装；底盘、强力胶够到区内方块时组装 | 区内方块完好，掉出来的是装置自己的方块；组装失败并显示原因 |
| 4 | 收割机、犁、压路机的装置沿着区边走（含火车上的压路机） | 区内作物不被重置，不耕地，不铺路 |
| 5 | 机械手（固定的与装置上的；住户自己的与 OP 的）对区内（含自家地块）拆、放、用物品、倒桶、攻击；装置上的蓝图打印进区里 | 全部被拒 |
| 6 | 蓝图炮在 50 格外打印一座跨界的建筑 | 区内的格子跳过、不耗火药；外围 8 格里的机器方块跳过 |
| 7 | 软管滑轮抽一片跨界的湖，再往区里灌；区内已有的开口管道 | 区内的水不动；灌不进区；区内的开口管道不放、不吸 |
| 8 | 火车带钻头穿过区（OP 铺的轨道）；矿车装置开进区 | **整合包上 N/A**：Flan 没有登记 `create_contraption`（20.3，权限 66 个），矿车装置也拿不到。装官方机械动力时：不拆；矿车装置被 Flan 的 `create_contraption` 拦在外面 |
| 9 | 别的 mod 里直接改方块的物品（P38，清单私下交接） | 打开 `enabled` 之前这些已经在整合包里处理好（删掉配方或只给 OP）：非 OP 拿不到、用不了；OP 用时记下实际表现 |
| 10 | 蓝图炮在外围之外打印一座带长传送带（伸进区里）的建筑；土豆加农炮往区里打南瓜、西瓜，往区里的耕地打土豆 | 传送带整条不铺，区内方块完好（C15）；区里不出现南瓜、西瓜、作物，射弹掉成物品（C19） |

### 23.8 聊天通知（22.10–22.12）

**本节在合入了登录门（PR #72）、按 22.12 换了 gate 的构建上跑**：本分支的构建在离线模式的测试服上一条通知都不发（P35），只能核对第 12 条的"不发"。

用 A（户主）、B（A 的朋友）、C（从没进过服）与 2 级 OP。第 1–8 条都做两遍：
- 收件人**在线**时做一遍：操作完成后，收件人立刻在聊天栏（不是动作栏）收到一条；
- 收件人**离线**时再做一遍：下次登录、`/login` 之后，先收到灰色的头一行"你不在线期间有 N 条自管区消息："，后面按发生顺序列出。

| # | 做什么（平板或 `/district` 命令都行） | 谁收到什么 |
|---|---|---|
| 1 | OP 把 A 加为住户；再把 A 移出，四种原因各一次（每次移出之后再加回） | A 收到"你已加入阿拜多斯学院……"；移出按原因种类各一句，**绝不出现填写的原因原文**；违反区规那句提到朋友身份已暂停 |
| 2 | A 有地时被移出；再加回；OP 解冻；另取一块冻结的地"立即收回" | A 依次收到：冻结 7 天、"原来的地块还在冻结中"、已解除冻结、被管理员收回。到期收回要等 7 天，实机不等，只看 GameTest（`freezeUnfreezeReclaim`） |
| 3 | C 从没进过服时被加为住户，然后 C 第一次登录；再用一个大小写不同的名字试一次 | C 登录、`/login` 之后收到"你已加入……" |
| 4 | OP 任命 A 为区务长，再换成 B，再撤销 | A 收到任命、撤销；B 收到任命、撤销；换人时 A 的撤销在 B 的任命之前 |
| 5 | 开放购买之后 A 买地 | A 收到"你买下了地块 阿拜多斯-0N，花费 …… 信用点" |
| 6 | OP 代 A 改开关、恢复默认、加 / 移 / 恢复朋友 B、调 A 的地块范围 | A 每次收到一条"管理员 XX 代你……"，恢复默认只一条；B 收到成为朋友、不再是朋友、朋友身份已恢复 |
| 7 | A 自己加、移、恢复 B | 只有 B 收到；A 什么都不收 |
| 8 | B 是 A 的地块的朋友，OP 以"违反区规"移出 B | A 收到"你的地块 …… 的朋友 B 因违反区规被移出本区……"；B 收到自己的移出通知 |
| 9 | OP 开放购买（关 → 开）；10 分钟内关了再开；过了 10 分钟再开 | 在线、没有地块、不是冻结地块原户主的住户各收一次；10 分钟内不重复；离线的不补发（P28、P33） |
| 10 | OP 把自己加进名单、任命自己 | OP 自己不收 |
| 11 | 离线期间攒超过 30 条（例如 OP 反复代改开关） | 上线只收到最新的 30 条 |
| 12 | 登录时序（AccessHub）：离线期间攒几条；进服之后**先不** `/login`，停一会儿再 `/login` | 本分支的构建（没有登录门）：进服、`/login` 之后都一条不收，行留在队列里，开服日志有"通知被扣着"的 WARN（P35）。合入登录门之后：`/login` 之前一条都不收，`/login` 之后才收到。合入之后必须重跑这一条 |
| 13 | 用英文客户端看一次 | 英文句子、中文参数（地块标签、金额），可以接受 |

通过标准：
- 每条的收件人、句子、颜色都对：有利或中性的青色，不利或要处理的金色，头一行灰色；
- 不重复：再登录一次不重发；
- 不漏发；
- 服务器日志没有 `district notices` 的 ERROR。

### 23.9 备份、恢复与落盘（20.5；20.12 第 10、12 条）

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | 建区、绑定、`/district resync` 之后看备份目录 | `<世界>/miningdim/district-flan-backups/<维度>/` 下有对应原因的 `.AdminClaims.json.bak`，不在任何 `data/claims` 里；`/district status` 列出目录与最新文件 |
| 2 | 恢复演练：停服，把一份备份复制成 `<维度存储目录>/data/claims/!AdminClaims.json`，开服 | 世界能打开；开服对账把各自管区补齐到库里的状态。备份之后别人新建、改过的其他管理员领地会丢，恢复前先看文件时间 |
| 3 | 把备份目录设成只读（或让别的程序占着一个旧备份）再建区 | 建区报"领地备份失败，本次没有写入领地"，领地不动；旧文件删不掉只记一条节流的 WARN |
| 4 | 停服，备份 `miningdim.db`；开服做几件事，停服；用备份覆盖回去再开服 | 开服对账按旧库把领地改回去；排着的通知回到旧库里的样子：可能重发，不会丢（至少送达一次） |

### 23.10 性能与收尾

| # | 做什么 | 看什么 / 怎样算过 |
|---|---|---|
| 1 | 一台大型采矿装置贴着区边跑几分钟，用 spark 采样 | `isActorActive`、`canSpreadTo`、活塞 `resolve` 在服务器线程采样里的占比可以忽略（低于 1%） |
| 2 | 大区的对账 | `/district status` 显示上一轮的耗时与切片数；每 tick 不超过 2 ms |
| 3 | 回写 | 结果逐条写进 20.12、22.16 与对接说明 2.5；不通过的，改代码或开 TODO，然后重跑相关的那几节 |
| 4 | 打开正式服之前（硬性前提，缺一条都不开） | P32 已由 22.20 做完，并通过 23.4 第 11–15 条；**登录门（PR #72）已合入、按 22.12 换了 gate，并在测试服重跑过 23.8 第 12 条**（P35）；**整合包里别的直接改方块的 mod 物品已经处理好（删掉配方或只给 OP）**（P38，23.7 第 9 条）；23.11 的开服前提在正式服与玩家客户端上都已对上；正式服的 `namespaces` 含 `ignored_void`（22.1）；正式服先备份 `miningdim.db`；然后服主把正式服的 `enabled` 改成 `true` 并重启（P15） |
| 5 | 以后换整合包里的机械动力 jar 之前 | 先带 `-PcreateJar=<新 jar>` 跑一轮 GameTest（22.14）：两条离线核对都是 0 个问题才换。换完开服看 `/district status` 是否 19/19；注入点对不上时服务端照样起来（22.7），但对应的拦截不在，要当事故处理 |

回退：
- 副本上出问题（世界打不开、领地错乱、mixin 让服务器崩）：停副本，留日志。正式服不受影响。
- 正式服打开之后才出问题：把 `enabled` 改成 `false` 重启，模块就完全停用（不碰 Flan、不装守卫；原版 mixin 仍在，但门面是 OFF，什么都不拦）。领地按 20.5 用开服备份恢复。

### 23.11 冒烟测试结论（2026-09-30）

2026-09-30 在本机用整合包起了一个专用服（只在本机，不对玩家开放），经 RCON 以控制台身份走了一遍 OP 的建区流程。
- 构建：`test/district-integration` 的 `-all.jar`，即 `feat/wok-district` 加上登录门（PR #72）与 gate 的接线。
- 服务端：Forge 47.4.16，整合包加 Flan 1.20.1-1.11.16 与 spark。
- 这不是 23.3 起的实机核对，只确认这一版能在整合包上起来，命令路径走得通。
- 日志与 RCON 记录留在本机，没有进仓库。

**结果：开服一次成功，走过的命令都符合预期，停服正常。**
- `/district status`：
  - 功能生效，Flan 1.20.1-1.11.16，网关 `FlanClaimGateway`；
  - 原版守卫 6/6，机械动力防护 19/19（版本 6.0.8），个人圈地限制 2/2；
  - Flan 权限 66 个（不是 67 个，见 20.3）；
  - 6 条名单问题，全是 22.4 末尾说的"方块 create:… 不存在"。
- 走过的命令：
  - `create`，以及之后的 `list`、`info`、`inspect`；
  - 住户：`add` 一个没有登录记录的名字被拒；`addUnseen` 加三个待生效住户，重复加被拒；
  - 区规两条；区务长：任命、换人，任命非住户被拒；
  - `machines`、`personalclaims`、`plots`、`resync`（1 成功、0 失败、0 改回）、`sweep`、`bounds … sync`；
  - `flan listAdminClaims` 列出父领地。
- RCON `stop` 正常退出，没有崩溃报告。备份目录里有 `create` 与 `resync` 两份，都不在 `data/claims` 里。

**开服前提**（带当前 miningdim 的任何构建都要，不只是自管区）：
1. **`flavor_immersed_daily` ≥ 1.1.0.3，服务端与玩家客户端都要。** miningdim 的 `mods.toml` 从 `43c8ac1b` 起声明了对它的可选依赖 `[1.1.0.3,)`，`origin/main` 上已经有。装着的版本低于这个范围时，FML 拒绝加载 miningdim。整合包里是 1.0.6.9，所以要升级整合包，服务端与客户端一起升。
2. **服务端装 spark。** 整合包的服务端开服要用到它；冒烟测试用的是 spark 1.10.53。
3. **服务端的 `mods/` 里拿掉 ToadLib。** 它只能在客户端加载，专用服加载它会失败。整合包里没有别的 mod 依赖它。
4. **Flan `1.20.1-1.11.16-forge`**，就是服主批准的那个 jar（20.1）。整合包里原本没有 Flan。

**还没观察到，留给实机核对：**
- **建区那一次显式存盘，父领地还没带居民组。** 建区时写出的 `!AdminClaims.json` 里有父领地，但还没有本区居民组 `d_<districtId>_resident`；到下一次存档（`save-all`）才落盘。之后的存档与 `resync`、`inspect`（0 处差异）都对。要补看的是：建完区、下一次存档之前停服，重启后开服对账能不能把居民组补上（23.9）。
- **带着在用自管区重启。** 这次建区之后没有再开服，所以开服对账与 `start` 备份（`…-start.AdminClaims.json.bak`）都没有观察到。23.3 第 3 条与 23.9 第 1 条要看。
- **地块。** 没有控制台建地块的路径：`plot.create` 是平板动作，要一个 `ServerPlayer`，这次没测。从 23.4 第 3 条起用真玩家做。
- **要真玩家的部分。** 聊天通知、真登录（AccessHub 的 `/login`）、守卫的实际拦截都要真玩家，见 23.4–23.8。
- **命名空间。** 冒烟测试那份 `miningdim-district.toml` 是旧默认值生成的，只有 `"create"`。副本开服前按 23.2 第 4 条加上 `ignored_void`，开服后看 `status` 的命名空间一行。

**随本节提交的改动：**
- `[district.createBan] namespaces` 的默认值加上 `ignored_void`（22.1、22.4 末尾）。
- `/district status` 的守卫一节加"算作机械动力的命名空间"一行；开服日志的 `district guards installed` 改为列出命名空间。
- `CreateBlockPolicy` 加一个可以另给注册名的构造器，只给 GameTest 用；新增 `ignoredVoidBlocksAreMachinesByDefault`（22.14）。
- 20.3 的权限数、23.6 与 23.7 的 N/A、P44。
