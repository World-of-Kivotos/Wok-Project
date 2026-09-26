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
| 配置 | 服务端 `miningdim-title.toml`（赞助专属称号的校验阈值与冷却，13.3） |

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
- 加载时校验：缺少 `text`、未知 `rarity`、渐变色标不足 2 个或多于 3 个、颜色格式非法（只收 `#RRGGBB`）、`color` 与 `gradient` 同时出现、占用专属称号保留的 `miningdim:custom/` 前缀（13.2），一律跳过该条并在日志告警，不影响其余定义。
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

- 这两张表已随 P1 落地为 `MiningSchema` 的 **V5**；第十三章的赞助资格与专属称号两表追加为 **V6**（13.6）。成就系统也要在 `MiningSchema` 末尾追加迁移，两条分支谁后合并谁把自己的版本号顺延（例如成就改为 V7），已经应用过的迁移不得改动。
- 数据库访问全部经过 `TitleRepository` 接口，当前实现为 `SqliteTitleRepository`。以后改为多子服时，新增共享数据库（如 MySQL）的实现即可，业务代码不改。
- **内存缓存只保存在线玩家**：登录时加载，登出时移出。按当前规模，读写在主线程同步完成即可，不引入异步线程。缓存写穿（先落库再改缓存），停服或崩溃不丢数据，登出也无需回写。
- `grant` 按主键幂等：重复发放同一称号不会报错，也不会覆盖最早的来源。没有加载定义的称号 id 拒发（`UNKNOWN_TITLE`），防止拼错的 id 落库。
- 调用方需要把"扣成就点"和"发称号"放进同一个事务时，`ITitleService` 提供 `grantInTransaction(Connection, ...)` 这一变体。成就点商店购买称号就是这种情况。
  - 传入的连接必须已关闭自动提交，否则直接抛 `IllegalStateException`（没开事务就谈不上"同一个事务"）；传 null 抛 `IllegalArgumentException`。这项校验先于其他一切检查（包括称号定义是否存在），接线错误不会被 `UNKNOWN_TITLE` 这种正常拒发结果盖过去。
  - 它不提交、不回滚，也不发聊天提示；事务提交后如需提示，调用方再调 `notifyGranted`。
  - 它不往缓存里加称号（外层可能回滚），而是把该玩家的持有集合标记为待重载，下次读取时从库里重新加载。若这次读取发生在外层事务尚未提交时（例如商店在同一事务里查"是否已拥有"），结果只返回、不写回缓存。
- **只有 `grantInTransaction` 可以并入调用方的事务。**`grant`（两个重载）、`revoke`、`equip` 自带写穿缓存、聊天提示和显示刷新，这些都立即生效、无法随外层回滚撤销；共享连接上正开着事务时，它们直接抛 `IllegalStateException`。调用方在事务里用 `grantInTransaction`，提交后再 `notifyGranted` / `equip` / `revoke`（例如"买下即佩戴"要在提交之后调 `equip`）。第十三章的赞助资格与专属称号写操作同理，一律拒绝在外层事务里执行。

---

## 五、三处显示 (DECIDED)

