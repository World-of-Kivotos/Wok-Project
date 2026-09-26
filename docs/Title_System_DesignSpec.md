# 称号系统 设计规格文档

## 文档元信息

- 用途：称号系统（`wok-title`）实现阶段的架构与机制参考。与代码不符时以代码为准并回写本文档。
- 目标平台：Minecraft 1.20.1 + Forge 47.x + Java 17。API 名称以此版本为准。
- 状态图例：DECIDED 已定稿 / PENDING 待拍板或待标定数值 / TODO 实现期补全。
- 姊妹文档：[Achievement_System_DesignSpec](Achievement_System_DesignSpec.md)。成就是称号的第一个发放方，但称号系统**不依赖**成就系统。
- 部署规模（2026-09-26 口径）：在线峰值约 50 人、上限 100 人以内，历史玩家不足一千；将来可能改为多子服架构。

---

## 一、系统定位 (DECIDED)

1. 称号是**纯外观**的身份标识，不提供任何战斗力或经济收益，不可交易、不可转让。
2. 玩家可拥有多个称号，**同一时刻只佩戴一个**，也可以不佩戴。
3. 称号显示在三处：**聊天前缀、Tab 列表、头顶名牌**（第五章）。
4. 称号系统是独立的底层模块，对外只暴露发放、回收、佩戴接口。成就、成就点商店、婚姻、活动、管理员都是它的**调用方**。
5. 不是每个成就都附带称号；称号从哪里来由调用方决定，称号系统只记录来源。

---

## 二、模块边界 (DECIDED)

| 项 | 值 |
|---|---|
| 模块 ID | `wok-title` |
| 分类 | `foundation`（让任意玩法模块都能依赖它，而不产生循环依赖） |
| Java 包 | `com.miningdim.title`（服务端）、`com.miningdim.client.title`（名牌渲染） |
| 依赖 | `wok-core`、`wok-store`；P2 注册 `title.*` WebUI 动作时再加 `wok-webui`（登记表只写真实引用到的模块） |
| 资源 | `data/miningdim/titles`、`assets/miningdim/lang` 中以 `title.miningdim.` 开头的键（原版已有 `title.singleplayer` 等 `title.*` 键，不能用裸 `title.` 前缀） |
| 对外入口 | `com.miningdim.title.ITitleService`，由 `TitleServices.titleService()` 取得 |

**依赖方向硬约束**：`wok-title` 不得引用任何玩法模块（成就、婚姻、职业……）。
需要"某件事发生后发称号"的，一律由那个玩法模块调用 `ITitleService.grant`。
违反这条会被 `gradlew verifyModuleBoundaries` 拦下。

`wok-app` 的 `dependencies` 需加入 `wok-title`；`MiningDim.registerSubsystems()` 中
`TitleSystem` 须排在 `WebUiServerSubsystem` 之后、任何称号调用方之前。

---

## 三、称号定义（数据包）(DECIDED)

路径：`data/miningdim/titles/<id>.json`，由 `SimpleJsonResourceReloadListener` 加载，
在 `AddReloadListenerEvent` 注册，支持 `/reload` 热重载。

```json
{
  "text": { "translate": "title.miningdim.mining.deep_diver" },
  "rarity": "diamond",
  "style": { "gradient": ["#6FF2FF", "#7FB0FF", "#D59CFF"], "bold": true },
  "description": { "translate": "title.miningdim.mining.deep_diver.desc" },
  "sort": 400
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `text` | 是 | 称号文字，Component JSON；一律用 `translate`，键名带 `title.` 前缀 |
| `rarity` | 是 | 七档之一：`bronze` `silver` `gold` `platinum` `diamond` `master` `legend`，与成就档位共用色板 |
| `style` | 否 | 覆盖稀有度的默认颜色：`color`（单色）或 `gradient`（2~3 个色标）加 `bold`。婚姻、活动这类特殊称号使用 |
| `description` | 否 | 获取说明，显示在"我的称号"页签 |
| `sort` | 否 | 排序权重，越大越靠前；缺省取稀有度的默认值（铜 100、银 200 …… 传说 700） |

- 称号 id 即资源路径，例如 `miningdim:mining/deep_diver`。
- 定义被删除后，玩家持有记录**保留不删**，但在显示和列表中跳过；定义恢复后自动复原。
- 加载时校验：缺少 `text`、未知 `rarity`、渐变色标不足 2 个或多于 3 个、颜色格式非法（只收 `#RRGGBB`）、`color` 与 `gradient` 同时出现，一律跳过该条并在日志告警，不影响其余定义。
- 首批 14 个称号（成就文档 9.8）按页签放在 `data/miningdim/titles/<页签>/<名称>.json`。三个来自隐藏成就的称号（鹰眼、欧皇、大人的卡），说明文案写"由一项隐藏成就获得"，不在"我的称号"里提前泄露隐藏成就的名字。

### 3.1 七档色板 (DECIDED)

与成就档位共用。前三档单色，白金起为逐字渐变加粗体，档位越高越华丽。

| 稀有度 | 游戏内颜色 | 粗体 | 16 色降级 |
|---|---|---|---|
| 铜 bronze | `#C8834A` | 否 | `§6` |
| 银 silver | `#D0D7DE` | 否 | `§7` |
| 金 gold | `#FFCC33` | 否 | `§e` |
| 白金 platinum | 渐变 `#FFFFFF → #FFF1C9 → #FFD66B` | 是 | `§f§l` |
| 钻石 diamond | 渐变 `#6FF2FF → #7FB0FF → #D59CFF` | 是 | `§b§l` |
| 大师 master | 渐变 `#A55CFF → #E05CFF → #FF5CB8` | 是 | `§d§l` |
| 传说 legend | 渐变 `#FF3D3D → #FF8A1F → #FFD23F` | 是 | `§c§l` |

- 渐变的实现方式：服务端把称号文字按码点逐字拆成多个带 RGB 颜色的 Component 片段（方括号一起参与渐变，首尾字恰为首尾色标）。原版支持，聊天、Tab 和 L 键界面都能显示。
- **渐变档的文字由服务端解析**：逐字上色必须先拿到纯文本，而专用服务端不加载 `assets/` 下的语言文件。解析顺序为 `Language.getInstance()`（集成服务端即房主客户端的语言）→ 模组自带的 `zh_cn` 语言表（本服玩家群体的语言）→ translate 的 `fallback` → 键名。代价是英文客户端看到的渐变称号也是中文。单色档不需要拆字，保留 translate，仍由各客户端按自己的语言显示。
- G 面板的浅色主题下，主色对比度不足，因此每档另外定义一组加深色，放在前端的 CSS 变量 `--tier-*` 里，在深色和浅色主题之间切换（TODO：实现时标定具体色值）。
- 色板在 Java 侧只有一份真源：枚举 `com.miningdim.title.TierPalette`（`BRONZE` … `LEGEND`，含色标、粗体、16 色降级码、默认排序，以及逐字渐变 `gradient(...)` 与只能整段上色时用的 `baseStyle()`）。成就模块直接引用它，不得各自复制色值。

---

## 四、存储 (DECIDED)

复用 `wok-store` 的世界级 SQLite，在 `MiningSchema` 末尾**追加**迁移（已发布的迁移不得修改）。

```sql
CREATE TABLE title_owned (
  player_uuid TEXT NOT NULL,
  title_id    TEXT NOT NULL,
  source      TEXT NOT NULL,   -- achievement / point_shop / marriage / event / admin
  source_ref  TEXT,            -- 例如成就 id、商品 id
  granted_at  INTEGER NOT NULL,
  PRIMARY KEY (player_uuid, title_id)
);
CREATE TABLE title_equipped (
  player_uuid TEXT PRIMARY KEY,
  title_id    TEXT NOT NULL
);
```