显示用的 Component 统一由 `TitleRenderer` 生成，数据包称号按称号 id 缓存：聊天和 Tab 用前缀，格式为 `[称号] `；名牌单独一行居中，用不带尾随空格的徽记（`[称号]`），两者样式完全相同。专属称号（第十三章）原样显示、不加方括号，定义随玩家修改而变，所以渲染结果缓存在该玩家的在线条目里，修改或登录时重算，不进按 id 的缓存。

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
    Optional<TitleDefinition> definition(ResourceLocation titleId); // 专属称号按记录动态生成
    List<TitleDefinition> definitions();                            // 全部数据包定义，sort 降序、id 升序
    Optional<Component> badge(ResourceLocation titleId);            // [称号]（专属称号原样），供提示消息与面板预览
    @Nullable Component displayPrefix(UUID player);                 // 显示路径专用，只读在线缓存
    @Nullable Component displayBadge(UUID player);

    // 赞助专属称号（第十三章）
    SponsorStatus grantSponsor(UUID player, @Nullable Integer days, String issuer); // days 为 null = 永久
    boolean revokeSponsor(UUID player, String issuer);
    CustomTitleInfo customTitleInfo(UUID player);                   // 资格、记录、下次可修改时间
    List<CustomTitleInfo> sponsors();
    CustomTitleResult previewCustomTitle(UUID player, CustomTitleDraft draft);
    CustomTitleResult setCustomTitle(ServerPlayer player, CustomTitleDraft draft);
    CustomTitleResult adminSetCustomTitle(UUID player, CustomTitleDraft draft, String issuer);
    CustomAdminResult resetCustomTitle(UUID player, String issuer);
    CustomAdminResult setCustomTitleLocked(UUID player, boolean locked, String issuer);
    CustomAdminResult clearCustomTitleCooldown(UUID player, String issuer);
}
```

- `TitleSource` 是一个枚举：`ACHIEVEMENT`、`POINT_SHOP`、`MARRIAGE`、`EVENT`、`ADMIN`，落库为小写 id。新增来源时追加枚举值。由落库 id 反查枚举的方法等 P2 "我的称号"页签真正读取 `source` 列时再加。
- 事务约定见第四章：只有 `grantInTransaction` 能并入调用方的事务；`grant`、`revoke`、`equip` 以及第十三章的各个写操作在外层事务开着时抛 `IllegalStateException`。
- `GrantResult`：`GRANTED`、`ALREADY_OWNED`、`UNKNOWN_TITLE`、`NOT_GRANTABLE`（专属称号 id，13.2）。`EquipResult`：`EQUIPPED`、`UNEQUIPPED`、`NOT_OWNED`、`UNKNOWN_TITLE`。
- `owned` 在专属称号有记录且赞助资格有效时把它排在最前；`revoke` 传入专属称号 id 直接返回 false、不做任何改动（处置走 `resetCustomTitle` / `revokeSponsor`）；`equip` 传入别人的专属称号 id 一律返回 `NOT_OWNED`，不去查对方的记录，不暴露对方有没有专属称号。`definitions()` 只含数据包定义，不含按玩家生成的专属称号；专属称号的 `TitleDefinition` 没有稀有度（`rarity()` 为 null，`isCustom()` 为 true）。
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

实现口径（P2，2026-09-26）：

- `wok-title` 的依赖加上 `wok-webui`（第二章表格原先就预告了这一步）。
- `title.list` 回 `{equipped, palette, titles, custom}`：`titles` 是全部数据包定义（含未拥有的），按 `definitions()` 的展示顺序，每行带 `rarity`、`sort`、`owned`、`equipped`、`badge` 与 `description`；`palette` 是 `TierPalette` 七档的色标与粗体（编辑器的"套用档位配色"用它，前端不另抄色值）；`custom` 见 13.8 的实现口径。持有记录还在、定义已被删除的称号不出现（第三章口径）。
- 徽记与说明经 `webui.server.WebUiTextJson` 拍平成"片段 + 颜色 + 粗体"：单色档是 `[` + 翻译键 + `]` 整段上色（客户端经 client.i18n 按自己的语言解），渐变档是服务端已经逐字上色的中文，与聊天、Tab、名牌里的样子逐字相同。前端照着画，不自己算渐变。
- "注明获取途径"取定义里的 `description`（来自隐藏成就的称号本来就写成"由一项隐藏成就获得"，不泄露成就名）；称号若在成就点商店上架，页面再从 `achievement.pointShop` 的商品里查出价格一并显示。称号模块不因此引用成就模块，第六章说的"由落库 id 反查来源枚举"也仍未需要。
- `title.equip {titleId: string | null}`：`null` 卸下；`TITLE_NOT_OWNED`（含别人的专属称号 id）、`TITLE_UNKNOWN` 带 params `titleId`；缺键、写不成资源 id 的回 `INVALID_REQUEST`（field=titleId）。回执 `{equipped}`。
- 页面：成就点商店页的"我的称号"页签，卡片网格，每张卡片是一条深底的"游戏内效果"预览（徽记 + 空格 + 玩家名，即 `TitleRenderer` 的 prefix），另有一张"不佩戴"卡片；当前佩戴的卡片高亮，未拥有的压灰并写明获取途径。
- GameTest：`TitleWebUiGameTests`（batch `title_webui`，5 条），覆盖列表形状与两档徽记、佩戴与每一种拒绝、预览、提交跟随自助开关、只有 `title.list` 能进批。

---

## 八、管理员命令 (DECIDED)

命令根为 `/mtitle`。本章的子命令都需要权限等级 2；第十三章新增的玩家子命令（`mine`、`wear`、`custom`）对所有人开放，所以命令根本身不设权限，由各子命令分别判断。命名参照 `/mchampion`：原版已经有 `/title` 命令，不能重名；仓库里也没有统一的 `/wok` 命令根，各模块各自注册命令根。以后可能会统一成 `/wok` 指令集，届时迁移到那里。

| 命令 | 作用 |
|---|---|
| `/mtitle grant <玩家> <称号id>` | 发放，来源记为 `ADMIN` |
| `/mtitle revoke <玩家> <称号id>` | 回收 |
| `/mtitle list <玩家>` | 查看拥有的称号和当前佩戴 |
| `/mtitle equip <玩家> <称号id\|none>` | 代玩家佩戴或卸下 |

称号 id 参数提供补全，候选来自当前已加载的定义。

- `grant`、`revoke`、`list` 的玩家参数按 GameProfile 解析，用户缓存里查得到的离线玩家同样可以补发、回收和查看；`equip` 要刷新在线显示，只接受在线玩家，而且同样校验已拥有且定义存在。
- `grant`、`revoke` 遇到专属称号 id（`miningdim:custom/...`）直接拒绝并提示改用第十三章的命令。
- 管理员发放的 `source_ref` 记为执行者名字；每次变更都写管理日志 `miningdim/title/admin`。赞助资格与专属称号的变更由服务层统一写 `miningdim/title/custom`（13.4），不重复写管理日志。

---

## 九、性能约束 (DECIDED)

1. 显示路径（`NameFormat`、`TabListNameFormat`）只读内存缓存，不查数据库。前缀 Component 按称号 id 缓存，数据包重载时清空。
2. 名牌同步只在状态变化时发包，不做逐 tick 同步。
3. 数据库只在登录、发放、回收、佩戴以及赞助资格 / 专属称号变更这几个低频点访问（登出只丢缓存，写穿缓存无需回写）。在线玩家的赞助资格、专属称号记录及其渲染结果随其他称号数据一起缓存。
4. Tab 名的队伍兜底重算每 20 tick 一次，只遍历佩戴着称号的在线玩家、只读缓存，结果不变时不发包。
5. 赞助到期巡检每 1200 tick（60 秒）一次，只读缓存，只看资格已失效的两类在线玩家：上次检查时资格还有效的，以及仍戴着自己专属称号的（上一次卸下写库失败留下的，这里重试）；只有真的卸下时才写库。巡检逐人兜住异常，一名玩家写库失败不影响其他人。

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
- 存储侧：`MiningStoreGameTests` 覆盖停在 V4 的老库升级到最新（称号持有、佩戴与赞助两表，含主键、NULL 永久资格与 `locked` 默认值），以及停在 V5（P1）的库补跑 V6 建出赞助两表、既有持有与佩戴行不变

P1.5 的赞助专属称号用例在 `com.miningdim.title.CustomTitleGameTests`（batch `title_custom`），逐条对应 13.9，见该节。

未被 GameTest 覆盖、需要人工 `runClient` 双人验证的：客户端名牌渲染（`TitleNameTagRenderer`、`TitleClientCache`），GameTest 服务端是专用服务端，不加载客户端类。队伍兜底与赞助到期巡检的 tick 钩子本身只是按间隔调用已测的 `refreshTitledTabNames` 与 `checkSponsorExpiry`（巡检逐人调用、逐人兜住异常）。

P2 再补：`title.list` / `title.equip` 的 WebUI 契约（仿照 `QuestWebUiGameTests`）。

---

## 十一、分期

| 期 | 内容 |
|---|---|
| P1 | 模块骨架、定义加载、存储、`ITitleService`、三处显示、同步包、管理员命令、GameTest |
| P1.5 | 赞助专属称号（第十三章）：赞助资格、自定义校验、冷却、到期、管理员处置、玩家命令、GameTest |
| P2 | "我的称号"页签（随成就点商店页一起上线），含赞助玩家的专属称号编辑卡片 |
| P3 | 婚姻称号接入（`Marriage_System_DesignSpec` 中规划的夫妻称号改由本系统发放）、名牌流光动画 |

## 十二、待定项

| # | 事项 | 状态 |
|---|---|---|
| 1 | G 面板浅色主题下七档加深色的具体色值 | DECIDED（2026-09-26）：`webui/src/styles/index.css` 的 `.light` 块。每个色标在 OKLCH 里保持色相、压低亮度（超出 sRGB 时收一点彩度），直到对 `#FFFFFF` / `#FAFAFA` / `#F0F0F0` 三种亮色表面的 WCAG 对比度都不低于 4.5:1（实测 4.50 ~ 5.18）。铜 `#9F5D21`；银 `#686E74`；金 `#876903`；白金 `#6D6D6D → #796C49 → #886901`；钻石 `#087982 → #406DB7 → #8D55B2`；大师 `#8F42E5 → #B227D0 → #C92289`；传说 `#DB051E → #AA5702 → #866A00`。变量名 `--tier-<档>`，渐变档另有 `--tier-<档>-from` / `-via` / `-to`，单值变量取中间色标。暗色档直接用 3.1 节的游戏内色值。只用于页面装饰（档位名、卡片色带）；"游戏内效果"预览恒为深底并用服务端下发的原值 |
| 2 | 名牌流光动画是否要做 | PENDING |
| 3 | 多子服架构下称号数据的共享方案 | PENDING（接口已预留） |
| 4 | 赞助专属称号违禁词表的初始内容 | TODO（服主补充到 `miningdim-title.toml` 的 `custom.bannedWords`，可热改；当前默认值即 13.3 列出的六个词） |

---

## 十三、赞助专属称号 (DECIDED，2026-09-26)

赞助玩家可以拥有**一个**专属称号，文字、外框、颜色都按玩家的意思定。它和成就称号一样只是外观，不带任何属性。按 Mojang 商用准则，纯外观的赞助回馈是允许的。

### 13.1 服主拍板

| 事项 | 决定 |
|---|---|
| 赞助资格来源 | **管理员手动发放**，可以设有效期 |
| 设置方式（2026-09-26 补充） | **当前由管理员代设置**：玩家赞助后告诉管理员想要的称号，管理员用 `custom admin set` 设置。玩家自助提交由配置 `selfServiceEnabled` 控制，**默认关闭**，以后改为自助时打开即可，不用改代码。玩家始终可以预览 |
| 到期处理 | 称号保留、自动卸下，不能佩戴也不能修改；续期后恢复原样 |
| 自定义限制 | 按本章默认值：长度、字符集、违禁词、亮度下限、7 天冷却（冷却只约束玩家自助提交） |
| 违禁词 | **从宽**（2026-09-26 补充）：服主有人工审查，默认词表只挡冒充服务器身份的词，不收 OP、GM 这类容易误伤的短词。管理员代设置本来就跳过违禁词 |
| 外框 | **完全由玩家自定义**，系统不强加 `[ ]`，玩家写什么就显示什么 |