- 已落地为 `MiningSchema` 的 **V5**。成就系统也要在 `MiningSchema` 末尾追加迁移，两条分支谁后合并谁把自己的版本号顺延（例如成就改为 V6），已发布的版本号不得改动。
- 数据库访问全部经过 `TitleRepository` 接口，当前实现为 `SqliteTitleRepository`。以后改为多子服时，新增共享数据库（如 MySQL）的实现即可，业务代码不改。
- **内存缓存只保存在线玩家**：登录时加载，登出时移出。按当前规模，读写在主线程同步完成即可，不引入异步线程。缓存写穿（先落库再改缓存），停服或崩溃不丢数据，登出也无需回写。
- `grant` 按主键幂等：重复发放同一称号不会报错，也不会覆盖最早的来源。没有加载定义的称号 id 拒发（`UNKNOWN_TITLE`），防止拼错的 id 落库。
- 调用方需要把"扣成就点"和"发称号"放进同一个事务时，`ITitleService` 提供 `grantInTransaction(Connection, ...)` 这一变体。成就点商店购买称号就是这种情况。
  - 传入的连接必须已关闭自动提交，否则直接抛 `IllegalStateException`（没开事务就谈不上"同一个事务"）；传 null 抛 `IllegalArgumentException`。这项校验先于其他一切检查（包括称号定义是否存在），接线错误不会被 `UNKNOWN_TITLE` 这种正常拒发结果盖过去。
  - 它不提交、不回滚，也不发聊天提示；事务提交后如需提示，调用方再调 `notifyGranted`。
  - 它不往缓存里加称号（外层可能回滚），而是把该玩家的持有集合标记为待重载，下次读取时从库里重新加载。若这次读取发生在外层事务尚未提交时（例如商店在同一事务里查"是否已拥有"），结果只返回、不写回缓存。
- **只有 `grantInTransaction` 可以并入调用方的事务。**`grant`（两个重载）、`revoke`、`equip` 自带写穿缓存、聊天提示和显示刷新，这些都立即生效、无法随外层回滚撤销；共享连接上正开着事务时，它们直接抛 `IllegalStateException`。调用方在事务里用 `grantInTransaction`，提交后再 `notifyGranted` / `equip` / `revoke`（例如"买下即佩戴"要在提交之后调 `equip`）。

---

## 五、三处显示 (DECIDED)

显示用的 Component 统一由 `TitleRenderer` 生成并按称号 id 缓存：聊天和 Tab 用 `prefix(titleId)`，格式为 `[称号] `；名牌单独一行居中，用不带尾随空格的 `badge(titleId)`（`[称号]`），两者样式完全相同。

| 位置 | 实现 | 刷新时机 |
|---|---|---|
| 聊天前缀 | Forge `PlayerEvent.NameFormat`，把前缀拼到显示名前面；集成服务端里客户端侧玩家也会触发此事件，必须按 `isClientSide` 挡掉 | 佩戴变化时调用 `player.refreshDisplayName()` |
| Tab 列表 | Forge `PlayerEvent.TabListNameFormat`；事件默认值为 null，有称号时自己补回队伍格式化再拼前缀 | 佩戴变化时调用 `player.refreshTabListName()`，原版在结果变化时自动广播；重生时重算一次；队伍变化由每秒一次的兜底重算追上（见下方） |
| 头顶名牌 | 客户端 `RenderNameTagEvent`，在名字（及名字下方计分项）**上方单独一行**渲染称号，原版名字照常渲染 | 见下方同步协议 |