### 13.2 在称号体系里的位置

- 专属称号的 id 固定为 `miningdim:custom/<玩家UUID小写>`，定义不来自数据包，而是**按玩家从数据库动态生成**。定义查询、持有判断、佩戴、三处显示、名牌同步都走现有通路。
- **持有条件**：有专属称号记录，而且赞助资格有效（永久，或者未过期）。资格失效时，这个称号不出现在持有集合里，但记录保留。
- **显示格式**：原样显示玩家写的文字，**不加方括号**。前缀是 `文字 `，名牌是 `文字`。颜色和粗体按玩家的设置，渐变复用 `TierPalette` 的逐字渐变算法。
- 专属称号没有稀有度。排序固定排在最前面，比传说档还高。
- 不允许冒用：`grant`、`/mtitle grant` 不能发放 `miningdim:custom/*`，专属称号只能通过本章的流程产生。
- 实现口径：`miningdim:custom/` 整个前缀都保留——数据包里占用它的定义被加载器跳过；`grant`（两个重载）和 `grantInTransaction` 对它一律返回 `NOT_GRANTABLE`（后缀不是 UUID 也一样，事务变体仍先校验事务）；`revoke` 对它不做任何改动。动态定义的 `sort` 为 `TitleDefinition.CUSTOM_SORT`（`Integer.MAX_VALUE`），说明文字统一为"赞助玩家的专属称号"。持有集合按当前时间实时推导，专属称号在其中排第一。

### 13.3 自定义校验（全部在服务端完成，阈值写进配置）

| 项 | 默认规则 |
|---|---|
| 长度 | 按码点计，总长 1~10。其中"内容字符"（汉字、假名、字母、数字）1~8 个，其余 2 个额度留给外框或装饰符号 |
| 字符集 | 汉字、平假名、片假名、拉丁字母、数字、半角空格（不能在首尾，不能连续出现），以及一份符号白名单（各种括号 `[]【】〔〕「」『』《》〈〉()（）<>`，以及 `★☆◆◇♦♥♠♣✦✧·・~-_!?！？`）。这份白名单写进配置 |
| 禁止 | `§` 格式码、换行、控制字符、零宽字符、私用区字符、emoji（原版字体渲染不了） |
| 违禁词 | 可配置列表，默认只有 管理、服主、官方、客服、admin、owner 六个（从宽，见 13.1）。匹配前先做归一化：转小写、全角转半角、去掉空格和符号，然后按子串匹配，防止"管 理""Ａｄｍｉｎ"这类绕过 |
| 颜色 | 单色 `#RRGGBB`，或 2~3 个色标的渐变 |
| 亮度下限 | 每个色标、以及渐变实际分给每个字的颜色，相对亮度（WCAG 定义）都不低于 0.18，约等于对黑色背景的对比度 ≥ 4.6:1，保证在聊天框和 Tab 的深色背景上看得清 |
| 粗体 | 可选 |

校验失败时，逐条告诉玩家哪里不合格，例如"第 3 个字符不在允许范围内""含违禁词""颜色 #1A1A1A 太暗"。

实现口径：