- 登录时要补一次刷新：`PlayerList.placeNewPlayer` 在触发登录事件之前就已广播过 Tab 条目、其他玩家也已开始追踪新玩家，那时缓存还没加载。
- `/reload` 后前缀缓存随定义换代作废，在线且佩戴着称号的玩家三处显示全部刷新一遍。
- 重生（含从末地返回）时 `PlayerList.respawn` 换了一个新的 `ServerPlayer`，它的 Tab 名字段是 null 且未初始化，而各客户端 Tab 里仍显示旧实体广播过的带称号名字。`refreshTabListName()` 只在新旧值不同时广播，若不处理，重生后卸下称号会比较 null 与 null、不发包，旧称号一直挂在所有人的 Tab 里。所以 `PlayerRespawnEvent` 里先对新实体调一次 `refreshTabListName()`，把字段对齐为当前应有的名字。
- 队伍兜底：有称号时 Tab 名里烘焙了队伍格式化，客户端就不再自己套队伍样式；而原版加入、离开、修改队伍都不会触发 Tab 名重算。`TitleSystem` 在服务端 tick 里每 20 tick 对佩戴着称号的在线玩家调一次 `refreshTabListName()`，只读缓存、不查库，结果没变时不发包，所以队伍变化最多延迟 1 秒体现在 Tab 里。未佩戴称号的玩家 Tab 名为 null，队伍样式照旧由原版客户端处理。
- `TitleSystem` 的全部钩子（显示、登录、登出、重生、重载、队伍兜底）都经 `TitleServices` 取当前注入的门面，本类不另持引用；生命周期方法只在注入的是本模块的 `TitleService` 时执行。GameTest 因此可以注入临时库上的实现，经真实事件验证这些钩子。
- 名牌行的可见性与原版名字严格同步：逐条复刻 `LivingEntityRenderer#shouldShowName`（距离、隐身、队伍名牌规则、F1、镜头实体、载具），因此本地玩家永远看不到自己的称号行；潜行时不透墙。

- **聊天前缀**：1.20.1 的聊天发送者名取自 `getDisplayName()`，所以改显示名就够了。不需要监听 `ServerChatEvent`，也不会破坏聊天签名。副作用是死亡消息、原版进度广播里也会带上称号，这是预期行为。
- **不使用计分板队伍前缀**：一个玩家只能加入一个队伍，会和以后的组队、PvP 阵营功能冲突。
- **头顶名牌同步协议**：新增 S2C 包 `S2CTitleSync(entityId, @Nullable Component)`，注册在称号模块自有的通道 `miningdim:title`（`title.network.TitleNetwork`）上，**不进** `MiningNetwork` 主通道：主通道归 `wok-core`，从那里登记称号包会形成 `wok-core → wok-title` 的反向依赖（精英怪体型包就是这样欠下 D003 的），也会挪动主通道既有包的编号。发送时机：
  - `PlayerEvent.StartTracking`：观察者开始看到某个玩家时，发送该玩家的称号
  - 玩家登录、切换维度或重生：发送自己的称号（重生时服务端换了新实体、entityId 沿用旧值，但客户端会重建本地玩家实体并摘掉旧的缓存条目）
  - 佩戴变化、`/reload`：用 `PacketDistributor.TRACKING_ENTITY_AND_SELF` 发送给所有正在看着他的人；卸下时发 null
  - 包里直接携带 Component，客户端不需要本地称号表
  - 只在状态变化时发包，**不做逐 tick 同步**
- 客户端缓存 `Map<entityId, Component>`（`client.title.TitleClientCache`），在实体离开客户端世界时摘除，在客户端世界卸载（断线、换维度）和登出时整表清空。
- 以后可以只在名牌上为高档称号加流光动画（客户端渲染可控）。聊天和 Tab 只显示静态效果。**PENDING**，不在 P1 范围内。

---

## 六、对外接口 (DECIDED)