- 阈值在服务端配置 `miningdim-title.toml` 的 `custom` 段：`maxLength`、`maxContentChars`、`allowedSymbols`、`bannedWords`、`minLuminance`、`customEditCooldownDays`、`selfServiceEnabled`（默认 false）。由 `TitleSystem` 走标准的 `registerConfig` 注册（GameTest 下由 `GameTestConfigWatchGuard` 统一摘掉监视器），每次校验实时读取，热改即生效。校验器 `CustomTitleValidator` 是纯函数，只认阈值快照 `CustomTitleRules`。
- 内容字符：汉字（Han 脚本的字母）、平假名、片假名（含长音符 `ー`）、ASCII 与全角两套拉丁字母和数字。带附加符号的拉丁字母、小型大写、上标之类的变体，以及西里尔、希腊等形近字母一律不收：它们与 ASCII 字母形近，NFKC 又不都能归一，收下就给违禁词留了后门。
- 判定顺序：`§`、控制字符（含换行）、格式字符（零宽空格 / 连接符、BOM、方向标记等）、行段分隔符、变体选择符、私用区字符无条件禁止，写进白名单也不放行；然后是半角空格规则；再放行内容字符与白名单符号；其余字符落在 emoji 区段（U+1F000..U+1FAFF、U+2600..U+27BF 以及散落的常见 emoji）报 emoji，否则报"不在允许范围内"。白名单里的 `★♥` 等虽在 emoji 区段内，照常放行。全角空格、不换行空格不是半角空格，按"不在允许范围内"处理。
- 违禁词归一化：NFKC（全角转半角、兼容字转标准字）→ 转小写 → 只保留字母和数字。词表两边做同一套归一化后按子串匹配。代价是短英文词（如 `op`）会顺带拦下含这些字母组合的单词（如 `shop`），所以默认词表不收这类短词，配置注释里也写明了。
- 笔画分隔：有一类字本身算内容字符、看上去却只是一笔或一个记号——长音符 `ー`、单笔画汉字 `一丨丶丿乀乁乛亅` 与片假名 `ノ`、重复记号 `々〻ゝゞヽヾ`。它们照常可以写进称号，但夹在违禁词中间起的是分隔作用（`管ー理`、`服一主`、`adーmin`），所以称号这一侧归一化后再去掉这些字比一次，任一次命中即算含违禁词。词表一侧不去掉：服主写进词表的词含这些字时（如 `一哥`）仍按原样匹配，不会被放宽成更短的词。ASCII 的 `1`、`l`、`I` 不在此列（去掉它们会误伤普通英文名），这类写法交给管理员的锁定与代设置处理。
- 渐变过渡色：两个各自够亮的色标之间，sRGB 线性插值出来的过渡色可能暗得多（例如 `#FF0000` 到 `#008A00`，两端都过线，中段却是发褐的 `#804500`，亮度约 0.088）。校验按渲染用的同一份插值（`TierPalette.gradientColors`）核对这段文字实际分到的每个颜色，空格不计；只有色标本身都合格时才查，报第一个过暗的字。内置色板的插值不变。
- 报告粒度：每条规则一次最多报一条，字符类规则报第一个出问题的位置（按码点从 1 数）；过暗的色标逐个报告（最多 3 条），渐变过渡色报第一个过暗的字；多条规则同时不合格时全部报告。提示文案的翻译键为 `title.miningdim.custom.invalid.<规则>`；文案里不能直接写 `§`（客户端会把它连同后一个字符当格式码吞掉），格式码一律写成 `U+00A7`。

### 13.4 冷却与生效

- 第一次设置不受冷却限制。之后每次成功修改，都要距离上次修改满 **7 天**（`customEditCooldownDays`，默认 7）。
- 提交后先过自动校验，通过就**立即生效**：写库、刷新缓存；如果正在佩戴，三处显示立即刷新。每次修改都写审计日志 `miningdim/title/custom`，记录修改前后的文字和样式。
- 预览不消耗冷却，也不写库，只把效果回显给玩家本人。
- 实现口径：提交依次判断资格、自助提交是否开启（关闭时答"由管理员设置"）、锁定、冷却、校验，任何一步被拒都不写库、不消耗冷却；冷却中照样可以预览，回显里附带下次可修改时间；自助提交关闭时这一行换成可复制的参数（玩家本来就不能自己改，报冷却没有意义），玩家的 `custom info` 也不报下次可修改时间，改为说明由管理员设置，资格发放和"还没有专属称号"的提示同样指向"预览后找管理员"。管理员的 `sponsor info` 照报冷却状态。冷却起点就是 `updated_at`，即玩家本人最近一次成功修改的时间，管理员"清除冷却"把它置 0。管理员代设置既不开始也不清除玩家的冷却：沿用原记录的 `updated_at`，新建的记录记为 0，所以管理员预先备好的记录不会挡住玩家自己的第一次设置；锁定状态保持不变。要禁止玩家再改，用锁定（13.7）。
- 审计日志由服务层统一写（任何入口的修改都不会漏记）：执行者、是否管理员代设、目标名与 UUID、修改前后的文字 / 颜色 / 粗体；赞助资格的发放、续期、撤销、到期卸下，以及清空、锁定、解锁、清除冷却也记在 `miningdim/title/custom`。