```java
public interface ITitleService {
    GrantResult grant(ServerPlayer player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);
    GrantResult grant(UUID player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef); // 离线补发
    GrantResult grantInTransaction(Connection tx, UUID player, ResourceLocation titleId, TitleSource source, @Nullable String sourceRef);
    void notifyGranted(ServerPlayer player, ResourceLocation titleId); // grantInTransaction 提交后由调用方补发提示
    boolean revoke(UUID player, ResourceLocation titleId);          // 若正在佩戴则同时卸下
    EquipResult equip(ServerPlayer player, @Nullable ResourceLocation titleId); // null = 不佩戴
    Set<ResourceLocation> owned(UUID player);
    Optional<ResourceLocation> equipped(UUID player);
    Optional<TitleDefinition> definition(ResourceLocation titleId);
    List<TitleDefinition> definitions();                            // 全部定义，sort 降序、id 升序
    Optional<Component> badge(ResourceLocation titleId);            // [称号]，供提示消息与面板预览
    @Nullable Component displayPrefix(UUID player);                 // 显示路径专用，只读在线缓存
    @Nullable Component displayBadge(UUID player);
}
```

- `TitleSource` 是一个枚举：`ACHIEVEMENT`、`POINT_SHOP`、`MARRIAGE`、`EVENT`、`ADMIN`，落库为小写 id。新增来源时追加枚举值。由落库 id 反查枚举的方法等 P2 "我的称号"页签真正读取 `source` 列时再加。
- 事务约定见第四章：只有 `grantInTransaction` 能并入调用方的事务；`grant`、`revoke`、`equip` 在外层事务开着时抛 `IllegalStateException`。
- `GrantResult`：`GRANTED`、`ALREADY_OWNED`、`UNKNOWN_TITLE`。`EquipResult`：`EQUIPPED`、`UNEQUIPPED`、`NOT_OWNED`、`UNKNOWN_TITLE`。
- 离线玩家也可以 `grant`（按 UUID 写库，不经过缓存），供以后婚姻、活动系统批量补发使用；玩家恰好在线时等价于在线发放。
- 获得称号时，给玩家本人发一条聊天提示："获得称号 [xxx]"，悬停可查看说明。**不做全服广播**（全服广播交给成就的原版公告）。
- `equip` 前服务端必须校验称号已拥有且定义存在，不相信客户端传入的值。

---

## 七、G 面板："我的称号"页签 (DECIDED)

位于 G 面板"成就点商店"页（路由 `/achievement-shop`）的第二个页签。页面本身由成就模块负责（见成就文档第八章），这个页签的数据来自称号模块。

| action | 类型 | 说明 |
|---|---|---|
| `title.list` | 只读，可加入 batch | 返回全部称号定义（含未拥有的）、拥有状态、来源说明、当前佩戴 |
| `title.equip` | 写 | 参数 `{ titleId: string \| null }`；`null` 表示卸下 |

- 界面：卡片网格，每张卡片带预览（"玩家名 + 称号"的实际效果），当前佩戴的卡片高亮，另有一张"不佩戴"卡片。
- 未拥有的称号显示为灰色，并注明获取途径（哪个成就，或多少成就点）。
- 错误码在 `WebUiErrorCodes` 中新增 `TITLE_NOT_OWNED`、`TITLE_UNKNOWN`，并在 `webui/src/lib/errorText.ts` 中映射成玩家可读的提示。
- Java 侧在 `TitleWebUiActions.registerAll()` 中注册，由 `TitleSystem.register()` 调用。`title.list` 同时登记进 `WebUiBatchAction.BATCHABLE` 和 `webui/src/lib/batch.ts`。

---

## 八、管理员命令 (DECIDED)

命令根为 `/mtitle`，需要权限等级 2。命名参照 `/mchampion`：原版已经有 `/title` 命令，不能重名；仓库里也没有统一的 `/wok` 命令根，各模块各自注册命令根。以后可能会统一成 `/wok` 指令集，届时迁移到那里。

| 命令 | 作用 |
|---|---|
| `/mtitle grant <玩家> <称号id>` | 发放，来源记为 `ADMIN` |
| `/mtitle revoke <玩家> <称号id>` | 回收 |
| `/mtitle list <玩家>` | 查看拥有的称号和当前佩戴 |
| `/mtitle equip <玩家> <称号id\|none>` | 代玩家佩戴或卸下 |

称号 id 参数提供补全，候选来自当前已加载的定义。