### 13.5 赞助资格

- 由管理员手动发放。`grant` 不带天数就是永久；带天数时，从"当前到期时间和现在二者中较晚的那个"开始往后延长。所以对还没到期的玩家续期会顺延，不会把剩余天数吞掉。
- 到期检查的时机：登录时；佩戴或修改时；以及在线期间每 60 秒检查一次，只看资格已失效、还需要处理的在线玩家（第九章第 5 条），只读缓存。
- 到期或被撤销时：如果正在佩戴专属称号，自动卸下并提示玩家本人；从持有集合中移除；专属称号记录保留。
- 续期：称号立即重新出现在持有集合里，但**不会自动重新佩戴**，玩家自己再戴上。
- 实现口径：
  - 到期那一毫秒起（`now >= expires_at`）即失效。持有集合按当前时间实时推导，到期后立刻不含专属称号；正佩戴着的，最多再显示到下一次巡检（60 秒）才被卸下。
  - 卸下要写库。写库失败（例如 `SQLITE_BUSY`、磁盘写满）时缓存里仍戴着失效的专属称号，巡检会一直重试到卸下落库为止——包括登录时就没卸成的（登录钩子对这种失败只记日志、照常放玩家进服）；重试成功时照常提示一次。
  - 已是永久资格时，带天数的发放不会把它降级成限期（要改成限期先撤销再发放）。天数范围 1~36500。发放或续期时，在线玩家收到资格有效期的提示。
  - 撤销直接删除资格行。在线玩家立即按到期处理；不在线的，下次登录时的到期检查再卸下。
  - 提示口径：在线期间资格从有效变为失效只提示一次（正在佩戴时说明已自动卸下）；登录时发现早已过期，只有真的卸下了才提示，不在每次登录时重复打扰。

### 13.6 存储

专属称号和赞助资格的表作为 **V6** 追加在 `MiningSchema` 末尾。V5（持有、佩戴两表）已随 P1 的提交落地，跑过那一版的库停在 `user_version=5`；把这两张表并进 V5 的话，这些库永远不会补建它们，称号系统会在每次登录读赞助资格时因缺表失败。另开 V6 后这类库开服时自动补跑 V6，不需要删库。

```sql
CREATE TABLE title_sponsor (
  player_uuid TEXT PRIMARY KEY,
  granted_by  TEXT NOT NULL,
  granted_at  INTEGER NOT NULL,
  expires_at  INTEGER            -- NULL = 永久
);
CREATE TABLE title_custom (
  player_uuid  TEXT PRIMARY KEY,
  text         TEXT NOT NULL,     -- 含玩家自选的外框符号
  colors       TEXT NOT NULL,     -- 逗号分隔的 1~3 个 #RRGGBB
  bold         INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,  -- 玩家本人最近一次成功修改（冷却起点）
  locked       INTEGER NOT NULL DEFAULT 0,
  locked_by    TEXT
);
```

以上都经过 `TitleRepository`，和第四章一样为多子服预留。

### 13.7 命令

`/mtitle` 命令根改为所有人可见，每个子命令各自判断权限。

**玩家命令**（任何玩家都能用，其中 `custom` 系列要求赞助资格有效）：

| 命令 | 作用 |
|---|---|
| `/mtitle mine` | 列出自己拥有的称号和当前佩戴。G 面板"我的称号"页签上线前的替代入口 |
| `/mtitle wear <称号id\|none>` | 佩戴自己拥有的称号，或者卸下 |
| `/mtitle custom preview <颜色> <粗体> <文字>` | 预览，不消耗冷却。自助提交关闭时，额外给出一串可点击复制的参数，玩家发给管理员即可 |
| `/mtitle custom set <颜色> <粗体> <文字>` | 提交专属称号，受 7 天冷却限制。**自助提交关闭（默认）时拒绝**，提示由管理员设置 |
| `/mtitle custom info` | 查看自己的专属称号、赞助到期时间、下次可修改时间 |

参数格式：`<颜色>` 是 `#RRGGBB`，或者用逗号分隔的 2~3 个色标，例如 `#FF3D3D,#FFD23F`；`<粗体>` 是 `true` 或 `false`；`<文字>` 放在最后，吃掉剩余的全部输入，所以可以包含空格。

**管理员命令**（权限等级 2）：

| 命令 | 作用 |
|---|---|
| `/mtitle sponsor grant <玩家> [天数]` | 发放或续期赞助资格；不写天数就是永久 |
| `/mtitle sponsor revoke <玩家>` | 撤销资格（效果等同到期） |
| `/mtitle sponsor info <玩家>` / `list` | 查看某人的资格，或列出全部赞助玩家 |
| `/mtitle custom admin set <玩家> <颜色> <粗体> <文字>` | 代为设置，**当前的主要设置方式**。不受冷却限制，也不改变玩家自己的冷却，但仍然过字符集和亮度校验。违禁词对管理员放行。目标没有有效赞助资格时照样保存，但提醒管理员"暂不生效，先发放资格" |
| `/mtitle custom admin reset <玩家>` | 清空专属称号，同时清除冷却，玩家可以立即重新设置 |
| `/mtitle custom admin lock <玩家>` / `unlock <玩家>` | 禁止或恢复该玩家修改专属称号，已有的专属称号保持不变 |
| `/mtitle custom admin cooldown <玩家>` | 清除冷却 |

原先的 `grant`、`revoke`、`list`、`equip` 管理员命令不变，仍然要求权限等级 2。

实现口径：

- `<颜色> <粗体> <文字>` 收在一个贪婪字符串参数里，由 `CustomTitleDraft.parse` 拆分：`#` 和 `,` 都不是 Brigadier 不加引号字符串允许的字符，拆成三个参数就没法照上面的写法直接输入。粗体不分大小写；文字原样保留，首尾或连续空格交给校验报告；缺段或粗体写错时回一条用法说明。
- 玩家子命令（`mine`、`wear`、`custom preview / set / info`）要求执行者是玩家，控制台看不到。`custom info` 不要求资格有效，资格失效的玩家也能查到到期时间和保留着的称号；`preview`、`set` 在执行时判断资格，没有有效资格时明确提示。`wear` 的补全候选是自己拥有的称号；`mine` 的每一项点击即填入 `wear` 命令。
- 玩家命令只给玩家看他自己的东西：`wear` 别人的专属称号 id 一律答"你还没有称号 …"，只回显 id 原文、不渲染对方的称号，也不因对方有没有记录而给出不同答复（资格失效后隐藏的、管理员预先备好的称号都不会被人试探出来）；`custom info` 在锁定时只说"已被管理员锁定"，与其他面向玩家的处置提示一样不点名管理员，执行者只在管理员的 `sponsor info` 里显示。
- 管理员子命令的玩家参数同样按 GameProfile 解析，离线玩家也能处置。
- `custom admin set` 不要求对方有赞助资格：记录可以先备好，资格生效后才出现在持有集合里。它不改变玩家的修改冷却（13.4）。成功时在线玩家收到提示。
- `custom admin reset` 删除整条记录（锁定状态存在同一行上），所以锁定中的记录拒绝清空，须先 `unlock`，以免锁定被悄悄解除。正在佩戴时一并卸下，在线玩家收到提示。清空是宽松处置：连冷却一起清掉，玩家可以立即重新设置。
- 处置不当内容的做法是"代设置 + 锁定"：用 `custom admin set` 覆盖成中性文字，再 `lock`（顺序不限，代设置不会解除锁定），玩家就既看不到原内容、也改不回去。不要先 `reset` 再 `lock`——清空删掉了整条记录，之后已经没有可锁的了。`reset_locked` 与没有记录时的 `lock` 提示都会指向这个做法。
- `lock`、`unlock`、`cooldown` 要求已有记录；状态本来就是目标值（已锁定再锁定、不在冷却中）时提示未改动。
- `sponsor list` 列出全部资格行，含已过期的（标注到期时间）。

### 13.8 G 面板（P2）

"我的称号"页签对赞助玩家额外显示一张编辑卡片：
- 可以输入文字、选择颜色（单色或渐变）和粗体，并实时预览"玩家名 + 称号"的效果
- 显示下次可修改的时间和赞助到期时间

预览在前端本地渲染，提交时服务端按 13.3 重新校验。需要的接口：`title.customPreview`（只读，返回校验结果）、`title.customSet`（写）。

实现口径（P2，2026-09-26）：