- `grant`、`revoke`、`list` 的玩家参数按 GameProfile 解析，用户缓存里查得到的离线玩家同样可以补发、回收和查看；`equip` 要刷新在线显示，只接受在线玩家，而且同样校验已拥有且定义存在。
- 管理员发放的 `source_ref` 记为执行者名字；每次变更都写管理日志 `miningdim/title/admin`。

---

## 九、性能约束 (DECIDED)

1. 显示路径（`NameFormat`、`TabListNameFormat`）只读内存缓存，不查数据库。前缀 Component 按称号 id 缓存，数据包重载时清空。
2. 名牌同步只在状态变化时发包，不做逐 tick 同步。
3. 数据库只在登录、发放、回收、佩戴这几个低频点访问（登出只丢缓存，写穿缓存无需回写）。
4. Tab 名的队伍兜底重算每 20 tick 一次，只遍历佩戴着称号的在线玩家、只读缓存，结果不变时不发包。

---

## 十、测试

GameTest 放在 `com.miningdim.title.TitleGameTests`（batch `title`），使用 `testutil.TempStoreDb` 提供的临时库。P1 已覆盖：

- `grant` 幂等且不覆盖最早来源；`revoke` 正在佩戴的称号后自动卸下
- 未拥有或定义缺失时 `equip` 返回失败，且不落库
- 定义删除后持有记录保留、显示隐藏，定义恢复后显示复原
- `grantInTransaction` 事务回滚后库与缓存都没有残留（事务内读到的未提交持有不写回缓存）；连接未开事务或为 null 时拒绝，且先于定义检查；外层事务开着时 `grant`、`equip`、`revoke` 一律拒绝
- 定义校验：非法颜色、非法稀有度、色标数不对、缺 `text` 会被跳过，且不影响其余定义
- `TierPalette` 逐字渐变的首、中、尾色标与按码点拆分
- 佩戴后聊天显示名与 Tab 名带 `[称号] ` 前缀，卸下后消失
- 经真实的 `PlayerLoggedInEvent` / `PlayerLoggedOutEvent`（mock 玩家的 `placeNewPlayer` 与 `PlayerList.remove`）加载与移出缓存，登录后显示名与 Tab 名已补刷；提交的数据关库重开仍在
- 首批 14 个称号随数据包加载，稀有度与 9.8 一致，中英文键齐全
- `S2CTitleSync` 编解码往返
- 经真实 `PlayerList.respawn` 重生后卸下称号，仍向客户端广播 Tab 名恢复原版
- 队伍兜底重算：佩戴称号的玩家加入、离开队伍后 Tab 名随之更新并广播，没变化时不发包，未佩戴的玩家不受影响
- 存储侧：`MiningStoreGameTests` 覆盖停在 V4 的老库升级到 V5

未被 GameTest 覆盖、需要人工 `runClient` 双人验证的：客户端名牌渲染（`TitleNameTagRenderer`、`TitleClientCache`），GameTest 服务端是专用服务端，不加载客户端类。队伍兜底的 tick 钩子本身只是按间隔调用上面已测的 `refreshTitledTabNames`。

P2 再补：`title.list` / `title.equip` 的 WebUI 契约（仿照 `QuestWebUiGameTests`）。

---

## 十一、分期

| 期 | 内容 |
|---|---|
| P1 | 模块骨架、定义加载、存储、`ITitleService`、三处显示、同步包、管理员命令、GameTest |
| P2 | "我的称号"页签（随成就点商店页一起上线） |
| P3 | 婚姻称号接入（`Marriage_System_DesignSpec` 中规划的夫妻称号改由本系统发放）、名牌流光动画 |

## 十二、待定项

| # | 事项 | 状态 |
|---|---|---|
| 1 | G 面板浅色主题下七档加深色的具体色值 | PENDING |
| 2 | 名牌流光动画是否要做 | PENDING |
| 3 | 多子服架构下称号数据的共享方案 | PENDING（接口已预留） |