- 只有赞助资格行存在（有效或已过期）时才显示这张卡片。`title.list` 的 `custom` 块：`titleId`（`miningdim:custom/<uuid>`）、`selfServiceEnabled`、`sponsor`（`permanent` / `expiresAt` / `active`，从未发放或已撤销为 null）、`record`（`text` / `colors` / `bold` / `locked` / `badge`，未设置为 null；锁定只说"已被管理员锁定"，不点名管理员）、`owned`、`equipped`、`nextEditAt`。
- 编辑器：文字、1~3 个 `#RRGGBB` 色标、粗体开关，另可一键套用七档色板。本地预览按 `TierPalette.gradientColors` 同一份插值（float 精度）逐字上色，停手 400 ms 后调 `title.customPreview` 取权威结论。
- `title.customPreview {colors, bold, text}`：没有有效资格回 `CUSTOM_TITLE_NOT_SPONSOR`；否则回 `status`（`VALID` / `INVALID`）、`violations`（规则名 + 参数，前端按 `title.miningdim.custom.invalid.*` 的中文逐条显示）、`badge`（渲染好的徽记）、`spec`（规范化后的 `<颜色> <粗体> <文字>`，与 `/mtitle custom preview` 同写法）、`adminCommand`（`/mtitle custom admin set <玩家名> <spec>`）、`nextEditAt`、`selfServiceEnabled`。不写库、不消耗冷却。入参形状错（colors 不是字符串数组、文字超过 256 个码元、色标多于 8 个或单个长于 32 个字符）回 `INVALID_REQUEST`。
- 页面跟随 `selfServiceEnabled`：关闭（当前默认，管理员代设置）时不给提交按钮，改为"复制参数发给管理员"，复制的就是 `adminCommand`（异步剪贴板失败时退回选区复制，命令本身始终显示在页面上可以手动选中），且不展示下次可修改时间；打开时给"提交"，显示下次可修改时间。
- `title.customSet` 与命令同一条服务路径（`setCustomTitle`），拒绝各有稳定码：`CUSTOM_TITLE_NOT_SPONSOR`、`CUSTOM_TITLE_SELF_SERVICE_DISABLED`（自助提交关闭时的明确拒绝）、`CUSTOM_TITLE_LOCKED`、`CUSTOM_TITLE_ON_COOLDOWN`（params nextEditAt）、`CUSTOM_TITLE_INVALID`（params count 与逗号分隔的 rules）。成功回 `{status: APPLIED, titleId, badge, nextEditAt}`。
- 赞助资格已失效时卡片仍显示保留的记录与到期时间，但编辑器换成一句说明（失效期间不能佩戴也不能修改，续期后恢复）。
- 中文输入：MCEF 里的页面输入框拿不到游戏的输入法（WebUI 中文输入方案仍未定，见 WebUI_ChineseIME_DesignSpec），编辑器的文字框目前只能粘贴或打英文；预览与复制参数不受影响。

### 13.9 测试

- 校验：长度（含外框额度）、字符集白名单、禁止字符、违禁词归一化绕过、亮度下限、色标数量，每条规则都有通过和不通过的用例
- 冷却：第一次设置不受限；冷却期内修改被拒绝；管理员清除冷却后可以修改；预览不消耗冷却
- 资格：永久发放；带天数发放；续期顺延；到期后自动卸下并从持有集合移除、记录保留；续期后恢复持有但不自动佩戴；撤销等同到期
- 非赞助玩家不能使用 `custom set`；`grant` 不能发放 `miningdim:custom/*`
- 管理员 reset、lock、unlock、代设置（跳过冷却和违禁词，但仍校验字符集）
- 佩戴专属称号后，聊天和 Tab 前缀按原样显示，不加方括号；渐变和粗体正确
- 在线定时到期检查（直接调用检查方法）
- V5 → V6 升级测试：停在 V5 的库补建两张新表

实现：`com.miningdim.title.CustomTitleGameTests`（batch `title_custom`）逐条覆盖上列各项，另有：配置默认值与本章一致、生产构造器的阈值与冷却实时取自配置；违禁词的笔画分隔绕过与渐变过渡色的亮度；佩戴与提交修改时自己先做到期检查；离线期间到期的玩家登录时卸下，登录时卸下写库失败（把临时库切成 `PRAGMA query_only` 模拟）后由巡检重试；玩家命令不渲染他人的专属称号、不点名执行锁定的管理员；自助提交关闭（默认口径）时玩家提交被拒且不落库、预览照常并附可复制参数、各处提示都指向"预览后找管理员"、管理员代设置后可佩戴、对没有资格的玩家代设置会提醒；默认违禁词不误伤 `Shop` 这类写法；两种语言的 `title.miningdim.*` 键一一对应且文案里没有 `§`。命令用例经服务端真实的命令分发器执行；权限门按解析结果逐条核对全部管理员与玩家子命令——只看执行是否抛错测不出漏掉的权限门，因为普通玩家用选择器本身就会被原版拒绝。GameTest 服务端没有用户缓存，原版 GameProfile 参数按裸名字解析会空指针，所以命令用例用 `@a[name=…]` 选择器指定 mock 玩家。V4 → 最新、V5 → V6 两条升级在 `MiningStoreGameTests`。
